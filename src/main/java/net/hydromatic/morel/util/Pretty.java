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
 * Nesting} extensions (which enable {@link #align(Doc)}).
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
   * Choose {@code wide} if it fits in the remaining space, otherwise {@code
   * narrow}. Invariant: {@code wide} is the flattened form of {@code narrow}.
   */
  static final class Union extends Doc {
    final Doc wide;
    final Doc narrow;

    Union(Doc wide, Doc narrow) {
      this.wide = requireNonNull(wide);
      this.narrow = requireNonNull(narrow);
    }

    @Override
    public String toString() {
      return "Union";
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

  // -- Laid-out document (intermediate form) --------------------------------

  /** A document that has been laid out to a specific width. */
  private abstract static class SimpleDoc {
    private SimpleDoc() {}
  }

  /** Empty laid-out document. */
  private static final class SEmpty extends SimpleDoc {
    static final SEmpty INSTANCE = new SEmpty();
  }

  /** Text followed by more laid-out document. */
  private static final class SText extends SimpleDoc {
    final String text;
    final SimpleDoc rest;

    SText(String text, SimpleDoc rest) {
      this.text = text;
      this.rest = rest;
    }
  }

  /** Newline, indented by {@code indent} spaces, then more document. */
  private static final class SLine extends SimpleDoc {
    final int indent;
    final SimpleDoc rest;

    SLine(int indent, SimpleDoc rest) {
      this.indent = indent;
      this.rest = rest;
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
   * Tries laying out {@code doc} on a single line by replacing line breaks with
   * their flat alternatives. If the result fits within the page width, it is
   * used; otherwise the original (broken) layout is used.
   *
   * <p>If the document contains a {@link #HARD_LINE}, flattening is impossible
   * and the document is returned unchanged.
   */
  public static Doc group(Doc doc) {
    if (doc instanceof Union) {
      return doc; // already a group
    }
    final Doc flat = flatten(doc);
    if (flat == null) {
      return doc; // contains a hard line; cannot flatten
    }
    return new Union(flat, doc);
  }

  /**
   * Replaces all line breaks in {@code doc} with their flattened alternatives.
   *
   * <p>Returns {@code null} if the document contains a hard line break that
   * cannot be flattened.
   */
  static Doc flatten(Doc doc) {
    if (doc instanceof Empty) {
      return doc;
    } else if (doc instanceof Cat) {
      final Doc a = flatten(((Cat) doc).a);
      if (a == null) {
        return null;
      }
      final Doc b = flatten(((Cat) doc).b);
      if (b == null) {
        return null;
      }
      return new Cat(a, b);
    } else if (doc instanceof Nest) {
      final Doc inner = flatten(((Nest) doc).doc);
      if (inner == null) {
        return null;
      }
      return new Nest(((Nest) doc).indent, inner);
    } else if (doc instanceof FlatAlt) {
      return ((FlatAlt) doc).flat;
    } else if (doc instanceof Union) {
      return flatten(((Union) doc).wide);
    } else if (doc instanceof Line) {
      // A bare Line (HARD_LINE) cannot be flattened.
      return null;
    } else if (doc instanceof Text) {
      final Text t = (Text) doc;
      if (t.doc instanceof Empty) {
        return doc;
      }
      final Doc flatRest = flatten(t.doc);
      if (flatRest == null) {
        return null;
      }
      return flatRest == t.doc ? doc : new Text(t.text, flatRest);
    } else if (doc instanceof Column) {
      return new Column(k -> flatten(((Column) doc).fn.apply(k)));
    } else if (doc instanceof Nesting) {
      return new Nesting(i -> flatten(((Nesting) doc).fn.apply(i)));
    } else {
      throw new AssertionError("unknown Doc: " + doc);
    }
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
   * <p>If the result fits on one line it is rendered horizontally; otherwise
   * each element is placed on its own line, aligned.
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
    return group(beside(open, beside(align(vcat(punctuated)), close)));
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
    final SimpleDoc simpleDoc = best(width, 0, doc);
    return display(simpleDoc);
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
   * Returns whether the laid-out document fits within {@code width} columns.
   * Stops early at the first newline.
   */
  private static boolean fits(int width, SimpleDoc doc) {
    if (width < 0) {
      return false;
    }
    if (doc instanceof SEmpty) {
      return true;
    } else if (doc instanceof SText) {
      return fits(width - ((SText) doc).text.length(), ((SText) doc).rest);
    } else if (doc instanceof SLine) {
      return true; // a newline always "fits"
    } else {
      throw new AssertionError();
    }
  }

  /**
   * Chooses the best layout for a document.
   *
   * <p>This is the core of Wadler's algorithm. It processes a list of {@code
   * (indent, doc)} pairs. The list is an immutable linked list so that it can
   * be shared across both branches of a {@link Union}.
   *
   * @param w page width
   * @param k current column
   * @param items work list of (indent, doc) pairs
   */
  private static SimpleDoc be(int w, int k, Items items) {
    if (items == null) {
      return SEmpty.INSTANCE;
    }
    final int i = items.indent;
    final Doc d = items.doc;
    final Items z = items.next;

    if (d instanceof Empty) {
      return be(w, k, z);
    } else if (d instanceof Cat) {
      return be(w, k, new Items(i, ((Cat) d).a, new Items(i, ((Cat) d).b, z)));
    } else if (d instanceof Nest) {
      return be(w, k, new Items(i + ((Nest) d).indent, ((Nest) d).doc, z));
    } else if (d instanceof Text) {
      final Text t = (Text) d;
      final Items rest = t.doc instanceof Empty ? z : new Items(i, t.doc, z);
      return new SText(t.text, be(w, k + t.text.length(), rest));
    } else if (d instanceof Line) {
      final Doc rest = ((Line) d).doc;
      final Items next = rest instanceof Empty ? z : new Items(i, rest, z);
      return new SLine(i, be(w, i, next));
    } else if (d instanceof FlatAlt) {
      return be(w, k, new Items(i, ((FlatAlt) d).primary, z));
    } else if (d instanceof Union) {
      final SimpleDoc wide = be(w, k, new Items(i, ((Union) d).wide, z));
      if (fits(w - k, wide)) {
        return wide;
      }
      return be(w, k, new Items(i, ((Union) d).narrow, z));
    } else if (d instanceof Column) {
      return be(w, k, new Items(i, ((Column) d).fn.apply(k), z));
    } else if (d instanceof Nesting) {
      return be(w, k, new Items(i, ((Nesting) d).fn.apply(i), z));
    } else {
      throw new AssertionError("unknown Doc: " + d);
    }
  }

  private static SimpleDoc best(int w, int k, Doc doc) {
    return be(w, k, new Items(0, doc, null));
  }

  /**
   * An entry in the work list for the layout algorithm. Immutable linked list
   * so that the list can be shared across Union branches.
   */
  private static final class Items {
    final int indent;
    final Doc doc;
    final Items next;

    Items(int indent, Doc doc, Items next) {
      this.indent = indent;
      this.doc = doc;
      this.next = next;
    }
  }

  /** Renders a SimpleDoc to a String. */
  private static String display(SimpleDoc doc) {
    final StringBuilder b = new StringBuilder();
    SimpleDoc d = doc;
    for (; ; ) {
      if (d instanceof SEmpty) {
        return b.toString();
      } else if (d instanceof SText) {
        b.append(((SText) d).text);
        d = ((SText) d).rest;
      } else if (d instanceof SLine) {
        b.append('\n');
        for (int j = 0; j < ((SLine) d).indent; j++) {
          b.append(' ');
        }
        d = ((SLine) d).rest;
      } else {
        throw new AssertionError();
      }
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
