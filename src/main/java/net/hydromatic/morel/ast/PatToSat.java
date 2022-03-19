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

import net.hydromatic.morel.type.DataType;
import net.hydromatic.morel.type.ForallType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.Ord;
import net.hydromatic.morel.util.Pair;
import net.hydromatic.morel.util.Sat;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.apache.calcite.util.Util;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static net.hydromatic.morel.ast.CoreBuilder.core;

import static java.util.Objects.requireNonNull;

/** Checks whether patterns are exhaustive and/or redundant.
 *
 * <p>The algorithm converts a list of patterns into a boolean formula
 * with several variables, then checks whether the formula is satisfiable
 * (that is, whether there is a combination of assignments of boolean values
 * to the variables such that the formula evaluates to true). */
class PatToSat {
  final TypeSystem typeSystem;
  final Sat sat = new Sat();
  final Map<Path, Slot> pathSlots = new HashMap<>();

  /** Creates a PatToSat. */
  PatToSat(TypeSystem typeSystem) {
    this.typeSystem = requireNonNull(typeSystem, "typeSystem");
  }

  /** Converts a pattern to a logical term. */
  private Sat.Term toTerm(Core.Pat pat) {
    final List<Sat.Term> terms = new ArrayList<>();
    toTerm(pat, EmptyPath.INSTANCE, terms);
    return terms.size() == 1 ? terms.get(0) : sat.and(terms);
  }

  private void toTerm(Core.Pat pat, Path path, List<Sat.Term> terms) {
    switch (pat.op) {
    case WILDCARD_PAT:
    case ID_PAT:
      return; // no constraints to add

    case AS_PAT:
      toTerm(((Core.AsPat) pat).pat, path, terms);
      return;

    case BOOL_LITERAL_PAT:
    case CHAR_LITERAL_PAT:
    case INT_LITERAL_PAT:
    case REAL_LITERAL_PAT:
    case STRING_LITERAL_PAT:
      final Path path2 = path.plus(((Core.LiteralPat) pat).value);
      terms.add(sat.variable(path2.toString()));
      return;

    case CON0_PAT:
      final Core.Con0Pat con0Pat = (Core.Con0Pat) pat;
      terms.add(typeConstructorTerm(path, con0Pat.tyCon));
      return;

    case CON_PAT:
      final Core.ConPat conPat = (Core.ConPat) pat;
      terms.add(typeConstructorTerm(path, conPat.tyCon));
      toTerm(conPat.pat, path, terms);
      return;

    case CONS_PAT:
      final Core.ConPat consPat = (Core.ConPat) pat;
      addConsTerms(path, terms, (Core.TuplePat) consPat.pat);
      return;

    case TUPLE_PAT:
      final Core.TuplePat tuplePat = (Core.TuplePat) pat;
      Ord.forEach(tuplePat.args, (pat2, i) -> {
        Path path3 = path.sub(i);
        toTerm(pat2, path3, terms);
      });
      return;

    case RECORD_PAT:
      final Core.RecordPat recordPat = (Core.RecordPat) pat;
      Ord.forEach(recordPat.args, (pat2, i) -> {
        Path path3 = path.sub(i);
        toTerm(pat2, path3, terms);
      });
      return;

    case LIST_PAT:
      // For list
      //   [a, b, c]
      // built terms as if they had written
      //   CONS (a, CONS (b, CONS (c, NIL))
      // namely
      //   var(tag.0=CONS)
      //   ^ var(tag.0.1=CONS)
      //   ^ var(tag.0.1.1=CONS)
      //   ^ var(tag.0.1.1.1=NIL
      toTerm(listToCons((Core.ListPat) pat), path, terms);
      return;

    default:
      throw new AssertionError(pat.op);
    }
  }

  /** Converts a list pattern into a pattern made up of the {@code CONS} and
   * {@code NIL} constructors of the built-in {@code datatype list}.
   *
   * <p>For example, converts:
   * "[]" to "NIL",
   * "[x]" to "CONS (x, NIL)",
   * "[x, y]" to "CONS (x, CONS (y, NIL))",
   * etc. */
  private Core.Pat listToCons(Core.ListPat listPat) {
    final Type listType = typeSystem.lookup("$list");
    final DataType listDataType = (DataType) ((ForallType) listType).type;
    return listToConsRecurse(listDataType, listPat.args);
  }

  private Core.Pat listToConsRecurse(DataType listDataType,
      List<Core.Pat> args) {
    if (args.isEmpty()) {
      return core.con0Pat(listDataType, "NIL");
    } else {
      return core.consPat(listDataType, "CONS",
          core.tuplePat(typeSystem,
              ImmutableList.of(args.get(0),
                  listToConsRecurse(listDataType, Util.skip(args)))));
    }
  }

  private void addConsTerms(Path path, List<Sat.Term> terms,
      Core.TuplePat tuplePat) {
    terms.add(typeConstructorTerm(path, "CONS"));
    toTerm(tuplePat, path, terms);
  }

  private Sat.Variable typeConstructorTerm(Path path, String con) {
    final Pair<DataType, Type> pair = typeSystem.lookupTyCon(con);
    final DataType dataType = pair.left;
    DataTypeSlot slot =
        (DataTypeSlot) pathSlots.computeIfAbsent(path,
            p -> new DataTypeSlot(dataType, p, sat));
    return slot.constructorMap.get(con);
  }

  /** Returns whether a pattern is covered by a list of patterns.
   *
   * A pattern {@code pat} is said to be covered by a list of patterns
   * {@code patList} if any possible value would be caught by one of the
   * patterns in {@code patList} before reaching {@code pat}. Thus {@code pat}
   * is said to be redundant in that context, and could be removed without
   * affecting behavior. */
  public boolean isCoveredBy(Core.Pat pat, List<Core.Pat> patList) {
    final List<Sat.Term> terms = new ArrayList<>();
    patList.forEach(p -> terms.add(toTerm(p)));
    final Sat.Term term = toTerm(pat);

    final List<Sat.Term> terms1 = new ArrayList<>();
    terms1.add(term);
    terms.forEach(t -> terms1.add(sat.not(t)));

    // Add constraints for tags, which are mutually exclusive.
    // For example, for a type with constructors A, B, C
    //   (tag=A or tag=B or tag=C)
    // because at least one tag must be present, and
    //   (not (tag=A or tag=B)
    //   or not (tag=B or tag=C)
    //   or not (tag=C or tag=A))
    // because at most one tag must be present.
    pathSlots.values().forEach(slot -> {
      if (slot instanceof DataTypeSlot) {
        final List<Sat.Term> terms2 =
            new ArrayList<>(((DataTypeSlot) slot).constructorMap.values());
        terms1.add(sat.or(terms2));

        final List<Sat.Term> terms3 = new ArrayList<>();
        for (int i = 0; i < terms2.size(); i++) {
          terms3.add(sat.not(sat.or(new ElideList<>(terms2, i))));
        }
        terms1.add(sat.or(terms3));
      }
    });
    final Sat.Term formula = sat.and(terms1);
    final Map<Sat.Variable, Boolean> solve = sat.solve(formula);
    return solve == null;
  }

  /** List that removes one particular element from a backing list.
   *
   * @param <E> element type */
  private static class ElideList<E> extends AbstractList<E> {
    private final List<E> list;
    private final int elide;

    ElideList(List<E> list, int elide) {
      this.list = requireNonNull(list, "list");
      this.elide = elide;
    }

    @Override public E get(int index) {
      return list.get(index < elide ? index : index + 1);
    }

    @Override public int size() {
      return list.size() - 1;
    }
  }

  /** Identifies a point in a nested pattern. */
  interface Path {
    @SuppressWarnings("rawtypes")
    default Path plus(Comparable value) {
      return new SubPath(this, value);
    }

    default Path sub(int i) {
      return new SubPath(this, i);
    }
  }

  /** Root path. */
  enum EmptyPath implements Path {
    INSTANCE {
      @Override public String toString() {
        return "/";
      }
    }
  }

  /** Path that is a child of a given parent path.
   * The {@code value} makes it unique within its parent.
   * For tuple and record patterns, {@code value} is the field ordinal. */
  @SuppressWarnings("rawtypes")
  private static class SubPath implements Path {
    private final Path parent;
    private final Comparable value;

    SubPath(Path parent, Comparable value) {
      this.parent = parent;
      this.value = value;
    }

    @Override public String toString() {
      return parent.toString() + '.' + value;
    }
  }

  /** Payload of a {@code Sat.Variable}. */
  private static class Slot {
  }

  /** Payload of a {@code Sat.Variable} that is an algebraic type.
   * There are sub-variables representing whether the tag holds
   * each of its allowed values (each of which is a constructor). */
  private static class DataTypeSlot extends Slot {
    final DataType dataType;
    final ImmutableMap<String, Sat.Variable> constructorMap;

    DataTypeSlot(DataType dataType, Path path, Sat sat) {
      this.dataType = dataType;
      final ImmutableMap.Builder<String, Sat.Variable> b =
          ImmutableMap.builder();
      dataType.typeConstructors.forEach((name, type) ->
          b.put(name, sat.variable(path.toString() + "tag=" + name)));
      this.constructorMap = b.build();
    }
  }
}

// End PatToSat.java
