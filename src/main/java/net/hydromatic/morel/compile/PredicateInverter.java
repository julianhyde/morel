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

import static java.util.Objects.requireNonNull;
import static net.hydromatic.morel.ast.CoreBuilder.core;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.CoreBuilder;
import net.hydromatic.morel.ast.FromBuilder;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.ast.Shuttle;
import net.hydromatic.morel.ast.Visitor;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.ListType;
import net.hydromatic.morel.type.PrimitiveType;
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

  /** Variable definitions discovered during inversion (e.g., x = expr) */
  private final Map<Core.NamedPat, Core.Exp> definitions = new HashMap<>();

  /** Memoization table for recursive predicate inversions */
  private final Map<Core.Id, Result> memo = new HashMap<>();

  private PredicateInverter(TypeSystem typeSystem, Environment env) {
    this.typeSystem = requireNonNull(typeSystem);
    this.env = env;
  }

  /**
   * Inverts a predicate to generate tuples.
   *
   * @param predicate The boolean expression to invert
   * @param goalPats Variables to generate (unbound variables we want to
   *     produce)
   * @param boundPats Variables already bound with their extents/values
   * @return Inversion result with generator and metadata, or empty if the
   *     expression has no inverse
   */
  public static Optional<Result> invert(
      TypeSystem typeSystem,
      Environment env,
      Core.Exp predicate,
      Set<Core.NamedPat> goalPats,
      SortedMap<Core.NamedPat, Core.Exp> boundPats) {
    return new PredicateInverter(typeSystem, env)
        .invert(predicate, goalPats, boundPats, ImmutableList.of());
  }

  private Optional<Result> invert(
      Core.Exp predicate,
      Set<Core.NamedPat> goalPats,
      SortedMap<Core.NamedPat, Core.Exp> boundPats,
      List<Core.Exp> active) {

    // Handle function application
    if (predicate.op == Op.APPLY) {
      Core.Apply apply = (Core.Apply) predicate;

      // Check for `elem` calls: pattern elem collection
      if (apply.isCallTo(BuiltIn.OP_ELEM)) {
        // Simple case: x elem myList => myList
        Core.Exp collection = apply.arg(1);
        return result(collection, ImmutableList.of(predicate), goalPats);
      }

      // Check for String.isPrefix pattern expr
      // This is represented as: APPLY(APPLY(STRING_IS_PREFIX, pattern), expr)
      if (apply.fn.op == Op.APPLY) {
        Core.Apply innerApply = (Core.Apply) apply.fn;
        if (innerApply.isCallTo(BuiltIn.STRING_IS_PREFIX)) {
          Core.Exp patternArg = innerApply.arg;
          Core.Exp stringArg = apply.arg;

          // Check if patternArg is a goal pattern (e.g., Id(s))
          if (patternArg.op == Op.ID) {
            Core.Id id = (Core.Id) patternArg;

            if (goalPats.contains(id.idPat)) {
              // Generate: List.tabulate(String.size s + 1, fn i =>
              // String.substring(s, 0, i))
              Core.Exp generator = generateStringPrefixes(stringArg);
              return result(
                  generator,
                  ImmutableList.of(predicate),
                  ImmutableSet.of(id.idPat));
            }
          }
        }
      }

      // Check for andalso (conjunction)
      if (apply.isCallTo(BuiltIn.Z_ANDALSO)) {
        return invertAndAlso(apply, goalPats, boundPats);
      }

      // Check for user-defined function literals (already compiled)
      if (apply.fn.op == Op.FN_LITERAL && env != null) {
        Core.Literal literal = (Core.Literal) apply.fn;
        if (literal.value instanceof Core.Fn) {
          Core.Fn fn = (Core.Fn) literal.value;

          // Substitute the function argument into the body
          Core.Exp substitutedBody = substituteArg(fn.idPat, apply.arg, fn.exp);

          // Try to invert the substituted body
          return invert(substitutedBody, goalPats, boundPats, active);
        }
      }

      // Check for user-defined function calls (ID references)
      if (apply.fn.op == Op.ID && env != null) {
        Core.Id fnId = (Core.Id) apply.fn;
        // Look up function definition in environment
        Core.NamedPat fnPat = fnId.idPat;

        // Check if we're already trying to invert this function (recursion)
        if (active.contains(fnId)) {
          // Can't invert recursive calls directly - would need special handling
          // like Relational.iterate for transitive closure
          return Optional.empty();
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
            return invert(
                substitutedBody,
                goalPats,
                boundPats,
                ConsList.of(fnId, active));
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
        return invertExists(fromExp, goalPats, boundPats);
      }
    }

    return Optional.empty();
  }

  /**
   * Inverts an exists expression: {@code exists s in collection where
   * predicate(p, s)}.
   *
   * <p>Returns the union of all values that satisfy the predicate for at least
   * one element in the collection.
   */
  private Optional<Result> invertExists(
      Core.Exp fromExp,
      Set<Core.NamedPat> goalPats,
      SortedMap<Core.NamedPat, Core.Exp> boundPats) {
    // For a from expression like: from s in collection where String.isPrefix p
    // s
    // We want to find all p that are prefixes of at least one s in collection

    // If fromExp is a FROM with scan and where steps:
    // - Extract the collection being scanned
    // - Extract the filter predicate
    // - For each element in the collection, invert the predicate
    // - Return the union of all inverted results

    if (fromExp.op != Op.FROM) {
      return Optional.empty();
    }

    Core.From from = (Core.From) fromExp;

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

    if (collection == null || filterPredicate == null || scanPat == null) {
      return Optional.empty();
    }

    // Check if the collection is a list literal
    if (collection.op == Op.APPLY && collection.isCallTo(BuiltIn.Z_LIST)) {
      Core.Apply listApply = (Core.Apply) collection;
      // Get the list elements
      if (listApply.arg.op == Op.TUPLE) {
        Core.Tuple tuple = (Core.Tuple) listApply.arg;
        List<Core.Exp> generators = new ArrayList<>();

        // For each element in the collection, substitute it into the filter
        // and try to invert
        for (Core.Exp element : tuple.args) {
          // Substitute scanPat with element in filterPredicate
          Core.Exp substitutedFilter =
              substituteArg(scanPat, element, filterPredicate);

          // Try to invert the substituted filter
          Optional<Result> elementResult =
              invert(
                  substitutedFilter, goalPats, boundPats, ImmutableList.of());

          if (elementResult.isPresent()) {
            generators.add(elementResult.get().generator);
          }
        }

        if (!generators.isEmpty()) {
          // Create union of all generators with distinct semantics
          Core.Exp unionGen;
          if (generators.size() == 1) {
            unionGen = generators.get(0);
          } else {
            // Create a FROM expression with UNION distinct steps
            // First generator becomes the initial FROM
            Core.Exp firstGen = generators.get(0);
            final Type elementType = ((ListType) firstGen.type).elementType;

            // Create a fresh pattern for the union variable
            final Core.IdPat unionPat = core.idPat(elementType, "p_union", 0);

            // Build the FROM expression: from p_union in firstGen
            FromBuilder builder =
                core.fromBuilder(typeSystem, (Environment) null);
            builder.scan(unionPat, firstGen);

            // Add UNION distinct steps for remaining generators
            for (int i = 1; i < generators.size(); i++) {
              Core.Exp gen = generators.get(i);
              builder.union(true, ImmutableList.of(gen));
            }

            // Yield the result
            builder.yield_(core.id(unionPat));
            unionGen = builder.build();
          }

          return result(unionGen, ImmutableList.of(), goalPats);
        }
      }
    }

    return Optional.empty();
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
   * Inverts a conjunction ({@code andalso}).
   *
   * <p>Handles specific patterns like {@code x > y andalso x < y + 10}.
   */
  private Optional<Result> invertAndAlso(
      Core.Apply andAlso,
      Set<Core.NamedPat> goalPats,
      SortedMap<Core.NamedPat, Core.Exp> boundPats) {

    // Try to match pattern: x > y andalso x < y + 10
    // to generate: List.tabulate(9, fn k => y + 1 + k)
    Core.Exp left = andAlso.arg(0);
    Core.Exp right = andAlso.arg(1);

    // Check if we have x > y and x < y + 10
    if (left.op == Op.APPLY && right.op == Op.APPLY) {
      Core.Apply leftApply = (Core.Apply) left;
      Core.Apply rightApply = (Core.Apply) right;

      if ((leftApply.isCallTo(BuiltIn.OP_GT)
              || leftApply.isCallTo(BuiltIn.OP_GE))
          && (rightApply.isCallTo(BuiltIn.OP_LT)
              || rightApply.isCallTo(BuiltIn.OP_LE))) {
        // Pattern: x > y andalso x < (y + 10)
        final Core.Exp xId = leftApply.arg(0);
        final Core.Exp lower = leftApply.arg(1);
        final Core.Exp xId2 = rightApply.arg(0);
        final Core.Exp upper = rightApply.arg(1);

        final boolean lowerStrict = leftApply.isCallTo(BuiltIn.OP_GT);
        final boolean upperStrict = rightApply.isCallTo(BuiltIn.OP_LT);

        // Verify x appears in both sides
        if (xId.op == Op.ID
            && xId2.op == Op.ID
            && ((Core.Id) xId).idPat.equals(((Core.Id) xId2).idPat)
            && goalPats.contains(((Core.Id) xId).idPat)) {
          Core.NamedPat xPat = ((Core.Id) xId).idPat;

          // Check if x is the goal and y is bound (or y is not in goalPats,
          // meaning it's already processed)
          if (!referencesAny(lower, goalPats)
              && !referencesAny(upper, goalPats)) {
            // Pattern matched! Generate:
            //   List.tabulate(upper - lower, fn k => lower + k)
            // which is the range
            //  [lower, upper]
            Core.Exp generator =
                generateRange(lower, lowerStrict, upper, upperStrict);

            return result(
                generator, ImmutableList.of(andAlso), ImmutableSet.of(xPat));
          }
        }
      }
    }

    return Optional.empty();
  }

  private Optional<Result> result(
      Core.Exp generator,
      Iterable<? extends Core.Exp> satisfiedFilters,
      Iterable<? extends Core.NamedPat> satisfiedPats) {
    return Optional.of(
        new Result(
            Simplifier.simplify(typeSystem, generator),
            false,
            false,
            satisfiedPats,
            satisfiedFilters));
  }

  /** Returns whether an expression references any of the given patterns. */
  private boolean referencesAny(Core.Exp exp, Set<Core.NamedPat> pats) {
    if (pats.isEmpty()) {
      return false; // short-cut
    }
    final Set<Core.NamedPat> referencedPats = new HashSet<>();
    final Visitor envVisitor =
        new Visitor() {
          @Override
          protected void visit(Core.Id id) {
            referencedPats.add(id.idPat);
          }
        };
    exp.accept(envVisitor);
    referencedPats.retainAll(pats);
    return !referencedPats.isEmpty();
  }

  /**
   * Generates an expression that produces all prefixes of a string.
   *
   * <p>For example, {@code generateStringPrefixes(s)} generates {@code
   * List.tabulate(String.size s + 1, fn i => String.substring(s, 0, i))}.
   *
   * @param stringExp The string expression to generate prefixes for
   * @return An expression that generates all prefixes
   */
  private Core.Exp generateStringPrefixes(Core.Exp stringExp) {
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
    return core.call(
        typeSystem, BuiltIn.LIST_TABULATE, stringType, Pos.ZERO, count, fn);
  }

  /**
   * Creates an expression that generates a range of integer values.
   *
   * <p>For example, {@code generateRange(3, true, 8, false)} generates a range
   * {@code 3 < x <= 8}, which yields the values {@code [4, 5, 6, 7, 8]}.
   *
   * @param lower Lower bound
   * @param lowerStrict Whether the lower bound is strict (exclusive): true for
   *     {@code x > lower}, false for {@code x >= lower}
   * @param upper Upper bound
   * @param upperStrict Whether the upper bound is strict (exclusive): true for
   *     {@code x < upper}, false for {@code x <= upper}
   */
  private Core.Exp generateRange(
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
    return core.call(
        typeSystem, BuiltIn.LIST_TABULATE, type, Pos.ZERO, count, fn);
  }

  /** Result of successfully inverting a predicate. */
  public static class Result {
    /**
     * An expression that generates a list of all values of the patterns for
     * which the predicate evaluates to true.
     */
    public final Core.Exp generator;

    /** Whether the generator may produce duplicates */
    public final boolean mayHaveDuplicates;

    /**
     * Whether the generator may contain elements that do not match the
     * constraint. If so, the original filter must still be checked.
     */
    public final boolean isSupersetOfSolution;

    /** Which goal variables are actually produced by this generator */
    public final Set<Core.NamedPat> satisfiedPats;

    /** Filters that were incorporated into the generator */
    public final List<Core.Exp> satisfiedFilters;

    private Result(
        Core.Exp generator,
        boolean mayHaveDuplicates,
        boolean isSupersetOfSolution,
        Iterable<? extends Core.NamedPat> satisfiedPats,
        Iterable<? extends Core.Exp> satisfiedFilters) {
      this.generator = requireNonNull(generator);
      this.mayHaveDuplicates = mayHaveDuplicates;
      this.isSupersetOfSolution = isSupersetOfSolution;
      this.satisfiedPats = ImmutableSet.copyOf(satisfiedPats);
      this.satisfiedFilters = ImmutableList.copyOf(satisfiedFilters);
    }
  }
}

// End PredicateInverter.java
