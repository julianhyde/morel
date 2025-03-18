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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Iterables;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.RandomAccess;
import java.util.Set;
import java.util.SortedMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collector;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Utilities. */
public class Static {
  private Static() {}

  /**
   * Whether to skip built-in functions.
   *
   * <p>To skip built-in functions, add "-DskipMorelBuiltIns" java's
   * command-line arguments.
   */
  public static final boolean SKIP =
      getBooleanProperty("skipMorelBuiltIns", false);

  /**
   * Method handle for {@link ImmutableList#builderWithExpectedSize(int)} (Guava
   * 23.1 and higher).
   */
  private static final @Nullable MethodHandle
      BUILDER_WITH_EXPECTED_SIZE_METHOD =
          findStaticOptional(
              ImmutableList.class,
              "builderWithExpectedSize",
              MethodType.methodType(ImmutableList.Builder.class, int.class));

  /**
   * Returns the value of a system property, converted into a boolean value.
   *
   * <p>Values "", "true", "TRUE" and "1" are treated as true; "false", "FALSE"
   * and "0" treated as false; for {@code null} and other values, returns {@code
   * defaultVal}.
   */
  @SuppressWarnings("SimplifiableConditionalExpression")
  private static boolean getBooleanProperty(String prop, boolean defaultVal) {
    final String value = System.getProperty(prop);
    if (value == null) {
      return defaultVal;
    }
    final String low = value.toLowerCase(Locale.ROOT);
    return low.equals("true") || low.equals("1") || low.isEmpty()
        ? true
        : low.equals("false") || low.equals("0") ? false : defaultVal;
  }

  /**
   * Returns a {@code Collector} that accumulates the input elements into a
   * Guava {@link ImmutableList} via a {@link ImmutableList.Builder}.
   *
   * <p>It will be obsolete when we move to Guava 21.0, which has {@code
   * ImmutableList.toImmutableList()}.
   *
   * @param <T> Type of the input elements
   * @return a {@code Collector} that collects all the input elements into an
   *     {@link ImmutableList}, in encounter order
   */
  public static <T>
      Collector<T, ImmutableList.Builder<T>, ImmutableList<T>>
          toImmutableList() {
    return Collector.of(
        ImmutableList::builder,
        ImmutableList.Builder::add,
        (t, u) -> {
          t.addAll(u.build());
          return t;
        },
        ImmutableList.Builder::build);
  }

  /**
   * Returns whether an {@link Iterable} has fewer than {@code n} elements.
   *
   * @param <E> Element type
   */
  public static <E> boolean shorterThan(Iterable<E> iterable, int n) {
    if (iterable instanceof Collection) {
      return ((Collection<E>) iterable).size() < n;
    }
    if (n <= 0) {
      return false;
    }
    int i = 0;
    for (Iterator<E> iterator = iterable.iterator();
        iterator.hasNext();
        iterator.next()) {
      if (++i == n) {
        return false;
      }
    }
    return true;
  }

  /**
   * Returns the last element of a list.
   *
   * @throws java.lang.IndexOutOfBoundsException if the list is empty
   */
  public static <E> E last(List<E> list) {
    return list.get(list.size() - 1);
  }

  /** Returns all but the first element of a list. */
  public static <E> List<E> skip(List<E> list) {
    return skip(list, 1);
  }

  /** Returns all but the first {@code count} elements of a list. */
  public static <E> List<E> skip(List<E> list, int count) {
    return list.subList(count, list.size());
  }

  /** Returns every element of a list but its last element. */
  public static <E> List<E> skipLast(List<E> list) {
    return skipLast(list, 1);
  }

  /** Returns every element of a list but its last {@code n} elements. */
  public static <E> List<E> skipLast(List<E> list, int n) {
    return list.subList(0, list.size() - n);
  }

  /**
   * Returns a list with one element appended.
   *
   * @see ConsList
   */
  public static <E> List<E> append(List<E> list, E e) {
    return ImmutableList.<E>builder().addAll(list).add(e).build();
  }

  /** Prepends an element to a list. */
  public static <E> List<E> plus(E e, List<E> list) {
    return ConsList.of(e, list);
  }

  /** Removes all occurrences of an element from a list. */
  public static <E> List<E> minus(List<E> list, E e) {
    final ImmutableList.Builder<E> builder = ImmutableList.builder();
    list.forEach(
        e2 -> {
          if (!e2.equals(e)) {
            builder.add(e2);
          }
        });
    return builder.build();
  }

  /** Adds an element to a map. */
  public static <K, V> Map<K, V> plus(Map<K, V> map, K k, V v) {
    return ImmutableMap.<K, V>builder().putAll(map).put(k, v).build();
  }

  /** Adds an element to a sorted map. */
  public static <K extends Comparable<K>, V> SortedMap<K, V> plus(
      SortedMap<K, V> map, K k, V v) {
    return new ImmutableSortedMap.Builder<K, V>(map.comparator())
        .putAll(map)
        .put(k, v)
        .build();
  }

  /** Next power of two. */
  public static int nextPowerOfTwo(int n) {
    final int p = Integer.numberOfLeadingZeros(n);
    return 1 << (Integer.SIZE - p);
  }

  /** Lazily transforms a list, applying a mapping function to each element. */
  public static <E, T> List<T> transform(
      List<? extends E> elements, Function<E, T> mapper) {
    return org.apache.calcite.util.Util.transform(elements, mapper);
  }

  /**
   * Lazily transforms an Iterable, applying a mapping function to each element.
   */
  public static <E, T> Iterable<T> transform(
      Iterable<? extends E> elements, Function<E, T> mapper) {
    return Iterables.transform(elements, mapper::apply);
  }

  /**
   * Eagerly converts an Iterable to an ImmutableList, applying a mapping
   * function to each element.
   */
  public static <E, T> ImmutableList<T> transformEager(
      Iterable<? extends E> elements, Function<E, T> mapper) {
    if (elements instanceof Collection) {
      // If elements is a Collection, we can optimize by pre-sizing the builder
      return transformEager((Collection<? extends E>) elements, mapper);
    }
    final ImmutableList.Builder<T> b = ImmutableList.builder();
    elements.forEach(e -> b.add(mapper.apply(e)));
    return b.build();
  }

  /**
   * Eagerly converts a Collection to an ImmutableList, applying a mapping
   * function to each element.
   *
   * <p>More efficient than {@link #transformEager(Iterable, Function)}, because
   * we can optimize the size of the builder for the size of the collection, and
   * can avoid creating a builder if the collection is empty.
   */
  public static <E, T> ImmutableList<T> transformEager(
      Collection<? extends E> elements, Function<E, T> mapper) {
    if (elements.isEmpty()) {
      // Save ourselves the effort of creating a Builder.
      return ImmutableList.of();
    }

    // Optimize by making the builder the same size as the collection.
    final ImmutableList.Builder<T> b = builderWithExpectedSize(elements.size());
    elements.forEach(e -> b.add(mapper.apply(e)));
    return b.build();
  }

  /** Equivalent to {@link ImmutableList#builderWithExpectedSize(int)}. */
  @SuppressWarnings("unchecked")
  public static <E> ImmutableList.Builder<E> builderWithExpectedSize(
      int expectedSize) {
    if (BUILDER_WITH_EXPECTED_SIZE_METHOD == null) {
      return ImmutableList.builder();
    }
    try {
      return (ImmutableList.Builder<E>)
          BUILDER_WITH_EXPECTED_SIZE_METHOD.invoke(expectedSize);
    } catch (RuntimeException | Error e) {
      throw e;
    } catch (Throwable e) {
      throw new RuntimeException(e);
    }
  }

  /** Returns the first index in a list where a predicate is true, or -1. */
  public static <E> int find(List<? extends E> list, Predicate<E> predicate) {
    if (list instanceof RandomAccess) {
      for (int i = 0; i < list.size(); i++) {
        if (predicate.test(list.get(i))) {
          return i;
        }
      }
    } else {
      int i = 0;
      for (E e : list) {
        if (predicate.test(e)) {
          return i;
        }
        ++i;
      }
    }
    return -1;
  }

  /**
   * Returns a list containing the elements of {@code list0} that are also in
   * {@code list1}. The result preserves the order of {@code list1}, and any
   * duplicates it may contain.
   */
  public static <E> List<E> intersect(
      List<E> list0, Iterable<? extends E> list1) {
    final ImmutableList.Builder<E> list2 = ImmutableList.builder();
    final Set<E> set = new HashSet<>(list0);
    for (E e : list1) {
      if (set.contains(e)) {
        list2.add(e);
      }
    }
    return list2.build();
  }

  /** Flushes a builder and returns its contents. */
  public static String str(StringBuilder b) {
    String s = b.toString();
    b.setLength(0);
    return s;
  }

  /** Returns whether a builder ends with a given string. */
  public static boolean endsWith(StringBuilder buf, String s) {
    final int i = buf.length() - s.length();
    return i >= 0 && buf.indexOf(s, i) == i;
  }

  /** Looks up a method and returns its handle, or null if not found. */
  @SuppressWarnings("SameParameterValue")
  private static @Nullable MethodHandle findStaticOptional(
      Class<?> refc, String name, MethodType type) {
    try {
      return MethodHandles.lookup().findStatic(refc, name, type);
    } catch (NoSuchMethodException | IllegalAccessException e) {
      return null;
    }
  }
}

// End Static.java
