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

import static net.hydromatic.morel.eval.Render.renderBag;
import static net.hydromatic.morel.eval.Render.renderDatatype;
import static net.hydromatic.morel.eval.Render.renderList;
import static net.hydromatic.morel.eval.Render.renderPrimitive;
import static net.hydromatic.morel.eval.Render.renderReal;
import static net.hydromatic.morel.eval.Render.renderRecord;
import static net.hydromatic.morel.eval.Render.renderValue;
import static net.hydromatic.morel.eval.Render.renderWord;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasToString;

import com.google.common.collect.ImmutableSortedMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.eval.Render;
import net.hydromatic.morel.eval.Unit;
import net.hydromatic.morel.type.DataType;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import org.junit.jupiter.api.Test;

/** Tests for {@link Render}. */
public class RenderTest {
  private final TypeSystem ts = new TypeSystem();

  {
    BuiltIn.dataTypes(ts, new ArrayList<>());
  }

  private RecordType record(String n0, Type t0, String n1, Type t1) {
    return (RecordType)
        ts.recordType(
            ImmutableSortedMap.<String, Type>orderedBy(RecordType.ORDERING)
                .put(n0, t0)
                .put(n1, t1)
                .build());
  }

  @Test
  void testPrimitives() {
    assertThat(renderPrimitive(PrimitiveType.BOOL, true), is("true"));
    assertThat(renderPrimitive(PrimitiveType.BOOL, false), is("false"));
    assertThat(renderPrimitive(PrimitiveType.INT, 5), is("5"));
    assertThat(renderPrimitive(PrimitiveType.INT, -5), is("~5"));
    assertThat(renderPrimitive(PrimitiveType.STRING, "hi"), is("\"hi\""));
    assertThat(renderPrimitive(PrimitiveType.CHAR, 'a'), is("#\"a\""));
    assertThat(renderPrimitive(PrimitiveType.UNIT, Unit.INSTANCE), is("()"));
  }

  /** Tests the StringBuilder form of {@code renderPrimitive}. */
  @Test
  void testPrimitivesStringBuilder() {
    final StringBuilder b = new StringBuilder("x=");
    renderPrimitive(b, PrimitiveType.INT, -5);
    assertThat(b, hasToString("x=~5"));
    assertThat(renderReal(new StringBuilder(), 1.5f), hasToString("1.5"));
    assertThat(renderWord(new StringBuilder(), 255L), hasToString("0wxFF"));
  }

  @Test
  void testValuePrimitives() {
    assertThat(renderValue(ts, PrimitiveType.INT, 42), is("42"));
    assertThat(renderValue(ts, PrimitiveType.STRING, "x\"y"), is("\"x\\\"y\""));
    assertThat(
        renderValue(new StringBuilder(), ts, PrimitiveType.INT, 42),
        hasToString("42"));
  }

  @Test
  void testList() {
    final Type intList = ts.listType(PrimitiveType.INT);
    assertThat(renderValue(ts, intList, Arrays.asList(1, 2, 3)), is("[1,2,3]"));
    assertThat(renderValue(ts, intList, Collections.emptyList()), is("[]"));
    final Type nested = ts.listType(intList);
    assertThat(
        renderValue(
            ts, nested, Arrays.asList(Arrays.asList(1, 2), Arrays.asList(3))),
        is("[[1,2],[3]]"));
    // The type-specific renderList method.
    assertThat(
        renderList(ts, PrimitiveType.INT, Arrays.asList(1, 2, 3)),
        is("[1,2,3]"));
  }

  @Test
  void testBag() {
    final Type bag = ts.bagType(PrimitiveType.STRING);
    assertThat(
        renderValue(ts, bag, Arrays.asList("a", "b")), is("[\"a\",\"b\"]"));
    // A bag renders like a list.
    assertThat(
        renderBag(ts, PrimitiveType.STRING, Arrays.asList("a", "b")),
        is("[\"a\",\"b\"]"));
    // renderDatatype reaches the same collection path.
    assertThat(
        renderDatatype(ts, (DataType) bag, Arrays.asList("a", "b")),
        is("[\"a\",\"b\"]"));
  }

  @Test
  void testRecord() {
    final RecordType rec =
        record("color", PrimitiveType.STRING, "units", PrimitiveType.INT);
    assertThat(
        renderValue(ts, rec, Arrays.asList("red", 10)),
        is("{color=\"red\",units=10}"));
    assertThat(
        renderRecord(ts, rec, Arrays.asList("red", 10)),
        is("{color=\"red\",units=10}"));
  }

  @Test
  void testListOfRecords() {
    final Type rec = record("a", PrimitiveType.INT, "b", PrimitiveType.INT);
    final Type list = ts.listType(rec);
    assertThat(
        renderValue(
            ts, list, Arrays.asList(Arrays.asList(1, 2), Arrays.asList(3, 4))),
        is("[{a=1,b=2},{a=3,b=4}]"));
  }
}

// End RenderTest.java
