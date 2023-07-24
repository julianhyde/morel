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
  /** Evaluates this expression in an environment and returns the result.
   *
   * <p>As we transition to the new stack-based evaluation model, which calls
   * tail-call optimizations, the default implementation of this method calls
   * {@link #eval(Stack)}. Eventually {@link #eval(Stack)} will be removed
   * and there will be no default implementation. */
  default Object eval0(EvalEnv evalEnv) {
    Stack stack = Stack.of(evalEnv);
    return eval(stack);
  }

  /** Executes this expression, reading the values it needs from the stack, and
   * returns the result. Does not push the result onto the stack. */
  Object eval(Stack stack);

  default boolean isConstant() {
    return false;
  }
}

// End Code.java
