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

import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.ast.Shuttle;
import net.hydromatic.morel.ast.Visitor;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.PrimitiveType;
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

  /**
   * Counter for the binders this translation generates.
   *
   * <p>Per tree, so that a query's plan text does not depend on how many names
   * were generated before it; see spec.md section 6. A `$` cannot occur in an
   * identifier, so these cannot capture a name the query wrote.
   */
  private int nextName;

  /** The tree built so far; null before the first step. */
  private Core.@Nullable Exp exp;

  /**
   * Whether the access map came from destructuring a scan's pattern, rather
   * than from an element that a step computed. Only such a map can read the
   * fields in an order the bindings do not describe.
   */
  private boolean patternAccess;

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
      // Bare 'from' iterates over a single element, which is unit.
      return unitCollection();
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
    patternAccess = false;
    if (exp == null && step.op != Op.SCAN) {
      // A 'from' with no scan -- 'from where p', 'from yield e' -- iterates
      // over a single element, which is unit.
      exp = unitCollection();
    }
    switch (step.op) {
      case SCAN:
        return scan((Core.Scan) step, Core.Rel.JoinType.INNER);

      case LEFT_JOIN:
        return scan((Core.Scan) step, Core.Rel.JoinType.LEFT);

      case RIGHT_JOIN:
        return scan((Core.Scan) step, Core.Rel.JoinType.RIGHT);

      case FULL_JOIN:
        return scan((Core.Scan) step, Core.Rel.JoinType.FULL);

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
      case INTERSECT:
      case EXCEPT:
        return setOp((Core.SetStep) step);

      default:
        return false;
    }
  }

  /**
   * Returns the collection that a scanless {@code from} iterates over: a list
   * containing one unit element.
   */
  private Core.Exp unitCollection() {
    return core.list(
        typeSystem, PrimitiveType.UNIT, ImmutableList.of(core.unitLiteral()));
  }

  /**
   * Translates a scan. The first scan is a leaf; a later scan whose collection
   * does not depend on the bindings so far is a join; one that does depend on
   * them is a {@code projectMany}.
   */
  private boolean scan(Core.Scan scan, Core.Rel.JoinType joinType) {
    final Type rightElementType = scan.exp.type.elementType();

    if (exp == null) {
      if (joinType != Core.Rel.JoinType.INNER) {
        // An outer join cannot be the first step; there is nothing to be
        // outer to.
        return false;
      }
      // The first scan is a leaf, and the pattern's binders are paths into its
      // element.
      exp = scan.exp;
      patternAccess = true;
      if (!destructure(scan.pat, core.input0(rightElementType), access)) {
        // The pattern can fail to match, so the scan filters as well as
        // binds. The projectMany builds the element, so the binders read it
        // as any later step would -- including this scan's own condition,
        // which is why the map is set before the condition is rewritten.
        final Core.@Nullable Exp exp2 =
            matchMany(scan.exp, scan.pat, elementType(scan.env));
        if (exp2 == null) {
          return false;
        }
        exp = exp2;
        setUniformAccess(scan.env);
      }
      if (!scan.condition.isBoolLiteral(true)) {
        exp = core.filter(exp, rewrite(scan.condition));
      }
      return true;
    }

    final Core.Exp left = requireExp();
    final Type wanted = elementType(scan.env);

    if (dependsOnBindings(scan.exp)) {
      if (joinType == Core.Rel.JoinType.RIGHT
          || joinType == Core.Rel.JoinType.FULL) {
        // Every element of a correlated collection comes from some left
        // element, so there is nothing for the other side to be outer to.
        return false;
      }
      // A correlated scan. The lambda's parameter names the left element,
      // because the body is a tree, and a tree would shadow $0.
      final Core.IdPat param = param(left.type.elementType());
      final Map<Core.NamedPat, Core.Exp> outerAccess = over(access, param);
      final Map<Core.NamedPat, Core.Exp> rightAccess = new LinkedHashMap<>();
      if (!destructure(scan.pat, core.input0(rightElementType), rightAccess)) {
        return false;
      }
      final Map<Core.NamedPat, Core.Exp> yieldAccess =
          new LinkedHashMap<>(outerAccess);
      final Map<Core.NamedPat, Core.Exp> emptyAccess =
          new LinkedHashMap<>(outerAccess);
      // A correlated outer join yields SOME of each right binder where the
      // collection has elements, and NONE for all of them where it does not.
      for (Map.Entry<Core.NamedPat, Core.Exp> entry : rightAccess.entrySet()) {
        if (joinType == Core.Rel.JoinType.INNER) {
          yieldAccess.put(entry.getKey(), entry.getValue());
          continue;
        }
        final Core.@Nullable NamedPat binding =
            binding(scan.env, entry.getKey().name);
        if (binding == null) {
          return false;
        }
        yieldAccess.put(entry.getKey(), some(binding.type, entry.getValue()));
        emptyAccess.put(entry.getKey(), none(binding.type));
      }
      final Map<Core.NamedPat, Core.Exp> combined =
          both(outerAccess, rightAccess);
      Core.Exp body = substitute(scan.exp, outerAccess);
      if (!scan.condition.isBoolLiteral(true)) {
        body = core.filter(body, substitute(scan.condition, combined));
      }
      body = core.project(typeSystem, body, element(yieldAccess, wanted));
      if (joinType != Core.Rel.JoinType.INNER) {
        // An outer apply: where the collection has nothing that matches, the
        // left element still yields a row. The node sits inside the lambda,
        // where the parameter its expression reads is in scope.
        body = core.ifEmpty(body, element(emptyAccess, wanted));
      }
      exp = core.projectMany(typeSystem, left, param, body);
      return true;
    }

    // An independent scan is a join: the left element is $0, the right $1.
    final Map<Core.NamedPat, Core.Exp> rightAccess = new LinkedHashMap<>();
    if (!destructure(scan.pat, core.input1(rightElementType), rightAccess)) {
      return false;
    }
    // The condition sees both elements as they are, because it is evaluated
    // on candidate pairs; the yield sees an option on a side that an outer
    // join can leave absent.
    final Map<Core.NamedPat, Core.Exp> condAccess = both(access, rightAccess);
    final Map<Core.NamedPat, Core.Exp> yieldAccess;
    if (joinType == Core.Rel.JoinType.INNER) {
      yieldAccess = condAccess;
    } else {
      yieldAccess = new LinkedHashMap<>();
      if (!side(
              yieldAccess,
              access,
              0,
              joinType.leftIsOption(),
              scan.env,
              left.type.elementType())
          || !side(
              yieldAccess,
              rightAccess,
              1,
              joinType.rightIsOption(),
              scan.env,
              rightElementType)) {
        return false;
      }
    }
    exp =
        core.join(
            typeSystem,
            joinType,
            left,
            scan.exp,
            substitute(scan.condition, condAccess),
            element(yieldAccess, wanted));
    return true;
  }

  /**
   * Returns a collection of the elements that match a pattern, for a scan whose
   * pattern can fail: a {@code projectMany} whose body yields one element where
   * the pattern matches and none where it does not.
   *
   * <p>The pattern binds its own names, as a lambda's parameter does, so the
   * element is built from those names rather than from {@code $0}.
   */
  private Core.@Nullable Exp matchMany(
      Core.Exp collection, Core.Pat pat, Type wanted) {
    final Map<Core.NamedPat, Core.Exp> binderAccess = new LinkedHashMap<>();
    for (Core.NamedPat binder : pat.expand()) {
      binderAccess.put(binder, core.id(binder));
    }
    final Core.Exp element = element(binderAccess, wanted);
    final Type elementType = collection.type.elementType();
    final Core.IdPat param = freshPat(elementType);
    final Core.Exp body =
        core.caseOf(
            Pos.ZERO,
            typeSystem.listType(element.type),
            core.id(param),
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
    return core.projectMany(typeSystem, collection, param, body);
  }

  /**
   * Adds one side of an outer join's yield to an access map.
   *
   * <p>On a side that the join can leave absent, every binder becomes an
   * option, and this translator can express that only when the side has a
   * single binder whose value is the whole element -- then the binder is the
   * option-typed input reference. A destructuring pattern would need each
   * binder mapped through the option, and is declined.
   */
  private boolean side(
      Map<Core.NamedPat, Core.Exp> yieldAccess,
      Map<Core.NamedPat, Core.Exp> sideAccess,
      int i,
      boolean option,
      Core.StepEnv env,
      Type rawElementType) {
    if (!option) {
      yieldAccess.putAll(sideAccess);
      return true;
    }
    final Core.Id rawRef = core.input(rawElementType, i);
    final Core.Id optionRef = core.input(typeSystem.option(rawElementType), i);
    for (Map.Entry<Core.NamedPat, Core.Exp> entry : sideAccess.entrySet()) {
      final Core.@Nullable NamedPat binding = binding(env, entry.getKey().name);
      if (binding == null) {
        return false;
      }
      yieldAccess.put(
          entry.getKey(),
          optionize(entry.getValue(), rawRef, optionRef, binding.type));
    }
    return true;
  }

  /**
   * Re-expresses an access into an element as an access into an option of that
   * element, for the yield of an outer join.
   *
   * <p>Where the binder is the whole element, the option-typed input reference
   * is the access. Otherwise the access is mapped through the option, because
   * Morel makes each binder of the absent side an option, not the side as a
   * whole: {@code left join (j, k) in pairs} binds {@code j : int option} and
   * {@code k : int option}, not {@code (int * int) option}.
   */
  private Core.Exp optionize(
      Core.Exp access, Core.Id rawRef, Core.Id optionRef, Type optionType) {
    if (access.op == Op.ID) {
      return optionRef;
    }
    final Core.IdPat param = freshPat(rawRef.type);
    final Core.Exp body =
        access.accept(
            new Shuttle(typeSystem) {
              @Override
              protected Core.Exp visit(Core.Id id) {
                return id.idPat.name.equals(rawRef.idPat.name)
                    ? core.id(param)
                    : id;
              }
            });
    final Core.Fn fn =
        core.fn(typeSystem.fnType(rawRef.type, body.type), param, body);
    final Core.Exp map = core.functionLiteral(typeSystem, BuiltIn.OPTION_MAP);
    return core.apply(
        Pos.ZERO,
        optionType,
        core.apply(
            Pos.ZERO, typeSystem.fnType(optionRef.type, optionType), map, fn),
        optionRef);
  }

  /** Wraps a value in {@code SOME}. */
  private Core.Exp some(Type optionType, Core.Exp value) {
    final Core.Id someId =
        core.id(
            core.idPat(typeSystem.fnType(value.type, optionType), "SOME", 0));
    return core.apply(Pos.ZERO, optionType, someId, value);
  }

  /** Returns {@code NONE} of a given option type. */
  private Core.Exp none(Type optionType) {
    return core.id(core.idPat(optionType, "NONE", 0));
  }

  /** Returns the binding of a given name in a step's environment, or null. */
  private Core.@Nullable NamedPat binding(Core.StepEnv env, String name) {
    for (Binding b : env.bindings) {
      if (b.id.name.equals(name)) {
        return b.id;
      }
    }
    return null;
  }

  /**
   * Translates a set operator. Morel aligns the branches of a {@code union}, so
   * an input whose element is a one-field record where the step describes a
   * bare value (or the other way about) is converted with a projection.
   */
  private boolean setOp(Core.SetStep step) {
    final Type wanted = elementType(step.env);
    final List<Core.Exp> inputs = new ArrayList<>();
    inputs.add(requireExp());
    step.args.forEach(arg -> inputs.add(rewrite(arg)));
    for (int i = 0; i < inputs.size(); i++) {
      final Core.@Nullable Exp input = align(inputs.get(i), wanted);
      if (input == null) {
        return false;
      }
      inputs.set(i, input);
    }
    switch (step.op) {
      case UNION:
        exp = core.union(typeSystem, step.distinct, inputs);
        return true;
      case INTERSECT:
        exp = core.intersect(typeSystem, step.distinct, inputs);
        return true;
      default:
        exp = core.except(typeSystem, step.distinct, inputs);
        return true;
    }
  }

  /**
   * Converts a collection so that its element has the wanted type, returning
   * the input unchanged if it already does, or null if the conversion is not
   * one of the two that branch alignment allows.
   */
  private Core.@Nullable Exp align(Core.Exp input, Type wanted) {
    final Type elementType = input.type.elementType();
    if (elementType.equals(wanted)) {
      return input;
    }
    if (wanted instanceof RecordLikeType) {
      // Wrap a bare value in the one-field record the step describes.
      final Map<String, Type> nameTypes =
          ((RecordLikeType) wanted).argNameTypes();
      if (nameTypes.size() == 1
          && nameTypes.values().iterator().next().equals(elementType)) {
        final PairList<String, Core.Exp> nameExps = PairList.of();
        nameExps.add(
            nameTypes.keySet().iterator().next(), core.input0(elementType));
        return core.project(
            typeSystem, input, core.record(typeSystem, nameExps));
      }
    }
    if (elementType instanceof RecordLikeType) {
      // Unwrap a one-field record to the bare value the step describes.
      final Map<String, Type> nameTypes =
          ((RecordLikeType) elementType).argNameTypes();
      if (nameTypes.size() == 1
          && nameTypes.values().iterator().next().equals(wanted)) {
        return core.project(
            typeSystem,
            input,
            core.field(typeSystem, core.input0(elementType), 0));
      }
    }
    return null;
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
    // The element must not only have the right type: the binders must read
    // the right fields of it. A pattern can permute them -- `from {b = a, a =
    // b} in rs` binds a to field b -- and then the types agree while the
    // access map does not, so compare the map, not just the type.
    if (!requireExp().type.elementType().equals(wanted)) {
      // A step that computes its own element -- a yield, a group -- differs
      // from what the bindings describe only in whether a lone value is
      // wrapped in a record; a scan differs in more, and its access map says
      // how.
      final Core.@Nullable Exp aligned = align(requireExp(), wanted);
      exp =
          aligned != null
              ? aligned
              : core.project(typeSystem, requireExp(), element(access, wanted));
    } else if (patternAccess && !isUniform(env, wanted)) {
      // The element has the right type but the binders read the wrong fields
      // of it, which a pattern that permutes them does: `from {b = a, a = b}
      // in rs` binds a to field b.
      exp = core.project(typeSystem, requireExp(), element(access, wanted));
    }
    setUniformAccess(env);
  }

  /**
   * Points each binder at the element that the step's bindings describe: the
   * element itself if the step atomizes, otherwise the field of the same name.
   */
  private void setUniformAccess(Core.StepEnv env) {
    final Core.Exp element = core.input0(elementType(env));
    patternAccess = false;
    access.clear();
    if (env.atom) {
      access.put(env.bindings.get(0).id, element);
    } else {
      env.bindings.forEach(b -> access.put(b.id, field(element, b.id.name)));
    }
  }

  /**
   * Returns whether each binder already reads the element the way the uniform
   * map would: the element itself if the step atomizes, otherwise the field of
   * the same name.
   */
  private boolean isUniform(Core.StepEnv env, Type wanted) {
    if (env.bindings.size() != access.size()) {
      return false;
    }
    for (Binding binding : env.bindings) {
      final Core.@Nullable Exp exp = access.get(binding.id);
      if (exp == null) {
        return false;
      }
      if (env.atom) {
        if (!isInput0(exp)) {
          return false;
        }
      } else {
        if (exp.op != Op.APPLY) {
          return false;
        }
        final Core.Apply apply = (Core.Apply) exp;
        if (apply.fn.op != Op.RECORD_SELECTOR || !isInput0(apply.arg)) {
          return false;
        }
        if (((Core.RecordSelector) apply.fn).slot
            != slot(wanted, binding.id.name)) {
          return false;
        }
      }
    }
    return true;
  }

  private static boolean isInput0(Core.Exp exp) {
    return exp.op == Op.ID && ((Core.Id) exp).idPat.name.equals(INPUT_0);
  }

  /** Returns the ordinal of a field in a record type, or -1. */
  private static int slot(Type type, String name) {
    if (!(type instanceof RecordLikeType)) {
      return -1;
    }
    int i = 0;
    for (String fieldName : ((RecordLikeType) type).argNameTypes().keySet()) {
      if (fieldName.equals(name)) {
        return i;
      }
      ++i;
    }
    return -1;
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
            return exp != null ? core.at(exp, id.pos) : id;
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
    return freshPat(elementType);
  }

  /**
   * Creates a binder that the translation needs and the query did not write.
   */
  private Core.IdPat freshPat(Type type) {
    return core.idPat(type, "v$" + nextName++, 0);
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
