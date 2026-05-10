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
import static net.hydromatic.morel.util.Static.transformEager;

import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.type.DataType;
import net.hydromatic.morel.type.FnType;
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

  /** Reifies a Core expression as a {@code Core.expr} value. */
  public static Object reifyExp(Core.Exp exp) {
    switch (exp.op) {
      case INT_LITERAL:
        return of(
            CORE_EXPR_INT_LITERAL.constructor,
            ((Core.Literal) exp).unwrap(Integer.class));

      case UNIT_LITERAL:
        return of(CORE_EXPR_UNIT_LITERAL.constructor);

      case ID:
        Core.Id id = (Core.Id) exp;
        return of(
            CORE_EXPR_VAR.constructor, of(id.idPat.name, reifyType(id.type)));

      case FN_LITERAL:
        // A bare reference to a built-in (e.g. `op elem` resolved to a
        // FN_LITERAL): reify as VAR carrying the built-in's ML name.
        BuiltIn fnBi = ((Core.Literal) exp).unwrap(BuiltIn.class);
        return of(
            CORE_EXPR_VAR.constructor, of(fnBi.mlName, reifyType(exp.type)));

      case TUPLE:
        final Core.Tuple tup = (Core.Tuple) exp;
        // Canonicalize: an empty tuple or empty record is unit.
        if (tup.args.isEmpty()) {
          return of(CORE_EXPR_UNIT_LITERAL.constructor);
        }
        if (tup.type instanceof RecordType) {
          // Named-field record. argNameTypes iterates in canonical
          // sorted-by-name order, parallel to tup.args. Zip the names
          // with the reified args into a PairList in one pass.
          PairList<String, Object> nameReified =
              PairList.fromTransformed(
                  ((RecordType) tup.type).argNameTypes.keySet(),
                  tup.args,
                  (name, e, c) -> c.accept(name, reifyExp(e)));
          ImmutableList<Object> fields =
              nameReified.transformEager((name, reified) -> of(name, reified));
          return of(
              CORE_EXPR_RECORD.constructor, of(fields, reifyType(tup.type)));
        } else {
          return of(
              CORE_EXPR_TUPLE.constructor,
              transformEager(tup.args, Plans::reifyExp));
        }

      case APPLY:
        Core.Apply ap = (Core.Apply) exp;
        // Recognize `op +` (whether arrived as FN_LITERAL of Z_PLUS_INT/REAL
        // or as the Id "op +") and reify as PLUS.
        if (isOpPlus(ap.fn)) {
          List<Core.Exp> args = ((Core.Tuple) ap.arg).args;
          return of(
              CORE_EXPR_PLUS.constructor,
              of(
                  reifyExp(args.get(0)),
                  reifyExp(args.get(1)),
                  reifyType(ap.type)));
        }
        if (ap.fn.op == Op.FN_LITERAL) {
          BuiltIn b = ((Core.Literal) ap.fn).unwrap(BuiltIn.class);
          if (b == BuiltIn.Z_LIST) {
            return of(
                CORE_EXPR_LIST_LITERAL.constructor,
                of(
                    transformEager(((Core.Tuple) ap.arg).args, Plans::reifyExp),
                    reifyType(ap.type)));
          }
        }
        // Record-selector application becomes FIELD: (#name r) ->
        // FIELD(r, name, t).
        if (ap.fn.op == Op.RECORD_SELECTOR) {
          Core.RecordSelector sel = (Core.RecordSelector) ap.fn;
          return of(
              CORE_EXPR_FIELD.constructor,
              of(reifyExp(ap.arg), sel.fieldName(), reifyType(ap.type)));
        }
        // General application: APPLY(fn, arg, type).
        return of(
            CORE_EXPR_APPLY.constructor,
            of(reifyExp(ap.fn), reifyExp(ap.arg), reifyType(ap.type)));

      case FROM:
        return reifyFrom((Core.From) exp);

      default:
        throw new UnsupportedOperationException(
            "Plan.core: cannot yet reify " + exp.op + " (" + exp + ")");
    }
  }

  /**
   * Reifies a {@link Core.From} into a relational tree of FILTER / PROJECT /
   * VAR nodes. Each step reads the variable bound by the preceding scan via
   * {@code VAR "<name>"}; scoping is implicit (the bound variable is in scope
   * for steps that follow).
   */
  private static Object reifyFrom(Core.From from) {
    // An empty `from` (no steps) evaluates to the singleton `[()]` of type
    // `unit list`. Reify it as a LIST_LITERAL containing a single TUPLE [].
    Object current = EMPTY_FROM_EXPR;
    boolean haveScan = false;
    for (Core.FromStep step : from.steps) {
      switch (step.op) {
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
          current =
              of(
                  CORE_EXPR_FILTER.constructor,
                  of(current, reifyExp(where.exp)));
          break;
        case YIELD:
          Core.Yield yield = (Core.Yield) step;
          current =
              of(
                  CORE_EXPR_PROJECT.constructor,
                  of(current, reifyExp(yield.exp)));
          break;
        default:
          throw new UnsupportedOperationException(
              "Plan.core: from-step " + step.op + " not yet supported");
      }
    }
    return current;
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
    List<String> violations = new ArrayList<>();
    collectViolations(expr, violations);
    if (!violations.isEmpty()) {
      StringBuilder buf = new StringBuilder("Plan canonicality violations:");
      for (String v : violations) {
        buf.append('\n').append("  ").append(v);
      }
      throw new AssertionError(buf.toString());
    }
  }

  /** Walks a reified {@code Core.expr} value, accumulating violations. */
  private static void collectViolations(Object expr, List<String> out) {
    if (!(expr instanceof List)) {
      return;
    }
    List<?> list = (List<?>) expr;
    if (list.isEmpty() || !(list.get(0) instanceof String)) {
      return;
    }
    String tag = (String) list.get(0);
    switch (tag) {
      case "INT_LITERAL":
      case "UNIT_LITERAL":
      case "VAR":
        // No sub-expressions to check.
        break;
      case "TUPLE":
        List<?> tupArgs = (List<?>) list.get(1);
        if (tupArgs.isEmpty()) {
          out.add("TUPLE [] should be UNIT_LITERAL");
        } else {
          tupArgs.forEach(a -> collectViolations(a, out));
        }
        break;
      case "E_RECORD":
        List<?> recPair = (List<?>) list.get(1);
        List<?> fields = (List<?>) recPair.get(0);
        if (fields.isEmpty()) {
          out.add("E_RECORD ([], _) should be UNIT_LITERAL");
        } else {
          for (Object f : fields) {
            collectViolations(((List<?>) f).get(1), out);
          }
        }
        break;
      case "PLUS":
      case "APPLY":
        List<?> binArgs = (List<?>) list.get(1);
        collectViolations(binArgs.get(0), out);
        collectViolations(binArgs.get(1), out);
        break;
      case "FILTER":
      case "PROJECT":
        List<?> relArgs = (List<?>) list.get(1);
        collectViolations(relArgs.get(0), out);
        collectViolations(relArgs.get(1), out);
        break;
      case "FIELD":
        List<?> fieldArgs = (List<?>) list.get(1);
        collectViolations(fieldArgs.get(0), out);
        break;
      case "LIST_LITERAL":
        List<?> llPair = (List<?>) list.get(1);
        List<?> elems = (List<?>) llPair.get(0);
        elems.forEach(e -> collectViolations(e, out));
        break;
      default:
        // Unknown constructor: don't recurse. (Could be a type tag or a
        // future Core.expr constructor.)
        break;
    }
  }

  /**
   * Reifies a {@link Type} as a runtime value of the Morel-level {@code Type.t}
   * datatype.
   */
  static Object reifyType(Type type) {
    switch (type.op()) {
      case ID:
        // Primitive types: PrimitiveType.INT, REAL, BOOL, CHAR, STRING, UNIT.
        switch ((PrimitiveType) type) {
          case INT:
            return of(TYPE_INT.constructor);
          case REAL:
            return of(TYPE_REAL.constructor);
          case BOOL:
            return of(TYPE_BOOL.constructor);
          case CHAR:
            return of(TYPE_CHAR.constructor);
          case STRING:
            return of(TYPE_STRING.constructor);
          case UNIT:
            return of(TYPE_UNIT.constructor);
        }
        break;

      case LIST:
        return of(
            TYPE_LIST.constructor, reifyType(((ListType) type).elementType));

      case FUNCTION_TYPE:
        final FnType ft = (FnType) type;
        return of(
            TYPE_FN.constructor,
            of(reifyType(ft.paramType), reifyType(ft.resultType)));

      case TUPLE_TYPE:
        return of(
            TYPE_TUPLE.constructor,
            transformEager(((TupleType) type).argTypes, Plans::reifyType));

      case RECORD_TYPE:
        return of(
            TYPE_RECORD.constructor,
            transformEager(
                ((RecordType) type).argNameTypes.entrySet(),
                e -> of(e.getKey(), reifyType(e.getValue()))));

      case DATA_TYPE:
        DataType dt = (DataType) type;
        if (dt.name.equals("bag")) {
          return of(TYPE_BAG.constructor, reifyType(dt.arguments.get(0)));
        }
        return of(
            TYPE_DATA.constructor,
            of(dt.name, transformEager(dt.arguments, Plans::reifyType)));

      case TY_VAR:
        return of(TYPE_VAR.constructor, ((TypeVar) type).ordinal);

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

// End Plans.java
