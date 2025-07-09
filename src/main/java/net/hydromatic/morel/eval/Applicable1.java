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

import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.compile.BuiltIn;

/**
 * Applicable whose argument is an atomic value.
 *
 * @see Applicable2
 * @see Applicable3
 * @param <R> return type
 * @param <A0> type of argument
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public abstract class Applicable1<R, A0> extends Codes.BaseApplicable {
  protected Applicable1(BuiltIn builtIn, Pos pos) {
    super(builtIn, pos);
  }

  protected Applicable1(BuiltIn builtIn) {
    this(builtIn, Pos.ZERO);
  }

  @Override
  public Object apply(EvalEnv env, Object argValue) {
    return apply((A0) argValue);
  }

  public abstract R apply(A0 a0);
}

// End Applicable1.java
