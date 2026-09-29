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
package net.hydromatic.morel.eval.codes;

import static net.hydromatic.morel.eval.Codes.OPTION_NONE;
import static net.hydromatic.morel.eval.Codes.optionSome;
import static net.hydromatic.morel.eval.codes.DateCodes.order;
import static net.hydromatic.morel.eval.codes.WordCodes.identity;
import static net.hydromatic.morel.util.Ord.forEachIndexed;
import static net.hydromatic.morel.util.Pair.forEach;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.eval.Applicable;
import net.hydromatic.morel.eval.Applicable1;
import net.hydromatic.morel.eval.Applicable2;
import net.hydromatic.morel.eval.Applicable3;
import net.hydromatic.morel.eval.BuiltInExn;
import net.hydromatic.morel.eval.MorelRuntimeException;
import net.hydromatic.morel.eval.Unit;
import net.hydromatic.morel.util.PairList;
import org.apache.calcite.runtime.FlatLists;
import org.jspecify.annotations.Nullable;

/**
 * Implementations of built-in functions and values in the Bag, List and
 * ListPair structures.
 */
public final class ListCodes {
  private ListCodes() {}

  /** Registers the implementations in this class. */
  public static void register(PairList<BuiltIn, Object> b) {
    // lint: sort until '#}' where '##b\.add\(BuiltIn' erase 'b\.'
    b.add(BuiltIn.BAG_ALL, BAG_ALL);
    b.add(BuiltIn.BAG_APP, BAG_APP);
    b.add(BuiltIn.BAG_AT, BAG_AT);
    b.add(BuiltIn.BAG_CONCAT, BAG_CONCAT);
    b.add(BuiltIn.BAG_DROP, BAG_DROP);
    b.add(BuiltIn.BAG_EXISTS, BAG_EXISTS);
    b.add(BuiltIn.BAG_FILTER, BAG_FILTER);
    b.add(BuiltIn.BAG_FIND, BAG_FIND);
    b.add(BuiltIn.BAG_FOLD, BAG_FOLD);
    b.add(BuiltIn.BAG_FROM_LIST, BAG_FROM_LIST);
    b.add(BuiltIn.BAG_GET_ITEM, BAG_GET_ITEM);
    b.add(BuiltIn.BAG_HD, BAG_HD);
    b.add(BuiltIn.BAG_LENGTH, BAG_LENGTH);
    b.add(BuiltIn.BAG_MAP, BAG_MAP);
    b.add(BuiltIn.BAG_MAP_PARTIAL, BAG_MAP_PARTIAL);
    b.add(BuiltIn.BAG_NTH, BAG_NTH);
    b.add(BuiltIn.BAG_NULL, BAG_NULL);
    b.add(BuiltIn.BAG_ONLY, BAG_ONLY);
    b.add(BuiltIn.BAG_PARTITION, BAG_PARTITION);
    b.add(BuiltIn.BAG_TABULATE, BAG_TABULATE);
    b.add(BuiltIn.BAG_TAKE, BAG_TAKE);
    b.add(BuiltIn.BAG_TL, BAG_TL);
    b.add(BuiltIn.BAG_TO_LIST, BAG_TO_LIST);
    b.add(BuiltIn.LIST_ALL, LIST_ALL);
    b.add(BuiltIn.LIST_APP, LIST_APP);
    b.add(BuiltIn.LIST_AT, LIST_AT);
    b.add(BuiltIn.LIST_COLLATE, LIST_COLLATE);
    b.add(BuiltIn.LIST_CONCAT, LIST_CONCAT);
    b.add(BuiltIn.LIST_DROP, LIST_DROP);
    b.add(BuiltIn.LIST_EXCEPT, LIST_EXCEPT);
    b.add(BuiltIn.LIST_EXISTS, LIST_EXISTS);
    b.add(BuiltIn.LIST_FILTER, LIST_FILTER);
    b.add(BuiltIn.LIST_FIND, LIST_FIND);
    b.add(BuiltIn.LIST_FOLDL, LIST_FOLDL);
    b.add(BuiltIn.LIST_FOLDR, LIST_FOLDR);
    b.add(BuiltIn.LIST_GET_ITEM, LIST_GET_ITEM);
    b.add(BuiltIn.LIST_HD, LIST_HD);
    b.add(BuiltIn.LIST_INTERSECT, LIST_INTERSECT);
    b.add(BuiltIn.LIST_LAST, LIST_LAST);
    b.add(BuiltIn.LIST_LENGTH, LIST_LENGTH);
    b.add(BuiltIn.LIST_MAP, LIST_MAP);
    b.add(BuiltIn.LIST_MAP_PARTIAL, LIST_MAP_PARTIAL);
    b.add(BuiltIn.LIST_MAPI, LIST_MAPI);
    b.add(BuiltIn.LIST_NTH, LIST_NTH);
    b.add(BuiltIn.LIST_NULL, LIST_NULL);
    b.add(BuiltIn.LIST_ONLY, LIST_ONLY);
    b.add(BuiltIn.LIST_PAIR_ALL, LIST_PAIR_ALL);
    b.add(BuiltIn.LIST_PAIR_ALL_EQ, LIST_PAIR_ALL_EQ);
    b.add(BuiltIn.LIST_PAIR_APP, LIST_PAIR_APP);
    b.add(BuiltIn.LIST_PAIR_APP_EQ, LIST_PAIR_APP_EQ);
    b.add(BuiltIn.LIST_PAIR_EXISTS, LIST_PAIR_EXISTS);
    b.add(BuiltIn.LIST_PAIR_FOLDL, LIST_PAIR_FOLDL);
    b.add(BuiltIn.LIST_PAIR_FOLDL_EQ, LIST_PAIR_FOLDL_EQ);
    b.add(BuiltIn.LIST_PAIR_FOLDR, LIST_PAIR_FOLDR);
    b.add(BuiltIn.LIST_PAIR_FOLDR_EQ, LIST_PAIR_FOLDR_EQ);
    b.add(BuiltIn.LIST_PAIR_MAP, LIST_PAIR_MAP);
    b.add(BuiltIn.LIST_PAIR_MAP_EQ, LIST_PAIR_MAP_EQ);
    b.add(BuiltIn.LIST_PAIR_UNZIP, LIST_PAIR_UNZIP);
    b.add(BuiltIn.LIST_PAIR_ZIP, LIST_PAIR_ZIP);
    b.add(BuiltIn.LIST_PAIR_ZIP_EQ, LIST_PAIR_ZIP_EQ);
    b.add(BuiltIn.LIST_PARTITION, LIST_PARTITION);
    b.add(BuiltIn.LIST_REV, LIST_REV);
    b.add(BuiltIn.LIST_REV_APPEND, LIST_REV_APPEND);
    b.add(BuiltIn.LIST_TABULATE, LIST_TABULATE);
    b.add(BuiltIn.LIST_TAKE, LIST_TAKE);
    b.add(BuiltIn.LIST_TL, LIST_TL);
  }

  // lint: sort until '#}' \
  //   where '##private static final [^ ]+ [^ ]+ =' \
  //   erase 'private static final [^ ]+ '

  /** @see BuiltIn#BAG_ALL */
  private static final Applicable2 BAG_ALL = all(BuiltIn.BAG_ALL);

  /** @see BuiltIn#BAG_APP */
  private static final Applicable2 BAG_APP = listApp(BuiltIn.BAG_APP);

  /** @see BuiltIn#BAG_AT */
  private static final Applicable2 BAG_AT = union(BuiltIn.BAG_AT);

  /** @see BuiltIn#BAG_CONCAT */
  private static final Applicable BAG_CONCAT = listConcat(BuiltIn.BAG_CONCAT);

  /** @see BuiltIn#BAG_DROP */
  private static final Applicable2 BAG_DROP = listDrop(BuiltIn.BAG_DROP);

  /** @see BuiltIn#BAG_EXISTS */
  private static final Applicable2 BAG_EXISTS = exists(BuiltIn.BAG_EXISTS);

  /** @see BuiltIn#BAG_FILTER */
  private static final Applicable2 BAG_FILTER = listFilter(BuiltIn.BAG_FILTER);

  /** @see BuiltIn#BAG_FIND */
  private static final Applicable2 BAG_FIND = find(BuiltIn.BAG_FIND);

  /** @see BuiltIn#BAG_FOLD */
  private static final Applicable3 BAG_FOLD =
      // Order does not matter, but we call with left = true because foldl is
      // more efficient than foldr.
      listFold0(BuiltIn.BAG_FOLD, true);

  /** @see BuiltIn#BAG_FROM_LIST */
  private static final Applicable1 BAG_FROM_LIST =
      identity(BuiltIn.BAG_FROM_LIST);

  /** @see BuiltIn#BAG_GET_ITEM */
  private static final Applicable BAG_GET_ITEM =
      listGetItem(BuiltIn.BAG_GET_ITEM);

  /** @see BuiltIn#BAG_HD */
  private static final Applicable BAG_HD =
      new ListHd(BuiltIn.LIST_HD, Pos.ZERO);

  /** @see BuiltIn#BAG_LENGTH */
  private static final Applicable1 BAG_LENGTH = length(BuiltIn.BAG_LENGTH);

  /** @see BuiltIn#BAG_MAP */
  private static final Applicable2 BAG_MAP = listMap(BuiltIn.BAG_MAP);

  /** @see BuiltIn#BAG_MAP_PARTIAL */
  private static final Applicable2 BAG_MAP_PARTIAL =
      listMapPartial(BuiltIn.BAG_MAP_PARTIAL);

  /** @see BuiltIn#BAG_NTH */
  private static final Applicable BAG_NTH =
      new ListNth(BuiltIn.BAG_NTH, Pos.ZERO);

  /** @see BuiltIn#BAG_NULL */
  private static final Applicable1 BAG_NULL = empty(BuiltIn.BAG_NULL);

  /** @see BuiltIn#BAG_ONLY */
  private static final Applicable BAG_ONLY =
      new RelationalOnly(BuiltIn.BAG_ONLY, Pos.ZERO);

  /** @see BuiltIn#BAG_PARTITION */
  private static final Applicable2 BAG_PARTITION =
      listPartition0(BuiltIn.BAG_PARTITION);

  /** @see BuiltIn#BAG_TABULATE */
  private static final Applicable BAG_TABULATE =
      new ListTabulate(BuiltIn.BAG_TABULATE, Pos.ZERO);

  /** @see BuiltIn#BAG_TAKE */
  private static final Applicable BAG_TAKE =
      new ListTake(BuiltIn.BAG_TAKE, Pos.ZERO);

  /** @see BuiltIn#BAG_TL */
  private static final Applicable BAG_TL =
      new ListTl(BuiltIn.LIST_TL, Pos.ZERO);

  /** @see BuiltIn#BAG_TO_LIST */
  private static final Applicable1 BAG_TO_LIST = identity(BuiltIn.BAG_TO_LIST);

  /** @see BuiltIn#LIST_ALL */
  private static final Applicable2 LIST_ALL = all(BuiltIn.LIST_ALL);

  static Applicable2 all(final BuiltIn builtIn) {
    return new BaseApplicable2<Boolean, Applicable1, List>(builtIn) {
      @Override
      public Boolean apply(Applicable1 f, List list) {
        for (Object o : list) {
          if (!(Boolean) f.apply(o)) {
            return false;
          }
        }
        return true;
      }
    };
  }

  /** @see BuiltIn#LIST_APP */
  private static final Applicable2 LIST_APP = listApp(BuiltIn.LIST_APP);

  private static Applicable2 listApp(BuiltIn builtIn) {
    return new BaseApplicable2<Unit, Applicable1, List>(builtIn) {
      @Override
      public Unit apply(Applicable1 consumer, List list) {
        list.forEach(consumer::apply);
        return Unit.INSTANCE;
      }
    };
  }

  /** @see BuiltIn#LIST_AT */
  private static final Applicable2 LIST_AT = union(BuiltIn.LIST_AT);

  private static Applicable2 union(final BuiltIn builtIn) {
    return new BaseApplicable2<List, List, List>(builtIn) {
      @Override
      public List apply(List list0, List list1) {
        return ImmutableList.builder().addAll(list0).addAll(list1).build();
      }
    };
  }

  /** @see BuiltIn#LIST_COLLATE */
  private static final Applicable2 LIST_COLLATE = collate(BuiltIn.LIST_COLLATE);

  static Applicable2 collate(final BuiltIn builtIn) {
    return new BaseApplicable2<Object, Applicable1<List, List>, List>(builtIn) {
      @Override
      public Object apply(Applicable1<List, List> comparator, List tuple) {
        final List list0 = (List) tuple.get(0);
        final List list1 = (List) tuple.get(1);
        final int n0 = list0.size();
        final int n1 = list1.size();
        final int n = Math.min(n0, n1);
        for (int i = 0; i < n; i++) {
          final Object element0 = list0.get(i);
          final Object element1 = list1.get(i);
          final List compare =
              comparator.apply(FlatLists.of(element0, element1));
          if (!compare.get(0).equals("EQUAL")) {
            return compare;
          }
        }
        return order(Integer.compare(n0, n1));
      }
    };
  }

  /** @see BuiltIn#LIST_CONCAT */
  private static final Applicable LIST_CONCAT = listConcat(BuiltIn.LIST_CONCAT);

  private static Applicable listConcat(BuiltIn builtIn) {
    return new BaseApplicable1<List, List<List>>(builtIn) {
      @Override
      public List apply(List<List> lists) {
        final ImmutableList.Builder builder = ImmutableList.builder();
        for (List list : lists) {
          builder.addAll(list);
        }
        return builder.build();
      }
    };
  }

  /** @see BuiltIn#LIST_DROP */
  private static final Applicable2 LIST_DROP = listDrop(BuiltIn.LIST_DROP);

  private static Applicable2<List, List, Integer> listDrop(BuiltIn builtIn) {
    return new BaseApplicable2<List, List, Integer>(builtIn) {
      @Override
      public List apply(List list, Integer i) {
        return list.subList(i, list.size());
      }
    };
  }

  /** @see BuiltIn#LIST_EXCEPT */
  private static final Applicable LIST_EXCEPT = new ListExcept(Pos.ZERO);

  /** Implements {@link #LIST_EXCEPT}. */
  private static class ListExcept
      extends BasePositionedApplicable1<List, List<List>> {
    ListExcept(Pos pos) {
      super(BuiltIn.LIST_EXCEPT, pos);
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new ListExcept(pos);
    }

    @Override
    public List apply(List<List> lists) {
      if (lists.isEmpty()) {
        throw new MorelRuntimeException(BuiltInExn.EMPTY, pos);
      }
      // Build a multiset of elements to exclude from the right-hand lists.
      final Map<Object, Integer> toRemove = new HashMap<>();
      for (int i = 1; i < lists.size(); i++) {
        for (Object element : lists.get(i)) {
          toRemove.merge(element, 1, Integer::sum);
        }
      }
      // Iterate through the left-hand list, skipping the first N occurrences
      // of each element (where N is the count in toRemove).
      final List result = new ArrayList();
      for (Object element : lists.get(0)) {
        Integer count = toRemove.get(element);
        if (count != null && count > 0) {
          // Element is in the exclusion multiset; decrement and skip.
          toRemove.put(element, count - 1);
        } else {
          // Element is not in the exclusion multiset (or count exhausted);
          // include it.
          result.add(element);
        }
      }
      return ImmutableList.copyOf(result);
    }
  }

  /** @see BuiltIn#LIST_EXISTS */
  private static final Applicable2 LIST_EXISTS = exists(BuiltIn.LIST_EXISTS);

  static Applicable2 exists(final BuiltIn builtIn) {
    return new BaseApplicable2<Boolean, Applicable1, List>(builtIn) {
      @Override
      public Boolean apply(Applicable1 f, List list) {
        for (Object o : list) {
          if ((Boolean) f.apply(o)) {
            return true;
          }
        }
        return false;
      }
    };
  }

  /** @see BuiltIn#LIST_FILTER */
  private static final Applicable2 LIST_FILTER =
      listFilter(BuiltIn.LIST_FILTER);

  private static Applicable2 listFilter(BuiltIn builtIn) {
    return new BaseApplicable2<List, Applicable1<Boolean, Object>, List>(
        builtIn) {
      @Override
      public List apply(Applicable1<Boolean, Object> f, List list) {
        final ImmutableList.Builder builder = ImmutableList.builder();
        for (Object o : list) {
          if (f.apply(o)) {
            builder.add(o);
          }
        }
        return builder.build();
      }
    };
  }

  /** @see BuiltIn#LIST_FIND */
  private static final Applicable2 LIST_FIND = find(BuiltIn.LIST_FIND);

  static Applicable2 find(BuiltIn builtIn) {
    return new BaseApplicable2<List, Applicable1<Boolean, Object>, List>(
        builtIn) {
      @Override
      public List apply(Applicable1<Boolean, Object> f, List list) {
        for (Object o : list) {
          if (f.apply(o)) {
            return optionSome(o);
          }
        }
        return OPTION_NONE;
      }
    };
  }

  /** @see BuiltIn#LIST_FOLDL */
  private static final Applicable3 LIST_FOLDL =
      listFold0(BuiltIn.LIST_FOLDL, true);

  /** @see BuiltIn#LIST_FOLDR */
  private static final Applicable3 LIST_FOLDR =
      listFold0(BuiltIn.LIST_FOLDR, false);

  private static Applicable3 listFold0(BuiltIn builtIn, boolean left) {
    return new BaseApplicable3<Object, Applicable1<Object, List>, Object, List>(
        builtIn) {
      @Override
      public Object apply(Applicable1<Object, List> f, Object init, List list) {
        Object b = init;
        for (Object a : left ? list : Lists.reverse(list)) {
          b = f.apply(FlatLists.of(a, b));
        }
        return b;
      }
    };
  }

  /** @see BuiltIn#LIST_GET_ITEM */
  private static final Applicable LIST_GET_ITEM =
      listGetItem(BuiltIn.LIST_GET_ITEM);

  private static Applicable listGetItem(BuiltIn builtIn) {
    return new BaseApplicable1<List, List>(builtIn) {
      @Override
      public List apply(List list) {
        if (list.isEmpty()) {
          return OPTION_NONE;
        } else {
          return optionSome(
              ImmutableList.of(list.get(0), list.subList(1, list.size())));
        }
      }
    };
  }

  /** @see BuiltIn#LIST_HD */
  private static final Applicable LIST_HD =
      new ListHd(BuiltIn.LIST_HD, Pos.ZERO);

  /** Implements {@link #LIST_HD}. */
  private static class ListHd extends BasePositionedApplicable1<Object, List> {
    ListHd(BuiltIn builtIn, Pos pos) {
      super(builtIn, pos);
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new ListHd(builtIn, pos);
    }

    @Override
    public Object apply(List list) {
      if (list.isEmpty()) {
        throw new MorelRuntimeException(BuiltInExn.EMPTY, pos);
      }
      return list.get(0);
    }
  }

  /** @see BuiltIn#LIST_INTERSECT */
  private static final Applicable LIST_INTERSECT = new ListIntersect(Pos.ZERO);

  /** Implements {@link #LIST_INTERSECT}. */
  private static class ListIntersect
      extends BasePositionedApplicable1<List, List<List>> {
    ListIntersect(Pos pos) {
      super(BuiltIn.LIST_INTERSECT, pos);
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new ListIntersect(pos);
    }

    @Override
    public List apply(List<List> lists) {
      if (lists.isEmpty()) {
        throw new MorelRuntimeException(BuiltInExn.EMPTY, pos);
      }
      if (lists.size() == 1) {
        return lists.get(0);
      }

      // For each of the right inputs, count the occurrences of each element.
      final Map<Object, int[]> map = new HashMap<>();
      forEachIndexed(
          lists,
          (list, i) -> {
            if (i > 0) {
              final Function<Object, int[]> create =
                  k -> new int[lists.size() - 1];
              final int j = i - 1;
              for (Object o : list) {
                int @Nullable [] ints = map.computeIfAbsent(o, create);
                ints[j]++;
              }
            }
          });

      // If there is more than one right input, put the minimum of the counts
      // into slot 0. Remove the element from the map if the count from any
      // input is 0.
      if (lists.size() > 2) {
        Iterator<Map.Entry<Object, int[]>> iterator = map.entrySet().iterator();
        next_element:
        while (iterator.hasNext()) {
          Map.Entry<Object, int[]> entry = iterator.next();
          int[] counts = entry.getValue();
          int m = counts[0];
          if (m == 0) {
            iterator.remove();
            continue;
          }
          for (int i = 1; i < counts.length; i++) {
            if (counts[i] < counts[0]) {
              if (counts[i] == 0) {
                iterator.remove();
                continue next_element;
              }
              counts[0] = counts[i];
            }
          }
        }
      }

      // Now read input 0. If an element is in the map, decrement its count.
      // If the count reaches 0, remove it from the map.
      List<Object> result = new ArrayList(lists.get(0).size());
      for (Object o : lists.get(0)) {
        int @Nullable [] counts = map.get(o);
        if (counts != null) {
          counts[0]--;
          if (counts[0] == 0) {
            map.remove(o);
          }
          result.add(o);
        }
      }
      return ImmutableList.copyOf(result);
    }
  }

  /** @see BuiltIn#LIST_LAST */
  private static final Applicable LIST_LAST =
      new ListLast(BuiltIn.LIST_LAST, Pos.ZERO);

  /** Implements {@link #LIST_LAST}. */
  private static class ListLast
      extends BasePositionedApplicable1<Object, List> {
    ListLast(BuiltIn builtIn, Pos pos) {
      super(builtIn, pos);
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new ListLast(builtIn, pos);
    }

    @Override
    public Object apply(List list) {
      final int size = list.size();
      if (size == 0) {
        throw new MorelRuntimeException(BuiltInExn.EMPTY, pos);
      }
      return list.get(size - 1);
    }
  }

  /** @see BuiltIn#LIST_LENGTH */
  private static final Applicable1 LIST_LENGTH = length(BuiltIn.LIST_LENGTH);

  static Applicable1 length(BuiltIn builtIn) {
    return new BaseApplicable1<Integer, List>(builtIn) {
      @Override
      public Integer apply(List list) {
        return list.size();
      }
    };
  }

  /** @see BuiltIn#LIST_MAP */
  private static final Applicable2 LIST_MAP = listMap(BuiltIn.LIST_MAP);

  private static Applicable2 listMap(BuiltIn builtIn) {
    return new BaseApplicable2<List, Applicable1, List>(builtIn) {
      @Override
      public List apply(Applicable1 f, List list) {
        final ImmutableList.Builder builder = ImmutableList.builder();
        for (Object o : list) {
          builder.add(f.apply(o));
        }
        return builder.build();
      }
    };
  }

  /** @see BuiltIn#LIST_MAP_PARTIAL */
  private static final Applicable2 LIST_MAP_PARTIAL =
      listMapPartial(BuiltIn.LIST_MAP_PARTIAL);

  private static Applicable2 listMapPartial(BuiltIn builtIn) {
    return new BaseApplicable2<List, Applicable1<List, Object>, List>(builtIn) {
      @Override
      public List apply(Applicable1<List, Object> f, List list) {
        final ImmutableList.Builder builder = ImmutableList.builder();
        for (Object o : list) {
          final List opt = f.apply(o);
          if (opt.size() == 2) {
            builder.add(opt.get(1));
          }
        }
        return builder.build();
      }
    };
  }

  /** @see BuiltIn#LIST_MAPI */
  private static final Applicable2 LIST_MAPI = listMapi(BuiltIn.LIST_MAPI);

  /** Implements {@link #LIST_MAPI}, {@link VectorCodes#VECTOR_MAPI}. */
  static Applicable2<List, Applicable1, List> listMapi(BuiltIn builtIn) {
    return new BaseApplicable2<List, Applicable1, List>(builtIn) {
      @Override
      public List apply(Applicable1 f, List vec) {
        ImmutableList.Builder b = ImmutableList.builder();
        forEachIndexed(vec, (e, i) -> b.add(f.apply(FlatLists.of(i, e))));
        return b.build();
      }
    };
  }

  /** @see BuiltIn#LIST_NTH */
  private static final Applicable LIST_NTH =
      new ListNth(BuiltIn.LIST_NTH, Pos.ZERO);

  /** Implements {@link #LIST_NTH} and {@link VectorCodes#VECTOR_SUB}. */
  static class ListNth
      extends BasePositionedApplicable2<Object, List, Integer> {
    ListNth(BuiltIn builtIn, Pos pos) {
      super(builtIn, pos);
    }

    @Override
    public ListNth withPos(Pos pos) {
      return new ListNth(builtIn, pos);
    }

    @Override
    public Object apply(List list, Integer i) {
      if (i < 0 || i >= list.size()) {
        throw new MorelRuntimeException(BuiltInExn.SUBSCRIPT, pos);
      }
      return list.get(i);
    }
  }

  /** @see BuiltIn#LIST_NULL */
  private static final Applicable1 LIST_NULL = empty(BuiltIn.LIST_NULL);

  /** @see BuiltIn#LIST_ONLY */
  private static final Applicable LIST_ONLY =
      new RelationalOnly(BuiltIn.LIST_ONLY, Pos.ZERO);

  /** @see BuiltIn#LIST_PAIR_ALL */
  private static final Applicable2 LIST_PAIR_ALL =
      listPairAll(BuiltIn.LIST_PAIR_ALL, false);

  /** @see BuiltIn#LIST_PAIR_ALL_EQ */
  private static final Applicable2 LIST_PAIR_ALL_EQ =
      listPairAll(BuiltIn.LIST_PAIR_ALL_EQ, true);

  static Applicable2 listPairAll(BuiltIn builtIn, boolean eq) {
    return new BaseApplicable2<Boolean, Applicable1, List<List<Object>>>(
        builtIn) {
      @Override
      public Boolean apply(Applicable1 f, List<List<Object>> listPair) {
        final List<Object> list0 = listPair.get(0);
        final List<Object> list1 = listPair.get(1);
        if (eq && list0.size() != list1.size()) {
          return false;
        }
        final Iterator<Object> iter0 = list0.iterator();
        final Iterator<Object> iter1 = list1.iterator();
        while (iter0.hasNext() && iter1.hasNext()) {
          if (!(Boolean) f.apply(FlatLists.of(iter0.next(), iter1.next()))) {
            return false;
          }
        }
        return true;
      }
    };
  }

  /** Helper for {@link #LIST_PAIR_APP} and {@link #LIST_PAIR_APP_EQ}. */
  private static class ListPairApp
      extends BasePositionedApplicable2<Unit, Applicable1, List<List<Object>>> {
    ListPairApp(BuiltIn builtIn, Pos pos) {
      super(builtIn, pos);
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new ListPairApp(builtIn, pos);
    }

    @Override
    public Unit apply(Applicable1 f, List<List<Object>> listPair) {
      final List<Object> list0 = listPair.get(0);
      final List<Object> list1 = listPair.get(1);
      if (builtIn == BuiltIn.LIST_PAIR_APP_EQ && list0.size() != list1.size()) {
        throw new MorelRuntimeException(BuiltInExn.UNEQUAL_LENGTHS, pos);
      }
      final Iterator<Object> iter0 = list0.iterator();
      final Iterator<Object> iter1 = list1.iterator();
      while (iter0.hasNext() && iter1.hasNext()) {
        f.apply(FlatLists.of(iter0.next(), iter1.next()));
      }
      return Unit.INSTANCE;
    }
  }

  /** @see BuiltIn#LIST_PAIR_APP */
  private static final Applicable LIST_PAIR_APP =
      new ListPairApp(BuiltIn.LIST_PAIR_APP, Pos.ZERO);

  /** @see BuiltIn#LIST_PAIR_APP_EQ */
  private static final Applicable LIST_PAIR_APP_EQ =
      new ListPairApp(BuiltIn.LIST_PAIR_APP_EQ, Pos.ZERO);

  /** @see BuiltIn#LIST_PAIR_EXISTS */
  private static final Applicable2 LIST_PAIR_EXISTS =
      new BaseApplicable2<Boolean, Applicable1, List<List<Object>>>(
          BuiltIn.LIST_PAIR_EXISTS) {
        @Override
        public Boolean apply(Applicable1 f, List<List<Object>> listPair) {
          final List<Object> list0 = listPair.get(0);
          final List<Object> list1 = listPair.get(1);
          final Iterator<Object> iter0 = list0.iterator();
          final Iterator<Object> iter1 = list1.iterator();
          while (iter0.hasNext() && iter1.hasNext()) {
            if ((Boolean) f.apply(FlatLists.of(iter0.next(), iter1.next()))) {
              return true;
            }
          }
          return false;
        }
      };

  /**
   * Helper for {@link #LIST_PAIR_FOLDL}, {@link #LIST_PAIR_FOLDL_EQ}, {@link
   * #LIST_PAIR_FOLDR}, {@link #LIST_PAIR_FOLDR_EQ}.
   */
  private static class ListPairFold
      extends BasePositionedApplicable3<
          Object, Applicable1, Object, List<List<Object>>> {
    private final boolean eq;
    private final boolean left;

    ListPairFold(BuiltIn builtIn, Pos pos, boolean eq, boolean left) {
      super(builtIn, pos);
      this.eq = eq;
      this.left = left;
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new ListPairFold(builtIn, pos, this.eq, this.left);
    }

    @Override
    public Object apply(Applicable1 f, Object init, List<List<Object>> pair) {
      final List<Object> list0 = pair.get(0);
      final List<Object> list1 = pair.get(1);
      if (eq && list0.size() != list1.size()) {
        throw new MorelRuntimeException(BuiltInExn.UNEQUAL_LENGTHS, pos);
      }
      final int n = Math.min(list0.size(), list1.size());
      Object b = init;
      if (left) {
        for (int i = 0; i < n; i++) {
          b = f.apply(FlatLists.of(list0.get(i), list1.get(i), b));
        }
      } else {
        for (int i = n - 1; i >= 0; i--) {
          b = f.apply(FlatLists.of(list0.get(i), list1.get(i), b));
        }
      }
      return b;
    }
  }

  /** @see BuiltIn#LIST_PAIR_FOLDL */
  private static final Applicable LIST_PAIR_FOLDL =
      new ListPairFold(BuiltIn.LIST_PAIR_FOLDL, Pos.ZERO, false, true);

  /** @see BuiltIn#LIST_PAIR_FOLDL_EQ */
  private static final Applicable LIST_PAIR_FOLDL_EQ =
      new ListPairFold(BuiltIn.LIST_PAIR_FOLDL_EQ, Pos.ZERO, true, true);

  /** @see BuiltIn#LIST_PAIR_FOLDR */
  private static final Applicable LIST_PAIR_FOLDR =
      new ListPairFold(BuiltIn.LIST_PAIR_FOLDR, Pos.ZERO, false, false);

  /** @see BuiltIn#LIST_PAIR_FOLDR_EQ */
  private static final Applicable LIST_PAIR_FOLDR_EQ =
      new ListPairFold(BuiltIn.LIST_PAIR_FOLDR_EQ, Pos.ZERO, true, false);

  /** Helper for {@link #LIST_PAIR_MAP}, {@link #LIST_PAIR_MAP_EQ}. */
  private static class ListPairMap
      extends BasePositionedApplicable2<
          Object, Applicable1, List<List<Object>>> {
    private final boolean equal;

    ListPairMap(BuiltIn builtIn, Pos pos, boolean equal) {
      super(builtIn, pos);
      this.equal = equal;
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new ListPairMap(builtIn, pos, equal);
    }

    @Override
    public Object apply(Applicable1 f, List<List<Object>> listPair) {
      List<Object> list0 = listPair.get(0);
      List<Object> list1 = listPair.get(1);
      if (equal && list0.size() != list1.size()) {
        throw new MorelRuntimeException(BuiltInExn.UNEQUAL_LENGTHS, pos);
      }
      final ImmutableList.Builder<Object> result = ImmutableList.builder();
      forEach(list0, list1, (a, b) -> result.add(f.apply(FlatLists.of(a, b))));
      return result.build();
    }
  }

  /** @see BuiltIn#LIST_PAIR_MAP */
  private static final Applicable LIST_PAIR_MAP =
      new ListPairMap(BuiltIn.LIST_PAIR_MAP, Pos.ZERO, false);

  /** @see BuiltIn#LIST_PAIR_MAP_EQ */
  private static final Applicable LIST_PAIR_MAP_EQ =
      new ListPairMap(BuiltIn.LIST_PAIR_MAP_EQ, Pos.ZERO, true);

  /** @see BuiltIn#LIST_PAIR_UNZIP */
  private static final Applicable LIST_PAIR_UNZIP = new ListPairUnzip(Pos.ZERO);

  /** Implements {@link #LIST_PAIR_UNZIP}. */
  private static class ListPairUnzip
      extends BasePositionedApplicable1<List<List>, List<List>> {
    ListPairUnzip(Pos pos) {
      super(BuiltIn.LIST_PAIR_UNZIP, pos);
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new ListPairUnzip(pos);
    }

    @Override
    public List<List> apply(List<List> lists) {
      if (lists.isEmpty()) {
        throw new MorelRuntimeException(BuiltInExn.EMPTY, pos);
      }
      final ImmutableList.Builder<Object> builder0 = ImmutableList.builder();
      final ImmutableList.Builder<Object> builder1 = ImmutableList.builder();
      for (List<Object> pair : lists) {
        builder0.add(pair.get(0));
        builder1.add(pair.get(1));
      }
      return ImmutableList.of(builder0.build(), builder1.build());
    }
  }

  /** Helper for {@link #LIST_PAIR_ZIP} and {@link #LIST_PAIR_ZIP_EQ}. */
  private static class ListPairZip
      extends BasePositionedApplicable2<List, List, List> {
    ListPairZip(BuiltIn builtIn, Pos pos) {
      super(builtIn, pos);
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new ListPairZip(builtIn, pos);
    }

    @Override
    public List apply(List list0, List list1) {
      if (builtIn == BuiltIn.LIST_PAIR_ZIP_EQ && list0.size() != list1.size()) {
        throw new MorelRuntimeException(BuiltInExn.UNEQUAL_LENGTHS, pos);
      }
      final List<Object> result = new ArrayList<>();
      forEach(list0, list1, (a, b) -> result.add(FlatLists.of(a, b)));
      return result;
    }
  }

  /** @see BuiltIn#LIST_PAIR_ZIP */
  private static final Applicable LIST_PAIR_ZIP =
      new ListPairZip(BuiltIn.LIST_PAIR_ZIP, Pos.ZERO);

  /** @see BuiltIn#LIST_PAIR_ZIP_EQ */
  private static final Applicable LIST_PAIR_ZIP_EQ =
      new ListPairZip(BuiltIn.LIST_PAIR_ZIP_EQ, Pos.ZERO);

  /** @see BuiltIn#LIST_PARTITION */
  private static final Applicable2 LIST_PARTITION =
      listPartition0(BuiltIn.LIST_PARTITION);

  private static Applicable2 listPartition0(BuiltIn builtIn) {
    return new BaseApplicable2<List, Applicable1<Boolean, Object>, List>(
        builtIn) {
      @Override
      public List apply(Applicable1<Boolean, Object> f, List list) {
        final ImmutableList.Builder trueBuilder = ImmutableList.builder();
        final ImmutableList.Builder falseBuilder = ImmutableList.builder();
        for (Object o : list) {
          (f.apply(o) ? trueBuilder : falseBuilder).add(o);
        }
        return ImmutableList.of(trueBuilder.build(), falseBuilder.build());
      }
    };
  }

  /** @see BuiltIn#LIST_REV */
  private static final Applicable LIST_REV =
      new BaseApplicable1<List, List>(BuiltIn.LIST_REV) {
        @Override
        public List apply(List list) {
          return Lists.reverse(list);
        }
      };

  /** @see BuiltIn#LIST_REV_APPEND */
  private static final Applicable2 LIST_REV_APPEND =
      new BaseApplicable2<List, List, List>(BuiltIn.LIST_REV_APPEND) {
        @Override
        public List apply(List list0, List list1) {
          return ImmutableList.builder()
              .addAll(Lists.reverse(list0))
              .addAll(list1)
              .build();
        }
      };

  /** @see BuiltIn#LIST_TABULATE */
  private static final Applicable LIST_TABULATE =
      new ListTabulate(BuiltIn.LIST_TABULATE, Pos.ZERO);

  /** Implements {@link #LIST_TABULATE}. */
  static class ListTabulate
      extends BasePositionedApplicable2<Object, Integer, Applicable1> {
    ListTabulate(BuiltIn builtIn, Pos pos) {
      super(builtIn, pos);
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new ListTabulate(builtIn, pos);
    }

    @Override
    public Object apply(Integer count, Applicable1 f) {
      if (count < 0) {
        throw new MorelRuntimeException(BuiltInExn.SIZE, pos);
      }
      final ImmutableList.Builder builder = ImmutableList.builder();
      for (int i = 0; i < count; i++) {
        builder.add(f.apply(i));
      }
      return builder.build();
    }
  }

  /** @see BuiltIn#LIST_TAKE */
  private static final Applicable LIST_TAKE =
      new ListTake(BuiltIn.LIST_TAKE, Pos.ZERO);

  /** Implements {@link #LIST_TAKE}. */
  private static class ListTake
      extends BasePositionedApplicable2<List, List, Integer> {
    ListTake(BuiltIn builtIn, Pos pos) {
      super(builtIn, pos);
    }

    @Override
    public ListTake withPos(Pos pos) {
      return new ListTake(builtIn, pos);
    }

    @Override
    public List apply(List list, Integer i) {
      if (i < 0 || i > list.size()) {
        throw new MorelRuntimeException(BuiltInExn.SUBSCRIPT, pos);
      }
      return list.subList(0, i);
    }
  }

  /** @see BuiltIn#LIST_TL */
  private static final Applicable LIST_TL =
      new ListTl(BuiltIn.LIST_TL, Pos.ZERO);

  /** Implements {@link #LIST_TL}. */
  private static class ListTl extends BasePositionedApplicable1<List, List> {
    ListTl(BuiltIn builtIn, Pos pos) {
      super(builtIn, pos);
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new ListTl(builtIn, pos);
    }

    @Override
    public List apply(List list) {
      final int size = list.size();
      if (size == 0) {
        throw new MorelRuntimeException(BuiltInExn.EMPTY, pos);
      }
      return list.subList(1, size);
    }
  }

  static Applicable1<Boolean, List> empty(BuiltIn builtIn) {
    return new BaseApplicable1<Boolean, List>(builtIn) {
      @Override
      public Boolean apply(List list) {
        return list.isEmpty();
      }
    };
  }

  /**
   * Implements {@link RelationalCodes#RELATIONAL_ONLY}, {@link #LIST_ONLY} and
   * {@link #BAG_ONLY}.
   */
  static class RelationalOnly extends BasePositionedApplicable1<Object, List> {
    RelationalOnly(BuiltIn builtIn, Pos pos) {
      super(builtIn, pos);
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new RelationalOnly(builtIn, pos);
    }

    @Override
    public Object apply(List list) {
      if (list.isEmpty()) {
        throw new MorelRuntimeException(BuiltInExn.EMPTY, pos);
      }
      if (list.size() > 1) {
        throw new MorelRuntimeException(BuiltInExn.SIZE, pos);
      }
      return list.get(0);
    }
  }
}

// End ListCodes.java
