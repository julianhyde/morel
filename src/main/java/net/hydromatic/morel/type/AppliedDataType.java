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

import org.apache.calcite.util.Util;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;

import net.hydromatic.morel.ast.Op;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.function.UnaryOperator;

import static com.google.common.base.Preconditions.checkArgument;

/** Algebraic type applied to type arguments.
 *
 * <p>For example, {@code int option} is an applied data type, obtained by
 * calling the {@code option} type scheme with the type parameter {@code int}.
 *
 * <p>The algebraic data type {@code order} has no type parameters,
 * and therefore it is an instance of {@link DataType}.
 */
public class AppliedDataType extends DataType {
  final List<? extends Type> argumentTypes;

  /** Creates an AppliedDataType. */
  AppliedDataType(String name, List<? extends Type> parameterTypes,
      List<? extends Type> argumentTypes,
      SortedMap<String, Key> typeConstructors) {
    super(Op.DATA_TYPE, name, name, parameterTypes.size(), argumentTypes, typeConstructors);
    this.argumentTypes = ImmutableList.copyOf(argumentTypes);
  }

  @Override public AppliedDataType copy(TypeSystem typeSystem,
      UnaryOperator<Type> transform) {
    final List<Type> parameterTypes =
        Util.transform(this.parameterTypes, transform);
    if (parameterTypes.equals(this.parameterTypes)) {
      return this;
    }
    return new AppliedDataType(name, parameterTypes, parameterTypes,
        typeConstructors);
  }

  @Override public Type substitute(TypeSystem typeSystem,
      List<? extends Type> types, TypeSystem.Transaction transaction) {
    // Create a copy of this datatype with type variables substituted with
    // actual types.
    assert types.size() == parameterTypes.size();
    assert types.size() == argumentTypes.size();
    if (types.equals(argumentTypes)) {
      return this;
    }
    final String moniker = computeMoniker(name, types);
    final Type lookup = typeSystem.lookupOpt(moniker); //TODO remove
    if (lookup != null) {
      return lookup;
    }
    return substitute3(typeSystem, types, transaction);
  }

  Type substitute3(TypeSystem typeSystem,
      List<? extends Type> types, TypeSystem.Transaction transaction) {
    Keys.DataTypeDef def =
        Keys.dataTypeDef(name, ImmutableList.of(), Keys.toKeys(types),
            Maps.transformValues(typeConstructors,
                t -> t.substitute(types)), true);
    return def.toType(typeSystem);
  }
}

// End AppliedDataType.java
