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

import static com.google.common.collect.Iterables.getLast;
import static java.lang.String.format;
import static net.hydromatic.morel.ast.CoreBuilder.core;
import static net.hydromatic.morel.compile.Generators.maybeGenerator;
import static net.hydromatic.morel.util.Static.append;
import static net.hydromatic.morel.util.Static.forEachInIntersection;
import static net.hydromatic.morel.util.Static.skip;
import static net.hydromatic.morel.util.Static.transformEager;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimap;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.FromBuilder;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.FnType;
import net.hydromatic.morel.type.ListType;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.PairList;
import org.checkerframework.checker.nullness.qual.Nullable;

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
      TypeSystem typeSystem, Environment env, Core.From from) {
    // Pre-pass: run FBBT to deduce tighter bounds for unbounded patterns
    // and strengthen any 'where' clause with the new bounds. The existing
    // extractor (below) then picks them up as finite generators.
    from = applyFbbt(typeSystem, from);

    final Generators.Cache cache = new Generators.Cache(typeSystem, env);
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
            if (!stepVars.usedPats.contains(namedPat)) {
              // Ignore patterns that are not used. For example, "y" is unused
              // in "exists x, y where x elem [1,2,3]".
              continue;
            }
            final Generator generator = cache.bestGenerator(namedPat);
            if (generator == null
                || generator.cardinality == Generator.Cardinality.INFINITE) {
              final String message =
                  format("pattern '%s' is not grounded", namedPat.name);
              throw new CompileException(message, false, scan.exp.pos);
            }
          }
        }
      }
    }

    // Third, substitute generators.
    Core.From from2 = expandFrom2(cache, env, stepVars);
    return from2.equals(from) ? from : from2;
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
    final Set<Core.NamedPat> unboundedPats = new HashSet<>();
    final List<Core.Exp> rangeImpliedBounds = new ArrayList<>();
    for (Core.FromStep step : from.steps) {
      if (step.op == Op.SCAN) {
        final Core.Scan scan = (Core.Scan) step;
        if (scan.exp.isExtent()) {
          unboundedPats.addAll(scan.pat.expand());
        } else {
          final InfiniteRange ir = matchInfiniteRange(scan);
          if (ir != null) {
            unboundedPats.add(ir.pat);
            rangeImpliedBounds.add(ir.boundExp(typeSystem));
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
        final Core.Exp whereWithImplied;
        if (!injectedImplied && !rangeImpliedBounds.isEmpty()) {
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
        if (strengthened != where.exp) {
          newSteps.add(where.copy(strengthened, where.env));
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
    return pushBoundsIntoRangeScans(typeSystem, result);
  }

  /**
   * Returns whether {@code scan.exp} is an infinite single-constructor range
   * list (e.g. {@code [1..]}, {@code [..^5]}). Used by {@link
   * SuchThatShuttle#containsUnbounded} so that such scans go through the
   * FBBT/Expander pipeline.
   */
  static boolean isInfiniteRangeScan(Core.Scan scan) {
    return matchInfiniteRange(scan) != null;
  }

  /**
   * For each infinite-range scan in {@code from}, finds a literal bound on the
   * scan's pattern in the first downstream {@code where} step and combines it
   * with the scan's existing range constructor to form a finite constructor.
   * The scan exp is replaced (preserving the list type), and the consumed
   * conjunct is removed from the where.
   */
  private static Core.From pushBoundsIntoRangeScans(
      TypeSystem typeSystem, Core.From from) {
    // Locate the first where step.
    int whereIdx = -1;
    for (int i = 0; i < from.steps.size(); i++) {
      if (from.steps.get(i) instanceof Core.Where) {
        whereIdx = i;
        break;
      }
    }
    if (whereIdx < 0) {
      return from;
    }
    final Core.Where where = (Core.Where) from.steps.get(whereIdx);
    final List<Core.Exp> whereConjuncts = core.decomposeAnd(where.exp);
    final Set<Core.Exp> consumed = new HashSet<>();
    final List<Core.FromStep> newSteps = new ArrayList<>(from.steps.size());
    boolean changed = false;
    for (int i = 0; i < from.steps.size(); i++) {
      final Core.FromStep step = from.steps.get(i);
      if (i == whereIdx) {
        // We rebuild the where step after the loop.
        newSteps.add(step);
        continue;
      }
      if (step.op == Op.SCAN) {
        final Core.Scan scan = (Core.Scan) step;
        final InfiniteRange ir = matchInfiniteRange(scan);
        if (ir != null) {
          final Tightening t =
              findTightening(typeSystem, ir, whereConjuncts, consumed);
          if (t != null) {
            newSteps.add(
                scan.copy(scan.env, scan.pat, t.newExp, scan.condition));
            consumed.add(t.consumedConjunct);
            changed = true;
            continue;
          }
        }
      }
      newSteps.add(step);
    }
    if (!changed) {
      return from;
    }
    final List<Core.Exp> remaining = new ArrayList<>();
    for (Core.Exp c : whereConjuncts) {
      if (!consumed.contains(c)) {
        remaining.add(c);
      }
    }
    if (remaining.isEmpty()) {
      // Drop the now-empty where step.
      newSteps.remove(whereIdx);
    } else {
      newSteps.set(
          whereIdx, where.copy(core.andAlso(typeSystem, remaining), where.env));
    }
    return core.from(typeSystem, newSteps);
  }

  /**
   * Result of {@link #findTightening}: the new finite-range scan expression and
   * the where conjunct it consumed.
   */
  private static final class Tightening {
    final Core.Exp newExp;
    final Core.Exp consumedConjunct;

    Tightening(Core.Exp newExp, Core.Exp consumedConjunct) {
      this.newExp = newExp;
      this.consumedConjunct = consumedConjunct;
    }
  }

  /**
   * Searches {@code whereConjuncts} for the tightest literal bound on {@code
   * ir.pat} that complements {@code ir}'s open side. Returns a {@link
   * Tightening} or {@code null} if no such bound exists.
   */
  private static @Nullable Tightening findTightening(
      TypeSystem typeSystem,
      InfiniteRange ir,
      List<Core.Exp> whereConjuncts,
      Set<Core.Exp> consumed) {
    // AT_LEAST/GREATER_THAN -> need an upper bound; AT_MOST/LESS_THAN ->
    // need a lower bound.
    final boolean needUpper = ir.op == BuiltIn.OP_GE || ir.op == BuiltIn.OP_GT;
    Core.Exp bestConjunct = null;
    BigDecimal bestValue = null;
    boolean bestStrict = false;
    for (Core.Exp c : whereConjuncts) {
      if (consumed.contains(c)) {
        continue;
      }
      final LiteralBound lb = extractLiteralBound(c, ir.pat);
      if (lb == null || lb.isUpper != needUpper) {
        continue;
      }
      if (bestValue == null
          || needUpper && lb.value.compareTo(bestValue) < 0
          || !needUpper && lb.value.compareTo(bestValue) > 0) {
        bestValue = lb.value;
        bestStrict = lb.strict;
        bestConjunct = c;
      }
    }
    if (bestConjunct == null) {
      return null;
    }
    final BigDecimal lowerValue;
    final boolean lowerStrict;
    final BigDecimal upperValue;
    final boolean upperStrict;
    final BigDecimal scanValue = literalValue(ir.value);
    if (scanValue == null) {
      return null;
    }
    if (needUpper) {
      lowerValue = scanValue;
      lowerStrict = ir.op == BuiltIn.OP_GT;
      upperValue = bestValue;
      upperStrict = bestStrict;
    } else {
      lowerValue = bestValue;
      lowerStrict = bestStrict;
      upperValue = scanValue;
      upperStrict = ir.op == BuiltIn.OP_LT;
    }
    if (lowerValue.compareTo(upperValue) > 0) {
      return null;
    }
    final BuiltIn.Constructor ctor = finiteCtor(lowerStrict, upperStrict);
    final Core.Exp newExp =
        buildRangeFlatten(
            typeSystem, ir.pat.type, ctor, lowerValue, upperValue);
    return new Tightening(newExp, bestConjunct);
  }

  private static BuiltIn.Constructor finiteCtor(
      boolean lowerStrict, boolean upperStrict) {
    if (lowerStrict) {
      return upperStrict
          ? BuiltIn.Constructor.RANGE_OPEN
          : BuiltIn.Constructor.RANGE_OPEN_CLOSED;
    }
    return upperStrict
        ? BuiltIn.Constructor.RANGE_CLOSED_OPEN
        : BuiltIn.Constructor.RANGE_CLOSED;
  }

  /**
   * Builds {@code Range.flatten [ctor (lo, hi)]} of type {@code <patType>
   * list}. Mirrors the shape produced by Resolver/RangeList for finite range
   * literals.
   */
  private static Core.Exp buildRangeFlatten(
      TypeSystem typeSystem,
      Type elemType,
      BuiltIn.Constructor ctor,
      BigDecimal lo,
      BigDecimal hi) {
    final Type rangeType = typeSystem.range(elemType);
    final FnType conFnType =
        typeSystem.fnType(typeSystem.tupleType(elemType, elemType), rangeType);
    final Core.IdPat conIdPat = core.idPat(conFnType, ctor.constructor, 0);
    final Core.Exp loLit = core.literal((PrimitiveType) elemType, lo);
    final Core.Exp hiLit = core.literal((PrimitiveType) elemType, hi);
    final Core.Apply rangeExp =
        core.apply(
            Pos.ZERO,
            rangeType,
            core.id(conIdPat),
            core.tuple(typeSystem, loLit, hiLit));
    final Core.Exp rangeListExp =
        core.list(typeSystem, rangeType, ImmutableList.of(rangeExp));
    // The flatten produces a list; that matches the original [k..]'s type.
    return core.call(
        typeSystem, BuiltIn.RANGE_FLATTEN, elemType, Pos.ZERO, rangeListExp);
  }

  /** A literal bound conjunct on a specific pattern. */
  private static final class LiteralBound {
    final boolean isUpper;
    final boolean strict;
    final BigDecimal value;

    LiteralBound(boolean isUpper, boolean strict, BigDecimal value) {
      this.isUpper = isUpper;
      this.strict = strict;
      this.value = value;
    }
  }

  /**
   * If {@code c} is a bound constraint of the form {@code pat OP literal} or
   * {@code literal OP pat} where OP is in {@code <, <=, >, >=}, returns a
   * {@link LiteralBound}; otherwise null.
   */
  private static @Nullable LiteralBound extractLiteralBound(
      Core.Exp c, Core.NamedPat pat) {
    if (c.op != Op.APPLY) {
      return null;
    }
    final BuiltIn op = c.builtIn();
    if (op == null) {
      return null;
    }
    final Core.Exp lhs = c.arg(0);
    final Core.Exp rhs = c.arg(1);
    final boolean lhsIsPat =
        lhs instanceof Core.Id && ((Core.Id) lhs).idPat.equals(pat);
    final boolean rhsIsPat =
        rhs instanceof Core.Id && ((Core.Id) rhs).idPat.equals(pat);
    final BuiltIn normalized;
    final Core.Exp constSide;
    if (lhsIsPat && !rhsIsPat) {
      normalized = op;
      constSide = rhs;
    } else if (rhsIsPat && !lhsIsPat) {
      normalized = op.reverse();
      constSide = lhs;
    } else {
      return null;
    }
    final BigDecimal value = literalValue(constSide);
    if (value == null) {
      return null;
    }
    switch (normalized) {
      case OP_LT:
        return new LiteralBound(true, true, value);
      case OP_LE:
        return new LiteralBound(true, false, value);
      case OP_GT:
        return new LiteralBound(false, true, value);
      case OP_GE:
        return new LiteralBound(false, false, value);
      default:
        return null;
    }
  }

  private static @Nullable BigDecimal literalValue(Core.Exp e) {
    if (!(e instanceof Core.Literal)) {
      return null;
    }
    final Core.Literal lit = (Core.Literal) e;
    if (lit.op != Op.INT_LITERAL) {
      return null;
    }
    return (BigDecimal) lit.value;
  }

  /**
   * If {@code scan.exp} is {@code Range.flatten [<single_infinite_ctor>]},
   * returns a descriptor of the pattern and its implied bound; otherwise {@code
   * null}.
   */
  private static @Nullable InfiniteRange matchInfiniteRange(Core.Scan scan) {
    if (!(scan.pat instanceof Core.NamedPat)) {
      return null;
    }
    final Core.NamedPat namedPat = (Core.NamedPat) scan.pat;
    if (!(scan.exp instanceof Core.Apply)) {
      return null;
    }
    final Core.Apply apply = (Core.Apply) scan.exp;
    if (apply.builtIn() != BuiltIn.RANGE_FLATTEN) {
      return null;
    }
    if (!apply.arg.isCallTo(BuiltIn.Z_LIST)) {
      return null;
    }
    final Core.Apply list = (Core.Apply) apply.arg;
    if (list.args().size() != 1) {
      return null;
    }
    final Core.Exp elem = list.args().get(0);
    if (!(elem instanceof Core.Apply)) {
      return null;
    }
    final Core.Apply ctor = (Core.Apply) elem;
    final String name =
        ctor.fn instanceof Core.Id ? ((Core.Id) ctor.fn).idPat.name : null;
    if (name == null) {
      return null;
    }
    switch (name) {
      case "AT_LEAST":
        return new InfiniteRange(namedPat, ctor.arg, BuiltIn.OP_GE);
      case "AT_MOST":
        return new InfiniteRange(namedPat, ctor.arg, BuiltIn.OP_LE);
      case "GREATER_THAN":
        return new InfiniteRange(namedPat, ctor.arg, BuiltIn.OP_GT);
      case "LESS_THAN":
        return new InfiniteRange(namedPat, ctor.arg, BuiltIn.OP_LT);
      default:
        return null;
    }
  }

  /** Pattern + the bound implied by the original infinite range scan. */
  private static final class InfiniteRange {
    final Core.NamedPat pat;
    final Core.Exp value;
    final BuiltIn op;

    InfiniteRange(Core.NamedPat pat, Core.Exp value, BuiltIn op) {
      this.pat = pat;
      this.value = value;
      this.op = op;
    }

    Core.Exp boundExp(TypeSystem typeSystem) {
      return core.call(
          typeSystem, op, PrimitiveType.BOOL, Pos.ZERO, core.id(pat), value);
    }
  }

  /** Processing state for a pattern during generator expansion. */
  enum PatternState {
    /** Pattern is currently being processed; used to detect cycles. */
    IN_PROGRESS,
    /** Pattern has been fully processed and has a scan. */
    DONE
  }

  private static Core.From expandFrom2(
      Generators.Cache cache, Environment env, StepVarSet stepVarSet) {
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
                if (!stepVarSet.usedPats.contains(namedPat)) {
                  // Ignore patterns that are not used. For example, "y" is
                  // unused in "exists x, y where x elem [1,2,3]".
                  continue;
                }
                final Generator generator = cache.bestGenerator(namedPat);
                if (generator != null) {
                  generatorMap.put(namedPat, generator);
                }
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
                // Skip scan variables that are not used, e.g. "y" in
                // "exists x, y where x elem [1, 2]".
                if (stepVarSet.usedPats.contains(p)) {
                  addGeneratorScan(
                      typeSystem,
                      patternState,
                      p,
                      generatorMap,
                      allPats,
                      allScanPats,
                      fromBuilder);
                }
              }
              if (scan.env.atom
                  && fromBuilder.stepEnv().bindings.size() == 1
                  && !fromBuilder.stepEnv().atom) {
                final Binding binding =
                    Iterables.getOnlyElement(fromBuilder.stepEnv().bindings);
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
      final List<Core.NamedPat> toProject = new ArrayList<>();
      for (Binding binding : fromBuilder.stepEnv().bindings) {
        if (originalPats.contains(binding.id)) {
          toProject.add(binding.id);
        }
      }
      if (toProject.size() < fromBuilder.stepEnv().bindings.size()) {
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
                  format("pattern '%s' is not grounded", p.name),
                  false,
                  patternPos.getOrDefault(p, Pos.ZERO));
            }
          }
        });
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
      if (generator.unique) {
        // Add "join (x, y, z) in collection".
        fromBuilder.scan(generator.pat, generator.exp);
      } else {
        // Generator may produce duplicates (e.g., union of overlapping ranges).
        // Wrap with distinct: "from pat in collection group pat"
        final FromBuilder fromBuilder2 = core.fromBuilder(typeSystem);
        fromBuilder2.scan(generator.pat, generator.exp);
        fromBuilder2.distinct();
        fromBuilder.scan(generator.pat, fromBuilder2.build());
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
      fromBuilder2.yield_(core.recordOrAtom(typeSystem, requiredPats));

      // Add distinct if:
      // 1. The generator may produce duplicates (!generator.unique), or
      // 2. We're projecting away inner variables (not outer-bound).
      //
      // If patterns are projected away because they're DONE (already bound
      // from outer scans), we don't need distinct - the outer context provides
      // uniqueness via the join condition.
      // If patterns are projected away because they're not in `allPats` (inner
      // variables like y in "exists y"), we need distinct to avoid duplicates.
      boolean needsDistinct = !generator.unique;
      if (!needsDistinct) {
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
   * <p>For example, if the map is {y -> y$}, then the pattern (x, y, z) becomes
   * (x, y$, z).
   */
  private static Core.Pat renamePatterns(
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
            Generator g = getLast(cache.generators.get(pat));
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
