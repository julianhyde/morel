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
import static net.hydromatic.morel.util.Static.transformEager;

import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Function;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Shuttle;
import net.hydromatic.morel.type.ListType;
import net.hydromatic.morel.type.TypeSystem;
import org.jspecify.annotations.Nullable;

/**
 * Applies {@link RelRule}s to the relational trees in an expression, and holds
 * the standard rules.
 *
 * <p>The driver is deterministic. It rewrites a tree bottom-up: a node's inputs
 * first, and the trees nested in its expressions, then the node, where it tries
 * the rules in the order given and applies the first that fires. A firing may
 * put new nodes under the replacement, so the replacement is rewritten in turn,
 * and the rules are tried again at it until none fires. The order of the rules
 * and of the walk being fixed, the result depends on the tree and the rules and
 * nothing else.
 *
 * <p>With assertions on, every firing is checked: the replacement has the type
 * of the node it replaces, and is a well-formed tree by {@link RelValidator}.
 * Those are the two properties a rule must preserve and the first place to look
 * when one is wrong.
 */
public class RelRules {
  /** Firings beyond which a rule set is taken to be looping. */
  private static final int LIMIT = 10_000;

  /**
   * Pushes an {@code unorder} down through the nodes that do not depend on
   * their input's order, and discards it where it reaches a {@code sort}, whose
   * order it discards, or a bag, which has none.
   *
   * <p>A node that binds an ordinal depends on its input's order, and the rule
   * stops there, as it does at {@code skip} and {@code take}.
   */
  public static final RelRule UNORDER_PUSHDOWN =
      new RelRule() {
        @Override
        public String name() {
          return "UnorderPushdown";
        }

        @Override
        public Core.@Nullable Exp apply(TypeSystem typeSystem, Core.Rel rel) {
          if (!(rel instanceof Core.Unorder)) {
            return null;
          }
          final Core.Exp input = ((Core.Unorder) rel).input;
          if (!(input.type instanceof ListType)) {
            // Already a bag.
            return input;
          }
          if (input instanceof Core.Sort) {
            return unorder(typeSystem, rel, ((Core.Sort) input).input);
          }
          if (input instanceof Core.Filter) {
            final Core.Filter filter = (Core.Filter) input;
            if (filter.ordinal == null) {
              return core.filter(
                  filter.pos,
                  filter.row,
                  null,
                  unorder(typeSystem, rel, filter.input),
                  filter.condition);
            }
          }
          if (input instanceof Core.Project) {
            final Core.Project project = (Core.Project) input;
            if (project.ordinal == null) {
              return core.project(
                  project.pos,
                  typeSystem,
                  project.row,
                  null,
                  unorder(typeSystem, rel, project.input),
                  project.exp);
            }
          }
          if (input instanceof Core.Group) {
            final Core.Group group = (Core.Group) input;
            if (group.ordinal == null) {
              return core.group(
                  group.pos,
                  typeSystem,
                  group.row,
                  null,
                  unorder(typeSystem, rel, group.input),
                  group.keys,
                  group.aggregates);
            }
          }
          if (input instanceof Core.Join) {
            final Core.Join join = (Core.Join) input;
            if (join.ordinal == null) {
              return core.join(
                  join.pos,
                  typeSystem,
                  join.joinType,
                  join.leftRow,
                  join.rightRow,
                  null,
                  unorder(typeSystem, rel, join.left),
                  unorder(typeSystem, rel, join.right),
                  join.condition);
            }
          }
          if (input instanceof Core.IfEmpty) {
            final Core.IfEmpty ifEmpty = (Core.IfEmpty) input;
            return core.ifEmpty(
                ifEmpty.pos,
                unorder(typeSystem, rel, ifEmpty.input),
                ifEmpty.exp);
          }
          if (input instanceof Core.SetRel) {
            final Core.SetRel setRel = (Core.SetRel) input;
            return setRel.copy(
                typeSystem,
                setRel.distinct,
                transformEager(
                    setRel.inputs, e -> unorder(typeSystem, rel, e)));
          }
          return null;
        }

        /** Unorders an input, unless it is a bag already. */
        private Core.Exp unorder(
            TypeSystem typeSystem, Core.Rel unorder, Core.Exp input) {
          return input.type instanceof ListType
              ? core.unorder(unorder.pos, typeSystem, input)
              : input;
        }
      };

  /** Drops a {@code filter} whose condition is {@code true}. */
  public static final RelRule FILTER_TRUE =
      new RelRule() {
        @Override
        public String name() {
          return "FilterTrue";
        }

        @Override
        public Core.@Nullable Exp apply(TypeSystem typeSystem, Core.Rel rel) {
          if (rel instanceof Core.Filter
              && ((Core.Filter) rel).condition.isBoolLiteral(true)) {
            return ((Core.Filter) rel).input;
          }
          return null;
        }
      };

  /** The rules that every query is rewritten with, in order. */
  public static final ImmutableList<RelRule> STANDARD =
      ImmutableList.of(UNORDER_PUSHDOWN, FILTER_TRUE);

  private RelRules() {}

  /** Rewrites every tree in a declaration. */
  public static Core.Decl rewrite(
      TypeSystem typeSystem, List<RelRule> rules, Core.Decl decl) {
    return decl.accept(new Driver(typeSystem, rules).shuttle);
  }

  /** Rewrites every tree in an expression. */
  public static Core.Exp rewrite(
      TypeSystem typeSystem, List<RelRule> rules, Core.Exp exp) {
    return new Driver(typeSystem, rules).rewrite(exp);
  }

  /** One rewrite of one expression. */
  private static class Driver {
    final TypeSystem typeSystem;
    final List<RelRule> rules;
    int firings;

    /**
     * Walks an expression, and rewrites each tree it meets; a tree's own walk
     * is {@link #rewrite}, so the shuttle does not descend into one.
     */
    final Shuttle shuttle;

    Driver(TypeSystem typeSystem, List<RelRule> rules) {
      this.typeSystem = typeSystem;
      this.rules = ImmutableList.copyOf(rules);
      this.shuttle =
          new Shuttle(typeSystem) {
            @Override
            protected Core.@Nullable Exp visitRel(Core.Rel rel) {
              return rewrite(rel);
            }
          };
    }

    /** Rewrites an expression: a tree bottom-up, anything else by walk. */
    Core.Exp rewrite(Core.Exp exp) {
      if (!(exp instanceof Core.Rel)) {
        return exp.accept(shuttle);
      }
      return fire(copy((Core.Rel) exp, this::rewrite, e -> e.accept(shuttle)));
    }

    /**
     * Applies the rules at a node whose inputs are rewritten, until none fires,
     * and returns the result.
     */
    private Core.Exp fire(Core.Exp node) {
      while (node instanceof Core.Rel) {
        final Core.Rel rel = (Core.Rel) node;
        Core.@Nullable Exp next = null;
        for (RelRule rule : rules) {
          final Core.@Nullable Exp result = rule.apply(typeSystem, rel);
          if (result != null && result != rel) {
            check(rule, rel, result);
            next = result;
            break;
          }
        }
        if (next == null) {
          return node;
        }
        if (++firings > LIMIT) {
          throw new IllegalStateException(
              "rules did not converge after " + LIMIT + " firings");
        }
        // The firing may have put new nodes below; rewrite those, but not
        // the expressions, which the walk has been through already.
        node =
            next instanceof Core.Rel
                ? copy((Core.Rel) next, this::rewrite, Function.identity())
                : next;
      }
      return node;
    }

    /** Checks the two properties every rule must preserve. */
    private void check(RelRule rule, Core.Rel before, Core.Exp after) {
      if (!after.type.equals(before.type)) {
        throw new AssertionError(
            String.format(
                "rule %s changed the type of %s from %s to %s",
                rule.name(),
                before.opName(),
                before.type.moniker(),
                after.type.moniker()));
      }
      assert valid(rule, after);
    }

    private boolean valid(RelRule rule, Core.Exp after) {
      if (after instanceof Core.Rel) {
        final List<String> violations =
            RelValidator.violations(typeSystem, (Core.Rel) after);
        if (!violations.isEmpty()) {
          throw new AssertionError(
              "rule " + rule.name() + " built an invalid tree: " + violations);
        }
      }
      return true;
    }

    /**
     * Copies a node with its inputs and its expressions passed through the
     * given functions; returns the node itself if nothing changed.
     */
    private Core.Exp copy(
        Core.Rel rel,
        Function<Core.Exp, Core.Exp> inputFn,
        Function<Core.Exp, Core.Exp> expFn) {
      if (rel instanceof Core.Filter) {
        final Core.Filter filter = (Core.Filter) rel;
        return filter.copy(
            inputFn.apply(filter.input), expFn.apply(filter.condition));
      }
      if (rel instanceof Core.Project) {
        final Core.Project project = (Core.Project) rel;
        return project.copy(
            typeSystem, inputFn.apply(project.input), expFn.apply(project.exp));
      }
      if (rel instanceof Core.Join) {
        final Core.Join join = (Core.Join) rel;
        return join.copy(
            typeSystem,
            join.joinType,
            inputFn.apply(join.left),
            inputFn.apply(join.right),
            expFn.apply(join.condition));
      }
      if (rel instanceof Core.Group) {
        final Core.Group group = (Core.Group) rel;
        return group.copy(
            typeSystem,
            inputFn.apply(group.input),
            transformValues(group.keys, expFn),
            transformValues(
                group.aggregates,
                a ->
                    a.copy(
                        a.type,
                        expFn.apply(a.aggregate),
                        a.argument == null ? null : expFn.apply(a.argument))));
      }
      if (rel instanceof Core.Sort) {
        final Core.Sort sort = (Core.Sort) rel;
        return sort.copy(
            typeSystem, inputFn.apply(sort.input), expFn.apply(sort.exp));
      }
      if (rel instanceof Core.Unorder) {
        final Core.Unorder unorder = (Core.Unorder) rel;
        return unorder.copy(typeSystem, inputFn.apply(unorder.input));
      }
      if (rel instanceof Core.Skip) {
        final Core.Skip skip = (Core.Skip) rel;
        return skip.copy(inputFn.apply(skip.input), expFn.apply(skip.count));
      }
      if (rel instanceof Core.Take) {
        final Core.Take take = (Core.Take) rel;
        return take.copy(inputFn.apply(take.input), expFn.apply(take.count));
      }
      if (rel instanceof Core.IfEmpty) {
        final Core.IfEmpty ifEmpty = (Core.IfEmpty) rel;
        return ifEmpty.copy(
            inputFn.apply(ifEmpty.input), expFn.apply(ifEmpty.exp));
      }
      if (rel instanceof Core.SetRel) {
        final Core.SetRel setRel = (Core.SetRel) rel;
        return setRel.copy(
            typeSystem,
            setRel.distinct,
            transformEager(setRel.inputs, inputFn::apply));
      }
      throw new AssertionError("unknown node " + rel.op);
    }

    private static <V> SortedMap<String, V> transformValues(
        SortedMap<String, V> map, Function<V, V> fn) {
      final SortedMap<String, V> map2 = new TreeMap<>(map.comparator());
      map.forEach((k, v) -> map2.put(k, fn.apply(v)));
      return map2;
    }
  }
}

// End RelRules.java
