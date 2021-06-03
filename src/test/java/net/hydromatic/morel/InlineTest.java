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

import org.junit.jupiter.api.Test;

import static net.hydromatic.morel.Ml.ml;

import static org.hamcrest.CoreMatchers.is;

/**
 * Test inlining and other optimizations.
 */
public class InlineTest {
  @Test void testAnalyze() {
    final String ml = "let\n"
        + "  val unused = 0\n"
        + "  val once1 = 1\n"
        + "  val once2 = 2\n"
        + "  val once3 = 3\n"
        + "  val twice = once1 + once2\n"
        + "  val multiSafe = once3\n"
        + "  val x = [1, 2]\n"
        + "  val z = case x of\n"
        + "     []            => multiSafe + 1\n"
        + "   | 1 :: x2 :: x3 => 2\n"
        + "   | x0 :: xs      => multiSafe + x0\n"
        + "in\n"
        + "  twice + twice\n"
        + "end";
    final String map = "{it=MULTI_UNSAFE, unused=DEAD, once1=ONCE_SAFE,"
        + " once2=ONCE_SAFE, once3=ONCE_SAFE, twice=MULTI_UNSAFE,"
        + " op +=MULTI_UNSAFE, multiSafe=MULTI_SAFE, x=ONCE_SAFE, z=DEAD,"
        + " x0=ONCE_SAFE, x2=DEAD, x3=DEAD, xs=DEAD}";
    ml(ml)
        .assertAnalyze(is(map));
  }

  @Test void testInline() {
    final String ml = "fun f x = let val y = x + 1 in y + 2 end";
    final String plan =
        "match(x, let1(matchCode match(y, apply(fnValue +, argCode tuple(get(name x), constant(1)))), resultCode apply(fnValue +, argCode tuple(get(name y), constant(2)))))";
    ml(ml).assertPlan(is(plan));
  }
}

// End InlineTest.java
