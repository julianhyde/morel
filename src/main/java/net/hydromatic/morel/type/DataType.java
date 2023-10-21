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

import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Ordering;
import org.apache.calcite.util.Util;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkArgument;

/** Algebraic type. */
public class DataType extends ParameterizedType {
  private final Key key;
  public final SortedMap<String, Type> typeConstructors;

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
  DataType(String name, Key key,
      List<? extends Type> parameterTypes,
      SortedMap<String, Type> typeConstructors) {
    this(Op.DATA_TYPE, name, key, parameterTypes, typeConstructors);
  }

  /** Called only from DataType and TemporaryType constructor. */
  protected DataType(Op op, String name, Key key,
      List<? extends Type> parameterTypes,
      SortedMap<String, Type> typeConstructors) {
    super(op, name, computeMoniker(name, parameterTypes),
        parameterTypes);
    this.key = key;
    this.typeConstructors = Objects.requireNonNull(typeConstructors);
    checkArgument(typeConstructors.comparator() == null
        || typeConstructors.comparator() == Ordering.natural());
//    checkArgument(this instanceof TemporaryType ? key instanceof Keys.NameKey
//        : parameterTypes.isEmpty() ? key instanceof Keys.DataTypeKey
//        : key instanceof Keys.ForallTypeApplyKey);
  }

  @Override public Key key() {
    return key;
  }

  @Override public Key key(TypeSystem typeSystem) {
    return computeKey(typeSystem, parameterTypes);
  }

  Key computeKey(TypeSystem typeSystem, List<? extends Type> typeVars) {
    if (typeVars.isEmpty()) {
      return key();
    }
    final ForallType forallType = (ForallType) typeSystem.lookup(name);
    return Keys.forallTypeApply(forallType, typeVars);
  }

  public Keys.DataTypeDef def() {
    return Keys.dataTypeDef(name, parameterTypes, typeConstructors, true);
  }

  public <R> R accept(TypeVisitor<R> typeVisitor) {
    return typeVisitor.visit(this);
  }

  @Override public DataType copy(TypeSystem typeSystem,
      UnaryOperator<Type> transform) {
    final List<Type> parameterTypes =
        Util.transform(this.parameterTypes, transform);
    final ImmutableSortedMap<String, Type> typeConstructors =
        typeSystem.copyTypeConstructors(this.typeConstructors, transform);
    if (parameterTypes.equals(this.parameterTypes)
        && typeConstructors.equals(this.typeConstructors)) {
      return this;
    }
    return new DataType(name, key, parameterTypes, typeConstructors);
  }

  @Override public Type substitute(TypeSystem typeSystem,
      List<? extends Type> types, TypeSystem.Transaction transaction) {
    // Create a copy of this datatype with type variables substituted with
    // actual types.
    assert types.size() == parameterTypes.size();
    if (types.equals(parameterTypes)) {
      return this;
    }
    final String moniker = computeMoniker(name, types);
//    final Type lookup = typeSystem.lookupOpt(moniker);
//    if (lookup != null) {
//      return lookup;
//    }
    //    final Key key = Keys.forallTypeApply()
    return substitute2(typeSystem, types, transaction);
  }

  /** Second part of the implementation of
   * {@link #substitute(TypeSystem, List, TypeSystem.Transaction)}, called
   * if there is not already a type of the given description. */
  protected Type substitute2(TypeSystem typeSystem,
      List<? extends Type> types, TypeSystem.Transaction transaction) {
//    final String moniker0 = computeMoniker(name, types);
    final Key key0 = computeKey(typeSystem, types);
//    final Map<String, Foo> fooMap = new LinkedHashMap<>();
    final Map<Key, Foo> fooMap2 = new LinkedHashMap<>();
//    final Map<String, Keys.DataTypeDef> defs = new LinkedHashMap<>();
//    final Map<String, TemporaryType> temporaryTypeMap = new HashMap<>();
//    final Map<String, Type> typeMap = new LinkedHashMap<>();
    final TypeShuttle typeVisitor = new TypeShuttle(typeSystem) {
      @Override public Type visit(TypeVar typeVar) {
        return types.get(typeVar.ordinal);
      }

      @Override public Type visit(DataType dataType) {
        final String moniker1 = computeMoniker(dataType.name, types);
        final Key key1 = dataType.computeKey(typeSystem, types);
//        final Type type = typeSystem.lookupOpt(moniker1);
        final Type type = typeSystem.typeByKey.get(key1);
        if (type != null) {
          return type;
        }
//        final Type type2 = temporaryTypeMap.get(moniker1);
//        final Foo foo0 = fooMap.get(moniker1);
        final Foo foo0 = fooMap2.get(key1);
        if (foo0 != null) {
          return foo0.temporaryType;
        }
        final TemporaryType temporaryType =
            typeSystem.temporaryType(dataType.name, types, transaction, false);
//        temporaryTypeMap.put(moniker1, temporaryType);
        final SortedMap<String, Type> typeConstructors = new TreeMap<>();
        dataType.typeConstructors.forEach((tyConName, tyConType) ->
            typeConstructors.put(tyConName, tyConType.accept(this)));
        final Keys.DataTypeDef def =
            Keys.dataTypeDef(dataType.name, types, typeConstructors, true);
//        defs.put(moniker1, def);
        final Foo foo = new Foo(def, temporaryType);
//        fooMap.put(moniker1, foo);
        fooMap2.put(key1, foo);
        transaction.replace(moniker1, () -> foo.type);
        return temporaryType;
      }
    };
    accept(typeVisitor);
//    for (Pair<Keys.DataTypeDef, ? extends Type> pair : Pair.zip(defs, types)) {
//      final String moniker1 = computeMoniker(pair.left.name, pair.left.types);
//      final TypeSystem.TransactionImpl transaction =
//          (TypeSystem.TransactionImpl) transaction_;
//
//      ((TypeSystem.TransactionImpl) transaction.
//      final Type type =
//          typeSystem.typeByName.putIfAbsent(moniker1, pair.right);
//      boolean b = typeSystem.typeByName.containsKey(moniker1);
//    }

    // Populate the type map, so that the transaction.replace can do its job and
    // swap the TemporaryType for the DataType.
    final List<Keys.DataTypeDef> defs =
        fooMap2.values().stream().map(foo -> foo.def).collect(Collectors.toList());
    for (Type type : typeSystem.dataTypes(defs)) {
//      typeMap.put(type.moniker(), type);
//      fooMap.get(type.moniker()).type = type;
      final Key key1 = type.key(typeSystem);
      fooMap2.get(key1).type = type;
    }
//    final Type type = typeMap.get(moniker0);
//    final Type type = fooMap.get(moniker0).type;
    final Type type = fooMap2.get(key0).type;
    return Objects.requireNonNull(type, "type");
//    for (Map.Entry<String, Keys.DataTypeDef> entry : defs.entrySet()) {
//      final Keys.DataTypeDef def = entry.getValue();
//      if (def.name.equals(name) && def.types.equals(types)) {
//        final String moniker = entry.getKey();
//        final Type type = typeMap.get(moniker);
//        return Objects.requireNonNull(type, "type");
//      }
//    }
//    throw new AssertionError();
  }

  static class Foo {
    final Keys.DataTypeDef def;
    final TemporaryType temporaryType;
    Type type;

    Foo(Keys.DataTypeDef def, TemporaryType temporaryType) {
      this.def = def;
      this.temporaryType = temporaryType;
    }
  }
}

// End DataType.java
