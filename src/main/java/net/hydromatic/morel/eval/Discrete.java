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
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Represents a discrete ordered type, supporting enumeration of its values.
 *
 * <p>A discrete type is a totally ordered type where each value (except the
 * maximum) has a unique successor. Examples: {@code int}, {@code char}, {@code
 * bool}, {@code unit}. Non-examples: {@code real}, {@code string}.
 *
 * <p>Analogous to Guava's {@code DiscreteDomain}.
 *
 * @param <T> the type of values in this domain
 */
public interface Discrete<T> {

  /** Returns the comparator for this type. */
  Comparator<Object> comparator();

  /**
   * Returns the successor of {@code v}, or null if {@code v} is the maximum
   * value of this type.
   */
  @Nullable
  T next(T v);

  /**
   * Returns the predecessor of {@code v}, or null if {@code v} is the minimum
   * value of this type.
   */
  @Nullable
  T prev(T v);

  /** Returns the minimum value of this type. */
  T minValue();

  /** Returns the maximum value of this type. */
  T maxValue();

  /**
   * Returns how many values this type has, saturating at {@link
   * Long#MAX_VALUE}.
   */
  long size();

  /**
   * Returns the position of {@code v}, counting from 0 at {@link #minValue},
   * saturating at {@link Long#MAX_VALUE}.
   *
   * <p>Positions serve only to count the values between two others, so a domain
   * that has more values than a {@code long} can number may saturate; a caller
   * that sees {@link Long#MAX_VALUE} learns that the position is too large to
   * count to, which is all it needs to know.
   */
  long ordinal(T v);
}

// End Discrete.java
