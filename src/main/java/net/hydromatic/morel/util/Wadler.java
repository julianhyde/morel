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

import static net.hydromatic.morel.util.Static.spaces;

import java.util.*;

/**
 * Implementation of Wadler's "A Prettier Printer" algorithm in Java Based on
 * the paper "A prettier printer" by Philip Wadler
 */
public class Wadler {
  private Wadler() {}

  /** Abstract representation of a document. */
  public abstract static class Doc {
    public abstract String render(int width);

    /** Converts all line breaks to spaces. */
    Doc flatten() {
      return this;
    }
  }

  /** Document that is a constant piece of text. */
  static class Text extends Doc {
    private final String text;

    Text(String text) {
      this.text = text;
    }

    @Override
    public String render(int width) {
      return text;
    }

    @Override
    public String toString() {
      return "Text(\"" + text + "\")";
    }
  }

  /** Document that is a line break. */
  static class Line extends Doc {
    @Override
    public String render(int width) {
      return "\n";
    }

    @Override
    Doc flatten() {
      return text(" ");
    }

    @Override
    public String toString() {
      return "Line";
    }
  }

  /** Document that concatenates two documents. */
  static class Concat extends Doc {
    private final Doc left;
    private final Doc right;

    Concat(Doc left, Doc right) {
      this.left = left;
      this.right = right;
    }

    @Override
    public String render(int width) {
      return left.render(width) + right.render(width);
    }

    @Override
    Doc flatten() {
      return new Concat(left.flatten(), right.flatten());
    }

    @Override
    public String toString() {
      return "Concat(" + left + ", " + right + ")";
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
    public String render(int width) {
      return addIndentation(doc.render(width), indent);
    }

    @Override
    Doc flatten() {
      return doc.flatten();
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
    Doc flatten() {
      // No need to flatten the left side, as it is already flat.
      return left;
    }

    @Override
    public String render(int width) {
      // Try the flat version first
      String flatResult = left.render(width);
      if (fits(flatResult, width)) {
        return flatResult;
      } else {
        return right.render(width);
      }
    }

    private boolean fits(String text, int width) {
      String[] lines = text.split("\n");
      for (String line : lines) {
        if (line.length() > width) {
          return false;
        }
      }
      return true;
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
    return new Line();
  }

  /** Concatenates multiple documents into one. */
  public static Doc concat(Doc... docs) {
    return concat(Arrays.asList(docs));
  }

  /** Concatenates a list of multiple documents into one. */
  public static Doc concat(List<Doc> docs) {
    if (docs.isEmpty()) {
      return text("");
    }

    Doc result = docs.get(0);
    for (int i = 1; i < docs.size(); i++) {
      result = new Concat(result, docs.get(i));
    }
    return result;
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
      return text("");
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
