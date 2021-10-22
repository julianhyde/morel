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

import org.apache.calcite.rel.core.JoinRelType;

/** Utilities for abstract syntax trees. */
public abstract class AstNodes {
  /** Converts an {@link Op} into a Calcite join type, otherwise throws. */
  public static JoinRelType joinRelType(Op op) {
    switch (op) {
    case SCAN:
      return JoinRelType.INNER;
    case LEFT_JOIN:
      return JoinRelType.LEFT;
    case RIGHT_JOIN:
      return JoinRelType.RIGHT;
    case FULL_JOIN:
      return JoinRelType.FULL;
    default:
      throw new AssertionError(op);
    }
  }
}

// End AstNodes.java
