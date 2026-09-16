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

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Visitor;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.TypeSystem;
import org.apache.calcite.util.Holder;
import org.jspecify.annotations.Nullable;

/**
 * Converts unbounded variables to bounded variables.
 *
 * <p>For example, converts
 *
 * <pre>{@code
 * from e
 *   where e elem #emps scott
 * }</pre>
 *
 * <p>to
 *
 * <pre>{@code
 * from e in #emps scott
 * }</pre>
 */
class SuchThatShuttle extends EnvShuttle {
  /** True if we're inside a recursive function definition. */
  private final boolean inRecursiveFunction;

  /**
   * True if the query being visited supplies only whether it has rows, not the
   * rows themselves, because it is the argument of {@code Relational.nonEmpty}
   * or {@code Relational.empty} - that is, it came from 'exists' or 'forall'.
   * Such a query may discard a variable that it cannot enumerate, as 'y' is
   * discarded in "exists x, y where x elem [1, 2, 3]".
   */
  private final boolean rowsUnused;

  /**
   * Where a binder that grounding invents gets its ordinal.
   *
   * <p>The session's, which is the resolver's: a name is unique only within one
   * generator, and grounding runs after inlining has brought names from several
   * declarations into one scope.
   */
  private final NameGenerator nameGenerator;

  /**
   * Roots that have been grounded, so that the descent into a grounded tree,
   * which grounds the trees nested in it, does not ground the root again.
   * Shared by every shuttle this one pushes.
   */
  private final Set<Core.Exp> grounded;

  SuchThatShuttle(
      TypeSystem typeSystem, Environment env, NameGenerator nameGenerator) {
    this(
        typeSystem,
        env,
        nameGenerator,
        false,
        false,
        Collections.newSetFromMap(new IdentityHashMap<>()));
  }

  private SuchThatShuttle(
      TypeSystem typeSystem,
      Environment env,
      NameGenerator nameGenerator,
      boolean inRecursiveFunction,
      boolean rowsUnused,
      Set<Core.Exp> grounded) {
    super(typeSystem, env);
    this.nameGenerator = nameGenerator;
    this.inRecursiveFunction = inRecursiveFunction;
    this.rowsUnused = rowsUnused;
    this.grounded = grounded;
  }

  @Override
  protected EnvShuttle push(Environment env) {
    return new SuchThatShuttle(
        typeSystem,
        env,
        nameGenerator,
        inRecursiveFunction,
        rowsUnused,
        grounded);
  }

  @Override
  protected Core.Exp visit(Core.Apply apply) {
    if (apply.isCallTo(BuiltIn.RELATIONAL_NON_EMPTY)
        || apply.isCallTo(BuiltIn.RELATIONAL_EMPTY)) {
      final SuchThatShuttle inner =
          new SuchThatShuttle(
              typeSystem,
              env,
              nameGenerator,
              inRecursiveFunction,
              true,
              grounded);
      return apply.copy(apply.fn.accept(inner), apply.arg.accept(inner));
    }
    return super.visit(apply);
  }

  @Override
  protected Core.RecValDecl visit(Core.RecValDecl recValDecl) {
    // When visiting recursive function definitions, mark that we're inside
    // so that From expressions won't be expanded (they're part of the
    // function definition, not queries to execute).
    final List<Binding> bindings = new ArrayList<>();
    Compiles.bindPattern(typeSystem, bindings, recValDecl);
    final SuchThatShuttle inner =
        new SuchThatShuttle(
            typeSystem,
            env.bindAll(bindings),
            nameGenerator,
            true,
            rowsUnused,
            grounded);
    return recValDecl.copy(inner.visitList(recValDecl.list));
  }

  static boolean containsUnbounded(Core.Decl decl) {
    final Holder<Boolean> found = Holder.of(false);
    decl.accept(
        new Visitor() {
          @Override
          protected void visit(Core.Scan scan) {
            super.visit(scan);
            if (Extents.isInfinite(scan.exp)
                || RangePushdown.isInfiniteRangeScan(scan)) {
              found.set(true);
            }
          }

          @Override
          protected void visitRel(Core.Rel rel) {
            // A tree has no scans: its leaves are the inputs that are not
            // themselves nodes. The latch in Compiles stops running this
            // shuttle once this says no, so it has to be exact.
            if (RelExpander.containsUnbounded(rel)) {
              found.set(true);
            }
          }
        });
    return found.get();
  }

  @Override
  protected Core.@Nullable Exp visitRel(Core.Rel rel) {
    // Ground at the root of the tree, which is the one node a shuttle sees
    // before what is under it. A tree nested in an expression is a root of its
    // own, and is grounded when the walk reaches it.
    if (inRecursiveFunction
        || grounded.contains(rel)
        || !RelExpander.containsUnbounded(rel)) {
      return null;
    }
    final boolean rowsUsed = !rowsUnused;
    final List<Core.Pat> leafPats = RelExpander.leafPats(rel);
    final Core.Exp expanded0 =
        RelExpander.expand(typeSystem, env, rel, rowsUsed, leafPats);
    // Ground the trees nested in what came back before asking whether every
    // leaf is bounded: a generator's collection may be a query of its own
    // over an extent, which is bounded by its own conditions, not this
    // tree's. The root is marked so that the descent does not ground it
    // again.
    grounded.add(expanded0);
    final Core.Exp expanded = expanded0.accept(this);
    // The same questions `Expander.expandViaTree` used to ask of its answer:
    // every leaf bounded, and no expression reading a field its row does not
    // have. Replacing a join with a projection makes the element one component
    // where it was several, and what reads it above was
    // written for the other shape; the failure is at run time, in a record
    // selector, a long way from the pass that caused it.
    if (RelExpander.containsUnbounded(expanded)
        || Expander.misaddressed(expanded)) {
      final Core.@Nullable NamedPat pat = RelExpander.ungrounded(rel, leafPats);
      throw new CompileException(
          pat == null ? "pattern is not grounded" : Expander.notGrounded(pat),
          false,
          rel.pos);
    }
    return expanded;
  }
}

// End SuchThatShuttle.java
