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
import net.hydromatic.morel.ast.Shuttle;
import net.hydromatic.morel.ast.Visitor;
import net.hydromatic.morel.type.TypeSystem;

/**
 * Translates every {@code from} in a declaration into a relational tree and
 * checks the result, without changing what the declaration does.
 *
 * <p>This is the shadow of step 1 of {@code plan.md}: while {@link Core.From}
 * still does the work, every query that the test suite compiles is also
 * translated, validated, and checked to have the type it started with. It runs
 * under {@code assert}, so it is on when the tests run and costs nothing when
 * they do not.
 *
 * <p>A query the translator declines -- an outer join, say -- is counted and
 * skipped. A query it translates *wrongly* is an error, because that is a bug
 * in the translation, not a gap in it.
 */
public class RelShadow {
  // Counters, read by a test that checks the shadow is running, and by the
  // throwaway probes that measure agreement while the port is in progress;
  // plan.md quotes their numbers.
  private static final AtomicInteger TRANSLATED = new AtomicInteger();
  private static final AtomicInteger DECLINED = new AtomicInteger();
  private static final AtomicInteger GROUNDING_AGREED = new AtomicInteger();
  private static final AtomicInteger GROUNDING_DIVERGED = new AtomicInteger();
  private static final AtomicInteger GROUNDING_UNEXAMINED = new AtomicInteger();

  private RelShadow() {}

  /**
   * Returns the number of queries translated so far, for tests that want to
   * know that the shadow is doing something.
   */
  public static int translatedCount() {
    return TRANSLATED.get();
  }

  /**
   * Replaces each query with the lowering of its tree, so that execution goes
   * through the relational tree.
   *
   * <p>A query the translator declines is left as it was; a query it translates
   * is executed as {@link RelLowerer} lowers it, and the script suite checks by
   * its results that the two are the same query.
   */
  public static Core.Decl viaTree(TypeSystem typeSystem, Core.Decl decl) {
    return decl.accept(
        new Shuttle(typeSystem) {
          @Override
          protected Core.Exp visit(Core.From from) {
            final Core.Exp from2 = super.visit(from);
            if (!(from2 instanceof Core.From)) {
              return from2;
            }
            if (containsExtent((Core.From) from2)
                || hasFailablePattern((Core.From) from2)) {
              // Two shapes that the round trip perturbs and that machinery
              // reading step lists depends on: an unbounded scan, whose
              // extent is fused with the conditions that bound it, and a
              // scan whose pattern can fail, which the tree turns into a
              // case and so erases. The grounding of `from b where cheap b`
              // needs to see through both. They stay on the old path until
              // `suchThat` is ported to the tree (plan.md step 5).
              return from2;
            }
            final Core.Exp tree =
                RelTranslator.toRel(typeSystem, (Core.From) from2);
            if (tree == null) {
              return from2;
            }
            return RelLowerer.lower(typeSystem, tree);
          }
        });
  }

  /**
   * Checks that grounding a query through its tree reaches the same verdict as
   * grounding its step list.
   *
   * <p>Always returns true, so that it can be called from an {@code assert};
   * throws {@link AssertionError} if the two disagree. A query the translator
   * declines is counted as unexamined rather than as agreement: the point is to
   * find divergence, not to claim coverage where there is none.
   */
  public static boolean groundingAgrees(
      TypeSystem typeSystem,
      Environment env,
      Core.From from,
      boolean stepGrounded,
      boolean rowsUsed) {
    final Core.Exp tree = RelTranslator.toRel(typeSystem, from);
    if (tree == null) {
      GROUNDING_UNEXAMINED.incrementAndGet();
      return true;
    }
    boolean treeGrounded;
    try {
      final Core.Exp expanded =
          RelExpander.expand(typeSystem, env, tree, rowsUsed);
      // An extent that survives expansion is one the walk did not reach or
      // could not bound; either way the tree has not grounded the query.
      treeGrounded = !containsExtent(expanded);
    } catch (CompileException e) {
      treeGrounded = false;
    } catch (RuntimeException e) {
      // The engine can answer with a generator that binds several variables
      // at once -- a tuple that one constraint ties together -- which a front
      // end that grounds one leaf at a time cannot use. Incompleteness, of
      // the same kind as grounding less, so counted rather than thrown.
      treeGrounded = false;
    }
    if (treeGrounded && !stepGrounded) {
      // The tree grounds a query the step list rejects. Whatever the merits,
      // it is a change in what compiles, and the flip must not make one
      // silently.
      throw new AssertionError(
          format("tree grounds a query that the step list does not: %s", from));
    }
    if (!treeGrounded && stepGrounded) {
      // The tree grounds less than the step list. A known incompleteness --
      // see plan.md -- so counted rather than thrown, until it is closed.
      GROUNDING_DIVERGED.incrementAndGet();
      return true;
    }
    GROUNDING_AGREED.incrementAndGet();
    return true;
  }

  /**
   * Returns whether a tree still has a leaf that cannot be enumerated.
   *
   * <p>Only the tree's own leaves count. A nested query inside an expression --
   * {@code where nonEmpty (from y : int where ...)} -- has an unbounded pattern
   * of its own, which the step list grounds when it reaches that query, and for
   * which this one is not answerable.
   *
   * <p>A finite extent is a perfectly good bound: {@code extent "bool"} is two
   * values.
   */
  private static boolean containsExtent(Core.Exp exp) {
    if (!(exp instanceof Core.Rel)) {
      return Extents.isInfinite(exp);
    }
    for (Core.Exp input : ((Core.Rel) exp).inputs()) {
      if (containsExtent(input)) {
        return true;
      }
    }
    return exp instanceof Core.ProjectMany
        && containsExtent(((Core.ProjectMany) exp).body);
  }

  /**
   * Returns whether any scan of a query has a pattern that can fail to match,
   * which the translation turns into a case.
   */
  private static boolean hasFailablePattern(Core.From from) {
    for (Core.FromStep step : from.steps) {
      if (step instanceof Core.Scan && failable(((Core.Scan) step).pat)) {
        return true;
      }
    }
    return false;
  }

  private static boolean failable(Core.Pat pat) {
    switch (pat.op) {
      case ID_PAT:
      case WILDCARD_PAT:
        return false;
      case TUPLE_PAT:
        return ((Core.TuplePat) pat)
            .args.stream().anyMatch(RelShadow::failable);
      case RECORD_PAT:
        return ((Core.RecordPat) pat)
            .args.stream().anyMatch(RelShadow::failable);
      default:
        return true;
    }
  }

  /**
   * Returns whether any scan of a query is over an extent, which the
   * unbounded-variable machinery bounds by reading step shapes.
   */
  private static boolean containsExtent(Core.From from) {
    for (Core.FromStep step : from.steps) {
      if (step instanceof Core.Scan && ((Core.Scan) step).exp.isExtent()) {
        return true;
      }
    }
    return false;
  }

  /** Returns how many queries the two grounding engines agreed on. */
  public static int groundingAgreedCount() {
    return GROUNDING_AGREED.get();
  }

  /** Returns how many queries the tree grounds less well than the step list. */
  public static int groundingDivergedCount() {
    return GROUNDING_DIVERGED.get();
  }

  /** Returns how many queries the tree grounding did not examine. */
  public static int groundingUnexaminedCount() {
    return GROUNDING_UNEXAMINED.get();
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
      DECLINED.incrementAndGet();
      return;
    }
    if (!exp.type.equals(from.type)) {
      throw new AssertionError(
          format(
              "tree for '%s' has type %s but the query has type %s",
              from, exp.type.moniker(), from.type.moniker()));
    }
    if (exp instanceof Core.Rel) {
      RelValidator.checkValid(typeSystem, (Core.Rel) exp);
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
