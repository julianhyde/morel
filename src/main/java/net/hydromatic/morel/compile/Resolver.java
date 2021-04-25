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

import org.apache.calcite.util.Util;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;

import net.hydromatic.morel.ast.Ast;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.type.DataType;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.util.ConsList;
import net.hydromatic.morel.util.Pair;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static net.hydromatic.morel.ast.CoreBuilder.core;

/** Converts AST expressions to Core expressions. */
public class Resolver {
  final TypeMap typeMap;

  public Resolver(TypeMap typeMap) {

    this.typeMap = typeMap;
  }

  public Core.Decl toCore(Ast.Decl node) {
    switch (node.op) {
    case VAL_DECL:
      return toCore((Ast.ValDecl) node);

    default:
      throw new AssertionError("unknown decl [" + node.op + ", " + node + "]");
    }
  }

  public Core.ValDecl toCore(Ast.ValDecl valDecl) {
    if (valDecl.valBinds.size() > 1) {
      // Transform "let val v1 = e1 and v2 = e2 in e"
      // to "let val (v1, v2) = (e1, e2) in e"
      final Map<Ast.Pat, Ast.Exp> matches = new LinkedHashMap<>();
      boolean rec = false;
      for (Ast.ValBind valBind : valDecl.valBinds) {
        flatten(matches, valBind.pat, valBind.e);
        rec |= valBind.rec;
      }
      final Pos pos = valDecl.pos;
      final List<Type> types = new ArrayList<>();
      final List<Core.Pat> pats = new ArrayList<>();
      final List<Core.Exp> exps = new ArrayList<>();
      matches.forEach((pat, exp) -> {
        Type type = typeMap.getType(pat);
        types.add(type);
        pats.add(toCore(pat));
        exps.add(toCore(exp));
      });
      final Type tupleType = typeMap.typeSystem.tupleType();
      final Core.Pat pat = core.tuplePat(tupleType, pats);
      final Core.Exp e2 = core.tuple(tupleType, exps);
      return core.valDecl(core.valBind(rec, pat, e2));
    } else {
      Ast.ValBind valBind = valDecl.valBinds.get(0);
      return core.valDecl(
          core.valBind(valBind.rec, toCore(valBind.pat), toCore(valBind.e)));
    }
  }

  private Core.DatatypeDecl toCore(Ast.DatatypeDecl datatypeDecl) {
    return core.datatypeDecl(Util.transform(datatypeDecl.binds, this::toCore));
  }

  private DataType toCore(Ast.DatatypeBind bind) {
    return (DataType) typeMap.typeSystem.lookup(bind.name.name);
  }

  private Core.Exp toCore(Ast.Exp e) {
    throw new AssertionError();
  }

  private Core.LetExp toCore(Ast.LetExp let) {
    return flattenLet(let.decls, let.e);
  }

  private Core.LetExp flattenLet(List<Ast.Decl> decls, Ast.Exp e) {
    final Core.Exp e2 = decls.size() == 1
        ? toCore(e)
        : flattenLet(decls.subList(1, decls.size()), e);
    return core.let(toCore(decls.get(0)), e2);
  }

  private void flatten(Map<Ast.Pat, Ast.Exp> matches,
      Ast.Pat pat, Ast.Exp exp) {
    switch (pat.op) {
    case TUPLE_PAT:
      final Ast.TuplePat tuplePat = (Ast.TuplePat) pat;
      if (exp.op == Op.TUPLE) {
        final Ast.Tuple tuple = (Ast.Tuple) exp;
        Pair.forEach(tuplePat.args, tuple.args,
            (p, e) -> flatten(matches, p, e));
        break;
      }
      // fall through
    default:
      matches.put(pat, exp);
    }
  }

  /** Converts a pattern to Core.
   *
   * <p>Expands a pattern if it is a record pattern that has an ellipsis
   * or if the arguments are not in the same order as the labels in the type. */
  private Core.Pat toCore(Ast.Pat pat) {
    final Type type = typeMap.getType(pat);
    switch (pat.op) {
    case ID_PAT:
      final Ast.IdPat idPat = (Ast.IdPat) pat;
      if (type.op() == Op.DATA_TYPE
          && ((DataType) type).typeConstructors.containsKey(idPat.name)) {
        return core.con0Pat((DataType) type, idPat.name);
      }
      return core.idPat(type, idPat.name);

    case RECORD_PAT:
      final RecordType recordType = (RecordType) type;
      final Ast.RecordPat recordPat = (Ast.RecordPat) pat;
      final Map<String, Core.Pat> args = new LinkedHashMap<>();
      recordType.argNameTypes.forEach((label, argType) -> {
        final Ast.Pat argPat = recordPat.args.get(label);
        final Core.Pat corePat = argPat != null ? toCore(argPat)
            : core.wildcardPat(argType);
        args.put(label, corePat);
      });
      return core.recordPat(type, recordPat.ellipsis, args);

    default:
      throw new AssertionError("unknown pat " + pat.op);
    }
  }

  Core.From toCore(Ast.From from) {
    final Map<Core.Pat, Core.Exp> sources = new LinkedHashMap<>();
    from.sources.forEach((pat, exp) -> sources.put(toCore(pat), toCore(exp)));
    return fromStepToCore(sources, from.steps,
        ImmutableList.of(), from.yieldExp);
  }

  /** Returns a list with one element appended.
   *
   * @see ConsList */
  private static <E> List<E> append(List<E> list, E e) {
    return ImmutableList.<E>builder().addAll(list).add(e).build();
  }

  private Core.From fromStepToCore(Map<Core.Pat, Core.Exp> sources,
      List<Ast.FromStep> steps, List<Core.FromStep> coreSteps,
      Ast.Exp yieldExp) {
    if (steps.isEmpty()) {
      final Core.Exp coreYieldExp = toCore(yieldExp);
      return core.from(sources, coreSteps, coreYieldExp);
    }
    final Ast.FromStep step = steps.get(0);
    switch (step.op) {
    case WHERE:
      final Ast.Where where = (Ast.Where) step;
      final Core.FromStep coreWhere = core.where(toCore(where.exp));
      return fromStepToCore(sources, Util.skip(steps),
          append(coreSteps, coreWhere), yieldExp);

    case ORDER:
      final Ast.Order order = (Ast.Order) step;
      final Core.FromStep coreOrder =
          core.order(Util.transform(order.orderItems, this::toCore));
      return fromStepToCore(sources, Util.skip(steps),
          append(coreSteps, coreOrder), yieldExp);

    case GROUP:
      final Ast.Group group = (Ast.Group) step;
      final ImmutableSortedMap.Builder<String, Core.Exp> groupExps =
          ImmutableSortedMap.orderedBy(RecordType.ORDERING);
      final ImmutableSortedMap.Builder<String, Core.Aggregate> aggregates =
          ImmutableSortedMap.orderedBy(RecordType.ORDERING);
      Pair.forEach(group.groupExps, (id, exp) ->
          groupExps.put(id.name, toCore(exp)));
      group.aggregates.forEach(aggregate ->
          aggregates.put(aggregate.id.name, toCore(aggregate)));
      final Core.FromStep coreGroup =
          core.group(groupExps.build(), aggregates.build());
      return fromStepToCore(sources, Util.skip(steps),
          append(coreSteps, coreGroup), yieldExp);

    default:
      throw new AssertionError("unknown step type " + step.op);
    }
  }

  private Core.Aggregate toCore(Ast.Aggregate aggregate) {
    return core.aggregate(typeMap.getType(aggregate),
        toCore(aggregate.aggregate),
        aggregate.argument == null ? null : toCore(aggregate.argument));
  }

  private Core.OrderItem toCore(Ast.OrderItem orderItem) {
    return core.orderItem(toCore(orderItem.exp), orderItem.direction);
  }

}

// End Resolver.java
