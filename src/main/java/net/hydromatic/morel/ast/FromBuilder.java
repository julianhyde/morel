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

import net.hydromatic.morel.compile.Compiles;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;

import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

import static net.hydromatic.morel.ast.CoreBuilder.core;

/** Builds a {@link Core.From}.
 *
 * <p>Simplifies:
 * <ul>
 *   <li>Converts "from v in list" to "list";
 *   <li>Removes "where true" steps;
 *   <li>Removes trivial yield, e.g. "from v in list where condition yield v"
 *   becomes "from v in list where condition"
 * </ul>
 */
public class FromBuilder {
  private final TypeSystem typeSystem;
  private final List<Core.FromStep> steps = new ArrayList<>();
  private final List<Binding> bindings = new ArrayList<>();

  /** Use
   * {@link net.hydromatic.morel.ast.CoreBuilder#fromBuilder(TypeSystem)}. */
  FromBuilder(TypeSystem typeSystem) {
    this.typeSystem = typeSystem;
  }

  private FromBuilder addStep(Core.FromStep step) {
    steps.add(step);
    if (!bindings.equals(step.bindings)) {
      bindings.clear();
      bindings.addAll(step.bindings);
    }
    return this;
  }

  public FromBuilder scan(Core.Pat pat, Core.Exp exp, Core.Exp condition) {
    Compiles.acceptBinding(typeSystem, pat, bindings);
    return addStep(core.scan(Op.INNER_JOIN, bindings, pat, exp, condition));
  }

  public FromBuilder scan(Core.Pat pat, Core.Exp exp) {
    return scan(pat, exp, core.boolLiteral(true));
  }

  public FromBuilder where(Core.Exp condition) {
    if (condition.op == Op.BOOL_LITERAL
        && (Boolean) ((Core.Literal) condition).value) {
      // skip "where true"
      return this;
    }
    return addStep(core.where(bindings, condition));
  }

  public FromBuilder group(SortedMap<Core.IdPat, Core.Exp> groupExps,
      SortedMap<Core.IdPat, Core.Aggregate> aggregates) {
    return addStep(core.group(groupExps, aggregates));
  }

  public FromBuilder order(Iterable<Core.OrderItem> orderItems) {
    return addStep(core.order(bindings, orderItems));
  }

  public FromBuilder yield(Core.Exp exp) {
    if (exp.op == (bindings.size() == 1 ? Op.ID : Op.TUPLE)
        && exp.equals(defaultYield())) {
      return this;
    }
    return addStep(core.yield_(typeSystem, exp));
  }

  private Core.Exp defaultYield() {
    if (bindings.size() == 1) {
      return core.id(bindings.get(0).id);
    } else {
      final SortedSet<Core.Id> list = new TreeSet<>();
      final SortedMap<String, Type> argNameTypes =
          new TreeMap<>(RecordType.ORDERING);
      bindings.forEach(b -> list.add(core.id(b.id)));
      bindings.forEach(b -> argNameTypes.put(b.id.name, b.id.type));
      return core.tuple(typeSystem, typeSystem.recordType(argNameTypes), list);
    }
  }

  public Core.From build() {
    // TODO: remove trivial yield, e.g. 'from e in list yield e'
    return core.from(typeSystem, steps);
  }

  /** As {@link #build}, but also simplifies "from x in list" to "list". */
  public Core.Exp buildSimplify() {
    if (steps.size() == 1
        && steps.get(0).op == Op.INNER_JOIN) {
      final Core.Scan scan = (Core.Scan) steps.get(0);
      if (scan.pat.op == Op.ID_PAT) {
        return scan.exp;
      }
    }
    return core.from(typeSystem, steps);
  }
}

// End FromBuilder.java
