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

import static net.hydromatic.morel.ast.CoreBuilder.core;
import static net.hydromatic.morel.util.Static.allMatch;

import com.google.common.collect.ImmutableSet;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.eval.Applicable;
import net.hydromatic.morel.eval.Applicable1;
import net.hydromatic.morel.eval.MorelRuntimeException;
import net.hydromatic.morel.eval.Stack;
import net.hydromatic.morel.type.DataType;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.TypeSystem;
import org.jspecify.annotations.Nullable;

/**
 * Rules for constant folding: which expressions may be evaluated at compile
 * time.
 *
 * <p>An expression is folded if it constructs a value from constants, or
 * converts a constant from one type to another without losing information, or
 * reads a field of a constant. Evaluating it at compile time gives the same
 * value as evaluating it each time it is reached, and costs nothing at run
 * time. A call that would raise an exception is not folded; it raises when it
 * is reached, if it is reached.
 *
 * <p>{@link Inliner} folds an expression whose value is a primitive or a {@code
 * decimal} to a literal, which later passes (such as the Spark and Calcite
 * translators, and the deduction of a variable's bounds) understand. {@link
 * Compiler} folds the rest -- records, tuples, lists, bags, datatype
 * constructors -- to constant code, leaving the expression as it is for those
 * passes.
 */
public final class Folder {
  private Folder() {}

  /**
   * Built-in functions that construct a value, or convert a value to another
   * type without losing information, and so may be evaluated at compile time if
   * their argument is constant.
   *
   * <p>Not included: functions that round ({@code Real.floor}, {@code
   * Decimal.toReal}) or truncate ({@code Time.toSeconds}), which lose
   * information; functions that format or parse ({@code Int.toString}, {@code
   * Date.fromString}); and functions that depend on the environment ({@code
   * Date.fromTimeLocal}, which depends on the time zone).
   */
  static final Set<BuiltIn> FOLDABLE =
      ImmutableSet.of(
          BuiltIn.BAG_FROM_LIST,
          BuiltIn.CHAR_CHR,
          BuiltIn.CHAR_ORD,
          BuiltIn.DECIMAL_DECIMAL,
          BuiltIn.DECIMAL_FROM_INT,
          BuiltIn.DECIMAL_FROM_REAL,
          BuiltIn.INT_FROM_INT,
          BuiltIn.INT_FROM_LARGE,
          BuiltIn.INT_TO_INT,
          BuiltIn.INT_TO_LARGE,
          BuiltIn.REAL_FROM_INT,
          BuiltIn.STRING_STR,
          BuiltIn.TIME_FROM_MICROSECONDS,
          BuiltIn.TIME_FROM_MILLISECONDS,
          BuiltIn.TIME_FROM_NANOSECONDS,
          BuiltIn.TIME_FROM_SECONDS,
          BuiltIn.VECTOR_FROM_LIST,
          BuiltIn.WORD_FROM_INT,
          BuiltIn.WORD_FROM_LARGE_WORD,
          BuiltIn.WORD_TO_LARGE_WORD);

  /**
   * Returns the value of a call to a foldable built-in whose argument is a
   * literal, as a literal; or null if the call cannot be folded to a literal,
   * because the function is not foldable, the argument is not a literal, the
   * result is not a primitive or a {@code decimal}, the call would raise an
   * exception, or (for {@code Real.fromInt}) the conversion would lose
   * information.
   */
  static Core.@Nullable Literal foldCall(
      TypeSystem typeSystem, Core.Apply apply) {
    if (apply.fn.op != Op.FN_LITERAL) {
      return null;
    }
    final BuiltIn builtIn = ((Core.Literal) apply.fn).unwrap(BuiltIn.class);
    if (!FOLDABLE.contains(builtIn)) {
      return null;
    }
    final Object arg = literalValue(apply.arg);
    if (arg == null) {
      return null;
    }
    final Object result;
    try {
      result = call(typeSystem, (Core.Literal) apply.fn, arg);
    } catch (MorelRuntimeException e) {
      // Leave the call, to raise at run time, if it is reached.
      return null;
    }
    if (!isLossless(builtIn, apply.arg)) {
      return null;
    }
    if (apply.type instanceof PrimitiveType) {
      return core.literal((PrimitiveType) apply.type, result);
    }
    if (result instanceof BigDecimal) {
      return core.valueLiteral(apply, result);
    }
    return null;
  }

  /**
   * Returns whether a call to a foldable built-in loses no information.
   *
   * <p>Every foldable built-in is lossless, except {@code Real.fromInt},
   * because a real has 24 bits of precision and cannot hold every int; so
   * {@code Real.fromInt 7} is folded, but not {@code Real.fromInt 16777217}.
   */
  private static boolean isLossless(BuiltIn builtIn, Core.Exp arg) {
    if (builtIn != BuiltIn.REAL_FROM_INT) {
      return true;
    }
    if (arg.op != Op.INT_LITERAL) {
      return false;
    }
    final int i = ((Core.Literal) arg).unwrap(Integer.class);
    return (int) (float) i == i;
  }

  /** Calls a built-in function, given as a literal, with an argument. */
  private static Object call(
      TypeSystem typeSystem, Core.Literal fn, Object arg) {
    final Object f = fn.toBuiltIn(typeSystem, null);
    if (f instanceof Applicable1) {
      @SuppressWarnings("unchecked")
      final Applicable1<Object, Object> f1 = (Applicable1<Object, Object>) f;
      return f1.apply(arg);
    }
    return ((Applicable) f).apply(Stack.withCapacity(0), arg);
  }

  /**
   * Returns the runtime value of a literal, or null if the expression is not a
   * literal of a primitive type or a {@code decimal}.
   */
  static @Nullable Object literalValue(Core.Exp exp) {
    switch (exp.op) {
      case BOOL_LITERAL:
        return ((Core.Literal) exp).unwrap(Boolean.class);
      case CHAR_LITERAL:
        return ((Core.Literal) exp).unwrap(Character.class);
      case INT_LITERAL:
        return ((Core.Literal) exp).unwrap(Integer.class);
      case REAL_LITERAL:
        return ((Core.Literal) exp).unwrap(Float.class);
      case STRING_LITERAL:
        return ((Core.Literal) exp).unwrap(String.class);
      case VALUE_LITERAL:
        final Object value = ((Core.Literal) exp).valueObject();
        return value instanceof BigDecimal ? value : null;
      default:
        return null;
    }
  }

  /**
   * Returns whether an expression constructs a value from constants, and so may
   * be compiled to code that returns that value.
   *
   * <p>Such an expression is a literal (other than a table, which is read each
   * time); a record, tuple or list whose elements are constant; a datatype
   * constructor applied to a constant; a call to a {@link #FOLDABLE} built-in
   * with a constant argument; or a field of a constant.
   */
  static boolean isConstant(Core.Exp exp) {
    switch (exp.op) {
      case BOOL_LITERAL:
      case CHAR_LITERAL:
      case INT_LITERAL:
      case REAL_LITERAL:
      case STRING_LITERAL:
      case UNIT_LITERAL:
      case WORD_LITERAL:
        return true;
      case VALUE_LITERAL:
        // A table (a TypedValue) is read when the code runs.
        return literalValue(exp) != null
            || ((Core.Literal) exp).valueObject() instanceof List;
      case TUPLE:
        return allMatch(((Core.Tuple) exp).args, Folder::isConstant);
      case APPLY:
        final Core.Apply apply = (Core.Apply) exp;
        if (!isConstant(apply.arg)) {
          return false;
        }
        switch (apply.fn.op) {
          case FN_LITERAL:
            final BuiltIn builtIn =
                ((Core.Literal) apply.fn).unwrap(BuiltIn.class);
            return builtIn == BuiltIn.Z_LIST
                || FOLDABLE.contains(builtIn) && isLossless(builtIn, apply.arg);
          case RECORD_SELECTOR:
            return true;
          case ID:
            return isConstructor(apply);
          default:
            return false;
        }
      default:
        return false;
    }
  }

  /**
   * Returns whether an application applies a datatype constructor, such as
   * {@code SOME 1}. Not a constructor of {@code variant}, whose values need a
   * session to construct.
   */
  private static boolean isConstructor(Core.Apply apply) {
    if (apply.fn.op != Op.ID || !(apply.type instanceof DataType)) {
      return false;
    }
    final DataType dataType = (DataType) apply.type;
    return !dataType.name.equals("variant")
        && dataType.typeConstructors.containsKey(
            ((Core.Id) apply.fn).idPat.name);
  }
}

// End Folder.java
