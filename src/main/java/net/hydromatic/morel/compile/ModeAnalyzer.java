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

import com.google.common.collect.ImmutableSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Op;

/**
 * Analyzes predicates to determine which variables they can generate.
 *
 * <p>Mode analysis is essential for proper conjunction handling. For a
 * predicate like {@code edge(x, y) andalso y > 5 andalso x < 10}:
 *
 * <ul>
 *   <li>{@code edge(x, y)}: can generate {x, y} when both unbound
 *   <li>{@code y > 5}: cannot generate y (infinite), can only filter
 *   <li>{@code x < 10}: cannot generate x (infinite), can only filter
 * </ul>
 *
 * <p>This determines the optimal order: Generate (x,y) from edge, then filter.
 *
 * <p>Per Julian Hyde: "Morel needs to achieve the same effect [as Datalog
 * grounding], but in the dual space of sets rather than predicates."
 */
public class ModeAnalyzer {

  private final FunctionRegistry functionRegistry;

  public ModeAnalyzer(FunctionRegistry functionRegistry) {
    this.functionRegistry = requireNonNull(functionRegistry);
  }

  /** Result of mode analysis for a single predicate. */
  public static final class ModeSignature {
    /** Variables that this predicate can generate (given the bound set). */
    public final ImmutableSet<Core.NamedPat> canGenerate;

    /** Whether generation produces finite results. */
    public final boolean isFinite;

    /** Variables that must be bound for this predicate to generate. */
    public final ImmutableSet<Core.NamedPat> requiredBound;

    /** Join variables (shared between generator and filter). */
    public final ImmutableSet<Core.NamedPat> joinVars;

    /** Priority for ordering (lower = earlier). Generators before filters. */
    public final int priority;

    public ModeSignature(
        Set<Core.NamedPat> canGenerate,
        boolean isFinite,
        Set<Core.NamedPat> requiredBound,
        Set<Core.NamedPat> joinVars,
        int priority) {
      this.canGenerate = ImmutableSet.copyOf(canGenerate);
      this.isFinite = isFinite;
      this.requiredBound = ImmutableSet.copyOf(requiredBound);
      this.joinVars = ImmutableSet.copyOf(joinVars);
      this.priority = priority;
    }

    /** Creates a signature for a generator (can produce values). */
    public static ModeSignature generator(
        Set<Core.NamedPat> canGenerate, Set<Core.NamedPat> requiredBound) {
      return new ModeSignature(canGenerate, true, requiredBound, Set.of(), 0);
    }

    /** Creates a signature for a generator with join variables. */
    public static ModeSignature generatorWithJoin(
        Set<Core.NamedPat> canGenerate,
        Set<Core.NamedPat> requiredBound,
        Set<Core.NamedPat> joinVars) {
      return new ModeSignature(canGenerate, true, requiredBound, joinVars, 0);
    }

    /** Creates a signature for a filter-only predicate. */
    public static ModeSignature filterOnly(Set<Core.NamedPat> requiredBound) {
      return new ModeSignature(Set.of(), true, requiredBound, Set.of(), 100);
    }

    /** Creates a signature for an infinite generator (range constraint). */
    public static ModeSignature infinite(Set<Core.NamedPat> requiredBound) {
      return new ModeSignature(Set.of(), false, requiredBound, Set.of(), 50);
    }

    /** Returns true if this predicate can generate any variables. */
    public boolean canGenerateAny() {
      return !canGenerate.isEmpty() && isFinite;
    }
  }

  /**
   * Analyzes a predicate to determine its mode signature.
   *
   * @param predicate the predicate to analyze
   * @param goalPats variables we want to generate
   * @param boundPats variables that are already bound
   * @return mode signature describing generation capabilities
   */
  public ModeSignature analyze(
      Core.Exp predicate,
      Set<Core.NamedPat> goalPats,
      Set<Core.NamedPat> boundPats) {

    if (predicate.op == Op.APPLY) {
      Core.Apply apply = (Core.Apply) predicate;

      // elem: x elem collection
      if (apply.isCallTo(BuiltIn.OP_ELEM)) {
        return analyzeElem(apply, goalPats, boundPats);
      }

      // Comparisons: x > y, x < y, etc.
      if (isComparison(apply)) {
        return analyzeComparison(apply, goalPats, boundPats);
      }

      // User-defined function call
      if (apply.fn.op == Op.ID) {
        return analyzeUserFunction(apply, goalPats, boundPats);
      }

      // exists (Relational.nonEmpty)
      if (apply.isCallTo(BuiltIn.RELATIONAL_NON_EMPTY)) {
        return analyzeExists(apply, goalPats, boundPats);
      }
    }

    // Default: filter only
    Set<Core.NamedPat> freeVars = extractFreeVars(predicate, goalPats);
    return ModeSignature.filterOnly(freeVars);
  }

  /** Analyzes an elem expression: {@code x elem collection}. */
  private ModeSignature analyzeElem(
      Core.Apply apply,
      Set<Core.NamedPat> goalPats,
      Set<Core.NamedPat> boundPats) {
    Core.Exp elemArg = apply.arg(0);
    Core.Exp collection = apply.arg(1);

    Set<Core.NamedPat> canGen = new HashSet<>();
    Set<Core.NamedPat> required = new HashSet<>();

    // If the element is an ID referencing a goal pattern, we can generate it
    if (elemArg.op == Op.ID) {
      Core.Id id = (Core.Id) elemArg;
      if (goalPats.contains(id.idPat) && !boundPats.contains(id.idPat)) {
        canGen.add(id.idPat);
      }
    } else if (elemArg.op == Op.TUPLE) {
      // Tuple element: (x, y) elem collection
      Core.Tuple tuple = (Core.Tuple) elemArg;
      for (Core.Exp arg : tuple.args) {
        if (arg.op == Op.ID) {
          Core.Id id = (Core.Id) arg;
          if (goalPats.contains(id.idPat) && !boundPats.contains(id.idPat)) {
            canGen.add(id.idPat);
          }
        }
      }
    }

    // Check collection for free variables that must be bound
    required.addAll(extractFreeVars(collection, goalPats));

    return ModeSignature.generator(canGen, required);
  }

  /** Analyzes a comparison expression (>, <, >=, <=, =). */
  private ModeSignature analyzeComparison(
      Core.Apply apply,
      Set<Core.NamedPat> goalPats,
      Set<Core.NamedPat> boundPats) {
    // Comparisons alone produce infinite results
    // They need to be paired (x > y andalso x < z) to generate finite ranges
    Set<Core.NamedPat> freeVars = extractFreeVars(apply, goalPats);
    return ModeSignature.infinite(freeVars);
  }

  /** Analyzes a user-defined function call. */
  private ModeSignature analyzeUserFunction(
      Core.Apply apply,
      Set<Core.NamedPat> goalPats,
      Set<Core.NamedPat> boundPats) {
    Core.Id fnId = (Core.Id) apply.fn;
    Core.NamedPat fnPat = fnId.idPat;

    // Check the function registry
    Optional<FunctionRegistry.FunctionInfo> infoOpt =
        functionRegistry.lookup(fnPat);

    if (infoOpt.isPresent()) {
      FunctionRegistry.FunctionInfo info = infoOpt.get();
      switch (info.status()) {
        case INVERTIBLE:
        case PARTIALLY_INVERTIBLE:
          // Function can generate - determine which goal patterns
          Set<Core.NamedPat> canGen =
              extractPatternsFromArg(apply.arg, goalPats, boundPats);
          return ModeSignature.generator(canGen, Set.of());

        case RECURSIVE:
          // Recursive functions can generate but need special handling
          Set<Core.NamedPat> recCanGen =
              extractPatternsFromArg(apply.arg, goalPats, boundPats);
          return ModeSignature.generator(recCanGen, Set.of());

        case NOT_INVERTIBLE:
          // Cannot generate - filter only
          Set<Core.NamedPat> freeVars = extractFreeVars(apply, goalPats);
          return ModeSignature.filterOnly(freeVars);
      }
    }

    // Function not in registry - assume it might be invertible
    // (legacy inlining will handle it)
    Set<Core.NamedPat> canGen =
        extractPatternsFromArg(apply.arg, goalPats, boundPats);
    if (!canGen.isEmpty()) {
      return ModeSignature.generator(canGen, Set.of());
    }

    return ModeSignature.filterOnly(extractFreeVars(apply, goalPats));
  }

  /** Analyzes an exists expression (Relational.nonEmpty). */
  private ModeSignature analyzeExists(
      Core.Apply apply,
      Set<Core.NamedPat> goalPats,
      Set<Core.NamedPat> boundPats) {
    // exists expressions can generate values via their inner from clause
    // The join variables are the ones shared between outer and inner scope
    Set<Core.NamedPat> freeVars = extractFreeVars(apply.arg, goalPats);
    Set<Core.NamedPat> joinVars = new HashSet<>(freeVars);
    joinVars.retainAll(boundPats);

    // Can generate the free variables that aren't bound
    Set<Core.NamedPat> canGen = new HashSet<>(freeVars);
    canGen.removeAll(boundPats);

    return ModeSignature.generatorWithJoin(canGen, boundPats, joinVars);
  }

  /**
   * Orders predicates for optimal inversion.
   *
   * <p>Generators that can produce values come first, filters come last. Among
   * generators, those with fewer required bound variables come first.
   *
   * @param predicates the predicates to order
   * @param goalPats variables we want to generate
   * @return ordered list of predicates
   */
  public List<Core.Exp> orderPredicates(
      List<Core.Exp> predicates, Set<Core.NamedPat> goalPats) {
    Set<Core.NamedPat> bound = new HashSet<>();
    List<Core.Exp> ordered = new ArrayList<>();
    List<Core.Exp> remaining = new ArrayList<>(predicates);

    // Greedy algorithm: at each step, pick the predicate that can generate
    // the most variables given what's already bound
    while (!remaining.isEmpty()) {
      Core.Exp best = null;
      ModeSignature bestSig = null;
      int bestScore = Integer.MIN_VALUE;

      for (Core.Exp pred : remaining) {
        ModeSignature sig = analyze(pred, goalPats, bound);

        // Score: generators before filters, more generation is better
        int score = sig.canGenerate.size() * 1000 - sig.priority;
        if (sig.canGenerateAny() && bound.containsAll(sig.requiredBound)) {
          score += 10000; // Strong preference for ready generators
        }

        if (score > bestScore) {
          bestScore = score;
          best = pred;
          bestSig = sig;
        }
      }

      if (best != null) {
        ordered.add(best);
        remaining.remove(best);
        if (bestSig != null) {
          bound.addAll(bestSig.canGenerate);
        }
      } else {
        // No progress - add remaining predicates as filters
        ordered.addAll(remaining);
        break;
      }
    }

    return ordered;
  }

  /**
   * Checks if all goal patterns can be grounded (traced to finite generators).
   *
   * @param predicates the predicates in the conjunction
   * @param goalPats variables we want to generate
   * @return true if all goal patterns can be finitely generated
   */
  public boolean canGround(
      List<Core.Exp> predicates, Set<Core.NamedPat> goalPats) {
    Set<Core.NamedPat> grounded = new HashSet<>();

    for (Core.Exp pred : orderPredicates(predicates, goalPats)) {
      ModeSignature sig = analyze(pred, goalPats, grounded);
      if (sig.canGenerateAny()) {
        grounded.addAll(sig.canGenerate);
      }
    }

    return grounded.containsAll(goalPats);
  }

  // --- Helper methods ---

  private boolean isComparison(Core.Apply apply) {
    return apply.isCallTo(BuiltIn.OP_GT)
        || apply.isCallTo(BuiltIn.OP_GE)
        || apply.isCallTo(BuiltIn.OP_LT)
        || apply.isCallTo(BuiltIn.OP_LE)
        || apply.isCallTo(BuiltIn.OP_EQ)
        || apply.isCallTo(BuiltIn.OP_NE);
  }

  /** Extracts goal patterns referenced in an expression. */
  private Set<Core.NamedPat> extractFreeVars(
      Core.Exp exp, Set<Core.NamedPat> goalPats) {
    Set<Core.NamedPat> result = new HashSet<>();
    extractFreeVarsRecursive(exp, goalPats, result);
    return result;
  }

  private void extractFreeVarsRecursive(
      Core.Exp exp, Set<Core.NamedPat> goalPats, Set<Core.NamedPat> result) {
    if (exp.op == Op.ID) {
      Core.Id id = (Core.Id) exp;
      if (goalPats.contains(id.idPat)) {
        result.add(id.idPat);
      }
    } else if (exp.op == Op.TUPLE) {
      Core.Tuple tuple = (Core.Tuple) exp;
      for (Core.Exp arg : tuple.args) {
        extractFreeVarsRecursive(arg, goalPats, result);
      }
    } else if (exp.op == Op.APPLY) {
      Core.Apply apply = (Core.Apply) exp;
      extractFreeVarsRecursive(apply.fn, goalPats, result);
      extractFreeVarsRecursive(apply.arg, goalPats, result);
    }
  }

  /** Extracts goal patterns from a function argument. */
  private Set<Core.NamedPat> extractPatternsFromArg(
      Core.Exp arg, Set<Core.NamedPat> goalPats, Set<Core.NamedPat> boundPats) {
    Set<Core.NamedPat> result = new HashSet<>();

    if (arg.op == Op.ID) {
      Core.Id id = (Core.Id) arg;
      if (goalPats.contains(id.idPat) && !boundPats.contains(id.idPat)) {
        result.add(id.idPat);
      }
    } else if (arg.op == Op.TUPLE) {
      Core.Tuple tuple = (Core.Tuple) arg;
      for (Core.Exp e : tuple.args) {
        result.addAll(extractPatternsFromArg(e, goalPats, boundPats));
      }
    }

    return result;
  }
}
// End ModeAnalyzer.java
