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
package net.hydromatic.morel.core;

import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.type.Type;

import java.util.Objects;

import static java.util.Objects.requireNonNull;

/** Abstract Core node. */
public abstract class CoreNode {
  public final Op op;
  public final Type type;

  public CoreNode(Op op, Type type) {
    this.op = requireNonNull(op);
    this.type = requireNonNull(type);
  }

  /** Converts this node into a string.
   *
   * <p>Derived classes <em>may</em> override, but they must produce the same
   * result; so the only reason to override is if they can do it more
   * efficiently.
   */
  @Override public final String toString() {
    // Marked final because you should override unparse, not toString
    return unparse(new CoreWriter(), 0, 0).toString();
  }

  abstract CoreWriter unparse(CoreWriter w, int left, int right);

  /** Accepts a shuttle, calling the
   * {@link CoreShuttle#visit}
   * method appropriate to the type of this node, and returning the result. */
  public abstract CoreNode accept(CoreShuttle shuttle);

  /** Accepts a visitor, calling the
   * {@link CoreVisitor#visit}
   * method appropriate to the type of this node, and returning the result. */
  public abstract void accept(CoreVisitor visitor);
}

// End CoreNode.java
