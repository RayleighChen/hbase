/**
 * Copyright 2010 The Apache Software Foundation
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hbase.regionserver;

import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.rmi.UnexpectedException;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NavigableSet;
import java.util.SortedSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.io.HeapSize;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.ClassSize;
import org.apache.hadoop.hbase.util.CollectionBackedScanner;

/**
 * The MemStore holds in-memory modifications to the Store.  Modifications
 * are {@link KeyValue}s.  When asked to flush, current memstore is moved
 * to snapshot and is cleared.  We continue to serve edits out of new memstore
 * and backing snapshot until flusher reports in that the flush succeeded. At
 * this point we let the snapshot go.
 * TODO: Adjust size of the memstore when we remove items because they have
 * been deleted.
 * TODO: With new KVSLS, need to make sure we update HeapSize with difference
 * in KV size.
 */
public class MemStore implements HeapSize {
  private static final Log LOG = LogFactory.getLog(MemStore.class);

  private Configuration conf;

  // MemStore.  Use a KeyValueSkipListSet rather than SkipListSet because of the
  // better semantics.  The Map will overwrite if passed a key it already had
  // whereas the Set will not add new KV if key is same though value might be
  // different.  Value is not important -- just make sure always same
  // reference passed.
  volatile KeyValueSkipListSet kvset;

  // Snapshot of memstore.  Made for flusher.
  volatile KeyValueSkipListSet snapshot;

  // Smallest LSN amongst all the edits in the Memstore
  volatile AtomicLong smallestSeqNumber = new AtomicLong();

  final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

  final KeyValue.KVComparator comparator;

  // Used comparing versions -- same r/c and ts but different type.
  final KeyValue.KVComparator comparatorIgnoreType;

  // Used comparing versions -- same r/c and type but different timestamp.
  final KeyValue.KVComparator comparatorIgnoreTimestamp;

  // Used to track own heapSize
  final AtomicLong size;
  volatile private long snapshotSize;

  private AtomicLong numDeletesInKvSet;
  private AtomicLong numDeletesInSnapshot;

  TimeRangeTracker timeRangeTracker;
  TimeRangeTracker snapshotTimeRangeTracker;

  MemStoreChunkPool chunkPool;
  volatile MemStoreLAB allocator;
  volatile MemStoreLAB snapshotAllocator;

  // Keep track of the total size of KVs not just the
  // bytes stored in the MSLAB
  private final AtomicLong successfullyAllocatedKvBytes;
  private final HColumnDescriptor familyDesc;

  /**
   * Default constructor. Used for tests.
   */
  public MemStore() {
    this(HBaseConfiguration.create(), KeyValue.COMPARATOR);
  }

  /**
   * Constructor which defaults the familyDescriptor to null and pass on
   * the other arguments as is.
   * @param conf
   * @param c
   * @param familyDesc
   */
  public MemStore(final Configuration conf, final KeyValue.KVComparator c) {
    this(conf, c, null);
  }

  /**
   * Constructor.
   * @param c Comparator
   */
  public MemStore(final Configuration conf,
      final KeyValue.KVComparator c, HColumnDescriptor familyDesc) {
    this.conf = conf;
    this.comparator = c;
    this.comparatorIgnoreTimestamp =
      this.comparator.getComparatorIgnoringTimestamps();
    this.comparatorIgnoreType = this.comparator.getComparatorIgnoringType();
    this.familyDesc = familyDesc;
    this.kvset = this.createNewKVSet();
    this.snapshot = this.createNewKVSet();
    timeRangeTracker = new TimeRangeTracker();
    snapshotTimeRangeTracker = new TimeRangeTracker();
    this.size = new AtomicLong(DEEP_OVERHEAD);
    this.snapshotSize = 0;
    this.numDeletesInKvSet = new AtomicLong(0);
    this.numDeletesInSnapshot = new AtomicLong(0);

    this.successfullyAllocatedKvBytes = new AtomicLong(0);

    if (conf.getBoolean(HConstants.USE_MSLAB_KEY, HConstants.USE_MSLAB_DEFAULT)) {
      this.chunkPool = MemStoreChunkPool.getPool(conf);
      this.allocator = new MemStoreLAB(conf, chunkPool);
    } else {
      this.allocator = null;
      this.chunkPool = null;
    }

    this.smallestSeqNumber.set(Long.MAX_VALUE);
  }

  void dump() {
    for (KeyValue kv: this.kvset) {
      LOG.info(kv);
    }
    for (KeyValue kv: this.snapshot) {
      LOG.info(kv);
    }
  }

  /**
   * Get the smallest LSN
   * @return
   */
  long getSmallestSeqNumber() {
    return smallestSeqNumber.get();
  }

  /**
   * Update the smallest LSN
   * @param seqNum
   */
  void updateSmallestSeqNumber(long seqNum) {
    if (seqNum < 0) {
      return;
    }

    // Do a Compare-and-Set instead of synchronized here.
    long smallestSeqNumberVal;
    do {
      smallestSeqNumberVal = smallestSeqNumber.get();
    } while (!smallestSeqNumber.compareAndSet(smallestSeqNumberVal,
             Math.min(smallestSeqNumberVal, seqNum)));
  }

  private KeyValueSkipListSet createNewKVSet() {
    int rowPrefixLength = -1;
    if (familyDesc != null) {
      rowPrefixLength = familyDesc.getRowPrefixLengthForBloom();
    }
    return new KeyValueSkipListSet(this.comparator,
        new MemstoreBloomFilterContainer.Builder(conf)
          .withRowPrefixFilter(rowPrefixLength).create());
  }

  /**
   * Creates a snapshot of the current memstore.
   * Snapshot must be cleared by call to {@link #clearSnapshot(SortedSet<KeyValue>)}
   * To get the snapshot made by this method, use {@link #getSnapshot()}
   */
  void snapshot() {
    this.lock.writeLock().lock();
    try {
      // If snapshot currently has entries, then flusher failed or didn't call
      // cleanup.  Log a warning.
      if (!this.snapshot.isEmpty()) {
        LOG.warn("Snapshot called again without clearing previous. " +
          "Doing nothing. Another ongoing flush or did we fail last attempt?");
      } else {
        if (!this.kvset.isEmpty()) {
          this.snapshot = this.kvset;
          this.kvset = createNewKVSet();

          // Reset the smallest sequence number
          this.smallestSeqNumber.set(Long.MAX_VALUE);
          this.snapshotTimeRangeTracker = this.timeRangeTracker;
          this.timeRangeTracker = new TimeRangeTracker();

          this.snapshotAllocator = this.allocator;
          // Reset allocator so we get a fresh buffer for the new memstore
          if (allocator != null) {
            this.allocator = new MemStoreLAB(conf, chunkPool);
          }

          // Reset heap to not include any keys
          this.snapshotSize = keySize();
          this.size.set(DEEP_OVERHEAD);
          this.numDeletesInSnapshot = numDeletesInKvSet;
          this.numDeletesInKvSet.set(0);
        }
      }
    } finally {
      this.lock.writeLock().unlock();
    }
  }

  /**
   * Return the current snapshot.
   * Called by flusher to get current snapshot made by a previous
   * call to {@link #snapshot()}
   * @return Return snapshot.
   * @see {@link #snapshot()}
   * @see {@link #clearSnapshot(SortedSet<KeyValue>)}
   */
  KeyValueSkipListSet getSnapshot() {
    return this.snapshot;
  }

  /**
   * The passed snapshot was successfully persisted; it can be let go.
   * @param ss The snapshot to clean out.
   * @throws UnexpectedException
   * @see {@link #snapshot()}
   */
  void clearSnapshot(final SortedSet<KeyValue> ss)
  throws UnexpectedException {
    MemStoreLAB tmpAllocator = null;
    this.lock.writeLock().lock();
    try {
      if (this.snapshot != ss) {
        throw new UnexpectedException("Current snapshot is " +
          this.snapshot + ", was passed " + ss);
      }
      // OK. Passed in snapshot is same as current snapshot.  If not-empty,
      // create a new snapshot and let the old one go.
      if (!ss.isEmpty()) {
        this.snapshot = createNewKVSet();
        this.snapshotTimeRangeTracker = new TimeRangeTracker();
      }
      if (this.snapshotAllocator != null) {
        tmpAllocator = this.snapshotAllocator;
        this.snapshotAllocator = null;
      }
      this.snapshotSize = 0;
    } finally {
      this.lock.writeLock().unlock();
    }
    if (tmpAllocator != null) {
      tmpAllocator.close();
    }
  }

  /**
   * Write an update.
   * This method should only be used by tests, since it does not specify the
   * LSN for the edit.
   * @param kv
   * @return
   */
  long add(final KeyValue kv) {
    return add(kv, -1L);
  }

  /**
   * Write an update
   * @param kv
   * @return approximate size of the passed key and value.
   */
  long add(final KeyValue kv, long seqNum) {
    long s = -1;
    this.lock.readLock().lock();
    try {
      KeyValue toAdd = maybeCloneWithAllocator(kv);
      s = heapSizeChange(toAdd, this.kvset.add(toAdd));
      timeRangeTracker.includeTimestamp(toAdd);
      this.size.addAndGet(s);
      if (toAdd.isDelete()) {
        this.numDeletesInKvSet.incrementAndGet();
      }
      updateSmallestSeqNumber(seqNum);
    } finally {
      this.lock.readLock().unlock();
    }
    return s;
  }

  private KeyValue maybeCloneWithAllocator(KeyValue kv) {
    if (allocator == null) {
      return kv;
    }

    int len = kv.getLength();
    MemStoreLAB.Allocation alloc = allocator.allocateBytes(len);
    if (alloc == null) {
      // The allocator decided not to do anything.
      return kv;
    }
    System.arraycopy(kv.getBuffer(), kv.getOffset(), alloc.getData(), alloc.getOffset(), len);
    KeyValue newKv = new KeyValue(alloc.getData(), alloc.getOffset(), len);
    newKv.setMemstoreTS(kv.getMemstoreTS());
    this.successfullyAllocatedKvBytes.addAndGet(newKv.heapSize());
    return newKv;

  }


  /**
   * Write a delete
   * @param delete
   * @return approximate size of the passed key and value.
   */
  long delete(final KeyValue delete, long seqNum) {
    return add(delete, seqNum);
  }

  /**
   * Should only be used in tests, since it does not provide a seqNum.
   * @param delete
   * @return
   */
  long delete(final KeyValue delete) {
    return delete(delete, -1);
  }

  /**
   * @param kv Find the row that comes after this one.  If null, we return the
   * first.
   * @return Next row or null if none found.
   */
  KeyValue getNextRow(final KeyValue kv) {
    this.lock.readLock().lock();
    try {
      return getLowest(getNextRow(kv, this.kvset), getNextRow(kv, this.snapshot));
    } finally {
      this.lock.readLock().unlock();
    }
  }

  /*
   * @param a
   * @param b
   * @return Return lowest of a or b or null if both a and b are null
   */
  private KeyValue getLowest(final KeyValue a, final KeyValue b) {
    if (a == null) {
      return b;
    }
    if (b == null) {
      return a;
    }
    return comparator.compareRows(a, b) <= 0? a: b;
  }

  /*
   * @param key Find row that follows this one.  If null, return first.
   * @param map Set to look in for a row beyond <code>row</code>.
   * @return Next row or null if none found.  If one found, will be a new
   * KeyValue -- can be destroyed by subsequent calls to this method.
   */
  private KeyValue getNextRow(final KeyValue key,
      final NavigableSet<KeyValue> set) {
    KeyValue result = null;
    SortedSet<KeyValue> tail = key == null? set: set.tailSet(key);
    // Iterate until we fall into the next row; i.e. move off current row
    for (KeyValue kv: tail) {
      if (comparator.compareRows(kv, key) <= 0)
        continue;
      // Note: Not suppressing deletes or expired cells.  Needs to be handled
      // by higher up functions.
      result = kv;
      break;
    }
    return result;
  }

  /**
   * @param state column/delete tracking state
   */
  void getRowKeyAtOrBefore(final GetClosestRowBeforeTracker state) {
    this.lock.readLock().lock();
    try {
      getRowKeyAtOrBefore(kvset, state);
      getRowKeyAtOrBefore(snapshot, state);
    } finally {
      this.lock.readLock().unlock();
    }
  }

  /*
   * @param set
   * @param state Accumulates deletes and candidates.
   */
  private void getRowKeyAtOrBefore(final NavigableSet<KeyValue> set,
      final GetClosestRowBeforeTracker state) {
    if (set.isEmpty()) {
      return;
    }
    if (!walkForwardInSingleRow(set, state.getTargetKey(), state)) {
      // Found nothing in row.  Try backing up.
      getRowKeyBefore(set, state);
    }
  }

  /*
   * Walk forward in a row from <code>firstOnRow</code>.  Presumption is that
   * we have been passed the first possible key on a row.  As we walk forward
   * we accumulate deletes until we hit a candidate on the row at which point
   * we return.
   * @param set
   * @param firstOnRow First possible key on this row.
   * @param state
   * @return True if we found a candidate walking this row.
   */
  private boolean walkForwardInSingleRow(final SortedSet<KeyValue> set,
      final KeyValue firstOnRow, final GetClosestRowBeforeTracker state) {
    boolean foundCandidate = false;
    SortedSet<KeyValue> tail = set.tailSet(firstOnRow);
    if (tail.isEmpty()) return foundCandidate;
    for (Iterator<KeyValue> i = tail.iterator(); i.hasNext();) {
      KeyValue kv = i.next();
      // Did we go beyond the target row? If so break.
      if (state.isTooFar(kv, firstOnRow)) break;
      if (state.isExpired(kv)) {
        i.remove();
        continue;
      }
      // If we added something, this row is a contender. break.
      if (state.handle(kv)) {
        foundCandidate = true;
        break;
      }
    }
    return foundCandidate;
  }

  /*
   * Walk backwards through the passed set a row at a time until we run out of
   * set or until we get a candidate.
   * @param set
   * @param state
   */
  private void getRowKeyBefore(NavigableSet<KeyValue> set,
      final GetClosestRowBeforeTracker state) {
    KeyValue firstOnRow = state.getTargetKey();
    for (Member p = memberOfPreviousRow(set, state, firstOnRow);
        p != null; p = memberOfPreviousRow(p.set, state, firstOnRow)) {
      // Make sure we don't fall out of our table.
      if (!state.isTargetTable(p.kv)) break;
      // Stop looking if we've exited the better candidate range.
      if (!state.isBetterCandidate(p.kv)) break;
      // Make into firstOnRow
      firstOnRow = new KeyValue(p.kv.getRow(), HConstants.LATEST_TIMESTAMP);
      // If we find something, break;
      if (walkForwardInSingleRow(p.set, firstOnRow, state)) break;
    }
  }

  /**
   * Given the specs of a column, update it, first by inserting a new record,
   * then removing the old one.  Since there is only 1 KeyValue involved, the memstoreTS
   * will be set to 0, thus ensuring that they instantly appear to anyone. The underlying
   * store will ensure that the insert/delete each are atomic. A scanner/reader will either
   * get the new value, or the old value and all readers will eventually only see the new
   * value after the old was removed.
   *
   * @param row
   * @param family
   * @param qualifier
   * @param newValue
   * @param now
   * @param seqNum The LSN for the edit.
   * @return
   */
  public long updateColumnValue(byte[] row,
                                byte[] family,
                                byte[] qualifier,
                                long newValue,
                                long now,
                                long seqNum) {
   this.lock.readLock().lock();
    try {
      // create a new KeyValue with 'now' and a 0 memstoreTS == immediately visible
      KeyValue newKv = new KeyValue(row, family, qualifier,
          now,
          Bytes.toBytes(newValue));

      long addedSize = add(newKv, seqNum);

      // now find and RM the old one(s) to prevent version explosion:
    KeyValue firstKv = KeyValue.createFirstOnRow(
        newKv.getBuffer(), newKv.getRowOffset(), newKv.getRowLength(),
        newKv.getBuffer(), newKv.getFamilyOffset(), newKv.getFamilyLength(),
        newKv.getBuffer(), newKv.getQualifierOffset(), newKv.getQualifierLength());
      SortedSet<KeyValue> ss = kvset.tailSet(firstKv);
      Iterator<KeyValue> it = ss.iterator();
      while ( it.hasNext() ) {
        KeyValue kv = it.next();

        if (kv == newKv) {
          // ignore the one i just put in (heh)
          continue;
        }
        // if this isnt the row we are interested in, then bail:
        if (0 != Bytes.compareTo(
            newKv.getBuffer(), newKv.getRowOffset(), newKv.getRowLength(),
            kv.getBuffer(), kv.getRowOffset(), kv.getRowLength())) {
          break; // rows dont match, bail.
        }

        // if the qualifier matches and it's a put, just RM it out of the kvset.
        if (0 == Bytes.compareTo(
            newKv.getBuffer(), newKv.getQualifierOffset(), newKv.getQualifierLength(),
            kv.getBuffer(), kv.getQualifierOffset(), kv.getQualifierLength())) {

          if (kv.getType() == KeyValue.Type.Put.getCode()) {
            // false means there was a change, so give us the size.
            long sz = heapSizeChange(kv, true);
            addedSize -= sz;
            this.size.addAndGet(-sz);

            it.remove();
          }
        }
      }

      return addedSize;
    } finally {
      this.lock.readLock().unlock();
    }
  }

  /*
   * Immutable data structure to hold member found in set and the set it was
   * found in.  Include set because it is carrying context.
   */
  private static class Member {
    final KeyValue kv;
    final NavigableSet<KeyValue> set;
    Member(final NavigableSet<KeyValue> s, final KeyValue kv) {
      this.kv = kv;
      this.set = s;
    }
  }

  /*
   * @param set Set to walk back in.  Pass a first in row or we'll return
   * same row (loop).
   * @param state Utility and context.
   * @param firstOnRow First item on the row after the one we want to find a
   * member in.
   * @return Null or member of row previous to <code>firstOnRow</code>
   */
  private Member memberOfPreviousRow(NavigableSet<KeyValue> set,
      final GetClosestRowBeforeTracker state, final KeyValue firstOnRow) {
    NavigableSet<KeyValue> head = set.headSet(firstOnRow, false);
    if (head.isEmpty()) return null;
    for (Iterator<KeyValue> i = head.descendingIterator(); i.hasNext();) {
      KeyValue found = i.next();
      if (state.isExpired(found)) {
        i.remove();
        continue;
      }
      return new Member(head, found);
    }
    return null;
  }

  /**
   * @return scanner on memstore and snapshot in this order.
   */
  List<KeyValueScanner> getScanners() {
    this.lock.readLock().lock();
    try {
      return Collections.<KeyValueScanner>singletonList(
          new MemStoreScanner());
    } finally {
      this.lock.readLock().unlock();
    }
  }

  /**
   * @return scanner on snapshot
   */
  public static List<KeyValueScanner> getSnapshotScanners(
      SortedSet<KeyValue> snapshot, KeyValue.KVComparator comparator) {
    return Collections.<KeyValueScanner>singletonList(
        new CollectionBackedScanner(snapshot, comparator));
  }

  /**
   * Check if this memstore may contain the required keys
   * @param scan
   * @return False if the key definitely does not exist in this Memstore
   */
  public boolean shouldSeek(Scan scan, long oldestUnexpiredTS) {
    return (timeRangeTracker.includesTimeRange(scan.getTimeRange()) ||
        snapshotTimeRangeTracker.includesTimeRange(scan.getTimeRange()))
        && (Math.max(timeRangeTracker.getMaximumTimestamp(),
                     snapshotTimeRangeTracker.getMaximumTimestamp()) >=
            oldestUnexpiredTS);
  }

  public TimeRangeTracker getSnapshotTimeRangeTracker() {
    return this.snapshotTimeRangeTracker;
  }

  /*
   * MemStoreScanner implements the KeyValueScanner.
   * It lets the caller scan the contents of a memstore -- both current
   * map and snapshot.
   * This behaves as if it were a real scanner but does not maintain position.
   */
  protected class MemStoreScanner extends NonLazyKeyValueScanner {
    // Next row information for either kvset or snapshot
    private KeyValue kvsetNextRow = null;
    private KeyValue snapshotNextRow = null;

    // iterator based scanning.
    Iterator<KeyValue> kvsetIt;
    Iterator<KeyValue> snapshotIt;

    // The kvset and snapshot at the time of creating this scanner
    volatile KeyValueSkipListSet kvsetAtCreation;
    volatile KeyValueSkipListSet snapshotAtCreation;

    // The allocator and snapshot allocator at the time of creating this scanner
    volatile MemStoreLAB allocatorAtCreation;
    volatile MemStoreLAB snapshotAllocatorAtCreation;

    // The maximum number of kvs to search linearly before doing a seek
    private int maxLinearReseeks = conf.getInt(HConstants.MEMSTORE_RESEEK_LINEAR_SEARCH_LIMIT_KEY,
        HConstants.MEMSTORE_RESEEK_LINEAR_SEARCH_LIMIT_DEFAULT);

    /*
    Some notes...

     So memstorescanner is fixed at creation time. this includes pointers/iterators into
    existing kvset/snapshot.  during a snapshot creation, the kvset is null, and the
    snapshot is moved.  since kvset is null there is no point on reseeking on both,
      we can save us the trouble. During the snapshot->hfile transition, the memstore
      scanner is re-created by StoreScanner#updateReaders().  StoreScanner should
      potentially do something smarter by adjusting the existing memstore scanner.

      But there is a greater problem here, that being once a scanner has progressed
      during a snapshot scenario, we currently iterate past the kvset then 'finish' up.
      if a scan lasts a little while, there is a chance for new entries in kvset to
      become available but we will never see them.  This needs to be handled at the
      StoreScanner level with coordination with MemStoreScanner.

    */

    MemStoreScanner() {
      super();

      kvsetAtCreation = kvset;
      snapshotAtCreation = snapshot;

      if (allocator != null) {
        this.allocatorAtCreation = allocator;
        this.allocatorAtCreation.incScannerCount();
      }
      if (snapshotAllocator != null) {
        this.snapshotAllocatorAtCreation = snapshotAllocator;
        this.snapshotAllocatorAtCreation.incScannerCount();
      }

    }

    protected KeyValue getNext(Iterator<KeyValue> it) {
      KeyValue ret = null;
      long readPoint = MultiVersionConsistencyControl.getThreadReadPoint();

      while (ret == null && it.hasNext()) {
        KeyValue v = it.next();
        if (v.getMemstoreTS() <= readPoint) {
          // keep it.
          ret = v;
        }
      }
      return ret;
    }

    @Override
    public synchronized boolean seek(KeyValue key) {
      if (key == null) {
        close();
        return false;
      }

      // kvset and snapshot will never be empty.
      // if tailSet cant find anything, SS is empty (not null).
      SortedSet<KeyValue> kvTail = kvsetAtCreation.tailSet(key);
      SortedSet<KeyValue> snapshotTail = snapshotAtCreation.tailSet(key);

      kvsetIt = kvTail.iterator();
      snapshotIt = snapshotTail.iterator();

      kvsetNextRow = getNext(kvsetIt);
      snapshotNextRow = getNext(snapshotIt);

      KeyValue lowest = getLowest();

      // has data := (lowest != null)
      return lowest != null;
    }


    @Override
    public synchronized boolean reseek(KeyValue key) {

      /*
       * The high level idea is to seek linearly up until a configurable maximum
       * and then if the kvs we are seeking to are not found yet we fall back to
       * logarithmic seek.
       *
       * The reason we can reach our reseek limit in the kvset and skip searching the
       * snapshot all together is as follows...
       * Let x and y denote the # of steps to seek in each of the lists, to reach "key".
       * If x + y < 20, the order does not matter: we will find the "key" in linear search regardless.
       * if x + y >= 20, we will have to fall back on seek(key) eventually. It does not matter,
       * if we spend the 20 steps on x, or we spend it on y. -- aaiyer
       */


      //Limit the number of kvs to search linearly before triggering a seek.
      int seeked = 0;

      while (kvsetNextRow != null &&
          comparator.compare(kvsetNextRow, key) < 0 &&
          ++seeked <= this.maxLinearReseeks) {
        kvsetNextRow = getNext(kvsetIt);
      }

      while (snapshotNextRow != null &&
          comparator.compare(snapshotNextRow, key) < 0 &&
          ++seeked <= this.maxLinearReseeks) {
        snapshotNextRow = getNext(snapshotIt);
      }

      // The linear reseek took more than the maximum allowed by config.
      if (seeked > this.maxLinearReseeks) {
        return seek(key);
      }

      return (kvsetNextRow != null || snapshotNextRow != null);
    }

    @Override
    public synchronized KeyValue peek() {
      return getLowest();
    }


    @Override
    public synchronized KeyValue next() {
      KeyValue theNext = getLowest();

      if (theNext == null) {
          return null;
      }

      // Advance one of the iterators
      if (theNext == kvsetNextRow) {
        kvsetNextRow = getNext(kvsetIt);
      } else {
        snapshotNextRow = getNext(snapshotIt);
      }

      return theNext;
    }

    protected KeyValue getLowest() {
      return getLower(kvsetNextRow,
          snapshotNextRow);
    }

    /*
     * Returns the lower of the two key values, or null if they are both null.
     * This uses comparator.compare() to compare the KeyValue using the memstore
     * comparator.
     */
    protected KeyValue getLower(KeyValue first, KeyValue second) {
      if (first == null && second == null) {
        return null;
      }
      if (first != null && second != null) {
        int compare = comparator.compare(first, second);
        return (compare <= 0 ? first : second);
      }
      return (first != null ? first : second);
    }

    @Override
    public synchronized void close() {
      this.kvsetNextRow = null;
      this.snapshotNextRow = null;

      this.kvsetIt = null;
      this.snapshotIt = null;

      if (allocatorAtCreation != null) {
        this.allocatorAtCreation.decScannerCount();
        this.allocatorAtCreation = null;
      }
      if (snapshotAllocatorAtCreation != null) {
        this.snapshotAllocatorAtCreation.decScannerCount();
        this.snapshotAllocatorAtCreation = null;
      }
    }

    /**
     * MemStoreScanner returns max value as sequence id because it will
     * always have the latest data among all files.
     */
    @Override
    public long getSequenceID() {
      return Long.MAX_VALUE;
    }

    @Override
    public boolean shouldUseScanner(Scan scan, SortedSet<byte[]> columns,
        long oldestUnexpiredTS) {
      return shouldSeek(scan, oldestUnexpiredTS);
    }

    @Override
    public boolean passesDeleteColumnCheck(KeyValue kv) {
      if (numDeletesInKvSet.get() > 0 || numDeletesInSnapshot.get() > 0)
        return true;
      return false;
    }

    @Override
    public boolean currKeyValueObtainedFromCache() {
      return true;
    }

    @Override
    public boolean passesRowKeyPrefixBloomFilter(KeyValue kv) {
      return this.kvsetAtCreation.containsRowPrefixForKeyValue(kv)
          || this.snapshotAtCreation.containsRowPrefixForKeyValue(kv);
    }
  }

  public final static long FIXED_OVERHEAD = ClassSize.align(
      ClassSize.OBJECT + (19 * ClassSize.REFERENCE));

  public final static long DEEP_OVERHEAD = ClassSize.align(FIXED_OVERHEAD +
      ClassSize.REENTRANT_LOCK + ClassSize.ATOMIC_LONG +
      ClassSize.COPYONWRITE_ARRAYSET + ClassSize.COPYONWRITE_ARRAYLIST +
      (2 * ClassSize.CONCURRENT_SKIPLISTMAP));

  /** Used for readability when we don't store memstore timestamp in HFile */
  public static final boolean NO_PERSISTENT_TS = false;

  /*
   * Calculate how the MemStore size has changed.  Includes overhead of the
   * backing Map.
   * @param kv
   * @param notpresent True if the kv was NOT present in the set.
   * @return Size
   */
  long heapSizeChange(final KeyValue kv, final boolean notpresent) {
    return notpresent ?
        ClassSize.align(ClassSize.CONCURRENT_SKIPLISTMAP_ENTRY + kv.heapSize()):
        0;
  }

  /**
   * Get the entire heap successfullyAllocatedBytes for this MemStore not including keys in the
   * snapshot.
   */
  @Override
  public long heapSize() {
    return size.get();
  }

  /**
   * Get the heap successfullyAllocatedBytes of KVs in this MemStore.
   */
  public long keySize() {
    return heapSize() - DEEP_OVERHEAD;
  }

  public long getSnapshotSize() {
    return snapshotSize;
  }

  /**
   * Flush will first clear out the data in snapshot if any. If snapshot is
   * empty, current keyvalue set will be flushed.
   *
   * @return size of data that is going to be flushed
   */
  public long getFlushableSize() {
    return snapshotSize > 0 ? snapshotSize : keySize();
  }

  /**
   * Code to help figure if our approximation of object heap sizes is close
   * enough.  See hbase-900.  Fills memstores then waits so user can heap
   * dump and bring up resultant hprof in something like jprofiler which
   * allows you get 'deep size' on objects.
   * @param args main args
   */
  public static void main(String [] args) {
    RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
    LOG.info("vmName=" + runtime.getVmName() + ", vmVendor=" +
      runtime.getVmVendor() + ", vmVersion=" + runtime.getVmVersion());
    LOG.info("vmInputArguments=" + runtime.getInputArguments());
    MemStore memstore1 = new MemStore();
    // TODO: x32 vs x64
    long size = 0;
    final int count = 10000;
    byte [] fam = Bytes.toBytes("col");
    byte [] qf = Bytes.toBytes("umn");
    byte [] empty = new byte[0];
    for (int i = 0; i < count; i++) {
      // Give each its own ts
      size += memstore1.add(new KeyValue(Bytes.toBytes(i), fam, qf, i, empty));
    }
    LOG.info("memstore1 estimated size=" + size);
    for (int i = 0; i < count; i++) {
      size += memstore1.add(new KeyValue(Bytes.toBytes(i), fam, qf, i, empty));
    }
    LOG.info("memstore1 estimated size (2nd loading of same data)=" + size);
    // Make a variably sized memstore.
    MemStore memstore2 = new MemStore();
    for (int i = 0; i < count; i++) {
      size += memstore2.add(new KeyValue(Bytes.toBytes(i), fam, qf, i,
        new byte[i]));
    }
    LOG.info("memstore2 estimated size=" + size);
    final int seconds = 30;
    LOG.info("Waiting " + seconds + " seconds while heap dump is taken");
    for (int i = 0; i < seconds; i++) {
      // Thread.sleep(1000);
    }
    LOG.info("Exiting.");
  }
}
