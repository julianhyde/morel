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
import static net.hydromatic.morel.util.Static.skip;
import static net.hydromatic.morel.util.Static.transformEager;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.function.BiConsumer;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.type.DataType;
import net.hydromatic.morel.type.ListType;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.RecordType;
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
    // Create a list value with the actual list type
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
    // Create a bag value with the actual bag type
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
    // Create a vector value with the actual vector type
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
   * BAZ of int}, {@code CONSTANT "BAR"} returns a Value with type {@code foo}
   * and value {@code "BAR"}.
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
   * BAZ of int}, {@code CONSTRUCT ("BAZ", INT 3)} returns a Value with type
   * {@code foo} and value {@code ("BAZ", 3)}.
   *
   * <p>For parameterized datatypes like {@code datatype 'a option = NONE | SOME
   * of 'a}, this method uses unification to determine the type parameters. For
   * instance, {@code CONSTRUCT ("SOME", INT 42)} unifies {@code 'a} with {@code
   * int}, yielding type {@code int option}.
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
    // type variable substitutions.
    final Map<Integer, Type> substitution =
        constructorArgType.unifyWith(conValue.type);

    if (substitution == null) {
      throw new IllegalArgumentException(
          format(
              "Constructor %s expects argument of type %s but got %s",
              conName, constructorArgType, conValue.type));
    }

    // Apply the substitution to the datatype to get the result type.
    // Build type args list in order by type variable ordinal, using the
    // substituted type if available, otherwise the original type variable.
    final ImmutableList.Builder<Type> typeArgsBuilder = ImmutableList.builder();
    for (int i = 0; i < typeCon.dataType.arguments.size(); i++) {
      final Type substitutedType = substitution.get(i);
      typeArgsBuilder.add(
          substitutedType != null
              ? substitutedType
              : typeCon.dataType.arguments.get(i));
    }
    final Type resultType =
        typeCon.dataType.substitute(typeSystem, typeArgsBuilder.build());

    // Unwrap the value before storing - consistent with optionSome behavior
    return new Value(resultType, FlatLists.of(conName, conValue.value));
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

  public static boolean bindConsPat(
      BiConsumer<Core.NamedPat, Object> envRef,
      Value value,
      Core.ConPat consPat) {
    ListType listType = (ListType) value.type;
    @SuppressWarnings("unchecked")
    final List<Object> consValue = (List<Object>) value.value;
    if (consValue.isEmpty()) {
      return false;
    }
    final Value head = of(listType.elementType, consValue.get(0));
    final List<Value> tail =
        transformEager(
            skip(consValue),
            e -> e instanceof Value ? (Value) e : of(listType.elementType, e));
    List<Core.Pat> patArgs = ((Core.TuplePat) consPat.pat).args;
    return Closure.bindRecurse(patArgs.get(0), head, envRef)
        && Closure.bindRecurse(patArgs.get(1), tail, envRef);
  }

  public static boolean bindConPat(
      BiConsumer<Core.NamedPat, Object> envRef,
      Value value,
      Core.ConPat conPat) {
    final String constructorName = Values.getConstructorName(value);
    if (!constructorName.equals(conPat.tyCon)) {
      return false;
    }
    // For list types, rewrap elements as Values if needed
    // For record types, reconstruct (name, value) pairs
    Object innerValue;
    if (value.type instanceof ListType) {
      final ListType listType = (ListType) value.type;
      final List<?> list = (List<?>) value.value;
      // Check if elements are already Values
      if (!list.isEmpty() && !(list.get(0) instanceof Value)) {
        // Elements are unwrapped, rewrap them
        innerValue =
            transformEager(
                list,
                e ->
                    e instanceof Value
                        ? (Value) e
                        : of(listType.elementType, e));
      } else {
        innerValue = value.value;
      }
    } else if (value.type instanceof RecordType) {
      final RecordType recordType = (RecordType) value.type;
      final List<?> fieldValues = (List<?>) value.value;
      // Reconstruct (name, value) pairs for pattern matching
      // Pattern expects a list of [name, value] pairs
      final ImmutableList.Builder<List<Object>> pairsBuilder =
          ImmutableList.builder();
      int i = 0;
      for (Map.Entry<String, Type> entry : recordType.argNameTypes.entrySet()) {
        final String name = entry.getKey();
        final Type fieldType = entry.getValue();
        final Object rawValue = fieldValues.get(i++);
        final Value fieldValue =
            rawValue instanceof Value
                ? (Value) rawValue
                : of(fieldType, rawValue);
        pairsBuilder.add(ImmutableList.of(name, fieldValue));
      }
      innerValue = pairsBuilder.build();
    } else {
      innerValue = value.value;
    }
    return Closure.bindRecurse(conPat.pat, innerValue, envRef);
  }

  /**
   * Converts this Value to a list with tag and value, the same format used for
   * other sum types in Morel.
   */
  private List<Object> toFlatList() {
    String tag = tag().constructor;
    return type == PrimitiveType.UNIT
        ? FlatLists.of(tag)
        : FlatLists.of(tag, this);
  }

  @Override
  public int size() {
    // Equivalent to "toFlatList().size()"
    return type == PrimitiveType.UNIT ? 1 : 2;
  }

  @Override
  public Object[] toArray() {
    // Equivalent to "toFlatList().toArray()"
    String tag = tag().constructor;
    return type == PrimitiveType.UNIT
        ? new Object[] {tag}
        : new Object[] {tag, this};
  }

  @Override
  public <T> T[] toArray(T[] a) {
    return toFlatList().toArray(a);
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
  public ListIterator<Object> listIterator(int index) {
    return toFlatList().listIterator(index);
  }

  @Override
  public List<Object> subList(int fromIndex, int toIndex) {
    if (fromIndex == 1 && toIndex == 2 && type != PrimitiveType.UNIT) {
      // Equivalent to "toFlatList().subList(fromIndex, toIndex)", but optimized
      return FlatLists.of(this);
    }
    return toFlatList().subList(fromIndex, toIndex);
  }

  @Override
  public Object get(int index) {
    // Equivalent to "toFlatList().get(index)", but optimized
    switch (index) {
      case 0:
        return tag().constructor;
      case 1:
        if (type != PrimitiveType.UNIT) {
          //          return value;
          return this;
        }
        // fall through
      default:
        throw new IndexOutOfBoundsException(
            "Index: " + index + ", Size: " + size());
    }
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

  /**
   * Converts a Value to its string representation.
   *
   * <p>Handles both refined (specific types like {@code int list}) and
   * unrefined (general types like {@code value list}) representations.
   *
   * @see net.hydromatic.morel.compile.BuiltIn#VALUE_PRINT
   */
  public String print() {
    // Handle primitive types
    if (type.op() == Op.ID) {
      switch ((PrimitiveType) type) {
        case UNIT:
          return "UNIT";
        case BOOL:
          return "BOOL " + value;
        case INT:
          final int intVal = (Integer) value;
          return "INT "
              + (intVal < 0 ? "~" + (-intVal) : String.valueOf(intVal));
        case REAL:
          final float realVal = (Float) value;
          return "REAL "
              + (realVal < 0 ? "~" + (-realVal) : String.valueOf(realVal));
        case CHAR:
          final Character ch = (Character) value;
          return "CHAR #\"" + charEscape(ch) + "\"";
        case STRING:
          final String str = (String) value;
          return "STRING \"" + stringEscape(str) + "\"";
        default:
          throw new AssertionError();
      }
    }

    // For more complex values, delegate to append. A single StringBuilder
    // avoids excessive string concatenation.
    StringBuilder buf = new StringBuilder();
    append(buf);
    return buf.toString();
  }

  /**
   * Appends the string representation of this Value to a StringBuilder.
   *
   * @see #print()
   */
  private StringBuilder append(StringBuilder buf) {
    if (type.op() == Op.ID) {
      switch ((PrimitiveType) type) {
        case UNIT:
          return buf.append("UNIT");
        case BOOL:
          return buf.append("BOOL ").append(value);
        case INT:
          final int intVal = (Integer) value;
          return buf.append("INT ")
              .append(intVal < 0 ? "~" + (-intVal) : String.valueOf(intVal));
        case REAL:
          final float realVal = (Float) value;
          return buf.append("REAL ")
              .append(realVal < 0 ? "~" + (-realVal) : String.valueOf(realVal));
        case CHAR:
          final Character ch = (Character) value;
          return buf.append("CHAR #\"").append(charEscape(ch)).append("\"");
        case STRING:
          final String str = (String) value;
          return buf.append("STRING \"").append(stringEscape(str)).append("\"");
        default:
          throw new AssertionError();
      }
    }

    // Handle list types
    if (type instanceof ListType) {
      final ListType listType = (ListType) type;
      return appendList(buf, "LIST [", value, listType.elementType, "]");
    }

    // Handle DataTypes (bag, vector, option, custom datatypes)
    if (type instanceof DataType) {
      final DataType dataType = (DataType) type;

      final Type elementType;
      switch (dataType.name) {
        case "bag":
          elementType = dataType.arguments.get(0);
          return appendList(buf, "BAG [", value, elementType, "]");

        case "vector":
          elementType = dataType.arguments.get(0);
          return appendList(buf, "VECTOR [", value, elementType, "]");

        case "option":
          if (value == Codes.OPTION_NONE) {
            return buf.append("VALUE_NONE");
          } else if (value instanceof Value) {
            buf.append("VALUE_SOME ");
            return ((Value) value).append(buf);
          } else if (value instanceof List) {
            // Refined option stored as ["SOME", innerValue]
            final List<?> optionList = (List<?>) value;
            if (optionList.size() == 2 && "SOME".equals(optionList.get(0))) {
              final Object innerVal = optionList.get(1);
              final Type innerType = dataType.arguments.get(0);
              final Value innerValue =
                  innerVal instanceof Value
                      ? (Value) innerVal
                      : Value.of(innerType, innerVal);
              buf.append("VALUE_SOME ");
              return innerValue.append(buf);
            }
            throw new IllegalArgumentException(
                "Invalid option value: " + optionList);
          } else {
            // Refined option: single value (SOME case)
            final Type innerType = dataType.arguments.get(0);
            buf.append("VALUE_SOME ");
            return Value.of(innerType, value).append(buf);
          }
      }

      // Handle other datatype constructors
      if (value instanceof List) {
        final List<?> conList = (List<?>) value;
        if (!conList.isEmpty() && conList.get(0) instanceof String) {
          final String conName = (String) conList.get(0);
          if (conList.size() == 1) {
            // Nullary constructor - use CONSTANT
            return buf.append("CONSTANT \"").append(conName).append("\"");
          } else if (conList.size() == 2) {
            // Unary constructor - use CONSTRUCT
            final Object conArg = conList.get(1);
            // conArg should be unwrapped due to ofConstructor change
            // Need to determine its type - use datatype's argument type
            final Value conArgValue;
            if (conArg instanceof Value) {
              conArgValue = (Value) conArg;
            } else {
              // Unwrapped value - wrap it with the datatype's argument type
              final Type argType =
                  dataType.arguments.isEmpty()
                      ? PrimitiveType.UNIT
                      : dataType.arguments.get(0);
              conArgValue = Value.of(argType, conArg);
            }
            buf.append("CONSTRUCT (\"").append(conName).append("\", ");
            conArgValue.append(buf);
            return buf.append(')');
          }
        }
      }
      throw new IllegalArgumentException(
          "Cannot print datatype value: " + dataType.name);
    }

    // Handle record types
    if (type instanceof RecordType) {
      final RecordType recordType = (RecordType) type;
      @SuppressWarnings("unchecked")
      final List<Object> recordValues = (List<Object>) value;
      return appendRecordPairs(buf, "RECORD [", recordType, recordValues, "]");
    }

    throw new IllegalArgumentException("Cannot print value of type: " + type);
  }

  /** Helper for print: escapes a character for printing. */
  private static String charEscape(Character ch) {
    switch (ch) {
      case '\n':
        return "\\n";
      case '\t':
        return "\\t";
      case '\r':
        return "\\r";
      case '\\':
        return "\\\\";
      case '"':
        return "\\\"";
      default:
        return String.valueOf(ch);
    }
  }

  /** Helper for print: escapes a string for printing. */
  private static String stringEscape(String str) {
    final StringBuilder buf = new StringBuilder();
    for (int i = 0; i < str.length(); i++) {
      buf.append(charEscape(str.charAt(i)));
    }
    return buf.toString();
  }

  /**
   * Helper for print: prints a list of Value instances (unrefined) or raw
   * values (refined).
   */
  @SuppressWarnings({"SameParameterValue", "rawtypes", "unchecked"})
  private static StringBuilder appendList(
      StringBuilder buf,
      String prefix,
      Object val,
      Type elementType,
      String suffix) {
    buf.append(prefix);
    final List list = (List) val;
    if (!list.isEmpty() && list.get(0) instanceof Value) {
      final List<Value> values = (List<Value>) list;
      for (int i = 0; i < values.size(); i++) {
        if (i > 0) {
          buf.append(", ");
        }
        values.get(i).append(buf);
      }
    } else {
      final List<Object> values = (List<Object>) list;
      for (int i = 0; i < values.size(); i++) {
        if (i > 0) {
          buf.append(", ");
        }
        // Wrap each raw value in a Value instance for printing.
        Value.of(elementType, values.get(i)).append(buf);
      }
    }
    buf.append(suffix);
    return buf;
  }

  /** Helper for print: prints record as list of (name, value) pairs. */
  @SuppressWarnings("SameParameterValue")
  private static StringBuilder appendRecordPairs(
      StringBuilder buf,
      String prefix,
      RecordType recordType,
      List<Object> values,
      String suffix) {
    buf.append(prefix);
    final Map<String, Type> fields = recordType.argNameTypes();
    int i = 0;
    for (Map.Entry<String, Type> entry : fields.entrySet()) {
      if (i > 0) {
        buf.append(", ");
      }
      final String fieldName = entry.getKey();
      final Type fieldType = entry.getValue();
      final Object fieldValue = values.get(i);

      buf.append("(\"").append(fieldName).append("\", ");
      Value.of(fieldType, fieldValue).append(buf);
      buf.append(")");
      i++;
    }
    buf.append(suffix);
    return buf;
  }
}

// End Value.java
