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
 * <p>Generates HTML with:
 *
 * <ul>
 *   <li>{@code <b>keyword</b>} for reserved words (SML and Morel extensions);
 *   <li>{@code <i>'a</i>} for type variables;
 *   <li>HTML-escaped text for everything else.
 * </ul>
 *
 * <p>For output text (REPL responses), use {@link #highlightOutput} which only
 * italicizes type variables and HTML-escapes other text.
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

  /** Morel-specific keywords not in SML. */
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

  /** All keywords that should be bolded in input code. */
  private static final Set<String> ALL_KEYWORDS;

  static {
    ImmutableSet.Builder<String> b = ImmutableSet.builder();
    b.addAll(SML_KEYWORDS);
    b.addAll(MOREL_KEYWORDS);
    ALL_KEYWORDS = b.build();
  }

  private MorelHighlighter() {}

  /**
   * Highlights Morel input code, bolding keywords and italicizing type
   * variables.
   */
  public static String highlightInput(String code) {
    StringBuilder sb = new StringBuilder();
    highlight(code, sb, true);
    return sb.toString();
  }

  /**
   * Highlights Morel output text, italicizing type variables only (no keyword
   * bolding).
   */
  public static String highlightOutput(String text) {
    StringBuilder sb = new StringBuilder();
    highlight(text, sb, false);
    return sb.toString();
  }

  private static void highlight(String s, StringBuilder out, boolean keywords) {
    int i = 0;
    int n = s.length();
    while (i < n) {
      char c = s.charAt(i);

      if (c == '(' && i + 1 < n && s.charAt(i + 1) == '*') {
        // SML comment: (* ... *), may be nested
        int end = scanComment(s, i, n);
        appendEscaped(s, i, end, out);
        i = end;

      } else if (c == '"') {
        // String literal
        int end = scanString(s, i, n);
        appendEscaped(s, i, end, out);
        i = end;

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
        out.append("<i>");
        appendEscaped(s, i, end, out);
        out.append("</i>");
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
        if (keywords && ALL_KEYWORDS.contains(word)) {
          out.append("<b>").append(word).append("</b>");
        } else {
          out.append(word);
        }
        i = end;

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
        out.append(c);
        i++;
      }
    }
  }

  /** Scans a nested SML comment {@code (* ... *)} and returns the end index. */
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

  /** Scans a string literal {@code "..."} and returns the end index. */
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
