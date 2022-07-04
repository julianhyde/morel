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
package net.hydromatic.morel.ast;

import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.TypeSystem;

import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;

import static net.hydromatic.morel.ast.CoreBuilder.core;

/** Builds a {@link Core.From}. */
public class FromBuilder {
  private final TypeSystem typeSystem;
  private final List<Core.FromStep> steps = new ArrayList<>();
  private final List<Binding> bindings = new ArrayList<>();

  /** Use
   * {@link net.hydromatic.morel.ast.CoreBuilder#fromBuilder(TypeSystem)}. */
  FromBuilder(TypeSystem typeSystem) {
    this.typeSystem = typeSystem;
  }

  public FromBuilder scan(Core.Pat pat, Core.Exp exp, Core.Exp condition) {
    steps.add(core.scan(Op.INNER_JOIN, bindings, pat, exp, condition));
    return this;
  }

  public FromBuilder scan(Core.Pat pat, Core.Exp exp) {
    return scan(pat, exp, core.boolLiteral(true));
  }

  public FromBuilder where(Core.Exp condition) {
    steps.add(core.where(bindings, condition));
    return this;
  }

  public FromBuilder group(SortedMap<Core.IdPat, Core.Exp> groupExps,
      SortedMap<Core.IdPat, Core.Aggregate> aggregates) {
    steps.add(core.group(groupExps, aggregates));
    return this;
  }

  public FromBuilder order(Iterable<Core.OrderItem> orderItems) {
    steps.add(core.order(bindings, orderItems));
    return this;
  }

  public FromBuilder yield(Core.Exp exp) {
    steps.add(core.yield_(typeSystem, exp));
    return this;
  }

  public Core.From build() {
    // TODO: simplify 'from e in list' to 'e'
    // TODO: remove 'where true' steps
    // TODO: remove trivial yield, e.g. 'from e in list yield e'
    return core.from(typeSystem, steps);
  }
}

// End FromBuilder.java
