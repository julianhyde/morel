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
import java.util.List;
import java.util.Optional;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.type.PrimitiveType;
import org.junit.jupiter.api.Test;

/** Unit tests for ProcessTreeNode class hierarchy. */
class ProcessTreeNodeTest {

  // Helper to create a dummy Core.Exp for testing
  private Core.Exp dummyExp() {
    return core.intLiteral(java.math.BigDecimal.valueOf(42));
  }

  // Helper to create a dummy VarEnvironment for testing
  private VarEnvironment dummyEnv() {
    return VarEnvironment.initial(
        ImmutableList.<Core.NamedPat>of(), Environments.empty());
  }

  // Helper to create a dummy generator
  private PredicateInverter.Generator dummyGenerator() {
    Core.NamedPat pat = core.idPat(PrimitiveType.INT, "x", 0);
    return new PredicateInverter.Generator(
        pat,
        dummyExp(),
        net.hydromatic.morel.compile.Generator.Cardinality.FINITE,
        ImmutableList.<Core.Exp>of(),
        ImmutableList.<Core.NamedPat>of());
  }

  // Helper to create a successful inversion result (empty remainingFilters)
  private PredicateInverter.Result successfulResult() {
    return new PredicateInverter.Result(
        dummyGenerator(), ImmutableList.<Core.Exp>of());
  }

  // Helper to create a failed inversion result (has remainingFilters)
  private PredicateInverter.Result failedResult() {
    return new PredicateInverter.Result(
        dummyGenerator(),
        ImmutableList.<Core.Exp>of(dummyExp())); // Has remaining filters
  }

  // Terminal node creation tests (2 tests)

  @Test
  void testTerminalNodeOf() {
    Core.Exp term = dummyExp();
    VarEnvironment env = dummyEnv();
    Optional<PredicateInverter.Result> result = Optional.of(successfulResult());

    ProcessTreeNode.TerminalNode node =
        ProcessTreeNode.TerminalNode.of(term, env, result);

    assertNotNull(node);
    assertEquals(term, node.term());
    assertEquals(env, node.env());
    assertEquals(result, node.inversionResult);
    assertFalse(node.isRecursive);
  }

  @Test
  void testTerminalNodeRecursive() {
    Core.Exp term = dummyExp();
    VarEnvironment env = dummyEnv();

    ProcessTreeNode.TerminalNode node =
        ProcessTreeNode.TerminalNode.recursive(term, env);

    assertNotNull(node);
    assertEquals(term, node.term());
    assertEquals(env, node.env());
    assertEquals(Optional.empty(), node.inversionResult);
    assertTrue(node.isRecursive);
  }

  // Terminal node factories (2 tests)

  @Test
  void testTerminalNodeFactoryWithInversion() {
    Core.Exp term = dummyExp();
    VarEnvironment env = dummyEnv();
    Optional<PredicateInverter.Result> result = Optional.of(successfulResult());

    ProcessTreeNode.TerminalNode node =
        ProcessTreeNode.TerminalNode.of(term, env, result);

    assertTrue(node.isInverted());
    assertFalse(node.isRecursive);
  }

  @Test
  void testTerminalNodeFactoryRecursive() {
    Core.Exp term = dummyExp();
    VarEnvironment env = dummyEnv();

    ProcessTreeNode.TerminalNode node =
        ProcessTreeNode.TerminalNode.recursive(term, env);

    assertFalse(node.isInverted());
    assertTrue(node.isRecursive);
  }

  // Terminal node.isInverted() (1 test)

  @Test
  void testIsInvertedLogic() {
    Core.Exp term = dummyExp();
    VarEnvironment env = dummyEnv();

    // Successful inversion: inversionResult present with empty remainingFilters
    ProcessTreeNode.TerminalNode successful =
        ProcessTreeNode.TerminalNode.of(
            term, env, Optional.of(successfulResult()));
    assertTrue(successful.isInverted());

    // Failed inversion: inversionResult present but has remainingFilters
    ProcessTreeNode.TerminalNode failed =
        ProcessTreeNode.TerminalNode.of(term, env, Optional.of(failedResult()));
    assertFalse(failed.isInverted());

    // No inversion attempted
    ProcessTreeNode.TerminalNode noResult =
        ProcessTreeNode.TerminalNode.of(
            term, env, Optional.<PredicateInverter.Result>empty());
    assertFalse(noResult.isInverted());

    // Recursive nodes cannot be inverted
    ProcessTreeNode.TerminalNode recursive =
        ProcessTreeNode.TerminalNode.recursive(term, env);
    assertFalse(recursive.isInverted());
  }

  // Branch node structure (2 tests)

  @Test
  void testBranchNodeCreation() {
    Core.Exp term = dummyExp();
    VarEnvironment env = dummyEnv();
    ProcessTreeNode.TerminalNode left =
        ProcessTreeNode.TerminalNode.of(
            term, env, Optional.of(successfulResult()));
    ProcessTreeNode.TerminalNode right =
        ProcessTreeNode.TerminalNode.recursive(term, env);

    ProcessTreeNode.BranchNode branch =
        new ProcessTreeNode.BranchNode(term, env, left, right);

    assertNotNull(branch);
    assertEquals(term, branch.term());
    assertEquals(env, branch.env());
    assertEquals(left, branch.left);
    assertEquals(right, branch.right);
  }

  @Test
  void testBranchNodeNullChecks() {
    Core.Exp term = dummyExp();
    VarEnvironment env = dummyEnv();
    ProcessTreeNode.TerminalNode left =
        ProcessTreeNode.TerminalNode.of(
            term, env, Optional.<PredicateInverter.Result>empty());
    ProcessTreeNode.TerminalNode right =
        ProcessTreeNode.TerminalNode.of(
            term, env, Optional.<PredicateInverter.Result>empty());

    assertThrows(
        NullPointerException.class,
        () -> new ProcessTreeNode.BranchNode(null, env, left, right));
    assertThrows(
        NullPointerException.class,
        () -> new ProcessTreeNode.BranchNode(term, null, left, right));
    assertThrows(
        NullPointerException.class,
        () -> new ProcessTreeNode.BranchNode(term, env, null, right));
    assertThrows(
        NullPointerException.class,
        () -> new ProcessTreeNode.BranchNode(term, env, left, null));
  }

  // Branch node predicates (2 tests)

  @Test
  void testHasInvertibleBaseCase() {
    Core.Exp term = dummyExp();
    VarEnvironment env = dummyEnv();

    // True case: left is inverted terminal
    ProcessTreeNode.TerminalNode invertedLeft =
        ProcessTreeNode.TerminalNode.of(
            term, env, Optional.of(successfulResult()));
    ProcessTreeNode.TerminalNode right =
        ProcessTreeNode.TerminalNode.recursive(term, env);
    ProcessTreeNode.BranchNode branchTrue =
        new ProcessTreeNode.BranchNode(term, env, invertedLeft, right);
    assertTrue(branchTrue.hasInvertibleBaseCase());

    // False case: left is not inverted
    ProcessTreeNode.TerminalNode notInvertedLeft =
        ProcessTreeNode.TerminalNode.of(
            term, env, Optional.<PredicateInverter.Result>empty());
    ProcessTreeNode.BranchNode branchFalse =
        new ProcessTreeNode.BranchNode(term, env, notInvertedLeft, right);
    assertFalse(branchFalse.hasInvertibleBaseCase());
  }

  @Test
  void testHasRecursiveCase() {
    Core.Exp term = dummyExp();
    VarEnvironment env = dummyEnv();
    ProcessTreeNode.TerminalNode left =
        ProcessTreeNode.TerminalNode.of(
            term, env, Optional.of(successfulResult()));

    // True case: right contains recursive call
    ProcessTreeNode.TerminalNode recursiveRight =
        ProcessTreeNode.TerminalNode.recursive(term, env);
    ProcessTreeNode.BranchNode branchTrue =
        new ProcessTreeNode.BranchNode(term, env, left, recursiveRight);
    assertTrue(branchTrue.hasRecursiveCase());

    // False case: right has no recursive call
    ProcessTreeNode.TerminalNode nonRecursiveRight =
        ProcessTreeNode.TerminalNode.of(
            term, env, Optional.<PredicateInverter.Result>empty());
    ProcessTreeNode.BranchNode branchFalse =
        new ProcessTreeNode.BranchNode(term, env, left, nonRecursiveRight);
    assertFalse(branchFalse.hasRecursiveCase());

    // True case: recursive call nested in sequence
    ProcessTreeNode.TerminalNode recursiveTerm =
        ProcessTreeNode.TerminalNode.recursive(term, env);
    ProcessTreeNode.TerminalNode nonRecursiveTerm =
        ProcessTreeNode.TerminalNode.of(
            term, env, Optional.<PredicateInverter.Result>empty());
    ProcessTreeNode.SequenceNode sequence =
        new ProcessTreeNode.SequenceNode(
            term,
            env,
            ImmutableList.<ProcessTreeNode>of(nonRecursiveTerm, recursiveTerm));
    ProcessTreeNode.BranchNode branchWithSequence =
        new ProcessTreeNode.BranchNode(term, env, left, sequence);
    assertTrue(branchWithSequence.hasRecursiveCase());
  }

  // Sequence node children (2 tests)

  @Test
  void testSequenceNodeCreation() {
    Core.Exp term = dummyExp();
    VarEnvironment env = dummyEnv();
    ProcessTreeNode.TerminalNode child1 =
        ProcessTreeNode.TerminalNode.of(
            term, env, Optional.<PredicateInverter.Result>empty());
    ProcessTreeNode.TerminalNode child2 =
        ProcessTreeNode.TerminalNode.recursive(term, env);
    ImmutableList<ProcessTreeNode> children =
        ImmutableList.<ProcessTreeNode>of(child1, child2);

    ProcessTreeNode.SequenceNode sequence =
        new ProcessTreeNode.SequenceNode(term, env, children);

    assertNotNull(sequence);
    assertEquals(term, sequence.term());
    assertEquals(env, sequence.env());
    assertEquals(2, sequence.children.size());
    assertEquals(child1, sequence.children.get(0));
    assertEquals(child2, sequence.children.get(1));
  }

  @Test
  void testSequenceNodeRequiresTwoChildren() {
    Core.Exp term = dummyExp();
    VarEnvironment env = dummyEnv();
    ProcessTreeNode.TerminalNode child =
        ProcessTreeNode.TerminalNode.of(
            term, env, Optional.<PredicateInverter.Result>empty());

    // Single child should fail
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ProcessTreeNode.SequenceNode(
                term, env, ImmutableList.<ProcessTreeNode>of(child)));

    // Empty list should fail
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ProcessTreeNode.SequenceNode(
                term, env, ImmutableList.<ProcessTreeNode>of()));

    // Two children should succeed
    assertDoesNotThrow(
        () ->
            new ProcessTreeNode.SequenceNode(
                term, env, ImmutableList.<ProcessTreeNode>of(child, child)));
  }

  // Sequence node filtering methods (bonus coverage)

  @Test
  void testSequenceNodeRecursiveChildren() {
    Core.Exp term = dummyExp();
    VarEnvironment env = dummyEnv();
    ProcessTreeNode.TerminalNode nonRec1 =
        ProcessTreeNode.TerminalNode.of(
            term, env, Optional.<PredicateInverter.Result>empty());
    ProcessTreeNode.TerminalNode rec1 =
        ProcessTreeNode.TerminalNode.recursive(term, env);
    ProcessTreeNode.TerminalNode rec2 =
        ProcessTreeNode.TerminalNode.recursive(term, env);

    ProcessTreeNode.SequenceNode sequence =
        new ProcessTreeNode.SequenceNode(
            term, env, ImmutableList.<ProcessTreeNode>of(nonRec1, rec1, rec2));

    List<ProcessTreeNode.TerminalNode> recursiveChildren =
        sequence.recursiveChildren();
    assertEquals(2, recursiveChildren.size());
    assertTrue(recursiveChildren.contains(rec1));
    assertTrue(recursiveChildren.contains(rec2));
  }

  @Test
  void testSequenceNodeNonRecursiveChildren() {
    Core.Exp term = dummyExp();
    VarEnvironment env = dummyEnv();
    ProcessTreeNode.TerminalNode nonRec1 =
        ProcessTreeNode.TerminalNode.of(
            term, env, Optional.<PredicateInverter.Result>empty());
    ProcessTreeNode.TerminalNode nonRec2 =
        ProcessTreeNode.TerminalNode.of(
            term, env, Optional.of(successfulResult()));
    ProcessTreeNode.TerminalNode rec1 =
        ProcessTreeNode.TerminalNode.recursive(term, env);

    ProcessTreeNode.SequenceNode sequence =
        new ProcessTreeNode.SequenceNode(
            term,
            env,
            ImmutableList.<ProcessTreeNode>of(nonRec1, rec1, nonRec2));

    List<ProcessTreeNode> nonRecursiveChildren =
        sequence.nonRecursiveChildren();
    assertEquals(2, nonRecursiveChildren.size());
    assertTrue(nonRecursiveChildren.contains(nonRec1));
    assertTrue(nonRecursiveChildren.contains(nonRec2));
  }

  // General predicates: isTerminal, isRecursiveCall (2 tests)

  @Test
  void testIsTerminalPredicate() {
    Core.Exp term = dummyExp();
    VarEnvironment env = dummyEnv();

    ProcessTreeNode.TerminalNode terminal =
        ProcessTreeNode.TerminalNode.of(
            term, env, Optional.<PredicateInverter.Result>empty());
    assertTrue(terminal.isTerminal());

    ProcessTreeNode.BranchNode branch =
        new ProcessTreeNode.BranchNode(term, env, terminal, terminal);
    assertFalse(branch.isTerminal());

    ProcessTreeNode.SequenceNode sequence =
        new ProcessTreeNode.SequenceNode(
            term, env, ImmutableList.<ProcessTreeNode>of(terminal, terminal));
    assertFalse(sequence.isTerminal());
  }

  @Test
  void testIsRecursiveCallPredicate() {
    Core.Exp term = dummyExp();
    VarEnvironment env = dummyEnv();

    ProcessTreeNode.TerminalNode recursiveNode =
        ProcessTreeNode.TerminalNode.recursive(term, env);
    assertTrue(recursiveNode.isRecursiveCall());

    ProcessTreeNode.TerminalNode nonRecursiveNode =
        ProcessTreeNode.TerminalNode.of(
            term, env, Optional.<PredicateInverter.Result>empty());
    assertFalse(nonRecursiveNode.isRecursiveCall());

    ProcessTreeNode.BranchNode branch =
        new ProcessTreeNode.BranchNode(
            term, env, recursiveNode, nonRecursiveNode);
    assertFalse(branch.isRecursiveCall());

    ProcessTreeNode.SequenceNode sequence =
        new ProcessTreeNode.SequenceNode(
            term,
            env,
            ImmutableList.<ProcessTreeNode>of(recursiveNode, nonRecursiveNode));
    assertFalse(sequence.isRecursiveCall());
  }
}
