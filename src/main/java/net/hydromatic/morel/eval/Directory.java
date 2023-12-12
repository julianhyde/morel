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
import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;

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
        String name;
        if (subFile.getName().endsWith(".csv")) {
          name = removeSuffix(subFile.getName(), ".csv");
          final DataFile dataFile = DataFile.create(subFile);
          if (dataFile != null) {
            entries.put(name, dataFile);
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

  private static class DataFile implements Codes.TypedValue {
    private final File file;
    private final ImmutablePairList<String, Type.Key> nameTypes;

    DataFile(File file, PairList<String, Type.Key> nameTypes) {
      this.file = file;
      this.nameTypes = nameTypes.immutable();
    }

    static DataFile create(File subFile) {
      try (BufferedReader r = Util.reader(subFile)) {
        String firstLine = r.readLine();
        PairList<String, Type.Key> nameTypes = PairList.of();
        if (firstLine == null) {
          // File is empty. There will be no fields, and row type will be unit.
        } else {
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
        }
        return new DataFile(subFile, nameTypes);
      } catch (IOException e) {
        // ignore, and skip file
        return null;
      }
    }

    @Override public <V> V valueAs(Class<V> clazz) {
      Object[] values = new Object[nameTypes.size()];
      List<String> sortedFieldNames =
          RecordType.ORDERING.immutableSortedCopy(nameTypes.leftList());
      try (BufferedReader r = Util.reader(file)) {
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
          for (int i = 0; i < values.length; i++) {
            final int j = nameTypes.leftList().indexOf(sortedFieldNames.get(i));
            String field = fields[j];
            Type.Key typeKey = nameTypes.rightList().get(j);
            values[i] = Integer.parseInt(field);
          }
          list.add(ImmutableList.copyOf(values));
        }
      } catch (IOException e) {
        // ignore
      }
      return null;
    }

    @Override public Type.Key typeKey() {
      return Keys.list(
          Keys.record(
              ImmutableSortedMap
                  .<String, Type.Key>orderedBy(RecordType.ORDERING)
                  .putAll(nameTypes)
                  .build()));
    }
  }
}

// End Directory.java
