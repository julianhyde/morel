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
import static java.util.Objects.requireNonNull;
import static net.hydromatic.morel.ast.CoreBuilder.core;
import static net.hydromatic.morel.util.Pair.forEach;
import static net.hydromatic.morel.util.Static.last;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.FromBuilder;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.ast.Shuttle;
import net.hydromatic.morel.ast.Visitor;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.FnType;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.RecordLikeType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.PairList;
import org.jspecify.annotations.Nullable;

/**
 * Lowers a relational tree ({@link Core.Rel}) into the environment-passing form
 * that executes: a {@link Core.From} whose steps carry bindings, which {@link
 * Compiler} turns into {@code RowSink} code.
 *
 * <p>This is the reverse of {@link RelTranslator}: the step list survives as an
 * unprinted lowering artifact. Where the translation eliminates variables, the
 * lowering reintroduces them.
 *
 * <p>It linearizes. The tree is left-deep after translation, so one step list
 * carries the whole left spine rather than each node nesting a {@code from} of
 * its own. What makes that work is carrying a node's element as an
 * <em>expression</em> over the step list's bindings instead of materializing
 * it: a projection then changes the expression, not the steps, and {@code from
 * e in emps, d in depts where p} lowers back to the three steps it began as.
 *
 * <p>The element is materialized, by a {@code yield}, only where something
 * needs the row itself: before a set operator, before an outer join (which
 * wraps whole bindings in {@code option}), and at the end.
 */
public class RelLowerer {
  private final TypeSystem typeSystem;

  /**
   * Name for each leaf's binder, in the order the leaves are lowered; empty
   * once exhausted, and then a name is generated.
   *
   * <p>A tree has no names, so the lowering invents them. Where a caller knows
   * the name the user wrote, saying so keeps it in the plan, which is what the
   * reader of a plan wants to see.
   */
  private final Deque<List<String>> scanNames;

  /**
   * Whether a collection may be scanned under a record pattern of the binders
   * its own last step leaves.
   *
   * <p>True for grounding, whose collections it generated itself: scanning one
   * under a single name leaves the builder unable to inline it -- it will not
   * inline a collection that yields a record -- so the plan gains a scan and
   * reads the fields back out of it, where the step list inlines and keeps the
   * names.
   *
   * <p>False for the resolver, whose collections are the user's subqueries: a
   * scan over one binds the name the user wrote, and binding the subquery's own
   * names instead loses it.
   */
  private final boolean scanOwnBinders;

  /**
   * The name of the leaf that each collection bounds, by identity; empty except
   * for grounding, which knows what each collection it built is for.
   *
   * <p>Consulted before {@link #scanNames}, which is positional and therefore
   * only right where nothing was reordered.
   */
  private final Map<Core.Exp, String> leafNames;

  /**
   * The names already given to a scan. A step list may not have two scans whose
   * names share a base -- the environment keys on it -- and a name can now come
   * from either of two places, so whichever asks first gets it.
   */
  private final Set<String> usedNames = new HashSet<>();

  /**
   * Where binder ordinals come from.
   *
   * <p>The caller's, and not the type system's, because a name is unique only
   * within one generator: the resolver numbers from the session's, and a
   * lowering that numbered from another could mint a binder equal to one the
   * resolver had already minted. `fun isNum n = ...; from n where isNum n` then
   * inlines `n` to itself, and the inliner does not stop.
   */
  private final NameGenerator nameGenerator;

  private RelLowerer(
      TypeSystem typeSystem,
      NameGenerator nameGenerator,
      Iterable<List<String>> scanNames,
      boolean scanOwnBinders,
      Map<Core.Exp, String> leafNames) {
    this.typeSystem = typeSystem;
    this.nameGenerator = nameGenerator;
    this.scanNames = new ArrayDeque<>(ImmutableList.copyOf(scanNames));
    this.scanOwnBinders = scanOwnBinders;
    this.leafNames = leafNames;
  }

  /** Lowers a tree into an executable expression. */
  public static Core.Exp lower(TypeSystem typeSystem, Core.Exp exp) {
    return lower(typeSystem, typeSystem.nameGenerator, exp, ImmutableList.of());
  }

  /**
   * Lowers a tree into an executable expression, naming the binder of each leaf
   * scan, in order, from {@code scanNames}.
   */
  public static Core.Exp lower(
      TypeSystem typeSystem,
      NameGenerator nameGenerator,
      Core.Exp exp,
      Iterable<List<String>> scanNames) {
    return lower(typeSystem, nameGenerator, exp, scanNames, false);
  }

  /**
   * Lowers a tree, naming each leaf scan's binder from {@code scanNames} and,
   * if {@code scanOwnBinders}, scanning a collection under the binders its own
   * last step leaves.
   */
  public static Core.Exp lower(
      TypeSystem typeSystem,
      NameGenerator nameGenerator,
      Core.Exp exp,
      Iterable<List<String>> scanNames,
      boolean scanOwnBinders) {
    return lower(
        typeSystem,
        nameGenerator,
        exp,
        scanNames,
        scanOwnBinders,
        ImmutableMap.of());
  }

  /**
   * As {@link #lower(TypeSystem, NameGenerator, Core.Exp, Iterable, boolean)},
   * and names a scan whose collection is in {@code leafNames} after the leaf
   * that collection bounds, rather than from {@code scanNames}.
   */
  public static Core.Exp lower(
      TypeSystem typeSystem,
      NameGenerator nameGenerator,
      Core.Exp exp,
      Iterable<List<String>> scanNames,
      boolean scanOwnBinders,
      Map<Core.Exp, String> leafNames) {
    return new RelLowerer(
            typeSystem, nameGenerator, scanNames, scanOwnBinders, leafNames)
        .lowerRel(exp);
  }

  /**
   * Lowers every tree in an expression, including the trees nested in the
   * expressions of a tree.
   *
   * <p>{@link #lower} stops at a nested node, because a tree's own {@code $0}
   * is not the enclosing node's; this walks what comes back, so that a nested
   * tree is lowered as a root of its own.
   */
  public static Core.Exp lowerAll(
      TypeSystem typeSystem, NameGenerator nameGenerator, Core.Exp exp) {
    return exp.accept(lowerAllShuttle(typeSystem, nameGenerator));
  }

  /**
   * As {@link #lowerAll(TypeSystem, NameGenerator, Core.Exp)}, for a
   * declaration.
   */
  public static Core.Decl lowerAll(
      TypeSystem typeSystem, NameGenerator nameGenerator, Core.Decl decl) {
    return decl.accept(lowerAllShuttle(typeSystem, nameGenerator));
  }

  private static Shuttle lowerAllShuttle(
      TypeSystem typeSystem, NameGenerator nameGenerator) {
    return new Shuttle(typeSystem) {
      @Override
      protected Core.@Nullable Exp visitRel(Core.Rel rel) {
        final Set<Core.NamedPat> rowPats = rowBindings(rel);
        final Core.Exp lowered =
            lower(typeSystem, nameGenerator, rel, scanNames(rel));
        return unbindRow(typeSystem, lowered, rowPats).accept(this);
      }
    };
  }

  /**
   * Returns the name for each of a tree's leaf scans, as the query wrote them.
   *
   * <p>The resolver used to hand these to the lowering; a tree does not carry
   * them, but its projection names its element's components after the binders
   * they came from, and {@link RelExpander#leafPats} reads them back.
   */
  private static Iterable<List<String>> scanNames(Core.Rel rel) {
    final List<Core.Pat> pats = RelExpander.leafPats(rel);
    final ImmutableList.Builder<List<String>> names = ImmutableList.builder();
    for (Core.Pat pat : pats) {
      if (!(pat instanceof Core.IdPat)) {
        return ImmutableList.of();
      }
      names.add(ImmutableList.of(((Core.IdPat) pat).name));
    }
    return names.build();
  }

  /**
   * Returns the binders that hold this node's element for a tree nested in one
   * of its expressions.
   *
   * <p>The resolver binds the element where a nested tree reads it, because a
   * tree's {@code $0} is its own and not the enclosing node's.
   */
  static Set<Core.NamedPat> rowBindings(Core.Exp exp) {
    final Set<Core.NamedPat> pats = new LinkedHashSet<>();
    exp.accept(
        new Visitor() {
          @Override
          protected void visit(Core.NonRecValDecl valDecl) {
            super.visit(valDecl);
            if (valDecl.pat instanceof Core.IdPat
                && containsInput(valDecl.exp)) {
              pats.add(valDecl.pat);
            }
          }
        });
    return pats;
  }

  /**
   * Replaces each of {@code pats} with the value it is bound to, and drops the
   * binding.
   *
   * <p>Once the node is lowered its element is an ordinary variable, and the
   * binding has nothing left to protect: what reads it is no longer inside a
   * tree that rebinds {@code $0}. Dropping it keeps the plan the shape it had
   * before the resolver stopped lowering, and it is what the step list's
   * grounding engine expects to see -- a {@code let} between a query and its
   * constraint is opaque to it.
   *
   * <p>A binding whose value <i>still</i> holds a {@code $0} is a different
   * thing, and must be left alone: it belongs to a tree that has not been
   * lowered yet, nested in this one's expressions. Substituting it would put
   * that {@code $0} under a node that rebinds it, so a reference to the
   * enclosing row would start reading the inner one -- `c = b` silently becomes
   * `c = d`. It is unbound when its own node is lowered, one turn of {@code
   * lowerAll}'s recursion later.
   */
  static Core.Exp unbindRow(
      TypeSystem typeSystem, Core.Exp exp, Set<Core.NamedPat> pats) {
    if (pats.isEmpty()) {
      return exp;
    }
    return exp.accept(
        new Shuttle(typeSystem) {
          final Map<Core.NamedPat, Core.Exp> values = new HashMap<>();

          @Override
          protected Core.Exp visit(Core.Let let) {
            if (let.decl instanceof Core.NonRecValDecl) {
              final Core.NonRecValDecl decl = (Core.NonRecValDecl) let.decl;
              if (pats.contains(decl.pat) && !containsInput(decl.exp)) {
                values.put(decl.pat, decl.exp.accept(this));
                return let.exp.accept(this);
              }
            }
            return super.visit(let);
          }

          @Override
          protected Core.Exp visit(Core.Id id) {
            final Core.@Nullable Exp value = values.get(id.idPat);
            return value == null ? id : core.at(value, id.pos);
          }
        });
  }

  /** Returns whether an expression reads an input of a relational tree. */
  static boolean containsInput(Core.Exp exp) {
    final boolean[] found = {false};
    exp.accept(
        new Visitor() {
          @Override
          protected void visit(Core.Input input) {
            found[0] = true;
          }
        });
    return found[0];
  }

  private Core.Exp lowerRel(Core.Exp exp) {
    if (isUnitCollection(exp)) {
      // A query that is nothing but the one unit row is a `from` with no
      // steps, which is what the step list means by it and what Calcite reads
      // as a values of one empty row.
      return core.fromBuilder(typeSystem).build();
    }
    if (!(exp instanceof Core.Rel)) {
      // A leaf is already an expression.
      return exp;
    }
    final FromBuilder fromBuilder = core.fromBuilder(typeSystem);
    materialize(fromBuilder, lowerInto(fromBuilder, exp));
    return fromBuilder.build();
  }

  /**
   * Appends the steps for a node, and returns an expression, over the step
   * list's bindings, that denotes the node's element.
   */
  private Core.Exp lowerInto(FromBuilder fromBuilder, Core.Exp exp) {
    if (!(exp instanceof Core.Rel)) {
      return scan(fromBuilder, exp);
    }
    if (exp instanceof Core.Filter) {
      final Core.Filter filter = (Core.Filter) exp;
      final Core.Exp element = lowerInto(fromBuilder, filter.input);
      fromBuilder.where(subst(filter.condition, element, null));
      return element;
    }
    if (exp instanceof Core.Project) {
      // A projection changes the element, not the steps; nothing is emitted
      // unless a later step needs the row.
      final Core.Project project = (Core.Project) exp;
      final Core.Exp element = lowerInto(fromBuilder, project.input);
      final Core.Exp element2 = subst(project.exp, element, null);
      if (containsOrdinal(element2)) {
        // Except for an ordinal, which counts rows: only a step evaluates its
        // expression exactly once per row, so deferring one would change what
        // it counts.
        return materialize(fromBuilder, element2);
      }
      return element2;
    }
    if (exp instanceof Core.IfEmpty) {
      // Needs the collection as a value, so it becomes an expression, which
      // is then scanned.
      return scan(fromBuilder, lowerIfEmpty((Core.IfEmpty) exp));
    }
    if (exp instanceof Core.Join) {
      return lowerJoin(fromBuilder, (Core.Join) exp);
    }
    if (exp instanceof Core.Group) {
      return lowerGroup(fromBuilder, (Core.Group) exp);
    }
    if (exp instanceof Core.Sort) {
      // A sort reads the element, so a projection above it must be paid for
      // here rather than deferred: deferring it would sort a wider row, and
      // evaluate the projection twice for every expression the sort key
      // shares with it.
      final Core.Sort sort = (Core.Sort) exp;
      final Core.Exp element =
          materialize(fromBuilder, lowerInto(fromBuilder, sort.input));
      fromBuilder.order(subst(sort.exp, element, null));
      return element;
    }
    if (exp instanceof Core.Unorder) {
      final Core.Exp element =
          lowerInto(fromBuilder, ((Core.Unorder) exp).input);
      fromBuilder.unorder();
      return element;
    }
    if (exp instanceof Core.Skip) {
      final Core.Skip skip = (Core.Skip) exp;
      final Core.Exp element = lowerInto(fromBuilder, skip.input);
      fromBuilder.skip(lowerRel(skip.count));
      return element;
    }
    if (exp instanceof Core.Take) {
      final Core.Take take = (Core.Take) exp;
      final Core.Exp element = lowerInto(fromBuilder, take.input);
      fromBuilder.take(lowerRel(take.count));
      return element;
    }
    if (exp instanceof Core.SetRel) {
      return lowerSetRel(fromBuilder, (Core.SetRel) exp);
    }
    throw new AssertionError("cannot lower " + exp.op);
  }

  private Core.Exp lowerGroup(FromBuilder fromBuilder, Core.Group group) {
    final Core.Exp element = lowerInto(fromBuilder, group.input);
    final SortedMap<Core.IdPat, Core.Exp> groupExps =
        new TreeMap<>(Core.NamedPat.ORDERING);
    group.keys.forEach(
        (label, keyExp) -> {
          final Core.Exp e = subst(keyExp, element, null);
          groupExps.put(core.idPat(e.type, label, 0), e);
        });
    final SortedMap<Core.IdPat, Core.Aggregate> aggregates =
        new TreeMap<>(Core.NamedPat.ORDERING);
    group.aggregates.forEach(
        (label, aggregate) ->
            aggregates.put(
                core.idPat(aggregate.type, label, 0),
                aggregate.copy(
                    aggregate.type,
                    subst(aggregate.aggregate, element, null),
                    aggregate.argument == null
                        ? null
                        : subst(aggregate.argument, element, null))));
    // Never an atom: the tree's group builds a record whether it has one
    // label or many, so the step list must carry the same
    // record, or the lowered form has a different type from the tree.
    fromBuilder.group(false, groupExps, aggregates);
    return naturalElement(fromBuilder);
  }

  private Core.Exp lowerSetRel(FromBuilder fromBuilder, Core.SetRel setRel) {
    // A set operator combines rows, so the element has to be the row.
    materialize(fromBuilder, lowerInto(fromBuilder, setRel.inputs.get(0)));
    final List<Core.Exp> args = new ArrayList<>();
    setRel
        .inputs
        .subList(1, setRel.inputs.size())
        .forEach(input -> args.add(lowerRel(input)));
    switch (setRel.op) {
      case UNION:
        fromBuilder.union(setRel.distinct, args);
        break;
      case INTERSECT:
        fromBuilder.intersect(setRel.distinct, args);
        break;
      default:
        fromBuilder.except(setRel.distinct, args);
        break;
    }
    return naturalElement(fromBuilder);
  }

  /**
   * Lowers a join. The condition sees both elements as they are; the yield sees
   * an option on a side that an outer join can leave absent, which is what the
   * bindings hold after the scan.
   */
  private Core.Exp lowerJoin(FromBuilder fromBuilder, Core.Join join) {
    Core.Exp left = lowerInto(fromBuilder, join.left);
    if (join.joinType != Core.Rel.JoinType.INNER
        && fromBuilder.stepEnv().bindings.size() <= 1) {
      // An outer join wraps a binding in 'option', so a left element of one
      // binding must be that binding and not an expression over it. Several
      // stay several: the scan wraps each of them, which is what gives one
      // option per component, and collapsing them into
      // one binding first would give one option over the lot.
      left = materialize(fromBuilder, left);
    }
    if (inner(join) && join.right instanceof Core.Join && inner(join.right)) {
      // Two inner joins nest to the right, but a step list has no nesting: it
      // has a scan per leaf. Lowering the right input into this same builder
      // gives that -- one scan each -- where scanning it as a subquery would
      // give a scan of a collection that the plan then reads back apart.
      Core.Rel right = (Core.Join) join.right;
      if (join.binder != null) {
        right = (Core.Rel) rename(right, join.binder, left);
      }
      final Core.Exp rightElement = lowerInto(fromBuilder, right);
      fromBuilder.where(subst(join.condition, left, rightElement));
      return element(join, left, rightElement);
    }
    // A leaf right input is scanned here rather than by `scan`, so this is
    // where its name is due; a right input that is a tree scans its own
    // leaves, and taking a name here would take the one they are owed.
    final Core.IdPat w =
        join.right instanceof Core.Rel
            ? freshPat(join.right.type.elementType())
            : scanPat(join.right);
    final Core.Exp condition = subst(join.condition, left, core.id(w));
    // A dependent join's right input reads the left element through the
    // binder. The step list has the left bindings in scope at the scan, so
    // the binder becomes the expression that denotes the left element -- the
    // step-list way of saying the same thing.
    Core.Exp right = lowerRel(join.right);
    if (join.binder != null) {
      right = rename(right, join.binder, left);
    }
    fromBuilder.scan(op(join.joinType), w, right, condition);
    // The element is the inputs' components in order. For
    // an outer join the scan has re-typed the bindings it can leave absent, so
    // the components are read off those, not the pattern variables.
    final boolean inner = inner(join);
    final Core.Exp leftElement = inner ? left : rebind(fromBuilder, left);
    final Core.Exp rightElement =
        inner ? core.id(w) : rebind(fromBuilder, core.id(w));
    return element(join, leftElement, rightElement);
  }

  /** Returns whether a join is an inner join. */
  private static boolean inner(Core.Exp join) {
    return ((Core.Join) join).joinType == Core.Rel.JoinType.INNER;
  }

  /** Returns a join's element: its inputs' components in order. */
  private Core.Exp element(
      Core.Join join, Core.Exp leftElement, Core.Exp rightElement) {
    final List<Core.Exp> exps =
        new ArrayList<>(core.components(typeSystem, join.left, leftElement));
    exps.addAll(core.components(typeSystem, join.right, rightElement));
    return core.tuple(typeSystem, null, exps);
  }

  /**
   * Lowers an {@code ifEmpty} to a conditional expression; the step list has no
   * such step.
   */
  private Core.Exp lowerIfEmpty(Core.IfEmpty ifEmpty) {
    final Core.Exp input = lowerRel(ifEmpty.input);
    final Core.Exp nonEmpty =
        core.apply(
            Pos.ZERO,
            PrimitiveType.BOOL,
            core.functionLiteral(typeSystem, BuiltIn.RELATIONAL_NON_EMPTY),
            input);
    final Core.Exp singleton =
        core.list(typeSystem, ifEmpty.exp.type, ImmutableList.of(ifEmpty.exp));
    return core.ifThenElse(nonEmpty, input, singleton);
  }

  /**
   * Scans a collection, binding its element to a fresh variable, and returns
   * the expression that denotes the element.
   */
  private Core.Exp scan(FromBuilder fromBuilder, Core.Exp collection) {
    if (isUnitCollection(collection)) {
      // The inverse of the translator's `unitCollection`: a query with no scan
      // iterates over one row, which is unit, and the tree says so with a leaf
      // holding that one row. A step list says it by having no scan, and
      // downstream reads that -- Calcite turns an empty `from` into a values
      // of one empty row, and a scan of `[()]` into a project over one.
      return core.unitLiteral();
    }
    if (scanOwnBinders) {
      final List<Core.NamedPat> own = bindersOf(collection);
      if (own.size() > 1) {
        fromBuilder.scan(core.recordOrAtomPat(typeSystem, own), collection);
        return naturalElement(fromBuilder);
      }
    }
    if (scanNames.size() > 0 && requireNonNull(scanNames.peek()).size() > 1) {
      // The queue has a name per component, because the query's pattern named
      // each -- `from (b, i)`. Scanning under a pattern of them keeps those
      // names, where scanning under one name and reading the fields out would
      // lose them, and grounding quotes them in what it says about a leaf it
      // cannot bound.
      final List<String> names = requireNonNull(scanNames.remove());
      final List<Type> argTypes =
          ((RecordLikeType) collection.type.elementType()).argTypes();
      checkArgument(
          names.size() == argTypes.size(),
          "a name per component, but %s names and %s components",
          names.size(),
          argTypes.size());
      final List<Core.NamedPat> pats = new ArrayList<>();
      forEach(
          names,
          argTypes,
          (name, argType) ->
              pats.add(core.idPat(argType, name, nameGenerator::inc)));
      // A tuple pattern, not `recordOrAtomPat`: the names are the query's and
      // the element is a tuple of the values it generates, so the pattern
      // matches by position.
      fromBuilder.scan(core.tuplePat(typeSystem, pats), collection);
      return naturalElement(fromBuilder);
    }
    final Core.IdPat v = scanPat(collection);
    fromBuilder.scan(v, collection);
    return rebind(fromBuilder, core.id(v));
  }

  /**
   * Returns the binders a collection's own last step leaves, or empty if it is
   * not a step list.
   */
  private static List<Core.NamedPat> bindersOf(Core.Exp collection) {
    if (!(collection instanceof Core.From)) {
      return ImmutableList.of();
    }
    final List<Core.FromStep> steps = ((Core.From) collection).steps;
    if (steps.isEmpty()) {
      return ImmutableList.of();
    }
    final List<Core.NamedPat> pats = new ArrayList<>();
    last(steps).env.bindings.forEach(binding -> pats.add(binding.id));
    return pats;
  }

  /**
   * Materializes the element as the step list's row, if it is not that already,
   * and returns the expression that denotes it afterwards.
   */
  private Core.Exp materialize(FromBuilder fromBuilder, Core.Exp element) {
    if (isNatural(fromBuilder, element)) {
      return element;
    }
    fromBuilder.yield_(element);
    return naturalElement(fromBuilder);
  }

  /**
   * Returns the expression that the step list's own bindings denote: the one
   * binding's value, or a record of them.
   */
  private Core.Exp naturalElement(FromBuilder fromBuilder) {
    final Core.StepEnv env = fromBuilder.stepEnv();
    if (env.atom) {
      return core.id(env.bindings.get(0).id);
    }
    final PairList<String, Core.Exp> nameExps = PairList.of();
    env.bindings.forEach(b -> nameExps.add(b.id.name, core.id(b.id)));
    return core.record(typeSystem, nameExps);
  }

  /**
   * Returns whether an expression is already what the bindings denote, in which
   * case a {@code yield} of it would be an identity step.
   *
   * <p>With no bindings the natural element is unit, which an element
   * expression is only if the query says so: {@code group {}} followed by a
   * yield still needs the yield.
   */
  private boolean isNatural(FromBuilder fromBuilder, Core.Exp element) {
    return same(naturalElement(fromBuilder), element);
  }

  /**
   * Returns whether two expressions are the same.
   *
   * <p>Only for the shapes {@link #naturalElement} builds -- a reference, or a
   * record of references -- because {@link Core.Exp} has no structural equality
   * of its own.
   */
  private static boolean same(Core.Exp e0, Core.Exp e1) {
    if (e0 instanceof Core.Id) {
      return e1 instanceof Core.Id
          && ((Core.Id) e0).idPat.equals(((Core.Id) e1).idPat);
    }
    if (e0 instanceof Core.Tuple && e1 instanceof Core.Tuple) {
      final Core.Tuple t0 = (Core.Tuple) e0;
      final Core.Tuple t1 = (Core.Tuple) e1;
      if (!t0.type.equals(t1.type) || t0.args.size() != t1.args.size()) {
        return false;
      }
      for (int i = 0; i < t0.args.size(); i++) {
        if (!same(t0.args.get(i), t1.args.get(i))) {
          return false;
        }
      }
      return true;
    }
    return false;
  }

  /**
   * Re-reads a reference against the current bindings, in case a step has
   * re-typed them -- an outer join wraps a binding in {@code option} -- or the
   * builder inlined a scan under a different name.
   */
  private Core.Exp rebind(FromBuilder fromBuilder, Core.Exp exp) {
    if (exp.op == Op.TUPLE) {
      // The components of a side that the scan has re-typed one binding at a
      // time. Rebuilt rather than copied: each component's type has changed,
      // so the tuple's has too, and a copy would keep the old one.
      final List<Core.Exp> args = new ArrayList<>();
      ((Core.Tuple) exp)
          .args.forEach(arg -> args.add(rebind(fromBuilder, arg)));
      return core.tuple(typeSystem, null, args);
    }
    if (exp.op != Op.ID) {
      return exp;
    }
    final String name = ((Core.Id) exp).idPat.name;
    for (Binding binding : fromBuilder.stepEnv().bindings) {
      if (binding.id.name.equals(name)) {
        return core.id(binding.id);
      }
    }
    // The builder inlined the scan and the name is gone. Where it left one
    // binding, that binding is the row: the trailing `yield e` it skipped is
    // exactly what made the subquery's rows scalar, and rebuilding a record
    // of the binding would put back what the yield took away.
    final List<Binding> bindings = fromBuilder.stepEnv().bindings;
    if (bindings.size() == 1) {
      return core.id(bindings.get(0).id);
    }
    return naturalElement(fromBuilder);
  }

  /** Returns whether an expression reads the ordinal of the current row. */
  private static boolean containsOrdinal(Core.Exp exp) {
    final boolean[] found = {false};
    exp.accept(
        new Visitor() {
          @Override
          protected void visit(Core.Apply apply) {
            super.visit(apply);
            if (apply.isCallTo(BuiltIn.Z_ORDINAL)) {
              found[0] = true;
            }
          }
        });
    return found[0];
  }

  private static Op op(Core.Rel.JoinType joinType) {
    switch (joinType) {
      case LEFT:
        return Op.LEFT_JOIN;
      case RIGHT:
        return Op.RIGHT_JOIN;
      case FULL:
        return Op.FULL_JOIN;
      default:
        return Op.SCAN;
    }
  }

  /**
   * Returns whether an expression is the one-row collection of {@code unit}.
   */
  private static boolean isUnitCollection(Core.Exp exp) {
    if (!(exp instanceof Core.Apply)) {
      return false;
    }
    final Core.Apply apply = (Core.Apply) exp;
    return apply.isCallTo(BuiltIn.Z_LIST)
        && exp.type.elementType() == PrimitiveType.UNIT
        && ((Core.Tuple) apply.arg).args.size() == 1;
  }

  /**
   * Returns a binder for a scan: the name its caller asked for, if one is still
   * owed, and otherwise a generated one.
   */
  private Core.IdPat scanPat(Core.Exp collection) {
    final Type type = collection.type.elementType();
    // Grounding knows which leaf this collection bounds, and its name beats
    // the one the queue has: the queue is positional, and grounding assembles
    // the leaves in the order it schedules them, not the order they were
    // written. Take the name out of the queue too, or the next scan is given
    // it a second time.
    final @Nullable String leafName = leafNames.get(collection);
    if (leafName != null && usedNames.add(leafName)) {
      scanNames.remove(ImmutableList.of(leafName));
      final Core.@Nullable IdPat leafOwn = binderOf(collection);
      return leafOwn != null && leafOwn.name.equals(leafName)
          ? leafOwn
          : core.idPat(type, leafName, nameGenerator::inc);
    }
    if (scanNames.isEmpty()) {
      // No name is owed. The collection's own binder is still better than a
      // fresh one, and for the reason below: inventing a second name makes the
      // builder rename what it inlined, so `from ... yield g$0` scanned under
      // `w$0` becomes `yield {w$0 = g$0}`, a one-field record where the
      // element was an atom. What reads the element then reads a field, and
      // the engine that inverts a function's body to build a generator does
      // not recognise it -- which is how `from p where cousin p` lost the
      // `x <> y` of its seed.
      // Only a binder grounding generated: it is unique, so reusing it cannot
      // capture, and it names nothing the user wrote. A name the query itself
      // chose may be a correlated subquery's, and scanning under it would take
      // the name the correlation reads.
      final Core.@Nullable IdPat own = binderOf(collection);
      if (own != null
          && Core.NamedPat.isGenerated(own.name)
          && usedNames.add(own.name)) {
        return own;
      }
      return freshPat(type);
    }
    final List<String> names = requireNonNull(scanNames.remove());
    final String name = names.size() == 1 ? names.get(0) : "";
    // Empty where the caller had a pattern rather than a name: a pattern
    // names no one thing, and the tree keeps paths instead.
    if (name.isEmpty() || !usedNames.add(name)) {
      return freshPat(type);
    }
    final Core.@Nullable IdPat own = binderOf(collection);
    if (own != null && own.name.equals(name)) {
      // The collection's own last step leaves a binder of this very name --
      // grounding built `from x in c group x order x yield x` for a scan the
      // query calls `x`. Numbering a second `x` makes the builder rename what
      // it inlined, and the plan gains `yield {x = x}` and calls the rest
      // `x_1`. Reusing the binder makes that rename the identity, which the
      // builder drops. Only where the names agree: where they differ the
      // collection may be a correlated subquery, and taking its binder would
      // capture what the correlation reads.
      return own;
    }
    return core.idPat(type, name, nameGenerator::inc);
  }

  /**
   * Returns the binder a collection's own last step leaves, or null if it does
   * not leave exactly one.
   */
  private static Core.@Nullable IdPat binderOf(Core.Exp collection) {
    if (!(collection instanceof Core.From)) {
      return null;
    }
    final List<Core.FromStep> steps = ((Core.From) collection).steps;
    if (steps.isEmpty()) {
      return null;
    }
    final List<Binding> bindings = last(steps).env.bindings;
    return bindings.size() == 1 && bindings.get(0).id instanceof Core.IdPat
        ? (Core.IdPat) bindings.get(0).id
        : null;
  }

  /**
   * Creates a binder for the step list, numbered from the counter the caller
   * supplied and prefixed {@code w$} to keep it clear of the tree's {@code v$}.
   *
   * <p>Two counters that both start at zero and both say {@code v$} collide the
   * moment their outputs meet in one expression, and these binders sit in a
   * step list whose expressions are the tree's.
   */
  private Core.IdPat freshPat(Type type) {
    return core.idPat(type, nameGenerator.getPrefixed("w"), 0);
  }

  /**
   * Replaces {@code $0} and {@code $1} with expressions.
   *
   * <p>The walk stops at a nested node: its own {@code $0} is its own input's
   * element, and the spec forbids it from reading this node's. To use this
   * node's element inside a nested tree the resolver binds it first, and the
   * binding is an ordinary name that substitution leaves alone.
   */
  private Core.Exp subst(
      Core.Exp exp, Core.@Nullable Exp e0, Core.@Nullable Exp e1) {
    return exp.accept(
        new Shuttle(typeSystem) {
          @Override
          protected Core.@Nullable Exp visitRel(Core.Rel rel) {
            return rel;
          }

          @Override
          protected Core.Exp visit(Core.Input input) {
            if (e0 != null && input.i == 0) {
              return core.at(e0, input.pos);
            }
            if (e1 != null && input.i == 1) {
              return core.at(e1, input.pos);
            }
            return input;
          }

          @Override
          protected Core.Exp visit(Core.Apply apply) {
            return readField(super.visit(apply));
          }
        });
  }

  /**
   * Reads a field out of a record that is being constructed here: {@code #b {a
   * = x, b = y}} becomes {@code y}.
   *
   * <p>The lowering carries a node's element as an expression rather than
   * materializing it, so a projection followed by anything that reads a field
   * -- a filter, a group key, a later projection -- meets a selector applied to
   * a record the projection built. The step list reads the field off the row
   * that its {@code yield} left behind; reading it off the expression that
   * describes the row is the same field, one step earlier.
   */
  private static Core.Exp readField(Core.Exp exp) {
    if (exp instanceof Core.Apply) {
      final Core.Apply apply = (Core.Apply) exp;
      if (apply.fn instanceof Core.RecordSelector
          && apply.arg instanceof Core.Tuple
          // The selector reads a slot of the record it was made for, and
          // only there is its slot this tuple's slot. A substitution can put
          // a tuple under a selector built for another -- one of a different
          // arity, and then the slot is not even in range.
          && ((FnType) apply.fn.type).paramType.equals(apply.arg.type)) {
        final int slot = ((Core.RecordSelector) apply.fn).slot;
        return ((Core.Tuple) apply.arg).args.get(slot);
      }
    }
    return exp;
  }

  /**
   * Replaces a dependent join's binder with the expression that denotes the
   * left element.
   */
  private Core.Exp rename(Core.Exp exp, Core.IdPat param, Core.Exp element) {
    return exp.accept(
        new Shuttle(typeSystem) {
          @Override
          protected Core.Exp visit(Core.Id id) {
            return id.idPat.equals(param) ? core.at(element, id.pos) : id;
          }

          @Override
          protected Core.Exp visit(Core.Apply apply) {
            return readField(super.visit(apply));
          }
        });
  }
}

// End RelLowerer.java
