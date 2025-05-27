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
import static java.util.Objects.requireNonNull;
import static net.hydromatic.morel.eval.Codes.order;

import java.util.List;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.type.DataType;
import net.hydromatic.morel.type.RecordLikeType;
import net.hydromatic.morel.type.TupleType;
import net.hydromatic.morel.type.Type;

@SuppressWarnings("rawtypes")
class Comparer extends Applicable2<List, Object, Object>
    implements Codes.Typed {
  private final Type type;

  Comparer(Type type) {
    super(BuiltIn.RELATIONAL_COMPARE);
    this.type = requireNonNull(type);
  }

  @Override
  public Applicable withType(Type argType) {
    checkArgument(argType instanceof TupleType);
    TupleType tupleType = (TupleType) argType;
    checkArgument(tupleType.argTypes.size() == 2);
    Type argType0 = tupleType.argTypes.get(0);
    Type argType1 = tupleType.argTypes.get(1);
    checkArgument(argType0.equals(argType1));
    return new Comparer(argType0);
  }

  @Override
  public List apply(Object o1, Object o2) {
    return order(compare(type, o1, o2));
  }

  @SuppressWarnings("unchecked")
  private static int compare(Type type, Object o1, Object o2) {
    switch (type.op()) {
      case ID:
        // Primitive types are compared using their natural order.
        return ((Comparable) o1).compareTo(o2);

      case TUPLE_TYPE:
      case RECORD_TYPE:
        return compareTuples((RecordLikeType) type, (List) o1, (List) o2);

      case LIST:
        return compareLists(type.arg(0), (List) o1, (List) o2);

      case DATA_TYPE:
        final DataType dataType = (DataType) type;
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
      DataType dataType, List list1, List list2) {
    String s1 = (String) list1.get(0);
    String s2 = (String) list2.get(0);
    int c = s1.compareTo(s2);
    if (c != 0) {
      return c;
    }
    Type.Key key = dataType.typeConstructors.get(s1);
    Type type1 = key.toType(null);
    return 0;
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
}

// End Comparer.java
