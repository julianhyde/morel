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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Strings.repeat;
import static com.google.common.collect.ImmutableList.sortedCopyOf;
import static java.lang.String.format;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
        for (FnDef fn :
            transformEager(
                (List<Map<String, Object>>) row.get("functions"),
                FnDef::create)) {
          names.add(fn.structure + "." + fn.canonicalName());
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
   * Returns entries in file order from {@code [[functions]]}, {@code
   * [[types]]}, and {@code [[exceptions]]} blocks. {@code [[values]]} and
   * {@code [[structures]]} blocks are excluded.
   *
   * <p>Each key is {@code "Foo.bar (function)"}, {@code "Foo.bar (type)"}, or
   * {@code "Foo.Bar (exception)"} — the qualified name followed by the element
   * kind in parentheses. Each value is the 1-based line number of the {@code
   * [[...]]} header.
   */
  static PairList<String, Integer> allEntryNamesInOrder(File file)
      throws IOException {
    final PairList<String, Integer> entries = PairList.of();
    String blockType = null; // "function", "type", "exception", or null (skip)
    int headerLine = -1;
    String structure = null;
    String name = null;
    int lineNumber = 0;
    try (BufferedReader br = new BufferedReader(new FileReader(file))) {
      for (String line = br.readLine(); line != null; line = br.readLine()) {
        lineNumber++;
        if (line.startsWith("[[")) {
          if (blockType != null && structure != null && name != null) {
            entries.add(
                structure + "." + name + " (" + blockType + ")", headerLine);
          }
          structure = null;
          name = null;
          headerLine = lineNumber;
          switch (line) {
            case "[[functions]]":
              blockType = "function";
              break;
            case "[[types]]":
              blockType = "type";
              break;
            case "[[exceptions]]":
              blockType = "exception";
              break;
            default:
              blockType = null; // skip [[values]], [[structures]], etc.
          }
        } else if (blockType != null) {
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
    if (blockType != null && structure != null && name != null) {
      entries.add(structure + "." + name + " (" + blockType + ")", headerLine);
    }
    return entries;
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

          final Object structuresObj = row.get("structures");
          final List<StrDef> strDefs =
              structuresObj != null
                  ? transformEager(
                      (List<Map<String, Object>>) structuresObj, StrDef::create)
                  : new ArrayList<>();

          final Object typesObj = row.get("types");
          final List<TyDef> tyDefs =
              typesObj != null
                  ? transformEager(
                      (List<Map<String, Object>>) typesObj, TyDef::create)
                  : new ArrayList<>();

          final Object exceptionsObj = row.get("exceptions");
          final List<ExnDef> exnDefs =
              exceptionsObj != null
                  ? transformEager(
                      (List<Map<String, Object>>) exceptionsObj, ExnDef::create)
                  : new ArrayList<>();

          // Check [[structures]] entries are sorted by name.
          final List<String> structureNames =
              transformEager(strDefs, s -> s.name);
          if (!Ordering.natural().isOrdered(structureNames)) {
            fail(
                "Structure names are not sorted\n"
                    + TestUtils.diffLines(
                        structureNames, sortedCopyOf(structureNames)));
          }

          // All entries (functions, types, exceptions) must be sorted.
          // This reduces the chance of merge conflicts.
          final PairList<String, Integer> entries = allEntryNamesInOrder(file);
          final List<String> names = entries.leftList();
          final List<Integer> lines = entries.rightList();
          for (int i = 1; i < names.size(); i++) {
            final String curr = names.get(i);
            final String prev = names.get(i - 1);
            if (prev.compareTo(curr) > 0) {
              final int currLine = lines.get(i);
              // Find the first entry whose name exceeds curr's; that is
              // where curr should be inserted.
              for (int j = 0; j < i; j++) {
                final String targetName = names.get(j);
                if (targetName.compareTo(curr) > 0) {
                  Integer targetLine = lines.get(j);
                  fail(
                      format(
                          "%s:%d: %s is out of order; move before %s at line %d",
                          file.getName(),
                          currLine,
                          curr,
                          targetName,
                          targetLine));
                  break;
                }
              }
              break;
            }
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

  /**
   * Returns the list of structure names declared in {@code functions.toml}, in
   * file order.
   */
  @SuppressWarnings("unchecked")
  public static List<String> structureNames() throws IOException {
    final File file = getFile();
    final List<String> names = new ArrayList<>();
    final TomlMapper mapper = new TomlMapper();
    try (MappingIterator<Object> it =
        mapper.readerForMapOf(Object.class).readValues(file)) {
      while (it.hasNextValue()) {
        final Map<String, Object> row = (Map<String, Object>) it.nextValue();
        final Object structuresObj = row.get("structures");
        if (structuresObj != null) {
          for (StrDef strDef :
              transformEager(
                  (List<Map<String, Object>>) structuresObj, StrDef::create)) {
            names.add(strDef.name);
          }
        }
      }
    }
    return names;
  }

  /**
   * Reads the {@code functions.toml} file and generates a two-column index
   * table of structures into {@code reference.md}.
   *
   * <p>Each row links the structure name to its {@code docs/lib/{name}.md} page
   * and shows the one-sentence description from the {@code [[structures]]}
   * entry.
   */
  @SuppressWarnings("unchecked")
  public static void generateStructureIndex(PrintWriter pw) throws IOException {
    final File file = getFile();
    final List<StrDef> allStrDefs = new ArrayList<>();
    final TomlMapper mapper = new TomlMapper();
    try (MappingIterator<Object> it =
        mapper.readerForMapOf(Object.class).readValues(file)) {
      while (it.hasNextValue()) {
        final Map<String, Object> row = (Map<String, Object>) it.nextValue();
        final Object structuresObj = row.get("structures");
        if (structuresObj != null) {
          allStrDefs.addAll(
              transformEager(
                  (List<Map<String, Object>>) structuresObj, StrDef::create));
        }
      }
    }
    pw.printf("%n");
    final Tabulator tabulator = new Tabulator(pw, -1, -1);
    tabulator.header("Structure", "Description");
    for (StrDef strDef : allStrDefs) {
      final String link =
          "[" + strDef.name + "](lib/" + toKebab(strDef.name) + ".md)";
      tabulator.row(link, munge(strDef.description));
    }
    pw.printf("%n");
  }

  /**
   * Converts a structure name in PascalCase to kebab-case.
   *
   * <p>Examples: {@code "IEEEReal"} &rarr; {@code "ieee-real"}, {@code
   * "ListPair"} &rarr; {@code "list-pair"}, {@code "StringCvt"} &rarr; {@code
   * "string-cvt"}.
   */
  public static String toKebab(String name) {
    return name.replaceAll("([A-Z]+)([A-Z][a-z])", "$1-$2")
        .replaceAll("([a-z])([A-Z])", "$1-$2")
        .toLowerCase();
  }

  /**
   * Reads the {@code functions.toml} file and generates the body of a
   * per-structure page at {@code docs/lib/{structure}.md}.
   */
  public static void generateStructureDoc(String structure, PrintWriter pw)
      throws IOException {
    new StructureDocGenerator(structure, pw).generate();
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
    final String specified;

    FnDef(
        String structure,
        String name,
        String type,
        String prototype,
        String description,
        String extra,
        boolean implemented,
        int ordinal,
        String specified) {
      this.structure = requireNonNull(structure, "structure");
      this.name = requireNonNull(name, "name");
      requireNonNull(type, "type");
      checkArgument(
          !type.isEmpty(), "type is empty for %s.%s", structure, name);
      this.type = type;
      this.prototype = requireNonNull(prototype, "prototype");
      this.description = requireNonNull(description, "description");
      this.extra = extra;
      this.implemented = implemented;
      this.ordinal = ordinal;
      this.specified = requireNonNull(specified, "specified");
    }

    String qualifiedName() {
      return structure + '.' + name;
    }

    /** Returns the name, stripping any disambiguation qualifier. */
    String canonicalName() {
      int comma = name.indexOf(", ");
      return comma >= 0 ? name.substring(0, comma) : name;
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
          map.containsKey("ordinal") ? (Integer) map.get("ordinal") : -1,
          map.containsKey("specified")
              ? (String) map.get("specified")
              : "basis");
    }
  }

  /** Structure definition (from {@code [[structures]]} in functions.toml). */
  private static class StrDef {
    final String name;
    final String description;
    final String overview;
    final String specified;

    StrDef(String name, String description, String overview, String specified) {
      this.name = requireNonNull(name, "name");
      this.description = requireNonNull(description, "description");
      this.overview = requireNonNull(overview, "overview");
      this.specified = requireNonNull(specified, "specified");
    }

    static StrDef create(Map<String, Object> map) {
      return new StrDef(
          (String) map.get("name"),
          (String) map.get("description"),
          (String) map.get("overview"),
          map.containsKey("specified")
              ? (String) map.get("specified")
              : "basis");
    }
  }

  /** Type definition (from {@code [[types]]} in functions.toml). */
  private static class TyDef {
    final String structure;
    final String name;
    final String type;
    final String description;
    final boolean implemented;

    TyDef(
        String structure,
        String name,
        String type,
        String description,
        boolean implemented) {
      this.structure = requireNonNull(structure, "structure");
      this.name = requireNonNull(name, "name");
      this.type = requireNonNull(type, "type");
      this.description = requireNonNull(description, "description");
      this.implemented = implemented;
    }

    String qualifiedName() {
      return structure + '.' + name;
    }

    static TyDef create(Map<String, Object> map) {
      return new TyDef(
          (String) map.get("structure"),
          (String) map.get("name"),
          (String) map.get("type"),
          (String) map.get("description"),
          first((Boolean) map.get("implemented"), true));
    }
  }

  /** Exception definition (from {@code [[exceptions]]} in functions.toml). */
  private static class ExnDef {
    final String structure;
    final String name;
    final @Nullable String type;
    final String description;
    final boolean implemented;
    final int ordinal;

    ExnDef(
        String structure,
        String name,
        @Nullable String type,
        String description,
        boolean implemented,
        int ordinal) {
      this.structure = requireNonNull(structure, "structure");
      this.name = requireNonNull(name, "name");
      this.type = type;
      this.description = requireNonNull(description, "description");
      this.implemented = implemented;
      this.ordinal = ordinal;
    }

    String qualifiedName() {
      return structure + '.' + name;
    }

    static ExnDef create(Map<String, Object> map) {
      return new ExnDef(
          (String) map.get("structure"),
          (String) map.get("name"),
          (String) map.get("type"),
          (String) map.get("description"),
          first((Boolean) map.get("implemented"), true),
          map.containsKey("ordinal") ? (Integer) map.get("ordinal") : -1);
    }
  }

  /** Generates the body of a per-structure Markdown page. */
  private static class StructureDocGenerator {
    final String structure;
    final PrintWriter pw;
    final StrDef strDef;
    final List<TyDef> tyDefs;
    final List<ExnDef> exnDefs;
    final List<FnDef> basisFns;
    final List<FnDef> morelFns;
    final List<FnDef> allFns;
    final Map<FnDef, String> fnAnchors;

    @SuppressWarnings("unchecked")
    StructureDocGenerator(String structure, PrintWriter pw) throws IOException {
      this.structure = structure;
      this.pw = pw;
      final File file = getFile();
      StrDef foundStrDef = null;
      final List<TyDef> tyList = new ArrayList<>();
      final List<ExnDef> exnList = new ArrayList<>();
      final List<FnDef> fnList = new ArrayList<>();
      final TomlMapper mapper = new TomlMapper();
      try (MappingIterator<Object> it =
          mapper.readerForMapOf(Object.class).readValues(file)) {
        while (it.hasNextValue()) {
          final Map<String, Object> row = (Map<String, Object>) it.nextValue();
          final Object structuresObj = row.get("structures");
          if (structuresObj != null) {
            for (StrDef s :
                transformEager(
                    (List<Map<String, Object>>) structuresObj,
                    StrDef::create)) {
              if (s.name.equals(structure)) {
                foundStrDef = s;
              }
            }
          }
          final Object typesObj = row.get("types");
          if (typesObj != null) {
            for (TyDef t :
                transformEager(
                    (List<Map<String, Object>>) typesObj, TyDef::create)) {
              if (t.structure.equals(structure)) {
                tyList.add(t);
              }
            }
          }
          final Object exceptionsObj = row.get("exceptions");
          if (exceptionsObj != null) {
            for (ExnDef e :
                transformEager(
                    (List<Map<String, Object>>) exceptionsObj,
                    ExnDef::create)) {
              if (e.structure.equals(structure)) {
                exnList.add(e);
              }
            }
          }
          for (FnDef fn :
              transformEager(
                  (List<Map<String, Object>>) row.get("functions"),
                  FnDef::create)) {
            if (fn.structure.equals(structure)) {
              fnList.add(fn);
            }
          }
        }
      }
      this.strDef =
          requireNonNull(foundStrDef, "structure not found: " + structure);
      this.tyDefs = tyList;
      this.exnDefs = exnList;
      final Comparator<FnDef> byOrdinal =
          Comparator.comparingInt(
                  (FnDef f) -> f.ordinal < 0 ? Integer.MAX_VALUE : f.ordinal)
              .thenComparing(f -> f.name);
      this.basisFns = new ArrayList<>();
      this.morelFns = new ArrayList<>();
      for (FnDef fn : fnList) {
        if ("morel".equals(fn.specified)) {
          morelFns.add(fn);
        } else {
          basisFns.add(fn);
        }
      }
      basisFns.sort(byOrdinal);
      morelFns.sort(byOrdinal);
      this.allFns = new ArrayList<>();
      allFns.addAll(basisFns);
      allFns.addAll(morelFns);
      this.fnAnchors = buildFnAnchors(allFns);
    }

    void generate() {
      pw.println(strDef.overview.trim());
      pw.println();
      if ("basis".equals(strDef.specified)) {
        pw.format(
            Locale.ROOT,
            "*Specified by the [Standard ML Basis Library]"
                + "(https://smlfamily.github.io/Basis/%s.html).*%n",
            toKebab(strDef.name));
        pw.println();
      }
      pw.println("## Synopsis");
      pw.println();
      synopsis();
      pw.println();
      for (TyDef ty : tyDefs) {
        typeSection(ty);
      }
      for (ExnDef exn : exnDefs) {
        exnSection(exn);
      }
      for (FnDef fn : allFns) {
        fnSection(fn);
      }
    }

    void synopsis() {
      pw.println("<pre>");
      for (TyDef ty : tyDefs) {
        pw.println(synopsisTypeLine(ty));
      }
      if (!tyDefs.isEmpty() && (!exnDefs.isEmpty() || !allFns.isEmpty())) {
        pw.println();
      }
      for (ExnDef exn : exnDefs) {
        pw.println(synopsisExnLine(exn));
      }
      if (!exnDefs.isEmpty() && !allFns.isEmpty()) {
        pw.println();
      }
      if ("morel".equals(strDef.specified)) {
        for (FnDef fn : allFns) {
          pw.println(synopsisFnLine(fn));
        }
      } else {
        for (FnDef fn : basisFns) {
          pw.println(synopsisFnLine(fn));
        }
        if (!morelFns.isEmpty()) {
          pw.println("(* Morel extensions *)");
          for (FnDef fn : morelFns) {
            pw.println(synopsisFnLine(fn));
          }
        }
      }
      pw.println("</pre>");
    }

    String synopsisTypeLine(TyDef ty) {
      final String link =
          format(
              Locale.ROOT,
              "<a id='%s' href=\"#%s-impl\">%s</a>",
              ty.name,
              ty.name,
              ty.name);
      // Link the declared name (the part before '=') leaving the rest intact.
      final int eqIdx = ty.type.indexOf('=');
      final String def = eqIdx >= 0 ? ty.type.substring(0, eqIdx) : ty.type;
      final String rest = eqIdx >= 0 ? ty.type.substring(eqIdx) : "";
      final int nameIdx = def.lastIndexOf(ty.name);
      if (nameIdx < 0) {
        return ty.type;
      }
      return def.substring(0, nameIdx)
          + link
          + def.substring(nameIdx + ty.name.length())
          + rest;
    }

    String synopsisExnLine(ExnDef exn) {
      final String s =
          format(
              Locale.ROOT,
              "exception <a id='%s' href=\"#%s-impl\">%s</a>",
              exn.name,
              exn.name,
              exn.name);
      return exn.type != null ? s + " of " + exn.type : s;
    }

    String synopsisFnLine(FnDef fn) {
      final String anchor = fnAnchors.get(fn);
      return format(
          Locale.ROOT,
          "val <a id='%s' href=\"#%s-impl\">%s</a> : %s",
          anchor,
          anchor,
          fn.canonicalName(),
          toSml(fn.type));
    }

    void typeSection(TyDef ty) {
      pw.format(Locale.ROOT, "<a id=\"%s-impl\"></a>%n", ty.name);
      final String keyword =
          ty.type.startsWith("datatype ")
              ? "datatype"
              : ty.type.startsWith("eqtype ") ? "eqtype" : "type";
      final String rest = ty.type.substring(keyword.length());
      pw.format(
          Locale.ROOT,
          "<h3><code><strong>%s</strong>%s</code></h3>%n",
          keyword,
          rest);
      pw.println();
      pw.println(processDesc(ty.description));
      pw.println();
    }

    void exnSection(ExnDef exn) {
      pw.format(Locale.ROOT, "<a id=\"%s-impl\"></a>%n", exn.name);
      pw.format(
          Locale.ROOT,
          "<h3><code><strong>exception</strong> %s</code></h3>%n",
          exn.name);
      pw.println();
      pw.println(processDesc(exn.description));
      pw.println();
    }

    void fnSection(FnDef fn) {
      pw.format(Locale.ROOT, "<a id=\"%s-impl\"></a>%n", fnAnchors.get(fn));
      pw.format(Locale.ROOT, "<h3><code>%s</code></h3>%n", fn.canonicalName());
      pw.println();
      if (fn.extra != null) {
        pw.format(
            Locale.ROOT,
            "`%s` %s %s%n",
            fn.prototype,
            processDesc(fn.description),
            fn.extra.trim());
      } else {
        pw.format(
            Locale.ROOT,
            "`%s` %s%n",
            fn.prototype,
            processDesc(fn.description));
      }
      if (!fn.implemented) {
        pw.println();
        pw.println("*Not yet implemented.*");
      }
      pw.println();
    }

    static String processDesc(String desc) {
      return desc.trim()
          .replace(
              "\n" //
                  + "\n" //
                  + "<p>",
              "\n" //
                  + "\n")
          .replace(
              "<p>",
              "\n" //
                  + "\n")
          .replace(
              "\n" //
                  + "\n" //
                  + "\n",
              "\n" //
                  + "\n");
    }

    static String toSml(String type) {
      return type.replace("α", "'a")
          .replace("β", "'b")
          .replace("γ", "'c")
          .replace("→", "->")
          .replace("≤", "<=")
          .replace("≥", ">=");
    }

    static Map<FnDef, String> buildFnAnchors(List<FnDef> fns) {
      // First pass: collect anchor names claimed by operator functions.
      final Set<String> used = new HashSet<>();
      for (FnDef fn : fns) {
        final String op = operatorAnchor(fn.canonicalName());
        if (op != null) {
          used.add(op);
        }
      }
      // Second pass: assign anchors, using name-fn if name is already taken.
      final Map<FnDef, String> result = new LinkedHashMap<>();
      for (FnDef fn : fns) {
        final String name = fn.canonicalName();
        String anchor = operatorAnchor(name);
        if (anchor == null) {
          anchor = name;
          if (!used.add(anchor)) {
            anchor = name + "-fn";
            used.add(anchor);
          }
        }
        result.put(fn, anchor);
      }
      return result;
    }

    static @Nullable String operatorAnchor(String name) {
      switch (name) {
        case "@":
          return "at";
        case "::":
          return "cons";
        case ":=":
          return "assign";
        case "!":
          return "deref";
        default:
          return null;
      }
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
