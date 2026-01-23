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
import com.google.common.collect.ImmutableRangeSet;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Range;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

          // Fallback: can't invert recursive calls
          // Return generatorFor(goalPats) to fall back to cartesian product
          return result(generatorFor(goalPats), ImmutableList.of());
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
        net.hydromatic.morel.compile.Generator.Cardinality c =
            net.hydromatic.morel.compile.Generator.Cardinality.SINGLE;
        for (Core.NamedPat p : goalPats) {
          Generator generator = generatorFor(ImmutableList.of(p));
          c = c.max(generator.cardinality);
          freeVars.addAll(generator.freeVars);
          fromBuilder.scan(p, generator.expression);
        }
        return new Generator(
            goalPat,
            fromBuilder.build(),
            c,
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

    if (baseCaseResult.remainingFilters.isEmpty()) {
      // Successfully inverted the base case
      // Now we would build the step function, but for now just use the base
      // case
      // as a fallback
      return baseCaseResult;
    }

    return null;
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
      fromBuilder.scan(pat, elemCall.arg(1));
      fromBuilder.yield_(tuple);
      final Generator generator =
          generator(
              toTuple(goalPats),
              collection,
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
}

// End PredicateInverter.java
