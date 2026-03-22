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
package net.hydromatic.morel.eval;

import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.util.PairList;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * One endpoint of a range: either unbounded (representing −∞ or +∞) or a
 * specific value with inclusivity.
 */
public class Bound {
  /** Sentinel representing an unbounded endpoint (−∞ or +∞). */
  public static final Bound UNBOUNDED = new Bound(null, false);

  /** The endpoint value, or {@code null} if unbounded. */
  public final @Nullable Object value;

  /**
   * Whether the endpoint is inclusive ({@code >=} or {@code <=}). Only
   * meaningful when {@link #value} is non-null.
   */
  public final boolean inclusive;

  private Bound(@Nullable Object value, boolean inclusive) {
    this.value = value;
    this.inclusive = inclusive;
  }

  /** Returns an inclusive bound at {@code v}. */
  public static Bound inclusive(Object v) {
    return new Bound(requireNonNull(v), true);
  }

  /** Returns an exclusive bound at {@code v}. */
  public static Bound exclusive(Object v) {
    return new Bound(requireNonNull(v), false);
  }

  /**
   * Converts a lower-upper {@link Bound} pair to a runtime range list value.
   */
  public static List<Object> toRange(Bound lo, Bound hi) {
    if (lo.value == null) {
      if (hi.value == null) {
        // All-encompassing range: represent as AT_LEAST of the min type —
        // this path should not normally be reached in practice.
        throw new AssertionError("cannot represent all-encompassing range");
      }
      return ImmutableList.of(
          hi.inclusive
              ? BuiltIn.Constructor.RANGE_AT_MOST.constructor
              : BuiltIn.Constructor.RANGE_LESS_THAN.constructor,
          hi.value);
    }
    if (hi.value == null) {
      return ImmutableList.of(
          lo.inclusive
              ? BuiltIn.Constructor.RANGE_AT_LEAST.constructor
              : BuiltIn.Constructor.RANGE_GREATER_THAN.constructor,
          lo.value);
    }
    // Both bounds are finite.
    if (lo.inclusive && hi.inclusive && lo.value.equals(hi.value)) {
      return ImmutableList.of(
          BuiltIn.Constructor.RANGE_POINT.constructor, lo.value);
    }
    return ImmutableList.of(
        lo.inclusive
            ? (hi.inclusive
                ? BuiltIn.Constructor.RANGE_CLOSED.constructor
                : BuiltIn.Constructor.RANGE_CLOSED_OPEN.constructor)
            : (hi.inclusive
                ? BuiltIn.Constructor.RANGE_OPEN_CLOSED.constructor
                : BuiltIn.Constructor.RANGE_OPEN.constructor),
        ImmutableList.of(lo.value, hi.value));
  }

  static void enumerate(
      Discrete<Object> discrete, Bound lo, Bound hi, Consumer<Object> out) {
    if (hi.value == null) {
      throw new Codes.MorelRuntimeException(
          Codes.BuiltInExn.SIZE, Pos.ZERO); // unbounded range
    }
    Object start;
    if (lo.value == null) {
      start = discrete.minValue();
    } else {
      if (lo.inclusive) {
        start = lo.value;
      } else {
        start = discrete.next(lo.value);
        if (start == null) {
          throw new Codes.MorelRuntimeException(
              Codes.BuiltInExn.SIZE, Pos.ZERO); // empty range
        }
      }
    }
    Comparator<Object> cmp = discrete.comparator();
    Object v = start;
    while (true) {
      int c = cmp.compare(v, hi.value);
      if (c > 0 || c == 0 && !hi.inclusive) {
        break;
      }
      out.accept(v);
      Object next = discrete.next(v);
      if (next == null) {
        break;
      }
      v = next;
    }
  }

  /** Extracts the lower {@link Bound} from a runtime range value. */
  @SuppressWarnings("rawtypes")
  static Bound lowerBound(List range) {
    BuiltIn.Constructor ctor =
        requireNonNull(BuiltIn.Constructor.forName((String) range.get(0)));
    switch (ctor) {
      case RANGE_AT_MOST:
      case RANGE_LESS_THAN:
        return UNBOUNDED;
      case RANGE_POINT:
      case RANGE_AT_LEAST:
      case RANGE_CLOSED:
      case RANGE_CLOSED_OPEN:
        return inclusive(arg0(range));
      case RANGE_GREATER_THAN:
      case RANGE_OPEN:
      case RANGE_OPEN_CLOSED:
        return exclusive(arg0(range));
      default:
        throw new AssertionError("unknown range constructor: " + ctor);
    }
  }

  /** Extracts the upper {@link Bound} from a runtime range value. */
  @SuppressWarnings("rawtypes")
  static Bound upperBound(List range) {
    BuiltIn.Constructor ctor =
        requireNonNull(BuiltIn.Constructor.forName((String) range.get(0)));
    switch (ctor) {
      case RANGE_AT_LEAST:
      case RANGE_GREATER_THAN:
        return UNBOUNDED;
      case RANGE_POINT:
      case RANGE_AT_MOST:
      case RANGE_CLOSED:
      case RANGE_OPEN_CLOSED:
        return inclusive(arg1(range));
      case RANGE_LESS_THAN:
      case RANGE_OPEN:
      case RANGE_CLOSED_OPEN:
        return exclusive(arg1(range));
      default:
        throw new AssertionError("unknown range constructor: " + ctor);
    }
  }

  /**
   * Returns the first (or only) value argument of a range. For unary
   * constructors (POINT, AT_LEAST, etc.) returns {@code range.get(1)}. For
   * binary constructors (CLOSED, OPEN, etc.) returns the tuple's first element.
   */
  @SuppressWarnings("rawtypes")
  private static Object arg0(List range) {
    Object arg = range.get(1);
    return arg instanceof List ? ((List) arg).get(0) : arg;
  }

  /**
   * Returns the second value argument of a range. For unary constructors
   * returns {@code range.get(1)}. For binary constructors returns the tuple's
   * second element.
   */
  @SuppressWarnings("rawtypes")
  private static Object arg1(List range) {
    Object arg = range.get(1);
    return arg instanceof List ? ((List) arg).get(1) : arg;
  }

  /**
   * Returns the index of the range in {@code ranges} that contains {@code x},
   * or {@code -1} if no range contains it.
   *
   * <p>Binary-searches for the last range whose lower bound is satisfied by
   * {@code x}, then checks whether {@code x} also satisfies that range's upper
   * bound.
   */
  static int rangeContaining(
      PairList<Bound, Bound> ranges, Object x, Comparator<Object> cmp) {
    int lo = 0;
    int hi = ranges.size() - 1;
    int candidate = -1;
    while (lo <= hi) {
      int mid = (lo + hi) >>> 1;
      if (ranges.left(mid).compareValue(x, cmp) >= 0) {
        candidate = mid;
        lo = mid + 1;
      } else {
        hi = mid - 1;
      }
    }
    if (candidate >= 0) {
      Bound hiB = ranges.right(candidate);
      if (hiB.value == null) {
        return candidate; // unbounded above: x is in this range
      }
      int c = cmp.compare(x, hiB.value);
      if (c < 0 || c == 0 && hiB.inclusive) {
        return candidate;
      }
    }
    return -1;
  }

  /**
   * Converts a list of runtime range values into a normalized {@link
   * PairList}{@code <Bound, Bound>}: sorts by lower bound, then merges
   * overlapping or touching ranges. If {@code discrete} is non-null, also
   * merges adjacent ranges whose effective endpoints are one step apart.
   */
  static PairList<Bound, Bound> fromRanges(
      List<List<?>> ranges,
      Comparator<Object> cmp,
      @Nullable Discrete<Object> discrete) {
    // Convert each runtime range to a pair of (lo, hi) bounds, then sort.
    final PairList<Bound, Bound> pairs = PairList.withCapacity(ranges.size());
    for (List<?> r : ranges) {
      pairs.add(lowerBound(r), upperBound(r));
    }

    // If there are 0 or 1 pairs, sorting and merging is unnecessary.
    if (pairs.size() < 2) {
      return pairs.immutable();
    }

    // Sort, then merge overlapping/touching ranges.
    pairs.sort((e1, e2) -> e1.getKey().compareLower(e2.getKey(), cmp));

    final PairList<Bound, Bound> result = PairList.of();
    Bound lo = pairs.left(0);
    Bound hi = pairs.right(0);
    for (int i = 1; i < pairs.size(); i++) {
      final Bound nextLo = pairs.left(i);
      final Bound nextHi = pairs.right(i);
      final boolean merge =
          discrete != null
              ? hi.canMergeDiscrete(nextLo, discrete)
              : hi.canMerge(nextLo, cmp);
      if (merge) {
        hi = hi.max(nextHi, cmp);
      } else {
        result.add(lo, hi);
        lo = nextLo;
        hi = nextHi;
      }
    }
    result.add(lo, hi);
    return result.immutable();
  }

  /**
   * Compares a value {@code x} against this lower bound.
   *
   * <p>Returns a positive number if {@code x} satisfies the bound (is at or
   * past the lower endpoint), zero if {@code x} exactly equals an inclusive
   * bound, or a negative number if {@code x} is strictly before the bound.
   * Returns -1 when {@code x} equals an exclusive lower bound (because {@code x
   * > lo} is required but not met).
   */
  int compareValue(Object x, Comparator<Object> cmp) {
    if (value == null) {
      return 1; // x is always past −∞
    }
    int c = cmp.compare(x, value);
    return (c == 0 && !inclusive) ? -1 : c;
  }

  /**
   * Returns whether this upper bound reaches or exceeds {@code lo2} (the lower
   * bound of the next range), meaning the two ranges overlap or touch.
   */
  boolean canMerge(Bound bound, Comparator<Object> cmp) {
    if (value == null || bound.value == null) {
      return true;
    }
    int c = cmp.compare(value, bound.value);
    if (c > 0) {
      return true;
    }
    if (c < 0) {
      return false;
    }
    return inclusive && bound.inclusive;
  }

  /**
   * Returns whether this upper bound and {@code bound} are adjacent in discrete
   * order (e.g., {@code CLOSED(1,3)} and {@code CLOSED(4,6)} for {@code int}).
   */
  boolean canMergeDiscrete(Bound bound, Discrete<Object> discrete) {
    if (value == null || bound.value == null) {
      return false;
    }
    // Compute effective last-included value of this range.
    Object hiEffective = inclusive ? value : discrete.prev(value);
    // Compute effective first-included value of the next range.
    Object loEffective =
        bound.inclusive ? bound.value : discrete.next(bound.value);
    if (hiEffective == null || loEffective == null) {
      return false;
    }
    Object nextAfterHi = discrete.next(hiEffective);
    return nextAfterHi != null
        && discrete.comparator().compare(nextAfterHi, loEffective) >= 0;
  }

  /** Returns the greater of this upper bound and {@code hi2}. */
  public Bound max(Bound bound, Comparator<Object> cmp) {
    if (value == null || bound.value == null) {
      return UNBOUNDED;
    }
    int c = cmp.compare(value, bound.value);
    if (c > 0) {
      return this;
    }
    if (c < 0) {
      return bound;
    }
    return inclusive ? this : bound;
  }

  /**
   * Compares this lower bound with {@code bound} (earlier/smaller first).
   *
   * <p>An unbounded lower bound sorts before all finite bounds. Among bounds
   * with the same value, inclusive sorts before exclusive (starts earlier).
   */
  public int compareLower(Bound bound, Comparator<Object> cmp) {
    if (value == null && bound.value == null) {
      return 0;
    }
    if (value == null) {
      return -1;
    }
    if (bound.value == null) {
      return 1;
    }
    int c = cmp.compare(value, bound.value);
    if (c != 0) {
      return c;
    }
    // Same value: inclusive sorts before exclusive (starts "earlier")
    if (inclusive && !bound.inclusive) {
      return -1;
    }
    if (!inclusive && bound.inclusive) {
      return 1;
    }
    return 0;
  }
}

// End Bound.java
