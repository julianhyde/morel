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
import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.FromBuilder;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.ast.Shuttle;
import net.hydromatic.morel.type.ListType;
import net.hydromatic.morel.type.TypeSystem;

/** Expands generators. */
public class Expander {
  private final Generators.Cache cache;
  private final List<Core.Exp> constraints;

  private Expander(Generators.Cache cache, List<Core.Exp> constraints) {
    this.cache = cache;
    this.constraints = constraints;
  }

  /**
   * Converts all unbounded variables in a query to bounded, introducing
   * generators by inverting predicates.
   */
  public static Core.Exp expandFrom(
      TypeSystem typeSystem, Environment env, Core.From from) {
    final Generators.Cache cache = new Generators.Cache(typeSystem);
    final Expander expander = new Expander(cache, ImmutableList.of());
    final Multimap<Core.NamedPat, Generator> generators =
        MultimapBuilder.hashKeys().arrayListValues().build();
    final Set<Core.NamedPat> done = new HashSet<>();

    // First, deduce generators.
    expandSteps(from.steps, expander, generators);

    // Second, substitute generators.
    final Simplifier simplifier = new Simplifier(typeSystem, generators);
    return from.accept(
        new Shuttle(typeSystem) {
          @Override
          protected Core.Exp visit(Core.From from) {
            final FromBuilder fromBuilder = core.fromBuilder(typeSystem);
            final Map<Core.Id, Core.Exp> substitution = new HashMap<>();
            for (Core.FromStep step : from.steps) {
              if (step.op == Op.SCAN) {
                final Core.Scan scan = (Core.Scan) step;
                for (Core.NamedPat namedPat : scan.pat.expand()) {
                  if (done.contains(namedPat)) {
                    continue;
                  }
                  Generator bestGenerator = null;
                  for (Generator generator : generators.get(namedPat)) {
                    bestGenerator = generator;
                  }
                  if (bestGenerator == null) {
                    fromBuilder.scan(scan.pat, scan.exp, scan.condition);
                  } else {
                    done.addAll(bestGenerator.pat.expand());
                    if (bestGenerator instanceof Generators.SubGenerator) {
                      // The query "from x, y where (x, y) elem links" creates a
                      // sub-generator for "x" with expression "z.1", and its
                      // parent "z as (x, y)" with expression "links".
                      // The expanded query is
                      // "from z in links, x in [z.1], y in [z.2] yield {x, y}".
                      final Generators.SubGenerator subGenerator =
                          (Generators.SubGenerator) bestGenerator;
                      fromBuilder.scan(
                          subGenerator.pat,
                          subGenerator.parent.exp,
                          core.boolLiteral(true));
                      // fromBuilder.scan(scan.pat,
                      //     subGenerator.exp,
                      //     scan.condition);
                      substitution.put(
                          core.id((Core.NamedPat) scan.pat), subGenerator.exp);
                    } else {
                      fromBuilder.scan(
                          bestGenerator.pat, bestGenerator.exp, scan.condition);
                    }
                  }
                }
              } else {
                step = Replacer.substitute(typeSystem, env, substitution, step);
                fromBuilder.addAll(ImmutableList.of(step));
              }
            }

            final Core.From from2 = fromBuilder.build();
            return from2.equals(from) ? from : from2;
          }

          /** Converts a list of patterns to a tuple or a single pattern. */
          private Core.Pat getPat(List<Core.NamedPat> patList) {
            return patList.size() == 1
                ? patList.get(0)
                : core.tuplePat(typeSystem, patList);
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
      Multimap<Core.NamedPat, Generator> generators) {
    if (steps.isEmpty()) {
      return;
    }
    final Core.FromStep step0 = steps.get(0);
    switch (step0.op) {
      case SCAN:
        final Core.Scan scan = (Core.Scan) step0;
        final Generator generator = Generators.maybeExtent(scan.pat, scan.exp);
        if (generator != null) {
          // The first attempt at a generator is the extent of the type.
          // Usually finite, but finite for types like 'bool option'.
          scan.pat.expand().forEach(p -> generators.put(p, generator));
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
  private void improveGenerators(
      Multimap<Core.NamedPat, Generator> generators) {
    final List<Generator> improved = new ArrayList<>();

    generators.forEach(
        (pat, generator) -> {
          if (generator.cardinality == Generator.Cardinality.INFINITE) {
            final boolean ordered = generator.exp.type instanceof ListType;

            final Generator generator1 =
                maybeGenerator(cache, pat, ordered, constraints);
            if (generator1 != null) {
              improved.add(generator1);
            }
          }
        });
    improved.forEach(g -> g.pat.expand().forEach(p2 -> generators.put(p2, g)));
  }

  private Expander plusConstraint(Core.Exp constraint) {
    return withConstraints(append(this.constraints, constraint));
  }

  private Expander withConstraints(List<Core.Exp> constraints) {
    return new Expander(cache, constraints);
  }
}

// End Expander.java
