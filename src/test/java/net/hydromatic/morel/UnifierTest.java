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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasToString;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsInstanceOf.instanceOf;
import static org.hamcrest.core.IsNot.not;
import static org.hamcrest.core.IsNull.notNullValue;
import static org.hamcrest.core.IsSame.sameInstance;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.hydromatic.morel.util.MartelliUnifier;
import net.hydromatic.morel.util.RobinsonUnifier;
import net.hydromatic.morel.util.Tracers;
import net.hydromatic.morel.util.Unifier;
import net.hydromatic.morel.util.Unifiers;
import org.hamcrest.Matcher;
import org.junit.jupiter.api.Test;

/** Test for {@link RobinsonUnifier}. */
public abstract class UnifierTest {
  final Unifier unifier = createUnifier();

  protected abstract Unifier createUnifier();

  Unifier.Sequence arrow(Unifier.Term t0, Unifier.Term t1) {
    return unifier.apply("->", t0, t1);
  }

  Unifier.Sequence a(Unifier.Term... terms) {
    return unifier.apply("a", terms);
  }

  Unifier.Sequence b(Unifier.Term... terms) {
    return unifier.apply("b", terms);
  }

  private Unifier.Sequence c(Unifier.Term... terms) {
    return unifier.apply("c", terms);
  }

  private Unifier.Sequence d(Unifier.Term... terms) {
    return unifier.apply("d", terms);
  }

  private Unifier.Sequence f(Unifier.Term... terms) {
    return unifier.apply("f", terms);
  }

  private Unifier.Sequence g(Unifier.Term... terms) {
    return unifier.apply("g", terms);
  }

  private Unifier.Sequence h(Unifier.Term... terms) {
    return unifier.apply("h", terms);
  }

  private Unifier.Sequence p(Unifier.Term... terms) {
    return unifier.apply("p", terms);
  }

  private Unifier.Sequence bill(Unifier.Term... terms) {
    return unifier.apply("bill", terms);
  }

  private Unifier.Sequence bob(Unifier.Term... terms) {
    return unifier.apply("bob", terms);
  }

  private Unifier.Sequence john(Unifier.Term... terms) {
    return unifier.apply("john", terms);
  }

  private Unifier.Sequence tom(Unifier.Term... terms) {
    return unifier.apply("tom", terms);
  }

  private Unifier.Sequence father(Unifier.Term... terms) {
    return unifier.apply("father", terms);
  }

  private Unifier.Sequence mother(Unifier.Term... terms) {
    return unifier.apply("mother", terms);
  }

  private Unifier.Sequence parents(Unifier.Term... terms) {
    return unifier.apply("parents", terms);
  }

  private Unifier.Sequence parent(Unifier.Term... terms) {
    return unifier.apply("parent", terms);
  }

  private Unifier.Sequence grandParent(Unifier.Term... terms) {
    return unifier.apply("grandParent", terms);
  }

  private Unifier.Sequence connected(Unifier.Term... terms) {
    return unifier.apply("connected", terms);
  }

  private Unifier.Sequence part(Unifier.Term... terms) {
    return unifier.apply("part", terms);
  }

  // Turn off checkstyle, because non-static fields are conventionally
  // lower-case.
  // CHECKSTYLE: IGNORE 4
  final Unifier.Variable X = unifier.variable("X");
  private final Unifier.Variable Y = unifier.variable("Y");
  private final Unifier.Variable W = unifier.variable("W");
  private final Unifier.Variable Z = unifier.variable("Z");

  void assertThatUnify(
      Unifier.Term e1, Unifier.Term e2, Matcher<String> matcher) {
    assertThatUnify(termPairs(e1, e2), matcher);
  }

  void assertThatUnify(
      List<Unifier.TermTerm> termPairs, Matcher<String> matcher) {
    final Unifier.Result result =
        unifier.unify(
            termPairs,
            ImmutableMap.of(),
            ImmutableList.of(),
            Tracers.nullTracer());
    assertThat(result, notNullValue());
    assertThat(result instanceof Unifier.Substitution, is(true));
    assertThat(((Unifier.Substitution) result).resolve().toString(), matcher);
  }

  void assertThatCannotUnify(Unifier.Term e1, Unifier.Term e2) {
    assertThatCannotUnify(termPairs(e1, e2));
  }

  /** Given [a, b, c, d], returns [(a, b), (c, d)]. */
  List<Unifier.TermTerm> termPairs(Unifier.Term... terms) {
    assert terms.length % 2 == 0;
    final ImmutableList.Builder<Unifier.TermTerm> pairs =
        ImmutableList.builder();
    for (int i = 0; i < terms.length; i += 2) {
      pairs.add(new Unifier.TermTerm(terms[i], terms[i + 1]));
    }
    return pairs.build();
  }

  void assertThatCannotUnify(List<Unifier.TermTerm> pairList) {
    final Unifier.Result result =
        unifier.unify(
            pairList,
            ImmutableMap.of(),
            ImmutableList.of(),
            Tracers.nullTracer());
    assertThat(result, not(instanceOf(Unifier.Substitution.class)));
  }

  @Test
  void testAtom() {
    final Unifier.Term a0 = unifier.atomUnique("A");
    assertThat(a0, hasToString("A0"));
    final Unifier.Term a1 = unifier.atomUnique("A");
    assertThat(a1, hasToString("A1"));
    final Unifier.Term v0 = unifier.variable();
    assertThat(v0, hasToString("T0"));
    final Unifier.Term a2 = unifier.atomUnique("T");
    assertThat(a2, hasToString("T1"));
    final Unifier.Term a3 = unifier.atomUnique("T1");
    assertThat(a3, hasToString("T10"));
    final Unifier.Term v1 = unifier.variable();
    assertThat(v1, hasToString("T2"));
    final Unifier.Term v2 = unifier.variable();
    final Unifier.Variable v1b = unifier.variable(v1.toString());
    assertThat(v1b, sameInstance(v1));
    final Unifier.Variable v1c = unifier.variable(2);
    assertThat(v1c, sameInstance(v1));
    final Unifier.Variable v2a = unifier.variable(3);
    assertThat(v2a, sameInstance(v2));
    final Unifier.Term v3 = unifier.variable();
    final Unifier.Term v4 = unifier.variable();
    final Unifier.Term v5 = unifier.variable();
    final Unifier.Term v6 = unifier.variable();
    final Unifier.Variable v4a = unifier.variable(5);
    assertThat(v4a, sameInstance(v4));
    final Unifier.Term v7 = unifier.variable();
    final Unifier.Term v8 = unifier.variable();
    assertThat(
        Arrays.asList(v0, v1, v2, v3, v4, v5, v6, v7, v8),
        hasToString("[T0, T2, T3, T4, T5, T6, T7, T8, T9]"));
    final Unifier.Term v9 = unifier.variable();
    assertThat("avoids T10 name used by a3 above", v9, hasToString("T11"));
  }

  @Test
  void test1() {
    final Unifier.Term e1 = p(f(a()), g(b()), Y);
    final Unifier.Term e2 = p(Z, g(d()), c());
    assertThat(e1, hasToString("p(f(a), g(b), Y)"));
    assertThat(unifier.substitution(f(a(), Y), Z), hasToString("[f(a, Y)/Z]"));
    assertThatCannotUnify(e1, e2);
  }

  @Test
  void test2() {
    final Unifier.Term e1 = p(f(a()), g(b()), Y);
    final Unifier.Term e2 = p(Z, g(W), c());
    assertThatUnify(e1, e2, is("[b/W, c/Y, f(a)/Z]"));
  }

  @Test
  void test3() {
    // Note: Hesham Alassaf's test says that these cannot be unified; I think
    // because X is free, and so it assumes that Xs are distinct.
    final Unifier.Term e1 = p(f(f(b())), X);
    final Unifier.Term e2 = p(f(Y), X);
    if (unifier instanceof RobinsonUnifier) {
      assertThatUnify(e1, e2, is("[X/X, f(b)/Y]"));
    } else {
      assertThatUnify(e1, e2, is("[f(b)/Y]"));
    }
  }

  @Test
  void test4() {
    final Unifier.Term e1 = p(f(f(b())), c());
    final Unifier.Term e2 = p(f(Y), X);
    assertThatUnify(e1, e2, is("[c/X, f(b)/Y]"));
  }

  @Test
  void test5() {
    final Unifier.Term e1 = p(a(), X);
    final Unifier.Term e2 = p(b(), Y);
    assertThatCannotUnify(e1, e2);
  }

  @Test
  void test6() {
    final Unifier.Term e1 = p(X, a());
    final Unifier.Term e2 = p(b(), Y);
    assertThatUnify(e1, e2, is("[b/X, a/Y]"));
  }

  @Test
  void test7() {
    final Unifier.Term e1 = f(a(), X);
    final Unifier.Term e2 = f(a(), b());
    assertThatUnify(e1, e2, is("[b/X]"));
  }

  @Test
  void test8() {
    final Unifier.Term e1 = f(X);
    final Unifier.Term e2 = f(Y);
    assertThatUnify(e1, e2, is("[Y/X]"));
  }

  @Test
  void test9() {
    final Unifier.Term e1 = f(g(X), X);
    final Unifier.Term e2 = f(Y);
    assertThatCannotUnify(e1, e2);
  }

  @Test
  void test10() {
    final Unifier.Term e1 = f(g(X));
    final Unifier.Term e2 = f(Y);
    assertThatUnify(e1, e2, is("[g(X)/Y]"));
  }

  @Test
  void test11() {
    final Unifier.Term e1 = f(g(X), X);
    final Unifier.Term e2 = f(Y, a());
    assertThatUnify(e1, e2, is("[a/X, g(a)/Y]"));
  }

  @Test
  void test12() {
    final Unifier.Term e1 = father(X, Y);
    final Unifier.Term e2 = father(bob(), tom());
    assertThatUnify(e1, e2, is("[bob/X, tom/Y]"));
  }

  @Test
  void test13() {
    final Unifier.Term e1 = parents(X, father(X), mother(bill()));
    final Unifier.Term e2 = parents(bill(), father(bill()), Y);
    assertThatUnify(e1, e2, is("[bill/X, mother(bill)/Y]"));
  }

  @Test
  void test14() {
    final Unifier.Term e1 = grandParent(X, parent(parent(X)));
    final Unifier.Term e2 = grandParent(john(), parent(Y));
    assertThatUnify(e1, e2, is("[john/X, parent(john)/Y]"));
  }

  @Test
  void test15() {
    final Unifier.Term e1 = p(f(a(), g(X)));
    final Unifier.Term e2 = p(Y, Y);
    assertThatCannotUnify(e1, e2);
  }

  @Test
  void test16() {
    final Unifier.Term e1 = p(a(), X, h(g(Z)));
    final Unifier.Term e2 = p(Z, h(Y), h(Y));
    assertThatUnify(e1, e2, is("[h(g(a))/X, g(a)/Y, a/Z]"));
  }

  @Test
  void test17() {
    final Unifier.Term e1 = p(X, X);
    final Unifier.Term e2 = p(Y, f(Y));
    if (unifier.occurs()) {
      assertThatCannotUnify(e1, e2);
    } else if (unifier instanceof RobinsonUnifier) {
      assertThatUnify(e1, e2, is("[Y/X, f(Y)/Y]"));
    } else {
      assertThatCannotUnify(e1, e2);
    }
  }

  @Test
  void test18() {
    final Unifier.Term e1 = part(W, X);
    final Unifier.Term e2 = connected(f(W, X), W);
    assertThatCannotUnify(e1, e2);
  }

  @Test
  void test19() {
    final Unifier.Term e1 = p(f(X), a(), Y);
    final Unifier.Term e2 = p(f(bill()), Z, g(b()));
    assertThatUnify(e1, e2, is("[bill/X, g(b)/Y, a/Z]"));
  }

  /** Tests {@link Unifiers#dump(PrintWriter, Iterable)}. */
  @Test
  void testUnifierDump() {
    List<Unifier.TermTerm> pairs = new ArrayList<>();
    final Unifier.Term intAtom = unifier.atom("int");
    final Unifier.Variable t5 = unifier.variable(5);
    pairs.add(new Unifier.TermTerm(intAtom, t5));
    StringWriter sw = new StringWriter();
    Unifiers.dump(new PrintWriter(sw), pairs);
    String expected =
        "List<Unifier.TermTerm> pairs = new ArrayList<>();\n"
            + "final Unifier.Term int = unifier.atom(\"int\");\n"
            + "final Unifier.Variable t5 = unifier.variable(5);\n"
            + "pairs.add(new Unifier.TermTerm(int, t5));\n";
    assertThat(sw, hasToString(expected));
  }

  /**
   * Variant of test that uses {@link
   * net.hydromatic.morel.util.RobinsonUnifier}.
   */
  public static class RobinsonUnifierTest extends UnifierTest {
    protected Unifier createUnifier() {
      return new RobinsonUnifier();
    }
  }

  /**
   * Variant of test that uses {@link
   * net.hydromatic.morel.util.MartelliUnifier}.
   */
  public static class MartelliUnifierTest extends UnifierTest {
    protected Unifier createUnifier() {
      return new MartelliUnifier();
    }

    /**
     * Solves the equations from the S combinator, "{@code fn x => fn y => fn z
     * => x z (z y)}", in [<a href=
     * "https://web.cs.ucla.edu/~palsberg/course/cs239/reading/wand87.pdf"> Wand
     * 87</a>].
     */
    @Test
    void test20() {
      final Unifier.Variable t0 = unifier.variable(0);
      final Unifier.Variable t1 = unifier.variable(1);
      final Unifier.Variable t2 = unifier.variable(2);
      final Unifier.Variable t3 = unifier.variable(3);
      final Unifier.Variable t4 = unifier.variable(4);
      final Unifier.Variable t5 = unifier.variable(5);
      final Unifier.Variable t6 = unifier.variable(6);
      final Unifier.Variable t7 = unifier.variable(7);
      final Unifier.Variable t8 = unifier.variable(8);
      final Unifier.Variable t9 = unifier.variable(9);
      final Unifier.TermTerm[] termTerms = {
        new Unifier.TermTerm(t0, arrow(t1, t2)),
        new Unifier.TermTerm(t2, arrow(t3, t4)),
        new Unifier.TermTerm(t4, arrow(t5, t6)),
        new Unifier.TermTerm(t1, arrow(t8, arrow(t7, t6))),
        new Unifier.TermTerm(t8, t5),
        new Unifier.TermTerm(arrow(t9, t7), t3),
        new Unifier.TermTerm(t9, t5)
      };
      final Unifier.Result unify =
          unifier.unify(
              Arrays.asList(termTerms),
              ImmutableMap.of(),
              ImmutableList.of(),
              Tracers.nullTracer());
      assertThat(unify, notNullValue());
      assertThat(unify instanceof Unifier.Substitution, is(true));
      assertThat(
          unify.toString(),
          is(
              "[->(T1, T2)/T0, ->(T8, ->(T7, T6))/T1, ->(T3, T4)/T2,"
                  + " ->(T9, T7)/T3, ->(T5, T6)/T4, T5/T8, T5/T9]"));
    }

    @Test
    void testAtomEqAtom() {
      assertThatCannotUnify(termPairs(b(), X, a(), X));
    }

    @Test
    void testAtomEqAtom2() {
      assertThatCannotUnify(termPairs(a(), X, a(), X, b(), X));
    }

    @Test
    void testAtomEqAtom3() {
      assertThatUnify(termPairs(a(), X, a(), X), is("[a/X]"));
    }

    @Test
    void testOverload() {
      List<Unifier.TermTerm> pairs = new ArrayList<>();
      final Unifier.Term intAtom = unifier.atom("int");
      final Unifier.Variable t5 = unifier.variable(5);
      pairs.add(new Unifier.TermTerm(intAtom, t5));
      final Unifier.Variable t4 = unifier.variable(4);
      pairs.add(new Unifier.TermTerm(t5, t4));
      final Unifier.Sequence fn1 = unifier.apply("fn", t5, t4);
      final Unifier.Variable t3 = unifier.variable(3);
      pairs.add(new Unifier.TermTerm(fn1, t3));
      final Unifier.Variable t6 = unifier.variable(6);
      final Unifier.Variable t7 = unifier.variable(7);
      final Unifier.Sequence fn11 = unifier.apply("fn", t6, t7);
      pairs.add(new Unifier.TermTerm(fn11, t3));
      final Unifier.Sequence fn21 = unifier.apply("fn", t3, t3);
      final Unifier.Variable t2 = unifier.variable(2);
      pairs.add(new Unifier.TermTerm(fn21, t2));
      final Unifier.Term bool = unifier.atom("bool");
      final Unifier.Variable t11 = unifier.variable(11);
      pairs.add(new Unifier.TermTerm(bool, t11));
      final Unifier.Variable t10 = unifier.variable(10);
      pairs.add(new Unifier.TermTerm(t11, t10));
      final Unifier.Sequence fn31 = unifier.apply("fn", t11, t10);
      final Unifier.Variable t9 = unifier.variable(9);
      pairs.add(new Unifier.TermTerm(fn31, t9));
      final Unifier.Variable t12 = unifier.variable(12);
      final Unifier.Variable t13 = unifier.variable(13);
      final Unifier.Sequence fn41 = unifier.apply("fn", t12, t13);
      pairs.add(new Unifier.TermTerm(fn41, t9));
      final Unifier.Sequence fn51 = unifier.apply("fn", t9, t9);
      final Unifier.Variable t8 = unifier.variable(8);
      pairs.add(new Unifier.TermTerm(fn51, t8));
      final Unifier.Variable t15 = unifier.variable(15);
      final Unifier.Variable t1 = unifier.variable(1);
      final Unifier.Sequence fn61 = unifier.apply("fn", t15, t1);
      final Unifier.Variable t14 = unifier.variable(14);
      pairs.add(new Unifier.TermTerm(fn61, t14));
      pairs.add(new Unifier.TermTerm(bool, t15));
      final Unifier.Sequence fn71 = unifier.apply("fn", t1, t1);
      final Unifier.Variable t0 = unifier.variable(0);
      pairs.add(new Unifier.TermTerm(fn71, t0));
      String expected =
          "[fn(T1, T1)/T0, fn(fn(int, int), fn(int, int))/T2, "
              + "fn(int, int)/T3, int/T4, int/T5, int/T6, int/T7, "
              + "fn(fn(bool, bool), fn(bool, bool))/T8, "
              + "fn(bool, bool)/T9, bool/T10, bool/T11, bool/T12, "
              + "bool/T13, fn(bool, T1)/T14, bool/T15]";
      assertThatUnify(pairs, is(expected));
    }
  }
}

// End UnifierTest.java
