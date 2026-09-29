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

import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.eval.Applicable;
import net.hydromatic.morel.eval.Describer;

/** Implementation of {@link Applicable} that stores a {@link BuiltIn}. */
abstract class BaseApplicable implements Applicable {
  protected final BuiltIn builtIn;

  protected BaseApplicable(BuiltIn builtIn) {
    this.builtIn = builtIn;
  }

  @Override
  public Describer describe(Describer describer) {
    return describer.start(name(), d -> {});
  }

  protected String name() {
    return builtIn.mlName.startsWith("op ")
        ? builtIn.mlName.substring("op ".length())
        : builtIn.structure + "." + builtIn.mlName;
  }
}

// End BaseApplicable.java
