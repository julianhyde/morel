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
package net.hydromatic.morel.type;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.SortedMap;

/** A type that has named fields, as a record type does. */
public interface RecordLikeType extends Type {
  SortedMap<String, Type> argNameTypes();

  /** Returns the type of the {@code i}th field, or throws. */
  Type argType(int i);

  /** Returns whether this type is progressive.
   *
   * <p>Progressive types are records, but can have additional fields each time
   * you look.
   *
   * <p>The "file" value is an example. */
  default boolean isProgressive() {
    return false;
  }

  /** Returns a type similar to this but with a field of the given name,
   * or null.
   *
   * <p>May return this type. If type is progressive, may return a new type with
   * the required field and all the current fields (and perhaps more fields). */
  default @Nullable RecordLikeType discoverField(TypeSystem typeSystem,
      String fieldName) {
    return argNameTypes().containsKey(fieldName) ? this : null;
  }
}

// End RecordLikeType.java
