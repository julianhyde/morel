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
package net.hydromatic.morel.type;

import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.util.Pair;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Maps;

import java.util.List;
import java.util.Objects;
import java.util.SortedMap;
import java.util.function.UnaryOperator;

import static net.hydromatic.morel.util.Ord.forEachIndexed;
import static net.hydromatic.morel.util.Static.transform;

import static com.google.common.base.Preconditions.checkArgument;

import static java.util.Objects.requireNonNull;

/** Type keys. */
public class Keys {
  private Keys() {}

  /** Returns a key that identifies types by name. */
  public static Type.Key name(String name) {
    return new NameKey(name);
  }

  /** Returns a key to the dummy type. */
  public static Type.Key dummy() {
    return name("");
  }

  /** Returns a key that identifies types (especially
   * {@link TypeVar type variables}) by ordinal. */
  public static Type.Key ordinal(int ordinal) {
    return new OrdinalKey(ordinal);
  }

  /** Returns a key that applies a polymorphic type to arguments. */
  public static Type.Key apply(Type.Key type,
      Iterable<? extends Type.Key> args) {
    return new ApplyKey(type, ImmutableList.copyOf(args));
  }

  /** Returns a key that identifies a {@link RecordType}
   * (or a {@link TupleType} if the field names are ascending integers,
   * or {@link PrimitiveType#UNIT unit} if the fields are empty). */
  public static Type.Key record(SortedMap<String, ? extends Type.Key> argNameTypes) {
    return new RecordKey(ImmutableSortedMap.copyOfSorted(argNameTypes));
  }

  /** Returns a key that identifies a {@link TupleType}. */
  public static Type.Key tuple(List<? extends Type.Key> args) {
    return new RecordKey(TupleType.recordMap(args));
  }

  /** Returns a key that identifies a {@link FnType}. */
  public static Type.Key fn(Type.Key paramType, Type.Key resultType) {
    return new OpKey(Op.FN, ImmutableList.of(paramType, resultType));
  }

  /** Returns a key that identifies a {@link ListType}. */
  public static Type.Key list(Type.Key elementType) {
    return new OpKey(Op.LIST, ImmutableList.of(elementType));
  }

  /** Returns a key that identifies a {@link ForallType}. */
  public static Type.Key forall(Type type, int parameterCount) {
    return new ForallKey(type, parameterCount);
  }

  /** Returns a key that identifies a {@link DataType}. */
  public static Type.Key datatype(String name, int parameterCount,
      List<? extends Type.Key> arguments,
      SortedMap<String, Type.Key> typeConstructors) {
    return new DataTypeKey(name, parameterCount, arguments, typeConstructors);
  }

  /** Returns a definition of a {@link DataType}. */
  public static DataTypeDef dataTypeDef(String name,
      List<? extends Type.Key> parameterTypes,
      List<? extends Type.Key> argumentTypes,
      SortedMap<String, Type.Key> tyCons, boolean scheme) {
    return new DataTypeDef(name, ImmutableList.copyOf(parameterTypes),
        ImmutableList.copyOf(argumentTypes), ImmutableSortedMap.copyOf(tyCons),
        scheme);
  }

  /** Converts a map of types to a map of keys. */
  public static SortedMap<String, Type.Key> toKeys(
      SortedMap<String, ? extends Type> nameTypes) {
    final ImmutableSortedMap.Builder<String, Type.Key> keys =
        ImmutableSortedMap.orderedBy(RecordType.ORDERING);
    nameTypes.forEach((name, t) -> keys.put(name, t.key()));
    return keys.build();
  }

  /** Converts a list of types to a list of keys. */
  public static List<Type.Key> toKeys(List<? extends Type> types) {
    final ImmutableList.Builder<Type.Key> keys = ImmutableList.builder();
    types.forEach(t -> keys.add(t.key()));
    return keys.build();
  }

  /** Base class for Key. */
  private abstract static class AbstractKey implements Type.Key {
    protected final Op op;

    AbstractKey(Op op) {
      this.op = requireNonNull(op);
    }

    @Override public Op op() {
      return op;
    }

    @Override public String toString() {
      return moniker();
    }

    @Override public Type.Key copy(UnaryOperator<Type.Key> transform) {
      return this;
    }
  }

  /** Key that identifies a type by name. */
  private static class NameKey extends AbstractKey {
    private final String name;

    NameKey(String name) {
      super(name.isEmpty() ? Op.DUMMY_TYPE : Op.DATA_TYPE);
      this.name = requireNonNull(name);
    }

    @Override public String toString() {
      return name;
    }

    @Override public StringBuilder describe(StringBuilder buf, int left,
        int right) {
      return buf.append(name);
    }

    @Override public int hashCode() {
      return name.hashCode();
    }

    @Override public boolean equals(Object obj) {
      return obj == this
          || obj instanceof NameKey
          && ((NameKey) obj).name.equals(name);
    }

    public Type toType(TypeSystem typeSystem) {
      if (name.isEmpty()) {
        return DummyType.INSTANCE;
      }
      return typeSystem.lookup(name);
    }
  }

  /** Key that identifies a type by ordinal. */
  private static class OrdinalKey extends AbstractKey {
    final int ordinal;

    OrdinalKey(int ordinal) {
      super(Op.TY_VAR);
      this.ordinal = ordinal;
    }

    @Override public String toString() {
      return TypeVar.name(ordinal);
    }

    @Override public StringBuilder describe(StringBuilder buf, int left,
        int right) {
      return buf.append(TypeVar.name(ordinal));
    }

    @Override public int hashCode() {
      return ordinal;
    }

    @Override public boolean equals(Object obj) {
      return obj == this
          || obj instanceof OrdinalKey
          && ((OrdinalKey) obj).ordinal == ordinal;
    }

    @Override public Type.Key substitute(List<? extends Type> types) {
      return types.get(ordinal).key();
    }

    public Type toType(TypeSystem typeSystem) {
      return new TypeVar(ordinal);
    }
  }

  /** Key of a type that applies a parameterized type to specific type
   * arguments. */
  private static class ApplyKey extends AbstractKey {
    final Type.Key key;
    final ImmutableList<Type.Key> args;

    ApplyKey(Type.Key key, ImmutableList<Type.Key> args) {
      super(Op.APPLY_TYPE);
      this.key = requireNonNull(key);
      this.args = ImmutableList.copyOf(args);
    }

    @Override public StringBuilder describe(StringBuilder buf, int left,
        int right) {
      if (!args.isEmpty()) {
        TypeSystem.unparseList(buf, Op.COMMA, left, Op.APPLY.left, args);
        buf.append(Op.APPLY.padded);
      }
      return buf.append(key);
    }

    @Override public int hashCode() {
      return Objects.hash(key, args);
    }

    @Override public boolean equals(Object obj) {
      return obj == this
          || obj instanceof ApplyKey
          && ((ApplyKey) obj).key.equals(key)
          && ((ApplyKey) obj).args.equals(args);
    }

    @Override public Type toType(TypeSystem typeSystem) {
      final Type type = key.toType(typeSystem);
      if (type instanceof ForallType) {
        return type.substitute(typeSystem, typeSystem.typesFor(args), null);
      }
      throw new AssertionError();
    }

    @Override public Type.Key copy(UnaryOperator<Type.Key> transform) {
      return new ApplyKey(key.copy(transform),
          transform(args, arg -> arg.copy(transform)));
    }
  }

  /** Key of a type that applies a built-in type constructor to specific type
   * arguments. */
  private static class OpKey extends AbstractKey {
    final ImmutableList<Type.Key> args;

    OpKey(Op op, List<Type.Key> args) {
      super(op);
      this.args = ImmutableList.copyOf(args);
    }

    @Override public StringBuilder describe(StringBuilder buf, int left,
        int right) {
      switch (op) {
      case LIST:
        return TypeSystem.unparse(buf, args.get(0), 0, Op.LIST.right)
            .append(" list");
      default:
        return TypeSystem.unparseList(buf, op, left, right, args);
      }
    }

    @Override public int hashCode() {
      return Objects.hash(op, args);
    }

    @Override public boolean equals(Object obj) {
      return obj == this
          || obj instanceof OpKey
          && ((OpKey) obj).op.equals(op)
          && ((OpKey) obj).args.equals(args);
    }

    public Type toType(TypeSystem typeSystem) {
      switch (op) {
      case FN:
        assert args.size() == 2;
        return new FnType(typeSystem.typeFor(args.get(0)),
            typeSystem.typeFor(args.get(1)));
      case LIST:
        assert args.size() == 1;
        return new ListType(typeSystem.typeFor(args.get(0)));
      default:
        throw new AssertionError(op);
      }
    }

    @Override public Type.Key copy(UnaryOperator<Type.Key> transform) {
      return new OpKey(op, transform(args, arg -> arg.copy(transform)));
    }
  }

  /** Key of a forall type. */
  private static class ForallKey extends AbstractKey {
    final Type type;
    final int parameterCount;

    ForallKey(Type type, int parameterCount) {
      super(Op.FORALL_TYPE);
      this.type = requireNonNull(type);
      this.parameterCount = parameterCount;
      checkArgument(parameterCount >= 0);
    }

    @Override public int hashCode() {
      return Objects.hash(type, parameterCount);
    }

    @Override public boolean equals(Object obj) {
      return obj == this
          || obj instanceof ForallKey
          && ((ForallKey) obj).type.equals(type)
          && ((ForallKey) obj).parameterCount == parameterCount;
    }

    @Override public StringBuilder describe(StringBuilder buf, int left,
        int right) {
      buf.append("forall");
      for (int i = 0; i < parameterCount; i++) {
        buf.append(' ').append(TypeVar.name(i));
      }
      buf.append(". ");
      return TypeSystem.unparse(buf, type.key(), 0, 0);
    }

    public Type toType(TypeSystem typeSystem) {
      return new ForallType(parameterCount, type);
    }
  }

  /** Key of a record type. */
  private static class RecordKey extends AbstractKey {
    final ImmutableSortedMap<String, Type.Key> argNameTypes;

    RecordKey(ImmutableSortedMap<String, Type.Key> argNameTypes) {
      super(TypeSystem.areContiguousIntegers(argNameTypes.keySet())
          ? Op.TUPLE_TYPE
          : Op.RECORD_TYPE);
      this.argNameTypes = requireNonNull(argNameTypes);
      checkArgument(argNameTypes.comparator() == RecordType.ORDERING);
    }

    @Override public Type.Key copy(UnaryOperator<Type.Key> transform) {
      return record(Maps.transformValues(argNameTypes, transform::apply));
    }

    @Override public StringBuilder describe(StringBuilder buf, int left,
        int right) {
      switch (argNameTypes.size()) {
      case 0:
        return buf.append("()");
      default:
        if (op == Op.TUPLE_TYPE) {
          return TypeSystem.unparseList(buf, Op.TIMES, left, right,
              argNameTypes.values());
        }
        // fall through
      case 1:
        buf.append('{');
        Pair.forEachIndexed(argNameTypes, (i, name, type) -> {
          if (i > 0) {
            buf.append(", ");
          }
          buf.append(name).append(':').append(type.moniker());
        });
        return buf.append('}');
      }
    }

    @Override public int hashCode() {
      return argNameTypes.hashCode();
    }

    @Override public boolean equals(Object obj) {
      return obj == this
          || obj instanceof RecordKey
          && ((RecordKey) obj).argNameTypes.equals(argNameTypes);
    }

    public Type toType(TypeSystem typeSystem) {
      switch (argNameTypes.size()) {
      case 0:
        return PrimitiveType.UNIT;
      default:
        if (op == Op.TUPLE_TYPE) {
          return new TupleType(typeSystem.typesFor(argNameTypes.values()));
        }
        // fall through
      case 1:
        return new RecordType(typeSystem.typesFor(argNameTypes));
      }
    }
  }

  /** Key that identifies a {@code datatype} scheme.
   *
   * <p>See also {@link DataTypeDef}, which has enough information to actually
   * create it. */
  private static class DataTypeKey extends AbstractKey {
    /** Ideally, a datatype would not have a name, just a list of named type
     * constructors, and the name would be associated later. When that happens,
     * we can remove the {@code name} field from this key. */
    private final String name;
    private final int parameterCount;
    private final List<? extends Type.Key> arguments;
    private final SortedMap<String, Type.Key> typeConstructors;

    DataTypeKey(String name, int parameterCount,
        List<? extends Type.Key> arguments,
        SortedMap<String, Type.Key> typeConstructors) {
      super(Op.DATA_TYPE);
      this.name = requireNonNull(name);
      this.parameterCount = parameterCount;
      this.arguments = ImmutableList.copyOf(arguments);
      this.typeConstructors = ImmutableSortedMap.copyOfSorted(typeConstructors);
    }

    @Override public int hashCode() {
      return Objects.hash(name, parameterCount, arguments, typeConstructors);
    }

    @Override public boolean equals(Object obj) {
      return obj == this
          || obj instanceof DataTypeKey
          && ((DataTypeKey) obj).name.equals(name)
          && ((DataTypeKey) obj).parameterCount == parameterCount
          && ((DataTypeKey) obj).arguments.equals(arguments)
          && ((DataTypeKey) obj).typeConstructors.equals(typeConstructors);
    }

    /** {@inheritDoc}
     *
     * <p>Prints the name of this datatype along with any type arguments.
     * Examples:
     *
     * <ul>
     *   <li>{@code order}
     *   <li>{@code 'a option}
     *   <li>{@code (int * int) option}
     *   <li>{@code bool option option}
     *   <li>{@code ('a,'b) tree}
     * </ul>
     *
     * @see ParameterizedType#computeMoniker(String, List)
     */
    @Override public StringBuilder describe(StringBuilder buf, int left,
        int right) {
      if (arguments.isEmpty()) {
        return buf.append(name);
      }
      if (arguments.size() > 1) {
        buf.append('(');
      }
      forEachIndexed(arguments, (t, i) -> {
        if (i > 0) {
          buf.append(",");
        }
        if (t.op() == Op.TUPLE_TYPE) {
          buf.append('(').append(t.moniker()).append(')');
        } else {
          buf.append(t.moniker());
        }
      });
      if (arguments.size() > 1) {
        buf.append(')');
      }
      return buf.append(' ').append(name);
    }

    @Override public Type toType(TypeSystem typeSystem) {
      return typeSystem.dataType(name, parameterCount,
          typeSystem.typesFor(arguments), typeConstructors);
    }
  }

  /** Information from which a data type can be created. */
  public static class DataTypeDef implements Type.Def {
    final String name;
    final ImmutableList<Type.Key> parameters;
    final ImmutableList<Type.Key> args;
    final SortedMap<String, Type.Key> tyCons;
    final boolean scheme;

    private DataTypeDef(String name, List<Type.Key> parameters,
        List<Type.Key> args, SortedMap<String, Type.Key> tyCons,
        boolean scheme) {
      this.name = requireNonNull(name);
      this.parameters = ImmutableList.copyOf(parameters);
      this.args = ImmutableList.copyOf(args);
      this.tyCons = ImmutableSortedMap.copyOfSorted(tyCons);
      this.scheme = scheme;
    }

    public StringBuilder describe(StringBuilder buf) {
      final int initialSize = buf.length();
      tyCons.forEach((tyConName, tyConType) -> {
        if (buf.length() > initialSize) {
          buf.append(" | ");
        }
        buf.append(tyConName);
        if (tyConType.op() != Op.DUMMY_TYPE) {
          buf.append(" of ");
          buf.append(tyConType.moniker());
        }
      });
      return buf;
    }

    public DataType toType(TypeSystem typeSystem) {
      return typeSystem.dataType(name, parameters.size(),
          typeSystem.typesFor(args), tyCons);
    }
  }
}

// End Keys.java
