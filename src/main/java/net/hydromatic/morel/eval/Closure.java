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
package net.hydromatic.morel.eval;

import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.util.ImmutablePairList;

import com.google.common.collect.ImmutableList;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.BiConsumer;

import static net.hydromatic.morel.util.Ord.forEachIndexed;
import static net.hydromatic.morel.util.Pair.allMatch;
import static net.hydromatic.morel.util.Static.skip;

import static java.util.Objects.requireNonNull;

/** Value that is sufficient for a function to bind its argument
 * and evaluate its body. */
public class Closure implements Comparable<Closure>, Applicable {
  /** The values of the unbound variables that are "closed over" by this
   * closure. (May make the stack obsolete one day.) */
  protected final ImmutableList<Object> values;

  /** A list of (pattern, code) pairs. During bind, the value being bound is
   * matched against each pattern. When a match is found, the code for that
   * pattern is used to evaluate.
   *
   * <p>For example, when applying
   * {@code fn x => case x of 0 => "yes" | _ => "no"}
   * to the value {@code 1}, the first pattern ({@code 0} fails) but the second
   * pattern ({@code _}) succeeds, and therefore we evaluate the second
   * code {@code "no"}. */
  private final ImmutablePairList<? extends Core.Pat, Code> patCodes;
  private final Pos pos;

  /** Not a public API. */
  public Closure(ImmutablePairList<? extends Core.Pat, Code> patCodes,
      Pos pos) {
    this.patCodes = requireNonNull(patCodes);
    this.values = ImmutableList.of();
    this.pos = pos;
  }

  /** Not a public API. Special constructor that populates the environment of
   * captured variables by evaluating expressions using the stack; can even
   * store a reference this Closure, so that the bodies of recursive functions
   * can use the name of the function to reference the function value.
   *
   * <p>Recursive functions capture variables, and then become closures. For
   * example, in the following, {@code factorial} is a closure whose captured
   * variables are itself ({@code factorial}) and {@code z}.
   *
   * <blockquote><pre>{@code
   * val z = 3;
   * fun factorial n =
   *   if n = z then n else n * factorial (n - 1);
   * factorial 5;
   * > 60;
   * }</pre></blockquote>
   */
  public Closure(ImmutablePairList<? extends Core.Pat, Code> patCodes, Pos pos,
      ImmutablePairList<Core.NamedPat, Code> captureCodes, Stack stack) {
    this.patCodes = requireNonNull(patCodes);
    this.pos = pos;
    this.values =
        captureCodes.transform2((p, c) ->
          c == Codes.CLOSURE ? Closure.this : c.eval(stack));
  }

  @Override public String toString() {
    StringBuilder b = new StringBuilder();
    b.append("Closure(");
    forEachIndexed(values, (value, i) -> {
      if (i > 0) {
        b.append(", ");
      }
      if (value == this) {
        b.append("this");
      } else {
        b.append(value);
      }
    });
    return b.append("; ").append(patCodes).append(")").toString();
  }

  public int compareTo(@NonNull Closure o) {
    return 0;
  }

  /** Evaluates expressions and binds them to the stack, to create a new
   * environment for a closure.
   *
   * <p>Reads the values it needs from
   * the stack, and writes the result to the stack. */
  public int execBind(Stack stack) {
    this.values.forEach(stack::push);
    final int top = stack.save();
    for (int i = 0, n = patCodes.size(); i < n; i++) {
      final Code code = patCodes.right(i);
      final Object argValue = code == Codes.CLOSURE ? this : code.eval(stack);
      final Core.Pat pat = patCodes.left(i);
      if (bindRecurse(pat, argValue, (p, o) -> stack.push(o))) {
        return 0;
      }
      stack.restore(top);
    }
    throw new AssertionError("no match");
  }

  @Override public Object apply(Stack stack, Object argValue) {
    final int top = stack.save();
    this.values.forEach(stack::push);
    final int top2 = stack.save();
    for (int i = 0, n = patCodes.size(); i < n; i++) {
      final Core.Pat pat = patCodes.left(i);
      if (bindRecurse(pat, argValue, (p, o) -> stack.push(o))) {
        final Code code = patCodes.right(i);
        final Object o = code == Codes.CLOSURE ? this : code.eval(stack);
        stack.restore(top);
        return o;
      }
      stack.restore(top2);
    }
    throw new Codes.MorelRuntimeException(Codes.BuiltInExn.BIND, pos);
  }

  @Override public Describer describe(Describer describer) {
    return describer.start("closure", d -> {});
  }

  /** Attempts to bind a value to a pattern. Returns whether it has succeeded in
   * matching the whole pattern.
   *
   * <p>Each time it matches a name, calls a consumer. It's possible that the
   * consumer is called a few times even if the whole pattern ultimately fails
   * to match. */
  @SuppressWarnings({"unchecked", "rawtypes"})
  public static boolean bindRecurse(Core.Pat pat, Object argValue,
      BiConsumer<Core.NamedPat, Object> envRef) {
    final List<Object> listValue;
    final Core.LiteralPat literalPat;
    switch (pat.op) {
    case ID_PAT:
      final Core.IdPat idPat = (Core.IdPat) pat;
      envRef.accept(idPat, argValue);
      return true;

    case WILDCARD_PAT:
      return true;

    case AS_PAT:
      final Core.AsPat asPat = (Core.AsPat) pat;
      envRef.accept(asPat, argValue);
      return bindRecurse(asPat.pat, argValue, envRef);

    case BOOL_LITERAL_PAT:
    case CHAR_LITERAL_PAT:
    case STRING_LITERAL_PAT:
      literalPat = (Core.LiteralPat) pat;
      return literalPat.value.equals(argValue);

    case INT_LITERAL_PAT:
      literalPat = (Core.LiteralPat) pat;
      return ((BigDecimal) literalPat.value).intValue() == (Integer) argValue;

    case REAL_LITERAL_PAT:
      literalPat = (Core.LiteralPat) pat;
      return ((BigDecimal) literalPat.value).doubleValue() == (Double) argValue;

    case TUPLE_PAT:
      final Core.TuplePat tuplePat = (Core.TuplePat) pat;
      listValue = (List) argValue;
      return allMatch(tuplePat.args, listValue,
          (pat1, value) -> bindRecurse(pat1, value, envRef));

    case RECORD_PAT:
      final Core.RecordPat recordPat = (Core.RecordPat) pat;
      listValue = (List) argValue;
      return allMatch(recordPat.args, listValue,
          (pat1, value) -> bindRecurse(pat1, value, envRef));

    case LIST_PAT:
      final Core.ListPat listPat = (Core.ListPat) pat;
      listValue = (List) argValue;
      if (listValue.size() != listPat.args.size()) {
        return false;
      }
      return allMatch(listPat.args, listValue,
          (pat1, value) -> bindRecurse(pat1, value, envRef));

    case CONS_PAT:
      final Core.ConPat consPat = (Core.ConPat) pat;
      @SuppressWarnings("unchecked") final List<Object> consValue =
          (List) argValue;
      if (consValue.isEmpty()) {
        return false;
      }
      final Object head = consValue.get(0);
      final List<Object> tail = skip(consValue);
      List<Core.Pat> patArgs = ((Core.TuplePat) consPat.pat).args;
      return bindRecurse(patArgs.get(0), head, envRef)
          && bindRecurse(patArgs.get(1), tail, envRef);

    case CON0_PAT:
      final Core.Con0Pat con0Pat = (Core.Con0Pat) pat;
      final List con0Value = (List) argValue;
      return con0Value.get(0).equals(con0Pat.tyCon);

    case CON_PAT:
      final Core.ConPat conPat = (Core.ConPat) pat;
      final List conValue = (List) argValue;
      return conValue.get(0).equals(conPat.tyCon)
          && bindRecurse(conPat.pat, conValue.get(1), envRef);

    default:
      throw new AssertionError("cannot compile " + pat.op + ": " + pat);
    }
  }

  public static class ClosureX extends Closure {
    private final ImmutableList<Object> values2;

    public ClosureX(ImmutablePairList<? extends Core.Pat, Code> patCodes,
        ImmutablePairList<Core.NamedPat, Code> captureCodes, Pos pos,
        Stack stack) {
      super(patCodes, pos);
      this.values2 =
          captureCodes.transform2((p, c) ->
              c == Codes.CLOSURE ? ClosureX.this : c.eval(stack));
    }

    @Override public int execBind(Stack stack) {
      this.values2.forEach(stack::push);
      return 0;
    }

    @Override public Object apply(Stack stack, Object argValue) {
      final int top = stack.save();
      try {
        this.values2.forEach(stack::push);
        return super.apply(stack, argValue);
      } finally {
        stack.restore(top);
      }
    }
  }
}

// End Closure.java
