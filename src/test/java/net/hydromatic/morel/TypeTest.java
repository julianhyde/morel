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

import net.hydromatic.morel.type.FnType;
import net.hydromatic.morel.type.ListType;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.type.TypeVar;

import org.hamcrest.Matcher;
import org.junit.jupiter.api.Test;

import java.util.function.BiFunction;
import java.util.function.UnaryOperator;

import static net.hydromatic.morel.Matchers.throwsA;
import static net.hydromatic.morel.compile.TypeUnifier.unify;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasToString;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.fail;

/** Unit test for types. */
public class TypeTest {
  @Test void testUnifyTypes() {
    checkUnifyTypes(true);
    checkUnifyTypes(false);
  }

  void checkUnifyTypes(boolean shortCut) {
    final TypeSystem typeSystem = new TypeSystem();
    final BiFunction<Type, Type, UnaryOperator<Type>> f =
        (t1, t2) -> unify(typeSystem, t1, t2, shortCut);
    final PrimitiveType boolType = PrimitiveType.BOOL; // bool
    final PrimitiveType intType = PrimitiveType.INT; // int
    final TypeVar alpha = typeSystem.typeVariable(0); // 'a
    final TypeVar beta = typeSystem.typeVariable(1); // 'b
    final TypeVar gamma = typeSystem.typeVariable(2); // 'c
    final FnType intToAlpha = typeSystem.fnType(intType, alpha); // int -> 'a
    final FnType intToBool =
        typeSystem.fnType(intType, boolType); // int -> bool
    final ListType v0List = typeSystem.listType(alpha); // 'a list
    final ListType intList = typeSystem.listType(intType); // int list
    final ListType intIntList =
        typeSystem.listType(
            typeSystem.tupleType(intType, intType)); // (int * int) list
    final ListType intBoolList =
        typeSystem.listType(
            typeSystem.tupleType(intType, boolType)); // (int * bool) list
    final ListType alphaAlphaList =
        typeSystem.listType(
            typeSystem.tupleType(alpha, alpha)); // ('a * 'a) list
    final ListType alphaBetaList =
        typeSystem.listType(
            typeSystem.tupleType(alpha, beta)); // ('a * 'b) list
    final ListType gammaAlphaList =
        typeSystem.listType(
            typeSystem.tupleType(gamma, alpha)); // ('c * 'a) list
    assertThat(f.apply(boolType, boolType),
        hasToString("{}"));
    assertUnify(f, intType, boolType,
        throwsA(IllegalArgumentException.class,
            is("cannot unify: conflict: int vs bool")));
    assertThat(f.apply(alpha, boolType),
        hasToString("{0=bool}"));
    assertThat(f.apply(alpha, beta),
        hasToString("{0='b}"));
    assertThat(f.apply(intToAlpha, intToBool),
        hasToString("{0=bool}"));
    assertThat(f.apply(v0List, intList),
        hasToString("{0=int}"));
    assertThat(f.apply(v0List, intList),
        hasToString("{0=int}"));
    assertThat(f.apply(alphaBetaList, intIntList),
        hasToString("{0=int, 1=int}"));
    assertThat(f.apply(alphaAlphaList, intIntList),
        hasToString("{0=int}"));
    assertThat(f.apply(alphaBetaList, intBoolList),
        hasToString("{0=int, 1=bool}"));
    assertThat(f.apply(gammaAlphaList, intBoolList),
        hasToString("{0=bool, 2=int}"));
  }

  void assertUnify(BiFunction<Type, Type, UnaryOperator<Type>> f,
      Type type1, Type type2, Matcher<Throwable> matcher) {
    try {
      final UnaryOperator<Type> unify = f.apply(type1, type2);
      fail("expected error, got " + unify);
    } catch (RuntimeException e) {
      assertThat(e, matcher);
    }
  }
}

// End TypeTest.java
