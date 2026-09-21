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
package net.hydromatic.morel;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.util.Comparators;
import org.junit.jupiter.api.Test;

/** Tests for {@link Comparators}. */
public class ComparatorTest {
  /**
   * Sorts {@code names}, shuffled, with {@code comparator}, and checks that the
   * result is {@code names} in the order given. Also checks that every pair of
   * names compares the same way in both directions.
   */
  private static void checkSorts(
      Comparator<String> comparator, String... names) {
    final List<String> expected = Arrays.asList(names);
    final List<String> list = new ArrayList<>(expected);
    Collections.shuffle(list, new Random(0));
    list.sort(comparator);
    assertThat(list, is(expected));
    for (String a : expected) {
      for (String b : expected) {
        final int c = comparator.compare(a, b);
        assertThat(
            a + " vs " + b,
            Integer.signum(c),
            is(-Integer.signum(comparator.compare(b, a))));
        assertThat(a + " vs " + b, c == 0, is(a.equals(b)));
      }
    }
  }

  /** Record field names: numbers first, in numeric order, then strings. */
  @Test
  void testCompareNames() {
    checkSorts(
        Comparators::compareNames,
        "1",
        "2",
        "22",
        "202",
        "a",
        "a2",
        "a202",
        "a22");
    // "0", a name with a leading zero, a name with a non-digit, and a name of
    // ten digits are not numbers, so they are compared as strings.
    checkSorts(
        Comparators::compareNames,
        "1",
        "999999999",
        "0",
        "01",
        "1000000000",
        "1x",
        "b");
    // The ordering is the one record types use.
    assertThat(
        RecordType.ORDERING.sortedCopy(Arrays.asList("b", "10", "a", "9")),
        is(Arrays.asList("9", "10", "a", "b")));
  }

  /** Generated names: by prefix, then by number, so v$2 precedes v$10. */
  @Test
  void testCompareNumberedNames() {
    checkSorts(Comparators::compareNumberedNames, "v$0", "v$2", "v$10", "v$11");
    // Different prefixes are compared as text; a name without '$' likewise.
    checkSorts(
        Comparators::compareNumberedNames,
        "col$3",
        "col$10",
        "e",
        "g$1",
        "g$2",
        "v",
        "v$1",
        "v$10",
        "w$0");
    // A suffix that is not a number (a leading zero is not a number) comes
    // after the numbered names of its
    // prefix, and such suffixes are compared as text.
    checkSorts(
        Comparators::compareNumberedNames,
        "v$1",
        "v$10",
        "v$",
        "v$00",
        "v$1x",
        "v$9999999999",
        "v$x");
  }
}

// End ComparatorTest.java
