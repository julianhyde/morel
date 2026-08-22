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
    final int before = RelShadow.translatedCount();
    Ml.ml("from i in [1, 2, 3] where i > 1").assertEval();
    assertThat(RelShadow.translatedCount(), greaterThan(before));
  }

  /**
   * Tests that compiling an unbounded query grounds it both ways and compares
   * the verdicts.
   */
  @Test
  void testGroundingShadowRuns() {
    final int before =
        RelShadow.groundingAgreedCount() + RelShadow.groundingDivergedCount();
    Ml.ml("from i where i elem [1, 2, 3]").assertEval();
    assertThat(
        RelShadow.groundingAgreedCount() + RelShadow.groundingDivergedCount(),
        greaterThan(before));
  }
}

// End GroundingShadowTest.java
