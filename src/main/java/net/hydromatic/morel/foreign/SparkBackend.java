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

import java.util.List;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import org.jspecify.annotations.Nullable;

/**
 * Executes queries on a Spark cluster, via Spark Connect.
 *
 * <p>The implementation lives in package {@code net.hydromatic.morel.spark},
 * the Spark adapter, which has requirements that the rest of Morel does not: a
 * newer JDK, and the gRPC, protobuf and Arrow libraries. Nothing outside that
 * package refers to it by name except {@link #load()}, which loads it by
 * reflection, so that Morel builds and runs without it.
 */
public interface SparkBackend {
  /** Name of the class that implements this interface. */
  String IMPLEMENTATION_CLASS =
      "net.hydromatic.morel.spark.SparkConnectBackend";

  /** Returns whether the Spark adapter is on the class path. */
  static boolean isAvailable() {
    try {
      Class.forName(IMPLEMENTATION_CLASS);
      return true;
    } catch (ClassNotFoundException | LinkageError e) {
      return false;
    }
  }

  /**
   * Loads the Spark adapter.
   *
   * @throws IllegalStateException if the adapter is not on the class path
   */
  static SparkBackend load() {
    try {
      return (SparkBackend)
          Class.forName(IMPLEMENTATION_CLASS)
              .getDeclaredConstructor()
              .newInstance();
    } catch (ReflectiveOperationException | LinkageError e) {
      throw new IllegalStateException(
          "The Spark adapter (" + IMPLEMENTATION_CLASS + ") is not available",
          e);
    }
  }

  /**
   * Opens a connection to a Spark Connect server.
   *
   * @param typeSystem Type system in which to create the Morel types of results
   * @param uri URI of the server, e.g. "sc://localhost:15002"
   */
  Connection connect(TypeSystem typeSystem, String uri);

  /** A connection to a Spark Connect server. */
  interface Connection extends AutoCloseable {
    /** Returns the URI this connection was opened with. */
    String uri();

    /**
     * Executes a SQL query and returns its rows.
     *
     * @throws SparkException if Spark reports an error, or the connection is
     *     closed
     */
    Result sql(String sql);

    /** Closes the connection; any later use raises {@link SparkException}. */
    @Override
    void close();
  }

  /**
   * The result of a query: the Morel type of a row, and the rows as Morel
   * values.
   *
   * <p>A row is a record, represented as a {@link List} whose elements are in
   * the order of the record type's fields (sorted by label, as Morel orders
   * them). Nested values follow the same conventions as the rest of Morel.
   */
  interface Result {
    /**
     * The type of a row: a record type, or a primitive type if the query has
     * one column and it is unnamed.
     */
    Type rowType();

    /** The rows. */
    List<Object> rows();
  }

  /** An error reported by Spark, or by the adapter. */
  class SparkException extends RuntimeException {
    /**
     * Spark's error class, e.g. "DIVIDE_BY_ZERO" or "TABLE_OR_VIEW_NOT_FOUND";
     * or one of the adapter's own: "CONNECTION" (the server could not be
     * reached), "CLOSED" (the connection was closed), "NULL_VALUE" (a null in a
     * column whose Morel type has no room for it), "UNSUPPORTED_TYPE".
     */
    public final String errorClass;

    public SparkException(
        String errorClass, String message, @Nullable Throwable cause) {
      super(message, cause);
      this.errorClass = errorClass;
    }

    public SparkException(String errorClass, String message) {
      this(errorClass, message, null);
    }

    @Override
    public String toString() {
      return "Spark {errorClass = \""
          + errorClass
          + "\", message = \""
          + getMessage()
          + "\"}";
    }
  }
}

// End SparkBackend.java
