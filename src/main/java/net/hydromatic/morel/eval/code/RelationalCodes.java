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
import java.util.function.BiConsumer;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.compile.Macro;
import net.hydromatic.morel.eval.Applicable;
import net.hydromatic.morel.eval.Applicable1;
import net.hydromatic.morel.eval.Applicable2;
import net.hydromatic.morel.eval.BuiltInExn;
import net.hydromatic.morel.eval.Codes;
import net.hydromatic.morel.eval.Codes.Typed;
import net.hydromatic.morel.eval.Comparators;
import net.hydromatic.morel.eval.Decimals;
import net.hydromatic.morel.eval.MorelRuntimeException;
import net.hydromatic.morel.eval.code.ListCodes.RelationalOnly;
import net.hydromatic.morel.type.DataType;
import net.hydromatic.morel.type.FnType;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.RangeExtent;
import net.hydromatic.morel.type.TupleType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
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
  public static void register(BiConsumer<BuiltIn, Object> c) {
    // lint: sort until '#}' where '##c\.accept\(BuiltIn' erase 'c\.'
    c.accept(BuiltIn.RELATIONAL_AVG, RELATIONAL_AVG);
    c.accept(BuiltIn.RELATIONAL_AVG_OPT, RELATIONAL_AVG_OPT);
    c.accept(BuiltIn.RELATIONAL_AVG_SOME, RELATIONAL_AVG_SOME);
    c.accept(BuiltIn.RELATIONAL_COMPARE, RELATIONAL_COMPARE);
    c.accept(BuiltIn.RELATIONAL_COUNT, RELATIONAL_COUNT);
    c.accept(BuiltIn.RELATIONAL_COUNT_SOME, RELATIONAL_COUNT_SOME);
    c.accept(BuiltIn.RELATIONAL_EMPTY, RELATIONAL_EMPTY);
    c.accept(BuiltIn.RELATIONAL_ITERATE, RELATIONAL_ITERATE);
    c.accept(BuiltIn.RELATIONAL_MAX, RELATIONAL_MAX);
    c.accept(BuiltIn.RELATIONAL_MAX_BY, RELATIONAL_MAX_BY);
    c.accept(BuiltIn.RELATIONAL_MAX_OPT, RELATIONAL_MAX_OPT);
    c.accept(BuiltIn.RELATIONAL_MAX_SOME, RELATIONAL_MAX_SOME);
    c.accept(BuiltIn.RELATIONAL_MIN, RELATIONAL_MIN);
    c.accept(BuiltIn.RELATIONAL_MIN_BY, RELATIONAL_MIN_BY);
    c.accept(BuiltIn.RELATIONAL_MIN_OPT, RELATIONAL_MIN_OPT);
    c.accept(BuiltIn.RELATIONAL_MIN_SOME, RELATIONAL_MIN_SOME);
    c.accept(BuiltIn.RELATIONAL_NON_EMPTY, RELATIONAL_NON_EMPTY);
    c.accept(BuiltIn.RELATIONAL_ONLY, RELATIONAL_ONLY);
    c.accept(BuiltIn.RELATIONAL_SOMES, RELATIONAL_SOMES);
    c.accept(BuiltIn.RELATIONAL_STDDEV_POP, RELATIONAL_STDDEV_POP);
    c.accept(BuiltIn.RELATIONAL_STDDEV_POP_OPT, RELATIONAL_STDDEV_POP_OPT);
    c.accept(BuiltIn.RELATIONAL_STDDEV_POP_SOME, RELATIONAL_STDDEV_POP_SOME);
    c.accept(BuiltIn.RELATIONAL_STDDEV_SAMP, RELATIONAL_STDDEV_SAMP);
    c.accept(BuiltIn.RELATIONAL_STDDEV_SAMP_OPT, RELATIONAL_STDDEV_SAMP_OPT);
    c.accept(BuiltIn.RELATIONAL_STDDEV_SAMP_SOME, RELATIONAL_STDDEV_SAMP_SOME);
    c.accept(BuiltIn.RELATIONAL_SUM, RELATIONAL_SUM);
    c.accept(BuiltIn.RELATIONAL_SUM_OPT, RELATIONAL_SUM_OPT);
    c.accept(BuiltIn.RELATIONAL_SUM_SOME, RELATIONAL_SUM_SOME);
    c.accept(BuiltIn.RELATIONAL_VAR_POP, RELATIONAL_VAR_POP);
    c.accept(BuiltIn.RELATIONAL_VAR_POP_OPT, RELATIONAL_VAR_POP_OPT);
    c.accept(BuiltIn.RELATIONAL_VAR_POP_SOME, RELATIONAL_VAR_POP_SOME);
    c.accept(BuiltIn.RELATIONAL_VAR_SAMP, RELATIONAL_VAR_SAMP);
    c.accept(BuiltIn.RELATIONAL_VAR_SAMP_OPT, RELATIONAL_VAR_SAMP_OPT);
    c.accept(BuiltIn.RELATIONAL_VAR_SAMP_SOME, RELATIONAL_VAR_SAMP_SOME);
    c.accept(BuiltIn.Z_EXTENT, Z_EXTENT);
    c.accept(BuiltIn.Z_LIST, Z_LIST);
    c.accept(BuiltIn.Z_SUM_DECIMAL, Z_SUM_DECIMAL);
    c.accept(BuiltIn.Z_SUM_INT, Z_SUM_INT);
    c.accept(BuiltIn.Z_SUM_OPT_DECIMAL, Z_SUM_OPT_DECIMAL);
    c.accept(BuiltIn.Z_SUM_OPT_INT, Z_SUM_OPT_INT);
    c.accept(BuiltIn.Z_SUM_OPT_REAL, Z_SUM_OPT_REAL);
    c.accept(BuiltIn.Z_SUM_REAL, Z_SUM_REAL);
  }

  // lint: sort until '#}' \
  //   where '##private static final [^ ]+ [^ ]+ =' \
  //   erase 'private static final [^ ]+ '

  /** @see BuiltIn#RELATIONAL_AVG */
  private static final Applicable RELATIONAL_AVG =
      new RelationalAvg(BuiltIn.RELATIONAL_AVG, Pos.ZERO, null);

  /** @see BuiltIn#RELATIONAL_AVG_OPT */
  private static final Applicable RELATIONAL_AVG_OPT =
      new RelationalAvg(BuiltIn.RELATIONAL_AVG_OPT, Pos.ZERO, null);

  /** @see BuiltIn#RELATIONAL_AVG_SOME */
  private static final Applicable RELATIONAL_AVG_SOME =
      new RelationalAvg(BuiltIn.RELATIONAL_AVG_SOME, Pos.ZERO, null);

  /** @see BuiltIn#RELATIONAL_COMPARE */
  private static final Applicable RELATIONAL_COMPARE = Comparer.INITIAL;

  /** @see BuiltIn#RELATIONAL_COUNT */
  private static final Applicable1 RELATIONAL_COUNT =
      length(BuiltIn.RELATIONAL_COUNT);

  /** @see BuiltIn#RELATIONAL_COUNT_SOME */
  private static final Applicable1 RELATIONAL_COUNT_SOME =
      new BaseApplicable1<Integer, List>(BuiltIn.RELATIONAL_COUNT_SOME) {
        @Override
        public Integer apply(List options) {
          return someValues(options).size();
        }
      };

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

  /** @see BuiltIn#RELATIONAL_MAX_OPT */
  private static final Applicable RELATIONAL_MAX_OPT =
      new RelationalMinMax(BuiltIn.RELATIONAL_MAX_OPT, Pos.ZERO, null);

  /** @see BuiltIn#RELATIONAL_MAX_SOME */
  private static final Applicable RELATIONAL_MAX_SOME =
      new RelationalMinMax(BuiltIn.RELATIONAL_MAX_SOME, Pos.ZERO, null);

  /** @see BuiltIn#RELATIONAL_MIN */
  private static final Applicable RELATIONAL_MIN =
      new RelationalMinMax(BuiltIn.RELATIONAL_MIN, Pos.ZERO, null);

  /** @see BuiltIn#RELATIONAL_MIN_BY */
  private static final Applicable RELATIONAL_MIN_BY =
      new RelationalMinMaxBy(BuiltIn.RELATIONAL_MIN_BY, Pos.ZERO, null);

  /** @see BuiltIn#RELATIONAL_MIN_OPT */
  private static final Applicable RELATIONAL_MIN_OPT =
      new RelationalMinMax(BuiltIn.RELATIONAL_MIN_OPT, Pos.ZERO, null);

  /** @see BuiltIn#RELATIONAL_MIN_SOME */
  private static final Applicable RELATIONAL_MIN_SOME =
      new RelationalMinMax(BuiltIn.RELATIONAL_MIN_SOME, Pos.ZERO, null);

  /**
   * Implements {@link #RELATIONAL_MAX}, {@link #RELATIONAL_MIN}, {@link
   * #RELATIONAL_MAX_OPT}, {@link #RELATIONAL_MIN_OPT}, {@link
   * #RELATIONAL_MAX_SOME} and {@link #RELATIONAL_MIN_SOME}: the greatest or
   * least element, by the comparator for the element type; for {@code maxSome}
   * and {@code minSome}, the greatest or least value of the {@code SOME}
   * elements, ignoring {@code NONE}, or {@code NONE} if there are none.
   *
   * <p>On an empty collection, {@code max} and {@code min} raise {@link
   * BuiltInExn#EMPTY}, like {@code List.hd}; {@code maxOpt} and {@code minOpt}
   * return {@code NONE}, and otherwise {@code SOME} of the element.
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
      // The comparator is chosen from the element type of the argument, a
      // list or bag; for "maxSome" and "minSome", from the type of the
      // options' values.
      final Type elementType = FnType.of(type).paramType.elementType();
      final Type valueType = some() ? elementType.arg(0) : elementType;
      final Comparator comparator =
          Comparators.comparatorFor(typeSystem, valueType, pos);
      return new RelationalMinMax(builtIn, pos, comparator);
    }

    /** Whether this is {@code maxOpt} or {@code minOpt}. */
    private boolean opt() {
      return builtIn == BuiltIn.RELATIONAL_MAX_OPT
          || builtIn == BuiltIn.RELATIONAL_MIN_OPT;
    }

    /** Whether this is {@code maxSome} or {@code minSome}. */
    private boolean some() {
      return builtIn == BuiltIn.RELATIONAL_MAX_SOME
          || builtIn == BuiltIn.RELATIONAL_MIN_SOME;
    }

    @Override
    public Object apply(List list) {
      final List values = some() ? someValues(list) : list;
      if (values.isEmpty()) {
        if (opt() || some()) {
          return Codes.OPTION_NONE;
        }
        throw new MorelRuntimeException(BuiltInExn.EMPTY, pos);
      }
      final Ordering ordering =
          Ordering.from(requireNonNull(comparator, "comparator"));
      final Object result =
          requireNonNull(
              builtIn == BuiltIn.RELATIONAL_MAX
                      || builtIn == BuiltIn.RELATIONAL_MAX_OPT
                      || builtIn == BuiltIn.RELATIONAL_MAX_SOME
                  ? ordering.max(values)
                  : ordering.min(values));
      return opt() || some() ? Codes.optionSome(result) : result;
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

  /** @see BuiltIn#RELATIONAL_SOMES */
  private static final Applicable1 RELATIONAL_SOMES =
      new BaseApplicable1<List, List>(BuiltIn.RELATIONAL_SOMES) {
        @Override
        public List apply(List options) {
          return someValues(options);
        }
      };

  /** @see BuiltIn#RELATIONAL_STDDEV_POP */
  private static final Applicable RELATIONAL_STDDEV_POP =
      new RelationalDeviation(BuiltIn.RELATIONAL_STDDEV_POP, Pos.ZERO, null);

  /** @see BuiltIn#RELATIONAL_STDDEV_POP_OPT */
  private static final Applicable RELATIONAL_STDDEV_POP_OPT =
      new RelationalDeviation(
          BuiltIn.RELATIONAL_STDDEV_POP_OPT, Pos.ZERO, null);

  /** @see BuiltIn#RELATIONAL_STDDEV_POP_SOME */
  private static final Applicable RELATIONAL_STDDEV_POP_SOME =
      new RelationalDeviation(
          BuiltIn.RELATIONAL_STDDEV_POP_SOME, Pos.ZERO, null);

  /** @see BuiltIn#RELATIONAL_STDDEV_SAMP */
  private static final Applicable RELATIONAL_STDDEV_SAMP =
      new RelationalDeviation(BuiltIn.RELATIONAL_STDDEV_SAMP, Pos.ZERO, null);

  /** @see BuiltIn#RELATIONAL_STDDEV_SAMP_OPT */
  private static final Applicable RELATIONAL_STDDEV_SAMP_OPT =
      new RelationalDeviation(
          BuiltIn.RELATIONAL_STDDEV_SAMP_OPT, Pos.ZERO, null);

  /** @see BuiltIn#RELATIONAL_STDDEV_SAMP_SOME */
  private static final Applicable RELATIONAL_STDDEV_SAMP_SOME =
      new RelationalDeviation(
          BuiltIn.RELATIONAL_STDDEV_SAMP_SOME, Pos.ZERO, null);

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
          if (isDecimal(resultType)) {
            return core.functionLiteral(typeSystem, BuiltIn.Z_SUM_DECIMAL);
          }
        }
        throw notDefined(BuiltIn.RELATIONAL_SUM, argType, pos);
      };

  /** @see BuiltIn#RELATIONAL_SUM_SOME */
  private static final Applicable RELATIONAL_SUM_SOME =
      new RelationalSumSome(Pos.ZERO, null);

  /** @see BuiltIn#RELATIONAL_SUM_OPT */
  static final Macro RELATIONAL_SUM_OPT =
      (typeSystem, env, argType, pos) -> {
        if (argType.isCollection()) {
          final Type elementType = argType.elementType();
          if (elementType == PrimitiveType.INT) {
            return core.functionLiteral(typeSystem, BuiltIn.Z_SUM_OPT_INT);
          }
          if (elementType == PrimitiveType.REAL) {
            return core.functionLiteral(typeSystem, BuiltIn.Z_SUM_OPT_REAL);
          }
          if (isDecimal(elementType)) {
            return core.functionLiteral(typeSystem, BuiltIn.Z_SUM_OPT_DECIMAL);
          }
        }
        throw notDefined(BuiltIn.RELATIONAL_SUM_OPT, argType, pos);
      };

  /** @see BuiltIn#RELATIONAL_VAR_POP */
  private static final Applicable RELATIONAL_VAR_POP =
      new RelationalDeviation(BuiltIn.RELATIONAL_VAR_POP, Pos.ZERO, null);

  /** @see BuiltIn#RELATIONAL_VAR_POP_OPT */
  private static final Applicable RELATIONAL_VAR_POP_OPT =
      new RelationalDeviation(BuiltIn.RELATIONAL_VAR_POP_OPT, Pos.ZERO, null);

  /** @see BuiltIn#RELATIONAL_VAR_POP_SOME */
  private static final Applicable RELATIONAL_VAR_POP_SOME =
      new RelationalDeviation(BuiltIn.RELATIONAL_VAR_POP_SOME, Pos.ZERO, null);

  /** @see BuiltIn#RELATIONAL_VAR_SAMP */
  private static final Applicable RELATIONAL_VAR_SAMP =
      new RelationalDeviation(BuiltIn.RELATIONAL_VAR_SAMP, Pos.ZERO, null);

  /** @see BuiltIn#RELATIONAL_VAR_SAMP_OPT */
  private static final Applicable RELATIONAL_VAR_SAMP_OPT =
      new RelationalDeviation(BuiltIn.RELATIONAL_VAR_SAMP_OPT, Pos.ZERO, null);

  /** @see BuiltIn#RELATIONAL_VAR_SAMP_SOME */
  private static final Applicable RELATIONAL_VAR_SAMP_SOME =
      new RelationalDeviation(BuiltIn.RELATIONAL_VAR_SAMP_SOME, Pos.ZERO, null);

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

  /** Implements {@link #RELATIONAL_SUM_OPT} for type {@code decimal list}. */
  private static final Applicable Z_SUM_OPT_DECIMAL =
      new ZSumOpt(BuiltIn.Z_SUM_OPT_DECIMAL, Pos.ZERO);

  /** Implements {@link #RELATIONAL_SUM_OPT} for type {@code int list}. */
  private static final Applicable Z_SUM_OPT_INT =
      new ZSumOpt(BuiltIn.Z_SUM_OPT_INT, Pos.ZERO);

  /** Implements {@link #RELATIONAL_SUM_OPT} for type {@code real list}. */
  private static final Applicable Z_SUM_OPT_REAL =
      new ZSumOpt(BuiltIn.Z_SUM_OPT_REAL, Pos.ZERO);

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

  /** Returns whether a type is {@code decimal}. */
  private static boolean isDecimal(Type type) {
    return type instanceof DataType && ((DataType) type).name.equals("decimal");
  }

  /**
   * Returns the values of the {@code SOME} elements of a list of options,
   * ignoring the {@code NONE}s.
   */
  private static List someValues(List options) {
    final List values = new ArrayList(options.size());
    for (Object o : options) {
      final List option = (List) o;
      if (option.size() == 2) {
        values.add(option.get(1));
      }
    }
    return values;
  }

  /**
   * Implements {@link #RELATIONAL_SUM_SOME}: {@code SOME} of the sum of the
   * values of the {@code SOME} elements, ignoring {@code NONE}, as {@code
   * sumOpt} would sum them, or {@code NONE} if there are none.
   */
  private static class RelationalSumSome
      extends BasePositionedApplicable1<List, List> implements Typed {
    /** The internal "sumOpt" for the values' type; null until typed. */
    private final @Nullable Applicable1 sumOpt;

    RelationalSumSome(Pos pos, @Nullable Applicable1 sumOpt) {
      super(BuiltIn.RELATIONAL_SUM_SOME, pos);
      this.sumOpt = sumOpt;
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new RelationalSumSome(pos, sumOpt);
    }

    @Override
    public Applicable withType(TypeSystem typeSystem, Type type, Pos pos) {
      final Type paramType = FnType.of(type).paramType;
      if (paramType.isCollection()) {
        final Type valueType = paramType.elementType().arg(0);
        if (valueType == PrimitiveType.INT) {
          return new RelationalSumSome(pos, (Applicable1) Z_SUM_OPT_INT);
        }
        if (valueType == PrimitiveType.REAL) {
          return new RelationalSumSome(pos, (Applicable1) Z_SUM_OPT_REAL);
        }
        if (isDecimal(valueType)) {
          return new RelationalSumSome(
              pos, new ZSumOpt(BuiltIn.Z_SUM_OPT_DECIMAL, pos));
        }
      }
      throw notDefined(BuiltIn.RELATIONAL_SUM_SOME, paramType, pos);
    }

    @Override
    public List apply(List options) {
      return (List) requireNonNull(sumOpt, "sumOpt").apply(someValues(options));
    }
  }

  /**
   * Implements {@link #Z_SUM_OPT_DECIMAL}, {@link #Z_SUM_OPT_INT} and {@link
   * #Z_SUM_OPT_REAL}: {@code SOME} of the sum, as the corresponding sum
   * computes it, or {@code NONE} if the collection is empty.
   */
  private static class ZSumOpt extends BasePositionedApplicable1<List, List> {
    ZSumOpt(BuiltIn builtIn, Pos pos) {
      super(builtIn, pos);
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new ZSumOpt(builtIn, pos);
    }

    @Override
    protected String name() {
      return "Relational.sumOpt$"
          + builtIn.mlName.substring("sumOpt:".length());
    }

    @Override
    public List apply(List values) {
      if (values.isEmpty()) {
        return Codes.OPTION_NONE;
      }
      final Object sum;
      switch (builtIn) {
        case Z_SUM_OPT_DECIMAL:
          sum = new ZSumDecimal(pos).apply((List<BigDecimal>) values);
          break;
        case Z_SUM_OPT_INT:
          sum = ((Applicable1) Z_SUM_INT).apply(values);
          break;
        default:
          sum = ((Applicable1) Z_SUM_REAL).apply(values);
          break;
      }
      return Codes.optionSome(sum);
    }
  }

  /**
   * Implements {@link #RELATIONAL_AVG}, {@link #RELATIONAL_AVG_OPT} and {@link
   * #RELATIONAL_AVG_SOME}.
   *
   * <p>Its type is overloaded, and {@link #withType} chooses the arithmetic:
   * the mean of {@code int} values is a {@code real}, computed in {@code
   * double}; of {@code real} values, a {@code real}; of {@code decimal} values,
   * a {@code decimal}, the exact sum divided by the count and rounded to 34
   * digits. On an empty collection, {@code avg} raises {@link BuiltInExn#EMPTY}
   * and {@code avgOpt} returns {@code NONE}; {@code avgOpt} otherwise returns
   * {@code SOME} of the mean. {@code avgSome} averages the values of the {@code
   * SOME} elements, ignoring {@code NONE}, as {@code avgOpt} would.
   */
  private static class RelationalAvg
      extends BasePositionedApplicable1<Object, List> implements Typed {
    /** Type of the values averaged; null until {@link #withType} is run. */
    private final @Nullable Type valueType;

    RelationalAvg(BuiltIn builtIn, Pos pos, @Nullable Type valueType) {
      super(builtIn, pos);
      this.valueType = valueType;
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new RelationalAvg(builtIn, pos, valueType);
    }

    @Override
    public Applicable withType(TypeSystem typeSystem, Type type, Pos pos) {
      final Type paramType = FnType.of(type).paramType;
      if (paramType.isCollection()) {
        final Type valueType =
            builtIn == BuiltIn.RELATIONAL_AVG_SOME
                ? paramType.elementType().arg(0)
                : paramType.elementType();
        if (valueType == PrimitiveType.INT
            || valueType == PrimitiveType.REAL
            || isDecimal(valueType)) {
          return new RelationalAvg(builtIn, pos, valueType);
        }
      }
      throw notDefined(builtIn, paramType, pos);
    }

    @Override
    public Object apply(List list) {
      final boolean some = builtIn == BuiltIn.RELATIONAL_AVG_SOME;
      final boolean opt = some || builtIn == BuiltIn.RELATIONAL_AVG_OPT;
      final List values = some ? someValues(list) : list;
      if (values.isEmpty()) {
        if (opt) {
          return Codes.OPTION_NONE;
        }
        throw new MorelRuntimeException(BuiltInExn.EMPTY, pos);
      }
      final Object avg = mean(values);
      return opt ? Codes.optionSome(avg) : avg;
    }

    private Object mean(List values) {
      if (valueType == PrimitiveType.INT) {
        long sum = 0;
        for (Object o : values) {
          sum += (Integer) o;
        }
        return (float) ((double) sum / values.size());
      }
      if (valueType == PrimitiveType.REAL) {
        double sum = 0;
        for (Object o : values) {
          sum += (Float) o;
        }
        return (float) (sum / values.size());
      }
      if (valueType != null && isDecimal(valueType)) {
        BigDecimal sum = BigDecimal.ZERO;
        for (Object o : values) {
          sum = sum.add((BigDecimal) o);
        }
        return decimalChecked(
            sum.divide(
                BigDecimal.valueOf(values.size()), Decimals.MATH_CONTEXT),
            pos);
      }
      throw new IllegalStateException("avg has no type: " + valueType);
    }
  }

  /**
   * Implements {@link #RELATIONAL_STDDEV_POP}, {@link #RELATIONAL_STDDEV_SAMP},
   * {@link #RELATIONAL_VAR_POP}, {@link #RELATIONAL_VAR_SAMP}, and their {@code
   * Opt} and {@code Some} forms.
   *
   * <p>Its type is overloaded; the elements may be {@code int}, {@code real} or
   * {@code decimal}, and the result is a {@code real}, computed in {@code
   * double} in two passes (the mean, then the squared deviations from it). The
   * population forms need one element, and the sample forms two; with fewer,
   * the plain form raises {@link BuiltInExn#EMPTY}, and the {@code Opt} and
   * {@code Some} forms return {@code NONE}. The {@code Some} forms ignore
   * {@code NONE} elements.
   */
  private static class RelationalDeviation
      extends BasePositionedApplicable1<Object, List> implements Typed {
    /** Type of the values; null until {@link #withType} is run. */
    private final @Nullable Type valueType;

    RelationalDeviation(BuiltIn builtIn, Pos pos, @Nullable Type valueType) {
      super(builtIn, pos);
      this.valueType = valueType;
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new RelationalDeviation(builtIn, pos, valueType);
    }

    @Override
    public Applicable withType(TypeSystem typeSystem, Type type, Pos pos) {
      final Type paramType = FnType.of(type).paramType;
      if (paramType.isCollection()) {
        final Type valueType =
            isSome() ? paramType.elementType().arg(0) : paramType.elementType();
        if (valueType == PrimitiveType.INT
            || valueType == PrimitiveType.REAL
            || isDecimal(valueType)) {
          return new RelationalDeviation(builtIn, pos, valueType);
        }
      }
      throw notDefined(builtIn, paramType, pos);
    }

    private boolean isSome() {
      return builtIn.mlName.endsWith("Some");
    }

    private boolean isOpt() {
      return isSome() || builtIn.mlName.endsWith("Opt");
    }

    /** Whether this is a sample (rather than a population) statistic. */
    private boolean isSample() {
      return builtIn.mlName.contains("Samp");
    }

    /** Whether this is a standard deviation (rather than a variance). */
    private boolean isStddev() {
      return builtIn.mlName.startsWith("stddev");
    }

    @Override
    public Object apply(List list) {
      final List values = isSome() ? someValues(list) : list;
      final int n = values.size();
      if (n < (isSample() ? 2 : 1)) {
        if (isOpt()) {
          return Codes.OPTION_NONE;
        }
        throw new MorelRuntimeException(BuiltInExn.EMPTY, pos);
      }
      double sum = 0;
      for (Object o : values) {
        sum += toDouble(o);
      }
      final double mean = sum / n;
      double sumSquares = 0;
      for (Object o : values) {
        final double d = toDouble(o) - mean;
        sumSquares += d * d;
      }
      final double variance = sumSquares / (isSample() ? n - 1 : n);
      final float result =
          (float) (isStddev() ? Math.sqrt(variance) : variance);
      return isOpt() ? Codes.optionSome(result) : result;
    }

    private static double toDouble(Object o) {
      return ((Number) o).doubleValue();
    }
  }

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
