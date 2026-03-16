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
import java.util.Collection;
import java.util.LinkedHashMap;
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
 * <p>The compiler builds a {@code StackLayout} while traversing a function body
 * and uses it to emit {@code Codes.StackCode} nodes for local variables.
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
   * Returns a new layout that is identical to this one but assigns {@code
   * slotIndex} to {@code pat}, replacing any prior assignment for that pat.
   *
   * <p>Replacement is needed when an inner scan re-binds a variable that
   * already has a slot in an outer scope (e.g. a join variable {@code v0} that
   * appears in both the outer layout and an inner {@code from} step). The inner
   * binding takes precedence.
   */
  public StackLayout with(Core.NamedPat pat, int slotIndex) {
    if (!slotMap.containsKey(pat)) {
      return new StackLayout(
          ImmutableMap.<Core.NamedPat, Integer>builder()
              .putAll(slotMap)
              .put(pat, slotIndex)
              .build());
    }
    // Replace existing entry.
    final LinkedHashMap<Core.NamedPat, Integer> map =
        new LinkedHashMap<>(slotMap);
    map.put(pat, slotIndex);
    return new StackLayout(ImmutableMap.copyOf(map));
  }

  /**
   * Returns a new layout that is identical to this one but has all entries
   * whose variable name is in {@code names} removed.
   *
   * <p>Used when compiling GROUP result expressions: the GROUP output names
   * (group keys and aggregate names) are rebound in {@code groupEnvs} at
   * result() time, so they must be read via {@code GetCode} (from {@code
   * globalEnv}) rather than {@code StackCode} (from the stack slot that still
   * holds the pre-GROUP value).
   */
  public StackLayout without(Collection<String> names) {
    if (names.isEmpty()) {
      return this;
    }
    final LinkedHashMap<Core.NamedPat, Integer> map =
        new LinkedHashMap<>(slotMap);
    map.keySet().removeIf(pat -> names.contains(pat.name));
    return new StackLayout(ImmutableMap.copyOf(map));
  }

  /** Returns the number of slots allocated in this layout. */
  public int size() {
    return slotMap.size();
  }

  /**
   * Returns a mapping from each variable name in this layout to its runtime
   * stack offset (1-based from the current stack top), given the current {@code
   * localDepth}.
   *
   * <p>The runtime offset for slot index {@code i} is {@code localDepth - i}. A
   * value of 1 means the most recently pushed slot.
   */
  public ImmutableMap<String, Integer> nameToOffsetMap(int localDepth) {
    if (slotMap.isEmpty()) {
      return ImmutableMap.of();
    }
    final ImmutableMap.Builder<String, Integer> b = ImmutableMap.builder();
    slotMap.forEach((pat, slot) -> b.put(pat.name, localDepth - slot));
    return b.build();
  }
}

// End StackLayout.java
