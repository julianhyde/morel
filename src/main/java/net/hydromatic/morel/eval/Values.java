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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Ordering;
import java.util.List;
import net.hydromatic.morel.type.DataType;
import net.hydromatic.morel.type.ListType;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.Ord;
import net.hydromatic.morel.util.PairList;

/**
 * Utilities for the Value structure - universal value representation for
 * embedded language interoperability.
 *
 * <p>Provides parsing and printing functions for the {@code value} datatype
 * defined in value.sig.
 */
public class Values {
  private Values() {
    // Utility class, no instances
  }

  /**
   * Creates a Value instance from a VALUE constructor name and argument.
   *
   * <p>Used by VALUE constructors (e.g., {@code INT 42}) to create Value
   * instances at runtime.
   *
   * @param name Constructor name (e.g., "INT", "BOOL", "LIST")
   * @param arg Constructor argument
   * @param typeSystem Type system for looking up types
   * @return Value instance with appropriate type
   */
  @SuppressWarnings("unchecked")
  public static Value fromConstructor(
      String name, Object arg, TypeSystem typeSystem) {
    switch (name) {
      case "UNIT":
        return Value.unit();
      case "BOOL":
        return Value.ofBool((Boolean) arg);
      case "INT":
        return Value.ofInt((Integer) arg);
      case "REAL":
        return Value.ofReal((Float) arg);
      case "CHAR":
        return Value.ofChar((Character) arg);
      case "STRING":
        return Value.ofString((String) arg);
      case "LIST":
        return Value.ofValueList(typeSystem, (List<Value>) arg);
      case "BAG":
        return Value.ofValueBag(typeSystem, (List<Value>) arg);
      case "VECTOR":
        return Value.ofValueVector(typeSystem, (List<Value>) arg);
      case "VALUE_NONE":
        {
          final Type valueType = typeSystem.lookup("value");
          return Value.ofNone(typeSystem, valueType);
        }
      case "VALUE_SOME":
        return Value.ofSome(typeSystem, (Value) arg);
      case "RECORD":
        // arg is a list of (name, value) pairs
        PairList<String, Value> nameValues =
            PairList.fromTransformed(
                (List<List<Object>>) arg,
                (lists, consumer) ->
                    consumer.accept(
                        (String) lists.get(0), (Value) lists.get(1)));
        nameValues = nameValues.withSortedKeys(Ordering.natural());
        return Value.ofRecord(typeSystem, nameValues);
      case "CONSTANT":
        // Nullary datatype constructor
        return Value.ofConstant(typeSystem, (String) arg);
      case "CONSTRUCT":
        // Unary datatype constructor with value
        final List<Object> list = (List<Object>) arg;
        final String conName = (String) list.get(0);
        final Value conArg = (Value) list.get(1);
        return Value.ofConstructor(typeSystem, conName, conArg);
      default:
        throw new IllegalArgumentException(
            String.format("Unknown VALUE constructor: %s", name));
    }
  }

  /**
   * Extracts the constructor name from a Value instance.
   *
   * <p>Returns the VALUE datatype constructor name (e.g., "INT", "LIST",
   * "BOOL") for a given Value instance, or null if the Value doesn't correspond
   * to a simple constructor.
   *
   * @param value Value instance
   * @return Constructor name, or null if not applicable
   */
  public static String getConstructorName(Value value) {
    final Type type = value.type;

    // Primitive types
    if (type == PrimitiveType.UNIT) {
      return "UNIT";
    }
    if (type == PrimitiveType.BOOL) {
      return "BOOL";
    }
    if (type == PrimitiveType.INT) {
      return "INT";
    }
    if (type == PrimitiveType.REAL) {
      return "REAL";
    }
    if (type == PrimitiveType.CHAR) {
      return "CHAR";
    }
    if (type == PrimitiveType.STRING) {
      return "STRING";
    }

    // List types - we can't distinguish LIST, BAG, VECTOR from type alone
    // For now, assume LIST (this is a limitation)
    if (type instanceof ListType) {
      return "LIST";
    }

    // DataType for option
    if (type instanceof DataType) {
      final DataType dataType = (DataType) type;
      if (dataType.name.equals("option")) {
        return value.value == null ? "VALUE_NONE" : "VALUE_SOME";
      }
    }

    // Record types
    if (type instanceof RecordType) {
      return "RECORD";
    }

    // For other types, we can't determine a simple constructor name
    return null;
  }

  /**
   * Converts a Value to its string representation.
   *
   * <p>Handles both refined (specific types like {@code int list}) and
   * unrefined (general types like {@code value list}) representations.
   *
   * @see net.hydromatic.morel.compile.BuiltIn#VALUE_PRINT
   */
  public static String print(Value value) {
    final Type type = value.type;
    final Object val = value.value;

    // Handle primitive types
    if (type == PrimitiveType.UNIT) {
      return "()";
    }
    if (type == PrimitiveType.BOOL) {
      return String.valueOf(val);
    }
    if (type == PrimitiveType.INT) {
      final int intVal = (Integer) val;
      return intVal < 0 ? "~" + (-intVal) : String.valueOf(intVal);
    }
    if (type == PrimitiveType.REAL) {
      final float realVal = (Float) val;
      return realVal < 0 ? "~" + (-realVal) : String.valueOf(realVal);
    }
    if (type == PrimitiveType.CHAR) {
      final Character ch = (Character) val;
      return "#\"" + charEscape(ch) + "\"";
    }
    if (type == PrimitiveType.STRING) {
      final String str = (String) val;
      return "\"" + stringEscape(str) + "\"";
    }

    // Handle list types
    if (type instanceof ListType) {
      final List list = (List) val;
      final ListType listType = (ListType) type;
      final Type elementType = listType.elementType;

      // Check if elements are Value instances (unrefined) or raw values
      // (refined)
      if (!list.isEmpty() && list.get(0) instanceof Value) {
        // Unrefined: list of Value instances
        return "[" + printValueList((List<Value>) list) + "]";
      } else {
        // Refined: list of raw values (int, string, etc.)
        return "[" + printRefinedList(list, elementType) + "]";
      }
    }

    // Handle option types (which are DataTypes)
    if (type instanceof DataType) {
      final DataType dataType = (DataType) type;
      // Check if it's an option type by checking the constructor names
      // Option has constructors NONE and SOME
      if (dataType.name.equals("option")) {
        if (val == null) {
          return "VALUE_NONE";
        } else if (val instanceof Value) {
          return "VALUE_SOME " + print((Value) val);
        } else {
          // Refined option: wrap the value
          // For option types, the argument type is the first element of
          // arguments
          final Type innerType = dataType.arguments.get(0);
          return "VALUE_SOME " + print(Value.of(innerType, val));
        }
      }
      // Handle other datatype constructors (CONST, CON)
      // TODO: implement general datatype printing
      return "CONST \"TODO\"";
    }

    // Handle record types
    if (type instanceof RecordType) {
      final RecordType recordType = (RecordType) type;
      final List recordValues = (List) val;
      return "{" + printRecordValues(recordType, recordValues) + "}";
    }

    throw new IllegalArgumentException("Cannot print value of type: " + type);
  }

  /** Helper for print: prints a list of Value instances (unrefined). */
  private static String printValueList(List<Value> values) {
    final StringBuilder buf = new StringBuilder();
    for (int i = 0; i < values.size(); i++) {
      if (i > 0) {
        buf.append(", ");
      }
      buf.append(print(values.get(i)));
    }
    return buf.toString();
  }

  /** Helper for print: prints a list of raw values (refined). */
  private static String printRefinedList(List values, Type elementType) {
    final StringBuilder buf = new StringBuilder();
    for (int i = 0; i < values.size(); i++) {
      if (i > 0) {
        buf.append(", ");
      }
      // Wrap each raw value in a Value instance for printing
      buf.append(print(Value.of(elementType, values.get(i))));
    }
    return buf.toString();
  }

  /** Helper for print: prints a record with field names and values. */
  private static String printRecordValues(RecordType recordType, List values) {
    final StringBuilder buf = new StringBuilder();
    final java.util.Map<String, Type> fields = recordType.argNameTypes();
    int i = 0;
    for (java.util.Map.Entry<String, Type> entry : fields.entrySet()) {
      if (i > 0) {
        buf.append(", ");
      }
      final String fieldName = entry.getKey();
      final Type fieldType = entry.getValue();
      final Object fieldValue = values.get(i);

      buf.append(fieldName).append(" = ");
      buf.append(print(Value.of(fieldType, fieldValue)));
      i++;
    }
    return buf.toString();
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
   * Converts a value to its pretty-printed string representation.
   *
   * <p>TODO: Implement proper pretty-printing using Pretty class when it
   * becomes accessible. For now, delegates to print for consistent output.
   *
   * @see net.hydromatic.morel.compile.BuiltIn#VALUE_PRETTY_PRINT
   */
  public static String prettyPrint(Value value, Session session) {
    // For now, delegate to print
    // Future enhancement: use Pretty class for formatted output with
    // indentation and line breaks based on session settings
    return print(value);
  }

  /**
   * Parses a string representation into a value.
   *
   * @see net.hydromatic.morel.compile.BuiltIn#VALUE_PARSE
   */
  public static Value parse(String s, TypeSystem typeSystem) {
    final Parser parser = new Parser(s, typeSystem);
    return parser.parse();
  }

  /** Helper class for parsing value representations. */
  private static class Parser {
    private final String input;
    private final TypeSystem typeSystem;
    private int pos;

    Parser(String input, TypeSystem typeSystem) {
      this.input = input;
      this.typeSystem = typeSystem;
      this.pos = 0;
    }

    Value parse() {
      skipWhitespace();
      return parseValue();
    }

    private Value parseValue() {
      skipWhitespace();
      if (pos >= input.length()) {
        throw new IllegalArgumentException("Unexpected end of input");
      }

      final char c = input.charAt(pos);
      switch (c) {
        case '(':
          return parseUnit();
        case 't':
        case 'f':
          return parseBool();
        case '"':
          return parseString();
        case '#':
          // Check if it's a VECTOR (#[...]) or CHAR (#"...")
          if (pos + 1 < input.length() && input.charAt(pos + 1) == '[') {
            return parseVector();
          } else {
            return parseChar();
          }
        case '[':
          return parseList();
        case '{':
          // Braces are always RECORD (BAG uses 'bag [...]' format)
          return parseRecord();
        case '~':
        case '0':
        case '1':
        case '2':
        case '3':
        case '4':
        case '5':
        case '6':
        case '7':
        case '8':
        case '9':
          return parseNumber();
        default:
          // Check if it's an identifier (bag, CONST, CON, VALUE_NONE,
          // VALUE_SOME)
          if (Character.isLetter(c)) {
            return parseIdentifierValue();
          }
          throw new IllegalArgumentException(
              "Unexpected character at position " + pos + ": " + c);
      }
    }

    private Value parseUnit() {
      expect("()");
      return (Value) Codes.VALUE_UNIT;
    }

    private Value parseBool() {
      if (tryConsume("true")) {
        return Value.ofBool(true);
      } else if (tryConsume("false")) {
        return Value.ofBool(false);
      } else {
        throw new IllegalArgumentException(
            "Expected 'true' or 'false' at position " + pos);
      }
    }

    private Value parseNumber() {
      // Call parseReal and parseInt in succession to try both
      final String remaining = input.substring(pos);

      // First, parse as a real. With strict=true, will return null if the value
      // looks like an int.
      final Ord<Float> realOrd = Codes.parseReal(remaining, true);
      if (realOrd != null) {
        pos += realOrd.i;
        return Value.ofReal(realOrd.e);
      }

      // Next, parse as an int.
      final Ord<Integer> intOrd = Codes.parseInt(remaining);
      if (intOrd != null) {
        pos += intOrd.i;
        return Value.ofInt(intOrd.e);
      }

      throw new IllegalArgumentException("Expected number at position " + pos);
    }

    private Value parseString() {
      expect("\"");
      final StringBuilder sb = new StringBuilder();
      while (pos < input.length() && input.charAt(pos) != '"') {
        if (input.charAt(pos) == '\\') {
          pos++;
          if (pos >= input.length()) {
            throw new IllegalArgumentException("Incomplete escape sequence");
          }
          final char c = input.charAt(pos);
          switch (c) {
            case 'n':
              sb.append('\n');
              break;
            case 't':
              sb.append('\t');
              break;
            case 'r':
              sb.append('\r');
              break;
            case '\\':
              sb.append('\\');
              break;
            case '"':
              sb.append('"');
              break;
            default:
              sb.append(c);
              break;
          }
          pos++;
        } else {
          sb.append(input.charAt(pos));
          pos++;
        }
      }
      expect("\"");
      return Value.ofString(sb.toString());
    }

    private Value parseChar() {
      expect("#\"");
      if (pos >= input.length()) {
        throw new IllegalArgumentException("Incomplete character literal");
      }
      char c;
      if (input.charAt(pos) == '\\') {
        pos++;
        if (pos >= input.length()) {
          throw new IllegalArgumentException("Incomplete escape sequence");
        }
        final char escapeChar = input.charAt(pos);
        switch (escapeChar) {
          case 'n':
            c = '\n';
            break;
          case 't':
            c = '\t';
            break;
          case 'r':
            c = '\r';
            break;
          case '\\':
            c = '\\';
            break;
          case '"':
            c = '"';
            break;
          default:
            c = escapeChar;
            break;
        }
        pos++;
      } else {
        c = input.charAt(pos);
        pos++;
      }
      expect("\"");
      return Value.ofChar(c);
    }

    private Value parseList() {
      expect("[");
      skipWhitespace();
      if (tryConsume("]")) {
        // Empty list of values
        final Type valueType = typeSystem.lookup("value");
        return Value.ofList(typeSystem, valueType, ImmutableList.of());
      }

      final ImmutableList.Builder<Value> values = ImmutableList.builder();
      values.add(parseValue());
      skipWhitespace();
      while (tryConsume(",")) {
        skipWhitespace();
        values.add(parseValue());
        skipWhitespace();
      }
      expect("]");
      final Type valueType = typeSystem.lookup("value");
      return Value.ofList(typeSystem, valueType, values.build());
    }

    private Value parseVector() {
      expect("#[");
      skipWhitespace();
      if (tryConsume("]")) {
        // Empty vector of values
        final Type valueType = typeSystem.lookup("value");
        return Value.ofVector(typeSystem, valueType, ImmutableList.of());
      }

      final ImmutableList.Builder<Value> values = ImmutableList.builder();
      values.add(parseValue());
      skipWhitespace();
      while (tryConsume(",")) {
        skipWhitespace();
        values.add(parseValue());
        skipWhitespace();
      }
      expect("]");
      final Type valueType = typeSystem.lookup("value");
      return Value.ofVector(typeSystem, valueType, values.build());
    }

    private Value parseRecord() {
      expect("{");
      skipWhitespace();
      if (tryConsume("}")) {
        // Empty record
        // TODO: construct proper empty record type
        return Value.of(PrimitiveType.UNIT, ImmutableList.of());
      }

      final ImmutableList.Builder<String> fieldNames = ImmutableList.builder();
      final ImmutableList.Builder<Value> fieldValues = ImmutableList.builder();

      // Parse first field
      skipWhitespace();
      final String fieldName = parseIdentifier();
      skipWhitespace();
      expect("=");
      skipWhitespace();
      final Value fieldValue = parseValue();
      fieldNames.add(fieldName);
      fieldValues.add(fieldValue);

      skipWhitespace();
      while (tryConsume(",")) {
        skipWhitespace();
        final String name = parseIdentifier();
        skipWhitespace();
        expect("=");
        skipWhitespace();
        final Value value = parseValue();
        fieldNames.add(name);
        fieldValues.add(value);
        skipWhitespace();
      }
      expect("}");

      // TODO: construct proper RecordType from field names and types
      // For now, use a temporary representation
      return Value.of(PrimitiveType.UNIT, fieldValues.build());
    }

    private Value parseIdentifierValue() {
      // Check for 'bag' prefix first
      if (tryConsume("bag")) {
        skipWhitespace();
        expect("[");
        skipWhitespace();
        if (tryConsume("]")) {
          // Empty bag of values
          final Type valueType = typeSystem.lookup("value");
          return Value.ofBag(typeSystem, valueType, ImmutableList.of());
        }
        final ImmutableList.Builder<Value> values = ImmutableList.builder();
        values.add(parseValue());
        skipWhitespace();
        while (tryConsume(",")) {
          skipWhitespace();
          values.add(parseValue());
          skipWhitespace();
        }
        expect("]");
        final Type valueType = typeSystem.lookup("value");
        return Value.ofBag(typeSystem, valueType, values.build());
      }
      // Check for CONSTRUCT format, e.g. CONSTRUCT "INL" (INT 5)
      if (tryConsume("CONSTRUCT")) {
        skipWhitespace();
        expect("\"");
        final String constName = parseStringContent();
        expect("\"");
        // TODO: implement proper CONST support
        return Value.of(PrimitiveType.STRING, constName);
      }
      // Check for CONSTANT, e.g. CONSTANT "LESS"
      if (tryConsume("CONSTANT")) {
        skipWhitespace();
        expect("(");
        skipWhitespace();
        expect("\"");
        final String conName = parseStringContent();
        expect("\"");
        skipWhitespace();
        expect(",");
        skipWhitespace();
        final Value conValue = parseValue();
        skipWhitespace();
        expect(")");
        // TODO: implement proper CON support
        return Value.of(PrimitiveType.STRING, conName);
      }
      // Try VALUE_NONE and VALUE_SOME constructors
      if (tryConsume("VALUE_NONE")) {
        final Type valueType = typeSystem.lookup("value");
        return Value.ofNone(typeSystem, valueType);
      } else if (tryConsume("VALUE_SOME")) {
        skipWhitespace();
        final Value value = parseValue();
        return Value.ofSome(typeSystem, value);
      }
      throw new IllegalArgumentException(
          "Unknown identifier at position " + pos);
    }

    private String parseStringContent() {
      final StringBuilder sb = new StringBuilder();
      while (pos < input.length() && input.charAt(pos) != '"') {
        if (input.charAt(pos) == '\\') {
          pos++;
          if (pos >= input.length()) {
            throw new IllegalArgumentException("Incomplete escape sequence");
          }
          final char c = input.charAt(pos);
          switch (c) {
            case 'n':
              sb.append('\n');
              break;
            case 't':
              sb.append('\t');
              break;
            case 'r':
              sb.append('\r');
              break;
            case '\\':
              sb.append('\\');
              break;
            case '"':
              sb.append('"');
              break;
            default:
              sb.append(c);
              break;
          }
          pos++;
        } else {
          sb.append(input.charAt(pos));
          pos++;
        }
      }
      return sb.toString();
    }

    private String parseIdentifier() {
      final int start = pos;
      while (pos < input.length()
          && (Character.isLetterOrDigit(input.charAt(pos))
              || input.charAt(pos) == '_')) {
        pos++;
      }
      if (start == pos) {
        throw new IllegalArgumentException(
            "Expected identifier at position " + pos);
      }
      return input.substring(start, pos);
    }

    private void skipWhitespace() {
      while (pos < input.length()
          && Character.isWhitespace(input.charAt(pos))) {
        pos++;
      }
    }

    private void expect(String expected) {
      skipWhitespace();
      if (!input.startsWith(expected, pos)) {
        throw new IllegalArgumentException(
            format(
                "Expected '%s' at position %d but found: %s",
                expected,
                pos,
                input.substring(pos, Math.min(pos + 10, input.length()))));
      }
      pos += expected.length();
    }

    private boolean tryConsume(String expected) {
      skipWhitespace();
      if (input.startsWith(expected, pos)) {
        pos += expected.length();
        return true;
      }
      return false;
    }
  }
}

// End Values.java
