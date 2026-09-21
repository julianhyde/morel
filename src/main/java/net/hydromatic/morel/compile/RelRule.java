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

import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.type.TypeSystem;
import org.jspecify.annotations.Nullable;

/**
 * A rewrite of a relational tree.
 *
 * <p>A rule is given a node and returns what replaces it, or null if the rule
 * does not apply there. The pattern -- which nodes the rule is about -- and the
 * guard -- what else must hold -- are both the rule's own tests on the node; a
 * rule that returns the node it was given has not fired.
 *
 * <p>There are two kinds. A <b>node rule</b> is applied at every node, after
 * the node's inputs have been rewritten, so it may assume that no rule applies
 * below it. A <b>whole-tree rule</b> ({@link #wholeTree()}) is applied at the
 * root of a tree before anything under it is rewritten: it is for a rewrite
 * that needs the tree as it was built, such as grounding, which reads the
 * constraints wherever they are and must not find an operator pushed between
 * them and the leaf they bound.
 *
 * <p>What a rule returns must mean the same as what it was given, and the
 * driver ({@link RelRules}) holds it to the cheap half of that: the replacement
 * has the node's type, element and kind both, and is a well-formed tree. The
 * one relaxation is where nothing reads the rows ({@link Context#rowsUsed}):
 * there the element may change, and only the kind is held.
 */
public interface RelRule {
  /** Returns the rule's name, for diagnostics. */
  String name();

  /** Returns what replaces {@code rel}, or null if this rule does not apply. */
  Core.@Nullable Exp apply(Context cx, Core.Rel rel);

  /**
   * Returns whether this rule is applied at a tree's root, before the tree is
   * rewritten below, rather than at each node after.
   */
  default boolean wholeTree() {
    return false;
  }

  /** Where a tree stands when a rule is applied to it. */
  interface Context {
    TypeSystem typeSystem();

    /** The environment the tree is in; what its free names are bound to. */
    Environment env();

    /**
     * Whether the tree's rows are read. Under {@code exists} or {@code empty}
     * they are only counted, and a rule may then change what a row is, but not
     * how many there are.
     */
    boolean rowsUsed();

    /**
     * Whether the tree is in the body of a recursive function. Such a body is
     * not a query to run here: it is expanded when the function is called, or
     * recognized as a transitive closure.
     */
    boolean inRecursiveFunction();
  }
}

// End RelRule.java
