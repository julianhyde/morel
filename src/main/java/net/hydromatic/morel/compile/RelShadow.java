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

import static java.lang.String.format;

import java.util.concurrent.atomic.AtomicInteger;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.RelBuilder;
import net.hydromatic.morel.ast.Simplification;
import net.hydromatic.morel.ast.Visitor;
import net.hydromatic.morel.type.TypeSystem;

/**
 * Translates every {@code from} in a declaration into a relational tree and
 * checks the result, without changing what the declaration does.
 *
 * <p>This is the shadow of step 1 of {@code plan.md}: every query that the test
 * suite compiles is translated, validated, and checked to have the type it
 * started with. It runs under {@code assert}, so it is on when the tests run
 * and costs nothing when they do not.
 *
 * <p>A query the translator declines -- an outer join, say -- is counted and
 * skipped. A query it translates *wrongly* is an error, because that is a bug
 * in the translation, not a gap in it.
 *
 * <p>What is left is the check. Routing execution through the translation was
 * this class's other half, and the flip retired it: the resolver returns the
 * tree, and {@code Compiles} lowers it, so the suite's results check the
 * translation without a shadow having to arrange it.
 */
public class RelShadow {
  // Counters, read by a test that checks the shadow is running; plan.md
  // quotes their numbers.
  private static final AtomicInteger TRANSLATED = new AtomicInteger();
  private static final AtomicInteger DECLINED = new AtomicInteger();
  private static final AtomicInteger GROUNDED_VIA_TREE = new AtomicInteger();
  private static final AtomicInteger REBUILT = new AtomicInteger();

  private RelShadow() {}

  /**
   * Checks that {@link RelBuilder} can express a tree exactly.
   *
   * <p>Rebuilt with no simplification, the tree must come back as it went in.
   * That is the precondition for anything depending on the builder -- the
   * resolver, when it builds trees natively -- and it is worth asserting on
   * every query rather than on the handful a unit test can write by hand,
   * because the shapes that break a builder are the ones nobody thinks to
   * write: an atomizing yield, an outer join whose absent side has several
   * binders, a set operator over three inputs.
   */
  private static void checkBuildable(TypeSystem typeSystem, Core.Exp tree) {
    final Core.Exp rebuilt;
    try {
      rebuilt = RelBuilder.rebuild(typeSystem, tree, Simplification.none());
    } catch (RuntimeException e) {
      throw new AssertionError("builder cannot express: " + tree, e);
    }
    if (!describe(rebuilt).equals(describe(tree))) {
      throw new AssertionError(
          format(
              "builder rebuilt a different tree%nfrom: %s%n  to: %s",
              describe(tree), describe(rebuilt)));
    }
    REBUILT.incrementAndGet();
  }

  private static String describe(Core.Exp exp) {
    return exp instanceof Core.Rel ? ((Core.Rel) exp).describe() : exp + "\n";
  }

  /** Returns how many trees the builder was asked to express. */
  public static int rebuiltCount() {
    return REBUILT.get();
  }

  /**
   * Returns the number of queries translated so far, for tests that want to
   * know that the shadow is doing something.
   */
  public static int translatedCount() {
    return TRANSLATED.get();
  }

  /** Counts a query that the tree grounded, and so carried to a plan. */
  public static void groundedViaTree() {
    GROUNDED_VIA_TREE.incrementAndGet();
  }

  /** Returns how many queries the tree grounded. */
  public static int groundedViaTreeCount() {
    return GROUNDED_VIA_TREE.get();
  }

  /** Returns the number of queries the translator declined so far. */
  public static int declinedCount() {
    return DECLINED.get();
  }

  /**
   * Translates and checks every {@code from} in a declaration.
   *
   * <p>Always returns true, so that it can be called from an {@code assert}
   * statement; throws {@link AssertionError} if a translation is wrong.
   */
  public static boolean check(TypeSystem typeSystem, Core.Decl decl) {
    decl.accept(
        new Visitor() {
          @Override
          protected void visit(Core.From from) {
            super.visit(from);
            check(typeSystem, from);
          }
        });
    return true;
  }

  private static void check(TypeSystem typeSystem, Core.From from) {
    final Core.Exp exp;
    try {
      exp = RelTranslator.toRel(typeSystem, from);
    } catch (RuntimeException e) {
      throw new AssertionError("cannot translate to a tree: " + from, e);
    }
    if (exp == null) {
      // Step 1 landed with no declines and the invariant has held since: a
      // query the translator turns down is a query the flip cannot carry.
      // Asserted here rather than counted, because a count shared by tests
      // that run in parallel cannot be compared before and after.
      throw new AssertionError("translator declined: " + from);
    }
    if (!exp.type.equals(from.type)) {
      throw new AssertionError(
          format(
              "tree for '%s' has type %s but the query has type %s",
              from, exp.type.moniker(), from.type.moniker()));
    }
    if (exp instanceof Core.Rel) {
      RelValidator.checkValid(typeSystem, (Core.Rel) exp);
      checkBuildable(typeSystem, exp);
    }
    final Core.Exp lowered;
    try {
      lowered = RelLowerer.lower(typeSystem, exp);
    } catch (RuntimeException e) {
      throw new AssertionError("cannot lower the tree for: " + from, e);
    }
    if (!lowered.type.equals(from.type)) {
      throw new AssertionError(
          format(
              "lowered tree for '%s' has type %s but the query has type %s",
              from, lowered.type.moniker(), from.type.moniker()));
    }
    TRANSLATED.incrementAndGet();
  }
}

// End RelShadow.java
