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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.ast.Shuttle;
import net.hydromatic.morel.ast.Visitor;
import net.hydromatic.morel.type.RecordLikeType;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.PairList;
import org.jspecify.annotations.Nullable;

/**
 * Translates a {@link Core.From} -- a list of steps, each executing in an
 * environment of bindings -- into a relational tree of {@link Core.Rel} nodes,
 * whose expressions name their input element {@code $0} and bind nothing.
 *
 * <p>This is the "variable elimination" of step 1 of {@code plan.md}: a binder
 * becomes an expression over {@code $0} -- the element itself, if the element
 * is that binder's value, otherwise a field of it -- and the binding list
 * disappears, because a node's element type says everything the bindings said.
 *
 * <p>The translation is a shadow: it does not change what a query does, and
 * exists so that trees can be built and printed for every query in the test
 * suite while {@code Core.From} still does the work. Constructs it cannot yet
 * translate -- outer joins, and scans whose pattern is not a variable, a
 * wildcard, a tuple or a record -- make it return null rather than guess.
 */
public class RelTranslator {
  /** The name that an expression uses for the element of its input. */
  private static final String INPUT_0 = "$0";

  private final TypeSystem typeSystem;

  /**
   * Expression, over the current tree's element, for each binder in scope.
   *
   * <p>Between steps the map is uniform, in the same sense that {@link
   * Core.StepEnv} is: the element either is the only binder's value ({@code
   * $0}) or is a record with one field per binder ({@code #name $0}).
   */
  private final Map<Core.NamedPat, Core.Exp> access = new LinkedHashMap<>();

  /** The tree built so far; null before the first step. */
  private Core.@Nullable Exp exp;

  private RelTranslator(TypeSystem typeSystem) {
    this.typeSystem = typeSystem;
  }

  /**
   * Translates a {@code from} expression into a tree, or returns null if it
   * contains something this translator does not yet handle.
   *
   * <p>The result is an expression, not necessarily a {@link Core.Rel}: the
   * tree for {@code from e in emps} is the leaf {@code emps}.
   */
  public static Core.@Nullable Exp toRel(
      TypeSystem typeSystem, Core.From from) {
    return new RelTranslator(typeSystem).from(from);
  }

  private Core.@Nullable Exp from(Core.From from) {
    if (from.steps.isEmpty()) {
      return null;
    }
    for (Core.FromStep step : from.steps) {
      if (!step(step)) {
        return null;
      }
      normalize(step.env);
    }
    return exp;
  }

  /** Translates one step, returning false if it cannot. */
  private boolean step(Core.FromStep step) {
    switch (step.op) {
      case SCAN:
        return scan((Core.Scan) step);

      case LEFT_JOIN:
      case RIGHT_JOIN:
      case FULL_JOIN:
        // An outer join makes the absent side an option; the condition and the
        // yield need care that this translator does not yet take.
        return false;

      case WHERE:
        exp = core.filter(requireExp(), rewrite(((Core.Where) step).exp));
        return true;

      case YIELD:
        exp =
            core.project(
                typeSystem, requireExp(), rewrite(((Core.Yield) step).exp));
        return true;

      case GROUP:
        return group((Core.GroupStep) step);

      case ORDER:
        exp =
            core.sort(
                typeSystem, requireExp(), rewrite(((Core.Order) step).exp));
        return true;

      case UNORDER:
        exp = core.unorder(typeSystem, requireExp());
        return true;

      case SKIP:
        exp = core.skip(requireExp(), rewrite(((Core.SkipStep) step).exp));
        return true;

      case TAKE:
        exp = core.take(requireExp(), rewrite(((Core.TakeStep) step).exp));
        return true;

      case UNION:
        exp = core.union(typeSystem, distinct(step), setInputs(step));
        return true;

      case INTERSECT:
        exp = core.intersect(typeSystem, distinct(step), setInputs(step));
        return true;

      case EXCEPT:
        exp = core.except(typeSystem, distinct(step), setInputs(step));
        return true;

      default:
        return false;
    }
  }

  /**
   * Translates a scan. The first scan is a leaf; a later scan whose collection
   * does not depend on the bindings so far is a join; one that does depend on
   * them is a {@code projectMany}.
   */
  private boolean scan(Core.Scan scan) {
    final Type rightElementType = scan.exp.type.elementType();

    if (exp == null) {
      // The first scan is a leaf, and the pattern's binders are paths into its
      // element.
      exp = scan.exp;
      if (!destructure(scan.pat, core.input0(rightElementType), access)) {
        return false;
      }
      if (!scan.condition.isBoolLiteral(true)) {
        exp = core.filter(exp, rewrite(scan.condition));
      }
      return true;
    }

    final Core.Exp left = requireExp();
    final Type wanted = elementType(scan.env);

    if (dependsOnBindings(scan.exp)) {
      // A correlated scan. The lambda's parameter names the left element,
      // because the body is a tree, and a tree would shadow $0.
      final Core.IdPat param = param(left.type.elementType());
      final Map<Core.NamedPat, Core.Exp> outerAccess = over(access, param);
      final Map<Core.NamedPat, Core.Exp> rightAccess = new LinkedHashMap<>();
      if (!destructure(scan.pat, core.input0(rightElementType), rightAccess)) {
        return false;
      }
      final Map<Core.NamedPat, Core.Exp> combined =
          both(outerAccess, rightAccess);
      Core.Exp body = substitute(scan.exp, outerAccess);
      if (!scan.condition.isBoolLiteral(true)) {
        body = core.filter(body, substitute(scan.condition, combined));
      }
      body = core.project(typeSystem, body, element(combined, wanted));
      exp = core.projectMany(typeSystem, left, param, body);
      return true;
    }

    // An independent scan is a join: the left element is $0, the right $1.
    final Map<Core.NamedPat, Core.Exp> rightAccess = new LinkedHashMap<>();
    if (!destructure(scan.pat, core.input1(rightElementType), rightAccess)) {
      return false;
    }
    final Map<Core.NamedPat, Core.Exp> combined = both(access, rightAccess);
    exp =
        core.join(
            typeSystem,
            left,
            scan.exp,
            substitute(scan.condition, combined),
            element(combined, wanted));
    return true;
  }

  private boolean group(Core.GroupStep group) {
    final SortedMap<String, Core.Exp> keys = new TreeMap<>(RecordType.ORDERING);
    group.groupExps.forEach((pat, exp) -> keys.put(pat.name, rewrite(exp)));
    final SortedMap<String, Core.Aggregate> aggregates =
        new TreeMap<>(RecordType.ORDERING);
    group.aggregates.forEach(
        (pat, aggregate) ->
            aggregates.put(
                pat.name,
                aggregate.copy(
                    aggregate.type,
                    rewrite(aggregate.aggregate),
                    aggregate.argument == null
                        ? null
                        : rewrite(aggregate.argument))));
    exp = core.group(typeSystem, requireExp(), keys, aggregates);
    return true;
  }

  /**
   * Makes the tree's element agree with the element that the step's bindings
   * describe, adding a projection if it does not, and leaves the access map
   * uniform.
   *
   * <p>Only a scan whose pattern destructures, or an atomizing step, needs the
   * projection; for every other step the node already produces the element.
   */
  private void normalize(Core.StepEnv env) {
    final Type wanted = elementType(env);
    if (!requireExp().type.elementType().equals(wanted)) {
      exp = core.project(typeSystem, requireExp(), element(access, wanted));
    }
    final Core.Exp element = core.input0(wanted);
    access.clear();
    if (env.atom) {
      access.put(env.bindings.get(0).id, element);
    } else {
      env.bindings.forEach(b -> access.put(b.id, field(element, b.id.name)));
    }
  }

  /**
   * Returns the element type that a step's bindings describe: the bare type of
   * the only binding if the step atomizes, otherwise a record with one field
   * per binding.
   */
  private Type elementType(Core.StepEnv env) {
    if (env.atom) {
      return env.bindings.get(0).id.type;
    }
    final PairList<String, Type> nameTypes = PairList.of();
    env.bindings.forEach(b -> nameTypes.add(b.id.name, b.id.type));
    return typeSystem.recordType(nameTypes);
  }

  /**
   * Returns the expression that builds an element of the wanted type from the
   * binders' access expressions: the only binder's value if the element
   * atomizes, otherwise a record.
   */
  private Core.Exp element(Map<Core.NamedPat, Core.Exp> access, Type wanted) {
    if (access.size() == 1) {
      final Core.Exp only = access.values().iterator().next();
      if (only.type.equals(wanted)) {
        return only;
      }
    }
    final PairList<String, Core.Exp> nameExps = PairList.of();
    access.forEach((pat, exp) -> nameExps.add(pat.name, exp));
    return core.record(typeSystem, nameExps);
  }

  private boolean distinct(Core.FromStep step) {
    return ((Core.SetStep) step).distinct;
  }

  /**
   * Returns the inputs of a set operator: the tree so far, and the step's
   * arguments.
   */
  private List<Core.Exp> setInputs(Core.FromStep step) {
    final List<Core.Exp> inputs = new ArrayList<>();
    inputs.add(requireExp());
    ((Core.SetStep) step).args.forEach(arg -> inputs.add(rewrite(arg)));
    return inputs;
  }

  /**
   * Adds to {@code map} an access expression for each binder of a pattern,
   * returning false if the pattern is not one this translator handles.
   */
  private boolean destructure(
      Core.Pat pat, Core.Exp element, Map<Core.NamedPat, Core.Exp> map) {
    switch (pat.op) {
      case ID_PAT:
        map.put((Core.IdPat) pat, element);
        return true;

      case WILDCARD_PAT:
        return true;

      case TUPLE_PAT:
        return destructureAll(((Core.TuplePat) pat).args, element, map);

      case RECORD_PAT:
        return destructureAll(((Core.RecordPat) pat).args, element, map);

      default:
        // A pattern that can fail to match -- a literal, a constructor, a list
        // -- also filters, which this translator does not yet express.
        return false;
    }
  }

  private boolean destructureAll(
      List<Core.Pat> pats, Core.Exp element, Map<Core.NamedPat, Core.Exp> map) {
    for (int i = 0; i < pats.size(); i++) {
      if (!destructure(pats.get(i), core.field(typeSystem, element, i), map)) {
        return false;
      }
    }
    return true;
  }

  /** Returns the field of an expression with a given name. */
  private Core.Exp field(Core.Exp exp, String name) {
    final RecordLikeType recordType = (RecordLikeType) exp.type;
    int slot = 0;
    for (String fieldName : recordType.argNameTypes().keySet()) {
      if (fieldName.equals(name)) {
        return core.field(typeSystem, exp, slot);
      }
      ++slot;
    }
    throw new IllegalArgumentException(
        "no field '" + name + "' in type '" + exp.type + "'");
  }

  /**
   * Re-expresses access expressions over a lambda parameter rather than over
   * {@code $0}, for the body of a {@code projectMany}.
   */
  private Map<Core.NamedPat, Core.Exp> over(
      Map<Core.NamedPat, Core.Exp> access, Core.IdPat param) {
    final Core.Id paramId = core.id(param);
    final Map<Core.NamedPat, Core.Exp> map = new LinkedHashMap<>();
    access.forEach(
        (pat, exp) ->
            map.put(
                pat,
                exp.accept(
                    new Shuttle(typeSystem) {
                      @Override
                      protected Core.Exp visit(Core.Id id) {
                        return id.idPat.name.equals(INPUT_0) ? paramId : id;
                      }
                    })));
    return map;
  }

  /**
   * Rewrites an expression, replacing each binder in scope with its access
   * expression over {@code $0}.
   */
  private Core.Exp rewrite(Core.Exp exp) {
    return substitute(exp, access);
  }

  private Core.Exp substitute(Core.Exp exp, Map<Core.NamedPat, Core.Exp> map) {
    return exp.accept(
        new Shuttle(typeSystem) {
          @Override
          protected Core.Exp visit(Core.Id id) {
            final Core.Exp exp = map.get(id.idPat);
            return exp != null ? exp : id;
          }
        });
  }

  /** Returns whether an expression references any binder in scope. */
  private boolean dependsOnBindings(Core.Exp exp) {
    final boolean[] depends = {false};
    exp.accept(
        new Visitor() {
          @Override
          protected void visit(Core.Id id) {
            if (access.containsKey(id.idPat)) {
              depends[0] = true;
            }
          }
        });
    return depends[0];
  }

  /**
   * Creates the parameter of a {@code projectMany} lambda. If the element is a
   * single binder's value, the parameter is that binder, which is why {@code
   * from d in depts, e in d.emps} prints as {@code fn d => ...}.
   */
  private Core.IdPat param(Type elementType) {
    if (access.size() == 1) {
      final Map.Entry<Core.NamedPat, Core.Exp> only =
          access.entrySet().iterator().next();
      if (only.getValue().op == Op.ID
          && only.getKey() instanceof Core.IdPat
          && only.getKey().type.equals(elementType)) {
        return (Core.IdPat) only.getKey();
      }
    }
    return core.idPat(elementType, typeSystem.nameGenerator.get(), 0);
  }

  private Map<Core.NamedPat, Core.Exp> both(
      Map<Core.NamedPat, Core.Exp> left, Map<Core.NamedPat, Core.Exp> right) {
    final Map<Core.NamedPat, Core.Exp> map = new LinkedHashMap<>(left);
    map.putAll(right);
    return map;
  }

  private Core.Exp requireExp() {
    if (exp == null) {
      throw new IllegalStateException("step before the first scan");
    }
    return exp;
  }
}

// End RelTranslator.java
