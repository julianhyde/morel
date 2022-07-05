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
package net.hydromatic.morel.compile;

import net.hydromatic.morel.ast.Ast;
import net.hydromatic.morel.ast.AstNode;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.FromBuilder;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.ast.Shuttle;
import net.hydromatic.morel.ast.Visitor;
import net.hydromatic.morel.eval.Prop;
import net.hydromatic.morel.eval.Session;
import net.hydromatic.morel.foreign.Calcite;
import net.hydromatic.morel.foreign.ForeignValue;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.DataType;
import net.hydromatic.morel.type.ListType;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.Ord;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Iterables;
import org.apache.calcite.util.Holder;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Consumer;

import static net.hydromatic.morel.ast.AstBuilder.ast;
import static net.hydromatic.morel.ast.CoreBuilder.core;
import static net.hydromatic.morel.util.Static.append;
import static net.hydromatic.morel.util.Static.minus;
import static net.hydromatic.morel.util.Static.plus;

import static org.apache.calcite.util.Util.skip;

/** Helpers for {@link Compiler} and {@link TypeResolver}. */
public abstract class Compiles {
  /** Validates an expression or declaration, deducing its type and perhaps
   * rewriting the expression to a form that can more easily be compiled.
   *
   * <p>Used for testing. */
  public static TypeResolver.Resolved validateExpression(AstNode statement,
      Map<String, ForeignValue> valueMap) {
    final TypeSystem typeSystem = new TypeSystem();
    final Environment env = Environments.env(typeSystem, valueMap);
    return TypeResolver.deduceType(env, toDecl(statement), typeSystem);
  }

  /**
   * Validates and compiles a statement (expression or declaration), and
   * compiles it to code that can be evaluated by the interpreter.
   */
  public static CompiledStatement prepareStatement(TypeSystem typeSystem,
      Session session, Environment env, AstNode statement,
      @Nullable Calcite calcite, Consumer<CompileException> warningConsumer,
      Tracer tracer) {
    Ast.Decl decl;
    if (statement instanceof Ast.Exp) {
      decl = toValDecl((Ast.Exp) statement);
    } else {
      decl = (Ast.Decl) statement;
    }
    return prepareDecl(typeSystem, session, env, calcite, decl,
        decl == statement, warningConsumer, tracer);
  }

  /**
   * Validates and compiles a declaration, and compiles it to
   * code that can be evaluated by the interpreter.
   */
  private static CompiledStatement prepareDecl(TypeSystem typeSystem,
      Session session, Environment env, @Nullable Calcite calcite,
      Ast.Decl decl, boolean isDecl,
      Consumer<CompileException> warningConsumer, Tracer tracer) {
    final TypeResolver.Resolved resolved =
        TypeResolver.deduceType(env, decl, typeSystem);
    final boolean hybrid = Prop.HYBRID.booleanValue(session.map);
    final int inlinePassCount =
        Math.max(Prop.INLINE_PASS_COUNT.intValue(session.map), 0);
    final boolean relationalize =
        Prop.RELATIONALIZE.booleanValue(session.map);

    final Resolver resolver = Resolver.of(resolved.typeMap, env);
    final Core.Decl coreDecl0 = resolver.toCore(resolved.node);
    tracer.onCore(0, coreDecl0);

    // Check for exhaustive and redundant patterns, and throw errors or
    // warnings.
    final boolean matchCoverageEnabled =
        Prop.MATCH_COVERAGE_ENABLED.booleanValue(session.map);
    if (matchCoverageEnabled) {
      checkPatternCoverage(typeSystem, coreDecl0, warningConsumer);
    }

    // Ensures that once we discover that there is no suchThat, we stop looking;
    // makes things a bit more efficient.
    boolean mayContainSuchThat = true;

    Core.Decl coreDecl;
    tracer.onCore(1, coreDecl0);
    if (inlinePassCount == 0) {
      // Inlining is disabled. Use the Inliner in a limited mode.
      final Inliner inliner = Inliner.of(typeSystem, env, null);
      coreDecl = coreDecl0.accept(inliner);
    } else {
      final @Nullable Relationalizer relationalizer =
          relationalize ? Relationalizer.of(typeSystem, env)
              : null;

      // Inline few times, or until we reach fixed point, whichever is sooner.
      coreDecl = coreDecl0;
      for (int i = 0; i < inlinePassCount; i++) {
        final Analyzer.Analysis analysis =
            Analyzer.analyze(typeSystem, env, coreDecl);
        final Inliner inliner = Inliner.of(typeSystem, env, analysis);
        final Core.Decl coreDecl2 = coreDecl;
        coreDecl = coreDecl2.accept(inliner);
        if (relationalizer != null) {
          coreDecl = coreDecl.accept(relationalizer);
        }
        if (mayContainSuchThat) {
          if (containsSuchThat(coreDecl0)) {
            coreDecl = coreDecl.accept(new SuchThatShuttle(typeSystem));
          } else {
            mayContainSuchThat = false;
          }
        }
        if (coreDecl == coreDecl0) {
          break;
        }
        tracer.onCore(i + 2, coreDecl);
      }
    }
    tracer.onCore(-1, coreDecl);
    final Compiler compiler;
    if (hybrid) {
      if (calcite == null) {
        calcite = Calcite.withDataSets(ImmutableMap.of());
      }
      compiler = new CalciteCompiler(typeSystem, calcite);
    } else {
      compiler = new Compiler(typeSystem);
    }

    // If the user wrote "scott.depts" we will print "<relation>";
    // but if the user wrote "from d in scott.depts", they would like to see
    // the full contents. Those two expressions may have been simplified to the
    // same Core.Exp, but in the latter case we will 'wrap' the RelList value
    // as a regular List so that it is printed in full.
    final ImmutableSet.Builder<Core.Exp> queriesToWrap = ImmutableSet.builder();
    if (resolved.originalNode instanceof Ast.ValDecl
        && coreDecl instanceof Core.NonRecValDecl) {
      final Ast.ValDecl valDecl = (Ast.ValDecl) resolved.originalNode;
      final Ast.ValBind valBind = valDecl.valBinds.get(0);
      final Core.NonRecValDecl nonRecValDecl = (Core.NonRecValDecl) coreDecl;
      if (valBind.exp.op == Op.FROM) {
        queriesToWrap.add(nonRecValDecl.exp);
      }
    }

    return compiler.compileStatement(env, coreDecl, isDecl,
        queriesToWrap.build());
  }

  private static boolean containsSuchThat(Core.Decl decl) {
    final Holder<Boolean> found = Holder.of(false);
    decl.accept(new Visitor() {
      @Override protected void visit(Core.Scan scan) {
        super.visit(scan);
        if (scan.op == Op.SUCH_THAT) {
          found.set(true);
        }
      }
    });
    return found.get();
  }

  /** Checks for exhaustive and redundant patterns, and throws if there are
   * errors/warnings. */
  private static void checkPatternCoverage(TypeSystem typeSystem,
      Core.Decl decl, final Consumer<CompileException> warningConsumer) {
    final List<CompileException> errorList = new ArrayList<>();
    decl.accept(new Visitor() {
      @Override protected void visit(Core.Case kase) {
        super.visit(kase);
        checkPatternCoverage(typeSystem, kase, errorList::add,
            warningConsumer);
      }
    });
    if (!errorList.isEmpty()) {
      throw errorList.get(0);
    }
  }

  private static void checkPatternCoverage(TypeSystem typeSystem,
      Core.Case kase, Consumer<CompileException> errorConsumer,
      Consumer<CompileException> warningConsumer) {
    final List<Core.Pat> prevPatList = new ArrayList<>();
    final List<Core.Match> redundantMatchList = new ArrayList<>();
    for (Core.Match match : kase.matchList) {
      if (PatternCoverageChecker.isCoveredBy(typeSystem, prevPatList,
          match.pat)) {
        redundantMatchList.add(match);
      }
      prevPatList.add(match.pat);
    }
    final boolean exhaustive =
        PatternCoverageChecker.isExhaustive(typeSystem, prevPatList);
    if (!redundantMatchList.isEmpty()) {
      final String message = exhaustive
          ? "match redundant"
          : "match redundant and nonexhaustive";
      errorConsumer.accept(
          new CompileException(message, false,
              redundantMatchList.get(0).pos));
    } else if (!exhaustive) {
      warningConsumer.accept(
          new CompileException("match nonexhaustive", true, kase.pos));
    }
  }

  /** Converts {@code e} to {@code val = e}. */
  public static Ast.ValDecl toValDecl(Ast.Exp statement) {
    final Pos pos = statement.pos;
    return ast.valDecl(pos, false,
        ImmutableList.of(ast.valBind(pos, ast.idPat(pos, "it"), statement)));
  }

  /** Converts an expression or value declaration to a value declaration. */
  public static Ast.ValDecl toValDecl(AstNode statement) {
    return statement instanceof Ast.ValDecl ? (Ast.ValDecl) statement
        : toValDecl((Ast.Exp) statement);
  }

  /** Converts an expression or declaration to a declaration. */
  public static Ast.Decl toDecl(AstNode statement) {
    return statement instanceof Ast.Decl ? (Ast.Decl) statement
        : toValDecl((Ast.Exp) statement);
  }

  /** Converts {@code val = e} to {@code e};
   * the converse of {@link #toValDecl(Ast.Exp)}. */
  public static Core.Exp toExp(Core.NonRecValDecl decl) {
    return decl.exp;
  }

  static void bindPattern(TypeSystem typeSystem, List<Binding> bindings,
      Core.DatatypeDecl datatypeDecl) {
    datatypeDecl.accept(binding(typeSystem, bindings));
  }

  /** Richer than {@link #bindPattern(TypeSystem, List, Core.Pat)} because
   * we have the expression. */
  static void bindPattern(TypeSystem typeSystem, List<Binding> bindings,
      Core.ValDecl valDecl) {
    valDecl.forEachBinding((pat, exp, pos) -> {
      if (pat instanceof Core.IdPat) {
        bindings.add(Binding.of(pat, exp));
      }
    });
  }

  static void bindPattern(TypeSystem typeSystem, List<Binding> bindings,
      Core.Pat pat) {
    pat.accept(binding(typeSystem, bindings));
  }

  static void bindPattern(TypeSystem typeSystem, List<Binding> bindings,
      Core.NamedPat namedPat) {
    bindings.add(Binding.of(namedPat));
  }

  public static void bindDataType(TypeSystem typeSystem, List<Binding> bindings,
      DataType dataType) {
    dataType.typeConstructors.keySet().forEach(name ->
        bindings.add(typeSystem.bindTyCon(dataType, name)));
  }

  static PatternBinder binding(TypeSystem typeSystem, List<Binding> bindings) {
    return new PatternBinder(typeSystem, bindings);
  }

  /** Visits a pattern, adding bindings to a list.
   *
   * <p>If the pattern is an {@link net.hydromatic.morel.ast.Core.IdPat},
   * don't use this method: just bind directly. */
  public static void acceptBinding(TypeSystem typeSystem, Core.Pat pat,
      List<Binding> bindings) {
    pat.accept(binding(typeSystem, bindings));
  }

  /** Visitor that adds a {@link Binding} each time it see an
   * {@link Core.IdPat} or {@link Core.AsPat}. */
  private static class PatternBinder extends Visitor {
    private final TypeSystem typeSystem;
    private final List<Binding> bindings;

    PatternBinder(TypeSystem typeSystem, List<Binding> bindings) {
      this.typeSystem = typeSystem;
      this.bindings = bindings;
    }

    @Override public void visit(Core.IdPat idPat) {
      bindPattern(typeSystem, bindings, idPat);
    }

    @Override public void visit(Core.AsPat asPat) {
      bindPattern(typeSystem, bindings, asPat);
      super.visit(asPat);
    }

    @Override protected void visit(Core.NonRecValDecl valBind) {
      // The super method visits valBind.e; we do not
      valBind.pat.accept(this);
    }

    @Override protected void visit(Core.DatatypeDecl datatypeDecl) {
      datatypeDecl.dataTypes.forEach(dataType ->
          bindDataType(typeSystem, bindings, dataType));
    }

    @Override protected void visit(Core.Local local) {
      bindDataType(typeSystem, bindings, local.dataType);
    }
  }

  /** Converts {@code suchThat} to {@code in} wherever possible. */
  private static class SuchThatShuttle extends Shuttle {
    SuchThatShuttle(TypeSystem typeSystem) {
      super(typeSystem);
    }

    @Override protected Core.Scan visit(Core.Scan scan) {
      if (scan.op == Op.SUCH_THAT) {
        final ImmutableList.Builder<Core.NamedPat> unboundPats =
            ImmutableList.builder();
        flatten(scan.pat).forEach(unboundPats::add);
        final ImmutableSortedMap.Builder<Core.NamedPat, Core.Exp> mapBuilder =
            ImmutableSortedMap.orderedBy(Core.NamedPat.ORDERING);
        final Core.@Nullable Exp rewritten =
            rewrite(unboundPats.build(),
                mapBuilder.build(),
                ImmutableMap.of(),
                ImmutableList.of(), conjunctions(scan.exp));
        return core.scan(Op.INNER_JOIN, scan.bindings,
            scan.pat, rewritten, scan.condition);
      }
      return super.visit(scan);
    }

    /** Converts a singleton id pattern "x" or tuple pattern "(x, y)"
     * to a list of id patterns. */
    private static List<Core.IdPat> flatten(Core.Pat pat) {
      switch (pat.op) {
      case ID_PAT:
        return ImmutableList.of((Core.IdPat) pat);

      case TUPLE_PAT:
        final Core.TuplePat tuplePat = (Core.TuplePat) pat;
        for (Core.Pat arg : tuplePat.args) {
          if (arg.op != Op.ID_PAT) {
            throw new CompileException("must be id", false, arg.pos);
          }
        }
        //noinspection unchecked,rawtypes
        return (List) tuplePat.args;

      default:
        throw new CompileException("must be id", false, pat.pos);
      }
    }

    private Core.Exp rewrite(List<Core.NamedPat> unboundPats,
        SortedMap<Core.NamedPat, Core.Exp> boundPats,
        Map<Core.IdPat, Core.Exp> scans, List<Core.Exp> filters,
        List<Core.Exp> exps) {
      if (exps.isEmpty()) {
        if (!unboundPats.isEmpty()) {
          throw new CompileException("Cannot implement 'suchthat'; variables "
              + unboundPats + " are not grounded", false, Pos.ZERO);
        }
        final FromBuilder fromBuilder = core.fromBuilder(typeSystem);
        scans.forEach(fromBuilder::scan);
        Core.Exp exp;
        if (boundPats.size() == 1) {
          exp = Iterables.getOnlyElement(boundPats.values());
        } else {
          final SortedMap<String, Type> argNameTypes =
              new TreeMap<>(RecordType.ORDERING);
          boundPats.keySet().forEach(p -> argNameTypes.put(p.name, p.type));
          exp =
              core.tuple(typeSystem.recordType(argNameTypes),
                  boundPats.values());
        }
        fromBuilder.yield_(exp);
        return fromBuilder.build();
      }
      final Core.Exp exp = exps.get(0);
      final List<Core.Exp> exps2 = skip(exps);
      if (exp.op == Op.APPLY) {
        Core.Apply apply = (Core.Apply) exp;
        if (apply.fn.op == Op.FN_LITERAL) {
          final Core.Literal literal = (Core.Literal) apply.fn;
          if (literal.value == BuiltIn.OP_ELEM) {
            Core.Exp a0 = apply.args().get(0);
            Core.Exp a1 = apply.args().get(1);
            return rewriteElem(typeSystem, unboundPats, boundPats, scans,
                filters, a0, a1, exps2);
          } else if (literal.value == BuiltIn.OP_EQ) {
            Core.Exp a0 = apply.args().get(0);
            Core.Exp a1 = apply.args().get(1);
            Core.Exp a1List =
                core.list(typeSystem, a1.type, ImmutableList.of(a1));
            return rewriteElem(typeSystem, unboundPats, boundPats, scans,
                filters, a0, a1List, exps2);
          }
        }
      }
      throw new AssertionError(exp);
    }

    private Core.Exp rewriteElem(TypeSystem typeSystem,
        List<Core.NamedPat> unboundPats,
        SortedMap<Core.NamedPat, Core.Exp> boundPats,
        Map<Core.IdPat, Core.Exp> scans,
        List<Core.Exp> filters, Core.Exp a0,
        Core.Exp a1, List<Core.Exp> exps2) {
      if (a0.op == Op.ID) {
        // from ... v suchthat (v elem list)
        final Core.IdPat idPat = (Core.IdPat) ((Core.Id) a0).idPat;
        if (unboundPats.contains(idPat)) {
          // "from a, b, c suchthat (b in list-valued-expression)"
          //  --> remove b from unbound
          //     add (b, scans.size) to bound
          //     add list to scans
          // from ... v in list
          final List<Core.NamedPat> unboundPats2 =
              minus(unboundPats, idPat);
          final SortedMap<Core.NamedPat, Core.Exp> boundPats2 =
              plus(boundPats, idPat, core.id(idPat));
          final Map<Core.IdPat, Core.Exp> scans2 =
              plus(scans, idPat, a1);
          return rewrite(unboundPats2, boundPats2, scans2, filters, exps2);
        }
        throw new AssertionError(unboundPats + ", " + idPat); // TODO
      } else if (a0.op == Op.TUPLE) {
        // from v, w, x suchthat ((v, w, x) elem list)
        //  -->
        //  from e suchthat (e elem list)
        //    yield (e.v, e.w, e.x)
        final Core.Tuple tuple = (Core.Tuple) a0;
        final Core.IdPat idPat =
            core.idPat(((ListType) a1.type).elementType,
                typeSystem.nameGenerator);
        final Core.Id id = core.id(idPat);
        SortedMap<Core.NamedPat, Core.Exp> boundPats2 = boundPats;
        List<Core.NamedPat> unboundPats2 = unboundPats;
        List<Core.Exp> filters2 = filters;
        for (Ord<Core.Exp> arg : Ord.zip(tuple.args)) {
          if (arg.e instanceof Core.Id) {
            final Core.NamedPat idPat2 = ((Core.Id) arg.e).idPat;
            final Core.Exp e = core.field(typeSystem, id, arg.i);
            if (unboundPats2.contains(idPat2)) {
              // This variable was not previously bound; bind it.
              unboundPats2 = minus(unboundPats2, idPat2);
              boundPats2 = plus(boundPats2, idPat2, e);
            } else {
              // This variable is already bound; now add a filter.
              filters2 =
                  append(filters, core.equal(typeSystem, e, arg.e));
            }
          }
        }
        final Map<Core.IdPat, Core.Exp> scans2 = plus(scans, idPat, a1);
        return rewrite(unboundPats2, boundPats2, scans2, filters2, exps2);
      } else {
        // from ... (v, w) suchThat ((v, w) elem list)
        throw new AssertionError();
      }
    }

    /** Returns an expression as a list of conjunctions.
     *
     * <p>For example {@code conjunctions(a andalso b)}
     * returns [{@code a}, {@code b}] (two elements);
     * {@code conjunctions(a orelse b)}
     * returns [{@code a orelse b}] (one element);
     * {@code conjunctions(true)}
     * returns [] (no elements);
     * {@code conjunctions(false)}
     * returns [{@code false}] (one element). */
    static List<Core.Exp> conjunctions(Core.Exp e) {
      if (e.op == Op.BOOL_LITERAL
          && (boolean) ((Core.Literal) e).value) {
        return ImmutableList.of();
      } else if (e.op == Op.APPLY
          && ((Core.Apply) e).fn.op == Op.FN_LITERAL
          && ((Core.Literal) ((Core.Apply) e).fn).value == BuiltIn.Z_ANDALSO) {
        return ((Core.Apply) e).args();
      } else {
        return ImmutableList.of(e);
      }
    }
  }
}

// End Compiles.java
