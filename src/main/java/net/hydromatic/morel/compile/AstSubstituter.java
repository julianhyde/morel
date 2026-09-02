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

import static net.hydromatic.morel.ast.AstBuilder.ast;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import net.hydromatic.morel.ast.Ast;
import net.hydromatic.morel.ast.Shuttle;
import net.hydromatic.morel.ast.Visitor;
import net.hydromatic.morel.type.TypeSystem;
import org.jspecify.annotations.Nullable;

/**
 * Substitutes an expression for the free occurrences of a name in an expression
 * of the abstract syntax tree.
 *
 * <p>A {@link Shuttle} has no scopes; this one tracks the names a pattern binds
 * as the tree is walked, so that an occurrence under a binding of the name is
 * left alone. That is what makes the substitution mean what it says: {@code r[r
 * := e]} replaces the {@code r} that the reader would call this {@code r}, and
 * not one that some inner {@code let} or {@code fn} rebound.
 *
 * <p>A query's steps bind too. They are followed as far as a scan, whose
 * pattern is in scope for the steps after it. Past a step that makes a scope of
 * its own -- {@code yield}, {@code group}, {@code compute}, {@code into},
 * {@code through} -- the names in scope are not known, so a substitution made
 * there may or may not be the one intended; {@link #unsafe} says that one was.
 */
class AstSubstituter extends Shuttle {
  private final TypeSystem typeSystem;
  private final String name;
  private final Ast.Exp exp;

  /** The names bound at this point in the walk, innermost first. */
  private final Deque<String> bound = new ArrayDeque<>();

  /** Whether the walk is past a step that bound names it does not know. */
  private boolean unknownScope;

  /**
   * Whether a substitution was made where the names in scope were not known,
   * and so may have replaced something other than what was meant.
   */
  boolean unsafe;

  private AstSubstituter(TypeSystem typeSystem, String name, Ast.Exp exp) {
    super(typeSystem);
    this.typeSystem = typeSystem;
    this.name = name;
    this.exp = exp;
  }

  /**
   * Returns {@code target} with {@code exp} substituted for the free
   * occurrences of {@code name}, or null if a substitution was made where the
   * names in scope were not known.
   */
  static Ast.@Nullable Exp substitute(
      TypeSystem typeSystem, Ast.Exp target, String name, Ast.Exp exp) {
    final AstSubstituter substituter =
        new AstSubstituter(typeSystem, name, exp);
    final Ast.Exp target2 = target.accept(substituter);
    return substituter.unsafe ? null : target2;
  }

  @Override
  protected Ast.Exp visit(Ast.Id id) {
    if (!id.name.equals(name) || bound.contains(name)) {
      return id;
    }
    if (unknownScope) {
      unsafe = true;
    }
    // A fresh copy for each occurrence. Later passes key what they deduce on
    // the identity of a node, so one node in two places is one node too few.
    return exp.accept(new Shuttle(typeSystem));
  }

  @Override
  protected Ast.Match visit(Ast.Match match) {
    // A match's pattern binds within its expression and nowhere else.
    // Both `fn` and `case` are made of matches, so this scopes both.
    final List<String> names = patNames(match.pat);
    return ast.match(match.pos, match.pat, scoped(names, match.exp));
  }

  @Override
  protected Ast.FunMatch visit(Ast.FunMatch funMatch) {
    final List<String> names = new ArrayList<>();
    funMatch.patList.forEach(pat -> names.addAll(patNames(pat)));
    names.add(funMatch.name);
    return ast.funMatch(
        funMatch.pos,
        funMatch.name,
        funMatch.patList,
        funMatch.returnType,
        scoped(names, funMatch.exp));
  }

  @Override
  protected Ast.Let visit(Ast.Let let) {
    final List<String> names = new ArrayList<>();
    let.decls.forEach(decl -> declNames(decl, names));
    // A recursive declaration is in scope in its own body, so its own names
    // are bound while the declarations are walked; a plain one is not.
    final boolean rec = let.decls.stream().anyMatch(AstSubstituter::isRec);
    names.forEach(bound::push);
    final List<Ast.Decl> decls =
        rec ? visitList(let.decls) : unscoped(names, let.decls);
    final Ast.Exp exp2 = let.exp.accept(this);
    names.forEach(name -> bound.pop());
    return ast.let(let.pos, decls, exp2);
  }

  @Override
  protected Ast.Exp visit(Ast.From from) {
    return ast.from(from.pos, steps(from.steps));
  }

  @Override
  protected Ast.Exp visit(Ast.Exists exists) {
    return ast.exists(exists.pos, steps(exists.steps));
  }

  @Override
  protected Ast.Exp visit(Ast.Forall forall) {
    return ast.forall(forall.pos, steps(forall.steps));
  }

  /**
   * Walks the steps of a query, in order, with each scan's pattern in scope for
   * the steps that follow.
   */
  private List<Ast.FromStep> steps(List<Ast.FromStep> steps) {
    final boolean unknownScope0 = unknownScope;
    final List<Ast.FromStep> steps2 = new ArrayList<>();
    int depth = 0;
    for (Ast.FromStep step : steps) {
      if (step instanceof Ast.Scan) {
        final Ast.Scan scan = (Ast.Scan) step;
        // The collection is outside the scope the pattern creates; the
        // 'on' condition is inside it.
        final Ast.Exp exp2 = scan.exp == null ? null : scan.exp.accept(this);
        final List<String> names = patNames(scan.pat);
        names.forEach(bound::push);
        depth += names.size();
        final Ast.Exp condition2 =
            scan.condition == null ? null : scan.condition.accept(this);
        steps2.add(ast.scan(scan.pos, scan.op, scan.pat, exp2, condition2));
      } else {
        steps2.add((Ast.FromStep) step.accept(this));
        if (replacesScope(step)) {
          unknownScope = true;
        }
      }
    }
    for (int i = 0; i < depth; i++) {
      bound.pop();
    }
    unknownScope = unknownScope0;
    return steps2;
  }

  /**
   * Whether a step gives the steps that follow it a scope of its own making.
   */
  private static boolean replacesScope(Ast.FromStep step) {
    switch (step.op) {
      case YIELD:
      case COMPUTE:
      case GROUP:
      case INTO:
      case THROUGH:
        return true;
      default:
        return false;
    }
  }

  /** Walks an expression with {@code names} in scope. */
  private Ast.Exp scoped(List<String> names, Ast.Exp exp) {
    names.forEach(bound::push);
    final Ast.Exp exp2 = exp.accept(this);
    names.forEach(name -> bound.pop());
    return exp2;
  }

  /** Walks declarations with {@code names} out of scope. */
  private List<Ast.Decl> unscoped(List<String> names, List<Ast.Decl> decls) {
    names.forEach(name -> bound.pop());
    final List<Ast.Decl> decls2 = visitList(decls);
    names.forEach(bound::push);
    return decls2;
  }

  private static boolean isRec(Ast.Decl decl) {
    return decl instanceof Ast.ValDecl && ((Ast.ValDecl) decl).rec;
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

// End AstSubstituter.java
