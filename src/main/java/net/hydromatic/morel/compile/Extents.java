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

import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.FromBuilder;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.ast.Shuttle;
import net.hydromatic.morel.type.ListType;
import net.hydromatic.morel.type.RangeExtent;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.Ord;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableRangeSet;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Range;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;

import static net.hydromatic.morel.ast.CoreBuilder.core;
import static net.hydromatic.morel.util.Pair.allMatch;
import static net.hydromatic.morel.util.Static.skip;
import static net.hydromatic.morel.util.Static.transform;

import static org.apache.calcite.util.Util.minus;

import static java.util.Objects.requireNonNull;

/** Generates an expression for the set of values that a variable can take in
 * a program.
 *
 * <p>If {@code i} is a variable of type {@code int} then one approximation is
 * the set of all 2<sup>32</sup> values of the {@code int} data type. (Every
 * data type, primitive data types and those built using sum ({@code datatype})
 * or product (record and tuple), has a finite set of values, but the set is
 * usually too large to iterate over.)
 *
 * <p>There is often a better approximation that can be deduced from the uses
 * of the variable. For example,
 *
 * <blockquote><pre>{@code
 *   let
 *     fun isOdd i = i % 2 = 0
 *   in
 *     from e in emps,
 *         i suchthat isOdd i andalso i < 100
 *       where i = e.deptno
 *   end
 * }</pre></blockquote>
 *
 * <p>we can deduce a better extent for {@code i}, namely
 *
 * <blockquote><pre>{@code
 *    from e in emps
 *      yield e.deptno
 *      where deptno % 2 = 0 andalso deptno < 100
 * }</pre></blockquote>
 */
public class Extents {
  private Extents() {}

  /** Returns an expression that generates the extent of a pattern.
   *
   * <p>For example, given the program
   *
   * <blockquote><pre>{@code
   *   let
   *     fun f i = i elem [1, 2, 4]
   *   in
   *     from x suchthat f x
   *   end
   * }</pre></blockquote>
   *
   * <p>we can deduce that the extent of "x" is "[1, 2, 4]".
   *
   * <p>We can also compute the extent of tuples. For the program
   *
   * <blockquote><pre>{@code
   *   let
   *     val edges = [(1, 2), (2, 3), (1, 4), (4, 2), (4, 3)]
   *     fun edge (i, j) = (i, j) elem edges
   *   in
   *     from (x, y, z) suchthat edge (x, y) andalso edge (y, z) andalso x <> z
   *   end
   * }</pre></blockquote>
   *
   * <p>we could deduce that "x" has extent "from e in edges group e.i",
   * "y" has extent "from e in edges group e.j"
   * ("from e in edges group e.i" is also valid),
   * "z" has extent "from e in edges group e.j",
   * and therefore "(x, y, z)" has extent
   *
   * <blockquote><pre>{@code
   * from x in (from e in edges group e.i),
   *   y in (from e in edges group e.j),
   *   z in (from e in edges group e.j)
   * }</pre></blockquote>
   *
   * <p>but we can do better by computing the extent of (x, y) simultaneously:
   *
   * <blockquote><pre>{@code
   * from (x, y) in (from e in edges),
   *   z in (from e in edges group e.j)
   * }</pre></blockquote>
   */
  public static Core.Exp generator(TypeSystem typeSystem, Core.Pat pat,
      Core.@Nullable Exp extentExp, Core.@Nullable Exp filterExp,
      List<Core.FromStep> followingSteps) {
    final Analysis analysis =
        create(typeSystem, pat, ImmutableSortedMap.of(), extentExp, filterExp,
            followingSteps);
    return analysis.extentExp;
  }

  public static Analysis create(TypeSystem typeSystem, Core.Pat pat,
      SortedMap<Core.NamedPat, Core.Exp> boundPats,
      Core.@Nullable Exp extentExp, Core.@Nullable Exp filterExp,
      List<Core.FromStep> followingSteps) {
    final Extent extent = new Extent(typeSystem, pat, boundPats);

    final ExtentMap map = new ExtentMap();
    if (filterExp != null) {
      extent.g3(map.map, filterExp);
    }
    for (Core.FromStep step : followingSteps) {
      if (step instanceof Core.Where) {
        extent.g3(map.map, ((Core.Where) step).exp);
      }
    }
    final Foo foo = map.get(typeSystem, pat);
    if (foo == null) {
      throw new AssertionError("unknown pattern " + pat);
    }
    final List<Core.Exp> remainingFilters = new ArrayList<>();
    final Foo mergedFoo = foo.mergeExtents(typeSystem, true);
    return new Analysis(boundPats, extent.goalPats, mergedFoo.extents.get(0),
        foo.filters, remainingFilters);
  }

  /** Converts a singleton id pattern "x" or tuple pattern "(x, y)"
   * to a list of id patterns. */
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

    default:
      throw new CompileException("must be id", false, pat.pos);
    }
  }

  /** Returns whether an expression is an infinite extent. */
  public static boolean isInfinite(Core.Exp exp) {
    if (!exp.isCallTo(BuiltIn.Z_EXTENT)) {
      return false;
    }
    final Core.Apply apply = (Core.Apply) exp;
    final Core.Literal literal = (Core.Literal) apply.arg;
    final RangeExtent rangeExtent = literal.unwrap(RangeExtent.class);
    return rangeExtent.isUnbounded();
  }

  public static Core.Decl infinitePats(TypeSystem typeSystem,
      Core.Decl node) {
    return node.accept(
        new Shuttle(typeSystem) {
          @Override
          protected Core.From visit(Core.From from) {
            for (Ord<Core.FromStep> step : Ord.zip(from.steps)) {
              if (step.e instanceof Core.Scan) {
                final Core.Scan scan = (Core.Scan) step.e;
                if (isInfinite(scan.exp)) {
                  final FromBuilder fromBuilder = core.fromBuilder(typeSystem);
                  assert scan.op != Op.SUCH_THAT; // SUCH_THAT is deprecated in core
                  List<Core.FromStep> followingSteps =
                      skip(from.steps, step.i + 1);
                  final Analysis analysis =
                      create(typeSystem, scan.pat, ImmutableSortedMap.of(),
                          scan.exp, null, followingSteps);
                  for (Core.FromStep step2 : from.steps) {
                    if (step2 == scan) {
                      fromBuilder.scan(scan.pat, analysis.extentExp,
                          scan.condition); // TODO
                    } else if (step2 instanceof Core.Where) {
                      fromBuilder.where(
                          core.subTrue(typeSystem,
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

  public static class Analysis {
    final SortedMap<Core.NamedPat, Core.Exp> boundPats;
    final Set<Core.NamedPat> goalPats;
    final Core.Exp extentExp;
    final List<Core.Exp> satisfiedFilters; // filters satisfied by extentExp
    final List<Core.Exp> remainingFilters;

    Analysis(SortedMap<Core.NamedPat, Core.Exp> boundPats,
        Set<Core.NamedPat> goalPats, Core.Exp extentExp,
        List<Core.Exp> satifiedFilters,
        List<Core.Exp> remainingFilters) {
      this.boundPats = boundPats;
      this.goalPats = goalPats;
      this.extentExp = extentExp;
      this.satisfiedFilters = satifiedFilters;
      this.remainingFilters = remainingFilters;
    }

    Set<Core.NamedPat> unboundPats() {
      return minus(goalPats, boundPats.keySet());
    }
  }

  private static class Extent {
    private final TypeSystem typeSystem;
    final Set<Core.NamedPat> goalPats;
    final SortedMap<Core.NamedPat, Core.Exp> boundPats;

    Extent(TypeSystem typeSystem, Core.Pat pat,
        SortedMap<Core.NamedPat, Core.Exp> boundPats) {
      this.typeSystem = typeSystem;
      this.goalPats = ImmutableSet.copyOf(flatten(pat));
      this.boundPats = ImmutableSortedMap.copyOf(boundPats);
    }

    @SuppressWarnings("SwitchStatementWithTooFewBranches")
    void g3(Map<Core.Pat, Foo> map, Core.Exp filter) {
      final Core.Apply apply;
      switch (filter.op) {
      case APPLY:
        apply = (Core.Apply) filter;
        switch (apply.fn.op) {
        case FN_LITERAL:
          BuiltIn builtIn = ((Core.Literal) apply.fn).unwrap(BuiltIn.class);
          final Map<Core.Pat, Foo> map2;
          switch (builtIn) {
          case Z_ANDALSO:
            // Expression is 'andalso'. Visit each pattern, and 'and' the
            // filters (intersect the extents).
            map2 = new LinkedHashMap<>();
            apply.arg.forEachArg((arg, i) -> g3(map2, arg));
            map2.forEach((pat, foo) -> {
              map.putIfAbsent(pat, new Foo());
              final Foo foo1 = map.get(pat);
              foo1.filters.add(core.andAlso(typeSystem, foo.filters));
              foo1.extents.add(core.intersect(typeSystem, foo.extents));
            });
            break;

          case Z_ORELSE:
            // Expression is 'orelse'. Visit each pattern, and intersect the
            // constraints (union the generators).
            map2 = new LinkedHashMap<>();
            apply.arg.forEachArg((arg, i) -> g3(map2, arg));
            map2.forEach((k, foo) -> {
              map.putIfAbsent(k, new Foo());
              final Foo foo2 = map.get(k);
              foo2.filters.add(core.orElse(typeSystem, foo.filters));
              foo2.extents.add(core.union(typeSystem, foo.extents));
            });
            break;

          case OP_EQ:
          case OP_NE:
          case OP_GE:
          case OP_GT:
          case OP_LT:
          case OP_LE:
            g4(builtIn, apply.arg(0), apply.arg(1), (pat, filter2, extent) -> {
              map.putIfAbsent(pat, new Foo());
              final Foo foo = map.get(pat);
              foo.filters.add(filter2);
              foo.extents.add(extent);
            });
            break;

          case OP_ELEM:
            final Foo foo;
            switch (apply.arg(0).op) {
            case ID:
              final Core.NamedPat pat = ((Core.Id) apply.arg(0)).idPat;
              map.putIfAbsent(pat, new Foo());
              foo = map.get(pat);
              foo.filters.add(apply);
              foo.extents.add(apply.arg(1));
              break;

            case TUPLE:
              final Core.Tuple tuple = (Core.Tuple) apply.arg(0);
              final Core.TuplePat tuplePat =
                  core.tuplePat(typeSystem,
                      transform(tuple.args, arg -> ((Core.Id) arg).idPat));
              map.putIfAbsent(tuplePat, new Foo());
              foo = map.get(tuplePat);
              foo.filters.add(apply);
              foo.extents.add(apply.arg(1));
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
    private void g4(BuiltIn builtIn, Core.Exp arg0, Core.Exp arg1,
        TriConsumer<Core.Pat, Core.Exp, Core.Exp> consumer) {
      switch (builtIn) {
      case OP_EQ:
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
            // if exp is "id > literal", add extent "id: (literal, inf)", etc.
            consumer.accept(id.idPat,
                core.call(typeSystem, builtIn, arg0.type, Pos.ZERO, arg0, arg1),
                baz(builtIn, arg1));
          }
          break;
        default:
          if (arg0.isConstant() && arg1.op == Op.ID) {
            // Try switched, "literal = id".
            g4(builtIn.reverse(), arg1, arg0, consumer);
          }
        }
        break;

      default:
        throw new AssertionError("unexpected: " + builtIn);
      }
    }

    @SuppressWarnings("UnstableApiUsage")
    private Core.Exp baz(BuiltIn builtIn, Core.Exp arg) {
      switch (builtIn) {
      case OP_EQ:
        return core.list(typeSystem, arg);
      case OP_NE:
        return core.extent(typeSystem, arg.type,
            ImmutableRangeSet.of(Range.singleton(((Core.Literal) arg).value))
                .complement());
      case OP_GE:
        return core.extent(typeSystem, arg.type,
            ImmutableRangeSet.of(Range.atLeast(((Core.Literal) arg).value)));
      case OP_GT:
        return core.extent(typeSystem, arg.type,
            ImmutableRangeSet.of(Range.greaterThan(((Core.Literal) arg).value)));
      case OP_LE:
        return core.extent(typeSystem, arg.type,
            ImmutableRangeSet.of(Range.atMost(((Core.Literal) arg).value)));
      case OP_LT:
        return core.extent(typeSystem, arg.type,
            ImmutableRangeSet.of(Range.lessThan(((Core.Literal) arg).value)));
      default:
        throw new AssertionError("unexpected: " + builtIn);
      }
    }

    @SuppressWarnings("UnstableApiUsage")
    ExtentFilter extent(Core.Scan scan) {
      final List<Core.Exp> extents = new ArrayList<>();
      final List<Core.Exp> filters = new ArrayList<>();
      extent(scan.pat, scan.exp, extents, filters);
      final Core.Exp extent;
      if (extents.isEmpty()) {
        extent = core.extent(typeSystem, scan.pat.type,
            ImmutableRangeSet.of(Range.all()));
      } else {
        extent = extents.get(0);
        filters.addAll(skip(extents));
      }
      return new ExtentFilter(extent, ImmutableList.copyOf(filters));
    }

    private void extent(Core.Pat pat, Core.Exp exp, List<Core.Exp> extents,
        List<Core.Exp> filters) {
      switch (exp.op) {
      case APPLY:
        final Core.Apply apply = (Core.Apply) exp;
        switch (apply.fn.op) {
        case FN_LITERAL:
          switch ((BuiltIn) ((Core.Literal) apply.fn).value) {
          case OP_ELEM:
            final List<Core.Exp> args = ((Core.Tuple) apply.arg).args;
            if (matches(args.get(0), pat)) {
              extents.add(args.get(1));
            }
            break;
          case Z_ANDALSO:
            for (Core.Exp e : ((Core.Tuple) apply.arg).args) {
              extent(pat, e, extents, filters);
              return;
            }
          }
        }
      }
      filters.add(exp);
    }

    /** Returns whether an expression corresponds exactly to a pattern.
     * For example "x" matches the pattern "x",
     * and "(z, y)" matches the pattern "(x, y)". */
    private static boolean matches(Core.Exp exp, Core.Pat pat) {
      if (exp.op == Op.ID && pat.op == Op.ID_PAT) {
        return ((Core.Id) exp).idPat.equals(pat);
      }
      if (exp.op == Op.TUPLE && pat.op == Op.TUPLE_PAT) {
        final Core.Tuple tuple = (Core.Tuple) exp;
        final Core.TuplePat tuplePat = (Core.TuplePat) pat;
        if (tuple.args.size() == tuplePat.args.size()) {
          return allMatch(tuple.args, tuplePat.args, Extent::matches);
        }
      }
      return false;
    }
  }

  /** A "suchthat" expression split into an extent and filters. */
  static class ExtentFilter {
    final Core.Exp extent;
    final ImmutableList<Core.Exp> filters;

    ExtentFilter(Core.Exp extent, ImmutableList<Core.Exp> filters) {
      this.extent = extent;
      this.filters = filters;
    }
  }

  static class Foo {
    final List<Core.Exp> extents = new ArrayList<>();
    final List<Core.Exp> filters = new ArrayList<>();

    @SuppressWarnings({"UnstableApiUsage", "rawtypes", "unchecked"})
    public Foo mergeExtents(TypeSystem typeSystem, boolean intersect) {
      switch (extents.size()) {
      case 0:
        throw new AssertionError();

      case 1:
        extents.set(0, core.simplify(typeSystem, extents.get(0)));
        return this;

      default:
        ImmutableRangeSet rangeSet = intersect
            ? ImmutableRangeSet.of(Range.all())
            : ImmutableRangeSet.of();
        List<Core.Exp> satisfiedFilters = new ArrayList<>();
        List<Core.Exp> remainingFilters = new ArrayList<>();
        for (Core.Exp exp : extents) {
          if (exp.isCallTo(BuiltIn.Z_EXTENT)) {
            final Core.Apply apply = (Core.Apply) exp;
            final Core.Literal argLiteral = (Core.Literal) apply.arg;
            final RangeExtent list = argLiteral.unwrap(RangeExtent.class);
            rangeSet = intersect
                ? rangeSet.intersection(list.rangeSet)
                : rangeSet.union(list.rangeSet);
            satisfiedFilters.add(exp);
          } else {
            remainingFilters.add(exp);
          }
        }
        final ListType listType = (ListType) extents.get(0).type;
        Core.Exp exp =
            core.extent(typeSystem, listType.elementType, rangeSet);
        for (Core.Exp remainingExp : remainingFilters) {
          exp = intersect
              ? core.intersect(typeSystem, exp, remainingExp)
              : core.union(typeSystem, exp, remainingExp);
        }
        final Foo foo = new Foo();
        foo.extents.add(exp);
        return foo;
      }
    }
  }

  @FunctionalInterface
  interface TriConsumer<R, S, T> {
    void accept(R r, S s, T t);
  }

  static class ExtentMap {
    final Map<Core.Pat, Foo> map = new LinkedHashMap<>();

    public @Nullable Foo get(TypeSystem typeSystem, Core.Pat pat) {
      Foo foo = map.get(pat);
      if (foo != null) {
        return foo;
      }
      if (canGet(pat)) {
        return get_(typeSystem, pat);
      }
      return null;
    }

    /**
     * Constructs an expression for the extent of a pattern.
     * You must have called {@link #canGet} first.
     */
    private @NonNull Foo get_(TypeSystem typeSystem, Core.Pat pat) {
      Foo foo = map.get(pat);
      if (foo != null) {
        return foo;
      }
      switch (pat.op) {
      case TUPLE_PAT:
        final Core.TuplePat tuplePat = (Core.TuplePat) pat;
        final Foo foo1 = new Foo();
        if (tuplePat.args.stream().allMatch(this::canGet)) {
          final FromBuilder fromBuilder = core.fromBuilder(typeSystem);
          for (Core.Pat p : tuplePat.args) {
            Foo f = requireNonNull(get(typeSystem, p), "contradicts canGet");
            foo1.filters.addAll(f.filters);
            fromBuilder.scan(p, f.extents.get(0));
          }
          foo1.extents.add(fromBuilder.build());
        } else {
          map.forEach((pat1, foo2) -> {
            if (pat1.op == Op.TUPLE_PAT) {
              final Core.TuplePat tuplePat1 = (Core.TuplePat) pat1;
              final List<String> fieldNames = tuplePat1.fieldNames();
              if (tuplePat.args.stream().allMatch(arg ->
                  arg instanceof Core.NamedPat
                      && fieldNames.contains(((Core.NamedPat) arg).name))) {
                foo1.extents.add(foo2.extents.get(0));
              }
            }
          });
        }
        return foo1;
      default:
        throw new AssertionError("contradicts canGet");
      }
    }

    boolean canGet(Core.Pat pat) {
      Foo foo = map.get(pat);
      if (foo != null) {
        return true;
      }
      if (pat.type.isFinite()) {
        return true;
      }
      switch (pat.op) {
      case TUPLE_PAT:
        final Core.TuplePat tuplePat = (Core.TuplePat) pat;
        if (tuplePat.args.stream().allMatch(this::canGet)) {
          return true;
        }
        // If the map contains a tuple with a field for every one of this
        // tuple's fields (not necessarily in the saem order) then we can use
        // it.
        for (Core.Pat pat1 : map.keySet()) {
          if (pat1.op == Op.TUPLE_PAT) {
            final Core.TuplePat tuplePat1 = (Core.TuplePat) pat1;
            final List<String> fieldNames = tuplePat1.fieldNames();
            if (tuplePat.args.stream().allMatch(arg ->
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
