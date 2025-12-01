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

import java.util.List;
import java.util.Objects;
import net.hydromatic.morel.type.Type;

/**
 * A value with an explicit type.
 *
 * <p>This representation stores a Type alongside the actual Object value,
 * enabling efficient storage of homogeneous collections while maintaining
 * full type information.
 *
 * <p>For example:
 * <ul>
 *   <li>{@code Value(PrimitiveType.INT, 42)} represents an int
 *   <li>{@code Value(ListType(INT), [1,2,3])} represents an int list
 *   <li>{@code Value(ListType(ValueType), [Value(...), ...])} represents
 *       a heterogeneous value list
 * </ul>
 */
public class Value {
  public final Type type;
  public final Object value;

  private Value(Type type, Object value) {
    this.type = Objects.requireNonNull(type, "type");
    this.value = value; // null is allowed for UNIT
  }

  /** Creates a Value instance. */
  public static Value of(Type type, Object value) {
    return new Value(type, value);
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
    // normalize both sides (e.g., unwrap collections) for true logical equality.
    return logicallyEqual(this, that);
  }

  /**
   * Checks logical equality between two values.
   *
   * <p>Refined and unrefined representations of the same logical value
   * should be equal. For example:
   * <ul>
   *   <li>{@code Value(ListType(INT), [1,2])} (refined)
   *   <li>{@code Value(ListType(ValueType), [Value(INT,1), Value(INT,2)])} (unrefined)
   * </ul>
   * These are logically equal even though they have different type and value representations.
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
