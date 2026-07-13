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

import com.google.common.collect.ImmutableSet;
import java.util.Set;
import java.util.regex.Pattern;
import net.hydromatic.morel.eval.Prop;
import net.hydromatic.morel.eval.Session;
import net.hydromatic.morel.util.ColorScheme;
import net.hydromatic.morel.util.ColorScheme.Category;
import net.hydromatic.morel.util.MorelHighlighter;
import org.jline.reader.Highlighter;
import org.jline.reader.LineReader;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;

/**
 * JLine {@link Highlighter} that colors Morel input in the shell.
 *
 * <p>Tokenizes the input with {@link MorelHighlighter} (a lenient, non-regex
 * lexer) and styles each token according to the current {@link ColorScheme}.
 * The scheme is read from the {@link Prop#COLOR_SCHEME} property on every
 * keystroke, so {@code Sys.set ("colorScheme", ...)} takes effect immediately.
 * A value of {@code "auto"} is resolved from the terminal and environment (see
 * {@link #resolveScheme()}).
 */
public class ShellHighlighter implements Highlighter {
  /** Identifiers that are styled as constants rather than plain identifiers. */
  private static final Set<String> CONSTANTS =
      ImmutableSet.of("true", "false", "nil", "NONE");

  private final Session session;
  private final Terminal terminal;

  ShellHighlighter(Session session, Terminal terminal) {
    this.session = session;
    this.terminal = terminal;
  }

  @Override
  public AttributedString highlight(LineReader reader, String buffer) {
    final ColorScheme scheme = resolveScheme();
    if (scheme == ColorScheme.NONE) {
      return new AttributedString(buffer);
    }
    final AttributedStringBuilder asb = new AttributedStringBuilder();
    MorelHighlighter.DEFAULT.highlightCode(
        buffer, new StyleSink(buffer, asb, scheme));
    return asb.toAttributedString();
  }

  /**
   * Resolves the current value of {@link Prop#COLOR_SCHEME} to a scheme. A
   * built-in name ({@code dark}, {@code light}, {@code none}) is used directly;
   * {@code auto} (or an unknown name) is resolved from the environment.
   */
  private ColorScheme resolveScheme() {
    final String name = Prop.COLOR_SCHEME.stringValue(session.map);
    final ColorScheme scheme = ColorScheme.builtIn(name);
    if (scheme != null) {
      return scheme;
    }
    return autoScheme();
  }

  /**
   * Resolves {@code auto}: {@code none} if color is disabled (dumb terminal,
   * {@code NO_COLOR} set, or {@code TERM=dumb}); otherwise {@code light} if
   * {@code COLORFGBG} indicates a light background, else {@code dark}.
   */
  private ColorScheme autoScheme() {
    final String type = terminal.getType();
    if (type == null || type.startsWith(Terminal.TYPE_DUMB)) {
      return ColorScheme.NONE;
    }
    if (System.getenv("NO_COLOR") != null) {
      return ColorScheme.NONE;
    }
    if ("dumb".equals(System.getenv("TERM"))) {
      return ColorScheme.NONE;
    }
    return isLightBackground() ? ColorScheme.LIGHT : ColorScheme.DARK;
  }

  /**
   * Returns whether {@code COLORFGBG} indicates a light background. The
   * variable has the form {@code "fg;bg"} (e.g. {@code "0;15"}); a background
   * of 7 or 15 (white or bright white) is treated as light.
   */
  private static boolean isLightBackground() {
    final String colorFgBg = System.getenv("COLORFGBG");
    if (colorFgBg == null) {
      return false;
    }
    final String[] parts = colorFgBg.split(";");
    try {
      final int bg = Integer.parseInt(parts[parts.length - 1].trim());
      return bg == 7 || bg == 15;
    } catch (NumberFormatException e) {
      return false;
    }
  }

  @Override
  public void setErrorPattern(Pattern errorPattern) {
    // Not used; errors are styled by the lexer, not by a pattern.
  }

  @Override
  public void setErrorIndex(int errorIndex) {
    // Not used.
  }

  /**
   * Sink that appends each token to an {@link AttributedStringBuilder}, styled
   * by category. Maps {@link MorelHighlighter}'s fine-grained token classes
   * onto the {@link Category} values that a scheme understands.
   */
  private static class StyleSink implements MorelHighlighter.Sink {
    private final String source;
    private final AttributedStringBuilder asb;
    private final ColorScheme scheme;

    StyleSink(String source, AttributedStringBuilder asb, ColorScheme scheme) {
      this.source = source;
      this.asb = asb;
      this.scheme = scheme;
    }

    private void span(Category category, int start, int end) {
      asb.styled(scheme.style(category), source.substring(start, end));
    }

    @Override
    public void kr(int start, int end) {
      span(Category.KEYWORD, start, end);
    }

    @Override
    public void s(int start, int end) {
      span(Category.STRING, start, end);
    }

    @Override
    public void c(int start, int end) {
      span(Category.COMMENT, start, end);
    }

    @Override
    public void cm(int start, int end) {
      span(Category.COMMENT, start, end);
    }

    @Override
    public void ct(int start, int end) {
      span(Category.TYPE_VAR, start, end);
    }

    @Override
    public void n(int start, int end) {
      span(Category.NUMERIC, start, end);
    }

    @Override
    public void o(int start, int end) {
      span(Category.SYMBOL, start, end);
    }

    @Override
    public void nv(int start, int end) {
      span(Category.IDENTIFIER, start, end);
    }

    @Override
    public void nf(int start, int end) {
      span(Category.IDENTIFIER, start, end);
    }

    @Override
    public void id(int start, int end) {
      final Category category =
          CONSTANTS.contains(source.substring(start, end))
              ? Category.CONSTANT
              : Category.IDENTIFIER;
      span(category, start, end);
    }

    @Override
    public void p(int start, int end) {
      span(Category.SYMBOL, start, end);
    }

    @Override
    public void plain(int start, int end) {
      asb.append(source.substring(start, end));
    }
  }
}

// End ShellHighlighter.java
