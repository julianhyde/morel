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
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.foreign.SparkBackend;
import net.hydromatic.morel.type.RecordLikeType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import org.apache.spark.connect.proto.Expression;
import org.apache.spark.connect.proto.Filter;
import org.apache.spark.connect.proto.LocalRelation;
import org.apache.spark.connect.proto.Plan;
import org.apache.spark.connect.proto.Project;
import org.apache.spark.connect.proto.Relation;
import org.apache.spark.connect.proto.RelationCommon;
import org.apache.spark.connect.proto.SubqueryAlias;
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

  /** Translates one relational node. */
  private Relation rel(Core.Exp exp) {
    final int planId = nextPlanId++;
    final Relation.Builder b = Relation.newBuilder();
    switch (exp.op) {
      case FILTER:
        final Core.Filter filter = (Core.Filter) exp;
        b.setFilter(
            Filter.newBuilder()
                .setInput(input(filter.input, filter.row))
                .setCondition(exp(filter.condition, filter.row)));
        break;

      case PROJECT:
        final Core.Project project = (Core.Project) exp;
        b.setProject(
            Project.newBuilder()
                .setInput(input(project.input, project.row))
                .addAllExpressions(
                    projections(project.exp, project.row, project.type)));
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
    throw unsupported("collection " + exp.op);
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
      Core.Exp exp, Core.IdPat row, Type type) {
    if (exp.op != Op.TUPLE) {
      throw unsupported("projection " + exp.op);
    }
    final Core.Tuple tuple = (Core.Tuple) exp;
    final List<String> names =
        new ArrayList<>(((RecordLikeType) type.arg(0)).argNameTypes().keySet());
    final List<Expression> expressions = new ArrayList<>();
    for (int i = 0; i < tuple.args.size(); i++) {
      final Core.Exp arg = tuple.args.get(i);
      final String name = names.get(i);
      final Expression expression = exp(arg, row);
      if (name.equals(fieldName(arg, row))) {
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
  private static @Nullable String fieldName(Core.Exp exp, Core.IdPat row) {
    if (exp.op == Op.APPLY) {
      final Core.Apply apply = (Core.Apply) exp;
      if (apply.fn.op == Op.RECORD_SELECTOR && isRow(apply.arg, row)) {
        return selectedName(apply);
      }
    }
    return null;
  }

  /** Translates an expression. */
  private Expression exp(Core.Exp exp, Core.IdPat row) {
    switch (exp.op) {
      case APPLY:
        final Core.Apply apply = (Core.Apply) exp;
        if (apply.fn.op == Op.RECORD_SELECTOR && isRow(apply.arg, row)) {
          return attribute(row.name + "." + selectedName(apply));
        }
        if (apply.fn.op == Op.FN_LITERAL) {
          return function(
              (BuiltIn) ((Core.Literal) apply.fn).value,
              arguments(apply.arg),
              row);
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
      BuiltIn builtIn, List<Core.Exp> args, Core.IdPat row) {
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
          b.addArguments(exp(arg, row));
        }
        return Expression.newBuilder().setUnresolvedFunction(b).build();
      default:
        throw unsupported(builtIn + ", which the table calls " + entry.kind);
    }
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

  /** Returns whether an expression is the row variable itself. */
  private static boolean isRow(Core.Exp exp, Core.IdPat row) {
    return exp.op == Op.ID && ((Core.Id) exp).idPat.equals(row);
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
