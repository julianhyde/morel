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
package net.hydromatic.morel.ast;

/**
 * Predicates that classify {@link Core.Exp} expressions by what it costs, and
 * what it risks, to evaluate them more than once, earlier, or not at all.
 *
 * <p>They form a ladder, each accepting everything the one before accepts:
 *
 * <ol>
 *   <li>{@link #isAtom}: an identifier or a literal. Substituting one for each
 *       use of a variable evaluates nothing.
 *   <li>{@link #isCheap}: atoms, and tuples, records and field selections of
 *       cheap expressions. Duplicating one only reads.
 * </ol>
 *
 * <p>{@link Core.Exp} has an instance method for each, which calls the method
 * here. {@link Core.Exp#isConstant()}, which says whether an expression has a
 * value that does not depend on any variable, is a different question, and is
 * not on the ladder.
 */
public class Exps {
  private Exps() {}

  /**
   * Returns whether an expression is an atom: an identifier or a literal.
   *
   * <p>An atom can be substituted for each use of a variable that is bound to
   * it, however many uses there are, because evaluating it again costs nothing.
   *
   * <p>{@code $0} counts. It is a node and not a variable, but it is as cheap
   * to duplicate as an id, and a {@code case} over a tuple of the row's
   * components is what beta-reducing a call in a query leaves: {@code case (x,
   * $0) of (x, y) => (x, y) elem edges}. Leaving that unreduced hides the
   * constraint from the grounding engine, which matches on {@code elem} over
   * the query's variables.
   */
  public static boolean isAtom(Core.Exp exp) {
    return exp instanceof Core.Literal || exp instanceof Core.Id;
  }

  /**
   * Returns whether an expression only reads: an atom, or a tuple, record or
   * field selection of cheap expressions. Duplicating one evaluates nothing
   * twice.
   */
  public static boolean isCheap(Core.Exp exp) {
    switch (exp.op) {
      case TUPLE:
        return ((Core.Tuple) exp).args.stream().allMatch(Exps::isCheap);
      case APPLY:
        final Core.Apply apply = (Core.Apply) exp;
        return apply.fn.op == Op.RECORD_SELECTOR && isCheap(apply.arg);
      default:
        return isAtom(exp);
    }
  }
}

// End Exps.java
