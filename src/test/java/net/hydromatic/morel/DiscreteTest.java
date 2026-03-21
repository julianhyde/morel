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
package net.hydromatic.morel;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.eval.Discrete;
import net.hydromatic.morel.eval.Discretes;
import net.hydromatic.morel.eval.Unit;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.DataType;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.RecordLikeType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import org.junit.jupiter.api.Test;

/** Unit test for {@link Discrete} and {@link Discretes}. */
class DiscreteTest {
  private final TypeSystem typeSystem = new TypeSystem();

  /**
   * Populates {@code typeSystem} with built-in types (needed for DataType
   * tests).
   */
  private void initBuiltIns() {
    final List<Binding> bindings = new ArrayList<>();
    BuiltIn.dataTypes(typeSystem, bindings);
  }

  @Test
  void testIntDiscrete() {
    Discrete d = Discretes.discreteFor(typeSystem, PrimitiveType.INT);
    assertThat(d.minValue(), is(Optional.empty()));
    assertThat(d.maxValue(), is(Optional.empty()));
    assertThat(d.next(0), is(Optional.of(1)));
    assertThat(d.next(3), is(Optional.of(4)));
    assertThat(d.next(-1), is(Optional.of(0)));
    assertThat(d.prev(0), is(Optional.of(-1)));
    assertThat(d.prev(3), is(Optional.of(2)));
    assertThat(d.prev(-1), is(Optional.of(-2)));
    assertThat(d.comparator().compare(1, 2) < 0, is(true));
    assertThat(d.comparator().compare(2, 2), is(0));
    assertThat(d.comparator().compare(3, 2) > 0, is(true));
  }

  @Test
  void testCharDiscrete() {
    Discrete d = Discretes.discreteFor(typeSystem, PrimitiveType.CHAR);
    assertThat(d.minValue(), is(Optional.of('\u0000')));
    assertThat(d.maxValue(), is(Optional.of('\u00ff')));
    assertThat(d.next('\u0000'), is(Optional.of('\u0001')));
    assertThat(d.next('a'), is(Optional.of('b')));
    assertThat(d.next('z'), is(Optional.of('{')));
    assertThat(d.next('\u00ff'), is(Optional.empty()));
    assertThat(d.prev('\u0000'), is(Optional.empty()));
    assertThat(d.prev('b'), is(Optional.of('a')));
    assertThat(d.prev('{'), is(Optional.of('z')));
    assertThat(d.comparator().compare('a', 'b') < 0, is(true));
    assertThat(d.comparator().compare('b', 'b'), is(0));
    assertThat(d.comparator().compare('c', 'b') > 0, is(true));
  }

  @Test
  void testBoolDiscrete() {
    Discrete d = Discretes.discreteFor(typeSystem, PrimitiveType.BOOL);
    assertThat(d.minValue(), is(Optional.of(Boolean.FALSE)));
    assertThat(d.maxValue(), is(Optional.of(Boolean.TRUE)));
    assertThat(d.next(Boolean.FALSE), is(Optional.of(Boolean.TRUE)));
    assertThat(d.next(Boolean.TRUE), is(Optional.empty()));
    assertThat(d.prev(Boolean.FALSE), is(Optional.empty()));
    assertThat(d.prev(Boolean.TRUE), is(Optional.of(Boolean.FALSE)));
    assertThat(d.comparator().compare(false, true) < 0, is(true));
    assertThat(d.comparator().compare(true, true), is(0));
    assertThat(d.comparator().compare(true, false) > 0, is(true));
  }

  @Test
  void testUnitDiscrete() {
    Discrete d = Discretes.discreteFor(typeSystem, PrimitiveType.UNIT);
    assertThat(d.minValue(), is(Optional.of(Unit.INSTANCE)));
    assertThat(d.maxValue(), is(Optional.of(Unit.INSTANCE)));
    assertThat(d.next(Unit.INSTANCE), is(Optional.empty()));
    assertThat(d.prev(Unit.INSTANCE), is(Optional.empty()));
    assertThat(d.comparator().compare(Unit.INSTANCE, Unit.INSTANCE), is(0));
  }

  @Test
  void testTupleDiscrete() {
    // bool * int: lexicographic order, rightmost increments first
    final RecordLikeType boolIntType =
        typeSystem.tupleType(PrimitiveType.BOOL, PrimitiveType.INT);
    final Discrete d = Discretes.discreteFor(typeSystem, boolIntType);

    // min/max: bool has bounds, int does not
    assertThat(d.minValue(), is(Optional.empty()));
    assertThat(d.maxValue(), is(Optional.empty()));

    // next: increment rightmost (int) component first
    final List<Object> falseZero = ImmutableList.of(false, 0);
    final List<Object> falseOne = ImmutableList.of(false, 1);
    assertThat(d.next(falseZero), is(Optional.of(falseOne)));

    // prev: decrement rightmost component
    assertThat(d.prev(falseOne), is(Optional.of(falseZero)));

    // comparator: false < true, then int order
    final List<Object> trueZero = ImmutableList.of(true, 0);
    assertThat(d.comparator().compare(falseZero, trueZero) < 0, is(true));
    assertThat(d.comparator().compare(falseZero, falseOne) < 0, is(true));
    assertThat(d.comparator().compare(falseZero, falseZero), is(0));
  }

  @Test
  void testBoolTupleDiscrete() {
    // bool * bool: fully bounded, can enumerate completely
    final RecordLikeType boolBoolType =
        typeSystem.tupleType(PrimitiveType.BOOL, PrimitiveType.BOOL);
    final Discrete d = Discretes.discreteFor(typeSystem, boolBoolType);

    final List<Object> ff = ImmutableList.of(false, false);
    final List<Object> ft = ImmutableList.of(false, true);
    final List<Object> tf = ImmutableList.of(true, false);
    final List<Object> tt = ImmutableList.of(true, true);

    assertThat(d.minValue(), is(Optional.of(ff)));
    assertThat(d.maxValue(), is(Optional.of(tt)));
    assertThat(d.next(ff), is(Optional.of(ft)));
    assertThat(d.next(ft), is(Optional.of(tf)));
    assertThat(d.next(tf), is(Optional.of(tt)));
    assertThat(d.next(tt), is(Optional.empty()));
    assertThat(d.prev(ff), is(Optional.empty()));
    assertThat(d.prev(ft), is(Optional.of(ff)));
    assertThat(d.prev(tf), is(Optional.of(ft)));
    assertThat(d.prev(tt), is(Optional.of(tf)));
  }

  @Test
  void testDescendingDiscrete() {
    initBuiltIns();
    final Type descendingScheme = typeSystem.descending();
    final DataType descendingInt =
        (DataType) typeSystem.apply(descendingScheme, PrimitiveType.INT);
    final Discrete d = Discretes.discreteFor(typeSystem, descendingInt);

    // In descending order, min is the largest int (no bound), max is smallest
    // (no bound).
    assertThat(d.minValue(), is(Optional.empty()));
    assertThat(d.maxValue(), is(Optional.empty()));

    // Runtime values: ["DESC", innerValue]
    final List<Object> desc5 = ImmutableList.of("DESC", 5);
    final List<Object> desc4 = ImmutableList.of("DESC", 4);
    final List<Object> desc6 = ImmutableList.of("DESC", 6);

    // next in descending order = prev in ascending order (i.e. 5 → 4)
    assertThat(d.next(desc5), is(Optional.of(desc4)));
    // prev in descending order = next in ascending order (i.e. 5 → 6)
    assertThat(d.prev(desc5), is(Optional.of(desc6)));

    // comparator: 5 DESC > 3 DESC (larger number comes first)
    assertThat(d.comparator().compare(desc5, desc4) < 0, is(true));
    assertThat(d.comparator().compare(desc5, desc5), is(0));
    assertThat(d.comparator().compare(desc4, desc5) > 0, is(true));
  }

  @Test
  void testEnumDiscrete() {
    initBuiltIns();
    final Type orderType = typeSystem.order();
    final Discrete d = Discretes.discreteFor(typeSystem, (DataType) orderType);

    // order has 3 constructors: LESS, EQUAL, GREATER
    final List<Object> less = ImmutableList.of("LESS");
    final List<Object> equal = ImmutableList.of("EQUAL");
    final List<Object> greater = ImmutableList.of("GREATER");

    assertThat(d.minValue(), is(Optional.of(less)));
    assertThat(d.maxValue(), is(Optional.of(greater)));
    assertThat(d.next(less), is(Optional.of(equal)));
    assertThat(d.next(equal), is(Optional.of(greater)));
    assertThat(d.next(greater), is(Optional.empty()));
    assertThat(d.prev(less), is(Optional.empty()));
    assertThat(d.prev(equal), is(Optional.of(less)));
    assertThat(d.prev(greater), is(Optional.of(equal)));
    assertThat(d.comparator().compare(less, equal) < 0, is(true));
    assertThat(d.comparator().compare(equal, equal), is(0));
    assertThat(d.comparator().compare(greater, equal) > 0, is(true));
  }

  @Test
  void testRealNotDiscrete() {
    assertThrows(
        IllegalArgumentException.class,
        () -> Discretes.discreteFor(typeSystem, PrimitiveType.REAL));
  }

  @Test
  void testStringNotDiscrete() {
    assertThrows(
        IllegalArgumentException.class,
        () -> Discretes.discreteFor(typeSystem, PrimitiveType.STRING));
  }
}

// End DiscreteTest.java
