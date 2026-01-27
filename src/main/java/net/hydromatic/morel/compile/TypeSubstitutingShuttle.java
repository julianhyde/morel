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

import static net.hydromatic.morel.ast.CoreBuilder.core;

import java.util.List;
import java.util.Map;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Shuttle;
import net.hydromatic.morel.type.DataType;
import net.hydromatic.morel.type.FnType;
import net.hydromatic.morel.type.RecordLikeType;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.type.TypeVar;

/**
 * Shuttle that walks a Core expression tree and substitutes TypeVars with
 * concrete types based on a substitution map.
 *
 * <p>This is used after identifier substitution (via {@link Replacer}) to
 * propagate concrete types through the expression tree when inlining singleton
 * case expressions.
 */
public class TypeSubstitutingShuttle extends Shuttle {
  private final Map<Integer, Type> typeSub;

  private TypeSubstitutingShuttle(
      TypeSystem typeSystem, Map<Integer, Type> typeSub) {
    super(typeSystem);
    this.typeSub = typeSub;
  }

  /**
   * Applies type substitution to an expression and all nested expressions.
   *
   * @param typeSystem the type system
   * @param typeSub map from TypeVar ordinal to replacement type
   * @param exp the expression to transform
   * @return the transformed expression with types substituted
   */
  public static Core.Exp substitute(
      TypeSystem typeSystem, Map<Integer, Type> typeSub, Core.Exp exp) {
    if (typeSub == null || typeSub.isEmpty()) {
      return exp;
    }
    return exp.accept(new TypeSubstitutingShuttle(typeSystem, typeSub));
  }

  /** Substitutes TypeVars in a type with their concrete replacements. */
  private Type substituteType(Type type) {
    return type.copy(
        typeSystem,
        t -> {
          if (t instanceof TypeVar) {
            Type replacement = typeSub.get(((TypeVar) t).ordinal);
            return replacement != null ? replacement : t;
          }
          return t;
        });
  }

  // === Pattern visitors ===

  @Override
  protected Core.IdPat visit(Core.IdPat idPat) {
    Type newType = substituteType(idPat.type);
    return newType == idPat.type ? idPat : idPat.withType(newType);
  }

  @Override
  protected Core.AsPat visit(Core.AsPat asPat) {
    Core.Pat newPat = asPat.pat.accept(this);
    Type newType = substituteType(asPat.type);
    if (newPat == asPat.pat && newType == asPat.type) {
      return asPat;
    }
    return core.asPat(newType, asPat.name, asPat.i, newPat);
  }

  @Override
  protected Core.Pat visit(Core.WildcardPat wildcardPat) {
    Type newType = substituteType(wildcardPat.type);
    return newType == wildcardPat.type
        ? wildcardPat
        : core.wildcardPat(newType);
  }

  @Override
  protected Core.Pat visit(Core.TuplePat tuplePat) {
    List<Core.Pat> newArgs = visitList(tuplePat.args);
    RecordLikeType newType = (RecordLikeType) substituteType(tuplePat.type);
    if (newArgs.equals(tuplePat.args) && newType == tuplePat.type) {
      return tuplePat;
    }
    return core.tuplePat(newType, newArgs);
  }

  @Override
  protected Core.Pat visit(Core.ListPat listPat) {
    List<Core.Pat> newArgs = visitList(listPat.args);
    Type newType = substituteType(listPat.type);
    if (newArgs.equals(listPat.args) && newType == listPat.type) {
      return listPat;
    }
    return core.listPat(newType, newArgs);
  }

  @Override
  protected Core.Pat visit(Core.RecordPat recordPat) {
    List<Core.Pat> newArgs = visitList(recordPat.args);
    RecordType newType = (RecordType) substituteType(recordPat.type);
    if (newArgs.equals(recordPat.args) && newType == recordPat.type) {
      return recordPat;
    }
    return core.recordPat(newType, newArgs);
  }

  @Override
  protected Core.Pat visit(Core.ConPat conPat) {
    Core.Pat newPat = conPat.pat.accept(this);
    Type newType = substituteType(conPat.type);
    if (newPat == conPat.pat && newType == conPat.type) {
      return conPat;
    }
    return core.conPat(newType, conPat.tyCon, newPat);
  }

  @Override
  protected Core.Pat visit(Core.Con0Pat con0Pat) {
    Type newType = substituteType(con0Pat.type);
    if (newType == con0Pat.type) {
      return con0Pat;
    }
    return core.con0Pat((DataType) newType, con0Pat.tyCon);
  }

  // LiteralPat has primitive types - no TypeVar substitution needed

  // === Expression visitors ===

  @Override
  protected Core.Exp visit(Core.Id id) {
    Core.NamedPat newIdPat = (Core.NamedPat) id.idPat.accept(this);
    return newIdPat == id.idPat ? id : core.id(newIdPat);
  }

  // Literals have primitive types - no TypeVar substitution needed

  @Override
  protected Core.Exp visit(Core.Tuple tuple) {
    List<Core.Exp> newArgs = visitList(tuple.args);
    RecordLikeType newType = (RecordLikeType) substituteType(tuple.type);
    if (newArgs.equals(tuple.args) && newType == tuple.type) {
      return tuple;
    }
    return core.tuple(newType, newArgs);
  }

  @Override
  protected Core.Exp visit(Core.Fn fn) {
    Core.IdPat newIdPat = visit(fn.idPat);
    Core.Exp newExp = fn.exp.accept(this);
    FnType newType = (FnType) substituteType(fn.type);
    if (newIdPat == fn.idPat && newExp == fn.exp && newType == fn.type) {
      return fn;
    }
    return core.fn(newType, newIdPat, newExp);
  }

  @Override
  protected Core.Exp visit(Core.Apply apply) {
    Core.Exp newFn = apply.fn.accept(this);
    Core.Exp newArg = apply.arg.accept(this);
    Type newType = substituteType(apply.type);
    if (newFn == apply.fn && newArg == apply.arg && newType == apply.type) {
      return apply;
    }
    return core.apply(apply.pos, newType, newFn, newArg);
  }

  @Override
  protected Core.Exp visit(Core.Case caseOf) {
    Core.Exp newExp = caseOf.exp.accept(this);
    List<Core.Match> newMatches = visitList(caseOf.matchList);
    Type newType = substituteType(caseOf.type);
    if (newExp == caseOf.exp
        && newMatches.equals(caseOf.matchList)
        && newType == caseOf.type) {
      return caseOf;
    }
    return core.caseOf(caseOf.pos, newType, newExp, newMatches);
  }

  @Override
  protected Core.Match visit(Core.Match match) {
    Core.Pat newPat = match.pat.accept(this);
    Core.Exp newExp = match.exp.accept(this);
    if (newPat == match.pat && newExp == match.exp) {
      return match;
    }
    return core.match(match.pos, newPat, newExp);
  }

  @Override
  protected Core.Exp visit(Core.Let let) {
    Core.ValDecl newDecl = let.decl.accept(this);
    Core.Exp newExp = let.exp.accept(this);
    if (newDecl == let.decl && newExp == let.exp) {
      return let;
    }
    return core.let(newDecl, newExp);
  }

  @Override
  protected Core.Exp visit(Core.Local local) {
    Core.Exp newExp = local.exp.accept(this);
    if (newExp == local.exp) {
      return local;
    }
    return local.copy(local.dataType, newExp);
  }

  @Override
  protected Core.NonRecValDecl visit(Core.NonRecValDecl valDecl) {
    Core.NamedPat newPat = (Core.NamedPat) valDecl.pat.accept(this);
    Core.Exp newExp = valDecl.exp.accept(this);
    Core.IdPat newOverload =
        valDecl.overloadPat == null ? null : visit(valDecl.overloadPat);
    if (newPat == valDecl.pat
        && newExp == valDecl.exp
        && newOverload == valDecl.overloadPat) {
      return valDecl;
    }
    return valDecl.copy(newPat, newExp, newOverload);
  }

  @Override
  protected Core.RecValDecl visit(Core.RecValDecl valDecl) {
    List<Core.NonRecValDecl> newList = visitList(valDecl.list);
    if (newList.equals(valDecl.list)) {
      return valDecl;
    }
    return valDecl.copy(newList);
  }

  @Override
  protected Core.Aggregate visit(Core.Aggregate aggregate) {
    Type newType = substituteType(aggregate.type);
    Core.Exp newAgg = aggregate.aggregate.accept(this);
    Core.Exp newArg =
        aggregate.argument == null ? null : aggregate.argument.accept(this);
    if (newType == aggregate.type
        && newAgg == aggregate.aggregate
        && newArg == aggregate.argument) {
      return aggregate;
    }
    return aggregate.copy(newType, newAgg, newArg);
  }

  // RecordSelector has primitive types - no TypeVar substitution needed
  // From expressions and steps use the parent Shuttle's implementation
  // which calls visitList appropriately
}

// End TypeSubstitutingShuttle.java
