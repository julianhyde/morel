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
import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.RelBuilder;
import net.hydromatic.morel.ast.Shuttle;
import net.hydromatic.morel.ast.Simplification;
import net.hydromatic.morel.ast.Visitor;
import net.hydromatic.morel.type.TypeSystem;
import org.jspecify.annotations.Nullable;

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
  private static final AtomicInteger GROUNDED_VIA_TREE = new AtomicInteger();
  private static final AtomicInteger GROUNDING_UNEXAMINED = new AtomicInteger();

  /** How many queries the two ground alike, and how many differently. */
  private static final AtomicInteger GROUNDING_SAME = new AtomicInteger();

  private static final AtomicInteger GROUNDING_DIFFERED = new AtomicInteger();
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
   *
   * <p>Disagreement in either direction is an error. It used to be an error one
   * way and a counter the other, while the tree front end was catching up; it
   * has caught up, so the invariant the flip needs -- that the tree grounds
   * neither less nor more than the step list -- is now enforced rather than
   * measured.
   */
  public static boolean groundingAgrees(
      TypeSystem typeSystem,
      NameGenerator nameGenerator,
      Environment env,
      Core.From from,
      Core.@Nullable From from2,
      boolean stepGrounded,
      boolean rowsUsed) {
    final Core.Exp tree = RelTranslator.toRel(typeSystem, from);
    if (tree == null) {
      GROUNDING_UNEXAMINED.incrementAndGet();
      return true;
    }
    boolean treeGrounded;
    Core.@Nullable Exp expandedExp = null;
    try {
      final Core.Exp expanded =
          RelExpander.expand(typeSystem, env, tree, rowsUsed);
      expandedExp = expanded;
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
    if (treeGrounded == stepGrounded && stepGrounded && from2 != null) {
      // Both ground it. Do they ground it the same way? Whether is the
      // cheaper question and the one this started with; what is the one the
      // flip needs, because a tree that grounds a query differently is a tree
      // that answers it differently -- `from x, y, z where (x, y) elem pairs
      // ...` grounds either way, and only one of the two is right.
      boolean same;
      try {
        final Core.Exp lowered =
            RelLowerer.lower(
                typeSystem,
                nameGenerator,
                requireNonNull(expandedExp),
                ImmutableList.of());
        same = decisions(lowered).equals(decisions(from2));
      } catch (RuntimeException e) {
        // An expansion that will not lower is one the tree cannot hand back,
        // which is a difference like any other. Counted, not thrown: this is
        // a measurement, and it runs inside an assert.
        same = false;
      }
      if (same) {
        GROUNDING_SAME.incrementAndGet();
      } else {
        GROUNDING_DIFFERED.incrementAndGet();
      }
    }
    if (treeGrounded != stepGrounded) {
      // Whichever way round, it is a change in what compiles, and the flip
      // must not make one silently.
      throw new AssertionError(
          format(
              "the %s grounds a query that the %s does not: %s",
              treeGrounded ? "tree" : "step list",
              treeGrounded ? "step list" : "tree",
              from));
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
    return false;
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

  /**
   * Returns what grounding decided about a query: the collection each scan
   * reads, and the conditions the filters still test, canonically named and
   * sorted.
   *
   * <p>Not the whole query, which differs between the two in ways grounding did
   * not decide -- the order of steps, a projection one of them emits -- and
   * comparing that reports hundreds of differences that are not differences.
   * What grounding decides is what bounds each variable, and what is left for a
   * filter to test because no generator enforces it. Both of the divergences
   * known today show up here: one leaves `where path p` that the other drops,
   * and the other bounds `x` and `y` separately where the other joins them to a
   * shared scan.
   */
  private static List<String> decisions(Core.Exp exp) {
    // One renaming for the whole query, applied to each part: a part names
    // binders that are bound outside it, and renaming each part on its own
    // would leave those alone and report a difference that is only a name.
    final Set<String> bound = new LinkedHashSet<>();
    exp.accept(
        new Visitor() {
          @Override
          protected void visit(Core.IdPat idPat) {
            super.visit(idPat);
            bound.add(idPat.toString());
          }
        });
    final Renamer renamer = new Renamer(bound);
    final List<String> parts = new ArrayList<>();
    exp.accept(
        new Visitor() {
          @Override
          protected void visit(Core.Scan scan) {
            super.visit(scan);
            parts.add("scan " + renamer.apply(scan.exp));
          }

          @Override
          protected void visit(Core.Where where) {
            super.visit(where);
            parts.add("where " + renamer.apply(where.exp));
          }
        });
    parts.sort(Comparator.naturalOrder());
    return parts;
  }

  /** Renames a fixed set of binders, by order of first occurrence. */
  private static class Renamer {
    final Map<String, String> names = new LinkedHashMap<>();
    final @Nullable Pattern pattern;

    Renamer(Set<String> bound) {
      pattern =
          bound.isEmpty()
              ? null
              : Pattern.compile(
                  bound.stream()
                      .map(Pattern::quote)
                      .collect(
                          Collectors.joining(
                              "|",
                              // Not \b: a name that needs quoting renders
                              // inside back-ticks, and \b before a back-tick
                              // is not a boundary, so `w$4` never matched.
                              "(?<![A-Za-z0-9_$`])(?:",
                              ")(?![A-Za-z0-9_$`])")));
    }

    String apply(Core.Exp exp) {
      if (pattern == null) {
        return exp.toString();
      }
      final Matcher matcher = pattern.matcher(exp.toString());
      final StringBuilder b = new StringBuilder();
      while (matcher.find()) {
        matcher.appendReplacement(
            b,
            Matcher.quoteReplacement(
                names.computeIfAbsent(
                    matcher.group(), n -> "v" + names.size())));
      }
      matcher.appendTail(b);
      return b.toString();
    }
  }

  /** Returns how many queries the two ground alike. */
  public static int groundingSameCount() {
    return GROUNDING_SAME.get();
  }

  /** Returns how many queries the two ground differently. */
  public static int groundingDifferedCount() {
    return GROUNDING_DIFFERED.get();
  }

  /** Returns how many queries the two grounding engines agreed on. */
  public static int groundingAgreedCount() {
    return GROUNDING_AGREED.get();
  }

  /**
   * Counts a query that the tree grounded, and that therefore reached a plan
   * without the step list.
   */
  public static void groundedViaTree() {
    GROUNDED_VIA_TREE.incrementAndGet();
  }

  /** Returns how many queries the tree grounded. */
  public static int groundedViaTreeCount() {
    return GROUNDED_VIA_TREE.get();
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
