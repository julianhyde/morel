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

import com.google.common.collect.ImmutableSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import net.hydromatic.morel.ast.Ast;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.ast.Visitor;

/**
 * Finds the names that occur free in an expression of the abstract syntax tree.
 *
 * <p>{@link FreeFinder} answers the same question of a {@link
 * net.hydromatic.morel.ast.Core} expression, where an {@link Environment}
 * already says what is in scope. Here there is no environment yet, so the names
 * a pattern binds are tracked as the tree is walked.
 *
 * <p><b>It over-approximates, and only ever in the safe direction.</b> A name
 * that a construct binds but that this does not know to be a binder is reported
 * free. That is what a caller must be able to withstand: a caller asks whether
 * a name is free in order to decide that something is <i>not</i> safe to do, so
 * a name reported free that was in fact bound loses an opportunity and never
 * takes a wrong one. A name is never missed, because every construct's children
 * are walked, whether or not it binds.
 *
 * <p>A {@code from} is the case that gives most away: its steps bind, and
 * nothing here tracks them, so a query that rebinds the name being asked about
 * reports it free.
 */
class AstFreeFinder extends Visitor {
  /** The names bound at this point in the walk, innermost last. */
  private final Deque<String> bound = new ArrayDeque<>();

  private final ImmutableSet.Builder<String> free = ImmutableSet.builder();

  AstFreeFinder() {}

  /** Whether a name is bound at this point in the walk. */
  boolean isBound(String name) {
    return bound.contains(name);
  }

  /** Called as each name comes into scope. */
  void bind(String name) {
    bound.push(name);
  }

  /** Called as each name goes out of scope. */
  void unbind() {
    bound.pop();
  }

  /** Returns the names that occur free in an expression. */
  static Set<String> freeNames(Ast.Exp exp) {
    final AstFreeFinder finder = new AstFreeFinder();
    exp.accept(finder);
    return finder.free.build();
  }

  /** Returns whether a name occurs free in an expression. */
  static boolean isFree(Ast.Exp exp, String name) {
    return freeNames(exp).contains(name);
  }

  /** How an expression uses a name that stands for a record. */
  static class Use {
    /**
     * Whether some free use of the name is not a selection of one of the given
     * fields -- the name used bare, or a field selected that is not among them.
     */
    final boolean usesWhole;

    /**
     * The nodes that are free selections of one of the given fields, held by
     * identity. They are the nodes, and the only nodes, that a rewrite of the
     * selections may touch; a selection that looked the same but stood under a
     * binding of the name is not among them.
     */
    final Set<Ast.Apply> selections;

    Use(boolean usesWhole, Set<Ast.Apply> selections) {
      this.usesWhole = usesWhole;
      this.selections = selections;
    }
  }

  /**
   * Returns how {@code exp} uses {@code name}, given the fields that may be
   * selected from it.
   *
   * <p>This is {@link #freeNames} with the selections discounted: a selection
   * of one of {@code fields} is not a use of the record, because a record of
   * another shape still has that field. Anything else is, and a record of
   * another shape is not the record.
   */
  static Use useOf(Ast.Exp exp, String name, Set<String> fields) {
    final SelectionFinder finder = new SelectionFinder(name, fields);
    exp.accept(finder);
    return new Use(finder.usesWhole, finder.selections);
  }

  /** Finds the uses of a record that are not selections of its fields. */
  private static class SelectionFinder extends AstFreeFinder {
    private final String name;
    private final Set<String> fields;
    boolean usesWhole = false;
    final Set<Ast.Apply> selections =
        Collections.newSetFromMap(new IdentityHashMap<>());

    SelectionFinder(String name, Set<String> fields) {
      this.name = name;
      this.fields = fields;
    }

    @Override
    protected void visit(Ast.Apply apply) {
      if (apply.fn.op == Op.RECORD_SELECTOR
          && apply.arg.op == Op.ID
          && ((Ast.Id) apply.arg).name.equals(name)
          && !isBound(name)) {
        // A selection of the record. The field must be one that
        // survives; the name itself is not a use, so the argument is
        // not walked.
        if (fields.contains(((Ast.RecordSelector) apply.fn).name)) {
          selections.add(apply);
        } else {
          usesWhole = true;
        }
        return;
      }
      super.visit(apply);
    }

    @Override
    protected void visit(Ast.Id id) {
      if (id.name.equals(name) && !isBound(name)) {
        usesWhole = true;
      }
      super.visit(id);
    }
  }

  @Override
  protected void visit(Ast.Id id) {
    if (!bound.contains(id.name)) {
      free.add(id.name);
    }
  }

  @Override
  protected void visit(Ast.Match match) {
    // A match's pattern binds within its expression and nowhere else.
    // Both `fn` and `case` are made of matches, so this scopes both.
    final List<String> names = patNames(match.pat);
    names.forEach(this::bind);
    match.exp.accept(this);
    names.forEach(name -> unbind());
  }

  @Override
  protected void visit(Ast.Let let) {
    // The declarations are walked outside the scope they create. That is
    // wrong for a recursive declaration, whose name is in scope in its own
    // body, and wrong in the safe direction: the name is reported free.
    let.decls.forEach(this::accept);
    final List<String> names = new ArrayList<>();
    let.decls.forEach(decl -> declNames(decl, names));
    names.forEach(this::bind);
    let.exp.accept(this);
    names.forEach(name -> unbind());
  }

  /** Returns the names a pattern binds. */
  private static List<String> patNames(Ast.Pat pat) {
    final List<String> names = new ArrayList<>();
    pat.accept(
        new Visitor() {
          @Override
          protected void visit(Ast.IdPat idPat) {
            names.add(idPat.name);
          }
        });
    return names;
  }

  /** Adds the names a declaration binds. */
  private static void declNames(Ast.Decl decl, List<String> names) {
    decl.accept(
        new Visitor() {
          @Override
          protected void visit(Ast.IdPat idPat) {
            names.add(idPat.name);
          }
        });
  }
}

// End AstFreeFinder.java
