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

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Objects;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.AbstractImmutableList;

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
 *   <li>{@code Value(PrimitiveType.INT, 42)} represents an int
 *   <li>{@code Value(ListType(INT), [1,2,3])} represents an int list
 *   <li>{@code Value(ListType(ValueType), [Value(...), ...])} represents a
 *       heterogeneous value list
 * </ul>
 */
public class Value extends AbstractImmutableList<Object> {
  public final Type type;
  public final Object value;

  private Value(Type type, Object value) {
    this.type = requireNonNull(type, "type");
    this.value = requireNonNull(value);
  }

  /** Creates a Value instance. */
  public static Value of(Type type, Object value) {
    return new Value(type, value);
  }

  /** Returns the {@code unit} instance. */
  public static Value unit() {
    return new Value(PrimitiveType.UNIT, Unit.INSTANCE);
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
      TypeSystem typeSystem, Type elementType, List<?> list) {
    return new Value(typeSystem.listType(elementType), list);
  }

  /** Returns a value that wraps a bag (treated as list for now). */
  public static Value ofBag(
      TypeSystem typeSystem, Type elementType, List<?> list) {
    return new Value(typeSystem.bagType(elementType), list);
  }

  /** Returns a value that wraps a vector (treated as list for now). */
  public static Value ofVector(
      TypeSystem typeSystem, Type elementType, List<?> list) {
    // TODO: proper vector type when available
    return new Value(typeSystem.vector(elementType), list);
  }

  /** Returns a value that wraps an option NONE. */
  public static Value ofNone(TypeSystem typeSystem, Type elementType) {
    return new Value(typeSystem.option(elementType), Codes.OPTION_NONE);
  }

  /** Returns a value that wraps an option SOME. */
  public static Value ofSome(
      TypeSystem typeSystem, Type elementType, Object value) {
    return new Value(typeSystem.option(elementType), value);
  }

  @Override
  protected List<Object> toList() {
    return this;
  }

  @Override
  public int size() {
    return Values.toList(this).size();
  }

  @Override
  public Object[] toArray() {
    return toList().toArray();
  }

  @Override
  public <T> T[] toArray(T[] a) {
    return toList().toArray(a);
  }

  @Override
  public Object get(int index) {
    return Values.toList(this).get(index);
  }

  @Override
  public int indexOf(Object o) {
    return toList().indexOf(o);
  }

  @Override
  public int lastIndexOf(Object o) {
    return toList().lastIndexOf(o);
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof Value)) {
      return false;
    }
    Value that = (Value) obj;
    // Logical equality: compare the actual values, not the type representation
    // This means refined and unrefined values are equal if they represent
    // the same logical value.
    // For now, delegate to value equality. In the future, we may need to
    // normalize both sides (e.g., unwrap collections) for true logical
    // equality.
    return logicallyEqual(this, that);
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
}

// End Value.java
