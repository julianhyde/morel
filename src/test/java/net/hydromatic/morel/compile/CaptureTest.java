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
import net.hydromatic.morel.type.FnType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.BiConsumer;

import static net.hydromatic.morel.ast.CoreBuilder.core;
import static net.hydromatic.morel.ast.Pos.ZERO;
import static net.hydromatic.morel.type.PrimitiveType.INT;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
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
                    core.apply(ZERO, INT, f.plus,
                        core.tuple(f.typeSystem, core.id(f.x),
                            core.intLiteral(BigDecimal.valueOf(2))))),
                core.apply(ZERO, INT, f.plus,
                    core.tuple(f.typeSystem, core.id(f.x), core.id(f.y)))));
    f.check(e, (e2, idList) -> assertThat(idList, empty()));
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
                        core.apply(ZERO, INT, f.plus,
                            core.tuple(f.typeSystem, core.id(f.x),
                                core.id(f.n))))),
                    core.apply(ZERO, INT, f.plus,
                        core.tuple(f.typeSystem, core.id(f.x),
                            core.apply(ZERO, INT, core.id(f.f), f.two)))));
    f.check(e, (e2, idList) -> {
      final String s = e2.toString();
      if (s.equals("let val f = fn n => op + (x, n) in op + (x, f 2) end")
          || s.equals("fn n => op + (x, n)")) {
        assertThat(idList, hasSize(1));
        assertThat(idList, hasItem(hasToString("x")));
      } else {
        assertThat(idList, empty());
      }
    });
  }

  @Test void testVariableCapture2() {
    // fun baz f =
    //   let
    //     fun foo 0 = 0
    //       | foo n = f n + foo (n - 1)
    //   in
    //     foo 5
    //   end;
    //
    // fn f =>
    //   let
    //     val foo = fn v0 =>
    //       case v0 of
    //           0 => 0
    //         | n => f n + foo (n - 1)
    //   in
    //     foo 5
    //   end
    Fixture f = new Fixture();
    final Type intToIntToInt =
        f.typeSystem.fnType(INT, INT, INT); // int -> int -> int
    Core.Exp e = null /*
        core.fn(INT_INT_INT, f.ffnType()f.x, f.one, ZERO),
        core.
        core.let(
            core.nonRecValDecl(f.x, f.one, ZERO),
            core.let(
                core.nonRecValDecl(f.f,
                    core.fn((FnType) f.f.type, f.n,
                        core.apply(ZERO, INT, f.plus,
                            core.tuple(f.typeSystem, core.id(f.x),
                                core.id(f.n)))),
                    ZERO),
                core.apply(ZERO, INT, f.plus,
                    core.tuple(f.typeSystem, core.id(f.x),
                        core.apply(ZERO, INT, core.id(f.f), f.two))))) */;
    f.check(e, (e2, idList) -> {
      final String s = e2.toString();
      if (s.equals("let val f = fn n => op + (x, n) in op + (x, f 2) end")
          || s.equals("fn n => op + (x, n)")) {
        assertThat(idList, hasSize(1));
        assertThat(idList, hasItem(hasToString("x")));
      } else {
        assertThat(idList, empty());
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

    @Override public void visit(Core.Let let) {
      consumer.accept(env, let);
      super.visit(let);
    }

    @Override public void visit(Core.Apply apply) {
      consumer.accept(env, apply);
      super.visit(apply);
    }

    @Override protected void visit(Core.Fn fn) {
      consumer.accept(env, fn);
      super.visit(fn);
    }
  }

  private static class Fixture {
    final TypeSystem typeSystem = new TypeSystem();
    final Core.IdPat f = core.idPat(typeSystem.fnType(INT, INT), "f", 0);
    final Core.IdPat n = core.idPat(INT, "n", 0);
    final Core.IdPat x = core.idPat(INT, "x", 0);
    final Core.IdPat y = core.idPat(INT, "y", 0);
    final Core.Literal plus = core.functionLiteral(typeSystem, BuiltIn.OP_PLUS);
    final Core.Literal one = core.intLiteral(BigDecimal.valueOf(1));
    final Core.Literal two = core.intLiteral(BigDecimal.valueOf(2));

    void check(Core.Exp e, BiConsumer<Core.Exp, List<Core.Id>> consumer) {
      final MyEnvVisitor v =
          new MyEnvVisitor(typeSystem, Environments.empty(), new ArrayDeque<>(),
              (env, exp) -> {
                final List<Core.Id> list = new ArrayList<>();
                final VariableCollector visitor =
                    VariableCollector.create(typeSystem, env, list::add);
                exp.accept(visitor);
                consumer.accept(exp, list);
              });
      e.accept(v);
    }
  }
}

// End EnvironmentTest.java
