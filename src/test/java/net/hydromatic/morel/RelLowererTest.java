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
import net.hydromatic.morel.compile.RelLowerer;
import net.hydromatic.morel.compile.RelTranslator;
import net.hydromatic.morel.compile.Resolver;
import net.hydromatic.morel.compile.TypeResolver;
import net.hydromatic.morel.eval.Session;
import net.hydromatic.morel.parse.MorelParserImpl;
import net.hydromatic.morel.type.TypeSystem;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link RelLowerer}, which turns a relational tree back into the step
 * list that executes.
 *
 * <p>Where {@link RelTranslatorTest} pins down what the tree looks like, these
 * pin down what runs. The lowering carries a node's element as an expression
 * over the step list's bindings rather than materializing it, so a projection
 * costs no step; what these tests watch is where a {@code yield} appears
 * anyway, and that reading a field of an element the lowering is building
 * reduces to the field itself.
 */
public class RelLowererTest {
  /**
   * Compiles a query, translates the {@code from} it contains into a tree, and
   * returns the text of the step list that the tree lowers to. Returns null if
   * the translator declined.
   */
  private static @Nullable String lower(String ml) {
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
      // The resolver simplified the query away; there is nothing to lower.
      return null;
    }
    final Core.Exp rel = RelTranslator.toRel(typeSystem, froms[0]);
    if (rel == null) {
      return null;
    }
    final Core.Exp lowered = RelLowerer.lower(typeSystem, rel);
    assertThat(
        "lowered step list has the element type of the from",
        lowered.type,
        is(froms[0].type));
    return lowered.toString();
  }

  /** Tests that a filter over a leaf lowers to the two steps it began as. */
  @Test
  void testFilter() {
    assertThat(
        lower("from i in [1, 2, 3] where i > 1"),
        is("from v$0 in [1, 2, 3] where v$0 > 1"));
  }

  /**
   * Tests that a projection costs no step: the element is carried as an
   * expression and materialized once, by the final {@code yield}.
   */
  @Test
  void testProjectionCostsNoStep() {
    assertThat(
        lower("from i in [1, 2, 3] yield {j = i + 1}"),
        is("from v$0 in [1, 2, 3] yield {j = v$0 + 1}"));
  }

  /**
   * Tests that a field read of an element the lowering is building reduces to
   * the field itself, rather than selecting from a record constructed on the
   * spot.
   *
   * <p>Without the reduction the {@code where} would read {@code #j {j = v$0 +
   * 1}}: correct, but it builds a record per row to throw all but one field of
   * it away, and it hides the column from anything that reads the step list --
   * the Calcite translation, which pushes {@code #j v} down and cannot push
   * down a selector applied to a record it did not build.
   */
  @Test
  void testFieldOfProjectionIsRead() {
    assertThat(
        lower("from i in [1, 2, 3] yield {j = i + 1} where j > 2"),
        is("from v$0 in [1, 2, 3] where v$0 + 1 > 2 yield {j = v$0 + 1}"));
  }

  /** Tests the same reduction for a field of a join's element. */
  @Test
  void testFieldOfJoinIsRead() {
    assertThat(
        lower("from i in [1, 2], j in [3, 4] where i < j yield i + j"),
        is(
            "from v$1 in [1, 2] join v$2 in [3, 4] "
                + "where v$1 < v$2 yield v$1 + v$2"));
  }
}

// End RelLowererTest.java
