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
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasToString;
import static org.hamcrest.core.Is.is;

import java.util.ArrayList;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.eval.Code;
import net.hydromatic.morel.eval.Codes;
import net.hydromatic.morel.eval.Stack;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.TypeSystem;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link StackLayout} and {@code Codes.StackCode}.
 *
 * <p>Verifies the compile-time and runtime infrastructure added in Step 3 of
 * the stack-based evaluation migration.
 */
public class StackLayoutTest {
  private final TypeSystem typeSystem = new TypeSystem();

  {
    BuiltIn.dataTypes(typeSystem, new ArrayList<>());
  }

  private Core.NamedPat namedPat(String name) {
    return core.idPat(PrimitiveType.INT, name, 0);
  }

  @Test
  public void testEmptyLayout() {
    assertThat(StackLayout.EMPTY.size(), is(0));
  }

  @Test
  public void testGetUnknown() {
    final Core.NamedPat x = namedPat("x");
    assertThat(StackLayout.EMPTY.get(x), is(-1));
  }

  @Test
  public void testWithSingleSlot() {
    final Core.NamedPat x = namedPat("x");
    final StackLayout layout = StackLayout.EMPTY.with(x, 0);
    assertThat(layout.size(), is(1));
    assertThat(layout.get(x), is(0));
  }

  @Test
  public void testWithMultipleSlots() {
    final Core.NamedPat x = namedPat("x");
    final Core.NamedPat y = namedPat("y");
    final StackLayout layout = StackLayout.EMPTY.with(x, 0).with(y, 1);
    assertThat(layout.size(), is(2));
    assertThat(layout.get(x), is(0));
    assertThat(layout.get(y), is(1));
  }

  @Test
  public void testImmutability() {
    // Adding a slot to a layout does not modify the original.
    final Core.NamedPat x = namedPat("x");
    final StackLayout base = StackLayout.EMPTY;
    final StackLayout extended = base.with(x, 0);
    assertThat(base.size(), is(0));
    assertThat(extended.size(), is(1));
  }

  @Test
  public void testStackGetDescribe() {
    // Verify that Codes.stackGet produces a Code whose describe output matches
    // the expected "stack(offset=N, name=...)" format used by Sys.plan.
    final Code code = Codes.stackGet(1, "x");
    assertThat(code, hasToString("stack(offset=1, name=x)"));
  }

  @Test
  public void testStackCodeEval() {
    // Verify that StackCode reads the correct slot from the stack.
    final Code code = Codes.stackGet(1, "x");
    final Stack stack = Stack.withCapacity(4);
    stack.slots[0] = "value-at-slot-0";
    stack.top = 1;
    // offset=1 means stack.slots[top - 1] = stack.slots[0]
    assertThat(code.eval(stack), is("value-at-slot-0"));
  }

  @Test
  public void testStackCodeEvalDeep() {
    // Two values on stack; offset 2 reaches the deeper one.
    final Code code = Codes.stackGet(2, "y");
    final Stack stack = Stack.withCapacity(4);
    stack.slots[0] = "deep";
    stack.slots[1] = "shallow";
    stack.top = 2;
    // offset=2 means stack.slots[top - 2] = stack.slots[0]
    assertThat(code.eval(stack), is("deep"));
  }
}

// End StackLayoutTest.java
