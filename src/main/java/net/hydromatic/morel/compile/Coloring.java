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

import java.util.List;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.ast.Visitor;
import net.hydromatic.morel.foreign.RelList;
import net.hydromatic.morel.type.Binding;
import org.jspecify.annotations.Nullable;

/**
 * Says which engine runs which part of a tree, by putting a {@link
 * Core.Boundary} where the answer changes.
 *
 * <p>The rule is the one the design fixes: push the largest subtrees the
 * profile permits. There is no cost model, so there is nothing to weigh; a
 * subtree either can go or cannot.
 *
 * <p>A node can go where the profile permits the node and every input can go. A
 * <b>leaf</b> can go where it is data the engine already holds -- a field of a
 * foreign value whose fields are that engine's relations. This is the
 * <i>anchored</i> half of the question: the computation moves to the data. The
 * <i>shipped</i> half, where data moves to the computation and a leaf takes its
 * colour from what consumes it, is what a profile that can be given data would
 * add, and no profile here can be.
 */
public class Coloring {
  private Coloring() {}

  /**
   * Returns a rule that colors a tree for an engine.
   *
   * <p>It is a whole-tree rule: where a node can go, so can everything under
   * it, and the boundary belongs at the top of that region rather than at each
   * node in it, which is a fact about the tree and not about one node.
   */
  public static RelRule rule(Profile profile) {
    return new RelRule() {
      @Override
      public String name() {
        return "Color(" + profile.name + ")";
      }

      @Override
      public boolean wholeTree() {
        return true;
      }

      @Override
      public Core.@Nullable Exp apply(Context cx, Core.Rel rel) {
        if (contains(rel, Op.BOUNDARY)) {
          // Colored already; a second firing would nest boundaries.
          return null;
        }
        final Core.Exp exp = color(cx, profile, rel);
        return exp == rel ? null : exp;
      }
    };
  }

  /** Wraps the largest subtrees of {@code exp} that the engine can run. */
  private static Core.Exp color(
      RelRule.Context cx, Profile profile, Core.Exp exp) {
    if (canGo(cx, profile, exp)) {
      return core.boundary(profile.name, exp);
    }
    if (!(exp instanceof Core.Rel)) {
      return exp;
    }
    final Core.Rel rel = (Core.Rel) exp;
    return RelRules.copy(
        cx.typeSystem(), rel, input -> color(cx, profile, input), e -> e);
  }

  /** Returns whether the engine can run an expression, and all below it. */
  private static boolean canGo(
      RelRule.Context cx, Profile profile, Core.Exp exp) {
    if (!(exp instanceof Core.Rel)) {
      return anchored(cx, exp);
    }
    final Core.Rel rel = (Core.Rel) exp;
    if (!profile.permits(rel)) {
      return false;
    }
    for (Core.Exp input : rel.inputs()) {
      if (!canGo(cx, profile, input)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Returns whether a leaf is data the engine already holds.
   *
   * <p>A field of a foreign value, {@code #depts scott}, whose value is one of
   * that engine's relations. A collection the query wrote is not: it is
   * Morel's, and no profile here can be given it.
   */
  private static boolean anchored(RelRule.Context cx, Core.Exp exp) {
    if (exp.op != Op.APPLY) {
      return false;
    }
    final Core.Apply apply = (Core.Apply) exp;
    if (apply.fn.op != Op.RECORD_SELECTOR || apply.arg.op != Op.ID) {
      return false;
    }
    final int slot = ((Core.RecordSelector) apply.fn).slot;
    final Core.NamedPat pat = ((Core.Id) apply.arg).idPat;
    final @Nullable Binding binding = cx.env().getOpt(pat);
    if (binding == null || !(binding.value instanceof List)) {
      return false;
    }
    final List<?> fields = (List<?>) binding.value;
    return slot < fields.size() && fields.get(slot) instanceof RelList;
  }

  /** Returns whether a tree contains a node of a given kind. */
  private static boolean contains(Core.Rel rel, Op op) {
    final boolean[] found = {false};
    rel.accept(
        new Visitor() {
          @Override
          protected void visitRel(Core.Rel rel) {
            if (rel.op == op) {
              found[0] = true;
            }
          }
        });
    return found[0];
  }
}

// End Coloring.java
