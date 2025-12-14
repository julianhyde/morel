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

import static net.hydromatic.morel.ast.AstBuilder.ast;
import static net.hydromatic.morel.ast.CoreBuilder.core;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasToString;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Optional;
import net.hydromatic.morel.ast.Ast;
import net.hydromatic.morel.ast.Ast.Decl;
import net.hydromatic.morel.ast.AstNode;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.compile.PredicateInverter.Result;
import net.hydromatic.morel.parse.MorelParserImpl;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import org.junit.jupiter.api.Test;

/** Tests for {@link PredicateInverter}. */
public class PredicateInverterTest {

  /** Test fixture with common setup. */
  private static class Fixture {
    final TypeSystem typeSystem = new TypeSystem();

    {
      // Register built-in types
      BuiltIn.dataTypes(typeSystem, new ArrayList<>());
    }

    final PrimitiveType intType = PrimitiveType.INT;
    final PrimitiveType stringType = PrimitiveType.STRING;

    final Type intListType = typeSystem.listType(intType);
    final Type stringListType = typeSystem.listType(stringType);

    // Variables for testing
    final Core.IdPat xPat = core.idPat(intType, "x", 0);
    final Core.Id xId = core.id(xPat);

    final Core.IdPat yPat = core.idPat(intType, "y", 0);
    final Core.Id yId = core.id(yPat);

    final Core.IdPat pPat = core.idPat(stringType, "p", 0);
    final Core.Id pId = core.id(pPat);

    final Core.IdPat sPat = core.idPat(stringType, "s", 0);
    final Core.Id sId = core.id(sPat);

    final Core.IdPat empnoPat = core.idPat(intType, "empno", 0);
    final Core.Id empnoId = core.id(empnoPat);

    final Core.IdPat deptnoPat = core.idPat(intType, "deptno", 0);
    final Core.Id deptnoId = core.id(deptnoPat);

    final Core.IdPat dnamePat = core.idPat(stringType, "dname", 0);
    final Core.Id dnameId = core.id(dnamePat);

    // Lists for testing
    final Core.IdPat myListPat = core.idPat(intListType, "myList", 0);
    final Core.Id myListId = core.id(myListPat);

    final Type edgeRecordType =
        typeSystem.recordType(RecordType.map("x", intType, "y", intType));
    final Type edgeListType = typeSystem.listType(edgeRecordType);
    final Core.IdPat edgesPat = core.idPat(edgeListType, "edges", 0);
    final Core.Id edgesId = core.id(edgesPat);

    // Function types for testing user-defined functions
    final Type tupleTwoIntsType = typeSystem.tupleType(intType, intType);
    final Type intIntBoolFnType =
        typeSystem.fnType(tupleTwoIntsType, PrimitiveType.BOOL);
    final Type stringIntTupleBoolFnType =
        typeSystem.fnType(
            typeSystem.tupleType(intType, stringType), PrimitiveType.BOOL);

    Core.Literal intLiteral(int i) {
      return core.intLiteral(BigDecimal.valueOf(i));
    }

    Core.Literal stringLiteral(String s) {
      return core.stringLiteral(s);
    }

    /** Converts a Morel expression to a Core.Exp. */
    public Core.Exp parseExp(String s) {
      try {
        // Parse the string to AST
        final MorelParserImpl parser = new MorelParserImpl(new StringReader(s));
        parser.zero("test");
        final AstNode astNode = parser.statementEofSafe();

        // Convert AST.Exp to AST.Decl (wrap in val declaration)
        final Decl decl;
        if (astNode instanceof Ast.Exp) {
          decl =
              ast.valDecl(
                  Pos.ZERO,
                  false, // not recursive
                  false, // not inferred
                  ImmutableList.of(
                      ast.valBind(
                          Pos.ZERO,
                          ast.idPat(Pos.ZERO, "it"),
                          (Ast.Exp) astNode)));
        } else {
          decl = (Decl) astNode;
        }

        // Type-check and resolve
        final net.hydromatic.morel.eval.Session session =
            new net.hydromatic.morel.eval.Session(
                java.util.Collections.emptyMap(), typeSystem);
        final Environment env =
            Environments.env(
                typeSystem, session, java.util.Collections.emptyMap());
        final TypeResolver.Resolved resolved =
            TypeResolver.deduceType(env, decl, typeSystem, w -> {});

        // Convert to Core
        final Resolver resolver = Resolver.of(resolved.typeMap, env, null);
        final Core.Decl coreDecl0 = resolver.toCore(resolved.node);

        final Inliner inliner = Inliner.of(typeSystem, env, null);
        final Core.Decl coreDecl = coreDecl0.accept(inliner);

        // Extract the expression from the Core.Decl
        if (coreDecl instanceof Core.NonRecValDecl) {
          final Core.NonRecValDecl valDecl = (Core.NonRecValDecl) coreDecl;
          return valDecl.exp;
        }
        throw new RuntimeException("Expected NonRecValDecl, got " + coreDecl);
      } catch (Exception e) {
        throw new RuntimeException("Failed to parse: " + s, e);
      }
    }
  }

  /**
   * Test 1: Invert 'x elem myList' to 'myList'.
   *
   * <p>Given the predicate {@code x elem myList}, invert it to generate all
   * values of {@code x}. The result should simply be {@code myList}.
   */
  @Test
  void testInvertSimpleElem() {
    final Fixture f = new Fixture();

    // Build: x elem myList
    final Core.Exp predicate = core.elem(f.typeSystem, f.xId, f.myListId);

    // Invert to generate x
    final Optional<Result> resultOpt =
        PredicateInverter.invert(
            f.typeSystem,
            null,
            predicate,
            ImmutableSet.of(f.xPat),
            ImmutableSortedMap.of());

    // Should successfully invert to myList
    assertTrue(
        resultOpt.isPresent(), "Should successfully invert 'x elem myList'");
    final Result result = resultOpt.get();

    assertThat(result.generator, hasToString("myList"));
    assertThat(result.mayHaveDuplicates, is(false));
    assertThat(result.isSupersetOfSolution, is(false));
    assertThat(result.satisfiedPats, is(ImmutableSet.of(f.xPat)));
  }

  /**
   * Tests that predicate {@code String.isPrefix p s} inverts to {@code
   * List.tabulate(String.size s + 1, fn i => String.substring(s, 0, i))} so
   * that {@code generate "abcd"} returns {@code ["","a","ab","abc","abcd"]}.
   */
  @Test
  void testInvertStringIsPrefix() {
    final Fixture f = new Fixture();

    // The expression "String.isPrefix p s"
    final Core.Exp stringIsPrefixFn =
        core.functionLiteral(f.typeSystem, BuiltIn.STRING_IS_PREFIX);
    final Core.Exp predicate =
        core.apply(
            Pos.ZERO, core.apply(Pos.ZERO, stringIsPrefixFn, f.pId), f.sId);

    // Invert to generate s
    final Optional<Result> resultOpt =
        PredicateInverter.invert(
            f.typeSystem,
            Environments.empty(),
            predicate,
            ImmutableSet.of(f.pPat),
            ImmutableSortedMap.of());

    // Should successfully invert to List.tabulate expression
    assertTrue(
        resultOpt.isPresent(),
        "Should successfully invert 'String.isPrefix s \"abcd\"'");
    final Result result = resultOpt.get();

    // Expected: List.tabulate(String.size s + 1, fn i => String.substring(s, 0,
    // i))
    String expected =
        "#tabulate List (op + (#size String s, 1), fn i => #substring String (s, 0, i))";

    assertThat(result.generator, hasToString(expected));
    assertThat(result.mayHaveDuplicates, is(false));
    assertThat(result.isSupersetOfSolution, is(false));
    assertThat(result.satisfiedPats, is(ImmutableSet.of(f.pPat)));
  }

  /**
   * Test 3: Invert a call to a composite function with exists.
   *
   * <p>Given:
   *
   * <pre>{@code
   * fun empInDept(empno, deptno) = ... (* can be inverted *)
   * fun deptName(deptno, dname) = ...   (* can be inverted *)
   * fun empInDeptName(empno, dname) =
   *   exists deptno where empInDept(empno, deptno) andalso deptName(deptno, dname)
   * }</pre>
   *
   * <p>When we invert {@code empInDeptName(empno, dname)}, it should:
   *
   * <ol>
   *   <li>Invert {@code empInDept(empno, deptno)} to get possible (empno,
   *       deptno) pairs
   *   <li>Invert {@code deptName(deptno, dname)} to get possible (deptno,
   *       dname) pairs
   *   <li>Join them on deptno
   *   <li>Project to (empno, dname)
   * </ol>
   */
  @Test
  void testInvertCompositeWithExists() {
    final Fixture f = new Fixture();

    // For this test, we'll manually construct the AST for:
    // exists deptno where empInDept(empno, deptno) andalso deptName(deptno,
    // dname)
    //
    // This is a more complex test that will require the inverter to handle:
    // 1. Function calls (empInDept, deptName)
    // 2. Conjunction (andalso)
    // 3. Existential quantification (exists)

    // Build: empInDept(empno, deptno) andalso deptName(deptno, dname)
    // We'll represent user functions as ID nodes with apply
    final Core.IdPat empInDeptPat =
        core.idPat(f.intIntBoolFnType, "empInDept", 0);
    final Core.IdPat deptNamePat =
        core.idPat(f.stringIntTupleBoolFnType, "deptName", 0);

    // empInDept(empno, deptno)
    final Core.Exp empInDeptCall =
        core.apply(
            Pos.ZERO,
            PrimitiveType.BOOL,
            core.id(empInDeptPat),
            core.tuple(f.typeSystem, f.empnoId, f.deptnoId));

    // deptName(deptno, dname)
    final Core.Exp deptNameCall =
        core.apply(
            Pos.ZERO,
            PrimitiveType.BOOL,
            core.id(deptNamePat),
            core.tuple(f.typeSystem, f.deptnoId, f.dnameId));

    // empInDept(empno, deptno) andalso deptName(deptno, dname)
    final Core.Exp conjunction =
        core.andAlso(f.typeSystem, empInDeptCall, deptNameCall);

    // Invert to generate (empno, dname), with deptno as an intermediate
    // variable
    final Optional<Result> resultOpt =
        PredicateInverter.invert(
            f.typeSystem,
            null,
            conjunction,
            ImmutableSet.of(f.empnoPat, f.dnamePat),
            ImmutableSortedMap.of());

    // Should successfully invert
    assumeTrue(false, "TODO enable test");
    assertTrue(
        resultOpt.isPresent(),
        "Should successfully invert composite function with exists");
    final Result result = resultOpt.get();

    // The generator should be a join
    assertThat(
        result.satisfiedPats, is(ImmutableSet.of(f.empnoPat, f.dnamePat)));
    // May have duplicates if the underlying relations do
    // isSupersetOfSolution depends on whether we can push down all filters
  }

  /**
   * Test 4: Invert a call to 'fun edge(x, y)'.
   *
   * <p>Given {@code fun edge(x, y) = {x, y} elem edges}, when we invert {@code
   * edge(x, y)} to generate both x and y, it should return {@code edges}.
   */
  @Test
  void testInvertEdgeFunction() {
    final Fixture f = new Fixture();

    // Build a function call: edge(x, y)
    // Where edge is defined as: fun edge(x, y) = {x, y} elem edges
    final Core.IdPat edgeFnPat = core.idPat(f.intIntBoolFnType, "edge", 0);

    // edge(x, y)
    final Core.Exp edgeCall =
        core.apply(
            Pos.ZERO,
            PrimitiveType.BOOL,
            core.id(edgeFnPat),
            core.tuple(f.typeSystem, f.xId, f.yId));

    // Invert to generate (x, y)
    final Optional<Result> resultOpt =
        PredicateInverter.invert(
            f.typeSystem,
            null,
            edgeCall,
            ImmutableSet.of(f.xPat, f.yPat),
            ImmutableSortedMap.of());

    // Should successfully invert
    assumeTrue(false, "TODO enable test");
    assertTrue(
        resultOpt.isPresent(), "Should successfully invert 'edge(x, y)'");
    final Result result = resultOpt.get();

    // After inlining and inverting, should get edges
    // (actual implementation will need function body lookup)
    assertThat(result.satisfiedPats, is(ImmutableSet.of(f.xPat, f.yPat)));
  }

  /**
   * Test 4b: Invert a recursive 'fun path(x, y)' function (transitive closure).
   *
   * <p>Given:
   *
   * <pre>{@code
   * val edges = [(1, 2), (2, 3)]
   * fun edge (x, y) = (x, y) elem edges
   * fun path (x, y) = edge (x, y) orelse
   *   (exists z where edge (x, z) andalso path (z, y))
   * }</pre>
   *
   * <p>When we invert {@code path(x, y)}, it should generate a {@code
   * Relational.iterate} expression:
   *
   * <pre>{@code
   * Relational.iterate edges
   *   (fn (old, new) =>
   *     from (x, z) in edges,
   *          (z2, y) in new
   *       where z = z2
   *       yield (x, y))
   * }</pre>
   *
   * <p>This computes the transitive closure and should produce: [(1,2), (2,3),
   * (1,3)]
   */
  @Test
  void testInvertPathFunction() {
    final Fixture f = new Fixture();

    // Create edges list: [(1, 2), (2, 3)]
    final Core.Exp edges =
        core.list(
            f.typeSystem,
            core.tuple(f.typeSystem, f.intLiteral(1), f.intLiteral(2)),
            core.tuple(f.typeSystem, f.intLiteral(2), f.intLiteral(3)));

    // Simplified test: Just verify that user function calls are detected
    // Full function inlining and inversion will be tested in integration tests

    // Build: someFunction(x, y)
    final Core.IdPat someFnPat =
        core.idPat(f.intIntBoolFnType, "someFunction", 0);
    final Core.Exp functionCall =
        core.apply(
            Pos.ZERO,
            PrimitiveType.BOOL,
            core.id(someFnPat),
            core.tuple(f.typeSystem, f.xId, f.yId));

    // Invert to generate (x, y)
    final Optional<Result> resultOpt =
        PredicateInverter.invert(
            f.typeSystem,
            null,
            functionCall,
            ImmutableSet.of(f.xPat, f.yPat),
            ImmutableSortedMap.of());

    // Currently returns empty because we don't have an environment
    // with the function definition
    // TODO: Implement full function inlining and inversion
    assertThat(
        resultOpt.isPresent(),
        is(false)); // Without env, user functions return empty
  }

  /**
   * Tests that {@code x > y andalso x < y + 10} can be inverted to generate y
   * values when x is known to be 3, 5, or 7.
   *
   * <p>Given x is bound to [3, 5, 7], we want to find all y such that:
   *
   * <ul>
   *   <li>{@code 3 > y andalso 3 < y + 10}, which gives y in (-infinity, 3)
   *       intersect (-7, infinity) = (-7, 3)
   *   <li>{@code 5 > y andalso 5 < y + 10}, which gives y in (-infinity, 5)
   *       intersect (-5, infinity) = (-5, 5)
   *   <li>{@code 7 > y andalso 7 < y + 10}, which gives y in (-infinity, 7)
   *       intersect (-3, infinity) = (-3, 7)
   * </ul>
   *
   * <p>For integer values:
   *
   * <ul>
   *   <li>x=3: y in {-6, -5, -4, -3, -2, -1, 0, 1, 2}
   *   <li>x=5: y in {-4, -3, -2, -1, 0, 1, 2, 3, 4}
   *   <li>x=7: y in {-2, -1, 0, 1, 2, 3, 4, 5, 6}
   * </ul>
   *
   * <p>Expected generator structure:
   *
   * <pre>{@code
   * from x in [3,5,7], y in List.tabulate(9, fn k => x - 9 + k) yield y
   * }</pre>
   */
  @Test
  void testInvertRangeConstraintsForY() {
    final Fixture f = new Fixture();

    // Build: x > y andalso x < y + 10
    final Core.Exp xGreaterY =
        core.call(
            f.typeSystem, BuiltIn.OP_GT, f.intType, Pos.ZERO, f.xId, f.yId);
    final Core.Exp yPlus10 =
        core.call(
            f.typeSystem,
            BuiltIn.OP_PLUS,
            f.intType,
            Pos.ZERO,
            f.yId,
            f.intLiteral(10));
    final Core.Exp xLessYPlus10 =
        core.call(
            f.typeSystem, BuiltIn.OP_LT, f.intType, Pos.ZERO, f.xId, yPlus10);
    final Core.Exp predicate =
        core.andAlso(f.typeSystem, xGreaterY, xLessYPlus10);

    // x is bound to [3, 5, 7]
    final Core.Exp xExtent =
        core.list(
            f.typeSystem, f.intLiteral(3), f.intLiteral(5), f.intLiteral(7));

    // Invert to generate y, given x
    final Optional<Result> resultOpt =
        PredicateInverter.invert(
            f.typeSystem,
            null,
            predicate,
            ImmutableSet.of(f.yPat),
            ImmutableSortedMap.of(f.xPat, xExtent));

    // Should successfully invert
    assumeTrue(false, "TODO enable test");
    assertTrue(
        resultOpt.isPresent(),
        "Should successfully invert range constraints for y");
    final Result result = resultOpt.get();

    // The generator should produce y values for each x value
    // Expected structure: from x in [3,5,7], y in List.tabulate(9, fn k => x -
    // 9 + k) yield y
    assertThat(result.satisfiedPats, is(ImmutableSet.of(f.yPat)));
  }

  /**
   * Tests that {@code x > y andalso x < y + 10} can be inverted to generate x
   * values when y is given.
   *
   * <p>Given y is bound to some value (or set of values), we want to find all x
   * such that: {@code y < x < y + 10}.
   *
   * <p>For example, if y = 5, then x in {6, 7, 8, 9, 10, 11, 12, 13, 14}.
   *
   * <p>Expected generator structure:
   *
   * <pre>{@code
   * from y in [0,5,10], x in List.tabulate(9, fn k => y + 1 + k) yield x
   * }</pre>
   */
  @Test
  void testInvertRangeConstraintsForX() {
    final Fixture f = new Fixture();

    // Build: x > y andalso x < y + 10
    final Core.Exp xGreaterY =
        core.call(
            f.typeSystem, BuiltIn.OP_GT, f.intType, Pos.ZERO, f.xId, f.yId);
    final Core.Exp yPlus10 =
        core.call(
            f.typeSystem,
            BuiltIn.OP_PLUS,
            f.intType,
            Pos.ZERO,
            f.yId,
            f.intLiteral(10));
    final Core.Exp xLessYPlus10 =
        core.call(
            f.typeSystem, BuiltIn.OP_LT, f.intType, Pos.ZERO, f.xId, yPlus10);
    final Core.Exp predicate =
        core.andAlso(f.typeSystem, xGreaterY, xLessYPlus10);

    // y is bound to [0, 5, 10]
    final Core.Exp yExtent =
        core.list(
            f.typeSystem, f.intLiteral(0), f.intLiteral(5), f.intLiteral(10));

    // Invert to generate x, given y
    final Optional<Result> resultOpt =
        PredicateInverter.invert(
            f.typeSystem,
            null,
            predicate,
            ImmutableSet.of(f.xPat),
            ImmutableSortedMap.of(f.yPat, yExtent));

    // Should successfully invert
    assertTrue(
        resultOpt.isPresent(),
        "Should successfully invert range constraints for x");
    final Result result = resultOpt.get();

    // The generator should produce x values for each y value.
    // Expected structure (unsimplified):
    //   List.tabulate(op - (op + (y, 10), y), fn k => op + (y, k))
    // Which is equivalent to: List.tabulate(10 - 0, fn k => y + 0 + k)
    // Which simplifies to: List.tabulate(10, fn k => y + k)
    // But we generate it from the bounds: y < x < y + 10
    // After adjusting for strict bounds: y + 1 <= x <= y + 10
    // So: List.tabulate((y + 10 + 1) - (y + 1), fn k => (y + 1) + k)
    assertThat(result.satisfiedPats, is(ImmutableSet.of(f.xPat)));
    String expected = "#tabulate List (9, fn k => y + 1 + k)";
    assertThat(result.generator, hasToString(expected));
    assertThat(result.mayHaveDuplicates, is(false));
    assertThat(result.isSupersetOfSolution, is(false));
  }

  /**
   * Test 7: Verify that uninvertible predicates return empty.
   *
   * <p>For example, {@code x * x = 25} cannot be easily inverted to generate x
   * (would require symbolic math).
   */
  @Test
  void testUninvertiblePredicate() {
    final Fixture f = new Fixture();

    // Build: x * x = 25 (uninvertible without symbolic solver)
    final Core.Exp xSquared =
        core.call(
            f.typeSystem, BuiltIn.OP_TIMES, f.intType, Pos.ZERO, f.xId, f.xId);
    final Core.Exp predicate =
        core.call(
            f.typeSystem,
            BuiltIn.OP_EQ,
            PrimitiveType.BOOL,
            Pos.ZERO,
            xSquared,
            f.intLiteral(25));

    // Try to invert to generate x
    final Optional<Result> resultOpt =
        PredicateInverter.invert(
            f.typeSystem,
            null,
            predicate,
            ImmutableSet.of(f.xPat),
            ImmutableSortedMap.of());

    // Should fail to invert
    assertThat(resultOpt.isPresent(), is(false));
  }

  private static void checkSimplify(
      String message, String s, String expectedToString) {
    final Fixture f = new Fixture();
    final Core.Exp e = f.parseExp(s);
    Core.Exp e2 = Simplifier.simplify(f.typeSystem, e);
    assertThat(message, e2, hasToString(expectedToString));
  }

  /** Tests various expression simplifications. */
  @Test
  void testSimplify() {
    checkSimplify(
        "y + 10 - 1 - (y + 1) => 8",
        "fn (x: int, y: int) => y + 10 - 1 - (y + 1)",
        "fn v => case v of (x, y) => 8");
    checkSimplify(
        "(x + y) - x => y",
        "fn (x: int, y: int) => x + y - x",
        "fn v => case v of (x, y) => y");
    checkSimplify(
        "(x + y) - (x + z) => y - z",
        "fn (x: int, y: int, z: int) => (x + y) - (x + z)",
        "fn v => case v of (x, y, z) => -:int (y, z)");
    checkSimplify("4 + 1 => 5", "4 + 1", "5");
    checkSimplify("4 - 1 => 3", "4 - 1", "3");
    checkSimplify("(9 + 1) - 2 => 8", "9 + 1 - 2", "8");
    checkSimplify("(9 - 2) + 1 => 8", "9 - 2 + 1", "8");
  }
}

// End PredicateInverterTest.java
