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

import java.util.Comparator;
import java.util.Optional;
import net.hydromatic.morel.type.PrimitiveType;
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
          break;
      }
    }
    throw new IllegalArgumentException("not a discrete type: " + type);
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
        public Optional<Object> minValue() {
          return Optional.empty(); // int is unbounded below
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
        public Optional<Object> minValue() {
          return Optional.of('\u0000');
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
        public Optional<Object> minValue() {
          return Optional.of(Boolean.FALSE);
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
        public Optional<Object> minValue() {
          return Optional.of(Unit.INSTANCE);
        }
      };
}

// End Discretes.java
