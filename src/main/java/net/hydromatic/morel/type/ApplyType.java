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

import net.hydromatic.morel.ast.Op;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import java.util.List;
import java.util.function.UnaryOperator;

import static com.google.common.base.Preconditions.*;
import static java.util.Objects.requireNonNull;
import static net.hydromatic.morel.util.Static.transform;

/** Type that is a polymorphic type applied to a set of types. */
public class ApplyType extends BaseType {
  public final ParameterizedType type;
  public final ImmutableList<Type> args;

  protected ApplyType(ParameterizedType type, List<Type> args) {
    super(Op.APPLY_TYPE);
    this.type = requireNonNull(type);
    this.args = ImmutableList.copyOf(args);
    checkArgument(!(type instanceof DataType));
  }

  public Key key() {
    return Keys.apply(type, Keys.toKeys(args));
  }

  public <R> R accept(TypeVisitor<R> typeVisitor) {
    return typeVisitor.visit(this);
  }

  @Override public Type copy(TypeSystem typeSystem,
      UnaryOperator<Type> transform) {
    final Type type2 = type.copy(typeSystem, transform);
    final ImmutableList<Type> types2 =
        transform(args, t -> t.copy(typeSystem, transform));
    return type == type2 && args.equals(types2) ? this
        : typeSystem.apply(type2, types2);
  }
}

// End ApplyType.java
