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

import net.hydromatic.morel.ast.AstNode;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.ast.Visitor;

/**
 * Validates that calls to {@code ordinal} occur only where they can be
 * evaluated exactly once per input row.
 *
 * <p>Two rules:
 *
 * <ol>
 *   <li>A call may occur only in the expression of a {@link Core.Yield}. A
 *       "where" condition, an "order" key, a "group" key, an aggregate
 *       argument, a "take" or "skip" count all read a field that an earlier
 *       "yield" materialized.
 *   <li>At most one call per {@code Yield}. One call, one increment, one field
 *       per row.
 * </ol>
 *
 * <p>The rules are what make the ordinal a field: exactly one step produces it,
 * exactly once per row, and everything downstream reads the binding. They are
 * not enforced by construction -- a call is an ordinary expression, so a pass
 * that hoists, duplicates, merges or inlines a yield's expression could break
 * them -- which is why they are checked.
 */
public class OrdinalChecker {
  private OrdinalChecker() {}

  /** Validates a node, throwing if either rule is broken. */
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
   * Validates one step. Counts only the calls that belong to this step; a call
   * inside a nested query belongs to a step of that query, and is checked when
   * the walk reaches it.
   */
  private static void checkStep(Core.FromStep step) {
    final int count = ordinalCount(step);
    if (step.op == Op.YIELD) {
      verify(
          count <= 1,
          "'ordinal' occurs %s times in one yield: %s",
          count,
          step);
    } else {
      verify(
          count == 0,
          "'ordinal' occurs outside a yield, in %s: %s",
          step.op,
          step);
    }
  }

  /**
   * Returns how many times a step calls {@code ordinal}, not descending into a
   * nested query.
   */
  private static int ordinalCount(Core.FromStep step) {
    final int[] count = {0};
    step.accept(
        new Visitor() {
          @Override
          protected void visit(Core.Apply apply) {
            if (apply.isCallTo(BuiltIn.Z_ORDINAL)) {
              ++count[0];
            }
            super.visit(apply);
          }

          @Override
          protected void visit(Core.From from) {
            // A nested query's steps are checked on their own terms.
          }
        });
    return count[0];
  }
}

// End OrdinalChecker.java
