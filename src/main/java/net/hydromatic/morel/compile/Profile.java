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
import java.util.Set;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Op;
import org.jspecify.annotations.Nullable;

/**
 * What an engine can be asked to run.
 *
 * <p>A description of a deployment, not only of an engine: what may be pushed
 * depends on where the engine is and what is installed beside it, and that is a
 * fact about a cluster rather than about SQL.
 *
 * <p>It is an <b>under-approximation</b>, and whoever reads it trusts it
 * absolutely. An engine that refuses what its profile permits is a bug; a
 * profile that omits what its engine could do costs a pushdown and nothing
 * else. So a profile errs towards saying no.
 *
 * <p>{@link #permits} is a pure function of a profile and one node -- it asks
 * nothing of the node's inputs, and nothing of where the node sits -- so a
 * caller may ask about the nodes of a tree in any order.
 *
 * <p>Two things an engine decides are not here, because neither is a fact about
 * a node alone. Whether it can evaluate an <em>expression</em> is one: the
 * translation falls back to a callback where it cannot, and that is a walk of
 * the expression rather than a question about the node. Whether it orders a
 * <em>type</em> as Morel does is the other, which decides whether {@code min}
 * and {@code max} may be pushed; answering it needs the engine's mapping of
 * Morel's types, which the engine's translator has and a profile does not yet.
 */
public class Profile {
  /** The engine's name, as a {@link Core.Boundary} names it. */
  public final String name;

  /** The node kinds the engine can run. */
  private final Set<Op> ops;

  /** The kinds of join it can run. */
  private final Set<Core.Rel.JoinType> joinTypes;

  /** Whether a node it runs may bind an ordinal. */
  private final boolean ordinals;

  /** Whether the count of a {@code skip} or {@code take} must be a literal. */
  private final boolean literalCounts;

  private Profile(
      String name,
      Set<Op> ops,
      Set<Core.Rel.JoinType> joinTypes,
      boolean ordinals,
      boolean literalCounts) {
    this.name = requireNonNull(name, "name");
    this.ops = ImmutableSet.copyOf(ops);
    this.joinTypes = ImmutableSet.copyOf(joinTypes);
    this.ordinals = ordinals;
    this.literalCounts = literalCounts;
  }

  /**
   * The profile of the Calcite adapter, as the translation has always had it,
   * with the decisions in one place rather than spread through the walk.
   *
   * <p>It runs every node but {@code ifEmpty}, which has no SQL spelling, and a
   * {@code boundary}, which it runs only where the boundary names it. No node
   * may bind an ordinal, because SQL has no row number that a filter can read
   * as Morel's does. A join must be inner. And the count of a {@code skip} or
   * {@code take} must be a literal, because SQL's {@code OFFSET} is not an
   * expression over the row.
   */
  public static final Profile CALCITE =
      new Profile(
          "calcite",
          ImmutableSet.of(
              Op.FILTER,
              Op.PROJECT,
              Op.SORT,
              Op.GROUP,
              Op.JOIN,
              Op.SKIP,
              Op.TAKE,
              Op.UNORDER,
              Op.BOUNDARY,
              Op.UNION,
              Op.INTERSECT,
              Op.EXCEPT),
          ImmutableSet.of(Core.Rel.JoinType.INNER),
          false,
          true);

  /** Returns whether this engine can run a node, given the node alone. */
  public boolean permits(Core.Rel rel) {
    if (!ops.contains(rel.op)) {
      return false;
    }
    if (!ordinals && ordinalOf(rel) != null) {
      return false;
    }
    switch (rel.op) {
      case JOIN:
        return joinTypes.contains(((Core.Join) rel).joinType);
      case SKIP:
        return !literalCounts || ((Core.Skip) rel).count.op == Op.INT_LITERAL;
      case TAKE:
        return !literalCounts || ((Core.Take) rel).count.op == Op.INT_LITERAL;
      case BOUNDARY:
        return ((Core.Boundary) rel).engine.equals(name);
      default:
        return true;
    }
  }

  /** Returns the ordinal a node binds, or null if it binds none. */
  private static Core.@Nullable IdPat ordinalOf(Core.Rel rel) {
    if (rel instanceof Core.RowRel) {
      return ((Core.RowRel) rel).ordinal;
    }
    if (rel instanceof Core.Join) {
      return ((Core.Join) rel).ordinal;
    }
    return null;
  }
}

// End Profile.java
