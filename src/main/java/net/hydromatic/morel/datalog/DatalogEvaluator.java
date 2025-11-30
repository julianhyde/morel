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
package net.hydromatic.morel.datalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.hydromatic.morel.datalog.DatalogAst.Atom;
import net.hydromatic.morel.datalog.DatalogAst.Constant;
import net.hydromatic.morel.datalog.DatalogAst.Fact;
import net.hydromatic.morel.datalog.DatalogAst.Output;
import net.hydromatic.morel.datalog.DatalogAst.Program;
import net.hydromatic.morel.datalog.DatalogAst.Statement;
import net.hydromatic.morel.datalog.DatalogAst.Term;
import net.hydromatic.morel.datalog.DatalogAst.Variable;
import net.hydromatic.morel.eval.Session;
import net.hydromatic.morel.type.Type;

/**
 * Evaluator for Datalog programs.
 *
 * <p>Orchestrates parsing, analysis, translation, and execution of Datalog
 * programs.
 */
public class DatalogEvaluator {
  private DatalogEvaluator() {
    // Utility class
  }

  /**
   * Executes a Datalog program and returns formatted output.
   *
   * @param program the Datalog program source code
   * @param session the Morel session
   * @return formatted output for relations marked with .output
   * @throws DatalogException if the program is invalid
   */
  public static String execute(String program, Session session) {
    try {
      // 1. Parse Datalog program
      Program ast = DatalogParserImpl.parse(program);

      // 2. Analyze for safety and stratification
      DatalogAnalyzer.analyze(ast);

      // 3. Collect facts by relation
      Map<String, List<Fact>> factsByRelation = new LinkedHashMap<>();
      for (Statement stmt : ast.statements) {
        if (stmt instanceof Fact) {
          Fact fact = (Fact) stmt;
          factsByRelation
              .computeIfAbsent(fact.atom.name, k -> new ArrayList<>())
              .add(fact);
        }
      }

      // 4. Format output for each .output directive
      StringBuilder result = new StringBuilder();
      for (Output output : ast.getOutputs()) {
        String relationName = output.relationName;
        List<Fact> facts =
            factsByRelation.getOrDefault(relationName, new ArrayList<>());

        // Output relation name
        if (result.length() > 0) {
          result.append("\n");
        }
        result.append(relationName).append("\n");

        // Output each fact's tuple
        for (Fact fact : facts) {
          result.append(formatTuple(fact.atom)).append("\n");
        }
      }

      return result.toString().trim();

    } catch (ParseException e) {
      throw new DatalogException("Parse error: " + e.getMessage(), e);
    }
  }

  /** Formats a tuple for output. */
  private static String formatTuple(Atom atom) {
    return atom.terms.stream()
        .map(DatalogEvaluator::formatTerm)
        .collect(Collectors.joining(" "));
  }

  /** Formats a single term for output. */
  private static String formatTerm(Term term) {
    if (term instanceof Constant) {
      Constant constant = (Constant) term;
      Object value = constant.value;
      if (value instanceof String) {
        // String values should be quoted in output
        return "\"" + value + "\"";
      }
      return String.valueOf(value);
    } else if (term instanceof Variable) {
      // Variables in facts are treated as symbols
      return ((Variable) term).name;
    }
    return term.toString();
  }

  /**
   * Validates a Datalog program and returns type information or error message.
   *
   * @param program the Datalog program source code
   * @param session the Morel session
   * @return "string" if valid, or "Error: message" if invalid
   */
  public static String validate(String program, Session session) {
    try {
      // 1. Parse Datalog program
      Program ast = DatalogParserImpl.parse(program);

      // 2. Analyze for safety and stratification
      DatalogAnalyzer.analyze(ast);

      // 3. Determine output type
      // For now, all Datalog programs that execute successfully return strings
      return "string";

    } catch (ParseException e) {
      return "Error: Parse error: " + e.getMessage();
    } catch (DatalogException e) {
      return "Error: " + e.getMessage();
    }
  }

  /**
   * Validates a Datalog program and returns the Morel type.
   *
   * <p>This is an internal method used by validate() to get the actual Type
   * object.
   *
   * @param program the Datalog program source code
   * @param session the Morel session
   * @return the Morel type representing the program's output
   * @throws DatalogException if the program is invalid
   */
  public static Type validateAndGetType(String program, Session session) {
    try {
      // 1. Parse Datalog program
      Program ast = DatalogParserImpl.parse(program);

      // 2. Analyze for safety and stratification
      DatalogAnalyzer.analyze(ast);

      // 3. Return type
      // All Datalog execute() calls return formatted strings
      return net.hydromatic.morel.type.PrimitiveType.STRING;

    } catch (ParseException e) {
      throw new DatalogException("Parse error: " + e.getMessage(), e);
    }
  }
}

// End DatalogEvaluator.java
