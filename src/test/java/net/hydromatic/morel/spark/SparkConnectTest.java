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

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.common.collect.ImmutableList;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.foreign.SparkBackend;
import net.hydromatic.morel.type.TypeSystem;
import org.junit.jupiter.api.Test;

/**
 * Tests the Spark adapter against a live Spark Connect server.
 *
 * <p>Runs only if system property {@code morel.spark} is set and environment
 * variable {@code SPARK_REMOTE} names the server, which must hold the seed
 * tables that {@code src/test/resources/spark/seed.py} creates. To run it:
 *
 * <pre>{@code
 * export SPARK_REMOTE=$(src/test/resources/spark/start-spark.sh)
 * ./mvnw test -Dtest=SparkConnectTest -Dmorel.spark=true
 * }</pre>
 */
public class SparkConnectTest {
  private static final List<Object> NONE = ImmutableList.of("NONE");

  private static List<Object> some(Object o) {
    return ImmutableList.of("SOME", o);
  }

  private static SparkBackend.Connection connect() {
    final String uri = System.getenv("SPARK_REMOTE");
    assumeTrue(
        System.getProperty("morel.spark") != null && uri != null,
        "set -Dmorel.spark=true and SPARK_REMOTE to run");
    final TypeSystem typeSystem = new TypeSystem();
    BuiltIn.dataTypes(typeSystem, new ArrayList<>());
    assertThat(SparkBackend.isAvailable(), is(true));
    return SparkBackend.load().connect(typeSystem, uri);
  }

  @Test
  void testEmp() {
    try (SparkBackend.Connection c = connect()) {
      final SparkBackend.Result result =
          c.sql(
              "SELECT ename, sal FROM scott.emps ORDER BY sal DESC, ename LIMIT 3");
      assertThat(result.rowType().moniker(), is("{ename:string, sal:real}"));
      assertThat(
          result.rows(),
          is(
              ImmutableList.of(
                  ImmutableList.of("KING", 5000f),
                  ImmutableList.of("FORD", 3000f),
                  ImmutableList.of("SCOTT", 3000f))));
      final SparkBackend.Result count =
          c.sql("SELECT COUNT(*) AS c FROM scott.emps");
      assertThat(count.rowType().moniker(), is("{c:int}"));
      assertThat(count.rows(), is(ImmutableList.of(ImmutableList.of(14))));
    }
  }

  /** Reads the zoo table's typical row: one column per Spark type. */
  @Test
  void testZooTypes() {
    try (SparkBackend.Connection c = connect()) {
      final SparkBackend.Result result =
          c.sql(
              "SELECT id, b, i8, i16, i32, i64, f32, f64, dec, s, bin, d, ts,"
                  + " ts_ntz, arr, st FROM zoo WHERE id = 1");
      assertThat(
          result.rowType().moniker(),
          is(
              "{arr:int option list, b:bool, bin:word list, d:date, dec:real,"
                  + " f32:real, f64:real, i16:int, i32:int, i64:int, i8:int,"
                  + " id:int, s:string, st:{a:int option, b:string option},"
                  + " ts:time, ts_ntz:date}"));
      final OffsetDateTime jan15 =
          OffsetDateTime.of(2024, 1, 15, 0, 0, 0, 0, ZoneOffset.UTC);
      final OffsetDateTime jan15at1030 = jan15.withHour(10).withMinute(30);
      assertThat(
          result.rows(),
          is(
              ImmutableList.of(
                  ImmutableList.of(
                      ImmutableList.of(some(1), some(2), some(3)), // arr
                      true, // b
                      ImmutableList.of(1L, 2L), // bin
                      jan15, // d
                      12.34f, // dec
                      1.5f, // f32
                      2.5f, // f64
                      2, // i16
                      3, // i32
                      4, // i64
                      1, // i8
                      1, // id
                      "hello", // s
                      ImmutableList.of(some(1), some("x")), // st
                      jan15at1030.toEpochSecond() * 1_000_000_000L, // ts
                      jan15at1030)))); // ts_ntz
    }
  }

  /** Reads the zoo table's edge row. */
  @Test
  void testZooEdges() {
    try (SparkBackend.Connection c = connect()) {
      final SparkBackend.Result result =
          c.sql(
              "SELECT i8, i16, i32, f32, f64, dec, s, bin, arr, st"
                  + " FROM zoo WHERE id = 2");
      final List<Object> row = (List<Object>) result.rows().get(0);
      // Labels in order: arr, bin, dec, f32, f64, i16, i32, i8, s, st
      assertThat(row.get(0), is(ImmutableList.of())); // arr
      assertThat(row.get(1), is(ImmutableList.of())); // bin
      assertThat(row.get(2), is(99999999.99f)); // dec
      assertThat(Float.isNaN((Float) row.get(3)), is(true)); // f32
      assertThat(row.get(4), is(Float.NEGATIVE_INFINITY)); // f64
      assertThat(row.get(5), is(32767)); // i16
      assertThat(row.get(6), is(Integer.MAX_VALUE)); // i32
      assertThat(row.get(7), is(-128)); // i8
      assertThat(row.get(8), is("")); // s
      assertThat(row.get(9), is(ImmutableList.of(NONE, some("ü")))); // st

      // A bigint that does not fit in int
      final SparkBackend.SparkException e =
          assertThrows(
              SparkBackend.SparkException.class,
              () -> c.sql("SELECT i64 FROM zoo WHERE id = 2"));
      assertThat(e.errorClass, is("CAST_OVERFLOW"));

      // A null in a column whose Morel type is not option
      final SparkBackend.SparkException e2 =
          assertThrows(
              SparkBackend.SparkException.class,
              () -> c.sql("SELECT id, b FROM zoo WHERE id = 3"));
      assertThat(e2.errorClass, is("NULL_VALUE"));
      assertThat(e2.getMessage(), containsString("column b"));

      // A map column has no Morel type
      final SparkBackend.SparkException e3 =
          assertThrows(
              SparkBackend.SparkException.class,
              () -> c.sql("SELECT m FROM zoo WHERE id = 1"));
      assertThat(e3.errorClass, is("UNSUPPORTED_TYPE"));
      assertThat(e3.getMessage(), containsString("map<string,integer>"));
    }
  }

  /** Spark errors carry Spark's error class, and the connection survives. */
  @Test
  void testErrors() {
    try (SparkBackend.Connection c = connect()) {
      final SparkBackend.SparkException e =
          assertThrows(
              SparkBackend.SparkException.class, () -> c.sql("SELECT 1 / 0"));
      assertThat(e.errorClass, is("DIVIDE_BY_ZERO"));
      final SparkBackend.SparkException e2 =
          assertThrows(
              SparkBackend.SparkException.class,
              () -> c.sql("SELECT * FROM no_such_table"));
      assertThat(e2.errorClass, is("TABLE_OR_VIEW_NOT_FOUND"));
      // Still usable
      assertThat(
          c.sql("SELECT 1 AS one").rows(),
          is(ImmutableList.of(ImmutableList.of(1))));
    }
  }

  @Test
  void testClose() {
    final SparkBackend.Connection c = connect();
    c.close();
    final SparkBackend.SparkException e =
        assertThrows(
            SparkBackend.SparkException.class, () -> c.sql("SELECT 1"));
    assertThat(e.errorClass, is("CLOSED"));
    c.close(); // idempotent
  }

  @Test
  void testBadUri() {
    final TypeSystem typeSystem = new TypeSystem();
    BuiltIn.dataTypes(typeSystem, new ArrayList<>());
    assumeTrue(SparkBackend.isAvailable());
    final SparkBackend.SparkException e =
        assertThrows(
            SparkBackend.SparkException.class,
            () -> SparkBackend.load().connect(typeSystem, "http://x"));
    assertThat(e.errorClass, is("CONNECTION"));
    // A server that is not there: the failure is reported on first use.
    try (SparkBackend.Connection c =
        SparkBackend.load().connect(typeSystem, "sc://localhost:1")) {
      final SparkBackend.SparkException e2 =
          assertThrows(
              SparkBackend.SparkException.class, () -> c.sql("SELECT 1"));
      assertThat(e2.errorClass, is("CONNECTION"));
    }
  }
}

// End SparkConnectTest.java
