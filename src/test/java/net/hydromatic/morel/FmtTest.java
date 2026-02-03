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
package net.hydromatic.morel;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import net.hydromatic.morel.parse.MorelFmt;
import org.junit.jupiter.api.Test;

/** End-to-end tests for the Morel code formatter ({@link MorelFmt}). */
class FmtTest {

  private static void check(String input, int width, String expected) {
    assertThat(MorelFmt.format(input, width), is(expected));
  }

  // -- Literals and identifiers -----------------------------------------------

  @Test
  void testLiteral() {
    check("42", 80, "42");
  }

  @Test
  void testStringLiteral() {
    check("\"hello world\"", 80, "\"hello world\"");
  }

  @Test
  void testId() {
    check("x", 80, "x");
  }

  // -- Infix operators --------------------------------------------------------

  @Test
  void testInfix() {
    check("1 + 2", 80, "1 + 2");
  }

  @Test
  void testInfixBreaks() {
    check(
        "longName + anotherLongName",
        15,
        "longName + \n" //
            + "  anotherLongName");
  }

  @Test
  void testInfixPrecedence() {
    check("1 + 2 * 3", 80, "1 + 2 * 3");
  }

  @Test
  void testInfixAssociativity() {
    check("1 - 2 - 3", 80, "1 - 2 - 3");
  }

  // -- Tuples, lists, records -------------------------------------------------

  @Test
  void testTuple() {
    check("(1, 2, 3)", 80, "(1, 2, 3)");
  }

  @Test
  void testTupleBreaks() {
    check(
        "(alpha, bravo, charlie)",
        15,
        "(alpha,\n" //
            + " bravo,\n"
            + " charlie)");
  }

  @Test
  void testList() {
    check("[1, 2, 3]", 80, "[1, 2, 3]");
  }

  @Test
  void testRecord() {
    check("{x = 1, y = 2}", 80, "{x = 1, y = 2}");
  }

  // -- If / then / else -------------------------------------------------------

  @Test
  void testIfFits() {
    check("if true then 1 else 2", 80, "if true then 1 else 2");
  }

  @Test
  void testIfBreaks() {
    check(
        "if true then 1 else 2",
        15,
        "if true\n" //
            + "  then 1\n"
            + "  else 2");
  }

  // -- Let / in / end ---------------------------------------------------------

  @Test
  void testLetFits() {
    check("let val x = 1 in x end", 80, "let val x = 1 in x end");
  }

  @Test
  void testLetBreaks() {
    check(
        "let val x = 1 in x end",
        15,
        "let\n" //
            + "  val x = 1\n"
            + "in\n"
            + "  x\n"
            + "end");
  }

  @Test
  void testLetMultipleDecls() {
    check(
        "let val x = 1; val y = 2 in x + y end",
        20,
        "let\n" //
            + "  val x = 1;\n"
            + "  val y = 2\n"
            + "in\n"
            + "  x + y\n"
            + "end");
  }

  // -- Fn (lambda) ------------------------------------------------------------

  @Test
  void testFnFits() {
    check("fn x => x + 1", 80, "fn x => x + 1");
  }

  @Test
  void testFnMultipleArms() {
    check("fn 0 => 1 | n => n * 2", 80, "fn 0 => 1 | n => n * 2");
  }

  @Test
  void testFnMultipleArmsBreaks() {
    check(
        "fn 0 => 1 | n => n * 2",
        15,
        "fn 0 => 1\n" //
            + "   | n => n * 2");
  }

  // -- Case -------------------------------------------------------------------

  @Test
  void testCaseFits() {
    check("case x of 0 => 1 | n => n", 80, "case x of 0 => 1 | n => n");
  }

  @Test
  void testCaseBreaks() {
    check(
        "case x of 0 => 1 | n => n",
        15,
        "case x of\n" //
            + "  0 => 1\n"
            + "  | n => n");
  }

  // -- Function application ---------------------------------------------------

  @Test
  void testApply() {
    check("f x", 80, "f x");
  }

  @Test
  void testApplyNested() {
    check("f (g x)", 80, "f (g x)");
  }

  // -- Declarations -----------------------------------------------------------

  @Test
  void testValDecl() {
    check("val x = 1", 80, "val x = 1");
  }

  @Test
  void testValDeclBreaks() {
    check(
        "val longName = longExpression + 1",
        20,
        "val longName =\n" //
            + "  longExpression + 1");
  }

  @Test
  void testFunDecl() {
    check("fun f x = x + 1", 80, "fun f x = x + 1");
  }

  @Test
  void testFunDeclMultipleClauses() {
    check("fun f 0 = 1 | f n = n * 2", 80, "fun f 0 = 1 | f n = n * 2");
  }

  // -- From queries -----------------------------------------------------------

  @Test
  void testFromFits() {
    // e.name is parsed as #name e (record selector application)
    check("from e in emps yield e.name", 80, "from e in emps yield #name e");
  }

  @Test
  void testFromBreaks() {
    // e.deptno is parsed as #deptno e, e.name as #name e
    check(
        "from e in emps where e.deptno = 10 yield e.name",
        30,
        "from\n" //
            + "  e in emps\n"
            + "  where #deptno e = 10\n"
            + "  yield #name e");
  }

  // -- Types ------------------------------------------------------------------

  @Test
  void testAnnotatedExp() {
    check("1 : int", 80, "1 : int");
  }

  // -- Datatype ---------------------------------------------------------------

  @Test
  void testDatatypeDecl() {
    check(
        "datatype 'a option = NONE | SOME of 'a",
        80,
        "datatype 'a option = NONE | SOME of 'a");
  }

  // -- Pattern matching -------------------------------------------------------

  @Test
  void testWildcardPat() {
    check("fn _ => 0", 80, "fn _ => 0");
  }

  @Test
  void testTuplePat() {
    check("fn (x, y) => x + y", 80, "fn (x, y) => x + y");
  }

  @Test
  void testListPat() {
    check("fn [x] => x | _ => 0", 80, "fn [x] => x | _ => 0");
  }

  // -- Idempotency (format twice, same result) --------------------------------

  @Test
  void testIdempotentInfix() {
    final String input = "1 + 2 * 3";
    final String formatted = MorelFmt.format(input, 80);
    assertThat(MorelFmt.format(formatted, 80), is(formatted));
  }

  @Test
  void testIdempotentLet() {
    final String input = "let val x = 1 in x end";
    final String formatted = MorelFmt.format(input, 20);
    assertThat(MorelFmt.format(formatted, 20), is(formatted));
  }

  @Test
  void testIdempotentFn() {
    final String input = "fn 0 => 1 | n => n * 2";
    final String formatted = MorelFmt.format(input, 15);
    assertThat(MorelFmt.format(formatted, 15), is(formatted));
  }

  // -- All-constructs comprehensive tests ------------------------------------

  /**
   * Reads a resource file from the {@code /script/fmt/} classpath directory.
   */
  private static String readResource(String name) throws IOException {
    final String path = "/script/fmt/" + name;
    try (InputStream is = FmtTest.class.getResourceAsStream(path)) {
      if (is == null) {
        throw new IOException("resource not found: " + path);
      }
      try (BufferedReader r = TestUtils.reader(is)) {
        return r.lines().collect(Collectors.joining("\n"));
      }
    }
  }

  /**
   * Formats {@code all-constructs.sml} at the given width, compares the result
   * against the reference file, and verifies idempotency.
   */
  private void checkAllConstructs(int width, String refFileName)
      throws IOException {
    final String source = readResource("all-constructs.sml");
    final String expected = readResource(refFileName);
    final String formatted = MorelFmt.format(source, width);

    // Compare against reference
    final List<String> expectedLines = Arrays.asList(expected.split("\n", -1));
    final List<String> actualLines = Arrays.asList(formatted.split("\n", -1));
    final String diff = TestUtils.diffLines(expectedLines, actualLines);
    assertThat(
        "formatted output differs from " + refFileName + ":\n" + diff,
        formatted,
        is(expected));

    // Idempotency: formatting the output again should be identical
    final String reformatted = MorelFmt.format(formatted, width);
    assertThat(
        "formatter is not idempotent at width " + width,
        reformatted,
        is(formatted));
  }

  @Test
  void testAllConstructsW80() throws IOException {
    checkAllConstructs(80, "all-constructs.w80.ref");
  }
}

// End FmtTest.java
