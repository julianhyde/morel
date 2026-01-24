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
import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.hydromatic.morel.ast.Core;

/**
 * A node in the Perfect Process Tree (PPT) for predicate inversion.
 *
 * <p>The PPT represents all possible computation paths when evaluating a
 * predicate with partially-specified input. This is the core data structure for
 * implementing the Universal Resolving Algorithm (URA).
 *
 * <p>Node types:
 *
 * <ul>
 *   <li>{@link TerminalNode} - leaf node where inversion is attempted
 *   <li>{@link BranchNode} - fork point (from orelse)
 *   <li>{@link SequenceNode} - conjunction of constraints (from andalso)
 * </ul>
 *
 * <p><b>Exists Pattern Handling</b>: The pattern {@code exists x where P}
 * compiles to {@code Core.From} expressions. During PPT construction, the
 * exists wrapper is unwrapped and the body P is analyzed directly. This is
 * semantically correct because for generation purposes, we don't check
 * existence but rather generate all values satisfying P.
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
    return this instanceof TerminalNode && ((TerminalNode) this).isRecursive;
  }

  /**
   * Terminal node in the PPT where inversion is attempted.
   *
   * <p>A terminal node represents an expression that can potentially be
   * inverted directly (e.g., elem, function call, literal).
   *
   * <p><b>Invariants</b>:
   *
   * <ul>
   *   <li>If {@code isRecursive} is true, {@code inversionResult} should be
   *       empty
   *   <li>A node is "inverted" iff {@code inversionResult} is present with
   *       empty remainingFilters
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
   * <p>In the PPT, orelse creates a fork: both branches represent valid
   * computation paths, and the final result is the union of solutions from both
   * branches.
   *
   * <p><b>Invariants</b>:
   *
   * <ul>
   *   <li>Left branch typically represents the base case (directly invertible)
   *   <li>Right branch typically represents the recursive case
   *   <li>Both branches are evaluated independently (fork point)
   *   <li>Final result is the union of solutions from both branches
   * </ul>
   *
   * <p><b>Transitive Closure Pattern</b>: For predicates like {@code edge(x,y)
   * orelse (exists z where ...)}, the left branch should be an inverted
   * TerminalNode and the right branch should contain a recursive call within a
   * SequenceNode.
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
      return left instanceof TerminalNode && ((TerminalNode) left).isInverted();
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
      return String.format("BranchNode{left=%s, right=%s}", left, right);
    }
  }

  /**
   * Sequence node representing an andalso (conjunction).
   *
   * <p>In the PPT, andalso creates a sequence of constraints that must all be
   * satisfied. This is used for join detection and predicate ordering.
   *
   * <p><b>Invariants</b>:
   *
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
        Core.Exp term, VarEnvironment env, List<ProcessTreeNode> children) {
      this.term = requireNonNull(term);
      this.env = requireNonNull(env);
      this.children = ImmutableList.copyOf(children);
      checkArgument(
          this.children.size() >= 2,
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
      return String.format("SequenceNode{children=%s}", children);
    }
  }
}
