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

import static java.util.Objects.requireNonNull;
import static net.hydromatic.morel.ast.CoreBuilder.core;
import static net.hydromatic.morel.util.Static.last;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.FromBuilder;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.ast.RelBuilder;
import net.hydromatic.morel.ast.Shuttle;
import net.hydromatic.morel.ast.Visitor;
import net.hydromatic.morel.type.RecordLikeType;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.TupleType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.PairList;
import org.jspecify.annotations.Nullable;

/**
 * Finds a generator for each leaf of a relational tree that is an infinite
 * extent, so that an unbounded query can be executed.
 *
 * <p>The engine that inverts predicates is {@link Generators}, unchanged: it
 * keys on a variable, and on field accesses into that variable, which is
 * exactly the shape a tree gives it once the element of a leaf has a name. This
 * class is the front end that gives it one, alongside {@link Expander}, which
 * does the same for a step list. Both call {@link Expander#ground}.
 *
 * <p>Where a step list says "a scan of an infinite extent, and the {@code
 * where} steps that follow it", a tree says "a leaf that is an infinite extent,
 * and the filters above it". The filters' conditions are expressions over
 * {@code $0}, so naming the element and substituting that name for {@code $0}
 * turns them into the constraints the engine expects.
 */
public class RelExpander {
  private final TypeSystem typeSystem;
  private final Environment env;

  /**
   * Whether the rows of the query are used, rather than only counted or tested
   * for existence.
   *
   * <p>When they are not, a leaf that no constraint mentions can be dropped
   * rather than grounded -- which is the only thing to be done with {@code from
   * w : 'a join x : int where x = 3}, since {@code 'a} cannot be enumerated at
   * all. {@code Expander} does the same.
   */
  private final boolean rowsUsed;

  private int nextName;

  /**
   * Patterns to name the leaves with, in the order a walk reaches them, and how
   * many have been used.
   *
   * <p>A tree has erased the patterns its query was written with, so a leaf is
   * named `g$0` and the plan says `from g$0 in [1, 2, 4]` where the step list
   * says `from x in [1, 2, 4]`. The caller still has them -- it translated the
   * query -- and the translation keeps the scans in order, so handing them back
   * costs nothing and the plan reads as the user wrote it.
   */
  private List<Core.Pat> leafPats = ImmutableList.of();

  /**
   * The name of the leaf that each collection grounding built bounds.
   *
   * <p>By identity, because it is the collection that carries the name to the
   * lowering, not its position: grounding builds the leaves in the order they
   * were written but assembles them in the order it schedules them, so a list
   * taken positionally gives a scan the name of another scan's variable.
   */
  private final Map<Core.Exp, String> collectionNames = new IdentityHashMap<>();

  private int nextLeafPat;

  /**
   * Whether to name a leaf whose element is a tuple by one variable per
   * component.
   *
   * <p>A step list is told which to do, because the user wrote a pattern:
   * {@code from t : int * bool} names the element once, and {@code from (b, i)
   * : bool * int} names each component, and the engine grounds accordingly -- a
   * whole-tuple constraint for the first, a constraint per component for the
   * second. A tree has erased the pattern, so it tries one and then the other.
   */
  private boolean destructure;

  /**
   * Whether removing a generator's duplicate values would change the query's
   * answer, so that a generator that may produce one twice must be
   * deduplicated. {@code Expander}'s {@code dedupObservable}.
   */
  private boolean dedupObservable;

  /**
   * How many components a dropped leaf took off the front of the element, and
   * how many are left, or null if nothing was dropped.
   *
   * <p>A query whose rows are only counted may leave a variable it cannot
   * enumerate unbounded, and the leaf goes. A step list drops the *binding*,
   * and its conditions name what is left, so nothing moves; a tree's element is
   * positional, so what a filter above says as `#2 $0` becomes `$0`. The
   * projection above needs no such care -- it is unobservable here, and dropped
   * whole.
   */
  private int @Nullable [] dropped;

  /**
   * Names that the query's own leaves bind.
   *
   * <p>Told from two others: a name the scope around the query binds, and a
   * name a generator introduced itself, from an {@code exists} inside the
   * constraint. The first two are in scope where the generator is scanned and
   * must be *tested* against what bound them; only the third is projected away.
   * {@code Expander} asks the same question of its {@code allScanPats}.
   */
  private Set<Core.NamedPat> leafNames = ImmutableSet.of();

  /**
   * Conditions that a sealed generator subsumes, and that the filter they came
   * from can therefore drop. Identity, as in {@code Expander}: the same
   * expression written twice is not the same constraint.
   */
  private final Set<Core.Exp> subsumed =
      Collections.newSetFromMap(new IdentityHashMap<>());

  /**
   * What a generator makes of a condition that it does not entirely enforce:
   * {@code x > 1 andalso x < 10} against a generator that already bounds {@code
   * x} below is {@code x < 10}. Identity, as {@link #subsumed} is.
   *
   * <p>{@code Expander} does this in {@code expandFrom2}, running every
   * generator's {@code simplify} over each surviving conjunct. It is how a
   * query grounded by a transitive closure loses its {@code where}: the
   * generator is not sealed, and does not need to be, because {@code simplify}
   * answers {@code true}.
   */
  private final Map<Core.Exp, Core.Exp> simplified = new IdentityHashMap<>();

  /**
   * What a leaf that is an infinite range was tightened to, by identity.
   *
   * <p>A range is not an extent, so grounding has nothing to give it; what
   * bounds it is a literal bound in a condition above. `Expander` does this for
   * a step list with `rangeImpliedBounds`, `Fbbt.strengthen` and
   * `RangePushdown.apply`, in that order, and so does this.
   */
  private final Map<Core.Exp, Core.Exp> tightened = new IdentityHashMap<>();

  private RelExpander(
      TypeSystem typeSystem, Environment env, boolean rowsUsed) {
    this.typeSystem = typeSystem;
    this.env = env;
    this.rowsUsed = rowsUsed;
  }

  /**
   * Replaces every infinite-extent leaf of a tree with a collection that bounds
   * it, and throws if there is none.
   *
   * <p>This is what {@link Expander#expandFrom} does for a step list.
   */
  public static Core.Exp expand(
      TypeSystem typeSystem, Environment env, Core.Exp tree) {
    return expand(typeSystem, env, tree, true);
  }

  /**
   * Replaces every infinite-extent leaf of a tree with a collection that bounds
   * it, and throws if there is none.
   *
   * <p>If {@code rowsUsed} is false the query's rows are only counted, or
   * tested for existence, so a leaf that nothing constrains need not be
   * bounded: it is dropped.
   */
  public static Core.Exp expand(
      TypeSystem typeSystem, Environment env, Core.Exp tree, boolean rowsUsed) {
    return expand(typeSystem, env, tree, rowsUsed, ImmutableList.of());
  }

  /**
   * Replaces every infinite-extent leaf of a tree with a collection that bounds
   * it, naming the leaves with the patterns the query was written with.
   */
  public static Core.Exp expand(
      TypeSystem typeSystem,
      Environment env,
      Core.Exp tree,
      boolean rowsUsed,
      List<Core.Pat> leafPats) {
    return expand(
        typeSystem, env, tree, rowsUsed, leafPats, new IdentityHashMap<>());
  }

  /**
   * As {@link #expand(TypeSystem, Environment, Core.Exp, boolean, List)}, and
   * puts into {@code leafNames} the name of the leaf that each collection it
   * built bounds, so that the lowering can name each scan after the variable
   * whose values it is scanning.
   */
  public static Core.Exp expand(
      TypeSystem typeSystem,
      Environment env,
      Core.Exp tree,
      boolean rowsUsed,
      List<Core.Pat> leafPats,
      Map<Core.Exp, String> leafNames) {
    final RelExpander expander = new RelExpander(typeSystem, env, rowsUsed);
    expander.dedupObservable = rowsUsed || hasTakeOrSkip(tree);
    expander.leafPats = ImmutableList.copyOf(leafPats);
    final Core.Exp expanded = expander.expand(tree, ImmutableList.of());
    leafNames.putAll(expander.collectionNames);
    return expanded;
  }

  /**
   * Returns a pattern for each leaf of a tree, named as the query named it, or
   * empty where the tree does not say.
   *
   * <p>A tree has no names -- a leaf is a bare expression, spec.md §3.1 -- but
   * a query with several binders ends in a projection that names its element's
   * components after them: {@code project [{deptno = #2 $0, loc = #1 $0, name =
   * #3 $0}]}. A join concatenates its inputs' components (§15), so component
   * <i>k</i> is leaf <i>k</i>, and the projection is the map from leaf to name.
   *
   * <p>It matters beyond plan text. Grounding names what it builds after the
   * leaf it bounds, a group's key record sorts its fields by label, and a
   * generated label sorts differently from the one the user wrote -- which puts
   * a query's rows in a different order.
   */
  public static List<Core.Pat> leafPats(Core.Exp tree) {
    if (!(tree instanceof Core.Project)) {
      return ImmutableList.of();
    }
    final Core.Project project = (Core.Project) tree;
    if (!(project.exp instanceof Core.Tuple)
        || !(project.exp.type instanceof RecordType)) {
      return ImmutableList.of();
    }
    // Which component each output field reads, and what it calls it.
    final Map<Integer, String> names = new HashMap<>();
    final boolean[] ok = {true};
    ((Core.Tuple) project.exp)
        .forEach(
            (i, name, exp) -> {
              if (!(exp instanceof Core.Apply)) {
                ok[0] = false;
                return;
              }
              final Core.Apply apply = (Core.Apply) exp;
              if (!(apply.fn instanceof Core.RecordSelector)
                  || !(apply.arg instanceof Core.Input)
                  || ((Core.Input) apply.arg).i != 0) {
                ok[0] = false;
                return;
              }
              names.put(((Core.RecordSelector) apply.fn).slot, name);
            });
    if (!ok[0]) {
      return ImmutableList.of();
    }
    final List<Core.Exp> leaves = new ArrayList<>();
    if (!collectLeaves(project.input, leaves)
        || leaves.size() != names.size()) {
      return ImmutableList.of();
    }
    final ImmutableList.Builder<Core.Pat> pats = ImmutableList.builder();
    for (int i = 0; i < leaves.size(); i++) {
      final @Nullable String name = names.get(i);
      if (name == null) {
        return ImmutableList.of();
      }
      pats.add(core.idPat(leaves.get(i).type.elementType(), name, 0));
    }
    return pats.build();
  }

  /**
   * Collects the leaves under a node, left to right, and returns whether every
   * node on the way is one that leaves the components alone.
   *
   * <p>A filter and a join do; anything else -- a group, a projection, a set
   * operator -- makes an element that is not the concatenation of the leaves,
   * and then a component says nothing about a leaf.
   */
  private static boolean collectLeaves(Core.Exp node, List<Core.Exp> leaves) {
    if (!(node instanceof Core.Rel)) {
      leaves.add(node);
      return true;
    }
    if (node instanceof Core.Filter) {
      return collectLeaves(((Core.Filter) node).input, leaves);
    }
    if (node instanceof Core.Join) {
      final Core.Join join = (Core.Join) node;
      return collectLeaves(join.left, leaves)
          && collectLeaves(join.right, leaves);
    }
    return false;
  }

  /**
   * Returns whether a tree has a leaf that is an infinite extent, and therefore
   * needs grounding.
   *
   * <p>The walk does not descend into a nested tree, which is a root of its own
   * and is grounded when the pass that walks the expression reaches it.
   */
  public static boolean containsUnbounded(Core.Exp tree) {
    if (!(tree instanceof Core.Rel)) {
      // A leaf that is an infinite range -- `[1..]` -- is unbounded too, and
      // the pipeline that bounds it (FBBT, then the pushdown) runs from the
      // same latch.
      return Extents.isInfinite(tree) || RangePushdown.isInfiniteRange(tree);
    }
    for (Core.Exp input : ((Core.Rel) tree).inputs()) {
      if (containsUnbounded(input)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns whether a tree has a node that depends on how many rows there are,
   * which makes a generator's duplicates observable even where the rows
   * themselves are not read.
   */
  private static boolean hasTakeOrSkip(Core.Exp tree) {
    if (!(tree instanceof Core.Rel)) {
      return false;
    }
    if (tree instanceof Core.Take || tree instanceof Core.Skip) {
      return true;
    }
    for (Core.Exp input : ((Core.Rel) tree).inputs()) {
      if (hasTakeOrSkip(input)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Rewrites a node, carrying the conditions of the filters passed on the way
   * down, and replacing each infinite-extent leaf with what grounds it.
   */
  private Core.Exp expand(Core.Exp exp, List<Core.Exp> conditions) {
    if (!(exp instanceof Core.Rel)) {
      if (exp.isExtent()) {
        return bound(exp, conditions);
      }
      // An infinite range is unbounded too, and what bounds it is a literal
      // bound in a filter above rather than a generator: `[1..]` under `where
      // x < 5` is `[1..^5]`. The step list does this in `RangePushdown.apply`,
      // after FBBT has deduced what bounds it can; a tree has no FBBT yet, so
      // this reaches only the bounds the query wrote.
      final RangePushdown.@Nullable Tightening tightening =
          RangePushdown.tighten(typeSystem, exp, conditions);
      if (tightening != null) {
        // The range now enforces the conjunct, so the filter can drop it.
        subsumed.add(tightening.consumedConjunct);
        return tightening.newExp;
      }
      return exp;
    }
    if (exp instanceof Core.Filter) {
      final Core.Filter filter = (Core.Filter) exp;
      final List<Core.Exp> conjuncts = core.decomposeAnd(filter.condition);
      // This filter's conjuncts go before the ones already carried, which are
      // from filters above it and so were written later. Order counts: where
      // two constraints could each generate a name, the engine keeps the
      // first, so the query's own order is the one to present them in.
      final List<Core.Exp> conditions2 = new ArrayList<>(conjuncts);
      conditions2.addAll(conditions);
      final Core.Exp input = expand(filter.input, conditions2);
      // A conjunct that a sealed generator subsumes is now enforced by the
      // collection that replaced the leaf, so the filter need not test it
      // again; if that was all it tested, the filter goes.
      final List<Core.Exp> remaining = new ArrayList<>();
      conjuncts.forEach(
          conjunct -> {
            if (subsumed.contains(conjunct)) {
              return;
            }
            final Core.Exp exp2 = simplified.getOrDefault(conjunct, conjunct);
            if (!exp2.isBoolLiteral(true)) {
              remaining.add(shiftFields(exp2));
            }
          });
      if (remaining.isEmpty()) {
        return input;
      }
      return filter.copy(input, core.andAlso(typeSystem, remaining));
    }
    if (exp instanceof Core.Project) {
      // A projection changes what $0 means, and substituting the projection
      // into a condition says the same thing about the element below it. The
      // step list cannot do this -- it has no expression to substitute, only
      // steps -- so a tree grounds strictly more; see discussion.md §12.
      final Core.Project project = (Core.Project) exp;
      final List<Core.Exp> pushed = new ArrayList<>();
      conditions.forEach(
          condition -> pushed.add(subst(condition, project.exp)));
      final Core.Exp input = expand(project.input, pushed);
      if (!rowsUsed) {
        // Nothing reads the rows, so a projection is unobservable: it maps
        // each row to a value and changes how many there are not at all. It
        // must go rather than stay, because dropping a leaf that nothing
        // constrains -- which is what `rowsUsed` false allows -- leaves the
        // element with fewer components than this was written for, and `{w =
        // #1 $0, x = #2 $0}` over `[3]` reads a scalar as a pair. The step
        // list changes the row's type here too: `exists w, x where x = 3`
        // becomes `from x in [3]`.
        return input;
      }
      return project.copy(typeSystem, input, project.exp);
    }
    // A step that neither changes the element nor drops rows by position
    // passes the conditions down. The step list does the same, by ignoring
    // every step but a scan and a where, so `from x take 3 where x elem
    // [1, 2, 3]` bounds x and then takes 3 of what remains.
    if (exp instanceof Core.Sort) {
      final Core.Sort sort = (Core.Sort) exp;
      return sort.copy(typeSystem, expand(sort.input, conditions), sort.exp);
    }
    if (exp instanceof Core.Unorder) {
      final Core.Unorder unorder = (Core.Unorder) exp;
      return unorder.copy(typeSystem, expand(unorder.input, conditions));
    }
    if (exp instanceof Core.Skip) {
      final Core.Skip skip = (Core.Skip) exp;
      return skip.copy(expand(skip.input, conditions), skip.count);
    }
    if (exp instanceof Core.Take) {
      final Core.Take take = (Core.Take) exp;
      return take.copy(expand(take.input, conditions), take.count);
    }
    if (exp instanceof Core.Join) {
      return expandJoinTree((Core.Join) exp, conditions);
    }
    // The remaining nodes build their element, so a condition above one says
    // nothing about the element below it; but what is below still has to be
    // expanded, or an unbounded leaf survives under a group.
    if (exp instanceof Core.Group) {
      final Core.Group group = (Core.Group) exp;
      return group.copy(
          typeSystem,
          expand(group.input, ImmutableList.of()),
          group.keys,
          group.aggregates);
    }
    if (exp instanceof Core.IfEmpty) {
      final Core.IfEmpty ifEmpty = (Core.IfEmpty) exp;
      return ifEmpty.copy(
          expand(ifEmpty.input, ImmutableList.of()), ifEmpty.exp);
    }
    if (exp instanceof Core.SetRel) {
      final Core.SetRel setRel = (Core.SetRel) exp;
      final List<Core.Exp> inputs = new ArrayList<>();
      setRel.inputs.forEach(
          input -> inputs.add(expand(input, ImmutableList.of())));
      return setRel.copy(typeSystem, setRel.distinct, inputs);
    }
    // A node whose element does not come from a leaf below it in a way this
    // pass understands: leave its inputs alone.
    return exp;
  }

  /**
   * Grounds every extent leaf of a join tree at once.
   *
   * <p>A step list grounds all of a query's patterns together, because a
   * constraint can tie two of them to each other: {@code from x, y where (x, y)
   * elem pairs} generates for both from one constraint. A tree says the same
   * thing with a join, so the leaves of a join tree are grounded together too.
   * The conditions above the tree describe the joined element, so they are read
   * in terms of the leaves by substituting the yields on the way down.
   */
  private Core.Exp expandJoinTree(Core.Join join, List<Core.Exp> conditions) {
    final int mark = nextName;
    final int leafMark = nextLeafPat;
    try {
      return expandJoinTree(join, conditions, false);
    } catch (CompileException e) {
      // The first attempt got far enough to record what it thought a
      // generator subsumed, or simplified, or which leaf it dropped. None of
      // that survives the attempt: the second one asks different questions of
      // different generators, and an answer from the first is about a tree
      // that is not being built.
      subsumed.clear();
      simplified.clear();
      tightened.clear();
      dropped = null;
      nextName = mark;
      nextLeafPat = leafMark;
      return expandJoinTree(join, conditions, true);
    }
  }

  private Core.Exp expandJoinTree(
      Core.Join join, List<Core.Exp> conditions, boolean destructure) {
    this.destructure = destructure;
    // Whatever a join further up dropped is not this join's business: the
    // shift belongs to the element the drop changed, and this one has its own.
    final int @Nullable [] outerDropped = dropped;
    dropped = null;
    final Frame frame = collect(join);
    final Set<Core.NamedPat> outerLeafNames = leafNames;
    final ImmutableSet.Builder<Core.NamedPat> leafNamesB =
        ImmutableSet.builder();
    frame.leaves.values().forEach(pat -> leafNamesB.addAll(pat.expand()));
    leafNames = leafNamesB.build();
    final List<Core.Exp> constraints = new ArrayList<>(frame.constraints);
    final Map<Core.Exp, Core.Exp> originals =
        new IdentityHashMap<>(frame.originals);
    conditions.forEach(
        condition -> {
          final Core.Exp constraint = subst(condition, frame.element);
          originals.put(constraint, condition);
          constraints.add(constraint);
          // Above the tree, so after everything in it -- a `where` that
          // follows every scan.
          frame.order.add(new Expander.Ground(null, constraint));
        });
    // Left to right through the tree, and not `frame.leaves`, whose order is
    // an IdentityHashMap's. The engine registers each extent as it is given
    // them and improves the generators after every constraint, so the order
    // decides which generator it settles on for each name -- and a hash
    // order makes that decision differently from one run to the next. A step
    // list gives them in the order its scans are written.
    tightenRanges(frame, constraints);
    final PairList<Core.Pat, Core.Exp> extents = PairList.of();
    extentsInOrder(join, frame, extents);
    if (extents.isEmpty() && tightened.isEmpty()) {
      return join.copy(
          typeSystem,
          join.joinType,
          join.binder,
          expand(join.left, ImmutableList.of()),
          expand(join.right, ImmutableList.of()),
          join.condition);
    }
    final Generators.Cache cache = new Generators.Cache(typeSystem, env);
    // Interleaved, in the order the walk reached them; the conjuncts that
    // strengthening added are not in that order, having no place in the tree,
    // so they go at the end.
    final Set<Core.Exp> written =
        Collections.newSetFromMap(new IdentityHashMap<>());
    written.addAll(constraints);
    final List<Expander.Ground> order = new ArrayList<>(frame.order);
    strengthen(constraints, extents)
        .forEach(
            constraint -> {
              if (!written.contains(constraint)) {
                order.add(new Expander.Ground(null, constraint));
              }
            });
    Expander.ground(cache, order);
    // The same bookkeeping the single-leaf path does, and by the same method:
    // an inline copy of it here recorded what a sealed generator subsumes and
    // not what one simplifies, so `from x, y where path (x, y)` kept a filter
    // that `from p where path p` had learned to drop.
    frame.leaves.forEach(
        (leaf, pat) -> {
          if (leaf.isExtent()) {
            recordSubsumed(pat, cache, originals);
          }
        });
    final Core.@Nullable Exp chained = scheduled(join, frame, cache);
    final Core.Exp result =
        chained != null
            ? chained
            : rebuild(join, frame, cache, ImmutableMap.of());
    if (dropped == null) {
      dropped = outerDropped;
    }
    leafNames = outerLeafNames;
    return result;
  }

  /**
   * Grounds a join tree whose leaves share a generator, by scanning each
   * generator once and joining on the names already bound.
   *
   * <p>A generator may bind several names: {@code (x, y) elem pairs} grounds
   * both. Replacing each leaf on its own enumerates the collection once per
   * leaf and pairs every value with every other, which is not what the
   * constraint said. {@code Expander} scans a generator once and lets a later
   * one join on whichever name is bound (its {@code sharedPats}, and the {@code
   * patternState} ordering of {@code addGeneratorScan}); this builds the same
   * chain, with the same builder, and the tree scans what it yields.
   *
   * <p>A generator per *name*, and then a schedule. A rule that decided at a
   * join, from that join's two sides, would decide too late: the tree picks a
   * generator per leaf, so two leaves can hold generators whose patterns
   * overlap and neither is the one to key on.
   *
   * <p>Returns null where this does not apply -- nothing is shared, a leaf is
   * not an extent, a node between the leaves is not a join, a join carries a
   * condition, or no order satisfies the generators' dependencies -- and the
   * ordinary leaf-by-leaf path runs instead.
   */
  private Core.@Nullable Exp scheduled(
      Core.Join join, Frame frame, Generators.Cache cache) {
    if (!joinsAndExtents(join, frame)) {
      return null;
    }
    // One generator per name, and the names each generator provides.
    final List<Core.NamedPat> names = new ArrayList<>();
    frame.leaves.forEach(
        (leaf, pat) -> {
          if (contains(join, leaf)) {
            names.addAll(pat.expand());
          }
        });
    final List<Generator> generators = new ArrayList<>();
    for (Core.NamedPat name : names) {
      final @Nullable Generator generator = cache.bestGenerator(name);
      if (generator == null
          || generator.cardinality == Generator.Cardinality.INFINITE) {
        return null;
      }
      if (!generators.contains(generator)) {
        generators.add(generator);
      }
    }
    if (generators.size() == names.size()) {
      // Nothing is shared, so the leaves are independent and the ordinary
      // path says so more directly.
      return null;
    }
    if (generators.size() == 1) {
      // One generator binds every name, which `commonGenerator` says in one
      // projection rather than a chain of one.
      return null;
    }

    // Schedule: a generator can be scanned once every name it reads that one
    // of these leaves binds has been scanned.
    final List<Integer> order = new ArrayList<>();
    final Set<Core.NamedPat> bound = new LinkedHashSet<>();
    while (order.size() < generators.size()) {
      int next = -1;
      for (int i = 0; i < generators.size(); i++) {
        if (order.contains(i)) {
          continue;
        }
        boolean ready = true;
        for (Core.NamedPat free : generators.get(i).freePats) {
          if (names.contains(free) && !bound.contains(free)) {
            ready = false;
            break;
          }
        }
        if (ready) {
          next = i;
          break;
        }
      }
      if (next < 0) {
        return null;
      }
      order.add(next);
      bound.addAll(generators.get(next).pat.expand());
    }

    // Build the chain, as `addGeneratorScan` builds it: a name the chain has
    // already bound is renamed in the scan pattern and tested against what
    // bound it.
    final FromBuilder fromBuilder = core.fromBuilder(typeSystem);
    final Set<Core.NamedPat> scanned = new LinkedHashSet<>();
    boolean projectsAway = false;
    boolean anyDuplicates = false;
    for (int i : order) {
      final Generator generator = generators.get(i);
      final Map<Core.NamedPat, Core.IdPat> renames = new LinkedHashMap<>();
      final List<Core.Exp> conditions = new ArrayList<>();
      for (Core.NamedPat p : generator.pat.expand()) {
        if (scanned.contains(p)) {
          // The name is numbered, and not just the ordinal: the chain is one
          // scope, where `Expander` builds a subquery per generator so its
          // `p'` never meets another, and what collides here is the name --
          // a step's bindings are keyed by it.
          final Core.IdPat fresh = freshPat(p);
          renames.put(p, fresh);
          conditions.add(core.equal(typeSystem, core.id(fresh), core.id(p)));
        } else if (!names.contains(p)) {
          projectsAway = true;
        }
      }
      anyDuplicates |= !generator.unique;
      fromBuilder.scan(
          Expander.renamePatterns(typeSystem, generator.pat, renames),
          generator.exp,
          core.andAlso(typeSystem, conditions));
      scanned.addAll(generator.pat.expand());
    }
    final Core.Exp yieldExp = core.recordOrAtom(typeSystem, names);
    fromBuilder.yield_(yieldExp);
    if (dedupObservable && (anyDuplicates || projectsAway)) {
      fromBuilder.distinct();
      fromBuilder.order(yieldExp);
    }
    final Core.Exp collection = fromBuilder.build();

    // The tree above wants the join's element, which is written in terms of
    // the leaves' names; read each out of the row the chain yields.
    final Core.Exp row = core.input0(collection.type.elementType());
    final Map<Core.NamedPat, Core.Exp> paths = new LinkedHashMap<>();
    if (names.size() == 1) {
      paths.put(names.get(0), row);
    } else {
      final List<String> fields =
          ImmutableList.copyOf(
              ((RecordLikeType) collection.type.elementType())
                  .argNameTypes()
                  .keySet());
      names.forEach(
          name ->
              paths.put(
                  name,
                  core.field(typeSystem, row, fields.indexOf(name.name))));
    }
    final Core.Exp element =
        requireNonNull(frame.elements.get(join))
            .accept(
                new Shuttle(typeSystem) {
                  @Override
                  protected Core.Exp visit(Core.Id id) {
                    final Core.@Nullable Exp path = paths.get(id.idPat);
                    return path != null ? path : id;
                  }
                });
    return core.project(typeSystem, collection, element);
  }

  /** Collects the extent leaves under a node, left to right. */
  private static void extentsInOrder(
      Core.Exp node, Frame frame, PairList<Core.Pat, Core.Exp> extents) {
    if (node instanceof Core.Join) {
      extentsInOrder(((Core.Join) node).left, frame, extents);
      extentsInOrder(((Core.Join) node).right, frame, extents);
      return;
    }
    final Core.@Nullable Pat pat = frame.leaves.get(node);
    if (pat != null && node.isExtent()) {
      extents.add(pat, node);
    }
  }

  /**
   * Returns whether every node under a join is a join or an extent leaf, and
   * every join is unconditional -- the shape the chain can stand in for.
   */
  private static boolean joinsAndExtents(Core.Exp node, Frame frame) {
    if (node instanceof Core.Join) {
      final Core.Join join = (Core.Join) node;
      return join.joinType == Core.Rel.JoinType.INNER
          && join.binder == null
          && join.condition.isBoolLiteral(true)
          && joinsAndExtents(join.left, frame)
          && joinsAndExtents(join.right, frame);
    }
    return node.isExtent() && frame.leaves.containsKey(node);
  }

  /**
   * Rebuilds a join tree with each extent leaf replaced by the collection that
   * bounds it. A leaf whose generator reads another leaf's element makes the
   * join dependent, its binder naming what the right side reads.
   */
  private Core.Exp rebuild(
      Core.Exp node,
      Frame frame,
      Generators.Cache cache,
      Map<Core.NamedPat, Core.Exp> bound) {
    if (node instanceof Core.Filter) {
      // The filter's conjuncts were grounded with the rest of the tree's
      // constraints, so what a sealed generator now enforces comes out, and
      // the filter goes if that was all of it.
      final Core.Filter filter = (Core.Filter) node;
      final Core.Exp input = rebuild(filter.input, frame, cache, bound);
      final List<Core.Exp> remaining = new ArrayList<>();
      core.decomposeAnd(filter.condition)
          .forEach(
              conjunct -> {
                if (subsumed.contains(conjunct)) {
                  return;
                }
                final Core.Exp exp2 =
                    simplified.getOrDefault(conjunct, conjunct);
                if (!exp2.isBoolLiteral(true)) {
                  remaining.add(exp2);
                }
              });
      if (remaining.isEmpty()) {
        return input;
      }
      return filter.copy(input, core.andAlso(typeSystem, remaining));
    }
    if (!(node instanceof Core.Join)) {
      if (!node.isExtent()) {
        final Core.@Nullable Exp finite = tightened.get(node);
        if (finite != null) {
          return finite;
        }
        return node instanceof Core.Rel
            ? expand(node, ImmutableList.of())
            : node;
      }
      // A leaf that `collect` reached, so `leaves` has a pattern for it.
      return bounded(
          node, requireNonNull(frame.leaves.get(node)), cache, bound);
    }
    final Core.Join join = (Core.Join) node;
    if (!rowsUsed) {
      // Nothing looks at the rows, so a side that nothing constrains cannot
      // affect the answer, and need not be enumerated.
      if (droppable(join.right, frame, cache)) {
        // The right's components come off the end, so what is left keeps its
        // positions and nothing above needs rewriting.
        return rebuild(join.left, frame, cache, bound);
      }
      if (droppable(join.left, frame, cache)) {
        final Core.Exp survivor = rebuild(join.right, frame, cache, bound);
        dropped =
            new int[] {
              core.componentCount(join.left), core.componentCount(join.right)
            };
        return survivor;
      }
    }
    final @Nullable Generator common =
        commonGenerator(join, frame, cache, bound);
    if (common != null) {
      return fromCommon(join, frame, common, bound);
    }
    final Core.Exp right = join.right;
    final @Nullable Generator rightGenerator =
        right.isExtent()
            ? generator(requireNonNull(frame.leaves.get(right)), cache)
            : null;
    if (rightGenerator != null && !free(rightGenerator, bound).isEmpty()) {
      // Correlated: the right side reads names that the left side binds. The
      // join becomes dependent, its binder naming the left element, and each
      // name the generator reads becomes the path that reads it out of that
      // element. The left side need not be a leaf: `from x, y where edge
      // (x, y) join y2 where y2 = y` reads `y` out of a pair.
      final Core.Exp leftElement = frame.elements.get(join.left);
      final Core.IdPat param = groundPat(join.left.type.elementType());
      final Map<Core.NamedPat, Core.Exp> paths = new LinkedHashMap<>();
      for (Core.NamedPat name : free(rightGenerator, bound)) {
        final Core.@Nullable Exp path =
            leftElement == null
                ? null
                : pathTo(leftElement, core.id(param), name);
        if (path == null) {
          // What the generator reads is not bound on the left -- it is a leaf
          // further away, which would need the join reordered.
          throw new CompileException(
              "pattern is not grounded", false, right.pos);
        }
        paths.put(name, path);
      }
      if (!join.condition.isBoolLiteral(true)) {
        // A dependent join has a condition, so the node could carry this one;
        // the step list cannot ground such a query, and `groundingAgrees`
        // holds the two engines to the same verdict in both directions.
        // Lifting this is a change to what compiles, like discussion.md §12,
        // and belongs with that one rather than smuggled in here.
        throw new CompileException("pattern is not grounded", false, right.pos);
      }
      paths.putAll(bound);
      final Core.Exp collection = replace(rightGenerator.exp, paths);
      // The yield needs no substitution: a dependent join's yield is over
      // `$0` and `$1` exactly as this join's already was.
      return core.join(
          typeSystem,
          join.joinType,
          param,
          rebuild(join.left, frame, cache, bound),
          collection,
          join.condition);
    }
    if ((rightGenerator != null || bounded(right, frame))
        && join.condition.isBoolLiteral(true)
        && reads(join.left, frame, cache, bound, frame.leaves.get(right))) {
      // The other way round: the right side grounds on its own and the left
      // side reads it, so the right comes first. `from dno, name, v where v
      // elem depts andalso #deptno v = dno` scans `depts` and reads `dno` out
      // of each row; the step list reorders the same way, deferring `dno`
      // until after `v`, as such-that.smli's comment says.
      //
      // The right side grounds on its own where a generator grounds it and
      // also where it is a leaf that was never unbounded: `from x, y in
      // [2, 3] where x > y` needs no generator for `y`, and `x` reads it.
      final Core.Pat rightPat = requireNonNull(frame.leaves.get(right));
      final Core.IdPat param = groundPat(right.type.elementType());
      final Map<Core.NamedPat, Core.Exp> bound2 = new LinkedHashMap<>(bound);
      rightPat
          .expand()
          .forEach(
              name ->
                  bound2.put(
                      name,
                      requireNonNull(path(rightPat, core.id(param), name))));
      final Core.Exp left = rebuild(join.left, frame, cache, bound2);
      final Core.Exp boundedRight =
          rightGenerator == null
              ? right
              : bounded(right, rightPat, cache, bound);
      // The sides swap, so the yield commutes with them: what was `$0` is now
      // `$1` and what was `$1` is now `$0` (spec.md §3.4).
      final Core.Join swapped =
          core.join(
              typeSystem,
              join.joinType,
              param,
              boundedRight,
              left,
              join.condition);
      // Swapping the inputs moves the components, and with no yield to absorb
      // the swap a projection puts them back where the tree above expects
      // them -- the re-path that discussion.md §15 records as commute's cost.
      return permute(
          swapped,
          core.componentCount(boundedRight),
          core.componentCount(left));
    }
    return join.copy(
        typeSystem,
        join.joinType,
        join.binder,
        rebuild(join.left, frame, cache, bound),
        rebuild(right, frame, cache, bound),
        join.condition);
  }

  /**
   * Grounds a join tree that one generator binds every leaf of.
   *
   * <p>`where {deptno = dno, dname = name} elem depts` binds both, so the
   * leaves and the join between them become one scan of that generator, read
   * through the paths its pattern gives each name. Replacing them separately
   * would enumerate the collection once per leaf and pair every value with
   * every other.
   */
  private Core.Exp fromCommon(
      Core.Join join,
      Frame frame,
      Generator common,
      Map<Core.NamedPat, Core.Exp> bound) {
    final Core.Exp collection = replace(common.exp, bound);
    if (dedupObservable && !common.unique) {
      // A generator may repeat a value where an unbounded scan yields each
      // assignment once, so `Expander` scans it under its own pattern,
      // deduplicates, and orders by the record of its variables. That order
      // is the query's answer, not a detail: `from x, y where (y, x) elem
      // [(1, true), (2, false)]` lists `false 2` before `true 1`.
      final List<Core.NamedPat> vars = common.pat.expand();
      final FromBuilder fromBuilder = core.fromBuilder(typeSystem);
      fromBuilder.scan(common.pat, collection);
      fromBuilder.distinct();
      final Core.Exp row = core.recordOrAtom(typeSystem, vars);
      fromBuilder.order(row);
      fromBuilder.yield_(row);
      final Core.Exp deduped = fromBuilder.build();
      // The rows are a record of the variables now, so the element reads
      // each out of that rather than out of the generator's own row.
      final Core.Exp element =
          rename(
              requireNonNull(frame.elements.get(join)),
              core.input0(deduped.type.elementType()),
              core.recordOrAtomPat(typeSystem, vars));
      return core.project(typeSystem, deduped, element);
    }
    if (!RelBuilder.destructurable(common.pat)) {
      // The pattern can fail -- `{deptno = dno, dname = name, loc =
      // "CHICAGO"} elem depts` binds two names and tests a third field --
      // and a projection reads every row where the pattern matches only
      // some. Scanning it filters, and the element is already written in
      // terms of the names it binds.
      final FromBuilder fromBuilder = core.fromBuilder(typeSystem);
      fromBuilder.scan(common.pat, collection);
      fromBuilder.yield_(requireNonNull(frame.elements.get(join)));
      return fromBuilder.build();
    }
    final Core.Exp element =
        rename(
            requireNonNull(frame.elements.get(join)),
            core.input0(collection.type.elementType()),
            common.pat);
    return core.project(typeSystem, collection, element);
  }

  /**
   * Projects a join whose inputs were swapped back into the component order the
   * tree above expects: the last {@code k} first, then the first {@code m}.
   */
  private Core.Exp permute(Core.Exp join, int m, int k) {
    final Core.Input element = core.input0(join.type.elementType());
    final List<Core.Exp> exps = new ArrayList<>();
    for (int i = 0; i < k; i++) {
      exps.add(core.field(typeSystem, element, m + i));
    }
    for (int i = 0; i < m; i++) {
      exps.add(core.field(typeSystem, element, i));
    }
    return core.project(typeSystem, join, core.tuple(typeSystem, null, exps));
  }

  /**
   * Moves a condition onto the element a dropped leaf left behind: {@code #2
   * $0} reads the second component, and with the first gone it is the first, or
   * the whole element where only one is left.
   */
  private Core.Exp shiftFields(Core.Exp exp) {
    final int @Nullable [] drop = dropped;
    if (drop == null) {
      return exp;
    }
    return exp.accept(
        new Shuttle(typeSystem) {
          @Override
          protected Core.Exp visit(Core.Apply apply) {
            if (apply.fn instanceof Core.RecordSelector
                && apply.arg.op == Op.INPUT
                && ((Core.Input) apply.arg).i == 0) {
              final int slot = ((Core.RecordSelector) apply.fn).slot;
              if (slot >= drop[0]) {
                final Core.Exp element = core.input0(apply.arg.type);
                return drop[1] == 1
                    ? core.input0(apply.type)
                    : core.field(typeSystem, element, slot - drop[0]);
              }
            }
            return super.visit(apply);
          }
        });
  }

  /** Returns whether a node is a leaf that needs no generator. */
  private static boolean bounded(Core.Exp node, Frame frame) {
    return !node.isExtent() && frame.leaves.containsKey(node);
  }

  /**
   * Returns whether any extent leaf under a node has a generator that reads a
   * name the given pattern binds.
   */
  private boolean reads(
      Core.Exp node,
      Frame frame,
      Generators.Cache cache,
      Map<Core.NamedPat, Core.Exp> bound,
      Core.@Nullable Pat pat) {
    if (pat == null) {
      return false;
    }
    final Set<Core.NamedPat> names = new LinkedHashSet<>(pat.expand());
    for (Map.Entry<Core.Exp, Core.Pat> entry : frame.leaves.entrySet()) {
      if (!contains(node, entry.getKey()) || !entry.getKey().isExtent()) {
        continue;
      }
      for (Core.NamedPat name : entry.getValue().expand()) {
        final @Nullable Generator generator = cache.bestGenerator(name);
        if (generator != null
            && free(generator, bound).stream().anyMatch(names::contains)) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Returns the generator that binds the names of every leaf under a node, if
   * one does.
   *
   * <p>Null if the leaves have different generators, if any is not an extent,
   * or if a condition of the joins between them is not one the generator
   * enforces -- in which case they are replaced one at a time, as before.
   */
  private @Nullable Generator commonGenerator(
      Core.Exp node,
      Frame frame,
      Generators.Cache cache,
      Map<Core.NamedPat, Core.Exp> bound) {
    @Nullable Generator common = null;
    for (Map.Entry<Core.Exp, Core.Pat> entry : frame.leaves.entrySet()) {
      if (!contains(node, entry.getKey())) {
        continue;
      }
      if (!entry.getKey().isExtent()) {
        return null;
      }
      for (Core.NamedPat name : entry.getValue().expand()) {
        final @Nullable Generator generator = cache.bestGenerator(name);
        if (generator == null
            || generator.cardinality == Generator.Cardinality.INFINITE
            || !free(generator, bound).isEmpty()) {
          return null;
        }
        if (common == null) {
          common = generator;
        } else if (common != generator) {
          return null;
        }
      }
    }
    if (common == null || common.pat instanceof Core.NamedPat) {
      // A generator that binds one name grounds one leaf, which the ordinary
      // path handles.
      return null;
    }
    return contains(node, node) && conditionsEnforced(node) ? common : null;
  }

  /** Returns whether a node contains another, by identity. */
  private static boolean contains(Core.Exp node, Core.Exp target) {
    if (node == target) {
      return true;
    }
    if (node instanceof Core.Filter) {
      return contains(((Core.Filter) node).input, target);
    }
    if (!(node instanceof Core.Join)) {
      return false;
    }
    final Core.Join join = (Core.Join) node;
    return contains(join.left, target) || contains(join.right, target);
  }

  /**
   * Returns whether every join under a node has a trivial condition, or one
   * that a generator has taken over.
   */
  private boolean conditionsEnforced(Core.Exp node) {
    if (node instanceof Core.Filter) {
      // Collapsing the leaves under a filter into one scan would discard the
      // filter with the join it replaces. A generator that subsumes the
      // filter's conjuncts would make that safe; short of knowing so, leave
      // the leaves to be replaced one at a time, with the filter in place.
      return false;
    }
    if (!(node instanceof Core.Join)) {
      return true;
    }
    final Core.Join join = (Core.Join) node;
    return join.condition.isBoolLiteral(true)
        && conditionsEnforced(join.left)
        && conditionsEnforced(join.right);
  }

  /**
   * Replaces each name of a pattern with the path that reads it out of an
   * element.
   */
  private Core.Exp rename(Core.Exp exp, Core.Exp element, Core.Pat pat) {
    return exp.accept(
        new Shuttle(typeSystem) {
          @Override
          protected Core.Exp visit(Core.Id id) {
            final Core.@Nullable Exp path = path(pat, element, id.idPat);
            return path != null ? path : id;
          }
        });
  }

  /**
   * Returns the expression that reads a name out of an element, given the
   * expression that says what the element is made of, or null if the element
   * does not contain the name.
   *
   * <p>The inverse of {@link Frame#elements}: that maps a node to its element
   * written in terms of the names of the leaves below it, and this reads one of
   * those names back out.
   */
  private Core.@Nullable Exp pathTo(
      Core.Exp element, Core.Exp accessor, Core.NamedPat name) {
    if (element instanceof Core.Id) {
      return ((Core.Id) element).idPat.equals(name) ? accessor : null;
    }
    if (element.op == Op.TUPLE) {
      final List<Core.Exp> args = ((Core.Tuple) element).args;
      for (int i = 0; i < args.size(); i++) {
        final Core.@Nullable Exp exp =
            pathTo(args.get(i), core.field(typeSystem, accessor, i), name);
        if (exp != null) {
          return exp;
        }
      }
    }
    return null;
  }

  /** Replaces each name of a map with the expression it maps to. */
  private Core.Exp replace(
      Core.Exp exp, Map<Core.NamedPat, Core.Exp> replacements) {
    return exp.accept(
        new Shuttle(typeSystem) {
          @Override
          protected Core.Exp visit(Core.Id id) {
            final Core.@Nullable Exp exp = replacements.get(id.idPat);
            return exp != null ? exp : id;
          }
        });
  }

  /**
   * Returns whether a side of a join is an infinite extent that no constraint
   * mentions, and can therefore be dropped when the rows are not used.
   */
  private boolean droppable(
      Core.Exp node, Frame frame, Generators.Cache cache) {
    if (!(node instanceof Core.Rel)
        && Extents.isInfinite(node)
        && frame.leaves.containsKey(node)) {
      for (Core.NamedPat name :
          requireNonNull(frame.leaves.get(node)).expand()) {
        final @Nullable Generator best = cache.bestGenerator(name);
        if (best != null
            && best.cardinality != Generator.Cardinality.INFINITE) {
          // Something bounds it after all.
          return false;
        }
        if (frame.constraints.stream()
            .anyMatch(constraint -> mentions(constraint, name))) {
          return false;
        }
      }
      return true;
    }
    return false;
  }

  /** Returns whether an expression mentions a name. */
  private static boolean mentions(Core.Exp exp, Core.NamedPat name) {
    final boolean[] found = {false};
    exp.accept(
        new Visitor() {
          @Override
          protected void visit(Core.Id id) {
            if (id.idPat.equals(name)) {
              found[0] = true;
            }
          }
        });
    return found[0];
  }

  /** Returns the generator of a leaf named by a single variable, or null. */
  private @Nullable Generator generator(Core.Pat pat, Generators.Cache cache) {
    return pat instanceof Core.NamedPat
        ? cache.bestGenerator((Core.NamedPat) pat)
        : null;
  }

  /**
   * Returns the collection that bounds a leaf.
   *
   * <p>A leaf named by a tuple of variables has a generator per component, each
   * bounded by a different constraint, so what bounds the leaf is their product
   * -- which is what the step list writes as several scans.
   */
  private Core.Exp bounded(
      Core.Exp leaf,
      Core.Pat pat,
      Generators.Cache cache,
      Map<Core.NamedPat, Core.Exp> bound) {
    final List<Core.NamedPat> names = pat.expand();
    if (names.size() == 1) {
      final @Nullable Generator generator = cache.bestGenerator(names.get(0));
      if (generator == null || !free(generator, bound).isEmpty()) {
        // Either nothing bounds this name, or what bounds it reads something
        // that is not bound yet -- `from x, y where x < y andalso y < x + 10`
        // bounds each by the other and neither on its own. The multi-name
        // path below asks the same question; without it here the generator's
        // free name reached the plan as a reference to nothing.
        throw new CompileException("pattern is not grounded", false, leaf.pos);
      }
      return named(
          project(generator, names.get(0), leaf.pos, bound), names.get(0));
    }
    Core.@Nullable Exp product = null;
    List<Core.Exp> access = new ArrayList<>();
    for (Core.NamedPat name : names) {
      final @Nullable Generator generator = cache.bestGenerator(name);
      if (generator == null || !free(generator, bound).isEmpty()) {
        // Either nothing bounds this component, or something that another
        // component binds does, which would need the product to be a
        // dependent join.
        throw new CompileException("pattern is not grounded", false, leaf.pos);
      }
      final Core.Exp component =
          named(project(generator, name, leaf.pos, bound), name);
      if (product == null) {
        product = component;
        access = new ArrayList<>();
        access.add(core.input0(component.type.elementType()));
        continue;
      }
      product =
          core.join(typeSystem, product, component, core.boolLiteral(true));
      final Core.Exp element = core.input0(product.type.elementType());
      final int n = core.componentCount(product);
      access = new ArrayList<>();
      for (int i = 0; i < n; i++) {
        access.add(core.field(typeSystem, element, i));
      }
    }
    return requireNonNull(product, "product");
  }

  /**
   * Replaces each leaf that is an infinite range with the finite range that the
   * conditions make of it.
   *
   * <p>The join's answer to what {@link #expand} does for a leaf that stands
   * alone. Two things it needs that the single-leaf case does not: the range's
   * own bound has to be written as a constraint, or FBBT has nothing to
   * propagate from; and the bound that comes back names the leaf, because a
   * join's condition speaks of its components and not of {@code $0}.
   */
  private void tightenRanges(Frame frame, List<Core.Exp> constraints) {
    final PairList<Core.Exp, Core.NamedPat> ranges = PairList.of();
    frame.leaves.forEach(
        (leaf, pat) -> {
          if (pat instanceof Core.NamedPat
              && RangePushdown.isInfiniteRange(leaf)) {
            ranges.add(leaf, (Core.NamedPat) pat);
          }
        });
    if (ranges.isEmpty()) {
      return;
    }
    final List<Core.Exp> augmented = new ArrayList<>(constraints);
    final Set<Core.NamedPat> pats = new LinkedHashSet<>();
    ranges.forEach(
        (leaf, pat) -> {
          pats.add(pat);
          final Core.@Nullable Exp implied =
              RangePushdown.impliedBound(typeSystem, leaf, core.id(pat));
          if (implied != null) {
            augmented.add(implied);
          }
        });
    final List<Core.Exp> strengthened =
        core.decomposeAnd(
            Fbbt.strengthen(
                typeSystem, pats, core.andAlso(typeSystem, augmented)));
    ranges.forEach(
        (leaf, pat) -> {
          final RangePushdown.@Nullable Tightening t =
              RangePushdown.tighten(
                  typeSystem, leaf, strengthened, e -> Bounds.isIdRef(e, pat));
          if (t != null) {
            tightened.put(leaf, t.newExp);
          }
        });
  }

  /**
   * Deduces tighter bounds for the leaves, as {@code expandFrom} does before
   * grounding a step list.
   *
   * <p>Without this, {@code from i : int where i > 0 andalso i < 10} does not
   * ground: the engine looks for a constraint that generates, and a pair of
   * comparisons only becomes one once Fbbt has turned them into a range.
   */
  private List<Core.Exp> strengthen(
      List<Core.Exp> constraints, PairList<Core.Pat, Core.Exp> extents) {
    if (constraints.isEmpty()) {
      return constraints;
    }
    final Set<Core.NamedPat> unbounded = new LinkedHashSet<>();
    extents.forEach((pat, exp) -> unbounded.addAll(pat.expand()));
    final Core.Exp strengthened =
        Fbbt.strengthen(
            typeSystem, unbounded, core.andAlso(typeSystem, constraints));
    // The conjuncts of the original survive as themselves, so what a
    // generator subsumes can still be matched by identity.
    return core.decomposeAnd(strengthened);
  }

  /** Projects a generator's collection down to one leaf's element. */
  private Core.Exp project(
      Generator generator,
      Core.NamedPat pat,
      Pos pos,
      Map<Core.NamedPat, Core.Exp> bound) {
    if (generator.cardinality == Generator.Cardinality.INFINITE) {
      throw new CompileException("pattern is not grounded", false, pos);
    }
    final Core.Exp exp = replace(generator.exp, bound);
    if (generator.pat instanceof Core.IdPat) {
      return dedup(generator, (Core.IdPat) generator.pat, exp);
    }
    final Core.@Nullable Exp element =
        path(generator.pat, core.input0(exp.type.elementType()), pat);
    if (element == null) {
      throw new CompileException("pattern is not grounded", false, pos);
    }
    // What else the generator's pattern binds, and where each stands. A name
    // the query already bound -- by an earlier leaf, whose value is in
    // `bound`, or by the scope around the query -- has to be *tested*, or the
    // rows that disagree with it come through: `from target where reachable
    // (source, target)` would count what is reachable from anywhere. A name
    // that nothing bound is the generator's own, from an `exists` inside the
    // constraint, and is projected away -- which can leave the same value
    // twice, so it is deduplicated. `Expander` draws the same line, by asking
    // whether a pattern is DONE or is not a scan pattern at all.
    final Map<Core.NamedPat, Core.Exp> tested = new LinkedHashMap<>();
    boolean projectsAway = false;
    boolean weakened = false;
    for (Core.NamedPat p : generator.pat.expand()) {
      if (p.equals(pat)) {
        continue;
      }
      final Core.@Nullable Exp value = bound.get(p);
      if (value != null) {
        tested.put(p, value);
      } else if (leafNames.contains(p)) {
        // Another leaf of this query binds it. Reading it here would need a
        // dependent join, which the schedule makes only when every leaf is an
        // extent, so the name is projected away and the generator produces
        // more rows than the constraint allows -- which is no matter while
        // the filter above still tests it, and every matter once a sealed
        // generator has taken it off. It has not earned that: it is not
        // enforcing the constraint if it is used like this.
        projectsAway = true;
        weakened = true;
      } else if (env.getOpt(p) != null) {
        // The scope around the query binds it, so it is in scope where the
        // generator is scanned and needs no join to reach.
        tested.put(p, core.id(p));
      } else {
        projectsAway = true;
      }
    }
    if (weakened) {
      // What this generator was thought to enforce, it does not. Keeping a
      // conjunct that is enforced is only wasted work; dropping one that is
      // not is a wrong answer.
      subsumed.clear();
      simplified.clear();
    }
    if (tested.isEmpty()
        && !projectsAway
        && RelBuilder.destructurable(generator.pat)) {
      // Nothing to test and nothing to drop, and the pattern cannot fail, so
      // reading the name out of each row says it all.
      return core.project(typeSystem, exp, element);
    }
    // Otherwise scan the pattern, which also filters where it can fail --
    // `(x, 20) elem [(1, 10), (2, 20)]` grounds `x` by a pattern holding a
    // literal, and a projection would read every row where the pattern
    // matches only some.
    final Map<Core.NamedPat, Core.IdPat> renames = new LinkedHashMap<>();
    final List<Core.Exp> conditions = new ArrayList<>();
    tested.forEach(
        (p, value) -> {
          final Core.IdPat fresh = freshPat(p);
          renames.put(p, fresh);
          conditions.add(core.equal(typeSystem, core.id(fresh), value));
        });
    final FromBuilder fromBuilder = core.fromBuilder(typeSystem);
    fromBuilder.scan(
        Expander.renamePatterns(typeSystem, generator.pat, renames),
        exp,
        core.andAlso(typeSystem, conditions));
    final Core.Exp yieldExp = core.id(pat);
    fromBuilder.yield_(yieldExp);
    if (dedupObservable && (projectsAway || !generator.unique)) {
      fromBuilder.distinct();
      fromBuilder.order(yieldExp);
    }
    return fromBuilder.build();
  }

  /**
   * Returns a name for a bound name's stand-in in a scan pattern, numbered so
   * that two scans in one scope do not both call it {@code p'}: a step's
   * bindings are keyed by name, and `Expander` never meets this because it
   * builds a subquery per generator.
   */
  private Core.IdPat freshPat(Core.NamedPat pat) {
    return core.idPat(pat.type, pat.name + "'" + nextName++, 0);
  }

  /**
   * Deduplicates a generator's collection where its duplicates would be
   * observable, as {@code Expander.expandFrom2} does.
   *
   * <p>A generator may produce a value more than once -- a collection may hold
   * duplicates, and a union of ranges may overlap -- but an unbounded scan
   * yields each assignment once. A query whose rows are only counted, and that
   * has no {@code take} or {@code skip}, does not need it: removing duplicate
   * rows cannot change whether there are any, nor how many there are when
   * nothing reads them.
   */
  private Core.Exp dedup(
      Generator generator, Core.IdPat pat, Core.Exp collection) {
    if (generator.unique || !dedupObservable) {
      return collection;
    }
    final FromBuilder fromBuilder = core.fromBuilder(typeSystem);
    fromBuilder.scan(pat, collection);
    fromBuilder.distinct();
    // An unbounded scan yields its values in the natural order of the
    // variable. Sort after the 'distinct', whose 'group' has no order of its
    // own, and before the 'yield', which rebinds the variable the sort names.
    fromBuilder.order(core.id(pat));
    fromBuilder.yield_(core.id(pat));
    return fromBuilder.build();
  }

  /**
   * What a join tree looks like from above: the expression that denotes its
   * element in terms of a name per leaf, the pattern that names each leaf, and
   * the conditions that its own joins impose.
   */
  private static class Frame {
    final Core.Exp element;
    final Map<Core.Exp, Core.Pat> leaves;
    final List<Core.Exp> constraints;

    /**
     * The conjunct a constraint came from, for the constraints contributed by a
     * filter inside the tree, so that the filter can drop what a sealed
     * generator has taken over. Identity, as everywhere here.
     */
    final Map<Core.Exp, Core.Exp> originals = new IdentityHashMap<>();

    /**
     * Element expression of every node of the tree, in terms of the names of
     * the leaves below it.
     */
    final Map<Core.Exp, Core.Exp> elements = new IdentityHashMap<>();

    /**
     * Extents to register and constraints to apply, in the order a walk of the
     * tree reaches them.
     *
     * <p>Not the extents and then the constraints: the engine improves its
     * generators after every constraint, so which extents are registered by
     * then decides what it settles on. A step list gives them interleaved, in
     * the order its steps are written, and a tree walked left to right gives
     * the same order.
     */
    final List<Expander.Ground> order = new ArrayList<>();

    Frame(
        Core.Exp element,
        Map<Core.Exp, Core.Pat> leaves,
        List<Core.Exp> constraints) {
      this.element = element;
      this.leaves = leaves;
      this.constraints = constraints;
    }
  }

  /**
   * Returns the collection that bounds a leaf that stands alone rather than
   * under a join.
   */
  private Core.Exp bound(Core.Exp leaf, List<Core.Exp> conditions) {
    try {
      return bound(leaf, conditions, false);
    } catch (CompileException e) {
      return bound(leaf, conditions, true);
    }
  }

  private Core.Exp bound(
      Core.Exp leaf, List<Core.Exp> conditions, boolean destructure) {
    this.destructure = destructure;
    final Core.Pat pat = elementPat(leaf);
    final Core.Exp element = patExp(pat);
    final Map<Core.Exp, Core.Exp> originals = new IdentityHashMap<>();
    final List<Core.Exp> constraints = new ArrayList<>();
    conditions.forEach(
        condition -> {
          final Core.Exp constraint = subst(condition, element);
          originals.put(constraint, condition);
          constraints.add(constraint);
        });
    final PairList<Core.Pat, Core.Exp> extents = PairList.of();
    extents.add(pat, leaf);
    final Generators.Cache cache = new Generators.Cache(typeSystem, env);
    Expander.ground(cache, extents, strengthen(constraints, extents));
    recordSubsumed(pat, cache, originals);
    leafNames = ImmutableSet.copyOf(pat.expand());
    return bounded(leaf, pat, cache, ImmutableMap.of());
  }

  /**
   * Remembers the conditions that a sealed generator enforces, so that the
   * filter they came from can drop them.
   */
  private void recordSubsumed(
      Core.Pat pat, Generators.Cache cache, Map<Core.Exp, Core.Exp> originals) {
    pat.expand()
        .forEach(
            name -> {
              final @Nullable Generator generator = cache.bestGenerator(name);
              if (generator == null) {
                return;
              }
              if (generator.sealed) {
                generator.provenance.forEach(
                    constraint -> {
                      final Core.Exp original = originals.get(constraint);
                      if (original != null) {
                        subsumed.add(original);
                      }
                    });
              }
              // What the generator makes of each condition it was given,
              // whether or not it is sealed.
              originals.forEach(
                  (constraint, original) -> {
                    final Core.Exp was =
                        simplified.getOrDefault(original, original);
                    final Core.Exp now =
                        generator.simplify(typeSystem, name, constraint);
                    if (now != constraint) {
                      simplified.put(original, now);
                    } else if (was != original) {
                      simplified.put(original, was);
                    }
                  });
            });
  }

  /**
   * Returns the expression that reads a pattern's binder out of an element, or
   * null if the pattern does not bind it.
   */
  private Core.@Nullable Exp path(
      Core.Pat pat, Core.Exp element, Core.NamedPat target) {
    if (pat instanceof Core.NamedPat) {
      return pat.equals(target) ? element : null;
    }
    final List<Core.Pat> args;
    if (pat instanceof Core.TuplePat) {
      args = ((Core.TuplePat) pat).args;
    } else if (pat instanceof Core.RecordPat) {
      args = ((Core.RecordPat) pat).args;
    } else {
      return null;
    }
    for (int i = 0; i < args.size(); i++) {
      final Core.@Nullable Exp exp =
          path(args.get(i), core.field(typeSystem, element, i), target);
      if (exp != null) {
        return exp;
      }
    }
    return null;
  }

  /**
   * Returns a generator for each infinite-extent leaf of a tree, or null for a
   * leaf that cannot be grounded.
   *
   * <p>The map is keyed by the leaf expression -- the {@code extent} call -- in
   * the order the leaves occur.
   */
  public static Map<Core.Exp, @Nullable Generator> ground(
      TypeSystem typeSystem, Environment env, Core.Exp tree) {
    final RelExpander expander = new RelExpander(typeSystem, env, true);
    final Map<Core.Exp, @Nullable Generator> generators = new LinkedHashMap<>();
    expander.ground(tree, ImmutableList.of(), generators);
    return generators;
  }

  /**
   * Walks a tree, carrying the conditions of the filters passed on the way
   * down, and grounds each infinite-extent leaf against them.
   */
  private void ground(
      Core.Exp exp,
      List<Core.Exp> conditions,
      Map<Core.Exp, @Nullable Generator> generators) {
    if (!(exp instanceof Core.Rel)) {
      if (exp.isExtent()) {
        generators.put(exp, ground(exp, conditions));
      }
      return;
    }
    if (exp instanceof Core.Filter) {
      final Core.Filter filter = (Core.Filter) exp;
      final List<Core.Exp> conditions2 =
          new ArrayList<>(core.decomposeAnd(filter.condition));
      conditions2.addAll(conditions);
      ground(filter.input, conditions2, generators);
      return;
    }
    if (exp instanceof Core.Project) {
      final Core.Project project = (Core.Project) exp;
      final List<Core.Exp> pushed = new ArrayList<>();
      conditions.forEach(
          condition -> pushed.add(subst(condition, project.exp)));
      ground(project.input, pushed, generators);
      return;
    }
    if (exp instanceof Core.Sort
        || exp instanceof Core.Unorder
        || exp instanceof Core.Skip
        || exp instanceof Core.Take) {
      // None of these changes the element, so the conditions still describe
      // it, as they do in the step list.
      ground(((Core.Rel) exp).inputs().get(0), conditions, generators);
      return;
    }
    // Any other node: its inputs are grounded, but the conditions collected
    // above do not describe their elements.
    ((Core.Rel) exp)
        .inputs()
        .forEach(input -> ground(input, ImmutableList.of(), generators));
  }

  /**
   * Grounds one leaf and returns the generator of its first name, for the
   * diagnostic walk.
   */
  private @Nullable Generator ground(Core.Exp leaf, List<Core.Exp> conditions) {
    final Core.Pat pat = elementPat(leaf);
    final Core.Exp element = patExp(pat);
    final List<Core.Exp> constraints = new ArrayList<>();
    conditions.forEach(condition -> constraints.add(subst(condition, element)));
    final PairList<Core.Pat, Core.Exp> extents = PairList.of();
    extents.add(pat, leaf);
    final Generators.Cache cache = new Generators.Cache(typeSystem, env);
    Expander.ground(cache, extents, strengthen(constraints, extents));
    return cache.bestGenerator(pat.expand().get(0));
  }

  /**
   * Creates the pattern that the engine keys on for a leaf's element.
   *
   * <p>A leaf whose element is a tuple is named by a tuple of variables, one
   * per component, because that is how a step list names it -- {@code from (b,
   * i) : bool * int} -- and it is what lets the engine ground the components
   * separately, from a different constraint each.
   */
  private Core.Pat elementPat(Core.Exp leaf) {
    if (!destructure && nextLeafPat < leafPats.size()) {
      final Core.Pat pat = leafPats.get(nextLeafPat++);
      if (pat.type.equals(leaf.type.elementType())) {
        return pat;
      }
      // The walk and the scans disagree about which leaf is which, so the
      // rest of the names are not to be trusted either.
      leafPats = ImmutableList.of();
    }
    return pat(leaf.type.elementType());
  }

  /** Records that a collection bounds a leaf, and returns the collection. */
  private Core.Exp named(Core.Exp collection, Core.NamedPat pat) {
    collectionNames.put(collection, pat.name);
    return collection;
  }

  private Core.Pat pat(Type type) {
    if (destructure && type instanceof TupleType) {
      final List<Core.Pat> args = new ArrayList<>();
      ((TupleType) type).argTypes.forEach(argType -> args.add(pat(argType)));
      return core.tuplePat(typeSystem, args);
    }
    return groundPat(type);
  }

  /**
   * Creates a binder for a leaf's element.
   *
   * <p>The name comes from the type system's generator, not from a counter of
   * this expander's own: grounding runs once per tree, a query has a tree for
   * each nested query in it, and two of them meet in one expression. Two
   * expanders that each numbered from zero would give the same name to
   * different things, and a record built over both loses a field -- {@code from
   * p where happy p} in such-that.smli names the outer leaf and one of the
   * inner ones {@code g$0}. Plan text stays deterministic because the printer
   * renumbers what it prints.
   */
  private Core.IdPat groundPat(Type type) {
    return core.idPat(type, typeSystem.nameGenerator.getPrefixed("g"), 0);
  }

  /**
   * Names the leaves of a join tree and works out what its element is in terms
   * of those names.
   */
  private Frame collect(Core.Exp node) {
    if (node instanceof Core.Filter) {
      // A filter between two joins constrains the leaves below it just as a
      // `where` between two scans constrains the patterns before it, so its
      // conjuncts join the tree's own constraints rather than the tree
      // stopping here and the leaves below going unconstrained.
      final Core.Filter filter = (Core.Filter) node;
      final Frame input = collect(filter.input);
      core.decomposeAnd(filter.condition)
          .forEach(
              conjunct -> {
                final Core.Exp constraint = subst(conjunct, input.element);
                input.originals.put(constraint, conjunct);
                input.constraints.add(constraint);
                input.order.add(new Expander.Ground(null, constraint));
              });
      input.elements.put(node, input.element);
      return input;
    }
    if (!(node instanceof Core.Join)) {
      final Core.Pat pat = elementPat(node);
      final Map<Core.Exp, Core.Pat> leaves = new IdentityHashMap<>();
      leaves.put(node, pat);
      final Frame frame = new Frame(patExp(pat), leaves, new ArrayList<>());
      frame.elements.put(node, frame.element);
      if (node.isExtent()) {
        frame.order.add(new Expander.Ground(pat, node));
      }
      return frame;
    }
    final Core.Join join = (Core.Join) node;
    final Frame left = collect(join.left);
    final Frame right = collect(join.right);
    final Map<Core.Exp, Core.Pat> leaves = new IdentityHashMap<>(left.leaves);
    leaves.putAll(right.leaves);
    final List<Core.Exp> constraints = new ArrayList<>(left.constraints);
    constraints.addAll(right.constraints);
    if (!join.condition.isBoolLiteral(true)) {
      constraints.add(subst(join.condition, left.element, right.element));
    }
    final List<Core.Exp> componentExps =
        new ArrayList<>(core.components(typeSystem, join.left, left.element));
    componentExps.addAll(
        core.components(typeSystem, join.right, right.element));
    final Frame frame =
        new Frame(
            core.tuple(typeSystem, null, componentExps), leaves, constraints);
    frame.elements.putAll(left.elements);
    frame.elements.putAll(right.elements);
    frame.elements.put(node, frame.element);
    frame.originals.putAll(left.originals);
    frame.originals.putAll(right.originals);
    frame.order.addAll(left.order);
    frame.order.addAll(right.order);
    if (!join.condition.isBoolLiteral(true)) {
      frame.order.add(new Expander.Ground(null, last(constraints)));
    }
    return frame;
  }

  /** Returns the expression that a pattern's variables denote. */
  private Core.Exp patExp(Core.Pat pat) {
    if (pat instanceof Core.TuplePat) {
      final List<Core.Exp> args = new ArrayList<>();
      ((Core.TuplePat) pat).args.forEach(arg -> args.add(patExp(arg)));
      return core.tuple(typeSystem, args.toArray(new Core.Exp[0]));
    }
    return core.id((Core.NamedPat) pat);
  }

  /**
   * Returns the names a generator reads that something else must bind.
   *
   * <p>{@code Generator.freePats} counts every name the expression mentions,
   * including constructors such as {@code OPEN} and globals; the environment
   * binds those already, and only the rest make a generator depend on another
   * leaf.
   */
  private List<Core.NamedPat> free(
      Generator generator, Map<Core.NamedPat, Core.Exp> bound) {
    final List<Core.NamedPat> free = new ArrayList<>();
    generator.freePats.forEach(
        pat -> {
          if (env.getOpt(pat) == null && !bound.containsKey(pat)) {
            free.add(pat);
          }
        });
    return free;
  }

  /**
   * Replaces {@code $0}, the element of the leaf, with a name the engine can
   * key on.
   */
  private Core.Exp subst(Core.Exp exp, Core.Exp element) {
    return subst(exp, element, null);
  }

  /**
   * Replaces {@code $0} and {@code $1} with expressions.
   *
   * <p>The walk stops at a nested node, whose {@code $0} is its own input's
   * element (spec.md §2 rule 3). What the enclosing node's element is called
   * inside a nested tree is a binder the resolver made for it, and once the
   * element is an ordinary name that binder has nothing left to protect: the
   * binding is dropped, so that the engine reads `Relational.nonEmpty (…)`
   * where it would otherwise read a `let`.
   */
  private Core.Exp subst(Core.Exp exp, Core.Exp e0, Core.@Nullable Exp e1) {
    final Set<Core.NamedPat> rowPats = RelLowerer.rowBindings(exp);
    final Core.Exp exp2 =
        exp.accept(
            new Shuttle(typeSystem) {
              @Override
              protected Core.@Nullable Exp visitRel(Core.Rel rel) {
                return rel;
              }

              @Override
              protected Core.Exp visit(Core.Input input) {
                if (input.i == 0) {
                  return core.at(e0, input.pos);
                }
                if (e1 != null && input.i == 1) {
                  return core.at(e1, input.pos);
                }
                return input;
              }
            });
    return simplify(RelLowerer.unbindRow(typeSystem, exp2, rowPats));
  }

  /**
   * Folds a field access applied to a record that is built right there.
   *
   * <p>Substituting a join's yield into a condition produces {@code #y {x = a,
   * y = b}}, and the engine looks for a reference to a variable, not for a
   * record it could have taken apart. Folding it to {@code b} is what lets the
   * engine see the constraint.
   */
  private Core.Exp simplify(Core.Exp exp) {
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
}

// End RelExpander.java
