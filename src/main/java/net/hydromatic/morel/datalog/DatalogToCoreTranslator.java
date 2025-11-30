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

import static net.hydromatic.morel.ast.CoreBuilder.core;

import com.google.common.collect.ImmutableList;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.datalog.DatalogAst.*;
import net.hydromatic.morel.type.FnType;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.RecordLikeType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;

/**
 * Translates Datalog programs to Morel Core expressions.
 *
 * <p>Translation Strategy:
 *
 * <ul>
 *   <li>Facts → Predicate functions returning disjunction of equality tests
 *       <p>Example: {@code edge(1,2). edge(2,3).}
 *       <p>Becomes: {@code fun edge(x,y) = (x=1 andalso y=2) orelse (x=2
 *       andalso y=3)}
 *   <li>Rules → Predicate functions returning conjunction of body atoms
 *       <p>Example: {@code p(X) :- q(X,1), r(X).}
 *       <p>Becomes: {@code fun p x = q(x,1) andalso r(x)}
 *   <li>Negation → Use {@code not} operator
 *       <p>Example: {@code p(X) :- q(X), !r(X).}
 *       <p>Becomes: {@code fun p x = q(x) andalso not(r(x))}
 *   <li>Output → Generate {@code from} expressions
 *       <p>Example: {@code .output p}
 *       <p>Becomes: {@code from x where p(x)}
 * </ul>
 *
 * <p>TODO: Full implementation pending. Current stub throws
 * UnsupportedOperationException. Implementation requires:
 *
 * <ol>
 *   <li>Group facts and rules by relation name
 *   <li>For each relation, create a predicate function:
 *       <ul>
 *         <li>Parameters = relation arity (x, y for arity 2)
 *         <li>Body = disjunction of all facts/rules for that relation
 *       </ul>
 *   <li>For facts: Generate equality tests for each parameter
 *   <li>For rules: Generate conjunction of body atoms
 *   <li>Handle variable substitution from rule head to body
 *   <li>For .output: Generate {@code from} expression enumerating the relation
 * </ol>
 *
 * <p>Core API Usage:
 *
 * <blockquote>
 *
 * <pre>
 * // Create predicate function: fun p(x, y) = body
 * FnType fnType = typeSystem.fnType(tupleType, PrimitiveType.BOOL);
 * Core.IdPat param = core.idPat(tupleType, "p", 0);
 * Core.Exp body = ...; // Expression tree
 * Core.Fn fn = core.fn(fnType, param, body);
 *
 * // Create conjunction: e1 andalso e2
 * Core.Exp conj = core.apply(Pos.ZERO, PrimitiveType.BOOL,
 *     core.apply(Pos.ZERO, fnType,
 *         core.functionLiteral(typeSystem, BuiltIn.Z_ANDALSO), e1), e2);
 *
 * // Create equality: x = 1
 * Core.Exp eq = core.equal(typeSystem, idExp, literalExp);
 * </pre>
 *
 * </blockquote>
 */
public class DatalogToCoreTranslator {
  private final TypeSystem typeSystem;
  private final Program program;

  private DatalogToCoreTranslator(TypeSystem typeSystem, Program program) {
    this.typeSystem = typeSystem;
    this.program = program;
  }

  /**
   * Translates a Datalog program to Morel Core expressions.
   *
   * @param program the Datalog program to translate
   * @param typeSystem the Morel type system
   * @return a map from relation names to Core expressions (predicate functions)
   */
  public static Map<String, Core.Exp> translate(
      Program program, TypeSystem typeSystem) {
    DatalogToCoreTranslator translator =
        new DatalogToCoreTranslator(typeSystem, program);
    return translator.translateProgram();
  }

  private Map<String, Core.Exp> translateProgram() {
    // 1. Group statements by relation name
    Map<String, List<Fact>> factsByRelation = new LinkedHashMap<>();
    Map<String, List<Rule>> rulesByRelation = new LinkedHashMap<>();

    for (Statement stmt : program.statements) {
      if (stmt instanceof Fact) {
        Fact fact = (Fact) stmt;
        String relName = fact.atom.name;
        factsByRelation
            .computeIfAbsent(relName, k -> new ArrayList<>())
            .add(fact);
      } else if (stmt instanceof Rule) {
        Rule rule = (Rule) stmt;
        String relName = rule.head.name;
        rulesByRelation
            .computeIfAbsent(relName, k -> new ArrayList<>())
            .add(rule);
      }
    }

    // 2. For each relation, generate predicate function
    Map<String, Core.Exp> predicates = new HashMap<>();
    for (Statement stmt : program.statements) {
      if (stmt instanceof Declaration) {
        Declaration decl = (Declaration) stmt;
        List<Fact> facts =
            factsByRelation.getOrDefault(decl.name, ImmutableList.of());
        List<Rule> rules =
            rulesByRelation.getOrDefault(decl.name, ImmutableList.of());

        Core.Fn fn =
            createPredicateFunction(decl.name, facts, rules, decl.arity());
        predicates.put(decl.name, fn);
      }
    }

    return predicates;
  }

  /**
   * Translates a single fact to a Core expression.
   *
   * <p>Example: {@code edge(1, 2)} becomes {@code x = 1 andalso y = 2}
   *
   * @param fact the fact to translate
   * @param paramNames parameter variable names
   * @return Core expression representing the fact
   */
  private Core.Exp translateFact(Fact fact, List<String> paramNames) {
    // Create conjunction of equality tests
    // Example: edge(1, 2) becomes: x = 1 andalso y = 2
    List<Core.Exp> equalityTests = new ArrayList<>();

    for (int i = 0; i < fact.atom.terms.size(); i++) {
      Term term = fact.atom.terms.get(i);
      String paramName = paramNames.get(i);

      // Create variable reference (e.g., "x")
      Type paramType = datalogTypeToMorelType(term);
      Core.IdPat idPat = core.idPat(paramType, paramName, 0);
      Core.Id idExp = core.id(idPat);

      // Create constant literal (e.g., 1)
      Core.Literal literalExp = termToLiteral(term);

      // Create equality test (e.g., "x = 1")
      Core.Exp eq = core.equal(typeSystem, idExp, literalExp);
      equalityTests.add(eq);
    }

    // Combine all equality tests with andalso
    return core.andAlso(typeSystem, equalityTests);
  }

  /**
   * Translates a single rule to a Core expression.
   *
   * <p>Example: {@code p(X) :- q(X, 1), r(X)} becomes {@code q(x, 1) andalso
   * r(x)}
   *
   * @param rule the rule to translate
   * @param paramNames parameter variable names
   * @return Core expression representing the rule
   */
  private Core.Exp translateRule(Rule rule, List<String> paramNames) {
    // Create conjunction of body atoms
    // Example: p(X) :- q(X, 1), r(X) becomes: q(x, 1) andalso r(x)
    List<Core.Exp> bodyExps = new ArrayList<>();

    for (BodyAtom bodyAtom : rule.body) {
      Atom atom = bodyAtom.atom;

      // Build arguments for the function call
      List<Core.Exp> args = new ArrayList<>();
      for (Term term : atom.terms) {
        if (term instanceof Variable) {
          // Variable reference - find its parameter position in the head
          Variable var = (Variable) term;
          int paramIndex = findVariableInHead(rule, var.name);
          if (paramIndex >= 0) {
            String paramName = paramNames.get(paramIndex);
            Type paramType = inferTermType(term);
            Core.IdPat idPat = core.idPat(paramType, paramName, 0);
            args.add(core.id(idPat));
          } else {
            // Variable not in head - this should have been caught by safety
            // analysis
            throw new IllegalStateException("Unsafe variable: " + var.name);
          }
        } else {
          // Constant value
          args.add(termToLiteral(term));
        }
      }

      // Create function call: relation(args...)
      // For now, create a simple id reference to the relation
      // TODO: This needs to reference the actual predicate function
      Core.IdPat relationPat = core.idPat(PrimitiveType.BOOL, atom.name, 0);
      Core.Id relationId = core.id(relationPat);

      Core.Exp call;
      if (args.size() == 1) {
        call =
            core.apply(Pos.ZERO, PrimitiveType.BOOL, relationId, args.get(0));
      } else {
        // Multiple arguments - create tuple
        RecordLikeType tupleType =
            typeSystem.tupleType(
                args.stream()
                    .map(Core.Exp::type)
                    .collect(ImmutableList.toImmutableList()));
        Core.Tuple argTuple = core.tuple(tupleType, args);
        call = core.apply(Pos.ZERO, PrimitiveType.BOOL, relationId, argTuple);
      }

      // Wrap in not() if negated
      if (bodyAtom.negated) {
        call = core.not(typeSystem, call);
      }

      bodyExps.add(call);
    }

    // Combine all body atoms with andalso
    return core.andAlso(typeSystem, bodyExps);
  }

  /**
   * Creates a predicate function for a relation.
   *
   * <p>Example: For relation {@code edge} with facts {@code edge(1,2)} and
   * {@code edge(2,3)}, generates: {@code fun edge(x, y) = (x=1 andalso y=2)
   * orelse (x=2 andalso y=3)}
   *
   * @param relationName name of the relation
   * @param facts list of facts for this relation
   * @param rules list of rules with this relation as head
   * @param arity arity of the relation
   * @return Core.Fn representing the predicate function
   */
  private Core.Fn createPredicateFunction(
      String relationName, List<Fact> facts, List<Rule> rules, int arity) {
    // Create parameter names (e.g., ["x", "y"] for arity 2)
    List<String> paramNames = new ArrayList<>();
    for (int i = 0; i < arity; i++) {
      paramNames.add("x" + i);
    }

    // Translate all facts and rules to expressions
    List<Core.Exp> disjuncts = new ArrayList<>();

    for (Fact fact : facts) {
      Core.Exp factExp = translateFact(fact, paramNames);
      disjuncts.add(factExp);
    }

    for (Rule rule : rules) {
      Core.Exp ruleExp = translateRule(rule, paramNames);
      disjuncts.add(ruleExp);
    }

    // Combine all disjuncts with orelse
    Core.Exp body;
    if (disjuncts.isEmpty()) {
      // Empty relation - always false
      body = core.boolLiteral(false);
    } else {
      body = core.orElse(typeSystem, disjuncts);
    }

    // Create function parameter
    Core.IdPat param;
    Type paramType;
    if (arity == 1) {
      // Single parameter: fun edge x0 = ...
      paramType = PrimitiveType.INT; // Default to int for now
      param = core.idPat(paramType, paramNames.get(0), 0);
    } else {
      // Multiple parameters: fun edge (x0, x1) = ...
      List<Type> paramTypes = new ArrayList<>();
      for (int i = 0; i < arity; i++) {
        paramTypes.add(PrimitiveType.INT); // Default to int for now
      }
      paramType = typeSystem.tupleType(paramTypes);
      param = core.idPat(paramType, "params", 0);
    }

    // Create function type: paramType -> bool
    FnType fnType = typeSystem.fnType(paramType, PrimitiveType.BOOL);

    // Create function
    return core.fn(fnType, param, body);
  }

  /** Helper: converts a Datalog term to a Morel type. */
  private Type datalogTypeToMorelType(Term term) {
    if (term instanceof Constant) {
      Constant constant = (Constant) term;
      return constant.type.equals("number")
          ? PrimitiveType.INT
          : PrimitiveType.STRING;
    }
    return PrimitiveType.INT; // Default for variables
  }

  /** Helper: converts a Datalog term to a Core literal. */
  private Core.Literal termToLiteral(Term term) {
    if (term instanceof Constant) {
      Constant constant = (Constant) term;
      Object value = constant.value;

      if (value instanceof Number) {
        BigDecimal bd =
            value instanceof BigDecimal
                ? (BigDecimal) value
                : BigDecimal.valueOf(((Number) value).longValue());
        return core.intLiteral(bd);
      } else if (value instanceof String) {
        return core.stringLiteral((String) value);
      } else {
        throw new IllegalArgumentException(
            "Unsupported constant type: " + value.getClass());
      }
    } else if (term instanceof Variable) {
      // Variables in facts are treated as symbols (strings)
      Variable var = (Variable) term;
      return core.stringLiteral(var.name);
    }
    throw new IllegalArgumentException(
        "Cannot convert term to literal: " + term);
  }

  /** Helper: infers the type of a term for rule translation. */
  private Type inferTermType(Term term) {
    if (term instanceof Constant) {
      return datalogTypeToMorelType(term);
    }
    return PrimitiveType.INT; // Default for variables
  }

  /** Helper: finds the parameter index of a variable in the rule head. */
  private int findVariableInHead(Rule rule, String varName) {
    for (int i = 0; i < rule.head.terms.size(); i++) {
      Term term = rule.head.terms.get(i);
      if (term instanceof Variable) {
        Variable var = (Variable) term;
        if (var.name.equals(varName)) {
          return i;
        }
      }
    }
    return -1; // Not found
  }
}

// End DatalogToCoreTranslator.java
