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

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static net.hydromatic.morel.ast.CoreBuilder.core;
import static net.hydromatic.morel.compile.Generators.maybeGenerator;
import static net.hydromatic.morel.util.Static.append;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Visitor;
import net.hydromatic.morel.type.ListType;
import net.hydromatic.morel.type.RecordLikeType;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.PairList;
import org.jspecify.annotations.Nullable;

/** Expands generators. */
public class Expander {
  private final Generators.Cache cache;
  private final List<Core.Exp> constraints;

  private Expander(Generators.Cache cache, List<Core.Exp> constraints) {
    this.cache = cache;
    this.constraints = constraints;
  }

  /**
   * Converts all unbounded variables in a query to bounded, introducing
   * generators by inverting predicates.
   *
   * <p>Grounding is the tree's, and this is its front end: translate, expand,
   * lower. Returns {@code from} unchanged where no expansion is required, and
   * also where the tree declines -- an extent that survives is reported by a
   * later pass, which is what {@link SuchThatShuttle} is for.
   */
  public static Core.From expandFrom(
      TypeSystem typeSystem,
      NameGenerator nameGenerator,
      Environment env,
      Core.From from,
      boolean rowsUsed) {
    final Core.@Nullable From from2 =
        expandViaTree(typeSystem, nameGenerator, env, from, rowsUsed);
    if (from2 == null) {
      return from;
    }
    RelShadow.groundedViaTree();
    return from2;
  }

  /**
   * Grounds a query by translating it to a relational tree, expanding that, and
   * lowering it back.
   *
   * <p>Returns null where the translator declines, or where the expansion does
   * not lower to a step list, so that the caller hands the query back
   * unchanged.
   */
  private static Core.@Nullable From expandViaTree(
      TypeSystem typeSystem,
      NameGenerator nameGenerator,
      Environment env,
      Core.From from,
      boolean rowsUsed) {
    if (!containsExtent(from)) {
      // Nothing to ground. A round trip through the tree would return an
      // equal query that is not the same object -- which the fixed-point loop
      // above reads as progress.
      return null;
    }
    final Core.@Nullable Exp tree = RelTranslator.toRel(typeSystem, from);
    if (tree == null) {
      return null;
    }
    // The patterns the query was written with, in scan order, so that the
    // leaves keep the names the user gave them.
    final List<Core.Pat> leafPats = new ArrayList<>();
    from.steps.forEach(
        step -> {
          if (step instanceof Core.Scan) {
            leafPats.add(((Core.Scan) step).pat);
          }
        });
    // The leaf that each collection grounding builds bounds, so that the
    // lowering can name each scan after the variable it scans.
    final Map<Core.Exp, String> leafNames = new IdentityHashMap<>();
    final Core.Exp expanded;
    try {
      expanded =
          RelExpander.expand(
              typeSystem, env, tree, rowsUsed, leafPats, leafNames);
    } catch (CompileException e) {
      // Hand the query back unchanged rather than report here. The pattern is
      // still unbounded, so `SuchThatShuttle` reaches it and says so, at the
      // position the query was written with.
      return null;
    }
    // The same names again, for the binders the lowering invents. Less
    // reliable than naming the leaves was -- grounding may have reordered
    // them, and the lowering takes them positionally -- but a name that ends
    // up on the wrong scan is still unique, and the common case is that they
    // are in the order they were written.
    final List<String> scanNames = new ArrayList<>();
    leafPats.forEach(
        pat -> {
          if (pat instanceof Core.IdPat) {
            scanNames.add(((Core.IdPat) pat).name);
          }
        });
    final Core.Exp lowered =
        RelLowerer.lower(
            typeSystem,
            nameGenerator,
            expanded,
            scanNames.size() == leafPats.size()
                ? Lists.transform(scanNames, ImmutableList::of)
                : ImmutableList.of(),
            true,
            leafNames);
    if (misaddressed(lowered)) {
      // A selector reading a field the row does not have. Replacing a join
      // with a projection makes the element one component where it was
      // several, and what reads it above was written for
      // the other shape; `rebuild` does not rebase them. Hand the query back
      // until it does.
      return null;
    }
    if (!(lowered instanceof Core.From) || containsExtent(lowered)) {
      // An extent that survives is one the walk did not reach or could not
      // bound -- including one inside a nested query, which this walk does not
      // ground. Handing it back would put an infinite collection in a plan,
      // which fails when the query runs rather than when it compiles.
      return null;
    }
    if (System.getenv("MOREL_DEBUG") != null) {
      System.err.println("VT " + lowered); // lint:skip
    }
    return (Core.From) lowered;
  }

  /**
   * Returns whether an expression reads a field that its row does not have.
   *
   * <p>Cheap, and it catches the shape exactly: a selector carries the slot it
   * was made for, and a substitution can put under it a row of fewer fields.
   */
  static boolean misaddressed(Core.Exp exp) {
    final boolean[] found = {false};
    exp.accept(
        new Visitor() {
          @Override
          protected void visit(Core.Apply apply) {
            super.visit(apply);
            if (apply.fn instanceof Core.RecordSelector
                && apply.arg.type instanceof RecordLikeType
                && ((Core.RecordSelector) apply.fn).slot
                    >= ((RecordLikeType) apply.arg.type)
                        .argNameTypes()
                        .size()) {
              found[0] = true;
            }
          }
        });
    return found[0];
  }

  /**
   * Returns whether a tree still has a leaf that cannot be enumerated.
   *
   * <p>Every scan counts, nested queries included: this asks whether the result
   * is safe to hand back, and one infinite extent anywhere in it is not. The
   * step list reaches a nested query later and grounds it then, so declining
   * here costs only that this query takes the other path.
   */
  private static boolean containsExtent(Core.Exp exp) {
    final boolean[] found = {false};
    exp.accept(
        new Visitor() {
          @Override
          protected void visit(Core.Scan scan) {
            super.visit(scan);
            if (Extents.isInfinite(scan.exp)) {
              found[0] = true;
            }
          }
        });
    return found[0];
  }

  /**
   * Returns the message for a pattern that nothing bounds.
   *
   * <p>A binder the compiler generated names nothing the user wrote -- under a
   * tree the query's own names do not reach the lowering -- so the message
   * leaves it out. A diagnostic that confidently names the wrong variable is
   * worse than one that names none
   */
  static String notGrounded(Core.NamedPat pat) {
    return Core.NamedPat.isGenerated(pat.name)
        ? "pattern is not grounded"
        : format("pattern '%s' is not grounded", pat.name);
  }

  /**
   * Renames patterns in a pattern tree according to the given map.
   *
   * <p>For example, if the map is {@code {y -> y$}}, then the pattern (x, y, z)
   * becomes (x, y$, z).
   */
  static Core.Pat renamePatterns(
      TypeSystem typeSystem,
      Core.Pat pat,
      Map<Core.NamedPat, Core.IdPat> renameMap) {
    if (pat instanceof Core.IdPat) {
      final Core.IdPat replacement = renameMap.get(pat);
      return replacement != null ? replacement : pat;
    } else if (pat instanceof Core.TuplePat) {
      final Core.TuplePat tuplePat = (Core.TuplePat) pat;
      final List<Core.Pat> args = new ArrayList<>(tuplePat.args.size());
      boolean changed = false;
      for (Core.Pat arg : tuplePat.args) {
        final Core.Pat newArg = renamePatterns(typeSystem, arg, renameMap);
        args.add(newArg);
        if (newArg != arg) {
          changed = true;
        }
      }
      return changed ? tuplePat.copy(typeSystem, args) : pat;
    } else if (pat instanceof Core.RecordPat) {
      final Core.RecordPat recordPat = (Core.RecordPat) pat;
      final List<Core.Pat> args = new ArrayList<>(recordPat.args.size());
      boolean changed = false;
      for (Core.Pat arg : recordPat.args) {
        final Core.Pat newArg = renamePatterns(typeSystem, arg, renameMap);
        args.add(newArg);
        if (newArg != arg) {
          changed = true;
        }
      }
      return changed ? core.recordPat(recordPat.type(), args) : pat;
    } else {
      return pat;
    }
  }

  /**
   * Grounds one pattern: registers the extent it scans, applies the constraints
   * that bound it, and returns the best generator, or null if there is none.
   *
   * <p>Expressed without steps, so that a relational tree can use it: a leaf
   * that is an infinite extent, and the conditions of the filters above it. See
   * {@link RelExpander}.
   */
  static Generators.Cache ground(
      Generators.Cache cache,
      Core.Pat pat,
      Core.Exp extentExp,
      List<Core.Exp> constraints) {
    return ground(cache, PairList.of(pat, extentExp), constraints);
  }

  /**
   * Grounds several patterns at once: every extent is registered before any
   * constraint is applied, so that a constraint tying two variables together
   * can generate for both.
   */
  static Generators.Cache ground(
      Generators.Cache cache,
      PairList<Core.Pat, Core.Exp> extents,
      List<Core.Exp> constraints) {
    final List<Ground> steps = new ArrayList<>();
    extents.forEach((pat, exp) -> steps.add(new Ground(pat, exp)));
    constraints.forEach(constraint -> steps.add(new Ground(null, constraint)));
    return ground(cache, steps);
  }

  /**
   * Registers extents and applies constraints in the order given.
   *
   * <p>The order is part of the answer, not a detail: the engine improves its
   * generators after every constraint, so a constraint sees the extents
   * registered before it and not the ones after. A tree walked left to right
   * gives them in the order the query's steps are written -- {@code from i
   * where A join b where B} is extent, A, extent, B.
   */
  static Generators.Cache ground(Generators.Cache cache, List<Ground> steps) {
    Expander expander = new Expander(cache, ImmutableList.of());
    for (Ground step : steps) {
      if (step.pat != null) {
        Generators.maybeExtent(cache, step.pat, step.exp);
      } else {
        expander = expander.plusConstraint(step.exp);
        expander.improveGenerators(cache.generators);
      }
    }
    return cache;
  }

  /** One step of grounding: an extent to register, or a constraint to apply. */
  static class Ground {
    final Core.@Nullable Pat pat;
    final Core.Exp exp;

    Ground(Core.@Nullable Pat pat, Core.Exp exp) {
      this.pat = pat;
      this.exp = exp;
    }
  }

  /**
   * Tries to improve the existing generators.
   *
   * <p>This means replacing each generator with one of lower cardinality - an
   * infinite generator with a finite generator, or a finite generator with one
   * that is a single value or is empty.
   */
  private void improveGenerators(
      PairList<Core.NamedPat, Generator> generators) {
    // Create a snapshot of the generators map, to avoid concurrent
    // modification.
    final PairList<Core.NamedPat, Generator> infiniteGenerators = PairList.of();
    generators.forEach(
        (pat, generator) -> {
          if (generator.cardinality == Generator.Cardinality.INFINITE) {
            infiniteGenerators.add(pat, generator);
          }
        });

    infiniteGenerators.forEach(
        (pat, generator) -> {
          final boolean ordered = generator.exp.type instanceof ListType;
          if (maybeGenerator(
              cache, pat, ordered, new Generators.Context(constraints))) {
            final Generator g = requireNonNull(cache.bestGenerator(pat));
            g.pat.expand().forEach(p2 -> generators.add(p2, g));
          }
        });
  }

  private Expander plusConstraint(Core.Exp constraint) {
    return withConstraints(append(this.constraints, constraint));
  }

  private Expander withConstraints(List<Core.Exp> constraints) {
    return new Expander(cache, constraints);
  }
}

// End Expander.java
