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
package net.hydromatic.morel.eval.codes;

import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.eval.Applicable2;
import net.hydromatic.morel.eval.Codes.Positioned;

/**
 * Base class with which to implement {@link Applicable2} and {@link
 * Positioned}.
 *
 * @param <R> return type
 * @param <A0> type of argument 0
 * @param <A1> type of argument 1
 */
abstract class BasePositionedApplicable2<R, A0, A1>
    extends BaseApplicable2<R, A0, A1> implements Positioned {
  protected final Pos pos;

  protected BasePositionedApplicable2(BuiltIn builtIn, Pos pos) {
    super(builtIn);
    this.pos = pos;
  }
}

// End BasePositionedApplicable2.java
