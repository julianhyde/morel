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
 * Syntax-highlights Morel source code as HTML.
 *
 * <p>Token classes produced for input code:
 *
 * <ul>
 *   <li>{@code kw} &mdash; SML and Morel reserved words;
 *   <li>{@code str} &mdash; double-quoted string literals;
 *   <li>{@code cmt} &mdash; SML comments ({@code (*} &hellip; {@code *)});
 *   <li>{@code num} &mdash; numeric literals;
 *   <li>{@code ctor} &mdash; type variables ({@code 'a}, {@code 'alpha},
 *       &hellip;) and structure names (identifier before {@code .});
 *   <li>{@code op} &mdash; operators ({@code ::}, {@code ->}, {@code :=},
 *       {@code +}, {@code -}, {@code *}, {@code /}, {@code :}).
 * </ul>
 *
 * <p>Plain identifiers and punctuation are emitted as undecorated text.
 *
 * <p>For output text (REPL responses), {@link #highlightOutput} returns the
 * text HTML-escaped but otherwise undecorated.
 *
 * <p>Morel-specific keywords (such as {@code from}, {@code yield}, {@code
 * elem}, {@code exists}) are given the {@code kw} class. Adding a new Morel
 * keyword requires only adding it to {@link #MOREL_KEYWORDS}.
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

  /** All keywords highlighted as {@code kw} in input code. */
  private static final Set<String> ALL_KEYWORDS;

  static {
    ImmutableSet.Builder<String> b = ImmutableSet.builder();
    b.addAll(SML_KEYWORDS);
    b.addAll(MOREL_KEYWORDS);
    ALL_KEYWORDS = b.build();
  }

  /**
   * Punctuation characters. Consecutive punctuation characters are grouped and
   * emitted as plain text (no span).
   */
  private static final String PUNCT_CHARS = "()[]{}=,;|.";

  private MorelHighlighter() {}

  /**
   * Highlights Morel input code using span classes.
   *
   * <p>Keywords become {@code kw}, type variables and structure names {@code
   * ctor}, integers {@code num}, operators {@code op}, comments {@code cmt},
   * and string literals {@code str}. Plain identifiers and punctuation are
   * emitted without spans.
   */
  public static String highlightInput(String code) {
    StringBuilder sb = new StringBuilder();
    highlightCode(code, sb, true);
    return sb.toString();
  }

  /**
   * Returns HTML-escaped output text (REPL responses), without any span
   * decoration.
   */
  public static String highlightOutput(String text) {
    StringBuilder sb = new StringBuilder();
    appendEscaped(text, 0, text.length(), sb);
    return sb.toString();
  }

  private static void highlightCode(
      String s, StringBuilder out, boolean keywords) {
    int i = 0;
    int n = s.length();

    while (i < n) {
      char c = s.charAt(i);

      if (c == '(' && i + 1 < n && s.charAt(i + 1) == '*') {
        // SML comment (* ... *): entire comment in "cmt".
        int end = scanComment(s, i, n);
        out.append("<span class=\"cmt\">");
        appendEscaped(s, i, end, out);
        out.append("</span>");
        i = end;

      } else if (c == '"') {
        // String literal
        int end = scanString(s, i, n);
        out.append("<span class=\"str\">");
        appendEscaped(s, i, end, out);
        out.append("</span>");
        i = end;

      } else if (c == '\''
          && i + 1 < n
          && Character.isLetter(s.charAt(i + 1))) {
        // Type variable: 'a, 'b, 'alpha, etc. → ctor class
        int end = i + 1;
        while (end < n
            && (Character.isLetterOrDigit(s.charAt(end))
                || s.charAt(end) == '_'
                || s.charAt(end) == '\'')) {
          end++;
        }
        out.append("<span class=\"ctor\">");
        appendEscaped(s, i, end, out);
        out.append("</span>");
        i = end;

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
        if (keywords && ALL_KEYWORDS.contains(word)
            || !keywords && SML_KEYWORDS.contains(word)) {
          out.append("<span class=\"kw\">");
          out.append(word);
          out.append("</span>");
        } else if (end < n && s.charAt(end) == '.') {
          // Identifier immediately followed by '.' is a structure name → ctor
          out.append("<span class=\"ctor\">");
          out.append(word);
          out.append("</span>");
        } else {
          out.append(word);
        }
        i = end;

      } else if (Character.isDigit(c)) {
        // Integer literal
        int end = i + 1;
        while (end < n && Character.isDigit(s.charAt(end))) {
          end++;
        }
        out.append("<span class=\"num\">");
        out.append(s, i, end);
        out.append("</span>");
        i = end;

      } else if (c == ':' && i + 1 < n && s.charAt(i + 1) == ':') {
        // :: list-cons operator (check before lone ':')
        out.append("<span class=\"op\">::</span>");
        i += 2;

      } else if (c == ':' && i + 1 < n && s.charAt(i + 1) == '=') {
        // := reference assignment operator
        out.append("<span class=\"op\">:=</span>");
        i += 2;

      } else if (c == '=' && i + 1 < n && s.charAt(i + 1) == '>') {
        // => pattern-match arrow (check before '=' alone in PUNCT_CHARS)
        out.append("<span class=\"op\">=&gt;</span>");
        i += 2;

      } else if (c == '-' && i + 1 < n && s.charAt(i + 1) == '>') {
        // -> function-type arrow
        out.append("<span class=\"op\">-&gt;</span>");
        i += 2;

      } else if (PUNCT_CHARS.indexOf(c) >= 0) {
        // Punctuation: plain text, group consecutive punctuation characters.
        int end = i + 1;
        while (end < n && PUNCT_CHARS.indexOf(s.charAt(end)) >= 0) {
          end++;
        }
        out.append(s, i, end);
        i = end;

      } else if (c == ':') {
        // Lone colon (type annotation) → op class
        out.append("<span class=\"op\">:</span>");
        i++;

      } else if (c == '+' || c == '*' || c == '/' || c == '-') {
        // Arithmetic operators (single character; - and -> handled above)
        out.append("<span class=\"op\">").append(c).append("</span>");
        i++;

      } else if (c == '<') {
        out.append("&lt;");
        i++;

      } else if (c == '>') {
        out.append("&gt;");
        i++;

      } else if (c == '&') {
        out.append("&amp;");
        i++;

      } else {
        // Whitespace, newlines, and other characters.
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
