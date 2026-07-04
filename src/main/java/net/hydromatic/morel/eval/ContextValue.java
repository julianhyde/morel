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
   * The modifiers in force after folding. For now only equality and filter
   * constraints arise, so no folding (override-replaces, relax-removes) is
   * needed yet.
   */
  private PairList<Modifier, List<Object>> active() {
    return modifiers;
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
