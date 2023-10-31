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

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import org.apache.calcite.util.Util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.function.UnaryOperator;

import static com.google.common.base.Preconditions.checkArgument;

/** Algebraic type. */
public class DataType extends ParameterizedType {
  private final List<Type> arguments;
  public final SortedMap<String, Key> typeConstructors;

  /** Creates a DataType.
   *
   * <p>Called only from {@link TypeSystem#dataTypes(List)}.
   *
   * <p>If the {@code typeSystem} argument is specified, canonizes the types
   * inside type-constructors. This also allows temporary types (necessary while
   * creating self-referential data types) to be replaced with real DataType
   * instances.
   *
   * <p>During replacement, if a type matches {@code placeholderType} it is
   * replaced with {@code this}. This allows cyclic graphs to be copied. */
  DataType(String name, String moniker, int parameterCount,
      List<? extends Type> arguments, SortedMap<String, Key> typeConstructors) {
    this(Op.DATA_TYPE, name, moniker, parameterCount, arguments,
        typeConstructors);
  }

  /** Called only from DataType and TemporaryType constructor. */
  protected DataType(Op op, String name, String moniker, int parameterCount,
      List<? extends Type> arguments, SortedMap<String, Key> typeConstructors) {
    super(op, name, moniker, parameterCount);
    this.arguments = ImmutableList.copyOf(arguments);
    this.typeConstructors = ImmutableSortedMap.copyOf(typeConstructors);
    checkArgument(typeConstructors.comparator() == null
        || typeConstructors.comparator() == Ordering.natural());
  }

  @Override public Key key() {
    return Keys.datatype(name, parameterTypes.size(), Keys.toKeys(arguments),
        typeConstructors);
  }

  public Keys.DataTypeDef def() {
    final List<Type.Key> parameters = Keys.toKeys(parameterTypes);
    return Keys.dataTypeDef(name, parameters, parameters, typeConstructors,
        true);
  }

  public <R> R accept(TypeVisitor<R> typeVisitor) {
    return typeVisitor.visit(this);
  }

  public SortedMap<String, Type> typeConstructors(TypeSystem typeSystem) {
    return Maps.transformValues(typeConstructors, k -> k.toType(typeSystem));
  }

  @Override public DataType copy(TypeSystem typeSystem,
      UnaryOperator<Type> transform) {
    final List<Type> parameterTypes =
        Util.transform(this.parameterTypes, transform);
    if (parameterTypes.equals(this.parameterTypes)) {
      return this;
    }
    return new DataType(name, moniker, parameterTypes.size(), arguments,
        typeConstructors);
  }

  /** Second part of the implementation of
   * {@link #substitute(TypeSystem, List, TypeSystem.Transaction)}, called
   * if there is not already a type of the given description. */
  protected Type substitute2(TypeSystem typeSystem,
      List<? extends Type> types, TypeSystem.Transaction transaction) {
    final List<Keys.DataTypeDef> defs = new ArrayList<>();
    final Map<String, TemporaryType> temporaryTypeMap = new HashMap<>();
    final TypeShuttle typeVisitor = new TypeShuttle(typeSystem) {
      @Override public Type visit(TypeVar typeVar) {
        return types.get(typeVar.ordinal);
      }

      @Override public Type visit(DataType dataType) {
        final String moniker1 = computeMoniker(dataType.name, types);
        final Type type = typeSystem.lookupOpt(moniker1);
        if (type != null) {
          return type;
        }
        final Type type2 = temporaryTypeMap.get(moniker1);
        if (type2 != null) {
          return type2;
        }
        final TemporaryType temporaryType =
            typeSystem.temporaryType(dataType.name, types, transaction, false);
        temporaryTypeMap.put(moniker1, temporaryType);
        defs.add(
            Keys.dataTypeDef(dataType.name, Keys.toKeys(types),
                ImmutableList.of(), typeConstructors, true));
        return temporaryType;
      }
    };
    accept(typeVisitor);
    final List<Type> types1 = typeSystem.dataTypes(defs);
    final int i = defs.size() == 1
        ? 0
        : Iterables.indexOf(defs, def ->
            def.name.equals(name) && def.parameters.equals(types));
    return types1.get(i);
  }

  Type substitute3(TypeSystem typeSystem,
      List<? extends Type> types, TypeSystem.Transaction transaction) {
    Keys.DataTypeDef def =
        Keys.dataTypeDef(name, ImmutableList.of(), Keys.toKeys(types),
            Maps.transformValues(typeConstructors,
                t -> t.substitute(types)), true);
    return def.toType(typeSystem);
  }
}

// End DataType.java
