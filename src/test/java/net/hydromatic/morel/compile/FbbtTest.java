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
package net.hydromatic.morel.compile;

import static net.hydromatic.morel.ast.CoreBuilder.core;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasToString;

import com.google.common.collect.ImmutableSet;
import java.math.BigDecimal;
import java.util.ArrayList;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.TypeSystem;
import org.junit.jupiter.api.Test;

/** Tests for {@link Fbbt}. */
public class FbbtTest {
  private final TypeSystem typeSystem = new TypeSystem();

  {
    BuiltIn.dataTypes(typeSystem, new ArrayList<>());
  }

  private final Core.IdPat xPat = core.idPat(PrimitiveType.INT, "x", 0);
  private final Core.IdPat yPat = core.idPat(PrimitiveType.INT, "y", 0);
  private final Core.Id xId = core.id(xPat);
  private final Core.Id yId = core.id(yPat);

  private Core.Literal i(int n) {
    return core.intLiteral(BigDecimal.valueOf(n));
  }

  /**
   * Strengthens {@code whereExp} treating {@code pats} as the unbounded
   * patterns; asserts the result matches {@code expected}.
   */
  private void checkStrengthen(
      Core.Exp whereExp, ImmutableSet<Core.NamedPat> pats, String expected) {
    final Core.Exp result = Fbbt.strengthen(typeSystem, pats, whereExp);
    assertThat(result, hasToString(expected));
  }

  /**
   * {@code x > 0 andalso x < 10} stays as-is: each conjunct is already a bound,
   * so the strengthen pass adds equivalents (which the framework does emit,
   * today, because forEachTightened doesn't yet distinguish "this came from the
   * input" from "this was newly deduced"). For now we just verify FBBT
   * converges and produces a strengthened result.
   */
  @Test
  void testConstantBoundsConverge() {
    // x > 0 andalso x < 10
    final Core.Exp w =
        core.andAlso(
            typeSystem,
            core.greaterThan(typeSystem, xId, i(0)),
            core.lessThan(typeSystem, xId, i(10)));
    final Core.Exp result =
        Fbbt.strengthen(typeSystem, ImmutableSet.of(xPat), w);
    // The strengthen pass deduced x in (0, 10) and re-emitted both bounds;
    // the input survives as the head of an andAlso.
    assertThat(
        result,
        hasToString("x > 0 andalso (x < 10 andalso (x > 0 andalso x < 10))"));
  }

  /**
   * A constraint that references an unknown pattern (e.g. {@code y < 5}) leaves
   * {@code x}'s interval unchanged.
   */
  @Test
  void testOtherPatternUntouched() {
    final Core.Exp w = core.lessThan(typeSystem, yId, i(5));
    final Core.Exp result =
        Fbbt.strengthen(typeSystem, ImmutableSet.of(xPat), w);
    // No bound on x; whereExp unchanged.
    assertThat(result, equalTo(w));
  }

  /**
   * {@code from} with no integer patterns: framework returns input unchanged.
   */
  @Test
  void testNoIntPatsIsNoOp() {
    final Core.Exp w = core.boolLiteral(true);
    final Core.Exp result = Fbbt.strengthen(typeSystem, ImmutableSet.of(), w);
    assertThat(result, equalTo(w));
  }
}

// End FbbtTest.java
