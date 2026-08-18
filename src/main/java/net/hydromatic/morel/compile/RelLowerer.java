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

import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.FromBuilder;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.ast.Shuttle;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.PairList;
import org.jspecify.annotations.Nullable;

/**
 * Lowers a relational tree ({@link Core.Rel}) into the environment-passing form
 * that executes: a {@link Core.From} whose steps carry bindings, which {@link
 * Compiler} turns into {@code RowSink} code.
 *
 * <p>This is the reverse of {@link RelTranslator}, and it is what step 2 of
 * {@code plan.md} means by "the step list survives as an unprinted lowering
 * artifact". Where the translation eliminates variables, the lowering
 * reintroduces them.
 *
 * <p>It linearizes. The tree is left-deep after translation, so one step list
 * carries the whole left spine rather than each node nesting a {@code from} of
 * its own. What makes that work is carrying a node's element as an
 * <em>expression</em> over the step list's bindings instead of materializing
 * it: a projection then changes the expression, not the steps, and {@code from
 * e in emps, d in depts where p} lowers back to the three steps it began as.
 *
 * <p>The element is materialized, by a {@code yield}, only where something
 * needs the row itself: before a set operator, before an outer join (which
 * wraps whole bindings in {@code option}), and at the end.
 */
public class RelLowerer {
  private final TypeSystem typeSystem;

  private RelLowerer(TypeSystem typeSystem) {
    this.typeSystem = typeSystem;
  }

  /** Lowers a tree into an executable expression. */
  public static Core.Exp lower(TypeSystem typeSystem, Core.Exp exp) {
    return new RelLowerer(typeSystem).lowerRel(exp);
  }

  private Core.Exp lowerRel(Core.Exp exp) {
    if (!(exp instanceof Core.Rel)) {
      // A leaf is already an expression.
      return exp;
    }
    final FromBuilder fromBuilder = core.fromBuilder(typeSystem);
    materialize(fromBuilder, lowerInto(fromBuilder, exp));
    return fromBuilder.build();
  }

  /**
   * Appends the steps for a node, and returns an expression, over the step
   * list's bindings, that denotes the node's element.
   */
  private Core.Exp lowerInto(FromBuilder fromBuilder, Core.Exp exp) {
    if (!(exp instanceof Core.Rel)) {
      return scan(fromBuilder, exp);
    }
    if (exp instanceof Core.Filter) {
      final Core.Filter filter = (Core.Filter) exp;
      final Core.Exp element = lowerInto(fromBuilder, filter.input);
      fromBuilder.where(subst(filter.condition, element, null));
      return element;
    }
    if (exp instanceof Core.Project) {
      // A projection changes the element, not the steps; nothing is emitted
      // unless a later step needs the row.
      final Core.Project project = (Core.Project) exp;
      final Core.Exp element = lowerInto(fromBuilder, project.input);
      return subst(project.exp, element, null);
    }
    if (exp instanceof Core.ProjectMany) {
      final Core.ProjectMany projectMany = (Core.ProjectMany) exp;
      final Core.Exp element = lowerInto(fromBuilder, projectMany.input);
      final Core.Exp body =
          rename(lowerRel(projectMany.body), projectMany.param, element);
      return scan(fromBuilder, body);
    }
    if (exp instanceof Core.IfEmpty) {
      // Needs the collection as a value, so it becomes an expression, which
      // is then scanned.
      return scan(fromBuilder, lowerIfEmpty((Core.IfEmpty) exp));
    }
    if (exp instanceof Core.Join) {
      return lowerJoin(fromBuilder, (Core.Join) exp);
    }
    if (exp instanceof Core.Group) {
      return lowerGroup(fromBuilder, (Core.Group) exp);
    }
    if (exp instanceof Core.Sort) {
      final Core.Sort sort = (Core.Sort) exp;
      final Core.Exp element = lowerInto(fromBuilder, sort.input);
      fromBuilder.order(subst(sort.exp, element, null));
      return element;
    }
    if (exp instanceof Core.Unorder) {
      final Core.Exp element =
          lowerInto(fromBuilder, ((Core.Unorder) exp).input);
      fromBuilder.unorder();
      return element;
    }
    if (exp instanceof Core.Skip) {
      final Core.Skip skip = (Core.Skip) exp;
      final Core.Exp element = lowerInto(fromBuilder, skip.input);
      fromBuilder.skip(lowerRel(skip.count));
      return element;
    }
    if (exp instanceof Core.Take) {
      final Core.Take take = (Core.Take) exp;
      final Core.Exp element = lowerInto(fromBuilder, take.input);
      fromBuilder.take(lowerRel(take.count));
      return element;
    }
    if (exp instanceof Core.SetRel) {
      return lowerSetRel(fromBuilder, (Core.SetRel) exp);
    }
    throw new AssertionError("cannot lower " + exp.op);
  }

  private Core.Exp lowerGroup(FromBuilder fromBuilder, Core.Group group) {
    final Core.Exp element = lowerInto(fromBuilder, group.input);
    final SortedMap<Core.IdPat, Core.Exp> groupExps =
        new TreeMap<>(Core.NamedPat.ORDERING);
    group.keys.forEach(
        (label, keyExp) -> {
          final Core.Exp e = subst(keyExp, element, null);
          groupExps.put(core.idPat(e.type, label, 0), e);
        });
    final SortedMap<Core.IdPat, Core.Aggregate> aggregates =
        new TreeMap<>(Core.NamedPat.ORDERING);
    group.aggregates.forEach(
        (label, aggregate) ->
            aggregates.put(
                core.idPat(aggregate.type, label, 0),
                aggregate.copy(
                    aggregate.type,
                    subst(aggregate.aggregate, element, null),
                    aggregate.argument == null
                        ? null
                        : subst(aggregate.argument, element, null))));
    final boolean atom = groupExps.size() + aggregates.size() == 1;
    fromBuilder.group(atom, groupExps, aggregates);
    return naturalElement(fromBuilder);
  }

  private Core.Exp lowerSetRel(FromBuilder fromBuilder, Core.SetRel setRel) {
    // A set operator combines rows, so the element has to be the row.
    materialize(fromBuilder, lowerInto(fromBuilder, setRel.inputs.get(0)));
    final List<Core.Exp> args = new ArrayList<>();
    setRel
        .inputs
        .subList(1, setRel.inputs.size())
        .forEach(input -> args.add(lowerRel(input)));
    switch (setRel.op) {
      case UNION:
        fromBuilder.union(setRel.distinct, args);
        break;
      case INTERSECT:
        fromBuilder.intersect(setRel.distinct, args);
        break;
      default:
        fromBuilder.except(setRel.distinct, args);
        break;
    }
    return naturalElement(fromBuilder);
  }

  /**
   * Lowers a join. The condition sees both elements as they are; the yield sees
   * an option on a side that an outer join can leave absent, which is what the
   * bindings hold after the scan.
   */
  private Core.Exp lowerJoin(FromBuilder fromBuilder, Core.Join join) {
    Core.Exp left = lowerInto(fromBuilder, join.left);
    if (join.joinType != Core.Rel.JoinType.INNER) {
      // An outer join wraps whole bindings in 'option', so the left element
      // must be one binding before the join, not an expression over several.
      left = materialize(fromBuilder, left);
    }
    final Core.IdPat w = freshPat(join.right.type.elementType());
    final Core.Exp condition = subst(join.condition, left, core.id(w));
    fromBuilder.scan(op(join.joinType), w, lowerRel(join.right), condition);
    if (join.joinType == Core.Rel.JoinType.INNER) {
      return subst(join.yieldExp, left, core.id(w));
    }
    // The scan has re-typed the bindings that the join can leave absent; the
    // yield reads those, not the pattern variables.
    return subst(
        join.yieldExp,
        rebind(fromBuilder, left),
        rebind(fromBuilder, core.id(w)));
  }

  /**
   * Lowers an {@code ifEmpty} to a conditional expression; the step list has no
   * such step.
   */
  private Core.Exp lowerIfEmpty(Core.IfEmpty ifEmpty) {
    final Core.Exp input = lowerRel(ifEmpty.input);
    final Core.Exp nonEmpty =
        core.apply(
            Pos.ZERO,
            PrimitiveType.BOOL,
            core.functionLiteral(typeSystem, BuiltIn.RELATIONAL_NON_EMPTY),
            input);
    final Core.Exp singleton =
        core.list(typeSystem, ifEmpty.exp.type, ImmutableList.of(ifEmpty.exp));
    return core.ifThenElse(nonEmpty, input, singleton);
  }

  /**
   * Scans a collection, binding its element to a fresh variable, and returns
   * the expression that denotes the element.
   */
  private Core.Exp scan(FromBuilder fromBuilder, Core.Exp collection) {
    final Core.IdPat v = freshPat(collection.type.elementType());
    fromBuilder.scan(v, collection);
    return rebind(fromBuilder, core.id(v));
  }

  /**
   * Materializes the element as the step list's row, if it is not that already,
   * and returns the expression that denotes it afterwards.
   */
  private Core.Exp materialize(FromBuilder fromBuilder, Core.Exp element) {
    if (isNatural(fromBuilder, element)) {
      return element;
    }
    fromBuilder.yield_(element);
    return naturalElement(fromBuilder);
  }

  /**
   * Returns the expression that the step list's own bindings denote: the one
   * binding's value, or a record of them.
   */
  private Core.Exp naturalElement(FromBuilder fromBuilder) {
    final Core.StepEnv env = fromBuilder.stepEnv();
    if (env.atom) {
      return core.id(env.bindings.get(0).id);
    }
    final PairList<String, Core.Exp> nameExps = PairList.of();
    env.bindings.forEach(b -> nameExps.add(b.id.name, core.id(b.id)));
    return core.record(typeSystem, nameExps);
  }

  /**
   * Returns whether an expression is already what the bindings denote, in which
   * case a {@code yield} of it would be an identity step.
   *
   * <p>Compares printed forms: both expressions are built the same way here, so
   * this is a structural comparison in practice.
   */
  private boolean isNatural(FromBuilder fromBuilder, Core.Exp element) {
    // With no bindings the natural element is unit, which an element
    // expression is only if the query says so -- 'group {}' followed by a
    // yield still needs the yield.
    return naturalElement(fromBuilder).toString().equals(element.toString());
  }

  /**
   * Re-reads a reference against the current bindings, in case a step has
   * re-typed them -- an outer join wraps a binding in {@code option} -- or the
   * builder inlined a scan under a different name.
   */
  private Core.Exp rebind(FromBuilder fromBuilder, Core.Exp exp) {
    if (exp.op != Op.ID) {
      return exp;
    }
    final String name = ((Core.Id) exp).idPat.name;
    for (Binding binding : fromBuilder.stepEnv().bindings) {
      if (binding.id.name.equals(name)) {
        return core.id(binding.id);
      }
    }
    // The builder inlined the scan and the name is gone, so the row is the
    // element.
    return naturalElement(fromBuilder);
  }

  private static Op op(Core.Rel.JoinType joinType) {
    switch (joinType) {
      case LEFT:
        return Op.LEFT_JOIN;
      case RIGHT:
        return Op.RIGHT_JOIN;
      case FULL:
        return Op.FULL_JOIN;
      default:
        return Op.SCAN;
    }
  }

  private Core.IdPat freshPat(Type type) {
    return core.idPat(type, typeSystem.nameGenerator.get(), 0);
  }

  /**
   * Replaces {@code $0} and {@code $1} with expressions.
   *
   * <p>A nested node is lowered rather than descended into: its own {@code $0}
   * is its own input's element, and the spec forbids it from reading this
   * node's.
   */
  private Core.Exp subst(
      Core.Exp exp, Core.@Nullable Exp e0, Core.@Nullable Exp e1) {
    return exp.accept(
        new Shuttle(typeSystem) {
          @Override
          protected Core.Exp visit(Core.Id id) {
            if (e0 != null && id.idPat.name.equals("$0")) {
              return e0;
            }
            if (e1 != null && id.idPat.name.equals("$1")) {
              return e1;
            }
            return id;
          }
        });
  }

  /**
   * Replaces a {@code projectMany} lambda's parameter with the expression that
   * denotes the input element.
   */
  private Core.Exp rename(Core.Exp exp, Core.IdPat param, Core.Exp element) {
    return exp.accept(
        new Shuttle(typeSystem) {
          @Override
          protected Core.Exp visit(Core.Id id) {
            return id.idPat.equals(param) ? element : id;
          }
        });
  }
}

// End RelLowerer.java
