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

import java.util.Locale;

/** How a {@link Core.Join} treats elements that have no match. */
public enum JoinType {
  INNER,
  LEFT,
  RIGHT,
  FULL;

  /**
   * Returns whether the left element is optional, and therefore whether {@code
   * $0} has an {@code option} type.
   */
  public boolean leftIsOption() {
    return this == RIGHT || this == FULL;
  }

  /**
   * Returns whether the right element is optional, and therefore whether {@code
   * $1} has an {@code option} type.
   */
  public boolean rightIsOption() {
    return this == LEFT || this == FULL;
  }

  /** Returns the name of this join type, as it appears in plan text. */
  public String opName() {
    return name().toLowerCase(Locale.ROOT);
  }
}

// End JoinType.java
