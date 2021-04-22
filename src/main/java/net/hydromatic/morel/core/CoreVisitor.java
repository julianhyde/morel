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

/** Visits trees of Core nodes. */
public class CoreVisitor {

  private <E extends CoreNode> void accept(E e) {
    e.accept(this);
  }

  // expressions

  protected void visit(Core.Literal literal) {
  }

//  protected void visit(Core.Id id) {
//  }
//
//  protected void visit(Core.AnnotatedExp annotatedExp) {
//    annotatedExp.e.accept(this);
//    annotatedExp.type.accept(this);
//  }
//
//  protected void visit(Core.If anIf) {
//    anIf.condition.accept(this);
//    anIf.ifTrue.accept(this);
//    anIf.ifFalse.accept(this);
//  }
//
//  protected void visit(Core.LetExp e) {
//    e.decls.forEach(this::accept);
//    e.e.accept(this);
//  }
//
//  protected void visit(Core.Case kase) {
//    kase.e.accept(this);
//    kase.matchList.forEach(this::accept);
//  }
//
//  // calls
//
//  protected void visit(Core.InfixCall infixCall) {
//    infixCall.a0.accept(this);
//    infixCall.a1.accept(this);
//  }
//
//  protected void visit(Core.PrefixCall prefixCall) {
//    prefixCall.a.accept(this);
//  }
//
//  // patterns
//
//  protected void visit(Core.IdPat idPat) {
//  }
//
//  protected void visit(Core.LiteralPat literalPat) {
//  }
//
//  protected void visit(Core.WildcardPat wildcardPat) {
//  }
//
//  protected void visit(Core.InfixPat infixPat) {
//    infixPat.p0.accept(this);
//    infixPat.p1.accept(this);
//  }
//
//  protected void visit(Core.TuplePat tuplePat) {
//    tuplePat.args.forEach(this::accept);
//  }
//
//  protected void visit(Core.ListPat listPat) {
//    listPat.args.forEach(this::accept);
//  }
//
//  protected void visit(Core.RecordPat recordPat) {
//    recordPat.args.values().forEach(this::accept);
//  }
//
//  protected void visit(Core.AnnotatedPat annotatedPat) {
//    annotatedPat.pat.accept(this);
//    annotatedPat.type.accept(this);
//  }
//
//  protected void visit(Core.ConPat conPat) {
//    conPat.tyCon.accept(this);
//    conPat.pat.accept(this);
//  }
//
//  protected void visit(Core.Con0Pat con0Pat) {
//    con0Pat.tyCon.accept(this);
//  }
//
//  // value constructors
//
//  protected void visit(Core.Tuple tuple) {
//    tuple.args.forEach(this::accept);
//  }
//
//  protected void visit(Core.List list) {
//    list.args.forEach(this::accept);
//  }
//
//  protected void visit(Core.Record record) {
//    record.args.values().forEach(this::accept);
//  }
//
//  // functions and matches
//
//  protected void visit(Core.Fn fn) {
//    fn.matchList.forEach(this::accept);
//  }
//
//  protected void visit(Core.Apply apply) {
//    apply.fn.accept(this);
//    apply.arg.accept(this);
//  }
//
//  protected void visit(Core.RecordSelector recordSelector) {
//  }
//
//  protected void visit(Core.Match match) {
//    match.pat.accept(this);
//    match.e.accept(this);
//  }
//
//  // types
//
//  protected void visit(Core.NamedType namedType) {
//    namedType.types.forEach(this::accept);
//  }
//
//  protected void visit(Core.TyVar tyVar) {
//  }
//
//  // declarations
//
//  protected void visit(Core.FunDecl funDecl) {
//    funDecl.funBinds.forEach(this::accept);
//  }
//
//  protected void visit(Core.FunBind funBind) {
//    funBind.matchList.forEach(this::accept);
//  }
//
//  protected void visit(Core.FunMatch funMatch) {
//    funMatch.patList.forEach(this::accept);
//    funMatch.e.accept(this);
//  }
//
//  protected void visit(Core.ValDecl valDecl) {
//    valDecl.valBinds.forEach(this::accept);
//  }
//
//  protected void visit(Core.ValBind valBind) {
//    valBind.pat.accept(this);
//    valBind.e.accept(this);
//  }
//
//  protected void visit(Core.From from) {
//    from.sources.forEach((pat, exp) -> {
//      pat.accept(this);
//      exp.accept(this);
//    });
//    from.steps.forEach(this::accept);
//    if (from.yieldExp != null) {
//      from.yieldExp.accept(this);
//    }
//  }
//
//  protected void visit(Core.Order order) {
//    order.orderItems.forEach(this::accept);
//  }
//
//  protected void visit(Core.OrderItem orderItem) {
//    orderItem.exp.accept(this);
//  }
//
//  protected void visit(Core.Where where) {
//    where.exp.accept(this);
//  }
//
//  protected void visit(Core.Group group) {
//    group.groupExps.forEach(p -> {
//      p.left.accept(this);
//      p.right.accept(this);
//    });
//    group.aggregates.forEach(this::accept);
//  }
//
//  protected void visit(Core.Aggregate aggregate) {
//    aggregate.aggregate.accept(this);
//    if (aggregate.argument != null) {
//      aggregate.argument.accept(this);
//    }
//    aggregate.id.accept(this);
//  }
//
//  protected void visit(Core.DatatypeDecl datatypeDecl) {
//    datatypeDecl.binds.forEach(this::accept);
//  }
//
//  protected void visit(Core.DatatypeBind datatypeBind) {
//    datatypeBind.tyVars.forEach(this::accept);
//    datatypeBind.tyCons.forEach(this::accept);
//  }
//
//  protected void visit(Core.TyCon tyCon) {
//    tyCon.type.accept(this);
//    tyCon.id.accept(this);
//  }
//
//  protected void visit(Core.RecordType recordType) {
//    recordType.fieldTypes.values().forEach(this::accept);
//  }
//
//  protected void visit(Core.TupleType tupleType) {
//    tupleType.types.forEach(this::accept);
//  }
//
//  protected void visit(Core.FunctionType functionType) {
//    functionType.paramType.accept(this);
//    functionType.resultType.accept(this);
//  }
//
//  protected void visit(Core.CompositeType compositeType) {
//    compositeType.types.forEach(this::accept);
//  }
}

// End CoreVisitor.java
