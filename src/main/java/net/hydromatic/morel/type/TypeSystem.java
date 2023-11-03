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
import net.hydromatic.morel.compile.NameGenerator;
import net.hydromatic.morel.eval.Codes;
import net.hydromatic.morel.type.Type.Key;
import net.hydromatic.morel.util.ComparableSingletonList;
import net.hydromatic.morel.util.Pair;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.function.Function;

import static net.hydromatic.morel.ast.CoreBuilder.core;
import static net.hydromatic.morel.util.Ord.forEachIndexed;
import static net.hydromatic.morel.util.Pair.forEach;
import static net.hydromatic.morel.util.Static.toImmutableList;

/** A table that contains all types in use, indexed by their description (e.g.
 * "{@code int -> int}"). */
public class TypeSystem {
  final Map<String, Type> typeByName = new HashMap<>();
  final Map<String, Type> internalTypeByName = new HashMap<>();
  final Map<Key, Type> typeByKey = new HashMap<>();

  private final Map<String, Pair<DataType, Type.Key>> typeConstructorByName =
      new HashMap<>();

  public final NameGenerator nameGenerator = new NameGenerator();

  public TypeSystem() {
    for (PrimitiveType primitiveType : PrimitiveType.values()) {
      typeByName.put(primitiveType.moniker, primitiveType);
    }
  }

  /** Creates a binding of a type constructor value. */
  public Binding bindTyCon(DataType dataType, String tyConName) {
    final Type type = dataType.typeConstructors(this).get(tyConName);
    if (type == DummyType.INSTANCE) {
      return Binding.of(core.idPat(dataType, tyConName, 0),
          Codes.constant(ComparableSingletonList.of(tyConName)));
    } else {
      final Type type2 = wrap(dataType, fnType(type, dataType));
      return Binding.of(core.idPat(type2, tyConName, 0),
          Codes.tyCon(dataType, tyConName));
    }
  }

  private Type wrap(DataType dataType, Type type) {
    final List<TypeVar> typeVars =
        dataType.parameterTypes.stream().filter(t -> t instanceof TypeVar)
            .map(t -> (TypeVar) t)
            .collect(toImmutableList());
    return typeVars.isEmpty() ? type : forallType(typeVars.size(), type);
  }

  /** Looks up an internal type by name. */
  public Type lookupInternal(String name) {
    final Type type = internalTypeByName.get(name);
    if (type == null) {
      throw new AssertionError("unknown type: " + name);
    }
    return type;
  }

  /** Looks up a type by name. */
  public Type lookup(String name) {
    final Type type = typeByName.get(name);
    if (type == null) {
      throw new AssertionError("unknown type: " + name);
    }
    return type;
  }

  /** Looks up a type by name, returning null if not found. */
  public Type lookupOpt(String name) {
    // TODO: only use this for names, e.g. 'option',
    // not monikers e.g. 'int option';
    // assert !name.contains(" ") : name;
    return typeByName.get(name);
  }

  /** Gets a type that matches a key, creating if necessary. */
  public Type typeFor(Key key) {
    Type type = typeByKey.get(key);
    if (type == null) {
      type = key.toType(this);
      typeByKey.putIfAbsent(key, type);
    }
    return type;
  }

  /** Converts a list of keys to a list of types. */
  public List<Type> typesFor(Iterable<? extends Key> keys) {
    final ImmutableList.Builder<Type> types = ImmutableList.builder();
    keys.forEach(key -> types.add(key.toType(this)));
    return types.build();
  }

  /** Converts a map of keys to a map of types. */
  public SortedMap<String, Type> typesFor(Map<String, ? extends Key> keys) {
    final ImmutableSortedMap.Builder<String, Type> types =
        ImmutableSortedMap.orderedBy(RecordType.ORDERING);
    keys.forEach((name, key) -> types.put(name, key.toType(this)));
    return types.build();
  }

  /** Creates a multi-step function type.
   *
   * <p>For example, {@code fnType(a, b, c, d)} returns the same as
   * {@code fnType(a, fnType(b, fnType(c, d)))},
   * viz <code>a &rarr; b &rarr; c &rarr; d</code>. */
  public Type fnType(Type paramType, Type type1, Type type2,
      Type... moreTypes) {
    final List<Type> types = ImmutableList.<Type>builder()
        .add(paramType).add(type1).add(type2).add(moreTypes).build();
    Type t = null;
    for (Type type : Lists.reverse(types)) {
      if (t == null) {
        t = type;
      } else {
        t = fnType(type, t);
      }
    }
    return Objects.requireNonNull(t);
  }

  /** Creates a function type. */
  public FnType fnType(Type paramType, Type resultType) {
    return (FnType) typeFor(Keys.fn(paramType.key(), resultType.key()));
  }

  /** Creates a tuple type from an array of types. */
  public TupleType tupleType(Type argType0, Type... argTypes) {
    return (TupleType) tupleType(Lists.asList(argType0, argTypes));
  }

  /** Creates a tuple type. */
  public RecordLikeType tupleType(List<? extends Type> argTypes) {
    return (RecordLikeType) typeFor(Keys.tuple(Keys.toKeys(argTypes)));
  }

  /** Creates a list type. */
  public ListType listType(Type elementType) {
    return (ListType) typeFor(Keys.list(elementType.key()));
  }

  /** Creates several data types simultaneously. */
  public List<Type> dataTypes(List<Keys.DataTypeDef> defs) {
    final Map<Type.Key, Type> dataTypeMap = new LinkedHashMap<>();
    defs.forEach(def -> {
      final Key key = Keys.name(def.name);
      final DataType dataType = def.toType(this);
      typeByKey.put(key, dataType);

      final ForallType forallType = forallType(def.parameters.size(), dataType);
      typeByName.put(def.name, forallType);
      dataType.typeConstructors.forEach((name3, typeKey) ->
          typeConstructorByName.put(name3, Pair.of(dataType, typeKey)));
      dataTypeMap.put(key, dataType);
    });
    final ImmutableList.Builder<Type> types = ImmutableList.builder();
    forEach(defs, dataTypeMap.values(), (def, dataType) -> {
      if (def.parameters.isEmpty()) {
        typeByName.put(def.name, dataType);
        types.add(dataType);
      } else {
        // We have just created an entry for the moniker (e.g. "'a option"),
        // so now create an entry for the name (e.g. "option").
        final ForallType forallType =
            forallType(def.parameters.size(), dataType);
        typeByName.put(def.name, forallType);
        types.add(forallType);
      }
    });
    return types.build();
  }

  /** Creates an algebraic type.
   *
   * <p>Parameter types is empty unless this is a type scheme.
   * For example,
   *
   * <ul>
   *   <li>{@code datatype 'a option = NONE | SOME of 'a} has
   *   parameter types and argument types {@code ['a]},
   *   type constructors {@code [NONE: dummy, SOME: 'a]};
   *   <li>{@code int option} has empty parameter types,
   *   argument types {@code [int]},
   *   type constructors {@code [NONE: dummy, SOME: int]};
   *   <li>{@code datatype color = RED | GREEN} has
   *   empty parameter types and argument types,
   *   type constructors {@code [RED: dummy, GREEN: dummy]}.
   * </ul>
   *
   * @param name Name (e.g. "option")
   * @param parameterCount Number of type parameters
   * @param argumentTypes Argument types
   * @param tyCons Type constructors
   */
  DataType dataType(String name, int parameterCount,
      List<? extends Type> argumentTypes, SortedMap<String, Type.Key> tyCons) {
    final String moniker = DataType.computeMoniker(name, argumentTypes);
    final DataType dataType =
        new DataType(Op.DATA_TYPE, name, moniker, parameterCount, argumentTypes,
            tyCons);
    if (parameterCount == 0) {
      // There are no type parameters, therefore there will be no ForallType to
      // register.
      tyCons.forEach((name3, typeKey) ->
          typeConstructorByName.put(name3, Pair.of(dataType, typeKey)));
    }
    return dataType;
  }

  /** Converts a regular type to an internal type. Throws if the type is not
   * known. */
  public void setInternal(String name) {
    final Type type = typeByName.remove(name);
    internalTypeByName.put(name, type);
  }

  /** Creates a data type scheme: a datatype if there are no type arguments
   * (e.g. "{@code ordering}"), or a forall type if there are type arguments
   * (e.g. "{@code forall 'a . 'a option}"). */
  public Type dataTypeScheme(String name, List<TypeVar> parameters,
      SortedMap<String, Type.Key> tyCons) {
    final List<Key> keys = Keys.toKeys(parameters);
    final Keys.DataTypeDef def = Keys.dataTypeDef(name, keys, keys, tyCons);
    return dataTypes(ImmutableList.of(def)).get(0);
  }

  /** Creates a record type, or returns a scalar type if {@code argNameTypes}
   * has one entry. */
  public Type recordOrScalarType(
      SortedMap<String, ? extends Type> argNameTypes) {
    switch (argNameTypes.size()) {
    case 1:
      return Iterables.getOnlyElement(argNameTypes.values());
    default:
      return recordType(argNameTypes);
    }
  }

  /** Creates a record type. (Or a tuple type if the fields are named "1", "2"
   * etc.; or "unit" if the field list is empty.) */
  public RecordLikeType recordType(SortedMap<String, ? extends Type> argNameTypes) {
    if (argNameTypes.isEmpty()) {
      return PrimitiveType.UNIT;
    }
    final ImmutableSortedMap<String, Type> argNameTypes2 =
        ImmutableSortedMap.copyOfSorted(argNameTypes);
    if (areContiguousIntegers(argNameTypes2.keySet())
        && argNameTypes2.size() != 1) {
      return tupleType(ImmutableList.copyOf(argNameTypes2.values()));
    }
    return (RecordLikeType) typeFor(Keys.record(Keys.toKeys(argNameTypes2)));
  }

  /** Returns whether the collection is ["1", "2", ... n]. */
  public static boolean areContiguousIntegers(Iterable<String> strings) {
    int i = 1;
    for (String string : strings) {
      if (!string.equals(Integer.toString(i++))) {
        return false;
      }
    }
    return true;
  }

  /** Creates a "forall" type. */
  public Type forallType(int typeCount, Function<ForallHelper, Type> builder) {
    final ForallHelper helper = new ForallHelper() {
      public TypeVar get(int i) {
        return typeVariable(i);
      }

      public ListType list(int i) {
        return listType(get(i));
      }

      public Type vector(int i) {
        return TypeSystem.this.vector(get(i));
      }

      public Type option(int i) {
        return TypeSystem.this.option(get(i));
      }

      public FnType predicate(int i) {
        return fnType(get(i), PrimitiveType.BOOL);
      }
    };
    final Type type = builder.apply(helper);
    return forallType(typeCount, type);
  }

  /** Creates a "for all" type. */
  public ForallType forallType(int typeCount, Type type) {
    final Key key = Keys.forall(type, typeCount);
    return (ForallType) typeFor(key);
  }

  static StringBuilder unparseList(StringBuilder builder, Op op, int left,
      int right, Collection<? extends Type.Key> argTypes) {
    if (op == Op.COMMA && argTypes.size() != 1 && !(left == 0 && right == 0)) {
      builder.append('(');
      unparseList(builder, op, 0, 0, argTypes);
      builder.append(')');
    } else {
      forEachIndexed(argTypes, (type, i) -> {
        if (i > 0) {
          builder.append(op.padded);
        }
        unparse(builder, type,
            i == 0 ? left : op.right,
            i == argTypes.size() - 1 ? right : op.left);
      });
    }
    return builder;
  }

  static StringBuilder unparse(StringBuilder builder, Type.Key type, int left,
      int right) {
    final Op op = type.op();
    if (left > op.left || op.right < right) {
      builder.append("(");
      unparse(builder, type, 0, 0);
      return builder.append(")");
    } else {
      return type.describe(builder, left, right);
    }
  }

  /** Creates a temporary type.
   *
   * <p>(Temporary types exist for a brief period while defining a recursive
   * {@code datatype}.) */
  public TemporaryType temporaryType(String name,
      List<? extends Type> parameterTypes, Transaction transaction_,
      boolean withScheme) {
    final TemporaryType temporaryType =
        new TemporaryType(name, parameterTypes);
    final TransactionImpl transaction = (TransactionImpl) transaction_;
    transaction.put(temporaryType.moniker, temporaryType);
    if (withScheme && !parameterTypes.isEmpty()) {
      transaction.put(name,
          new ForallType(parameterTypes.size(), temporaryType));
    }
    return temporaryType;
  }

  public List<TypeVar> typeVariables(int size) {
    return new AbstractList<TypeVar>() {
      public int size() {
        return size;
      }

      public TypeVar get(int index) {
        return typeVariable(index);
      }
    };
  }

  public Pair<DataType, Type.Key> lookupTyCon(String tyConName) {
    return typeConstructorByName.get(tyConName);
  }

  public Type apply(Type type, Type... types) {
    return apply(type, ImmutableList.copyOf(types));
  }

  public Type apply(Type type, List<Type> types) {
    if (type instanceof TemporaryType) {
      final TemporaryType temporaryType = (TemporaryType) type;
      if (types.equals(temporaryType.parameterTypes)) {
        return type;
      }
      throw new AssertionError();
    }
    if (type instanceof ForallType) {
      final ForallType forallType = (ForallType) type;
      try (Transaction transaction = transaction()) {
        return forallType.substitute(this, types, transaction);
      }
    }
    if (type instanceof DataType) {
      final DataType dataType = (DataType) type;
      try (Transaction transaction = transaction()) {
        return dataType.substitute(this, types, transaction);
      }
    }
    throw new AssertionError();
  }

  /** Creates a type variable. */
  public TypeVar typeVariable(int ordinal) {
    return (TypeVar) typeFor(Keys.ordinal(ordinal));
  }

  /** Creates an "option" type.
   *
   * <p>"option(type)" is shorthand for "apply(lookup("option"), type)". */
  public Type option(Type type) {
    final Type optionType = lookup("option");
    return apply(optionType, type);
  }

  /** Creates a "vector" type.
   *
   * <p>"vector(type)" is shorthand for "apply(lookup("vector"), type)". */
  public Type vector(Type type) {
    final Type vectorType = lookup("vector");
    return apply(vectorType, type);
  }

  /** Converts a type into a {@link ForallType} if it has free type
   * variables. */
  public Type ensureClosed(Type type) {
    final VariableCollector collector = new VariableCollector();
    type.accept(collector);
    if (collector.vars.isEmpty()) {
      return type;
    }
    final TypeSystem ts = this;
    return forallType(collector.vars.size(), h ->
        type.copy(ts, t ->
            t instanceof TypeVar ? h.get(((TypeVar) t).ordinal) : t));
  }

  public TypeSystem.Transaction transaction() {
    return new TransactionImpl();
  }

  /** Holds temporary changes to the type system. */
  public interface Transaction extends AutoCloseable {
    void close();
  }

  /** Implementation of {@link Transaction}. */
  private class TransactionImpl implements Transaction {
    final List<String> names = new ArrayList<>();

    void put(String moniker, Type type) {
      typeByName.put(moniker, Objects.requireNonNull(type));
      names.add(moniker);
    }

    public void close() {
      for (String name : names) {
        typeByName.remove(name);
      }
      names.clear();
    }
  }

  /** Visitor that finds all {@link TypeVar} instances within a {@link Type}. */
  private static class VariableCollector extends TypeVisitor<Void> {
    final Set<TypeVar> vars = new LinkedHashSet<>();

    @Override public Void visit(DataType dataType) {
      return null; // ignore type variables in the datatype
    }

    @Override public Void visit(TypeVar typeVar) {
      vars.add(typeVar);
      return super.visit(typeVar);
    }
  }

  /** Provides access to type variables from within a call to
   * {@link TypeSystem#forallType(int, Function)}. */
  public interface ForallHelper {
    /** Creates type {@code `i}. */
    TypeVar get(int i);
    /** Creates type {@code `i list}. */
    ListType list(int i);
    /** Creates type {@code `i vector}. */
    Type vector(int i);
    /** Creates type {@code `i option}. */
    Type option(int i);
    /** Creates type <code>`i &rarr; bool</code>. */
    FnType predicate(int i);
  }
}

// End TypeSystem.java
