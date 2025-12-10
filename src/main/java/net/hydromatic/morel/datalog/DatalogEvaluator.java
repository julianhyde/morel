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

import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.hydromatic.morel.ast.AstNode;
import net.hydromatic.morel.compile.CompileException;
import net.hydromatic.morel.compile.CompiledStatement;
import net.hydromatic.morel.compile.Compiles;
import net.hydromatic.morel.compile.Environment;
import net.hydromatic.morel.compile.Environments;
import net.hydromatic.morel.compile.Tracers;
import net.hydromatic.morel.datalog.DatalogAst.Constant;
import net.hydromatic.morel.datalog.DatalogAst.Declaration;
import net.hydromatic.morel.datalog.DatalogAst.Fact;
import net.hydromatic.morel.datalog.DatalogAst.Output;
import net.hydromatic.morel.datalog.DatalogAst.Param;
import net.hydromatic.morel.datalog.DatalogAst.Program;
import net.hydromatic.morel.datalog.DatalogAst.Rule;
import net.hydromatic.morel.datalog.DatalogAst.Statement;
import net.hydromatic.morel.datalog.DatalogAst.Term;
import net.hydromatic.morel.datalog.DatalogAst.Variable;
import net.hydromatic.morel.eval.Session;
import net.hydromatic.morel.eval.Variant;
import net.hydromatic.morel.parse.MorelParserImpl;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;

/**
 * Evaluator for Datalog programs.
 *
 * <p>Translates Datalog programs to Morel code and executes them through
 * Morel's compilation pipeline.
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
   * <p>This method parses, analyzes, translates to Morel, and compiles the
   * program, returning an instance that can be executed.
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
      String morelSource = translateToMorelSource(ast);

      // 4. Parse the Morel source
      MorelParserImpl parser =
          new MorelParserImpl(new StringReader(morelSource));
      AstNode statement = parser.statementEofSafe();

      // 5. Compile the statement
      TypeSystem typeSystem = new TypeSystem();
      Environment env = Environments.empty();
      List<CompileException> warnings = new ArrayList<>();
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

    } catch (ParseException e) {
      throw new DatalogException(
          String.format("Parse error: %s", e.getMessage()), e);
    } catch (Exception e) {
      throw new DatalogException(
          String.format("Compilation error: %s", e.getMessage()), e);
    }
  }

  /**
   * Executes the compiled Datalog program.
   *
   * @return variant containing structured data for relations marked with {@code
   *     .output}
   */
  private Variant executeCompiled() {
    try {
      // Evaluate and capture the binding
      List<String> outLines = new ArrayList<>();
      List<Binding> bindings = new ArrayList<>();
      compiled.eval(session, environment, outLines::add, bindings::add);

      // The last binding should be "it" with the value
      if (bindings.isEmpty()) {
        throw new DatalogException("No bindings produced from Morel execution");
      }

      Object result = bindings.get(bindings.size() - 1).value;

      // Wrap the result in a Variant using the compiled statement's type
      Type resultType = compiled.getType();
      return Variant.of(resultType, result);
    } catch (Exception e) {
      throw new DatalogException(
          String.format(
              "Error executing Morel translation: %s\n"
                  + "Generated Morel code:\n"
                  + "%s",
              e.getMessage(), morelSource),
          e);
    }
  }

  /**
   * Executes a Datalog program and returns structured output as a variant.
   *
   * @param program the Datalog program source code
   * @param session the Morel session
   * @return variant containing structured data for relations marked with {@code
   *     .output}
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
   * <p>Returns an option value: SOME(morelSource) if the program is valid and
   * can be translated, NONE if the program is invalid.
   *
   * @param program the Datalog program source code
   * @param session the Morel session
   * @return option value containing the Morel source code or NONE
   */
  public static Object translate(String program, Session session) {
    try {
      DatalogEvaluator evaluator = compile(program, session);
      // Return SOME(morelSource)
      return com.google.common.collect.ImmutableList.of(
          net.hydromatic.morel.compile.BuiltIn.Constructor.OPTION_SOME
              .constructor,
          evaluator.morelSource);
    } catch (DatalogException | UnsupportedOperationException e) {
      // Return NONE for any error
      return com.google.common.collect.ImmutableList.of("NONE");
    }
  }

  /**
   * Translates a Datalog program to Morel source code.
   *
   * <p>Translation strategy:
   *
   * <ul>
   *   <li>Facts → List literals
   *   <li>Rules → from expressions (or recursive fixpoint for transitive
   *       closure)
   *   <li>Output → return record with output relations
   * </ul>
   */
  private static String translateToMorelSource(Program ast) {
    StringBuilder morel = new StringBuilder();

    // Group facts by relation
    Map<String, List<Fact>> factsByRelation = new LinkedHashMap<>();
    Map<String, List<Rule>> rulesByRelation = new LinkedHashMap<>();

    for (Statement stmt : ast.statements) {
      if (stmt instanceof Fact) {
        Fact fact = (Fact) stmt;
        factsByRelation
            .computeIfAbsent(fact.atom.name, k -> new ArrayList<>())
            .add(fact);
      } else if (stmt instanceof Rule) {
        Rule rule = (Rule) stmt;
        rulesByRelation
            .computeIfAbsent(rule.head.name, k -> new ArrayList<>())
            .add(rule);
      }
    }

    // Check if we need a let expression (when there are facts or rules)
    boolean hasDeclarations = false;
    for (Declaration decl : ast.getDeclarations()) {
      List<Fact> facts =
          factsByRelation.getOrDefault(decl.name, new ArrayList<>());
      List<Rule> rules =
          rulesByRelation.getOrDefault(decl.name, new ArrayList<>());

      if (!facts.isEmpty() || !rules.isEmpty()) {
        hasDeclarations = true;
        break;
      }
    }

    if (hasDeclarations) {
      // Start let expression
      morel.append("let\n");

      // Generate val declarations for each relation
      for (Declaration decl : ast.getDeclarations()) {
        List<Fact> facts =
            factsByRelation.getOrDefault(decl.name, new ArrayList<>());
        List<Rule> rules =
            rulesByRelation.getOrDefault(decl.name, new ArrayList<>());

        if (!facts.isEmpty() && rules.isEmpty()) {
          // Facts only - simple list literal
          morel.append("  val ").append(decl.name).append(" = ");
          morel.append(translateFactsToList(decl, facts));
          morel.append("\n");
        } else if (!rules.isEmpty()) {
          // Has rules - need more complex translation
          // For now, throw error for unsupported features
          throw new UnsupportedOperationException(
              "Rules not yet supported in Morel translation: " + decl.name);
        }
      }

      // in clause with output record
      morel.append("in\n").append("  ");
    }

    List<Output> outputs = ast.getOutputs();
    if (outputs.isEmpty()) {
      morel.append("()");
    } else if (outputs.size() == 1) {
      String relName = outputs.get(0).relationName;
      morel.append("{").append(relName).append("=");
      if (hasDeclarations) {
        morel.append(relName);
      } else {
        // Empty relation
        morel.append("[]");
      }
      morel.append("}");
    } else {
      morel.append("{");
      for (int i = 0; i < outputs.size(); i++) {
        if (i > 0) {
          morel.append(", ");
        }
        String relName = outputs.get(i).relationName;
        morel.append(relName).append("=");
        if (hasDeclarations) {
          morel.append(relName);
        } else {
          // Empty relation
          morel.append("[]");
        }
      }
      morel.append("}");
    }

    // end clause (only if we started with let)
    if (hasDeclarations) {
      morel.append("\n").append("end");
    }

    return morel.toString();
  }

  /**
   * Translates a list of facts to a Morel list literal.
   *
   * <p>Example: {@code edge(1,2). edge(2,3).} becomes {@code [{x=1,y=2},
   * {x=2,y=3}]}
   */
  private static String translateFactsToList(
      Declaration decl, List<Fact> facts) {
    StringBuilder sb = new StringBuilder("[");

    for (int i = 0; i < facts.size(); i++) {
      if (i > 0) {
        sb.append(", ");
      }
      Fact fact = facts.get(i);

      if (decl.arity() == 1) {
        // Single value - no tuple
        sb.append(termToMorel(fact.atom.terms.get(0)));
      } else {
        // Multiple values - record with field names
        sb.append("{");
        for (int j = 0; j < fact.atom.terms.size(); j++) {
          if (j > 0) {
            sb.append(", ");
          }
          Param param = decl.params.get(j);
          sb.append(param.name).append("=");
          sb.append(termToMorel(fact.atom.terms.get(j)));
        }
        sb.append("}");
      }
    }

    sb.append("]");
    return sb.toString();
  }

  /**
   * Converts a Datalog term to Morel syntax.
   *
   * @param term the term to convert
   * @return the Morel representation
   */
  private static String termToMorel(Term term) {
    if (term instanceof Constant) {
      Constant constant = (Constant) term;
      Object value = constant.value;
      if (value instanceof Number) {
        return value.toString();
      } else if (value instanceof String) {
        return "\""
            + ((String) value).replace("\\", "\\\\").replace("\"", "\\\"")
            + "\"";
      }
    } else if (term instanceof Variable) {
      // Variables in facts are treated as symbols (strings)
      Variable var = (Variable) term;
      return "\"" + var.name + "\"";
    }
    throw new IllegalArgumentException("Cannot convert term to Morel: " + term);
  }
}

// End DatalogEvaluator.java
