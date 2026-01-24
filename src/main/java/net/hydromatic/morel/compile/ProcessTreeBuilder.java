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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.compile.ProcessTreeNode.BranchNode;
import net.hydromatic.morel.compile.ProcessTreeNode.SequenceNode;
import net.hydromatic.morel.compile.ProcessTreeNode.TerminalNode;
import net.hydromatic.morel.type.TypeSystem;

/**
 * Builds a Perfect Process Tree (PPT) from a predicate expression.
 *
 * <p>The builder walks the expression structure, creating nodes for:
 *
 * <ul>
 *   <li>{@code orelse} - creates {@link BranchNode}
 *   <li>{@code andalso} - creates {@link SequenceNode}
 *   <li>{@code exists} (Core.From) - unwraps and recurses
 *   <li>other expressions - creates {@link TerminalNode}
 * </ul>
 *
 * <p>Usage:
 *
 * <pre>{@code
 * ProcessTreeBuilder builder =
 *     new ProcessTreeBuilder(typeSystem, inverter, activeFunctions);
 * ProcessTreeNode tree =
 *     builder.build(fnBody, VarEnvironment.initial(goals, env));
 * Optional<CaseComponents> components =
 *     builder.extractCaseComponents(tree);
 * }</pre>
 *
 * @see ProcessTreeNode
 */
public class ProcessTreeBuilder {
  private final TypeSystem typeSystem;
  private final PredicateInverter inverter;
  private final ImmutableSet<Core.Exp> activeFunctions;

  /**
   * Creates a ProcessTreeBuilder.
   *
   * @param typeSystem Type system for expression construction
   * @param inverter Predicate inverter for attempting inversions at terminals
   * @param activeFunctions Functions currently being inverted (for recursion
   *     detection)
   */
  public ProcessTreeBuilder(
      TypeSystem typeSystem,
      PredicateInverter inverter,
      Set<Core.Exp> activeFunctions) {
    this.typeSystem = requireNonNull(typeSystem);
    this.inverter = inverter;
    this.activeFunctions = ImmutableSet.copyOf(activeFunctions);
  }

  /**
   * Builds a PPT from the given expression.
   *
   * <p><b>Handles exists patterns</b>: Expressions like {@code exists z where
   * P} (represented as Core.From) are unwrapped and the body P is analyzed
   * directly.
   *
   * @param exp The predicate expression to analyze
   * @param env Current variable environment
   * @return Root node of the PPT
   */
  public ProcessTreeNode build(Core.Exp exp, VarEnvironment env) {
    requireNonNull(exp, "exp");
    requireNonNull(env, "env");

    // Handle orelse (disjunction)
    if (exp.op == Op.APPLY) {
      Core.Apply apply = (Core.Apply) exp;

      if (apply.isCallTo(BuiltIn.Z_ORELSE)) {
        return buildBranch(apply, env);
      }

      if (apply.isCallTo(BuiltIn.Z_ANDALSO)) {
        return buildSequence(apply, env);
      }

      // Check for recursive call
      if (apply.fn.op == Op.ID) {
        Core.Id fnId = (Core.Id) apply.fn;
        if (activeFunctions.contains(fnId)) {
          return TerminalNode.recursive(exp, env);
        }
      }
    }

    // Handle exists pattern: exists x where P compiles to Core.From
    if (exp.op == Op.FROM) {
      Core.From from = (Core.From) exp;
      if (isExistsPattern(from)) {
        Core.Exp body = extractWhereClause(from);
        if (body != null) {
          return build(body, env); // Recurse on the where body
        }
      }
    }

    // Terminal case: try to invert
    return buildTerminal(exp, env);
  }

  /**
   * Checks if a From expression is an exists pattern.
   *
   * <p><b>CRITICAL FIX - Exists patterns vs Generators</b>:
   *
   * <ul>
   *   <li><b>Exists pattern</b>: {@code exists x where P}
   *       <ul>
   *         <li>Compiles to Core.From with WHERE step(s) but NO YIELD
   *         <li>NO SCAN steps (no iteration source)
   *       </ul>
   *   <li><b>Generator</b>: {@code from x in xs where x > 5}
   *       <ul>
   *         <li>Compiles to Core.From with SCAN and WHERE steps
   *         <li>Has a SCAN that specifies the iteration source (xs)
   *       </ul>
   * </ul>
   *
   * <p>This method distinguishes between them by checking for SCAN.
   *
   * @param from The From expression to check
   * @return true if this is an exists pattern (has WHERE, no YIELD, no SCAN)
   */
  private boolean isExistsPattern(Core.From from) {
    // Check for WHERE clause
    boolean hasWhere = from.steps.stream().anyMatch(s -> s.op == Op.WHERE);

    // Exists patterns don't have YIELD
    boolean hasYield = from.steps.stream().anyMatch(s -> s.op == Op.YIELD);

    // CRITICAL FIX: Generators have SCAN, exists patterns do not
    boolean hasScan = from.steps.stream().anyMatch(s -> s.op == Op.SCAN);

    return hasWhere && !hasYield && !hasScan; // FIXED
  }

  /**
   * Extracts WHERE clause body from an exists pattern.
   *
   * <p>For pattern {@code exists x where P}, returns P. For patterns with
   * multiple WHERE clauses, combines them with andalso.
   *
   * @param from The From expression (should be exists pattern)
   * @return The WHERE clause expression, or null if not found
   */
  private Core.Exp extractWhereClause(Core.From from) {
    List<Core.Exp> whereExprs = new ArrayList<>();

    // Find all WHERE steps
    for (Core.FromStep step : from.steps) {
      if (step.op == Op.WHERE) {
        Core.Where where = (Core.Where) step;
        whereExprs.add(where.exp);
      }
    }

    if (whereExprs.isEmpty()) {
      return null;
    } else if (whereExprs.size() == 1) {
      return whereExprs.get(0);
    } else {
      // Combine multiple WHERE clauses with andalso
      Core.Exp combined = whereExprs.get(0);
      for (int i = 1; i < whereExprs.size(); i++) {
        combined = core.andAlso(typeSystem, combined, whereExprs.get(i));
      }
      return combined;
    }
  }

  /** Builds a branch node for an orelse expression. */
  private BranchNode buildBranch(Core.Apply orElseApply, VarEnvironment env) {
    Core.Exp leftExpr = orElseApply.arg(0);
    Core.Exp rightExpr = orElseApply.arg(1);

    ProcessTreeNode left = build(leftExpr, env);
    ProcessTreeNode right = build(rightExpr, env);

    return new BranchNode(orElseApply, env, left, right);
  }

  /** Builds a sequence node for an andalso expression. */
  private SequenceNode buildSequence(
      Core.Apply andAlsoApply, VarEnvironment env) {
    // Decompose into flat list of conjuncts
    List<Core.Exp> conjuncts = core.decomposeAnd(andAlsoApply);

    // Identify join variables before building children
    Set<Core.NamedPat> joins = identifyJoinVariables(conjuncts, env);
    VarEnvironment envWithJoins = env.withJoinVars(joins);

    // Build child nodes
    List<ProcessTreeNode> children = new ArrayList<>();
    for (Core.Exp conjunct : conjuncts) {
      children.add(build(conjunct, envWithJoins));
    }

    return new SequenceNode(andAlsoApply, envWithJoins, children);
  }

  /** Builds a terminal node, attempting inversion. */
  private TerminalNode buildTerminal(Core.Exp exp, VarEnvironment env) {
    // Attempt inversion using the PredicateInverter
    Optional<PredicateInverter.Result> result = tryInvert(exp, env);
    return TerminalNode.of(exp, env, result);
  }

  /** Attempts to invert an expression using the PredicateInverter. */
  private Optional<PredicateInverter.Result> tryInvert(
      Core.Exp exp, VarEnvironment env) {
    if (inverter == null) {
      return Optional.empty();
    }

    try {
      PredicateInverter.Result result =
          PredicateInverter.invert(
              typeSystem,
              env.typeEnv,
              exp,
              ImmutableList.copyOf(env.unboundGoals()),
              env.boundVars);

      // Only consider it successful if there are no remaining filters
      if (result.remainingFilters.isEmpty()) {
        return Optional.of(result);
      }
      return Optional.empty();
    } catch (Exception e) {
      // Inversion failed
      return Optional.empty();
    }
  }

  /**
   * Identifies join variables in a list of conjuncts.
   *
   * <p>A join variable is one that appears in multiple conjuncts and is used to
   * link predicates together.
   *
   * @param conjuncts List of conjunct expressions
   * @param env Current variable environment
   * @return Set of identified join variables
   */
  public Set<Core.NamedPat> identifyJoinVariables(
      List<Core.Exp> conjuncts, VarEnvironment env) {

    // Collect variables used in each conjunct
    List<Set<Core.NamedPat>> varSets = new ArrayList<>();
    for (Core.Exp conjunct : conjuncts) {
      varSets.add(collectFreeVariables(conjunct));
    }

    // Find variables that appear in multiple conjuncts
    Set<Core.NamedPat> joinVars = new HashSet<>();
    for (int i = 0; i < varSets.size(); i++) {
      for (int j = i + 1; j < varSets.size(); j++) {
        for (Core.NamedPat var : varSets.get(i)) {
          if (varSets.get(j).contains(var)) {
            // Skip if it's a goal variable (those are outputs, not joins)
            if (!env.isGoal(var)) {
              joinVars.add(var);
            }
          }
        }
      }
    }

    return joinVars;
  }

  /** Collects free variables in an expression. */
  private Set<Core.NamedPat> collectFreeVariables(Core.Exp exp) {
    Set<Core.NamedPat> vars = new HashSet<>();
    collectFreeVarsRecursive(exp, vars);
    return vars;
  }

  private void collectFreeVarsRecursive(Core.Exp exp, Set<Core.NamedPat> vars) {
    if (exp.op == Op.ID) {
      Core.Id id = (Core.Id) exp;
      vars.add(id.idPat);
    } else if (exp.op == Op.APPLY) {
      Core.Apply apply = (Core.Apply) exp;
      if (apply.fn.op != Op.FN_LITERAL) {
        collectFreeVarsRecursive(apply.fn, vars);
      }
      collectFreeVarsRecursive(apply.arg, vars);
    } else if (exp.op == Op.TUPLE) {
      Core.Tuple tuple = (Core.Tuple) exp;
      for (Core.Exp arg : tuple.args) {
        collectFreeVarsRecursive(arg, vars);
      }
    } else if (exp.op == Op.FROM) {
      Core.From from = (Core.From) exp;
      for (Core.FromStep step : from.steps) {
        collectFreeVarsInStep(step, vars);
      }
    }
  }

  private void collectFreeVarsInStep(
      Core.FromStep step, Set<Core.NamedPat> vars) {
    if (step.op == Op.SCAN) {
      Core.Scan scan = (Core.Scan) step;
      collectFreeVarsRecursive(scan.exp, vars);
      collectFreeVarsRecursive(scan.condition, vars);
    } else if (step.op == Op.WHERE) {
      Core.Where where = (Core.Where) step;
      collectFreeVarsRecursive(where.exp, vars);
    }
  }

  /**
   * Extracts case components from a PPT for transitive closure.
   *
   * <p>This identifies:
   *
   * <ul>
   *   <li>The base case expression (e.g., edge(x,y))
   *   <li>The recursive call expression (e.g., path(z,y))
   *   <li>The join variables (e.g., z)
   * </ul>
   *
   * @param tree The PPT root node (should be a BranchNode)
   * @return Extracted components, or empty if pattern doesn't match
   */
  public Optional<CaseComponents> extractCaseComponents(ProcessTreeNode tree) {
    if (!(tree instanceof BranchNode)) {
      // Log: "Expected BranchNode for transitive closure pattern, got: " +
      // tree.getClass().getName()
      return Optional.empty();
    }
    BranchNode branch = (BranchNode) tree;

    if (!(branch.left instanceof TerminalNode)) {
      // Log: "Expected TerminalNode as base case, got: " +
      // branch.left.getClass().getName()
      return Optional.empty();
    }
    TerminalNode baseTerminal = (TerminalNode) branch.left;

    if (!baseTerminal.isInverted()) {
      // Log: "Base case not successfully inverted"
      return Optional.empty();
    }

    Optional<RecursiveComponents> recursiveOpt =
        extractRecursiveComponents(branch.right);

    if (!recursiveOpt.isPresent()) {
      // Log: "No recursive call found in right branch"
      return Optional.empty();
    }

    RecursiveComponents recursive = recursiveOpt.get();
    return Optional.of(
        new CaseComponents(
            baseTerminal.term,
            baseTerminal.inversionResult.get(),
            recursive.recursiveCall,
            recursive.joinVars));
  }

  private Optional<RecursiveComponents> extractRecursiveComponents(
      ProcessTreeNode node) {
    if (node instanceof SequenceNode) {
      SequenceNode seq = (SequenceNode) node;
      // Look for recursive call among children
      for (ProcessTreeNode child : seq.children) {
        if (child.isRecursiveCall()) {
          TerminalNode recCall = (TerminalNode) child;
          return Optional.of(
              new RecursiveComponents(recCall.term, seq.env.joinVars));
        }
      }
    } else if (node instanceof TerminalNode) {
      TerminalNode term = (TerminalNode) node;
      if (term.isRecursive) {
        return Optional.of(
            new RecursiveComponents(term.term, ImmutableSet.of()));
      }
    }
    return Optional.empty();
  }

  /** Extracted components for building Relational.iterate. */
  public static class CaseComponents {
    public final Core.Exp baseCaseExpr;
    public final PredicateInverter.Result baseCaseResult;
    public final Core.Exp recursiveCallExpr;
    public final Set<Core.NamedPat> joinVars;

    /**
     * Creates CaseComponents.
     *
     * @param baseCaseExpr Expression for the base case (e.g., edge(x,y))
     * @param baseCaseResult Successful inversion result for base case
     * @param recursiveCallExpr Expression for the recursive call (e.g.,
     *     path(z,y))
     * @param joinVars Variables that link base and recursive cases
     */
    public CaseComponents(
        Core.Exp baseCaseExpr,
        PredicateInverter.Result baseCaseResult,
        Core.Exp recursiveCallExpr,
        Set<Core.NamedPat> joinVars) {
      this.baseCaseExpr = requireNonNull(baseCaseExpr);
      this.baseCaseResult = requireNonNull(baseCaseResult);
      this.recursiveCallExpr = requireNonNull(recursiveCallExpr);
      this.joinVars = ImmutableSet.copyOf(joinVars);
    }
  }

  private static class RecursiveComponents {
    final Core.Exp recursiveCall;
    final Set<Core.NamedPat> joinVars;

    RecursiveComponents(Core.Exp recursiveCall, Set<Core.NamedPat> joinVars) {
      this.recursiveCall = recursiveCall;
      this.joinVars = joinVars;
    }
  }
}

// End ProcessTreeBuilder.java
