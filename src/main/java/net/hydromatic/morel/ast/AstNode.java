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

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Abstract syntax tree node. */
public abstract class AstNode {
  public final Pos pos;
  public final Op op;

  public AstNode(Pos pos, Op op) {
    this.pos = requireNonNull(pos);
    this.op = requireNonNull(op);
  }

  /**
   * Returns a copy of this node with a given position.
   *
   * <p>Only implemented for certain node types. Intended to be called only by
   * the parser, right after node creation. Returns this node if the position is
   * already correct, and in any case returns a node of the same type.
   */
  public AstNode withPos(Pos pos) {
    if (pos.equals(this.pos)) {
      return this;
    }
    throw new IllegalArgumentException(
        format("cannot change position: %s %s", op, getClass()));
  }

  /**
   * Converts this node into an ML string.
   *
   * <p>The purpose of this string is debugging. If you want to generate an
   * expression, use {@link #unparse}, which will insert parentheses as
   * necessary for operator precedence.
   *
   * <p>Derived classes <em>may</em> override, but they must produce the same
   * result; so the only reason to override is if they can do it more
   * efficiently.
   */
  @Override
  public final String toString() {
    // Marked final because you should override unparse, not toString
    return unparse(new AstWriter());
  }

  /** Converts this node into a string with fewer id ordinals. */
  public final String unparseRenumbered() {
    return unparse(new RenumberingAstWriter());
  }

  /** Converts this node into an ML string, with a given writer. */
  public final String unparse(AstWriter w) {
    return unparse(w, 0, 0).toString();
  }

  abstract AstWriter unparse(AstWriter w, int left, int right);

  /**
   * Accepts a shuttle, calling the {@link
   * net.hydromatic.morel.ast.Shuttle#visit} method appropriate to the type of
   * this node, and returning the result.
   */
  public abstract AstNode accept(Shuttle shuttle);

  /**
   * Accepts a visitor, calling the {@link
   * net.hydromatic.morel.ast.Shuttle#visit} method appropriate to the type of
   * this node, and returning the result.
   */
  public abstract void accept(Visitor visitor);

  /**
   * Implementation of {@link net.hydromatic.morel.ast.AstWriter} that remembers
   * occurrences of {@link Core.NamedPat}.
   */
  private static class RenumberingAstWriter extends AstWriter {
    final Map<String, List<Integer>> nameIds = new HashMap<>();
    final Map<String, String> names = new LinkedHashMap<>();

    private int register(String name, int i) {
      final List<Integer> list =
          nameIds.computeIfAbsent(name, id_ -> new ArrayList<>());
      int j = list.indexOf(i);
      if (j < 0) {
        j = list.size();
        list.add(i);
      }
      return j;
    }

    /**
     * Renumbers a generated binder by first occurrence, so that a plan's text
     * does not depend on what was compiled before it.
     *
     * <p>Allocation stays free -- a binder takes the next number from whatever
     * counter its maker uses, and uniqueness is all that is asked of it -- and
     * determinism is a property of the text, so it belongs to the printer. This
     * is what {@link Core.Rel#describe} does for a tree's plan.
     */
    private String rename(String name) {
      if (!Core.NamedPat.isGenerated(name)) {
        return name;
      }
      final String existing = names.get(name);
      if (existing != null) {
        return existing;
      }
      // The prefix distinguishes what one maker generated from another's;
      // each is numbered in its own sequence.
      final String prefix = name.substring(0, name.indexOf('$') + 1);
      int n = 0;
      for (String s : names.values()) {
        if (s.startsWith(prefix)) {
          ++n;
        }
      }
      final String replacement = prefix + n;
      names.put(name, replacement);
      return replacement;
    }

    @Override
    public AstWriter id(String name) {
      return super.id(rename(name));
    }

    @Override
    public AstWriter id(String name, int i) {
      return super.id(rename(name), register(name, i));
    }

    // A record label may be the name of a generated binder -- a tree's
    // projection names its element's components after them -- so a label is
    // renumbered like the id that reads it. Otherwise the two disagree, and a
    // plan reads as if it bound one name and used another.
    @Override
    public AstWriter idQuoted(String name) {
      return super.idQuoted(rename(name));
    }

    @Override
    public AstWriter idQuoted(String name, int i) {
      return super.idQuoted(rename(name), register(name, i));
    }
  }
}

// End AstNode.java
