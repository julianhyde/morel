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
package net.hydromatic.morel.type;

import static java.util.Objects.requireNonNull;

import java.util.Objects;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.compile.Environment;
import net.hydromatic.morel.eval.Unit;

/**
 * Binding of a name to a type and a value.
 *
 * <p>Used in {@link net.hydromatic.morel.compile.Environment}.
 */
public class Binding {
  public final Core.NamedPat id;
  public final Core.Exp exp;
  public final Object value;
  /** If true, the binding is ignored by inlining. */
  public final boolean parameter;
  /** Distinguishes between regular and overloaded values. */
  public final Kind kind;

  private Binding(
      Core.NamedPat id,
      Core.Exp exp,
      Object value,
      boolean parameter,
      Kind kind) {
    this.id = requireNonNull(id);
    this.exp = exp;
    this.value = requireNonNull(value);
    assert !(value instanceof Core.IdPat);
    this.parameter = parameter;
    this.kind = requireNonNull(kind);
  }

  public static Binding of(Core.NamedPat id) {
    return new Binding(id, null, Unit.INSTANCE, false, Kind.VAL);
  }

  public static Binding of(Core.NamedPat id, Core.Exp exp) {
    return new Binding(id, exp, Unit.INSTANCE, false, Kind.VAL);
  }

  public static Binding of(Core.NamedPat id, Object value) {
    return new Binding(id, null, value, false, Kind.VAL);
  }

  /** Used by {@link Environment#renumber()}. */
  public Binding withFlattenedName() {
    if (id.i == 0) {
      return this;
    }
    Core.NamedPat id1 = id.withName(id.name + '_' + id.i);
    return new Binding(id1, exp, value, parameter, kind);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id, exp, value);
  }

  @Override
  public boolean equals(Object o) {
    return this == o
        || o instanceof Binding
            && id.equals(((Binding) o).id)
            && Objects.equals(exp, ((Binding) o).exp)
            && value.equals(((Binding) o).value);
  }

  public Binding withParameter(boolean parameter) {
    return parameter == this.parameter
        ? this
        : new Binding(id, exp, value, parameter, kind);
  }

  public Binding withKind(Kind kind) {
    return kind == this.kind
        ? this
        : new Binding(id, exp, value, parameter, kind);
  }

  @Override
  public String toString() {
    if (exp != null) {
      return id + " = " + exp;
    } else if (value == Unit.INSTANCE) {
      return id + " : " + id.type.moniker();
    } else {
      return id + " = " + value + " : " + id.type.moniker();
    }
  }

  /** What kind of binding? */
  public enum Kind {
    /** Regular, non-overloaded binding ({@code val}). */
    VAL,
    /** Declaration that a name is overloaded ({@code over}). */
    OVER,
    /** Instance of an overloaded name ({@code val inst}). */
    INST
  }
}

// End Binding.java
