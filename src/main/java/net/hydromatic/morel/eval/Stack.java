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
package net.hydromatic.morel.eval;

import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Visitor;
import net.hydromatic.morel.util.Pair;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static net.hydromatic.morel.util.Static.nextPowerOfTwo;
import static net.hydromatic.morel.util.Static.skip;

/** Where all the data lives at runtime.
 *
 * <p>It should be called a stack, or an environment, or something.
 * It's definitely not a heap.
 *
 * <p>Tagged 'final' to encourage the JVM to inline method calls.
 * Data members are public and mutable, so that clients can manipulate the stack
 * as efficiently as possible. We hope that the JVM will cache data members in
 * registers. */
public final class Stack {
  /** Initial number of slots. Must be a power of two, and greater than 1. */
  private static final int INITIAL_SLOT_COUNT = 16;

  public EvalEnv env;
  public Object[] slots;
  public int top;

  public Stack() {
  }

  public static Stack of(EvalEnv env) {
    Stack stack = new Stack();
    stack.env = env;
    stack.slots = new Object[INITIAL_SLOT_COUNT];
    stack.top = 0;
    return stack;
  }

  public void push(Object o) {
    ensureCapacity(top);
    slots[top++] = o;
  }

  public Object pop() {
    return slots[--top];
  }

  public Object peek() {
    return slots[top - 1];
  }

  public int save() {
    return top;
  }

  public void restore(int top) {
    this.top = top;
  }

  // TODO: in Closure, don't copy the whole stack
  public Stack fix() {
    Stack stack = new Stack();
    stack.env = env;
    stack.slots = slots.clone();
    stack.top = top;
    return stack;
  }

  public Mutator bindMutablePat(Core.Pat pat) {
    if (pat instanceof Core.IdPat) {
      // Pattern is simple; use a simple implementation.
      return new SingletonMutator(((Core.IdPat) pat).name);
    }

    // TODO: add a simple mutator for simple, unconditional patterns such as
    // (x, y)

    // TODO: compute the name list at compile time
    final List<String> names = new ArrayList<>();
    pat.accept(new Visitor() {
      @Override protected void visit(Core.IdPat idPat) {
        names.add(idPat.name);
      }
      @Override protected void visit(Core.AsPat asPat) {
        names.add(asPat.name);
        super.visit(asPat);
      }
    });
    return new PatternMutator(pat, names);
  }

  public Mutator bindMutableArray(List<String> names) {
    if (names.size() == 1) {
      return new SingletonMutator(names.get(0));
    } else {
      return new ArrayMutator(names);
    }
  }

  /** Assigns elements to a region of this Stack. */
  public abstract static class Mutator {
    abstract boolean setOpt(Object element);
    abstract void close();

    void set(Object value) {
      if (!setOpt(value)) {
        // If this error happens, perhaps your code should be calling "setOpt"
        // and handling a false result appropriately.
        throw new AssertionError("bind failed");
      }
    }
  }

  /** Assigns elements to a region of this Stack. */
  class SingletonMutator extends Mutator {
    final int i = top++;

    SingletonMutator(String name) {
      // TODO: we don't need 'name'
    }

    @Override boolean setOpt(Object element) {
      slots[i] = element;
      return true;
    }

    @Override void close() {
      --top;
    }
  }

  class ArrayMutator extends Mutator {
    final int save;
    final List<String> names;

    ArrayMutator(List<String> names) {
      this.names = names;
      save = top;
      top += names.size();
      ensureCapacity(top);
    }

    @Override boolean setOpt(Object element) {
      System.arraycopy(element, 0, slots, save, names.size());
      return true;
    }

    @Override void close() {
      top = save;
    }
  }

  /** Ensures that the slot array is long enough. Call after moving top up. */
  private void ensureCapacity(int capacity) {
    if (capacity >= slots.length) {
      final int newLength = nextPowerOfTwo(Math.max(slots.length * 2, capacity));
      slots = Arrays.copyOf(slots, newLength);
    }
  }

  class PatternMutator extends Mutator {
    private final Core.Pat pat;
    private final int base;
    private int i;

    PatternMutator(Core.Pat pat, List<String> names) {
      this.pat = pat;
      this.base = top;
      top += names.size();
      ensureCapacity(top);
    }

    @Override void close() {
      top = base;
    }

    @Override boolean setOpt(Object value) {
      i = base;
      return bindRecurse(pat, value);
    }

    private boolean bindRecurse(Core.Pat pat, Object argValue) {
      final List<Object> listValue;
      final Core.LiteralPat literalPat;
      switch (pat.op) {
      case ID_PAT:
        slots[i++] = argValue;
        return true;

      case AS_PAT:
        final Core.AsPat asPat = (Core.AsPat) pat;
        final int oldSlot = i++;
        if (bindRecurse(asPat.pat, argValue)) {
          slots[oldSlot] = argValue;
          return true;
        } else {
          return false;
        }

      case WILDCARD_PAT:
        return true;

      case BOOL_LITERAL_PAT:
      case CHAR_LITERAL_PAT:
      case STRING_LITERAL_PAT:
        literalPat = (Core.LiteralPat) pat;
        return literalPat.value.equals(argValue);

      case INT_LITERAL_PAT:
        literalPat = (Core.LiteralPat) pat;
        return ((BigDecimal) literalPat.value).intValue() == (Integer) argValue;

      case REAL_LITERAL_PAT:
        literalPat = (Core.LiteralPat) pat;
        return ((BigDecimal) literalPat.value).doubleValue() == (Double) argValue;

      case TUPLE_PAT:
        final Core.TuplePat tuplePat = (Core.TuplePat) pat;
        listValue = (List) argValue;
        for (Pair<Core.Pat, Object> pair : Pair.zip(tuplePat.args, listValue)) {
          if (!bindRecurse(pair.left, pair.right)) {
            return false;
          }
        }
        return true;

      case RECORD_PAT:
        final Core.RecordPat recordPat = (Core.RecordPat) pat;
        listValue = (List) argValue;
        for (Pair<Core.Pat, Object> pair
            : Pair.zip(recordPat.args, listValue)) {
          if (!bindRecurse(pair.left, pair.right)) {
            return false;
          }
        }
        return true;

      case LIST_PAT:
        final Core.ListPat listPat = (Core.ListPat) pat;
        listValue = (List) argValue;
        if (listValue.size() != listPat.args.size()) {
          return false;
        }
        for (Pair<Core.Pat, Object> pair : Pair.zip(listPat.args, listValue)) {
          if (!bindRecurse(pair.left, pair.right)) {
            return false;
          }
        }
        return true;

      case CONS_PAT:
        final Core.ConPat infixPat = (Core.ConPat) pat;
        @SuppressWarnings("unchecked") final List<Object> consValue =
            (List) argValue;
        if (consValue.isEmpty()) {
          return false;
        }
        final Object head = consValue.get(0);
        final List<Object> tail = skip(consValue);
        List<Core.Pat> patArgs = ((Core.TuplePat) infixPat.pat).args;
        return bindRecurse(patArgs.get(0), head)
            && bindRecurse(patArgs.get(1), tail);

      case CON0_PAT:
        final Core.Con0Pat con0Pat = (Core.Con0Pat) pat;
        final List con0Value = (List) argValue;
        return con0Value.get(0).equals(con0Pat.tyCon);

      case CON_PAT:
        final Core.ConPat conPat = (Core.ConPat) pat;
        final List conValue = (List) argValue;
        return conValue.get(0).equals(conPat.tyCon)
            && bindRecurse(conPat.pat, conValue.get(1));

      default:
        throw new AssertionError("cannot compile " + pat.op + ": " + pat);
      }
    }
  }
}

// End Stack.java
