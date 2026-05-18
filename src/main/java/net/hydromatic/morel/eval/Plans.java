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
import static java.lang.String.format;
import static net.hydromatic.morel.ast.CoreBuilder.core;
import static net.hydromatic.morel.compile.BuiltIn.Constructor.*;
import static net.hydromatic.morel.util.Static.transform;
import static net.hydromatic.morel.util.Static.transformEager;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import java.math.BigDecimal;
import java.util.AbstractMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.FromBuilder;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.compile.Environment;
import net.hydromatic.morel.compile.Environments;
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
      // lint: sort until '##default:' where '##  return '
      switch (b) {
        case OP_CARET:
          return OpKind.CONCAT;
        case OP_CONS:
          return OpKind.CONS;
        case REAL_DIVIDE:
          return OpKind.DIVIDE;
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
        case OP_NEGATE:
        case Z_NEGATE_INT:
        case Z_NEGATE_REAL:
          return OpKind.NEG;
        case BOOL_NOT:
        case NOT:
          return OpKind.NOT;
        case OP_NOT_ELEM:
          return OpKind.NOT_ELEM;
        case OP_NE:
          return OpKind.NOT_EQUALS;
        case OP_PLUS:
        case Z_PLUS_INT:
        case Z_PLUS_REAL:
          return OpKind.PLUS;
        case OP_TIMES:
        case Z_TIMES_INT:
        case Z_TIMES_REAL:
          return OpKind.TIMES;
        default:
          return null;
      }
    }
    if (fn.op == Op.ID) {
      // Strip the overload `$N` suffix before matching, so e.g.
      // "op elem$1" matches the "op elem" case.
      switch (stripDollarN(((Core.Id) fn).idPat.name)) {
          // lint: sort until '#}' where '#case '
        case "not":
          return OpKind.NOT;
        case "op /":
          return OpKind.DIVIDE;
        case "op ^":
          return OpKind.CONCAT;
        case "op ~":
          return OpKind.NEG;
        case "op -":
          return OpKind.MINUS;
        case "op @":
          return OpKind.AT;
        case "op *":
          return OpKind.TIMES;
        case "op +":
          return OpKind.PLUS;
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
        case "op div":
          return OpKind.DIV;
        case "op elem":
          return OpKind.ELEM;
        case "op mod":
          return OpKind.MOD;
        case "op notelem":
          return OpKind.NOT_ELEM;
        case "op ::":
          return OpKind.CONS;
        default:
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
        // lint: sort until '#default:' where '#case '
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
      default:
        return null;
    }
  }

  /**
   * Untyped binary operators (result is bool or string): kind -> constructor.
   */
  private static BuiltIn.@Nullable Constructor binaryUntypedOpCon(OpKind k) {
    switch (k) {
        // lint: sort until '#default:' where '#case '
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
      default:
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

  /** Reifies a {@link Type} as a runtime {@code Type.t} value. */
  public static Object reifyType(Type type) {
    return new Reifier().reifyType(type);
  }

  /**
   * Inverse of {@link #reifyType}: converts a reified {@code Type.t} value back
   * to a {@link Type}. Types never reference user-environment bindings, so this
   * needs only a {@link TypeSystem}.
   */
  public static Type unreifyType(Object value, TypeSystem typeSystem) {
    return new Unreifier(typeSystem, Environments.empty()).unreifyType(value);
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
          // lint: sort until '#default:' where '#case '
        case APPLY:
          return reifyApply((Core.Apply) exp);

        case BOOL_LITERAL:
          return iof(
              CORE_EXPR_BOOL_LITERAL,
              ((Core.Literal) exp).unwrap(Boolean.class));

        case CASE:
          Core.Case caseExp = (Core.Case) exp;
          ImmutableList.Builder<Object> matches = ImmutableList.builder();
          for (Core.Match m : caseExp.matchList) {
            matches.add(of(reifyPat(m.pat), reifyExp(m.exp)));
          }
          return iof(
              CORE_EXPR_CASE,
              of(reifyExp(caseExp.exp), matches.build(), reifyType(exp.type)));

        case CHAR_LITERAL:
          return iof(
              CORE_EXPR_CHAR_LITERAL,
              ((Core.Literal) exp).unwrap(Character.class));

        case FN:
          Core.Fn fn = (Core.Fn) exp;
          return iof(
              CORE_EXPR_FN,
              of(reifyPat(fn.idPat), reifyExp(fn.exp), reifyType(exp.type)));

        case FN_LITERAL:
          // A bare reference to a built-in (e.g. `op elem` resolved to a
          // FN_LITERAL): reify as VAR carrying the built-in's ML name.
          BuiltIn fnBi = ((Core.Literal) exp).unwrap(BuiltIn.class);
          return iof(CORE_EXPR_VAR, of(fnBi.mlName, reifyType(exp.type)));

        case FROM:
          return reifyFrom((Core.From) exp);

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

        case INT_LITERAL:
          return iof(
              CORE_EXPR_INT_LITERAL,
              ((Core.Literal) exp).unwrap(Integer.class));

        case LET:
          Core.Let let = (Core.Let) exp;
          return reifyLet(let);

        case RAISE:
          Core.Raise raise = (Core.Raise) exp;
          return iof(
              CORE_EXPR_RAISE, of(reifyExp(raise.exp), reifyType(exp.type)));

        case REAL_LITERAL:
          return iof(
              CORE_EXPR_REAL_LITERAL, ((Core.Literal) exp).unwrap(Float.class));

        case STRING_LITERAL:
          return iof(
              CORE_EXPR_STRING_LITERAL,
              ((Core.Literal) exp).unwrap(String.class));

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

        case UNIT_LITERAL:
          return iof(CORE_EXPR_UNIT_LITERAL);

        case VALUE_LITERAL:
          // The Inliner replaces an Id whose value is statically known
          // with a VALUE_LITERAL carrying the value. Reify the value
          // recursively as a literal Core.expr (INT_LITERAL, LIST_LITERAL,
          // E_RECORD, ...). This preserves the value across reify/unreify
          // so that Plan.transform can round-trip captured closures.
          // Datatype-constructor values (e.g., NONE, SOME x) still reify
          // as VAR with the constructor name; unrepresentable values fall
          // back to a `<value>` placeholder.
          Object value = ((Core.Literal) exp).unwrap(Object.class);
          if (value instanceof List
              && !((List<?>) value).isEmpty()
              && ((List<?>) value).get(0) instanceof String) {
            return iof(
                CORE_EXPR_VAR,
                of((String) ((List<?>) value).get(0), reifyType(exp.type)));
          }
          Object reified = reifyRuntimeValue(value, exp.type);
          if (reified != null) {
            return reified;
          }
          return iof(CORE_EXPR_VAR, of("<value>", reifyType(exp.type)));

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
      int scanIndex = 0;
      // Bindings in scope BEFORE the current step, used to rewrite
      // predicates/conditions/keys into positional ($0/$1) form.
      List<Binding> prevBindings = ImmutableList.of();
      for (Core.FromStep step : from.steps) {
        switch (step.op) {
            // lint: sort until '#default:' where '#case '
          case EXCEPT:
            current = reifySetStep(step, CORE_EXPR_EXCEPT, current);
            break;
          case GROUP:
            Core.Group group = (Core.Group) step;
            // GROUP keys reference pre-group scan-bound names. Each agg
            // argument (`agg over expr`) does too. Rewrite both with
            // $0-positional refs against prevBindings.
            final List<Binding> groupPrev = prevBindings;
            ImmutableList.Builder<Object> keys = ImmutableList.builder();
            group.groupExps.forEach(
                (k, v) ->
                    keys.add(
                        of(
                            k.name,
                            rewriteToPositional(
                                reifyExp(v), groupPrev, "$0"))));
            ImmutableList.Builder<Object> aggs = ImmutableList.builder();
            group.aggregates.forEach(
                (k, agg) -> {
                  Object argOpt =
                      agg.argument == null
                          ? of("NONE")
                          : of(
                              "SOME",
                              rewriteToPositional(
                                  reifyExp(agg.argument), groupPrev, "$0"));
                  aggs.add(of(k.name, reifyExp(agg.aggregate), argOpt));
                });
            current =
                iof(CORE_EXPR_GROUP, of(current, keys.build(), aggs.build()));
            break;
          case INTERSECT:
            current = reifySetStep(step, CORE_EXPR_INTERSECT, current);
            break;
          case ORDER:
            Core.Order order = (Core.Order) step;
            // ORDER's key expression references scan-bound names; rewrite
            // to $0-positional form.
            Object orderExp =
                rewriteToPositional(reifyExp(order.exp), prevBindings, "$0");
            current = iof(CORE_EXPR_ORDER, of(current, orderExp));
            break;
          case SCAN:
            Core.Scan scan = (Core.Scan) step;
            // For an IdPat scan, SCAN(name, source) carries the scan
            // variable name directly. For a destructuring pattern
            // (TuplePat / RecordPat), introduce a synthetic name
            // "$0, $1, ..." for the scan element and wrap with a
            // PROJECT that yields a record extracting the destructured
            // bindings. Downstream operations then see those bindings
            // by name, and rowType reads them from the PROJECT's
            // yield-record type just like any other yield.
            Object scanNode = reifyScanWithDestructure(scan, scanIndex);
            scanIndex++;
            if (!haveScan) {
              current = scanNode;
              haveScan = true;
            } else {
              // Subsequent scans become JOINs with the existing tree. The
              // join condition uses positional references: left scan-vars
              // become $0, the right scan-var becomes $1, with FIELD
              // access by ordinal for multi-col rows.
              Object joinCond = reifyExp(scan.condition);
              List<Binding> rightBindings =
                  bindingsAddedBy(prevBindings, scan.env.bindings);
              Object joinCondRewritten =
                  rewriteToPositionalJoin(
                      joinCond, prevBindings, rightBindings);
              current =
                  iof(CORE_EXPR_JOIN, of(current, scanNode, joinCondRewritten));
            }
            break;
          case SKIP:
            Core.Skip skip = (Core.Skip) step;
            current = iof(CORE_EXPR_SKIP, of(current, reifyExp(skip.exp)));
            break;
          case TAKE:
            Core.Take take = (Core.Take) step;
            current = iof(CORE_EXPR_TAKE, of(current, reifyExp(take.exp)));
            break;
          case UNION:
            current = reifySetStep(step, CORE_EXPR_UNION, current);
            break;
          case UNORDER:
            current = iof(CORE_EXPR_UNORDER, current);
            break;
          case WHERE:
            Core.Where where = (Core.Where) step;
            // FILTER's predicate references the chain's row as $0. With
            // the IR's "never atomize" rule, $0 always has T_RECORD type;
            // a scan-var "e" becomes FIELD(VAR("$0", T_RECORD [...]),
            // "e", T).
            Object predExpr = reifyExp(where.exp);
            Object predRewritten =
                rewriteToPositional(predExpr, where.env.bindings, "$0");
            current = iof(CORE_EXPR_FILTER, of(current, predRewritten));
            break;
          case YIELD:
            Core.Yield yield = (Core.Yield) step;
            // YIELD's body references pre-yield scan-bound names; rewrite
            // to $0-positional form. The output row's binding names come
            // from the yield's record-type structure (or a synthetic name
            // for bare yields), handled downstream by rowType.
            Object yieldExp =
                rewriteToPositional(reifyExp(yield.exp), prevBindings, "$0");
            current = iof(CORE_EXPR_PROJECT, of(current, yieldExp));
            break;
          default:
            throw new UnsupportedOperationException(
                "Plan.core: from-step " + step.op + " not yet supported");
        }
        prevBindings = step.env.bindings;
      }
      return current;
    }

    /**
     * Returns the bindings present in {@code after} but not in {@code before},
     * preserving order. Used to identify the new bindings introduced by a scan
     * (one for IdPat, several for destructuring).
     */
    private static List<Binding> bindingsAddedBy(
        List<Binding> before, List<Binding> after) {
      Set<String> beforeNames = new HashSet<>();
      for (Binding b : before) {
        beforeNames.add(b.id.name);
      }
      ImmutableList.Builder<Binding> added = ImmutableList.builder();
      for (Binding b : after) {
        if (!beforeNames.contains(b.id.name)) {
          added.add(b);
        }
      }
      return added.build();
    }

    /** Reifies an {@code EXCEPT}/{@code INTERSECT}/{@code UNION} from-step. */
    Object reifySetStep(
        Core.FromStep step, BuiltIn.Constructor setCon, Object current) {
      final Core.SetStep setStep = (Core.SetStep) step;
      final List<Object> args = transformEager(setStep.args, this::reifyExp);
      return iof(setCon, of(current, setStep.distinct, args));
    }

    /**
     * Reifies a scan. If the pattern is an {@link Core.IdPat}, emits {@code
     * SCAN(name, source)}. Otherwise emits {@code PROJECT(SCAN("$N", source),
     * record)} where the record extracts each bound variable from a synthetic
     * scan-element binding.
     */
    Object reifyScanWithDestructure(Core.Scan scan, int scanIndex) {
      Object reifiedSource = reifyExp(scan.exp);
      if (scan.pat instanceof Core.IdPat) {
        String name = ((Core.IdPat) scan.pat).name;
        return iof(CORE_EXPR_SCAN, of(name, reifiedSource));
      }
      // Destructuring scan: synth name, then PROJECT that extracts bindings.
      String synthName = "$" + scanIndex;
      Type elemType = ((Core.Scan) scan).pat.type;
      Object scanNode = iof(CORE_EXPR_SCAN, of(synthName, reifiedSource));
      Object rootVar = iof(CORE_EXPR_VAR, of(synthName, reifyType(elemType)));
      List<Map.Entry<String, Object>> bindings = new ArrayList<>();
      List<Map.Entry<String, Type>> bindingTypes = new ArrayList<>();
      collectDestructureBindings(scan.pat, rootVar, bindings, bindingTypes);
      // Sort alphabetically (record field order).
      List<Integer> order = new ArrayList<>();
      for (int i = 0; i < bindings.size(); i++) {
        order.add(i);
      }
      order.sort(
          (a, b) ->
              bindings.get(a).getKey().compareTo(bindings.get(b).getKey()));
      ImmutableList.Builder<Object> fields = ImmutableList.builder();
      ImmutableList.Builder<Object> typeFields = ImmutableList.builder();
      for (int idx : order) {
        Map.Entry<String, Object> b = bindings.get(idx);
        Map.Entry<String, Type> bt = bindingTypes.get(idx);
        fields.add(of(b.getKey(), b.getValue()));
        typeFields.add(of(bt.getKey(), reifyType(bt.getValue())));
      }
      Object yieldRecordType = iof(TYPE_RECORD, typeFields.build());
      Object yieldExpr =
          iof(CORE_EXPR_RECORD, of(fields.build(), yieldRecordType));
      return iof(CORE_EXPR_PROJECT, of(scanNode, yieldExpr));
    }

    /**
     * Walks a destructuring pattern, appending {@code (name, access-expr)}
     * pairs (and their types) for each bound variable found. {@code
     * WildcardPat} contributes nothing.
     */
    void collectDestructureBindings(
        Core.Pat pat,
        Object accessExpr,
        List<Map.Entry<String, Object>> bindings,
        List<Map.Entry<String, Type>> bindingTypes) {
      if (pat instanceof Core.IdPat) {
        Core.IdPat id = (Core.IdPat) pat;
        bindings.add(new AbstractMap.SimpleEntry<>(id.name, accessExpr));
        bindingTypes.add(new AbstractMap.SimpleEntry<>(id.name, id.type));
        return;
      }
      if (pat instanceof Core.TuplePat) {
        Core.TuplePat tp = (Core.TuplePat) pat;
        // Tuple fields are encoded as records with numeric labels "1","2",...
        for (int i = 0; i < tp.args.size(); i++) {
          Core.Pat sub = tp.args.get(i);
          String fieldName = String.valueOf(i + 1);
          Object subAccess =
              iof(
                  CORE_EXPR_FIELD,
                  of(accessExpr, fieldName, reifyType(sub.type)));
          collectDestructureBindings(sub, subAccess, bindings, bindingTypes);
        }
        return;
      }
      if (pat instanceof Core.RecordPat) {
        Core.RecordPat rp = (Core.RecordPat) pat;
        List<String> fieldNames =
            new ArrayList<>(rp.type().argNameTypes.keySet());
        for (int i = 0; i < rp.args.size(); i++) {
          Core.Pat sub = rp.args.get(i);
          String fieldName = fieldNames.get(i);
          Object subAccess =
              iof(
                  CORE_EXPR_FIELD,
                  of(accessExpr, fieldName, reifyType(sub.type)));
          collectDestructureBindings(sub, subAccess, bindings, bindingTypes);
        }
        return;
      }
      // WildcardPat, LiteralPat, ConPat: nothing to bind.
    }

    /**
     * Rewrites a reified expression so that references to scan-bound names in
     * {@code bindings} become positional accesses on {@code rootName}. For a
     * one-element {@code bindings}, the row is atomized: a reference becomes
     * {@code VAR(rootName, t)}. For two or more, the row is a tuple: a
     * reference becomes {@code FIELD(VAR(rootName, T_TUPLE [...]), "N", t)}
     * with {@code N} the 1-based ordinal. Names not in {@code bindings} are
     * outer-scope references and are left untouched.
     *
     * <p>This is what gives FILTER/JOIN predicates the "implicit lambda" shape:
     * {@code fn $0 => body} for FILTER, {@code fn ($0, $1) => body} for JOIN,
     * without actually wrapping in an FN.
     */
    Object rewriteToPositional(
        Object expr, Iterable<? extends Binding> bindings, String rootName) {
      Set<String> namesInRow = new HashSet<>();
      ImmutableList.Builder<Object> rowFields = ImmutableList.builder();
      int i = 0;
      for (Binding b : bindings) {
        namesInRow.add(b.id.name);
        rowFields.add(of(b.id.name, reifyType(b.id.type)));
        i++;
      }
      if (i == 0) {
        return expr;
      }
      // Row type is always T_RECORD with fields in pipeline (insertion)
      // order — even for 1-binding chains. Atomization happens only at
      // the Morel boundary (when the from-builder materializes a single-
      // scan result), not in the IR; keeping the predicate shape uniform
      // means rewrite rules like filter-pushdown don't have to special-
      // case row width.
      Object rowType = iof(TYPE_RECORD, rowFields.build());
      return rewriteVarRefs(expr, namesInRow, rootName, rowType);
    }

    /**
     * Two-input version for JOIN: left names map to {@code rootLeft} ($0) and
     * right names map to {@code rootRight} ($1). Applied as two sequential
     * passes (left first, then right).
     */
    Object rewriteToPositionalJoin(
        Object expr,
        Iterable<? extends Binding> leftBindings,
        Iterable<? extends Binding> rightBindings) {
      Object afterLeft = rewriteToPositional(expr, leftBindings, "$0");
      return rewriteToPositional(afterLeft, rightBindings, "$1");
    }

    /** Recursive walker for {@link #rewriteToPositional}. */
    private Object rewriteVarRefs(
        Object expr, Set<String> namesInRow, String rootName, Object rowType) {
      if (!(expr instanceof List)) {
        return expr;
      }
      List<?> list = (List<?>) expr;
      if (list.isEmpty()) {
        return expr;
      }
      Object tag = list.get(0);
      if ("VAR".equals(tag) && list.size() > 1 && list.get(1) instanceof List) {
        List<?> payload = (List<?>) list.get(1);
        if (payload.size() == 2 && payload.get(0) instanceof String) {
          String name = (String) payload.get(0);
          if (namesInRow.contains(name)) {
            Object type = payload.get(1);
            // FIELD access by name. The row type is T_RECORD in pipeline
            // order; FIELD doesn't depend on order, just looks up by name.
            Object rootVar = iof(CORE_EXPR_VAR, of(rootName, rowType));
            return iof(CORE_EXPR_FIELD, of(rootVar, name, type));
          }
        }
      }
      // Recurse into all sub-lists.
      ImmutableList.Builder<Object> b = ImmutableList.builder();
      boolean changed = false;
      for (Object child : list) {
        Object newChild = rewriteVarRefs(child, namesInRow, rootName, rowType);
        if (newChild != child) {
          changed = true;
        }
        b.add(newChild);
      }
      return changed ? b.build() : expr;
    }

    /**
     * Reifies a {@link Type} as a runtime value of the Morel-level {@code
     * Type.t} datatype.
     */
    Object reifyType(Type type) {
      switch (type.op()) {
          // lint: sort until '#default:' where '#case '
        case DATA_TYPE:
          DataType dt = (DataType) type;
          if (dt.name.equals("bag")) {
            return iof(TYPE_BAG, reifyType(dt.arguments.get(0)));
          }
          return iof(
              TYPE_DATA,
              of(dt.name, transformEager(dt.arguments, this::reifyType)));

        case FORALL_TYPE:
          // Unwrap the universal quantifier; the body's type variables
          // are reified as T_VAR with their ordinals.
          return reifyType(((ForallType) type).type);

        case FUNCTION_TYPE:
          final FnType ft = (FnType) type;
          return iof(
              TYPE_FN, of(reifyType(ft.paramType), reifyType(ft.resultType)));

        case ID:
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
            default:
              throw new AssertionError("unknown primitive type: " + type);
          }

        case LIST:
          return iof(TYPE_LIST, reifyType(((ListType) type).elementType));

        case RECORD_TYPE:
          return iof(
              TYPE_RECORD,
              transformEager(
                  ((RecordType) type).argNameTypes.entrySet(),
                  e -> of(e.getKey(), reifyType(e.getValue()))));

        case TUPLE_TYPE:
          return iof(
              TYPE_TUPLE,
              transformEager(((TupleType) type).argTypes, this::reifyType));

        case TY_VAR:
          return iof(TYPE_VAR, ((TypeVar) type).ordinal);

        default:
          throw new UnsupportedOperationException(
              format(
                  "Plan.core: cannot yet reify type %s (%s)",
                  type, type.getClass().getSimpleName()));
      }
    }

    /**
     * Reifies a Java-level runtime value as a literal {@code Core.expr}, so
     * that captured constants (lists, records, primitive literals) survive the
     * reify/unreify round-trip. Returns {@code null} for shapes that cannot be
     * represented (e.g., closures); callers fall back to a placeholder.
     */
    @Nullable
    Object reifyRuntimeValue(Object value, Type type) {
      switch (type.op()) {
        case ID:
          if (type == PrimitiveType.INT) {
            return iof(CORE_EXPR_INT_LITERAL, value);
          }
          if (type == PrimitiveType.REAL) {
            return iof(CORE_EXPR_REAL_LITERAL, value);
          }
          if (type == PrimitiveType.STRING) {
            return iof(CORE_EXPR_STRING_LITERAL, value);
          }
          if (type == PrimitiveType.CHAR) {
            return iof(CORE_EXPR_CHAR_LITERAL, value);
          }
          if (type == PrimitiveType.BOOL) {
            return iof(CORE_EXPR_BOOL_LITERAL, value);
          }
          if (type == PrimitiveType.UNIT) {
            return iof(CORE_EXPR_UNIT_LITERAL);
          }
          return null;

        case LIST:
          if (value instanceof List) {
            Type elemType = ((ListType) type).elementType;
            ImmutableList.Builder<Object> elems = ImmutableList.builder();
            for (Object e : (List<?>) value) {
              Object r = reifyRuntimeValue(e, elemType);
              if (r == null) {
                return null;
              }
              elems.add(r);
            }
            return iof(
                CORE_EXPR_LIST_LITERAL, of(elems.build(), reifyType(type)));
          }
          return null;

        case TUPLE_TYPE:
          if (value instanceof List) {
            List<?> args = (List<?>) value;
            List<Type> argTypes = ((TupleType) type).argTypes;
            if (args.size() != argTypes.size()) {
              return null;
            }
            ImmutableList.Builder<Object> rArgs = ImmutableList.builder();
            for (int i = 0; i < args.size(); i++) {
              Object r = reifyRuntimeValue(args.get(i), argTypes.get(i));
              if (r == null) {
                return null;
              }
              rArgs.add(r);
            }
            return iof(CORE_EXPR_TUPLE, rArgs.build());
          }
          return null;

        case RECORD_TYPE:
          if (value instanceof List) {
            List<?> args = (List<?>) value;
            RecordType rt = (RecordType) type;
            ImmutableList.Builder<Object> fields = ImmutableList.builder();
            int i = 0;
            for (Map.Entry<String, Type> e : rt.argNameTypes.entrySet()) {
              if (i >= args.size()) {
                return null;
              }
              Object r = reifyRuntimeValue(args.get(i), e.getValue());
              if (r == null) {
                return null;
              }
              fields.add(of(e.getKey(), r));
              i++;
            }
            return iof(CORE_EXPR_RECORD, of(fields.build(), reifyType(type)));
          }
          return null;

        default:
          return null;
      }
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

  /** Pre-built reified primitive types. */
  private static final ImmutableList<Object> R_BOOL = of(TYPE_BOOL.constructor);

  private static final ImmutableList<Object> R_CHAR = of(TYPE_CHAR.constructor);
  private static final ImmutableList<Object> R_INT = of(TYPE_INT.constructor);
  private static final ImmutableList<Object> R_REAL = of(TYPE_REAL.constructor);
  private static final ImmutableList<Object> R_STRING =
      of(TYPE_STRING.constructor);
  private static final ImmutableList<Object> R_UNIT = of(TYPE_UNIT.constructor);

  /**
   * Returns the reified {@code Type.t} of a reified {@code Core.expr}.
   *
   * <p>Walks the top-level node and extracts the embedded type when possible;
   * recurses into a child for {@code LET}, {@code TUPLE}, and from-step
   * constructors. {@code JOIN} and {@code GROUP} change the chain's element
   * type in ways that depend on the surrounding scan variables, so they fall
   * back to {@link #unreifyExp} (which has the necessary context) and then
   * {@link #reifyType}.
   */
  public static Object typeOfReified(
      Object value, TypeSystem typeSystem, Environment env) {
    final List<?> list = (List<?>) value;
    final String tag = (String) list.get(0);
    switch (tag) {
        // lint: sort until '#default:' where '#case '
      case "AND":
        return R_BOOL;
      case "APPLY":
        return ((List<?>) list.get(1)).get(2);
      case "AT":
        return ((List<?>) list.get(1)).get(2);
      case "BOOL_LITERAL":
        return R_BOOL;
      case "CASE":
        return ((List<?>) list.get(1)).get(2);
      case "CHAR_LITERAL":
        return R_CHAR;
      case "CONCAT":
        return R_STRING;
      case "CONS":
        return ((List<?>) list.get(1)).get(2);
      case "DIV":
        return ((List<?>) list.get(1)).get(2);
      case "DIVIDE":
        return ((List<?>) list.get(1)).get(2);
      case "E_RECORD":
        return ((List<?>) list.get(1)).get(1);
      case "ELEM":
        return R_BOOL;
      case "EQUALS":
        return R_BOOL;
      case "EXCEPT":
        return typeOfReified(((List<?>) list.get(1)).get(0), typeSystem, env);
      case "FIELD":
        return ((List<?>) list.get(1)).get(2);
      case "FILTER":
        return typeOfReified(((List<?>) list.get(1)).get(0), typeSystem, env);
      case "FN":
        return ((List<?>) list.get(1)).get(2);
      case "GE":
        return R_BOOL;
      case "GROUP":
        return reifyType(unreifyExp(value, typeSystem, env).type);
      case "GT":
        return R_BOOL;
      case "INT_LITERAL":
        return R_INT;
      case "INTERSECT":
        return typeOfReified(((List<?>) list.get(1)).get(0), typeSystem, env);
      case "JOIN":
        {
          // Element type: T_RECORD with fields in input-0 then input-1
          // order — explicitly NOT alphabetized, so JOIN reassociation
          // doesn't shuffle field positions in the IR. Collection kind
          // is the meet of the two children's kinds (any bag forces bag).
          List<Object> joinCols = rowType(value, typeSystem, env);
          if (joinCols == null) {
            return reifyType(unreifyExp(value, typeSystem, env).type);
          }
          List<?> joinPayload = (List<?>) list.get(1);
          boolean joinOrdered =
              isList(joinPayload.get(0), typeSystem, env)
                  && isList(joinPayload.get(1), typeSystem, env);
          Object joinElem = of(TYPE_RECORD.constructor, joinCols);
          return collectionOfKind(joinOrdered, joinElem);
        }
      case "LE":
        return R_BOOL;
      case "LET":
        return typeOfReified(((List<?>) list.get(1)).get(1), typeSystem, env);
      case "LIST_LITERAL":
        return ((List<?>) list.get(1)).get(1);
      case "LT":
        return R_BOOL;
      case "MINUS":
        return ((List<?>) list.get(1)).get(2);
      case "MOD":
        return ((List<?>) list.get(1)).get(2);
      case "NEG":
        return ((List<?>) list.get(1)).get(1);
      case "NOT_ELEM":
        return R_BOOL;
      case "NOT_EQUALS":
        return R_BOOL;
      case "NOT":
        return R_BOOL;
      case "OR":
        return R_BOOL;
      case "ORDER":
        // Forces a list (ordered) collection over the chain's element.
        return collectionOfKind(
            true,
            elementOfCollection(
                typeOfReified(
                    ((List<?>) list.get(1)).get(0), typeSystem, env)));
      case "PLUS":
        return ((List<?>) list.get(1)).get(2);
      case "PROJECT":
        {
          List<?> args = (List<?>) list.get(1);
          Object chainType = typeOfReified(args.get(0), typeSystem, env);
          Object newElem = typeOfReified(args.get(1), typeSystem, env);
          boolean ordered =
              TYPE_LIST.constructor.equals(((List<?>) chainType).get(0));
          return collectionOfKind(ordered, newElem);
        }
      case "RAISE":
        return ((List<?>) list.get(1)).get(1);
      case "REAL_LITERAL":
        return R_REAL;
      case "SCAN":
        {
          // SCAN(name, source) — the IR's row is a 1-field record
          // {name: source.elem}. (Boundary-atomization to the scalar
          // happens only when the from-builder materializes a single-
          // scan result for Morel; the IR uniformly sees records.)
          List<?> scanPayload = (List<?>) list.get(1);
          String scanName = (String) scanPayload.get(0);
          Object srcType = typeOfReified(scanPayload.get(1), typeSystem, env);
          Object scanElem =
              of(
                  TYPE_RECORD.constructor,
                  ImmutableList.of(of(scanName, elementOfCollection(srcType))));
          boolean scanOrdered =
              TYPE_LIST.constructor.equals(((List<?>) srcType).get(0));
          return collectionOfKind(scanOrdered, scanElem);
        }
      case "SKIP":
        return typeOfReified(((List<?>) list.get(1)).get(0), typeSystem, env);
      case "STRING_LITERAL":
        return R_STRING;
      case "TAKE":
        return typeOfReified(((List<?>) list.get(1)).get(0), typeSystem, env);
      case "TIMES":
        return ((List<?>) list.get(1)).get(2);
      case "TUPLE":
        return of(
            TYPE_TUPLE.constructor,
            transformEager(
                (List<?>) list.get(1),
                arg -> typeOfReified(arg, typeSystem, env)));
      case "UNION":
        return typeOfReified(((List<?>) list.get(1)).get(0), typeSystem, env);
      case "UNIT_LITERAL":
        return R_UNIT;
      case "UNORDER":
        // Forces a bag (unordered) collection over the chain's element.
        return collectionOfKind(
            false,
            elementOfCollection(typeOfReified(list.get(1), typeSystem, env)));
      case "VAR":
        return ((List<?>) list.get(1)).get(1);
      default:
        throw new UnsupportedOperationException(
            "Plan.expr_type: unknown tag " + tag);
    }
  }

  /**
   * Returns the row type of a reified pipeline expression as a list of {@code
   * [name, reified-type]} pairs in pipeline (positional) order — the order in
   * which the columns would be concatenated by {@code JOIN}, before the
   * boundary-time alphabetization that {@code T_RECORD} would impose.
   *
   * <p>Walks the chain bottom-up, burrowing through transparent steps
   * (FILTER/ORDER/SKIP/TAKE/UNORDER/UNION/INTERSECT/EXCEPT) until it reaches a
   * column-introducing node: {@code SCAN} contributes one column named for the
   * scan variable; {@code JOIN} concatenates its two children's rows (with
   * {@code $N}-suffix renaming for duplicate names, $1/$2/... in occurrence
   * order); {@code PROJECT} replaces the row with the fields of its yield
   * expression's record type (or a single synthetic column for a scalar yield);
   * {@code GROUP} returns its keys ++ aggs as columns.
   *
   * <p>Returns {@code null} when the expression is not a pipeline expression
   * (i.e. not a chain of from-step constructors rooted at SCAN, GROUP, etc.).
   * The Morel-visible {@code Plan.rowType} wraps this as {@code option}.
   */
  public static @Nullable List<Object> rowType(
      Object value, TypeSystem typeSystem, Environment env) {
    if (!(value instanceof List)) {
      return null;
    }
    List<?> list = (List<?>) value;
    if (list.isEmpty() || !(list.get(0) instanceof String)) {
      return null;
    }
    String tag = (String) list.get(0);
    switch (tag) {
      case "SCAN":
        {
          List<?> p = (List<?>) list.get(1);
          String name = (String) p.get(0);
          Object srcType = typeOfReified(p.get(1), typeSystem, env);
          return ImmutableList.of(of(name, elementOfCollection(srcType)));
        }
      case "JOIN":
        {
          List<?> p = (List<?>) list.get(1);
          List<Object> leftCols = rowType(p.get(0), typeSystem, env);
          List<Object> rightCols = rowType(p.get(1), typeSystem, env);
          if (leftCols == null || rightCols == null) {
            return null;
          }
          return dedupCols(concatCols(leftCols, rightCols));
        }
      case "FILTER":
      case "ORDER":
      case "SKIP":
      case "TAKE":
      case "UNORDER":
        return rowType(((List<?>) list.get(1)).get(0), typeSystem, env);
      case "EXCEPT":
      case "INTERSECT":
      case "UNION":
        return rowType(((List<?>) list.get(1)).get(0), typeSystem, env);
      case "PROJECT":
        {
          List<?> p = (List<?>) list.get(1);
          Object yieldType = typeOfReified(p.get(1), typeSystem, env);
          return rowFromType(yieldType, "$yield");
        }
      case "GROUP":
        {
          // GROUP's reified form: (chain, keys, aggs). Each key is
          // (name, expr); each agg is (name, aggFn, argOpt).
          List<?> p = (List<?>) list.get(1);
          List<?> keys = (List<?>) p.get(1);
          List<?> aggs = (List<?>) p.get(2);
          ImmutableList.Builder<Object> cols = ImmutableList.builder();
          for (Object k : keys) {
            List<?> pair = (List<?>) k;
            cols.add(
                of(pair.get(0), typeOfReified(pair.get(1), typeSystem, env)));
          }
          for (Object a : aggs) {
            List<?> tr = (List<?>) a;
            Object aggType = typeOfReified(tr.get(1), typeSystem, env);
            // aggFn has type 'a list -> b; the column type is b.
            cols.add(of(tr.get(0), codomainOfFnType(aggType)));
          }
          return cols.build();
        }
      default:
        return null;
    }
  }

  /**
   * Builds a row from a value type — used by PROJECT/YIELD. If the type is a
   * record, each field becomes a column; otherwise a single synthetic column
   * holds the whole value.
   */
  private static @Nullable List<Object> rowFromType(
      Object type, String synthName) {
    if (!(type instanceof List) || ((List<?>) type).isEmpty()) {
      return null;
    }
    String tag = (String) ((List<?>) type).get(0);
    if (TYPE_RECORD.constructor.equals(tag)) {
      // T_RECORD's payload is a list of [name, type] pairs.
      Object payload = ((List<?>) type).get(1);
      ImmutableList.Builder<Object> cols = ImmutableList.builder();
      for (Object pair : (List<?>) payload) {
        cols.add(pair);
      }
      return cols.build();
    }
    return ImmutableList.of(of(synthName, type));
  }

  /** Returns the codomain of a reified function type {@code T_FN(_, b)}. */
  private static Object codomainOfFnType(Object fnType) {
    List<?> list = (List<?>) fnType;
    if (TYPE_FN.constructor.equals(list.get(0))) {
      return ((List<?>) list.get(1)).get(1);
    }
    return fnType;
  }

  /** Concatenates two column lists in positional order. */
  private static List<Object> concatCols(
      List<Object> left, List<Object> right) {
    ImmutableList.Builder<Object> b = ImmutableList.builder();
    b.addAll(left);
    b.addAll(right);
    return b.build();
  }

  /**
   * Renames duplicate column names with {@code $N} suffixes (N = count of prior
   * occurrences). The first occurrence keeps its name; the second becomes
   * {@code name$1}, the third {@code name$2}, etc.
   */
  private static List<Object> dedupCols(List<Object> cols) {
    Map<String, Integer> seen = new LinkedHashMap<>();
    ImmutableList.Builder<Object> b = ImmutableList.builder();
    for (Object col : cols) {
      List<?> pair = (List<?>) col;
      String name = (String) pair.get(0);
      Integer count = seen.get(name);
      if (count == null) {
        seen.put(name, 1);
        b.add(col);
      } else {
        seen.put(name, count + 1);
        b.add(of(name + "$" + count, pair.get(1)));
      }
    }
    return b.build();
  }

  /**
   * Returns whether the reified expression's collection type is {@code list}
   * (rather than {@code bag}). Used by JOIN to compute its output kind via the
   * {@code list ⋈ list = list, otherwise bag} meet rule.
   */
  public static boolean isList(
      Object value, TypeSystem typeSystem, Environment env) {
    Object t = typeOfReified(value, typeSystem, env);
    return t instanceof List
        && !((List<?>) t).isEmpty()
        && TYPE_LIST.constructor.equals(((List<?>) t).get(0));
  }

  /** Returns a {@code T_LIST}'s or {@code T_BAG}'s element type. */
  private static Object elementOfCollection(Object collectionType) {
    List<?> list = (List<?>) collectionType;
    String tag = (String) list.get(0);
    if (TYPE_LIST.constructor.equals(tag) || TYPE_BAG.constructor.equals(tag)) {
      return list.get(1);
    }
    throw new IllegalStateException(
        "expected list or bag type; got " + collectionType);
  }

  /** Wraps {@code elementType} in {@code T_LIST} (ordered) or {@code T_BAG}. */
  private static Object collectionOfKind(boolean ordered, Object elementType) {
    return of(
        ordered ? TYPE_LIST.constructor : TYPE_BAG.constructor, elementType);
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
    // Apply each rule at most once per node, in order. The result of one
    // rule is passed to the next. This guarantees termination even for
    // rules that would otherwise cycle (e.g. swapping operands of a
    // commutative operator); callers wanting fixpoint behavior can invoke
    // Plan.optimize repeatedly.
    for (Object rule : rules) {
      Object result = evaluator.evaluate(rule, node);
      List<?> resultList = (List<?>) result;
      if ("SOME".equals(resultList.get(0))) {
        node = resultList.get(1);
      }
    }
    return node;
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

    /**
     * Inverse of {@code rewriteToPositional}: substitutes references to a
     * positional root ($0 or $1) back to the lexical scan-variable names. For
     * an atomized 1-binding chain, {@code VAR(root, t)} becomes {@code
     * VAR(name, t)}. For an N-binding chain, {@code FIELD(VAR(root, _), "k",
     * t)} becomes {@code VAR(bindings[k-1].name, t)} (1-based ordinal).
     */
    private Object substituteRootToLexical(
        Object expr, List<Binding> bindings, String rootName) {
      if (bindings.isEmpty()) {
        return expr;
      }
      return walkSubstitute(expr, bindings, rootName);
    }

    private Object walkSubstitute(
        Object expr, List<Binding> bindings, String rootName) {
      if (!(expr instanceof List)) {
        return expr;
      }
      List<?> list = (List<?>) expr;
      if (list.isEmpty()) {
        return expr;
      }
      Object tag = list.get(0);
      // FIELD(VAR(root, _), "name", T): the field name in the IR IS the
      // scan-variable name, so the substitution is simply VAR(name, T).
      if ("FIELD".equals(tag)
          && list.size() > 1
          && list.get(1) instanceof List) {
        List<?> payload = (List<?>) list.get(1);
        if (payload.size() >= 3
            && isVarOf(payload.get(0), rootName)
            && payload.get(1) instanceof String) {
          String fieldName = (String) payload.get(1);
          for (Binding b : bindings) {
            if (b.id.name.equals(fieldName)) {
              return of(
                  CORE_EXPR_VAR.constructor, of(fieldName, payload.get(2)));
            }
          }
        }
      }
      // Recurse.
      ImmutableList.Builder<Object> b = ImmutableList.builder();
      boolean changed = false;
      for (Object child : list) {
        Object newChild = walkSubstitute(child, bindings, rootName);
        if (newChild != child) {
          changed = true;
        }
        b.add(newChild);
      }
      return changed ? b.build() : expr;
    }

    private static boolean isVarOf(Object expr, String name) {
      if (!(expr instanceof List)) {
        return false;
      }
      List<?> list = (List<?>) expr;
      if (list.size() < 2 || !"VAR".equals(list.get(0))) {
        return false;
      }
      Object payload = list.get(1);
      if (!(payload instanceof List)) {
        return false;
      }
      List<?> pl = (List<?>) payload;
      return !pl.isEmpty() && name.equals(pl.get(0));
    }

    Core.Exp unreifyExp(Object value) {
      final List<?> list = asList(value);
      final String tag = (String) list.get(0);
      if (tag.equals("UNIT_LITERAL")) {
        return core.unitLiteral();
      }

      final Object payload = list.get(1);
      switch (tag) {
          // lint: sort until '#default:' where '#case '
        case "AND":
          return unreifyAndOr(payload, BuiltIn.Z_ANDALSO);

        case "APPLY":
          {
            List<?> p = asList(payload);
            Core.Exp fn = unreifyExp(p.get(0));
            Core.Exp arg = unreifyExp(p.get(1));
            Type type = unreifyType(p.get(2));
            return core.apply(Pos.ZERO, type, fn, arg);
          }

        case "AT":
          return unreifyTypedBinary(payload, "op @");

        case "BOOL_LITERAL":
          return core.boolLiteral((Boolean) payload);

        case "CASE":
          return unreifyCase(payload);

        case "CHAR_LITERAL":
          return core.charLiteral((Character) payload);

        case "CONCAT":
          return unreifyUntypedBinary(payload, "op ^", PrimitiveType.STRING);

        case "CONS":
          return unreifyTypedBinary(payload, "op ::");

        case "DIV":
          return unreifyTypedBinary(payload, "op div");

        case "DIVIDE":
          return unreifyTypedBinary(payload, "op /");

        case "E_RECORD":
          return unreifyRecord(payload);

        case "ELEM":
          return unreifyUntypedBinary(payload, "op elem", PrimitiveType.BOOL);

        case "EQUALS":
          return unreifyUntypedBinary(payload, "op =", PrimitiveType.BOOL);

        case "EXCEPT":
          return unreifyFromChain(value);

        case "FIELD":
          return unreifyField(payload);

        case "FILTER":
          return unreifyFromChain(value);

        case "FN":
          return unreifyFn(payload);

        case "GE":
          return unreifyUntypedBinary(payload, "op >=", PrimitiveType.BOOL);

        case "GROUP":
          return unreifyFromChain(value);

        case "GT":
          return unreifyUntypedBinary(payload, "op >", PrimitiveType.BOOL);

        case "INT_LITERAL":
          return core.intLiteral(toBigDecimal(payload));

        case "INTERSECT":
          return unreifyFromChain(value);

        case "JOIN":
          return unreifyFromChain(value);

        case "LE":
          return unreifyUntypedBinary(payload, "op <=", PrimitiveType.BOOL);

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

        case "NOT_ELEM":
          return unreifyUntypedBinary(
              payload, "op notelem", PrimitiveType.BOOL);

        case "NOT_EQUALS":
          return unreifyUntypedBinary(payload, "op <>", PrimitiveType.BOOL);

        case "NOT":
          return core.apply(
              Pos.ZERO,
              PrimitiveType.BOOL,
              resolveId("not"),
              unreifyExp(payload));

        case "OR":
          return unreifyAndOr(payload, BuiltIn.Z_ORELSE);

        case "ORDER":
          return unreifyFromChain(value);

        case "PLUS":
          return unreifyTypedBinary(payload, "op +");

        case "PROJECT":
          return unreifyFromChain(value);

        case "RAISE":
          {
            List<?> p = asList(payload);
            Core.Exp raiseExp = unreifyExp(p.get(0));
            Type raiseType = unreifyType(p.get(1));
            return core.raise(Pos.ZERO, raiseType, raiseExp);
          }

        case "REAL_LITERAL":
          return core.realLiteral((Float) payload);

        case "SKIP":
          return unreifyFromChain(value);

        case "STRING_LITERAL":
          return core.stringLiteral((String) payload);

        case "TAKE":
          return unreifyFromChain(value);

        case "TIMES":
          return unreifyTypedBinary(payload, "op *");

        case "TUPLE":
          {
            List<Core.Exp> es =
                transformEager(asList(payload), this::unreifyExp);
            return core.tuple(ts, es.toArray(new Core.Exp[0]));
          }

        case "UNION":
          return unreifyFromChain(value);

        case "UNORDER":
          return unreifyFromChain(value);

        case "VAR":
          {
            List<?> p = asList(payload);
            String name = (String) p.get(0);
            return resolveId(name);
          }

        default:
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
      // The chain bottoms out at a SCAN(name, source) wrapper introduced
      // by reifyFrom; unwrap it. Older reified forms (no SCAN wrapper)
      // fall back to the type-matching heuristic for compatibility with
      // hand-written rule outputs.
      String initName;
      Core.Exp initialScan;
      if (current instanceof List
          && !((List<?>) current).isEmpty()
          && "SCAN".equals(((List<?>) current).get(0))) {
        List<?> scanPayload = asList(((List<?>) current).get(1));
        initName = (String) scanPayload.get(0);
        initialScan = unreifyExp(scanPayload.get(1));
      } else {
        initName = null;
        initialScan = unreifyExp(current);
      }
      List<List<?>> steps = new ArrayList<>(stepsDeque);

      // Pre-scan all step expressions for unbound VARs (scan-bound names).
      LinkedHashMap<String, Object> scanVars = new LinkedHashMap<>();
      for (List<?> step : steps) {
        collectScanCandidates(step, scanVars);
      }

      FromBuilder fb = core.fromBuilder(ts);
      pushScope();
      try {
        Core.IdPat initVar;
        if (initName != null) {
          initVar = core.idPat(elementType(initialScan.type), initName, 0);
          scanVars.remove(initName);
        } else {
          initVar =
              takeMatchingScanVar(scanVars, elementType(initialScan.type));
          if (initVar == null) {
            initVar = core.idPat(elementType(initialScan.type), "$scan", 0);
          }
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
      final String tag = (String) step.get(0);
      switch (tag) {
        case "UNORDER":
          fb.unorder();
          return;
      }

      final List<?> p;
      switch (tag) {
          // lint: sort until '#default:' where '#case '
        case "EXCEPT":
          p = asList(step.get(1));
          fb.except(
              (Boolean) p.get(1),
              transformEager(asList(p.get(2)), this::unreifyExp));
          break;
        case "FILTER":
          p = asList(step.get(1));
          // The reified predicate references its input as $0; substitute
          // back to the chain's bound scan variable(s) before unreifying
          // so Morel's compiler can resolve the names.
          Object filterPred =
              substituteRootToLexical(p.get(1), fb.stepEnv().bindings, "$0");
          fb.where(unreifyExp(filterPred));
          break;
        case "GROUP":
          processGroup(fb, step.get(1));
          break;
        case "INTERSECT":
          p = asList(step.get(1));
          fb.intersect(
              (Boolean) p.get(1),
              transformEager(asList(p.get(2)), this::unreifyExp));
          break;
        case "JOIN":
          {
            p = asList(step.get(1));
            // The right side of a JOIN is a SCAN(name, source) node; the
            // name gives us the join variable directly. Older bare-source
            // forms fall back to the heuristic.
            Object right = p.get(1);
            String joinName = null;
            Core.Exp rightScan;
            if (right instanceof List
                && !((List<?>) right).isEmpty()
                && "SCAN".equals(((List<?>) right).get(0))) {
              List<?> scanPayload = asList(((List<?>) right).get(1));
              joinName = (String) scanPayload.get(0);
              rightScan = unreifyExp(scanPayload.get(1));
            } else {
              rightScan = unreifyExp(right);
            }
            Core.IdPat joinVar;
            if (joinName != null) {
              joinVar = core.idPat(elementType(rightScan.type), joinName, 0);
              scanVars.remove(joinName);
            } else {
              joinVar =
                  takeMatchingScanVar(scanVars, elementType(rightScan.type));
              if (joinVar == null) {
                joinVar = core.idPat(elementType(rightScan.type), "$join", 0);
              }
            }
            // The condition references the left chain's bindings as $0
            // and the new scan as $1. Substitute back to lexical names
            // before unreifying. (Left bindings come from fb's stepEnv
            // BEFORE adding the new joinVar; right is just [joinVar].)
            List<Binding> joinLeft = fb.stepEnv().bindings;
            bindLocal(joinVar);
            Object condReified = p.get(2);
            condReified = substituteRootToLexical(condReified, joinLeft, "$0");
            condReified =
                substituteRootToLexical(
                    condReified, ImmutableList.of(Binding.of(joinVar)), "$1");
            Core.Exp cond = unreifyExp(condReified);
            fb.scan(joinVar, rightScan, cond);
            break;
          }
        case "ORDER":
          p = asList(step.get(1));
          Object orderRewritten =
              substituteRootToLexical(p.get(1), fb.stepEnv().bindings, "$0");
          fb.order(unreifyExp(orderRewritten));
          break;
        case "PROJECT":
          p = asList(step.get(1));
          Object projectRewritten =
              substituteRootToLexical(p.get(1), fb.stepEnv().bindings, "$0");
          fb.yield_(unreifyExp(projectRewritten));
          break;
        case "SKIP":
          p = asList(step.get(1));
          fb.skip(unreifyExp(p.get(1)));
          break;
        case "TAKE":
          p = asList(step.get(1));
          fb.take(unreifyExp(p.get(1)));
          break;
        case "UNION":
          p = asList(step.get(1));
          fb.union(
              (Boolean) p.get(1),
              transformEager(asList(p.get(2)), this::unreifyExp));
          break;
        default:
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
      // Pre-group bindings — the scope keys and agg arguments reference
      // via $0. Capture before any binding shifts; GROUP is the only step
      // we reach here so fb.stepEnv() reflects pre-group state.
      List<Binding> preGroup = fb.stepEnv().bindings;
      ImmutableSortedMap.Builder<Core.IdPat, Core.Exp> keyMap =
          ImmutableSortedMap.naturalOrder();
      for (Object k : keys) {
        List<?> pair = asList(k);
        String name = (String) pair.get(0);
        Object keyRewritten =
            substituteRootToLexical(pair.get(1), preGroup, "$0");
        Core.Exp ke = unreifyExp(keyRewritten);
        keyMap.put(core.idPat(ke.type, name, 0), ke);
      }
      ImmutableSortedMap.Builder<Core.IdPat, Core.Aggregate> aggMap =
          ImmutableSortedMap.naturalOrder();
      for (Object a : aggs) {
        List<?> pair = asList(a);
        String name = (String) pair.get(0);
        Core.Exp ae = unreifyExp(pair.get(1));
        Object argReified = pair.get(2);
        Core.Exp argExp;
        List<?> argList = asList(argReified);
        if ("SOME".equals(argList.get(0))) {
          Object argRewritten =
              substituteRootToLexical(argList.get(1), preGroup, "$0");
          argExp = unreifyExp(argRewritten);
        } else {
          argExp = null;
        }
        Core.Aggregate agg =
            core.aggregate(((FnType) ae.type).resultType, ae, argExp);
        aggMap.put(core.idPat(agg.type, name, 0), agg);
      }
      fb.group(false, keyMap.build(), aggMap.build());
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
        return core.id(b.id);
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
      final List<?> list = asList(value);
      final String tag = (String) list.get(0);
      final List<?> p;
      switch (tag) {
          // lint: sort until '#default:' where '#case '
        case "P_AS":
          {
            p = asList(list.get(1));
            String name = (String) p.get(0);
            Core.Pat inner = unreifyPat(p.get(1));
            Type asType = unreifyType(p.get(2));
            return core.asPat(asType, name, 0, inner);
          }

        case "P_BOOL_LIT":
          return core.literalPat(
              Op.BOOL_LITERAL_PAT, PrimitiveType.BOOL, (Boolean) list.get(1));

        case "P_CHAR_LIT":
          return core.literalPat(
              Op.CHAR_LITERAL_PAT, PrimitiveType.CHAR, (Character) list.get(1));

        case "P_CON":
          {
            p = asList(list.get(1));
            String tyCon = (String) p.get(0);
            Core.Pat inner = unreifyPat(p.get(1));
            Type conType = unreifyType(p.get(2));
            return core.conPat(conType, tyCon, inner);
          }

        case "P_CON0":
          {
            p = asList(list.get(1));
            String tyCon = (String) p.get(0);
            Type conType = unreifyType(p.get(1));
            return core.con0Pat((DataType) conType, tyCon);
          }

        case "P_CONS":
          {
            p = asList(list.get(1));
            Core.Pat head = unreifyPat(p.get(0));
            Core.Pat tail = unreifyPat(p.get(1));
            Type consType = unreifyType(p.get(2));
            return core.consPat(
                consType, "::", core.tuplePat(ts, of(head, tail)));
          }

        case "P_INT_LIT":
          return core.literalPat(
              Op.INT_LITERAL_PAT, PrimitiveType.INT, toBigDecimal(list.get(1)));

        case "P_LIST":
          {
            p = asList(list.get(1));
            Type listType = unreifyType(p.get(1));
            return core.listPat(
                listType, transformEager(asList(p.get(0)), this::unreifyPat));
          }

        case "P_REAL_LIT":
          return core.literalPat(
              Op.REAL_LITERAL_PAT, PrimitiveType.REAL, (Float) list.get(1));

        case "P_RECORD":
          {
            p = asList(list.get(1));
            final List<Core.Pat> sub = new ArrayList<>();
            final PairList<String, Type> nameTypes =
                PairList.fromTransformed(
                    p,
                    (o, consumer) -> {
                      final List<?> pair = asList(o);
                      final String name = (String) pair.get(0);
                      final Core.Pat patArg = unreifyPat(pair.get(1));
                      sub.add(patArg);
                      consumer.accept(name, patArg.type);
                    });
            RecordType recType = (RecordType) ts.recordType(nameTypes);
            return core.recordPat(recType, sub);
          }

        case "P_STRING_LIT":
          return core.literalPat(
              Op.STRING_LITERAL_PAT,
              PrimitiveType.STRING,
              (String) list.get(1));

        case "P_TUPLE":
          return core.tuplePat(
              ts, transformEager(asList(list.get(1)), this::unreifyPat));

        case "P_VAR":
          {
            p = asList(list.get(1));
            String name = (String) p.get(0);
            Type type = unreifyType(p.get(1));
            return core.idPat(type, name, 0);
          }

        case "P_WILD":
          return core.wildcardPat(unreifyType(list.get(1)));

        default:
          throw new UnsupportedOperationException(
              "Plan.transform: cannot yet unreify pat " + tag);
      }
    }

    Type unreifyType(Object value) {
      final List<?> list = asList(value);
      final String tag = (String) list.get(0);
      final List<?> p;
      switch (tag) {
          // lint: sort until '#}' where '#case '
        case "T_BAG":
          return ts.bagType(unreifyType(list.get(1)));

        case "T_BOOL":
          return PrimitiveType.BOOL;

        case "T_CHAR":
          return PrimitiveType.CHAR;

        case "T_DATA":
          {
            p = asList(list.get(1));
            Type base = ts.lookup((String) p.get(0));
            List<?> args = asList(p.get(1));
            if (args.isEmpty()) {
              return base;
            }
            return ts.apply(base, transformEager(args, this::unreifyType));
          }

        case "T_FN":
          {
            p = asList(list.get(1));
            return ts.fnType(unreifyType(p.get(0)), unreifyType(p.get(1)));
          }

        case "T_INT":
          return PrimitiveType.INT;

        case "T_LIST":
          return ts.listType(unreifyType(list.get(1)));

        case "T_REAL":
          return PrimitiveType.REAL;

        case "T_RECORD":
          @SuppressWarnings("unchecked")
          final List<List<?>> fields = (List<List<?>>) list.get(1);
          return ts.recordType(
              PairList.fromTransformed(
                  fields,
                  (o, consumer) ->
                      consumer.accept(
                          (String) o.get(0), unreifyType(o.get(1)))));

        case "T_STRING":
          return PrimitiveType.STRING;

        case "T_TUPLE":
          return ts.tupleType(
              transformEager(asList(list.get(1)), this::unreifyType));

        case "T_UNIT":
          return PrimitiveType.UNIT;

        case "T_VAR":
          return ts.typeVariable(((Number) list.get(1)).intValue());

        default:
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
