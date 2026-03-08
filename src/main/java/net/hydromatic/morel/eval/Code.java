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
  Object eval(EvalEnv evalEnv);

  /**
   * Evaluates this expression using a {@link Stack}.
   *
   * <p>In Step 2 of the stack-based evaluation migration, this default
   * implementation delegates to {@link #eval(EvalEnv)} via {@link
   * Stack#globalEnv}. In Step 4 and beyond, implementations will override this
   * method to access local variables directly from {@link Stack#slots}.
   */
  default Object eval(final Stack stack) {
    return eval(stack.globalEnv);
  }

  default boolean isConstant() {
    return false;
  }
}

// End Code.java
