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

import java.util.List;

/** Accepts rows produced by a supplier as part of a {@code from} step. */
public interface RowSink extends Describable {
  void start(EvalEnv env);

  void accept(EvalEnv env);

  List<Object> result(EvalEnv env);

  /**
   * Initializes this row sink with a {@link Stack}.
   *
   * <p>The default implementation delegates to {@link #start(EvalEnv)} via
   * {@link Stack#globalEnv}. Implementations that push iteration variables onto
   * the stack override this method.
   */
  default void start(final Stack stack) {
    start(stack.globalEnv);
  }

  /**
   * Accepts a row using a {@link Stack}.
   *
   * <p>The default implementation delegates to {@link #accept(EvalEnv)} via
   * {@link Stack#globalEnv}. Implementations that read iteration variables from
   * the stack override this method.
   */
  default void accept(final Stack stack) {
    accept(stack.globalEnv);
  }

  /**
   * Returns the collected results using a {@link Stack}.
   *
   * <p>The default implementation delegates to {@link #result(EvalEnv)} via
   * {@link Stack#globalEnv}. Implementations that need stack access override
   * this method.
   */
  default List<Object> result(final Stack stack) {
    return result(stack.globalEnv);
  }
}

// End RowSink.java
