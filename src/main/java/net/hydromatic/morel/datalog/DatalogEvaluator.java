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

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import net.hydromatic.morel.ast.AstNode;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.compile.CompileException;
import net.hydromatic.morel.compile.CompiledStatement;
import net.hydromatic.morel.compile.Compiles;
import net.hydromatic.morel.compile.Environment;
import net.hydromatic.morel.compile.Environments;
import net.hydromatic.morel.compile.Tracers;
import net.hydromatic.morel.datalog.DatalogAst.Program;
import net.hydromatic.morel.eval.Session;
import net.hydromatic.morel.eval.Variant;
import net.hydromatic.morel.parse.MorelParserImpl;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;

/**
 * Evaluator for Datalog programs.
 *
 * <p>Orchestrates the pipeline: parse → analyze → translate → compile →
 * execute.
 *
 * <p>Translation to Morel source code is handled by {@link DatalogTranslator}.
 */
public class DatalogEvaluator {
  private final CompiledStatement compiled;
  private final Session session;
  private final Environment environment;
  private final String morelSource;

  private DatalogEvaluator(
      CompiledStatement compiled,
      Session session,
      Environment environment,
      String morelSource) {
    this.compiled = compiled;
    this.session = session;
    this.environment = environment;
    this.morelSource = morelSource;
  }

  /**
   * Validates and compiles a Datalog program.
   *
   * @param program the Datalog program source code
   * @param session the Morel session
   * @return a compiled DatalogEvaluator instance
   * @throws DatalogException if the program is invalid
   */
  private static DatalogEvaluator compile(String program, Session session) {
    try {
      // 1. Parse Datalog program
      Program ast = DatalogParserImpl.parse(program);

      // 2. Analyze for safety and stratification
      DatalogAnalyzer.analyze(ast);

      // 3. Translate to Morel source code
      String morelSource = DatalogTranslator.translate(ast);

      // 4. Parse the Morel source
      MorelParserImpl parser =
          new MorelParserImpl(new StringReader(morelSource));
      AstNode statement = parser.statementEofSafe();

      // 5. Compile the statement
      final TypeSystem typeSystem = requireNonNull(session.typeSystem);
      final Environment env =
          Environments.env(typeSystem, session, ImmutableMap.of());
      final List<CompileException> warnings = new ArrayList<>();
      CompiledStatement compiled =
          Compiles.prepareStatement(
              typeSystem,
              session,
              env,
              statement,
              null,
              warnings::add,
              Tracers.empty());

      return new DatalogEvaluator(compiled, session, env, morelSource);

    } catch (ParseException | TokenMgrError e) {
      throw new DatalogException(format("Parse error: %s", e.getMessage()), e);
    } catch (Exception e) {
      throw new DatalogException(
          format("Compilation error: %s", e.getMessage()), e);
    }
  }

  /**
   * Executes the compiled Datalog program.
   *
   * @return variant containing structured data for output relations
   */
  private Variant executeCompiled() {
    try {
      List<String> outLines = new ArrayList<>();
      List<Binding> bindings = new ArrayList<>();
      compiled.eval(session, environment, outLines::add, bindings::add);

      if (bindings.isEmpty()) {
        throw new DatalogException("No bindings produced from Morel execution");
      }

      Object result = bindings.get(bindings.size() - 1).value;
      Type resultType = compiled.getType();
      return Variant.of(resultType, result);
    } catch (Exception e) {
      throw new DatalogException(
          format(
              "Error executing Morel translation: %s%n"
                  + "Generated Morel code:%n%s",
              e.getMessage(), morelSource),
          e);
    }
  }

  /**
   * Executes a Datalog program and returns structured output as a variant.
   *
   * @param program the Datalog program source code
   * @param session the Morel session
   * @return variant containing structured data for output relations
   * @throws DatalogException if the program is invalid
   */
  public static Variant execute(String program, Session session) {
    DatalogEvaluator evaluator = compile(program, session);
    return evaluator.executeCompiled();
  }

  /**
   * Validates a Datalog program and returns a string representation of the
   * output type.
   *
   * @param program the Datalog program source code
   * @param session the Morel session
   * @return string representation of the type, or error message
   */
  public static String validate(String program, Session session) {
    try {
      DatalogEvaluator evaluator = compile(program, session);
      Type type = evaluator.compiled.getType();
      return type.toString();
    } catch (DatalogException e) {
      return e.getMessage();
    }
  }

  /**
   * Translates a Datalog program to Morel source code.
   *
   * <p>Returns an option value: SOME(morelSource) if the program is valid, NONE
   * if parsing fails.
   *
   * @param program the Datalog program source code
   * @param session the Morel session (unused, kept for API consistency)
   * @return option value containing the Morel source code or error info
   */
  public static Object translate(String program, Session session) {
    try {
      DatalogEvaluator evaluator = compile(program, session);
      return ImmutableList.of(
          BuiltIn.Constructor.OPTION_SOME.constructor, evaluator.morelSource);
    } catch (DatalogException | UnsupportedOperationException e) {
      // For debugging, try to get the translated source even on error
      try {
        Program ast = DatalogParserImpl.parse(program);
        DatalogAnalyzer.analyze(ast);
        String morelSource = DatalogTranslator.translate(ast);
        return ImmutableList.of(
            BuiltIn.Constructor.OPTION_SOME.constructor,
            "ERROR: "
                + e.getMessage()
                + "\n"
                + "\n"
                + "Generated:\n"
                + morelSource);
      } catch (Exception e2) {
        return ImmutableList.of("NONE");
      }
    }
  }
}

// End DatalogEvaluator.java
