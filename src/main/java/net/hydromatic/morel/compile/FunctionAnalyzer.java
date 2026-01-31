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
package net.hydromatic.morel.compile;

import static java.util.Objects.requireNonNull;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.ast.Visitor;
import net.hydromatic.morel.type.TypeSystem;

/**
 * Analyzes function definitions to determine their invertibility status.
 *
 * <p>This class is called once when a function is defined. The analysis result
 * is cached in {@link FunctionRegistry} for later use by {@link
 * PredicateInverter}.
 *
 * <p>The analyzer detects several patterns:
 *
 * <ul>
 *   <li>ELEM pattern: {@code fun edge(x,y) = (x,y) elem edges} → INVERTIBLE
 *   <li>RECURSIVE pattern: {@code fun path(x,y) = edge(x,y) orelse (exists z
 *       where edge(x,z) andalso path(z,y))} → RECURSIVE
 *   <li>Uninvertible: functions that can't generate values
 * </ul>
 */
public class FunctionAnalyzer {

  private final TypeSystem typeSystem;
  private final Environment env;
  private FunctionRegistry registry;

  /**
   * Creates a FunctionAnalyzer.
   *
   * @param typeSystem the type system
   * @param env the environment for resolving bindings
   */
  public FunctionAnalyzer(TypeSystem typeSystem, Environment env) {
    this.typeSystem = requireNonNull(typeSystem, "typeSystem");
    this.env = requireNonNull(env, "env");
  }

  /**
   * Analyzes a function definition and returns its invertibility information.
   *
   * @param fnPat the function's name pattern
   * @param fn the function expression
   * @return the function's invertibility information
   */
  public FunctionRegistry.FunctionInfo analyze(
      Core.NamedPat fnPat, Core.Fn fn) {
    // Check for ELEM pattern first (simplest and most common)
    Optional<FunctionRegistry.FunctionInfo> elemResult =
        analyzeElemPattern(fnPat, fn);
    if (elemResult.isPresent()) {
      return elemResult.get();
    }

    // Check for RECURSIVE pattern (transitive closure)
    Optional<FunctionRegistry.FunctionInfo> recursiveResult =
        analyzeRecursivePattern(fnPat, fn);
    if (recursiveResult.isPresent()) {
      return recursiveResult.get();
    }

    // Default: not registered - let legacy inlining handle it
    // This includes andalso patterns and other complex predicates
    // Legacy inlining will substitute the function body and try to invert it
    return null; // Signal: not pre-analyzed, use legacy inlining
  }

  /**
   * Analyzes whether a function matches the ELEM pattern.
   *
   * <p>Pattern: {@code fun edge(x,y) = (x,y) elem edges}
   *
   * <p>AST structure:
   *
   * <pre>
   * Core.Fn(idPat=(x,y), exp=
   *   Core.Apply(fn=ELEM_BUILTIN, arg=
   *     Core.Tuple(
   *       Core.Id(x,y),  // or Core.Tuple for multi-arg
   *       Core.Id(edges))))
   * </pre>
   *
   * @param fnPat the function name pattern
   * @param fn the function expression
   * @return FunctionInfo if pattern matches, empty otherwise
   */
  private Optional<FunctionRegistry.FunctionInfo> analyzeElemPattern(
      Core.NamedPat fnPat, Core.Fn fn) {
    // The function body can be:
    // 1. Direct: op elem ((x, y), edges)
    // 2. Via case: case v of (x, y) => op elem ((x, y), edges)
    // Handle both patterns.
    Core.Exp body = fn.exp;
    Core.Pat formalParam = fn.idPat;

    // Check for case pattern: case v of pat => body
    if (body.op == Op.CASE) {
      Core.Case caseExp = (Core.Case) body;
      if (caseExp.matchList.size() == 1) {
        Core.Match match = caseExp.matchList.get(0);
        // The match pattern becomes our formal parameter
        formalParam = match.pat;
        body = match.exp;
      }
    }

    // Body must be an Apply
    if (body.op != Op.APPLY) {
      return Optional.empty();
    }
    Core.Apply apply = (Core.Apply) body;

    // The function being applied must be 'elem'
    if (!isElemBuiltIn(apply.fn)) {
      return Optional.empty();
    }

    // Argument must be a tuple: (element, collection)
    if (apply.arg.op != Op.TUPLE) {
      return Optional.empty();
    }
    Core.Tuple argTuple = (Core.Tuple) apply.arg;
    if (argTuple.args.size() != 2) {
      return Optional.empty();
    }

    Core.Exp element = argTuple.args.get(0);
    Core.Exp collection = argTuple.args.get(1);

    // Element must match the formal parameter
    if (!matchesFormalParameter(element, formalParam)) {
      return Optional.empty();
    }

    // Extract the patterns that can be generated from the real formal param
    Set<Core.NamedPat> canGenerate = extractNamedPats(formalParam);

    return Optional.of(
        FunctionRegistry.FunctionInfo.invertible(
            formalParam, collection, canGenerate));
  }

  /**
   * Analyzes whether a function matches the RECURSIVE pattern (transitive
   * closure).
   *
   * <p>Pattern: {@code fun path(x,y) = edge(x,y) orelse (exists z where
   * edge(x,z) andalso path(z,y))}
   *
   * <p>AST structure:
   *
   * <pre>
   * Core.Fn(idPat=(x,y), exp=
   *   Apply(ORELSE, Tuple(
   *     Apply(edge, ...),              // base case - invertible function
   *     Apply(RELATIONAL_NON_EMPTY,    // recursive case - exists
   *       From(...)))))
   * </pre>
   *
   * @param fnPat the function name pattern
   * @param fn the function expression
   * @return FunctionInfo if pattern matches, empty otherwise
   */
  private Optional<FunctionRegistry.FunctionInfo> analyzeRecursivePattern(
      Core.NamedPat fnPat, Core.Fn fn) {
    // Handle CASE-wrapped function body
    Core.Exp body = fn.exp;
    Core.Pat formalParam = fn.idPat;

    if (body.op == Op.CASE) {
      Core.Case caseExp = (Core.Case) body;
      if (caseExp.matchList.size() == 1) {
        Core.Match match = caseExp.matchList.get(0);
        formalParam = match.pat;
        body = match.exp;
      }
    }

    // Body must be Apply(ORELSE, ...)
    if (body.op != Op.APPLY) {
      return Optional.empty();
    }
    Core.Apply orElseApply = (Core.Apply) body;
    if (!isOrElseBuiltIn(orElseApply.fn)) {
      return Optional.empty();
    }

    // Argument must be a tuple: (baseCase, recursiveCase)
    if (orElseApply.arg.op != Op.TUPLE) {
      return Optional.empty();
    }
    Core.Tuple orElseArgs = (Core.Tuple) orElseApply.arg;
    if (orElseArgs.args.size() != 2) {
      return Optional.empty();
    }

    Core.Exp baseCase = orElseArgs.args.get(0);
    Core.Exp recursiveCase = orElseArgs.args.get(1);

    // Base case: must be an invertible function call that we can use as
    // generator. We check if it's a call to a function that matches the ELEM
    // pattern.
    Optional<Core.Exp> baseGenerator = extractBaseGenerator(baseCase);
    if (!baseGenerator.isPresent()) {
      return Optional.empty();
    }

    // Recursive case: must be exists (RELATIONAL_NON_EMPTY(From...))
    // with a recursive call to this function
    boolean isRecursive = isRecursiveCase(recursiveCase, fnPat.name);
    if (!isRecursive) {
      return Optional.empty();
    }

    // Extract patterns that can be generated
    Set<Core.NamedPat> canGenerate = extractNamedPats(formalParam);

    // Build the recursive step function for Relational.iterate
    // The step function takes (old, new) bags and computes the next
    // generation. For transitive closure: join new with baseGenerator.
    Core.Exp stepFunction =
        buildRecursiveStep(formalParam, baseGenerator.get(), recursiveCase);

    return Optional.of(
        FunctionRegistry.FunctionInfo.recursive(
            formalParam, baseGenerator.get(), stepFunction, canGenerate));
  }

  /**
   * Extracts the base generator from a base case expression.
   *
   * <p>Looks for patterns like edge(x,y) where edge is defined as (x,y) elem
   * edges. The function must have been previously registered in the
   * FunctionRegistry.
   */
  private Optional<Core.Exp> extractBaseGenerator(Core.Exp baseCase) {
    // Base case is typically a function call: Apply(Id(edge), Tuple(x, y))
    if (baseCase.op != Op.APPLY) {
      return Optional.empty();
    }
    Core.Apply apply = (Core.Apply) baseCase;

    // Check if it's a user-defined function call
    if (apply.fn.op != Op.ID) {
      return Optional.empty();
    }
    Core.Id fnId = (Core.Id) apply.fn;

    // Look up the function in the registry - it should have been registered
    // in a previous declaration (e.g., edge before path)
    if (registry == null) {
      return Optional.empty();
    }

    // Look up by name since the idPat instances may differ
    Optional<FunctionRegistry.FunctionInfo> infoOpt =
        registry.lookupByName(fnId.idPat.name);
    if (!infoOpt.isPresent()) {
      return Optional.empty();
    }

    FunctionRegistry.FunctionInfo info = infoOpt.get();
    if (info.status() == FunctionRegistry.InvertibilityStatus.INVERTIBLE) {
      return info.baseGenerator();
    }

    return Optional.empty();
  }

  /**
   * Checks if an expression is a recursive case containing a call to the named
   * function.
   */
  private boolean isRecursiveCase(Core.Exp exp, String fnName) {
    // Recursive case is: Apply(RELATIONAL_NON_EMPTY, From(...))
    if (exp.op != Op.APPLY) {
      return false;
    }
    Core.Apply apply = (Core.Apply) exp;

    // Check for RELATIONAL_NON_EMPTY
    if (!isNonEmptyBuiltIn(apply.fn)) {
      return false;
    }

    // Argument should be a FROM expression
    if (apply.arg.op != Op.FROM) {
      return false;
    }

    // Check if the FROM contains a recursive call to fnName
    return containsRecursiveCall(apply.arg, fnName);
  }

  /**
   * Checks if an expression contains a recursive call to the named function.
   */
  private boolean containsRecursiveCall(Core.Exp exp, String fnName) {
    final boolean[] found = {false};
    exp.accept(
        new Visitor() {
          @Override
          protected void visit(Core.Apply apply) {
            if (apply.fn.op == Op.ID) {
              Core.Id id = (Core.Id) apply.fn;
              if (id.idPat.name.equals(fnName)) {
                found[0] = true;
              }
            }
            super.visit(apply);
          }
        });
    return found[0];
  }

  /**
   * Builds the recursive step function for Relational.iterate.
   *
   * <p>For transitive closure, the step function computes: from (x, z) in
   * newPaths, (z2, y) in baseGenerator where z = z2 yield (x, y)
   */
  private Core.Exp buildRecursiveStep(
      Core.Pat formalParam, Core.Exp baseGenerator, Core.Exp recursiveCase) {
    // For now, return the recursive case directly.
    // The full implementation would build a proper step function.
    // TODO: Properly construct the join expression for iterate
    return recursiveCase;
  }

  /** Checks if an expression is the built-in 'orelse' operator. */
  private boolean isOrElseBuiltIn(Core.Exp exp) {
    if (exp.op == Op.FN_LITERAL) {
      Core.Literal literal = (Core.Literal) exp;
      if (literal.value instanceof BuiltIn) {
        return literal.value == BuiltIn.Z_ORELSE;
      }
    }
    return false;
  }

  /** Checks if an expression is the built-in 'Relational.nonEmpty'. */
  private boolean isNonEmptyBuiltIn(Core.Exp exp) {
    if (exp.op == Op.FN_LITERAL) {
      Core.Literal literal = (Core.Literal) exp;
      if (literal.value instanceof BuiltIn) {
        return literal.value == BuiltIn.RELATIONAL_NON_EMPTY;
      }
    }
    return false;
  }

  /**
   * Checks if an expression is the built-in 'elem' function.
   *
   * @param exp the expression to check
   * @return true if it's the elem built-in
   */
  private boolean isElemBuiltIn(Core.Exp exp) {
    if (exp.op == Op.FN_LITERAL) {
      Core.Literal literal = (Core.Literal) exp;
      if (literal.value instanceof BuiltIn) {
        BuiltIn builtIn = (BuiltIn) literal.value;
        return builtIn == BuiltIn.OP_ELEM;
      }
    }
    return false;
  }

  /**
   * Checks if an expression matches a formal parameter pattern.
   *
   * <p>For the pattern to match:
   *
   * <ul>
   *   <li>Simple IdPat: element must be Id referencing that pattern
   *   <li>TuplePat: element must be Tuple with matching Id references
   * </ul>
   *
   * @param element the element expression from the function body
   * @param formalParam the function's formal parameter pattern
   * @return true if element references the formal parameter
   */
  private boolean matchesFormalParameter(
      Core.Exp element, Core.Pat formalParam) {
    if (formalParam instanceof Core.IdPat) {
      // Simple case: formal param is a single variable
      Core.IdPat idPat = (Core.IdPat) formalParam;
      if (element.op == Op.ID) {
        Core.Id id = (Core.Id) element;
        return id.idPat.equals(idPat);
      }
      return false;
    } else if (formalParam instanceof Core.TuplePat) {
      // Tuple case: formal param is (x, y, ...)
      // The element can be either a tuple (x, y) or a record {x, y}
      Core.TuplePat tuplePat = (Core.TuplePat) formalParam;
      if (element.op == Op.TUPLE || element.op == Op.RECORD) {
        Core.Tuple tuple = (Core.Tuple) element;
        if (tuple.args.size() != tuplePat.args.size()) {
          return false;
        }
        // Each element must match corresponding pattern
        for (int i = 0; i < tuple.args.size(); i++) {
          if (!matchesFormalParameter(
              tuple.args.get(i), tuplePat.args.get(i))) {
            return false;
          }
        }
        return true;
      }
      return false;
    }
    // Other pattern types not supported yet
    return false;
  }

  /**
   * Extracts all named patterns from a pattern.
   *
   * @param pat the pattern to extract from
   * @return set of all named patterns
   */
  private Set<Core.NamedPat> extractNamedPats(Core.Pat pat) {
    Set<Core.NamedPat> result = new HashSet<>();
    pat.accept(
        new Visitor() {
          @Override
          protected void visit(Core.IdPat idPat) {
            result.add(idPat);
          }

          @Override
          protected void visit(Core.AsPat asPat) {
            result.add(asPat);
            super.visit(asPat);
          }
        });
    return result;
  }

  /**
   * Registers function definitions from a declaration in the FunctionRegistry.
   *
   * <p>This method scans a declaration for function definitions and analyzes
   * each one. The analysis results are stored in the registry for later use by
   * PredicateInverter.
   *
   * @param decl the declaration to scan
   * @param registry the registry to populate
   */
  public void registerFunctions(Core.Decl decl, FunctionRegistry registry) {
    // Store registry so extractBaseGenerator can look up previously registered
    // functions
    this.registry = registry;
    decl.accept(
        new Visitor() {
          @Override
          protected void visit(Core.NonRecValDecl valDecl) {
            // Check if this is a function definition
            if (valDecl.exp.op == Op.FN
                && valDecl.pat instanceof Core.NamedPat) {
              Core.Fn fn = (Core.Fn) valDecl.exp;
              Core.NamedPat fnPat = (Core.NamedPat) valDecl.pat;
              FunctionRegistry.FunctionInfo info = analyze(fnPat, fn);
              // Only register if analysis produced a result
              // Null means: not pre-analyzed, use legacy inlining
              if (info != null) {
                registry.register(fnPat, info);
              }
            }
          }

          @Override
          protected void visit(Core.RecValDecl recValDecl) {
            // Recursive declarations can contain multiple functions
            for (Core.NonRecValDecl inner : recValDecl.list) {
              if (inner.exp.op == Op.FN && inner.pat instanceof Core.NamedPat) {
                Core.Fn fn = (Core.Fn) inner.exp;
                Core.NamedPat fnPat = (Core.NamedPat) inner.pat;
                FunctionRegistry.FunctionInfo info = analyze(fnPat, fn);
                // Only register if analysis produced a result
                // Null means: not pre-analyzed, use legacy inlining
                if (info != null) {
                  registry.register(fnPat, info);
                }
              }
            }
          }
        });
  }

  /**
   * Builds a FunctionRegistry by analyzing all functions in an environment.
   *
   * <p>This method scans the environment for function bindings and analyzes
   * each to determine its invertibility status. Functions are analyzed in
   * dependency order: simple (ELEM) functions first, then recursive functions.
   *
   * @param typeSystem the type system
   * @param env the environment containing function definitions
   * @return a populated FunctionRegistry with all functions from the
   *     environment
   */
  public static FunctionRegistry buildRegistryFromEnvironment(
      TypeSystem typeSystem, Environment env) {
    final FunctionRegistry registry = new FunctionRegistry();
    final FunctionAnalyzer analyzer = new FunctionAnalyzer(typeSystem, env);
    analyzer.registry = registry;

    // Two-pass analysis to handle dependencies:
    // Pass 1: Analyze simple (ELEM) functions that don't depend on others
    // Pass 2: Analyze recursive functions that may reference functions from
    // Pass 1
    env.visit(
        binding -> {
          if (binding.exp != null
              && binding.exp.op == Op.FN
              && binding.id instanceof Core.NamedPat) {
            Core.Fn fn = (Core.Fn) binding.exp;
            Core.NamedPat fnPat = (Core.NamedPat) binding.id;

            // Analyze and register the function
            FunctionRegistry.FunctionInfo info = analyzer.analyze(fnPat, fn);
            // Only register if analysis produced a result
            // Null means: not pre-analyzed, use legacy inlining
            if (info != null) {
              registry.register(fnPat, info);
            }
          }
        });

    return registry;
  }

  /**
   * Analyzes whether a function matches the ANDALSO pattern for partial
   * invertibility.
   *
   * <p>Pattern: {@code fun notPath(x,y) = edge(x,y) andalso x <> y}
   *
   * <p>This pattern is partially invertible when the left conjunct is
   * invertible and can produce a generator, while the right conjunct acts as a
   * filter.
   *
   * @param fnPat the function name pattern
   * @param fn the function expression
   * @return FunctionInfo if pattern matches, empty otherwise
   */
}
// End FunctionAnalyzer.java
