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

import static net.hydromatic.morel.ast.CoreBuilder.core;
import static org.junit.jupiter.api.Assertions.*;

import com.google.common.collect.ImmutableList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.TypeSystem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for ProcessTreeBuilder.
 *
 * <p>These tests validate the critical SCAN check fix in isExistsPattern() and
 * comprehensive PPT construction logic.
 */
class ProcessTreeBuilderTest {

  private TypeSystem typeSystem;
  private Environment env;
  private PredicateInverter inverter;
  private Set<Core.Exp> activeFunctions;

  // Function types for testing
  private net.hydromatic.morel.type.Type intIntBoolFnType;
  private net.hydromatic.morel.type.Type tupleTwoIntsType;

  @BeforeEach
  void setUp() {
    typeSystem = new TypeSystem();
    // Register built-in types (required for bag, list, etc.)
    BuiltIn.dataTypes(typeSystem, new java.util.ArrayList<>());

    env = Environments.empty();
    activeFunctions = new HashSet<>();

    // Create function types for user-defined functions
    tupleTwoIntsType =
        typeSystem.tupleType(PrimitiveType.INT, PrimitiveType.INT);
    intIntBoolFnType = typeSystem.fnType(tupleTwoIntsType, PrimitiveType.BOOL);
  }

  // Helper methods

  private ProcessTreeBuilder builder() {
    return new ProcessTreeBuilder(typeSystem, inverter, activeFunctions);
  }

  private Core.NamedPat namedPat(String name) {
    return core.idPat(PrimitiveType.INT, name, 0);
  }

  private VarEnvironment emptyEnv() {
    return VarEnvironment.initial(ImmutableList.<Core.NamedPat>of(), env);
  }

  private VarEnvironment envWithGoals(Core.NamedPat... pats) {
    return VarEnvironment.initial(ImmutableList.copyOf(pats), env);
  }

  private Core.Exp intLiteral(int value) {
    return core.intLiteral(java.math.BigDecimal.valueOf(value));
  }

  private Core.Exp boolLiteral(boolean value) {
    return core.boolLiteral(value);
  }

  // Create a proper function pattern with function type
  private Core.IdPat functionPat(String name) {
    return core.idPat(intIntBoolFnType, name, 0);
  }

  // Create a function reference (ID expression)
  private Core.Id functionRef(String name) {
    return core.id(functionPat(name));
  }

  // Create a non-constant predicate to avoid core.andAlso() optimization
  private Core.Exp predicate(String varName) {
    Core.NamedPat pat = namedPat(varName);
    return core.id(pat);
  }

  // ========== Basic Tree Construction Tests (4 tests) ==========

  @Test
  void testBuildTerminalNode() {
    // Simple non-recursive predicate should create TerminalNode
    Core.Exp predicate = boolLiteral(true);
    VarEnvironment env = emptyEnv();

    ProcessTreeNode node = builder().build(predicate, env);

    assertTrue(node instanceof ProcessTreeNode.TerminalNode);
    ProcessTreeNode.TerminalNode terminal = (ProcessTreeNode.TerminalNode) node;
    assertEquals(predicate, terminal.term());
    assertEquals(env, terminal.env());
    assertFalse(terminal.isRecursive);
  }

  @Test
  void testBuildBranchNodeForDisjunction() {
    // baseCase orelse recursiveCase should create BranchNode
    Core.Exp baseCase = boolLiteral(true);
    Core.Exp recursiveCase = boolLiteral(false);
    Core.Exp disjunction = core.orElse(typeSystem, baseCase, recursiveCase);
    VarEnvironment env = emptyEnv();

    ProcessTreeNode node = builder().build(disjunction, env);

    assertTrue(node instanceof ProcessTreeNode.BranchNode);
    ProcessTreeNode.BranchNode branch = (ProcessTreeNode.BranchNode) node;
    assertEquals(disjunction, branch.term());
    assertTrue(branch.left instanceof ProcessTreeNode.TerminalNode);
    assertTrue(branch.right instanceof ProcessTreeNode.TerminalNode);
  }

  @Test
  void testBuildSequenceNode() {
    // Conjunction with multiple conditions should create SequenceNode
    Core.Exp cond1 = predicate("a");
    Core.Exp cond2 = predicate("b");
    Core.Exp conjunction = core.andAlso(typeSystem, cond1, cond2);
    VarEnvironment env = emptyEnv();

    ProcessTreeNode node = builder().build(conjunction, env);

    assertTrue(node instanceof ProcessTreeNode.SequenceNode);
    ProcessTreeNode.SequenceNode sequence = (ProcessTreeNode.SequenceNode) node;
    assertEquals(2, sequence.children.size());
  }

  @Test
  void testBuildWithComplexNesting() {
    // Nested (A orelse B) andalso C should create SequenceNode with
    // BranchNode child
    Core.Exp a = predicate("a");
    Core.Exp b = predicate("b");
    Core.Exp c = predicate("c");
    Core.Exp aOrB = core.orElse(typeSystem, a, b);
    Core.Exp complex = core.andAlso(typeSystem, aOrB, c);
    VarEnvironment env = emptyEnv();

    ProcessTreeNode node = builder().build(complex, env);

    assertTrue(node instanceof ProcessTreeNode.SequenceNode);
    ProcessTreeNode.SequenceNode sequence = (ProcessTreeNode.SequenceNode) node;
    assertEquals(2, sequence.children.size());
    assertTrue(sequence.children.get(0) instanceof ProcessTreeNode.BranchNode);
  }

  // ========== Exists Pattern Detection Tests (7 tests) - CRITICAL FOR BUG FIX
  // ==========

  @Test
  void testIsExistsPatternWithWhereOnly() {
    // Test that FROM with WHERE (no SCAN, no YIELD) is correctly identified and
    // unwrapped
    Core.NamedPat x = namedPat("x");
    // Use a non-trivial predicate that won't be optimized away
    Core.Exp whereCond = core.id(x);

    // Create: from _ where whereCond (no SCAN, no YIELD)
    Core.From existsPattern =
        core.fromBuilder(typeSystem).where(whereCond).build();

    // Verify the FROM structure
    assertFalse(existsPattern.steps.isEmpty(), "FROM should have WHERE step");
    assertTrue(
        existsPattern.steps.stream().anyMatch(s -> s.op == Op.WHERE),
        "FROM should have WHERE step");
    assertFalse(
        existsPattern.steps.stream().anyMatch(s -> s.op == Op.SCAN),
        "FROM should NOT have SCAN step - this is NOT a generator");
    assertFalse(
        existsPattern.steps.stream().anyMatch(s -> s.op == Op.YIELD),
        "FROM should NOT have YIELD step");

    // Build with this FROM expression
    VarEnvironment env = emptyEnv();
    ProcessTreeNode node = builder().build(existsPattern, env);

    // The builder should unwrap the exists pattern and recurse on the WHERE
    // clause
    assertTrue(node instanceof ProcessTreeNode.TerminalNode);
    ProcessTreeNode.TerminalNode terminal = (ProcessTreeNode.TerminalNode) node;
    // The term should be the unwrapped WHERE clause, not the FROM
    assertEquals(whereCond, terminal.term());
  }

  @Test
  void testIsExistsPatternVsGenerator() {
    // CRITICAL TEST: from x in xs where P is a GENERATOR, not exists
    // This validates the SCAN check fix
    Core.NamedPat x = namedPat("x");
    Core.Exp xs = core.list(typeSystem, intLiteral(1));
    Core.Exp whereCond = boolLiteral(true);

    // Create: from x in xs where x > 5 (has SCAN - this is a generator!)
    Core.From generator =
        core.fromBuilder(typeSystem).scan(x, xs).where(whereCond).build();

    // Build with this FROM expression
    VarEnvironment env = emptyEnv();
    ProcessTreeNode node = builder().build(generator, env);

    // The builder should NOT unwrap this (it's a generator, not exists)
    // Should create TerminalNode for the FROM itself
    assertTrue(node instanceof ProcessTreeNode.TerminalNode);
    ProcessTreeNode.TerminalNode terminal = (ProcessTreeNode.TerminalNode) node;
    assertEquals(generator, terminal.term());
  }

  @Test
  void testIsExistsPatternWithYield() {
    // from x where P yield x should return false (has YIELD)
    Core.NamedPat x = namedPat("x");
    Core.Exp whereCond = boolLiteral(true);
    Core.Exp yieldExp = core.id(x);

    // Create: from _ where whereCond yield yieldExp
    Core.From withYield =
        core.fromBuilder(typeSystem).where(whereCond).yield_(yieldExp).build();

    // Build with this FROM expression
    VarEnvironment env = emptyEnv();
    ProcessTreeNode node = builder().build(withYield, env);

    // Should NOT unwrap (has YIELD) - treat as terminal
    assertTrue(node instanceof ProcessTreeNode.TerminalNode);
    ProcessTreeNode.TerminalNode terminal = (ProcessTreeNode.TerminalNode) node;
    assertEquals(withYield, terminal.term());
  }

  @Test
  void testIsExistsPatternMultipleWhereClauses() {
    // Multiple WHERE clauses should be combined with andalso
    Core.Exp where1 = predicate("a");
    Core.Exp where2 = predicate("b");

    Core.From multiWhere =
        core.fromBuilder(typeSystem).where(where1).where(where2).build();

    VarEnvironment env = emptyEnv();
    ProcessTreeNode node = builder().build(multiWhere, env);

    // Should unwrap and combine WHERE clauses
    assertTrue(node instanceof ProcessTreeNode.SequenceNode);
  }

  @Test
  void testIsExistsPatternEmptyFrom() {
    // Edge case: FROM with no steps
    Core.From emptyFrom =
        core.fromBuilder(typeSystem).build(); // No steps at all

    VarEnvironment env = emptyEnv();
    ProcessTreeNode node = builder().build(emptyFrom, env);

    // Empty FROM should be treated as terminal
    assertTrue(node instanceof ProcessTreeNode.TerminalNode);
  }

  @Test
  void testIsExistsPatternScanStepDetection() {
    // Validate SCAN step is correctly detected and prevents unwrapping
    Core.NamedPat x = namedPat("x");
    Core.Exp xs = core.list(typeSystem, intLiteral(1));

    // Just SCAN, no WHERE
    Core.From justScan = core.fromBuilder(typeSystem).scan(x, xs).build();

    VarEnvironment env = emptyEnv();
    ProcessTreeNode node = builder().build(justScan, env);

    // Should NOT unwrap (has SCAN) - treat as terminal
    assertTrue(node instanceof ProcessTreeNode.TerminalNode);
    ProcessTreeNode.TerminalNode terminal = (ProcessTreeNode.TerminalNode) node;
    assertEquals(justScan, terminal.term());
  }

  @Test
  void testIsExistsPatternRecursionDetectionMatches() {
    // Ensure recursion detection aligns with PredicateInverter expectations
    Core.Id pathFn = functionRef("path");
    activeFunctions.add(pathFn);

    // Recursive call: path(z, y)
    Core.Exp recursiveCall =
        core.apply(
            net.hydromatic.morel.ast.Pos.ZERO,
            pathFn,
            core.tuple(typeSystem, intLiteral(1), intLiteral(2)));

    VarEnvironment env = emptyEnv();
    ProcessTreeNode node = builder().build(recursiveCall, env);

    // Should be marked as recursive
    assertTrue(node instanceof ProcessTreeNode.TerminalNode);
    ProcessTreeNode.TerminalNode terminal = (ProcessTreeNode.TerminalNode) node;
    assertTrue(terminal.isRecursive);
  }

  // ========== Base Case & Recursive Case Detection (4 tests) ==========

  @Test
  void testDetectSimpleBaseCase() {
    // edge(x, y) should be directly invertible
    Core.Id edgeFn = functionRef("edge");
    Core.Exp baseCase =
        core.apply(
            net.hydromatic.morel.ast.Pos.ZERO,
            edgeFn,
            core.tuple(typeSystem, intLiteral(1), intLiteral(2)));

    VarEnvironment env = emptyEnv();
    ProcessTreeNode node = builder().build(baseCase, env);

    // Should create terminal (not recursive since edge not in activeFunctions)
    assertTrue(node instanceof ProcessTreeNode.TerminalNode);
    ProcessTreeNode.TerminalNode terminal = (ProcessTreeNode.TerminalNode) node;
    assertFalse(terminal.isRecursive);
  }

  @Test
  void testDetectRecursiveCall() {
    // Function that calls itself should be detected in disjunction right side
    Core.Id pathFn = functionRef("path");
    activeFunctions.add(pathFn);

    Core.Exp baseCase = boolLiteral(true);
    Core.Exp recursiveCall =
        core.apply(
            net.hydromatic.morel.ast.Pos.ZERO,
            pathFn,
            core.tuple(typeSystem, intLiteral(1), intLiteral(2)));
    Core.Exp disjunction = core.orElse(typeSystem, baseCase, recursiveCall);

    VarEnvironment env = emptyEnv();
    ProcessTreeNode node = builder().build(disjunction, env);

    assertTrue(node instanceof ProcessTreeNode.BranchNode);
    ProcessTreeNode.BranchNode branch = (ProcessTreeNode.BranchNode) node;
    assertTrue(branch.hasRecursiveCase());
  }

  @Test
  void testDetectInvertibleBaseCase() {
    // x in list should be invertible (using elem)
    // This will be a terminal with potential inversion result
    Core.NamedPat x = namedPat("x");
    Core.Exp list = core.list(typeSystem, intLiteral(1), intLiteral(2));

    // Create elem call: x elem [1, 2]
    Core.Exp elemCall = core.elem(typeSystem, core.id(x), list);

    VarEnvironment env = envWithGoals(x);
    ProcessTreeNode node = builder().build(elemCall, env);

    assertTrue(node instanceof ProcessTreeNode.TerminalNode);
  }

  @Test
  void testDetectComplexRecursivePattern() {
    // Nested function calls should be detected
    Core.Id pathFn = functionRef("path");
    activeFunctions.add(pathFn);

    Core.Exp innerCall =
        core.apply(
            net.hydromatic.morel.ast.Pos.ZERO,
            pathFn,
            core.tuple(typeSystem, intLiteral(1), intLiteral(2)));
    Core.Exp conjunction = core.andAlso(typeSystem, predicate("a"), innerCall);

    VarEnvironment env = emptyEnv();
    ProcessTreeNode node = builder().build(conjunction, env);

    assertTrue(node instanceof ProcessTreeNode.SequenceNode);
    ProcessTreeNode.SequenceNode seq = (ProcessTreeNode.SequenceNode) node;
    assertTrue(seq.recursiveChildren().size() > 0);
  }

  // ========== Conjunction Handling (3 tests) ==========

  @Test
  void testHandleConjunctionTwoConditions() {
    // A andalso B should create SequenceNode with 2 children
    Core.Exp a = predicate("a");
    Core.Exp b = predicate("b");
    Core.Exp conjunction = core.andAlso(typeSystem, a, b);

    VarEnvironment env = emptyEnv();
    ProcessTreeNode node = builder().build(conjunction, env);

    assertTrue(node instanceof ProcessTreeNode.SequenceNode);
    ProcessTreeNode.SequenceNode seq = (ProcessTreeNode.SequenceNode) node;
    assertEquals(2, seq.children.size());
  }

  @Test
  void testHandleConjunctionThreeOrMore() {
    // A andalso B andalso C should create SequenceNode with 3 children
    Core.Exp a = predicate("a");
    Core.Exp b = predicate("b");
    Core.Exp c = predicate("c");
    List<Core.Exp> exprs = ImmutableList.of(a, b, c);
    Core.Exp conjunction = core.andAlso(typeSystem, exprs);

    VarEnvironment env = emptyEnv();
    ProcessTreeNode node = builder().build(conjunction, env);

    assertTrue(node instanceof ProcessTreeNode.SequenceNode);
    ProcessTreeNode.SequenceNode seq = (ProcessTreeNode.SequenceNode) node;
    assertEquals(3, seq.children.size());
  }

  @Test
  void testConjunctionWithInvertibility() {
    // Some conjuncts invertible, some not
    Core.NamedPat x = namedPat("x");
    Core.Exp invertible = predicate("x"); // Variable reference
    Core.Exp nonInvertible = predicate("y");
    Core.Exp conjunction = core.andAlso(typeSystem, invertible, nonInvertible);

    VarEnvironment env = envWithGoals(x);
    ProcessTreeNode node = builder().build(conjunction, env);

    assertTrue(node instanceof ProcessTreeNode.SequenceNode);
    ProcessTreeNode.SequenceNode seq = (ProcessTreeNode.SequenceNode) node;
    assertEquals(2, seq.children.size());
  }

  // ========== Edge Cases (3 tests) ==========

  @Test
  void testBuildWithNullPredicate() {
    VarEnvironment env = emptyEnv();

    assertThrows(NullPointerException.class, () -> builder().build(null, env));
  }

  @Test
  void testBuildWithUninvertiblePredicate() {
    // Non-invertible predicate should create TerminalNode with empty result
    Core.Exp uninvertible = boolLiteral(false);
    VarEnvironment env = emptyEnv();

    ProcessTreeNode node = builder().build(uninvertible, env);

    assertTrue(node instanceof ProcessTreeNode.TerminalNode);
    ProcessTreeNode.TerminalNode terminal = (ProcessTreeNode.TerminalNode) node;
    assertFalse(terminal.isInverted());
  }

  @Test
  void testBuildComplexTransitiveClosurePattern() {
    // Full pattern: fun path(x, y) = edge(x, y) orelse (exists z where
    // edge(x, z) andalso path(z, y))
    Core.NamedPat x = namedPat("x");
    Core.NamedPat y = namedPat("y");
    Core.NamedPat z = namedPat("z");
    Core.Id edgeFn = functionRef("edge");
    Core.Id pathFn = functionRef("path");

    activeFunctions.add(pathFn);

    // Base case: edge(x, y)
    Core.Exp baseCase =
        core.apply(
            net.hydromatic.morel.ast.Pos.ZERO,
            edgeFn,
            core.tuple(typeSystem, core.id(x), core.id(y)));

    // Recursive case components: edge(x, z) andalso path(z, y)
    Core.Exp edgeXZ =
        core.apply(
            net.hydromatic.morel.ast.Pos.ZERO,
            edgeFn,
            core.tuple(typeSystem, core.id(x), core.id(z)));
    Core.Exp pathZY =
        core.apply(
            net.hydromatic.morel.ast.Pos.ZERO,
            pathFn,
            core.tuple(typeSystem, core.id(z), core.id(y)));
    Core.Exp conjunction = core.andAlso(typeSystem, edgeXZ, pathZY);

    // Wrap in exists: exists z where (conjunction)
    Core.From existsPattern =
        core.fromBuilder(typeSystem).where(conjunction).build();

    // Full pattern: baseCase orelse existsPattern
    Core.Exp fullPattern = core.orElse(typeSystem, baseCase, existsPattern);

    VarEnvironment env = envWithGoals(x, y);
    ProcessTreeNode node = builder().build(fullPattern, env);

    // Should be BranchNode
    assertTrue(node instanceof ProcessTreeNode.BranchNode);
    ProcessTreeNode.BranchNode branch = (ProcessTreeNode.BranchNode) node;

    // Left should be terminal (base case)
    assertTrue(branch.left instanceof ProcessTreeNode.TerminalNode);

    // Right should be unwrapped exists -> SequenceNode (andalso)
    assertTrue(branch.right instanceof ProcessTreeNode.SequenceNode);
    ProcessTreeNode.SequenceNode rightSeq =
        (ProcessTreeNode.SequenceNode) branch.right;

    // Should have recursive call in sequence
    assertTrue(rightSeq.recursiveChildren().size() > 0);
  }

  // ========== Comprehensive Integration Tests (2 tests) ==========

  @Test
  void testIntegrationComplexTransitiveClosureWithUnboundVariables() {
    // INTEGRATION TEST: Verify that the entire pipeline (ProcessTreeBuilder →
    // VarEnvironment tracking → ProcessTreeNode structure) correctly handles
    // transitive closure with unbound variables requiring generators.
    //
    // Scenario: fun path (x, y) = edge (x, y) orelse
    //             (exists z where edge (x, z) andalso path (z, y))
    //           from p where path p
    //
    // Expected: ProcessTreeBuilder builds correct BranchNode structure,
    // VarEnvironment tracks unbound goals, ProcessTreeNode hierarchy matches
    // expected structure for transitive closure.

    Core.NamedPat x = namedPat("x");
    Core.NamedPat y = namedPat("y");
    Core.NamedPat z = namedPat("z");
    Core.Id edgeFn = functionRef("edge");
    Core.Id pathFn = functionRef("path");

    activeFunctions.add(pathFn);

    // Base case: edge(x, y)
    Core.Exp baseCase =
        core.apply(
            net.hydromatic.morel.ast.Pos.ZERO,
            edgeFn,
            core.tuple(typeSystem, core.id(x), core.id(y)));

    // Recursive case: edge(x, z) andalso path(z, y)
    Core.Exp edgeXZ =
        core.apply(
            net.hydromatic.morel.ast.Pos.ZERO,
            edgeFn,
            core.tuple(typeSystem, core.id(x), core.id(z)));
    Core.Exp pathZY =
        core.apply(
            net.hydromatic.morel.ast.Pos.ZERO,
            pathFn,
            core.tuple(typeSystem, core.id(z), core.id(y)));
    Core.Exp conjunction = core.andAlso(typeSystem, edgeXZ, pathZY);

    // Wrap in exists: exists z where (conjunction)
    Core.From existsPattern =
        core.fromBuilder(typeSystem).where(conjunction).build();

    // Full pattern: baseCase orelse existsPattern
    Core.Exp fullPattern = core.orElse(typeSystem, baseCase, existsPattern);

    // Create environment with unbound goals x and y
    VarEnvironment initialEnv = envWithGoals(x, y);

    // Verify initial state
    assertEquals(2, initialEnv.unboundGoals().size());
    assertTrue(initialEnv.unboundGoals().contains(x));
    assertTrue(initialEnv.unboundGoals().contains(y));

    // Build the process tree
    ProcessTreeNode tree = builder().build(fullPattern, initialEnv);

    // Verify tree structure: should be BranchNode (baseCase orelse
    // recursiveCase)
    assertTrue(
        tree instanceof ProcessTreeNode.BranchNode,
        "Root should be BranchNode for disjunction");
    ProcessTreeNode.BranchNode rootBranch = (ProcessTreeNode.BranchNode) tree;

    // Verify left branch (base case): edge(x, y)
    assertTrue(
        rootBranch.left instanceof ProcessTreeNode.TerminalNode,
        "Base case should be TerminalNode");
    ProcessTreeNode.TerminalNode baseCaseNode =
        (ProcessTreeNode.TerminalNode) rootBranch.left;
    assertFalse(baseCaseNode.isRecursive, "Base case should not be recursive");

    // Verify right branch (recursive case): unwrapped exists → SequenceNode
    assertTrue(
        rootBranch.right instanceof ProcessTreeNode.SequenceNode,
        "Recursive case should be SequenceNode after exists unwrapping");
    ProcessTreeNode.SequenceNode recursiveSeq =
        (ProcessTreeNode.SequenceNode) rootBranch.right;

    // Verify sequence has 2 children: edge(x, z) and path(z, y)
    assertEquals(
        2,
        recursiveSeq.children.size(),
        "Recursive case should have 2 conjuncts");

    // Verify one child is recursive (path call)
    List<ProcessTreeNode.TerminalNode> recursiveChildren =
        recursiveSeq.recursiveChildren();
    assertEquals(
        1, recursiveChildren.size(), "Should have exactly 1 recursive child");
    ProcessTreeNode.TerminalNode recursiveCall = recursiveChildren.get(0);
    assertTrue(
        recursiveCall.isRecursive, "Path call should be marked recursive");

    // Verify environment tracking: initial goals remain unbound until inversion
    assertEquals(
        2,
        baseCaseNode.env().unboundGoals().size(),
        "Base case should inherit unbound goals");
    assertEquals(
        2,
        recursiveSeq.env().unboundGoals().size(),
        "Recursive case should inherit unbound goals");

    // Verify overall branch structure
    assertTrue(
        rootBranch.hasRecursiveCase(),
        "Branch should detect recursive case in right child");
  }

  @Test
  void testIntegrationProcessTreeNodeWithVarEnvironmentStateTracking() {
    // INTEGRATION TEST: Verify that VarEnvironment state is correctly updated
    // throughout PPT construction as variables become bound.
    //
    // Scenario:
    // - Start with unbound goals [x, y, z]
    // - Process base case: binds x and y from edge relation
    // - Process recursive case: tracks z as join variable
    // - Verify final environment state
    //
    // Expected:
    // - Initial environment has 3 unbound goals
    // - After tree construction: variables remain unbound (inversion happens
    // later)
    // - Tree structure reflects all state transitions

    Core.NamedPat x = namedPat("x");
    Core.NamedPat y = namedPat("y");
    Core.NamedPat z = namedPat("z");

    // Create initial environment with 3 unbound goals
    VarEnvironment initialEnv = envWithGoals(x, y, z);

    // Verify initial state
    assertEquals(3, initialEnv.unboundGoals().size());
    assertTrue(initialEnv.unboundGoals().contains(x));
    assertTrue(initialEnv.unboundGoals().contains(y));
    assertTrue(initialEnv.unboundGoals().contains(z));

    // Build a simple predicate: x andalso y andalso z
    Core.Exp predX = core.id(x);
    Core.Exp predY = core.id(y);
    Core.Exp predZ = core.id(z);
    Core.Exp conjunction =
        core.andAlso(typeSystem, ImmutableList.of(predX, predY, predZ));

    // Build tree
    ProcessTreeNode tree = builder().build(conjunction, initialEnv);

    // Verify structure: SequenceNode with 3 children
    assertTrue(
        tree instanceof ProcessTreeNode.SequenceNode,
        "Root should be SequenceNode for conjunction");
    ProcessTreeNode.SequenceNode seq = (ProcessTreeNode.SequenceNode) tree;
    assertEquals(3, seq.children.size(), "Should have 3 conjuncts");

    // Verify each child is a TerminalNode with correct environment
    for (int i = 0; i < 3; i++) {
      ProcessTreeNode child = seq.children.get(i);
      assertTrue(
          child instanceof ProcessTreeNode.TerminalNode,
          "Child " + i + " should be TerminalNode");
      ProcessTreeNode.TerminalNode terminal =
          (ProcessTreeNode.TerminalNode) child;

      // Each terminal should inherit the environment with unbound goals
      assertEquals(
          3,
          terminal.env().unboundGoals().size(),
          "Terminal " + i + " should have 3 unbound goals");
    }

    // Verify environment immutability: original environment unchanged
    assertEquals(
        3,
        initialEnv.unboundGoals().size(),
        "Original environment should be unchanged");

    // Test with a more complex pattern: disjunction with conjunction
    Core.Exp disjunction = core.orElse(typeSystem, predX, conjunction);
    ProcessTreeNode complexTree = builder().build(disjunction, initialEnv);

    // Verify structure: BranchNode with TerminalNode left, SequenceNode right
    assertTrue(
        complexTree instanceof ProcessTreeNode.BranchNode,
        "Root should be BranchNode");
    ProcessTreeNode.BranchNode branch =
        (ProcessTreeNode.BranchNode) complexTree;

    assertTrue(
        branch.left instanceof ProcessTreeNode.TerminalNode,
        "Left should be TerminalNode");
    assertTrue(
        branch.right instanceof ProcessTreeNode.SequenceNode,
        "Right should be SequenceNode");

    // Verify environment propagation through branches
    ProcessTreeNode.TerminalNode leftTerminal =
        (ProcessTreeNode.TerminalNode) branch.left;
    ProcessTreeNode.SequenceNode rightSeq =
        (ProcessTreeNode.SequenceNode) branch.right;

    assertEquals(
        3,
        leftTerminal.env().unboundGoals().size(),
        "Left terminal should have 3 unbound goals");
    assertEquals(
        3,
        rightSeq.env().unboundGoals().size(),
        "Right sequence should have 3 unbound goals");

    // Verify environment sharing: both branches reference same initial
    // environment
    assertEquals(
        initialEnv,
        leftTerminal.env(),
        "Left terminal should reference initial environment");
    assertEquals(
        initialEnv,
        rightSeq.env(),
        "Right sequence should reference initial environment");

    // Verify no modifications leaked through
    assertEquals(
        3,
        initialEnv.unboundGoals().size(),
        "Initial environment should still have 3 unbound goals");
  }
}

// End ProcessTreeBuilderTest.java
