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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import net.hydromatic.morel.Main;
import net.hydromatic.morel.eval.Prop;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * BagPrinter that reorders bag elements to match expected output.
 *
 * <p>Before each command is evaluated, the expected output (from the script
 * file) is set via {@link #setExpectedOutput}. When a bag is printed, the
 * elements are reordered to match the expected element order.
 */
public class ExpectedOutputBagPrinter implements BagPrinter {
  private final TypeSystem typeSystem;
  private @Nullable String currentExpected;

  public ExpectedOutputBagPrinter(TypeSystem typeSystem) {
    this.typeSystem = typeSystem;
  }

  @Override
  public void setExpectedOutput(@Nullable String expectedOutput) {
    this.currentExpected = expectedOutput;
  }

  @Override
  public List<Object> order(List<Object> elements, Type elementType) {
    if (currentExpected == null) {
      return elements;
    }
    List<String> expectedElements =
        Main.parseBracketedElements(currentExpected);
    if (expectedElements == null) {
      return elements;
    }

    // Format each actual element using a plain Pretty
    Pretty plain =
        new Pretty(
            typeSystem,
            -1,
            Prop.Output.CLASSIC,
            -1,
            -1,
            -1,
            BagPrinter.NATURAL);
    Map<String, Queue<Object>> byFormatted = new LinkedHashMap<>();
    for (Object elem : elements) {
      StringBuilder buf = new StringBuilder();
      plain.pretty(buf, elementType, elem);
      byFormatted
          .computeIfAbsent(buf.toString().trim(), k -> new ArrayDeque<>())
          .add(elem);
    }

    // Reorder: match expected strings to actual elements
    List<Object> result = new ArrayList<>();
    for (String exp : expectedElements) {
      Queue<Object> q = byFormatted.get(exp.trim());
      if (q != null && !q.isEmpty()) {
        result.add(q.poll());
      }
    }
    // Append unmatched
    for (Queue<Object> q : byFormatted.values()) {
      result.addAll(q);
    }
    return result;
  }
}

// End ExpectedOutputBagPrinter.java
