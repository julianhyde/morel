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
package net.hydromatic.morel.ast;

import static java.lang.String.format;
import static net.hydromatic.morel.ast.CoreBuilder.core;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SortedMap;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;

/**
 * Builds {@link Rel} nodes, deriving each node's element type and kind ({@code
 * list} or {@code bag}) from its inputs and expressions.
 *
 * <p>The derivations are the normative ones in {@code spec.md} sections 3 and
 * 4, and they are the only place that knows them: a node stores the type it is
 * given, so that a node cannot be built with a type that disagrees with the
 * spec.
 */
public enum RelBuilder {
  /**
   * The singleton instance of the REL builder. The short name is convenient for
   * use via 'import static', but checkstyle does not approve.
   */
  // CHECKSTYLE: IGNORE 1
  rel;

  /** Name that an expression uses for the element of a node's input. */
  public static final String INPUT_0 = "$0";

  /** Name that a join's expressions use for the element of its right input. */
  public static final String INPUT_1 = "$1";

  /** Creates a reference to the element of a node's input, {@code $0}. */
  public Core.Id input0(Type elementType) {
    return input(elementType, 0);
  }

  /** Creates a reference to the element of a join's right input, {@code $1}. */
  public Core.Id input1(Type elementType) {
    return input(elementType, 1);
  }

  /** Creates a reference to the element of input {@code i}. */
  public Core.Id input(Type elementType, int i) {
    return core.id(core.idPat(elementType, "$" + i, 0));
  }

  /**
   * Creates a collection type: a {@code list} if {@code ordered}, otherwise a
   * {@code bag}.
   */
  public Type collectionType(
      TypeSystem typeSystem, boolean ordered, Type elementType) {
    return ordered
        ? typeSystem.listType(elementType)
        : typeSystem.bagType(elementType);
  }

  /** Creates a leaf, whose expression must have a collection type. */
  public Rel.Scan scan(Core.Exp exp) {
    if (!exp.type.isCollection()) {
      throw new IllegalArgumentException(
          "scan expression must be list or bag: " + exp.type);
    }
    return new Rel.Scan(exp);
  }

  /**
   * Creates a filter; its condition is a {@code bool} expression over {@code
   * $0}.
   */
  public Rel.Filter filter(Rel input, Core.Exp condition) {
    checkBool(condition, "filter condition");
    return new Rel.Filter(input, condition);
  }

  /**
   * Creates a projection; the element type is the type of {@code exp}, an
   * expression over {@code $0}, and the kind is that of the input.
   */
  public Rel.Project project(TypeSystem typeSystem, Rel input, Core.Exp exp) {
    final Type type = collectionType(typeSystem, input.isOrdered(), exp.type);
    return new Rel.Project(type, input, exp);
  }

  /**
   * Creates a {@code projectMany}; the element type is that of the body, and
   * the output is ordered only if both the input and the body are ordered,
   * because it is a nested loop.
   */
  public Rel.ProjectMany projectMany(
      TypeSystem typeSystem, Rel input, Core.IdPat param, Rel body) {
    final boolean ordered = input.isOrdered() && body.isOrdered();
    final Type type = collectionType(typeSystem, ordered, body.elementType());
    return new Rel.ProjectMany(type, input, param, body);
  }

  /** Creates an inner join. */
  public Rel.Join join(
      TypeSystem typeSystem,
      Rel left,
      Rel right,
      Core.Exp condition,
      Core.Exp yieldExp) {
    return join(
        typeSystem, Rel.JoinType.INNER, left, right, condition, yieldExp);
  }

  /**
   * Creates a join; the element type is the type of the yield expression, over
   * {@code $0} and {@code $1}, and the output is ordered only if both inputs
   * are ordered.
   */
  public Rel.Join join(
      TypeSystem typeSystem,
      Rel.JoinType joinType,
      Rel left,
      Rel right,
      Core.Exp condition,
      Core.Exp yieldExp) {
    checkBool(condition, "join condition");
    final boolean ordered = left.isOrdered() && right.isOrdered();
    final Type type = collectionType(typeSystem, ordered, yieldExp.type);
    return new Rel.Join(type, joinType, left, right, condition, yieldExp);
  }

  /**
   * Creates a {@code group}; the element type is a record of the keys and
   * aggregates, or, if there is exactly one of them, its bare type.
   */
  public Rel.Group group(
      TypeSystem typeSystem,
      Rel input,
      SortedMap<String, Core.Exp> keys,
      SortedMap<String, Core.Aggregate> aggregates) {
    final Map<String, Type> nameTypes = new LinkedHashMap<>();
    keys.forEach((name, exp) -> nameTypes.put(name, exp.type));
    aggregates.forEach((name, agg) -> nameTypes.put(name, agg.type));
    if (nameTypes.size() < keys.size() + aggregates.size()) {
      throw new IllegalArgumentException(
          format(
              "duplicate label in group keys %s and aggregates %s",
              keys.keySet(), aggregates.keySet()));
    }
    final Type elementType =
        typeSystem.recordOrScalarType(
            ImmutableSortedMap.copyOf(nameTypes, RecordType.ORDERING)
                .entrySet());
    final Type type =
        collectionType(typeSystem, input.isOrdered(), elementType);
    return new Rel.Group(
        type,
        input,
        ImmutableSortedMap.copyOf(keys, RecordType.ORDERING),
        ImmutableSortedMap.copyOf(aggregates, RecordType.ORDERING));
  }

  /** Creates an {@code order}; the output is always a {@code list}. */
  public Rel.Order order(TypeSystem typeSystem, Rel input, Core.Exp exp) {
    final Type type = typeSystem.listType(input.elementType());
    return new Rel.Order(type, input, exp);
  }

  /** Creates an {@code unorder}; the output is always a {@code bag}. */
  public Rel.Unorder unorder(TypeSystem typeSystem, Rel input) {
    final Type type = typeSystem.bagType(input.elementType());
    return new Rel.Unorder(type, input);
  }

  /**
   * Creates a {@code skip}; its count is evaluated before the first element
   * exists, and therefore cannot mention {@code $0}.
   */
  public Rel.Skip skip(Rel input, Core.Exp count) {
    return new Rel.Skip(input, count);
  }

  /**
   * Creates a {@code take}; its count is evaluated before the first element
   * exists, and therefore cannot mention {@code $0}.
   */
  public Rel.Take take(Rel input, Core.Exp count) {
    return new Rel.Take(input, count);
  }

  /**
   * Creates a set operator; all inputs must have the same element type, and the
   * output is ordered only if every input is ordered.
   */
  public Rel.SetRel setRel(
      TypeSystem typeSystem,
      Rel.SetOp setOp,
      boolean distinct,
      Iterable<? extends Rel> inputs) {
    final ImmutableList<Rel> inputList = ImmutableList.copyOf(inputs);
    final Type elementType = inputList.get(0).elementType();
    boolean ordered = true;
    for (Rel input : inputList) {
      if (!input.elementType().equals(elementType)) {
        throw new IllegalArgumentException(
            format(
                "set operator inputs have different element types: %s, %s",
                elementType, input.elementType()));
      }
      ordered = ordered && input.isOrdered();
    }
    final Type type = collectionType(typeSystem, ordered, elementType);
    return new Rel.SetRel(type, setOp, distinct, inputList);
  }

  private static void checkBool(Core.Exp exp, String what) {
    if (exp.type != PrimitiveType.BOOL) {
      throw new IllegalArgumentException(
          format("%s must be bool: %s", what, exp.type));
    }
  }
}

// End RelBuilder.java
