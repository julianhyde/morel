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
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Shuttle that builds a list of variables that are defined outside the
 * scope that created the shuttle.
 */
abstract class VariableCollector extends EnvVisitor {
  protected final Deque<Environment> lambdaEnvStack;

  private VariableCollector(TypeSystem typeSystem, Environment env,
      Deque<FromContext> fromStack, Deque<Environment> lambdaEnvStack) {
    super(typeSystem, env, fromStack);
    this.lambdaEnvStack = lambdaEnvStack;
  }

  /** Creates a variable collector. */
  public static VariableCollector create(TypeSystem typeSystem, Environment env,
      BiConsumer<Core.Id, Scope> consumer) {
    return new RootVariableCollector(typeSystem, env, consumer);
  }

  @Override protected SubVariableCollector push(Environment env) {
    return new SubVariableCollector(env, this);
  }

  /** {@inheritDoc}
   *
   * <p>For the purposes of finding free variables, it is important to
   * know when we pass through a lambda. Consider these pieces of code:
   *
   * <blockquote><pre>{@code
   * let
   *   val x = 1
   * in
   *   fn n => x + n
   * end;
   *
   * let
   *   val x = 1
   * in
   *   x + 2
   * end
   * }</pre>
   * </blockquote>
   *
   * <p>{@code x} is free inside both {@code fn n => x + n} and
   * {@code x + 2}, but for the second we do not need to create a closure,
   * because we have not crossed a {@code fn} boundary.
   */
  @Override protected void visit(Core.Fn fn) {
    fn.idPat.accept(this);
    try {
      lambdaEnvStack.push(env);
      fn.exp.accept(bind(Binding.of(fn.idPat)));
    } finally {
      lambdaEnvStack.pop();
    }
  }

  /**
   * {@inheritDoc}
   *
   * <p>Pushes onto the stack the special environment that contains the
   * still-being-resolved recursive variable.
   */
  @Override protected void visit(Core.RecValDecl recValDecl) {
    final List<Binding> bindings = new ArrayList<>();
    recValDecl.list.forEach(decl ->
        Compiles.bindPattern(typeSystem, bindings, decl.pat));
    final VariableCollector v2 = (VariableCollector) bindRec(bindings);
    try {
      lambdaEnvStack.push(v2.env);
      recValDecl.list.forEach(v2::accept);
    } finally {
      lambdaEnvStack.pop();
    }
  }

  @Override protected void visit(Core.Id id) {
    visitX(id);
  }

  abstract void visitX(Core.Id id);

  /** Variable collector at the initial level. */
  private static class RootVariableCollector extends VariableCollector {
    final BiConsumer<Core.Id, Scope> consumer;

    RootVariableCollector(TypeSystem typeSystem, Environment env,
        BiConsumer<Core.Id, Scope> consumer) {
      super(typeSystem, env, new ArrayDeque<>(), new ArrayDeque<>());
      this.consumer = consumer;
    }

    @Override void visitX(Core.Id id) {
      final @Nullable Pair<Binding, Environment> binding =
          env.getOpt2(id.idPat);
      if (binding != null
          && !lambdaEnvStack.isEmpty()) {
        Environment env = lambdaEnvStack.peek();
        if (env.isAncestorOf(binding.right)) {
          Scope scope = Scope.FREE;
          if (env == binding.right
              && env instanceof Environments.MapRecEnvironment) {
            scope = Scope.REC;
          }
          consumer.accept(id, scope);
        }
      }
    }
  }

  /** Variable collector at a sub-level. */
  private static class SubVariableCollector extends VariableCollector {
    final VariableCollector parent;

    SubVariableCollector(Environment env, VariableCollector parent) {
      super(parent.typeSystem, env, parent.fromStack, parent.lambdaEnvStack);
      this.parent = parent;
    }

    @Override void visitX(Core.Id id) {
      parent.visitX(id);
    }
  }

  /** Whether a variable is in scope. */
  enum Scope {
    /** Variable is free.
     * For example, in "fn x => x + y", "y" is free. */
    FREE,
    /** Variable is recursive.
     * For example, in "val rec f = fn i => case i of 0 => 0 | f (i - 1)",
     * "f" is recursive. */
    REC
  }
}
