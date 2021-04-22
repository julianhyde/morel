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
package net.hydromatic.morel.core;

import net.hydromatic.morel.core.Core;
import net.hydromatic.morel.core.CoreNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static net.hydromatic.morel.core.CoreBuilder.core;

/** Visits and transforms syntax trees. */
public class CoreShuttle {
  private <E extends CoreNode> List<E> visitList(List<E> nodes) {
    final List<E> list = new ArrayList<>();
    for (E node : nodes) {
      list.add((E) node.accept(this));
    }
    return list;
  }

  private <K, E extends CoreNode> Map<K, E> visitMap(Map<K, E> nodes) {
    final Map<K, E> map = new LinkedHashMap<>();
    nodes.forEach((k, v) -> map.put(k, (E) v.accept(this)));
    return map;
  }

  // expressions

  protected Core.Exp visit(Core.Literal literal) {
    return literal; // leaf
  }

//  protected Core.Id visit(Core.Id id) {
//    return id; // leaf
//  }
//
//  protected Core.Exp visit(Core.AnnotatedExp annotatedExp) {
//    return core.annotatedExp(annotatedExp.pos,
//        annotatedExp.type.accept(this),
//        annotatedExp.e.accept(this));
//  }
//
//  protected Core.Exp visit(Core.If anIf) {
//    return core.ifThenElse(anIf.pos, anIf.condition.accept(this),
//        anIf.ifTrue.accept(this), anIf.ifFalse.accept(this));
//  }
//
//  protected Core.LetExp visit(Core.LetExp e) {
//    return core.let(e.pos, visitList(e.decls), e.e);
//  }
//
//  protected Core.Exp visit(Core.Case kase) {
//    return core.caseOf(kase.pos, kase.e.accept(this),
//        visitList(kase.matchList));
//  }
//
//  // calls
//
//  protected Core.Exp visit(Core.InfixCall infixCall) {
//    return core.infixCall(infixCall.pos, infixCall.op,
//        infixCall.a0.accept(this), infixCall.a1.accept(this));
//  }
//
//  public Core.Exp visit(Core.PrefixCall prefixCall) {
//    return core.prefixCall(prefixCall.pos, prefixCall.op,
//        prefixCall.a.accept(this));
//  }
//
//  // patterns
//
//  protected Core.Pat visit(Core.IdPat idPat) {
//    return idPat; // leaf
//  }
//
//  protected Core.Pat visit(Core.LiteralPat literalPat) {
//    return literalPat; // leaf
//  }
//
//  protected Core.Pat visit(Core.WildcardPat wildcardPat) {
//    return wildcardPat; // leaf
//  }
//
//  protected Core.Pat visit(Core.InfixPat infixPat) {
//    return infixPat.copy(infixPat.p0.accept(this), infixPat.p1.accept(this));
//  }
//
//  protected Core.Pat visit(Core.TuplePat tuplePat) {
//    return tuplePat.copy(visitList(tuplePat.args));
//  }
//
//  protected Core.Pat visit(Core.ListPat listPat) {
//    return listPat.copy(visitList(listPat.args));
//  }
//
//  protected Core.Pat visit(Core.RecordPat recordPat) {
//    return recordPat.copy(recordPat.ellipsis, visitMap(recordPat.args));
//  }
//
//  protected Core.Pat visit(Core.AnnotatedPat annotatedPat) {
//    return annotatedPat.copy(annotatedPat.pat.accept(this),
//        annotatedPat.type.accept(this));
//  }
//
//  public Core.ConPat visit(Core.ConPat conPat) {
//    return conPat.copy(conPat.tyCon.accept(this), conPat.pat.accept(this));
//  }
//
//  public Core.Con0Pat visit(Core.Con0Pat con0Pat) {
//    return con0Pat.copy(con0Pat.tyCon.accept(this));
//  }
//
//  // value constructors
//
//  protected Core.Exp visit(Core.Tuple tuple) {
//    return core.tuple(tuple.pos, visitList(tuple.args));
//  }
//
//  protected Core.List visit(Core.List list) {
//    return core.list(list.pos, visitList(list.args));
//  }
//
//  protected Core.Exp visit(Core.Record record) {
//    return core.record(record.pos, visitMap(record.args));
//  }
//
//  // functions and matches
//
//  protected Core.Fn visit(Core.Fn fn) {
//    return core.fn(fn.pos, visitList(fn.matchList));
//  }
//
//  protected Core.Apply visit(Core.Apply apply) {
//    return core.apply(apply.fn.accept(this), apply.arg.accept(this));
//  }
//
//  protected Core.Exp visit(Core.RecordSelector recordSelector) {
//    return recordSelector; // leaf
//  }
//
//  protected Core.Match visit(Core.Match match) {
//    return core.match(match.pos, match.pat.accept(this), match.e.accept(this));
//  }
//
//  // types
//
//  protected Core.Type visit(Core.NamedType namedType) {
//    return namedType; // leaf
//  }
//
//  protected Core.TyVar visit(Core.TyVar tyVar) {
//    return tyVar; // leaf
//  }
//
//  // declarations
//
//  protected Core.Decl visit(Core.FunDecl funDecl) {
//    return core.funDecl(funDecl.pos, visitList(funDecl.funBinds));
//  }
//
//  protected Core.FunBind visit(Core.FunBind funBind) {
//    return core.funBind(funBind.pos, visitList(funBind.matchList));
//  }
//
//  protected Core.FunMatch visit(Core.FunMatch funMatch) {
//    return core.funMatch(funMatch.pos, funMatch.name,
//        visitList(funMatch.patList), funMatch.e.accept(this));
//  }
//
//  protected Core.ValDecl visit(Core.ValDecl valDecl) {
//    return core.valDecl(valDecl.pos, visitList(valDecl.valBinds));
//  }
//
//  protected Core.ValBind visit(Core.ValBind valBind) {
//    return core.valBind(valBind.pos, valBind.rec, valBind.pat, valBind.e);
//  }
//
//  public Core.Exp visit(Core.From from) {
//    return core.from(from.pos, from.sources, from.steps, from.yieldExp);
//  }
//
//  public CoreNode visit(Core.Order order) {
//    return core.order(order.pos, order.orderItems);
//  }
//
//  public CoreNode visit(Core.OrderItem orderItem) {
//    return core.orderItem(orderItem.pos, orderItem.exp, orderItem.direction);
//  }
//
//  public CoreNode visit(Core.Where where) {
//    return core.where(where.pos, where.exp.accept(this));
//  }
//
//  public CoreNode visit(Core.Group group) {
//    return core.group(group.pos, group.groupExps, group.aggregates);
//  }
//
//  public CoreNode visit(Core.Aggregate aggregate) {
//    return core.aggregate(aggregate.pos, aggregate.aggregate, aggregate.argument,
//        aggregate.id);
//  }
//
//  public Core.DatatypeDecl visit(Core.DatatypeDecl datatypeDecl) {
//    return core.datatypeDecl(datatypeDecl.pos, visitList(datatypeDecl.binds));
//  }
//
//  public Core.DatatypeBind visit(Core.DatatypeBind datatypeBind) {
//    return core.datatypeBind(datatypeBind.pos, datatypeBind.name.accept(this),
//        visitList(datatypeBind.tyVars), visitList(datatypeBind.tyCons));
//  }
//
//  public CoreNode visit(Core.TyCon tyCon) {
//    return core.typeConstructor(tyCon.pos, tyCon.id.accept(this),
//        tyCon.type == null ? null : tyCon.type.accept(this));
//  }
//
//  public Core.RecordType visit(Core.RecordType recordType) {
//    return core.recordType(recordType.pos, visitMap(recordType.fieldTypes));
//  }
//
//  public Core.Type visit(Core.TupleType tupleType) {
//    return core.tupleType(tupleType.pos, visitList(tupleType.types));
//  }
//
//  public Core.Type visit(Core.FunctionType functionType) {
//    return core.functionType(functionType.pos, functionType.paramType,
//        functionType.resultType);
//  }
//
//  public Core.Type visit(Core.CompositeType compositeType) {
//    return core.compositeType(compositeType.pos,
//        visitList(compositeType.types));
//  }
}

// End CoreShuttle.java
