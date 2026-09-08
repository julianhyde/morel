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
import static net.hydromatic.morel.ast.CoreBuilder.core;
import static net.hydromatic.morel.compile.FreeFinder.freePats;
import static net.hydromatic.morel.compile.Generators.maybeGenerator;
import static net.hydromatic.morel.util.Static.append;
import static net.hydromatic.morel.util.Static.forEachInIntersection;
import static net.hydromatic.morel.util.Static.last;
import static net.hydromatic.morel.util.Static.only;
import static net.hydromatic.morel.util.Static.skip;
import static net.hydromatic.morel.util.Static.transformEager;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.FromBuilder;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.ast.Visitor;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.ListType;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.RecordLikeType;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.PairList;
import org.jspecify.annotations.Nullable;

/** Expands generators. */
public class Expander {
  private final Generators.Cache cache;
  private final List<Core.Exp> constraints;

  private Expander(Generators.Cache cache, List<Core.Exp> constraints) {
    this.cache = cache;
    this.constraints = constraints;
  }

  /**
   * Converts all unbounded variables in a query to bounded, introducing
   * generators by inverting predicates.
   *
   * <p>Returns {@code from} unchanged if no expansion is required.
   */
  public static Core.From expandFrom(
      TypeSystem typeSystem,
      NameGenerator nameGenerator,
      Environment env,
      Core.From from,
      boolean rowsUsed) {
    final Core.@Nullable From from2 =
        expandViaTree(typeSystem, nameGenerator, env, from, rowsUsed);
    if (from2 != null) {
      RelShadow.groundedViaTree();
      return from2;
    }
    // The tree declined -- see `expandViaTree` -- and the step list is the
    // fallback for what it will not take.
    return expandFromSteps(typeSystem, nameGenerator, env, from, rowsUsed);
  }

  /**
   * Grounds a query by translating it to a relational tree, expanding that, and
   * lowering it back.
   *
   * <p>Returns null where the translator declines, or where the expansion does
   * not lower to a step list, so that the caller can ground the steps instead.
   */
  private static Core.@Nullable From expandViaTree(
      TypeSystem typeSystem,
      NameGenerator nameGenerator,
      Environment env,
      Core.From from,
      boolean rowsUsed) {
    if (!containsExtent(from)) {
      // Nothing to ground. The step list returns the query unchanged, and a
      // round trip through the tree would return an equal query that is not
      // the same object -- which the fixed-point loop above reads as progress.
      return null;
    }
    final Core.@Nullable Exp tree = RelTranslator.toRel(typeSystem, from);
    if (tree == null) {
      return null;
    }
    // The patterns the query was written with, in scan order, so that the
    // leaves keep the names the user gave them.
    final List<Core.Pat> leafPats = new ArrayList<>();
    from.steps.forEach(
        step -> {
          if (step instanceof Core.Scan) {
            leafPats.add(((Core.Scan) step).pat);
          }
        });
    // The leaf that each collection grounding builds bounds, so that the
    // lowering can name each scan after the variable it scans.
    final Map<Core.Exp, String> leafNames = new IdentityHashMap<>();
    final Core.Exp expanded;
    try {
      expanded =
          RelExpander.expand(
              typeSystem, env, tree, rowsUsed, leafPats, leafNames);
    } catch (CompileException e) {
      // The step list has its own answer for a query it cannot ground: an
      // error naming the pattern, or the query unchanged so that a later pass
      // reports it. Leave that to it rather than say the same thing
      // differently.
      return null;
    }
    // The same names again, for the binders the lowering invents. Less
    // reliable than naming the leaves was -- grounding may have reordered
    // them, and the lowering takes them positionally -- but a name that ends
    // up on the wrong scan is still unique, and the common case is that they
    // are in the order they were written.
    final List<String> scanNames = new ArrayList<>();
    leafPats.forEach(
        pat -> {
          if (pat instanceof Core.IdPat) {
            scanNames.add(((Core.IdPat) pat).name);
          }
        });
    final Core.Exp lowered =
        RelLowerer.lower(
            typeSystem,
            nameGenerator,
            expanded,
            scanNames.size() == leafPats.size()
                ? Lists.transform(scanNames, ImmutableList::of)
                : ImmutableList.of(),
            true,
            leafNames);
    if (misaddressed(lowered)) {
      // A selector reading a field the row does not have. Replacing a join
      // with a projection makes the element one component where it was
      // several (discussion.md §16), and what reads it above was written for
      // the other shape; `rebuild` does not rebase them. The step list's
      // answer is the one to use until it does.
      return null;
    }
    if (!(lowered instanceof Core.From) || containsExtent(lowered)) {
      // An extent that survives is one the walk did not reach or could not
      // bound -- including one inside a nested query, which this walk does not
      // ground and the step list reaches later. Handing it back would put an
      // infinite collection in a plan, which fails when the query runs rather
      // than when it compiles.
      return null;
    }
    if (System.getenv("MOREL_DEBUG") != null) {
      System.err.println("VT " + lowered); // lint:skip
    }
    return (Core.From) lowered;
  }

  /**
   * Returns whether an expression reads a field that its row does not have.
   *
   * <p>Cheap, and it catches the shape exactly: a selector carries the slot it
   * was made for, and a substitution can put under it a row of fewer fields.
   */
  static boolean misaddressed(Core.Exp exp) {
    final boolean[] found = {false};
    exp.accept(
        new Visitor() {
          @Override
          protected void visit(Core.Apply apply) {
            super.visit(apply);
            if (apply.fn instanceof Core.RecordSelector
                && apply.arg.type instanceof RecordLikeType
                && ((Core.RecordSelector) apply.fn).slot
                    >= ((RecordLikeType) apply.arg.type)
                        .argNameTypes()
                        .size()) {
              found[0] = true;
            }
          }
        });
    return found[0];
  }

  /**
   * Returns whether a tree still has a leaf that cannot be enumerated.
   *
   * <p>Every scan counts, nested queries included: this asks whether the result
   * is safe to hand back, and one infinite extent anywhere in it is not. The
   * step list reaches a nested query later and grounds it then, so declining
   * here costs only that this query takes the other path.
   */
  private static boolean containsExtent(Core.Exp exp) {
    final boolean[] found = {false};
    exp.accept(
        new Visitor() {
          @Override
          protected void visit(Core.Scan scan) {
            super.visit(scan);
            if (Extents.isInfinite(scan.exp)) {
              found[0] = true;
            }
          }
        });
    return found[0];
  }

  private static Core.From expandFromSteps(
      TypeSystem typeSystem,
      NameGenerator nameGenerator,
      Environment env,
      Core.From from,
      boolean rowsUsed) {
    // Pre-pass: run FBBT to deduce tighter bounds for unbounded patterns
    // and strengthen any 'where' clause with the new bounds. The existing
    // extractor (below) then picks them up as finite generators.
    from = applyFbbt(typeSystem, from);

    final Generators.Cache cache =
        new Generators.Cache(typeSystem, env, ungroundedPats(typeSystem, from));
    final Expander expander = new Expander(cache, ImmutableList.of());

    // First, deduce generators.
    expandSteps(from.steps, expander);

    // Second, check that we found a generator for each pattern (infinite
    // extent).
    final StepVarSet stepVars = StepVarSet.create(from, typeSystem);
    for (Core.FromStep step : from.steps) {
      if (step.op == Op.SCAN) {
        final Core.Scan scan = (Core.Scan) step;
        if (scan.exp.isExtent()) {
          final List<Core.NamedPat> namedPats = scan.pat.expand();
          for (Core.NamedPat namedPat : namedPats) {
            if (!rowsUsed && !stepVars.usedPats.contains(namedPat)) {
              // A query whose rows are not used, only counted, may ignore a
              // pattern that no step refers to. For example, "y" is unused in
              // "exists x, y where x elem [1,2,3]". A query whose rows are
              // used may not: the pattern is a column of its output, so if we
              // cannot enumerate it we must say so.
              continue;
            }
            final Generator generator = cache.bestGenerator(namedPat);
            if (generator == null
                || generator.cardinality == Generator.Cardinality.INFINITE) {
              final String message = notGrounded(namedPat);
              assert RelShadow.groundingAgrees(
                  typeSystem, nameGenerator, env, from, null, false, rowsUsed);
              throw new CompileException(message, false, scan.exp.pos);
            }
          }
        }
      }
    }

    // Third, substitute generators.
    // Deduplicating a generator is observable if the query's rows are used,
    // and also if a 'take' or 'skip' is applied to them: those depend on how
    // many rows there are, which removing duplicates changes.
    final boolean dedupObservable = rowsUsed || hasTakeOrSkip(from.steps);
    Core.From from2 = expandFrom2(cache, env, stepVars, dedupObservable);
    if (from2.steps.isEmpty() && !from.steps.isEmpty()) {
      // Every pattern was dropped, because none is referred to by a step and
      // none can be grounded, as in "from i, j". Dropping them all leaves a
      // query with no bindings, which cannot stand in for one that had them.
      // Return the query unchanged, so that its infinite extent is reported
      // as an error rather than crashing the builder.
      return from;
    }
    assert RelShadow.groundingAgrees(
        typeSystem, nameGenerator, env, from, from2, true, rowsUsed);
    return from2.equals(from) ? from : from2;
  }

  /**
   * If {@code scan} iterates over a list of numeric literals, adds conjuncts
   * for the least and the greatest of them to {@code bounds}.
   */
  private static void listBounds(
      TypeSystem typeSystem, Core.Scan scan, List<Core.Exp> bounds) {
    if (!(scan.pat instanceof Core.NamedPat)
        || !scan.exp.isCallTo(BuiltIn.Z_LIST)) {
      return;
    }
    final Core.NamedPat pat = (Core.NamedPat) scan.pat;
    if (pat.type != PrimitiveType.INT && pat.type != PrimitiveType.REAL) {
      return;
    }
    final Core.Exp arg = ((Core.Apply) scan.exp).arg;
    if (arg.op != Op.TUPLE) {
      return;
    }
    final List<Core.Exp> elements = ((Core.Tuple) arg).args;
    if (elements.isEmpty()) {
      return;
    }
    BigDecimal min = null;
    BigDecimal max = null;
    for (Core.Exp element : elements) {
      final Core.@Nullable Literal literal = Bounds.numericLiteral(element);
      if (literal == null) {
        // Not a constant, so we know nothing about the range.
        return;
      }
      final BigDecimal value = literal.unwrap(BigDecimal.class);
      min = min == null || value.compareTo(min) < 0 ? value : min;
      max = max == null || value.compareTo(max) > 0 ? value : max;
    }
    final Core.Exp id = core.id(pat);
    bounds.add(
        core.greaterThanOrEqualTo(
            typeSystem,
            id,
            core.literal((PrimitiveType) pat.type, requireNonNull(min))));
    bounds.add(
        core.call(
            typeSystem,
            BuiltIn.OP_LE,
            PrimitiveType.BOOL,
            Pos.ZERO,
            id,
            core.literal((PrimitiveType) pat.type, requireNonNull(max))));
  }

  /** Returns the patterns of {@code from}'s extent scans. */
  private static Set<Core.NamedPat> extentPats(Core.From from) {
    final Set<Core.NamedPat> pats = new LinkedHashSet<>();
    for (Core.FromStep step : from.steps) {
      if (step.op == Op.SCAN) {
        final Core.Scan scan = (Core.Scan) step;
        if (scan.exp.isExtent()) {
          pats.addAll(scan.pat.expand());
        }
      }
    }
    return pats;
  }

  /**
   * Returns the patterns that are not yet bound when we are looking for a
   * generator: the patterns of {@code from}'s extent scans, plus the patterns
   * of any scan that is correlated with them, directly or transitively.
   *
   * <p>A correlated scan such as {@code y in [x * 2]} cannot run until {@code
   * x} has a generator, so {@code y} is no better than {@code x} as a bound for
   * {@code x}; treating it as bound would let {@link Generators} choose a pair
   * of generators that depend on each other.
   */
  private static Set<Core.NamedPat> ungroundedPats(
      TypeSystem typeSystem, Core.From from) {
    final Set<Core.NamedPat> pats = new LinkedHashSet<>();
    for (Core.FromStep step : from.steps) {
      if (step.op == Op.SCAN) {
        final Core.Scan scan = (Core.Scan) step;
        if (scan.exp.isExtent()
            || !Collections.disjoint(freePats(typeSystem, scan.exp), pats)) {
          pats.addAll(scan.pat.expand());
        }
      }
    }
    return pats;
  }

  /**
   * Runs FBBT over each {@code where} step in {@code from}, strengthening its
   * expression with newly-deduced bounds on the in-scope unbounded patterns.
   * Returns the original {@code from} if FBBT made no progress.
   *
   * <p>"In-scope unbounded patterns" at a given step are the extent-scan
   * patterns seen earlier in the same {@code from}. We don't track wider scope;
   * deductions about an outer-bound variable would require treating it as a
   * free variable here, which the current scope does not handle.
   */
  private static Core.From applyFbbt(TypeSystem typeSystem, Core.From from) {
    // Collect all patterns from extent scans AND infinite-range scans so
    // FBBT can deduce bounds for them via cross-variable reasoning. For
    // infinite-range scans, FBBT also needs to *see* the scan's implied
    // bound (e.g. `x >= 1` from `from x in [1..]`), so we synthesize that
    // bound into the where.
    final Set<Core.NamedPat> unboundedPats = new HashSet<>(extentPats(from));
    final List<Core.Exp> rangeImpliedBounds = new ArrayList<>();
    for (Core.FromStep step : from.steps) {
      if (step.op == Op.SCAN) {
        final Core.Scan scan = (Core.Scan) step;
        if (!scan.exp.isExtent()) {
          final RangePushdown.ScanInfo info = RangePushdown.match(scan);
          if (info != null) {
            unboundedPats.add(info.pat);
            rangeImpliedBounds.add(info.boundExp(typeSystem));
          } else {
            // A scan over a list of numbers, such as 'z in [1, 2, 3]', bounds
            // 'z'. FBBT needs to see those bounds, because they may bound
            // another variable: 'z' bounds 'x' in
            // 'from z in [1, 2, 3], x, y where x + y = z'.
            listBounds(typeSystem, scan, rangeImpliedBounds);
          }
        }
      }
    }

    boolean changed = false;
    final List<Core.FromStep> newSteps = new ArrayList<>(from.steps.size());
    boolean injectedImplied = false;
    for (Core.FromStep step : from.steps) {
      if (step instanceof Core.Where && !unboundedPats.isEmpty()) {
        final Core.Where where = (Core.Where) step;
        // Augment the where's conjuncts with the infinite-range implied
        // bounds before strengthening, so FBBT can use them. Only inject
        // into the first where step.
        final boolean injectHere =
            !injectedImplied && !rangeImpliedBounds.isEmpty();
        final Core.Exp whereWithImplied;
        if (injectHere) {
          whereWithImplied =
              core.andAlso(
                  typeSystem,
                  ImmutableList.<Core.Exp>builder()
                      .addAll(rangeImpliedBounds)
                      .add(where.exp)
                      .build());
          injectedImplied = true;
        } else {
          whereWithImplied = where.exp;
        }
        final Core.Exp strengthened =
            Fbbt.strengthen(typeSystem, unboundedPats, whereWithImplied);
        // The injected bounds were only useful as input to FBBT (they let
        // it deduce cross-variable bounds). Once FBBT is done, they are
        // implied by the original scan range and would just appear as
        // redundant filters in the final query. Strip them.
        final Core.Exp cleaned =
            injectHere
                ? stripConjunctsByIdentity(
                    typeSystem, strengthened, rangeImpliedBounds)
                : strengthened;
        if (cleaned != where.exp) {
          newSteps.add(where.copy(cleaned, where.env));
          changed = true;
        } else {
          newSteps.add(step);
        }
      } else {
        newSteps.add(step);
      }
    }
    Core.From result = changed ? core.from(typeSystem, newSteps) : from;
    // Second pass: push literal-bound where conjuncts into the range
    // constructor of infinite-range scans, turning e.g.
    //   from x in [1..] where x < 5
    // into
    //   from x in [1..^5]
    // (i.e. Range.flatten([CLOSED_OPEN (1, 5)])). This preserves the
    // scan's result type (still a list) and removes the runtime need to
    // materialize the infinite range.
    return RangePushdown.apply(typeSystem, result);
  }

  /**
   * Returns {@code whereExp} with any top-level conjuncts (those at the leaves
   * of an {@code andalso} tree) that are reference-equal (==) to one of {@code
   * toStrip} removed. Used to drop synthetic FBBT-input bounds after FBBT has
   * used them.
   */
  private static Core.Exp stripConjunctsByIdentity(
      TypeSystem typeSystem, Core.Exp whereExp, List<Core.Exp> toStrip) {
    if (toStrip.isEmpty()) {
      return whereExp;
    }
    final List<Core.Exp> conjuncts = core.decomposeAnd(whereExp);
    final List<Core.Exp> remaining = new ArrayList<>(conjuncts.size());
    for (Core.Exp c : conjuncts) {
      boolean strip = false;
      for (Core.Exp t : toStrip) {
        if (c == t) {
          strip = true;
          break;
        }
      }
      if (!strip) {
        remaining.add(c);
      }
    }
    if (remaining.size() == conjuncts.size()) {
      return whereExp;
    }
    return core.andAlso(typeSystem, remaining);
  }

  /** Processing state for a pattern during generator expansion. */
  enum PatternState {
    /** Pattern is currently being processed; used to detect cycles. */
    IN_PROGRESS,
    /** Pattern has been fully processed and has a scan. */
    DONE
  }

  /** Returns whether any step is a 'take' or a 'skip'. */
  private static boolean hasTakeOrSkip(List<Core.FromStep> steps) {
    for (Core.FromStep step : steps) {
      if (step.op == Op.TAKE || step.op == Op.SKIP) {
        return true;
      }
    }
    return false;
  }

  private static Core.From expandFrom2(
      Generators.Cache cache,
      Environment env,
      StepVarSet stepVarSet,
      boolean dedupObservable) {
    // Build the set of patterns that are assigned in some scan.
    final TypeSystem typeSystem = cache.typeSystem;
    // Tracks processing state for each pattern.
    final Map<Core.NamedPat, PatternState> patternState = new HashMap<>();
    final Set<Core.NamedPat> allPats = new HashSet<>();
    // All patterns defined in any scan step (extent or not). Used to
    // distinguish local scan patterns from outer-scope variables.
    final Set<Core.NamedPat> allScanPats = new HashSet<>();
    final Map<Core.NamedPat, Generator> generatorMap = new HashMap<>();
    // Source position of each extent pattern, for error reporting.
    final Map<Core.NamedPat, Pos> patternPos = new HashMap<>();
    stepVarSet.stepVars.forEach(
        (step, vars) -> {
          if (step.op == Op.SCAN) {
            final Core.Scan scan = (Core.Scan) step;
            final List<Core.NamedPat> namedPats = scan.pat.expand();
            allScanPats.addAll(namedPats);
            if (scan.exp.isExtent()) {
              for (Core.NamedPat namedPat : namedPats) {
                patternPos.put(namedPat, scan.exp.pos);
                final Generator generator = cache.bestGenerator(namedPat);
                if (generator == null) {
                  continue;
                }
                if (!stepVarSet.usedPats.contains(namedPat)
                    && generator.cardinality
                        == Generator.Cardinality.INFINITE) {
                  // Ignore patterns that no step refers to and that we could
                  // not ground anyway. For example, "y" is unused in
                  // "exists x, y where x elem [1,2,3]", and its extent is
                  // infinite; dropping it does not change whether the query
                  // has rows.
                  //
                  // A pattern that no step refers to but that has a finite
                  // generator is kept: it is still a column of the query's
                  // output, as "b" is in "from b: bool, i where i elem [3,5]".
                  continue;
                }
                generatorMap.put(namedPat, generator);
              }
              allPats.addAll(namedPats);
            }
          }
        });

    // Track original patterns before adding shared ones for joining.
    // We'll need to project away shared patterns at the end.
    final Set<Core.NamedPat> originalPats = new HashSet<>(allPats);

    // Find shared patterns across generators. If a pattern appears in
    // multiple generators, add it to allPats so it can be used for joining.
    // For example, for "exists v0 where parent(v0, x) andalso parent(v0, y)",
    // both the generator for x and the generator for y have v0 in their
    // patterns. We need v0 in allPats so the second generator can join on it.
    // Use a Set to deduplicate generators (the same generator may be indexed
    // under multiple patterns in generatorMap).
    final Map<Core.NamedPat, Integer> patternCounts = new HashMap<>();
    final Set<Generator> uniqueGenerators =
        new HashSet<>(generatorMap.values());
    for (Generator generator : uniqueGenerators) {
      for (Core.NamedPat p : generator.pat.expand()) {
        patternCounts.merge(p, 1, Integer::sum);
      }
    }
    final Set<Core.NamedPat> sharedPats = new HashSet<>();
    patternCounts.forEach(
        (p, count) -> {
          if (count > 1 && allPats.add(p)) {
            sharedPats.add(p);
          }
        });

    final FromBuilder fromBuilder = core.fromBuilder(typeSystem);
    final Map<Core.NamedPat, Core.Exp> substitution = new HashMap<>();
    stepVarSet.stepVars.forEach(
        (step, freePats) -> {
          // Pull forward any generators.
          for (Core.NamedPat freePat : freePats) {
            addGeneratorScan(
                dedupObservable,
                typeSystem,
                patternState,
                freePat,
                generatorMap,
                allPats,
                allScanPats,
                fromBuilder);
          }

          if (step instanceof Core.Scan) {
            final Core.Scan scan = (Core.Scan) step;
            if (scan.exp.isExtent()) {
              for (Core.NamedPat p : scan.pat.expand()) {
                // Variables that no step refers to and that we could not
                // ground, e.g. "y" in "exists x, y where x elem [1, 2]", have
                // no entry in generatorMap, and addGeneratorScan skips them.
                addGeneratorScan(
                    dedupObservable,
                    typeSystem,
                    patternState,
                    p,
                    generatorMap,
                    allPats,
                    allScanPats,
                    fromBuilder);
              }
              if (scan.env.atom
                  && fromBuilder.stepEnv().bindings.size() == 1
                  && !fromBuilder.stepEnv().atom) {
                final Binding binding = only(fromBuilder.stepEnv().bindings);
                fromBuilder.yield_(core.id(binding.id));
              }
              return;
            }
            // The pattern(s) defined in the scan are now available to
            // subsequent steps.
            for (Core.NamedPat p : scan.pat.expand()) {
              patternState.put(p, PatternState.DONE);
            }
          }

          // The step is not a scan over an extent. Add it now.
          step = Replacer.substitute(typeSystem, env, substitution, step);

          // For "where" steps, simplify the condition by removing
          // predicates that are subsumed by generators.
          //
          // Two levels of simplification:
          // 1. Provenance: remove conjuncts that appear in any
          //    generator's provenance (exact object identity).
          // 2. Simplify: apply each generator's simplify method to
          //    remaining conjuncts (semantic equivalence).
          if (step instanceof Core.Where) {
            final Core.Where where = (Core.Where) step;

            // Collect all provenance constraints from sealed generators.
            // Sealed generators fully encode their provenance, so their
            // constraints can safely be removed from WHERE. Unsealed
            // generators' provenance is advisory only.
            final Set<Core.Exp> allProvenance = new HashSet<>();
            for (Generator g : generatorMap.values()) {
              if (g.sealed) {
                allProvenance.addAll(g.provenance);
              }
            }
            // Decompose, filter by provenance, then simplify remainder.
            final List<Core.Exp> remaining = new ArrayList<>();
            for (Core.Exp conjunct : core.decomposeAnd(where.exp)) {
              if (allProvenance.contains(conjunct)) {
                continue; // subsumed by a generator
              }
              // Apply generator-based simplification.
              final Core.Exp[] simplified = {conjunct};
              generatorMap.forEach(
                  (p, g) ->
                      simplified[0] = g.simplify(typeSystem, p, simplified[0]));
              remaining.add(simplified[0]);
            }
            fromBuilder.where(core.andAlso(typeSystem, remaining));
            return;
          }

          fromBuilder.addAll(ImmutableList.of(step));
        });

    checkAllGrounded(stepVarSet, originalPats, patternState, patternPos);

    // If we added shared patterns for joining, project them away at the end.
    // The final result should only contain the original query patterns.
    if (!sharedPats.isEmpty()) {
      // Check if any shared patterns are in the current step environment
      // What is in scope that is not a shared pattern, and whether any shared
      // pattern is in scope at all. Asked this way round rather than "is it
      // one of the query's own patterns": a step may have rebound the row --
      // `distinct` groups on `x` and binds a new `x` -- and a name that is
      // neither the query's nor shared is still a name the query returns.
      final List<Core.NamedPat> toProject = new ArrayList<>();
      boolean anyShared = false;
      for (Binding binding : fromBuilder.stepEnv().bindings) {
        if (sharedPats.contains(binding.id)) {
          anyShared = true;
        } else {
          toProject.add(binding.id);
        }
      }
      if (anyShared) {
        // Some shared patterns need to be projected away.
        // We also need distinct because projecting away variables that were
        // used for joining (like y in "exists y where edge(x,y) andalso
        // edge(y,z)") can cause duplicates. For example, (1, 3) would appear
        // twice if there are two different y values connecting x=1 to z=3.
        fromBuilder.yield_(core.recordOrAtom(typeSystem, toProject));
        fromBuilder.distinct();
      }
    }

    return fromBuilder.build();
  }

  /**
   * Throws "pattern '...' is not grounded" if any extent pattern is not {@link
   * PatternState#DONE}.
   *
   * <p>This happens when a pattern has a generator but the generator depends on
   * another pattern's generator and the dependency cycles back. For example, in
   * {@code from x, y where x > 0 andalso x < y andalso y < 10}, x's generator
   * depends on y (upper bound) and y's generator depends on x (lower bound).
   * Without bound propagation across the cycle, neither can be scheduled.
   */
  private static void checkAllGrounded(
      StepVarSet stepVarSet,
      Set<Core.NamedPat> originalPats,
      Map<Core.NamedPat, PatternState> patternState,
      Map<Core.NamedPat, Pos> patternPos) {
    // Iterate in source order so the error is deterministic and points at
    // the first ungrounded pattern.
    stepVarSet.stepVars.forEach(
        (step, vars) -> {
          if (step.op != Op.SCAN) {
            return;
          }
          final Core.Scan scan = (Core.Scan) step;
          if (!scan.exp.isExtent()) {
            return;
          }
          for (Core.NamedPat p : scan.pat.expand()) {
            if (!stepVarSet.usedPats.contains(p) || !originalPats.contains(p)) {
              continue;
            }
            if (patternState.get(p) != PatternState.DONE) {
              throw new CompileException(
                  notGrounded(p), false, patternPos.getOrDefault(p, Pos.ZERO));
            }
          }
        });
  }

  /**
   * Returns the message for a pattern that nothing bounds.
   *
   * <p>A binder the compiler generated names nothing the user wrote -- under a
   * tree the query's own names do not reach the lowering -- so the message
   * leaves it out. A diagnostic that confidently names the wrong variable is
   * worse than one that names none; discussion.md §11.
   */
  static String notGrounded(Core.NamedPat pat) {
    return Core.NamedPat.isGenerated(pat.name)
        ? "pattern is not grounded"
        : format("pattern '%s' is not grounded", pat.name);
  }

  /**
   * Adds a scan that generates {@code freePat}.
   *
   * <p>Does nothing if {@code freePat} is not an unbounded variable, or if it
   * already has a scan.
   *
   * <p>If the generator expression depends on other unbounded variables, adds
   * those variables' generators first.
   */
  private static void addGeneratorScan(
      boolean dedupObservable,
      TypeSystem typeSystem,
      Map<Core.NamedPat, PatternState> patternState,
      Core.NamedPat freePat,
      Map<Core.NamedPat, Generator> generatorMap,
      Set<Core.NamedPat> allPats,
      Set<Core.NamedPat> allScanPats,
      FromBuilder fromBuilder) {
    if (patternState.containsKey(freePat) || !allPats.contains(freePat)) {
      return;
    }

    // Find a generator, and find which patterns it depends on.
    final Generator generator = generatorMap.get(freePat);
    if (generator == null) {
      return;
    }

    // Mark this pattern as "in progress" to prevent infinite recursion.
    // If a dependency cycles back to this pattern, we'll detect it via
    // patternState.containsKey() and stop recursing.
    patternState.put(freePat, PatternState.IN_PROGRESS);

    // Make sure all dependencies have a scan.
    for (Core.NamedPat p : generator.freePats) {
      addGeneratorScan(
          dedupObservable,
          typeSystem,
          patternState,
          p,
          generatorMap,
          allPats,
          allScanPats,
          fromBuilder);
    }

    // Check that all dependencies are now satisfied.
    // If a dependency is from a scan in this from expression that hasn't been
    // processed yet, we cannot add this generator now - it will be added later
    // when the dependency is DONE (e.g., when processing the WHERE clause).
    // Free variables from outer scopes (e.g., a variable defined in an
    // enclosing let) are already bound and don't need to be DONE.
    for (Core.NamedPat p : generator.freePats) {
      if (allScanPats.contains(p) && patternState.get(p) != PatternState.DONE) {
        patternState.remove(freePat);
        return;
      }
    }

    // The patterns we need (requiredPats) are those provided by the generator,
    // which are used in later steps (allPats),
    // and are not already DONE.
    final List<Core.NamedPat> expandedPats = generator.pat.expand();
    final List<Core.NamedPat> requiredPats =
        new ArrayList<>(expandedPats.size());
    for (Core.NamedPat p : expandedPats) {
      if (allPats.contains(p) && patternState.get(p) != PatternState.DONE) {
        requiredPats.add(p);
      }
    }
    // Now all dependencies are DONE, add a scan for the generator.
    if (expandedPats.equals(requiredPats)) {
      if (generator.unique || !dedupObservable) {
        // Add "join (x, y, z) in collection".
        //
        // A query whose rows are only counted - 'exists' or 'forall', with no
        // 'take' or 'skip' - does not need the generator deduplicated:
        // removing duplicate rows cannot change whether there are any.
        fromBuilder.scan(generator.pat, generator.exp);
      } else {
        // The generator may produce a value more than once - a collection may
        // hold duplicates, and a union of ranges may overlap - but an
        // unbounded scan yields each assignment once. Deduplicate:
        //   join (x, y, z) in (from (x, y, z) in collection
        //                        distinct
        //                        yield {x, y, z})
        // The yield and the scan pattern both use the canonical record form,
        // because 'distinct' groups by the variables and sorts them, and so
        // does not return a tuple in the order the generator's pattern
        // expects.
        final FromBuilder fromBuilder2 = core.fromBuilder(typeSystem);
        fromBuilder2.scan(generator.pat, generator.exp);
        fromBuilder2.distinct();
        // An unbounded scan yields its values in the natural order of the
        // variables. Sort after the 'distinct', whose 'group' has no order of
        // its own, and before the 'yield', which rebinds the variables that
        // the sort names.
        fromBuilder2.order(core.recordOrAtom(typeSystem, expandedPats));
        fromBuilder2.yield_(core.recordOrAtom(typeSystem, expandedPats));
        fromBuilder.scan(
            core.recordOrAtomPat(typeSystem, expandedPats),
            fromBuilder2.build());
      }
    } else {
      // Some patterns are already bound. Create a filtered projection.
      // For example, for "(y, z) in edges" where y is already bound:
      // Add "join z in (from (y', z) in edges where y' = y yield z)".

      // Identify patterns that are already bound.
      final Map<Core.NamedPat, Core.IdPat> renameMap = new HashMap<>();
      final List<Core.Exp> joinConditions = new ArrayList<>();
      for (Core.NamedPat p : expandedPats) {
        // A pattern is already bound if it's DONE (scanned earlier in this
        // from) or if it's not in allScanPats (bound in an outer scope).
        final boolean alreadyBound =
            patternState.get(p) == PatternState.DONE
                || !allScanPats.contains(p);
        if (alreadyBound && !requiredPats.contains(p)) {
          // Create a fresh pattern variable for the subquery's binding.
          final Core.IdPat freshPat = core.idPat(p.type, p.name + "'", 0);
          renameMap.put(p, freshPat);
          // Add condition: freshPat = p (subquery's value equals outer value)
          joinConditions.add(
              core.equal(typeSystem, core.id(freshPat), core.id(p)));
        }
      }

      // Build subquery: from (y', z) in collection where y' = y yield z
      final FromBuilder fromBuilder2 = core.fromBuilder(typeSystem);
      final Core.Pat scanPat =
          renamePatterns(typeSystem, generator.pat, renameMap);
      fromBuilder2.scan(
          scanPat, generator.exp, core.andAlso(typeSystem, joinConditions));

      // Yield only the required patterns.
      final Core.Exp yieldExp = core.recordOrAtom(typeSystem, requiredPats);
      fromBuilder2.yield_(yieldExp);

      // Add distinct if:
      // 1. The generator may produce duplicates (!generator.unique), or
      // 2. We're projecting away inner variables (not outer-bound).
      //
      // If patterns are projected away because they're DONE (already bound
      // from outer scans), we don't need distinct - the outer context provides
      // uniqueness via the join condition.
      // If patterns are projected away because they're not in `allPats` (inner
      // variables like y in "exists y"), we need distinct to avoid duplicates.
      // As above, a query whose rows are only counted needs no deduplication.
      boolean needsDistinct = !generator.unique && dedupObservable;
      if (!needsDistinct && dedupObservable) {
        for (Core.NamedPat p : expandedPats) {
          // Outer-scope variables (!allScanPats) have join conditions added
          // above and don't require distinct. Only inner variables (those that
          // are local to this from but not required) need distinct.
          if (!requiredPats.contains(p)
              && patternState.get(p) != PatternState.DONE
              && allScanPats.contains(p)) {
            needsDistinct = true;
            break;
          }
        }
      }
      if (needsDistinct) {
        fromBuilder2.distinct();
        fromBuilder2.order(yieldExp);
      }

      // Add scan from the filtered subquery.
      final Core.Pat scanPat2 = core.recordOrAtomPat(typeSystem, requiredPats);
      final Core.From subquery = fromBuilder2.build();
      fromBuilder.scan(scanPat2, subquery);
    }
    for (Core.NamedPat p : requiredPats) {
      patternState.put(p, PatternState.DONE);
    }
  }

  /**
   * Renames patterns in a pattern tree according to the given map.
   *
   * <p>For example, if the map is {@code {y -> y$}}, then the pattern (x, y, z)
   * becomes (x, y$, z).
   */
  static Core.Pat renamePatterns(
      TypeSystem typeSystem,
      Core.Pat pat,
      Map<Core.NamedPat, Core.IdPat> renameMap) {
    if (pat instanceof Core.IdPat) {
      final Core.IdPat replacement = renameMap.get(pat);
      return replacement != null ? replacement : pat;
    } else if (pat instanceof Core.TuplePat) {
      final Core.TuplePat tuplePat = (Core.TuplePat) pat;
      final List<Core.Pat> args = new ArrayList<>(tuplePat.args.size());
      boolean changed = false;
      for (Core.Pat arg : tuplePat.args) {
        final Core.Pat newArg = renamePatterns(typeSystem, arg, renameMap);
        args.add(newArg);
        if (newArg != arg) {
          changed = true;
        }
      }
      return changed ? tuplePat.copy(typeSystem, args) : pat;
    } else if (pat instanceof Core.RecordPat) {
      final Core.RecordPat recordPat = (Core.RecordPat) pat;
      final List<Core.Pat> args = new ArrayList<>(recordPat.args.size());
      boolean changed = false;
      for (Core.Pat arg : recordPat.args) {
        final Core.Pat newArg = renamePatterns(typeSystem, arg, renameMap);
        args.add(newArg);
        if (newArg != arg) {
          changed = true;
        }
      }
      return changed ? core.recordPat(recordPat.type(), args) : pat;
    } else {
      return pat;
    }
  }

  /**
   * Grounds one pattern: registers the extent it scans, applies the constraints
   * that bound it, and returns the best generator, or null if there is none.
   *
   * <p>This is what {@link #expandSteps} does for a step list, expressed
   * without steps, so that a relational tree can use the same engine: a leaf
   * that is an infinite extent, and the conditions of the filters above it. See
   * {@link RelExpander}.
   */
  static Generators.Cache ground(
      Generators.Cache cache,
      Core.Pat pat,
      Core.Exp extentExp,
      List<Core.Exp> constraints) {
    return ground(cache, PairList.of(pat, extentExp), constraints);
  }

  /**
   * Grounds several patterns at once, as a step list does: every extent is
   * registered before any constraint is applied, so that a constraint tying two
   * variables together can generate for both.
   */
  static Generators.Cache ground(
      Generators.Cache cache,
      PairList<Core.Pat, Core.Exp> extents,
      List<Core.Exp> constraints) {
    final List<Ground> steps = new ArrayList<>();
    extents.forEach((pat, exp) -> steps.add(new Ground(pat, exp)));
    constraints.forEach(constraint -> steps.add(new Ground(null, constraint)));
    return ground(cache, steps);
  }

  /**
   * Registers extents and applies constraints in the order given.
   *
   * <p>The order is part of the answer, not a detail: the engine improves its
   * generators after every constraint, so a constraint sees the extents
   * registered before it and not the ones after. {@link #expandSteps} gives
   * them in the order a query's steps are written -- {@code from i where A join
   * b where B} is extent, A, extent, B -- and a tree walked left to right gives
   * the same order.
   */
  static Generators.Cache ground(Generators.Cache cache, List<Ground> steps) {
    Expander expander = new Expander(cache, ImmutableList.of());
    for (Ground step : steps) {
      if (step.pat != null) {
        Generators.maybeExtent(cache, step.pat, step.exp);
      } else {
        expander = expander.plusConstraint(step.exp);
        expander.improveGenerators(cache.generators);
      }
    }
    return cache;
  }

  /** One step of grounding: an extent to register, or a constraint to apply. */
  static class Ground {
    final Core.@Nullable Pat pat;
    final Core.Exp exp;

    Ground(Core.@Nullable Pat pat, Core.Exp exp) {
      this.pat = pat;
      this.exp = exp;
    }
  }

  static void expandSteps(List<Core.FromStep> steps, Expander expander) {
    if (steps.isEmpty()) {
      return;
    }
    final Generators.Cache cache = expander.cache;
    final Core.FromStep step0 = steps.get(0);
    switch (step0.op) {
      case SCAN:
        final Core.Scan scan = (Core.Scan) step0;
        // The first attempt at a generator is the extent of the type.
        // Usually finite, but finite for types like 'bool option'.
        Generators.maybeExtent(cache, scan.pat, scan.exp);
        break;

      case WHERE:
        final Core.Where where = (Core.Where) step0;
        final List<Core.Exp> conditions = core.decomposeAnd(where.exp);
        for (Core.Exp condition : conditions) {
          expander = expander.plusConstraint(condition);
          expander.improveGenerators(cache.generators);
        }
        break;
    }
    expandSteps(skip(steps), expander);
  }

  /**
   * Tries to improve the existing generators.
   *
   * <p>This means replacing each generator with one of lower cardinality - an
   * infinite generator with a finite generator, or a finite generator with one
   * that is a single value or is empty.
   */
  private void improveGenerators(
      Multimap<Core.NamedPat, Generator> generators) {
    // Create a snapshot of the generators map, to avoid concurrent
    // modification.
    final PairList<Core.NamedPat, Generator> infiniteGenerators = PairList.of();
    generators.forEach(
        (pat, generator) -> {
          if (generator.cardinality == Generator.Cardinality.INFINITE) {
            infiniteGenerators.add(pat, generator);
          }
        });

    infiniteGenerators.forEach(
        (pat, generator) -> {
          final boolean ordered = generator.exp.type instanceof ListType;
          if (maybeGenerator(
              cache, pat, ordered, new Generators.Context(constraints))) {
            Generator g = last(cache.generators.get(pat));
            g.pat.expand().forEach(p2 -> generators.put(p2, g));
          }
        });
  }

  private Expander plusConstraint(Core.Exp constraint) {
    return withConstraints(append(this.constraints, constraint));
  }

  private Expander withConstraints(List<Core.Exp> constraints) {
    return new Expander(cache, constraints);
  }

  /**
   * Finds free variables in an expression.
   *
   * <p>It works similarly to {@link FreeFinder}.
   */
  static class StepAnalyzer extends EnvVisitor {
    final List<Core.NamedPat> freePats;
    final BiConsumer<Core.FromStep, Set<Core.NamedPat>> consumer;

    private StepAnalyzer(
        TypeSystem typeSystem,
        Environment env,
        Deque<FromContext> fromStack,
        List<Core.NamedPat> freePats,
        BiConsumer<Core.FromStep, Set<Core.NamedPat>> consumer) {
      super(typeSystem, env, fromStack);
      this.freePats = freePats;
      this.consumer = consumer;
    }

    /**
     * Given a query, computes a list of steps and the free variables in each
     * step.
     */
    private static PairList<Core.FromStep, Set<Core.NamedPat>> getEntries(
        Core.From from, TypeSystem typeSystem) {
      final PairList<Core.FromStep, Set<Core.NamedPat>> list = PairList.of();
      final Environment env = Environments.empty();
      from.accept(
          new StepAnalyzer(
              typeSystem,
              env,
              new ArrayDeque<>(),
              new ArrayList<>(),
              list::add));
      return list;
    }

    @Override
    protected EnvVisitor push(Environment env) {
      return new StepAnalyzer(typeSystem, env, fromStack, freePats, consumer);
    }

    @Override
    protected void visit(Core.Id id) {
      freePats.add(id.idPat);
    }

    @Override
    public void visitStep(Core.FromStep step, Core.StepEnv stepEnv) {
      if (!fromStack.isEmpty()) {
        super.visitStep(step, stepEnv);
        return;
      }
      freePats.clear();
      super.visitStep(step, stepEnv);
      final ImmutableSet.Builder<Core.NamedPat> namedPats =
          ImmutableSet.builder();
      forEachInIntersection(
          freePats,
          transformEager(stepEnv.bindings, b -> b.id),
          namedPats::add);
      consumer.accept(step, namedPats.build());
    }
  }

  /** Analysis of the variables used in each step of a query. */
  static class StepVarSet {
    private final PairList<Core.FromStep, Set<Core.NamedPat>> stepVars;
    private final Set<Core.NamedPat> usedPats;

    StepVarSet(PairList<Core.FromStep, Set<Core.NamedPat>> stepVars) {
      this.stepVars = stepVars.immutable();
      this.usedPats =
          stepVars.rightList().stream()
              .flatMap(Collection::stream)
              .collect(ImmutableSet.toImmutableSet());
    }

    /**
     * Given a query, returns a list of the steps and the variables from the
     * step environment used by each step.
     */
    static StepVarSet create(Core.From from, TypeSystem typeSystem) {
      return new StepVarSet(StepAnalyzer.getEntries(from, typeSystem));
    }
  }
}

// End Expander.java
