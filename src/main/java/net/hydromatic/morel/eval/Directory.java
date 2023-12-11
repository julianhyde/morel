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
import net.hydromatic.morel.util.PairList;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Maps;
import org.apache.calcite.util.Util;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.util.SortedMap;
import java.util.TreeMap;

import static java.util.Objects.requireNonNull;

/** Directory in the file system.
 *
 * <p>Its type is progressive, so that it can discover new files and
 * subdirectories.
 */
public class Directory implements Codes.TypedValue {
  private final File file;
  private final SortedMap<String, Codes.TypedValue> entries =
      new TreeMap<>(RecordType.ORDERING);

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
      return clazz.cast(ImmutableList.copyOf(entries.values()));
    }
    throw new IllegalArgumentException("not a " + clazz);
  }

  @Override public Type.Key typeKey() {
    return Keys.record(Maps.transformValues(entries, Codes.TypedValue::typeKey));
  }

  @Override public boolean discoverField(TypeSystem typeSystem, String fieldName) {
    if (!file.isDirectory()) {
      return false;
    }

    entries.clear();
    for (File subFile : Util.first(file.listFiles(), new File[0])) {
      if (subFile.isDirectory()) {
        entries.put(subFile.getName(), new Directory(subFile));
      } else if (subFile.isFile()) {
        Type.Key typeKey;
        if (subFile.getName().endsWith(".csv")) {
          try (BufferedReader r = Util.reader(subFile)) {
            String firstLine = r.readLine();
            if (firstLine == null) {
              typeKey = PrimitiveType.UNIT.key();
            } else {
              PairList<String, Type.Key> nameTypes = PairList.of();
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
              typeKey =
                  Keys.list(
                      Keys.record(
                          ImmutableSortedMap
                              .<String, Type.Key>orderedBy(RecordType.ORDERING)
                              .putAll(nameTypes)
                              .build()));
            }
            entries.put(subFile.getName(), new DataFile(subFile, typeKey));
          } catch (IOException e) {
            // ignore, and skip file
          }
        }
      }
    }
    return true;
  }

  private static class DataFile implements Codes.TypedValue {
    private final Type.Key typeKey;

    DataFile(File file, Type.Key typeKey) {
      this.typeKey = typeKey;
    }

    @Override public <V> V valueAs(Class<V> clazz) {
      return null; // TODO parse file into records
    }

    @Override public Type.Key typeKey() {
      return typeKey;
    }
  }
}

// End Directory.java
