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

import static java.lang.String.format;
import static net.hydromatic.morel.ast.CoreBuilder.core;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Visitor;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;

/**
 * Checks the invariants of a relational tree ({@link Core.Rel}).
 *
 * <p>The rules are those in section 5 of {@code spec.md}: every node's type and
 * kind are the ones its inputs and expressions derive, every expression has the
 * type its position requires, and {@code $0} and {@code $1} occur only where a
 * node binds them.
 *
 * <p>Run it after translation and after every rule firing. A rule that produces
 * a tree the validator rejects is wrong, and it is much cheaper to find that
 * here than in the wrong query results it would otherwise cause.
 */
public class RelValidator {
  /** The element of a node's input. */
  private static final String INPUT_0 = "$0";

  /** The element of a join's right input. */
  private static final String INPUT_1 = "$1";

  private static final Set<String> NONE = ImmutableSet.of();
  private static final Set<String> ZERO = ImmutableSet.of(INPUT_0);
  private static final Set<String> ZERO_ONE = ImmutableSet.of(INPUT_0, INPUT_1);

  private final TypeSystem typeSystem;
  private final List<String> violations = new ArrayList<>();

  private RelValidator(TypeSystem typeSystem) {
    this.typeSystem = typeSystem;
  }

  /**
   * Returns the ways in which a tree violates the invariants; an empty list if
   * it is valid.
   */
  public static List<String> violations(TypeSystem typeSystem, Core.Rel rel) {
    final RelValidator validator = new RelValidator(typeSystem);
    validator.node(rel);
    return ImmutableList.copyOf(validator.violations);
  }

  /** Throws if a tree violates the invariants. */
  public static void checkValid(TypeSystem typeSystem, Core.Rel rel) {
    final List<String> violations = violations(typeSystem, rel);
    if (!violations.isEmpty()) {
      throw new IllegalStateException(
          "invalid relational tree: " + String.join("; ", violations));
    }
  }

  private void violation(String message, Object... args) {
    violations.add(format(message, args));
  }

  /**
   * Validates a node: its inputs, the types of its expressions, the scope in
   * which each expression is evaluated, and its own type.
   */
  private void node(Core.Rel rel) {
    if (rel instanceof Core.Filter) {
      final Core.Filter filter = (Core.Filter) rel;
      input(filter.input);
      requireType(filter.condition, PrimitiveType.BOOL, "filter condition");
      scope(filter.condition, ZERO, "filter condition");
      requireDerivedType(rel, core.filter(filter.input, filter.condition));
    } else if (rel instanceof Core.Project) {
      final Core.Project project = (Core.Project) rel;
      input(project.input);
      scope(project.exp, ZERO, "project expression");
      requireDerivedType(
          rel, core.project(typeSystem, project.input, project.exp));
    } else if (rel instanceof Core.ProjectMany) {
      final Core.ProjectMany projectMany = (Core.ProjectMany) rel;
      input(projectMany.input);
      // The body names the input element by the lambda's parameter, so its own
      // $0 belongs to whatever node the body is; validate it as an input.
      input(projectMany.body);
      if (!projectMany.body.type.isCollection()) {
        violation(
            "projectMany body must be list or bag: %s", projectMany.body.type);
      }
      if (projectMany.ifEmpty != null) {
        // Evaluated where the body yields nothing, so it sees the parameter
        // and not $0.
        scope(projectMany.ifEmpty, NONE, "projectMany ifEmpty");
        if (!projectMany.ifEmpty.type.equals(
            projectMany.body.type.elementType())) {
          violation(
              "projectMany ifEmpty must have the element type %s: %s",
              projectMany.body.type.elementType().moniker(),
              projectMany.ifEmpty.type.moniker());
        }
      }
      requireDerivedType(
          rel,
          core.projectMany(
              typeSystem,
              projectMany.input,
              projectMany.param,
              projectMany.body,
              projectMany.ifEmpty));
    } else if (rel instanceof Core.Join) {
      final Core.Join join = (Core.Join) rel;
      input(join.left);
      input(join.right);
      requireType(join.condition, PrimitiveType.BOOL, "join condition");
      scope(join.condition, ZERO_ONE, "join condition");
      scope(join.yieldExp, ZERO_ONE, "join yield");
      requireDerivedType(
          rel,
          core.join(
              typeSystem,
              join.joinType,
              join.left,
              join.right,
              join.condition,
              join.yieldExp));
    } else if (rel instanceof Core.Group) {
      final Core.Group group = (Core.Group) rel;
      input(group.input);
      group.keys.forEach((label, exp) -> scope(exp, ZERO, "group key"));
      group.aggregates.forEach(
          (label, aggregate) -> {
            scope(aggregate.aggregate, ZERO, "aggregate function");
            if (aggregate.argument != null) {
              scope(aggregate.argument, ZERO, "aggregate argument");
            }
          });
      requireDerivedType(
          rel,
          core.group(typeSystem, group.input, group.keys, group.aggregates));
    } else if (rel instanceof Core.Sort) {
      final Core.Sort sort = (Core.Sort) rel;
      input(sort.input);
      scope(sort.exp, ZERO, "sort key");
      requireDerivedType(rel, core.sort(typeSystem, sort.input, sort.exp));
    } else if (rel instanceof Core.Unorder) {
      final Core.Unorder unorder = (Core.Unorder) rel;
      input(unorder.input);
      requireDerivedType(rel, core.unorder(typeSystem, unorder.input));
    } else if (rel instanceof Core.Skip) {
      final Core.Skip skip = (Core.Skip) rel;
      input(skip.input);
      requireType(skip.count, PrimitiveType.INT, "skip count");
      // Evaluated before the first element exists; see spec.md section 2.
      scope(skip.count, NONE, "skip count");
      requireDerivedType(rel, core.skip(skip.input, skip.count));
    } else if (rel instanceof Core.Take) {
      final Core.Take take = (Core.Take) rel;
      input(take.input);
      requireType(take.count, PrimitiveType.INT, "take count");
      scope(take.count, NONE, "take count");
      requireDerivedType(rel, core.take(take.input, take.count));
    } else if (rel instanceof Core.SetRel) {
      final Core.SetRel setRel = (Core.SetRel) rel;
      setRel.inputs.forEach(this::input);
      final Type elementType = setRel.inputs.get(0).type.elementType();
      setRel.inputs.forEach(
          input -> {
            if (!input.type.elementType().equals(elementType)) {
              violation(
                  "%s inputs have different element types: %s, %s",
                  setRel.opName(), elementType, input.type.elementType());
            }
          });
      requireDerivedType(
          rel, setRel.copy(typeSystem, setRel.distinct, setRel.inputs));
    } else {
      violation("unknown node: %s", rel.getClass());
    }
  }

  /**
   * Validates an input: a nested node, or a leaf, which must be a collection
   * and cannot see the element of the node above it.
   */
  private void input(Core.Exp input) {
    if (input instanceof Core.Rel) {
      node((Core.Rel) input);
      return;
    }
    if (!input.type.isCollection()) {
      violation("input must be list or bag: %s", input.type);
    }
    scope(input, NONE, "leaf");
  }

  /**
   * Checks that a node's type is the one derived for it. Rebuilding is the
   * derivation, so a node whose type disagrees was not built by the builder.
   */
  private void requireDerivedType(Core.Rel rel, Core.Rel derived) {
    if (!rel.type.equals(derived.type)) {
      violation(
          "%s has type %s but derives %s",
          rel.opName(), rel.type.moniker(), derived.type.moniker());
    }
  }

  private void requireType(Core.Exp exp, Type type, String what) {
    if (!exp.type.equals(type)) {
      violation("%s must be %s: %s", what, type.moniker(), exp.type.moniker());
    }
  }

  /**
   * Checks that an expression mentions no input reference beyond those the node
   * binds.
   *
   * <p>The walk stops at a nested node, whose expressions are in that node's
   * scope, not this one; the nested node is validated in its own right.
   */
  private void scope(Core.Exp exp, Set<String> allowed, String what) {
    exp.accept(
        new RelBoundaryVisitor() {
          @Override
          protected void visit(Core.Id id) {
            final String name = id.idPat.name;
            if ((name.equals(INPUT_0) || name.equals(INPUT_1))
                && !allowed.contains(name)) {
              violation("%s cannot reference %s", what, name);
            }
          }

          @Override
          protected void rel(Core.Rel rel) {
            node(rel);
          }
        });
  }

  /**
   * Visitor that does not descend into a nested relational node, because the
   * node rebinds {@code $0}.
   */
  private abstract static class RelBoundaryVisitor extends Visitor {
    protected abstract void rel(Core.Rel rel);

    @Override
    protected void visit(Core.Filter filter) {
      rel(filter);
    }

    @Override
    protected void visit(Core.Project project) {
      rel(project);
    }

    @Override
    protected void visit(Core.ProjectMany projectMany) {
      rel(projectMany);
    }

    @Override
    protected void visit(Core.Join join) {
      rel(join);
    }

    @Override
    protected void visit(Core.Group group) {
      rel(group);
    }

    @Override
    protected void visit(Core.Sort sort) {
      rel(sort);
    }

    @Override
    protected void visit(Core.Unorder unorder) {
      rel(unorder);
    }

    @Override
    protected void visit(Core.Skip skip) {
      rel(skip);
    }

    @Override
    protected void visit(Core.Take take) {
      rel(take);
    }

    @Override
    protected void visit(Core.Union union) {
      rel(union);
    }

    @Override
    protected void visit(Core.Intersect intersect) {
      rel(intersect);
    }

    @Override
    protected void visit(Core.Except except) {
      rel(except);
    }
  }
}

// End RelValidator.java
