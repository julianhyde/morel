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

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.type.DataType;
import net.hydromatic.morel.type.Keys;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.RecordLikeType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Utilities for creating {@link Discrete} instances.
 *
 * <p>Analogous to {@link Comparators}.
 */
public class Discretes {
  private Discretes() {}

  /**
   * Returns a {@link Discrete} domain for the given type, or throws {@link
   * IllegalArgumentException} if the type is not discrete.
   */
  @SuppressWarnings("unchecked")
  public static Discrete<Object> discreteFor(TypeSystem typeSystem, Type type) {
    if (type instanceof PrimitiveType) {
      switch ((PrimitiveType) type) {
        case INT:
          return (Discrete<Object>) (Discrete<?>) INT;
        case CHAR:
          return (Discrete<Object>) (Discrete<?>) CHAR;
        case BOOL:
          return (Discrete<Object>) (Discrete<?>) BOOL;
        case UNIT:
          return (Discrete<Object>) (Discrete<?>) UNIT;
        default:
          throw new IllegalArgumentException("not a discrete type: " + type);
      }
    }
    if (type instanceof RecordLikeType) {
      return tupleDiscrete(typeSystem, (RecordLikeType) type);
    }
    if (type instanceof DataType) {
      return dataTypeDiscrete(typeSystem, (DataType) type);
    }
    throw new IllegalArgumentException("not a discrete type: " + type);
  }

  /** Creates a {@link Discrete} for a tuple or record type. */
  private static Discrete<Object> tupleDiscrete(
      TypeSystem typeSystem, RecordLikeType type) {
    final List<Discrete<Object>> components =
        type.argTypes().stream()
            .map(t -> discreteFor(typeSystem, t))
            .collect(toImmutableList());
    final Comparator<Object> cmp = Comparators.comparatorFor(typeSystem, type);
    return new Discrete<Object>() {
      @Override
      public Comparator<Object> comparator() {
        return cmp;
      }

      @Override
      public @Nullable Object next(Object v) {
        return stepTuple((List<?>) v, components, /* forward= */ true);
      }

      @Override
      public @Nullable Object prev(Object v) {
        return stepTuple((List<?>) v, components, /* forward= */ false);
      }

      @Override
      public @Nullable Object minValue() {
        return tupleExtreme(components, /* min= */ true);
      }

      @Override
      public @Nullable Object maxValue() {
        return tupleExtreme(components, /* min= */ false);
      }
    };
  }

  /**
   * Advances or retreats a tuple by one step (lexicographic, rightmost
   * component first).
   *
   * <p>In forward mode: tries to increment the rightmost component; if it is at
   * its maximum, resets it to its minimum and carries into the next component
   * to the left.
   *
   * <p>In backward mode: symmetric, using {@code prev} and {@code maxValue}.
   */
  private static @Nullable Object stepTuple(
      List<?> values, List<Discrete<Object>> components, boolean forward) {
    final int n = components.size();
    for (int i = n - 1; i >= 0; i--) {
      final Object stepped =
          forward
              ? components.get(i).next(values.get(i))
              : components.get(i).prev(values.get(i));
      if (stepped != null) {
        final List<Object> result = new ArrayList<>(values);
        result.set(i, stepped);
        // Reset components to the right (forward) or left (backward) to their
        // extreme.
        for (int j = i + 1; j < n; j++) {
          final Object extreme =
              forward
                  ? components.get(j).minValue()
                  : components.get(j).maxValue();
          if (extreme == null) {
            return null;
          }
          result.set(j, extreme);
        }
        return ImmutableList.copyOf(result);
      }
    }
    return null;
  }

  /**
   * Returns the min or max tuple value (null if any component is unbounded).
   */
  private static @Nullable Object tupleExtreme(
      List<Discrete<Object>> components, boolean min) {
    final List<Object> result = new ArrayList<>();
    for (Discrete<Object> d : components) {
      final Object extreme = min ? d.minValue() : d.maxValue();
      if (extreme == null) {
        return null;
      }
      result.add(extreme);
    }
    return ImmutableList.copyOf(result);
  }

  /** Creates a {@link Discrete} for a DataType. */
  private static Discrete<Object> dataTypeDiscrete(
      TypeSystem typeSystem, DataType dt) {
    if (dt.name.equals(BuiltIn.Datatype.DESCENDING.mlName())) {
      final Discrete<Object> inner = discreteFor(typeSystem, dt.arg(0));
      final Comparator<Object> cmp = Comparators.comparatorFor(typeSystem, dt);
      // Runtime value: ["DESC", innerValue]
      return new Discrete<Object>() {
        @Override
        public Comparator<Object> comparator() {
          return cmp;
        }

        @Override
        public @Nullable Object next(Object v) {
          // In descending order, the successor is the predecessor in the inner
          // order.
          final Object p = inner.prev(((List<?>) v).get(1));
          return p == null ? null : ImmutableList.of("DESC", p);
        }

        @Override
        public @Nullable Object prev(Object v) {
          final Object n = inner.next(((List<?>) v).get(1));
          return n == null ? null : ImmutableList.of("DESC", n);
        }

        @Override
        public @Nullable Object minValue() {
          final Object max = inner.maxValue();
          return max == null ? null : ImmutableList.of("DESC", max);
        }

        @Override
        public @Nullable Object maxValue() {
          final Object min = inner.minValue();
          return min == null ? null : ImmutableList.of("DESC", min);
        }
      };
    }

    // Finite enum DataType: all constructors must be nullary.
    final ImmutableList<String> ctors =
        dt.typeConstructors.entrySet().stream()
            .filter(e -> e.getValue().equals(Keys.dummy()))
            .map(Map.Entry::getKey)
            .collect(toImmutableList());
    if (ctors.size() == dt.typeConstructors.size() && !ctors.isEmpty()) {
      final Comparator<Object> cmp = Comparators.comparatorFor(typeSystem, dt);
      return new Discrete<Object>() {
        @Override
        public Comparator<Object> comparator() {
          return cmp;
        }

        @Override
        public @Nullable Object next(Object v) {
          final int i = ctors.indexOf(((List<?>) v).get(0));
          return i < ctors.size() - 1
              ? ImmutableList.of(ctors.get(i + 1))
              : null;
        }

        @Override
        public @Nullable Object prev(Object v) {
          final int i = ctors.indexOf(((List<?>) v).get(0));
          return i > 0 ? ImmutableList.of(ctors.get(i - 1)) : null;
        }

        @Override
        public Object minValue() {
          return ImmutableList.of(ctors.get(0));
        }

        @Override
        public Object maxValue() {
          return ImmutableList.of(ctors.get(ctors.size() - 1));
        }
      };
    }

    throw new IllegalArgumentException("not a discrete type: " + dt);
  }

  private static final Comparator<Object> NATURAL = Comparators::compare;

  /** {@link Discrete} for {@code int}. */
  private static final Discrete<Integer> INT =
      new Discrete<Integer>() {
        @Override
        public Comparator<Object> comparator() {
          return NATURAL;
        }

        @Override
        public @Nullable Integer next(Integer v) {
          return v == Integer.MAX_VALUE ? null : v + 1;
        }

        @Override
        public @Nullable Integer prev(Integer v) {
          return v == Integer.MIN_VALUE ? null : v - 1;
        }

        @Override
        public Integer minValue() {
          return Integer.MIN_VALUE;
        }

        @Override
        public Integer maxValue() {
          return Integer.MAX_VALUE;
        }
      };

  /** {@link Discrete} for {@code char} (ordinals 0–255). */
  private static final Discrete<Character> CHAR =
      new Discrete<Character>() {
        @Override
        public Comparator<Object> comparator() {
          return NATURAL;
        }

        @Override
        public @Nullable Character next(Character v) {
          return v == '\u00ff' ? null : (char) (v + 1);
        }

        @Override
        public @Nullable Character prev(Character v) {
          return v == '\u0000' ? null : (char) (v - 1);
        }

        @Override
        public Character minValue() {
          return '\u0000';
        }

        @Override
        public Character maxValue() {
          return '\u00ff';
        }
      };

  /** {@link Discrete} for {@code bool} (false &lt; true). */
  private static final Discrete<Boolean> BOOL =
      new Discrete<Boolean>() {
        @Override
        public Comparator<Object> comparator() {
          return NATURAL;
        }

        @Override
        public @Nullable Boolean next(Boolean v) {
          return v ? null : Boolean.TRUE;
        }

        @Override
        public @Nullable Boolean prev(Boolean v) {
          return v ? Boolean.FALSE : null;
        }

        @Override
        public Boolean minValue() {
          return Boolean.FALSE;
        }

        @Override
        public Boolean maxValue() {
          return Boolean.TRUE;
        }
      };

  /** {@link Discrete} for {@code unit} (a single value). */
  public static final Discrete<Unit> UNIT =
      new Discrete<Unit>() {
        private final Comparator<Object> cmp = (a, b) -> 0;

        @Override
        public Comparator<Object> comparator() {
          return cmp;
        }

        @Override
        public @Nullable Unit next(Unit v) {
          return null;
        }

        @Override
        public @Nullable Unit prev(Unit v) {
          return null;
        }

        @Override
        public Unit minValue() {
          return Unit.INSTANCE;
        }

        @Override
        public Unit maxValue() {
          return Unit.INSTANCE;
        }
      };
}

// End Discretes.java
