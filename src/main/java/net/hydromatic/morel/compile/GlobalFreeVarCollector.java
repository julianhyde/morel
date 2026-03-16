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
import java.util.function.Consumer;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.eval.Code;
import net.hydromatic.morel.eval.Unit;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.TypeSystem;

/**
 * Visitor that collects the names of free global variables in an expression.
 *
 * <p>A variable is a "free global" if it is bound in the enclosing environment
 * with an actual runtime value — not a {@link Code} placeholder or {@link
 * Unit#INSTANCE}. Such variables will be accessed at runtime via {@link
 * net.hydromatic.morel.eval.Codes#get(String)} unless marshalled onto the stack
 * in advance.
 *
 * <p>Function bodies ({@code fn} expressions) are not traversed, because they
 * are compiled with a fresh context that resets the global-slot map.
 *
 * <p>Usage:
 *
 * <blockquote>
 *
 * <pre>{@code
 * LinkedHashSet<String> globals = new LinkedHashSet<>();
 * GlobalFreeVarCollector.collect(typeSystem, env, exp, globals::add);
 * }</pre>
 *
 * </blockquote>
 */
class GlobalFreeVarCollector extends EnvVisitor {
  private final Consumer<String> consumer;

  private GlobalFreeVarCollector(
      TypeSystem typeSystem,
      Environment env,
      Deque<FromContext> fromStack,
      Consumer<String> consumer) {
    super(typeSystem, env, fromStack);
    this.consumer = consumer;
  }

  /**
   * Collects the names of free global variables in {@code exp} into {@code
   * consumer}.
   *
   * @param typeSystem Type system
   * @param env Environment in effect at the expression's scope
   * @param exp Expression to scan
   * @param consumer Called for each free global name found (may be called
   *     multiple times for the same name)
   */
  public static void collect(
      TypeSystem typeSystem,
      Environment env,
      Core.Exp exp,
      Consumer<String> consumer) {
    new GlobalFreeVarCollector(typeSystem, env, new ArrayDeque<>(), consumer)
        .accept(exp);
  }

  @Override
  protected EnvVisitor push(Environment env) {
    return new GlobalFreeVarCollector(typeSystem, env, fromStack, consumer);
  }

  @Override
  protected void visit(Core.Id id) {
    final Binding binding = env.getOpt(id.idPat);
    if (binding != null
        && !(binding.value instanceof Code)
        && binding.value != Unit.INSTANCE) {
      consumer.accept(id.idPat.name);
    }
  }

  @Override
  protected void visit(Core.Fn fn) {
    // Do not recurse into fn bodies: they use their own closed-over env.
  }
}

// End GlobalFreeVarCollector.java
