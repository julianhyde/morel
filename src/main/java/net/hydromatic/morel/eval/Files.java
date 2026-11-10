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
import static java.nio.file.Files.newInputStream;
import static java.util.Objects.requireNonNull;
import static org.apache.calcite.util.Util.first;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import java.io.BufferedReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.zip.GZIPInputStream;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.type.Keys;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.type.TypedValue;
import net.hydromatic.morel.util.Ord;
import org.apache.calcite.util.Util;
import org.jspecify.annotations.Nullable;

/** Implementations of {@link File}. */
public class Files {
  private Files() {}

  /** Creates a file (or directory). Never returns null. */
  // lint:skip 1
  public static File create(java.io.File ioFile) {
    return createUnknown(null, ioFile).expand();
  }

  static UnknownFile createUnknown(
      @Nullable Directory directory, java.io.File ioFile) { // lint:skip
    FileType fileType;
    if (ioFile.isDirectory()) {
      fileType = FileType.DIRECTORY;
    } else {
      fileType = FileType.FILE;
      for (FileType fileType2 : FileType.INSTANCES) {
        if (ioFile.getName().endsWith(fileType2.suffix)) {
          fileType = fileType2;
          break;
        }
      }
    }
    if (directory != null) {
      return new UnknownChildFile(directory, ioFile, fileType);
    } else {
      return new UnknownFile(ioFile, fileType);
    }
  }

  /**
   * Returns a string without its suffix; for example, {@code
   * removeSuffix("x.txt", ".txt")} returns {@code "x"}.
   */
  private static String removeSuffix(String s, String suffix) {
    if (!s.endsWith(suffix)) {
      return s;
    }
    return s.substring(0, s.length() - suffix.length());
  }

  private static List<Column> deduceFieldsCsv(BufferedReader r)
      throws IOException {
    String firstLine = r.readLine();
    if (firstLine == null) {
      // File is empty. There will be no fields, and row type will be unit.
      return ImmutableList.of();
    }

    final ImmutableList.Builder<Column> columns = ImmutableList.builder();
    for (String field : firstLine.split(",")) {
      final String[] split = field.split(":");
      final String name = split[0];
      final String type = split.length > 1 ? split[1] : "string";
      columns.add(Column.of(name, type));
    }
    return columns.build();
  }

  /**
   * Parses lines of a CSV file into values.
   *
   * <p>Fields are separated by commas. A field that starts with a single quote
   * runs to the next single quote that is not doubled, and may contain commas;
   * a doubled quote inside it stands for one quote. The quotes stay in the
   * field that is given to the column's parser, so that a quoted empty string,
   * {@code ''}, can be told from an empty field; {@link #unquoteString} removes
   * them. A trailing empty field is a field, so that "1," has two.
   *
   * <p>Each field is converted as soon as it is found, by its column's parser,
   * and the value goes straight into the row's array of values; there is no
   * intermediate list of fields. A field is a substring of the line, without
   * copying through a buffer, unless it contains a doubled quote. Because it
   * re-uses its buffer, a parser must not be shared between threads.
   */
  static class LineParser {
    /** The columns of the file, in the order they occur in a line. */
    private final Column[] columns;

    private final StringBuilder b = new StringBuilder();

    LineParser(List<Column> columns) {
      this.columns = columns.toArray(new Column[0]);
    }

    /**
     * Parses a line, putting the value of column {@code c} of the file into
     * {@code values[targets[c]]}.
     *
     * <p>Fields beyond the last column are ignored. A line with fewer fields
     * than there are columns raises {@code Fail}.
     */
    void parse(String line, int[] targets, Object[] values) {
      final int n = line.length();
      int i = 0;
      for (int c = 0; c < columns.length; c++) {
        if (i > n) {
          throw new MorelRuntimeException(
              BuiltInExn.FAIL,
              format(
                  "line has %d fields, but the header has %d: %s",
                  c, columns.length, line),
              Pos.ZERO);
        }
        final int start = i;
        final String field;
        if (i < n && line.charAt(i) == '\'') {
          // A quoted field. Find the closing quote, skipping doubled quotes.
          boolean doubled = false;
          ++i;
          while (i < n) {
            if (line.charAt(i++) == '\'') {
              if (i < n && line.charAt(i) == '\'') {
                doubled = true;
                ++i;
                continue;
              }
              break;
            }
          }
          // Anything between the closing quote and the comma is part of the
          // field.
          while (i < n && line.charAt(i) != ',') {
            ++i;
          }
          field = doubled ? undouble(line, start, i) : line.substring(start, i);
        } else {
          final int comma = line.indexOf(',', i);
          i = comma < 0 ? n : comma;
          field = line.substring(start, i);
        }
        values[targets[c]] = columns[c].parser.apply(field);
        ++i; // skip the comma; beyond the end of the line if there was none
      }
    }

    /**
     * Returns the quoted field that runs from {@code start} to {@code end} of a
     * line, with each doubled quote after the opening quote made single.
     */
    private String undouble(String line, int start, int end) {
      b.setLength(0);
      b.append('\'');
      for (int k = start + 1; k < end; k++) {
        final char c = line.charAt(k);
        b.append(c);
        if (c == '\'' && k + 1 < end && line.charAt(k + 1) == '\'') {
          ++k;
        }
      }
      return b.toString();
    }
  }

  /** Converts "abc" to "abc" and "'abc, def'" to "abc, def". */
  static Object unquoteString(String s) {
    if (s.startsWith("'")) {
      return s.substring(1, s.length() - 1);
    }
    return s;
  }

  /**
   * Whether a field holds no value: {@code NULL}, or nothing at all. (A quoted
   * empty string, {@code ''}, is a value.)
   */
  private static boolean isNull(String s) {
    return s.isEmpty() || s.equals("NULL");
  }

  /**
   * A column of a CSV file: its name, its type, and how to convert a field to a
   * value of that type.
   *
   * <p>The header of a file declares each column as {@code name:type}, where
   * type is {@code bool}, {@code int}, {@code real} (or its synonym {@code
   * double}), {@code decimal}, {@code date} or {@code string}, optionally
   * followed by {@code option}; a missing or unknown type is {@code string}. In
   * an option column, an empty field or {@code NULL} is {@code NONE}; in any
   * other column, it raises {@code Fail} when the file is read, because the
   * column's type has no room for it.
   */
  static class Column {
    final String name;
    final Type.Key typeKey;
    final Function<String, Object> parser;

    Column(String name, Type.Key typeKey, Function<String, Object> parser) {
      this.name = requireNonNull(name, "name");
      this.typeKey = requireNonNull(typeKey, "typeKey");
      this.parser = requireNonNull(parser, "parser");
    }

    /** Creates a column from its name and type, as the header gives them. */
    static Column of(String name, String type) {
      final String[] words = type.trim().split(" +");
      if (words.length == 2 && words[1].equals("option")) {
        final Column column = of(name, words[0]);
        return new Column(
            name,
            Keys.apply(Keys.name("option"), ImmutableList.of(column.typeKey)),
            s ->
                isNull(s)
                    ? Codes.OPTION_NONE
                    : Codes.optionSome(column.parser.apply(s)));
      }
      final Type.Key typeKey;
      final Function<String, Object> parser;
      switch (words[0]) {
        case "bool":
          typeKey = PrimitiveType.BOOL.key();
          parser = Boolean::parseBoolean;
          break;
        case "date":
          typeKey = Keys.name("date");
          parser = Files::parseDate;
          break;
        case "decimal":
          typeKey = Keys.name("decimal");
          parser = s -> parseDecimal(name, s);
          break;
        case "double":
        case "real":
          typeKey = PrimitiveType.REAL.key();
          parser = Float::parseFloat;
          break;
        case "int":
          typeKey = PrimitiveType.INT.key();
          parser = Integer::parseInt;
          break;
        default:
          typeKey = PrimitiveType.STRING.key();
          parser = Files::unquoteString;
          break;
      }
      return new Column(
          name,
          typeKey,
          s -> {
            if (isNull(s)) {
              throw new MorelRuntimeException(
                  BuiltInExn.FAIL,
                  format(
                      "null in column %s, whose type %s has no room for it",
                      name, words[0]),
                  Pos.ZERO);
            }
            return parser.apply(s);
          });
    }
  }

  /**
   * Parses a date, such as "1980-12-17" or "'1980-12-17'", as midnight UTC on
   * that day; that is also how a Spark {@code date} is read.
   */
  private static Object parseDate(String s) {
    final String s2 = (String) unquoteString(s);
    return OffsetDateTime.of(localDate(s2).atStartOfDay(), ZoneOffset.UTC);
  }

  /**
   * Parses a date in ISO format. Handles "yyyy-mm-dd" directly, because {@link
   * LocalDate#parse} goes through a general-purpose formatter and is several
   * times slower; anything else (a year beyond four digits, a malformed date)
   * goes to {@link LocalDate#parse}, which accepts or rejects it.
   */
  private static LocalDate localDate(String s) {
    if (s.length() == 10 && s.charAt(4) == '-' && s.charAt(7) == '-') {
      final int y = digits(s, 0, 4);
      final int m = digits(s, 5, 7);
      final int d = digits(s, 8, 10);
      if (y >= 0 && m >= 0 && d >= 0) {
        return LocalDate.of(y, m, d);
      }
    }
    return LocalDate.parse(s);
  }

  /**
   * Returns the value of the decimal digits from {@code start} to {@code end}
   * of a string, or -1 if any of them is not a digit.
   */
  private static int digits(String s, int start, int end) {
    int v = 0;
    for (int i = start; i < end; i++) {
      final char c = s.charAt(i);
      if (c < '0' || c > '9') {
        return -1;
      }
      v = v * 10 + (c - '0');
    }
    return v;
  }

  /** Parses a decimal, such as "800.00", in canonical form. */
  private static Object parseDecimal(String name, String s) {
    final BigDecimal d = Decimals.canonical(new BigDecimal(s));
    if (d == null) {
      throw new MorelRuntimeException(
          BuiltInExn.OVERFLOW,
          format("value %s in column %s is too large for a decimal", s, name),
          Pos.ZERO);
    }
    return d;
  }

  /** Abstract implementation of File. */
  abstract static class AbstractFile implements File {
    final java.io.File ioFile; // lint:skip
    final String baseName;
    final FileType fileType;

    /** Creates an AbstractFile. */
    AbstractFile(java.io.File ioFile, FileType fileType) { // lint:skip
      this.ioFile = requireNonNull(ioFile, "file");
      this.baseName = removeSuffix(ioFile.getName(), fileType.suffix);
      this.fileType = requireNonNull(fileType, "fileType");
    }

    @Override
    public String toString() {
      return baseName;
    }
  }

  /** File that is a directory. */
  private static class Directory extends AbstractList<File> implements File {
    final java.io.File ioFile; // lint:skip
    final SortedMap<String, File> entries; // mutable

    Directory(java.io.File file) { // lint:skip
      this.ioFile = file;

      entries = new TreeMap<>(RecordType.ORDERING);
      for (java.io.File subFile : // lint:skip
          first(ioFile.listFiles(), new java.io.File[0])) { // lint:skip
        UnknownFile f = createUnknown(this, subFile);
        entries.put(f.baseName, f);
      }
    }

    @Override
    public Type.Key typeKey() {
      return Keys.progressiveRecord(
          Maps.transformValues(entries, TypedValue::typeKey));
    }

    @Override
    public File discoverField(TypeSystem typeSystem, String fieldName) {
      final File file = entries.get(fieldName);
      if (file != null) {
        File file2 = file.expand();
        if (file2 != file) {
          typeSystem.expandCount.incrementAndGet();
        }
      }
      return this;
    }

    @Override
    public File get(int index) {
      return entry(index);
    }

    /** Returns the {@code index}th entry. */
    private File entry(int index) {
      return requireNonNull(Iterables.get(entries.values(), index));
    }

    @Override
    public int size() {
      return entries.size();
    }

    @Override
    public <V> V valueAs(Class<V> clazz) {
      if (clazz.isInstance(this)) {
        return clazz.cast(this);
      }
      throw new IllegalArgumentException("not a " + clazz);
    }

    @Override
    public <V> V fieldValueAs(String fieldName, Class<V> clazz) {
      return clazz.cast(requireNonNull(entries.get(fieldName), fieldName));
    }

    @Override
    public <V> V fieldValueAs(int fieldIndex, Class<V> clazz) {
      return clazz.cast(entry(fieldIndex));
    }
  }

  /** File that is not a directory, and can be parsed into a set of records. */
  private static class DataFile extends AbstractFile {
    final Type.Key typeKey;

    /** The columns of the file, in the order they occur in a line. */
    final ImmutableList<Column> columns;

    DataFile(
        java.io.File file, // lint:skip
        FileType fileType,
        Type.Key typeKey,
        List<Column> columns) {
      super(file, fileType);
      this.typeKey = requireNonNull(typeKey, "typeKey");
      this.columns = ImmutableList.copyOf(columns);
    }

    @Override
    public <V> V valueAs(Class<V> clazz) {
      try (BufferedReader r = fileType.open(ioFile)) {
        String firstLine = r.readLine();
        if (firstLine == null) {
          // The file is empty, and therefore has no records, just as a file
          // that has only a header line has none.
          return clazz.cast(ImmutableList.of());
        }
        // A record's fields are sorted by name, so the target of a column
        // (the ordinal of its field in the record) is the position of its
        // name in sorted order. Sort (ordinal, column) pairs by name, and
        // number them.
        final int[] targets = new int[columns.size()];
        Ord.forEach(
            RecordType.ORDERING
                .onResultOf((Ord<Column> o) -> o.e.name)
                .sortedCopy(Ord.zip(columns)),
            (o, k) -> targets[o.i] = k);
        final Object[] values = new Object[columns.size()];
        final List<List<Object>> list = new ArrayList<>();
        final LineParser parser = new LineParser(columns);
        for (; ; ) {
          String line = r.readLine();
          if (line == null) {
            return clazz.cast(list);
          }
          parser.parse(line, targets, values);
          list.add(ImmutableList.copyOf(values));
        }
      } catch (IOException e) {
        // The file cannot be read; treat it as if it had no records.
        return clazz.cast(ImmutableList.of());
      }
    }

    @Override
    public Type.Key typeKey() {
      return typeKey;
    }
  }

  /**
   * File that we have not yet categorized. We don't know whether it is a
   * directory.
   *
   * <p>Its type is an empty record type (because we don't know the files in the
   * directory, or the fields of the data file).
   */
  private static class UnknownFile extends AbstractFile {
    /**
     * Key for the type "{...}", the progressive record with no (as yet known)
     * fields.
     */
    static final Type.Key PROGRESSIVE_UNIT =
        Keys.progressiveRecord(ImmutableSortedMap.of());

    /**
     * Key for the type "{...} list", the list of progressive records with no
     * (as yet known) fields.
     */
    static final Type.Key PROGRESSIVE_UNIT_LIST = Keys.list(PROGRESSIVE_UNIT);

    protected UnknownFile(java.io.File file, FileType fileType) { // lint:skip
      super(file, fileType);
    }

    @Override
    public <V> V valueAs(Class<V> clazz) {
      if (clazz.isAssignableFrom(ImmutableList.class)) {
        return clazz.cast(ImmutableList.of());
      }
      throw new IllegalArgumentException("not a " + clazz);
    }

    @Override
    public Type.Key typeKey() {
      return fileType.list ? PROGRESSIVE_UNIT_LIST : PROGRESSIVE_UNIT;
    }

    @Override
    public File expand() {
      switch (fileType) {
        case DIRECTORY:
          return new Directory(ioFile);

        case FILE:
          return this;

        default:
          try (BufferedReader r = fileType.open(ioFile)) {
            final List<Column> columns = fileType.deduceFields(r);
            final ImmutableSortedMap.Builder<String, Type.Key> builder =
                ImmutableSortedMap.orderedBy(RecordType.ORDERING);
            columns.forEach(column -> builder.put(column.name, column.typeKey));
            final Type.Key listType = Keys.list(Keys.record(builder.build()));
            return new DataFile(ioFile, fileType, listType, columns);
          } catch (IOException e) {
            // ignore, and skip file
            return this;
          }
      }
    }

    @Override
    public File discoverField(TypeSystem typeSystem, String fieldName) {
      final File file = expand();
      if (file == this) {
        return this;
      }
      typeSystem.expandCount.incrementAndGet();
      return file.discoverField(typeSystem, fieldName);
    }
  }

  private static class UnknownChildFile extends UnknownFile {
    private final Directory directory;

    protected UnknownChildFile(
        Directory directory,
        java.io.File file, // lint:skip
        FileType fileType) {
      super(file, fileType);
      this.directory = requireNonNull(directory, "directory");
    }

    @Override
    public File expand() {
      final File file = super.expand();
      if (file != this) {
        directory.entries.put(baseName, file);
      }
      return file;
    }
  }

  /**
   * Describes a type of file that can be read by this reader. Each file has a
   * way to deduce the schema (set of field names and types) and to parse the
   * file into a set of records.
   */
  enum FileType {
    DIRECTORY("", false),
    FILE("", false),
    CSV(".csv", true),
    CSV_GZ(".csv.gz", true);

    /** The non-trivial file types. */
    static final List<FileType> INSTANCES =
        Arrays.stream(values())
            .filter(f -> !f.suffix.isEmpty())
            .collect(ImmutableList.toImmutableList());

    final String suffix;

    /** Whether this file is a list of records. */
    final boolean list;

    FileType(String suffix, boolean list) {
      this.suffix = suffix;
      this.list = list;
    }

    BufferedReader open(java.io.File file) throws IOException { // lint:skip
      switch (this) {
        case CSV:
          return Util.reader(file);
        case CSV_GZ:
          return Util.reader(
              new GZIPInputStream(newInputStream(file.toPath())));
        default:
          throw new IllegalArgumentException(
              format("cannot open file %s of type %s", file, this));
      }
    }

    List<Column> deduceFields(BufferedReader r) throws IOException {
      return deduceFieldsCsv(r);
    }
  }
}

// End Files.java
