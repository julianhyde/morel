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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.FromBuilder;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.ast.Shuttle;
import net.hydromatic.morel.ast.Visitor;
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

  /**
   * Name for each leaf's binder, in the order the leaves are lowered; empty
   * once exhausted, and then a name is generated.
   *
   * <p>A tree has no names, so the lowering invents them. Where a caller knows
   * the name the user wrote, saying so keeps it in the plan, which is what the
   * reader of a plan wants to see.
   */
  private final Deque<String> scanNames;

  private RelLowerer(TypeSystem typeSystem, Iterable<String> scanNames) {
    this.typeSystem = typeSystem;
    this.scanNames = new ArrayDeque<>(ImmutableList.copyOf(scanNames));
  }

  /** Lowers a tree into an executable expression. */
  public static Core.Exp lower(TypeSystem typeSystem, Core.Exp exp) {
    return lower(typeSystem, exp, ImmutableList.of());
  }

  /**
   * Lowers a tree into an executable expression, naming the binder of each leaf
   * scan, in order, from {@code scanNames}.
   */
  public static Core.Exp lower(
      TypeSystem typeSystem, Core.Exp exp, Iterable<String> scanNames) {
    return new RelLowerer(typeSystem, scanNames).lowerRel(exp);
  }

  private Core.Exp lowerRel(Core.Exp exp) {
    if (isUnitCollection(exp)) {
      // A query that is nothing but the one unit row is a `from` with no
      // steps, which is what the step list means by it and what Calcite reads
      // as a values of one empty row.
      return core.fromBuilder(typeSystem).build();
    }
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
      final Core.Exp element2 = subst(project.exp, element, null);
      if (containsOrdinal(element2)) {
        // Except for an ordinal, which counts rows: only a step evaluates its
        // expression exactly once per row, so deferring one would change what
        // it counts.
        return materialize(fromBuilder, element2);
      }
      return element2;
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
      // A sort reads the element, so a projection above it must be paid for
      // here rather than deferred: deferring it would sort a wider row, and
      // evaluate the projection twice for every expression the sort key
      // shares with it.
      final Core.Sort sort = (Core.Sort) exp;
      final Core.Exp element =
          materialize(fromBuilder, lowerInto(fromBuilder, sort.input));
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
    // Never an atom: the tree's group builds a record whether it has one
    // label or many (discussion.md §14), so the step list must carry the same
    // record, or the lowered form has a different type from the tree.
    fromBuilder.group(false, groupExps, aggregates);
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
    // A leaf right input is scanned here rather than by `scan`, so this is
    // where its name is due; a right input that is a tree scans its own
    // leaves, and taking a name here would take the one they are owed.
    final Core.IdPat w =
        join.right instanceof Core.Rel
            ? freshPat(join.right.type.elementType())
            : scanPat(join.right.type.elementType());
    final Core.Exp condition = subst(join.condition, left, core.id(w));
    // A dependent join's right input reads the left element through the
    // binder. The step list has the left bindings in scope at the scan, so
    // the binder becomes the expression that denotes the left element -- the
    // step-list way of saying the same thing.
    Core.Exp right = lowerRel(join.right);
    if (join.binder != null) {
      right = rename(right, join.binder, left);
    }
    fromBuilder.scan(op(join.joinType), w, right, condition);
    // The element is the inputs' components in order (discussion.md §15). For
    // an outer join the scan has re-typed the bindings it can leave absent, so
    // the components are read off those, not the pattern variables.
    final boolean inner = join.joinType == Core.Rel.JoinType.INNER;
    final Core.Exp leftElement = inner ? left : rebind(fromBuilder, left);
    final Core.Exp rightElement =
        inner ? core.id(w) : rebind(fromBuilder, core.id(w));
    final List<Core.Exp> exps =
        new ArrayList<>(core.components(typeSystem, join.left, leftElement));
    exps.addAll(core.components(typeSystem, join.right, rightElement));
    return core.tuple(typeSystem, null, exps);
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
    if (isUnitCollection(collection)) {
      // The inverse of the translator's `unitCollection`: a query with no scan
      // iterates over one row, which is unit, and the tree says so with a leaf
      // holding that one row. A step list says it by having no scan, and
      // downstream reads that -- Calcite turns an empty `from` into a values
      // of one empty row, and a scan of `[()]` into a project over one.
      return core.unitLiteral();
    }
    final Core.IdPat v = scanPat(collection.type.elementType());
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
   * <p>With no bindings the natural element is unit, which an element
   * expression is only if the query says so: {@code group {}} followed by a
   * yield still needs the yield.
   */
  private boolean isNatural(FromBuilder fromBuilder, Core.Exp element) {
    return same(naturalElement(fromBuilder), element);
  }

  /**
   * Returns whether two expressions are the same.
   *
   * <p>Only for the shapes {@link #naturalElement} builds -- a reference, or a
   * record of references -- because {@link Core.Exp} has no structural equality
   * of its own.
   */
  private static boolean same(Core.Exp e0, Core.Exp e1) {
    if (e0 instanceof Core.Id) {
      return e1 instanceof Core.Id
          && ((Core.Id) e0).idPat.equals(((Core.Id) e1).idPat);
    }
    if (e0 instanceof Core.Tuple && e1 instanceof Core.Tuple) {
      final Core.Tuple t0 = (Core.Tuple) e0;
      final Core.Tuple t1 = (Core.Tuple) e1;
      if (!t0.type.equals(t1.type) || t0.args.size() != t1.args.size()) {
        return false;
      }
      for (int i = 0; i < t0.args.size(); i++) {
        if (!same(t0.args.get(i), t1.args.get(i))) {
          return false;
        }
      }
      return true;
    }
    return false;
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
    // The builder inlined the scan and the name is gone. Where it left one
    // binding, that binding is the row: the trailing `yield e` it skipped is
    // exactly what made the subquery's rows scalar, and rebuilding a record
    // of the binding would put back what the yield took away.
    final List<Binding> bindings = fromBuilder.stepEnv().bindings;
    if (bindings.size() == 1) {
      return core.id(bindings.get(0).id);
    }
    return naturalElement(fromBuilder);
  }

  /** Returns whether an expression reads the ordinal of the current row. */
  private static boolean containsOrdinal(Core.Exp exp) {
    final boolean[] found = {false};
    exp.accept(
        new Visitor() {
          @Override
          protected void visit(Core.Apply apply) {
            super.visit(apply);
            if (apply.isCallTo(BuiltIn.Z_ORDINAL)) {
              found[0] = true;
            }
          }
        });
    return found[0];
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

  /**
   * Returns whether an expression is the one-row collection of {@code unit}.
   */
  private static boolean isUnitCollection(Core.Exp exp) {
    if (!(exp instanceof Core.Apply)) {
      return false;
    }
    final Core.Apply apply = (Core.Apply) exp;
    return apply.isCallTo(BuiltIn.Z_LIST)
        && exp.type.elementType() == PrimitiveType.UNIT
        && ((Core.Tuple) apply.arg).args.size() == 1;
  }

  /**
   * Returns a binder for a scan: the name its caller asked for, if one is still
   * owed, and otherwise a generated one.
   */
  private Core.IdPat scanPat(Type type) {
    if (scanNames.isEmpty()) {
      return freshPat(type);
    }
    final String name = scanNames.remove();
    // Empty where the caller had a pattern rather than a name: a pattern
    // names no one thing, and the tree keeps paths instead.
    return name.isEmpty()
        ? freshPat(type)
        : core.idPat(type, name, typeSystem.nameGenerator::inc);
  }

  /**
   * Creates a binder for the step list, numbered from the counter the caller
   * supplied and prefixed {@code w$} to keep it clear of the tree's {@code v$}.
   *
   * <p>Two counters that both start at zero and both say {@code v$} collide the
   * moment their outputs meet in one expression, and these binders sit in a
   * step list whose expressions are the tree's.
   */
  private Core.IdPat freshPat(Type type) {
    return core.idPat(type, typeSystem.nameGenerator.getPrefixed("w"), 0);
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
              return core.at(e0, id.pos);
            }
            if (e1 != null && id.idPat.name.equals("$1")) {
              return core.at(e1, id.pos);
            }
            return id;
          }

          @Override
          protected Core.Exp visit(Core.Apply apply) {
            return readField(super.visit(apply));
          }
        });
  }

  /**
   * Reads a field out of a record that is being constructed here: {@code #b {a
   * = x, b = y}} becomes {@code y}.
   *
   * <p>The lowering carries a node's element as an expression rather than
   * materializing it, so a projection followed by anything that reads a field
   * -- a filter, a group key, a later projection -- meets a selector applied to
   * a record the projection built. The step list reads the field off the row
   * that its {@code yield} left behind; reading it off the expression that
   * describes the row is the same field, one step earlier.
   */
  private static Core.Exp readField(Core.Exp exp) {
    if (exp instanceof Core.Apply) {
      final Core.Apply apply = (Core.Apply) exp;
      if (apply.fn instanceof Core.RecordSelector
          && apply.arg instanceof Core.Tuple) {
        final int slot = ((Core.RecordSelector) apply.fn).slot;
        return ((Core.Tuple) apply.arg).args.get(slot);
      }
    }
    return exp;
  }

  /**
   * Replaces a dependent join's binder with the expression that denotes the
   * left element.
   */
  private Core.Exp rename(Core.Exp exp, Core.IdPat param, Core.Exp element) {
    return exp.accept(
        new Shuttle(typeSystem) {
          @Override
          protected Core.Exp visit(Core.Id id) {
            return id.idPat.equals(param) ? core.at(element, id.pos) : id;
          }

          @Override
          protected Core.Exp visit(Core.Apply apply) {
            return readField(super.visit(apply));
          }
        });
  }
}

// End RelLowerer.java
