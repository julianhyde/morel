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
package net.hydromatic.morel;

import net.hydromatic.morel.type.Binding;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/** Collection of bindings that have been built up in a session.
 *
 * <p>The most binding with a given name can be accessed via
 * {@link #forEach(BiConsumer)}. But older bindings may still be accessible.
 * For example:
 *
 * <blockquote><pre>{@code
 * val x = 1;
 * fun f n = n + x;
 * val x = 0;
 * }</pre></blockquote>
 *
 * <p>After running those three commands, the environment has {@code x} with
 * value 0 and {@code f} as a function that adds one to its argument. The
 * previous {@code x}, with value 1, is no longer accessible but is still needed
 * by {@code f}.
 *
 * <p>When inlining {@code f}, it would be incorrect to expand to reference the
 * next {@code x}.
 */
class BindingMap {
  private final Map<String, Binding> byName = new LinkedHashMap<>();

  /** Creates a BindingMap. */
  BindingMap() {
  }

  void put(String name, Binding binding) {
    byName.put(name, binding);
  }

  Collection<Binding> values() {
    return byName.values();
  }

  void forEach(BiConsumer<String, Binding> consumer) {
    byName.forEach(consumer);
  }

  void clear() {
    byName.clear();
  }

  void add(Binding binding) {
    put(binding.id.name, binding);
  }

  @Nullable Binding get(String name) {
    return byName.get(name);
  }
}

// End BindingMap.java
