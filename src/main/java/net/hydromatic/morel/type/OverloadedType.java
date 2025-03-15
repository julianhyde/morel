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

import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.function.UnaryOperator;
import net.hydromatic.morel.ast.Op;

/** Overloaded type. */
public class OverloadedType extends BaseType {
  public final List<Type> types;

  OverloadedType(List<Type> types) {
    super(Op.OVERLOADED_TYPE);
    this.types = ImmutableList.copyOf(types);
  }

  @Override
  public <R> R accept(TypeVisitor<R> typeVisitor) {
    return typeVisitor.visit(this);
  }

  @Override
  public Key key() {
    return Keys.overloaded(Keys.toKeys(types));
  }

  @Override
  public OverloadedType copy(
      TypeSystem typeSystem, UnaryOperator<Type> transform) {
    throw new UnsupportedOperationException();
  }
}

// End OverloadedType.java
