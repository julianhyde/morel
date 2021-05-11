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
package net.hydromatic.morel.compile;

import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.CoreBuilder;
import net.hydromatic.morel.eval.Applicable;
import net.hydromatic.morel.eval.Codes;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.FnType;
import net.hydromatic.morel.type.TypeSystem;

import java.util.List;

/**
 * Shuttle that inlines constant values.
 */
public class Inliner extends EnvShuttle {
  private final TypeSystem typeSystem;

  public Inliner(TypeSystem typeSystem, Environment env) {
    super(env);
    this.typeSystem = typeSystem;
  }

  @Override protected Inliner bind(List<Binding> bindingList) {
    // The "!bindingList.isEmpty()" and "env2 != env" checks are optimizations.
    // If you remove them, this method will have the same effect, just slower.
    if (!bindingList.isEmpty()) {
      final Environment env2 = env.bindAll(bindingList);
      if (env2 != env) {
        return new Inliner(typeSystem, env2);
      }
    }
    return this;
  }

  @Override public Core.Exp visit(Core.Id id) {
    Binding binding = env.getOpt(id.name);
    if (binding != null) {
      Object v = binding.value;
      BuiltIn builtIn = null;
      if (v instanceof Macro) {
        final Macro macro = (Macro) binding.value;
        Core.Exp x = macro.expand(typeSystem, env, ((FnType) id.type).paramType);
        // TODO: just return x? It's already a function literal, probably
        switch (x.op) {
        case FN_LITERAL:
          builtIn = (BuiltIn) ((Core.Literal) x).value;
        }
      }
      if (v instanceof Applicable) {
        builtIn = Codes.BUILT_IN_MAP.get(v);
      }
      if (builtIn != null) {
        return CoreBuilder.core.functionLiteral(typeSystem, builtIn);
      }
    }
    return super.visit(id);
  }
}

// End Inliner.java
