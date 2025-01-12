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

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.function.BinaryOperator;

import static java.util.Objects.requireNonNull;

/** Implementations of {@link MergeableMap}. */
public abstract class MergeableMaps {
  private MergeableMaps() {
  }

  /** Creates a MergeableMap backed by a {@link HashMap}. */
  public static <K, V> MergeableMap<K, V> create(BinaryOperator<V> combiner) {
    return new MergeableMapImpl<>(new HashMap<>(), combiner);
  }

  /** Implementation of {@link MergeableMap} that maps each key to an
   * {@link Item} via a backing map.
   *
   * <p>The implementation uses path compression but not rank.
   *
   * @param <K> Key type
   * @param <V> Value type
   */
  static class MergeableMapImpl<K, V> extends AbstractMap<K, V>
      implements MergeableMap<K, V> {
    private final Map<K, Item<K, V>> map;
    private final BinaryOperator<V> combiner;
    /** Number of times that {@link #union(Object, Object)} has merged two
     * equivalence sets. */
    private int mergeCount = 0;

    MergeableMapImpl(Map<K, Item<K, V>> map, BinaryOperator<V> combiner) {
      this.map = map;
      this.combiner = combiner;
    }

    @Override public void clear() {
      map.clear();
      mergeCount = 0;
    }

    /** Returns the root item for a given key. */
    private Item<K, V> root(K key) {
      return item(key).resolve();
    }

    /** Returns the item for a given key. */
    private Item<K, V> item(K key) {
      final Item<K, V> item = map.get(key);
      if (item == null) {
        throw new IllegalArgumentException("not in set");
      }
      return item;
    }

    @Override public K find(K key) {
      return root(key).key;
    }

    @Override public boolean inSameSet(K key0, K key1) {
      return root(key0) == root(key1);
    }

    @Override public V put(K key, V value) {
      final Item<K, V> item = map.get(key);
      if (item == null) {
        // Key is not previously known. Add key as a new equivalence class.
        map.put(key, new Item<>(key, value));
        return null;
      }
      // Key is already known. Replace the value for its equivalence set.
      Item<K, V> rootItem = item.resolve();
      V previousValue = rootItem.value;
      rootItem.value = value;
      return previousValue;
    }

    @Override public @NonNull Set<Entry<K, V>> entrySet() {
      return new AbstractSet<Entry<K, V>>() {
        @Override public @NonNull Iterator<Entry<K, V>> iterator() {
          final Iterator<Item<K, V>> items = map.values().iterator();
          return new Iterator<Entry<K, V>>() {
            @Override public boolean hasNext() {
              return items.hasNext();
            }

            @Override public Entry<K, V> next() {
              final Item<K, V> item = items.next();
              final Item<K, V> root = item.resolve();
              return new MapEntry<>(item.key, root.value);
            }
          };
        }

        @Override public int size() {
          return map.size();
        }
      };
    }

    @Override public V union(K key0, K key1) {
      final Item<K, V> root0 = root(key0);
      final Item<K, V> item1 = item(key1);
      final Item<K, V> root1 = item1.resolve();
      if (root0 == root1) {
        // key0 and key1 were already in same equivalence set
        return root0.value;
      }
      ++mergeCount; // number of equivalence sets has decreased

      // Set the root of item1, and all its ancestors including root1, to root0.
      for (Item<K, V> item2 = item1;;) {
        @Nullable Item<K, V> next = item2.parent;
        item2.parent = root0;
        if (next == null) {
          break;
        }
        item2 = next;
      }

      // Combine values.
      root0.value = combiner.apply(root0.value, root1.value);
      root1.value = null; // no longer a root
      return root0.value;
    }

    @Override public int setCount() {
      // The number of equivalence sets increases by one each time a key is
      // added, and decreases by one each time 'union' merges two sets.
      // Therefore, the number of equivalence sets is the number of keys minus
      // the number of merges.
      return map.size() - mergeCount;
    }

    @Override public int size() {
      return map.size();
    }
  }

  /** All the information about a key in a {@link MergeableMap}.
   *
   * <p>An item is either a root (in which case {@link #parent} is null),
   * or a non-root (in which case {@link #parent} is not-null,
   * and {@link #value} is null).
   *
   * @param <K> Key type
   * @param <V> Value type
   */
  private static class Item<K, V> {
    final K key;
    @Nullable Item<K, V> parent; // null if a root
    @Nullable V value; // null if not a root

    /** Creates a root Item. (Parent is null, but will be assigned later if this
     * Item becomes non-root.) */
    private Item(K key, V value) {
      this.key = requireNonNull(key);
      this.value = value;
    }

    @Override public String toString() {
      return "{k=" + key
          + ", parent=" + (parent == null ? null : parent.key)
          + ", v=" + value + "}";
    }

    /** Returns the ultimate parent of this Item.
     *
     * <p>Fixes up any parents it finds along the way, but does not merge any
     * sets. */
    private Item<K, V> resolve() {
      if (parent == null) {
        return this;
      }

      // Find the root (the ancestor that has no parent)
      Item<K, V> root = this;
      while (root.parent != null) {
        root = root.parent;
      }

      // Compress the path: walk the towards the root, making every item point
      // directly to the root.
      //
      // Only has an effect if the chain is longer than 2. If the chain is
      // [a, b, c, root] then a and b will be modified, and c will already point
      // to root. If the chain is [c, root] then no modifications are needed.
      for (Item<K, V> item = this; item.parent != root;) {
        Item<K, V> previous = item;
        item = requireNonNull(item.parent);
        previous.parent = root;
      }

      return root;
    }
  }
}

// End MergeableMaps.java
