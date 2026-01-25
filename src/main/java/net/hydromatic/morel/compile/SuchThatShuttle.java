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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Visitor;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.TypeSystem;
import org.apache.calcite.util.Holder;

/**
 * Converts unbounded variables to bounded variables.
 *
 * <p>For example, converts
 *
 * <pre>{@code
 * from e
 *   where e elem #emps scott
 * }</pre>
 *
 * <p>to
 *
 * <pre>{@code
 * from e in #emps scott
 * }</pre>
 */
class SuchThatShuttle extends EnvShuttle {
  /** True if we're inside a recursive function definition. */
  private final boolean inRecursiveFunction;

  SuchThatShuttle(TypeSystem typeSystem, Environment env) {
    this(typeSystem, env, false);
  }

  private SuchThatShuttle(
      TypeSystem typeSystem, Environment env, boolean inRecursiveFunction) {
    super(typeSystem, env);
    this.inRecursiveFunction = inRecursiveFunction;
  }

  @Override
  protected EnvShuttle push(Environment env) {
    return new SuchThatShuttle(typeSystem, env, inRecursiveFunction);
  }

  @Override
  protected Core.RecValDecl visit(Core.RecValDecl recValDecl) {
    // When visiting recursive function definitions, mark that we're inside
    // so that From expressions won't be expanded (they're part of the
    // function definition, not queries to execute).
    final java.util.List<Binding> bindings = new java.util.ArrayList<>();
    Compiles.bindPattern(typeSystem, bindings, recValDecl);
    final SuchThatShuttle inner =
        new SuchThatShuttle(typeSystem, env.bindAll(bindings), true);
    return recValDecl.copy(inner.visitList(recValDecl.list));
  }

  static boolean containsUnbounded(Core.Decl decl) {
    final Holder<Boolean> found = Holder.of(false);
    decl.accept(
        new Visitor() {
          @Override
          protected void visit(Core.Scan scan) {
            super.visit(scan);
            if (Extents.isInfinite(scan.exp)) {
              found.set(true);
            }
          }
        });
    return found.get();
  }

  @Override
  protected Core.Exp visit(Core.From from) {
    System.err.println(
        "DEBUG SuchThatShuttle.visit(From): inRecursiveFunction="
            + inRecursiveFunction);

    // Skip expansion for From expressions inside recursive function
    // definitions.
    // These are part of the function's logic, not queries to execute.
    // The outer query will handle transitive closure detection.
    if (inRecursiveFunction) {
      System.err.println(
          "DEBUG SuchThatShuttle: skipping expansion inside recursive function");
      return super.visit(from);
    }

    final Core.From from2 = Expander.expandFrom(typeSystem, env, from);
    System.out.println(from2);

    // Expand subqueries.
    return super.visit(from2);
  }

  // TODO: Refactor: Move this method to a better place.
  static Set<Core.NamedPat> freePats(TypeSystem typeSystem, Core.Exp exp) {
    final Set<Core.NamedPat> set = new HashSet<>();
    exp.accept(
        new FreeFinder(
            typeSystem, Environments.empty(), new ArrayDeque<>(), set::add));
    return set;
  }

  /** Finds free variables in an expression. */
  private static class FreeFinder extends EnvVisitor {
    final Consumer<Core.NamedPat> consumer;

    FreeFinder(
        TypeSystem typeSystem,
        Environment env,
        Deque<FromContext> fromStack,
        Consumer<Core.NamedPat> consumer) {
      super(typeSystem, env, fromStack);
      this.consumer = consumer;
    }

    @Override
    protected EnvVisitor push(Environment env) {
      return new FreeFinder(typeSystem, env, fromStack, consumer);
    }

    @Override
    protected void visit(Core.Id id) {
      if (env.getOpt(id.idPat) == null) {
        consumer.accept(id.idPat);
      }
    }
  }
}

// End SuchThatShuttle.java
