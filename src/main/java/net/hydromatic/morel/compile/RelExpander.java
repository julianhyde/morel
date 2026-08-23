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

import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.ast.Shuttle;
import net.hydromatic.morel.ast.Visitor;
import net.hydromatic.morel.type.TupleType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.PairList;
import org.checkerframework.checker.nullness.qual.Nullable;

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
   * Conditions that a sealed generator subsumes, and that the filter they came
   * from can therefore drop. Identity, as in {@code Expander}: the same
   * expression written twice is not the same constraint.
   */
  private final Set<Core.Exp> subsumed =
      Collections.newSetFromMap(new IdentityHashMap<>());

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
    return new RelExpander(typeSystem, env, rowsUsed)
        .expand(tree, ImmutableList.of());
  }

  /**
   * Rewrites a node, carrying the conditions of the filters passed on the way
   * down, and replacing each infinite-extent leaf with what grounds it.
   */
  private Core.Exp expand(Core.Exp exp, List<Core.Exp> conditions) {
    if (!(exp instanceof Core.Rel)) {
      return exp.isExtent() ? bound(exp, conditions) : exp;
    }
    if (exp instanceof Core.Filter) {
      final Core.Filter filter = (Core.Filter) exp;
      final List<Core.Exp> conjuncts = core.decomposeAnd(filter.condition);
      final List<Core.Exp> conditions2 = new ArrayList<>(conditions);
      conditions2.addAll(conjuncts);
      final Core.Exp input = expand(filter.input, conditions2);
      // A conjunct that a sealed generator subsumes is now enforced by the
      // collection that replaced the leaf, so the filter need not test it
      // again; if that was all it tested, the filter goes.
      final List<Core.Exp> remaining = new ArrayList<>();
      conjuncts.forEach(
          conjunct -> {
            if (!subsumed.contains(conjunct)) {
              remaining.add(conjunct);
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
      return project.copy(
          typeSystem, expand(project.input, pushed), project.exp);
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
    if (exp instanceof Core.ProjectMany) {
      final Core.ProjectMany projectMany = (Core.ProjectMany) exp;
      return projectMany.copy(
          typeSystem,
          expand(projectMany.input, ImmutableList.of()),
          projectMany.param,
          expand(projectMany.body, ImmutableList.of()));
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
    try {
      return expandJoinTree(join, conditions, false);
    } catch (CompileException e) {
      return expandJoinTree(join, conditions, true);
    }
  }

  private Core.Exp expandJoinTree(
      Core.Join join, List<Core.Exp> conditions, boolean destructure) {
    this.destructure = destructure;
    final Frame frame = collect(join);
    final List<Core.Exp> constraints = new ArrayList<>(frame.constraints);
    final Map<Core.Exp, Core.Exp> originals =
        new IdentityHashMap<>(frame.originals);
    conditions.forEach(
        condition -> {
          final Core.Exp constraint = subst(condition, frame.element);
          originals.put(constraint, condition);
          constraints.add(constraint);
        });
    final PairList<Core.Pat, Core.Exp> extents = PairList.of();
    frame.leaves.forEach(
        (leaf, pat) -> {
          if (leaf.isExtent()) {
            extents.add(pat, leaf);
          }
        });
    if (extents.isEmpty()) {
      return join.copy(
          typeSystem,
          join.joinType,
          expand(join.left, ImmutableList.of()),
          expand(join.right, ImmutableList.of()),
          join.condition,
          join.yieldExp);
    }
    final Generators.Cache cache = new Generators.Cache(typeSystem, env);
    Expander.ground(cache, extents, strengthen(constraints, extents));
    frame.leaves.forEach(
        (leaf, pat) -> {
          if (leaf.isExtent()) {
            pat.expand()
                .forEach(
                    name -> {
                      final Generator generator = cache.bestGenerator(name);
                      if (generator != null && generator.sealed) {
                        generator.provenance.forEach(
                            constraint -> {
                              final Core.Exp original =
                                  originals.get(constraint);
                              if (original != null) {
                                subsumed.add(original);
                              }
                            });
                      }
                    });
          }
        });
    return rebuild(join, frame, cache);
  }

  /**
   * Rebuilds a join tree with each extent leaf replaced by the collection that
   * bounds it. A leaf whose generator reads another leaf's element makes the
   * join a {@code projectMany}, whose lambda binds what it reads.
   */
  private Core.Exp rebuild(Core.Exp node, Frame frame, Generators.Cache cache) {
    if (node instanceof Core.Filter) {
      // The filter's conjuncts were grounded with the rest of the tree's
      // constraints, so what a sealed generator now enforces comes out, and
      // the filter goes if that was all of it.
      final Core.Filter filter = (Core.Filter) node;
      final Core.Exp input = rebuild(filter.input, frame, cache);
      final List<Core.Exp> remaining = new ArrayList<>();
      core.decomposeAnd(filter.condition)
          .forEach(
              conjunct -> {
                if (!subsumed.contains(conjunct)) {
                  remaining.add(conjunct);
                }
              });
      if (remaining.isEmpty()) {
        return input;
      }
      return filter.copy(input, core.andAlso(typeSystem, remaining));
    }
    if (!(node instanceof Core.Join)) {
      if (!node.isExtent()) {
        return node instanceof Core.Rel
            ? expand(node, ImmutableList.of())
            : node;
      }
      return bounded(node, frame.leaves.get(node), cache);
    }
    final Core.Join join = (Core.Join) node;
    if (!rowsUsed) {
      // Nothing looks at the rows, so a side that nothing constrains cannot
      // affect the answer, and need not be enumerated.
      if (droppable(join.right, frame, cache)) {
        return rebuild(join.left, frame, cache);
      }
      if (droppable(join.left, frame, cache)) {
        return rebuild(join.right, frame, cache);
      }
    }
    final @Nullable Generator common = commonGenerator(join, frame, cache);
    if (common != null) {
      // One generator binds the names of every leaf under this join -- `where
      // {deptno = dno, dname = name} elem depts` binds both -- so the leaves
      // and the join between them become one scan of that generator, read
      // through the paths that its pattern gives each name. Replacing them
      // separately would enumerate the collection once per leaf and pair
      // every value with every other.
      final Core.Exp element =
          rename(
              frame.elements.get(join),
              core.input0(common.exp.type.elementType()),
              common.pat);
      return core.project(typeSystem, common.exp, element);
    }
    final Core.Exp right = join.right;
    final @Nullable Generator rightGenerator =
        right.isExtent() ? generator(frame.leaves.get(right), cache) : null;
    if (rightGenerator != null && !free(rightGenerator).isEmpty()) {
      // Correlated: the right side reads names that the left side binds. The
      // join becomes a `projectMany` whose lambda binds the left element, and
      // each name the generator reads becomes the path that reads it out of
      // that element. The left side need not be a leaf: `from x, y where edge
      // (x, y) join y2 where y2 = y` reads `y` out of a pair.
      final Core.Exp leftElement = frame.elements.get(join.left);
      final Core.IdPat param =
          core.idPat(join.left.type.elementType(), "g$" + nextName++, 0);
      final Map<Core.NamedPat, Core.Exp> paths = new LinkedHashMap<>();
      for (Core.NamedPat name : free(rightGenerator)) {
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
        // The `projectMany` has nowhere to put the join's own condition.
        throw new CompileException("pattern is not grounded", false, right.pos);
      }
      final Core.Exp collection = replace(rightGenerator.exp, paths);
      final Core.Exp body =
          core.project(
              typeSystem,
              collection,
              subst(
                  join.yieldExp,
                  core.id(param),
                  core.input0(collection.type.elementType())));
      return core.projectMany(
          typeSystem, rebuild(join.left, frame, cache), param, body);
    }
    return join.copy(
        typeSystem,
        join.joinType,
        rebuild(join.left, frame, cache),
        rebuild(right, frame, cache),
        join.condition,
        join.yieldExp);
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
      Core.Exp node, Frame frame, Generators.Cache cache) {
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
            || !free(generator).isEmpty()) {
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
      for (Core.NamedPat name : frame.leaves.get(node).expand()) {
        if (cache.bestGenerator(name) != null
            && cache.bestGenerator(name).cardinality
                != Generator.Cardinality.INFINITE) {
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
      Core.Exp leaf, Core.Pat pat, Generators.Cache cache) {
    final List<Core.NamedPat> names = pat.expand();
    if (names.size() == 1) {
      final @Nullable Generator generator = cache.bestGenerator(names.get(0));
      if (generator == null) {
        throw new CompileException("pattern is not grounded", false, leaf.pos);
      }
      return project(generator, names.get(0), leaf.pos);
    }
    Core.@Nullable Exp product = null;
    List<Core.Exp> access = new ArrayList<>();
    for (Core.NamedPat name : names) {
      final @Nullable Generator generator = cache.bestGenerator(name);
      if (generator == null || !free(generator).isEmpty()) {
        // Either nothing bounds this component, or something that another
        // component binds does, which would need the product to be a
        // dependent join.
        throw new CompileException("pattern is not grounded", false, leaf.pos);
      }
      final Core.Exp component = project(generator, name, leaf.pos);
      if (product == null) {
        product = component;
        access = new ArrayList<>();
        access.add(core.input0(component.type.elementType()));
        continue;
      }
      final List<Core.Exp> args = new ArrayList<>(access);
      args.add(core.input1(component.type.elementType()));
      product =
          core.join(
              typeSystem,
              product,
              component,
              core.boolLiteral(true),
              core.tuple(typeSystem, args.toArray(new Core.Exp[0])));
      final Core.Exp element = core.input0(product.type.elementType());
      access = new ArrayList<>();
      for (int i = 0; i < args.size(); i++) {
        access.add(core.field(typeSystem, element, i));
      }
    }
    return requireNonNull(product, "product");
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
  private Core.Exp project(Generator generator, Core.NamedPat pat, Pos pos) {
    if (generator.cardinality == Generator.Cardinality.INFINITE) {
      throw new CompileException("pattern is not grounded", false, pos);
    }
    if (generator.pat instanceof Core.IdPat) {
      return generator.exp;
    }
    final Core.@Nullable Exp element =
        path(generator.pat, core.input0(generator.exp.type.elementType()), pat);
    if (element == null) {
      throw new CompileException("pattern is not grounded", false, pos);
    }
    return core.project(typeSystem, generator.exp, element);
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
    return bounded(leaf, pat, cache);
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
              if (generator != null && generator.sealed) {
                generator.provenance.forEach(
                    constraint -> {
                      final Core.Exp original = originals.get(constraint);
                      if (original != null) {
                        subsumed.add(original);
                      }
                    });
              }
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
      final List<Core.Exp> conditions2 = new ArrayList<>(conditions);
      conditions2.addAll(core.decomposeAnd(filter.condition));
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
    return pat(leaf.type.elementType());
  }

  private Core.Pat pat(Type type) {
    if (destructure && type instanceof TupleType) {
      final List<Core.Pat> args = new ArrayList<>();
      ((TupleType) type).argTypes.forEach(argType -> args.add(pat(argType)));
      return core.tuplePat(typeSystem, args);
    }
    return core.idPat(type, "g$" + nextName++, 0);
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
    final Frame frame =
        new Frame(
            subst(join.yieldExp, left.element, right.element),
            leaves,
            constraints);
    frame.elements.putAll(left.elements);
    frame.elements.putAll(right.elements);
    frame.elements.put(node, frame.element);
    frame.originals.putAll(left.originals);
    frame.originals.putAll(right.originals);
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
  private List<Core.NamedPat> free(Generator generator) {
    final List<Core.NamedPat> free = new ArrayList<>();
    generator.freePats.forEach(
        pat -> {
          if (env.getOpt(pat) == null) {
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

  /** Replaces {@code $0} and {@code $1} with expressions. */
  private Core.Exp subst(Core.Exp exp, Core.Exp e0, Core.@Nullable Exp e1) {
    final Core.Exp exp2 =
        exp.accept(
            new Shuttle(typeSystem) {
              @Override
              protected Core.Exp visit(Core.Id id) {
                if (id.idPat.name.equals("$0")) {
                  return e0;
                }
                if (e1 != null && id.idPat.name.equals("$1")) {
                  return e1;
                }
                return id;
              }
            });
    return simplify(exp2);
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
                  && apply2.arg.op == Op.TUPLE) {
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
