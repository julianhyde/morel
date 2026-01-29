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

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.hydromatic.morel.datalog.DatalogAst.ArithOp;
import net.hydromatic.morel.datalog.DatalogAst.ArithmeticExpr;
import net.hydromatic.morel.datalog.DatalogAst.Atom;
import net.hydromatic.morel.datalog.DatalogAst.BodyAtom;
import net.hydromatic.morel.datalog.DatalogAst.CompOp;
import net.hydromatic.morel.datalog.DatalogAst.Comparison;
import net.hydromatic.morel.datalog.DatalogAst.Constant;
import net.hydromatic.morel.datalog.DatalogAst.Declaration;
import net.hydromatic.morel.datalog.DatalogAst.Fact;
import net.hydromatic.morel.datalog.DatalogAst.Output;
import net.hydromatic.morel.datalog.DatalogAst.Program;
import net.hydromatic.morel.datalog.DatalogAst.Rule;
import net.hydromatic.morel.datalog.DatalogAst.Statement;
import net.hydromatic.morel.datalog.DatalogAst.Term;
import net.hydromatic.morel.datalog.DatalogAst.Variable;

/**
 * Translates Datalog programs to Morel source code.
 *
 * <p>Translation strategy:
 *
 * <ul>
 *   <li>Facts → List literals
 *   <li>Rules → Predicate functions using from/where/exists expressions
 *   <li>Output → Record with output relations enumerated via from expressions
 * </ul>
 *
 * <p>For recursive rules (transitive closure), generates predicate functions
 * that Morel can optimize. For non-recursive rules, generates direct from
 * expressions.
 */
public class DatalogTranslator {

  private DatalogTranslator() {}

  /**
   * Translates a Datalog program to Morel source code.
   *
   * @param ast the parsed Datalog program
   * @return Morel source code string
   */
  public static String translate(Program ast) {
    StringBuilder morel = new StringBuilder();

    // Build declaration map
    Map<String, Declaration> declarationMap = new LinkedHashMap<>();
    for (Declaration decl : ast.getDeclarations()) {
      declarationMap.put(decl.name, decl);
    }

    // Group facts and rules by relation
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

      // First pass: generate fact-only relations as predicate functions
      for (Declaration decl : ast.getDeclarations()) {
        List<Fact> facts =
            factsByRelation.getOrDefault(decl.name, new ArrayList<>());
        List<Rule> rules =
            rulesByRelation.getOrDefault(decl.name, new ArrayList<>());

        if (!facts.isEmpty() && rules.isEmpty()) {
          // Facts only - generate facts list and predicate function
          morel.append("  val ").append(decl.name).append("_facts = ");
          morel.append(translateFactsToList(decl, facts));
          morel.append("\n");

          // Generate predicate function
          morel.append("  fun ").append(decl.name).append(" ");
          appendParams(morel, decl);
          morel.append(" = ");
          appendElemCheck(morel, decl);
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
          morel.append(
              translateRulesToPredicates(decl, facts, rules, declarationMap));
        }
      }

      // in clause with output record
      morel.append("in\n").append("  ");
    }

    // Generate output expression
    List<Output> outputs = ast.getOutputs();
    if (outputs.isEmpty()) {
      morel.append("()");
    } else {
      morel.append("{");
      for (int i = 0; i < outputs.size(); i++) {
        if (i > 0) {
          morel.append(", ");
        }
        String relName = outputs.get(i).relationName;
        morel.append(relName).append(" = ");

        Declaration decl = declarationMap.get(relName);
        List<Fact> facts =
            factsByRelation.getOrDefault(relName, new ArrayList<>());
        List<Rule> rules =
            rulesByRelation.getOrDefault(relName, new ArrayList<>());

        if (!facts.isEmpty() || !rules.isEmpty()) {
          // Generate from ... where expression
          morel.append("from ");
          for (int j = 0; j < decl.params.size(); j++) {
            if (j > 0) {
              morel.append(", ");
            }
            morel.append(decl.params.get(j).name);
          }
          morel.append(" where ").append(relName);
          if (decl.arity() == 1) {
            morel.append(" ").append(decl.params.get(0).name);
          } else {
            morel.append(" (");
            for (int j = 0; j < decl.params.size(); j++) {
              if (j > 0) {
                morel.append(", ");
              }
              morel.append(decl.params.get(j).name);
            }
            morel.append(")");
          }
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

  /** Appends parameter pattern to the builder. */
  private static void appendParams(StringBuilder sb, Declaration decl) {
    if (decl.arity() == 1) {
      sb.append(decl.params.get(0).name);
    } else {
      sb.append("(");
      for (int i = 0; i < decl.params.size(); i++) {
        if (i > 0) {
          sb.append(", ");
        }
        sb.append(decl.params.get(i).name);
      }
      sb.append(")");
    }
  }

  /** Appends elem check expression to the builder. */
  private static void appendElemCheck(StringBuilder sb, Declaration decl) {
    String factsName = decl.name + "_facts";
    if (decl.arity() == 1) {
      sb.append(decl.params.get(0).name).append(" elem ").append(factsName);
    } else {
      sb.append("(");
      for (int i = 0; i < decl.params.size(); i++) {
        if (i > 0) {
          sb.append(", ");
        }
        sb.append(decl.params.get(i).name);
      }
      sb.append(") elem ").append(factsName);
    }
  }

  /** Translates rules to Morel predicate functions. */
  private static String translateRulesToPredicates(
      Declaration decl,
      List<Fact> facts,
      List<Rule> rules,
      Map<String, Declaration> declarationMap) {
    StringBuilder sb = new StringBuilder();
    String relationName = decl.name;

    // If there are facts, generate the facts list
    if (!facts.isEmpty()) {
      sb.append("  val ").append(relationName).append("_facts = ");
      sb.append(translateFactsToList(decl, facts));
      sb.append("\n");
    }

    // Generate predicate function
    sb.append("  fun ").append(relationName).append(" ");
    appendParams(sb, decl);
    sb.append(" =\n");

    // Generate body: facts check orelse rule1 orelse rule2 ...
    List<String> disjuncts = new ArrayList<>();

    // Add facts elem check if there are facts
    if (!facts.isEmpty()) {
      StringBuilder elemCheck = new StringBuilder();
      appendElemCheck(elemCheck, decl);
      disjuncts.add(elemCheck.toString());
    }

    // Add each rule as a disjunct
    for (Rule rule : rules) {
      disjuncts.add(translateRuleToPredicate(rule, decl, declarationMap));
    }

    // Join with orelse
    sb.append("    ");
    if (disjuncts.size() == 1) {
      sb.append(disjuncts.get(0));
    } else {
      for (int i = 0; i < disjuncts.size(); i++) {
        if (i > 0) {
          sb.append(
              " orelse\n" //
                  + "    ");
        }
        sb.append(disjuncts.get(i));
      }
    }
    sb.append("\n");

    return sb.toString();
  }

  /** Translates a single Datalog rule to a Morel predicate expression. */
  private static String translateRuleToPredicate(
      Rule rule, Declaration decl, Map<String, Declaration> declarationMap) {
    // Map head variables to declaration param names (or expressions for
    // backward substitution). Values are Morel expression strings.
    Map<String, String> headVarToParam = new LinkedHashMap<>();
    // Extra constraints from arithmetic or constant head terms.
    // Each entry is (paramName, term) where the constraint is paramName = term.
    List<Map.Entry<String, Term>> headConstraints = new ArrayList<>();

    for (int i = 0; i < rule.head.terms.size(); i++) {
      Term term = rule.head.terms.get(i);
      String paramName = decl.params.get(i).name;
      if (term instanceof Variable) {
        Variable var = (Variable) term;
        headVarToParam.put(var.name, paramName);
      } else if (term instanceof Constant) {
        // Constant in head: add equality constraint
        headConstraints.add(new AbstractMap.SimpleEntry<>(paramName, term));
      } else if (term instanceof ArithmeticExpr) {
        processHeadArithExpr(
            (ArithmeticExpr) term, paramName, headVarToParam, headConstraints);
      }
    }

    // Collect body-only variables with fresh names.
    // A variable is "body-only" if it is not in headVarToParam.
    Set<String> headParamNames = new HashSet<>(headVarToParam.values());
    Map<String, String> bodyVarToFresh = new LinkedHashMap<>();
    int freshCounter = 0;

    for (BodyAtom bodyAtom : rule.body) {
      freshCounter =
          collectBodyVars(
              bodyAtom,
              headVarToParam,
              headParamNames,
              bodyVarToFresh,
              freshCounter);
    }

    // Build the body expression
    StringBuilder body = new StringBuilder();

    // If there are body-only variables, wrap in exists
    if (!bodyVarToFresh.isEmpty()) {
      body.append("(exists ");
      body.append(String.join(", ", bodyVarToFresh.values()));
      body.append(" where ");
    }

    // Build conjunction of body atoms
    List<String> conjuncts = new ArrayList<>();
    for (BodyAtom bodyAtom : rule.body) {
      if (bodyAtom instanceof Comparison) {
        Comparison comp = (Comparison) bodyAtom;
        String left =
            termToPredicateRef(comp.left, headVarToParam, bodyVarToFresh);
        String right =
            termToPredicateRef(comp.right, headVarToParam, bodyVarToFresh);
        String op = compOpToMorel(comp.op);
        conjuncts.add(left + " " + op + " " + right);
      } else {
        Atom atom = bodyAtom.atom;
        StringBuilder call = new StringBuilder();
        if (bodyAtom.negated) {
          call.append("not (");
        }
        call.append(atom.name);
        if (atom.terms.size() == 1) {
          call.append(" ");
          call.append(
              termToPredicateRef(
                  atom.terms.get(0), headVarToParam, bodyVarToFresh));
        } else {
          call.append(" (");
          for (int i = 0; i < atom.terms.size(); i++) {
            if (i > 0) {
              call.append(", ");
            }
            call.append(
                termToPredicateRef(
                    atom.terms.get(i), headVarToParam, bodyVarToFresh));
          }
          call.append(")");
        }
        if (bodyAtom.negated) {
          call.append(")");
        }
        conjuncts.add(call.toString());
      }
    }

    // Add head constraints (from constants or complex arithmetic in head)
    for (Map.Entry<String, Term> constraint : headConstraints) {
      String paramRef = constraint.getKey();
      String termRef =
          termToPredicateRef(
              constraint.getValue(), headVarToParam, bodyVarToFresh);
      conjuncts.add(paramRef + " = " + termRef);
    }

    body.append(String.join(" andalso ", conjuncts));

    if (!bodyVarToFresh.isEmpty()) {
      body.append(")");
    }

    return body.toString();
  }

  /**
   * Processes an arithmetic expression in the rule head.
   *
   * <p>For simple forms like {@code var + const} or {@code var - const},
   * applies backward substitution: maps the variable to the inverse expression.
   * For complex forms (like {@code x + y}), adds an equality constraint.
   */
  private static void processHeadArithExpr(
      ArithmeticExpr expr,
      String paramName,
      Map<String, String> headVarToParam,
      List<Map.Entry<String, Term>> headConstraints) {
    // Simple case: var op const
    if (expr.left instanceof Variable && expr.right instanceof Constant) {
      Variable var = (Variable) expr.left;
      String constStr = termToMorel(expr.right);
      String inverseOp = inverseOp(expr.op);
      if (inverseOp != null && !headVarToParam.containsKey(var.name)) {
        headVarToParam.put(
            var.name, paramName + " " + inverseOp + " " + constStr);
      } else {
        // Variable already mapped (appears in another head position);
        // fall through to constraint
        headConstraints.add(new AbstractMap.SimpleEntry<>(paramName, expr));
      }
      return;
    }
    // Simple case: const op var (only for commutative operators)
    if (expr.left instanceof Constant && expr.right instanceof Variable) {
      Variable var = (Variable) expr.right;
      String constStr = termToMorel(expr.left);
      if ((expr.op == ArithOp.PLUS || expr.op == ArithOp.TIMES)
          && !headVarToParam.containsKey(var.name)) {
        String inverseOp = inverseOp(expr.op);
        headVarToParam.put(
            var.name, paramName + " " + inverseOp + " " + constStr);
        return;
      }
      // For const - var or const / var, use constraint
    }
    // Complex case: add equality constraint.
    // The Term will be resolved later via termToPredicateRef.
    headConstraints.add(new AbstractMap.SimpleEntry<>(paramName, expr));
  }

  /**
   * Collects body-only variables from a body atom, returning the updated fresh
   * counter.
   */
  private static int collectBodyVars(
      BodyAtom bodyAtom,
      Map<String, String> headVarToParam,
      Set<String> headParamNames,
      Map<String, String> bodyVarToFresh,
      int freshCounter) {
    if (bodyAtom instanceof Comparison) {
      Comparison comp = (Comparison) bodyAtom;
      for (Term term : Arrays.asList(comp.left, comp.right)) {
        freshCounter =
            collectTermVars(
                term,
                headVarToParam,
                headParamNames,
                bodyVarToFresh,
                freshCounter);
      }
      return freshCounter;
    }
    Atom atom = bodyAtom.atom;
    for (Term term : atom.terms) {
      freshCounter =
          collectTermVars(
              term,
              headVarToParam,
              headParamNames,
              bodyVarToFresh,
              freshCounter);
    }
    return freshCounter;
  }

  /**
   * Collects body-only variables from a term (recursing into ArithmeticExpr),
   * returning the updated fresh counter.
   */
  private static int collectTermVars(
      Term term,
      Map<String, String> headVarToParam,
      Set<String> headParamNames,
      Map<String, String> bodyVarToFresh,
      int freshCounter) {
    if (term instanceof Variable) {
      Variable var = (Variable) term;
      if (!headVarToParam.containsKey(var.name)
          && !bodyVarToFresh.containsKey(var.name)) {
        String fresh = "v" + freshCounter++;
        while (headParamNames.contains(fresh)) {
          fresh = "v" + freshCounter++;
        }
        bodyVarToFresh.put(var.name, fresh);
      }
    } else if (term instanceof ArithmeticExpr) {
      ArithmeticExpr expr = (ArithmeticExpr) term;
      freshCounter =
          collectTermVars(
              expr.left,
              headVarToParam,
              headParamNames,
              bodyVarToFresh,
              freshCounter);
      freshCounter =
          collectTermVars(
              expr.right,
              headVarToParam,
              headParamNames,
              bodyVarToFresh,
              freshCounter);
    }
    return freshCounter;
  }

  /** Converts a term to a reference in a predicate expression. */
  private static String termToPredicateRef(
      Term term,
      Map<String, String> headVarToParam,
      Map<String, String> bodyVarToFresh) {
    if (term instanceof Variable) {
      Variable var = (Variable) term;
      String paramName = headVarToParam.get(var.name);
      if (paramName != null) {
        return paramName;
      }
      String freshName = bodyVarToFresh.get(var.name);
      if (freshName != null) {
        return freshName;
      }
      return var.name.toLowerCase();
    } else if (term instanceof Constant) {
      return termToMorel(term);
    } else if (term instanceof ArithmeticExpr) {
      ArithmeticExpr expr = (ArithmeticExpr) term;
      String left =
          termToPredicateRefParens(
              expr.left, expr.op, true, headVarToParam, bodyVarToFresh);
      String right =
          termToPredicateRefParens(
              expr.right, expr.op, false, headVarToParam, bodyVarToFresh);
      return left + " " + expr.op.symbol + " " + right;
    }
    throw new IllegalArgumentException("Unknown term type: " + term);
  }

  /**
   * Converts a sub-term to a reference, adding parentheses if needed for
   * correct precedence.
   */
  private static String termToPredicateRefParens(
      Term term,
      ArithOp parentOp,
      boolean isLeft,
      Map<String, String> headVarToParam,
      Map<String, String> bodyVarToFresh) {
    String ref = termToPredicateRef(term, headVarToParam, bodyVarToFresh);
    if (needsParens(term, parentOp, isLeft, headVarToParam)) {
      return "(" + ref + ")";
    }
    return ref;
  }

  /**
   * Returns whether a sub-term needs parentheses given its parent operator.
   *
   * <p>Multiplicative operators ({@code *}, {@code /}) bind tighter than
   * additive ({@code +}, {@code -}). A sub-term needs parentheses if it has
   * lower precedence than the parent, or if it is on the right side of a
   * non-commutative/non-associative operator.
   */
  private static boolean needsParens(
      Term term,
      ArithOp parentOp,
      boolean isLeft,
      Map<String, String> headVarToParam) {
    // If the term is a variable that resolves to a compound expression
    // (backward substitution like N -> n - 1), it needs parens when used
    // in a multiplicative context.
    if (term instanceof Variable) {
      String resolved = headVarToParam.get(((Variable) term).name);
      if (resolved != null && resolved.contains(" ")) {
        // The resolved expression is compound (e.g., "n - 1");
        // needs parens if parent is multiplicative
        return isMultiplicative(parentOp);
      }
      return false;
    }
    if (!(term instanceof ArithmeticExpr)) {
      return false;
    }
    ArithmeticExpr subExpr = (ArithmeticExpr) term;
    // Additive inside multiplicative needs parens
    if (isMultiplicative(parentOp) && !isMultiplicative(subExpr.op)) {
      return true;
    }
    // Right operand of minus or divide needs parens if it is additive
    if (!isLeft
        && (parentOp == ArithOp.MINUS || parentOp == ArithOp.DIVIDE)
        && !isMultiplicative(subExpr.op)) {
      return true;
    }
    return false;
  }

  /** Returns whether an operator is multiplicative (higher precedence). */
  private static boolean isMultiplicative(ArithOp op) {
    return op == ArithOp.TIMES || op == ArithOp.DIVIDE;
  }

  /** Converts comparison operator to Morel syntax. */
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

  /** Returns the inverse operator symbol, or null if not invertible. */
  private static String inverseOp(ArithOp op) {
    switch (op) {
      case PLUS:
        return "-";
      case MINUS:
        return "+";
      case TIMES:
        return "/";
      case DIVIDE:
        return "*";
      default:
        return null;
    }
  }

  /** Translates facts to a Morel list literal. */
  private static String translateFactsToList(
      Declaration decl, List<Fact> facts) {
    StringBuilder sb = new StringBuilder("[");

    for (int i = 0; i < facts.size(); i++) {
      if (i > 0) {
        sb.append(", ");
      }
      Fact fact = facts.get(i);

      if (decl.arity() == 1) {
        sb.append(termToMorel(fact.atom.terms.get(0)));
      } else {
        sb.append("(");
        for (int j = 0; j < fact.atom.terms.size(); j++) {
          if (j > 0) {
            sb.append(", ");
          }
          sb.append(termToMorel(fact.atom.terms.get(j)));
        }
        sb.append(")");
      }
    }

    sb.append("]");
    return sb.toString();
  }

  /** Converts a Datalog term to Morel syntax. */
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
      Variable var = (Variable) term;
      return "\"" + var.name + "\"";
    }
    throw new IllegalArgumentException("Cannot convert term to Morel: " + term);
  }
}

// End DatalogTranslator.java
