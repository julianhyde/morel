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
package net.hydromatic.morel.eval;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static net.hydromatic.morel.eval.Codes.optionSome;
import static net.hydromatic.morel.util.Static.transformEager;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Objects;
import java.util.SortedMap;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.type.DataType;
import net.hydromatic.morel.type.ListType;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeCon;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.AbstractImmutableList;
import net.hydromatic.morel.util.ImmutablePairList;
import net.hydromatic.morel.util.PairList;
import org.apache.calcite.runtime.FlatLists;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A value with an explicit type.
 *
 * <p>This representation stores a Type alongside the actual Object value,
 * enabling efficient storage of homogeneous collections while maintaining full
 * type information.
 *
 * <p>For example:
 *
 * <ul>
 *   <li>{@code Value(PrimitiveType.INT, 42)} represents an {@code int};
 *   <li>{@code Value(ListType(INT), [1,2,3])} represents an {@code int list};
 *   <li>{@code Value(ListType(ValueType), [Value(...), ...])} represents a
 *       heterogeneous value list
 * </ul>
 */
public class Value extends AbstractImmutableList<Object> {
  private static final Value UNIT_VALUE =
      new Value(PrimitiveType.UNIT, Unit.INSTANCE);

  public final Type type;
  public final Object value;

  private Value(Type type, Object value) {
    this.type = requireNonNull(type, "type");
    this.value = requireNonNull(value, "value");
  }

  /** Creates a Value instance. */
  public static Value of(Type type, Object value) {
    return new Value(type, value);
  }

  /** Returns the {@code unit} instance. */
  public static Value unit() {
    return UNIT_VALUE;
  }

  /** Returns a value that wraps a {@code bool}. */
  public static Value ofBool(boolean b) {
    return new Value(PrimitiveType.BOOL, b);
  }

  /** Returns a value that wraps an {@code int}. */
  public static Value ofInt(int i) {
    return new Value(PrimitiveType.INT, i);
  }

  /** Returns a value that wraps a {@code real}. */
  public static Value ofReal(float v) {
    return new Value(PrimitiveType.REAL, v);
  }

  /** Returns a value that wraps a {@code string}. */
  public static Value ofString(String s) {
    return new Value(PrimitiveType.STRING, s);
  }

  /** Returns a value that wraps a {@code char}. */
  public static Value ofChar(char c) {
    return new Value(PrimitiveType.CHAR, c);
  }

  /** Returns a value that wraps a list with a given element type. */
  public static Value ofList(
      TypeSystem typeSystem, Type elementType, List<Value> list) {
    return new Value(typeSystem.listType(elementType), list);
  }

  /**
   * Returns a value that wraps a list of values, perhaps with the same element
   * type.
   */
  public static Value ofValueList(
      TypeSystem typeSystem, List<Value> valueList) {
    Type elementType = commonElementType(valueList);
    if (elementType != null) {
      final ListType listType = typeSystem.listType(elementType);
      final List<Object> list = transformEager(valueList, v -> v.value);
      return Value.of(listType, list);
    } else {
      // If we can't determine a common element type, fall back to 'value'
      elementType = typeSystem.lookup("value");
      return Value.ofList(typeSystem, elementType, valueList);
    }
  }

  /** Returns a value that wraps a bag (treated as list for now). */
  public static Value ofBag(
      TypeSystem typeSystem, Type elementType, List<?> list) {
    return new Value(typeSystem.bagType(elementType), list);
  }

  /**
   * Returns a value that wraps a bag of values, perhaps with the same element
   * type.
   */
  public static Value ofValueBag(TypeSystem typeSystem, List<Value> valueList) {
    Type elementType = commonElementType(valueList);
    if (elementType != null) {
      final Type bagType = typeSystem.bagType(elementType);
      final List<Object> list = transformEager(valueList, v -> v.value);
      return Value.of(bagType, list);
    } else {
      // If we can't determine a common element type, fall back to 'value'
      elementType = typeSystem.lookup("value");
      return Value.ofBag(typeSystem, elementType, valueList);
    }
  }

  /** Returns a value that wraps a vector (treated as list for now). */
  public static Value ofVector(
      TypeSystem typeSystem, Type elementType, List<?> list) {
    // TODO: proper vector type when available
    return new Value(typeSystem.vector(elementType), list);
  }

  /**
   * Returns a value that wraps a vector of values, perhaps with the same
   * element type.
   */
  public static Value ofValueVector(
      TypeSystem typeSystem, List<Value> valueList) {
    Type elementType = commonElementType(valueList);
    if (elementType != null) {
      final Type vectorType = typeSystem.vector(elementType);
      final List<Object> list = transformEager(valueList, v -> v.value);
      return Value.of(vectorType, list);
    } else {
      // If we can't determine a common element type, fall back to 'value'
      elementType = typeSystem.lookup("value");
      return Value.ofVector(typeSystem, elementType, valueList);
    }
  }

  /** Returns a value that wraps an option NONE. */
  public static Value ofNone(TypeSystem typeSystem, Type elementType) {
    return new Value(typeSystem.option(elementType), Codes.OPTION_NONE);
  }

  /** Returns a value that wraps an option SOME. */
  public static Value ofSome(TypeSystem typeSystem, Value value) {
    return new Value(typeSystem.option(value.type), optionSome(value.value));
  }

  /**
   * Returns a value that is a call to a constant (zero-argument constructor).
   *
   * <p>For example, given the datatype declaration {@code datatype foo = BAR |
   * BAZ of int}, {@code CONST "BAR"} returns a Value with type {@code foo} and
   * value {@code "BAR"}.
   */
  public static Value ofConstant(TypeSystem typeSystem, String conName) {
    TypeCon typeCon = typeSystem.lookupTyCon(conName);
    if (typeCon == null) {
      throw new IllegalArgumentException("Unknown constructor: " + conName);
    }
    return new Value(typeCon.dataType, FlatLists.of(conName));
  }

  /**
   * Returns a value that is a call to a constructor.
   *
   * <p>For example, given the datatype declaration {@code datatype foo = BAR |
   * BAZ of int}, {@code CON ("BAZ", 3)} returns a Value with type {@code foo}
   * and value {@code ("BAZ", 3)}.
   *
   * <p>For parameterized datatypes like {@code datatype 'a option = NONE | SOME
   * of 'a}, this method uses unification to determine the type parameters. For
   * instance, {@code SOME (INT 42)} unifies {@code 'a} with {@code int},
   * yielding type {@code int option}.
   */
  public static Value ofConstructor(
      TypeSystem typeSystem, String conName, Value conValue) {
    @Nullable TypeCon typeCon = typeSystem.lookupTyCon(conName);
    if (typeCon == null) {
      throw new IllegalArgumentException("Unknown constructor: " + conName);
    }

    // Get the constructor's expected argument type (may contain type variables)
    final Type constructorArgType = typeCon.argTypeKey.toType(typeSystem);

    // Unify the expected type with the actual value's type to determine
    // type variable substitutions
    final java.util.Map<Integer, Type> substitution =
        constructorArgType.unifyWith(conValue.type);

    if (substitution == null) {
      throw new IllegalArgumentException(
          format(
              "Constructor %s expects argument of type %s but got %s",
              conName, constructorArgType, conValue.type));
    }

    // Apply the substitution to the datatype to get the result type.
    // Build type args list in order by type variable ordinal.
    final ImmutableList.Builder<Type> typeArgsBuilder = ImmutableList.builder();
    for (int i = 0; i < typeCon.dataType.arguments.size(); i++) {
      final Type typeArg = substitution.get(i);
      if (typeArg != null) {
        typeArgsBuilder.add(typeArg);
      } else {
        // Type variable not constrained by constructor argument, use original
        typeArgsBuilder.add(typeCon.dataType.arguments.get(i));
      }
    }
    final Type resultType =
        typeSystem.apply(typeCon.dataType, typeArgsBuilder.build());

    return new Value(resultType, FlatLists.of(conName, conValue));
  }

  public static Value ofRecord(
      TypeSystem typeSystem, PairList<String, Value> nameValues) {
    final Type valueType = typeSystem.lookup(BuiltIn.Datatype.VALUE);
    final ImmutablePairList<String, Value> sortedNameValues =
        nameValues.withSortedKeys(Ordering.natural());
    final SortedMap<String, Value> sortedNameValueMap =
        sortedNameValues.asSortedMap();
    if (nameValues.noneMatch((name, value) -> value.type == valueType)) {
      // None of the values are variants. Create a record over the raw values.
      final SortedMap<String, Type> nameTypes =
          Maps.transformValues(sortedNameValueMap, v -> v.type);
      final Type recordType = typeSystem.recordType(nameTypes);
      final List<Object> rawValues =
          sortedNameValues.transformEager((name, value) -> value.value);
      return new Value(recordType, rawValues);
    } else {
      // Create a record whose fields all have type 'value'.
      final SortedMap<String, Type> nameTypes =
          Maps.transformValues(sortedNameValueMap, v -> valueType);
      final Type recordType = typeSystem.recordType(nameTypes);
      return new Value(recordType, sortedNameValues.rightList());
    }
  }

  @Override
  protected List<Object> toList() {
    return this;
  }

  /**
   * Converts this Value to a list with tag and value, the same format used for
   * other sum types in Morel.
   */
  private List<Object> toFlatList() {
    String tag = tag().constructor;
    return type == PrimitiveType.UNIT
        ? FlatLists.of(tag)
        : FlatLists.of(tag, value);
  }

  @Override
  public ListIterator<Object> listIterator() {
    return toFlatList().listIterator();
  }

  @Override
  public Iterator<Object> iterator() {
    return toFlatList().iterator();
  }

  @Override
  public int size() {
    return type == PrimitiveType.UNIT ? 1 : 2;
  }

  @Override
  public Object[] toArray() {
    return toFlatList().toArray();
  }

  @Override
  public <T> T[] toArray(T[] a) {
    return toFlatList().toArray(a);
  }

  @Override
  public Object get(int index) {
    return toFlatList().get(index);
  }

  @Override
  public int indexOf(Object o) {
    return toFlatList().indexOf(o);
  }

  @Override
  public int lastIndexOf(Object o) {
    return toFlatList().lastIndexOf(o);
  }

  @Override
  public boolean equals(Object obj) {
    return this == obj
        || obj instanceof Value
            // Logical equality: compare the actual values, not the type
            // representation. This means refined and unrefined values are equal
            // if
            // they represent the same logical value. For now, delegate to value
            // equality. In the future, we may need to normalize both sides
            // (e.g.,
            // unwrap collections) for true logical equality.
            && logicallyEqual(this, (Value) obj);
  }

  /**
   * Checks logical equality between two values.
   *
   * <p>Refined and unrefined representations of the same logical value should
   * be equal. For example:
   *
   * <ul>
   *   <li>{@code Value(ListType(INT), [1,2])} (refined)
   *   <li>{@code Value(ListType(ValueType), [Value(INT,1), Value(INT,2)])}
   *       (unrefined)
   * </ul>
   *
   * <p>These are logically equal even though they have different type and value
   * representations.
   */
  private static boolean logicallyEqual(Value v1, Value v2) {
    // For now, use structural equality on the wrapped values
    // TODO: Implement proper logical equality that handles refined vs unrefined
    return Objects.equals(v1.value, v2.value);
  }

  @Override
  public int hashCode() {
    return Objects.hash(type, value);
  }

  @Override
  public String toString() {
    return "Value(" + type + ", " + value + ")";
  }

  /** Returns the constructor tag for this value. */
  private BuiltIn.Constructor tag() {
    switch (type.op()) {
      case ID:
        switch ((PrimitiveType) type) {
          case UNIT:
            return BuiltIn.Constructor.VALUE_UNIT;
          case BOOL:
            return BuiltIn.Constructor.VALUE_BOOL;
          case INT:
            return BuiltIn.Constructor.VALUE_INT;
          case REAL:
            return BuiltIn.Constructor.VALUE_REAL;
          case CHAR:
            return BuiltIn.Constructor.VALUE_CHAR;
          case STRING:
            return BuiltIn.Constructor.VALUE_STRING;
          default:
            throw new IllegalArgumentException(
                "No constructor for primitive type: " + type);
        }

      case LIST:
        return BuiltIn.Constructor.VALUE_LIST;

      case RECORD_TYPE:
      case TUPLE_TYPE:
        return BuiltIn.Constructor.VALUE_RECORD;

      case DATA_TYPE:
        final DataType dataType = (DataType) type;
        if (dataType.name.equals("option")) {
          return value == Codes.OPTION_NONE
              ? BuiltIn.Constructor.VALUE_NONE
              : BuiltIn.Constructor.VALUE_SOME;
        }
        // TODO: BuiltIn.Constructor.VALUE_CON
        return BuiltIn.Constructor.VALUE_CONSTRUCT;

      default:
        throw new IllegalArgumentException(
            "No constructor for primitive type: " + type);
    }
  }

  private static @Nullable Type commonElementType(List<Value> list) {
    if (list.isEmpty()) {
      return null;
    }
    Type commonType = list.get(0).type;
    for (int i = 1; i < list.size(); i++) {
      final Type currentType = list.get(i).type;
      if (!commonType.equals(currentType)) {
        return null; // No common type
      }
    }
    return commonType;
  }
}

// End Value.java
