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
package net.hydromatic.morel.spark;

import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.JoinType;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.ast.Visitor;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.foreign.SparkBackend;
import net.hydromatic.morel.type.RecordLikeType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import org.apache.spark.connect.proto.Aggregate;
import org.apache.spark.connect.proto.Expression;
import org.apache.spark.connect.proto.Filter;
import org.apache.spark.connect.proto.Join;
import org.apache.spark.connect.proto.Limit;
import org.apache.spark.connect.proto.LocalRelation;
import org.apache.spark.connect.proto.Plan;
import org.apache.spark.connect.proto.Project;
import org.apache.spark.connect.proto.Read;
import org.apache.spark.connect.proto.Relation;
import org.apache.spark.connect.proto.RelationCommon;
import org.apache.spark.connect.proto.Sort;
import org.apache.spark.connect.proto.SubqueryAlias;
import org.apache.spark.connect.proto.SubqueryExpression;
import org.apache.spark.connect.proto.WithRelations;
import org.jspecify.annotations.Nullable;

/**
 * Translates a Morel query, as the core tree holds it, to a Spark plan.
 *
 * <p>Connect's {@code Relation} is a conventional operator tree, and so is the
 * core tree since #449, so the translation is close to one node for one node.
 * The hinge is the row variable: a Morel query binds one (the {@code r} of
 * {@code from r in ...}) and refers to a column as a field of it, which is what
 * Spark calls a subquery alias and a qualified attribute. So the alias goes on
 * the scanned collection, and {@code #b r} becomes {@code r.b}.
 *
 * <p>Plan ids are assigned in the order the text prints them -- the outermost
 * node is 0 -- so that a plan's text depends on the query and nothing else.
 */
public class SparkTranslator {
  private final TypeSystem typeSystem;
  private final SparkFunctionTable functions = SparkFunctionTable.instance();
  private final List<Integer> localRowCounts = new ArrayList<>();

  /**
   * The relations that expressions in the query refer to, which Spark carries
   * beside the root rather than inside it. A correlated subquery is one.
   */
  private final List<Relation> references = new ArrayList<>();

  private int nextPlanId;

  private SparkTranslator(TypeSystem typeSystem) {
    this.typeSystem = typeSystem;
  }

  /** Translates a query to a plan. */
  public static SparkPlan translate(TypeSystem typeSystem, Core.Exp query) {
    final SparkTranslator translator = new SparkTranslator(typeSystem);
    final Relation root = translator.rel(query);
    return new SparkPlan(
        Plan.newBuilder().setRoot(root).build(), translator.localRowCounts);
  }

  /**
   * Translates a relational node, wrapping it where its own expressions refer
   * to relations: Spark keeps those beside the node, not inside it, and the
   * wrapper is what holds them. The wrapper takes the plan id above the node's,
   * which is the order the text prints them.
   */
  private Relation rel(Core.Exp exp) {
    if (!refersToRelation(exp)) {
      return relNode(exp, nextPlanId++);
    }
    final int wrapperId = nextPlanId++;
    final List<Relation> enclosing = new ArrayList<>(references);
    references.clear();
    final Relation node = relNode(exp, nextPlanId++);
    final List<Relation> own = new ArrayList<>(references);
    references.clear();
    references.addAll(enclosing);
    return Relation.newBuilder()
        .setCommon(RelationCommon.newBuilder().setPlanId(wrapperId))
        .setWithRelations(
            WithRelations.newBuilder().setRoot(node).addAllReferences(own))
        .build();
  }

  /** Returns whether a node's own expressions ask a question of a query. */
  private static boolean refersToRelation(Core.Exp exp) {
    if (exp instanceof Core.Filter) {
      return containsSubquery(((Core.Filter) exp).condition);
    }
    if (exp instanceof Core.Project) {
      return containsSubquery(((Core.Project) exp).exp);
    }
    return false;
  }

  /** Returns whether an expression asks a question of a query. */
  private static boolean containsSubquery(Core.Exp exp) {
    final boolean[] found = {false};
    exp.accept(
        new Visitor() {
          @Override
          protected void visit(Core.Apply apply) {
            if (apply.isCallTo(BuiltIn.RELATIONAL_NON_EMPTY)) {
              found[0] = true;
            }
            super.visit(apply);
          }
        });
    return found[0];
  }

  /** Translates one relational node, under a given plan id. */
  private Relation relNode(Core.Exp exp, int planId) {
    final Relation.Builder b = Relation.newBuilder();
    switch (exp.op) {
      case FILTER:
        final Core.Filter filter = (Core.Filter) exp;
        b.setFilter(
            Filter.newBuilder()
                .setInput(input(filter.input, filter.row))
                .setCondition(exp(filter.condition, parts(filter.input))));
        break;

      case PROJECT:
        final Core.Project project = (Core.Project) exp;
        b.setProject(
            Project.newBuilder()
                .setInput(input(project.input, project.row))
                .addAllExpressions(
                    projections(
                        project.exp, parts(project.input), project.type)));
        break;

      case GROUP:
        final Core.Group group = (Core.Group) exp;
        final Aggregate.Builder aggregate =
            Aggregate.newBuilder()
                .setInput(input(group.input, group.row))
                .setGroupType(Aggregate.GroupType.GROUP_TYPE_GROUPBY);
        final List<String> groupParts = parts(group.input);
        group.keys.forEach(
            (name, key) ->
                aggregate.addGroupingExpressions(
                    named(exp(key, groupParts), name)));
        group.aggregates.forEach(
            (name, agg) ->
                aggregate.addAggregateExpressions(
                    named(aggregate(agg, groupParts), name)));
        b.setAggregate(aggregate);
        break;

      case SORT:
        final Core.Sort sort = (Core.Sort) exp;
        final Sort.Builder sortBuilder =
            Sort.newBuilder().setInput(input(sort.input, sort.row));
        sortOrder(sort.exp, false, parts(sort.input), sortBuilder::addOrder);
        // A query orders its whole result, not each partition of it.
        b.setSort(sortBuilder.setIsGlobal(true));
        break;

      case TAKE:
        final Core.Take take = (Core.Take) exp;
        if (take.count.op != Op.INT_LITERAL) {
          throw unsupported("take of " + take.count.op);
        }
        b.setLimit(
            Limit.newBuilder()
                .setInput(rel(take.input))
                .setLimit(
                    ((Number) ((Core.Literal) take.count).value).intValue()));
        break;

      case JOIN:
        final Core.Join join = (Core.Join) exp;
        b.setJoin(
            Join.newBuilder()
                .setLeft(input(join.left, join.leftRow))
                .setRight(input(join.right, join.rightRow))
                .setJoinCondition(exp(join.condition, ImmutableList.of()))
                .setJoinType(joinType(join.joinType)));
        break;

      default:
        throw unsupported("relational operator " + exp.op);
    }
    return b.setCommon(RelationCommon.newBuilder().setPlanId(planId)).build();
  }

  /**
   * Translates a node's input. A relational input translates as itself;
   * anything else is the collection the query scans, and takes the row
   * variable's name as its alias.
   */
  private Relation input(Core.Exp exp, Core.IdPat row) {
    if (exp instanceof Core.Rel) {
      return rel(exp);
    }
    final int planId = nextPlanId++;
    return Relation.newBuilder()
        .setCommon(RelationCommon.newBuilder().setPlanId(planId))
        .setSubqueryAlias(
            SubqueryAlias.newBuilder().setInput(leaf(exp)).setAlias(row.name))
        .build();
  }

  /** Translates the collection a query scans. */
  private Relation leaf(Core.Exp exp) {
    final int planId = nextPlanId++;
    if (exp.isCallTo(BuiltIn.Z_LIST)) {
      final List<Core.Exp> rows = rows(exp);
      localRowCounts.add(rows.size());
      return Relation.newBuilder()
          .setCommon(RelationCommon.newBuilder().setPlanId(planId))
          .setLocalRelation(
              LocalRelation.newBuilder()
                  .setSchema(SparkSchema.schemaJson(elementType(exp.type))))
          .build();
    }
    if (tableName(exp) != null) {
      return Relation.newBuilder()
          .setCommon(RelationCommon.newBuilder().setPlanId(planId))
          .setRead(
              Read.newBuilder()
                  .setNamedTable(
                      Read.NamedTable.newBuilder()
                          .setUnparsedIdentifier(tableName(exp))))
          .build();
    }
    throw unsupported("collection " + exp.op);
  }

  /**
   * Returns the name of the table a collection reads, or null if it is not a
   * table. A table is a field of a foreign value, so {@code scott.emps} is a
   * record selector applied to a name, and Spark wants the two joined by a dot,
   * as the user wrote them.
   */
  private static @Nullable String tableName(Core.Exp exp) {
    if (exp.op == Op.APPLY) {
      final Core.Apply apply = (Core.Apply) exp;
      if (apply.fn.op == Op.RECORD_SELECTOR && apply.arg.op == Op.ID) {
        return ((Core.Id) apply.arg).idPat.name + "." + selectedName(apply);
      }
    }
    return null;
  }

  /** Translates a join's kind. */
  private static Join.JoinType joinType(JoinType joinType) {
    switch (joinType) {
      case INNER:
        return Join.JoinType.JOIN_TYPE_INNER;
      case LEFT:
        return Join.JoinType.JOIN_TYPE_LEFT_OUTER;
      case RIGHT:
        return Join.JoinType.JOIN_TYPE_RIGHT_OUTER;
      case FULL:
        return Join.JoinType.JOIN_TYPE_FULL_OUTER;
      default:
        throw unsupported("join kind " + joinType);
    }
  }

  /**
   * Returns the rows of an inline collection. A list is a call to the internal
   * list constructor over a tuple of its elements.
   */
  private static List<Core.Exp> rows(Core.Exp exp) {
    final Core.Exp arg = ((Core.Apply) exp).arg;
    if (arg.op == Op.TUPLE) {
      return ((Core.Tuple) arg).args;
    }
    throw unsupported("inline collection whose rows are " + arg.op);
  }

  /** Returns the element type of a collection type. */
  private Type elementType(Type type) {
    return type.arg(0);
  }

  /**
   * Translates the expression a {@code yield} computes into one Spark
   * expression per output field. A field whose value is the input field of the
   * same name needs no alias; anything else is aliased to the output name.
   */
  private List<Expression> projections(
      Core.Exp exp, List<String> parts, Type type) {
    if (exp.op != Op.TUPLE) {
      // A query can yield one unnamed column -- `yield d.dname` -- and then
      // there is no record of names to draw an alias from.
      return ImmutableList.of(exp(exp, parts));
    }
    final Core.Tuple tuple = (Core.Tuple) exp;
    final List<String> names =
        new ArrayList<>(((RecordLikeType) type.arg(0)).argNameTypes().keySet());
    final List<Expression> expressions = new ArrayList<>();
    for (int i = 0; i < tuple.args.size(); i++) {
      final Core.Exp arg = tuple.args.get(i);
      final String name = names.get(i);
      final Expression expression = exp(arg, parts);
      if (name.equals(fieldName(arg))) {
        expressions.add(expression);
      } else {
        expressions.add(
            Expression.newBuilder()
                .setAlias(
                    Expression.Alias.newBuilder()
                        .setExpr(expression)
                        .addName(name))
                .build());
      }
    }
    return expressions;
  }

  /**
   * Returns the name of the input field that {@code exp} reads, or null if it
   * reads none: that is, if it is anything but a field of the row.
   */
  private static @Nullable String fieldName(Core.Exp exp) {
    if (exp.op == Op.APPLY) {
      final Core.Apply apply = (Core.Apply) exp;
      if (apply.fn.op == Op.RECORD_SELECTOR) {
        return selectedName(apply);
      }
    }
    return null;
  }

  /** Translates an expression. */
  private Expression exp(Core.Exp exp, List<String> parts) {
    switch (exp.op) {
      case APPLY:
        final Core.Apply apply = (Core.Apply) exp;
        if (apply.fn.op == Op.RECORD_SELECTOR) {
          final String qualifier = qualifier(apply.arg, parts);
          if (qualifier != null) {
            return attribute(
                qualifier.isEmpty()
                    ? selectedName(apply)
                    : qualifier + "." + selectedName(apply));
          }
        }
        if (apply.isCallTo(BuiltIn.RELATIONAL_NON_EMPTY)) {
          return subquery(apply.arg);
        }
        if (apply.fn.op == Op.FN_LITERAL) {
          return function(
              (BuiltIn) ((Core.Literal) apply.fn).value,
              arguments(apply.arg),
              parts);
        }
        throw unsupported("call to " + apply.fn.op);

      case BOOL_LITERAL:
        return literal(
            Expression.Literal.newBuilder()
                .setBoolean((Boolean) ((Core.Literal) exp).value));

      case INT_LITERAL:
        return literal(
            Expression.Literal.newBuilder()
                .setInteger(((Number) ((Core.Literal) exp).value).intValue()));

      case REAL_LITERAL:
        return literal(
            Expression.Literal.newBuilder()
                .setDouble(
                    ((Number) ((Core.Literal) exp).value).doubleValue()));

      case STRING_LITERAL:
        return literal(
            Expression.Literal.newBuilder()
                .setString((String) ((Core.Literal) exp).value));

      default:
        throw unsupported("expression " + exp.op);
    }
  }

  /**
   * Returns the arguments of a call: a tuple's fields, or the one argument of a
   * one-argument call.
   */
  private static List<Core.Exp> arguments(Core.Exp arg) {
    return arg.op == Op.TUPLE ? ((Core.Tuple) arg).args : ImmutableList.of(arg);
  }

  /** Translates a call to a built-in. */
  private Expression function(
      BuiltIn builtIn, List<Core.Exp> args, List<String> parts) {
    final SparkFunctionTable.Entry entry = functions.get(builtIn);
    if (entry == null) {
      throw unsupported("built-in " + builtIn + ", which the table omits");
    }
    switch (entry.kind) {
      case DIRECT:
        final Expression.UnresolvedFunction.Builder b =
            Expression.UnresolvedFunction.newBuilder()
                .setFunctionName(entry.spark);
        for (Core.Exp arg : args) {
          b.addArguments(exp(arg, parts));
        }
        return Expression.newBuilder().setUnresolvedFunction(b).build();
      default:
        throw unsupported(builtIn + ", which the table calls " + entry.kind);
    }
  }

  /**
   * Translates an aggregate function over a group: the function the table
   * names, applied to what the query aggregates. An aggregate with no argument
   * counts rows, and Spark counts them by counting a constant.
   */
  private Expression aggregate(Core.Aggregate agg, List<String> parts) {
    if (agg.aggregate.op != Op.FN_LITERAL) {
      throw unsupported("aggregate " + agg.aggregate.op);
    }
    final BuiltIn builtIn = (BuiltIn) ((Core.Literal) agg.aggregate).value;
    final SparkFunctionTable.Entry entry = functions.get(builtIn);
    if (entry == null) {
      throw unsupported("aggregate " + builtIn + ", which the table omits");
    }
    if (entry.kind != SparkFunctionTable.Kind.AGGREGATE) {
      throw unsupported(
          builtIn + " over a group, which the table calls " + entry.kind);
    }
    final Expression.UnresolvedFunction.Builder b =
        Expression.UnresolvedFunction.newBuilder().setFunctionName(entry.spark);
    b.addArguments(
        agg.argument == null || agg.argument.op == Op.UNIT_LITERAL
            ? literal(Expression.Literal.newBuilder().setInteger(1))
            : exp(agg.argument, parts));
    return Expression.newBuilder().setUnresolvedFunction(b).build();
  }

  /**
   * Names an expression, unless it already reads the field of that name: a key
   * called `deptno` that reads `deptno` needs no alias, and an aggregate always
   * does.
   */
  private static Expression named(Expression expression, String name) {
    if (expression.hasUnresolvedAttribute()) {
      final String id =
          expression.getUnresolvedAttribute().getUnparsedIdentifier();
      if (id.endsWith("." + name) || id.equals(name)) {
        return expression;
      }
    }
    return Expression.newBuilder()
        .setAlias(
            Expression.Alias.newBuilder().setExpr(expression).addName(name))
        .build();
  }

  /**
   * Translates the keys a query orders by, which are a tuple when there is more
   * than one, and each of which may be wrapped in `DESC`.
   *
   * <p>Spark puts nulls where that direction puts them: last when descending,
   * first when ascending.
   */
  private void sortOrder(
      Core.Exp exp,
      boolean descending,
      List<String> parts,
      Consumer<Expression.SortOrder> consumer) {
    if (exp.isCallTo(BuiltIn.Constructor.DESCENDING_DESC)) {
      sortOrder(((Core.Apply) exp).arg, !descending, parts, consumer);
      return;
    }
    if (exp.op == Op.TUPLE) {
      for (Core.Exp arg : ((Core.Tuple) exp).args) {
        sortOrder(arg, descending, parts, consumer);
      }
      return;
    }
    consumer.accept(
        Expression.SortOrder.newBuilder()
            .setChild(exp(exp, parts))
            .setDirection(
                descending
                    ? Expression.SortOrder.SortDirection
                        .SORT_DIRECTION_DESCENDING
                    : Expression.SortOrder.SortDirection
                        .SORT_DIRECTION_ASCENDING)
            .setNullOrdering(
                descending
                    ? Expression.SortOrder.NullOrdering.SORT_NULLS_LAST
                    : Expression.SortOrder.NullOrdering.SORT_NULLS_FIRST)
            .build());
  }

  /**
   * Translates a query that an expression asks a question of: whether it has a
   * row. The query goes beside the root, under a plan id of its own, and the
   * expression quotes that id.
   *
   * <p>The query may read the outer row, which is what makes it correlated;
   * nothing special is needed for that, because the outer row prints under the
   * name it has, and Spark resolves it against the enclosing relation.
   */
  private Expression subquery(Core.Exp exp) {
    if (!(exp instanceof Core.Rel)) {
      throw unsupported("exists over " + exp.op);
    }
    final int planId = nextPlanId++;
    final Relation relation = relNode(exp, planId);
    references.add(relation);
    return Expression.newBuilder()
        .setSubqueryExpression(
            SubqueryExpression.newBuilder()
                .setPlanId(planId)
                .setSubqueryType(
                    SubqueryExpression.SubqueryType.SUBQUERY_TYPE_EXISTS))
        .build();
  }

  private static Expression attribute(String identifier) {
    return Expression.newBuilder()
        .setUnresolvedAttribute(
            Expression.UnresolvedAttribute.newBuilder()
                .setUnparsedIdentifier(identifier)
                .setIsMetadataColumn(false))
        .build();
  }

  private static Expression literal(Expression.Literal.Builder literal) {
    return Expression.newBuilder().setLiteral(literal).build();
  }

  /**
   * Returns the alias that qualifies a field read out of {@code exp}, or null
   * if {@code exp} is not something a field can be read from.
   *
   * <p>A field of the row is qualified by the row's own name. A field of a
   * component of the row -- what a projection over a join reads -- is qualified
   * by the name of the input that contributed it, which {@code parts} lists in
   * order.
   */
  private static @Nullable String qualifier(Core.Exp exp, List<String> parts) {
    if (exp.op == Op.ID) {
      final String name = ((Core.Id) exp).idPat.name;
      // A row the compiler named, as a projection's output is, has no alias
      // in the plan, so a column of it is unqualified.
      return name.startsWith("$") ? "" : name;
    }
    if (exp.op == Op.APPLY) {
      final Core.Apply apply = (Core.Apply) exp;
      if (apply.fn.op == Op.RECORD_SELECTOR && apply.arg.op == Op.ID) {
        final int slot = ((Core.RecordSelector) apply.fn).slot;
        if (slot < parts.size()) {
          return parts.get(slot);
        }
      }
    }
    return null;
  }

  /**
   * Returns the names of a node's input's components: a join's two sides, or
   * nothing at all, since only a join concatenates rows.
   */
  private static List<String> parts(Core.Exp input) {
    if (input instanceof Core.Join) {
      final Core.Join join = (Core.Join) input;
      return ImmutableList.of(join.leftRow.name, join.rightRow.name);
    }
    return ImmutableList.of();
  }

  /** Returns the field name that a record selector selects. */
  private static String selectedName(Core.Apply apply) {
    final int slot = ((Core.RecordSelector) apply.fn).slot;
    final RecordLikeType recordType = (RecordLikeType) apply.arg.type;
    int i = 0;
    for (Map.Entry<String, Type> field : recordType.argNameTypes().entrySet()) {
      if (i++ == slot) {
        return field.getKey();
      }
    }
    throw new IllegalArgumentException(
        "no field " + slot + " in " + recordType);
  }

  private static SparkBackend.SparkException unsupported(String what) {
    return new SparkBackend.SparkException(
        "UNSUPPORTED", "Spark cannot do " + what);
  }
}

// End SparkTranslator.java
