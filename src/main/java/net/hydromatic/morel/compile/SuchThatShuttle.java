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
import net.hydromatic.morel.ast.Visitor;
import net.hydromatic.morel.type.ListType;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.Ord;
import net.hydromatic.morel.util.Pair;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import org.apache.calcite.util.Holder;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.UnaryOperator;

import static net.hydromatic.morel.ast.CoreBuilder.core;
import static net.hydromatic.morel.util.Static.append;
import static net.hydromatic.morel.util.Static.intersect;
import static net.hydromatic.morel.util.Static.plus;
import static net.hydromatic.morel.util.Static.toImmutableList;

import static com.google.common.collect.Iterables.getLast;
import static com.google.common.collect.Iterables.getOnlyElement;
import static org.apache.calcite.util.Util.skip;

/**
 * Converts {@code suchThat} to {@code in} wherever possible.
 */
class SuchThatShuttle extends Shuttle {
  final Deque<FromState> fromStates = new ArrayDeque<>();

  SuchThatShuttle(TypeSystem typeSystem) {
    super(typeSystem);
  }

  static boolean containsSuchThat(Core.Decl decl) {
    final Holder<Boolean> found = Holder.of(false);
    decl.accept(new Visitor() {
      @Override protected void visit(Core.Scan scan) {
        super.visit(scan);
        if (scan.op == Op.SUCH_THAT) {
          found.set(true);
        }
      }
    });
    return found.get();
  }

  @Override protected Core.Exp visit(Core.From from) {
    try {
      final FromState fromState = new FromState(from);
      fromStates.push(fromState);
      for (Core.FromStep node : from.steps) {
        fromState.steps.add(node.accept(this));
      }
      return from.copy(typeSystem, fromState.steps);
    } finally {
      fromStates.pop();
    }
  }

  @Override protected Core.Scan visit(Core.Scan scan) {
    if (scan.op != Op.SUCH_THAT) {
      return super.visit(scan);
    }
    final Set<Core.NamedPat> goalPatSet =
        new LinkedHashSet<>(flatten(scan.pat));
    final ImmutableSortedMap.Builder<Core.NamedPat, Core.Exp> boundPatBuilder =
        ImmutableSortedMap.orderedBy(Core.NamedPat.ORDERING);
    if (!fromStates.element().steps.isEmpty()) {
      getLast(fromStates.element().steps).bindings.forEach(b -> {
        goalPatSet.add(b.id);
        boundPatBuilder.put(b.id, core.id(b.id));
      });
    }
    final SortedMap<Core.NamedPat, Core.Exp> boundPats = boundPatBuilder.build();
    final Map<Core.IdPat, Core.Exp> scans = ImmutableMap.of();
    final List<Core.Exp> filters = ImmutableList.of();
    final List<Core.NamedPat> goalPats = ImmutableList.copyOf(goalPatSet);
    final UnaryOperator<Core.NamedPat> originalPats = UnaryOperator.identity();
    final Core.Exp rewritten =
        rewrite(originalPats, goalPats, boundPats, scans, filters,
            conjunctions(scan.exp));
    return core.scan(Op.INNER_JOIN, scan.bindings,
        scan.pat, rewritten, scan.condition);
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

  private Core.Exp rewrite(UnaryOperator<Core.NamedPat> mapper,
      List<Core.NamedPat> goalPats,
      SortedMap<Core.NamedPat, Core.Exp> boundPats,
      Map<Core.IdPat, Core.Exp> scans, List<Core.Exp> filters,
      List<Core.Exp> exps) {
    if (exps.isEmpty()) {
      final Set<Core.NamedPat> unboundPats = new HashSet<>(goalPats);
      boundPats.keySet().forEach(unboundPats::remove);
      if (!unboundPats.isEmpty()) {
        throw new CompileException("Cannot implement 'suchthat'; variables "
            + unboundPats + " are not grounded", false, Pos.ZERO);
      }
      final FromBuilder fromBuilder = core.fromBuilder(typeSystem);
      scans.forEach(fromBuilder::scan);
      filters.forEach(fromBuilder::where);
      final SortedMap<String, Core.Exp> nameExps =
          new TreeMap<>(RecordType.ORDERING);
      Core.FromStep step = fromStates.element().currentStep();
      final Set<Core.NamedPat> goalPatSet =
          new LinkedHashSet<>(flatten(((Core.Scan) step).pat));
      boundPats.forEach((p, e) -> {
        Core.NamedPat p2 = mapper.apply(p);
        if (goalPatSet.contains(p2)) {
          nameExps.put(p2.name, e);
        }
      });
//      originalPats.forEach(p -> nameExps.remove(p.name)); // TODO
      fromBuilder.yield_(nameExps.size() == 1
          ? getOnlyElement(nameExps.values())
          : core.record(typeSystem, nameExps));
      return fromBuilder.build();
    }
    final Core.Exp exp = exps.get(0);
    final List<Core.Exp> exps2 = skip(exps);
    switch (exp.op) {
    case APPLY:
      final Core.Apply apply = (Core.Apply) exp;
      if (apply.fn.op == Op.FN_LITERAL) {
        final Core.Literal literal = (Core.Literal) apply.fn;
        if (literal.value == BuiltIn.OP_ELEM) {
          Core.Exp a0 = apply.args().get(0);
          Core.Exp a1 = apply.args().get(1);
          Core.@Nullable Exp e =
              rewriteElem(typeSystem, mapper, goalPats, boundPats, scans,
                  filters, a0, a1, exps2);
          if (e != null) {
            return e;
          }
          throw new AssertionError(exp);
        } else if (literal.value == BuiltIn.OP_EQ) {
          Core.Exp a0 = apply.args().get(0);
          Core.Exp a1 = apply.args().get(1);
          if (a1.op == Op.ID && a0.op != Op.ID) {
            final Core.Exp tmp = a0;
            a0 = a1;
            a1 = tmp;
          }
          Core.Exp a1List =
              core.list(typeSystem, a1.type, ImmutableList.of(a1));
          Core.@Nullable Exp e =
              rewriteElem(typeSystem, mapper, goalPats, boundPats, scans,
                  filters, a0, a1List, exps2);
          if (e != null) {
            return e;
          }
        }
        final List<Core.Exp> filters2 = append(filters, exp);
        return rewrite(mapper, goalPats, boundPats, scans, filters2,
            exps2);
      }
      throw new AssertionError(exp);

    case CASE:
      final Core.Case case_ = (Core.Case) exp;
      if (case_.matchList.size() == 1) {
        // A simple renaming case, e.g. "case (e, j) of (a, b) => #job a = b",
        // boundPats is "{e}" translate as if the expression were "#job e = j",
        // boundPats = "{a}".
        final Core.Match match = case_.matchList.get(0);
        final List<Pair<Core.NamedPat, Core.NamedPat>> names = new ArrayList<>();
        foo(names, match.pat, case_.exp);
        UnaryOperator<Core.NamedPat> mapper2 =
            namedPat -> {
              for (Pair<Core.NamedPat, Core.NamedPat> pair : names) {
                if (pair.right.equals(namedPat)) {
                  namedPat = pair.left;
                }
              }
              return namedPat;
            };
        final List<Core.NamedPat> goalPats2 =
            goalPats.stream().map(mapper).collect(toImmutableList());
        final SortedMap<Core.NamedPat, Core.Exp> boundPats2 =
            new TreeMap<>(boundPats.comparator());
        boundPats.forEach((p, e) -> boundPats2.put(mapper.apply(p), e));
        return rewrite(mapper.andThen(mapper2)::apply, goalPats2, boundPats2,
            scans, filters, plus(match.exp, exps2));
      }
      throw new AssertionError(exp);

    default:
      throw new AssertionError(exp);
    }
  }

  private void foo(List<Pair<Core.NamedPat, Core.NamedPat>> names, Core.Pat pat,
      Core.Exp exp) {
    if (pat.op == Op.ID_PAT) {
      names.add(Pair.of(((Core.Id) exp).idPat, (Core.IdPat) pat));
    } else if (pat.op == Op.TUPLE_PAT) {
      final Core.TuplePat tuplePat = (Core.TuplePat) pat;
      final Core.Tuple tuple = (Core.Tuple) exp;
      for (Pair<Core.Pat, Core.Exp> pair : Pair.zip(tuplePat.args, tuple.args)) {
        foo(names, pair.left, pair.right);
      }
    }
  }

  private Core.@Nullable Exp rewriteElem(TypeSystem typeSystem,
      UnaryOperator<Core.NamedPat> mapper, List<Core.NamedPat> goalPats,
      SortedMap<Core.NamedPat, Core.Exp> boundPats,
      Map<Core.IdPat, Core.Exp> scans,
      List<Core.Exp> filters, Core.Exp a0,
      Core.Exp a1, List<Core.Exp> exps2) {
    if (a0.op == Op.ID) {
      // from ... v suchthat (v elem list)
      final Core.IdPat idPat = (Core.IdPat) ((Core.Id) a0).idPat;
      if (!boundPats.containsKey(idPat)) {
        // "from a, b, c suchthat (b in list-valued-expression)"
        //  --> remove b from unbound
        //     add (b, scans.size) to bound
        //     add list to scans
        // from ... v in list
        final SortedMap<Core.NamedPat, Core.Exp> boundPats2 =
            plus(boundPats, idPat, core.id(idPat));
        final List<Core.NamedPat> goalPats2 =
            intersect(ImmutableList.of(idPat), goalPats); // fails 2f // TODO
//         goalPats; // passes 2f // TODO
        final Map<Core.IdPat, Core.Exp> scans2 = plus(scans, idPat, a1);
        return rewrite(mapper, goalPats2, boundPats2, scans2, filters,
            exps2);
      } else {
        final Core.Exp e = boundPats.get(idPat);
        final List<Core.Exp> filters2 =
            append(filters, core.elem(typeSystem, e, a1));
        return rewrite(mapper, goalPats, boundPats, scans, filters2,
            exps2);
      }
    } else if (a0.op == Op.TUPLE) {
      // from v, w, x suchthat ((v, w, x) elem list)
      //  -->
      //  from e suchthat (e elem list)
      //    yield (e.v, e.w, e.x)
      final Core.Tuple tuple = (Core.Tuple) a0;
      final Core.IdPat idPat =
          core.idPat(((ListType) a1.type).elementType,
              typeSystem.nameGenerator);
      final Core.Id id = core.id(idPat);
      SortedMap<Core.NamedPat, Core.Exp> boundPats2 = boundPats;
      List<Core.Exp> filters2 = filters;
      for (Ord<Core.Exp> arg : Ord.zip(tuple.args)) {
        final Core.Exp e = core.field(typeSystem, id, arg.i);
        if (arg.e instanceof Core.Id) {
          final Core.NamedPat idPat2 = ((Core.Id) arg.e).idPat;
          if (!boundPats2.containsKey(idPat2)) {
            // This variable was not previously bound; bind it.
            boundPats2 = plus(boundPats2, idPat2, e);
          } else {
            // This variable is already bound; now add a filter.
            filters2 =
                append(filters, core.equal(typeSystem, e, arg.e));
          }
        } else {
          filters2 =
              append(filters, core.equal(typeSystem, e, arg.e));
        }
      }
      final Map<Core.IdPat, Core.Exp> scans2 = plus(scans, idPat, a1);
      return rewrite(mapper, goalPats, boundPats2, scans2, filters2,
          exps2);
    } else {
      return null;
    }
  }

  /**
   * Returns an expression as a list of conjunctions.
   *
   * <p>For example
   * {@code conjunctions(a andalso b)}
   * returns [{@code a}, {@code b}] (two elements);
   * {@code conjunctions(a andalso b andalso c)}
   * returns [{@code a}, {@code b}, {@code c}] (three elements);
   * {@code conjunctions(a orelse b)}
   * returns [{@code a orelse b}] (one element);
   * {@code conjunctions(true)}
   * returns [] (no elements);
   * {@code conjunctions(false)}
   * returns [{@code false}] (one element).
   */
  static List<Core.Exp> conjunctions(Core.Exp e) {
    final ImmutableList.Builder<Core.Exp> b = ImmutableList.builder();
    addConjunctions(b, e);
    return b.build();
  }

  private static void addConjunctions(ImmutableList.Builder<Core.Exp> b,
      Core.Exp e) {
    if (e.op == Op.APPLY
        && ((Core.Apply) e).fn.op == Op.FN_LITERAL
        && ((Core.Literal) ((Core.Apply) e).fn).value == BuiltIn.Z_ANDALSO) {
      ((Core.Apply) e).args().forEach(a -> addConjunctions(b, a));
    } else if (e.op != Op.BOOL_LITERAL
        || !((boolean) ((Core.Literal) e).value)) {
      // skip true
      b.add(e);
    }
  }

  static class FromState {
    final Core.From from;
    final List<Core.FromStep> steps = new ArrayList<>();

    FromState(Core.From from) {
      this.from = from;
    }

    Core.FromStep currentStep() {
      // We assume that from.steps are translated 1:1 into steps that get added
      // to this.steps. If steps.size() is N, we are currently working on
      // from.steps.get(N).
      return from.steps.get(steps.size());
    }
  }

  private static class NamedPatUnaryOperator
      implements UnaryOperator<Core.NamedPat> {
    final Core.Pat pat;
    final Core.Exp exp;

    NamedPatUnaryOperator(Core.Pat pat, Core.Exp exp) {
      this.pat = pat;
      this.exp = exp;
    }

    @Override public Core.NamedPat apply(Core.NamedPat p) {
      return foo(pat, exp, p);
    }

    private Core.@Nullable NamedPat foo(Core.Pat pat, Core.Exp exp,
        Core.NamedPat p) {
      if (pat.op == Op.ID_PAT) {
        if (((Core.Id) exp).idPat.name.equals(p.name)) {
          return (Core.IdPat) pat;
        }
        return null;
      } else if (pat.op == Op.TUPLE_PAT) {
        final Core.TuplePat tuplePat = (Core.TuplePat) pat;
        final Core.Tuple tuple = (Core.Tuple) exp;
        for (Pair<Core.Pat, Core.Exp> pair : Pair.zip(tuplePat.args, tuple.args)) {
          final Core.@Nullable NamedPat p2 = foo(pair.left, pair.right, p);
          if (p2 != null) {
            return p2;
          }
        }
        return null;
      } else {
        return null;
      }
    }

  }
}

// End SuchThatShuttle.java
