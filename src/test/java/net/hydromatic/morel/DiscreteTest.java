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

import static net.hydromatic.morel.Matchers.list;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.eval.Discrete;
import net.hydromatic.morel.eval.Discretes;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.DataType;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.RecordLikeType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link Discrete} and {@link Discretes}.
 *
 * <p>These tests cover the ends of each domain, which a Morel expression cannot
 * reach: {@code Range.flatten [AT_LEAST x]} raises {@code Size} rather than
 * counting up to the greatest value. What a Morel expression can reach -- the
 * successor function and the order, for each discrete type -- is covered by
 * {@code script/range.smli}, under "Discrete element types".
 */
@SuppressWarnings({"EqualsWithItself", "UnnecessaryUnicodeEscape"})
class DiscreteTest {
  private static final char CHR_255 = '\u00ff';
  private static final char CHR_0 = '\u0000';
  private static final char CHR_1 = '\u0001';

  private final TypeSystem typeSystem = new TypeSystem();

  /**
   * Populates {@code typeSystem} with built-in types (needed for DataType
   * tests).
   */
  private void initBuiltIns() {
    final List<Binding> bindings = new ArrayList<>();
    BuiltIn.dataTypes(typeSystem, bindings);
  }

  /** Returns the discrete domain of a type. */
  private Discrete<Object> discrete(Type type) {
    return Discretes.discreteFor(typeSystem, type, Pos.ZERO);
  }

  @Test
  void testIntDiscrete() {
    Discrete<Object> d = discrete(PrimitiveType.INT);
    assertThat(d.minValue(), is(Integer.MIN_VALUE));
    assertThat(d.maxValue(), is(Integer.MAX_VALUE));
    assertThat(d.next(0), is(1));
    assertThat(d.next(3), is(4));
    assertThat(d.next(-1), is(0));
    assertThat(d.prev(0), is(-1));
    assertThat(d.prev(3), is(2));
    assertThat(d.prev(-1), is(-2));
    assertThat(d.comparator().compare(1, 2) < 0, is(true));
    assertThat(d.comparator().compare(2, 2), is(0));
    assertThat(d.comparator().compare(3, 2) > 0, is(true));
    assertThat(d.size(), is(1L << 32));
    assertThat(d.ordinal(Integer.MIN_VALUE), is(0L));
    assertThat(d.ordinal(0), is(1L << 31));
    assertThat(d.ordinal(Integer.MAX_VALUE), is((1L << 32) - 1));
  }

  @Test
  void testCharDiscrete() {
    Discrete<Object> d = discrete(PrimitiveType.CHAR);
    assertThat(d.minValue(), is(CHR_0));
    assertThat(d.maxValue(), is(CHR_255));
    assertThat(d.next(CHR_0), is(CHR_1));
    assertThat(d.next('a'), is('b'));
    assertThat(d.next('z'), is('{'));
    assertThat(d.next(CHR_255), nullValue());
    assertThat(d.prev(CHR_0), nullValue());
    assertThat(d.prev('b'), is('a'));
    assertThat(d.prev('{'), is('z'));
    assertThat(d.comparator().compare('a', 'b') < 0, is(true));
    assertThat(d.comparator().compare('b', 'b'), is(0));
    assertThat(d.comparator().compare('c', 'b') > 0, is(true));
    assertThat(d.size(), is(256L));
    assertThat(d.ordinal(CHR_0), is(0L));
    assertThat(d.ordinal('a'), is(97L));
    assertThat(d.ordinal(CHR_255), is(255L));
  }

  @Test
  void testTupleDiscrete() {
    // bool * int: the ends of the product are the ends of each component.
    final RecordLikeType boolIntType =
        typeSystem.tupleType(PrimitiveType.BOOL, PrimitiveType.INT);
    final Discrete<Object> d = discrete(boolIntType);

    assertThat(d.minValue(), is(list(Boolean.FALSE, Integer.MIN_VALUE)));
    assertThat(d.maxValue(), is(list(Boolean.TRUE, Integer.MAX_VALUE)));

    // Carry: the rightmost component wraps into the one on its left.
    final List<Object> falseMax = ImmutableList.of(false, Integer.MAX_VALUE);
    final List<Object> trueMin = ImmutableList.of(true, Integer.MIN_VALUE);
    assertThat(d.next(falseMax), is(trueMin));
    assertThat(d.prev(trueMin), is(falseMax));
    assertThat(d.next(d.maxValue()), nullValue());
    assertThat(d.prev(d.minValue()), nullValue());

    // Positions run over the product, so counting from one value to another
    // costs no more than subtracting.
    assertThat(d.size(), is(2L * (1L << 32)));
    assertThat(d.ordinal(d.minValue()), is(0L));
    assertThat(d.ordinal(falseMax), is((1L << 32) - 1));
    assertThat(d.ordinal(trueMin), is(1L << 32));
    assertThat(d.ordinal(d.maxValue()), is(2L * (1L << 32) - 1));
  }

  /**
   * Tests a domain with more values than a {@code long} can number. Three ints
   * make 2^96 values, so its size and the positions in its upper reaches
   * saturate.
   */
  @Test
  void testTupleDiscreteSaturates() {
    final RecordLikeType intIntIntType =
        typeSystem.tupleType(
            PrimitiveType.INT, PrimitiveType.INT, PrimitiveType.INT);
    final Discrete<Object> d = discrete(intIntIntType);

    assertThat(d.size(), is(Long.MAX_VALUE));
    assertThat(d.ordinal(d.minValue()), is(0L));
    assertThat(d.ordinal(d.maxValue()), is(Long.MAX_VALUE));

    // The bottom of the domain is still numbered exactly; only the third
    // component has been counted, and it has not overflowed.
    final List<Object> low =
        ImmutableList.of(Integer.MIN_VALUE, Integer.MIN_VALUE, 0);
    assertThat(d.ordinal(low), is(1L << 31));
  }

  @Test
  void testDescendingDiscrete() {
    initBuiltIns();
    final Type descendingScheme = typeSystem.descending();
    final DataType descendingInt =
        (DataType) typeSystem.apply(descendingScheme, PrimitiveType.INT);
    final Discrete<Object> d = discrete(descendingInt);

    // In descending order, min is the largest int, max is smallest.
    assertThat(d.minValue(), is(list("DESC", Integer.MAX_VALUE)));
    assertThat(d.maxValue(), is(list("DESC", Integer.MIN_VALUE)));

    // Runtime values: ["DESC", innerValue]
    final List<Object> desc5 = ImmutableList.of("DESC", 5);
    final List<Object> desc4 = ImmutableList.of("DESC", 4);
    final List<Object> desc6 = ImmutableList.of("DESC", 6);

    // next in descending order = prev in ascending order (i.e. 5 → 4)
    assertThat(d.next(desc5), is(desc4));
    // prev in descending order = next in ascending order (i.e. 5 → 6)
    assertThat(d.prev(desc5), is(desc6));

    // comparator: 5 DESC > 3 DESC (larger number comes first)
    assertThat(d.comparator().compare(desc5, desc4) < 0, is(true));
    assertThat(d.comparator().compare(desc5, desc5), is(0));
    assertThat(d.comparator().compare(desc4, desc5) > 0, is(true));

    // Positions are counted from the far end, so they too are reversed.
    assertThat(d.size(), is(1L << 32));
    assertThat(d.ordinal(d.minValue()), is(0L));
    assertThat(d.ordinal(desc5), is((1L << 31) - 6));
    assertThat(d.ordinal(desc4), is((1L << 31) - 5));
    assertThat(d.ordinal(d.maxValue()), is((1L << 32) - 1));
  }
}

// End DiscreteTest.java
