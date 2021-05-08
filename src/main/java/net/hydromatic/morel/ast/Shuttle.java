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
package net.hydromatic.morel.ast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static net.hydromatic.morel.ast.AstBuilder.ast;
import static net.hydromatic.morel.ast.CoreBuilder.core;

/** Visits and transforms syntax trees. */
public class Shuttle {
  private <E extends AstNode> List<E> visitList(List<E> nodes) {
    final List<E> list = new ArrayList<>();
    for (E node : nodes) {
      list.add((E) node.accept(this));
    }
    return list;
  }

  private <K, E extends AstNode> Map<K, E> visitMap(Map<K, E> nodes) {
    final Map<K, E> map = new LinkedHashMap<>();
    nodes.forEach((k, v) -> map.put(k, (E) v.accept(this)));
    return map;
  }

  // expressions

  protected Ast.Exp visit(Ast.Literal literal) {
    return literal; // leaf
  }

  protected Ast.Id visit(Ast.Id id) {
    return id; // leaf
  }

  protected Ast.Exp visit(Ast.AnnotatedExp annotatedExp) {
    return ast.annotatedExp(annotatedExp.pos,
        annotatedExp.type.accept(this),
        annotatedExp.e.accept(this));
  }

  protected Ast.Exp visit(Ast.If anIf) {
    return ast.ifThenElse(anIf.pos, anIf.condition.accept(this),
        anIf.ifTrue.accept(this), anIf.ifFalse.accept(this));
  }

  protected Ast.LetExp visit(Ast.LetExp e) {
    return ast.let(e.pos, visitList(e.decls), e.e);
  }

  protected Ast.Exp visit(Ast.Case kase) {
    return ast.caseOf(kase.pos, kase.e.accept(this),
        visitList(kase.matchList));
  }

  // calls

  protected Ast.Exp visit(Ast.InfixCall infixCall) {
    return ast.infixCall(infixCall.pos, infixCall.op,
        infixCall.a0.accept(this), infixCall.a1.accept(this));
  }

  public Ast.Exp visit(Ast.PrefixCall prefixCall) {
    return ast.prefixCall(prefixCall.pos, prefixCall.op,
        prefixCall.a.accept(this));
  }

  // patterns

  protected Ast.Pat visit(Ast.IdPat idPat) {
    return idPat; // leaf
  }

  protected Ast.Pat visit(Ast.LiteralPat literalPat) {
    return literalPat; // leaf
  }

  protected Ast.Pat visit(Ast.WildcardPat wildcardPat) {
    return wildcardPat; // leaf
  }

  protected Ast.Pat visit(Ast.InfixPat infixPat) {
    return infixPat.copy(infixPat.p0.accept(this), infixPat.p1.accept(this));
  }

  protected Ast.Pat visit(Ast.TuplePat tuplePat) {
    return tuplePat.copy(visitList(tuplePat.args));
  }

  protected Ast.Pat visit(Ast.ListPat listPat) {
    return listPat.copy(visitList(listPat.args));
  }

  protected Ast.Pat visit(Ast.RecordPat recordPat) {
    return recordPat.copy(recordPat.ellipsis, visitMap(recordPat.args));
  }

  protected Ast.Pat visit(Ast.AnnotatedPat annotatedPat) {
    return annotatedPat.copy(annotatedPat.pat.accept(this),
        annotatedPat.type.accept(this));
  }

  public Ast.ConPat visit(Ast.ConPat conPat) {
    return conPat.copy(conPat.tyCon.accept(this), conPat.pat.accept(this));
  }

  public Ast.Con0Pat visit(Ast.Con0Pat con0Pat) {
    return con0Pat.copy(con0Pat.tyCon.accept(this));
  }

  // value constructors

  protected Ast.Exp visit(Ast.Tuple tuple) {
    return ast.tuple(tuple.pos, visitList(tuple.args));
  }

  protected Ast.ListExp visit(Ast.ListExp list) {
    return ast.list(list.pos, visitList(list.args));
  }

  protected Ast.Exp visit(Ast.Record record) {
    return ast.record(record.pos, visitMap(record.args));
  }

  // functions and matches

  protected Ast.Fn visit(Ast.Fn fn) {
    return ast.fn(fn.pos, visitList(fn.matchList));
  }

  protected Ast.Apply visit(Ast.Apply apply) {
    return ast.apply(apply.fn.accept(this), apply.arg.accept(this));
  }

  protected Ast.Exp visit(Ast.RecordSelector recordSelector) {
    return recordSelector; // leaf
  }

  protected Ast.Match visit(Ast.Match match) {
    return ast.match(match.pos, match.pat.accept(this), match.e.accept(this));
  }

  // types

  protected Ast.Type visit(Ast.NamedType namedType) {
    return namedType; // leaf
  }

  protected Ast.TyVar visit(Ast.TyVar tyVar) {
    return tyVar; // leaf
  }

  // declarations

  protected Ast.Decl visit(Ast.FunDecl funDecl) {
    return ast.funDecl(funDecl.pos, visitList(funDecl.funBinds));
  }

  protected Ast.FunBind visit(Ast.FunBind funBind) {
    return ast.funBind(funBind.pos, visitList(funBind.matchList));
  }

  protected Ast.FunMatch visit(Ast.FunMatch funMatch) {
    return ast.funMatch(funMatch.pos, funMatch.name,
        visitList(funMatch.patList), funMatch.e.accept(this));
  }

  protected Ast.ValDecl visit(Ast.ValDecl valDecl) {
    return ast.valDecl(valDecl.pos, visitList(valDecl.valBinds));
  }

  protected Ast.ValBind visit(Ast.ValBind valBind) {
    return ast.valBind(valBind.pos, valBind.rec, valBind.pat, valBind.e);
  }

  public Ast.Exp visit(Ast.From from) {
    return ast.from(from.pos, from.sources, from.steps, from.yieldExp,
        from.yieldExpOrDefault);
  }

  public AstNode visit(Ast.Order order) {
    return ast.order(order.pos, order.orderItems);
  }

  public AstNode visit(Ast.OrderItem orderItem) {
    return ast.orderItem(orderItem.pos, orderItem.exp, orderItem.direction);
  }

  public AstNode visit(Ast.Where where) {
    return ast.where(where.pos, where.exp.accept(this));
  }

  public AstNode visit(Ast.Group group) {
    return ast.group(group.pos, group.groupExps, group.aggregates);
  }

  public AstNode visit(Ast.Aggregate aggregate) {
    return ast.aggregate(aggregate.pos, aggregate.aggregate, aggregate.argument,
        aggregate.id);
  }

  public Ast.DatatypeDecl visit(Ast.DatatypeDecl datatypeDecl) {
    return ast.datatypeDecl(datatypeDecl.pos, visitList(datatypeDecl.binds));
  }

  public Ast.DatatypeBind visit(Ast.DatatypeBind datatypeBind) {
    return ast.datatypeBind(datatypeBind.pos, datatypeBind.name.accept(this),
        visitList(datatypeBind.tyVars), visitList(datatypeBind.tyCons));
  }

  public AstNode visit(Ast.TyCon tyCon) {
    return ast.typeConstructor(tyCon.pos, tyCon.id.accept(this),
        tyCon.type == null ? null : tyCon.type.accept(this));
  }

  public Ast.RecordType visit(Ast.RecordType recordType) {
    return ast.recordType(recordType.pos, visitMap(recordType.fieldTypes));
  }

  public Ast.Type visit(Ast.TupleType tupleType) {
    return ast.tupleType(tupleType.pos, visitList(tupleType.types));
  }

  public Ast.Type visit(Ast.FunctionType functionType) {
    return ast.functionType(functionType.pos, functionType.paramType,
        functionType.resultType);
  }

  public Ast.Type visit(Ast.CompositeType compositeType) {
    return ast.compositeType(compositeType.pos,
        visitList(compositeType.types));
  }

  // core expressions, patterns

  protected Core.Apply visit(Core.Apply apply) {
    return core.apply(apply.type, apply.fn.accept(this), apply.arg.accept(this));
  }

  public Core.Exp visit(Core.Id id) {
    return core.id(id.type, id.name);
  }

  public Core.RecordSelector visit(Core.RecordSelector recordSelector) {
    return core.recordSelector(recordSelector.type(), recordSelector.name);
  }

  public Core.Exp visit(Core.Literal literal) {
    return literal;
  }

  public Core.Exp visit(Core.Tuple tuple) {
    return core.tuple(tuple.type(), visitList(tuple.args));
  }

  public Core.Exp visit(Core.LetExp letExp) {
    return core.let(letExp.decl.accept(this), letExp.e.accept(this));
  }

  public Core.DatatypeDecl visit(Core.DatatypeDecl datatypeDecl) {
    return core.datatypeDecl(datatypeDecl.dataTypes);
  }

  public Core.Decl visit(Core.ValDecl valDecl) {
    return core.valDecl(valDecl.rec, valDecl.pat.accept(this),
        valDecl.e.accept(this));
  }

  public Core.Pat visit(Core.IdPat idPat) {
    return idPat;
  }

  public Core.Pat visit(Core.LiteralPat literalPat) {
    return literalPat;
  }

  public Core.Pat visit(Core.WildcardPat wildcardPat) {
    return wildcardPat;
  }

  public Core.Pat visit(Core.ConPat conPat) {
    return core.conPat(conPat.type, conPat.tyCon, conPat.pat.accept(this));
  }

  public Core.Pat visit(Core.Con0Pat con0Pat) {
    return con0Pat;
  }

  public Core.Pat visit(Core.TuplePat tuplePat) {
    return core.tuplePat(tuplePat.type, visitList(tuplePat.args));
  }

  public Core.Pat visit(Core.ListPat listPat) {
    return core.listPat(listPat.type, visitList(listPat.args));
  }

  public Core.Pat visit(Core.RecordPat recordPat) {
    return core.recordPat(recordPat.type(), visitList(recordPat.args));
  }

  public Core.Exp visit(Core.Fn fn) {
    return core.fn(fn.type(), visitList(fn.matchList));
  }

  public Core.Exp visit(Core.Case aCase) {
    return core.caseOf(aCase.type, aCase.e.accept(this),
        visitList(aCase.matchList));
  }

  public Core.Exp visit(Core.From from) {
    return core.from(from.type(), visitMap(from.sources), visitList(from.steps),
        from.yieldExp.accept(this));
  }

  public Core.Where visit(Core.Where where) {
    return core.where(where.exp.accept(this));
  }

  public Core.Match visit(Core.Match match) {
    return core.match(match.pat.accept(this), match.e.accept(this));
  }

  public Core.Group visit(Core.Group group) {
    return core.group(visitMap(group.groupExps), visitMap(group.aggregates));
  }

  public Core.Order visit(Core.Order order) {
    return core.order(visitList(order.orderItems));
  }

  public Core.Aggregate visit(Core.Aggregate aggregate) {
    return core.aggregate(aggregate.type, aggregate.aggregate.accept(this),
        aggregate.argument == null ? null : aggregate.argument.accept(this));
  }

  public Core.OrderItem visit(Core.OrderItem orderItem) {
    return core.orderItem(orderItem.exp.accept(this), orderItem.direction);
  }
}

// End Shuttle.java
