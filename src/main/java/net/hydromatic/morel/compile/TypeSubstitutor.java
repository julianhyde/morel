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

import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.Map;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Shuttle;
import net.hydromatic.morel.type.FnType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;

/**
 * Substitutes type variables throughout Core expressions.
 *
 * <p>Similar to {@link Type#substitute}, but operates on Core expressions
 * rather than just types. Creates new expression nodes with substituted types.
 */
public class TypeSubstitutor extends Shuttle {
  private final List<Type> substList;

  private TypeSubstitutor(TypeSystem typeSystem, List<Type> substList) {
    super(typeSystem);
    this.substList = substList;
  }

  /**
   * Substitutes type variables in an expression based on a substitution map.
   *
   * @param typeSystem Type system
   * @param exp Expression to transform
   * @param subst Map from type variable ordinals to concrete types
   * @return Expression with substituted types
   */
  public static Core.Exp substitute(
      TypeSystem typeSystem, Core.Exp exp, Map<Integer, Type> subst) {
    if (subst.isEmpty()) {
      return exp;
    }
    // Convert map to list for Type.substitute
    int maxOrdinal = subst.keySet().stream().mapToInt(i -> i).max().orElse(-1);
    ImmutableList.Builder<Type> builder = ImmutableList.builder();
    for (int i = 0; i <= maxOrdinal; i++) {
      Type t = subst.get(i);
      // If no mapping, use a placeholder TypeVar (won't be substituted)
      builder.add(t != null ? t : typeSystem.typeVariable(i));
    }
    return exp.accept(new TypeSubstitutor(typeSystem, builder.build()));
  }

  private Type subst(Type type) {
    return type.substitute(typeSystem, substList);
  }

  @Override
  protected Core.Exp visit(Core.Fn fn) {
    FnType newType = (FnType) subst(fn.type());
    Core.IdPat newIdPat = fn.idPat.accept(this);
    Core.Exp newBody = fn.exp.accept(this);
    return core.fn(newType, newIdPat, newBody);
  }

  @Override
  protected Core.Exp visit(Core.Id id) {
    Core.NamedPat newIdPat = id.idPat.accept(this);
    return core.id(newIdPat);
  }

  @Override
  protected Core.IdPat visit(Core.IdPat idPat) {
    Type newType = subst(idPat.type);
    if (newType == idPat.type) {
      return idPat;
    }
    return core.idPat(newType, idPat.name, idPat.i);
  }

  @Override
  protected Core.Exp visit(Core.Apply apply) {
    Type newType = subst(apply.type);
    Core.Exp newFn = apply.fn.accept(this);
    Core.Exp newArg = apply.arg.accept(this);
    return core.apply(apply.pos, newType, newFn, newArg);
  }

  @Override
  protected Core.Exp visit(Core.Tuple tuple) {
    List<Core.Exp> newArgs = visitList(tuple.args);
    return core.tuple(typeSystem, null, newArgs);
  }

  @Override
  protected Core.Exp visit(Core.Case caseOf) {
    Type newType = subst(caseOf.type);
    Core.Exp newExp = caseOf.exp.accept(this);
    List<Core.Match> newMatchList = visitList(caseOf.matchList);
    return core.caseOf(caseOf.pos, newType, newExp, newMatchList);
  }
}

// End TypeSubstitutor.java
