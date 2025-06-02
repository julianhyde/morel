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

import static net.hydromatic.morel.util.Wadler.concat;
import static net.hydromatic.morel.util.Wadler.group;
import static net.hydromatic.morel.util.Wadler.join;
import static net.hydromatic.morel.util.Wadler.line;
import static net.hydromatic.morel.util.Wadler.nest;
import static net.hydromatic.morel.util.Wadler.text;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

import net.hydromatic.morel.util.Wadler;
import org.junit.jupiter.api.Test;

/** Tests for pretty-printing. */
public class WadlerTest {
  /** Simple function call. */
  @Test
  void testExample1() {
    System.out.println("=== Example 1: Function Call ===");
    Wadler.Doc funcCall =
        group(
            concat(
                text("function("),
                nest(
                    2,
                    concat(
                        line(),
                        join(
                            concat(text(","), line()),
                            text("arg1"),
                            text("arg2"),
                            text("arg3")))),
                line(),
                text(")")));

    assertThat(
        funcCall.render(20), is("function(\n  arg1,\n  arg2,\n  arg3\n)"));
    assertThat(
        funcCall.render(10), is("function(\n  arg1,\n  arg2,\n  arg3\n)"));
  }

  /** Nested structure with various elements. */
  @Test
  void testExample2() {
    Wadler.Doc nestedObj =
        group(
            concat(
                text("{"),
                nest(
                    2,
                    concat(
                        line(),
                        join(
                            concat(text(","), line()),
                            concat(
                                text("key1: "),
                                group(concat(text("\"value1\"")))),
                            concat(
                                text("key2: "),
                                group(
                                    concat(
                                        text("["),
                                        nest(
                                            2,
                                            concat(
                                                line(),
                                                join(
                                                    concat(text(","), line()),
                                                    text("1"),
                                                    text("2"),
                                                    text("3")))),
                                        line(),
                                        text("]")))),
                            concat(text("key3: "), text("\"value3\""))))),
                line(),
                text("}")));

    assertThat(
        "width 40",
        nestedObj.render(40),
        is(
            "{\n  key1: \"value1\",\n  key2: [ 1, 2, 3 ],\n  key3: \"value3\"\n}"));

    assertThat(
        "width 15",
        nestedObj.render(15),
        is(
            "{\n  key1: \"value1\",\n  key2: [ 1, 2, 3 ],\n  key3: \"value3\"\n}"));
  }

  /** Code-like structure with nested elements. */
  @Test
  void testExample3() {
    Wadler.Doc codeBlock =
        group(
            concat(
                text("if (condition) {"),
                nest(
                    4,
                    concat(
                        line(),
                        group(
                            concat(
                                text("doSomething("),
                                nest(
                                    4,
                                    concat(
                                        line(),
                                        join(
                                            concat(text(","), line()),
                                            text("param1"),
                                            text("param2"),
                                            text("param3")))),
                                line(),
                                text(");"))))),
                line(),
                text("}")));

    assertThat(
        "width 50",
        codeBlock.render(50),
        is("if (condition) {\n    doSomething( param1, param2, param3 );\n}"));
    assertThat(
        "width 20",
        codeBlock.render(20),
        is(
            "if (condition) {\n    doSomething(\n        param1,\n        param2,\n        param3\n    );\n}"));
  }
}

// End WadlerTest.java
