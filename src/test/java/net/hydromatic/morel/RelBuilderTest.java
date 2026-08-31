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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Set;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.ast.RelBuilder;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.compile.RelValidator;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.PairList;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link RelBuilder}, which does three things: keeps a stack of
 * relational expressions, maps names onto paths into an input, and simplifies
 * under a switchable {@link RelBuilder.Simp} set.
 *
 * <p>Every simplification is tested twice -- once with the set empty, which is
 * what the builder was told to build, and once with it enabled. That pairing is
 * the point of the {@code EnumSet}: it makes "what did simplification change?"
 * a question the tests can ask directly.
 */
public class RelBuilderTest {
  private static class Fixture {
    final TypeSystem typeSystem = new TypeSystem();

    {
      BuiltIn.dataTypes(typeSystem, new ArrayList<>());
    }

    /** A collection of records, as {@code scott.emps} is. */
    final Core.Exp emps =
        core.list(
            typeSystem,
            record("deptno", core.literal(PrimitiveType.INT, 10)),
            record("deptno", core.literal(PrimitiveType.INT, 20)));

    final Core.Exp list12 =
        core.list(
            typeSystem,
            core.literal(PrimitiveType.INT, 1),
            core.literal(PrimitiveType.INT, 2));

    Core.Exp record(String name, Core.Exp exp) {
      final PairList<String, Core.Exp> nameExps = PairList.of();
      nameExps.add(name, exp);
      return core.record(typeSystem, nameExps);
    }

    Core.Literal intLiteral(int i) {
      return core.literal(PrimitiveType.INT, i);
    }

    RelBuilder builder(Set<RelBuilder.Simp> simps) {
      return RelBuilder.create(typeSystem, simps);
    }

    /** Builds, checks the validator is happy, and returns the plan text. */
    String plan(Core.Exp rel) {
      if (rel instanceof Core.Rel) {
        assertThat(
            RelValidator.violations(typeSystem, (Core.Rel) rel), empty());
        return ((Core.Rel) rel).describe();
      }
      return rel + "\n";
    }
  }

  /** Tests that a leaf and a filter make the tree they say. */
  @Test
  void testFilter() {
    final Fixture f = new Fixture();
    final RelBuilder b = f.builder(RelBuilder.Simp.NONE);
    final Core.Exp rel =
        b.push(f.list12)
            .filter(core.greaterThan(f.typeSystem, b.input(0), f.intLiteral(1)))
            .build();
    assertThat(
        f.plan(rel),
        is(
            "filter [$0 > 1]\n" //
                + "  [1, 2]\n"));
  }

  /**
   * Tests the name map: a leaf can name its element, and a field of that name
   * becomes a selector over {@code $0}, so that the caller never writes {@code
   * $0} or a slot number.
   */
  @Test
  void testNameAndField() {
    final Fixture f = new Fixture();
    final RelBuilder b = f.builder(RelBuilder.Simp.NONE);
    final Core.Exp rel =
        b.push("e", f.emps)
            .filter(
                core.equal(
                    f.typeSystem, b.field(0, "e", "deptno"), f.intLiteral(10)))
            .build();
    assertThat(
        f.plan(rel),
        is(
            "filter [#deptno $0 = 10]\n"
                + "  [{deptno = 10}, {deptno = 20}]\n"));
  }

  /**
   * Tests that a record element's field names are available without the leaf
   * being named at all, because the names of a node are the field names of its
   * element.
   */
  @Test
  void testFieldWithoutName() {
    final Fixture f = new Fixture();
    final RelBuilder b = f.builder(RelBuilder.Simp.NONE);
    final Core.Exp rel = b.push(f.emps).project(b.name("deptno")).build();
    assertThat(
        f.plan(rel),
        is(
            "project [#deptno $0]\n" //
                + "  [{deptno = 10}, {deptno = 20}]\n"));
  }

  /**
   * Tests the stack with two inputs: the deeper is the left and is {@code $0},
   * the top is the right and is {@code $1}.
   */
  @Test
  void testJoin() {
    final Fixture f = new Fixture();
    final RelBuilder b = f.builder(RelBuilder.Simp.NONE);
    final PairList<String, Core.Exp> nameExps = PairList.of();
    b.push("i", f.list12).push("j", f.list12).pair();
    nameExps.add("i", b.name(0, "i"));
    nameExps.add("j", b.name(1, "j"));
    final Core.Exp rel =
        b.join(
                Core.Rel.JoinType.INNER,
                core.boolLiteral(true),
                core.record(f.typeSystem, nameExps))
            .build();
    assertThat(
        f.plan(rel),
        is(
            "join [{i = $0, j = $1}]\n" //
                + "  [1, 2]\n"
                + "  [1, 2]\n"));
  }

  /**
   * Tests a dependent join: the binder names the left element inside the right
   * input, which is a tree of its own and so cannot say {@code $0} and mean the
   * left.
   */
  @Test
  void testDependentJoin() {
    final Fixture f = new Fixture();
    final RelBuilder b = f.builder(RelBuilder.Simp.NONE);
    b.push("e", f.emps);
    final Core.IdPat binder = b.binder("e");
    // The right input reads the left element through the binder.
    b.push(core.list(f.typeSystem, b.field(core.id(binder), "deptno")));
    b.pair();
    final PairList<String, Core.Exp> nameExps = PairList.of();
    nameExps.add("d", b.input(1));
    nameExps.add("e", b.input(0));
    final Core.Exp rel =
        b.join(
                Core.Rel.JoinType.INNER,
                binder,
                core.boolLiteral(true),
                core.record(f.typeSystem, nameExps))
            .build();
    assertThat(
        f.plan(rel),
        is(
            "join [e] [{d = $1, e = $0}]\n" //
                + "  [{deptno = 10}, {deptno = 20}]\n"
                + "  [#deptno e]\n"));
  }

  /**
   * Tests {@link RelBuilder.Simp#JOIN_INDEPENDENT}: a binder the right input
   * does not read makes no join dependent, so it goes, and what is left is an
   * independent join with a condition.
   */
  @Test
  void testJoinIndependent() {
    final Fixture f = new Fixture();
    // The right input reads nothing of the left, though a binder is offered.
    assertThat(
        f.plan(offeredBinder(f, RelBuilder.Simp.NONE)),
        is(
            "join [i] [$0 = $1] [$0]\n" //
                + "  [1, 2]\n"
                + "  [1, 2]\n"));
    assertThat(
        f.plan(offeredBinder(f, RelBuilder.Simp.ALL)),
        is(
            "join [$0 = $1] [$0]\n" //
                + "  [1, 2]\n"
                + "  [1, 2]\n"));
  }

  private static Core.Exp offeredBinder(Fixture f, Set<RelBuilder.Simp> simps) {
    final RelBuilder b = f.builder(simps);
    b.push("i", f.list12);
    final Core.IdPat binder = b.binder("i");
    b.push(f.list12).pair();
    return b.join(
            Core.Rel.JoinType.INNER,
            binder,
            core.equal(f.typeSystem, b.input(0), b.input(1)),
            b.input(0))
        .build();
  }

  /**
   * Tests that a binder the right input <em>does</em> read is kept, so the join
   * stays dependent.
   */
  @Test
  void testJoinStaysDependentWhenRead() {
    final Fixture f = new Fixture();
    final RelBuilder b = f.builder(RelBuilder.Simp.ALL);
    b.push("e", f.emps);
    final Core.IdPat binder = b.binder("e");
    b.push(core.list(f.typeSystem, b.field(core.id(binder), "deptno")));
    b.pair();
    final Core.Exp rel =
        b.join(
                Core.Rel.JoinType.INNER,
                binder,
                core.boolLiteral(true),
                b.input(1))
            .build();
    assertThat(
        f.plan(rel),
        is(
            "join [e] [$1]\n" //
                + "  [{deptno = 10}, {deptno = 20}]\n"
                + "  [#deptno e]\n"));
  }

  /**
   * Tests that the validator rejects a binder read from the condition or the
   * yield, where {@code $0} and {@code $1} are what a join says.
   */
  @Test
  void testBinderOutOfScope() {
    final Fixture f = new Fixture();
    final RelBuilder b = f.builder(RelBuilder.Simp.NONE);
    b.push("e", f.emps);
    final Core.IdPat binder = b.binder("e");
    b.push(core.list(f.typeSystem, b.field(core.id(binder), "deptno")));
    b.pair();
    // Illegal: the yield reads the binder rather than $0.
    final Core.Exp rel =
        b.join(
                Core.Rel.JoinType.INNER,
                binder,
                core.boolLiteral(true),
                core.id(binder))
            .build();
    assertThat(
        RelValidator.violations(f.typeSystem, (Core.Rel) rel),
        hasItem(
            containsString("join yield cannot reference the join's binder")));
  }

  /** Tests that a set operator takes as many inputs as it is given. */
  @Test
  void testUnionOfThree() {
    final Fixture f = new Fixture();
    final RelBuilder b = f.builder(RelBuilder.Simp.NONE);
    final Core.Exp rel =
        b.push(f.list12).push(f.list12).push(f.list12).union(3, false).build();
    assertThat(
        f.plan(rel),
        is(
            "union [all]\n" //
                + "  [1, 2]\n"
                + "  [1, 2]\n"
                + "  [1, 2]\n"));
  }

  /** Tests {@link RelBuilder.Simp#FILTER_TRUE}, off and on. */
  @Test
  void testFilterTrue() {
    final Fixture f = new Fixture();
    assertThat(
        f.plan(
            f.builder(RelBuilder.Simp.NONE)
                .push(f.list12)
                .filter(core.boolLiteral(true))
                .build()),
        is(
            "filter [true]\n" //
                + "  [1, 2]\n"));
    assertThat(
        f.plan(
            f.builder(RelBuilder.Simp.ALL)
                .push(f.list12)
                .filter(core.boolLiteral(true))
                .build()),
        is("[1, 2]\n"));
  }

  /** Tests {@link RelBuilder.Simp#FILTER_MERGE}, off and on. */
  @Test
  void testFilterMerge() {
    final Fixture f = new Fixture();
    assertThat(
        f.plan(twoFilters(f, RelBuilder.Simp.NONE)),
        is(
            "filter [$0 < 2]\n" //
                + "  filter [$0 > 0]\n"
                + "    [1, 2]\n"));
    assertThat(
        f.plan(twoFilters(f, RelBuilder.Simp.ALL)),
        is(
            "filter [$0 > 0 andalso $0 < 2]\n" //
                + "  [1, 2]\n"));
  }

  private static Core.Exp twoFilters(Fixture f, Set<RelBuilder.Simp> simps) {
    final RelBuilder b = f.builder(simps);
    return b.push(f.list12)
        .filter(core.greaterThan(f.typeSystem, b.input(0), f.intLiteral(0)))
        .filter(core.lessThan(f.typeSystem, b.input(0), f.intLiteral(2)))
        .build();
  }

  /** Tests {@link RelBuilder.Simp#PROJECT_IDENTITY}, off and on. */
  @Test
  void testProjectIdentity() {
    final Fixture f = new Fixture();
    final RelBuilder b0 = f.builder(RelBuilder.Simp.NONE);
    assertThat(
        f.plan(b0.push(f.list12).project(b0.input(0)).build()),
        is(
            "project [$0]\n" //
                + "  [1, 2]\n"));
    final RelBuilder b1 = f.builder(RelBuilder.Simp.ALL);
    assertThat(
        f.plan(b1.push(f.list12).project(b1.input(0)).build()), is("[1, 2]\n"));
  }

  /**
   * Tests {@link RelBuilder.Simp#PROJECT_MERGE}, off and on. Merging is
   * substitution: the outer projection's {@code $0} is the inner projection's
   * expression, so {@code $0 > 1} over {@code #deptno $0} becomes {@code
   * #deptno $0 > 1}.
   */
  @Test
  void testProjectMerge() {
    final Fixture f = new Fixture();
    assertThat(
        f.plan(twoProjects(f, RelBuilder.Simp.NONE)),
        is(
            "project [$0 > 1]\n" //
                + "  project [#deptno $0]\n"
                + "    [{deptno = 10}, {deptno = 20}]\n"));
    assertThat(
        f.plan(twoProjects(f, RelBuilder.Simp.ALL)),
        is(
            "project [#deptno $0 > 1]\n" //
                + "  [{deptno = 10}, {deptno = 20}]\n"));
  }

  private static Core.Exp twoProjects(Fixture f, Set<RelBuilder.Simp> simps) {
    final RelBuilder b = f.builder(simps);
    b.push(f.emps).project(b.field("deptno"));
    return b.project(
            core.greaterThan(f.typeSystem, b.input(0), f.intLiteral(1)))
        .build();
  }

  /** Tests {@link RelBuilder.Simp#SKIP_ZERO}, off and on. */
  @Test
  void testSkipZero() {
    final Fixture f = new Fixture();
    assertThat(
        f.plan(
            f.builder(RelBuilder.Simp.NONE)
                .push(f.list12)
                .skip(f.intLiteral(0))
                .build()),
        is(
            "skip [0]\n" //
                + "  [1, 2]\n"));
    assertThat(
        f.plan(
            f.builder(RelBuilder.Simp.ALL)
                .push(f.list12)
                .skip(f.intLiteral(0))
                .build()),
        is("[1, 2]\n"));
    // A non-zero skip survives either way.
    assertThat(
        f.plan(
            f.builder(RelBuilder.Simp.ALL)
                .push(f.list12)
                .skip(f.intLiteral(1))
                .build()),
        is(
            "skip [1]\n" //
                + "  [1, 2]\n"));
  }

  /**
   * Tests that the simplifications are independent: enabling one does not
   * enable another.
   */
  @Test
  void testOneSimplificationAtATime() {
    final Fixture f = new Fixture();
    final Set<RelBuilder.Simp> onlyFilter =
        EnumSet.of(RelBuilder.Simp.FILTER_TRUE);
    final RelBuilder b = f.builder(onlyFilter);
    final Core.Exp rel =
        b.push(f.list12)
            .filter(core.boolLiteral(true))
            .skip(f.intLiteral(0))
            .build();
    // The filter went; the skip stayed, because SKIP_ZERO is off.
    assertThat(
        f.plan(rel),
        is(
            "skip [0]\n" //
                + "  [1, 2]\n"));
  }

  /**
   * Tests the oracle that the {@code EnumSet} buys: the same query built twice,
   * once as told and once simplified, and the two compared.
   */
  @Test
  void testSimplifiedDiffersFromLiteral() {
    final Fixture f = new Fixture();
    final String literal = f.plan(twoFilters(f, RelBuilder.Simp.NONE));
    final String simplified = f.plan(twoFilters(f, RelBuilder.Simp.ALL));
    assertThat(literal, not(is(simplified)));
    // Both are valid trees of the same type: simplification does not change
    // what the query returns, only how many nodes say it.
    assertThat(
        twoFilters(f, RelBuilder.Simp.NONE).type,
        is(twoFilters(f, RelBuilder.Simp.ALL).type));
  }

  /**
   * Tests that a scan's pattern is erased into one name per binder, each mapped
   * to the path that reads it out of the element.
   */
  @Test
  void testPushPattern() {
    final Fixture f = new Fixture();
    final RelBuilder b = f.builder(RelBuilder.Simp.NONE);
    final Core.Pat pat =
        core.tuplePat(
            f.typeSystem,
            ImmutableList.of(
                core.idPat(PrimitiveType.INT, "a", 0),
                core.idPat(PrimitiveType.INT, "b", 0)));
    final Core.Exp pairs =
        core.list(
            f.typeSystem,
            core.tuple(f.typeSystem, f.intLiteral(1), f.intLiteral(2)));
    final Core.Exp rel =
        b.push(pat, pairs)
            .filter(core.greaterThan(f.typeSystem, b.name("b"), b.name("a")))
            .build();
    assertThat(
        f.plan(rel),
        is(
            "filter [#2 $0 > #1 $0]\n" //
                + "  [(1, 2)]\n"));
  }

  /**
   * Tests that a pattern which can fail to match is refused, because such a
   * pattern also filters and so cannot simply be erased.
   */
  @Test
  void testFailablePatternRefused() {
    final Core.Pat idPat = core.idPat(PrimitiveType.INT, "a", 0);
    final Core.Pat literalPat =
        core.literalPat(Op.INT_LITERAL_PAT, PrimitiveType.INT, BigDecimal.ONE);
    assertThat(RelBuilder.destructurable(idPat), is(true));
    assertThat(RelBuilder.destructurable(literalPat), is(false));
  }

  /**
   * Tests {@code projectMany}, which is what a scan that reads an earlier
   * binder becomes: the lambda's parameter names the enclosing element, because
   * the body is a tree of its own and its {@code $0} is its own.
   */
  @Test
  void testProjectMany() {
    final Fixture f = new Fixture();
    final RelBuilder b = f.builder(RelBuilder.Simp.NONE);
    b.push("i", f.list12);
    final Core.IdPat param = b.param("i");
    final Core.Exp body = core.list(f.typeSystem, core.id(param));
    final Core.Exp rel = b.projectMany(param, body).build();
    assertThat(
        f.plan(rel),
        is(
            "projectMany\n" //
                + "  [1, 2]\n"
                + "  fn i =>\n"
                + "    [i]\n"));
  }

  /**
   * Tests that merging two projections binds the inner expression to a variable
   * when the outer reads it more than once, so that the merge never turns one
   * evaluation per row into two.
   */
  @Test
  void testProjectMergeBindsWhatItWouldDuplicate() {
    final Fixture f = new Fixture();
    final RelBuilder b = f.builder(RelBuilder.Simp.NONE);
    b.push(f.emps).project(b.field("deptno"));
    // The outer projection reads its input twice.
    final Core.Exp rel =
        b.project(core.greaterThan(f.typeSystem, b.input(0), b.input(0)))
            .build();
    assertThat(
        f.plan(rel),
        is(
            "project [$0 > $0]\n" //
                + "  project [#deptno $0]\n"
                + "    [{deptno = 10}, {deptno = 20}]\n"));

    final RelBuilder b2 = f.builder(RelBuilder.Simp.ALL);
    b2.push(f.emps).project(b2.field("deptno"));
    final Core.Exp rel2 =
        b2.project(core.greaterThan(f.typeSystem, b2.input(0), b2.input(0)))
            .build();
    assertThat(
        f.plan(rel2),
        is(
            "project [let val v$0 = #deptno $0 in v$0 > v$0 end]\n" //
                + "  [{deptno = 10}, {deptno = 20}]\n"));
  }

  /** Tests {@code group}, whose element is a record of keys and aggregates. */
  @Test
  void testGroup() {
    final Fixture f = new Fixture();
    final RelBuilder b = f.builder(RelBuilder.Simp.NONE);
    b.push(f.list12);
    final Core.Exp rel =
        b.group(ImmutableSortedMap.of("k", b.input(0)), ImmutableSortedMap.of())
            .build();
    assertThat(
        f.plan(rel),
        is(
            "group [k = $0]\n" //
                + "  [1, 2]\n"));
  }
}

// End RelBuilderTest.java
