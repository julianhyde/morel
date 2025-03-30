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

import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.AbstractList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.RandomAccess;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import org.apache.calcite.linq4j.function.Functions;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Various implementations of {@link PairList}. */
class PairLists {
  static final ImmutablePairList<Object, Object> EMPTY =
      new EmptyImmutablePairList<>();

  private PairLists() {}

  @SuppressWarnings("unchecked")
  static <T, U> ImmutablePairList<T, U> immutableBackedBy(List<Object> list) {
    switch (list.size()) {
      case 0:
        return ImmutablePairList.of();
      case 2:
        return new SingletonImmutablePairList<>(
            (T) list.get(0), (U) list.get(1));
      default:
        return new ArrayImmutablePairList<>(list.toArray());
    }
  }

  @CanIgnoreReturnValue
  static Object[] checkElementsNotNull(Object... elements) {
    for (int i = 0; i < elements.length; i++) {
      checkElementNotNull(i, elements[i]);
    }
    return elements;
  }

  static void checkElementNotNull(int i, Object element) {
    if (element == null) {
      throw new NullPointerException(
          (i % 2 == 0 ? "key" : "value") + " at index " + (i / 2));
    }
  }

  /**
   * Base class for all implementations of PairList.
   *
   * @param <T> First type
   * @param <U> Second type
   */
  abstract static class AbstractPairList<T, U>
      extends AbstractList<Map.Entry<T, U>> implements PairList<T, U> {
    /**
     * Returns a list containing the alternating left and right elements of each
     * pair.
     */
    abstract List<Object> backingList();
  }

  /**
   * Mutable version of {@link PairList}.
   *
   * @param <T> First type
   * @param <U> Second type
   */
  static class MutablePairList<T, U> extends AbstractPairList<T, U> {
    final List<@Nullable Object> list;

    MutablePairList(List<@Nullable Object> list) {
      this.list = list;
    }

    @Override
    List<Object> backingList() {
      return list;
    }

    @Override
    public void clear() {
      list.clear();
    }

    @Override
    public int size() {
      return list.size() / 2;
    }

    @Override
    public boolean isEmpty() {
      return list.isEmpty();
    }

    @SuppressWarnings("unchecked")
    @Override
    public Map.Entry<T, U> get(int index) {
      int x = index * 2;
      return new MapEntry<>((T) list.get(x), (U) list.get(x + 1));
    }

    @SuppressWarnings("unchecked")
    @Override
    public T left(int index) {
      int x = index * 2;
      return (T) list.get(x);
    }

    @SuppressWarnings("unchecked")
    @Override
    public U right(int index) {
      int x = index * 2;
      return (U) list.get(x + 1);
    }

    @Override
    public Map.Entry<T, U> set(int index, Map.Entry<T, U> entry) {
      return set(index, entry.getKey(), entry.getValue());
    }

    @SuppressWarnings("unchecked")
    @Override
    public Map.Entry<T, U> set(int index, T t, U u) {
      int x = index * 2;
      T t0 = (T) list.set(x, t);
      U u0 = (U) list.set(x + 1, u);
      return new MapEntry<>(t0, u0);
    }

    @SuppressWarnings("unchecked")
    @Override
    public Map.Entry<T, U> remove(int index) {
      final int x = index * 2;
      T t = (T) list.remove(x);
      U u = (U) list.remove(x);
      return new MapEntry<>(t, u);
    }

    @SuppressWarnings("RedundantCast")
    @Override
    public boolean add(Map.Entry<T, U> entry) {
      list.add((Object) entry.getKey());
      list.add((Object) entry.getValue());
      return true;
    }

    @SuppressWarnings("RedundantCast")
    @Override
    public void add(int index, Map.Entry<T, U> entry) {
      int x = index * 2;
      list.add(x, (Object) entry.getKey());
      list.add(x + 1, (Object) entry.getValue());
    }

    @SuppressWarnings("RedundantCast")
    @Override
    public void add(T t, U u) {
      list.add((Object) t);
      list.add((Object) u);
    }

    @SuppressWarnings("RedundantCast")
    @Override
    public void add(int index, T t, U u) {
      int x = index * 2;
      list.add(x, (Object) t);
      list.add(x + 1, (Object) u);
    }

    @Override
    public boolean addAll(PairList<T, U> list2) {
      return list.addAll(((AbstractPairList<T, U>) list2).backingList());
    }

    @Override
    public boolean addAll(int index, PairList<T, U> list2) {
      int x = index * 2;
      return list.addAll(x, ((AbstractPairList<T, U>) list2).backingList());
    }

    @SuppressWarnings("unchecked")
    @Override
    public List<T> leftList() {
      return new RandomAccessList<T>() {
        @Override
        public int size() {
          return list.size() / 2;
        }

        @Override
        public T get(int index) {
          return (T) list.get(index * 2);
        }

        @Override
        public T set(int index, T element) {
          return (T) list.set(index * 2, element);
        }

        @Override
        public T remove(int index) {
          return (T) list.remove(index * 2);
        }
      };
    }

    @SuppressWarnings("unchecked")
    @Override
    public List<U> rightList() {
      return new RandomAccessList<U>() {
        @Override
        public int size() {
          return list.size() / 2;
        }

        @Override
        public U get(int index) {
          return (U) list.get(index * 2 + 1);
        }

        @Override
        public U set(int index, U element) {
          return (U) list.set(index * 2 + 1, element);
        }

        @Override
        public U remove(int index) {
          return (U) list.remove(index * 2 + 1);
        }
      };
    }

    @SuppressWarnings("unchecked")
    @Override
    public void forEach(BiConsumer<T, U> consumer) {
      requireNonNull(consumer, "consumer");
      for (int i = 0; i < list.size(); ) {
        T t = (T) list.get(i++);
        U u = (U) list.get(i++);
        consumer.accept(t, u);
      }
    }

    @SuppressWarnings("unchecked")
    @Override
    public void forEachIndexed(IndexedBiConsumer<T, U> consumer) {
      requireNonNull(consumer, "consumer");
      for (int i = 0, j = 0; i < list.size(); ) {
        T t = (T) list.get(i++);
        U u = (U) list.get(i++);
        consumer.accept(j++, t, u);
      }
    }

    @Override
    public ImmutableMap<T, U> toImmutableMap() {
      final ImmutableMap.Builder<T, U> b = ImmutableMap.builder();
      forEach((t, u) -> b.put(t, u));
      return b.build();
    }

    @Override
    public ImmutablePairList<T, U> immutable() {
      return immutableBackedBy(list);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <R> List<R> transform(BiFunction<T, U, R> function) {
      return Functions.generate(
          list.size() / 2,
          index -> {
            final int x = index * 2;
            final T t = (T) list.get(x);
            final U u = (U) list.get(x + 1);
            return function.apply(t, u);
          });
    }

    @SuppressWarnings("unchecked")
    @Override
    public <R> ImmutableList<R> transform2(BiFunction<T, U, R> function) {
      if (list.isEmpty()) {
        return ImmutableList.of();
      }
      final ImmutableList.Builder<R> builder = ImmutableList.builder();
      for (int i = 0, n = list.size(); i < n; ) {
        final T t = (T) list.get(i++);
        final U u = (U) list.get(i++);
        builder.add(function.apply(t, u));
      }
      return builder.build();
    }

    @SuppressWarnings("unchecked")
    @Override
    public boolean anyMatch(BiPredicate<T, U> predicate) {
      for (int i = 0; i < list.size(); ) {
        final T t = (T) list.get(i++);
        final U u = (U) list.get(i++);
        if (predicate.test(t, u)) {
          return true;
        }
      }
      return false;
    }

    @SuppressWarnings("unchecked")
    @Override
    public boolean allMatch(BiPredicate<T, U> predicate) {
      for (int i = 0; i < list.size(); ) {
        final T t = (T) list.get(i++);
        final U u = (U) list.get(i++);
        if (!predicate.test(t, u)) {
          return false;
        }
      }
      return true;
    }

    @SuppressWarnings("unchecked")
    @Override
    public boolean noMatch(BiPredicate<T, U> predicate) {
      for (int i = 0; i < list.size(); ) {
        final T t = (T) list.get(i++);
        final U u = (U) list.get(i++);
        if (predicate.test(t, u)) {
          return false;
        }
      }
      return true;
    }

    @SuppressWarnings("unchecked")
    @Override
    public int firstMatch(BiPredicate<T, U> predicate) {
      for (int i = 0, j = 0; i < list.size(); ++j) {
        final T t = (T) list.get(i++);
        final U u = (U) list.get(i++);
        if (predicate.test(t, u)) {
          return j;
        }
      }
      return -1;
    }
  }

  /**
   * Empty immutable list of pairs.
   *
   * @param <T> First type
   * @param <U> Second type
   */
  static class EmptyImmutablePairList<T, U> extends AbstractPairList<T, U>
      implements ImmutablePairList<T, U> {
    @Override
    List<Object> backingList() {
      return ImmutableList.of();
    }

    @Override
    public Map.Entry<T, U> get(int index) {
      throw new IndexOutOfBoundsException("Index out of range: " + index);
    }

    @Override
    public T left(int index) {
      throw new IndexOutOfBoundsException("Index out of range: " + index);
    }

    @Override
    public U right(int index) {
      throw new IndexOutOfBoundsException("Index out of range: " + index);
    }

    @Override
    public int size() {
      return 0;
    }

    @Override
    public List<T> leftList() {
      return ImmutableList.of();
    }

    @Override
    public List<U> rightList() {
      return ImmutableList.of();
    }

    @Override
    public void forEach(BiConsumer<T, U> consumer) {}

    @Override
    public void forEachIndexed(IndexedBiConsumer<T, U> consumer) {}

    @Override
    public <R> List<R> transform(BiFunction<T, U, R> function) {
      return ImmutableList.of();
    }

    @Override
    public <R> ImmutableList<R> transform2(BiFunction<T, U, R> function) {
      return ImmutableList.of();
    }

    @Override
    public boolean anyMatch(BiPredicate<T, U> predicate) {
      return false;
    }

    @Override
    public boolean allMatch(BiPredicate<T, U> predicate) {
      return true;
    }

    @Override
    public boolean noMatch(BiPredicate<T, U> predicate) {
      return true;
    }

    @Override
    public int firstMatch(BiPredicate<T, U> predicate) {
      return -1;
    }
  }

  /**
   * Immutable list that contains one pair.
   *
   * @param <T> First type
   * @param <U> Second type
   */
  static class SingletonImmutablePairList<T, U> extends AbstractPairList<T, U>
      implements ImmutablePairList<T, U> {
    private final T t;
    private final U u;

    SingletonImmutablePairList(T t, U u) {
      this.t = t;
      this.u = u;
      checkElementNotNull(0, t);
      checkElementNotNull(1, u);
    }

    @Override
    List<Object> backingList() {
      return ImmutableList.of(t, u);
    }

    @Override
    public Map.Entry<T, U> get(int index) {
      if (index != 0) {
        throw new IndexOutOfBoundsException("Index out of range: " + index);
      }
      return new MapEntry<>(t, u);
    }

    @Override
    public T left(int index) {
      if (index != 0) {
        throw new IndexOutOfBoundsException("Index out of range: " + index);
      }
      return t;
    }

    @Override
    public U right(int index) {
      if (index != 0) {
        throw new IndexOutOfBoundsException("Index out of range: " + index);
      }
      return u;
    }

    @Override
    public int size() {
      return 1;
    }

    @Override
    public List<T> leftList() {
      return ImmutableList.of(t);
    }

    @Override
    public List<U> rightList() {
      return ImmutableList.of(u);
    }

    @Override
    public void forEach(BiConsumer<T, U> consumer) {
      consumer.accept(t, u);
    }

    @Override
    public void forEachIndexed(IndexedBiConsumer<T, U> consumer) {
      consumer.accept(0, t, u);
    }

    @Override
    public <R> List<R> transform(BiFunction<T, U, R> function) {
      return ImmutableList.of(function.apply(t, u));
    }

    @Override
    public <R> ImmutableList<R> transform2(BiFunction<T, U, R> function) {
      return ImmutableList.of(function.apply(t, u));
    }

    @Override
    public boolean anyMatch(BiPredicate<T, U> predicate) {
      return predicate.test(t, u);
    }

    @Override
    public boolean allMatch(BiPredicate<T, U> predicate) {
      return predicate.test(t, u);
    }

    @Override
    public boolean noMatch(BiPredicate<T, U> predicate) {
      return !predicate.test(t, u);
    }

    @Override
    public int firstMatch(BiPredicate<T, U> predicate) {
      return predicate.test(t, u) ? 0 : -1;
    }
  }

  /**
   * Base class for a list that implements {@link RandomAccess}.
   *
   * @param <E> Element type
   */
  abstract static class RandomAccessList<E> extends AbstractList<E>
      implements RandomAccess {}

  /**
   * Immutable list of pairs backed by an array.
   *
   * @param <T> First type
   * @param <U> Second type
   */
  static class ArrayImmutablePairList<T, U> extends AbstractPairList<T, U>
      implements ImmutablePairList<T, U> {
    private final Object[] elements;

    /**
     * Creates an ArrayImmutablePairList.
     *
     * <p>Does not copy the {@code elements} array. Assumes that the caller has
     * made a copy, and will never modify the contents.
     *
     * <p>Assumes that {@code elements} is not null, but checks that none of its
     * elements are null.
     */
    ArrayImmutablePairList(Object[] elements) {
      this.elements = checkElementsNotNull(elements);
    }

    @Override
    List<Object> backingList() {
      return Arrays.asList(elements);
    }

    @SuppressWarnings("unchecked")
    @Override
    public Map.Entry<T, U> get(int index) {
      int x = index * 2;
      return new MapEntry<>((T) elements[x], (U) elements[x + 1]);
    }

    @SuppressWarnings("unchecked")
    @Override
    public T left(int index) {
      int x = index * 2;
      return (T) elements[x];
    }

    @SuppressWarnings("unchecked")
    @Override
    public U right(int index) {
      int x = index * 2;
      return (U) elements[x + 1];
    }

    @Override
    public int size() {
      return elements.length / 2;
    }

    @Override
    public List<T> leftList() {
      return new RandomAccessList<T>() {
        @Override
        public int size() {
          return elements.length / 2;
        }

        @SuppressWarnings("unchecked")
        @Override
        public T get(int index) {
          return (T) elements[index * 2];
        }
      };
    }

    @Override
    public List<U> rightList() {
      return new RandomAccessList<U>() {
        @Override
        public int size() {
          return elements.length / 2;
        }

        @SuppressWarnings("unchecked")
        @Override
        public U get(int index) {
          return (U) elements[index * 2 + 1];
        }
      };
    }

    @SuppressWarnings("unchecked")
    @Override
    public void forEach(BiConsumer<T, U> consumer) {
      for (int x = 0; x < elements.length; ) {
        T t = (T) elements[x++];
        U u = (U) elements[x++];
        consumer.accept(t, u);
      }
    }

    @SuppressWarnings("unchecked")
    @Override
    public void forEachIndexed(IndexedBiConsumer<T, U> consumer) {
      for (int x = 0, i = 0; x < elements.length; ) {
        T t = (T) elements[x++];
        U u = (U) elements[x++];
        consumer.accept(i++, t, u);
      }
    }

    @SuppressWarnings("unchecked")
    @Override
    public <R> List<R> transform(BiFunction<T, U, R> function) {
      return Functions.generate(
          elements.length / 2,
          index -> {
            final int x = index * 2;
            final T t = (T) elements[x];
            final U u = (U) elements[x + 1];
            return function.apply(t, u);
          });
    }

    @SuppressWarnings("unchecked")
    @Override
    public <R> ImmutableList<R> transform2(BiFunction<T, U, R> function) {
      final ImmutableList.Builder<R> builder = ImmutableList.builder();
      for (int i = 0; i < elements.length; ) {
        final T t = (T) elements[i++];
        final U u = (U) elements[i++];
        builder.add(function.apply(t, u));
      }
      return builder.build();
    }

    @SuppressWarnings("unchecked")
    @Override
    public boolean anyMatch(BiPredicate<T, U> predicate) {
      for (int i = 0; i < elements.length; ) {
        final T t = (T) elements[i++];
        final U u = (U) elements[i++];
        if (predicate.test(t, u)) {
          return true;
        }
      }
      return false;
    }

    @SuppressWarnings("unchecked")
    @Override
    public boolean allMatch(BiPredicate<T, U> predicate) {
      for (int i = 0; i < elements.length; ) {
        final T t = (T) elements[i++];
        final U u = (U) elements[i++];
        if (!predicate.test(t, u)) {
          return false;
        }
      }
      return true;
    }

    @SuppressWarnings("unchecked")
    @Override
    public boolean noMatch(BiPredicate<T, U> predicate) {
      for (int i = 0; i < elements.length; ) {
        final T t = (T) elements[i++];
        final U u = (U) elements[i++];
        if (predicate.test(t, u)) {
          return false;
        }
      }
      return true;
    }

    @SuppressWarnings("unchecked")
    @Override
    public int firstMatch(BiPredicate<T, U> predicate) {
      for (int i = 0, j = 0; i < elements.length; ++j) {
        final T t = (T) elements[i++];
        final U u = (U) elements[i++];
        if (predicate.test(t, u)) {
          return j;
        }
      }
      return -1;
    }
  }
}

// End PairLists.java
