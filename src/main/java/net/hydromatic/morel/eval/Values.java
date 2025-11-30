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
import java.util.List;

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
   * Converts a value (represented as a List) to its string representation.
   *
   * @see net.hydromatic.morel.compile.BuiltIn#VALUE_PRINT
   */
  public static String print(List value) {
    if (value.isEmpty()) {
      throw new IllegalArgumentException("Invalid value: empty list");
    }

    final String constructor = (String) value.get(0);
    switch (constructor) {
      case "UNIT":
        return "()";
      case "BOOL":
        return String.valueOf(value.get(1));
      case "INT":
        return String.valueOf(value.get(1));
      case "REAL":
        return String.valueOf(value.get(1));
      case "CHAR":
        final Character ch = (Character) value.get(1);
        return "#\"" + charEscape(ch) + "\"";
      case "STRING":
        final String str = (String) value.get(1);
        return "\"" + stringEscape(str) + "\"";
      case "LIST":
        return "[" + printList((List) value.get(1)) + "]";
      case "BAG":
        return "bag [" + printList((List) value.get(1)) + "]";
      case "VECTOR":
        return "#[" + printList((List) value.get(1)) + "]";
      case "VALUE_NONE":
        return "VALUE_NONE";
      case "VALUE_SOME":
        return "VALUE_SOME " + print((List) value.get(1));
      case "RECORD":
        return "{" + printRecord((List) value.get(1)) + "}";
      case "CONST":
        final String constName = (String) value.get(1);
        return "CONST \"" + constName + "\"";
      case "CON":
        final List conPair = (List) value.get(1);
        final String conName = (String) conPair.get(0);
        final List conValue = (List) conPair.get(1);
        return "CON (\"" + conName + "\", " + print(conValue) + ")";
      default:
        throw new IllegalArgumentException(
            "Unknown value constructor: " + constructor);
    }
  }

  /** Helper for print: prints a list of values. */
  private static String printList(List values) {
    final StringBuilder buf = new StringBuilder();
    for (int i = 0; i < values.size(); i++) {
      if (i > 0) {
        buf.append(", ");
      }
      buf.append(print((List) values.get(i)));
    }
    return buf.toString();
  }

  /** Helper for print: prints a record (list of (string, value) pairs). */
  private static String printRecord(List fields) {
    final StringBuilder buf = new StringBuilder();
    for (int i = 0; i < fields.size(); i++) {
      if (i > 0) {
        buf.append(", ");
      }
      final List pair = (List) fields.get(i);
      final String key = (String) pair.get(0);
      final List val = (List) pair.get(1);
      buf.append(key).append(" = ").append(print(val));
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
  public static String prettyPrint(List value, Session session) {
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
  public static List parse(String s) {
    final Parser parser = new Parser(s);
    return parser.parse();
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
        case '-':
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
      final int start = pos;
      boolean isNegative = false;
      if (input.charAt(pos) == '-') {
        isNegative = true;
        pos++;
      }

      while (pos < input.length() && Character.isDigit(input.charAt(pos))) {
        pos++;
      }

      // Check for real number (has decimal point or exponent)
      boolean isReal = false;
      if (pos < input.length() && input.charAt(pos) == '.') {
        isReal = true;
        pos++;
        while (pos < input.length() && Character.isDigit(input.charAt(pos))) {
          pos++;
        }
      }

      if (pos < input.length()
          && (input.charAt(pos) == 'e' || input.charAt(pos) == 'E')) {
        isReal = true;
        pos++;
        if (pos < input.length()
            && (input.charAt(pos) == '+' || input.charAt(pos) == '-')) {
          pos++;
        }
        while (pos < input.length() && Character.isDigit(input.charAt(pos))) {
          pos++;
        }
      }

      final String numStr = input.substring(start, pos);
      if (isReal) {
        return ImmutableList.of("REAL", Double.parseDouble(numStr));
      } else {
        return ImmutableList.of("INT", Integer.parseInt(numStr));
      }
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
