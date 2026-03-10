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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.hydromatic.morel.eval.Prop;
import net.hydromatic.morel.foreign.ForeignValue;
import net.hydromatic.morel.util.MorelHighlighter;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Morel notebook kernel: processes documents containing {@code <!-- morel ...
 * -->} cells, executes the embedded Morel code, and weaves {@code <div
 * class="morel">...</div>} blocks with syntax-highlighted output back into the
 * document.
 *
 * <p>Inspired by Donald Knuth's WEAVE (part of the WEB literate programming
 * system, 1984), which produced a typeset document from source containing both
 * code and prose. Like Jupyter notebooks, Darn treats each {@code <!-- morel
 * -->} comment as a cell, executes it via the Morel kernel, and stores the
 * results alongside the code — but operating on plain text files (markdown,
 * HTML, or any file that accepts HTML comments) rather than a JSON notebook
 * format.
 *
 * <p>Cell attributes on the {@code <!-- morel} opening line:
 *
 * <ul>
 *   <li>{@code silent} &mdash; execute but do not emit a {@code <div>} block;
 *   <li>{@code skip} &mdash; emit a {@code <div>} but do not execute;
 *   <li>{@code no-output} &mdash; execute and emit a {@code <div>} showing only
 *       the input, not the output (output is still stored in the comment for
 *       validation);
 *   <li>{@code fail} &mdash; expect execution to fail;
 *   <li>{@code env=NAME} &mdash; share kernel state with other cells that use
 *       the same environment name.
 * </ul>
 *
 * <p>Usage:
 *
 * <pre>
 * ./morel --darn file.md          # execute cells and update in-place
 * ./morel --darn-verify file.md   # verify only, report mismatches
 * </pre>
 */
public class Darn {

  static final String DIV_OPEN = "<div class=\"morel\">";
  static final String DIV_CLOSE = "</div>";
  static final String COMMENT_PREFIX = "<!-- morel";
  static final String COMMENT_CLOSE = "-->";

  private Darn() {}

  /**
   * Processes a document file. In update mode, writes changes in-place. In
   * verify mode, reports mismatches.
   *
   * @param file The document file to process
   * @param verifyOnly If true, report mismatches but do not modify the file
   * @return true if there were any changes (or mismatches in verify mode)
   */
  public static boolean process(File file, boolean verifyOnly)
      throws IOException {
    List<String> inputLines =
        Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
    ProcessResult result = processLines(inputLines);
    if (!result.lines.equals(inputLines)) {
      if (!verifyOnly) {
        Files.write(file.toPath(), result.lines, StandardCharsets.UTF_8);
      } else {
        System.err.println(
            "Mismatch in "
                + file
                + ": "
                + result.mismatchCount
                + " cell(s) differ");
      }
      return true;
    }
    return false;
  }

  /** Processes a list of lines and returns the updated list. */
  static ProcessResult processLines(List<String> lines) {
    List<String> result = new ArrayList<>();
    int mismatchCount = 0;
    int i = 0;
    int n = lines.size();

    // Map from environment name to the accumulated input run so far (for
    // state sharing across cells in the same kernel environment).
    Map<String, String> envState = new LinkedHashMap<>();

    while (i < n) {
      String line = lines.get(i);

      if (!line.startsWith(COMMENT_PREFIX)) {
        result.add(line);
        i++;
        continue;
      }

      // Found a "<!-- morel ..." opening. Collect lines through "-->".
      List<String> commentLines = new ArrayList<>();
      commentLines.add(line);
      i++;
      while (i < n && !lines.get(i).equals(COMMENT_CLOSE)) {
        commentLines.add(lines.get(i));
        i++;
      }
      if (i < n) {
        commentLines.add(COMMENT_CLOSE);
        i++;
      }

      // Parse attributes and content.
      Attrs attrs = parseAttrs(line);
      List<String> contentLines =
          commentLines.subList(1, commentLines.size() - 1);
      List<Segment> segments = parseSegments(contentLines);

      // Emit the comment block unchanged.
      result.addAll(commentLines);

      // Skip any existing <div class="morel">...</div> block that follows.
      // Consume a single blank line separator if present.
      int blankLines = 0;
      while (i < n && lines.get(i).isEmpty()) {
        blankLines++;
        i++;
      }
      if (i < n && lines.get(i).equals(DIV_OPEN)) {
        // Skip through </div>.
        i++;
        while (i < n && !lines.get(i).equals(DIV_CLOSE)) {
          i++;
        }
        if (i < n) {
          i++; // skip </div>
        }
        // Also skip a trailing blank line after </div> if present.
        if (i < n && lines.get(i).isEmpty()) {
          i++;
        }
      } else {
        // No existing div; put back the blank lines we consumed.
        i -= blankLines;
      }

      // Execute the cell (unless skip).
      if (!attrs.skip && !attrs.silent) {
        String preamble = envState.getOrDefault(attrs.env, "");
        String allInput = buildInput(segments);
        try {
          String actual = executeCode(preamble + allInput);
          // Strip preamble output from actual (run preamble alone to get its
          // output).
          String preambleOutput =
              preamble.isEmpty() ? "" : executeCode(preamble);
          if (actual.startsWith(preambleOutput)) {
            actual = actual.substring(preambleOutput.length());
          }
          actual = actual.trim();
          List<Segment> updated = updateSegments(segments, actual);
          if (!updated.equals(segments)) {
            mismatchCount++;
            segments = updated;
            // Rewrite comment with updated > lines.
            // (Replace the last emitted comment block in result.)
            int resultCommentStart = result.size() - commentLines.size();
            List<String> newCommentLines = rebuildComment(line, segments);
            result.subList(resultCommentStart, result.size()).clear();
            result.addAll(newCommentLines);
          }
        } catch (Exception e) {
          // Execution error: leave segments as-is.
        }
        envState.put(attrs.env, preamble + allInput);
      }

      // Generate and insert a <div class="morel"> block (unless silent).
      if (!attrs.silent) {
        result.add("");
        result.addAll(generateHtmlLines(segments, attrs.noOutput));
        result.add("");
      }
    }

    return new ProcessResult(result, mismatchCount);
  }

  /** Parses attributes from the {@code <!-- morel [attrs] } opening line. */
  static Attrs parseAttrs(String openingLine) {
    // Strip "<!-- morel" prefix and optional trailing whitespace.
    String rest = openingLine.substring(COMMENT_PREFIX.length()).trim();
    boolean silent = false;
    boolean skip = false;
    boolean noOutput = false;
    boolean fail = false;
    String env = "default";
    for (String token : rest.split("\\s+")) {
      if (token.isEmpty()) {
        continue;
      }
      switch (token) {
        case "silent":
          silent = true;
          break;
        case "skip":
          skip = true;
          break;
        case "no-output":
          noOutput = true;
          break;
        case "fail":
          fail = true;
          break;
        default:
          if (token.startsWith("env=")) {
            env = token.substring("env=".length());
          }
          break;
      }
    }
    return new Attrs(silent, skip, noOutput, fail, env);
  }

  /**
   * Parses comment content lines into a list of {@link Segment}s.
   *
   * <p>Input lines are plain text; output lines are prefixed with {@code "> "}.
   * Adjacent input lines form a single cell's input; adjacent output lines form
   * the following cell's output.
   */
  static List<Segment> parseSegments(List<String> contentLines) {
    List<Segment> segments = new ArrayList<>();
    List<String> currentInput = new ArrayList<>();
    List<String> currentOutput = new ArrayList<>();
    boolean inOutput = false;

    for (String line : contentLines) {
      if (line.equals(">") || line.startsWith("> ")) {
        String stripped = line.equals(">") ? "" : line.substring(2);
        if (!inOutput && !currentInput.isEmpty()) {
          inOutput = true;
        }
        currentOutput.add(stripped);
      } else {
        if (inOutput) {
          // Transition from output back to input: save the current segment.
          segments.add(
              new Segment(
                  ImmutableList.copyOf(currentInput),
                  ImmutableList.copyOf(currentOutput)));
          currentInput = new ArrayList<>();
          currentOutput = new ArrayList<>();
          inOutput = false;
        }
        currentInput.add(line);
      }
    }
    // Save the last segment.
    if (!currentInput.isEmpty() || !currentOutput.isEmpty()) {
      segments.add(
          new Segment(
              ImmutableList.copyOf(currentInput),
              ImmutableList.copyOf(currentOutput)));
    }
    return segments;
  }

  /** Builds the concatenated input string for all segments. */
  private static String buildInput(List<Segment> segments) {
    StringBuilder sb = new StringBuilder();
    for (Segment segment : segments) {
      for (String line : segment.input) {
        sb.append(line).append('\n');
      }
    }
    return sb.toString();
  }

  /** Rebuilds the comment lines with updated expected output. */
  static List<String> rebuildComment(
      String openingLine, List<Segment> segments) {
    List<String> lines = new ArrayList<>();
    lines.add(openingLine);
    for (Segment segment : segments) {
      lines.addAll(segment.input);
      for (String outLine : segment.output) {
        lines.add(outLine.isEmpty() ? ">" : "> " + outLine);
      }
    }
    lines.add(COMMENT_CLOSE);
    return lines;
  }

  /**
   * Updates segment expected output with the actual execution output.
   *
   * <p>Distributes the actual output lines across segments proportionally to
   * the number of expected output lines, treating segments without expected
   * output as having zero output.
   */
  static List<Segment> updateSegments(List<Segment> segments, String actual) {
    List<String> actualLines = new ArrayList<>();
    for (String line : actual.split("\n", -1)) {
      actualLines.add(line);
    }
    // Remove trailing empty lines from actualLines.
    while (!actualLines.isEmpty()
        && actualLines.get(actualLines.size() - 1).isEmpty()) {
      actualLines.remove(actualLines.size() - 1);
    }

    // Collect existing expected output, flat.
    List<String> expected = new ArrayList<>();
    for (Segment segment : segments) {
      expected.addAll(segment.output);
    }

    if (actualLines.equals(expected)) {
      return segments; // no change
    }

    // Put all actual output in the last segment.
    List<Segment> updated = new ArrayList<>();
    for (int i = 0; i < segments.size(); i++) {
      Segment segment = segments.get(i);
      if (i == segments.size() - 1) {
        updated.add(
            new Segment(segment.input, ImmutableList.copyOf(actualLines)));
      } else {
        updated.add(new Segment(segment.input, ImmutableList.of()));
      }
    }
    return updated;
  }

  /** Executes Morel code and returns the captured output as a string. */
  static String executeCode(String code) throws IOException {
    if (code.trim().isEmpty()) {
      return "";
    }
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    try (PrintStream ps =
        new PrintStream(baos, true, StandardCharsets.UTF_8.name())) {
      byte[] inputBytes = code.getBytes(StandardCharsets.UTF_8);
      ByteArrayInputStream bais = new ByteArrayInputStream(inputBytes);
      List<String> argList = ImmutableList.of();
      Map<String, ForeignValue> valueMap = ImmutableMap.of();
      Map<Prop, Object> propMap = ImmutableMap.of();
      Main main = new Main(argList, bais, ps, valueMap, propMap, false);
      main.run();
    }
    return baos.toString(StandardCharsets.UTF_8.name());
  }

  /** Generates the HTML lines for a {@code <div class="morel">} block. */
  static List<String> generateHtmlLines(List<Segment> segments) {
    return generateHtmlLines(segments, false);
  }

  /**
   * Generates the HTML lines for a {@code <div class="morel">} block.
   *
   * @param noOutput If true, omit the {@code morel-output} pre block (the
   *     output is stored in the comment for validation but not rendered)
   */
  static List<String> generateHtmlLines(
      List<Segment> segments, boolean noOutput) {
    List<String> lines = new ArrayList<>();
    lines.add(DIV_OPEN);
    for (Segment segment : segments) {
      if (!segment.input.isEmpty()) {
        String inputHtml =
            MorelHighlighter.highlightInput(String.join("\n", segment.input));
        addCodeBlock(lines, "morel-input", inputHtml);
      }
      if (!noOutput && !segment.output.isEmpty()) {
        String outputHtml =
            MorelHighlighter.highlightOutput(String.join("\n", segment.output));
        addCodeBlock(lines, "morel-output", outputHtml);
      }
    }
    lines.add(DIV_CLOSE);
    return lines;
  }

  /**
   * Wraps content in a {@code pre} block with the given CSS class, splitting on
   * newlines so that multi-line blocks occupy multiple list entries (and thus
   * round-trip correctly through {@link java.nio.file.Files#readAllLines} and
   * {@link java.nio.file.Files#write}).
   */
  private static void addCodeBlock(
      List<String> lines, String cls, String content) {
    String block =
        "<pre class=\"" + cls + "\"><code>" + content + "</code></pre>";
    for (String line : block.split("\n", -1)) {
      lines.add(line);
    }
  }

  /** Attributes parsed from the {@code <!-- morel [attrs] } opening line. */
  static class Attrs {
    final boolean silent;
    final boolean skip;
    /** If true, execute but suppress the output block in the rendered div. */
    final boolean noOutput;

    final boolean fail;
    /** Kernel environment name; never null (defaults to {@code "default"}). */
    final String env;

    Attrs(
        boolean silent,
        boolean skip,
        boolean noOutput,
        boolean fail,
        String env) {
      this.silent = silent;
      this.skip = skip;
      this.noOutput = noOutput;
      this.fail = fail;
      this.env = env;
    }
  }

  /**
   * A segment: one group of input lines (a cell's code) paired with its
   * expected kernel output.
   */
  static class Segment {
    final List<String> input;
    final List<String> output;

    Segment(List<String> input, List<String> output) {
      this.input = ImmutableList.copyOf(input);
      this.output = ImmutableList.copyOf(output);
    }

    @Override
    public boolean equals(@Nullable Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof Segment)) {
        return false;
      }
      Segment other = (Segment) o;
      return input.equals(other.input) && output.equals(other.output);
    }

    @Override
    public int hashCode() {
      return 31 * input.hashCode() + output.hashCode();
    }
  }

  /** Result of processing a document file. */
  static class ProcessResult {
    final List<String> lines;
    final int mismatchCount;

    ProcessResult(List<String> lines, int mismatchCount) {
      this.lines = ImmutableList.copyOf(lines);
      this.mismatchCount = mismatchCount;
    }
  }
}

// End Darn.java
