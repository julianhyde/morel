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

import static java.util.Objects.requireNonNull;
import static net.hydromatic.morel.util.Pair.allMatch;
import static net.hydromatic.morel.util.Static.skip;
import static net.hydromatic.morel.util.Static.transformEager;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.util.ImmutablePairList;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Value that is sufficient for a function to bind its argument and evaluate its
 * body.
 */
public class Closure implements Comparable<Closure>, Applicable, Applicable1 {
  /**
   * Environment for evaluation. Contains the variables "captured" from the
   * environment when the closure was created.
   */
  private final EvalEnv evalEnv;

  /**
   * A list of (pattern, code) pairs. During bind, the value being bound is
   * matched against each pattern. When a match is found, the code for that
   * pattern is used to evaluate.
   *
   * <p>For example, when applying {@code fn x => case x of 0 => "yes" | _ =>
   * "no"} to the value {@code 1}, the first pattern ({@code 0} fails) but the
   * second pattern ({@code _}) succeeds, and therefore we evaluate the second
   * code {@code "no"}.
   */
  private final ImmutablePairList<Core.Pat, Code> patCodes;

  private final Pos pos;

  /** Not a public API. */
  public Closure(
      EvalEnv evalEnv, ImmutablePairList<Core.Pat, Code> patCodes, Pos pos) {
    this.evalEnv = requireNonNull(evalEnv).fix();
    this.patCodes = requireNonNull(patCodes);
    this.pos = pos;
  }

  @Override
  public String toString() {
    return "Closure(evalEnv = " + evalEnv + ", patCodes = " + patCodes + ")";
  }

  public int compareTo(Closure o) {
    return 0;
  }

  /**
   * Binds an argument value to create a new environment for a closure.
   *
   * <p>When calling a simple function such as {@code (fn x => x + 1) 2}, the
   * binder sets just contains one variable, {@code x}, and the new environment
   * contains {@code x = 1}. If the function's parameter is a match, more
   * variables might be bound. For example, when you invoke {@code (fn (x, y) =>
   * x + y) (3, 4)}, the binder sets {@code x} to 3 and {@code y} to 4.
   */
  EvalEnv bind(Object argValue) {
    final EvalEnvHolder envRef = new EvalEnvHolder(evalEnv);
    for (Core.Pat pat : patCodes.leftList()) {
      if (bindRecurse(pat, argValue, envRef)) {
        return envRef.env;
      }
    }
    throw new AssertionError("no match");
  }

  /**
   * Similar to {@link #bind}, but also evaluates. May return a {@link
   * Codes.TailCall} sentinel; callers must trampoline if they need a real
   * value.
   */
  private Object bindEvalBody(Object argValue) {
    final EvalEnvHolder envRef = new EvalEnvHolder(evalEnv);
    for (Map.Entry<Core.Pat, Code> patCode : patCodes) {
      final Core.Pat pat = patCode.getKey();
      if (bindRecurse(pat, argValue, envRef)) {
        final Code code = patCode.getValue();
        final Session session = (Session) envRef.env.getOpt(EvalEnv.SESSION);
        return code.eval(new Stack(session, code.maxSlots()));
      }
    }
    throw new Codes.MorelRuntimeException(Codes.BuiltInExn.BIND, pos);
  }

  /**
   * Similar to {@link #bindEvalBody}, but trampolines {@link Codes.TailCall}
   * sentinels so that tail-recursive calls execute in O(1) Java stack space.
   */
  private Object bindEval(Object argValue) {
    Object result = bindEvalBody(argValue);
    while (result instanceof Codes.TailCall) {
      final Codes.TailCall tc = (Codes.TailCall) result;
      if (tc.fn instanceof Closure) {
        result = ((Closure) tc.fn).bindEvalBody(tc.arg);
      } else {
        result = tc.fn.apply(evalEnv, tc.arg);
      }
    }
    return result;
  }

  @Override
  public Object apply(Stack stack, Object argValue) {
    return bindEval(argValue);
  }

  @Override
  public Object apply(Object argValue) {
    return bindEval(argValue);
  }

  @Override
  public Describer describe(Describer describer) {
    return describer.start("closure", d -> {});
  }

  /**
   * Attempts to bind a value to a pattern. Returns whether it has succeeded in
   * matching the whole pattern.
   *
   * <p>Each time it matches a name, calls a consumer. It's possible that the
   * consumer is called a few times even if the whole pattern ultimately fails
   * to match.
   */
  public static boolean bindRecurse(
      Core.Pat pat, Object argValue, BiConsumer<Core.NamedPat, Object> envRef) {
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
        return ((BigDecimal) literalPat.value).doubleValue()
            == (Double) argValue;

      case TUPLE_PAT:
        final Core.TuplePat tuplePat = (Core.TuplePat) pat;
        listValue = (List) argValue;
        return allMatch(
            tuplePat.args,
            listValue,
            (pat1, value) -> bindRecurse(pat1, value, envRef));

      case RECORD_PAT:
        final Core.RecordPat recordPat = (Core.RecordPat) pat;
        listValue = (List) argValue;
        return allMatch(
            recordPat.args,
            listValue,
            (pat1, value) -> bindRecurse(pat1, value, envRef));

      case LIST_PAT:
        final Core.ListPat listPat = (Core.ListPat) pat;
        listValue = (List) argValue;
        if (listValue.size() != listPat.args.size()) {
          return false;
        }
        return allMatch(
            listPat.args,
            listValue,
            (pat1, value) -> bindRecurse(pat1, value, envRef));

      case CONS_PAT:
        final Core.ConPat consPat = (Core.ConPat) pat;
        if (argValue instanceof Variant) {
          return Variant.bindConsPat(envRef, (Variant) argValue, consPat);
        }
        @SuppressWarnings("unchecked")
        final List<Object> consValue = (List) argValue;
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
        if (argValue instanceof Variant) {
          final Variant value = (Variant) argValue;
          return Variant.bindConPat(envRef, value, conPat);
        }
        // Old-style [tag, payload] representation
        final List conValue = (List) argValue;
        return conValue.get(0).equals(conPat.tyCon)
            && bindRecurse(conPat.pat, conValue.get(1), envRef);

      default:
        throw new AssertionError("cannot compile " + pat.op + ": " + pat);
    }
  }

  /**
   * Callback for {@link #bindRecurse(Core.Pat, Object, BiConsumer)} that
   * modifies an environment.
   */
  private static class EvalEnvHolder
      implements BiConsumer<Core.NamedPat, Object> {
    EvalEnv env;

    EvalEnvHolder(EvalEnv env) {
      this.env = env;
    }

    @Override
    public void accept(Core.NamedPat namedPat, Object o) {
      env = env.bind(namedPat.name, o);
    }
  }

  /**
   * A closure that uses the evaluation stack for local variable access.
   *
   * <p>At creation time, {@link #capturedValues} are snapshotted from the
   * stack. At call time, they are pushed onto the stack, followed by the
   * argument bindings, and the body code is evaluated with {@link
   * Code#eval(Stack)}.
   */
  /**
   * Shared mutable array for a mutually-recursive function group.
   *
   * <p>Allocated by {@code StackMultiLetCode.eval} before any closure in the
   * group is created. Each closure in the group holds a reference to the same
   * {@code RecFrame} object. After all closures are created, {@code
   * StackMultiLetCode.eval} fills {@code bindings[i]} with closure {@code i}.
   *
   * <p>When a closure is applied, {@code StackClosure.applyOnce} pushes all
   * {@code bindings} onto the stack before the argument, so that body code
   * compiled with a {@code StackCode} for each peer resolves correctly.
   */
  public static final class RecFrame {
    /**
     * One slot per binding in the rec group; filled after all closures exist.
     */
    public final Object[] bindings;

    public RecFrame(int size) {
      bindings = new Object[size];
    }
  }

  public static class StackClosure
      implements Comparable<StackClosure>, Applicable, Applicable1 {
    /**
     * The current session, shared with all closures in this evaluation.
     *
     * <p>When non-null, {@link #apply(Object)} creates a fresh {@link Stack}
     * from {@link Session#globalEnv} so it automatically sees bindings added
     * after this closure was created (e.g. the closure's own top-level
     * definition).
     */
    final @Nullable Session session;

    final Object[] capturedValues;

    /**
     * Shared frame for the mutual-recursion group this closure belongs to, or
     * {@code null} for non-recursive closures.
     *
     * <p>Set by {@code StackMultiLetCode.eval} immediately after closure
     * creation, before any call can observe it.
     */
    RecFrame recFrame;

    private final ImmutablePairList<Core.Pat, Code> patCodes;
    /**
     * Minimum stack capacity needed when this closure is invoked without a
     * pre-existing {@link Stack}. Computed at compile time by {@code
     * StackMatchCode} from {@code captureOffsets.length} plus the maximum body
     * depth (including rec-group peers when in a rec group).
     */
    final int capacity;

    private final Pos pos;

    public StackClosure(
        @Nullable Session session,
        Object[] capturedValues,
        RecFrame recFrame,
        ImmutablePairList<Core.Pat, Code> patCodes,
        int capacity,
        Pos pos) {
      this.session = session;
      this.capturedValues = capturedValues;
      this.recFrame = recFrame;
      this.patCodes = patCodes;
      this.capacity = capacity;
      this.pos = pos;
    }

    @Override
    public int compareTo(StackClosure o) {
      return 0;
    }

    @Override
    public String toString() {
      return "StackClosure(captured=" + capturedValues.length + ")";
    }

    @Override
    public Describer describe(Describer describer) {
      return describer.start("stackClosure", d -> {});
    }

    /**
     * Applies this closure to {@code argValue}, using the stack for local
     * variable storage.
     *
     * <p>If the caller's slots array doesn't have room for this closure's
     * capacity (e.g., a non-tail recursive call where the outer frame's
     * bindings are still live), allocates a larger array so that pushBindings
     * can store the new frame's variables without going out of bounds.
     */
    @Override
    public Object apply(Stack stack, Object argValue) {
      Stack evalStack = stack.ensureSize(capacity);
      int savedTop = evalStack.save();
      Object result = applyOnce(evalStack, argValue);
      while (result instanceof Codes.TailCall) {
        final Codes.TailCall tc = (Codes.TailCall) result;
        evalStack.restore(savedTop);
        if (tc.fn instanceof StackClosure) {
          final StackClosure nextFn = (StackClosure) tc.fn;
          // Ensure slots array is large enough for the tail-called closure.
          // The outer closure may have a smaller capacity than the tail-called
          // closure needs (e.g., fn x => case x of head::tail => ...).
          evalStack = evalStack.ensureSize(nextFn.capacity);
          result = nextFn.applyOnce(evalStack, tc.arg);
        } else {
          result = tc.fn.apply(evalStack, tc.arg);
          break;
        }
      }
      evalStack.restore(savedTop);
      return result;
    }

    private Object applyOnce(Stack stack, Object argValue) {
      // Push captured values from the outer frame.
      for (Object v : capturedValues) {
        stack.push(v);
      }
      // Push rec-group peers (if this closure is part of a rec group).
      // These are at slots captureLen..captureLen+N-1 in the body context,
      // matching the StackCode offsets assigned at compile time.
      if (recFrame != null) {
        for (Object v : recFrame.bindings) {
          stack.push(v);
        }
      }
      // Try each pattern arm
      for (Map.Entry<Core.Pat, Code> patCode : patCodes) {
        final int armTop = stack.save();
        if (pushBindings(patCode.getKey(), argValue, stack)) {
          return patCode.getValue().eval(stack);
        }
        stack.restore(armTop);
      }
      throw new Codes.MorelRuntimeException(Codes.BuiltInExn.BIND, pos);
    }

    /**
     * Applies this closure without a pre-existing {@link Stack}.
     *
     * <p>Implements {@link Applicable1} so that built-in higher-order functions
     * (e.g. {@code List.map}) can call user-defined functions without needing
     * to supply an {@link EvalEnv}.
     */
    @Override
    public Object apply(Object argValue) {
      return apply(new Stack(session, capacity), argValue);
    }

    /**
     * Matches {@code pat} against {@code argValue}, pushing bound values onto
     * {@code stack} in the same order as {@link Closure#bindRecurse}.
     *
     * <p>Returns true if the pattern matched, false otherwise. On false, the
     * caller is responsible for restoring {@code stack.top}.
     */
    @SuppressWarnings("unchecked")
    public static boolean pushBindings(
        Core.Pat pat, Object argValue, Stack stack) {
      final Core.LiteralPat literalPat;
      switch (pat.op) {
        case ID_PAT:
          stack.push(argValue);
          return true;

        case WILDCARD_PAT:
          return true;

        case AS_PAT:
          final Core.AsPat asPat = (Core.AsPat) pat;
          stack.push(argValue);
          return pushBindings(asPat.pat, argValue, stack);

        case BOOL_LITERAL_PAT:
        case CHAR_LITERAL_PAT:
        case STRING_LITERAL_PAT:
          literalPat = (Core.LiteralPat) pat;
          return literalPat.value.equals(argValue);

        case INT_LITERAL_PAT:
          literalPat = (Core.LiteralPat) pat;
          return ((BigDecimal) literalPat.value).intValue()
              == (Integer) argValue;

        case REAL_LITERAL_PAT:
          literalPat = (Core.LiteralPat) pat;
          return ((BigDecimal) literalPat.value).doubleValue()
              == (Double) argValue;

        case TUPLE_PAT:
          final Core.TuplePat tuplePat = (Core.TuplePat) pat;
          final List<Object> tupleValue = (List<Object>) argValue;
          for (int i = 0; i < tuplePat.args.size(); i++) {
            if (!pushBindings(tuplePat.args.get(i), tupleValue.get(i), stack)) {
              return false;
            }
          }
          return true;

        case RECORD_PAT:
          final Core.RecordPat recordPat = (Core.RecordPat) pat;
          final List<Object> recordValue = (List<Object>) argValue;
          for (int i = 0; i < recordPat.args.size(); i++) {
            if (!pushBindings(
                recordPat.args.get(i), recordValue.get(i), stack)) {
              return false;
            }
          }
          return true;

        case LIST_PAT:
          final Core.ListPat listPat = (Core.ListPat) pat;
          final List<Object> listValue = (List<Object>) argValue;
          if (listValue.size() != listPat.args.size()) {
            return false;
          }
          for (int i = 0; i < listPat.args.size(); i++) {
            if (!pushBindings(listPat.args.get(i), listValue.get(i), stack)) {
              return false;
            }
          }
          return true;

        case CONS_PAT:
          final Core.ConPat consPat = (Core.ConPat) pat;
          if (argValue instanceof Variant) {
            return pushVariantConsPat((Variant) argValue, consPat, stack);
          }
          final List<Object> consValue = (List<Object>) argValue;
          if (consValue.isEmpty()) {
            return false;
          }
          final Object head = consValue.get(0);
          final List<Object> tail = skip(consValue);
          final List<Core.Pat> patArgs = ((Core.TuplePat) consPat.pat).args;
          return pushBindings(patArgs.get(0), head, stack)
              && pushBindings(patArgs.get(1), tail, stack);

        case CON0_PAT:
          final Core.Con0Pat con0Pat = (Core.Con0Pat) pat;
          final List con0Value = (List) argValue;
          return con0Value.get(0).equals(con0Pat.tyCon);

        case CON_PAT:
          final Core.ConPat conPat = (Core.ConPat) pat;
          if (argValue instanceof Variant) {
            return pushVariantConPat((Variant) argValue, conPat, stack);
          }
          final List conValue = (List) argValue;
          return conValue.get(0).equals(conPat.tyCon)
              && pushBindings(conPat.pat, conValue.get(1), stack);

        default:
          throw new AssertionError(
              "cannot push bindings for " + pat.op + ": " + pat);
      }
    }

    private static boolean pushVariantConsPat(
        Variant variant, Core.ConPat consPat, Stack stack) {
      @SuppressWarnings("unchecked")
      final List<Object> consValue = (List<Object>) variant.value;
      if (consValue.isEmpty()) {
        return false;
      }
      final Type elementType = variant.type.elementType();
      final Variant head = Variant.of(elementType, consValue.get(0));
      final List<Variant> tail =
          transformEager(
              skip(consValue),
              e ->
                  e instanceof Variant
                      ? (Variant) e
                      : Variant.of(elementType, e));
      List<Core.Pat> patArgs = ((Core.TuplePat) consPat.pat).args;
      return pushBindings(patArgs.get(0), head, stack)
          && pushBindings(patArgs.get(1), tail, stack);
    }

    private static boolean pushVariantConPat(
        Variant variant, Core.ConPat conPat, Stack stack) {
      if (!variant.constructor().constructor.equals(conPat.tyCon)) {
        return false;
      }
      return pushBindings(conPat.pat, Variant.innerValue(variant), stack);
    }
  }
}

// End Closure.java
