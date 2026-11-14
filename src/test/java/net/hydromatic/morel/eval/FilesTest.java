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
package net.hydromatic.morel.eval;

import static java.util.Objects.requireNonNull;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.TypeSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for {@link Files}. */
class FilesTest {
  /**
   * Parses a line into a given number of columns, each kept as its field
   * (quotes and all), in file order.
   */
  private static List<Object> split(String line, int columnCount) {
    final List<Files.Column> columns = new ArrayList<>();
    final int[] targets = new int[columnCount];
    for (int i = 0; i < columnCount; i++) {
      columns.add(column("c" + i, field -> field));
      targets[i] = i;
    }
    final Object[] values = new Object[columnCount];
    new Files.LineParser(columns).parse(line, targets, values);
    return Arrays.asList(values);
  }

  /** Creates a column with a given parser. */
  private static Files.Column column(
      String name, Function<String, Object> parser) {
    return new Files.Column(name, PrimitiveType.STRING.key(), parser);
  }

  @Test
  void testSplit() {
    assertThat(split("a,b,c", 3), is(Arrays.asList("a", "b", "c")));
    assertThat(split("", 1), is(Arrays.asList("")));
    assertThat(split("1,", 2), is(Arrays.asList("1", "")));
    assertThat(split(",,", 3), is(Arrays.asList("", "", "")));
    assertThat(split("NULL,1", 2), is(Arrays.asList("NULL", "1")));
    // Fields beyond the last column are ignored.
    assertThat(split("a,b,c", 2), is(Arrays.asList("a", "b")));
  }

  /**
   * A quoted field keeps its quotes, may hold commas, and a doubled quote
   * stands for one.
   */
  @Test
  void testSplitQuoted() {
    assertThat(split("1,'a, b',2", 3), is(Arrays.asList("1", "'a, b'", "2")));
    assertThat(split("''", 1), is(Arrays.asList("''")));
    assertThat(split("'',x", 2), is(Arrays.asList("''", "x")));
    assertThat(split("'it''s',x", 2), is(Arrays.asList("'it's'", "x")));
    assertThat(split("'''x'", 1), is(Arrays.asList("''x'")));
    assertThat(split("'a'''", 1), is(Arrays.asList("'a''")));
    // An unterminated quote runs to the end of the line.
    assertThat(split("'a, b", 1), is(Arrays.asList("'a, b")));
    // Text after the closing quote is part of the field.
    assertThat(split("'a'b,c", 2), is(Arrays.asList("'a'b", "c")));
  }

  /** Each column's value goes to its ordinal in the record. */
  @Test
  void testParseTargets() {
    final List<Files.Column> columns =
        Arrays.asList(
            column("c", field -> field),
            column("a", Integer::parseInt),
            column("b", String::length));
    final Object[] values = new Object[3];
    new Files.LineParser(columns)
        .parse("x,10,abcd", new int[] {2, 0, 1}, values);
    assertThat(Arrays.asList(values), is(Arrays.asList(10, 4, "x")));
  }

  /** Converts a field with the parser of a column of a given type. */
  private static Object parse(String type, String field) {
    return Files.Column.of("c", type).parser.apply(field);
  }

  /** Returns the message of the exception that parsing a field raises. */
  private static String parseError(String type, String field) {
    final MorelRuntimeException e =
        assertThrows(MorelRuntimeException.class, () -> parse(type, field));
    return e.describeTo(new StringBuilder()).toString();
  }

  /**
   * Each column type parses its fields, quoted or not; a field that is not a
   * value of its type raises {@code Fail}. In a {@code string} column, an empty
   * field is the empty string; in a column of another type, a null.
   */
  @Test
  void testParseTypes() {
    assertThat(parse("string", "abc"), is("abc"));
    assertThat(parse("string", ""), is(""));
    assertThat(parse("string", "''"), is(""));
    assertThat(parse("string option", ""), is(Codes.OPTION_NONE));
    assertThat(
        parseError("string", "NULL"),
        is(
            "uncaught exception Fail [Fail: null in column c, "
                + "whose type string has no room for it]"));
    assertThat(parse("bool", "true"), is(true));
    assertThat(parse("bool", "'false'"), is(false));
    assertThat(
        parseError("bool", "yes"),
        is(
            "uncaught exception Fail [Fail: value yes in column c "
                + "is not a valid bool]"));
    assertThat(
        parseError("bool", ""),
        is(
            "uncaught exception Fail [Fail: null in column c, "
                + "whose type bool has no room for it]"));
    assertThat(parse("int", "'7'"), is(7));
    assertThat(
        parseError("int", "7x"),
        is(
            "uncaught exception Fail [Fail: value 7x in column c "
                + "is not a valid int]"));
    assertThat(parse("real", "1.5"), is(1.5f));
    assertThat(parse("decimal", "'1.50'"), is(new BigDecimal("1.5")));
    assertThat(
        parseError("decimal", "1.5.0"),
        is(
            "uncaught exception Fail [Fail: value 1.5.0 in column c "
                + "is not a valid decimal]"));
    assertThat(
        parseError("date", "2024-13-01"),
        is(
            "uncaught exception Fail [Fail: value 2024-13-01 in column c "
                + "is not a valid date]"));
  }

  /** A line with fewer fields than the header has columns is an error. */
  @Test
  void testParseShortLine() {
    final MorelRuntimeException e =
        assertThrows(MorelRuntimeException.class, () -> split("a,b", 3));
    assertThat(
        e.describeTo(new StringBuilder()).toString(),
        is(
            "uncaught exception Fail [Fail: line has 2 fields, "
                + "but the header has 3: a,b]"));
    assertThrows(MorelRuntimeException.class, () -> split("a", 2));
  }

  /**
   * Reading a table twice gives the same rows, so that the queries that scan it
   * share one copy, rather than each holding its own.
   */
  @Test
  void testRowsShared() {
    final URL url =
        requireNonNull(FilesTest.class.getResource("/data/scott/depts.csv"));
    final java.io.File depts0 = new java.io.File(url.getFile()); // lint:skip
    final File dir = Files.create(depts0.getParentFile());
    final File depts =
        dir.discoverField(new TypeSystem(), "depts")
            .fieldValueAs("depts", File.class);
    final List<?> rows = depts.valueAs(List.class);
    assertThat(rows.size(), is(4));
    assertThat(depts.valueAs(List.class), sameInstance(rows));
  }
  /** If a file changes, the next scan reads it again. */
  @Test
  void testRowsReread(@TempDir Path dir) throws IOException {
    final Path t = dir.resolve("t.csv");
    write(t, "n:int", "1", "2");
    final File d = Files.create(dir.toFile());
    final File table =
        d.discoverField(new TypeSystem(), "t").fieldValueAs("t", File.class);
    final List<?> rows = table.valueAs(List.class);
    assertThat(rows.size(), is(2));
    assertThat(table.valueAs(List.class), sameInstance(rows));
    write(t, "n:int", "1", "2", "3");
    assertThat(table.valueAs(List.class).size(), is(3));
  }

  /** Writes lines to a file. */
  private static void write(Path path, String... lines) throws IOException {
    java.nio.file.Files.write( // lint:skip
        path, Arrays.asList(lines), StandardCharsets.UTF_8);
  }
}

// End FilesTest.java
