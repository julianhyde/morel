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

import static java.util.Objects.requireNonNull;
import static net.hydromatic.morel.ast.CoreBuilder.core;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.type.FnType;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import org.jspecify.annotations.Nullable;

/**
 * Rewrites scans whose expression is an infinite single-constructor range list
 * (e.g. {@code from x in [1..]}) by combining the range constructor with a
 * matching literal bound conjunct from the same {@code from}'s {@code where}
 * step, producing a finite range:
 *
 * <pre>{@code
 * from x in [1..] where x < 5      // Range.flatten [AT_LEAST 1]
 *   -->
 * from x in [1..^5]                // Range.flatten [CLOSED_OPEN (1, 5)]
 * }</pre>
 *
 * <p>The rewrite preserves the scan's result type: a list scan stays a list
 * scan, just with a finite range. The consumed where conjunct is dropped (it is
 * now expressed by the scan range).
 *
 * <p>Designed to run after {@link Fbbt#strengthen}, so FBBT-deduced bounds
 * (e.g. {@code x <= 7} inferred from a non-linear constraint) are pushed into
 * infinite-range scans too.
 */
final class RangePushdown {
  private RangePushdown() {}

  /**
   * Returns whether an expression is an infinite single-constructor range list.
   * A leaf is a bare expression, with no pattern to go with it.
   */
  static boolean isInfiniteRange(Core.Exp exp) {
    if (!(exp instanceof Core.Apply)) {
      return false;
    }
    Core.Apply apply = (Core.Apply) exp;
    if (apply.builtIn() == BuiltIn.BAG_FROM_LIST
        && apply.arg instanceof Core.Apply) {
      apply = (Core.Apply) apply.arg;
    }
    if (apply.builtIn() != BuiltIn.RANGE_FLATTEN
        || !apply.arg.isCallTo(BuiltIn.Z_LIST)) {
      return false;
    }
    final Core.Apply list = (Core.Apply) apply.arg;
    if (list.args().size() != 1
        || !(list.args().get(0) instanceof Core.Apply)) {
      return false;
    }
    final Core.Apply ctor = (Core.Apply) list.args().get(0);
    if (!(ctor.fn instanceof Core.Id)) {
      return false;
    }
    final BuiltIn.Constructor ctorEnum =
        BuiltIn.Constructor.forName(((Core.Id) ctor.fn).idPat.name);
    if (ctorEnum == null) {
      return false;
    }
    switch (ctorEnum) {
      case RANGE_AT_LEAST:
      case RANGE_AT_MOST:
      case RANGE_GREATER_THAN:
      case RANGE_LESS_THAN:
        return true;
      default:
        return false;
    }
  }

  /**
   * Tightens a tree's leaf against a condition carried down to it, and returns
   * the finite range and the conjunct it consumed, or null.
   *
   * <p>{@code filter [$0 < 5] (#flatten Range ([AT_LEAST 1]))} is {@code
   * #flatten Range ([CLOSED_OPEN (1, 5)])}, which is finite, so grounding has
   * nothing left to do. The condition is on {@code $0}, because a leaf has no
   * pattern.
   */
  static @Nullable Tightening tighten(
      TypeSystem typeSystem,
      Core.Exp leaf,
      Core.IdPat row,
      List<Core.Exp> conditions) {
    return tighten(
        typeSystem,
        leaf,
        conditions,
        e -> e instanceof Core.Id && ((Core.Id) e).idPat.equals(row));
  }

  /**
   * As {@link #tighten(TypeSystem, Core.Exp, Core.IdPat, List)}, where the leaf
   * is one of a join's and the conditions name it rather than saying {@code
   * $0}.
   */
  static @Nullable Tightening tighten(
      TypeSystem typeSystem,
      Core.Exp leaf,
      List<Core.Exp> conditions,
      Predicate<Core.Exp> isVar) {
    final RangeInfo info = matchExp(leaf);
    if (info == null) {
      return null;
    }
    return findTightening(
        typeSystem,
        leaf.type.elementType(),
        info.op,
        info.value,
        info.bagWrapped,
        isVar,
        conditions,
        ImmutableSet.of());
  }

  /**
   * Returns what an infinite range says about its element -- {@code v >= 1} for
   * {@code [1..]} -- or null if the leaf is not one.
   *
   * <p>FBBT deduces a bound from the bounds it is given, and a range's is not
   * among them until it is written as a constraint. {@code Expander} does the
   * same for a step list, in {@code rangeImpliedBounds}.
   */
  static Core.@Nullable Exp impliedBound(
      TypeSystem typeSystem, Core.Exp leaf, Core.Exp varExp) {
    final RangeInfo info = matchExp(leaf);
    if (info == null) {
      return null;
    }
    // CHAR_OP_* are concretely typed (char * char -> bool); the OP_* family is
    // polymorphic and needs the type-parameter form of core.call.
    switch (info.op) {
      case CHAR_OP_LT:
      case CHAR_OP_LE:
      case CHAR_OP_GT:
      case CHAR_OP_GE:
        return core.call(typeSystem, info.op, varExp, info.value);
      default:
        return core.call(
            typeSystem,
            info.op,
            PrimitiveType.BOOL,
            Pos.ZERO,
            varExp,
            info.value);
    }
  }

  /**
   * If {@code exp} is {@code Range.flatten [<single_infinite_ctor>]}, possibly
   * wrapped in {@code Bag.fromList}, returns what the constructor says;
   * otherwise null. Whether the element is a char, a leaf says with its type.
   */
  private static @Nullable RangeInfo matchExp(Core.Exp exp) {
    if (!(exp instanceof Core.Apply)) {
      return null;
    }
    Core.Apply apply = (Core.Apply) exp;
    final boolean bagWrapped;
    if (apply.builtIn() == BuiltIn.BAG_FROM_LIST
        && apply.arg instanceof Core.Apply) {
      apply = (Core.Apply) apply.arg;
      bagWrapped = true;
    } else {
      bagWrapped = false;
    }
    if (apply.builtIn() != BuiltIn.RANGE_FLATTEN
        || !apply.arg.isCallTo(BuiltIn.Z_LIST)) {
      return null;
    }
    final Core.Apply list = (Core.Apply) apply.arg;
    if (list.args().size() != 1
        || !(list.args().get(0) instanceof Core.Apply)) {
      return null;
    }
    final Core.Apply ctor = (Core.Apply) list.args().get(0);
    if (!(ctor.fn instanceof Core.Id)) {
      return null;
    }
    final BuiltIn.Constructor ctorEnum =
        BuiltIn.Constructor.forName(((Core.Id) ctor.fn).idPat.name);
    if (ctorEnum == null) {
      return null;
    }
    final boolean isChar = exp.type.elementType() == PrimitiveType.CHAR;
    switch (ctorEnum) {
      case RANGE_AT_LEAST:
        return new RangeInfo(
            ctor.arg, isChar ? BuiltIn.CHAR_OP_GE : BuiltIn.OP_GE, bagWrapped);
      case RANGE_AT_MOST:
        return new RangeInfo(
            ctor.arg, isChar ? BuiltIn.CHAR_OP_LE : BuiltIn.OP_LE, bagWrapped);
      case RANGE_GREATER_THAN:
        return new RangeInfo(
            ctor.arg, isChar ? BuiltIn.CHAR_OP_GT : BuiltIn.OP_GT, bagWrapped);
      case RANGE_LESS_THAN:
        return new RangeInfo(
            ctor.arg, isChar ? BuiltIn.CHAR_OP_LT : BuiltIn.OP_LT, bagWrapped);
      default:
        return null;
    }
  }

  /** What an infinite single-constructor range says, without a pattern. */
  private static final class RangeInfo {
    final Core.Exp value;
    final BuiltIn op;
    final boolean bagWrapped;

    RangeInfo(Core.Exp value, BuiltIn op, boolean bagWrapped) {
      this.value = value;
      this.op = op;
      this.bagWrapped = bagWrapped;
    }
  }

  /**
   * Searches {@code whereConjuncts} for the tightest literal bound that
   * complements the range's open side, and returns a {@link Tightening} or null
   * if there is none.
   *
   * <p>A leaf has no pattern, and the bound is on {@code $0}; {@code isVar} is
   * what tells one side of a comparison from the other.
   */
  private static @Nullable Tightening findTightening(
      TypeSystem typeSystem,
      Type elementType,
      BuiltIn scanOp,
      Core.Exp scanValue,
      boolean bagWrapped,
      Predicate<Core.Exp> isVar,
      List<Core.Exp> whereConjuncts,
      Set<Core.Exp> consumed) {
    // AT_LEAST/GREATER_THAN -> need an upper bound; AT_MOST/LESS_THAN ->
    // need a lower bound.
    final boolean needUpper =
        scanOp == BuiltIn.OP_GE
            || scanOp == BuiltIn.OP_GT
            || scanOp == BuiltIn.CHAR_OP_GE
            || scanOp == BuiltIn.CHAR_OP_GT;
    Core.Exp bestConjunct = null;
    BigDecimal bestValue = null;
    boolean bestStrict = false;
    for (Core.Exp c : whereConjuncts) {
      if (consumed.contains(c)) {
        continue;
      }
      final LiteralBound lb = extractLiteralBound(c, isVar);
      if (lb == null || lb.isUpper != needUpper) {
        continue;
      }
      if (bestValue == null
          || needUpper && lb.value.compareTo(bestValue) < 0
          || !needUpper && lb.value.compareTo(bestValue) > 0) {
        bestValue = lb.value;
        bestStrict = lb.strict;
        bestConjunct = c;
      }
    }
    if (bestConjunct == null) {
      return null;
    }
    // 'bestValue' is assigned whenever 'bestConjunct' is.
    final BigDecimal bestValue2 = requireNonNull(bestValue);
    final Core.@Nullable Literal scanLit = Bounds.scalarLiteral(scanValue);
    if (scanLit == null) {
      return null;
    }
    final BigDecimal scanNumber = Bounds.asBigDecimal(scanLit);
    final BigDecimal lowerValue;
    final boolean lowerStrict;
    final BigDecimal upperValue;
    final boolean upperStrict;
    if (needUpper) {
      lowerValue = scanNumber;
      lowerStrict = scanOp == BuiltIn.OP_GT || scanOp == BuiltIn.CHAR_OP_GT;
      upperValue = bestValue2;
      upperStrict = bestStrict;
    } else {
      lowerValue = bestValue2;
      lowerStrict = bestStrict;
      upperValue = scanNumber;
      upperStrict = scanOp == BuiltIn.OP_LT || scanOp == BuiltIn.CHAR_OP_LT;
    }
    if (lowerValue.compareTo(upperValue) > 0) {
      return null;
    }
    final BuiltIn.Constructor ctor = finiteCtor(lowerStrict, upperStrict);
    final Core.Exp newExp =
        buildRangeFlatten(
            typeSystem, elementType, ctor, lowerValue, upperValue, bagWrapped);
    return new Tightening(newExp, bestConjunct);
  }

  private static BuiltIn.Constructor finiteCtor(
      boolean lowerStrict, boolean upperStrict) {
    if (lowerStrict) {
      return upperStrict
          ? BuiltIn.Constructor.RANGE_OPEN
          : BuiltIn.Constructor.RANGE_OPEN_CLOSED;
    }
    return upperStrict
        ? BuiltIn.Constructor.RANGE_CLOSED_OPEN
        : BuiltIn.Constructor.RANGE_CLOSED;
  }

  /**
   * Builds {@code Range.flatten [ctor (lo, hi)]} of type {@code <elemType>
   * list}, optionally wrapped in {@code Bag.fromList} to give an {@code
   * <elemType> bag}.
   */
  private static Core.Exp buildRangeFlatten(
      TypeSystem typeSystem,
      Type elemType,
      BuiltIn.Constructor ctor,
      BigDecimal lo,
      BigDecimal hi,
      boolean bagWrapped) {
    final Type rangeType = typeSystem.range(elemType);
    final FnType conFnType =
        typeSystem.fnType(typeSystem.tupleType(elemType, elemType), rangeType);
    final Core.IdPat conIdPat = core.idPat(conFnType, ctor.constructor, 0);
    final PrimitiveType pType = (PrimitiveType) elemType;
    final Core.Exp loLit = numericValueAsLiteral(pType, lo);
    final Core.Exp hiLit = numericValueAsLiteral(pType, hi);
    final Core.Apply rangeExp =
        core.apply(
            Pos.ZERO,
            rangeType,
            core.id(conIdPat),
            core.tuple(typeSystem, loLit, hiLit));
    final Core.Exp rangeListExp =
        core.list(typeSystem, rangeType, ImmutableList.of(rangeExp));
    final Core.Apply flatten =
        core.call(
            typeSystem,
            BuiltIn.RANGE_FLATTEN,
            elemType,
            Pos.ZERO,
            rangeListExp);
    return bagWrapped
        ? core.call(
            typeSystem, BuiltIn.BAG_FROM_LIST, elemType, Pos.ZERO, flatten)
        : flatten;
  }

  /**
   * Builds a literal of {@code type} from the {@link BigDecimal}-encoded value.
   * For int and real this is a straight {@code core.literal} call; for char,
   * the BigDecimal carries the integer character code and the literal is
   * rebuilt via {@link net.hydromatic.morel.ast.CoreBuilder#charLiteral(char)}.
   */
  private static Core.Literal numericValueAsLiteral(
      PrimitiveType type, BigDecimal value) {
    if (type == PrimitiveType.CHAR) {
      return core.charLiteral((char) value.intValueExact());
    }
    return core.literal(type, value);
  }

  /**
   * If {@code c} is {@code pat OP literal} (or the mirrored form) with OP in
   * {@code <, <=, >, >=}, returns a {@link LiteralBound}; otherwise null.
   */
  private static @Nullable LiteralBound extractLiteralBound(
      Core.Exp c, Predicate<Core.Exp> isVar) {
    if (c.op != Op.APPLY) {
      return null;
    }
    final BuiltIn op = c.builtIn();
    if (op == null) {
      return null;
    }
    final Core.Exp lhs = c.arg(0);
    final Core.Exp rhs = c.arg(1);
    final boolean lhsIsPat = isVar.test(lhs);
    final boolean rhsIsPat = isVar.test(rhs);
    final BuiltIn normalized;
    final Core.Exp constSide;
    if (lhsIsPat && !rhsIsPat) {
      normalized = op;
      constSide = rhs;
    } else if (rhsIsPat && !lhsIsPat) {
      normalized = op.reverse();
      constSide = lhs;
    } else {
      return null;
    }
    final Core.@Nullable Literal valueLit = Bounds.scalarLiteral(constSide);
    if (valueLit == null) {
      return null;
    }
    final BigDecimal value = Bounds.asBigDecimal(valueLit);
    switch (normalized) {
      case OP_LT:
      case CHAR_OP_LT:
        return new LiteralBound(true, true, value);
      case OP_LE:
      case CHAR_OP_LE:
        return new LiteralBound(true, false, value);
      case OP_GT:
      case CHAR_OP_GT:
        return new LiteralBound(false, true, value);
      case OP_GE:
      case CHAR_OP_GE:
        return new LiteralBound(false, false, value);
      default:
        return null;
    }
  }

  /** A literal bound conjunct on a specific pattern. */
  private static final class LiteralBound {
    final boolean isUpper;
    final boolean strict;
    final BigDecimal value;

    LiteralBound(boolean isUpper, boolean strict, BigDecimal value) {
      this.isUpper = isUpper;
      this.strict = strict;
      this.value = value;
    }
  }

  /**
   * Result of {@link #findTightening}: the new finite-range scan expression and
   * the where conjunct it consumed.
   */
  static final class Tightening {
    final Core.Exp newExp;
    final Core.Exp consumedConjunct;

    Tightening(Core.Exp newExp, Core.Exp consumedConjunct) {
      this.newExp = newExp;
      this.consumedConjunct = consumedConjunct;
    }
  }
}

// End RangePushdown.java
