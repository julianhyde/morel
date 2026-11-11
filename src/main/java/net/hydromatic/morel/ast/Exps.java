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

import com.google.common.collect.ImmutableSet;
import java.util.Set;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.type.DataType;
import net.hydromatic.morel.type.FnType;
import net.hydromatic.morel.type.Type;

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
 *   <li>{@link #isSimple}: cheap expressions, and constructors such as {@code
 *       SOME} applied to simple expressions. Evaluating one can neither raise
 *       nor have an effect, so it may be evaluated eagerly, or on rows where it
 *       was not written to be.
 *   <li>{@link #isSafe}: simple expressions, and comparisons, {@code elem},
 *       {@code andalso}, {@code orelse} and {@code not} of safe expressions.
 *       Like a simple expression, it can neither raise nor have an effect; it
 *       is a condition that may be moved to where it filters earlier.
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

  /**
   * Returns whether an expression is simple: a cheap expression, or a
   * constructor applied to a simple expression, or a tuple, record or field
   * selection of simple expressions. Evaluating one can neither raise nor have
   * an effect.
   */
  public static boolean isSimple(Core.Exp exp) {
    switch (exp.op) {
      case TUPLE:
        return ((Core.Tuple) exp).args.stream().allMatch(Exps::isSimple);
      case APPLY:
        final Core.Apply apply = (Core.Apply) exp;
        return (apply.fn.op == Op.RECORD_SELECTOR
                || apply.fn instanceof Core.Id
                    && isConstructor((Core.Id) apply.fn))
            && isSimple(apply.arg);
      default:
        return isAtom(exp);
    }
  }

  /**
   * Returns whether an expression is safe: a simple expression, or a
   * comparison, {@code elem}, {@code andalso}, {@code orelse} or {@code not} of
   * safe expressions, or a tuple, record, field selection or constructor
   * application of safe expressions. Evaluating one can neither raise nor have
   * an effect, so a condition that is safe may be evaluated earlier, or on
   * fewer rows, than it was written to be.
   */
  public static boolean isSafe(Core.Exp exp) {
    switch (exp.op) {
      case TUPLE:
        return ((Core.Tuple) exp).args.stream().allMatch(Exps::isSafe);
      case APPLY:
        final Core.Apply apply = (Core.Apply) exp;
        return (apply.fn.op == Op.RECORD_SELECTOR
                || apply.fn instanceof Core.Id
                    && isConstructor((Core.Id) apply.fn)
                || SAFE_FUNCTIONS.contains(apply.builtIn())
                || COMPARISONS.contains(apply.builtIn().mlName))
            && isSafe(apply.arg);
      default:
        return isAtom(exp);
    }
  }

  /**
   * Names of the comparison operators. Every built-in of one of these names,
   * such as {@code Decimal.<}, can neither raise nor have an effect.
   */
  private static final Set<String> COMPARISONS =
      ImmutableSet.of("<", "<=", "<>", "=", ">", ">=");

  /**
   * Built-in functions that can neither raise nor have an effect, besides those
   * that {@link #COMPARISONS} names. (The polymorphic comparisons, such as
   * {@code op <}, are here because their names have the "op" prefix.)
   */
  private static final Set<BuiltIn> SAFE_FUNCTIONS =
      ImmutableSet.of(
          BuiltIn.BOOL_NOT,
          BuiltIn.OP_ELEM,
          BuiltIn.OP_EQ,
          BuiltIn.OP_GE,
          BuiltIn.OP_GT,
          BuiltIn.OP_LE,
          BuiltIn.OP_LT,
          BuiltIn.OP_NE,
          BuiltIn.OP_NOT_ELEM,
          BuiltIn.Z_ANDALSO,
          BuiltIn.Z_ORELSE);

  /**
   * Returns whether an identifier is a constructor of a datatype, such as
   * {@code SOME}.
   */
  public static boolean isConstructor(Core.Id id) {
    Type type = id.type.unalias();
    if (type instanceof FnType) {
      type = ((FnType) type).resultType.unalias();
    }
    return type instanceof DataType
        && ((DataType) type).typeConstructors.containsKey(id.idPat.name);
  }
}

// End Exps.java
