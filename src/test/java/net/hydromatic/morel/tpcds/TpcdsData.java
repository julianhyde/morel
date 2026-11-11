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

import static com.google.common.base.Preconditions.checkArgument;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.trino.tpcds.Results;
import io.trino.tpcds.Session;
import io.trino.tpcds.Table;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

/**
 * Generates the TPC-DS data set, for hydromatic/morel#502.
 *
 * <p>Uses Trino's Java port of {@code dsdgen} ({@code io.trino.tpcds}), and
 * writes one {@code TABLE.csv.gz} per table of {@code tpcds/schema.sql} into a
 * directory, in the format that Morel's file reader ({@code file.data}) reads:
 * a header of {@code name:type} fields, with {@code option} after the type of
 * each nullable column; a null as an empty field; a string in single quotes if
 * it is empty or holds a comma, a quote or a line ending. It also writes {@code
 * VERSION}, naming the generator and the scale, and prints the row count and
 * the null count of each column.
 *
 * <p>The output is deterministic: the same scale gives the same bytes.
 *
 * <p>Usage: {@code TpcdsData DIR [SCALE]}; the scale defaults to 0.01. See
 * {@code src/test/resources/tpcds/generate.sh}, which calls it.
 */
public class TpcdsData {
  /** Version of the generator; must match {@code tpcds.version} in pom.xml. */
  static final String GENERATOR = "io.trino.tpcds:tpcds:1.4";

  /**
   * Columns that the generator names differently from Spark's schema (and so
   * from {@code schema.sql} and the queries): a truncation and a misspelling
   * that are in {@code dsdgen}, and a column that the specification renamed.
   */
  private static final Map<String, String> RENAMES =
      ImmutableMap.of(
          "c_last_review_date_sk", "c_last_review_date",
          "p_response_targe", "p_response_target",
          "s_tax_precentage", "s_tax_percentage");

  private TpcdsData() {}

  public static void main(String[] args) throws IOException {
    if (args.length < 1 || args.length > 2) {
      System.err.println("Usage: TpcdsData DIR [SCALE]");
      System.exit(2);
    }
    final File dir = new File(args[0]);
    final double scale = args.length > 1 ? Double.parseDouble(args[1]) : 0.01;
    generate(dir, scale, System.out);
  }

  /** Generates every table into {@code dir}. */
  static void generate(File dir, double scale, PrintStream out)
      throws IOException {
    if (!dir.isDirectory() && !dir.mkdirs()) {
      throw new IOException("cannot create directory " + dir);
    }
    final Map<String, TableDef> tables = schema();
    final Session session = Session.getDefaultSession().withScale(scale);
    for (TableDef tableDef : tables.values()) {
      final Table table = Table.getTable(tableDef.name);
      if (table.isChild()) {
        continue; // generated with its parent
      }
      final TableDef childDef =
          table.hasChild()
              ? requireNonNull(tables.get(table.getChild().getName()))
              : null;
      check(tableDef, table);
      if (childDef != null) {
        check(childDef, table.getChild());
      }
      try (TableWriter w = new TableWriter(dir, tableDef);
          TableWriter cw =
              childDef == null ? null : new TableWriter(dir, childDef)) {
        for (List<List<String>> rows :
            Results.constructResults(table, session)) {
          if (!rows.isEmpty()) {
            w.write(rows.get(0));
          }
          if (rows.size() > 1) {
            requireNonNull(cw, "child row, but no child table")
                .write(rows.get(1));
          }
        }
        w.report(out);
        if (cw != null) {
          cw.report(out);
        }
      }
    }
    try (PrintWriter pw =
        new PrintWriter(
            new OutputStreamWriter(
                new FileOutputStream(new File(dir, "VERSION")),
                StandardCharsets.UTF_8))) {
      pw.printf(Locale.ROOT, "generator=%s%n", GENERATOR);
      pw.printf(Locale.ROOT, "scale=%s%n", scale);
    }
  }

  /** Checks that the generator's columns are the ones the schema names. */
  private static void check(TableDef tableDef, Table table) {
    final List<String> names = new ArrayList<>();
    for (io.trino.tpcds.column.Column c : table.getColumns()) {
      names.add(RENAMES.getOrDefault(c.getName(), c.getName()));
    }
    final List<String> expected = new ArrayList<>();
    tableDef.columns.forEach(c -> expected.add(c.name));
    checkArgument(
        names.equals(expected),
        "columns of %s: generator has %s, schema.sql has %s",
        tableDef.name,
        names,
        expected);
  }

  /** Reads {@code tpcds/schema.sql}, the tables in the order it has them. */
  static Map<String, TableDef> schema() throws IOException {
    final Pattern createPattern = Pattern.compile("create table (\\w+) \\(");
    final Pattern columnPattern =
        Pattern.compile("(\\w+) +([a-z]+)(?:\\([0-9, ]+\\))?( not null)?,?");
    final Map<String, TableDef> tables = new LinkedHashMap<>();
    try (InputStream in =
            requireNonNull(
                TpcdsData.class.getResourceAsStream("/tpcds/schema.sql"),
                "tpcds/schema.sql not on the class path");
        BufferedReader r =
            new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
      String name = null;
      final List<ColumnDef> columns = new ArrayList<>();
      for (String line; (line = r.readLine()) != null; ) {
        line = line.trim();
        if (line.isEmpty() || line.startsWith("--")) {
          continue;
        }
        if (name == null) {
          final Matcher m = createPattern.matcher(line);
          checkArgument(m.matches(), "expected create table: %s", line);
          name = m.group(1);
        } else if (line.equals(");")) {
          tables.put(name, new TableDef(name, columns));
          name = null;
          columns.clear();
        } else {
          final Matcher m = columnPattern.matcher(line);
          checkArgument(m.matches(), "expected column: %s", line);
          columns.add(
              new ColumnDef(
                  m.group(1), morelType(m.group(2)), m.group(3) == null));
        }
      }
      checkArgument(name == null, "unterminated table %s", name);
    }
    return tables;
  }

  /** Converts a SQL type name in {@code schema.sql} to a header type. */
  private static String morelType(String sqlType) {
    switch (sqlType) {
      case "int":
      case "date":
      case "decimal":
        return sqlType;
      case "char":
      case "varchar":
        return "string";
      default:
        throw new IllegalArgumentException("unknown type " + sqlType);
    }
  }

  /** A table in {@code schema.sql}. */
  static class TableDef {
    final String name;
    final List<ColumnDef> columns;

    TableDef(String name, List<ColumnDef> columns) {
      this.name = name;
      this.columns = ImmutableList.copyOf(columns);
    }
  }

  /** A column in {@code schema.sql}. */
  static class ColumnDef {
    final String name;
    /** Morel's type, as a header names it: "int", "string", ... */
    final String type;

    final boolean nullable;

    ColumnDef(String name, String type, boolean nullable) {
      this.name = name;
      this.type = type;
      this.nullable = nullable;
    }

    /** The field for this column in the header, e.g. "x:int option". */
    String header() {
      return name + ":" + type + (nullable ? " option" : "");
    }
  }

  /** Writes the rows of one table, and counts them and their nulls. */
  private static class TableWriter implements AutoCloseable {
    private final TableDef tableDef;
    private final Writer w;
    private final StringBuilder b = new StringBuilder();
    private final long[] nullCounts;
    private long rowCount;

    TableWriter(File dir, TableDef tableDef) throws IOException {
      this.tableDef = tableDef;
      this.nullCounts = new long[tableDef.columns.size()];
      final File file = new File(dir, tableDef.name + ".csv.gz");
      this.w =
          new OutputStreamWriter(
              new GZIPOutputStream(new FileOutputStream(file), 1 << 16),
              StandardCharsets.UTF_8);
      for (int i = 0; i < tableDef.columns.size(); i++) {
        b.append(i == 0 ? "" : ",").append(tableDef.columns.get(i).header());
      }
      w.write(b.append('\n').toString());
    }

    void write(List<String> values) throws IOException {
      checkArgument(
          values.size() == nullCounts.length,
          "%s: row has %s values, table has %s columns",
          tableDef.name,
          values.size(),
          nullCounts.length);
      b.setLength(0);
      for (int i = 0; i < values.size(); i++) {
        if (i > 0) {
          b.append(',');
        }
        final String value = values.get(i);
        final ColumnDef column = tableDef.columns.get(i);
        if (value == null) {
          if (!column.nullable) {
            throw new IllegalStateException(
                format(
                    "null in %s.%s, which is not null, in row %d",
                    tableDef.name, column.name, rowCount + 1));
          }
          ++nullCounts[i];
        } else if (column.type.equals("string")) {
          appendString(b, value);
        } else {
          b.append(value);
        }
      }
      w.write(b.append('\n').toString());
      ++rowCount;
    }

    /**
     * Appends a string field, quoted if it is empty (so that it is not read as
     * null), is "NULL", or holds a comma, a quote or a line ending.
     */
    private static void appendString(StringBuilder b, String value) {
      if (value.isEmpty()
          || value.equals("NULL")
          || value.charAt(0) == ' '
          || value.charAt(value.length() - 1) == ' '
          || value.indexOf(',') >= 0
          || value.indexOf('\'') >= 0
          || value.indexOf('"') >= 0
          || value.indexOf('\n') >= 0
          || value.indexOf('\r') >= 0) {
        b.append('\'').append(value.replace("'", "''")).append('\'');
      } else {
        b.append(value);
      }
    }

    /** Prints the row count and each column's nulls. */
    void report(PrintStream out) {
      final StringBuilder s = new StringBuilder();
      s.append(format(Locale.ROOT, "%s: %d rows", tableDef.name, rowCount));
      for (int i = 0; i < nullCounts.length; i++) {
        if (nullCounts[i] > 0) {
          s.append(
              format(
                  Locale.ROOT,
                  "%n  %s: %d nulls (%.1f%%)",
                  tableDef.columns.get(i).name,
                  nullCounts[i],
                  100d * nullCounts[i] / rowCount));
        }
      }
      out.println(s);
    }

    @Override
    public void close() throws IOException {
      w.close();
    }
  }
}

// End TpcdsData.java
