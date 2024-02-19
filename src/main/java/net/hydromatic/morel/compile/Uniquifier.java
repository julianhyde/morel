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
import net.hydromatic.morel.type.TypeSystem;

import java.util.HashMap;
import java.util.Map;

import static net.hydromatic.morel.ast.CoreBuilder.core;

/** TODO: Javadoc. */
public class Uniquifier extends EnvShuttle {
  final Map<Core.IdPat, Core.IdPat> map;
  private final NameGenerator nameGenerator;

  protected Uniquifier(TypeSystem typeSystem, Environment env,
      NameGenerator nameGenerator, Map<Core.IdPat, Core.IdPat> map) {
    super(typeSystem, env);
    this.nameGenerator = nameGenerator;
    this.map = map;
  }

  public static Uniquifier create(TypeSystem typeSystem,
      NameGenerator nameGenerator, Environment env) {
    return new Uniquifier(typeSystem, env, nameGenerator,
        new HashMap<>());
  }

  @Override protected Uniquifier push(Environment env) {
    return new Uniquifier(typeSystem, env, nameGenerator, map);
  }

  @Override protected Core.IdPat visit(Core.IdPat idPat) {
    return map.computeIfAbsent(idPat,
        idPat1 -> {
          if (env.getOpt(idPat1) != null) {
            return idPat1; // already known
          }
          return core.idPat(idPat1.type, idPat1.name, nameGenerator);
        });
  }

  @Override protected Core.Exp visit(Core.Id id) {
    return id.copy(id.idPat.accept(this));
  }
}

// End Uniquifier.java
