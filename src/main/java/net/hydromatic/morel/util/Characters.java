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
package net.hydromatic.morel.util;

/**
 * Character utilities.
 *
 * <p>The predicates classify characters in the "C" locale, matching the SML
 * Basis {@code Char.is*} functions, and are ASCII-only.
 */
public class Characters {
  private Characters() {}

  /** Returns whether {@code c} is a letter. */
  public static boolean isAlpha(char c) {
    return isUpper(c) || isLower(c);
  }

  /** Returns whether {@code c} is a letter or a decimal digit. */
  public static boolean isAlphaNum(char c) {
    return isAlpha(c) || isDigit(c);
  }

  /** Returns whether {@code c} is a 7-bit ASCII character. */
  public static boolean isAscii(char c) {
    return c <= 127;
  }

  /** Returns whether {@code c} is a control character. */
  public static boolean isCntrl(char c) {
    return isAscii(c) && !isPrint(c);
  }

  /** Returns whether {@code c} is a decimal digit. */
  public static boolean isDigit(char c) {
    return '0' <= c && c <= '9';
  }

  /**
   * Returns whether {@code c} is a visible (printable, non-space) character.
   */
  public static boolean isGraph(char c) {
    return c >= '!' && c <= '~';
  }

  /** Returns whether {@code c} is a hexadecimal digit (0-9, a-f, A-F). */
  public static boolean isHexDigit(char c) {
    return isDigit(c) || 'a' <= c && c <= 'f' || 'A' <= c && c <= 'F';
  }

  /** Returns whether {@code c} is a lowercase letter. */
  public static boolean isLower(char c) {
    return 'a' <= c && c <= 'z';
  }

  /** Returns whether {@code c} is an octal digit (0-7). */
  public static boolean isOctDigit(char c) {
    return '0' <= c && c <= '7';
  }

  /** Returns whether {@code c} is a printable character (including space). */
  public static boolean isPrint(char c) {
    return isGraph(c) || c == ' ';
  }

  /** Returns whether {@code c} is a punctuation character. */
  public static boolean isPunct(char c) {
    return isGraph(c) && !isAlphaNum(c);
  }

  /** Returns whether {@code c} is a whitespace character. */
  public static boolean isSpace(char c) {
    return c >= '\t' && c <= '\r' || c == ' ';
  }

  /** Returns whether {@code c} is an uppercase letter. */
  public static boolean isUpper(char c) {
    return 'A' <= c && c <= 'Z';
  }

  /**
   * Scans a Morel numeric literal starting at {@code start} and returns the
   * index after it (or {@code start} if there is none).
   *
   * <p>Handles integer, word ({@code 0w7}, {@code 0wx1F}), real ({@code 1.5})
   * and scientific ({@code 1e~7}) literals. If {@code sign} is true and the
   * literal starts with {@code ~}, the sign is included; word literals never
   * have a sign.
   *
   * <p>Decimal digits use {@link Character#isDigit} to match the callers'
   * dispatch, so that scanning always makes progress.
   */
  public static int scanNumber(
      CharSequence s, int start, int end, boolean sign) {
    int i = start;
    if (sign && i < end && s.charAt(i) == '~') {
      i++;
    }
    // Word literal: 0w<digits> or 0wx<hex>.
    if (i == start
        && i + 1 < end
        && s.charAt(i) == '0'
        && s.charAt(i + 1) == 'w') {
      if (i + 2 < end && (s.charAt(i + 2) == 'x' || s.charAt(i + 2) == 'X')) {
        int k = i + 3;
        while (k < end && isHexDigit(s.charAt(k))) {
          k++;
        }
        if (k > i + 3) {
          return k;
        }
      } else {
        int k = i + 2;
        while (k < end && Character.isDigit(s.charAt(k))) {
          k++;
        }
        if (k > i + 2) {
          return k;
        }
      }
    }
    // Integer part.
    while (i < end && Character.isDigit(s.charAt(i))) {
      i++;
    }
    // Fractional part: '.' followed by at least one digit.
    if (i + 1 < end
        && s.charAt(i) == '.'
        && Character.isDigit(s.charAt(i + 1))) {
      i += 2;
      while (i < end && Character.isDigit(s.charAt(i))) {
        i++;
      }
    }
    // Exponent: [eE] ~? digits.
    if (i < end && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
      int j = i + 1;
      if (j < end && s.charAt(j) == '~') {
        j++;
      }
      if (j < end && Character.isDigit(s.charAt(j))) {
        i = j + 1;
        while (i < end && Character.isDigit(s.charAt(i))) {
          i++;
        }
      }
    }
    return i;
  }
}

// End Characters.java
