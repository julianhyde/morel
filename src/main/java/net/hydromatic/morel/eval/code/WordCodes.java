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
import static net.hydromatic.morel.eval.Codes.optionSome;
import static net.hydromatic.morel.eval.code.DateCodes.digits;
import static net.hydromatic.morel.eval.code.DateCodes.order;
import static net.hydromatic.morel.eval.code.StringCodes.scanString;

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
 * Implementations of built-in functions and values in the {@code Word}
 * structure.
 */
@SuppressWarnings({"rawtypes"})
public final class WordCodes {
  private WordCodes() {}

  /** Registers the implementations in this class. */
  public static void register(BiConsumer<BuiltIn, Object> c) {
    // lint: sort until '#}' where '##c\.accept\(BuiltIn' erase 'c\.'
    c.accept(BuiltIn.WORD_ANDB, WORD_ANDB);
    c.accept(BuiltIn.WORD_COMPARE, WORD_COMPARE);
    c.accept(BuiltIn.WORD_DIV, WORD_DIV);
    c.accept(BuiltIn.WORD_FMT, WORD_FMT);
    c.accept(BuiltIn.WORD_FROM_INT, WORD_FROM_INT);
    c.accept(BuiltIn.WORD_FROM_LARGE, WORD_FROM_LARGE);
    c.accept(BuiltIn.WORD_FROM_LARGE_INT, WORD_FROM_LARGE_INT);
    c.accept(BuiltIn.WORD_FROM_LARGE_WORD, WORD_FROM_LARGE_WORD);
    c.accept(BuiltIn.WORD_FROM_STRING, WORD_FROM_STRING);
    c.accept(BuiltIn.WORD_MAX, WORD_MAX);
    c.accept(BuiltIn.WORD_MIN, WORD_MIN);
    c.accept(BuiltIn.WORD_MOD, WORD_MOD);
    c.accept(BuiltIn.WORD_NOTB, WORD_NOTB);
    c.accept(BuiltIn.WORD_OP_GE, WORD_OP_GE);
    c.accept(BuiltIn.WORD_OP_GT, WORD_OP_GT);
    c.accept(BuiltIn.WORD_OP_LE, WORD_OP_LE);
    c.accept(BuiltIn.WORD_OP_LT, WORD_OP_LT);
    c.accept(BuiltIn.WORD_OP_MINUS, WORD_OP_MINUS);
    c.accept(BuiltIn.WORD_OP_NEGATE, WORD_OP_NEGATE);
    c.accept(BuiltIn.WORD_OP_PLUS, WORD_OP_PLUS);
    c.accept(BuiltIn.WORD_OP_SHIFT_LEFT, WORD_OP_SHIFT_LEFT);
    c.accept(BuiltIn.WORD_OP_SHIFT_RIGHT, WORD_OP_SHIFT_RIGHT);
    c.accept(
        BuiltIn.WORD_OP_SHIFT_RIGHT_ARITHMETIC, WORD_OP_SHIFT_RIGHT_ARITHMETIC);
    c.accept(BuiltIn.WORD_OP_TIMES, WORD_OP_TIMES);
    c.accept(BuiltIn.WORD_ORB, WORD_ORB);
    c.accept(BuiltIn.WORD_SCAN, WORD_SCAN);
    c.accept(BuiltIn.WORD_TO_INT, WORD_TO_INT);
    c.accept(BuiltIn.WORD_TO_INT_X, WORD_TO_INT_X);
    c.accept(BuiltIn.WORD_TO_LARGE, WORD_TO_LARGE);
    c.accept(BuiltIn.WORD_TO_LARGE_INT, WORD_TO_LARGE_INT);
    c.accept(BuiltIn.WORD_TO_LARGE_INT_X, WORD_TO_LARGE_INT_X);
    c.accept(BuiltIn.WORD_TO_LARGE_WORD, WORD_TO_LARGE_WORD);
    c.accept(BuiltIn.WORD_TO_LARGE_WORD_X, WORD_TO_LARGE_WORD_X);
    c.accept(BuiltIn.WORD_TO_LARGE_X, WORD_TO_LARGE_X);
    c.accept(BuiltIn.WORD_TO_STRING, WORD_TO_STRING);
    c.accept(BuiltIn.WORD_WORD_SIZE, WORD_WORD_SIZE);
    c.accept(BuiltIn.WORD_XORB, WORD_XORB);
  }

  // lint: sort until '#}' \
  //   where '##private static final [^ ]+ [^ ]+ =' \
  //   erase 'private static final [^ ]+ '

  // Word values are stored as the bit pattern of a signed Java 'long';
  // wordSize is 64. Unsigned semantics use the Long.*Unsigned helpers.

  /** @see BuiltIn#WORD_ANDB */
  private static final Applicable2 WORD_ANDB =
      new BaseApplicable2<Long, Long, Long>(BuiltIn.WORD_ANDB) {
        @Override
        public Long apply(Long a0, Long a1) {
          return a0 & a1;
        }
      };

  /** @see BuiltIn#WORD_COMPARE */
  private static final Applicable2 WORD_COMPARE =
      new BaseApplicable2<List, Long, Long>(BuiltIn.WORD_COMPARE) {
        @Override
        public List apply(Long a0, Long a1) {
          return order(Long.compareUnsigned(a0, a1));
        }
      };

  /** @see BuiltIn#WORD_DIV */
  private static final Applicable2 WORD_DIV =
      new WordDiv(BuiltIn.WORD_DIV, Pos.ZERO);

  /** Implements {@link #WORD_DIV}. */
  private static class WordDiv
      extends BasePositionedApplicable2<Long, Long, Long> {
    WordDiv(BuiltIn builtIn, Pos pos) {
      super(builtIn, pos);
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new WordDiv(builtIn, pos);
    }

    @Override
    public Long apply(Long a0, Long a1) {
      if (a1 == 0) {
        throw new MorelRuntimeException(BuiltInExn.DIV, pos);
      }
      return Long.divideUnsigned(a0, a1);
    }
  }

  /** @see BuiltIn#WORD_FMT */
  private static final Applicable2 WORD_FMT =
      new BaseApplicable2<String, List, Long>(BuiltIn.WORD_FMT) {
        @Override
        public String apply(List radix, Long w) {
          return Long.toUnsignedString(w, Radix.of(radix).base)
              .toUpperCase(Locale.ROOT);
        }
      };

  /** @see BuiltIn#WORD_FROM_INT */
  private static final Applicable1 WORD_FROM_INT =
      new BaseApplicable1<Long, Integer>(BuiltIn.WORD_FROM_INT) {
        @Override
        public Long apply(Integer i) {
          return (long) i;
        }
      };

  /** @see BuiltIn#WORD_FROM_LARGE */
  private static final Applicable1 WORD_FROM_LARGE =
      identity(BuiltIn.WORD_FROM_LARGE);

  /** @see BuiltIn#WORD_FROM_LARGE_INT */
  private static final Applicable1 WORD_FROM_LARGE_INT =
      new BaseApplicable1<Long, Integer>(BuiltIn.WORD_FROM_LARGE_INT) {
        @Override
        public Long apply(Integer i) {
          return (long) i;
        }
      };

  /** @see BuiltIn#WORD_FROM_LARGE_WORD */
  private static final Applicable1 WORD_FROM_LARGE_WORD =
      identity(BuiltIn.WORD_FROM_LARGE_WORD);

  /** @see BuiltIn#WORD_FROM_STRING */
  private static final Applicable WORD_FROM_STRING =
      new WordFromString(BuiltIn.WORD_FROM_STRING, Pos.ZERO);

  /** Implements {@link #WORD_FROM_STRING}. */
  private static class WordFromString
      extends BasePositionedApplicable1<List, String> {
    WordFromString(BuiltIn builtIn, Pos pos) {
      super(builtIn, pos);
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new WordFromString(builtIn, pos);
    }

    @Override
    public List apply(String s) {
      return scanString(WORD_SCAN, Radix.HEX, s);
    }
  }

  /** @see BuiltIn#WORD_MAX */
  private static final Applicable2 WORD_MAX =
      new BaseApplicable2<Long, Long, Long>(BuiltIn.WORD_MAX) {
        @Override
        public Long apply(Long a0, Long a1) {
          return Long.compareUnsigned(a0, a1) >= 0 ? a0 : a1;
        }
      };

  /** @see BuiltIn#WORD_MIN */
  private static final Applicable2 WORD_MIN =
      new BaseApplicable2<Long, Long, Long>(BuiltIn.WORD_MIN) {
        @Override
        public Long apply(Long a0, Long a1) {
          return Long.compareUnsigned(a0, a1) <= 0 ? a0 : a1;
        }
      };

  /** @see BuiltIn#WORD_MOD */
  private static final Applicable2 WORD_MOD =
      new WordMod(BuiltIn.WORD_MOD, Pos.ZERO);

  /** Implements {@link #WORD_MOD}. */
  private static class WordMod
      extends BasePositionedApplicable2<Long, Long, Long> {
    WordMod(BuiltIn builtIn, Pos pos) {
      super(builtIn, pos);
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new WordMod(builtIn, pos);
    }

    @Override
    public Long apply(Long a0, Long a1) {
      if (a1 == 0) {
        throw new MorelRuntimeException(BuiltInExn.DIV, pos);
      }
      return Long.remainderUnsigned(a0, a1);
    }
  }

  /** @see BuiltIn#WORD_NOTB */
  private static final Applicable1 WORD_NOTB =
      new BaseApplicable1<Long, Long>(BuiltIn.WORD_NOTB) {
        @Override
        public Long apply(Long w) {
          return ~w;
        }
      };

  /** @see BuiltIn#WORD_OP_GE */
  private static final Applicable2 WORD_OP_GE =
      new BaseApplicable2<Boolean, Long, Long>(BuiltIn.WORD_OP_GE) {
        @Override
        public Boolean apply(Long a0, Long a1) {
          return Long.compareUnsigned(a0, a1) >= 0;
        }
      };

  /** @see BuiltIn#WORD_OP_GT */
  private static final Applicable2 WORD_OP_GT =
      new BaseApplicable2<Boolean, Long, Long>(BuiltIn.WORD_OP_GT) {
        @Override
        public Boolean apply(Long a0, Long a1) {
          return Long.compareUnsigned(a0, a1) > 0;
        }
      };

  /** @see BuiltIn#WORD_OP_LE */
  private static final Applicable2 WORD_OP_LE =
      new BaseApplicable2<Boolean, Long, Long>(BuiltIn.WORD_OP_LE) {
        @Override
        public Boolean apply(Long a0, Long a1) {
          return Long.compareUnsigned(a0, a1) <= 0;
        }
      };

  /** @see BuiltIn#WORD_OP_LT */
  private static final Applicable2 WORD_OP_LT =
      new BaseApplicable2<Boolean, Long, Long>(BuiltIn.WORD_OP_LT) {
        @Override
        public Boolean apply(Long a0, Long a1) {
          return Long.compareUnsigned(a0, a1) < 0;
        }
      };

  /** @see BuiltIn#WORD_OP_MINUS */
  private static final Applicable2 WORD_OP_MINUS =
      new BaseApplicable2<Long, Long, Long>(BuiltIn.WORD_OP_MINUS) {
        @Override
        public Long apply(Long a0, Long a1) {
          return a0 - a1;
        }
      };

  /** @see BuiltIn#WORD_OP_NEGATE */
  private static final Applicable1 WORD_OP_NEGATE =
      new BaseApplicable1<Long, Long>(BuiltIn.WORD_OP_NEGATE) {
        @Override
        public Long apply(Long w) {
          return -w;
        }
      };

  /** @see BuiltIn#WORD_OP_PLUS */
  private static final Applicable2 WORD_OP_PLUS =
      new BaseApplicable2<Long, Long, Long>(BuiltIn.WORD_OP_PLUS) {
        @Override
        public Long apply(Long a0, Long a1) {
          return a0 + a1;
        }
      };

  /** @see BuiltIn#WORD_OP_SHIFT_LEFT */
  private static final Applicable2 WORD_OP_SHIFT_LEFT =
      new BaseApplicable2<Long, Long, Long>(BuiltIn.WORD_OP_SHIFT_LEFT) {
        @Override
        public Long apply(Long a0, Long a1) {
          return Long.compareUnsigned(a1, 64) >= 0 ? 0L : a0 << a1;
        }
      };

  /** @see BuiltIn#WORD_OP_SHIFT_RIGHT */
  private static final Applicable2 WORD_OP_SHIFT_RIGHT =
      new BaseApplicable2<Long, Long, Long>(BuiltIn.WORD_OP_SHIFT_RIGHT) {
        @Override
        public Long apply(Long a0, Long a1) {
          return Long.compareUnsigned(a1, 64) >= 0 ? 0L : a0 >>> a1;
        }
      };

  /** @see BuiltIn#WORD_OP_SHIFT_RIGHT_ARITHMETIC */
  private static final Applicable2 WORD_OP_SHIFT_RIGHT_ARITHMETIC =
      new BaseApplicable2<Long, Long, Long>(
          BuiltIn.WORD_OP_SHIFT_RIGHT_ARITHMETIC) {
        @Override
        public Long apply(Long a0, Long a1) {
          if (Long.compareUnsigned(a1, 64) >= 0) {
            return a0 < 0 ? -1L : 0L;
          }
          return a0 >> a1;
        }
      };

  /** @see BuiltIn#WORD_OP_TIMES */
  private static final Applicable2 WORD_OP_TIMES =
      new BaseApplicable2<Long, Long, Long>(BuiltIn.WORD_OP_TIMES) {
        @Override
        public Long apply(Long a0, Long a1) {
          return a0 * a1;
        }
      };

  /** @see BuiltIn#WORD_ORB */
  private static final Applicable2 WORD_ORB =
      new BaseApplicable2<Long, Long, Long>(BuiltIn.WORD_ORB) {
        @Override
        public Long apply(Long a0, Long a1) {
          return a0 | a1;
        }
      };

  /** @see BuiltIn#WORD_SCAN */
  private static final BaseApplicable3<
          List, List, Applicable1<List, Object>, Object>
      WORD_SCAN =
          new BaseApplicable3<List, List, Applicable1<List, Object>, Object>(
              BuiltIn.WORD_SCAN) {
            @Override
            public List apply(
                List radix, Applicable1<List, Object> reader, Object stream) {
              final int base = Radix.of(radix).base;
              final CharSource[] source = {new CharSource(reader, stream)};
              source[0].skipWhitespace();

              // A word may be written "0w42"; a hexadecimal word may also be
              // written "0x1f" or "0wx1f". If what follows is not a digit, the
              // "0" is a digit and the rest is not ours.
              if (source[0].peek() == '0') {
                final Object mark = source[0].stream();
                source[0].advance();
                boolean prefix = false;
                if (source[0].peek() == 'w') {
                  source[0].advance();
                  if (base == 16
                      && (source[0].peek() == 'x' || source[0].peek() == 'X')) {
                    source[0].advance();
                  }
                  prefix = Character.digit(source[0].peek(), base) >= 0;
                } else if (base == 16
                    && (source[0].peek() == 'x' || source[0].peek() == 'X')) {
                  source[0].advance();
                  prefix = Character.digit(source[0].peek(), base) >= 0;
                }
                if (!prefix) {
                  source[0] = new CharSource(reader, mark);
                }
              }

              final StringBuilder b = new StringBuilder();
              if (digits(source, b, base) == 0) {
                return OPTION_NONE;
              }
              final long w;
              try {
                w = Long.parseUnsignedLong(b.toString(), base);
              } catch (NumberFormatException e) {
                throw new MorelRuntimeException(BuiltInExn.OVERFLOW, Pos.ZERO);
              }
              return optionSome(ImmutableList.of(w, source[0].stream()));
            }
          };

  /** @see BuiltIn#WORD_TO_INT */
  private static final Applicable WORD_TO_INT =
      new WordToInt(BuiltIn.WORD_TO_INT, Pos.ZERO);

  /**
   * Implements {@link #WORD_TO_INT} and {@link #WORD_TO_LARGE_INT}: treats the
   * word as an unsigned value and raises {@code Overflow} if it does not fit in
   * {@code int}.
   */
  private static class WordToInt
      extends BasePositionedApplicable1<Integer, Long> {
    WordToInt(BuiltIn builtIn, Pos pos) {
      super(builtIn, pos);
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new WordToInt(builtIn, pos);
    }

    @Override
    public Integer apply(Long w) {
      if (Long.compareUnsigned(w, Integer.MAX_VALUE) > 0) {
        throw new MorelRuntimeException(BuiltInExn.OVERFLOW, pos);
      }
      return (int) (long) w;
    }
  }

  /** @see BuiltIn#WORD_TO_INT_X */
  private static final Applicable WORD_TO_INT_X =
      new WordToIntX(BuiltIn.WORD_TO_INT_X, Pos.ZERO);

  /**
   * Implements {@link #WORD_TO_INT_X} and {@link #WORD_TO_LARGE_INT_X}: treats
   * the word as a signed 2's-complement value and raises {@code Overflow} if it
   * does not fit in {@code int}.
   */
  private static class WordToIntX
      extends BasePositionedApplicable1<Integer, Long> {
    WordToIntX(BuiltIn builtIn, Pos pos) {
      super(builtIn, pos);
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new WordToIntX(builtIn, pos);
    }

    @Override
    public Integer apply(Long w) {
      if (w < Integer.MIN_VALUE || w > Integer.MAX_VALUE) {
        throw new MorelRuntimeException(BuiltInExn.OVERFLOW, pos);
      }
      return (int) (long) w;
    }
  }

  /** @see BuiltIn#WORD_TO_LARGE */
  private static final Applicable1 WORD_TO_LARGE =
      identity(BuiltIn.WORD_TO_LARGE);

  /** @see BuiltIn#WORD_TO_LARGE_INT */
  private static final Applicable WORD_TO_LARGE_INT =
      new WordToInt(BuiltIn.WORD_TO_LARGE_INT, Pos.ZERO);

  /** @see BuiltIn#WORD_TO_LARGE_INT_X */
  private static final Applicable WORD_TO_LARGE_INT_X =
      new WordToIntX(BuiltIn.WORD_TO_LARGE_INT_X, Pos.ZERO);

  /** @see BuiltIn#WORD_TO_LARGE_WORD */
  private static final Applicable1 WORD_TO_LARGE_WORD =
      identity(BuiltIn.WORD_TO_LARGE_WORD);

  /** @see BuiltIn#WORD_TO_LARGE_WORD_X */
  private static final Applicable1 WORD_TO_LARGE_WORD_X =
      identity(BuiltIn.WORD_TO_LARGE_WORD_X);

  /** @see BuiltIn#WORD_TO_LARGE_X */
  private static final Applicable1 WORD_TO_LARGE_X =
      identity(BuiltIn.WORD_TO_LARGE_X);

  /** @see BuiltIn#WORD_TO_STRING */
  private static final Applicable1 WORD_TO_STRING =
      new BaseApplicable1<String, Long>(BuiltIn.WORD_TO_STRING) {
        @Override
        public String apply(Long w) {
          return Long.toUnsignedString(w, 16).toUpperCase(Locale.ROOT);
        }
      };

  /** @see BuiltIn#WORD_WORD_SIZE */
  private static final int WORD_WORD_SIZE = 64;

  /** @see BuiltIn#WORD_XORB */
  private static final Applicable2 WORD_XORB =
      new BaseApplicable2<Long, Long, Long>(BuiltIn.WORD_XORB) {
        @Override
        public Long apply(Long a0, Long a1) {
          return a0 ^ a1;
        }
      };

  /** Returns an Applicable that returns its argument. */
  static Applicable1 identity(BuiltIn builtIn) {
    return new BaseApplicable1<Object, Object>(builtIn) {
      @Override
      public Object apply(Object arg) {
        return arg;
      }
    };
  }
}

// End WordCodes.java
