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

import static net.hydromatic.morel.ast.CoreBuilder.core;
import static net.hydromatic.morel.compile.Generators.maybeGenerator;
import static net.hydromatic.morel.util.Static.append;
import static net.hydromatic.morel.util.Static.skip;

import com.google.common.collect.ImmutableList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Shuttle;
import net.hydromatic.morel.type.ListType;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.PairList;

/** Expands generators. */
public class Expander {
  private final Root root;
  private final List<Core.Exp> constraints;

  private Expander(Root root, List<Core.Exp> constraints) {
    this.root = root;
    this.constraints = constraints;
  }

  /**
   * Converts all unbounded variables in a query to bounded, introducing
   * generators by inverting predicates.
   */
  public static Core.Exp expandFrom(
      TypeSystem typeSystem, Environment env, Core.From from) {
    final Root root = new Root(typeSystem);
    final Expander expander = new Expander(root, ImmutableList.of());
    final Map<Core.Pat, Generator> generators = new LinkedHashMap<>();

    // First, deduce generators.
    expandSteps(from.steps, expander, generators);

    // Second, substitute generators.
    final Simplifier simplifier = new Simplifier(typeSystem, generators);
    return from.accept(
        new Shuttle(typeSystem) {
          @Override
          protected Core.Scan visit(Core.Scan scan) {
            final Generator generator = generators.get(scan.pat);
            if (generator != null) {
              scan =
                  scan.copy(scan.env, scan.pat, generator.exp, scan.condition);
            }
            return super.visit(scan);
          }

          @Override
          protected Core.Exp visit(Core.Apply apply) {
            Core.Exp exp1 = super.visit(apply);
            final Core.Exp exp2 = simplifier.simplify(exp1);
            if (exp2 == apply) {
              return apply;
            }
            return exp2.accept(this);
          }
        });
  }

  static void expandSteps(
      List<Core.FromStep> steps,
      Expander expander,
      Map<Core.Pat, Generator> generators) {
    if (steps.isEmpty()) {
      return;
    }
    final Core.FromStep step0 = steps.get(0);
    switch (step0.op) {
      case SCAN:
        final Core.Scan scan = (Core.Scan) step0;
        final Generator generator = Generators.maybeExtent(scan.exp);
        if (generator != null) {
          // The first attempt at a generator is the extent of the type.
          // Usually finite, but finite for types like 'bool option'.
          generators.put(scan.pat, generator);
        }
        break;

      case WHERE:
        final Core.Where where = (Core.Where) step0;
        final List<Core.Exp> conditions = core.decomposeAnd(where.exp);
        for (Core.Exp condition : conditions) {
          expander = expander.plusConstraint(condition);
          expander.improveGenerators(generators);
        }
        break;
    }
    expandSteps(skip(steps), expander, generators);
  }

  /**
   * Tries to improve the existing generators.
   *
   * <p>This means replacing them with a lower cardinality - an infinite
   * generator with a finite generator, or a finite generator with one that is a
   * single value or is empty.
   */
  private void improveGenerators(Map<Core.Pat, Generator> generators) {
    final PairList<Core.Pat, Generator> improved = PairList.of();

    generators.forEach(
        (pat, generator) -> {
          if (generator.cardinality == Generator.Cardinality.INFINITE
              && pat instanceof Core.NamedPat) {
            final boolean ordered = generator.exp.type instanceof ListType;

            final Generator generator1 =
                maybeGenerator(root.typeSystem, pat, ordered, constraints);
            if (generator1 != null) {
              improved.add(pat, generator1);
            }
          }
        });
    improved.forEach(generators::put);
  }

  private Expander plusConstraint(Core.Exp constraint) {
    return withConstraints(append(this.constraints, constraint));
  }

  private Expander withConstraints(List<Core.Exp> constraints) {
    return new Expander(root, constraints);
  }

  /** Root expander. */
  static class Root {
    final TypeSystem typeSystem;

    Root(TypeSystem typeSystem) {
      this.typeSystem = typeSystem;
    }
  }
}

// End Expander.java
