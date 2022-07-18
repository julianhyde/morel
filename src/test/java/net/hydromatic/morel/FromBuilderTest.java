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

import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.FromBuilder;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;

import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

import static net.hydromatic.morel.ast.CoreBuilder.core;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Test {@link net.hydromatic.morel.ast.FromBuilder}.
 */
public class FromBuilderTest {
  private static class Fixture {
    final TypeSystem typeSystem = new TypeSystem();
    final PrimitiveType intType = PrimitiveType.INT;
    final Core.IdPat iPat = core.idPat(intType, "i", 0);
    final Core.Id iId = core.id(iPat);
    final Core.IdPat jPat = core.idPat(intType, "j", 0);
    final Core.Id jId = core.id(jPat);
    final Core.Exp list12 = core.list(typeSystem, intType,
        ImmutableList.of(intLiteral(1), intLiteral(2)));
    final Core.Exp list34 = core.list(typeSystem, intType,
        ImmutableList.of(intLiteral(3), intLiteral(4)));

    Core.Literal intLiteral(int i) {
      return core.literal(intType, i);
    }

    public Core.Exp record(Core.Id... ids) {
      final List<Core.Id> args = ImmutableList.copyOf(ids);
      final SortedMap<String, Type> argNameTypes =
          new TreeMap<>(RecordType.ORDERING);
      args.forEach(arg -> argNameTypes.put(arg.idPat.name, arg.type));
      return core.tuple(typeSystem.recordType(argNameTypes), args);
    }
  }

  @Test void testBasic() {
    // from i in [1, 2]
    final Fixture f = new Fixture();
    final FromBuilder fromBuilder = core.fromBuilder(f.typeSystem);
    fromBuilder.scan(f.iPat, f.list12);

    final Core.From from = fromBuilder.build();
    assertThat(from.toString(), is("from i in [1, 2]"));
    final Core.Exp e = fromBuilder.buildSimplify();
    assertThat(e.toString(), is("[1, 2]"));

    // "from i in [1, 2] yield i" --> "[1, 2]"
    fromBuilder.yield(f.iId);
    final Core.From from2 = fromBuilder.build();
    assertThat(from2, is(from));
    final Core.Exp e2 = fromBuilder.buildSimplify();
    assertThat(e2, is(e));
  }

  @Test void testWhere() {
    // from i in [1, 2] where i < 2
    final Fixture f = new Fixture();
    final FromBuilder fromBuilder = core.fromBuilder(f.typeSystem);
    fromBuilder.scan(f.iPat, f.list12)
        .where(core.lessThan(f.typeSystem, f.iId, f.intLiteral(2)));

    final Core.From from = fromBuilder.build();
    assertThat(from.toString(), is("from i in [1, 2] where i < 2"));
    final Core.Exp e = fromBuilder.buildSimplify();
    assertThat(e, is(from));

    // "where true" is ignored
    fromBuilder.where(core.boolLiteral(true));
    final Core.From from2 = fromBuilder.build();
    assertThat(from2, is(from));
    final Core.Exp e2 = fromBuilder.buildSimplify();
    assertThat(e2, is(e));
  }

  @Test void testTrivialYield() {
    // from i in [1, 2] where i < 2 yield i
    final Fixture f = new Fixture();
    final FromBuilder fromBuilder = core.fromBuilder(f.typeSystem);
    fromBuilder.scan(f.iPat, f.list12)
        .where(core.lessThan(f.typeSystem, f.iId, f.intLiteral(2)))
        .yield(f.iId);

    final Core.From from = fromBuilder.build();
    assertThat(from.toString(), is("from i in [1, 2] where i < 2"));
    final Core.Exp e = fromBuilder.buildSimplify();
    assertThat(e, is(from));
  }

  @Test void testTrivialYield2() {
    // from j in [1, 2], i in [3, 4] where i < 2 yield {i, j}
    //   ==>
    // from j in [1, 2], i in [3, 4] where i < 2
    final Fixture f = new Fixture();
    final FromBuilder fromBuilder = core.fromBuilder(f.typeSystem);
    fromBuilder.scan(f.jPat, f.list12)
        .scan(f.iPat, f.list34)
        .where(core.lessThan(f.typeSystem, f.iId, f.intLiteral(2)))
        .yield(f.record(f.iId, f.jId));

    final Core.From from = fromBuilder.build();
    final String expected = "from j in [1, 2] "
        + "join i in [3, 4] "
        + "where i < 2";
    assertThat(from.toString(), is(expected));
    final Core.Exp e = fromBuilder.buildSimplify();
    assertThat(e, is(from));
  }
}

// End FromBuilderTest.java
