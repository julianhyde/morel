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

import com.google.common.collect.ImmutableSortedMap;

import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.function.UnaryOperator;

/** Algebraic type. */
public class PolymorphicDataType extends DataType {
  /** Creates a PolymorphicDataType.
   *
   * <p>Called only from {@link TypeSystem#dataType(String, List, Map)}.
   * If the {@code typeSystem} argument is specified, canonizes the types inside
   * type-constructors. This also allows temporary types (necessary while
   * creating self-referential data types) to be replaced with real DataType
   * instances. */
  PolymorphicDataType(TypeSystem typeSystem, String name, String description,
      List<TypeVar> typeVars, SortedMap<String, Type> typeConstructors) {
    super(typeSystem, name, description, typeVars, typeConstructors);
  }

  @Override public PolymorphicDataType copy(TypeSystem typeSystem,
      UnaryOperator<Type> transform) {
    final ImmutableSortedMap<String, Type> typeConstructors =
        copyTypeConstructors(typeSystem, this.typeConstructors, transform);
    return typeConstructors.equals(this.typeConstructors)
        ? this
        : new PolymorphicDataType(typeSystem, name, description, getTypeVars(),
            typeConstructors);
  }

  @SuppressWarnings("unchecked")
  public List<TypeVar> getTypeVars() {
    return (List<TypeVar>) parameterTypes;
  }
}

// End PolymorphicDataType.java
