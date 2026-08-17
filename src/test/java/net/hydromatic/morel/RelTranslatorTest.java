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
            "project [#i $0 + #j $0]\n" //
                + "  join [{i = $0, j = $1}]\n"
                + "    [1, 2]\n"
                + "    [3, 4]\n"));
  }

  /**
   * Tests that a scan that depends on an earlier binder becomes a {@code
   * projectMany} whose lambda parameter is that binder.
   */
  @Test
  void testCorrelatedScan() {
    assertThat(
        plan("from i in [1, 2], j in [i, i + 1] yield {i, j}"),
        is(
            "projectMany\n" //
                + "  [1, 2]\n"
                + "  fn i =>\n"
                + "    project [{i = i, j = $0}]\n"
                + "      [i, i + 1]\n"));
  }

  /**
   * Tests {@code group}, {@code order}, {@code skip} and {@code take}.
   *
   * <p>The identity projection over the group is the {@code yield i} that the
   * resolver leaves in the step list, not something the translation adds.
   */
  @Test
  void testGroupOrderSkipTake() {
    assertThat(
        plan("from i in [1, 2, 3] group j = i"),
        is(
            "project [$0]\n" //
                + "  group [i = $0]\n"
                + "    [1, 2, 3]\n"));
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
   */
  @Test
  void testDestructuringScan() {
    assertThat(
        plan("from (i, j) in [(1, 2)] where i > j"),
        is(
            "filter [#i $0 > #j $0]\n" //
                + "  project [{i = #1 $0, j = #2 $0}]\n"
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
   * binds: it becomes a {@code projectMany} whose body yields one element where
   * the pattern matches and none where it does not.
   */
  @Test
  void testFailablePattern() {
    assertThat(
        plan("from (i, 2) in [(1, 2), (3, 4)]"),
        is(
            "projectMany\n" //
                + "  [(1, 2), (3, 4)]\n"
                + "  fn v$1 =>\n"
                + "    case v$1 of (i, 2) => [i] | _ => []\n"));
    assertThat(
        plan("from (x :: xs) in [[1, 2], []] yield x"),
        is(
            "project [#x $0]\n" //
                + "  projectMany\n"
                + "    [[1, 2], []]\n"
                + "    fn v$1 =>\n"
                + "      case v$1 of op ::((x, xs)) => [{x = x, xs = xs}] | _ => []\n"));
  }

  /**
   * Tests an outer apply: a correlated outer join, whose left element yields a
   * row even where its collection has nothing that matches.
   */
  @Test
  void testOuterApply() {
    System.out.println(
        plan(
            "from r in [{id = 1, items = [2]}] "
                + "left join i in r.items on i > 2"));
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
            "join [left] [$0 = $1] [{i = $0, j = $1}]\n" //
                + "  [1, 2, 3]\n"
                + "  [1, 2]\n"));
    assertThat(
        plan("from i in [1, 2] right join j in [3, 4] on i = j"),
        is(
            "join [right] [$0 = $1] [{i = $0, j = $1}]\n" //
                + "  [1, 2]\n"
                + "  [3, 4]\n"));
    assertThat(
        plan("from i in [1, 2] full join j in [3, 4] on i = j"),
        is(
            "join [full] [$0 = $1] [{i = $0, j = $1}]\n" //
                + "  [1, 2]\n"
                + "  [3, 4]\n"));
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
            "join [left] [$0 = #1 $1] "
                + "[{i = $0, j = #map Option (fn v$2 => #1 v$2) $1, "
                + "k = #map Option (fn v$3 => #2 v$3) $1}]\n"
                + "  [1, 2]\n"
                + "  [(1, 2)]\n"));
    assertThat(
        plan("from i in [1, 2] right join j in [3] right join k in [4]"),
        is(
            "join [right] [{i = #map Option (fn v$2 => #i v$2) $0, "
                + "j = #map Option (fn v$3 => #j v$3) $0, k = $1}]\n"
                + "  join [right] [{i = $0, j = $1}]\n"
                + "    [1, 2]\n"
                + "    [3]\n"
                + "  [4]\n"));
  }
}

// End RelTranslatorTest.java
