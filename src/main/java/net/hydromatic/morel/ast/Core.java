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

import static com.google.common.base.Preconditions.checkArgument;
import static java.lang.String.format;
import static java.util.Objects.hash;
import static java.util.Objects.requireNonNull;
import static net.hydromatic.morel.ast.CoreBuilder.core;
import static net.hydromatic.morel.type.TypeSystem.canAssign;
import static net.hydromatic.morel.util.Ord.forEachIndexed;
import static net.hydromatic.morel.util.Pair.forEach;
import static net.hydromatic.morel.util.Pair.forEachIndexed;
import static net.hydromatic.morel.util.Static.allMatch;
import static net.hydromatic.morel.util.Static.last;
import static net.hydromatic.morel.util.Static.only;
import static net.hydromatic.morel.util.Static.transform;
import static org.apache.calcite.util.Util.first;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.common.primitives.UnsignedLong;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.function.ObjIntConsumer;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.compile.Environment;
import net.hydromatic.morel.compile.Extents;
import net.hydromatic.morel.compile.Resolver;
import net.hydromatic.morel.eval.Applicable;
import net.hydromatic.morel.eval.Applicable1;
import net.hydromatic.morel.eval.Applicable2;
import net.hydromatic.morel.eval.Applicable3;
import net.hydromatic.morel.eval.Applicable4;
import net.hydromatic.morel.eval.Closure;
import net.hydromatic.morel.eval.Code;
import net.hydromatic.morel.eval.Codes;
import net.hydromatic.morel.eval.Describer;
import net.hydromatic.morel.type.AliasType;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.DataType;
import net.hydromatic.morel.type.FnType;
import net.hydromatic.morel.type.ListType;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.RangeExtent;
import net.hydromatic.morel.type.RecordLikeType;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.TupleType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.type.TypedValue;
import net.hydromatic.morel.util.Pair;
import net.hydromatic.morel.util.PairList;
import org.jspecify.annotations.Nullable;

/**
 * Core expressions.
 *
 * <p>Many expressions are subclasses of similarly named expressions in {@link
 * Ast}. This class functions as a namespace, so that we can keep the class
 * names short.
 */
// TODO: remove 'parse tree for...' from all the comments below
@SuppressWarnings("StaticPseudoFunctionalStyleMethod")
public class Core {
  private Core() {}

  /** Abstract base class of Core nodes. */
  abstract static class BaseNode extends AstNode {
    BaseNode(Pos pos, Op op) {
      super(pos, op);
    }

    @Override
    public AstNode accept(Shuttle shuttle) {
      throw new UnsupportedOperationException(
          getClass() + " cannot accept " + shuttle.getClass());
    }

    @Override
    public void accept(Visitor visitor) {
      throw new UnsupportedOperationException(
          getClass() + " cannot accept " + visitor.getClass());
    }
  }

  /**
   * Base class for a pattern.
   *
   * <p>For example, "x" in "val x = 5" is a {@link IdPat}; the "(x, y) in "val
   * (x, y) = makePair 1 2" is a {@link TuplePat}.
   */
  public abstract static class Pat extends BaseNode {
    public final Type type;

    Pat(Op op, Type type) {
      super(Pos.ZERO, op);
      this.type = requireNonNull(type);
    }

    /** Returns the type. */
    public Type type() {
      return type;
    }

    @Override
    public abstract Pat accept(Shuttle shuttle);

    /**
     * Converts this pattern to a string.
     *
     * <p>The result is similar to that of {@link #toString()}, except that
     * identifiers are permuted. For example, if a pattern includes "c.3" and
     * "c.2" but not "c.0" or "c.1", then "c.3" will be printed as "c" and "c.2"
     * will be printed as "c.1".
     *
     * <p>This behavior makes plans more concise if their local variables happen
     * to have the same name as other variables that occur in functions in the
     * session history.
     */
    public String describe(Describer describer) {
      final AstWriter w =
          new AstWriter() {
            @Override
            public AstWriter id(String name, int i) {
              int j = describer.register(name, i);
              return super.id(name, j);
            }

            @Override
            public AstWriter idQuoted(String name, int i) {
              int j = describer.register(name, i);
              return super.idQuoted(name, j);
            }
          };
      return unparse(w);
    }

    /** Returns all named patterns in this pattern. */
    public List<NamedPat> expand() {
      final ImmutableList.Builder<NamedPat> list = ImmutableList.builder();
      accept(
          new Visitor() {
            @Override
            protected void visit(Core.IdPat idPat) {
              list.add(idPat);
            }

            @Override
            protected void visit(Core.AsPat asPat) {
              list.add(asPat);
              super.visit(asPat);
            }
          });
      return list.build();
    }
  }

  /**
   * Base class for named patterns ({@link IdPat} and {@link AsPat}).
   *
   * <p>Implements {@link Comparable} so that names are sorted correctly for
   * record fields (see {@link RecordType#ORDERING}).
   *
   * <p>A {@link Core.ValDecl} must be one of these.
   */
  public abstract static class NamedPat extends Pat
      implements Comparable<NamedPat> {
    /**
     * Ordering that compares named patterns by their names, then by their
     * ordinal.
     */
    public static final Ordering<NamedPat> ORDERING =
        Ordering.from(NamedPat::compare);

    public final String name;
    public final int i;

    NamedPat(Op op, Type type, String name, int i) {
      super(op, type);
      this.name = requireNonNull(name, "name");
      this.i = i;
      checkArgument(!name.isEmpty(), "empty name");
    }

    /**
     * {@inheritDoc}
     *
     * <p>Collate first on name, then on ordinal.
     */
    @Override
    public int compareTo(NamedPat o) {
      return compare(this, o);
    }

    /** Helper for {@link #ORDERING}. */
    static int compare(NamedPat o1, NamedPat o2) {
      int c = RecordType.compareNames(o1.name, o2.name);
      if (c != 0) {
        return c;
      }
      return Integer.compare(o1.i, o2.i);
    }

    /**
     * Returns a copy of this pattern with a given type; or this pattern if the
     * type is the same.
     */
    public abstract NamedPat withType(Type type);

    /**
     * Returns a copy of this pattern with a given name and ordinal 0; or this
     * pattern if the name is the same.
     */
    public abstract NamedPat withName(String name);

    @Override
    public abstract NamedPat accept(Shuttle shuttle);
  }

  /**
   * Named pattern.
   *
   * @see Ast.Id
   */
  public static class IdPat extends NamedPat {
    IdPat(Type type, String name, int i) {
      super(Op.ID_PAT, type, name, i);
    }

    @Override
    public int hashCode() {
      return name.hashCode() + i;
    }

    @Override
    public boolean equals(Object obj) {
      return obj == this
          || obj instanceof IdPat
              && ((IdPat) obj).name.equals(name)
              && ((IdPat) obj).i == i;
    }

    @Override
    AstWriter unparse(AstWriter w, int left, int right) {
      return w.idQuoted(name, i);
    }

    @Override
    public IdPat accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override
    public List<NamedPat> expand() {
      return ImmutableList.of(this);
    }

    @Override
    public IdPat withType(Type type) {
      return type == this.type ? this : new IdPat(type, name, i);
    }

    @Override
    public IdPat withName(String name) {
      return name.equals(this.name) ? this : new IdPat(type, name, 0);
    }
  }

  /**
   * Literal pattern, the pattern analog of the {@link Literal} expression.
   *
   * <p>For example, "0" in "fun fact 0 = 1 | fact n = n * fact (n - 1)".
   */
  @SuppressWarnings("rawtypes")
  public static class LiteralPat extends Pat {
    public final Comparable value;

    LiteralPat(Op op, Type type, Comparable value) {
      super(op, type);
      this.value = requireNonNull(value);
      checkArgument(
          op == Op.BOOL_LITERAL_PAT
              || op == Op.CHAR_LITERAL_PAT
              || op == Op.INT_LITERAL_PAT
              || op == Op.REAL_LITERAL_PAT
              || op == Op.STRING_LITERAL_PAT
              || op == Op.WORD_LITERAL_PAT);
    }

    @Override
    public int hashCode() {
      return value.hashCode();
    }

    @Override
    public boolean equals(Object o) {
      return o == this
          || o instanceof LiteralPat
              && this.value.equals(((LiteralPat) o).value);
    }

    @Override
    AstWriter unparse(AstWriter w, int left, int right) {
      if (op == Op.WORD_LITERAL_PAT) {
        return w.appendLiteral(
            UnsignedLong.valueOf(((BigDecimal) value).toBigIntegerExact()));
      }
      return w.appendLiteral(value);
    }

    @Override
    public Pat accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }
  }

  /**
   * Wildcard pattern.
   *
   * <p>For example, "{@code _}" in "{@code fn foo _ => 42}".
   */
  public static class WildcardPat extends Pat {
    WildcardPat(Type type) {
      super(Op.WILDCARD_PAT, type);
    }

    @Override
    public int hashCode() {
      return "_".hashCode();
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof WildcardPat;
    }

    @Override
    AstWriter unparse(AstWriter w, int left, int right) {
      return w.append("_");
    }

    @Override
    public Pat accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }
  }

  /** Layered pattern. */
  public static class AsPat extends NamedPat {
    public final Pat pat;

    protected AsPat(Type type, String name, int i, Pat pat) {
      super(Op.AS_PAT, type, name, i);
      this.pat = requireNonNull(pat);
    }

    @Override
    AstWriter unparse(AstWriter w, int left, int right) {
      return w.id(name).append(" as ").append(pat, 0, 0);
    }

    @Override
    public AsPat withType(Type type) {
      return type == this.type ? this : new AsPat(type, name, i, pat);
    }

    @Override
    public AsPat withName(String name) {
      return name.equals(this.name) ? this : new AsPat(type, name, 0, pat);
    }

    @Override
    public AsPat accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    /**
     * Creates a copy of this {@code AsPat} with given contents, or {@code this}
     * if the contents are the same.
     */
    public Core.AsPat copy(String name, int i, Core.Pat pat) {
      return this.name.equals(name) && this.i == i && this.pat.equals(pat)
          ? this
          : new AsPat(type, name, i, pat);
    }
  }

  /**
   * Type constructor pattern with an argument.
   *
   * <p>For example, in "fun nvl NIL = 0 | OPTION x = x", "OPTION x" is a type
   * constructor pattern that binds "x"; and "NIL" is a type constructor pattern
   * whose {@link #pat} is null.
   *
   * @see Con0Pat
   */
  public static class ConPat extends Pat {
    public final String tyCon;
    public final Pat pat;

    /**
     * Mostly-private constructor.
     *
     * <p>Exposed so that "op ::" (cons) can supply a different {@link Op}
     * value. The "list" datatype is not represented the same as other
     * datatypes, and the separate "op" value allows us to deconstruct it in a
     * different way.
     */
    protected ConPat(Op op, Type type, String tyCon, Pat pat) {
      super(op, type);
      this.tyCon = requireNonNull(tyCon);
      this.pat = requireNonNull(pat);
      checkArgument(op == Op.CON_PAT || op == Op.CONS_PAT);
    }

    ConPat(Type type, String tyCon, Pat pat) {
      this(Op.CON_PAT, type, tyCon, pat);
    }

    @Override
    AstWriter unparse(AstWriter w, int left, int right) {
      return w.id(tyCon).append("(").append(pat, 0, 0).append(")");
    }

    @Override
    public Pat accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    /**
     * Creates a copy of this {@code ConPat} with given contents, or {@code
     * this} if the contents are the same.
     */
    public Core.ConPat copy(String tyCon, Core.Pat pat) {
      return this.tyCon.equals(tyCon) && this.pat.equals(pat)
          ? this
          : new ConPat(op, type, tyCon, pat);
    }
  }

  /**
   * Type constructor pattern with no argument.
   *
   * <p>For example, in "fun nvl NIL = 0 | OPTION x = x", "NIL" is a zero-arg
   * type constructor pattern.
   *
   * @see ConPat
   */
  public static class Con0Pat extends Pat {
    public final String tyCon;

    Con0Pat(DataType type, String tyCon) {
      super(Op.CON0_PAT, type);
      this.tyCon = requireNonNull(tyCon);
    }

    @Override
    public DataType type() {
      return (DataType) type;
    }

    @Override
    AstWriter unparse(AstWriter w, int left, int right) {
      return w.id(tyCon);
    }

    @Override
    public Pat accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }
  }

  /**
   * Tuple pattern, the pattern analog of the {@link Tuple} expression.
   *
   * <p>For example, "(x, y)" in "fun sum (x, y) = x + y".
   */
  public static class TuplePat extends Pat {
    public final List<Pat> args;

    /**
     * Creates a TuplePat.
     *
     * <p>Type is {@link PrimitiveType#UNIT} if {@code args} is empty, otherwise
     * a {@link TupleType}.
     */
    TuplePat(RecordLikeType type, ImmutableList<Pat> args) {
      super(Op.TUPLE_PAT, type);
      this.args = requireNonNull(args);
      checkArgument(args.size() == type.argNameTypes().size());
      checkArgument(
          args.isEmpty()
              ? type == PrimitiveType.UNIT
              : type instanceof TupleType);
    }

    @Override
    public RecordLikeType type() {
      return (RecordLikeType) type;
    }

    @Override
    AstWriter unparse(AstWriter w, int left, int right) {
      w.append("(");
      forEachIndexed(
          args, (arg, i) -> w.append(i == 0 ? "" : ", ").append(arg, 0, 0));
      return w.append(")");
    }

    @Override
    public Pat accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    public TuplePat copy(TypeSystem typeSystem, List<Pat> args) {
      return args.equals(this.args) ? this : core.tuplePat(typeSystem, args);
    }

    /** Returns the names of all components that are named. */
    public List<String> fieldNames() {
      final ImmutableList.Builder<String> names = ImmutableList.builder();
      for (Pat arg : args) {
        if (arg instanceof NamedPat) {
          names.add(((NamedPat) arg).name);
        }
      }
      return names.build();
    }
  }

  /**
   * List pattern.
   *
   * <p>For example, "[x, y]" in "fun sum [x, y] = x + y".
   */
  public static class ListPat extends Pat {
    public final List<Pat> args;

    ListPat(Type type, ImmutableList<Pat> args) {
      super(Op.LIST_PAT, type);
      this.args = requireNonNull(args);
    }

    @Override
    AstWriter unparse(AstWriter w, int left, int right) {
      w.append("[");
      forEachIndexed(
          args, (arg, i) -> w.append(i == 0 ? "" : ", ").append(arg, 0, 0));
      return w.append("]");
    }

    @Override
    public Pat accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    public ListPat copy(TypeSystem typeSystem, List<Pat> args) {
      return args.equals(this.args) ? this : core.listPat(typeSystem, args);
    }
  }

  /** Record pattern. */
  public static class RecordPat extends Pat {
    public final List<Pat> args;

    RecordPat(RecordType type, ImmutableList<Pat> args) {
      super(Op.RECORD_PAT, type);
      this.args = requireNonNull(args);
      checkArgument(args.size() == type.argNameTypes.size());
      checkArgument(!args.isEmpty(), "Use TuplePat for empty record");
    }

    @Override
    public RecordType type() {
      return (RecordType) type;
    }

    @Override
    AstWriter unparse(AstWriter w, int left, int right) {
      w.append("{");
      forEachIndexed(
          type().argNameTypes.keySet(),
          args,
          (i, name, arg) ->
              w.append(i > 0 ? ", " : "")
                  .append(name)
                  .append(" = ")
                  .append(arg, 0, 0));
      return w.append("}");
    }

    @Override
    public Pat accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    public Pat copy(TypeSystem typeSystem, RecordType type, List<Pat> args) {
      if (args.equals(this.args)) {
        return this;
      }
      if (!transform(args, Core.Pat::type).equals(type.argTypes())) {
        // The field types do not match. Create a new record type with the same
        // field names, new field types.
        final ImmutableSortedMap.Builder<String, Type> nameTypes =
            ImmutableSortedMap.orderedBy(RecordType.ORDERING);
        forEach(
            type.argNames(),
            args,
            (name, pat) -> nameTypes.put(name, pat.type));

        // Cast is safe. If the previous type was a record type (non-empty,
        // field names are not consecutive integers) the new one will be also.
        type = (RecordType) typeSystem.recordType(nameTypes.build());
      }
      return core.recordPat(type, args);
    }
  }

  /** Base class of core expressions. */
  public abstract static class Exp extends BaseNode {
    public final Type type;

    Exp(Pos pos, Op op, Type type) {
      super(pos, op);
      this.type = requireNonNull(type);
    }

    public void forEachArg(ObjIntConsumer<Exp> action) {
      // no args
    }

    /** Returns the {@code i}<sup>th</sup> argument. */
    public Exp arg(int i) {
      throw new UnsupportedOperationException();
    }

    /** Returns the type. */
    public Type type() {
      return type;
    }

    @Override
    public abstract Exp accept(Shuttle shuttle);

    /**
     * Returns whether this expression is a constant.
     *
     * <p>Examples include literals {@code 1}, {@code true}, constructors
     * applied to constants, records and tuples whose arguments are constant.
     */
    public boolean isConstant() {
      return false;
    }

    /**
     * Returns the {@link BuiltIn} that this is a call to, or {@link
     * BuiltIn#Z_VOID} if not a call.
     */
    public BuiltIn builtIn() {
      return BuiltIn.Z_VOID;
    }

    /** Returns whether this expression is a call to the given built-in. */
    public boolean isCallTo(BuiltIn builtIn) {
      return builtIn() == builtIn;
    }

    /**
     * Returns whether this expression is a call to the given constructor of a
     * built-in datatype.
     */
    public boolean isCallTo(BuiltIn.Constructor constructor) {
      return false;
    }

    /**
     * Returns whether this expression iterates over the values of a type. Some
     * extents are infinite.
     */
    public boolean isExtent() {
      return isCallTo(BuiltIn.Z_EXTENT);
    }

    /** Returns the extent, or throws if not {@link #isExtent()}. */
    public RangeExtent getRangeExtent() {
      checkArgument(isExtent());
      final Apply apply = (Apply) this;
      final Core.Literal argLiteral = (Core.Literal) apply.arg;
      return argLiteral.unwrap(RangeExtent.class);
    }

    /** Returns whether this is a boolean literal with value {@code b}. */
    public boolean isBoolLiteral(boolean b) {
      return op == Op.BOOL_LITERAL
          && ((Literal) this).unwrap(Boolean.class) == b;
    }
  }

  /**
   * Reference to a variable.
   *
   * <p>While {@link Ast.Id} is widely used, and means an occurrence of a name
   * in the parse tree, {@code Id} is much narrower: it means a reference to a
   * value. What would be an {@code Id} in Ast is often a {@link String} in
   * Core; for example, compare {@link Ast.Con0Pat#tyCon} with {@link
   * Con0Pat#tyCon}.
   */
  public static class Id extends Exp implements Comparable<Id> {
    public final NamedPat idPat;

    /** Creates an Id. */
    Id(Pos pos, NamedPat idPat) {
      super(pos, Op.ID, idPat.type);
      this.idPat = requireNonNull(idPat);
    }

    @Override
    public int compareTo(Id o) {
      return idPat.compareTo(o.idPat);
    }

    @Override
    public int hashCode() {
      return idPat.hashCode();
    }

    @Override
    public boolean equals(Object o) {
      return o == this || o instanceof Id && this.idPat.equals(((Id) o).idPat);
    }

    @Override
    public Exp accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override
    AstWriter unparse(AstWriter w, int left, int right) {
      return w.idQuoted(idPat.name, idPat.i);
    }
  }

  /** Record selector function. */
  public static class RecordSelector extends Exp {
    /**
     * The ordinal of the field in the record or tuple that is to be accessed.
     */
    public final int slot;

    /** Creates a record selector. */
    RecordSelector(FnType fnType, int slot) {
      super(Pos.ZERO, Op.RECORD_SELECTOR, fnType);
      this.slot = slot;
    }

    @Override
    public int hashCode() {
      return slot + 2237;
    }

    @Override
    public boolean equals(Object o) {
      return o == this
          || o instanceof RecordSelector
              && this.slot == ((RecordSelector) o).slot
              && this.type.equals(((RecordSelector) o).type);
    }

    public String fieldName() {
      final RecordLikeType recordType = (RecordLikeType) type().paramType;
      return recordType.argNames().get(slot);
    }

    @Override
    public FnType type() {
      return (FnType) type;
    }

    @Override
    public RecordSelector accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override
    AstWriter unparse(AstWriter w, int left, int right) {
      return w.append("#").append(fieldName());
    }
  }

  /** Code of a literal (constant). */
  @SuppressWarnings("rawtypes")
  public static class Literal extends Exp {
    public final Comparable value;

    /** Creates a Literal. */
    Literal(Op op, Type type, Comparable value) {
      super(Pos.ZERO, op, type);
      this.value = requireNonNull(value);
    }

    static Comparable wrap(Exp exp, Object value) {
      return new Wrapper(exp, value);
    }

    /**
     * Returns the value of this literal as a given class, or throws {@link
     * ClassCastException}. If the class is not {@link Comparable}, the value
     * will be in a wrapper.
     */
    public <C> C unwrap(Class<C> clazz) {
      Object v;
      if (value instanceof Wrapper
          && ((Wrapper) value).o instanceof TypedValue) {
        return ((TypedValue) ((Wrapper) value).o).valueAs(clazz);
      }
      if (clazz.isInstance(value) && clazz != Object.class) {
        v = value;
      } else if (Number.class.isAssignableFrom(clazz)
          && value instanceof Number) {
        Number number = (Number) value;
        if (clazz == Double.class) {
          v = number.doubleValue();
        } else if (clazz == Float.class) {
          v = number.floatValue();
        } else if (clazz == Long.class) {
          v = number.longValue();
        } else if (clazz == Integer.class) {
          v = number.intValue();
        } else if (clazz == Short.class) {
          v = number.shortValue();
        } else if (clazz == Byte.class) {
          v = number.byteValue();
        } else if (clazz == BigInteger.class && number instanceof BigDecimal) {
          v = ((BigDecimal) number).toBigIntegerExact();
        } else if (clazz == UnsignedLong.class
            && number instanceof BigDecimal) {
          // Throws if the value is negative or exceeds 2^64 - 1.
          v = UnsignedLong.valueOf(((BigDecimal) number).toBigIntegerExact());
        } else {
          v = value;
        }
      } else {
        v = ((Wrapper) value).o;
      }
      return clazz.cast(v);
    }

    /** Converts to a built-in. */
    public Object toBuiltIn(TypeSystem typeSystem, @Nullable Pos pos) {
      final BuiltIn builtIn = unwrap(BuiltIn.class);
      Object o = requireNonNull(Codes.BUILT_IN_VALUES.get(builtIn));
      final Pos pos2 = first(pos, this.pos);
      if (o instanceof Codes.Typed) {
        o = ((Codes.Typed) o).withType(typeSystem, type, pos2);
      }
      if (o instanceof Codes.Positioned) {
        o = ((Codes.Positioned) o).withPos(pos2);
      }
      return o;
    }

    /** Converts to an {@link Applicable} with one argument, or returns null. */
    public @Nullable Applicable1 toApplicable1(
        TypeSystem typeSystem, @Nullable Pos pos) {
      Object o = toBuiltIn(typeSystem, pos);
      if (o instanceof Applicable2) {
        return ((Applicable2) o).curry();
      }
      if (o instanceof Applicable3) {
        return ((Applicable3) o).curry();
      }
      return null;
    }

    /** Converts to an {@link Applicable2}, or returns null. */
    public @Nullable Applicable2 toApplicable2(
        TypeSystem typeSystem, @Nullable Pos pos) {
      Object o = toBuiltIn(typeSystem, pos);
      if (o instanceof Applicable2) {
        return (Applicable2) o;
      }
      return null;
    }

    /** Converts to an {@link Applicable3}, or returns null. */
    public @Nullable Applicable3 toApplicable3(
        TypeSystem typeSystem, @Nullable Pos pos) {
      Object o = toBuiltIn(typeSystem, pos);
      if (o instanceof Applicable3) {
        return (Applicable3) o;
      }
      return null;
    }

    /** Converts to an {@link Applicable4}, or returns null. */
    public @Nullable Applicable4 toApplicable4(
        TypeSystem typeSystem, @Nullable Pos pos) {
      Object o = toBuiltIn(typeSystem, pos);
      if (o instanceof Applicable4) {
        return (Applicable4) o;
      }
      return null;
    }

    @Override
    public int hashCode() {
      return value.hashCode();
    }

    @Override
    public boolean equals(Object o) {
      return o == this
          || o instanceof Literal && value.equals(((Literal) o).value);
    }

    @Override
    public Exp accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override
    AstWriter unparse(AstWriter w, int left, int right) {
      switch (op) {
        case VALUE_LITERAL:
          // Generate the original expression from which this value was derived.
          return ((Wrapper) value).exp.unparse(w, left, right);
        case INTERNAL_LITERAL:
          // Print the value as if it were a string.
          return w.appendLiteral(((Wrapper) value).o.toString());
        case WORD_LITERAL:
          return w.appendLiteral(unwrap(UnsignedLong.class));
      }
      return w.appendLiteral(value);
    }

    @Override
    public boolean isConstant() {
      return true;
    }
  }

  /** Base class for declarations. */
  public abstract static class Decl extends BaseNode {
    Decl(Pos pos, Op op) {
      super(pos, op);
    }

    @Override
    public abstract Decl accept(Shuttle shuttle);
  }

  /** Overloaded operator declaration. */
  public static class OverDecl extends Decl {
    public final IdPat pat;

    OverDecl(IdPat pat) {
      super(Pos.ZERO, Op.OVER_DECL);
      this.pat = requireNonNull(pat);
    }

    @Override
    public int hashCode() {
      return hash(Op.OVER_DECL, pat);
    }

    @Override
    public boolean equals(Object o) {
      return o == this
          || o instanceof OverDecl && pat.equals(((OverDecl) o).pat);
    }

    @Override
    AstWriter unparse(AstWriter w, int left, int right) {
      return w.append("over ").append(pat.name);
    }

    @Override
    public OverDecl accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }
  }

  /** Type declaration. */
  public static class TypeDecl extends Decl {
    public final List<AliasType> types;

    TypeDecl(ImmutableList<AliasType> types) {
      super(Pos.ZERO, Op.TYPE_DECL);
      this.types = requireNonNull(types);
      checkArgument(!this.types.isEmpty());
    }

    @Override
    public int hashCode() {
      return hash(types);
    }

    @Override
    public boolean equals(Object o) {
      return o == this
          || o instanceof TypeDecl && types.equals(((TypeDecl) o).types);
    }

    @Override
    AstWriter unparse(AstWriter w, int left, int right) {
      forEachIndexed(
          types,
          (type, i) ->
              w.append(i == 0 ? "type " : " and ").append(type.toString()));
      return w;
    }

    @Override
    public TypeDecl accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }
  }

  /** Datatype declaration. */
  public static class DatatypeDecl extends Decl {
    public final List<DataType> dataTypes;

    DatatypeDecl(ImmutableList<DataType> dataTypes) {
      super(Pos.ZERO, Op.DATATYPE_DECL);
      this.dataTypes = requireNonNull(dataTypes);
      checkArgument(!this.dataTypes.isEmpty());
    }

    @Override
    public int hashCode() {
      return hash(dataTypes);
    }

    @Override
    public boolean equals(Object o) {
      return o == this
          || o instanceof DatatypeDecl
              && dataTypes.equals(((DatatypeDecl) o).dataTypes);
    }

    @Override
    AstWriter unparse(AstWriter w, int left, int right) {
      forEachIndexed(
          dataTypes,
          (dataType, i) ->
              w.append(i == 0 ? "datatype " : " and ")
                  .append(dataType.toString()));
      return w;
    }

    @Override
    public DatatypeDecl accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }
  }

  /** Abstract (recursive or non-recursive) value declaration. */
  public abstract static class ValDecl extends Decl {
    ValDecl(Pos pos, Op op) {
      super(pos, op);
    }

    @Override
    public abstract ValDecl accept(Shuttle shuttle);

    public abstract void forEachBinding(BindingConsumer consumer);

    /** Returns all patterns bound. */
    public List<NamedPat> boundPats() {
      final ImmutableList.Builder<Core.NamedPat> list = ImmutableList.builder();
      forEachBinding((pat, exp2, overloadPat, pos) -> list.add(pat));
      return list.build();
    }
  }

  /** Consumer of bindings. */
  @FunctionalInterface
  public interface BindingConsumer {
    void accept(NamedPat pat, Exp exp, @Nullable IdPat overloadPat, Pos pos);
  }

  /**
   * Non-recursive value declaration.
   *
   * @see RecValDecl#list
   */
  public static class NonRecValDecl extends ValDecl {
    public final NamedPat pat;
    public final Exp exp;
    /** If an 'inst', the overloaded name, otherwise null. */
    public final Core.@Nullable IdPat overloadPat;

    NonRecValDecl(NamedPat pat, Exp exp, @Nullable IdPat overloadPat, Pos pos) {
      super(pos, Op.VAL_DECL);
      this.pat = pat;
      this.exp = exp;
      this.overloadPat = overloadPat;
      if (!canAssign(exp.type, pat.type)) {
        throw new IllegalArgumentException(
            format(
                "cannot assign '%s' (type '%s') to pattern '%s' (type '%s')",
                exp, exp.type, pat, pat.type));
      }
    }

    @Override
    public int hashCode() {
      return hash(pat, exp);
    }

    @Override
    public boolean equals(Object o) {
      return o == this
          || o instanceof NonRecValDecl
              && pat.equals(((NonRecValDecl) o).pat)
              && exp.equals(((NonRecValDecl) o).exp);
    }

    @Override
    AstWriter unparse(AstWriter w, int left, int right) {
      return w.append("val ")
          .append(pat, 0, 0)
          .append(" = ")
          .append(exp, 0, right);
    }

    @Override
    public NonRecValDecl accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    public NonRecValDecl copy(
        NamedPat pat, Exp exp, @Nullable IdPat overloadPat) {
      return pat == this.pat
              && exp == this.exp
              && overloadPat == this.overloadPat
          ? this
          : core.nonRecValDecl(pos, pat, overloadPat, exp);
    }

    @Override
    public void forEachBinding(BindingConsumer consumer) {
      consumer.accept(pat, exp, overloadPat, pos);
    }
  }

  /** Recursive value declaration. */
  public static class RecValDecl extends ValDecl {
    public final ImmutableList<NonRecValDecl> list;

    RecValDecl(ImmutableList<NonRecValDecl> list) {
      super(Pos.ZERO, Op.REC_VAL_DECL);
      this.list = requireNonNull(list);
    }

    @Override
    public int hashCode() {
      return list.hashCode();
    }

    @Override
    public boolean equals(Object o) {
      return o == this
          || o instanceof RecValDecl && list.equals(((RecValDecl) o).list);
    }

    @Override
    AstWriter unparse(AstWriter w, int left, int right) {
      w.append("val rec ");
      forEachIndexed(
          list,
          (decl, i) ->
              w.append(i == 0 ? "" : " and ")
                  .append(decl.pat, 0, 0)
                  .append(" = ")
                  .append(decl.exp, 0, right));
      return w;
    }

    @Override
    public RecValDecl accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override
    public void forEachBinding(BindingConsumer consumer) {
      list.forEach(b -> b.forEachBinding(consumer));
    }

    public RecValDecl copy(List<NonRecValDecl> list) {
      return list.equals(this.list) ? this : core.recValDecl(list);
    }
  }

  /** Tuple expression. Also implements record expression. */
  // TODO: remove, replace with a call to the constructor of the n-tuple type?
  public static class Tuple extends Exp {
    public final List<Exp> args;

    Tuple(RecordLikeType type, ImmutableList<Exp> args) {
      super(Pos.ZERO, Op.TUPLE, type);
      checkArgument(
          !(type instanceof PrimitiveType) || type == PrimitiveType.UNIT,
          "primitive type '%s' is not a tuple",
          type);
      this.args = ImmutableList.copyOf(args);
    }

    @Override
    public boolean equals(Object o) {
      return this == o
          || o instanceof Tuple
              && args.equals(((Tuple) o).args)
              && type.equals(((Tuple) o).type);
    }

    @Override
    public int hashCode() {
      return hash(args, type);
    }

    @Override
    public RecordLikeType type() {
      return (RecordLikeType) type;
    }

    @Override
    public void forEachArg(ObjIntConsumer<Exp> action) {
      forEachIndexed(args, action);
    }

    @Override
    public Exp arg(int i) {
      return args.get(i);
    }

    @Override
    public Exp accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override
    AstWriter unparse(AstWriter w, int left, int right) {
      if (type instanceof RecordType) {
        w.append("{");
        forEach(
            (i, name, exp) ->
                w.append(i > 0 ? ", " : "")
                    .append(name)
                    .append(" = ")
                    .append(exp, 0, 0));
        return w.append("}");
      } else {
        w.append("(");
        forEach(
            (i, name, arg) -> w.append(i == 0 ? "" : ", ").append(arg, 0, 0));
        return w.append(")");
      }
    }

    /**
     * Calls a consumer with the name, expression and ordinal of each field of
     * this tuple.
     */
    public void forEach(PairList.IndexedBiConsumer<String, Exp> consumer) {
      forEachIndexed(type().argNames(), args, consumer::accept);
    }

    public Tuple copy(TypeSystem typeSystem, List<Exp> args) {
      return args.equals(this.args)
          ? this
          : core.tuple(typeSystem, type(), args);
    }

    @Override
    public boolean isConstant() {
      return allMatch(args, Exp::isConstant);
    }
  }

  /** "Let" expression. */
  public static class Let extends Exp {
    public final ValDecl decl;
    public final Exp exp;

    Let(ValDecl decl, Exp exp) {
      super(Pos.ZERO, Op.LET, exp.type);
      this.decl = requireNonNull(decl);
      this.exp = requireNonNull(exp);
    }

    @Override
    AstWriter unparse(AstWriter w, int left, int right) {
      return w.append("let ")
          .append(decl, 0, 0)
          .append(" in ")
          .append(exp, 0, 0)
          .append(" end");
    }

    @Override
    public Exp accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    public Exp copy(ValDecl decl, Exp exp) {
      return decl == this.decl && exp == this.exp ? this : core.let(decl, exp);
    }
  }

  /** "Local" expression. */
  public static class Local extends Exp {
    public final DataType dataType;
    public final Exp exp;

    Local(DataType dataType, Exp exp) {
      super(Pos.ZERO, Op.LOCAL, exp.type);
      this.dataType = requireNonNull(dataType);
      this.exp = requireNonNull(exp);
    }

    @Override
    AstWriter unparse(AstWriter w, int left, int right) {
      return w.append("local datatype ")
          .append(dataType.toString())
          .append(" in ")
          .append(exp, 0, 0)
          .append(" end");
    }

    @Override
    public Exp accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    public Exp copy(DataType dataType, Exp exp) {
      return dataType == this.dataType && exp == this.exp
          ? this
          : core.local(dataType, exp);
    }
  }

  /**
   * Match.
   *
   * <p>In AST, there are several places that can deconstruct values via
   * patterns: {@link Ast.FunDecl fun}, {@link Ast.Fn fn}, {@link Ast.Let let},
   * {@link Ast.Case case}. But in Core, there is only {@code Match}, and {@code
   * Match} only occurs within {@link Ast.Case case}. This makes the Core
   * language a little more verbose than AST but a lot more uniform.
   */
  public static class Match extends BaseNode {
    public final Pat pat;
    public final Exp exp;

    Match(Pos pos, Pat pat, Exp exp) {
      super(pos, Op.MATCH);
      this.pat = pat;
      this.exp = exp;
    }

    @Override
    public Match accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override
    AstWriter unparse(AstWriter w, int left, int right) {
      return w.append(pat, 0, 0).append(" => ").append(exp, 0, right);
    }

    public Match copy(Pat pat, Exp exp) {
      return pat == this.pat && exp == this.exp
          ? this
          : core.match(pos, pat, exp);
    }
  }

  /** Lambda expression. */
  public static class Fn extends Exp {
    public final IdPat idPat;
    public final Exp exp;

    Fn(Pos pos, FnType type, IdPat idPat, Exp exp) {
      super(pos, Op.FN, type);
      this.idPat = requireNonNull(idPat);
      this.exp = requireNonNull(exp);
    }

    @Override
    public FnType type() {
      return (FnType) type;
    }

    @Override
    AstWriter unparse(AstWriter w, int left, int right) {
      return w.append("fn ")
          .append(idPat, 0, 0)
          .append(" => ")
          .append(exp, 0, right);
    }

    @Override
    public Exp accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    public Fn copy(IdPat idPat, Exp exp) {
      return idPat == this.idPat && exp == this.exp
          ? this
          : core.fn(type(), idPat, exp);
    }
  }

  /**
   * Raise expression: evaluates {@code exp} (of type {@code exn}) and throws.
   */
  public static class Raise extends Exp {
    public final Exp exp;

    Raise(Pos pos, Type type, Exp exp) {
      super(pos, Op.RAISE, type);
      this.exp = requireNonNull(exp);
    }

    @Override
    AstWriter unparse(AstWriter w, int left, int right) {
      return w.append("raise ").append(exp, 0, right);
    }

    @Override
    public Exp accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    public Raise copy(Type type, Exp exp) {
      return type == this.type && exp == this.exp
          ? this
          : new Raise(pos, type, exp);
    }
  }

  /**
   * Case expression.
   *
   * <p>Also implements {@link Ast.If}.
   */
  public static class Case extends Exp {
    public final Exp exp;
    public final List<Match> matchList;

    Case(Pos pos, Type type, Exp exp, ImmutableList<Match> matchList) {
      super(pos, Op.CASE, type);
      this.exp = exp;
      this.matchList = matchList;
    }

    @Override
    AstWriter unparse(AstWriter w, int left, int right) {
      return w.append("case ")
          .append(exp, 0, 0)
          .append(" of ")
          .appendAll(matchList, left, Op.BAR, right);
    }

    @Override
    public Exp accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    public Case copy(Exp exp, List<Match> matchList) {
      return exp == this.exp && matchList.equals(this.matchList)
          ? this
          : core.caseOf(pos, type, exp, matchList);
    }
  }

  /** From expression. */
  public static class From extends Exp {
    public final ImmutableList<FromStep> steps;

    From(Type type, ImmutableList<FromStep> steps) {
      super(Pos.ZERO, Op.FROM, type);
      this.steps = requireNonNull(steps);
    }

    @Override
    public boolean equals(Object o) {
      return this == o || o instanceof From && steps.equals(((From) o).steps);
    }

    @Override
    public int hashCode() {
      return steps.hashCode();
    }

    @Override
    public Exp accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override
    AstWriter unparse(AstWriter w, int left, int right) {
      if (left > op.left || op.right < right) {
        return w.append("(").append(this, 0, 0).append(")");
      } else {
        w.append("from");
        forEachIndexed(steps, (step, i) -> step.unparseStep(w, i, 0, 0));
        return w;
      }
    }

    /**
     * Copies this {@code From} with a new set of steps.
     *
     * <p>Returns this {@code From} if the steps are the same. If {@code env} is
     * not null, performs additional checking.
     */
    public Exp copy(
        TypeSystem typeSystem,
        @Nullable Environment env,
        List<FromStep> steps) {
      return steps.equals(this.steps)
          ? this
          : core.fromBuilder(typeSystem, env).addAll(steps).build();
    }

    /** Returns whether the collection of elements is ordered. */
    public boolean isOrdered() {
      return steps.isEmpty() || last(steps).env.ordered;
    }
  }

  /**
   * A step in a {@code from} expression - {@code where}, {@code group} or
   * {@code order}.
   */
  public abstract static class FromStep extends BaseNode {
    public final StepEnv env;

    FromStep(Op op, StepEnv env) {
      super(Pos.ZERO, op);
      this.env = requireNonNull(env);
    }

    /**
     * Returns whether the output of this step is ordered, given whether the
     * input is ordered.
     *
     * <p>For example, {@link Where} and {@link Yield} are ordered if and only
     * if their input is ordered; {@link Order} is always ordered; {@link
     * GroupStep} is unordered.
     */
    public boolean isOrdered(boolean inputIsOrdered) {
      return inputIsOrdered;
    }

    @Override
    final AstWriter unparse(AstWriter w, int left, int right) {
      return unparseStep(w, -1, left, right);
    }

    protected abstract AstWriter unparseStep(
        AstWriter w, int ordinal, int left, int right);

    @Override
    public abstract FromStep accept(Shuttle shuttle);
  }

  /** Environment for a step. */
  public static class StepEnv {
    public static final StepEnv EMPTY =
        new StepEnv(ImmutableList.of(), false, true);

    public final ImmutableList<Binding> bindings;
    public final boolean atom;
    public final boolean ordered;

    private StepEnv(List<Binding> bindings, boolean atom, boolean ordered) {
      this.bindings = ImmutableList.copyOf(bindings);
      this.atom = atom;
      this.ordered = ordered;
      checkArgument(!atom || bindings.size() == 1);
    }

    /** Creates a StepEnv. */
    public static StepEnv of(
        List<Binding> bindings, boolean atom, boolean ordered) {
      if (bindings.isEmpty() && !atom && ordered) {
        return EMPTY;
      }
      return new StepEnv(bindings, atom, ordered);
    }

    /** Returns a copy of this env with the given {@code ordered} property. */
    public StepEnv withOrdered(boolean ordered) {
      if (ordered == this.ordered) {
        return this;
      }
      return new StepEnv(bindings, atom, ordered);
    }

    /** Returns a copy of this env with the given bindings. */
    public StepEnv withBindings(Iterable<? extends Binding> bindings) {
      if (bindings.equals(this.bindings)) {
        return this;
      }
      final ImmutableList<Binding> bindings2 = ImmutableList.copyOf(bindings);
      if (bindings2.equals(this.bindings)) {
        return this;
      }
      return new StepEnv(bindings2, atom, ordered);
    }

    @Override
    public int hashCode() {
      return hash(bindings, atom, ordered);
    }

    @Override
    public boolean equals(Object obj) {
      return this == obj
          || obj instanceof StepEnv
              && this.atom == ((StepEnv) obj).atom
              && this.ordered == ((StepEnv) obj).ordered
              && this.bindings.equals(((StepEnv) obj).bindings);
    }
  }

  /**
   * A {@code join} or {@code v in listExpr} or {@code v = expr} clause in a
   * {@code from} expression.
   */
  public static class Scan extends FromStep {
    public final Pat pat;
    public final Exp exp;
    public final Exp condition;

    Scan(Op op, StepEnv env, Pat pat, Exp exp, Exp condition) {
      super(op, env.withOrdered(env.ordered && exp.type instanceof ListType));
      checkArgument(op.isJoin(), "not a join: %s", op);
      this.pat = requireNonNull(pat, "pat");
      this.exp = requireNonNull(exp, "exp");
      this.condition = requireNonNull(condition, "condition");
      if (!exp.type.isCollection()) {
        throw new IllegalArgumentException(
            "scan expression must be list or bag: " + exp.type);
      }
      if (!canAssign(exp.type.elementType(), pat.type)) {
        throw new IllegalArgumentException(
            format("cannot assign elements of %s to %s", exp.type, pat.type));
      }
    }

    /**
     * {@inheritDoc}
     *
     * <p>A {@code Scan} is ordered only if the input is ordered and {@link
     * #exp} is ordered. Think of it as like nested loops join.
     *
     * <p>If a {@code Scan} is the first step in the {@code from}, we think of
     * its input as an ordered list containing {@code unit}. Therefore, its
     * output is ordered if {@code exp} is a {@code list}.
     */
    @Override
    public boolean isOrdered(boolean inputIsOrdered) {
      return inputIsOrdered && exp.type instanceof ListType;
    }

    @Override
    public Scan accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override
    protected AstWriter unparseStep(
        AstWriter w, int ordinal, int left, int right) {
      final String keyword;
      switch (op) {
        case LEFT_JOIN:
          keyword = " left join ";
          break;
        case RIGHT_JOIN:
          keyword = " right join ";
          break;
        case FULL_JOIN:
          keyword = " full join ";
          break;
        default:
          keyword = ordinal == 0 ? " " : " join ";
      }
      w.append(keyword)
          // for these purposes 'in' has same precedence as '='
          .append(pat, 0, Op.EQ.left);
      if (Extents.isInfinite(exp)) {
        // Print "from x : int" rather "from x in extent 'int'"
        w.append(" : ").append(exp.type.elementType().moniker());
      } else {
        w.append(" in ").append(exp, Op.EQ.right, 0);
      }
      if (!condition.isBoolLiteral(true)) {
        w.append(" on ").append(condition, 0, 0);
      }
      return w;
    }

    public Scan copy(StepEnv env, Pat pat, Exp exp, Exp condition) {
      return pat == this.pat
              && exp == this.exp
              && condition == this.condition
              && env.equals(this.env)
          ? this
          : core.scan(op, env, pat, exp, condition);
    }
  }

  /** A {@code where} clause in a {@code from} expression. */
  public static class Where extends FromStep {
    public final Exp exp;

    Where(StepEnv env, Exp exp) {
      super(Op.WHERE, env);
      this.exp = requireNonNull(exp, "exp");
    }

    @Override
    public Where accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override
    protected AstWriter unparseStep(
        AstWriter w, int ordinal, int left, int right) {
      return w.append(" where ").append(exp, 0, 0);
    }

    public Where copy(Exp exp, StepEnv env) {
      return exp == this.exp && env.equals(this.env)
          ? this
          : core.where(env, exp);
    }
  }

  /** A {@code skip} clause in a {@code from} expression. */
  public static class SkipStep extends FromStep {
    public final Exp exp;

    SkipStep(Core.StepEnv env, Exp exp) {
      super(Op.SKIP, env);
      this.exp = requireNonNull(exp, "exp");
    }

    @Override
    public SkipStep accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override
    protected AstWriter unparseStep(
        AstWriter w, int ordinal, int left, int right) {
      return w.append(" skip ").append(exp, 0, 0);
    }

    public SkipStep copy(Exp exp, Core.StepEnv env) {
      return exp == this.exp && env.equals(this.env)
          ? this
          : core.skip(env, exp);
    }
  }

  /** A {@code take} clause in a {@code from} expression. */
  public static class TakeStep extends FromStep {
    public final Exp exp;

    TakeStep(Core.StepEnv env, Exp exp) {
      super(Op.TAKE, env);
      this.exp = requireNonNull(exp, "exp");
    }

    @Override
    public TakeStep accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override
    protected AstWriter unparseStep(
        AstWriter w, int ordinal, int left, int right) {
      return w.append(" take ").append(exp, 0, 0);
    }

    public TakeStep copy(Exp exp, Core.StepEnv env) {
      return exp == this.exp && env.equals(this.env)
          ? this
          : core.take(env, exp);
    }
  }

  /**
   * Base class of {@link ExceptStep}, {@link IntersectStep}, {@link UnionStep}.
   */
  public abstract static class SetStep extends FromStep {
    public final boolean distinct;
    public final ImmutableList<Exp> args;

    SetStep(Op op, StepEnv env, boolean distinct, ImmutableList<Exp> args) {
      super(op, env);
      this.distinct = distinct;
      this.args = requireNonNull(args, "args");
    }

    @Override
    protected AstWriter unparseStep(
        AstWriter w, int ordinal, int left, int right) {
      forEachIndexed(
          args,
          (arg, i) -> w.append(i == 0 ? op.padded : ", ").append(arg, 0, 0));
      return w;
    }

    public abstract SetStep copy(boolean distinct, List<Exp> args, StepEnv env);

    @Override
    public boolean isOrdered(boolean inputIsOrdered) {
      // The output is ordered if input and all arguments are ordered.
      return inputIsOrdered
          && allMatch(args, arg -> arg.type instanceof ListType);
    }
  }

  /** An {@code except} clause in a {@code from} expression. */
  public static class ExceptStep extends SetStep {
    ExceptStep(StepEnv env, boolean distinct, ImmutableList<Exp> args) {
      super(
          Op.EXCEPT,
          env.withOrdered(
              env.ordered
                  && allMatch(args, arg -> arg.type instanceof ListType)),
          distinct,
          args);
    }

    @Override
    public ExceptStep accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override
    public ExceptStep copy(boolean distinct, List<Exp> args, StepEnv env) {
      return distinct == this.distinct
              && args.equals(this.args)
              && env.equals(this.env)
          ? this
          : core.except(env, distinct, args);
    }
  }

  /** An {@code intersect} clause in a {@code from} expression. */
  public static class IntersectStep extends SetStep {
    IntersectStep(StepEnv env, boolean distinct, ImmutableList<Exp> args) {
      super(
          Op.INTERSECT,
          env.withOrdered(
              env.ordered
                  && allMatch(args, arg -> arg.type instanceof ListType)),
          distinct,
          args);
    }

    @Override
    public IntersectStep accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override
    public IntersectStep copy(boolean distinct, List<Exp> args, StepEnv env) {
      return distinct == this.distinct
              && args.equals(this.args)
              && env.equals(this.env)
          ? this
          : core.intersect(env, distinct, args);
    }
  }

  /** A {@code union} clause in a {@code from} expression. */
  public static class UnionStep extends SetStep {
    UnionStep(StepEnv env, boolean distinct, ImmutableList<Exp> args) {
      super(
          Op.UNION,
          env.withOrdered(
              env.ordered
                  && allMatch(args, arg -> arg.type instanceof ListType)),
          distinct,
          args);
    }

    @Override
    public UnionStep accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override
    public UnionStep copy(boolean distinct, List<Exp> args, StepEnv env) {
      return distinct == this.distinct
              && args.equals(this.args)
              && env.equals(this.env)
          ? this
          : core.union(env, distinct, args);
    }
  }

  /** An {@code order} clause in a {@code from} expression. */
  public static class Order extends FromStep {
    public final Exp exp;

    Order(Core.StepEnv env, Exp exp) {
      super(Op.ORDER, env.withOrdered(true));
      this.exp = requireNonNull(exp);
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code Order} is always ordered. If there are zero keys, or the keys
     * not exhaustive, the order is not deterministic, but the ordering is still
     * clearly part of the information in the value.
     */
    @Override
    public boolean isOrdered(boolean inputIsOrdered) {
      return true;
    }

    @Override
    public Order accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override
    protected AstWriter unparseStep(
        AstWriter w, int ordinal, int left, int right) {
      return w.append(" order ").append(exp, 0, right);
    }

    public Order copy(Core.StepEnv env, Core.Exp exp) {
      return env.equals(this.env) && exp.equals(this.exp)
          ? this
          : core.order(env, exp);
    }
  }

  /** A {@code group} clause in a {@code from} expression. */
  public static class GroupStep extends FromStep {
    public final SortedMap<Core.IdPat, Exp> groupExps;
    public final SortedMap<Core.IdPat, Aggregate> aggregates;

    GroupStep(
        Core.StepEnv env,
        ImmutableSortedMap<Core.IdPat, Exp> groupExps,
        ImmutableSortedMap<Core.IdPat, Aggregate> aggregates) {
      super(Op.GROUP, env);
      this.groupExps = groupExps;
      this.aggregates = aggregates;
      checkArgument(!env.atom || groupExps.size() + aggregates.size() == 1);
    }

    @Override
    public GroupStep accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override
    protected AstWriter unparseStep(
        AstWriter w, int ordinal, int left, int right) {
      if (env.atom) {
        if (aggregates.isEmpty()) {
          w.append(" group ").append(only(groupExps.values()), 0, right);
        } else {
          w.append(" group {} compute ")
              .append(only(aggregates.values()), 0, right);
        }
      } else {
        w.append(" group");
        Pair.forEachIndexed( // lint:skip
            groupExps,
            (i, id, exp) ->
                w.append(i == 0 ? " {" : ", ")
                    .append(id, 0, 0)
                    .append(" = ")
                    .append(exp, 0, 0));
        Pair.forEachIndexed( // lint:skip
            aggregates,
            (i, name, aggregate) ->
                w.append(i == 0 ? "} compute {" : ", ")
                    .append(name, 0, 0)
                    .append(" = ")
                    .append(aggregate, 0, 0));
        w.append("}");
      }
      return w;
    }

    public GroupStep copy(
        boolean atom,
        SortedMap<Core.IdPat, Exp> groupExps,
        SortedMap<Core.IdPat, Aggregate> aggregates) {
      return atom == env.atom
              && groupExps.equals(this.groupExps)
              && aggregates.equals(this.aggregates)
          ? this
          : core.group(atom, env.ordered, groupExps, aggregates);
    }
  }

  /** Step that converts the stream to an unordered collection. */
  public static class UnorderStep extends FromStep {
    UnorderStep(Core.StepEnv env) {
      super(Op.UNORDER, env.withOrdered(false));
    }

    @Override
    public boolean isOrdered(boolean inputIsOrdered) {
      return false;
    }

    @Override
    protected AstWriter unparseStep(
        AstWriter w, int ordinal, int left, int right) {
      return w.append(" unorder");
    }

    @Override
    public UnorderStep accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    public UnorderStep copy(Core.StepEnv env) {
      return env.equals(this.env) ? this : core.unorder(env);
    }
  }

  /** Step that computes an expression. */
  public static class Yield extends FromStep {
    public final Exp exp;

    Yield(Core.StepEnv env, Exp exp) {
      super(Op.YIELD, env);
      this.exp = exp;
    }

    @Override
    protected AstWriter unparseStep(
        AstWriter w, int ordinal, int left, int right) {
      return w.append(" yield ").append(exp, 0, 0);
    }

    @Override
    public Yield accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    public Yield copy(Core.StepEnv env, Exp exp) {
      return env.equals(this.env) && exp == this.exp
          ? this
          : core.yield_(env, exp);
    }
  }

  /**
   * Node in a relational tree: an operator whose inputs are collections and
   * whose value is a collection of a definite type.
   *
   * <p>This is the balanced representation that replaces the step list of
   * {@link From}; see {@code spec.md} for the normative description, and {@code
   * plan.md} for the sequence in which it lands. In step 1 the tree is a
   * shadow: it is built and printed, but {@code From} still does the work.
   *
   * <p>A node is an {@link Exp} whose type is a collection type. Therefore a
   * node's input is simply an expression: a nested node, or a leaf such as
   * {@code scott.emps} or {@code [1, 2]}. There is no scan operator; any
   * collection-valued expression will do, and the node above it names its
   * element {@code $0}.
   *
   * <p>Unlike a {@link FromStep}, a node carries no bindings. Its element type
   * is derived from its inputs and its expressions, and is exactly the type of
   * the value that flows out of it. Expressions inside a node name the input
   * element {@code $0} (and, in a {@link Join}, the right input element {@code
   * $1}); the exception is {@link ProjectMany}, whose lambda parameter names
   * the input element, because its body may contain a tree that would shadow
   * {@code $0}.
   */
  public abstract static class Rel extends Exp {
    Rel(Op op, Type type) {
      super(Pos.ZERO, op, type);
      if (!type.isCollection()) {
        throw new IllegalArgumentException("not a collection type: " + type);
      }
    }

    /** Returns the type of the elements of this node's output. */
    public Type elementType() {
      return type.elementType();
    }

    /**
     * Returns whether this node's output is ordered, that is, a {@code list}
     * rather than a {@code bag}.
     */
    public boolean isOrdered() {
      return type instanceof ListType;
    }

    /** Returns the name of this node's operator, as it appears in plan text. */
    public abstract String opName();

    /** Returns this node's inputs. */
    public abstract List<Exp> inputs();

    /**
     * Appends this node's arguments, each in brackets, to a plan-text line.
     *
     * <p>Arguments that carry no information (an inner join's kind, a condition
     * that is {@code true}) are omitted.
     */
    protected void describeArgs(StringBuilder b) {}

    /** Returns this node's plan text, as {@code Sys.plan} prints it. */
    public String describe() {
      return describe(false);
    }

    /**
     * Returns this node's plan text; if {@code withTypes}, appends the
     * collection type of every node, as {@code Sys.planEx} prints it.
     */
    public String describe(boolean withTypes) {
      final StringBuilder b = new StringBuilder();
      describe(b, 0, withTypes);
      return b.toString();
    }

    protected void describe(StringBuilder b, int indent, boolean withTypes) {
      describeLine(b, indent, withTypes);
      for (Exp input : inputs()) {
        describeInput(input, b, indent + 2, withTypes);
      }
    }

    protected void describeLine(
        StringBuilder b, int indent, boolean withTypes) {
      indent(b, indent);
      b.append(opName());
      describeArgs(b);
      if (withTypes) {
        b.append(" : ").append(type.moniker());
      }
      b.append('\n');
    }

    /**
     * Appends an input to plan text: a node prints as a node, and any other
     * expression prints as a leaf line.
     */
    static void describeInput(
        Exp input, StringBuilder b, int indent, boolean withTypes) {
      if (input instanceof Rel) {
        ((Rel) input).describe(b, indent, withTypes);
        return;
      }
      indent(b, indent);
      b.append(input);
      if (withTypes) {
        b.append(" : ").append(input.type.moniker());
      }
      b.append('\n');
    }

    static void indent(StringBuilder b, int indent) {
      for (int i = 0; i < indent; i++) {
        b.append(' ');
      }
    }

    /** Appends an argument, in brackets, to a plan-text line. */
    protected static void arg(StringBuilder b, Object arg) {
      b.append(" [").append(arg).append(']');
    }

    /** Appends a named-argument list, in brackets, to a plan-text line. */
    protected static void args(
        StringBuilder b, Map<String, ? extends AstNode> map) {
      b.append(" [");
      int i = 0;
      for (Map.Entry<String, ? extends AstNode> entry : map.entrySet()) {
        if (i++ > 0) {
          b.append(", ");
        }
        b.append(entry.getKey()).append(" = ").append(entry.getValue());
      }
      b.append(']');
    }

    @Override
    AstWriter unparse(AstWriter w, int left, int right) {
      return w.append(describe());
    }

    /** How a {@link Join} treats elements that have no match. */
    public enum JoinType {
      INNER,
      LEFT,
      RIGHT,
      FULL;

      /**
       * Returns whether the left element is optional, and therefore whether
       * {@code $0} has an {@code option} type.
       */
      public boolean leftIsOption() {
        return this == RIGHT || this == FULL;
      }

      /**
       * Returns whether the right element is optional, and therefore whether
       * {@code $1} has an {@code option} type.
       */
      public boolean rightIsOption() {
        return this == LEFT || this == FULL;
      }

      /** Returns the name of this join type, as it appears in plan text. */
      public String opName() {
        return name().toLowerCase(Locale.ROOT);
      }
    }
  }

  /** Node with one input. */
  public abstract static class SingleRel extends Rel {
    public final Exp input;

    SingleRel(Op op, Type type, Exp input) {
      super(op, type);
      this.input = requireNonNull(input, "input");
    }

    @Override
    public List<Exp> inputs() {
      return ImmutableList.of(input);
    }
  }

  /**
   * Removes the elements for which a condition, an expression over {@code $0},
   * is false.
   */
  public static class Filter extends SingleRel {
    public final Exp condition;

    Filter(Exp input, Exp condition) {
      super(Op.FILTER, input.type, input);
      this.condition = requireNonNull(condition, "condition");
    }

    @Override
    public String opName() {
      return "filter";
    }

    @Override
    protected void describeArgs(StringBuilder b) {
      arg(b, condition);
    }

    @Override
    public Filter accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    public Filter copy(Exp input, Exp condition) {
      return input == this.input && condition == this.condition
          ? this
          : core.filter(input, condition);
    }
  }

  /** Maps each element to one element, via an expression over {@code $0}. */
  public static class Project extends SingleRel {
    public final Exp exp;

    Project(Type type, Exp input, Exp exp) {
      super(Op.PROJECT, type, input);
      this.exp = requireNonNull(exp, "exp");
    }

    @Override
    public String opName() {
      return "project";
    }

    @Override
    protected void describeArgs(StringBuilder b) {
      arg(b, exp);
    }

    @Override
    public Project accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    public Project copy(TypeSystem typeSystem, Exp input, Exp exp) {
      return input == this.input && exp == this.exp
          ? this
          : core.project(typeSystem, input, exp);
    }
  }

  /**
   * Maps each element to many elements: monadic bind, and what a dependent scan
   * becomes.
   *
   * <p>The parameter {@link #param} names the input element throughout the
   * body, in place of {@code $0}, because the body may be a tree and would
   * otherwise shadow it. The node is correlated if, and only if, a leaf of the
   * body mentions the parameter.
   */
  public static class ProjectMany extends SingleRel {
    public final IdPat param;
    public final Exp body;

    ProjectMany(Type type, Exp input, IdPat param, Exp body) {
      super(Op.PROJECT_MANY, type, input);
      this.param = requireNonNull(param, "param");
      this.body = requireNonNull(body, "body");
    }

    @Override
    public String opName() {
      return "projectMany";
    }

    @Override
    protected void describe(StringBuilder b, int indent, boolean withTypes) {
      describeLine(b, indent, withTypes);
      describeInput(input, b, indent + 2, withTypes);
      indent(b, indent + 2);
      b.append("fn ").append(param.name).append(" =>").append('\n');
      describeInput(body, b, indent + 4, withTypes);
    }

    @Override
    public ProjectMany accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    public ProjectMany copy(
        TypeSystem typeSystem, Exp input, IdPat param, Exp body) {
      return input == this.input && param == this.param && body == this.body
          ? this
          : core.projectMany(typeSystem, input, param, body);
    }
  }

  /**
   * Pairs elements of two inputs, and maps each pair to an element via a yield
   * expression over {@code $0} and {@code $1}.
   *
   * <p>Commuting a join swaps its inputs and substitutes {@code $0} for {@code
   * $1} and vice versa in the condition and the yield; the element type is
   * unchanged, so nothing above the node rewrites.
   */
  public static class Join extends Rel {
    public final Rel.JoinType joinType;
    public final Exp left;
    public final Exp right;
    public final Exp condition;
    public final Exp yieldExp;

    Join(
        Type type,
        Rel.JoinType joinType,
        Exp left,
        Exp right,
        Exp condition,
        Exp yieldExp) {
      super(Op.JOIN, type);
      this.joinType = requireNonNull(joinType, "joinType");
      this.left = requireNonNull(left, "left");
      this.right = requireNonNull(right, "right");
      this.condition = requireNonNull(condition, "condition");
      this.yieldExp = requireNonNull(yieldExp, "yieldExp");
    }

    @Override
    public String opName() {
      return "join";
    }

    @Override
    public List<Exp> inputs() {
      return ImmutableList.of(left, right);
    }

    @Override
    protected void describeArgs(StringBuilder b) {
      if (joinType != Rel.JoinType.INNER) {
        arg(b, joinType.opName());
      }
      if (!condition.isBoolLiteral(true)) {
        arg(b, condition);
      }
      arg(b, yieldExp);
    }

    @Override
    public Join accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    public Join copy(
        TypeSystem typeSystem,
        Rel.JoinType joinType,
        Exp left,
        Exp right,
        Exp condition,
        Exp yieldExp) {
      return joinType == this.joinType
              && left == this.left
              && right == this.right
              && condition == this.condition
              && yieldExp == this.yieldExp
          ? this
          : core.join(typeSystem, joinType, left, right, condition, yieldExp);
    }
  }

  /**
   * Groups elements by zero or more keys, computing zero or more aggregates.
   *
   * <p>Keys and aggregate arguments are expressions over {@code $0}; the labels
   * are the output record's labels. {@code distinct} is this node with the
   * whole element as its only key and no aggregates.
   */
  public static class Group extends SingleRel {
    public final ImmutableSortedMap<String, Exp> keys;
    public final ImmutableSortedMap<String, Aggregate> aggregates;

    Group(
        Type type,
        Exp input,
        ImmutableSortedMap<String, Exp> keys,
        ImmutableSortedMap<String, Aggregate> aggregates) {
      super(Op.GROUP, type, input);
      this.keys = requireNonNull(keys, "keys");
      this.aggregates = requireNonNull(aggregates, "aggregates");
    }

    @Override
    public String opName() {
      return "group";
    }

    @Override
    protected void describeArgs(StringBuilder b) {
      args(b, keys);
      if (!aggregates.isEmpty()) {
        args(b, aggregates);
      }
    }

    @Override
    public Group accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    public Group copy(
        TypeSystem typeSystem,
        Exp input,
        SortedMap<String, Exp> keys,
        SortedMap<String, Aggregate> aggregates) {
      return input == this.input
              && keys.equals(this.keys)
              && aggregates.equals(this.aggregates)
          ? this
          : core.group(typeSystem, input, keys, aggregates);
    }
  }

  /**
   * Yields one element where the input has none, and the input's elements where
   * it has some.
   *
   * <p>This is what makes an apply outer: {@code from r in orders left join i
   * in r.items on p} keeps an order none of whose items match, and the element
   * to keep it as -- `{i = NONE, r = r}` -- is this node's expression.
   *
   * <p>The expression is evaluated only when there is no element, so, like the
   * count of a {@link Skip}, it cannot mention {@code $0}. It can mention
   * whatever the tree's enclosing environment binds, which inside the body of a
   * {@link ProjectMany} includes that node's parameter.
   */
  public static class IfEmpty extends SingleRel {
    public final Exp exp;

    IfEmpty(Exp input, Exp exp) {
      super(Op.IF_EMPTY, input.type, input);
      this.exp = requireNonNull(exp, "exp");
    }

    @Override
    public String opName() {
      return "ifEmpty";
    }

    @Override
    protected void describeArgs(StringBuilder b) {
      arg(b, exp);
    }

    @Override
    public IfEmpty accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    public IfEmpty copy(Exp input, Exp exp) {
      return input == this.input && exp == this.exp
          ? this
          : core.ifEmpty(input, exp);
    }
  }

  /**
   * Sorts elements by an expression over {@code $0}; always yields a {@code
   * list}.
   */
  public static class Sort extends SingleRel {
    public final Exp exp;

    Sort(Type type, Exp input, Exp exp) {
      super(Op.SORT, type, input);
      this.exp = requireNonNull(exp, "exp");
    }

    @Override
    public String opName() {
      return "sort";
    }

    @Override
    protected void describeArgs(StringBuilder b) {
      arg(b, exp);
    }

    @Override
    public Sort accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    public Sort copy(TypeSystem typeSystem, Exp input, Exp exp) {
      return input == this.input && exp == this.exp
          ? this
          : core.sort(typeSystem, input, exp);
    }
  }

  /** Discards order; always yields a {@code bag}. */
  public static class Unorder extends SingleRel {
    Unorder(Type type, Exp input) {
      super(Op.UNORDER, type, input);
    }

    @Override
    public String opName() {
      return "unorder";
    }

    @Override
    public Unorder accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    public Unorder copy(TypeSystem typeSystem, Exp input) {
      return input == this.input ? this : core.unorder(typeSystem, input);
    }
  }

  /**
   * Discards the first {@code count} elements.
   *
   * <p>{@code count} is evaluated once, before the first element exists, and
   * therefore cannot mention {@code $0}.
   */
  public static class Skip extends SingleRel {
    public final Exp count;

    Skip(Exp input, Exp count) {
      super(Op.SKIP, input.type, input);
      this.count = requireNonNull(count, "count");
    }

    @Override
    public String opName() {
      return "skip";
    }

    @Override
    protected void describeArgs(StringBuilder b) {
      arg(b, count);
    }

    @Override
    public Skip accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    public Skip copy(Exp input, Exp count) {
      return input == this.input && count == this.count
          ? this
          : core.skip(input, count);
    }
  }

  /**
   * Keeps the first {@code count} elements.
   *
   * <p>{@code count} is evaluated once, before the first element exists, and
   * therefore cannot mention {@code $0}.
   */
  public static class Take extends SingleRel {
    public final Exp count;

    Take(Exp input, Exp count) {
      super(Op.TAKE, input.type, input);
      this.count = requireNonNull(count, "count");
    }

    @Override
    public String opName() {
      return "take";
    }

    @Override
    protected void describeArgs(StringBuilder b) {
      arg(b, count);
    }

    @Override
    public Take accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    public Take copy(Exp input, Exp count) {
      return input == this.input && count == this.count
          ? this
          : core.take(input, count);
    }
  }

  /**
   * Base class of {@link Union}, {@link Intersect} and {@link Except}, which
   * combine the elements of two or more inputs of the same element type.
   */
  public abstract static class SetRel extends Rel {
    public final boolean distinct;
    public final ImmutableList<Exp> inputs;

    SetRel(Op op, Type type, boolean distinct, ImmutableList<Exp> inputs) {
      super(op, type);
      this.distinct = distinct;
      this.inputs = requireNonNull(inputs, "inputs");
      if (inputs.size() < 2) {
        throw new IllegalArgumentException(
            "set operator needs at least two inputs: " + inputs.size());
      }
    }

    @Override
    public List<Exp> inputs() {
      return inputs;
    }

    @Override
    protected void describeArgs(StringBuilder b) {
      if (!distinct) {
        arg(b, "all");
      }
    }

    public abstract SetRel copy(
        TypeSystem typeSystem, boolean distinct, List<Exp> inputs);
  }

  /** Combines the elements of its inputs. */
  public static class Union extends SetRel {
    Union(Type type, boolean distinct, ImmutableList<Exp> inputs) {
      super(Op.UNION, type, distinct, inputs);
    }

    @Override
    public String opName() {
      return "union";
    }

    @Override
    public Union accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override
    public Union copy(
        TypeSystem typeSystem, boolean distinct, List<Exp> inputs) {
      return distinct == this.distinct && inputs.equals(this.inputs)
          ? this
          : core.union(typeSystem, distinct, inputs);
    }
  }

  /** Keeps the elements that occur in every input. */
  public static class Intersect extends SetRel {
    Intersect(Type type, boolean distinct, ImmutableList<Exp> inputs) {
      super(Op.INTERSECT, type, distinct, inputs);
    }

    @Override
    public String opName() {
      return "intersect";
    }

    @Override
    public Intersect accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override
    public Intersect copy(
        TypeSystem typeSystem, boolean distinct, List<Exp> inputs) {
      return distinct == this.distinct && inputs.equals(this.inputs)
          ? this
          : core.intersect(typeSystem, distinct, inputs);
    }
  }

  /** Keeps the elements of the first input that occur in no other input. */
  public static class Except extends SetRel {
    Except(Type type, boolean distinct, ImmutableList<Exp> inputs) {
      super(Op.EXCEPT, type, distinct, inputs);
    }

    @Override
    public String opName() {
      return "except";
    }

    @Override
    public Except accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override
    public Except copy(
        TypeSystem typeSystem, boolean distinct, List<Exp> inputs) {
      return distinct == this.distinct && inputs.equals(this.inputs)
          ? this
          : core.except(typeSystem, distinct, inputs);
    }
  }

  /** Application of a function to its argument. */
  public static class Apply extends Exp {
    public final Exp fn;
    public final Exp arg;

    Apply(Pos pos, Type type, Exp fn, Exp arg) {
      super(pos, Op.APPLY, type);
      this.fn = fn;
      this.arg = arg;
    }

    /**
     * Returns the argument list (assuming that the arguments are a tuple or
     * record).
     *
     * @throws ClassCastException if argument is not a tuple
     */
    public List<Exp> args() {
      return ((Tuple) arg).args;
    }

    @Override
    public Exp arg(int i) {
      // Throws if the argument is not a tuple.
      return arg.arg(i);
    }

    @Override
    public Exp accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override
    AstWriter unparse(AstWriter w, int left, int right) {
      switch (fn.op) {
        case FN_LITERAL:
          final BuiltIn builtIn = ((Literal) fn).unwrap(BuiltIn.class);

          // Because the Core language is narrower than AST, a few AST
          // expression
          // types do not exist in Core and are translated to function
          // applications. Here we convert them back to original syntax.
          switch (builtIn) {
            case Z_LIST:
              w.append("[");
              arg.forEachArg(
                  (arg, i) -> w.append(i == 0 ? "" : ", ").append(arg, 0, 0));
              return w.append("]");
          }

          // Convert built-ins to infix operators.
          final Op op = Resolver.toOp(builtIn);
          if (op != null) {
            return w.infix(left, args().get(0), op, args().get(1), right);
          }
      }
      return w.infix(left, fn, op, arg, right);
    }

    public Apply copy(Exp fn, Exp arg) {
      return fn == this.fn && arg == this.arg
          ? this
          : core.apply(pos, type, fn, arg);
    }

    /**
     * Creates an Apply with the same function and a list of new arguments. If
     * the arguments are the same, returns this Apply.
     */
    public Apply withArgs(List<Exp> args) {
      if (args.size() == 1) {
        return copy(fn, args.get(0));
      }
      return withTupleArgs(args);
    }

    /**
     * Creates an Apply with the same function and new arguments. If the
     * arguments are the same, returns this Apply.
     */
    public Apply withArgs(Exp arg0, Exp... args) {
      if (args.length == 0) {
        return copy(fn, arg0);
      }
      return withArgs(Lists.asList(arg0, args));
    }

    private Apply withTupleArgs(List<Exp> args) {
      if (args.equals(args())) {
        return this;
      }
      final Tuple newArg = core.tuple((RecordLikeType) this.arg.type, args);
      return core.apply(pos, type, fn, newArg);
    }

    @Override
    public boolean isConstant() {
      // A list of constants is constant
      return isCallTo(BuiltIn.Z_LIST) && allMatch(args(), Exp::isConstant);
    }

    @Override
    public BuiltIn builtIn() {
      if (fn.op == Op.FN_LITERAL) {
        return ((Literal) fn).unwrap(BuiltIn.class);
      }
      return super.builtIn();
    }

    @Override
    public boolean isCallTo(BuiltIn.Constructor constructor) {
      return fn instanceof Id
          && ((Id) fn).idPat.name.equals(constructor.constructor);
    }
  }

  /**
   * Call to an aggregate function in a {@code compute} clause.
   *
   * <p>For example, in {@code compute {sumId = sum over #id e}}, {@code
   * aggregate} is "sum", {@code argument} is "#id e".
   */
  public static class Aggregate extends BaseNode {
    public final Type type;
    public final Exp aggregate;
    public final @Nullable Exp argument;

    Aggregate(Pos pos, Type type, Exp aggregate, @Nullable Exp argument) {
      super(pos, Op.AGGREGATE);
      this.type = type;
      this.aggregate = requireNonNull(aggregate);
      this.argument = argument;
    }

    @Override
    public Aggregate accept(Shuttle shuttle) {
      return shuttle.visit(this);
    }

    @Override
    public void accept(Visitor visitor) {
      visitor.visit(this);
    }

    @Override
    AstWriter unparse(AstWriter w, int left, int right) {
      w.append(aggregate, 0, 0);
      if (argument != null) {
        w.append(" over ").append(argument, 0, 0);
      }
      return w;
    }

    public Aggregate copy(Type type, Exp aggregate, @Nullable Exp argument) {
      return aggregate == this.aggregate && argument == this.argument
          ? this
          : core.aggregate(pos, type, aggregate, argument);
    }
  }

  /**
   * Wraps a value as a Comparable, and stores the global expression from which
   * the value was derived. That global expression will be used if the value is
   * converted by to Morel code.
   */
  static class Wrapper implements Comparable<Wrapper> {
    private final Exp exp;
    private final Object o;

    private Wrapper(Exp exp, Object o) {
      this.exp = exp;
      this.o = o;
      assert isValidValue(exp, o) : o;
    }

    private static boolean isValidValue(Exp exp, Object o) {
      if (o instanceof Code) {
        return false;
      }
      if (o instanceof Closure) {
        return false;
      }
      if (o instanceof Id) {
        final String name = ((Id) exp).idPat.name;
        return !("true".equals(name) || "false".equals(name));
      }
      return true;
    }

    @Override
    public int compareTo(Wrapper o) {
      return Integer.compare(this.o.hashCode(), o.o.hashCode());
    }

    @Override
    public String toString() {
      return o.toString();
    }

    @Override
    public int hashCode() {
      return o.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
      return this == obj
          || obj instanceof Wrapper && this.o.equals(((Wrapper) obj).o);
    }

    /** Returns the value. */
    <T> T unwrap(Class<T> valueClass) {
      return valueClass.cast(o);
    }
  }
}

// End Core.java
