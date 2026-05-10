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

import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.Map;
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
        return ImmutableList.of(
            BuiltIn.Constructor.CORE_EXPR_INT_LITERAL.constructor,
            ((Core.Literal) exp).unwrap(Integer.class));

      case ID:
        Core.Id id = (Core.Id) exp;
        return ImmutableList.of(
            BuiltIn.Constructor.CORE_EXPR_VAR.constructor,
            ImmutableList.of(id.idPat.name, reifyType(id.type)));

      case FN_LITERAL:
        // A bare reference to a built-in (e.g. `op elem` resolved to a
        // FN_LITERAL): reify as VAR carrying the built-in's ML name.
        BuiltIn fnBi = ((Core.Literal) exp).unwrap(BuiltIn.class);
        return ImmutableList.of(
            BuiltIn.Constructor.CORE_EXPR_VAR.constructor,
            ImmutableList.of(fnBi.mlName, reifyType(exp.type)));

      case TUPLE:
        Core.Tuple tup = (Core.Tuple) exp;
        if (tup.type instanceof RecordType
            && !(tup.type instanceof TupleType)) {
          // Named-field record.
          RecordType rt = (RecordType) tup.type;
          ImmutableList.Builder<Object> fields = ImmutableList.builder();
          int i = 0;
          for (String name : rt.argNameTypes.keySet()) {
            fields.add(ImmutableList.of(name, reifyExp(tup.args.get(i++))));
          }
          return ImmutableList.of(
              BuiltIn.Constructor.CORE_EXPR_RECORD.constructor,
              ImmutableList.of(fields.build(), reifyType(tup.type)));
        }
        ImmutableList.Builder<Object> tupArgs = ImmutableList.builder();
        for (Core.Exp e : tup.args) {
          tupArgs.add(reifyExp(e));
        }
        return ImmutableList.of(
            BuiltIn.Constructor.CORE_EXPR_TUPLE.constructor, tupArgs.build());

      case APPLY:
        Core.Apply ap = (Core.Apply) exp;
        // Recognize `op +` (whether arrived as FN_LITERAL of Z_PLUS_INT/REAL
        // or as the Id "op +") and reify as PLUS.
        if (isOpPlus(ap.fn)) {
          List<Core.Exp> args = ((Core.Tuple) ap.arg).args;
          return ImmutableList.of(
              BuiltIn.Constructor.CORE_EXPR_PLUS.constructor,
              ImmutableList.of(
                  reifyExp(args.get(0)),
                  reifyExp(args.get(1)),
                  reifyType(ap.type)));
        }
        if (ap.fn.op == Op.FN_LITERAL) {
          BuiltIn b = ((Core.Literal) ap.fn).unwrap(BuiltIn.class);
          if (b == BuiltIn.Z_LIST) {
            ImmutableList.Builder<Object> elems = ImmutableList.builder();
            for (Core.Exp e : ((Core.Tuple) ap.arg).args) {
              elems.add(reifyExp(e));
            }
            return ImmutableList.of(
                BuiltIn.Constructor.CORE_EXPR_LIST_LITERAL.constructor,
                ImmutableList.of(elems.build(), reifyType(ap.type)));
          }
        }
        // Record-selector application becomes FIELD: (#name r) ->
        // FIELD(r, name, t).
        if (ap.fn.op == Op.RECORD_SELECTOR) {
          Core.RecordSelector sel = (Core.RecordSelector) ap.fn;
          return ImmutableList.of(
              BuiltIn.Constructor.CORE_EXPR_FIELD.constructor,
              ImmutableList.of(
                  reifyExp(ap.arg), sel.fieldName(), reifyType(ap.type)));
        }
        // General application: APPLY(fn, arg, type).
        return ImmutableList.of(
            BuiltIn.Constructor.CORE_EXPR_APPLY.constructor,
            ImmutableList.of(
                reifyExp(ap.fn), reifyExp(ap.arg), reifyType(ap.type)));

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
    Object current = null;
    for (Core.FromStep step : from.steps) {
      switch (step.op) {
        case SCAN:
          Core.Scan scan = (Core.Scan) step;
          if (current == null) {
            current = reifyExp(scan.exp);
          } else {
            throw new UnsupportedOperationException(
                "Plan.core: multi-scan `from` not yet supported");
          }
          break;
        case WHERE:
          Core.Where where = (Core.Where) step;
          current =
              ImmutableList.of(
                  BuiltIn.Constructor.CORE_EXPR_FILTER.constructor,
                  ImmutableList.of(current, reifyExp(where.exp)));
          break;
        case YIELD:
          Core.Yield yield = (Core.Yield) step;
          current =
              ImmutableList.of(
                  BuiltIn.Constructor.CORE_EXPR_PROJECT.constructor,
                  ImmutableList.of(current, reifyExp(yield.exp)));
          break;
        default:
          throw new UnsupportedOperationException(
              "Plan.core: from-step " + step.op + " not yet supported");
      }
    }
    return current;
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
            return ImmutableList.of(BuiltIn.Constructor.TYPE_INT.constructor);
          case REAL:
            return ImmutableList.of(BuiltIn.Constructor.TYPE_REAL.constructor);
          case BOOL:
            return ImmutableList.of(BuiltIn.Constructor.TYPE_BOOL.constructor);
          case CHAR:
            return ImmutableList.of(BuiltIn.Constructor.TYPE_CHAR.constructor);
          case STRING:
            return ImmutableList.of(
                BuiltIn.Constructor.TYPE_STRING.constructor);
          case UNIT:
            return ImmutableList.of(BuiltIn.Constructor.TYPE_UNIT.constructor);
        }
        break;

      case LIST:
        return ImmutableList.of(
            BuiltIn.Constructor.TYPE_LIST.constructor,
            reifyType(((ListType) type).elementType));

      case FUNCTION_TYPE:
        FnType ft = (FnType) type;
        return ImmutableList.of(
            BuiltIn.Constructor.TYPE_FN.constructor,
            ImmutableList.of(
                reifyType(ft.paramType), reifyType(ft.resultType)));

      case TUPLE_TYPE:
        ImmutableList.Builder<Object> tupleArgs = ImmutableList.builder();
        for (Type t : ((TupleType) type).argTypes) {
          tupleArgs.add(reifyType(t));
        }
        return ImmutableList.of(
            BuiltIn.Constructor.TYPE_TUPLE.constructor, tupleArgs.build());

      case RECORD_TYPE:
        ImmutableList.Builder<Object> recordArgs = ImmutableList.builder();
        for (Map.Entry<String, Type> e :
            ((RecordType) type).argNameTypes.entrySet()) {
          recordArgs.add(ImmutableList.of(e.getKey(), reifyType(e.getValue())));
        }
        return ImmutableList.of(
            BuiltIn.Constructor.TYPE_RECORD.constructor, recordArgs.build());

      case DATA_TYPE:
        DataType dt = (DataType) type;
        if (dt.name.equals("bag")) {
          return ImmutableList.of(
              BuiltIn.Constructor.TYPE_BAG.constructor,
              reifyType(dt.arguments.get(0)));
        }
        ImmutableList.Builder<Object> dataArgs = ImmutableList.builder();
        for (Type t : dt.arguments) {
          dataArgs.add(reifyType(t));
        }
        return ImmutableList.of(
            BuiltIn.Constructor.TYPE_DATA.constructor,
            ImmutableList.of(dt.name, dataArgs.build()));

      case TY_VAR:
        return ImmutableList.of(
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
