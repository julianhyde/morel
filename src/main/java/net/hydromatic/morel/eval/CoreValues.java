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

import static java.util.Objects.requireNonNull;
import static net.hydromatic.morel.ast.CoreBuilder.core;
import static net.hydromatic.morel.util.Static.transformEager;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.compile.Inliner;
import net.hydromatic.morel.type.DataType;
import net.hydromatic.morel.type.FnType;
import net.hydromatic.morel.type.ForallType;
import net.hydromatic.morel.type.ListType;
import net.hydromatic.morel.type.MultiType;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.RecordLikeType;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.type.TypeVar;
import org.jspecify.annotations.Nullable;

/**
 * The compiler's tree as Morel values: the datatypes {@code exp} and {@code
 * pat} of the {@code Core} structure.
 *
 * <p>A value of either datatype is a view of a node the compiler holds. It
 * presents the list form every datatype value has -- the constructor's name,
 * then its argument -- so that a {@code case} matches it without knowing, and
 * it renders the argument on demand, one level down, each child wrapped in a
 * view of its own and nothing copied. Applying a constructor goes the other
 * way, {@link #fromConstructor}: it builds the node the arguments describe,
 * through {@code CoreBuilder}, which derives its type.
 *
 * <p>What the view does not render -- a {@code local}, a {@code raise}, a
 * recursive {@code let}, a pattern kind the datatype does not spell -- is
 * {@code OPAQUE} or {@code OPAQUE_PAT}: a nullary constructor whose value still
 * holds the node, so that a rule can pass it along.
 */
public abstract class CoreValues {
  private CoreValues() {}

  /** Wraps an expression as a value of the datatype {@code exp}. */
  public static ExpValue of(Core.Exp exp) {
    return new ExpValue(exp);
  }

  /** Wraps a pattern as a value of the datatype {@code pat}. */
  public static PatValue of(Core.Pat pat) {
    return new PatValue(pat);
  }

  /** Returns the expression a value of the datatype {@code exp} holds. */
  public static Core.Exp toExp(Object value) {
    if (value instanceof ExpValue) {
      return ((ExpValue) value).exp;
    }
    throw new IllegalArgumentException("not an expression: " + value);
  }

  /** Returns the pattern a value of the datatype {@code pat} holds. */
  public static Core.Pat toPat(Object value) {
    if (value instanceof PatValue) {
      return ((PatValue) value).pat;
    }
    throw new IllegalArgumentException("not a pattern: " + value);
  }

  private static Core.IdPat toIdPat(Object value) {
    final Core.Pat pat = toPat(value);
    if (pat instanceof Core.IdPat) {
      return (Core.IdPat) pat;
    }
    throw new IllegalArgumentException("not a name pattern: " + pat);
  }

  private static Core.@Nullable IdPat toOrdinal(Object option) {
    final List<?> list = (List<?>) option;
    return list.size() == 1 ? null : toIdPat(list.get(1));
  }

  private static Object ofOrdinal(Core.@Nullable IdPat ordinal) {
    return ordinal == null ? Codes.OPTION_NONE : Codes.optionSome(of(ordinal));
  }

  /** The common shape: a constructor name, and an argument rendered once. */
  public abstract static class View extends AbstractList<Object> {
    private @Nullable Object arg;
    private boolean rendered;

    abstract String tag();

    /** Renders the argument; null for a nullary constructor. */
    abstract @Nullable Object render();

    private @Nullable Object arg() {
      if (!rendered) {
        arg = render();
        rendered = true;
      }
      return arg;
    }

    @Override
    public int size() {
      return arg() == null ? 1 : 2;
    }

    @Override
    public Object get(int index) {
      switch (index) {
        case 0:
          return tag();
        case 1:
          return requireNonNull(arg());
        default:
          throw new IndexOutOfBoundsException();
      }
    }

    @Override
    public String toString() {
      // Not the argument: rendering it is the matcher's business, and a
      // debugger's string should not do it.
      return tag() + (rendered && arg != null ? " " + arg : "");
    }
  }

  /** A value of the datatype {@code exp}. */
  public static class ExpValue extends View {
    public final Core.Exp exp;

    ExpValue(Core.Exp exp) {
      this.exp = requireNonNull(exp);
    }

    @Override
    public boolean equals(@Nullable Object o) {
      return o == this
          || o instanceof ExpValue && ((ExpValue) o).exp.equals(exp);
    }

    @Override
    public int hashCode() {
      return exp.hashCode();
    }

    @Override
    String tag() {
      switch (exp.op) {
        case FILTER:
          return "FILTER";
        case PROJECT:
          return "PROJECT";
        case JOIN:
          return "JOIN";
        case GROUP:
          return "GROUP";
        case SORT:
          return "SORT";
        case UNORDER:
          return "UNORDER";
        case SKIP:
          return "SKIP";
        case TAKE:
          return "TAKE";
        case IF_EMPTY:
          return "IF_EMPTY";
        case UNION:
          return "UNION";
        case INTERSECT:
          return "INTERSECT";
        case EXCEPT:
          return "EXCEPT";
        case ID:
          return "ID";
        case BOOL_LITERAL:
        case CHAR_LITERAL:
        case INT_LITERAL:
        case REAL_LITERAL:
        case STRING_LITERAL:
        case UNIT_LITERAL:
          return "LITERAL";
        case FN_LITERAL:
          return "BUILTIN";
        case RECORD_SELECTOR:
          return "SELECTOR";
        case APPLY:
          return "APPLY";
        case TUPLE:
          return ((Core.Tuple) exp).type instanceof RecordType
              ? "RECORD_EXP"
              : "TUPLE";
        case FN:
          return "FN";
        case LET:
          return ((Core.Let) exp).decl instanceof Core.NonRecValDecl
                  && ((Core.NonRecValDecl) ((Core.Let) exp).decl).overloadPat
                      == null
              ? "LET"
              : "OPAQUE";
        case CASE:
          return "CASE";
        default:
          return "OPAQUE";
      }
    }

    @Override
    @Nullable
    Object render() {
      switch (tag()) {
        case "FILTER":
          {
            final Core.Filter filter = (Core.Filter) exp;
            // Fields in label order: condition, input, ordinalPat, row.
            return ImmutableList.of(
                of(filter.condition),
                of(filter.input),
                ofOrdinal(filter.ordinal),
                of(filter.row));
          }
        case "PROJECT":
          {
            final Core.Project project = (Core.Project) exp;
            // exp, input, ordinalPat, row.
            return ImmutableList.of(
                of(project.exp),
                of(project.input),
                ofOrdinal(project.ordinal),
                of(project.row));
          }
        case "JOIN":
          {
            final Core.Join join = (Core.Join) exp;
            // condition, kind, leftInput, leftRow, ordinalPat, rightInput,
            // rightRow.
            return ImmutableList.of(
                of(join.condition),
                ImmutableList.of(join.joinType.name()),
                of(join.left),
                of(join.leftRow),
                ofOrdinal(join.ordinal),
                of(join.right),
                of(join.rightRow));
          }
        case "GROUP":
          {
            final Core.Group group = (Core.Group) exp;
            final List<Object> aggregates = new ArrayList<>();
            group.aggregates.forEach(
                (name, aggregate) ->
                    aggregates.add(
                        ImmutableList.of(
                            name,
                            of(aggregate.aggregate),
                            aggregate.argument == null
                                ? Codes.OPTION_NONE
                                : Codes.optionSome(of(aggregate.argument)),
                            aggregate.type)));
            final List<Object> keys = new ArrayList<>();
            group.keys.forEach(
                (name, key) -> keys.add(ImmutableList.of(name, of(key))));
            // aggregates, input, keys, ordinalPat, row.
            return ImmutableList.of(
                aggregates,
                of(group.input),
                keys,
                ofOrdinal(group.ordinal),
                of(group.row));
          }
        case "SORT":
          {
            final Core.Sort sort = (Core.Sort) exp;
            // input, key, ordinalPat, row.
            return ImmutableList.of(
                of(sort.input),
                of(sort.exp),
                ofOrdinal(sort.ordinal),
                of(sort.row));
          }
        case "UNORDER":
          return of(((Core.Unorder) exp).input);
        case "SKIP":
          {
            final Core.Skip skip = (Core.Skip) exp;
            // count, input.
            return ImmutableList.of(of(skip.count), of(skip.input));
          }
        case "TAKE":
          {
            final Core.Take take = (Core.Take) exp;
            return ImmutableList.of(of(take.count), of(take.input));
          }
        case "IF_EMPTY":
          {
            final Core.IfEmpty ifEmpty = (Core.IfEmpty) exp;
            // input, otherwise.
            return ImmutableList.of(of(ifEmpty.input), of(ifEmpty.exp));
          }
        case "UNION":
        case "INTERSECT":
        case "EXCEPT":
          {
            final Core.SetRel setRel = (Core.SetRel) exp;
            // inputs, unique.
            return ImmutableList.of(
                transformEager(setRel.inputs, CoreValues::of), setRel.distinct);
          }
        case "ID":
          return of(((Core.Id) exp).idPat);
        case "LITERAL":
          {
            final Core.Literal literal = (Core.Literal) exp;
            return Variant.of(literal.type, literalValue(literal));
          }
        case "BUILTIN":
          {
            // The name and the type: a polymorphic built-in's literal has
            // the type it was instantiated at, which the name alone loses.
            final BuiltIn builtIn = ((Core.Literal) exp).unwrap(BuiltIn.class);
            return ImmutableList.of(
                builtIn.structure.equals("Top")
                    ? builtIn.mlName
                    : builtIn.structure + "." + builtIn.mlName,
                exp.type);
          }
        case "SELECTOR":
          {
            final Core.RecordSelector selector = (Core.RecordSelector) exp;
            return ImmutableList.of(
                selector.fieldName(), ((FnType) selector.type).paramType);
          }
        case "APPLY":
          {
            final Core.Apply apply = (Core.Apply) exp;
            return ImmutableList.of(of(apply.fn), of(apply.arg));
          }
        case "TUPLE":
          return transformEager(((Core.Tuple) exp).args, CoreValues::of);
        case "RECORD_EXP":
          {
            final Core.Tuple tuple = (Core.Tuple) exp;
            final RecordType recordType = (RecordType) tuple.type;
            final List<Object> fields = new ArrayList<>();
            int i = 0;
            for (String name : recordType.argNameTypes.keySet()) {
              fields.add(ImmutableList.of(name, of(tuple.args.get(i++))));
            }
            return fields;
          }
        case "FN":
          {
            final Core.Fn fn = (Core.Fn) exp;
            return ImmutableList.of(of(fn.idPat), of(fn.exp));
          }
        case "LET":
          {
            final Core.Let let = (Core.Let) exp;
            final Core.NonRecValDecl decl = (Core.NonRecValDecl) let.decl;
            // body, pat, value.
            return ImmutableList.of(of(let.exp), of(decl.pat), of(decl.exp));
          }
        case "CASE":
          {
            final Core.Case case_ = (Core.Case) exp;
            return ImmutableList.of(
                of(case_.exp),
                transformEager(
                    case_.matchList,
                    match -> ImmutableList.of(of(match.pat), of(match.exp))));
          }
        default:
          return null;
      }
    }
  }

  /** A value of the datatype {@code pat}. */
  public static class PatValue extends View {
    public final Core.Pat pat;

    PatValue(Core.Pat pat) {
      this.pat = requireNonNull(pat);
    }

    @Override
    public boolean equals(@Nullable Object o) {
      return o == this
          || o instanceof PatValue && ((PatValue) o).pat.equals(pat);
    }

    @Override
    public int hashCode() {
      return pat.hashCode();
    }

    @Override
    String tag() {
      switch (pat.op) {
        case ID_PAT:
          return "ID_PAT";
        case WILDCARD_PAT:
          return "WILDCARD";
        case TUPLE_PAT:
          return "TUPLE_PAT";
        case RECORD_PAT:
          return "RECORD_PAT";
        case CON0_PAT:
          return "CON0_PAT";
        case CON_PAT:
          return "CON_PAT";
        default:
          return "OPAQUE_PAT";
      }
    }

    @Override
    @Nullable
    Object render() {
      switch (tag()) {
        case "ID_PAT":
          {
            final Core.IdPat idPat = (Core.IdPat) pat;
            // i, name, ty.
            return ImmutableList.of(idPat.i, idPat.name, idPat.type);
          }
        case "WILDCARD":
          return pat.type;
        case "TUPLE_PAT":
          return transformEager(((Core.TuplePat) pat).args, CoreValues::of);
        case "RECORD_PAT":
          {
            final Core.RecordPat recordPat = (Core.RecordPat) pat;
            final RecordType recordType = (RecordType) recordPat.type;
            final List<Object> fields = new ArrayList<>();
            int i = 0;
            for (String name : recordType.argNameTypes.keySet()) {
              fields.add(ImmutableList.of(name, of(recordPat.args.get(i++))));
            }
            return fields;
          }
        case "CON0_PAT":
          return ImmutableList.of(((Core.Con0Pat) pat).tyCon, pat.type);
        case "CON_PAT":
          {
            final Core.ConPat conPat = (Core.ConPat) pat;
            return ImmutableList.of(conPat.tyCon, of(conPat.pat), pat.type);
          }
        default:
          return null;
      }
    }
  }

  /**
   * Builds the node that a constructor of {@code exp} or {@code pat} applied to
   * an argument describes, and returns it wrapped.
   *
   * <p>The types come from the parts: a node's from its inputs and expressions,
   * an application's from its function, a pattern's from the {@code ty} it
   * carries.
   */
  public static Object fromConstructor(
      String name, Object arg, TypeSystem typeSystem) {
    final List<?> fields =
        arg instanceof List ? (List<?>) arg : ImmutableList.of();
    switch (name) {
      case "FILTER":
        // condition, input, ordinalPat, row.
        return of(
            core.filter(
                toIdPat(fields.get(3)),
                toOrdinal(fields.get(2)),
                toExp(fields.get(1)),
                toExp(fields.get(0))));
      case "PROJECT":
        // exp, input, ordinalPat, row.
        return of(
            core.project(
                typeSystem,
                toIdPat(fields.get(3)),
                toOrdinal(fields.get(2)),
                toExp(fields.get(1)),
                toExp(fields.get(0))));
      case "JOIN":
        // condition, kind, leftInput, leftRow, ordinalPat, rightInput,
        // rightRow.
        return of(
            core.join(
                typeSystem,
                Core.Rel.JoinType.valueOf(
                    (String) ((List<?>) fields.get(1)).get(0)),
                toIdPat(fields.get(3)),
                toIdPat(fields.get(6)),
                toOrdinal(fields.get(4)),
                toExp(fields.get(2)),
                toExp(fields.get(5)),
                toExp(fields.get(0))));
      case "GROUP":
        {
          // aggregates, input, keys, ordinalPat, row.
          final SortedMap<String, Core.Aggregate> aggregates = new TreeMap<>();
          for (Object o : (List<?>) fields.get(0)) {
            final List<?> triple = (List<?>) o;
            final Core.Exp fn = toExp(triple.get(1));
            final List<?> argument = (List<?>) triple.get(2);
            // The result type is carried: the function may be polymorphic,
            // and its type alone does not say what it makes of the group.
            aggregates.put(
                (String) triple.get(0),
                core.aggregate(
                    Pos.ZERO,
                    (Type) triple.get(3),
                    fn,
                    argument.size() == 1 ? null : toExp(argument.get(1))));
          }
          final SortedMap<String, Core.Exp> keys = new TreeMap<>();
          for (Object o : (List<?>) fields.get(2)) {
            final List<?> pair = (List<?>) o;
            keys.put((String) pair.get(0), toExp(pair.get(1)));
          }
          return of(
              core.group(
                  typeSystem,
                  toIdPat(fields.get(4)),
                  toOrdinal(fields.get(3)),
                  toExp(fields.get(1)),
                  ImmutableSortedMap.copyOfSorted(keys),
                  ImmutableSortedMap.copyOfSorted(aggregates)));
        }
      case "SORT":
        // input, key, ordinalPat, row.
        return of(
            core.sort(
                typeSystem,
                toIdPat(fields.get(3)),
                toOrdinal(fields.get(2)),
                toExp(fields.get(0)),
                toExp(fields.get(1))));
      case "UNORDER":
        return of(core.unorder(typeSystem, toExp(arg)));
      case "SKIP":
        // count, input.
        return of(core.skip(toExp(fields.get(1)), toExp(fields.get(0))));
      case "TAKE":
        return of(core.take(toExp(fields.get(1)), toExp(fields.get(0))));
      case "IF_EMPTY":
        // input, otherwise.
        return of(core.ifEmpty(toExp(fields.get(0)), toExp(fields.get(1))));
      case "UNION":
        return of(
            core.union(typeSystem, (Boolean) fields.get(1), inputs(fields)));
      case "INTERSECT":
        return of(
            core.intersect(
                typeSystem, (Boolean) fields.get(1), inputs(fields)));
      case "EXCEPT":
        return of(
            core.except(typeSystem, (Boolean) fields.get(1), inputs(fields)));
      case "ID":
        return of(core.id((Core.NamedPat) toPat(arg)));
      case "LITERAL":
        {
          final Variant variant = (Variant) arg;
          if (variant.type instanceof PrimitiveType) {
            return of(
                core.literal((PrimitiveType) variant.type, variant.value));
          }
          // A constant of a type without a literal syntax, a list say: a
          // value literal, printing as the expression that spells it.
          return of(
              core.valueLiteral(
                  Inliner.valueToExp(typeSystem, variant.type, variant.value),
                  variant.value));
        }
      case "BUILTIN":
        return of(
            core.functionLiteral(
                (Type) fields.get(1), builtIn((String) fields.get(0))));
      case "SELECTOR":
        return of(
            core.recordSelector(
                typeSystem,
                (RecordLikeType) fields.get(1),
                (String) fields.get(0)));
      case "APPLY":
        return of(
            apply(typeSystem, toExp(fields.get(0)), toExp(fields.get(1))));
      case "TUPLE":
        return of(
            core.tuple(
                typeSystem, null, transformEager(fields, CoreValues::toExp)));
      case "RECORD_EXP":
        {
          final List<Map.Entry<String, Core.Exp>> nameExps = new ArrayList<>();
          for (Object o : fields) {
            final List<?> pair = (List<?>) o;
            nameExps.add(Map.entry((String) pair.get(0), toExp(pair.get(1))));
          }
          return of(core.record(typeSystem, nameExps));
        }
      case "FN":
        {
          final Core.IdPat idPat = toIdPat(fields.get(0));
          final Core.Exp body = toExp(fields.get(1));
          return of(
              core.fn(typeSystem.fnType(idPat.type, body.type), idPat, body));
        }
      case "LET":
        // body, pat, value.
        return of(
            core.let(
                core.nonRecValDecl(
                    Pos.ZERO,
                    (Core.NamedPat) toPat(fields.get(1)),
                    null,
                    toExp(fields.get(2))),
                toExp(fields.get(0))));
      case "CASE":
        {
          final Core.Exp exp = toExp(fields.get(0));
          final List<Core.Match> matches = new ArrayList<>();
          for (Object o : (List<?>) fields.get(1)) {
            final List<?> pair = (List<?>) o;
            matches.add(
                core.match(Pos.ZERO, toPat(pair.get(0)), toExp(pair.get(1))));
          }
          return of(
              core.caseOf(Pos.ZERO, matches.get(0).exp.type, exp, matches));
        }
      default:
        return patFromConstructor(name, arg, typeSystem);
    }
  }

  /** As {@link #fromConstructor}, for the constructors of {@code pat}. */
  private static Object patFromConstructor(
      String name, Object arg, TypeSystem typeSystem) {
    final List<?> fields =
        arg instanceof List ? (List<?>) arg : ImmutableList.of();
    switch (name) {
      case "ID_PAT":
        // i, name, ty.
        return of(
            core.idPat(
                (Type) fields.get(2),
                (String) fields.get(1),
                (Integer) fields.get(0)));
      case "WILDCARD":
        return of(core.wildcardPat((Type) arg));
      case "TUPLE_PAT":
        return of(
            core.tuplePat(
                typeSystem, transformEager(fields, CoreValues::toPat)));
      case "RECORD_PAT":
        {
          final Map<String, Core.Pat> namePats = new TreeMap<>();
          for (Object o : fields) {
            final List<?> pair = (List<?>) o;
            namePats.put((String) pair.get(0), toPat(pair.get(1)));
          }
          return of(core.recordPat(typeSystem, namePats));
        }
      case "CON0_PAT":
        return of(
            core.con0Pat((DataType) fields.get(1), (String) fields.get(0)));
      case "CON_PAT":
        return of(
            core.conPat(
                (Type) fields.get(2),
                (String) fields.get(0),
                toPat(fields.get(1))));
      default:
        throw new IllegalArgumentException(
            "cannot build " + name + ": not a node the view renders");
    }
  }

  /**
   * Applies a function to an argument, deriving the result type.
   *
   * <p>A function's type says what it returns, unless the function is a
   * built-in's literal, whose type may be the generic one -- {@code forall 'a.
   * 'a * 'a -> bool} for {@code =} -- or an overload set, or, for an internal
   * operator, nothing useful at all. Those are instantiated at the argument's
   * type, as the resolver instantiates them.
   */
  private static Core.Exp apply(
      TypeSystem typeSystem, Core.Exp fn, Core.Exp arg) {
    Type fnType = fn.type;
    if (fn.op == Op.FN_LITERAL && !(fnType instanceof FnType)) {
      final BuiltIn builtIn = ((Core.Literal) fn).unwrap(BuiltIn.class);
      if (builtIn == BuiltIn.Z_LIST) {
        // "[a, b]" is "list (a, b)", of the type of its elements.
        final Core.Tuple tuple = (Core.Tuple) arg;
        return core.apply(
            Pos.ZERO, typeSystem.listType(tuple.args.get(0).type), fn, arg);
      }
      fnType = builtIn.typeFunction.apply(typeSystem);
    }
    if (fnType instanceof MultiType) {
      fnType =
          ((MultiType) fnType)
              .types.stream()
                  .filter(t -> t.canCallArgOf(arg.type))
                  .findFirst()
                  .orElseThrow(
                      () ->
                          new IllegalArgumentException(
                              "no overload of " + fn + " takes " + arg.type));
    }
    if (fnType instanceof ForallType) {
      final ForallType forallType = (ForallType) fnType;
      final Type[] found = new Type[forallType.parameterCount];
      match(((FnType) forallType.type).paramType, arg.type, found);
      for (Type type : found) {
        if (type == null) {
          throw new IllegalArgumentException(
              "cannot instantiate " + fn + " at " + arg.type);
        }
      }
      fnType = typeSystem.apply(forallType, ImmutableList.copyOf(found));
    }
    if (fnType instanceof FnType) {
      return core.apply(Pos.ZERO, ((FnType) fnType).resultType, fn, arg);
    }
    throw new IllegalArgumentException(
        "cannot apply " + fn + " of type " + fn.type);
  }

  /** Binds the type variables of a pattern type to the parts of a type. */
  private static void match(Type pattern, Type actual, Type[] found) {
    if (pattern instanceof TypeVar) {
      final int ordinal = ((TypeVar) pattern).ordinal;
      if (ordinal < found.length && found[ordinal] == null) {
        found[ordinal] = actual;
      }
    } else if (pattern instanceof FnType && actual instanceof FnType) {
      match(((FnType) pattern).paramType, ((FnType) actual).paramType, found);
      match(((FnType) pattern).resultType, ((FnType) actual).resultType, found);
    } else if (pattern instanceof ListType && actual instanceof ListType) {
      match(
          ((ListType) pattern).elementType,
          ((ListType) actual).elementType,
          found);
    } else if (pattern instanceof RecordLikeType
        && actual instanceof RecordLikeType) {
      final List<Type> patterns = ((RecordLikeType) pattern).argTypes();
      final List<Type> actuals = ((RecordLikeType) actual).argTypes();
      for (int i = 0; i < patterns.size() && i < actuals.size(); i++) {
        match(patterns.get(i), actuals.get(i), found);
      }
    } else if (pattern instanceof DataType && actual instanceof DataType) {
      final List<Type> patterns = ((DataType) pattern).arguments;
      final List<Type> actuals = ((DataType) actual).arguments;
      for (int i = 0; i < patterns.size() && i < actuals.size(); i++) {
        match(patterns.get(i), actuals.get(i), found);
      }
    }
  }

  private static List<Core.Exp> inputs(List<?> fields) {
    return transformEager((List<?>) fields.get(0), CoreValues::toExp);
  }

  /**
   * Returns a literal's value as the evaluator represents it: an {@code int}
   * literal holds a decimal and evaluates to an integer.
   *
   * <p>A value literal -- a constant of a type with no literal syntax, the
   * collection a name was inlined to, say -- is not rendered: it prints as the
   * expression it stands for, which no rendering keeps, so it is {@code
   * OPAQUE}.
   */
  private static Object literalValue(Core.Literal literal) {
    switch (literal.op) {
      case INT_LITERAL:
        return literal.unwrap(Integer.class);
      case REAL_LITERAL:
        return literal.unwrap(Float.class);
      default:
        return literal.value;
    }
  }

  /** Looks up a built-in by its name, qualified by structure if it has one. */
  private static BuiltIn builtIn(String name) {
    final int dot = name.indexOf('.');
    final @Nullable BuiltIn builtIn;
    if (dot < 0) {
      builtIn = BuiltIn.BY_ML_NAME.get(name);
    } else if (name.startsWith("$.")) {
      // An internal operator, "andalso" say, has no structure to look in.
      builtIn =
          Arrays.stream(BuiltIn.values())
              .filter(
                  b ->
                      b.structure.equals("$")
                          && b.mlName.equals(name.substring(dot + 1)))
              .findFirst()
              .orElse(null);
    } else {
      final BuiltIn.@Nullable Structure structure =
          BuiltIn.BY_STRUCTURE.get(name.substring(0, dot));
      builtIn =
          structure == null
              ? null
              : structure.memberMap.get(name.substring(dot + 1));
    }
    if (builtIn == null) {
      throw new IllegalArgumentException("unknown built-in: " + name);
    }
    return builtIn;
  }

  /** Prints an expression as {@code Sys.planOf} prints a query. */
  public static String print(Core.Exp exp, TypeSystem typeSystem, int width) {
    return exp instanceof Core.Rel
        ? exp.unparsePlan(typeSystem, width)
        : exp.unparseRenumbered(typeSystem, width, true);
  }
}

// End CoreValues.java
