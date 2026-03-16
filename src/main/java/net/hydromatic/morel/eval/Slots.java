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
package net.hydromatic.morel.eval;

import java.util.Collection;

/**
 * Utilities for computing {@link Code#maxSlots()} values.
 *
 * <p>Stack slot counts combine in two ways:
 *
 * <ul>
 *   <li><b>Sequential</b> (a node pushes {@code n} slots then runs a child):
 *       {@code n + child.maxSlots()} — already a one-liner at each call site.
 *   <li><b>Parallel</b> (sibling nodes share the same stack space, e.g.
 *       alternative branches or independent argument evaluations): the required
 *       depth is the <em>max</em> over all siblings. This forms a commutative
 *       monoid {@code (Z>=0, max, 0)}, and the {@link #maxOf} helpers reduce a
 *       set of {@link Code} nodes over that monoid.
 * </ul>
 */
public final class Slots {
  private Slots() {}

  /**
   * Returns the max of {@link Code#maxSlots()} over the given codes.
   *
   * <p>Use this when the codes are evaluated in series (one at a time), so
   * their stack usage does not overlap and the maximum depth suffices.
   *
   * @param codes the code nodes
   * @return max slot count
   */
  public static int maxOf(final Code... codes) {
    int max = 0;
    for (Code code : codes) {
      max = Math.max(max, code.maxSlots());
    }
    return max;
  }

  /**
   * Returns the max of {@link Code#maxSlots()} over a collection of codes.
   *
   * <p>Use this when the codes are evaluated in series (one at a time), so
   * their stack usage does not overlap and the maximum depth suffices.
   *
   * @param codes the code nodes
   * @return max slot count
   */
  public static int maxOf(Collection<? extends Code> codes) {
    return maxOf(0, codes);
  }

  /**
   * Returns the max of {@code base} and {@link Code#maxSlots()} over a
   * collection of codes.
   *
   * <p>Use this when a fixed number of slots are always required (the
   * sequential cost {@code base}), plus codes evaluated in series whose stack
   * usage does not overlap with each other (though it may overlap with the base
   * slots).
   *
   * @param base minimum slot count (e.g. {@code n + child.maxSlots()})
   * @param codes additional code nodes evaluated in series
   * @return max slot count
   */
  public static int maxOf(int base, Collection<? extends Code> codes) {
    int max = base;
    for (Code code : codes) {
      max = Math.max(max, code.maxSlots());
    }
    return max;
  }
}

// End Slots.java
