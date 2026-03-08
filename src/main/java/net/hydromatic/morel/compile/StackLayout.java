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

import com.google.common.collect.ImmutableMap;
import net.hydromatic.morel.ast.Core;

/**
 * Maps local variable patterns to fixed slot indices within a stack frame.
 *
 * <p>During compilation, {@code StackLayout} tracks which {@link Core.NamedPat}
 * instances have been assigned stack slots. The slot index is used at runtime
 * to access {@link net.hydromatic.morel.eval.Stack#slots}.
 *
 * <p>{@code StackLayout} is immutable. Adding a new slot produces a new {@code
 * StackLayout} instance; restoring a previous layout is done by returning to a
 * previously-saved reference.
 *
 * <p>Created in Step 3; first used in Step 4 when the compiler begins emitting
 * {@code Codes.StackCode} for local variables.
 */
public class StackLayout {
  /** An empty layout with no allocated slots. */
  public static final StackLayout EMPTY = new StackLayout(ImmutableMap.of());

  private final ImmutableMap<Core.NamedPat, Integer> slotMap;

  private StackLayout(ImmutableMap<Core.NamedPat, Integer> slotMap) {
    this.slotMap = slotMap;
  }

  /**
   * Returns the stack slot index assigned to {@code pat}, or {@code -1} if
   * {@code pat} has not been allocated a slot.
   */
  public int get(Core.NamedPat pat) {
    final Integer slot = slotMap.get(pat);
    return slot == null ? -1 : slot;
  }

  /**
   * Returns a new layout that is identical to this one but also assigns {@code
   * slotIndex} to {@code pat}.
   */
  public StackLayout with(Core.NamedPat pat, int slotIndex) {
    return new StackLayout(
        ImmutableMap.<Core.NamedPat, Integer>builder()
            .putAll(slotMap)
            .put(pat, slotIndex)
            .build());
  }

  /** Returns the number of slots allocated in this layout. */
  public int size() {
    return slotMap.size();
  }
}

// End StackLayout.java
