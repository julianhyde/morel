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

import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.Pair;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;

/**
 * Shuttle that builds a list of variables that are defined outside the
 * scope that created the shuttle.
 */
abstract class VariableCollector extends EnvVisitor {
  private VariableCollector(TypeSystem typeSystem, Environment env,
      Deque<FromContext> fromStack) {
    super(typeSystem, env, fromStack);
  }

  /** Creates a variable collector. */
  public static VariableCollector create(TypeSystem typeSystem, Environment env,
      Consumer<Core.Id> consumer) {
    return new RootVariableCollector(typeSystem, env, consumer);
  }

  @Override protected SubVariableCollector push(Environment env) {
    return new SubVariableCollector(typeSystem, env, fromStack, this);
  }

  @Override protected void visit(Core.Id id) {
    visitX(id);
  }

  abstract void visitX(Core.Id id);

  /** Variable collector at the initial level. */
  private static class RootVariableCollector extends VariableCollector {
    final Consumer<Core.Id> unboundConsumer;

    RootVariableCollector(TypeSystem typeSystem, Environment env,
        Consumer<Core.Id> unboundConsumer) {
      super(typeSystem, env, new ArrayDeque<>());
      this.unboundConsumer = unboundConsumer;
    }

    @Override void visitX(Core.Id id) {
      final @Nullable Pair<Binding, Environment> binding =
          env.getOpt2(id.idPat);
      if (binding != null) {
        unboundConsumer.accept(id);
      }
    }
  }

  /** Variable collector at a sub-level. */
  private static class SubVariableCollector extends VariableCollector {
    final VariableCollector parent;

    SubVariableCollector(TypeSystem typeSystem, Environment env,
        Deque<FromContext> fromStack, VariableCollector parent) {
      super(typeSystem, env, fromStack);
      this.parent = parent;
    }

    @Override void visitX(Core.Id id) {
      parent.visitX(id);
    }
  }
}
