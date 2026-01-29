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

import static com.google.common.collect.Iterables.getLast;
import static net.hydromatic.morel.ast.CoreBuilder.core;
import static net.hydromatic.morel.compile.Generators.maybeGenerator;
import static net.hydromatic.morel.util.Static.append;
import static net.hydromatic.morel.util.Static.forEachInIntersection;
import static net.hydromatic.morel.util.Static.skip;
import static net.hydromatic.morel.util.Static.transformEager;
import static net.hydromatic.morel.util.Static.transformToMap;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.FromBuilder;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.ast.Shuttle;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.ListType;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.PairList;

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
   *
   * <p>Returns {@code from} unchanged if no expansion is required.
   */
  public static Core.From expandFrom(
      TypeSystem typeSystem, Environment env, Core.From from) {
    return expandFrom(typeSystem, env, from, new FunctionRegistry());
  }

  /**
   * Converts all unbounded variables in a query to bounded, using a function
   * registry for predicate inversion.
   *
   * @param functionRegistry Registry containing pre-analyzed function
   *     invertibility info
   */
  public static Core.From expandFrom(
      TypeSystem typeSystem,
      Environment env,
      Core.From from,
      FunctionRegistry functionRegistry) {
    final Core.From outerFrom = from;
    final Generators.Cache cache =
        new Generators.Cache(typeSystem, env, functionRegistry);
    final Expander expander = new Expander(cache, ImmutableList.of());

    // First, deduce generators.
    expandSteps(from.steps, expander);

    // Second, check that we found a generator for each pattern (infinite
    // extent).
    final StepVarSet stepVars = StepVarSet.create(from, typeSystem);
    for (Core.FromStep step : from.steps) {
      if (step.op == Op.SCAN) {
        final Core.Scan scan = (Core.Scan) step;
        if (scan.exp.isExtent()) {
          final List<Core.NamedPat> namedPats = scan.pat.expand();
          for (Core.NamedPat namedPat : namedPats) {
            if (!stepVars.usedPats.contains(namedPat)) {
              // Ignore patterns that are not used. For example, "y" is unused
              // in "exists x, y where x elem [1,2,3]".
              continue;
            }
            final Generator generator = cache.bestGenerator(namedPat);
            if (generator == null
                || generator.cardinality == Generator.Cardinality.INFINITE) {
              // Pattern cannot be grounded - the predicate couldn't be inverted
              // to produce a finite generator. This typically happens with:
              // - User-defined recursive functions that aren't invertible
              // - Complex predicates without finite solutions
              throw new IllegalArgumentException(
                  "pattern "
                      + namedPat
                      + " cannot be grounded: "
                      + "predicate inversion failed to produce a finite generator");
            }
          }
        }
      }
    }

    // Third, substitute generators.
    if (true) {
      Core.From from2 = expandFrom2(cache, env, stepVars);
      return from2.equals(from) ? from : from2;
    }

    final Simplifier simplifier = new Simplifier(typeSystem, cache.generators);
    return (Core.From)
        from.accept(
            new Shuttle(typeSystem) {
              @Override
              protected Core.Exp visit(Core.From from) {
                final Core.From from2;
                if (from == outerFrom) {
                  from2 = expandFrom2(cache, env, stepVars);
                } else if (false) {
                  // Don't recurse into subqueries.
                  return from;
                } else {
                  final StepVarSet stepVars2 =
                      StepVarSet.create(from, typeSystem);
                  from2 = expandFrom2(cache, env, stepVars2);
                }
                return from2;
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

  private static Core.From expandFrom2(
      Generators.Cache cache, Environment env, StepVarSet stepVarSet) {
    // Build the set of patterns that are assigned in some scan.
    final TypeSystem typeSystem = cache.typeSystem;
    final Set<Core.NamedPat> done = new HashSet<>();
    final Set<Core.NamedPat> allPats = new HashSet<>();
    final Map<Core.NamedPat, Generator> generatorMap = new HashMap<>();
    stepVarSet.stepVars.forEach(
        (step, vars) -> {
          if (step.op == Op.SCAN) {
            final Core.Scan scan = (Core.Scan) step;
            final List<Core.NamedPat> namedPats = scan.pat.expand();
            if (scan.exp.isExtent()) {
              for (Core.NamedPat namedPat : namedPats) {
                if (!stepVarSet.usedPats.contains(namedPat)) {
                  // Ignore patterns that are not used. For example, "y" is
                  // unused in "exists x, y where x elem [1,2,3]".
                  continue;
                }
                final Generator generator = cache.bestGenerator(namedPat);
                if (generator != null) {
                  generatorMap.put(namedPat, generator);
                }
              }
              allPats.addAll(namedPats);
            }
          }
        });

    final FromBuilder fromBuilder = core.fromBuilder(typeSystem);
    final Map<Core.Id, Core.Exp> substitution = new HashMap<>();
    stepVarSet.stepVars.forEach(
        (step, freePats) -> {
          // Pull forward any generators.
          for (Core.NamedPat freePat : freePats) {
            addGeneratorScan(
                typeSystem, done, freePat, generatorMap, allPats, fromBuilder);
          }

          if (step instanceof Core.Scan) {
            final Core.Scan scan = (Core.Scan) step;
            if (scan.exp.isExtent()) {
              for (Core.NamedPat p : scan.pat.expand()) {
                // Skip scan variables that are not used, e.g. "y" in
                // "exists x, y where x elem [1, 2]".
                if (stepVarSet.usedPats.contains(p)) {
                  addGeneratorScan(
                      typeSystem, done, p, generatorMap, allPats, fromBuilder);
                }
              }
              if (scan.env.atom
                  && fromBuilder.stepEnv().bindings.size() == 1
                  && !fromBuilder.stepEnv().atom) {
                final Binding binding =
                    Iterables.getOnlyElement(fromBuilder.stepEnv().bindings);
                fromBuilder.yield_(core.id(binding.id));
              }
              return;
            }
            // The pattern(s) defined in the scan are now available to
            // subsequent steps.
            done.addAll(scan.pat.expand());
          }

          // The step is not a scan over an extent. Add it now.
          step = Replacer.substitute(typeSystem, env, substitution, step);

          // For WHERE steps, simplify the condition using generators.
          // If a generator was created from a predicate (e.g., path(p) was
          // inverted to produce an iterate expression), the original predicate
          // should be removed from the WHERE clause.
          if (step instanceof Core.Where) {
            final Core.Where where = (Core.Where) step;
            final Simplifier simplifier =
                new Simplifier(typeSystem, cache.generators);
            final Core.Exp simplified = simplifier.simplify(where.exp);
            if (simplified.op == Op.BOOL_LITERAL
                && Boolean.TRUE.equals(((Core.Literal) simplified).value)) {
              // Condition simplified to true, skip the WHERE step
              return;
            }
            if (simplified != where.exp) {
              // Condition was simplified, use the simplified version
              fromBuilder.where(simplified);
              return;
            }
          }
          fromBuilder.addAll(ImmutableList.of(step));
        });

    return fromBuilder.build();
  }

  /**
   * Adds a scan that generates {@code freePat}.
   *
   * <p>Does nothing if {@code freePat} is not an unbounded variable, or if it
   * already has a scan.
   *
   * <p>If the generator expression depends on other unbounded variables, adds
   * those variables' generators first.
   */
  private static void addGeneratorScan(
      TypeSystem typeSystem,
      Set<Core.NamedPat> done,
      Core.NamedPat freePat,
      Map<Core.NamedPat, Generator> generatorMap,
      Set<Core.NamedPat> allPats,
      FromBuilder fromBuilder) {
    if (done.contains(freePat) || !allPats.contains(freePat)) {
      return;
    }

    // Find a generator, and find which patterns it depends on.
    final Generator generator = generatorMap.get(freePat);

    // Make sure all dependencies have a scan.
    for (Core.NamedPat p : generator.freePats) {
      addGeneratorScan(typeSystem, done, p, generatorMap, allPats, fromBuilder);
    }

    // The patterns we need (requiredPats) are those provided by the generator,
    // which are used in later steps (allPats),
    // and are not provided by earlier steps (done).
    // Deduplicate by name to avoid "Multiple entries with same key" errors
    // when recursive inversion creates patterns with the same name.
    final List<Core.NamedPat> expandedPats = generator.pat.expand();
    final List<Core.NamedPat> requiredPats =
        new ArrayList<>(expandedPats.size());
    final java.util.Set<String> seenNames = new java.util.HashSet<>();
    for (Core.NamedPat p : expandedPats) {
      if (allPats.contains(p) && !done.contains(p) && seenNames.add(p.name)) {
        requiredPats.add(p);
      }
    }

    // Now all dependencies are done, add a scan for the generator.
    if (expandedPats.equals(requiredPats)) {
      // Add "join (x, y, z) in collection".
      fromBuilder.scan(generator.pat, generator.exp);
    } else {
      // Add "join (x, z) in (from (x, y, z) in collection yield {x, z})".
      final FromBuilder fromBuilder2 = core.fromBuilder(typeSystem);
      fromBuilder2.scan(generator.pat, generator.exp);
      ImmutableMap<String, Core.Exp> nameExps =
          transformToMap(requiredPats, (p, c) -> c.accept(p.name, core.id(p)));
      fromBuilder2.yield_(core.record(typeSystem, nameExps));
      fromBuilder2.distinct();

      final Core.Pat recordPat =
          core.recordPat(
              typeSystem,
              transformToMap(requiredPats, (p, c) -> c.accept(p.name, p)));
      fromBuilder.scan(recordPat, fromBuilder2.build());
    }
    done.addAll(requiredPats);
  }

  /** Returns either "p" or "(p, q, r)". */
  static Core.Pat singleOrTuplePat(
      TypeSystem typeSystem, List<Core.NamedPat> namedPats) {
    if (namedPats.size() == 1) {
      return namedPats.get(0);
    } else {
      return core.tuplePat(typeSystem, namedPats);
    }
  }

  static void expandSteps(List<Core.FromStep> steps, Expander expander) {
    if (steps.isEmpty()) {
      return;
    }
    final Generators.Cache cache = expander.cache;
    final Core.FromStep step0 = steps.get(0);
    switch (step0.op) {
      case SCAN:
        final Core.Scan scan = (Core.Scan) step0;
        // The first attempt at a generator is the extent of the type.
        // Usually finite, but finite for types like 'bool option'.
        Generators.maybeExtent(cache, scan.pat, scan.exp);
        break;

      case WHERE:
        final Core.Where where = (Core.Where) step0;
        final List<Core.Exp> conditions = core.decomposeAnd(where.exp);
        for (Core.Exp condition : conditions) {
          expander = expander.plusConstraint(condition);
          expander.improveGenerators(cache.generators);
        }
        break;
    }
    expandSteps(skip(steps), expander);
  }

  /**
   * Tries to improve the existing generators.
   *
   * <p>This means replacing each generator with one of lower cardinality - an
   * infinite generator with a finite generator, or a finite generator with one
   * that is a single value or is empty.
   */
  private void improveGenerators(
      Multimap<Core.NamedPat, Generator> generators) {
    // Create a snapshot of the generators map, to avoid concurrent
    // modification.
    final PairList<Core.NamedPat, Generator> infiniteGenerators = PairList.of();
    generators.forEach(
        (pat, generator) -> {
          if (generator.cardinality == Generator.Cardinality.INFINITE) {
            infiniteGenerators.add(pat, generator);
          }
        });

    infiniteGenerators.forEach(
        (pat, generator) -> {
          final boolean ordered = generator.exp.type instanceof ListType;
          if (maybeGenerator(cache, pat, ordered, constraints)) {
            Generator g = getLast(cache.generators.get(pat));
            g.pat.expand().forEach(p2 -> generators.put(p2, g));
          }
        });
  }

  private Expander plusConstraint(Core.Exp constraint) {
    return withConstraints(append(this.constraints, constraint));
  }

  private Expander withConstraints(List<Core.Exp> constraints) {
    return new Expander(cache, constraints);
  }

  /** Finds free variables in an expression. */
  static class FreeFinder extends EnvVisitor {
    final List<Core.NamedPat> freePats;
    final PairList<Core.FromStep, Set<Core.NamedPat>> stepVars;

    private FreeFinder(
        TypeSystem typeSystem,
        Environment env,
        Deque<FromContext> fromStack,
        List<Core.NamedPat> freePats,
        PairList<Core.FromStep, Set<Core.NamedPat>> stepVars) {
      super(typeSystem, env, fromStack);
      this.freePats = freePats;
      this.stepVars = stepVars;
    }

    @Override
    protected EnvVisitor push(Environment env) {
      return new FreeFinder(typeSystem, env, fromStack, freePats, stepVars);
    }

    //    @Override
    //    protected void visit(Core.From from) {
    // Recurse into the root query, not into subqueries.
    //      if (fromStack.isEmpty()) {
    //        super.visit(from);
    //      }
    //    }

    @Override
    protected void visit(Core.Id id) {
      freePats.add(id.idPat);
    }

    @Override
    public void visitStep(Core.FromStep step, Core.StepEnv stepEnv) {
      if (!fromStack.isEmpty()) {
        super.visitStep(step, stepEnv);
        return;
      }
      freePats.clear();
      super.visitStep(step, stepEnv);
      final ImmutableSet.Builder<Core.NamedPat> namedPats =
          ImmutableSet.builder();
      forEachInIntersection(
          freePats,
          transformEager(stepEnv.bindings, b -> b.id),
          namedPats::add);
      stepVars.add(step, namedPats.build());
    }
  }

  /** Analysis of the variables used in each step of a query. */
  static class StepVarSet {
    private final PairList<Core.FromStep, Set<Core.NamedPat>> stepVars;
    private final Set<Core.NamedPat> usedPats;

    StepVarSet(PairList<Core.FromStep, Set<Core.NamedPat>> stepVars) {
      this.stepVars = stepVars.immutable();
      this.usedPats =
          stepVars.rightList().stream()
              .flatMap(Collection::stream)
              .collect(ImmutableSet.toImmutableSet());
    }

    /**
     * Given a query, returns a list of the steps and the variables from the
     * step environment used by each step.
     */
    static StepVarSet create(Core.From from, TypeSystem typeSystem) {
      final PairList<Core.FromStep, Set<Core.NamedPat>> list = PairList.of();
      final Environment env = Environments.empty();
      final FreeFinder visitor =
          new FreeFinder(
              typeSystem, env, new ArrayDeque<>(), new ArrayList<>(), list);
      from.accept(visitor);
      return new StepVarSet(list);
    }
  }
}

// End Expander.java
