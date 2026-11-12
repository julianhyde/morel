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
package net.hydromatic.morel.tpcds;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableList;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.hydromatic.morel.compile.Tracer;
import net.hydromatic.morel.compile.Tracers;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.FnType;
import net.hydromatic.morel.type.RecordLikeType;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.TupleType;
import net.hydromatic.morel.type.Type;
import org.jspecify.annotations.Nullable;

/**
 * Compares the value of each TPC-DS query in a script with Spark SQL's answer
 * to the same query.
 *
 * <p>A script defines each query as a function, {@code fun q3 () = from ...},
 * and binds its value, {@code val r3 = q3 ()}; for each {@code rN}, the checker
 * reads {@code qN.csv} from the answers directory, which {@code init-tpcds.py}
 * wrote, and compares the two as multisets of rows (the {@code order} of a
 * TPC-DS query is often by a key that is not unique). A column of the answer
 * matches the field of the same name, ignoring case, and with each character
 * that is not a letter, digit or underscore as an underscore, as {@code
 * init-tpcds.py} writes it (so {@code `30 days `} matches {@code 30_days_}); if
 * one field and one column are left over, they match each other, which covers a
 * column that Spark named, such as {@code count(1)}.
 *
 * <p>Values are compared as text, except numbers: a decimal must equal a
 * decimal exactly (unless Morel's has more digits after the point, as after a
 * division, when they need only agree to 15 digits), and a {@code real}
 * (Morel's is 32 bits, Spark's {@code double} 64) need only agree to {@link
 * #REAL_TOLERANCE}. {@code NONE} and an empty field are null, and {@code SOME
 * v} is {@code v}; so an answer column that happens to hold no null matches a
 * Morel option.
 *
 * <p>Where Spark's answer is an error ({@code qN.error}, such as a division by
 * zero), {@code val rN = qN ()} raises, binds nothing, and is not checked here;
 * the script's expected output shows the exception.
 */
public class TpcdsChecker {
  /** Relative tolerance for comparing reals. */
  static final double REAL_TOLERANCE = 1e-5;

  /**
   * Digits to which a decimal must agree with Spark's, if Morel's has more
   * digits after the point than Spark's.
   */
  static final MathContext DECIMAL_PRECISION = new MathContext(15);

  private static final Pattern QUERY_NAME = Pattern.compile("r([0-9]+[ab]?)");

  private static final Pattern FUNCTION_NAME =
      Pattern.compile("q([0-9]+[ab]?)");

  private final File answerDirectory;
  private final Map<String, String> results = new TreeMap<>();

  /** The queries that the script defines as functions. */
  private final Set<String> defined = new TreeSet<>();

  public TpcdsChecker(File answerDirectory) {
    this.answerDirectory = requireNonNull(answerDirectory);
  }

  /** Returns a tracer that checks each query that the script binds. */
  public Tracer tracer(Tracer tracer) {
    return Tracers.withOnBinding(tracer, this::onBinding);
  }

  /**
   * Returns the result of each query, keyed by name: "ok" or the reason it
   * failed.
   */
  public Map<String, String> results() {
    return results;
  }

  /**
   * Returns the failures, one per line; empty if every query agreed. A query
   * that is defined but whose value was never bound fails too, unless Spark's
   * answer is an error.
   */
  public String failures() {
    final StringBuilder b = new StringBuilder();
    for (String query : defined) {
      if (!results.containsKey(query)
          && !new File(answerDirectory, query + ".error").exists()) {
        b.append(query).append(": defined, but its value was not checked\n");
      }
    }
    results.forEach(
        (name, result) -> {
          if (!result.equals("ok")) {
            b.append(name).append(": ").append(result).append('\n');
          }
        });
    return b.toString();
  }

  private void onBinding(Binding binding) {
    final String name = binding.id.name;
    final Matcher fnMatcher = FUNCTION_NAME.matcher(name);
    if (fnMatcher.matches() && binding.id.type instanceof FnType) {
      defined.add(name);
      return;
    }
    final Matcher matcher = QUERY_NAME.matcher(name);
    if (!matcher.matches()) {
      return;
    }
    final String query = "q" + matcher.group(1);
    String result;
    try {
      result = check(query, binding.id.type, binding.value);
    } catch (IOException | RuntimeException e) {
      result = "error: " + e;
    }
    results.put(query, result);
  }

  /** Checks a query's value; returns "ok" or the reason it differs. */
  String check(String name, Type type, Object value) throws IOException {
    final File file = new File(answerDirectory, name + ".csv");
    if (!file.exists()) {
      return "no answer " + file;
    }
    final Answer answer = Answer.read(file);

    // The value as rows of cells, and the name and type of each column.
    final List<String> names = new ArrayList<>();
    final List<Type> types = new ArrayList<>();
    final List<List<Object>> rows = new ArrayList<>();
    final Type rowType;
    final List<?> values;
    if (type.isCollection()) {
      rowType = type.elementType();
      values = (List<?>) value;
    } else {
      rowType = type;
      values = ImmutableList.of(value);
    }
    if (rowType instanceof RecordType || rowType instanceof TupleType) {
      ((RecordLikeType) rowType)
          .argNameTypes()
          .forEach(
              (n, t) -> {
                names.add(n.toLowerCase(Locale.ROOT).replaceAll("\\W", "_"));
                types.add(t);
              });
      for (Object v : values) {
        @SuppressWarnings("unchecked")
        final List<Object> row = (List<Object>) v;
        rows.add(row);
      }
    } else {
      names.add("");
      types.add(rowType);
      for (Object v : values) {
        rows.add(ImmutableList.of(v));
      }
    }

    // Match the answer's columns to the value's fields.
    if (names.size() != answer.names.size()) {
      return format(
          "has fields %s; answer has columns %s", names, answer.names);
    }
    final int[] map = new int[names.size()]; // map[column] = field
    final List<Integer> unmatchedFields = new ArrayList<>();
    final List<Integer> unmatchedColumns = new ArrayList<>();
    for (int c = 0; c < answer.names.size(); c++) {
      final int f = names.indexOf(answer.names.get(c));
      map[c] = f;
      if (f < 0) {
        unmatchedColumns.add(c);
      }
    }
    for (int f = 0; f < names.size(); f++) {
      if (!answer.names.contains(names.get(f))) {
        unmatchedFields.add(f);
      }
    }
    if (unmatchedColumns.size() == 1) {
      map[unmatchedColumns.get(0)] = unmatchedFields.get(0);
    } else if (!unmatchedColumns.isEmpty()) {
      return format(
          "has fields %s; answer has columns %s", names, answer.names);
    }

    // Each row as a list of cells in the answer's column order.
    final List<List<Object>> actual = new ArrayList<>();
    for (List<Object> row : rows) {
      final List<Object> cells = new ArrayList<>();
      for (int c = 0; c < map.length; c++) {
        cells.add(normalize(row.get(map[c])));
      }
      actual.add(cells);
    }
    final List<List<Object>> expected = answer.rows;
    if (actual.size() != expected.size()) {
      return format(
          "has %d rows; answer has %d", actual.size(), expected.size());
    }
    final Comparator<List<Object>> comparator = TpcdsChecker::compareRows;
    actual.sort(comparator);
    expected.sort(comparator);
    for (int i = 0; i < actual.size(); i++) {
      if (!rowsEqual(actual.get(i), expected.get(i))) {
        return format(
            "row %s differs from answer %s (columns %s)",
            actual.get(i), expected.get(i), answer.names);
      }
    }
    return "ok";
  }

  /**
   * Converts a Morel value to the form in which an answer holds it: null for
   * {@code NONE}, the value for {@code SOME v}, a {@link BigDecimal} for a
   * number, a string for a date.
   */
  private static @Nullable Object normalize(Object v) {
    if (v instanceof List) {
      final List<?> list = (List<?>) v;
      if (list.size() == 1 && "NONE".equals(list.get(0))) {
        return null;
      }
      if (list.size() == 2 && "SOME".equals(list.get(0))) {
        return normalize(list.get(1));
      }
      return v;
    }
    if (v instanceof Integer) {
      return new BigDecimal((Integer) v);
    }
    if (v instanceof Float) {
      return new Real(((Float) v).doubleValue());
    }
    if (v instanceof Double) {
      return new Real((Double) v);
    }
    if (v instanceof OffsetDateTime) {
      return ((OffsetDateTime) v).toLocalDate().toString();
    }
    return v;
  }

  private static int compareRows(List<Object> r0, List<Object> r1) {
    for (int i = 0; i < r0.size(); i++) {
      final int c = compareCells(r0.get(i), r1.get(i));
      if (c != 0) {
        return c;
      }
    }
    return 0;
  }

  /**
   * Compares two cells for sorting. Nulls first, then numbers (a real rounded
   * to a few digits, so that two that are equal within tolerance usually sort
   * together), then everything else as text.
   */
  private static int compareCells(@Nullable Object o0, @Nullable Object o1) {
    if (o0 == null || o1 == null) {
      return o0 == null ? (o1 == null ? 0 : -1) : 1;
    }
    final @Nullable BigDecimal n0 = sortKey(o0);
    final @Nullable BigDecimal n1 = sortKey(o1);
    if (n0 != null && n1 != null) {
      return n0.compareTo(n1);
    }
    if (n0 != null || n1 != null) {
      return n0 != null ? -1 : 1;
    }
    return o0.toString().compareTo(o1.toString());
  }

  private static @Nullable BigDecimal sortKey(Object o) {
    if (o instanceof BigDecimal) {
      return (BigDecimal) o;
    }
    if (o instanceof Real) {
      return new BigDecimal(
          format("%.4e", ((Real) o).value), MathContext.DECIMAL64);
    }
    return null;
  }

  private static boolean rowsEqual(List<Object> r0, List<Object> r1) {
    for (int i = 0; i < r0.size(); i++) {
      if (!cellsEqual(r0.get(i), r1.get(i))) {
        return false;
      }
    }
    return true;
  }

  private static boolean cellsEqual(@Nullable Object o0, @Nullable Object o1) {
    if (o0 == null || o1 == null) {
      return o0 == o1;
    }
    if (o0 instanceof Real || o1 instanceof Real) {
      final double d0 = toDouble(o0);
      final double d1 = toDouble(o1);
      return Math.abs(d0 - d1)
          <= REAL_TOLERANCE * Math.max(Math.abs(d0), Math.abs(d1));
    }
    if (o0 instanceof BigDecimal && o1 instanceof BigDecimal) {
      final BigDecimal actual = (BigDecimal) o0;
      final BigDecimal expected = (BigDecimal) o1;
      if (actual.stripTrailingZeros().scale()
          > expected.stripTrailingZeros().scale()) {
        // Morel kept more digits than Spark, as after a division; they
        // need only agree to Spark's precision.
        return actual
                .round(DECIMAL_PRECISION)
                .compareTo(expected.round(DECIMAL_PRECISION))
            == 0;
      }
      return actual.compareTo(expected) == 0;
    }
    return o0.toString().equals(o1.toString());
  }

  private static double toDouble(Object o) {
    if (o instanceof Real) {
      return ((Real) o).value;
    }
    if (o instanceof BigDecimal) {
      return ((BigDecimal) o).doubleValue();
    }
    return Double.NaN;
  }

  /** A real number, compared within a tolerance. */
  private static class Real {
    final double value;

    Real(double value) {
      this.value = value;
    }

    @Override
    public String toString() {
      return Double.toString(value);
    }
  }

  /** An answer: the column names, and the rows, already normalized. */
  static class Answer {
    final List<String> names;
    final List<List<Object>> rows;

    Answer(List<String> names, List<List<Object>> rows) {
      this.names = names;
      this.rows = rows;
    }

    /**
     * Reads an answer file. Its first line is a header of {@code name:type}
     * columns, optionally followed by {@code option}; then one line per row, an
     * empty field being null, and a string that holds a comma or quote being in
     * single quotes, a quote doubled.
     */
    static Answer read(File file) throws IOException {
      final List<String> names = new ArrayList<>();
      final List<String> types = new ArrayList<>();
      final List<List<Object>> rows = new ArrayList<>();
      try (BufferedReader r =
          Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
        final String header = requireNonNull(r.readLine(), "header");
        for (String column : header.split(",")) {
          final String[] nameType = column.split(":", 2);
          names.add(nameType[0].toLowerCase(Locale.ROOT));
          types.add(nameType[1].split(" ")[0]);
        }
        for (String line; (line = r.readLine()) != null; ) {
          final List<String> fields = split(line);
          final List<Object> row = new ArrayList<>();
          for (int i = 0; i < types.size(); i++) {
            row.add(parse(types.get(i), fields.get(i)));
          }
          rows.add(row);
        }
      }
      return new Answer(names, rows);
    }

    private static @Nullable Object parse(String type, String field) {
      if (field.isEmpty()) {
        return null;
      }
      switch (type) {
        case "int":
        case "decimal":
          return new BigDecimal(field);
        case "real":
          return new Real(Double.parseDouble(field));
        default:
          return field;
      }
    }

    /** Splits a line into fields, removing quotes. */
    private static List<String> split(String line) {
      final List<String> fields = new ArrayList<>();
      final StringBuilder b = new StringBuilder();
      int i = 0;
      for (; ; ) {
        b.setLength(0);
        if (i < line.length() && line.charAt(i) == '\'') {
          ++i;
          for (; ; ) {
            final char c = line.charAt(i++);
            if (c == '\'') {
              if (i < line.length() && line.charAt(i) == '\'') {
                b.append('\'');
                ++i;
              } else {
                break;
              }
            } else {
              b.append(c);
            }
          }
        } else {
          while (i < line.length() && line.charAt(i) != ',') {
            b.append(line.charAt(i++));
          }
        }
        fields.add(b.toString());
        if (i >= line.length()) {
          return fields;
        }
        ++i; // skip ','
      }
    }
  }
}

// End TpcdsChecker.java
