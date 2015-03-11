/*
 * Copyright © 2012-2014 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.tephra.hbase98.coprocessor;

import co.cask.tephra.Transaction;
import co.cask.tephra.TransactionCodec;
import co.cask.tephra.TxConstants;
import co.cask.tephra.coprocessor.TransactionStateCache;
import co.cask.tephra.coprocessor.TransactionStateCacheSupplier;
import co.cask.tephra.hbase98.Filters;
import co.cask.tephra.persist.TransactionSnapshot;
import co.cask.tephra.util.TxUtils;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CellUtil;
import org.apache.hadoop.hbase.CoprocessorEnvironment;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.Tag;
import org.apache.hadoop.hbase.client.Durability;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.OperationWithAttributes;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.coprocessor.BaseRegionObserver;
import org.apache.hadoop.hbase.coprocessor.ObserverContext;
import org.apache.hadoop.hbase.coprocessor.RegionCoprocessorEnvironment;
import org.apache.hadoop.hbase.filter.Filter;
import org.apache.hadoop.hbase.filter.FilterBase;
import org.apache.hadoop.hbase.regionserver.InternalScanner;
import org.apache.hadoop.hbase.regionserver.KeyValueScanner;
import org.apache.hadoop.hbase.regionserver.RegionScanner;
import org.apache.hadoop.hbase.regionserver.ScanType;
import org.apache.hadoop.hbase.regionserver.Store;
import org.apache.hadoop.hbase.regionserver.StoreScanner;
import org.apache.hadoop.hbase.regionserver.compactions.CompactionRequest;
import org.apache.hadoop.hbase.regionserver.wal.WALEdit;
import org.apache.hadoop.hbase.util.Bytes;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;

/**
 * {@code org.apache.hadoop.hbase.coprocessor.RegionObserver} coprocessor that handles server-side processing
 * for transactions:
 * <ul>
 *   <li>applies filtering to exclude data from invalid and in-progress transactions</li>
 *   <li>overrides the scanner returned for flush and compaction to drop data written by invalidated transactions,
 *   or expired due to TTL.</li>
 * </ul>
 *
 * <p>In order to use this coprocessor for transactions, configure the class on any table involved in transactions,
 * or on all user tables by adding the following to hbase-site.xml:
 * {@code
 * <property>
 *   <name>hbase.coprocessor.region.classes</name>
 *   <value>co.cask.tephra.hbase98.coprocessor.TransactionProcessor</value>
 * </property>
 * }
 * </p>
 *
 * <p>HBase {@code Get} and {@code Scan} operations should have the current transaction serialized on to the operation
 * as an attribute:
 * {@code
 * Transaction t = ...;
 * Get get = new Get(...);
 * TransactionCodec codec = new TransactionCodec();
 * codec.addToOperation(get, t);
 * }
 * </p>
 */
public class TransactionProcessor extends BaseRegionObserver {
  public static final List<Tag> DELETE_TAGS = ImmutableList.<Tag>of(
      new Tag(TxConstants.HBase.CELL_TAG_TYPE_DELETE, new byte[]{1}));

  private static final Log LOG = LogFactory.getLog(TransactionProcessor.class);

  private TransactionStateCache cache;
  private final TransactionCodec txCodec;

  protected Map<byte[], Long> ttlByFamily = Maps.newTreeMap(Bytes.BYTES_COMPARATOR);

  public TransactionProcessor() {
    this.txCodec = new TransactionCodec();
  }

  /* RegionObserver implementation */

  @Override
  public void start(CoprocessorEnvironment e) throws IOException {
    if (e instanceof RegionCoprocessorEnvironment) {
      RegionCoprocessorEnvironment env = (RegionCoprocessorEnvironment) e;
      Supplier<TransactionStateCache> cacheSupplier = getTransactionStateCacheSupplier(env);
      this.cache = cacheSupplier.get();

      HTableDescriptor tableDesc = env.getRegion().getTableDesc();
      for (HColumnDescriptor columnDesc : tableDesc.getFamilies()) {
        String columnTTL = columnDesc.getValue(TxConstants.PROPERTY_TTL);
        long ttl = 0;
        if (columnTTL != null) {
          try {
            ttl = Long.parseLong(columnTTL);
            LOG.info("Family " + columnDesc.getNameAsString() + " has TTL of " + columnTTL);
          } catch (NumberFormatException nfe) {
            LOG.warn("Invalid TTL value configured for column family " + columnDesc.getNameAsString() +
                       ", value = " + columnTTL);
          }
        }
        ttlByFamily.put(columnDesc.getName(), ttl);
      }
    }
  }

  protected Supplier<TransactionStateCache> getTransactionStateCacheSupplier(RegionCoprocessorEnvironment env) {
    return new TransactionStateCacheSupplier(env.getConfiguration());
  }

  @Override
  public void stop(CoprocessorEnvironment e) throws IOException {
    // nothing to do
  }

  @Override
  public void preGetOp(ObserverContext<RegionCoprocessorEnvironment> e, Get get, List<Cell> results)
    throws IOException {
    Transaction tx = getFromOperation(get);
    if (tx != null) {
      get.setMaxVersions();
      get.setTimeRange(TxUtils.getOldestVisibleTimestamp(ttlByFamily, tx), TxUtils.getMaxVisibleTimestamp(tx));
      Filter newFilter = Filters.combine(getTransactionFilter(tx, ScanType.USER_SCAN), get.getFilter());
      get.setFilter(newFilter);
    }
  }

  @Override
  public void prePut(ObserverContext<RegionCoprocessorEnvironment> e, Put put, WALEdit edit, Durability durability)
      throws IOException {
    if (put.getAttribute(TxConstants.DELETE_OPERATION_ATTRIBUTE_KEY) != null) {
      LOG.info("Received a delete for " + put);
      NavigableMap<byte[], List<Cell>> newFamilyMap = Maps.newTreeMap(Bytes.BYTES_COMPARATOR);

      for (Map.Entry<byte[], List<Cell>> entry : put.getFamilyCellMap().entrySet()) {
        List<Cell> newCells = Lists.newArrayListWithCapacity(entry.getValue().size());
        for (Cell cell : entry.getValue()) {
          LOG.info("Converting cell: " + cell);
          newCells.add(new KeyValue(CellUtil.cloneRow(cell), CellUtil.cloneFamily(cell),
              CellUtil.cloneQualifier(cell), cell.getTimestamp(), CellUtil.cloneValue(cell),
              ImmutableList.<Tag>of(new Tag(TxConstants.HBase.CELL_TAG_TYPE_DELETE, new byte[]{1}))));
        }
        newFamilyMap.put(entry.getKey(), newCells);
      }
      put.setFamilyCellMap(newFamilyMap);
    }
  }

  @Override
  public RegionScanner preScannerOpen(ObserverContext<RegionCoprocessorEnvironment> e, Scan scan, RegionScanner s)
    throws IOException {
    Transaction tx = getFromOperation(scan);
    if (tx != null) {
      scan.setMaxVersions();
      scan.setTimeRange(TxUtils.getOldestVisibleTimestamp(ttlByFamily, tx), TxUtils.getMaxVisibleTimestamp(tx));
      Filter newFilter = Filters.combine(getTransactionFilter(tx, ScanType.USER_SCAN), scan.getFilter());
      scan.setFilter(newFilter);
    }
    return s;
  }

  @Override
  public InternalScanner preFlushScannerOpen(ObserverContext<RegionCoprocessorEnvironment> c, Store store,
                                             KeyValueScanner memstoreScanner, InternalScanner scanner)
      throws IOException {
    return createStoreScanner(c.getEnvironment(), "flush", cache.getLatestState(), store,
                              Collections.singletonList(memstoreScanner), ScanType.COMPACT_RETAIN_DELETES,
                              HConstants.OLDEST_TIMESTAMP);
  }

  @Override
  public InternalScanner preCompactScannerOpen(ObserverContext<RegionCoprocessorEnvironment> c, Store store,
      List<? extends KeyValueScanner> scanners, ScanType scanType, long earliestPutTs, InternalScanner s,
      CompactionRequest request)
      throws IOException {
    return createStoreScanner(c.getEnvironment(), "compaction", cache.getLatestState(), store, scanners,
                              scanType, earliestPutTs);
  }

  protected InternalScanner createStoreScanner(RegionCoprocessorEnvironment env, String action,
                                               TransactionSnapshot snapshot, Store store,
                                               List<? extends KeyValueScanner> scanners, ScanType type,
                                               long earliestPutTs) throws IOException {
    if (snapshot == null) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("Region " + env.getRegion().getRegionNameAsString() +
                    ", no current transaction state found, defaulting to normal " + action + " scanner");
      }
      return null;
    }

    // construct a dummy transaction from the latest snapshot
    Transaction dummyTx = TxUtils.createDummyTransaction(snapshot);
    Scan scan = new Scan();
    // need to see all versions, since we filter out excludes and applications may rely on multiple versions
    scan.setMaxVersions();
    scan.setFilter(
        new IncludeInProgressFilter(dummyTx.getVisibilityUpperBound(),
            snapshot.getInvalid(),
            getTransactionFilter(dummyTx, type)));

    return new StoreScanner(store, store.getScanInfo(), scan, scanners,
                            type, store.getSmallestReadPoint(), earliestPutTs);
  }

  private Transaction getFromOperation(OperationWithAttributes op) throws IOException {
    byte[] encoded = op.getAttribute(TxConstants.TX_OPERATION_ATTRIBUTE_KEY);
    if (encoded != null) {
      return txCodec.decode(encoded);
    }
    return null;
  }

  /**
   * Derived classes can override this method to customize the filter used to return data visible for the current
   * transaction.
   *
   * @param tx the current transaction to apply
   * @param type the type of scan being performed
   */
  protected Filter getTransactionFilter(Transaction tx, ScanType type) {
    return new TransactionVisibilityFilter(tx, ttlByFamily, type);
  }

  /**
   * Filter used to include cells visible to in-progress transactions on flush and commit.
   */
  static class IncludeInProgressFilter extends FilterBase {
    private final long visibilityUpperBound;
    private final Set<Long> invalidIds;
    private final Filter txFilter;

    public IncludeInProgressFilter(long upperBound, Collection<Long> invalids, Filter transactionFilter) {
      this.visibilityUpperBound = upperBound;
      this.invalidIds = Sets.newHashSet(invalids);
      this.txFilter = transactionFilter;
    }

    @Override
    public ReturnCode filterKeyValue(Cell cell) throws IOException {
      // include all cells visible to in-progress transactions, except for those already marked as invalid
      long ts = cell.getTimestamp();
      if (ts > visibilityUpperBound) {
        // include everything that could still be in-progress except invalids
        if (invalidIds.contains(ts)) {
          return ReturnCode.SKIP;
        }
        return ReturnCode.INCLUDE;
      }
      return txFilter.filterKeyValue(cell);
    }
  }
}
