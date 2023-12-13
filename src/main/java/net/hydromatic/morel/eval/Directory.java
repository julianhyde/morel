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
import net.hydromatic.morel.type.ProgressiveRecordType;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.ImmutablePairList;
import net.hydromatic.morel.util.PairList;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Maps;
import org.apache.calcite.util.Util;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import java.util.function.Function;
import java.util.zip.GZIPInputStream;

import static java.util.Objects.requireNonNull;

/** Directory in the file system.
 *
 * <p>Its type is progressive, so that it can discover new files and
 * subdirectories.
 */
public class Directory implements Codes.TypedValue {
  private final File file;
  private @Nullable SortedMap<String, Codes.TypedValue> entries = null;

  /** Creates a Directory in the default directory. */
  public Directory() {
    this(new File("/home/jhyde/dev/morel.4/src/test/resources"));
  }

  /** Creates a Directory. */
  public Directory(File file) {
    this.file = requireNonNull(file, "file");
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
    if (entries == null) {
      return Keys.progressiveRecord(
          ProgressiveRecordType.DefaultHandler.INSTANCE,
          ImmutableSortedMap.of());
    }
    return Keys.record(Maps.transformValues(entries, Codes.TypedValue::typeKey));
  }

  @Override public boolean discoverField(TypeSystem typeSystem, String fieldName) {
    if (entries != null) {
      return false; // already populated
    }
    if (!file.isDirectory()) {
      return false;
    }

    final ImmutableSortedMap.Builder<String, Codes.TypedValue> entries =
        ImmutableSortedMap.orderedBy(RecordType.ORDERING);
    for (File subFile : Util.first(file.listFiles(), new File[0])) {
      if (subFile.isDirectory()) {
        entries.put(subFile.getName(), new Directory(subFile));
      } else if (subFile.isFile()) {
        for (FileType fileType : FileType.values()) {
          if (subFile.getName().endsWith(fileType.suffix)) {
            String name = removeSuffix(subFile.getName(), fileType.suffix);
            final DataFile dataFile = DataFile.create(subFile, fileType);
            if (dataFile != null) {
              entries.put(name, dataFile);
            }
          }
        }
      }
    }
    this.entries = entries.build();
    return true;
  }

  private static String removeSuffix(String name, String suffix) {
    if (!name.endsWith(suffix)) {
      throw new IllegalArgumentException(name + " does not end with " + suffix);
    }
    return name.substring(0, name.length() - suffix.length());
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

  private static class DataFile implements Codes.TypedValue {
    final File file;
    final FileType fileType;
    final Type.Key typeKey;
    final PairList<Integer, Function<String, Object>> parsers;

    DataFile(File file, FileType fileType, Type.Key typeKey,
        PairList<Integer, Function<String, Object>> parsers) {
      this.file = requireNonNull(file, "file");
      this.fileType = requireNonNull(fileType, "fileType");
      this.typeKey = requireNonNull(typeKey, "typeKey");
      this.parsers = parsers.immutable();
    }

    static DataFile create(File subFile, FileType fileType) {
      try (BufferedReader r = fileType.open(subFile)) {
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

        return new DataFile(subFile, fileType,
            Keys.list(Keys.record(sortedNameTypes)), fieldParsers);
      } catch (IOException e) {
        // ignore, and skip file
        return null;
      }
    }

    static Function<String, Object> parser(Type.Key type) {
      switch (type.op) {
      case DATA_TYPE:
        switch (type.toString()) {
        case "int":
          return s -> s.equals("NULL") ? 0 : Integer.parseInt(s);
        case "real":
          return s -> s.equals("NULL") ? 0f : Float.parseFloat(s);
        case "string":
          return DataFile::unquoteString;
        default:
          throw new IllegalArgumentException("unknown type " + type);
        }
      default:
        throw new IllegalArgumentException("unknown type " + type);
      }
    }

    /** Converts "abc" to "abc" and "'abc, def'" to "abc, def". */
    private static Object unquoteString(String s) {
      if (s.startsWith("'")) {
        return s.substring(1, s.length() - 1);
      }
      return s;
    }

    @Override public <V> V valueAs(Class<V> clazz) {
      Object[] values = new Object[parsers.size()];
      try (BufferedReader r = fileType.open(file)) {
        String firstLine = r.readLine();
        if (firstLine == null) {
          return null;
        }
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
      }
      return null;
    }

    @Override public Type.Key typeKey() {
      return typeKey;
    }
  }

  enum FileType {
    CSV(".csv"),
    CSV_GZ(".csv.gz");

    final String suffix;

    FileType(String suffix) {
      this.suffix = suffix;
    }

    BufferedReader open(File file) throws IOException {
      switch (this) {
      case CSV:
        return Util.reader(file);
      case CSV_GZ:
        return Util.reader(
            new GZIPInputStream(Files.newInputStream(file.toPath())));
      default:
        throw new IllegalArgumentException("cannot open " + file);
      }
    }

    PairList<String, Type.Key> deduceFields(BufferedReader r)
        throws IOException {
      return deduceFieldsCsv(r);
    }
  }
}

// End Directory.java
