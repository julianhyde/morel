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

import java.util.List;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.eval.Applicable1;
import net.hydromatic.morel.eval.Applicable2;
import net.hydromatic.morel.eval.Stack;

/**
 * Base class with which to implement {@link Applicable2}.
 *
 * @param <R> return type
 * @param <A0> type of argument 0
 * @param <A1> type of argument 1
 */
@SuppressWarnings({"rawtypes", "unchecked"})
abstract class BaseApplicable2<R, A0, A1> extends BaseApplicable
    implements Applicable1<R, List>, Applicable2<R, A0, A1> {
  protected BaseApplicable2(BuiltIn builtIn) {
    super(builtIn);
  }

  @Override // Applicable1
  public R apply(List list) {
    return apply((A0) list.get(0), (A1) list.get(1));
  }

  @Override // Applicable
  public Object apply(Stack stack, Object argValue) {
    final List list = (List) argValue;
    return apply((A0) list.get(0), (A1) list.get(1));
  }

  @Override
  public Applicable1<Applicable1<R, A1>, A0> curry() {
    return new CurriedApplicable1<Applicable1<R, A1>, A0>(builtIn, this) {
      @Override
      public Applicable1<R, A1> apply(A0 a0) {
        return new Applicable1<R, A1>() {
          @Override
          public R apply(A1 a1) {
            return BaseApplicable2.this.apply(a0, a1);
          }
        };
      }
    };
  }
}

// End BaseApplicable2.java
