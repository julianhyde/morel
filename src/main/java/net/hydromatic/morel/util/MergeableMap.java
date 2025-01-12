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
 * @param <K> Key type
 * @param <V> Value type
 */
public interface MergeableMap<K, V>  extends Map<K, V> {
  /** Merges two keys, and returns the merged value. */
  V merge(K key0, K key1);

  /** Returns whether two keys are in the same equivalence set. */
  boolean inSameSet(K key0, K key1);
}

// End MergeableMap.java
