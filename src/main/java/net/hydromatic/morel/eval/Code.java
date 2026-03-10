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

/** A compiled expression, that can be evaluated. */
public interface Code extends Describable {
  /**
   * Evaluates this expression using a {@link Stack}.
   *
   * <p>This is the primary evaluation method. Stack-based code nodes override
   * this method to access local variables directly from {@link Stack#slots}.
   * Other code nodes rely on the default, which delegates to {@link
   * #eval(EvalEnv)} via {@link Stack#globalEnv}.
   */
  default Object eval(final Stack stack) {
    return eval(stack.globalEnv);
  }

  /**
   * Evaluates this expression using an {@link EvalEnv}.
   *
   * <p>This is the fallback / legacy evaluation path. Most non-stack code nodes
   * implement this method. Stack-only nodes (e.g. {@code StackCode}, {@code
   * StackLet1Code}) do not override this; callers must use {@link #eval(Stack)}
   * instead.
   */
  default Object eval(EvalEnv evalEnv) {
    throw new UnsupportedOperationException(
        getClass().getSimpleName() + " requires a Stack");
  }

  default boolean isConstant() {
    return false;
  }
}

// End Code.java
