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
import net.hydromatic.morel.eval.Session;
import net.hydromatic.morel.foreign.ForeignValue;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static net.hydromatic.morel.ast.CoreBuilder.core;
import static net.hydromatic.morel.util.Static.SKIP;
import static net.hydromatic.morel.util.Static.shorterThan;

import static java.util.Objects.requireNonNull;

/** Helpers for {@link Environment}. */
public abstract class Environments {

  /** An environment with only "true" and "false". */
  private static final Environment BASIC_ENVIRONMENT =
      EmptyEnvironment.INSTANCE
          .bind(core.idPat(PrimitiveType.BOOL, "true", 0), true)
          .bind(core.idPat(PrimitiveType.BOOL, "false", 0), false);

  private Environments() {}

  /** Creates an empty environment. */
  public static Environment empty() {
    return BASIC_ENVIRONMENT;
  }

  /** Creates an environment containing built-ins and the given foreign
   * values. */
  public static Environment env(TypeSystem typeSystem,
      @Nullable Session session, Map<String, ForeignValue> valueMap) {
    return env(EmptyEnvironment.INSTANCE, typeSystem, session, valueMap);
  }

  /** Creates a compilation environment, including built-ins and foreign
   * values. */
  private static Environment env(Environment environment, TypeSystem typeSystem,
      @Nullable Session session, Map<String, ForeignValue> valueMap) {
    if (SKIP) {
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
      if (key.sessionValue != null) {
        if (session == null) {
          return;
        }
        value = key.sessionValue.apply(session);
      }
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
    if (shorterThan(bindings, 5)) {
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

  /** Creates a description of an environment. */
  public static String describe(Environment e) {
    final StringBuilder b = new StringBuilder();
    for (;;) {
      if (e instanceof SubEnvironment) {
        final SubEnvironment e2 = (SubEnvironment) e;
        b.append(e2.binding.id)
            .append("; ");
        e = e2.parent;
      } else if (e instanceof MapEnvironment) {
        final MapEnvironment e2 = (MapEnvironment) e;
        b.append("map with ")
            .append(e2.map.size())
            .append(" elements");
        break;
      } else {
        break;
      }
    }
    return b.toString();
  }

  /** Environment that inherits from a parent environment and adds one
   * binding. */
  static class SubEnvironment extends Environment {
    final Environment parent;
    final Binding binding;

    SubEnvironment(Environment parent, Binding binding) {
      this.parent = requireNonNull(parent);
      this.binding = requireNonNull(binding);
    }

    @Override public String toString() {
      return binding.id + ", ...";
    }

    @Override public final @Nullable Binding getOpt(String name) {
      // Manual tail recursion elimination:
      // Loop rather than recurse, to save stack space.
      for (SubEnvironment t = this;; t = (SubEnvironment) t.parent) {
        if (name.equals(t.binding.id.name)) {
          return t.binding;
        }
        if (!(t.parent instanceof SubEnvironment)) {
          return t.parent.getOpt(name);
        }
      }
    }

    @Override public final @Nullable Binding getOpt(Core.NamedPat id) {
      // Manual tail recursion elimination:
      // Loop rather than recurse, to save stack space.
      for (SubEnvironment t = this;; t = (SubEnvironment) t.parent) {
        if (id.equals(t.binding.id)) {
          return t.binding;
        }
        if (!(t.parent instanceof SubEnvironment)) {
          return t.parent.getOpt(id);
        }
      }
    }

    @Override protected Environment bind(Binding binding) {
      Environment env;
      if (this.binding.id.equals(binding.id)) {
        // The new binding will obscure the current environment's binding,
        // because it binds a variable of the same name. Bind the parent
        // environment instead. This strategy is worthwhile because it tends to
        // prevent long chains from forming, and allows obscured values to be
        // garbage-collected.
        env = parent;
        while (env instanceof SubEnvironment
            && ((SubEnvironment) env).binding.id.equals(binding.id)) {
          env = ((SubEnvironment) env).parent;
        }
      } else {
        env = this;
      }
      return new Environments.SubEnvironment(env, binding);
    }

    void visit(Consumer<Binding> consumer) {
      consumer.accept(binding);
      parent.visit(consumer);
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
  }

  /** Empty environment. */
  private static class EmptyEnvironment extends Environment {
    static final EmptyEnvironment INSTANCE = new EmptyEnvironment();

    void visit(Consumer<Binding> consumer) {
    }

    @Override public @Nullable Binding getOpt(String name) {
      return null;
    }

    @Override public @Nullable Binding getOpt(Core.NamedPat id) {
      return null;
    }

    @Override Environment nearestAncestorNotObscuredBy(Set<Core.NamedPat> names) {
      return this;
    }

    @Override int distance(int soFar, Core.NamedPat id) {
      return -1;
    }
  }

  /** Environment that keeps bindings in a map. */
  static class MapEnvironment extends Environment {
    private final Environment parent;
    private final Map<Core.NamedPat, Binding> map;

    MapEnvironment(Environment parent,
        ImmutableMap<Core.NamedPat, Binding> map) {
      this.parent = requireNonNull(parent);
      this.map = requireNonNull(map);
    }

    void visit(Consumer<Binding> consumer) {
      map.values().forEach(consumer);
      parent.visit(consumer);
    }

    public @Nullable Binding getOpt(String name) {
      for (Map.Entry<Core.NamedPat, Binding> entry : map.entrySet()) {
        if (entry.getKey().name.equals(name)) {
          return entry.getValue();
        }
      }
      return parent.getOpt(name);
    }

    public @Nullable Binding getOpt(Core.NamedPat id) {
      final Binding binding = map.get(id);
      return binding != null && binding.id.i == id.i ? binding
          : parent.getOpt(id);
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

  static class SubstitutingEnvironment extends Environment {
    private final Environment delegate;
    private final ImmutableMap<Core.Id, Core.Id> byId;
    private final ImmutableMap<Core.NamedPat, Core.Id> byPat;
    private final ImmutableMap<String, Core.Id> byName;

    SubstitutingEnvironment(Environment delegate,
        Map<Core.Id, Core.Id> byId) {
      this.delegate = requireNonNull(delegate);
      this.byId = ImmutableMap.copyOf(byId);
      final ImmutableMap.Builder<Core.NamedPat, Core.Id> patBuilder =
          ImmutableMap.builder();
      final ImmutableMap.Builder<String, Core.Id> nameBuilder =
          ImmutableMap.builder();
      byId.forEach((k, v) -> {
        patBuilder.put(k.idPat, v);
        nameBuilder.put(k.idPat.name, v);
      });
      this.byPat = patBuilder.build();
      this.byName = nameBuilder.build();
    }

    @Override void visit(Consumer<Binding> consumer) {
      delegate.visit(binding -> consumer.accept(substitute(binding)));
    }

    private Binding substitute(Binding binding) {
      for (Map.Entry<Core.Id, Core.Id> entry : byId.entrySet()) {
        if (binding.id.equals(entry.getKey().idPat)) {
          return Binding.of(entry.getValue().idPat, binding.exp, binding.value);
        }
      }
      return binding;
    }

    @Override public @Nullable Binding getOpt(String name) {
      final Core.Id id = byName.get(name);
      final String name2 = id != null ? id.idPat.name : name;
      return delegate.getOpt(name2);
    }

    @Override public @Nullable Binding getOpt(Core.NamedPat idPat) {
      final Core.Id id = byPat.get(idPat);
      final Core.NamedPat idPat2 = id != null ? id.idPat : idPat;
      return delegate.getOpt(idPat2);
    }

    @Override Environment nearestAncestorNotObscuredBy(Set<Core.NamedPat> names) {
      ImmutableSet.Builder<Core.NamedPat> b = ImmutableSet.builder();
      names.forEach(idPat -> {
        final Core.Id id = byPat.get(idPat);
        final Core.NamedPat idPat2 = id != null ? id.idPat : idPat;
        b.add(idPat2);
      });
      return delegate.nearestAncestorNotObscuredBy(b.build());
    }

    @Override int distance(int soFar, Core.NamedPat idPat) {
      final Core.Id id = byPat.get(idPat);
      final Core.NamedPat idPat2 = id != null ? id.idPat : idPat;
      return delegate.distance(soFar, idPat2);
    }
  }
}

// End Environments.java
