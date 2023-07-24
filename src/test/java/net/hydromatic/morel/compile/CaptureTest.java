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

import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.type.FnType;
import net.hydromatic.morel.type.TypeSystem;

import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;

import static net.hydromatic.morel.ast.CoreBuilder.core;
import static net.hydromatic.morel.ast.Pos.ZERO;
import static net.hydromatic.morel.compile.BuiltIn.OP_MINUS;
import static net.hydromatic.morel.compile.BuiltIn.OP_PLUS;
import static net.hydromatic.morel.type.PrimitiveType.INT;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.anEmptyMap;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasToString;

/**
 * Tests that {@link VariableCollector} correctly deduces which variables are
 * free in various expressions.
 */
public class CaptureTest {
  @Test void testVariableCapture0() {
    // let
    //   val x = 1
    // in
    //   let
    //     val y = x + 2
    //   in
    //     x + y
    //   end
    // end
    Fixture f = new Fixture();
    Core.Let e =
        core.let(
            core.nonRecValDecl(ZERO, f.x, f.one),
            core.let(
                core.nonRecValDecl(ZERO, f.y,
                    core.apply(ZERO, INT, f.typeSystem, OP_PLUS,
                        core.id(f.x), core.intLiteral(BigDecimal.valueOf(2)))),
                core.apply(ZERO, INT, f.typeSystem, OP_PLUS, core.id(f.x),
                    core.id(f.y))));
    f.check(e, (e2, idList) -> assertThat(idList, anEmptyMap()));
  }

  @Test void testVariableCapture1() {
    // let
    //   val x = 1
    // in
    //   let
    //     val f = fn n => x + n
    //   in
    //     x + f 2
    //   end
    // end
    Fixture f = new Fixture();
    Core.Let e =
        core.let(
            core.nonRecValDecl(ZERO, f.x, f.one),
            core.let(
                core.nonRecValDecl(ZERO, f.f,
                    core.fn((FnType) f.f.type, f.n,
                        core.apply(ZERO, INT, f.typeSystem, OP_PLUS,
                            core.id(f.x), core.id(f.n)))),
                    core.apply(ZERO, INT, f.typeSystem, OP_PLUS, core.id(f.x),
                        core.apply(ZERO, INT, core.id(f.f), f.two))));
    f.check(e, (e2, idList) -> {
      final String s = e2.toString();
      if (s.equals("let val f = fn n => op + (x, n) in op + (x, f 2) end")
          || s.equals("fn n => op + (x, n)")) {
        assertThat(idList, aMapWithSize(1));
        assertThat(idList, hasKey(hasToString("x")));
      } else {
        assertThat(idList, anEmptyMap());
      }
    });
  }

  @Test void testVariableCapture2() {
    // fun baz f =
    //   let
    //     fun foo 0 = 0
    //       | foo n = f n + foo (n - 1)
    //   in
    //     foo 2
    //   end;
    //
    // which in core is equivalent to:
    //
    // fn f =>
    //   let
    //     val rec foo = fn v0 =>
    //       case v0 of
    //           0 => 0
    //         | n => f n + foo (n - 1)
    //   in
    //     foo 2
    //   end
    Fixture f = new Fixture();
    final FnType intToIntToInt =
        (FnType) f.typeSystem.fnType(INT, INT, INT); // int -> int -> int
    Core.Exp e =
        core.fn(intToIntToInt, f.f,
            core.let(
                core.recValDecl(
                    ImmutableList.of(
                        core.nonRecValDecl(ZERO, f.foo,
                            core.fn((FnType) f.foo.type, f.v0,
                                core.caseOf(ZERO, f.foo.type, core.id(f.v0),
                                    ImmutableList.of(
                                        core.match(ZERO,
                                            core.literalPat(Op.INT_LITERAL_PAT,
                                                INT, 0),
                                            core.intLiteral(BigDecimal.ZERO)),
                                        core.match(ZERO, f.n,
                                            core.apply(ZERO, INT, f.typeSystem,
                                                OP_PLUS,
                                                core.apply(ZERO, INT,
                                                    core.id(f.f), core.id(f.n)),
                                                core.apply(ZERO, INT,
                                                    core.id(f.foo),
                                                    core.apply(ZERO, INT,
                                                        f.typeSystem, OP_MINUS,
                                                        core.id(f.n),
                                                        f.one)))))))))),
                core.apply(ZERO, INT, core.id(f.f), f.two)));
    f.check(e, (e2, idList) -> {
      final String s = e2.toString();
      String z = "fn v0 => case v0 of 0 => 0"
          + " | n => op + (f n, foo (op - (n, 1)))";
      if (s.equals(z)) {
        assertThat(idList, aMapWithSize(1));
        assertThat(idList, hasKey(hasToString("foo")));
      } else if (s.equals("let val foo = " + z + " in f 2 end")) {
        assertThat(idList, aMapWithSize(1));
        assertThat(idList, hasKey(hasToString("f")));
      } else {
        assertThat(idList, anEmptyMap());
      }
    });
  }

  /** Visitor that walks over an expression tree and applies an action on every
   * node, with the appropriate environment. */
  private static class MyEnvVisitor extends EnvVisitor {
    private final BiConsumer<Environment, Core.Exp> consumer;

    MyEnvVisitor(TypeSystem typeSystem, Environment env,
        Deque<FromContext> fromStack,
        BiConsumer<Environment, Core.Exp> consumer) {
      super(typeSystem, env, fromStack);
      this.consumer = consumer;
    }

    @Override protected MyEnvVisitor push(Environment env) {
      return new MyEnvVisitor(typeSystem, env, fromStack, consumer);
    }

    @Override public void visit(Core.Id id) {
      consumer.accept(env, id);
      super.visit(id);
    }

    @Override public void visit(Core.Apply apply) {
      consumer.accept(env, apply);
      super.visit(apply);
    }

    @Override public void visit(Core.Case caseOf) {
      consumer.accept(env, caseOf);
      super.visit(caseOf);
    }

    @Override protected void visit(Core.Fn fn) {
      consumer.accept(env, fn);
      super.visit(fn);
    }

    @Override public void visit(Core.Let let) {
      consumer.accept(env, let);
      super.visit(let);
    }
  }

  private static class Fixture {
    final TypeSystem typeSystem = new TypeSystem();
    final Core.IdPat f = core.idPat(typeSystem.fnType(INT, INT), "f", 0);
    final Core.IdPat foo = core.idPat(f.type, "foo", 0);
    final Core.IdPat n = core.idPat(INT, "n", 0);
    final Core.IdPat v0 = core.idPat(INT, "v0", 0);
    final Core.IdPat x = core.idPat(INT, "x", 0);
    final Core.IdPat y = core.idPat(INT, "y", 0);
    final Core.Literal one = core.intLiteral(BigDecimal.valueOf(1));
    final Core.Literal two = core.intLiteral(BigDecimal.valueOf(2));

    void check(Core.Exp e,
        BiConsumer<Core.Exp, Map<Core.Id, VariableCollector.Scope>> consumer) {
      final MyEnvVisitor v =
          new MyEnvVisitor(typeSystem, Environments.empty(), new ArrayDeque<>(),
              (env, exp) -> {
                final Map<Core.Id, VariableCollector.Scope> scopeMap =
                    new LinkedHashMap<>();
                final VariableCollector visitor =
                    VariableCollector.create(typeSystem, env, scopeMap::put);
                exp.accept(visitor);
                consumer.accept(exp, scopeMap);
              });
      e.accept(v);
    }
  }
}

// End EnvironmentTest.java
