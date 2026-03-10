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

import com.google.common.collect.ImmutableSet;
import java.util.Set;

/**
 * Syntax-highlights Morel source code as HTML, using Rouge-compatible CSS token
 * classes.
 *
 * <p>Token classes produced for input code:
 *
 * <ul>
 *   <li>{@code kr} &mdash; SML and Morel reserved words;
 *   <li>{@code n} &mdash; plain identifiers;
 *   <li>{@code nf} &mdash; function name (identifier after {@code fun});
 *   <li>{@code nv} &mdash; variable name (identifier after {@code val});
 *   <li>{@code nn} &mdash; structure name (identifier before {@code .});
 *   <li>{@code nd} &mdash; type variables ({@code 'a}, {@code 'alpha},
 *       &hellip;);
 *   <li>{@code mi} &mdash; integer literals;
 *   <li>{@code p} &mdash; punctuation ({@code ()[]{}=,;|.}), consecutive
 *       punctuation characters are grouped into one span;
 *   <li>{@code o} &mdash; operators ({@code ::}, {@code ->}, {@code :=}, {@code
 *       +}, {@code -}, {@code *}, {@code /}, {@code :});
 *   <li>{@code c} + {@code cm} &mdash; SML comments ({@code (*} &hellip; {@code
 *       *)});
 *   <li>{@code s2} &mdash; double-quoted string literals.
 * </ul>
 *
 * <p>For output lines (REPL responses), {@link #highlightOutput} wraps each
 * line in {@code <span class="c">} (comment style), matching the treatment
 * produced by Rouge plus the {@code after.sh} post-processor.
 *
 * <p>Morel-specific keywords (such as {@code from}, {@code yield}, {@code
 * elem}, {@code exists}) are given the {@code kr} class, just as {@code
 * after.sh} patches them on the live blog. Adding a new Morel keyword requires
 * only adding it to {@link #MOREL_KEYWORDS}.
 */
public class MorelHighlighter {

  /** SML reserved words. */
  private static final Set<String> SML_KEYWORDS =
      ImmutableSet.of(
          "abstype",
          "and",
          "andalso",
          "as",
          "case",
          "datatype",
          "do",
          "else",
          "end",
          "exception",
          "fn",
          "fun",
          "handle",
          "if",
          "in",
          "infix",
          "infixr",
          "let",
          "local",
          "nonfix",
          "of",
          "op",
          "open",
          "orelse",
          "raise",
          "rec",
          "sharing",
          "sig",
          "signature",
          "struct",
          "structure",
          "then",
          "type",
          "val",
          "where",
          "while",
          "with",
          "withtype");

  /**
   * Morel-specific keywords not in SML. These are also the keywords that {@code
   * after.sh} promotes from {@code <span class="n">} to {@code <span
   * class="kr">} on the live blog.
   */
  private static final Set<String> MOREL_KEYWORDS =
      ImmutableSet.of(
          "compute",
          "current",
          "desc",
          "distinct",
          "elem",
          "except",
          "exists",
          "forall",
          "from",
          "group",
          "implies",
          "inst",
          "intersect",
          "join",
          "not",
          "on",
          "order",
          "ordinal",
          "over",
          "require",
          "skip",
          "take",
          "unorder",
          "union",
          "yield");

  /** All keywords highlighted as {@code kr} in input code. */
  private static final Set<String> ALL_KEYWORDS;

  static {
    ImmutableSet.Builder<String> b = ImmutableSet.builder();
    b.addAll(SML_KEYWORDS);
    b.addAll(MOREL_KEYWORDS);
    ALL_KEYWORDS = b.build();
  }

  /**
   * Punctuation characters. Consecutive punctuation characters are grouped into
   * a single {@code <span class="p">} span, matching Rouge's behavior.
   */
  private static final String PUNCT_CHARS = "()[]{}=,;|.";

  private MorelHighlighter() {}

  /**
   * Highlights Morel input code using Rouge-compatible span classes.
   *
   * <p>Keywords become {@code kr}, identifiers {@code n}/{@code nf}/{@code nv}/
   * {@code nn}, type variables {@code nd}, integers {@code mi}, punctuation
   * {@code p}, operators {@code o}, comments {@code c}/{@code cm}, and string
   * literals {@code s2}.
   */
  public static String highlightInput(String code) {
    StringBuilder sb = new StringBuilder();
    highlightCode(code, sb, true);
    return sb.toString();
  }

  /**
   * Highlights Morel output text (REPL responses).
   *
   * <p>Each output line is wrapped in {@code <span class="c">} (comment style),
   * matching the live blog's treatment of output lines (originally {@code (*[>
   * ... ]*)}, kept gray by Rouge's comment CSS).
   */
  public static String highlightOutput(String text) {
    StringBuilder sb = new StringBuilder();
    String[] lines = text.split("\n", -1);
    for (int j = 0; j < lines.length; j++) {
      sb.append("<span class=\"c\">");
      appendEscaped(lines[j], 0, lines[j].length(), sb);
      sb.append("</span>");
      if (j < lines.length - 1) {
        sb.append('\n');
      }
    }
    return sb.toString();
  }

  private static void highlightCode(
      String s, StringBuilder out, boolean keywords) {
    int i = 0;
    int n = s.length();
    // Context for context-sensitive identifier classification.
    // Set to "fun" or "val" after those keywords; whitespace preserves it;
    // any other token clears it.
    String prevKeyword = null;

    while (i < n) {
      char c = s.charAt(i);

      if (c == '(' && i + 1 < n && s.charAt(i + 1) == '*') {
        // SML comment (* ... *): opening (* in "c", body+close in "cm".
        int end = scanComment(s, i, n);
        out.append("<span class=\"c\">(*</span>");
        if (i + 2 < end) {
          out.append("<span class=\"cm\">");
          appendEscaped(s, i + 2, end, out);
          out.append("</span>");
        }
        i = end;
        prevKeyword = null;

      } else if (c == '"') {
        // String literal
        int end = scanString(s, i, n);
        out.append("<span class=\"s2\">");
        appendEscaped(s, i, end, out);
        out.append("</span>");
        i = end;
        prevKeyword = null;

      } else if (c == '\''
          && i + 1 < n
          && Character.isLetter(s.charAt(i + 1))) {
        // Type variable: 'a, 'b, 'alpha, etc.
        int end = i + 1;
        while (end < n
            && (Character.isLetterOrDigit(s.charAt(end))
                || s.charAt(end) == '_'
                || s.charAt(end) == '\'')) {
          end++;
        }
        out.append("<span class=\"nd\">");
        appendEscaped(s, i, end, out);
        out.append("</span>");
        i = end;
        prevKeyword = null;

      } else if (Character.isLetter(c) || c == '_') {
        // Identifier or keyword
        int end = i + 1;
        while (end < n
            && (Character.isLetterOrDigit(s.charAt(end))
                || s.charAt(end) == '_'
                || s.charAt(end) == '\'')) {
          end++;
        }
        String word = s.substring(i, end);
        String cls;
        String nextPrev;
        if (keywords && ALL_KEYWORDS.contains(word)) {
          cls = "kr";
          nextPrev = word;
        } else if (!keywords && SML_KEYWORDS.contains(word)) {
          cls = "kr";
          nextPrev = word;
        } else if ("fun".equals(prevKeyword)) {
          cls = "nf";
          nextPrev = null;
        } else if ("val".equals(prevKeyword)) {
          cls = "nv";
          nextPrev = null;
        } else if (end < n && s.charAt(end) == '.') {
          // Identifier immediately followed by '.' is a structure name.
          cls = "nn";
          nextPrev = null;
        } else {
          cls = "n";
          nextPrev = null;
        }
        out.append("<span class=\"").append(cls).append("\">");
        out.append(word);
        out.append("</span>");
        prevKeyword = nextPrev;
        i = end;

      } else if (Character.isDigit(c)) {
        // Integer literal
        int end = i + 1;
        while (end < n && Character.isDigit(s.charAt(end))) {
          end++;
        }
        out.append("<span class=\"mi\">");
        out.append(s, i, end);
        out.append("</span>");
        i = end;
        prevKeyword = null;

      } else if (c == ':' && i + 1 < n && s.charAt(i + 1) == ':') {
        // :: list-cons operator (check before lone ':')
        out.append("<span class=\"o\">::</span>");
        i += 2;
        prevKeyword = null;

      } else if (c == ':' && i + 1 < n && s.charAt(i + 1) == '=') {
        // := reference assignment operator
        out.append("<span class=\"o\">:=</span>");
        i += 2;
        prevKeyword = null;

      } else if (c == '=' && i + 1 < n && s.charAt(i + 1) == '>') {
        // => pattern-match arrow (check before '=' alone in PUNCT_CHARS)
        out.append("<span class=\"o\">=&gt;</span>");
        i += 2;
        prevKeyword = null;

      } else if (c == '-' && i + 1 < n && s.charAt(i + 1) == '>') {
        // -> function-type arrow
        out.append("<span class=\"o\">-&gt;</span>");
        i += 2;
        prevKeyword = null;

      } else if (PUNCT_CHARS.indexOf(c) >= 0) {
        // Punctuation: group consecutive punctuation characters into one span,
        // matching Rouge's behavior (e.g., "[(" and ")," are single spans).
        int end = i + 1;
        while (end < n && PUNCT_CHARS.indexOf(s.charAt(end)) >= 0) {
          end++;
        }
        out.append("<span class=\"p\">");
        out.append(s, i, end);
        out.append("</span>");
        i = end;
        prevKeyword = null;

      } else if (c == ':') {
        // Lone colon (type annotation)
        out.append("<span class=\"o\">:</span>");
        i++;
        prevKeyword = null;

      } else if (c == '+' || c == '*' || c == '/' || c == '-') {
        // Arithmetic operators (single character; - and -> handled above)
        out.append("<span class=\"o\">").append(c).append("</span>");
        i++;
        prevKeyword = null;

      } else if (c == '<') {
        out.append("&lt;");
        i++;
        prevKeyword = null;

      } else if (c == '>') {
        out.append("&gt;");
        i++;
        prevKeyword = null;

      } else if (c == '&') {
        out.append("&amp;");
        i++;
        prevKeyword = null;

      } else {
        // Whitespace, newlines, and other characters. Whitespace intentionally
        // does NOT reset prevKeyword, so "fun  name" and "val\n  x" work.
        out.append(c);
        i++;
      }
    }
  }

  /** Scans a nested SML comment {@code (* ... *)} and returns end index. */
  private static int scanComment(String s, int start, int n) {
    int depth = 0;
    int i = start;
    while (i < n) {
      if (s.charAt(i) == '(' && i + 1 < n && s.charAt(i + 1) == '*') {
        depth++;
        i += 2;
      } else if (s.charAt(i) == '*' && i + 1 < n && s.charAt(i + 1) == ')') {
        depth--;
        i += 2;
        if (depth == 0) {
          break;
        }
      } else {
        i++;
      }
    }
    return i;
  }

  /** Scans a string literal {@code "..."} and returns end index. */
  private static int scanString(String s, int start, int n) {
    int i = start + 1; // skip opening "
    while (i < n) {
      char c = s.charAt(i);
      if (c == '\\') {
        i += 2; // skip escape sequence
      } else if (c == '"') {
        i++; // skip closing "
        break;
      } else {
        i++;
      }
    }
    return i;
  }

  /**
   * Appends HTML-escaped characters from {@code s[start..end)} to {@code out}.
   */
  private static void appendEscaped(
      String s, int start, int end, StringBuilder out) {
    for (int i = start; i < end; i++) {
      char c = s.charAt(i);
      if (c == '<') {
        out.append("&lt;");
      } else if (c == '>') {
        out.append("&gt;");
      } else if (c == '&') {
        out.append("&amp;");
      } else {
        out.append(c);
      }
    }
  }
}

// End MorelHighlighter.java
