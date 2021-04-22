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

/** Builds Core nodes. */
public enum CoreBuilder {
  /** The singleton instance of the CoreBuilder.
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
//
//  /** Creates a {@code boolean} literal. */
//  public Core.Literal boolLiteral(Pos p, boolean b) {
//    return new Core.Literal(p, Op.BOOL_LITERAL, b);
//  }
//
//  /** Creates a {@code char} literal. */
//  public Core.Literal charLiteral(Pos p, char c) {
//    return new Core.Literal(p, Op.CHAR_LITERAL, c);
//  }
//
//  /** Creates an {@code int} literal. */
//  public Core.Literal intLiteral(Pos pos, BigDecimal value) {
//    return new Core.Literal(pos, Op.INT_LITERAL, value);
//  }
//
//  /** Creates a {@code float} literal. */
//  public Core.Literal realLiteral(Pos pos, BigDecimal value) {
//    return new Core.Literal(pos, Op.REAL_LITERAL, value);
//  }
//
//  /** Creates a string literal. */
//  public Core.Literal stringLiteral(Pos pos, String value) {
//    return new Core.Literal(pos, Op.STRING_LITERAL, value);
//  }
//
//  /** Creates a unit literal. */
//  public Core.Literal unitLiteral(Pos p) {
//    return new Core.Literal(p, Op.UNIT_LITERAL, Unit.INSTANCE);
//  }
//
//  public Core.Id id(Pos pos, String name) {
//    return new Core.Id(pos, name);
//  }
//
//  public Core.TyVar tyVar(Pos pos, String name) {
//    return new Core.TyVar(pos, name);
//  }
//
//  public Core.RecordType recordType(Pos pos, Map<String, Core.Type> fieldTypes) {
//    return new Core.RecordType(pos, ImmutableMap.copyOf(fieldTypes));
//  }
//
//  public Core.RecordSelector recordSelector(Pos pos, String name) {
//    return new Core.RecordSelector(pos, name);
//  }
//
//  public Core.Type namedType(Pos pos, Iterable<? extends Core.Type> types,
//      String name) {
//    return new Core.NamedType(pos, ImmutableList.copyOf(types), name);
//  }
//
//  public Core.Pat idPat(Pos pos, String name) {
//    // Don't treat built-in constants as identifiers.
//    // If we did, matching the pattern would rebind the name
//    // to some other value.
//    switch (name) {
//    case "false":
//      return literalPat(pos, Op.BOOL_LITERAL_PAT, false);
//    case "true":
//      return literalPat(pos, Op.BOOL_LITERAL_PAT, true);
//    case "nil":
//      return listPat(pos);
//    default:
//      return new Core.IdPat(pos, name);
//    }
//  }
//
//  public Core.LiteralPat literalPat(Pos pos, Op op, Comparable value) {
//    return new Core.LiteralPat(pos, op, value);
//  }
//
//  public Core.WildcardPat wildcardPat(Pos pos) {
//    return new Core.WildcardPat(pos);
//  }
//
//  public Core.ConPat conPat(Pos pos, Core.Id tyCon, Core.Pat pat) {
//    return new Core.ConPat(pos, tyCon, pat);
//  }
//
//  public Core.Con0Pat con0Pat(Pos pos, Core.Id tyCon) {
//    return new Core.Con0Pat(pos, tyCon);
//  }
//
//  public Core.TuplePat tuplePat(Pos pos, Iterable<? extends Core.Pat> args) {
//    return new Core.TuplePat(pos, ImmutableList.copyOf(args));
//  }
//
//  public Core.TuplePat tuplePat(Pos pos, Core.Pat... args) {
//    return new Core.TuplePat(pos, ImmutableList.copyOf(args));
//  }
//
//  public Core.ListPat listPat(Pos pos, Iterable<? extends Core.Pat> args) {
//    return new Core.ListPat(pos, ImmutableList.copyOf(args));
//  }
//
//  public Core.ListPat listPat(Pos pos, Core.Pat... args) {
//    return new Core.ListPat(pos, ImmutableList.copyOf(args));
//  }
//
//  public Core.RecordPat recordPat(Pos pos, boolean ellipsis,
//      Map<String, ? extends Core.Pat> args) {
//    return new Core.RecordPat(pos, ellipsis,
//        ImmutableSortedMap.copyOf(args, RecordType.ORDERING));
//  }
//
//  public Core.AnnotatedPat annotatedPat(Pos pos, Core.Pat pat, Core.Type type) {
//    return new Core.AnnotatedPat(pos, pat, type);
//  }
//
//  public Core.InfixPat consPat(Core.Pat p0, Core.Pat p1) {
//    return infixPat(p0.pos.plus(p1.pos), Op.CONS_PAT, p0, p1);
//  }
//
//  public Core.Tuple tuple(Pos pos, Iterable<? extends Core.Exp> list) {
//    return new Core.Tuple(pos, list);
//  }
//
//  public Core.List list(Pos pos, Iterable<? extends Core.Exp> list) {
//    return new Core.List(pos, list);
//  }
//
//  public Core.Record record(Pos pos, Map<String, Core.Exp> map) {
//    return new Core.Record(pos,
//        ImmutableSortedMap.copyOf(map, RecordType.ORDERING));
//  }
//
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
//
//  public Core.LetExp let(Pos pos, Iterable<? extends Core.Decl> decls,
//      Core.Exp e) {
//    return new Core.LetExp(pos, ImmutableList.copyOf(decls), e);
//  }
//
//  public Core.ValDecl valDecl(Pos pos,
//      Iterable<? extends Core.ValBind> valBinds) {
//    return new Core.ValDecl(pos, ImmutableList.copyOf(valBinds));
//  }
//
//  public Core.ValDecl valDecl(Pos pos, Core.ValBind... valBinds) {
//    return new Core.ValDecl(pos, ImmutableList.copyOf(valBinds));
//  }
//
//  public Core.ValBind valBind(Pos pos, boolean rec, Core.Pat pat, Core.Exp e) {
//    return new Core.ValBind(pos, rec, pat, e);
//  }
//
//  public Core.Match match(Pos pos, Core.Pat pat, Core.Exp e) {
//    return new Core.Match(pos, pat, e);
//  }
//
//  public Core.Case caseOf(Pos pos, Core.Exp e,
//      Iterable<? extends Core.Match> matchList) {
//    return new Core.Case(pos, e, ImmutableList.copyOf(matchList));
//  }
//
//  public Core.From from(Pos pos, Map<Core.Pat, Core.Exp> sources,
//      List<Core.FromStep> steps, Core.Exp yieldExp) {
//    return new Core.From(pos, ImmutableMap.copyOf(sources),
//        ImmutableList.copyOf(steps), yieldExp);
//  }
//
//  public Core.Fn fn(Pos pos, Core.Match... matchList) {
//    return new Core.Fn(pos, ImmutableList.copyOf(matchList));
//  }
//
//  public Core.Fn fn(Pos pos,
//      Iterable<? extends Core.Match> matchList) {
//    return new Core.Fn(pos, ImmutableList.copyOf(matchList));
//  }
//
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
//
//  public Core.Apply apply(Core.Exp fn, Core.Exp arg) {
//    return new Core.Apply(fn.pos.plus(arg.pos), fn, arg);
//  }
//
//  public Core.Exp ifThenElse(Pos pos, Core.Exp condition, Core.Exp ifTrue,
//      Core.Exp ifFalse) {
//    return new Core.If(pos, condition, ifTrue, ifFalse);
//  }
//
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
//
//  public Core.DatatypeDecl datatypeDecl(Pos pos,
//      Iterable<Core.DatatypeBind> binds) {
//    return new Core.DatatypeDecl(pos, ImmutableList.copyOf(binds));
//  }
//
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
//
//  public Core.Aggregate aggregate(Pos pos, Core.Exp aggregate, Core.Exp argument,
//      Core.Id id) {
//    return new Core.Aggregate(pos, aggregate, argument, id);
//  }
//
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
//
//  public Core.Order order(Pos pos, Iterable<Core.OrderItem> orderItems) {
//    return new Core.Order(pos, ImmutableList.copyOf(orderItems));
//  }
//
//  public Core.OrderItem orderItem(Pos pos, Core.Exp exp, Core.Direction direction) {
//    return new Core.OrderItem(pos, exp, direction);
//  }
//
//  public Core.Group group(Pos pos, List<Pair<Core.Id, Core.Exp>> groupExps,
//      List<Core.Aggregate> aggregates) {
//    return new Core.Group(pos, ImmutableList.copyOf(groupExps),
//        ImmutableList.copyOf(aggregates));
//  }
//  public Core.FromStep where(Pos pos, Core.Exp exp) {
//    return new Core.Where(pos, exp);
//  }
//
//  public Core.ApplicableExp wrapApplicable(Applicable applicable) {
//    return new Core.ApplicableExp(applicable);
//  }
}

// End CoreBuilder.java
