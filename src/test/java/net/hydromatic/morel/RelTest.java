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

import com.google.common.collect.ImmutableSortedMap;
import java.util.ArrayList;
import java.util.Arrays;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.PairList;
import org.junit.jupiter.api.Test;

/**
 * Tests the relational tree, {@link Core.Rel}: the element type and kind that
 * {@link net.hydromatic.morel.ast.CoreBuilder} derives for each node, and the
 * plan text that a tree prints.
 *
 * <p>The derivations are those in {@code spec.md} sections 3 and 4, and the
 * plan text is the one in section 6; both are the contract that morel-rust and
 * morel-go implement, so a change here is a change to that contract.
 */
public class RelTest {
  /** Fixture with a type system and a few expressions to build trees from. */
  private static class Fixture {
    final TypeSystem typeSystem = new TypeSystem();

    {
      // Register 'bag' and the other built-in data types.
      BuiltIn.dataTypes(typeSystem, new ArrayList<>());
    }

    final PrimitiveType intType = PrimitiveType.INT;

    /** {@code $0}, the element of a node's input, of type {@code int}. */
    final Core.Id input0 = core.input0(intType);

    /** {@code $1}, the element of a join's right input. */
    final Core.Id input1 = core.input1(intType);

    final Core.Exp list12 = core.list(typeSystem, intLiteral(1), intLiteral(2));
    final Core.Exp list34 = core.list(typeSystem, intLiteral(3), intLiteral(4));
    final Core.Exp bag56 = core.bag(typeSystem, intLiteral(5), intLiteral(6));

    Core.Literal intLiteral(int i) {
      return core.literal(intType, i);
    }

    /**
     * Creates a record of the given expressions, labelled {@code a}, {@code b},
     * and so on.
     */
    Core.Exp record(Core.Exp... exps) {
      final PairList<String, Core.Exp> nameExps = PairList.of();
      for (int i = 0; i < exps.length; i++) {
        nameExps.add(String.valueOf((char) ('a' + i)), exps[i]);
      }
      return core.record(typeSystem, nameExps);
    }

    Core.Rel scan(Core.Exp exp) {
      return core.scan(exp);
    }
  }

  /**
   * Tests a leaf, a filter and a projection, and that the element type of a
   * projection is simply the type of its expression.
   */
  @Test
  void testFilterProject() {
    final Fixture f = new Fixture();
    final Core.Rel scan = f.scan(f.list12);
    final Core.Rel filter =
        core.filter(
            scan, core.greaterThan(f.typeSystem, f.input0, f.intLiteral(1)));
    final Core.Rel project =
        core.project(f.typeSystem, filter, f.record(f.input0, f.intLiteral(0)));

    assertThat(scan.type.moniker(), is("int list"));
    assertThat(filter.type.moniker(), is("int list"));
    assertThat(project.type.moniker(), is("{a:int, b:int} list"));
    assertThat(
        project.describe(),
        is(
            "project [{a = $0, b = 0}]\n"
                + "  filter [$0 > 1]\n"
                + "    scan [[1, 2]]\n"));
  }

  /**
   * Tests that {@code planEx}-style output prints the collection type of every
   * node.
   */
  @Test
  void testDescribeWithTypes() {
    final Fixture f = new Fixture();
    final Core.Rel filter =
        core.filter(
            f.scan(f.list12),
            core.greaterThan(f.typeSystem, f.input0, f.intLiteral(1)));
    assertThat(
        filter.describe(true),
        is(
            "filter [$0 > 1] : int list\n" //
                + "  scan [[1, 2]] : int list\n"));
  }

  /**
   * Tests that a join's element type is the type of its yield expression, and
   * that it is ordered only if both inputs are ordered.
   */
  @Test
  void testJoin() {
    final Fixture f = new Fixture();
    final Core.Rel join =
        core.join(
            f.typeSystem,
            f.scan(f.list12),
            f.scan(f.list34),
            core.equal(f.typeSystem, f.input0, f.input1),
            f.record(f.input0, f.input1));
    assertThat(join.type.moniker(), is("{a:int, b:int} list"));
    assertThat(
        join.describe(),
        is(
            "join [$0 = $1] [{a = $0, b = $1}]\n"
                + "  scan [[1, 2]]\n"
                + "  scan [[3, 4]]\n"));

    // A join with a bag input is a bag; a nested loop over a bag has no
    // order to preserve.
    final Core.Rel join2 =
        core.join(
            f.typeSystem,
            f.scan(f.list12),
            f.scan(f.bag56),
            core.boolLiteral(true),
            f.record(f.input0, f.input1));
    assertThat(join2.type.moniker(), is("{a:int, b:int} bag"));

    // A condition that is 'true' and an inner join kind print nothing.
    assertThat(
        join2.describe(),
        is(
            "join [{a = $0, b = $1}]\n"
                + "  scan [[1, 2]]\n"
                + "  scan [#fromList Bag ([5, 6])]\n"));

    // An outer join prints its kind.
    final Core.Rel join3 =
        core.join(
            f.typeSystem,
            Core.Rel.JoinType.LEFT,
            f.scan(f.list12),
            f.scan(f.list34),
            core.boolLiteral(true),
            f.record(f.input0, f.input1));
    assertThat(
        join3.describe(),
        is(
            "join [left] [{a = $0, b = $1}]\n"
                + "  scan [[1, 2]]\n"
                + "  scan [[3, 4]]\n"));
  }

  /**
   * Tests {@code projectMany}: its element type is that of its body, its lambda
   * parameter names the input element, and its body prints as a tree below the
   * input.
   */
  @Test
  void testProjectMany() {
    final Fixture f = new Fixture();
    final Core.IdPat dPat = core.idPat(f.intType, "d", 0);
    final Core.Id dId = core.id(dPat);

    // from d in [1, 2], e in [3, 4] yield {a = d, b = e}
    //   -- but the body's leaf mentions d, so it is correlated
    final Core.Rel body =
        core.project(
            f.typeSystem,
            f.scan(core.list(f.typeSystem, dId, f.intLiteral(4))),
            f.record(dId, f.input0));
    final Core.Rel projectMany =
        core.projectMany(f.typeSystem, f.scan(f.list12), dPat, body);

    assertThat(projectMany.type.moniker(), is("{a:int, b:int} list"));
    assertThat(
        projectMany.describe(),
        is(
            "projectMany\n"
                + "  scan [[1, 2]]\n"
                + "  fn d =>\n"
                + "    project [{a = d, b = $0}]\n"
                + "      scan [[d, 4]]\n"));

    // A bag body makes the output a bag, as a dependent scan over a bag
    // does today.
    final Core.Rel body2 = f.scan(f.bag56);
    final Core.Rel projectMany2 =
        core.projectMany(f.typeSystem, f.scan(f.list12), dPat, body2);
    assertThat(projectMany2.type.moniker(), is("int bag"));
  }

  /**
   * Tests that {@code group} derives a record element type, and atomizes to a
   * bare type when there is exactly one key or aggregate.
   */
  @Test
  void testGroup() {
    final Fixture f = new Fixture();
    final Core.Rel scan = f.scan(f.list12);

    final Core.Rel group1 =
        core.group(
            f.typeSystem,
            scan,
            ImmutableSortedMap.of("j", (Core.Exp) f.input0),
            ImmutableSortedMap.of());
    assertThat(group1.type.moniker(), is("int list"));
    assertThat(
        group1.describe(),
        is(
            "group [j = $0]\n" //
                + "  scan [[1, 2]]\n"));

    final Core.Rel group2 =
        core.group(
            f.typeSystem,
            scan,
            ImmutableSortedMap.<String, Core.Exp>orderedBy(RecordType.ORDERING)
                .put("i", f.input0)
                .put("j", f.input0)
                .build(),
            ImmutableSortedMap.of());
    assertThat(group2.type.moniker(), is("{i:int, j:int} list"));
  }

  /**
   * Tests the kind signatures of {@code order}, {@code unorder}, {@code skip},
   * {@code take} and the set operators.
   */
  @Test
  void testKinds() {
    final Fixture f = new Fixture();
    final Core.Rel scanList = f.scan(f.list12);
    final Core.Rel scanBag = f.scan(f.bag56);

    // order : coll -> list; unorder : coll -> bag
    assertThat(
        core.order(f.typeSystem, scanBag, f.input0).type.moniker(),
        is("int list"));
    assertThat(
        core.unorder(f.typeSystem, scanList).type.moniker(), is("int bag"));

    // skip and take preserve the kind, and print their counts
    final Core.Rel skip = core.skip(scanList, f.intLiteral(1));
    assertThat(skip.type.moniker(), is("int list"));
    assertThat(
        core.take(skip, f.intLiteral(2)).describe(),
        is(
            "take [2]\n" //
                + "  skip [1]\n"
                + "    scan [[1, 2]]\n"));

    // a set operator is a list only if every input is a list
    assertThat(
        core.setRel(
                f.typeSystem,
                Core.Rel.SetOp.UNION,
                true,
                Arrays.asList(scanList, f.scan(f.list34)))
            .type
            .moniker(),
        is("int list"));
    final Core.Rel union =
        core.setRel(
            f.typeSystem,
            Core.Rel.SetOp.UNION,
            false,
            Arrays.asList(scanList, scanBag));
    assertThat(union.type.moniker(), is("int bag"));
    assertThat(
        union.describe(),
        is(
            "union [all]\n"
                + "  scan [[1, 2]]\n"
                + "  scan [#fromList Bag ([5, 6])]\n"));
  }
}

// End RelTest.java
