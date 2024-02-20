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
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.TypeSystem;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static net.hydromatic.morel.ast.CoreBuilder.core;

/** TODO: Javadoc. */
public class Uniquifier2 extends EnvShuttle {
  final Map<Core.IdPat, Core.IdPat> map;
  final Map<String, AtomicInteger> names;

  /** Creates a Uniquifier2. */
  private Uniquifier2(TypeSystem typeSystem, Environment env,
      Map<Core.IdPat, Core.IdPat> map,
      Map<String, AtomicInteger> names) {
    super(typeSystem, env);
    this.map = map;
    this.names = names;
  }

  /** Creates a top-level Uniquifier2. */
  public static Uniquifier2 create(TypeSystem typeSystem, Environment env) {
    return new Uniquifier2(typeSystem, env, new HashMap<>(), new HashMap<>());
  }

  @Override protected Uniquifier2 push(Environment env) {
    return new Uniquifier2(typeSystem, env, map, names);
  }

  @Override protected Core.IdPat visit(Core.IdPat idPat) {
    return map.computeIfAbsent(idPat,
        idPat1 -> {
          Binding binding = env.getOpt(idPat1);
          if (binding != null) {
            // Identifier is already in the environment.
            return idPat1;
          }
          AtomicInteger atomicInteger =
              names.computeIfAbsent(idPat.name, name -> new AtomicInteger());
          return core.idPat(idPat1.type, idPat1.name,
              -atomicInteger.incrementAndGet());
        });
  }

  @Override protected Core.Exp visit(Core.Id id) {
    return id.copy(id.idPat.accept(this));
  }
}

// End Uniquifier2.java
