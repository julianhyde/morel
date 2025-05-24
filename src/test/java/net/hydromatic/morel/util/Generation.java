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
package net.hydromatic.morel.util;

import static com.google.common.collect.ImmutableList.sortedCopyOf;
import static net.hydromatic.morel.util.Static.filterEager;
import static org.apache.calcite.util.Util.first;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import com.google.common.collect.Ordering;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.hydromatic.morel.Main;
import net.hydromatic.morel.TestUtils;

/** Generates code from metadata. */
public class Generation {
  private Generation() {}

  /**
   * Reads the {@code functions.toml} file and generates a table of function
   * definitions into {@code reference.md}.
   */
  @SuppressWarnings("unchecked")
  public static void generateFunctionTable(PrintWriter pw, List<String> names)
      throws IOException {
    final URL inUrl = Main.class.getResource("/functions.toml");
    assertThat(inUrl, notNullValue());
    final File file = TestUtils.urlToFile(inUrl);

    final TomlMapper mapper = new TomlMapper();
    try (MappingIterator<Object> it =
        mapper.readerForMapOf(Object.class).readValues(file)) {
      while (it.hasNextValue()) {
        Map<String, Object> row = (Map<String, Object>) it.nextValue();
        List<Map<String, Object>> functions =
            (List<Map<String, Object>>) row.get("functions");

        for (Map<String, Object> function : functions) {
          names.add((String) function.get("name"));
        }
        if (!Ordering.natural().isOrdered(names)) {
          fail(
              "Names are not sorted\n"
                  + TestUtils.diffLines(names, sortedCopyOf(names)));
        }

        functions.sort(Generation::compare);
        Predicate<Map<String, Object>> isImplemented =
            Generation::isImplemented;
        List<Map<String, Object>> implemented =
            filterEager(functions, isImplemented);
        generateTable(pw, implemented);

        List<Map<String, Object>> notImplemented =
            filterEager(functions, isImplemented.negate());
        if (!notImplemented.isEmpty()) {
          pw.printf("Not yet implemented%n");
          generateTable(pw, notImplemented);
        }
      }
    }
  }

  private static boolean isImplemented(Map<String, Object> o) {
    return first((Boolean) o.get("implemented"), true);
  }

  private static void generateTable(
      PrintWriter pw, List<Map<String, Object>> functions) {
    pw.printf("%n");
    row(pw, "Name", "Type", "Description");
    row(pw, "----", "----", "-----------");
    for (Map<String, Object> function : functions) {
      String name = (String) function.get("name");
      String type = (String) function.get("type");
      String description = (String) function.get("description");
      String extra = (String) function.get("extra");
      String name2 = munge(name);
      String type2 = munge(type);
      String description2 = munge(description);
      if (extra != null) {
        description2 += " " + extra.trim();
      }
      row(pw, name2, type2, description2);
    }
    pw.printf("%n");
  }

  private static void row(
      PrintWriter pw, String name, String type, String description) {
    pw.printf("| %s | %s | %s |\n", name, type, description);
  }

  private static int compare(Map<String, Object> f1, Map<String, Object> f2) {
    Integer o1 = (Integer) f1.get("ordinal");
    Integer o2 = (Integer) f2.get("ordinal");
    if (o1 != null && o2 != null) {
      int c = o1.compareTo(o2);
      if (c != 0) {
        return c;
      }
    }
    String n1 = (String) f1.get("name");
    String n2 = (String) f2.get("name");
    return n1.compareTo(n2);
  }

  private static String munge(String s) {
    return s.trim()
        .replace("α", "&alpha;")
        .replace("β", "&beta;")
        .replace("γ", "&gamma;")
        .replace("→", "&rarr;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("≤", "&le;")
        .replace("≥", "&ge;")
        .replace("&lt;br&gt;", "<br>")
        .replace("&lt;sup&gt;", "<sup>")
        .replace("&lt;/sup&gt;", "</sup>")
        .replace("[", "\\[")
        .replace("]", "\\]")
        .replace("|", "\\|")
        .replace("\n", " ")
        .replaceAll(" *<br>", "<br>");
  }
}

// End Generation.java
