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

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static net.hydromatic.morel.eval.Codes.OPTION_NONE;
import static net.hydromatic.morel.eval.Codes.optionSome;
import static net.hydromatic.morel.eval.code.DateCodes.order;
import static net.hydromatic.morel.eval.code.GeneralCodes.ORDER_EQUAL;
import static net.hydromatic.morel.eval.code.GeneralCodes.ORDER_GREATER;
import static net.hydromatic.morel.eval.code.GeneralCodes.ORDER_LESS;
import static net.hydromatic.morel.util.Characters.isPrint;
import static net.hydromatic.morel.util.Static.padRightTo;

import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Chars;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.eval.Applicable;
import net.hydromatic.morel.eval.Applicable1;
import net.hydromatic.morel.eval.Applicable2;
import net.hydromatic.morel.eval.Applicable3;
import net.hydromatic.morel.eval.BuiltInExn;
import net.hydromatic.morel.eval.Codes.Positioned;
import net.hydromatic.morel.eval.MorelRuntimeException;
import net.hydromatic.morel.parse.Parsers;
import net.hydromatic.morel.util.Characters;
import net.hydromatic.morel.util.MapList;
import net.hydromatic.morel.util.PairList;
import org.apache.calcite.runtime.FlatLists;
import org.jspecify.annotations.Nullable;

/**
 * Implementations of built-in functions and values in the {@code Char} and
 * {@code String} structures.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public final class StringCodes {
  private StringCodes() {}

  /** Registers the implementations in this class. */
  public static void register(PairList<BuiltIn, Object> b) {
    // lint: sort until '#}' where '##b\.add\(BuiltIn' erase 'b\.'
    b.add(BuiltIn.CHAR_CHR, CHAR_CHR);
    b.add(BuiltIn.CHAR_COMPARE, CHAR_COMPARE);
    b.add(BuiltIn.CHAR_CONTAINS, CHAR_CONTAINS);
    b.add(BuiltIn.CHAR_FROM_CSTRING, CHAR_FROM_CSTRING);
    b.add(BuiltIn.CHAR_FROM_INT, CHAR_FROM_INT);
    b.add(BuiltIn.CHAR_FROM_STRING, CHAR_FROM_STRING);
    b.add(BuiltIn.CHAR_IS_ALPHA, CHAR_IS_ALPHA);
    b.add(BuiltIn.CHAR_IS_ALPHA_NUM, CHAR_IS_ALPHA_NUM);
    b.add(BuiltIn.CHAR_IS_ASCII, CHAR_IS_ASCII);
    b.add(BuiltIn.CHAR_IS_CNTRL, CHAR_IS_CNTRL);
    b.add(BuiltIn.CHAR_IS_DIGIT, CHAR_IS_DIGIT);
    b.add(BuiltIn.CHAR_IS_GRAPH, CHAR_IS_GRAPH);
    b.add(BuiltIn.CHAR_IS_HEX_DIGIT, CHAR_IS_HEX_DIGIT);
    b.add(BuiltIn.CHAR_IS_LOWER, CHAR_IS_LOWER);
    b.add(BuiltIn.CHAR_IS_OCT_DIGIT, CHAR_IS_OCT_DIGIT);
    b.add(BuiltIn.CHAR_IS_PRINT, CHAR_IS_PRINT);
    b.add(BuiltIn.CHAR_IS_PUNCT, CHAR_IS_PUNCT);
    b.add(BuiltIn.CHAR_IS_SPACE, CHAR_IS_SPACE);
    b.add(BuiltIn.CHAR_IS_UPPER, CHAR_IS_UPPER);
    b.add(BuiltIn.CHAR_MAX_CHAR, CHAR_MAX_CHAR);
    b.add(BuiltIn.CHAR_MAX_ORD, CHAR_MAX_ORD);
    b.add(BuiltIn.CHAR_MIN_CHAR, CHAR_MIN_CHAR);
    b.add(BuiltIn.CHAR_NOT_CONTAINS, CHAR_NOT_CONTAINS);
    b.add(BuiltIn.CHAR_OP_EQ, CHAR_OP_EQ);
    b.add(BuiltIn.CHAR_OP_GE, CHAR_OP_GE);
    b.add(BuiltIn.CHAR_OP_GT, CHAR_OP_GT);
    b.add(BuiltIn.CHAR_OP_LE, CHAR_OP_LE);
    b.add(BuiltIn.CHAR_OP_LT, CHAR_OP_LT);
    b.add(BuiltIn.CHAR_OP_NE, CHAR_OP_NE);
    b.add(BuiltIn.CHAR_ORD, CHAR_ORD);
    b.add(BuiltIn.CHAR_PRED, CHAR_PRED);
    b.add(BuiltIn.CHAR_SCAN, CHAR_SCAN);
    b.add(BuiltIn.CHAR_SUCC, CHAR_SUCC);
    b.add(BuiltIn.CHAR_TO_CSTRING, CHAR_TO_CSTRING);
    b.add(BuiltIn.CHAR_TO_LOWER, CHAR_TO_LOWER);
    b.add(BuiltIn.CHAR_TO_STRING, CHAR_TO_STRING);
    b.add(BuiltIn.CHAR_TO_UPPER, CHAR_TO_UPPER);
    b.add(BuiltIn.STRING_COLLATE, STRING_COLLATE);
    b.add(BuiltIn.STRING_COMPARE, STRING_COMPARE);
    b.add(BuiltIn.STRING_CONCAT, STRING_CONCAT);
    b.add(BuiltIn.STRING_CONCAT_WITH, STRING_CONCAT_WITH);
    b.add(BuiltIn.STRING_CVT_DROPL, STRING_CVT_DROPL);
    b.add(BuiltIn.STRING_CVT_PAD_LEFT, STRING_CVT_PAD_LEFT);
    b.add(BuiltIn.STRING_CVT_PAD_RIGHT, STRING_CVT_PAD_RIGHT);
    b.add(BuiltIn.STRING_CVT_SCAN_STRING, STRING_CVT_SCAN_STRING);
    b.add(BuiltIn.STRING_CVT_SKIP_WS, STRING_CVT_SKIP_WS);
    b.add(BuiltIn.STRING_CVT_SPLITL, STRING_CVT_SPLITL);
    b.add(BuiltIn.STRING_CVT_TAKEL, STRING_CVT_TAKEL);
    b.add(BuiltIn.STRING_EXPLODE, STRING_EXPLODE);
    b.add(BuiltIn.STRING_EXTRACT, STRING_EXTRACT);
    b.add(BuiltIn.STRING_FIELDS, STRING_FIELDS);
    b.add(BuiltIn.STRING_FROM_CSTRING, STRING_FROM_CSTRING);
    b.add(BuiltIn.STRING_FROM_STRING, STRING_FROM_STRING);
    b.add(BuiltIn.STRING_IMPLODE, STRING_IMPLODE);
    b.add(BuiltIn.STRING_IS_PREFIX, STRING_IS_PREFIX);
    b.add(BuiltIn.STRING_IS_SUBSTRING, STRING_IS_SUBSTRING);
    b.add(BuiltIn.STRING_IS_SUFFIX, STRING_IS_SUFFIX);
    b.add(BuiltIn.STRING_MAP, STRING_MAP);
    b.add(BuiltIn.STRING_MAX_SIZE, STRING_MAX_SIZE);
    b.add(BuiltIn.STRING_OP_CARET, STRING_OP_CARET);
    b.add(BuiltIn.STRING_OP_EQ, STRING_OP_EQ);
    b.add(BuiltIn.STRING_OP_GE, STRING_OP_GE);
    b.add(BuiltIn.STRING_OP_GT, STRING_OP_GT);
    b.add(BuiltIn.STRING_OP_LE, STRING_OP_LE);
    b.add(BuiltIn.STRING_OP_LT, STRING_OP_LT);
    b.add(BuiltIn.STRING_OP_NE, STRING_OP_NE);
    b.add(BuiltIn.STRING_SCAN, STRING_SCAN);
    b.add(BuiltIn.STRING_SIZE, STRING_SIZE);
    b.add(BuiltIn.STRING_STR, STRING_STR);
    b.add(BuiltIn.STRING_SUB, STRING_SUB);
    b.add(BuiltIn.STRING_SUBSTRING, STRING_SUBSTRING);
    b.add(BuiltIn.STRING_TO_CSTRING, STRING_TO_CSTRING);
    b.add(BuiltIn.STRING_TO_STRING, STRING_TO_STRING);
    b.add(BuiltIn.STRING_TOKENS, STRING_TOKENS);
    b.add(BuiltIn.STRING_TRANSLATE, STRING_TRANSLATE);
  }

  // lint: sort until '#}' \
  //   where '##private static final [^ ]+ [^ ]+ =' \
  //   erase 'private static final [^ ]+ '

  /** @see BuiltIn#CHAR_CHR */
  private static final Applicable1 CHAR_CHR = new CharChr(Pos.ZERO);

  /** Implements {@link #CHAR_CHR}. */
  private static class CharChr
      extends BasePositionedApplicable1<Character, Integer>
      implements Positioned {
    CharChr(Pos pos) {
      super(BuiltIn.CHAR_CHR, pos);
    }

    @Override
    public CharChr withPos(Pos pos) {
      return new CharChr(pos);
    }

    @Override
    public Character apply(Integer ord) {
      if (ord < 0 || ord > 255) {
        throw new MorelRuntimeException(BuiltInExn.CHR, pos);
      }
      return (char) ord.intValue();
    }
  }

  /** @see BuiltIn#CHAR_COMPARE */
  private static final Applicable2 CHAR_COMPARE =
      new BaseApplicable2<List, Character, Character>(BuiltIn.CHAR_COMPARE) {
        @Override
        public List apply(Character a0, Character a1) {
          if (a0 < a1) {
            return ORDER_LESS;
          }
          if (a0 > a1) {
            return ORDER_GREATER;
          }
          return ORDER_EQUAL;
        }
      };

  /** @see BuiltIn#CHAR_CONTAINS */
  private static final Applicable2 CHAR_CONTAINS =
      charContains(BuiltIn.CHAR_CONTAINS);

  /** Implement {@link #CHAR_CONTAINS} and {@link #CHAR_NOT_CONTAINS}. */
  private static Applicable2 charContains(BuiltIn builtIn) {
    return new BaseApplicable2<Boolean, String, Character>(builtIn) {
      final boolean negate = builtIn == BuiltIn.CHAR_NOT_CONTAINS;

      @Override
      public Boolean apply(String s, Character c) {
        return s.indexOf(c) >= 0 ^ negate;
      }
    };
  }

  /** @see BuiltIn#CHAR_FROM_CSTRING */
  private static final Applicable1 CHAR_FROM_CSTRING =
      new BaseApplicable1<List, String>(BuiltIn.CHAR_FROM_CSTRING) {
        @Override
        public List apply(String s) {
          final Applicable1<List, Object> reader = stringReader(s);
          final CharSource[] source = {new CharSource(reader, 0)};
          final Character c = scanCChar(source, reader);
          return c == null ? OPTION_NONE : optionSome(c);
        }
      };

  /**
   * Scans a character, or a C escape sequence denoting a character, from {@code
   * source}, and returns null if {@code source} does not start with one.
   *
   * <p>A character stands for itself if it is printable and is not a backslash;
   * unlike SML, C allows an unescaped double-quote here. The escape sequences
   * are C's: a letter, a backslash, a quote or a question mark; one to three
   * octal digits; or "x" and one or more hexadecimal digits. A code above
   * {@link #CHAR_MAX_ORD} is not a character, and the source is left where it
   * was.
   *
   * @see BuiltIn#CHAR_FROM_CSTRING
   * @see BuiltIn#STRING_FROM_CSTRING
   */
  private static @Nullable Character scanCChar(
      CharSource[] source, Applicable1<List, Object> reader) {
    final Object mark = source[0].stream();
    final int c = source[0].peek();
    if (c < 0) {
      return null;
    }
    if (c != '\\') {
      if (!isPrint((char) c)) {
        return null;
      }
      source[0].advance();
      return (char) c;
    }

    source[0].advance();
    final int c2 = source[0].peek();
    final int escaped;
    switch (c2) {
      case 'a':
        escaped = 7;
        break;
      case 'b':
        escaped = '\b';
        break;
      case 't':
        escaped = '\t';
        break;
      case 'n':
        escaped = '\n';
        break;
      case 'v':
        escaped = 11;
        break;
      case 'f':
        escaped = '\f';
        break;
      case 'r':
        escaped = '\r';
        break;
      case '"':
      case '\'':
      case '?':
      case '\\':
        escaped = c2;
        break;

      case 'x':
        // "x" and as many hexadecimal digits as follow it; there must be at
        // least one.
        source[0].advance();
        return scanCCode(source, reader, mark, 16, Integer.MAX_VALUE);

      default:
        if (c2 >= '0' && c2 <= '7') {
          // Up to three octal digits, stopping at the first character that is
          // not one.
          return scanCCode(source, reader, mark, 8, 3);
        }
        source[0] = new CharSource(reader, mark);
        return null;
    }
    source[0].advance();
    return (char) escaped;
  }

  /**
   * Scans up to {@code max} digits in the given base - at least one - and
   * returns the character whose code they are.
   *
   * <p>Returns null, and rewinds {@code source} to {@code mark}, if there is no
   * digit or the code is not that of a character.
   */
  private static @Nullable Character scanCCode(
      CharSource[] source,
      Applicable1<List, Object> reader,
      Object mark,
      int base,
      int max) {
    int code = 0;
    int n = 0;
    while (n < max && Character.digit(source[0].peek(), base) >= 0) {
      code = code * base + Character.digit(source[0].peek(), base);
      if (code > CHAR_MAX_ORD) {
        // Keep going so that the whole numeral is consumed conceptually, but
        // the value is out of range; stop before it overflows.
        source[0] = new CharSource(reader, mark);
        return null;
      }
      source[0].advance();
      ++n;
    }
    if (n == 0) {
      source[0] = new CharSource(reader, mark);
      return null;
    }
    return (char) code;
  }

  /** @see BuiltIn#CHAR_FROM_INT */
  private static final Applicable CHAR_FROM_INT =
      new BaseApplicable1<List, Integer>(BuiltIn.CHAR_FROM_INT) {
        @Override
        public List apply(Integer ord) {
          if (ord < 0 || ord > 255) {
            return OPTION_NONE;
          }
          return optionSome((char) ord.intValue());
        }
      };

  /** @see BuiltIn#CHAR_FROM_STRING */
  private static final Applicable CHAR_FROM_STRING =
      new BaseApplicable1<List, String>(BuiltIn.CHAR_FROM_STRING) {
        @Override
        public List apply(String s) {
          return scanString(CHAR_SCAN, s);
        }
      };

  /** @see BuiltIn#CHAR_IS_ALPHA */
  private static final Applicable CHAR_IS_ALPHA =
      new CharPredicate(BuiltIn.CHAR_IS_ALPHA, Characters::isAlpha);

  /** @see BuiltIn#CHAR_IS_ALPHA_NUM */
  private static final Applicable CHAR_IS_ALPHA_NUM =
      new CharPredicate(BuiltIn.CHAR_IS_ALPHA_NUM, Characters::isAlphaNum);

  /** @see BuiltIn#CHAR_IS_ASCII */
  private static final Applicable CHAR_IS_ASCII =
      new CharPredicate(BuiltIn.CHAR_IS_ASCII, Characters::isAscii);

  /** @see BuiltIn#CHAR_IS_CNTRL */
  private static final Applicable CHAR_IS_CNTRL =
      new CharPredicate(BuiltIn.CHAR_IS_CNTRL, Characters::isCntrl);

  /** @see BuiltIn#CHAR_IS_DIGIT */
  private static final Applicable CHAR_IS_DIGIT =
      new CharPredicate(BuiltIn.CHAR_IS_DIGIT, Characters::isDigit);

  /** @see BuiltIn#CHAR_IS_GRAPH */
  private static final Applicable CHAR_IS_GRAPH =
      new CharPredicate(BuiltIn.CHAR_IS_GRAPH, Characters::isGraph);

  /** @see BuiltIn#CHAR_IS_HEX_DIGIT */
  private static final Applicable CHAR_IS_HEX_DIGIT =
      new CharPredicate(BuiltIn.CHAR_IS_HEX_DIGIT, Characters::isHexDigit);

  /** @see BuiltIn#CHAR_IS_LOWER */
  private static final Applicable CHAR_IS_LOWER =
      new CharPredicate(BuiltIn.CHAR_IS_LOWER, Characters::isLower);

  /** @see BuiltIn#CHAR_IS_OCT_DIGIT */
  private static final Applicable CHAR_IS_OCT_DIGIT =
      new CharPredicate(BuiltIn.CHAR_IS_OCT_DIGIT, Characters::isOctDigit);

  /** @see BuiltIn#CHAR_IS_PRINT */
  private static final Applicable CHAR_IS_PRINT =
      new CharPredicate(BuiltIn.CHAR_IS_PRINT, Characters::isPrint);

  /** @see BuiltIn#CHAR_IS_PUNCT */
  private static final Applicable CHAR_IS_PUNCT =
      new CharPredicate(BuiltIn.CHAR_IS_PUNCT, Characters::isPunct);

  /** @see BuiltIn#CHAR_IS_SPACE */
  private static final Applicable CHAR_IS_SPACE =
      new CharPredicate(BuiltIn.CHAR_IS_SPACE, Characters::isSpace);

  /** @see BuiltIn#CHAR_IS_UPPER */
  private static final Applicable CHAR_IS_UPPER =
      new CharPredicate(BuiltIn.CHAR_IS_UPPER, Characters::isUpper);

  /** @see BuiltIn#CHAR_MAX_CHAR */
  private static final Character CHAR_MAX_CHAR = 255;

  /** @see BuiltIn#CHAR_MAX_ORD */
  private static final Integer CHAR_MAX_ORD = 255;

  /** @see BuiltIn#CHAR_MIN_CHAR */
  private static final Character CHAR_MIN_CHAR = 0;

  /** @see BuiltIn#CHAR_NOT_CONTAINS */
  private static final Applicable2 CHAR_NOT_CONTAINS =
      charContains(BuiltIn.CHAR_NOT_CONTAINS);

  /** @see BuiltIn#CHAR_OP_EQ */
  private static final Applicable2 CHAR_OP_EQ =
      new BaseApplicable2<Boolean, Character, Character>(BuiltIn.CHAR_OP_EQ) {
        @Override
        public Boolean apply(Character a0, Character a1) {
          return a0.equals(a1);
        }
      };

  /** @see BuiltIn#CHAR_OP_GE */
  private static final Applicable2 CHAR_OP_GE =
      new BaseApplicable2<Boolean, Character, Character>(BuiltIn.CHAR_OP_GE) {
        @Override
        public Boolean apply(Character a0, Character a1) {
          return a0 >= a1;
        }
      };

  /** @see BuiltIn#CHAR_OP_GT */
  private static final Applicable2 CHAR_OP_GT =
      new BaseApplicable2<Boolean, Character, Character>(BuiltIn.CHAR_OP_GT) {
        @Override
        public Boolean apply(Character a0, Character a1) {
          return a0 > a1;
        }
      };

  /** @see BuiltIn#CHAR_OP_LE */
  private static final Applicable2 CHAR_OP_LE =
      new BaseApplicable2<Boolean, Character, Character>(BuiltIn.CHAR_OP_LE) {
        @Override
        public Boolean apply(Character a0, Character a1) {
          return a0 <= a1;
        }
      };

  /** @see BuiltIn#CHAR_OP_LT */
  private static final Applicable2 CHAR_OP_LT =
      new BaseApplicable2<Boolean, Character, Character>(BuiltIn.CHAR_OP_LT) {
        @Override
        public Boolean apply(Character a0, Character a1) {
          return a0 < a1;
        }
      };

  /** @see BuiltIn#CHAR_OP_NE */
  private static final Applicable2 CHAR_OP_NE =
      new BaseApplicable2<Boolean, Character, Character>(BuiltIn.CHAR_OP_NE) {
        @Override
        public Boolean apply(Character a0, Character a1) {
          return !a0.equals(a1);
        }
      };

  /** @see BuiltIn#CHAR_ORD */
  private static final Applicable1 CHAR_ORD =
      new BaseApplicable1<Integer, Character>(BuiltIn.CHAR_ORD) {
        @Override
        public Integer apply(Character arg) {
          return (int) arg;
        }
      };

  /** @see BuiltIn#CHAR_PRED */
  private static final Applicable1 CHAR_PRED = new CharPred(Pos.ZERO);

  /** Implements {@link #CHAR_PRED}. */
  private static class CharPred
      extends BasePositionedApplicable1<Character, Character> {
    CharPred(Pos pos) {
      super(BuiltIn.CHAR_PRED, pos);
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new CharPred(pos);
    }

    @Override
    public Character apply(Character c) {
      if (c == CHAR_MIN_CHAR) {
        throw new MorelRuntimeException(BuiltInExn.CHR, pos);
      }
      return (char) (c - 1);
    }
  }

  /** @see BuiltIn#CHAR_SCAN */
  private static final BaseApplicable2<List, Applicable1<List, Object>, Object>
      CHAR_SCAN =
          new BaseApplicable2<List, Applicable1<List, Object>, Object>(
              BuiltIn.CHAR_SCAN) {
            @Override
            public List apply(Applicable1<List, Object> reader, Object stream) {
              final CharSource[] source = {new CharSource(reader, stream)};
              skipGaps(source, reader);
              final Character c = scanChar(source, reader, false);
              return c == null
                  ? OPTION_NONE
                  : optionSome(ImmutableList.of(c, source[0].stream()));
            }
          };

  /**
   * Consumes any escaped formatting sequences that {@code source} starts with.
   *
   * <p>Such a sequence is a backslash, one or more whitespace characters, and a
   * backslash; it stands for nothing. If a backslash is followed by whitespace
   * but no closing backslash, nothing is consumed, and the caller will find the
   * backslash and reject it as an ill-formed escape.
   */
  private static void skipGaps(
      CharSource[] source, Applicable1<List, Object> reader) {
    for (; ; ) {
      final Object mark = source[0].stream();
      if (source[0].peek() != '\\') {
        return;
      }
      source[0].advance();
      if (source[0].peek() < 0
          || !Character.isWhitespace((char) source[0].peek())) {
        source[0] = new CharSource(reader, mark);
        return;
      }
      source[0].skipWhitespace();
      if (source[0].peek() != '\\') {
        source[0] = new CharSource(reader, mark);
        return;
      }
      source[0].advance();
    }
  }

  /**
   * Scans a character, or an SML escape sequence denoting a character, from
   * {@code source}, and returns null if {@code source} does not start with one.
   *
   * <p>A character stands for itself if it is printable and is not a backslash;
   * a backslash, and the non-printable characters, have to be escaped. A
   * double-quote stands for itself only if {@code quoteOk}; it does in a string
   * but not in a character. If the result is null, {@code source} is left where
   * it was.
   *
   * @see BuiltIn#CHAR_SCAN
   * @see BuiltIn#STRING_SCAN
   */
  private static @Nullable Character scanChar(
      CharSource[] source, Applicable1<List, Object> reader, boolean quoteOk) {
    final Object mark = source[0].stream();
    final int c = source[0].peek();
    if (c < 0) {
      return null;
    }
    if (c != '\\') {
      if (!isPrint((char) c) || c == '"' && !quoteOk) {
        return null;
      }
      source[0].advance();
      return (char) c;
    }

    source[0].advance();
    final int c2 = source[0].peek();
    final int escaped;
    switch (c2) {
      case '"':
      case '\\':
        escaped = c2;
        break;
      case 'a':
        escaped = 7;
        break;
      case 'b':
        escaped = '\b';
        break;
      case 't':
        escaped = '\t';
        break;
      case 'n':
        escaped = '\n';
        break;
      case 'v':
        escaped = 11;
        break;
      case 'f':
        escaped = '\f';
        break;
      case 'r':
        escaped = '\r';
        break;

      case '^':
        // "\^@" is character 0, "\^A" is 1, ..., "\^_" is 31.
        source[0].advance();
        final int c3 = source[0].peek();
        if (c3 < '@' || c3 > '_') {
          source[0] = new CharSource(reader, mark);
          return null;
        }
        source[0].advance();
        return (char) (c3 - '@');

      case 'u':
        // A "u" escape is the character whose code is the four hexadecimal
        // digits that follow.
        source[0].advance();
        return scanCode(source, reader, mark, 16, 4);

      default:
        if (c2 >= '0' && c2 <= '9') {
          // "\ddd" is the character whose code is the three decimal digits
          // "ddd".
          return scanCode(source, reader, mark, 10, 3);
        }
        source[0] = new CharSource(reader, mark);
        return null;
    }
    source[0].advance();
    return (char) escaped;
  }

  /**
   * Scans exactly {@code n} digits in the given base, and returns the character
   * whose code they are.
   *
   * <p>Returns null, and rewinds {@code source} to {@code mark}, if there are
   * too few digits or the code is not that of a character.
   */
  private static @Nullable Character scanCode(
      CharSource[] source,
      Applicable1<List, Object> reader,
      Object mark,
      int base,
      int n) {
    int code = 0;
    for (int i = 0; i < n; i++) {
      final int digit = Character.digit(source[0].peek(), base);
      if (digit < 0) {
        source[0] = new CharSource(reader, mark);
        return null;
      }
      code = code * base + digit;
      source[0].advance();
    }
    if (code > CHAR_MAX_ORD) {
      source[0] = new CharSource(reader, mark);
      return null;
    }
    return (char) code;
  }

  /** @see BuiltIn#CHAR_SUCC */
  private static final Applicable CHAR_SUCC = new CharSucc(Pos.ZERO);

  /** Implements {@link #CHAR_SUCC}. */
  private static class CharSucc
      extends BasePositionedApplicable1<Character, Character> {
    CharSucc(Pos pos) {
      super(BuiltIn.CHAR_SUCC, pos);
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new CharSucc(pos);
    }

    @Override
    public Character apply(Character c) {
      if (c.equals(CHAR_MAX_CHAR)) {
        throw new MorelRuntimeException(BuiltInExn.CHR, pos);
      }
      return (char) (c + 1);
    }
  }

  /** @see BuiltIn#CHAR_TO_CSTRING */
  private static final Applicable CHAR_TO_CSTRING =
      new BaseApplicable1<String, Character>(BuiltIn.CHAR_TO_CSTRING) {
        @Override
        public String apply(Character character) {
          return charToCString(character);
        }
      };

  /**
   * Converts a character to how it appears in a C string literal.
   *
   * <p>A printable character stands for itself, except for the four that C
   * requires to be escaped; every other character becomes a backslash and three
   * octal digits. Inverse of {@link #scanCChar}.
   */
  private static String charToCString(char c) {
    switch (c) {
      case 7:
        return "\\a";
      case '\b':
        return "\\b";
      case '\t':
        return "\\t";
      case '\n':
        return "\\n";
      case 11:
        return "\\v";
      case '\f':
        return "\\f";
      case '\r':
        return "\\r";
      case '"':
        return "\\\"";
      case '\'':
        return "\\'";
      case '?':
        return "\\?";
      case '\\':
        return "\\\\";
      default:
        return isPrint(c) ? String.valueOf(c) : format("\\%03o", (int) c);
    }
  }

  /** @see BuiltIn#CHAR_TO_LOWER */
  private static final Applicable CHAR_TO_LOWER =
      new BaseApplicable1<Character, Character>(BuiltIn.CHAR_TO_LOWER) {
        @Override
        public Character apply(Character c) {
          return Character.toLowerCase(c);
        }
      };

  /** @see BuiltIn#CHAR_TO_STRING */
  private static final Applicable CHAR_TO_STRING =
      new BaseApplicable1<String, Character>(BuiltIn.CHAR_TO_STRING) {
        @Override
        public String apply(Character c) {
          return Parsers.charToString(c);
        }
      };

  /** @see BuiltIn#CHAR_TO_UPPER */
  private static final Applicable CHAR_TO_UPPER =
      new BaseApplicable1<Character, Character>(BuiltIn.CHAR_TO_LOWER) {
        @Override
        public Character apply(Character c) {
          return Character.toUpperCase(c);
        }
      };

  /**
   * Pulls characters from a stream through a {@code StringCvt} reader, for the
   * {@code scan} functions.
   *
   * <p>The stream is opaque - it belongs to whoever supplied the reader - so
   * the only way along it is to call the reader. A stream is a value, though,
   * so keeping one is enough to go back to it.
   */
  static class CharSource {
    private final Applicable1<List, Object> reader;
    private Object stream;
    /** Next character, or -1 at the end of the stream. */
    private int c;
    /** Stream that follows {@link #c}; null at the end of the stream. */
    private @Nullable Object nextStream;

    CharSource(Applicable1<List, Object> reader, Object stream) {
      this.reader = reader;
      this.stream = stream;
      read();
    }

    private void read() {
      final List option = reader.apply(stream);
      if (option.size() < 2) {
        c = -1;
      } else {
        final List pair = (List) option.get(1);
        c = (Character) pair.get(0);
        nextStream = pair.get(1);
      }
    }

    /** Returns the next character without consuming it, or -1 at the end. */
    int peek() {
      return c;
    }

    /** Consumes the next character. */
    void advance() {
      stream = requireNonNull(nextStream);
      read();
    }

    /** Returns the stream, positioned before the next character. */
    Object stream() {
      return stream;
    }

    /** Consumes any whitespace. */
    void skipWhitespace() {
      while (c >= 0 && Character.isWhitespace((char) c)) {
        advance();
      }
    }
  }

  /** @see BuiltIn#STRING_COLLATE */
  private static final Applicable2 STRING_COLLATE =
      new BaseApplicable2<List, Applicable1, List>(BuiltIn.STRING_COLLATE) {
        @Override
        public List apply(Applicable1 comparator, List tuple) {
          final String string0 = (String) tuple.get(0);
          final String string1 = (String) tuple.get(1);
          final int n0 = string0.length();
          final int n1 = string1.length();
          final int n = Math.min(n0, n1);
          for (int i = 0; i < n; i++) {
            final char char0 = string0.charAt(i);
            final char char1 = string1.charAt(i);
            final List compare =
                (List) comparator.apply(FlatLists.of(char0, char1));
            if (!compare.get(0).equals("EQUAL")) {
              return compare;
            }
          }
          return order(Integer.compare(n0, n1));
        }
      };

  /** @see BuiltIn#STRING_COMPARE */
  private static final Applicable2 STRING_COMPARE =
      new BaseApplicable2<List, String, String>(BuiltIn.STRING_COMPARE) {
        @Override
        public List apply(String a0, String a1) {
          return order(a0.compareTo(a1));
        }
      };

  /** @see BuiltIn#STRING_CONCAT */
  private static final Applicable1 STRING_CONCAT =
      new StringConcat(BuiltIn.STRING_CONCAT, Pos.ZERO);

  /** Implements {@link #STRING_CONCAT}. */
  private static class StringConcat
      extends BasePositionedApplicable1<String, List<String>> {
    StringConcat(BuiltIn builtIn, Pos pos) {
      super(builtIn, pos);
    }

    @Override
    public StringConcat withPos(Pos pos) {
      return new StringConcat(BuiltIn.STRING_CONCAT, pos);
    }

    @Override
    public String apply(List<String> list) {
      long n = 0;
      for (String s : list) {
        n += s.length();
      }
      if (n > STRING_MAX_SIZE) {
        throw new MorelRuntimeException(BuiltInExn.SIZE, pos);
      }
      return String.join("", list);
    }
  }

  /** @see BuiltIn#STRING_CONCAT_WITH */
  private static final Applicable2 STRING_CONCAT_WITH =
      new StringConcatWith(BuiltIn.STRING_CONCAT_WITH, Pos.ZERO);

  /** Implements {@link #STRING_CONCAT_WITH}. */
  private static class StringConcatWith
      extends BasePositionedApplicable2<String, String, List<String>> {
    StringConcatWith(BuiltIn builtIn, Pos pos) {
      super(builtIn, pos);
    }

    @Override
    public StringConcatWith withPos(Pos pos) {
      return new StringConcatWith(BuiltIn.STRING_CONCAT_WITH, pos);
    }

    @Override
    public String apply(String separator, List<String> list) {
      long n = 0;
      for (String s : list) {
        n += s.length();
        n += separator.length();
      }
      if (n > STRING_MAX_SIZE) {
        throw new MorelRuntimeException(BuiltInExn.SIZE, pos);
      }
      return String.join(separator, list);
    }
  }

  /**
   * Reads from {@code src} the longest prefix of characters that satisfy {@code
   * p}, and returns it with the rest of the stream.
   *
   * <p>Shared by {@link BuiltIn#STRING_CVT_SPLITL}, {@link
   * BuiltIn#STRING_CVT_TAKEL} and {@link BuiltIn#STRING_CVT_DROPL}, which
   * return the prefix and the rest, the prefix, and the rest respectively.
   *
   * @param p Whether a character belongs to the prefix
   * @param rdr Reads one character, returning it with the rest of the stream,
   *     or {@code NONE} at the end of the stream
   * @param src Stream to read from
   * @return the prefix, and the stream that follows it
   */
  private static List splitl(
      Applicable1<Boolean, Character> p,
      Applicable1<List, Object> rdr,
      Object src) {
    final StringBuilder b = new StringBuilder();
    Object s = src;
    for (; ; ) {
      final List option = rdr.apply(s);
      if (option.size() < 2) {
        break; // NONE: end of stream
      }
      final List pair = (List) option.get(1);
      final Character c = (Character) pair.get(0);
      if (!p.apply(c)) {
        break;
      }
      b.append(c.charValue());
      s = pair.get(1);
    }
    return ImmutableList.of(b.toString(), s);
  }

  /** @see BuiltIn#STRING_CVT_DROPL */
  private static final Applicable STRING_CVT_DROPL =
      new BaseApplicable3<
          Object,
          Applicable1<Boolean, Character>,
          Applicable1<List, Object>,
          Object>(BuiltIn.STRING_CVT_DROPL) {
        @Override
        public Object apply(
            Applicable1<Boolean, Character> p,
            Applicable1<List, Object> rdr,
            Object src) {
          return splitl(p, rdr, src).get(1);
        }
      };

  /** @see BuiltIn#STRING_CVT_PAD_LEFT */
  private static final Applicable STRING_CVT_PAD_LEFT =
      new BaseApplicable3<String, Character, Integer, String>(
          BuiltIn.STRING_CVT_PAD_LEFT) {
        @Override
        public String apply(Character c, Integer i, String s) {
          if (s.length() >= i) {
            return s;
          }
          final StringBuilder sb = new StringBuilder(i);
          padRightTo(sb, i - s.length(), c);
          return sb.append(s).toString();
        }
      };

  /** @see BuiltIn#STRING_CVT_PAD_RIGHT */
  private static final Applicable STRING_CVT_PAD_RIGHT =
      new BaseApplicable3<String, Character, Integer, String>(
          BuiltIn.STRING_CVT_PAD_RIGHT) {
        @Override
        public String apply(Character c, Integer i, String s) {
          if (s.length() >= i) {
            return s;
          }
          final StringBuilder sb = new StringBuilder(i).append(s);
          return padRightTo(sb, i, c).toString();
        }
      };

  /** @see BuiltIn#STRING_CVT_SCAN_STRING */
  private static final Applicable STRING_CVT_SCAN_STRING =
      new BaseApplicable2<
          List,
          Applicable1<Applicable1<List, Object>, Applicable1<List, Object>>,
          String>(BuiltIn.STRING_CVT_SCAN_STRING) {
        @Override
        public List apply(
            Applicable1<Applicable1<List, Object>, Applicable1<List, Object>> f,
            String s) {
          return value(f.apply(stringReader(s)).apply(0));
        }
      };

  /**
   * Returns a reader over the characters of {@code s}.
   *
   * <p>The stream is a position in {@code s}, but a scanner's type does not say
   * so, and the reader is the only thing that can make sense of it.
   */
  private static Applicable1<List, Object> stringReader(String s) {
    return new BaseApplicable1<List, Object>(BuiltIn.STRING_CVT_SCAN_STRING) {
      @Override
      public List apply(Object stream) {
        final int i = (Integer) stream;
        return i < s.length()
            ? optionSome(ImmutableList.of(s.charAt(i), i + 1))
            : OPTION_NONE;
      }
    };
  }

  /**
   * Converts what a {@code scan} function returns - {@code SOME (value, rest)}
   * or {@code NONE} - into {@code SOME value} or {@code NONE}, discarding the
   * rest of the stream.
   */
  private static List value(List option) {
    return option.size() < 2
        ? OPTION_NONE
        : optionSome(((List) option.get(1)).get(0));
  }

  /**
   * Scans a value from the characters of a string, and returns it without the
   * rest of the stream.
   *
   * <p>The Standard Basis defines each {@code fromString} this way, as {@code
   * StringCvt.scanString scan}; characters after the value are ignored.
   */
  static List scanString(
      Applicable2<List, Applicable1<List, Object>, Object> scan, String s) {
    return value(scan.apply(stringReader(s), 0));
  }

  /** As {@link #scanString}, for a {@code scan} that takes a radix. */
  static List scanString(
      Applicable3<List, List, Applicable1<List, Object>, Object> scan,
      Radix radix,
      String s) {
    final List radixValue = ImmutableList.of(radix.name());
    return value(scan.apply(radixValue, stringReader(s), 0));
  }

  /** @see BuiltIn#STRING_CVT_SKIP_WS */
  private static final Applicable STRING_CVT_SKIP_WS =
      new BaseApplicable2<Object, Applicable1<List, Object>, Object>(
          BuiltIn.STRING_CVT_SKIP_WS) {
        @Override
        public Object apply(Applicable1<List, Object> rdr, Object src) {
          return splitl(c -> Character.isWhitespace(c), rdr, src).get(1);
        }
      };

  /** @see BuiltIn#STRING_CVT_SPLITL */
  private static final Applicable STRING_CVT_SPLITL =
      new BaseApplicable3<
          List,
          Applicable1<Boolean, Character>,
          Applicable1<List, Object>,
          Object>(BuiltIn.STRING_CVT_SPLITL) {
        @Override
        public List apply(
            Applicable1<Boolean, Character> p,
            Applicable1<List, Object> rdr,
            Object src) {
          return splitl(p, rdr, src);
        }
      };

  /** @see BuiltIn#STRING_CVT_TAKEL */
  private static final Applicable STRING_CVT_TAKEL =
      new BaseApplicable3<
          String,
          Applicable1<Boolean, Character>,
          Applicable1<List, Object>,
          Object>(BuiltIn.STRING_CVT_TAKEL) {
        @Override
        public String apply(
            Applicable1<Boolean, Character> p,
            Applicable1<List, Object> rdr,
            Object src) {
          return (String) splitl(p, rdr, src).get(0);
        }
      };

  /** @see BuiltIn#STRING_EXPLODE */
  private static final Applicable1 STRING_EXPLODE =
      new BaseApplicable1<List, String>(BuiltIn.STRING_EXPLODE) {
        @Override
        public List apply(String s) {
          return MapList.of(s.length(), s::charAt);
        }
      };

  /** @see BuiltIn#STRING_EXTRACT */
  private static final Applicable STRING_EXTRACT = new StringExtract(Pos.ZERO);

  /** Implements {@link #STRING_EXTRACT}. */
  private static class StringExtract
      extends BasePositionedApplicable3<String, String, Integer, List> {
    StringExtract(Pos pos) {
      super(BuiltIn.STRING_EXTRACT, pos);
    }

    @Override
    public StringExtract withPos(Pos pos) {
      return new StringExtract(pos);
    }

    @Override
    public String apply(String s, Integer i, List jOpt) {
      if (i < 0) {
        throw new MorelRuntimeException(BuiltInExn.SUBSCRIPT, pos);
      }
      if (jOpt.size() == 2) {
        final int j = (Integer) jOpt.get(1);
        if (j < 0 || i + j > s.length()) {
          throw new MorelRuntimeException(BuiltInExn.SUBSCRIPT, pos);
        }
        return s.substring(i, i + j);
      } else {
        if (i > s.length()) {
          throw new MorelRuntimeException(BuiltInExn.SUBSCRIPT, pos);
        }
        return s.substring(i);
      }
    }
  }

  /** @see BuiltIn#STRING_FIELDS */
  private static final Applicable2 STRING_FIELDS =
      new StringTokenize(BuiltIn.STRING_FIELDS);

  /** @see BuiltIn#STRING_FROM_CSTRING */
  private static final Applicable STRING_FROM_CSTRING =
      new BaseApplicable1<List, String>(BuiltIn.STRING_FROM_CSTRING) {
        @Override
        public List apply(String s) {
          final Applicable1<List, Object> reader = stringReader(s);
          final CharSource[] source = {new CharSource(reader, 0)};
          final StringBuilder b = new StringBuilder();
          while (source[0].peek() >= 0) {
            // Every character has to be scanned; there is no stopping early
            // and ignoring the rest, as there is in fromString.
            final Character c = scanCChar(source, reader);
            if (c == null) {
              return OPTION_NONE;
            }
            b.append((char) c);
          }
          return optionSome(b.toString());
        }
      };

  /** @see BuiltIn#STRING_FROM_STRING */
  private static final Applicable STRING_FROM_STRING =
      new BaseApplicable1<List, String>(BuiltIn.STRING_FROM_STRING) {
        @Override
        public List apply(String s) {
          return scanString(STRING_SCAN, s);
        }
      };

  /** @see BuiltIn#STRING_IMPLODE */
  private static final Applicable STRING_IMPLODE =
      new BaseApplicable1<String, List<Character>>(BuiltIn.STRING_IMPLODE) {
        @Override
        public String apply(List<Character> characters) {
          // Note: In theory this function should raise Size, but it is not
          // possible in practice because List.size() is never larger than
          // Integer.MAX_VALUE.
          return String.valueOf(Chars.toArray(characters));
        }
      };

  /** @see BuiltIn#STRING_IS_PREFIX */
  private static final Applicable2 STRING_IS_PREFIX =
      new BaseApplicable2<Boolean, String, String>(BuiltIn.STRING_IS_PREFIX) {
        @Override
        public Boolean apply(String s, String s2) {
          return s2.startsWith(s);
        }
      };

  /** @see BuiltIn#STRING_IS_SUBSTRING */
  private static final Applicable2 STRING_IS_SUBSTRING =
      new BaseApplicable2<Boolean, String, String>(
          BuiltIn.STRING_IS_SUBSTRING) {
        @Override
        public Boolean apply(String s, String s2) {
          return s2.contains(s);
        }
      };

  /** @see BuiltIn#STRING_IS_SUFFIX */
  private static final Applicable2 STRING_IS_SUFFIX =
      new BaseApplicable2<Boolean, String, String>(BuiltIn.STRING_IS_SUFFIX) {
        @Override
        public Boolean apply(String s, String s2) {
          return s2.endsWith(s);
        }
      };

  /** @see BuiltIn#STRING_MAP */
  private static final Applicable2 STRING_MAP =
      new BaseApplicable2<String, Applicable1<Character, Character>, String>(
          BuiltIn.STRING_MAP) {
        @Override
        public String apply(Applicable1<Character, Character> f, String s) {
          final StringBuilder buf = new StringBuilder();
          for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            final char c2 = f.apply(c);
            buf.append(c2);
          }
          return buf.toString();
        }
      };

  /** @see BuiltIn#STRING_MAX_SIZE */
  private static final Integer STRING_MAX_SIZE = Integer.MAX_VALUE;

  /** @see BuiltIn#STRING_OP_CARET */
  private static final Applicable2 STRING_OP_CARET =
      new BaseApplicable2<String, String, String>(BuiltIn.STRING_OP_CARET) {
        @Override
        public String apply(String a0, String a1) {
          return a0 + a1;
        }
      };

  /** @see BuiltIn#STRING_OP_EQ */
  private static final Applicable2 STRING_OP_EQ =
      new BaseApplicable2<Boolean, String, String>(BuiltIn.STRING_OP_EQ) {
        @Override
        public Boolean apply(String a0, String a1) {
          return a0.equals(a1);
        }
      };

  /** @see BuiltIn#STRING_OP_GE */
  private static final Applicable2 STRING_OP_GE =
      new BaseApplicable2<Boolean, String, String>(BuiltIn.STRING_OP_GE) {
        @Override
        public Boolean apply(String a0, String a1) {
          return a0.compareTo(a1) >= 0;
        }
      };

  /** @see BuiltIn#STRING_OP_GT */
  private static final Applicable2 STRING_OP_GT =
      new BaseApplicable2<Boolean, String, String>(BuiltIn.STRING_OP_GT) {
        @Override
        public Boolean apply(String a0, String a1) {
          return a0.compareTo(a1) > 0;
        }
      };

  /** @see BuiltIn#STRING_OP_LE */
  private static final Applicable2 STRING_OP_LE =
      new BaseApplicable2<Boolean, String, String>(BuiltIn.STRING_OP_LE) {
        @Override
        public Boolean apply(String a0, String a1) {
          return a0.compareTo(a1) <= 0;
        }
      };

  /** @see BuiltIn#STRING_OP_LT */
  private static final Applicable2 STRING_OP_LT =
      new BaseApplicable2<Boolean, String, String>(BuiltIn.STRING_OP_LT) {
        @Override
        public Boolean apply(String a0, String a1) {
          return a0.compareTo(a1) < 0;
        }
      };

  /** @see BuiltIn#STRING_OP_NE */
  private static final Applicable2 STRING_OP_NE =
      new BaseApplicable2<Boolean, String, String>(BuiltIn.STRING_OP_NE) {
        @Override
        public Boolean apply(String a0, String a1) {
          return !a0.equals(a1);
        }
      };

  /** @see BuiltIn#STRING_SCAN */
  private static final BaseApplicable2<List, Applicable1<List, Object>, Object>
      STRING_SCAN =
          new BaseApplicable2<List, Applicable1<List, Object>, Object>(
              BuiltIn.STRING_SCAN) {
            @Override
            public List apply(Applicable1<List, Object> reader, Object stream) {
              final CharSource[] source = {new CharSource(reader, stream)};
              final StringBuilder b = new StringBuilder();
              for (; ; ) {
                // An escaped formatting sequence stands for nothing, and is
                // consumed even if what follows it cannot be scanned.
                skipGaps(source, reader);
                final Character c = scanChar(source, reader, true);
                if (c == null) {
                  break;
                }
                b.append((char) c);
              }
              if (b.length() == 0 && source[0].peek() >= 0) {
                // We scanned nothing, and it was not because the stream ended.
                return OPTION_NONE;
              }
              return optionSome(
                  ImmutableList.of(b.toString(), source[0].stream()));
            }
          };

  /** @see BuiltIn#STRING_SIZE */
  private static final Applicable1 STRING_SIZE =
      new BaseApplicable1<Integer, String>(BuiltIn.STRING_SIZE) {
        @Override
        public Integer apply(String s) {
          return s.length();
        }
      };

  /** @see BuiltIn#STRING_STR */
  private static final Applicable STRING_STR =
      new BaseApplicable1<String, Character>(BuiltIn.STRING_STR) {
        @Override
        public String apply(Character character) {
          return character + "";
        }
      };

  /** @see BuiltIn#STRING_SUB */
  private static final Applicable2 STRING_SUB = new StringSub(Pos.ZERO);

  /** Implements {@link #STRING_SUB}. */
  private static class StringSub
      extends BasePositionedApplicable2<Character, String, Integer> {
    StringSub(Pos pos) {
      super(BuiltIn.STRING_SUB, pos);
    }

    @Override
    public Character apply(String s, Integer i) {
      if (i < 0 || i >= s.length()) {
        throw new MorelRuntimeException(BuiltInExn.SUBSCRIPT, pos);
      }
      return s.charAt(i);
    }

    public StringSub withPos(Pos pos) {
      return new StringSub(pos);
    }
  }

  /** @see BuiltIn#STRING_SUBSTRING */
  private static final Applicable3 STRING_SUBSTRING =
      new StringSubstring(Pos.ZERO);

  /** Implements {@link #STRING_SUBSTRING}. */
  private static class StringSubstring
      extends BasePositionedApplicable3<String, String, Integer, Integer> {
    StringSubstring(Pos pos) {
      super(BuiltIn.STRING_SUBSTRING, pos);
    }

    @Override
    public StringSubstring withPos(Pos pos) {
      return new StringSubstring(pos);
    }

    @Override
    public String apply(String s, Integer i, Integer j) {
      if (i < 0 || j < 0 || i + j > s.length()) {
        throw new MorelRuntimeException(BuiltInExn.SUBSCRIPT, pos);
      }
      return s.substring(i, i + j);
    }
  }

  /** @see BuiltIn#STRING_TO_CSTRING */
  private static final Applicable STRING_TO_CSTRING =
      new BaseApplicable1<String, String>(BuiltIn.STRING_TO_CSTRING) {
        @Override
        public String apply(String s) {
          final StringBuilder b = new StringBuilder();
          for (int i = 0; i < s.length(); i++) {
            b.append(charToCString(s.charAt(i)));
          }
          return b.toString();
        }
      };

  /** @see BuiltIn#STRING_TO_STRING */
  private static final Applicable STRING_TO_STRING =
      new BaseApplicable1<String, String>(BuiltIn.STRING_TO_STRING) {
        @Override
        public String apply(String s) {
          return Parsers.stringToString(s);
        }
      };

  /** @see BuiltIn#STRING_TOKENS */
  private static final Applicable2 STRING_TOKENS =
      new StringTokenize(BuiltIn.STRING_TOKENS);

  /** Implements {@link #STRING_FIELDS} and {@link #STRING_TOKENS}. */
  private static class StringTokenize
      extends BaseApplicable2<
          List<String>, Applicable1<Boolean, Character>, String> {
    StringTokenize(BuiltIn builtIn) {
      super(builtIn);
    }

    @Override
    public List<String> apply(Applicable1<Boolean, Character> f, String s) {
      List<String> result = new ArrayList<>();
      int h = 0;
      for (int i = 0; i < s.length(); i++) {
        char c = s.charAt(i);
        if (f.apply(c)) {
          if (builtIn == BuiltIn.STRING_FIELDS || i > h) {
            // String.tokens only adds fields if they are non-empty.
            result.add(s.substring(h, i));
          }
          h = i + 1;
        }
      }
      if (builtIn == BuiltIn.STRING_FIELDS || s.length() > h) {
        // String.tokens only adds fields if they are non-empty.
        result.add(s.substring(h));
      }
      return result;
    }
  }

  /** @see BuiltIn#STRING_TRANSLATE */
  private static final Applicable2 STRING_TRANSLATE =
      new BaseApplicable2<String, Applicable1<String, Character>, String>(
          BuiltIn.STRING_TRANSLATE) {
        @Override
        public String apply(Applicable1<String, Character> f, String s) {
          final StringBuilder buf = new StringBuilder();
          for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            final String c2 = f.apply(c);
            buf.append(c2);
          }
          return buf.toString();
        }
      };

  /** Implementation of {@link Applicable} that has a single char argument. */
  private static class CharPredicate
      extends BaseApplicable1<Boolean, Character> {
    private final Predicate<Character> predicate;

    CharPredicate(BuiltIn builtIn, Predicate<Character> predicate) {
      super(builtIn);
      this.predicate = predicate;
    }

    @Override
    public Boolean apply(Character c) {
      return predicate.test(c);
    }
  }
}

// End StringCodes.java
