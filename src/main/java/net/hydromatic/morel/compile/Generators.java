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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.Iterables.getLast;
import static java.util.Objects.requireNonNull;
import static net.hydromatic.morel.ast.CoreBuilder.core;
import static net.hydromatic.morel.util.Static.skip;
import static net.hydromatic.morel.util.Static.skipLast;
import static net.hydromatic.morel.util.Static.transformEager;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.CoreBuilder;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.RangeExtent;
import net.hydromatic.morel.type.RecordLikeType;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.Ord;
import net.hydromatic.morel.util.Pair;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Implementations of {@link Generator}, and supporting methods. */
class Generators {
  private Generators() {}

  static boolean maybeGenerator(
      Cache cache, Core.Pat pat, boolean ordered, List<Core.Exp> constraints) {
    if (maybeElem(cache, pat, ordered, constraints)) {
      return true;
    }

    if (maybePoint(cache, pat, ordered, constraints)) {
      return true;
    }

    if (maybeRange(cache, pat, ordered, constraints)) {
      return true;
    }

    if (maybeExists(cache, pat, constraints)) {
      return true;
    }

    if (maybeUserFunction(cache, pat, ordered, constraints)) {
      return true;
    }

    return maybeUnion(cache, pat, ordered, constraints);
  }

  /**
   * For each predicate "exists ... where pat ...", adds a generator for "pat".
   *
   * <p>Because we're in core, {@code exists} has been translated to a call to
   * {@link BuiltIn#RELATIONAL_NON_EMPTY}. The above query will look like
   * "Relational.nonEmpty (from ... where pat ...)".
   *
   * <p>Pattern can occur in other places than a {@code where} clause, but it
   * must be in a location that is <b>monotonic</b>. That is, where adding a
   * value to the generator can never cause the query to emit fewer rows.
   */
  private static boolean maybeExists(
      Cache cache, Core.Pat pat, List<Core.Exp> constraints) {
    constraint_loop:
    for (int j = 0; j < constraints.size(); j++) {
      final Core.Exp constraint = constraints.get(j);
      if (constraint.isCallTo(BuiltIn.RELATIONAL_NON_EMPTY)) {
        final Core.Apply apply = (Core.Apply) constraint;
        if (apply.arg instanceof Core.From) {
          final Core.From from = (Core.From) apply.arg;

          // Create a copy of constraints with this constraint removed.
          // When we encounter a "where" step, we will add more constraints.
          final List<Core.Exp> constraints2 = new ArrayList<>(constraints);
          //noinspection SuspiciousListRemoveInLoop
          constraints2.remove(j);

          for (Core.FromStep step : from.steps) {
            switch (step.op) {
              case SCAN:
                break;
              case WHERE:
                constraints2.add(((Core.Where) step).exp);
                if (maybeGenerator(cache, pat, false, constraints2)) {
                  return true;
                }
                break;
              default:
                continue constraint_loop;
            }
          }
        }
      }
    }
    return false;
  }

  /**
   * Tries to invert user-defined function predicates using PredicateInverter.
   *
   * <p>For example, given a constraint {@code path_1_1 p} where {@code
   * path_1_1} is a recursive function defining transitive closure, this method
   * attempts to invert it into a generator using {@code Relational.iterate}.
   *
   * @param cache the generator cache
   * @param pat the pattern to generate values for
   * @param ordered whether the generator should preserve order
   * @param constraints the list of predicates to potentially invert
   * @return true if a finite generator was successfully created
   */
  private static boolean maybeUserFunction(
      Cache cache, Core.Pat pat, boolean ordered, List<Core.Exp> constraints) {
    // Get the named patterns we need to generate
    final List<Core.NamedPat> goalPats = pat.expand();

    for (Core.Exp constraint : constraints) {
      // Check if constraint is something we can potentially invert
      // The constraint might be:
      // 1. User-defined function call: APPLY where fn.op == ID (e.g., "path p")
      // 2. Pre-expanded function body: CASE wrapping an orelse pattern
      //    The Inliner expands "path p" to:
      //    "case p of (x, y) => edge(x,y) orelse exists..."
      // 3. Direct orelse pattern (less common)

      // Unwrap CASE expressions to get to the actual predicate body
      Core.Exp effectiveConstraint = constraint;
      if (constraint.op == Op.CASE) {
        Core.Case caseExp = (Core.Case) constraint;
        // Check if this is a single-arm case (function application pattern)
        if (caseExp.matchList.size() == 1) {
          // Use the body of the single match arm
          effectiveConstraint = caseExp.matchList.get(0).exp;
        }
      }

      if (effectiveConstraint.op == Op.APPLY) {
        Core.Apply apply = (Core.Apply) effectiveConstraint;

        // Handle four cases:
        // 1. User-defined function calls: fn.op == ID (e.g., "path p")
        // 2. Pre-expanded function bodies: orelse patterns (after Inliner)
        // 3. Andalso patterns: conjunctions that can be partially inverted
        //
        // NOTE: We skip inlined functions (Op.FN) because they include built-in
        // generators like 'range' and identity functions like 'mustBeList' that
        // shouldn't be inverted. Disabling this prevents NullPointerException
        // in regex-example.smli where these functions are used in
        // comprehensions.
        boolean isUserFunctionCall = apply.fn.op == Op.ID;
        boolean isOrElsePattern = apply.isCallTo(BuiltIn.Z_ORELSE);
        boolean isAndalsoPattern = apply.isCallTo(BuiltIn.Z_ANDALSO);

        if (isUserFunctionCall || isOrElsePattern || isAndalsoPattern) {
          // Build generators map from existing cache generators
          final Map<Core.NamedPat, PredicateInverter.Generator> generators =
              new LinkedHashMap<>();
          cache.generators.forEach(
              (namedPat, generator) ->
                  generators.put(
                      namedPat,
                      new PredicateInverter.Generator(
                          namedPat,
                          generator.exp,
                          generator.cardinality,
                          ImmutableList.of(),
                          generator.freePats)));

          // Build FunctionRegistry from environment for predicate inversion
          final FunctionRegistry functionRegistry =
              FunctionAnalyzer.buildRegistryFromEnvironment(
                  cache.typeSystem, cache.env);

          // Try to invert the predicate
          PredicateInverter.Result result =
              PredicateInverter.invert(
                  cache.typeSystem,
                  cache.env,
                  constraint,
                  goalPats,
                  generators,
                  functionRegistry);

          // Check if inversion succeeded with a finite generator
          if (result.generator.cardinality != Generator.Cardinality.INFINITE) {
            // Create a UserFunctionGenerator from the result.
            // Use the original 'pat' parameter, not result.generator.goalPat.
            // When CASE handling destructures 'p' into '(x_1, y_1)', the
            // generator produces tuples that match the original pattern 'p',
            // so we must register for 'p' (which Expander is looking for).
            // Also track the original constraint so it can be simplified to
            // true in the WHERE clause.
            UserFunctionGenerator.create(
                cache, ordered, pat, result.generator.expression, constraint);
            return true;
          }
        }
      }
    }
    return false;
  }

  /**
   * Returns a list without the {@code i}<sup>th</sup> element.
   *
   * <p>For example, {@code skipMid(["a", "b", "c"], 1)} evaluates to {@code
   * ["a", "c"]}.
   */
  // TODO: Improve this method if used, remove it if not
  static <E> List<E> skipMid(List<E> list, int i) {
    if (i == 0) {
      return skip(list);
    } else if (i == list.size() - 1) {
      return skipLast(list);
    } else {
      final ImmutableList.Builder<E> list2 = ImmutableList.builder();
      for (int j = 0; j < list.size(); j++) {
        if (j != i) {
          list2.add(list.get(j));
        }
      }
      return list2.build();
    }
  }

  /**
   * Tries to inline a function call if the function body is an {@code elem}
   * predicate.
   *
   * <p>For example, given {@code edge2(z, y)} where {@code edge2} is defined as
   * {@code fun edge2 (x, y) = {x, y} elem edges2}, returns {@code {z, y} elem
   * edges2}.
   *
   * @param cache Cache containing environment for function lookup
   * @param predicate The predicate to potentially inline
   * @return The inlined expression if predicate is a function call with elem
   *     body, or null otherwise
   */
  private static Core.@Nullable Exp tryInlineFunctionToElem(
      Cache cache, Core.Exp predicate) {
    // Check if predicate is a function call: fn(arg)
    if (predicate.op != Op.APPLY) {
      return null;
    }
    final Core.Apply apply = (Core.Apply) predicate;

    // Check if fn is a user-defined function (ID reference)
    if (apply.fn.op != Op.ID) {
      return null;
    }
    final Core.Id fnId = (Core.Id) apply.fn;

    // Look up function binding in environment
    final Binding binding = cache.env.getOpt(fnId.idPat);
    if (binding == null || binding.exp == null) {
      return null;
    }

    // Check if binding is a function
    if (binding.exp.op != Op.FN) {
      return null;
    }
    final Core.Fn fn = (Core.Fn) binding.exp;

    // Extract the actual body and parameter pattern.
    // Functions like `fun edge (x, y) = (x, y) elem edges` compile to:
    //   fn v => case v of (x, y) => op elem (...)
    // We need to unwrap the case to get the real pattern and body.
    Core.Pat paramPat = fn.idPat;
    Core.Exp body = fn.exp;

    if (body.op == Op.CASE) {
      final Core.Case caseExp = (Core.Case) body;
      // Check if case is matching on the function parameter
      if (caseExp.exp.op == Op.ID) {
        final Core.Id caseId = (Core.Id) caseExp.exp;
        if (caseId.idPat.equals(fn.idPat) && caseExp.matchList.size() == 1) {
          // Single-arm case matching on parameter - extract the arm
          final Core.Match match = caseExp.matchList.get(0);
          paramPat = match.pat;
          body = match.exp;
        }
      }
    }

    // Check if function body is an elem predicate
    if (!body.isCallTo(BuiltIn.OP_ELEM)) {
      return null;
    }

    // Build substitution from param pattern to call argument
    final Map<Core.Id, Core.Exp> subst =
        buildPatternSubstitution(paramPat, apply.arg);
    if (subst == null) {
      return null;
    }

    // Apply substitution to function body
    return Replacer.substitute(cache.typeSystem, subst, body);
  }

  /**
   * Builds a substitution map from a pattern to an expression.
   *
   * <p>For example, given pattern {@code (x, y)} and expression {@code (z, w)},
   * returns {@code {x -> z, y -> w}}.
   */
  private static @Nullable Map<Core.Id, Core.Exp> buildPatternSubstitution(
      Core.Pat pat, Core.Exp exp) {
    final ImmutableMap.Builder<Core.Id, Core.Exp> subst =
        ImmutableMap.builder();
    if (!buildPatternSubstitution(pat, exp, subst)) {
      return null;
    }
    return subst.build();
  }

  private static boolean buildPatternSubstitution(
      Core.Pat pat,
      Core.Exp exp,
      ImmutableMap.Builder<Core.Id, Core.Exp> subst) {
    switch (pat.op) {
      case ID_PAT:
        final Core.IdPat idPat = (Core.IdPat) pat;
        subst.put(core.id(idPat), exp);
        return true;

      case TUPLE_PAT:
        final Core.TuplePat tuplePat = (Core.TuplePat) pat;
        if (exp.op != Op.TUPLE) {
          return false;
        }
        final Core.Tuple tuple = (Core.Tuple) exp;
        if (tuplePat.args.size() != tuple.args.size()) {
          return false;
        }
        for (int i = 0; i < tuplePat.args.size(); i++) {
          if (!buildPatternSubstitution(
              tuplePat.args.get(i), tuple.args.get(i), subst)) {
            return false;
          }
        }
        return true;

      case RECORD_PAT:
        final Core.RecordPat recordPat = (Core.RecordPat) pat;
        if (exp.op != Op.TUPLE) {
          return false;
        }
        final Core.Tuple recordTuple = (Core.Tuple) exp;
        if (recordPat.args.size() != recordTuple.args.size()) {
          return false;
        }
        for (int i = 0; i < recordPat.args.size(); i++) {
          if (!buildPatternSubstitution(
              recordPat.args.get(i), recordTuple.args.get(i), subst)) {
            return false;
          }
        }
        return true;

      default:
        return false;
    }
  }

  /**
   * For each predicate "pat elem collection", adds a generator based on
   * "collection".
   *
   * <p>Also handles function calls that inline to {@code elem} patterns.
   */
  static boolean maybeElem(
      Cache cache,
      Core.Pat goalPat,
      boolean ordered,
      List<Core.Exp> predicates) {
    for (Core.Exp predicate : predicates) {
      // First, try to inline function calls that resolve to elem patterns
      final Core.Exp inlined = tryInlineFunctionToElem(cache, predicate);
      Core.Exp effectivePredicate = inlined != null ? inlined : predicate;

      // Handle CASE expressions that result from function inlining.
      // After Inliner, `edge p` may become `case p of (x,y) => (x,y) elem
      // edges`.
      // In this case, the CASE is matching on goalPat (p), and the body
      // contains an elem call that we should use as the generator.
      if (effectivePredicate.op == Op.CASE) {
        Core.Case caseExp = (Core.Case) effectivePredicate;
        if (caseExp.matchList.size() == 1) {
          // Check if the CASE is matching on our goal pattern
          if (containsRef(caseExp.exp, goalPat)) {
            // The body might be:
            // 1. Direct elem: (x, y) elem edges
            // 2. Conjunction: (x, y) elem edges andalso x <> y
            Core.Exp body = caseExp.matchList.get(0).exp;

            // Find an elem call in the body
            Core.Exp elemCall = findElemCall(body);
            if (elemCall != null) {
              // The collection is the generator source for goalPat
              final Core.Exp collection = elemCall.arg(1);
              // The goalPat type matches the collection element type
              CollectionGenerator.create(cache, ordered, goalPat, collection);
              return true;
            }
          }
          // Fall through to unwrap and check other patterns
          effectivePredicate = caseExp.matchList.get(0).exp;
        }
      }

      if (effectivePredicate.isCallTo(BuiltIn.OP_ELEM)) {
        if (containsRef(effectivePredicate.arg(0), goalPat)) {
          // If predicate is "(p, q) elem links", first create a generator
          // for "p2 elem links", where "p2 as (p, q)".
          final Core.Exp collection = effectivePredicate.arg(1);
          final ExpPat expPat =
              requireNonNull(
                  wholePat(cache.typeSystem, effectivePredicate.arg(0)));
          CollectionGenerator.create(cache, ordered, expPat.pat, collection);
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Finds an {@code elem} call in an expression, searching through conjunctions
   * and other patterns.
   *
   * <p>For example, given {@code (x, y) elem edges andalso x <> y}, returns the
   * {@code (x, y) elem edges} call.
   */
  private static Core.@Nullable Exp findElemCall(Core.Exp exp) {
    if (exp.isCallTo(BuiltIn.OP_ELEM)) {
      return exp;
    }
    // Search through andalso conjunctions
    if (exp.isCallTo(BuiltIn.Z_ANDALSO)) {
      Core.Exp left = findElemCall(exp.arg(0));
      if (left != null) {
        return left;
      }
      return findElemCall(exp.arg(1));
    }
    return null;
  }

  static boolean maybePoint(
      Cache cache, Core.Pat pat, boolean ordered, List<Core.Exp> constraints) {
    final Core.@Nullable Exp value = point(pat, constraints);
    if (value != null) {
      PointGenerator.create(cache, pat, ordered, value);
      return true;
    }
    return false;
  }

  /**
   * Creates an expression that generates a values from several generators.
   *
   * @param ordered If true, generate a `list`, otherwise a `bag`
   * @param generators Generators
   */
  @SuppressWarnings("UnusedReturnValue")
  static Generator generateUnion(
      Cache cache, boolean ordered, List<Generator> generators) {
    final Core.Exp fn =
        core.functionLiteral(
            cache.typeSystem,
            ordered ? BuiltIn.LIST_CONCAT : BuiltIn.BAG_CONCAT);
    final Type collectionType = generators.get(0).exp.type;
    Core.Exp arg =
        core.list(
            cache.typeSystem,
            collectionType.elementType(),
            transformEager(generators, g -> g.exp));
    final Core.Exp exp = core.apply(Pos.ZERO, collectionType, fn, arg);
    final Set<Core.NamedPat> freePats =
        SuchThatShuttle.freePats(cache.typeSystem, exp);
    return cache.add(new UnionGenerator(exp, freePats, generators));
  }

  static boolean maybeUnion(
      Cache cache, Core.Pat pat, boolean ordered, List<Core.Exp> constraints) {
    next_constraint:
    for (Core.Exp constraint : constraints) {
      if (constraint.isCallTo(BuiltIn.Z_ORELSE)) {
        final List<Core.Exp> orList = core.decomposeOr(constraint);
        final List<Generator> generators = new ArrayList<>();
        for (Core.Exp exp : orList) {
          final List<Core.Exp> andList = core.decomposeAnd(exp);
          if (!maybeGenerator(cache, pat, ordered, andList)) {
            continue next_constraint;
          }
          generators.add(getLast(cache.generators.get((Core.NamedPat) pat)));
        }
        generateUnion(cache, ordered, generators);
        return true;
      }
    }
    return false;
  }

  static boolean maybeRange(
      Cache cache, Core.Pat pat, boolean ordered, List<Core.Exp> constraints) {
    if (pat.type != PrimitiveType.INT) {
      return false;
    }
    final @Nullable Pair<Core.Exp, Boolean> lower =
        lowerBound(pat, constraints);
    if (lower == null) {
      return false;
    }
    final @Nullable Pair<Core.Exp, Boolean> upper =
        upperBound(pat, constraints);
    if (upper == null) {
      return false;
    }
    Generators.generateRange(
        cache,
        ordered,
        (Core.NamedPat) pat,
        lower.left,
        lower.right,
        upper.left,
        upper.right);
    return true;
  }

  /**
   * Creates an expression that generates a range of integer values.
   *
   * <p>For example, {@code generateRange(3, true, 8, false)} generates a range
   * {@code 3 < x <= 8}, which yields the values {@code [4, 5, 6, 7, 8]}.
   *
   * @param ordered If true, generate a `list`, otherwise a `bag`
   * @param pat Pattern
   * @param lower Lower bound
   * @param lowerStrict Whether the lower bound is strict (exclusive): true for
   *     {@code x > lower}, false for {@code x >= lower}
   * @param upper Upper bound
   * @param upperStrict Whether the upper bound is strict (exclusive): true for
   *     {@code x < upper}, false for {@code x <= upper}
   */
  static Generator generateRange(
      Cache cache,
      boolean ordered,
      Core.NamedPat pat,
      Core.Exp lower,
      boolean lowerStrict,
      Core.Exp upper,
      boolean upperStrict) {
    // For x > lower, we want x >= lower + 1
    final TypeSystem typeSystem = cache.typeSystem;
    final Core.Exp lower2 =
        lowerStrict
            ? core.call(
                typeSystem,
                BuiltIn.Z_PLUS_INT,
                lower,
                core.intLiteral(BigDecimal.ONE))
            : lower;
    // For x < upper, we want x <= upper - 1
    final Core.Exp upper2 =
        upperStrict
            ? core.call(
                typeSystem,
                BuiltIn.Z_MINUS_INT,
                upper,
                core.intLiteral(BigDecimal.ONE))
            : upper;

    // List.tabulate(upper - lower + 1, fn k => lower + k)
    final Type type = PrimitiveType.INT;
    final Core.IdPat kPat = core.idPat(type, "k", 0);
    final Core.Exp upperMinusLower =
        core.call(typeSystem, BuiltIn.OP_MINUS, type, Pos.ZERO, upper2, lower2);
    final Core.Exp count =
        core.call(
            typeSystem,
            BuiltIn.OP_PLUS,
            type,
            Pos.ZERO,
            upperMinusLower,
            core.intLiteral(BigDecimal.ONE));
    final Core.Exp lowerPlusK =
        core.call(typeSystem, BuiltIn.Z_PLUS_INT, lower2, core.id(kPat));
    final Core.Fn fn = core.fn(typeSystem.fnType(type, type), kPat, lowerPlusK);
    BuiltIn tabulate = ordered ? BuiltIn.LIST_TABULATE : BuiltIn.BAG_TABULATE;
    Core.Apply exp = core.call(typeSystem, tabulate, type, Pos.ZERO, count, fn);
    final Core.Exp simplified = Simplifier.simplify(typeSystem, exp);
    final Set<Core.NamedPat> freePats =
        SuchThatShuttle.freePats(typeSystem, simplified);
    return cache.add(
        new RangeGenerator(
            typeSystem,
            pat,
            simplified,
            freePats,
            lower,
            lowerStrict,
            upper,
            upperStrict));
  }

  /** Returns an extent generator, or null if expression is not an extent. */
  @SuppressWarnings("UnusedReturnValue")
  public static boolean maybeExtent(Cache cache, Core.Pat pat, Core.Exp exp) {
    if (exp.isExtent()) {
      ExtentGenerator.create(cache, pat, exp);
      return true;
    }
    return false;
  }

  /** If there is a predicate "pat = exp" or "exp = pat", returns "exp". */
  static Core.@Nullable Exp point(Core.Pat pat, List<Core.Exp> predicates) {
    for (Core.Exp constraint : predicates) {
      if (constraint.isCallTo(BuiltIn.OP_EQ)) {
        if (references(constraint.arg(0), pat)) {
          return constraint.arg(1);
        }
        if (references(constraint.arg(1), pat)) {
          return constraint.arg(0);
        }
      }
    }
    return null;
  }

  /**
   * If there is a predicate "pat &gt; exp" or "pat &ge; exp", returns "exp" and
   * whether the comparison is strict.
   *
   * <p>We do not attempt to find the strongest such constraint. Clearly "p &gt;
   * 10" is stronger than "p &ge; 0". But is "p &gt; x" stronger than "p &ge;
   * y"? If the goal is to convert an infinite generator to a finite generator,
   * any constraint is good enough.
   */
  static @Nullable Pair<Core.Exp, Boolean> lowerBound(
      Core.Pat pat, List<Core.Exp> predicates) {
    for (Core.Exp constraint : predicates) {
      switch (constraint.builtIn()) {
        case OP_GT:
        case OP_GE:
          if (references(constraint.arg(0), pat)) {
            // "p > e" -> (strict, e); "p >= e" -> (non-strict, e).
            final boolean strict = constraint.builtIn() == BuiltIn.OP_GT;
            return Pair.of(constraint.arg(1), strict);
          }
          break;
        case OP_LT:
        case OP_LE:
          if (references(constraint.arg(1), pat)) {
            // "e < p" -> (strict, e); "e <= p" -> (non-strict, e).
            final boolean strict = constraint.builtIn() == BuiltIn.OP_LT;
            return Pair.of(constraint.arg(0), strict);
          }
          break;
      }
    }
    return null;
  }

  /**
   * If there is a constraint "pat &lt; exp" or "pat &le; exp", returns "exp"
   * and whether the comparison is strict.
   *
   * <p>Analogous to {@link #lowerBound(Core.Pat, List)}.
   */
  static @Nullable Pair<Core.Exp, Boolean> upperBound(
      Core.Pat pat, List<Core.Exp> predicates) {
    for (Core.Exp constraint : predicates) {
      switch (constraint.builtIn()) {
        case OP_LT:
        case OP_LE:
          if (references(constraint.arg(0), pat)) {
            // "p < e" -> (strict, e); "p <= e" -> (non-strict, e).
            final boolean strict = constraint.builtIn() == BuiltIn.OP_LT;
            return Pair.of(constraint.arg(1), strict);
          }
          break;
        case OP_GT:
        case OP_GE:
          if (references(constraint.arg(1), pat)) {
            // "e > p" -> (strict, e); "e >= p" -> (non-strict, e).
            final boolean strict = constraint.builtIn() == BuiltIn.OP_GT;
            return Pair.of(constraint.arg(0), strict);
          }
          break;
      }
    }
    return null;
  }

  /**
   * Returns whether an expression is a reference ({@link Core.Id}) to a
   * pattern.
   */
  private static boolean references(Core.Exp arg, Core.Pat pat) {
    return arg.op == Op.ID && ((Core.Id) arg).idPat.equals(pat);
  }

  /**
   * Returns whether an expression contains a reference to a pattern. If the
   * pattern is {@link Core.IdPat} {@code p}, returns true for {@link Core.Id}
   * {@code p} and tuple {@code (p, q)}.
   */
  private static boolean containsRef(Core.Exp exp, Core.Pat pat) {
    switch (exp.op) {
      case ID:
        return ((Core.Id) exp).idPat.equals(pat);

      case TUPLE:
        for (Core.Exp arg : ((Core.Tuple) exp).args) {
          if (containsRef(arg, pat)) {
            return true;
          }
        }
        return false;

      default:
        return false;
    }
  }

  /**
   * Creates a pattern that encompasses a whole expression.
   *
   * <p>Returns null if {@code exp} does not contain {@code pat}; you should
   * have called {@link #containsRef(Core.Exp, Core.Pat)} first.
   *
   * <p>Examples:
   *
   * <ul>
   *   <li>{@code wholePat(id(p), idPat(p)} returns pattern {@code idPat(p)} and
   *       expression {@code id(p)}
   *   <li>{@code wholePat(tuple(id(p), id(q)), idPat(p)} returns pattern {@code
   *       idPat(r)} and expression {@code r.1}
   *   <li>{@code wholePat(tuple(id(p), id(p)), idPat(p)} is illegal because it
   *       contains {@code p} more than once
   *   <li>{@code wholePat(tuple(id(p), literal("elrond")), idPat(p))} returns
   *       pattern {@code idPat(p)}, expression {@code id(p)}, and a filter
   *       {@code id(q) = literal("elrond")}. We currently ignore filters.
   * </ul>
   */
  private static @Nullable ExpPat wholePat(
      TypeSystem typeSystem, Core.Exp exp) {
    switch (exp.op) {
      case ID:
        Core.Id id1 = (Core.Id) exp;
        return ExpPat.of(exp, id1.idPat);

      case TUPLE:
        int slot = -1;
        final List<Core.Pat> patList = new ArrayList<>();
        for (Ord<Core.Exp> arg : Ord.zip(((Core.Tuple) exp).args)) {
          final @Nullable ExpPat p = wholePat(typeSystem, arg.e);
          if (p != null) {
            slot = arg.i;
          }
          patList.add(toPat(arg.e));
        }
        if (slot < 0) {
          return null;
        }
        final Core.Pat tuplePat =
            (exp.type instanceof RecordType)
                ? core.recordPat((RecordType) exp.type, patList)
                : core.tuplePat((RecordLikeType) exp.type, patList);
        final Core.IdPat idPat = core.idPat(exp.type, "z", 0); // "r as (p, q)"
        final Core.Id id = core.id(idPat);
        final Core.Exp exp2 = core.field(typeSystem, id, slot);
        return ExpPat.of(exp2, tuplePat);

      default:
        return null;
    }
  }

  /**
   * Converts an expression to the equivalent pattern.
   *
   * @see Op#toPat()
   */
  private static Core.Pat toPat(Core.Exp exp) {
    switch (exp.op) {
      case ID:
        return ((Core.Id) exp).idPat;

      case TUPLE:
        final Core.Tuple tuple = (Core.Tuple) exp;
        if (tuple.type.op() == Op.RECORD_TYPE) {
          return core.recordPat(
              (RecordType) tuple.type(),
              transformEager(tuple.args, Generators::toPat));
        } else {
          return core.tuplePat(
              tuple.type(), transformEager(tuple.args, Generators::toPat));
        }

      case BOOL_LITERAL:
      case CHAR_LITERAL:
      case INT_LITERAL:
      case REAL_LITERAL:
      case STRING_LITERAL:
      case UNIT_LITERAL:
        final Core.Literal literal = (Core.Literal) exp;
        return core.literalPat(exp.op.toPat(), exp.type, literal.value);

      default:
        throw new AssertionError("cannot convert " + exp + " to pattern");
    }
  }

  /**
   * Generator that generates a range of integers from {@code lower} to {@code
   * upper}.
   */
  static class RangeGenerator extends Generator {
    private final TypeSystem typeSystem;
    private final Core.Exp lower;
    private final Core.Exp upper;

    RangeGenerator(
        TypeSystem typeSystem,
        Core.NamedPat pat,
        Core.Exp exp,
        Iterable<? extends Core.NamedPat> freePats,
        Core.Exp lower,
        boolean ignoreLowerStrict,
        Core.Exp upper,
        boolean ignoreUpperStrict) {
      super(exp, freePats, pat, Cardinality.FINITE);
      this.typeSystem = typeSystem;
      this.lower = lower;
      this.upper = upper;
    }

    @Override
    Core.Exp simplify(Core.Pat pat, Core.Exp exp) {
      // Simplify "p > lower && p < upper && other_predicates"
      // Only remove the bounds predicates, keep other predicates as filter
      if (exp.isCallTo(BuiltIn.Z_ANDALSO)) {
        final List<Core.Exp> ands = core.decomposeAnd(exp);
        final Pair<Core.Exp, Boolean> lowerBound = lowerBound(pat, ands);
        final Pair<Core.Exp, Boolean> upperBound = upperBound(pat, ands);
        if (lowerBound != null && upperBound != null) {
          boolean boundsMatch =
              lowerBound.left.equals(this.lower)
                  && upperBound.left.equals(this.upper);
          boolean boundsImplied =
              lowerBound.left.isConstant()
                  && upperBound.left.isConstant()
                  && this.lower.isConstant()
                  && this.upper.isConstant()
                  && gt(lowerBound.left, this.lower, lowerBound.right)
                  && gt(this.upper, upperBound.left, upperBound.right);

          if (boundsMatch || boundsImplied) {
            // Remove bounds predicates but keep other predicates
            final List<Core.Exp> remaining = new ArrayList<>();
            for (Core.Exp predicate : ands) {
              if (!isBoundPredicate(pat, predicate)) {
                remaining.add(predicate);
              }
            }
            // Return remaining predicates as a conjunction, or true if none
            return core.andAlso(typeSystem, remaining);
          }
        }
      }
      return exp;
    }

    /**
     * Checks if predicate is a bound constraint for the given pattern. Returns
     * true for predicates like "p &gt; e", "p &lt; e", "p &gt;= e", "p &lt;=
     * e".
     */
    private static boolean isBoundPredicate(Core.Pat pat, Core.Exp predicate) {
      switch (predicate.builtIn()) {
        case OP_GT:
        case OP_GE:
        case OP_LT:
        case OP_LE:
          return references(predicate.arg(0), pat)
              || references(predicate.arg(1), pat);
        default:
          return false;
      }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static boolean gt(Core.Exp left, Core.Exp right, boolean strict) {
      final Comparable leftVal = ((Core.Literal) left).value;
      final Comparable rightVal = ((Core.Literal) right).value;
      int c = leftVal.compareTo(rightVal);
      return strict ? c > 0 : c >= 0;
    }
  }

  /**
   * Generator that generates a range of integers from {@code lower} to {@code
   * upper}.
   */
  static class PointGenerator extends Generator {
    private final Core.Exp point;

    PointGenerator(
        Core.Pat pat,
        Core.Exp exp,
        Iterable<? extends Core.NamedPat> freePats,
        Core.Exp point) {
      super(exp, freePats, pat, Cardinality.SINGLE);
      this.point = point;
    }

    /**
     * Creates an expression that generates a single value.
     *
     * @param ordered If true, generate a `list`, otherwise a `bag`
     * @param lower Lower bound
     */
    @SuppressWarnings("UnusedReturnValue")
    static Generator create(
        Cache cache, Core.Pat pat, boolean ordered, Core.Exp lower) {
      final Core.Exp exp =
          ordered
              ? core.list(cache.typeSystem, lower)
              : core.bag(cache.typeSystem, lower);
      final Set<Core.NamedPat> freePats =
          SuchThatShuttle.freePats(cache.typeSystem, exp);
      return cache.add(new PointGenerator(pat, exp, freePats, lower));
    }

    @Override
    Core.Exp simplify(Core.Pat pat, Core.Exp exp) {
      // Simplify "p = point" to true.
      if (exp.isCallTo(BuiltIn.OP_EQ)) {
        final Core.@Nullable Exp point = point(pat, ImmutableList.of(exp));
        if (point != null) {
          if (point.equals(this.point)) {
            return core.boolLiteral(true);
          }
          if (point.isConstant() && this.point.isConstant()) {
            return core.boolLiteral(false);
          }
        }
      }
      return exp;
    }
  }

  /** Generator that generates a union of several underlying generators. */
  static class UnionGenerator extends Generator {
    private final List<Generator> generators;

    UnionGenerator(
        Core.Exp exp,
        Iterable<? extends Core.NamedPat> freePats,
        List<Generator> generators) {
      super(exp, freePats, firstGenerator(generators).pat, Cardinality.FINITE);
      this.generators = ImmutableList.copyOf(generators);
    }

    private static Generator firstGenerator(List<Generator> generators) {
      checkArgument(generators.size() >= 2);
      return generators.get(0);
    }

    @Override
    Core.Exp simplify(Core.Pat pat, Core.Exp exp) {
      // First, check if this is an orelse expression that matches our union
      if (exp.isCallTo(BuiltIn.Z_ORELSE)) {
        final List<Core.Exp> orList = core.decomposeOr(exp);
        // Check if each branch of the orelse can be simplified by one of our
        // generators. If all branches simplify to true, the whole orelse is
        // true.
        if (orList.size() == generators.size()) {
          boolean allSimplified = true;
          for (int i = 0; i < orList.size(); i++) {
            Core.Exp branch = orList.get(i);
            Core.Exp simplified = generators.get(i).simplify(pat, branch);
            if (simplified.op != Op.BOOL_LITERAL
                || !Boolean.TRUE.equals(((Core.Literal) simplified).value)) {
              allSimplified = false;
              break;
            }
          }
          if (allSimplified) {
            return core.boolLiteral(true);
          }
        }
      }

      // Fall back to delegating to constituent generators
      for (Generator generator : generators) {
        exp = generator.simplify(pat, exp);
      }
      return exp;
    }
  }

  /**
   * Generator that generates all values of a type. For most types it is
   * infinite.
   */
  static class ExtentGenerator extends Generator {
    private ExtentGenerator(
        Core.Pat pat,
        Core.Exp exp,
        Iterable<? extends Core.NamedPat> freePats,
        Cardinality cardinality) {
      super(exp, freePats, pat, cardinality);
    }

    /** Creates an extent generator. */
    @SuppressWarnings("UnusedReturnValue")
    static Generator create(Cache cache, Core.Pat pat, Core.Exp exp) {
      final RangeExtent rangeExtent = exp.getRangeExtent();
      final Cardinality cardinality =
          rangeExtent.iterable == null
              ? Cardinality.INFINITE
              : Cardinality.FINITE;
      final Set<Core.NamedPat> freePats =
          SuchThatShuttle.freePats(cache.typeSystem, exp);
      return cache.add(new ExtentGenerator(pat, exp, freePats, cardinality));
    }

    @Override
    Core.Exp simplify(Core.Pat pat, Core.Exp exp) {
      return exp;
    }
  }

  /**
   * Generator that returns the contents of a collection.
   *
   * <p>The inverse of {@code p elem collection} is {@code collection}.
   */
  static class CollectionGenerator extends Generator {
    final Core.Exp collection;

    private CollectionGenerator(
        Core.Pat pat,
        Core.Exp collection,
        Iterable<? extends Core.NamedPat> freePats) {
      super(collection, freePats, pat, Cardinality.FINITE);
      this.collection = collection;
      checkArgument(collection.type.isCollection());
    }

    @SuppressWarnings("UnusedReturnValue")
    static CollectionGenerator create(
        Cache cache, boolean ordered, Core.Pat pat, Core.Exp collection) {
      // Convert the collection to a list or bag, per "ordered".
      final TypeSystem typeSystem = cache.typeSystem;
      final Core.Exp collection2 =
          CoreBuilder.withOrdered(ordered, collection, typeSystem);
      final Set<Core.NamedPat> freePats =
          SuchThatShuttle.freePats(cache.typeSystem, collection2);
      return cache.add(new CollectionGenerator(pat, collection2, freePats));
    }

    @Override
    Core.Exp simplify(Core.Pat pat, Core.Exp exp) {
      if (exp.isCallTo(BuiltIn.OP_ELEM)
          && references(exp.arg(0), pat)
          && exp.arg(1).equals(this.collection)) {
        // "p elem collection" simplifies to "true"
        return core.boolLiteral(true);
      }
      return exp;
    }
  }

  /**
   * Generator that was created by inverting a user-defined function predicate.
   *
   * <p>For example, if {@code path(p)} is inverted to produce an {@code
   * iterate} expression, this generator tracks the original predicate and
   * simplifies it to {@code true} when encountered in a WHERE clause.
   */
  static class UserFunctionGenerator extends Generator {
    /** The original predicate that was inverted to create this generator. */
    final Core.Exp originalPredicate;

    private UserFunctionGenerator(
        Core.Pat pat,
        Core.Exp generatorExp,
        Core.Exp originalPredicate,
        Iterable<? extends Core.NamedPat> freePats) {
      super(generatorExp, freePats, pat, Cardinality.FINITE);
      this.originalPredicate = requireNonNull(originalPredicate);
    }

    @SuppressWarnings("UnusedReturnValue")
    static UserFunctionGenerator create(
        Cache cache,
        boolean ordered,
        Core.Pat pat,
        Core.Exp generatorExp,
        Core.Exp originalPredicate) {
      // Convert the collection to a list or bag, per "ordered".
      final TypeSystem typeSystem = cache.typeSystem;
      final Core.Exp generatorExp2 =
          CoreBuilder.withOrdered(ordered, generatorExp, typeSystem);
      final Set<Core.NamedPat> freePats =
          SuchThatShuttle.freePats(typeSystem, generatorExp2);
      return cache.add(
          new UserFunctionGenerator(
              pat, generatorExp2, originalPredicate, freePats));
    }

    @Override
    Core.Exp simplify(Core.Pat pat, Core.Exp exp) {
      // If the expression matches the original predicate that was inverted,
      // simplify it to true (the generator already produces all valid values).
      if (matchesPredicate(exp, originalPredicate)) {
        return core.boolLiteral(true);
      }
      return exp;
    }

    /**
     * Checks if an expression matches the original predicate.
     *
     * <p>This handles structural matching, including the case where the
     * predicate is wrapped in a CASE expression (e.g., {@code case p of (x, y)
     * => ...}).
     */
    private boolean matchesPredicate(Core.Exp exp, Core.Exp predicate) {
      // Direct match
      if (exp.equals(predicate)) {
        return true;
      }
      // Handle CASE-wrapped predicates
      if (exp.op == Op.CASE && predicate.op == Op.CASE) {
        Core.Case expCase = (Core.Case) exp;
        Core.Case predCase = (Core.Case) predicate;
        if (expCase.matchList.size() == 1 && predCase.matchList.size() == 1) {
          return expCase
              .matchList
              .get(0)
              .exp
              .equals(predCase.matchList.get(0).exp);
        }
      }
      // For CASE expressions, compare the body of the single match arm
      if (exp.op == Op.CASE) {
        Core.Case caseExp = (Core.Case) exp;
        if (caseExp.matchList.size() == 1) {
          return matchesPredicate(caseExp.matchList.get(0).exp, predicate);
        }
      }
      if (predicate.op == Op.CASE) {
        Core.Case caseExp = (Core.Case) predicate;
        if (caseExp.matchList.size() == 1) {
          return matchesPredicate(exp, caseExp.matchList.get(0).exp);
        }
      }
      return false;
    }
  }

  /** Generators that have been created to date. */
  static class Cache {
    final TypeSystem typeSystem;
    final Environment env;
    final Multimap<Core.NamedPat, Generator> generators =
        MultimapBuilder.hashKeys().arrayListValues().build();

    Cache(TypeSystem typeSystem, Environment env) {
      this.typeSystem = requireNonNull(typeSystem);
      this.env = requireNonNull(env);
    }

    @Nullable
    Generator bestGenerator(Core.NamedPat namedPat) {
      Generator bestGenerator = null;
      for (Generator generator : generators.get(namedPat)) {
        bestGenerator = generator;
      }
      return bestGenerator;
    }

    /**
     * Registers a generator, adding it to the index of each constituent
     * pattern.
     *
     * <p>For example, if {@code g} is {@code CollectionGenerator((s, t) elem
     * links)}, then {@code add(g)} will add {@code (s, g), (t, g)} to the
     * generators index.
     */
    public <G extends Generator> G add(G generator) {
      for (Core.NamedPat namedPat : generator.pat.expand()) {
        generators.put(namedPat, generator);
      }
      return generator;
    }
  }

  /** An expression and a pattern. */
  static class ExpPat {
    final Core.Exp exp;
    final Core.Pat pat;

    ExpPat(Core.Exp exp, Core.Pat pat) {
      this.exp = requireNonNull(exp);
      this.pat = requireNonNull(pat);
    }

    static ExpPat of(Core.Exp exp, Core.Pat pat) {
      return new ExpPat(exp, pat);
    }

    @Override
    public String toString() {
      return "{exp " + exp + ", pat " + pat + "}";
    }
  }
}

// End Generators.java
