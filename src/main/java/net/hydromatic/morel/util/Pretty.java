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

import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.function.BinaryOperator;
import java.util.function.IntFunction;

/**
 * Pretty-printer based on Wadler's "prettier printer" algorithm.
 *
 * <p>The {@link Doc} algebraic data type represents a set of possible layouts
 * for a document. The {@link #render(int, Doc)} method chooses the best layout
 * given a line-width limit.
 *
 * <p>This is the Wadler-Lindig variant with Leijen's {@code Column} and {@code
 * Nesting} extensions (which enable {@link #align(Doc)}). {@link #render(int,
 * Doc)} uses Lindig's strict, iterative formulation (Christian Lindig,
 * "Strictly Pretty", 2000): it lays out the document in a single pass over an
 * explicit work list, so it runs in time linear in the size of the document and
 * uses constant Java stack however deeply the document nests.
 *
 * <p>This class has no dependency on Morel's AST.
 *
 * @see <a
 *     href="https://homepages.inf.ed.ac.uk/wadler/papers/prettier/prettier.pdf">Wadler,
 *     "A prettier printer"</a>
 */
public class Pretty {
  private Pretty() {}

  // -- Doc algebraic type ---------------------------------------------------

  /**
   * A document that can be laid out in multiple ways.
   *
   * <p>Instances are created via the static methods in {@link Pretty}.
   */
  public abstract static class Doc {
    private Doc() {}
  }

  /** The empty document. */
  static final class Empty extends Doc {
    static final Empty INSTANCE = new Empty();

    private Empty() {}

    @Override
    public String toString() {
      return "Empty";
    }
  }

  /** Literal text {@code s} followed by {@code doc}. */
  static final class Text extends Doc {
    final String text;
    final Doc doc;

    Text(String text, Doc doc) {
      this.text = requireNonNull(text);
      this.doc = requireNonNull(doc);
    }

    @Override
    public String toString() {
      return "Text(" + text + ")";
    }
  }

  /** Newline, then {@code indent} spaces, then {@code doc}. */
  static final class Line extends Doc {
    final Doc doc;

    Line(Doc doc) {
      this.doc = requireNonNull(doc);
    }

    @Override
    public String toString() {
      return "Line";
    }
  }

  /**
   * {@code primary} when broken across lines; {@code flat} when flattened to
   * one line.
   */
  static final class FlatAlt extends Doc {
    final Doc primary;
    final Doc flat;

    FlatAlt(Doc primary, Doc flat) {
      this.primary = requireNonNull(primary);
      this.flat = requireNonNull(flat);
    }

    @Override
    public String toString() {
      return "FlatAlt";
    }
  }

  /** Concatenation of {@code a} followed by {@code b}. */
  static final class Cat extends Doc {
    final Doc a;
    final Doc b;

    Cat(Doc a, Doc b) {
      this.a = requireNonNull(a);
      this.b = requireNonNull(b);
    }

    @Override
    public String toString() {
      return "Cat";
    }
  }

  /** Increase indentation by {@code indent} for the sub-document. */
  static final class Nest extends Doc {
    final int indent;
    final Doc doc;

    Nest(int indent, Doc doc) {
      this.indent = indent;
      this.doc = requireNonNull(doc);
    }

    @Override
    public String toString() {
      return "Nest(" + indent + ")";
    }
  }

  /**
   * Lay out {@code doc} flat if it fits the remaining space, otherwise broken.
   *
   * <p>Flattening happens on the fly during rendering (via {@link Mode#FLAT}),
   * so there is no separate flattened copy of {@code doc}.
   */
  static final class Group extends Doc {
    final Doc doc;

    Group(Doc doc) {
      this.doc = requireNonNull(doc);
    }

    @Override
    public String toString() {
      return "Group";
    }
  }

  /** Access the current column to produce a document. */
  static final class Column extends Doc {
    final IntFunction<Doc> fn;

    Column(IntFunction<Doc> fn) {
      this.fn = requireNonNull(fn);
    }

    @Override
    public String toString() {
      return "Column";
    }
  }

  /** Access the current nesting level to produce a document. */
  static final class Nesting extends Doc {
    final IntFunction<Doc> fn;

    Nesting(IntFunction<Doc> fn) {
      this.fn = requireNonNull(fn);
    }

    @Override
    public String toString() {
      return "Nesting";
    }
  }

  // -- Primitives -----------------------------------------------------------

  /** The empty document. */
  public static final Doc EMPTY = Empty.INSTANCE;

  /**
   * A line break that is replaced by a space when flattened.
   *
   * <p>This is the most common separator for {@link #group(Doc)}: when the
   * group fits on one line the line breaks become spaces.
   */
  public static final Doc LINE =
      new FlatAlt(new Line(EMPTY), new Text(" ", EMPTY));

  /**
   * A line break that is replaced by nothing when flattened.
   *
   * <p>Useful when the line break only exists for formatting, e.g. after an
   * opening bracket.
   */
  public static final Doc LINE_BREAK = new FlatAlt(new Line(EMPTY), EMPTY);

  /**
   * A space when it fits, otherwise a line break.
   *
   * <p>Equivalent to {@code group(line)}.
   */
  public static final Doc SOFT_LINE = group(LINE);

  /**
   * Nothing when it fits, otherwise a line break.
   *
   * <p>Equivalent to {@code group(lineBreak)}.
   */
  public static final Doc SOFT_BREAK = group(LINE_BREAK);

  /**
   * A line break that is always rendered, even when flattened.
   *
   * <p>Use sparingly — this prevents the enclosing {@code group} from
   * flattening.
   */
  public static final Doc HARD_LINE = new Line(EMPTY);

  /**
   * Creates a document containing literal text. The text must not contain
   * newlines; use {@link #LINE} or {@link #HARD_LINE} for those.
   */
  public static Doc text(String s) {
    if (s.isEmpty()) {
      return EMPTY;
    }
    return new Text(s, EMPTY);
  }

  // -- Composition ----------------------------------------------------------

  /** Concatenation: {@code a} followed by {@code b}. */
  public static Doc beside(Doc a, Doc b) {
    return new Cat(a, b);
  }

  /** Increases indentation of {@code doc} by {@code indent} spaces. */
  public static Doc nest(int indent, Doc doc) {
    if (indent == 0) {
      return doc;
    }
    return new Nest(indent, doc);
  }

  /**
   * Marks {@code doc} as a group: {@link #render(int, Doc)} lays the group out
   * flat (line breaks become their flat alternatives) if it fits the remaining
   * width, and otherwise lays it out broken.
   *
   * <p>A group that contains a {@link #HARD_LINE} can never be laid out flat,
   * so it always breaks.
   */
  public static Doc group(Doc doc) {
    if (doc instanceof Group) {
      return doc; // already a group
    }
    return new Group(doc);
  }

  /**
   * Lays out {@code doc} with the nesting level set to the current column.
   *
   * <p>This is used to align sub-documents to the current position, e.g. to
   * align {@code |} in match arms or {@code ,} in tuples.
   */
  public static Doc align(Doc doc) {
    return new Column(k -> new Nesting(i -> nest(k - i, doc)));
  }

  /**
   * Lays out {@code doc} with a nesting level of {@code indent} relative to the
   * current column.
   *
   * <p>Equivalent to {@code align(nest(indent, doc))}.
   */
  public static Doc hang(int indent, Doc doc) {
    return align(nest(indent, doc));
  }

  /**
   * Indents {@code doc} by {@code indent} spaces, and then aligns subsequent
   * lines to the first.
   */
  public static Doc indent(int indent, Doc doc) {
    return beside(text(spaces(indent)), hang(indent, doc));
  }

  // -- List combinators -----------------------------------------------------

  /** Concatenates documents horizontally, separated by spaces. */
  public static Doc hsep(List<Doc> docs) {
    return fold(docs, Pretty::withSpace);
  }

  /** Concatenates documents vertically, separated by line breaks. */
  public static Doc vsep(List<Doc> docs) {
    return fold(docs, Pretty::withLine);
  }

  /**
   * Concatenates documents separated by spaces if they fit on one line,
   * otherwise separates them with line breaks. Equivalent to {@code
   * group(vsep(docs))}.
   */
  public static Doc sep(List<Doc> docs) {
    return group(vsep(docs));
  }

  /** Concatenates documents horizontally with no separator. */
  public static Doc hcat(List<Doc> docs) {
    return fold(docs, Pretty::beside);
  }

  /** Concatenates documents vertically, separated by empty line breaks. */
  public static Doc vcat(List<Doc> docs) {
    return fold(docs, Pretty::withLineBreak);
  }

  /**
   * Concatenates documents with no separator if they fit on one line, otherwise
   * separates them with line breaks. Equivalent to {@code group(vcat(docs))}.
   */
  public static Doc cat(List<Doc> docs) {
    return group(vcat(docs));
  }

  /**
   * Concatenates documents, filling each line with as many as will fit,
   * separated by spaces.
   */
  public static Doc fillSep(List<Doc> docs) {
    return fold(docs, Pretty::withSoftLine);
  }

  /**
   * Concatenates documents, filling each line with as many as will fit, with no
   * separator.
   */
  public static Doc fillCat(List<Doc> docs) {
    return fold(docs, Pretty::withSoftBreak);
  }

  /**
   * Intersperses {@code separator} between the documents.
   *
   * <p>For example, {@code punctuate(text(","), [a, b, c])} returns {@code
   * [beside(a, text(",")), beside(b, text(",")), c]}.
   */
  public static List<Doc> punctuate(Doc separator, List<Doc> docs) {
    if (docs.size() <= 1) {
      return docs;
    }
    final ImmutableList.Builder<Doc> b = ImmutableList.builder();
    for (int i = 0; i < docs.size() - 1; i++) {
      b.add(beside(docs.get(i), separator));
    }
    b.add(docs.get(docs.size() - 1));
    return b.build();
  }

  /**
   * Encloses a list of documents between {@code open} and {@code close},
   * separated by {@code separator}.
   *
   * <p>If the result fits on one line it is rendered horizontally with spaces
   * after each separator; otherwise each element is placed on its own line,
   * aligned.
   *
   * <p>The separator should be just the punctuation (e.g., {@code text(",")})
   * without a trailing space; the space or line break is added automatically.
   */
  public static Doc encloseSep(
      Doc open, Doc close, Doc separator, List<Doc> docs) {
    if (docs.isEmpty()) {
      return beside(open, close);
    }
    if (docs.size() == 1) {
      return beside(open, beside(docs.get(0), close));
    }
    final List<Doc> punctuated = punctuate(separator, docs);
    return group(beside(open, beside(align(vsep(punctuated)), close)));
  }

  // -- Bracketing helpers ---------------------------------------------------

  /** Encloses {@code doc} in parentheses. */
  public static Doc parens(Doc doc) {
    return beside(text("("), beside(doc, text(")")));
  }

  /** Encloses {@code doc} in braces. */
  public static Doc braces(Doc doc) {
    return beside(text("{"), beside(doc, text("}")));
  }

  /** Encloses {@code doc} in square brackets. */
  public static Doc brackets(Doc doc) {
    return beside(text("["), beside(doc, text("]")));
  }

  // -- Rendering ------------------------------------------------------------

  /**
   * Renders a document to a string, choosing the best layout for the given line
   * width.
   */
  public static String render(int width, Doc doc) {
    final StringBuilder b = new StringBuilder();
    int k = 0; // current column
    Item item = new Item(0, Mode.BREAK, doc, null);
    while (item != null) {
      final int i = item.indent;
      final Mode mode = item.mode;
      final Doc d = item.doc;
      final Item next = item.next;
      if (d instanceof Empty) {
        item = next;
      } else if (d instanceof Text) {
        final Text t = (Text) d;
        b.append(t.text);
        k += t.text.length();
        item = t.doc instanceof Empty ? next : new Item(i, mode, t.doc, next);
      } else if (d instanceof Cat) {
        final Cat cat = (Cat) d;
        item = new Item(i, mode, cat.a, new Item(i, mode, cat.b, next));
      } else if (d instanceof Nest) {
        final Nest nest = (Nest) d;
        item = new Item(i + nest.indent, mode, nest.doc, next);
      } else if (d instanceof Line) {
        final Doc rest = ((Line) d).doc;
        b.append('\n').append(spaces(i));
        k = i;
        item = rest instanceof Empty ? next : new Item(i, mode, rest, next);
      } else if (d instanceof FlatAlt) {
        final FlatAlt f = (FlatAlt) d;
        item = new Item(i, mode, mode == Mode.FLAT ? f.flat : f.primary, next);
      } else if (d instanceof Group) {
        final Doc inner = ((Group) d).doc;
        final Item flat = new Item(i, Mode.FLAT, inner, next);
        item =
            fits(width, k, flat) ? flat : new Item(i, Mode.BREAK, inner, next);
      } else if (d instanceof Column) {
        item = new Item(i, mode, ((Column) d).fn.apply(k), next);
      } else if (d instanceof Nesting) {
        item = new Item(i, mode, ((Nesting) d).fn.apply(i), next);
      } else {
        throw new AssertionError("unknown Doc: " + d);
      }
    }
    return b.toString();
  }

  // -- Private helpers ------------------------------------------------------

  /** Folds a list of documents using a binary operator. */
  private static Doc fold(List<Doc> docs, BinaryOperator<Doc> op) {
    switch (docs.size()) {
      case 0:
        return EMPTY;
      case 1:
        return docs.get(0);
      default:
        Doc result = docs.get(docs.size() - 1);
        for (int i = docs.size() - 2; i >= 0; i--) {
          result = op.apply(docs.get(i), result);
        }
        return result;
    }
  }

  private static Doc withSpace(Doc a, Doc b) {
    return beside(a, beside(text(" "), b));
  }

  private static Doc withLine(Doc a, Doc b) {
    return beside(a, beside(LINE, b));
  }

  private static Doc withLineBreak(Doc a, Doc b) {
    return beside(a, beside(LINE_BREAK, b));
  }

  private static Doc withSoftLine(Doc a, Doc b) {
    return beside(a, beside(SOFT_LINE, b));
  }

  private static Doc withSoftBreak(Doc a, Doc b) {
    return beside(a, beside(SOFT_BREAK, b));
  }

  /**
   * Returns whether the work list fits in the remaining space on the current
   * line. Scans forward until the first line break (which ends the current
   * line, so what precedes it fits) or until the page width is exceeded.
   *
   * @param width page width
   * @param col current column
   * @param item work list to measure
   */
  private static boolean fits(int width, int col, Item item) {
    for (; ; ) {
      if (col > width) {
        return false;
      }
      if (item == null) {
        return true;
      }
      final int i = item.indent;
      final Mode mode = item.mode;
      final Doc d = item.doc;
      final Item next = item.next;
      if (d instanceof Empty) {
        item = next;
      } else if (d instanceof Text) {
        final Text t = (Text) d;
        col += t.text.length();
        item = t.doc instanceof Empty ? next : new Item(i, mode, t.doc, next);
      } else if (d instanceof Cat) {
        final Cat cat = (Cat) d;
        item = new Item(i, mode, cat.a, new Item(i, mode, cat.b, next));
      } else if (d instanceof Nest) {
        final Nest nest = (Nest) d;
        item = new Item(i + nest.indent, mode, nest.doc, next);
      } else if (d instanceof Line) {
        // In a broken layout a line break ends the current line, so what
        // precedes it fits. In a flat layout a bare (hard) line cannot be
        // flattened, so this layout does not fit and the group must break.
        return mode == Mode.BREAK;
      } else if (d instanceof FlatAlt) {
        final FlatAlt f = (FlatAlt) d;
        item = new Item(i, mode, mode == Mode.FLAT ? f.flat : f.primary, next);
      } else if (d instanceof Group) {
        // Inside a flat layout every group is flat too. A downstream group in a
        // broken layout makes its own break decision: it breaks when its
        // flattened form (with what follows) does not fit, and the resulting
        // line break ends the current line. This is what lets adjacent groups
        // in a fill wrap independently rather than all-or-nothing.
        final Item flat = new Item(i, Mode.FLAT, ((Group) d).doc, next);
        if (mode == Mode.BREAK && !fits(width, col, flat)) {
          return true;
        }
        item = flat;
      } else if (d instanceof Column) {
        item = new Item(i, mode, ((Column) d).fn.apply(col), next);
      } else if (d instanceof Nesting) {
        item = new Item(i, mode, ((Nesting) d).fn.apply(i), next);
      } else {
        throw new AssertionError("unknown Doc: " + d);
      }
    }
  }

  /** Layout mode of a work-list item: {@code FLAT} suppresses line breaks. */
  private enum Mode {
    FLAT,
    BREAK
  }

  /**
   * An entry in the work list for the layout algorithm: render {@link #doc} at
   * the given indent and mode, then continue with {@link #next}.
   *
   * <p>The list is immutable, so the tail can be shared between the flat and
   * broken alternatives of a {@link Group} without copying.
   */
  private static final class Item {
    final int indent;
    final Mode mode;
    final Doc doc;
    final Item next;

    Item(int indent, Mode mode, Doc doc, Item next) {
      this.indent = indent;
      this.mode = mode;
      this.doc = doc;
      this.next = next;
    }
  }

  /** Returns a string of {@code n} spaces. */
  private static String spaces(int n) {
    final StringBuilder b = new StringBuilder(n);
    for (int i = 0; i < n; i++) {
      b.append(' ');
    }
    return b.toString();
  }
}

// End Pretty.java
