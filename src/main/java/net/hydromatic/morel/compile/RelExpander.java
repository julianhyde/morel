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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Shuttle;
import net.hydromatic.morel.type.TypeSystem;
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
  private int nextName;

  private RelExpander(TypeSystem typeSystem, Environment env) {
    this.typeSystem = typeSystem;
    this.env = env;
  }

  /**
   * Replaces every infinite-extent leaf of a tree with a collection that bounds
   * it, and throws if there is none.
   *
   * <p>This is what {@link Expander#expandFrom} does for a step list.
   */
  public static Core.Exp expand(
      TypeSystem typeSystem, Environment env, Core.Exp tree) {
    return new RelExpander(typeSystem, env).expand(tree, ImmutableList.of());
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
      final List<Core.Exp> conditions2 = new ArrayList<>(conditions);
      conditions2.addAll(core.decomposeAnd(filter.condition));
      return filter.copy(expand(filter.input, conditions2), filter.condition);
    }
    if (exp instanceof Core.Project) {
      // A projection changes what $0 means. A condition above it could be
      // pushed through by substitution, but the step list does not do that
      // either -- `from x yield {y = x} where y elem [2, 3]` is not grounded
      // today -- so the conditions stop here, for parity.
      final Core.Project project = (Core.Project) exp;
      return project.copy(
          typeSystem, expand(project.input, ImmutableList.of()), project.exp);
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
      final Core.Join join = (Core.Join) exp;
      return join.copy(
          typeSystem,
          join.joinType,
          expand(join.left, ImmutableList.of()),
          expand(join.right, ImmutableList.of()),
          join.condition,
          join.yieldExp);
    }
    // A node whose element does not come from a leaf below it in a way this
    // pass understands: leave its inputs alone.
    return exp;
  }

  /**
   * Returns the collection that bounds an infinite-extent leaf.
   *
   * <p>If the generator binds a pattern other than the element itself -- it
   * bounds a tuple of which the element is one component -- the collection is
   * projected down to the element.
   */
  private Core.Exp bound(Core.Exp leaf, List<Core.Exp> conditions) {
    final Core.IdPat pat = elementPat(leaf);
    final Generator generator = ground(leaf, conditions, pat);
    if (generator == null
        || generator.cardinality == Generator.Cardinality.INFINITE) {
      // The step list names the pattern here -- "pattern 'b' is not
      // grounded" -- and the tree has erased the name. The position has not
      // been erased, and points at what the user wrote, so the message says
      // the same thing without the name. See discussion.md section 11.
      throw new CompileException("pattern is not grounded", false, leaf.pos);
    }
    if (!generator.freePats.isEmpty()) {
      // A generator that reads other variables needs them bound first, which
      // is a dependent scan; not yet.
      throw new CompileException(
          "pattern is not grounded until " + generator.freePats + " is bound",
          false,
          leaf.pos);
    }
    if (generator.exp.type.elementType().equals(leaf.type.elementType())
        && generator.pat instanceof Core.IdPat) {
      return generator.exp;
    }
    final Core.Exp element =
        path(generator.pat, core.input0(generator.exp.type.elementType()), pat);
    if (element == null) {
      throw new CompileException("pattern is not grounded", false, leaf.pos);
    }
    return core.project(typeSystem, generator.exp, element);
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
    if (pat instanceof Core.TuplePat) {
      final List<Core.Pat> args = ((Core.TuplePat) pat).args;
      for (int i = 0; i < args.size(); i++) {
        final Core.@Nullable Exp exp =
            path(args.get(i), core.field(typeSystem, element, i), target);
        if (exp != null) {
          return exp;
        }
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
    final RelExpander expander = new RelExpander(typeSystem, env);
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
   * Grounds one leaf: names its element, rewrites the conditions in terms of
   * that name, and asks the engine.
   */
  private @Nullable Generator ground(Core.Exp leaf, List<Core.Exp> conditions) {
    return ground(leaf, conditions, elementPat(leaf));
  }

  /** Creates the name that the engine keys on for a leaf's element. */
  private Core.IdPat elementPat(Core.Exp leaf) {
    return core.idPat(leaf.type.elementType(), "g$" + nextName++, 0);
  }

  private @Nullable Generator ground(
      Core.Exp leaf, List<Core.Exp> conditions, Core.IdPat pat) {
    final List<Core.Exp> constraints = new ArrayList<>();
    conditions.forEach(
        condition -> constraints.add(subst(condition, core.id(pat))));
    final Generators.Cache cache = new Generators.Cache(typeSystem, env);
    Expander.ground(cache, pat, leaf, constraints);
    return cache.bestGenerator(pat);
  }

  /**
   * Replaces {@code $0}, the element of the leaf, with a name the engine can
   * key on.
   */
  private Core.Exp subst(Core.Exp exp, Core.Exp element) {
    return exp.accept(
        new Shuttle(typeSystem) {
          @Override
          protected Core.Exp visit(Core.Id id) {
            return id.idPat.name.equals("$0") ? element : id;
          }
        });
  }
}

// End RelExpander.java
