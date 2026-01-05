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

import java.util.List;
import net.hydromatic.morel.ast.Core;

/**
 * An expression that returns all values that satisfy a predicate.
 *
 * <p>Created by {@link Expander}.
 */
abstract class Generator {
  final Core.Exp exp;
  final List<Core.NamedPat> patList;
  final Cardinality cardinality;

  Generator(
      Core.Exp exp, List<Core.NamedPat> patList, Cardinality cardinality) {
    this.exp = exp;
    this.patList = patList;
    this.cardinality = cardinality;
  }

  /**
   * Returns every value returned from the generator satisfies a given boolean
   * expression.
   *
   * <p>Each generator should return literal "true" for the predicate that it
   * inverted. For example, the generator from {@link
   * Generators#generateRange}(0, 5) should return true for {@code p > 0 andalso
   * p < 5}.
   */
  abstract Core.Exp simplify(Core.Pat pat, Core.Exp exp);

  /** Cardinality of a generator per binding of its free variables. */
  public enum Cardinality {
    /** Produces exactly one value (e.g., x = 5) */
    SINGLE,
    /** Produces a finite number of values (e.g., x elem [1,2,3]) */
    FINITE,
    /** Produces an infinite number of values (e.g., unbounded extent) */
    INFINITE;

    /** Returns this or {@code o}, whichever has the greater cardiality. */
    Cardinality max(Cardinality o) {
      return this.ordinal() >= o.ordinal() ? this : o;
    }
  }
}

// End Generator.java
