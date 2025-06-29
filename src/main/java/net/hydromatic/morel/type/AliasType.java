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

import java.util.function.UnaryOperator;
import net.hydromatic.morel.ast.Op;

/**
 * Type that is an alias for another type.
 *
 * <p>For example, "{@code type point = real list real}" creates a type "point"
 * as an alias for "real list".
 */
public class AliasType extends ParameterizedType {
  public final Type type;

  public AliasType(String name, String moniker, Type type) {
    super(Op.ALIAS_TYPE, name, moniker, 0);
    this.type = type;
  }

  @Override
  public Key key() {
    return Keys.alias(name, type.key());
  }

  @Override
  public Type copy(TypeSystem typeSystem, UnaryOperator<Type> transform) {
    return typeSystem.aliasType(name, transform.apply(type));
  }

  @Override
  public Type arg(int i) {
    return type;
  }

  @Override
  public <R> R accept(TypeVisitor<R> typeVisitor) {
    return typeVisitor.visit(this);
  }
}

// End AliasType.java
