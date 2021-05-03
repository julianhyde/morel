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

import org.apache.calcite.util.Util;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;

import net.hydromatic.morel.eval.Applicable;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.DataType;
import net.hydromatic.morel.type.FnType;
import net.hydromatic.morel.type.ListType;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.RecordLikeType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.util.Ord;
import net.hydromatic.morel.util.Pair;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.ObjIntConsumer;
import javax.annotation.Nullable;

import static net.hydromatic.morel.ast.Pos.ZERO;
import static net.hydromatic.morel.type.RecordType.ORDERING;

import static java.util.Objects.requireNonNull;

/** Core expressions.
 *
 * <p>Many expressions are sub-classes of similarly named expressions in
 * {@link Ast}. This class functions as a namespace, so that we can keep the
 * class names short.
 */
// TODO: remove 'parse tree for...' from all the comments below
public class Core {
  private Core() {}

  /** Object that has a type. */
  // TODO: remove?
  public interface Typed {
    /** Returns the type of this expression. */
    Type type();

    Op op();
  }

  /** Abstract base class of Core nodes. */
  abstract static class BaseNode extends AstNode {
    BaseNode(Op op) {
      super(ZERO, op);
    }

    @Override public AstNode accept(Shuttle shuttle) {
      throw new UnsupportedOperationException();
    }

    @Override public void accept(Visitor visitor) {
      throw new UnsupportedOperationException("cannot accept " + getClass());
    }
  }

  /** Base class for a pattern.
   *
   * <p>For example, "x" in "val x = 5" is a {@link IdPat};
   * the "(x, y) in "val (x, y) = makePair 1 2" is a {@link TuplePat}. */
  public abstract static class Pat extends BaseNode {
    public final Type type;

    Pat(Op op, Type type) {
      super(op);
      this.type = requireNonNull(type);
    }

    /** Returns the type. */
    public Type type() {
      return type;
    }

    public void forEachArg(ObjIntConsumer<Pat> action) {
      // no args
    }

//    @Override public abstract Pat accept(AstShuttle shuttle);

    public void visit(Consumer<Pat> consumer) {
      consumer.accept(this);
      forEachArg((arg, i) -> arg.visit(consumer));
    }
  }

  /** Named pattern.
   *
   * @see Ast.Id */
  public static class IdPat extends Pat {
    public final String name;

    IdPat(Type type, String name) {
      super(Op.ID_PAT, type);
      this.name = name;
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      return w.id(name);
    }
  }

  /** Literal pattern, the pattern analog of the {@link Literal} expression.
   *
   * <p>For example, "0" in "fun fact 0 = 1 | fact n = n * fact (n - 1)".*/
  @SuppressWarnings("rawtypes")
  public static class LiteralPat extends Pat {
    public final Comparable value;

    LiteralPat(Op op, Type type, Comparable value) {
      super(op, type);
      this.value = requireNonNull(value);
      Preconditions.checkArgument(op == Op.BOOL_LITERAL_PAT
          || op == Op.CHAR_LITERAL_PAT
          || op == Op.INT_LITERAL_PAT
          || op == Op.REAL_LITERAL_PAT
          || op == Op.STRING_LITERAL_PAT);
    }

    @Override public int hashCode() {
      return value.hashCode();
    }

    @Override public boolean equals(Object o) {
      return o == this
          || o instanceof LiteralPat
          && this.value.equals(((LiteralPat) o).value);
    }

//    public Pat accept(AstShuttle shuttle) {
//      return shuttle.visit(this);
//    }
//
//    @Override public void accept(Visitor visitor) {
//      visitor.visit(this);
//    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      return w.appendLiteral(value);
    }
  }

  /** Wildcard pattern.
   *
   * <p>For example, "{@code _}" in "{@code fn foo _ => 42}". */
  public static class WildcardPat extends Pat {
    WildcardPat(Type type) {
      super(Op.WILDCARD_PAT, type);
    }

    @Override public int hashCode() {
      return "_".hashCode();
    }

    @Override public boolean equals(Object o) {
      return o instanceof WildcardPat;
    }

//    public Pat accept(AstShuttle shuttle) {
//      return shuttle.visit(this);
//    }
//
//    @Override public void accept(Visitor visitor) {
//      visitor.visit(this);
//    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      return w.append("_");
    }
  }

//  /** Pattern build from an infix operator applied to two patterns. */
  // TODO: replace with another composite pattern
//  public static class InfixPat extends Pat {
//    public final Pat p0;
//    public final Pat p1;
//
//    InfixPat(Op op, Type type, Pat p0, Pat p1) {
//      super(op, type);
//      this.p0 = requireNonNull(p0);
//      this.p1 = requireNonNull(p1);
//    }
//
//    public Pat accept(AstShuttle shuttle) {
//      return shuttle.visit(this);
//    }
//
//    @Override public void accept(Visitor visitor) {
//      visitor.visit(this);
//    }

//    @Override public void forEachArg(ObjIntConsumer<Pat> action) {
//      action.accept(p0, 0);
//      action.accept(p1, 1);
//    }

//    @Override AstWriter unparse(AstWriter w, int left, int right) {
//      return w.infix(left, p0, op, p1, right);
//    }
//
//    /** Creates a copy of this {@code InfixPat} with given contents
//     * and same operator,
//     * or {@code this} if the contents are the same. */
//    public InfixPat copy(Pat p0, Pat p1) {
//      return this.p0.equals(p0)
//          && this.p1.equals(p1)
//          ? this
//          : ast.infixPat(pos, op, p0, p1);
//    }
//  }

  /** Type constructor pattern with an argument.
   *
   * <p>For example, in "fun nvl NIL = 0 | OPTION x = x",
   * "OPTION x" is a type constructor pattern that binds "x";
   * and "NIL" is a type constructor pattern whose {@link #pat} is null.
   *
   * @see Con0Pat */
  public static class ConPat extends Pat {
    public final String tyCon;
    public final Pat pat;

    /** Mostly-private constructor.
     *
     * <p>Exposed so that "op ::" (cons) can supply a differnt {@link Op}
     * value. The "list" datatype is not represented the same as other
     * datatypes, and the separate "op" value allows us to deconstruct it in a
     * different way. */
    ConPat(Op op, Type type, String tyCon, Pat pat) {
      super(op, type);
      this.tyCon = requireNonNull(tyCon);
      this.pat = requireNonNull(pat);
      Preconditions.checkArgument(op == Op.CON_PAT || op == Op.CONS_PAT);
    }

    ConPat(Type type, String tyCon, Pat pat) {
      this(Op.CON_PAT, type, tyCon, pat);
    }

//    public Pat accept(AstShuttle shuttle) {
//      return shuttle.visit(this);
//    }
//
//    @Override public void accept(Visitor visitor) {
//      visitor.visit(this);
//    }

    @Override public void forEachArg(ObjIntConsumer<Pat> action) {
      action.accept(pat, 0);
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      return w.id(tyCon).append("(").append(pat, 0, 0).append(")");
    }

//    /** Creates a copy of this {@code ConPat} with given contents,
//     * or {@code this} if the contents are the same. */
//    public ConPat copy(Id tyCon, Pat pat) {
//      return this.tyCon.equals(tyCon)
//          && this.pat.equals(pat)
//          ? this
//          : ast.conPat(pos, tyCon, pat);
//    }
  }

  /** Type constructor pattern with no argument.
   *
   * <p>For example, in "fun nvl NIL = 0 | OPTION x = x",
   * "NIL" is a zero-arg type constructor pattern.
   *
   * @see ConPat */
  public static class Con0Pat extends Pat {
    public final String tyCon;

    Con0Pat(DataType type, String tyCon) {
      super(Op.CON0_PAT, type);
      this.tyCon = requireNonNull(tyCon);
    }

    @Override public DataType type() {
      return (DataType) type;
    }

//    public Pat accept(AstShuttle shuttle) {
//      return shuttle.visit(this);
//    }
//
//    @Override public void accept(Visitor visitor) {
//      visitor.visit(this);
//    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      return w.id(tyCon);
    }

//    /** Creates a copy of this {@code Con0Pat} with given contents,
//     * or {@code this} if the contents are the same. */
//    public Con0Pat copy(Id tyCon) {
//      return this.tyCon.equals(tyCon)
//          ? this
//          : ast.con0Pat(pos, tyCon);
//    }
  }

  /** Tuple pattern, the pattern analog of the {@link Tuple} expression.
   *
   * <p>For example, "(x, y)" in "fun sum (x, y) = x + y". */
  public static class TuplePat extends Pat {
    public final List<Pat> args;

    TuplePat(Type type, ImmutableList<Pat> args) {
      super(Op.TUPLE_PAT, type);
      this.args = requireNonNull(args);
    }

//    public Pat accept(AstShuttle shuttle) {
//      return shuttle.visit(this);
//    }
//
//    @Override public void accept(Visitor visitor) {
//      visitor.visit(this);
//    }

    @Override public void forEachArg(ObjIntConsumer<Pat> action) {
      Ord.forEach(args, action);
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      w.append("(");
      forEachArg((arg, i) -> w.append(i == 0 ? "" : ", ").append(arg, 0, 0));
      return w.append(")");
    }

//    /** Creates a copy of this {@code TuplePat} with given contents,
//     * or {@code this} if the contents are the same. */
//    public TuplePat copy(List<Pat> args) {
//      return this.args.equals(args)
//          ? this
//          : ast.tuplePat(pos, args);
//    }
  }

  /** List pattern, the pattern analog of {@link ListExp}.
   *
   * <p>For example, "[x, y]" in "fun sum [x, y] = x + y". */
  public static class ListPat extends Pat {
    public final List<Pat> args;

    ListPat(Type type, ImmutableList<Pat> args) {
      super(Op.LIST_PAT, type);
      this.args = requireNonNull(args);
    }

//    public Pat accept(AstShuttle shuttle) {
//      return shuttle.visit(this);
//    }
//
//    @Override public void accept(Visitor visitor) {
//      visitor.visit(this);
//    }

    @Override public void forEachArg(ObjIntConsumer<Pat> action) {
      Ord.forEach(args, action);
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      w.append("[");
      forEachArg((arg, i) -> w.append(i == 0 ? "" : ", ").append(arg, 0, 0));
      return w.append("]");
    }

//    /** Creates a copy of this {@code ListPat} with given contents,
//     * or {@code this} if the contents are the same. */
//    public ListPat copy(List<Pat> args) {
//      return this.args.equals(args)
//          ? this
//          : ast.listPat(pos, args);
//    }
  }

  /** Record pattern. */
  public static class RecordPat extends Pat {
    public final boolean ellipsis;
    public final SortedMap<String, Pat> args;

    RecordPat(Type type, boolean ellipsis,
        ImmutableSortedMap<String, Pat> args) {
      super(Op.RECORD_PAT, type);
      this.ellipsis = ellipsis;
      this.args = requireNonNull(args);
      Preconditions.checkArgument(args.comparator() == ORDERING);
    }

//    public Pat accept(AstShuttle shuttle) {
//      return shuttle.visit(this);
//    }
//
//    @Override public void accept(Visitor visitor) {
//      visitor.visit(this);
//    }

    @Override public void forEachArg(ObjIntConsumer<Pat> action) {
      Ord.forEach(args.values(), action);
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      w.append("{");
      Ord.forEach(args, (i, k, v) ->
          w.append(i > 0 ? ", " : "").append(k).append(" = ").append(v, 0, 0));
      if (ellipsis) {
        if (!args.isEmpty()) {
          w.append(", ");
        }
        w.append("...");
      }
      return w.append("}");
    }

//    public RecordPat copy(boolean ellipsis, Map<String, ? extends Pat> args) {
//      return this.ellipsis == ellipsis
//          && this.args.equals(args)
//          ? this
//          : ast.recordPat(pos, ellipsis, args);
//    }
  }

//  /** Pattern that is a pattern annotated with a type.
//   *
//   * <p>For example, "x : int" in "val x : int = 5". */
//  public static class AnnotatedPat extends Pat {
//    public final Pat pat;
//    public final Type type;
//
//    AnnotatedPat(Pos pos, Pat pat, Type type) {
//      super(pos, Op.ANNOTATED_PAT);
//      this.pat = pat;
//      this.type = type;
//    }
//
//    public Pat accept(AstShuttle shuttle) {
//      return shuttle.visit(this);
//    }
//
//    @Override public void accept(Visitor visitor) {
//      visitor.visit(this);
//    }
//
//    AstWriter unparse(AstWriter w, int left, int right) {
//      return w.infix(left, pat, op, type, right);
//    }
//
//    @Override public void forEachArg(ObjIntConsumer<Pat> action) {
//      action.accept(pat, 0);
//    }
//
//    /** Creates a copy of this {@code AnnotatedPat} with given contents,
//     * or {@code this} if the contents are the same. */
//    public AnnotatedPat copy(Pat pat, Type type) {
//      return this.pat.equals(pat)
//          && this.type.equals(type)
//          ? this
//          : ast.annotatedPat(pos, pat, type);
//    }
//  }
//
//  /** Base class for parse tree nodes that represent types. */
//  public abstract static class Type extends AstNode {
//    /** Creates a type node. */
//    Type(Pos pos, Op op) {
//      super(pos, op);
//    }
//
//    @Override public abstract Type accept(AstShuttle shuttle);
//  }
//
//  /** Parse tree node of an expression annotated with a type. */
//  public static class AnnotatedExp extends Exp {
//    public final Type type;
//    public final Exp e;
//
//    /** Creates a type annotation. */
//    AnnotatedExp(Pos pos, Type type, Exp e) {
//      super(pos, Op.ANNOTATED_EXP);
//      this.type = requireNonNull(type);
//      this.e = requireNonNull(e);
//    }
//
//    @Override public int hashCode() {
//      return Objects.hash(type, e);
//    }
//
//    @Override public boolean equals(Object obj) {
//      return this == obj
//          || obj instanceof AnnotatedExp
//              && type.equals(((AnnotatedExp) obj).type)
//              && e.equals(((AnnotatedExp) obj).e);
//    }
//
//    public Exp accept(AstShuttle shuttle) {
//      return shuttle.visit(this);
//    }
//
//    @Override public void accept(Visitor visitor) {
//      visitor.visit(this);
//    }
//
//    AstWriter unparse(AstWriter w, int left, int right) {
//      return w.infix(left, e, op, type, right);
//    }
//  }
//
//  /** Parse tree for a named type (e.g. "int" or "(int, string) list"). */
//  public static class NamedType extends Type {
//    public final List<Type> types;
//    public final String name;
//
//    /** Creates a type. */
//    NamedType(Pos pos, ImmutableList<Type> types, String name) {
//      super(pos, Op.NAMED_TYPE);
//      this.types = requireNonNull(types);
//      this.name = requireNonNull(name);
//    }
//
//    @Override public int hashCode() {
//      return Objects.hash(types, name);
//    }
//
//    @Override public boolean equals(Object obj) {
//      return obj == this
//          || obj instanceof NamedType
//          && types.equals(((NamedType) obj).types)
//          && name.equals(((NamedType) obj).name);
//    }
//
//    public Type accept(AstShuttle shuttle) {
//      return shuttle.visit(this);
//    }
//
//    @Override public void accept(Visitor visitor) {
//      visitor.visit(this);
//    }
//
//    AstWriter unparse(AstWriter w, int left, int right) {
//      switch (types.size()) {
//      case 0:
//        return w.append(name);
//      case 1:
//        return w.append(types.get(0), left, op.left)
//            .append(" ").append(name);
//      default:
//        w.append("(");
//        Ord.forEach(types, (type, i) ->
//            w.append(i == 0 ? "" : ", ").append(type, 0, 0));
//        return w.append(") ")
//            .append(name);
//      }
//    }
//  }
//
//  /** Parse tree node of a type variable. */
//  public static class TyVar extends Type {
//    public final String name;
//
//    /** Creates a TyVar. */
//    TyVar(Pos pos, String name) {
//      super(pos, Op.TY_VAR);
//      this.name = requireNonNull(name);
//    }
//
//    @Override public int hashCode() {
//      return name.hashCode();
//    }
//
//    @Override public boolean equals(Object o) {
//      return o == this
//          || o instanceof TyVar
//          && this.name.equals(((TyVar) o).name);
//    }
//
//    public TyVar accept(AstShuttle shuttle) {
//      return shuttle.visit(this);
//    }
//
//    @Override public void accept(Visitor visitor) {
//      visitor.visit(this);
//    }
//
//    AstWriter unparse(AstWriter w, int left, int right) {
//      return w.append(name);
//    }
//  }
//
//  /** Parse tree node of a record type. */
//  public static class RecordType extends Type {
//    public final Map<String, Type> fieldTypes;
//
//    /** Creates a TyVar. */
//    RecordType(Pos pos, ImmutableMap<String, Type> fieldTypes) {
//      super(pos, Op.RECORD_TYPE);
//      this.fieldTypes = requireNonNull(fieldTypes);
//    }
//
//    @Override public int hashCode() {
//      return fieldTypes.hashCode();
//    }
//
//    @Override public boolean equals(Object o) {
//      return o == this
//          || o instanceof RecordType
//          && this.fieldTypes.equals(((RecordType) o).fieldTypes);
//    }
//
//    public RecordType accept(AstShuttle shuttle) {
//      return shuttle.visit(this);
//    }
//
//    @Override public void accept(Visitor visitor) {
//      visitor.visit(this);
//    }
//
//    AstWriter unparse(AstWriter w, int left, int right) {
//      w.append("{");
//      Ord.forEach(fieldTypes, (i, field, type) ->
//          w.append(i > 0 ? ", " : "")
//              .append(field).append(": ").append(type, 0, 0));
//      return w.append("}");
//    }
//  }
//
//  /** Tuple type. */
//  public static class TupleType extends Type {
//    public final List<Type> types;
//
//    TupleType(Pos pos, ImmutableList<Type> types) {
//      super(pos, Op.TUPLE_TYPE);
//      this.types = requireNonNull(types);
//    }
//
//    public Type accept(AstShuttle shuttle) {
//      return shuttle.visit(this);
//    }
//
//    @Override public void accept(Visitor visitor) {
//      visitor.visit(this);
//    }
//
//    AstWriter unparse(AstWriter w, int left, int right) {
//      // "*" is non-associative. Elevate both left and right precedence
//      // to force parentheses if the inner expression is also "*".
//      Ord.forEach(types, (arg, i) ->
//          w.append(i == 0 ? "" : " * ")
//              .append(arg, op.left + 1, op.right + 1));
//      return w;
//    }
//  }
//
//  /** Not really a type, just a way for the parser to represent the type
//   * arguments to a type constructor.
//   *
//   * <p>For example, in {@code datatype foo = Pair of (int, string) list},
//   * {@code (int, string)} is briefly represented as a composite type,
//   * then {@code int} and {@code string} becomes the two type parameters to
//   * the {@code list} {@link NamedType}. */
//  public static class CompositeType extends Type {
//    public final List<Type> types;
//
//    CompositeType(Pos pos, ImmutableList<Type> types) {
//      super(pos, Op.TUPLE_TYPE);
//      this.types = requireNonNull(types);
//    }
//
//    public Type accept(AstShuttle shuttle) {
//      return shuttle.visit(this);
//    }
//
//    @Override public void accept(Visitor visitor) {
//      visitor.visit(this);
//    }
//
//    AstWriter unparse(AstWriter w, int left, int right) {
//      w.append("(");
//      Ord.forEach(types, (arg, i) ->
//          w.append(i == 0 ? "" : ", ").append(arg, 0, 0));
//      return w.append(")");
//    }
//  }
//
//  /** Function type. */
//  public static class FunctionType extends Type {
//    public final Type paramType;
//    public final Type resultType;
//
//    FunctionType(Pos pos, Type paramType, Type resultType) {
//      super(pos, Op.FUNCTION_TYPE);
//      this.paramType = requireNonNull(paramType);
//      this.resultType = requireNonNull(resultType);
//    }
//
//    public Type accept(AstShuttle shuttle) {
//      return shuttle.visit(this);
//    }
//
//    @Override public void accept(Visitor visitor) {
//      visitor.visit(this);
//    }
//
//    AstWriter unparse(AstWriter w, int left, int right) {
//      return w.append(paramType, left, op.left)
//          .append(" -> ")
//          .append(resultType, op.right, right);
//    }
//  }

  /** Base class of core expressions. */
  public abstract static class Exp extends BaseNode {
    public final Type type;

    Exp(Op op, Type type) {
      super(op);
      this.type = requireNonNull(type);
    }

    public void forEachArg(ObjIntConsumer<Exp> action) {
      // no args
    }

    /** Returns the type. */
    public Type type() {
      return type;
    }
  }

  /** Reference to a variable.
   *
   * <p>While {@link Ast.Id} is widely used, and means an occurrence of a name
   * in the parse tree, {@code Id} is much narrower: it means a reference to a
   * value. What would be an {@code Id} in Ast is often a {@link String} in
   * Core; for example, compare {@link Ast.Con0Pat#tyCon}
   * with {@link Con0Pat#tyCon}. */
  public static class Id extends Exp {
    public final String name;

    /** Creates an Id. */
    Id(String name, Type type) {
      super(Op.ID, type);
      this.name = requireNonNull(name);
    }

    @Override public int hashCode() {
      return name.hashCode();
    }

    @Override public boolean equals(Object o) {
      return o == this
          || o instanceof Id
          && this.name.equals(((Id) o).name);
    }

//    public Id accept(AstShuttle shuttle) {
//      return shuttle.visit(this);
//    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    AstWriter unparse(AstWriter w, int left, int right) {
      return w.id(name);
    }
  }

  /** Parse tree node of a record selector. */
  // TODO: remove this class, replace with a call to a special function
  public static class RecordSelector extends Exp {
    public final String name;

    /** The ordinal of the field in the record or tuple that is to be
     * accessed. */
    public final int slot;

    /** Creates a record selector. */
    RecordSelector(FnType fnType, String name) {
      super(Op.RECORD_SELECTOR, fnType);
      this.name = requireNonNull(name);
      Preconditions.checkArgument(!name.startsWith("#"));
      final RecordLikeType recordType = (RecordLikeType) fnType.paramType;
      slot = new ArrayList<>(recordType.argNameTypes().keySet()).indexOf(name);
      Preconditions.checkArgument(slot >= 0, "name [%s] not found in [%s]",
          name, recordType);
    }

    @Override public int hashCode() {
      return name.hashCode();
    }

    @Override public boolean equals(Object o) {
      return o == this
          || o instanceof RecordSelector
          && this.name.equals(((RecordSelector) o).name);
    }

    @Override public FnType type() {
      return (FnType) type;
    }

//    public Exp accept(AstShuttle shuttle) {
//      return shuttle.visit(this);
//    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    AstWriter unparse(AstWriter w, int left, int right) {
      return w.append("#").append(name);
    }
  }

  /** Code of a literal (constant). */
  @SuppressWarnings("rawtypes")
  public static class Literal extends Exp {
    public final Comparable value;

    /** Creates a Literal. */
    Literal(Op op, Type type, Comparable value) {
      super(op, type);
      this.value = value;
    }

    @Override public int hashCode() {
      return value.hashCode();
    }

    @Override public boolean equals(Object o) {
      return o == this
          || o instanceof Literal
          && value.equals(((Literal) o).value);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      return w.appendLiteral(value);
    }
  }

  /** Base class for declarations. */
  public abstract static class Decl extends BaseNode {
    Decl(Op op) {
      super(op);
    }
  }

  /** Datatype declaration. */
  public static class DatatypeDecl extends Decl {
    public final List<DataType> dataTypes;

    DatatypeDecl(ImmutableList<DataType> dataTypes) {
      super(Op.DATATYPE_DECL);
      this.dataTypes = requireNonNull(dataTypes);
      Preconditions.checkArgument(!this.dataTypes.isEmpty());
    }

    @Override public int hashCode() {
      return Objects.hash(dataTypes);
    }

    @Override public boolean equals(Object o) {
      return o == this
          || o instanceof DatatypeDecl
          && dataTypes.equals(((DatatypeDecl) o).dataTypes);
    }

//    public DatatypeDecl accept(AstShuttle shuttle) {
//      return shuttle.visit(this);
//    }
//
//    @Override public void accept(Visitor visitor) {
//      visitor.visit(this);
//    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      Ord.forEach(dataTypes, (dataType, i) -> {
        w.append(i == 0 ? "datatype " : " and ")
            .append(dataType.toString());
      });
      return w;
    }
  }

//  /** Datatype binding.
//   *
//   * <p>Example: the datatype declaration
//   * {@code datatype 'a x = X1 of 'a | X2 and y = Y}
//   * consists of type bindings {@code 'a x = X1 of 'a | X2} and
//   * {@code y = Y}. */
//  public static class DatatypeBind extends BaseNode {
//    public final List<Ast.TyVar> tyVars;
//    public final String name;
//    public final List<Ast.TyCon> tyCons;
//
//    DatatypeBind(DataType type, ImmutableList<Ast.TyVar> tyVars, String name,
//        ImmutableList<Ast.TyCon> tyCons) {
//      super(Op.DATATYPE_DECL, type);
//      this.tyVars = requireNonNull(tyVars);
//      this.name = requireNonNull(name);
//      this.tyCons = requireNonNull(tyCons);
//      Preconditions.checkArgument(!this.tyCons.isEmpty());
//    }
//
//    @Override public int hashCode() {
//      return Objects.hash(tyVars, tyCons);
//    }
//
//    @Override public boolean equals(Object o) {
//      return o == this
//          || o instanceof DatatypeBind
//          && name.equals(((DatatypeBind) o).name)
//          && tyVars.equals(((DatatypeBind) o).tyVars)
//          && tyCons.equals(((DatatypeBind) o).tyCons);
//    }
//
//    public DatatypeBind accept(AstShuttle shuttle) {
//      return shuttle.visit(this);
//    }
//
//    @Override public void accept(Visitor visitor) {
//      visitor.visit(this);
//    }
//
//    @Override AstWriter unparse(AstWriter w, int left, int right) {
//      switch (tyVars.size()) {
//      case 0:
//        break;
//      case 1:
//        w.append(tyVars.get(0), 0, 0).append(" ");
//        break;
//      default:
//        w.appendAll(tyVars, "(", ", ", ") ");
//      }
//      return w.append(name.name)
//          .appendAll(tyCons, " = ", " | ", "");
//    }
//  }

//  /** Type constructor.
//   *
//   * <p>For example, in the {@link DatatypeDecl datatype declaration}
//   * {@code datatype 'a option = NIL | SOME of 'a}, "NIL" and "SOME of 'a"
//   * are both type constructors.
//   */
//  public static class TyCon extends AstNode {
//    public final Id id;
//    public final Type type;
//
//    TyCon(Pos pos, Id id, Type type) {
//      super(pos, Op.TY_CON);
//      this.id = requireNonNull(id);
//      this.type = type; // optional
//    }
//
//    AstWriter unparse(AstWriter w, int left, int right) {
//      if (type != null) {
//        return w.append(id, left, op.left)
//            .append(" of ")
//            .append(type, op.right, right);
//      } else {
//        return w.append(id, left, right);
//      }
//    }
//
//    public CoreNode accept(AstShuttle shuttle) {
//      return shuttle.visit(this);
//    }
//
//    @Override public void accept(Visitor visitor) {
//      visitor.visit(this);
//    }
//  }

  /** Value declaration. */
  // TODO: should this extend Ast.ValDecl? Remove 'class ValBind'?
  public static class ValDecl extends Decl {
    public final ValBind valBind;

    ValDecl(ValBind valBind) {
      super(Op.VAL_DECL);
      this.valBind = valBind;
    }

    @Override public int hashCode() {
      return valBind.hashCode();
    }

    @Override public boolean equals(Object o) {
      return o == this
          || o instanceof ValDecl
          && valBind.equals(((ValDecl) o).valBind);
    }

//    public ValDecl accept(Shuttle shuttle) {
//      return shuttle.visit(this);
//    }

//    @Override public void accept(Visitor visitor) {
//      visitor.visit(this);
//    }

//    /** Creates a copy of this {@code ValDecl} with given contents,
//     * or {@code this} if the contents are the same. */
//    public ValDecl copy(Iterable<ValBind> valBinds) {
//      return Iterables.elementsEqual(this.valBinds, valBinds)
//          ? this
//          : ast.valDecl(pos, valBinds);
//    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      return w.append("val ").append(valBind, 0, right);
    }
  }

//  /** Parse tree node of a function declaration. */
//  public static class FunDecl extends Decl {
//    public final List<FunBind> funBinds;
//
//    FunDecl(Pos pos, ImmutableList<FunBind> funBinds) {
//      super(pos, Op.FUN_DECL);
//      this.funBinds = requireNonNull(funBinds);
//      Preconditions.checkArgument(!funBinds.isEmpty());
//      // TODO: check that functions have the same name
//    }
//
//    @Override public int hashCode() {
//      return funBinds.hashCode();
//    }
//
//    @Override public boolean equals(Object o) {
//      return o == this
//          || o instanceof FunDecl
//          && this.funBinds.equals(((FunDecl) o).funBinds);
//    }
//
//    public Decl accept(AstShuttle shuttle) {
//      return shuttle.visit(this);
//    }
//
//    @Override public void accept(Visitor visitor) {
//      visitor.visit(this);
//    }
//
//    @Override AstWriter unparse(AstWriter w, int left, int right) {
//      return w.appendAll(funBinds, "fun ", " and ", "");
//    }
//  }
//
//  /** One of the branches (separated by 'and') in a 'fun' function
//   * declaration. */
//  public static class FunBind extends AstNode {
//    public final List<FunMatch> matchList;
//    public final String name;
//
//    FunBind(Pos pos, ImmutableList<FunMatch> matchList) {
//      super(pos, Op.FUN_BIND);
//      Preconditions.checkArgument(!matchList.isEmpty());
//      this.matchList = matchList;
//      // We assume that the function name is the same in all matches.
//      // We will check during validation.
//      this.name = matchList.get(0).name;
//    }
//
//    public FunBind accept(AstShuttle shuttle) {
//      return shuttle.visit(this);
//    }
//
//    @Override public void accept(Visitor visitor) {
//      visitor.visit(this);
//    }
//
//    AstWriter unparse(AstWriter w, int left, int right) {
//      return w.appendAll(matchList, " | ");
//    }
//  }
//
//  /** One of the branches (separated by '|') in a 'fun' function declaration. */
//  public static class FunMatch extends AstNode {
//    public final String name;
//    public final List<Pat> patList;
//    public final Exp e;
//
//    FunMatch(Pos pos, String name, ImmutableList<Pat> patList, Exp e) {
//      super(pos, Op.FUN_MATCH);
//      this.name = name;
//      this.patList = patList;
//      this.e = e;
//    }
//
//    public FunMatch accept(AstShuttle shuttle) {
//      return shuttle.visit(this);
//    }
//
//    @Override public void accept(Visitor visitor) {
//      visitor.visit(this);
//    }
//
//    AstWriter unparse(AstWriter w, int left, int right) {
//      w.append(name);
//      for (Pat pat : patList) {
//        w.append(" ").append(pat, Op.APPLY.left, Op.APPLY.right);
//      }
//      return w.append(" = ").append(e, 0, right);
//    }
//  }

  /** Tuple. */
  // TODO: remove, replace with a call to the constructor of the n-tuple type?
  public static class Tuple extends Exp {
    public final List<Exp> args;

    Tuple(Type type, ImmutableList<Exp> args) {
      super(Op.TUPLE, type);
      this.args = ImmutableList.copyOf(args);
    }

    @Override public void forEachArg(ObjIntConsumer<Exp> action) {
      Ord.forEach(args, action);
    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      w.append("(");
      forEachArg((arg, i) -> w.append(i == 0 ? "" : ", ").append(arg, 0, 0));
      return w.append(")");
    }

    //    public Tuple copy(List<Exp> args) {
//      return this.args.equals(args) ? this : new Tuple(pos, args);
//    }
  }

  /** List. */
  // TODO: remove; replace with call to list constructor function
  public static class ListExp extends Exp {
    public final List<Exp> args;

    ListExp(Type type, ImmutableList<Exp> args) {
      super(Op.LIST, type);
      this.args = requireNonNull(args);
    }

    @Override public void forEachArg(ObjIntConsumer<Exp> action) {
      Ord.forEach(args, action);
    }

//    public List accept(AstShuttle shuttle) {
//      return shuttle.visit(this);
//    }
//
//    @Override public void accept(Visitor visitor) {
//      visitor.visit(this);
//    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      w.append("[");
      forEachArg((arg, i) -> w.append(i == 0 ? "" : ", ").append(arg, 0, 0));
      return w.append("]");
    }
  }

  /** Record. */
  // TODO: remove? Functionally equivalent to Tuple
  public static class Record extends Exp {
    public final List<Exp> args;

    Record(RecordLikeType type, ImmutableList<Exp> args) {
      super(Op.RECORD, type);
      this.args = requireNonNull(args);
      Preconditions.checkArgument(Util.transform(args, a -> a.type)
          .equals(type.argNameTypes().values()) || 0 == 0, // TODO
          "mismatched types: args [%s], record type [%s]", args, type);
    }

    @Override public RecordLikeType type() {
      return (RecordLikeType) type;
    }

    @Override public void forEachArg(ObjIntConsumer<Exp> action) {
      Ord.forEach(args, action);
    }

    /** Calls an action for each argument, supplying the ordinal, name and
     * expression for the argument. (The type can be found from the
     * expression.) */
    public void forEachNamedArg(Ord.IntObjObjConsumer<String, Exp> action) {
      Ord.forEach(type().argNameTypes().keySet(), (name, i) ->
          action.accept(i, name, args.get(i)));
    }

//    public Exp accept(AstShuttle shuttle) {
//      return shuttle.visit(this);
//    }
//
//    @Override public void accept(Visitor visitor) {
//      visitor.visit(this);
//    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      w.append("{");
      forEachNamedArg((i, name, arg) ->
          w.append(i > 0 ? ", " : "").id(name)
              .append(" = ").append(arg, 0, 0));
      return w.append("}");
    }

//    public Record copy(Map<String, Core.Exp> map) {
//      return ast.record(pos, map);
//    }
  }

//  /** Call to an infix operator. */
//  public static class InfixCall extends Exp {
//    public final Exp a0;
//    public final Exp a1;
//
//    InfixCall(Pos pos, Op op, Exp a0, Exp a1) {
//      super(pos, op);
//      this.a0 = requireNonNull(a0);
//      this.a1 = requireNonNull(a1);
//    }
//
//    @Override public void forEachArg(ObjIntConsumer<Exp> action) {
//      action.accept(a0, 0);
//      action.accept(a1, 1);
//    }
//
//    public Exp accept(AstShuttle shuttle) {
//      return shuttle.visit(this);
//    }
//
//    @Override public void accept(Visitor visitor) {
//      visitor.visit(this);
//    }
//
//    @Override AstWriter unparse(AstWriter w, int left, int right) {
//      return w.infix(left, a0, op, a1, right);
//    }
//
//    /** Creates a copy of this {@code InfixCall} with given contents,
//     * or {@code this} if the contents are the same. */
//    public InfixCall copy(Exp a0, Exp a1) {
//      return this.a0.equals(a0)
//          && this.a1.equals(a1)
//          ? this
//          : new InfixCall(pos, op, a0, a1);
//    }
//  }
//
//  /** Call to an prefix operator. */
//  public static class PrefixCall extends Exp {
//    public final Exp a;
//
//    PrefixCall(Pos pos, Op op, Exp a) {
//      super(pos, op);
//      this.a = requireNonNull(a);
//    }
//
//    @Override public void forEachArg(ObjIntConsumer<Exp> action) {
//      action.accept(a, 0);
//    }
//
//    public Exp accept(AstShuttle shuttle) {
//      return shuttle.visit(this);
//    }
//
//    @Override public void accept(Visitor visitor) {
//      visitor.visit(this);
//    }
//
//    @Override AstWriter unparse(AstWriter w, int left, int right) {
//      return w.prefix(left, op, a, right);
//    }
//  }

  /** "If ... else" expression. */
  // TODO: replace If(c,t,f) with Case(c, true, t, f)
  public static class If extends Exp {
    public final Exp condition;
    public final Exp ifTrue;
    public final Exp ifFalse;

    If(Exp condition, Exp ifTrue, Exp ifFalse) {
      super(Op.IF, ifTrue.type);
      this.condition = condition;
      this.ifTrue = ifTrue;
      this.ifFalse = ifFalse;
      Preconditions.checkArgument(condition.type == PrimitiveType.BOOL);
      Preconditions.checkArgument(ifTrue.type.equals(ifFalse.type));
    }

//    public Exp accept(AstShuttle shuttle) {
//      return shuttle.visit(this);
//    }
//
//    @Override public void accept(Visitor visitor) {
//      visitor.visit(this);
//    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      return w.append("if ").append(condition, 0, 0)
          .append(" then ").append(ifTrue, 0, 0)
          .append(" else ").append(ifFalse, 0, right);
    }

//    /** Creates a copy of this {@code If} with given contents,
//     * or {@code this} if the contents are the same. */
//    public If copy(Exp condition, Exp ifTrue, Exp ifFalse) {
//      return this.condition.equals(condition)
//          && this.ifTrue.equals(ifTrue)
//          && this.ifFalse.equals(ifFalse)
//          ? this
//          : new If(pos, condition, ifTrue, ifFalse);
//    }
  }

  /** "Let" expression. */
  public static class LetExp extends Exp {
    public final Decl decl;
    public final Exp e;

    LetExp(Decl decl, Exp e) {
      super(Op.LET, e.type);
      this.decl = requireNonNull(decl);
      this.e = requireNonNull(e);
    }

//    public Exp accept(AstShuttle shuttle) {
//      return shuttle.visit(this);
//    }
//
//    @Override public void accept(Visitor visitor) {
//      visitor.visit(this);
//    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      return w.append("let ").append(decl, 0, 0)
          .append(" in ").append(e, 0, 0)
          .append(" end");
    }

//    /** Creates a copy of this {@code LetExp} with given contents,
//     * or {@code this} if the contents are the same. */
//    public LetExp copy(Iterable<Decl> decls, Exp e) {
//      return Iterables.elementsEqual(this.decls, decls)
//          && Objects.equals(this.e, e)
//          ? this
//          : ast.let(pos, decls, e);
//    }
  }

  /** Value bind. */
  // TODO: should this extend Ast.ValBind?
  public static class ValBind extends BaseNode {
    public final boolean rec;
    public final Pat pat;
    public final Exp e;

    ValBind(boolean rec, Pat pat, Exp e) {
      super(Op.VAL_BIND);
      this.rec = rec;
      this.pat = pat;
      this.e = e;
    }

//    public CoreNode accept(AstShuttle shuttle) {
//      return shuttle.visit(this);
//    }
//
//    @Override public void accept(Visitor visitor) {
//      visitor.visit(this);
//    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      if (rec) {
        w.append("rec ");
      }
      return w.append(pat, 0, 0).append(" = ").append(e, 0, right);
    }

//    /** Creates a copy of this {@code ValBind} with given contents,
//     * or {@code this} if the contents are the same. */
//    public ValBind copy(boolean rec, Pat pat, Exp e) {
//      return this.rec == rec
//          && this.pat.equals(pat)
//          && this.e.equals(e)
//          ? this
//          : ast.valBind(pos, rec, pat, e);
//    }
  }

  /** Match. */
  public static class Match extends BaseNode {
    public final Pat pat;
    public final Exp e;

    Match(Pat pat, Exp e) {
      super(Op.MATCH);
      this.pat = pat;
      this.e = e;
    }

//    public Match accept(AstShuttle shuttle) {
//      return shuttle.visit(this);
//    }
//
//    @Override public void accept(Visitor visitor) {
//      visitor.visit(this);
//    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      return w.append(pat, 0, 0).append(" => ").append(e, 0, right);
    }

//    /** Creates a copy of this {@code Match} with given contents,
//     * or {@code this} if the contents are the same. */
//    public Match copy(Pat pat, Exp e) {
//      return this.pat.equals(pat)
//          && this.e.equals(e)
//          ? this
//          : ast.match(pos, pat, e);
//    }
  }

  /** Lambda expression. */
  public static class Fn extends Exp {
    public final List<Match> matchList;

    Fn(FnType type, ImmutableList<Match> matchList) {
      super(Op.FN, type);
      this.matchList = requireNonNull(matchList);
      Preconditions.checkArgument(!matchList.isEmpty());
    }

    @Override public FnType type() {
      return (FnType) type;
    }

//    public Fn accept(AstShuttle shuttle) {
//      return shuttle.visit(this);
//    }
//
//    @Override public void accept(Visitor visitor) {
//      visitor.visit(this);
//    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      return w.append("fn ").appendAll(matchList, 0, Op.BAR, right);
    }

//    /** Creates a copy of this {@code Fn} with given contents,
//     * or this if the contents are the same. */
//    public Fn copy(List<Match> matchList) {
//      return this.matchList.equals(matchList)
//          ? this
//          : ast.fn(pos, matchList);
//    }
  }

  /** Case expression. */
  public static class Case extends Exp {
    public final Exp e;
    public final List<Match> matchList;

    Case(Type type, Exp e, ImmutableList<Match> matchList) {
      super(Op.CASE, type);
      this.e = e;
      this.matchList = matchList;
    }

//    public Exp accept(AstShuttle shuttle) {
//      return shuttle.visit(this);
//    }
//
//    @Override public void accept(Visitor visitor) {
//      visitor.visit(this);
//    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      return w.append("case ").append(e, 0, 0).append(" of ")
          .appendAll(matchList, left, Op.BAR, right);
    }

//    public Case copy(Exp e, List<Match> matchList) {
//      return this.e.equals(e)
//          && this.matchList.equals(matchList)
//          ? this
//          : ast.caseOf(pos, e, matchList);
//    }
  }

  /** From expression. */
  public static class From extends Exp {
    public final Map<Pat, Exp> sources;
    public final ImmutableList<FromStep> steps;
    public final Exp yieldExp;

    From(ListType type, ImmutableMap<Pat, Exp> sources,
        ImmutableList<FromStep> steps, Exp yieldExp) {
      super(Op.FROM, type);
      this.sources = requireNonNull(sources);
      this.steps = requireNonNull(steps);
      this.yieldExp = requireNonNull(yieldExp);
      Preconditions.checkArgument(type.elementType.equals(yieldExp.type));
    }

    @Override public ListType type() {
      return (ListType) type;
    }

//    public Exp accept(AstShuttle shuttle) {
//      return shuttle.visit(this);
//    }
//
//    @Override public void accept(Visitor visitor) {
//      visitor.visit(this);
//    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      if (left > op.left || op.right < right) {
        return w.append("(").append(this, 0, 0).append(")");
      } else {
        w.append("from");
        Ord.forEach(sources, (i, id, exp) ->
            w.append(i == 0 ? " " : ", ")
                .append(id, 0, 0).append(" in ").append(exp, 0, 0));
        for (FromStep step : steps) {
          w.append(" ");
          step.unparse(w, 0, 0);
        }
        if (yieldExp != null) {
          w.append(" yield ").append(yieldExp, 0, 0);
        }
        return w;
      }
    }

//    /** Creates a copy of this {@code From} with given contents,
//     * or {@code this} if the contents are the same. */
//    public From copy(Map<Core.Pat, Core.Exp> sources,
//        List<FromStep> steps, Core.Exp yieldExp) {
//      return this.sources.equals(sources)
//          && this.steps.equals(steps)
//          && Objects.equals(this.yieldExp, yieldExp)
//          ? this
//          : ast.from(pos, sources, steps, yieldExp);
//    }
  }

  /** A step in a {@code from} expression - {@code where}, {@code group}
   * or {@code order}. */
  public abstract static class FromStep extends BaseNode {
    FromStep(Op op) {
      super(op);
    }

    /** Returns the names of the fields produced by this step, given the names
     * of the fields that are input to this step.
     *
     * <p>By default, a step outputs the same fields as it inputs.
     */
    public void deriveOutBindings(Iterable<Binding> inBindings,
        BiFunction<String, Type, Binding> binder,
        Consumer<Binding> outBindings) {
      inBindings.forEach(outBindings);
    }
  }

  /** A {@code where} clause in a {@code from} expression. */
  public static class Where extends FromStep {
    public final Exp exp;

    Where(Exp exp) {
      super(Op.WHERE);
      this.exp = exp;
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      return w.append("where ").append(exp, 0, 0);
    }

//    @Override public CoreNode accept(AstShuttle shuttle) {
//      return shuttle.visit(this);
//    }
//
//    @Override public void accept(Visitor visitor) {
//      visitor.visit(this);
//    }
//
//    public Where copy(Exp exp) {
//      return this.exp.equals(exp) ? this : new Where(pos, exp);
//    }
  }

  /** An {@code order} clause in a {@code from} expression. */
  public static class Order extends FromStep {
    public final ImmutableList<OrderItem> orderItems;

    Order(ImmutableList<OrderItem> orderItems) {
      super(Op.ORDER);
      this.orderItems = requireNonNull(orderItems);
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      return w.append("order ").appendAll(orderItems, ", ");
    }

//    @Override public CoreNode accept(AstShuttle shuttle) {
//      return shuttle.visit(this);
//    }
//
//    @Override public void accept(Visitor visitor) {
//      visitor.visit(this);
//    }
//
//    public Order copy(List<OrderItem> orderItems) {
//      return this.orderItems.equals(orderItems)
//          ? this
//          : new Order(pos, ImmutableList.copyOf(orderItems));
//    }
  }

  /** An item in an {@code order} clause. */
  public static class OrderItem extends BaseNode {
    public final Exp exp;
    public final Ast.Direction direction;

    OrderItem(Exp exp, Ast.Direction direction) {
      super(Op.ORDER_ITEM);
      this.exp = requireNonNull(exp);
      this.direction = requireNonNull(direction);
    }

    AstWriter unparse(AstWriter w, int left, int right) {
      return w.append(exp, 0, 0)
          .append(direction == Ast.Direction.DESC ? " desc" : "");
    }

//    public CoreNode accept(AstShuttle shuttle) {
//      return shuttle.visit(this);
//    }
//
//    @Override public void accept(Visitor visitor) {
//      visitor.visit(this);
//    }
//
//    public OrderItem copy(Exp exp, Direction direction) {
//      return this.exp.equals(exp)
//          && this.direction == direction
//          ? this
//          : new OrderItem(pos, exp, direction);
//    }
  }

  /** A {@code group} clause in a {@code from} expression. */
  public static class Group extends FromStep {
    public final SortedMap<String, Exp> groupExps;
    public final SortedMap<String, Aggregate> aggregates;

    Group(ImmutableSortedMap<String, Exp> groupExps,
        ImmutableSortedMap<String, Aggregate> aggregates) {
      super(Op.GROUP);
      this.groupExps = groupExps;
      this.aggregates = aggregates;
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      Pair.forEachIndexed(groupExps, (i, id, exp) ->
          w.append(i == 0 ? "group " : ", ")
              .id(id).append(" = ").append(exp, 0, 0));
      Pair.forEachIndexed(aggregates, (i, name, aggregate) ->
          w.append(i == 0 ? " compute " : ", ")
              .id(name).append(" = ").append(aggregate, 0, 0));
      return w;
    }

//    @Override public CoreNode accept(AstShuttle shuttle) {
//      return shuttle.visit(this);
//    }
//
//    @Override public void accept(Visitor visitor) {
//      visitor.visit(this);
//    }

    @Override public void deriveOutBindings(Iterable<Binding> inBindings,
        BiFunction<String, Type, Binding> binder,
        Consumer<Binding> outBindings) {
      groupExps.forEach((id, exp) ->
          outBindings.accept(binder.apply(id, exp.type)));
      aggregates.forEach((id, aggregate) ->
          outBindings.accept(binder.apply(id, aggregate.type)));
    }

//    public Group copy(List<Pair<Id, Exp>> groupExps,
//        List<Aggregate> aggregates) {
//      return this.groupExps.equals(groupExps)
//          && this.aggregates.equals(aggregates)
//          ? this
//          : ast.group(pos, groupExps, aggregates);
//    }
  }

  /** Application of a function to its argument. */
  public static class Apply extends Exp {
    public final Exp fn;
    public final Exp arg;

    Apply(Type type, Exp fn, Exp arg) {
      super(Op.APPLY, type);
      this.fn = fn;
      this.arg = arg;
    }

//    public Exp accept(AstShuttle shuttle) {
//      return shuttle.visit(this);
//    }

    @Override public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override AstWriter unparse(AstWriter w, int left, int right) {
      return w.infix(left, fn, op, arg, right);
    }

//    public Apply copy(Exp fn, Exp arg) {
//      return this.fn.equals(fn) && this.arg.equals(arg) ? this
//          : new Apply(pos, fn, arg);
//    }
  }

  /** Call to an aggregate function in a {@code compute} clause.
   *
   * <p>For example, in {@code compute sumId = sum of #id e},
   * {@code aggregate} is "sum", {@code argument} is "#id e". */
  public static class Aggregate extends BaseNode {
    public final Type type;
    public final Exp aggregate;
    public final Exp argument;

    Aggregate(Type type, Exp aggregate, @Nullable Exp argument) {
      super(Op.AGGREGATE);
      this.type = type;
      this.aggregate = requireNonNull(aggregate);
      this.argument = argument;
    }

    AstWriter unparse(AstWriter w, int left, int right) {
      w.append(aggregate, 0, 0);
      if (argument != null) {
        w.append(" of ")
            .append(argument, 0, 0);
      }
      return w;
    }

//    public CoreNode accept(AstShuttle shuttle) {
//      return shuttle.visit(this);
//    }
//
//    @Override public void accept(Visitor visitor) {
//      visitor.visit(this);
//    }
//
//    public Aggregate copy(Exp aggregate, Exp argument, Id id) {
//      return this.aggregate.equals(aggregate)
//          && Objects.equals(this.argument, argument)
//          && this.id.equals(id)
//          ? this
//          : ast.aggregate(pos, aggregate, argument, id);
//    }
  }

  /** Expression that is a wrapped {@link Applicable}.
   *
   * <p>Does not occur in the output of the parser, only as an intermediate
   * value during compilation. */
  public static class ApplicableExp extends Core.Exp {
    public final Applicable applicable;

    ApplicableExp(Applicable applicable) {
      super(Op.WRAPPED_APPLICABLE, PrimitiveType.UNIT);
      this.applicable = requireNonNull(applicable);
    }

//    public Core.Exp accept(AstShuttle shuttle) {
//      return this;
//    }
//
//    @Override public void accept(Visitor visitor) {
//       no-op
//    }

    AstWriter unparse(AstWriter w, int left, int right) {
      return w;
    }
  }
}

// End Core.java
