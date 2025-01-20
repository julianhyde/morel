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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.Spliterator;
import java.util.function.BiFunction;
import java.util.function.BinaryOperator;
import java.util.function.Consumer;
import java.util.function.Function;

import static java.util.Objects.requireNonNull;

/** Implementations and helpers for {@link UnionSet}
 * and {@link UnionMap}. */
public abstract class Unions {
  private Unions() {
  }

  /** Creates a UnionMap backed by a {@link LinkedHashMap}.
   *
   * <p>The functions are called at various points in the lifecycle:
   * <ul>
   * <li>{@code bind(value)} is called to generate a sum value for an
   *   equivalence set that contains one key, e.g. when
   *   {@code put(key, value)} is called on a new key;
   * <li>{@code add(sum1, sum2)} is called to combine two sums {@code sum1} and
   *   {@code sum2} when two equivalence sets are merged;
   * <li>{@code update(sum, value1, value2)} is called to generate a new sum
   *   for an equivalence set when {@code put(key, value2)} has been called
   *   to change the value of {@code key} from {@code value1} to
   *   {@code value2}.
   * </ul>
   */
  public static <K, V, S> UnionMap<K, V, S> createMap(Function<V, S> bind,
      BinaryOperator<S> add, TriFunction<S, V, V, S> update) {
    return new UnionMapImpl<>(new LinkedHashMap<>(), bind, add, update);
  }

  /** Creates a UnionSet backed by a {@link LinkedHashMap}. */
  public static <E> UnionSet<E> createSet() {
    return new UnionSetImpl<>(new LinkedHashMap<>());
  }

  /** Implementation of {@link UnionMap} that maps each key to an
   * {@link Item} via a backing map.
   *
   * <p>The implementation uses path compression but not rank.
   *
   * @param <K> Key type
   * @param <V> Value type
   * @param <S> Sum type
   */
  static class UnionMapImpl<K, V, S> extends AbstractMap<K, V>
      implements UnionMap<K, V, S> {
    private final Map<K, Item<K, V, S>> map;
    private final Function<V, S> bind;
    private final BiFunction<S, S, S> add;
    private final TriFunction<S, V, V, S> update;

    /** Number of times that {@link #union(Object, Object)} has merged two
     * equivalence sets. */
    private int mergeCount = 0;

    UnionMapImpl(Map<K, Item<K, V, S>> map, Function<V, S> bind,
        BinaryOperator<S> add, TriFunction<S, V, V, S> update) {
      this.map = map;
      this.bind = bind;
      this.add = add;
      this.update = update;
    }

    @Override public void clear() {
      map.clear();
      mergeCount = 0;
    }

    /** Returns the root item for a given key. */
    private Item<K, V, S> root(K key) {
      return item(key).resolve2();
    }

    /** Returns the item for a given key. */
    private Item<K, V, S> item(K key) {
      final Item<K, V, S> item = map.get(key);
      if (item == null) {
        throw new IllegalArgumentException("not in set");
      }
      return item;
    }

    @Override public EqSet<K, S> find(K key) {
      return root(key);
    }

    @Override public boolean inSameSet(K key0, K key1) {
      return root(key0) == root(key1);
    }

    @Override public V get(Object key) {
      final Item<K, V, S> item = map.get(key);
      if (item == null) {
        return null;
      }
      return item.value;
    }

    @Override public V put(K key, V value) {
      final Item<K, V, S> item = map.get(key);
      if (item == null) {
        // Key is not previously known. Add key as a new equivalence class.
        map.put(key, new Item<>(key, value, bind.apply(value)));
        return null;
      }
      // Key is already known. Replace the value.
      V previousValue = item.value;
      item.value = value;

      // Update the sum for the equivalence set.
      Item<K, V, S> rootItem = item.resolve2();
      rootItem.sum = update.apply(rootItem.sum, previousValue, value);
      return previousValue;
    }

    @SuppressWarnings("deprecation")
    @Override public V remove(Object key) {
      throw new UnsupportedOperationException("remove");
    }

    @Override public @NonNull Set<Entry<K, V>> entrySet() {
      return new AbstractSet<Entry<K, V>>() {
        @Override public @NonNull Iterator<Entry<K, V>> iterator() {
          final Iterator<Item<K, V, S>> items = map.values().iterator();
          return new Iterator<Entry<K, V>>() {
            @Override public boolean hasNext() {
              return items.hasNext();
            }

            @Override public Entry<K, V> next() {
              return items.next().resolve2();
            }
          };
        }

        @Override public int size() {
          return map.size();
        }
      };
    }

    @Override public EqSet<K, S> union(K key0, K key1) {
      final Item<K, V, S> root0 = root(key0);
      final Item<K, V, S> item1 = item(key1);
      final Item<K, V, S> root1 = item1.resolve2();
      if (root0 != root1) {
        // If key0 and key1 are already in same equivalence set, there's nothing
        // to do. Otherwise, the number of equivalence sets has decreased
        ++mergeCount;

        // Set the root of item1, and all its ancestors including root1, to root0.
        for (SetItem<K> item2 = item1;;) {
          @Nullable SetItem<K> next = item2.parent;
          item2.parent = root0;
          if (next == null) {
            break;
          }
          item2 = next;
        }

        // Combine values.
        root0.sum = add.apply(root0.sum, root1.sum);
        root1.sum = null; // no longer a root
      }
      return root0;
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

  /** Implementation of {@link UnionMap} that maps each key to an
   * {@link Item} via a backing map.
   *
   * <p>The implementation uses path compression but not rank.
   *
   * @param <E> Element type
   */
  static class UnionSetImpl<E> extends AbstractSet<E>
      implements UnionSet<E> {
    private final Map<E, SetItem<E>> map;

    /** Number of times that {@link #union(Object, Object)} has merged two
     * equivalence sets. */
    private int mergeCount = 0;

    UnionSetImpl(Map<E, SetItem<E>> map) {
      this.map = map;
    }

    @Override public void clear() {
      map.clear();
      mergeCount = 0;
    }

    @Override public @NonNull Iterator<E> iterator() {
      return map.keySet().iterator();
    }

    @Override public @NonNull Spliterator<E> spliterator() {
      return map.keySet().spliterator();
    }

    @Override public void forEach(Consumer<? super E> action) {
      map.keySet().forEach(action);
    }

    /** Returns the root item for a given element. */
    private SetItem<E> root(E e) {
      return item(e).resolve();
    }

    /** Returns the item for a given key. */
    private SetItem<E> item(E e) {
      final SetItem<E> item = map.get(e);
      if (item == null) {
        throw new IllegalArgumentException("not in set");
      }
      return item;
    }

    @Override public E find(E e) {
      return root(e).key;
    }

    @Override public boolean inSameSet(E element0, E element1) {
      return root(element0) == root(element1);
    }

    @Override public boolean add(E e) {
      final SetItem<E> item = map.get(e);
      if (item == null) {
        // Key is not previously known. Add key as a new equivalence class.
        map.put(e, new SetItem<>(e));
        return true;
      }
      return false;
    }

    @SuppressWarnings("deprecation")
    @Override public boolean remove(Object o) {
      throw new UnsupportedOperationException("remove");
    }

    @Override public E union(E element0, E element1) {
      final SetItem<E> root0 = root(element0);
      final SetItem<E> item1 = item(element1);
      final SetItem<E> root1 = item1.resolve();
      if (root0 != root1) {
        // If key0 and key1 are already in same equivalence set, there's nothing
        // to do. Otherwise, the number of equivalence sets has decreased
        ++mergeCount;

        // Set the root of item1, and all its ancestors including root1, to root0.
        for (SetItem<E> item2 = item1;;) {
          @Nullable SetItem<E> next = item2.parent;
          item2.parent = root0;
          if (next == null) {
            break;
          }
          item2 = next;
        }
      }
      return root0.key;
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

  /** All the information about a key in a {@link UnionSet}.
   *
   * <p>An item is either a root (in which case {@link #parent} is null),
   * or a non-root (in which case {@link #parent} is not-null.
   *
   * @param <E> Element type
   */
  private static class SetItem<E> {
    final E key;
    @Nullable SetItem<E> parent; // null if a root

    SetItem(E key) {
      this.key = requireNonNull(key);
    }

    @Override public String toString() {
      return "{k=" + key
          + ", parent=" + (parent == null ? null : parent.key) + "}";
    }

    /** Returns the ultimate parent of this Item.
     *
     * <p>Fixes up any parents it finds along the way, but does not merge any
     * sets. */
    SetItem<E> resolve() {
      if (parent == null) {
        return this;
      }

      // Find the root (the ancestor that has no parent)
      SetItem<E> root = this;
      while (root.parent != null) {
        root = root.parent;
      }

      // Compress the path: walk the towards the root, making every item point
      // directly to the root.
      //
      // Only has an effect if the chain is longer than 2. If the chain is
      // [a, b, c, root] then a and b will be modified, and c will already point
      // to root. If the chain is [c, root] then no modifications are needed.
      for (SetItem<E> item = this; item.parent != root;) {
        SetItem<E> previous = item;
        item = requireNonNull(item.parent);
        previous.parent = root;
      }

      return root;
    }
  }

  /** All the information about a key in a {@link UnionMap}.
   *
   * <p>An item is either a root (in which case {@link #parent} is null),
   * or a non-root (in which case {@link #parent} is not-null,
   * and {@link #value} is null).
   *
   * @param <K> Key type
   * @param <V> Value type
   * @param <S> Sum type
   */
  private static class Item<K, V, S>
      extends SetItem<K>
      implements UnionMap.EqSet<K, S>, Map.Entry<K, V> {
    V value;
    S sum; // null if not a root

    /** Creates a root Item. (Parent is null, but will be assigned later if this
     * Item becomes non-root.) */
    private Item(K key, V value, S sum) {
      super(key);
      this.value = requireNonNull(value);
      this.sum = requireNonNull(sum);
    }

    @Override public String toString() {
      return "{k=" + key
          + ", parent=" + (parent == null ? null : parent.key)
          + ", v=" + value + "}";
    }

    // implement Map.Entry and EqSet
    @Override public K getKey() {
      return key;
    }

    // implement Map.Entry
    @Override public V getValue() {
      return value;
    }

    // implement Map.Entry
    @Override public V setValue(V value) {
      // we could consider implementing this in future
      throw new UnsupportedOperationException("setValue");
    }

    // implement EqSet
    @Override public S sum() {
      return sum;
    }

    Item<K, V, S> resolve2() {
      return (Item<K, V, S>) resolve();
    }
  }

  /**
   * Function that takes three arguments.
   *
   * @param <T> First argument type
   * @param <U> Second argument type
   * @param <V> Third argument type
   * @param <R> Result type
   */
  @FunctionalInterface
  public interface TriFunction<T, U, V, R> {
    /**
     * Applies this function to the given arguments.
     *
     * @param t First function argument
     * @param u Second function argument
     * @param v Third function argument
     * @return the function result
     */
    R apply(T t, U u, V v);
  }
}

// End Unions.java
