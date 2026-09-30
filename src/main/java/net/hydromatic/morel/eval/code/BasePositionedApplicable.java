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

import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.eval.Applicable;
import net.hydromatic.morel.eval.Codes.Positioned;

/**
 * Implementation of both {@link Applicable} and {@link Positioned}. Remembers
 * its {@link BuiltIn} so that it can re-position.
 */
abstract class BasePositionedApplicable extends BaseApplicable
    implements Positioned {
  protected final Pos pos;

  protected BasePositionedApplicable(BuiltIn builtIn, Pos pos) {
    super(builtIn);
    this.pos = pos;
  }
}

// End BasePositionedApplicable.java
