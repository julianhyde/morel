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

import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import net.hydromatic.morel.type.TypeSystem;

/** Runtime value of a {@code cx} (context). */
class ContextValue {
  /** The parameter in force. */
  final Object param;

  /**
   * The modifiers applied to the base context, in application order; empty
   * means "match all".
   */
  final List<Modifier.Applied> modifiers;

  ContextValue(Object param, List<Modifier.Applied> modifiers) {
    this.param = param;
    this.modifiers = modifiers;
  }

  /** The match-all context, with a unit parameter. */
  static final ContextValue MATCH_ALL =
      new ContextValue(Unit.INSTANCE, ImmutableList.of());

  /**
   * The modifiers in force after §6 folding. For now only equality constraints
   * arise, so no folding (override-replaces, relax-removes) is needed yet.
   */
  private List<Modifier.Applied> active() {
    return modifiers;
  }

  /** Whether a base element satisfies every active constraint. */
  boolean test(Object element) {
    for (Modifier.Applied a : active()) {
      if (!a.test(element)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Renders the context per the portable {@code Table.toString} spec: the
   * active items, sorted and comma-separated, wrapped in braces; "{@code {}}"
   * when empty. Anonymous filters collapse to a single "{@code ?}".
   */
  String render(TypeSystem typeSystem) {
    final SortedSet<String> items = new TreeSet<>();
    for (Modifier.Applied a : active()) {
      final String item = a.item(typeSystem);
      if (item != null) {
        items.add(item);
      }
    }
    return items.isEmpty() ? "{}" : "{" + String.join(", ", items) + "}";
  }

  @Override
  public String toString() {
    return "cx";
  }
}

// End ContextValue.java
