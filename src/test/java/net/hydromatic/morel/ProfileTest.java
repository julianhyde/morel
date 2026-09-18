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
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import com.google.common.collect.ImmutableList;
import java.math.BigDecimal;
import java.util.ArrayList;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.RelBuilder;
import net.hydromatic.morel.ast.Simplification;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.compile.Profile;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.TypeSystem;
import org.junit.jupiter.api.Test;

/** Tests {@link Profile}, which says what an engine can be asked to run. */
public class ProfileTest {
  private static class Fixture {
    final TypeSystem typeSystem = new TypeSystem();

    {
      BuiltIn.dataTypes(typeSystem, new ArrayList<>());
    }

    final Core.Exp list =
        core.list(
            typeSystem,
            PrimitiveType.INT,
            ImmutableList.of(intLiteral(1), intLiteral(2)));

    Core.Exp intLiteral(int i) {
      return core.intLiteral(BigDecimal.valueOf(i));
    }

    /** A builder that simplifies nothing, so a node survives to be asked. */
    RelBuilder builder() {
      final RelBuilder b =
          RelBuilder.create(
              typeSystem, java.util.EnumSet.noneOf(Simplification.class));
      b.push(list);
      return b;
    }

    boolean permits(Core.Exp rel) {
      return Profile.CALCITE.permits((Core.Rel) rel);
    }
  }

  /** The nodes Calcite runs, and the ones it does not. */
  @Test
  void testCalciteProfile() {
    final Fixture f = new Fixture();

    // A filter it runs; one that binds an ordinal it does not, because SQL
    // has no row number a filter can read as Morel's does.
    final RelBuilder b = f.builder();
    b.filter(core.greaterThan(f.typeSystem, b.input(0), f.intLiteral(1)));
    assertThat(f.permits(b.build()), is(true));

    final RelBuilder b2 = f.builder();
    b2.filter(core.greaterThan(f.typeSystem, b2.ordinal(), f.intLiteral(1)));
    assertThat(f.permits(b2.build()), is(false));

    // A skip whose count is a literal it runs; SQL's OFFSET is not an
    // expression over the row, so one that computes it, it does not.
    final RelBuilder b3 = f.builder();
    b3.skip(f.intLiteral(1));
    assertThat(f.permits(b3.build()), is(true));

    final RelBuilder b4 = f.builder();
    b4.skip(
        core.call(
            f.typeSystem,
            BuiltIn.INT_OP_PLUS,
            f.intLiteral(1),
            f.intLiteral(2)));
    assertThat(f.permits(b4.build()), is(false));

    // 'ifEmpty' has no SQL spelling.
    final RelBuilder b5 = f.builder();
    b5.ifEmpty(f.intLiteral(0));
    assertThat(f.permits(b5.build()), is(false));
  }

  /** A boundary is run by the engine it names, and by no other. */
  @Test
  void testBoundaryNamesItsEngine() {
    final Fixture f = new Fixture();
    assertThat(f.permits(core.boundary("calcite", f.list)), is(true));
    assertThat(f.permits(core.boundary("spark", f.list)), is(false));
  }

  /** A join must be inner. */
  @Test
  void testJoinMustBeInner() {
    final Fixture f = new Fixture();
    final RelBuilder b = f.builder();
    b.push(f.list);
    b.pair();
    b.join(
        Core.Rel.JoinType.INNER,
        core.equal(f.typeSystem, b.input(0), b.input(1)));
    assertThat(f.permits(b.build()), is(true));

    final RelBuilder b2 = f.builder();
    b2.push(f.list);
    b2.pair();
    b2.join(
        Core.Rel.JoinType.LEFT,
        core.equal(f.typeSystem, b2.input(0), b2.input(1)));
    assertThat(f.permits(b2.build()), is(false));
  }
}

// End ProfileTest.java
