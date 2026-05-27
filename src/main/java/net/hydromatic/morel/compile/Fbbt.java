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
import com.google.common.collect.ImmutableRangeSet;
import com.google.common.collect.Range;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.TypeSystem;

/**
 * Feasibility-based bound tightening (FBBT).
 *
 * <p>Given the conjunction of conjuncts in a {@code where} clause, FBBT
 * tightens the per-variable feasible interval by propagating each constraint,
 * iterating to a fixed point. Bounds deduced by FBBT are appended to the {@code
 * where} clause as new conjuncts; the existing range extractor in {@link
 * Generators} then turns them into finite generators.
 *
 * <p>The current FBBT scope is integer-valued patterns over linear constraints.
 * Other primitive numeric types and non-linear constraints will be added
 * incrementally.
 *
 * <p>See <a href="https://github.com/hydromatic/morel/issues/373">issue
 * #373</a>.
 */
class Fbbt {
  /**
   * Maximum number of fixed-point iterations. FBBT typically converges in a
   * small number of rounds; this is a safety cap.
   */
  private static final int MAX_ROUNDS = 8;

  private static final ImmutableRangeSet<BigDecimal> ALL =
      ImmutableRangeSet.of(Range.all());

  private static final ImmutableList<Propagator> PROPAGATORS =
      ImmutableList.of(new ConstantBoundPropagator());

  private Fbbt() {}

  /**
   * Tightens the bounds of each pattern in {@code unboundedPats} by propagating
   * the conjuncts of {@code whereExp} to a fixed point.
   *
   * <p>Returns a strengthened where-expression with new range conjuncts
   * appended, or the original expression unchanged if FBBT made no progress.
   *
   * @param typeSystem Type system
   * @param unboundedPats Patterns to deduce bounds for (typically the extent
   *     patterns of a {@code from})
   * @param whereExp Conjunction of constraints from a {@code where} clause
   */
  static Core.Exp strengthen(
      TypeSystem typeSystem,
      Set<Core.NamedPat> unboundedPats,
      Core.Exp whereExp) {
    final State state = new State(unboundedPats);
    if (state.isEmpty()) {
      return whereExp;
    }
    final List<Core.Exp> conjuncts = core.decomposeAnd(whereExp);
    iterateToFixedPoint(state, conjuncts);
    return augmentWhere(typeSystem, whereExp, state);
  }

  /** Runs propagators on each conjunct, iterating until no bound tightens. */
  private static void iterateToFixedPoint(
      State state, List<Core.Exp> conjuncts) {
    for (int round = 0; round < MAX_ROUNDS; round++) {
      boolean changed = false;
      for (Core.Exp conjunct : conjuncts) {
        for (Propagator p : PROPAGATORS) {
          changed |= p.propagate(conjunct, state);
        }
      }
      if (!changed) {
        return;
      }
    }
  }

  /**
   * If FBBT deduced any tighter bounds, appends them as new conjuncts to {@code
   * whereExp}; otherwise returns it unchanged.
   */
  private static Core.Exp augmentWhere(
      TypeSystem typeSystem, Core.Exp whereExp, State state) {
    final ImmutableList.Builder<Core.Exp> extras = ImmutableList.builder();
    state.forEachTightened(
        (pat, rangeSet) -> {
          for (Range<BigDecimal> r : rangeSet.asRanges()) {
            if (r.hasLowerBound()) {
              extras.add(boundConjunct(typeSystem, pat, r, true));
            }
            if (r.hasUpperBound()) {
              extras.add(boundConjunct(typeSystem, pat, r, false));
            }
          }
        });
    final ImmutableList<Core.Exp> extraConjuncts = extras.build();
    if (extraConjuncts.isEmpty()) {
      return whereExp;
    }
    return core.andAlso(
        typeSystem,
        ImmutableList.<Core.Exp>builder()
            .add(whereExp)
            .addAll(extraConjuncts)
            .build());
  }

  /**
   * Builds a conjunct expressing one side of a range as a Core.Exp. For
   * example, {@code (pat=x, range=[1, 8], lower=true)} returns {@code x >= 1};
   * with {@code lower=false} returns {@code x <= 8}.
   */
  private static Core.Exp boundConjunct(
      TypeSystem typeSystem,
      Core.NamedPat pat,
      Range<BigDecimal> range,
      boolean lower) {
    final BigDecimal value =
        lower ? range.lowerEndpoint() : range.upperEndpoint();
    final boolean strict =
        (lower ? range.lowerBoundType() : range.upperBoundType())
            == com.google.common.collect.BoundType.OPEN;
    final Core.Exp idExp = core.id(pat);
    final Core.Exp constExp = core.literal(PrimitiveType.INT, value);
    if (lower) {
      // x > c (strict) or x >= c
      return strict
          ? core.greaterThan(typeSystem, idExp, constExp)
          : core.greaterThanOrEqualTo(typeSystem, idExp, constExp);
    } else {
      // x < c (strict) or x <= c
      return strict
          ? core.lessThan(typeSystem, idExp, constExp)
          : core.call(
              typeSystem,
              BuiltIn.OP_LE,
              PrimitiveType.BOOL,
              Pos.ZERO,
              idExp,
              constExp);
    }
  }

  /** Per-pattern feasible interval (an {@link ImmutableRangeSet}). */
  static class State {
    private final Set<Core.NamedPat> pats;
    private final Map<Core.NamedPat, ImmutableRangeSet<BigDecimal>> intervals =
        new HashMap<>();

    State(Set<Core.NamedPat> pats) {
      this.pats = pats;
    }

    /**
     * Returns whether there are no integer patterns to deduce bounds for. (We
     * restrict to int for now.)
     */
    boolean isEmpty() {
      for (Core.NamedPat p : pats) {
        if (p.type == PrimitiveType.INT) {
          return false;
        }
      }
      return true;
    }

    /** Returns whether {@code pat} is one of the patterns under analysis. */
    boolean knows(Core.NamedPat pat) {
      return pats.contains(pat) && pat.type == PrimitiveType.INT;
    }

    /** Returns the current best interval for {@code pat}. */
    ImmutableRangeSet<BigDecimal> get(Core.NamedPat pat) {
      return intervals.getOrDefault(pat, ALL);
    }

    /**
     * Intersects {@code pat}'s current interval with {@code rangeSet}. Returns
     * whether the interval actually tightened.
     */
    boolean tighten(Core.NamedPat pat, ImmutableRangeSet<BigDecimal> rangeSet) {
      if (!knows(pat)) {
        return false;
      }
      final ImmutableRangeSet<BigDecimal> current = get(pat);
      final ImmutableRangeSet<BigDecimal> next = current.intersection(rangeSet);
      if (next.equals(current)) {
        return false;
      }
      intervals.put(pat, next);
      return true;
    }

    /** Calls {@code consumer} once per pattern with a tightened interval. */
    void forEachTightened(BoundsConsumer consumer) {
      intervals.forEach(
          (pat, rs) -> {
            if (!rs.equals(ALL)) {
              consumer.accept(pat, rs);
            }
          });
    }
  }

  /** Receives a tightened bound for use in materialization. */
  @FunctionalInterface
  interface BoundsConsumer {
    void accept(Core.NamedPat pat, ImmutableRangeSet<BigDecimal> rangeSet);
  }

  /**
   * Examines a single constraint and (possibly) tightens the bounds of one or
   * more patterns in {@code state}.
   */
  @FunctionalInterface
  interface Propagator {
    /** Returns whether any pattern's interval tightened. */
    boolean propagate(Core.Exp constraint, State state);
  }

  /**
   * Propagator for {@code x op c} and {@code c op x} where {@code c} is an
   * integer literal and {@code op} is one of {@code <, <=, >, >=, =}.
   *
   * <p>This is what the existing range extractor (in {@link Generators}) also
   * picks up; we duplicate the work here so the FBBT state has a useful
   * starting point for cross-variable propagators.
   */
  static class ConstantBoundPropagator implements Propagator {
    @Override
    public boolean propagate(Core.Exp constraint, State state) {
      if (constraint.op != Op.APPLY) {
        return false;
      }
      final BuiltIn builtIn = constraint.builtIn();
      if (builtIn == null) {
        return false;
      }
      switch (builtIn) {
        case OP_LT:
        case OP_LE:
        case OP_GT:
        case OP_GE:
        case OP_EQ:
          break;
        default:
          return false;
      }
      final Core.Exp lhs = constraint.arg(0);
      final Core.Exp rhs = constraint.arg(1);
      // Normalize so 'pat OP const'.
      final Core.NamedPat pat;
      final BigDecimal value;
      final BuiltIn normalized;
      if (lhs instanceof Core.Id && rhs instanceof Core.Literal) {
        pat = ((Core.Id) lhs).idPat;
        value = literalInt((Core.Literal) rhs);
        normalized = builtIn;
      } else if (rhs instanceof Core.Id && lhs instanceof Core.Literal) {
        pat = ((Core.Id) rhs).idPat;
        value = literalInt((Core.Literal) lhs);
        normalized = builtIn.reverse();
      } else {
        return false;
      }
      if (value == null || !state.knows(pat)) {
        return false;
      }
      return state.tighten(pat, rangeOf(normalized, value));
    }

    /**
     * Returns the half-open range that an integer-typed variable must lie in to
     * satisfy {@code v op c}.
     */
    private static ImmutableRangeSet<BigDecimal> rangeOf(
        BuiltIn op, BigDecimal c) {
      switch (op) {
        case OP_LT:
          return ImmutableRangeSet.of(Range.lessThan(c));
        case OP_LE:
          return ImmutableRangeSet.of(Range.atMost(c));
        case OP_GT:
          return ImmutableRangeSet.of(Range.greaterThan(c));
        case OP_GE:
          return ImmutableRangeSet.of(Range.atLeast(c));
        case OP_EQ:
          return ImmutableRangeSet.of(Range.singleton(c));
        default:
          throw new AssertionError(op);
      }
    }

    /**
     * Returns {@code lit}'s value as a {@link BigDecimal} if it's an int
     * literal, otherwise null.
     */
    private static BigDecimal literalInt(Core.Literal lit) {
      if (lit.op != Op.INT_LITERAL) {
        return null;
      }
      return (BigDecimal) lit.value;
    }
  }
}

// End Fbbt.java
