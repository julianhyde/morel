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
package net.hydromatic.morel.eval.code;

import static net.hydromatic.morel.eval.Codes.OPTION_NONE;
import static net.hydromatic.morel.eval.Codes.optionSome;
import static net.hydromatic.morel.eval.code.ListCodes.all;
import static net.hydromatic.morel.eval.code.ListCodes.collate;
import static net.hydromatic.morel.eval.code.ListCodes.exists;
import static net.hydromatic.morel.eval.code.ListCodes.find;
import static net.hydromatic.morel.eval.code.ListCodes.length;
import static net.hydromatic.morel.eval.code.ListCodes.listMapi;
import static net.hydromatic.morel.eval.code.WordCodes.identity;
import static net.hydromatic.morel.util.Ord.forEachIndexed;

import com.google.common.collect.ImmutableList;
import java.util.List;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.eval.Applicable;
import net.hydromatic.morel.eval.Applicable1;
import net.hydromatic.morel.eval.Applicable2;
import net.hydromatic.morel.eval.Applicable3;
import net.hydromatic.morel.eval.BuiltInExn;
import net.hydromatic.morel.eval.MorelRuntimeException;
import net.hydromatic.morel.eval.Unit;
import net.hydromatic.morel.eval.code.ListCodes.ListNth;
import net.hydromatic.morel.eval.code.ListCodes.ListTabulate;
import net.hydromatic.morel.util.PairList;
import org.apache.calcite.runtime.FlatLists;

/**
 * Implementations of built-in functions and values in the {@code Vector}
 * structure.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public final class VectorCodes {
  private VectorCodes() {}

  /** Registers the implementations in this class. */
  public static void register(PairList<BuiltIn, Object> b) {
    // lint: sort until '#}' where '##b\.add\(BuiltIn' erase 'b\.'
    b.add(BuiltIn.VECTOR_ALL, VECTOR_ALL);
    b.add(BuiltIn.VECTOR_APP, VECTOR_APP);
    b.add(BuiltIn.VECTOR_APPI, VECTOR_APPI);
    b.add(BuiltIn.VECTOR_COLLATE, VECTOR_COLLATE);
    b.add(BuiltIn.VECTOR_CONCAT, VECTOR_CONCAT);
    b.add(BuiltIn.VECTOR_EXISTS, VECTOR_EXISTS);
    b.add(BuiltIn.VECTOR_FIND, VECTOR_FIND);
    b.add(BuiltIn.VECTOR_FINDI, VECTOR_FINDI);
    b.add(BuiltIn.VECTOR_FOLDL, VECTOR_FOLDL);
    b.add(BuiltIn.VECTOR_FOLDLI, VECTOR_FOLDLI);
    b.add(BuiltIn.VECTOR_FOLDR, VECTOR_FOLDR);
    b.add(BuiltIn.VECTOR_FOLDRI, VECTOR_FOLDRI);
    b.add(BuiltIn.VECTOR_FROM_LIST, VECTOR_FROM_LIST);
    b.add(BuiltIn.VECTOR_LENGTH, VECTOR_LENGTH);
    b.add(BuiltIn.VECTOR_MAP, VECTOR_MAP);
    b.add(BuiltIn.VECTOR_MAPI, VECTOR_MAPI);
    b.add(BuiltIn.VECTOR_MAX_LEN, VECTOR_MAX_LEN);
    b.add(BuiltIn.VECTOR_SUB, VECTOR_SUB);
    b.add(BuiltIn.VECTOR_TABULATE, VECTOR_TABULATE);
    b.add(BuiltIn.VECTOR_UPDATE, VECTOR_UPDATE);
  }

  // lint: sort until '#}' \
  //   where '##private static final [^ ]+ [^ ]+ =' \
  //   erase 'private static final [^ ]+ '

  /** @see BuiltIn#VECTOR_ALL */
  private static final Applicable2 VECTOR_ALL = all(BuiltIn.VECTOR_ALL);

  /** @see BuiltIn#VECTOR_APP */
  private static final Applicable2 VECTOR_APP =
      new BaseApplicable2<Unit, Applicable1<Unit, Object>, List>(
          BuiltIn.VECTOR_APP) {
        @Override
        public Unit apply(Applicable1 f, List vec) {
          vec.forEach(f::apply);
          return Unit.INSTANCE;
        }
      };

  /** @see BuiltIn#VECTOR_APPI */
  private static final Applicable2 VECTOR_APPI =
      new BaseApplicable2<Unit, Applicable1<Unit, List>, List>(
          BuiltIn.VECTOR_APPI) {
        @Override
        public Unit apply(Applicable1<Unit, List> f, List vec) {
          forEachIndexed(vec, (e, i) -> f.apply(FlatLists.of(i, e)));
          return Unit.INSTANCE;
        }
      };

  /** @see BuiltIn#VECTOR_COLLATE */
  private static final Applicable2 VECTOR_COLLATE =
      collate(BuiltIn.VECTOR_COLLATE);

  /** @see BuiltIn#VECTOR_CONCAT */
  private static final Applicable VECTOR_CONCAT =
      new BaseApplicable1<List, List<List>>(BuiltIn.VECTOR_CONCAT) {
        @Override
        public List apply(List<List> lists) {
          final ImmutableList.Builder b = ImmutableList.builder();
          for (List<Object> list : lists) {
            b.addAll(list);
          }
          return b.build();
        }
      };

  /** @see BuiltIn#VECTOR_EXISTS */
  private static final Applicable2 VECTOR_EXISTS =
      exists(BuiltIn.VECTOR_EXISTS);

  /** @see BuiltIn#VECTOR_FIND */
  private static final Applicable2 VECTOR_FIND = find(BuiltIn.VECTOR_FIND);

  /** @see BuiltIn#VECTOR_FINDI */
  private static final Applicable2 VECTOR_FINDI =
      new BaseApplicable2<List, Applicable1, List>(BuiltIn.VECTOR_FINDI) {
        @Override
        public List apply(Applicable1 f, List vec) {
          for (int i = 0, n = vec.size(); i < n; i++) {
            final List<Object> tuple = FlatLists.of(i, vec.get(i));
            if ((Boolean) f.apply(tuple)) {
              return optionSome(tuple);
            }
          }
          return OPTION_NONE;
        }
      };

  /** @see BuiltIn#VECTOR_FOLDL */
  private static final Applicable3 VECTOR_FOLDL =
      new BaseApplicable3<Object, Applicable1<Object, List>, Object, List>(
          BuiltIn.VECTOR_FOLDL) {
        @Override
        public Object apply(
            Applicable1<Object, List> f, Object init, List vec) {
          Object acc = init;
          for (Object o : vec) {
            acc = f.apply(FlatLists.of(o, acc));
          }
          return acc;
        }
      };

  /** @see BuiltIn#VECTOR_FOLDLI */
  private static final Applicable3 VECTOR_FOLDLI =
      new BaseApplicable3<Object, Applicable1<Object, List>, Object, List>(
          BuiltIn.VECTOR_FOLDLI) {
        @Override
        public Object apply(
            Applicable1<Object, List> f, Object init, List vec) {
          Object acc = init;
          for (int i = 0, n = vec.size(); i < n; i++) {
            acc = f.apply(FlatLists.of(i, vec.get(i), acc));
          }
          return acc;
        }
      };

  /** @see BuiltIn#VECTOR_FOLDR */
  private static final Applicable3 VECTOR_FOLDR =
      new BaseApplicable3<Object, Applicable1<Object, List>, Object, List>(
          BuiltIn.VECTOR_FOLDR) {
        @Override
        public Object apply(
            Applicable1<Object, List> f, Object init, List vec) {
          Object acc = init;
          for (int i = vec.size() - 1; i >= 0; i--) {
            acc = f.apply(FlatLists.of(vec.get(i), acc));
          }
          return acc;
        }
      };

  /** @see BuiltIn#VECTOR_FOLDRI */
  private static final Applicable3 VECTOR_FOLDRI =
      new BaseApplicable3<Object, Applicable1<Object, List>, Object, List>(
          BuiltIn.VECTOR_FOLDRI) {
        @Override
        public Object apply(
            Applicable1<Object, List> f, Object init, List vec) {
          Object acc = init;
          for (int i = vec.size() - 1; i >= 0; i--) {
            acc = f.apply(FlatLists.of(i, vec.get(i), acc));
          }
          return acc;
        }
      };

  /** @see BuiltIn#VECTOR_FROM_LIST */
  private static final Applicable1 VECTOR_FROM_LIST =
      identity(BuiltIn.VECTOR_FROM_LIST);

  /** @see BuiltIn#VECTOR_LENGTH */
  private static final Applicable1 VECTOR_LENGTH =
      length(BuiltIn.VECTOR_LENGTH);

  /** @see BuiltIn#VECTOR_MAP */
  private static final Applicable2 VECTOR_MAP =
      new BaseApplicable2<List, Applicable1, List>(BuiltIn.VECTOR_MAP) {
        @Override
        public List apply(Applicable1 f, List vec) {
          ImmutableList.Builder b = ImmutableList.builder();
          vec.forEach(e -> b.add(f.apply(e)));
          return b.build();
        }
      };

  /** @see BuiltIn#VECTOR_MAPI */
  private static final Applicable2 VECTOR_MAPI = listMapi(BuiltIn.VECTOR_MAPI);

  /** @see BuiltIn#VECTOR_MAX_LEN */
  private static final int VECTOR_MAX_LEN = (1 << 24) - 1;

  /** @see BuiltIn#VECTOR_SUB */
  private static final Applicable VECTOR_SUB =
      new ListNth(BuiltIn.VECTOR_SUB, Pos.ZERO);

  /** @see BuiltIn#VECTOR_TABULATE */
  private static final Applicable VECTOR_TABULATE =
      new ListTabulate(BuiltIn.VECTOR_TABULATE, Pos.ZERO);

  /** @see BuiltIn#VECTOR_UPDATE */
  private static final Applicable VECTOR_UPDATE = new VectorUpdate(Pos.ZERO);

  /** Implements {@link #VECTOR_UPDATE}. */
  private static class VectorUpdate
      extends BasePositionedApplicable3<List, List, Integer, Object> {
    VectorUpdate(Pos pos) {
      super(BuiltIn.VECTOR_UPDATE, pos);
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new VectorUpdate(pos);
    }

    @Override
    public List apply(List vec, Integer i, Object x) {
      if (i < 0 || i >= vec.size()) {
        throw new MorelRuntimeException(BuiltInExn.SUBSCRIPT, pos);
      }
      final Object[] elements = vec.toArray();
      elements[i] = x;
      return ImmutableList.copyOf(elements);
    }
  }
}

// End VectorCodes.java
