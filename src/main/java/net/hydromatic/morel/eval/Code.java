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
   * <p>This is the primary evaluation method. All concrete {@code Code} nodes
   * must override this method. The default throws {@link
   * UnsupportedOperationException} to catch any node that has not yet been
   * migrated.
   */
  default Object eval(final Stack stack) {
    throw new UnsupportedOperationException(
        getClass().getSimpleName() + " requires eval(Stack) override");
  }

  /**
   * Returns the maximum number of stack slots that this code node (and its
   * descendants) will push beyond the current stack top during evaluation. Used
   * to compute a tight array size for newly allocated {@link Stack} instances.
   *
   * <p>The default returns 0 (no slots pushed). {@code StackLet1Code} and
   * {@code StackLetPatCode} override this to return their contribution.
   *
   * @see Slots
   */
  default int maxSlots() {
    return 0;
  }

  default boolean isConstant() {
    return false;
  }
}

// End Code.java
