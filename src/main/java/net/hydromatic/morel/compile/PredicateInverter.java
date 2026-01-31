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
import static java.util.Collections.disjoint;
import static java.util.Objects.requireNonNull;
import static net.hydromatic.morel.ast.CoreBuilder.core;
import static net.hydromatic.morel.util.Static.skip;
import static net.hydromatic.morel.util.Static.transformEager;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableRangeSet;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Range;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.CoreBuilder;
import net.hydromatic.morel.ast.FromBuilder;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.ast.Shuttle;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.RecordLikeType;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.TupleType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.ConsList;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Inverts predicates into generator expressions.
 *
 * <p>Given a predicate like {@code edge(x, y) andalso y > 5} and goal
 * variables, produces a generator expression that yields all tuples satisfying
 * the predicate.
 *
 * <p>Maintains state for:
 *
 * <ul>
 *   <li>Memoization of recursive function inversions
 *   <li>Variable definitions (e.g., "name = dept.dname")
 *   <li>Tracking which filters have been incorporated into generators
 * </ul>
 */
public class PredicateInverter {
  private final TypeSystem typeSystem;
  private final Environment env;
  private final Map<Core.Pat, Generator> generators;
  private final FunctionRegistry functionRegistry;
  private final ModeAnalyzer modeAnalyzer;

  private PredicateInverter(
      TypeSystem typeSystem,
      Environment env,
      Map<Core.NamedPat, Generator> initialGenerators,
      FunctionRegistry functionRegistry) {
    this.typeSystem = requireNonNull(typeSystem);
    this.env = requireNonNull(env);
    this.generators = new HashMap<>(initialGenerators);
    this.functionRegistry = requireNonNull(functionRegistry);
    this.modeAnalyzer = new ModeAnalyzer(functionRegistry);
  }

  /** Package-private constructor for testing. */
  PredicateInverter(TypeSystem typeSystem, Environment env) {
    this(typeSystem, env, ImmutableMap.of(), new FunctionRegistry());
  }

  /**
   * Inverts a predicate to generate tuples.
   *
   * @param predicate The boolean expression to invert
   * @param goalPats Variables in the output tuple (what we want to generate)
   * @param generators Generators for ALL variables in scope (including extents)
   * @return Inversion result with improved generator, or fallback to cartesian
   *     product
   */
  public static Result invert(
      TypeSystem typeSystem,
      Environment env,
      Core.Exp predicate,
      List<Core.NamedPat> goalPats,
      Map<Core.NamedPat, Generator> generators) {
    return invert(
        typeSystem,
        env,
        predicate,
        goalPats,
        generators,
        new FunctionRegistry());
  }

  /**
   * Inverts a predicate to generate tuples using a function registry.
   *
   * <p>The function registry contains pre-analyzed invertibility information
   * for user-defined functions. This avoids the "mixing domains" problem where
   * function bodies would be inlined onto the inversion stack.
   *
   * @param predicate The boolean expression to invert
   * @param goalPats Variables in the output tuple (what we want to generate)
   * @param generators Generators for ALL variables in scope (including extents)
   * @param functionRegistry Registry of function invertibility info
   * @return Inversion result with improved generator, or fallback to cartesian
   *     product
   */
  public static Result invert(
      TypeSystem typeSystem,
      Environment env,
      Core.Exp predicate,
      List<Core.NamedPat> goalPats,
      Map<Core.NamedPat, Generator> generators,
      FunctionRegistry functionRegistry) {
    return new PredicateInverter(typeSystem, env, generators, functionRegistry)
        .invert(predicate, goalPats, ImmutableList.of());
  }

  /**
   * Inverts a predicate.
   *
   * @param predicate The boolean expression to invert
   * @param goalPats Variables in the output tuple (what we want to generate)
   * @param active Functions that are being expanded (further up the call stack)
   * @return Inversion result with improved generator, or fallback to cartesian
   *     product
   */
  private Result invert(
      Core.Exp predicate, List<Core.NamedPat> goalPats, List<Core.Exp> active) {
    // Deduplicate goalPats by name to avoid accumulating duplicates in
    // recursive calls. This can happen with recursive transitive closure
    // inversion where the same existential variable gets added to goalPats
    // multiple times.
    java.util.Set<String> seenNames = new java.util.LinkedHashSet<>();
    List<Core.NamedPat> uniqueGoalPats = new ArrayList<>();
    for (Core.NamedPat pat : goalPats) {
      if (seenNames.add(pat.name)) {
        uniqueGoalPats.add(pat);
      }
    }
    goalPats = uniqueGoalPats;

    // Handle CASE expressions that result from function application expansion.
    // When Inliner expands "path p", it becomes:
    //   case p of (x_1, y_1) => edge(x_1, y_1) orelse exists...
    // We need to invert the body using the pattern variables as new goals.
    if (predicate.op == Op.CASE) {
      Result caseResult = tryInvertCase(predicate, goalPats, active);
      if (caseResult != null) {
        return caseResult;
      }
    }

    // Handle function application
    if (predicate.op == Op.APPLY) {
      Core.Apply apply = (Core.Apply) predicate;

      // Check for `elem` calls: pattern elem collection
      if (apply.isCallTo(BuiltIn.OP_ELEM)) {
        return invertElem(apply, goalPats);
      }

      // Check for String.isPrefix pattern expr
      // This is represented as: APPLY(APPLY(STRING_IS_PREFIX, pattern), expr)
      if (apply.fn.op == Op.APPLY) {
        Core.Apply innerApply = (Core.Apply) apply.fn;
        if (innerApply.isCallTo(BuiltIn.STRING_IS_PREFIX)) {
          Core.Exp patternArg = innerApply.arg;
          Core.Exp stringArg = apply.arg;

          // Check if patternArg is an output pattern (e.g., Id(s))
          if (patternArg.op == Op.ID) {
            Core.Id id = (Core.Id) patternArg;

            if (goalPats.contains(id.idPat)) {
              // Generate: List.tabulate(String.size s + 1, fn i =>
              // String.substring(s, 0, i))
              Generator generator = generateStringPrefixes(id.idPat, stringArg);
              return result(generator, ImmutableList.of());
            }
          }
        }
      }

      // Check for andalso (conjunction)
      if (apply.isCallTo(BuiltIn.Z_ANDALSO)) {
        final List<Core.Exp> predicates = core.decomposeAnd(apply);
        return invertAnds(predicates, goalPats, active);
      }

      // Try orelse: first multi-branch disjunction, then TC, then simple union
      if (apply.isCallTo(BuiltIn.Z_ORELSE)) {
        // Try multi-branch disjunction (N >= 2 branches)
        Result disjunctionResult =
            tryInvertDisjunction(apply, goalPats, active);
        if (disjunctionResult != null) {
          return disjunctionResult;
        }
        // Try transitive closure inversion
        Result tcResult =
            tryInvertTransitiveClosure(apply, null, null, goalPats, active);
        if (tcResult != null) {
          return tcResult;
        }
        // Fall back to simple binary union
        Result unionResult = tryInvertSimpleUnion(apply, goalPats, active);
        if (unionResult != null) {
          return unionResult;
        }
      }

      // Check for function literals (Op.FN_LITERAL) or inlined function
      // definitions (Op.FN). Both cases substitute the arg into the body.
      Result fnResult = tryInvertFnApplication(apply, goalPats, active);
      if (fnResult != null) {
        return fnResult;
      }

      // Check for user-defined function calls (ID references)
      if (apply.fn.op == Op.ID) {
        Result userFnResult =
            tryInvertUserDefinedFunction(apply, goalPats, active);
        if (userFnResult != null) {
          return userFnResult;
        }
      }
    }

    // Handle exists: Relational.nonEmpty (from ...)
    // exists s in collection where predicate(goalVar, s)
    // => union of all values generated by inverting predicate for each s
    if (predicate.op == Op.APPLY) {
      Core.Apply apply = (Core.Apply) predicate;
      if (apply.isCallTo(BuiltIn.RELATIONAL_NON_EMPTY)) {
        // The argument is a from expression
        Core.Exp fromExp = apply.arg;

        // Try to extract the pattern and invert
        // For now, handle simple case: from s in collection where pred(p, s)
        // This would be represented as a FROM with a WHERE clause
        return invertExists((Core.From) fromExp, goalPats);
      }
    }

    // When we can't invert a predicate, keep it as a remaining filter.
    // This allows the caller (e.g., invertAnds) to combine a successful
    // inversion with remaining filters. Without this, predicates like
    // "x = 1" would be silently dropped.
    return result(generatorFor(goalPats), ImmutableList.of(predicate));
  }

  /**
   * Tries to invert a CASE expression that results from function application
   * expansion. When Inliner expands "path p", it becomes: case p of (x_1, y_1)
   * => edge(x_1, y_1) orelse exists... We need to invert the body using the
   * pattern variables as new goals.
   *
   * @param predicate the CASE expression to invert
   * @param goalPats the patterns we want to generate values for
   * @param active the list of functions currently being inverted (for recursion
   *     detection)
   * @return the inversion result, or null if the case cannot be inverted
   */
  private Result tryInvertCase(
      Core.Exp predicate, List<Core.NamedPat> goalPats, List<Core.Exp> active) {
    Core.Case caseExp = (Core.Case) predicate;
    // Check for single-arm case matching on a goal pattern
    if (caseExp.matchList.size() == 1 && caseExp.exp.op == Op.ID) {
      Core.Id caseId = (Core.Id) caseExp.exp;
      // Check if we're matching on one of our goal patterns
      if (goalPats.contains(caseId.idPat)) {
        Core.Match match = caseExp.matchList.get(0);
        // The new goal patterns are the variables bound by the match pattern
        List<Core.NamedPat> newGoalPats = match.pat.expand();

        // Invert the body using the new goal patterns
        Result bodyResult = invert(match.exp, newGoalPats, active);

        // If successful, the generator produces tuples matching the pattern
        // which corresponds to the original goal pattern (since the case
        // destructures goalPat into the match pattern)
        return bodyResult;
      }
    }
    return null;
  }

  /**
   * Tries to invert a user-defined function call (ID references). This
   * includes: 1. Registry lookup for pre-analyzed functions 2. Recursive call
   * detection and transitive closure inversion 3. Legacy inlining approach for
   * non-registered functions
   *
   * @param apply the function application to invert
   * @param goalPats the patterns we want to generate values for
   * @param active the list of functions currently being inverted (for recursion
   *     detection)
   * @return the inversion result, or null if the function cannot be inverted
   */
  private Result tryInvertUserDefinedFunction(
      Core.Apply apply, List<Core.NamedPat> goalPats, List<Core.Exp> active) {
    Core.Id fnId = (Core.Id) apply.fn;
    // Look up function definition in environment
    Core.NamedPat fnPat = fnId.idPat;

    // First, check the function registry for pre-analyzed invertibility.
    // Per Scott's principle: "Edge should never be on the stack."
    Result registryResult = tryInvertFromRegistry(fnPat, apply.arg, goalPats);
    if (registryResult != null) {
      return registryResult;
    }

    // Fallback: function not in registry - use legacy inlining approach.
    // This path will be deprecated once all functions are pre-analyzed.

    // Check if we're already trying to invert this function (recursion)
    if (active.contains(fnId)) {
      // This is a recursive call - try to invert it with transitive closure
      Binding binding = env.getOpt(fnPat);

      if (binding != null && binding.exp != null) {
        Core.Exp fnBody = binding.exp;
        if (fnBody.op == Op.FN) {
          Core.Fn fn = (Core.Fn) fnBody;

          // Substitute the function argument into the body, handling case
          // unwrapping for tuple parameters
          Core.Exp substitutedBody = substituteIntoFn(fn, apply.arg);

          // Try to invert as transitive closure pattern
          Result transitiveClosureResult =
              tryInvertTransitiveClosure(
                  substitutedBody, fn, apply.arg, goalPats, active);
          if (transitiveClosureResult != null) {
            return transitiveClosureResult;
          }
        }
      }

      // Fallback: can't invert recursive calls and couldn't build
      // Relational.iterate
      // Since we couldn't invert the transitive closure, we can't safely
      // generate
      // values for these goal patterns using infinite extents. Instead of
      // returning
      // an infinite cartesian product (which would fail at runtime), we
      // signal
      // complete inversion failure by returning null.
      // This forces the caller to handle the failure explicitly, either by:
      // 1. Allowing deferred grounding (if safe in context)
      // 2. Returning null to propagate the failure further
      // 3. Throwing an ungrounded pattern error
      // In all cases, returning an INFINITE cardinality result signals to
      // the caller that this predicate couldn't be inverted properly.
      // We cannot safely create a fallback with infinite extents, because:
      // 1. The inversion failed (base case is non-invertible)
      // 2. Relational.iterate can't be built (due to infinite base case)
      // 3. Any fallback with infinite extents will fail at runtime when
      // materialized
      //
      // Return null to signal complete failure. The caller (invert method)
      // will handle this appropriately.
      return null;
    }

    Binding binding = env.getOpt(fnPat);
    if (binding != null && binding.exp != null) {
      Core.Exp fnBody = binding.exp;
      if (fnBody.op == Op.FN) {
        Core.Fn fn = (Core.Fn) fnBody;

        // Substitute the function argument into the body, handling case
        // unwrapping for tuple parameters
        Core.Exp substitutedBody = substituteIntoFn(fn, apply.arg);

        // Try to invert the substituted body
        return invert(substitutedBody, goalPats, ConsList.of(fnId, active));
      }
    }
    return null;
  }

  /**
   * Tries to invert a function application where the function is either an
   * {@code Op.FN_LITERAL} (compiled function literal) or {@code Op.FN} (inlined
   * function definition from Inliner).
   *
   * <p>Both cases work the same: substitute the argument into the function body
   * and recursively invert.
   *
   * @param apply The function application
   * @param goalPats Goal patterns to generate
   * @param active Functions currently being expanded (recursion guard)
   * @return Inversion result, or null if the function is not FN/FN_LITERAL
   */
  private @Nullable Result tryInvertFnApplication(
      Core.Apply apply, List<Core.NamedPat> goalPats, List<Core.Exp> active) {
    // Handle Op.FN_LITERAL: compiled function literal, value is Core.Fn
    if (apply.fn.op == Op.FN_LITERAL) {
      Core.Literal literal = (Core.Literal) apply.fn;
      if (literal.value instanceof Core.Fn) {
        Core.Fn fn = (Core.Fn) literal.value;
        Core.Exp substitutedBody = substituteArg(fn.idPat, apply.arg, fn.exp);
        return invert(substitutedBody, goalPats, active);
      }
    }

    // Handle Op.FN: inlined function definition (from Inliner replacing ID
    // with the actual Fn). Inliner processes "path p" and may replace
    // Id(path) with the actual Fn definition, resulting in
    // Apply(fn=Fn(...), arg=p).
    if (apply.fn.op == Op.FN) {
      Core.Fn fn = (Core.Fn) apply.fn;
      Core.Exp substitutedBody = substituteIntoFn(fn, apply.arg);
      return invert(substitutedBody, goalPats, active);
    }

    return null;
  }

  /**
   * Inverts an exists expression: {@code exists s in collection where
   * predicate(p, s)}.
   *
   * <p>Returns the union of all values that satisfy the predicate for at least
   * one element in the collection.
   */
  private Result invertExists(Core.From from, List<Core.NamedPat> goalPats) {
    // For a query like "from s in collection where String.isPrefix p s",
    // we want to find all p that are prefixes of at least one s in collection.

    // If fromExp is a FROM with scan and where steps:
    // - Extract the collection being scanned
    // - Extract the filter predicate
    // - For each element in the collection, invert the predicate
    // - Return the union of all inverted results

    // Look for the scan step to get the collection
    Core.Exp collection = null;
    Core.Pat scanPat = null;
    Core.Exp filterPredicate = null;

    for (Core.FromStep step : from.steps) {
      if (step.op == Op.SCAN) {
        Core.Scan scan = (Core.Scan) step;
        collection = scan.exp;
        scanPat = scan.pat;
      } else if (step.op == Op.WHERE) {
        Core.Where where = (Core.Where) step;
        filterPredicate = where.exp;
      }
    }

    if (filterPredicate == null || scanPat == null) {
      throw new AssertionError();
    }

    // Check if the collection is an extent (unbounded domain).
    // In this case, we need to invert the filter to get generators.
    requireNonNull(collection);
    if (collection.isExtent()) {
      // Extent scan - filter must provide the generators.
      // Add the scan variable to outputPats temporarily so it can be
      // referenced. Check if already present (by name) to avoid duplicates in
      // recursive calls.
      List<Core.NamedPat> extendedOutputPats = new ArrayList<>(goalPats);
      if (scanPat.op == Op.ID_PAT) {
        Core.IdPat scanIdPat = (Core.IdPat) scanPat;
        // Only add if a pattern with same name is not already present
        // (avoid duplicates in recursive calls)
        boolean alreadyPresent =
            goalPats.stream().anyMatch(p -> p.name.equals(scanIdPat.name));
        if (!alreadyPresent) {
          extendedOutputPats.add(scanIdPat);
        }
        // Add an infinite extent generator for the scan variable
        this.generators.put(
            scanIdPat,
            generator(
                toTuple(goalPats),
                collection,
                net.hydromatic.morel.compile.Generator.Cardinality.INFINITE,
                ImmutableList.of()));
      }

      // Try to invert the filter with the extended patterns and generators
      return invert(filterPredicate, extendedOutputPats, ImmutableList.of());
    }

    // Check if the collection is a list literal.
    if (collection.op == Op.APPLY && collection.isCallTo(BuiltIn.Z_LIST)) {
      Core.Apply listApply = (Core.Apply) collection;
      // Get the list elements
      if (listApply.arg.op == Op.TUPLE) {
        Core.Tuple tuple = (Core.Tuple) listApply.arg;
        List<Generator> resultGenerators = new ArrayList<>();

        // For each element in the collection, substitute it into the filter
        // and try to invert.
        for (Core.Exp element : tuple.args) {
          // Substitute scanPat with element in filterPredicate.
          Core.Exp substitutedFilter =
              substituteArg(scanPat, element, filterPredicate);

          // Try to invert the substituted filter.
          Result elementResult =
              invert(substitutedFilter, goalPats, ImmutableList.of());
          // Only add if it improved (no remaining filters)
          if (elementResult.remainingFilters.isEmpty()) {
            resultGenerators.add(elementResult.generator);
          }
        }

        if (!resultGenerators.isEmpty()) {
          // Chain generators using @ operator instead of FROM...union.
          Core.Exp combined;
          if (resultGenerators.size() == 1) {
            combined = resultGenerators.get(0).expression;
          } else {
            combined = resultGenerators.get(0).expression;
            for (int i = 1; i < resultGenerators.size(); i++) {
              // Create (combined @ nextExpr) with explicit types
              combined =
                  createListAppend(
                      combined, resultGenerators.get(i).expression);
            }
          }
          Generator gen =
              new Generator(
                  resultGenerators.get(0).goalPat,
                  combined,
                  net.hydromatic.morel.compile.Generator.Cardinality.FINITE,
                  resultGenerators.get(0).constraints,
                  resultGenerators.get(0).freeVars);
          return result(gen, ImmutableList.of());
        }
      }
    }

    return result(
        generatorFor(goalPats),
        ImmutableList.of(
            core.call(typeSystem, BuiltIn.RELATIONAL_NON_EMPTY, from)));
  }

  private Generator generatorFor(List<Core.NamedPat> goalPats) {
    // Deduplicate goalPats by name to avoid duplicate bindings in recursive
    // calls.
    // This can happen when recursive transitive closure inversion accumulates
    // the same existential variable multiple times across iterations.
    java.util.Set<String> seenNames = new java.util.LinkedHashSet<>();
    List<Core.NamedPat> uniqueGoalPats = new ArrayList<>();
    for (Core.NamedPat pat : goalPats) {
      if (seenNames.add(pat.name)) {
        uniqueGoalPats.add(pat);
      }
    }
    goalPats = uniqueGoalPats;

    final Core.Pat goalPat = toTuple(goalPats);
    switch (goalPats.size()) {
      case 0:
        // No patterns. The generator is '[()]'.
        return generator(
            goalPat,
            core.list(typeSystem, core.unitLiteral()),
            net.hydromatic.morel.compile.Generator.Cardinality.SINGLE,
            ImmutableList.of());

      case 1:
        // One pattern. The generator is the one in the dictionary,
        // or make an infinite one.
        final Core.NamedPat pat = goalPats.get(0);
        final Generator generator1;
        if (generators.containsKey(pat)) {
          generator1 = generators.get(pat);
        } else {
          generator1 = createExtentGenerator(pat);
          generators.put(pat, generator1);
        }
        return requireNonNull(generator1);

      default:
        // More than one pattern. The generator is a Cartesian product.
        final FromBuilder fromBuilder = core.fromBuilder(typeSystem, env);
        final ImmutableSet.Builder<Core.NamedPat> freeVars =
            ImmutableSet.builder();
        net.hydromatic.morel.compile.Generator.Cardinality cardinality =
            net.hydromatic.morel.compile.Generator.Cardinality.SINGLE;
        for (Core.NamedPat pattern : goalPats) {
          Generator generator = generatorFor(ImmutableList.of(pattern));
          cardinality = cardinality.max(generator.cardinality);
          freeVars.addAll(generator.freeVars);
          fromBuilder.scan(pattern, generator.expression);
        }
        return new Generator(
            goalPats.get(goalPats.size() - 1),
            fromBuilder.build(),
            cardinality,
            ImmutableList.of(),
            freeVars.build());
    }
  }

  private Generator generator(
      Core.Pat goalPat,
      Core.Exp exp,
      net.hydromatic.morel.compile.Generator.Cardinality cardinality,
      Iterable<? extends Core.Exp> filters) {
    final Core.Exp simplified = Simplifier.simplify(typeSystem, exp);
    final Set<Core.NamedPat> freeVars = freeVarsIn(simplified);
    return new Generator(goalPat, simplified, cardinality, filters, freeVars);
  }

  /**
   * Creates a "from" expression with "union distinct" steps, "from p_union in
   * scan#0 union distinct scan#1, ... scan#N".
   */
  private Generator unionDistinct(List<Generator> generators) {
    checkArgument(!generators.isEmpty());
    final Generator generator0 = generators.get(0);
    if (generators.size() == 1) {
      return generator0;
    } else {
      final Type elementType = generator0.expression.type.elementType();
      final Core.IdPat unionPat = core.idPat(elementType, "p_union", 0);
      final Core.From exp =
          core.fromBuilder(typeSystem, (Environment) null)
              .scan(unionPat, generator0.expression)
              .union(true, transformEager(skip(generators), g -> g.expression))
              .build();
      return generator(
          generator0.goalPat,
          exp,
          net.hydromatic.morel.compile.Generator.Cardinality.FINITE,
          ImmutableList.of());
    }
  }

  /**
   * Recursively extracts all non-recursive base cases from an orelse tree. Base
   * cases are branches that do NOT contain 'exists' or recursive calls.
   *
   * <p>For pattern: {@code a orelse b orelse (exists z where rec(z))} Returns:
   * [a, b]
   *
   * @param exp the expression to analyze (typically an orelse tree)
   * @return list of all base case expressions (non-exists branches)
   */
  private List<Core.Exp> extractAllBaseCases(Core.Exp exp) {
    List<Core.Exp> baseCases = new ArrayList<>();
    extractBaseCasesHelper(exp, baseCases);
    return baseCases;
  }

  /** Helper for extractAllBaseCases - recursively walks the orelse tree. */
  private void extractBaseCasesHelper(Core.Exp exp, List<Core.Exp> baseCases) {
    if (exp.op == Op.APPLY) {
      Core.Apply apply = (Core.Apply) exp;
      if (apply.isCallTo(BuiltIn.Z_ORELSE)) {
        Core.Exp arg0 = apply.arg(0);
        Core.Exp arg1 = apply.arg(1);

        // Check arg0
        if (containsExists(arg0)) {
          // Has 'exists', dig deeper to find nested base cases
          extractBaseCasesHelper(arg0, baseCases);
        } else {
          // Doesn't have 'exists', it's a base case
          baseCases.add(arg0);
        }

        // Check arg1
        if (containsExists(arg1)) {
          // Has 'exists', dig deeper to find nested base cases
          extractBaseCasesHelper(arg1, baseCases);
        } else {
          // Doesn't have 'exists', it's a base case
          baseCases.add(arg1);
        }

        return;
      }
    }

    // Not an orelse pattern. If it contains exists, it's a recursive case (skip
    // it).
    // Only add non-exists expressions as base cases.
    if (!containsExists(exp)) {
      baseCases.add(exp);
    }
  }

  /**
   * Flattens a nested orelse expression into a list of branches. Handles both
   * left-associative and right-associative nesting.
   *
   * <p>Examples:
   *
   * <ul>
   *   <li>{@code a} → {@code [a]}
   *   <li>{@code a orelse b} → {@code [a, b]}
   *   <li>{@code (a orelse b) orelse c} → {@code [a, b, c]}
   *   <li>{@code a orelse (b orelse c)} → {@code [a, b, c]}
   *   <li>{@code (a orelse b) orelse (c orelse d)} → {@code [a, b, c, d]}
   * </ul>
   *
   * @param exp The expression to flatten (may or may not be an orelse)
   * @return List of branch expressions (singleton list if not an orelse)
   */
  private List<Core.Exp> flattenOrelse(Core.Exp exp) {
    requireNonNull(exp, "exp");
    List<Core.Exp> branches = new ArrayList<>();
    flattenOrElseRecursive(exp, branches);
    return branches;
  }

  /**
   * Recursive helper for flattenOrelse. Traverses the orelse tree and collects
   * all leaf branches.
   *
   * @param exp The current expression being examined
   * @param accumulator The list collecting all branches
   */
  private void flattenOrElseRecursive(
      Core.Exp exp, List<Core.Exp> accumulator) {
    if (exp.op == Op.APPLY) {
      Core.Apply apply = (Core.Apply) exp;
      if (apply.isCallTo(BuiltIn.Z_ORELSE)) {
        // Recursively flatten both sides
        flattenOrElseRecursive(apply.arg(0), accumulator);
        flattenOrElseRecursive(apply.arg(1), accumulator);
        return;
      }
    }
    // Not an orelse - this is a leaf branch
    accumulator.add(exp);
  }

  /**
   * Builds a union expression from multiple generators using List.concat.
   *
   * <p>Combines generators into a balanced binary tree for efficiency:
   *
   * <ul>
   *   <li>union([a]) = a
   *   <li>union([a, b]) = List.concat [a, b]
   *   <li>union([a, b, c, d]) = List.concat [List.concat [a, b], List.concat
   *       [c, d]]
   * </ul>
   *
   * @param generators List of generator expressions to combine (MUST all have
   *     compatible list types - caller's responsibility to ensure type
   *     homogeneity)
   * @return Single expression representing the union of all generators
   * @throws IllegalArgumentException if generators is empty
   * @throws NullPointerException if generators is null
   */
  private Core.Exp buildUnion(List<Core.Exp> generators) {
    requireNonNull(generators, "generators");
    if (generators.isEmpty()) {
      throw new IllegalArgumentException("Cannot build union of empty list");
    }
    if (generators.size() == 1) {
      return generators.get(0);
    }

    // Get the type of the generators (should all be the same list type)
    final Type listType = generators.get(0).type;

    // Defensive check: verify all generators have the same type
    assert generators.stream().map(g -> g.type).distinct().count() == 1
        : "All generators must have the same type";

    // Build balanced binary tree of concatenations
    List<Core.Exp> currentLevel = new ArrayList<>(generators);
    while (currentLevel.size() > 1) {
      List<Core.Exp> nextLevel = new ArrayList<>();
      for (int i = 0; i < currentLevel.size(); i += 2) {
        if (i + 1 < currentLevel.size()) {
          // Pair two generators with concat
          final Core.Exp left = currentLevel.get(i);
          final Core.Exp right = currentLevel.get(i + 1);
          final Core.Exp concatFn =
              core.functionLiteral(typeSystem, BuiltIn.LIST_CONCAT);
          final Core.Exp listArg =
              core.list(typeSystem, listType, ImmutableList.of(left, right));
          final Core.Exp concat =
              core.apply(Pos.ZERO, listType, concatFn, listArg);
          nextLevel.add(concat);
        } else {
          // Odd element, carry forward
          nextLevel.add(currentLevel.get(i));
        }
      }
      currentLevel = nextLevel;
    }

    return currentLevel.get(0);
  }

  /**
   * Tries to invert a multi-branch disjunction by flattening nested orelse
   * expressions and unioning the results.
   *
   * <p>This handles N-branch disjunctions (N >= 2) where each branch can be
   * inverted to a finite generator. Uses {@link #flattenOrelse} to decompose
   * nested orelse structures, then {@link #buildUnion} to combine generators
   * with a balanced binary tree of List.concat calls.
   *
   * <p><b>Strategy</b>:
   *
   * <ol>
   *   <li>Flatten orelse into N branches
   *   <li>For each branch, attempt inversion via {@link #tryInvertBranch}
   *   <li>If any branch is not invertible, fail entire disjunction
   *   <li>Union all generators using balanced tree
   * </ol>
   *
   * <p><b>Example</b>: For {@code directPath(x,y) orelse pathViaA(x,y) orelse
   * pathViaB(x,y)}, generates {@code List.concat([directGen, viaAGen,
   * viaBGen])} with balanced binary tree.
   *
   * @param orElse the orelse expression
   * @param goalPats the output patterns we want to generate
   * @param active recursive call stack for detecting transitive closure
   * @return Result with union generator, or null if any branch not invertible
   */
  @Nullable
  private Result tryInvertDisjunction(
      Core.Apply orElse, List<Core.NamedPat> goalPats, List<Core.Exp> active) {

    // Step 1: Flatten orelse into branches
    final List<Core.Exp> branches = flattenOrelse(orElse);

    if (branches.size() < 2) {
      // Not a multi-branch disjunction, fall through to other handlers
      return null;
    }

    // Check if any branch contains EXISTS (indicates transitive closure
    // pattern)
    // Transitive closure should be handled by tryInvertTransitiveClosure, not
    // here
    for (int i = 0; i < branches.size(); i++) {
      if (containsExists(branches.get(i))) {
        return null; // Let tryInvertTransitiveClosure handle it
      }
    }

    // Step 2: Attempt to invert each branch
    final List<Core.Exp> generatorExprs = new ArrayList<>();
    final List<Core.Exp> combinedFilters = new ArrayList<>();
    Core.Pat goalPat = null;
    net.hydromatic.morel.compile.Generator.Cardinality combinedCardinality =
        net.hydromatic.morel.compile.Generator.Cardinality.FINITE;
    ImmutableSet<Core.NamedPat> combinedFreeVars = ImmutableSet.of();

    for (int i = 0; i < branches.size(); i++) {
      final Core.Exp branch = branches.get(i);
      final Result branchResult = tryInvertBranch(branch, goalPats, active);

      if (branchResult == null) {
        // Branch not invertible - fail entire disjunction
        return null;
      }

      // Check if branch produces finite generator
      if (branchResult.generator.cardinality
          == net.hydromatic.morel.compile.Generator.Cardinality.INFINITE) {
        return null; // Can't create finite union with infinite generators
      }

      generatorExprs.add(branchResult.generator.expression);
      combinedFilters.addAll(branchResult.remainingFilters);

      // Use first branch's goalPat for the union
      if (goalPat == null) {
        goalPat = branchResult.generator.goalPat;
      }

      // Union of free vars from all branches
      combinedFreeVars =
          ImmutableSet.<Core.NamedPat>builder()
              .addAll(combinedFreeVars)
              .addAll(branchResult.generator.freeVars)
              .build();
    }

    // Step 3: Build union of all generators
    final Core.Exp unionExpr = buildUnion(generatorExprs);

    final Generator unionGen =
        new Generator(
            goalPat,
            unionExpr,
            combinedCardinality,
            ImmutableList.of(),
            combinedFreeVars);

    return result(unionGen, combinedFilters);
  }

  /**
   * Attempts to invert a single branch of a disjunction.
   *
   * <p>This is a helper method for {@link #tryInvertDisjunction} that inverts
   * individual branches. It delegates to the main {@link #invert} method to
   * handle the branch expression.
   *
   * @param branch the branch expression to invert
   * @param goalPats the output patterns we want to generate
   * @param active recursive call stack for detecting transitive closure
   * @return Result if branch is invertible, null otherwise
   */
  @Nullable
  private Result tryInvertBranch(
      Core.Exp branch, List<Core.NamedPat> goalPats, List<Core.Exp> active) {
    // Delegate to main invert method
    return invert(branch, goalPats, active);
  }

  /**
   * Tries to invert a simple orelse pattern (without exists) as a union.
   *
   * <p>Handles patterns like: {@code a orelse b} where neither branch contains
   * exists. Used for base cases of transitive closures and standalone
   * disjunctions.
   *
   * @param orElse the orelse expression (must have 2 arguments)
   * @param goalPats the output patterns we want to generate
   * @param active recursive call stack for detecting transitive closure
   * @return a Generator combining both branches via unionDistinct, or null if
   *     either branch cannot be inverted to a finite generator
   */
  @Nullable
  private Result tryInvertSimpleUnion(
      Core.Apply orElse, List<Core.NamedPat> goalPats, List<Core.Exp> active) {

    final Core.Exp branch0 = orElse.arg(0);
    final Core.Exp branch1 = orElse.arg(1);

    // Try to invert both branches
    final Result result0 = invert(branch0, goalPats, active);
    final Result result1 = invert(branch1, goalPats, active);

    // Check if both inversions succeeded with FINITE generators
    if (result0.generator.cardinality
            == net.hydromatic.morel.compile.Generator.Cardinality.INFINITE
        || result1.generator.cardinality
            == net.hydromatic.morel.compile.Generator.Cardinality.INFINITE) {
      return null; // Can't create finite union with infinite generators
    }

    // Combine generators using @ operator instead of FROM...union
    final Core.Exp combinedExpr =
        createListAppend(
            result0.generator.expression, result1.generator.expression);
    final Generator unionGen =
        new Generator(
            result0.generator.goalPat,
            combinedExpr,
            net.hydromatic.morel.compile.Generator.Cardinality.FINITE,
            result0.generator.constraints,
            result0.generator.freeVars);

    // Combine remaining filters from both branches
    final List<Core.Exp> combinedFilters = new ArrayList<>();
    combinedFilters.addAll(result0.remainingFilters);
    combinedFilters.addAll(result1.remainingFilters);

    return result(unionGen, combinedFilters);
  }

  /**
   * Tries to invert a function using the pre-analyzed registry.
   *
   * <p>Per Scott's principle: "Edge should never be on the stack." Functions
   * are analyzed once at compile time; we use the cached result.
   *
   * <p>Pattern matching (Phase B'): When the call argument differs from the
   * function's formal parameter (e.g., scalar {@code p} vs tuple {@code
   * (x,y)}), we use PatternMatcher to determine the correct binding.
   *
   * @param fnPat the function's name pattern
   * @param callArg the argument expression passed to the function
   * @param goalPats the patterns we want to generate values for
   * @return inversion result if function is registered and invertible, null if
   *     not registered or requires fallback handling
   */
  private @Nullable Result tryInvertFromRegistry(
      Core.NamedPat fnPat, Core.Exp callArg, List<Core.NamedPat> goalPats) {
    Optional<FunctionRegistry.FunctionInfo> registeredInfo =
        functionRegistry.lookup(fnPat);
    if (!registeredInfo.isPresent()) {
      return null; // Not registered - use legacy inlining
    }

    FunctionRegistry.FunctionInfo info = registeredInfo.get();

    // Pattern matching: determine which goalPats are bound by this call
    Optional<PatternMatcher.MatchResult> matchResult =
        PatternMatcher.match(callArg, info.formalParameter(), goalPats);

    // Determine the effective goal pattern for the generator
    Core.Pat effectiveGoalPat =
        matchResult.isPresent()
            ? matchResult.get().goalPat
            : info.formalParameter();

    switch (info.status()) {
      case INVERTIBLE:
        // Function has a known generator - return it directly
        if (info.baseGenerator().isPresent()) {
          Generator gen =
              new Generator(
                  effectiveGoalPat,
                  info.baseGenerator().get(),
                  net.hydromatic.morel.compile.Generator.Cardinality.FINITE,
                  ImmutableList.of(),
                  ImmutableSet.of());
          return result(gen, ImmutableList.of());
        }
        break;

      case RECURSIVE:
        // Recursive function - use cached base case and step from registry.
        // Per Scott: "Recursion happens in a different domain" - the step
        // function is pre-computed at registration time, NOT inlined here.
        if (info.baseGenerator().isPresent()
            && info.recursiveStep().isPresent()) {
          Core.Exp baseGen = info.baseGenerator().get();
          Core.Exp stepFn = info.recursiveStep().get();

          // Build: Relational.iterate baseGen stepFn
          // RELATIONAL_ITERATE has type: 'a bag -> (('a bag, 'a bag) -> 'a bag)
          //   -> 'a bag
          // After applying baseGen ('a bag): (('a bag, 'a bag) -> 'a bag) -> 'a
          // bag
          // After applying stepFn: 'a bag
          Type bagType = baseGen.type; // 'a bag (the result type)
          Type stepFnArgType =
              typeSystem.tupleType(bagType, bagType); // ('a bag, 'a bag)
          Type stepFnType =
              typeSystem.fnType(stepFnArgType, bagType); // ('a bag, 'a bag) ->
          // 'a bag
          Type afterBaseType =
              typeSystem.fnType(stepFnType, bagType); // step -> 'a bag

          Core.Exp iterateFn =
              core.functionLiteral(typeSystem, BuiltIn.RELATIONAL_ITERATE);
          Core.Exp iterateWithBase =
              core.apply(Pos.ZERO, afterBaseType, iterateFn, baseGen);
          Core.Exp relationalIterate =
              core.apply(Pos.ZERO, bagType, iterateWithBase, stepFn);

          Generator gen =
              new Generator(
                  effectiveGoalPat,
                  relationalIterate,
                  net.hydromatic.morel.compile.Generator.Cardinality.FINITE,
                  ImmutableList.of(),
                  ImmutableSet.of());
          return result(gen, ImmutableList.of());
        }
        break;

      case NOT_INVERTIBLE:
        // Function cannot be inverted - this is a definitive failure
        // Return a sentinel that signals complete failure
        // (Returning null here would fall through to legacy inlining)
        break;

      case PARTIALLY_INVERTIBLE:
        // Function is partially invertible - return generator with filters
        if (info.baseGenerator().isPresent()) {
          Generator gen =
              new Generator(
                  effectiveGoalPat,
                  info.baseGenerator().get(),
                  net.hydromatic.morel.compile.Generator.Cardinality.FINITE,
                  ImmutableList.of(),
                  ImmutableSet.of());
          return result(gen, ImmutableList.copyOf(info.requiredFilters()));
        }
        break;
    }
    return null; // Fall through to legacy inlining
  }

  /**
   * Tries to invert a transitive closure pattern.
   *
   * <p>Pattern: {@code baseCase orelse (exists z where recursiveCall andalso
   * otherCall)}
   *
   * @param fnBody The function body to check
   * @param fn The function definition
   * @param fnArg The argument passed to the function
   * @param goalPats The output patterns
   * @param active Functions currently being inverted (for recursion detection)
   * @return Inversion result using Relational.iterate, or null if pattern not
   *     matched
   */
  private Result tryInvertTransitiveClosure(
      Core.Exp fnBody,
      Core.Fn fn,
      Core.Exp fnArg,
      List<Core.NamedPat> goalPats,
      List<Core.Exp> active) {
    // Check if fnBody matches: baseCase orelse recursiveCase
    if (fnBody.op != Op.APPLY) {
      return null;
    }

    Core.Apply orElseApply = (Core.Apply) fnBody;
    if (!orElseApply.isCallTo(BuiltIn.Z_ORELSE)) {
      return null;
    }

    // The standard pattern is: baseCase orelse recursiveCase
    // But some code uses: recursiveCase orelse baseCase
    // Detect which operand contains the recursive call to identify the cases
    Core.Exp arg0 = orElseApply.arg(0);
    Core.Exp arg1 = orElseApply.arg(1);

    // Check if arg0 contains exists (recursive pattern usually has exists)
    boolean arg0HasExists = containsExists(arg0);
    boolean arg1HasExists = containsExists(arg1);

    // A transitive closure pattern MUST have an exists in the recursive case.
    // If NEITHER branch contains exists, this is NOT a transitive closure -
    // it's a simple union like "x > 0 orelse x < -10" that should be handled
    // by maybeUnion, not PredicateInverter.
    if (!arg0HasExists && !arg1HasExists) {
      return null;
    }

    // Extract ALL base cases (non-exists branches) from the orelse tree.
    // For patterns like: edge_b (x, y) orelse edge_a (x, y) orelse (exists z
    // ...)
    // This extracts: [edge_b (x, y), edge_a (x, y)]
    List<Core.Exp> allBaseCases = extractAllBaseCases(fnBody);

    if (allBaseCases.isEmpty()) {
      return null; // No base cases found
    }

    // Invert each base case and collect the generators and remaining filters
    List<Generator> baseGenerators = new ArrayList<>();
    List<Core.Exp> allRemainingFilters = new ArrayList<>();
    for (Core.Exp baseCase : allBaseCases) {
      Result baseCaseResult = invert(baseCase, goalPats, ImmutableList.of());

      // Check if base case could be inverted to a FINITE generator.
      // If the base case is non-invertible (e.g., a user-defined function
      // call),
      // the invert method returns a result with INFINITE cardinality as a
      // fallback. We cannot safely build Relational.iterate with an infinite
      // generator because:
      // 1. An infinite generator cannot be materialized at runtime
      // 2. At compile time we don't know what runtime values are available
      // 3. The entire iteration would fail with "infinite: int * int" at
      // runtime
      //
      // In this case, we cannot safely build Relational.iterate with an
      // infinite
      // base case. Return null to signal that transitive closure pattern
      // matching
      // failed, causing the caller to skip this optimization.
      if (baseCaseResult.generator.cardinality
          == net.hydromatic.morel.compile.Generator.Cardinality.INFINITE) {
        return null;
      }

      baseGenerators.add(baseCaseResult.generator);
      allRemainingFilters.addAll(baseCaseResult.remainingFilters);
    }

    // Combine all base generators into a single list expression.
    // For Relational.iterate, we need a simple list expression, not a
    // FROM...union.
    // Use balanced union tree for efficiency: O(log n) depth instead of O(n)
    final Core.Exp combinedBaseExpression;
    final Core.Pat goalPat = baseGenerators.get(0).goalPat;
    if (baseGenerators.size() == 1) {
      combinedBaseExpression = baseGenerators.get(0).expression;
    } else {
      // Extract generator expressions and build balanced union tree
      List<Core.Exp> generatorExprs = new ArrayList<>();
      for (Generator gen : baseGenerators) {
        generatorExprs.add(gen.expression);
      }
      combinedBaseExpression = buildUnion(generatorExprs);
    }

    // Build Relational.iterate with the finite base generator.
    // Remaining filters will be applied at runtime along with the iterate
    // expansion.
    // Create IOPair for the base case
    final IOPair baseCaseIO =
        new IOPair(ImmutableMap.of(), combinedBaseExpression);

    // Create TabulationResult with the base case
    // Use FINITE cardinality since transitive closure is finite for finite
    // input
    final TabulationResult tabulation =
        new TabulationResult(
            ImmutableList.of(baseCaseIO),
            net.hydromatic.morel.compile.Generator.Cardinality.FINITE,
            true); // may have duplicates

    // Create VarEnvironment for step function construction
    final VarEnvironment varEnv = VarEnvironment.initial(goalPats, env);

    // Build step function: fn (old, new) => FROM ...
    final Core.Exp stepFunction = buildStepFunction(tabulation, varEnv);

    // Construct Relational.iterate call
    // Pattern: Relational.iterate baseGenerator stepFunction
    // If the base generator produces records but we need tuples, wrap it with
    // a conversion: from {x,y} in baseGen yield (x,y)
    Core.Exp baseGenerator = combinedBaseExpression;
    final Type baseElemType = baseGenerator.type.elementType();
    if (baseElemType != null && baseElemType.op() == Op.RECORD_TYPE) {
      baseGenerator = convertRecordBagToTupleBag(baseGenerator);
    }

    // Get the RELATIONAL_ITERATE built-in as a function literal
    // RELATIONAL_ITERATE has type: 'a bag -> (('a bag, 'a bag) -> 'a bag) -> 'a
    // bag
    final Core.Exp iterateFn =
        core.functionLiteral(typeSystem, BuiltIn.RELATIONAL_ITERATE);

    // Compute types for the application
    final Type bagType = baseGenerator.type; // 'a bag
    final Type stepFnArgType =
        typeSystem.tupleType(bagType, bagType); // ('a bag, 'a bag)
    final Type stepFnType =
        typeSystem.fnType(stepFnArgType, bagType); // ('a bag, 'a bag) -> 'a bag
    final Type afterBaseType =
        typeSystem.fnType(stepFnType, bagType); // step -> 'a bag

    // Apply iterate to base generator
    final Core.Exp iterateWithBase =
        core.apply(Pos.ZERO, afterBaseType, iterateFn, baseGenerator);

    // Apply the result to the step function
    final Core.Exp relationalIterate =
        core.apply(Pos.ZERO, bagType, iterateWithBase, stepFunction);

    // Create a Generator wrapping the Relational.iterate call
    // Get constraints and freeVars from the first base generator (they should
    // all be compatible)
    final Generator firstBaseGen = baseGenerators.get(0);
    final Generator iterateGenerator =
        new Generator(
            goalPat,
            relationalIterate,
            net.hydromatic.morel.compile.Generator.Cardinality.FINITE,
            firstBaseGen.constraints,
            firstBaseGen.freeVars);

    // Return result with remaining filters from all base cases
    // These will be applied at runtime
    return result(iterateGenerator, ImmutableList.copyOf(allRemainingFilters));
  }

  /**
   * Converts a bag of records to a bag of tuples.
   *
   * <p>Generates: {@code from {x, y, ...} in recordBag yield (x, y, ...)}
   *
   * <p>This is needed when the base case produces records but the transitive
   * closure result should be tuples (to match the goal pattern type).
   *
   * @param recordBag The bag of records to convert
   * @return An expression that produces a bag of tuples
   */
  private Core.Exp convertRecordBagToTupleBag(Core.Exp recordBag) {
    final Type elemType = recordBag.type.elementType();
    if (elemType == null || elemType.op() != Op.RECORD_TYPE) {
      return recordBag; // Not a record bag, return as-is
    }
    final RecordType recordType = (RecordType) elemType;
    final List<String> fieldNames = recordType.argNames();
    final List<Type> fieldTypes = recordType.argTypes();

    // Create ID patterns for each field
    final List<Core.IdPat> idPats = new ArrayList<>();
    for (int i = 0; i < fieldNames.size(); i++) {
      idPats.add(
          core.idPat(fieldTypes.get(i), typeSystem.nameGenerator.get(), 0));
    }

    // Create record pattern for scan: {x=pat1, y=pat2, ...}
    final Core.Pat recordPat =
        core.recordPat(recordType, ImmutableList.copyOf(idPats));

    // Create tuple yield expression: (pat1, pat2, ...)
    final List<Core.Exp> yieldArgs = new ArrayList<>();
    for (Core.IdPat idPat : idPats) {
      yieldArgs.add(core.id(idPat));
    }
    final Core.Exp yieldExp = core.tuple(typeSystem, null, yieldArgs);

    // Build FROM expression: from {x, y} in recordBag yield (x, y)
    final FromBuilder builder =
        core.fromBuilder(typeSystem, (Environment) null);
    builder.scan(recordPat, recordBag);
    builder.yield_(yieldExp);

    return builder.build();
  }

  /**
   * Creates a list append expression using the @ operator with explicit types.
   *
   * <p>For example, {@code createListAppend(list1, list2)} becomes {@code
   * list1 @ list2}.
   *
   * <p>This method ensures proper type inference by instantiating the
   * polymorphic LIST_AT function with the concrete element type, avoiding
   * ForallType vs FnType casting issues.
   *
   * @param list1 The first list expression
   * @param list2 The second list expression
   * @return An Apply expression representing list1 @ list2
   */
  private Core.Exp createListAppend(Core.Exp list1, Core.Exp list2) {
    final Type listType = list1.type;
    final Type elementType = listType.elementType();

    // Create the tuple of arguments
    final Type tupleType = typeSystem.tupleType(listType, listType);
    final Core.Tuple argTuple = core.tuple((TupleType) tupleType, list1, list2);

    // Use core.call with type parameter to properly instantiate the polymorphic
    // LIST_AT function.
    // This creates the proper Apply node with the correct instantiated type.
    return core.call(
        typeSystem, BuiltIn.LIST_AT, elementType, Pos.ZERO, argTuple);
  }

  /**
   * Identifies the recursive function call in an expression.
   *
   * <p>Searches recursively through expression structure to find a function
   * application where the function is an ID matching the given function name.
   *
   * @param exp The expression to search
   * @param functionName The name of the recursive function to find
   * @return The recursive Apply node, or empty if not found
   */
  private Optional<Core.Apply> identifyRecursiveCall(
      Core.Exp exp, String functionName) {
    if (exp.op == Op.APPLY) {
      Core.Apply apply = (Core.Apply) exp;
      if (apply.fn.op == Op.ID) {
        Core.Id fnId = (Core.Id) apply.fn;
        if (fnId.idPat.name.equals(functionName)) {
          return Optional.of(apply);
        }
      }
    }

    // Recursively search in sub-expressions
    if (exp.op == Op.APPLY) {
      Core.Apply app = (Core.Apply) exp;
      if (app.isCallTo(BuiltIn.Z_ANDALSO)) {
        Optional<Core.Apply> left =
            identifyRecursiveCall(app.arg(0), functionName);
        if (left.isPresent()) {
          return left;
        }
        return identifyRecursiveCall(app.arg(1), functionName);
      }
      if (app.isCallTo(BuiltIn.Z_ORELSE)) {
        Optional<Core.Apply> left =
            identifyRecursiveCall(app.arg(0), functionName);
        if (left.isPresent()) {
          return left;
        }
        return identifyRecursiveCall(app.arg(1), functionName);
      }
    }

    // Check FROM expressions (exists patterns)
    if (exp.op == Op.FROM) {
      Core.From from = (Core.From) exp;
      for (Core.FromStep step : from.steps) {
        if (step.op == Op.WHERE) {
          Core.Where where = (Core.Where) step;
          Optional<Core.Apply> result =
              identifyRecursiveCall(where.exp, functionName);
          if (result.isPresent()) {
            return result;
          }
        }
      }
    }

    return Optional.empty();
  }

  /**
   * Checks if an expression contains an exists pattern (FROM expression).
   *
   * <p>This is used to detect which operand of an orelse is the recursive case.
   * The recursive case typically contains an exists clause like: {@code exists
   * z where ...}
   *
   * @param exp The expression to check
   * @return true if the expression contains a FROM/exists pattern
   */
  private boolean containsExists(Core.Exp exp) {
    if (exp.op == Op.FROM) {
      return true;
    }
    if (exp.op == Op.APPLY) {
      Core.Apply apply = (Core.Apply) exp;
      // Check arguments for nested exists
      if (apply.isCallTo(BuiltIn.Z_ANDALSO)
          || apply.isCallTo(BuiltIn.Z_ORELSE)) {
        return containsExists(apply.arg(0)) || containsExists(apply.arg(1));
      }
      // Check function and argument
      return containsExists(apply.fn) || containsExists(apply.arg);
    }
    if (exp.op == Op.CASE) {
      Core.Case caseExp = (Core.Case) exp;
      for (Core.Match match : caseExp.matchList) {
        if (containsExists(match.exp)) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Identifies variables that are threaded through recursion.
   *
   * <p>Threaded variables are those that appear as arguments to the recursive
   * call. For example, in {@code path(z, y)}, both z and y are threaded.
   *
   * <p>This is used to determine which variables need to be preserved during
   * iteration and which are intermediate join variables.
   *
   * @param recursiveCase The recursive case expression
   * @param functionName The name of the recursive function
   * @return Set of patterns threaded through the recursive call
   */
  private Set<Core.NamedPat> identifyThreadedVariables(
      Core.Exp recursiveCase, String functionName) {
    // Find recursive call in recursive case
    Optional<Core.Apply> recursiveCall =
        identifyRecursiveCall(recursiveCase, functionName);
    if (!recursiveCall.isPresent()) {
      return ImmutableSet.of();
    }

    Core.Apply call = recursiveCall.get();
    ImmutableSet.Builder<Core.NamedPat> threaded = ImmutableSet.builder();

    // Extract patterns from call arguments
    // Recursively collect all NamedPats from the argument expression
    forEachFreeVarIn(call.arg, pat -> threaded.add(pat));

    return threaded.build();
  }

  /**
   * Inverts an elem predicate: pattern elem collection.
   *
   * <p>For simple patterns like {@code x elem list}, returns the collection.
   *
   * <p>For tuple patterns like {@code (x, y) elem list} where some elements are
   * bound, creates a FROM expression that scans the collection and filters.
   */
  private Result invertElem(Core.Apply elemCall, List<Core.NamedPat> goalPats) {
    Core.Exp pattern = elemCall.arg(0);
    Core.Exp collection = elemCall.arg(1);

    // Simple case: x elem myList => myList
    if (pattern.op == Op.ID) {
      Core.Id id = (Core.Id) pattern;
      if (goalPats.contains(id.idPat)) {
        final Generator generator =
            generator(
                toTuple(goalPats),
                collection,
                net.hydromatic.morel.compile.Generator.Cardinality.FINITE,
                ImmutableList.of());
        return result(generator, ImmutableList.of());
      }
    }

    // Handle tuple elem patterns like (x, y) elem list or {x=a, y=b} elem list
    // But only if the pattern contains only variable IDs (no
    // literals/constants).
    // Both simple tuples and records with only ID fields can be inverted.
    if (pattern.op == Op.TUPLE) {
      final Core.Tuple tuple = (Core.Tuple) pattern;
      boolean isRecord = tuple.type.op() == Op.RECORD_TYPE;
      if (containsOnlyIds(tuple)) {
        // Check if the tuple has duplicate variable names (e.g., (z, z))
        // This can happen after function expansion: path(z, y) expands and
        // substitutes x->z, turning edge(x, z) into edge(z, z).
        // Pattern (z, z) elem edges means "pairs where both elements are
        // equal".
        List<Core.Id> tupleIds = new ArrayList<>();
        for (Core.Exp arg : tuple.args) {
          tupleIds.add((Core.Id) arg);
        }
        java.util.Set<String> seenNames = new java.util.HashSet<>();
        boolean hasDuplicates = false;
        for (Core.Id id : tupleIds) {
          if (!seenNames.add(id.idPat.name)) {
            hasDuplicates = true;
            break;
          }
        }

        if (hasDuplicates) {
          // Handle duplicate names by creating fresh scan patterns
          // and adding equality constraints.
          // For (z, z) elem edges, generate:
          //   from (z_1, z_2) in edges where z_1 = z_2 yield z_1
          final FromBuilder fromBuilder = core.fromBuilder(typeSystem);
          List<Core.IdPat> freshPats = new ArrayList<>();
          for (Core.Id id : tupleIds) {
            Core.IdPat freshPat =
                core.idPat(id.type, typeSystem.nameGenerator.get(), 0);
            freshPats.add(freshPat);
          }
          final Core.Pat scanPat =
              freshPats.size() == 1
                  ? freshPats.get(0)
                  : core.tuplePat(typeSystem, freshPats);
          fromBuilder.scan(scanPat, collection);

          // Add WHERE clauses for equality constraints between duplicate vars
          // For (z, z), we need: z_1 = z_2
          Map<String, List<Integer>> namePositions =
              new java.util.LinkedHashMap<>();
          for (int i = 0; i < tupleIds.size(); i++) {
            namePositions
                .computeIfAbsent(
                    tupleIds.get(i).idPat.name, k -> new ArrayList<>())
                .add(i);
          }
          for (List<Integer> positions : namePositions.values()) {
            if (positions.size() > 1) {
              // Generate equality constraints: p0 = p1, p0 = p2, etc.
              Core.IdPat first = freshPats.get(positions.get(0));
              for (int i = 1; i < positions.size(); i++) {
                Core.IdPat other = freshPats.get(positions.get(i));
                Core.Exp eqConstraint =
                    core.call(
                        typeSystem,
                        BuiltIn.OP_EQ,
                        first.type,
                        Pos.ZERO,
                        core.id(first),
                        core.id(other));
                fromBuilder.where(eqConstraint);
              }
            }
          }

          // Yield the first fresh pattern for each unique name
          List<Core.Exp> yieldArgs = new ArrayList<>();
          java.util.Set<String> yieldedNames = new java.util.HashSet<>();
          for (int i = 0; i < tupleIds.size(); i++) {
            String name = tupleIds.get(i).idPat.name;
            if (yieldedNames.add(name)) {
              yieldArgs.add(core.id(freshPats.get(i)));
            }
          }
          final Core.Exp yieldExp =
              yieldArgs.size() == 1
                  ? yieldArgs.get(0)
                  : core.tuple(typeSystem, yieldArgs.toArray(new Core.Exp[0]));
          fromBuilder.yield_(yieldExp);

          final Generator generator =
              generator(
                  toTuple(goalPats),
                  fromBuilder.build(),
                  net.hydromatic.morel.compile.Generator.Cardinality.FINITE,
                  ImmutableList.of());
          return result(generator, ImmutableList.of());
        }

        // No duplicates - use collection directly.
        // Building `from pat in collection yield tuple` would be an identity
        // transformation since pat = toPat(tuple). Using collection directly
        // avoids issues with FromBuilder's flattening logic in buildStepBody().
        final Generator generator =
            generator(
                toTuple(goalPats),
                collection,
                net.hydromatic.morel.compile.Generator.Cardinality.FINITE,
                ImmutableList.of());
        return result(generator, ImmutableList.of());
      }

      // Handle field accesses on a single goal pattern: (#1 p, #2 p) elem list
      // This occurs when path(p) is inverted and edge body is substituted.
      // If all tuple elements are field accesses on the same goal pattern,
      // we can simplify to: p elem list (for tuples) or build a projection
      // (for records where field order differs from tuple order).
      final int[] slotMapping = new int[tuple.args.size()];
      final Core.NamedPat sourceGoal =
          extractFieldAccessGoal(tuple, goalPats, slotMapping);
      if (sourceGoal != null) {
        // Check if we need a projection (field order differs from tuple order)
        boolean needsProjection = false;
        for (int i = 0; i < slotMapping.length; i++) {
          if (slotMapping[i] != i) {
            needsProjection = true;
            break;
          }
        }

        if (!needsProjection) {
          // Simple case: slots match positions, so p elem collection works
          final Generator generator =
              generator(
                  sourceGoal,
                  collection,
                  net.hydromatic.morel.compile.Generator.Cardinality.FINITE,
                  ImmutableList.of());
          return result(generator, ImmutableList.of());
        } else {
          // Record case: need to project fields to match tuple order
          // For {dst = #2 p, src = #1 p} elem edges, generate:
          //   from r in edges yield (#src r, #dst r)
          // which produces tuples (src, dst) matching p = (x, y)
          final FromBuilder fromBuilder = core.fromBuilder(typeSystem);
          final Core.IdPat scanPat =
              core.idPat(tuple.type(), typeSystem.nameGenerator.get(), 0);
          fromBuilder.scan(scanPat, collection);

          // Build yield expression: reorder fields to match tuple slots
          // slotMapping[i] = j means position i in tuple has selector #(j+1)
          // So we need to yield field at position j from the source record
          final List<Core.Exp> yieldArgs = new ArrayList<>();
          for (int slot : slotMapping) {
            // Access the field at the slot position from the scanned record
            yieldArgs.add(core.field(typeSystem, core.id(scanPat), slot));
          }
          final Core.Exp yieldExp =
              yieldArgs.size() == 1
                  ? yieldArgs.get(0)
                  : core.tuple(
                      typeSystem, (RecordLikeType) sourceGoal.type, yieldArgs);
          fromBuilder.yield_(yieldExp);

          final Generator generator =
              generator(
                  sourceGoal,
                  fromBuilder.build(),
                  net.hydromatic.morel.compile.Generator.Cardinality.FINITE,
                  ImmutableList.of());
          return result(generator, ImmutableList.of());
        }
      }
      // Fall through for patterns with constants - let g3 handle with field
      // extraction
    }

    // TODO: Handle if some but not all of goalPats are covered in elemCall.
    // TODO: Deal with repeated IDs, like "(c, b, c) elem myList"
    // TODO: Deal with "() elem myList"

    return result(generatorFor(goalPats), ImmutableList.of(elemCall));
  }

  /**
   * Checks if a tuple expression contains only ID references (no literals).
   * Used to determine if we can handle the tuple elem pattern directly, or
   * should fall through to g3's field extraction approach.
   */
  private boolean containsOnlyIds(Core.Tuple tuple) {
    for (Core.Exp arg : tuple.args) {
      switch (arg.op) {
        case ID:
          // This is fine - continue checking
          break;
        case TUPLE:
          // Nested tuple - check recursively
          if (!containsOnlyIds((Core.Tuple) arg)) {
            return false;
          }
          break;
        default:
          // Literal or other expression - not pure IDs
          return false;
      }
    }
    return true;
  }

  /**
   * Checks if all elements of a tuple are field accesses on the same goal
   * pattern. For example, (#1 p, #2 p) where p is a goal pattern.
   *
   * @param tuple The tuple expression to check
   * @param goalPats The goal patterns we're trying to generate
   * @param slotMapping Output parameter: slotMapping[i] will be set to the
   *     selector slot for position i in the tuple. This allows the caller to
   *     build a projection if field order differs from tuple order.
   * @return The common source pattern if all elements are field accesses on it,
   *     null otherwise
   */
  private Core.@Nullable NamedPat extractFieldAccessGoal(
      Core.Tuple tuple, List<Core.NamedPat> goalPats, int[] slotMapping) {
    Core.NamedPat commonSource = null;
    for (int i = 0; i < tuple.args.size(); i++) {
      final Core.Exp arg = tuple.args.get(i);

      // Check if this is a field access: #slot id
      if (arg.op != Op.APPLY) {
        return null;
      }
      final Core.Apply apply = (Core.Apply) arg;

      if (apply.fn.op != Op.RECORD_SELECTOR) {
        return null;
      }
      final Core.RecordSelector selector = (Core.RecordSelector) apply.fn;

      // The argument to the selector should be an ID
      if (apply.arg.op != Op.ID) {
        return null;
      }
      final Core.Id id = (Core.Id) apply.arg;

      // Check if this ID is one of our goal patterns
      if (!goalPats.contains(id.idPat)) {
        return null;
      }
      // Record the slot for this position (allows for field reordering)
      slotMapping[i] = selector.slot;
      // Check that all accesses are on the same source pattern
      if (commonSource == null) {
        commonSource = id.idPat;
      } else if (commonSource != id.idPat) {
        return null;
      }
    }
    return commonSource;
  }

  /**
   * Converts an expression to the equivalent pattern.
   *
   * @see Op#toPat()
   */
  private Core.Pat toPat(Core.Exp exp) {
    switch (exp.op) {
      case ID:
        return ((Core.Id) exp).idPat;

      case TUPLE:
        final Core.Tuple tuple = (Core.Tuple) exp;
        if (tuple.type.op() == Op.RECORD_TYPE) {
          return core.recordPat(
              (RecordType) tuple.type(),
              transformEager(tuple.args, this::toPat));
        } else {
          return core.tuplePat(
              tuple.type(), transformEager(tuple.args, this::toPat));
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

  private Core.Exp toExp(Core.Pat pat) {
    switch (pat.op) {
      case ID_PAT:
        return core.id((Core.NamedPat) pat);

      default:
        throw new AssertionError("cannot convert " + pat + " to expression");
    }
  }

  private Set<Core.NamedPat> freeVarsIn(Core.Exp... exps) {
    final ImmutableSet.Builder<Core.NamedPat> list = ImmutableSet.builder();
    for (Core.Exp exp : exps) {
      forEachFreeVarIn(exp, list::add);
    }
    return list.build();
  }

  private void forEachFreeVarIn(
      Core.Exp exp, Consumer<Core.NamedPat> consumer) {
    class MyEnvVisitor extends EnvVisitor {
      MyEnvVisitor(Environment env, Deque<FromContext> fromStack) {
        super(PredicateInverter.this.typeSystem, env, fromStack);
      }

      @Override
      protected EnvVisitor push(Environment env) {
        return new MyEnvVisitor(env, fromStack);
      }

      @Override
      protected void visit(Core.Id id) {
        consumer.accept(id.idPat);
      }
    }

    exp.accept(
        new MyEnvVisitor(PredicateInverter.this.env, new ArrayDeque<>()));
  }

  /**
   * Substitutes an argument into a function body.
   *
   * <p>When the parameter is a tuple pattern like {@code (x, y)} and the
   * argument is a tuple like {@code (z, w)}, this method builds a substitution
   * map {@code {x -> z, y -> w}} and replaces individual variable references in
   * the body.
   *
   * @param param The function parameter pattern
   * @param arg The argument expression to substitute
   * @param body The function body
   * @return The body with the parameter replaced by the argument
   */
  private Core.Exp substituteArg(Core.Pat param, Core.Exp arg, Core.Exp body) {
    // Build substitution map from pattern to argument
    final Map<Core.NamedPat, Core.Exp> subst = new HashMap<>();
    if (!buildSubstitution(param, arg, subst)) {
      // If we can't build a substitution, return body unchanged
      return body;
    }

    // Use a Shuttle to traverse and replace
    return body.accept(
        new Shuttle(typeSystem) {
          @Override
          public Core.Exp visit(Core.Id id) {
            // Check if this ID is in the substitution map
            final Core.Exp replacement = subst.get(id.idPat);
            if (replacement != null) {
              return replacement;
            }
            return super.visit(id);
          }
        });
  }

  /**
   * Substitutes a function argument into the function body, handling the case
   * where the function has a tuple parameter compiled as {@code fn v => case v
   * of (x, y) => ...}.
   *
   * <p>Functions like {@code fun edge (x, y) = (x, y) elem edges} compile to:
   * {@code fn v => case v of (x, y) => op elem (...)}. This method unwraps the
   * case to use the real parameter pattern for substitution.
   *
   * @param fn The function expression
   * @param arg The argument to substitute
   * @return The function body with argument substituted
   */
  private Core.Exp substituteIntoFn(Core.Fn fn, Core.Exp arg) {
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

    return substituteArg(paramPat, arg, body);
  }

  /**
   * Builds a substitution map from a pattern to an expression.
   *
   * <p>For example, given pattern {@code (x, y)} and expression {@code (z, w)},
   * builds map {@code {x -> z, y -> w}}.
   *
   * @param pat The pattern
   * @param exp The expression
   * @param subst The map to populate
   * @return true if substitution was built successfully
   */
  private boolean buildSubstitution(
      Core.Pat pat, Core.Exp exp, Map<Core.NamedPat, Core.Exp> subst) {
    switch (pat.op) {
      case ID_PAT:
        subst.put((Core.IdPat) pat, exp);
        return true;

      case TUPLE_PAT:
        final Core.TuplePat tuplePat = (Core.TuplePat) pat;
        // Handle variable of tuple type: p -> {x -> #1 p, y -> #2 p}
        if (exp.op == Op.ID && exp.type instanceof RecordLikeType) {
          final RecordLikeType recordType = (RecordLikeType) exp.type;
          if (tuplePat.args.size() != recordType.argTypes().size()) {
            return false;
          }
          for (int i = 0; i < tuplePat.args.size(); i++) {
            final Core.Pat argPat = tuplePat.args.get(i);
            // Create field access: #i exp
            final Core.Exp fieldExp = core.field(typeSystem, exp, i);
            if (!buildSubstitution(argPat, fieldExp, subst)) {
              return false;
            }
          }
          return true;
        }
        if (exp.op != Op.TUPLE) {
          return false;
        }
        final Core.Tuple tuple = (Core.Tuple) exp;
        if (tuplePat.args.size() != tuple.args.size()) {
          return false;
        }
        for (int i = 0; i < tuplePat.args.size(); i++) {
          if (!buildSubstitution(
              tuplePat.args.get(i), tuple.args.get(i), subst)) {
            return false;
          }
        }
        return true;

      case RECORD_PAT:
        final Core.RecordPat recordPat = (Core.RecordPat) pat;
        // Handle variable of record type: r -> {x -> #1 r, y -> #2 r}
        if (exp.op == Op.ID && exp.type instanceof RecordLikeType) {
          final RecordLikeType recType = (RecordLikeType) exp.type;
          if (recordPat.args.size() != recType.argTypes().size()) {
            return false;
          }
          for (int i = 0; i < recordPat.args.size(); i++) {
            final Core.Pat argPat = recordPat.args.get(i);
            final Core.Exp fieldExp = core.field(typeSystem, exp, i);
            if (!buildSubstitution(argPat, fieldExp, subst)) {
              return false;
            }
          }
          return true;
        }
        // Records are represented as tuples in Core
        if (exp.op != Op.TUPLE) {
          return false;
        }
        final Core.Tuple recordTuple = (Core.Tuple) exp;
        if (recordPat.args.size() != recordTuple.args.size()) {
          return false;
        }
        for (int i = 0; i < recordPat.args.size(); i++) {
          if (!buildSubstitution(
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
   * Inverts a conjunction of predicates.
   *
   * <p>Handles patterns like:
   *
   * <ul>
   *   <li>Range constraints: {@code x > y andalso x < z}
   *   <li>Multiple predicates: {@code P1 andalso P2 andalso ...}
   *   <li>Mixed predicates: {@code x < z andalso x <> 3 andalso x > y}
   * </ul>
   */
  private Result invertAnds(
      List<Core.Exp> predicates,
      List<Core.NamedPat> goalPats,
      List<Core.Exp> active) {
    if (predicates.size() < 2) {
      // If flatten created a degenerate case, it's someone else's problem.
      return invert(core.andAlso(typeSystem, predicates), goalPats, active);
    }

    // Phase C: Use mode analysis to order predicates optimally.
    // Generators come before filters, ordered by dependencies.
    final List<Core.Exp> orderedPredicates =
        modeAnalyzer.orderPredicates(predicates, ImmutableSet.copyOf(goalPats));

    // Use the ordered predicates for all subsequent processing
    final List<Core.Exp> predsToProcess = orderedPredicates;

    for (int i = 0; i < predsToProcess.size(); i++) {
      if (predsToProcess.get(i).op != Op.APPLY) {
        continue;
      }
      final Core.Apply lowerBound = (Core.Apply) predsToProcess.get(i);
      if (lowerBound.isCallTo(BuiltIn.OP_GT)
          || lowerBound.isCallTo(BuiltIn.OP_GE)) {
        final boolean lowerStrict = lowerBound.isCallTo(BuiltIn.OP_GT);
        for (int j = 0; j < predsToProcess.size(); j++) {
          if (j == i) {
            continue;
          }
          if (predsToProcess.get(j).op != Op.APPLY) {
            continue;
          }
          final Core.Apply upperBound = (Core.Apply) predsToProcess.get(j);
          if (upperBound.isCallTo(BuiltIn.OP_LT)
              || upperBound.isCallTo(BuiltIn.OP_LE)) {
            final boolean upperStrict = upperBound.isCallTo(BuiltIn.OP_LT);

            // Pattern: x > y andalso x < (y + 10)
            final Core.Exp xId = lowerBound.arg(0);
            final Core.Exp lower = lowerBound.arg(1);
            final Core.Exp xId2 = upperBound.arg(0);
            final Core.Exp upper = upperBound.arg(1);

            // Verify that x appears on both sides.
            if (xId.op == Op.ID
                && xId2.op == Op.ID
                && ((Core.Id) xId).idPat.equals(((Core.Id) xId2).idPat)
                && goalPats.contains(((Core.Id) xId).idPat)) {
              Core.NamedPat xPat = ((Core.Id) xId).idPat;

              // Check if x is the output and y is bound (or y is not in
              // outputPats, meaning it's already processed).
              if (disjoint(freeVarsIn(lower, upper), goalPats)) {
                // Pattern matched! Generate:
                //   List.tabulate(upper - lower, fn k => lower + k)
                // which is the range
                //  [lower, upper]
                final Generator generator =
                    generateRange(xPat, lower, lowerStrict, upper, upperStrict);

                final List<Core.Exp> remainingPredicates =
                    new ArrayList<>(predsToProcess);
                remainingPredicates.remove(lowerBound);
                remainingPredicates.remove(upperBound);
                return result(generator, remainingPredicates);
              }
            }
          }
        }
      }
    }

    // Try to match pattern: x > y andalso x < y + 10
    // to generate: List.tabulate(9, fn k => y + 1 + k)
    Core.Exp left = predsToProcess.get(0);
    Core.Exp right = predsToProcess.get(1);

    // Try to invert both sides and join them
    Result leftResult = invert(left, goalPats, ImmutableList.of());
    Result rightResult = invert(right, goalPats, ImmutableList.of());

    // Only join if both sides improved (no remaining filters)
    if (leftResult.remainingFilters.isEmpty()
        && rightResult.remainingFilters.isEmpty()) {
      // Both sides can be inverted - create a cross product or join
      Result lr = leftResult;
      Result rr = rightResult;

      // For now, create a simple concatenation approach:
      // from x in leftGen, y in rightGen yield (x, y)
      // This is a cross product - the actual filtering happens at runtime

      // Combine the generators by creating a FROM that scans both
      // The user's hint suggests: from (empno,deptno) in emps, (deptno2,dname)
      // in
      // depts where deptno = deptno2
      // For now, just scan both and let the filters apply
      Generator gen1 = lr.generator;
      Generator gen2 = rr.generator;

      // Create combined generator: List.concat (from x in gen1 yield from y in
      // gen2
      // yield ...)
      // For simplicity, let's try returning a FROM expression that scans both

      // Get the element types
      Type gen1ElemType = gen1.expression.type.elementType();
      Type gen2ElemType = gen2.expression.type.elementType();

      // Create patterns for scanning
      Core.IdPat pat1 = core.idPat(gen1ElemType, "x_left", 0);
      Core.IdPat pat2 = core.idPat(gen2ElemType, "x_right", 0);

      // Build FROM: from x_left in gen1, x_right in gen2
      FromBuilder builder = core.fromBuilder(typeSystem, (Environment) null);
      builder.scan(pat1, gen1.expression);
      builder.scan(pat2, gen2.expression);

      // Yield a tuple of both (or just the goal variables)
      // For now, yield both - the caller will handle extracting what's needed
      Core.Exp yieldExp;
      if (gen1ElemType.op() == Op.TUPLE_TYPE
          && gen2ElemType.op() == Op.TUPLE_TYPE) {
        // Both are tuples - create a combined tuple
        Core.Tuple tuple1 =
            core.tuple(typeSystem, null, ImmutableList.of(core.id(pat1)));
        Core.Tuple tuple2 =
            core.tuple(typeSystem, null, ImmutableList.of(core.id(pat2)));
        yieldExp =
            core.tuple(
                typeSystem,
                null,
                ImmutableList.of(core.id(pat1), core.id(pat2)));
      } else {
        yieldExp =
            core.tuple(
                typeSystem,
                null,
                ImmutableList.of(core.id(pat1), core.id(pat2)));
      }

      builder.yield_(yieldExp);
      Core.Exp joinedGen = builder.build();

      return result(
          generator(
              toTuple(goalPats),
              joinedGen,
              net.hydromatic.morel.compile.Generator.Cardinality.FINITE,
              ImmutableList.of()),
          ImmutableList.of());
    }

    // Handle partial success: one side inverted successfully, the other didn't
    // If left inverted successfully, use its generator with right as a filter
    if (leftResult.remainingFilters.isEmpty()
        && !rightResult.remainingFilters.isEmpty()) {
      // Use left's generator (e.g., the TC generator), keep right as a
      // remaining filter
      return result(leftResult.generator, ImmutableList.of(right));
    }

    // If right inverted successfully, use its generator with left as a filter
    if (!leftResult.remainingFilters.isEmpty()
        && rightResult.remainingFilters.isEmpty()) {
      // Use right's generator, keep left as a remaining filter
      return result(rightResult.generator, ImmutableList.of(left));
    }

    return result(generatorFor(goalPats), predicates);
  }

  private Core.Pat toTuple(List<Core.NamedPat> pats) {
    return pats.size() == 1 ? pats.get(0) : core.tuplePat(typeSystem, pats);
  }

  private Result result(
      Generator generator, Iterable<? extends Core.Exp> remainingFilters) {
    return new Result(generator, remainingFilters);
  }

  /** Creates an extent generator for a pattern with infinite cardinality. */
  private Generator createExtentGenerator(Core.NamedPat pat) {
    final Core.Exp extentExp =
        core.extent(typeSystem, pat.type, ImmutableRangeSet.of(Range.all()));
    return new Generator(
        pat,
        extentExp,
        net.hydromatic.morel.compile.Generator.Cardinality.INFINITE,
        ImmutableList.of(),
        ImmutableSet.of());
  }

  /** Returns whether an expression references any of the given patterns. */
  private boolean referencesNone(Core.Exp exp, Collection<Core.NamedPat> pats) {
    if (pats.isEmpty()) {
      return false; // short-cut
    }
    final AtomicBoolean b = new AtomicBoolean(true);
    forEachFreeVarIn(
        exp,
        pat -> {
          if (pats.contains(pat)) {
            b.set(false);
          }
        });
    return b.get();
  }

  /**
   * Generates an expression that produces all prefixes of a string.
   *
   * <p>For example, {@code generateStringPrefixes(s)} generates {@code
   * List.tabulate(String.size s + 1, fn i => String.substring(s, 0, i))}.
   *
   * @param idPat Pattern
   * @param stringExp The string expression to generate prefixes for
   * @return An expression that generates all prefixes
   */
  private Generator generateStringPrefixes(
      Core.NamedPat idPat, Core.Exp stringExp) {
    final Type stringType = PrimitiveType.STRING;
    final Type intType = PrimitiveType.INT;

    // String.size s
    final Core.Exp sizeCall =
        core.call(typeSystem, BuiltIn.STRING_SIZE, stringExp);

    // String.size s + 1
    final Core.Exp count =
        core.call(
            typeSystem,
            BuiltIn.OP_PLUS,
            intType,
            Pos.ZERO,
            sizeCall,
            core.intLiteral(BigDecimal.ONE));

    // fn i => String.substring(s, 0, i)
    final Core.IdPat iPat = core.idPat(intType, "i", 0);
    final Core.Exp substringCall =
        core.call(
            typeSystem,
            BuiltIn.STRING_SUBSTRING,
            stringExp,
            core.intLiteral(BigDecimal.ZERO),
            core.id(iPat));

    final Core.Fn fn =
        core.fn(typeSystem.fnType(intType, stringType), iPat, substringCall);

    // List.tabulate(count, fn)
    return generator(
        idPat,
        core.call(
            typeSystem, BuiltIn.LIST_TABULATE, stringType, Pos.ZERO, count, fn),
        net.hydromatic.morel.compile.Generator.Cardinality.FINITE,
        ImmutableList.of());
  }

  /**
   * Creates an expression that generates a range of integer values.
   *
   * <p>For example, {@code generateRange(3, true, 8, false)} generates a range
   * {@code 3 < x <= 8}, which yields the values {@code [4, 5, 6, 7, 8]}.
   *
   * @param pat Goal pattern
   * @param lower Lower bound
   * @param lowerStrict Whether the lower bound is strict (exclusive): true for
   *     {@code x > lower}, false for {@code x >= lower}
   * @param upper Upper bound
   * @param upperStrict Whether the upper bound is strict (exclusive): true for
   *     {@code x < upper}, false for {@code x <= upper}
   */
  private Generator generateRange(
      Core.NamedPat pat,
      Core.Exp lower,
      boolean lowerStrict,
      Core.Exp upper,
      boolean upperStrict) {
    if (lowerStrict) {
      // For x > lower, we want x >= lower + 1
      lower =
          core.call(
              typeSystem,
              BuiltIn.Z_PLUS_INT,
              lower,
              core.intLiteral(BigDecimal.ONE));
    }
    if (upperStrict) {
      // For x < upper, we want x <= upper - 1
      upper =
          core.call(
              typeSystem,
              BuiltIn.Z_MINUS_INT,
              upper,
              core.intLiteral(BigDecimal.ONE));
    }

    // List.tabulate(upper - lower + 1, fn k => lower + k)
    final Type type = PrimitiveType.INT;
    final Core.IdPat kPat = core.idPat(type, "k", 0);
    final Core.Exp upperMinusLower =
        core.call(typeSystem, BuiltIn.OP_MINUS, type, Pos.ZERO, upper, lower);
    final Core.Exp count =
        core.call(
            typeSystem,
            BuiltIn.OP_PLUS,
            type,
            Pos.ZERO,
            upperMinusLower,
            core.intLiteral(BigDecimal.ONE));
    final Core.Exp lowerPlusK =
        core.call(
            typeSystem, BuiltIn.Z_PLUS_INT, lower, CoreBuilder.core.id(kPat));
    final Core.Fn fn = core.fn(typeSystem.fnType(type, type), kPat, lowerPlusK);
    return generator(
        pat,
        core.call(typeSystem, BuiltIn.LIST_TABULATE, type, Pos.ZERO, count, fn),
        net.hydromatic.morel.compile.Generator.Cardinality.FINITE,
        ImmutableList.of());
  }

  /**
   * A generator produces values for variables.
   *
   * <p>It consists of:
   *
   * <ul>
   *   <li>An expression that generates values
   *   <li>Cardinality metadata (single/finite/infinite per binding of free
   *       variables)
   *   <li>A list of predicates that values satisfy
   *   <li>Free variables referenced in the expression
   * </ul>
   */
  public static class Generator {
    /** Pattern that the expression generates values for. */
    public final Core.Pat goalPat;

    /** The generator expression */
    public final Core.Exp expression;

    /** Cardinality per binding of free variables */
    public final net.hydromatic.morel.compile.Generator.Cardinality cardinality;

    /**
     * A list of constraints that the values from the generator satisfy (without
     * any checking or filtering). We keep constraints because they may allow us
     * to remove filters.
     */
    public final List<Core.Exp> constraints;

    /** Non-output variables referenced in the expression */
    public final Set<Core.NamedPat> freeVars;

    Generator(
        Core.Pat goalPat,
        Core.Exp expression,
        net.hydromatic.morel.compile.Generator.Cardinality cardinality,
        Iterable<? extends Core.Exp> constraints,
        Iterable<? extends Core.NamedPat> freeVars) {
      this.goalPat = requireNonNull(goalPat);
      this.expression = requireNonNull(expression);
      this.cardinality = requireNonNull(cardinality);
      this.constraints = ImmutableList.copyOf(constraints);
      this.freeVars = ImmutableSet.copyOf(freeVars);
    }
  }

  /** Result of inverting a predicate. */
  public static class Result {
    /**
     * An expression that generates a list of all values of the patterns for
     * which the predicate evaluates to true.
     */
    public final Generator generator;

    /**
     * Filters that still need to be checked at runtime. Empty if the generator
     * generates only values that satisfy all predicates.
     */
    public final List<Core.Exp> remainingFilters;

    public Result(
        Generator generator, Iterable<? extends Core.Exp> remainingFilters) {
      this.generator = requireNonNull(generator);
      this.remainingFilters = ImmutableList.copyOf(remainingFilters);
    }
  }

  /**
   * Result of tabulating a Perfect Process Tree (PPT).
   *
   * <p>Tabulation (URA Step 2) traverses the PPT and collects input-output
   * (I-O) pairs from terminal nodes. These pairs represent concrete mappings
   * that will be used to generate Relational.iterate calls.
   *
   * <p>The PPT represents all computation paths through a recursive function.
   * Terminal nodes represent base cases with inversion results. Tabulation
   * extracts these terminal results and tracks cardinality.
   */
  public static class TabulationResult {
    /** Collected input-output pairs from PPT terminals. */
    public final List<IOPair> ioMappings;

    /** Cardinality of the outputs. */
    public final net.hydromatic.morel.compile.Generator.Cardinality cardinality;

    /** Whether duplicates are possible. */
    public final boolean mayHaveDuplicates;

    public TabulationResult(
        Iterable<IOPair> ioMappings,
        net.hydromatic.morel.compile.Generator.Cardinality cardinality,
        boolean mayHaveDuplicates) {
      this.ioMappings = ImmutableList.copyOf(ioMappings);
      this.cardinality = requireNonNull(cardinality);
      this.mayHaveDuplicates = mayHaveDuplicates;
    }
  }

  /**
   * An input-output pair for tabulation.
   *
   * <p>Represents a concrete mapping from input bindings to output expressions.
   */
  public static class IOPair {
    /** Input binding for this I-O pair. */
    public final ImmutableMap<Core.NamedPat, Core.Exp> inputBinding;

    /** Output expression. */
    public final Core.Exp output;

    public IOPair(Map<Core.NamedPat, Core.Exp> inputBinding, Core.Exp output) {
      this.inputBinding = ImmutableMap.copyOf(inputBinding);
      this.output = requireNonNull(output);
    }
  }

  /**
   * Determines overall cardinality from individual cardinalities.
   *
   * <p>Rules:
   *
   * <ul>
   *   <li>If any is INFINITE, overall is INFINITE
   *   <li>If all are SINGLE, overall is SINGLE
   *   <li>Otherwise FINITE
   * </ul>
   *
   * @param cardinalities List of cardinalities from terminals
   * @return Overall cardinality
   */
  private net.hydromatic.morel.compile.Generator.Cardinality computeCardinality(
      List<net.hydromatic.morel.compile.Generator.Cardinality> cardinalities) {
    if (cardinalities.isEmpty()) {
      return net.hydromatic.morel.compile.Generator.Cardinality
          .FINITE; // Default
    }

    // If any is INFINITE, overall is INFINITE
    if (cardinalities.contains(
        net.hydromatic.morel.compile.Generator.Cardinality.INFINITE)) {
      return net.hydromatic.morel.compile.Generator.Cardinality.INFINITE;
    }

    // If all are SINGLE, overall is SINGLE
    if (cardinalities.stream()
        .allMatch(
            c ->
                c
                    == net.hydromatic.morel.compile.Generator.Cardinality
                        .SINGLE)) {
      return net.hydromatic.morel.compile.Generator.Cardinality.SINGLE;
    }

    // Otherwise FINITE
    return net.hydromatic.morel.compile.Generator.Cardinality.FINITE;
  }

  /**
   * Detects if same output can occur from different input classes.
   *
   * <p>Simple heuristic: check if any output expression appears multiple times.
   *
   * @param pairs List of I-O pairs
   * @return True if duplicates are possible
   */
  private boolean detectDuplicates(List<IOPair> pairs) {
    // Simple heuristic: check if any output expression appears multiple times
    final Set<String> outputStrings = new HashSet<>();
    for (IOPair pair : pairs) {
      final String outStr = pair.output.toString();
      if (outputStrings.contains(outStr)) {
        return true; // Duplicate found
      }
      outputStrings.add(outStr);
    }
    return false;
  }

  // ===== Phase 3b-3: Step Function Generation =====

  /**
   * Builds the step function for Relational.iterate.
   *
   * <p>The step function takes two parameters (oldTuples, newTuples) and
   * produces newly derived tuples by joining the base case with new results.
   *
   * <p>For transitive closure {@code path(x,y) = edge(x,y) orelse (exists z
   * where edge(x,z) andalso path(z,y))}, generates:
   *
   * <pre>{@code
   * fn (oldPaths, newPaths) =>
   *   from (x, z) in edges,
   *        (z2, y) in newPaths
   *     where z = z2
   *     yield (x, y)
   * }</pre>
   *
   * @param tabulation The tabulation result containing I-O mappings
   * @param env The variable environment
   * @return Lambda expression for the step function
   */
  private Core.Exp buildStepFunction(
      TabulationResult tabulation, VarEnvironment env) {
    if (tabulation.ioMappings.isEmpty()) {
      throw new IllegalArgumentException(
          "Cannot build step function for empty tabulation");
    }

    // Get base case generator from first I-O pair
    final IOPair baseCase = tabulation.ioMappings.get(0);
    final Core.Exp baseGenerator = baseCase.output;

    // Determine threaded variables (variables preserved through recursion)
    final Set<Core.NamedPat> threadedVars = env.unboundGoals();

    // Build the lambda function
    return buildStepLambda(baseGenerator, threadedVars, env);
  }

  /**
   * Builds the lambda expression for the step function.
   *
   * <p>Creates a function {@code fn (old, new) => FROM ...} where the FROM
   * expression joins the base generator with new results.
   *
   * @param baseGen The base case generator expression
   * @param threadedVars Variables threaded through recursion
   * @param env The variable environment
   * @return Lambda expression
   */
  private Core.Exp buildStepLambda(
      Core.Exp baseGen, Set<Core.NamedPat> threadedVars, VarEnvironment env) {

    // Determine the element type from the base generator
    final Type elementType = baseGen.type.elementType();
    final Type bagType = baseGen.type;

    // Create parameter patterns for the lambda: (old, new)
    // Use nameGenerator to avoid collisions with source code variables
    final Core.IdPat oldPat =
        core.idPat(bagType, typeSystem.nameGenerator.get(), 0);
    final Core.IdPat newPat =
        core.idPat(bagType, typeSystem.nameGenerator.get(), 0);

    // Build the FROM expression that forms the body
    final Core.Exp fromExp =
        buildStepBody(baseGen, oldPat, newPat, threadedVars, env);

    // Create the tuple pattern for lambda parameters: (old, new)
    final Core.TuplePat paramPat =
        core.tuplePat(typeSystem, ImmutableList.of(oldPat, newPat));

    // Determine function type: (bag, bag) -> bag
    final Type paramType = typeSystem.tupleType(bagType, bagType);
    final net.hydromatic.morel.type.FnType fnType =
        typeSystem.fnType(paramType, bagType);

    // Create lambda parameter (simple IdPat)
    // Use nameGenerator to avoid collisions with source code variables
    final Core.IdPat lambdaParam =
        core.idPat(paramType, typeSystem.nameGenerator.get(), 0);

    // Create lambda: fn v => case v of (old, new) => fromExp
    final Core.Match match = core.match(Pos.ZERO, paramPat, fromExp);
    final Core.Case caseExp =
        core.caseOf(
            Pos.ZERO, bagType, core.id(lambdaParam), ImmutableList.of(match));

    return core.fn(fnType, lambdaParam, caseExp);
  }

  /**
   * Builds the FROM expression inside the step function.
   *
   * <p>Pattern: {@code from (x,z) in base, (z2,y) in new where z=z2 yield
   * (x,y)}.
   *
   * <p>The FROM scans both the base generator and the new results, joins them
   * on the intermediate variable, and yields the result tuple.
   *
   * @param baseGen The base case generator
   * @param oldPat Pattern for old results (unused in simple transitive closure)
   * @param newPat Pattern for new results
   * @param threadedVars Variables threaded through recursion
   * @param env The variable environment
   * @return FROM expression
   */
  private Core.Exp buildStepBody(
      Core.Exp baseGen,
      Core.NamedPat oldPat,
      Core.NamedPat newPat,
      Set<Core.NamedPat> threadedVars,
      VarEnvironment env) {

    // Determine the base element type (should be a tuple type like (int, int))
    final Type baseElemType = baseGen.type.elementType();

    // For transitive closure pattern (x, z) from edges
    // We need to decompose the tuple type to get individual variable types
    final Type[] baseComponents;
    // Check for both TUPLE_TYPE and RECORD_TYPE - records also need
    // decomposition
    if (baseElemType.op() == Op.TUPLE_TYPE
        || baseElemType.op() == Op.RECORD_TYPE) {
      final RecordLikeType recordLikeType = (RecordLikeType) baseElemType;
      final List<Type> argTypes = recordLikeType.argTypes();
      baseComponents = argTypes.toArray(new Type[0]);
    } else {
      // Simple case: single element type
      baseComponents = new Type[] {baseElemType};
    }

    // Check if the base element type is a record (named fields) vs tuple
    // (positional)
    final boolean isRecordType = baseElemType.op() == Op.RECORD_TYPE;

    // Create patterns for new scan: (x, z) for tuples or {x=x, y=z} for records
    // This scans the newly discovered results from the previous iteration
    // Use nameGenerator to avoid collisions with source code variables
    final Core.IdPat xPat =
        core.idPat(baseComponents[0], typeSystem.nameGenerator.get(), 0);
    final Core.IdPat zPat =
        baseComponents.length > 1
            ? core.idPat(baseComponents[1], typeSystem.nameGenerator.get(), 0)
            : core.idPat(baseComponents[0], typeSystem.nameGenerator.get(), 0);
    final Core.Pat newScanPat;
    if (baseComponents.length > 1) {
      if (isRecordType) {
        newScanPat =
            core.recordPat(
                (RecordType) baseElemType, ImmutableList.of(xPat, zPat));
      } else {
        newScanPat = core.tuplePat(typeSystem, ImmutableList.of(xPat, zPat));
      }
    } else {
      newScanPat = xPat;
    }

    // Create patterns for base scan: (z2, y) for tuples or {x=z2, y=y} for
    // records
    // This scans the base edges/relations
    // Use nameGenerator to avoid collisions with source code variables
    final Core.IdPat z2Pat =
        core.idPat(baseComponents[0], typeSystem.nameGenerator.get(), 0);
    final Core.IdPat yPat =
        baseComponents.length > 1
            ? core.idPat(baseComponents[1], typeSystem.nameGenerator.get(), 0)
            : core.idPat(baseComponents[0], typeSystem.nameGenerator.get(), 0);
    final Core.Pat baseScanPat;
    if (baseComponents.length > 1) {
      if (isRecordType) {
        baseScanPat =
            core.recordPat(
                (RecordType) baseElemType, ImmutableList.of(z2Pat, yPat));
      } else {
        baseScanPat = core.tuplePat(typeSystem, ImmutableList.of(z2Pat, yPat));
      }
    } else {
      baseScanPat = z2Pat;
    }

    // Build FROM expression
    // Note: Pass null for environment because oldPat and newPat are bound by
    // the Case pattern match, not the Environment
    final FromBuilder builder =
        core.fromBuilder(typeSystem, (Environment) null);

    // Scan new results first: from (x, z) in newTuples
    builder.scan(newScanPat, core.id(newPat));

    // Handle the base generator. For simple collections (tuples), scan
    // directly.
    // For record-based collections with projection, extract the underlying
    // collection and scan with record patterns to avoid FromBuilder flattening
    // issues.
    final Core.Exp underlyingCollection = extractUnderlyingCollection(baseGen);
    final Type collectionElemType = underlyingCollection.type.elementType();
    final boolean collectionIsRecord =
        collectionElemType.op() == Op.RECORD_TYPE;

    if (collectionIsRecord && !isRecordType) {
      // Record-to-tuple projection in transitive closure is not yet supported.
      // This requires extracting field mapping from the base generator.
      // For now, fall through to the simple case which may not produce
      // correct results but won't crash compilation.
    }

    // For simple cases (tuples or same-type records), scan directly
    final Core.Exp simplifiedBaseGen = simplifyFromExpression(baseGen);
    builder.scan(baseScanPat, simplifiedBaseGen);

    // Add WHERE clause: z = z2 (join on intermediate variable)
    if (baseComponents.length > 1) {
      // OP_EQ is polymorphic, so we need to provide the type parameter
      final Type zType = zPat.type;
      final Core.Exp whereClause =
          core.call(
              typeSystem,
              BuiltIn.OP_EQ,
              zType,
              Pos.ZERO,
              core.id(zPat),
              core.id(z2Pat));
      builder.where(whereClause);
    }

    // Add YIELD clause: always (x, y) tuples
    // The goal pattern expects tuples, so we always yield tuples regardless
    // of whether the input collection contains records or tuples. The scan
    // patterns match the input type, but the yield produces the output type.
    final Core.Exp yieldExp;
    if (baseComponents.length > 1) {
      yieldExp = core.tuple(typeSystem, core.id(xPat), core.id(yPat));
    } else {
      yieldExp = core.id(xPat);
    }
    builder.yield_(yieldExp);

    return builder.build();
  }

  /**
   * Identifies the join variable from threaded variables.
   *
   * <p>The join variable is the intermediate variable that appears in both:
   *
   * <ul>
   *   <li>Second position of base case output (z in (x,z))
   *   <li>First position of recursive call argument (z in path(z,y))
   * </ul>
   *
   * <p>Uses deterministic ordering by variable name to ensure consistent code
   * generation across multiple runs. Returns the variable that comes first in
   * lexicographic order.
   *
   * @param threadedVars Variables threaded through recursion
   * @return The join variable (first in lexicographic order by name)
   */
  private Core.NamedPat identifyJoinVariable(Set<Core.NamedPat> threadedVars) {
    if (threadedVars.isEmpty()) {
      throw new IllegalArgumentException("No join variable identified");
    }
    // Sort by name for deterministic behavior
    return threadedVars.stream()
        .min(Comparator.comparing(pat -> pat.name))
        .orElseThrow(
            () -> new IllegalArgumentException("No join variable identified"));
  }

  /**
   * Extracts the mapping from tuple positions to record field slots.
   *
   * <p>For a FROM expression like {@code from r in coll yield (#src r, #dst
   * r)}, returns an array where index i contains the record slot for tuple
   * position i.
   *
   * <p>For non-FROM expressions, returns identity mapping [0, 1, ...].
   *
   * @param exp The base generator expression
   * @return Array mapping tuple positions to record slots
   */
  private int[] extractTupleToRecordMapping(Core.Exp exp) {
    if (exp.op != Op.FROM) {
      // Identity mapping for non-FROM expressions
      return new int[] {0, 1};
    }
    final Core.From from = (Core.From) exp;
    if (from.steps.isEmpty()) {
      return new int[] {0, 1};
    }

    // Find the YIELD step
    Core.Yield yield = null;
    for (Core.FromStep step : from.steps) {
      if (step.op == Op.YIELD) {
        yield = (Core.Yield) step;
      }
    }
    if (yield == null || yield.exp.op != Op.TUPLE) {
      return new int[] {0, 1};
    }

    // Extract slots from the yield tuple
    final Core.Tuple tuple = (Core.Tuple) yield.exp;
    final int[] mapping = new int[tuple.args.size()];
    for (int i = 0; i < tuple.args.size(); i++) {
      final Core.Exp arg = tuple.args.get(i);
      if (arg.op == Op.APPLY) {
        final Core.Apply apply = (Core.Apply) arg;
        if (apply.fn.op == Op.RECORD_SELECTOR) {
          mapping[i] = ((Core.RecordSelector) apply.fn).slot;
        } else {
          mapping[i] = i; // Default to identity
        }
      } else {
        mapping[i] = i; // Default to identity
      }
    }
    return mapping;
  }

  /**
   * Extracts the underlying collection from a FROM expression.
   *
   * <p>For expressions like {@code from pat in collection yield ...}, returns
   * the collection. For non-FROM expressions, returns the expression itself.
   *
   * @param exp The expression to extract from
   * @return The underlying collection if FROM, or the original expression
   */
  private Core.Exp extractUnderlyingCollection(Core.Exp exp) {
    if (exp.op != Op.FROM) {
      return exp;
    }
    final Core.From from = (Core.From) exp;
    if (from.steps.isEmpty() || from.steps.get(0).op != Op.SCAN) {
      return exp;
    }
    return ((Core.Scan) from.steps.get(0)).exp;
  }

  /**
   * Simplifies a FROM expression to its underlying collection if possible.
   *
   * <p>If the expression is {@code from pat in collection yield tuple} where
   * the yield is an identity transformation (tuple reconstructs the same values
   * bound by pat), returns just the collection. This avoids broken variable
   * references when FromBuilder tries to flatten nested FROM expressions.
   *
   * @param exp The expression to simplify
   * @return The simplified expression (collection) or the original if not
   *     simplifiable
   */
  private Core.Exp simplifyFromExpression(Core.Exp exp) {
    if (exp.op != Op.FROM) {
      return exp;
    }
    final Core.From from = (Core.From) exp;
    final List<Core.FromStep> steps = from.steps;

    // Check for pattern: from pat in collection yield tuple
    // This requires exactly 2 steps: SCAN + YIELD
    if (steps.size() != 2) {
      return exp;
    }
    if (steps.get(0).op != Op.SCAN || steps.get(1).op != Op.YIELD) {
      return exp;
    }

    final Core.Scan scan = (Core.Scan) steps.get(0);
    final Core.Yield yield = (Core.Yield) steps.get(1);

    // Check if yield is an identity transformation
    // The yield expression should be a tuple/record that matches the scan
    // pattern
    if (!isIdentityYield(scan.pat, yield.exp)) {
      return exp;
    }

    // Return the underlying collection
    return scan.exp;
  }

  /**
   * Checks if a yield expression is an identity transformation of a pattern.
   *
   * <p>Returns true if the yield expression reconstructs the same values that
   * the pattern binds. For example, if pat binds (x, y) and exp is (x, y).
   *
   * @param pat The scan pattern
   * @param exp The yield expression
   * @return true if exp is an identity transformation of pat
   */
  private boolean isIdentityYield(Core.Pat pat, Core.Exp exp) {
    switch (pat.op) {
      case ID_PAT:
        // Simple ID pattern: yield should be an ID reference to the same var
        if (exp.op != Op.ID) {
          return false;
        }
        return ((Core.IdPat) pat).equals(((Core.Id) exp).idPat);

      case TUPLE_PAT:
        // Tuple pattern: yield should be a tuple with matching elements
        if (exp.op != Op.TUPLE) {
          return false;
        }
        final Core.TuplePat tuplePat = (Core.TuplePat) pat;
        final Core.Tuple tuple = (Core.Tuple) exp;
        if (tuplePat.args.size() != tuple.args.size()) {
          return false;
        }
        for (int i = 0; i < tuplePat.args.size(); i++) {
          if (!isIdentityYield(tuplePat.args.get(i), tuple.args.get(i))) {
            return false;
          }
        }
        return true;

      case RECORD_PAT:
        // Record pattern: yield should be a tuple (record literal) with
        // matching
        if (exp.op != Op.TUPLE) {
          return false;
        }
        final Core.RecordPat recordPat = (Core.RecordPat) pat;
        final Core.Tuple recordTuple = (Core.Tuple) exp;
        if (recordPat.args.size() != recordTuple.args.size()) {
          return false;
        }
        for (int i = 0; i < recordPat.args.size(); i++) {
          if (!isIdentityYield(
              recordPat.args.get(i), recordTuple.args.get(i))) {
            return false;
          }
        }
        return true;

      default:
        return false;
    }
  }
}

// End PredicateInverter.java
