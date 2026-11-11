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

import static java.util.Objects.requireNonNull;
import static net.hydromatic.morel.Ml.ml;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Exps;
import net.hydromatic.morel.compile.Tracers;
import org.junit.jupiter.api.Test;

/** Tests {@link Exps}, the ladder of predicates on {@link Core.Exp}. */
class ExpsTest {
  /**
   * The variables that an expression under test may read: {@code x}, a record;
   * {@code n}, an int; {@code r}, a real; {@code s}, a string; {@code opt}, an
   * int option.
   */
  private static final String PARAMS =
      "(x : {a:int, b:int}, n : int, r : real, s : string, opt : int option)";

  /**
   * Returns the {@link Core.Exp} that an expression becomes, after inlining and
   * the rules, in the scope of {@link #PARAMS}.
   */
  private static Core.Exp core(String exp) {
    final AtomicReference<Core.Decl> decl = new AtomicReference<>();
    ml("fn " + PARAMS + " => " + exp)
        .withTracer(Tracers.withOnCore(Tracers.empty(), -1, decl::set))
        .assertEval();
    // The resolver makes "fn (x, n, ...) => e" into "fn v => case v of (x,
    // n, ...) => e"; the expression under test is "e".
    Core.Exp e = ((Core.NonRecValDecl) requireNonNull(decl.get())).exp;
    for (; ; ) {
      if (e instanceof Core.Fn) {
        e = ((Core.Fn) e).exp;
      } else if (e instanceof Core.Case
          && ((Core.Case) e).matchList.size() == 1) {
        e = ((Core.Case) e).matchList.get(0).exp;
      } else {
        return e;
      }
    }
  }

  /**
   * Checks the predicates on an expression. {@code rung} is the lowest rung of
   * the ladder that accepts it: 0 for an atom, 1 for cheap, 2 for simple, 3 for
   * safe, and {@code Integer.MAX_VALUE} for none. Every rung above that one
   * must accept it too, and every rung below must not.
   */
  private static void check(String exp, int rung) {
    final Core.Exp e = core(exp);
    assertThat(exp + " isAtom", e.isAtom(), is(rung <= 0));
    assertThat(exp + " isCheap", e.isCheap(), is(rung <= 1));
    assertThat(exp + " isSimple", e.isSimple(), is(rung <= 2));
    assertThat(exp + " isSafe", e.isSafe(), is(rung <= 3));
  }

  private static final int ATOM = 0;
  private static final int CHEAP = 1;
  private static final int SIMPLE = 2;
  private static final int SAFE = 3;
  private static final int NONE = Integer.MAX_VALUE;

  @Test
  void testAtoms() {
    check("x", ATOM);
    check("n", ATOM);
    check("1", ATOM);
    check("1.5", ATOM);
    check("\"abc\"", ATOM);
    check("#\"c\"", ATOM);
    check("true", ATOM);
    check("()", ATOM);
    // A nullary constructor is a value literal.
    check("NONE", ATOM);
  }

  @Test
  void testCheap() {
    check("x.a", CHEAP);
    check("(n, s)", CHEAP);
    check("{p = x.a, q = n}", CHEAP);
    check("(x.a, (n, 1))", CHEAP);
  }

  @Test
  void testSimple() {
    // A constructor applied to an argument.
    check("SOME n", SIMPLE);
    check("SOME (x.a, n)", SIMPLE);
    check("(SOME n, s)", SIMPLE);
    check("[n, x.a]", NONE);
    check("SOME (n + 1)", NONE);
  }

  @Test
  void testSafe() {
    check("n < 3", SAFE);
    check("x.a = n", SAFE);
    check("opt = SOME n", SAFE);
    check("s <> \"a\"", SAFE);
    // A real comparison is safe; NaN compares, but does not raise.
    check("r < 1.5", SAFE);
    check("not (n > 3)", SAFE);
    check("n < 3 andalso x.a >= n orelse s = \"z\"", SAFE);
    check("SOME (n < 3)", SAFE);
    // A list is not simple, so neither is "elem" over one.
    check("n elem [1, 2]", NONE);
    // An operand that might raise makes the comparison unsafe.
    check("10 div n > 1", NONE);
    check("n + 1 < 3", NONE);
  }

  @Test
  void testNeither() {
    // Arithmetic, which can overflow.
    check("n + 1", NONE);
    check("10 div n", NONE);
    // A function call.
    check("String.size s", NONE);
    // A tuple with an expensive component.
    check("(n, n + 1)", NONE);
    check("let val y = n + 1 in y * y end", NONE);
    check("if n > 0 then 1 else 2", NONE);
  }
}

// End ExpsTest.java
