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

import static net.hydromatic.morel.eval.Codes.OPTION_NONE;
import static net.hydromatic.morel.eval.Codes.intToString;
import static net.hydromatic.morel.eval.Codes.optionSome;
import static net.hydromatic.morel.eval.code.DateCodes.order;
import static net.hydromatic.morel.eval.code.StringCodes.scanString;
import static net.hydromatic.morel.eval.code.WordCodes.identity;

import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.eval.Applicable;
import net.hydromatic.morel.eval.Applicable1;
import net.hydromatic.morel.eval.Applicable2;
import net.hydromatic.morel.eval.BuiltInExn;
import net.hydromatic.morel.eval.MorelRuntimeException;
import net.hydromatic.morel.eval.code.StringCodes.CharSource;

/**
 * Implementations of built-in functions and values in the {@code Int}
 * structure.
 */
@SuppressWarnings({"rawtypes"})
public final class IntCodes {
  private IntCodes() {}

  /** Registers the implementations in this class. */
  public static void register(BiConsumer<BuiltIn, Object> c) {
    // lint: sort until '#}' where '##c\.accept\(BuiltIn' erase 'c\.'
    c.accept(BuiltIn.INT_ABS, INT_ABS);
    c.accept(BuiltIn.INT_COMPARE, INT_COMPARE);
    c.accept(BuiltIn.INT_DIV, INT_DIV);
    c.accept(BuiltIn.INT_FMT, INT_FMT);
    c.accept(BuiltIn.INT_FROM_INT, INT_FROM_INT);
    c.accept(BuiltIn.INT_FROM_LARGE, INT_FROM_LARGE);
    c.accept(BuiltIn.INT_FROM_STRING, INT_FROM_STRING);
    c.accept(BuiltIn.INT_MAX, INT_MAX);
    c.accept(BuiltIn.INT_MAX_INT, INT_MAX_INT);
    c.accept(BuiltIn.INT_MIN, INT_MIN);
    c.accept(BuiltIn.INT_MIN_INT, INT_MIN_INT);
    c.accept(BuiltIn.INT_MOD, INT_MOD);
    c.accept(BuiltIn.INT_OP_GE, INT_OP_GE);
    c.accept(BuiltIn.INT_OP_GT, INT_OP_GT);
    c.accept(BuiltIn.INT_OP_LE, INT_OP_LE);
    c.accept(BuiltIn.INT_OP_LT, INT_OP_LT);
    c.accept(BuiltIn.INT_OP_MINUS, INT_OP_MINUS);
    c.accept(BuiltIn.INT_OP_NEGATE, INT_OP_NEGATE);
    c.accept(BuiltIn.INT_OP_PLUS, INT_OP_PLUS);
    c.accept(BuiltIn.INT_OP_TIMES, INT_OP_TIMES);
    c.accept(BuiltIn.INT_PRECISION, INT_PRECISION);
    c.accept(BuiltIn.INT_QUOT, INT_QUOT);
    c.accept(BuiltIn.INT_REM, INT_REM);
    c.accept(BuiltIn.INT_SAME_SIGN, INT_SAME_SIGN);
    c.accept(BuiltIn.INT_SCAN, INT_SCAN);
    c.accept(BuiltIn.INT_SIGN, INT_SIGN);
    c.accept(BuiltIn.INT_TO_INT, INT_TO_INT);
    c.accept(BuiltIn.INT_TO_LARGE, INT_TO_LARGE);
    c.accept(BuiltIn.INT_TO_STRING, INT_TO_STRING);
  }

  // lint: sort until '#}' \
  //   where '##private static final [^ ]+ [^ ]+ =' \
  //   erase 'private static final [^ ]+ '

  /** @see BuiltIn#INT_ABS */
  private static final Applicable INT_ABS =
      new IntAbs(BuiltIn.INT_ABS, Pos.ZERO);

  /** Implements {@link #INT_ABS}. */
  private static class IntAbs
      extends BasePositionedApplicable1<Integer, Integer> {
    IntAbs(BuiltIn builtIn, Pos pos) {
      super(builtIn, pos);
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new IntAbs(builtIn, pos);
    }

    @Override
    public Integer apply(Integer i) {
      if (i == Integer.MIN_VALUE) {
        throw new MorelRuntimeException(BuiltInExn.OVERFLOW, pos);
      }
      return Math.abs(i);
    }
  }

  /** @see BuiltIn#INT_COMPARE */
  private static final Applicable2 INT_COMPARE =
      new BaseApplicable2<List, Integer, Integer>(BuiltIn.INT_COMPARE) {
        @Override
        public List apply(Integer a0, Integer a1) {
          return order(Integer.compare(a0, a1));
        }
      };

  /** @see BuiltIn#INT_DIV */
  private static final Applicable2 INT_DIV =
      new IntDiv(BuiltIn.INT_DIV, Pos.ZERO);

  /** @see BuiltIn#INT_FMT */
  private static final Applicable2 INT_FMT =
      new BaseApplicable2<String, List, Integer>(BuiltIn.INT_FMT) {
        @Override
        public String apply(List radix, Integer i) {
          // Use upper-case digits A..F for hex, prefix '-' with '~'.
          final String s =
              Integer.toString(i, Radix.of(radix).base)
                  .toUpperCase(Locale.ROOT);
          return s.startsWith("-") ? "~" + s.substring(1) : s;
        }
      };

  /** @see BuiltIn#INT_FROM_INT */
  private static final Applicable1 INT_FROM_INT =
      identity(BuiltIn.INT_FROM_INT);

  /** @see BuiltIn#INT_FROM_LARGE */
  private static final Applicable1 INT_FROM_LARGE =
      identity(BuiltIn.INT_FROM_LARGE);

  /** @see BuiltIn#INT_FROM_STRING */
  private static final Applicable INT_FROM_STRING =
      new BaseApplicable1<List, String>(BuiltIn.INT_FROM_STRING) {
        @Override
        public List apply(String s) {
          return scanString(INT_SCAN, Radix.DEC, s);
        }
      };

  /** @see BuiltIn#INT_MAX */
  private static final Applicable2 INT_MAX =
      new BaseApplicable2<Integer, Integer, Integer>(BuiltIn.INT_MAX) {
        @Override
        public Integer apply(Integer a0, Integer a1) {
          return Math.max(a0, a1);
        }
      };

  /** @see BuiltIn#INT_MAX_INT */
  private static final List INT_MAX_INT = optionSome(Integer.MAX_VALUE);

  /** @see BuiltIn#INT_MIN */
  private static final Applicable2 INT_MIN =
      new BaseApplicable2<Integer, Integer, Integer>(BuiltIn.INT_MIN) {
        @Override
        public Integer apply(Integer a0, Integer a1) {
          return Math.min(a0, a1);
        }
      };

  /** @see BuiltIn#INT_MIN_INT */
  private static final List INT_MIN_INT = optionSome(Integer.MIN_VALUE);

  /** Implements {@link #INT_DIV}. */
  private static class IntDiv
      extends BasePositionedApplicable2<Integer, Integer, Integer> {
    IntDiv(BuiltIn builtIn, Pos pos) {
      super(builtIn, pos);
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new IntDiv(builtIn, pos);
    }

    @Override
    public Integer apply(Integer a0, Integer a1) {
      if (a1 == 0) {
        throw new MorelRuntimeException(BuiltInExn.DIV, pos);
      }
      return Math.floorDiv(a0, a1);
    }
  }

  /** @see BuiltIn#INT_MOD */
  private static final Applicable2 INT_MOD =
      new IntMod(BuiltIn.INT_MOD, Pos.ZERO);

  /** Implements {@link #INT_MOD}. */
  private static class IntMod
      extends BasePositionedApplicable2<Integer, Integer, Integer> {
    IntMod(BuiltIn builtIn, Pos pos) {
      super(builtIn, pos);
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new IntMod(builtIn, pos);
    }

    @Override
    public Integer apply(Integer a0, Integer a1) {
      if (a1 == 0) {
        throw new MorelRuntimeException(BuiltInExn.DIV, pos);
      }
      return Math.floorMod(a0, a1);
    }
  }

  /** @see BuiltIn#INT_OP_GE */
  private static final Applicable2 INT_OP_GE =
      new BaseApplicable2<Boolean, Integer, Integer>(BuiltIn.INT_OP_GE) {
        @Override
        public Boolean apply(Integer a0, Integer a1) {
          return a0 >= a1;
        }
      };

  /** @see BuiltIn#INT_OP_GT */
  private static final Applicable2 INT_OP_GT =
      new BaseApplicable2<Boolean, Integer, Integer>(BuiltIn.INT_OP_GT) {
        @Override
        public Boolean apply(Integer a0, Integer a1) {
          return a0 > a1;
        }
      };

  /** @see BuiltIn#INT_OP_LE */
  private static final Applicable2 INT_OP_LE =
      new BaseApplicable2<Boolean, Integer, Integer>(BuiltIn.INT_OP_LE) {
        @Override
        public Boolean apply(Integer a0, Integer a1) {
          return a0 <= a1;
        }
      };

  /** @see BuiltIn#INT_OP_LT */
  private static final Applicable2 INT_OP_LT =
      new BaseApplicable2<Boolean, Integer, Integer>(BuiltIn.INT_OP_LT) {
        @Override
        public Boolean apply(Integer a0, Integer a1) {
          return a0 < a1;
        }
      };

  /** @see BuiltIn#INT_OP_MINUS */
  private static final Applicable2 INT_OP_MINUS =
      new BaseApplicable2<Integer, Integer, Integer>(BuiltIn.INT_OP_MINUS) {
        @Override
        public Integer apply(Integer a0, Integer a1) {
          return a0 - a1;
        }
      };

  /** @see BuiltIn#INT_OP_NEGATE */
  private static final Applicable1 INT_OP_NEGATE =
      new BaseApplicable1<Integer, Integer>(BuiltIn.INT_OP_NEGATE) {
        @Override
        public Integer apply(Integer i) {
          return -i;
        }
      };

  /** @see BuiltIn#INT_OP_PLUS */
  private static final Applicable2 INT_OP_PLUS =
      new BaseApplicable2<Integer, Integer, Integer>(BuiltIn.INT_OP_PLUS) {
        @Override
        public Integer apply(Integer a0, Integer a1) {
          return a0 + a1;
        }
      };

  /** @see BuiltIn#INT_OP_TIMES */
  private static final Applicable2 INT_OP_TIMES =
      new BaseApplicable2<Integer, Integer, Integer>(BuiltIn.INT_OP_TIMES) {
        @Override
        public Integer apply(Integer a0, Integer a1) {
          return a0 * a1;
        }
      };

  /** @see BuiltIn#INT_PRECISION */
  private static final List INT_PRECISION = optionSome(32); // Java int 32 bits

  /** @see BuiltIn#INT_QUOT */
  private static final Applicable2 INT_QUOT =
      new BaseApplicable2<Integer, Integer, Integer>(BuiltIn.INT_QUOT) {
        @Override
        public Integer apply(Integer a0, Integer a1) {
          return a0 / a1;
        }
      };

  /** @see BuiltIn#INT_REM */
  private static final Applicable2 INT_REM =
      new BaseApplicable2<Integer, Integer, Integer>(BuiltIn.INT_REM) {
        @Override
        public Integer apply(Integer a0, Integer a1) {
          return a0 % a1;
        }
      };

  /** @see BuiltIn#INT_SAME_SIGN */
  private static final Applicable2 INT_SAME_SIGN =
      new BaseApplicable2<Boolean, Integer, Integer>(BuiltIn.INT_SAME_SIGN) {
        @Override
        public Boolean apply(Integer a0, Integer a1) {
          return a0 < 0 && a1 < 0 || a0 == 0 && a1 == 0 || a0 > 0 && a1 > 0;
        }
      };

  /** @see BuiltIn#INT_SCAN */
  private static final BaseApplicable3<
          List, List, Applicable1<List, Object>, Object>
      INT_SCAN =
          new BaseApplicable3<List, List, Applicable1<List, Object>, Object>(
              BuiltIn.INT_SCAN) {
            @Override
            public List apply(
                List radix, Applicable1<List, Object> reader, Object stream) {
              final int base = Radix.of(radix).base;
              CharSource source = new CharSource(reader, stream);
              source.skipWhitespace();

              final boolean negative;
              if (source.peek() == '~' || source.peek() == '-') {
                negative = true;
                source.advance();
              } else {
                negative = false;
                if (source.peek() == '+') {
                  source.advance();
                }
              }

              // A hexadecimal integer may start "0x"; if what follows is not a
              // hexadecimal digit, the "0" is a digit and the "x" is not ours.
              if (base == 16 && source.peek() == '0') {
                final Object mark = source.stream();
                source.advance();
                if (source.peek() == 'x' || source.peek() == 'X') {
                  source.advance();
                  if (Character.digit(source.peek(), 16) < 0) {
                    source = new CharSource(reader, mark);
                  }
                } else {
                  source = new CharSource(reader, mark);
                }
              }

              final StringBuilder digits = new StringBuilder();
              while (source.peek() >= 0
                  && Character.digit(source.peek(), base) >= 0) {
                digits.append((char) source.peek());
                source.advance();
              }
              if (digits.length() == 0) {
                return OPTION_NONE;
              }
              final int i;
              try {
                i =
                    Integer.parseInt(
                        negative ? "-" + digits : digits.toString(), base);
              } catch (NumberFormatException e) {
                throw new MorelRuntimeException(BuiltInExn.OVERFLOW, Pos.ZERO);
              }
              return optionSome(ImmutableList.of(i, source.stream()));
            }
          };

  /** @see BuiltIn#INT_SIGN */
  private static final Applicable1 INT_SIGN =
      new BaseApplicable1<Integer, Integer>(BuiltIn.INT_SIGN) {
        @Override
        public Integer apply(Integer i) {
          return Integer.compare(i, 0);
        }
      };

  /** @see BuiltIn#INT_TO_INT */
  private static final Applicable1 INT_TO_INT = identity(BuiltIn.INT_TO_INT);

  /** @see BuiltIn#INT_TO_LARGE */
  private static final Applicable1 INT_TO_LARGE =
      identity(BuiltIn.INT_TO_LARGE);

  /** @see BuiltIn#INT_TO_STRING */
  private static final Applicable1 INT_TO_STRING =
      new BaseApplicable1<String, Integer>(BuiltIn.INT_TO_STRING) {
        @Override
        public String apply(Integer f) {
          return intToString(f);
        }
      };
}

// End IntCodes.java
