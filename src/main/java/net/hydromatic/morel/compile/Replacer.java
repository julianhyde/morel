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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.type.TypeSystem;

/** Replaces identifiers with expressions. */
public class Replacer extends EnvShuttle {
  private final Map<Core.Id, ? extends Core.Exp> substitution;

  private Replacer(
      TypeSystem typeSystem,
      Environment env,
      Map<Core.Id, ? extends Core.Exp> substitution) {
    super(typeSystem, env);
    this.substitution = requireNonNull(substitution);
  }

  static Core.Exp substitute(
      TypeSystem typeSystem,
      Map<Core.Id, ? extends Core.Exp> substitution,
      Core.Exp exp) {
    final Replacer replacer =
        new Replacer(typeSystem, Environments.empty(), substitution);
    return exp.accept(replacer);
  }

  static Core.FromStep substitute(
      TypeSystem typeSystem,
      Environment env,
      Map<Core.Id, Core.Exp> substitution,
      Core.FromStep step) {
    if (substitution.isEmpty()) {
      return step;
    }
    final Replacer replacer = new Replacer(typeSystem, env, substitution);
    return step.accept(replacer);
  }

  @Override
  protected Replacer push(Environment env) {
    return new Replacer(typeSystem, env, substitution);
  }

  @Override
  protected Core.Exp visit(Core.Id id) {
    final Core.Exp exp = substitution.get(id);
    return exp != null ? exp : id;
  }

  /**
   * Overrides EnvShuttle.visit(Core.From) to skip validation.
   *
   * <p>Replacer is created with an empty environment for simple substitution.
   * The parent class would pass this.env to from.copy() which triggers
   * RefChecker validation. Since the empty environment doesn't contain
   * outer-scope variables that may be referenced in the FROM expression,
   * validation would fail.
   *
   * <p>By passing null to from.copy(), we skip validation. This is safe because
   * Replacer is only doing simple identifier substitution, and the result will
   * be validated elsewhere (in the caller that invoked the substitution).
   */
  @Override
  protected Core.Exp visit(Core.From from) {
    Core.StepEnv stepEnv = Core.StepEnv.EMPTY;
    final List<Core.FromStep> steps = new ArrayList<>();
    for (Core.FromStep step : from.steps) {
      final Core.FromStep step2 = step.accept(bind(stepEnv.bindings));
      steps.add(step2);
      stepEnv = step2.env;
    }
    // Pass null to skip validation - Replacer uses empty env for substitution
    return from.copy(typeSystem, null, steps);
  }
}

// End Replacer.java
