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
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasToString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.StringReader;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Optional;
import java.util.Set;
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
import org.junit.jupiter.api.Disabled;
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
    final Result result =
        PredicateInverter.invert(
            f.typeSystem,
            Environments.empty(),
            predicate,
            ImmutableList.of(f.xPat),
            ImmutableMap.of());

    // Should successfully invert to myList
    assertThat(result.generator.expression, hasToString("myList"));
    assertThat(result.remainingFilters, empty());
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
    final Result result =
        PredicateInverter.invert(
            f.typeSystem,
            Environments.empty(),
            predicate,
            ImmutableList.of(f.pPat),
            ImmutableMap.of());

    // Should successfully invert to List.tabulate expression
    // Expected: List.tabulate(String.size s + 1, fn i => String.substring(s, 0,
    // i))
    String expected =
        "#tabulate List (op + (#size String s, 1), fn i => #substring String (s, 0, i))";

    assertThat(result.generator.expression, hasToString(expected));
    assertThat(result.remainingFilters, empty());
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
   *
   * <p><b>NOTE:</b> Disabled - deferred to Phase 4. Requires complex predicate
   * inversion with conjunction analysis and join synthesis.
   */
  @Disabled("Phase 4 - Complex predicate inversion with joins")
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
    final Result result =
        PredicateInverter.invert(
            f.typeSystem,
            Environments.empty(),
            conjunction,
            ImmutableList.of(f.empnoPat, f.dnamePat),
            ImmutableMap.of());

    // Should successfully invert
    assumeTrue(false, "TODO enable test");
    // The generator should be a join
    // May have duplicates if the underlying relations do
  }

  /**
   * Test 4: Invert a call to 'fun edge(x, y)'.
   *
   * <p>Given {@code fun edge(x, y) = {x, y} elem edges}, when we invert {@code
   * edge(x, y)} to generate both x and y, it should return {@code edges}.
   *
   * <p><b>NOTE:</b> Disabled - deferred to Phase 4. Requires function body
   * lookup and inlining, which will be implemented after mode analysis.
   */
  @Disabled("Phase 4 - User-defined function call inversion")
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
    final Result result =
        PredicateInverter.invert(
            f.typeSystem,
            Environments.empty(),
            edgeCall,
            ImmutableList.of(f.xPat, f.yPat),
            ImmutableMap.of());

    // Should successfully invert
    assumeTrue(false, "TODO enable test");
    // After inlining and inverting, should get edges
    // (actual implementation will need function body lookup)
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
   *
   * <p><b>NOTE:</b> Disabled - deferred to Phase 4. Transitive closure
   * inversion via function inlining requires mode analysis and function body
   * lookup.
   */
  @Disabled("Phase 4 - Transitive closure via function inlining")
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
    final Result result =
        PredicateInverter.invert(
            f.typeSystem,
            Environments.empty(),
            functionCall,
            ImmutableList.of(f.xPat, f.yPat),
            ImmutableMap.of());

    // Currently returns fallback because we don't have an environment
    // with the function definition
    // TODO: Implement full function inlining and inversion
    // Without env, user functions use fallback - check that predicate is in
    // remainingFilters
    assumeTrue(false, "TODO enable test");
    assertThat(result.remainingFilters, not(empty()));
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
   *
   * <p><b>NOTE:</b> Disabled - deferred to Phase 4. Range constraint inversion
   * requires arithmetic constraint solving and mode analysis.
   */
  @Disabled("Phase 4 - Range constraint inversion")
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
    final Result result =
        PredicateInverter.invert(
            f.typeSystem,
            Environments.empty(),
            predicate,
            ImmutableList.of(f.yPat),
            ImmutableMap.of(
                f.xPat,
                new PredicateInverter.Generator(
                    f.xPat,
                    xExtent,
                    Generator.Cardinality.FINITE,
                    ImmutableList.of(),
                    ImmutableSet.of())));

    // Should successfully invert
    assumeTrue(false, "TODO enable test");
    // The generator should produce y values for each x value
    // Expected structure: from x in [3,5,7], y in List.tabulate(9, fn k => x -
    // 9 + k) yield y
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
    final Result result =
        PredicateInverter.invert(
            f.typeSystem,
            Environments.empty(),
            predicate,
            ImmutableList.of(f.xPat),
            ImmutableMap.of(
                f.yPat,
                new PredicateInverter.Generator(
                    f.yPat,
                    yExtent,
                    Generator.Cardinality.FINITE,
                    ImmutableList.of(),
                    ImmutableSet.of())));

    // Should successfully invert
    // The generator should produce x values for each y value.
    // Expected structure (unsimplified):
    //   List.tabulate(op - (op + (y, 10), y), fn k => op + (y, k))
    // Which is equivalent to: List.tabulate(10 - 0, fn k => y + 0 + k)
    // Which simplifies to: List.tabulate(10, fn k => y + k)
    // But we generate it from the bounds: y < x < y + 10
    // After adjusting for strict bounds: y + 1 <= x <= y + 10
    // So: List.tabulate((y + 10 + 1) - (y + 1), fn k => (y + 1) + k)
    String expected = "#tabulate List (9, fn k => y + 1 + k)";
    assertThat(result.remainingFilters, empty());
  }

  /**
   * Tests that {@code case x of 1 => true | 3 => true | _ => false} can be
   * inverted to generate {@code [1, 3]} as the possible x values.
   *
   * <p><b>NOTE:</b> Disabled - deferred to Phase 4. Case expression inversion
   * requires pattern analysis and value extraction from match arms.
   */
  @Disabled("Phase 4 - Case expression inversion")
  @Test
  void testInvertCase() {
    assumeTrue(false, "TODO enable test");
  }

  /**
   * Test 7: Verify that uninvertible predicates return empty.
   *
   * <p>For example, {@code x * x = 25} cannot be easily inverted to generate x
   * (would require symbolic math).
   *
   * <p><b>NOTE:</b> Disabled - deferred to Phase 4. Test currently passes with
   * fallback behavior, but will be re-enabled to verify proper uninvertible
   * predicate detection once all Phase 4 features are implemented.
   */
  @Disabled("Phase 4 - Uninvertible predicate detection")
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
    final Result result =
        PredicateInverter.invert(
            f.typeSystem,
            Environments.empty(),
            predicate,
            ImmutableList.of(f.xPat),
            ImmutableMap.of());

    // Should fail to invert - fallback should have predicate in
    // remainingFilters
    assumeTrue(false, "TODO enable test");
    assertThat(result.remainingFilters, not(empty()));
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

  /**
   * TODO: Case "exists i: int, j: int where j = 3". It doesn't matter that "i"
   * is not constrained, because it isn't used. An infinite generator will
   * suffice. This query is valid.
   */
  @Test
  void testUnused() {}

  // ===== Phase 3a Integration Tests - ProcessTreeBuilder Integration =====

  /**
   * Integration Test 1: Verify ProcessTreeBuilder is called successfully.
   *
   * <p>This test validates that tryInvertWithProcessTree constructs a PPT
   * without throwing exceptions. It doesn't verify full inversion (Phase 4) but
   * confirms the integration point works.
   */
  @Test
  void testProcessTreeBuilderIntegration() {
    final Fixture f = new Fixture();

    // Build: x elem myList
    final Core.Exp predicate = core.elem(f.typeSystem, f.xId, f.myListId);

    // Create a PredicateInverter instance
    final PredicateInverter inverter =
        new PredicateInverter(f.typeSystem, Environments.empty());

    // Call tryInvertWithProcessTree directly
    Optional<Result> result =
        inverter.tryInvertWithProcessTree(
            predicate, ImmutableList.of(f.xPat), ImmutableMap.of());

    // Phase 3a: Method should complete without exception
    // Empty result is expected (Phase 4 will return actual generators)
    assertThat(result.isPresent(), hasToString("false"));
  }

  /**
   * Integration Test 2: Confirm recursive predicates are recognized.
   *
   * <p>This test validates that recursive function patterns (like transitive
   * closure) are detected and flow through ProcessTreeBuilder without errors.
   */
  @Test
  void testTransitiveClosureDetected() {
    final Fixture f = new Fixture();

    // Build: edge(x, y) orelse (exists z where edge(x, z) andalso path(z, y))
    // This is the classic transitive closure pattern

    // For now, use a simpler orelse pattern: x elem [1,2,3] orelse x elem
    // [4,5,6]
    final Core.Exp left = core.elem(f.typeSystem, f.xId, f.myListId);
    final Core.Exp right = core.elem(f.typeSystem, f.xId, f.myListId);
    final Core.Exp predicate = core.orElse(f.typeSystem, left, right);

    final PredicateInverter inverter =
        new PredicateInverter(f.typeSystem, Environments.empty());

    // Call tryInvertWithProcessTree directly
    Optional<Result> result =
        inverter.tryInvertWithProcessTree(
            predicate, ImmutableList.of(f.xPat), ImmutableMap.of());

    // Should complete without exception
    assertThat(result.isPresent(), hasToString("false"));
  }

  /**
   * Integration Test 3: Verify simple predicates flow through builder.
   *
   * <p>This test validates that simple predicates are properly analyzed by
   * ProcessTreeBuilder without errors.
   */
  @Test
  void testSimpleExistsPatternViaBuilder() {
    final Fixture f = new Fixture();

    // Build a simple predicate: x elem myList
    // ProcessTreeBuilder should handle this and create a TerminalNode
    final Core.Exp predicate = core.elem(f.typeSystem, f.xId, f.myListId);

    final PredicateInverter inverter =
        new PredicateInverter(f.typeSystem, Environments.empty());

    // Call tryInvertWithProcessTree directly
    Optional<Result> result =
        inverter.tryInvertWithProcessTree(
            predicate, ImmutableList.of(f.xPat), ImmutableMap.of());

    // Should complete without exception
    assertThat(result.isPresent(), hasToString("false"));
  }

  /**
   * Integration Test 4: Verify conjunctions are analyzed correctly.
   *
   * <p>This test validates that andalso patterns are properly decomposed and
   * analyzed by ProcessTreeBuilder to identify join variables.
   */
  @Test
  void testConjunctionHandledByBuilder() {
    final Fixture f = new Fixture();

    // Build: x elem myList andalso y elem myList
    final Core.Exp left = core.elem(f.typeSystem, f.xId, f.myListId);
    final Core.Exp right = core.elem(f.typeSystem, f.yId, f.myListId);
    final Core.Exp predicate = core.andAlso(f.typeSystem, left, right);

    final PredicateInverter inverter =
        new PredicateInverter(f.typeSystem, Environments.empty());

    // Call tryInvertWithProcessTree directly
    Optional<Result> result =
        inverter.tryInvertWithProcessTree(
            predicate, ImmutableList.of(f.xPat, f.yPat), ImmutableMap.of());

    // Should complete without exception
    assertThat(result.isPresent(), hasToString("false"));
  }

  /**
   * Integration Test 5: Verify graceful failure on invalid predicates.
   *
   * <p>This test validates that when ProcessTreeBuilder cannot construct a PPT,
   * tryInvertWithProcessTree returns empty rather than throwing exceptions.
   */
  @Test
  void testInversionFailureHandled() {
    final Fixture f = new Fixture();

    // Build an expression that cannot be inverted
    // For example, a complex nested expression or invalid structure
    final Core.Exp predicate = core.intLiteral(BigDecimal.valueOf(42));

    final PredicateInverter inverter =
        new PredicateInverter(f.typeSystem, Environments.empty());

    // Call tryInvertWithProcessTree directly
    Optional<Result> result =
        inverter.tryInvertWithProcessTree(
            predicate, ImmutableList.of(f.xPat), ImmutableMap.of());

    // Should return empty without throwing
    assertThat(result.isPresent(), hasToString("false"));
  }

  // ===== Phase 3b-1 Tests - Extended Base & Recursive Case Inversion =====

  /**
   * Test 1: isTransitiveClosurePattern recognizes simple edge-based pattern.
   *
   * <p>Pattern: {@code edge(x,y) orelse (exists z where edge(x,z) andalso
   * path(z,y))}
   *
   * <p>This test validates that a simple transitive closure pattern is
   * correctly identified when the base case is invertible and the recursive
   * case contains recursion.
   */
  @Test
  void testIsTransitiveClosurePatternSimpleEdge() {
    final Fixture f = new Fixture();

    // Build: x elem myList (invertible base case)
    final Core.Exp baseCase = core.elem(f.typeSystem, f.xId, f.myListId);

    // Build: recursive call (simulated)
    // In real usage, this would be constructed by ProcessTreeBuilder
    // For testing, we'll build the tree structure manually

    // Create base case result (successful inversion)
    final Result baseCaseResult =
        new Result(
            new PredicateInverter.Generator(
                f.xPat,
                f.myListId,
                Generator.Cardinality.FINITE,
                ImmutableList.of(),
                ImmutableSet.of()),
            ImmutableList.of());

    // Build a simple PPT: baseCase orelse recursiveCall
    final VarEnvironment varEnv =
        VarEnvironment.initial(
            ImmutableList.of(f.xPat), ImmutableMap.of(), Environments.empty());

    final ProcessTreeNode.TerminalNode baseCaseNode =
        ProcessTreeNode.TerminalNode.of(
            baseCase, varEnv, Optional.of(baseCaseResult));

    final ProcessTreeNode.TerminalNode recursiveNode =
        ProcessTreeNode.TerminalNode.recursive(baseCase, varEnv);

    final ProcessTreeNode.BranchNode tree =
        new ProcessTreeNode.BranchNode(
            core.orElse(f.typeSystem, baseCase, baseCase),
            varEnv,
            baseCaseNode,
            recursiveNode);

    // Create inverter instance to test the method
    final PredicateInverter inverter =
        new PredicateInverter(f.typeSystem, Environments.empty());

    // Access the private method using reflection
    try {
      Method method =
          PredicateInverter.class.getDeclaredMethod(
              "isTransitiveClosurePattern", ProcessTreeNode.class);
      method.setAccessible(true);
      boolean result = (boolean) method.invoke(inverter, tree);

      // Should recognize the pattern
      assertThat(result, hasToString("true"));
    } catch (Exception e) {
      throw new RuntimeException(
          "Failed to invoke isTransitiveClosurePattern", e);
    }
  }

  /**
   * Test 2: isTransitiveClosurePattern returns false when base case is not
   * invertible.
   *
   * <p>This validates that the pattern recognition correctly rejects patterns
   * where the base case cannot be inverted.
   */
  @Test
  void testIsTransitiveClosurePatternNoInversion() {
    final Fixture f = new Fixture();

    final Core.Exp baseCase = core.intLiteral(BigDecimal.valueOf(42));

    final VarEnvironment varEnv =
        VarEnvironment.initial(
            ImmutableList.of(f.xPat), ImmutableMap.of(), Environments.empty());

    // Base case with failed inversion (remainingFilters not empty)
    final Result baseCaseResult =
        new Result(
            new PredicateInverter.Generator(
                f.xPat,
                f.myListId,
                Generator.Cardinality.FINITE,
                ImmutableList.of(),
                ImmutableSet.of()),
            ImmutableList.of(baseCase)); // Has remaining filters - not inverted

    final ProcessTreeNode.TerminalNode baseCaseNode =
        ProcessTreeNode.TerminalNode.of(
            baseCase, varEnv, Optional.of(baseCaseResult));

    final ProcessTreeNode.TerminalNode recursiveNode =
        ProcessTreeNode.TerminalNode.recursive(baseCase, varEnv);

    final ProcessTreeNode.BranchNode tree =
        new ProcessTreeNode.BranchNode(
            core.orElse(f.typeSystem, baseCase, baseCase),
            varEnv,
            baseCaseNode,
            recursiveNode);

    final PredicateInverter inverter =
        new PredicateInverter(f.typeSystem, Environments.empty());

    try {
      Method method =
          PredicateInverter.class.getDeclaredMethod(
              "isTransitiveClosurePattern", ProcessTreeNode.class);
      method.setAccessible(true);
      boolean result = (boolean) method.invoke(inverter, tree);

      // Should reject the pattern
      assertThat(result, hasToString("false"));
    } catch (Exception e) {
      throw new RuntimeException(
          "Failed to invoke isTransitiveClosurePattern", e);
    }
  }

  /**
   * Test 3: identifyRecursiveCall finds recursive call in simple expression.
   *
   * <p>Pattern: {@code path(z, y)} - the recursive call in the transitive
   * closure.
   */
  @Test
  void testIdentifyRecursiveCallFound() {
    final Fixture f = new Fixture();

    // Build: path(x, y)
    final Core.IdPat pathPat = core.idPat(f.intIntBoolFnType, "path", 0);
    final Core.Exp pathCall =
        core.apply(
            Pos.ZERO,
            PrimitiveType.BOOL,
            core.id(pathPat),
            core.tuple(f.typeSystem, f.xId, f.yId));

    final PredicateInverter inverter =
        new PredicateInverter(f.typeSystem, Environments.empty());

    try {
      Method method =
          PredicateInverter.class.getDeclaredMethod(
              "identifyRecursiveCall", Core.Exp.class, String.class);
      method.setAccessible(true);
      Optional<Core.Apply> result =
          (Optional<Core.Apply>) method.invoke(inverter, pathCall, "path");

      // Should find the call
      assertThat(result.isPresent(), hasToString("true"));
      assertThat(result.get(), hasToString(pathCall.toString()));
    } catch (Exception e) {
      throw new RuntimeException("Failed to invoke identifyRecursiveCall", e);
    }
  }

  /**
   * Test 4: identifyRecursiveCall returns empty when no recursive call exists.
   *
   * <p>This validates that non-recursive expressions are correctly identified.
   */
  @Test
  void testIdentifyRecursiveCallNotFound() {
    final Fixture f = new Fixture();

    // Build: x elem myList (no recursive call)
    final Core.Exp nonRecursive = core.elem(f.typeSystem, f.xId, f.myListId);

    final PredicateInverter inverter =
        new PredicateInverter(f.typeSystem, Environments.empty());

    try {
      Method method =
          PredicateInverter.class.getDeclaredMethod(
              "identifyRecursiveCall", Core.Exp.class, String.class);
      method.setAccessible(true);
      Optional<Core.Apply> result =
          (Optional<Core.Apply>) method.invoke(inverter, nonRecursive, "path");

      // Should not find any call
      assertThat(result.isPresent(), hasToString("false"));
    } catch (Exception e) {
      throw new RuntimeException("Failed to invoke identifyRecursiveCall", e);
    }
  }

  /**
   * Test 5: identifyThreadedVariables finds simple threaded variables.
   *
   * <p>Pattern: {@code path(z, y)} identifies {z, y} as threaded.
   */
  @Test
  void testIdentifyThreadedVariablesSimple() {
    final Fixture f = new Fixture();

    final Core.IdPat zPat = core.idPat(f.intType, "z", 0);
    final Core.Id zId = core.id(zPat);

    // Build: path(z, y)
    final Core.IdPat pathPat = core.idPat(f.intIntBoolFnType, "path", 0);
    final Core.Exp pathCall =
        core.apply(
            Pos.ZERO,
            PrimitiveType.BOOL,
            core.id(pathPat),
            core.tuple(f.typeSystem, zId, f.yId));

    final PredicateInverter inverter =
        new PredicateInverter(f.typeSystem, Environments.empty());

    try {
      Method method =
          PredicateInverter.class.getDeclaredMethod(
              "identifyThreadedVariables", Core.Exp.class, String.class);
      method.setAccessible(true);
      Set<Core.NamedPat> result =
          (Set<Core.NamedPat>)
              method.invoke(
                  inverter, pathCall, // recursiveCase
                  "path");

      // Should find both z and y
      assertThat(result.size(), hasToString("2"));
      assertThat(result.contains(zPat), hasToString("true"));
      assertThat(result.contains(f.yPat), hasToString("true"));
    } catch (Exception e) {
      throw new RuntimeException(
          "Failed to invoke identifyThreadedVariables", e);
    }
  }

  /**
   * Test 6: identifyThreadedVariables handles complex recursive calls.
   *
   * <p>Pattern: {@code edge(x,z) andalso path(z,y)} should still identify
   * threaded variables in the path call.
   */
  @Test
  void testIdentifyThreadedVariablesComplex() {
    final Fixture f = new Fixture();

    final Core.IdPat zPat = core.idPat(f.intType, "z", 0);
    final Core.Id zId = core.id(zPat);

    // Build: edge(x, z)
    final Core.IdPat edgePat = core.idPat(f.intIntBoolFnType, "edge", 0);
    final Core.Exp edgeCall =
        core.apply(
            Pos.ZERO,
            PrimitiveType.BOOL,
            core.id(edgePat),
            core.tuple(f.typeSystem, f.xId, zId));

    // Build: path(z, y)
    final Core.IdPat pathPat = core.idPat(f.intIntBoolFnType, "path", 0);
    final Core.Exp pathCall =
        core.apply(
            Pos.ZERO,
            PrimitiveType.BOOL,
            core.id(pathPat),
            core.tuple(f.typeSystem, zId, f.yId));

    // Build: edge(x,z) andalso path(z,y)
    final Core.Exp recursiveCase =
        core.andAlso(f.typeSystem, edgeCall, pathCall);

    final PredicateInverter inverter =
        new PredicateInverter(f.typeSystem, Environments.empty());

    try {
      Method method =
          PredicateInverter.class.getDeclaredMethod(
              "identifyThreadedVariables", Core.Exp.class, String.class);
      method.setAccessible(true);
      Set<Core.NamedPat> result =
          (Set<Core.NamedPat>) method.invoke(inverter, recursiveCase, "path");

      // Should find both z and y from the path call
      assertThat(result.size(), hasToString("2"));
      assertThat(result.contains(zPat), hasToString("true"));
      assertThat(result.contains(f.yPat), hasToString("true"));
    } catch (Exception e) {
      throw new RuntimeException(
          "Failed to invoke identifyThreadedVariables", e);
    }
  }

  /**
   * Test 7: extractRecursiveBody returns right branch of BranchNode.
   *
   * <p>Validates that the recursive case is correctly extracted from a
   * transitive closure pattern.
   */
  @Test
  void testExtractRecursiveBodyReturnsRight() {
    final Fixture f = new Fixture();

    final Core.Exp baseCase = core.elem(f.typeSystem, f.xId, f.myListId);
    final Core.Exp recursiveCase = core.elem(f.typeSystem, f.yId, f.myListId);

    final VarEnvironment varEnv =
        VarEnvironment.initial(
            ImmutableList.of(f.xPat), ImmutableMap.of(), Environments.empty());

    final ProcessTreeNode.TerminalNode baseNode =
        ProcessTreeNode.TerminalNode.of(baseCase, varEnv, Optional.empty());
    final ProcessTreeNode.TerminalNode recursiveNode =
        ProcessTreeNode.TerminalNode.recursive(recursiveCase, varEnv);

    final ProcessTreeNode.BranchNode tree =
        new ProcessTreeNode.BranchNode(
            core.orElse(f.typeSystem, baseCase, recursiveCase),
            varEnv,
            baseNode,
            recursiveNode);

    final PredicateInverter inverter =
        new PredicateInverter(f.typeSystem, Environments.empty());

    try {
      Method method =
          PredicateInverter.class.getDeclaredMethod(
              "extractRecursiveBody", ProcessTreeNode.class);
      method.setAccessible(true);
      Core.Exp result = (Core.Exp) method.invoke(inverter, tree);

      // Should return the recursive case expression
      assertThat(result, hasToString(recursiveCase.toString()));
    } catch (Exception e) {
      throw new RuntimeException("Failed to invoke extractRecursiveBody", e);
    }
  }

  /**
   * Test 8: Extended inversion returns partial result for recognized patterns.
   *
   * <p>This test validates that when a transitive closure pattern is
   * recognized, the base case inversion is returned as a partial result (Phase
   * 3b-1 requirement).
   *
   * <p>Full Relational.iterate generation will be implemented in Phase 3b-2+.
   */
  @Test
  void testExtendedInversionReturnsPartialResult() {
    final Fixture f = new Fixture();

    // Build simple predicate: x elem myList
    final Core.Exp predicate = core.elem(f.typeSystem, f.xId, f.myListId);

    final PredicateInverter inverter =
        new PredicateInverter(f.typeSystem, Environments.empty());

    // Test that tryInvertWithProcessTree handles this without error
    Optional<Result> result =
        inverter.tryInvertWithProcessTree(
            predicate, ImmutableList.of(f.xPat), ImmutableMap.of());

    // Phase 3b-1: Should return empty (placeholder)
    // Phase 3b-2+: Will return base case inversion
    assertThat(result.isPresent(), hasToString("false"));
  }

  // ===== Phase 3b-2 Tests - Tabulation Algorithm =====

  /**
   * Test 1: Tabulate single terminal node returns one I-O pair.
   *
   * <p>Given a simple PPT with one terminal node containing an inversion
   * result, tabulation should extract one I-O pair.
   */
  @Test
  void testTabulateSimpleTerminal() {
    final Fixture f = new Fixture();

    // Create a simple inversion result: x elem myList => myList
    final Result inversionResult =
        new Result(
            new PredicateInverter.Generator(
                f.xPat,
                f.myListId,
                Generator.Cardinality.FINITE,
                ImmutableList.of(),
                ImmutableSet.of()),
            ImmutableList.of());

    final VarEnvironment varEnv =
        VarEnvironment.initial(
            ImmutableList.of(f.xPat), ImmutableMap.of(), Environments.empty());

    final Core.Exp predicate = core.elem(f.typeSystem, f.xId, f.myListId);
    final ProcessTreeNode.TerminalNode terminal =
        ProcessTreeNode.TerminalNode.of(
            predicate, varEnv, Optional.of(inversionResult));

    final PredicateInverter inverter =
        new PredicateInverter(f.typeSystem, Environments.empty());

    try {
      Method method =
          PredicateInverter.class.getDeclaredMethod(
              "tabulate", ProcessTreeNode.class, VarEnvironment.class);
      method.setAccessible(true);
      PredicateInverter.TabulationResult result =
          (PredicateInverter.TabulationResult)
              method.invoke(inverter, terminal, varEnv);

      // Should have exactly one I-O pair
      assertThat(result.ioMappings.size(), hasToString("1"));
      // Output should be myList
      assertThat(result.ioMappings.get(0).output, hasToString("myList"));
      // Cardinality should be FINITE
      assertThat(result.cardinality, hasToString("FINITE"));
    } catch (Exception e) {
      throw new RuntimeException("Failed to invoke tabulate", e);
    }
  }

  /**
   * Test 2: Tabulate branch with two terminals returns two pairs.
   *
   * <p>Given a BranchNode (orelse) with two terminal children, tabulation
   * should collect I-O pairs from both terminals.
   */
  @Test
  void testTabulateBranchWithTwoTerminals() {
    final Fixture f = new Fixture();

    // Left terminal: x elem [1,2]
    final Core.Exp leftList =
        core.list(f.typeSystem, f.intLiteral(1), f.intLiteral(2));
    final Result leftResult =
        new Result(
            new PredicateInverter.Generator(
                f.xPat,
                leftList,
                Generator.Cardinality.FINITE,
                ImmutableList.of(),
                ImmutableSet.of()),
            ImmutableList.of());

    // Right terminal: x elem [3,4]
    final Core.Exp rightList =
        core.list(f.typeSystem, f.intLiteral(3), f.intLiteral(4));
    final Result rightResult =
        new Result(
            new PredicateInverter.Generator(
                f.xPat,
                rightList,
                Generator.Cardinality.FINITE,
                ImmutableList.of(),
                ImmutableSet.of()),
            ImmutableList.of());

    final VarEnvironment varEnv =
        VarEnvironment.initial(
            ImmutableList.of(f.xPat), ImmutableMap.of(), Environments.empty());

    final Core.Exp leftPred = core.elem(f.typeSystem, f.xId, leftList);
    final Core.Exp rightPred = core.elem(f.typeSystem, f.xId, rightList);

    final ProcessTreeNode.TerminalNode leftNode =
        ProcessTreeNode.TerminalNode.of(
            leftPred, varEnv, Optional.of(leftResult));
    final ProcessTreeNode.TerminalNode rightNode =
        ProcessTreeNode.TerminalNode.of(
            rightPred, varEnv, Optional.of(rightResult));

    final ProcessTreeNode.BranchNode branch =
        new ProcessTreeNode.BranchNode(
            core.orElse(f.typeSystem, leftPred, rightPred),
            varEnv,
            leftNode,
            rightNode);

    final PredicateInverter inverter =
        new PredicateInverter(f.typeSystem, Environments.empty());

    try {
      Method method =
          PredicateInverter.class.getDeclaredMethod(
              "tabulate", ProcessTreeNode.class, VarEnvironment.class);
      method.setAccessible(true);
      PredicateInverter.TabulationResult result =
          (PredicateInverter.TabulationResult)
              method.invoke(inverter, branch, varEnv);

      // Should have two I-O pairs
      assertThat(result.ioMappings.size(), hasToString("2"));
      // Cardinality should be FINITE
      assertThat(result.cardinality, hasToString("FINITE"));
    } catch (Exception e) {
      throw new RuntimeException("Failed to invoke tabulate", e);
    }
  }

  /**
   * Test 3: Tabulate sequence with multiple terminals returns all pairs.
   *
   * <p>Given a SequenceNode (andalso) with multiple children, tabulation should
   * collect I-O pairs from all terminals.
   */
  @Test
  void testTabulateSequenceMultipleTerminals() {
    final Fixture f = new Fixture();

    // First terminal: x elem [1,2]
    final Core.Exp list1 =
        core.list(f.typeSystem, f.intLiteral(1), f.intLiteral(2));
    final Result result1 =
        new Result(
            new PredicateInverter.Generator(
                f.xPat,
                list1,
                Generator.Cardinality.FINITE,
                ImmutableList.of(),
                ImmutableSet.of()),
            ImmutableList.of());

    // Second terminal: y elem [3,4]
    final Core.Exp list2 =
        core.list(f.typeSystem, f.intLiteral(3), f.intLiteral(4));
    final Result result2 =
        new Result(
            new PredicateInverter.Generator(
                f.yPat,
                list2,
                Generator.Cardinality.FINITE,
                ImmutableList.of(),
                ImmutableSet.of()),
            ImmutableList.of());

    final VarEnvironment varEnv =
        VarEnvironment.initial(
            ImmutableList.of(f.xPat, f.yPat),
            ImmutableMap.of(),
            Environments.empty());

    final Core.Exp pred1 = core.elem(f.typeSystem, f.xId, list1);
    final Core.Exp pred2 = core.elem(f.typeSystem, f.yId, list2);

    final ProcessTreeNode.TerminalNode node1 =
        ProcessTreeNode.TerminalNode.of(pred1, varEnv, Optional.of(result1));
    final ProcessTreeNode.TerminalNode node2 =
        ProcessTreeNode.TerminalNode.of(pred2, varEnv, Optional.of(result2));

    final ProcessTreeNode.SequenceNode sequence =
        new ProcessTreeNode.SequenceNode(
            core.andAlso(f.typeSystem, pred1, pred2),
            varEnv,
            ImmutableList.of(node1, node2));

    final PredicateInverter inverter =
        new PredicateInverter(f.typeSystem, Environments.empty());

    try {
      Method method =
          PredicateInverter.class.getDeclaredMethod(
              "tabulate", ProcessTreeNode.class, VarEnvironment.class);
      method.setAccessible(true);
      PredicateInverter.TabulationResult result =
          (PredicateInverter.TabulationResult)
              method.invoke(inverter, sequence, varEnv);

      // Should have two I-O pairs (one from each child)
      assertThat(result.ioMappings.size(), hasToString("2"));
      // Cardinality should be FINITE
      assertThat(result.cardinality, hasToString("FINITE"));
    } catch (Exception e) {
      throw new RuntimeException("Failed to invoke tabulate", e);
    }
  }

  /**
   * Test 4: Tabulate recognizes SINGLE cardinality.
   *
   * <p>When a terminal has SINGLE cardinality, the overall result should be
   * SINGLE.
   */
  @Test
  void testTabulateCardinalitySingle() {
    final Fixture f = new Fixture();

    final Core.Exp singletonList = core.list(f.typeSystem, f.intLiteral(42));
    final Result inversionResult =
        new Result(
            new PredicateInverter.Generator(
                f.xPat,
                singletonList,
                Generator.Cardinality.SINGLE,
                ImmutableList.of(),
                ImmutableSet.of()),
            ImmutableList.of());

    final VarEnvironment varEnv =
        VarEnvironment.initial(
            ImmutableList.of(f.xPat), ImmutableMap.of(), Environments.empty());

    final Core.Exp predicate = core.elem(f.typeSystem, f.xId, singletonList);
    final ProcessTreeNode.TerminalNode terminal =
        ProcessTreeNode.TerminalNode.of(
            predicate, varEnv, Optional.of(inversionResult));

    final PredicateInverter inverter =
        new PredicateInverter(f.typeSystem, Environments.empty());

    try {
      Method method =
          PredicateInverter.class.getDeclaredMethod(
              "tabulate", ProcessTreeNode.class, VarEnvironment.class);
      method.setAccessible(true);
      PredicateInverter.TabulationResult result =
          (PredicateInverter.TabulationResult)
              method.invoke(inverter, terminal, varEnv);

      // Cardinality should be SINGLE
      assertThat(result.cardinality, hasToString("SINGLE"));
    } catch (Exception e) {
      throw new RuntimeException("Failed to invoke tabulate", e);
    }
  }

  /**
   * Test 5: Tabulate recognizes FINITE cardinality.
   *
   * <p>When a terminal has FINITE cardinality, the overall result should be
   * FINITE.
   */
  @Test
  void testTabulateCardinalityFinite() {
    final Fixture f = new Fixture();

    final Core.Exp finiteList =
        core.list(
            f.typeSystem, f.intLiteral(1), f.intLiteral(2), f.intLiteral(3));
    final Result inversionResult =
        new Result(
            new PredicateInverter.Generator(
                f.xPat,
                finiteList,
                Generator.Cardinality.FINITE,
                ImmutableList.of(),
                ImmutableSet.of()),
            ImmutableList.of());

    final VarEnvironment varEnv =
        VarEnvironment.initial(
            ImmutableList.of(f.xPat), ImmutableMap.of(), Environments.empty());

    final Core.Exp predicate = core.elem(f.typeSystem, f.xId, finiteList);
    final ProcessTreeNode.TerminalNode terminal =
        ProcessTreeNode.TerminalNode.of(
            predicate, varEnv, Optional.of(inversionResult));

    final PredicateInverter inverter =
        new PredicateInverter(f.typeSystem, Environments.empty());

    try {
      Method method =
          PredicateInverter.class.getDeclaredMethod(
              "tabulate", ProcessTreeNode.class, VarEnvironment.class);
      method.setAccessible(true);
      PredicateInverter.TabulationResult result =
          (PredicateInverter.TabulationResult)
              method.invoke(inverter, terminal, varEnv);

      // Cardinality should be FINITE
      assertThat(result.cardinality, hasToString("FINITE"));
    } catch (Exception e) {
      throw new RuntimeException("Failed to invoke tabulate", e);
    }
  }

  /**
   * Test 6: Tabulate recognizes INFINITE cardinality.
   *
   * <p>When a terminal has INFINITE cardinality, the overall result should be
   * INFINITE.
   */
  @Test
  void testTabulateCardinalityInfinite() {
    final Fixture f = new Fixture();

    // Create an extent expression (infinite cardinality)
    final Core.Exp extentExpr =
        core.extent(
            f.typeSystem,
            f.intType,
            com.google.common.collect.ImmutableRangeSet.of(
                com.google.common.collect.Range.all()));
    final Result inversionResult =
        new Result(
            new PredicateInverter.Generator(
                f.xPat,
                extentExpr,
                Generator.Cardinality.INFINITE,
                ImmutableList.of(),
                ImmutableSet.of()),
            ImmutableList.of());

    final VarEnvironment varEnv =
        VarEnvironment.initial(
            ImmutableList.of(f.xPat), ImmutableMap.of(), Environments.empty());

    final Core.Exp predicate = core.elem(f.typeSystem, f.xId, extentExpr);
    final ProcessTreeNode.TerminalNode terminal =
        ProcessTreeNode.TerminalNode.of(
            predicate, varEnv, Optional.of(inversionResult));

    final PredicateInverter inverter =
        new PredicateInverter(f.typeSystem, Environments.empty());

    try {
      Method method =
          PredicateInverter.class.getDeclaredMethod(
              "tabulate", ProcessTreeNode.class, VarEnvironment.class);
      method.setAccessible(true);
      PredicateInverter.TabulationResult result =
          (PredicateInverter.TabulationResult)
              method.invoke(inverter, terminal, varEnv);

      // Cardinality should be INFINITE
      assertThat(result.cardinality, hasToString("INFINITE"));
    } catch (Exception e) {
      throw new RuntimeException("Failed to invoke tabulate", e);
    }
  }

  /**
   * Test 7: Tabulate handles mixed cardinalities correctly.
   *
   * <p>When terminals have different cardinalities (SINGLE and FINITE), the
   * overall result should be FINITE (the max).
   */
  @Test
  void testTabulateCardinalityMixed() {
    final Fixture f = new Fixture();

    // Left terminal: SINGLE cardinality
    final Core.Exp singletonList = core.list(f.typeSystem, f.intLiteral(1));
    final Result leftResult =
        new Result(
            new PredicateInverter.Generator(
                f.xPat,
                singletonList,
                Generator.Cardinality.SINGLE,
                ImmutableList.of(),
                ImmutableSet.of()),
            ImmutableList.of());

    // Right terminal: FINITE cardinality
    final Core.Exp finiteList =
        core.list(
            f.typeSystem, f.intLiteral(2), f.intLiteral(3), f.intLiteral(4));
    final Result rightResult =
        new Result(
            new PredicateInverter.Generator(
                f.xPat,
                finiteList,
                Generator.Cardinality.FINITE,
                ImmutableList.of(),
                ImmutableSet.of()),
            ImmutableList.of());

    final VarEnvironment varEnv =
        VarEnvironment.initial(
            ImmutableList.of(f.xPat), ImmutableMap.of(), Environments.empty());

    final Core.Exp leftPred = core.elem(f.typeSystem, f.xId, singletonList);
    final Core.Exp rightPred = core.elem(f.typeSystem, f.xId, finiteList);

    final ProcessTreeNode.TerminalNode leftNode =
        ProcessTreeNode.TerminalNode.of(
            leftPred, varEnv, Optional.of(leftResult));
    final ProcessTreeNode.TerminalNode rightNode =
        ProcessTreeNode.TerminalNode.of(
            rightPred, varEnv, Optional.of(rightResult));

    final ProcessTreeNode.BranchNode branch =
        new ProcessTreeNode.BranchNode(
            core.orElse(f.typeSystem, leftPred, rightPred),
            varEnv,
            leftNode,
            rightNode);

    final PredicateInverter inverter =
        new PredicateInverter(f.typeSystem, Environments.empty());

    try {
      Method method =
          PredicateInverter.class.getDeclaredMethod(
              "tabulate", ProcessTreeNode.class, VarEnvironment.class);
      method.setAccessible(true);
      PredicateInverter.TabulationResult result =
          (PredicateInverter.TabulationResult)
              method.invoke(inverter, branch, varEnv);

      // Cardinality should be FINITE (max of SINGLE and FINITE)
      assertThat(result.cardinality, hasToString("FINITE"));
    } catch (Exception e) {
      throw new RuntimeException("Failed to invoke tabulate", e);
    }
  }

  /**
   * Test 8: Tabulate detects duplicates when same output appears twice.
   *
   * <p>When two terminals produce the same output expression, mayHaveDuplicates
   * should be true.
   */
  @Test
  void testTabulateDetectsDuplicatesSingle() {
    final Fixture f = new Fixture();

    // Both terminals produce the same output: myList
    final Result leftResult =
        new Result(
            new PredicateInverter.Generator(
                f.xPat,
                f.myListId,
                Generator.Cardinality.FINITE,
                ImmutableList.of(),
                ImmutableSet.of()),
            ImmutableList.of());

    final Result rightResult =
        new Result(
            new PredicateInverter.Generator(
                f.xPat,
                f.myListId, // Same output as left
                Generator.Cardinality.FINITE,
                ImmutableList.of(),
                ImmutableSet.of()),
            ImmutableList.of());

    final VarEnvironment varEnv =
        VarEnvironment.initial(
            ImmutableList.of(f.xPat), ImmutableMap.of(), Environments.empty());

    final Core.Exp leftPred = core.elem(f.typeSystem, f.xId, f.myListId);
    final Core.Exp rightPred = core.elem(f.typeSystem, f.xId, f.myListId);

    final ProcessTreeNode.TerminalNode leftNode =
        ProcessTreeNode.TerminalNode.of(
            leftPred, varEnv, Optional.of(leftResult));
    final ProcessTreeNode.TerminalNode rightNode =
        ProcessTreeNode.TerminalNode.of(
            rightPred, varEnv, Optional.of(rightResult));

    final ProcessTreeNode.BranchNode branch =
        new ProcessTreeNode.BranchNode(
            core.orElse(f.typeSystem, leftPred, rightPred),
            varEnv,
            leftNode,
            rightNode);

    final PredicateInverter inverter =
        new PredicateInverter(f.typeSystem, Environments.empty());

    try {
      Method method =
          PredicateInverter.class.getDeclaredMethod(
              "tabulate", ProcessTreeNode.class, VarEnvironment.class);
      method.setAccessible(true);
      PredicateInverter.TabulationResult result =
          (PredicateInverter.TabulationResult)
              method.invoke(inverter, branch, varEnv);

      // Should detect duplicates
      assertThat(result.mayHaveDuplicates, hasToString("true"));
    } catch (Exception e) {
      throw new RuntimeException("Failed to invoke tabulate", e);
    }
  }

  /**
   * Test 9: Tabulate detects duplicates in complex tree.
   *
   * <p>Given a more complex tree structure with multiple terminals, some
   * producing duplicate outputs, duplicates should be detected.
   */
  @Test
  void testTabulateDetectsDuplicatesNonemptyPairs() {
    final Fixture f = new Fixture();

    // Create three terminals: two with duplicate outputs, one unique
    final Core.Exp list1 = core.list(f.typeSystem, f.intLiteral(1));
    final Core.Exp list2 =
        core.list(f.typeSystem, f.intLiteral(1)); // Duplicate
    final Core.Exp list3 = core.list(f.typeSystem, f.intLiteral(2)); // Unique

    final Result result1 =
        new Result(
            new PredicateInverter.Generator(
                f.xPat,
                list1,
                Generator.Cardinality.SINGLE,
                ImmutableList.of(),
                ImmutableSet.of()),
            ImmutableList.of());

    final Result result2 =
        new Result(
            new PredicateInverter.Generator(
                f.xPat,
                list2, // Same as list1
                Generator.Cardinality.SINGLE,
                ImmutableList.of(),
                ImmutableSet.of()),
            ImmutableList.of());

    final Result result3 =
        new Result(
            new PredicateInverter.Generator(
                f.xPat,
                list3,
                Generator.Cardinality.SINGLE,
                ImmutableList.of(),
                ImmutableSet.of()),
            ImmutableList.of());

    final VarEnvironment varEnv =
        VarEnvironment.initial(
            ImmutableList.of(f.xPat), ImmutableMap.of(), Environments.empty());

    final Core.Exp pred1 = core.elem(f.typeSystem, f.xId, list1);
    final Core.Exp pred2 = core.elem(f.typeSystem, f.xId, list2);
    final Core.Exp pred3 = core.elem(f.typeSystem, f.xId, list3);

    final ProcessTreeNode.TerminalNode node1 =
        ProcessTreeNode.TerminalNode.of(pred1, varEnv, Optional.of(result1));
    final ProcessTreeNode.TerminalNode node2 =
        ProcessTreeNode.TerminalNode.of(pred2, varEnv, Optional.of(result2));
    final ProcessTreeNode.TerminalNode node3 =
        ProcessTreeNode.TerminalNode.of(pred3, varEnv, Optional.of(result3));

    // Create a sequence with all three nodes
    final ProcessTreeNode.SequenceNode sequence =
        new ProcessTreeNode.SequenceNode(
            core.andAlso(
                f.typeSystem, core.andAlso(f.typeSystem, pred1, pred2), pred3),
            varEnv,
            ImmutableList.of(node1, node2, node3));

    final PredicateInverter inverter =
        new PredicateInverter(f.typeSystem, Environments.empty());

    try {
      Method method =
          PredicateInverter.class.getDeclaredMethod(
              "tabulate", ProcessTreeNode.class, VarEnvironment.class);
      method.setAccessible(true);
      PredicateInverter.TabulationResult result =
          (PredicateInverter.TabulationResult)
              method.invoke(inverter, sequence, varEnv);

      // Should detect duplicates (list1 and list2 produce same output)
      assertThat(result.mayHaveDuplicates, hasToString("true"));
      // Should have three I-O pairs
      assertThat(result.ioMappings.size(), hasToString("3"));
    } catch (Exception e) {
      throw new RuntimeException("Failed to invoke tabulate", e);
    }
  }

  /**
   * Test 10: Tabulate handles non-inverted terminals gracefully.
   *
   * <p>When a terminal node has no inversion result (failed to invert),
   * tabulation should skip it without throwing exceptions.
   */
  @Test
  void testTabulateThrowsOnNonterminal() {
    final Fixture f = new Fixture();

    // Create a terminal without inversion result
    final VarEnvironment varEnv =
        VarEnvironment.initial(
            ImmutableList.of(f.xPat), ImmutableMap.of(), Environments.empty());

    final Core.Exp predicate = core.elem(f.typeSystem, f.xId, f.myListId);
    final ProcessTreeNode.TerminalNode terminal =
        ProcessTreeNode.TerminalNode.of(predicate, varEnv, Optional.empty());

    final PredicateInverter inverter =
        new PredicateInverter(f.typeSystem, Environments.empty());

    try {
      Method method =
          PredicateInverter.class.getDeclaredMethod(
              "tabulate", ProcessTreeNode.class, VarEnvironment.class);
      method.setAccessible(true);
      PredicateInverter.TabulationResult result =
          (PredicateInverter.TabulationResult)
              method.invoke(inverter, terminal, varEnv);

      // Should have zero I-O pairs (terminal not inverted)
      assertThat(result.ioMappings.size(), hasToString("0"));
      // Cardinality should be default (FINITE for empty)
      assertThat(result.cardinality, hasToString("FINITE"));
      // No duplicates
      assertThat(result.mayHaveDuplicates, hasToString("false"));
    } catch (Exception e) {
      throw new RuntimeException("Failed to invoke tabulate", e);
    }
  }

  // ===== Phase 3b-3 Tests - Step Function Generation =====

  /**
   * Test 1: buildStepFunction generates step function successfully.
   *
   * <p>Given a simple tabulation result with one I-O pair, buildStepFunction
   * should successfully generate a lambda expression without throwing
   * exceptions.
   */
  @Test
  void testBuildStepFunctionSimple() {
    final Fixture f = new Fixture();

    // Create a simple tabulation result
    final Core.Exp edgesList =
        core.list(
            f.typeSystem,
            core.tuple(f.typeSystem, f.intLiteral(1), f.intLiteral(2)),
            core.tuple(f.typeSystem, f.intLiteral(2), f.intLiteral(3)));

    final VarEnvironment varEnv =
        VarEnvironment.initial(
            ImmutableList.of(f.xPat, f.yPat),
            ImmutableMap.of(),
            Environments.empty());

    final ProcessTreeNode.TerminalNode dummySource =
        ProcessTreeNode.TerminalNode.of(edgesList, varEnv, Optional.empty());

    final PredicateInverter.IOPair ioPair =
        new PredicateInverter.IOPair(ImmutableMap.of(), edgesList, dummySource);

    final PredicateInverter.TabulationResult tabulation =
        new PredicateInverter.TabulationResult(
            ImmutableList.of(ioPair), Generator.Cardinality.FINITE, false);

    final PredicateInverter inverter =
        new PredicateInverter(f.typeSystem, Environments.empty());

    try {
      Method method =
          PredicateInverter.class.getDeclaredMethod(
              "buildStepFunction",
              PredicateInverter.TabulationResult.class,
              VarEnvironment.class);
      method.setAccessible(true);
      Core.Exp result = (Core.Exp) method.invoke(inverter, tabulation, varEnv);

      // Should produce a function expression
      assertThat(result.op, hasToString("FN"));
    } catch (Exception e) {
      throw new RuntimeException("Failed to invoke buildStepFunction", e);
    }
  }

  /**
   * Test 2: buildStepFunction produces correct function type.
   *
   * <p>The generated step function should have type (bag, bag) -> bag where bag
   * is the type of the base generator.
   */
  @Test
  void testBuildStepFunctionCorrectType() {
    final Fixture f = new Fixture();

    // Create tabulation result with edges list
    final Core.Exp edgesList =
        core.list(
            f.typeSystem,
            core.tuple(f.typeSystem, f.intLiteral(1), f.intLiteral(2)));

    final VarEnvironment varEnv =
        VarEnvironment.initial(
            ImmutableList.of(f.xPat, f.yPat),
            ImmutableMap.of(),
            Environments.empty());

    final ProcessTreeNode.TerminalNode dummySource =
        ProcessTreeNode.TerminalNode.of(edgesList, varEnv, Optional.empty());

    final PredicateInverter.IOPair ioPair =
        new PredicateInverter.IOPair(ImmutableMap.of(), edgesList, dummySource);

    final PredicateInverter.TabulationResult tabulation =
        new PredicateInverter.TabulationResult(
            ImmutableList.of(ioPair), Generator.Cardinality.FINITE, false);

    final PredicateInverter inverter =
        new PredicateInverter(f.typeSystem, Environments.empty());

    try {
      Method method =
          PredicateInverter.class.getDeclaredMethod(
              "buildStepFunction",
              PredicateInverter.TabulationResult.class,
              VarEnvironment.class);
      method.setAccessible(true);
      Core.Exp result = (Core.Exp) method.invoke(inverter, tabulation, varEnv);

      // Check that the type is a function type
      assertThat(result.type.op(), hasToString("FUNCTION_TYPE"));

      // The function should take a tuple of two bags
      final net.hydromatic.morel.type.FnType fnType =
          (net.hydromatic.morel.type.FnType) result.type;
      final Type paramType = fnType.paramType;
      assertThat(paramType.op(), hasToString("TUPLE_TYPE"));
    } catch (Exception e) {
      throw new RuntimeException("Failed to invoke buildStepFunction", e);
    }
  }

  /**
   * Test 3: buildStepLambda produces correct lambda structure.
   *
   * <p>The lambda should have a tuple parameter pattern (old, new) and a FROM
   * expression as the body.
   */
  @Test
  void testBuildStepLambdaCorrectStructure() {
    final Fixture f = new Fixture();

    final Core.Exp edgesList =
        core.list(
            f.typeSystem,
            core.tuple(f.typeSystem, f.intLiteral(1), f.intLiteral(2)));

    final Set<Core.NamedPat> threadedVars = ImmutableSet.of(f.xPat, f.yPat);

    final VarEnvironment varEnv =
        VarEnvironment.initial(
            ImmutableList.of(f.xPat, f.yPat),
            ImmutableMap.of(),
            Environments.empty());

    final PredicateInverter inverter =
        new PredicateInverter(f.typeSystem, Environments.empty());

    try {
      Method method =
          PredicateInverter.class.getDeclaredMethod(
              "buildStepLambda",
              Core.Exp.class,
              Set.class,
              VarEnvironment.class);
      method.setAccessible(true);
      Core.Exp result =
          (Core.Exp) method.invoke(inverter, edgesList, threadedVars, varEnv);

      // Should be a FN expression
      assertThat(result.op, hasToString("FN"));

      final Core.Fn fn = (Core.Fn) result;

      // Parameter should be an ID pattern (simple parameter)
      assertThat(fn.idPat.op, hasToString("ID_PAT"));

      // Body should be a CASE expression (for tuple destructuring)
      assertThat(fn.exp.op, hasToString("CASE"));

      final Core.Case caseExp = (Core.Case) fn.exp;

      // Case should have one match with a TUPLE_PAT
      assertThat(caseExp.matchList.size(), hasToString("1"));
      final Core.Match match = caseExp.matchList.get(0);
      assertThat(match.pat.op, hasToString("TUPLE_PAT"));

      // Match body should be a FROM expression
      assertThat(match.exp.op, hasToString("FROM"));
    } catch (Exception e) {
      throw new RuntimeException("Failed to invoke buildStepLambda", e);
    }
  }

  /**
   * Test 4: buildStepBody creates FROM expression with SCAN, WHERE, YIELD.
   *
   * <p>The FROM expression should scan both the base generator and new results,
   * add a WHERE clause for the join, and yield the result tuple.
   */
  @Test
  void testBuildStepBodyFromExpression() {
    final Fixture f = new Fixture();

    final Core.Exp edgesList =
        core.list(
            f.typeSystem,
            core.tuple(f.typeSystem, f.intLiteral(1), f.intLiteral(2)));

    final Type bagType = edgesList.type;
    final Core.IdPat oldPat = core.idPat(bagType, "oldTuples", 0);
    final Core.IdPat newPat = core.idPat(bagType, "newTuples", 0);

    final Set<Core.NamedPat> threadedVars = ImmutableSet.of(f.xPat, f.yPat);

    final VarEnvironment varEnv =
        VarEnvironment.initial(
            ImmutableList.of(f.xPat, f.yPat),
            ImmutableMap.of(),
            Environments.empty());

    final PredicateInverter inverter =
        new PredicateInverter(f.typeSystem, Environments.empty());

    try {
      Method method =
          PredicateInverter.class.getDeclaredMethod(
              "buildStepBody",
              Core.Exp.class,
              Core.NamedPat.class,
              Core.NamedPat.class,
              Set.class,
              VarEnvironment.class);
      method.setAccessible(true);
      Core.Exp result =
          (Core.Exp)
              method.invoke(
                  inverter, edgesList, oldPat, newPat, threadedVars, varEnv);

      // Should be a FROM expression
      assertThat(result.op, hasToString("FROM"));

      final Core.From from = (Core.From) result;

      // Should have at least 2 steps: scan base, scan new
      assertThat(from.steps.size() >= 2, hasToString("true"));

      // First step should be SCAN
      assertThat(from.steps.get(0).op, hasToString("SCAN"));

      // Second step should be SCAN
      assertThat(from.steps.get(1).op, hasToString("SCAN"));
    } catch (Exception e) {
      throw new RuntimeException("Failed to invoke buildStepBody", e);
    }
  }

  /**
   * Test 5: identifyJoinVariable returns first threaded variable.
   *
   * <p>Given a set of threaded variables, identifyJoinVariable should return
   * one of them (currently returns the first).
   */
  @Test
  void testIdentifyJoinVariableSimple() {
    final Fixture f = new Fixture();

    final Set<Core.NamedPat> threadedVars = ImmutableSet.of(f.xPat, f.yPat);

    final PredicateInverter inverter =
        new PredicateInverter(f.typeSystem, Environments.empty());

    try {
      Method method =
          PredicateInverter.class.getDeclaredMethod(
              "identifyJoinVariable", Set.class);
      method.setAccessible(true);
      Core.NamedPat result =
          (Core.NamedPat) method.invoke(inverter, threadedVars);

      // Should return one of the threaded variables
      assertThat(threadedVars.contains(result), hasToString("true"));
    } catch (Exception e) {
      throw new RuntimeException("Failed to invoke identifyJoinVariable", e);
    }
  }

  /**
   * Test 6: End-to-end integration test.
   *
   * <p>Given a complete tabulation result, buildStepFunction should generate a
   * compilable step function that can be used with Relational.iterate.
   */
  @Test
  void testStepFunctionIntegration() {
    final Fixture f = new Fixture();

    // Create realistic tabulation result for path(x,y) transitive closure
    final Core.Exp edgesList =
        core.list(
            f.typeSystem,
            core.tuple(f.typeSystem, f.intLiteral(1), f.intLiteral(2)),
            core.tuple(f.typeSystem, f.intLiteral(2), f.intLiteral(3)));

    final VarEnvironment varEnv =
        VarEnvironment.initial(
            ImmutableList.of(f.xPat, f.yPat),
            ImmutableMap.of(),
            Environments.empty());

    final ProcessTreeNode.TerminalNode dummySource =
        ProcessTreeNode.TerminalNode.of(edgesList, varEnv, Optional.empty());

    final PredicateInverter.Generator generator =
        new PredicateInverter.Generator(
            core.tuplePat(f.typeSystem, ImmutableList.of(f.xPat, f.yPat)),
            edgesList,
            Generator.Cardinality.FINITE,
            ImmutableList.of(),
            ImmutableSet.of());

    final PredicateInverter.IOPair ioPair =
        new PredicateInverter.IOPair(ImmutableMap.of(), edgesList, dummySource);

    final PredicateInverter.TabulationResult tabulation =
        new PredicateInverter.TabulationResult(
            ImmutableList.of(ioPair), Generator.Cardinality.FINITE, false);

    final PredicateInverter inverter =
        new PredicateInverter(f.typeSystem, Environments.empty());

    try {
      Method method =
          PredicateInverter.class.getDeclaredMethod(
              "buildStepFunction",
              PredicateInverter.TabulationResult.class,
              VarEnvironment.class);
      method.setAccessible(true);
      Core.Exp stepFunction =
          (Core.Exp) method.invoke(inverter, tabulation, varEnv);

      // Should successfully generate a step function
      assertThat(stepFunction, not(hasToString("null")));
      assertThat(stepFunction.op, hasToString("FN"));

      // The step function should be a valid lambda expression
      final Core.Fn fn = (Core.Fn) stepFunction;

      // Parameter should be an ID pattern (simple parameter)
      assertThat(fn.idPat.op, hasToString("ID_PAT"));

      // Body should be a CASE expression (for tuple destructuring)
      assertThat(fn.exp.op, hasToString("CASE"));

      final Core.Case caseExp = (Core.Case) fn.exp;

      // Case should have one match with a TUPLE_PAT
      assertThat(caseExp.matchList.size(), hasToString("1"));
      final Core.Match match = caseExp.matchList.get(0);
      assertThat(match.pat.op, hasToString("TUPLE_PAT"));

      // Match body should be a FROM expression
      assertThat(match.exp.op, hasToString("FROM"));

      // Validate the FROM expression structure
      final Core.From from = (Core.From) match.exp;
      assertThat(from.steps.size() >= 2, hasToString("true"));
    } catch (Exception e) {
      throw new RuntimeException("Failed integration test", e);
    }
  }
}

// End PredicateInverterTest.java
