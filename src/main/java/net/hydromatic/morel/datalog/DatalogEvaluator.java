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

import net.hydromatic.morel.datalog.DatalogAst.Program;
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

      // 3. Translate to Core (currently throws UnsupportedOperationException)
      // Map<String, Core.Exp> relations =
      //     DatalogToCoreTranslator.translate(ast, session.typeSystem);

      // 4. Compile and evaluate
      // TODO: Implement compilation and evaluation

      // 5. Format output
      // TODO: Implement output formatting

      throw new UnsupportedOperationException(
          "Datalog execution not yet fully implemented. "
              + "Parser and analyzer are working. "
              + "Translation to Core and execution are pending.");

    } catch (ParseException e) {
      throw new DatalogException("Parse error: " + e.getMessage(), e);
    }
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
