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
package net.hydromatic.morel.eval;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.Maps.transformValues;
import static java.util.Objects.requireNonNull;
import static net.hydromatic.morel.util.Static.transformEager;
import static net.hydromatic.morel.util.Static.transformValuesEager;

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.type.DataType;
import net.hydromatic.morel.type.DummyType;
import net.hydromatic.morel.type.ListType;
import net.hydromatic.morel.type.RecordLikeType;
import net.hydromatic.morel.type.TupleType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeShuttle;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.type.TypeVar;

@SuppressWarnings("rawtypes")
class Comparer extends Applicable2<List, Object, Object>
    implements Codes.Typed {
  private final Comparator<Object> comparator;

  Comparer(Comparator<Object> comparator) {
    super(BuiltIn.RELATIONAL_COMPARE);
    this.comparator = requireNonNull(comparator);
  }

  /** Creates a Comparator for a given value type. */
  public static Comparer create(TypeSystem typeSystem, Type type) {
    ComparatorBuilder b = new ComparatorBuilder(typeSystem);
    Comparator<Object> comparator = b.comparatorFor(type);
    return new Comparer(comparator);
  }

  @Override
  public List apply(Object o1, Object o2) {
    return Codes.order(comparator.compare(o1, o2));
  }

  @SuppressWarnings("unchecked")
  private static int compare(Object o1, Object o2) {
    return ((Comparable) o1).compareTo(o2);
  }

  enum Dummy implements Comparator {
    INSTANCE;

    @Override
    public int compare(Object o1, Object o2) {
      return 0;
    }
  }

  static class ComparatorBuilder {
    private final TypeSystem typeSystem;
    private final Map<Type.Key, Comparator> cache = new HashMap<>();

    ComparatorBuilder(TypeSystem typeSystem) {
      this.typeSystem = requireNonNull(typeSystem);
    }

    Comparator comparatorFor(Type t2) {
      if (t2 == DummyType.INSTANCE) {
        // This is a no-argument type constructor.
        return (list1, list2) -> 0;
      }

      // Check if we have already computed the comparator for this type.
      Type.Key key = t2.key();
      final Comparator comparator = cache.get(key);
      if (comparator == Dummy.INSTANCE) {
        // The comparator is a sentinel, indicating that we are
        // in the process of computing it. We need to defer the
        // lookup of the comparator until it is first used.
        return new DeferredComparator(key);
      }
      if (comparator != null) {
        return comparator;
      }
      return comparatorFor1(t2);
    }

    @SuppressWarnings("unchecked")
    Comparator comparatorFor1(Type type) {
      final Type.Key key = type.key();

      // Put a sentinel in the cache so that we can detect cycles.
      cache.put(key, Dummy.INSTANCE);
      final Comparator comparator2 = comparatorFor2(type);
      cache.put(key, comparator2);
      return comparator2;
    }

    @SuppressWarnings("unchecked")
    Comparator comparatorFor2(Type type) {
      switch (type.op()) {
        case ID:
        case TY_VAR:
          // Primitive types are compared using their natural order.
          return Comparer::compare;

        case TUPLE_TYPE:
        case RECORD_TYPE:
          final List<Comparator> fieldComparators =
              transformEager(
                  ((RecordLikeType) type).argTypes(), this::comparatorFor);
          return (Comparator<List>)
              (List list1, List list2) -> {
                for (int i = 0; i < fieldComparators.size(); i++) {
                  Comparator comparator = fieldComparators.get(i);
                  int c = comparator.compare(list1.get(i), list2.get(i));
                  if (c != 0) {
                    return c;
                  }
                }
                return 0;
              };

        case LIST:
          return listComparator(typeSystem, type.arg(0));

        case DATA_TYPE:
          DataType dataType = (DataType) type;
          switch (dataType.name) {
            case "bag":
              return listComparator(typeSystem, dataType.arg(0));

            case "descending":
              Comparator<Object> objectComparator =
                  comparatorFor(dataType.arg(0));
              return (Comparator<List>)
                  (list1, list2) ->
                      objectComparator.compare(list2.get(1), list1.get(1));

            case "option":
              // The option type is represented as a list of size 1 or 2.
              final Comparator someComparator = comparatorFor(dataType.arg(0));
              return (Comparator<List>)
                  (list1, list2) -> {
                    if (list1.size() == 2 && list2.size() == 2) {
                      // We have (SOME v1, SOME v2). Now compare (v1, v2).
                      return someComparator.compare(list1.get(1), list2.get(1));
                    }
                    // One or both are NONE, and NONE compares like
                    // +infinity.
                    //  (NONE, NONE) => 0
                    //  (SOME _, NONE) => -1
                    //  (NONE, SOME _) => 1
                    return -Integer.compare(list1.size(), list2.size());
                  };
          }
          final Map<String, Comparator> constructorComparators =
              transformValuesEager(
                  dataType.typeConstructors(typeSystem), this::comparatorFor);
          return (Comparator<List>)
              (list1, list2) -> {
                String s1 = (String) list1.get(0);
                String s2 = (String) list2.get(0);
                int c = s1.compareTo(s2);
                if (c != 0) {
                  return c;
                }
                if (list1.size() == 1) {
                  // These are instances of the same no-argument type
                  // constructor.
                  return 0;
                }
                Comparator constructorComparator =
                    requireNonNull(constructorComparators.get(s1));
                return constructorComparator.compare(
                    list1.get(1), list2.get(1));
              };

        default:
          throw new AssertionError("unknown type: " + type);
      }
    }

    private Comparator comparatorFor(Type.Key k, List<Type> arguments) {
      Type t = requireNonNull(k).toType(typeSystem);
      Type t2 = t.substitute(typeSystem, arguments);
      if (t2 == DummyType.INSTANCE) {
        // This is a no-argument type constructor.
        return (list1, list2) -> 0;
      }

      // Check if we have already computed the comparator for this type.
      Type.Key key = t2.key();
      final Comparator comparator = cache.get(key);
      if (comparator == Dummy.INSTANCE) {
        // The comparator is a sentinel, indicating that we are
        // in the process of computing it. We need to defer the
        // lookup of the comparator until it is first used.
        return new DeferredComparator(key);
      }
      if (comparator != null) {
        return comparator;
      }
      return comparatorFor(t2);
    }

    Comparator<List> listComparator(TypeSystem typeSystem, Type elementType) {
      final Comparator<Object> elementComparator = comparatorFor(elementType);
      return (list1, list2) -> {
        final int n1 = list1.size();
        final int n2 = list2.size();
        final int n = Math.min(n1, n2);
        for (int i = 0; i < n; i++) {
          final Object element0 = list1.get(i);
          final Object element1 = list2.get(i);
          final int c = elementComparator.compare(element0, element1);
          if (c != 0) {
            return c;
          }
        }
        return Integer.compare(n1, n2);
      };
    }

    /**
     * Comparator that defers the lookup of the comparator until it is first
     * used. Used for cyclic types, e.g.
     *
     * <pre>{@code
     * datatype 'a list = cons of 'a * 'a list | nil;
     * }</pre>
     */
    private class DeferredComparator implements Comparator {
      final Supplier<Comparator> supplier;

      DeferredComparator(Type.Key key) {
        supplier = Suppliers.memoize(() -> requireNonNull(cache.get(key)));
      }

      @SuppressWarnings("unchecked")
      @Override
      public int compare(Object o1, Object o2) {
        return requireNonNull(supplier.get()).compare(o1, o2);
      }
    }
  }

  @Override
  public Applicable withType(TypeSystem typeSystem, Type argType) {
    checkArgument(argType instanceof TupleType);
    TupleType tupleType = (TupleType) argType;
    checkArgument(tupleType.argTypes.size() == 2);
    Type argType0 = tupleType.argTypes.get(0);
    Type argType1 = tupleType.argTypes.get(1);
    checkArgument(argType0.equals(argType1));
    if (true) {
      return Comparer.create(typeSystem, argType0);
    }
    Type reifiedType =
        argType0.accept(
            new TypeShuttle(typeSystem) {
              @Override
              public Type visit(TypeVar typeVar) {
                return typeVar;
              }

              @Override
              public ListType visit(ListType listType) {
                Type elementType = listType.elementType.accept(this);
                if (elementType.equals(listType.elementType)) {
                  return listType;
                }
                return typeSystem.listType(elementType);
              }

              @Override
              public Type visit(DataType dataType) {
                ImmutableList<Type> arguments =
                    transformEager(dataType.arguments, k -> k.accept(this));
                // Reify the type constructors in the data type.
                SortedMap<String, Type> typeConstructors2 =
                    transformValues(
                        dataType.typeConstructors,
                        k -> requireNonNull(k).toType(typeSystem).accept(this));
                return new ExpandedDataType(
                    dataType.name,
                    dataType.moniker,
                    arguments,
                    dataType.typeConstructors,
                    typeConstructors2);
              }
            });
    return new Comparer(null);
  }

  @SuppressWarnings("unchecked")
  private static int compare(Type type, Object o1, Object o2) {
    switch (type.op()) {
      case ID:
        // Primitive types are compared using their natural order.
        return compare(o1, o2);

      case TUPLE_TYPE:
      case RECORD_TYPE:
        return compareTuples((RecordLikeType) type, (List) o1, (List) o2);

      case LIST:
        return compareLists(type.arg(0), (List) o1, (List) o2);

      case DATA_TYPE:
        final ExpandedDataType dataType = (ExpandedDataType) type;
        final List list1 = (List) o1;
        final List list2 = (List) o2;
        switch (dataType.name) {
          case "option":
            if (list1.size() == 2 && list2.size() == 2) {
              // We have (SOME v1, SOME v2). Now compare (v1, v2).
              return compare(dataType.arg(0), list1.get(1), list2.get(1));
            }
            // One or both are NONE.
            //  (NONE, NONE) => 0
            //  (SOME _, NONE) => -1
            //  (NONE, SOME _) => 1
            return -Integer.compare(list1.size(), list2.size());

          case "descending":
            return -compare(dataType.arg(0), list1.get(1), list2.get(1));

          case "bag":
            return compareLists(dataType.arg(0), list1, list2);

          default:
            return compareDataTypes(dataType, list1, list2);
        }

      default:
        throw new AssertionError("unknown type: " + type);
    }
  }

  private static int compareDataTypes(
      ExpandedDataType dataType, List list1, List list2) {
    String s1 = (String) list1.get(0);
    String s2 = (String) list2.get(0);
    int c = s1.compareTo(s2);
    if (c != 0) {
      return c;
    }
    Type type1 = dataType.typeConstructors2.get(s1);
    if (type1 == DummyType.INSTANCE) {
      // These are instances of the same no-argument type constructor.
      return 0;
    }
    return compare(
        type1, list1.subList(1, list1.size()), list2.subList(1, list2.size()));
  }

  private static int compareLists(Type elementType, List list1, List list2) {
    final int n1 = list1.size();
    final int n2 = list2.size();
    final int n = Math.min(n1, n2);
    for (int i = 0; i < n; i++) {
      final Object element0 = list1.get(i);
      final Object element1 = list2.get(i);
      final int c = compare(elementType, element0, element1);
      if (c != 0) {
        return c;
      }
    }
    return Integer.compare(n1, n2);
  }

  private static int compareTuples(
      RecordLikeType recordType, List list1, List list2) {
    for (int i = 0; i < recordType.argTypes().size(); i++) {
      Type fieldType = recordType.argTypes().get(i);
      int c = compare(fieldType, list1.get(i), list2.get(i));
      if (c != 0) {
        return c;
      }
    }
    return 0;
  }

  /** Extension to {@link DataType} with reified type constructors. */
  static class ExpandedDataType extends DataType {
    private final SortedMap<String, Type> typeConstructors2;

    protected ExpandedDataType(
        String name,
        String moniker,
        List<? extends Type> arguments,
        SortedMap<String, Key> typeConstructors,
        SortedMap<String, Type> typeConstructors2) {
      super(name, moniker, arguments, typeConstructors);
      this.typeConstructors2 = ImmutableSortedMap.copyOf(typeConstructors2);
    }

    @Override
    public boolean equals(Object o) {
      return this == o
          || o instanceof ExpandedDataType
              && name.equals(((ExpandedDataType) o).name)
              && arguments.equals(((ExpandedDataType) o).arguments)
              && typeConstructors.equals(
                  ((ExpandedDataType) o).typeConstructors);
    }

    @Override
    public int hashCode() {
      return Objects.hash(name, arguments);
    }
  }
}

// End Comparer.java
