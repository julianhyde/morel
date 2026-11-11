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

import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.TypeSystem;
import org.junit.jupiter.api.Test;

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
    final File depts = dir.discoverField(new TypeSystem(), "depts");
    final List<?> rows = depts.valueAs(List.class);
    assertThat(rows.size(), is(4));
    assertThat(depts.valueAs(List.class), sameInstance(rows));
  }
}

// End FilesTest.java
