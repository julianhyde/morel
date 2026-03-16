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

import java.util.Arrays;
import org.checkerframework.checker.nullness.qual.Nullable;

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
 * <p>{@code Stack} is shared across a function call chain. Built-in functions
 * that do not bind local variables pass the {@code Stack} through unchanged;
 * user-defined functions push their arguments and let-bindings onto {@link
 * #slots} and pop them on return.
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
   * in {@link #slots}, not here.
   */
  public final EvalEnv globalEnv;

  /**
   * The current session, derived from {@link #globalEnv} at construction time.
   *
   * <p>Cached here so that built-in functions can access the session via {@code
   * stack.session} without calling {@link EvalEnv#getSession()} on every
   * invocation.
   *
   * <p>May be {@code null} when a stack is created from an empty environment
   * (e.g. during compile-time constant evaluation).
   */
  public final @Nullable Session session;

  /**
   * Storage for local variables. Each slot holds one value.
   *
   * <p>The stack grows upward: {@code slots[top - 1]} is the most recently
   * pushed value.
   */
  public final Object[] slots;

  /**
   * Index of the next free slot. Equivalently, the number of currently live
   * local variables on the stack.
   */
  public int top;

  /**
   * Creates a Stack with a pre-allocated slots array.
   *
   * @param globalEnv The environment for top-level and built-in bindings
   * @param capacity The number of slots to pre-allocate
   */
  public Stack(final EvalEnv globalEnv, final int capacity) {
    this.globalEnv = globalEnv;
    this.session = (Session) globalEnv.getOpt(EvalEnv.SESSION);
    this.slots = new Object[capacity];
    this.top = 0;
  }

  /**
   * Creates a Stack that shares the slots array of a parent stack but has a
   * different {@link #globalEnv}.
   *
   * <p>Used by row sinks to create an inner evaluation context: outer variables
   * remain accessible via the shared {@link #slots} (StackCode nodes), while
   * new iteration-variable bindings are in the extended {@code globalEnv}
   * ({@code Codes.get} nodes).
   *
   * @param globalEnv Extended env that includes the new iteration variable
   * @param parentSlots The slots array from the parent stack (shared by
   *     reference)
   * @param top The current stack top, copied from the parent
   */
  public Stack(
      final EvalEnv globalEnv, final Object[] parentSlots, final int top) {
    this.globalEnv = globalEnv;
    this.session = (Session) globalEnv.getOpt(EvalEnv.SESSION);
    this.slots = parentSlots;
    this.top = top;
  }

  /**
   * Returns the current global environment.
   *
   * <p>If a {@link Session} is present, returns {@link Session#globalEnv},
   * which may have been temporarily extended by row-sink or aggregate code.
   * Otherwise falls back to {@link #globalEnv}.
   */
  public EvalEnv currentEnv() {
    return session != null ? session.globalEnv : globalEnv;
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

  /**
   * Returns this stack if it already has room for {@code needed} slots above
   * {@link #top}, or a new stack with a grown {@link #slots} array otherwise.
   *
   * <p>When compile-time slot estimates are accurate this method always returns
   * {@code this}; it exists as a safe fallback for cases (e.g. deep recursion)
   * where the required depth was not predictable at compile time.
   */
  public Stack ensureSize(int needed) {
    if (slots.length >= top + needed) {
      return this;
    }
    return new Stack(globalEnv, Arrays.copyOf(slots, top + needed), top);
  }
}

// End Stack.java
