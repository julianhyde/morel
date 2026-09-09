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
 * tree. It runs under {@code assert}, which is easy to disable by accident and
 * impossible to notice: a shadow that has stopped running looks exactly like
 * one that finds nothing.
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
   * Tests that compiling an unbounded query grounds it through its tree.
   *
   * <p>There is one grounding engine now, and the counter is the only evidence
   * that {@link net.hydromatic.morel.compile.Expander} ran: a query it declines
   * is handed back unchanged and {@code SuchThatShuttle} grounds it later,
   * which looks the same from here as a front end that was never called.
   *
   * <p>Hence the query. {@code from i where i elem [1, 2, 3]} does not do:
   * making {@code Expander.expandFrom} return its argument leaves every script
   * green except six lines of {@code such-that.smli}, all of them a {@code
   * case} over a constructor, so those six are what the front end alone grounds
   * and one of them is what this asserts on.
   */
  @Test
  void testGroundingShadowRuns() {
    final int before = RelShadow.groundedViaTreeCount();
    Ml.ml(
            "from e where (case e of INL n => n >= 5 andalso n <= 8"
                + " | _ => false)")
        .assertEval();
    assertThat(RelShadow.groundedViaTreeCount(), greaterThan(before));
  }
}

// End GroundingShadowTest.java
