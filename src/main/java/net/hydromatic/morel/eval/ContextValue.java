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

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.PairList;

/** Runtime value of a {@code cx} (context). */
class ContextValue {
  /** The parameter in force. */
  final Object param;

  /**
   * The modifiers applied to the base context, each paired with its runtime
   * parameter values, in application order; empty means "match all".
   */
  final PairList<Modifier, List<Object>> modifiers;

  ContextValue(Object param, PairList<Modifier, List<Object>> modifiers) {
    this.param = param;
    this.modifiers = modifiers;
  }

  /** The match-all context, with a unit parameter. */
  static final ContextValue MATCH_ALL =
      new ContextValue(Unit.INSTANCE, PairList.of());

  /**
   * Returns a context with a modifier and its runtime parameter values appended
   * (a {@code restrict} adds a filter).
   */
  ContextValue plus(Modifier modifier, List<Object> params) {
    final PairList<Modifier, List<Object>> newModifiers = PairList.of();
    newModifiers.addAll(modifiers);
    newModifiers.add(modifier, params);
    return new ContextValue(param, newModifiers);
  }

  /** Returns a context with any modifier on {@code label} removed (a relax). */
  ContextValue minus(String label) {
    final PairList<Modifier, List<Object>> newModifiers = PairList.of();
    modifiers.forEach(
        (modifier, params) -> {
          if (!label.equals(modifier.label)) {
            newModifiers.add(modifier, params);
          }
        });
    return new ContextValue(param, newModifiers);
  }

  /**
   * Returns a context with a measure's deferred modifiers applied in order: a
   * relax removes its label, any other modifier is appended.
   */
  ContextValue applyAll(PairList<Modifier, List<Object>> ops) {
    ContextValue c = this;
    for (int i = 0; i < ops.size(); i++) {
      final Modifier modifier = ops.left(i);
      c =
          modifier instanceof Modifier.Relax
              ? c.minus(requireNonNull(modifier.label))
              : c.plus(modifier, ops.right(i));
    }
    return c;
  }

  /**
   * The modifiers in force after folding: one equality per label, a later
   * equality (an {@code override}) superseding an earlier one (a group key or
   * override) on the same label. Relax removals were already applied when the
   * measure was evaluated (see {@link #applyAll}).
   */
  private PairList<Modifier, List<Object>> active() {
    final PairList<Modifier, List<Object>> result = PairList.of();
    for (int i = 0; i < modifiers.size(); i++) {
      if (modifiers.left(i) instanceof Modifier.Equality
          && supersededLater(i)) {
        continue;
      }
      result.add(modifiers.left(i), modifiers.right(i));
    }
    return result;
  }

  /**
   * Whether a later equality has the same label as the modifier at {@code i}.
   */
  private boolean supersededLater(int i) {
    final String label = requireNonNull(modifiers.left(i).label);
    for (int j = i + 1; j < modifiers.size(); j++) {
      if (modifiers.left(j) instanceof Modifier.Equality
          && label.equals(modifiers.left(j).label)) {
        return true;
      }
    }
    return false;
  }

  /** Whether a base element satisfies every active constraint. */
  boolean test(Stack stack, Object element) {
    return active()
        .allMatch((modifier, params) -> modifier.test(stack, element, params));
  }

  /**
   * Renders the context per the portable {@code Table.toString} spec: the
   * active items, sorted and comma-separated, wrapped in braces; "{@code {}}"
   * when empty.
   */
  String render(TypeSystem typeSystem) {
    final SortedSet<String> items = new TreeSet<>();
    active()
        .forEach(
            (modifier, params) -> items.add(modifier.item(params, typeSystem)));
    return items.isEmpty() ? "{}" : "{" + String.join(", ", items) + "}";
  }

  @Override
  public String toString() {
    return "cx";
  }
}

// End ContextValue.java
