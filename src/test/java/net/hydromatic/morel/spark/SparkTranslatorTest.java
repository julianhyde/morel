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
package net.hydromatic.morel.spark;

import static java.util.Objects.requireNonNull;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import com.google.common.collect.ImmutableMap;
import com.google.common.io.Resources;
import java.io.IOException;
import java.io.StringReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import net.hydromatic.morel.BuiltInDataSet;
import net.hydromatic.morel.ast.Ast;
import net.hydromatic.morel.ast.AstNode;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Visitor;
import net.hydromatic.morel.compile.CompileException;
import net.hydromatic.morel.compile.Compiles;
import net.hydromatic.morel.compile.Environment;
import net.hydromatic.morel.compile.Environments;
import net.hydromatic.morel.compile.Inliner;
import net.hydromatic.morel.compile.Resolver;
import net.hydromatic.morel.compile.TypeResolver;
import net.hydromatic.morel.foreign.Calcite;
import net.hydromatic.morel.parse.MorelParserImpl;
import net.hydromatic.morel.type.TypeSystem;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link SparkTranslator} against the plans that {@code
 * spark/seed-plans.txt} records.
 *
 * <p>Those plans were captured from the PySpark client and executed against a
 * real server, so they are the authority: a plan Morel produces is right if
 * Spark would have produced it.
 */
public class SparkTranslatorTest {
  /** A query compiled as far as core, with the type system that compiled it. */
  private static class Fixture {
    final TypeSystem typeSystem = new TypeSystem();
    final Core.Rel rel;

    Fixture(String ml) {
      final MorelParserImpl parser = new MorelParserImpl(new StringReader(ml));
      final AstNode statement = parser.statementEofSafe();
      final Environment env =
          Environments.env(
              typeSystem,
              null,
              Calcite.withDataSets(
                      ImmutableMap.of("scott", BuiltInDataSet.SCOTT))
                  .foreignValues());
      final Ast.ValDecl valDecl = Compiles.toValDecl(statement);
      final Consumer<CompileException> ignoreWarnings = w -> {};
      final TypeResolver.Resolved resolved =
          TypeResolver.deduceType(env, valDecl, typeSystem, ignoreWarnings);
      final Resolver resolver = Resolver.of(resolved.typeMap, env, null);
      // Inline once, as Compiles does: until then a built-in is an Id,
      // not the function literal the translator matches on.
      final Core.ValDecl valDecl2 =
          (Core.ValDecl)
              resolver
                  .toCore((Ast.ValDecl) resolved.node)
                  .accept(Inliner.of(typeSystem, env, null));
      final Core.Rel[] rels = {null};
      valDecl2.accept(
          new Visitor() {
            @Override
            protected void visitRel(Core.Rel rel) {
              if (rels[0] == null) {
                rels[0] = rel;
              }
            }
          });
      rel = requireNonNull(rels[0], "no query in " + ml);
    }

    String plan() {
      return SparkTranslator.translate(typeSystem, rel).text();
    }
  }

  /** Returns the plan that {@code seed-plans.txt} records for a query. */
  private static String seedPlan(String name) throws IOException {
    final URL url =
        requireNonNull(
            SparkTranslatorTest.class.getResource("/spark/seed-plans.txt"),
            "seed-plans.txt");
    final String text = Resources.toString(url, StandardCharsets.UTF_8);
    final String header = "=== " + name + " ===\n";
    final int start = text.indexOf(header);
    if (start < 0) {
      throw new IllegalArgumentException("no section " + header.trim());
    }
    final int from = start + header.length();
    final int next = text.indexOf("\n=== ", from); // a header starts a line
    final String section =
        next < 0 ? text.substring(from) : text.substring(from, next);
    // Sections are separated by blank lines that are not part of a plan.
    return section.replaceAll("\\s+$", "");
  }

  /**
   * Query 1: filter and project over an inline relation. Pins the local
   * relation, a conjunctive filter, and a projection that renames and computes.
   */
  @Test
  void testQuery1() throws IOException {
    final Fixture f =
        new Fixture(
            "from r in [{i = 1, s = \"one\", b = true, x = 1.5},\n"
                + "           {i = 2, s = \"two\", b = false, x = 2.5},\n"
                + "           {i = 3, s = \"three\", b = true, x = 3.5}]\n"
                + "  where r.b andalso r.i > 1\n"
                + "  yield {r.s, y = r.x * 2.0}");
    assertThat(f.plan(), is(seedPlan("q1")));
  }

  /**
   * Query 2: equijoin. Pins the join relation, the qualification of a column
   * name that both inputs have, and projection over the concatenated row.
   */
  @Test
  void testQuery2() throws IOException {
    final Fixture f =
        new Fixture(
            "from e in scott.emps\n"
                + "  join d in scott.depts on e.deptno = d.deptno\n"
                + "  yield {e.ename, d.dname}");
    assertThat(f.plan(), is(seedPlan("q2")));
  }
}

// End SparkTranslatorTest.java
