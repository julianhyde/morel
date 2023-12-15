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

import net.hydromatic.morel.type.Keys;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.ImmutablePairList;
import net.hydromatic.morel.util.PairList;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Maps;
import org.apache.calcite.util.Util;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.zip.GZIPInputStream;

import static java.util.Objects.requireNonNull;

/** Directory in the file system.
 *
 * <p>Its type is progressive, so that it can discover new files and
 * subdirectories.
 */
public abstract class File implements Codes.TypedValue {
  final java.io.File ioFile;
  final String baseName;
  final FileType fileType;

  /** Creates a File. */
  File(java.io.File ioFile, FileType fileType) {
    this.ioFile = requireNonNull(ioFile, "file");
    this.baseName = removeSuffix(ioFile.getName(), fileType.suffix);
    this.fileType = requireNonNull(fileType, "fileType");
  }

  @Override public String toString() {
    return baseName;
  }

  /** Creates a file based on the default directory (src/test/resources). */
  public static File createDefault() {
    final java.io.File file =
        new java.io.File("/home/jhyde/dev/morel.4/src/test/resources");
    return requireNonNull(create(file), "file");
  }

  /** Creates a file (or directory).
   * Returns null if it is not a recognized type. */
  public static File create(java.io.File ioFile) {
    return UnknownFile.createUnknown(ioFile).expand();
  }

  static UnknownFile createUnknown(java.io.File ioFile) {
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
    return new UnknownFile(ioFile, fileType);
  }

  private static String removeSuffix(String s, String suffix) {
    if (s.endsWith(suffix)) {
      return s;
    }
    return s.substring(0, s.length() - suffix.length());
  }

  private static PairList<String, Type.Key> deduceFieldsCsv(BufferedReader r)
      throws IOException {
    String firstLine = r.readLine();
    if (firstLine == null) {
      // File is empty. There will be no fields, and row type will be unit.
      return ImmutablePairList.of();
    }

    final PairList<String, Type.Key> nameTypes = PairList.of();
    for (String field : firstLine.split(",")) {
      final String[] split = field.split(":");
      final String subFieldName = split[0];
      final String subFieldType =
          split.length > 1 ? split[1] : "string";
      Type.Key subType;
      switch (subFieldType) {
      case "bool":
        subType = PrimitiveType.BOOL.key();
        break;
      case "decimal":
      case "double":
        subType = PrimitiveType.REAL.key();
        break;
      case "int":
        subType = PrimitiveType.INT.key();
        break;
      default:
        subType = PrimitiveType.STRING.key();
        break;
      }
      nameTypes.add(subFieldName, subType);
    }
    return nameTypes;
  }

  /** Creates a function that converts a string field value to the desired
   * type. */
  static Function<String, Object> parser(Type.Key type) {
    switch (type.op) {
    case DATA_TYPE:
      switch (type.toString()) {
      case "int":
        return s -> s.equals("NULL") ? 0 : Integer.parseInt(s);
      case "real":
        return s -> s.equals("NULL") ? 0f : Float.parseFloat(s);
      case "string":
        return File::unquoteString;
      default:
        throw new IllegalArgumentException("unknown type " + type);
      }
    default:
      throw new IllegalArgumentException("unknown type " + type);
    }
  }

  /** Converts "abc" to "abc" and "'abc, def'" to "abc, def". */
  static Object unquoteString(String s) {
    if (s.startsWith("'")) {
      return s.substring(1, s.length() - 1);
    }
    return s;
  }

  /** File that is a directory. */
  private static class Directory extends File {
    final SortedMap<String, File> entries; // mutable

    Directory(java.io.File file, SortedMap<String, File> entries) {
      super(file, FileType.DIRECTORY);
      this.entries = requireNonNull(entries, "entries");
    }

    @Override public <V> V valueAs(Class<V> clazz) {
      if (clazz.isAssignableFrom(ImmutableList.class)) {
        return clazz.cast(
            entries == null
                ? ImmutableList.of()
                : ImmutableList.copyOf(entries.values()));
      }
      throw new IllegalArgumentException("not a " + clazz);
    }

    @Override public Type.Key typeKey() {
      return Keys.progressiveRecord(
          Maps.transformValues(entries, Codes.TypedValue::typeKey));
    }

    @Override public boolean discoverField(TypeSystem typeSystem,
        String fieldName) {
      final File subFile = entries.get(fieldName);
      if (subFile instanceof UnknownFile) {
        entries.put(fieldName, ((UnknownFile) subFile).expand());
        return true;
      }
      return true;
    }

  }

  /** File that is not a directory, and can be parsed into a set of records. */
  private static class DataFile extends File {
    final Type.Key typeKey;
    final PairList<Integer, Function<String, Object>> parsers;

    DataFile(java.io.File file, FileType fileType, Type.Key typeKey,
        PairList<Integer, Function<String, Object>> parsers) {
      super(file, fileType);
      this.typeKey = requireNonNull(typeKey, "typeKey");
      this.parsers = parsers.immutable();
    }

    @Override public <V> V valueAs(Class<V> clazz) {
      try (BufferedReader r = fileType.open(ioFile)) {
        String firstLine = r.readLine();
        if (firstLine == null) {
          return null;
        }
        final Object[] values = new Object[parsers.size()];
        final List<List<Object>> list = new ArrayList<>();
        for (;;) {
          String line = r.readLine();
          if (line == null) {
            return clazz.cast(list);
          }
          String[] fields = line.split(",");
          parsers.forEachIndexed((i, j, parser) ->
              values[j] = parser.apply(fields[i]));
          list.add(ImmutableList.copyOf(values));
        }
      } catch (IOException e) {
        // ignore
        return null;
      }
    }

    @Override public Type.Key typeKey() {
      return typeKey;
    }
  }

  /** File that we have not yet categorized. We don't know whether it is a
   * directory.
   *
   * <p>Its type is an empty record type (because we don't know the files in the
   * directory, or the fields of the data file). */
  private static class UnknownFile extends File {
    protected UnknownFile(java.io.File file, FileType fileType) {
      super(file, fileType);
    }

    @Override public <V> V valueAs(Class<V> clazz) {
      if (clazz.isAssignableFrom(ImmutableList.class)) {
        return clazz.cast(ImmutableList.of());
      }
      throw new IllegalArgumentException("not a " + clazz);
    }

    @Override public Type.Key typeKey() {
      return Keys.progressiveRecord(ImmutableSortedMap.of());
    }

    public File expand() {
      switch (fileType) {
      case DIRECTORY:
        final SortedMap<String, File> entries =
            new TreeMap<>(RecordType.ORDERING);
        for (java.io.File subFile
            : Util.first(ioFile.listFiles(), new java.io.File[0])) {
          UnknownFile f = createUnknown(subFile);
          entries.put(f.baseName, f);
        }
        return new Directory(ioFile, entries);

      case FILE:
        return this;

      default:
        try (BufferedReader r = fileType.open(ioFile)) {
          final PairList<String, Type.Key> nameTypes = fileType.deduceFields(r);
          final ImmutableSortedMap<String, Type.Key> sortedNameTypes =
              ImmutableSortedMap.<String, Type.Key>orderedBy(RecordType.ORDERING)
                  .putAll(nameTypes)
                  .build();
          final PairList<Integer, Function<String, Object>> fieldParsers =
              PairList.of();
          nameTypes.forEach((name, typeKey) -> {
            final int j = sortedNameTypes.keySet().asList().indexOf(name);
            fieldParsers.add(j, parser(typeKey));
          });

          final Type.Key listType = Keys.list(Keys.record(sortedNameTypes));
          return new DataFile(ioFile, fileType, listType, fieldParsers);
        } catch (IOException e) {
          // ignore, and skip file
          return null;
        }
      }
    }
  }

  /** Describes a type of file that can be read by this reader.
   * Each file has a way to deduce the schema (set of field names and types)
   * and to parse the file into a set of records. */
  enum FileType {
    DIRECTORY(""),
    FILE(""),
    CSV(".csv"),
    CSV_GZ(".csv.gz");

    /** The non-trivial file types. */
    static final List<FileType> INSTANCES =
        Arrays.stream(values())
            .filter(f -> !f.suffix.isEmpty())
            .collect(ImmutableList.toImmutableList());

    final String suffix;

    FileType(String suffix) {
      this.suffix = suffix;
    }

    BufferedReader open(java.io.File file) throws IOException {
      switch (this) {
      case CSV:
        return Util.reader(file);
      case CSV_GZ:
        return Util.reader(
            new GZIPInputStream(Files.newInputStream(file.toPath())));
      default:
        throw new IllegalArgumentException("cannot open file " + file
            + " of type " + this);
      }
    }

    PairList<String, Type.Key> deduceFields(BufferedReader r)
        throws IOException {
      return deduceFieldsCsv(r);
    }
  }
}

// End File.java
