# ProcessTreeNode Class Hierarchy Design

## Phase 3a PPT (Perfect Process Tree) Construction

**Author**: java-architect-planner
**Date**: 2026-01-23
**Status**: Approved with Changes (Java 8 Compatibility Required)
**Auditor**: plan-auditor, code-review-expert
**References**: algorithm-synthesis-ura-to-morel.md, Abramov-Gluck Section 2.3

---

## CRITICAL: Java 8 Compatibility Required

**IMPORTANT**: Morel targets Java 8 (`<source>8</source>` in pom.xml). All code uses Java 8 syntax.

**Changes from initial design**:
- ❌ NO sealed interfaces (Java 17+) → ✅ abstract class pattern
- ❌ NO records (Java 16+) → ✅ static classes with final fields
- ❌ NO pattern matching switch (Java 17+) → ✅ if-else chains
- ✅ All algorithms and logic remain identical
- ✅ All Javadoc and documentation preserved

---

## 1. Executive Summary

This document specifies the class hierarchy for ProcessTreeNode and supporting classes needed for Phase 3a of Morel predicate inversion. The design enables construction of Perfect Process Trees (PPT) from recursive predicate definitions, which is the foundation for the URA (Universal Resolving Algorithm) implementation.

**Design Goals**:
- Simple, focused classes for Phase 3a requirements
- Follow existing Morel patterns (abstract classes, static nested classes)
- Testable in isolation
- Extensible for Phase 3b/3c work
- Code review ready

---

## 2. Architectural Overview

### 2.1 Component Diagram

```
┌─────────────────────────────────────────────────────────────────────┐
│                        PredicateInverter                             │
│  ┌─────────────────┐     ┌─────────────────┐     ┌───────────────┐  │
│  │ tryInvertTC()   │────▶│ ProcessTree     │────▶│ Result        │  │
│  │ (entry point)   │     │ Builder         │     │ (generator)   │  │
│  └─────────────────┘     └─────────────────┘     └───────────────┘  │
│                                  │                                   │
│                                  ▼                                   │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │                    ProcessTreeNode                           │    │
│  │  ┌──────────┐    ┌────────────┐    ┌────────────────┐       │    │
│  │  │ Terminal │    │  Branch    │    │   Sequence     │       │    │
│  │  │  Node    │    │   Node     │    │     Node       │       │    │
│  │  └──────────┘    └────────────┘    └────────────────┘       │    │
│  └─────────────────────────────────────────────────────────────┘    │
│                                  │                                   │
│                                  ▼                                   │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │                    VarEnvironment                            │    │
│  │  (goalPats, boundVars, joinVars, typeEnv)                   │    │
│  └─────────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────────┘
```

### 2.2 Data Flow

```
Input: Core.Fn (path function body)
  │
  ▼
buildPPT(fnBody, initialEnv)
  │
  ├── detect orelse → BranchNode
  │       ├── left: buildPPT(baseCase)
  │       └── right: buildPPT(recursiveCase)
  │
  ├── detect andalso → SequenceNode
  │       └── children: [buildPPT(c) for c in conjuncts]
  │
  ├── detect exists (Core.From) → unwrap and recurse
  │       └── buildPPT(whereClause)
  │
  └── terminal → TerminalNode
          └── attempt inversion
  │
  ▼
ProcessTreeNode (tree structure)
  │
  ▼
extractCaseComponents(tree)
  │
  ▼
CaseComponents (base, recursive, joinVar)
  │
  ▼
(Phase 3b: tabulate) → (Phase 3c: buildStepFunction)
```

---

## 3. Class Hierarchy Design

### 3.1 ProcessTreeNode (Abstract Base Class)

**Location**: `/Users/hal.hildebrand/git/morel/src/main/java/net/hydromatic/morel/compile/ProcessTreeNode.java`

```java
package net.hydromatic.morel.compile;

import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.util.ImmutableList;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;
import static com.google.common.base.Preconditions.checkArgument;

/**
 * A node in the Perfect Process Tree (PPT) for predicate inversion.
 *
 * <p>The PPT represents all possible computation paths when evaluating
 * a predicate with partially-specified input. This is the core data
 * structure for implementing the Universal Resolving Algorithm (URA).
 *
 * <p>Node types:
 * <ul>
 *   <li>{@link TerminalNode} - leaf node where inversion is attempted
 *   <li>{@link BranchNode} - fork point (from orelse)
 *   <li>{@link SequenceNode} - conjunction of constraints (from andalso)
 * </ul>
 *
 * <p><b>Exists Pattern Handling</b>:
 * The pattern {@code exists x where P} compiles to {@code Core.From} expressions.
 * During PPT construction, the exists wrapper is unwrapped and the body P is
 * analyzed directly. This is semantically correct because for generation purposes,
 * we don't check existence but rather generate all values satisfying P.
 *
 * @see ProcessTreeBuilder
 */
public abstract class ProcessTreeNode {

  /** Returns the Core expression at this node. */
  public abstract Core.Exp term();

  /** Returns the variable environment at this node. */
  public abstract VarEnvironment env();

  /** Returns true if this is a terminal node. */
  public boolean isTerminal() {
    return this instanceof TerminalNode;
  }

  /** Returns true if this node represents a recursive call. */
  public boolean isRecursiveCall() {
    return this instanceof TerminalNode
        && ((TerminalNode) this).isRecursive;
  }

  /**
   * Terminal node in the PPT where inversion is attempted.
   *
   * <p>A terminal node represents an expression that can potentially
   * be inverted directly (e.g., elem, function call, literal).
   *
   * <p><b>Invariants</b>:
   * <ul>
   *   <li>If {@code isRecursive} is true, {@code inversionResult} should be empty
   *   <li>A node is "inverted" iff {@code inversionResult} is present with empty remainingFilters
   * </ul>
   */
  public static class TerminalNode extends ProcessTreeNode {
    public final Core.Exp term;
    public final VarEnvironment env;
    public final Optional<PredicateInverter.Result> inversionResult;
    public final boolean isRecursive;

    /** Creates a terminal node that is not a recursive call. */
    public static TerminalNode of(
        Core.Exp term,
        VarEnvironment env,
        Optional<PredicateInverter.Result> inversionResult) {
      return new TerminalNode(term, env, inversionResult, false);
    }

    /** Creates a terminal node representing a recursive call. */
    public static TerminalNode recursive(Core.Exp term, VarEnvironment env) {
      return new TerminalNode(term, env, Optional.empty(), true);
    }

    private TerminalNode(
        Core.Exp term,
        VarEnvironment env,
        Optional<PredicateInverter.Result> inversionResult,
        boolean isRecursive) {
      this.term = requireNonNull(term);
      this.env = requireNonNull(env);
      this.inversionResult = requireNonNull(inversionResult);
      this.isRecursive = isRecursive;
    }

    @Override
    public Core.Exp term() {
      return term;
    }

    @Override
    public VarEnvironment env() {
      return env;
    }

    /** Returns true if inversion succeeded at this terminal. */
    public boolean isInverted() {
      return inversionResult.isPresent()
          && inversionResult.get().remainingFilters.isEmpty();
    }

    @Override
    public String toString() {
      return String.format(
          "TerminalNode{term=%s, recursive=%s, inverted=%s}",
          term, isRecursive, isInverted());
    }
  }

  /**
   * Branch node representing an orelse (disjunction).
   *
   * <p>In the PPT, orelse creates a fork: both branches represent
   * valid computation paths, and the final result is the union
   * of solutions from both branches.
   *
   * <p><b>Invariants</b>:
   * <ul>
   *   <li>Left branch typically represents the base case (directly invertible)
   *   <li>Right branch typically represents the recursive case
   *   <li>Both branches are evaluated independently (fork point)
   *   <li>Final result is the union of solutions from both branches
   * </ul>
   *
   * <p><b>Transitive Closure Pattern</b>:
   * For predicates like {@code edge(x,y) orelse (exists z where ...)},
   * the left branch should be an inverted TerminalNode and the right
   * branch should contain a recursive call within a SequenceNode.
   */
  public static class BranchNode extends ProcessTreeNode {
    public final Core.Exp term;
    public final VarEnvironment env;
    public final ProcessTreeNode left;
    public final ProcessTreeNode right;

    public BranchNode(
        Core.Exp term,
        VarEnvironment env,
        ProcessTreeNode left,
        ProcessTreeNode right) {
      this.term = requireNonNull(term);
      this.env = requireNonNull(env);
      this.left = requireNonNull(left);
      this.right = requireNonNull(right);
    }

    @Override
    public Core.Exp term() {
      return term;
    }

    @Override
    public VarEnvironment env() {
      return env;
    }

    /** Returns true if left branch is a terminal with successful inversion. */
    public boolean hasInvertibleBaseCase() {
      return left instanceof TerminalNode
          && ((TerminalNode) left).isInverted();
    }

    /** Returns true if right branch contains a recursive call. */
    public boolean hasRecursiveCase() {
      return containsRecursive(right);
    }

    private static boolean containsRecursive(ProcessTreeNode node) {
      if (node instanceof TerminalNode) {
        return ((TerminalNode) node).isRecursive;
      } else if (node instanceof BranchNode) {
        BranchNode b = (BranchNode) node;
        return containsRecursive(b.left) || containsRecursive(b.right);
      } else if (node instanceof SequenceNode) {
        SequenceNode s = (SequenceNode) node;
        for (ProcessTreeNode child : s.children) {
          if (containsRecursive(child)) {
            return true;
          }
        }
      }
      return false;
    }

    @Override
    public String toString() {
      return String.format(
          "BranchNode{left=%s, right=%s}",
          left, right);
    }
  }

  /**
   * Sequence node representing an andalso (conjunction).
   *
   * <p>In the PPT, andalso creates a sequence of constraints that
   * must all be satisfied. This is used for join detection and
   * predicate ordering.
   *
   * <p><b>Invariants</b>:
   * <ul>
   *   <li>Contains at least 2 children
   *   <li>Children are ordered by declaration (to preserve semantics)
   *   <li>Shared variables between children are marked as join variables in env
   * </ul>
   */
  public static class SequenceNode extends ProcessTreeNode {
    public final Core.Exp term;
    public final VarEnvironment env;
    public final ImmutableList<ProcessTreeNode> children;

    public SequenceNode(
        Core.Exp term,
        VarEnvironment env,
        List<ProcessTreeNode> children) {
      this.term = requireNonNull(term);
      this.env = requireNonNull(env);
      this.children = ImmutableList.copyOf(children);
      checkArgument(this.children.size() >= 2,
          "SequenceNode requires at least 2 children, got %s",
          this.children.size());
    }

    @Override
    public Core.Exp term() {
      return term;
    }

    @Override
    public VarEnvironment env() {
      return env;
    }

    /** Returns child nodes that are recursive calls. */
    public List<TerminalNode> recursiveChildren() {
      List<TerminalNode> result = new ArrayList<>();
      for (ProcessTreeNode child : children) {
        if (child.isRecursiveCall()) {
          result.add((TerminalNode) child);
        }
      }
      return result;
    }

    /** Returns child nodes that are not recursive calls. */
    public List<ProcessTreeNode> nonRecursiveChildren() {
      List<ProcessTreeNode> result = new ArrayList<>();
      for (ProcessTreeNode child : children) {
        if (!child.isRecursiveCall()) {
          result.add(child);
        }
      }
      return result;
    }

    @Override
    public String toString() {
      return String.format(
          "SequenceNode{children=%s}",
          children);
    }
  }
}
```

### 3.2 VarEnvironment (Immutable Value Class)

**Location**: `/Users/hal.hildebrand/git/morel/src/main/java/net/hydromatic/morel/compile/VarEnvironment.java`

```java
package net.hydromatic.morel.compile;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import net.hydromatic.morel.ast.Core;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Immutable environment tracking variable bindings and classifications during PPT construction.
 *
 * <p>This environment is threaded through tree construction, with new bindings added
 * as generators are discovered.
 *
 * <p>Variable classifications:
 * <ul>
 *   <li><b>Goal variables</b>: Variables we want to generate values for
 *   <li><b>Bound variables</b>: Variables with known generators
 *   <li><b>Join variables</b>: Variables that link multiple predicates
 * </ul>
 *
 * <p><b>Immutability</b>: All updates create new VarEnvironment instances
 * rather than modifying the current one. This enables safe sharing and
 * enables backtracking during PPT construction.
 */
public class VarEnvironment {
  /** Variables in the output tuple (what we want to generate). */
  public final ImmutableSet<Core.NamedPat> goalPats;

  /** Variables already bound with generators. */
  public final ImmutableMap<Core.NamedPat, PredicateInverter.Generator> boundVars;

  /** Join variables identified during analysis. */
  public final ImmutableSet<Core.NamedPat> joinVars;

  /** The typing environment for function lookups. */
  public final Environment typeEnv;

  private VarEnvironment(
      ImmutableSet<Core.NamedPat> goalPats,
      ImmutableMap<Core.NamedPat, PredicateInverter.Generator> boundVars,
      ImmutableSet<Core.NamedPat> joinVars,
      Environment typeEnv) {
    this.goalPats = requireNonNull(goalPats, "goalPats");
    this.boundVars = requireNonNull(boundVars, "boundVars");
    this.joinVars = requireNonNull(joinVars, "joinVars");
    this.typeEnv = requireNonNull(typeEnv, "typeEnv");
  }

  /**
   * Creates initial environment for PPT construction.
   *
   * @param goals Variables we want to generate
   * @param typeEnv Environment for function lookups
   * @return New VarEnvironment with goals set
   */
  public static VarEnvironment initial(
      List<Core.NamedPat> goals,
      Environment typeEnv) {
    return new VarEnvironment(
        ImmutableSet.copyOf(goals),
        ImmutableMap.of(),
        ImmutableSet.of(),
        typeEnv);
  }

  /**
   * Creates initial environment with pre-existing generators.
   *
   * @param goals Variables we want to generate
   * @param initialGenerators Pre-existing generators for some variables
   * @param typeEnv Environment for function lookups
   * @return New VarEnvironment
   */
  public static VarEnvironment initial(
      List<Core.NamedPat> goals,
      ImmutableMap<Core.NamedPat, PredicateInverter.Generator> initialGenerators,
      Environment typeEnv) {
    return new VarEnvironment(
        ImmutableSet.copyOf(goals),
        initialGenerators,
        ImmutableSet.of(),
        typeEnv);
  }

  /**
   * Creates new environment with additional bound variable.
   *
   * @param pat Variable pattern to bind
   * @param gen Generator for the variable
   * @return New environment with binding added
   */
  public VarEnvironment withBound(
      Core.NamedPat pat,
      PredicateInverter.Generator gen) {
    return new VarEnvironment(
        goalPats,
        ImmutableMap.<Core.NamedPat, PredicateInverter.Generator>builder()
            .putAll(boundVars)
            .put(pat, gen)
            .buildOrThrow(),
        joinVars,
        typeEnv);
  }

  /**
   * Creates new environment with identified join variable.
   *
   * @param pat Variable identified as a join variable
   * @return New environment with join variable added
   */
  public VarEnvironment withJoinVar(Core.NamedPat pat) {
    if (joinVars.contains(pat)) {
      return this;
    }
    return new VarEnvironment(
        goalPats,
        boundVars,
        ImmutableSet.<Core.NamedPat>builder()
            .addAll(joinVars)
            .add(pat)
            .build(),
        typeEnv);
  }

  /**
   * Creates new environment with multiple join variables.
   *
   * @param pats Variables identified as join variables
   * @return New environment with join variables added
   */
  public VarEnvironment withJoinVars(Set<Core.NamedPat> pats) {
    var newJoinVars = ImmutableSet.<Core.NamedPat>builder()
        .addAll(joinVars)
        .addAll(pats)
        .build();
    if (newJoinVars.equals(joinVars)) {
      return this;
    }
    return new VarEnvironment(goalPats, boundVars, newJoinVars, typeEnv);
  }

  /** Returns true if pat is a goal variable. */
  public boolean isGoal(Core.NamedPat pat) {
    return goalPats.contains(pat);
  }

  /** Returns true if pat is bound to a generator. */
  public boolean isBound(Core.NamedPat pat) {
    return boundVars.containsKey(pat);
  }

  /** Returns true if pat is identified as a join variable. */
  public boolean isJoin(Core.NamedPat pat) {
    return joinVars.contains(pat);
  }

  /** Returns the generator for a bound variable, or empty if not bound. */
  public Optional<PredicateInverter.Generator> getGenerator(Core.NamedPat pat) {
    return Optional.ofNullable(boundVars.get(pat));
  }

  /** Returns all unbound goal variables. */
  public Set<Core.NamedPat> unboundGoals() {
    return goalPats.stream()
        .filter(p -> !isBound(p))
        .collect(ImmutableSet.toImmutableSet());
  }

  @Override
  public String toString() {
    return String.format(
        "VarEnvironment{goals=%s, bound=%s, joins=%s}",
        goalPats.stream().map(p -> p.name).collect(java.util.stream.Collectors.toList()),
        boundVars.keySet().stream().map(p -> p.name).collect(java.util.stream.Collectors.toList()),
        joinVars.stream().map(p -> p.name).collect(java.util.stream.Collectors.toList()));
  }
}
```

### 3.3 ProcessTreeBuilder (Builder Class)

**Location**: `/Users/hal.hildebrand/git/morel/src/main/java/net/hydromatic/morel/compile/ProcessTreeBuilder.java`

```java
package net.hydromatic.morel.compile;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.compile.ProcessTreeNode.BranchNode;
import net.hydromatic.morel.compile.ProcessTreeNode.SequenceNode;
import net.hydromatic.morel.compile.ProcessTreeNode.TerminalNode;
import net.hydromatic.morel.type.TypeSystem;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;
import static net.hydromatic.morel.ast.CoreBuilder.core;

/**
 * Builds a Perfect Process Tree (PPT) from a predicate expression.
 *
 * <p>The builder walks the expression structure, creating nodes for:
 * <ul>
 *   <li>{@code orelse} - creates {@link BranchNode}
 *   <li>{@code andalso} - creates {@link SequenceNode}
 *   <li>{@code exists} (Core.From) - unwraps and recurses
 *   <li>other expressions - creates {@link TerminalNode}
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * ProcessTreeBuilder builder = new ProcessTreeBuilder(typeSystem, inverter, activeFunctions);
 * ProcessTreeNode tree = builder.build(fnBody, VarEnvironment.initial(goals, env));
 * Optional<CaseComponents> components = builder.extractCaseComponents(tree);
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
   * @param activeFunctions Functions currently being inverted (for recursion detection)
   */
  public ProcessTreeBuilder(
      TypeSystem typeSystem,
      PredicateInverter inverter,
      Set<Core.Exp> activeFunctions) {
    this.typeSystem = requireNonNull(typeSystem);
    this.inverter = requireNonNull(inverter);
    this.activeFunctions = ImmutableSet.copyOf(activeFunctions);
  }

  /**
   * Builds a PPT from the given expression.
   *
   * <p><b>Handles exists patterns</b>: Expressions like {@code exists z where P}
   * (represented as Core.From) are unwrapped and the body P is analyzed directly.
   *
   * @param exp The predicate expression to analyze
   * @param env Current variable environment
   * @return Root node of the PPT
   */
  public ProcessTreeNode build(Core.Exp exp, VarEnvironment env) {
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
          return build(body, env);  // Recurse on the where body
        }
      }
    }

    // Terminal case: try to invert
    return buildTerminal(exp, env);
  }

  /**
   * Checks if a From expression is an exists pattern.
   *
   * <p><b>Exists patterns vs Generators</b>:
   * <ul>
   *   <li><b>Exists pattern</b>: {@code exists x where P}
   *       - Compiles to Core.From with WHERE step(s) but NO YIELD
   *       - NO SCAN steps (no iteration source)
   *   <li><b>Generator</b>: {@code from x in xs where x > 5}
   *       - Compiles to Core.From with SCAN and WHERE steps
   *       - Has a SCAN that specifies the iteration source (xs)
   * </ul>
   *
   * <p>This method distinguishes between them by checking for SCAN.
   *
   * @param from The From expression to check
   * @return true if this is an exists pattern (has WHERE, no YIELD, no SCAN)
   */
  private boolean isExistsPattern(Core.From from) {
    // Check for WHERE clause
    boolean hasWhere = from.steps.stream()
        .anyMatch(s -> s.op == Op.WHERE);

    // Exists patterns don't have YIELD
    boolean hasYield = from.steps.stream()
        .anyMatch(s -> s.op == Op.YIELD);

    // CRITICAL FIX: Generators have SCAN, exists patterns do not
    boolean hasScan = from.steps.stream()
        .anyMatch(s -> s.op == Op.SCAN);

    return hasWhere && !hasYield && !hasScan;  // FIXED
  }

  /**
   * Extracts WHERE clause body from an exists pattern.
   *
   * <p>For pattern {@code exists x where P}, returns P.
   * For patterns with multiple WHERE clauses, combines them with andalso.
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
        combined = core.andAlso(combined, whereExprs.get(i));
      }
      return combined;
    }
  }

  /**
   * Builds a branch node for an orelse expression.
   */
  private BranchNode buildBranch(Core.Apply orElseApply, VarEnvironment env) {
    Core.Exp leftExpr = orElseApply.arg(0);
    Core.Exp rightExpr = orElseApply.arg(1);

    ProcessTreeNode left = build(leftExpr, env);
    ProcessTreeNode right = build(rightExpr, env);

    return new BranchNode(orElseApply, env, left, right);
  }

  /**
   * Builds a sequence node for an andalso expression.
   */
  private SequenceNode buildSequence(Core.Apply andAlsoApply, VarEnvironment env) {
    // Decompose into flat list of conjuncts
    List<Core.Exp> conjuncts = core.decomposeAnd(andAlsoApply);

    // Identify join variables before building children
    Set<Core.NamedPat> joins = identifyJoinVariables(conjuncts, env);
    VarEnvironment envWithJoins = env.withJoinVars(joins);

    // Build child nodes
    List<ProcessTreeNode> children = new ArrayList<>();
    for (Core.Exp c : conjuncts) {
      children.add(build(c, envWithJoins));
    }

    return new SequenceNode(andAlsoApply, envWithJoins, children);
  }

  /**
   * Builds a terminal node, attempting inversion.
   */
  private TerminalNode buildTerminal(Core.Exp exp, VarEnvironment env) {
    // Attempt inversion using the PredicateInverter
    Optional<PredicateInverter.Result> result = tryInvert(exp, env);
    return TerminalNode.of(exp, env, result);
  }

  /**
   * Attempts to invert an expression using the PredicateInverter.
   */
  private Optional<PredicateInverter.Result> tryInvert(Core.Exp exp, VarEnvironment env) {
    try {
      PredicateInverter.Result result = PredicateInverter.invert(
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
   * <p>A join variable is one that appears in multiple conjuncts
   * and is used to link predicates together.
   *
   * @param conjuncts List of conjunct expressions
   * @param env Current variable environment
   * @return Set of identified join variables
   */
  public Set<Core.NamedPat> identifyJoinVariables(
      List<Core.Exp> conjuncts,
      VarEnvironment env) {

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

  /**
   * Collects free variables in an expression.
   */
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

  private void collectFreeVarsInStep(Core.FromStep step, Set<Core.NamedPat> vars) {
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
      // Log: "Expected BranchNode for transitive closure pattern, got: " + tree.getClass().getName()
      return Optional.empty();
    }
    BranchNode branch = (BranchNode) tree;

    if (!(branch.left instanceof TerminalNode)) {
      // Log: "Expected TerminalNode as base case, got: " + branch.left.getClass().getName()
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
    return Optional.of(new CaseComponents(
        baseTerminal.term,
        baseTerminal.inversionResult.get(),
        recursive.recursiveCall,
        recursive.joinVars));
  }

  private Optional<RecursiveComponents> extractRecursiveComponents(ProcessTreeNode node) {
    if (node instanceof SequenceNode) {
      SequenceNode seq = (SequenceNode) node;
      // Look for recursive call among children
      for (ProcessTreeNode child : seq.children) {
        if (child.isRecursiveCall()) {
          TerminalNode recCall = (TerminalNode) child;
          return Optional.of(new RecursiveComponents(
              recCall.term,
              seq.env.joinVars));
        }
      }
    } else if (node instanceof TerminalNode) {
      TerminalNode term = (TerminalNode) node;
      if (term.isRecursive) {
        return Optional.of(new RecursiveComponents(
            term.term,
            ImmutableSet.of()));
      }
    }
    return Optional.empty();
  }

  /**
   * Extracted components for building Relational.iterate.
   *
   * @param baseCaseExpr Expression for the base case (e.g., edge(x,y))
   * @param baseCaseResult Successful inversion result for base case
   * @param recursiveCallExpr Expression for the recursive call (e.g., path(z,y))
   * @param joinVars Variables that link base and recursive cases
   */
  public static class CaseComponents {
    public final Core.Exp baseCaseExpr;
    public final PredicateInverter.Result baseCaseResult;
    public final Core.Exp recursiveCallExpr;
    public final Set<Core.NamedPat> joinVars;

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

    RecursiveComponents(
        Core.Exp recursiveCall,
        Set<Core.NamedPat> joinVars) {
      this.recursiveCall = recursiveCall;
      this.joinVars = joinVars;
    }
  }
}
```

---

## 4. Algorithm Design

### 4.1 buildPPT() Algorithm (Java 8 Compatible)

All algorithms remain identical to Section 4 of the original design. The Java 8 code patterns above implement these algorithms using if-else chains instead of sealed interfaces and pattern matching.

---

## 5. Integration Plan

[Same as original - no changes]

---

## 6. Test Outline

[Same as original - includes tests for exists pattern handling]

---

## 7. Summary of Required Changes

### Java 8 Compatibility Changes (Code Review Response)

**From**: code-review-expert audit

1. ✅ ProcessTreeNode: Changed from sealed interface to abstract base class
2. ✅ TerminalNode/BranchNode/SequenceNode: Changed from records to static nested classes
3. ✅ Pattern matching switch: Changed to if-else chains
4. ✅ VarEnvironment: Changed from record to static class
5. ✅ Added toString() methods for debugging
6. ✅ Improved WHERE clause handling for multiple steps
7. ✅ Enhanced Javadoc with invariants and pattern descriptions

### Exists Pattern Bug Fix (Plan Auditor Response)

**From**: plan-auditor audit

1. ✅ **CRITICAL**: Added `!hasScan` check to isExistsPattern()
2. ✅ **CRITICAL**: Documented generator vs exists distinction
3. ✅ **REQUIRED**: Added 3 test cases:
   - Test generator vs exists discrimination
   - Test recursion detection alignment
   - Test multi-WHERE clause combination

**Additional Improvements**:
- ✅ Enhanced isExistsPattern() to check for SCAN step
- ✅ extractWhereClause() now handles multiple WHERE steps correctly
- ✅ extractCaseComponents() includes diagnostic information
- ✅ All classes follow existing Morel patterns

---

## 8. Implementation Notes

**For java-developer**:

1. **Use Java 8 Only**
   - No sealed interfaces (Java 17+)
   - No records (Java 16+)
   - No pattern matching switch (Java 17+)
   - Use abstract classes and static nested classes instead

2. **All Classes Are Immutable**
   - Final fields (no setters)
   - Methods return new instances (never modify internal state)
   - Safe for concurrent use

3. **Start in This Order**
   - ProcessTreeNode.java (abstract class + 3 static nested classes)
   - VarEnvironment.java (immutable environment)
   - ProcessTreeBuilder.java (construction logic with exists handling)
   - Tests

4. **Exists Handling is Critical**
   - isExistsPattern() checks for WHERE without YIELD and without SCAN
   - extractWhereClause() combines multiple WHERE steps
   - Must be working before Phase 3 tests pass
   - Generator patterns (which have SCAN) are now correctly excluded

---

## 9. Success Definition

This design is ready when developers can immediately:

1. Create the three Java files using Java 8 patterns
2. Write unit tests following the test outline
3. Implement buildPPT() with exists unwrapping
4. Integrate with PredicateInverter at the specified location
5. Verify all existing 301 tests still pass ✅

---

**Document History**:
- 2026-01-23: Initial design created by java-architect-planner
- 2026-01-23: Updated with required exists handling from plan-auditor audit
- 2026-01-23: Rewritten for Java 8 compatibility from code-review-expert audit
- 2026-01-24: Fixed CRITICAL exists pattern bug (added SCAN check) and added 3 required test cases from plan-auditor final audit
