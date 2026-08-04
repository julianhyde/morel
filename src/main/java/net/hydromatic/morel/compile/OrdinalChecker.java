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
import static net.hydromatic.morel.util.Ord.forEachIndexed;

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
            forEachIndexed(from.steps, (step, i) -> checkStep(step, i == 0));
            super.visit(from);
          }
        });
  }

  /** Validates one step. */
  private static void checkStep(Core.FromStep step, boolean first) {
    if (step.op != Op.YIELD) {
      verify(
          !usesOrdinal(step, first),
          "'ordinal' occurs outside a yield, in %s: %s",
          step.op,
          step);
    }
  }

  /**
   * Returns whether a step calls {@code ordinal}.
   *
   * <p>Counts the calls that belong to this step, which is not the same as the
   * calls that occur within it. A call belongs to the innermost step whose
   * input row it is positioned within, so:
   *
   * <ul>
   *   <li>A call inside a nested query counts here only if it is in the
   *       expression the nested query scans, because that expression is
   *       evaluated once per row of <i>this</i> step. Calls in the nested
   *       query's later steps belong to those steps.
   *   <li>Conversely, if this step is the first of its query, a call in the
   *       expression it scans belongs to the enclosing step, and does not count
   *       here. (In a query that is not nested, such a call is rejected
   *       earlier, by {@code TypeResolver}.)
   * </ul>
   *
   * <p>This is the same rule that {@code Resolver.FromResolver.usesOrdinal}
   * applies to the AST, when deciding which step must materialize the field.
   * The two must agree: this one validates what that one produces.
   */
  private static boolean usesOrdinal(Core.FromStep step, boolean first) {
    final AtomicBoolean b = new AtomicBoolean();
    final Visitor visitor =
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
            if (!from.steps.isEmpty()
                && from.steps.get(0) instanceof Core.Scan) {
              ((Core.Scan) from.steps.get(0)).exp.accept(this);
            }
          }
        };
    if (first && step instanceof Core.Scan) {
      ((Core.Scan) step).condition.accept(visitor);
    } else {
      step.accept(visitor);
    }
    return b.get();
  }
}

// End OrdinalChecker.java
