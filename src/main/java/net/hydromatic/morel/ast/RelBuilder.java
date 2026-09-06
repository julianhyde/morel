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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.function.Function;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.type.ListType;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.RecordLikeType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import org.jspecify.annotations.Nullable;

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
 * <p>It <b>simplifies</b>, under a set of {@link Simplification}. Each
 * simplification is named and can be switched off. With {@link
 * Simplification#none()} the builder is a pure constructor, which is a test
 * oracle: build a query twice, once simplified and once not, and compare what
 * the two produce.
 */
public class RelBuilder {
  private final TypeSystem typeSystem;
  private final ImmutableSet<Simplification> simps;
  private final Deque<Frame> stack = new ArrayDeque<>();

  /**
   * Counter for generated binders. Per builder, not per type system, so that
   * plan text does not depend on what was compiled before it (spec.md §6).
   */
  private int nextName = 0;

  private RelBuilder(TypeSystem typeSystem, Set<Simplification> simps) {
    this.typeSystem = requireNonNull(typeSystem);
    this.simps = ImmutableSet.copyOf(simps);
  }

  /** Creates a builder that applies every simplification. */
  public static RelBuilder create(TypeSystem typeSystem) {
    return new RelBuilder(typeSystem, Simplification.all());
  }

  /** Creates a builder that applies only the given simplifications. */
  public static RelBuilder create(
      TypeSystem typeSystem, Set<Simplification> simps) {
    return new RelBuilder(typeSystem, simps);
  }

  /**
   * Rebuilds a tree through a builder, node by node.
   *
   * <p>With {@link Simplification#none()} the result must equal the input: that
   * is the assertion that the builder can express every tree there is, which is
   * what a caller has to be able to assume before it depends on the builder.
   * With other sets it is what those simplifications make of the tree.
   */
  public static Core.Exp rebuild(
      TypeSystem typeSystem, Core.Exp exp, Set<Simplification> simps) {
    final RelBuilder b = create(typeSystem, simps);
    b.rebuild(exp);
    return b.build();
  }

  /** Pushes the rebuilt form of one node, having rebuilt its inputs. */
  private void rebuild(Core.Exp exp) {
    if (!(exp instanceof Core.Rel)) {
      push(exp);
    } else if (exp instanceof Core.Filter) {
      final Core.Filter filter = (Core.Filter) exp;
      rebuild(filter.input);
      filter(filter.condition);
    } else if (exp instanceof Core.Project) {
      final Core.Project project = (Core.Project) exp;
      rebuild(project.input);
      project(project.exp);
    } else if (exp instanceof Core.IfEmpty) {
      final Core.IfEmpty ifEmpty = (Core.IfEmpty) exp;
      rebuild(ifEmpty.input);
      ifEmpty(ifEmpty.exp);
    } else if (exp instanceof Core.Join) {
      final Core.Join join = (Core.Join) exp;
      rebuild(join.left);
      rebuild(join.right);
      join(join.joinType, join.binder, join.condition);
    } else if (exp instanceof Core.Group) {
      final Core.Group group = (Core.Group) exp;
      rebuild(group.input);
      group(group.keys, group.aggregates);
    } else if (exp instanceof Core.Sort) {
      final Core.Sort sort = (Core.Sort) exp;
      rebuild(sort.input);
      sort(sort.exp);
    } else if (exp instanceof Core.Unorder) {
      rebuild(((Core.Unorder) exp).input);
      unorder();
    } else if (exp instanceof Core.Skip) {
      final Core.Skip skip = (Core.Skip) exp;
      rebuild(skip.input);
      skip(skip.count);
    } else if (exp instanceof Core.Take) {
      final Core.Take take = (Core.Take) exp;
      rebuild(take.input);
      take(take.count);
    } else if (exp instanceof Core.SetRel) {
      final Core.SetRel setRel = (Core.SetRel) exp;
      setRel.inputs.forEach(this::rebuild);
      final int n = setRel.inputs.size();
      switch (setRel.op) {
        case UNION:
          union(n, setRel.distinct);
          break;
        case INTERSECT:
          intersect(n, setRel.distinct);
          break;
        default:
          except(n, setRel.distinct);
          break;
      }
    } else {
      throw new AssertionError("cannot rebuild " + exp.op);
    }
  }

  /** Returns whether a simplification is enabled. */
  private boolean on(Simplification simp) {
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
    // A pattern that can fail to match filters as well as binds, and the two
    // halves are separate nodes: the filter here, the binding in the names.
    final Core.@Nullable Exp test = test(typeSystem, pat, element);
    final Core.Exp rel2 = test == null ? rel : core.filter(rel, test);
    stack.push(new Frame(rel2, elementNames(rel2, names)));
    return this;
  }

  /**
   * Returns whether {@link #push(Core.Pat, Core.Exp)} accepts a pattern: it
   * binds names, and where it also filters the filter has a total expression.
   */
  public static boolean pushable(Core.Pat pat) {
    return destructurable(pat) || testable(pat);
  }

  /** Returns whether a pattern binds names without also filtering. */
  public static boolean destructurable(Core.Pat pat) {
    switch (pat.op) {
      case ID_PAT:
      case WILDCARD_PAT:
        return true;
      case AS_PAT:
        return destructurable(((Core.AsPat) pat).pat);
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

  /**
   * Returns whether {@link #test} can express a pattern's condition, and {@link
   * #destructure} its bindings, as expressions over the element.
   */
  public static boolean testable(Core.Pat pat) {
    switch (pat.op) {
      case AS_PAT:
        // The name matches whatever the pattern it wraps matches.
        return testable(((Core.AsPat) pat).pat);
      case ID_PAT:
      case WILDCARD_PAT:
      case BOOL_LITERAL_PAT:
      case CHAR_LITERAL_PAT:
      case INT_LITERAL_PAT:
      case REAL_LITERAL_PAT:
      case STRING_LITERAL_PAT:
      case WORD_LITERAL_PAT:
        return true;
      case TUPLE_PAT:
        return ((Core.TuplePat) pat)
            .args.stream().allMatch(RelBuilder::testable);
      case RECORD_PAT:
        return ((Core.RecordPat) pat)
            .args.stream().allMatch(RelBuilder::testable);
      case CONS_PAT:
        // `::` is a constructor, but the list datatype has total accessors --
        // `null`, `hd`, `tl` -- where a user datatype has none.
        return ((Core.ConPat) pat).pat.op == Op.TUPLE_PAT
            && testable(((Core.ConPat) pat).pat);
      case LIST_PAT:
        return ((Core.ListPat) pat)
            .args.stream().allMatch(RelBuilder::testable);
      default:
        return false;
    }
  }

  /**
   * Returns the condition under which a pattern matches an element, or null if
   * it always matches.
   *
   * <p>A pattern that can fail to match filters as well as binds, and the two
   * halves separate: this is the filter, and {@link #destructure} is the
   * binding. A scan is then a leaf with a filter above it and, where the
   * bindings do not describe the element, a projection above that -- three
   * ordinary nodes, each of which a rule can see through, rather than one node
   * holding a {@code case} that yields a collection.
   *
   * <p>Callers must first ask {@link #testable}. A constructor pattern is not
   * testable here, because extracting what it binds needs a {@code case} of its
   * own: a datatype has no total accessor for a constructor's argument, and no
   * value to give the branch that does not match.
   */
  public static Core.@Nullable Exp test(
      TypeSystem typeSystem, Core.Pat pat, Core.Exp element) {
    switch (pat.op) {
      case ID_PAT:
      case WILDCARD_PAT:
        return null;

      case AS_PAT:
        return test(typeSystem, ((Core.AsPat) pat).pat, element);

      case BOOL_LITERAL_PAT:
      case CHAR_LITERAL_PAT:
      case INT_LITERAL_PAT:
      case REAL_LITERAL_PAT:
      case STRING_LITERAL_PAT:
      case WORD_LITERAL_PAT:
        final Core.LiteralPat literalPat = (Core.LiteralPat) pat;
        return core.equal(
            typeSystem,
            element,
            core.literal((PrimitiveType) pat.type, literalPat.value));

      case TUPLE_PAT:
      case RECORD_PAT:
        final List<Core.Pat> args =
            pat.op == Op.TUPLE_PAT
                ? ((Core.TuplePat) pat).args
                : ((Core.RecordPat) pat).args;
        final List<Core.Exp> tests = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
          final Core.@Nullable Exp test =
              test(typeSystem, args.get(i), core.field(typeSystem, element, i));
          if (test != null) {
            tests.add(test);
          }
        }
        return tests.isEmpty() ? null : core.andAlso(typeSystem, tests);

      case CONS_PAT:
        // A non-empty list, whose head and tail must match in turn.
        final Core.TuplePat consPat = (Core.TuplePat) ((Core.ConPat) pat).pat;
        final List<Core.Exp> consTests = new ArrayList<>();
        consTests.add(core.not(typeSystem, isNull(typeSystem, element)));
        addTest(
            typeSystem,
            consTests,
            consPat.args.get(0),
            hd(typeSystem, element));
        addTest(
            typeSystem,
            consTests,
            consPat.args.get(1),
            tl(typeSystem, element));
        return core.andAlso(typeSystem, consTests);

      case LIST_PAT:
        // A list of exactly this length, whose items must match in turn.
        final List<Core.Pat> listItems = ((Core.ListPat) pat).args;
        if (listItems.isEmpty()) {
          return isNull(typeSystem, element);
        }
        final List<Core.Exp> listTests = new ArrayList<>();
        listTests.add(
            core.equal(
                typeSystem,
                length(typeSystem, element),
                core.literal(PrimitiveType.INT, listItems.size())));
        for (int i = 0; i < listItems.size(); i++) {
          addTest(
              typeSystem,
              listTests,
              listItems.get(i),
              nth(typeSystem, element, i));
        }
        return core.andAlso(typeSystem, listTests);

      default:
        throw new AssertionError("not testable: " + pat);
    }
  }

  /** Adds a pattern's test to a list, if it has one. */
  private static void addTest(
      TypeSystem typeSystem,
      List<Core.Exp> tests,
      Core.Pat pat,
      Core.Exp element) {
    final Core.@Nullable Exp test = test(typeSystem, pat, element);
    if (test != null) {
      tests.add(test);
    }
  }

  /** Applies a one-argument list built-in to a list. */
  private static Core.Exp listCall(
      TypeSystem typeSystem, BuiltIn builtIn, Core.Exp list, Type resultType) {
    return core.apply(
        Pos.ZERO,
        resultType,
        core.functionLiteral(typeSystem.fnType(list.type, resultType), builtIn),
        list);
  }

  public static Core.Exp isNull(TypeSystem typeSystem, Core.Exp list) {
    return listCall(typeSystem, BuiltIn.LIST_NULL, list, PrimitiveType.BOOL);
  }

  public static Core.Exp length(TypeSystem typeSystem, Core.Exp list) {
    return listCall(typeSystem, BuiltIn.LIST_LENGTH, list, PrimitiveType.INT);
  }

  public static Core.Exp hd(TypeSystem typeSystem, Core.Exp list) {
    return listCall(typeSystem, BuiltIn.LIST_HD, list, list.type.elementType());
  }

  public static Core.Exp tl(TypeSystem typeSystem, Core.Exp list) {
    return listCall(typeSystem, BuiltIn.LIST_TL, list, list.type);
  }

  public static Core.Exp nth(TypeSystem typeSystem, Core.Exp list, int i) {
    final Type elementType = list.type.elementType();
    final Type fnType =
        typeSystem.fnType(
            typeSystem.tupleType(list.type, PrimitiveType.INT), elementType);
    return core.apply(
        Pos.ZERO,
        elementType,
        core.functionLiteral(fnType, BuiltIn.LIST_NTH),
        core.tuple(
            typeSystem,
            null,
            ImmutableList.of(list, core.literal(PrimitiveType.INT, i))));
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
      case AS_PAT:
        // `p as (a, b)` names the whole value and its parts, and in a tree
        // both are paths to the same element -- so, unlike a pattern that
        // reaches Core as a value to be taken apart, there is nothing here
        // that makes the two dependent.
        final Core.AsPat asPat = (Core.AsPat) pat;
        names.put(asPat.name, element);
        return destructure(asPat.pat, element, names);
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

      case BOOL_LITERAL_PAT:
      case CHAR_LITERAL_PAT:
      case INT_LITERAL_PAT:
      case REAL_LITERAL_PAT:
      case STRING_LITERAL_PAT:
      case WORD_LITERAL_PAT:
        // A literal binds nothing; it filters, and `test` says how.
        return true;

      case CONS_PAT:
        // `h :: t` binds the head and the tail, which `hd` and `tl` reach.
        final Core.TuplePat headTail = (Core.TuplePat) ((Core.ConPat) pat).pat;
        return destructure(headTail.args.get(0), hd(typeSystem, element), names)
            && destructure(
                headTail.args.get(1), tl(typeSystem, element), names);

      case LIST_PAT:
        final List<Core.Pat> items = ((Core.ListPat) pat).args;
        for (int i = 0; i < items.size(); i++) {
          if (!destructure(items.get(i), nth(typeSystem, element, i), names)) {
            return false;
          }
        }
        return true;

      default:
        // A user datatype's constructor also filters, but extracting what it
        // binds has no total expression -- see `test`.
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
    if (on(Simplification.FILTER_TRUE) && condition.isBoolLiteral(true)) {
      return push(frame);
    }
    if (on(Simplification.FILTER_MERGE) && frame.rel instanceof Core.Filter) {
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
    if (on(Simplification.PROJECT_IDENTITY) && isInput0(exp)) {
      return push(frame);
    }
    if (on(Simplification.PROJECT_MERGE) && frame.rel instanceof Core.Project) {
      final Core.Project project = (Core.Project) frame.rel;
      return project(project.input, merge(exp, project.exp));
    }
    return project(frame.rel, exp);
  }

  private RelBuilder project(Core.Exp input, Core.Exp exp) {
    return push(core.project(typeSystem, input, exp));
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
    if (on(Simplification.UNORDER_UNORDERED) && !isOrdered(frame.rel)) {
      return push(frame);
    }
    return push(frame.withRel(core.unorder(typeSystem, frame.rel)));
  }

  /** Skips rows of the top of the stack. */
  public RelBuilder skip(Core.Exp count) {
    final Frame frame = pop();
    if (on(Simplification.SKIP_ZERO) && isIntLiteral(count, 0)) {
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
    final Core.Exp rel = core.group(typeSystem, frame.rel, keys, aggregates);
    // A group's element is a record of its labels, whether there is one label
    // or many, so the names come off its fields like any other node's.
    return push(rel);
  }

  /**
   * Projects the top of the stack, naming the element.
   *
   * <p>An atomizing yield -- {@code yield e.deptno} -- makes the element a bare
   * value, which has no fields to take names from, but the query still refers
   * to it by the name the yield gave it.
   */
  public RelBuilder project(String name, Core.Exp exp) {
    project(exp);
    final Frame frame = pop();
    return push(
        new Frame(
            frame.rel,
            elementNames(
                frame.rel,
                ImmutableMap.of(
                    name, core.input0(frame.rel.type.elementType())))));
  }

  /**
   * Joins the top two of the stack, the deeper being the left. The condition
   * and the yield are over {@code $0} and {@code $1}.
   */
  public RelBuilder join(Core.Rel.JoinType joinType, Core.Exp condition) {
    return join(joinType, null, condition);
  }

  /**
   * Joins the top two of the stack, with a binder that names the left element
   * inside the right input.
   *
   * <p>This is what a scan whose collection reads an earlier binder becomes.
   * The right input is a tree of its own, so it cannot say {@code $0} and mean
   * the left element; the binder crosses that boundary by ordinary lexical
   * scoping. Pass null where the right input reads nothing of the left.
   */
  public RelBuilder join(
      Core.Rel.JoinType joinType,
      Core.@Nullable IdPat binder,
      Core.Exp condition) {
    final Frame right = pop();
    final Frame left = pop();
    arity = 1;
    if (on(Simplification.JOIN_INDEPENDENT)
        && binder != null
        && !references(right.rel, binder)) {
      // An independent join is far preferable to a dependent one -- it can be
      // commuted, reassociated, and executed by something other than a nested
      // loop -- and a binder nothing reads is what makes the difference
      // between them, so it goes. This is the decorrelation rule, applied
      // where the tree is built rather than waiting for a pass to notice: a
      // caller can offer a binder without first knowing whether the right
      // input will use it.
      binder = null;
    }
    final Core.Exp rel =
        core.join(typeSystem, joinType, binder, left.rel, right.rel, condition);
    return push(new Frame(rel, joinNames(rel, left, right)));
  }

  /**
   * Returns the names of a join, by rebasing its inputs' onto the element it
   * concatenates them into.
   *
   * <p>This is what lets a caller keep saying {@code e} after two joins: the
   * element of {@code join(join(emps, depts), salgrades)} is three components,
   * and the map says which of them each name is. Without it the names would be
   * the element's field names, which for a tuple are {@code 1}, {@code 2} and
   * {@code 3} -- true, and useless to anything that started from a query.
   */
  private ImmutableMap<String, Core.Exp> joinNames(
      Core.Exp rel, Frame left, Frame right) {
    final Core.Exp element = core.input0(rel.type.elementType());
    final Map<String, Core.Exp> names = new LinkedHashMap<>();
    rebaseInto(names, left, 0, element);
    rebaseInto(names, right, core.componentCount(left.rel), element);
    return ImmutableMap.copyOf(names);
  }

  /**
   * Rebases one input's names onto the join's element: an input with one
   * component is the whole of a position, and one that is itself a flattened
   * join has several, whose indexes shift by the offset.
   */
  private void rebaseInto(
      Map<String, Core.Exp> names, Frame frame, int offset, Core.Exp element) {
    final int n = core.componentCount(frame.rel);
    frame.names.forEach(
        (name, a) ->
            names.put(
                name,
                n == 1
                    ? substitute(a, core.field(typeSystem, element, offset))
                    : shift(a, offset, element)));
  }

  /** Rewrites {@code #j $0} to {@code #(offset + j) $0}. */
  private Core.Exp shift(Core.Exp exp, int offset, Core.Exp element) {
    return exp.accept(
        new Shuttle(typeSystem) {
          @Override
          protected Core.Exp visit(Core.Apply apply) {
            if (apply.fn instanceof Core.RecordSelector
                && isInput0(apply.arg)) {
              return core.field(
                  typeSystem,
                  element,
                  offset + ((Core.RecordSelector) apply.fn).slot);
            }
            return super.visit(apply);
          }
        });
  }

  /**
   * Returns whether an expression has a free occurrence of a binder.
   *
   * <p>The walk does not stop at a nested node: the binder is an ordinary name,
   * and a nested tree rebinds {@code $0} but does not shield a name.
   */
  private static boolean references(Core.Exp exp, Core.IdPat binder) {
    final boolean[] found = {false};
    exp.accept(
        new Visitor() {
          @Override
          protected void visit(Core.Id id) {
            if (id.idPat.equals(binder)) {
              found[0] = true;
            }
          }
        });
    return found[0];
  }

  /**
   * Returns a binder that names the top input's element, for the right input of
   * a dependent join. The right input is built after this, and reads the binder
   * where it needs the left element.
   */
  public Core.IdPat binder(String name) {
    return core.idPat(frame(0).rel.type.elementType(), name, 0);
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

  /**
   * Substitutes the inner projection's expression into the outer's, binding it
   * to a variable first if the outer reads it more than once.
   *
   * <p>Merging two projections is substitution, and substitution duplicates:
   * {@code project [$0 + $0]} over {@code project [f $0]} would call {@code f}
   * twice per row where the two nodes called it once. A {@code let} keeps the
   * one evaluation that the two nodes had, so the merge is a simplification of
   * the plan and never a pessimization of it.
   */
  private Core.Exp merge(Core.Exp outer, Core.Exp inner) {
    if (count(outer) <= 1) {
      return substitute(outer, inner);
    }
    final Core.IdPat pat = core.idPat(inner.type, "v$" + nextName++, 0);
    return core.let(
        core.nonRecValDecl(inner.pos, pat, null, inner),
        substitute(outer, core.id(pat)));
  }

  /** Returns how many times an expression reads {@code $0}. */
  private static int count(Core.Exp exp) {
    final int[] n = {0};
    exp.accept(
        new Visitor() {
          @Override
          protected void visit(Core.Id id) {
            if (isInput0(id)) {
              ++n[0];
            }
          }
        });
    return n[0];
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
