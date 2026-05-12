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
import static net.hydromatic.morel.ast.CoreBuilder.core;
import static net.hydromatic.morel.compile.BuiltIn.Constructor.*;
import static net.hydromatic.morel.util.Static.transform;
import static net.hydromatic.morel.util.Static.transformEager;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.FromBuilder;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.compile.Environment;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.DataType;
import net.hydromatic.morel.type.FnType;
import net.hydromatic.morel.type.ForallType;
import net.hydromatic.morel.type.ListType;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.RecordLikeType;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.TupleType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.type.TypeVar;
import net.hydromatic.morel.util.PairList;
import org.checkerframework.checker.nullness.qual.Nullable;

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
   * Strips a trailing {@code $N} (where N is a non-empty digit run) from {@code
   * name}. Returns {@code name} unchanged if there is no such suffix.
   */
  private static String stripDollarN(String name) {
    int dollar = name.lastIndexOf('$');
    if (dollar <= 0 || dollar == name.length() - 1) {
      return name;
    }
    for (int i = dollar + 1; i < name.length(); i++) {
      if (!Character.isDigit(name.charAt(i))) {
        return name;
      }
    }
    return name.substring(0, dollar);
  }

  /**
   * Dedicated reify forms for common Morel operators. Each kind has a dedicated
   * {@code Core.expr} constructor (e.g. {@code PLUS}, {@code AND}) rather than
   * reifying as a generic {@code APPLY (VAR "op X", ...)}.
   */
  private enum OpKind {
    PLUS,
    MINUS,
    TIMES,
    DIVIDE,
    DIV,
    MOD,
    NEG,
    EQUALS,
    NOT_EQUALS,
    LT,
    LE,
    GT,
    GE,
    ELEM,
    NOT_ELEM,
    AND,
    OR,
    NOT,
    CONS,
    AT,
    CONCAT
  }

  /** Returns the {@link OpKind} of a function reference, or null. */
  private static @Nullable OpKind opKindOf(Core.Exp fn) {
    if (fn.op == Op.FN_LITERAL) {
      BuiltIn b = ((Core.Literal) fn).unwrap(BuiltIn.class);
      switch (b) {
          // lint: sort until '#}' where '##case '
        case BOOL_NOT:
        case NOT:
          return OpKind.NOT;
        case OP_CARET:
          return OpKind.CONCAT;
        case OP_CONS:
          return OpKind.CONS;
        case OP_DIV:
          return OpKind.DIV;
        case OP_ELEM:
          return OpKind.ELEM;
        case OP_EQ:
          return OpKind.EQUALS;
        case OP_GE:
          return OpKind.GE;
        case OP_GT:
          return OpKind.GT;
        case OP_LE:
          return OpKind.LE;
        case OP_LT:
          return OpKind.LT;
        case OP_MINUS:
        case Z_MINUS_INT:
        case Z_MINUS_REAL:
          return OpKind.MINUS;
        case OP_MOD:
          return OpKind.MOD;
        case OP_NE:
          return OpKind.NOT_EQUALS;
        case OP_NEGATE:
        case Z_NEGATE_INT:
        case Z_NEGATE_REAL:
          return OpKind.NEG;
        case OP_NOT_ELEM:
          return OpKind.NOT_ELEM;
        case OP_PLUS:
        case Z_PLUS_INT:
        case Z_PLUS_REAL:
          return OpKind.PLUS;
        case OP_TIMES:
        case Z_TIMES_INT:
        case Z_TIMES_REAL:
          return OpKind.TIMES;
        case REAL_DIVIDE:
          return OpKind.DIVIDE;
        default: // lint:skip 1 "alphabetical region ends here"
          return null;
      }
    }
    if (fn.op == Op.ID) {
      // Strip the overload `$N` suffix before matching, so e.g.
      // "op elem$1" matches the "op elem" case.
      switch (stripDollarN(((Core.Id) fn).idPat.name)) {
          // lint: sort until '#}' where '##case '
        case "not":
          return OpKind.NOT;
        case "op *":
          return OpKind.TIMES;
        case "op +":
          return OpKind.PLUS;
        case "op -":
          return OpKind.MINUS;
        case "op ::":
          return OpKind.CONS;
        case "op <":
          return OpKind.LT;
        case "op <=":
          return OpKind.LE;
        case "op <>":
          return OpKind.NOT_EQUALS;
        case "op =":
          return OpKind.EQUALS;
        case "op >":
          return OpKind.GT;
        case "op >=":
          return OpKind.GE;
        case "op @":
          return OpKind.AT;
        case "op ^":
          return OpKind.CONCAT;
        case "op div":
          return OpKind.DIV;
        case "op elem":
          return OpKind.ELEM;
        case "op mod":
          return OpKind.MOD;
        case "op notelem":
          return OpKind.NOT_ELEM;
        case "op ~":
          return OpKind.NEG;
        case "op /":
          return OpKind.DIVIDE;
        default: // lint:skip 1 "alphabetical region ends here"
          return null;
      }
    }
    return null;
  }

  /**
   * Builds and interns a unary-constructor list (helper for OpKind dispatch).
   */
  private static BuiltIn.@Nullable Constructor binaryOpCon(OpKind k) {
    switch (k) {
        // lint: sort until '#}' where '##case '
      case AT:
        return CORE_EXPR_AT;
      case CONS:
        return CORE_EXPR_CONS;
      case DIV:
        return CORE_EXPR_DIV;
      case DIVIDE:
        return CORE_EXPR_DIVIDE;
      case MINUS:
        return CORE_EXPR_MINUS;
      case MOD:
        return CORE_EXPR_MOD;
      case PLUS:
        return CORE_EXPR_PLUS;
      case TIMES:
        return CORE_EXPR_TIMES;
      default: // lint:skip 1 "alphabetical region ends here"
        return null;
    }
  }

  /**
   * Untyped binary operators (result is bool or string): kind -> constructor.
   */
  private static BuiltIn.@Nullable Constructor binaryUntypedOpCon(OpKind k) {
    switch (k) {
        // lint: sort until '#}' where '##case '
      case CONCAT:
        return CORE_EXPR_CONCAT;
      case ELEM:
        return CORE_EXPR_ELEM;
      case EQUALS:
        return CORE_EXPR_EQUALS;
      case GE:
        return CORE_EXPR_GE;
      case GT:
        return CORE_EXPR_GT;
      case LE:
        return CORE_EXPR_LE;
      case LT:
        return CORE_EXPR_LT;
      case NOT_ELEM:
        return CORE_EXPR_NOT_ELEM;
      case NOT_EQUALS:
        return CORE_EXPR_NOT_EQUALS;
      default: // lint:skip 1 "alphabetical region ends here"
        return null;
    }
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
          // Recognize the `bool` datatype's constructors as literals.
          if (id.type == PrimitiveType.BOOL) {
            if (id.idPat.name.equals("true")) {
              return iof(CORE_EXPR_BOOL_LITERAL, true);
            }
            if (id.idPat.name.equals("false")) {
              return iof(CORE_EXPR_BOOL_LITERAL, false);
            }
          }
          // Strip a trailing "$N" overload-resolution suffix (e.g.
          // "op elem$1" -> "op elem"). That suffix is an internal
          // disambiguator and varies across resolutions. Do NOT strip
          // mid-string dollars on internally-generated names like "$col".
          return iof(
              CORE_EXPR_VAR,
              of(stripDollarN(id.idPat.name), reifyType(id.type)));

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
          return reifyApply((Core.Apply) exp);

        case FROM:
          return reifyFrom((Core.From) exp);

        case CASE:
          Core.Case caseExp = (Core.Case) exp;
          ImmutableList.Builder<Object> matches = ImmutableList.builder();
          for (Core.Match m : caseExp.matchList) {
            matches.add(of(reifyPat(m.pat), reifyExp(m.exp)));
          }
          return iof(
              CORE_EXPR_CASE,
              of(reifyExp(caseExp.exp), matches.build(), reifyType(exp.type)));

        case FN:
          Core.Fn fn = (Core.Fn) exp;
          return iof(
              CORE_EXPR_FN,
              of(reifyPat(fn.idPat), reifyExp(fn.exp), reifyType(exp.type)));

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
     * Reifies a {@link Core.Pat} as a {@code Core.pat} value. Mirrors {@link
     * Core.Pat}'s constructor hierarchy.
     */
    Object reifyPat(Core.Pat pat) {
      switch (pat.op) {
        case ID_PAT:
          Core.IdPat idPat = (Core.IdPat) pat;
          return iof(CORE_PAT_P_VAR, of(idPat.name, reifyType(idPat.type)));

        case WILDCARD_PAT:
          return iof(CORE_PAT_P_WILD, reifyType(pat.type));

        case BOOL_LITERAL_PAT:
          return iof(CORE_PAT_P_BOOL_LIT, ((Core.LiteralPat) pat).value);
        case CHAR_LITERAL_PAT:
          return iof(CORE_PAT_P_CHAR_LIT, ((Core.LiteralPat) pat).value);
        case INT_LITERAL_PAT:
          return iof(
              CORE_PAT_P_INT_LIT,
              ((Number) ((Core.LiteralPat) pat).value).intValue());
        case REAL_LITERAL_PAT:
          return iof(
              CORE_PAT_P_REAL_LIT,
              ((Number) ((Core.LiteralPat) pat).value).floatValue());
        case STRING_LITERAL_PAT:
          return iof(CORE_PAT_P_STRING_LIT, ((Core.LiteralPat) pat).value);

        case CON_PAT:
          Core.ConPat conPat = (Core.ConPat) pat;
          return iof(
              CORE_PAT_P_CON,
              of(conPat.tyCon, reifyPat(conPat.pat), reifyType(pat.type)));

        case CON0_PAT:
          Core.Con0Pat con0Pat = (Core.Con0Pat) pat;
          return iof(CORE_PAT_P_CON0, of(con0Pat.tyCon, reifyType(pat.type)));

        case CONS_PAT:
          // ConsPat: argument is a TuplePat [head, tail].
          Core.ConPat consPat = (Core.ConPat) pat;
          Core.TuplePat consArg = (Core.TuplePat) consPat.pat;
          return iof(
              CORE_PAT_P_CONS,
              of(
                  reifyPat(consArg.args.get(0)),
                  reifyPat(consArg.args.get(1)),
                  reifyType(pat.type)));

        case TUPLE_PAT:
          Core.TuplePat tuplePat = (Core.TuplePat) pat;
          return iof(
              CORE_PAT_P_TUPLE, transformEager(tuplePat.args, this::reifyPat));

        case RECORD_PAT:
          Core.RecordPat recordPat = (Core.RecordPat) pat;
          // Pair field names (in canonical sorted order) with sub-patterns.
          PairList<String, Object> recFields =
              PairList.fromZip(
                  recordPat.type().argNameTypes.keySet(),
                  transform(recordPat.args, this::reifyPat));
          return iof(
              CORE_PAT_P_RECORD, recFields.transformEager(ImmutableList::of));

        case LIST_PAT:
          Core.ListPat listPat = (Core.ListPat) pat;
          return iof(
              CORE_PAT_P_LIST,
              of(
                  transformEager(listPat.args, this::reifyPat),
                  reifyType(pat.type)));

        case AS_PAT:
          Core.AsPat asPat = (Core.AsPat) pat;
          return iof(
              CORE_PAT_P_AS,
              of(asPat.name, reifyPat(asPat.pat), reifyType(pat.type)));

        default:
          throw new UnsupportedOperationException(
              "Plan.core: cannot yet reify pat " + pat.op + " (" + pat + ")");
      }
    }

    /**
     * Reifies an {@code APPLY}, recognizing operator forms (PLUS, EQUALS, AND,
     * etc.), the {@code Z_LIST} sugar, and record selectors as dedicated {@code
     * Core.expr} constructors. Falls through to a plain {@code APPLY} for
     * general function application.
     */
    Object reifyApply(Core.Apply ap) {
      OpKind kind = opKindOf(ap.fn);
      if (kind != null) {
        return reifyOpApply(ap, kind);
      }
      if (ap.fn.op == Op.FN_LITERAL) {
        BuiltIn b = ((Core.Literal) ap.fn).unwrap(BuiltIn.class);
        if (b == BuiltIn.Z_LIST) {
          return iof(
              CORE_EXPR_LIST_LITERAL,
              of(
                  transformEager(((Core.Tuple) ap.arg).args, this::reifyExp),
                  reifyType(ap.type)));
        }
        if (b == BuiltIn.Z_ANDALSO) {
          return reifyAndOr(ap, CORE_EXPR_AND);
        }
        if (b == BuiltIn.Z_ORELSE) {
          return reifyAndOr(ap, CORE_EXPR_OR);
        }
      }
      if (ap.fn.op == Op.RECORD_SELECTOR) {
        Core.RecordSelector sel = (Core.RecordSelector) ap.fn;
        return iof(
            CORE_EXPR_FIELD,
            of(reifyExp(ap.arg), sel.fieldName(), reifyType(ap.type)));
      }
      return iof(
          CORE_EXPR_APPLY,
          of(reifyExp(ap.fn), reifyExp(ap.arg), reifyType(ap.type)));
    }

    /** Reifies a binary/unary operator application. */
    Object reifyOpApply(Core.Apply ap, OpKind kind) {
      if (kind == OpKind.NEG) {
        // Unary: APPLY(~, x) -> NEG(x, type).
        return iof(CORE_EXPR_NEG, of(reifyExp(ap.arg), reifyType(ap.type)));
      }
      if (kind == OpKind.NOT) {
        // Unary: APPLY(not, x) -> NOT(x).
        return iof(CORE_EXPR_NOT, reifyExp(ap.arg));
      }
      // Binary: arg is a tuple (lhs, rhs).
      List<Core.Exp> args = ((Core.Tuple) ap.arg).args;
      Object lhs = reifyExp(args.get(0));
      Object rhs = reifyExp(args.get(1));
      BuiltIn.Constructor typedCon = binaryOpCon(kind);
      if (typedCon != null) {
        return iof(typedCon, of(lhs, rhs, reifyType(ap.type)));
      }
      BuiltIn.Constructor untypedCon = binaryUntypedOpCon(kind);
      if (untypedCon != null) {
        return iof(untypedCon, of(lhs, rhs));
      }
      throw new AssertionError("unmapped op kind " + kind);
    }

    /**
     * Reifies a binary {@code andalso} / {@code orelse} application as an n-ary
     * {@code AND} / {@code OR} node, flattening any nested same-kind
     * applications into a single list.
     */
    Object reifyAndOr(Core.Apply ap, BuiltIn.Constructor con) {
      ImmutableList.Builder<Object> args = ImmutableList.builder();
      collectAndOr(ap, con, args);
      return iof(con, args.build());
    }

    private void collectAndOr(
        Core.Exp exp,
        BuiltIn.Constructor con,
        ImmutableList.Builder<Object> out) {
      if (exp.op == Op.APPLY) {
        Core.Apply a = (Core.Apply) exp;
        if (a.fn.op == Op.FN_LITERAL) {
          BuiltIn b = ((Core.Literal) a.fn).unwrap(BuiltIn.class);
          boolean match =
              con == CORE_EXPR_AND && b == BuiltIn.Z_ANDALSO
                  || con == CORE_EXPR_OR && b == BuiltIn.Z_ORELSE;
          if (match) {
            List<Core.Exp> args = ((Core.Tuple) a.arg).args;
            collectAndOr(args.get(0), con, out);
            collectAndOr(args.get(1), con, out);
            return;
          }
        }
      }
      out.add(reifyExp(exp));
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
        bindings = of(of(reifyPat(nrd.pat), reifyExp(nrd.exp)));
      } else {
        // RecValDecl: multiple mutually-recursive bindings.
        Core.RecValDecl rvd = (Core.RecValDecl) decl;
        bindings =
            transformEager(
                rvd.list, nrd -> of(reifyPat(nrd.pat), reifyExp(nrd.exp)));
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
              // Subsequent scans become JOINs with the existing tree. The
              // join condition defaults to BOOL_LITERAL true (Cartesian
              // product); a scan with an explicit `on` condition reifies
              // that condition.
              Object joinCond =
                  scan.condition != null
                      ? reifyExp(scan.condition)
                      : iof(CORE_EXPR_BOOL_LITERAL, true);
              current =
                  iof(
                      CORE_EXPR_JOIN,
                      of(current, reifyExp(scan.exp), joinCond));
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
                (k, agg) -> {
                  Object argOpt =
                      agg.argument == null
                          ? of("NONE")
                          : of("SOME", reifyExp(agg.argument));
                  aggs.add(of(k.name, reifyExp(agg.aggregate), argOpt));
                });
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
   * Inverse of {@link #reifyExp}: converts a runtime {@code Core.expr} value
   * (nested list/tuple form) back to a typed {@link Core.Exp}.
   *
   * <p>{@code Plan.transform} calls this on the output of a user-supplied
   * rewriter. The resulting {@link Core.Exp} is then compiled and evaluated
   * with the session's type system and environment to produce a new value of
   * the same Morel type.
   *
   * <p>Variable names are resolved against {@code env}: a reified {@code VAR
   * "n"} becomes a {@link Core.Id} pointing at the binding for {@code n}.
   * Failing such a lookup is fatal — {@code Plan.transform} requires the
   * transformed expression to be well-typed in the current environment.
   */
  public static Core.Exp unreifyExp(
      Object value, TypeSystem typeSystem, Environment env) {
    return new Unreifier(typeSystem, env).unreifyExp(value);
  }

  /**
   * Callback that evaluates a Morel rule (a {@code Core.expr -> Core.expr
   * option} function value) against a reified {@code Core.expr}, returning the
   * {@code option}-shaped result.
   */
  public interface RuleEvaluator {
    /** Returns {@code ["NONE"]} or {@code ["SOME", newReified]}. */
    Object evaluate(Object rule, Object reifiedExpr);
  }

  /** Names of all {@code Core.expr} constructors; populated lazily. */
  private static final Set<String> CORE_EXPR_TAGS = computeCoreExprTags();

  private static Set<String> computeCoreExprTags() {
    ImmutableList.Builder<String> b = ImmutableList.builder();
    for (BuiltIn.Constructor c : BuiltIn.Constructor.values()) {
      if (c.datatype == BuiltIn.Datatype.CORE_EXPR) {
        b.add(c.constructor);
      }
    }
    return ImmutableSet.copyOf(b.build());
  }

  /**
   * Walks a reified {@code Core.expr} value bottom-up. At every {@code
   * Core.expr} node (identified by a tag in {@link #CORE_EXPR_TAGS}), tries
   * each rule via {@code evaluator}. If a rule returns {@code SOME e'}, the
   * node is replaced and rules are retried until none fire (fixpoint per node).
   * Non-{@code Core.expr} payloads (types, pats, record field names) are
   * skipped.
   */
  public static Object walkAndApplyRules(
      Object reified, List<?> rules, RuleEvaluator evaluator) {
    return walkAndApply(reified, rules, evaluator);
  }

  private static Object walkAndApply(
      Object node, List<?> rules, RuleEvaluator evaluator) {
    if (!(node instanceof List)) {
      return node;
    }
    List<?> list = (List<?>) node;
    if (list.isEmpty()) {
      return node;
    }
    // Recurse into children.
    ImmutableList.Builder<Object> b = ImmutableList.builder();
    boolean changed = false;
    for (Object child : list) {
      Object newChild = walkAndApply(child, rules, evaluator);
      if (newChild != child) {
        changed = true;
      }
      b.add(newChild);
    }
    Object current = changed ? b.build() : node;
    // If this is a Core.expr node, try rules.
    if (CORE_EXPR_TAGS.contains(((List<?>) current).get(0))) {
      return applyRulesAtNode(current, rules, evaluator);
    }
    return current;
  }

  private static Object applyRulesAtNode(
      Object node, List<?> rules, RuleEvaluator evaluator) {
    while (true) {
      boolean changed = false;
      for (Object rule : rules) {
        Object result = evaluator.evaluate(rule, node);
        List<?> resultList = (List<?>) result;
        if ("SOME".equals(resultList.get(0))) {
          node = resultList.get(1);
          changed = true;
          break;
        }
      }
      if (!changed) {
        return node;
      }
    }
  }

  /**
   * Walker that converts reified {@code Core.expr} / {@code pat} / {@code t}
   * values back to their Java counterparts. Uses the session's {@link
   * TypeSystem} for type constructors and the compile-time {@link Environment}
   * for variable lookups.
   *
   * <p>Maintains a stack of local scopes for variables introduced during
   * unreification (e.g. by {@code FN}, {@code LET}, {@code CASE} branches, or
   * from-step scans). Innermost scope wins; if not found locally, falls back to
   * {@code env}.
   */
  private static class Unreifier {
    final TypeSystem ts;
    final Environment env;
    final ArrayDeque<Map<String, Core.NamedPat>> localBindings =
        new ArrayDeque<>();

    Unreifier(TypeSystem ts, Environment env) {
      this.ts = ts;
      this.env = env;
    }

    private void pushScope() {
      localBindings.addFirst(new HashMap<>());
    }

    private void popScope() {
      localBindings.removeFirst();
    }

    private void bindLocal(Core.NamedPat namedPat) {
      if (!namedPat.name.equals("_")) {
        localBindings.peekFirst().put(namedPat.name, namedPat);
      }
    }

    /**
     * Bind every IdPat/AsPat contained in {@code pat} into the current scope.
     */
    private void bindPatVars(Core.Pat pat) {
      if (pat instanceof Core.IdPat) {
        bindLocal((Core.IdPat) pat);
      } else if (pat instanceof Core.AsPat) {
        Core.AsPat asPat = (Core.AsPat) pat;
        bindLocal(asPat);
        bindPatVars(asPat.pat);
      } else if (pat instanceof Core.TuplePat) {
        for (Core.Pat sub : ((Core.TuplePat) pat).args) {
          bindPatVars(sub);
        }
      } else if (pat instanceof Core.RecordPat) {
        for (Core.Pat sub : ((Core.RecordPat) pat).args) {
          bindPatVars(sub);
        }
      } else if (pat instanceof Core.ListPat) {
        for (Core.Pat sub : ((Core.ListPat) pat).args) {
          bindPatVars(sub);
        }
      } else if (pat instanceof Core.ConPat) {
        bindPatVars(((Core.ConPat) pat).pat);
      }
      // Wildcard, Con0, Literal patterns introduce no bindings.
    }

    Core.Exp unreifyExp(Object value) {
      List<?> list = asList(value);
      String tag = (String) list.get(0);
      Object payload = list.size() > 1 ? list.get(1) : null;
      switch (tag) {
          // lint: sort until '#}' where '##case '
        case "APPLY":
          {
            List<?> p = asList(payload);
            Core.Exp fn = unreifyExp(p.get(0));
            Core.Exp arg = unreifyExp(p.get(1));
            Type type = unreifyType(p.get(2));
            return core.apply(Pos.ZERO, type, fn, arg);
          }

        case "BOOL_LITERAL":
          return core.boolLiteral((Boolean) payload);

        case "CASE":
          return unreifyCase(payload);

        case "CHAR_LITERAL":
          return core.charLiteral((Character) payload);

        case "E_RECORD":
          return unreifyRecord(payload);

        case "EXCEPT":
        case "FILTER":
        case "GROUP":
        case "INTERSECT":
        case "JOIN":
        case "ORDER":
        case "PROJECT":
        case "SKIP":
        case "TAKE":
        case "UNION":
        case "UNORDER":
          return unreifyFromChain(value);

        case "FIELD":
          return unreifyField(payload);

        case "FN":
          return unreifyFn(payload);

        case "INT_LITERAL":
          return core.intLiteral(toBigDecimal(payload));

        case "LET":
          return unreifyLet(payload);

        case "LIST_LITERAL":
          {
            List<?> p = asList(payload);
            Type type = unreifyType(p.get(1));
            List<Core.Exp> es =
                transformEager(asList(p.get(0)), this::unreifyExp);
            // Build via APPLY of the Z_LIST built-in over a TUPLE.
            Type elemType = ((ListType) type).elementType;
            FnType fnType =
                ts.fnType(ts.tupleType(repeat(elemType, es.size())), type);
            Core.Literal listFn = core.functionLiteral(fnType, BuiltIn.Z_LIST);
            Core.Exp argTuple =
                es.isEmpty()
                    ? core.unitLiteral()
                    : core.tuple(ts, es.toArray(new Core.Exp[0]));
            return core.apply(Pos.ZERO, type, listFn, argTuple);
          }

        case "AND":
          return unreifyAndOr(payload, BuiltIn.Z_ANDALSO);
        case "AT":
          return unreifyTypedBinary(payload, "op @");
        case "CONCAT":
          return unreifyUntypedBinary(payload, "op ^", PrimitiveType.STRING);
        case "CONS":
          return unreifyTypedBinary(payload, "op ::");
        case "DIV":
          return unreifyTypedBinary(payload, "op div");
        case "DIVIDE":
          return unreifyTypedBinary(payload, "op /");
        case "ELEM":
          return unreifyUntypedBinary(payload, "op elem", PrimitiveType.BOOL);
        case "EQUALS":
          return unreifyUntypedBinary(payload, "op =", PrimitiveType.BOOL);
        case "GE":
          return unreifyUntypedBinary(payload, "op >=", PrimitiveType.BOOL);
        case "GT":
          return unreifyUntypedBinary(payload, "op >", PrimitiveType.BOOL);
        case "LE":
          return unreifyUntypedBinary(payload, "op <=", PrimitiveType.BOOL);
        case "LT":
          return unreifyUntypedBinary(payload, "op <", PrimitiveType.BOOL);
        case "MINUS":
          return unreifyTypedBinary(payload, "op -");
        case "MOD":
          return unreifyTypedBinary(payload, "op mod");
        case "NEG":
          {
            List<?> p = asList(payload);
            Core.Exp opArg = unreifyExp(p.get(0));
            Type opType = unreifyType(p.get(1));
            return core.apply(Pos.ZERO, opType, resolveId("op ~"), opArg);
          }
        case "NOT":
          return core.apply(
              Pos.ZERO,
              PrimitiveType.BOOL,
              resolveId("not"),
              unreifyExp(payload));
        case "NOT_ELEM":
          return unreifyUntypedBinary(
              payload, "op notelem", PrimitiveType.BOOL);
        case "NOT_EQUALS":
          return unreifyUntypedBinary(payload, "op <>", PrimitiveType.BOOL);
        case "OR":
          return unreifyAndOr(payload, BuiltIn.Z_ORELSE);
        case "PLUS":
          return unreifyTypedBinary(payload, "op +");

        case "RAISE":
          {
            List<?> p = asList(payload);
            Core.Exp raiseExp = unreifyExp(p.get(0));
            Type raiseType = unreifyType(p.get(1));
            return core.raise(Pos.ZERO, raiseType, raiseExp);
          }

        case "REAL_LITERAL":
          return core.realLiteral((Float) payload);

        case "STRING_LITERAL":
          return core.stringLiteral((String) payload);

        case "TIMES":
          return unreifyTypedBinary(payload, "op *");

        case "TUPLE":
          {
            List<Core.Exp> es =
                transformEager(asList(payload), this::unreifyExp);
            return core.tuple(ts, es.toArray(new Core.Exp[0]));
          }

        case "UNIT_LITERAL":
          return core.unitLiteral();

        case "VAR":
          {
            List<?> p = asList(payload);
            String name = (String) p.get(0);
            return resolveId(name);
          }

        default: // lint:skip 1 "alphabetical region ends here"
          throw new UnsupportedOperationException(
              "Plan.transform: cannot yet unreify " + tag);
      }
    }

    /**
     * Unreifies a binary operator payload {@code [lhs, rhs, type]} into {@code
     * APPLY(op, (lhs, rhs))}.
     */
    private Core.Exp unreifyTypedBinary(Object payload, String opName) {
      List<?> p = asList(payload);
      Core.Exp lhs = unreifyExp(p.get(0));
      Core.Exp rhs = unreifyExp(p.get(1));
      Type type = unreifyType(p.get(2));
      return core.apply(
          Pos.ZERO, type, resolveId(opName), core.tuple(ts, lhs, rhs));
    }

    /**
     * Unreifies a binary operator payload {@code [lhs, rhs]} into {@code
     * APPLY(op, (lhs, rhs))} with a fixed result type.
     */
    private Core.Exp unreifyUntypedBinary(
        Object payload, String opName, Type resultType) {
      List<?> p = asList(payload);
      Core.Exp lhs = unreifyExp(p.get(0));
      Core.Exp rhs = unreifyExp(p.get(1));
      return core.apply(
          Pos.ZERO, resultType, resolveId(opName), core.tuple(ts, lhs, rhs));
    }

    /**
     * Unreifies an n-ary {@code AND}/{@code OR} payload (a list of exprs) into
     * a right-leaning binary chain of {@code andalso}/{@code orelse}.
     */
    private Core.Exp unreifyAndOr(Object payload, BuiltIn fn) {
      List<?> args = asList(payload);
      if (args.isEmpty()) {
        return fn == BuiltIn.Z_ANDALSO
            ? core.boolLiteral(true)
            : core.boolLiteral(false);
      }
      List<Core.Exp> es = transformEager(args, this::unreifyExp);
      Core.Exp result = es.get(es.size() - 1);
      Core.Literal opFn = core.functionLiteral(ts, fn);
      for (int i = es.size() - 2; i >= 0; i--) {
        result =
            core.apply(
                Pos.ZERO,
                PrimitiveType.BOOL,
                opFn,
                core.tuple(ts, es.get(i), result));
      }
      return result;
    }

    /** Unreifies a {@code CASE} payload {@code [scrut, matches, type]}. */
    private Core.Exp unreifyCase(Object payload) {
      List<?> p = asList(payload);
      Core.Exp scrut = unreifyExp(p.get(0));
      List<?> matches = asList(p.get(1));
      Type caseType = unreifyType(p.get(2));
      List<Core.Match> ml = new ArrayList<>(matches.size());
      for (Object m : matches) {
        List<?> mp = asList(m);
        pushScope();
        try {
          Core.Pat matchPat = unreifyPat(mp.get(0));
          bindPatVars(matchPat);
          Core.Exp matchExp = unreifyExp(mp.get(1));
          ml.add(core.match(Pos.ZERO, matchPat, matchExp));
        } finally {
          popScope();
        }
      }
      return core.caseOf(Pos.ZERO, caseType, scrut, ml);
    }

    /** Unreifies an {@code E_RECORD} payload {@code [fields, type]}. */
    private Core.Exp unreifyRecord(Object payload) {
      List<?> p = asList(payload);
      RecordType recType = (RecordType) unreifyType(p.get(1));
      // Fields are reified in canonical sorted-by-name order; preserve that.
      List<Core.Exp> es =
          transformEager(asList(p.get(0)), f -> unreifyExp(asList(f).get(1)));
      return core.tuple(recType, es.toArray(new Core.Exp[0]));
    }

    /** Unreifies a {@code FIELD} payload {@code [record, name, type]}. */
    private Core.Exp unreifyField(Object payload) {
      List<?> p = asList(payload);
      Core.Exp rec = unreifyExp(p.get(0));
      String fieldName = (String) p.get(1);
      Type fieldType = unreifyType(p.get(2));
      RecordLikeType recType = (RecordLikeType) rec.type;
      Core.RecordSelector sel = core.recordSelector(ts, recType, fieldName);
      return core.apply(Pos.ZERO, fieldType, sel, rec);
    }

    /** Unreifies a {@code FN} payload {@code [pat, body, type]}. */
    private Core.Exp unreifyFn(Object payload) {
      List<?> p = asList(payload);
      Type fnType = unreifyType(p.get(2));
      pushScope();
      try {
        Core.Pat fnPat = unreifyPat(p.get(0));
        if (!(fnPat instanceof Core.IdPat)) {
          throw new IllegalStateException(
              "FN's pat must be P_VAR; got " + fnPat);
        }
        Core.IdPat idPat = (Core.IdPat) fnPat;
        bindLocal(idPat);
        Core.Exp body = unreifyExp(p.get(1));
        return core.fn((FnType) fnType, idPat, body);
      } finally {
        popScope();
      }
    }

    /** Unreifies a {@code LET} payload {@code [bindings, body]}. */
    private Core.Exp unreifyLet(Object payload) {
      List<?> p = asList(payload);
      List<?> bindings = asList(p.get(0));
      pushScope();
      try {
        if (bindings.size() == 1) {
          // Non-recursive: exp evaluated in outer scope; pat in body only.
          List<?> binding = asList(bindings.get(0));
          Core.Pat pat = unreifyPat(binding.get(0));
          Core.Exp exp = unreifyExp(binding.get(1));
          bindPatVars(pat);
          Core.Exp body = unreifyExp(p.get(1));
          Core.NonRecValDecl decl =
              core.nonRecValDecl(Pos.ZERO, (Core.NamedPat) pat, null, exp);
          return core.let(decl, body);
        }
        // Recursive: all pats in scope of all exps and body.
        List<Core.Pat> pats = new ArrayList<>(bindings.size());
        for (Object b : bindings) {
          Core.Pat pat = unreifyPat(asList(b).get(0));
          pats.add(pat);
          bindPatVars(pat);
        }
        List<Core.NonRecValDecl> decls = new ArrayList<>();
        for (int i = 0; i < bindings.size(); i++) {
          Core.Exp exp = unreifyExp(asList(bindings.get(i)).get(1));
          decls.add(
              core.nonRecValDecl(
                  Pos.ZERO, (Core.NamedPat) pats.get(i), null, exp));
        }
        Core.Exp body = unreifyExp(p.get(1));
        return core.let(core.recValDecl(decls), body);
      } finally {
        popScope();
      }
    }

    /**
     * Tags that are from-chain steps (their first payload element is a
     * recursive "previous chain").
     */
    private static boolean isFromStepTag(Object tag) {
      if (!(tag instanceof String)) {
        return false;
      }
      switch ((String) tag) {
        case "EXCEPT":
        case "FILTER":
        case "GROUP":
        case "INTERSECT":
        case "JOIN":
        case "ORDER":
        case "PROJECT":
        case "SKIP":
        case "TAKE":
        case "UNION":
        case "UNORDER":
          return true;
        default:
          return false;
      }
    }

    /** Element type of a {@code 'a list} or {@code 'a bag}. */
    private static Type elementType(Type t) {
      if (t instanceof ListType) {
        return ((ListType) t).elementType;
      }
      if (t instanceof DataType && "bag".equals(((DataType) t).name)) {
        return ((DataType) t).arguments.get(0);
      }
      throw new IllegalStateException("not a list/bag type: " + t);
    }

    /**
     * Walks a reified value collecting unbound {@code VAR} names (in order of
     * first appearance), recording each one's reified type.
     */
    private void collectScanCandidates(
        Object value, LinkedHashMap<String, Object> out) {
      if (!(value instanceof List)) {
        return;
      }
      List<?> list = (List<?>) value;
      if (list.isEmpty()) {
        return;
      }
      Object head = list.get(0);
      if ("VAR".equals(head) && list.size() > 1) {
        List<?> p = asList(list.get(1));
        String name = (String) p.get(0);
        if (!isLocallyBound(name)
            && env.getOpt(name) == null
            && !out.containsKey(name)) {
          out.put(name, p.get(1));
        }
      }
      for (Object e : list) {
        collectScanCandidates(e, out);
      }
    }

    private boolean isLocallyBound(String name) {
      for (Map<String, Core.NamedPat> scope : localBindings) {
        if (scope.containsKey(name)) {
          return true;
        }
      }
      return false;
    }

    /**
     * Removes and returns the first scan-candidate whose reified type unreifies
     * to {@code expectedType}.
     */
    private Core.@Nullable IdPat takeMatchingScanVar(
        LinkedHashMap<String, Object> scanVars, Type expectedType) {
      for (Map.Entry<String, Object> e : scanVars.entrySet()) {
        Type t = unreifyType(e.getValue());
        if (t.equals(expectedType)) {
          scanVars.remove(e.getKey());
          return core.idPat(t, e.getKey(), 0);
        }
      }
      return null;
    }

    /** Unreifies a chain of from-step constructors into a {@link Core.From}. */
    private Core.Exp unreifyFromChain(Object value) {
      // Peel chain inner-to-outer.
      ArrayDeque<List<?>> stepsDeque = new ArrayDeque<>();
      Object current = value;
      while (current instanceof List
          && isFromStepTag(((List<?>) current).get(0))) {
        List<?> outer = (List<?>) current;
        stepsDeque.addFirst(outer);
        // The "chain so far" is the first payload element of every from-step
        // (positions: FILTER[0]=chain, PROJECT[0]=chain, JOIN[0]=chain, etc.).
        Object payload = outer.size() > 1 ? outer.get(1) : null;
        if (payload instanceof List && !((List<?>) payload).isEmpty()) {
          current = ((List<?>) payload).get(0);
        } else {
          current = payload;
        }
      }
      Core.Exp initialScan = unreifyExp(current);
      List<List<?>> steps = new ArrayList<>(stepsDeque);

      // Pre-scan all step expressions for unbound VARs (scan-bound names).
      LinkedHashMap<String, Object> scanVars = new LinkedHashMap<>();
      for (List<?> step : steps) {
        collectScanCandidates(step, scanVars);
      }

      FromBuilder fb = core.fromBuilder(ts);
      pushScope();
      try {
        // Bind initial scan's variable by matching type.
        Core.IdPat initVar =
            takeMatchingScanVar(scanVars, elementType(initialScan.type));
        if (initVar == null) {
          // No subsequent step uses the initial scan's element; introduce a
          // throwaway binding with a generated name.
          initVar = core.idPat(elementType(initialScan.type), "$scan", 0);
        }
        bindLocal(initVar);
        fb.scan(initVar, initialScan);

        for (List<?> step : steps) {
          processFromStep(fb, step, scanVars);
        }
        return fb.build();
      } finally {
        popScope();
      }
    }

    /**
     * Processes one from-step against the builder, binding any new scan
     * variables introduced by {@code JOIN}.
     */
    private void processFromStep(
        FromBuilder fb, List<?> step, LinkedHashMap<String, Object> scanVars) {
      String tag = (String) step.get(0);
      Object payload = step.size() > 1 ? step.get(1) : null;
      switch (tag) {
          // lint: sort until '#}' where '##case '
        case "EXCEPT":
          {
            List<?> p = asList(payload);
            fb.except(
                (Boolean) p.get(1),
                transformEager(asList(p.get(2)), this::unreifyExp));
            break;
          }
        case "FILTER":
          fb.where(unreifyExp(asList(payload).get(1)));
          break;
        case "GROUP":
          processGroup(fb, payload);
          break;
        case "INTERSECT":
          {
            List<?> p = asList(payload);
            fb.intersect(
                (Boolean) p.get(1),
                transformEager(asList(p.get(2)), this::unreifyExp));
            break;
          }
        case "JOIN":
          {
            List<?> p = asList(payload);
            Core.Exp rightScan = unreifyExp(p.get(1));
            Core.IdPat joinVar =
                takeMatchingScanVar(scanVars, elementType(rightScan.type));
            if (joinVar == null) {
              joinVar = core.idPat(elementType(rightScan.type), "$join", 0);
            }
            bindLocal(joinVar);
            Core.Exp cond = unreifyExp(p.get(2));
            fb.scan(joinVar, rightScan, cond);
            break;
          }
        case "ORDER":
          fb.order(unreifyExp(asList(payload).get(1)));
          break;
        case "PROJECT":
          fb.yield_(unreifyExp(asList(payload).get(1)));
          break;
        case "SKIP":
          fb.skip(unreifyExp(asList(payload).get(1)));
          break;
        case "TAKE":
          fb.take(unreifyExp(asList(payload).get(1)));
          break;
        case "UNION":
          {
            List<?> p = asList(payload);
            fb.union(
                (Boolean) p.get(1),
                transformEager(asList(p.get(2)), this::unreifyExp));
            break;
          }
        case "UNORDER":
          fb.unorder();
          break;
        default: // lint:skip 1 "alphabetical region ends here"
          throw new UnsupportedOperationException(
              "Plan.transform: unsupported from-step " + tag);
      }
    }

    /**
     * Reconstructs a {@code GROUP} step. Note: the original aggregate argument
     * expressions are lost in reification (only the aggregate function
     * expression is kept), so a round-trip GROUP is only approximate.
     */
    private void processGroup(FromBuilder fb, Object payload) {
      List<?> p = asList(payload);
      List<?> keys = asList(p.get(1));
      List<?> aggs = asList(p.get(2));
      ImmutableSortedMap.Builder<Core.IdPat, Core.Exp> keyMap =
          ImmutableSortedMap.naturalOrder();
      for (Object k : keys) {
        List<?> pair = asList(k);
        String name = (String) pair.get(0);
        Core.Exp ke = unreifyExp(pair.get(1));
        keyMap.put(core.idPat(ke.type, name, 0), ke);
      }
      ImmutableSortedMap.Builder<Core.IdPat, Core.Aggregate> aggMap =
          ImmutableSortedMap.naturalOrder();
      for (Object a : aggs) {
        List<?> pair = asList(a);
        String name = (String) pair.get(0);
        Core.Exp ae = unreifyExp(pair.get(1));
        Core.Exp argExp = unreifyOption(pair.get(2));
        Core.Aggregate agg =
            core.aggregate(((FnType) ae.type).resultType, ae, argExp);
        aggMap.put(core.idPat(agg.type, name, 0), agg);
      }
      fb.group(false, keyMap.build(), aggMap.build());
    }

    /**
     * Unreifies an {@code 'a option} value: {@code ["NONE"]} or {@code ["SOME",
     * expr]}. Returns null for NONE.
     */
    private Core.@Nullable Exp unreifyOption(Object value) {
      List<?> list = asList(value);
      if ("SOME".equals(list.get(0))) {
        return unreifyExp(list.get(1));
      }
      return null;
    }

    /**
     * Resolves a reified {@code VAR} reference. Tries, in order:
     *
     * <ol>
     *   <li>A locally-bound IdPat (from FN/CASE/LET/from-scan)
     *   <li>A user-visible {@link Environment} binding
     *   <li>A built-in by ML name (including internal {@code Z_*} ops, which
     *       reified as {@code FN_LITERAL} carrying the built-in's mlName)
     * </ol>
     */
    Core.Exp resolveId(String name) {
      for (Map<String, Core.NamedPat> scope : localBindings) {
        Core.NamedPat p = scope.get(name);
        if (p != null) {
          return core.id(p);
        }
      }
      Binding b = env.getOpt(name);
      if (b != null) {
        return core.id((Core.IdPat) b.id);
      }
      BuiltIn builtIn = lookupBuiltInByMlName(name);
      if (builtIn != null) {
        return core.functionLiteral(ts, builtIn);
      }
      throw new IllegalStateException(
          "Plan.transform: unbound name '" + name + "'");
    }

    private static @Nullable BuiltIn lookupBuiltInByMlName(String mlName) {
      BuiltIn b = BuiltIn.BY_ML_NAME.get(mlName);
      if (b != null) {
        return b;
      }
      // BY_ML_NAME omits internal ($) operators like Z_SUM_INT (mlName
      // "sum:int"). Scan all BuiltIn values to find a match.
      for (BuiltIn bi : BuiltIn.values()) {
        if (bi.mlName.equals(mlName)) {
          return bi;
        }
      }
      return null;
    }

    Core.Pat unreifyPat(Object value) {
      List<?> list = asList(value);
      String tag = (String) list.get(0);
      Object payload = list.size() > 1 ? list.get(1) : null;
      switch (tag) {
          // lint: sort until '#}' where '##case '
        case "P_AS":
          {
            List<?> p = asList(payload);
            String name = (String) p.get(0);
            Core.Pat inner = unreifyPat(p.get(1));
            Type asType = unreifyType(p.get(2));
            return core.asPat(asType, name, 0, inner);
          }

        case "P_BOOL_LIT":
          return core.literalPat(
              Op.BOOL_LITERAL_PAT, PrimitiveType.BOOL, (Boolean) payload);

        case "P_CHAR_LIT":
          return core.literalPat(
              Op.CHAR_LITERAL_PAT, PrimitiveType.CHAR, (Character) payload);

        case "P_CON":
          {
            List<?> p = asList(payload);
            String tyCon = (String) p.get(0);
            Core.Pat inner = unreifyPat(p.get(1));
            Type conType = unreifyType(p.get(2));
            return core.conPat(conType, tyCon, inner);
          }

        case "P_CON0":
          {
            List<?> p = asList(payload);
            String tyCon = (String) p.get(0);
            Type conType = unreifyType(p.get(1));
            return core.con0Pat((DataType) conType, tyCon);
          }

        case "P_CONS":
          {
            List<?> p = asList(payload);
            Core.Pat head = unreifyPat(p.get(0));
            Core.Pat tail = unreifyPat(p.get(1));
            Type consType = unreifyType(p.get(2));
            return core.consPat(
                consType, "::", core.tuplePat(ts, of(head, tail)));
          }

        case "P_INT_LIT":
          return core.literalPat(
              Op.INT_LITERAL_PAT, PrimitiveType.INT, toBigDecimal(payload));

        case "P_LIST":
          {
            List<?> p = asList(payload);
            Type listType = unreifyType(p.get(1));
            return core.listPat(
                listType, transformEager(asList(p.get(0)), this::unreifyPat));
          }

        case "P_REAL_LIT":
          return core.literalPat(
              Op.REAL_LITERAL_PAT, PrimitiveType.REAL, (Float) payload);

        case "P_RECORD":
          {
            List<?> fields = asList(payload);
            SortedMap<String, Type> nameTypes =
                new TreeMap<>(RecordType.ORDERING);
            List<Core.Pat> sub =
                transformEager(
                    fields,
                    f -> {
                      List<?> pair = asList(f);
                      Core.Pat patArg = unreifyPat(pair.get(1));
                      nameTypes.put((String) pair.get(0), patArg.type);
                      return patArg;
                    });
            RecordType recType = (RecordType) ts.recordType(nameTypes);
            return core.recordPat(recType, sub);
          }

        case "P_STRING_LIT":
          return core.literalPat(
              Op.STRING_LITERAL_PAT, PrimitiveType.STRING, (String) payload);

        case "P_TUPLE":
          return core.tuplePat(
              ts, transformEager(asList(payload), this::unreifyPat));

        case "P_VAR":
          {
            List<?> p = asList(payload);
            String name = (String) p.get(0);
            Type type = unreifyType(p.get(1));
            return core.idPat(type, name, 0);
          }

        case "P_WILD":
          return core.wildcardPat(unreifyType(payload));

        default: // lint:skip 1 "alphabetical region ends here"
          throw new UnsupportedOperationException(
              "Plan.transform: cannot yet unreify pat " + tag);
      }
    }

    Type unreifyType(Object value) {
      List<?> list = asList(value);
      String tag = (String) list.get(0);
      Object payload = list.size() > 1 ? list.get(1) : null;
      switch (tag) {
          // lint: sort until '#}' where '##case '
        case "T_BAG":
          return ts.bagType(unreifyType(payload));

        case "T_BOOL":
          return PrimitiveType.BOOL;

        case "T_CHAR":
          return PrimitiveType.CHAR;

        case "T_DATA":
          {
            List<?> p = asList(payload);
            Type base = ts.lookup((String) p.get(0));
            List<?> args = asList(p.get(1));
            if (args.isEmpty()) {
              return base;
            }
            return ts.apply(base, transformEager(args, this::unreifyType));
          }

        case "T_FN":
          {
            List<?> p = asList(payload);
            return ts.fnType(unreifyType(p.get(0)), unreifyType(p.get(1)));
          }

        case "T_INT":
          return PrimitiveType.INT;

        case "T_LIST":
          return ts.listType(unreifyType(payload));

        case "T_REAL":
          return PrimitiveType.REAL;

        case "T_RECORD":
          {
            List<?> fields = asList(payload);
            SortedMap<String, Type> m = new TreeMap<>(RecordType.ORDERING);
            for (Object f : fields) {
              List<?> pair = asList(f);
              m.put((String) pair.get(0), unreifyType(pair.get(1)));
            }
            return ts.recordType(m);
          }

        case "T_STRING":
          return PrimitiveType.STRING;

        case "T_TUPLE":
          return ts.tupleType(
              transformEager(asList(payload), this::unreifyType));

        case "T_UNIT":
          return PrimitiveType.UNIT;

        case "T_VAR":
          return ts.typeVariable(((Number) payload).intValue());

        default: // lint:skip 1 "alphabetical region ends here"
          throw new UnsupportedOperationException(
              "Plan.transform: cannot yet unreify type " + tag);
      }
    }

    private static List<?> asList(Object o) {
      if (!(o instanceof List)) {
        throw new IllegalStateException(
            "Plan.transform: expected list, got " + o);
      }
      return (List<?>) o;
    }

    private static BigDecimal toBigDecimal(Object o) {
      if (o instanceof BigDecimal) {
        return (BigDecimal) o;
      }
      return BigDecimal.valueOf(((Number) o).longValue());
    }

    private static List<Type> repeat(Type t, int n) {
      List<Type> r = new ArrayList<>(n);
      for (int i = 0; i < n; i++) {
        r.add(t);
      }
      return r;
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
