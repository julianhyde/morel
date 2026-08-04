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
package net.hydromatic.morel.compile;

import static com.google.common.base.Verify.verify;

import java.util.concurrent.atomic.AtomicBoolean;
import net.hydromatic.morel.ast.AstNode;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.ast.Visitor;

/**
 * Validates that calls to {@code ordinal} occur only in a {@link Core.Yield}.
 *
 * <p>A "yield" is evaluated exactly once per input row, and the increment is
 * attached to the step, not to the call. So a "yield" may contain any number of
 * calls -- they all read the one counter, and all see the same value -- but a
 * call anywhere else would read a counter that nothing advances. A "where"
 * condition, an "order" key, a "group" key, an aggregate argument, and a "take"
 * or "skip" count all read a field that an earlier "yield" materialized.
 *
 * <p>The rule is not enforced by construction, because a call is an ordinary
 * expression that a pass could hoist out of the step it belongs to, which is
 * why it is checked. Merging, inlining or duplicating expressions within a
 * "yield" is safe and needs no check: a second call is a second read of the
 * same counter.
 */
public class OrdinalChecker {
  private OrdinalChecker() {}

  /** Validates a node, throwing if the rule is broken. */
  public static void check(AstNode node) {
    node.accept(
        new Visitor() {
          @Override
          protected void visit(Core.From from) {
            from.steps.forEach(OrdinalChecker::checkStep);
            super.visit(from);
          }
        });
  }

  /**
   * Validates one step. Considers only the calls that belong to this step; a
   * call inside a nested query belongs to a step of that query, and is checked
   * when the walk reaches it.
   */
  private static void checkStep(Core.FromStep step) {
    if (step.op != Op.YIELD) {
      verify(
          !usesOrdinal(step),
          "'ordinal' occurs outside a yield, in %s: %s",
          step.op,
          step);
    }
  }

  /**
   * Returns whether a step calls {@code ordinal}, not descending into a nested
   * query.
   */
  private static boolean usesOrdinal(Core.FromStep step) {
    final AtomicBoolean b = new AtomicBoolean();
    step.accept(
        new Visitor() {
          @Override
          protected void visit(Core.Apply apply) {
            if (apply.isCallTo(BuiltIn.Z_ORDINAL)) {
              b.set(true);
            }
            super.visit(apply);
          }

          @Override
          protected void visit(Core.From from) {
            // A nested query's steps are checked on their own terms.
          }
        });
    return b.get();
  }
}

// End OrdinalChecker.java
