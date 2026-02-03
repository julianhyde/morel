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

import static net.hydromatic.morel.ast.AstBuilder.ast;
import static net.hydromatic.morel.util.Pretty.EMPTY;
import static net.hydromatic.morel.util.Pretty.LINE;
import static net.hydromatic.morel.util.Pretty.LINE_BREAK;
import static net.hydromatic.morel.util.Pretty.align;
import static net.hydromatic.morel.util.Pretty.beside;
import static net.hydromatic.morel.util.Pretty.encloseSep;
import static net.hydromatic.morel.util.Pretty.group;
import static net.hydromatic.morel.util.Pretty.hsep;
import static net.hydromatic.morel.util.Pretty.nest;
import static net.hydromatic.morel.util.Pretty.punctuate;
import static net.hydromatic.morel.util.Pretty.sep;
import static net.hydromatic.morel.util.Pretty.text;
import static net.hydromatic.morel.util.Pretty.vsep;

import com.google.common.collect.ImmutableList;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.util.Pretty.Doc;

/** Converts Morel AST nodes to {@link Doc} values for pretty-printing. */
public class AstToDoc {
  private AstToDoc() {}

  /** Converts any AstNode to a Doc. */
  public static Doc toDoc(AstNode node) {
    return toDoc(node, 0, 0);
  }

  /** Converts with precedence context (for parenthesization). */
  static Doc toDoc(AstNode node, int left, int right) {
    // Patterns
    if (node instanceof Ast.IdPat) {
      return text(((Ast.IdPat) node).name);
    } else if (node instanceof Ast.LiteralPat) {
      return text(formatLiteral(((Ast.LiteralPat) node).value));
    } else if (node instanceof Ast.WildcardPat) {
      return text("_");
    } else if (node instanceof Ast.InfixPat) {
      final Ast.InfixPat pat = (Ast.InfixPat) node;
      return infix(left, pat.p0, pat.op, pat.p1, right);
    } else if (node instanceof Ast.ConPat) {
      final Ast.ConPat pat = (Ast.ConPat) node;
      return infix(left, pat.tyCon, pat.op, pat.pat, right);
    } else if (node instanceof Ast.AsPat) {
      final Ast.AsPat pat = (Ast.AsPat) node;
      return infix(left, pat.id, pat.op, pat.pat, right);
    } else if (node instanceof Ast.Con0Pat) {
      return toDoc(((Ast.Con0Pat) node).tyCon, left, right);
    } else if (node instanceof Ast.TuplePat) {
      return tupleDoc(patDocs(((Ast.TuplePat) node).args));
    } else if (node instanceof Ast.ListPat) {
      return listDoc(patDocs(((Ast.ListPat) node).args));
    } else if (node instanceof Ast.RecordPat) {
      return recordPatDoc((Ast.RecordPat) node);
    } else if (node instanceof Ast.AnnotatedPat) {
      final Ast.AnnotatedPat pat = (Ast.AnnotatedPat) node;
      return infix(left, pat.pat, pat.op, pat.type, right);

      // Types
    } else if (node instanceof Ast.NamedType) {
      return namedTypeDoc((Ast.NamedType) node, left);
    } else if (node instanceof Ast.TyVar) {
      return text(((Ast.TyVar) node).name);
    } else if (node instanceof Ast.RecordType) {
      return recordTypeDoc((Ast.RecordType) node);
    } else if (node instanceof Ast.TupleType) {
      return tupleTypeDoc((Ast.TupleType) node, left, right);
    } else if (node instanceof Ast.FunctionType) {
      final Ast.FunctionType t = (Ast.FunctionType) node;
      return infix(left, t.paramType, t.op, t.resultType, right);
    } else if (node instanceof Ast.CompositeType) {
      return tupleDoc(typeDocs(((Ast.CompositeType) node).types));
    } else if (node instanceof Ast.ExpressionType) {
      final Ast.ExpressionType t = (Ast.ExpressionType) node;
      return beside(text(t.op.padded), toDoc(t.exp, t.op.right, right));

      // Expressions
    } else if (node instanceof Ast.Id) {
      return text(((Ast.Id) node).name);
    } else if (node instanceof Ast.OpSection) {
      return hsep(
          ImmutableList.of(text("op"), text(((Ast.OpSection) node).name)));
    } else if (node instanceof Ast.Current) {
      return text("current");
    } else if (node instanceof Ast.Elements) {
      return text("elements");
    } else if (node instanceof Ast.Ordinal) {
      return text("ordinal");
    } else if (node instanceof Ast.RecordSelector) {
      return text("#" + ((Ast.RecordSelector) node).name);
    } else if (node instanceof Ast.Literal) {
      return text(formatLiteral(((Ast.Literal) node).value));
    } else if (node instanceof Ast.Tuple) {
      return tupleDoc(expDocs(((Ast.Tuple) node).args));
    } else if (node instanceof Ast.ListExp) {
      return listDoc(expDocs(((Ast.ListExp) node).args));
    } else if (node instanceof Ast.Record) {
      return recordDoc((Ast.Record) node);
    } else if (node instanceof Ast.InfixCall) {
      final Ast.InfixCall call = (Ast.InfixCall) node;
      return infix(left, call.a0, call.op, call.a1, right);
    } else if (node instanceof Ast.PrefixCall) {
      final Ast.PrefixCall call = (Ast.PrefixCall) node;
      return prefixDoc(left, call.op, call.a, right);
    } else if (node instanceof Ast.If) {
      return ifDoc((Ast.If) node);
    } else if (node instanceof Ast.Let) {
      return letDoc((Ast.Let) node);
    } else if (node instanceof Ast.Fn) {
      return fnDoc((Ast.Fn) node, right);
    } else if (node instanceof Ast.Case) {
      return caseDoc((Ast.Case) node, left, right);
    } else if (node instanceof Ast.Apply) {
      return applyDoc((Ast.Apply) node, left, right);
    } else if (node instanceof Ast.AnnotatedExp) {
      final Ast.AnnotatedExp ann = (Ast.AnnotatedExp) node;
      return infix(left, ann.exp, ann.op, ann.type, right);
    } else if (node instanceof Ast.Aggregate) {
      return aggregateDoc((Ast.Aggregate) node);
    } else if (node instanceof Ast.From) {
      return queryDoc((Ast.Query) node, left, right);
    } else if (node instanceof Ast.Exists) {
      return queryDoc((Ast.Query) node, left, right);
    } else if (node instanceof Ast.Forall) {
      return queryDoc((Ast.Query) node, left, right);

      // Declarations
    } else if (node instanceof Ast.ValDecl) {
      return valDeclDoc((Ast.ValDecl) node, right);
    } else if (node instanceof Ast.FunDecl) {
      return funDeclDoc((Ast.FunDecl) node);
    } else if (node instanceof Ast.TypeDecl) {
      return typeDeclDoc((Ast.TypeDecl) node);
    } else if (node instanceof Ast.DatatypeDecl) {
      return datatypeDeclDoc((Ast.DatatypeDecl) node);
    } else if (node instanceof Ast.SignatureDecl) {
      return signatureDeclDoc((Ast.SignatureDecl) node);
    } else if (node instanceof Ast.OverDecl) {
      return beside(text("over "), text(((Ast.OverDecl) node).pat.name));

      // Bindings
    } else if (node instanceof Ast.ValBind) {
      return valBindDoc((Ast.ValBind) node, right);
    } else if (node instanceof Ast.FunBind) {
      return funBindDoc((Ast.FunBind) node);
    } else if (node instanceof Ast.FunMatch) {
      return funMatchDoc((Ast.FunMatch) node);
    } else if (node instanceof Ast.Match) {
      return matchDoc((Ast.Match) node, right);
    } else if (node instanceof Ast.TypeBind) {
      return typeBindDoc((Ast.TypeBind) node);
    } else if (node instanceof Ast.DatatypeBind) {
      return datatypeBindDoc((Ast.DatatypeBind) node);
    } else if (node instanceof Ast.TyCon) {
      return tyConDoc((Ast.TyCon) node, left, right);
    } else if (node instanceof Ast.SignatureBind) {
      return signatureBindDoc((Ast.SignatureBind) node);

      // Specs
    } else if (node instanceof Ast.ValSpec) {
      return valSpecDoc((Ast.ValSpec) node);
    } else if (node instanceof Ast.TypeSpec) {
      return typeSpecDoc((Ast.TypeSpec) node);
    } else if (node instanceof Ast.DatatypeSpec) {
      return datatypeSpecDoc((Ast.DatatypeSpec) node);
    } else if (node instanceof Ast.ExceptionSpec) {
      return exceptionSpecDoc((Ast.ExceptionSpec) node);

      // From steps
    } else if (node instanceof Ast.Scan) {
      return scanDoc((Ast.Scan) node);
    } else if (node instanceof Ast.Where) {
      return beside(text("where "), toDoc(((Ast.Where) node).exp, 0, 0));
    } else if (node instanceof Ast.Yield) {
      return beside(text("yield "), toDoc(((Ast.Yield) node).exp, 0, 0));
    } else if (node instanceof Ast.Order) {
      return beside(text("order "), toDoc(((Ast.Order) node).exp, 0, 0));
    } else if (node instanceof Ast.Compute) {
      final Ast.Compute compute = (Ast.Compute) node;
      return beside(text("compute "), toDoc(compute.aggregate, 0, 0));
    } else if (node instanceof Ast.Group) {
      return groupDoc((Ast.Group) node);
    } else if (node instanceof Ast.Skip) {
      return beside(text("skip "), toDoc(((Ast.Skip) node).exp, 0, 0));
    } else if (node instanceof Ast.Take) {
      return beside(text("take "), toDoc(((Ast.Take) node).exp, 0, 0));
    } else if (node instanceof Ast.Into) {
      return beside(text("into "), toDoc(((Ast.Into) node).exp, 0, 0));
    } else if (node instanceof Ast.Through) {
      final Ast.Through through = (Ast.Through) node;
      return beside(
          text("through "),
          beside(
              toDoc(through.pat, 0, 0),
              beside(text(" in "), toDoc(through.exp, 0, 0))));
    } else if (node instanceof Ast.Distinct) {
      return text("distinct");
    } else if (node instanceof Ast.Unorder) {
      return text("unorder");
    } else if (node instanceof Ast.Require) {
      return beside(text("require "), toDoc(((Ast.Require) node).exp, 0, 0));
    } else if (node instanceof Ast.SetStep) {
      return setStepDoc((Ast.SetStep) node);
    } else {
      // Fallback: use the AstWriter
      return text(node.unparse(new AstWriter(), left, right).toString());
    }
  }

  // -- Helpers ----------------------------------------------------------------

  /** Wraps in parens if needed. */
  static Doc maybeParens(boolean need, Doc doc) {
    return need ? beside(text("("), beside(doc, text(")"))) : doc;
  }

  /** Infix operator with line-break-after-operator. */
  static Doc infix(int left, AstNode a0, Op op, AstNode a1, int right) {
    // Special case for APPLY with operator name (matches AstWriter.infix)
    if (op == Op.APPLY && a0.op == Op.ID && a0 instanceof Ast.Id) {
      final Op op2 = Op.BY_OP_NAME.get(((Ast.Id) a0).name);
      if (op2 != null && op2.left > 0 && a1 instanceof Ast.Tuple) {
        final List<Ast.Exp> args = ((Ast.Tuple) a1).args;
        if (args.size() == 2) {
          return infix(left, args.get(0), op2, args.get(1), right);
        }
      }
    }
    final boolean needParens = left > op.left || op.right < right;
    if (needParens) {
      left = 0;
      right = 0;
    }
    final Doc inner =
        group(
            beside(
                toDoc(a0, left, op.left),
                beside(
                    text(op.padded),
                    nest(2, beside(LINE_BREAK, toDoc(a1, op.right, right))))));
    return maybeParens(needParens, inner);
  }

  /** Prefix operator. */
  static Doc prefixDoc(int left, Op op, AstNode a, int right) {
    final boolean needParens = left > op.left || op.right < right;
    if (needParens) {
      right = 0;
    }
    final Doc inner = beside(text(op.padded), toDoc(a, op.right, right));
    return maybeParens(needParens, inner);
  }

  /** List of docs from a list of patterns. */
  private static List<Doc> patDocs(List<? extends Ast.Pat> args) {
    final List<Doc> docs = new ArrayList<>();
    for (Ast.Pat arg : args) {
      docs.add(toDoc(arg, 0, 0));
    }
    return docs;
  }

  /** List of docs from a list of expressions. */
  private static List<Doc> expDocs(List<? extends Ast.Exp> args) {
    final List<Doc> docs = new ArrayList<>();
    for (Ast.Exp arg : args) {
      docs.add(toDoc(arg, 0, 0));
    }
    return docs;
  }

  /** List of docs from a list of types. */
  private static List<Doc> typeDocs(List<? extends Ast.Type> args) {
    final List<Doc> docs = new ArrayList<>();
    for (Ast.Type arg : args) {
      docs.add(toDoc(arg, 0, 0));
    }
    return docs;
  }

  /** Tuple: {@code (a, b, c)}. */
  private static Doc tupleDoc(List<Doc> docs) {
    return encloseSep(text("("), text(")"), text(","), docs);
  }

  /** List: {@code [a, b, c]}. */
  private static Doc listDoc(List<Doc> docs) {
    return encloseSep(text("["), text("]"), text(","), docs);
  }

  /** Record expression: {@code {x = 1, y = 2}}. */
  private static Doc recordDoc(Ast.Record record) {
    final List<Doc> fieldDocs = new ArrayList<>();
    if (record.with != null) {
      // First field doc is "exp with field1 = val1"
      // Actually, we prefix "exp with " before the fields
    }
    record.args.forEachIndexed(
        (i, k, v) -> {
          final Doc valueDoc = toDoc(v, 0, 0);
          if (!(k.name.isEmpty() || k.name.equals(ast.implicitLabelOpt(v)))) {
            fieldDocs.add(beside(text(k.name + " = "), valueDoc));
          } else {
            fieldDocs.add(valueDoc);
          }
        });
    Doc fieldsDoc = encloseSep(text("{"), text("}"), text(","), fieldDocs);
    if (record.with != null) {
      // Wrap: {exp with fields}
      final List<Doc> allDocs = new ArrayList<>();
      allDocs.add(beside(toDoc(record.with, 0, 0), text(" with")));
      allDocs.addAll(fieldDocs);
      fieldsDoc = encloseSep(text("{"), text("}"), text(","), allDocs);
    }
    return fieldsDoc;
  }

  /** Record pattern: {@code {x = p1, y = p2, ...}}. */
  private static Doc recordPatDoc(Ast.RecordPat pat) {
    final List<Doc> fieldDocs = new ArrayList<>();
    for (Map.Entry<String, Ast.Pat> entry : pat.args.entrySet()) {
      fieldDocs.add(
          beside(text(entry.getKey() + " = "), toDoc(entry.getValue(), 0, 0)));
    }
    if (pat.ellipsis) {
      fieldDocs.add(text("..."));
    }
    return encloseSep(text("{"), text("}"), text(","), fieldDocs);
  }

  /** Record type: {@code {x: int, y: string}}. */
  private static Doc recordTypeDoc(Ast.RecordType type) {
    final List<Doc> fieldDocs = new ArrayList<>();
    for (Map.Entry<String, Ast.Type> entry : type.fieldTypes.entrySet()) {
      fieldDocs.add(
          beside(text(entry.getKey() + ": "), toDoc(entry.getValue(), 0, 0)));
    }
    return encloseSep(text("{"), text("}"), text(","), fieldDocs);
  }

  /** Tuple type: {@code a * b * c}. Non-associative. */
  private static Doc tupleTypeDoc(Ast.TupleType type, int left, int right) {
    final List<Doc> docs = new ArrayList<>();
    for (Ast.Type t : type.types) {
      // "*" is non-associative. Elevate both left and right precedence
      // to force parentheses if the inner expression is also "*".
      docs.add(toDoc(t, Op.TUPLE_TYPE.left + 1, Op.TUPLE_TYPE.right + 1));
    }
    final Doc inner = group(hsep(punctuate(text(" *"), docs)));
    return maybeParens(
        left > Op.TUPLE_TYPE.left || Op.TUPLE_TYPE.right < right, inner);
  }

  /** Named type: {@code int}, {@code int list}, {@code (int, string) list}. */
  private static Doc namedTypeDoc(Ast.NamedType type, int left) {
    switch (type.types.size()) {
      case 0:
        return text(type.name);
      case 1:
        return beside(
            toDoc(type.types.get(0), left, type.op.left),
            text(" " + type.name));
      default:
        final List<Doc> docs = typeDocs(type.types);
        return beside(tupleDoc(docs), text(" " + type.name));
    }
  }

  /** If-then-else. */
  private static Doc ifDoc(Ast.If ifExp) {
    return group(
        beside(
            text("if "),
            beside(
                toDoc(ifExp.condition, 0, 0),
                beside(
                    nest(2, beside(LINE, text("then "))),
                    beside(
                        toDoc(ifExp.ifTrue, 0, 0),
                        beside(
                            nest(2, beside(LINE, text("else "))),
                            toDoc(ifExp.ifFalse, 0, 0)))))));
  }

  /** Let-in-end. */
  private static Doc letDoc(Ast.Let let) {
    final List<Doc> declDocs = new ArrayList<>();
    for (Ast.Decl decl : let.decls) {
      declDocs.add(toDoc(decl, 0, 0));
    }
    final Doc declsDoc = vsep(punctuate(text(";"), declDocs));
    return group(
        beside(
            text("let"),
            beside(
                nest(2, beside(LINE, declsDoc)),
                beside(
                    LINE,
                    beside(
                        text("in"),
                        beside(
                            nest(2, beside(LINE, toDoc(let.exp, 0, 0))),
                            beside(LINE, text("end"))))))));
  }

  /** Fn (lambda). */
  private static Doc fnDoc(Ast.Fn fn, int right) {
    return beside(text("fn "), group(align(matchListDoc(fn.matchList, right))));
  }

  /** Case expression. */
  private static Doc caseDoc(Ast.Case caseExp, int left, int right) {
    return group(
        beside(
            text("case "),
            beside(
                toDoc(caseExp.exp, 0, 0),
                beside(
                    text(" of"),
                    nest(
                        2,
                        beside(
                            LINE, matchListDoc(caseExp.matchList, right)))))));
  }

  /** Match arms joined by {@code " | "}. */
  private static Doc matchListDoc(List<Ast.Match> matchList, int right) {
    final List<Doc> docs = new ArrayList<>();
    for (int i = 0; i < matchList.size(); i++) {
      final Doc m = matchDoc(matchList.get(i), right);
      if (i > 0) {
        docs.add(beside(text("| "), m));
      } else {
        docs.add(m);
      }
    }
    return vsep(docs);
  }

  /** Single match arm: {@code pat => exp}. */
  private static Doc matchDoc(Ast.Match match, int right) {
    return beside(
        toDoc(match.pat, 0, 0),
        beside(text(" => "), toDoc(match.exp, 0, right)));
  }

  /** Apply (function application). */
  private static Doc applyDoc(Ast.Apply apply, int left, int right) {
    // Special case: when fn is an Id whose name is an infix operator
    if (apply.fn.op == Op.ID && apply.fn instanceof Ast.Id) {
      final Op op2 = Op.BY_OP_NAME.get(((Ast.Id) apply.fn).name);
      if (op2 != null && op2.left > 0 && apply.arg instanceof Ast.Tuple) {
        final List<Ast.Exp> args = ((Ast.Tuple) apply.arg).args;
        if (args.size() == 2) {
          return infix(left, args.get(0), op2, args.get(1), right);
        }
      }
    }
    final boolean needParens = left > Op.APPLY.left || Op.APPLY.right < right;
    if (needParens) {
      left = 0;
      right = 0;
    }
    final Doc inner =
        group(
            beside(
                toDoc(apply.fn, left, Op.APPLY.left),
                nest(
                    2, beside(LINE, toDoc(apply.arg, Op.APPLY.right, right)))));
    return maybeParens(needParens, inner);
  }

  /** Aggregate: {@code sum over e}. */
  private static Doc aggregateDoc(Ast.Aggregate agg) {
    return group(
        beside(
            toDoc(agg.aggregate, 0, 0),
            beside(text(" over "), toDoc(agg.argument, 0, 0))));
  }

  /** From/Exists/Forall query. */
  private static Doc queryDoc(Ast.Query query, int left, int right) {
    if (left > query.op.left || query.op.right < right) {
      return beside(text("("), beside(queryDoc(query, 0, 0), text(")")));
    }
    final List<Doc> stepDocs = new ArrayList<>();
    for (int i = 0; i < query.steps.size(); i++) {
      final Ast.FromStep step = query.steps.get(i);
      if (step.op == Op.SCAN && i > 0) {
        if (query.steps.get(i - 1).op == Op.SCAN) {
          // Comma-separated scans
          stepDocs.add(beside(text(", "), toDoc(step, 0, 0)));
          continue;
        } else {
          stepDocs.add(beside(text("join "), scanDocInner((Ast.Scan) step)));
          continue;
        }
      }
      stepDocs.add(toDoc(step, 0, 0));
    }
    final Doc stepsDoc = vsep(stepDocs);
    return group(
        beside(text(query.op.lowerName()), nest(2, beside(LINE, stepsDoc))));
  }

  /** Scan step. */
  private static Doc scanDoc(Ast.Scan scan) {
    return scanDocInner(scan);
  }

  /** Inner scan doc (without leading space). */
  private static Doc scanDocInner(Ast.Scan scan) {
    Doc doc = toDoc(scan.pat, 0, 0);
    if (scan.exp != null) {
      if (scan.exp.op == Op.FROM_EQ) {
        doc =
            beside(
                doc,
                beside(
                    text(" = "),
                    toDoc(((Ast.PrefixCall) scan.exp).a, Op.EQ.right, 0)));
      } else {
        doc =
            beside(doc, beside(text(" in "), toDoc(scan.exp, Op.EQ.right, 0)));
      }
    }
    if (scan.condition != null) {
      doc = beside(doc, beside(text(" on "), toDoc(scan.condition, 0, 0)));
    }
    return doc;
  }

  /** Group step. */
  private static Doc groupDoc(Ast.Group group) {
    Doc doc = beside(text("group "), toDoc(group.group, 0, 0));
    if (group.aggregate != null) {
      doc =
          beside(
              doc,
              beside(
                  LINE,
                  beside(text("compute "), toDoc(group.aggregate, 0, 0))));
    }
    return doc;
  }

  /** Set step (union, intersect, except). */
  private static Doc setStepDoc(Ast.SetStep step) {
    final List<Doc> docs = new ArrayList<>();
    for (int i = 0; i < step.args.size(); i++) {
      final Doc expDoc = toDoc(step.args.get(i), Op.EQ.right, 0);
      final String prefix;
      if (i == 0) {
        prefix = step.op.padded.trim() + " ";
      } else {
        prefix = ", ";
      }
      docs.add(
          beside(
              text(prefix),
              step.distinct ? beside(text("distinct "), expDoc) : expDoc));
    }
    return vsep(docs);
  }

  // -- Declarations -----------------------------------------------------------

  /** Val declaration: {@code val [rec] [inst] pat = exp}. */
  private static Doc valDeclDoc(Ast.ValDecl decl, int right) {
    final String keyword =
        decl.rec
            ? (decl.inst ? "val rec inst " : "val rec ")
            : (decl.inst ? "val inst " : "val ");
    final List<Doc> bindDocs = new ArrayList<>();
    for (int i = 0; i < decl.valBinds.size(); i++) {
      final Doc bind = valBindDoc(decl.valBinds.get(i), right);
      if (i == 0) {
        bindDocs.add(beside(text(keyword), bind));
      } else {
        bindDocs.add(beside(text("and "), bind));
      }
    }
    return vsep(bindDocs);
  }

  /** Val bind: {@code pat = exp}. */
  private static Doc valBindDoc(Ast.ValBind valBind, int right) {
    return group(
        beside(
            toDoc(valBind.pat, 0, Op.EQ.left),
            beside(
                text(" ="),
                nest(
                    2, beside(LINE, toDoc(valBind.exp, Op.EQ.right, right))))));
  }

  /** Fun declaration: {@code fun f x = ...}. */
  private static Doc funDeclDoc(Ast.FunDecl decl) {
    final List<Doc> bindDocs = new ArrayList<>();
    for (int i = 0; i < decl.funBinds.size(); i++) {
      final Doc bind = funBindDoc(decl.funBinds.get(i));
      if (i == 0) {
        bindDocs.add(beside(text("fun "), bind));
      } else {
        bindDocs.add(beside(text("and "), bind));
      }
    }
    return vsep(bindDocs);
  }

  /** Fun bind: match clauses joined by {@code " | "}. */
  private static Doc funBindDoc(Ast.FunBind funBind) {
    if (funBind.matchList.size() == 1) {
      return funMatchDoc(funBind.matchList.get(0));
    }
    Doc result = funMatchDoc(funBind.matchList.get(0));
    for (int i = 1; i < funBind.matchList.size(); i++) {
      final Doc m = funMatchDoc(funBind.matchList.get(i));
      // Flat: "clause1 | clause2"; broken: "clause1\n  | clause2"
      result = beside(result, nest(2, beside(LINE, beside(text("| "), m))));
    }
    return group(result);
  }

  /** Fun match: {@code name pat ... = exp}. */
  private static Doc funMatchDoc(Ast.FunMatch funMatch) {
    final List<Doc> patDocs = new ArrayList<>();
    for (Ast.Pat pat : funMatch.patList) {
      patDocs.add(toDoc(pat, Op.APPLY.left, Op.APPLY.right));
    }
    final Doc patsDoc =
        patDocs.isEmpty() ? EMPTY : beside(text(" "), hsep(patDocs));
    return group(
        beside(
            text(funMatch.name),
            beside(
                patsDoc,
                beside(
                    text(" ="),
                    nest(2, beside(LINE, toDoc(funMatch.exp, 0, 0)))))));
  }

  /** Type declaration: {@code type 'a t = ...}. */
  private static Doc typeDeclDoc(Ast.TypeDecl decl) {
    final List<Doc> bindDocs = new ArrayList<>();
    for (int i = 0; i < decl.binds.size(); i++) {
      final Doc bind = typeBindDoc(decl.binds.get(i));
      if (i == 0) {
        bindDocs.add(beside(text("type "), bind));
      } else {
        bindDocs.add(beside(text("and "), bind));
      }
    }
    return vsep(bindDocs);
  }

  /** Type bind: {@code 'a t = type}. */
  private static Doc typeBindDoc(Ast.TypeBind typeBind) {
    return beside(
        tyVarListDoc(typeBind.tyVars),
        beside(
            text(typeBind.name.name),
            beside(text(" = "), toDoc(typeBind.type, 0, 0))));
  }

  /** Datatype declaration: {@code datatype 'a t = ...}. */
  private static Doc datatypeDeclDoc(Ast.DatatypeDecl decl) {
    final List<Doc> bindDocs = new ArrayList<>();
    for (int i = 0; i < decl.binds.size(); i++) {
      final Doc bind = datatypeBindDoc(decl.binds.get(i));
      if (i == 0) {
        bindDocs.add(beside(text("datatype "), bind));
      } else {
        bindDocs.add(beside(text("and "), bind));
      }
    }
    return vsep(bindDocs);
  }

  /** Datatype bind: {@code 'a t = C1 of t1 | C2}. */
  private static Doc datatypeBindDoc(Ast.DatatypeBind bind) {
    final List<Doc> tyConDocs = new ArrayList<>();
    for (int i = 0; i < bind.tyCons.size(); i++) {
      final Doc tc = tyConDoc(bind.tyCons.get(i), 0, 0);
      if (i > 0) {
        tyConDocs.add(beside(text("| "), tc));
      } else {
        tyConDocs.add(tc);
      }
    }
    return beside(
        tyVarListDoc(bind.tyVars),
        beside(text(bind.name.name + " = "), sep(tyConDocs)));
  }

  /** Type constructor: {@code C} or {@code C of t}. */
  private static Doc tyConDoc(Ast.TyCon tyCon, int left, int right) {
    if (tyCon.type != null) {
      return beside(
          toDoc(tyCon.id, left, tyCon.op.left),
          beside(text(" of "), toDoc(tyCon.type, tyCon.op.right, right)));
    } else {
      return toDoc(tyCon.id, left, right);
    }
  }

  /** Signature declaration. */
  private static Doc signatureDeclDoc(Ast.SignatureDecl decl) {
    final List<Doc> bindDocs = new ArrayList<>();
    for (int i = 0; i < decl.binds.size(); i++) {
      final Doc bind = signatureBindDoc(decl.binds.get(i));
      if (i == 0) {
        bindDocs.add(beside(text("signature "), bind));
      } else {
        bindDocs.add(beside(text("and "), bind));
      }
    }
    return vsep(bindDocs);
  }

  /** Signature bind: {@code NAME = sig ... end}. */
  private static Doc signatureBindDoc(Ast.SignatureBind bind) {
    final List<Doc> specDocs = new ArrayList<>();
    for (Ast.Spec spec : bind.specs) {
      specDocs.add(toDoc(spec, 0, 0));
    }
    return beside(
        text(bind.name.name + " = sig"),
        beside(
            nest(2, beside(LINE, vsep(specDocs))), beside(LINE, text("end"))));
  }

  // -- Specs ------------------------------------------------------------------

  /** Value spec: {@code val name : type}. */
  private static Doc valSpecDoc(Ast.ValSpec spec) {
    return beside(
        text("val "),
        beside(
            text(spec.name.name), beside(text(" : "), toDoc(spec.type, 0, 0))));
  }

  /** Type spec: {@code type 'a t} or {@code type 'a t = type}. */
  private static Doc typeSpecDoc(Ast.TypeSpec spec) {
    Doc doc =
        beside(
            text("type "),
            beside(tyVarListDoc(spec.tyVars), text(spec.name.name)));
    if (spec.type != null) {
      doc = beside(doc, beside(text(" = "), toDoc(spec.type, 0, 0)));
    }
    return doc;
  }

  /** Datatype spec: {@code datatype 'a t = C1 of t1 | C2}. */
  private static Doc datatypeSpecDoc(Ast.DatatypeSpec spec) {
    final List<Doc> tyConDocs = new ArrayList<>();
    for (int i = 0; i < spec.tyCons.size(); i++) {
      final Doc tc = tyConDoc(spec.tyCons.get(i), 0, 0);
      if (i > 0) {
        tyConDocs.add(beside(text("| "), tc));
      } else {
        tyConDocs.add(tc);
      }
    }
    return beside(
        text("datatype "),
        beside(
            tyVarListDoc(spec.tyVars),
            beside(text(spec.name.name + " = "), sep(tyConDocs))));
  }

  /** Exception spec: {@code exception E} or {@code exception E of t}. */
  private static Doc exceptionSpecDoc(Ast.ExceptionSpec spec) {
    Doc doc = beside(text("exception "), text(spec.name.name));
    if (spec.type != null) {
      doc = beside(doc, beside(text(" of "), toDoc(spec.type, 0, 0)));
    }
    return doc;
  }

  // -- Type variable list helpers ---------------------------------------------

  /** Type variable list for declarations: {@code 'a }, {@code ('a, 'b) }. */
  private static Doc tyVarListDoc(List<Ast.TyVar> tyVars) {
    switch (tyVars.size()) {
      case 0:
        return EMPTY;
      case 1:
        return beside(toDoc(tyVars.get(0), 0, 0), text(" "));
      default:
        final List<Doc> docs = new ArrayList<>();
        for (Ast.TyVar tv : tyVars) {
          docs.add(toDoc(tv, 0, 0));
        }
        return beside(tupleDoc(docs), text(" "));
    }
  }

  // -- Literal formatting -----------------------------------------------------

  /** Formats a literal value as a string, matching AstWriter.appendLiteral. */
  @SuppressWarnings("rawtypes")
  static String formatLiteral(Comparable value) {
    if (value instanceof String) {
      return "\""
          + ((String) value).replace("\\", "\\\\").replace("\"", "\\\"")
          + "\"";
    } else if (value instanceof Character) {
      switch ((char) value) {
        case '"':
          return "#\"\\\"\"";
        case '\\':
          return "#\"\\\\\"";
        default:
          return "#\"" + value + "\"";
      }
    } else if (value instanceof BigDecimal) {
      BigDecimal c = (BigDecimal) value;
      if (c.compareTo(BigDecimal.ZERO) < 0) {
        return "~" + c.negate().toString().replace("+", "");
      }
      return c.toString().replace("+", "");
    } else if (value instanceof BuiltIn) {
      final BuiltIn builtIn = (BuiltIn) value;
      if (builtIn.structure != null && !builtIn.structure.equals("$")) {
        return "#" + builtIn.mlName + " " + builtIn.structure;
      } else {
        return builtIn.mlName;
      }
    } else {
      return value.toString();
    }
  }
}

// End AstToDoc.java
