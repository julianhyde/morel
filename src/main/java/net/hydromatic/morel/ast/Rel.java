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

import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import java.util.List;
import java.util.Map;
import net.hydromatic.morel.type.ListType;
import net.hydromatic.morel.type.Type;

/**
 * Node in a relational tree: an operator whose inputs are collections and whose
 * value is a collection of a definite type.
 *
 * <p>This is the balanced representation that replaces the step list of {@link
 * Core.From}; see {@code spec.md} for the normative description, and {@code
 * plan.md} for the sequence in which it lands. In step 1 the tree is a shadow:
 * it is built and printed, but {@code Core.From} still does the work.
 *
 * <p>Unlike a {@link Core.FromStep}, a node carries no bindings. Its element
 * type is derived from its inputs and its expressions, and is exactly the type
 * of the value that flows out of it. Expressions inside a node name the input
 * element {@code $0} (and, in a {@link Join}, the right input element {@code
 * $1}); the exception is {@link ProjectMany}, whose lambda parameter names the
 * input element, because its body may contain a tree that would shadow {@code
 * $0}.
 */
public abstract class Rel {
  /**
   * Collection type of this node; {@code list} or {@code bag} of the element
   * type.
   */
  public final Type type;

  protected Rel(Type type) {
    this.type = requireNonNull(type, "type");
    if (!type.isCollection()) {
      throw new IllegalArgumentException("not a collection type: " + type);
    }
  }

  /** Returns the type of the elements of this node's output. */
  public Type elementType() {
    return type.elementType();
  }

  /**
   * Returns whether this node's output is ordered, that is, a {@code list}
   * rather than a {@code bag}.
   */
  public boolean isOrdered() {
    return type instanceof ListType;
  }

  /** Returns the name of this node's operator, as it appears in plan text. */
  public abstract String opName();

  /** Returns this node's inputs. */
  public abstract List<Rel> inputs();

  /**
   * Appends this node's arguments, each in brackets, to a plan-text line.
   *
   * <p>Arguments that carry no information (an inner join's kind, a condition
   * that is {@code true}) are omitted.
   */
  protected void describeArgs(StringBuilder b) {}

  /** Returns this node's plan text, as {@code Sys.plan} prints it. */
  public String describe() {
    return describe(false);
  }

  /**
   * Returns this node's plan text; if {@code withTypes}, appends the collection
   * type of every node, as {@code Sys.planEx} prints it.
   */
  public String describe(boolean withTypes) {
    final StringBuilder b = new StringBuilder();
    describe(b, 0, withTypes);
    return b.toString();
  }

  protected void describe(StringBuilder b, int indent, boolean withTypes) {
    describeLine(b, indent, withTypes);
    for (Rel input : inputs()) {
      input.describe(b, indent + 2, withTypes);
    }
  }

  protected void describeLine(StringBuilder b, int indent, boolean withTypes) {
    for (int i = 0; i < indent; i++) {
      b.append(' ');
    }
    b.append(opName());
    describeArgs(b);
    if (withTypes) {
      b.append(" : ").append(type.moniker());
    }
    b.append('\n');
  }

  /** Appends an argument, in brackets, to a plan-text line. */
  protected static void arg(StringBuilder b, Object arg) {
    b.append(" [").append(arg).append(']');
  }

  /** Appends a named-argument list, in brackets, to a plan-text line. */
  protected static void args(
      StringBuilder b, Map<String, ? extends AstNode> map) {
    b.append(" [");
    int i = 0;
    for (Map.Entry<String, ? extends AstNode> entry : map.entrySet()) {
      if (i++ > 0) {
        b.append(", ");
      }
      b.append(entry.getKey()).append(" = ").append(entry.getValue());
    }
    b.append(']');
  }

  @Override
  public String toString() {
    return describe();
  }

  /**
   * Leaf: a collection-valued expression, and the boundary between the tree and
   * the rest of Core.
   *
   * <p>A scan binds nothing. Its element flows out as a value, and the node
   * above names it {@code $0}.
   */
  public static class Scan extends Rel {
    public final Core.Exp exp;

    Scan(Core.Exp exp) {
      super(exp.type);
      this.exp = exp;
    }

    @Override
    public String opName() {
      return "scan";
    }

    @Override
    public List<Rel> inputs() {
      return ImmutableList.of();
    }

    @Override
    protected void describeArgs(StringBuilder b) {
      arg(b, exp);
    }
  }

  /** Node with one input. */
  public abstract static class SingleRel extends Rel {
    public final Rel input;

    SingleRel(Type type, Rel input) {
      super(type);
      this.input = requireNonNull(input, "input");
    }

    @Override
    public List<Rel> inputs() {
      return ImmutableList.of(input);
    }
  }

  /**
   * Removes the elements for which a condition, an expression over {@code $0},
   * is false.
   */
  public static class Filter extends SingleRel {
    public final Core.Exp condition;

    Filter(Rel input, Core.Exp condition) {
      super(input.type, input);
      this.condition = requireNonNull(condition, "condition");
    }

    @Override
    public String opName() {
      return "filter";
    }

    @Override
    protected void describeArgs(StringBuilder b) {
      arg(b, condition);
    }
  }

  /** Maps each element to one element, via an expression over {@code $0}. */
  public static class Project extends SingleRel {
    public final Core.Exp exp;

    Project(Type type, Rel input, Core.Exp exp) {
      super(type, input);
      this.exp = requireNonNull(exp, "exp");
    }

    @Override
    public String opName() {
      return "project";
    }

    @Override
    protected void describeArgs(StringBuilder b) {
      arg(b, exp);
    }
  }

  /**
   * Maps each element to many elements: monadic bind, and what a dependent scan
   * becomes.
   *
   * <p>The parameter {@code param} names the input element throughout the body,
   * in place of {@code $0}, because the body is a tree and would otherwise
   * shadow it. The node is correlated if, and only if, a leaf of the body
   * mentions the parameter.
   */
  public static class ProjectMany extends SingleRel {
    public final Core.IdPat param;
    public final Rel body;

    ProjectMany(Type type, Rel input, Core.IdPat param, Rel body) {
      super(type, input);
      this.param = requireNonNull(param, "param");
      this.body = requireNonNull(body, "body");
    }

    @Override
    public String opName() {
      return "projectMany";
    }

    @Override
    protected void describe(StringBuilder b, int indent, boolean withTypes) {
      describeLine(b, indent, withTypes);
      input.describe(b, indent + 2, withTypes);
      for (int i = 0; i < indent + 2; i++) {
        b.append(' ');
      }
      b.append("fn ").append(param.name).append(" =>").append('\n');
      body.describe(b, indent + 4, withTypes);
    }
  }

  /**
   * Groups elements by zero or more keys, computing zero or more aggregates.
   *
   * <p>Keys and aggregate arguments are expressions over {@code $0}; the labels
   * are the output record's labels. {@code distinct} is this node with the
   * whole element as its only key and no aggregates.
   */
  public static class Group extends SingleRel {
    public final ImmutableSortedMap<String, Core.Exp> keys;
    public final ImmutableSortedMap<String, Core.Aggregate> aggregates;

    Group(
        Type type,
        Rel input,
        ImmutableSortedMap<String, Core.Exp> keys,
        ImmutableSortedMap<String, Core.Aggregate> aggregates) {
      super(type, input);
      this.keys = requireNonNull(keys, "keys");
      this.aggregates = requireNonNull(aggregates, "aggregates");
    }

    @Override
    public String opName() {
      return "group";
    }

    @Override
    protected void describeArgs(StringBuilder b) {
      args(b, keys);
      if (!aggregates.isEmpty()) {
        args(b, aggregates);
      }
    }
  }

  /**
   * Sorts elements by an expression over {@code $0}; always yields a {@code
   * list}.
   */
  public static class Order extends SingleRel {
    public final Core.Exp exp;

    Order(Type type, Rel input, Core.Exp exp) {
      super(type, input);
      this.exp = requireNonNull(exp, "exp");
    }

    @Override
    public String opName() {
      return "order";
    }

    @Override
    protected void describeArgs(StringBuilder b) {
      arg(b, exp);
    }
  }

  /** Discards order; always yields a {@code bag}. */
  public static class Unorder extends SingleRel {
    Unorder(Type type, Rel input) {
      super(type, input);
    }

    @Override
    public String opName() {
      return "unorder";
    }
  }

  /**
   * Discards the first {@code count} elements.
   *
   * <p>{@code count} is evaluated once, before the first element exists, and
   * therefore cannot mention {@code $0}.
   */
  public static class Skip extends SingleRel {
    public final Core.Exp count;

    Skip(Rel input, Core.Exp count) {
      super(input.type, input);
      this.count = requireNonNull(count, "count");
    }

    @Override
    public String opName() {
      return "skip";
    }

    @Override
    protected void describeArgs(StringBuilder b) {
      arg(b, count);
    }
  }

  /**
   * Keeps the first {@code count} elements.
   *
   * <p>{@code count} is evaluated once, before the first element exists, and
   * therefore cannot mention {@code $0}.
   */
  public static class Take extends SingleRel {
    public final Core.Exp count;

    Take(Rel input, Core.Exp count) {
      super(input.type, input);
      this.count = requireNonNull(count, "count");
    }

    @Override
    public String opName() {
      return "take";
    }

    @Override
    protected void describeArgs(StringBuilder b) {
      arg(b, count);
    }
  }

  /** How a {@link Join} treats elements that have no match. */
  public enum JoinType {
    INNER,
    LEFT,
    RIGHT,
    FULL;

    /**
     * Returns the type of the value that a join of this type binds to {@code
     * $0}, given the element type of its left input.
     */
    public boolean leftIsOption() {
      return this == RIGHT || this == FULL;
    }

    /**
     * Returns whether the right element is optional, and therefore whether
     * {@code $1} has an {@code option} type.
     */
    public boolean rightIsOption() {
      return this == LEFT || this == FULL;
    }

    public String toString2() {
      return name().toLowerCase(java.util.Locale.ROOT);
    }
  }

  /**
   * Pairs elements of two inputs, and maps each pair to an element via a yield
   * expression over {@code $0} and {@code $1}.
   *
   * <p>Commuting a join swaps its inputs and substitutes {@code $0} for {@code
   * $1} and vice versa in the condition and the yield; the element type is
   * unchanged, so nothing above the node rewrites.
   */
  public static class Join extends Rel {
    public final JoinType joinType;
    public final Rel left;
    public final Rel right;
    public final Core.Exp condition;
    public final Core.Exp yieldExp;

    Join(
        Type type,
        JoinType joinType,
        Rel left,
        Rel right,
        Core.Exp condition,
        Core.Exp yieldExp) {
      super(type);
      this.joinType = requireNonNull(joinType, "joinType");
      this.left = requireNonNull(left, "left");
      this.right = requireNonNull(right, "right");
      this.condition = requireNonNull(condition, "condition");
      this.yieldExp = requireNonNull(yieldExp, "yieldExp");
    }

    @Override
    public String opName() {
      return "join";
    }

    @Override
    public List<Rel> inputs() {
      return ImmutableList.of(left, right);
    }

    @Override
    protected void describeArgs(StringBuilder b) {
      if (joinType != JoinType.INNER) {
        arg(b, joinType.toString2());
      }
      if (!condition.isBoolLiteral(true)) {
        arg(b, condition);
      }
      arg(b, yieldExp);
    }
  }

  /** Which set operation a {@link SetRel} performs. */
  public enum SetOp {
    UNION,
    INTERSECT,
    EXCEPT;

    public String opName() {
      return name().toLowerCase(java.util.Locale.ROOT);
    }
  }

  /**
   * Combines the elements of two or more inputs, all of the same element type.
   */
  public static class SetRel extends Rel {
    public final SetOp setOp;
    public final boolean distinct;
    public final ImmutableList<Rel> inputs;

    SetRel(
        Type type, SetOp setOp, boolean distinct, ImmutableList<Rel> inputs) {
      super(type);
      this.setOp = requireNonNull(setOp, "setOp");
      this.distinct = distinct;
      this.inputs = requireNonNull(inputs, "inputs");
      if (inputs.size() < 2) {
        throw new IllegalArgumentException(
            "set operator needs at least two inputs: " + inputs.size());
      }
    }

    @Override
    public String opName() {
      return setOp.opName();
    }

    @Override
    public List<Rel> inputs() {
      return inputs;
    }

    @Override
    protected void describeArgs(StringBuilder b) {
      if (!distinct) {
        arg(b, "all");
      }
    }
  }
}

// End Rel.java
