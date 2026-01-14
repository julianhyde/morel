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
import static com.google.common.collect.Iterables.getLast;
import static java.util.Objects.requireNonNull;
import static net.hydromatic.morel.ast.CoreBuilder.core;
import static net.hydromatic.morel.util.Static.skip;
import static net.hydromatic.morel.util.Static.skipLast;
import static net.hydromatic.morel.util.Static.transformEager;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.CoreBuilder;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.RangeExtent;
import net.hydromatic.morel.type.RecordLikeType;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.Ord;
import net.hydromatic.morel.util.Pair;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Implementations of {@link Generator}, and supporting methods. */
class Generators {
  private Generators() {}

  static boolean maybeGenerator(
      Cache cache, Core.Pat pat, boolean ordered, List<Core.Exp> constraints) {
    if (maybeElem(cache, pat, ordered, constraints)) {
      return true;
    }

    if (maybePoint(cache, pat, ordered, constraints)) {
      return true;
    }

    if (maybeRange(cache, pat, ordered, constraints)) {
      return true;
    }

    if (maybeExists(cache, pat, constraints)) {
      return true;
    }

    return maybeUnion(cache, pat, ordered, constraints);
  }

  /**
   * For each predicate "exists ... where pat ...", adds a generator for "pat".
   *
   * <p>Because we're in core, {@code exists} has been translated to a call to
   * {@link BuiltIn#RELATIONAL_NON_EMPTY}. The above query will look like
   * "Relational.nonEmpty (from ... where pat ...)".
   *
   * <p>Pattern can occur in other places than a {@code where} clause, but it
   * must be in a location that is <b>monotonic</b>. That is, where adding a
   * value to the generator can never cause the query to emit fewer rows.
   */
  private static boolean maybeExists(
      Cache cache, Core.Pat pat, List<Core.Exp> constraints) {
    constraint_loop:
    for (int j = 0; j < constraints.size(); j++) {
      final Core.Exp constraint = constraints.get(j);
      if (constraint.isCallTo(BuiltIn.RELATIONAL_NON_EMPTY)) {
        final Core.Apply apply = (Core.Apply) constraint;
        if (apply.arg instanceof Core.From) {
          final Core.From from = (Core.From) apply.arg;

          // Create a copy of constraints with this constraint removed.
          // When we encounter a "where" step, we will add more constraints.
          final List<Core.Exp> constraints2 = new ArrayList<>(constraints);
          //noinspection SuspiciousListRemoveInLoop
          constraints2.remove(j);

          for (Core.FromStep step : from.steps) {
            switch (step.op) {
              case SCAN:
                break;
              case WHERE:
                constraints2.add(((Core.Where) step).exp);
                if (maybeGenerator(cache, pat, false, constraints2)) {
                  return true;
                }
                break;
              default:
                continue constraint_loop;
            }
          }
        }
      }
    }
    return false;
  }

  /**
   * Returns a list without the {@code i}<sup>th</sup> element.
   *
   * <p>For example, {@code skipMid(["a", "b", "c"], 1)} evaluates to {@code
   * ["a", "c"]}.
   */
  // TODO: Improve this method if used, remove it if not
  static <E> List<E> skipMid(List<E> list, int i) {
    if (i == 0) {
      return skip(list);
    } else if (i == list.size() - 1) {
      return skipLast(list);
    } else {
      final ImmutableList.Builder<E> list2 = ImmutableList.builder();
      for (int j = 0; j < list.size(); j++) {
        if (j != i) {
          list2.add(list.get(j));
        }
      }
      return list2.build();
    }
  }

  /**
   * For each predicate "pat elem collection", adds a generator based on
   * "collection".
   */
  static boolean maybeElem(
      Cache cache,
      Core.Pat goalPat,
      boolean ordered,
      List<Core.Exp> predicates) {
    for (Core.Exp predicate : predicates) {
      if (predicate.isCallTo(BuiltIn.OP_ELEM)) {
        if (containsRef(predicate.arg(0), goalPat)) {
          // If predicate is "(p, q) elem links", first create a generator
          // for "p2 elem links", where "p2 as (p, q)".
          final Core.Exp collection = predicate.arg(1);
          final ExpPat expPat =
              requireNonNull(wholePat(cache.typeSystem, predicate.arg(0)));
          CollectionGenerator.create(cache, ordered, expPat.pat, collection);
          return true;
        }
      }
    }
    return false;
  }

  static boolean maybePoint(
      Cache cache, Core.Pat pat, boolean ordered, List<Core.Exp> constraints) {
    final Core.@Nullable Exp value = point(pat, constraints);
    if (value != null) {
      PointGenerator.create(cache, pat, ordered, value);
      return true;
    }
    return false;
  }

  /**
   * Creates an expression that generates a values from several generators.
   *
   * @param ordered If true, generate a `list`, otherwise a `bag`
   * @param generators Generators
   */
  @SuppressWarnings("UnusedReturnValue")
  static Generator generateUnion(
      Cache cache, boolean ordered, List<Generator> generators) {
    final Core.Exp fn =
        core.functionLiteral(
            cache.typeSystem,
            ordered ? BuiltIn.LIST_CONCAT : BuiltIn.BAG_CONCAT);
    final Type collectionType = generators.get(0).exp.type;
    Core.Exp arg =
        core.list(
            cache.typeSystem,
            collectionType.elementType(),
            transformEager(generators, g -> g.exp));
    final Core.Exp exp = core.apply(Pos.ZERO, collectionType, fn, arg);
    final Set<Core.NamedPat> freePats =
        SuchThatShuttle.freePats(cache.typeSystem, exp);
    return cache.add(new UnionGenerator(exp, freePats, generators));
  }

  static boolean maybeUnion(
      Cache cache, Core.Pat pat, boolean ordered, List<Core.Exp> constraints) {
    next_constraint:
    for (Core.Exp constraint : constraints) {
      if (constraint.isCallTo(BuiltIn.Z_ORELSE)) {
        final List<Core.Exp> orList = core.decomposeOr(constraint);
        final List<Generator> generators = new ArrayList<>();
        for (Core.Exp exp : orList) {
          final List<Core.Exp> andList = core.decomposeAnd(exp);
          if (!maybeGenerator(cache, pat, ordered, andList)) {
            continue next_constraint;
          }
          generators.add(getLast(cache.generators.get((Core.NamedPat) pat)));
        }
        generateUnion(cache, ordered, generators);
        return true;
      }
    }
    return false;
  }

  static boolean maybeRange(
      Cache cache, Core.Pat pat, boolean ordered, List<Core.Exp> constraints) {
    if (pat.type != PrimitiveType.INT) {
      return false;
    }
    final @Nullable Pair<Core.Exp, Boolean> lower =
        lowerBound(pat, constraints);
    if (lower == null) {
      return false;
    }
    final @Nullable Pair<Core.Exp, Boolean> upper =
        upperBound(pat, constraints);
    if (upper == null) {
      return false;
    }
    Generators.generateRange(
        cache,
        ordered,
        (Core.NamedPat) pat,
        lower.left,
        lower.right,
        upper.left,
        upper.right);
    return true;
  }

  /**
   * Creates an expression that generates a range of integer values.
   *
   * <p>For example, {@code generateRange(3, true, 8, false)} generates a range
   * {@code 3 < x <= 8}, which yields the values {@code [4, 5, 6, 7, 8]}.
   *
   * @param ordered If true, generate a `list`, otherwise a `bag`
   * @param pat Pattern
   * @param lower Lower bound
   * @param lowerStrict Whether the lower bound is strict (exclusive): true for
   *     {@code x > lower}, false for {@code x >= lower}
   * @param upper Upper bound
   * @param upperStrict Whether the upper bound is strict (exclusive): true for
   *     {@code x < upper}, false for {@code x <= upper}
   */
  static Generator generateRange(
      Cache cache,
      boolean ordered,
      Core.NamedPat pat,
      Core.Exp lower,
      boolean lowerStrict,
      Core.Exp upper,
      boolean upperStrict) {
    // For x > lower, we want x >= lower + 1
    final TypeSystem typeSystem = cache.typeSystem;
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
        core.call(typeSystem, BuiltIn.Z_PLUS_INT, lower2, core.id(kPat));
    final Core.Fn fn = core.fn(typeSystem.fnType(type, type), kPat, lowerPlusK);
    BuiltIn tabulate = ordered ? BuiltIn.LIST_TABULATE : BuiltIn.BAG_TABULATE;
    Core.Apply exp = core.call(typeSystem, tabulate, type, Pos.ZERO, count, fn);
    final Core.Exp simplified = Simplifier.simplify(typeSystem, exp);
    final Set<Core.NamedPat> freePats =
        SuchThatShuttle.freePats(typeSystem, simplified);
    return cache.add(
        new RangeGenerator(
            pat, simplified, freePats, lower, lowerStrict, upper, upperStrict));
  }

  /** Returns an extent generator, or null if expression is not an extent. */
  @SuppressWarnings("UnusedReturnValue")
  public static boolean maybeExtent(Cache cache, Core.Pat pat, Core.Exp exp) {
    if (exp.isExtent()) {
      ExtentGenerator.create(cache, pat, exp);
      return true;
    }
    return false;
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
   * If there is a predicate "pat &gt; exp" or "pat &ge; exp", returns "exp" and
   * whether the comparison is strict.
   *
   * <p>We do not attempt to find the strongest such constraint. Clearly "p &gt;
   * 10" is stronger than "p &ge; 0". But is "p &gt; x" stronger than "p &ge;
   * y"? If the goal is to convert an infinite generator to a finite generator,
   * any constraint is good enough.
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
   * If there is a constraint "pat &lt; exp" or "pat &le; exp", returns "exp"
   * and whether the comparison is strict.
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

  /**
   * Returns whether an expression is a reference ({@link Core.Id}) to a
   * pattern.
   */
  private static boolean references(Core.Exp arg, Core.Pat pat) {
    return arg.op == Op.ID && ((Core.Id) arg).idPat.equals(pat);
  }

  /**
   * Returns whether an expression contains a reference to a pattern. If the
   * pattern is {@link Core.IdPat} {@code p}, returns true for {@link Core.Id}
   * {@code p} and tuple {@code (p, q)}.
   */
  private static boolean containsRef(Core.Exp exp, Core.Pat pat) {
    switch (exp.op) {
      case ID:
        return ((Core.Id) exp).idPat.equals(pat);

      case TUPLE:
        for (Core.Exp arg : ((Core.Tuple) exp).args) {
          if (containsRef(arg, pat)) {
            return true;
          }
        }
        return false;

      default:
        return false;
    }
  }

  /**
   * Creates a pattern that encompasses a whole expression.
   *
   * <p>Returns null if {@code exp} does not contain {@code pat}; you should
   * have called {@link #containsRef(Core.Exp, Core.Pat)} first.
   *
   * <p>Examples:
   *
   * <ul>
   *   <li>{@code wholePat(id(p), idPat(p)} returns pattern {@code idPat(p)} and
   *       expression {@code id(p)}
   *   <li>{@code wholePat(tuple(id(p), id(q)), idPat(p)} returns pattern {@code
   *       idPat(r)} and expression {@code r.1}
   *   <li>{@code wholePat(tuple(id(p), id(p)), idPat(p)} is illegal because it
   *       contains {@code p} more than once
   *   <li>{@code wholePat(tuple(id(p), literal("elrond")), idPat(p))} returns
   *       pattern {@code idPat(p)}, expression {@code id(p)}, and a filter
   *       {@code id(q) = literal("elrond")}. We currently ignore filters.
   * </ul>
   */
  private static @Nullable ExpPat wholePat(
      TypeSystem typeSystem, Core.Exp exp) {
    switch (exp.op) {
      case ID:
        Core.Id id1 = (Core.Id) exp;
        return ExpPat.of(exp, id1.idPat);

      case TUPLE:
        int slot = -1;
        final List<Core.Pat> patList = new ArrayList<>();
        for (Ord<Core.Exp> arg : Ord.zip(((Core.Tuple) exp).args)) {
          final @Nullable ExpPat p = wholePat(typeSystem, arg.e);
          if (p != null) {
            slot = arg.i;
          }
          patList.add(toPat(arg.e));
        }
        if (slot < 0) {
          return null;
        }
        final Core.Pat tuplePat =
            (exp.type instanceof RecordType)
                ? core.recordPat((RecordType) exp.type, patList)
                : core.tuplePat((RecordLikeType) exp.type, patList);
        final Core.IdPat idPat = core.idPat(exp.type, "z", 0); // "r as (p, q)"
        final Core.Id id = core.id(idPat);
        final Core.Exp exp2 = core.field(typeSystem, id, slot);
        return ExpPat.of(exp2, tuplePat);

      default:
        return null;
    }
  }

  /**
   * Converts an expression to the equivalent pattern.
   *
   * @see Op#toPat()
   */
  private static Core.Pat toPat(Core.Exp exp) {
    switch (exp.op) {
      case ID:
        return ((Core.Id) exp).idPat;

      case TUPLE:
        final Core.Tuple tuple = (Core.Tuple) exp;
        if (tuple.type.op() == Op.RECORD_TYPE) {
          return core.recordPat(
              (RecordType) tuple.type(),
              transformEager(tuple.args, Generators::toPat));
        } else {
          return core.tuplePat(
              tuple.type(), transformEager(tuple.args, Generators::toPat));
        }

      case BOOL_LITERAL:
      case CHAR_LITERAL:
      case INT_LITERAL:
      case REAL_LITERAL:
      case STRING_LITERAL:
      case UNIT_LITERAL:
        final Core.Literal literal = (Core.Literal) exp;
        return core.literalPat(exp.op.toPat(), exp.type, literal.value);

      default:
        throw new AssertionError("cannot convert " + exp + " to pattern");
    }
  }

  /**
   * Generator that generates a range of integers from {@code lower} to {@code
   * upper}.
   */
  static class RangeGenerator extends Generator {
    private final Core.Exp lower;
    private final Core.Exp upper;

    RangeGenerator(
        Core.NamedPat pat,
        Core.Exp exp,
        Iterable<? extends Core.NamedPat> freePats,
        Core.Exp lower,
        boolean ignoreLowerStrict,
        Core.Exp upper,
        boolean ignoreUpperStrict) {
      super(exp, freePats, pat, Cardinality.FINITE);
      this.lower = lower;
      this.upper = upper;
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

    PointGenerator(
        Core.Pat pat,
        Core.Exp exp,
        Iterable<? extends Core.NamedPat> freePats,
        Core.Exp point) {
      super(exp, freePats, pat, Cardinality.SINGLE);
      this.point = point;
    }

    /**
     * Creates an expression that generates a single value.
     *
     * @param ordered If true, generate a `list`, otherwise a `bag`
     * @param lower Lower bound
     */
    @SuppressWarnings("UnusedReturnValue")
    static Generator create(
        Cache cache, Core.Pat pat, boolean ordered, Core.Exp lower) {
      final Core.Exp exp =
          ordered
              ? core.list(cache.typeSystem, lower)
              : core.bag(cache.typeSystem, lower);
      final Set<Core.NamedPat> freePats =
          SuchThatShuttle.freePats(cache.typeSystem, exp);
      return cache.add(new PointGenerator(pat, exp, freePats, lower));
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

    UnionGenerator(
        Core.Exp exp,
        Iterable<? extends Core.NamedPat> freePats,
        List<Generator> generators) {
      super(exp, freePats, firstGenerator(generators).pat, Cardinality.FINITE);
      this.generators = ImmutableList.copyOf(generators);
    }

    private static Generator firstGenerator(List<Generator> generators) {
      checkArgument(generators.size() >= 2);
      return generators.get(0);
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
    private ExtentGenerator(
        Core.Pat pat,
        Core.Exp exp,
        Iterable<? extends Core.NamedPat> freePats,
        Cardinality cardinality) {
      super(exp, freePats, pat, cardinality);
    }

    /** Creates an extent generator. */
    @SuppressWarnings("UnusedReturnValue")
    static Generator create(Cache cache, Core.Pat pat, Core.Exp exp) {
      final RangeExtent rangeExtent = exp.getRangeExtent();
      final Cardinality cardinality =
          rangeExtent.iterable == null
              ? Cardinality.INFINITE
              : Cardinality.FINITE;
      final Set<Core.NamedPat> freePats =
          SuchThatShuttle.freePats(cache.typeSystem, exp);
      return cache.add(new ExtentGenerator(pat, exp, freePats, cardinality));
    }

    @Override
    Core.Exp simplify(Core.Pat pat, Core.Exp exp) {
      return exp;
    }
  }

  /**
   * Generator that returns the contents of a collection.
   *
   * <p>The inverse of {@code p elem collection} is {@code collection}.
   */
  static class CollectionGenerator extends Generator {
    final Core.Exp collection;

    private CollectionGenerator(
        Core.Pat pat,
        Core.Exp collection,
        Iterable<? extends Core.NamedPat> freePats) {
      super(collection, freePats, pat, Cardinality.FINITE);
      this.collection = collection;
      checkArgument(collection.type.isCollection());
    }

    @SuppressWarnings("UnusedReturnValue")
    static CollectionGenerator create(
        Cache cache, boolean ordered, Core.Pat pat, Core.Exp collection) {
      // Convert the collection to a list or bag, per "ordered".
      final TypeSystem typeSystem = cache.typeSystem;
      final Core.Exp collection2 =
          CoreBuilder.withOrdered(ordered, collection, typeSystem);
      final Set<Core.NamedPat> freePats =
          SuchThatShuttle.freePats(cache.typeSystem, collection2);
      return cache.add(new CollectionGenerator(pat, collection2, freePats));
    }

    @Override
    Core.Exp simplify(Core.Pat pat, Core.Exp exp) {
      if (exp.isCallTo(BuiltIn.OP_ELEM)
          && references(exp.arg(0), pat)
          && exp.arg(1).equals(this.collection)) {
        // "p elem collection" simplifies to "true"
        return core.boolLiteral(true);
      }
      return exp;
    }
  }

  /** Generators that have been created to date. */
  static class Cache {
    final TypeSystem typeSystem;
    final Multimap<Core.NamedPat, Generator> generators =
        MultimapBuilder.hashKeys().arrayListValues().build();

    Cache(TypeSystem typeSystem) {
      this.typeSystem = requireNonNull(typeSystem);
    }

    @Nullable
    Generator bestGenerator(Core.NamedPat namedPat) {
      Generator bestGenerator = null;
      for (Generator generator : generators.get(namedPat)) {
        bestGenerator = generator;
      }
      return bestGenerator;
    }

    /**
     * Registers a generator, adding it to the index of each constituent
     * pattern.
     *
     * <p>For example, if {@code g} is {@code CollectionGenerator((s, t) elem
     * links)}, then {@code add(g)} will add {@code (s, g), (t, g)} to the
     * generators index.
     */
    public <G extends Generator> G add(G generator) {
      for (Core.NamedPat namedPat : generator.pat.expand()) {
        generators.put(namedPat, generator);
      }
      return generator;
    }
  }

  /** An expression and a pattern. */
  static class ExpPat {
    final Core.Exp exp;
    final Core.Pat pat;

    ExpPat(Core.Exp exp, Core.Pat pat) {
      this.exp = requireNonNull(exp);
      this.pat = requireNonNull(pat);
    }

    static ExpPat of(Core.Exp exp, Core.Pat pat) {
      return new ExpPat(exp, pat);
    }

    @Override
    public String toString() {
      return "{exp " + exp + ", pat " + pat + "}";
    }
  }
}

// End Generators.java
