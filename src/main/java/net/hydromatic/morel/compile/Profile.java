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
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.ast.Visitor;
import net.hydromatic.morel.foreign.RelList;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.FnType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeVisitor;
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

  /**
   * Whether a leaf is data this engine already holds.
   *
   * <p>The <i>anchored</i> question, and the engine's to answer: a database
   * holds its own tables and nothing else. An engine that can be given data
   * would answer differently, and would take a leaf's colour from what consumes
   * it rather than from where the data is.
   */
  private final BiPredicate<Core.Exp, Environment> holds;

  /**
   * Whether the engine can call back into Morel for what it cannot evaluate
   * itself.
   *
   * <p>A deployment fact, and the third. In-process Calcite can: its plan may
   * hold a callback, and one reading a column at that. A database reached over
   * SQL cannot, and nor can a cluster the Morel runtime is not installed on.
   * Only where the answer is no does {@link #evaluates} decide anything.
   */
  private final boolean callsBack;

  /** The functions the engine has of its own. */
  private final Set<BuiltIn> functions;

  /**
   * Whether the engine can be given data it does not hold.
   *
   * <p>The second deployment fact. Spark Connect ships values from the
   * environment to the cluster; a database reached over SQL is given nothing
   * but the query. Where it can, a leaf's colour follows what consumes it
   * rather than where the data is, which is the difference between the
   * computation moving to the data and the data moving to the computation.
   *
   * <p>Data only. A function, or a value containing one, cannot cross in any
   * position, whatever the engine.
   */
  private final boolean acceptsData;

  private Profile(
      String name,
      Set<Op> ops,
      Set<Core.Rel.JoinType> joinTypes,
      boolean ordinals,
      boolean literalCounts,
      BiPredicate<Core.Exp, Environment> holds,
      boolean callsBack,
      Set<BuiltIn> functions,
      boolean acceptsData) {
    this.name = requireNonNull(name, "name");
    this.ops = ImmutableSet.copyOf(ops);
    this.joinTypes = ImmutableSet.copyOf(joinTypes);
    this.ordinals = ordinals;
    this.literalCounts = literalCounts;
    this.holds = requireNonNull(holds, "holds");
    this.callsBack = callsBack;
    this.functions = ImmutableSet.copyOf(functions);
    this.acceptsData = acceptsData;
  }

  /**
   * Returns whether a leaf may be run by this engine: data it holds already, or
   * -- where it can be given data -- any leaf that is data.
   */
  public boolean takes(Core.Exp leaf, Environment env) {
    if (holds.test(leaf, env)) {
      return true;
    }
    return acceptsData && isData(leaf.type);
  }

  /** Returns whether a type is data: no function anywhere within it. */
  private static boolean isData(Type type) {
    final boolean[] data = {true};
    type.accept(
        new TypeVisitor<Void>() {
          @Override
          public Void visit(FnType fnType) {
            data[0] = false;
            return null;
          }
        });
    return data[0];
  }

  /** Returns whether the engine can call back into Morel. */
  public boolean callsBack() {
    return callsBack;
  }

  /**
   * Returns whether the engine can evaluate an expression itself.
   *
   * <p>Asked only where the engine cannot call back. Every function in the
   * expression must be one the engine has; a name, a literal, a field and a
   * record of those it can always do. Anything else -- a function of the
   * query's own, a {@code case}, a {@code let} -- it cannot.
   */
  public boolean evaluates(Core.Exp exp) {
    final boolean[] ok = {true};
    exp.accept(
        new Visitor() {
          @Override
          protected void visit(Core.Literal literal) {
            if (literal.op == Op.FN_LITERAL
                && !functions.contains(literal.unwrap(BuiltIn.class))) {
              ok[0] = false;
            }
          }

          @Override
          protected void visit(Core.Fn fn) {
            ok[0] = false;
          }

          @Override
          protected void visit(Core.Case case_) {
            ok[0] = false;
          }

          @Override
          protected void visit(Core.Let let) {
            ok[0] = false;
          }
        });
    return ok[0];
  }

  /** Creates a profile; for a test that wants engines this one has not. */
  public static Profile create(
      String name,
      Set<Op> ops,
      Set<Core.Rel.JoinType> joinTypes,
      boolean ordinals,
      boolean literalCounts,
      BiPredicate<Core.Exp, Environment> holds,
      boolean callsBack,
      Set<BuiltIn> functions,
      boolean acceptsData) {
    return new Profile(
        name,
        ops,
        joinTypes,
        ordinals,
        literalCounts,
        holds,
        callsBack,
        functions,
        acceptsData);
  }

  /** Returns whether a leaf is data this engine already holds. */
  public boolean holds(Core.Exp leaf, Environment env) {
    return holds.test(leaf, env);
  }

  /** The functions Calcite has an exact equivalent for. */
  private static final Set<BuiltIn> CALCITE_FUNCTIONS =
      ImmutableSet.<BuiltIn>builder()
          .addAll(CalciteCompiler.UNARY_OPERATORS.keySet())
          .addAll(CalciteCompiler.BINARY_OPERATORS.keySet())
          .build();

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
          true,
          Profile::calciteHolds,
          true,
          CALCITE_FUNCTIONS,
          false);

  /**
   * The profile of a database reached over SQL: the same nodes as Calcite, but
   * it cannot call back into Morel, so what it runs it must be able to
   * evaluate.
   *
   * <p>Nothing executes a boundary that names it -- Morel runs one, as the
   * identity permits -- so coloring for it says where a database *could* take a
   * query, which is a question worth being able to ask.
   */
  public static final Profile SQL =
      new Profile(
          "sql",
          CALCITE.ops,
          CALCITE.joinTypes,
          false,
          true,
          Profile::calciteHolds,
          false,
          CALCITE_FUNCTIONS,
          false);

  /**
   * The profile of a Spark cluster with the Morel runtime on its executors.
   *
   * <p>Spark Connect ships values from the environment, so it can be given data
   * it does not hold, and a leaf that is data takes the colour of what consumes
   * it. The runtime being there, it can call back for what SQL cannot do, which
   * is why its function set does not decide anything.
   *
   * <p>Nothing executes a boundary naming it. Coloring for it says what a
   * cluster could be given, which is the question §13 is about.
   */
  public static final Profile SPARK =
      new Profile(
          "spark",
          CALCITE.ops,
          ImmutableSet.copyOf(Core.Rel.JoinType.values()),
          false,
          true,
          (leaf, env) -> false,
          true,
          CALCITE_FUNCTIONS,
          true);

  /**
   * Returns whether a leaf is one of Calcite's own relations: a field of a
   * foreign value whose fields are relations. A collection the query wrote is
   * not, and this profile cannot be given one.
   */
  private static boolean calciteHolds(Core.Exp exp, Environment env) {
    if (exp.op != Op.APPLY) {
      return false;
    }
    final Core.Apply apply = (Core.Apply) exp;
    if (apply.fn.op != Op.RECORD_SELECTOR || apply.arg.op != Op.ID) {
      return false;
    }
    final int slot = ((Core.RecordSelector) apply.fn).slot;
    final @Nullable Binding binding = env.getOpt(((Core.Id) apply.arg).idPat);
    if (binding == null || !(binding.value instanceof List)) {
      return false;
    }
    final List<?> fields = (List<?>) binding.value;
    return slot < fields.size() && fields.get(slot) instanceof RelList;
  }

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
