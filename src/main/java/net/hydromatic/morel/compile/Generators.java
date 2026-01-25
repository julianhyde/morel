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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.FromBuilder;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.ast.Visitor;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.ListType;
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

    if (maybeFunction(cache, pat, ordered, constraints)) {
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
   * Finds the index of goalPat within a tuple expression.
   *
   * <p>For example, if fnArg is (x, y) and goalPat is x, returns 0. If fnArg is
   * (x, y) and goalPat is y, returns 1. Returns -1 if fnArg is not a tuple or
   * goalPat is not found.
   */
  private static int findTupleComponent(Core.Exp fnArg, Core.Pat goalPat) {
    if (fnArg.op != Op.TUPLE) {
      return -1;
    }
    final Core.Tuple tuple = (Core.Tuple) fnArg;
    for (int i = 0; i < tuple.args.size(); i++) {
      Core.Exp arg = tuple.args.get(i);
      if (arg.op == Op.ID) {
        Core.Id id = (Core.Id) arg;
        if (goalPat instanceof Core.IdPat
            && id.idPat.name.equals(((Core.IdPat) goalPat).name)) {
          return i;
        }
      }
    }
    return -1;
  }

  /**
   * Creates a tuple pattern from a tuple expression.
   *
   * <p>For example, if fnArg is (x, y) where x and y are Ids referencing
   * IdPats, creates a TuplePat containing those IdPats.
   */
  private static Core.Pat createTuplePatFromArg(TypeSystem ts, Core.Exp fnArg) {
    if (fnArg.op != Op.TUPLE) {
      throw new IllegalArgumentException("Expected tuple: " + fnArg);
    }
    final Core.Tuple tuple = (Core.Tuple) fnArg;
    final List<Core.Pat> pats = new ArrayList<>();
    for (Core.Exp arg : tuple.args) {
      if (arg.op == Op.ID) {
        pats.add(((Core.Id) arg).idPat);
      } else {
        throw new IllegalArgumentException("Expected ID in tuple: " + arg);
      }
    }
    return core.tuplePat(ts, pats);
  }

  /**
   * Builds a projection expression that extracts a component from a collection
   * of tuples.
   *
   * <p>For example, to project the first component from a bag of (int * int):
   * {@code from t in collection yield #1 t}
   */
  private static Core.Exp buildTupleProjection(
      TypeSystem ts, Core.Exp collection, int componentIndex, Type resultType) {
    final Type elementType = collection.type.elementType();
    final FromBuilder fb = core.fromBuilder(ts);
    final Core.IdPat tPat = core.idPat(elementType, "t", 0);
    fb.scan(tPat, collection);
    fb.yield_(core.field(ts, core.id(tPat), componentIndex));
    return fb.build();
  }

  /**
   * Attempts to invert a user-defined recursive boolean function call into a
   * generator.
   *
   * <p>Handles functions following the bounded recursive pattern:
   *
   * <pre>{@code
   * fun path (x, y, n) =
   *   n > 0 andalso
   *   (edge (x, y) orelse
   *    exists z where edge (x, z) andalso path (z, y, n - 1))
   * }</pre>
   *
   * <p>A function is invertible if:
   *
   * <ul>
   *   <li>The constraint is a function call {@code f(arg)} where {@code f} is a
   *       user-defined function (not a built-in)
   *   <li>The function body has the form {@code n > 0 andalso (base orelse
   *       recursive)}
   *   <li>The bound parameter {@code n} has a guard {@code n > 0} and is
   *       decremented in the recursive call
   *   <li>The bound parameter is supplied as a constant at the call site
   *   <li>The base case is directly invertible (e.g., {@code {x, y} elem
   *       edges})
   *   <li>The recursive case contains a call to the same function
   * </ul>
   *
   * <p>When these conditions are met, generates a bounded iteration that
   * computes paths up to the specified depth, returning the union of all
   * intermediate results.
   *
   * @param cache The generator cache (requires environment for function lookup)
   * @param goalPat The pattern to generate values for
   * @param ordered Whether to generate a list (true) or bag (false)
   * @param constraints Boolean expressions that must be satisfied
   * @return true if a generator was created, false otherwise
   */
  static boolean maybeFunction(
      Cache cache,
      Core.Pat goalPat,
      boolean ordered,
      List<Core.Exp> constraints) {
    for (Core.Exp constraint : constraints) {
      // Step 1: Find function calls (Apply where fn is an Id, not a built-in)
      if (constraint.op != Op.APPLY) {
        System.err.println(
            "DEBUG maybeFunction: constraint not APPLY: " + constraint.op);
        continue;
      }
      final Core.Apply apply = (Core.Apply) constraint;
      if (apply.fn.op != Op.ID) {
        System.err.println("DEBUG maybeFunction: fn not ID: " + apply.fn.op);
        continue;
      }
      final Core.Id fnId = (Core.Id) apply.fn;
      final String fnName = fnId.idPat.name;
      System.err.println("DEBUG maybeFunction: found function call: " + fnName);

      // Step 2: Look up the function definition in the environment
      // Use getTop(name) instead of getOpt(idPat) because the idPat in the
      // call expression may have a different 'i' than the one in the definition
      final Binding binding = cache.env.getTop(fnName);
      if (binding == null) {
        System.err.println(
            "DEBUG maybeFunction: binding is null for " + fnName);
        // List what's in env
        final List<String> names = new ArrayList<>();
        cache.env.visit(b -> names.add(b.id.name));
        System.err.println("DEBUG maybeFunction: env contains: " + names);
        continue;
      }
      if (binding.exp == null) {
        System.err.println(
            "DEBUG maybeFunction: binding.exp is null for " + fnName);
        System.err.println("DEBUG maybeFunction: binding = " + binding);
        System.err.println("DEBUG maybeFunction: binding.id = " + binding.id);
        System.err.println(
            "DEBUG maybeFunction: binding.value = " + binding.value);
        continue;
      }
      if (binding.exp.op != Op.FN) {
        System.err.println(
            "DEBUG maybeFunction: binding.exp not FN: " + binding.exp.op);
        continue;
      }
      final Core.Fn fn = (Core.Fn) binding.exp;
      System.err.println(
          "DEBUG maybeFunction: found function definition for " + fnName);

      // Step 3a: Try to analyze as a bounded recursive pattern (with depth
      // limit)
      final @Nullable BoundedRecursivePattern boundedPattern =
          analyzeBoundedRecursive(cache, fn, apply.arg, fnName);
      if (boundedPattern != null) {
        // Step 4a: Generate bounded iterate expression
        final Core.Exp iterateExp =
            generateBoundedIterate(cache, boundedPattern, goalPat, ordered);
        if (iterateExp != null) {
          // Step 5a: Register the generator
          final Set<Core.NamedPat> freePats =
              SuchThatShuttle.freePats(cache.typeSystem, iterateExp);
          cache.add(
              new BoundedIterateGenerator(
                  (Core.NamedPat) goalPat,
                  iterateExp,
                  freePats,
                  boundedPattern.depthBound));
          return true;
        }
      }

      // Step 3b: Try to analyze as unbounded transitive closure pattern.
      // Handle two cases:
      // 1. Full application: path p where p : (int * int)
      // 2. Tuple application: path (x, y) where x, y : int
      final Core.Exp fnArg = apply.arg;
      final boolean isFullApplication =
          fnArg.op == Op.ID && fnArg.type.equals(goalPat.type);
      // Check if fnArg is a tuple containing goalPat as a component
      final int tupleComponentIndex = findTupleComponent(fnArg, goalPat);
      final boolean isTupleApplication = tupleComponentIndex >= 0;
      System.err.println(
          "DEBUG: trying analyzeTransitiveClosure for "
              + fnName
              + ", isFullApplication="
              + isFullApplication
              + ", isTupleApplication="
              + isTupleApplication
              + ", tupleComponentIndex="
              + tupleComponentIndex
              + ", fnArg.type="
              + fnArg.type
              + ", goalPat.type="
              + goalPat.type);
      if (isFullApplication) {
        final @Nullable TransitiveClosurePattern tcPattern =
            analyzeTransitiveClosure(cache, fn, apply.arg, fnName);
        System.err.println(
            "DEBUG: tcPattern = " + (tcPattern != null ? "found" : "null"));
        if (tcPattern != null) {
          // Step 4b: Generate Relational.iterate expression for fixed point
          final Core.Exp iterateExp =
              generateTransitiveClosure(cache, tcPattern, goalPat, ordered);
          System.err.println(
              "DEBUG: iterateExp = "
                  + (iterateExp != null ? "generated" : "null"));
          if (iterateExp != null) {
            // Step 5b: Register the generator
            final Set<Core.NamedPat> freePats =
                SuchThatShuttle.freePats(cache.typeSystem, iterateExp);
            // Pass the original constraint (the function call) so it can be
            // simplified to true when the generator is used.
            cache.add(
                new TransitiveClosureGenerator(
                    (Core.NamedPat) goalPat, iterateExp, freePats, apply));
            return true;
          }
        }
      }
      // Handle tuple application: path(x, y) where x, y are separate patterns.
      // Create a TuplePat from the argument, generate transitive closure for
      // it,
      // and register the generator under all component patterns.
      if (isTupleApplication) {
        // Check if a TransitiveClosureGenerator already exists for goalPat from
        // the same function call (we may have already generated for a sibling
        // component of the same tuple).
        final Generator existingGen =
            cache.bestGenerator((Core.NamedPat) goalPat);
        if (existingGen instanceof TransitiveClosureGenerator) {
          final TransitiveClosureGenerator tcGen =
              (TransitiveClosureGenerator) existingGen;
          // Check if the existing generator is for the same constraint
          if (tcGen.constraint.equals(apply)) {
            System.err.println(
                "DEBUG: isTupleApplication, TC generator already exists for "
                    + goalPat
                    + " from same constraint");
            return true;
          }
        }

        // Create the TuplePat from the function argument (x, y)
        final Core.Pat tuplePat =
            createTuplePatFromArg(cache.typeSystem, fnArg);
        System.err.println(
            "DEBUG: isTupleApplication, created tuplePat = " + tuplePat);

        final @Nullable TransitiveClosurePattern tcPattern =
            analyzeTransitiveClosure(cache, fn, apply.arg, fnName);
        System.err.println(
            "DEBUG: tuple tcPattern = "
                + (tcPattern != null ? "found" : "null"));
        if (tcPattern != null) {
          // Generate iterate expression with TuplePat as goalPat
          final Core.Exp iterateExp =
              generateTransitiveClosure(cache, tcPattern, tuplePat, ordered);
          System.err.println(
              "DEBUG: tuple iterateExp = "
                  + (iterateExp != null ? "generated" : "null"));
          if (iterateExp != null) {
            // Register the generator under all component patterns
            final Set<Core.NamedPat> freePats =
                SuchThatShuttle.freePats(cache.typeSystem, iterateExp);
            cache.add(
                new TransitiveClosureGenerator(
                    tuplePat, iterateExp, freePats, apply));
            return true;
          }
        }
      }

      // Step 3c: Try simple (non-recursive) function inversion.
      // Inline the function body by substituting actual args for formal params,
      // then recursively try to find a generator for the substituted body.
      System.err.println(
          "DEBUG maybeFunction: trying simple inversion for " + fnName);
      final Core.Exp inlinedBody =
          inlineFunctionBody(cache.typeSystem, fn, apply.arg);
      if (inlinedBody != null) {
        System.err.println(
            "DEBUG maybeFunction: inlined body = " + inlinedBody);
        final List<Core.Exp> inlinedConstraints = ImmutableList.of(inlinedBody);
        if (maybeGenerator(cache, goalPat, ordered, inlinedConstraints)) {
          System.err.println(
              "DEBUG maybeFunction: simple inversion succeeded for " + fnName);
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Inlines a function body by substituting actual arguments for formal
   * parameters.
   *
   * <p>For example, if the function is {@code fn (x, y) => {x, y} elem edges}
   * and actual args are {@code (#1 p, #2 p)}, returns {@code {#1 p, #2 p} elem
   * edges}.
   */
  private static Core.@Nullable Exp inlineFunctionBody(
      TypeSystem ts, Core.Fn fn, Core.Exp actualArgs) {
    // Unwrap CASE expression if present (from tuple pattern matching)
    Core.Exp body = fn.exp;
    Core.Pat formalParams = fn.idPat;
    if (body.op == Op.CASE) {
      final Core.Case caseExp = (Core.Case) body;
      if (caseExp.matchList.size() == 1) {
        final Core.Match match = caseExp.matchList.get(0);
        body = match.exp;
        formalParams = match.pat;
      }
    }
    return substituteArgs(ts, formalParams, actualArgs, body);
  }

  /**
   * Attempts to recognize a bounded recursive pattern.
   *
   * <p>Expects a structure like:
   *
   * <pre>{@code
   * fun f(x, y, n) =
   *   n > 0 andalso
   *   (baseCase orelse
   *    exists z where stepPredicate andalso f(z, y, n - 1))
   * }</pre>
   *
   * @param cache The generator cache
   * @param fn The function definition
   * @param callArgs The actual arguments at call site
   * @param fnName The function name
   * @return The pattern, or null if not recognized
   */
  private static @Nullable BoundedRecursivePattern analyzeBoundedRecursive(
      Cache cache, Core.Fn fn, Core.Exp callArgs, String fnName) {

    // Step 1: Check for "n > 0 andalso body" structure
    if (!fn.exp.isCallTo(BuiltIn.Z_ANDALSO)) {
      return null;
    }
    final List<Core.Exp> topConjuncts = core.decomposeAnd(fn.exp);

    // Find the guard "n > 0" and identify the bound parameter
    Core.NamedPat boundParam = null;
    int boundParamIndex = -1;
    final List<Core.Exp> bodyParts = new ArrayList<>();

    for (Core.Exp conjunct : topConjuncts) {
      if (conjunct.isCallTo(BuiltIn.OP_GT)) {
        final Core.Apply gt = (Core.Apply) conjunct;
        if (gt.arg(0).op == Op.ID && isZeroLiteral(gt.arg(1))) {
          // Found "n > 0" pattern
          boundParam = ((Core.Id) gt.arg(0)).idPat;
          boundParamIndex = findParamIndex(fn.idPat, boundParam);
          continue;
        }
      }
      bodyParts.add(conjunct);
    }

    if (boundParam == null || bodyParts.isEmpty()) {
      return null;
    }

    // Reconstruct the body without the guard
    final Core.Exp remainingBody = core.andAlso(cache.typeSystem, bodyParts);

    // Step 2: Check that body is "baseCase orelse recursiveCase"
    if (!remainingBody.isCallTo(BuiltIn.Z_ORELSE)) {
      return null;
    }
    final List<Core.Exp> disjuncts = core.decomposeOr(remainingBody);
    if (disjuncts.size() != 2) {
      return null;
    }

    final Core.Exp baseCase = disjuncts.get(0);
    final Core.Exp recursiveCase = disjuncts.get(1);

    // Step 3: Check that recursiveCase contains a self-reference
    if (!containsSelfCall(recursiveCase, fnName)) {
      return null;
    }

    // Step 4: Analyze recursive case for
    // "exists z where step andalso f(z, n-1)"
    if (!recursiveCase.isCallTo(BuiltIn.RELATIONAL_NON_EMPTY)) {
      return null;
    }
    final Core.Apply nonEmpty = (Core.Apply) recursiveCase;
    if (nonEmpty.arg.op != Op.FROM) {
      return null;
    }
    final Core.From existsFrom = (Core.From) nonEmpty.arg;

    // Extract intermediate var and where clause
    Core.Pat intermediateVar = null;
    Core.Exp whereClause = null;
    for (Core.FromStep step : existsFrom.steps) {
      if (step.op == Op.SCAN) {
        intermediateVar = ((Core.Scan) step).pat;
      } else if (step.op == Op.WHERE) {
        whereClause = ((Core.Where) step).exp;
      }
    }

    if (intermediateVar == null || whereClause == null) {
      return null;
    }

    // Step 5: Decompose where clause into step predicate and recursive call
    final List<Core.Exp> whereConjuncts = core.decomposeAnd(whereClause);
    Core.Apply recursiveCall = null;
    final List<Core.Exp> stepPredicates = new ArrayList<>();

    for (Core.Exp conj : whereConjuncts) {
      if (conj.op == Op.APPLY) {
        final Core.Apply applyConj = (Core.Apply) conj;
        if (applyConj.fn.op == Op.ID
            && ((Core.Id) applyConj.fn).idPat.name.equals(fnName)) {
          recursiveCall = applyConj;
          continue;
        }
      }
      stepPredicates.add(conj);
    }

    if (recursiveCall == null || stepPredicates.isEmpty()) {
      return null;
    }

    // Step 6: Verify recursive call has "n - 1" for bound parameter
    final List<Core.Exp> recursiveArgs = flattenTupleExp(recursiveCall.arg);
    if (boundParamIndex >= recursiveArgs.size()) {
      return null;
    }
    final Core.Exp boundArg = recursiveArgs.get(boundParamIndex);
    if (!isDecrementOf(boundArg, boundParam)) {
      return null;
    }

    // Step 7: Extract constant bound from call site
    final List<Core.Exp> actualArgs = flattenTupleExp(callArgs);
    if (boundParamIndex >= actualArgs.size()) {
      return null;
    }
    final Core.Exp boundValue = actualArgs.get(boundParamIndex);
    if (boundValue.op != Op.INT_LITERAL) {
      return null; // Bound must be a constant
    }
    final int depthBound =
        ((BigDecimal) ((Core.Literal) boundValue).value).intValue();
    if (depthBound <= 0) {
      return null;
    }

    // Step 8: Extract output parameters (all except bound param)
    final List<Core.NamedPat> outputParams = new ArrayList<>();
    final List<Core.Pat> allParams = flattenTuplePat(fn.idPat);
    for (int i = 0; i < allParams.size(); i++) {
      if (i != boundParamIndex && allParams.get(i) instanceof Core.NamedPat) {
        outputParams.add((Core.NamedPat) allParams.get(i));
      }
    }

    // Build the step predicate
    final Core.Exp stepPredicate =
        core.andAlso(cache.typeSystem, stepPredicates);

    return new BoundedRecursivePattern(
        fnName,
        boundParamIndex,
        boundParam,
        outputParams,
        fn.idPat,
        baseCase,
        intermediateVar,
        stepPredicate,
        recursiveCall,
        depthBound,
        callArgs);
  }

  /**
   * Attempts to recognize an unbounded transitive closure pattern.
   *
   * <p>Expects a structure like:
   *
   * <pre>{@code
   * fun path(x, y) =
   *   edge(x, y) orelse
   *   (exists z where path(x, z) andalso edge(z, y))
   * }</pre>
   *
   * <p>Or equivalently:
   *
   * <pre>{@code
   * fun path(x, y) =
   *   edge(x, y) orelse
   *   (exists z where edge(x, z) andalso path(z, y))
   * }</pre>
   *
   * @param cache The generator cache
   * @param fn The function definition
   * @param callArgs The actual arguments at call site
   * @param fnName The function name
   * @return The pattern, or null if not recognized
   */
  private static @Nullable TransitiveClosurePattern analyzeTransitiveClosure(
      Cache cache, Core.Fn fn, Core.Exp callArgs, String fnName) {

    System.err.println("DEBUG analyzeTC: fn.exp = " + fn.exp);
    System.err.println("DEBUG analyzeTC: fn.exp.op = " + fn.exp.op);

    // Unwrap CASE expression from tuple pattern matching
    // fun path (x, y) = ... becomes fn v => case v of (x, y) => body
    // We need to extract the actual pattern (x, y) for proper substitution
    Core.Exp body = fn.exp;
    Core.Pat formalParams = fn.idPat; // Default to lambda param
    if (body.op == Op.CASE) {
      final Core.Case caseExp = (Core.Case) body;
      if (caseExp.matchList.size() == 1) {
        final Core.Match match = caseExp.matchList.get(0);
        body = match.exp;
        formalParams =
            match.pat; // Use the CASE pattern (x, y) not lambda param v
        System.err.println("DEBUG analyzeTC: unwrapped CASE, body = " + body);
        System.err.println("DEBUG analyzeTC: body.op = " + body.op);
        System.err.println("DEBUG analyzeTC: formalParams = " + formalParams);
      }
    }

    // Check that body is "baseCase orelse recursiveCase" (no guard)
    if (!body.isCallTo(BuiltIn.Z_ORELSE)) {
      System.err.println(
          "DEBUG analyzeTC: not Z_ORELSE, checking builtIn: " + body.builtIn());
      return null;
    }
    final List<Core.Exp> disjuncts = core.decomposeOr(body);
    if (disjuncts.size() != 2) {
      System.err.println(
          "DEBUG analyzeTC: wrong number of disjuncts: " + disjuncts.size());
      return null;
    }

    final Core.Exp baseCase = disjuncts.get(0);
    final Core.Exp recursiveCase = disjuncts.get(1);

    // Check that recursiveCase contains a self-reference
    if (!containsSelfCall(recursiveCase, fnName)) {
      return null;
    }

    // Analyze recursive case for "exists z where step andalso f(z, y)"
    if (!recursiveCase.isCallTo(BuiltIn.RELATIONAL_NON_EMPTY)) {
      return null;
    }
    final Core.Apply nonEmpty = (Core.Apply) recursiveCase;
    if (nonEmpty.arg.op != Op.FROM) {
      return null;
    }
    final Core.From existsFrom = (Core.From) nonEmpty.arg;

    // Extract intermediate var and where clause
    Core.Pat intermediateVar = null;
    Core.Exp whereClause = null;
    for (Core.FromStep step : existsFrom.steps) {
      if (step.op == Op.SCAN) {
        intermediateVar = ((Core.Scan) step).pat;
      } else if (step.op == Op.WHERE) {
        whereClause = ((Core.Where) step).exp;
      }
    }

    if (intermediateVar == null || whereClause == null) {
      return null;
    }

    // Decompose where clause into step predicate and recursive call
    final List<Core.Exp> whereConjuncts = core.decomposeAnd(whereClause);
    Core.Apply recursiveCall = null;
    final List<Core.Exp> stepPredicates = new ArrayList<>();

    for (Core.Exp conj : whereConjuncts) {
      if (conj.op == Op.APPLY) {
        final Core.Apply applyConj = (Core.Apply) conj;
        if (applyConj.fn.op == Op.ID
            && ((Core.Id) applyConj.fn).idPat.name.equals(fnName)) {
          recursiveCall = applyConj;
          continue;
        }
      }
      stepPredicates.add(conj);
    }

    if (recursiveCall == null || stepPredicates.isEmpty()) {
      return null;
    }

    // Build the step predicate
    final Core.Exp stepPredicate =
        core.andAlso(cache.typeSystem, stepPredicates);

    return new TransitiveClosurePattern(
        fnName,
        formalParams,
        baseCase,
        intermediateVar,
        stepPredicate,
        recursiveCall,
        callArgs);
  }

  /**
   * Generates an {@link BuiltIn#RELATIONAL_ITERATE iterate} expression for
   * transitive closure.
   *
   * <p>For {@code from p where path p}, generates:
   *
   * <pre>{@code
   * Relational.iterate edges
   *   (fn (allPaths, newPaths) =>
   *     from (x, z) in edges, (z2, y) in newPaths
   *       where z = z2
   *       yield (x, y))
   * }</pre>
   */
  private static Core.@Nullable Exp generateTransitiveClosure(
      Cache cache,
      TransitiveClosurePattern pattern,
      Core.Pat goalPat,
      boolean ordered) {

    final TypeSystem ts = cache.typeSystem;

    System.err.println(
        "DEBUG generateTC: pattern.baseCase = " + pattern.baseCase);
    System.err.println(
        "DEBUG generateTC: pattern.stepPredicate = " + pattern.stepPredicate);
    System.err.println("DEBUG generateTC: goalPat = " + goalPat);

    // 1. Substitute actual args into base case and try to invert to get
    // the base generator (e.g., edges)
    final Core.Exp substitutedBase =
        substituteArgs(
            ts, pattern.formalParams, pattern.callArgs, pattern.baseCase);
    System.err.println(
        "DEBUG generateTC: substitutedBase = " + substitutedBase);

    // Try to create a generator for the base case
    final Cache baseCache = cache; // new Cache(ts, cache.env);
    final List<Core.Exp> baseConstraints = ImmutableList.of(substitutedBase);
    if (!maybeGenerator(baseCache, goalPat, ordered, baseConstraints)) {
      System.err.println(
          "DEBUG generateTC: failed to create generator for base case");
      return null;
    }
    final Generator baseGenerator = baseCache.bestGeneratorForPat(goalPat);
    System.err.println("DEBUG generateTC: baseGenerator = " + baseGenerator);
    System.err.println(
        "DEBUG generateTC: baseGenerator.exp = " + baseGenerator.exp);
    System.err.println(
        "DEBUG generateTC: baseGenerator.exp.type = " + baseGenerator.exp.type);

    // 2. Similarly, substitute and invert the step predicate
    final Core.Exp substitutedStep =
        substituteArgs(
            ts, pattern.formalParams, pattern.callArgs, pattern.stepPredicate);
    System.err.println(
        "DEBUG generateTC: substitutedStep = " + substitutedStep);

    // Note: We don't need to create a step generator. The step function in
    // Relational.iterate just scans the base edges and joins with newPaths.
    // The base generator provides the edges collection which is used for both
    // the initial seed and the step computation.

    // 3. Build the Relational.iterate expression
    return buildRelationalIterate(
        cache, baseGenerator, substitutedBase, pattern, goalPat, ordered);
  }

  /**
   * Builds an {@code iterate} expression for fixed-point computation.
   *
   * <pre>{@code
   * Relational.iterate base
   *   (fn (all, new) =>
   *     from step in base, prev in new
   *       where step = prev.joinField
   *       yield {outputFields})
   * }</pre>
   */
  private static Core.Exp buildRelationalIterate(
      Cache cache,
      Generator baseGenerator,
      Core.Exp substitutedBase,
      TransitiveClosurePattern pattern,
      Core.Pat goalPat,
      boolean ordered) {

    final TypeSystem ts = cache.typeSystem;
    final Type baseElementType = baseGenerator.exp.type.elementType();

    // Get the original collection type by unwrapping any #fromList Bag wrapper
    // that was added by core.withOrdered when ordered=false
    final Type originalCollectionType;
    if (baseGenerator.exp.isCallTo(BuiltIn.BAG_FROM_LIST)) {
      // baseGenerator.exp is "#fromList Bag edges" - get edges.type
      // Access the arg field directly since arg(i) assumes tuple arg
      originalCollectionType = ((Core.Apply) baseGenerator.exp).arg.type;
    } else {
      originalCollectionType = baseGenerator.exp.type;
    }

    System.err.println(
        "DEBUG buildRI: originalCollectionType = "
            + originalCollectionType
            + " instanceof ListType: "
            + (originalCollectionType instanceof ListType));

    // The result type should match goalPat, which may differ from base element
    // type (e.g., edges are records {x,y} but goalPat wants tuples (x,y))
    final Type resultElementType = goalPat.type;
    // Preserve list vs bag from original collection
    final Type resultCollectionType =
        originalCollectionType instanceof ListType
            ? ts.listType(resultElementType)
            : ts.bagType(resultElementType);

    // Create a tuple type for the step function argument: (allPaths, newPaths)
    final Core.IdPat allPaths = core.idPat(resultCollectionType, "allPaths", 0);
    final Core.IdPat newPaths = core.idPat(resultCollectionType, "newPaths", 0);

    // Build the step body: from step in baseGen, prev in newPaths
    //   where joinCondition yield outputTuple
    final Environment env =
        cache.env.bindAll(
            ImmutableList.of(Binding.of(allPaths), Binding.of(newPaths)));
    final FromBuilder fb = core.fromBuilder(ts, env);

    // Scan base generator (e.g., edges) - the same collection used as the seed
    final Core.IdPat stepPat = core.idPat(baseElementType, "step", 0);
    fb.scan(stepPat, baseGenerator.exp);

    // Scan newPaths (second component of the tuple argument) - uses result type
    final Core.IdPat prevPat = core.idPat(resultElementType, "prev", 0);
    fb.scan(prevPat, core.id(newPaths));

    // Build join condition: step.#1 = prev.#2
    // The first field of edge (start node) equals the second field of path
    // (end node). For path (x, z2) and edge (z, y), we join on z = z2.
    final Core.Exp stepId = core.id(stepPat);
    final Core.Exp prevId = core.id(prevPat);
    final Core.Exp stepFirstField = core.field(ts, stepId, 0); // z
    final Core.Exp prevSecondField = core.field(ts, prevId, 1); // z2
    fb.where(core.equal(ts, stepFirstField, prevSecondField));

    // Yield: (prev.#1, step.#2) = (x, y)
    // For transitive closure: x is start node of path, y is end node of edge
    final Core.Exp prevFirst = core.field(ts, prevId, 0); // x (start of path)
    final Core.Exp stepSecond = core.field(ts, stepId, 1); // y (end of edge)

    // Build yield expression based on goalPat type (record or tuple)
    final Core.Exp yieldExp;
    if (resultElementType instanceof RecordType) {
      // For record types like {x:int, y:int}, build a record
      RecordLikeType recordLike = (RecordLikeType) resultElementType;
      List<String> fieldNames = recordLike.argNames();
      ImmutableMap<String, Core.Exp> nameExps =
          ImmutableMap.of(
              fieldNames.get(0), prevFirst,
              fieldNames.get(1), stepSecond);
      yieldExp = core.record(ts, nameExps);
    } else {
      // For tuple types like (int * int), build a tuple
      yieldExp =
          core.tuple(
              (RecordLikeType) resultElementType,
              ImmutableList.of(prevFirst, stepSecond));
    }
    fb.yield_(yieldExp);
    final Core.From stepBody = fb.build();

    // Create the step function: fn (all, new) => stepBody
    final Core.TuplePat stepArgPat =
        core.tuplePat(ts, ImmutableList.of(allPaths, newPaths));
    final Core.Fn stepFn =
        core.fn(
            Pos.ZERO,
            ts.fnType(stepArgPat.type, resultCollectionType),
            ImmutableList.of(core.match(Pos.ZERO, stepArgPat, stepBody)),
            value -> 0);

    // Convert base generator to result type if needed
    // e.g., from {x,y} in edges yield (x, y)
    final Core.Exp seedExp;
    if (baseElementType.equals(resultElementType)) {
      seedExp = baseGenerator.exp;
    } else {
      final FromBuilder seedFb = core.fromBuilder(ts);
      final Core.IdPat seedPat = core.idPat(baseElementType, "e", 0);
      seedFb.scan(seedPat, baseGenerator.exp);
      final Core.Exp seedId = core.id(seedPat);
      // Build conversion expression based on result type
      if (resultElementType instanceof RecordType) {
        RecordLikeType recordLike = (RecordLikeType) resultElementType;
        List<String> fieldNames = recordLike.argNames();
        ImmutableMap<String, Core.Exp> nameExps =
            ImmutableMap.of(
                fieldNames.get(0), core.field(ts, seedId, 0),
                fieldNames.get(1), core.field(ts, seedId, 1));
        seedFb.yield_(core.record(ts, nameExps));
      } else {
        seedFb.yield_(
            core.tuple(
                (RecordLikeType) resultElementType,
                ImmutableList.of(
                    core.field(ts, seedId, 0), core.field(ts, seedId, 1))));
      }
      seedExp = seedFb.build();
    }

    // Build: Relational.iterate seedExp stepFn
    final Core.Exp iterateFn =
        core.functionLiteral(ts, BuiltIn.RELATIONAL_ITERATE);
    final Core.Exp iterateWithBase =
        core.apply(
            Pos.ZERO,
            ts.fnType(stepFn.type, resultCollectionType),
            iterateFn,
            seedExp);
    return core.withOrdered(
        ordered,
        core.apply(Pos.ZERO, resultCollectionType, iterateWithBase, stepFn),
        ts);
  }

  /**
   * Generates a bounded iteration expression.
   *
   * <p>For {@code path(x, y, 3)}, generates:
   *
   * <pre>{@code
   * let
   *   val iter0 = from (x, y) in edges yield {x, y}
   *   val iter1 = from (x, z) in edges, e in iter0
   *                 where z = e.x yield {x, y = e.y}
   *   val iter2 = from (x, z) in edges, e in iter1
   *                 where z = e.x yield {x, y = e.y}
   * in
   *   List.concat [iter0, iter1, iter2]
   * end
   * }</pre>
   */
  private static Core.@Nullable Exp generateBoundedIterate(
      Cache cache,
      BoundedRecursivePattern pattern,
      Core.Pat goalPat,
      boolean ordered) {

    final TypeSystem ts = cache.typeSystem;

    // 1. Substitute actual args into base case and try to invert
    final Core.Exp substitutedBase =
        substituteArgs(
            ts, pattern.formalParams, pattern.callArgs, pattern.baseCase);

    // Try to create a generator for the base case
    final Cache baseCache = new Cache(ts, cache.env);
    final List<Core.Exp> baseConstraints = ImmutableList.of(substitutedBase);
    if (!maybeGenerator(baseCache, goalPat, ordered, baseConstraints)) {
      return null;
    }
    final Generator baseGenerator =
        getLast(baseCache.generators.get((Core.NamedPat) goalPat));

    // 2. Similarly, substitute and invert the step predicate
    final Core.Exp substitutedStep =
        substituteArgs(
            ts, pattern.formalParams, pattern.callArgs, pattern.stepPredicate);

    final Cache stepCache = new Cache(ts, cache.env);
    // For step, we need a pattern that captures the "joining" variable
    final Core.Pat stepGoalPat = deriveStepGoalPat(ts, pattern, goalPat);
    if (stepGoalPat == null) {
      return null;
    }
    final List<Core.Exp> stepConstraints = ImmutableList.of(substitutedStep);
    if (!maybeGenerator(stepCache, stepGoalPat, ordered, stepConstraints)) {
      return null;
    }
    final Generator stepGenerator =
        getLast(stepCache.generators.get((Core.NamedPat) stepGoalPat));

    // 3. Build the unrolled iteration
    return unrollBoundedIterate(
        cache, baseGenerator, stepGenerator, pattern, goalPat, ordered);
  }

  /**
   * Derives the goal pattern for the step generator.
   *
   * <p>For a pattern like {@code edge(x, z)}, we need to capture both x and z
   * so we can join z with the previous iteration's output.
   */
  private static Core.@Nullable Pat deriveStepGoalPat(
      TypeSystem ts, BoundedRecursivePattern pattern, Core.Pat goalPat) {
    // For simplicity, use the intermediate variable as the step goal
    // This works for the common case where step is like "edge(x, z)"
    if (pattern.intermediateVar instanceof Core.NamedPat) {
      return pattern.intermediateVar;
    }
    return null;
  }

  /** Unrolls bounded iteration into a let expression with union. */
  private static Core.Exp unrollBoundedIterate(
      Cache cache,
      Generator baseGenerator,
      Generator stepGenerator,
      BoundedRecursivePattern pattern,
      Core.Pat goalPat,
      boolean ordered) {

    final TypeSystem ts = cache.typeSystem;
    final Type elementType = baseGenerator.exp.type.elementType();
    final Type collectionType = baseGenerator.exp.type;

    final List<Core.IdPat> iterPats = new ArrayList<>();
    final List<Core.Exp> iterExps = new ArrayList<>();

    // iter0 = base generator
    final Core.IdPat iter0Pat = core.idPat(collectionType, "iter", 0);
    iterPats.add(iter0Pat);
    iterExps.add(baseGenerator.exp);

    // For each subsequent iteration, build a join
    Core.IdPat prevIterPat = iter0Pat;
    for (int i = 1; i < pattern.depthBound; i++) {
      final Core.IdPat iterIPat = core.idPat(collectionType, "iter", i);

      // Build: from stepVar in stepGenerator, prevVar in prevIter
      //          where joinCondition yield outputTuple
      final FromBuilder fb = core.fromBuilder(ts, cache.env);

      // Scan step generator (e.g., edges for edge(x, z))
      final Core.IdPat stepPat =
          core.idPat(stepGenerator.exp.type.elementType(), "step", 0);
      fb.scan(stepPat, stepGenerator.exp);

      // Scan previous iteration
      final Core.IdPat prevPat = core.idPat(elementType, "prev", 0);
      fb.scan(prevPat, core.id(prevIterPat));

      // Build join condition: the intermediate var from step equals
      // the first field of prev
      // For path(x, y, n), step produces (x, z) and prev has (z', y)
      // Join on z = z'
      final Core.Exp joinCondition =
          buildJoinCondition(ts, pattern, stepPat, prevPat);
      if (joinCondition != null) {
        fb.where(joinCondition);
      }

      // Yield the combined output: (x from step, y from prev)
      final Core.Exp yieldExp = buildYieldExp(ts, pattern, stepPat, prevPat);
      fb.yield_(yieldExp);

      iterPats.add(iterIPat);
      iterExps.add(fb.build());
      prevIterPat = iterIPat;
    }

    // Build: List.concat [iter0, iter1, ..., iterN-1]
    final List<Core.Exp> iterRefs = transformEager(iterPats, core::id);
    final Core.Exp listOfIters = core.list(ts, collectionType, iterRefs);
    final Core.Exp concatCall =
        core.apply(
            Pos.ZERO,
            collectionType,
            core.functionLiteral(
                ts, ordered ? BuiltIn.LIST_CONCAT : BuiltIn.BAG_CONCAT),
            listOfIters);

    // Wrap in nested let expressions: let val iter0 = ... in let val iter1 =
    // ... in ... end end
    Core.Exp result = concatCall;
    for (int i = iterPats.size() - 1; i >= 0; i--) {
      final Core.NonRecValDecl decl =
          core.nonRecValDecl(Pos.ZERO, iterPats.get(i), null, iterExps.get(i));
      result = core.let(decl, result);
    }
    return result;
  }

  /**
   * Builds the join condition for connecting step output to previous iteration.
   *
   * <p>For path traversal where step produces (x, z) and prev has (z', y), the
   * join condition is z = z'.x (or the appropriate field).
   */
  private static Core.Exp buildJoinCondition(
      TypeSystem ts,
      BoundedRecursivePattern pattern,
      Core.IdPat stepPat,
      Core.IdPat prevPat) {
    // The intermediate variable from the step should equal the first
    // component of the previous tuple.
    // For edge(x, z), z is the intermediate var.
    // For prev tuple {x, y}, we join on z = prev.x

    // Get the "joining" field from step (the intermediate variable)
    // This is typically the second field of the step output
    final Core.Exp stepId = core.id(stepPat);
    final Core.Exp prevId = core.id(prevPat);

    // For records with x, y fields, join on step's element = prev.x
    if (stepPat.type instanceof RecordLikeType
        && prevPat.type instanceof RecordLikeType) {
      // step element should equal prev.x (first output param)
      final Core.Exp prevField = core.field(ts, prevId, 0);
      return core.equal(ts, stepId, prevField);
    }

    // Simple case: direct equality
    return core.equal(ts, stepId, core.field(ts, prevId, 0));
  }

  /**
   * Builds the yield expression for the iteration step.
   *
   * <p>Combines the first field from step with remaining fields from prev.
   */
  private static Core.Exp buildYieldExp(
      TypeSystem ts,
      BoundedRecursivePattern pattern,
      Core.IdPat stepPat,
      Core.IdPat prevPat) {
    // For path(x, y), yield {x = step.x, y = prev.y}
    // where step comes from edge(x, z) giving us x
    // and prev comes from previous paths giving us y

    final Core.Exp stepId = core.id(stepPat);
    final Core.Exp prevId = core.id(prevPat);

    if (prevPat.type instanceof RecordType) {
      final RecordType recordType = (RecordType) prevPat.type;
      final List<Core.Exp> fields = new ArrayList<>();

      // First field from step generator (the "x" in edge(x, z))
      // For now, we use the step directly if it's a simple type
      if (stepPat.type instanceof RecordType) {
        fields.add(core.field(ts, stepId, 0));
      } else {
        fields.add(stepId);
      }

      // Remaining fields from prev (skip the first which was the join key)
      for (int i = 1; i < recordType.argNameTypes.size(); i++) {
        fields.add(core.field(ts, prevId, i));
      }

      return core.tuple(ts, null, fields);
    }

    // Simple tuple case
    return core.tuple(ts, null, ImmutableList.of(stepId, prevId));
  }

  /** Returns whether the expression contains a call to the named function. */
  private static boolean containsSelfCall(Core.Exp exp, String fnName) {
    final AtomicBoolean found = new AtomicBoolean(false);
    exp.accept(
        new Visitor() {
          @Override
          public void visit(Core.Apply apply) {
            if (apply.fn.op == Op.ID) {
              final Core.Id id = (Core.Id) apply.fn;
              if (id.idPat.name.equals(fnName)) {
                found.set(true);
              }
            }
            super.visit(apply);
          }
        });
    return found.get();
  }

  /** Returns whether exp is a literal zero. */
  private static boolean isZeroLiteral(Core.Exp exp) {
    return exp.op == Op.INT_LITERAL
        && BigDecimal.ZERO.equals(((Core.Literal) exp).value);
  }

  /** Returns whether exp is a literal one. */
  private static boolean isOneLiteral(Core.Exp exp) {
    return exp.op == Op.INT_LITERAL
        && BigDecimal.ONE.equals(((Core.Literal) exp).value);
  }

  /** Returns whether exp is "param - 1". */
  private static boolean isDecrementOf(Core.Exp exp, Core.NamedPat param) {
    if (!exp.isCallTo(BuiltIn.OP_MINUS) && !exp.isCallTo(BuiltIn.Z_MINUS_INT)) {
      return false;
    }
    final Core.Apply minus = (Core.Apply) exp;
    return minus.arg(0).op == Op.ID
        && ((Core.Id) minus.arg(0)).idPat.equals(param)
        && isOneLiteral(minus.arg(1));
  }

  /** Finds the index of a parameter in a (possibly tuple) pattern. */
  private static int findParamIndex(Core.Pat params, Core.NamedPat target) {
    final List<Core.Pat> flatParams = flattenTuplePat(params);
    for (int i = 0; i < flatParams.size(); i++) {
      if (flatParams.get(i).equals(target)) {
        return i;
      }
    }
    return -1;
  }

  /** Flattens a tuple pattern into a list of patterns. */
  private static List<Core.Pat> flattenTuplePat(Core.Pat pat) {
    if (pat.op == Op.TUPLE_PAT) {
      return ((Core.TuplePat) pat).args;
    } else if (pat.op == Op.RECORD_PAT) {
      return ((Core.RecordPat) pat).args;
    } else {
      return ImmutableList.of(pat);
    }
  }

  /** Flattens a tuple expression into a list of expressions. */
  private static List<Core.Exp> flattenTupleExp(Core.Exp exp) {
    if (exp.op == Op.TUPLE) {
      return ((Core.Tuple) exp).args;
    } else {
      return ImmutableList.of(exp);
    }
  }

  /**
   * Substitutes actual arguments for formal parameters in an expression.
   *
   * <p>Handles the case where formals are a tuple pattern like {@code (x, y)}
   * but actual is a single expression like {@code p}. In this case, substitutes
   * {@code x -> #1 p} and {@code y -> #2 p}.
   */
  private static Core.Exp substituteArgs(
      TypeSystem ts,
      Core.Pat formalParams,
      Core.Exp actualArgs,
      Core.Exp body) {
    final List<Core.Pat> formals = flattenTuplePat(formalParams);
    final List<Core.Exp> actuals = flattenTupleExp(actualArgs);

    // Build substitution map
    final Map<Core.NamedPat, Core.Exp> substitutions = new LinkedHashMap<>();

    if (formals.size() == actuals.size()) {
      // Sizes match - direct substitution
      for (int i = 0; i < formals.size(); i++) {
        if (formals.get(i) instanceof Core.NamedPat) {
          substitutions.put((Core.NamedPat) formals.get(i), actuals.get(i));
        }
      }
    } else if (actuals.size() == 1 && formals.size() > 1) {
      // Single actual (e.g., record p) with multiple formals (e.g., (x, y))
      // Substitute x -> #1 p, y -> #2 p (field access by index)
      final Core.Exp single = actuals.get(0);
      if (single.type instanceof RecordLikeType) {
        for (int i = 0; i < formals.size(); i++) {
          if (formals.get(i) instanceof Core.NamedPat) {
            // Create field access: #i single
            final Core.Exp fieldAccess = core.field(ts, single, i);
            substitutions.put((Core.NamedPat) formals.get(i), fieldAccess);
          }
        }
      }
    } else if (formals.size() == 1 && actuals.size() > 1) {
      // Single formal (e.g., p) with multiple actuals (e.g., (x, y))
      // Substitute p -> (x, y) (construct a tuple)
      final Core.Pat single = formals.get(0);
      if (single instanceof Core.NamedPat) {
        final RecordLikeType tupleType = (RecordLikeType) actualArgs.type;
        final Core.Exp tupleExp = core.tuple(tupleType, actuals);
        substitutions.put((Core.NamedPat) single, tupleExp);
      }
    }

    return substituteAll(substitutions, body);
  }

  /** Applies all substitutions to an expression, traversing recursively. */
  private static Core.Exp substituteAll(
      Map<Core.NamedPat, Core.Exp> substitutions, Core.Exp body) {
    if (substitutions.isEmpty()) {
      return body;
    }

    switch (body.op) {
      case ID:
        final Core.Id id = (Core.Id) body;
        final Core.Exp replacement = substitutions.get(id.idPat);
        return replacement != null ? replacement : body;

      case APPLY:
        final Core.Apply apply = (Core.Apply) body;
        final Core.Exp newFn = substituteAll(substitutions, apply.fn);
        final Core.Exp newArg = substituteAll(substitutions, apply.arg);
        if (newFn == apply.fn && newArg == apply.arg) {
          return body;
        }
        return core.apply(apply.pos, apply.type, newFn, newArg);

      case TUPLE:
        final Core.Tuple tuple = (Core.Tuple) body;
        final List<Core.Exp> newArgs = new ArrayList<>();
        boolean changed = false;
        for (Core.Exp arg : tuple.args) {
          final Core.Exp newArg2 = substituteAll(substitutions, arg);
          newArgs.add(newArg2);
          if (newArg2 != arg) {
            changed = true;
          }
        }
        final RecordLikeType recordLikeType = tuple.type();
        Core.Exp simplified = simplifyTuple(newArgs, recordLikeType);
        return simplified != null
            ? simplified
            : changed ? core.tuple(recordLikeType, newArgs) : body;

      default:
        // For other expression types, return unchanged
        // A full implementation would handle all cases
        return body;
    }
  }

  /** Simplifies "(#1 x, #2 x)" to "x" if "x" has 2 fields; otherwise null. */
  // TODO: move this to general-purpose simplify
  private static Core.@Nullable Exp simplifyTuple(
      List<Core.Exp> newArgs, RecordLikeType recordLikeType) {
    if (newArgs.isEmpty()) {
      // Cannot simplify unit, "()".
      return null;
    }
    if (newArgs.size() != recordLikeType.argTypes().size()) {
      // Cannot simplify "(#1 x, #2 x)" to "x" if x has 3 or more fields.
      return null;
    }
    Core.Exp arg = null;
    for (int i = 0; i < newArgs.size(); i++) {
      final Core.Exp a = newArgs.get(i);
      if (!(a instanceof Core.Apply)) {
        return null;
      }
      final Core.Apply apply = (Core.Apply) a;
      if (!(apply.fn instanceof Core.RecordSelector)) {
        return null;
      }
      final Core.RecordSelector recordSelector = (Core.RecordSelector) apply.fn;
      if (recordSelector.slot == i) {
        if (i == 0) {
          arg = apply.arg;
        } else if (arg != apply.arg) {
          // Arguments are not the same, e.g. "(#1 x, #2 x, #3 y)".
          return null;
        }
      }
    }
    return arg;
  }

  /**
   * Pattern for bounded recursive functions.
   *
   * <p>Recognizes:
   *
   * <pre>{@code
   * fun f(x, y, n) =
   *   n > 0 andalso
   *   (baseCase orelse
   *    exists z where stepPredicate andalso f(z, y, n - 1))
   * }</pre>
   */
  static class BoundedRecursivePattern {
    /** The function name. */
    final String fnName;
    /** Position of the depth-bound parameter (e.g., 2 for third param). */
    final int boundParamIndex;
    /** The depth-bound parameter pattern. */
    final Core.NamedPat boundParam;
    /** The output parameters (x, y). */
    final List<Core.NamedPat> outputParams;
    /** The formal parameters pattern. */
    final Core.Pat formalParams;
    /** The base case predicate (e.g., edge(x, y)). */
    final Core.Exp baseCase;
    /** The intermediate variable from exists (z). */
    final Core.Pat intermediateVar;
    /** The step predicate (e.g., edge(x, z)). */
    final Core.Exp stepPredicate;
    /** The recursive call (e.g., path(z, y, n - 1)). */
    final Core.Apply recursiveCall;
    /** The constant bound from the call site (e.g., 3). */
    final int depthBound;
    /** The actual arguments at the call site. */
    final Core.Exp callArgs;

    BoundedRecursivePattern(
        String fnName,
        int boundParamIndex,
        Core.NamedPat boundParam,
        List<Core.NamedPat> outputParams,
        Core.Pat formalParams,
        Core.Exp baseCase,
        Core.Pat intermediateVar,
        Core.Exp stepPredicate,
        Core.Apply recursiveCall,
        int depthBound,
        Core.Exp callArgs) {
      this.fnName = requireNonNull(fnName);
      this.boundParamIndex = boundParamIndex;
      this.boundParam = requireNonNull(boundParam);
      this.outputParams = ImmutableList.copyOf(outputParams);
      this.formalParams = requireNonNull(formalParams);
      this.baseCase = requireNonNull(baseCase);
      this.intermediateVar = requireNonNull(intermediateVar);
      this.stepPredicate = requireNonNull(stepPredicate);
      this.recursiveCall = requireNonNull(recursiveCall);
      this.depthBound = depthBound;
      this.callArgs = requireNonNull(callArgs);
    }
  }

  /**
   * Generator that uses bounded iteration for depth-limited recursive queries.
   */
  static class BoundedIterateGenerator extends Generator {
    private final int depthBound;

    BoundedIterateGenerator(
        Core.NamedPat pat,
        Core.Exp exp,
        Iterable<? extends Core.NamedPat> freePats,
        int depthBound) {
      super(exp, freePats, pat, Cardinality.FINITE);
      this.depthBound = depthBound;
    }

    @Override
    Core.Exp simplify(Core.Pat pat, Core.Exp exp) {
      // No simplification for bounded iterate expressions
      return exp;
    }
  }

  /**
   * Pattern for unbounded transitive closure functions.
   *
   * <p>Recognizes:
   *
   * <pre>{@code
   * fun path(x, y) =
   *   edge(x, y) orelse
   *   (exists z where edge(x, z) andalso path(z, y))
   * }</pre>
   */
  static class TransitiveClosurePattern {
    /** The function name. */
    final String fnName;
    /** The formal parameters pattern. */
    final Core.Pat formalParams;
    /** The base case predicate (e.g., edge(x, y)). */
    final Core.Exp baseCase;
    /** The intermediate variable from exists (z). */
    final Core.Pat intermediateVar;
    /** The step predicate (e.g., edge(x, z)). */
    final Core.Exp stepPredicate;
    /** The recursive call (e.g., path(z, y)). */
    final Core.Apply recursiveCall;
    /** The actual arguments at the call site. */
    final Core.Exp callArgs;

    TransitiveClosurePattern(
        String fnName,
        Core.Pat formalParams,
        Core.Exp baseCase,
        Core.Pat intermediateVar,
        Core.Exp stepPredicate,
        Core.Apply recursiveCall,
        Core.Exp callArgs) {
      this.fnName = requireNonNull(fnName);
      this.formalParams = requireNonNull(formalParams);
      this.baseCase = requireNonNull(baseCase);
      this.intermediateVar = requireNonNull(intermediateVar);
      this.stepPredicate = requireNonNull(stepPredicate);
      this.recursiveCall = requireNonNull(recursiveCall);
      this.callArgs = requireNonNull(callArgs);
    }
  }

  /** Generator that uses Relational.iterate for transitive closure queries. */
  static class TransitiveClosureGenerator extends Generator {
    /**
     * The original constraint (function call) that this generator satisfies.
     */
    private final Core.Apply constraint;

    TransitiveClosureGenerator(
        Core.Pat pat,
        Core.Exp exp,
        Iterable<? extends Core.NamedPat> freePats,
        Core.Apply constraint) {
      super(exp, freePats, pat, Cardinality.FINITE);
      this.constraint = constraint;
    }

    @Override
    Core.Exp simplify(Core.Pat pat, Core.Exp exp) {
      // If the expression is the original constraint (the path function call),
      // simplify it to true since the generator produces exactly the values
      // that satisfy the constraint.
      if (exp.equals(constraint)) {
        return core.boolLiteral(true);
      }
      // Also check if it's a call to the same function with the goal pattern.
      // Use containsRef to handle both IdPat (path p) and TuplePat (path (x,
      // y)).
      if (exp.op == Op.APPLY) {
        Core.Apply apply = (Core.Apply) exp;
        if (apply.fn.equals(constraint.fn) && containsRef(apply.arg, pat)) {
          return core.boolLiteral(true);
        }
      }
      return exp;
    }
  }

  /**
   * For each constraint "pat elem collection", adds a generator based on
   * "collection".
   */
  static boolean maybeElem(
      Cache cache,
      Core.Pat goalPat,
      boolean ordered,
      List<Core.Exp> constraints) {
    for (Core.Exp predicate : constraints) {
      if (predicate.isCallTo(BuiltIn.OP_ELEM)) {
        if (containsRef(predicate.arg(0), goalPat)) {
          // If predicate is "(p, q) elem links", first create a generator
          // for "p2 elem links", where "p2 as (p, q)".
          final Core.Exp collection = predicate.arg(1);
          final ExpPat expPat =
              requireNonNull(wholePat(cache.typeSystem, predicate.arg(0)));
          CollectionGenerator.create(cache, ordered, expPat.pat, collection);
          return true;
        }
      }
    }
    return false;
  }

  static boolean maybeElemNew(
      Cache cache,
      Core.Pat goalPat,
      boolean ordered,
      List<Core.Exp> constraints) {
    for (Core.Exp predicate : constraints) {
      if (predicate.isCallTo(BuiltIn.OP_ELEM)) {
        if (containsRef(predicate.arg(0), goalPat)) {
          // Check if the element expression is a projection from goalPat
          // (e.g., {x = #1 p, y = #2 p} where goalPat = p)
          final Core.Exp elemExp = predicate.arg(0);
          final Core.Exp collection = predicate.arg(1);

          // Case 1: Simple case where elemExp is directly the goalPat
          // (e.g., "p elem edges" where goalPat = p)
          if (elemExp.op == Op.ID
              && goalPat instanceof Core.IdPat
              && ((Core.Id) elemExp).idPat.equals(goalPat)) {
            CollectionGenerator.create(cache, ordered, goalPat, collection);
            return true;
          }

          // Case 2: Projection case where elemExp is a record/tuple of
          // field accesses on goalPat
          // (e.g., {x = #1 p, y = #2 p} elem edges)
          final Core.NamedPat projectedPat = getProjectedPattern(elemExp);
          if (projectedPat != null && projectedPat.equals(goalPat)) {
            // Create a projection generator that converts collection
            // elements to goalPat's type
            ProjectionGenerator.create(
                cache, ordered, (Core.NamedPat) goalPat, elemExp, collection);
            return true;
          }

          // Case 3: General case - create generator for the full pattern
          final ExpPat expPat =
              requireNonNull(wholePat(cache.typeSystem, elemExp));
          CollectionGenerator.create(cache, ordered, expPat.pat, collection);
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Returns the pattern that is projected in an expression containing only
   * field accesses on a single pattern.
   *
   * <p>For example, for {@code {x = #1 p, y = #2 p}}, returns {@code p}.
   * Returns null if the expression is not a pure projection (e.g., contains
   * literals or references to multiple patterns).
   */
  private static Core.@Nullable NamedPat getProjectedPattern(Core.Exp exp) {
    final Set<Core.NamedPat> pats = new HashSet<>();
    collectFieldAccessPatterns(exp, pats);
    return pats.size() == 1 ? pats.iterator().next() : null;
  }

  /**
   * Collects patterns that are accessed via field selectors in the expression.
   */
  private static void collectFieldAccessPatterns(
      Core.Exp exp, Set<Core.NamedPat> pats) {
    switch (exp.op) {
      case APPLY:
        final Core.Apply apply = (Core.Apply) exp;
        if (apply.fn.op == Op.RECORD_SELECTOR && apply.arg.op == Op.ID) {
          pats.add(((Core.Id) apply.arg).idPat);
        } else {
          collectFieldAccessPatterns(apply.fn, pats);
          collectFieldAccessPatterns(apply.arg, pats);
        }
        break;
      case TUPLE:
        for (Core.Exp arg : ((Core.Tuple) exp).args) {
          collectFieldAccessPatterns(arg, pats);
        }
        break;
      case ID:
        // Direct ID reference (not a field access) - add to pats
        pats.add(((Core.Id) exp).idPat);
        break;
      default:
        // Literals and other expressions don't contribute to patterns
        break;
    }
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
            pat, simplified, freePats, lower, lowerStrict, upper, upperStrict));
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
  static Core.@Nullable Exp point(Core.Pat pat, List<Core.Exp> constraints) {
    for (Core.Exp constraint : constraints) {
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
      Core.Pat pat, List<Core.Exp> constraints) {
    for (Core.Exp constraint : constraints) {
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
      Core.Pat pat, List<Core.Exp> constraints) {
    for (Core.Exp constraint : constraints) {
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
   *
   * <p>When {@code pat} is a TuplePat, returns true if {@code exp} is a tuple
   * expression where each component matches the corresponding component of
   * {@code pat}.
   */
  private static boolean containsRef(Core.Exp exp, Core.Pat pat) {
    // Special case: if pat is a TuplePat, check if exp is a matching tuple
    if (pat instanceof Core.TuplePat && exp.op == Op.TUPLE) {
      final Core.TuplePat tuplePat = (Core.TuplePat) pat;
      final Core.Tuple tuple = (Core.Tuple) exp;
      if (tuplePat.args.size() == tuple.args.size()) {
        // Check if each component of the tuple expression matches
        // the corresponding component pattern
        for (int i = 0; i < tuplePat.args.size(); i++) {
          if (!containsRef(tuple.args.get(i), tuplePat.args.get(i))) {
            return false;
          }
        }
        return true;
      }
    }

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

        // Note: Records in Core are represented as Tuple with RecordType.
        // The TUPLE case above handles them since Tuple.args contains the
        // values.

      case APPLY:
        // Handle field access like #1 p or #x p
        final Core.Apply apply = (Core.Apply) exp;
        if (apply.fn.op == Op.RECORD_SELECTOR) {
          return containsRef(apply.arg, pat);
        }
        // Also recursively check function and arg for other applies
        return containsRef(apply.fn, pat) || containsRef(apply.arg, pat);

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
          patList.add(core.toPat(arg.e));
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

      case APPLY:
        // Handle field access like #1 p or #x p
        final Core.Apply apply = (Core.Apply) exp;
        if (apply.fn.op == Op.RECORD_SELECTOR && apply.arg.op == Op.ID) {
          // Field access on an ID - the underlying variable is what we want
          final Core.Id baseId = (Core.Id) apply.arg;
          return ExpPat.of(apply, baseId.idPat);
        }
        return null;

      default:
        return null;
    }
  }

  /**
   * Generator that generates a range of integers from {@code lower} to {@code
   * upper}.
   */
  static class RangeGenerator extends Generator {
    private final Core.Exp lower;
    private final Core.Exp upper;

    RangeGenerator(
        Core.NamedPat pat,
        Core.Exp exp,
        Iterable<? extends Core.NamedPat> freePats,
        Core.Exp lower,
        boolean ignoreLowerStrict,
        Core.Exp upper,
        boolean ignoreUpperStrict) {
      super(exp, freePats, pat, Cardinality.FINITE);
      this.lower = lower;
      this.upper = upper;
    }

    @Override
    Core.Exp simplify(Core.Pat pat, Core.Exp exp) {
      // Simplify "p > lower && p < upper"
      if (exp.isCallTo(BuiltIn.Z_ANDALSO)) {
        final List<Core.Exp> ands = core.decomposeAnd(exp);
        final Pair<Core.Exp, Boolean> lowerBound = lowerBound(pat, ands);
        final Pair<Core.Exp, Boolean> upperBound = upperBound(pat, ands);
        if (lowerBound != null && upperBound != null) {
          if (lowerBound.left.equals(this.lower)
              && upperBound.left.equals(this.upper)) {
            return core.boolLiteral(true);
          }
          if (lowerBound.left.isConstant()
              && upperBound.left.isConstant()
              && this.lower.isConstant()
              && this.upper.isConstant()) {
            return core.boolLiteral(
                gt(lowerBound.left, this.lower, lowerBound.right)
                    && gt(this.upper, upperBound.left, upperBound.right));
          }
        }
      }
      return exp;
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
          core.withOrdered(ordered, collection, typeSystem);
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
   * Generator that scans a collection and projects each element to match the
   * goal pattern's type.
   *
   * <p>For example, for {@code {x = #1 p, y = #2 p} elem edges} where {@code p
   * : int * int}, this generator scans {@code edges} (which contains {@code {x:
   * int, y: int}} records) and yields {@code (r.x, r.y)} for each record {@code
   * r}, effectively converting records to tuples to bind {@code p}.
   */
  static class ProjectionGenerator extends Generator {
    final Core.Exp collection;
    final Core.Exp projectionExp;

    private ProjectionGenerator(
        Core.NamedPat goalPat,
        Core.Exp projectionExp,
        Core.Exp collection,
        Iterable<? extends Core.NamedPat> freePats) {
      super(projectionExp, freePats, goalPat, Cardinality.FINITE);
      this.collection = collection;
      this.projectionExp = projectionExp;
    }

    static void create(
        Cache cache,
        boolean ordered,
        Core.NamedPat goalPat,
        Core.Exp elemExp,
        Core.Exp collection) {
      final TypeSystem ts = cache.typeSystem;

      // Build a projection expression: from r in collection yield projection
      // where projection converts r to goalPat's type
      final Core.Exp projectionExp =
          buildProjectionExpression(ts, goalPat, elemExp, collection, ordered);

      final Set<Core.NamedPat> freePats =
          SuchThatShuttle.freePats(ts, projectionExp);
      cache.add(
          new ProjectionGenerator(
              goalPat, projectionExp, collection, freePats));
    }

    /**
     * Builds an expression that scans the collection and projects each element
     * to the goal pattern's type.
     *
     * <p>For {@code {x = #1 p, y = #2 p} elem edges} where p : int * int,
     * builds: {@code from r in edges yield (r.x, r.y)}
     */
    private static Core.Exp buildProjectionExpression(
        TypeSystem ts,
        Core.NamedPat goalPat,
        Core.Exp elemExp,
        Core.Exp collection,
        boolean ordered) {
      // Create a fresh variable for scanning the collection
      final Type elemType =
          ((net.hydromatic.morel.type.ListType) collection.type).elementType;
      final Core.IdPat scanPat = core.idPat(elemType, "r", 0);
      final Core.Id scanId = core.id(scanPat);

      // Build the yield expression by extracting fields from scanId based on
      // the structure of elemExp
      final Core.Exp yieldExp = buildYieldExpression(ts, elemExp, scanId);

      // Build: from r in collection yield yieldExp
      final FromBuilder fb = core.fromBuilder(ts);
      fb.scan(scanPat, core.withOrdered(ordered, collection, ts));
      fb.yield_(ordered, null, yieldExp, true);
      return fb.build();
    }

    /**
     * Builds the yield expression that converts collection elements to the goal
     * pattern's type.
     *
     * <p>For elemExp = {x = #1 p, y = #2 p} and scanId = r, returns (r.x, r.y)
     * (a tuple built from the record fields).
     */
    private static Core.Exp buildYieldExpression(
        TypeSystem ts, Core.Exp elemExp, Core.Id scanId) {
      // elemExp is a record/tuple of field accesses on p
      // We need to extract the corresponding fields from scanId
      if (elemExp.op == Op.TUPLE) {
        final Core.Tuple tuple = (Core.Tuple) elemExp;
        final List<Core.Exp> projectedArgs = new ArrayList<>();

        if (tuple.type instanceof RecordType) {
          // Record: {x = #1 p, y = #2 p}
          // For each field in elemExp, get the corresponding field from scanId
          final RecordType recordType = (RecordType) tuple.type;
          int i = 0;
          for (String fieldName : recordType.argNames()) {
            // Get the field value from scanId
            projectedArgs.add(core.field(ts, scanId, i));
            i++;
          }
        } else {
          // Tuple: (#1 p, #2 p)
          for (int i = 0; i < tuple.args.size(); i++) {
            projectedArgs.add(core.field(ts, scanId, i));
          }
        }

        // Create result tuple with projected fields
        final RecordLikeType goalType =
            ts.tupleType(transformEager(projectedArgs, Core.Exp::type));
        return core.tuple(goalType, projectedArgs);
      }

      // Fallback: just return scanId if we can't decompose
      return scanId;
    }

    @Override
    Core.Exp simplify(Core.Pat pat, Core.Exp exp) {
      return exp;
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
     * Gets the best generator for a pattern, which may be a TuplePat.
     *
     * <p>For TuplePat, looks for a generator that is indexed under all
     * component patterns. Returns the first such generator found.
     */
    @Nullable
    Generator bestGeneratorForPat(Core.Pat pat) {
      if (pat instanceof Core.NamedPat) {
        return bestGenerator((Core.NamedPat) pat);
      }
      if (pat instanceof Core.TuplePat) {
        // For TuplePat, find a generator common to all component patterns
        final Core.TuplePat tuplePat = (Core.TuplePat) pat;
        if (tuplePat.args.isEmpty()) {
          return null;
        }
        // Get generators for the first component
        Set<Generator> candidates = null;
        for (Core.Pat component : tuplePat.args) {
          if (component instanceof Core.NamedPat) {
            final Set<Generator> componentGens =
                new HashSet<>(generators.get((Core.NamedPat) component));
            if (candidates == null) {
              candidates = componentGens;
            } else {
              candidates.retainAll(componentGens);
            }
          }
        }
        if (candidates != null && !candidates.isEmpty()) {
          return candidates.iterator().next();
        }
      }
      return null;
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
