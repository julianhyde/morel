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

import static net.hydromatic.morel.ast.CoreBuilder.core;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import java.math.BigDecimal;
import net.hydromatic.morel.ast.AstWriter;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.TypeSystem;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link AstWriter}, and in particular how it lays text out to fit a
 * width.
 */
public class AstWriterTest {
  /** A writer with a width, as a plan's writer has. */
  private static AstWriter writer(int width) {
    return new AstWriter() {
      @Override
      protected int width() {
        return width;
      }
    };
  }

  /** Returns {@code (x > 1 andalso y > 2) andalso z > 3}. */
  private static Core.Exp conjunction() {
    final TypeSystem typeSystem = new TypeSystem();
    final Core.Exp[] conjuncts = new Core.Exp[3];
    final String[] names = {"x", "y", "z"};
    for (int i = 0; i < 3; i++) {
      final Core.Id id = core.id(core.idPat(PrimitiveType.INT, names[i], 0));
      conjuncts[i] =
          core.greaterThan(
              typeSystem, id, core.intLiteral(BigDecimal.valueOf(i + 1)));
    }
    return core.andAlso(
        typeSystem,
        core.andAlso(typeSystem, conjuncts[0], conjuncts[1]),
        conjuncts[2]);
  }

  /**
   * A writer with no width prints on one line, and so does one whose width the
   * text fits.
   */
  @Test
  void testFits() {
    final Core.Exp exp = conjunction();
    final String flat = "x > 1 andalso y > 2 andalso z > 3";
    assertThat(exp.unparse(new AstWriter()), is(flat));
    assertThat(exp.unparse(writer(flat.length())), is(flat));
  }

  /**
   * An {@code andalso} chain that does not fit breaks before each {@code
   * andalso}, which leads its line; the outer group breaks before the inner
   * one, so what fits on a line stays on it.
   */
  @Test
  void testBreaks() {
    final Core.Exp exp = conjunction();
    assertThat(
        exp.unparse(writer(20)),
        is(
            "x > 1 andalso y > 2\n" //
                + "andalso z > 3"));
    assertThat(
        exp.unparse(writer(10)),
        is(
            "x > 1\n" //
                + "andalso y > 2\n"
                + "andalso z > 3"));
  }
}

// End AstWriterTest.java
