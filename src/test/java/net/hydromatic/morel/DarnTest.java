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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.core.Is.is;

import com.google.common.collect.ImmutableList;
import java.io.File;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import net.hydromatic.morel.util.MorelHighlighter;
import org.junit.jupiter.api.Test;

/** Tests for {@link Darn}, the Morel notebook kernel. */
public class DarnTest {

  // -----------------------------------------------------------------------
  // parseAttrs

  @Test
  void testParseAttrsDefault() {
    Darn.Attrs attrs = Darn.parseAttrs("<!-- morel");
    assertThat(attrs.command, is(Darn.Command.RUN));
    assertThat(attrs.fail, is(false));
    assertThat(attrs.env, is("default"));
  }

  @Test
  void testParseAttrsRun() {
    Darn.Attrs attrs = Darn.parseAttrs("<!-- morel run");
    assertThat(attrs.command, is(Darn.Command.RUN));
  }

  @Test
  void testParseAttrsSilent() {
    Darn.Attrs attrs = Darn.parseAttrs("<!-- morel silent");
    assertThat(attrs.command, is(Darn.Command.SILENT));
  }

  @Test
  void testParseAttrsSkip() {
    Darn.Attrs attrs = Darn.parseAttrs("<!-- morel skip");
    assertThat(attrs.command, is(Darn.Command.SKIP));
  }

  @Test
  void testParseAttrsFail() {
    Darn.Attrs attrs = Darn.parseAttrs("<!-- morel fail");
    assertThat(attrs.fail, is(true));
  }

  @Test
  void testParseAttrsNoFail() {
    Darn.Attrs attrs = Darn.parseAttrs("<!-- morel fail no-fail");
    assertThat(attrs.fail, is(false));
  }

  @Test
  void testParseAttrsNoOutput() {
    Darn.Attrs attrs = Darn.parseAttrs("<!-- morel no-output");
    assertThat(attrs.noOutput, is(true));
    assertThat(attrs.command, is(Darn.Command.RUN));
  }

  @Test
  void testParseAttrsOutput() {
    Darn.Attrs attrs = Darn.parseAttrs("<!-- morel no-output output");
    assertThat(attrs.noOutput, is(false));
  }

  @Test
  void testParseAttrsEnv() {
    Darn.Attrs attrs = Darn.parseAttrs("<!-- morel env=mykernel");
    assertThat(attrs.env, is("mykernel"));
  }

  @Test
  void testParseAttrsMultiple() {
    Darn.Attrs attrs = Darn.parseAttrs("<!-- morel silent env=foo");
    assertThat(attrs.command, is(Darn.Command.SILENT));
    assertThat(attrs.env, is("foo"));
  }

  // -----------------------------------------------------------------------
  // parseSegments

  @Test
  void testParseSegmentsSingleInputOutput() {
    List<String> lines = ImmutableList.of("1 + 2;", "> val it = 3 : int");
    List<Darn.Segment> segments = Darn.parseSegments(lines);
    assertThat(segments, hasSize(1));
    assertThat(segments.get(0).input, is(ImmutableList.of("1 + 2;")));
    assertThat(
        segments.get(0).output, is(ImmutableList.of("val it = 3 : int")));
  }

  @Test
  void testParseSegmentsInputOnly() {
    List<String> lines = ImmutableList.of("val x = 5;");
    List<Darn.Segment> segments = Darn.parseSegments(lines);
    assertThat(segments, hasSize(1));
    assertThat(segments.get(0).input, is(ImmutableList.of("val x = 5;")));
    assertThat(segments.get(0).output, is(ImmutableList.of()));
  }

  @Test
  void testParseSegmentsMultiLineInput() {
    List<String> lines =
        ImmutableList.of(
            "fun len [] = 0",
            "  | len (_ :: tl) = 1 + len tl;",
            "> val len = fn : 'a list -> int");
    List<Darn.Segment> segments = Darn.parseSegments(lines);
    assertThat(segments, hasSize(1));
    assertThat(segments.get(0).input, hasSize(2));
    assertThat(segments.get(0).output, hasSize(1));
  }

  @Test
  void testParseSegmentsMultiLineOutput() {
    List<String> lines =
        ImmutableList.of(
            "val x = 1;", "> val x = 1 : int", "> val y = 2 : int");
    List<Darn.Segment> segments = Darn.parseSegments(lines);
    assertThat(segments, hasSize(1));
    assertThat(segments.get(0).output, hasSize(2));
  }

  @Test
  void testParseSegmentsEmptyOutputLine() {
    // A bare ">" line becomes an empty output line.
    List<String> lines = ImmutableList.of("1;", ">");
    List<Darn.Segment> segments = Darn.parseSegments(lines);
    assertThat(segments, hasSize(1));
    assertThat(segments.get(0).output, is(ImmutableList.of("")));
  }

  // -----------------------------------------------------------------------
  // generateHtmlLines

  @Test
  void testGenerateHtmlLinesInputOnly() {
    Darn.Segment seg =
        new Darn.Segment(ImmutableList.of("1 + 2;"), ImmutableList.of());
    List<String> html = Darn.generateHtmlLines(ImmutableList.of(seg));
    assertThat(html.get(0), is("<div class=\"code-block\">"));
    // 1 -> num, + -> op, 2 -> num, ; -> plain
    assertThat(
        html.get(1),
        is(
            "<div class=\"code-input\">"
                + "<span class=\"num\">1</span>"
                + " <span class=\"op\">+</span>"
                + " <span class=\"num\">2</span>"
                + ";"
                + "</div>"));
    assertThat(html.get(html.size() - 1), is("</div>"));
  }

  @Test
  void testGenerateHtmlLinesInputAndOutput() {
    Darn.Segment seg =
        new Darn.Segment(
            ImmutableList.of("1 + 2;"), ImmutableList.of("val it = 3 : int"));
    List<String> html = Darn.generateHtmlLines(ImmutableList.of(seg));
    assertThat(html.get(0), is("<div class=\"code-block\">"));
    // Output line as plain HTML-escaped text
    assertThat(
        html.get(2),
        is("<div class=\"code-output\">" + "val it = 3 : int" + "</div>"));
    assertThat(html.get(3), is("</div>"));
  }

  @Test
  void testGenerateHtmlLinesKeywords() {
    Darn.Segment seg =
        new Darn.Segment(ImmutableList.of("fun f x = x;"), ImmutableList.of());
    List<String> html = Darn.generateHtmlLines(ImmutableList.of(seg));
    // "fun" -> kw, "f" -> plain, "x" -> plain, "=" -> plain, ";" -> plain
    final String expected =
        "<div class=\"code-input\">"
            + "<span class=\"kw\">fun</span>"
            + " f"
            + " x"
            + " ="
            + " x"
            + ";"
            + "</div>";
    assertThat(html.get(1), is(expected));
  }

  @Test
  void testHighlightOutputHtmlEscapes() {
    // Output is HTML-escaped but otherwise plain (no spans).
    String highlighted = MorelHighlighter.highlightOutput("'a list -> int");
    assertThat(highlighted, is("'a list -&gt; int"));
  }

  @Test
  void testHighlightOutputMultiLine() {
    String highlighted =
        MorelHighlighter.highlightOutput(
            "line1\n" //
                + "line2");
    assertThat(
        highlighted,
        is(
            "line1\n" //
                + "line2"));
  }

  @Test
  void testGenerateHtmlLinesNoOutput() {
    // no-output: div shows input block only; output pre is suppressed.
    Darn.Segment seg =
        new Darn.Segment(
            ImmutableList.of("val x = 5;"),
            ImmutableList.of("val x = 5 : int"));
    List<String> html = Darn.generateHtmlLines(ImmutableList.of(seg), true);
    assertThat(html.get(0), is("<div class=\"code-block\">"));
    assertThat(html.get(html.size() - 1), is("</div>"));
    // Input div block is present.
    assertThat(html.stream().anyMatch(l -> l.contains("code-input")), is(true));
    // Output div block is absent.
    assertThat(
        html.stream().anyMatch(l -> l.contains("code-output")), is(false));
  }

  @Test
  void testProcessLinesNoOutputCellNoOutputDiv() {
    // A no-output cell is executed, div is emitted, but output pre is absent.
    List<String> input =
        Arrays.asList(
            "<!-- morel no-output", "val x = 5;", "> val x = 5 : int", "-->");
    Darn.ProcessResult result = Darn.processLines(input);
    assertThat(result.mismatchCount, is(0));
    assertThat(
        result.lines.stream()
            .anyMatch(l -> l.equals("<div class=\"code-block\">")),
        is(true));
    assertThat(
        result.lines.stream().anyMatch(l -> l.contains("code-input")),
        is(true));
    assertThat(
        result.lines.stream().anyMatch(l -> l.contains("code-output")),
        is(false));
  }

  @Test
  void testGenerateHtmlLinesHtmlEscape() {
    String highlighted =
        MorelHighlighter.highlightInput("val x = a < b andalso c > d;");
    // < and > must be escaped; "andalso" must use kw span
    assertThat(highlighted.contains("&lt;"), is(true));
    assertThat(highlighted.contains("&gt;"), is(true));
    assertThat(
        highlighted.contains("<span class=\"kw\">andalso</span>"), is(true));
  }

  // -----------------------------------------------------------------------
  // updateSegments

  @Test
  void testUpdateSegmentsNoChange() {
    Darn.Segment seg =
        new Darn.Segment(
            ImmutableList.of("1 + 2;"), ImmutableList.of("val it = 3 : int"));
    List<Darn.Segment> segments = ImmutableList.of(seg);
    List<Darn.Segment> updated =
        Darn.updateSegments(segments, "val it = 3 : int");
    assertThat(updated, is(segments));
  }

  @Test
  void testUpdateSegmentsChanged() {
    Darn.Segment seg =
        new Darn.Segment(
            ImmutableList.of("1 + 2;"), ImmutableList.of("val it = 99 : int"));
    List<Darn.Segment> segments = ImmutableList.of(seg);
    List<Darn.Segment> updated =
        Darn.updateSegments(segments, "val it = 3 : int");
    assertThat(updated.get(0).output, is(ImmutableList.of("val it = 3 : int")));
  }

  // -----------------------------------------------------------------------
  // rebuildComment

  @Test
  void testRebuildComment() {
    Darn.Segment seg =
        new Darn.Segment(
            ImmutableList.of("1 + 2;"), ImmutableList.of("val it = 3 : int"));
    List<String> rebuilt =
        Darn.rebuildComment("<!-- morel", ImmutableList.of(seg));
    assertThat(rebuilt.get(0), is("<!-- morel"));
    assertThat(rebuilt.get(1), is("1 + 2;"));
    assertThat(rebuilt.get(2), is("> val it = 3 : int"));
    assertThat(rebuilt.get(3), is("-->"));
  }

  // -----------------------------------------------------------------------
  // probeLines

  @Test
  void testProbeLinesSkipOkNoOutput() {
    // A skip cell that executes with no output → suggest no-output.
    List<String> input = Arrays.asList("<!-- morel skip", "val x = 5;", "-->");
    List<Darn.ProbeResult> results = Darn.probeLines(input);
    assertThat(results, hasSize(1));
    assertThat(results.get(0).isOk(), is(true));
    assertThat(results.get(0).output, is("val x = 5 : int"));
    assertThat(results.get(0).lineNumber, is(1));
  }

  @Test
  void testProbeLinesSkipError() {
    // A skip cell with an unbound name: interpreter prints an error message
    // (no Java exception), captured as output. isOk() returns false because
    // the output does not match the "val ..." / "type ..." success pattern.
    List<String> input =
        Arrays.asList("<!-- morel skip", "unboundName;", "-->");
    List<Darn.ProbeResult> results = Darn.probeLines(input);
    assertThat(results, hasSize(1));
    assertThat(results.get(0).isOk(), is(false));
    assertThat(results.get(0).error, is((String) null)); // no Java exception
    assertThat(results.get(0).output.contains("unbound"), is(true));
  }

  @Test
  void testProbeLinesNonSkipBuildsEnv() {
    // A non-skip cell before a skip cell contributes to the environment, so
    // the skip cell can reference the definition.
    List<String> input =
        Arrays.asList(
            "<!-- morel",
            "val x = 42;",
            "> val x = 42 : int",
            "-->",
            "<!-- morel skip",
            "x + 1;",
            "-->");
    List<Darn.ProbeResult> results = Darn.probeLines(input);
    assertThat(results, hasSize(1));
    assertThat(results.get(0).isOk(), is(true));
    assertThat(results.get(0).output, is("val it = 43 : int"));
  }

  // -----------------------------------------------------------------------
  // processLines — idempotency test against the basic.md fixture

  @Test
  void testProcessLinesIdempotent() throws Exception {
    URL url = DarnTest.class.getResource("/darn/basic.md");
    assert url != null : "resource /darn/basic.md not found";
    File file = new File(url.toURI());
    List<String> original =
        Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
    Darn.ProcessResult result = Darn.processLines(original);
    assertThat(
        "processLines should produce no changes on an already-processed file",
        result.lines,
        is(original));
    assertThat(result.mismatchCount, is(0));
  }

  // -----------------------------------------------------------------------
  // processLines — cell execution smoke test

  @Test
  void testProcessLinesSimpleExpression() {
    List<String> input =
        Arrays.asList("<!-- morel", "1 + 2;", "> val it = 3 : int", "-->");
    Darn.ProcessResult result = Darn.processLines(input);
    assertThat(result.mismatchCount, is(0));
    // Output should contain the div block.
    assertThat(
        result.lines.stream()
            .anyMatch(l -> l.equals("<div class=\"code-block\">")),
        is(true));
  }

  @Test
  void testProcessLinesSkipCellNotExecuted() {
    // A skip cell is not executed but its div block is generated.
    List<String> input =
        Arrays.asList("<!-- morel skip", "fun f x = x;", "-->");
    Darn.ProcessResult result = Darn.processLines(input);
    assertThat(result.mismatchCount, is(0));
    assertThat(
        result.lines.stream()
            .anyMatch(l -> l.contains("<span class=\"kw\">fun</span>")),
        is(true));
  }

  @Test
  void testProcessLinesSilentCellNoDiv() {
    // A silent cell is executed but no div block is emitted.
    List<String> input =
        Arrays.asList(
            "<!-- morel silent",
            "val hidden = 99;",
            "> val hidden = 99 : int",
            "-->");
    Darn.ProcessResult result = Darn.processLines(input);
    assertThat(result.mismatchCount, is(0));
    assertThat(
        result.lines.stream()
            .anyMatch(l -> l.equals("<div class=\"code-block\">")),
        is(false));
  }

  @Test
  void testSilentCellBuildsEnvForSubsequentCell() {
    // A silent cell executes and its bindings are available to the next cell.
    List<String> input =
        Arrays.asList(
            "<!-- morel silent",
            "val x = 42;",
            "> val x = 42 : int",
            "-->",
            "<!-- morel",
            "x + 1;",
            "> val it = 43 : int",
            "-->");
    Darn.ProcessResult result = Darn.processLines(input);
    assertThat(result.mismatchCount, is(0));
    // Only one div — from the non-silent cell.
    assertThat(
        result.lines.stream()
            .filter(l -> l.equals("<div class=\"code-block\">"))
            .count(),
        is(1L));
  }
}

// End DarnTest.java
