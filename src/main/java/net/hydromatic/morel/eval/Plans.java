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

import static com.google.common.collect.ImmutableList.of;
import static net.hydromatic.morel.compile.BuiltIn.Constructor.*;
import static net.hydromatic.morel.util.Static.transform;
import static net.hydromatic.morel.util.Static.transformEager;

import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.type.DataType;
import net.hydromatic.morel.type.FnType;
import net.hydromatic.morel.type.ForallType;
import net.hydromatic.morel.type.ListType;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.TupleType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeVar;
import net.hydromatic.morel.util.PairList;

/**
 * Helpers for the {@code Plan} structure.
 *
 * <p>Reifies a typed Morel {@link Core.Exp} as a runtime value of the
 * Morel-level {@code Core.expr} datatype, so that {@code Plan.core} can return
 * a first-class representation of any Morel expression. The runtime
 * representation of a datatype value is a list whose first element is the
 * constructor name (per the convention used by other built-in datatypes; see
 * {@link Codes#OPTION_NONE}).
 */
public class Plans {
  private Plans() {}

  /**
   * Reified Core for an empty {@code from} expression: a {@code LIST_LITERAL}
   * containing a single {@code UNIT_LITERAL} (i.e. {@code [()]} of type {@code
   * unit list}).
   */
  private static final ImmutableList<Object> EMPTY_FROM_EXPR =
      of(
          CORE_EXPR_LIST_LITERAL.constructor,
          of(
              of(of(CORE_EXPR_UNIT_LITERAL.constructor)),
              of(TYPE_LIST.constructor, of(TYPE_UNIT.constructor))));

  /**
   * Returns whether {@code fn} refers to the `op +` operator. Matches both the
   * {@code FN_LITERAL} forms (after Inliner conversion) and the {@code Id "op
   * +"} form (when the Plan.core path suppressed inlining).
   */
  private static boolean isOpPlus(Core.Exp fn) {
    if (fn.op == Op.FN_LITERAL) {
      BuiltIn b = ((Core.Literal) fn).unwrap(BuiltIn.class);
      return b == BuiltIn.Z_PLUS_INT
          || b == BuiltIn.Z_PLUS_REAL
          || b == BuiltIn.OP_PLUS;
    }
    return fn.op == Op.ID && ((Core.Id) fn).idPat.name.equals("op +");
  }

  /**
   * Reifies a Core expression as a {@code Core.expr} value. Sub-expressions
   * (and sub-types) that are structurally equal share the same object — e.g.
   * within a single call, {@code int list} appears as one cached value wherever
   * it occurs, and {@code 1 + 2} as one cached value if it occurs twice.
   */
  public static Object reifyExp(Core.Exp exp) {
    return new Reifier().reifyExp(exp);
  }

  /**
   * Holds a per-call interning cache. Each {@code reifyExp} and {@code
   * reifyType} method ends in {@link #intern}, so equal-by-structure
   * sub-results share a single object.
   */
  private static class Reifier {
    private final Map<Object, Object> cache = new HashMap<>();

    /** Returns the canonical instance for {@code value}. */
    @SuppressWarnings("unchecked")
    private <T> T intern(T value) {
      Object existing = cache.putIfAbsent(value, value);
      return existing == null ? value : (T) existing;
    }

    /** Builds and interns a nullary-constructor value {@code [name]}. */
    private ImmutableList<Object> iof(BuiltIn.Constructor con) {
      return intern(of(con.constructor));
    }

    /** Builds and interns a unary-constructor value {@code [name, payload]}. */
    private ImmutableList<Object> iof(BuiltIn.Constructor con, Object payload) {
      return intern(of(con.constructor, payload));
    }

    Object reifyExp(Core.Exp exp) {
      switch (exp.op) {
          // lint: sort until '#}' where '##case '
        case INT_LITERAL:
          return iof(
              CORE_EXPR_INT_LITERAL,
              ((Core.Literal) exp).unwrap(Integer.class));

        case REAL_LITERAL:
          return iof(
              CORE_EXPR_REAL_LITERAL, ((Core.Literal) exp).unwrap(Float.class));

        case STRING_LITERAL:
          return iof(
              CORE_EXPR_STRING_LITERAL,
              ((Core.Literal) exp).unwrap(String.class));

        case CHAR_LITERAL:
          return iof(
              CORE_EXPR_CHAR_LITERAL,
              ((Core.Literal) exp).unwrap(Character.class));

        case BOOL_LITERAL:
          return iof(
              CORE_EXPR_BOOL_LITERAL,
              ((Core.Literal) exp).unwrap(Boolean.class));

        case UNIT_LITERAL:
          return iof(CORE_EXPR_UNIT_LITERAL);

        case ID:
          Core.Id id = (Core.Id) exp;
          // Strip the "$N" overload-resolution suffix from identifier names
          // (e.g. "op elem$1" -> "op elem"). That suffix is an internal
          // disambiguator and varies across resolutions; canonical reified
          // form uses the base name.
          String idName = id.idPat.name;
          int dollar = idName.indexOf('$');
          if (dollar >= 0) {
            idName = idName.substring(0, dollar);
          }
          return iof(CORE_EXPR_VAR, of(idName, reifyType(id.type)));

        case FN_LITERAL:
          // A bare reference to a built-in (e.g. `op elem` resolved to a
          // FN_LITERAL): reify as VAR carrying the built-in's ML name.
          BuiltIn fnBi = ((Core.Literal) exp).unwrap(BuiltIn.class);
          return iof(CORE_EXPR_VAR, of(fnBi.mlName, reifyType(exp.type)));

        case VALUE_LITERAL:
          // The Inliner replaces an Id whose value is statically known
          // with a VALUE_LITERAL carrying the value. For constructor values
          // (list whose first element is the constructor's name), reify as
          // a VAR with that name; otherwise we lose the source identifier
          // and fall back to a placeholder.
          Object value = ((Core.Literal) exp).unwrap(Object.class);
          if (value instanceof List
              && !((List<?>) value).isEmpty()
              && ((List<?>) value).get(0) instanceof String) {
            return iof(
                CORE_EXPR_VAR,
                of((String) ((List<?>) value).get(0), reifyType(exp.type)));
          }
          return iof(CORE_EXPR_VAR, of("<value>", reifyType(exp.type)));

        case TUPLE:
          final Core.Tuple tup = (Core.Tuple) exp;
          // Canonicalize: an empty tuple or empty record is unit.
          if (tup.args.isEmpty()) {
            return iof(CORE_EXPR_UNIT_LITERAL);
          }
          if (tup.type instanceof RecordType) {
            // Named-field record. argNameTypes iterates in canonical
            // sorted-by-name order, parallel to tup.args. Zip the names
            // with the reified args into a PairList in one pass.
            final PairList<String, Object> fields =
                PairList.fromZip(
                    ((RecordType) tup.type).argNameTypes.keySet(),
                    transform(tup.args, this::reifyExp));
            return iof(
                CORE_EXPR_RECORD,
                of(
                    fields.transformEager(ImmutableList::of),
                    reifyType(tup.type)));
          } else {
            return iof(
                CORE_EXPR_TUPLE, transformEager(tup.args, this::reifyExp));
          }

        case APPLY:
          Core.Apply ap = (Core.Apply) exp;
          // Recognize `op +` (whether arrived as FN_LITERAL of Z_PLUS_INT/REAL
          // or as the Id "op +") and reify as PLUS.
          if (isOpPlus(ap.fn)) {
            List<Core.Exp> args = ((Core.Tuple) ap.arg).args;
            return iof(
                CORE_EXPR_PLUS,
                of(
                    reifyExp(args.get(0)),
                    reifyExp(args.get(1)),
                    reifyType(ap.type)));
          }
          if (ap.fn.op == Op.FN_LITERAL) {
            BuiltIn b = ((Core.Literal) ap.fn).unwrap(BuiltIn.class);
            if (b == BuiltIn.Z_LIST) {
              return iof(
                  CORE_EXPR_LIST_LITERAL,
                  of(
                      transformEager(
                          ((Core.Tuple) ap.arg).args, this::reifyExp),
                      reifyType(ap.type)));
            }
          }
          // Record-selector application becomes FIELD: (#name r) ->
          // FIELD(r, name, t).
          if (ap.fn.op == Op.RECORD_SELECTOR) {
            Core.RecordSelector sel = (Core.RecordSelector) ap.fn;
            return iof(
                CORE_EXPR_FIELD,
                of(reifyExp(ap.arg), sel.fieldName(), reifyType(ap.type)));
          }
          // General application: APPLY(fn, arg, type).
          return iof(
              CORE_EXPR_APPLY,
              of(reifyExp(ap.fn), reifyExp(ap.arg), reifyType(ap.type)));

        case FROM:
          return reifyFrom((Core.From) exp);

        case CASE:
          Core.Case caseExp = (Core.Case) exp;
          ImmutableList.Builder<Object> matches = ImmutableList.builder();
          for (Core.Match m : caseExp.matchList) {
            matches.add(of(m.pat.toString(), reifyExp(m.exp)));
          }
          return iof(
              CORE_EXPR_CASE,
              of(reifyExp(caseExp.exp), matches.build(), reifyType(exp.type)));

        case FN:
          Core.Fn fn = (Core.Fn) exp;
          return iof(
              CORE_EXPR_FN,
              of(fn.idPat.name, reifyExp(fn.exp), reifyType(exp.type)));

        case LET:
          Core.Let let = (Core.Let) exp;
          return reifyLet(let);

        case RAISE:
          Core.Raise raise = (Core.Raise) exp;
          return iof(
              CORE_EXPR_RAISE, of(reifyExp(raise.exp), reifyType(exp.type)));

        default:
          throw new UnsupportedOperationException(
              "Plan.core: cannot yet reify " + exp.op + " (" + exp + ")");
      }
    }

    /**
     * Reifies a {@link Core.Let} into {@code LET ([(name, value), ...], body)}.
     * Handles only single-pattern bindings (the typical case); collapses nested
     * LETs of NonRecValDecls into one if convenient.
     */
    Object reifyLet(Core.Let let) {
      Core.ValDecl decl = let.decl;
      ImmutableList<Object> bindings;
      if (decl instanceof Core.NonRecValDecl) {
        Core.NonRecValDecl nrd = (Core.NonRecValDecl) decl;
        bindings = of(of(nrd.pat.name, reifyExp(nrd.exp)));
      } else {
        // RecValDecl: multiple mutually-recursive bindings.
        Core.RecValDecl rvd = (Core.RecValDecl) decl;
        bindings =
            transformEager(
                rvd.list, nrd -> of(nrd.pat.name, reifyExp(nrd.exp)));
      }
      return iof(CORE_EXPR_LET, of(bindings, reifyExp(let.exp)));
    }

    /**
     * Reifies a {@link Core.From} into a relational tree of FILTER / PROJECT /
     * VAR nodes. Each step reads the variable bound by the preceding scan via
     * {@code VAR "<name>"}; scoping is implicit (the bound variable is in scope
     * for steps that follow).
     */
    Object reifyFrom(Core.From from) {
      // An empty `from` (no steps) evaluates to the singleton `[()]` of type
      // `unit list`. Reify it as a LIST_LITERAL containing a single
      // UNIT_LITERAL.
      Object current = intern(EMPTY_FROM_EXPR);
      boolean haveScan = false;
      for (Core.FromStep step : from.steps) {
        switch (step.op) {
            // lint: sort until '#}' where '##case '
          case SCAN:
            Core.Scan scan = (Core.Scan) step;
            if (!haveScan) {
              current = reifyExp(scan.exp);
              haveScan = true;
            } else {
              throw new UnsupportedOperationException(
                  "Plan.core: multi-scan `from` not yet supported");
            }
            break;
          case WHERE:
            Core.Where where = (Core.Where) step;
            current = iof(CORE_EXPR_FILTER, of(current, reifyExp(where.exp)));
            break;
          case YIELD:
            Core.Yield yield = (Core.Yield) step;
            current = iof(CORE_EXPR_PROJECT, of(current, reifyExp(yield.exp)));
            break;
          case ORDER:
            Core.Order order = (Core.Order) step;
            current = iof(CORE_EXPR_ORDER, of(current, reifyExp(order.exp)));
            break;
          case SKIP:
            Core.Skip skip = (Core.Skip) step;
            current = iof(CORE_EXPR_SKIP, of(current, reifyExp(skip.exp)));
            break;
          case TAKE:
            Core.Take take = (Core.Take) step;
            current = iof(CORE_EXPR_TAKE, of(current, reifyExp(take.exp)));
            break;
          case UNORDER:
            current = iof(CORE_EXPR_UNORDER, current);
            break;
          case GROUP:
            Core.Group group = (Core.Group) step;
            ImmutableList.Builder<Object> keys = ImmutableList.builder();
            group.groupExps.forEach(
                (k, v) -> keys.add(of(k.name, reifyExp(v))));
            ImmutableList.Builder<Object> aggs = ImmutableList.builder();
            group.aggregates.forEach(
                (k, agg) -> aggs.add(of(k.name, reifyExp(agg.aggregate))));
            current =
                iof(CORE_EXPR_GROUP, of(current, keys.build(), aggs.build()));
            break;
          case UNION:
          case INTERSECT:
          case EXCEPT:
            Core.SetStep setStep = (Core.SetStep) step;
            BuiltIn.Constructor setCon =
                step.op == Op.UNION
                    ? CORE_EXPR_UNION
                    : step.op == Op.INTERSECT
                        ? CORE_EXPR_INTERSECT
                        : CORE_EXPR_EXCEPT;
            current =
                iof(
                    setCon,
                    of(
                        current,
                        setStep.distinct,
                        transformEager(setStep.args, this::reifyExp)));
            break;
          default:
            throw new UnsupportedOperationException(
                "Plan.core: from-step " + step.op + " not yet supported");
        }
      }
      return current;
    }

    /**
     * Reifies a {@link Type} as a runtime value of the Morel-level {@code
     * Type.t} datatype.
     */
    Object reifyType(Type type) {
      switch (type.op()) {
          // lint: sort until '#}' where '##case '
        case ID:
          // Primitive types: PrimitiveType.INT, REAL, BOOL, CHAR, STRING, UNIT.
          switch ((PrimitiveType) type) {
            case INT:
              return iof(TYPE_INT);
            case REAL:
              return iof(TYPE_REAL);
            case BOOL:
              return iof(TYPE_BOOL);
            case CHAR:
              return iof(TYPE_CHAR);
            case STRING:
              return iof(TYPE_STRING);
            case UNIT:
              return iof(TYPE_UNIT);
          }
          break;

        case LIST:
          return iof(TYPE_LIST, reifyType(((ListType) type).elementType));

        case FUNCTION_TYPE:
          final FnType ft = (FnType) type;
          return iof(
              TYPE_FN, of(reifyType(ft.paramType), reifyType(ft.resultType)));

        case TUPLE_TYPE:
          return iof(
              TYPE_TUPLE,
              transformEager(((TupleType) type).argTypes, this::reifyType));

        case RECORD_TYPE:
          return iof(
              TYPE_RECORD,
              transformEager(
                  ((RecordType) type).argNameTypes.entrySet(),
                  e -> of(e.getKey(), reifyType(e.getValue()))));

        case DATA_TYPE:
          DataType dt = (DataType) type;
          if (dt.name.equals("bag")) {
            return iof(TYPE_BAG, reifyType(dt.arguments.get(0)));
          }
          return iof(
              TYPE_DATA,
              of(dt.name, transformEager(dt.arguments, this::reifyType)));

        case TY_VAR:
          return iof(TYPE_VAR, ((TypeVar) type).ordinal);

        case FORALL_TYPE:
          // Unwrap the universal quantifier; the body's type variables
          // are reified as T_VAR with their ordinals.
          return reifyType(((ForallType) type).type);

        default:
          break;
      }
      throw new UnsupportedOperationException(
          "Plan.core: cannot yet reify type "
              + type
              + " ("
              + type.getClass().getSimpleName()
              + ")");
    }
  }

  /**
   * Verifies that a reified {@code Core.expr} value is in canonical form.
   * Currently checks one rule:
   *
   * <ul>
   *   <li>Unit values must be reified as {@code UNIT_LITERAL}, not as {@code
   *       TUPLE []} or {@code E_RECORD ([], _)}.
   * </ul>
   *
   * <p>Reify (via {@link #reifyExp}) always produces canonical output; this
   * method is a defensive check for hand-built or future-rule output.
   *
   * @throws AssertionError if any violation is found
   */
  public static void checkCanonical(Object expr) {
    CanonicalityChecker checker = new CanonicalityChecker();
    checker.check(expr);
    if (!checker.violations.isEmpty()) {
      StringBuilder buf = new StringBuilder("Plan canonicality violations:");
      for (String v : checker.violations) {
        buf.append('\n').append("  ").append(v);
      }
      throw new AssertionError(buf.toString());
    }
  }

  /**
   * Walks a reified {@code Core.expr} value tree, accumulating canonicality
   * violations.
   *
   * <p>Most reified nodes have the shape {@code [tag, payload]} (with {@code
   * tag} a string and {@code payload} either a primitive or a list of
   * children); the generic walk descends into anything that's a List, so per-
   * tag handling is needed only for nodes with extra checks. Currently that's
   * just {@code TUPLE} and {@code E_RECORD}, which can't be empty.
   */
  private static class CanonicalityChecker {
    final List<String> violations = new ArrayList<>();

    void check(Object node) {
      if (!(node instanceof List)) {
        return;
      }
      List<?> list = (List<?>) node;
      if (!list.isEmpty() && list.get(0) instanceof String) {
        switch ((String) list.get(0)) {
          case "E_RECORD":
            // ["E_RECORD", [[fields...], type]]
            if (list.size() == 2
                && ((List<?>) ((List<?>) list.get(1)).get(0)).isEmpty()) {
              violations.add("E_RECORD ([], _) should be UNIT_LITERAL");
            }
            break;
          case "TUPLE":
            // ["TUPLE", [args...]]
            if (list.size() == 2 && ((List<?>) list.get(1)).isEmpty()) {
              violations.add("TUPLE [] should be UNIT_LITERAL");
            }
            break;
          default:
            // No tag-specific rule.
            break;
        }
      }
      // Recurse into every element. Non-list elements (strings, numbers) are
      // skipped at the top of `check`. The recursion visits all sub-structure
      // uniformly without needing to know which positions of each constructor
      // hold sub-expressions vs. types vs. names.
      for (Object e : list) {
        check(e);
      }
    }
  }
}

// End Plans.java
