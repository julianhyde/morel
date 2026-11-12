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
import com.google.common.collect.ImmutableSet;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Function;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Exps;
import net.hydromatic.morel.ast.JoinType;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.ast.Shuttle;
import net.hydromatic.morel.ast.Visitor;
import net.hydromatic.morel.type.RecordLikeType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import org.jspecify.annotations.Nullable;

/**
 * Applies {@link RelRule}s to the relational trees in an expression, and holds
 * the standard rules.
 *
 * <p>The driver is deterministic. At a tree's root it first applies the
 * whole-tree rules, in the order given, until none fires. Then it rewrites the
 * tree bottom-up: a node's inputs first, and the trees nested in its
 * expressions, then the node, where it tries the node rules in the order given
 * and applies the first that fires. A firing may put new nodes under the
 * replacement, so the replacement is rewritten in turn, and the rules are tried
 * again at it until none fires. The order of the rules and of the walk being
 * fixed, the result depends on the tree, the rules and the context -- the
 * environment, and whether the elements are read -- and nothing else.
 *
 * <p>Every firing is checked: the replacement has the type of the node it
 * replaces, always, and, with assertions on, is a well-formed tree by {@link
 * RelValidator}. Those are the two properties a rule must preserve and the
 * first place to look when one is wrong.
 */
public class RelRules {
  /** Firings beyond which a rule set is taken to be looping. */
  private static final int LIMIT = 10_000;

  /** Drops a {@code filter} whose condition is {@code true}. */
  public static final RelRule FILTER_TRUE =
      new RelRule() {
        @Override
        public String name() {
          return "FilterTrue";
        }

        @Override
        public Core.@Nullable Exp apply(RelRule.Context cx, Core.Rel rel) {
          if (rel instanceof Core.Filter
              && ((Core.Filter) rel).condition.isBoolLiteral(true)) {
            return ((Core.Filter) rel).input;
          }
          return null;
        }
      };

  /**
   * Merges a {@code filter} over a {@code filter} into one whose condition is
   * the conjunction of the two, the inner's first.
   *
   * <p>The resolver leaves the {@code where} steps as the user wrote them, so
   * that a plan reads as the query does; it is here that they become one
   * condition. A condition that reads the ordinal counts the elements the inner
   * filter let through, which the merged filter would not have, so such a
   * filter stays one of its own.
   */
  public static final RelRule FILTER_MERGE =
      new RelRule() {
        @Override
        public String name() {
          return "FilterMerge";
        }

        @Override
        public Core.@Nullable Exp apply(RelRule.Context cx, Core.Rel rel) {
          final TypeSystem typeSystem = cx.typeSystem();
          if (!(rel instanceof Core.Filter)) {
            return null;
          }
          final Core.Filter outer = (Core.Filter) rel;
          if (outer.ordinalId != null
              || !(outer.input instanceof Core.Filter)) {
            return null;
          }
          final Core.Filter inner = (Core.Filter) outer.input;
          final Core.Exp condition =
              core.substitute(
                  typeSystem, outer.condition, outer.id, core.id(inner.id));
          return core.filter(
              outer.pos(),
              inner.id,
              inner.ordinalId,
              inner.input,
              core.andAlso(typeSystem, inner.condition, condition));
        }
      };

  /**
   * Converts a conjunct {@code exists q} of a {@code filter} into a semi-join
   * of the filter's input with {@code q}, and {@code not (exists q)} into an
   * anti-join, when {@code q} reads the row only in equalities: {@code from c
   * in customers where (exists s in sales where s.cust = c.id)} becomes a
   * semi-join of {@code customers} with {@code sales} on {@code s.cust = c.id},
   * which evaluates {@code sales} once rather than once per customer, and can
   * be a hash join.
   *
   * <p>The equalities may be in any filter of {@code q} that is under only
   * filters and inner joins; each comes out of its filter and into the join's
   * condition, re-expressed over {@code q}'s element. If {@code q} reads the
   * row anywhere else, the conjunct stays. The other conjuncts go into a filter
   * below the join, so that they can move into the joins below it; a filter
   * that reads its ordinal is not changed.
   */
  public static final RelRule FILTER_EXISTS_INTO_JOIN =
      new RelRule() {
        @Override
        public String name() {
          return "FilterExistsIntoJoin";
        }

        @Override
        public Core.@Nullable Exp apply(RelRule.Context cx, Core.Rel rel) {
          if (!(rel instanceof Core.Filter)) {
            return null;
          }
          final Core.Filter filter = (Core.Filter) rel;
          if (filter.ordinalId != null) {
            return null;
          }
          final TypeSystem typeSystem = cx.typeSystem();
          final List<Core.Exp> conjuncts = conjuncts(filter.condition);
          for (int i = 0; i < conjuncts.size(); i++) {
            final Core.Exp conjunct = conjuncts.get(i);
            // The names by which the query may read the row: the filter's id,
            // and the name the resolver gave it in a "let", if any.
            final Set<Core.NamedPat> rowNames = new HashSet<>();
            rowNames.add(filter.id);
            Core.Exp e = conjunct;
            if (e instanceof Core.Let
                && ((Core.Let) e).decl instanceof Core.NonRecValDecl) {
              final Core.NonRecValDecl decl =
                  (Core.NonRecValDecl) ((Core.Let) e).decl;
              if (decl.exp instanceof Core.Id
                  && ((Core.Id) decl.exp).idPat.equals(filter.id)) {
                rowNames.add(decl.pat);
                e = ((Core.Let) e).exp;
              }
            }
            boolean anti = false;
            if (e.isCallTo(BuiltIn.BOOL_NOT)) {
              anti = true;
              e = ((Core.Apply) e).arg;
            }
            if (e.isCallTo(BuiltIn.RELATIONAL_EMPTY)) {
              anti = !anti;
            } else if (!e.isCallTo(BuiltIn.RELATIONAL_NON_EMPTY)) {
              continue;
            }
            // Whether the query has a row does not depend on what its
            // elements are, or their order; so a projection or a sort on top
            // of it, such as the record of a query with several scans, can go.
            Core.Exp q = ((Core.Apply) e).arg;
            while ((q instanceof Core.Project || q instanceof Core.Sort)
                && ((Core.RowRel) q).ordinalId == null) {
              q = ((Core.RowRel) q).input;
            }
            final Decorrelated d =
                decorrelate(typeSystem, q, rowNames, ImmutableSet.of());
            if (d == null || d.conditions.isEmpty()) {
              continue;
            }
            final List<Core.Exp> others = new ArrayList<>(conjuncts);
            others.remove(i);
            final Core.Exp left =
                others.isEmpty()
                    ? filter.input
                    : core.filter(
                        filter.pos(),
                        filter.id,
                        null,
                        filter.input,
                        core.andAlso(typeSystem, others));
            final Core.IdPat leftId =
                core.idPat(
                    filter.id.type,
                    filter.id.name,
                    typeSystem.nameGenerator::inc);
            final Core.IdPat rightId =
                core.idPat(
                    d.rel.type.elementType(),
                    d.name,
                    typeSystem.nameGenerator::inc);
            final List<Core.Exp> conditions = new ArrayList<>();
            for (Function<Core.Exp, Core.Exp> condition : d.conditions) {
              Core.Exp c = condition.apply(core.id(rightId));
              for (Core.NamedPat rowName : rowNames) {
                c = core.substitute(typeSystem, c, rowName, core.id(leftId));
              }
              conditions.add(c);
            }
            return core.join(
                filter.pos(),
                typeSystem,
                anti ? JoinType.ANTI : JoinType.SEMI,
                leftId,
                rightId,
                null,
                left,
                d.rel,
                core.andAlso(typeSystem, conditions));
          }
          return null;
        }
      };

  /**
   * A query from which the equalities that read the outer row have been taken:
   * the query without them, and each equality as a function of an expression
   * for the query's element.
   */
  private static class Decorrelated {
    final Core.Exp rel;
    final List<Function<Core.Exp, Core.Exp>> conditions;
    /**
     * A name for the query's element, after the id of the node it came from.
     */
    final String name;

    Decorrelated(
        Core.Exp rel,
        List<Function<Core.Exp, Core.Exp>> conditions,
        String name) {
      this.rel = rel;
      this.conditions = conditions;
      this.name = name;
    }
  }

  /**
   * Takes from a query the equalities that read the outer row, under any of
   * {@code rowNames}; returns null if the query reads it in any other way.
   * {@code locals} are the patterns that the query binds above this node, which
   * the outer side of an equality must not read.
   */
  private static @Nullable Decorrelated decorrelate(
      TypeSystem typeSystem,
      Core.Exp node,
      Set<Core.NamedPat> rowNames,
      Set<Core.NamedPat> locals) {
    if (rowNames.stream().noneMatch(p -> Core.mentions(node, p))) {
      return new Decorrelated(node, ImmutableList.of(), name(node));
    }
    if (node instanceof Core.Filter) {
      final Core.Filter filter = (Core.Filter) node;
      if (filter.ordinalId != null) {
        return null;
      }
      final Set<Core.NamedPat> locals2 =
          ImmutableSet.<Core.NamedPat>builder()
              .addAll(locals)
              .add(filter.id)
              .build();
      final Decorrelated input =
          decorrelate(typeSystem, filter.input, rowNames, locals2);
      if (input == null) {
        return null;
      }
      final List<Function<Core.Exp, Core.Exp>> conditions =
          new ArrayList<>(input.conditions);
      final List<Core.Exp> remaining = new ArrayList<>();
      for (Core.Exp conjunct : core.decomposeAnd(filter.condition)) {
        if (rowNames.stream().noneMatch(p -> Core.mentions(conjunct, p))) {
          remaining.add(conjunct);
          continue;
        }
        if (!conjunct.isCallTo(BuiltIn.OP_EQ)
            || !isOuterKey(conjunct.arg(0), rowNames, locals2)
                && !isOuterKey(conjunct.arg(1), rowNames, locals2)) {
          return null;
        }
        // The equality, given an expression for the element; the filter's
        // element is its input's.
        conditions.add(
            element ->
                core.substitute(typeSystem, conjunct, filter.id, element));
      }
      final Core.Exp rel =
          remaining.isEmpty()
              ? input.rel
              : core.filter(
                  filter.pos(),
                  filter.id,
                  null,
                  input.rel,
                  core.andAlso(typeSystem, remaining));
      return new Decorrelated(rel, conditions, filter.id.name);
    }
    if (node instanceof Core.Join) {
      final Core.Join join = (Core.Join) node;
      if (join.joinType != JoinType.INNER
          || join.ordinalId != null
          || rowNames.stream()
              .anyMatch(p -> Core.mentions(join.condition, p))) {
        return null;
      }
      final Decorrelated left =
          decorrelate(typeSystem, join.left, rowNames, locals);
      final Decorrelated right =
          decorrelate(
              typeSystem,
              join.right,
              rowNames,
              ImmutableSet.<Core.NamedPat>builder()
                  .addAll(locals)
                  .add(join.leftId)
                  .build());
      if (left == null || right == null) {
        return null;
      }
      final Core.Join join2 =
          join.copy(
              typeSystem, join.joinType, left.rel, right.rel, join.condition);
      final int leftCount = core.componentCount(join2.left);
      final int rightCount = core.componentCount(join2.right);
      final List<Function<Core.Exp, Core.Exp>> conditions = new ArrayList<>();
      // An input's element, from the join's element: the input's components.
      left.conditions.forEach(
          c ->
              conditions.add(
                  element ->
                      c.apply(
                          slice(typeSystem, join2, element, 0, leftCount))));
      right.conditions.forEach(
          c ->
              conditions.add(
                  element ->
                      c.apply(
                          slice(
                              typeSystem,
                              join2,
                              element,
                              leftCount,
                              leftCount + rightCount))));
      return new Decorrelated(join2, conditions, left.name);
    }
    return null;
  }

  /**
   * Returns whether one side of an equality is a key over the outer row: it
   * reads the row, and none of the query's own patterns.
   */
  private static boolean isOuterKey(
      Core.Exp exp, Set<Core.NamedPat> rowNames, Set<Core.NamedPat> locals) {
    return rowNames.stream().anyMatch(p -> Core.mentions(exp, p))
        && locals.stream().noneMatch(p -> Core.mentions(exp, p));
  }

  /**
   * Returns the expression for the element of an input of a join, given an
   * expression for the join's element: its components {@code start} to {@code
   * end}, as a tuple if there are several.
   */
  private static Core.Exp slice(
      TypeSystem typeSystem,
      Core.Join join,
      Core.Exp element,
      int start,
      int end) {
    final List<Core.Exp> components =
        core.components(typeSystem, join, element).subList(start, end);
    return components.size() == 1
        ? components.get(0)
        : core.tuple(typeSystem, null, components);
  }

  /** Returns a name for the element of a query, after its id, if it has one. */
  private static String name(Core.Exp node) {
    if (node instanceof Core.RowRel) {
      return ((Core.RowRel) node).id.name;
    }
    if (node instanceof Core.Join) {
      return name(((Core.Join) node).left);
    }
    return "e";
  }

  /**
   * Moves the conjuncts of a {@code filter} over a {@code join} into the join,
   * as far down as each can go: into the left input, if it reads only the left
   * element; into the right input, if it reads only the right element and the
   * right input does not read the left element; into the join's condition, if
   * it reads both. (Issue #505.)
   *
   * <p>So {@code from x in xs, y in ys where x.b = y.c andalso y.d > 0}, whose
   * join is a cross product, becomes a join on {@code x.b = y.c} of {@code xs}
   * with {@code ys} filtered by {@code y.d > 0}, and the join can be a hash
   * join. The rule applies again to a filter it puts over a join, so a conjunct
   * reaches the lowest join that reads both its sides.
   *
   * <p>Over a {@code left join}, only a conjunct that reads only the left
   * element moves, into the left input; over a {@code right join}, only one
   * that reads only the right element, into the right input. Every element of
   * that input is in the output, so filtering it before the join removes the
   * same rows. A conjunct that reads the side padded with {@code NONE} stays,
   * and nothing moves over a {@code full join}.
   *
   * <p>Filtering an input of an inner join, or its pairs, before the join
   * rather than after gives the same rows in the same order. A conjunct moves
   * only if it can neither raise nor have an effect (see {@link Exps#isSafe}),
   * so a conjunct that stays is evaluated on no row that it was not evaluated
   * on before, though perhaps on fewer. Neither a filter nor a join that reads
   * its ordinal is changed, because moving a conjunct changes what is counted.
   */
  public static final RelRule FILTER_INTO_JOIN =
      new RelRule() {
        @Override
        public String name() {
          return "FilterIntoJoin";
        }

        @Override
        public Core.@Nullable Exp apply(RelRule.Context cx, Core.Rel rel) {
          if (!(rel instanceof Core.Filter)) {
            return null;
          }
          final Core.Filter filter = (Core.Filter) rel;
          if (filter.ordinalId != null
              || !(filter.input instanceof Core.Join)) {
            return null;
          }
          final Core.Join join = (Core.Join) filter.input;
          if (join.joinType == JoinType.FULL || join.ordinalId != null) {
            return null;
          }
          final TypeSystem typeSystem = cx.typeSystem();
          if (join.joinType.filtersLeft()) {
            // A semi- or anti-join's element is its left element, so a safe
            // conjunct can filter the left input instead.
            final List<Core.Exp> toLeft = new ArrayList<>();
            final List<Core.Exp> remaining = new ArrayList<>();
            for (Core.Exp conjunct : conjuncts(filter.condition)) {
              (conjunct.isSafe() ? toLeft : remaining).add(conjunct);
            }
            if (toLeft.isEmpty()) {
              return null;
            }
            final Core.Join join2 =
                join.copy(
                    typeSystem,
                    join.joinType,
                    filterOver(typeSystem, join.left, filter.id, toLeft),
                    join.right,
                    join.condition);
            return remaining.isEmpty()
                ? join2
                : core.filter(
                    filter.pos(),
                    filter.id,
                    null,
                    join2,
                    core.andAlso(typeSystem, remaining));
          }
          // The element, over the join's own ids. The components of a side
          // that an outer join pads with NONE are options, which the ids are
          // not, so they are placeholders; a conjunct that reads one stays.
          final List<Type> types =
              ((RecordLikeType) join.type.elementType()).argTypes();
          final List<Core.Exp> components = new ArrayList<>();
          final Set<Core.IdPat> padded = new HashSet<>();
          final List<Core.Exp> leftComponents =
              core.components(typeSystem, join.left, core.id(join.leftId));
          final List<Core.Exp> rightComponents =
              core.components(typeSystem, join.right, core.id(join.rightId));
          for (int i = 0; i < types.size(); i++) {
            final boolean isLeft = i < leftComponents.size();
            if (isLeft
                ? join.joinType.leftIsOption()
                : join.joinType.rightIsOption()) {
              final Core.IdPat pad =
                  core.idPat(
                      types.get(i), "$pad", typeSystem.nameGenerator::inc);
              padded.add(pad);
              components.add(core.id(pad));
            } else {
              components.add(
                  isLeft
                      ? leftComponents.get(i)
                      : rightComponents.get(i - leftComponents.size()));
            }
          }
          final Core.Exp element = core.tuple(typeSystem, null, components);
          final List<Core.Exp> toLeft = new ArrayList<>();
          final List<Core.Exp> toRight = new ArrayList<>();
          final List<Core.Exp> toCondition = new ArrayList<>();
          final List<Core.Exp> remaining = new ArrayList<>();
          for (Core.Exp conjunct : conjuncts(filter.condition)) {
            if (!conjunct.isSafe()) {
              remaining.add(conjunct);
              continue;
            }
            final Core.Exp e =
                readFields(
                    typeSystem,
                    core.substitute(typeSystem, conjunct, filter.id, element));
            final boolean readsLeft = Core.mentions(e, join.leftId);
            final boolean readsRight = Core.mentions(e, join.rightId);
            if (padded.stream().anyMatch(pad -> Core.mentions(e, pad))) {
              // It reads a side that is padded with NONE.
              remaining.add(conjunct);
            } else if (join.joinType != JoinType.INNER) {
              // Over an outer join, a conjunct may filter the side whose every
              // element is in the output, but may not join the condition: a
              // pair that fails it would be padded with NONE, not removed.
              if (readsLeft && !readsRight) {
                toLeft.add(e);
              } else if (readsRight && !readsLeft) {
                toRight.add(e);
              } else {
                remaining.add(conjunct);
              }
            } else if (readsLeft && readsRight) {
              toCondition.add(e);
            } else if (readsLeft) {
              toLeft.add(e);
            } else if (readsRight && !join.isDependent()) {
              toRight.add(e);
            } else if (readsRight) {
              toCondition.add(e);
            } else {
              remaining.add(conjunct);
            }
          }
          if (toLeft.isEmpty() && toRight.isEmpty() && toCondition.isEmpty()) {
            return null;
          }
          final Core.Exp left =
              toLeft.isEmpty()
                  ? join.left
                  : filterOver(typeSystem, join.left, join.leftId, toLeft);
          final Core.Exp right =
              toRight.isEmpty()
                  ? join.right
                  : filterOver(typeSystem, join.right, join.rightId, toRight);
          final List<Core.Exp> conditions = new ArrayList<>();
          if (!join.condition.isBoolLiteral(true)) {
            conditions.add(join.condition);
          }
          conditions.addAll(toCondition);
          final Core.Join join2 =
              core.join(
                  join.pos(),
                  typeSystem,
                  join.joinType,
                  join.leftId,
                  join.rightId,
                  null,
                  left,
                  right,
                  core.andAlso(typeSystem, conditions));
          if (remaining.isEmpty()) {
            return join2;
          }
          return core.filter(
              filter.pos(),
              filter.id,
              null,
              join2,
              core.andAlso(typeSystem, remaining));
        }
      };

  /**
   * Drops a {@code project} whose expression is its input's element and that
   * binds no ordinal.
   */
  public static final RelRule PROJECT_IDENTITY =
      new RelRule() {
        @Override
        public String name() {
          return "ProjectIdentity";
        }

        @Override
        public Core.@Nullable Exp apply(RelRule.Context cx, Core.Rel rel) {
          if (rel instanceof Core.Project) {
            final Core.Project project = (Core.Project) rel;
            if (project.ordinalId == null && isId(project.exp, project.id)) {
              return project.input;
            }
          }
          return null;
        }
      };

  /**
   * Merges a {@code project} over a {@code project} into one, by substituting
   * the inner's expression into the outer's.
   *
   * <p>Substitution duplicates: {@code project [$0 + $0]} over {@code project
   * [f $0]} would call {@code f} twice per element where the two nodes called
   * it once. Where the outer reads its element more than once, the inner's
   * expression is bound by a {@code let} first, which keeps the one evaluation
   * the two nodes had. An expression that only reads -- a variable, a literal,
   * a field, a record of those -- costs nothing to duplicate, and is
   * substituted however often it is read, so that the fold below can reach it.
   *
   * <p>A projection keeps its input's positions, so an ordinal the outer reads
   * is the inner's input's, and the merged node binds it.
   *
   * <p>Where the inner builds a record and the outer reads a field of it, the
   * substitution leaves {@code #id {id = #id $0}}, and that is folded to {@code
   * #id $0}.
   */
  public static final RelRule PROJECT_MERGE =
      new RelRule() {
        @Override
        public String name() {
          return "ProjectMerge";
        }

        @Override
        public Core.@Nullable Exp apply(RelRule.Context cx, Core.Rel rel) {
          final TypeSystem typeSystem = cx.typeSystem();
          if (!(rel instanceof Core.Project)) {
            return null;
          }
          final Core.Project outer = (Core.Project) rel;
          if (!(outer.input instanceof Core.Project)) {
            return null;
          }
          final Core.Project inner = (Core.Project) outer.input;
          Core.@Nullable IdPat ordinal = inner.ordinalId;
          Core.Exp exp = outer.exp;
          if (outer.ordinalId != null) {
            if (ordinal == null) {
              ordinal = outer.ordinalId;
            } else {
              exp =
                  core.substitute(
                      typeSystem, exp, outer.ordinalId, core.id(ordinal));
            }
          }
          final Core.Exp exp2;
          if (count(exp, outer.id) <= 1 || inner.exp.isCheap()) {
            exp2 =
                foldSelectors(
                    typeSystem,
                    core.substitute(typeSystem, exp, outer.id, inner.exp));
          } else {
            final Core.IdPat pat =
                core.idPat(
                    inner.exp.type,
                    "v$" + typeSystem.nameGenerator.inc("v$"),
                    0);
            exp2 =
                core.let(
                    core.nonRecValDecl(inner.exp.pos(), pat, null, inner.exp),
                    core.substitute(typeSystem, exp, outer.id, core.id(pat)));
          }
          return core.project(
              outer.pos(), typeSystem, inner.id, ordinal, inner.input, exp2);
        }
      };

  /** Drops a {@code skip} whose count is {@code 0}. */
  public static final RelRule SKIP_ZERO =
      new RelRule() {
        @Override
        public String name() {
          return "SkipZero";
        }

        @Override
        public Core.@Nullable Exp apply(RelRule.Context cx, Core.Rel rel) {
          if (rel instanceof Core.Skip) {
            final Core.Skip skip = (Core.Skip) rel;
            if (skip.count.op == Op.INT_LITERAL
                && ((Core.Literal) skip.count).value.equals(BigDecimal.ZERO)) {
              return skip.input;
            }
          }
          return null;
        }
      };

  /**
   * Grounds a tree: replaces the extents among its leaves -- "every value of
   * the type" -- with generators derived from the tree's constraints.
   *
   * <p>A whole-tree rule: the constraints are wherever the query put them, and
   * it reads the tree as it was built. Under {@code exists} or {@code empty}
   * the elements are only counted, and the grounding may then simplify what an
   * element is. A tree in a recursive function's body is left alone: it is
   * expanded when the function is called, or recognized as a transitive
   * closure.
   *
   * <p>A generator's collection may be a query of its own over an extent that
   * its own conditions bound, so the leaves that are trees are grounded in turn
   * before the result is judged. A result with an unbounded leaf, or one that
   * reads a field its element no longer has, is not a grounding, and the rule
   * declines; the tree stays as it was, and the diagnostic is the caller's,
   * which asks for it after every rule has had its chance.
   */
  public static final RelRule GROUND =
      new RelRule() {
        @Override
        public String name() {
          return "Ground";
        }

        @Override
        public boolean wholeTree() {
          return true;
        }

        @Override
        public Core.@Nullable Exp apply(RelRule.Context cx, Core.Rel rel) {
          if (cx.inRecursiveFunction() || !RelExpander.containsUnbounded(rel)) {
            return null;
          }
          final Core.Exp expanded0 =
              RelExpander.expand(
                  cx.typeSystem(),
                  cx.env(),
                  rel,
                  cx.rowsUsed(),
                  RelExpander.leafPats(rel));
          final Core.Exp expanded = groundLeaves(cx, expanded0);
          if (RelExpander.containsUnbounded(expanded)
              || Expander.misaddressed(expanded)) {
            return null;
          }
          return expanded;
        }

        /** Grounds the leaves of a tree that are trees over extents. */
        private Core.Exp groundLeaves(RelRule.Context cx, Core.Exp exp) {
          if (!(exp instanceof Core.Rel)) {
            return exp;
          }
          return copy(
              cx.typeSystem(),
              (Core.Rel) exp,
              input -> {
                if (input instanceof Core.Rel
                    && RelExpander.containsUnbounded(input)) {
                  final Core.@Nullable Exp grounded =
                      apply(cx, (Core.Rel) input);
                  return grounded != null ? grounded : input;
                }
                return groundLeaves(cx, input);
              },
              Function.identity());
        }
      };

  /**
   * The rules that every query is rewritten with, in order.
   *
   * <p>Grounding comes first, and is the one whole-tree rule: it sees each tree
   * before the node rules reshape it.
   *
   * <p>The builder applies most of these as it builds -- what is cheaper not to
   * build than to build and remove -- and they are rules as well for the trees
   * that a pass leaves: a filter under a filter after inlining, say. The
   * resolver builds without the filter merge on purpose, so that the plan of a
   * query reads as the query does.
   */
  public static final ImmutableList<RelRule> STANDARD =
      ImmutableList.of(
          GROUND,
          FILTER_TRUE,
          FILTER_MERGE,
          FILTER_EXISTS_INTO_JOIN,
          FILTER_INTO_JOIN,
          PROJECT_IDENTITY,
          PROJECT_MERGE,
          SKIP_ZERO);

  private RelRules() {}

  /**
   * Returns the conjuncts of a condition.
   *
   * <p>When a conjunct refers to the row from inside a query, as in {@code
   * where x.a = y.b andalso (exists z in zs where z.c = x.a)}, the resolver
   * names the row, and the condition becomes {@code let val v = r in ... end}.
   * Such a condition is split too: each conjunct of its body that does not
   * mention {@code v} is a conjunct on its own, and each that does is wrapped
   * in the {@code let}.
   */
  private static List<Core.Exp> conjuncts(Core.Exp condition) {
    final List<Core.Exp> list = new ArrayList<>();
    for (Core.Exp e : core.decomposeAnd(condition)) {
      if (e instanceof Core.Let
          && ((Core.Let) e).decl instanceof Core.NonRecValDecl
          && ((Core.NonRecValDecl) ((Core.Let) e).decl).exp.isAtom()) {
        final Core.Let let = (Core.Let) e;
        final Core.NonRecValDecl decl = (Core.NonRecValDecl) let.decl;
        for (Core.Exp c : conjuncts(let.exp)) {
          list.add(Core.mentions(c, decl.pat) ? core.let(decl, c) : c);
        }
      } else {
        list.add(e);
      }
    }
    return list;
  }

  /**
   * Returns a filter over {@code input} whose condition is the conjunction of
   * {@code conditions}, which read the input's element as {@code id}.
   */
  private static Core.Exp filterOver(
      TypeSystem typeSystem,
      Core.Exp input,
      Core.IdPat id,
      List<Core.Exp> conditions) {
    // Named after the id it replaces, so that the plan reads as the query.
    final Core.IdPat id2 =
        core.idPat(
            input.type.elementType(), id.name, typeSystem.nameGenerator::inc);
    final Core.Exp condition =
        core.substitute(
            typeSystem, core.andAlso(typeSystem, conditions), id, core.id(id2));
    return core.filter(id2, null, input, condition);
  }

  /**
   * Reads each field of a tuple that is constructed in an expression off the
   * tuple: {@code #2 (x, y)} becomes {@code y}.
   */
  private static Core.Exp readFields(TypeSystem typeSystem, Core.Exp exp) {
    return exp.accept(
        new Shuttle(typeSystem) {
          @Override
          protected Core.Exp visit(Core.Apply apply) {
            return RelCompiler.readField(super.visit(apply));
          }
        });
  }

  private static boolean isId(Core.Exp exp, Core.IdPat id) {
    return exp instanceof Core.Id && ((Core.Id) exp).idPat.equals(id);
  }

  /** Returns how many times an expression reads a pattern. */
  private static int count(Core.Exp exp, Core.IdPat pat) {
    final int[] n = {0};
    exp.accept(
        new Visitor() {
          @Override
          protected void visit(Core.Id id) {
            if (id.idPat.equals(pat)) {
              ++n[0];
            }
          }
        });
    return n[0];
  }

  /**
   * Folds a field access applied to a record that is built right there.
   *
   * <p>Substitution produces {@code #y {x = a, y = b}}: a projection merged
   * into the one above it, or a join's yield put into a condition. The engine
   * looks for a reference to a variable, not for a record it could have taken
   * apart, so folding it to {@code b} is what lets the engine see the
   * constraint.
   */
  static Core.Exp foldSelectors(TypeSystem typeSystem, Core.Exp exp) {
    return exp.accept(
        new Shuttle(typeSystem) {
          @Override
          protected Core.Exp visit(Core.Apply apply) {
            final Core.Exp exp2 = super.visit(apply);
            if (exp2 instanceof Core.Apply) {
              final Core.Apply apply2 = (Core.Apply) exp2;
              if (apply2.fn.op == Op.RECORD_SELECTOR
                  && apply2.arg.op == Op.TUPLE
                  // The selector reads a slot of the record it was made for,
                  // and a substitution can put a tuple under a selector built
                  // for another, of a different arity, where the slot is not
                  // even in range. Fold only what is; folding is what lets the
                  // engine see a constraint, so decline no more than this.
                  && ((Core.RecordSelector) apply2.fn).slot
                      < ((Core.Tuple) apply2.arg).args.size()) {
                return ((Core.Tuple) apply2.arg)
                    .args.get(((Core.RecordSelector) apply2.fn).slot);
              }
            }
            return exp2;
          }
        });
  }

  /** Rewrites every tree in a declaration. */
  public static Core.Decl rewrite(
      TypeSystem typeSystem,
      Environment env,
      List<RelRule> rules,
      Core.Decl decl) {
    return decl.accept(new Driver(typeSystem, rules).walker(env));
  }

  /** Rewrites every tree in an expression. */
  public static Core.Exp rewrite(
      TypeSystem typeSystem,
      Environment env,
      List<RelRule> rules,
      Core.Exp exp) {
    return new Driver(typeSystem, rules).walker(env).rewrite(exp);
  }

  /** Rewrites every tree in an expression that has no free names. */
  public static Core.Exp rewrite(
      TypeSystem typeSystem, List<RelRule> rules, Core.Exp exp) {
    return rewrite(typeSystem, Environments.empty(), rules, exp);
  }

  /**
   * Copies a node with its inputs and its expressions passed through the given
   * functions; returns the node itself if nothing changed.
   */
  private static Core.Exp copy(
      TypeSystem typeSystem,
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

  /** One rewrite of one expression: the rules, and the count of firings. */
  private static class Driver {
    final TypeSystem typeSystem;
    final List<RelRule> wholeTreeRules;
    final List<RelRule> nodeRules;
    int firings;

    Driver(TypeSystem typeSystem, List<RelRule> rules) {
      this.typeSystem = typeSystem;
      final ImmutableList.Builder<RelRule> whole = ImmutableList.builder();
      final ImmutableList.Builder<RelRule> node = ImmutableList.builder();
      rules.forEach(rule -> (rule.wholeTree() ? whole : node).add(rule));
      this.wholeTreeRules = whole.build();
      this.nodeRules = node.build();
    }

    Walker walker(Environment env) {
      return new Walker(env, true, false, ImmutableSet.of());
    }

    /**
     * Applies rules at a node until none fires, and returns the result.
     *
     * <p>{@code below} is how a firing's replacement is completed: a node rule
     * may put new nodes under its replacement, which are rewritten in turn; a
     * whole-tree rule's replacement is rewritten below afterwards, as a whole.
     */
    private Core.Exp fire(
        List<RelRule> rules,
        RelRule.Context cx,
        Set<Core.IdPat> patterns,
        Core.Exp node,
        Function<Core.Exp, Core.Exp> below) {
      while (node instanceof Core.Rel) {
        final Core.Rel rel = (Core.Rel) node;
        Core.@Nullable Exp next = null;
        for (RelRule rule : rules) {
          final Core.@Nullable Exp result = rule.apply(cx, rel);
          if (result != null && result != rel) {
            check(rule, cx, rel, result, patterns);
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
        node = below.apply(next);
      }
      return node;
    }

    /** Checks the two properties every rule must preserve. */
    private void check(
        RelRule rule,
        RelRule.Context cx,
        Core.Rel before,
        Core.Exp after,
        Set<Core.IdPat> patterns) {
      final boolean ok =
          cx.rowsUsed()
              ? after.type.equals(before.type)
              // Nothing reads the elements, so the element may change; the kind
              // may not.
              : after.type.isCollection()
                  && after.type.getClass() == before.type.getClass();
      if (!ok) {
        throw new AssertionError(
            String.format(
                "rule %s changed the type of %s from %s to %s",
                rule.name(),
                before.op.lowerName,
                before.type.moniker(),
                after.type.moniker()));
      }
      assert valid(rule, after, patterns);
    }

    private boolean valid(
        RelRule rule, Core.Exp after, Set<Core.IdPat> patterns) {
      if (after instanceof Core.Rel) {
        final List<String> violations =
            RelValidator.violations(
                typeSystem, (Core.Rel) after, patterns::contains);
        if (!violations.isEmpty()) {
          throw new AssertionError(
              "rule "
                  + rule.name()
                  + " built an invalid tree: "
                  + violations
                  + "\n"
                  + after);
        }
      }
      return true;
    }

    /**
     * Walks an expression in its context, and rewrites each tree it meets; a
     * tree's own walk is {@link #rewrite}, so the shuttle does not descend into
     * one.
     *
     * <p>The context is the environment, whether the elements are read, and
     * whether the walk is in a recursive function's body, and each is what it
     * was where the tree stands. The walker also carries the patterns of the
     * enclosing nodes, which a tree nested in a node's expression may read;
     * they are for the validator, which would otherwise see such a read as
     * unbound.
     */
    private class Walker extends EnvShuttle implements RelRule.Context {
      private final boolean rowsUsed;
      private final boolean inRecursiveFunction;
      private final Set<Core.IdPat> patterns;

      Walker(
          Environment env,
          boolean rowsUsed,
          boolean inRecursiveFunction,
          Set<Core.IdPat> patterns) {
        super(Driver.this.typeSystem, env);
        this.rowsUsed = rowsUsed;
        this.inRecursiveFunction = inRecursiveFunction;
        this.patterns = patterns;
      }

      @Override
      protected EnvShuttle push(Environment env) {
        return new Walker(env, rowsUsed, inRecursiveFunction, patterns);
      }

      /** Returns a walker for what is under a node, which binds patterns. */
      private Walker under(Iterable<Core.IdPat> pats) {
        return new Walker(
            env,
            rowsUsed,
            inRecursiveFunction,
            ImmutableSet.<Core.IdPat>builder()
                .addAll(patterns)
                .addAll(pats)
                .build());
      }

      @Override
      public TypeSystem typeSystem() {
        return typeSystem;
      }

      @Override
      public Environment env() {
        return env;
      }

      @Override
      public boolean rowsUsed() {
        return rowsUsed;
      }

      @Override
      public boolean inRecursiveFunction() {
        return inRecursiveFunction;
      }

      @Override
      protected Core.Exp visit(Core.Apply apply) {
        if (rowsUsed
            && (apply.isCallTo(BuiltIn.RELATIONAL_NON_EMPTY)
                || apply.isCallTo(BuiltIn.RELATIONAL_EMPTY))) {
          // The argument's elements are counted, not read.
          final Walker inner =
              new Walker(env, false, inRecursiveFunction, patterns);
          return apply.copy(apply.fn.accept(inner), apply.arg.accept(inner));
        }
        return super.visit(apply);
      }

      @Override
      protected Core.RecValDecl visit(Core.RecValDecl recValDecl) {
        if (inRecursiveFunction) {
          return super.visit(recValDecl);
        }
        return new Walker(env, rowsUsed, true, patterns)
            .visitRecursive(recValDecl);
      }

      private Core.RecValDecl visitRecursive(Core.RecValDecl recValDecl) {
        return super.visit(recValDecl);
      }

      @Override
      protected Core.@Nullable Exp visitRel(Core.Rel rel) {
        return rewrite(rel);
      }

      /**
       * Rewrites an expression: a tree from its root, anything else by walk.
       */
      Core.Exp rewrite(Core.Exp exp) {
        if (!(exp instanceof Core.Rel)) {
          return exp.accept(this);
        }
        // The whole-tree rules first, at the root, on the tree as it was
        // built; then the tree below, and the node rules.
        final Core.Exp tree =
            fire(wholeTreeRules, this, patterns, exp, Function.identity());
        return tree instanceof Core.Rel
            ? rewriteNodes((Core.Rel) tree)
            : tree.accept(this);
      }

      /**
       * Rewrites a tree bottom-up: its inputs, and the trees nested in its
       * expressions, then the node rules at the node.
       */
      private Core.Exp rewriteNodes(Core.Rel rel) {
        // The node's expressions may read its patterns; a join's right input
        // may read its left id, and its ordinal.
        final Walker inner = under(rel.patterns());
        final Core.Exp node =
            copy(typeSystem, rel, inputFn(rel), inner::rewrite);
        // A firing may put new nodes below its replacement; rewrite those,
        // but not the expressions, which the walk has been through already.
        return fire(
            nodeRules,
            this,
            patterns,
            node,
            next ->
                next instanceof Core.Rel
                    ? copy(
                        typeSystem,
                        (Core.Rel) next,
                        inputFn((Core.Rel) next),
                        Function.identity())
                    : next);
      }

      /**
       * Returns the function that rewrites a node's inputs: a join's right
       * input under a walker that binds the join's left id, and its ordinal if
       * it has one, since the right input may read them; any other input under
       * this walker.
       */
      private Function<Core.Exp, Core.Exp> inputFn(Core.Rel rel) {
        final Walker right =
            rel instanceof Core.Join
                ? under(
                    ((Core.Join) rel).ordinalId == null
                        ? ImmutableList.of(((Core.Join) rel).leftId)
                        : ImmutableList.of(
                            ((Core.Join) rel).leftId,
                            ((Core.Join) rel).ordinalId))
                : this;
        return input ->
            (rel instanceof Core.Join && input == ((Core.Join) rel).right
                    ? right
                    : this)
                .rewriteInput(input);
      }

      /** Rewrites an input: a node by the node rules, a leaf by walk. */
      private Core.Exp rewriteInput(Core.Exp input) {
        return input instanceof Core.Rel
            ? rewriteNodes((Core.Rel) input)
            : input.accept(this);
      }
    }
  }
}

// End RelRules.java
