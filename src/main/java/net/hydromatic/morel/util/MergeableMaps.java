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

  static class MergeableMapImpl<K, V> extends AbstractMap<K, V>
      implements MergeableMap<K, V> {
    private final Map<K, Item<K, V>> map;
    private final BinaryOperator<V> combiner;

    MergeableMapImpl(Map<K, Item<K, V>> map, BinaryOperator<V> combiner) {
      this.map = map;
      this.combiner = combiner;
    }

    @Override public boolean equiv(K k0, K k1) {
      final Item<K, V> item0 = map.get(k0);
      if (item0 == null) {
        throw new IllegalArgumentException("not in set");
      }
      final Item<K, V> root0 = item0.resolve();
      final Item<K, V> item1 = map.get(k1);
      if (item1 == null) {
        throw new IllegalArgumentException("not in set");
      }
      final Item<K, V> root1 = item1.resolve();
      return root0 == root1;
    }

    @Override public V put(K k, V v) {
      final Item<K, V> item = map.get(k);
      if (item == null) {
        // Key is not previously known. Add k as a new equivalence class.
        map.put(k, new Item<>(k, null, v));
        return null;
      }
      // Key is already known. Replace the value for its equivalence set.
      Item<K, V> rootItem = item.resolve();
      V oldV = rootItem.v;
      rootItem.v = v;
      return oldV;
    }

    @Override public Set<Entry<K, V>> entrySet() {
      return new AbstractSet<Entry<K, V>>() {
        @Override public Iterator<Entry<K, V>> iterator() {
          final Iterator<Item<K, V>> items = map.values().iterator();
          return new Iterator<Entry<K, V>>() {
            @Override public boolean hasNext() {
              return items.hasNext();
            }

            @Override public Entry<K, V> next() {
              final Item<K, V> item = items.next();
              final Item<K, V> root = item.resolve();
              return new MapEntry<>(item.k, root.v);
            }
          };
        }

        @Override public int size() {
          return map.size();
        }
      };
    }

    @Override public V merge(K k0, K k1) {
      final Item<K, V> item0 = map.get(k0);
      final Item<K, V> root0 = item0.resolve();
      final Item<K, V> item1 = map.get(k1);
      final Item<K, V> root1 = item1.resolve();
      if (root0 == root1) {
        // k0 and k1 were already in same equivalence set
        return root0.v;
      }
      root0.v = combiner.apply(root0.v, root1.v);
      item1.setRoot(root0);
      return root0.v;
    }
  }

  /** Wrapper to distinguish payloads from equivalence classes.
   *
   * @param <K> Key type
   * @param <V> Value type
   */
  private static class Item<K, V> {
    K k;
    @Nullable Item<K, V> parent; // null if a root
    V v; // null if not a root

    /** Creates an Item. */
    private Item(K k, @Nullable Item<K, V> parent, V v) {
      this.k = requireNonNull(k);
      this.parent = parent;
      this.v = v;
    }

    @Override public String toString() {
      return "{k=" + k
          + ", parent=" + (parent == null ? null : parent.k)
          + ", v=" + v + "}";
    }

    /** Returns the ultimate parent of this Item.
     *
     * <p>Fixes up any parents it finds along the way, but does not merge any
     * sets. */
    private Item<K, V> resolve() {
      if (parent == null) {
        return this;
      }
      Item<K, V> root = this;
      while (root.parent != null) {
        root = root.parent;
      }
      // If root is not the parent, fix up the chain
      if (root != parent) {
        for (Item<K, V> i2 = this; i2.parent != root;) {
          @Nullable Item<K, V> next = i2;
          i2 = requireNonNull(i2.parent);
          next.parent = root;
        }
      }
      return root;
    }

    /** Sets the root of this item and its ancestors. */
    void setRoot(Item<K, V> item) {
      checkArgument(item.parent == null);
      for (Item<K, V> i = this;;) {
        @Nullable Item<K, V> next = i.parent;
        i.parent = item;
        if (next == null) {
          i.v = null;
          break;
        }
        i = next;
      }
    }
  }
}

// End MergeableMaps.java
