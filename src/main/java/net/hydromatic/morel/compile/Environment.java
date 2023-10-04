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

import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.eval.EvalEnv;
import net.hydromatic.morel.eval.Unit;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.util.Pair;

import com.google.common.collect.ImmutableList;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static com.google.common.collect.Lists.reverse;

/** Environment for validation/compilation.
 *
 * <p>Every environment is immutable; when you call {@link #bind}, a new
 * environment is created that inherits from the previous environment.
 * The new environment may obscure bindings in the old environment, but
 * neither the new nor the old will ever change.
 *
 * <p>To create an empty environment, call {@link Environments#empty()}.
 *
 * @see TypeResolver.TypeEnv
 * @see EvalEnv
 */
public abstract class Environment {
  /** Visits this environment and its ancestors. */
  abstract void forEachAncestor(Consumer<Environment> consumer);

  /** Visits every variable binding in this environment (including those
   * inherited from its ancestor environments).
   *
   * <p>Bindings that are obscured by more recent bindings of the same name
   * are visited, but after the more obscuring bindings. */
  abstract void forEachBinding(Consumer<Binding> consumer);

  /** Converts this environment to a string.
   *
   * <p>This method does not override the {@link #toString()} method; if we did,
   * debuggers would invoke it automatically, burning lots of CPU and memory. */
  public String asString() {
    final StringBuilder b = new StringBuilder();
    getValueMap().forEach((k, v) ->
        b.append(v).append("\n"));
    return b.toString();
  }

  /** Returns the binding of {@code name} if bound, null if not. */
  public abstract @Nullable Binding getOpt(String name);

  /** Returns the binding of {@code id} if bound, null if not. */
  public final @Nullable Binding getOpt(Core.NamedPat id) {
    Pair<Binding, Environment> p = getOpt2(id);
    return p == null ? null : p.left;
  }

  /** Returns the binding and environment of {@code id} if bound,
   * null if not. */
  public abstract @Nullable Pair<Binding, Environment> getOpt2(Core.NamedPat id);

  /** Creates an environment that is the same as a given environment, plus one
   * more variable. */
  public Environment bind(Core.IdPat id, Object value) {
    return bind(Binding.of(id, value));
  }

  protected Environment bind(Binding binding) {
    return new Environments.SubEnvironment(this, binding);
  }

  /** Calls a consumer for each variable and its type.
   * Does not visit obscured bindings. */
  public void forEachType(BiConsumer<String, Type> consumer) {
    final Set<String> names = new HashSet<>();
    forEachBinding(binding -> {
      if (names.add(binding.id.name)) {
        consumer.accept(binding.id.name, binding.id.type);
      }
    });
  }

  /** Calls a consumer for each variable and its value.
   * Does not visit obscured bindings, or bindings to {@link Unit#INSTANCE}. */
  public void forEachValue(BiConsumer<String, Object> consumer) {
    final Set<String> names = new HashSet<>();
    forEachBinding(binding -> {
      if (names.add(binding.id.name) && binding.value != Unit.INSTANCE) {
        consumer.accept(binding.id.name, binding.value);
      }
    });
  }

  /** Returns a map of the values and bindings. */
  public final Map<String, Binding> getValueMap() {
    final Map<String, Binding> valueMap = new HashMap<>();
    forEachBinding(binding -> valueMap.putIfAbsent(binding.id.name, binding));
    return valueMap;
  }

  /** Creates an environment that is the same as this, plus the
   * given bindings. */
  public final Environment bindAll(Iterable<Binding> bindings) {
    return Environments.bind(this, bindings);
  }

  /** If this environment only defines bindings in the given set, returns
   * its parent. Never returns null. The empty environment returns itself. */
  abstract Environment nearestAncestorNotObscuredBy(Set<Core.NamedPat> names);

  abstract int distance(int soFar, Core.NamedPat id);

  protected abstract void populateLayout(List<Core.NamedPat> list);

  StackLayout layout(List<Core.NamedPat> unbounds) {
    final List<Core.NamedPat> list = new ArrayList<>();
    populateLayout(list);
    return new StackLayout(ImmutableList.copyOf(list),
        ImmutableList.copyOf(unbounds));
  }

  /** Returns this environment plus the bindings in the given environment. */
  public Environment plus(Environment env) {
    final List<Binding> bindingList = new ArrayList<>();
    env.forEachBinding(bindingList::add);
    return bindAll(reverse(bindingList));
  }

  public boolean isAncestorOf(Environment env) {
    final List<Environment> ancestors = new ArrayList<>();
    env.forEachAncestor(ancestors::add);
    return ancestors.contains(this);
  }

  /** Layout of the stack, describing the name and type of the variable in each
   * slot of the stack. */
  public static class StackLayout {
    final ImmutableList<Core.NamedPat> list;
    public final ImmutableList<Core.NamedPat> unbounds;

    StackLayout(ImmutableList<Core.NamedPat> list,
        ImmutableList<Core.NamedPat> unbounds) {
      this.list = list;
      this.unbounds = unbounds;
    }

    @Override public String toString() {
      return list.toString();
    }

    /** Returns the distance of a given variable from the top of the stack, or
     * -1 if it is not on the stack. */
    public int distance(Core.NamedPat idPat) {
      final int i = list.indexOf(idPat);
      if (i >= 0) {
        return i;
      }
      final int j = unbounds.indexOf(idPat);
      if (j >= 0) {
        return list.size() + j;
      }
      return -1;
    }
  }
}

// End Environment.java
