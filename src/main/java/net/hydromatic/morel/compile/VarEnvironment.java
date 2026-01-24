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

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.hydromatic.morel.ast.Core;

/**
 * Immutable environment tracking variable bindings and classifications during
 * PPT construction.
 *
 * <p>This environment is threaded through tree construction, with new bindings
 * added as generators are discovered.
 *
 * <p>Variable classifications:
 *
 * <ul>
 *   <li><b>Goal variables</b>: Variables we want to generate values for
 *   <li><b>Bound variables</b>: Variables with known generators
 *   <li><b>Join variables</b>: Variables that link multiple predicates
 * </ul>
 *
 * <p><b>Immutability</b>: All updates create new VarEnvironment instances
 * rather than modifying the current one. This enables safe sharing and enables
 * backtracking during PPT construction.
 */
public class VarEnvironment {
  /** Variables in the output tuple (what we want to generate). */
  public final ImmutableSet<Core.NamedPat> goalPats;

  /** Variables already bound with generators. */
  public final ImmutableMap<Core.NamedPat, PredicateInverter.Generator>
      boundVars;

  /** Join variables identified during analysis. */
  public final ImmutableSet<Core.NamedPat> joinVars;

  /** The typing environment for function lookups. */
  public final Environment typeEnv;

  private VarEnvironment(
      ImmutableSet<Core.NamedPat> goalPats,
      ImmutableMap<Core.NamedPat, PredicateInverter.Generator> boundVars,
      ImmutableSet<Core.NamedPat> joinVars,
      Environment typeEnv) {
    this.goalPats = requireNonNull(goalPats, "goalPats");
    this.boundVars = requireNonNull(boundVars, "boundVars");
    this.joinVars = requireNonNull(joinVars, "joinVars");
    this.typeEnv = requireNonNull(typeEnv, "typeEnv");
  }

  /**
   * Creates initial environment for PPT construction.
   *
   * @param goals Variables we want to generate
   * @param typeEnv Environment for function lookups
   * @return New VarEnvironment with goals set
   */
  public static VarEnvironment initial(
      List<Core.NamedPat> goals, Environment typeEnv) {
    return new VarEnvironment(
        ImmutableSet.copyOf(goals),
        ImmutableMap.of(),
        ImmutableSet.of(),
        typeEnv);
  }

  /**
   * Creates initial environment with pre-existing generators.
   *
   * @param goals Variables we want to generate
   * @param initialGenerators Pre-existing generators for some variables
   * @param typeEnv Environment for function lookups
   * @return New VarEnvironment
   */
  public static VarEnvironment initial(
      List<Core.NamedPat> goals,
      ImmutableMap<Core.NamedPat, PredicateInverter.Generator>
          initialGenerators,
      Environment typeEnv) {
    return new VarEnvironment(
        ImmutableSet.copyOf(goals),
        initialGenerators,
        ImmutableSet.of(),
        typeEnv);
  }

  /**
   * Creates new environment with additional bound variable.
   *
   * @param pat Variable pattern to bind
   * @param gen Generator for the variable
   * @return New environment with binding added
   */
  public VarEnvironment withBound(
      Core.NamedPat pat, PredicateInverter.Generator gen) {
    return new VarEnvironment(
        goalPats,
        ImmutableMap.<Core.NamedPat, PredicateInverter.Generator>builder()
            .putAll(boundVars)
            .put(pat, gen)
            .buildOrThrow(),
        joinVars,
        typeEnv);
  }

  /**
   * Creates new environment with identified join variable.
   *
   * @param pat Variable identified as a join variable
   * @return New environment with join variable added
   */
  public VarEnvironment withJoinVar(Core.NamedPat pat) {
    if (joinVars.contains(pat)) {
      return this;
    }
    return new VarEnvironment(
        goalPats,
        boundVars,
        ImmutableSet.<Core.NamedPat>builder().addAll(joinVars).add(pat).build(),
        typeEnv);
  }

  /**
   * Creates new environment with multiple join variables.
   *
   * @param pats Variables identified as join variables
   * @return New environment with join variables added
   */
  public VarEnvironment withJoinVars(Set<Core.NamedPat> pats) {
    ImmutableSet<Core.NamedPat> newJoinVars =
        ImmutableSet.<Core.NamedPat>builder()
            .addAll(joinVars)
            .addAll(pats)
            .build();
    if (newJoinVars.equals(joinVars)) {
      return this;
    }
    return new VarEnvironment(goalPats, boundVars, newJoinVars, typeEnv);
  }

  /** Returns true if pat is a goal variable. */
  public boolean isGoal(Core.NamedPat pat) {
    return goalPats.contains(pat);
  }

  /** Returns true if pat is bound to a generator. */
  public boolean isBound(Core.NamedPat pat) {
    return boundVars.containsKey(pat);
  }

  /** Returns true if pat is identified as a join variable. */
  public boolean isJoin(Core.NamedPat pat) {
    return joinVars.contains(pat);
  }

  /** Returns the generator for a bound variable, or empty if not bound. */
  public Optional<PredicateInverter.Generator> getGenerator(Core.NamedPat pat) {
    return Optional.ofNullable(boundVars.get(pat));
  }

  /** Returns all unbound goal variables. */
  public Set<Core.NamedPat> unboundGoals() {
    return goalPats.stream()
        .filter(p -> !isBound(p))
        .collect(ImmutableSet.toImmutableSet());
  }

  @Override
  public String toString() {
    return String.format(
        "VarEnvironment{goals=%s, bound=%s, joins=%s}",
        goalPats.stream()
            .map(p -> p.name)
            .collect(java.util.stream.Collectors.toList()),
        boundVars.keySet().stream()
            .map(p -> p.name)
            .collect(java.util.stream.Collectors.toList()),
        joinVars.stream()
            .map(p -> p.name)
            .collect(java.util.stream.Collectors.toList()));
  }
}

// End VarEnvironment.java
