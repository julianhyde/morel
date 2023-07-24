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
import net.hydromatic.morel.eval.Codes;
import net.hydromatic.morel.eval.EvalEnv;
import net.hydromatic.morel.foreign.ForeignValue;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.Pair;
import net.hydromatic.morel.util.Static;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static net.hydromatic.morel.ast.CoreBuilder.core;

import static com.google.common.collect.ImmutableList.copyOf;

import static java.util.Objects.requireNonNull;

/** Helpers for {@link Environment}. */
public abstract class Environments {

  private Environments() {}

  /** Creates an empty environment. */
  public static Environment empty() {
    return EmptyEnvironment.INSTANCE;
  }

  /** Creates an environment containing built-ins and the given foreign
   * values. */
  public static Environment env(TypeSystem typeSystem,
      Map<String, ForeignValue> valueMap) {
    final Environment environment = EmptyEnvironment.INSTANCE;
    if (Static.SKIP) {
      return environment;
    }
    final List<Binding> bindings = new ArrayList<>();
    BuiltIn.dataTypes(typeSystem, bindings);
    final NameGenerator nameGen = typeSystem.nameGenerator;
    Codes.BUILT_IN_VALUES.forEach((key, value) -> {
      if ("$".equals(key.structure)) {
        return; // ignore Z_ANDALSO, Z_LIST, etc.
      }
      final Type type = key.typeFunction.apply(typeSystem);
      if (key.structure == null) {
        bindings.add(Binding.of(core.idPat(type, key.mlName, nameGen), value));
      }
      if (key.alias != null) {
        bindings.add(Binding.of(core.idPat(type, key.alias, nameGen), value));
      }
    });

    final EvalEnv emptyEnv = Codes.emptyEnv();
    BuiltIn.forEachStructure(typeSystem, (structure, type) ->
        bindings.add(
            Binding.of(core.idPat(type, structure.name, nameGen),
                emptyEnv.getOpt(structure.name))));

    foreignBindings(typeSystem, valueMap, bindings);
    return bind(environment, bindings);
  }

  private static void foreignBindings(TypeSystem typeSystem,
      Map<String, ForeignValue> map, List<Binding> bindings) {
    map.forEach((name, value) -> bindings.add(
        Binding.of(
            core.idPat(value.type(typeSystem), name, typeSystem.nameGenerator),
            value.value())
            .withParameter(true)));
  }

  /** Creates an environment that is a given environment plus bindings. */
  static Environment bind(Environment env, Iterable<Binding> bindings) {
    if (Static.shorterThan(bindings, 5)) {
      for (Binding binding : bindings) {
        env = env.bind(binding);
      }
      return env;
    } else {
      // We assume that the set of bindings does not include two Core.IdPat
      // instances with the same name but different ordinals.
      final ImmutableMap.Builder<Core.NamedPat, Binding> b =
          ImmutableMap.builder();
      bindings.forEach(binding -> b.put(binding.id, binding));
      final ImmutableMap<Core.NamedPat, Binding> map = b.build();
      final ImmutableSet<Core.NamedPat> names = map.keySet();
      env = env.nearestAncestorNotObscuredBy(names);
      return new MapEnvironment(env, map);
    }
  }

  /** Creates an environment that looks first in one environment, then
   * another. */
  public static Environment concat(Environment... environments) {
    final List<Environment> list = new ArrayList<>();
    for (Environment environment : environments) {
      addEnv(list, environment);
    }
    switch (list.size()) {
    case 0:
      return empty();
    case 1:
      return list.get(0);
    default:
      return new ConcatEnvironment(copyOf(list));
    }
  }

  /** Adds an environment to a list, flattening as it goes. */
  private static void addEnv(List<Environment> list, Environment e) {
    if (e instanceof ConcatEnvironment) {
      ((ConcatEnvironment) e).list.forEach(e2 -> addEnv(list, e2));
    } else if (!(e instanceof EmptyEnvironment)) {
      list.add(e);
    }
  }

  public static Environment rec(Environment env, List<Binding> bindings) {
    final ImmutableMap.Builder<Core.NamedPat, Binding> b =
        ImmutableMap.builder();
    bindings.forEach(binding -> b.put(binding.id, binding));
    return new MapRecEnvironment(env, b.build());
  }

  /** Environment that inherits from a parent environment and adds one
   * binding. */
  static class SubEnvironment extends Environment {
    private final Environment parent;
    private final Binding binding;

    SubEnvironment(Environment parent, Binding binding) {
      this.parent = requireNonNull(parent);
      this.binding = requireNonNull(binding);
    }

    @Override public String toString() {
      return binding.id + ", ...";
    }

    @Override public @Nullable Binding getOpt(String name) {
      if (name.equals(binding.id.name)) {
        return binding;
      }
      return parent.getOpt(name);
    }

    @Override public @Nullable Pair<Binding, Environment> getOpt2(
        Core.NamedPat id) {
      if (id.equals(binding.id)) {
        return Pair.of(binding, this);
      }
      return parent.getOpt2(id);
    }

    @Override protected Environment bind(Binding binding) {
      Environment env;
      if (this.binding.id.equals(binding.id)
          && !this.binding.id.name.isEmpty()) {
        // The new binding will obscure the current environment's binding,
        // because it binds a variable of the same name. Bind the parent
        // environment instead. This strategy is worthwhile because it tends to
        // prevent long chains from forming, and allows obscured values to be
        // garbage-collected.
        env = parent;
        while (env instanceof SubEnvironment
            && ((SubEnvironment) env).binding.id.name.equals(binding.id.name)) {
          env = ((SubEnvironment) env).parent;
        }
      } else {
        env = this;
      }
      return new Environments.SubEnvironment(env, binding);
    }

    @Override void forEachAncestor(Consumer<Environment> consumer) {
      consumer.accept(this);
      parent.forEachAncestor(consumer);
    }

    @Override void forEachBinding(Consumer<Binding> consumer) {
      consumer.accept(binding);
      parent.forEachBinding(consumer);
    }

    @Override Environment nearestAncestorNotObscuredBy(Set<Core.NamedPat> names) {
      return names.contains(binding.id)
          ? parent.nearestAncestorNotObscuredBy(names)
          : this;
    }

    @Override int distance(int soFar, Core.NamedPat id) {
      if (id.equals(this.binding.id)) {
        return soFar;
      } else {
        return parent.distance(soFar + 1, id);
      }
    }

    @Override protected void populateLayout(List<Core.NamedPat> list) {
      list.add(binding.id);
      parent.populateLayout(list);
    }
  }

  /** Empty environment. */
  private static class EmptyEnvironment extends Environment {
    static final EmptyEnvironment INSTANCE = new EmptyEnvironment();

    @Override void forEachAncestor(Consumer<Environment> consumer) {
      consumer.accept(this);
    }

    @Override void forEachBinding(Consumer<Binding> consumer) {
    }

    @Override public @Nullable Binding getOpt(String name) {
      return null;
    }

    @Override public @Nullable Pair<Binding, Environment> getOpt2(
        Core.NamedPat id) {
      return null;
    }

    @Override Environment nearestAncestorNotObscuredBy(Set<Core.NamedPat> names) {
      return this;
    }

    @Override int distance(int soFar, Core.NamedPat id) {
      return -1;
    }

    @Override protected void populateLayout(List<Core.NamedPat> list) {
    }
  }

  /** Environment that keeps bindings in a map. */
  static class MapEnvironment extends Environment {
    protected final Environment parent;
    private final Map<Core.NamedPat, Binding> map;

    MapEnvironment(Environment parent,
        ImmutableMap<Core.NamedPat, Binding> map) {
      this.parent = requireNonNull(parent);
      this.map = requireNonNull(map);
    }

    @Override void forEachAncestor(Consumer<Environment> consumer) {
      consumer.accept(this);
      parent.forEachAncestor(consumer);
    }

    @Override void forEachBinding(Consumer<Binding> consumer) {
      map.values().forEach(consumer);
      parent.forEachBinding(consumer);
    }

    public @Nullable Binding getOpt(String name) {
      for (Map.Entry<Core.NamedPat, Binding> entry : map.entrySet()) {
        if (entry.getKey().name.equals(name)) {
          return entry.getValue();
        }
      }
      return parent.getOpt(name);
    }

    @Override public @Nullable Pair<Binding, Environment> getOpt2(
        Core.NamedPat id) {
      final Binding binding = map.get(id);
      return binding != null && binding.id.i == id.i
          ? Pair.of(binding, this)
          : parent.getOpt2(id);
    }

    @Override Environment nearestAncestorNotObscuredBy(Set<Core.NamedPat> names) {
      return names.containsAll(map.keySet())
          ? parent.nearestAncestorNotObscuredBy(names)
          : this;
    }

    @Override int distance(int soFar, Core.NamedPat id) {
      final int i = find(map.keySet(), id);
      if (i >= 0) {
        return soFar + map.size() - 1 - i;
      } else {
        return parent.distance(soFar + map.size(), id);
      }
    }

    @Override protected void populateLayout(List<Core.NamedPat> list) {
      list.addAll(copyOf(map.keySet()).reverse());
      parent.populateLayout(list);
    }

    private <E> int find(Iterable<E> iterable, E e) {
      int i = 0;
      for (E e1 : iterable) {
        if (e1.equals(e)) {
          return i;
        }
        ++i;
      }
      return -1;
    }
  }

  static class MapRecEnvironment extends MapEnvironment {
    MapRecEnvironment(Environment parent,
        ImmutableMap<Core.NamedPat, Binding> map) {
      super(parent, map);
    }

    @Override protected void populateLayout(List<Core.NamedPat> list) {
      // Skip the bindings in this environment
      parent.populateLayout(list);
    }
  }

  /** Environment that looks in a chain of environments, returning the first
   * match that it finds. */
  static class ConcatEnvironment extends Environment {
    private final List<Environment> list;

    ConcatEnvironment(ImmutableList<Environment> list) {
      this.list = list;
    }

    @Override public @Nullable Binding getOpt(String name) {
      for (Environment env : list) {
        Binding binding = env.getOpt(name);
        if (binding != null) {
          return binding;
        }
      }
      return null;
    }

    @Override public @Nullable Pair<Binding, Environment> getOpt2(
        Core.NamedPat id) {
      for (Environment env : list) {
        Binding binding = env.getOpt(id);
        if (binding != null) {
          return Pair.of(binding, env);
        }
      }
      return null;
    }

    @Override void forEachAncestor(Consumer<Environment> consumer) {
      consumer.accept(this);
      list.forEach(consumer);
    }

    @Override void forEachBinding(Consumer<Binding> consumer) {
      list.forEach(env -> env.forEachBinding(consumer));
    }

    @Override Environment nearestAncestorNotObscuredBy(Set<Core.NamedPat> names) {
      // REVIEW
      return list.get(0).nearestAncestorNotObscuredBy(names);
    }

    @Override int distance(int soFar, Core.NamedPat id) {
      // REVIEW Is this right? We can only look in the first environment.
      return list.get(0).distance(soFar, id);
    }

    @Override protected void populateLayout(List<Core.NamedPat> list) {
      this.list.forEach(e -> e.populateLayout(list));
    }
  }
}

// End Environments.java
