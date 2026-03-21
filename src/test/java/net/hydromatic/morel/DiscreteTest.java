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

import java.util.Optional;
import net.hydromatic.morel.eval.Discrete;
import net.hydromatic.morel.eval.Discretes;
import net.hydromatic.morel.eval.Unit;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.TypeSystem;
import org.junit.jupiter.api.Test;

/** Unit test for {@link Discrete} and {@link Discretes}. */
class DiscreteTest {
  private final TypeSystem typeSystem = new TypeSystem();

  @Test
  void testIntDiscrete() {
    Discrete d = Discretes.discreteFor(typeSystem, PrimitiveType.INT);
    assertThat(d.minValue(), is(Optional.empty()));
    assertThat(d.next(0), is(Optional.of(1)));
    assertThat(d.next(3), is(Optional.of(4)));
    assertThat(d.next(-1), is(Optional.of(0)));
    assertThat(d.comparator().compare(1, 2) < 0, is(true));
    assertThat(d.comparator().compare(2, 2), is(0));
    assertThat(d.comparator().compare(3, 2) > 0, is(true));
  }

  @Test
  void testCharDiscrete() {
    Discrete d = Discretes.discreteFor(typeSystem, PrimitiveType.CHAR);
    assertThat(d.minValue(), is(Optional.of('\u0000')));
    assertThat(d.next('\u0000'), is(Optional.of('\u0001')));
    assertThat(d.next('a'), is(Optional.of('b')));
    assertThat(d.next('z'), is(Optional.of('{')));
    assertThat(d.next('\u00ff'), is(Optional.empty()));
    assertThat(d.comparator().compare('a', 'b') < 0, is(true));
    assertThat(d.comparator().compare('b', 'b'), is(0));
    assertThat(d.comparator().compare('c', 'b') > 0, is(true));
  }

  @Test
  void testBoolDiscrete() {
    Discrete d = Discretes.discreteFor(typeSystem, PrimitiveType.BOOL);
    assertThat(d.minValue(), is(Optional.of(Boolean.FALSE)));
    assertThat(d.next(Boolean.FALSE), is(Optional.of(Boolean.TRUE)));
    assertThat(d.next(Boolean.TRUE), is(Optional.empty()));
    assertThat(d.comparator().compare(false, true) < 0, is(true));
    assertThat(d.comparator().compare(true, true), is(0));
    assertThat(d.comparator().compare(true, false) > 0, is(true));
  }

  @Test
  void testUnitDiscrete() {
    Discrete d = Discretes.discreteFor(typeSystem, PrimitiveType.UNIT);
    assertThat(d.minValue(), is(Optional.of(Unit.INSTANCE)));
    assertThat(d.next(Unit.INSTANCE), is(Optional.empty()));
    assertThat(d.comparator().compare(Unit.INSTANCE, Unit.INSTANCE), is(0));
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
