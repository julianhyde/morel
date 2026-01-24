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

  @BeforeEach
  void setUp() {
    typeSystem = new TypeSystem();
    env = Environments.empty();
    activeFunctions = new HashSet<>();
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
    Core.Exp cond1 = boolLiteral(true);
    Core.Exp cond2 = boolLiteral(false);
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
    Core.Exp a = boolLiteral(true);
    Core.Exp b = boolLiteral(false);
    Core.Exp c = boolLiteral(true);
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
    Core.Exp where1 = boolLiteral(true);
    Core.Exp where2 = boolLiteral(false);

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
    Core.NamedPat pathFn = namedPat("path");
    activeFunctions.add(core.id(pathFn));

    // Recursive call: path(z, y)
    Core.Exp recursiveCall =
        core.apply(
            net.hydromatic.morel.ast.Pos.ZERO,
            core.id(pathFn),
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
    Core.NamedPat edgeFn = namedPat("edge");
    Core.Exp baseCase =
        core.apply(
            net.hydromatic.morel.ast.Pos.ZERO,
            core.id(edgeFn),
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
    Core.NamedPat pathFn = namedPat("path");
    activeFunctions.add(core.id(pathFn));

    Core.Exp baseCase = boolLiteral(true);
    Core.Exp recursiveCall =
        core.apply(
            net.hydromatic.morel.ast.Pos.ZERO, core.id(pathFn), intLiteral(1));
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
    Core.Exp elemCall =
        core.apply(
            net.hydromatic.morel.ast.Pos.ZERO,
            core.apply(
                net.hydromatic.morel.ast.Pos.ZERO,
                core.functionLiteral(typeSystem, BuiltIn.OP_ELEM),
                core.id(x)),
            list);

    VarEnvironment env = envWithGoals(x);
    ProcessTreeNode node = builder().build(elemCall, env);

    assertTrue(node instanceof ProcessTreeNode.TerminalNode);
  }

  @Test
  void testDetectComplexRecursivePattern() {
    // Nested function calls should be detected
    Core.NamedPat pathFn = namedPat("path");
    activeFunctions.add(core.id(pathFn));

    Core.Exp innerCall =
        core.apply(
            net.hydromatic.morel.ast.Pos.ZERO, core.id(pathFn), intLiteral(1));
    Core.Exp conjunction =
        core.andAlso(typeSystem, boolLiteral(true), innerCall);

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
    Core.Exp a = boolLiteral(true);
    Core.Exp b = boolLiteral(false);
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
    Core.Exp a = boolLiteral(true);
    Core.Exp b = boolLiteral(false);
    Core.Exp c = boolLiteral(true);
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
    Core.Exp invertible = boolLiteral(true); // Simple literal
    Core.Exp nonInvertible = boolLiteral(false);
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
    Core.NamedPat edgeFn = namedPat("edge");
    Core.NamedPat pathFn = namedPat("path");

    activeFunctions.add(core.id(pathFn));

    // Base case: edge(x, y)
    Core.Exp baseCase =
        core.apply(
            net.hydromatic.morel.ast.Pos.ZERO,
            core.id(edgeFn),
            core.tuple(typeSystem, core.id(x), core.id(y)));

    // Recursive case components: edge(x, z) andalso path(z, y)
    Core.Exp edgeXZ =
        core.apply(
            net.hydromatic.morel.ast.Pos.ZERO,
            core.id(edgeFn),
            core.tuple(typeSystem, core.id(x), core.id(z)));
    Core.Exp pathZY =
        core.apply(
            net.hydromatic.morel.ast.Pos.ZERO,
            core.id(pathFn),
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
}

// End ProcessTreeBuilderTest.java
