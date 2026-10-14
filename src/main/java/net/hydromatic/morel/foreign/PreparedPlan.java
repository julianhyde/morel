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
package net.hydromatic.morel.foreign;

import static java.util.Objects.requireNonNull;
import static net.hydromatic.morel.ast.CoreBuilder.core;

import java.util.List;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Shuttle;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import org.jspecify.annotations.Nullable;

/**
 * A plan prepared on a connection: the value of Morel's {@code ('a, 'b) plan}.
 *
 * <p>{@code prepare} translates a function for a connection, and afterwards
 * both are needed: the plan to print or to run, and the connection to run it
 * on. So is the Morel type of the result, because that is the type the program
 * was typed against, and the rows have to come back as values of it.
 *
 * <p>A function with a parameter is held as the query rather than as a plan,
 * and translated at each execution with the argument in place of the parameter.
 * Spark has nowhere in a plan to keep a parameter -- its own parameters are for
 * SQL text, not for a tree of relations -- so the argument has to reach the
 * plan before it is sent. Translating again costs a tree walk, which is nothing
 * beside the round trip, and the plan that goes out is the plan a query written
 * with that argument would have produced.
 *
 * <p>It prints as {@code <plan>}. A plan's text is long, and asking for it is
 * what {@code Spark.toString} is for.
 */
public class PreparedPlan {
  private final SparkBackend.Connection connection;
  private final TypeSystem typeSystem;
  private final Core.Rel rel;
  private final Core.@Nullable IdPat parameter;
  private final SparkBackend.@Nullable Plan plan;
  private final Type elementType;
  private final boolean collection;

  /**
   * Creates a prepared plan.
   *
   * @param connection Connection the plan was prepared on, and runs on
   * @param typeSystem Type system that typed the query
   * @param rel The query
   * @param parameter The function's parameter, or null if it has none; a query
   *     with one is translated at each execution, because the argument is part
   *     of the plan
   * @param elementType Morel type of a row of the result
   * @param collection Whether the result is a collection of rows rather than
   *     one value; a query that computes a single value, such as a count, is a
   *     relation of one row and one column in Spark either way
   */
  public PreparedPlan(
      SparkBackend.Connection connection,
      TypeSystem typeSystem,
      Core.Rel rel,
      Core.@Nullable IdPat parameter,
      Type elementType,
      boolean collection) {
    this.connection = requireNonNull(connection, "connection");
    this.typeSystem = requireNonNull(typeSystem, "typeSystem");
    this.rel = requireNonNull(rel, "rel");
    this.parameter = parameter;
    // A query with no parameter is translated once, here, so that its plan is
    // the same plan however often it is used.
    this.plan =
        parameter == null ? SparkBackend.translate(typeSystem, rel) : null;
    this.elementType = requireNonNull(elementType, "elementType");
    this.collection = collection;
  }

  @Override
  public String toString() {
    return "<plan>";
  }

  /** Returns the plan as text. */
  public String text() {
    if (plan == null) {
      throw new SparkBackend.SparkException(
          "UNSUPPORTED",
          "A plan with a parameter has no text until it is given one;"
              + " execute it instead");
    }
    return plan.text();
  }

  /**
   * Executes the plan on an argument and returns its result: the rows, or,
   * where the query computes a single value, that value.
   */
  public Object execute(Object arg) {
    final SparkBackend.Plan plan =
        this.plan != null
            ? this.plan
            : SparkBackend.translate(typeSystem, substitute(arg));
    final List<Object> rows = connection.execute(plan, elementType);
    if (collection) {
      return rows;
    }
    if (rows.size() != 1) {
      throw new SparkBackend.SparkException(
          "CONNECTION",
          "Query computes one value, but Spark returned "
              + rows.size()
              + " rows");
    }
    return rows.get(0);
  }

  /** Returns the query with an argument in place of its parameter. */
  private Core.Rel substitute(Object arg) {
    final Core.IdPat parameter = requireNonNull(this.parameter, "parameter");
    return (Core.Rel)
        rel.accept(
            new Shuttle(typeSystem) {
              @Override
              protected Core.Exp visit(Core.Id id) {
                return id.idPat.equals(parameter) ? literal(id, arg) : id;
              }
            });
  }

  /** Returns a literal of the type of {@code id} whose value is {@code arg}. */
  private static Core.Exp literal(Core.Id id, Object arg) {
    if (id.type instanceof PrimitiveType) {
      return core.literal((PrimitiveType) id.type, arg);
    }
    // A decimal, say. A value literal holds the value as it is, and remembers
    // the expression it stands for, which is the parameter.
    return core.valueLiteral(id, arg);
  }
}

// End PreparedPlan.java
