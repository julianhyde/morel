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
import static net.hydromatic.morel.util.Pair.forEach;
import static net.hydromatic.morel.util.Static.transformEager;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
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
            BuiltIn.Constructor.CORE_EXPR_INT_LITERAL.constructor,
            ((Core.Literal) exp).unwrap(Integer.class));

      case ID:
        Core.Id id = (Core.Id) exp;
        return of(
            BuiltIn.Constructor.CORE_EXPR_VAR.constructor,
            of(id.idPat.name, reifyType(id.type)));

      case FN_LITERAL:
        // A bare reference to a built-in (e.g. `op elem` resolved to a
        // FN_LITERAL): reify as VAR carrying the built-in's ML name.
        BuiltIn fnBi = ((Core.Literal) exp).unwrap(BuiltIn.class);
        return of(
            BuiltIn.Constructor.CORE_EXPR_VAR.constructor,
            of(fnBi.mlName, reifyType(exp.type)));

      case TUPLE:
        final Core.Tuple tup = (Core.Tuple) exp;
        if (tup.type instanceof RecordType) {
          // Named-field record. argNameTypes iterates in canonical
          // sorted-by-name order, parallel to tup.args. Pair the names
          // with the args into a PairList and transform.
          ImmutableMap.Builder<String, Core.Exp> nameArg =
              ImmutableMap.builder();
          forEach(
              ((RecordType) tup.type).argNameTypes.keySet(),
              tup.args,
              nameArg::put);
          ImmutableList<Object> fields =
              PairList.viewOf(nameArg.build())
                  .transformEager((name, e) -> of(name, reifyExp(e)));
          return of(
              BuiltIn.Constructor.CORE_EXPR_RECORD.constructor,
              of(fields, reifyType(tup.type)));
        } else {
          return of(
              BuiltIn.Constructor.CORE_EXPR_TUPLE.constructor,
              transformEager(tup.args, Plans::reifyExp));
        }

      case APPLY:
        Core.Apply ap = (Core.Apply) exp;
        // Recognize `op +` (whether arrived as FN_LITERAL of Z_PLUS_INT/REAL
        // or as the Id "op +") and reify as PLUS.
        if (isOpPlus(ap.fn)) {
          List<Core.Exp> args = ((Core.Tuple) ap.arg).args;
          return of(
              BuiltIn.Constructor.CORE_EXPR_PLUS.constructor,
              of(
                  reifyExp(args.get(0)),
                  reifyExp(args.get(1)),
                  reifyType(ap.type)));
        }
        if (ap.fn.op == Op.FN_LITERAL) {
          BuiltIn b = ((Core.Literal) ap.fn).unwrap(BuiltIn.class);
          if (b == BuiltIn.Z_LIST) {
            return of(
                BuiltIn.Constructor.CORE_EXPR_LIST_LITERAL.constructor,
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
              BuiltIn.Constructor.CORE_EXPR_FIELD.constructor,
              of(reifyExp(ap.arg), sel.fieldName(), reifyType(ap.type)));
        }
        // General application: APPLY(fn, arg, type).
        return of(
            BuiltIn.Constructor.CORE_EXPR_APPLY.constructor,
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
    Object current = emptyFromExpr();
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
                  BuiltIn.Constructor.CORE_EXPR_FILTER.constructor,
                  of(current, reifyExp(where.exp)));
          break;
        case YIELD:
          Core.Yield yield = (Core.Yield) step;
          current =
              of(
                  BuiltIn.Constructor.CORE_EXPR_PROJECT.constructor,
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
   * Returns the reified Core for an empty {@code from} expression: a {@code
   * LIST_LITERAL} containing a single empty {@code TUPLE} (i.e. {@code [()]} of
   * type {@code unit list}).
   */
  private static Object emptyFromExpr() {
    Object unitTuple =
        of(BuiltIn.Constructor.CORE_EXPR_TUPLE.constructor, ImmutableList.of());
    Object unitListType =
        of(
            BuiltIn.Constructor.TYPE_LIST.constructor,
            of(BuiltIn.Constructor.TYPE_UNIT.constructor));
    return of(
        BuiltIn.Constructor.CORE_EXPR_LIST_LITERAL.constructor,
        of(ImmutableList.of(unitTuple), unitListType));
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
            return of(BuiltIn.Constructor.TYPE_INT.constructor);
          case REAL:
            return of(BuiltIn.Constructor.TYPE_REAL.constructor);
          case BOOL:
            return of(BuiltIn.Constructor.TYPE_BOOL.constructor);
          case CHAR:
            return of(BuiltIn.Constructor.TYPE_CHAR.constructor);
          case STRING:
            return of(BuiltIn.Constructor.TYPE_STRING.constructor);
          case UNIT:
            return of(BuiltIn.Constructor.TYPE_UNIT.constructor);
        }
        break;

      case LIST:
        return of(
            BuiltIn.Constructor.TYPE_LIST.constructor,
            reifyType(((ListType) type).elementType));

      case FUNCTION_TYPE:
        final FnType ft = (FnType) type;
        return of(
            BuiltIn.Constructor.TYPE_FN.constructor,
            of(reifyType(ft.paramType), reifyType(ft.resultType)));

      case TUPLE_TYPE:
        return of(
            BuiltIn.Constructor.TYPE_TUPLE.constructor,
            transformEager(((TupleType) type).argTypes, Plans::reifyType));

      case RECORD_TYPE:
        return of(
            BuiltIn.Constructor.TYPE_RECORD.constructor,
            transformEager(
                ((RecordType) type).argNameTypes.entrySet(),
                e -> of(e.getKey(), reifyType(e.getValue()))));

      case DATA_TYPE:
        DataType dt = (DataType) type;
        if (dt.name.equals("bag")) {
          return of(
              BuiltIn.Constructor.TYPE_BAG.constructor,
              reifyType(dt.arguments.get(0)));
        }
        return of(
            BuiltIn.Constructor.TYPE_DATA.constructor,
            of(dt.name, transformEager(dt.arguments, Plans::reifyType)));

      case TY_VAR:
        return of(
            BuiltIn.Constructor.TYPE_VAR.constructor, ((TypeVar) type).ordinal);

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
