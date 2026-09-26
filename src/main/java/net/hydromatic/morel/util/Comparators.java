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

/** Comparison functions for names. */
public class Comparators {
  private Comparators() {}

  /**
   * Compares record field names: integer values numerically, string values
   * lexicographically, and integer values before string values.
   *
   * <p>Thus: 2, 22, 202, a, a2, a202, a22.
   */
  public static int compareNames(String o1, String o2) {
    // A numeric label is a positive integer; "0" is a string.
    final int i1 = parseInt(o1, 0);
    final int i2 = parseInt(o2, 0);
    if (i1 > 0 && i2 > 0) {
      return Integer.compare(i1, i2);
    }
    if (i1 > 0) {
      return -1;
    }
    if (i2 > 0) {
      return 1;
    }
    return o1.compareTo(o2);
  }

  /**
   * Compares generated names such as {@code v$2} and {@code v$10}: by the text
   * before the '$', then by the number after it, so that {@code v$2} comes
   * before {@code v$10}. A name with no '$', or whose suffix is not a number,
   * is compared as text and comes after the numbered names that share its
   * prefix.
   */
  public static int compareNumberedNames(String o1, String o2) {
    final int i = o1.indexOf('$');
    final int j = o2.indexOf('$');
    if (i < 0 || j < 0 || i != j || !o1.regionMatches(0, o2, 0, i)) {
      return o1.compareTo(o2);
    }
    final int i1 = parseInt(o1, i + 1);
    final int i2 = parseInt(o2, j + 1);
    if (i1 < 0 || i2 < 0) {
      // At least one suffix is not a number. Numbered names come first.
      return i1 < 0 && i2 < 0 ? o1.compareTo(o2) : i1 < 0 ? 1 : -1;
    }
    return Integer.compare(i1, i2);
  }

  /**
   * Parses the integer written from position {@code start} to the end of {@code
   * s}; returns -1 if it is not one: if there are no characters, or any is not
   * a digit, or there is a leading zero (other than "0" itself), or there are
   * ten or more digits.
   *
   * <p>This approach is much faster for our purposes than {@link
   * Integer#parseInt(String)}, which has to create and throw an exception if
   * the value is not an integer.
   */
  private static int parseInt(String s, int start) {
    final int length = s.length();
    if (start == length || length - start > 9) {
      // Values of ten or more digits, 1 billion (1,000,000,000) or higher,
      // are not integers here, so the loop below cannot overflow.
      return -1;
    }
    if (s.charAt(start) == '0' && length - start > 1) {
      // "01" and "007" are not integers here.
      return -1;
    }
    int n = 0;
    for (int i = start; i < length; i++) {
      final char c = s.charAt(i);
      if (c < '0' || c > '9') {
        return -1;
      }
      n = n * 10 + (c - '0');
    }
    return n;
  }
}

// End Comparators.java
