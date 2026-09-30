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
package net.hydromatic.morel.eval.code;

import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.eval.Applicable1;
import net.hydromatic.morel.eval.Describable;
import net.hydromatic.morel.eval.Describer;

/**
 * Curried function.
 *
 * <p>It takes one argument, because it is the first stage of several calls.
 *
 * <p>It implements {@link Describable}, because it may need to appear in a
 * plan. (If a call yields another {@link Applicable1}, that function is lighter
 * weight, because it does not need to be described.)
 *
 * @param <R> return type
 * @param <A0> type of first argument
 */
@SuppressWarnings({"rawtypes"})
abstract class CurriedApplicable1<R, A0> extends BaseApplicable1<R, A0> {
  private final Applicable1 parent;

  CurriedApplicable1(BuiltIn builtIn, Applicable1 parent) {
    super(builtIn);
    this.parent = parent;
  }

  @Override
  public Describer describe(Describer describer) {
    return parent.describe(describer);
  }
}

// End CurriedApplicable1.java
