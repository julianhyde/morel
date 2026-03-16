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
  /** Initializes this row sink with a {@link Stack}. */
  void start(Stack stack);

  /** Accepts a row using a {@link Stack}. */
  void accept(Stack stack);

  /** Returns the collected results using a {@link Stack}. */
  List<Object> result(Stack stack);

  default void start(EvalEnv env) {
    throw new UnsupportedOperationException("use start(Stack)");
  }

  default void accept(EvalEnv env) {
    throw new UnsupportedOperationException("use accept(Stack)");
  }

  default List<Object> result(EvalEnv env) {
    throw new UnsupportedOperationException("use result(Stack)");
  }

  /**
   * Returns the maximum number of stack slots this sink (and all its
   * descendants) may push above {@code stack.top} at any point during
   * processing (across both {@link #accept} and {@link #result} calls).
   *
   * <p>Used by {@link Code#maxSlots()} implementations to size the initial
   * {@link Stack} array at statement-evaluation time.
   */
  default int maxSlots() {
    return 0;
  }
}

// End RowSink.java
