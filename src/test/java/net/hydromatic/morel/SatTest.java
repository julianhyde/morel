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
package net.hydromatic.morel;

import net.hydromatic.morel.util.Sat;
import net.hydromatic.morel.util.Sat.Term;
import net.hydromatic.morel.util.Sat.Variable;
import net.hydromatic.morel.util.TailList;

import org.hamcrest.CoreMatchers;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

/** Tests satisfiability. */
public class SatTest {
  /** Tests {@link TailList}. */
  @Test void testBuild() {
    final Sat sat = new Sat();
    final Variable x = sat.term("x");
    final Variable y = sat.term("y");

    // (x ∨ x ∨ y) ∧ (¬x ∨ ¬y ∨ ¬y) ∧ (¬x ∨ y ∨ y)
    final Term clause0 = sat.or(x, x, y);
    final Term clause1 = sat.or(sat.not(x), sat.not(y), sat.not(y));
    final Term clause2 = sat.or(sat.not(x), y, y);
    final Term formula = sat.and(clause0, clause1, clause2);
    assertThat(formula.toString(),
        is("(x ∨ x ∨ y) ∧ (¬x ∨ ¬y ∨ ¬y) ∧ (¬x ∨ y ∨ y)"));

    final Map<Variable, Boolean> solution = sat.solve(formula);
    assertThat(solution, notNullValue());
    assertThat(solution.toString(), is("{x=false, y=true}"));
  }

}

// End SatTest.java
