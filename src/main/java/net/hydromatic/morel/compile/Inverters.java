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
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.type.TypeSystem;

import com.google.common.collect.ImmutableList;

import java.util.Arrays;
import java.util.List;
import java.util.function.BiFunction;

import static net.hydromatic.morel.ast.CoreBuilder.core;

abstract class Inverters {
  static final Registry REGISTRY = new Builder().build();

  public static Core.Exp invert(TypeSystem typeSystem, Core.Exp exp,
      Core.NamedPat goalPat) {
    return REGISTRY.findGenerator(typeSystem, exp, goalPat);
  }

  /** Pattern of arguments to a function. */
  enum ArgPattern {
    /** Function with two curried arguments, "f a b". */
    F2
  }

  /** Constructs a Registry. */
  private static class Builder {
    final ImmutableList.Builder<Inverter> b = ImmutableList.builder();

    private void register(ArgPattern argPattern, BuiltIn builtIn, int i,
        BiFunction<TypeSystem, List<Core.Exp>, Core.Exp> generate) {
      b.add(new Inverter(argPattern, builtIn, i, generate));
    }

    Registry build() {
      // "from a0 where String.isPrefix a0 a1" -> "prefixesOf a1"
      register(ArgPattern.F2, BuiltIn.STRING_IS_PREFIX, 0, Builder::prefixesOf);

      return new Registry(b.build());
    }

    static Core.Exp prefixesOf(TypeSystem typeSystem, List<Core.Exp> args) {
      return core.applyCurried(Pos.ZERO, typeSystem, BuiltIn.Z_PREFIXES_OF,
          args.get(1));
    }
  }

  /** Rewrite operation that can match a call to a particular function
   * and convert into an expression that gives the possible values of one of
   * the arguments. */
  static class Inverter {
    final ArgPattern argPattern;
    final BuiltIn builtIn;
    final int patternOrdinal;
    final BiFunction<TypeSystem, List<Core.Exp>, Core.Exp> generate;

    /** Creates an Inverter. */
    Inverter(ArgPattern argPattern, BuiltIn builtIn, int patternOrdinal,
        BiFunction<TypeSystem, List<Core.Exp>, Core.Exp> generate) {
      this.argPattern = argPattern;
      this.builtIn = builtIn;
      this.patternOrdinal = patternOrdinal;
      this.generate = generate;
    }
  }

  static class Registry {
    private final ImmutableList<Inverter> inverters;

    Registry(ImmutableList<Inverter> inverters) {
      this.inverters = inverters;
    }

    Core.Exp findGenerator(TypeSystem typeSystem, Core.Exp exp,
        Core.Pat pat) {
      if (exp.op == Op.APPLY) {
        Core.Apply applyExp = (Core.Apply) exp;
        if (applyExp.fn.op == Op.APPLY) {
          Core.Apply applyFn = (Core.Apply) applyExp.fn;
          if (applyFn.fn.op == Op.FN_LITERAL) {
            Core.Literal literal = (Core.Literal) applyFn.fn;
            return find(typeSystem, pat,
                ArgPattern.F2,
                literal.unwrap(BuiltIn.class),
                applyFn.arg,
                applyExp.arg);
          }
        }
      }
      return null;
    }

    Core.Exp find(TypeSystem typeSystem, Core.Pat pat,
        ArgPattern argPattern, BuiltIn builtIn, Core.Exp... args) {
      for (Inverter inverter : inverters) {
        if (inverter.argPattern == argPattern && inverter.builtIn == builtIn) {
          final Core.Exp arg = args[inverter.patternOrdinal];
          if (arg instanceof Core.Id && ((Core.Id) arg).idPat.equals(pat)) {
            return inverter.generate.apply(typeSystem, Arrays.asList(args));
          }
        }
      }
      return null;
    }
  }
}

// End Inverters.java
