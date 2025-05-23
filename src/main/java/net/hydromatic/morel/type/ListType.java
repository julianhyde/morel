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

import static java.util.Objects.requireNonNull;

import java.util.function.UnaryOperator;
import net.hydromatic.morel.ast.Op;

/** The type of a list value. */
// TODO: remove this, and use datatype?
public class ListType extends BaseType {
  public final Type elementType;

  ListType(Type elementType) {
    super(Op.LIST);
    this.elementType = requireNonNull(elementType);
  }

  @Override
  public Key key() {
    return Keys.list(elementType.key());
  }

  @Override
  public Type arg(int i) {
    if (i != 0) {
      throw new IndexOutOfBoundsException();
    }
    return elementType;
  }

  @Override
  public boolean isCollection() {
    return true;
  }

  public <R> R accept(TypeVisitor<R> typeVisitor) {
    return typeVisitor.visit(this);
  }

  @Override
  public ListType copy(TypeSystem typeSystem, UnaryOperator<Type> transform) {
    final Type elementType2 = elementType.copy(typeSystem, transform);
    return elementType2 == elementType
        ? this
        : typeSystem.listType(elementType2);
  }

  @Override
  public boolean specializes(Type type) {
    if (type instanceof ListType) {
      final ListType listType = (ListType) type;
      return elementType.specializes(listType.elementType);
    }
    return type instanceof TypeVar;
  }
}

// End ListType.java
