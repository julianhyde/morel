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
import static org.hamcrest.Matchers.hasToString;

import com.google.common.collect.ImmutableList;
import java.math.BigDecimal;
import java.util.List;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.FromBuilder;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.RecordLikeType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import org.junit.jupiter.api.Test;

/** Tests {@link FromBuilder}, which builds a query as a relational tree. */
public class FromBuilderTest {
  private static class Fixture {
    final TypeSystem typeSystem = new TypeSystem();
    final PrimitiveType intType = PrimitiveType.INT;
    final RecordLikeType pairType =
        typeSystem.tupleType(ImmutableList.of(intType, intType));
    /** A list of pairs, {@code [(1, 2), (2, 3)]}. */
    final Core.Exp pairs =
        core.list(
            typeSystem,
            pairType,
            ImmutableList.of(
                core.tuple(pairType, intLiteral(1), intLiteral(2)),
                core.tuple(pairType, intLiteral(2), intLiteral(3))));

    Core.Exp intLiteral(int i) {
      return core.intLiteral(BigDecimal.valueOf(i));
    }

    Core.IdPat idPat(String name, Type type) {
      return core.idPat(type, name, 0);
    }

    Core.Pat tuplePat(Core.IdPat... pats) {
      return core.tuplePat(typeSystem, ImmutableList.copyOf(pats));
    }
  }

  @Test
  void testScanWhereYield() {
    final Fixture f = new Fixture();
    final Core.IdPat p = f.idPat("p", f.intType);
    final Core.IdPat x = f.idPat("x", f.intType);
    final FromBuilder fb = core.fromBuilder(f.typeSystem);
    fb.scan(f.tuplePat(p, x), f.pairs)
        .where(core.equal(f.typeSystem, core.id(p), f.intLiteral(1)))
        .yield_(core.id(x));
    final Core.Exp exp = fb.build();
    assertThat(
        exp,
        hasToString(
            "project [#2 $0]\n"
                + "  filter [#1 $0 = 1]\n"
                + "    [(1, 2), (2, 3)]\n"));
    assertThat(exp.type, hasToString("int list"));
  }

  /**
   * Two scans that share a name: the second renames it and tests it against the
   * first, as the grounding engine's chain does.
   */
  @Test
  void testChainDistinct() {
    final Fixture f = new Fixture();
    final Core.IdPat p = f.idPat("p", f.intType);
    final Core.IdPat x = f.idPat("x", f.intType);
    final Core.IdPat p1 = f.idPat("p'1", f.intType);
    final Core.IdPat y = f.idPat("y", f.intType);
    final FromBuilder fb = core.fromBuilder(f.typeSystem);
    fb.scan(f.tuplePat(p, x), f.pairs)
        .scan(
            f.tuplePat(p1, y),
            f.pairs,
            core.equal(f.typeSystem, core.id(p1), core.id(p)))
        .where(core.notEqual(f.typeSystem, core.id(x), core.id(y)));
    final List<Core.IdPat> names = ImmutableList.of(x, y);
    final Core.Exp row = core.recordOrAtom(f.typeSystem, names);
    fb.yield_(row).distinct().order(row);
    final Core.Exp exp = fb.build();
    assertThat(
        exp,
        hasToString(
            "sort [$0]\n"
                + "  group [x = #x $0, y = #y $0]\n"
                + "    project [{x = #2 (#1 $0), y = #2 (#2 $0)}]\n"
                + "      filter [#2 (#1 $0) <> #2 (#2 $0)]\n"
                + "        join [#1 $1 = #1 $0]\n"
                + "          [(1, 2), (2, 3)]\n"
                + "          [(1, 2), (2, 3)]\n"));
    assertThat(exp.type, hasToString("{x:int, y:int} list"));
  }
}

// End FromBuilderTest.java
