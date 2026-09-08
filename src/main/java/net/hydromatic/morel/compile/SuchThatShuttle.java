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
import java.util.List;
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

  SuchThatShuttle(
      TypeSystem typeSystem, Environment env, NameGenerator nameGenerator) {
    this(typeSystem, env, nameGenerator, false, false);
  }

  private SuchThatShuttle(
      TypeSystem typeSystem,
      Environment env,
      NameGenerator nameGenerator,
      boolean inRecursiveFunction,
      boolean rowsUnused) {
    super(typeSystem, env);
    this.nameGenerator = nameGenerator;
    this.inRecursiveFunction = inRecursiveFunction;
    this.rowsUnused = rowsUnused;
  }

  @Override
  protected EnvShuttle push(Environment env) {
    return new SuchThatShuttle(
        typeSystem, env, nameGenerator, inRecursiveFunction, rowsUnused);
  }

  @Override
  protected Core.Exp visit(Core.Apply apply) {
    if (apply.isCallTo(BuiltIn.RELATIONAL_NON_EMPTY)
        || apply.isCallTo(BuiltIn.RELATIONAL_EMPTY)) {
      final SuchThatShuttle inner =
          new SuchThatShuttle(
              typeSystem, env, nameGenerator, inRecursiveFunction, true);
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
            typeSystem, env.bindAll(bindings), nameGenerator, true, rowsUnused);
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
    if (inRecursiveFunction || !RelExpander.containsUnbounded(rel)) {
      return null;
    }
    final boolean rowsUsed = !rowsUnused;
    try {
      final Core.Exp expanded =
          RelExpander.expand(
              typeSystem, env, rel, rowsUsed, RelExpander.leafPats(rel));
      // The same questions `Expander.expandViaTree` asks of its answer: every
      // leaf bounded, and no expression reading a field its row does not have.
      // Replacing a join with a projection makes the element one component
      // where it was several (discussion.md §16), and what reads it above was
      // written for the other shape; `rebuild` does not rebase them, and the
      // failure is at run time, in a record selector, a long way from the pass
      // that caused it. The reading happens in the lowered form, so that is
      // what to ask; the lowering here is thrown away.
      if (!RelExpander.containsUnbounded(expanded)
          && !Expander.misaddressed(
              RelLowerer.lowerAll(typeSystem, nameGenerator, expanded))) {
        // Descend into what came back, to ground the trees nested in it.
        return expanded.accept(this);
      }
    } catch (CompileException e) {
      // The tree engine declined; the step list is the fallback, as it is in
      // Expander.expandFrom.
    }
    // What the tree engine will not take, the step list's engine still can:
    // `from n where isNum n`, where the predicate is a function that the
    // inliner leaves alone because it reads a global. Lower the tree and hand
    // it over. The round trip that goal 3 removes is paid only here, on the
    // queries the tree engine declines, and it shrinks as that engine grows.
    //
    // Lower it deeply: the step list's engine reads the constraints inside a
    // nested query -- `exists x where (exists y where (x, y) elem pairs)`
    // grounds x from the inner query's constraint -- and a tree left in a
    // condition is opaque to it.
    final Core.Exp lowered =
        RelLowerer.lowerAll(typeSystem, nameGenerator, rel);
    if (!(lowered instanceof Core.From)) {
      return null;
    }
    final Core.From expanded =
        Expander.expandFrom(
            typeSystem, nameGenerator, env, (Core.From) lowered, rowsUsed);
    return super.visit(expanded);
  }

  @Override
  protected Core.Exp visit(Core.From from) {
    // Skip expansion for "from" expressions inside recursive function
    // definitions. These are part of the function's logic, not queries to
    // execute. The outer query will handle transitive closure detection.
    if (inRecursiveFunction) {
      return super.visit(from);
    }

    final Core.From from2 =
        Expander.expandFrom(typeSystem, nameGenerator, env, from, !rowsUnused);

    // Expand subqueries.
    return super.visit(from2);
  }
}

// End SuchThatShuttle.java
