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

import static net.hydromatic.morel.ast.CoreBuilder.core;
import static org.junit.jupiter.api.Assertions.*;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.Optional;
import java.util.Set;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.type.PrimitiveType;
import org.junit.jupiter.api.Test;

/** Unit tests for VarEnvironment class. */
class VarEnvironmentTest {

  // ===== Helper Methods =====

  /** Creates a test NamedPat with given name. */
  private Core.NamedPat namedPat(String name) {
    return core.idPat(PrimitiveType.INT, name, 0);
  }

  /** Creates a dummy Core.Exp for testing. */
  private Core.Exp dummyExp() {
    return core.intLiteral(java.math.BigDecimal.valueOf(42));
  }

  /** Creates a dummy Generator for testing. */
  private PredicateInverter.Generator dummyGenerator(Core.NamedPat pat) {
    return new PredicateInverter.Generator(
        pat,
        dummyExp(),
        Generator.Cardinality.FINITE,
        ImmutableList.<Core.Exp>of(),
        ImmutableList.<Core.NamedPat>of());
  }

  /** Creates an empty type environment for testing. */
  private Environment emptyEnv() {
    return Environments.empty();
  }

  // ===== Factory & Initialization Tests (4 tests) =====

  @Test
  void testInitialWithGoals() {
    Core.NamedPat x = namedPat("x");
    Core.NamedPat y = namedPat("y");
    Environment env = emptyEnv();

    VarEnvironment varEnv =
        VarEnvironment.initial(ImmutableList.<Core.NamedPat>of(x, y), env);

    // Goals should be set
    assertTrue(varEnv.isGoal(x));
    assertTrue(varEnv.isGoal(y));

    // Nothing should be bound or joined initially
    assertFalse(varEnv.isBound(x));
    assertFalse(varEnv.isBound(y));
    assertFalse(varEnv.isJoin(x));
    assertFalse(varEnv.isJoin(y));

    // Type environment should be preserved
    assertEquals(env, varEnv.typeEnv);
  }

  @Test
  void testInitialWithGoalsAndGenerators() {
    Core.NamedPat x = namedPat("x");
    Core.NamedPat y = namedPat("y");
    Environment env = emptyEnv();
    PredicateInverter.Generator genX = dummyGenerator(x);

    ImmutableMap<Core.NamedPat, PredicateInverter.Generator> initialGens =
        ImmutableMap.<Core.NamedPat, PredicateInverter.Generator>of(x, genX);

    VarEnvironment varEnv =
        VarEnvironment.initial(
            ImmutableList.<Core.NamedPat>of(x, y), initialGens, env);

    // Goals should be set
    assertTrue(varEnv.isGoal(x));
    assertTrue(varEnv.isGoal(y));

    // x should be bound, y should not
    assertTrue(varEnv.isBound(x));
    assertFalse(varEnv.isBound(y));

    // Should be able to retrieve generator for x
    Optional<PredicateInverter.Generator> retrieved = varEnv.getGenerator(x);
    assertTrue(retrieved.isPresent());
    assertEquals(genX, retrieved.get());
  }

  @Test
  void testInitialPreservesTypeEnvironment() {
    Core.NamedPat x = namedPat("x");
    Environment env = emptyEnv();

    VarEnvironment varEnv =
        VarEnvironment.initial(ImmutableList.<Core.NamedPat>of(x), env);

    // Type environment should be accessible for subsequent compilation
    assertNotNull(varEnv.typeEnv);
    assertEquals(env, varEnv.typeEnv);
  }

  @Test
  void testInitialEmptyGoals() {
    Environment env = emptyEnv();

    // Edge case: creating with empty goal list should work
    VarEnvironment varEnv =
        VarEnvironment.initial(ImmutableList.<Core.NamedPat>of(), env);

    assertNotNull(varEnv);
    assertTrue(varEnv.goalPats.isEmpty());
    assertTrue(varEnv.boundVars.isEmpty());
    assertTrue(varEnv.joinVars.isEmpty());
  }

  // ===== Immutability Tests (3 tests) =====

  @Test
  void testWithBoundReturnsNewInstance() {
    Core.NamedPat x = namedPat("x");
    Core.NamedPat y = namedPat("y");
    Environment env = emptyEnv();

    VarEnvironment original =
        VarEnvironment.initial(ImmutableList.<Core.NamedPat>of(x, y), env);
    VarEnvironment modified = original.withBound(x, dummyGenerator(x));

    // Should return different instance
    assertNotSame(original, modified);

    // Original should be unchanged
    assertFalse(original.isBound(x));

    // Modified should have the binding
    assertTrue(modified.isBound(x));
  }

  @Test
  void testWithJoinVarReturnsNewInstance() {
    Core.NamedPat x = namedPat("x");
    Environment env = emptyEnv();

    VarEnvironment original =
        VarEnvironment.initial(ImmutableList.<Core.NamedPat>of(x), env);
    VarEnvironment modified = original.withJoinVar(x);

    // Should return different instance
    assertNotSame(original, modified);

    // Original should be unchanged
    assertFalse(original.isJoin(x));

    // Modified should have join variable
    assertTrue(modified.isJoin(x));
  }

  @Test
  void testWithJoinVarsReturnsNewInstance() {
    Core.NamedPat x = namedPat("x");
    Core.NamedPat y = namedPat("y");
    Environment env = emptyEnv();

    VarEnvironment original =
        VarEnvironment.initial(ImmutableList.<Core.NamedPat>of(x, y), env);
    VarEnvironment modified =
        original.withJoinVars(ImmutableSet.<Core.NamedPat>of(x, y));

    // Should return different instance
    assertNotSame(original, modified);

    // Original should be unchanged
    assertFalse(original.isJoin(x));
    assertFalse(original.isJoin(y));

    // Modified should have both join variables
    assertTrue(modified.isJoin(x));
    assertTrue(modified.isJoin(y));
  }

  // ===== Binding Operations Tests (3 tests) =====

  @Test
  void testWithBoundSingleVariable() {
    Core.NamedPat x = namedPat("x");
    Environment env = emptyEnv();
    PredicateInverter.Generator gen = dummyGenerator(x);

    VarEnvironment varEnv =
        VarEnvironment.initial(ImmutableList.<Core.NamedPat>of(x), env);
    VarEnvironment withBinding = varEnv.withBound(x, gen);

    // Variable should be bound
    assertTrue(withBinding.isBound(x));

    // Generator should be retrievable
    Optional<PredicateInverter.Generator> retrieved =
        withBinding.getGenerator(x);
    assertTrue(retrieved.isPresent());
    assertEquals(gen, retrieved.get());
  }

  @Test
  void testWithBoundMultipleVariables() {
    Core.NamedPat x = namedPat("x");
    Core.NamedPat y = namedPat("y");
    Core.NamedPat z = namedPat("z");
    Environment env = emptyEnv();
    PredicateInverter.Generator genX = dummyGenerator(x);
    PredicateInverter.Generator genY = dummyGenerator(y);
    PredicateInverter.Generator genZ = dummyGenerator(z);

    VarEnvironment varEnv =
        VarEnvironment.initial(ImmutableList.<Core.NamedPat>of(x, y, z), env)
            .withBound(x, genX)
            .withBound(y, genY)
            .withBound(z, genZ);

    // All bindings should be present
    assertTrue(varEnv.isBound(x));
    assertTrue(varEnv.isBound(y));
    assertTrue(varEnv.isBound(z));

    // All generators should be retrievable
    assertEquals(genX, varEnv.getGenerator(x).get());
    assertEquals(genY, varEnv.getGenerator(y).get());
    assertEquals(genZ, varEnv.getGenerator(z).get());
  }

  @Test
  void testWithBoundOverwrite() {
    Core.NamedPat x = namedPat("x");
    Environment env = emptyEnv();
    PredicateInverter.Generator gen1 = dummyGenerator(x);
    PredicateInverter.Generator gen2 = dummyGenerator(x);

    VarEnvironment varEnv =
        VarEnvironment.initial(ImmutableList.<Core.NamedPat>of(x), env)
            .withBound(x, gen1);

    // Binding same variable twice should throw IllegalArgumentException
    // (ImmutableMap.Builder doesn't allow duplicate keys)
    assertThrows(
        IllegalArgumentException.class, () -> varEnv.withBound(x, gen2));
  }

  // ===== Join Variable Tracking Tests (3 tests) =====

  @Test
  void testWithJoinVarMarksVariable() {
    Core.NamedPat x = namedPat("x");
    Environment env = emptyEnv();

    VarEnvironment varEnv =
        VarEnvironment.initial(ImmutableList.<Core.NamedPat>of(x), env)
            .withJoinVar(x);

    // Variable should be marked as join
    assertTrue(varEnv.isJoin(x));
  }

  @Test
  void testWithJoinVarsMultiple() {
    Core.NamedPat x = namedPat("x");
    Core.NamedPat y = namedPat("y");
    Core.NamedPat z = namedPat("z");
    Environment env = emptyEnv();

    VarEnvironment varEnv =
        VarEnvironment.initial(ImmutableList.<Core.NamedPat>of(x, y, z), env)
            .withJoinVars(ImmutableSet.<Core.NamedPat>of(x, y, z));

    // All should be marked as join
    assertTrue(varEnv.isJoin(x));
    assertTrue(varEnv.isJoin(y));
    assertTrue(varEnv.isJoin(z));
  }

  @Test
  void testWithJoinVarAndBound() {
    Core.NamedPat x = namedPat("x");
    Environment env = emptyEnv();
    PredicateInverter.Generator gen = dummyGenerator(x);

    // Same variable can be both bound and join (needed for PPT joins)
    VarEnvironment varEnv =
        VarEnvironment.initial(ImmutableList.<Core.NamedPat>of(x), env)
            .withBound(x, gen)
            .withJoinVar(x);

    assertTrue(varEnv.isBound(x));
    assertTrue(varEnv.isJoin(x));
  }

  // ===== Query Methods Tests (3 tests) =====

  @Test
  void testUnboundGoals() {
    Core.NamedPat x = namedPat("x");
    Core.NamedPat y = namedPat("y");
    Core.NamedPat z = namedPat("z");
    Environment env = emptyEnv();
    PredicateInverter.Generator genY = dummyGenerator(y);

    // Create with goals [x, y, z], bind only y
    VarEnvironment varEnv =
        VarEnvironment.initial(ImmutableList.<Core.NamedPat>of(x, y, z), env)
            .withBound(y, genY);

    // unboundGoals() should return [x, z]
    Set<Core.NamedPat> unbound = varEnv.unboundGoals();
    assertEquals(2, unbound.size());
    assertTrue(unbound.contains(x));
    assertTrue(unbound.contains(z));
    assertFalse(unbound.contains(y));
  }

  @Test
  void testUnboundGoalsEmptyWhenAllBound() {
    Core.NamedPat x = namedPat("x");
    Core.NamedPat y = namedPat("y");
    Environment env = emptyEnv();
    PredicateInverter.Generator genX = dummyGenerator(x);
    PredicateInverter.Generator genY = dummyGenerator(y);

    // All goals have generators
    VarEnvironment varEnv =
        VarEnvironment.initial(ImmutableList.<Core.NamedPat>of(x, y), env)
            .withBound(x, genX)
            .withBound(y, genY);

    // unboundGoals() should be empty
    Set<Core.NamedPat> unbound = varEnv.unboundGoals();
    assertTrue(unbound.isEmpty());
  }

  @Test
  void testGetGeneratorReturnsCorrectBinding() {
    Core.NamedPat x = namedPat("x");
    Core.NamedPat y = namedPat("y");
    Environment env = emptyEnv();
    PredicateInverter.Generator genX = dummyGenerator(x);

    VarEnvironment varEnv =
        VarEnvironment.initial(ImmutableList.<Core.NamedPat>of(x, y), env)
            .withBound(x, genX);

    // getGenerator() returns correct Core.Exp for bound variable
    Optional<PredicateInverter.Generator> retrievedX = varEnv.getGenerator(x);
    assertTrue(retrievedX.isPresent());
    assertEquals(genX, retrievedX.get());

    // getGenerator() returns empty for unbound variable
    Optional<PredicateInverter.Generator> retrievedY = varEnv.getGenerator(y);
    assertFalse(retrievedY.isPresent());
  }

  // ===== Edge Cases & Integration Tests (1 test) =====

  @Test
  void testChainedOperationsPreserveState() {
    Core.NamedPat x = namedPat("x");
    Core.NamedPat y = namedPat("y");
    Core.NamedPat z = namedPat("z");
    Environment env = emptyEnv();
    PredicateInverter.Generator genX = dummyGenerator(x);
    PredicateInverter.Generator genY = dummyGenerator(y);

    // Complex chain of operations
    VarEnvironment varEnv =
        VarEnvironment.initial(ImmutableList.<Core.NamedPat>of(x, y, z), env)
            .withBound(x, genX)
            .withJoinVar(x)
            .withBound(y, genY)
            .withJoinVars(ImmutableSet.<Core.NamedPat>of(y, z));

    // Verify final state
    // Goals
    assertTrue(varEnv.isGoal(x));
    assertTrue(varEnv.isGoal(y));
    assertTrue(varEnv.isGoal(z));

    // Bindings
    assertTrue(varEnv.isBound(x));
    assertTrue(varEnv.isBound(y));
    assertFalse(varEnv.isBound(z));

    // Join variables
    assertTrue(varEnv.isJoin(x));
    assertTrue(varEnv.isJoin(y));
    assertTrue(varEnv.isJoin(z));

    // Generators
    assertEquals(genX, varEnv.getGenerator(x).get());
    assertEquals(genY, varEnv.getGenerator(y).get());
    assertFalse(varEnv.getGenerator(z).isPresent());

    // Unbound goals
    Set<Core.NamedPat> unbound = varEnv.unboundGoals();
    assertEquals(1, unbound.size());
    assertTrue(unbound.contains(z));
  }
}

// End VarEnvironmentTest.java
