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
import java.util.Optional;
import net.hydromatic.morel.type.DataType;
import net.hydromatic.morel.type.Keys;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.RecordLikeType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;

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
  public static Discrete discreteFor(TypeSystem typeSystem, Type type) {
    if (type instanceof PrimitiveType) {
      switch ((PrimitiveType) type) {
        case INT:
          return INT;
        case CHAR:
          return CHAR;
        case BOOL:
          return BOOL;
        case UNIT:
          return UNIT;
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
  private static Discrete tupleDiscrete(
      TypeSystem typeSystem, RecordLikeType type) {
    final List<Discrete> components =
        type.argTypes().stream()
            .map(t -> discreteFor(typeSystem, t))
            .collect(toImmutableList());
    final Comparator<Object> cmp = Comparators.comparatorFor(typeSystem, type);
    return new Discrete() {
      @Override
      public Comparator<Object> comparator() {
        return cmp;
      }

      @Override
      public Optional<Object> next(Object v) {
        return stepTuple((List<?>) v, components, /* forward= */ true);
      }

      @Override
      public Optional<Object> prev(Object v) {
        return stepTuple((List<?>) v, components, /* forward= */ false);
      }

      @Override
      public Optional<Object> minValue() {
        return tupleExtreme(components, /* min= */ true);
      }

      @Override
      public Optional<Object> maxValue() {
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
  private static Optional<Object> stepTuple(
      List<?> values, List<Discrete> components, boolean forward) {
    final int n = components.size();
    for (int i = n - 1; i >= 0; i--) {
      final Optional<Object> stepped =
          forward
              ? components.get(i).next(values.get(i))
              : components.get(i).prev(values.get(i));
      if (stepped.isPresent()) {
        final List<Object> result = new ArrayList<>(values);
        result.set(i, stepped.get());
        // Reset components to the right (forward) or left (backward) to their
        // extreme.
        for (int j = i + 1; j < n; j++) {
          final Optional<Object> extreme =
              forward
                  ? components.get(j).minValue()
                  : components.get(j).maxValue();
          if (!extreme.isPresent()) {
            return Optional.empty();
          }
          result.set(j, extreme.get());
        }
        return Optional.of(ImmutableList.copyOf(result));
      }
    }
    return Optional.empty();
  }

  /**
   * Returns the min or max tuple value (empty if any component is unbounded).
   */
  private static Optional<Object> tupleExtreme(
      List<Discrete> components, boolean min) {
    final List<Object> result = new ArrayList<>();
    for (Discrete d : components) {
      final Optional<Object> extreme = min ? d.minValue() : d.maxValue();
      if (!extreme.isPresent()) {
        return Optional.empty();
      }
      result.add(extreme.get());
    }
    return Optional.of(ImmutableList.copyOf(result));
  }

  /** Creates a {@link Discrete} for a DataType. */
  private static Discrete dataTypeDiscrete(TypeSystem typeSystem, DataType dt) {
    if (dt.name.equals("descending")) {
      final Discrete inner = discreteFor(typeSystem, dt.arg(0));
      final Comparator<Object> cmp = Comparators.comparatorFor(typeSystem, dt);
      // Runtime value: ["DESC", innerValue]
      return new Discrete() {
        @Override
        public Comparator<Object> comparator() {
          return cmp;
        }

        @Override
        public Optional<Object> next(Object v) {
          // In descending order, the successor is the predecessor in the inner
          // order.
          return inner
              .prev(((List<?>) v).get(1))
              .map(p -> ImmutableList.of("DESC", p));
        }

        @Override
        public Optional<Object> prev(Object v) {
          return inner
              .next(((List<?>) v).get(1))
              .map(n -> ImmutableList.of("DESC", n));
        }

        @Override
        public Optional<Object> minValue() {
          return inner.maxValue().map(max -> ImmutableList.of("DESC", max));
        }

        @Override
        public Optional<Object> maxValue() {
          return inner.minValue().map(min -> ImmutableList.of("DESC", min));
        }
      };
    }

    // Finite enum DataType: all constructors must be nullary.
    final ImmutableList<String> ctors =
        dt.typeConstructors.entrySet().stream()
            .filter(e -> e.getValue().equals(Keys.dummy()))
            .map(e -> e.getKey())
            .collect(toImmutableList());
    if (ctors.size() == dt.typeConstructors.size() && !ctors.isEmpty()) {
      final Comparator<Object> cmp = Comparators.comparatorFor(typeSystem, dt);
      return new Discrete() {
        @Override
        public Comparator<Object> comparator() {
          return cmp;
        }

        @Override
        public Optional<Object> next(Object v) {
          final int i = ctors.indexOf(((List<?>) v).get(0));
          return i < ctors.size() - 1
              ? Optional.of(ImmutableList.of(ctors.get(i + 1)))
              : Optional.empty();
        }

        @Override
        public Optional<Object> prev(Object v) {
          final int i = ctors.indexOf(((List<?>) v).get(0));
          return i > 0
              ? Optional.of(ImmutableList.of(ctors.get(i - 1)))
              : Optional.empty();
        }

        @Override
        public Optional<Object> minValue() {
          return Optional.of(ImmutableList.of(ctors.get(0)));
        }

        @Override
        public Optional<Object> maxValue() {
          return Optional.of(ImmutableList.of(ctors.get(ctors.size() - 1)));
        }
      };
    }

    throw new IllegalArgumentException("not a discrete type: " + dt);
  }

  @SuppressWarnings("rawtypes")
  private static final Comparator<Object> NATURAL = Comparators::compare;

  /** {@link Discrete} for {@code int}. */
  private static final Discrete INT =
      new Discrete() {
        @Override
        public Comparator<Object> comparator() {
          return NATURAL;
        }

        @Override
        public Optional<Object> next(Object v) {
          return Optional.of((Integer) v + 1);
        }

        @Override
        public Optional<Object> prev(Object v) {
          return Optional.of((Integer) v - 1);
        }

        @Override
        public Optional<Object> minValue() {
          return Optional.empty(); // int is unbounded below
        }

        @Override
        public Optional<Object> maxValue() {
          return Optional.empty(); // int is unbounded above
        }
      };

  /** {@link Discrete} for {@code char} (ordinals 0–255). */
  private static final Discrete CHAR =
      new Discrete() {
        @Override
        public Comparator<Object> comparator() {
          return NATURAL;
        }

        @Override
        public Optional<Object> next(Object v) {
          char c = (Character) v;
          return c == '\u00ff' ? Optional.empty() : Optional.of((char) (c + 1));
        }

        @Override
        public Optional<Object> prev(Object v) {
          char c = (Character) v;
          return c == '\u0000' ? Optional.empty() : Optional.of((char) (c - 1));
        }

        @Override
        public Optional<Object> minValue() {
          return Optional.of('\u0000');
        }

        @Override
        public Optional<Object> maxValue() {
          return Optional.of('\u00ff');
        }
      };

  /** {@link Discrete} for {@code bool} (false &lt; true). */
  private static final Discrete BOOL =
      new Discrete() {
        @Override
        public Comparator<Object> comparator() {
          return NATURAL;
        }

        @Override
        public Optional<Object> next(Object v) {
          return (Boolean) v ? Optional.empty() : Optional.of(Boolean.TRUE);
        }

        @Override
        public Optional<Object> prev(Object v) {
          return (Boolean) v ? Optional.of(Boolean.FALSE) : Optional.empty();
        }

        @Override
        public Optional<Object> minValue() {
          return Optional.of(Boolean.FALSE);
        }

        @Override
        public Optional<Object> maxValue() {
          return Optional.of(Boolean.TRUE);
        }
      };

  /** {@link Discrete} for {@code unit} (a single value). */
  private static final Discrete UNIT =
      new Discrete() {
        private final Comparator<Object> cmp = (a, b) -> 0;

        @Override
        public Comparator<Object> comparator() {
          return cmp;
        }

        @Override
        public Optional<Object> next(Object v) {
          return Optional.empty();
        }

        @Override
        public Optional<Object> prev(Object v) {
          return Optional.empty();
        }

        @Override
        public Optional<Object> minValue() {
          return Optional.of(Unit.INSTANCE);
        }

        @Override
        public Optional<Object> maxValue() {
          return Optional.of(Unit.INSTANCE);
        }
      };
}

// End Discretes.java
