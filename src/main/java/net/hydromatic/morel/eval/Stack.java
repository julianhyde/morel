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

import java.util.List;

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
  public EvalEnv env;
  public Object[] slots;
  public int top;

  public Stack() {
  }

  public static Stack of(EvalEnv env) {
    Stack stack = new Stack();
    stack.env = env;
    stack.slots = new Object[100];
    stack.top = 0;
    return stack;
  }

  public void push(Object o) {
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
    return new SingletonMutator();
  }

  public Mutator bindMutableArray(List<String> names) {
    if (names.size() == 1) {
      return new SingletonMutator();
    } else {
      return new ArrayMutator(names);
    }
  }

  /** Assigns elements to a region of this Stack. */
  public abstract class Mutator {
    public abstract boolean setOpt(Object element);
    public abstract void close();

    public void set(Object row) {
      setOpt(row);
    }
  }

  /** Assigns elements to a region of this Stack. */
  public class SingletonMutator extends Mutator {
    final int i = top++;

    @Override public boolean setOpt(Object element) {
      slots[i] = element;
      return true;
    }

    @Override public void close() {
      --top;
    }
  }

  public class ArrayMutator extends Mutator {
    final int save;
    final List<String> names;

    public ArrayMutator(List<String> names) {
      this.names = names;
      save = top;
      top += names.size();
    }

    @Override public boolean setOpt(Object element) {
      System.arraycopy(element, 0, slots, save, names.size());
      return true;
    }

    @Override public void close() {
      top = save;
    }
  }
}

// End Stack.java
