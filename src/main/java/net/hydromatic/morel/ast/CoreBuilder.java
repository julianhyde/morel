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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;

import net.hydromatic.morel.eval.Applicable;
import net.hydromatic.morel.eval.Unit;
import net.hydromatic.morel.type.DataType;
import net.hydromatic.morel.type.FnType;
import net.hydromatic.morel.type.ListType;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.RecordLikeType;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.Type;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/** Builds parse tree nodes. */
public enum CoreBuilder {
  /** The singleton instance of the CORE builder.
   * The short name is convenient for use via 'import static',
   * but checkstyle does not approve. */
  // CHECKSTYLE: IGNORE 1
  core;

//  public String implicitLabel(Core.Exp e) {
//    if (e instanceof Core.Apply) {
//      final Core.Apply apply = (Core.Apply) e;
//      if (apply.fn instanceof Core.RecordSelector) {
//        final Core.RecordSelector selector = (Core.RecordSelector) apply.fn;
//        return selector.name;
//      }
//    }
//    if (e instanceof Core.Id) {
//      return ((Core.Id) e).name;
//    }
//    throw new IllegalArgumentException("cannot derive label for expression "
//        + e);
//  }
//
//  /** Creates a call to an infix operator. */
//  private Core.InfixCall infix(Op op, Core.Exp a0, Core.Exp a1) {
//    return new Core.InfixCall(a0.pos.plus(a1.pos), op, a0, a1);
//  }
//
//  /** Creates a call to a prefix operator. */
//  public Core.PrefixCall prefixCall(Pos p, Op op, Core.Exp a) {
//    return new Core.PrefixCall(p.plus(a.pos), op, a);
//  }

  /** Creates a {@code boolean} literal. */
  public Core.Literal boolLiteral(boolean b) {
    return new Core.Literal(Op.BOOL_LITERAL, PrimitiveType.BOOL, b);
  }

  /** Creates a {@code char} literal. */
  public Core.Literal charLiteral(char c) {
    return new Core.Literal(Op.CHAR_LITERAL, PrimitiveType.CHAR, c);
  }

  /** Creates an {@code int} literal. */
  public Core.Literal intLiteral(BigDecimal value) {
    return new Core.Literal(Op.INT_LITERAL, PrimitiveType.INT, value);
  }

  /** Creates a {@code float} literal. */
  public Core.Literal realLiteral(BigDecimal value) {
    return new Core.Literal(Op.REAL_LITERAL, PrimitiveType.REAL, value);
  }

  /** Creates a string literal. */
  public Core.Literal stringLiteral(String value) {
    return new Core.Literal(Op.STRING_LITERAL, PrimitiveType.STRING, value);
  }

  /** Creates a unit literal. */
  public Core.Literal unitLiteral() {
    return new Core.Literal(Op.UNIT_LITERAL, PrimitiveType.UNIT, Unit.INSTANCE);
  }

  /** Creates a function literal. */
  public Core.Literal functionLiteral(Op op) {
    return new Core.Literal(Op.FN_LITERAL, PrimitiveType.UNIT, op);
  }

  /** Creates a reference to a value. */
  public Core.Id id(Type type, String name) {
    return new Core.Id(name, type);
  }

//  public Core.TyVar tyVar(Pos pos, String name) {
//    return new Core.TyVar(pos, name);
//  }
//
//  public Core.RecordType recordType(Pos pos, Map<String, Core.Type> fieldTypes) {
//    return new Core.RecordType(pos, ImmutableMap.copyOf(fieldTypes));
//  }

  public Core.RecordSelector recordSelector(FnType fnType, String name) {
    return new Core.RecordSelector(fnType, name);
  }

//  public Core.Type namedType(Pos pos, Iterable<? extends Core.Type> types,
//      String name) {
//    return new Core.NamedType(pos, ImmutableList.copyOf(types), name);
//  }

  public Core.Pat idPat(Type type, String name) {
    return new Core.IdPat(type, name);
  }

  @SuppressWarnings("rawtypes")
  public Core.LiteralPat literalPat(Op op, Type type, Comparable value) {
    return new Core.LiteralPat(op, type, value);
  }

  public Core.WildcardPat wildcardPat(Type type) {
    return new Core.WildcardPat(type);
  }

  public Core.ConPat consPat(Type type, String tyCon, Core.Pat pat) {
    return new Core.ConPat(Op.CONS_PAT, type, tyCon, pat);
  }

  public Core.ConPat conPat(Type type, String tyCon, Core.Pat pat) {
    return new Core.ConPat(type, tyCon, pat);
  }

  public Core.Con0Pat con0Pat(DataType type, String tyCon) {
    return new Core.Con0Pat(type, tyCon);
  }

  public Core.TuplePat tuplePat(Type type, Iterable<? extends Core.Pat> args) {
    return new Core.TuplePat(type, ImmutableList.copyOf(args));
  }

  public Core.TuplePat tuplePat(Type type, Core.Pat... args) {
    return new Core.TuplePat(type, ImmutableList.copyOf(args));
  }

  public Core.ListPat listPat(Type type, Iterable<? extends Core.Pat> args) {
    return new Core.ListPat(type, ImmutableList.copyOf(args));
  }

  public Core.ListPat listPat(Type type, Core.Pat... args) {
    return new Core.ListPat(type, ImmutableList.copyOf(args));
  }

  public Core.RecordPat recordPat(RecordType type,
      List<? extends Core.Pat> args) {
    return new Core.RecordPat(type, ImmutableList.copyOf(args));
  }

//  public Core.AnnotatedPat annotatedPat(Pos pos, Core.Pat pat, Core.Type type) {
//    return new Core.AnnotatedPat(pos, pat, type);
//  }
//
//  public Core.InfixPat consPat(Core.Pat p0, Core.Pat p1) {
//    return infixPat(p0.pos.plus(p1.pos), Op.CONS_PAT, p0, p1);
//  }

  public Core.Tuple tuple(Type type, Iterable<? extends Core.Exp> args) {
    return new Core.Tuple(type, ImmutableList.copyOf(args));
  }

  public Core.Tuple tuple(Type type, Core.Exp... args) {
    return new Core.Tuple(type, ImmutableList.copyOf(args));
  }

  public Core.ListExp list(Type type, Iterable<? extends Core.Exp> list) {
    return new Core.ListExp(type, ImmutableList.copyOf(list));
  }

  public Core.Record record(RecordLikeType type,
      Iterable<? extends Core.Exp> args) {
    return new Core.Record(type, ImmutableList.copyOf(args));
  }

//  public Core.Exp equal(Core.Exp a0, Core.Exp a1) {
//    return infix(Op.EQ, a0, a1);
//  }
//
//  public Core.Exp notEqual(Core.Exp a0, Core.Exp a1) {
//    return infix(Op.NE, a0, a1);
//  }
//
//  public Core.Exp lessThan(Core.Exp a0, Core.Exp a1) {
//    return infix(Op.LT, a0, a1);
//  }
//
//  public Core.Exp greaterThan(Core.Exp a0, Core.Exp a1) {
//    return infix(Op.GT, a0, a1);
//  }
//
//  public Core.Exp lessThanOrEqual(Core.Exp a0, Core.Exp a1) {
//    return infix(Op.LE, a0, a1);
//  }
//
//  public Core.Exp greaterThanOrEqual(Core.Exp a0, Core.Exp a1) {
//    return infix(Op.GE, a0, a1);
//  }
//
//  public Core.Exp elem(Core.Exp a0, Core.Exp a1) {
//    return infix(Op.ELEM, a0, a1);
//  }
//
//  public Core.Exp notElem(Core.Exp a0, Core.Exp a1) {
//    return infix(Op.NOT_ELEM, a0, a1);
//  }
//
//  public Core.Exp andAlso(Core.Exp a0, Core.Exp a1) {
//    return infix(Op.ANDALSO, a0, a1);
//  }
//
//  public Core.Exp orElse(Core.Exp a0, Core.Exp a1) {
//    return infix(Op.ORELSE, a0, a1);
//  }
//
//  public Core.Exp plus(Core.Exp a0, Core.Exp a1) {
//    return infix(Op.PLUS, a0, a1);
//  }
//
//  public Core.Exp minus(Core.Exp a0, Core.Exp a1) {
//    return infix(Op.MINUS, a0, a1);
//  }
//
//  public Core.Exp times(Core.Exp a0, Core.Exp a1) {
//    return infix(Op.TIMES, a0, a1);
//  }
//
//  public Core.Exp divide(Core.Exp a0, Core.Exp a1) {
//    return infix(Op.DIVIDE, a0, a1);
//  }
//
//  public Core.Exp div(Core.Exp a0, Core.Exp a1) {
//    return infix(Op.DIV, a0, a1);
//  }
//
//  public Core.Exp mod(Core.Exp a0, Core.Exp a1) {
//    return infix(Op.MOD, a0, a1);
//  }
//
//  public Core.Exp caret(Core.Exp a0, Core.Exp a1) {
//    return infix(Op.CARET, a0, a1);
//  }
//
//  public Core.Exp o(Core.Exp a0, Core.Exp a1) {
//    return infix(Op.COMPOSE, a0, a1);
//  }
//
//  public Core.Exp except(Core.Exp a0, Core.Exp a1) {
//    return infix(Op.EXCEPT, a0, a1);
//  }
//
//  public Core.Exp intersect(Core.Exp a0, Core.Exp a1) {
//    return infix(Op.INTERSECT, a0, a1);
//  }
//
//  public Core.Exp union(Core.Exp a0, Core.Exp a1) {
//    return infix(Op.UNION, a0, a1);
//  }
//
//  public Core.Exp negate(Pos p, Core.Exp a) {
//    return prefixCall(p, Op.NEGATE, a);
//  }
//
//  public Core.Exp cons(Core.Exp a0, Core.Exp a1) {
//    return infix(Op.CONS, a0, a1);
//  }
//
//  public Core.Exp foldCons(List<Core.Exp> list) {
//    return foldRight(list, this::cons);
//  }

  public Core.LetExp let(Core.Decl decl, Core.Exp e) {
    return new Core.LetExp(decl, e);
  }

  public Core.ValDecl valDecl(Core.ValBind valBind) {
    return new Core.ValDecl(valBind);
  }

//  public Core.ValDecl valDecl(Pos pos, Core.ValBind... valBinds) {
//    return new Core.ValDecl(pos, ImmutableList.copyOf(valBinds));
//  }

  public Core.ValBind valBind(boolean rec, Core.Pat pat, Core.Exp e) {
    return new Core.ValBind(rec, pat, e);
  }

  public Core.Match match(Core.Pat pat, Core.Exp e) {
    return new Core.Match(pat, e);
  }

  public Core.Case caseOf(Type type, Core.Exp e,
      Iterable<? extends Core.Match> matchList) {
    return new Core.Case(type, e, ImmutableList.copyOf(matchList));
  }

  public Core.From from(ListType type, Map<Core.Pat, Core.Exp> sources,
      List<Core.FromStep> steps, Core.Exp yieldExp) {
    return new Core.From(type, ImmutableMap.copyOf(sources),
        ImmutableList.copyOf(steps), yieldExp);
  }

  public Core.Fn fn(FnType type, Core.Match... matchList) {
    return new Core.Fn(type, ImmutableList.copyOf(matchList));
  }

  public Core.Fn fn(FnType type, Iterable<? extends Core.Match> matchList) {
    return new Core.Fn(type, ImmutableList.copyOf(matchList));
  }

//  public Core.FunDecl funDecl(Pos pos,
//      Iterable<? extends Core.FunBind> valBinds) {
//    return new Core.FunDecl(pos, ImmutableList.copyOf(valBinds));
//  }
//
//  public Core.FunBind funBind(Pos pos,
//      Iterable<? extends Core.FunMatch> matchList) {
//    return new Core.FunBind(pos, ImmutableList.copyOf(matchList));
//  }
//
//  public Core.FunMatch funMatch(Pos pos, String name,
//      Iterable<? extends Core.Pat> patList, Core.Exp e) {
//    return new Core.FunMatch(pos, name, ImmutableList.copyOf(patList), e);
//  }

  public Core.Apply apply(Type type, Core.Exp fn, Core.Exp arg) {
    return new Core.Apply(type, fn, arg);
  }

  public Core.If ifThenElse(Core.Exp condition, Core.Exp ifTrue,
      Core.Exp ifFalse) {
    return new Core.If(condition, ifTrue, ifFalse);
  }

//  public Core.InfixPat infixPat(Pos pos, Op op, Core.Pat p0, Core.Pat p1) {
//    return new Core.InfixPat(pos, op, p0, p1);
//  }
//
//  public Core.Exp annotatedExp(Pos pos, Core.Type type, Core.Exp expression) {
//    return new Core.AnnotatedExp(pos, type, expression);
//  }
//
//  public Core.Exp infixCall(Pos pos, Op op, Core.Exp a0, Core.Exp a1) {
//    return new Core.InfixCall(pos, op, a0, a1);
//  }

  public Core.DatatypeDecl datatypeDecl(Iterable<DataType> dataTypes) {
    return new Core.DatatypeDecl(ImmutableList.copyOf(dataTypes));
  }

//  public Core.DatatypeBind datatypeBind(Pos pos, Core.Id name,
//      Iterable<Core.TyVar> tyVars, Iterable<Core.TyCon> tyCons) {
//    return new Core.DatatypeBind(pos, ImmutableList.copyOf(tyVars), name,
//        ImmutableList.copyOf(tyCons));
//  }
//
//  public Core.TyCon typeConstructor(Pos pos, Core.Id id, Core.Type type) {
//    return new Core.TyCon(pos, id, type);
//  }
//
//  public Core.Type tupleType(Pos pos, Iterable<Core.Type> types) {
//    return new Core.TupleType(pos, ImmutableList.copyOf(types));
//  }
//
//  public Core.Type compositeType(Pos pos, Iterable<Core.Type> types) {
//    return new Core.CompositeType(pos, ImmutableList.copyOf(types));
//  }
//
//  public Core.FunctionType functionType(Pos pos, Core.Type fromType,
//      Core.Type toType) {
//    return new Core.FunctionType(pos, fromType, toType);
//  }
//
//  public Core.Type foldFunctionType(List<Core.Type> types) {
//    return foldRight(types, (t1,  t2) ->
//        functionType(t1.pos.plus(t2.pos), t1, t2));
//  }
//
//  private <E> E foldRight(List<E> list, BiFunction<E, E, E> fold) {
//    E e = list.get(list.size() - 1);
//    for (int i = list.size() - 2; i >= 0; i--) {
//      e = fold.apply(list.get(i), e);
//    }
//    return e;
//  }

  public Core.Aggregate aggregate(Type type, Core.Exp aggregate,
      @Nullable Core.Exp argument) {
    return new Core.Aggregate(type, aggregate, argument);
  }

//  /** Returns a reference to a built-in: either a name (e.g. "true")
//   * or a field reference (e.g. "#hd List"). */
//  private Core.Exp ref(Pos pos, BuiltIn builtIn) {
//    if (builtIn.structure == null) {
//      return id(pos, builtIn.mlName);
//    } else {
//      return apply(id(pos, builtIn.structure),
//          id(pos, builtIn.mlName));
//    }
//  }
//
//  public Core.Exp map(Pos pos, Core.Exp e1, Core.Exp e2) {
//    return apply(apply(ref(pos, BuiltIn.LIST_MAP), e1), e2);
//  }

  public Core.Order order(Iterable<Core.OrderItem> orderItems) {
    return new Core.Order(ImmutableList.copyOf(orderItems));
  }

  public Core.OrderItem orderItem(Core.Exp exp, Ast.Direction direction) {
    return new Core.OrderItem(exp, direction);
  }

  public Core.Group group(Map<String, Core.Exp> groupExps,
      Map<String, Core.Aggregate> aggregates) {
    return new Core.Group(ImmutableSortedMap.copyOf(groupExps),
        ImmutableSortedMap.copyOf(aggregates));
  }

  public Core.FromStep where(Core.Exp exp) {
    return new Core.Where(exp);
  }

  public Core.ApplicableExp wrapApplicable(FnType fnType, Applicable applicable) {
    return new Core.ApplicableExp(applicable);
  }

}

// End CoreBuilder.java
