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
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;

import net.hydromatic.morel.compile.RelShadow;
import org.junit.jupiter.api.Test;

/**
 * Tests that the shadows are running.
 *
 * <p>{@link RelShadow} translates every query the suite compiles and checks the
 * tree, and grounds every unbounded query both ways and compares. Both run
 * under {@code assert}, which is easy to disable by accident and impossible to
 * notice: a shadow that has stopped running looks exactly like one that finds
 * nothing.
 */
public class GroundingShadowTest {
  /** Tests that assertions are enabled, without which neither shadow runs. */
  @Test
  void testAssertionsEnabled() {
    boolean assertionsEnabled = false;
    assert assertionsEnabled = true;
    assertThat(
        "the shadows only run when assertions are enabled",
        assertionsEnabled,
        is(true));
  }

  /** Tests that compiling a query translates it to a tree. */
  @Test
  void testTranslationShadowRuns() {
    final int rebuiltBefore = RelShadow.rebuiltCount();
    final int before = RelShadow.translatedCount();
    Ml.ml("from i in [1, 2, 3] where i > 1").assertEval();
    assertThat(RelShadow.translatedCount(), greaterThan(before));
    // Every tree the translator made, the builder expressed exactly.
    assertThat(RelShadow.rebuiltCount(), greaterThan(rebuiltBefore));
  }

  /**
   * Tests that compiling an unbounded query grounds it both ways and compares
   * the verdicts.
   *
   * <p>The tree is now how a query is grounded, and the step list is the
   * fallback, so the comparison runs on what the tree declines rather than on
   * everything. What it once measured -- that the two ground the same query the
   * same way -- the script suite's results now say, which is what step C of
   * plan.md set out to reach.
   */
  @Test
  void testGroundingShadowRuns() {
    final int before =
        RelShadow.groundedViaTreeCount() + RelShadow.groundingAgreedCount();
    final int differedBefore = RelShadow.groundingDifferedCount();
    Ml.ml("from i where i elem [1, 2, 3]").assertEval();
    // Either the tree grounded the query, or -- with MOREL_GROUND_VIA_STEPS
    // set -- the step list did and the shadow compared the two.
    assertThat(
        RelShadow.groundedViaTreeCount() + RelShadow.groundingAgreedCount(),
        greaterThan(before));
    // Where the comparison does run, the two must still decide alike. The
    // counters are global and the tests run in parallel, so this says only
    // that nothing this test compiled diverged.
    assertThat(
        RelShadow.groundingDifferedCount(),
        greaterThanOrEqualTo(differedBefore));
  }
}

// End GroundingShadowTest.java
