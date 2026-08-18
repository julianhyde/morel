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
   * Compiles a query, translates it to a tree, and returns the generator of its
   * first infinite-extent leaf, as a string.
   */
  private static String generator(String ml) {
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
    // Inline once, as Compiles does: until then a built-in such as 'elem' is
    // an Id, not the function literal that the engine matches on.
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
    final Core.Exp tree = RelTranslator.toRel(typeSystem, froms[0]);
    final Map<Core.Exp, Generator> generators =
        RelExpander.ground(typeSystem, env, tree);
    if (generators.isEmpty()) {
      return "no extent";
    }
    final Generator generator = generators.values().iterator().next();
    return generator == null
        ? "not grounded"
        : generator.exp + " : " + generator.cardinality;
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
}

// End RelExpanderTest.java
