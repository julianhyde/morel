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

import java.util.List;
import net.hydromatic.morel.type.Type;

/**
 * A plan prepared on a connection: the value of Morel's {@code ('a, 'b) plan}.
 *
 * <p>{@code prepare} translates a function for a connection, and afterwards
 * both are needed: the plan to print or to run, and the connection to run it
 * on. So is the Morel type of the result, because that is the type the program
 * was typed against, and the rows have to come back as values of it.
 *
 * <p>It prints as {@code <plan>}. A plan's text is long, and asking for it is
 * what {@code Spark.toString} is for.
 */
public class PreparedPlan {
  private final SparkBackend.Connection connection;
  private final SparkBackend.Plan plan;
  private final Type elementType;
  private final boolean collection;

  /**
   * Creates a prepared plan.
   *
   * @param connection Connection the plan was prepared on, and runs on
   * @param plan The translated plan
   * @param elementType Morel type of a row of the result
   * @param collection Whether the result is a collection of rows rather than
   *     one value; a query that computes a single value, such as a count, is a
   *     relation of one row and one column in Spark either way
   */
  public PreparedPlan(
      SparkBackend.Connection connection,
      SparkBackend.Plan plan,
      Type elementType,
      boolean collection) {
    this.connection = requireNonNull(connection, "connection");
    this.plan = requireNonNull(plan, "plan");
    this.elementType = requireNonNull(elementType, "elementType");
    this.collection = collection;
  }

  @Override
  public String toString() {
    return "<plan>";
  }

  /** Returns the plan as text. */
  public String text() {
    return plan.text();
  }

  /**
   * Executes the plan and returns its result: the rows, or, where the query
   * computes a single value, that value.
   */
  public Object execute() {
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
}

// End PreparedPlan.java
