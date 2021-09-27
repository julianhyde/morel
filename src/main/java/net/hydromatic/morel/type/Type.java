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
package net.hydromatic.morel.type;

import net.hydromatic.morel.ast.Op;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.UnaryOperator;

/** Type. */
public interface Type {
  /** Description of the type, e.g. "{@code int}", "{@code int -> int}",
   * "{@code NONE | SOME of 'a}". */
  Key key();

  /** Key of the type.
   *
   * <p>Often the same as {@link #key()}, but an exception is datatype.
   * For example, datatype "{@code 'a option}" has moniker and name
   * "{@code option}" and description "{@code NONE | SOME of 'a}".
   *
   * <p>Use the description if you are looking for a type that is structurally
   * equivalent. Use the moniker to identify it when printing. */
  default String moniker() {
    return key().moniker();
  }

  /** Type operator. */
  Op op();

  /** Copies this type, applying a given transform to component types,
   * and returning the original type if the component types are unchanged. */
  Type copy(TypeSystem typeSystem, UnaryOperator<Type> transform);

  <R> R accept(TypeVisitor<R> typeVisitor);

  /** Returns a copy of this type, specialized by substituting type
   * parameters. */
  @Deprecated
  default Type substitute(TypeSystem typeSystem, List<? extends Type> types,
      TypeSystem.Transaction transaction) {
    if (!types.isEmpty()) {
      throw new IllegalArgumentException("too many type parameters, "
          + types + " (expected 0)");
    }
    return this;
  }

  /** Returns a copy of this type, specialized by substituting type
   * parameters. */
  default Type substitute1(TypeSystem typeSystem, List<? extends Type> types,
      TypeSystem.Transaction transaction) {
    return copy(typeSystem, new TypeCopier(types, typeSystem, transaction));
  }

  /** Structural identifier of a type. */
  interface Key {
    Type toType(TypeSystem typeSystem);

    default String moniker() {
      return describe(new StringBuilder(), 0, 0).toString();
    }

    StringBuilder describe(StringBuilder buf, int left, int right);

    // TODO: either get rid of this method or get rid of Keys.apply
    default Key substitute(List<? extends Type> types) {
      if (!types.isEmpty()) {
        throw new IllegalArgumentException();
      }
      return this;
    }
  }

  /** Definition of a type. */
  interface Def {
    StringBuilder describe(StringBuilder buf);

    DataType toType(TypeSystem typeSystem);
  }

  /** Copies a type, substituting types for type variables.
   *
   * <p>TODO: move somewhere else; make private; remove the duplicate code in
   * {@link DataType#substitute(TypeSystem, List, TypeSystem.Transaction)}. */
  class TypeCopier implements UnaryOperator<Type> {
    private final List<? extends Type> types;
    private final TypeSystem typeSystem;
    private final TypeSystem.Transaction transaction;

    TypeCopier(List<? extends Type> types, TypeSystem typeSystem,
        TypeSystem.Transaction transaction) {
      this.types = types;
      this.typeSystem = typeSystem;
      this.transaction = transaction;
    }

    @Override public Type apply(Type type) {
      if (type instanceof TypeVar) {
        return types.get(((TypeVar) type).ordinal);
      }
      if (type instanceof DataType) {
        // Create a copy of this datatype with type variables substituted with
        // actual types.
        final DataType dataType = (DataType) type;
        if (types.equals(dataType.parameterTypes)) {
          return dataType;
        }
        final String moniker =
            ParameterizedType.computeMoniker(dataType.name, types);
        final Type lookup = typeSystem.lookupOpt(moniker);
        if (lookup != null) {
          return lookup;
        }
        assert types.size() == dataType.parameterTypes.size();
        final List<Keys.DataTypeDef> defs = new ArrayList<>();
        final Map<String, TemporaryType> temporaryTypeMap = new HashMap<>();
        try (TypeSystem.Transaction transaction2 = typeSystem.transaction()) {
          final TypeShuttle typeVisitor = new TypeShuttle(typeSystem) {
            @Override public Type visit(TypeVar typeVar) {
              return types.get(typeVar.ordinal);
            }

            @Override public Type visit(DataType dataType) {
              final String moniker1 =
                  ParameterizedType.computeMoniker(dataType.name, types);
              final Type type = typeSystem.lookupOpt(moniker1);
              if (type != null) {
                return type;
              }
              final Type type2 = temporaryTypeMap.get(moniker1);
              if (type2 != null) {
                return type2;
              }
              final TemporaryType temporaryType =
                  typeSystem.temporaryType(dataType.name, types, transaction2,
                      false);
              temporaryTypeMap.put(moniker1, temporaryType);
              final SortedMap<String, Type> typeConstructors = new TreeMap<>();
              dataType.typeConstructors.forEach((tyConName, tyConType) ->
                  typeConstructors.put(tyConName, tyConType.accept(this)));
              defs.add(
                  Keys.dataTypeDef(dataType.name, types, typeConstructors, false));
              return temporaryType;
            }
          };
          type.accept(typeVisitor);
        }
        final List<Type> types1 = typeSystem.dataTypes(defs);
        return types1.get(0);
      }
      if (type instanceof TemporaryType) {
        final TemporaryType temporaryType = (TemporaryType) type;
        // Create a copy of this temporary type with type variables substituted
        // with actual types.
        if (types.equals(temporaryType.parameterTypes)) {
          return type;
        }
        final String moniker =
            ParameterizedType.computeMoniker(temporaryType.name, types);
        final Type lookup = typeSystem.lookupOpt(moniker);
        if (lookup != null) {
          return lookup;
        }
        assert types.size() == temporaryType.parameterTypes.size();
        return typeSystem.temporaryType(temporaryType.name, types,
            transaction, false);
      }
      return type;
    }
  }
}

// End Type.java
