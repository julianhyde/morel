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

import java.util.Set;

/** Set that allows elements be merged into equivalence sets.
 *
 * <p>Like {@link MergeableMap}, {@code MergeableSet}
 * is an implementation of the union-find algorithm using the
 * <a href="https://en.wikipedia.org/wiki/Disjoint-set_data_structure">disjoint
 * set</a> data structure.
 *
 * @param <E> Element type
 *
 * @see MergeableMap
 * @see MergeableMaps#createSet
 */
public interface MergeableSet<E>  extends Set<E> {
  /** Merges two keys, and returns the representative key of the merged
   * equivalence set. */
  E union(E key0, E key1);

  /** Returns whether two keys are in the same equivalence set. */
  default boolean inSameSet(E key0, E key1) {
    return find(key0).equals(find(key1));
  }

  /** Returns the representative key of the set that element {@code e} belongs
   * to.
   *
   * <p>Throws if {@code e} is not in the set; never returns null. */
  E find(E e);

  /** {@inheritDoc}
   *
   * <p>Deletion of individual elements is not supported. */
  @Deprecated @Override default boolean remove(Object key) {
    throw new UnsupportedOperationException("remove");
  }

  /** Returns the number of equivalence sets in this set. */
  int setCount();
}

// End MergeableSet.java
