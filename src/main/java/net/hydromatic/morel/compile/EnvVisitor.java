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

import java.util.ArrayList;
import java.util.List;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Visitor;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.TypeSystem;

/** Shuttle that keeps an environment of what variables are in scope. */
public abstract class EnvVisitor extends Visitor {
  protected final TypeSystem typeSystem;
  protected final Environment env;

  /** Creates an EnvVisitor. */
  protected EnvVisitor(TypeSystem typeSystem, Environment env) {
    this.typeSystem = typeSystem;
    this.env = env;
  }

  /** Creates a visitor the same as this but with a new environment. */
  protected abstract EnvVisitor push(Environment env);

  /** Creates a visitor the same as this but overriding a binding. */
  protected EnvVisitor bind(Binding binding) {
    return push(env.bind(binding));
  }

  /** Creates a visitor the same as this but with overriding bindings. */
  protected EnvVisitor bind(Iterable<Binding> bindingList) {
    // The "env2 == env" check is an optimization.
    // If you remove it, this method will have the same effect, just slower.
    final Environment env2 = env.bindAll(bindingList);
    if (env2 != env) {
      return push(env2);
    }
    return this;
  }

  @Override
  protected void visit(Core.Fn fn) {
    fn.idPat.accept(this);
    fn.exp.accept(bind(Binding.of(fn.idPat)));
  }

  @Override
  protected void visit(Core.Match match) {
    final List<Binding> bindings = new ArrayList<>();
    match.pat.accept(this);
    Compiles.acceptBinding(typeSystem, match.pat, bindings);
    match.exp.accept(bind(bindings));
  }

  @Override
  protected void visit(Core.Let let) {
    let.decl.accept(this);
    final List<Binding> bindings = new ArrayList<>();
    Compiles.bindPattern(typeSystem, bindings, let.decl);
    let.exp.accept(bind(bindings));
  }

  @Override
  protected void visit(Core.Local local) {
    final List<Binding> bindings = new ArrayList<>();
    Compiles.bindDataType(typeSystem, bindings, local.dataType);
    local.exp.accept(bind(bindings));
  }

  @Override
  protected void visit(Core.RecValDecl recValDecl) {
    final List<Binding> bindings = new ArrayList<>();
    recValDecl.list.forEach(decl -> Compiles.acceptBinding(decl.pat, bindings));
    final EnvVisitor v2 = bind(bindings);
    recValDecl.list.forEach(v2::accept);
  }

  /** Returns the bindings of a node's patterns. */
  private static List<Binding> patternBindings(Core.Rel rel) {
    final List<Binding> bindings = new ArrayList<>();
    rel.patterns().forEach(pat -> bindings.add(Binding.of(pat)));
    return bindings;
  }

  // A node binds its patterns for its expressions and not for its inputs.

  @Override
  protected void visit(Core.Filter filter) {
    filter.input.accept(this);
    filter.condition.accept(bind(patternBindings(filter)));
  }

  @Override
  protected void visit(Core.Project project) {
    project.input.accept(this);
    project.exp.accept(bind(patternBindings(project)));
  }

  @Override
  protected void visit(Core.Sort sort) {
    sort.input.accept(this);
    sort.exp.accept(bind(patternBindings(sort)));
  }

  @Override
  protected void visit(Core.Join join) {
    join.left.accept(this);
    // The right input is evaluated once per left element, and may read it.
    final List<Binding> rightBindings = new ArrayList<>();
    rightBindings.add(Binding.of(join.leftRow));
    if (join.ordinal != null) {
      rightBindings.add(Binding.of(join.ordinal));
    }
    join.right.accept(bind(rightBindings));
    join.condition.accept(bind(patternBindings(join)));
  }

  @Override
  protected void visit(Core.Group group) {
    group.input.accept(this);
    final EnvVisitor rowV = bind(patternBindings(group));
    group.keys.values().forEach(rowV::accept);
    // A tree's group is not a step, so there is no FromContext to build an
    // aggregate's environment from. The aggregate's argument reads the row;
    // the aggregate function may name a key -- `fn list => List.size list +
    // k` -- so bind the keys for it.
    final List<Binding> bindings = new ArrayList<>();
    group.keys.forEach(
        (name, key) -> bindings.add(Binding.of(core.idPat(key.type, name, 0))));
    final EnvVisitor v2 = bind(bindings);
    group
        .aggregates
        .values()
        .forEach(
            aggregate -> {
              aggregate.aggregate.accept(v2);
              if (aggregate.argument != null) {
                aggregate.argument.accept(rowV);
              }
            });
  }
}

// End EnvVisitor.java
