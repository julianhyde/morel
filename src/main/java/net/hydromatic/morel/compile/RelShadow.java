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

import static java.lang.String.format;

import java.util.concurrent.atomic.AtomicInteger;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Visitor;
import net.hydromatic.morel.type.TypeSystem;

/**
 * Translates every {@code from} in a declaration into a relational tree and
 * checks the result, without changing what the declaration does.
 *
 * <p>This is the shadow of step 1 of {@code plan.md}: while {@link Core.From}
 * still does the work, every query that the test suite compiles is also
 * translated, validated, and checked to have the type it started with. It runs
 * under {@code assert}, so it is on when the tests run and costs nothing when
 * they do not.
 *
 * <p>A query the translator declines -- an outer join, say -- is counted and
 * skipped. A query it translates *wrongly* is an error, because that is a bug
 * in the translation, not a gap in it.
 */
public class RelShadow {
  private static final AtomicInteger TRANSLATED = new AtomicInteger();
  private static final AtomicInteger DECLINED = new AtomicInteger();

  private RelShadow() {}

  /**
   * Returns the number of queries translated so far, for tests that want to
   * know that the shadow is doing something.
   */
  public static int translatedCount() {
    return TRANSLATED.get();
  }

  /** Returns the number of queries the translator declined so far. */
  public static int declinedCount() {
    return DECLINED.get();
  }

  /**
   * Translates and checks every {@code from} in a declaration.
   *
   * <p>Always returns true, so that it can be called from an {@code assert}
   * statement; throws {@link AssertionError} if a translation is wrong.
   */
  public static boolean check(TypeSystem typeSystem, Core.Decl decl) {
    decl.accept(
        new Visitor() {
          @Override
          protected void visit(Core.From from) {
            super.visit(from);
            check(typeSystem, from);
          }
        });
    return true;
  }

  private static void check(TypeSystem typeSystem, Core.From from) {
    final Core.Exp exp;
    try {
      exp = RelTranslator.toRel(typeSystem, from);
    } catch (RuntimeException e) {
      throw new AssertionError("cannot translate to a tree: " + from, e);
    }
    if (exp == null) {
      DECLINED.incrementAndGet();
      return;
    }
    if (!exp.type.equals(from.type)) {
      throw new AssertionError(
          format(
              "tree for '%s' has type %s but the query has type %s",
              from, exp.type.moniker(), from.type.moniker()));
    }
    if (exp instanceof Core.Rel) {
      RelValidator.checkValid(typeSystem, (Core.Rel) exp);
    }
    TRANSLATED.incrementAndGet();
  }
}

// End RelShadow.java
