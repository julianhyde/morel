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
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.Pair;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import org.apache.calcite.util.Util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;

import static net.hydromatic.morel.ast.CoreBuilder.core;

/** Builds a {@link Core.From}.
 *
 * <p>Simplifies the following patterns:
 * <ul>
 *   <li>Converts "from v in list" to "list"
 *   (only works in {@link #buildSimplify()}, not {@link #build()});
 *   <li>Removes "where true" steps;
 *   <li>Removes empty "order" steps;
 *   <li>Removes trivial {@code yield},
 *   e.g. "from v in list where condition yield v"
 *   becomes "from v in list where condition";
 *   <li>Inlines {@code from} expressions,
 *   e.g. "from v in (from w in list)"
 *   becomes "from w in list yield {v = w}".
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
    if (exp.op == Op.FROM
        && steps.isEmpty()
        && core.boolLiteral(true).equals(condition)
        && (pat instanceof Core.IdPat
            && !((Core.From) exp).steps.isEmpty()
            && Util.last(((Core.From) exp).steps).bindings.size() == 1
            || pat instanceof Core.RecordPat
                && ((Core.RecordPat) pat).args.stream()
                    .allMatch(a -> a instanceof Core.IdPat))) {
      final Core.From from = (Core.From) exp;
      addAll(from.steps);
      final Map<String, Core.Exp> nameExps = new LinkedHashMap<>();
      if (pat instanceof Core.RecordPat) {
        final Core.RecordPat recordPat = (Core.RecordPat) pat;
        Pair.forEach(recordPat.type().argNameTypes.keySet(), recordPat.args,
            (name, arg) -> nameExps.put(name, core.id((Core.IdPat) arg)));
      } else {
        final Core.IdPat idPat = (Core.IdPat) pat;
        final Core.FromStep fromStep = Util.last(((Core.From) exp).steps);
        final Binding binding = Iterables.getOnlyElement(fromStep.bindings);
        nameExps.put(idPat.name, core.id(binding.id));
      }
      return this.yield_(core.record(typeSystem, nameExps));
    }
    Compiles.acceptBinding(typeSystem, pat, bindings);
    return addStep(core.scan(Op.INNER_JOIN, bindings, pat, exp, condition));
  }

  public FromBuilder addAll(Iterable<? extends Core.FromStep> steps) {
    final StepHandler stepHandler = new StepHandler();
    steps.forEach(stepHandler::accept);
    return this;
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
    final List<Core.OrderItem> orderItemList = ImmutableList.copyOf(orderItems);
    if (orderItemList.isEmpty()) {
      // skip empty "order"
      return this;
    }
    return addStep(core.order(bindings, orderItems));
  }

  public FromBuilder yield_(Core.Exp exp) {
    if (exp.op == (bindings.size() == 1 ? Op.ID : Op.TUPLE)
        && exp.equals(defaultYield())) {
      return this;
    }
    if (exp.op == Op.TUPLE
        && isTrivial((Core.Tuple) exp)) {
      return this;
    }
    return addStep(core.yield_(typeSystem, exp));
  }

  /** Returns whether tuple is something like "{i = i, j = j}". */
  private boolean isTrivial(Core.Tuple tuple) {
    final ImmutableList<String> argNames =
        ImmutableList.copyOf(tuple.type().argNameTypes().keySet());
    for (int i = 0; i < tuple.args.size(); i++) {
      Core.Exp exp = tuple.args.get(i);
      if (exp.op != Op.ID
          || !((Core.Id) exp).idPat.name.equals(argNames.get(i))) {
        return false;
      }
    }
    return true;
  }

  private Core.Exp defaultYield() {
    if (bindings.size() == 1) {
      return core.id(bindings.get(0).id);
    } else {
      final Map<String, Core.Exp> argExps = new LinkedHashMap<>();
      bindings.forEach(b -> argExps.put(b.id.name, core.id(b.id)));
      return core.record(typeSystem, argExps);
    }
  }

  public Core.From build() {
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

  /** Calls the method to re-register a step. */
  private class StepHandler extends Visitor {
    @Override protected void visit(Core.Group group) {
      group(group.groupExps, group.aggregates);
    }

    @Override protected void visit(Core.Order order) {
      order(order.orderItems);
    }

    @Override protected void visit(Core.Scan scan) {
      scan(scan.pat, scan.exp, scan.condition);
    }

    @Override protected void visit(Core.Where where) {
      where(where.exp);
    }

    @Override protected void visit(Core.Yield yield) {
      yield_(yield.exp);
    }
  }
}

// End FromBuilder.java
