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

import static net.hydromatic.morel.eval.Render.renderValue;

import java.util.List;
import java.util.Objects;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A modifier applied to a query context: a group-key equality, an {@code
 * override}, a {@code restrict}/{@code where} filter, or a {@code relax}.
 *
 * <p>A modifier separates its compile-time structure (this object) from its
 * runtime data (zero or more parameter values in their native format, supplied
 * when the context is materialized). Everything needed to {@link #test
 * evaluate} a base element and to {@link #item render} the §6 constraint string
 * is stored here, so a modifier is self-contained.
 */
public abstract class Modifier {
  /** The kind of a modifier, which determines its §6 canonicalization. */
  public enum Kind {
    EQUALITY,
    LABELED_FILTER,
    ANON_FILTER,
    RELAX
  }

  public final Kind kind;
  /**
   * The label this modifier acts on ({@code "e.color"}), or null if it is
   * anonymous (an {@code where}/{@code restrict_anon} filter).
   */
  public final @Nullable String label;
  /** The number of runtime parameters this modifier consumes. */
  public final int paramCount;

  protected Modifier(Kind kind, @Nullable String label, int paramCount) {
    this.kind = kind;
    this.label = label;
    this.paramCount = paramCount;
  }

  /**
   * Whether a base element satisfies this modifier, given its runtime {@code
   * params}. The {@code stack} is needed by modifiers whose parameter is a
   * predicate closure (a filter).
   */
  public abstract boolean test(
      Stack stack, Object element, List<Object> params);

  /**
   * The §6 constraint item that this modifier contributes (for example {@code
   * "e.color = \"red\""} or {@code "?"}), or null if it contributes none (for
   * example {@link Kind#RELAX}).
   */
  public abstract @Nullable String item(
      List<Object> params, TypeSystem typeSystem);

  /** A modifier with its runtime parameter values plugged in. */
  public static final class Applied {
    public final Modifier modifier;
    public final List<Object> params;

    public Applied(Modifier modifier, List<Object> params) {
      this.modifier = modifier;
      this.params = params;
    }

    public boolean test(Stack stack, Object element) {
      return modifier.test(stack, element, params);
    }

    public @Nullable String item(TypeSystem typeSystem) {
      return modifier.item(params, typeSystem);
    }
  }

  /**
   * An equality constraint, contributed by a group key or an {@code override}.
   * Its single parameter is the constrained value; {@link #fieldIndex} locates
   * the corresponding field within a base element (a record), and {@link
   * #valueType} renders the value.
   */
  public static final class Equality extends Modifier {
    public final Type valueType;
    public final int fieldIndex;

    public Equality(String label, Type valueType, int fieldIndex) {
      super(Kind.EQUALITY, label, 1);
      this.valueType = valueType;
      this.fieldIndex = fieldIndex;
    }

    @Override
    public boolean test(Stack stack, Object element, List<Object> params) {
      final Object fieldValue = ((List<?>) element).get(fieldIndex);
      return Objects.equals(fieldValue, params.get(0));
    }

    @Override
    public @Nullable String item(List<Object> params, TypeSystem typeSystem) {
      return label + " = " + renderValue(typeSystem, valueType, params.get(0));
    }
  }

  /**
   * A filter constraint, contributed by a {@code where}. Its single parameter
   * is a predicate closure on base elements; it renders as the predicate's
   * source text (for example {@code "e.units > 5"}).
   */
  public static final class Filter extends Modifier {
    public final String text;

    public Filter(String text) {
      super(Kind.ANON_FILTER, null, 1);
      this.text = text;
    }

    @Override
    public boolean test(Stack stack, Object element, List<Object> params) {
      final Applicable predicate = (Applicable) params.get(0);
      return (Boolean) predicate.apply(stack, element);
    }

    @Override
    public @Nullable String item(List<Object> params, TypeSystem typeSystem) {
      return text;
    }
  }
}

// End Modifier.java
