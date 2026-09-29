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

import static net.hydromatic.morel.eval.Codes.OPTION_NONE;
import static net.hydromatic.morel.eval.Codes.floatToString;
import static net.hydromatic.morel.eval.Codes.isNegative;
import static net.hydromatic.morel.eval.Codes.optionSome;
import static net.hydromatic.morel.eval.codes.DateCodes.digits;
import static net.hydromatic.morel.eval.codes.GeneralCodes.ORDER_EQUAL;
import static net.hydromatic.morel.eval.codes.GeneralCodes.ORDER_GREATER;
import static net.hydromatic.morel.eval.codes.GeneralCodes.ORDER_LESS;
import static net.hydromatic.morel.eval.codes.GeneralCodes.consume;
import static net.hydromatic.morel.eval.codes.StringCodes.scanString;

import com.google.common.collect.ImmutableList;
import java.util.List;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.eval.Applicable;
import net.hydromatic.morel.eval.Applicable1;
import net.hydromatic.morel.eval.Applicable2;
import net.hydromatic.morel.eval.BuiltInExn;
import net.hydromatic.morel.eval.MorelRuntimeException;
import net.hydromatic.morel.eval.codes.StringCodes.CharSource;
import net.hydromatic.morel.util.PairList;

/**
 * Implementations of built-in functions and values in the Real, Math and
 * IEEEReal structures.
 */
public final class RealCodes {
  private RealCodes() {}

  /** Registers the implementations in this class. */
  public static void register(PairList<BuiltIn, Object> b) {
    // lint: sort until '#}' where '##b\.add\(BuiltIn' erase 'b\.'
    b.add(BuiltIn.MATH_ACOS, MATH_ACOS);
    b.add(BuiltIn.MATH_ASIN, MATH_ASIN);
    b.add(BuiltIn.MATH_ATAN, MATH_ATAN);
    b.add(BuiltIn.MATH_ATAN2, MATH_ATAN2);
    b.add(BuiltIn.MATH_COS, MATH_COS);
    b.add(BuiltIn.MATH_COSH, MATH_COSH);
    b.add(BuiltIn.MATH_E, MATH_E);
    b.add(BuiltIn.MATH_EXP, MATH_EXP);
    b.add(BuiltIn.MATH_LN, MATH_LN);
    b.add(BuiltIn.MATH_LOG10, MATH_LOG10);
    b.add(BuiltIn.MATH_PI, MATH_PI);
    b.add(BuiltIn.MATH_POW, MATH_POW);
    b.add(BuiltIn.MATH_SIN, MATH_SIN);
    b.add(BuiltIn.MATH_SINH, MATH_SINH);
    b.add(BuiltIn.MATH_SQRT, MATH_SQRT);
    b.add(BuiltIn.MATH_TAN, MATH_TAN);
    b.add(BuiltIn.MATH_TANH, MATH_TANH);
    b.add(BuiltIn.REAL_ABS, REAL_ABS);
    b.add(BuiltIn.REAL_CEIL, REAL_CEIL);
    b.add(BuiltIn.REAL_CHECK_FLOAT, REAL_CHECK_FLOAT);
    b.add(BuiltIn.REAL_COMPARE, REAL_COMPARE);
    b.add(BuiltIn.REAL_COPY_SIGN, REAL_COPY_SIGN);
    b.add(BuiltIn.REAL_DIVIDE, REAL_DIVIDE);
    b.add(BuiltIn.REAL_FLOOR, REAL_FLOOR);
    b.add(BuiltIn.REAL_FMT, REAL_FMT);
    b.add(BuiltIn.REAL_FROM_INT, REAL_FROM_INT);
    b.add(BuiltIn.REAL_FROM_MAN_EXP, REAL_FROM_MAN_EXP);
    b.add(BuiltIn.REAL_FROM_STRING, REAL_FROM_STRING);
    b.add(BuiltIn.REAL_IS_FINITE, REAL_IS_FINITE);
    b.add(BuiltIn.REAL_IS_NAN, REAL_IS_NAN);
    b.add(BuiltIn.REAL_IS_NORMAL, REAL_IS_NORMAL);
    b.add(BuiltIn.REAL_MAX, REAL_MAX);
    b.add(BuiltIn.REAL_MAX_FINITE, REAL_MAX_FINITE);
    b.add(BuiltIn.REAL_MIN, REAL_MIN);
    b.add(BuiltIn.REAL_MIN_NORMAL_POS, REAL_MIN_NORMAL_POS);
    b.add(BuiltIn.REAL_MIN_POS, REAL_MIN_POS);
    b.add(BuiltIn.REAL_NEG_INF, REAL_NEG_INF);
    b.add(BuiltIn.REAL_OP_EQ, REAL_OP_EQ);
    b.add(BuiltIn.REAL_OP_GE, REAL_OP_GE);
    b.add(BuiltIn.REAL_OP_GT, REAL_OP_GT);
    b.add(BuiltIn.REAL_OP_LE, REAL_OP_LE);
    b.add(BuiltIn.REAL_OP_LT, REAL_OP_LT);
    b.add(BuiltIn.REAL_OP_MINUS, REAL_OP_MINUS);
    b.add(BuiltIn.REAL_OP_NE, REAL_OP_NE);
    b.add(BuiltIn.REAL_OP_NEGATE, REAL_OP_NEGATE);
    b.add(BuiltIn.REAL_OP_PLUS, REAL_OP_PLUS);
    b.add(BuiltIn.REAL_OP_TIMES, REAL_OP_TIMES);
    b.add(BuiltIn.REAL_POS_INF, REAL_POS_INF);
    b.add(BuiltIn.REAL_PRECISION, REAL_PRECISION);
    b.add(BuiltIn.REAL_RADIX, REAL_RADIX);
    b.add(BuiltIn.REAL_REAL_CEIL, REAL_REAL_CEIL);
    b.add(BuiltIn.REAL_REAL_FLOOR, REAL_REAL_FLOOR);
    b.add(BuiltIn.REAL_REAL_MOD, REAL_REAL_MOD);
    b.add(BuiltIn.REAL_REAL_ROUND, REAL_REAL_ROUND);
    b.add(BuiltIn.REAL_REAL_TRUNC, REAL_REAL_TRUNC);
    b.add(BuiltIn.REAL_REM, REAL_REM);
    b.add(BuiltIn.REAL_ROUND, REAL_ROUND);
    b.add(BuiltIn.REAL_SAME_SIGN, REAL_SAME_SIGN);
    b.add(BuiltIn.REAL_SCAN, REAL_SCAN);
    b.add(BuiltIn.REAL_SIGN, REAL_SIGN);
    b.add(BuiltIn.REAL_SIGN_BIT, REAL_SIGN_BIT);
    b.add(BuiltIn.REAL_SPLIT, REAL_SPLIT);
    b.add(BuiltIn.REAL_TO_MAN_EXP, REAL_TO_MAN_EXP);
    b.add(BuiltIn.REAL_TO_STRING, REAL_TO_STRING);
    b.add(BuiltIn.REAL_TRUNC, REAL_TRUNC);
    b.add(BuiltIn.REAL_UNORDERED, REAL_UNORDERED);
  }

  // lint: sort until '#}' \
  //   where '##private static final [^ ]+ [^ ]+ =' \
  //   erase 'private static final [^ ]+ '

  /** @see BuiltIn#MATH_ACOS */
  private static final Applicable MATH_ACOS =
      new BaseApplicable1<Float, Float>(BuiltIn.MATH_ACOS) {
        @Override
        public Float apply(Float f) {
          return (float) Math.acos(f);
        }
      };

  /** @see BuiltIn#MATH_ASIN */
  private static final Applicable MATH_ASIN =
      new BaseApplicable1<Float, Float>(BuiltIn.MATH_ASIN) {
        @Override
        public Float apply(Float f) {
          return (float) Math.asin(f);
        }
      };

  /** @see BuiltIn#MATH_ATAN */
  private static final Applicable MATH_ATAN =
      new BaseApplicable1<Float, Float>(BuiltIn.MATH_ATAN) {
        @Override
        public Float apply(Float f) {
          return (float) Math.atan(f);
        }
      };

  /** @see BuiltIn#MATH_ATAN2 */
  private static final Applicable2 MATH_ATAN2 =
      new BaseApplicable2<Float, Float, Float>(BuiltIn.MATH_ATAN2) {
        @Override
        public Float apply(Float arg0, Float arg1) {
          return (float) Math.atan2(arg0, arg1);
        }
      };

  /** @see BuiltIn#MATH_COS */
  private static final Applicable MATH_COS =
      new BaseApplicable1<Float, Float>(BuiltIn.MATH_COS) {
        @Override
        public Float apply(Float f) {
          return (float) Math.cos(f);
        }
      };

  /** @see BuiltIn#MATH_COSH */
  private static final Applicable MATH_COSH =
      new BaseApplicable1<Float, Float>(BuiltIn.MATH_COSH) {
        @Override
        public Float apply(Float f) {
          return (float) Math.cosh(f);
        }
      };

  /** @see BuiltIn#MATH_E */
  private static final float MATH_E = (float) Math.E;

  /** @see BuiltIn#MATH_EXP */
  private static final Applicable MATH_EXP =
      new BaseApplicable1<Float, Float>(BuiltIn.MATH_EXP) {
        @Override
        public Float apply(Float f) {
          return (float) Math.exp(f);
        }
      };

  /** @see BuiltIn#MATH_LN */
  private static final Applicable MATH_LN =
      new BaseApplicable1<Float, Float>(BuiltIn.MATH_LN) {
        @Override
        public Float apply(Float f) {
          return (float) Math.log(f);
        }
      };

  /** @see BuiltIn#MATH_LOG10 */
  private static final Applicable MATH_LOG10 =
      new BaseApplicable1<Float, Float>(BuiltIn.MATH_LOG10) {
        @Override
        public Float apply(Float f) {
          return (float) Math.log10(f);
        }
      };

  /** @see BuiltIn#MATH_PI */
  private static final float MATH_PI = (float) Math.PI;

  /** @see BuiltIn#MATH_POW */
  private static final Applicable2 MATH_POW =
      new BaseApplicable2<Float, Float, Float>(BuiltIn.MATH_POW) {
        @Override
        public Float apply(Float arg0, Float arg1) {
          return (float) Math.pow(arg0, arg1);
        }
      };

  /** @see BuiltIn#MATH_SIN */
  private static final Applicable MATH_SIN =
      new BaseApplicable1<Float, Float>(BuiltIn.MATH_SIN) {
        @Override
        public Float apply(Float f) {
          return (float) Math.sin(f);
        }
      };

  /** @see BuiltIn#MATH_SINH */
  private static final Applicable MATH_SINH =
      new BaseApplicable1<Float, Float>(BuiltIn.MATH_SINH) {
        @Override
        public Float apply(Float f) {
          return (float) Math.sinh(f);
        }
      };

  /** @see BuiltIn#MATH_SQRT */
  private static final Applicable MATH_SQRT =
      new BaseApplicable1<Float, Float>(BuiltIn.MATH_SQRT) {
        @Override
        public Float apply(Float f) {
          return (float) Math.sqrt(f);
        }
      };

  /** @see BuiltIn#MATH_TAN */
  private static final Applicable MATH_TAN =
      new BaseApplicable1<Float, Float>(BuiltIn.MATH_TAN) {
        @Override
        public Float apply(Float f) {
          return (float) Math.tan(f);
        }
      };

  /** @see BuiltIn#MATH_TANH */
  private static final Applicable MATH_TANH =
      new BaseApplicable1<Float, Float>(BuiltIn.MATH_TANH) {
        @Override
        public Float apply(Float f) {
          return (float) Math.tanh(f);
        }
      };

  /** @see BuiltIn#REAL_ABS */
  private static final Applicable REAL_ABS =
      new BaseApplicable1<Float, Float>(BuiltIn.REAL_ABS) {
        @Override
        public Float apply(Float f) {
          if (f.isNaN()) {
            return isNegative(f) ? -f : f;
          }
          return Math.abs(f);
        }
      };

  /** @see BuiltIn#REAL_CEIL */
  private static final Applicable REAL_CEIL =
      new RealToInt(BuiltIn.REAL_CEIL, Pos.ZERO);

  /**
   * Implements {@link #REAL_FLOOR}, {@link #REAL_CEIL}, {@link #REAL_ROUND} and
   * {@link #REAL_TRUNC}, which convert a {@code real} to the {@code int} that
   * is respectively the largest integer not greater than the argument, the
   * smallest integer not less than it, the nearest integer (ties to even), and
   * the argument rounded toward zero.
   *
   * <p>Following the Standard ML Basis, they raise {@link BuiltInExn#DOMAIN} if
   * the argument is {@code NaN}, and {@link BuiltInExn#OVERFLOW} if the result
   * is out of {@code int} range (in particular, on an infinity).
   */
  private static class RealToInt
      extends BasePositionedApplicable1<Integer, Float> {
    RealToInt(BuiltIn builtIn, Pos pos) {
      super(builtIn, pos);
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new RealToInt(builtIn, pos);
    }

    @Override
    public Integer apply(Float f) {
      if (Float.isNaN(f)) {
        throw new MorelRuntimeException(BuiltInExn.DOMAIN, pos);
      }
      final double d;
      switch (builtIn) {
        case REAL_FLOOR:
          d = Math.floor(f);
          break;
        case REAL_CEIL:
          d = Math.ceil(f);
          break;
        case REAL_ROUND:
          d = Math.rint(f);
          break;
        case REAL_TRUNC:
        default:
          d = f < 0 ? Math.ceil(f) : Math.floor(f);
          break;
      }
      if (d < Integer.MIN_VALUE || d > Integer.MAX_VALUE) {
        throw new MorelRuntimeException(BuiltInExn.OVERFLOW, pos);
      }
      return (int) d;
    }
  }

  /** @see BuiltIn#REAL_CHECK_FLOAT */
  private static final Applicable REAL_CHECK_FLOAT =
      new RealCheckFloat(Pos.ZERO);

  /** Implements {@link #REAL_CHECK_FLOAT}. */
  private static class RealCheckFloat
      extends BasePositionedApplicable1<Float, Float> {
    RealCheckFloat(Pos pos) {
      super(BuiltIn.REAL_CHECK_FLOAT, pos);
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new RealCheckFloat(pos);
    }

    @Override
    public Float apply(Float f) {
      if (Float.isFinite(f)) {
        return f;
      }
      if (Float.isNaN(f)) {
        throw new MorelRuntimeException(BuiltInExn.DIV, pos);
      } else {
        throw new MorelRuntimeException(BuiltInExn.OVERFLOW, pos);
      }
    }
  }

  /** @see BuiltIn#REAL_COMPARE */
  private static final Applicable REAL_COMPARE = new RealCompare(Pos.ZERO);

  /** Implements {@link #REAL_COMPARE}. */
  private static class RealCompare
      extends BasePositionedApplicable2<List, Float, Float> {
    RealCompare(Pos pos) {
      super(BuiltIn.REAL_COMPARE, pos);
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new RealCompare(pos);
    }

    @Override
    public List apply(Float f0, Float f1) {
      if (Float.isNaN(f0) || Float.isNaN(f1)) {
        throw new MorelRuntimeException(BuiltInExn.UNORDERED, pos);
      }
      if (f0 < f1) {
        return ORDER_LESS;
      }
      if (f0 > f1) {
        return ORDER_GREATER;
      }
      // In particular, compare (~0.0, 0) returns ORDER_EQUAL
      return ORDER_EQUAL;
    }
  }

  /** @see BuiltIn#REAL_COPY_SIGN */
  private static final Applicable2 REAL_COPY_SIGN =
      new BaseApplicable2<Float, Float, Float>(BuiltIn.REAL_COPY_SIGN) {
        @Override
        public Float apply(Float f0, Float f1) {
          if (Float.isNaN(f1)) {
            // 'Math.copySign' is free to treat a NaN sign argument as positive,
            // so extract the raw sign bit ourselves for a deterministic result.
            f1 = isNegative(f1) ? -1.0f : 1.0f;
          }
          return Math.copySign(f0, f1);
        }
      };

  /** @see BuiltIn#REAL_DIVIDE */
  private static final Applicable2 REAL_DIVIDE =
      new RealDivide(BuiltIn.REAL_DIVIDE);

  /** Implements {@link #REAL_DIVIDE}. */
  private static class RealDivide extends BaseApplicable2<Float, Float, Float> {
    RealDivide(BuiltIn builtIn) {
      super(builtIn);
    }

    @Override
    public Float apply(Float a0, Float a1) {
      // Do not normalize the sign of a NaN result; IEEE 754 leaves it
      // unspecified, so callers must not rely on it.
      return a0 / a1;
    }
  }

  /** @see BuiltIn#REAL_FLOOR */
  private static final Applicable REAL_FLOOR =
      new RealToInt(BuiltIn.REAL_FLOOR, Pos.ZERO);

  /** @see BuiltIn#REAL_FMT */
  private static final Applicable2 REAL_FMT = new RealFmt(Pos.ZERO);

  /** Implements {@link #REAL_FMT}. */
  private static class RealFmt
      extends BasePositionedApplicable2<String, List, Float> {
    RealFmt(Pos pos) {
      super(BuiltIn.REAL_FMT, pos);
    }

    @Override
    public RealFmt withPos(Pos pos) {
      return new RealFmt(pos);
    }

    @Override
    public String apply(List spec, Float r) {
      return FmtSpec.parse(spec, pos).format(r);
    }

    /**
     * Validates {@code spec} on partial application so that {@code Real.fmt
     * (StringCvt.SCI (SOME ~1))} raises {@code Size} immediately, matching
     * SML/NJ's behavior.
     */
    @Override
    public Applicable1<Applicable1<String, Float>, List> curry() {
      return new CurriedApplicable1<Applicable1<String, Float>, List>(
          builtIn, this) {
        @Override
        public Applicable1<String, Float> apply(List spec) {
          final FmtSpec info = FmtSpec.parse(spec, pos);
          return info::format;
        }
      };
    }
  }

  /** @see BuiltIn#REAL_FROM_INT */
  private static final Applicable REAL_FROM_INT =
      new BaseApplicable1<Float, Integer>(BuiltIn.REAL_FROM_INT) {
        @Override
        public Float apply(Integer i) {
          return i.floatValue();
        }
      };

  /** @see BuiltIn#REAL_FROM_MAN_EXP */
  private static final Applicable2 REAL_FROM_MAN_EXP =
      new BaseApplicable2<Float, Integer, Float>(BuiltIn.REAL_FROM_MAN_EXP) {
        @Override
        public Float apply(Integer exp, Float mantissa) {
          if (!Float.isFinite(mantissa)) {
            return mantissa;
          }
          if (exp >= Float.MAX_EXPONENT) {
            final int exp2 = (exp - Float.MIN_EXPONENT) & 0xFF;
            final int bits = Float.floatToRawIntBits(mantissa);
            final int bits2 = (bits & ~(0xFF << 23)) | (exp2 << 23);
            return Float.intBitsToFloat(bits2);
          }
          final int exp2 = (exp - Float.MIN_EXPONENT + 1) & 0xFF;
          final float exp3 = Float.intBitsToFloat(exp2 << 23); // 2 ^ exp
          return mantissa * exp3;
        }
      };

  /** @see BuiltIn#REAL_FROM_STRING */
  private static final Applicable REAL_FROM_STRING =
      new BaseApplicable1<List, String>(BuiltIn.REAL_FROM_STRING) {
        @Override
        public List<Float> apply(String s) {
          return scanString(REAL_SCAN, s);
        }
      };

  /** @see BuiltIn#REAL_IS_FINITE */
  private static final Applicable REAL_IS_FINITE =
      new BaseApplicable1<Boolean, Float>(BuiltIn.REAL_IS_FINITE) {
        @Override
        public Boolean apply(Float f) {
          return Float.isFinite(f);
        }
      };

  /** @see BuiltIn#REAL_IS_NAN */
  private static final Applicable REAL_IS_NAN =
      new BaseApplicable1<Boolean, Float>(BuiltIn.REAL_IS_NAN) {
        @Override
        public Boolean apply(Float f) {
          return Float.isNaN(f);
        }
      };

  /** @see BuiltIn#REAL_IS_NORMAL */
  private static final Applicable REAL_IS_NORMAL =
      new BaseApplicable1<Boolean, Float>(BuiltIn.REAL_IS_NORMAL) {
        @Override
        public Boolean apply(Float f) {
          return Float.isFinite(f)
              && (f >= Float.MIN_NORMAL || f <= -Float.MIN_NORMAL);
        }
      };

  /** @see BuiltIn#REAL_MAX */
  private static final Applicable2 REAL_MAX =
      new BaseApplicable2<Float, Float, Float>(BuiltIn.REAL_MAX) {
        @Override
        public Float apply(Float f0, Float f1) {
          return Float.isNaN(f0) ? f1 : Float.isNaN(f1) ? f0 : Math.max(f0, f1);
        }
      };

  /** @see BuiltIn#REAL_MAX_FINITE */
  private static final float REAL_MAX_FINITE = Float.MAX_VALUE;

  /** @see BuiltIn#REAL_MIN */
  private static final Applicable2 REAL_MIN =
      new BaseApplicable2<Float, Float, Float>(BuiltIn.REAL_MIN) {
        @Override
        public Float apply(Float f0, Float f1) {
          return Float.isNaN(f0) ? f1 : Float.isNaN(f1) ? f0 : Math.min(f0, f1);
        }
      };

  /** @see BuiltIn#REAL_MIN_NORMAL_POS */
  private static final float REAL_MIN_NORMAL_POS = Float.MIN_NORMAL;

  /** @see BuiltIn#REAL_MIN_POS */
  private static final float REAL_MIN_POS = Float.MIN_VALUE;

  /** @see BuiltIn#REAL_NEG_INF */
  private static final float REAL_NEG_INF = Float.NEGATIVE_INFINITY;

  /** @see BuiltIn#REAL_OP_EQ */
  private static final Applicable2 REAL_OP_EQ =
      new BaseApplicable2<Boolean, Float, Float>(BuiltIn.REAL_OP_EQ) {
        @Override
        public Boolean apply(Float a0, Float a1) {
          return a0.equals(a1);
        }
      };

  /** @see BuiltIn#REAL_OP_GE */
  private static final Applicable2 REAL_OP_GE =
      new BaseApplicable2<Boolean, Float, Float>(BuiltIn.REAL_OP_GE) {
        @Override
        public Boolean apply(Float a0, Float a1) {
          return a0 >= a1;
        }
      };

  /** @see BuiltIn#REAL_OP_GT */
  private static final Applicable2 REAL_OP_GT =
      new BaseApplicable2<Boolean, Float, Float>(BuiltIn.REAL_OP_GT) {
        @Override
        public Boolean apply(Float a0, Float a1) {
          return a0 > a1;
        }
      };

  /** @see BuiltIn#REAL_OP_LE */
  private static final Applicable2 REAL_OP_LE =
      new BaseApplicable2<Boolean, Float, Float>(BuiltIn.REAL_OP_LE) {
        @Override
        public Boolean apply(Float a0, Float a1) {
          return a0 <= a1;
        }
      };

  /** @see BuiltIn#REAL_OP_LT */
  private static final Applicable2 REAL_OP_LT =
      new BaseApplicable2<Boolean, Float, Float>(BuiltIn.REAL_OP_LT) {
        @Override
        public Boolean apply(Float a0, Float a1) {
          return a0 < a1;
        }
      };

  /** @see BuiltIn#REAL_OP_MINUS */
  private static final Applicable2 REAL_OP_MINUS =
      new BaseApplicable2<Float, Float, Float>(BuiltIn.REAL_OP_MINUS) {
        @Override
        public Float apply(Float a0, Float a1) {
          return a0 - a1;
        }
      };

  /** @see BuiltIn#REAL_OP_NE */
  private static final Applicable2 REAL_OP_NE =
      new BaseApplicable2<Boolean, Float, Float>(BuiltIn.REAL_OP_NE) {
        @Override
        public Boolean apply(Float a0, Float a1) {
          return !a0.equals(a1);
        }
      };

  /** @see BuiltIn#REAL_OP_NEGATE */
  private static final Applicable1 REAL_OP_NEGATE =
      new BaseApplicable1<Float, Float>(BuiltIn.REAL_OP_NEGATE) {
        @Override
        public Float apply(Float f) {
          // '-f' negates. IEEE 754 says negation flips the sign bit even for a
          // NaN, but the JVM is not required to, so Morel does not rely on the
          // sign of a negated NaN.
          return -f;
        }
      };

  /** @see BuiltIn#REAL_OP_PLUS */
  private static final Applicable2 REAL_OP_PLUS =
      new BaseApplicable2<Float, Float, Float>(BuiltIn.REAL_OP_PLUS) {
        @Override
        public Float apply(Float a0, Float a1) {
          return a0 + a1;
        }
      };

  /** @see BuiltIn#REAL_OP_TIMES */
  private static final Applicable2 REAL_OP_TIMES =
      new BaseApplicable2<Float, Float, Float>(BuiltIn.REAL_OP_TIMES) {
        @Override
        public Float apply(Float a0, Float a1) {
          return a0 * a1;
        }
      };

  /** @see BuiltIn#REAL_POS_INF */
  private static final float REAL_POS_INF = Float.POSITIVE_INFINITY;

  /** @see BuiltIn#REAL_PRECISION */
  // value is from jdk.internal.math.FloatConsts#SIGNIFICAND_WIDTH
  // (32 bit IEEE floating point is 1 sign bit, 8 bit exponent,
  // 23 bit mantissa)
  private static final int REAL_PRECISION = 24;

  /** @see BuiltIn#REAL_RADIX */
  private static final int REAL_RADIX = 2;

  /** @see BuiltIn#REAL_REAL_CEIL */
  private static final Applicable REAL_REAL_CEIL =
      new BaseApplicable1<Float, Float>(BuiltIn.REAL_REAL_CEIL) {
        @Override
        public Float apply(Float f) {
          return (float) Math.ceil(f);
        }
      };

  /** @see BuiltIn#REAL_REAL_FLOOR */
  private static final Applicable REAL_REAL_FLOOR =
      new BaseApplicable1<Float, Float>(BuiltIn.REAL_REAL_FLOOR) {
        @Override
        public Float apply(Float f) {
          return (float) Math.floor(f);
        }
      };

  /** @see BuiltIn#REAL_REAL_MOD */
  private static final Applicable REAL_REAL_MOD =
      new BaseApplicable1<Float, Float>(BuiltIn.REAL_REAL_MOD) {
        @Override
        public Float apply(Float f) {
          if (Float.isInfinite(f)) {
            // realMod posInf  => 0.0
            // realMod negInf  => ~0.0
            return f > 0f ? 0f : -0f;
          }
          return f % 1;
        }
      };

  /** @see BuiltIn#REAL_REAL_ROUND */
  private static final Applicable REAL_REAL_ROUND =
      new BaseApplicable1<Float, Float>(BuiltIn.REAL_REAL_ROUND) {
        @Override
        public Float apply(Float f) {
          return (float) Math.rint(f);
        }
      };

  /** @see BuiltIn#REAL_REAL_TRUNC */
  private static final Applicable REAL_REAL_TRUNC =
      new BaseApplicable1<Float, Float>(BuiltIn.REAL_REAL_TRUNC) {
        @Override
        public Float apply(Float f) {
          final float frac = f % 1;
          return f - frac;
        }
      };

  /** @see BuiltIn#REAL_REM */
  private static final Applicable2 REAL_REM =
      new BaseApplicable2<Float, Float, Float>(BuiltIn.REAL_REM) {
        @Override
        public Float apply(Float x, Float y) {
          return x % y;
        }
      };

  /** @see BuiltIn#REAL_ROUND */
  private static final Applicable REAL_ROUND =
      new RealToInt(BuiltIn.REAL_ROUND, Pos.ZERO);

  /** @see BuiltIn#REAL_SAME_SIGN */
  private static final Applicable2 REAL_SAME_SIGN =
      new BaseApplicable2<Boolean, Float, Float>(BuiltIn.REAL_SAME_SIGN) {
        @Override
        public Boolean apply(Float x, Float y) {
          return isNegative(x) == isNegative(y);
        }
      };

  /** @see BuiltIn#REAL_SCAN */
  private static final BaseApplicable2<List, Applicable1<List, Object>, Object>
      REAL_SCAN =
          new BaseApplicable2<List, Applicable1<List, Object>, Object>(
              BuiltIn.REAL_SCAN) {
            @Override
            public List apply(Applicable1<List, Object> reader, Object stream) {
              final CharSource[] source = {new CharSource(reader, stream)};
              source[0].skipWhitespace();
              final StringBuilder b = new StringBuilder();
              if (source[0].peek() == '~' || source[0].peek() == '-') {
                b.append('-');
                source[0].advance();
              } else if (source[0].peek() == '+') {
                source[0].advance();
              }

              // "inf", "infinity" and "nan", in any case.
              final boolean negative = b.length() > 0;
              for (String word : new String[] {"infinity", "inf", "nan"}) {
                if (consume(source, reader, word, true)) {
                  final float d =
                      word.equals("nan")
                          ? Float.NaN
                          : negative
                              ? Float.NEGATIVE_INFINITY
                              : Float.POSITIVE_INFINITY;
                  return optionSome(ImmutableList.of(d, source[0].stream()));
                }
              }

              int digits = digits(source, b, 10);
              if (source[0].peek() == '.') {
                final Object mark = source[0].stream();
                source[0].advance();
                final StringBuilder f = new StringBuilder(".");
                final int fracDigits = digits(source, f, 10);
                if (fracDigits == 0) {
                  source[0] = new CharSource(reader, mark);
                } else {
                  b.append(f);
                  digits += fracDigits;
                }
              }
              if (digits == 0) {
                return OPTION_NONE;
              }
              if (source[0].peek() == 'e' || source[0].peek() == 'E') {
                final Object mark = source[0].stream();
                source[0].advance();
                final StringBuilder e = new StringBuilder("e");
                if (source[0].peek() == '~' || source[0].peek() == '-') {
                  e.append('-');
                  source[0].advance();
                } else if (source[0].peek() == '+') {
                  source[0].advance();
                }
                if (digits(source, e, 10) == 0) {
                  source[0] = new CharSource(reader, mark);
                } else {
                  b.append(e);
                }
              }
              return optionSome(
                  ImmutableList.of(
                      Float.parseFloat(b.toString()), source[0].stream()));
            }
          };

  /** @see BuiltIn#REAL_SIGN */
  private static final Applicable REAL_SIGN = new RealSign(Pos.ZERO);

  /** Implements {@link #REAL_COMPARE}. */
  private static class RealSign
      extends BasePositionedApplicable1<Integer, Float> {
    RealSign(Pos pos) {
      super(BuiltIn.REAL_SIGN, pos);
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new RealSign(pos);
    }

    @Override
    public Integer apply(Float f) {
      if (Float.isNaN(f)) {
        throw new MorelRuntimeException(BuiltInExn.DOMAIN, pos);
      }
      return f == 0f
          ? 0 // positive or negative zero
          : (f > 0f)
              ? 1 // positive number or positive infinity
              : -1; // negative number or negative infinity
    }
  }

  /** @see BuiltIn#REAL_SIGN_BIT */
  private static final Applicable REAL_SIGN_BIT =
      new BaseApplicable1<Boolean, Float>(BuiltIn.REAL_SIGN_BIT) {
        @Override
        public Boolean apply(Float f) {
          return isNegative(f);
        }
      };

  /** @see BuiltIn#REAL_SPLIT */
  private static final Applicable REAL_SPLIT =
      new BaseApplicable1<List, Float>(BuiltIn.REAL_SPLIT) {
        @Override
        public List apply(Float f) {
          final float frac;
          final float whole;
          if (Float.isInfinite(f)) {
            // realMod posInf  => 0.0
            // realMod negInf  => ~0.0
            frac = f > 0f ? 0f : -0f;
            whole = f;
          } else {
            frac = f % 1;
            whole = f - frac;
          }
          return ImmutableList.of(frac, whole);
        }
      };

  /** @see BuiltIn#REAL_TO_MAN_EXP */
  private static final Applicable REAL_TO_MAN_EXP =
      new BaseApplicable1<List, Float>(BuiltIn.REAL_TO_MAN_EXP) {
        @Override
        public List apply(Float f) {
          // In IEEE 32 bit floating point,
          // bit 31 is the sign (1 bit);
          // bits 30 - 23 are the exponent (8 bits);
          // bits 22 - 0 are the mantissa (23 bits).
          final int bits = Float.floatToRawIntBits(f);
          final int exp = (bits >> 23) & 0xFF;
          final float mantissa;
          if (exp == 0) {
            // Exponent = 0 indicates that f is a very small number (0 < abs(f)
            // <= MIN_NORMAL). The mantissa has leading zeros, so we have to use
            // a different algorithm to get shift it into range.
            mantissa = f / Float.MIN_NORMAL;
          } else if (Float.isFinite(f)) {
            // Set the exponent to 126 (which is the exponent for 1.0). First
            // remove all set bits, then OR in the value 126.
            final int bits2 = (bits & ~(0xFF << 23)) | (0x7E << 23);
            mantissa = Float.intBitsToFloat(bits2);
          } else {
            mantissa = f;
          }
          return ImmutableList.of(exp + Float.MIN_EXPONENT, mantissa);
        }
      };

  /** @see BuiltIn#REAL_TO_STRING */
  private static final Applicable REAL_TO_STRING =
      new BaseApplicable1<String, Float>(BuiltIn.REAL_TO_STRING) {
        @Override
        public String apply(Float f) {
          // Java's formatting is reasonably close to ML's formatting,
          // if we replace minus signs.
          return floatToString(f);
        }
      };

  /** @see BuiltIn#REAL_TRUNC */
  private static final Applicable REAL_TRUNC =
      new RealToInt(BuiltIn.REAL_TRUNC, Pos.ZERO);

  /** @see BuiltIn#REAL_UNORDERED */
  private static final Applicable2 REAL_UNORDERED =
      new BaseApplicable2<Boolean, Float, Float>(BuiltIn.REAL_UNORDERED) {
        @Override
        public Boolean apply(Float f0, Float f1) {
          return Float.isNaN(f0) || Float.isNaN(f1);
        }
      };
}

// End RealCodes.java
