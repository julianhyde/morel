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

import com.google.common.collect.ImmutableList;
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
 * when the context is materialized).
 *
 * <p>Rendering is uniform: {@link #format} is a text template whose {@code {i}}
 * placeholders index the first {@link #keyTypes}{@code .size()} parameters (the
 * "keys"), each rendered by its type. For example an equality has format {@code
 * "e.color = {0}"}, and a {@code where} filter {@code "e.units > {0}"}. A
 * modifier may consume further parameters beyond its keys (a filter's last
 * parameter is the predicate closure, used only by {@link #test}). Construct
 * modifiers through {@link Modifiers}.
 */
public abstract class Modifier {
  /**
   * The label this modifier acts on ({@code "e.color"}), or null if it is
   * anonymous (a {@code where}/{@code restrict_anon} filter).
   */
  public final @Nullable String label;
  /**
   * The rendering template, with {@code {i}} placeholders indexing the key
   * parameters (for example {@code "e.color = {0}"}).
   */
  public final String format;
  /** The type of each key, used to render the corresponding placeholder. */
  public final List<Type> keyTypes;

  protected Modifier(
      @Nullable String label, String format, List<Type> keyTypes) {
    this.label = label;
    this.format = format;
    this.keyTypes = keyTypes;
  }

  /**
   * The number of runtime parameters this modifier consumes. The keys are the
   * first parameters; a subclass with test-only parameters (a filter's closure)
   * overrides to add them.
   */
  public int paramCount() {
    return keyTypes.size();
  }

  /**
   * Whether a base element satisfies this modifier, given its runtime {@code
   * params}. The {@code stack} is needed by modifiers whose parameter is a
   * predicate closure (a filter).
   */
  public abstract boolean test(
      Stack stack, Object element, List<Object> params);

  /**
   * The constraint item that this modifier contributes, rendered by
   * substituting each {@code {i}} placeholder in {@link #format} with key
   * {@code i}.
   */
  public String item(List<Object> params, TypeSystem typeSystem) {
    final StringBuilder buf = new StringBuilder();
    int i = 0;
    while (i < format.length()) {
      final char c = format.charAt(i);
      if (c == '{') {
        final int j = format.indexOf('}', i);
        final int k = Integer.parseInt(format.substring(i + 1, j));
        renderValue(buf, typeSystem, keyTypes.get(k), params.get(k));
        i = j + 1;
      } else {
        buf.append(c);
        i++;
      }
    }
    return buf.toString();
  }

  /**
   * An equality constraint, contributed by a group key or an {@code override}.
   * Its single parameter (also its one key) is the constrained value; {@link
   * #fieldIndex} locates the corresponding field within a base element.
   */
  static final class Equality extends Modifier {
    final int fieldIndex;

    Equality(String label, Type valueType, int fieldIndex) {
      super(label, label + " = {0}", ImmutableList.of(valueType));
      this.fieldIndex = fieldIndex;
    }

    @Override
    public boolean test(Stack stack, Object element, List<Object> params) {
      final Object fieldValue = ((List<?>) element).get(fieldIndex);
      return Objects.equals(fieldValue, params.get(0));
    }
  }

  /**
   * A filter constraint, contributed by a {@code where} (anonymous) or a {@code
   * restrict} (labeled). Its keys are the predicate template's holes; its last
   * parameter, after the keys, is the predicate closure used by {@link #test}.
   */
  static final class Filter extends Modifier {
    Filter(@Nullable String label, String format, List<Type> keyTypes) {
      super(label, format, keyTypes);
    }

    @Override
    public int paramCount() {
      return keyTypes.size() + 1;
    }

    @Override
    public boolean test(Stack stack, Object element, List<Object> params) {
      final Applicable predicate = (Applicable) params.get(params.size() - 1);
      return (Boolean) predicate.apply(stack, element);
    }
  }
}

// End Modifier.java
