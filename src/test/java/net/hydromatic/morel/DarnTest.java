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
    assertThat(attrs.silent, is(false));
    assertThat(attrs.skip, is(false));
    assertThat(attrs.fail, is(false));
    assertThat(attrs.env, is("default"));
  }

  @Test
  void testParseAttrsSilent() {
    Darn.Attrs attrs = Darn.parseAttrs("<!-- morel silent");
    assertThat(attrs.silent, is(true));
    assertThat(attrs.skip, is(false));
  }

  @Test
  void testParseAttrsSkip() {
    Darn.Attrs attrs = Darn.parseAttrs("<!-- morel skip");
    assertThat(attrs.silent, is(false));
    assertThat(attrs.skip, is(true));
  }

  @Test
  void testParseAttrsFail() {
    Darn.Attrs attrs = Darn.parseAttrs("<!-- morel fail");
    assertThat(attrs.fail, is(true));
  }

  @Test
  void testParseAttrsEnv() {
    Darn.Attrs attrs = Darn.parseAttrs("<!-- morel env=mykernel");
    assertThat(attrs.env, is("mykernel"));
  }

  @Test
  void testParseAttrsMultiple() {
    Darn.Attrs attrs = Darn.parseAttrs("<!-- morel silent env=foo");
    assertThat(attrs.silent, is(true));
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
    assertThat(html.get(0), is("<div class=\"morel\">"));
    // 1 -> mi, + -> o, 2 -> mi, ; -> p
    assertThat(
        html.get(1),
        is(
            "<pre class=\"morel-input\"><code>"
                + "<span class=\"mi\">1</span>"
                + " <span class=\"o\">+</span>"
                + " <span class=\"mi\">2</span>"
                + "<span class=\"p\">;</span>"
                + "</code></pre>"));
    assertThat(html.get(html.size() - 1), is("</div>"));
  }

  @Test
  void testGenerateHtmlLinesInputAndOutput() {
    Darn.Segment seg =
        new Darn.Segment(
            ImmutableList.of("1 + 2;"), ImmutableList.of("val it = 3 : int"));
    List<String> html = Darn.generateHtmlLines(ImmutableList.of(seg));
    assertThat(html.get(0), is("<div class=\"morel\">"));
    // Output line wrapped in <span class="c"> (comment style, like after.sh)
    assertThat(
        html.get(2),
        is(
            "<pre class=\"morel-output\"><code>"
                + "<span class=\"c\">val it = 3 : int</span>"
                + "</code></pre>"));
    assertThat(html.get(3), is("</div>"));
  }

  @Test
  void testGenerateHtmlLinesKeywords() {
    Darn.Segment seg =
        new Darn.Segment(ImmutableList.of("fun f x = x;"), ImmutableList.of());
    List<String> html = Darn.generateHtmlLines(ImmutableList.of(seg));
    // "fun" -> kr, "f" -> nf (after fun), "x" -> n, "=" -> p, "x" -> n,
    // ";" -> p
    final String expected =
        "<pre class=\"morel-input\"><code>"
            + "<span class=\"kr\">fun</span>"
            + " <span class=\"nf\">f</span>"
            + " <span class=\"n\">x</span>"
            + " <span class=\"p\">=</span>"
            + " <span class=\"n\">x</span>"
            + "<span class=\"p\">;</span>"
            + "</code></pre>";
    assertThat(html.get(1), is(expected));
  }

  @Test
  void testHighlightOutputWrapsInCommentSpan() {
    // Output is wrapped per-line in <span class="c">, matching after.sh style.
    String highlighted = MorelHighlighter.highlightOutput("'a list -> int");
    assertThat(highlighted, is("<span class=\"c\">'a list -&gt; int</span>"));
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
            "<span class=\"c\">line1</span>\n" //
                + "<span class=\"c\">line2</span>"));
  }

  @Test
  void testGenerateHtmlLinesHtmlEscape() {
    String highlighted =
        MorelHighlighter.highlightInput("val x = a < b andalso c > d;");
    // < and > must be escaped; "andalso" must use kr span
    assertThat(highlighted.contains("&lt;"), is(true));
    assertThat(highlighted.contains("&gt;"), is(true));
    assertThat(
        highlighted.contains("<span class=\"kr\">andalso</span>"), is(true));
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
        result.lines.stream().anyMatch(l -> l.equals("<div class=\"morel\">")),
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
            .anyMatch(l -> l.contains("<span class=\"kr\">fun</span>")),
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
    assertThat(
        result.lines.stream().anyMatch(l -> l.equals("<div class=\"morel\">")),
        is(false));
  }
}

// End DarnTest.java
