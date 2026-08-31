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
package net.hydromatic.morel.ast;

import com.google.common.collect.ImmutableSet;
import java.util.EnumSet;
import java.util.Set;

/**
 * A simplification that {@link RelBuilder} may apply as it builds.
 *
 * <p>Every one of them is optional, and a builder is given the set it may use.
 * With {@link #none()} the builder is a pure constructor, which is a test
 * oracle: build a query twice, once simplified and once not, and compare what
 * the two produce.
 *
 * <p>What belongs here rather than in the rule framework of step 4 is what is
 * cheaper not to build than to build and then remove.
 */
public enum Simplification {
  /** Combines a filter over a filter into one conjunction. */
  FILTER_MERGE,

  /** Drops {@code filter true}. */
  FILTER_TRUE,

  /**
   * Drops a join's binder when its right input does not read it, making a
   * dependent join independent.
   */
  JOIN_INDEPENDENT,

  /** Drops a projection whose expression is its input's element. */
  PROJECT_IDENTITY,

  /**
   * Combines a projection over a projection by substitution, binding the inner
   * expression to a variable where the outer reads it more than once.
   */
  PROJECT_MERGE,

  /** Drops {@code skip 0}. */
  SKIP_ZERO,

  /** Drops an {@code unorder} whose input is already unordered. */
  UNORDER_UNORDERED;

  /** Returns the empty set; the builder constructs exactly what it is told. */
  public static Set<Simplification> none() {
    return ImmutableSet.of();
  }

  /**
   * Returns every simplification.
   *
   * <p>Via an {@link EnumSet}, because {@link ImmutableSet#copyOf} specializes
   * for one -- it returns an {@code ImmutableEnumSet}, backed by a bit vector
   * -- and does not for the array that {@code values()} gives.
   */
  public static Set<Simplification> all() {
    return ImmutableSet.copyOf(EnumSet.allOf(Simplification.class));
  }
}

// End Simplification.java
