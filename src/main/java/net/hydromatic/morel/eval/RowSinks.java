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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;
import static net.hydromatic.morel.util.Ord.forEachIndexed;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Multimaps;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.util.ImmutablePairList;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Implementations of {@link RowSink}. */
@SuppressWarnings({"rawtypes", "unchecked"})
public abstract class RowSinks {
  private RowSinks() {}

  /**
   * Creates a {@link Code} that implements a query.
   *
   * @see #first(RowSink)
   */
  public static Code from(Supplier<RowSink> rowSinkFactory) {
    return new FromCode(rowSinkFactory);
  }

  /** Creates a {@link RowSink} that starts all downstream row sinks. */
  public static RowSink first(RowSink rowSink) {
    // Use a Describer to walk over the downstream sinks and their codes, and
    // collect the start actions. The only start action, currently, resets
    // ordinals to -1.
    final List<Runnable> startActions = new ArrayList<>();
    rowSink.describe(
        new CodeVisitor() {
          @Override
          public void addStartAction(Runnable runnable) {
            startActions.add(runnable);
          }
        });
    if (startActions.isEmpty()) {
      // There are no start actions, so there's no need to wrap the in a
      // FirstRowSink.
      return rowSink;
    }
    return new FirstRowSink(rowSink, startActions);
  }

  /** Creates a {@link RowSink} for an {@code except} step. */
  public static RowSink except(
      boolean distinct,
      ImmutableList<Code> codes,
      ImmutableList<String> names,
      ImmutablePairList<String, Code> inSlots,
      int scanDepth,
      RowSink rowSink) {
    return distinct
        ? new ExceptDistinctRowSink(codes, names, inSlots, scanDepth, rowSink)
        : new ExceptAllRowSink(codes, names, inSlots, scanDepth, rowSink);
  }

  /** Creates a {@link RowSink} for a {@code group} step. */
  public static RowSink group(
      Code keyCode,
      ImmutableList<Applicable> aggregateCodes,
      ImmutablePairList<String, Code> inSlots,
      int scanDepth,
      ImmutableList<String> keyNames,
      ImmutableList<String> outNames,
      RowSink rowSink) {
    return new GroupRowSink(
        keyCode,
        aggregateCodes,
        inSlots,
        scanDepth,
        keyNames,
        outNames,
        rowSink);
  }

  /** Creates a {@link RowSink} for an {@code intersect} step. */
  public static RowSink intersect(
      boolean distinct,
      ImmutableList<Code> codes,
      ImmutableList<String> names,
      ImmutablePairList<String, Code> inSlots,
      int scanDepth,
      RowSink rowSink) {
    return distinct
        ? new IntersectDistinctRowSink(
            codes, names, inSlots, scanDepth, rowSink)
        : new IntersectAllRowSink(codes, names, inSlots, scanDepth, rowSink);
  }

  /** Creates a {@link RowSink} for an {@code order} step. */
  public static RowSink order(
      Code code,
      Comparator comparator,
      ImmutablePairList<String, Code> inSlots,
      int scanDepth,
      RowSink rowSink) {
    return new OrderRowSink(code, comparator, inSlots, scanDepth, rowSink);
  }

  /** Creates a {@link RowSink} for a scan or {@code join} step. */
  public static RowSink scan(
      Op op,
      Core.Pat pat,
      int varCount,
      Code code,
      Code conditionCode,
      RowSink rowSink) {
    return new ScanRowSink(op, pat, varCount, code, conditionCode, rowSink);
  }

  /** Creates a {@link RowSink} for a {@code skip} step. */
  public static RowSink skip(Code skipCode, RowSink rowSink) {
    return new SkipRowSink(skipCode, rowSink);
  }

  /** Creates a {@link RowSink} for a {@code take} step. */
  public static RowSink take(Code takeCode, RowSink rowSink) {
    return new TakeRowSink(takeCode, rowSink);
  }

  /** Creates a {@link RowSink} for a {@code union} step. */
  public static RowSink union(
      boolean distinct,
      ImmutableList<Code> codes,
      ImmutableList<String> names,
      ImmutablePairList<String, Code> inSlots,
      int scanDepth,
      RowSink rowSink) {
    return new UnionRowSink(
        distinct, codes, names, inSlots, scanDepth, rowSink);
  }

  /** Creates a {@link RowSink} for a {@code where} step. */
  public static RowSink where(Code filterCode, RowSink rowSink) {
    return new WhereRowSink(filterCode, rowSink);
  }

  /** Creates a {@link RowSink} for a non-terminal {@code yield} step. */
  public static RowSink yield(Map<String, Code> yieldCodes, RowSink rowSink) {
    return new YieldRowSink(
        ImmutableList.copyOf(yieldCodes.keySet()),
        ImmutableList.copyOf(yieldCodes.values()),
        rowSink);
  }

  /**
   * Creates a {@link RowSink} to collect the results of a {@code from}
   * expression.
   */
  public static RowSink collect(Code code) {
    return new CollectRowSink(code);
  }

  /** Code that evaluates a query. */
  private static class FromCode implements Code {
    private final Supplier<RowSink> rowSinkFactory;

    FromCode(Supplier<RowSink> rowSinkFactory) {
      this.rowSinkFactory = requireNonNull(rowSinkFactory);
    }

    @Override
    public Describer describe(Describer describer) {
      return describer.start("from", d -> d.arg("sink", rowSinkFactory.get()));
    }

    @Override
    public int maxSlots() {
      return rowSinkFactory.get().maxSlots();
    }

    @Override
    public Object eval(Stack stack) {
      final RowSink rowSink = rowSinkFactory.get();
      rowSink.start(stack);
      rowSink.accept(stack);
      return rowSink.result(stack);
    }
  }

  /** Abstract implementation for row sinks that have one successor. */
  private abstract static class BaseRowSink implements RowSink {
    final RowSink rowSink;

    BaseRowSink(RowSink rowSink) {
      this.rowSink = requireNonNull(rowSink);
    }

    @Override
    public void start(Stack stack) {
      rowSink.start(stack);
    }

    @Override
    public void accept(Stack stack) {
      rowSink.accept(stack);
    }

    @Override
    public List<Object> result(Stack stack) {
      return rowSink.result(stack);
    }

    @Override
    public int maxSlots() {
      return rowSink.maxSlots();
    }
  }

  /** Implementation of {@link RowSink} for a {@code join} step. */
  private static class ScanRowSink extends BaseRowSink {
    final Op op; // inner, left, right, full
    final Core.Pat pat;
    /** Number of stack slots pushed per element. */
    final int varCount;

    final Code code;
    final Code conditionCode;

    ScanRowSink(
        Op op,
        Core.Pat pat,
        int varCount,
        Code code,
        Code conditionCode,
        RowSink rowSink) {
      super(rowSink);
      checkArgument(op == Op.SCAN);
      this.op = op;
      this.pat = pat;
      this.varCount = varCount;
      this.code = code;
      this.conditionCode = conditionCode;
    }

    @Override
    public Describer describe(Describer describer) {
      return describer.start(
          "join",
          d ->
              d.arg("pat", pat)
                  .arg("exp", code)
                  .argIf(
                      "condition",
                      conditionCode,
                      !isConstantTrue(conditionCode))
                  .arg("sink", rowSink));
    }

    static boolean isConstantTrue(Code code) {
      return code.isConstant()
          && Objects.equals(
              code.eval(new Stack(EvalEnvs.empty(), code.maxSlots())), true);
    }

    @Override
    public int maxSlots() {
      return varCount + rowSink.maxSlots();
    }

    @Override
    public void accept(Stack stack) {
      // Evaluate the collection expression using the full stack so that outer
      // variables (StackCode nodes) resolve correctly.
      final Iterable<Object> elements = (Iterable<Object>) code.eval(stack);
      // Grow slots if needed for scan variable slots.
      Stack s = stack.ensureSize(varCount);
      final int savedTop = s.save();
      for (Object element : elements) {
        s.restore(savedTop);
        // Push scan variable bindings onto the stack.
        if (Closure.StackClosure.pushBindings(pat, element, s)) {
          if ((Boolean) conditionCode.eval(s)) {
            rowSink.accept(s);
          }
        }
      }
      s.restore(savedTop);
    }
  }

  /** Implementation of {@link RowSink} for a {@code where} step. */
  private static class WhereRowSink extends BaseRowSink {
    final Code filterCode;

    WhereRowSink(Code filterCode, RowSink rowSink) {
      super(rowSink);
      this.filterCode = filterCode;
    }

    @Override
    public Describer describe(Describer describer) {
      return describer.start(
          "where", d -> d.arg("condition", filterCode).arg("sink", rowSink));
    }

    @Override
    public void accept(Stack stack) {
      if ((Boolean) filterCode.eval(stack)) {
        rowSink.accept(stack);
      }
    }
  }

  /** Implementation of {@link RowSink} for a {@code skip} step. */
  private static class SkipRowSink extends BaseRowSink {
    final Code skipCode;
    int skip;

    SkipRowSink(Code skipCode, RowSink rowSink) {
      super(rowSink);
      this.skipCode = skipCode;
    }

    @Override
    public Describer describe(Describer describer) {
      return describer.start(
          "skip", d -> d.arg("count", skipCode).arg("sink", rowSink));
    }

    @Override
    public void start(Stack stack) {
      skip = (Integer) skipCode.eval(stack);
      rowSink.start(stack);
    }

    @Override
    public void accept(Stack stack) {
      if (skip > 0) {
        --skip;
      } else {
        rowSink.accept(stack);
      }
    }
  }

  /** Implementation of {@link RowSink} for a {@code take} step. */
  private static class TakeRowSink extends BaseRowSink {
    final Code takeCode;
    int take;

    TakeRowSink(Code takeCode, RowSink rowSink) {
      super(rowSink);
      this.takeCode = takeCode;
    }

    @Override
    public Describer describe(Describer describer) {
      return describer.start(
          "take", d -> d.arg("count", takeCode).arg("sink", rowSink));
    }

    @Override
    public void start(Stack stack) {
      take = (Integer) takeCode.eval(stack);
      rowSink.start(stack);
    }

    @Override
    public void accept(Stack stack) {
      if (take > 0) {
        --take;
        rowSink.accept(stack);
      }
    }
  }

  /**
   * Implementation of {@link RowSink} for an {@code except}, {@code intersect},
   * or {@code union} step.
   */
  private abstract static class SetRowSink extends BaseRowSink {
    static final int[] ZERO = {};

    final Op op;
    final boolean distinct;
    final ImmutableList<Code> codes;
    final ImmutableList<String> names;
    /**
     * (Name, code) slots to capture scope variables during {@code
     * accept(Stack)}.
     */
    final ImmutablePairList<String, Code> inSlots;
    /**
     * Number of {@code names} entries that are stack-layout-based. These are
     * pushed back onto the stack at {@code result()} time.
     */
    final int scanDepth;

    final Map<Object, int[]> map;

    final Object[] values;

    SetRowSink(
        Op op,
        boolean distinct,
        ImmutableList<Code> codes,
        ImmutableList<String> names,
        ImmutablePairList<String, Code> inSlots,
        int scanDepth,
        RowSink rowSink) {
      super(rowSink);
      checkArgument(
          op == Op.EXCEPT || op == Op.INTERSECT || op == Op.UNION,
          "invalid op %s",
          op);
      this.op = op;
      this.distinct = distinct;
      this.codes = requireNonNull(codes);
      this.names = requireNonNull(names);
      this.inSlots = requireNonNull(inSlots);
      this.scanDepth = scanDepth;
      this.values = new Object[names.size()];
      if (op == Op.UNION && !distinct) {
        // Union-all does not require storage.
        map = ImmutableMap.of();
      } else if (op == Op.INTERSECT && distinct) {
        // Intersect-distinct needs to preserve order of insertion.
        map = new LinkedHashMap<>();
      } else {
        // Except and intersect-all use the map only for probing, so a HashMap
        // is fine.
        map = new HashMap<>();
      }
    }

    @Override
    public Describer describe(Describer describer) {
      return describer.start(
          op.opName,
          d -> {
            d.arg("distinct", distinct);
            forEachIndexed(codes, (code, i) -> d.arg("arg" + i, code));
            d.arg("sink", rowSink);
          });
    }

    /**
     * Adds the current element to the collection, and returns whether it was
     * added.
     */
    boolean add(EvalEnv env) {
      if (names.size() == 1) {
        Object value = env.getOpt(names.get(0));
        return map.put(value, ZERO) == null;
      } else {
        for (int i = 0; i < names.size(); i++) {
          values[i] = env.getOpt(names.get(i));
        }
        return map.put(ImmutableList.copyOf(values), ZERO) == null;
      }
    }

    /** Removes the current element from the collection. */
    void remove(EvalEnv env) {
      if (names.size() == 1) {
        Object value = env.getOpt(names.get(0));
        map.remove(value);
      } else {
        for (int i = 0; i < names.size(); i++) {
          values[i] = env.getOpt(names.get(i));
        }
        map.remove(ImmutableList.copyOf(values));
      }
    }

    /** Increments the count of the current element in the collection. */
    void inc(EvalEnv env) {
      compute(
          env,
          (k, v) -> {
            if (v == null) {
              return new int[] {1};
            }
            ++v[0];
            return v;
          });
    }

    /**
     * Decrements the count of the current element in the collection, if it is
     * present.
     */
    void dec(EvalEnv env) {
      computeIfPresent(
          env,
          (k, v) -> {
            --v[0];
            return v;
          });
    }

    /** Does something to the count of the current element in the collection. */
    void compute(EvalEnv env, BiFunction<Object, int[], int[]> fn) {
      Object value;
      if (names.size() == 1) {
        value = env.getOpt(names.get(0));
      } else {
        for (int i = 0; i < names.size(); i++) {
          values[i] = env.getOpt(names.get(i));
        }
        value = ImmutableList.copyOf(values);
      }
      map.compute(value, fn);
    }

    /** Does something to the count of the current element in the collection. */
    void computeIfPresent(EvalEnv env, BiFunction<Object, int[], int[]> fn) {
      Object value;
      if (names.size() == 1) {
        value = env.getOpt(names.get(0));
      } else {
        for (int i = 0; i < names.size(); i++) {
          values[i] = env.getOpt(names.get(i));
        }
        value = ImmutableList.copyOf(values);
      }
      map.computeIfPresent(value, fn);
    }

    /** Does something to the count of the current element in the collection. */
    void computeIfAbsent(EvalEnv env, Function<Object, int[]> fn) {
      Object value;
      if (names.size() == 1) {
        value = env.getOpt(names.get(0));
      } else {
        for (int i = 0; i < names.size(); i++) {
          values[i] = env.getOpt(names.get(i));
        }
        value = ImmutableList.copyOf(values);
      }
      map.computeIfAbsent(value, fn);
    }

    /** Computes the key for the current row using codes for {@code names}. */
    Object computeKey(Stack stack) {
      if (names.size() == 1) {
        int idx = inSlots.leftList().indexOf(names.get(0));
        return inSlots.right(idx).eval(stack);
      } else {
        final Object[] keyValues = new Object[names.size()];
        for (int i = 0; i < names.size(); i++) {
          int idx = inSlots.leftList().indexOf(names.get(i));
          keyValues[i] = inSlots.right(idx).eval(stack);
        }
        return ImmutableList.copyOf(keyValues);
      }
    }

    // Stack-based helpers use inCodes to read scope variables from stack.
    boolean add(Stack stack) {
      return map.put(computeKey(stack), ZERO) == null;
    }

    void remove(Stack stack) {
      map.remove(computeKey(stack));
    }

    void inc(Stack stack) {
      compute(
          stack,
          (k, v) -> {
            if (v == null) {
              return new int[] {1};
            }
            ++v[0];
            return v;
          });
    }

    void dec(Stack stack) {
      computeIfPresent(
          stack,
          (k, v) -> {
            --v[0];
            return v;
          });
    }

    void compute(Stack stack, BiFunction<Object, int[], int[]> fn) {
      map.compute(computeKey(stack), fn);
    }

    void computeIfPresent(Stack stack, BiFunction<Object, int[], int[]> fn) {
      map.computeIfPresent(computeKey(stack), fn);
    }

    void computeIfAbsent(Stack stack, Function<Object, int[]> fn) {
      map.computeIfAbsent(computeKey(stack), fn);
    }

    @Override
    public int maxSlots() {
      return scanDepth + rowSink.maxSlots();
    }

    /**
     * Prepares the stack for a downstream {@code accept()} or {@code result()}
     * call by pushing stack-based key values back onto the stack and binding
     * env-based key values into {@code globalEnv}.
     *
     * <p>The {@code key} is the value stored in the map: a single value when
     * {@code names.size() == 1}, or an {@code ImmutableList} otherwise.
     */
    Stack withRowFromKey(Stack s, Object key, int savedTop) {
      if (names.size() == 1) {
        if (scanDepth == 1) {
          s.push(key);
          return s;
        } else {
          // env-based: bind in MutableEvalEnv
          final MutableEvalEnv mEnv = s.globalEnv.bindMutable(names.get(0));
          mEnv.set(key);
          return new Stack(mEnv, s.slots, s.top);
        }
      } else {
        @SuppressWarnings("unchecked")
        final List<Object> keyList = (List<Object>) key;
        for (int i = 0; i < scanDepth; i++) {
          s.push(keyList.get(i));
        }
        if (scanDepth < names.size()) {
          EvalEnv env2 = s.globalEnv;
          for (int i = scanDepth; i < names.size(); i++) {
            final MutableEvalEnv mEnv = env2.bindMutable(names.get(i));
            mEnv.set(keyList.get(i));
            env2 = mEnv;
          }
          return new Stack(env2, s.slots, s.top);
        }
        return s;
      }
    }
  }

  /** Implementation of {@link RowSink} for non-distinct {@code except} step. */
  private static class ExceptAllRowSink extends SetRowSink {
    private boolean initialized = false;

    ExceptAllRowSink(
        ImmutableList<Code> codes,
        ImmutableList<String> names,
        ImmutablePairList<String, Code> inSlots,
        int scanDepth,
        RowSink rowSink) {
      super(Op.EXCEPT, false, codes, names, inSlots, scanDepth, rowSink);
    }

    @Override
    public void accept(Stack stack) {
      if (!initialized) {
        initialized = true;
        final MutableEvalEnv mutableEvalEnv =
            stack.globalEnv.bindMutableArray(names);
        for (Code code : codes) {
          final Iterable<Object> elements = (Iterable<Object>) code.eval(stack);
          for (Object element : elements) {
            mutableEvalEnv.set(element);
            inc(mutableEvalEnv);
          }
        }
      }
      // Use inCodes to compute the key for the current row from stack.
      final Object value = computeKey(stack);
      int[] count = map.get(value);
      if (count != null && count[0] > 0) {
        --count[0];
        if (count[0] == 0) {
          map.remove(value);
        }
      } else {
        // Scan vars are live on the stack; pass through directly.
        rowSink.accept(stack);
      }
    }
  }

  /** Implementation of {@link RowSink} for a distinct {@code except} step. */
  private static class ExceptDistinctRowSink extends SetRowSink {
    ExceptDistinctRowSink(
        ImmutableList<Code> codes,
        ImmutableList<String> names,
        ImmutablePairList<String, Code> inSlots,
        int scanDepth,
        RowSink rowSink) {
      super(Op.EXCEPT, true, codes, names, inSlots, scanDepth, rowSink);
    }

    @Override
    public void accept(Stack stack) {
      add(stack);
    }

    @Override
    public List<Object> result(Stack stack) {
      final MutableEvalEnv mutableEvalEnv =
          stack.globalEnv.bindMutableArray(names);
      for (Code code : codes) {
        final Iterable<Object> elements = (Iterable<Object>) code.eval(stack);
        for (Object element : elements) {
          mutableEvalEnv.set(element);
          remove(mutableEvalEnv);
        }
      }
      if (!map.isEmpty()) {
        Stack s = stack.ensureSize(scanDepth);
        final int savedTop = s.top;
        final Stack s2 = s;
        map.keySet()
            .forEach(
                element -> {
                  rowSink.accept(withRowFromKey(s2, element, savedTop));
                  s2.restore(savedTop);
                });
      }
      return rowSink.result(stack);
    }
  }

  /**
   * Implementation of {@link RowSink} for a non-distinct {@code intersect}
   * step.
   *
   * <p>The algorithm is as follows:
   *
   * <ol>
   *   <li>During accept() calls, populate slot 0 with counts from input 0
   *       (upstream).
   *   <li>On first call to accept(), also populate slots 1..n from
   *       codes[0..n-1] (inputs 1..n), then compute min(count0, ..., countN)
   *       and move it into slot 0, removing any keys with min count of 0.
   *   <li>For each accept() call, probe the map for the current element,
   *       decrement its count in slot 0, emit it, and remove it if count
   *       reaches 0. This ensures that elements are emitted in the order they
   *       appear in input 0, with the correct multiplicity.
   * </ol>
   */
  private static class IntersectAllRowSink extends SetRowSink {
    private boolean initialized = false;

    IntersectAllRowSink(
        ImmutableList<Code> codes,
        ImmutableList<String> names,
        ImmutablePairList<String, Code> inSlots,
        int scanDepth,
        RowSink rowSink) {
      super(Op.INTERSECT, false, codes, names, inSlots, scanDepth, rowSink);
    }

    @Override
    public void accept(Stack stack) {
      if (!initialized) {
        initialized = true;
        final int n = codes.size();
        final MutableEvalEnv mutableEvalEnv =
            stack.globalEnv.bindMutableArray(names);
        for (int i = 0; i < codes.size(); i++) {
          final int slot = i;
          final Code code = codes.get(i);
          final Iterable<Object> elements = (Iterable<Object>) code.eval(stack);
          for (Object element : elements) {
            mutableEvalEnv.set(element);
            compute(
                mutableEvalEnv,
                (k, v) -> {
                  if (v == null) {
                    v = new int[n];
                  }
                  ++v[slot];
                  return v;
                });
          }
        }
        map.entrySet()
            .removeIf(
                e -> {
                  int[] counts = e.getValue();
                  int minCount = counts[0];
                  for (int i = 1; i < n; i++) {
                    minCount = Math.min(minCount, counts[i]);
                  }
                  counts[0] = minCount;
                  return minCount == 0;
                });
      }
      // Use inCodes to compute the key for the current row from stack.
      final Object value = computeKey(stack);
      map.computeIfPresent(
          value,
          (k, counts) -> {
            // Scan vars are live on the stack; pass through directly.
            rowSink.accept(stack);
            return --counts[0] == 0 ? null : counts;
          });
    }
  }

  /**
   * Implementation of {@link RowSink} for a distinct {@code intersect} step.
   *
   * <p>The algorithm is as follows:
   *
   * <ol>
   *   <li>Populate the map with (k, 0) for each key k from input 0;
   *   <li>Read input 1, and for each key increments the count.
   *   <li>If there is another input, first remove each key with count zero, and
   *       sets other keys' count to zero. Then repeat from step 1.
   *   <li>Output all keys that have count greater than 0 from the last pass.
   * </ol>
   */
  private static class IntersectDistinctRowSink extends SetRowSink {
    IntersectDistinctRowSink(
        ImmutableList<Code> codes,
        ImmutableList<String> names,
        ImmutablePairList<String, Code> inSlots,
        int scanDepth,
        RowSink rowSink) {
      super(Op.INTERSECT, true, codes, names, inSlots, scanDepth, rowSink);
    }

    @Override
    public void accept(Stack stack) {
      // Use inCodes to compute the key for the current row from stack.
      map.computeIfAbsent(computeKey(stack), k -> new int[] {0});
    }

    @Override
    public List<Object> result(Stack stack) {
      final MutableEvalEnv mutableEvalEnv =
          stack.globalEnv.bindMutableArray(names);
      int pass = 0;
      for (Code code : codes) {
        final Iterable<Object> elements = (Iterable<Object>) code.eval(stack);
        if (pass++ > 0) {
          map.entrySet().removeIf(e -> e.getValue()[0] == 0);
          map.forEach((k, v) -> v[0] = 0);
        }
        for (Object element : elements) {
          mutableEvalEnv.set(element);
          computeIfPresent(
              mutableEvalEnv,
              (k, v) -> {
                ++v[0];
                return v;
              });
        }
      }
      if (!map.isEmpty()) {
        Stack s = stack.ensureSize(scanDepth);
        final int savedTop = s.top;
        final Stack s2 = s;
        map.forEach(
            (k, v) -> {
              if (v[0] > 0) {
                rowSink.accept(withRowFromKey(s2, k, savedTop));
                s2.restore(savedTop);
              }
            });
      }
      return rowSink.result(stack);
    }
  }

  /** Implementation of {@link RowSink} for a {@code union} step. */
  private static class UnionRowSink extends SetRowSink {
    UnionRowSink(
        boolean distinct,
        ImmutableList<Code> codes,
        ImmutableList<String> names,
        ImmutablePairList<String, Code> inSlots,
        int scanDepth,
        RowSink rowSink) {
      super(Op.UNION, distinct, codes, names, inSlots, scanDepth, rowSink);
    }

    @Override
    public void accept(Stack stack) {
      if (!distinct || add(stack)) {
        // Scan vars are live on the stack; pass through directly.
        rowSink.accept(stack);
      }
    }

    @Override
    public List<Object> result(Stack stack) {
      // keyEnv is used only for distinctness checks (add(keyEnv)).
      final MutableEvalEnv keyEnv = stack.globalEnv.bindMutableArray(names);
      Stack s = stack.ensureSize(scanDepth);
      final int savedTop = s.top;
      final Stack s2 = s;
      for (Code code : codes) {
        final Iterable<Object> elements = (Iterable<Object>) code.eval(stack);
        for (Object element : elements) {
          keyEnv.set(element);
          if (!distinct || add(keyEnv)) {
            rowSink.accept(withRowFromKey(s2, element, savedTop));
            s2.restore(savedTop);
          }
        }
      }
      return rowSink.result(stack);
    }
  }

  /** Implementation of {@link RowSink} for a {@code group} step. */
  private static class GroupRowSink extends BaseRowSink {
    final Code keyCode;
    final ImmutableList<String> keyNames;
    /** group names followed by aggregate names */
    final ImmutableList<String> outNames;
    /**
     * (Name, code) slots to capture scope variables during {@code
     * accept(Stack)}.
     */
    final ImmutablePairList<String, Code> inSlots;
    /**
     * Number of {@code inSlots} entries that are stack-layout-based. These are
     * pushed back onto the stack inside {@link Codes#aggregate} at result()
     * time so that {@code argumentCode} can read them via StackCode.
     */
    final int scanDepth;

    final ImmutableList<Applicable> aggregateCodes;
    final ListMultimap<Object, Object> map = ArrayListMultimap.create();
    final Object[] values;

    GroupRowSink(
        Code keyCode,
        ImmutableList<Applicable> aggregateCodes,
        ImmutablePairList<String, Code> inSlots,
        int scanDepth,
        ImmutableList<String> keyNames,
        ImmutableList<String> outNames,
        RowSink rowSink) {
      super(rowSink);
      this.keyCode = requireNonNull(keyCode);
      this.aggregateCodes = requireNonNull(aggregateCodes);
      this.inSlots = requireNonNull(inSlots);
      this.scanDepth = scanDepth;
      this.keyNames = requireNonNull(keyNames);
      this.outNames = requireNonNull(outNames);
      this.values = inSlots.size() == 1 ? null : new Object[inSlots.size()];
      checkArgument(isPrefix(keyNames, outNames));
    }

    @Override
    public int maxSlots() {
      return scanDepth + rowSink.maxSlots();
    }

    static <E> boolean isPrefix(List<E> list0, List<E> list1) {
      return list0.size() <= list1.size()
          && list0.equals(list1.subList(0, list0.size()));
    }

    @Override
    public Describer describe(Describer describer) {
      return describer.start(
          "group",
          d -> {
            d.arg("key", keyCode);
            aggregateCodes.forEach(a -> d.arg("agg", a));
            d.arg("sink", rowSink);
          });
    }

    @Override
    public void accept(Stack stack) {
      if (inSlots.size() == 1) {
        map.put(keyCode.eval(stack), inSlots.right(0).eval(stack));
      } else {
        for (int i = 0; i < inSlots.size(); i++) {
          values[i] = inSlots.right(i).eval(stack);
        }
        map.put(keyCode.eval(stack), values.clone());
      }
    }

    @Override
    public List<Object> result(Stack stack) {
      EvalEnv env2 = stack.globalEnv;
      final MutableEvalEnv[] groupEnvs = new MutableEvalEnv[outNames.size()];
      int i = 0;
      for (String name : outNames) {
        env2 = groupEnvs[i++] = env2.bindMutable(name);
      }
      final EvalEnv env3 =
          keyNames.isEmpty() ? stack.globalEnv : groupEnvs[keyNames.size() - 1];
      final Stack innerStack = new Stack(env2, stack.slots, stack.top);
      final Map<Object, List<Object>> map2;
      if (map.isEmpty()
          && keyCode instanceof Codes.TupleCode
          && ((Codes.TupleCode) keyCode).codes.isEmpty()) {
        map2 = ImmutableMap.of(ImmutableList.of(), ImmutableList.of());
      } else {
        //noinspection UnstableApiUsage
        map2 = Multimaps.asMap(map);
      }
      for (Map.Entry<Object, List<Object>> entry : map2.entrySet()) {
        final List list = (List) entry.getKey();
        for (i = 0; i < list.size(); i++) {
          groupEnvs[i].set(list.get(i));
        }
        final List<Object> rows = entry.getValue();
        for (Applicable aggregateCode : aggregateCodes) {
          // Pass a Stack so that StackCode nodes in the argument expression
          // (outer let-bound variables) are evaluated with the correct slots.
          groupEnvs[i++].set(
              aggregateCode.apply(
                  new Stack(env3, stack.slots, stack.top), rows));
        }
        rowSink.accept(innerStack);
      }
      return rowSink.result(innerStack);
    }
  }

  /** Implementation of {@link RowSink} for an {@code order} step. */
  private static class OrderRowSink extends BaseRowSink {
    final Code code;
    final Comparator comparator;
    /**
     * (Name, code) slots to capture scope variables during {@code
     * accept(Stack)}.
     */
    final ImmutablePairList<String, Code> inSlots;
    /**
     * Number of {@code inSlots} entries that are stack-layout-based. These are
     * pushed back onto the stack at {@code result()} time.
     */
    final int scanDepth;

    final List<Object> rows = new ArrayList<>();
    final Object @Nullable [] values;

    OrderRowSink(
        Code code,
        Comparator comparator,
        ImmutablePairList<String, Code> inSlots,
        int scanDepth,
        RowSink rowSink) {
      super(rowSink);
      this.code = code;
      this.comparator = comparator;
      this.inSlots = inSlots;
      this.scanDepth = scanDepth;
      this.values = inSlots.size() == 1 ? null : new Object[inSlots.size()];
    }

    @Override
    public Describer describe(Describer describer) {
      return describer.start(
          "order", d -> d.arg("code", code).arg("sink", rowSink));
    }

    @Override
    public void accept(Stack stack) {
      // Use inSlots to capture scope variables from stack/env.
      if (inSlots.size() == 1) {
        rows.add(inSlots.right(0).eval(stack));
      } else {
        final Object[] row = new Object[inSlots.size()];
        for (int i = 0; i < inSlots.size(); i++) {
          row[i] = inSlots.right(i).eval(stack);
        }
        rows.add(row);
      }
    }

    @Override
    public int maxSlots() {
      return scanDepth + rowSink.maxSlots();
    }

    @Override
    public List<Object> result(Stack stack) {
      Stack s = stack.ensureSize(scanDepth);
      final int savedTop = s.top;
      final int envCount = inSlots.size() - scanDepth;
      final Stack s2 = s; // effectively final for lambda
      rows.sort(
          (left, right) -> {
            final Object leftVal =
                code.eval(withRow(s2, left, savedTop, envCount));
            s2.restore(savedTop);
            final Object rightVal =
                code.eval(withRow(s2, right, savedTop, envCount));
            s2.restore(savedTop);
            return comparator.compare(leftVal, rightVal);
          });
      for (Object row : rows) {
        rowSink.accept(withRow(s, row, savedTop, envCount));
        s.restore(savedTop);
      }
      return rowSink.result(stack);
    }

    /**
     * Pushes per-row captured values back onto the stack (and into globalEnv
     * for env-based slots), restoring the scan-time context so that downstream
     * {@code StackCode} nodes resolve correctly.
     */
    private Stack withRow(Stack s, Object row, int savedTop, int envCount) {
      if (inSlots.size() == 1) {
        if (scanDepth == 1) {
          s.push(row);
        } else { // scanDepth == 0: single env-based slot
          final MutableEvalEnv mEnv = s.globalEnv.bindMutable(inSlots.left(0));
          mEnv.set(row);
          return new Stack(mEnv, s.slots, s.top);
        }
      } else {
        final Object[] arr = (Object[]) row;
        for (int i = 0; i < scanDepth; i++) {
          s.push(arr[i]);
        }
        if (envCount > 0) {
          EvalEnv env2 = s.globalEnv;
          for (int i = scanDepth; i < inSlots.size(); i++) {
            final MutableEvalEnv mEnv = env2.bindMutable(inSlots.left(i));
            mEnv.set(arr[i]);
            env2 = mEnv;
          }
          return new Stack(env2, s.slots, s.top);
        }
      }
      return s;
    }
  }

  /**
   * Implementation of {@link RowSink} for a {@code yield} step.
   *
   * <p>If this is the last step, use instead a {@link CollectRowSink}. It is
   * more efficient; there is no downstream row sink; and a terminal yield step
   * is allowed to generate expressions that are not records. Non-record
   * expressions (e.g. {@code int} expressions) do not have a name, and
   * therefore the value cannot be passed via the {@link EvalEnv}.
   */
  private static class YieldRowSink extends BaseRowSink {
    final ImmutableList<String> names;
    final ImmutableList<Code> codes;
    final Object[] values;

    YieldRowSink(
        ImmutableList<String> names,
        ImmutableList<Code> codes,
        RowSink rowSink) {
      super(rowSink);
      this.names = names;
      this.codes = codes;
      this.values = names.size() == 1 ? null : new Object[names.size()];
    }

    @Override
    public Describer describe(Describer describer) {
      return describer.start(
          "yield", d -> d.args("codes", codes).arg("sink", rowSink));
    }

    @Override
    public int maxSlots() {
      return codes.size() + rowSink.maxSlots();
    }

    @Override
    public void accept(Stack stack) {
      Stack s = stack.ensureSize(codes.size());
      final int savedTop = s.top;
      // Evaluate all yield codes from the input stack before pushing any
      // value: simultaneous evaluation prevents one yield's value from
      // affecting another yield's expression, and keeps StackCode offsets
      // valid throughout.
      if (values == null) {
        s.push(codes.get(0).eval(stack));
      } else {
        for (int i = 0; i < codes.size(); i++) {
          values[i] = codes.get(i).eval(stack);
        }
        for (Object v : values) {
          s.push(v);
        }
      }
      rowSink.accept(s);
      s.restore(savedTop);
    }
  }

  /**
   * Implementation of {@link RowSink} that the last step of a {@code from}
   * writes into.
   */
  private static class CollectRowSink implements RowSink {
    final List<Object> list = new ArrayList<>();
    final Code code;

    CollectRowSink(Code code) {
      this.code = requireNonNull(code);
    }

    @Override
    public Describer describe(Describer describer) {
      return describer.start("collect", d -> d.arg("", code));
    }

    @Override
    public void start(Stack stack) {
      list.clear();
    }

    @Override
    public void accept(Stack stack) {
      list.add(code.eval(stack));
    }

    @Override
    public List<Object> result(Stack stack) {
      return list;
    }
  }

  /** First row sink in the chain. */
  private static class FirstRowSink extends BaseRowSink {
    final ImmutableList<Runnable> startActions;

    FirstRowSink(RowSink rowSink, List<Runnable> startActions) {
      super(rowSink);
      this.startActions = ImmutableList.copyOf(startActions);
    }

    @Override
    public Describer describe(Describer describer) {
      return rowSink.describe(describer);
    }

    @Override
    public void start(Stack stack) {
      startActions.forEach(Runnable::run);
      rowSink.start(stack);
    }
  }
}

// End RowSinks.java
