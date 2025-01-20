/*
 * Licensed to Julian Hyde under one or more contributor license
 * agreements.  See the NOTICE file distributed with this work
 * for additional information regarding copyright ownership.
 * Julian Hyde licenses this file to you under the Apache
 * License, Version 2.0 (the "License"); you may not use this
 * file except in compliance with the License.  You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.  See the License for the specific
 * language governing permissions and limitations under the
 * License.
 */
package net.hydromatic.morel.util;

import java.util.Map;

/** Map that allows keys to be merged into equivalence sets.
 *
 * <p>It is an implementation of the union-find algorithm using the
 * <a href="https://en.wikipedia.org/wiki/Disjoint-set_data_structure">disjoint
 * set</a> data structure.
 *
 * <p>Because it is a map, not only can keys be merged into equivalence sets,
 * but keys have associated values (of type {@code <V>}). The equivalence set
 * also has a value (of type {@code <S>} for 'sum'); that value is computed
 * from the keys and values that comprise the set, and is rolled up when who
 * sets are merged.
 *
 * @param <K> Key type
 * @param <V> Value type
 * @param <S> Sum type
 *
 * @see UnionSet
 * @see Unions#createMap
 */
public interface UnionMap<K, V, S>  extends Map<K, V> {
  /** Merges two keys, and returns the merged value. */
  EqSet<K, S> union(K key0, K key1);

  /** Returns whether two keys are in the same equivalence set. */
  default boolean inSameSet(K key0, K key1) {
    return find(key0).equals(find(key1));
  }

  /** Returns the equivalence set that {@code key} belongs to.
   *
   * <p>Throws if {@code key} is not in the map; never returns null. */
  EqSet<K, S> find(K key);

  /** {@inheritDoc}
   *
   * <p>Deletion of individual elements is not supported. */
  @Deprecated @Override default V remove(Object key) {
    throw new UnsupportedOperationException("remove");
  }

  /** Returns the number of equivalence sets in this map. */
  int setCount();

  /** An equivalence set.
   *
   * @param <K> Key type
   * @param <S> Sum type
   */
  interface EqSet<K, S> {
    /** Representative key. */
    K getKey();

    /** Value for the equivalence set. */
    S sum();
  }
}

// End UnionMap.java
