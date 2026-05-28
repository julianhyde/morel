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

import com.google.common.collect.ImmutableList;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.type.FnType;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import org.checkerframework.checker.nullness.qual.Nullable;

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
   * Returns whether {@code scan.exp} is an infinite single-constructor range
   * list. Used by {@link SuchThatShuttle#containsUnbounded} so that such scans
   * go through the FBBT/Expander pipeline.
   */
  static boolean isInfiniteRangeScan(Core.Scan scan) {
    return match(scan) != null;
  }

  /**
   * If {@code scan.exp} is {@code Range.flatten [<single_infinite_ctor>]},
   * returns a {@link ScanInfo}; otherwise null.
   */
  static @Nullable ScanInfo match(Core.Scan scan) {
    if (!(scan.pat instanceof Core.NamedPat)) {
      return null;
    }
    final Core.NamedPat namedPat = (Core.NamedPat) scan.pat;
    if (!(scan.exp instanceof Core.Apply)) {
      return null;
    }
    final Core.Apply apply = (Core.Apply) scan.exp;
    if (apply.builtIn() != BuiltIn.RANGE_FLATTEN) {
      return null;
    }
    if (!apply.arg.isCallTo(BuiltIn.Z_LIST)) {
      return null;
    }
    final Core.Apply list = (Core.Apply) apply.arg;
    if (list.args().size() != 1) {
      return null;
    }
    final Core.Exp elem = list.args().get(0);
    if (!(elem instanceof Core.Apply)) {
      return null;
    }
    final Core.Apply ctor = (Core.Apply) elem;
    if (!(ctor.fn instanceof Core.Id)) {
      return null;
    }
    final BuiltIn.Constructor ctorEnum =
        BuiltIn.Constructor.forName(((Core.Id) ctor.fn).idPat.name);
    if (ctorEnum == null) {
      return null;
    }
    switch (ctorEnum) {
      case RANGE_AT_LEAST:
        return new ScanInfo(namedPat, ctor.arg, BuiltIn.OP_GE);
      case RANGE_AT_MOST:
        return new ScanInfo(namedPat, ctor.arg, BuiltIn.OP_LE);
      case RANGE_GREATER_THAN:
        return new ScanInfo(namedPat, ctor.arg, BuiltIn.OP_GT);
      case RANGE_LESS_THAN:
        return new ScanInfo(namedPat, ctor.arg, BuiltIn.OP_LT);
      default:
        return null;
    }
  }

  /**
   * For each infinite-range scan in {@code from}, finds a literal bound on the
   * scan's pattern in the first downstream {@code where} step and combines it
   * with the scan's existing range constructor to form a finite constructor.
   * The scan exp is replaced (preserving the list type), and the consumed
   * conjunct is removed from the where.
   */
  static Core.From apply(TypeSystem typeSystem, Core.From from) {
    // Locate the first where step.
    int whereIdx = -1;
    for (int i = 0; i < from.steps.size(); i++) {
      if (from.steps.get(i) instanceof Core.Where) {
        whereIdx = i;
        break;
      }
    }
    if (whereIdx < 0) {
      return from;
    }
    final Core.Where where = (Core.Where) from.steps.get(whereIdx);
    final List<Core.Exp> whereConjuncts = core.decomposeAnd(where.exp);
    final Set<Core.Exp> consumed = new HashSet<>();
    final List<Core.FromStep> newSteps = new ArrayList<>(from.steps.size());
    boolean changed = false;
    for (int i = 0; i < from.steps.size(); i++) {
      final Core.FromStep step = from.steps.get(i);
      if (i == whereIdx) {
        newSteps.add(step);
        continue;
      }
      if (step.op == Op.SCAN) {
        final Core.Scan scan = (Core.Scan) step;
        final ScanInfo info = match(scan);
        if (info != null) {
          final Tightening t =
              findTightening(typeSystem, info, whereConjuncts, consumed);
          if (t != null) {
            newSteps.add(
                scan.copy(scan.env, scan.pat, t.newExp, scan.condition));
            consumed.add(t.consumedConjunct);
            changed = true;
            continue;
          }
        }
      }
      newSteps.add(step);
    }
    if (!changed) {
      return from;
    }
    final List<Core.Exp> remaining = new ArrayList<>();
    for (Core.Exp c : whereConjuncts) {
      if (!consumed.contains(c)) {
        remaining.add(c);
      }
    }
    if (remaining.isEmpty()) {
      newSteps.remove(whereIdx);
    } else {
      newSteps.set(
          whereIdx, where.copy(core.andAlso(typeSystem, remaining), where.env));
    }
    return core.from(typeSystem, newSteps);
  }

  /**
   * Searches {@code whereConjuncts} for the tightest literal bound on {@code
   * info.pat} that complements {@code info}'s open side. Returns a {@link
   * Tightening} or {@code null} if no such bound exists.
   */
  private static @Nullable Tightening findTightening(
      TypeSystem typeSystem,
      ScanInfo info,
      List<Core.Exp> whereConjuncts,
      Set<Core.Exp> consumed) {
    // AT_LEAST/GREATER_THAN -> need an upper bound; AT_MOST/LESS_THAN ->
    // need a lower bound.
    final boolean needUpper =
        info.op == BuiltIn.OP_GE || info.op == BuiltIn.OP_GT;
    Core.Exp bestConjunct = null;
    BigDecimal bestValue = null;
    boolean bestStrict = false;
    for (Core.Exp c : whereConjuncts) {
      if (consumed.contains(c)) {
        continue;
      }
      final LiteralBound lb = extractLiteralBound(c, info.pat);
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
    final BigDecimal scanValue = Bounds.literalInt(info.value);
    if (scanValue == null) {
      return null;
    }
    final BigDecimal lowerValue;
    final boolean lowerStrict;
    final BigDecimal upperValue;
    final boolean upperStrict;
    if (needUpper) {
      lowerValue = scanValue;
      lowerStrict = info.op == BuiltIn.OP_GT;
      upperValue = bestValue;
      upperStrict = bestStrict;
    } else {
      lowerValue = bestValue;
      lowerStrict = bestStrict;
      upperValue = scanValue;
      upperStrict = info.op == BuiltIn.OP_LT;
    }
    if (lowerValue.compareTo(upperValue) > 0) {
      return null;
    }
    final BuiltIn.Constructor ctor = finiteCtor(lowerStrict, upperStrict);
    final Core.Exp newExp =
        buildRangeFlatten(
            typeSystem, info.pat.type, ctor, lowerValue, upperValue);
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
   * list}.
   */
  private static Core.Exp buildRangeFlatten(
      TypeSystem typeSystem,
      Type elemType,
      BuiltIn.Constructor ctor,
      BigDecimal lo,
      BigDecimal hi) {
    final Type rangeType = typeSystem.range(elemType);
    final FnType conFnType =
        typeSystem.fnType(typeSystem.tupleType(elemType, elemType), rangeType);
    final Core.IdPat conIdPat = core.idPat(conFnType, ctor.constructor, 0);
    final Core.Exp loLit = core.literal((PrimitiveType) elemType, lo);
    final Core.Exp hiLit = core.literal((PrimitiveType) elemType, hi);
    final Core.Apply rangeExp =
        core.apply(
            Pos.ZERO,
            rangeType,
            core.id(conIdPat),
            core.tuple(typeSystem, loLit, hiLit));
    final Core.Exp rangeListExp =
        core.list(typeSystem, rangeType, ImmutableList.of(rangeExp));
    return core.call(
        typeSystem, BuiltIn.RANGE_FLATTEN, elemType, Pos.ZERO, rangeListExp);
  }

  /**
   * If {@code c} is {@code pat OP literal} (or the mirrored form) with OP in
   * {@code <, <=, >, >=}, returns a {@link LiteralBound}; otherwise null.
   */
  private static @Nullable LiteralBound extractLiteralBound(
      Core.Exp c, Core.NamedPat pat) {
    if (c.op != Op.APPLY) {
      return null;
    }
    final BuiltIn op = c.builtIn();
    if (op == null) {
      return null;
    }
    final Core.Exp lhs = c.arg(0);
    final Core.Exp rhs = c.arg(1);
    final boolean lhsIsPat = Bounds.isIdRef(lhs, pat);
    final boolean rhsIsPat = Bounds.isIdRef(rhs, pat);
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
    final BigDecimal value = Bounds.literalInt(constSide);
    if (value == null) {
      return null;
    }
    switch (normalized) {
      case OP_LT:
        return new LiteralBound(true, true, value);
      case OP_LE:
        return new LiteralBound(true, false, value);
      case OP_GT:
        return new LiteralBound(false, true, value);
      case OP_GE:
        return new LiteralBound(false, false, value);
      default:
        return null;
    }
  }

  /**
   * Descriptor of an infinite single-constructor range scan: which pattern it
   * binds, the constructor's int value, and the comparison op that value
   * implies on the pattern (e.g. {@code AT_LEAST 1} implies {@code x >= 1}, so
   * {@code op} is {@code OP_GE}).
   */
  static final class ScanInfo {
    final Core.NamedPat pat;
    final Core.Exp value;
    final BuiltIn op;

    ScanInfo(Core.NamedPat pat, Core.Exp value, BuiltIn op) {
      this.pat = pat;
      this.value = value;
      this.op = op;
    }

    /**
     * Returns the bound expression {@code pat OP value} suitable for injection
     * into a where clause.
     */
    Core.Exp boundExp(TypeSystem typeSystem) {
      return core.call(
          typeSystem, op, PrimitiveType.BOOL, Pos.ZERO, core.id(pat), value);
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
  private static final class Tightening {
    final Core.Exp newExp;
    final Core.Exp consumedConjunct;

    Tightening(Core.Exp newExp, Core.Exp consumedConjunct) {
      this.newExp = newExp;
      this.consumedConjunct = consumedConjunct;
    }
  }
}

// End RangePushdown.java
