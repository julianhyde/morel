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
import static net.hydromatic.morel.util.Static.allMatch;
import static net.hydromatic.morel.util.Static.skip;
import static org.apache.calcite.util.Util.minus;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableRangeSet;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Range;
import com.google.common.collect.RangeSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.FromBuilder;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.ast.Shuttle;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.ImmutablePairList;
import net.hydromatic.morel.util.Ord;
import net.hydromatic.morel.util.Pair;
import net.hydromatic.morel.util.PairList;
import net.hydromatic.morel.util.TriConsumer;

/**
 * Generates an expression for the set of values that a variable can take in a
 * program.
 *
 * <p>If {@code i} is a variable of type {@code int} then one approximation is
 * the set of all 2<sup>32</sup> values of the {@code int} data type. (Every
 * data type, primitive data types and those built using sum ({@code datatype})
 * or product (record and tuple), has a finite set of values, but the set is
 * usually too large to iterate over.)
 *
 * <p>There is often a better approximation that can be deduced from the uses of
 * the variable. For example,
 *
 * <pre>{@code
 * let
 *   fun isOdd i = i % 2 = 0
 * in
 *   from e in emps,
 *       i
 *     where isOdd i
 *       andalso i < 100
 *       andalso i = e.deptno
 * end
 * }</pre>
 *
 * <p>we can deduce a better extent for {@code i}, namely
 *
 * <pre>{@code
 * from e in emps
 *   yield e.deptno
 *   where deptno % 2 = 0
 *     andalso deptno < 100
 * }</pre>
 */
public class Extents {
  private Extents() {}

  /**
   * Analyzes the extent of a pattern in an expression and creates an {@link
   * Analysis}.
   *
   * <p>For example, given the program
   *
   * <pre>{@code
   * let
   *   fun f i = i elem [1, 2, 4]
   * in
   *   from x where f x
   * end
   * }</pre>
   *
   * <p>we can deduce that the extent of "x" is "[1, 2, 4]".
   *
   * <p>We can also compute the extent of tuples. For the program
   *
   * <pre>{@code
   * let
   *   val edges = [(1, 2), (2, 3), (1, 4), (4, 2), (4, 3)]
   *   fun edge (i, j) = (i, j) elem edges
   * in
   *   from x, y, z
   *   where edge (x, y) andalso edge (y, z) andalso x <> z
   * end
   * }</pre>
   *
   * <p>we could deduce that "x" has extent "from e in edges group e.i", "y" has
   * extent "from e in edges group e.j" ("from e in edges group e.i" is also
   * valid), "z" has extent "from e in edges group e.j", and therefore "(x, y,
   * z)" has extent
   *
   * <pre>{@code
   * from x in (from e in edges group e.i),
   *   y in (from e in edges group e.j),
   *   z in (from e in edges group e.j)
   * }</pre>
   *
   * <p>but we can do better by computing the extent of (x, y) simultaneously:
   *
   * <pre>{@code
   * from (x, y) in (from e in edges),
   *   z in (from e in edges group e.j)
   * }</pre>
   */
  public static Analysis create(
      TypeSystem typeSystem,
      Environment env,
      boolean invert,
      Core.Pat pat,
      SortedMap<Core.NamedPat, Core.Exp> boundPats,
      Iterable<? extends Core.FromStep> followingSteps,
      PairList<Core.IdPat, Core.Exp> idPats) {
    final Extent extent =
        new Extent(typeSystem, env, invert, pat, boundPats, idPats);
    final List<Core.Exp> remainingFilters = new ArrayList<>();

    final ExtentMap map = new ExtentMap();
    for (Core.FromStep step : followingSteps) {
      if (step instanceof Core.Where) {
        extent.g3(map.map, ((Core.Where) step).exp);
      }
    }
    extent.definitions.forEach(
        (namedPat, exp) -> {
          // Is this expression better than the existing one?
          // Yes, if there's no existing expression,
          // or if the existing expression is infinite.
          // For example, 'dno = v.deptno' is better than 'dno > 25'.
          if (!map.map.containsKey(namedPat)
              || Extents.isInfinite(map.map.get(namedPat).left(0))) {
            map.map.put(
                namedPat,
                ImmutablePairList.of(
                    core.list(typeSystem, exp),
                    core.equal(typeSystem, core.id(namedPat), exp)));
          }
        });
    final PairList<Core.Exp, Core.Exp> foo = map.get(typeSystem, pat);
    final Pair<Core.Exp, Core.Exp> extentFilter;
    if (foo.isEmpty()) {
      extentFilter =
          Pair.of(
              core.extent(
                  typeSystem, pat.type, ImmutableRangeSet.of(Range.all())),
              core.boolLiteral(true));
    } else {
      extentFilter = reduceAnd(typeSystem, foo);
    }
    return new Analysis(
        boundPats,
        extent.goalPats,
        extentFilter.left,
        core.decomposeAnd(extentFilter.right),
        remainingFilters);
  }

  /**
   * Converts a singleton id pattern "x" or tuple pattern "(x, y)" to a list of
   * id patterns.
   */
  private static List<Core.IdPat> flatten(Core.Pat pat) {
    switch (pat.op) {
      case ID_PAT:
        return ImmutableList.of((Core.IdPat) pat);

      case TUPLE_PAT:
        final Core.TuplePat tuplePat = (Core.TuplePat) pat;
        for (Core.Pat arg : tuplePat.args) {
          if (arg.op != Op.ID_PAT) {
            throw new CompileException("must be id", false, arg.pos);
          }
        }
        //noinspection unchecked,rawtypes
        return (List) tuplePat.args;

      case RECORD_PAT:
        final Core.RecordPat recordPat = (Core.RecordPat) pat;
        for (Core.Pat arg : recordPat.args) {
          if (arg.op != Op.ID_PAT) {
            throw new CompileException("must be id", false, arg.pos);
          }
        }
        //noinspection unchecked,rawtypes
        return (List) recordPat.args;

      default:
        throw new CompileException("must be id", false, pat.pos);
    }
  }

  /** Returns whether an expression is an infinite extent. */
  public static boolean isInfinite(Core.Exp exp) {
    return exp.isExtent() && exp.getRangeExtent().iterable == null;
  }

  public static Core.Decl infinitePats(
      TypeSystem typeSystem, Environment env, Core.Decl node) {
    if (true) {
      return node; // TODO:
    }
    return node.accept(
        new Shuttle(typeSystem) {
          @Override
          protected Core.From visit(Core.From from) {
            for (Ord<Core.FromStep> step : Ord.zip(from.steps)) {
              if (step.e instanceof Core.Scan) {
                final Core.Scan scan = (Core.Scan) step.e;
                if (isInfinite(scan.exp)) {
                  final FromBuilder fromBuilder = core.fromBuilder(typeSystem);
                  List<Core.FromStep> followingSteps =
                      skip(from.steps, step.i + 1);
                  final Analysis analysis =
                      create(
                          typeSystem,
                          env,
                          true,
                          scan.pat,
                          ImmutableSortedMap.of(),
                          followingSteps,
                          ImmutablePairList.of());
                  for (Core.FromStep step2 : from.steps) {
                    if (step2 == scan) {
                      fromBuilder.scan(
                          scan.pat, analysis.extentExp, scan.condition); // TODO
                    } else if (step2 instanceof Core.Where) {
                      fromBuilder.where(
                          core.subTrue(
                              typeSystem,
                              ((Core.Where) step2).exp,
                              analysis.satisfiedFilters));
                    } else {
                      fromBuilder.addAll(ImmutableList.of(step2));
                    }
                  }
                  return fromBuilder.build();
                }
              }
            }
            return from; // unchanged
          }
        });
  }

  /**
   * Intersects a collection of range set maps (maps from prefix to {@link
   * RangeSet}) into one.
   */
  public static <C extends Comparable<C>>
      Map<String, ImmutableRangeSet<C>> intersect(
          List<Map<String, ImmutableRangeSet<C>>> rangeSetMaps) {
    switch (rangeSetMaps.size()) {
      case 0:
        // No filters, therefore the extent allows all values.
        // An empty map expresses this.
        return ImmutableMap.of();

      case 1:
        return rangeSetMaps.get(0);

      default:
        final Multimap<String, ImmutableRangeSet<C>> rangeSetMultimap =
            HashMultimap.create();
        for (Map<String, ImmutableRangeSet<C>> rangeSetMap : rangeSetMaps) {
          rangeSetMap.forEach(rangeSetMultimap::put);
        }
        final ImmutableMap.Builder<String, ImmutableRangeSet<C>> rangeSetMap =
            ImmutableMap.builder();
        rangeSetMultimap
            .asMap()
            .forEach(
                (path, rangeSets) ->
                    rangeSetMap.put(path, intersectRangeSets(rangeSets)));
        return rangeSetMap.build();
    }
  }

  /**
   * Unions a collection of range set maps (maps from prefix to {@link
   * RangeSet}) into one.
   */
  public static <C extends Comparable<C>>
      Map<String, ImmutableRangeSet<C>> union(
          List<Map<String, ImmutableRangeSet<C>>> rangeSetMaps) {
    switch (rangeSetMaps.size()) {
      case 0:
        // No filters, therefore the extent is empty.
        // A map containing an empty RangeSet for path "/" expresses this.
        return ImmutableMap.of("/", ImmutableRangeSet.of());

      case 1:
        return rangeSetMaps.get(0);

      default:
        final Multimap<String, ImmutableRangeSet<C>> rangeSetMultimap =
            HashMultimap.create();
        for (Map<String, ImmutableRangeSet<C>> rangeSetMap : rangeSetMaps) {
          rangeSetMap.forEach(rangeSetMultimap::put);
        }
        final ImmutableMap.Builder<String, ImmutableRangeSet<C>> rangeSetMap =
            ImmutableMap.builder();
        rangeSetMultimap
            .asMap()
            .forEach(
                (path, rangeSets) ->
                    rangeSetMap.put(path, unionRangeSets(rangeSets)));
        return rangeSetMap.build();
    }
  }

  /**
   * Intersects a collection of {@link RangeSet} into one.
   *
   * @see ImmutableRangeSet#intersection(RangeSet)
   */
  private static <C extends Comparable<C>>
      ImmutableRangeSet<C> intersectRangeSets(
          Collection<ImmutableRangeSet<C>> rangeSets) {
    return rangeSets.stream()
        .reduce(
            ImmutableRangeSet.of(Range.all()), ImmutableRangeSet::intersection);
  }

  /**
   * Unions a collection of {@link RangeSet} into one.
   *
   * @see ImmutableRangeSet#union(RangeSet)
   */
  private static <C extends Comparable<C>> ImmutableRangeSet<C> unionRangeSets(
      Collection<ImmutableRangeSet<C>> rangeSets) {
    return rangeSets.stream()
        .reduce(ImmutableRangeSet.of(), ImmutableRangeSet::union);
  }

  /**
   * Result of analyzing the variables in a query, pulling filters into the
   * extent expression for each variable, so that no variable is over an
   * infinite extent.
   */
  public static class Analysis {
    final SortedMap<Core.NamedPat, Core.Exp> boundPats;
    final Set<Core.NamedPat> goalPats;
    final Core.Exp extentExp;
    final List<Core.Exp> satisfiedFilters; // filters satisfied by extentExp
    final List<Core.Exp> remainingFilters;

    private Analysis(
        SortedMap<Core.NamedPat, Core.Exp> boundPats,
        List<Core.NamedPat> goalPats,
        Core.Exp extentExp,
        List<Core.Exp> satisfiedFilters,
        List<Core.Exp> remainingFilters) {
      this.boundPats = ImmutableSortedMap.copyOf(boundPats);
      this.goalPats = ImmutableSet.copyOf(goalPats);
      this.extentExp = extentExp;
      this.satisfiedFilters = ImmutableList.copyOf(satisfiedFilters);
      this.remainingFilters = ImmutableList.copyOf(remainingFilters);
    }

    Set<Core.NamedPat> unboundPats() {
      return minus(goalPats, boundPats.keySet());
    }
  }

  private static class Extent {
    private final TypeSystem typeSystem;
    private final Environment env;
    private final boolean invert;
    final List<Core.NamedPat> goalPats;
    final SortedMap<Core.NamedPat, Core.Exp> boundPats;

    /**
     * New variables introduced as scans over an existing relation (list of
     * records). Other variables, which are goals of this extent, are typically
     * fields of this variable.
     */
    final PairList<Core.IdPat, Core.Exp> idPats;

    /**
     * Contains definitions, such as "name = d.dname". With such a definition,
     * "name" won't need an extent, because we can define it (or inline it) as
     * the expression "d.dname".
     */
    final Map<Core.NamedPat, Core.Exp> definitions = new HashMap<>();

    Extent(
        TypeSystem typeSystem,
        Environment env,
        boolean invert,
        Core.Pat pat,
        SortedMap<Core.NamedPat, Core.Exp> boundPats,
        PairList<Core.IdPat, Core.Exp> idPats) {
      this.typeSystem = requireNonNull(typeSystem);
      this.env = requireNonNull(env);
      this.invert = invert;
      this.goalPats = ImmutableList.copyOf(flatten(pat));
      this.boundPats = ImmutableSortedMap.copyOf(boundPats);
      this.idPats = idPats;
    }

    @SuppressWarnings("SwitchStatementWithTooFewBranches")
    void g3(Map<Core.Pat, PairList<Core.Exp, Core.Exp>> map, Core.Exp filter) {
      final Core.Apply apply;
      switch (filter.op) {
        case APPLY:
          apply = (Core.Apply) filter;
          switch (apply.fn.op) {
            case FN_LITERAL:
              BuiltIn builtIn = ((Core.Literal) apply.fn).unwrap(BuiltIn.class);
              final Map<Core.Pat, PairList<Core.Exp, Core.Exp>> map2;
              switch (builtIn) {
                case Z_ANDALSO:
                  // Expression is 'andalso'. Visit each pattern, and 'and' the
                  // filters (intersect the extents).
                  map2 = new LinkedHashMap<>();
                  apply.arg.forEachArg((arg, i) -> g3(map2, arg));
                  map2.forEach(
                      (pat, foo) ->
                          map.computeIfAbsent(pat, p -> PairList.of())
                              .addAll(foo));
                  break;

                case Z_ORELSE:
                  // Expression is 'orelse'. Visit each pattern, and intersect
                  // the constraints (union the generators).
                  map2 = new LinkedHashMap<>();
                  final Map<Core.Pat, PairList<Core.Exp, Core.Exp>> map3 =
                      new LinkedHashMap<>();
                  apply.arg.forEachArg(
                      (arg, i) -> {
                        g3(map3, arg);
                        map3.forEach(
                            (pat, foo) ->
                                map2.computeIfAbsent(pat, p -> PairList.of())
                                    .add(reduceAnd(typeSystem, foo)));
                        map3.clear();
                      });
                  map2.forEach(
                      (pat, foo) -> {
                        final PairList<Core.Exp, Core.Exp> foo1 =
                            map.computeIfAbsent(pat, p -> PairList.of());
                        if (foo1.isEmpty()) {
                          // [] union [x2, x3, x4]
                          //  =>
                          // [x2, x3, x4]
                          foo1.add(reduceOr(typeSystem, foo));
                        } else {
                          // [x0, x1] union [x2, x3, x4]
                          //  =>
                          // [union(intersect(x0, x1), intersect(x2, x3, x4))]
                          PairList<Core.Exp, Core.Exp> intersectExtents =
                              PairList.of();
                          intersectExtents.add(reduceAnd(typeSystem, foo1));
                          intersectExtents.add(reduceAnd(typeSystem, foo));
                          foo1.clear();
                          foo1.add(reduceOr(typeSystem, intersectExtents));
                        }
                      });
                  break;

                case OP_EQ:
                case OP_NE:
                case OP_GE:
                case OP_GT:
                case OP_LT:
                case OP_LE:
                  g4(
                      builtIn,
                      apply.arg(0),
                      apply.arg(1),
                      (pat, filter2, extent) ->
                          map.computeIfAbsent(pat, p -> PairList.of())
                              .add(extent, filter2));
                  break;

                case OP_ELEM:
                  switch (apply.arg(0).op) {
                    case ID:
                      final Core.NamedPat pat = ((Core.Id) apply.arg(0)).idPat;
                      map.computeIfAbsent(pat, p1 -> PairList.of())
                          .add(apply.arg(1), apply);
                      break;

                    case TUPLE:
                      final Core.Tuple tuple = (Core.Tuple) apply.arg(0);
                      final Core.Id id =
                          core.id(createId(tuple.type, apply.arg(1)));
                      final Core.Exp elem =
                          core.elem(typeSystem, id, apply.arg(1));
                      g3(
                          map,
                          core.andAlso(
                              typeSystem,
                              elem,
                              core.equal(typeSystem, id, tuple)));
                      final List<Core.Exp> conjunctions = new ArrayList<>();
                      conjunctions.add(core.elem(typeSystem, id, apply.arg(1)));
                      tuple.forEach(
                          (i, name, arg) ->
                              conjunctions.add(
                                  core.equal(
                                      typeSystem,
                                      core.field(typeSystem, id, i),
                                      arg)));
                      g3(map, core.andAlso(typeSystem, conjunctions));
                      break;
                  }
                  break;
              }
          }
          break;

        default:
          break;
      }
    }

    @SuppressWarnings("SwitchStatementWithTooFewBranches")
    void g3b(Map<Core.Pat, PairList<Core.Exp, Core.Exp>> map, Core.Exp filter) {
      boolean tryInvert = false;
      final Core.Apply apply;
      switch (filter.op) {
        case APPLY:
          apply = (Core.Apply) filter;
          switch (apply.fn.op) {
            case FN_LITERAL:
              Core.Literal literal = (Core.Literal) apply.fn;
              // Check if it's a user-defined function
              if (literal.value instanceof Core.Fn) {
                tryInvert = true;
                break;
              }
              BuiltIn builtIn = literal.unwrap(BuiltIn.class);
              final Map<Core.Pat, PairList<Core.Exp, Core.Exp>> map2;
              switch (builtIn) {
                case Z_ANDALSO:
                  // Expression is 'andalso'. Visit each pattern, and 'and' the
                  // filters (intersect the extents).
                  // First try PredicateInverter
                  // Convert boundPats to generators
                  final Map<Core.NamedPat, PredicateInverter.Generator>
                      generators = new LinkedHashMap<>();
                  boundPats.forEach(
                      (pat, exp) ->
                          generators.put(
                              pat,
                              new PredicateInverter.Generator(
                                  pat,
                                  exp,
                                  Generator.Cardinality.FINITE,
                                  ImmutableList.of(),
                                  ImmutableSet.of())));
                  final PredicateInverter.Result result =
                      PredicateInverter.invert(
                          typeSystem, env, filter, goalPats, generators);
                  if (invert
                      && result.generator.cardinality
                          != Generator.Cardinality.INFINITE) {
                    // The generator produces tuples for all goalPats
                    // Add remaining filters if any
                    final Core.Exp combinedFilter =
                        core.andAlso(typeSystem, result.remainingFilters);
                    goalPats.forEach(
                        pat ->
                            map.computeIfAbsent(pat, p -> PairList.of())
                                .add(
                                    result.generator.expression,
                                    combinedFilter));
                    break;
                  }
                  map2 = new LinkedHashMap<>();
                  apply.arg.forEachArg((arg, i) -> g3(map2, arg));
                  map2.forEach(
                      (pat, foo) ->
                          map.computeIfAbsent(pat, p -> PairList.of())
                              .addAll(foo));
                  break;

                case Z_ORELSE:
                  // Expression is 'orelse'. Visit each pattern, and intersect
                  // the constraints (union the generators).
                  map2 = new LinkedHashMap<>();
                  final Map<Core.Pat, PairList<Core.Exp, Core.Exp>> map3 =
                      new LinkedHashMap<>();
                  apply.arg.forEachArg(
                      (arg, i) -> {
                        g3(map3, arg);
                        map3.forEach(
                            (pat, foo) ->
                                map2.computeIfAbsent(pat, p -> PairList.of())
                                    .add(reduceAnd(typeSystem, foo)));
                        map3.clear();
                      });
                  map2.forEach(
                      (pat, foo) -> {
                        final PairList<Core.Exp, Core.Exp> foo1 =
                            map.computeIfAbsent(pat, p -> PairList.of());
                        if (foo1.isEmpty()) {
                          // [] union [x2, x3, x4]
                          //  =>
                          // [x2, x3, x4]
                          foo1.add(reduceOr(typeSystem, foo));
                        } else {
                          // [x0, x1] union [x2, x3, x4]
                          //  =>
                          // [union(intersect(x0, x1), intersect(x2, x3, x4))]
                          PairList<Core.Exp, Core.Exp> intersectExtents =
                              PairList.of();
                          intersectExtents.add(reduceAnd(typeSystem, foo1));
                          intersectExtents.add(reduceAnd(typeSystem, foo));
                          foo1.clear();
                          foo1.add(reduceOr(typeSystem, intersectExtents));
                        }
                      });
                  break;

                case OP_EQ:
                case OP_NE:
                case OP_GE:
                case OP_GT:
                case OP_LT:
                case OP_LE:
                  g4(
                      builtIn,
                      apply.arg(0),
                      apply.arg(1),
                      (pat, filter2, extent) ->
                          map.computeIfAbsent(pat, p -> PairList.of())
                              .add(extent, filter2));
                  break;

                case OP_ELEM:
                  switch (apply.arg(0).op) {
                    case ID:
                      final Core.NamedPat pat = ((Core.Id) apply.arg(0)).idPat;
                      map.computeIfAbsent(pat, p1 -> PairList.of())
                          .add(apply.arg(1), apply);
                      break;

                    case TUPLE:
                      final Core.Tuple tuple = (Core.Tuple) apply.arg(0);
                      final Core.Id id =
                          core.id(createId(tuple.type, apply.arg(1)));
                      final Core.Exp elem =
                          core.elem(typeSystem, id, apply.arg(1));
                      g3(
                          map,
                          core.andAlso(
                              typeSystem,
                              elem,
                              core.equal(typeSystem, id, tuple)));
                      final List<Core.Exp> conjunctions = new ArrayList<>();
                      conjunctions.add(core.elem(typeSystem, id, apply.arg(1)));
                      tuple.forEach(
                          (i, name, arg) ->
                              conjunctions.add(
                                  core.equal(
                                      typeSystem,
                                      core.field(typeSystem, id, i),
                                      arg)));
                      g3(map, core.andAlso(typeSystem, conjunctions));
                      break;
                  }
                  break;

                default:
                  // For other built-in functions, try PredicateInverter
                  tryInvert = true;
                  break;
              }
              break;

            case ID:
              // User-defined function call. Try to invert.
              tryInvert = true;
              break;

            case APPLY:
              // Curried function application (e.g., String.isPrefix s "abcd").
              // Try to invert.
              tryInvert = true;
              break;

            default:
              break;
          }
          break;

        default:
          break;
      }

      if (tryInvert) {
        // Convert boundPats to generators
        final Map<Core.NamedPat, PredicateInverter.Generator> generators =
            new LinkedHashMap<>();
        boundPats.forEach(
            (pat, exp) ->
                generators.put(
                    pat,
                    new PredicateInverter.Generator(
                        pat,
                        exp,
                        Generator.Cardinality.FINITE,
                        ImmutableList.of(),
                        ImmutableSet.of())));
        final PredicateInverter.Result result =
            PredicateInverter.invert(
                typeSystem, env, filter, goalPats, generators);
        // Check if inversion succeeded (didn't just return fallback)
        final boolean inversionSucceeded =
            result.remainingFilters.size() != 1
                || !result.remainingFilters.get(0).equals(filter);
        if (inversionSucceeded) {
          // The generator produces tuples for all goalPats
          final Core.Exp combinedFilter =
              result.remainingFilters.isEmpty()
                  ? core.boolLiteral(true)
                  : core.andAlso(typeSystem, result.remainingFilters);
          goalPats.forEach(
              pat ->
                  map.computeIfAbsent(pat, p -> PairList.of())
                      .add(result.generator.expression, combinedFilter));
        }
      }
    }

    private void g4(
        BuiltIn builtIn,
        Core.Exp arg0,
        Core.Exp arg1,
        TriConsumer<Core.Pat, Core.Exp, Core.Exp> consumer) {
      g5(builtIn, arg0, arg1, consumer);
      g5(builtIn.reverse(), arg1, arg0, consumer);
    }

    @SuppressWarnings("SwitchStatementWithTooFewBranches")
    private void g5(
        BuiltIn builtIn,
        Core.Exp arg0,
        Core.Exp arg1,
        TriConsumer<Core.Pat, Core.Exp, Core.Exp> consumer) {
      switch (builtIn) {
        case OP_EQ:
          switch (arg0.op) {
            case ID:
              final Core.Id id = (Core.Id) arg0;
              definitions.put(id.idPat, arg1);
          }
          // fall through
        case OP_NE:
        case OP_GE:
        case OP_GT:
        case OP_LE:
        case OP_LT:
          switch (arg0.op) {
            case ID:
              final Core.Id id = (Core.Id) arg0;
              if (arg1.isConstant()) {
                // If exp is "id = literal", add extent "id: [literal]";
                // if exp is "id > literal", add extent "id: (literal, inf)",
                // etc.
                consumer.accept(
                    id.idPat,
                    core.call(
                        typeSystem, builtIn, arg0.type, Pos.ZERO, arg0, arg1),
                    baz(builtIn, arg1));
              }
              break;
          }
          break;

        default:
          throw new AssertionError("unexpected: " + builtIn);
      }
    }

    private Core.Exp baz(BuiltIn builtIn, Core.Exp arg) {
      switch (builtIn) {
        case OP_EQ:
          return core.list(typeSystem, arg);
        case OP_NE:
          return core.extent(
              typeSystem,
              arg.type,
              ImmutableRangeSet.of(Range.singleton(((Core.Literal) arg).value))
                  .complement());
        case OP_GE:
          return core.extent(
              typeSystem,
              arg.type,
              ImmutableRangeSet.of(Range.atLeast(((Core.Literal) arg).value)));
        case OP_GT:
          return core.extent(
              typeSystem,
              arg.type,
              ImmutableRangeSet.of(
                  Range.greaterThan(((Core.Literal) arg).value)));
        case OP_LE:
          return core.extent(
              typeSystem,
              arg.type,
              ImmutableRangeSet.of(Range.atMost(((Core.Literal) arg).value)));
        case OP_LT:
          return core.extent(
              typeSystem,
              arg.type,
              ImmutableRangeSet.of(Range.lessThan(((Core.Literal) arg).value)));
        default:
          throw new AssertionError("unexpected: " + builtIn);
      }
    }

    private Core.IdPat createId(Type type, Core.Exp extent) {
      int i = idPats.firstMatch((id, e) -> extent.equals(e));
      if (i >= 0) {
        return idPats.leftList().get(i);
      }
      final Core.IdPat idPat = core.idPat(type, typeSystem.nameGenerator::get);
      idPats.add(idPat, extent);
      return idPat;
    }
  }

  /**
   * Reduces a list of extent-filter pairs [e0, f0, e1, f1, ...] to an
   * extent-filter pair [e0 intersect e1 ..., f0 andalso f1 ...].
   *
   * <p>If any of the e<sub>i</sub> are extents (see {@link
   * Core.Exp#isExtent()}), merges them into a single extent. For example, in
   *
   * <pre>{@code
   * [extent "int: (0, inf)", x > 0,
   *   x elem primes, isPrime x,
   *   extent "int: (-inf, 10)", x < 10]
   * }</pre>
   *
   * <p>the extents for "(0, inf)" and "(-inf, 10)" are merged into extent "(0,
   * 10)":
   *
   * <pre>{@code
   * (extent "int: (0, 10)" intersect primes,
   *   x > 0 andalso isPrime x andalso x < 10)
   * }</pre>
   */
  static Pair<Core.Exp, Core.Exp> reduceAnd(
      TypeSystem typeSystem, PairList<Core.Exp, Core.Exp> extentFilters) {
    if (extentFilters.isEmpty()) {
      // Empty list would require us to create an infinite extent, but we
      // don't know the type. Caller must ensure that the list is non-empty.
      throw new IllegalArgumentException();
    }
    final List<Core.Exp> extents = new ArrayList<>();
    core.flattenAnds(extentFilters.leftList(), extents::add);
    final Pair<Core.Exp, List<Core.Exp>> pair =
        core.intersectExtents(typeSystem, extents);
    return Pair.of(
        pair.left, core.andAlso(typeSystem, extentFilters.rightList()));
  }

  /**
   * Reduces a list of extent-filter pairs [e0, f0, e1, f1, ...] to an
   * extent-filter pair [e0 union e1 ..., f0 orelse f1 ...].
   */
  static Pair<Core.Exp, Core.Exp> reduceOr(
      TypeSystem typeSystem, PairList<Core.Exp, Core.Exp> extentFilters) {
    return Pair.of(
        core.listConcat(typeSystem, extentFilters.leftList()),
        core.orElse(typeSystem, extentFilters.rightList()));
  }

  static class ExtentMap {
    final Map<Core.Pat, PairList<Core.Exp, Core.Exp>> map =
        new LinkedHashMap<>();

    public PairList<Core.Exp, Core.Exp> get(
        TypeSystem typeSystem, Core.Pat pat) {
      PairList<Core.Exp, Core.Exp> foo = map.get(pat);
      if (foo != null && !foo.isEmpty()) {
        return foo;
      }
      if (canGet(pat)) {
        return get_(typeSystem, pat);
      }
      return ImmutablePairList.of();
    }

    /**
     * Constructs an expression for the extent of a pattern. You must have
     * called {@link #canGet} first.
     */
    private PairList<Core.Exp, Core.Exp> get_(
        TypeSystem typeSystem, Core.Pat pat) {
      final PairList<Core.Exp, Core.Exp> foo = map.get(pat);
      if (foo != null && !foo.isEmpty()) {
        return foo;
      }
      switch (pat.op) {
        case TUPLE_PAT:
          final Core.TuplePat tuplePat = (Core.TuplePat) pat;
          if (allMatch(tuplePat.args, this::canGet)) {
            // Convert 'from x, y where p(x) andalso q(y)'
            // to 'from x in extentP, y in extentQ'
            // and that becomes the extent of '(x, y)'.
            final FromBuilder fromBuilder = core.fromBuilder(typeSystem);
            final List<Core.Exp> filters = new ArrayList<>();
            for (Core.Pat p : tuplePat.args) {
              PairList<Core.Exp, Core.Exp> f =
                  requireNonNull(get(typeSystem, p), "contradicts canGet");
              fromBuilder.scan(p, core.listConcat(typeSystem, f.leftList()));
              core.flattenAnds(f.rightList(), filters::add);
            }
            return PairList.of(
                fromBuilder.build(), core.andAlso(typeSystem, filters));
          } else {
            final PairList<Core.Exp, Core.Exp> foo1 = PairList.of();
            map.forEach(
                (pat1, foo2) -> {
                  if (pat1.op == Op.TUPLE_PAT) {
                    final Core.TuplePat tuplePat1 = (Core.TuplePat) pat1;
                    final List<String> fieldNames = tuplePat1.fieldNames();
                    if (allMatch(
                        tuplePat.args,
                        arg ->
                            arg instanceof Core.NamedPat
                                && fieldNames.contains(
                                    ((Core.NamedPat) arg).name))) {
                      foo1.addAll(foo2);
                    }
                  }
                });
            return foo1;
          }
        default:
          throw new AssertionError("contradicts canGet");
      }
    }

    boolean canGet(Core.Pat pat) {
      PairList<Core.Exp, Core.Exp> foo = map.get(pat);
      if (foo != null && !foo.isEmpty()) {
        return true;
      }
      if (pat.type.isFinite()) {
        return true;
      }
      switch (pat.op) {
        case TUPLE_PAT:
          final Core.TuplePat tuplePat = (Core.TuplePat) pat;
          if (allMatch(tuplePat.args, this::canGet)) {
            return true;
          }
          // If the map contains a tuple with a field for every one of this
          // tuple's fields (not necessarily in the same order) then we can use
          // it.
          for (Core.Pat pat1 : map.keySet()) {
            if (pat1.op == Op.TUPLE_PAT) {
              final Core.TuplePat tuplePat1 = (Core.TuplePat) pat1;
              final List<String> fieldNames = tuplePat1.fieldNames();
              if (allMatch(
                  tuplePat.args,
                  arg ->
                      arg instanceof Core.NamedPat
                          && fieldNames.contains(((Core.NamedPat) arg).name))) {
                return true;
              }
            }
          }
          return false;
        default:
          return false;
      }
    }
  }
}

// End Extents.java
