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

import static com.google.common.base.Preconditions.checkArgument;
import static net.hydromatic.morel.ast.CoreBuilder.core;

import com.google.common.collect.ImmutableList;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.CoreBuilder;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.RangeExtent;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.Pair;
import net.hydromatic.morel.util.Static;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Implementations of {@link Generator}, and supporting methods. */
class Generators {
  private Generators() {}

  @Nullable
  static Generator maybeGenerator(
      TypeSystem typeSystem,
      Core.Pat pat,
      boolean ordered,
      List<Core.Exp> constraints) {
    final Generator generator1 =
        maybePoint(typeSystem, pat, ordered, constraints);
    if (generator1 != null) {
      return generator1;
    }

    final Generator generator2 =
        maybeRange(typeSystem, pat, ordered, constraints);
    if (generator2 != null) {
      return generator2;
    }

    return maybeUnion(typeSystem, pat, ordered, constraints);
  }

  @Nullable
  static Generator maybePoint(
      TypeSystem typeSystem,
      Core.Pat pat,
      boolean ordered,
      List<Core.Exp> constraints) {
    final Core.@Nullable Exp value = point(pat, constraints);
    if (value == null) {
      return null;
    }
    return generatePoint(typeSystem, ordered, value);
  }

  /**
   * Creates an expression that generates a single value.
   *
   * @param ordered If true, generate a `list`, otherwise a `bag`
   * @param lower Lower bound
   */
  static Generator generatePoint(
      TypeSystem typeSystem, boolean ordered, Core.Exp lower) {
    final Core.Exp exp =
        ordered ? core.list(typeSystem, lower) : core.bag(typeSystem, lower);
    return new PointGenerator(exp, lower);
  }

  /**
   * Creates an expression that generates a values from several generators.
   *
   * @param ordered If true, generate a `list`, otherwise a `bag`
   * @param generators Generators
   */
  static Generator generateUnion(
      TypeSystem typeSystem, boolean ordered, List<Generator> generators) {
    final Core.Exp fn =
        core.functionLiteral(
            typeSystem, ordered ? BuiltIn.LIST_CONCAT : BuiltIn.BAG_CONCAT);
    final Type collectionType = generators.get(0).exp.type;
    Core.Exp arg =
        core.list(
            typeSystem,
            collectionType.arg(0),
            Static.transformEager(generators, g -> g.exp));
    final Core.Exp exp = core.apply(Pos.ZERO, collectionType, fn, arg);
    return new UnionGenerator(exp, generators);
  }

  @Nullable
  static Generator maybeUnion(
      TypeSystem typeSystem,
      Core.Pat pat,
      boolean ordered,
      List<Core.Exp> constraints) {
    constraints:
    for (Core.Exp constraint : constraints) {
      if (constraint.isCallTo(BuiltIn.Z_ORELSE)) {
        final List<Core.Exp> orList = core.decomposeOr(constraint);
        final List<Generator> generators = new ArrayList<>();
        for (Core.Exp exp : orList) {
          final List<Core.Exp> andList = core.decomposeAnd(exp);
          final Generator generator =
              maybeGenerator(typeSystem, pat, ordered, andList);
          if (generator == null) {
            continue constraints;
          }
          generators.add(generator);
        }
        return generateUnion(typeSystem, ordered, generators);
      }
    }
    return null;
  }

  @Nullable
  static Generator maybeRange(
      TypeSystem typeSystem,
      Core.Pat pat,
      boolean ordered,
      List<Core.Exp> constraints) {
    if (pat.type != PrimitiveType.INT) {
      return null;
    }
    final @Nullable Pair<Core.Exp, Boolean> lower =
        lowerBound(pat, constraints);
    if (lower == null) {
      return null;
    }
    final @Nullable Pair<Core.Exp, Boolean> upper =
        upperBound(pat, constraints);
    if (upper == null) {
      return null;
    }
    return Generators.generateRange(
        typeSystem, ordered, lower.left, lower.right, upper.left, upper.right);
  }

  /**
   * Creates an expression that generates a range of integer values.
   *
   * <p>For example, {@code generateRange(3, true, 8, false)} generates a range
   * {@code 3 < x <= 8}, which yields the values {@code [4, 5, 6, 7, 8]}.
   *
   * @param ordered If true, generate a `list`, otherwise a `bag`
   * @param lower Lower bound
   * @param lowerStrict Whether the lower bound is strict (exclusive): true for
   *     {@code x > lower}, false for {@code x >= lower}
   * @param upper Upper bound
   * @param upperStrict Whether the upper bound is strict (exclusive): true for
   *     {@code x < upper}, false for {@code x <= upper}
   */
  static Generator generateRange(
      TypeSystem typeSystem,
      boolean ordered,
      Core.Exp lower,
      boolean lowerStrict,
      Core.Exp upper,
      boolean upperStrict) {
    // For x > lower, we want x >= lower + 1
    final Core.Exp lower2 =
        lowerStrict
            ? core.call(
                typeSystem,
                BuiltIn.Z_PLUS_INT,
                lower,
                core.intLiteral(BigDecimal.ONE))
            : lower;
    // For x < upper, we want x <= upper - 1
    final Core.Exp upper2 =
        upperStrict
            ? core.call(
                typeSystem,
                BuiltIn.Z_MINUS_INT,
                upper,
                core.intLiteral(BigDecimal.ONE))
            : upper;

    // List.tabulate(upper - lower + 1, fn k => lower + k)
    final Type type = PrimitiveType.INT;
    final Core.IdPat kPat = core.idPat(type, "k", 0);
    final Core.Exp upperMinusLower =
        core.call(typeSystem, BuiltIn.OP_MINUS, type, Pos.ZERO, upper2, lower2);
    final Core.Exp count =
        core.call(
            typeSystem,
            BuiltIn.OP_PLUS,
            type,
            Pos.ZERO,
            upperMinusLower,
            core.intLiteral(BigDecimal.ONE));
    final Core.Exp lowerPlusK =
        core.call(
            typeSystem, BuiltIn.Z_PLUS_INT, lower2, CoreBuilder.core.id(kPat));
    final Core.Fn fn = core.fn(typeSystem.fnType(type, type), kPat, lowerPlusK);
    BuiltIn tabulate = ordered ? BuiltIn.LIST_TABULATE : BuiltIn.BAG_TABULATE;
    Core.Apply exp = core.call(typeSystem, tabulate, type, Pos.ZERO, count, fn);
    final Core.Exp simplified = Simplifier.simplify(typeSystem, exp);
    //      final Set<Core.NamedPat> freeVars = freeVarsIn(simplified);
    return new RangeGenerator(
        simplified, lower, lowerStrict, upper, upperStrict);
  }

  /** Returns an extent generator, or null if expression is not an extent. */
  public static @Nullable Generator maybeExtent(Core.Exp exp) {
    return !exp.isCallTo(BuiltIn.Z_EXTENT) ? null : extent(exp);
  }

  /** Returns an extent generator. Throws if expression is not a constraint. */
  public static Generator extent(Core.Exp exp) {
    checkArgument(exp.isCallTo(BuiltIn.Z_EXTENT));
    final Core.Apply apply = (Core.Apply) exp;
    final Core.Literal literal = (Core.Literal) apply.arg;
    final RangeExtent rangeExtent = literal.unwrap(RangeExtent.class);
    final Generator.Cardinality cardinality =
        rangeExtent.iterable == null
            ? Generator.Cardinality.INFINITE
            : Generator.Cardinality.FINITE;

    return new ExtentGenerator(exp, cardinality);
  }

  /** If there is a predicate "pat = exp" or "exp = pat", returns "exp". */
  static Core.@Nullable Exp point(Core.Pat pat, List<Core.Exp> predicates) {
    for (Core.Exp constraint : predicates) {
      if (constraint.isCallTo(BuiltIn.OP_EQ)) {
        if (references(constraint.arg(0), pat)) {
          return constraint.arg(1);
        }
        if (references(constraint.arg(1), pat)) {
          return constraint.arg(0);
        }
      }
    }
    return null;
  }

  /**
   * If there is a predicate "pat > exp" or "pat >= exp", returns "exp" and
   * whether the comparison is strict.
   *
   * <p>We do not attempt to find the strongest such constraint. Clearly "p >
   * 10" is stronger than "p >= 0". But is "p > x" stronger than "p >= y"? If
   * the goal is to convert an infinite generator to a finite generator, any
   * constraint will do.
   */
  static @Nullable Pair<Core.Exp, Boolean> lowerBound(
      Core.Pat pat, List<Core.Exp> predicates) {
    for (Core.Exp constraint : predicates) {
      switch (constraint.builtIn()) {
        case OP_GT:
        case OP_GE:
          if (references(constraint.arg(0), pat)) {
            // "p > e" -> (strict, e); "p >= e" -> (non-strict, e).
            final boolean strict = constraint.builtIn() == BuiltIn.OP_GT;
            return Pair.of(constraint.arg(1), strict);
          }
          break;
        case OP_LT:
        case OP_LE:
          if (references(constraint.arg(1), pat)) {
            // "e < p" -> (strict, e); "e <= p" -> (non-strict, e).
            final boolean strict = constraint.builtIn() == BuiltIn.OP_LT;
            return Pair.of(constraint.arg(0), strict);
          }
          break;
      }
    }
    return null;
  }

  /**
   * If there is a constraint "pat < exp" or "pat <= exp", returns "exp" and
   * whether the comparison is strict.
   *
   * <p>Analogous to {@link #lowerBound(Core.Pat, List)}.
   */
  static @Nullable Pair<Core.Exp, Boolean> upperBound(
      Core.Pat pat, List<Core.Exp> predicates) {
    for (Core.Exp constraint : predicates) {
      switch (constraint.builtIn()) {
        case OP_LT:
        case OP_LE:
          if (references(constraint.arg(0), pat)) {
            // "p < e" -> (strict, e); "p <= e" -> (non-strict, e).
            final boolean strict = constraint.builtIn() == BuiltIn.OP_LT;
            return Pair.of(constraint.arg(1), strict);
          }
          break;
        case OP_GT:
        case OP_GE:
          if (references(constraint.arg(1), pat)) {
            // "e > p" -> (strict, e); "e >= p" -> (non-strict, e).
            final boolean strict = constraint.builtIn() == BuiltIn.OP_GT;
            return Pair.of(constraint.arg(0), strict);
          }
          break;
      }
    }
    return null;
  }

  private static boolean references(Core.Exp arg, Core.Pat pat) {
    return arg.op == Op.ID && ((Core.Id) arg).idPat.equals(pat);
  }

  /**
   * Generator that generates a range of integers from {@code lower} to {@code
   * upper}.
   */
  static class RangeGenerator extends Generator {
    private final Core.Exp lower;
    private final boolean lowerStrict;
    private final Core.Exp upper;
    private final boolean upperStrict;

    RangeGenerator(
        Core.Exp exp,
        Core.Exp lower,
        boolean lowerStrict,
        Core.Exp upper,
        boolean upperStrict) {
      super(exp, Cardinality.FINITE);
      this.lower = lower;
      this.lowerStrict = lowerStrict;
      this.upper = upper;
      this.upperStrict = upperStrict;
    }

    @Override
    Core.Exp simplify(Core.Pat pat, Core.Exp exp) {
      // Simplify "p > lower && p < upper"
      if (exp.isCallTo(BuiltIn.Z_ANDALSO)) {
        final List<Core.Exp> ands = core.decomposeAnd(exp);
        final Pair<Core.Exp, Boolean> lowerBound = lowerBound(pat, ands);
        final Pair<Core.Exp, Boolean> upperBound = upperBound(pat, ands);
        if (lowerBound != null && upperBound != null) {
          if (lowerBound.left.equals(this.lower)
              && upperBound.left.equals(this.upper)) {
            return core.boolLiteral(true);
          }
          if (lowerBound.left.isConstant()
              && upperBound.left.isConstant()
              && this.lower.isConstant()
              && this.upper.isConstant()) {
            return core.boolLiteral(
                gt(lowerBound.left, this.lower, lowerBound.right)
                    && gt(this.upper, upperBound.left, upperBound.right));
          }
        }
      }
      return exp;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static boolean gt(Core.Exp left, Core.Exp right, boolean strict) {
      final Comparable leftVal = ((Core.Literal) left).value;
      final Comparable rightVal = ((Core.Literal) right).value;
      int c = leftVal.compareTo(rightVal);
      return strict ? c > 0 : c >= 0;
    }
  }

  /**
   * Generator that generates a range of integers from {@code lower} to {@code
   * upper}.
   */
  static class PointGenerator extends Generator {
    private final Core.Exp point;

    PointGenerator(Core.Exp exp, Core.Exp point) {
      super(exp, Cardinality.SINGLE);
      this.point = point;
    }

    @Override
    Core.Exp simplify(Core.Pat pat, Core.Exp exp) {
      // Simplify "p = point" to true.
      if (exp.isCallTo(BuiltIn.OP_EQ)) {
        final Core.@Nullable Exp point = point(pat, ImmutableList.of(exp));
        if (point != null) {
          if (point.equals(this.point)) {
            return core.boolLiteral(true);
          }
          if (point.isConstant() && this.point.isConstant()) {
            return core.boolLiteral(false);
          }
        }
      }
      return exp;
    }
  }

  /** Generator that generates a union of several underlying generators. */
  static class UnionGenerator extends Generator {
    private final List<Generator> generators;

    UnionGenerator(Core.Exp exp, List<Generator> generators) {
      super(exp, Cardinality.FINITE);
      this.generators = ImmutableList.copyOf(generators);
      checkArgument(generators.size() >= 2);
    }

    @Override
    Core.Exp simplify(Core.Pat pat, Core.Exp exp) {
      for (Generator generator : generators) {
        exp = generator.simplify(pat, exp);
      }
      return exp;
    }
  }

  /**
   * Generator that generates all values of a type. For most types it is
   * infinite.
   */
  static class ExtentGenerator extends Generator {
    private ExtentGenerator(Core.Exp exp, Cardinality cardinality) {
      super(exp, cardinality);
    }

    @Override
    Core.Exp simplify(Core.Pat pat, Core.Exp exp) {
      return exp;
    }
  }
}

// End Generators.java
