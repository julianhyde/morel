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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasToString;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.function.IntPredicate;
import java.util.stream.Collectors;
import org.hamcrest.Matcher;
import org.junit.jupiter.api.Test;

/**
 * An algorithm to partition an array into regions where a predicate evaluates
 * to true and false.
 *
 * <p>The goal is to work in place (i.e. not requiring a copy of the array or
 * temporary storage) and to be stable (i.e. to preserve the relative order of
 * elements) but the algorithm fails to be stable. I'm not sure that it is
 * possible to meet the goal.
 */
public class PartitionTest {
  @Test
  void testPartitionArray() {
    IntPredicate even = j -> j % 2 == 0;
    IntPredicate odd = j -> j % 2 == 1;
    IntPredicate never = j -> false;
    IntPredicate always = j -> true;
    String all15 = "[11, 8, 3, 13, 4, 1, 9, 14, 10, 7, 0, 5, 12, 6, 2]";
    String odd15 = "[11, 3, 13, 1, 5, 9, 7]";
    String even15 = "[2, 8, 6, 12, 4, 0, 10, 14]";
    checkPartition(
        15, even, hasToString(all15), hasToString(odd15), hasToString(even15));
    checkPartition(
        15, odd, hasToString(all15), hasToString(even15), hasToString(odd15));
    checkPartition(
        1, even, hasToString("[0]"), hasToString("[]"), hasToString("[0]"));
    checkPartition(0, even, empty(), empty(), empty());
  }

  private static void checkPartition(
      int size,
      IntPredicate intPredicate,
      Matcher<Collection<?>> initialMatcher,
      Matcher<Collection<?>> trueMatcher,
      Matcher<Collection<?>> falseMatcher) {
    long seed = 123L;
    final Random r = new Random(seed);
    final int[] ints = new int[size];
    for (int i = 0; i < ints.length; i++) {
      ints[i] = i;
    }
    // Shuffle the array.
    for (int i = ints.length - 1; i > 0; i--) {
      int j = r.nextInt(i + 1);
      // Simple swap
      swap(ints, i, j);
    }
    final List<Integer> initialList =
        Arrays.stream(ints).boxed().collect(Collectors.toList());
    assertThat(initialList, initialMatcher);
    final int i = partition(ints, intPredicate).i;
    final List<Integer> finalList =
        Arrays.stream(ints).boxed().collect(Collectors.toList());
    assertThat(finalList.subList(i, finalList.size()), trueMatcher);
    assertThat(finalList.subList(0, i), falseMatcher);
  }

  static IntPair partition(int[] ints, IntPredicate predicate) {
    if (ints.length == 0) {
      return new IntPair(0, 0); // Nothing to partition
    }
    int i = 0;
    while (predicate.test(ints[i])) {
      ++i;
      if (i == ints.length) {
        return new IntPair(0, i);
      }
    }

    int j = ints.length;
    outer:
    for (; ; ) {
      // ints[i] just evaluated to false.
      // Write its entry into ints[j], and figure out where to put the value
      // that was there before.
      do {
        int c = ints[j];
        ints[j] = ints[i];
        --j;
        if (j < 0) {
          // (This will never happen)
          j = ints.length - 1; // Wrap around
        }
        if (i == j) {
          // We have processed all elements. We're done.
          ints[j] = c;
          break outer;
        }
      } while (predicate.test(ints[j]));

      // ints[j] just evaluated to true.
      // Going up
      // Evaluated to false.
      ints[j] = ints[i];
      --j;
      if (j == -1) {
        j = ints.length - 1; // Wrap around
      }
      if (i == j) {
        break outer;
      }
    }
    // Reverse the order of the upper part (which contains
    // the elements that do not match the predicate).
    IntPair result = new IntPair(i, j);
    for (int k = ints.length - 1; i < k; i++, k--) {
      swap(ints, i, k);
    }
    // Return the size of the lower part.
    return result;
  }

  private static void swap(int[] ints, int i, int j) {
    int tmp = ints[i];
    ints[i] = ints[j];
    ints[j] = tmp;
  }

  static class IntPair {
    final int i;
    final int j;

    IntPair(int i, int j) {
      this.i = i;
      this.j = j;
    }
  }
}
