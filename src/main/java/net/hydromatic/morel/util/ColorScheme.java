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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.jline.utils.AttributedStyle;
import org.jline.utils.StyleResolver;

/**
 * A mapping from syntax-highlighting token categories to terminal styles.
 *
 * <p>A scheme assigns an {@link AttributedStyle} to each {@link Category}. Any
 * category that the scheme does not mention uses the terminal's default style.
 *
 * <p>The built-in schemes {@link #DARK}, {@link #LIGHT} and {@link #NONE} are
 * loaded from {@code /color-schemes/*.properties} on the classpath. A scheme
 * value is zero or more attributes ({@code bold}, {@code italic}, {@code
 * underline}, {@code faint}, ...) followed by a color (an ANSI color name, an
 * index 0-255, or {@code #rrggbb}); for example {@code "bold #5f87ff"} or
 * {@code "italic bright-black"}. Values are resolved to styles using JLine's
 * {@link StyleResolver}.
 */
public class ColorScheme {
  /** Token category that can be assigned a style. */
  public enum Category {
    KEYWORD("keyword"),
    SYMBOL("symbol"),
    NUMERIC("numeric"),
    CONSTANT("constant"),
    STRING("string"),
    COMMENT("comment"),
    TYPE_VAR("typeVar"),
    IDENTIFIER("identifier"),
    ERROR("error");

    /** Name used as a key in a scheme's properties file. */
    public final String key;

    Category(String key) {
      this.key = key;
    }

    private static final Map<String, Category> BY_KEY;

    static {
      final ImmutableMap.Builder<String, Category> b = ImmutableMap.builder();
      for (Category category : values()) {
        b.put(category.key, category);
      }
      BY_KEY = b.build();
    }

    /** Returns the category with the given key, or null. */
    public static @Nullable Category byKey(String key) {
      return BY_KEY.get(key);
    }
  }

  /** Named attributes recognized in a scheme value (all others are colors). */
  private static final Set<String> ATTRIBUTES =
      ImmutableMap.<String, Boolean>builder()
          .put("bold", true)
          .put("faint", true)
          .put("italic", true)
          .put("underline", true)
          .put("blink", true)
          .put("inverse", true)
          .put("conceal", true)
          .put("crossed-out", true)
          .put("hidden", true)
          .build()
          .keySet();

  private static final Pattern WHITESPACE = Pattern.compile("\\s+");
  private static final Pattern DIGITS = Pattern.compile("[0-9]+");

  /** Scheme that applies no styling. */
  public static final ColorScheme NONE =
      new ColorScheme("none", ImmutableMap.of(), ImmutableMap.of());

  /** Default scheme, for a dark terminal background. */
  public static final ColorScheme DARK = load("dark");

  /** Scheme for a light terminal background. */
  public static final ColorScheme LIGHT = load("light");

  private final String name;
  private final Map<Category, AttributedStyle> styles;
  private final Map<Category, String> specs;

  private ColorScheme(
      String name,
      Map<Category, AttributedStyle> styles,
      Map<Category, String> specs) {
    this.name = name;
    this.styles = ImmutableMap.copyOf(styles);
    this.specs = ImmutableMap.copyOf(specs);
  }

  /** Returns the built-in schemes. */
  public static ImmutableList<ColorScheme> builtIns() {
    return ImmutableList.of(DARK, LIGHT, NONE);
  }

  /** Returns the name of this scheme. */
  public String name() {
    return name;
  }

  /**
   * Returns the style for a category, or {@link AttributedStyle#DEFAULT} if the
   * scheme does not style that category.
   */
  public AttributedStyle style(Category category) {
    return styles.getOrDefault(category, AttributedStyle.DEFAULT);
  }

  /**
   * Returns the scheme's style specification for a category (e.g. {@code "bold
   * cyan"}), or the empty string if the scheme does not style that category.
   */
  public String spec(Category category) {
    return specs.getOrDefault(category, "");
  }

  /** Returns the built-in scheme with the given name, or null. */
  public static @Nullable ColorScheme builtIn(String name) {
    switch (name) {
      case "none":
        return NONE;
      case "dark":
        return DARK;
      case "light":
        return LIGHT;
      default:
        return null;
    }
  }

  /**
   * Creates a scheme from a map of category key to style value (e.g. {@code
   * "keyword"} to {@code "bold cyan"}). Unknown keys are ignored.
   */
  public static ColorScheme parse(String name, Map<String, String> props) {
    final StyleResolver resolver = new StyleResolver(s -> null);
    final EnumMap<Category, AttributedStyle> styles =
        new EnumMap<>(Category.class);
    final EnumMap<Category, String> specs = new EnumMap<>(Category.class);
    props.forEach(
        (key, value) -> {
          final Category category = Category.byKey(key);
          if (category == null) {
            return;
          }
          specs.put(category, value.trim());
          final String spec = toStyleSpec(value);
          if (!spec.isEmpty()) {
            styles.put(category, resolver.resolve(spec));
          }
        });
    return new ColorScheme(name, styles, specs);
  }

  /**
   * Translates a scheme value such as {@code "bold #5f87ff"} into a {@link
   * StyleResolver} specification such as {@code "bold,fg-rgb:#5f87ff"}.
   * Attributes ({@code bold}, {@code italic}, ...) are passed through; a {@code
   * #rrggbb} color becomes {@code fg-rgb:#rrggbb}; a bare number becomes an
   * xterm-256 foreground index (raw ANSI {@code 38;5;n}); anything else is
   * treated as a foreground color name ({@code fg:...}, which also accepts
   * {@code bright-*}).
   */
  private static String toStyleSpec(String value) {
    final StringBuilder b = new StringBuilder();
    for (String token : WHITESPACE.split(value.trim())) {
      if (token.isEmpty()) {
        continue;
      }
      if (b.length() > 0) {
        b.append(',');
      }
      if (ATTRIBUTES.contains(token.toLowerCase(Locale.ROOT))) {
        b.append(token);
      } else if (token.startsWith("#")) {
        b.append("fg-rgb:").append(token);
      } else if (DIGITS.matcher(token).matches()) {
        b.append("38;5;").append(token);
      } else {
        b.append("fg:").append(token);
      }
    }
    return b.toString();
  }

  /** Loads a built-in scheme from a classpath resource. */
  private static ColorScheme load(String name) {
    final String resource = "/color-schemes/" + name + ".properties";
    final Properties properties = new Properties();
    try (InputStream in = ColorScheme.class.getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("missing color scheme " + resource);
      }
      properties.load(in);
    } catch (IOException e) {
      throw new IllegalStateException("cannot load color scheme " + name, e);
    }
    final Map<String, String> map = new LinkedHashMap<>();
    properties.forEach((k, v) -> map.put((String) k, (String) v));
    return parse(name, map);
  }
}

// End ColorScheme.java
