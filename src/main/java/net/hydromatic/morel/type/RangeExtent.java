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
package net.hydromatic.morel.type;

import net.hydromatic.morel.ast.Op;

import com.google.common.collect.ContiguousSet;
import com.google.common.collect.DiscreteDomain;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableRangeSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Range;
import com.google.common.collect.RangeSet;
import org.apache.calcite.runtime.FlatLists;
import org.apache.calcite.runtime.Unit;
import org.apache.calcite.util.RangeSets;
import org.apache.calcite.util.Util;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.annotation.CheckForNull;

import static java.util.Objects.requireNonNull;

/** A type and a range set. */
@SuppressWarnings({"UnstableApiUsage", "rawtypes"})
public class RangeExtent {
  public final RangeSet rangeSet;
  public final Type type;
  public final @Nullable Iterable iterable;

  private static final List<Boolean> BOOLEANS = ImmutableList.of(false, true);

  /** Creates a RangeExtent. */
  @SuppressWarnings("unchecked")
  public RangeExtent(RangeSet rangeSet, Type type) {
    this.rangeSet = ImmutableRangeSet.copyOf(rangeSet);
    this.type = type;
    this.iterable = toList(type, rangeSet);
  }

  @Override public String toString() {
    if (isUnbounded()) {
      return type.toString(); // range set is unconstrained; don't print it
    }
    return type + " " + rangeSet;
  }

  /** Whether this extent returns all, or an unbounded number of, the values of
   * its type.
   *
   * <p>Examples:
   * "(-inf,+inf)" (true),
   * "(-inf,0]" (x <= 0),
   * "{(-inf,3),(10,+inf)}" (x < 3 or x > 10) are unbounded;
   * "{}" (false),
   * "{3, 10}" (x in [3, 10]),
   * "(3, 10)" (x >= 3 andalso x <= 10) are bounded. */
  public boolean isUnbounded() {
    return rangeSet.complement().isEmpty();
  }

  /** Whether this extent returns all, or an unbounded number of, the values of
   * its type.
   *
   * <p>Examples:
   * "(-inf,+inf)" (true),
   * "(-inf,0]" (x <= 0),
   * "{(-inf,3),(10,+inf)}" (x < 3 or x > 10) are unbounded;
   * "{}" (false),
   * "{3, 10}" (x in [3, 10]),
   * "(3, 10)" (x >= 3 andalso x <= 10) are bounded. */
  @SuppressWarnings("unchecked")
  public boolean isSomewhatUnbounded() {
    return ((RangeSet<Comparable>) rangeSet).asRanges().stream()
        .anyMatch(r -> !r.hasLowerBound() || !r.hasUpperBound());
  }

  /** Derives the collection of values in the range, or returns empty if
   * the range is infinite. */
  private <E extends Comparable<E>> Iterable<E> toList(Type type,
      RangeSet<E> rangeSet) {
    final List<E> list = new ArrayList<>();
    if (type.populate(rangeSet, list::add)) {
      return list;
    }
    return null;
  }

  /** Derives the collection of values in the range, or returns empty if
   * the range is infinite. */
  private <E extends Comparable<E>> Optional<Iterable<E>> toIterable(Type type,
      TypeSystem typeSystem, RangeSet<E> rangeSet) {
    final List<Iterable<E>> setList = new ArrayList<>();
    for (Range<E> range : rangeSet.asRanges()) {
      final Optional<Iterable<E>> optionalIterable =
          toIterable(type, typeSystem, range);
      if (!optionalIterable.isPresent()) {
        return Optional.empty();
      }
      setList.add(optionalIterable.get());
    }
    return Optional.of(concat(setList));
  }

  /** Returns the collection of values in the range. */
  @SuppressWarnings({"unchecked", "rawtypes"})
  private <E extends Comparable<E>> Optional<Iterable<E>> toIterable(Type type,
      TypeSystem typeSystem, Range<E> range) {
    switch (type.op()) {
    case ID:
      final PrimitiveType primitiveType = (PrimitiveType) type;
      switch (primitiveType) {
      case INT:
        final Range<BigDecimal> bigDecimalRange = (Range) range;
        return Optional.of(
            (Iterable<E>) ContiguousSet.create(
                RangeSets.copy(bigDecimalRange, BigDecimal::intValue),
                DiscreteDomain.integers()));

      case BOOL:
        final Range<Boolean> booleanRange = (Range) range;
        return Optional.of(
            (Iterable<E>) Iterables.filter(BOOLEANS, booleanRange::contains));
      }
      break;

    case TUPLE_TYPE:
      final TupleType tupleType = (TupleType) type;
      // TODO: copy rangeSet, to convert embedded BigDecimal to Integer
      final DiscreteDomain<E> domain = discreteDomain(tupleType, typeSystem);
      if (domain instanceof UnenumeratedDiscreteDomain) {
        break;
      }
      return Optional.of(ContiguousSet.create(range, domain));
    }
    // Cannot convert type to iterable. Perhaps it is infinite.
    return Optional.empty();
  }

  @SuppressWarnings("unchecked")
  private <E extends Comparable<E>> DiscreteDomain<E> discreteDomain(Type type,
      TypeSystem typeSystem) {
    switch (type.op()) {
    case ID:
      final PrimitiveType primitiveType = (PrimitiveType) type;
      switch (primitiveType) {
      case UNIT:
        return (DiscreteDomain<E>)
            new EnumeratedDiscreteDomain<>(ImmutableList.of(Unit.INSTANCE));

      case BOOL:
        return (DiscreteDomain<E>)
            new EnumeratedDiscreteDomain<>(ImmutableList.of(false, true));

      case INT:
        return (DiscreteDomain<E>) DiscreteDomain.integers();

      case STRING:
        return (DiscreteDomain<E>)
            new UnenumeratedDiscreteDomain<>(String.class);
      }
      break;

    case TUPLE_TYPE:
    case RECORD_TYPE:
      final RecordLikeType tupleType = (RecordLikeType) type;
      final List<DiscreteDomain> domains =
          tupleType.argTypes().stream().map(t -> discreteDomain(t, typeSystem))
              .collect(Util.toImmutableList());
      if (domains.stream()
          .anyMatch(d -> d instanceof UnenumeratedDiscreteDomain)) {
        return (DiscreteDomain<E>)
            new UnenumeratedDiscreteDomain<>(Comparable.class);
      }
      return (DiscreteDomain<E>) new ProductDiscreteDomain(domains);

    case DATA_TYPE:
      final DataType dataType = (DataType) type;
      final List<Comparable> list = new ArrayList<>();
      dataType.typeConstructors(typeSystem).forEach((name, type1) -> {
        if (type1.op() == Op.DUMMY_TYPE) {
          list.add(name);
        } else {
          DiscreteDomain d = discreteDomain(type1, typeSystem);
          for (Comparable v = d.minValue(); v != null; v = d.next(v)) {
            list.add((Comparable) FlatLists.of(name, v));
          }
        }
      });
      return (DiscreteDomain<E>) new EnumeratedDiscreteDomain<>(list);
    }

    throw new AssertionError("cannot convert type '" + type
        + "' to discrete domain");
  }

  /** Calls {@link Iterables#concat(Iterable)}, optimizing for the case with 0
   * or 1 entries. */
  private static <E> Iterable<? extends E> concat(
      List<? extends Iterable<? extends E>> iterableList) {
    switch (iterableList.size()) {
    case 0:
      return ImmutableList.of();
    case 1:
      return iterableList.get(0);
    default:
      return Iterables.concat(iterableList);
    }
  }

  private static class EnumeratedDiscreteDomain<C extends Comparable<C>>
      extends DiscreteDomain<C> {
    private final List<C> values;

    protected EnumeratedDiscreteDomain(List<C> values) {
      this.values = requireNonNull(values, "values");
    }

    @CheckForNull
    @Override public @Nullable C next(C value) {
      int i = values.indexOf(value);
      return i + 1 >= values.size() ? null : values.get(i + 1);
    }

    @CheckForNull @Override public @Nullable C previous(C value) {
      int i = values.indexOf(value);
      return i - 1 < 0 ? null : values.get(i - 1);
    }

    @Override public long distance(C start, C end) {
      int iStart = values.indexOf(start);
      int iEnd = values.indexOf(end);
      return iEnd - iStart;
    }

    @Override public C minValue() {
      return values.get(0);
    }

    @Override public C maxValue() {
      return values.get(values.size() - 1);
    }
  }

  private static class UnenumeratedDiscreteDomain<C extends Comparable<C>>
      extends DiscreteDomain<C> {
    UnenumeratedDiscreteDomain(Class<C> unused) {
    }

    @CheckForNull
    @Override public @Nullable C next(C value) {
      throw new UnsupportedOperationException();
    }

    @CheckForNull @Override public @Nullable C previous(C value) {
      throw new UnsupportedOperationException();
    }

    @Override public long distance(C start, C end) {
      throw new UnsupportedOperationException();
    }

    @Override public C minValue() {
      throw new UnsupportedOperationException();
    }

    @Override public C maxValue() {
      throw new UnsupportedOperationException();
    }
  }

  private static class ProductDiscreteDomain
      extends DiscreteDomain<FlatLists.ComparableList<Comparable>> {
    private final List<DiscreteDomain> domains;
    private final FlatLists.ComparableList<Comparable> minValue;
    private final FlatLists.ComparableList<Comparable> maxValues;

    ProductDiscreteDomain(List<DiscreteDomain> domains) {
      this.domains = ImmutableList.copyOf(domains);
      this.minValue = FlatLists.ofComparable(
          domains.stream()
              .map(DiscreteDomain::minValue)
              .collect(Collectors.toList()));
      this.maxValues = FlatLists.ofComparable(
          domains.stream()
              .map(DiscreteDomain::maxValue)
              .collect(Collectors.toList()));
    }

    @CheckForNull @Override public FlatLists.ComparableList<Comparable> next(
        FlatLists.ComparableList<Comparable> values) {
      final Comparable[] objects = values.toArray(new Comparable[0]);
      for (int i = 0; i < values.size(); i++) {
        Comparable value = values.get(i);
        final DiscreteDomain domain = domains.get(i);
        Comparable next = domain.next(value);
        if (next != null) {
          objects[i] = next;
          return (FlatLists.ComparableList) FlatLists.of(objects);
        }
        objects[i] = domain.minValue();
      }
      return null;
    }

    @CheckForNull @Override public FlatLists.ComparableList<Comparable> previous(
        FlatLists.ComparableList<Comparable> values) {
      throw new UnsupportedOperationException(); // TODO implement, like next
    }

    @Override public long distance(FlatLists.ComparableList<Comparable> start,
        FlatLists.ComparableList<Comparable> end) {
      // A better implementation might be to compute distances between each
      // pair of values, and multiply by the number of superior values.
      long d = 0;
      for (FlatLists.ComparableList<Comparable> c = start;
           c != null && c.compareTo(end) < 0;
           c = next(c)) {
        ++d;
      }
      return d;
    }
  }
}

// End RangeExtent.java
