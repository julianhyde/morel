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
package net.hydromatic.morel.compile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.hydromatic.morel.type.DataType;
import net.hydromatic.morel.type.ListType;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.TupleType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Compares two output strings for semantic equivalence, treating bag values as
 * unordered (multisets).
 *
 * <p>Output strings have the form {@code val name = value : type} or {@code
 * value : type}. The type suffix tells us which brackets represent bags
 * (unordered) vs lists (ordered).
 */
public class OutputMatcher {
  private final TypeSystem typeSystem = new TypeSystem();

  public OutputMatcher() {}

  /**
   * Returns whether {@code actual} and {@code expected} are semantically
   * equivalent. Bag-typed values are compared as multisets (order-independent).
   *
   * <p>If in doubt, we return false. We cannot afford false-positives such as
   * saying that two strings are equivalent when they have a different number of
   * spaces.
   */
  public boolean equivalent(Type type_, String actual, String expected) {
    // Extract type suffix from the output.
    // Format: "val name = value : type" or "value : type"
    // The type is after the last top-level " : ".
    String typeStr = extractType(actual);
    if (typeStr == null) {
      typeStr = extractType(expected);
    }
    if (typeStr == null) {
      return false;
    }

    // Extract value portions
    String actualValue = extractValue(actual);
    String expectedValue = extractValue(expected);
    if (actualValue == null || expectedValue == null) {
      return false;
    }

    // Parse both values and compare with bag-aware equality
    try {
      Object actualParsed =
          parseValue(new Scanner(normalizeWhitespace(actualValue)));
      Object expectedParsed =
          parseValue(new Scanner(normalizeWhitespace(expectedValue)));
      return valuesEqual(actualParsed, expectedParsed, type_);
    } catch (RuntimeException e) {
      // If parsing fails, fall back to whitespace-normalized comparison
      return normalizeWhitespace(actual).equals(normalizeWhitespace(expected));
    }
  }

  /**
   * Extracts the type portion from output like "val x = value : type". Finds
   * the last " : " that is not inside brackets or strings.
   */
  static @Nullable String extractType(String s) {
    int depth = 0;
    boolean inString = false;
    int lastColon = -1;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (inString) {
        if (c == '"') {
          inString = false;
        } else if (c == '\\') {
          i++; // skip escaped char
        }
        continue;
      }
      if (c == '"') {
        inString = true;
      } else if (c == '(' || c == '[' || c == '{') {
        depth++;
      } else if (c == ')' || c == ']' || c == '}') {
        depth--;
      } else if (c == ':'
          && depth == 0
          && i > 0
          && s.charAt(i - 1) == ' '
          && i + 1 < s.length()
          && s.charAt(i + 1) == ' ') {
        lastColon = i;
      }
    }
    return lastColon >= 0 ? s.substring(lastColon + 2).trim() : null;
  }

  /**
   * Extracts the value portion from output like "val x = value : type" or
   * "value : type".
   */
  static @Nullable String extractValue(String s) {
    // Find start of value: after "val <name> = " if present
    int valueStart = 0;
    int eqIdx = indexOfEqWhitespace(s);
    if (eqIdx >= 0 && s.substring(0, eqIdx).contains("val ")) {
      // Skip "=" and any following whitespace (space, newline, etc.)
      int start = eqIdx + 1;
      while (start < s.length() && isWhitespaceChar(s.charAt(start))) {
        start++;
      }
      valueStart = start;
    }

    // Find end of value: before the last top-level " : "
    int depth = 0;
    boolean inString = false;
    int lastColon = -1;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (inString) {
        if (c == '"') {
          inString = false;
        } else if (c == '\\') {
          i++;
        }
        continue;
      }
      if (c == '"') {
        inString = true;
      } else if (c == '(' || c == '[' || c == '{') {
        depth++;
      } else if (c == ')' || c == ']' || c == '}') {
        depth--;
      } else if (c == ':'
          && depth == 0
          && i > 0
          && s.charAt(i - 1) == ' '
          && i + 1 < s.length()
          && s.charAt(i + 1) == ' ') {
        lastColon = i;
      }
    }
    if (lastColon < 0) {
      return null;
    }
    return s.substring(valueStart, lastColon - 1);
  }

  static String normalizeWhitespace(String s) {
    StringBuilder buf = new StringBuilder();
    boolean inString = false;
    boolean lastWasSpace = false;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (inString) {
        buf.append(c);
        if (c == '"') {
          inString = false;
        } else if (c == '\\' && i + 1 < s.length()) {
          buf.append(s.charAt(++i));
        }
        lastWasSpace = false;
        continue;
      }
      if (c == '"') {
        if (lastWasSpace && buf.length() > 0) {
          buf.append(' ');
        }
        buf.append(c);
        inString = true;
        lastWasSpace = false;
      } else if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
        lastWasSpace = true;
      } else {
        if (lastWasSpace && buf.length() > 0 && needsSpace(buf, c)) {
          buf.append(' ');
        }
        buf.append(c);
        lastWasSpace = false;
      }
    }
    return buf.toString();
  }

  /**
   * Determines whether a space is needed between the last char in buf and the
   * next char c. Spaces are needed between alphanumeric tokens but not around
   * brackets and punctuation.
   */
  private static boolean needsSpace(StringBuilder buf, char c) {
    char prev = buf.charAt(buf.length() - 1);
    // Space needed between two word chars, or after '=' before value,
    // or before/after certain keywords
    if (isWordChar(prev) && isWordChar(c)) {
      return true;
    }
    if (prev == '=' && c != '{' && c != '[' && c != '(' && c != ')') {
      return true;
    }
    if (c == '=' && prev != '>' && prev != '<' && prev != '!') {
      return true;
    }
    return false;
  }

  private static boolean isWordChar(char c) {
    return Character.isLetterOrDigit(c) || c == '_' || c == '\'' || c == '~';
  }

  private static boolean isWhitespaceChar(char c) {
    return c == ' ' || c == '\n' || c == '\r' || c == '\t';
  }

  /** Finds the first '=' followed by whitespace (space or newline). */
  private static int indexOfEqWhitespace(String s) {
    for (int i = 0; i < s.length() - 1; i++) {
      if (s.charAt(i) == '=' && isWhitespaceChar(s.charAt(i + 1))) {
        return i;
      }
    }
    return -1;
  }

  // --- Value parser ---

  /**
   * A parsed value. Either a literal string (for atoms) or a structured value
   * (list, record, tuple).
   */
  abstract static class Val {
    abstract String canonical();
  }

  static class AtomVal extends Val {
    final String text;

    AtomVal(String text) {
      this.text = text;
    }

    @Override
    String canonical() {
      return text;
    }

    @Override
    public String toString() {
      return text;
    }
  }

  static class ListVal extends Val {
    final List<Val> elements;

    ListVal(List<Val> elements) {
      this.elements = elements;
    }

    @Override
    String canonical() {
      StringBuilder buf = new StringBuilder("[");
      for (int i = 0; i < elements.size(); i++) {
        if (i > 0) {
          buf.append(",");
        }
        buf.append(elements.get(i).canonical());
      }
      return buf.append("]").toString();
    }
  }

  static class TupleVal extends Val {
    final List<Val> fields;

    TupleVal(List<Val> fields) {
      this.fields = fields;
    }

    @Override
    String canonical() {
      StringBuilder buf = new StringBuilder("(");
      for (int i = 0; i < fields.size(); i++) {
        if (i > 0) {
          buf.append(",");
        }
        buf.append(fields.get(i).canonical());
      }
      return buf.append(")").toString();
    }
  }

  static class RecordVal extends Val {
    final List<String> names;
    final List<Val> values;

    RecordVal(List<String> names, List<Val> values) {
      this.names = names;
      this.values = values;
    }

    @Override
    String canonical() {
      StringBuilder buf = new StringBuilder("{");
      for (int i = 0; i < names.size(); i++) {
        if (i > 0) {
          buf.append(",");
        }
        buf.append(names.get(i)).append("=").append(values.get(i).canonical());
      }
      return buf.append("}").toString();
    }
  }

  /** Parses a value from whitespace-normalized text. */
  static Val parseValue(Scanner sc) {
    char c = sc.peek();
    if (c == '[') {
      return parseListValue(sc);
    } else if (c == '(') {
      // Could be tuple or parenthesized value
      return parseTupleValue(sc);
    } else if (c == '{') {
      return parseRecordValue(sc);
    } else if (c == '#') {
      // Character literal like #"z"
      sc.consume("#");
      return new AtomVal("#" + sc.consumeString());
    } else if (c == '"') {
      return new AtomVal(sc.consumeString());
    } else if (c == '~' || Character.isDigit(c)) {
      return new AtomVal(sc.consumeNumber());
    } else {
      // Constructor (SOME, NONE, LESS, GREATER, EQUAL, etc.) or bool
      String word = sc.consumeWord();
      if (("SOME".equals(word) || "INL".equals(word) || "INR".equals(word))
          && sc.hasMore()
          && sc.peek() != ','
          && sc.peek() != ')'
          && sc.peek() != ']'
          && sc.peek() != '}') {
        Val inner = parseValue(sc);
        return new ListVal(Arrays.asList(new AtomVal(word), inner));
      }
      return new ListVal(Collections.singletonList(new AtomVal(word)));
    }
  }

  private static Val parseListValue(Scanner sc) {
    sc.consume("[");
    List<Val> elements = new ArrayList<>();
    if (sc.peek() != ']') {
      for (; ; ) {
        elements.add(parseValue(sc));
        if (sc.peek() != ',') {
          break;
        }
        sc.consume(",");
      }
    }
    sc.consume("]");
    return new ListVal(elements);
  }

  private static Val parseTupleValue(Scanner sc) {
    sc.consume("(");
    if (sc.peek() == ')') {
      sc.consume(")");
      return new AtomVal("()");
    }
    Val first = parseValue(sc);
    if (sc.peek() == ',') {
      List<Val> fields = new ArrayList<>();
      fields.add(first);
      while (sc.peek() == ',') {
        sc.consume(",");
        fields.add(parseValue(sc));
      }
      sc.consume(")");
      return new TupleVal(fields);
    }
    sc.consume(")");
    // Parenthesized single value
    return first;
  }

  private static Val parseRecordValue(Scanner sc) {
    sc.consume("{");
    List<String> names = new ArrayList<>();
    List<Val> values = new ArrayList<>();
    if (sc.peek() != '}') {
      for (; ; ) {
        names.add(sc.consumeWord());
        sc.consume("=");
        values.add(parseValue(sc));
        if (sc.peek() != ',') {
          break;
        }
        sc.consume(",");
      }
    }
    sc.consume("}");
    return new RecordVal(names, values);
  }

  // --- Value comparison ---

  /**
   * Compares two parsed values for equivalence, treating bag-typed collections
   * as unordered.
   */
  boolean valuesEqual(Object actual, Object expected, Type type) {
    if (actual instanceof Val) {
      return valEqual(type, (Val) actual, (Val) expected);
    }
    return actual.equals(expected);
  }

  private boolean valEqual(Type type, Val actual, Val expected) {
    if (type instanceof DataType && type.isCollection()) {
      // Bag: compare as multisets
      if (!(actual instanceof ListVal) || !(expected instanceof ListVal)) {
        return actual.canonical().equals(expected.canonical());
      }
      ListVal actualList = (ListVal) actual;
      ListVal expectedList = (ListVal) expected;
      if (actualList.elements.size() != expectedList.elements.size()) {
        return false;
      }
      Type elemType = type.elementType();
      return bagEqual(elemType, actualList.elements, expectedList.elements);
    } else if (type instanceof ListType) {
      // List: compare element-wise, in order
      if (!(actual instanceof ListVal) || !(expected instanceof ListVal)) {
        return actual.canonical().equals(expected.canonical());
      }
      ListVal actualList = (ListVal) actual;
      ListVal expectedList = (ListVal) expected;
      if (actualList.elements.size() != expectedList.elements.size()) {
        return false;
      }
      final Type elemType = type.elementType();
      for (int i = 0; i < actualList.elements.size(); i++) {
        if (!valEqual(
            elemType,
            actualList.elements.get(i),
            expectedList.elements.get(i))) {
          return false;
        }
      }
      return true;
    } else if (type instanceof TupleType) {
      return actual instanceof TupleVal
          && expected instanceof TupleVal
          && tupleEqual(
              (TupleType) type,
              ((TupleVal) actual).fields,
              ((TupleVal) expected).fields);
    } else if (type instanceof RecordType) {
      return actual instanceof RecordVal
          && expected instanceof RecordVal
          && recordEqual(
              (RecordType) type, (RecordVal) actual, (RecordVal) expected);
    } else if (type instanceof DataType) {
      return actual instanceof ListVal
          && expected instanceof ListVal
          && dataEqual(
              (DataType) type,
              ((ListVal) actual).elements,
              ((ListVal) expected).elements);
    } else {
      // Atomic type
      return actual.canonical().equals(expected.canonical());
    }
  }

  private boolean tupleEqual(TupleType type, List<Val> list0, List<Val> list1) {
    if (list0.size() != list1.size() || list0.size() != type.argTypes.size()) {
      return false;
    }
    final List<Type> argTypes = type.argTypes();
    for (int i = 0; i < list0.size(); i++) {
      if (!valEqual(argTypes.get(i), list0.get(i), list1.get(i))) {
        return false;
      }
    }
    return true;
  }

  private boolean recordEqual(
      RecordType type, RecordVal actual, RecordVal expected) {
    if (actual.names.size() != expected.names.size()) {
      return false;
    }
    for (int i = 0; i < actual.names.size(); i++) {
      if (!actual.names.get(i).equals(expected.names.get(i))) {
        return false;
      }
      Type fieldType = type.argNameTypes.get(actual.names.get(i));
      if (fieldType == null
          || !valEqual(
              fieldType, actual.values.get(i), expected.values.get(i))) {
        return false;
      }
    }
    return true;
  }

  private boolean dataEqual(
      DataType dataType, List<Val> list0, List<Val> list1) {
    if (list0.size() != list1.size()) {
      return false;
    }
    if (!list0.get(0).canonical().equals(list1.get(0).canonical())) {
      return false;
    }
    if (list0.size() == 1) {
      return true;
    }
    String constructor = list0.get(0).canonical();
    Type type = dataType.typeConstructors(typeSystem).get(constructor);
    return valuesEqual(list0.get(1), list1.get(1), type);
  }

  /**
   * Compares two lists as multi-sets: every element in {@code list0} must match
   * exactly one element in {@code list1} (using bag-aware equality).
   */
  private boolean bagEqual(Type type, List<Val> list0, List<Val> list1) {
    if (list0.size() != list1.size()) {
      return false;
    }
    final List<Val> remaining = new ArrayList<>(list1);
    for (Val a : list0) {
      boolean found = false;
      for (int j = 0; j < remaining.size(); j++) {
        if (valEqual(type, a, remaining.get(j))) {
          remaining.remove(j);
          found = true;
          break;
        }
      }
      if (!found) {
        return false;
      }
    }
    return true;
  }

  // --- Scanner ---

  /** Simple scanner over whitespace-normalized text. */
  static class Scanner {
    private final String s;
    private int pos;

    Scanner(String s) {
      this.s = s;
      this.pos = 0;
    }

    boolean hasMore() {
      skipSpaces();
      return pos < s.length();
    }

    char peek() {
      skipSpaces();
      return pos < s.length() ? s.charAt(pos) : 0;
    }

    /**
     * Peeks at the next word without consuming it. Returns null if next token
     * is not a word.
     */
    @Nullable
    String peekWord() {
      skipSpaces();
      if (pos >= s.length() || !isWordChar(s.charAt(pos))) {
        return null;
      }
      int start = pos;
      int end = start;
      while (end < s.length() && isWordChar(s.charAt(end))) {
        end++;
      }
      return s.substring(start, end);
    }

    void consume(String expected) {
      skipSpaces();
      if (!s.startsWith(expected, pos)) {
        throw new IllegalStateException(
            "expected '" + expected + "' at pos " + pos + " in: " + s);
      }
      pos += expected.length();
    }

    String consumeWord() {
      skipSpaces();
      int start = pos;
      while (pos < s.length() && isWordChar(s.charAt(pos))) {
        pos++;
      }
      if (pos == start) {
        throw new IllegalStateException(
            "expected word at pos " + pos + " in: " + s);
      }
      return s.substring(start, pos);
    }

    String consumeString() {
      skipSpaces();
      if (s.charAt(pos) != '"') {
        throw new IllegalStateException(
            "expected '\"' at pos " + pos + " in: " + s);
      }
      int start = pos;
      pos++; // skip opening "
      while (pos < s.length() && s.charAt(pos) != '"') {
        if (s.charAt(pos) == '\\') {
          pos++; // skip escape
        }
        pos++;
      }
      pos++; // skip closing "
      return s.substring(start, pos);
    }

    String consumeNumber() {
      skipSpaces();
      int start = pos;
      if (pos < s.length() && s.charAt(pos) == '~') {
        pos++; // negative sign
      }
      while (pos < s.length()
          && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.')) {
        pos++;
      }
      // Handle 'E' in real literals
      if (pos < s.length() && (s.charAt(pos) == 'E' || s.charAt(pos) == 'e')) {
        pos++;
        if (pos < s.length() && s.charAt(pos) == '~') {
          pos++;
        }
        while (pos < s.length() && Character.isDigit(s.charAt(pos))) {
          pos++;
        }
      }
      return s.substring(start, pos);
    }

    private void skipSpaces() {
      while (pos < s.length() && s.charAt(pos) == ' ') {
        pos++;
      }
    }
  }
}

// End OutputMatcher.java
