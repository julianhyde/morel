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

import static java.util.Objects.requireNonNull;

import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.util.MorelException;
import org.jspecify.annotations.Nullable;

/** Java exception that wraps an exception thrown by the Morel runtime. */
public class MorelRuntimeException extends RuntimeException
    implements MorelException {
  private final BuiltInExn e;
  private final @Nullable Object payload;
  private final Pos pos;

  /** Creates a MorelRuntimeException. */
  public MorelRuntimeException(BuiltInExn e, Pos pos) {
    this(e, null, pos);
  }

  /** Creates a MorelRuntimeException with a runtime payload. */
  public MorelRuntimeException(
      BuiltInExn e, @Nullable Object payload, Pos pos) {
    this.e = requireNonNull(e);
    this.payload = payload;
    this.pos = requireNonNull(pos);
  }

  /** Returns which built-in exception this is. */
  public BuiltInExn builtInExn() {
    return e;
  }

  @Override
  public String toString() {
    return e.mlName() + " at " + pos;
  }

  @Override
  public StringBuilder describeTo(StringBuilder buf) {
    buf.append(UNCAUGHT_PREFIX).append(e.mlName());
    if (payload instanceof Description) {
      buf.append(" [").append(payload).append("]");
    } else if (payload != null) {
      buf.append(" [")
          .append(e.mlName())
          .append(": ")
          .append(payload)
          .append("]");
    } else if (e.description != null) {
      buf.append(" [").append(e.description).append("]");
    }
    return buf;
  }

  @Override
  public Pos pos() {
    return pos;
  }

  /** How a description of an uncaught exception begins. */
  public static final String UNCAUGHT_PREFIX = "uncaught exception ";

  /**
   * Payload of a {@link MorelRuntimeException} that describes the failure in
   * full, and is therefore rendered without the exception's name.
   *
   * <p>An exception with an ordinary payload renders it as "{@code Fail: no
   * such file}"; one with a {@code Description} renders "{@code ~1 is not a
   * valid nat}", which reads better when the description is a sentence and the
   * exception carries no value at the Morel level.
   */
  public static class Description {
    private final String s;

    public Description(String s) {
      this.s = requireNonNull(s);
    }

    @Override
    public String toString() {
      return s;
    }
  }
}

// End MorelRuntimeException.java
