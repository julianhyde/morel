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
package net.hydromatic.morel.eval.code;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.math.BigInteger;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiConsumer;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.eval.Applicable;
import net.hydromatic.morel.eval.Applicable1;
import net.hydromatic.morel.eval.BuiltInExn;
import net.hydromatic.morel.eval.Codes.Typed;
import net.hydromatic.morel.eval.Comparators;
import net.hydromatic.morel.eval.Discrete;
import net.hydromatic.morel.eval.Discretes;
import net.hydromatic.morel.eval.MorelRuntimeException;
import net.hydromatic.morel.eval.Prop;
import net.hydromatic.morel.eval.Stack;
import net.hydromatic.morel.type.DataType;
import net.hydromatic.morel.type.FnType;
import net.hydromatic.morel.type.ListType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.PairList;
import org.jspecify.annotations.Nullable;

/**
 * Implementations of built-in functions and values in the {@code Range}
 * structure.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public final class RangeCodes {
  private RangeCodes() {}

  /** Registers the implementations in this class. */
  public static void register(BiConsumer<BuiltIn, Object> c) {
    // lint: sort until '#}' where '##c\.accept\(BuiltIn' erase 'c\.'
    c.accept(BuiltIn.RANGE_CONTAINS, RANGE_CONTAINS);
    c.accept(
        BuiltIn.RANGE_CONTINUOUS_SET_COMPLEMENT,
        RANGE_CONTINUOUS_SET_COMPLEMENT);
    c.accept(
        BuiltIn.RANGE_CONTINUOUS_SET_CONTAINS, RANGE_CONTINUOUS_SET_CONTAINS);
    c.accept(BuiltIn.RANGE_CONTINUOUS_SET_OF, RANGE_CONTINUOUS_SET_OF);
    c.accept(BuiltIn.RANGE_CONTINUOUS_SET_RANGES, RANGE_CONTINUOUS_SET_RANGES);
    c.accept(
        BuiltIn.RANGE_DISCRETE_SET_COMPLEMENT, RANGE_DISCRETE_SET_COMPLEMENT);
    c.accept(BuiltIn.RANGE_DISCRETE_SET_CONTAINS, RANGE_DISCRETE_SET_CONTAINS);
    c.accept(BuiltIn.RANGE_DISCRETE_SET_OF, RANGE_DISCRETE_SET_OF);
    c.accept(BuiltIn.RANGE_DISCRETE_SET_RANGES, RANGE_DISCRETE_SET_RANGES);
    c.accept(BuiltIn.RANGE_DISCRETE_SET_TO_BAG, RANGE_DISCRETE_SET_TO_BAG);
    c.accept(BuiltIn.RANGE_DISCRETE_SET_TO_LIST, RANGE_DISCRETE_SET_TO_LIST);
    c.accept(BuiltIn.RANGE_FLATTEN, RANGE_FLATTEN);
  }

  // lint: sort until '#}' \
  //   where '##private static final [^ ]+ [^ ]+ =' \
  //   erase 'private static final [^ ]+ '

  /**
   * Returns the largest number of values that expanding a range may produce,
   * from the session that {@code stack} belongs to.
   */
  private static BigInteger rangeMaxLength(Stack stack) {
    return Prop.RANGE_MAX_LENGTH.bigIntegerValue(stack.session.map);
  }

  /** @see BuiltIn#RANGE_CONTAINS */
  private static final Applicable RANGE_CONTAINS =
      new RangeContains(Comparators::compare);

  /** @see BuiltIn#RANGE_CONTINUOUS_SET_COMPLEMENT */
  private static final Applicable RANGE_CONTINUOUS_SET_COMPLEMENT =
      new SetComplement(BuiltIn.RANGE_CONTINUOUS_SET_COMPLEMENT, null);

  /** @see BuiltIn#RANGE_CONTINUOUS_SET_CONTAINS */
  private static final Applicable RANGE_CONTINUOUS_SET_CONTAINS =
      new SetContains(
          BuiltIn.RANGE_CONTINUOUS_SET_CONTAINS, Comparators::compare);

  /** @see BuiltIn#RANGE_CONTINUOUS_SET_OF */
  private static final Applicable RANGE_CONTINUOUS_SET_OF =
      new ContinuousSetOf(Comparators::compare);

  /** @see BuiltIn#RANGE_CONTINUOUS_SET_RANGES */
  private static final Applicable RANGE_CONTINUOUS_SET_RANGES =
      new SetRanges(BuiltIn.RANGE_CONTINUOUS_SET_RANGES);

  /** @see BuiltIn#RANGE_DISCRETE_SET_COMPLEMENT */
  private static final Applicable RANGE_DISCRETE_SET_COMPLEMENT =
      new SetComplement(
          BuiltIn.RANGE_DISCRETE_SET_COMPLEMENT, Discretes.dummy());

  /** @see BuiltIn#RANGE_DISCRETE_SET_CONTAINS */
  private static final Applicable RANGE_DISCRETE_SET_CONTAINS =
      new SetContains(
          BuiltIn.RANGE_DISCRETE_SET_CONTAINS, Comparators::compare);

  /** @see BuiltIn#RANGE_DISCRETE_SET_OF */
  private static final Applicable RANGE_DISCRETE_SET_OF =
      new DiscreteSetOf(Comparators::compare, Discretes.dummy());

  /** @see BuiltIn#RANGE_DISCRETE_SET_RANGES */
  private static final Applicable RANGE_DISCRETE_SET_RANGES =
      new SetRanges(BuiltIn.RANGE_DISCRETE_SET_RANGES);

  /** @see BuiltIn#RANGE_DISCRETE_SET_TO_BAG */
  private static final Applicable RANGE_DISCRETE_SET_TO_BAG =
      new DiscreteSetEnumerate(
          BuiltIn.RANGE_DISCRETE_SET_TO_BAG, Discretes.dummy(), Pos.ZERO);

  /** @see BuiltIn#RANGE_DISCRETE_SET_TO_LIST */
  private static final Applicable RANGE_DISCRETE_SET_TO_LIST =
      new DiscreteSetEnumerate(
          BuiltIn.RANGE_DISCRETE_SET_TO_LIST, Discretes.dummy(), Pos.ZERO);

  /** @see BuiltIn#RANGE_FLATTEN */
  private static final Applicable RANGE_FLATTEN =
      new RangeFlatten(Discretes.dummy(), Pos.ZERO);

  // -----------------------------------------------------------------------
  // Range implementations

  /** Extracts the element type from a Range function's concrete type. */
  private static Type rangeElementType(Type type) {
    // type is one of:
    //   'a range -> 'a -> bool          (contains: paramType = 'a range)
    //   'a continuous_set -> ...        (set functions: paramType = DataType)
    //   'a discrete_set -> ...          (set functions: paramType = DataType)
    //   'a range list -> ...            (continuousSetOf, discreteSetOf)
    checkArgument(type instanceof FnType);
    Type paramType = ((FnType) type).paramType;
    if (paramType instanceof DataType) {
      // 'a range, 'a continuous_set, or 'a discrete_set
      return paramType.arg(0);
    }
    if (paramType instanceof ListType) {
      // 'a range list
      Type elemType = paramType.elementType();
      checkArgument(
          elemType instanceof DataType, "expected 'a range list, got %s", type);
      return elemType.arg(0);
    }
    return paramType;
  }

  /** Implementation of {@link BuiltIn#RANGE_CONTAINS}. */
  @SuppressWarnings({"rawtypes", "unchecked"})
  private static class RangeContains extends BaseApplicable implements Typed {
    private final Comparator cmp;

    RangeContains(Comparator cmp) {
      super(BuiltIn.RANGE_CONTAINS);
      this.cmp = requireNonNull(cmp);
    }

    @Override
    public Applicable withType(TypeSystem typeSystem, Type type, Pos pos) {
      Type elemType = rangeElementType(type);
      return new RangeContains(
          Comparators.comparatorFor(typeSystem, elemType, pos));
    }

    @Override
    public Object apply(Stack stack, Object argValue) {
      // First application: receives the range r
      final List range = (List) argValue;
      final BuiltIn.Constructor ctor =
          requireNonNull(BuiltIn.Constructor.forName((String) range.get(0)));
      return (Applicable1<Boolean, Object>)
          x -> {
            final List bounds;
            switch (ctor) {
              case RANGE_ALL:
                return true;
              case RANGE_POINT:
                return cmp.compare(x, range.get(1)) == 0;
              case RANGE_AT_LEAST:
                return cmp.compare(x, range.get(1)) >= 0;
              case RANGE_GREATER_THAN:
                return cmp.compare(x, range.get(1)) > 0;
              case RANGE_AT_MOST:
                return cmp.compare(x, range.get(1)) <= 0;
              case RANGE_LESS_THAN:
                return cmp.compare(x, range.get(1)) < 0;
              case RANGE_CLOSED:
                bounds = (List) range.get(1);
                return cmp.compare(x, bounds.get(0)) >= 0
                    && cmp.compare(x, bounds.get(1)) <= 0;
              case RANGE_OPEN:
                bounds = (List) range.get(1);
                return cmp.compare(x, bounds.get(0)) > 0
                    && cmp.compare(x, bounds.get(1)) < 0;
              case RANGE_CLOSED_OPEN:
                bounds = (List) range.get(1);
                return cmp.compare(x, bounds.get(0)) >= 0
                    && cmp.compare(x, bounds.get(1)) < 0;
              case RANGE_OPEN_CLOSED:
                bounds = (List) range.get(1);
                return cmp.compare(x, bounds.get(0)) > 0
                    && cmp.compare(x, bounds.get(1)) <= 0;
              default:
                throw new AssertionError("unknown range constructor: " + ctor);
            }
          };
    }
  }

  /**
   * Converts the representation of a {@code discrete_set} or {@code
   * continuous_set} to a list of range values.
   */
  public static List setToRangeList(Object o) {
    final PairList<Bound, Bound> pairList = (PairList<Bound, Bound>) o;
    return pairList.transformEager(Bound::toRange);
  }

  /** Implementation of {@link BuiltIn#RANGE_CONTINUOUS_SET_OF}. */
  @SuppressWarnings({"rawtypes"})
  private static class ContinuousSetOf extends BaseApplicable1<List, List>
      implements Typed {
    private final Comparator cmp;

    ContinuousSetOf(Comparator cmp) {
      super(BuiltIn.RANGE_CONTINUOUS_SET_OF);
      this.cmp = requireNonNull(cmp);
    }

    @Override
    public Applicable withType(TypeSystem typeSystem, Type type, Pos pos) {
      Type elemType = rangeElementType(type);
      return new ContinuousSetOf(
          Comparators.comparatorFor(typeSystem, elemType, pos));
    }

    @Override
    public List apply(List ranges) {
      return ImmutableList.of(
          BuiltIn.Constructor.CONTINUOUS_SET_CONTINUOUS_SET.constructor,
          Bound.fromRanges(ranges, cmp, null));
    }
  }

  /** Implementation of {@link BuiltIn#RANGE_DISCRETE_SET_OF}. */
  @SuppressWarnings({"rawtypes"})
  private static class DiscreteSetOf extends BaseApplicable1<List, List>
      implements Typed {
    private final Comparator cmp;
    private final Discrete<Object> discrete;

    DiscreteSetOf(Comparator cmp, Discrete<Object> discrete) {
      super(BuiltIn.RANGE_DISCRETE_SET_OF);
      this.cmp = requireNonNull(cmp);
      this.discrete = requireNonNull(discrete);
    }

    @Override
    public Applicable withType(TypeSystem typeSystem, Type type, Pos pos) {
      Type elemType = rangeElementType(type);
      return new DiscreteSetOf(
          Comparators.comparatorFor(typeSystem, elemType, pos),
          Discretes.discreteFor(typeSystem, elemType, pos));
    }

    @Override
    public List apply(List ranges) {
      return ImmutableList.of(
          BuiltIn.Constructor.DISCRETE_SET_DISCRETE_SET.constructor,
          Bound.fromRanges(ranges, cmp, discrete));
    }
  }

  /**
   * Shared implementation of {@link BuiltIn#RANGE_CONTINUOUS_SET_CONTAINS} and
   * {@link BuiltIn#RANGE_DISCRETE_SET_CONTAINS}.
   */
  @SuppressWarnings({"rawtypes"})
  private static class SetContains extends BaseApplicable implements Typed {
    private final Comparator cmp;

    SetContains(BuiltIn builtIn, Comparator cmp) {
      super(builtIn);
      this.cmp = requireNonNull(cmp);
    }

    @Override
    public Applicable withType(TypeSystem typeSystem, Type type, Pos pos) {
      final Type elemType = rangeElementType(type);
      final Comparator comparator =
          Comparators.comparatorFor(typeSystem, elemType, pos);
      return new SetContains(builtIn, comparator);
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public Object apply(Stack stack, Object argValue) {
      final List set = (List) argValue;
      final PairList<Bound, Bound> ranges = (PairList<Bound, Bound>) set.get(1);
      return (Applicable1<Boolean, Object>)
          x -> Bound.rangeContaining(ranges, x, cmp) >= 0;
    }
  }

  /**
   * Shared implementation of {@link BuiltIn#RANGE_CONTINUOUS_SET_RANGES} and
   * {@link BuiltIn#RANGE_DISCRETE_SET_RANGES}.
   */
  private static class SetRanges extends BaseApplicable1<List, List> {
    SetRanges(BuiltIn builtIn) {
      super(builtIn);
    }

    @Override
    public List apply(List set) {
      return setToRangeList(set.get(1));
    }
  }

  /**
   * Shared implementation of {@link BuiltIn#RANGE_CONTINUOUS_SET_COMPLEMENT}
   * and {@link BuiltIn#RANGE_DISCRETE_SET_COMPLEMENT}.
   */
  @SuppressWarnings({"rawtypes", "unchecked"})
  private static class SetComplement extends BaseApplicable1<List, List>
      implements Typed {
    private final @Nullable Discrete<Object> discrete;

    SetComplement(BuiltIn builtIn, @Nullable Discrete<Object> discrete) {
      super(builtIn);
      this.discrete = discrete;
    }

    @Override
    public Applicable withType(TypeSystem typeSystem, Type type, Pos pos) {
      if (discrete == null) {
        return this; // continuous: complement needs no type-specific logic
      }
      final Type elemType = rangeElementType(type);
      return new SetComplement(
          builtIn, Discretes.discreteFor(typeSystem, elemType, pos));
    }

    @Override
    public List apply(List set) {
      final PairList<Bound, Bound> ranges = (PairList<Bound, Bound>) set.get(1);
      final PairList<Bound, Bound> comp = Bound.complement(ranges, discrete);
      final BuiltIn.Constructor ctor =
          builtIn == BuiltIn.RANGE_CONTINUOUS_SET_COMPLEMENT
              ? BuiltIn.Constructor.CONTINUOUS_SET_CONTINUOUS_SET
              : BuiltIn.Constructor.DISCRETE_SET_DISCRETE_SET;
      return ImmutableList.of(ctor.constructor, comp);
    }
  }

  /**
   * Implementation of {@link BuiltIn#RANGE_DISCRETE_SET_TO_LIST} and {@link
   * BuiltIn#RANGE_DISCRETE_SET_TO_BAG}.
   */
  @SuppressWarnings({"rawtypes", "unchecked"})
  private static class DiscreteSetEnumerate extends BaseApplicable1<List, List>
      implements Typed {
    private final Discrete<Object> discrete;
    /** Position of the call, which a range too long to expand reports. */
    private final Pos pos;

    DiscreteSetEnumerate(BuiltIn builtIn, Discrete<Object> discrete, Pos pos) {
      super(builtIn);
      this.discrete = requireNonNull(discrete);
      this.pos = requireNonNull(pos);
    }

    @Override
    public Applicable withType(TypeSystem typeSystem, Type type, Pos pos) {
      Type elemType = rangeElementType(type);
      return new DiscreteSetEnumerate(
          builtIn, Discretes.discreteFor(typeSystem, elemType, pos), pos);
    }

    @Override
    public Object apply(Stack stack, Object argValue) {
      return apply(rangeMaxLength(stack), (List) argValue);
    }

    @Override
    public List apply(List set) {
      // No session, so the default applies.
      return apply(
          Prop.RANGE_MAX_LENGTH.bigIntegerValue(ImmutableMap.of()), set);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private List apply(BigInteger maxLength, List set) {
      final PairList<Bound, Bound> ranges = (PairList<Bound, Bound>) set.get(1);
      final ImmutableList.Builder<Object> result = ImmutableList.builder();
      ranges.forEach(
          (lo, hi) ->
              Bound.enumerate(discrete, lo, hi, maxLength, pos, result::add));
      return result.build();
    }
  }

  /** Implementation of {@link BuiltIn#RANGE_FLATTEN}. */
  @SuppressWarnings("rawtypes")
  private static class RangeFlatten
      extends BasePositionedApplicable1<List, List> implements Typed {
    /**
     * Discrete instance for the element type, or null if not discrete (e.g.
     * {@code real}). When null, only POINT items are finite at runtime.
     */
    private final @Nullable Discrete<Object> discrete;

    RangeFlatten(@Nullable Discrete<Object> discrete, Pos pos) {
      super(BuiltIn.RANGE_FLATTEN, pos);
      this.discrete = discrete;
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new RangeFlatten(discrete, pos);
    }

    @Override
    public Applicable withType(TypeSystem typeSystem, Type type, Pos pos) {
      Type elemType = rangeElementType(type);
      // A non-discrete element type (e.g. real) is not an error here. POINT
      // items are still finite; non-POINT items raise Size at runtime.
      final Discrete<Object> d =
          Discretes.discreteForOrNull(typeSystem, elemType, pos);
      return new RangeFlatten(d, pos);
    }

    @Override
    public Object apply(Stack stack, Object argValue) {
      return apply(rangeMaxLength(stack), (List) argValue);
    }

    @Override
    public List apply(List ranges) {
      // No session, so the default applies.
      return apply(
          Prop.RANGE_MAX_LENGTH.bigIntegerValue(ImmutableMap.of()), ranges);
    }

    private List apply(BigInteger maxLength, List ranges) {
      final ImmutableList.Builder<Object> result = ImmutableList.builder();
      for (Object r : ranges) {
        final List range = (List) r;
        if (discrete == null) {
          // Only POINT items are finite over a non-discrete element type.
          if (!BuiltIn.Constructor.RANGE_POINT.constructor.equals(
              range.get(0))) {
            throw new MorelRuntimeException(BuiltInExn.SIZE, pos);
          }
          result.add(requireNonNull(Bound.lowerBound(range).value));
        } else {
          final Bound lo = Bound.lowerBound(range);
          final Bound hi = Bound.upperBound(range);
          Bound.enumerate(discrete, lo, hi, maxLength, pos, result::add);
        }
      }
      return result.build();
    }
  }
}

// End RangeCodes.java
