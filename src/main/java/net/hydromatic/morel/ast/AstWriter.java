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
package net.hydromatic.morel.ast;

import com.google.common.collect.Lists;
import com.google.common.primitives.UnsignedLong;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.parse.Parsers;
import net.hydromatic.morel.util.Lindig;
import net.hydromatic.morel.util.Lindig.Doc;

/**
 * Context for writing an AST out as a string.
 *
 * <p>Builds a {@link Doc} rather than a string, so that the layout can be
 * chosen to fit a width. A run of characters with no break in it accumulates in
 * {@code pending} and becomes one {@code text} when a break arrives, which
 * keeps the document small and lets the hundred-odd {@code unparse} methods go
 * on appending characters as they always did.
 *
 * <p>Until a break point is offered -- a {@link Lindig#group} around something
 * that may be laid out either way -- the document is a flat concatenation, and
 * renders the same at every width.
 */
public class AstWriter {
  /**
   * The width a plan is laid out within where the session does not say -- a
   * test, an assertion message. Matches the default of the {@code lineWidth}
   * property, which is what says it everywhere else.
   */
  public static final int DEFAULT_WIDTH = 79;

  /** Characters written since the last break. */
  private final StringBuilder pending = new StringBuilder();

  private final List<Doc> docs = new ArrayList<>();
  private final List<Frame> stack = new ArrayList<>();
  private final boolean parenthesize;

  /**
   * Whether nothing but spaces has been written since the last line break.
   * Tracked as it is written, because a document does not have a column until
   * it is rendered.
   */
  private boolean lineStart = true;

  public AstWriter() {
    this(false);
  }

  /**
   * Creates a writer that wraps every operator application in parentheses,
   * which makes an expression's structure explicit.
   */
  public AstWriter(boolean parenthesize) {
    this.parenthesize = parenthesize;
  }

  /** Moves the characters written so far into the document. */
  private void flush() {
    if (pending.length() > 0) {
      docs.add(Lindig.text(pending.toString()));
      pending.setLength(0);
    }
  }

  /**
   * The width to lay the document out within.
   *
   * <p>Unbounded unless a subclass says otherwise, so that a plain writer --
   * the one behind {@code toString}, an error message, a test matcher --
   * produces the one layout a document with no break point has. Plan text is
   * the thing with a width.
   */
  protected int width() {
    return Integer.MAX_VALUE;
  }

  /**
   * Starts a region that may be laid out on one line or broken.
   *
   * <p>Breaks offered inside it (by {@link #softBreak}) are taken only if what
   * the region holds does not fit, and a broken line is indented {@code indent}
   * from where the region began.
   */
  public AstWriter startGroup(int indent) {
    return start(indent, true);
  }

  /**
   * Starts a region that is indented but not grouped.
   *
   * <p>A group decides for itself whether to break; a nest only says where a
   * break lands. Use this where several breaks must be taken together but at
   * different indents -- the `let`, `in` and `end` of a `let` are one decision,
   * and the two parts they enclose are indented.
   */
  public AstWriter startNest(int indent) {
    return start(indent, false);
  }

  private AstWriter start(int indent, boolean group) {
    flush();
    stack.add(new Frame(docs.size(), indent, group));
    return this;
  }

  /** Ends the region that {@link #startGroup} began. */
  public AstWriter endGroup() {
    return end();
  }

  /** Ends the region that {@link #startNest} began. */
  public AstWriter endNest() {
    return end();
  }

  private AstWriter end() {
    flush();
    final Frame frame = stack.remove(stack.size() - 1);
    final List<Doc> inner =
        new ArrayList<>(docs.subList(frame.start, docs.size()));
    docs.subList(frame.start, docs.size()).clear();
    final Doc doc = Lindig.nest(frame.indent, Lindig.hcat(inner));
    docs.add(frame.group ? Lindig.group(doc) : doc);
    return this;
  }

  /**
   * Offers a break: a space if the enclosing group fits on one line, a line
   * break and the group's indent if it does not.
   */
  public AstWriter softBreak() {
    flush();
    docs.add(Lindig.LINE);
    return this;
  }

  /** An open {@link #startGroup} region. */
  private static class Frame {
    final int start;
    final int indent;
    final boolean group;

    Frame(int start, int indent, boolean group) {
      this.start = start;
      this.indent = indent;
      this.group = group;
    }
  }

  /**
   * {@inheritDoc}
   *
   * <p>Returns the ML source code generated by this writer.
   */
  @Override
  public String toString() {
    flush();
    return Lindig.render(width(), Lindig.hcat(docs));
  }

  /**
   * Returns whether a relational node should print the collection type of every
   * line, as {@code Sys.planEx} prints it.
   */
  public boolean withTypes() {
    return false;
  }

  /**
   * The longest type moniker that a plan prints in full. A longer one is
   * replaced by a reference, {@code t[1]}, and printed once in the legend.
   *
   * <p>A character count rather than a rule about the type's shape, because
   * three implementations must agree on it and they already agree on the
   * moniker's text. It sits above {@code int option list} (15) and {@code
   * {a:int, b:int} list} (19), and below a three-field record (41).
   */
  public static final int MAX_TYPE_LENGTH = 24;

  /**
   * Returns whether a relation is broken out onto lines of its own, as {@code
   * Sys.planEx} prints it, rather than nested in an expression like any other
   * application.
   */
  public boolean treeMode() {
    return false;
  }

  /**
   * Registers a relation that cannot print where it stands, and returns the
   * reference that prints instead -- {@code r[1]}, {@code r[2]}, and so on.
   *
   * <p>A relational operator is the first non-whitespace on its line. A
   * relation reached from inside an expression is not, so it is broken out and
   * defined below the tree.
   */
  public String relRef(Core.Rel rel) {
    throw new UnsupportedOperationException("not in tree mode");
  }

  /**
   * Returns the reference for the {@code i}th broken-out relation, with the
   * variables it reads from outside itself -- {@code r$0[v$0, v$1]}. The same
   * text stands at the reference and at the definition, so that a fragment that
   * carries a variable into a fragment nested inside it says so.
   */
  public String relHeader(int i) {
    throw new UnsupportedOperationException("not in tree mode");
  }

  /** Returns how many relations {@link #relRef} has broken out. */
  public int relDefCount() {
    return 0;
  }

  /** Returns the {@code i}th relation that {@link #relRef} broke out. */
  public Core.Rel relDef(int i) {
    throw new UnsupportedOperationException("not in tree mode");
  }

  /**
   * Returns whether nothing but spaces has been written since the last line
   * break, so that what comes next is the first non-whitespace on its line.
   */
  public boolean atLineStart() {
    return lineStart;
  }

  /** Appends an identifier, quoting it if it needs quoting. */
  private void appendQuoted(String name) {
    final StringBuilder b = new StringBuilder();
    Parsers.appendId(b, name);
    raw(b.toString());
  }

  /**
   * Returns how a type is written where a plan line names it: the moniker
   * itself if it is short, otherwise a reference such as {@code t[1]} that
   * {@link #typeLegend} expands.
   *
   * <p>Whether a type is converted into a reference is based on a simple
   * heuristic: whether the moniker is longer than {@link #MAX_TYPE_LENGTH}.
   *
   * <p>A better rule would allow monikers to share components with other
   * monikers. For example,
   *
   * <pre>
   *   t[0] = {a:int, b:bool} list
   *   t[1] = {a:int, b:bool} option bag
   * </pre>
   *
   * <p>could be shortened by introducing an intermediate type {@code t[2]}:
   *
   * <pre>
   *   t[0] = t[2] list
   *   t[1] = t[2] option bag
   *   t[2] = {a:int, b:bool}
   * </pre>
   *
   * <p>Choosing the set of common types that minimizes the total length of the
   * {@link #typeLegend()} is the <a
   * href="https://en.wikipedia.org/wiki/Smallest_grammar_problem">Smallest
   * grammar problem</a>, which is NP-complete.
   */
  public String typeRef(String moniker) {
    return moniker;
  }

  /**
   * Returns the legend for the references {@link #typeRef} has handed out: one
   * line per distinct type, in the order it was first encountered, or the empty
   * string if there are none.
   */
  public String typeLegend() {
    return "";
  }

  /** Appends a string to the output. */
  public AstWriter append(String s) {
    int i;
    while ((i = s.indexOf('\n')) >= 0) {
      pending.append(s, 0, i);
      flush();
      docs.add(Lindig.HARD_LINE);
      lineStart = true;
      s = s.substring(i + 1);
    }
    raw(s);
    return this;
  }

  /** Appends characters that contain no line break. */
  private void raw(String s) {
    pending.append(s);
    for (int i = 0; i < s.length(); i++) {
      if (s.charAt(i) != ' ') {
        lineStart = false;
        break;
      }
    }
  }

  /**
   * Appends a name to the output verbatim (no quoting). Use for type names,
   * type variables, and keywords that unparse themselves (e.g. {@code
   * ordinal}); use {@link #idQuoted} for variable identifiers and record
   * labels, which must be quoted if they are reserved words.
   */
  public AstWriter id(String name) {
    raw(name);
    return this;
  }

  /**
   * Appends an ordinal-qualified identifier to the output, verbatim.
   *
   * <p>Prints "v" for {@code id("v", 0)}, "v_1" for {@code id("v", 1)}, and so
   * forth.
   */
  public AstWriter id(String name, int i) {
    raw(name);
    if (i > 0) {
      raw("_" + i);
    }
    return this;
  }

  /**
   * Appends a variable identifier or record label, quoting it with back-ticks
   * if it is a reserved word (or otherwise requires quoting), so that, for
   * example, a variable named {@code left} round-trips.
   */
  public AstWriter idQuoted(String name) {
    appendQuoted(name);
    return this;
  }

  /** Appends an ordinal-qualified variable identifier, quoting if necessary. */
  public AstWriter idQuoted(String name, int i) {
    if (i == 0) {
      appendQuoted(name);
    } else {
      // "name_i" is never a reserved word, so it does not need quoting.
      raw(name + "_" + i);
    }
    return this;
  }

  /** Appends a call to an infix operator. */
  public AstWriter infix(int left, AstNode a0, Op op, AstNode a1, int right) {
    if (op == Op.APPLY && a0.op == Op.ID) {
      if (a0 instanceof Ast.Id) {
        final Op op2 = Op.BY_OP_NAME.get(((Ast.Id) a0).name);
        if (op2 != null && op2.left > 0) {
          final List<Ast.Exp> args = ((Ast.Tuple) a1).args;
          final Ast.InfixCall call =
              new Ast.InfixCall(Pos.ZERO, op2, args.get(0), args.get(1));
          return call.unparse(this, left, right);
        }
      }
      if (a0 instanceof Core.Id) {
        // TODO: obsolete Core.Id for these purposes. The operator should
        // be a function literal, and we would use a reverse mapping to
        // figure out which built-in operator it implements, and whether it
        // is infix (e.g. "+") or in a namespace (e.g. "#translate String")
        final Op op2 = Op.BY_OP_NAME.get(((Core.Id) a0).idPat.name);
        if (op2 != null && op2.left > 0) {
          final List<Core.Exp> args = ((Core.Tuple) a1).args;
          return infix(left, args.get(0), op2, args.get(1), right);
        }
      }
    }
    final boolean p = parenthesize || left > op.left || op.right < right;
    if (p) {
      raw("(");
      left = right = 0;
    }
    // `andalso` and `orelse` chain, and a plan's conditions are mostly made
    // of them, so they are where a long line is worth breaking. Each is a
    // group of its own, so an outer one breaks before an inner one does, and
    // a condition that fits stays on its line.
    final boolean breakable = op == Op.ANDALSO || op == Op.ORELSE;
    if (breakable) {
      startGroup(0);
    }
    append(a0, left, op.left);
    if (breakable) {
      softBreak();
      append(op.padded.substring(1));
    } else {
      append(op.padded);
    }
    append(a1, op.right, right);
    if (breakable) {
      endGroup();
    }
    if (p) {
      raw(")");
    }
    return this;
  }

  /** Appends a call to an prefix operator. */
  public AstWriter prefix(int left, Op op, AstNode a, int right) {
    final boolean p = parenthesize || left > op.left || op.right < right;
    if (p) {
      raw("(");
      right = 0;
    }
    append(op.padded);
    a.unparse(this, op.right, right);
    if (p) {
      raw(")");
    }
    return this;
  }

  /** Appends a call to a binary operator (e.g. "val ... = ..."). */
  public AstWriter binary(
      String left, AstNode a0, String mid, AstNode a1, int right) {
    append(left);
    a0.unparse(this, 0, 0);
    append(mid);
    a1.unparse(this, 0, right);
    return this;
  }

  /** Appends a call to a binary operator (e.g. "let ... in ... end"). */
  public AstWriter binary(
      String left, AstNode a0, String mid, AstNode a1, String right) {
    append(left);
    a0.unparse(this, 0, 0);
    append(mid);
    a1.unparse(this, 0, 0);
    append(right);
    return this;
  }

  /** Appends a parse tree node. */
  public AstWriter append(AstNode node, int left, int right) {
    final boolean p = parenthesize || node.op.wraps(left, right);
    if (p) {
      raw("(");
      left = right = 0;
    }
    node.unparse(this, left, right);
    if (p) {
      raw(")");
    }
    return this;
  }

  /** Appends a list of parse tree nodes. */
  public AstWriter appendAll(
      Iterable<? extends AstNode> nodes, int left, Op op, int right) {
    @SuppressWarnings("unchecked")
    final List<AstNode> nodeList =
        nodes instanceof List ? (List) nodes : Lists.newArrayList(nodes);
    for (int i = 0; i < nodeList.size(); i++) {
      final AstNode node = nodeList.get(i);
      final int thisLeft = i == 0 ? left : op.left;
      final int thisRight = i == nodeList.size() - 1 ? right : op.right;
      if (i > 0) {
        append(op.padded);
      }
      append(node, thisLeft, thisRight);
    }
    return this;
  }

  /** Appends a list of parse tree nodes separated by {@code sep}. */
  public AstWriter appendAll(Iterable<? extends AstNode> list, String sep) {
    return appendAll(list, "", sep, "");
  }

  /**
   * Appends a list of parse tree nodes separated by {@code sep}, and also with
   * prefix and suffix: {@code start node0 sep node1 ... sep nodeN end}.
   */
  public AstWriter appendAll(
      Iterable<? extends AstNode> list, String start, String sep, String end) {
    return appendAll(list, start, sep, end, "");
  }

  /**
   * Appends a list of parse tree nodes separated by {@code sep}, and also with
   * prefix and suffix: {@code start node0 sep node1 ... sep nodeN end}.
   */
  public AstWriter appendAll(
      Iterable<? extends AstNode> list,
      String start,
      String sep,
      String end,
      String empty) {
    String s = start;
    int i = 0;
    for (AstNode node : list) {
      ++i;
      append(s);
      s = sep;
      append(node, 0, 0);
    }
    if (i == 0 && empty != null) {
      append(empty);
    } else {
      append(end);
    }
    return this;
  }

  public AstWriter appendLiteral(Comparable value) {
    if (value instanceof String) {
      append("\"")
          .append(((String) value).replace("\\", "\\\\").replace("\"", "\\\""))
          .append("\"");
    } else if (value instanceof UnsignedLong) {
      // A word, e.g. "0wxFF". Like Standard ML, print in hexadecimal.
      append("0wx")
          .append(
              Long.toUnsignedString(((UnsignedLong) value).longValue(), 16)
                  .toUpperCase(Locale.ROOT));
    } else if (value instanceof Character) {
      switch ((char) value) {
        case '"':
          append("#\"\\\"\"");
          break;
        case '\\':
          append("#\"\\\\\"");
          break;
        default:
          append("#\"").append(value.toString()).append("\"");
          break;
      }
    } else if (value instanceof BigDecimal) {
      BigDecimal c = (BigDecimal) value;
      if (c.compareTo(BigDecimal.ZERO) < 0) {
        append("~");
        c = c.negate();
      }
      append(c.toString().replace("+", ""));
    } else if (value instanceof BuiltIn) {
      final BuiltIn builtIn = (BuiltIn) value;
      if (builtIn == BuiltIn.BOOL_NOT) {
        // The "not" prefix operator prints unqualified, not as "#not Bool".
        append("not");
      } else if (!builtIn.structure.equals("Top")
          && !builtIn.structure.equals("$")) {
        // E.g. "#find List" for the List.find function
        append("#")
            .append(builtIn.mlName)
            .append(" ")
            .append(builtIn.structure);
      } else {
        append(builtIn.mlName);
      }
    } else {
      append(value.toString());
    }
    return this;
  }
}

// End AstWriter.java
