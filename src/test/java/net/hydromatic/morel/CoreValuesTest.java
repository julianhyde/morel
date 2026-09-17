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
import static net.hydromatic.morel.util.Static.transformEager;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasToString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.RelBuilder;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.eval.CoreValues;
import net.hydromatic.morel.eval.Variant;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.RecordLikeType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link CoreValues}, the view of the compiler's tree as values of the
 * datatypes {@code exp} and {@code pat}.
 */
public class CoreValuesTest {
  private static class Fixture {
    final TypeSystem typeSystem = new TypeSystem();

    {
      BuiltIn.dataTypes(typeSystem, new ArrayList<>());
    }

    final Type recordType =
        typeSystem.recordType(
            ImmutableSortedMap.of(
                "deptno", PrimitiveType.INT, "name", PrimitiveType.STRING));

    Core.Exp intLiteral(int i) {
      return core.intLiteral(BigDecimal.valueOf(i));
    }

    /** {@code [{deptno = 10, name = "a"}, {deptno = 20, name = "b"}]}. */
    Core.Exp emps() {
      return core.list(
          typeSystem,
          recordType,
          ImmutableList.of(
              core.tuple(
                  typeSystem,
                  (RecordLikeType) recordType,
                  ImmutableList.of(intLiteral(10), core.stringLiteral("a"))),
              core.tuple(
                  typeSystem,
                  (RecordLikeType) recordType,
                  ImmutableList.of(intLiteral(20), core.stringLiteral("b")))));
    }

    RelBuilder builder() {
      return RelBuilder.create(typeSystem);
    }

    /**
     * Takes a value of {@code exp} or {@code pat} apart to its leaves and
     * builds it again from its constructors, as a Morel program would.
     */
    Object roundTrip(Object value) {
      if (value instanceof CoreValues.View) {
        final List<?> view = (List<?>) value;
        if (view.size() == 1) {
          return value; // OPAQUE, OPAQUE_PAT: nothing to take apart
        }
        return CoreValues.fromConstructor(
            (String) view.get(0), roundTrip(view.get(1)), typeSystem);
      }
      if (value instanceof List && !(value instanceof Variant)) {
        // A record, a tuple, a list, an option: rebuild each element.
        return transformEager((List<?>) value, this::roundTrip);
      }
      return value; // a literal, a name, a type
    }

    void checkRoundTrip(Core.Exp exp) {
      final Object value = CoreValues.of(exp);
      final Object value2 = roundTrip(value);
      assertThat(CoreValues.toExp(value2), hasToString(exp.toString()));
      assertThat(
          CoreValues.toExp(value2).type.moniker(), is(exp.type.moniker()));
    }
  }

  /** A value is a view of the node, not a copy; matching renders a level. */
  @Test
  void testView() {
    final Fixture f = new Fixture();
    final RelBuilder b = f.builder();
    b.push(f.emps());
    b.filter(
        core.greaterThan(f.typeSystem, b.field("deptno"), f.intLiteral(10)));
    final Core.Exp filter = b.build();
    final CoreValues.ExpValue value = CoreValues.of(filter);
    assertThat(value.exp, sameInstance(filter));
    assertThat(value.size(), is(2));
    assertThat(value.get(0), is("FILTER"));
    // condition, input, ordinalPat, row
    final List<?> fields = (List<?>) value.get(1);
    assertThat(fields.size(), is(4));
    assertThat(((List<?>) fields.get(0)).get(0), is("APPLY"));
    // The input is a list expression, an application of the list function.
    assertThat(((List<?>) fields.get(1)).get(0), is("APPLY"));
    assertThat(fields.get(2), hasToString("[NONE]"));
    assertThat(((List<?>) fields.get(3)).get(0), is("ID_PAT"));
    assertThat(
        CoreValues.toExp(fields.get(1)),
        sameInstance(((Core.Filter) filter).input));
  }

  /** Every kind of node survives being taken apart and built again. */
  @Test
  void testRoundTrip() {
    final Fixture f = new Fixture();
    final RelBuilder b = f.builder();
    b.push(f.emps());
    b.filter(
        core.greaterThan(f.typeSystem, b.field("deptno"), f.intLiteral(10)));
    b.project(b.field("name"));
    b.sort(b.input(0));
    b.skip(f.intLiteral(1));
    b.take(f.intLiteral(5));
    f.checkRoundTrip(b.build());

    final RelBuilder b2 = f.builder();
    b2.push(f.emps());
    b2.push(f.emps());
    b2.pair();
    b2.join(
        Core.Rel.JoinType.LEFT,
        core.equal(f.typeSystem, b2.field(0, "deptno"), b2.field(1, "deptno")));
    b2.unorder();
    f.checkRoundTrip(b2.build());

    final RelBuilder b3 = f.builder();
    b3.push(f.emps());
    b3.push(f.emps());
    b3.union(2, true);
    b3.ifEmpty(
        core.tuple(
            f.typeSystem,
            (RecordLikeType) f.recordType,
            ImmutableList.of(f.intLiteral(0), core.stringLiteral(""))));
    f.checkRoundTrip(b3.build());
  }
}

// End CoreValuesTest.java
