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
import static net.hydromatic.morel.ast.CoreBuilder.core;
import static net.hydromatic.morel.eval.code.DateCodes.order;
import static net.hydromatic.morel.eval.code.DecimalCodes.decimalChecked;
import static net.hydromatic.morel.eval.code.GeneralCodes.notDefined;
import static net.hydromatic.morel.eval.code.ListCodes.empty;
import static net.hydromatic.morel.eval.code.ListCodes.length;
import static net.hydromatic.morel.eval.code.WordCodes.identity;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Ordering;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.compile.Macro;
import net.hydromatic.morel.eval.Applicable;
import net.hydromatic.morel.eval.Applicable1;
import net.hydromatic.morel.eval.Applicable2;
import net.hydromatic.morel.eval.BuiltInExn;
import net.hydromatic.morel.eval.Codes.Typed;
import net.hydromatic.morel.eval.Comparators;
import net.hydromatic.morel.eval.MorelRuntimeException;
import net.hydromatic.morel.eval.code.ListCodes.RelationalOnly;
import net.hydromatic.morel.type.DataType;
import net.hydromatic.morel.type.FnType;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.RangeExtent;
import net.hydromatic.morel.type.TupleType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.PairList;
import org.apache.calcite.runtime.FlatLists;
import org.jspecify.annotations.Nullable;

/**
 * Implementations of built-in functions and values in the {@code Relational}
 * structure.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public final class RelationalCodes {
  private RelationalCodes() {}

  /** Registers the implementations in this class. */
  public static void register(PairList<BuiltIn, Object> b) {
    // lint: sort until '#}' where '##b\.add\(BuiltIn' erase 'b\.'
    b.add(BuiltIn.RELATIONAL_COMPARE, RELATIONAL_COMPARE);
    b.add(BuiltIn.RELATIONAL_COUNT, RELATIONAL_COUNT);
    b.add(BuiltIn.RELATIONAL_EMPTY, RELATIONAL_EMPTY);
    b.add(BuiltIn.RELATIONAL_ITERATE, RELATIONAL_ITERATE);
    b.add(BuiltIn.RELATIONAL_MAX, RELATIONAL_MAX);
    b.add(BuiltIn.RELATIONAL_MAX_BY, RELATIONAL_MAX_BY);
    b.add(BuiltIn.RELATIONAL_MIN, RELATIONAL_MIN);
    b.add(BuiltIn.RELATIONAL_MIN_BY, RELATIONAL_MIN_BY);
    b.add(BuiltIn.RELATIONAL_NON_EMPTY, RELATIONAL_NON_EMPTY);
    b.add(BuiltIn.RELATIONAL_ONLY, RELATIONAL_ONLY);
    b.add(BuiltIn.RELATIONAL_SUM, RELATIONAL_SUM);
    b.add(BuiltIn.Z_EXTENT, Z_EXTENT);
    b.add(BuiltIn.Z_LIST, Z_LIST);
    b.add(BuiltIn.Z_SUM_DECIMAL, Z_SUM_DECIMAL);
    b.add(BuiltIn.Z_SUM_INT, Z_SUM_INT);
    b.add(BuiltIn.Z_SUM_REAL, Z_SUM_REAL);
  }

  // lint: sort until '#}' \
  //   where '##private static final [^ ]+ [^ ]+ =' \
  //   erase 'private static final [^ ]+ '

  /** @see BuiltIn#RELATIONAL_COMPARE */
  private static final Applicable RELATIONAL_COMPARE = Comparer.INITIAL;

  /** @see BuiltIn#RELATIONAL_COUNT */
  private static final Applicable1 RELATIONAL_COUNT =
      length(BuiltIn.RELATIONAL_COUNT);

  /** @see BuiltIn#RELATIONAL_EMPTY */
  private static final Applicable1 RELATIONAL_EMPTY =
      empty(BuiltIn.RELATIONAL_EMPTY);

  /** @see BuiltIn#RELATIONAL_ITERATE */
  private static final Applicable2 RELATIONAL_ITERATE =
      new BaseApplicable2<List, List, Applicable1<List, List>>(
          BuiltIn.RELATIONAL_ITERATE) {
        @SuppressWarnings({"rawtypes", "unchecked"})
        @Override
        public List apply(
            final List initialList, Applicable1<List, List> update) {
          List list = initialList;
          List newList = list;
          final Set seen = new LinkedHashSet(list);
          for (; ; ) {
            List nextList = update.apply(FlatLists.of(list, newList));
            // Subtract already-seen elements (semi-naive evaluation).
            // Without this, cyclic graphs would cause infinite iteration.
            final List genuinelyNew = new ArrayList();
            for (Object o : nextList) {
              if (seen.add(o)) {
                genuinelyNew.add(o);
              }
            }
            if (genuinelyNew.isEmpty()) {
              return list;
            }
            list =
                ImmutableList.builder()
                    .addAll(list)
                    .addAll(genuinelyNew)
                    .build();
            newList = genuinelyNew;
          }
        }
      };

  /** @see BuiltIn#RELATIONAL_MAX */
  private static final Applicable RELATIONAL_MAX =
      new RelationalMinMax(BuiltIn.RELATIONAL_MAX, Pos.ZERO, null);

  /** @see BuiltIn#RELATIONAL_MAX_BY */
  private static final Applicable RELATIONAL_MAX_BY =
      new RelationalMinMaxBy(BuiltIn.RELATIONAL_MAX_BY, Pos.ZERO, null);

  /** @see BuiltIn#RELATIONAL_MIN */
  private static final Applicable RELATIONAL_MIN =
      new RelationalMinMax(BuiltIn.RELATIONAL_MIN, Pos.ZERO, null);

  /** @see BuiltIn#RELATIONAL_MIN_BY */
  private static final Applicable RELATIONAL_MIN_BY =
      new RelationalMinMaxBy(BuiltIn.RELATIONAL_MIN_BY, Pos.ZERO, null);

  /**
   * Implements {@link #RELATIONAL_MAX} and {@link #RELATIONAL_MIN}.
   *
   * <p>Compares elements with {@link Comparators#comparatorFor} (as {@code
   * order} and {@code distinct} do), rather than Java's {@link
   * Ordering#natural}. Thus ordered types that Java would compare wrongly
   * ({@code word}, which is unsigned; {@code real}, whose {@code NaN} and
   * {@code ~0.0} differ) or not at all (tuples, records, lists, datatypes --
   * represented as Java lists that are not {@code Comparable}) are compared
   * correctly.
   *
   * <p>Raises {@link BuiltInExn#EMPTY} on an empty collection, like {@code
   * List.hd}.
   */
  private static class RelationalMinMax
      extends BasePositionedApplicable1<Object, List> implements Typed {
    /** Comparator for the element type; null until {@link #withType} is run. */
    private final @Nullable Comparator comparator;

    RelationalMinMax(
        BuiltIn builtIn, Pos pos, @Nullable Comparator comparator) {
      super(builtIn, pos);
      this.comparator = comparator;
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new RelationalMinMax(builtIn, pos, comparator);
    }

    @Override
    public Applicable withType(TypeSystem typeSystem, Type type, Pos pos) {
      // 'type' is 'elementType bag -> elementType' (perhaps wrapped in a
      // ForallType if the function is used as a value); its result type is the
      // element type.
      final FnType fnType = FnType.of(type);
      final Type elementType = fnType.resultType;
      final Comparator comparator =
          Comparators.comparatorFor(typeSystem, elementType, pos);
      return new RelationalMinMax(builtIn, pos, comparator);
    }

    @Override
    public Object apply(List list) {
      if (list.isEmpty()) {
        throw new MorelRuntimeException(BuiltInExn.EMPTY, pos);
      }
      final Ordering ordering =
          Ordering.from(requireNonNull(comparator, "comparator"));
      return requireNonNull(
          builtIn == BuiltIn.RELATIONAL_MAX
              ? ordering.max(list)
              : ordering.min(list));
    }
  }

  /**
   * Implements {@link #RELATIONAL_MAX_BY} and {@link #RELATIONAL_MIN_BY}.
   *
   * <p>Applies the key function to each element and compares the resulting keys
   * with {@link Comparators#comparatorFor}, just as {@link RelationalMinMax}
   * compares elements. Returns the first element whose key is extreme, so if
   * several elements are tied the result depends on the order in which the
   * collection is traversed; for a bag, that order is arbitrary.
   *
   * <p>Raises {@link BuiltInExn#EMPTY} on an empty collection, like {@code
   * List.hd}.
   */
  private static class RelationalMinMaxBy
      extends BasePositionedApplicable2<Object, Applicable1, List>
      implements Typed {
    /** Comparator for the key type; null until {@link #withType} is run. */
    private final @Nullable Comparator comparator;

    RelationalMinMaxBy(
        BuiltIn builtIn, Pos pos, @Nullable Comparator comparator) {
      super(builtIn, pos);
      this.comparator = comparator;
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new RelationalMinMaxBy(builtIn, pos, comparator);
    }

    @Override
    public Applicable withType(TypeSystem typeSystem, Type type, Pos pos) {
      // 'type' is '(elementType -> keyType) -> elementType bag -> elementType'
      // (perhaps wrapped in a ForallType if the function is used as a value);
      // the key type is the result type of its first argument.
      final FnType fnType = FnType.of(type);
      final FnType fnType1 = (FnType) fnType.paramType;
      final Type keyType = fnType1.resultType;
      final Comparator comparator =
          Comparators.comparatorFor(typeSystem, keyType, pos);
      return new RelationalMinMaxBy(builtIn, pos, comparator);
    }

    @Override
    public Object apply(Applicable1 keyFn, List list) {
      final Iterator iterator = list.iterator();
      if (!iterator.hasNext()) {
        throw new MorelRuntimeException(BuiltInExn.EMPTY, pos);
      }
      final Comparator comparator =
          requireNonNull(this.comparator, "comparator");
      // 'minBy' is 'maxBy' with the comparison reversed.
      final int sign = builtIn == BuiltIn.RELATIONAL_MAX_BY ? 1 : -1;
      Object best = iterator.next();
      Object bestKey = keyFn.apply(best);
      while (iterator.hasNext()) {
        final Object element = iterator.next();
        final Object key = keyFn.apply(element);
        if (sign * comparator.compare(key, bestKey) > 0) {
          best = element;
          bestKey = key;
        }
      }
      return best;
    }
  }

  /** @see BuiltIn#RELATIONAL_NON_EMPTY */
  private static final Applicable1 RELATIONAL_NON_EMPTY =
      new BaseApplicable1<Boolean, List>(BuiltIn.RELATIONAL_NON_EMPTY) {
        @Override
        public Boolean apply(List list) {
          return !list.isEmpty();
        }
      };

  /** @see BuiltIn#RELATIONAL_ONLY */
  private static final Applicable RELATIONAL_ONLY =
      new RelationalOnly(BuiltIn.RELATIONAL_ONLY, Pos.ZERO);

  /** @see BuiltIn#RELATIONAL_SUM */
  static final Macro RELATIONAL_SUM =
      (typeSystem, env, argType, pos) -> {
        if (argType.isCollection()) {
          // The element type is not necessarily primitive; in "group i compute
          // sum" it is a type variable, because nothing says what is summed.
          final Type resultType = argType.elementType();
          if (resultType instanceof PrimitiveType) {
            switch ((PrimitiveType) resultType) {
              case INT:
                return core.functionLiteral(typeSystem, BuiltIn.Z_SUM_INT);
              case REAL:
                return core.functionLiteral(typeSystem, BuiltIn.Z_SUM_REAL);
            }
          }
          if (resultType instanceof DataType
              && ((DataType) resultType).name.equals("decimal")) {
            return core.functionLiteral(typeSystem, BuiltIn.Z_SUM_DECIMAL);
          }
        }
        throw notDefined(BuiltIn.RELATIONAL_SUM, argType, pos);
      };

  /** @see BuiltIn#Z_EXTENT */
  private static final Applicable Z_EXTENT =
      new BaseApplicable1<List, RangeExtent>(BuiltIn.Z_EXTENT) {
        @Override
        public List apply(RangeExtent rangeExtent) {
          if (rangeExtent.iterable == null) {
            throw new AssertionError("infinite: " + rangeExtent);
          }
          return ImmutableList.copyOf(rangeExtent.iterable);
        }
      };

  /** @see BuiltIn#Z_LIST */
  private static final Applicable1 Z_LIST = identity(BuiltIn.Z_LIST);

  /** Implements {@link #RELATIONAL_SUM} for type {@code decimal list}. */
  private static final Applicable Z_SUM_DECIMAL = new ZSumDecimal(Pos.ZERO);

  /**
   * Implements {@link #Z_SUM_DECIMAL}. Adds exactly, then rounds; raises {@link
   * BuiltInExn#OVERFLOW} if the sum is too large.
   */
  private static class ZSumDecimal
      extends BasePositionedApplicable1<BigDecimal, List<BigDecimal>> {
    ZSumDecimal(Pos pos) {
      super(BuiltIn.Z_SUM_DECIMAL, pos);
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new ZSumDecimal(pos);
    }

    @Override
    protected String name() {
      return "Relational.sum$decimal";
    }

    @Override
    public BigDecimal apply(List<BigDecimal> decimals) {
      BigDecimal sum = BigDecimal.ZERO;
      for (BigDecimal d : decimals) {
        sum = sum.add(d);
      }
      return decimalChecked(sum, pos);
    }
  }

  /** Implements {@link #RELATIONAL_SUM} for type {@code int list}. */
  private static final Applicable Z_SUM_INT =
      new BaseApplicable1<Integer, List<? extends Number>>(BuiltIn.Z_SUM_INT) {
        @Override
        protected String name() {
          return "Relational.sum$int";
        }

        @Override
        public Integer apply(List<? extends Number> numbers) {
          int sum = 0;
          for (Number o : numbers) {
            sum += o.intValue();
          }
          return sum;
        }
      };

  /** Implements {@link #RELATIONAL_SUM} for type {@code real list}. */
  private static final Applicable Z_SUM_REAL =
      new BaseApplicable1<Float, List<? extends Number>>(BuiltIn.Z_SUM_REAL) {
        @Override
        protected String name() {
          return "Relational.sum$real";
        }

        @Override
        public Float apply(List<? extends Number> numbers) {
          float sum = 0;
          for (Number o : numbers) {
            sum += o.floatValue();
          }
          return sum;
        }
      };

  // -----------------------------------------------------------------------

  /** Implementation of {@link #RELATIONAL_COMPARE}. */
  @SuppressWarnings("rawtypes")
  static class Comparer extends BaseApplicable2<List, Object, Object>
      implements Applicable1<List, List>, Typed {
    static final Applicable INITIAL = new Comparer(Comparators::compare);

    private final Comparator comparator;

    Comparer(Comparator comparator) {
      super(BuiltIn.RELATIONAL_COMPARE);
      this.comparator = requireNonNull(comparator);
    }

    @Override
    public Applicable withType(TypeSystem typeSystem, Type type, Pos pos) {
      checkArgument(type instanceof FnType);
      Type argType = ((FnType) type).paramType;
      checkArgument(argType instanceof TupleType);
      List<Type> argTypes = ((TupleType) argType).argTypes;
      checkArgument(argTypes.size() == 2);
      Type argType0 = argTypes.get(0);
      Type argType1 = argTypes.get(1);
      checkArgument(argType0.equals(argType1));
      return new Comparer(Comparators.comparatorFor(typeSystem, argType0, pos));
    }

    @SuppressWarnings("unchecked")
    @Override // Applicable2
    public List apply(Object o1, Object o2) {
      return order(comparator.compare(o1, o2));
    }
  }
}

// End RelationalCodes.java
