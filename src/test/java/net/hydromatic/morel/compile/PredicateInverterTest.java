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
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasToString;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Optional;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.compile.PredicateInverter.InversionResult;
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

    PredicateInverter inverter() {
      return new PredicateInverter(typeSystem, null);
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
    final PredicateInverter inverter = f.inverter();

    // Build: x elem myList
    final Core.Exp predicate = core.elem(f.typeSystem, f.xId, f.myListId);

    // Invert to generate x
    final Optional<InversionResult> resultOpt =
        inverter.invert(
            predicate, ImmutableSet.of(f.xPat), ImmutableSortedMap.of());

    // Should successfully invert to myList
    assertTrue(
        resultOpt.isPresent(), "Should successfully invert 'x elem myList'");
    final InversionResult result = resultOpt.get();

    assertThat(result.generator, hasToString("myList"));
    assertThat(result.mayHaveDuplicates, is(false));
    assertThat(result.isSupersetOfSolution, is(false));
    assertThat(result.satisfiedPats, is(ImmutableSet.of(f.xPat)));
  }

  /**
   * Test 2: Invert 'String.isPrefix s "abcd"' to '["","a","ab","abc","abcd"]'.
   *
   * <p>Given the predicate {@code String.isPrefix s "abcd"}, invert it to
   * generate all possible prefixes of "abcd".
   */
  @Test
  void testInvertStringIsPrefix() {
    final Fixture f = new Fixture();
    final PredicateInverter inverter = f.inverter();

    // Build: String.isPrefix s "abcd"
    // This is represented as: String_isPrefix (s, "abcd")
    final Core.Exp stringIsPrefixFn =
        core.functionLiteral(f.typeSystem, BuiltIn.STRING_IS_PREFIX);
    final Core.Exp predicate =
        core.apply(
            Pos.ZERO,
            f.typeSystem,
            BuiltIn.STRING_IS_PREFIX,
            f.sId,
            f.stringLiteral("abcd"));

    // Invert to generate s
    final Optional<InversionResult> resultOpt =
        inverter.invert(
            predicate, ImmutableSet.of(f.sPat), ImmutableSortedMap.of());

    // Should successfully invert to ["", "a", "ab", "abc", "abcd"]
    assertTrue(
        resultOpt.isPresent(),
        "Should successfully invert 'String.isPrefix s \"abcd\"'");
    final InversionResult result = resultOpt.get();

    // Expected: ["", "a", "ab", "abc", "abcd"]
    final Core.Exp expected =
        core.list(
            f.typeSystem,
            f.stringLiteral(""),
            f.stringLiteral("a"),
            f.stringLiteral("ab"),
            f.stringLiteral("abc"),
            f.stringLiteral("abcd"));

    assertThat(result.generator, hasToString(expected.toString()));
    assertThat(result.mayHaveDuplicates, is(false));
    assertThat(result.isSupersetOfSolution, is(false));
    assertThat(result.satisfiedPats, is(ImmutableSet.of(f.sPat)));
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
    final PredicateInverter inverter = f.inverter();

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
    final Optional<InversionResult> resultOpt =
        inverter.invert(
            conjunction,
            ImmutableSet.of(f.empnoPat, f.dnamePat),
            ImmutableSortedMap.of());

    // Should successfully invert
    assertTrue(
        resultOpt.isPresent(),
        "Should successfully invert composite function with exists");
    final InversionResult result = resultOpt.get();

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
    final PredicateInverter inverter = f.inverter();

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
    final Optional<InversionResult> resultOpt =
        inverter.invert(
            edgeCall, ImmutableSet.of(f.xPat, f.yPat), ImmutableSortedMap.of());

    // Should successfully invert
    assertTrue(
        resultOpt.isPresent(), "Should successfully invert 'edge(x, y)'");
    final InversionResult result = resultOpt.get();

    // After inlining and inverting, should get edges
    // (actual implementation will need function body lookup)
    assertThat(result.satisfiedPats, is(ImmutableSet.of(f.xPat, f.yPat)));
  }

  /**
   * Test 5: Invert 'x > y andalso x < y + 10' to generate y values when x is
   * known to be 3, 5, or 7.
   *
   * <p>Given x is bound to [3, 5, 7], we want to find all y such that:
   *
   * <ul>
   *   <li>3 > y andalso 3 < y + 10, which gives y in (-infinity, 3) intersect
   *       (-7, infinity) = (-7, 3)
   *   <li>5 > y andalso 5 < y + 10, which gives y in (-infinity, 5) intersect
   *       (-5, infinity) = (-5, 5)
   *   <li>7 > y andalso 7 < y + 10, which gives y in (-infinity, 7) intersect
   *       (-3, infinity) = (-3, 7)
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
    final PredicateInverter inverter = f.inverter();

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
    final Optional<InversionResult> resultOpt =
        inverter.invert(
            predicate,
            ImmutableSet.of(f.yPat),
            ImmutableSortedMap.of(f.xPat, xExtent));

    // Should successfully invert
    assertTrue(
        resultOpt.isPresent(),
        "Should successfully invert range constraints for y");
    final InversionResult result = resultOpt.get();

    // The generator should produce y values for each x value
    // Expected structure: from x in [3,5,7], y in List.tabulate(9, fn k => x -
    // 9 + k) yield y
    assertThat(result.satisfiedPats, is(ImmutableSet.of(f.yPat)));
  }

  /**
   * Test 6: Invert 'x > y andalso x < y + 10' to generate x values when y is
   * given.
   *
   * <p>Given y is bound to some value (or set of values), we want to find all x
   * such that: y < x < y + 10.
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
    final PredicateInverter inverter = f.inverter();

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
    final Optional<InversionResult> resultOpt =
        inverter.invert(
            predicate,
            ImmutableSet.of(f.xPat),
            ImmutableSortedMap.of(f.yPat, yExtent));

    // Should successfully invert
    assertTrue(
        resultOpt.isPresent(),
        "Should successfully invert range constraints for x");
    final InversionResult result = resultOpt.get();

    // The generator should produce x values for each y value
    // Expected structure: from y in [0,5,10], x in List.tabulate(9, fn k => y +
    // 1 + k) yield x
    assertThat(result.satisfiedPats, is(ImmutableSet.of(f.xPat)));
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
    final PredicateInverter inverter = f.inverter();

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
    final Optional<InversionResult> resultOpt =
        inverter.invert(
            predicate, ImmutableSet.of(f.xPat), ImmutableSortedMap.of());

    // Should fail to invert
    assertThat(resultOpt.isPresent(), is(false));
  }
}

// End PredicateInverterTest.java
