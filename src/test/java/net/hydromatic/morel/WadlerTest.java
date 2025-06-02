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

import static net.hydromatic.morel.util.Static.repeat;
import static net.hydromatic.morel.util.Wadler.concat;
import static net.hydromatic.morel.util.Wadler.group;
import static net.hydromatic.morel.util.Wadler.join;
import static net.hydromatic.morel.util.Wadler.line;
import static net.hydromatic.morel.util.Wadler.nest;
import static net.hydromatic.morel.util.Wadler.text;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.hydromatic.morel.util.Wadler.Doc;
import org.junit.jupiter.api.Test;

/** Tests for pretty-printing. */
public class WadlerTest {

  private final PrintWriter out;

  WadlerTest() {
    out = new PrintWriter(System.out, true);
  }

  private void checkDocument(Doc doc) {
    int[] widths = {20, 40, 80, 120};

    for (int width : widths) {
      out.printf("Width %d:%n", width);
      out.println(repeat("─", width));

      long startTime = System.nanoTime();
      String result = doc.render(width);
      long endTime = System.nanoTime();

      out.println(result);
      out.printf("Time: %s us%n", (endTime - startTime) / 1_000.0);

      // Verify width constraint
      String[] lines = result.split("\n");
      int maxLineLength = 0;
      for (String line : lines) {
        maxLineLength = Math.max(maxLineLength, line.length());
      }
      out.println(
          "Max line length: "
              + maxLineLength
              + (maxLineLength > width ? " (EXCEEDED!)" : " (OK)"));
    }
  }

  static void benchmarkPerformance(
      StressTestGenerator generator, PrintWriter out) {
    int[] complexities = {5, 10, 15, 20};
    int iterations = 10;

    for (int complexity : complexities) {
      long totalNanos = 0;

      for (int i = 0; i < iterations; i++) {
        Doc doc = generator.generate(complexity);

        long startTime = System.nanoTime();
        doc.render(40);
        long endTime = System.nanoTime();

        totalNanos += endTime - startTime;
      }

      double avgMicros = totalNanos / (double) iterations / 1_000.0;
      out.printf("Complexity %d: %.2f us average%n", complexity, avgMicros);
    }
  }

  /** Simple function call. */
  @Test
  void testExample1() {
    Doc funcCall =
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

    assertThat(funcCall.render(30), is("function( arg1, arg2, arg3 )"));
    assertThat(
        funcCall.render(10),
        is(
            "function(\n" //
                + "  arg1,\n"
                + "  arg2,\n"
                + "  arg3\n"
                + ")"));
  }

  /** Nested structure with various elements. */
  @Test
  void testExample2() {
    Doc nestedObj =
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
            "{\n"
                + "  key1: \"value1\",\n"
                + "  key2: [ 1, 2, 3 ],\n"
                + "  key3: \"value3\"\n"
                + "}"));

    assertThat(
        "width 10",
        nestedObj.render(10),
        is(
            "{\n"
                + "  key1: \"value1\",\n"
                + "  key2: [\n"
                + "    1,\n"
                + "    2,\n"
                + "    3\n"
                + "  ],\n"
                + "  key3: \"value3\"\n"
                + "}"));
  }

  /** Code-like structure with nested elements. */
  @Test
  void testExample3() {
    Doc codeBlock =
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
        is(
            "if (condition) {\n"
                + "    doSomething( param1, param2, param3 );\n"
                + "}"));
    assertThat(
        "width 20",
        codeBlock.render(20),
        is(
            "if (condition) {\n"
                + "    doSomething(\n"
                + "        param1,\n"
                + "        param2,\n"
                + "        param3\n"
                + "    );\n"
                + "}"));
  }

  /** Stress test with high complexity. */
  @Test
  void testStress1() {
    StressTestGenerator generator = new StressTestGenerator(12345);
    Doc doc = generator.generate(8);
    checkDocument(doc);
  }

  /** Stress test with medium complexity, narrow width. */
  @Test
  void testStress2() {
    StressTestGenerator generator = new StressTestGenerator(12345);
    Doc doc = generator.generate(6);
    checkDocument(doc);
  }

  /** Stress test with deep nesting. */
  @Test
  void testStress3() {
    StressTestGenerator generator = new StressTestGenerator(12345);
    Doc doc = generator.generate(10);
    checkDocument(doc);
  }

  /** Stress test with deep nesting. */
  @Test
  void testBenchmark() {
    StressTestGenerator generator = new StressTestGenerator(12345);
    benchmarkPerformance(generator, out);
  }

  /**
   * Generates random documents that stress-test the Wadler pretty printer by
   * creating complex nested structures that require extensive backtracking
   */
  static class StressTestGenerator {
    private final Random random;
    private final String[] identifiers = {
      "variable",
      "function",
      "parameter",
      "argument",
      "method",
      "class",
      "object",
      "property",
      "attribute",
      "element",
      "component",
      "module",
      "namespace",
      "constant",
      "field",
      "member",
      "instance",
      "reference",
      "pointer",
      "handler"
    };

    private final String[] operators = {
      "+", "-", "*", "/", "==", "!=", "<=", ">=", "<", ">", "&&", "||", "!",
      "=", "+=", "-=", "*=", "/=", "&", "|", "^", "<<", ">>", "++", "--"
    };

    StressTestGenerator(long seed) {
      this.random = new Random(seed);
    }

    /** Generates a random document that stresses the pretty printer. */
    public Doc generate(int complexity) {
      return nestedStructure(complexity, 0);
    }

    private Doc nestedStructure(int complexity, int depth) {
      if (complexity <= 0 || depth > 8) {
        return simpleExpression();
      }

      int structureType = random.nextInt(8);
      switch (structureType) {
        case 0:
          return functionCall(complexity, depth);
        case 1:
          return array(complexity, depth);
        case 2:
          return objectLiteral(complexity, depth);
        case 3:
          return nestedExpression(complexity, depth);
        case 4:
          return conditional(complexity, depth);
        case 5:
          return chainedCall(complexity, depth);
        default:
          return simpleExpression();
      }
    }

    /**
     * Generates a deeply nested function call with many parameters. This forces
     * the printer to make many layout decisions.
     */
    private Doc functionCall(int complexity, int depth) {
      String funcName = randomIdentifier();
      int paramCount = 2 + random.nextInt(Math.min(8, complexity));

      List<Doc> params = new ArrayList<>();
      for (int i = 0; i < paramCount; i++) {
        if (random.nextInt(10) < 4 && complexity > 1) {
          // Nested structure as parameter
          params.add(nestedStructure(complexity / 2, depth + 1));
        } else {
          // Simple parameter, but sometimes make it long
          if (random.nextInt(10) < 3) {
            params.add(longExpression());
          } else {
            params.add(simpleExpression());
          }
        }
      }

      return group(
          concat(
              text(funcName + "("),
              nest(2, concat(line(), join(concat(text(","), line()), params))),
              line(),
              text(")")));
    }

    /** Generates an array with mixed content types and irregular lengths. */
    private Doc array(int complexity, int depth) {
      int elementCount = 1 + random.nextInt(Math.min(10, complexity + 2));
      List<Doc> elements = new ArrayList<>();

      for (int i = 0; i < elementCount; i++) {
        if (random.nextInt(10) < 5 && complexity > 1) {
          elements.add(nestedStructure(complexity / 3, depth + 1));
        } else if (random.nextInt(10) < 4) {
          elements.add(longExpression());
        } else {
          elements.add(simpleExpression());
        }
      }

      return group(
          concat(
              text("["),
              nest(
                  2, concat(line(), join(concat(text(","), line()), elements))),
              line(),
              text("]")));
    }

    /** Generates an object literal with nested properties. */
    private Doc objectLiteral(int complexity, int depth) {
      int propertyCount = 2 + random.nextInt(Math.min(6, complexity));
      List<Doc> properties = new ArrayList<>();

      for (int i = 0; i < propertyCount; i++) {
        String key = randomIdentifier();
        Doc value;

        if (random.nextInt(10) < 6 && complexity > 1) {
          value = nestedStructure(complexity / 2, depth + 1);
        } else {
          value =
              random.nextInt(10) < 3 ? longExpression() : simpleExpression();
        }

        properties.add(concat(text(key + ": "), value));
      }

      return group(
          concat(
              text("{"),
              nest(
                  2,
                  concat(line(), join(concat(text(","), line()), properties))),
              line(),
              text("}")));
    }

    /** Generates a deeply nested parenthesized expression. */
    private Doc nestedExpression(int complexity, int depth) {
      int termCount = 2 + random.nextInt(Math.min(5, complexity));
      List<Doc> terms = new ArrayList<>();

      for (int i = 0; i < termCount; i++) {
        if (random.nextInt(10) < 5 && complexity > 1) {
          // Wrap in parentheses to create more grouping decisions
          Doc inner = nestedStructure(complexity / 2, depth + 1);
          terms.add(
              group(
                  concat(
                      text("("),
                      nest(1, concat(line(), inner)),
                      line(),
                      text(")"))));
        } else {
          terms.add(simpleExpression());
        }
      }

      // Join with random operators
      List<Doc> result = new ArrayList<>();
      for (int i = 0; i < terms.size(); i++) {
        if (i > 0) {
          result.add(concat(text(" " + randomOperator() + " "), line()));
        }
        result.add(terms.get(i));
      }

      return group(concat(result.toArray(new Doc[0])));
    }

    /** Generates a conditional expression with nested conditions. */
    private Doc conditional(int complexity, int depth) {
      Doc condition =
          complexity > 1
              ? nestedStructure(complexity / 2, depth + 1)
              : simpleExpression();

      Doc thenBranch =
          complexity > 1
              ? nestedStructure(complexity / 2, depth + 1)
              : simpleExpression();

      Doc elseBranch =
          complexity > 1
              ? nestedStructure(complexity / 2, depth + 1)
              : simpleExpression();

      return group(
          concat(
              condition,
              nest(2, concat(line(), text("? "), thenBranch)),
              nest(2, concat(line(), text(": "), elseBranch))));
    }

    /**
     * Generates a chained method call. This stresses horizontal vs vertical
     * layout decisions.
     */
    private Doc chainedCall(int complexity, int depth) {
      int chainLength = 2 + random.nextInt(Math.min(6, complexity));
      Doc result = text(randomIdentifier());

      for (int i = 0; i < chainLength; i++) {
        String methodName = randomIdentifier();

        // Sometimes add parameters to make it more complex
        if (random.nextInt(10) < 4) {
          List<Doc> params = new ArrayList<>();
          int paramCount = 1 + random.nextInt(3);
          for (int j = 0; j < paramCount; j++) {
            params.add(simpleExpression());
          }

          result =
              group(
                  concat(
                      result,
                      line(),
                      text("." + methodName + "("),
                      join(concat(text(", "), line()), params),
                      text(")")));
        } else {
          result = group(concat(result, line(), text("." + methodName + "()")));
        }
      }

      return result;
    }

    /**
     * Generates an intentionally long expression that probably won't fit on one
     * line.
     */
    private Doc longExpression() {
      StringBuilder sb = new StringBuilder();
      int length = 15 + random.nextInt(25);

      while (sb.length() < length) {
        if (sb.length() > 0) {
          sb.append("_");
        }
        sb.append(randomIdentifier());
      }

      return text(sb.toString());
    }

    /**
     * Generates a simple expression, either a number, string, or identifier.
     */
    private Doc simpleExpression() {
      if (random.nextInt(10) < 3) {
        return text(String.valueOf(random.nextInt(10000)));
      } else if (random.nextInt(10) < 5) {
        return text("\"" + randomIdentifier() + "\"");
      } else {
        return text(randomIdentifier());
      }
    }

    /** Generates a random identifier. */
    private String randomIdentifier() {
      return identifiers[random.nextInt(identifiers.length)]
          + random.nextInt(100);
    }

    /** Generates a random operator. */
    private String randomOperator() {
      return operators[random.nextInt(operators.length)];
    }
  }
}

// End WadlerTest.java
