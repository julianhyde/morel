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
import org.jspecify.annotations.Nullable;

/**
 * Lowers a relational tree ({@link Core.Rel}) into the environment-passing form
 * that executes: a {@link Core.From} whose steps carry bindings, which {@link
 * Compiler} turns into {@code RowSink} code.
 *
 * <p>This is the reverse of {@link RelTranslator}, and it is what step 2 of
 * {@code plan.md} means by "the step list survives as an unprinted lowering
 * artifact". Where the translation eliminates variables, the lowering
 * reintroduces them: each node's input element gets a binder, and {@code $0}
 * and {@code $1} become references to it.
 *
 * <p>It is not an inverse in the structural sense, and does not try to be. The
 * check that matters is that a query lowered from its tree returns what it
 * returned before, which is what the script suite asserts on every run.
 */
public class RelLowerer {
  private final TypeSystem typeSystem;

  private RelLowerer(TypeSystem typeSystem) {
    this.typeSystem = typeSystem;
  }

  /** Lowers a tree into an executable expression. */
  public static Core.Exp lower(TypeSystem typeSystem, Core.Exp exp) {
    return new RelLowerer(typeSystem).lower(exp);
  }

  private Core.Exp lower(Core.Exp exp) {
    if (!(exp instanceof Core.Rel)) {
      // A leaf is already an expression.
      return exp;
    }
    if (exp instanceof Core.Filter) {
      final Core.Filter filter = (Core.Filter) exp;
      final FromBuilder fromBuilder = fromBuilder();
      final Core.IdPat v = scan(fromBuilder, filter.input);
      fromBuilder.where(subst(filter.condition, core.id(v), null));
      return fromBuilder.build();
    }
    if (exp instanceof Core.Project) {
      final Core.Project project = (Core.Project) exp;
      final FromBuilder fromBuilder = fromBuilder();
      final Core.IdPat v = scan(fromBuilder, project.input);
      fromBuilder.yield_(subst(project.exp, core.id(v), null));
      return fromBuilder.build();
    }
    if (exp instanceof Core.ProjectMany) {
      final Core.ProjectMany projectMany = (Core.ProjectMany) exp;
      final FromBuilder fromBuilder = fromBuilder();
      final Core.IdPat v = scan(fromBuilder, projectMany.input);
      // The lambda's parameter is the input element, which the scan has just
      // bound; the body becomes a dependent scan.
      final Core.Exp body =
          rename(lower(projectMany.body), projectMany.param, v);
      final Core.IdPat w = freshPat(body.type.elementType());
      fromBuilder.scan(w, body);
      fromBuilder.yield_(core.id(w));
      return fromBuilder.build();
    }
    if (exp instanceof Core.IfEmpty) {
      return lowerIfEmpty((Core.IfEmpty) exp);
    }
    if (exp instanceof Core.Join) {
      return lowerJoin((Core.Join) exp);
    }
    if (exp instanceof Core.Group) {
      final Core.Group group = (Core.Group) exp;
      final FromBuilder fromBuilder = fromBuilder();
      final Core.IdPat v = scan(fromBuilder, group.input);
      final SortedMap<Core.IdPat, Core.Exp> groupExps =
          new TreeMap<>(Core.NamedPat.ORDERING);
      group.keys.forEach(
          (label, keyExp) -> {
            final Core.Exp e = subst(keyExp, core.id(v), null);
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
                      subst(aggregate.aggregate, core.id(v), null),
                      aggregate.argument == null
                          ? null
                          : subst(aggregate.argument, core.id(v), null))));
      // Atom when a single key or aggregate: the same rule the tree derives.
      final boolean atom = groupExps.size() + aggregates.size() == 1;
      fromBuilder.group(atom, groupExps, aggregates);
      return fromBuilder.build();
    }
    if (exp instanceof Core.Sort) {
      final Core.Sort sort = (Core.Sort) exp;
      final FromBuilder fromBuilder = fromBuilder();
      final Core.IdPat v = scan(fromBuilder, sort.input);
      fromBuilder.order(subst(sort.exp, core.id(v), null));
      return fromBuilder.build();
    }
    if (exp instanceof Core.Unorder) {
      final Core.Unorder unorder = (Core.Unorder) exp;
      final FromBuilder fromBuilder = fromBuilder();
      scan(fromBuilder, unorder.input);
      fromBuilder.unorder();
      return fromBuilder.build();
    }
    if (exp instanceof Core.Skip) {
      final Core.Skip skip = (Core.Skip) exp;
      final FromBuilder fromBuilder = fromBuilder();
      scan(fromBuilder, skip.input);
      fromBuilder.skip(lower(skip.count));
      return fromBuilder.build();
    }
    if (exp instanceof Core.Take) {
      final Core.Take take = (Core.Take) exp;
      final FromBuilder fromBuilder = fromBuilder();
      scan(fromBuilder, take.input);
      fromBuilder.take(lower(take.count));
      return fromBuilder.build();
    }
    if (exp instanceof Core.SetRel) {
      final Core.SetRel setRel = (Core.SetRel) exp;
      final FromBuilder fromBuilder = fromBuilder();
      scan(fromBuilder, setRel.inputs.get(0));
      final List<Core.Exp> args = new ArrayList<>();
      setRel
          .inputs
          .subList(1, setRel.inputs.size())
          .forEach(input -> args.add(lower(input)));
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
      return fromBuilder.build();
    }
    throw new AssertionError("cannot lower " + exp.op);
  }

  /**
   * Lowers a join. The condition sees both elements as they are; the yield sees
   * an option on a side that an outer join can leave absent, which is what the
   * step's bindings hold after the scan.
   */
  private Core.Exp lowerJoin(Core.Join join) {
    final FromBuilder fromBuilder = fromBuilder();
    final Core.IdPat v0 = scan(fromBuilder, join.left);
    final Core.IdPat w = freshPat(join.right.type.elementType());
    final Core.Exp condition = subst(join.condition, core.id(v0), core.id(w));
    fromBuilder.scan(op(join.joinType), w, lower(join.right), condition);
    // After the scan the bindings may have been wrapped in 'option'; the yield
    // reads those, not the raw pattern variables.
    final @Nullable SidePair pair = outerBindings(fromBuilder, v0, w);
    if (pair == null) {
      throw new AssertionError("cannot lower join " + join.opName());
    }
    fromBuilder.yield_(subst(join.yieldExp, pair.left, pair.right));
    return fromBuilder.build();
  }

  /**
   * Lowers an {@code ifEmpty}: if the input has an element, it is the input,
   * otherwise a collection of the one expression.
   *
   * <p>The step list has no such step, so this becomes a conditional expression
   * rather than a step.
   */
  private Core.Exp lowerIfEmpty(Core.IfEmpty ifEmpty) {
    final Core.Exp input = lower(ifEmpty.input);
    final Core.Exp nonEmpty =
        core.apply(
            Pos.ZERO,
            PrimitiveType.BOOL,
            core.functionLiteral(typeSystem, BuiltIn.RELATIONAL_NON_EMPTY),
            input);
    final Core.Exp singleton =
        core.list(
            typeSystem, ifEmpty.exp.type, ImmutableList.of(lower(ifEmpty.exp)));
    return core.ifThenElse(nonEmpty, input, singleton);
  }

  /** Pair of expressions that a join's yield reads for its two sides. */
  private static class SidePair {
    final Core.Exp left;
    final Core.Exp right;

    SidePair(Core.Exp left, Core.Exp right) {
      this.left = left;
      this.right = right;
    }
  }

  /**
   * Returns what the yield of a join reads for each side: the binding of that
   * side after the scan, which an outer join has wrapped in {@code option}.
   */
  private @Nullable SidePair outerBindings(
      FromBuilder fromBuilder, Core.IdPat v0, Core.IdPat w) {
    Core.@Nullable Exp left = null;
    Core.@Nullable Exp right = null;
    for (Binding binding : fromBuilder.stepEnv().bindings) {
      if (binding.id.name.equals(v0.name)) {
        left = core.id(binding.id);
      } else if (binding.id.name.equals(w.name)) {
        right = core.id(binding.id);
      }
    }
    return left == null || right == null ? null : new SidePair(left, right);
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

  /** Scans a lowered input, binding its element to a fresh variable. */
  private Core.IdPat scan(FromBuilder fromBuilder, Core.Exp input) {
    final Core.Exp e = lower(input);
    final Core.IdPat v = freshPat(e.type.elementType());
    fromBuilder.scan(v, e);
    return v;
  }

  private FromBuilder fromBuilder() {
    return core.fromBuilder(typeSystem);
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
   * Renames a variable, for the body of a {@code projectMany} whose parameter
   * the lowering has rebound.
   */
  private Core.Exp rename(Core.Exp exp, Core.IdPat from, Core.IdPat to) {
    if (from.equals(to)) {
      return exp;
    }
    return exp.accept(
        new Shuttle(typeSystem) {
          @Override
          protected Core.Exp visit(Core.Id id) {
            return id.idPat.equals(from) ? core.id(to) : id;
          }
        });
  }
}

// End RelLowerer.java
