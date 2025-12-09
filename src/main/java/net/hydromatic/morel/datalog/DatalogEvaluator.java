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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
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
  /**
   * Flag to control evaluation strategy.
   *
   * <p>When true, uses direct semi-naive evaluation with custom Tuple engine.
   * When false (default), translates to Morel and executes through Morel's
   * pipeline.
   */
  private static final boolean USE_DIRECT_EVALUATOR = false;

  private DatalogEvaluator() {
    // Utility class
  }

  /**
   * Executes a Datalog program and returns structured output as a variant.
   *
   * @param program the Datalog program source code
   * @param session the Morel session
   * @return variant containing structured data for relations marked with
   *     {@code .output}
   * @throws DatalogException if the program is invalid
   */
  public static Variant execute(String program, Session session) {
    try {
      // 1. Parse Datalog program
      Program ast = DatalogParserImpl.parse(program);

      // 2. Analyze for safety and stratification
      DatalogAnalyzer.analyze(ast);

      // 3. Choose evaluation strategy
      if (USE_DIRECT_EVALUATOR) {
        return executeDirectly(ast, session);
      } else {
        return executeViaMorel(ast, session);
      }

    } catch (ParseException e) {
      throw new DatalogException(
          String.format("Parse error: %s", e.getMessage()), e);
    }
  }

  /**
   * Executes via Morel translation (default strategy).
   *
   * <p>Translates the Datalog program to Morel source code and executes it
   * through Morel's normal compilation pipeline.
   */
  private static Variant executeViaMorel(Program ast, Session session) {
    // Translate Datalog AST to Morel source code
    String morelSource = translateToMorelSource(ast);

    try {
      // Parse the Morel source
      MorelParserImpl parser =
          new MorelParserImpl(new StringReader(morelSource));
      AstNode statement = parser.statementEofSafe();

      // Compile the statement
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

      // Evaluate and capture the binding
      List<String> outLines = new ArrayList<>();
      List<Binding> bindings = new ArrayList<>();
      compiled.eval(session, env, outLines::add, bindings::add);

      // The last binding should be "it" with the variant value
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

    List<Output> outputs = ast.getOutputs();
    if (outputs.isEmpty()) {
      morel.append("()");
    } else if (outputs.size() == 1) {
      morel
          .append("{")
          .append(outputs.get(0).relationName)
          .append("=")
          .append(outputs.get(0).relationName)
          .append("}");
    } else {
      morel.append("{");
      for (int i = 0; i < outputs.size(); i++) {
        if (i > 0) {
          morel.append(", ");
        }
        String relName = outputs.get(i).relationName;
        morel.append(relName).append("=").append(relName);
      }
      morel.append("}");
    }

    // end clause
    morel.append("\n").append("end");

    return morel.toString();
  }

  /**
   * Translates a list of facts to a Morel list literal.
   *
   * <p>Example: {@code edge(1,2). edge(2,3).} becomes {@code [(1,2), (2,3)]}
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

  /** Converts a Datalog term to Morel syntax. */
  private static String termToMorel(Term term) {
    if (term instanceof Constant) {
      Constant constant = (Constant) term;
      Object value = constant.value;

      if (value instanceof Number) {
        return value.toString();
      } else if (value instanceof String) {
        // Escape the string for Morel
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

  /**
   * Executes directly using semi-naive evaluation (legacy strategy).
   *
   * <p>Uses a custom Tuple-based engine for evaluation.
   */
  private static Variant executeDirectly(Program ast, Session session) {
    TypeSystem typeSystem = session.typeSystem;

    // Collect facts and rules by relation
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

    // Evaluate rules using semi-naive evaluation
    evaluateRules(rulesByRelation, tuplesByRelation);

    // Build variant output for each .output directive
    List<Output> outputs = ast.getOutputs();

    if (outputs.isEmpty()) {
      // No outputs -> return unit
      return Variant.unit();
    }

    // Build a record with one field per output relation
    PairList<String, Variant> fields = PairList.of();

    for (Output output : outputs) {
      String relationName = output.relationName;
      Declaration decl = ast.getDeclaration(relationName);
      Set<Tuple> tuples =
          tuplesByRelation.getOrDefault(relationName, new HashSet<>());

      // Convert tuples to variant list
      List<Variant> tupleVariants = new ArrayList<>();
      for (Tuple tuple :
          tuples.stream().sorted().collect(Collectors.toList())) {
        tupleVariants.add(tupleToVariant(tuple, decl, typeSystem));
      }

      // Determine element type for the list
      Type elementType = tupleElementType(decl, typeSystem);
      Variant listVariant =
          Variant.ofList(typeSystem, elementType, tupleVariants);
      fields.add(relationName, listVariant);
    }

    // Return a record variant
    return Variant.ofRecord(typeSystem, fields);
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
    // Handle comparisons separately
    if (bodyAtom instanceof Comparison) {
      return filterByComparison(currentBindings, (Comparison) bodyAtom);
    }

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
   * Filters bindings based on a comparison predicate.
   *
   * @param currentBindings current variable bindings
   * @param comparison comparison to evaluate
   * @return bindings that satisfy the comparison
   */
  private static List<Map<String, Object>> filterByComparison(
      List<Map<String, Object>> currentBindings, Comparison comparison) {
    List<Map<String, Object>> newBindings = new ArrayList<>();

    for (Map<String, Object> binding : currentBindings) {
      Object leftVal = evaluateTerm(comparison.left, binding);
      Object rightVal = evaluateTerm(comparison.right, binding);

      if (leftVal != null
          && rightVal != null
          && compareValues(leftVal, rightVal, comparison.op)) {
        newBindings.add(binding);
      }
    }

    return newBindings;
  }

  /**
   * Evaluates a term given current bindings.
   *
   * @param term term to evaluate
   * @param binding current variable bindings
   * @return value of the term
   */
  private static Object evaluateTerm(Term term, Map<String, Object> binding) {
    if (term instanceof Constant) {
      return ((Constant) term).value;
    } else if (term instanceof Variable) {
      return binding.get(((Variable) term).name);
    }
    return null;
  }

  /**
   * Compares two values using the given operator.
   *
   * @param left left operand
   * @param right right operand
   * @param op comparison operator
   * @return true if the comparison holds
   */
  @SuppressWarnings({"unchecked", "rawtypes"})
  private static boolean compareValues(Object left, Object right, CompOp op) {
    switch (op) {
      case EQ:
        return left.equals(right);
      case NE:
        return !left.equals(right);
      case LT:
        if (left instanceof Comparable && right instanceof Comparable) {
          return ((Comparable) left).compareTo((Comparable) right) < 0;
        }
        return false;
      case LE:
        if (left instanceof Comparable && right instanceof Comparable) {
          return ((Comparable) left).compareTo((Comparable) right) <= 0;
        }
        return false;
      case GT:
        if (left instanceof Comparable && right instanceof Comparable) {
          return ((Comparable) left).compareTo((Comparable) right) > 0;
        }
        return false;
      case GE:
        if (left instanceof Comparable && right instanceof Comparable) {
          return ((Comparable) left).compareTo((Comparable) right) >= 0;
        }
        return false;
      default:
        return false;
    }
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

  /**
   * Converts a tuple to a variant based on the relation declaration.
   *
   * <p>For arity-1 relations, returns a single value variant. For arity > 1,
   * returns a record variant with field names from the declaration.
   */
  private static Variant tupleToVariant(
      Tuple tuple, Declaration decl, TypeSystem typeSystem) {
    List<Param> params = decl.params;
    if (params.size() == 1) {
      // Single value
      return valueToVariant(
          tuple.values.get(0), params.get(0).type, typeSystem);
    } else {
      // Multiple values - create a record with actual parameter names
      PairList<String, Variant> recordFields = PairList.of();
      for (int i = 0; i < params.size(); i++) {
        String fieldName = params.get(i).name;
        Variant fieldValue =
            valueToVariant(tuple.values.get(i), params.get(i).type, typeSystem);
        recordFields.add(fieldName, fieldValue);
      }
      return Variant.ofRecord(typeSystem, recordFields);
    }
  }

  /**
   * Determines the element type for a relation's output list.
   *
   * <p>For arity-1 relations, the element type is the param type. For arity >
   * 1, the element type is a record type with field names from the declaration.
   */
  private static Type tupleElementType(
      Declaration decl, TypeSystem typeSystem) {
    List<Param> params = decl.params;
    if (params.size() == 1) {
      return datalogTypeToMorelType(params.get(0).type, typeSystem);
    } else {
      // Record type with actual parameter names (SortedMap required)
      PairList<String, Type> fieldTypes = PairList.of();
      for (Param param : params) {
        fieldTypes.add(
            param.name, datalogTypeToMorelType(param.type, typeSystem));
      }
      return typeSystem.recordType(fieldTypes.asSortedMap());
    }
  }

  /** Converts a Datalog value to a Variant based on its declared type. */
  private static Variant valueToVariant(
      Object value, String datalogType, TypeSystem typeSystem) {
    switch (datalogType) {
      case "number":
        return Variant.ofInt((Integer) value);
      case "string":
      case "symbol":
        return Variant.ofString((String) value);
      default:
        throw new IllegalArgumentException(
            "Unknown Datalog type: " + datalogType);
    }
  }

  /** Converts a Datalog type name to a Morel Type. */
  private static Type datalogTypeToMorelType(
      String datalogType, TypeSystem typeSystem) {
    switch (datalogType) {
      case "number":
        return PrimitiveType.INT;
      case "string":
      case "symbol":
        return PrimitiveType.STRING;
      default:
        throw new IllegalArgumentException(
            "Unknown Datalog type: " + datalogType);
    }
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
      throw new DatalogException(
          String.format("Parse error: %s", e.getMessage()), e);
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
            String.format("Output relation '%s' not declared", relationName));
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
        throw new DatalogException(
            String.format("Unknown Datalog type: %s", datalogType));
    }
  }
}

// End DatalogEvaluator.java
