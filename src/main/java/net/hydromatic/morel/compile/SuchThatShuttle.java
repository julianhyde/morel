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
import net.hydromatic.morel.ast.Shuttle;
import net.hydromatic.morel.ast.Visitor;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.ListType;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.ImmutablePairList;
import net.hydromatic.morel.util.Ord;
import net.hydromatic.morel.util.Pair;
import net.hydromatic.morel.util.PairList;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import org.apache.calcite.util.Holder;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import static net.hydromatic.morel.ast.CoreBuilder.core;
import static net.hydromatic.morel.util.Pair.forEach;
import static net.hydromatic.morel.util.Static.append;
import static net.hydromatic.morel.util.Static.plus;
import static net.hydromatic.morel.util.Static.skip;
import static net.hydromatic.morel.util.Static.transformEager;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.Iterables.getOnlyElement;

/**
 * Converts unbounded variables to bounded variables.
 *
 * <p>For example, converts
 *
 * <blockquote><pre>{@code
 * from e
 *   where e elem #dept scott
 * }</pre></blockquote>
 *
 * <p>to
 *
 * <blockquote><pre>{@code
 * from e in #dept scott
 * }</pre></blockquote>
 */
class SuchThatShuttle extends Shuttle {
  final @Nullable Environment env;

  SuchThatShuttle(TypeSystem typeSystem, @Nullable Environment env) {
    super(typeSystem);
    this.env = env;
  }

  static boolean containsUnbounded(Core.Decl decl) {
    final Holder<Boolean> found = Holder.of(false);
    decl.accept(new Visitor() {
      @Override protected void visit(Core.Scan scan) {
        super.visit(scan);
        if (Extents.isInfinite(scan.exp)) {
          found.set(true);
        }
      }
    });
    return found.get();
  }

  @Override protected Core.Exp visit(Core.From from) {
    final Core.From from2 = new FromVisitor(typeSystem, env).visit(from);
    return from2.equals(from) ? from : from2;
  }

  /** Workspace for converting unbounded variables in a particular
   * {@link Core.From} to bounded scans. */
  static class FromVisitor {
    final TypeSystem typeSystem;
    final FromBuilder fromBuilder;
    final List<Core.Exp> satisfiedFilters = new ArrayList<>();

    FromVisitor(TypeSystem typeSystem, @Nullable Environment env) {
      this.typeSystem = typeSystem;
      this.fromBuilder = core.fromBuilder(typeSystem, env);
    }

    Core.From visit(Core.From from) {
      final List<Core.FromStep> steps = from.steps;
      final DeferredStepList deferredScans =
          DeferredStepList.create(typeSystem, steps);

      Environment env = Environments.empty();
      final PairList<Core.IdPat, Core.Exp> idPats = PairList.of();
      for (int i = 0; i < steps.size(); i++) {
        final Core.FromStep step = steps.get(i);
        switch (step.op) {
        case SCAN:
        case INNER_JOIN:
          final Core.Scan scan = (Core.Scan) step;
          if (Extents.isInfinite(scan.exp)) {
            final Core.Exp rewritten;
            if (false) { // TODO remove dead code (lots of it!)
              final SortedMap<Core.NamedPat, Core.Exp> boundPats =
                  new TreeMap<>(Core.NamedPat.ORDERING);
              fromBuilder.bindings().forEach(b ->
                  boundPats.put(b.id, core.id(b.id)));
              rewritten = rewrite0(boundPats, scan, skip(steps, i));
            } else {
              final int idPatCount = idPats.size();
              rewritten = rewrite1(scan, skip(steps, i), idPats);

              // Create a scan for any new variables introduced by rewrite.
              idPats.forEachIndexed((j, pat, extent) -> {
                if (j >= idPatCount) {
                  fromBuilder.scan(pat, extent);
                }
              });
            }
            deferredScans.scan(env, scan.pat, rewritten, scan.condition);
          } else {
            deferredScans.scan(env, scan.pat, scan.exp);
          }
          break;

        case YIELD:
          final Core.Yield yield = (Core.Yield) step;
          killTemporaryScans(idPats);
          deferredScans.flush(fromBuilder);
          fromBuilder.yield_(false, yield.bindings, yield.exp);
          break;

        case WHERE:
          final Core.Where where = (Core.Where) step;
          Core.Exp condition =
              core.subTrue(typeSystem, where.exp, satisfiedFilters);
          deferredScans.where(env, condition);
          break;

        case GROUP:
          final Core.Group group = (Core.Group) step;
          killTemporaryScans(idPats);
          deferredScans.flush(fromBuilder);
          fromBuilder.group(group.groupExps, group.aggregates);
          break;

        case ORDER:
          final Core.Order order = (Core.Order) step;
          killTemporaryScans(idPats);
          deferredScans.flush(fromBuilder);
          fromBuilder.order(order.orderItems);
          break;

        default:
          throw new AssertionError(step.op);
        }
        env = Environments.empty().bindAll(step.bindings);
      }
      deferredScans.flush(fromBuilder);
      killTemporaryScans(idPats);
      return fromBuilder.build();
    }

    private void killTemporaryScans(PairList<Core.IdPat, Core.Exp> idPats) {
      if (idPats.isEmpty()) {
        return;
      }
      final PairList<String, Core.Id> nameExps = PairList.of();
      for (Binding b : fromBuilder.bindings()) {
        Core.IdPat id = (Core.IdPat) b.id;
        if (!idPats.leftList().contains(id)) {
          nameExps.add(id.name, core.id(id));
        }
      }
      if (nameExps.size() == 1) {
        fromBuilder.yield_(false, null, nameExps.get(0).getValue());
      } else {
        fromBuilder.yield_(false, null, core.record(typeSystem, nameExps));
      }
      idPats.clear();
    }

    private Core.Exp rewrite0(SortedMap<Core.NamedPat, Core.Exp> boundPats,
        Core.Scan scan, List<? extends Core.FromStep> laterSteps) {
      try {
        final Map<Core.IdPat, Core.Exp> scans = ImmutableMap.of();
        final List<Core.Exp> filters = ImmutableList.of();
        final UnaryOperator<Core.NamedPat> originalPats =
            UnaryOperator.identity();
        final List<Core.Exp> inFilters = new ArrayList<>();
        for (Core.FromStep step : laterSteps) {
          if (step == scan) {
            // continue to second step
          } else if (step.op == Op.WHERE) {
            core.flattenAnd(((Core.Where) step).exp, inFilters::add);
          } else {
            // If this step is a scan, any subsequent 'where' steps might be
            // invalid because they reference variables defined in the scan.
            // (They might not... we should think more about that.)
            break;
          }
        }

        return rewrite(scan, originalPats, boundPats, scans, filters,
            inFilters);
      } catch (RewriteFailedException e) {
        // We could not rewrite.
        // Try a different approach.
        // Generate an iterator over all values of all variables,
        // then filter.
        return rewrite1(scan, laterSteps, PairList.of());
      }
    }

    private Core.From rewrite1(Core.Scan scan,
        List<? extends Core.FromStep> laterSteps,
        PairList<Core.IdPat, Core.Exp> idPats) {
      final Extents.Analysis analysis =
          Extents.create(typeSystem, scan.pat, ImmutableSortedMap.of(),
              laterSteps, idPats);
      satisfiedFilters.addAll(analysis.satisfiedFilters);
      final FromBuilder fromBuilder = core.fromBuilder(typeSystem);
      fromBuilder.scan(scan.pat, analysis.extentExp);
      return fromBuilder.build();
    }

    /** Rewrites a "from var where condition" expression to a
     * "from var in list" expression; returns null if no rewrite is possible.
     *
     * <p>The "filters" argument contains a list of conditions to be applied
     * after generating rows. For example,
     * "from x where x % 2 = 1 andalso x elem list"
     * becomes "from x in list where x % 2 = 1" with the filter "x % 2 = 1".
     *
     * <p>The "scans" argument contains scans to be added. For example,
     * "from x where x elem list" adds the scan "(x, list)".
     *
     * <p>The "boundPats" argument contains expressions that are bound to
     * variables. For example, "from x, y where (x, y) elem list" will add the
     * scan "(v0, list)" and boundPats [(x, #1 v0), (y, #2 v0)], as if the user
     * had written "from v0 in list yield {x = #1 v0, y = #2 v0}".
     *
     * @param mapper Renames variables
     * @param boundPats Variables that have been bound to a list
     * @param scans Scans (joins) to be appended to the resulting "from"
     * @param filters Filters to be appended as "where" in the resulting "from"
     * @param exps The condition, decomposed into conjunctions
     * @return Rewritten expression
     */
    private Core.Exp rewrite(Core.Scan scan,
        UnaryOperator<Core.NamedPat> mapper,
        SortedMap<Core.NamedPat, Core.Exp> boundPats,
        Map<Core.IdPat, Core.Exp> scans, List<Core.Exp> filters,
        List<Core.Exp> exps) {
      if (exps.isEmpty()) {
        final SortedMap<Core.NamedPat, Core.Exp> boundPats2 = new TreeMap<>();
        boundPats.forEach((p, e) -> {
          final Core.NamedPat p2 = mapper.apply(p);
          final Core.Exp e0 = boundPats2.get(p2);
          if (e0 == null) {
            boundPats2.put(p2, e);
          } else if (Extents.isInfinite(e0)) {
            boundPats2.put(p2, core.andAlso(typeSystem, e0, e));
          }
        });

        final PairList<String, Core.Exp> nameExps = PairList.of();
        if (scans.isEmpty()) {
          final Extents.Analysis extent =
              Extents.create(typeSystem, scan.pat, boundPats2,
                  transformEager(filters,
                      f -> core.where(ImmutableList.of(), f)),
                  ImmutablePairList.of());
          final Set<Core.NamedPat> unboundPats = extent.unboundPats();
          if (!unboundPats.isEmpty()) {
            throw new RewriteFailedException("Cannot implement; "
                + "variables " + unboundPats + " are not grounded" + "]");
          }
          boundPats2.forEach((p, e) -> {
            if (extent.goalPats.contains(p)) {
              nameExps.add(p.name, e);
            }
          });
        } else {
          boundPats2.forEach((p, e) -> nameExps.add(p.name, e));
        }

        final FromBuilder fromBuilder = core.fromBuilder(typeSystem);
        scans.forEach(fromBuilder::scan);
        filters.forEach(fromBuilder::where);
        fromBuilder.yield_(nameExps.size() == 1
            ? getOnlyElement(nameExps).getValue()
            : core.record(typeSystem, nameExps));
        return fromBuilder.build();
      }
      final Core.Exp exp = exps.get(0);
      final List<Core.Exp> exps2 = skip(exps);
      switch (exp.op) {
      case APPLY:
        final Core.Apply apply = (Core.Apply) exp;
        if (exp.isCallTo(BuiltIn.OP_ELEM)) {
          Core.Exp a0 = apply.args().get(0);
          Core.Exp a1 = apply.args().get(1);
          Core.@Nullable Exp e =
              rewriteElem(scan, mapper, boundPats, scans, filters, a0, a1,
                  exps2);
          if (e != null) {
            return e;
          }
          throw new AssertionError(exp);
        }
        if (exp.isCallTo(BuiltIn.OP_EQ)) {
          Core.Exp a0 = apply.args().get(0);
          Core.Exp a1 = apply.args().get(1);
          if (a1.op == Op.ID && a0.op != Op.ID) {
            final Core.Exp tmp = a0;
            a0 = a1;
            a1 = tmp;
          }
          Core.Exp a1List = core.list(typeSystem, a1);
          Core.@Nullable Exp e =
              rewriteElem(scan, mapper, boundPats, scans,
                  filters, a0, a1List, exps2);
          if (e != null) {
            return e;
          }
        }
        final List<Core.Exp> filters2 = append(filters, exp);
        return rewrite(scan, mapper, boundPats, scans, filters2,
            exps2);

      case CASE:
        final Core.Case case_ = (Core.Case) exp;
        if (case_.matchList.size() == 1) {
          // A simple renaming case, e.g. "case (e, j) of (a, b) => #job a = b",
          // boundPats is "{e}" translate as if the expression were "#job e = j",
          // boundPats = "{a}".
          final Core.Match match = case_.matchList.get(0);
          final SortedMap<Core.NamedPat, Core.Exp> boundPats2 =
              new TreeMap<>(boundPats.comparator());
          boundPats.forEach((p, e) -> boundPats2.put(mapper.apply(p), e));
          final PatMap patMap = PatMap.of(match.pat, case_.exp);
          return rewrite(scan, mapper.andThen(patMap::apply)::apply, boundPats2,
              scans, filters, plus(match.exp, exps2));
        }
        break;
      }

      throw new RewriteFailedException("not implemented: suchthat " + exp.op
          + " [" + exp + "]");
    }

    private Core.@Nullable Exp rewriteElem(Core.Scan scan,
        UnaryOperator<Core.NamedPat> mapper,
        SortedMap<Core.NamedPat, Core.Exp> boundPats,
        Map<Core.IdPat, Core.Exp> scans,
        List<Core.Exp> filters, Core.Exp a0,
        Core.Exp a1, List<Core.Exp> exps2) {
      if (a0.op == Op.ID) {
        // from ... v where (v elem list)
        final Core.IdPat idPat = (Core.IdPat) ((Core.Id) a0).idPat;
        if (!boundPats.containsKey(idPat)) {
          // "from a, b, c where (b in list-valued-expression)"
          //  --> remove b from unbound
          //     add (b, scans.size) to bound
          //     add list to scans
          // from ... v in list
          final SortedMap<Core.NamedPat, Core.Exp> boundPats2 =
              plus(boundPats, idPat, core.id(idPat));
          final Map<Core.IdPat, Core.Exp> scans2 = plus(scans, idPat, a1);
          return rewrite(scan, mapper, boundPats2, scans2, filters, exps2);
        } else {
          final Core.Exp e = boundPats.get(idPat);
          final List<Core.Exp> filters2 =
              append(filters, core.elem(typeSystem, e, a1));
          return rewrite(scan, mapper, boundPats, scans, filters2, exps2);
        }
      } else if (a0.op == Op.TUPLE) {
        // from v, w, x where ((v, w, x) elem list)
        //   -->
        // from e in list
        //   yield {e.v, e.w, e.x}
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
              filters2 = append(filters, core.equal(typeSystem, e, arg.e));
            }
          } else {
            filters2 = append(filters, core.equal(typeSystem, e, arg.e));
          }
        }
        final Map<Core.IdPat, Core.Exp> scans2 = plus(scans, idPat, a1);
        return rewrite(scan, mapper, boundPats2, scans2, filters2, exps2);
      } else {
        return null;
      }
    }
  }

  /** Maps patterns from their name in the "from" to their name after a sequence
   * of renames.
   *
   * <p>For example, in "case (x, y) of (a, b) => a + b", "x" is renamed to "a"
   * and "y" is renamed to "b". */
  private static class PatMap {
    private final ImmutableMap<Core.NamedPat, Core.NamedPat> map;

    PatMap(ImmutableMap<Core.NamedPat, Core.NamedPat> map) {
      this.map = map;
    }

    static PatMap of(Core.Pat pat, Core.Exp exp) {
      final ImmutableMap.Builder<Core.NamedPat, Core.NamedPat> builder =
          ImmutableMap.builder();
      populate(pat, exp, builder);
      return new PatMap(builder.build());
    }

    private static void populate(Core.Pat pat, Core.Exp exp,
        ImmutableMap.Builder<Core.NamedPat, Core.NamedPat> nameBuilder) {
      switch (pat.op) {
      case ID_PAT:
        nameBuilder.put(Pair.of(((Core.Id) exp).idPat, (Core.IdPat) pat));
        break;
      case TUPLE_PAT:
        final Core.TuplePat tuplePat = (Core.TuplePat) pat;
        final Core.Tuple tuple = (Core.Tuple) exp;
        forEach(tuplePat.args, tuple.args,
            (pat2, exp2) -> populate(pat2, exp2, nameBuilder));
        break;
      }
    }

    Core.NamedPat apply(Core.NamedPat p) {
      for (Map.Entry<Core.NamedPat, Core.NamedPat> pair : map.entrySet()) {
        if (pair.getValue().equals(p)) {
          p = pair.getKey();
        }
      }
      return p;
    }
  }

  /** Signals that we could not rewrite. */
  private static class RewriteFailedException extends RuntimeException {
    RewriteFailedException(String message) {
      super(message);
    }
  }

  /** Maintains a list of steps that have not been applied yet.
   *
   * <p>Holds the state necessary for a classic topological sort algorithm:
   * For each node, keep the list of unresolved forward references.
   * After each reference is resolved, remove it from each node's list.
   * Output each node as its unresolved list becomes empty.
   * The topological sort is stable.
   */
  static class DeferredStepList {
    final PairList<Set<Core.Pat>, Consumer<FromBuilder>> steps = PairList.of();
    final FreeFinder freeFinder;
    final List<Core.NamedPat> refs;

    DeferredStepList(FreeFinder freeFinder, List<Core.NamedPat> refs) {
      this.freeFinder = freeFinder;
      this.refs = refs;
    }

    static DeferredStepList create(TypeSystem typeSystem,
        List<Core.FromStep> steps) {
      final ImmutableSet<Core.Pat> forwardRefs =
          steps.stream()
              .filter(step -> step instanceof Core.Scan)
              .map(step -> ((Core.Scan) step).pat)
              .collect(toImmutableSet());
      final List<Core.NamedPat> refs = new ArrayList<>();
      final Consumer<Core.NamedPat> consumer = p -> {
        if (forwardRefs.contains(p)) {
          refs.add(p);
        }
      };
      final FreeFinder freeFinder =
          new FreeFinder(typeSystem, Environments.empty(),
              new ArrayDeque<>(), consumer);
      return new DeferredStepList(freeFinder, refs);
    }

    void scan(Environment env, Core.Pat pat, Core.Exp exp, Core.Exp condition) {
      final Set<Core.Pat> unresolvedRefs = unresolvedRefs(env, exp);
      steps.add(unresolvedRefs, fromBuilder -> {
        fromBuilder.scan(pat, exp, condition);
        resolve(pat);
      });
    }

    void scan(Environment env, Core.Pat pat, Core.Exp exp) {
      final Set<Core.Pat> unresolvedRefs = unresolvedRefs(env, exp);
      steps.add(unresolvedRefs, fromBuilder -> {
        fromBuilder.scan(pat, exp);
        resolve(pat);
      });
    }

    void where(Environment env, Core.Exp condition) {
      final Set<Core.Pat> unresolvedRefs = unresolvedRefs(env, condition);
      steps.add(unresolvedRefs,
          fromBuilder -> fromBuilder.where(condition));
    }

    private Set<Core.Pat> unresolvedRefs(Environment env, Core.Exp exp) {
      refs.clear();
      exp.accept(freeFinder.push(env));
      return new LinkedHashSet<>(refs);
    }

    /** Marks that a pattern has now been defined.
     *
     * <p>After this method, it is possible that some steps might have no
     * unresolved references. Those steps are now ready to add to the
     * builder. */
    void resolve(Core.Pat pat) {
      steps.forEach((unresolvedRefs, consumer) -> unresolvedRefs.remove(pat));
    }

    void flush(FromBuilder fromBuilder) {
      // Are there any scans that had forward references previously but
      // whose references are now all satisfied? Add them to the builder.
      for (;;) {
        int j = steps.firstMatch((unresolvedRefs, consumer) ->
            unresolvedRefs.isEmpty());
        if (j < 0) {
          break;
        }
        final Map.Entry<Set<Core.Pat>, Consumer<FromBuilder>> step =
            steps.remove(j);
        step.getValue().accept(fromBuilder);
      }
    }
  }

  /** Finds free variables in an expression. */
  private static class FreeFinder extends EnvVisitor {
    final Consumer<Core.NamedPat> consumer;

    FreeFinder(TypeSystem typeSystem, Environment env,
        Deque<FromContext> fromStack, Consumer<Core.NamedPat> consumer) {
      super(typeSystem, env, fromStack);
      this.consumer = consumer;
    }

    @Override protected EnvVisitor push(Environment env) {
      return new FreeFinder(typeSystem, env, fromStack, consumer);
    }

    @Override protected void visit(Core.Id id) {
      if (env.getOpt(id.idPat) == null) {
        consumer.accept(id.idPat);
      }
    }
  }
}

// End SuchThatShuttle.java
