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

import net.hydromatic.morel.type.DataType;
import net.hydromatic.morel.type.FnType;
import net.hydromatic.morel.type.ForallType;
import net.hydromatic.morel.type.ListType;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.TupleType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.type.TypeVar;
import net.hydromatic.morel.util.MartelliUnifier;
import net.hydromatic.morel.util.Tracers;
import net.hydromatic.morel.util.Unifier;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.UnaryOperator;

import static net.hydromatic.morel.util.Static.transform;
import static net.hydromatic.morel.util.Static.transformEager;

import static java.util.Objects.requireNonNull;

/** Can unify types. */
public class TypeUnifier {
  static final String TUPLE_TY_CON = "tuple";
  static final String LIST_TY_CON = "list";
  static final String RECORD_TY_CON = "record";
  static final String FN_TY_CON = "fn";

  /** A field of this name indicates that a record type is progressive. */
  static final String PROGRESSIVE_LABEL = "z$dummy";

  protected final TypeSystem typeSystem;
  protected final Unifier unifier = new MartelliUnifier();
  private final Map<Unifier.Variable, TypeVar> typeVarToVar = new HashMap<>();

  protected TypeUnifier(TypeSystem typeSystem) {
    this.typeSystem = requireNonNull(typeSystem);
  }

  /** Given a source and destination type, returns a variable substitution
   * map that converts source to destination.
   *
   * <p>Examples:
   * <ul>
   *   <li>{@code unify(var(0), int)} &rarr; [var(0) &rarr; int]
   *   <li>{@code unify(var(0), bool list)} &rarr; [var(0) &rarr; bool list]
   *   <li>{@code unify(var(0) * var(0), int * int)} &rarr; [var(0) &rarr; int]
   * </ul>
   *
   * @param shortCut Whether to use a quick algorithm for simple cases;
   *                 call this with true in production,
   *                 and with both true and false during testing
   */
  public static UnaryOperator<Type> unify(TypeSystem typeSystem, Type type1,
      Type type2, boolean shortCut) {
    if (shortCut) {
      if (type1 instanceof TypeVar) {
        return new Substitution(
            ImmutableMap.of(((TypeVar) type1).ordinal, type2));
      }
      if (type1.equals(type2)) {
        return new Substitution(ImmutableMap.of());
      }
    }
    return new TypeUnifier(typeSystem).unify(type1, type2);
  }

  /** Unifies two types. */
  private Substitution unify(Type type1, Type type2) {
    Unifier.Term term1 = toTerm(type1, Subst.EMPTY);
    Unifier.Term term2 = toTerm(type2, Subst.EMPTY);
    final Unifier.Result result =
        unifier.unify(
            ImmutableList.of(new Unifier.TermTerm(term1, term2)),
            new HashMap<>(), Tracers.nullTracer());
    if (result instanceof Unifier.Failure) {
      throw new IllegalArgumentException("cannot unify: " + result);
    }
    final Map<Integer, Type> map = new HashMap<>();
    final TermToTypeConverter termToTypeConverter =
        new TermToTypeConverter(typeSystem,
            ((Unifier.SubstitutionResult) result).resultMap::get,
            typeVarToVar::get);
    ((Unifier.SubstitutionResult) result).resultMap.forEach((v, t) ->
        map.put(typeVarToVar.get(v).ordinal,
            t.accept(termToTypeConverter)));
    return new Substitution(map);
  }

  private List<Unifier.Term> toTerms(Iterable<? extends Type> types,
      Subst subst) {
    return transformEager(types, type -> toTerm(type, subst));
  }

  protected Unifier.Term toTerm(PrimitiveType type) {
    return unifier.atom(type.moniker);
  }

  protected Unifier.Term toTerm(Type type, Subst subst) {
    switch (type.op()) {
    case ID:
      return toTerm((PrimitiveType) type);
    case TY_VAR:
      Unifier.Variable variable = subst.get((TypeVar) type);
      if (variable == null) {
        variable = unifier.variable();
        typeVarToVar.put(variable, (TypeVar) type);
      }
      return variable;
    case DATA_TYPE:
      final DataType dataType = (DataType) type;
      return unifier.apply(dataType.name(), toTerms(dataType.arguments, subst));
    case FUNCTION_TYPE:
      final FnType fnType = (FnType) type;
      return unifier.apply(FN_TY_CON, toTerm(fnType.paramType, subst),
          toTerm(fnType.resultType, subst));
    case TUPLE_TYPE:
      final TupleType tupleType = (TupleType) type;
      return unifier.apply(TUPLE_TY_CON,
          transform(tupleType.argTypes, type1 -> toTerm(type1, subst)));
    case RECORD_TYPE:
      final RecordType recordType = (RecordType) type;
      SortedMap<String, Type> argNameTypes = recordType.argNameTypes;
      if (recordType.isProgressive()) {
        argNameTypes = new TreeMap<>(argNameTypes);
        argNameTypes.put(PROGRESSIVE_LABEL, PrimitiveType.UNIT);
      }
      @SuppressWarnings({"rawtypes", "unchecked"})
      final NavigableSet<String> labelNames =
          (NavigableSet) argNameTypes.keySet();
      final String result;
      if (labelNames.isEmpty()) {
        result = PrimitiveType.UNIT.name();
      } else if (TypeSystem.areContiguousIntegers(labelNames)) {
        result = TUPLE_TY_CON;
      } else {
        final StringBuilder b = new StringBuilder(RECORD_TY_CON);
        for (String label : labelNames) {
          b.append(':').append(label);
        }
        result = b.toString();
      }
      final List<Unifier.Term> args =
          transformEager(argNameTypes.values(),
              type1 -> toTerm(type1, subst));
      return unifier.apply(result, args);
    case LIST:
      final ListType listType = (ListType) type;
      return unifier.apply(LIST_TY_CON,
          toTerm(listType.elementType, subst));
    case FORALL_TYPE:
      final ForallType forallType = (ForallType) type;
      TypeResolver.Subst subst2 = subst;
      for (int i = 0; i < forallType.parameterCount; i++) {
        subst2 = subst2.plus(typeSystem.typeVariable(i), unifier.variable());
      }
      return toTerm(forallType.type, subst2);
    default:
      throw new AssertionError("unknown type: " + type.moniker());
    }
  }

  /** Substitution. */
  protected abstract static class Subst {
    static final Subst EMPTY = new EmptySubst();

    Subst plus(TypeVar typeVar, Unifier.Variable variable) {
      return new PlusSubst(this, typeVar, variable);
    }

    abstract Unifier.Variable get(TypeVar typeVar);
  }

  /** Empty substitution. */
  private static class EmptySubst extends Subst {
    @Override public String toString() {
      return "[]";
    }

    @Override Unifier.Variable get(TypeVar typeVar) {
      return null;
    }
  }

  /** Substitution that adds one (type, variable) assignment to a parent
   * substitution. */
  private static class PlusSubst extends Subst {
    final Subst parent;
    final TypeVar typeVar;
    final Unifier.Variable variable;

    PlusSubst(Subst parent, TypeVar typeVar, Unifier.Variable variable) {
      this.parent = parent;
      this.typeVar = typeVar;
      this.variable = variable;
    }

    @Override Unifier.Variable get(TypeVar typeVar) {
      return typeVar.equals(this.typeVar)
          ? variable
          : parent.get(typeVar);
    }

    @Override public String toString() {
      final Map<TypeVar, Unifier.Term> map = new LinkedHashMap<>();
      for (PlusSubst e = this;;) {
        map.putIfAbsent(e.typeVar, e.variable);
        if (e.parent instanceof PlusSubst) {
          e = (PlusSubst) e.parent;
        } else {
          return map.toString();
        }
      }
    }
  }

  /** Results of {@link #unify}. */
  private static class Substitution implements UnaryOperator<Type> {
    final Map<Integer, Type> map;

    Substitution(Map<Integer, Type> map) {
      this.map = map;
    }

    @Override public String toString() {
      return new TreeMap<>(map).toString();
    }

    @Override public Type apply(Type type) {
      if (type instanceof TypeVar) {
        final Type type1 = map.get(((TypeVar) type).ordinal);
        if (type1 != null) {
          return type1;
        }
      }
      return type;
    }
  }
}

// End TypeUnifier.java
