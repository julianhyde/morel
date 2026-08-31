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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;
import static net.hydromatic.morel.ast.CoreBuilder.core;

import com.google.common.collect.ImmutableMap;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.function.Function;
import net.hydromatic.morel.type.ListType;
import net.hydromatic.morel.type.RecordLikeType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;

/**
 * Builds a relational tree ({@link Core.Rel}).
 *
 * <p>It does three things, and deliberately no more (discussion.md §13).
 *
 * <p>It keeps a <b>stack</b> of relational expressions. A node takes its inputs
 * from the stack and leaves its result there, so an n-ary set operator or a
 * two-input join is ordinary rather than special. {@link FromBuilder} kept a
 * linear list of steps because a step list is linear.
 *
 * <p>It keeps a <b>name map</b> per stack entry, so that a caller can say
 * {@code e} for an input's element and {@code deptno} for a field of it,
 * without writing {@code $0} or a record selector itself. The names of a node
 * are the field names of its element, plus whatever a leaf was pushed under; so
 * the map is derived rather than threaded, which is what makes it unlike a
 * {@link Core.StepEnv}.
 *
 * <p>It <b>simplifies</b>, under an {@link EnumSet} of {@link Simp}. Each
 * simplification is named and can be switched off. With {@link Simp#NONE} the
 * builder is a pure constructor, which is a test oracle: build a query twice,
 * once simplified and once not, and compare what the two produce.
 */
public class RelBuilder {
  /**
   * A simplification that {@link RelBuilder} may apply. Every one of them is
   * optional, and {@link #NONE} turns them all off.
   *
   * <p>What belongs here rather than in the rule framework of step 4 is what is
   * cheaper not to build than to build and then remove.
   */
  public enum Simp {
    /** Drops {@code filter true}. */
    FILTER_TRUE,
    /** Combines a filter over a filter into one conjunction. */
    FILTER_MERGE,
    /** Drops a projection whose expression is its input's element. */
    PROJECT_IDENTITY,
    /** Combines a projection over a projection by substitution. */
    PROJECT_MERGE,
    /** Drops {@code skip 0}. */
    SKIP_ZERO,
    /** Drops an {@code unorder} whose input is already unordered. */
    UNORDER_UNORDERED;

    /** No simplification; the builder constructs exactly what it is told. */
    public static final Set<Simp> NONE = EnumSet.noneOf(Simp.class);

    /** Every simplification. */
    public static final Set<Simp> ALL = EnumSet.allOf(Simp.class);
  }

  private final TypeSystem typeSystem;
  private final Set<Simp> simps;
  private final Deque<Frame> stack = new ArrayDeque<>();

  private RelBuilder(TypeSystem typeSystem, Set<Simp> simps) {
    this.typeSystem = requireNonNull(typeSystem);
    this.simps = EnumSet.copyOf(simps.isEmpty() ? Simp.NONE : simps);
  }

  /** Creates a builder that applies every simplification. */
  public static RelBuilder create(TypeSystem typeSystem) {
    return new RelBuilder(typeSystem, Simp.ALL);
  }

  /** Creates a builder that applies only the given simplifications. */
  public static RelBuilder create(TypeSystem typeSystem, Set<Simp> simps) {
    return new RelBuilder(typeSystem, simps);
  }

  /** Returns whether a simplification is enabled. */
  private boolean on(Simp simp) {
    return simps.contains(simp);
  }

  // Stack

  /** Pushes a relational expression, whose element has no name of its own. */
  public RelBuilder push(Core.Exp rel) {
    stack.push(new Frame(rel, elementNames(rel, ImmutableMap.of())));
    return this;
  }

  /**
   * Pushes a relational expression and names its element, as {@code from e in
   * emps} names the element {@code e}.
   */
  public RelBuilder push(String name, Core.Exp rel) {
    final Core.Exp element = core.input0(rel.type.elementType());
    stack.push(
        new Frame(rel, elementNames(rel, ImmutableMap.of(name, element))));
    return this;
  }

  /**
   * Pushes a relational expression and names its element by a pattern, as
   * {@code from (a, b) in pairs} names the two components.
   *
   * <p>The pattern is erased: what survives is one name per binder, each mapped
   * to the path that reads it out of the element. That is what a tree has
   * instead of a pattern, and it is why a pattern that can *fail* -- a literal,
   * a constructor -- is rejected here rather than erased, because such a
   * pattern also filters. Use {@link #destructurable} to ask first.
   */
  public RelBuilder push(Core.Pat pat, Core.Exp rel) {
    final Map<String, Core.Exp> names = new LinkedHashMap<>();
    final Core.Exp element = core.input0(rel.type.elementType());
    if (!destructure(pat, element, names)) {
      throw new IllegalArgumentException(
          "pattern cannot be destructured, because it can fail to match: "
              + pat);
    }
    stack.push(new Frame(rel, elementNames(rel, names)));
    return this;
  }

  /**
   * Returns whether {@link #push(Core.Pat, Core.Exp)} accepts a pattern, that
   * is, whether it binds names without also filtering.
   */
  public static boolean destructurable(Core.Pat pat) {
    switch (pat.op) {
      case ID_PAT:
      case WILDCARD_PAT:
        return true;
      case TUPLE_PAT:
      case RECORD_PAT:
        for (Core.Pat arg : args(pat)) {
          if (!destructurable(arg)) {
            return false;
          }
        }
        return true;
      default:
        return false;
    }
  }

  /** Returns the components of a tuple or record pattern. */
  private static List<Core.Pat> args(Core.Pat pat) {
    return pat.op == Op.TUPLE_PAT
        ? ((Core.TuplePat) pat).args
        : ((Core.RecordPat) pat).args;
  }

  private boolean destructure(
      Core.Pat pat, Core.Exp element, Map<String, Core.Exp> names) {
    switch (pat.op) {
      case ID_PAT:
        names.put(((Core.IdPat) pat).name, element);
        return true;
      case WILDCARD_PAT:
        return true;
      case TUPLE_PAT:
      case RECORD_PAT:
        final List<Core.Pat> args = args(pat);
        for (int i = 0; i < args.size(); i++) {
          if (!destructure(
              args.get(i), core.field(typeSystem, element, i), names)) {
            return false;
          }
        }
        return true;
      default:
        return false;
    }
  }

  /** Returns how many expressions are on the stack. */
  public int size() {
    return stack.size();
  }

  /** Returns the expression on top of the stack, without popping it. */
  public Core.Exp peek() {
    return frame(0).rel;
  }

  /**
   * Returns the finished expression, which must be the only one on the stack.
   */
  public Core.Exp build() {
    checkArgument(stack.size() == 1, "expected one expression, got %s", stack);
    return stack.pop().rel;
  }

  // Names

  /**
   * Returns the expression that a name denotes, over the element of the {@code
   * i}th input, counting from the deepest of the inputs a two-input node will
   * take. For a one-input node {@code i} is 0 and the expression is over {@code
   * $0}; for a join, input 0 is the left and input 1 the right.
   */
  public Core.Exp name(int i, String name) {
    final Core.Exp exp = frame(i).names.get(name);
    if (exp == null) {
      throw new IllegalArgumentException(
          "no name '" + name + "' among " + frame(i).names.keySet());
    }
    return rebase(exp, i);
  }

  /** Returns the expression that a name denotes in the top input. */
  public Core.Exp name(String name) {
    return name(0, name);
  }

  /** Returns a field of the {@code i}th input's element, {@code #f $i}. */
  public Core.Exp field(int i, String fieldName) {
    return field(input(i), fieldName);
  }

  /** Returns a field of the top input's element, {@code #f $0}. */
  public Core.Exp field(String fieldName) {
    return field(0, fieldName);
  }

  /** Returns a field of what a name denotes, as {@code e.deptno} does. */
  public Core.Exp field(int i, String name, String fieldName) {
    return field(name(i, name), fieldName);
  }

  /** Returns a reference to the element of the {@code i}th input. */
  public Core.Exp input(int i) {
    return rebase(core.input0(frame(i).rel.type.elementType()), i);
  }

  /** Returns a field of an expression, by name. */
  public Core.Exp field(Core.Exp exp, String fieldName) {
    final RecordLikeType recordType = (RecordLikeType) exp.type;
    final int slot =
        new ArrayList<>(recordType.argNameTypes().keySet()).indexOf(fieldName);
    checkArgument(
        slot >= 0, "no field '%s' in %s", fieldName, exp.type.moniker());
    return core.field(typeSystem, exp, slot);
  }

  /**
   * Rewrites an expression over {@code $0} to be over {@code $i}, which is what
   * a join's right input needs.
   */
  private Core.Exp rebase(Core.Exp exp, int i) {
    if (i == 0) {
      return exp;
    }
    final Core.Id input = core.input(frame(i).rel.type.elementType(), i);
    return exp.accept(
        new Shuttle(typeSystem) {
          @Override
          protected Core.Exp visit(Core.Id id) {
            return id.idPat.name.equals(CoreBuilder.INPUT_0) ? input : id;
          }
        });
  }

  /**
   * Returns the names an expression's element offers: one per field if it is a
   * record, plus any the caller supplied for the element itself.
   */
  private ImmutableMap<String, Core.Exp> elementNames(
      Core.Exp rel, Map<String, Core.Exp> extra) {
    final ImmutableMap.Builder<String, Core.Exp> b = ImmutableMap.builder();
    b.putAll(extra);
    final Type elementType = rel.type.elementType();
    if (elementType instanceof RecordLikeType) {
      final RecordLikeType recordType = (RecordLikeType) elementType;
      final Core.Exp element = core.input0(elementType);
      int slot = 0;
      for (String fieldName : recordType.argNameTypes().keySet()) {
        if (!extra.containsKey(fieldName)) {
          b.put(fieldName, core.field(typeSystem, element, slot));
        }
        ++slot;
      }
    }
    return b.build();
  }

  /**
   * Returns the {@code i}th input of the node being built, counting from the
   * deepest; {@code frame(0)} is the top of the stack for a one-input node, and
   * for a join {@code frame(0)} is the left because it was pushed first.
   */
  private Frame frame(int i) {
    checkArgument(i >= 0 && i < stack.size(), "no input %s", i);
    // A two-input node takes the top two, left deeper than right, so counting
    // from the deepest means counting down from the top.
    int j = arity() - 1 - i;
    for (Frame frame : stack) {
      if (j-- == 0) {
        return frame;
      }
    }
    throw new AssertionError();
  }

  /**
   * How many of the stack's entries the names currently address. Two while a
   * join's expressions are being written, one otherwise.
   */
  private int arity = 1;

  private int arity() {
    return arity;
  }

  /**
   * Declares that the next expressions are written over two inputs, so that
   * {@link #name(int, String)} counts {@code 0} as the left and {@code 1} as
   * the right. Used while building a join's condition and yield.
   */
  public RelBuilder pair() {
    checkArgument(stack.size() >= 2, "need two inputs");
    arity = 2;
    return this;
  }

  // Nodes

  /** Filters the top of the stack. */
  public RelBuilder filter(Core.Exp condition) {
    final Frame frame = pop();
    if (on(Simp.FILTER_TRUE) && condition.isBoolLiteral(true)) {
      return push(frame);
    }
    if (on(Simp.FILTER_MERGE) && frame.rel instanceof Core.Filter) {
      final Core.Filter filter = (Core.Filter) frame.rel;
      return push(
          frame.withRel(
              core.filter(
                  filter.input,
                  core.andAlso(typeSystem, filter.condition, condition))));
    }
    return push(frame.withRel(core.filter(frame.rel, condition)));
  }

  /** Projects the top of the stack; {@code exp} is over {@code $0}. */
  public RelBuilder project(Core.Exp exp) {
    final Frame frame = pop();
    if (on(Simp.PROJECT_IDENTITY) && isInput0(exp)) {
      return push(frame);
    }
    if (on(Simp.PROJECT_MERGE) && frame.rel instanceof Core.Project) {
      final Core.Project project = (Core.Project) frame.rel;
      return project(project.input, substitute(exp, project.exp));
    }
    return project(frame.rel, exp);
  }

  private RelBuilder project(Core.Exp input, Core.Exp exp) {
    return push(core.project(typeSystem, input, exp));
  }

  /**
   * Returns a parameter naming the top input's element, for the lambda of a
   * {@link #projectMany}. A {@code projectMany}'s body is a tree of its own, so
   * it cannot say {@code $0} -- that is its own input's element -- and names
   * the enclosing element through this parameter instead.
   */
  public Core.IdPat param(String name) {
    return core.idPat(frame(0).rel.type.elementType(), name, 0);
  }

  /**
   * Applies a collection-valued lambda to each element of the top of the stack,
   * which is what a scan that reads an earlier binder becomes.
   */
  public RelBuilder projectMany(Core.IdPat param, Core.Exp body) {
    final Frame frame = pop();
    return push(core.projectMany(typeSystem, frame.rel, param, body));
  }

  /**
   * Yields one element where the top of the stack is empty, which is what the
   * absent side of an outer join needs.
   */
  public RelBuilder ifEmpty(Core.Exp exp) {
    final Frame frame = pop();
    return push(frame.withRel(core.ifEmpty(frame.rel, exp)));
  }

  /** Sorts the top of the stack; the result is a list. */
  public RelBuilder sort(Core.Exp exp) {
    final Frame frame = pop();
    return push(frame.withRel(core.sort(typeSystem, frame.rel, exp)));
  }

  /** Discards the ordering of the top of the stack; the result is a bag. */
  public RelBuilder unorder() {
    final Frame frame = pop();
    if (on(Simp.UNORDER_UNORDERED) && !isOrdered(frame.rel)) {
      return push(frame);
    }
    return push(frame.withRel(core.unorder(typeSystem, frame.rel)));
  }

  /** Skips rows of the top of the stack. */
  public RelBuilder skip(Core.Exp count) {
    final Frame frame = pop();
    if (on(Simp.SKIP_ZERO) && isIntLiteral(count, 0)) {
      return push(frame);
    }
    return push(frame.withRel(core.skip(frame.rel, count)));
  }

  /** Takes rows of the top of the stack. */
  public RelBuilder take(Core.Exp count) {
    final Frame frame = pop();
    return push(frame.withRel(core.take(frame.rel, count)));
  }

  /** Groups the top of the stack. */
  public RelBuilder group(
      SortedMap<String, Core.Exp> keys,
      SortedMap<String, Core.Aggregate> aggregates) {
    final Frame frame = pop();
    return push(core.group(typeSystem, frame.rel, keys, aggregates));
  }

  /**
   * Joins the top two of the stack, the deeper being the left. The condition
   * and the yield are over {@code $0} and {@code $1}.
   */
  public RelBuilder join(
      Core.Rel.JoinType joinType, Core.Exp condition, Core.Exp yieldExp) {
    final Frame right = pop();
    final Frame left = pop();
    arity = 1;
    return push(
        core.join(
            typeSystem, joinType, left.rel, right.rel, condition, yieldExp));
  }

  /** Combines the top {@code n} of the stack with a set operator. */
  public RelBuilder union(int n, boolean distinct) {
    return setRel(n, inputs -> core.union(typeSystem, distinct, inputs));
  }

  /** Intersects the top {@code n} of the stack. */
  public RelBuilder intersect(int n, boolean distinct) {
    return setRel(n, inputs -> core.intersect(typeSystem, distinct, inputs));
  }

  /** Subtracts the top {@code n - 1} of the stack from the one below them. */
  public RelBuilder except(int n, boolean distinct) {
    return setRel(n, inputs -> core.except(typeSystem, distinct, inputs));
  }

  private RelBuilder setRel(int n, Function<List<Core.Exp>, Core.Exp> f) {
    checkArgument(n >= 2, "a set operator needs at least two inputs");
    final List<Core.Exp> inputs = new ArrayList<>();
    Frame first = null;
    for (int i = 0; i < n; i++) {
      final Frame frame = pop();
      inputs.add(0, frame.rel);
      first = frame;
    }
    return push(requireNonNull(first).withRel(f.apply(inputs)));
  }

  // Helpers

  private Frame pop() {
    checkArgument(!stack.isEmpty(), "stack is empty");
    arity = 1;
    return stack.pop();
  }

  private RelBuilder push(Frame frame) {
    stack.push(frame);
    return this;
  }

  private boolean isOrdered(Core.Exp rel) {
    return rel.type instanceof ListType;
  }

  private static boolean isInput0(Core.Exp exp) {
    return exp.op == Op.ID
        && ((Core.Id) exp).idPat.name.equals(CoreBuilder.INPUT_0);
  }

  private static boolean isIntLiteral(Core.Exp exp, int value) {
    return exp.op == Op.INT_LITERAL
        && ((Core.Literal) exp)
                .unwrap(BigDecimal.class)
                .compareTo(BigDecimal.valueOf(value))
            == 0;
  }

  /** Replaces {@code $0} in an expression with another expression. */
  private Core.Exp substitute(Core.Exp exp, Core.Exp e0) {
    return exp.accept(
        new Shuttle(typeSystem) {
          @Override
          protected Core.Exp visit(Core.Id id) {
            return isInput0(id) ? core.at(e0, id.pos) : id;
          }
        });
  }

  /** An expression on the stack, and the names its element offers. */
  private static class Frame {
    final Core.Exp rel;
    final ImmutableMap<String, Core.Exp> names;

    Frame(Core.Exp rel, ImmutableMap<String, Core.Exp> names) {
      this.rel = requireNonNull(rel);
      this.names = requireNonNull(names);
    }

    /**
     * Returns a frame for an expression whose element is the same as this
     * one's, and which therefore offers the same names.
     */
    Frame withRel(Core.Exp rel) {
      return new Frame(rel, names);
    }

    @Override
    public String toString() {
      return rel.toString();
    }
  }
}

// End RelBuilder.java
