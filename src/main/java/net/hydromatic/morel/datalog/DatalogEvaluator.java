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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.hydromatic.morel.datalog.DatalogAst.Atom;
import net.hydromatic.morel.datalog.DatalogAst.BodyAtom;
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
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.PairList;

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

      // 3. Collect facts and rules by relation
      Map<String, List<Rule>> rulesByRelation = new LinkedHashMap<>();
      Map<String, Set<Tuple>> tuplesByRelation = new LinkedHashMap<>();

      for (Statement stmt : ast.statements) {
        if (stmt instanceof Fact) {
          Fact fact = (Fact) stmt;
          String relationName = fact.atom.name;
          tuplesByRelation
              .computeIfAbsent(relationName, k -> new HashSet<>())
              .add(Tuple.fromAtom(fact.atom));
        } else if (stmt instanceof Rule) {
          Rule rule = (Rule) stmt;
          String relationName = rule.head.name;
          rulesByRelation
              .computeIfAbsent(relationName, k -> new ArrayList<>())
              .add(rule);
        }
      }

      // 4. Evaluate rules using semi-naive evaluation
      evaluateRules(rulesByRelation, tuplesByRelation);

      // 5. Format output for each .output directive
      StringBuilder result = new StringBuilder();
      for (Output output : ast.getOutputs()) {
        String relationName = output.relationName;
        Set<Tuple> tuples =
            tuplesByRelation.getOrDefault(relationName, new HashSet<>());

        // Output relation name
        if (result.length() > 0) {
          result.append("\n");
        }
        result.append(relationName).append("\n");

        // Output each tuple (sorted for deterministic output)
        tuples.stream()
            .sorted()
            .forEach(tuple -> result.append(tuple.format()).append("\n"));
      }

      return result.toString().trim();

    } catch (ParseException e) {
      throw new DatalogException("Parse error: " + e.getMessage(), e);
    }
  }

  /**
   * Evaluates rules using semi-naive evaluation.
   *
   * @param rulesByRelation rules grouped by head relation
   * @param tuplesByRelation current tuples for each relation (modified in
   *     place)
   */
  private static void evaluateRules(
      Map<String, List<Rule>> rulesByRelation,
      Map<String, Set<Tuple>> tuplesByRelation) {
    // Semi-naive evaluation: iterate until fixpoint
    boolean changed = true;
    while (changed) {
      changed = false;

      // Apply each rule
      for (Map.Entry<String, List<Rule>> entry : rulesByRelation.entrySet()) {
        String headRelation = entry.getKey();
        List<Rule> rules = entry.getValue();

        Set<Tuple> currentTuples =
            tuplesByRelation.getOrDefault(headRelation, new HashSet<>());
        Set<Tuple> newTuples = new HashSet<>(currentTuples);

        for (Rule rule : rules) {
          // Evaluate rule and add derived tuples
          Set<Tuple> derived = evaluateRule(rule, tuplesByRelation);
          newTuples.addAll(derived);
        }

        // Check if new tuples were derived
        if (newTuples.size() > currentTuples.size()) {
          tuplesByRelation.put(headRelation, newTuples);
          changed = true;
        }
      }
    }
  }

  /**
   * Evaluates a single rule and returns derived tuples.
   *
   * @param rule the rule to evaluate
   * @param tuplesByRelation current tuples for each relation
   * @return set of tuples derived from this rule
   */
  private static Set<Tuple> evaluateRule(
      Rule rule, Map<String, Set<Tuple>> tuplesByRelation) {
    Set<Tuple> results = new HashSet<>();

    // Start with all possible variable bindings from the first body atom
    List<Map<String, Object>> bindings = new ArrayList<>();
    bindings.add(new HashMap<>());

    // Process each body atom
    for (BodyAtom bodyAtom : rule.body) {
      bindings = joinWithAtom(bindings, bodyAtom, tuplesByRelation);
    }

    // Project bindings to head variables
    for (Map<String, Object> binding : bindings) {
      List<Object> values = new ArrayList<>();
      for (Term term : rule.head.terms) {
        if (term instanceof Variable) {
          values.add(binding.get(((Variable) term).name));
        } else if (term instanceof Constant) {
          values.add(((Constant) term).value);
        }
      }
      results.add(new Tuple(values));
    }

    return results;
  }

  /**
   * Joins current bindings with tuples from a body atom.
   *
   * @param currentBindings current variable bindings
   * @param bodyAtom atom to join with
   * @param tuplesByRelation available tuples
   * @return new bindings after join
   */
  private static List<Map<String, Object>> joinWithAtom(
      List<Map<String, Object>> currentBindings,
      BodyAtom bodyAtom,
      Map<String, Set<Tuple>> tuplesByRelation) {
    List<Map<String, Object>> newBindings = new ArrayList<>();

    String relationName = bodyAtom.atom.name;
    Set<Tuple> tuples =
        tuplesByRelation.getOrDefault(relationName, new HashSet<>());

    for (Map<String, Object> binding : currentBindings) {
      for (Tuple tuple : tuples) {
        Map<String, Object> newBinding =
            tryMatch(binding, bodyAtom.atom.terms, tuple);
        if (newBinding != null) {
          // Check negation
          if (!bodyAtom.negated) {
            newBindings.add(newBinding);
          }
        } else if (bodyAtom.negated) {
          // Negation: add binding if no match found
          newBindings.add(new HashMap<>(binding));
        }
      }
    }

    return newBindings;
  }

  /**
   * Try to match atom terms with a tuple given current bindings.
   *
   * @param binding current variable bindings
   * @param terms atom terms (variables or constants)
   * @param tuple tuple to match against
   * @return new binding if match succeeds, null otherwise
   */
  private static Map<String, Object> tryMatch(
      Map<String, Object> binding, List<Term> terms, Tuple tuple) {
    if (terms.size() != tuple.values.size()) {
      return null;
    }

    Map<String, Object> newBinding = new HashMap<>(binding);

    for (int i = 0; i < terms.size(); i++) {
      Term term = terms.get(i);
      Object tupleValue = tuple.values.get(i);

      if (term instanceof Variable) {
        String varName = ((Variable) term).name;
        if (newBinding.containsKey(varName)) {
          // Variable already bound - check consistency
          if (!newBinding.get(varName).equals(tupleValue)) {
            return null;
          }
        } else {
          // Bind variable
          newBinding.put(varName, tupleValue);
        }
      } else if (term instanceof Constant) {
        // Constant must match exactly
        Object constValue = ((Constant) term).value;
        if (!constValue.equals(tupleValue)) {
          return null;
        }
      }
    }

    return newBinding;
  }

  /** Represents a tuple of values in a relation. */
  private static class Tuple implements Comparable<Tuple> {
    final List<Object> values;

    Tuple(List<Object> values) {
      this.values = new ArrayList<>(values);
    }

    static Tuple fromAtom(Atom atom) {
      List<Object> values = new ArrayList<>();
      for (Term term : atom.terms) {
        if (term instanceof Constant) {
          values.add(((Constant) term).value);
        } else if (term instanceof Variable) {
          // Variables in facts are treated as symbols
          values.add(((Variable) term).name);
        }
      }
      return new Tuple(values);
    }

    String format() {
      return values.stream()
          .map(Tuple::formatValue)
          .collect(Collectors.joining(" "));
    }

    private static String formatValue(Object value) {
      if (value instanceof String) {
        // Check if it looks like a quoted string or a symbol
        String str = (String) value;
        // Simple heuristic: if it contains spaces or special chars, quote it
        if (str.matches(".*[\\s,;()].*")) {
          return "\"" + str + "\"";
        }
        return str;
      }
      return String.valueOf(value);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof Tuple)) {
        return false;
      }
      Tuple tuple = (Tuple) o;
      return values.equals(tuple.values);
    }

    @Override
    public int hashCode() {
      return values.hashCode();
    }

    @Override
    public int compareTo(Tuple other) {
      for (int i = 0; i < Math.min(values.size(), other.values.size()); i++) {
        Object v1 = values.get(i);
        Object v2 = other.values.get(i);

        if (v1 instanceof Comparable && v2 instanceof Comparable) {
          @SuppressWarnings({"unchecked", "rawtypes"})
          int cmp = ((Comparable) v1).compareTo((Comparable) v2);
          if (cmp != 0) {
            return cmp;
          }
        }
      }
      return Integer.compare(values.size(), other.values.size());
    }
  }

  /**
   * Validates a Datalog program and returns type information or error message.
   *
   * @param program the Datalog program source code
   * @param session the Morel session
   * @return type representation like "{edge: (int * int) list}", or "Error:
   *     ..." if invalid
   */
  public static String validate(String program, Session session) {
    try {
      // 1. Parse Datalog program
      Program ast = DatalogParserImpl.parse(program);

      // 2. Analyze for safety and stratification
      DatalogAnalyzer.analyze(ast);

      // 3. Build and return type representation
      TypeSystem typeSystem = new TypeSystem();
      Type type = buildOutputType(ast, typeSystem);
      return type.toString();

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

      // 3. Build and return output type
      TypeSystem typeSystem = new TypeSystem();
      return buildOutputType(ast, typeSystem);

    } catch (ParseException e) {
      throw new DatalogException("Parse error: " + e.getMessage(), e);
    }
  }

  /**
   * Builds the output type for a Datalog program.
   *
   * <p>For a program with .output directives, returns a record type with each
   * output relation as a field. For example, {@code .output edge} where edge
   * has type {@code (int * int)} produces {@code {edge: (int * int) list}}.
   *
   * @param ast the parsed Datalog program
   * @param typeSystem the Morel type system
   * @return the output type
   */
  private static Type buildOutputType(Program ast, TypeSystem typeSystem) {
    PairList<String, Type> fields = PairList.of();

    for (Output output : ast.getOutputs()) {
      String relationName = output.relationName;
      Declaration decl = ast.getDeclaration(relationName);

      if (decl == null) {
        throw new DatalogException(
            "Output relation '" + relationName + "' not declared");
      }

      // Build tuple type for the relation
      Type tupleType = buildTupleType(decl, typeSystem);

      // Wrap in list type
      Type listType = typeSystem.listType(tupleType);

      fields.add(relationName, listType);
    }

    // Return record type with all output relations
    return typeSystem.recordType(fields);
  }

  /**
   * Builds a tuple type for a Datalog relation declaration.
   *
   * <p>For example, {@code .decl edge(x:number, y:number)} produces type {@code
   * int * int}.
   *
   * @param decl the relation declaration
   * @param typeSystem the Morel type system
   * @return the tuple type
   */
  private static Type buildTupleType(Declaration decl, TypeSystem typeSystem) {
    if (decl.params.isEmpty()) {
      return PrimitiveType.UNIT;
    }

    List<Type> paramTypes = new ArrayList<>();
    for (Param param : decl.params) {
      paramTypes.add(datalogTypeToMorelType(param.type));
    }

    if (paramTypes.size() == 1) {
      return paramTypes.get(0);
    } else {
      return typeSystem.tupleType(paramTypes);
    }
  }

  /**
   * Converts a Datalog type name to a Morel type.
   *
   * @param datalogType the Datalog type ("number", "string", "symbol")
   * @return the corresponding Morel type
   */
  private static Type datalogTypeToMorelType(String datalogType) {
    switch (datalogType) {
      case "number":
        return PrimitiveType.INT;
      case "string":
      case "symbol":
        return PrimitiveType.STRING;
      default:
        throw new DatalogException("Unknown Datalog type: " + datalogType);
    }
  }
}

// End DatalogEvaluator.java
