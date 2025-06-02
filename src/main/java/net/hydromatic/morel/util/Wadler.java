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

import static com.google.common.base.Preconditions.checkArgument;
import static net.hydromatic.morel.util.Static.spaces;

import com.google.common.collect.ImmutableList;
import java.util.*;

/**
 * Implementation of Wadler's "A Prettier Printer" algorithm in Java Based on
 * the paper "A prettier printer" by Philip Wadler
 */
public class Wadler {
  private Wadler() {}

  /** Singleton instance of a line break document. */
  private static final Line LINE = new Line();

  /** The empty document. */
  private static final Doc EMPTY_TEXT = new Text("");

  /** A document that is a single space. */
  private static final Doc SPACE = new Text(" ");

  /** Abstract representation of a document. */
  public abstract static class Doc {
    /** Outputs this document a string where no line exceeds the given width. */
    public final String render(int width) {
      final StringBuilder b = new StringBuilder();
      render(b, width);
      return b.toString();
    }

    /**
     * Outputs this document to a StringBuilder, with no line exceeding the
     * given width.
     */
    abstract void render(StringBuilder b, int width);

    /** Converts all line breaks to spaces. */
    Doc flatten() {
      final List<Doc> flatDocs = new ArrayList<>();
      flattenTo(flatDocs);
      return concat(flatDocs);
    }

    /**
     * Appends this document to a list of documents where all line breaks have
     * been converted to spaces.
     */
    void flattenTo(List<Doc> flatDocs) {
      flatDocs.add(this);
    }

    /** Appends all documents inside this document to a list. */
    void collect(List<Doc> docs) {
      docs.add(this);
    }
  }

  /** Document that is a constant piece of text. */
  static class Text extends Doc {
    private final String text;

    Text(String text) {
      this.text = text;
    }

    @Override
    void render(StringBuilder b, int width) {
      b.append(text);
    }

    @Override
    Doc flatten() {
      return this;
    }

    @Override
    public String toString() {
      return "Text(\"" + text + "\")";
    }
  }

  /** Document that is a line break. */
  static class Line extends Doc {
    @Override
    void render(StringBuilder b, int width) {
      b.append("\n");
    }

    @Override
    Doc flatten() {
      return SPACE;
    }

    @Override
    void flattenTo(List<Doc> flatDocs) {
      flatDocs.add(SPACE);
    }

    @Override
    public String toString() {
      return "Line";
    }
  }

  /** Document that concatenates two documents. */
  static class Concat extends Doc {
    private final List<Doc> docs;

    Concat(List<Doc> docs) {
      this.docs = ImmutableList.copyOf(docs);
      checkArgument(docs.size() >= 2);
    }

    @Override
    void render(StringBuilder b, int width) {
      for (Doc doc : docs) {
        doc.render(b, width);
      }
    }

    @Override
    Doc flatten() {
      // Slightly more efficient than base method. After flattening, checks
      // whether the flattened list is the same as the original list, and if so,
      // avoids the cost of creating a new Concat. We don't need to check
      // whether flatDocs has 0 or 1 element, because this is not possible.
      final List<Doc> flatDocs = new ArrayList<>();
      flattenTo(flatDocs);
      if (flatDocs.equals(docs)) {
        return this;
      }
      return new Concat(flatDocs);
    }

    @Override
    void flattenTo(List<Doc> flatDocs) {
      for (Doc doc : docs) {
        doc.flattenTo(flatDocs);
      }
    }

    @Override
    public String toString() {
      return "Concat" + docs;
    }

    @Override
    void collect(List<Doc> docs) {
      this.docs.forEach(d -> d.collect(docs));
    }
  }

  /** Document that represents a line break with indentation. */
  static class Nest extends Doc {
    private final int indent;
    private final Doc doc;

    Nest(int indent, Doc doc) {
      this.indent = indent;
      this.doc = doc;
    }

    @Override
    void render(StringBuilder b, int width) {
      final int start = b.length();
      doc.render(b, width);
      final String s = b.substring(start);
      final String s2 = addIndentation(s, indent);
      b.replace(start, b.length(), s2);
    }

    @Override
    void flattenTo(List<Doc> flatDocs) {
      doc.flattenTo(flatDocs);
    }

    private String addIndentation(String text, int indent) {
      String indentStr = spaces(indent);
      return text.replaceAll("\n", "\n" + indentStr);
    }

    @Override
    public String toString() {
      return "Nest(" + indent + ", " + doc + ")";
    }
  }

  /**
   * Document that represents a union of two alternative layouts.
   *
   * <p>Key to Wadler's algorithm.
   */
  static class Union extends Doc {
    private final Doc left; // "flat" version
    private final Doc right; // "broken" version

    Union(Doc left, Doc right) {
      this.left = left;
      this.right = right;
    }

    @Override
    void flattenTo(List<Doc> flatDocs) {
      // No need to flatten the left side, as it is already flat.
      left.collect(flatDocs);
    }

    @Override
    void render(StringBuilder b, int width) {
      // Try the flat version first.
      final int start = b.length();
      left.render(b, width);
      if (!fits(b, start, width)) {
        // It doesn't fit. Use the broken version.
        b.setLength(start);
        right.render(b, width);
      }
    }

    /**
     * Returns false if any line in a StringBuilder after {@code start} exceeds
     * the given width.
     */
    private boolean fits(StringBuilder b, int start, int width) {
      for (; ; ) {
        int next = b.indexOf("\n", start);
        if (next < 0) {
          int lineLength = b.length() - start;
          return lineLength <= width;
        }
        int lineLength = next - start;
        if (lineLength > width) {
          return false;
        }
        start = next + 1; // Move to start of next line
      }
    }

    @Override
    public String toString() {
      return "Union(" + left + ", " + right + ")";
    }
  }

  /** Creates a document that is a constant piece of text. */
  public static Doc text(String s) {
    return new Text(s);
  }

  /** Creates a document that is a line break. */
  public static Doc line() {
    return LINE;
  }

  /** Concatenates multiple documents into one. */
  public static Doc concat(Doc... docs) {
    return concat(Arrays.asList(docs));
  }

  /** Concatenates a list of multiple documents into one. */
  public static Doc concat(List<Doc> docs) {
    switch (docs.size()) {
      case 0:
        return EMPTY_TEXT;
      case 1:
        return docs.get(0);
      default:
        List<Doc> flattened = new ArrayList<>();
        for (Doc doc : docs) {
          doc.collect(flattened);
        }
        return new Concat(flattened);
    }
  }

  /** Creates a document that represents a line break with indentation. */
  public static Doc nest(int indent, Doc doc) {
    return new Nest(indent, doc);
  }

  /** Creates a union between flat and broken versions of a document. */
  public static Doc group(Doc doc) {
    return new Union(doc.flatten(), doc);
  }

  /** Joins documents with separators. */
  public static Doc join(Doc separator, Doc... docs) {
    return join(separator, Arrays.asList(docs));
  }

  /** Joins a list of documents with separators. */
  public static Doc join(Doc separator, List<Doc> docs) {
    if (docs.isEmpty()) {
      return EMPTY_TEXT;
    }
    if (docs.size() == 1) {
      return docs.get(0);
    }

    final List<Doc> result = new ArrayList<>();
    for (Doc doc : docs) {
      if (!result.isEmpty()) {
        result.add(separator);
      }
      result.add(doc);
    }
    return concat(result);
  }
}

// End Wadler.java
