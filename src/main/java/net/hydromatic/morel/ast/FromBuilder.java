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

import static net.hydromatic.morel.ast.CoreBuilder.core;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableRangeSet;
import com.google.common.collect.Range;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.RecordLikeType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;

/**
 * Builds a query, step by step, as a relational tree.
 *
 * <p>The steps are those of a {@code from} expression -- scan, where, yield,
 * order, group, distinct, skip, take, and the set operators -- and their
 * expressions are written over the variables that earlier steps bound, as a
 * query is written: {@code scan(x, xs)} then {@code where(x > 1)}. The builder
 * keeps the names in scope and rewrites each expression over the tree's own
 * patterns before it hands it to a {@link RelBuilder}, which is where the tree
 * is built.
 *
 * <p>A step's names are keyed by name, so a later step that binds a name again
 * shadows the earlier binding, as it does in a query.
 */
public class FromBuilder {
  private final TypeSystem typeSystem;
  private final RelBuilder b;

  /** Names in scope, in the order they were bound; the row's bindings. */
  private final Set<String> bindings = new LinkedHashSet<>();

  /**
   * Whether the row is a single value bound to one name, as opposed to a record
   * whose fields are the bindings.
   */
  private boolean atom;

  /** Use {@link net.hydromatic.morel.ast.CoreBuilder#fromBuilder}. */
  FromBuilder(TypeSystem typeSystem) {
    this.typeSystem = typeSystem;
    this.b = RelBuilder.create(typeSystem);
  }

  @Override
  public String toString() {
    return b.size() == 0 ? "[]" : b.peek().toString();
  }

  /** Creates an unbounded scan, "from pat". */
  public FromBuilder scan(Core.Pat pat) {
    final Core.Exp extent =
        core.extent(
            Pos.ZERO, typeSystem, pat.type, ImmutableRangeSet.of(Range.all()));
    return scan(pat, extent, core.boolLiteral(true));
  }

  /** Creates a bounded scan, "from pat in exp". */
  public FromBuilder scan(Core.Pat pat, Core.Exp exp) {
    return scan(pat, exp, core.boolLiteral(true));
  }

  public FromBuilder scan(Core.Pat pat, Core.Exp exp, Core.Exp condition) {
    return scan(Core.Rel.JoinType.INNER, pat, exp, condition);
  }

  /**
   * Scans a collection, joining it to the query so far if there is one.
   *
   * <p>The collection may read the names bound so far, in which case the join
   * is dependent; the condition may read those and the names the pattern binds.
   */
  public FromBuilder scan(
      Core.Rel.JoinType joinType,
      Core.Pat pat,
      Core.Exp exp,
      Core.Exp condition) {
    final Core.Exp exp2 = resolve(exp);
    final List<String> names = new ArrayList<>();
    pat.expand().forEach(p -> names.add(p.name));
    if (b.size() == 0) {
      push(pat, exp2);
      bind(names);
      atom = bindings.size() == 1;
      return where(condition);
    }
    push(pat, exp2);
    b.pair();
    // The condition sees both sides: a name the pattern binds is the right
    // input's, and any other is the left's.
    final Core.Exp condition2 = resolve(condition, names);
    b.join(joinType, condition2);
    bind(names);
    atom = false;
    return this;
  }

  /**
   * Pushes a collection under a pattern. A pattern that can fail to match -- a
   * user datatype's constructor -- is scanned through a {@code case} that
   * yields nought or one row, as the resolver does.
   */
  private void push(Core.Pat pat, Core.Exp exp) {
    if (RelBuilder.destructurable(pat) || RelBuilder.testable(pat)) {
      b.push(pat, exp);
      return;
    }
    final List<Core.NamedPat> bound = pat.expand();
    final Core.Exp element = core.recordOrAtom(typeSystem, bound);
    final Type elementType = exp.type.elementType();
    b.push(exp);
    final Core.IdPat binder = b.binder();
    final Core.Exp body =
        core.caseOf(
            Pos.ZERO,
            typeSystem.listType(element.type),
            core.id(binder),
            ImmutableList.of(
                core.match(
                    Pos.ZERO,
                    pat,
                    core.list(
                        typeSystem, element.type, ImmutableList.of(element))),
                core.match(
                    Pos.ZERO,
                    core.wildcardPat(elementType),
                    core.list(typeSystem, element.type, ImmutableList.of()))));
    b.push(body);
    b.pair();
    b.join(Core.Rel.JoinType.INNER, core.boolLiteral(true));
    final Core.Exp matched = core.field(typeSystem, b.input(0), 1);
    if (bound.size() == 1) {
      b.project(bound.get(0).name, matched);
    } else {
      b.project(matched);
    }
  }

  /** Binds names, each shadowing an earlier binding of the same name. */
  private void bind(List<String> names) {
    bindings.removeAll(names);
    bindings.addAll(names);
  }

  public FromBuilder where(Core.Exp condition) {
    if (condition.isBoolLiteral(true)) {
      return this;
    }
    b.filter(resolve(condition));
    return this;
  }

  public FromBuilder skip(Core.Exp count) {
    if (count.op == Op.INT_LITERAL
        && ((Core.Literal) count).value.equals(BigDecimal.ZERO)) {
      return this;
    }
    b.skip(resolve(count));
    return this;
  }

  public FromBuilder take(Core.Exp count) {
    b.take(resolve(count));
    return this;
  }

  public FromBuilder except(boolean distinct, List<Core.Exp> args) {
    args.forEach(b::push);
    b.except(args.size() + 1, distinct);
    return this;
  }

  public FromBuilder intersect(boolean distinct, List<Core.Exp> args) {
    args.forEach(b::push);
    b.intersect(args.size() + 1, distinct);
    return this;
  }

  public FromBuilder union(boolean distinct, List<Core.Exp> args) {
    args.forEach(b::push);
    b.union(args.size() + 1, distinct);
    return this;
  }

  /** Makes the query unordered. No-op if already unordered. */
  public FromBuilder unorder() {
    b.unorder();
    return this;
  }

  /** Removes duplicate rows, keeping each binding. */
  public FromBuilder distinct() {
    if (bindings.size() == 1
        && b.name(bindings.iterator().next()).type == PrimitiveType.UNIT) {
      // "from [(), ()] where p distinct" is not "group {}", which always
      // returns one row; "take 1" returns none when the input is empty.
      return take(core.intLiteral(BigDecimal.ONE));
    }
    final SortedMap<String, Core.Exp> keys = new TreeMap<>();
    bindings.forEach(name -> keys.put(name, b.name(name)));
    return group(atom, keys, new TreeMap<>());
  }

  /**
   * Groups the query so far. The keys and aggregates are labeled by name, and
   * their expressions read the bindings so far.
   *
   * @param atom Whether the result is the single output's value rather than a
   *     record of the outputs; meaningful only where there is one output
   */
  public FromBuilder group(
      boolean atom,
      SortedMap<String, Core.Exp> keys,
      SortedMap<String, Core.Aggregate> aggregates) {
    final SortedMap<String, Core.Exp> keys2 = new TreeMap<>();
    keys.forEach((name, exp) -> keys2.put(name, resolve(exp)));
    final SortedMap<String, Core.Aggregate> aggregates2 = new TreeMap<>();
    aggregates.forEach(
        (name, aggregate) ->
            aggregates2.put(
                name,
                aggregate.copy(
                    aggregate.type,
                    resolve(aggregate.aggregate),
                    aggregate.argument == null
                        ? null
                        : resolve(aggregate.argument))));
    b.group(keys2, aggregates2);
    bindings.clear();
    bindings.addAll(keys2.keySet());
    bindings.addAll(aggregates2.keySet());
    this.atom = atom && bindings.size() == 1;
    if (this.atom) {
      // The tree's group builds a record; the atom is its one field.
      final String name = bindings.iterator().next();
      b.project(name, b.field(name));
    }
    return this;
  }

  public FromBuilder order(Core.Exp exp) {
    b.sort(isRow(exp) ? b.input(0) : resolve(exp));
    return this;
  }

  /**
   * Returns whether an expression is the row itself, written as the record of
   * the bindings: {@code {a = a, b = b}} where the row is a record with fields
   * {@code a} and {@code b}. Yielding it is the identity, and sorting by it is
   * sorting by the row.
   */
  private boolean isRow(Core.Exp exp) {
    if (b.size() == 0
        || atom
        || exp.op != Op.TUPLE
        || exp.type.op() != Op.RECORD_TYPE) {
      return false;
    }
    final Core.Tuple tuple = (Core.Tuple) exp;
    final List<String> names = tuple.type().argNames();
    final Type elementType = b.peek().type.elementType();
    if (!(elementType instanceof RecordLikeType)
        || !((RecordLikeType) elementType).argNames().equals(names)
        || !new HashSet<>(names).equals(bindings)) {
      return false;
    }
    for (int i = 0; i < names.size(); i++) {
      final Core.Exp arg = tuple.args.get(i);
      if (arg.op != Op.ID || !((Core.Id) arg).idPat.name.equals(names.get(i))) {
        return false;
      }
    }
    return true;
  }

  /**
   * Yields an expression. A record's fields become the bindings; a value that
   * is a binding keeps its name; any other value has no name.
   */
  public FromBuilder yield_(Core.Exp exp) {
    if (isRow(exp)) {
      return this;
    }
    final Core.Exp exp2 = resolve(exp);
    if (exp.op == Op.TUPLE && exp.type.op() == Op.RECORD_TYPE) {
      b.project(exp2);
      bindings.clear();
      bindings.addAll(((RecordLikeType) exp.type).argNames());
      atom = false;
      return this;
    }
    if (exp.op == Op.ID && bindings.contains(((Core.Id) exp).idPat.name)) {
      final String name = ((Core.Id) exp).idPat.name;
      b.project(name, exp2);
      bindings.clear();
      bindings.add(name);
      atom = true;
      return this;
    }
    b.project(exp2);
    bindings.clear();
    atom = true;
    return this;
  }

  /** Returns the tree built so far. */
  public Core.Exp build() {
    if (b.size() == 0) {
      // Bare 'from' iterates over a single element, which is unit.
      return core.list(
          typeSystem, PrimitiveType.UNIT, ImmutableList.of(core.unitLiteral()));
    }
    return b.build();
  }

  /** Rewrites an expression over the bindings to be over the tree's row. */
  private Core.Exp resolve(Core.Exp exp) {
    return resolve(exp, ImmutableList.of());
  }

  /**
   * Rewrites an expression over the bindings to be over the tree's inputs:
   * {@code rightNames} are the right input's, and the rest the left's (or, for
   * a one-input node, the only input's).
   */
  private Core.Exp resolve(Core.Exp exp, List<String> rightNames) {
    if (b.size() == 0) {
      return exp;
    }
    return exp.accept(
        new Shuttle(typeSystem) {
          @Override
          protected Core.Exp visit(Core.Id id) {
            final String name = id.idPat.name;
            if (rightNames.contains(name)) {
              return core.at(b.name(1, name), id.pos);
            }
            if (bindings.contains(name)) {
              return core.at(b.name(0, name), id.pos);
            }
            return id;
          }
        });
  }
}

// End FromBuilder.java
