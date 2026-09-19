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
import com.google.common.collect.ImmutableSet;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.ast.RelBuilder;
import net.hydromatic.morel.ast.Simplification;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.compile.Coloring;
import net.hydromatic.morel.compile.Profile;
import net.hydromatic.morel.compile.RelRules;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.TypeSystem;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link Coloring}, which says which engine runs which part of a tree.
 *
 * <p>The engine is imaginary: a profile whose leaves are the lists {@code [1,
 * 2]} and no others, which is enough to ask where the boundary falls without a
 * database to put behind it.
 */
public class ColoringTest {
  private static class Fixture {
    final TypeSystem typeSystem = new TypeSystem();

    {
      BuiltIn.dataTypes(typeSystem, new ArrayList<>());
    }

    final Core.Exp mine =
        core.list(
            typeSystem,
            PrimitiveType.INT,
            ImmutableList.of(intLiteral(1), intLiteral(2)));

    final Core.Exp theirs =
        core.list(
            typeSystem,
            PrimitiveType.INT,
            ImmutableList.of(intLiteral(3), intLiteral(4)));

    /** An engine that holds {@link #mine} and nothing else. */
    final Profile profile =
        Profile.create(
            "test",
            ImmutableSet.of(
                Op.FILTER, Op.PROJECT, Op.JOIN, Op.SORT, Op.BOUNDARY),
            ImmutableSet.of(Core.Rel.JoinType.INNER),
            false,
            true,
            (leaf, env) -> leaf == mine,
            true,
            ImmutableSet.of(),
            false);

    /** The same engine, but it can be given data it does not hold. */
    final Profile ships =
        Profile.create(
            "test",
            ImmutableSet.of(
                Op.FILTER, Op.PROJECT, Op.JOIN, Op.SORT, Op.BOUNDARY),
            ImmutableSet.of(Core.Rel.JoinType.INNER),
            false,
            true,
            (leaf, env) -> false,
            true,
            ImmutableSet.of(),
            true);

    /** The same engine, but it cannot call back, and has only "&gt;". */
    final Profile noCallback =
        Profile.create(
            "test",
            ImmutableSet.of(
                Op.FILTER, Op.PROJECT, Op.JOIN, Op.SORT, Op.BOUNDARY),
            ImmutableSet.of(Core.Rel.JoinType.INNER),
            false,
            true,
            (leaf, env) -> leaf == mine,
            false,
            ImmutableSet.of(BuiltIn.OP_GT),
            false);

    Core.Exp intLiteral(int i) {
      return core.intLiteral(BigDecimal.valueOf(i));
    }

    RelBuilder builder(Core.Exp leaf) {
      final RelBuilder b =
          RelBuilder.create(typeSystem, EnumSet.noneOf(Simplification.class));
      b.push(leaf);
      return b;
    }

    /** Colors a tree. */
    Core.Exp colored(Core.Exp tree) {
      return colored(profile, tree);
    }

    Core.Exp colored(Profile profile, Core.Exp tree) {
      return RelRules.rewrite(
          typeSystem, ImmutableList.of(Coloring.rule(profile)), tree);
    }

    /** Colors a tree, and returns its plan text. */
    String color(Core.Exp tree) {
      return colored(tree).toString();
    }
  }

  /** Where every node and leaf can go, the boundary is at the root. */
  @Test
  void testAllOfIt() {
    final Fixture f = new Fixture();
    final RelBuilder b = f.builder(f.mine);
    b.filter(core.greaterThan(f.typeSystem, b.input(0), f.intLiteral(1)));
    assertThat(
        f.color(b.build()),
        hasToString(
            "boundary [test]\n" //
                + "  filter [$0 > 1]\n"
                + "    [1, 2]\n"));
  }

  /** Where the leaf is not the engine's, nothing can go. */
  @Test
  void testNoneOfIt() {
    final Fixture f = new Fixture();
    final RelBuilder b = f.builder(f.theirs);
    b.filter(core.greaterThan(f.typeSystem, b.input(0), f.intLiteral(1)));
    assertThat(
        f.color(b.build()),
        hasToString(
            "filter [$0 > 1]\n" //
                + "  [3, 4]\n"));
  }

  /**
   * Where one side of a join is the engine's and the other is not, the boundary
   * falls on that side, and the join stays outside it.
   */
  @Test
  void testOneSide() {
    final Fixture f = new Fixture();
    final RelBuilder b = f.builder(f.mine);
    b.filter(core.greaterThan(f.typeSystem, b.input(0), f.intLiteral(1)));
    b.push(f.theirs);
    b.pair();
    b.join(
        Core.Rel.JoinType.INNER,
        core.equal(f.typeSystem, b.input(0), b.input(1)));
    assertThat(
        f.color(b.build()),
        hasToString(
            "join [$0 = $1]\n" //
                + "  boundary [test]\n"
                + "    filter [$0 > 1]\n"
                + "      [1, 2]\n"
                + "  [3, 4]\n"));
  }

  /** A node the profile does not run stops the boundary below it. */
  @Test
  void testNodeNotRun() {
    final Fixture f = new Fixture();
    final RelBuilder b = f.builder(f.mine);
    b.filter(core.greaterThan(f.typeSystem, b.input(0), f.intLiteral(1)));
    // 'take' is not in this profile's node kinds.
    b.take(f.intLiteral(1));
    assertThat(
        f.color(b.build()),
        hasToString(
            "take [1]\n" //
                + "  boundary [test]\n"
                + "    filter [$0 > 1]\n"
                + "      [1, 2]\n"));
  }

  /**
   * An engine that cannot call back must be able to evaluate what it runs, so a
   * condition using a function it lacks keeps the node out.
   */
  @Test
  void testCannotCallBack() {
    final Fixture f = new Fixture();
    final RelBuilder b = f.builder(f.mine);
    b.filter(core.greaterThan(f.typeSystem, b.input(0), f.intLiteral(1)));
    // '>' it has, so it takes the filter.
    assertThat(
        f.colored(f.noCallback, b.build()),
        hasToString(
            "boundary [test]\n" //
                + "  filter [$0 > 1]\n"
                + "    [1, 2]\n"));

    final RelBuilder b2 = f.builder(f.mine);
    b2.filter(core.lessThan(f.typeSystem, b2.input(0), f.intLiteral(1)));
    // '<' it does not, so the filter stays outside and only the leaf goes.
    assertThat(
        f.colored(f.noCallback, b2.build()),
        hasToString(
            "filter [$0 < 1]\n" //
                + "  boundary [test]\n"
                + "    [1, 2]\n"));
  }

  /**
   * An engine that can be given data takes a leaf it does not hold, so the
   * colour follows what consumes it; but never a leaf that is a function.
   */
  @Test
  void testShipping() {
    final Fixture f = new Fixture();
    final RelBuilder b = f.builder(f.theirs);
    b.filter(core.greaterThan(f.typeSystem, b.input(0), f.intLiteral(1)));
    assertThat(
        f.colored(f.ships, b.build()),
        hasToString(
            "boundary [test]\n" //
                + "  filter [$0 > 1]\n"
                + "    [3, 4]\n"));

    // A collection of functions is not data, so it does not cross.
    final Core.Exp functions =
        core.list(
            f.typeSystem,
            f.typeSystem.fnType(PrimitiveType.INT, PrimitiveType.INT),
            ImmutableList.of());
    final RelBuilder b2 =
        RelBuilder.create(f.typeSystem, EnumSet.noneOf(Simplification.class));
    b2.push(functions);
    b2.sort(b2.input(0));
    assertThat(
        f.colored(f.ships, b2.build()),
        hasToString(
            "sort [$0]\n" //
                + "  []\n"));
  }

  /** Coloring a tree that is colored already changes nothing. */
  @Test
  void testIdempotent() {
    final Fixture f = new Fixture();
    final RelBuilder b = f.builder(f.mine);
    b.filter(core.greaterThan(f.typeSystem, b.input(0), f.intLiteral(1)));
    final Core.Exp once = f.colored(b.build());
    assertThat(f.color(once), hasToString(once.toString()));
  }
}

// End ColoringTest.java
