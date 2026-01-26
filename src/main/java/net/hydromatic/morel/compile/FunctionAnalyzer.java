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

import com.google.common.collect.ImmutableSet;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.ast.Visitor;
import net.hydromatic.morel.type.TypeSystem;

/**
 * Analyzes function definitions to determine their invertibility.
 *
 * <p>This analyzer examines function bodies to detect patterns that can be
 * inverted to produce generators:
 *
 * <ul>
 *   <li>Simple elem: {@code (x, y) elem edges} inverts to generator {@code
 *       edges}
 *   <li>Transitive closure: {@code base orelse (exists z where recursive)}
 *       requires Relational.iterate
 * </ul>
 *
 * <p>Functions are analyzed ONCE at compile time and their invertibility status
 * is cached in a {@link FunctionRegistry}.
 */
public class FunctionAnalyzer {
  private final TypeSystem typeSystem;

  public FunctionAnalyzer(TypeSystem typeSystem) {
    this.typeSystem = requireNonNull(typeSystem, "typeSystem");
  }

  /**
   * Analyzes a function definition and returns its invertibility info.
   *
   * @param fnPat the function's name pattern
   * @param fnExp the function's body expression (should be a Core.Fn)
   * @return the function's invertibility info
   */
  public FunctionRegistry.FunctionInfo analyze(
      Core.NamedPat fnPat, Core.Exp fnExp) {
    if (fnExp.op != Op.FN) {
      return FunctionRegistry.FunctionInfo.notInvertible(fnPat);
    }

    Core.Fn fn = (Core.Fn) fnExp;
    Core.Pat formalParam = fn.idPat;
    Core.Exp body = fn.exp;

    // Check if this is a recursive function (references itself)
    boolean isRecursive = referencesFunction(body, fnPat);

    if (isRecursive) {
      return analyzeRecursive(fnPat, formalParam, body);
    } else {
      return analyzeNonRecursive(fnPat, formalParam, body);
    }
  }

  /**
   * Analyzes a non-recursive function.
   *
   * <p>Looks for simple patterns like {@code x elem collection}.
   */
  private FunctionRegistry.FunctionInfo analyzeNonRecursive(
      Core.NamedPat fnPat, Core.Pat formalParam, Core.Exp body) {
    // Pattern 1: body is "arg elem collection"
    Optional<Core.Exp> elemCollection = extractElemCollection(body);
    if (elemCollection.isPresent()) {
      Set<Core.NamedPat> canGenerate = collectNamedPats(formalParam);
      return FunctionRegistry.FunctionInfo.invertible(
          formalParam, elemCollection.get(), canGenerate);
    }

    // Pattern 2: body is conjunction with elem as one term
    // e.g., "x > 0 andalso (x, y) elem pairs"
    Optional<ElemWithFilters> elemWithFilters = extractElemWithFilters(body);
    if (elemWithFilters.isPresent()) {
      Set<Core.NamedPat> canGenerate = collectNamedPats(formalParam);
      return FunctionRegistry.FunctionInfo.partiallyInvertible(
          formalParam,
          elemWithFilters.get().collection,
          canGenerate,
          elemWithFilters.get().filters);
    }

    return FunctionRegistry.FunctionInfo.notInvertible(formalParam);
  }

  /**
   * Analyzes a recursive function.
   *
   * <p>Looks for the transitive closure pattern: {@code base orelse (exists z
   * where step)}.
   */
  private FunctionRegistry.FunctionInfo analyzeRecursive(
      Core.NamedPat fnPat, Core.Pat formalParam, Core.Exp body) {
    // Pattern: baseCase orelse recursiveCase
    if (body.op == Op.APPLY) {
      Core.Apply apply = (Core.Apply) body;
      if (apply.isCallTo(BuiltIn.Z_ORELSE)) {
        Core.Exp baseCase = apply.arg(0);
        Core.Exp recursiveCase = apply.arg(1);

        // Analyze base case - should be invertible
        Optional<Core.Exp> baseGenerator = extractElemCollection(baseCase);
        if (baseGenerator.isPresent()) {
          // For now, mark as RECURSIVE with the base generator
          // The step function will be constructed during inversion
          Set<Core.NamedPat> canGenerate = collectNamedPats(formalParam);
          return FunctionRegistry.FunctionInfo.recursive(
              formalParam,
              baseGenerator.get(),
              recursiveCase, // Store recursive case for later analysis
              canGenerate);
        }
      }
    }

    // Could not analyze as transitive closure
    return FunctionRegistry.FunctionInfo.notInvertible(formalParam);
  }

  /**
   * Extracts the collection from an "elem" expression.
   *
   * <p>For {@code x elem collection}, returns {@code collection}.
   */
  private Optional<Core.Exp> extractElemCollection(Core.Exp exp) {
    if (exp.op == Op.APPLY) {
      Core.Apply apply = (Core.Apply) exp;
      if (apply.isCallTo(BuiltIn.OP_ELEM)) {
        // apply.arg(0) is the element, apply.arg(1) is the collection
        return Optional.of(apply.arg(1));
      }
    }
    return Optional.empty();
  }

  /**
   * Extracts elem with additional filters from a conjunction.
   *
   * <p>For {@code x > 0 andalso (x, y) elem pairs}, returns the collection and
   * the filter expressions.
   */
  private Optional<ElemWithFilters> extractElemWithFilters(Core.Exp exp) {
    if (exp.op != Op.APPLY) {
      return Optional.empty();
    }
    Core.Apply apply = (Core.Apply) exp;
    if (!apply.isCallTo(BuiltIn.Z_ANDALSO)) {
      return Optional.empty();
    }

    // Decompose the conjunction
    Set<Core.Exp> filters = new HashSet<>();
    Core.Exp collection = null;

    for (Core.Exp arg : apply.args()) {
      Optional<Core.Exp> elemColl = extractElemCollection(arg);
      if (elemColl.isPresent()) {
        if (collection != null) {
          // Multiple elem expressions - too complex
          return Optional.empty();
        }
        collection = elemColl.get();
      } else {
        filters.add(arg);
      }
    }

    if (collection != null) {
      return Optional.of(new ElemWithFilters(collection, filters));
    }
    return Optional.empty();
  }

  /** Result of extracting elem with filters. */
  private static class ElemWithFilters {
    final Core.Exp collection;
    final Set<Core.Exp> filters;

    ElemWithFilters(Core.Exp collection, Set<Core.Exp> filters) {
      this.collection = collection;
      this.filters = filters;
    }
  }

  /** Checks if an expression references a specific function. */
  private boolean referencesFunction(Core.Exp exp, Core.NamedPat fnPat) {
    final boolean[] found = {false};
    exp.accept(
        new Visitor() {
          @Override
          protected void visit(Core.Id id) {
            if (id.idPat.equals(fnPat)) {
              found[0] = true;
            }
          }
        });
    return found[0];
  }

  /** Collects all named patterns from a pattern. */
  private Set<Core.NamedPat> collectNamedPats(Core.Pat pat) {
    Set<Core.NamedPat> result = new HashSet<>();
    collectNamedPatsRecursive(pat, result);
    return ImmutableSet.copyOf(result);
  }

  private void collectNamedPatsRecursive(
      Core.Pat pat, Set<Core.NamedPat> result) {
    switch (pat.op) {
      case ID_PAT:
        result.add((Core.IdPat) pat);
        break;
      case TUPLE_PAT:
        Core.TuplePat tuplePat = (Core.TuplePat) pat;
        for (Core.Pat arg : tuplePat.args) {
          collectNamedPatsRecursive(arg, result);
        }
        break;
      case RECORD_PAT:
        Core.RecordPat recordPat = (Core.RecordPat) pat;
        for (Core.Pat arg : recordPat.args) {
          collectNamedPatsRecursive(arg, result);
        }
        break;
      default:
        // Other pattern types don't contribute named patterns
        break;
    }
  }

  /**
   * Populates a FunctionRegistry by analyzing all function definitions in a
   * declaration.
   *
   * @param decl the declaration to analyze
   * @param registry the registry to populate
   */
  public void analyzeDecl(Core.Decl decl, FunctionRegistry registry) {
    if (decl instanceof Core.RecValDecl) {
      Core.RecValDecl recValDecl = (Core.RecValDecl) decl;
      for (Core.NonRecValDecl valDecl : recValDecl.list) {
        if (valDecl.pat instanceof Core.NamedPat) {
          Core.NamedPat fnPat = (Core.NamedPat) valDecl.pat;
          FunctionRegistry.FunctionInfo info = analyze(fnPat, valDecl.exp);
          registry.register(fnPat, info);
        }
      }
    }
  }
}
// End FunctionAnalyzer.java
