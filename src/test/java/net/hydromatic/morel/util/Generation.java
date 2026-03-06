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

import static com.google.common.base.Strings.repeat;
import static com.google.common.collect.ImmutableList.sortedCopyOf;
import static java.util.Objects.requireNonNull;
import static net.hydromatic.morel.util.Static.filterEager;
import static net.hydromatic.morel.util.Static.transformEager;
import static org.apache.calcite.util.Util.first;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import com.google.common.collect.Ordering;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.hydromatic.morel.Main;
import net.hydromatic.morel.TestUtils;
import net.hydromatic.morel.eval.Prop;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Generates code from metadata. */
public class Generation {
  private Generation() {}

  /**
   * Reads the {@code functions.toml} file and generates a table of function
   * definitions into {@code reference.md}.
   */
  public static void generateFunctionTable(PrintWriter pw) throws IOException {
    new FunctionTableGenerator(pw).generate();
  }

  /**
   * Returns the set of "Structure.name" keys documented in {@code
   * functions.toml}.
   *
   * <p>Names with a disambiguation qualifier (e.g. {@code "fromInt, int"}) are
   * normalized by stripping everything from the first {@code ", "} onwards, so
   * the returned key for such an entry is {@code "Int.fromInt"}.
   */
  @SuppressWarnings("unchecked")
  public static Set<String> functionNames() throws IOException {
    final File file = getFile();

    final Set<String> names = new HashSet<>();
    final TomlMapper mapper = new TomlMapper();
    try (MappingIterator<Object> it =
        mapper.readerForMapOf(Object.class).readValues(file)) {
      while (it.hasNextValue()) {
        final Map<String, Object> row = (Map<String, Object>) it.nextValue();
        for (Map<String, Object> entry :
            (List<Map<String, Object>>) row.get("functions")) {
          String structure = (String) entry.get("structure");
          String name = (String) entry.get("name");
          int comma = name.indexOf(", ");
          if (comma >= 0) {
            name = name.substring(0, comma);
          }
          names.add(structure + "." + name);
        }
      }
    }
    return names;
  }

  /** The {@code functions.toml} file as a URL. */
  public static URL getResource() {
    return requireNonNull(Main.class.getResource("/functions.toml"));
  }

  /** The {@code functions.toml} file. */
  public static File getFile() {
    return requireNonNull(TestUtils.urlToFile(getResource()));
  }

  /**
   * Returns entry names in file order from {@code [[functions]]}, {@code
   * [[types]]}, and {@code [[exceptions]]} blocks. {@code [[values]]} and
   * {@code [[structures]]} blocks are excluded.
   */
  static List<String> allEntryNamesInOrder(File file) throws IOException {
    final List<String> names = new ArrayList<>();
    boolean skip = false;
    String structure = null;
    String name = null;
    try (BufferedReader br = new BufferedReader(new FileReader(file))) {
      for (String line = br.readLine(); line != null; line = br.readLine()) {
        if (line.startsWith("[[")) {
          if (!skip && structure != null && name != null) {
            names.add(structure + "." + name);
          }
          structure = null;
          name = null;
          skip = line.equals("[[values]]") || line.equals("[[structures]]");
        } else if (!skip) {
          if (line.startsWith("structure = \"")) {
            structure = line.substring(13, line.length() - 1);
          } else if (line.startsWith("name = \"")) {
            name = line.substring(8, line.length() - 1);
            final int comma = name.indexOf(", ");
            if (comma >= 0) {
              name = name.substring(0, comma);
            }
          }
        }
      }
    }
    if (!skip && structure != null && name != null) {
      names.add(structure + "." + name);
    }
    return names;
  }

  private static class FunctionTableGenerator {
    private final PrintWriter pw;

    FunctionTableGenerator(PrintWriter pw) {
      this.pw = pw;
    }

    @SuppressWarnings("unchecked")
    void generate() throws IOException {
      final File file = getFile();

      final TomlMapper mapper = new TomlMapper();
      try (MappingIterator<Object> it =
          mapper.readerForMapOf(Object.class).readValues(file)) {
        while (it.hasNextValue()) {
          final Map<String, Object> row = (Map<String, Object>) it.nextValue();
          final List<FnDef> fnDefs =
              transformEager(
                  (List<Map<String, Object>>) row.get("functions"),
                  FnDef::create);

          // Check [[structures]] entries are sorted by name.
          final Object structuresObj = row.get("structures");
          final List<Map<String, Object>> structs =
              structuresObj != null
                  ? (List<Map<String, Object>>) structuresObj
                  : new ArrayList<>();
          final List<String> structureNames =
              transformEager(structs, s -> (String) s.get("name"));
          if (!Ordering.natural().isOrdered(structureNames)) {
            fail(
                "Structure names are not sorted\n"
                    + TestUtils.diffLines(
                        structureNames, sortedCopyOf(structureNames)));
          }

          // All entries (functions, types, exceptions) must be sorted.
          // This reduces the chance of merge conflicts.
          final List<String> allNames = allEntryNamesInOrder(file);
          if (!Ordering.natural().isOrdered(allNames)) {
            fail(
                "Names are not sorted\n"
                    + TestUtils.diffLines(allNames, sortedCopyOf(allNames)));
          }

          // Build sorted list of functions. First add the ones with ordinals,
          // sorted by ordinal. Then add the rest, sorted by name.
          final List<FnDef> sortedFnDefs = new ArrayList<>();
          for (FnDef fnDef : fnDefs) {
            if (fnDef.ordinal >= 0) {
              sortedFnDefs.add(fnDef);
            }
          }
          sortedFnDefs.sort(
              Comparator.<FnDef, String>comparing(f -> f.structure)
                  .thenComparingInt(f -> f.ordinal));
          for (FnDef fnDef : fnDefs) {
            if (fnDef.ordinal <= 0) {
              int i =
                  findMax(
                      sortedFnDefs,
                      f ->
                          f.qualifiedName().compareTo(fnDef.qualifiedName())
                              < 0);
              sortedFnDefs.add(i, fnDef);
            }
          }

          List<FnDef> implemented =
              filterEager(sortedFnDefs, fn -> fn.implemented);
          generateTable(implemented);

          List<FnDef> notImplemented =
              filterEager(sortedFnDefs, fn -> !fn.implemented);
          if (!notImplemented.isEmpty()) {
            pw.printf("Not yet implemented%n");
            generateTable(notImplemented);
          }
        }
      }
    }

    /** Returns the first index of the list where the predicate is false. */
    private static <E> int findMax(List<E> list, Predicate<E> predicate) {
      for (int i = 0; i < list.size(); i++) {
        E e = list.get(i);
        if (!predicate.test(e)) {
          return i;
        }
      }
      return -1;
    }

    void generateTable(List<FnDef> functions) {
      pw.printf("%n");
      final Tabulator tabulator = new Tabulator(pw, 4, -1, -1);
      tabulator.header("Name", "Type", "Description");
      for (FnDef function : functions) {
        String name2 = munge(function.structure + '.' + function.name);
        String type2 = munge(function.type);
        String description2 =
            munge(
                function.description.startsWith("As ")
                    ? function.description
                    : '"' + function.prototype + "\" " + function.description);
        if (function.extra != null) {
          description2 += " " + function.extra.trim();
        }
        tabulator.row(name2, type2, description2);
      }
      pw.printf("%n");
    }
  }

  /** Generates a table of properties into {@code reference.md}. */
  public static void generatePropertyTable(PrintWriter pw) {
    new PropertyTableGenerator(pw).generate();
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
        .replace("`?&lt;&gt;`", "`?<>`")
        .replace("`&lt;`", "`<`")
        .replace("`&lt;&gt;`", "`<>`")
        .replace("`a &lt; b`", "`a < b`")
        .replace("`not (a &gt;= b)`", "`not (a >= b)`")
        .replace("&lt;br&gt;", "<br>")
        .replace("&lt;p&gt;", "<br><br>")
        .replace("&lt;sup&gt;", "<sup>")
        .replace("&lt;/sup&gt;", "</sup>")
        .replace("&lt;pre&gt;", "<pre>")
        .replace("&lt;/pre&gt;", "</pre>")
        .replace("|", "\\|")
        .replace("\n", " ")
        .replaceAll(" *<br>", "<br>");
  }

  /** Function definition. */
  private static class FnDef {
    final String structure;
    final String name;
    final String type;
    final String prototype;
    final String description;
    final @Nullable String extra;
    final boolean implemented;
    final int ordinal;

    FnDef(
        String structure,
        String name,
        String type,
        String prototype,
        String description,
        String extra,
        boolean implemented,
        int ordinal) {
      this.structure = requireNonNull(structure, "structure");
      this.name = requireNonNull(name, "name");
      this.type = requireNonNull(type, "type");
      this.prototype = requireNonNull(prototype, "prototype");
      this.description = requireNonNull(description, "description");
      this.extra = extra;
      this.implemented = implemented;
      this.ordinal = ordinal;
    }

    String qualifiedName() {
      return structure + '.' + name;
    }

    static FnDef create(Map<String, Object> map) {
      return new FnDef(
          (String) map.get("structure"),
          (String) map.get("name"),
          (String) map.get("type"),
          (String) map.get("prototype"),
          (String) map.get("description"),
          (String) map.get("extra"),
          first((Boolean) map.get("implemented"), true),
          map.containsKey("ordinal") ? (Integer) map.get("ordinal") : -1);
    }
  }

  /** Generates a table of {@link Prop} values. */
  private static class PropertyTableGenerator {
    private final PrintWriter pw;

    PropertyTableGenerator(PrintWriter pw) {
      this.pw = pw;
    }

    void generate() {
      pw.printf("%n");
      final List<Prop> propList =
          Ordering.from(Comparator.comparing((Prop p) -> p.name()))
              .sortedCopy(Arrays.asList(Prop.values()));
      final Tabulator tabulator = new Tabulator(pw, 20, 6, 7, -1);
      tabulator.header("Name", "Type", "Default", "Description");
      for (Prop p : propList) {
        tabulator.row(
            p.camelName, p.typeName(), p.defaultValue(), munge(p.description));
      }
      pw.printf("%n");
    }
  }

  /** Generates a Markdown table. */
  private static class Tabulator {
    private final PrintWriter pw;
    private final String format;
    private final int[] widths;

    private Tabulator(PrintWriter pw, int... widths) {
      this.pw = pw;
      this.widths = widths;

      final StringBuilder b = new StringBuilder("|");
      for (int width : widths) {
        b.append(" ")
            .append(width < 0 ? "%s" : "%-" + width + "s")
            .append(" |");
      }
      b.append("%n");
      this.format = b.toString();
    }

    void row(Object... values) {
      pw.printf(format, values);
    }

    void header(String... names) {
      row((Object[]) names);

      Object[] hyphens = new Object[names.length];
      for (int i = 0; i < names.length; i++) {
        String name = names[i];
        hyphens[i] = repeat("-", Math.max(widths[i], name.length()));
      }
      row(hyphens);
    }
  }
}

// End Generation.java
