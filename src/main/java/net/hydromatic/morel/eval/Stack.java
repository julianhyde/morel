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

/**
 * Evaluation stack for the Morel interpreter.
 *
 * <p>{@code Stack} replaces the linked-map {@link EvalEnv} as the primary
 * carrier of local variable bindings during evaluation. Local variables are
 * stored in a flat {@code Object[]} array at compile-time-computed offsets,
 * giving O(1) access without walking an environment chain.
 *
 * <p>The {@link #globalEnv} field holds an {@link EvalEnv} for top-level
 * declarations and built-ins. It is set once at the start of each statement
 * evaluation and never mutated.
 *
 * <p>In Step 2 of the stack-based evaluation migration, {@code Stack} acts as a
 * thin wrapper around {@link EvalEnv}: the {@code slots} array is empty and all
 * variable lookup falls through to {@link #globalEnv}. Subsequent steps will
 * introduce slot-based access for local variables.
 *
 * <p>{@code Stack} is NOT thread-safe. Each evaluation thread must use its own
 * {@code Stack} instance.
 */
public final class Stack {
  /**
   * The global environment holding top-level declarations and built-ins.
   *
   * <p>This is set once when evaluation begins and is never mutated. Variables
   * introduced by {@code let} or pattern-matching during evaluation are stored
   * in {@link #slots} (starting from Step 4), not here.
   */
  public final EvalEnv globalEnv;

  /**
   * Storage for local variables. Each slot holds one value.
   *
   * <p>The stack grows upward: {@code slots[top - 1]} is the most recently
   * pushed value. In Step 2, this array is always empty (all lookups go through
   * {@link #globalEnv}).
   */
  public final Object[] slots;

  /**
   * Index of the next free slot. Equivalently, the number of currently live
   * local variables on the stack.
   */
  public int top;

  /**
   * Creates a Stack with no local variable slots (Step 2 placeholder).
   *
   * @param globalEnv The environment for top-level and built-in bindings
   */
  public Stack(final EvalEnv globalEnv) {
    this.globalEnv = globalEnv;
    this.slots = new Object[0];
    this.top = 0;
  }

  /**
   * Creates a Stack with a pre-allocated slots array.
   *
   * @param globalEnv The environment for top-level and built-in bindings
   * @param capacity The number of slots to pre-allocate
   */
  public Stack(final EvalEnv globalEnv, final int capacity) {
    this.globalEnv = globalEnv;
    this.slots = new Object[capacity];
    this.top = 0;
  }

  /** Pushes {@code value} onto the stack. */
  public void push(Object value) {
    slots[top++] = value;
  }

  /** Returns the current top (for save/restore). */
  public int save() {
    return top;
  }

  /** Restores top to a previously saved value. */
  public void restore(int savedTop) {
    top = savedTop;
  }
}

// End Stack.java
