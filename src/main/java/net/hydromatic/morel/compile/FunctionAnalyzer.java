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

    // TODO: Phase 1b - Check for RECURSIVE pattern (transitive closure)
    // Optional<FunctionRegistry.FunctionInfo> recursiveResult =
    //     analyzeRecursivePattern(fnPat, fn);
    // if (recursiveResult.isPresent()) {
    //   return recursiveResult.get();
    // }

    // Default: not invertible
    return FunctionRegistry.FunctionInfo.notInvertible(fn.idPat);
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
      Core.TuplePat tuplePat = (Core.TuplePat) formalParam;
      if (element.op == Op.TUPLE) {
        Core.Tuple tuple = (Core.Tuple) element;
        if (tuple.args.size() != tuplePat.args.size()) {
          return false;
        }
        // Each tuple element must match corresponding pattern
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
              registry.register(fnPat, info);
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
                registry.register(fnPat, info);
              }
            }
          }
        });
  }
}
// End FunctionAnalyzer.java
