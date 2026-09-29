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
package net.hydromatic.morel.eval.codes;

import static java.util.Objects.requireNonNull;
import static net.hydromatic.morel.eval.Codes.FLOAT_TO_STRING;
import static net.hydromatic.morel.eval.Codes.OPTION_NONE;
import static net.hydromatic.morel.eval.Codes.optionSome;
import static net.hydromatic.morel.eval.codes.DateCodes.order;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.eval.Applicable;
import net.hydromatic.morel.eval.Applicable1;
import net.hydromatic.morel.eval.Applicable2;
import net.hydromatic.morel.eval.BuiltInExn;
import net.hydromatic.morel.eval.Decimals;
import net.hydromatic.morel.eval.MorelRuntimeException;
import net.hydromatic.morel.util.PairList;

/**
 * Implementations of built-in functions and values in the {@code Decimal}
 * structure.
 */
public final class DecimalCodes {
  private DecimalCodes() {}

  /** Registers the implementations in this class. */
  public static void register(PairList<BuiltIn, Object> b) {
    // lint: sort until '#}' where '##b\.add\(BuiltIn' erase 'b\.'
    b.add(BuiltIn.DECIMAL_ABS, DECIMAL_ABS);
    b.add(BuiltIn.DECIMAL_CEIL, DECIMAL_CEIL);
    b.add(BuiltIn.DECIMAL_COMPARE, DECIMAL_COMPARE);
    b.add(BuiltIn.DECIMAL_DECIMAL, DECIMAL_DECIMAL);
    b.add(BuiltIn.DECIMAL_DIVIDE, DECIMAL_DIVIDE);
    b.add(BuiltIn.DECIMAL_FLOOR, DECIMAL_FLOOR);
    b.add(BuiltIn.DECIMAL_FMT, DECIMAL_FMT);
    b.add(BuiltIn.DECIMAL_FROM_INT, DECIMAL_FROM_INT);
    b.add(BuiltIn.DECIMAL_FROM_REAL, DECIMAL_FROM_REAL);
    b.add(BuiltIn.DECIMAL_FROM_STRING, DECIMAL_FROM_STRING);
    b.add(BuiltIn.DECIMAL_MAX, DECIMAL_MAX);
    b.add(BuiltIn.DECIMAL_MAX_FINITE, DECIMAL_MAX_FINITE);
    b.add(BuiltIn.DECIMAL_MIN, DECIMAL_MIN);
    b.add(BuiltIn.DECIMAL_MIN_POS, DECIMAL_MIN_POS);
    b.add(BuiltIn.DECIMAL_OP_GE, DECIMAL_OP_GE);
    b.add(BuiltIn.DECIMAL_OP_GT, DECIMAL_OP_GT);
    b.add(BuiltIn.DECIMAL_OP_LE, DECIMAL_OP_LE);
    b.add(BuiltIn.DECIMAL_OP_LT, DECIMAL_OP_LT);
    b.add(BuiltIn.DECIMAL_OP_MINUS, DECIMAL_OP_MINUS);
    b.add(BuiltIn.DECIMAL_OP_NEGATE, DECIMAL_OP_NEGATE);
    b.add(BuiltIn.DECIMAL_OP_PLUS, DECIMAL_OP_PLUS);
    b.add(BuiltIn.DECIMAL_OP_TIMES, DECIMAL_OP_TIMES);
    b.add(BuiltIn.DECIMAL_PRECISION, DECIMAL_PRECISION);
    b.add(BuiltIn.DECIMAL_RADIX, DECIMAL_RADIX);
    b.add(BuiltIn.DECIMAL_REAL_CEIL, DECIMAL_REAL_CEIL);
    b.add(BuiltIn.DECIMAL_REAL_FLOOR, DECIMAL_REAL_FLOOR);
    b.add(BuiltIn.DECIMAL_REAL_ROUND, DECIMAL_REAL_ROUND);
    b.add(BuiltIn.DECIMAL_REAL_TRUNC, DECIMAL_REAL_TRUNC);
    b.add(BuiltIn.DECIMAL_REM, DECIMAL_REM);
    b.add(BuiltIn.DECIMAL_ROUND, DECIMAL_ROUND);
    b.add(BuiltIn.DECIMAL_SIGN, DECIMAL_SIGN);
    b.add(BuiltIn.DECIMAL_TO_REAL, DECIMAL_TO_REAL);
    b.add(BuiltIn.DECIMAL_TO_STRING, DECIMAL_TO_STRING);
    b.add(BuiltIn.DECIMAL_TRUNC, DECIMAL_TRUNC);
  }

  // lint: sort until '#}' \
  //   where '##private static final [^ ]+ [^ ]+ =' \
  //   erase 'private static final [^ ]+ '

  /** @see BuiltIn#DECIMAL_ABS */
  private static final Applicable DECIMAL_ABS =
      new DecimalFn1(BuiltIn.DECIMAL_ABS, Pos.ZERO);

  /** @see BuiltIn#DECIMAL_CEIL */
  private static final Applicable DECIMAL_CEIL =
      new DecimalFn1(BuiltIn.DECIMAL_CEIL, Pos.ZERO);

  /** @see BuiltIn#DECIMAL_COMPARE */
  private static final Applicable DECIMAL_COMPARE =
      new DecimalFn2(BuiltIn.DECIMAL_COMPARE, Pos.ZERO);

  /** @see BuiltIn#DECIMAL_DECIMAL */
  private static final Applicable DECIMAL_DECIMAL =
      new DecimalFn1(BuiltIn.DECIMAL_DECIMAL, Pos.ZERO);

  /** @see BuiltIn#DECIMAL_DIVIDE */
  private static final Applicable DECIMAL_DIVIDE =
      new DecimalFn2(BuiltIn.DECIMAL_DIVIDE, Pos.ZERO);

  /** @see BuiltIn#DECIMAL_FLOOR */
  private static final Applicable DECIMAL_FLOOR =
      new DecimalFn1(BuiltIn.DECIMAL_FLOOR, Pos.ZERO);

  /** @see BuiltIn#DECIMAL_FMT */
  private static final Applicable2 DECIMAL_FMT = new DecimalFmt(Pos.ZERO);

  /** @see BuiltIn#DECIMAL_FROM_INT */
  private static final Applicable DECIMAL_FROM_INT =
      new DecimalFn1(BuiltIn.DECIMAL_FROM_INT, Pos.ZERO);

  /** @see BuiltIn#DECIMAL_FROM_REAL */
  private static final Applicable DECIMAL_FROM_REAL =
      new DecimalFn1(BuiltIn.DECIMAL_FROM_REAL, Pos.ZERO);

  /** @see BuiltIn#DECIMAL_FROM_STRING */
  private static final Applicable DECIMAL_FROM_STRING =
      new DecimalFn1(BuiltIn.DECIMAL_FROM_STRING, Pos.ZERO);

  /** @see BuiltIn#DECIMAL_MAX */
  private static final Applicable DECIMAL_MAX =
      new DecimalFn2(BuiltIn.DECIMAL_MAX, Pos.ZERO);

  /** @see BuiltIn#DECIMAL_MAX_FINITE */
  private static final BigDecimal DECIMAL_MAX_FINITE = Decimals.MAX_FINITE;

  /** @see BuiltIn#DECIMAL_MIN */
  private static final Applicable DECIMAL_MIN =
      new DecimalFn2(BuiltIn.DECIMAL_MIN, Pos.ZERO);

  /** @see BuiltIn#DECIMAL_MIN_POS */
  private static final BigDecimal DECIMAL_MIN_POS = Decimals.MIN_POS;

  /** @see BuiltIn#DECIMAL_OP_GE */
  private static final Applicable DECIMAL_OP_GE =
      new DecimalFn2(BuiltIn.DECIMAL_OP_GE, Pos.ZERO);

  /** @see BuiltIn#DECIMAL_OP_GT */
  private static final Applicable DECIMAL_OP_GT =
      new DecimalFn2(BuiltIn.DECIMAL_OP_GT, Pos.ZERO);

  /** @see BuiltIn#DECIMAL_OP_LE */
  private static final Applicable DECIMAL_OP_LE =
      new DecimalFn2(BuiltIn.DECIMAL_OP_LE, Pos.ZERO);

  /** @see BuiltIn#DECIMAL_OP_LT */
  private static final Applicable DECIMAL_OP_LT =
      new DecimalFn2(BuiltIn.DECIMAL_OP_LT, Pos.ZERO);

  /** @see BuiltIn#DECIMAL_OP_MINUS */
  private static final Applicable DECIMAL_OP_MINUS =
      new DecimalFn2(BuiltIn.DECIMAL_OP_MINUS, Pos.ZERO);

  /** @see BuiltIn#DECIMAL_OP_NEGATE */
  private static final Applicable DECIMAL_OP_NEGATE =
      new DecimalFn1(BuiltIn.DECIMAL_OP_NEGATE, Pos.ZERO);

  /** @see BuiltIn#DECIMAL_OP_PLUS */
  private static final Applicable DECIMAL_OP_PLUS =
      new DecimalFn2(BuiltIn.DECIMAL_OP_PLUS, Pos.ZERO);

  /** @see BuiltIn#DECIMAL_OP_TIMES */
  private static final Applicable DECIMAL_OP_TIMES =
      new DecimalFn2(BuiltIn.DECIMAL_OP_TIMES, Pos.ZERO);

  /** @see BuiltIn#DECIMAL_PRECISION */
  private static final int DECIMAL_PRECISION = Decimals.PRECISION;

  /** @see BuiltIn#DECIMAL_RADIX */
  private static final int DECIMAL_RADIX = 10;

  /** @see BuiltIn#DECIMAL_REAL_CEIL */
  private static final Applicable DECIMAL_REAL_CEIL =
      new DecimalFn1(BuiltIn.DECIMAL_REAL_CEIL, Pos.ZERO);

  /** @see BuiltIn#DECIMAL_REAL_FLOOR */
  private static final Applicable DECIMAL_REAL_FLOOR =
      new DecimalFn1(BuiltIn.DECIMAL_REAL_FLOOR, Pos.ZERO);

  /** @see BuiltIn#DECIMAL_REAL_ROUND */
  private static final Applicable DECIMAL_REAL_ROUND =
      new DecimalFn1(BuiltIn.DECIMAL_REAL_ROUND, Pos.ZERO);

  /** @see BuiltIn#DECIMAL_REAL_TRUNC */
  private static final Applicable DECIMAL_REAL_TRUNC =
      new DecimalFn1(BuiltIn.DECIMAL_REAL_TRUNC, Pos.ZERO);

  /** @see BuiltIn#DECIMAL_REM */
  private static final Applicable DECIMAL_REM =
      new DecimalFn2(BuiltIn.DECIMAL_REM, Pos.ZERO);

  /** @see BuiltIn#DECIMAL_ROUND */
  private static final Applicable DECIMAL_ROUND =
      new DecimalFn1(BuiltIn.DECIMAL_ROUND, Pos.ZERO);

  /** @see BuiltIn#DECIMAL_SIGN */
  private static final Applicable DECIMAL_SIGN =
      new DecimalFn1(BuiltIn.DECIMAL_SIGN, Pos.ZERO);

  /** @see BuiltIn#DECIMAL_TO_REAL */
  private static final Applicable DECIMAL_TO_REAL =
      new DecimalFn1(BuiltIn.DECIMAL_TO_REAL, Pos.ZERO);

  /** @see BuiltIn#DECIMAL_TO_STRING */
  private static final Applicable DECIMAL_TO_STRING =
      new DecimalFn1(BuiltIn.DECIMAL_TO_STRING, Pos.ZERO);

  /** @see BuiltIn#DECIMAL_TRUNC */
  private static final Applicable DECIMAL_TRUNC =
      new DecimalFn1(BuiltIn.DECIMAL_TRUNC, Pos.ZERO);

  /**
   * Implements the {@code Decimal} functions that take one argument.
   *
   * <p>Following the Standard ML Basis, a conversion to {@code int} raises
   * {@link BuiltInExn#OVERFLOW} if the result is out of range.
   */
  private static class DecimalFn1
      extends BasePositionedApplicable1<Object, Object> {
    DecimalFn1(BuiltIn builtIn, Pos pos) {
      super(builtIn, pos);
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new DecimalFn1(builtIn, pos);
    }

    @Override
    public Object apply(Object arg) {
      switch (builtIn) {
        case DECIMAL_DECIMAL:
          final BigDecimal d = Decimals.parseExact((String) arg);
          if (d == null) {
            throw new MorelRuntimeException(BuiltInExn.DOMAIN, pos);
          }
          return d;
        case DECIMAL_FROM_INT:
          return decimalChecked(BigDecimal.valueOf((Integer) arg), pos);
        case DECIMAL_FROM_REAL:
          final float f = (Float) arg;
          if (Float.isNaN(f)) {
            throw new MorelRuntimeException(BuiltInExn.DOMAIN, pos);
          }
          if (Float.isInfinite(f)) {
            throw new MorelRuntimeException(BuiltInExn.OVERFLOW, pos);
          }
          // The shortest decimal that converts back to the same float.
          return decimalChecked(new BigDecimal(FLOAT_TO_STRING.apply(f)), pos);
        case DECIMAL_FROM_STRING:
          final BigDecimal d2 = Decimals.parsePrefix((String) arg);
          return d2 == null ? OPTION_NONE : optionSome(decimalChecked(d2, pos));
        default:
          return apply2((BigDecimal) arg);
      }
    }

    private Object apply2(BigDecimal d) {
      switch (builtIn) {
        case DECIMAL_ABS:
          return d.abs();
        case DECIMAL_CEIL:
          return decimalToInt(d, RoundingMode.CEILING, pos);
        case DECIMAL_FLOOR:
          return decimalToInt(d, RoundingMode.FLOOR, pos);
        case DECIMAL_OP_NEGATE:
          return d.negate();
        case DECIMAL_REAL_CEIL:
          return decimalIntegral(d, RoundingMode.CEILING);
        case DECIMAL_REAL_FLOOR:
          return decimalIntegral(d, RoundingMode.FLOOR);
        case DECIMAL_REAL_ROUND:
          return decimalIntegral(d, RoundingMode.HALF_EVEN);
        case DECIMAL_REAL_TRUNC:
          return decimalIntegral(d, RoundingMode.DOWN);
        case DECIMAL_ROUND:
          return decimalToInt(d, RoundingMode.HALF_EVEN, pos);
        case DECIMAL_SIGN:
          return d.signum();
        case DECIMAL_TO_REAL:
          return d.floatValue();
        case DECIMAL_TO_STRING:
          return Decimals.toString(d);
        case DECIMAL_TRUNC:
          return decimalToInt(d, RoundingMode.DOWN, pos);
        default:
          throw new AssertionError(builtIn);
      }
    }
  }

  /**
   * Implements the {@code Decimal} functions that take two arguments.
   *
   * <p>Arithmetic raises {@link BuiltInExn#OVERFLOW} if the result is too
   * large, and division raises {@link BuiltInExn#DIV} if the divisor is zero.
   */
  private static class DecimalFn2
      extends BasePositionedApplicable2<Object, BigDecimal, BigDecimal> {
    DecimalFn2(BuiltIn builtIn, Pos pos) {
      super(builtIn, pos);
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new DecimalFn2(builtIn, pos);
    }

    @Override
    public Object apply(BigDecimal d1, BigDecimal d2) {
      switch (builtIn) {
        case DECIMAL_COMPARE:
          return order(d1.compareTo(d2));
        case DECIMAL_DIVIDE:
          if (d2.signum() == 0) {
            throw new MorelRuntimeException(BuiltInExn.DIV, pos);
          }
          return decimalChecked(d1.divide(d2, Decimals.MATH_CONTEXT), pos);
        case DECIMAL_MAX:
          return d1.compareTo(d2) >= 0 ? d1 : d2;
        case DECIMAL_MIN:
          return d1.compareTo(d2) <= 0 ? d1 : d2;
        case DECIMAL_OP_GE:
          return d1.compareTo(d2) >= 0;
        case DECIMAL_OP_GT:
          return d1.compareTo(d2) > 0;
        case DECIMAL_OP_LE:
          return d1.compareTo(d2) <= 0;
        case DECIMAL_OP_LT:
          return d1.compareTo(d2) < 0;
        case DECIMAL_OP_MINUS:
          return decimalChecked(d1.subtract(d2, Decimals.MATH_CONTEXT), pos);
        case DECIMAL_OP_PLUS:
          return decimalChecked(d1.add(d2, Decimals.MATH_CONTEXT), pos);
        case DECIMAL_OP_TIMES:
          return decimalChecked(d1.multiply(d2, Decimals.MATH_CONTEXT), pos);
        case DECIMAL_REM:
          if (d2.signum() == 0) {
            throw new MorelRuntimeException(BuiltInExn.DIV, pos);
          }
          return decimalChecked(d1.remainder(d2), pos);
        default:
          throw new AssertionError(builtIn);
      }
    }
  }

  /** Implements {@link #DECIMAL_FMT}. */
  private static class DecimalFmt
      extends BasePositionedApplicable2<String, List, BigDecimal> {
    DecimalFmt(Pos pos) {
      super(BuiltIn.DECIMAL_FMT, pos);
    }

    @Override
    public DecimalFmt withPos(Pos pos) {
      return new DecimalFmt(pos);
    }

    @Override
    public String apply(List spec, BigDecimal d) {
      return FmtSpec.parse(spec, pos).format(d);
    }

    /**
     * Validates {@code spec} on partial application, as {@code
     * RealCodes.RealFmt} does.
     */
    @Override
    public Applicable1<Applicable1<String, BigDecimal>, List> curry() {
      return new CurriedApplicable1<Applicable1<String, BigDecimal>, List>(
          builtIn, this) {
        @Override
        public Applicable1<String, BigDecimal> apply(List spec) {
          final FmtSpec info = FmtSpec.parse(spec, pos);
          return info::format;
        }
      };
    }
  }

  /**
   * Converts a value to a canonical {@code decimal}, raising {@link
   * BuiltInExn#OVERFLOW} if it is too large.
   */
  static BigDecimal decimalChecked(BigDecimal d, Pos pos) {
    final BigDecimal c = Decimals.canonical(d);
    if (c == null) {
      throw new MorelRuntimeException(BuiltInExn.OVERFLOW, pos);
    }
    return c;
  }

  /** Rounds a {@code decimal} to an integer-valued {@code decimal}. */
  private static BigDecimal decimalIntegral(BigDecimal d, RoundingMode mode) {
    if (d.scale() <= 0) {
      return d; // already an integer
    }
    return requireNonNull(Decimals.canonical(d.setScale(0, mode)));
  }

  /**
   * Rounds a {@code decimal} to an {@code int}, raising {@link
   * BuiltInExn#OVERFLOW} if it is out of range.
   */
  private static int decimalToInt(BigDecimal d, RoundingMode mode, Pos pos) {
    if (Decimals.adjustedExponent(d) <= 10) {
      try {
        return d.setScale(0, mode).intValueExact();
      } catch (ArithmeticException e) {
        // fall through
      }
    }
    throw new MorelRuntimeException(BuiltInExn.OVERFLOW, pos);
  }
}

// End DecimalCodes.java
