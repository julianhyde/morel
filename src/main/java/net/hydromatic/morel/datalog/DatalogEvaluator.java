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
import net.hydromatic.morel.datalog.DatalogAst.Atom;
import net.hydromatic.morel.datalog.DatalogAst.BodyAtom;
import net.hydromatic.morel.datalog.DatalogAst.CompOp;
import net.hydromatic.morel.datalog.DatalogAst.Comparison;
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
      Environment env = Environments.empty();
      List<CompileException> warnings = new ArrayList<>();
      CompiledStatement compiled =
          Compiles.prepareStatement(
              session.typeSystem,
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

    // Build declaration map
    Map<String, Declaration> declarationMap = new LinkedHashMap<>();
    for (Declaration decl : ast.getDeclarations()) {
      declarationMap.put(decl.name, decl);
    }

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

      // Helper: check if two lists have same length
      morel.append(
          "  fun sameLength [] [] = true | sameLength (_::xs) (_::ys) = sameLength xs ys | sameLength _ _ = false\n");

      // Generate val declarations for each relation
      // First pass: generate fact-only relations (these can be referenced by
      // rules)
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
        }
      }

      // Second pass: generate rule-based relations
      for (Declaration decl : ast.getDeclarations()) {
        List<Fact> facts =
            factsByRelation.getOrDefault(decl.name, new ArrayList<>());
        List<Rule> rules =
            rulesByRelation.getOrDefault(decl.name, new ArrayList<>());

        if (!rules.isEmpty()) {
          // Has rules - generate fixpoint iteration
          morel.append(
              translateRulesToFixpoint(decl, facts, rules, declarationMap));
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
   * Translates rules to Morel fixpoint iteration code.
   *
   * <p>Generates code like:
   *
   * <pre>
   * fun iterate_path prev =
   *   let
   *     val rule1 = edge
   *     val rule2 = from p in prev, e in edge where ... yield ...
   *     val new = List.removeDuplicates (rule1 @ rule2)
   *     val combined = List.removeDuplicates (prev @ new)
   *   in
   *     if List.length combined = List.length prev then
   *       combined
   *     else
   *       iterate_path combined
   *   end
   * val path = iterate_path []
   * </pre>
   */
  private static String translateRulesToFixpoint(
      Declaration decl,
      List<Fact> facts,
      List<Rule> rules,
      Map<String, Declaration> declarationMap) {
    StringBuilder sb = new StringBuilder();
    String relationName = decl.name;

    // Generate iteration function
    sb.append("  fun iterate_")
        .append(relationName)
        .append(" prev =\n")
        .append("    let\n");

    // Generate code for each rule
    for (int i = 0; i < rules.size(); i++) {
      Rule rule = rules.get(i);
      sb.append("      val rule").append(i + 1).append(" = ");
      sb.append(translateRule(rule, decl, relationName, declarationMap));
      sb.append("\n");
    }

    // Combine all rule results
    sb.append("      val new = ");
    if (rules.size() == 1) {
      sb.append("rule1");
    } else {
      for (int i = 0; i < rules.size(); i++) {
        if (i > 0) {
          sb.append(" @ ");
        }
        sb.append("rule").append(i + 1);
      }
    }
    sb.append("\n");

    // Combine with previous results
    sb.append("      val combined = prev @ new\n");

    // Check for fixpoint
    sb.append("    in\n");
    sb.append("      if sameLength combined prev then\n");
    sb.append("        combined\n");
    sb.append("      else\n");
    sb.append("        iterate_").append(relationName).append(" combined\n");
    sb.append("    end\n");

    // Initialize with empty or facts
    sb.append("  val ").append(relationName).append(" = iterate_");
    sb.append(relationName).append(" ");
    if (facts.isEmpty()) {
      sb.append("[]");
    } else {
      sb.append(translateFactsToList(decl, facts));
    }
    sb.append("\n");

    return sb.toString();
  }

  /**
   * Translates a single Datalog rule to Morel code.
   *
   * <p>Examples:
   *
   * <ul>
   *   <li>{@code path(X,Y) :- edge(X,Y)} → {@code edge}
   *   <li>{@code path(X,Z) :- path(X,Y), edge(Y,Z)} → {@code from p in prev, e
   *       in edge where #y p = #x e yield {x = #x p, y = #y e}}
   * </ul>
   */
  private static String translateRule(
      Rule rule,
      Declaration decl,
      String relationName,
      Map<String, Declaration> declarationMap) {
    // Build variable mapping from head
    Map<String, String> headVars = new LinkedHashMap<>();
    for (int i = 0; i < rule.head.terms.size(); i++) {
      Term term = rule.head.terms.get(i);
      if (term instanceof Variable) {
        Variable var = (Variable) term;
        String paramName = decl.params.get(i).name;
        headVars.put(var.name, paramName);
      }
    }

    // Check if rule body contains only one atom that matches entire relation
    if (rule.body.size() == 1) {
      BodyAtom bodyAtom = rule.body.get(0);
      if (!(bodyAtom instanceof Comparison) && !bodyAtom.negated) {
        Atom atom = bodyAtom.atom;
        // Check if body atom variables match head variables in order
        boolean simpleMatch = true;
        if (atom.terms.size() == rule.head.terms.size()) {
          for (int i = 0; i < atom.terms.size(); i++) {
            Term bodyTerm = atom.terms.get(i);
            Term headTerm = rule.head.terms.get(i);
            if (!(bodyTerm instanceof Variable)
                || !(headTerm instanceof Variable)) {
              simpleMatch = false;
              break;
            }
            if (!((Variable) bodyTerm)
                .name.equals(((Variable) headTerm).name)) {
              simpleMatch = false;
              break;
            }
          }
        } else {
          simpleMatch = false;
        }

        // If it's a simple copy, just reference the relation
        if (simpleMatch) {
          // Use "prev" if the body references the recursive relation
          if (atom.name.equals(relationName)) {
            return "prev";
          } else {
            return atom.name;
          }
        }
      }
    }

    // Build from expression for complex rules
    StringBuilder sb = new StringBuilder();
    sb.append("from ");

    // Generate bindings for each body atom
    List<String> bindings = new ArrayList<>();
    Map<String, String> atomVars =
        new LinkedHashMap<>(); // Maps variable name to atom.field reference
    int atomIndex = 0;

    for (BodyAtom bodyAtom : rule.body) {
      if (bodyAtom instanceof Comparison) {
        // Handle comparisons later in where clause
        continue;
      }

      Atom atom = bodyAtom.atom;
      String atomAlias = String.valueOf((char) ('a' + atomIndex));
      atomIndex++;

      // Determine source (use "prev" for recursive references)
      String source = atom.name.equals(relationName) ? "prev" : atom.name;

      bindings.add(atomAlias + " in " + source);

      // Track variables from this atom
      for (int i = 0; i < atom.terms.size(); i++) {
        Term term = atom.terms.get(i);
        if (term instanceof Variable) {
          Variable var = (Variable) term;
          String paramName = getParamName(atom, i, declarationMap);
          String ref =
              (decl.arity() == 1)
                  ? atomAlias
                  : "#" + paramName + " " + atomAlias;
          atomVars.putIfAbsent(var.name, ref);
        }
      }
    }

    sb.append(String.join(", ", bindings));

    // Generate where clause for join conditions and comparisons
    List<String> whereConditions = new ArrayList<>();

    // Add join conditions (variables appearing in multiple atoms)
    Map<String, List<String>> varLocations = new LinkedHashMap<>();
    atomIndex = 0;
    for (BodyAtom bodyAtom : rule.body) {
      if (bodyAtom instanceof Comparison) {
        continue;
      }
      Atom atom = bodyAtom.atom;
      String atomAlias = String.valueOf((char) ('a' + atomIndex));
      atomIndex++;

      for (int i = 0; i < atom.terms.size(); i++) {
        Term term = atom.terms.get(i);
        if (term instanceof Variable) {
          Variable var = (Variable) term;
          String paramName = getParamName(atom, i, declarationMap);
          String ref =
              (getArity(atom) == 1)
                  ? atomAlias
                  : "#" + paramName + " " + atomAlias;
          varLocations
              .computeIfAbsent(var.name, k -> new ArrayList<>())
              .add(ref);
        }
      }
    }

    // For variables that appear in multiple places, add equality conditions
    for (Map.Entry<String, List<String>> entry : varLocations.entrySet()) {
      List<String> refs = entry.getValue();
      for (int i = 1; i < refs.size(); i++) {
        whereConditions.add(refs.get(0) + " = " + refs.get(i));
      }
    }

    // Add comparison conditions
    for (BodyAtom bodyAtom : rule.body) {
      if (bodyAtom instanceof Comparison) {
        Comparison comp = (Comparison) bodyAtom;
        String leftRef = termToReference(comp.left, atomVars);
        String rightRef = termToReference(comp.right, atomVars);
        String op = compOpToMorel(comp.op);
        whereConditions.add(leftRef + " " + op + " " + rightRef);
      }
    }

    if (!whereConditions.isEmpty()) {
      sb.append(" where ").append(String.join(" andalso ", whereConditions));
    }

    // Generate yield clause
    sb.append(" yield ");
    if (decl.arity() == 1) {
      String varName = ((Variable) rule.head.terms.get(0)).name;
      sb.append(atomVars.get(varName));
    } else {
      sb.append("{");
      for (int i = 0; i < rule.head.terms.size(); i++) {
        if (i > 0) {
          sb.append(", ");
        }
        String paramName = decl.params.get(i).name;
        String varName = ((Variable) rule.head.terms.get(i)).name;
        sb.append(paramName).append(" = ").append(atomVars.get(varName));
      }
      sb.append("}");
    }

    return sb.toString();
  }

  /** Helper to get parameter name for an atom's position. */
  private static String getParamName(
      Atom atom, int index, Map<String, Declaration> declarationMap) {
    Declaration atomDecl = declarationMap.get(atom.name);
    if (atomDecl != null && index < atomDecl.params.size()) {
      return atomDecl.params.get(index).name;
    }
    // Fallback to generic names if declaration not found
    return "x" + index;
  }

  /** Helper to get arity of an atom. */
  private static int getArity(Atom atom) {
    return atom.terms.size();
  }

  /** Helper to convert a term to a Morel reference expression. */
  private static String termToReference(
      Term term, Map<String, String> atomVars) {
    if (term instanceof Variable) {
      Variable var = (Variable) term;
      return atomVars.get(var.name);
    } else if (term instanceof Constant) {
      return termToMorel(term);
    }
    throw new IllegalArgumentException("Unknown term type: " + term);
  }

  /** Helper to convert comparison operator to Morel syntax. */
  private static String compOpToMorel(CompOp op) {
    switch (op) {
      case EQ:
        return "=";
      case NE:
        return "<>";
      case LT:
        return "<";
      case LE:
        return "<=";
      case GT:
        return ">";
      case GE:
        return ">=";
      default:
        throw new IllegalArgumentException("Unknown operator: " + op);
    }
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
