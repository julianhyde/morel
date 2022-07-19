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
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.Ord;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Iterables;
import org.apache.calcite.util.Holder;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import static net.hydromatic.morel.ast.CoreBuilder.core;
import static net.hydromatic.morel.compile.Resolver.append;
import static net.hydromatic.morel.compile.Resolver.minus;
import static net.hydromatic.morel.compile.Resolver.plus;

import static org.apache.calcite.util.Util.skip;

/**
 * Converts {@code suchThat} to {@code in} wherever possible.
 */
class SuchThatShuttle extends Shuttle {
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

  @Override protected Core.Scan visit(Core.Scan scan) {
    if (scan.op == Op.SUCH_THAT) {
      final ImmutableList.Builder<Core.NamedPat> unboundPats =
          ImmutableList.builder();
      Resolver.flatten(scan.pat).forEach(unboundPats::add);
      final ImmutableSortedMap.Builder<Core.NamedPat, Core.Exp> mapBuilder =
          ImmutableSortedMap.orderedBy(Core.NamedPat.ORDERING);
      final Core.@Nullable Exp rewritten =
          rewrite(unboundPats.build(),
              mapBuilder.build(),
              ImmutableMap.of(),
              ImmutableList.of(), conjunctions(scan.exp));
      return core.scan(Op.INNER_JOIN, scan.bindings,
          scan.pat, rewritten, scan.condition);
    }
    return super.visit(scan);
  }

  private Core.Exp rewrite(List<Core.NamedPat> unboundPats,
      SortedMap<Core.NamedPat, Core.Exp> boundPats,
      Map<Core.IdPat, Core.Exp> scans, List<Core.Exp> filters,
      List<Core.Exp> exps) {
    if (exps.isEmpty()) {
      if (!unboundPats.isEmpty()) {
        throw new CompileException("Cannot implement 'suchthat'; variables "
            + unboundPats + " are not grounded", false, Pos.ZERO);
      }
      final FromBuilder fromBuilder = core.fromBuilder(typeSystem);
      scans.forEach(fromBuilder::scan);
      filters.forEach(fromBuilder::where);
      Core.Exp exp;
      if (boundPats.size() == 1) {
        exp = Iterables.getOnlyElement(boundPats.values());
      } else {
        final SortedMap<String, Type> argNameTypes =
            new TreeMap<>(RecordType.ORDERING);
        boundPats.keySet().forEach(p -> argNameTypes.put(p.name, p.type));
        exp =
            core.tuple(typeSystem.recordType(argNameTypes),
                boundPats.values());
      }
      fromBuilder.yield_(exp);
      return fromBuilder.build();
    }
    final Core.Exp exp = exps.get(0);
    final List<Core.Exp> exps2 = skip(exps);
    if (exp.op == Op.APPLY) {
      Core.Apply apply = (Core.Apply) exp;
      if (apply.fn.op == Op.FN_LITERAL) {
        final Core.Literal literal = (Core.Literal) apply.fn;
        if (literal.value == BuiltIn.OP_ELEM) {
          Core.Exp a0 = apply.args().get(0);
          Core.Exp a1 = apply.args().get(1);
          Core.@Nullable Exp e =
              rewriteElem(typeSystem, unboundPats, boundPats, scans,
                  filters, a0, a1, exps2);
          if (e != null) {
            return e;
          }
        } else if (literal.value == BuiltIn.OP_EQ) {
          Core.Exp a0 = apply.args().get(0);
          Core.Exp a1 = apply.args().get(1);
          Core.Exp a1List =
              core.list(typeSystem, a1.type, ImmutableList.of(a1));
          Core.@Nullable Exp e =
              rewriteElem(typeSystem, unboundPats, boundPats, scans,
                  filters, a0, a1List, exps2);
          if (e != null) {
            return e;
          }
        }
        final List<Core.Exp> filters2 = append(filters, exp);
        return rewrite(unboundPats, boundPats, scans, filters2, exps2);
      }
    }
    throw new AssertionError(exp);
  }

  private Core.@Nullable Exp rewriteElem(TypeSystem typeSystem,
      List<Core.NamedPat> unboundPats,
      SortedMap<Core.NamedPat, Core.Exp> boundPats,
      Map<Core.IdPat, Core.Exp> scans,
      List<Core.Exp> filters, Core.Exp a0,
      Core.Exp a1, List<Core.Exp> exps2) {
    if (a0.op == Op.ID) {
      // from ... v suchthat (v elem list)
      final Core.IdPat idPat = (Core.IdPat) ((Core.Id) a0).idPat;
      if (unboundPats.contains(idPat)) {
        // "from a, b, c suchthat (b in list-valued-expression)"
        //  --> remove b from unbound
        //     add (b, scans.size) to bound
        //     add list to scans
        // from ... v in list
        final List<Core.NamedPat> unboundPats2 =
            minus(unboundPats, idPat);
        final SortedMap<Core.NamedPat, Core.Exp> boundPats2 =
            plus(boundPats, idPat, core.id(idPat));
        final Map<Core.IdPat, Core.Exp> scans2 =
            plus(scans, idPat, a1);
        return rewrite(unboundPats2, boundPats2, scans2, filters, exps2);
      } else {
        final Core.Exp e = boundPats.get(idPat);
        final List<Core.Exp> filters2 =
            append(filters, core.elem(typeSystem, e, a1));
        return rewrite(unboundPats, boundPats, scans, filters2, exps2);
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
      List<Core.NamedPat> unboundPats2 = unboundPats;
      List<Core.Exp> filters2 = filters;
      for (Ord<Core.Exp> arg : Ord.zip(tuple.args)) {
        final Core.Exp e = core.field(typeSystem, id, arg.i);
        if (arg.e instanceof Core.Id) {
          final Core.NamedPat idPat2 = ((Core.Id) arg.e).idPat;
          if (unboundPats2.contains(idPat2)) {
            // This variable was not previously bound; bind it.
            unboundPats2 = minus(unboundPats2, idPat2);
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
      return rewrite(unboundPats2, boundPats2, scans2, filters2, exps2);
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
}

// End SuchThatShuttle.java
