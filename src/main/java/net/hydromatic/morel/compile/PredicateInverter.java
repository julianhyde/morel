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
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.ConsList;

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

  private PredicateInverter(
      TypeSystem typeSystem,
      Environment env,
      Map<Core.NamedPat, Generator> initialGenerators) {
    this.typeSystem = requireNonNull(typeSystem);
    this.env = requireNonNull(env);
    this.generators = new HashMap<>(initialGenerators);
  }

  /** Package-private constructor for testing. */
  PredicateInverter(TypeSystem typeSystem, Environment env) {
    this(typeSystem, env, ImmutableMap.of());
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
    return new PredicateInverter(typeSystem, env, generators)
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

      // Check for orelse (disjunction)
      // Only handle transitive closure pattern in recursive contexts.
      // Non-TC orelse patterns fall through to default handler (returns
      // INFINITE extent).
      // This design choice optimizes for the common case (TC patterns) while
      // allowing future enhancement to support general disjunction union (Phase
      // 6a).
      if (apply.isCallTo(BuiltIn.Z_ORELSE) && !active.isEmpty()) {
        Result tcResult =
            tryInvertTransitiveClosure(apply, null, null, goalPats, active);
        if (tcResult != null) {
          return tcResult;
        }
        // If transitive closure pattern doesn't match, fall through to default
        // handling
      }

      // Check for user-defined function literals (already compiled)
      if (apply.fn.op == Op.FN_LITERAL) {
        Core.Literal literal = (Core.Literal) apply.fn;
        if (literal.value instanceof Core.Fn) {
          Core.Fn fn = (Core.Fn) literal.value;

          // Substitute the function argument into the body
          Core.Exp substitutedBody = substituteArg(fn.idPat, apply.arg, fn.exp);

          // Try to invert the substituted body
          return invert(substitutedBody, goalPats, active);
        }
      }

      // Check for user-defined function calls (ID references)
      if (apply.fn.op == Op.ID) {
        Core.Id fnId = (Core.Id) apply.fn;
        // Look up function definition in environment
        Core.NamedPat fnPat = fnId.idPat;

        // Check if we're already trying to invert this function (recursion)
        if (active.contains(fnId)) {
          // This is a recursive call - try to invert it with transitive closure
          Binding binding = env.getOpt(fnPat);

          if (binding != null && binding.value instanceof Core.Exp) {
            Core.Exp fnBody = (Core.Exp) binding.value;
            if (fnBody.op == Op.FN) {
              Core.Fn fn = (Core.Fn) fnBody;

              // Substitute the function argument into the body
              Core.Exp substitutedBody =
                  substituteArg(fn.idPat, apply.arg, fn.exp);

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

        if (binding != null && binding.value instanceof Core.Exp) {
          Core.Exp fnBody = (Core.Exp) binding.value;
          if (fnBody.op == Op.FN) {
            Core.Fn fn = (Core.Fn) fnBody;

            // Substitute the function argument into the body
            Core.Exp substitutedBody =
                substituteArg(fn.idPat, apply.arg, fn.exp);

            // Try to invert the substituted body
            return invert(substitutedBody, goalPats, ConsList.of(fnId, active));
          }
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

    return result(generatorFor(goalPats), ImmutableList.of());
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
      // referenced.
      List<Core.NamedPat> extendedOutputPats = new ArrayList<>(goalPats);
      if (scanPat.op == Op.ID_PAT) {
        Core.IdPat scanIdPat = (Core.IdPat) scanPat;
        extendedOutputPats.add(scanIdPat);
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
          // Create union of all generators with distinct semantics.
          return result(unionDistinct(resultGenerators), ImmutableList.of());
        }
      }
    }

    return result(
        generatorFor(goalPats),
        ImmutableList.of(
            core.call(typeSystem, BuiltIn.RELATIONAL_NON_EMPTY, from)));
  }

  private Generator generatorFor(List<Core.NamedPat> goalPats) {
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

    Core.Exp baseCase = orElseApply.arg(0);
    Core.Exp recursiveCase = orElseApply.arg(1);

    // Try to invert the base case without the recursive call
    Result baseCaseResult = invert(baseCase, goalPats, ImmutableList.of());

    // Check if base case could be inverted to a FINITE generator.
    // If the base case is non-invertible (e.g., a user-defined function call),
    // the invert method returns a result with INFINITE cardinality as a
    // fallback.
    // We cannot safely build Relational.iterate with an infinite generator
    // because:
    // 1. An infinite generator cannot be materialized at runtime
    // 2. At compile time we don't know what runtime values are available
    // 3. The entire iteration would fail with "infinite: int * int" at runtime
    //
    // In this case, we cannot safely build Relational.iterate with an infinite
    // base case. Return null to signal that transitive closure pattern matching
    // failed, causing the caller to skip this optimization.
    if (baseCaseResult.generator.cardinality
        == net.hydromatic.morel.compile.Generator.Cardinality.INFINITE) {
      return null;
    }

    // Build Relational.iterate with the finite base generator.
    // Remaining filters will be applied at runtime along with the iterate
    // expansion.
    // Create IOPair for the base case (no PPT terminal, so pass null)
    final IOPair baseCaseIO =
        new IOPair(
            ImmutableMap.of(), baseCaseResult.generator.expression, null);

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
    final Core.Exp baseGenerator = baseCaseResult.generator.expression;

    // Get the RELATIONAL_ITERATE built-in as a function literal
    final Core.Exp iterateFn =
        core.functionLiteral(typeSystem, BuiltIn.RELATIONAL_ITERATE);

    // Apply iterate to base generator
    final Core.Exp iterateWithBase =
        core.apply(Pos.ZERO, iterateFn, baseGenerator);

    // Apply the result to the step function
    final Core.Exp relationalIterate =
        core.apply(Pos.ZERO, iterateWithBase, stepFunction);

    // Create a Generator wrapping the Relational.iterate call
    final Generator iterateGenerator =
        new Generator(
            baseCaseResult.generator.goalPat,
            relationalIterate,
            net.hydromatic.morel.compile.Generator.Cardinality.FINITE,
            baseCaseResult.generator.constraints,
            baseCaseResult.generator.freeVars);

    // Return result with remaining filters from base case
    // These will be applied at runtime
    return result(iterateGenerator, baseCaseResult.remainingFilters);
  }

  /**
   * Attempts to invert a predicate using ProcessTreeBuilder analysis.
   *
   * <p>This method uses ProcessTreeBuilder to construct a Perfect Process Tree
   * (PPT) and analyze the predicate structure. It's the integration point
   * between PredicateInverter and ProcessTreeBuilder.
   *
   * <p><b>PHASE 4 PLACEHOLDER:</b> Currently constructs PPT successfully but
   * always returns empty. Full implementation requires:
   *
   * <ul>
   *   <li>PPT tabulation to build I-O pairs
   *   <li>Mode analysis for smart generator selection
   *   <li>Generation of Relational.iterate calls from PPT structure
   * </ul>
   *
   * <p>Phase 3b uses direct AST pattern matching instead (see {@link
   * #tryInvertTransitiveClosure}). This method will be fully implemented in
   * Phase 4 to handle all recursive predicates via PPT analysis.
   *
   * <p>Package-private for testing.
   *
   * @param predicate The predicate expression to analyze
   * @param goalPatterns Variables to generate values for
   * @param boundGenerators Generators for bound variables
   * @return Always returns empty in Phase 3b; will return inversion result in
   *     Phase 4
   */
  @SuppressWarnings("unused") // Phase 4 implementation placeholder
  Optional<Result> tryInvertWithProcessTree(
      Core.Exp predicate,
      List<Core.NamedPat> goalPatterns,
      Map<Core.NamedPat, Generator> boundGenerators) {

    try {
      // Create VarEnvironment from goal patterns and bound generators
      VarEnvironment varEnv =
          VarEnvironment.initial(
              goalPatterns,
              boundGenerators.isEmpty()
                  ? ImmutableMap.of()
                  : ImmutableMap.copyOf(boundGenerators),
              env);

      // Build PPT using ProcessTreeBuilder
      // Pass null for inverter to avoid infinite recursion
      ProcessTreeBuilder builder =
          new ProcessTreeBuilder(typeSystem, null, ImmutableSet.of());
      ProcessTreeNode ppt = builder.build(predicate, varEnv);

      // Phase 3a: PPT construction succeeded
      // Phase 4 TODO: Tabulate PPT to build I-O pairs
      // Phase 4 TODO: Use tabulation results to generate Relational.iterate

      // For now, return empty (placeholder for Phase 4)
      return Optional.empty();

    } catch (Exception e) {
      // PPT construction failed - not a valid transitive closure
      return Optional.empty();
    }
  }

  /**
   * Validates that a ProcessTreeNode matches the transitive closure pattern.
   *
   * <p>Pattern: {@code baseCase orelse recursiveCase} where:
   *
   * <ul>
   *   <li>baseCase is a TerminalNode with successful inversion
   *   <li>recursiveCase contains a recursive call
   * </ul>
   *
   * @param tree The ProcessTreeNode to check
   * @return True if tree matches transitive closure pattern
   */
  private boolean isTransitiveClosurePattern(ProcessTreeNode tree) {
    if (!(tree instanceof ProcessTreeNode.BranchNode)) {
      return false;
    }

    ProcessTreeNode.BranchNode branch = (ProcessTreeNode.BranchNode) tree;

    // Left must be invertible terminal
    if (!branch.left.isTerminal()) {
      return false;
    }
    ProcessTreeNode.TerminalNode leftTerm =
        (ProcessTreeNode.TerminalNode) branch.left;
    if (!leftTerm.isInverted()) {
      return false; // Must have inversion result
    }

    // Right must contain recursion
    if (!branch.right.isRecursiveCall() && !containsRecursion(branch.right)) {
      return false;
    }

    return true;
  }

  /**
   * Recursively checks if a node contains a recursive call.
   *
   * @param node The node to check
   * @return True if node or any descendant is a recursive call
   */
  private boolean containsRecursion(ProcessTreeNode node) {
    if (node.isRecursiveCall()) {
      return true;
    }
    if (node instanceof ProcessTreeNode.BranchNode) {
      ProcessTreeNode.BranchNode b = (ProcessTreeNode.BranchNode) node;
      return containsRecursion(b.left) || containsRecursion(b.right);
    }
    if (node instanceof ProcessTreeNode.SequenceNode) {
      ProcessTreeNode.SequenceNode s = (ProcessTreeNode.SequenceNode) node;
      return s.children.stream().anyMatch(this::containsRecursion);
    }
    return false;
  }

  /**
   * Extracts the recursive case body from a transitive closure pattern.
   *
   * <p>For a BranchNode representing {@code baseCase orelse recursiveCase},
   * returns the expression from the recursive case (right branch).
   *
   * @param tree The BranchNode containing the pattern
   * @return The recursive case expression
   */
  private Core.Exp extractRecursiveBody(ProcessTreeNode tree) {
    if (!(tree instanceof ProcessTreeNode.BranchNode)) {
      throw new IllegalArgumentException("Expected BranchNode, got " + tree);
    }
    ProcessTreeNode.BranchNode branch = (ProcessTreeNode.BranchNode) tree;
    return branch.right.term();
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

    // If the expression is "(a, b, c, "foo") elem myList"
    // and the goal is "(c, b)"
    // then the generator is "from (a, b, c, "foo") in myList yield {c, b}".
    if (pattern.op == Op.TUPLE) {
      final Core.Tuple tuple = (Core.Tuple) pattern;
      final Core.Pat pat = toPat(tuple);

      final FromBuilder fromBuilder = core.fromBuilder(typeSystem, env);
      fromBuilder.scan(pat, collection);
      fromBuilder.yield_(tuple);
      final Generator generator =
          generator(
              toTuple(goalPats),
              fromBuilder.build(),
              net.hydromatic.morel.compile.Generator.Cardinality.FINITE,
              ImmutableList.of());
      return result(generator, ImmutableList.of());
    }

    // TODO: Handle if some but not all of goalPats are covered in elemCall.
    // TODO: Deal with repeated IDs, like "(c, b, c) elem myList"
    // TODO: Deal with "() elem myList"

    return result(generatorFor(goalPats), ImmutableList.of(elemCall));
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
   * @param param The function parameter pattern
   * @param arg The argument expression to substitute
   * @param body The function body
   * @return The body with the parameter replaced by the argument
   */
  private Core.Exp substituteArg(Core.Pat param, Core.Exp arg, Core.Exp body) {
    // Use a Shuttle to traverse and replace
    return body.accept(
        new Shuttle(typeSystem) {
          @Override
          public Core.Exp visit(Core.Id id) {
            // Check if this ID matches the parameter
            if (param.op == Op.ID_PAT && id.idPat.equals(param)) {
              return arg;
            }
            return super.visit(id);
          }

          @Override
          public Core.Exp visit(Core.Tuple tuple) {
            // Check if this tuple matches the parameter pattern exactly
            if (param.op == Op.TUPLE_PAT
                && tuplesMatch((Core.TuplePat) param, tuple)) {
              return arg;
            }
            return super.visit(tuple);
          }
        });
  }

  /**
   * Checks if a tuple expression matches a tuple pattern (for substitution).
   *
   * @param pat The tuple pattern
   * @param tuple The tuple expression
   * @return True if all elements match
   */
  private boolean tuplesMatch(Core.TuplePat pat, Core.Tuple tuple) {
    if (pat.args.size() != tuple.args.size()) {
      return false;
    }
    for (int i = 0; i < pat.args.size(); i++) {
      Core.Pat patArg = pat.args.get(i);
      Core.Exp tupleArg = tuple.args.get(i);
      // Check if the tuple element matches the pattern element
      if (patArg.op == Op.ID_PAT && tupleArg.op == Op.ID) {
        Core.IdPat idPat = (Core.IdPat) patArg;
        Core.Id id = (Core.Id) tupleArg;
        if (!idPat.equals(id.idPat)) {
          return false;
        }
      } else {
        return false; // Don't handle nested patterns yet
      }
    }
    return true;
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

    for (int i = 0; i < predicates.size(); i++) {
      if (predicates.get(i).op != Op.APPLY) {
        continue;
      }
      final Core.Apply lowerBound = (Core.Apply) predicates.get(i);
      if (lowerBound.isCallTo(BuiltIn.OP_GT)
          || lowerBound.isCallTo(BuiltIn.OP_GE)) {
        final boolean lowerStrict = lowerBound.isCallTo(BuiltIn.OP_GT);
        for (int j = 0; j < predicates.size(); j++) {
          if (j == i) {
            continue;
          }
          if (predicates.get(j).op != Op.APPLY) {
            continue;
          }
          final Core.Apply upperBound = (Core.Apply) predicates.get(j);
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
                    new ArrayList<>(predicates);
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
    Core.Exp left = predicates.get(0);
    Core.Exp right = predicates.get(1);

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
   * An input-output pair from a PPT terminal node.
   *
   * <p>Represents a concrete mapping from input bindings to output expressions
   * discovered during PPT traversal.
   */
  public static class IOPair {
    /** Input binding for this I-O pair. */
    public final ImmutableMap<Core.NamedPat, Core.Exp> inputBinding;

    /** Output expression from this terminal. */
    public final Core.Exp output;

    /** Which terminal node produced this pair. */
    public final ProcessTreeNode.TerminalNode source;

    public IOPair(
        Map<Core.NamedPat, Core.Exp> inputBinding,
        Core.Exp output,
        ProcessTreeNode.TerminalNode source) {
      this.inputBinding = ImmutableMap.copyOf(inputBinding);
      this.output = requireNonNull(output);
      this.source = requireNonNull(source);
    }
  }

  /**
   * Tabulates a Perfect Process Tree to extract I-O pairs.
   *
   * <p>This is URA Step 2: traverse the PPT depth-first and collect terminal
   * nodes with successful inversions. The result includes:
   *
   * <ul>
   *   <li>I-O pairs mapping inputs to outputs
   *   <li>Overall cardinality (SINGLE, FINITE, or INFINITE)
   *   <li>Duplicate detection flag
   * </ul>
   *
   * @param ppt The Perfect Process Tree root node
   * @param env The variable environment
   * @return Tabulation result with I-O pairs and metadata
   */
  private TabulationResult tabulate(ProcessTreeNode ppt, VarEnvironment env) {
    // Traverse PPT depth-first, collect terminals
    final List<IOPair> pairs = new ArrayList<>();
    final List<net.hydromatic.morel.compile.Generator.Cardinality>
        cardinalities = new ArrayList<>();
    final Set<String> seenOutputs = new HashSet<>();

    traversePPT(ppt, env, pairs, cardinalities, seenOutputs);

    // Compute overall cardinality
    final net.hydromatic.morel.compile.Generator.Cardinality
        overallCardinality = computeCardinality(cardinalities);

    // Detect duplicates (same output from different input classes)
    final boolean mayHaveDuplicates = detectDuplicates(pairs);

    return new TabulationResult(pairs, overallCardinality, mayHaveDuplicates);
  }

  /**
   * Depth-first traversal of PPT collecting terminal nodes.
   *
   * <p>Recursively visits all nodes in the PPT and extracts I-O pairs from
   * terminals with successful inversions.
   *
   * @param node The current node to traverse
   * @param env The variable environment
   * @param collector List to collect I-O pairs into
   * @param cardinalities List to collect cardinalities from terminals
   * @param seenOutputs Set of output strings for duplicate detection
   */
  private void traversePPT(
      ProcessTreeNode node,
      VarEnvironment env,
      List<IOPair> collector,
      List<net.hydromatic.morel.compile.Generator.Cardinality> cardinalities,
      Set<String> seenOutputs) {

    if (node.isTerminal()) {
      final ProcessTreeNode.TerminalNode terminal =
          (ProcessTreeNode.TerminalNode) node;

      // Terminal must have inversion result
      if (terminal.isInverted()) {
        // Extract generator result
        final Result invResult = terminal.inversionResult.get();
        final PredicateInverter.Generator gen = invResult.generator;

        // Convert boundVars map from Generator to Exp
        final ImmutableMap.Builder<Core.NamedPat, Core.Exp> inputBuilder =
            ImmutableMap.builder();
        for (Map.Entry<Core.NamedPat, PredicateInverter.Generator> entry :
            env.boundVars.entrySet()) {
          inputBuilder.put(entry.getKey(), entry.getValue().expression);
        }

        // Create I-O pair
        final IOPair pair =
            new IOPair(inputBuilder.build(), gen.expression, terminal);

        collector.add(pair);
        cardinalities.add(gen.cardinality);
        seenOutputs.add(gen.expression.toString());
      }
    } else if (node instanceof ProcessTreeNode.BranchNode) {
      final ProcessTreeNode.BranchNode branch =
          (ProcessTreeNode.BranchNode) node;

      // Traverse both branches
      traversePPT(branch.left, env, collector, cardinalities, seenOutputs);
      traversePPT(branch.right, env, collector, cardinalities, seenOutputs);

    } else if (node instanceof ProcessTreeNode.SequenceNode) {
      final ProcessTreeNode.SequenceNode seq =
          (ProcessTreeNode.SequenceNode) node;

      // For sequences, process all children with current environment
      for (ProcessTreeNode child : seq.children) {
        traversePPT(child, env, collector, cardinalities, seenOutputs);
      }
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
    final Core.IdPat oldPat = core.idPat(bagType, "oldTuples", 0);
    final Core.IdPat newPat = core.idPat(bagType, "newTuples", 0);

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
    final Core.IdPat lambdaParam = core.idPat(paramType, "stepFnParam", 0);

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
    if (baseElemType.op() == Op.TUPLE_TYPE) {
      final RecordLikeType recordLikeType = (RecordLikeType) baseElemType;
      final List<Type> argTypes = recordLikeType.argTypes();
      baseComponents = argTypes.toArray(new Type[0]);
    } else {
      // Simple case: single element type
      baseComponents = new Type[] {baseElemType};
    }

    // Create patterns for new scan: (x, z)
    // This scans the newly discovered results from the previous iteration
    final Core.IdPat xPat = core.idPat(baseComponents[0], "x", 0);
    final Core.IdPat zPat =
        baseComponents.length > 1
            ? core.idPat(baseComponents[1], "z", 0)
            : core.idPat(baseComponents[0], "z", 0);
    final Core.Pat newScanPat =
        baseComponents.length > 1
            ? core.tuplePat(typeSystem, ImmutableList.of(xPat, zPat))
            : xPat;

    // Create patterns for base scan: (z2, y)
    // This scans the base edges/relations
    final Core.IdPat z2Pat = core.idPat(baseComponents[0], "z2", 0);
    final Core.IdPat yPat =
        baseComponents.length > 1
            ? core.idPat(baseComponents[1], "y", 0)
            : core.idPat(baseComponents[0], "y", 0);
    final Core.Pat baseScanPat =
        baseComponents.length > 1
            ? core.tuplePat(typeSystem, ImmutableList.of(z2Pat, yPat))
            : z2Pat;

    // Build FROM expression
    // Note: Pass null for environment because oldPat and newPat are bound by
    // the Case pattern match, not the Environment
    final FromBuilder builder =
        core.fromBuilder(typeSystem, (Environment) null);

    // Scan new results first: from (x, z) in newTuples
    builder.scan(newScanPat, core.id(newPat));

    // Scan base generator: (z2, y) in baseGen
    builder.scan(baseScanPat, baseGen);

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

    // Add YIELD clause: (x, y)
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
}

// End PredicateInverter.java
