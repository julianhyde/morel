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

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.fail;

import com.google.common.collect.ImmutableMap;
import java.io.StringReader;
import java.util.Map;
import java.util.function.Consumer;
import net.hydromatic.morel.ast.Ast;
import net.hydromatic.morel.ast.AstNode;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Visitor;
import net.hydromatic.morel.eval.Session;
import net.hydromatic.morel.parse.MorelParserImpl;
import net.hydromatic.morel.type.TypeSystem;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link RelExpander}, the front end that grounds the infinite-extent
 * leaves of a relational tree.
 *
 * <p>The engine it calls is the one that grounds a step list, unchanged, so
 * what these tests check is the front end: that naming a leaf's element and
 * rewriting the filters above it in terms of that name yields the generator the
 * step list would have found.
 */
public class RelExpanderTest {
  /**
   * A query compiled as far as Core, with the type system and environment that
   * compiled it.
   */
  private static class Fixture {
    final TypeSystem typeSystem = new TypeSystem();
    final Environment env;
    final Core.From from;

    Fixture(String ml) {
      final MorelParserImpl parser = new MorelParserImpl(new StringReader(ml));
      final AstNode statement = parser.statementEofSafe();
      final Session session = null;
      env = Environments.env(typeSystem, session, ImmutableMap.of());
      final Ast.ValDecl valDecl = Compiles.toValDecl(statement);
      final Consumer<CompileException> ignoreWarnings = w -> {};
      final TypeResolver.Resolved resolved =
          TypeResolver.deduceType(env, valDecl, typeSystem, ignoreWarnings);
      final Resolver resolver = Resolver.of(resolved.typeMap, env, null);
      // Inline once, as Compiles does: until then a built-in such as 'elem'
      // is an Id, not the function literal that the engine matches on.
      final Core.ValDecl valDecl2 =
          (Core.ValDecl)
              resolver
                  .toCore((Ast.ValDecl) resolved.node)
                  .accept(Inliner.of(typeSystem, env, null));
      final Core.From[] froms = {null};
      valDecl2.accept(
          new Visitor() {
            @Override
            protected void visit(Core.From from) {
              super.visit(from);
              if (froms[0] == null) {
                froms[0] = from;
              }
            }
          });
      from = froms[0];
    }

    Core.Exp tree() {
      return RelTranslator.toRel(typeSystem, from);
    }
  }

  /**
   * Returns the generator of the first infinite-extent leaf of a query's tree,
   * as a string.
   */
  private static String generator(String ml) {
    final Fixture f = new Fixture(ml);
    final Map<Core.Exp, Generator> generators =
        RelExpander.ground(f.typeSystem, f.env, f.tree());
    if (generators.isEmpty()) {
      return "no extent";
    }
    final Generator generator = generators.values().iterator().next();
    return generator == null
        ? "not grounded"
        : generator.exp + " : " + generator.cardinality;
  }

  /**
   * Returns the plan text of a query's tree, with its infinite-extent leaves
   * replaced by what bounds them.
   */
  private static String expanded(String ml) {
    final Fixture f = new Fixture(ml);
    final Core.Exp tree = RelExpander.expand(f.typeSystem, f.env, f.tree());
    return tree instanceof Core.Rel
        ? ((Core.Rel) tree).describe()
        : tree + "\n";
  }

  /** Tests that a leaf constrained by 'elem' is grounded by the list. */
  @Test
  void testElem() {
    assertThat(
        generator("from x where x elem [1, 2, 3]"), is("[1, 2, 3] : FINITE"));
  }

  /**
   * Tests that a leaf with no constraint that bounds it comes back infinite.
   *
   * <p>That is how the engine reports "not grounded": it returns the extent
   * itself, and the caller decides that an infinite generator is an error.
   */
  @Test
  void testUnbounded() {
    assertThat(
        generator("from x where x > 1"), is("extent \"int\" : INFINITE"));
  }

  /** Tests that a query with no unbounded leaf has nothing to ground. */
  @Test
  void testBounded() {
    assertThat(generator("from x in [1, 2] where x > 1"), is("no extent"));
  }

  /**
   * Tests that the leaf is replaced by what bounds it, so that an unbounded
   * query becomes one that can run.
   *
   * <p>The filter goes too: the generator is sealed, so the collection that
   * replaced the leaf already enforces the condition, and the filter tested
   * nothing else.
   */
  @Test
  void testExpand() {
    assertThat(expanded("from x where x elem [1, 2, 3]"), is("[1, 2, 3]\n"));
  }

  /** Tests that a condition a generator does not subsume is kept. */
  @Test
  void testExpandKeepsOtherConditions() {
    assertThat(
        expanded("from x where x elem [1, 2, 3] andalso x > 1"),
        is(
            "filter [$0 > 1]\n" //
                + "  [1, 2, 3]\n"));
  }

  /**
   * Tests a generator that reads another variable: the join becomes a {@code
   * projectMany}, whose lambda binds the left element that the generator needs.
   */
  @Test
  void testCorrelated() {
    assertThat(
        expanded("from x in [1, 2], y where y elem [x, x + 1]"),
        is(
            "projectMany\n" //
                + "  [1, 2]\n"
                + "  fn g$2 =>\n"
                + "    project [{x = g$2, y = $0}]\n"
                + "      [g$2, g$2 + 1]\n"));
  }

  /**
   * Tests that a condition reaches a leaf through a projection, which the step
   * list cannot do.
   *
   * <p>`from x yield {y = x} where y elem [2, 3]` errors today with "pattern
   * 'x' is not grounded"; a tree substitutes the projection into the condition
   * and grounds it. See discussion.md section 12.
   */
  @Test
  void testThroughProjection() {
    assertThat(
        generator("from x yield {y = x} where y elem [2, 3]"),
        is("[2, 3] : FINITE"));
  }

  /**
   * Tests that leaves which one generator binds together become one scan.
   *
   * <p>Replacing them separately would enumerate the collection once per leaf
   * and pair every value with every other; the step list makes them one scan
   * because the user wrote one pattern, and a tree has to notice.
   */
  @Test
  void testLeavesOneGeneratorBinds() {
    assertThat(
        expanded(
            "from i : int join j : string "
                + "where (i, j) elem [(1, \"a\"), (2, \"b\")]"),
        is(
            "project [{i = #1 $0, j = #2 $0}]\n" //
                + "  [(1, \"a\"), (2, \"b\")]\n"));
  }

  /**
   * Tests that a leaf nothing constrains is dropped when the rows are only
   * counted.
   *
   * <p>{@code 'a} cannot be enumerated, so grounding `w` is impossible; but
   * inside an `exists` the rows do not matter, and neither does `w`.
   */
  @Test
  void testRowsNotUsed() {
    final Fixture f = new Fixture("from w : int join x : int where x = 3");
    final Core.Exp expanded =
        RelExpander.expand(f.typeSystem, f.env, f.tree(), false);
    // `w` is gone, and so is the condition, which the generator enforces.
    assertThat(
        expanded instanceof Core.Rel
            ? ((Core.Rel) expanded).describe()
            : expanded + "\n",
        is("[3]\n"));
  }

  /**
   * Tests that a filter between two joins constrains the leaves below it.
   *
   * <p>The tree is a join whose left input is a filter over another join, so
   * the walk has to see through the filter to reach the leaves, and gather its
   * conjuncts as it passes: {@code j} is bounded by a condition above the
   * filter and {@code i} by one below it.
   */
  @Test
  void testFilterBetweenJoins() {
    assertThat(
        expanded(
            "from i : int join j : int where i elem [1, 2] "
                + "join k : int where j elem [3, 4] andalso k elem [5, 6]"),
        is(
            "join [{i = #i $0, j = #j $0, k = $1}]\n" //
                + "  join [{i = $0, j = $1}]\n"
                + "    [1, 2]\n"
                + "    [3, 4]\n"
                + "  [5, 6]\n"));
  }

  /**
   * Tests a generator that reads a name bound deeper in the left subtree.
   *
   * <p>{@code z} is correlated with {@code y}, which is not the join's left
   * input but a leaf inside it, so the {@code projectMany} binds the left
   * element and reads {@code y} out of it by path.
   */
  @Test
  void testCorrelatedWithSubtree() {
    assertThat(
        expanded(
            "from x in [1, 2] join y where y elem [x] "
                + "join z where z elem [y, y + 1]"),
        is(
            "projectMany\n" //
                + "  projectMany\n"
                + "    [1, 2]\n"
                + "    fn g$4 =>\n"
                + "      project [{x = g$4, y = $0}]\n"
                + "        [g$4]\n"
                + "  fn g$3 =>\n"
                + "    project [{x = #x g$3, y = #y g$3, z = $0}]\n"
                + "      [#y g$3, #y g$3 + 1]\n"));
  }

  /** Tests that a query that cannot be bounded is an error. */
  @Test
  void testExpandUnbounded() {
    try {
      final String plan = expanded("from x where x > 1");
      fail("expected error, got " + plan);
    } catch (CompileException e) {
      assertThat(e.getMessage(), containsString("pattern is not grounded"));
    }
  }

  /**
   * Tests that a condition reaches the leaf through the steps that do not
   * change the element, as it does in the step list: {@code from x take 3 where
   * x elem [1, 2, 3]} bounds x and then takes 3 of what remains.
   */
  @Test
  void testThroughOrderTakeSkip() {
    assertThat(
        generator("from x order x where x elem [1, 2, 3]"),
        is("[1, 2, 3] : FINITE"));
    assertThat(
        generator("from x take 3 where x elem [1, 2, 3]"),
        is("[1, 2, 3] : FINITE"));
    assertThat(
        generator("from x skip 1 where x elem [1, 2, 3]"),
        is("[1, 2, 3] : FINITE"));
  }
}

// End RelExpanderTest.java
