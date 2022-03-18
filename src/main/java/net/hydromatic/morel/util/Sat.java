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
package net.hydromatic.morel.util;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Boolean satisfiability.
 */
public class Sat {
  private final Map<Integer, Variable> variablesById = new HashMap<>();
  private int nextVariable = 0;

  /** Finds an assignment of variables such that a term evaluates to true,
   * or null if there is no solution. */
  public @Nullable Map<Variable, Boolean> solve(Term term) {
    List<List<Assignment>> allAssignments = new ArrayList<>();
    for (Variable variable : variablesById.values()) {
      allAssignments.add(
          ImmutableList.of(new Assignment(variable, false),
              new Assignment(variable, true)));
    }

    final Map<Variable, Boolean> env = new LinkedHashMap<>();
    for (List<Assignment> assignments : Lists.cartesianProduct(allAssignments)) {
      assignments.forEach(a -> env.put(a.variable, a.value));
      boolean b = term.evaluate(env);
      if (b) {
        return ImmutableMap.copyOf(env);
      }
    }
    return null;
  }

  private static void gatherVariables(Term term, Set<Variable> variables) {
    switch (term.op) {
    case VARIABLE:
      variables.add((Variable) term);
      return;
    case AND:
    case OR:
      for (Term term1 : ((Node) term).terms) {
        gatherVariables(term1, variables);
      }
      return;
    case NOT:
      gatherVariables(((Not) term).term, variables);
      return;
    default:
      throw new AssertionError(term);
    }
  }

  public Variable term(String name) {
    int id = nextVariable++;
    final Variable variable = new Variable(id, name);
    variablesById.put(id, variable);
    return variable;
  }

  public Term not(Term term) {
    return new Not(term);
  }

  public Term and(Term... terms) {
    return new And(ImmutableList.copyOf(terms));
  }

  public Term or(Term... terms) {
    return new Or(ImmutableList.copyOf(terms));
  }

  /** Base class for all terms (variables, and, or, not). */
  public abstract static class Term {
    final Op op;

    Term(Op op) {
      this.op = requireNonNull(op, "op");
    }

    @Override public String toString() {
      return unparse(new StringBuilder(), 0, 0).toString();
    }

    protected abstract StringBuilder unparse(StringBuilder buf, int left,
        int right);

    public abstract boolean evaluate(Map<Variable, Boolean> env);
  }

  /** Variable. Its value can be true or false. */
  public static class Variable extends Term {
    public final int id;
    public final String name;

    Variable(int id, String name) {
      super(Op.VARIABLE);
      this.id = id;
      this.name = requireNonNull(name, "name");
    }

    @Override protected StringBuilder unparse(StringBuilder buf, int left,
        int right) {
      return buf.append(name);
    }

    @Override public boolean evaluate(Map<Variable, Boolean> env) {
      return env.get(this);
    }
  }

  /** Term that has a variable number of arguments ("and" or "or"). */
  abstract static class Node extends Term {
    public final ImmutableList<Term> terms;

    Node(Op op, ImmutableList<Term> terms) {
      super(op);
      this.terms = requireNonNull(terms);
    }

    @Override protected StringBuilder unparse(StringBuilder buf, int left,
        int right) {
      if (left > op.left || right > op.right) {
        return unparse(buf.append('('), 0, 0).append(')');
      }
      for (int i = 0; i < terms.size(); i++) {
        final Term term = terms.get(i);
        if (i > 0) {
          buf.append(op.str);
        }
        term.unparse(buf,
            i == 0 ? left : op.right,
            i == terms.size() - 1 ? right : op.left);
      }
      return buf;
    }
  }

  /** "And" term. */
  static class And extends Node {
    And(ImmutableList<Term> terms) {
      super(Op.AND, terms);
    }

    @Override public boolean evaluate(Map<Variable, Boolean> env) {
      for (Term term : terms) {
        if (!term.evaluate(env)) {
          return false;
        }
      }
      return true;
    }
  }

  /** "Or" term. */
  static class Or extends Node {
    Or(ImmutableList<Term> terms) {
      super(Op.OR, terms);
    }

    @Override public boolean evaluate(Map<Variable, Boolean> env) {
      for (Term term : terms) {
        if (term.evaluate(env)) {
          return true;
        }
      }
      return false;
    }
  }

  /** "Not" term. */
  static class Not extends Term {
    public final Term term;

    Not(Term term) {
      super(Op.NOT);
      this.term = requireNonNull(term, "term");
    }

    @Override protected StringBuilder unparse(StringBuilder buf, int left,
        int right) {
      return term.unparse(buf.append(op.str), op.right, right);
    }

    @Override public boolean evaluate(Map<Variable, Boolean> env) {
      return !term.evaluate(env);
    }
  }

  /** Operator (or type of term), with its left and right precedence and print
   * name. */
  private enum Op {
    AND(3, 4, " ∧ "),
    OR(1, 2, " ∨ "),
    NOT(5, 5, "¬"),
    VARIABLE(0, 0, "");

    public final int left;
    public final int right;
    public final String str;

    Op(int left, int right, String str) {
      this.left = left;
      this.right = right;
      this.str = str;
    }
  }

  /** Assignment of a variable to a value. */
  private static class Assignment {
    final Variable variable;
    final boolean value;

    Assignment(Variable variable, boolean value) {
      this.variable = requireNonNull(variable, "variable");
      this.value = value;
    }
  }
}

// End Sat.java
