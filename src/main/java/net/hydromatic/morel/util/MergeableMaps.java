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

import static com.google.common.base.Preconditions.checkArgument;

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
   * @param <K> Key type
   * @param <V> Value type
   */
  static class MergeableMapImpl<K, V> extends AbstractMap<K, V>
      implements MergeableMap<K, V> {
    private final Map<K, Item<K, V>> map;
    private final BinaryOperator<V> combiner;

    MergeableMapImpl(Map<K, Item<K, V>> map, BinaryOperator<V> combiner) {
      this.map = map;
      this.combiner = combiner;
    }

    @Override public void clear() {
      map.clear();
    }

    @Override public K find(K key) {
      final Item<K, V> item = map.get(key);
      if (item == null) {
        throw new IllegalArgumentException("not in set");
      }
      return item.key;
    }

    @Override public boolean inSameSet(K key0, K key1) {
      final Item<K, V> item0 = map.get(key0);
      if (item0 == null) {
        throw new IllegalArgumentException("not in set");
      }
      final Item<K, V> root0 = item0.resolve();
      final Item<K, V> item1 = map.get(key1);
      if (item1 == null) {
        throw new IllegalArgumentException("not in set");
      }
      final Item<K, V> root1 = item1.resolve();
      return root0 == root1;
    }

    @Override public V put(K key, V value) {
      final Item<K, V> item = map.get(key);
      if (item == null) {
        // Key is not previously known. Add k as a new equivalence class.
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
      final Item<K, V> item0 = map.get(key0);
      final Item<K, V> root0 = item0.resolve();
      final Item<K, V> item1 = map.get(key1);
      final Item<K, V> root1 = item1.resolve();
      if (root0 == root1) {
        // key0 and key1 were already in same equivalence set
        return root0.value;
      }
      root0.value = combiner.apply(root0.value, root1.value);
      item1.setRoot(root0);
      return root0.value;
    }
  }

  /** Wrapper to distinguish payloads from equivalence classes.
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

      // Walk the chain, making every item point directly to the root. Only
      // has an effect if the chain is longer than 2: if the chain is
      // [a, b, c, root] then a and b will be modified, and c will already point
      // to root.
      for (Item<K, V> item = this; item.parent != root;) {
        Item<K, V> previous = item;
        item = requireNonNull(item.parent);
        previous.parent = root;
      }

      return root;
    }

    /** Sets the root of this item and its ancestors. */
    void setRoot(Item<K, V> item) {
      checkArgument(item.parent == null);
      for (Item<K, V> item2 = this;;) {
        @Nullable Item<K, V> next = item2.parent;
        item2.parent = item;
        if (next == null) {
          // We only need to clear 'v' for the item that was previously a root.
          // In non-root items 'v' was already null.
          item2.value = null;
          break;
        }
        item2 = next;
      }
    }
  }
}

// End MergeableMaps.java
