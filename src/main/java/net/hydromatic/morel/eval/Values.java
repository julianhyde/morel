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

import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;
import net.hydromatic.morel.type.DataType;
import net.hydromatic.morel.type.ListType;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.Ord;

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
   * Converts a List representation (from parser) to a Value instance.
   *
   * <p>The parser creates Lists like ["INT", 42] or ["LIST", [...]]. This
   * method converts them to Value instances with proper types.
   *
   * <p>The resulting Value uses general types (value list, value option, etc.)
   * since the parser doesn't infer specific types.
   */
  static Value fromList(List list, TypeSystem typeSystem) {
    if (list.isEmpty()) {
      throw new IllegalArgumentException("Invalid value: empty list");
    }

    final String constructor = (String) list.get(0);
    switch (constructor) {
      case "UNIT":
        return Value.of(PrimitiveType.UNIT, Unit.INSTANCE);
      case "BOOL":
        return Value.of(PrimitiveType.BOOL, list.get(1));
      case "INT":
        return Value.of(PrimitiveType.INT, list.get(1));
      case "REAL":
        return Value.of(PrimitiveType.REAL, list.get(1));
      case "CHAR":
        return Value.of(PrimitiveType.CHAR, list.get(1));
      case "STRING":
        return Value.of(PrimitiveType.STRING, list.get(1));
      case "LIST":
        {
          final List elements = (List) list.get(1);
          final List<Value> values = new ArrayList<>();
          for (Object elem : elements) {
            values.add(fromList((List) elem, typeSystem));
          }
          // Parser produces general type: value list
          final Type valueType = typeSystem.lookup("value");
          return Value.of(typeSystem.listType(valueType), values);
        }
      case "BAG":
        {
          final List elements = (List) list.get(1);
          final List<Value> values = new ArrayList<>();
          for (Object elem : elements) {
            values.add(fromList((List) elem, typeSystem));
          }
          // BAG is a datatype constructor - need to look it up
          // For now, treat similar to LIST
          final Type valueType = typeSystem.lookup("value");
          return Value.of(
              typeSystem.listType(valueType), values); // TODO: proper bag type
        }
      case "VECTOR":
        {
          final List elements = (List) list.get(1);
          final List<Value> values = new ArrayList<>();
          for (Object elem : elements) {
            values.add(fromList((List) elem, typeSystem));
          }
          final Type valueType = typeSystem.lookup("value");
          return Value.of(
              typeSystem.listType(valueType),
              values); // TODO: proper vector type
        }
      case "VALUE_NONE":
        // NONE of value option
        final Type valueType = typeSystem.lookup("value");
        return Value.of(
            typeSystem.option(valueType),
            null); // TODO: proper option representation
      case "VALUE_SOME":
        {
          final Value inner = fromList((List) list.get(1), typeSystem);
          final Type vType = typeSystem.lookup("value");
          return Value.of(
              typeSystem.option(vType),
              inner); // TODO: proper option representation
        }
      case "RECORD":
        {
          final List<List> fields = (List) list.get(1);
          final List<Value> fieldValues = new ArrayList<>();
          for (List field : fields) {
            fieldValues.add(fromList((List) field.get(1), typeSystem));
          }
          // TODO: construct proper record type and value
          return Value.of(PrimitiveType.UNIT, fieldValues); // TEMP
        }
      case "CONST":
        // TODO: datatype constructor
        return Value.of(PrimitiveType.STRING, list.get(1)); // TEMP
      case "CON":
        // TODO: datatype constructor with value
        final List pair = (List) list.get(1);
        return Value.of(PrimitiveType.STRING, pair.get(0)); // TEMP
      default:
        throw new IllegalArgumentException(
            "Unknown constructor: " + constructor);
    }
  }

  /**
   * Converts a Value to its string representation.
   *
   * <p>Handles both refined (specific types like int list) and unrefined
   * (general types like value list) representations.
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
  public static Value parse(String s) {
    final Parser parser = new Parser(s);
    final List list = parser.parse();
    // Convert List representation to Value with proper types
    final TypeSystem typeSystem = new TypeSystem();
    return fromList(list, typeSystem);
  }

  /** Helper class for parsing value representations. */
  private static class Parser {
    private final String input;
    private int pos;

    Parser(String input) {
      this.input = input;
      this.pos = 0;
    }

    List parse() {
      skipWhitespace();
      return parseValue();
    }

    private List parseValue() {
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

    private List parseUnit() {
      expect("()");
      return ImmutableList.of("UNIT");
    }

    private List parseBool() {
      if (tryConsume("true")) {
        return ImmutableList.of("BOOL", true);
      } else if (tryConsume("false")) {
        return ImmutableList.of("BOOL", false);
      } else {
        throw new IllegalArgumentException(
            "Expected 'true' or 'false' at position " + pos);
      }
    }

    private List parseNumber() {
      // Call parseReal and parseInt in succession to try both
      final String remaining = input.substring(pos);

      // Try parseReal first (FLOAT_PATTERN handles both reals and integers)
      final Ord<Float> realOrd = Codes.parseReal(remaining);
      if (realOrd != null) {
        // Determine what type it is by checking the consumed string
        final String s2 = remaining.replace('~', '-');
        final String consumed = s2.substring(0, realOrd.i);

        // Check if it's actually a real (has decimal point or exponent)
        if (consumed.contains(".")
            || consumed.contains("e")
            || consumed.contains("E")) {
          pos += realOrd.i;
          return ImmutableList.of("REAL", realOrd.e);
        }
        // Fall through to parseInt for integer case
      }

      // Try parseInt (will match if it's an integer)
      final Ord<Integer> intOrd = Codes.parseInt(remaining);
      if (intOrd != null) {
        pos += intOrd.i;
        return ImmutableList.of("INT", intOrd.e);
      }

      throw new IllegalArgumentException("Expected number at position " + pos);
    }

    private List parseString() {
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
      return ImmutableList.of("STRING", sb.toString());
    }

    private List parseChar() {
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
      return ImmutableList.of("CHAR", c);
    }

    private List parseList() {
      expect("[");
      skipWhitespace();
      if (tryConsume("]")) {
        return ImmutableList.of("LIST", ImmutableList.of());
      }

      final ImmutableList.Builder<List> values = ImmutableList.builder();
      values.add(parseValue());
      skipWhitespace();
      while (tryConsume(",")) {
        skipWhitespace();
        values.add(parseValue());
        skipWhitespace();
      }
      expect("]");
      return ImmutableList.of("LIST", values.build());
    }

    private List parseVector() {
      expect("#[");
      skipWhitespace();
      if (tryConsume("]")) {
        return ImmutableList.of("VECTOR", ImmutableList.of());
      }

      final ImmutableList.Builder<List> values = ImmutableList.builder();
      values.add(parseValue());
      skipWhitespace();
      while (tryConsume(",")) {
        skipWhitespace();
        values.add(parseValue());
        skipWhitespace();
      }
      expect("]");
      return ImmutableList.of("VECTOR", values.build());
    }

    private List parseRecord() {
      expect("{");
      skipWhitespace();
      if (tryConsume("}")) {
        return ImmutableList.of("RECORD", ImmutableList.of());
      }

      final ImmutableList.Builder<List> fields = ImmutableList.builder();
      // Parse first field
      skipWhitespace();
      final String fieldName = parseIdentifier();
      skipWhitespace();
      expect("=");
      skipWhitespace();
      final List fieldValue = parseValue();
      fields.add(ImmutableList.of(fieldName, fieldValue));

      skipWhitespace();
      while (tryConsume(",")) {
        skipWhitespace();
        final String name = parseIdentifier();
        skipWhitespace();
        expect("=");
        skipWhitespace();
        final List value = parseValue();
        fields.add(ImmutableList.of(name, value));
        skipWhitespace();
      }
      expect("}");
      return ImmutableList.of("RECORD", fields.build());
    }

    private List parseIdentifierValue() {
      // Check for 'bag' prefix first
      if (tryConsume("bag")) {
        skipWhitespace();
        expect("[");
        skipWhitespace();
        if (tryConsume("]")) {
          return ImmutableList.of("BAG", ImmutableList.of());
        }
        final ImmutableList.Builder<List> values = ImmutableList.builder();
        values.add(parseValue());
        skipWhitespace();
        while (tryConsume(",")) {
          skipWhitespace();
          values.add(parseValue());
          skipWhitespace();
        }
        expect("]");
        return ImmutableList.of("BAG", values.build());
      }
      // Check for CONST format (CONST "name")
      if (tryConsume("CONST")) {
        skipWhitespace();
        expect("\"");
        final String constName = parseStringContent();
        expect("\"");
        return ImmutableList.of("CONST", constName);
      }
      // Check for CON format (CON ("name", value))
      if (tryConsume("CON")) {
        skipWhitespace();
        expect("(");
        skipWhitespace();
        expect("\"");
        final String conName = parseStringContent();
        expect("\"");
        skipWhitespace();
        expect(",");
        skipWhitespace();
        final List conValue = parseValue();
        skipWhitespace();
        expect(")");
        return ImmutableList.of("CON", ImmutableList.of(conName, conValue));
      }
      // Try VALUE_NONE and VALUE_SOME constructors
      if (tryConsume("VALUE_NONE")) {
        return ImmutableList.of("VALUE_NONE");
      } else if (tryConsume("VALUE_SOME")) {
        skipWhitespace();
        final List value = parseValue();
        return ImmutableList.of("VALUE_SOME", value);
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
            "Expected '"
                + expected
                + "' at position "
                + pos
                + " but found: "
                + input.substring(pos, Math.min(pos + 10, input.length())));
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
