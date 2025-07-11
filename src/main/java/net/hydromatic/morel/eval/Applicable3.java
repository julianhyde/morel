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
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.compile.BuiltIn;

/**
 * Applicable whose argument is a 3-tuple.
 *
 * <p>Implementations that use {@code Applicable3} are more efficient and
 * concise than {@link ApplicableImpl} because there is no need to create an
 * ephemeral tuple (Java {@link List}) to pass the arguments, and Java's
 * generics provide the casting.
 *
 * <p>But the rewrite assumes that the function is <b>strict</b> (always
 * evaluates all arguments, even if the function throws) and doesn't use {@link
 * EvalEnv}, so it is not appropriate for all functions.
 *
 * <p>If a function has an {@code Applicable3} implementation and the argument
 * tuple is evaluated whole, the old evaluation path will be used.
 *
 * @see Applicable2
 * @param <R> return type
 * @param <A0> type of argument 0
 * @param <A1> type of argument 1
 * @param <A2> type of argument 2
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public abstract class Applicable3<R, A0, A1, A2> extends ApplicableImpl
    implements Applicable1<R, List> {
  protected Applicable3(BuiltIn builtIn, Pos pos) {
    super(builtIn, pos);
  }

  protected Applicable3(BuiltIn builtIn) {
    this(builtIn, Pos.ZERO);
  }

  @Override // Applicable1
  public R apply(List list) {
    return apply((A0) list.get(0), (A1) list.get(1), (A2) list.get(2));
  }

  @Override // Applicable
  public Object apply(EvalEnv env, Object argValue) {
    final List list = (List) argValue;
    return apply((A0) list.get(0), (A1) list.get(1), (A2) list.get(2));
  }

  /** Applies this function to its three arguments. */
  public abstract R apply(A0 a0, A1 a1, A2 a2);

  /**
   * Converts this function {@code f(a, b, c)} into a function that can be
   * called {@code f(a)(b)(c)}.
   */
  public Applicable1 curry(BuiltIn builtIn) {
    return new Codes.CurriedApplicable1<Applicable1, A0>(builtIn, this) {
      @Override
      public Applicable1 apply(A0 a0) {
        return new Applicable1<Applicable1, A1>() {
          @Override
          public Applicable1 apply(A1 a1) {
            return new Applicable1<R, A2>() {
              @Override
              public R apply(A2 a2) {
                return Applicable3.this.apply(a0, a1, a2);
              }
            };
          }
        };
      }
    };
  }
}

// End Applicable3.java
