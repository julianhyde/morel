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

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;

import com.google.common.collect.ImmutableMap;
import java.io.StringReader;
import java.util.function.Consumer;
import net.hydromatic.morel.ast.Ast;
import net.hydromatic.morel.ast.AstNode;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Visitor;
import net.hydromatic.morel.compile.CompileException;
import net.hydromatic.morel.compile.Compiles;
import net.hydromatic.morel.compile.Environment;
import net.hydromatic.morel.compile.Environments;
import net.hydromatic.morel.compile.RelShadow;
import net.hydromatic.morel.compile.RelTranslator;
import net.hydromatic.morel.compile.RelValidator;
import net.hydromatic.morel.compile.Resolver;
import net.hydromatic.morel.compile.TypeResolver;
import net.hydromatic.morel.eval.Session;
import net.hydromatic.morel.parse.MorelParserImpl;
import net.hydromatic.morel.type.TypeSystem;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link RelTranslator}, which converts a {@code from} expression's step
 * list into a relational tree.
 *
 * <p>Each test compiles a query to Core, translates the {@code from} it
 * contains, checks the tree against the validator, and compares its plan text.
 * The tree is a shadow -- nothing executes it yet -- so what these tests pin
 * down is that the translation preserves the element type and eliminates the
 * binders in favour of {@code $0}.
 *
 * <p>Breadth comes from elsewhere: {@link
 * net.hydromatic.morel.compile.RelShadow} translates and checks every query
 * that the suite compiles, so the scripts exercise paths -- set-operator branch
 * alignment, for one -- that are awkward to write by hand.
 */
public class RelTranslatorTest {
  /**
   * Compiles a query, translates the {@code from} it contains, and returns the
   * tree's plan text. Returns null if the translator declined.
   */
  private static @Nullable String plan(String ml) {
    final MorelParserImpl parser = new MorelParserImpl(new StringReader(ml));
    final AstNode statement = parser.statementEofSafe();
    final TypeSystem typeSystem = new TypeSystem();
    final Session session = null;
    final Environment env =
        Environments.env(typeSystem, session, ImmutableMap.of());
    final Ast.ValDecl valDecl = Compiles.toValDecl(statement);
    final Consumer<CompileException> ignoreWarnings = w -> {};
    final TypeResolver.Resolved resolved =
        TypeResolver.deduceType(env, valDecl, typeSystem, ignoreWarnings);
    final Resolver resolver = Resolver.of(resolved.typeMap, env, null);
    final Core.ValDecl valDecl2 = resolver.toCore((Ast.ValDecl) resolved.node);

    final Core.From[] froms = {null};
    valDecl2.accept(
        new Visitor() {
          @Override
          protected void visit(Core.From from) {
            if (froms[0] == null) {
              froms[0] = from;
            }
            super.visit(from);
          }
        });
    if (froms[0] == null) {
      // The resolver simplified the query away; there is nothing to translate.
      return null;
    }

    // The shadow does the same for every query the test suite compiles; check
    // that it is happy with this one too.
    assertThat(RelShadow.check(typeSystem, valDecl2), is(true));

    final Core.Exp rel = RelTranslator.toRel(typeSystem, froms[0]);
    if (rel == null) {
      return null;
    }
    assertThat(
        "translated tree has the element type of the from",
        rel.type,
        is(froms[0].type));
    if (rel instanceof Core.Rel) {
      assertThat(RelValidator.violations(typeSystem, (Core.Rel) rel), empty());
      return ((Core.Rel) rel).describe();
    }
    return rel + "\n";
  }

  /**
   * Tests that a {@code where} step becomes a filter over a leaf, and that the
   * binder {@code i} becomes {@code $0}.
   */
  @Test
  void testFilter() {
    assertThat(
        plan("from i in [1, 2, 3] where i > 1"),
        is(
            "filter [$0 > 1]\n" //
                + "  [1, 2, 3]\n"));
  }

  /**
   * Tests that an independent scan becomes a join whose yield names both
   * binders, and that a later step reads them as fields of {@code $0}.
   */
  @Test
  void testJoin() {
    assertThat(
        plan("from i in [1, 2], j in [3, 4] yield i + j"),
        is(
            "project [#1 $0 + #2 $0]\n" //
                + "  join\n"
                + "    [1, 2]\n"
                + "    [3, 4]\n"));
  }

  /**
   * Tests that a scan that depends on an earlier binder becomes a join carrying
   * a binder, which its right input reads.
   */
  @Test
  void testCorrelatedScan() {
    assertThat(
        plan("from i in [1, 2], j in [i, i + 1] yield {i, j}"),
        is(
            "project [{i = #1 $0, j = #2 $0}]\n" //
                + "  join [i]\n"
                + "    [1, 2]\n"
                + "    [i, i + 1]\n"));
  }

  /**
   * Tests the one pattern that still needs a {@code case}: a user datatype's
   * constructor.
   *
   * <p>Its test is expressible as an expression, but extracting what it binds
   * is not -- there is no total accessor for a constructor's argument, and no
   * value to give the branch that does not match. So it stays a dependent join
   * whose right input yields one element where the pattern matches and none
   * where it does not. The list datatype escapes this because {@code null},
   * {@code hd} and {@code tl} are exactly the accessors it lacks.
   */
  @Test
  void testConstructorPattern() {
    assertThat(
        plan("from (SOME i) in [SOME 1, NONE] yield i"),
        is(
            "project [#2 $0]\n" //
                + "  join [v$0]\n"
                + "    [SOME 1, NONE]\n"
                + "    case v$0 of SOME(i) => [i] | _ => []\n"));
  }

  /**
   * Tests {@code group}, {@code order}, {@code skip} and {@code take}.
   *
   * <p>The identity projection over the group is the {@code yield i} that the
   * resolver leaves in the step list, not something the translation adds.
   *
   * <p>The projection below it is: the group builds a record whether it has one
   * label or many, and this query's value is the bare key, so an ordinary
   * projection of the field says so. That projection is a node a rule can see
   * and move, where a group that collapsed its own element was not
   * (discussion.md §14).
   */
  @Test
  void testGroupOrderSkipTake() {
    assertThat(
        plan("from i in [1, 2, 3] group j = i"),
        is(
            "project [$0]\n" //
                + "  project [#i $0]\n"
                + "    group [i = $0]\n"
                + "      [1, 2, 3]\n"));
    assertThat(
        plan("from i in [1, 2, 3] order i skip 1 take 1"),
        is(
            "take [1]\n" //
                + "  skip [1]\n"
                + "    sort [$0]\n"
                + "      [1, 2, 3]\n"));
  }

  /**
   * Tests that a scan whose pattern destructures becomes a projection that
   * builds the element the bindings describe.
   *
   * <p>The filter reads the components rather than the record, and so comes
   * first: a pattern binds paths into the element, and the projection that
   * makes the record is owed only to whatever wants the row.
   */
  @Test
  void testDestructuringScan() {
    assertThat(
        plan("from (i, j) in [(1, 2)] where i > j"),
        is(
            "project [{i = #1 $0, j = #2 $0}]\n" //
                + "  filter [#1 $0 > #2 $0]\n"
                + "    [(1, 2)]\n"));
  }

  /**
   * Tests a set operator, whose inputs are the tree so far and the step's
   * arguments.
   */
  @Test
  void testUnion() {
    assertThat(
        plan("from i in [1, 2] union [3]"),
        is(
            "union [all]\n" //
                + "  [1, 2]\n"
                + "  [3]\n"));
  }

  /**
   * Tests a {@code from} with no scan, which iterates over a single unit
   * element.
   */
  @Test
  void testNoScan() {
    assertThat(
        plan("from yield 1 + 2"),
        is(
            "project [1 + 2]\n" //
                + "  [()]\n"));
  }

  /**
   * Tests a scan whose pattern can fail to match, which filters as well as
   * binds. The two halves separate into ordinary nodes: a filter for the
   * condition, and the projection that the bindings need anyway.
   */
  @Test
  void testFailablePattern() {
    assertThat(
        plan("from (i, 2) in [(1, 2), (3, 4)]"),
        is(
            "project [#1 $0]\n" //
                + "  filter [#2 $0 = 2]\n"
                + "    [(1, 2), (3, 4)]\n"));
    // `::` is a constructor, but the list datatype has the total accessors a
    // user datatype lacks, so a cons pattern takes the same path: `null` is
    // the test, `hd` and `tl` are the paths.
    assertThat(
        plan("from (x :: xs) in [[1, 2], []] yield x"),
        is(
            "project [#x $0]\n" //
                + "  project [{x = #hd List $0, xs = #tl List $0}]\n"
                + "    filter [not (#null List $0)]\n"
                + "      [[1, 2], []]\n"));

    // An empty-list pattern is the test alone; it binds nothing, so the
    // element the bindings describe is unit.
    assertThat(
        plan("from [] in [[1], []] yield 1"),
        is(
            "project [1]\n" //
                + "  project [()]\n"
                + "    filter [#null List $0]\n"
                + "      [[1], []]\n"));
  }

  /**
   * Tests an outer apply: a correlated outer join, whose left element yields a
   * row even where its collection has nothing that matches.
   *
   * <p>It needs no special construction. A dependent join whose kind is {@code
   * left} emits that row already, because that is what {@code left} means, so
   * the {@code ifEmpty} that an earlier design put inside the lambda is gone.
   */
  @Test
  void testOuterApply() {
    assertThat(
        plan(
            "from r in [{id = 1, items = [2]}] "
                + "left join i in r.items on i > 2"),
        is(
            "project [{i = #2 $0, r = #1 $0}]\n" //
                + "  join [left] [r] [$1 > 2]\n"
                + "    [{id = 1, items = [2]}]\n"
                + "    #items r\n"));
  }

  /**
   * Tests that the shadow runs. It is an {@code assert} statement, so it does
   * nothing unless the test JVM enables assertions.
   */
  @Test
  void testAssertionsEnabled() {
    boolean assertionsEnabled = false;
    assert assertionsEnabled = true;
    assertThat(
        "the relational-tree shadow only runs when assertions are enabled",
        assertionsEnabled,
        is(true));
  }

  /**
   * Tests an outer join: the condition sees both elements as they are, and the
   * yield sees an option on the side that can be absent.
   */
  @Test
  void testOuterJoin() {
    assertThat(
        plan("from i in [1, 2, 3] left join j in [1, 2] on i = j"),
        is(
            "project [{i = #1 $0, j = #2 $0}]\n" //
                + "  join [left] [$0 = $1]\n"
                + "    [1, 2, 3]\n"
                + "    [1, 2]\n"));
    assertThat(
        plan("from i in [1, 2] right join j in [3, 4] on i = j"),
        is(
            "project [{i = #1 $0, j = #2 $0}]\n" //
                + "  join [right] [$0 = $1]\n"
                + "    [1, 2]\n"
                + "    [3, 4]\n"));
    assertThat(
        plan("from i in [1, 2] full join j in [3, 4] on i = j"),
        is(
            "project [{i = #1 $0, j = #2 $0}]\n" //
                + "  join [full] [$0 = $1]\n"
                + "    [1, 2]\n"
                + "    [3, 4]\n"));
  }

  /**
   * Tests an outer join whose absent side has more than one binder.
   *
   * <p>Morel makes each binder an option, not the side as a whole, so the yield
   * maps each access through the option. A chained outer join nests them:
   * {@code i} is an {@code int option option}.
   */
  @Test
  void testOuterJoinMultipleBinders() {
    assertThat(
        plan("from i in [1, 2] left join (j, k) in [(1, 2)] on i = j"),
        is(
            "project [{i = #1 $0, "
                + "j = #map Option (fn v$0 => #1 v$0) (#2 $0), "
                + "k = #map Option (fn v$1 => #2 v$1) (#2 $0)}]\n"
                + "  join [left] [$0 = #1 $1]\n"
                + "    [1, 2]\n"
                + "    [(1, 2)]\n"));
  }
}

// End RelTranslatorTest.java
