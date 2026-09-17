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
 * A rewrite of one node of a relational tree.
 *
 * <p>A rule is given a node and returns what replaces it, or null if the rule
 * does not apply there. The pattern -- which nodes the rule is about -- and the
 * guard -- what else must hold -- are both the rule's own tests on the node; a
 * rule that returns the node it was given has not fired.
 *
 * <p>What a rule returns must mean the same as what it was given, and the
 * driver ({@link RelRules}) holds it to the cheap half of that: the replacement
 * has the node's type, element and kind both, and is a well-formed tree. A rule
 * is applied to a node whose inputs have already been rewritten, so it may
 * assume that no rule applies below it.
 */
public interface RelRule {
  /** Returns the rule's name, for diagnostics. */
  String name();

  /** Returns what replaces {@code rel}, or null if this rule does not apply. */
  Core.@Nullable Exp apply(TypeSystem typeSystem, Core.Rel rel);
}

// End RelRule.java
