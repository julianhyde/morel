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

import net.hydromatic.morel.compile.Compiles;
import net.hydromatic.morel.compile.Environment;
import net.hydromatic.morel.compile.RefChecker;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.Pair;
import net.hydromatic.morel.util.PairList;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableRangeSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Range;
import org.apache.calcite.util.Util;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import java.util.concurrent.atomic.AtomicInteger;

import static net.hydromatic.morel.ast.CoreBuilder.core;
import static net.hydromatic.morel.util.Pair.forEach;
import static net.hydromatic.morel.util.Static.append;

import static com.google.common.collect.Iterables.getLast;

import static java.util.Objects.requireNonNull;

/** Builds a {@link Core.From}.
 *
 * <p>Simplifies the following patterns:
 * <ul>
 *   <li>Converts "from v in list" to "list"
 *   (only works in {@link #buildSimplify()}, not {@link #build()});
 *   <li>Removes "where true" steps;
 *   <li>Removes empty "order" steps;
 *   <li>Removes trivial {@code yield},
 *   e.g. "from v in list where condition yield v"
 *   becomes "from v in list where condition";
 *   <li>Inlines {@code from} expressions,
 *   e.g. "from v in (from w in list)"
 *   becomes "from w in list yield {v = w}".
 * </ul>
 */
public class FromBuilder {
  private final TypeSystem typeSystem;
  private final @Nullable Environment env;
  private final List<Core.FromStep> steps = new ArrayList<>();
  private final List<Binding> bindings = new ArrayList<>();

  /** If non-negative, flags that particular step should be removed if it is not
   * the last step. (For example, "yield {i = i}", which changes the result
   * shape if the last step but is otherwise a no-op.) */
  private int removeIfNotLastIndex = Integer.MIN_VALUE;
  /** If non-negative, flags that particular step should be removed if it is
   * the last step. (For example, we flatten "from p in (from q in list)",
   * to "from q in list yield {p = q}" but we want to remove "yield {p = q}"
   * if it turns out to be the last step.) */
  private int removeIfLastIndex = Integer.MIN_VALUE;

  /** Use {@link net.hydromatic.morel.ast.CoreBuilder#fromBuilder}. */
  FromBuilder(TypeSystem typeSystem, @Nullable Environment env) {
    this.typeSystem = typeSystem;
    this.env = env;
  }

  /** Resets state as if this {@code FromBuilder} had just been created. */
  public void clear() {
    steps.clear();
    bindings.clear();
    removeIfNotLastIndex = Integer.MIN_VALUE;
    removeIfLastIndex = Integer.MIN_VALUE;
  }

  @Override public String toString() {
    return steps.toString();
  }

  /** Returns the bindings available after the most recent step. */
  public List<Binding> bindings() {
    return ImmutableList.copyOf(bindings);
  }

  private FromBuilder addStep(Core.FromStep step) {
    if (env != null) {
      // Validate the step. (Not necessary, but helps find bugs.)
      RefChecker.of(typeSystem, env.bindAll(bindings))
          .visitStep(step, bindings);
    }
    if (removeIfNotLastIndex == steps.size() - 1) {
      // A trivial record yield with a single yield, e.g. 'yield {i = i}', has
      // a purpose only if it is the last step. (It forces the return to be a
      // record, e.g. '{i: int}' rather than a scalar 'int'.)
      // We've just about to add a new step, so this is no longer necessary.
      removeIfNotLastIndex = Integer.MIN_VALUE;
      removeIfLastIndex = Integer.MIN_VALUE;
      final Core.FromStep lastStep = getLast(steps);
      if (lastStep.op == Op.YIELD) {
        final Core.Yield yield = (Core.Yield) lastStep;
        if (yield.exp.op == Op.TUPLE) {
          final Core.Tuple tuple = (Core.Tuple) yield.exp;
          final Core.FromStep previousStep = steps.get(steps.size() - 2);
          final List<Binding> previousBindings = previousStep.bindings;
          if (tuple.args.size() == 1
              && isTrivial(tuple, previousBindings, yield.bindings)) {
            steps.remove(steps.size() - 1);
          }
        }
      }
    }
    steps.add(step);
    if (!bindings.equals(step.bindings)) {
      bindings.clear();
      bindings.addAll(step.bindings);
    }
    return this;
  }

  /** Creates an unbounded scan, "from pat". */
  public FromBuilder scan(Core.Pat pat) {
    final Core.Exp extent =
        core.extent(typeSystem, pat.type, ImmutableRangeSet.of(Range.all()));
    return scan(pat, extent, core.boolLiteral(true), null);
  }

  /** Creates a bounded scan, "from pat in exp". */
  public FromBuilder scan(Core.Pat pat, Core.Exp exp) {
    return scan(pat, exp, core.boolLiteral(true), null);
  }

  /** Creates a bounded scan, "from pat in exp", with explicit bindings. */
  public FromBuilder scan(List<Binding> bindings2, Core.Pat pat, Core.Exp exp) {
    return scan(pat, exp, core.boolLiteral(true), bindings2);
  }

  public FromBuilder scan(Core.Pat pat, Core.Exp exp, Core.Exp condition,
      @Nullable List<Binding> bindings2a) {
    if (!isFromThatCanBeInlined(pat, exp, condition)) {
      if (bindings2a != null) {
        bindings.addAll(Util.last(bindings2a, bindingCount(pat)));
      } else {
        Compiles.acceptBinding(typeSystem, pat, bindings::add);
      }
      return addStep(core.scan(bindings, pat, exp, condition));
    }

    final Core.From from = (Core.From) exp;
    final Core.FromStep lastStep = getLast(from.steps);
    List<Core.FromStep> steps = from.steps;

    final PairList<Core.NamedPat, Core.Exp> nameExps = PairList.of();
    final boolean uselessIfLast = this.bindings.isEmpty();
    if (pat instanceof Core.RecordPat) {
      final Core.RecordPat recordPat = (Core.RecordPat) pat;
      this.bindings.forEach(b -> nameExps.add(b.id, core.id(b.id)));
      forEach(recordPat.type().argNameTypes.keySet(), recordPat.args,
          (name, arg) ->
              nameExps.add(
                  core.idPat(arg.type, name, typeSystem.nameGenerator),
                  core.id((Core.IdPat) arg)));
      final List<Binding> bindings2 =
          bindings2a == null ? null : Util.last(bindings2a, bindingCount(pat));
      addAll(steps);
      return yield3(bindings2, uselessIfLast, nameExps);
    } else if (pat instanceof Core.TuplePat) {
      final Core.TuplePat tuplePat = (Core.TuplePat) pat;
      forEach(tuplePat.args, lastStep.bindings,
          (arg, binding) ->
              nameExps.add((Core.IdPat) arg, core.id(binding.id)));
      final List<Binding> bindings2;
      if (bindings2a == null) {
        bindings2 = nameExps.transform((namedPat, e) -> Binding.of(namedPat));
      } else {
        bindings2 = Util.last(bindings2a, bindingCount(pat));
      }
      addAll(steps);
      return yield3(bindings2, uselessIfLast, nameExps);
    } else if (lastStep instanceof Core.Yield
        && ((Core.Yield) lastStep).exp.op != Op.RECORD) {
      // The last step is a yield scalar, say 'yield x + 1'.
      // Translate it to a yield singleton record, say 'yield {y = x + 1}'
      final Core.IdPat idPat = (Core.IdPat) pat;
      steps = Util.skipLast(from.steps);
      addAll(steps);
      nameExps.add(idPat, ((Core.Yield) lastStep).exp);
      final List<Binding> bindings2 = ImmutableList.of(Binding.of(idPat));
      return yield3(bindings2, false, nameExps);
    } else {
      final Core.IdPat idPat = (Core.IdPat) pat;
      if (!this.bindings.isEmpty()) {
        // With at least one binding, and one new variable, the output will be
        // a record type.
        this.bindings.forEach(b -> nameExps.add(b.id, core.id(b.id)));
        lastStep.bindings.forEach(b -> nameExps.add(idPat, core.id(b.id)));
      } else {
        if (lastStep instanceof Core.Yield) {
          steps = Util.skipLast(from.steps);
        }
        final Binding binding = Iterables.getOnlyElement(lastStep.bindings);
        nameExps.add(idPat, core.id(binding.id));
      }
      final List<Binding> initialBindings = ImmutableList.copyOf(bindings);
      addAll(steps);
      final List<Binding> bindings2 = append(initialBindings, Binding.of(idPat));
      return yield3(bindings2, uselessIfLast, nameExps);
    }
  }

  private FromBuilder yield3(List<Binding> bindings2, boolean uselessIfLast,
      PairList<Core.NamedPat, Core.Exp> nameExps) {
    requireNonNull(bindings2);
    Core.Exp exp =
        core.record(typeSystem,
            nameExps.transform((n, e) -> Pair.of(n.name, e)));
    boolean uselessIfNotLast = false;
    switch (exp.op) {
    case TUPLE:
      final TupleType tupleType =
          tupleType((Core.Tuple) exp, bindings, bindings2);
      switch (tupleType) {
      case IDENTITY:
        // A trivial record does not rename, so its only purpose is to change
        // from a scalar to a record, and even then only when a singleton.
        if (bindings.size() == 1) {
          // Singleton record that does not rename, e.g. 'yield {x=x}'
          // It only has meaning as the last step.
          uselessIfNotLast = true;
          break;
        } else {
          // Non-singleton record that does not rename, e.g. 'yield {x=x,y=y}'
          // It is useless.
          return this;
        }
      case RENAME:
        if (bindings.size() == 1) {
          // Singleton record that renames, e.g. 'yield {y=x}'.
          // It is always useful.
          break;
        } else {
          // Non-singleton record that renames, e.g. 'yield {y=x,z=y}'
          // It is always useful.
          break;
        }
      }
      break;

    case ID:
      if (bindings.size() == 1
          && ((Core.Id) exp).idPat.equals(bindings.get(0).id)) {
        return this;
      }
    }
    addStep(core.yield_(bindings2, exp));
    removeIfNotLastIndex = uselessIfNotLast ? steps.size() - 1 : Integer.MIN_VALUE;
    removeIfLastIndex = uselessIfLast ? steps.size() - 1 : Integer.MIN_VALUE;
    return this;
  }

  private int bindingCount(Core.Pat pat) {
    final AtomicInteger c = new AtomicInteger();
    Compiles.acceptBinding(typeSystem, pat, binding -> c.incrementAndGet());
    return c.intValue();
  }

  /** Returns whether {@code exp} is a {@link Core.From} that can be inlined
   * as a pipeline of steps. */
  private static boolean isFromThatCanBeInlined(Core.Pat pat, Core.Exp exp,
      Core.Exp condition) {
    if (exp.op != Op.FROM) {
      return false;
    }
    final Core.From from = (Core.From) exp;
    if (!core.boolLiteral(true).equals(condition)) {
      return false;
    }
    if (pat instanceof Core.IdPat
        && !from.steps.isEmpty()
        && getLast(from.steps).bindings.size() == 1) {
      return true;
    }
    if (pat instanceof Core.RecordPat
        && ((Core.RecordPat) pat).args.stream()
        .allMatch(a -> a instanceof Core.IdPat)) {
      return true;
    }
    if (pat instanceof Core.TuplePat
        && ((Core.TuplePat) pat).args.stream()
        .allMatch(a -> a instanceof Core.IdPat)) {
      return true;
    }
    return false;
  }

  public FromBuilder addAll(Iterable<? extends Core.FromStep> steps) {
    final StepHandler stepHandler = new StepHandler();
    steps.forEach(stepHandler::accept);
    return this;
  }

  public FromBuilder where(Core.Exp condition) {
    if (condition.op == Op.BOOL_LITERAL
        && ((Core.Literal) condition).unwrap(Boolean.class)) {
      // skip "where true"
      return this;
    }
    return addStep(core.where(bindings, condition));
  }

  public FromBuilder skip(Core.Exp count) {
    if (count.op == Op.INT_LITERAL
        && ((Core.Literal) count).value.equals(BigDecimal.ZERO)) {
      // skip "skip 0"
      return this;
    }
    return addStep(core.skip(bindings, count));
  }

  public FromBuilder take(Core.Exp count) {
    return addStep(core.take(bindings, count));
  }

  public FromBuilder group(SortedMap<Core.IdPat, Core.Exp> groupExps,
      SortedMap<Core.IdPat, Core.Aggregate> aggregates) {
    return addStep(core.group(groupExps, aggregates));
  }

  public FromBuilder order(Iterable<Core.OrderItem> orderItems) {
    final List<Core.OrderItem> orderItemList = ImmutableList.copyOf(orderItems);
    if (orderItemList.isEmpty()) {
      // skip empty "order"
      return this;
    }
    return addStep(core.order(bindings, orderItems));
  }

  public FromBuilder yield2(boolean uselessIfLast,
      @Nullable List<Binding> bindings2, Core.Exp exp) {
    return yield_(uselessIfLast, bindings2, exp);
  }

  public FromBuilder yield_(Core.Exp exp) {
    return yield_(false, exp);
  }

  public FromBuilder yield_(boolean uselessIfLast, Core.Exp exp) {
    return yield_(uselessIfLast, null, exp);
  }

  /** Creates a "yield" step.
   *
   * <p>When copying, the {@code bindings2} parameter is the
   * {@link net.hydromatic.morel.ast.Core.Yield#bindings} value of the current
   * Yield, so that we don't generate new variables (with different ordinals).
   * Later steps are relying on the variables remaining the same. For example,
   * in
   *
   * <blockquote>{@code
   * from ... yield {a = b} where a > 5
   * }</blockquote>
   *
   * <p>the {@code a} in {@code a > 5} references {@code IdPat('a', 0)} and we
   * don't want yield to generate an {@code IdPat('a', 1)}.
   *
   * @param uselessIfLast Whether this Yield will be useless if it is the last
   *                      step. The expression {@code {x = y} } is an example of
   *                      this
   * @param bindings2     Desired bindings, or null
   * @param exp           Expression to yield
   *
   * @return This FromBuilder, with a Yield added to the list of steps
   */
  public FromBuilder yield_(boolean uselessIfLast,
      @Nullable List<Binding> bindings2, Core.Exp exp) {
    boolean uselessIfNotLast = false;
    switch (exp.op) {
    case TUPLE:
      final TupleType tupleType =
          tupleType((Core.Tuple) exp, bindings, bindings2);
      switch (tupleType) {
      case IDENTITY:
        // A trivial record does not rename, so its only purpose is to change
        // from a scalar to a record, and even then only when a singleton.
        if (bindings.size() == 1) {
          // Singleton record that does not rename, e.g. 'yield {x=x}'
          // It only has meaning as the last step.
          if (bindings2 == null) {
            bindings2 = ImmutableList.copyOf(bindings);
          }
          uselessIfNotLast = true;
          break;
        } else {
          // Non-singleton record that does not rename, e.g. 'yield {x=x,y=y}'
          // It is useless.
          return this;
        }
      case RENAME:
        if (bindings.size() == 1) {
          // Singleton record that renames, e.g. 'yield {y=x}'.
          // It is always useful.
          break;
        } else {
          // Non-singleton record that renames, e.g. 'yield {y=x,z=y}'
          // It is always useful.
          break;
        }
      }
      break;

    case ID:
      if (bindings.size() == 1
          && ((Core.Id) exp).idPat.equals(bindings.get(0).id)) {
        return this;
      }
    }
    addStep(bindings2 != null
        ? core.yield_(bindings2, exp)
        : core.yield_(typeSystem, exp));
    removeIfNotLastIndex = uselessIfNotLast ? steps.size() - 1 : Integer.MIN_VALUE;
    removeIfLastIndex = uselessIfLast ? steps.size() - 1 : Integer.MIN_VALUE;
    return this;
  }

  /** Returns whether tuple is something like "{i = i, j = j}". */
  private static boolean isTrivial(Core.Tuple tuple, List<Binding> bindings,
      @Nullable List<Binding> bindings2) {
    return tupleType(tuple, bindings, bindings2) == TupleType.IDENTITY;
  }

  /** Returns whether tuple is something like "{i = i, j = j}". */
  private static TupleType tupleType(Core.Tuple tuple, List<Binding> bindings,
      @Nullable List<Binding> bindings2) {
    if (tuple.args.size() != bindings.size()) {
      return TupleType.OTHER;
    }
    final ImmutableList<String> argNames =
        ImmutableList.copyOf(tuple.type().argNameTypes().keySet());
    boolean identity = bindings2 == null || bindings.equals(bindings2);
    for (int i = 0; i < tuple.args.size(); i++) {
      Core.Exp exp = tuple.args.get(i);
      if (exp.op != Op.ID) {
        return TupleType.OTHER;
      }
      if (!((Core.Id) exp).idPat.name.equals(argNames.get(i))) {
        identity = false;
      }
    }
    return identity ? TupleType.IDENTITY : TupleType.RENAME;
  }

  private Core.Exp build(boolean simplify) {
    if (removeIfLastIndex == steps.size() - 1) {
      removeIfLastIndex = Integer.MIN_VALUE;
      final Core.Yield yield = (Core.Yield) getLast(steps);
      if (yield.exp.op != Op.TUPLE
          || ((Core.Tuple) yield.exp).args.size() != 1) {
        throw new AssertionError(yield.exp);
      }
      steps.remove(steps.size() - 1);
    }
    if (simplify
        && steps.size() == 1
        && steps.get(0).op == Op.SCAN) {
      final Core.Scan scan = (Core.Scan) steps.get(0);
      if (scan.pat.op == Op.ID_PAT) {
        return scan.exp;
      }
    }
    return core.from(typeSystem, steps);
  }

  public Core.From build() {
    return (Core.From) build(false);
  }

  /** As {@link #build}, but also simplifies "from x in list" to "list". */
  public Core.Exp buildSimplify() {
    return build(true);
  }

  /** Calls the method to re-register a step. */
  private class StepHandler extends Visitor {
    @Override protected void visit(Core.Group group) {
      group(group.groupExps, group.aggregates);
    }

    @Override protected void visit(Core.Order order) {
      order(order.orderItems);
    }

    @Override protected void visit(Core.Scan scan) {
      scan(scan.pat, scan.exp, scan.condition, scan.bindings);
    }

    @Override protected void visit(Core.Where where) {
      where(where.exp);
    }

    @Override protected void visit(Core.Skip skip) {
      skip(skip.exp);
    }

    @Override protected void visit(Core.Take take) {
      take(take.exp);
    }

    @Override protected void visit(Core.Yield yield) {
      yield_(false, yield.bindings, yield.exp);
    }
  }

  /** Category of expression passed to "yield". */
  private enum TupleType {
    /** Tuple whose right side are the current fields,
     * e.g. "{a = deptno, b = dname}". */
    RENAME,
    /** Tuple whose right side are the current fields
     * and left side are the same as the right,
     * e.g. "{deptno = deptno, dname = dname}". */
    IDENTITY,
    /** Any other tuple,
     * e.g. "{a = deptno + 1, dname = dname}",
     * "{deptno = deptno}" (too few fields). */
    OTHER
  }
}

// End FromBuilder.java
