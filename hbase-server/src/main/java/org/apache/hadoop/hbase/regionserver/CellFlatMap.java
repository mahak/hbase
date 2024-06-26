/*
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

import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Set;
import org.apache.hadoop.hbase.Cell;
import org.apache.yetus.audience.InterfaceAudience;

/**
 * CellFlatMap stores a constant number of elements and is immutable after creation stage. Being
 * immutable, the CellFlatMap can be implemented as array. The actual array can be on- or off-heap
 * and is implemented in concrete class derived from CellFlatMap. The CellFlatMap uses no
 * synchronization primitives, it is assumed to be created by a single thread and then it can be
 * read-only by multiple threads. The "flat" in the name, means that the memory layout of the Map is
 * sequential array and thus requires less memory than ConcurrentSkipListMap.
 */
@InterfaceAudience.Private
public abstract class CellFlatMap<T extends Cell> implements NavigableMap<T, T> {

  private final Comparator<? super T> comparator;
  protected int minCellIdx = 0; // the index of the minimal cell (for sub-sets)
  protected int maxCellIdx = 0; // the index of the cell after the maximal cell (for sub-sets)
  private boolean descending = false;

  /* C-tor */
  public CellFlatMap(Comparator<? super T> comparator, int min, int max, boolean d) {
    this.comparator = comparator;
    this.minCellIdx = min;
    this.maxCellIdx = max;
    this.descending = d;
  }

  /* Used for abstract CellFlatMap creation, implemented by derived class */
  protected abstract CellFlatMap<T> createSubCellFlatMap(int min, int max, boolean descending);

  /* Returns the i-th cell in the cell block */
  protected abstract T getCell(int i);

  /**
   * Binary search for a given key in between given boundaries of the array. Positive returned
   * numbers mean the index. Negative returned numbers means the key not found. The absolute value
   * of the output is the possible insert index for the searched key In twos-complement, (-1 *
   * insertion point)-1 is the bitwise not of the insert point.
   * @param needle The key to look for in all of the entries
   * @return Same return value as Arrays.binarySearch.
   */
  private int find(T needle) {
    int begin = minCellIdx;
    int end = maxCellIdx - 1;

    while (begin <= end) {
      int mid = begin + ((end - begin) >> 1);
      T midCell = getCell(mid);
      int compareRes = comparator.compare(midCell, needle);

      if (compareRes == 0) {
        return mid; // 0 means equals. We found the key
      }
      // Key not found. Check the comparison results; reverse the meaning of
      // the comparison in case the order is descending (using XOR)
      if ((compareRes < 0) ^ descending) {
        // midCell is less than needle so we need to look at farther up
        begin = mid + 1;
      } else {
        // midCell is greater than needle so we need to look down
        end = mid - 1;
      }
    }

    return (-1 * begin) - 1;
  }

  /**
   * Get the index of the given anchor key for creating subsequent set. It doesn't matter whether
   * the given key exists in the set or not. taking into consideration whether the key should be
   * inclusive or exclusive.
   */
  private int getValidIndex(T key, boolean inclusive, boolean tail) {
    final int index = find(key);
    // get the valid (positive) insertion point from the output of the find() method
    int insertionPoint = index < 0 ? ~index : index;

    // correct the insertion point in case the given anchor key DOES EXIST in the set
    if (index >= 0) {
      if (descending && !(tail ^ inclusive)) {
        // for the descending case
        // if anchor for head set (tail=false) AND anchor is not inclusive -> move the insertion pt
        // if anchor for tail set (tail=true) AND the keys is inclusive -> move the insertion point
        // because the end index of a set is the index of the cell after the maximal cell
        insertionPoint += 1;
      } else if (!descending && (tail ^ inclusive)) {
        // for the ascending case
        // if anchor for head set (tail=false) AND anchor is inclusive -> move the insertion point
        // because the end index of a set is the index of the cell after the maximal cell
        // if anchor for tail set (tail=true) AND the keys is not inclusive -> move the insertion pt
        insertionPoint += 1;
      }
    }
    // insert the insertion point into the valid range,
    // as we may enlarge it too much in the above correction
    return Math.min(Math.max(insertionPoint, minCellIdx), maxCellIdx);
  }

  @Override
  public Comparator<? super T> comparator() {
    return comparator;
  }

  @Override
  public int size() {
    return maxCellIdx - minCellIdx;
  }

  @Override
  public boolean isEmpty() {
    return (size() == 0);
  }

  // ---------------- Sub-Maps ----------------
  @Override
  public NavigableMap<T, T> subMap(T fromKey, boolean fromInclusive, T toKey, boolean toInclusive) {
    final int lessCellIndex = getValidIndex(fromKey, fromInclusive, true);
    final int greaterCellIndex = getValidIndex(toKey, toInclusive, false);
    if (descending) {
      return createSubCellFlatMap(greaterCellIndex, lessCellIndex, descending);
    } else {
      return createSubCellFlatMap(lessCellIndex, greaterCellIndex, descending);
    }
  }

  @Override
  public NavigableMap<T, T> headMap(T toKey, boolean inclusive) {
    if (descending) {
      return createSubCellFlatMap(getValidIndex(toKey, inclusive, false), maxCellIdx, descending);
    } else {
      return createSubCellFlatMap(minCellIdx, getValidIndex(toKey, inclusive, false), descending);
    }
  }

  @Override
  public NavigableMap<T, T> tailMap(T fromKey, boolean inclusive) {
    if (descending) {
      return createSubCellFlatMap(minCellIdx, getValidIndex(fromKey, inclusive, true), descending);
    } else {
      return createSubCellFlatMap(getValidIndex(fromKey, inclusive, true), maxCellIdx, descending);
    }
  }

  @Override
  public NavigableMap<T, T> descendingMap() {
    return createSubCellFlatMap(minCellIdx, maxCellIdx, true);
  }

  @Override
  public NavigableMap<T, T> subMap(T k1, T k2) {
    return this.subMap(k1, true, k2, true);
  }

  @Override
  public NavigableMap<T, T> headMap(T k) {
    return this.headMap(k, true);
  }

  @Override
  public NavigableMap<T, T> tailMap(T k) {
    return this.tailMap(k, true);
  }

  // -------------------------------- Key's getters --------------------------------
  @Override
  public T firstKey() {
    if (isEmpty()) {
      return null;
    }
    return descending ? getCell(maxCellIdx - 1) : getCell(minCellIdx);
  }

  @Override
  public T lastKey() {
    if (isEmpty()) {
      return null;
    }
    return descending ? getCell(minCellIdx) : getCell(maxCellIdx - 1);
  }

  @Override
  public T lowerKey(T k) {
    if (isEmpty()) {
      return null;
    }
    int index = find(k);
    // If index>=0 there's a key exactly equal
    index = (index >= 0) ? index - 1 : -(index);
    return (index < minCellIdx || index >= maxCellIdx) ? null : getCell(index);
  }

  @Override
  public T floorKey(T k) {
    if (isEmpty()) {
      return null;
    }
    int index = find(k);
    index = (index >= 0) ? index : -(index);
    return (index < minCellIdx || index >= maxCellIdx) ? null : getCell(index);
  }

  @Override
  public T ceilingKey(T k) {
    if (isEmpty()) {
      return null;
    }
    int index = find(k);
    index = (index >= 0) ? index : -(index) + 1;
    return (index < minCellIdx || index >= maxCellIdx) ? null : getCell(index);
  }

  @Override
  public T higherKey(T k) {
    if (isEmpty()) {
      return null;
    }
    int index = find(k);
    index = (index >= 0) ? index + 1 : -(index) + 1;
    return (index < minCellIdx || index >= maxCellIdx) ? null : getCell(index);
  }

  @Override
  public boolean containsKey(Object o) {
    int index = find((T) o);
    return (index >= 0);
  }

  @Override
  public boolean containsValue(Object o) { // use containsKey(Object o) instead
    throw new UnsupportedOperationException("Use containsKey(Object o) instead");
  }

  @Override
  public T get(Object o) {
    int index = find((T) o);
    return (index >= 0) ? getCell(index) : null;
  }

  // -------------------------------- Entry's getters --------------------------------

  private static class CellFlatMapEntry<T> implements Entry<T, T> {
    private final T cell;

    public CellFlatMapEntry(T cell) {
      this.cell = cell;
    }

    @Override
    public T getKey() {
      return cell;
    }

    @Override
    public T getValue() {
      return cell;
    }

    @Override
    public T setValue(T value) {
      throw new UnsupportedOperationException();
    }
  }

  @Override
  public Entry<T, T> lowerEntry(T k) {
    T cell = lowerKey(k);
    if (cell == null) {
      return null;
    }
    return new CellFlatMapEntry<>(cell);
  }

  @Override
  public Entry<T, T> higherEntry(T k) {
    T cell = higherKey(k);
    if (cell == null) {
      return null;
    }
    return new CellFlatMapEntry<>(cell);
  }

  @Override
  public Entry<T, T> ceilingEntry(T k) {
    T cell = ceilingKey(k);
    if (cell == null) {
      return null;
    }
    return new CellFlatMapEntry<>(cell);
  }

  @Override
  public Entry<T, T> floorEntry(T k) {
    T cell = floorKey(k);
    if (cell == null) {
      return null;
    }
    return new CellFlatMapEntry<>(cell);
  }

  @Override
  public Entry<T, T> firstEntry() {
    T cell = firstKey();
    if (cell == null) {
      return null;
    }
    return new CellFlatMapEntry<>(cell);
  }

  @Override
  public Entry<T, T> lastEntry() {
    T cell = lastKey();
    if (cell == null) {
      return null;
    }
    return new CellFlatMapEntry<>(cell);
  }

  // The following 2 methods (pollFirstEntry, pollLastEntry) are unsupported because these are
  // updating methods.
  @Override
  public Entry<T, T> pollFirstEntry() {
    throw new UnsupportedOperationException();
  }

  @Override
  public Entry<T, T> pollLastEntry() {
    throw new UnsupportedOperationException();
  }

  // -------------------------------- Updates --------------------------------
  // All updating methods below are unsupported.
  // Assuming an array of Cells will be allocated externally,
  // fill up with Cells and provided in construction time.
  // Later the structure is immutable.
  @Override
  public T put(T k, T v) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void clear() {
    throw new UnsupportedOperationException();
  }

  @Override
  public T remove(Object o) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void putAll(Map<? extends T, ? extends T> map) {
    throw new UnsupportedOperationException();
  }

  // -------------------------------- Sub-Sets --------------------------------
  @Override
  public NavigableSet<T> navigableKeySet() {
    throw new UnsupportedOperationException();
  }

  @Override
  public NavigableSet<T> descendingKeySet() {
    throw new UnsupportedOperationException();
  }

  @Override
  public NavigableSet<T> keySet() {
    throw new UnsupportedOperationException();
  }

  @Override
  public Collection<T> values() {
    return new CellFlatMapCollection();
  }

  @Override
  public Set<Entry<T, T>> entrySet() {
    throw new UnsupportedOperationException();
  }

  // -------------------------------- Iterator K --------------------------------
  private final class CellFlatMapIterator implements Iterator<T> {
    int index;

    private CellFlatMapIterator() {
      index = descending ? maxCellIdx - 1 : minCellIdx;
    }

    @Override
    public boolean hasNext() {
      return descending ? (index >= minCellIdx) : (index < maxCellIdx);
    }

    @Override
    public T next() {
      T result = getCell(index);
      if (descending) {
        index--;
      } else {
        index++;
      }
      return result;
    }

    @Override
    public void remove() {
      throw new UnsupportedOperationException();
    }
  }

  // -------------------------------- Collection --------------------------------
  private final class CellFlatMapCollection implements Collection<T> {

    @Override
    public int size() {
      return CellFlatMap.this.size();
    }

    @Override
    public boolean isEmpty() {
      return CellFlatMap.this.isEmpty();
    }

    @Override
    public void clear() {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean contains(Object o) {
      return containsKey(o);
    }

    @Override
    public Iterator<T> iterator() {
      return new CellFlatMapIterator();
    }

    @Override
    public Object[] toArray() {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> T[] toArray(T[] ts) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean add(T k) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean remove(Object o) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean containsAll(Collection<?> collection) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean addAll(Collection<? extends T> collection) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean removeAll(Collection<?> collection) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean retainAll(Collection<?> collection) {
      throw new UnsupportedOperationException();
    }
  }
}
