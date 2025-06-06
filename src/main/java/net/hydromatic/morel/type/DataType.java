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
import static net.hydromatic.morel.util.Pair.allMatch;
import static net.hydromatic.morel.util.Static.transformEager;
import static net.hydromatic.morel.util.Static.transformValuesEager;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.compile.BuiltIn;

/** Algebraic type. */
public class DataType extends ParameterizedType {
  public final List<Type> arguments;
  public final Map<String, Key> typeConstructors;

  /**
   * Creates a DataType.
   *
   * <p>Called only from {@link TypeSystem#dataTypes(List)}.
   *
   * <p>If the {@code typeSystem} argument is specified, canonizes the types
   * inside type-constructors. This also allows temporary types (necessary while
   * creating self-referential data types) to be replaced with real DataType
   * instances.
   *
   * <p>During replacement, if a type matches {@code placeholderType} it is
   * replaced with {@code this}. This allows cyclic graphs to be copied.
   */
  protected DataType(
      String name,
      String moniker,
      ImmutableList<Type> arguments,
      ImmutableMap<String, Key> typeConstructors) {
    super(Op.DATA_TYPE, name, moniker, arguments.size());
    this.arguments = requireNonNull(arguments);
    this.typeConstructors = requireNonNull(typeConstructors);
  }

  @Override
  public Keys.DataTypeKey key() {
    return Keys.datatype(
        name, Keys.toKeys(arguments), ImmutableMap.copyOf(typeConstructors));
  }

  @Override
  public Type arg(int i) {
    return arguments.get(i);
  }

  @Override
  public boolean isCollection() {
    return name.equals(BuiltIn.Eqtype.BAG.mlName());
  }

  public <R> R accept(TypeVisitor<R> typeVisitor) {
    return typeVisitor.visit(this);
  }

  public Map<String, Type> typeConstructors(TypeSystem typeSystem) {
    return transformValuesEager(
        typeConstructors,
        k -> k.copy(t -> t.substitute(arguments)).toType(typeSystem));
  }

  @Override
  public DataType copy(TypeSystem typeSystem, UnaryOperator<Type> transform) {
    final List<Type> arguments = transformEager(this.arguments, transform);
    if (arguments.equals(this.arguments)) {
      return this;
    }
    return (DataType) key().substitute(arguments).toType(typeSystem);
  }

  /**
   * Writes out the definition of the datatype. For example,
   *
   * <pre>{@code
   * datatype ('a,'b) tree =
   *     Empty
   *   | Node of ('a,'b) tree * 'b * 'a * ('a,'b) tree
   * }</pre>
   */
  public StringBuilder describe(StringBuilder buf) {
    buf.append("datatype ").append(moniker).append(" = ");
    final int initialSize = buf.length();
    typeConstructors.forEach(
        (name, typeKey) -> {
          if (buf.length() > initialSize) {
            buf.append(" | ");
          }
          buf.append(name);
          if (typeKey.op != Op.DUMMY_TYPE) {
            buf.append(" of ");
            typeKey.describe(buf, 0, 0);
          }
        });
    return buf;
  }

  @Override
  public boolean specializes(Type type) {
    if (type instanceof DataType) {
      final DataType dataType = (DataType) type;
      return name.equals(dataType.name)
          && allMatch(arguments, dataType.arguments, Type::specializes);
    }
    return type instanceof TypeVar;
  }
}

// End DataType.java
