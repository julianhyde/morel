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
package net.hydromatic.morel.eval.code;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static net.hydromatic.morel.ast.CoreBuilder.core;
import static net.hydromatic.morel.eval.code.RelationalCodes.RELATIONAL_SUM;

import com.google.common.collect.ImmutableList;
import java.io.StringReader;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.hydromatic.morel.ast.AstDumper;
import net.hydromatic.morel.ast.AstNode;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.compile.Compiles;
import net.hydromatic.morel.compile.Environment;
import net.hydromatic.morel.compile.Macro;
import net.hydromatic.morel.datalog.DatalogEvaluator;
import net.hydromatic.morel.eval.Applicable;
import net.hydromatic.morel.eval.Applicable1;
import net.hydromatic.morel.eval.BuiltInExn;
import net.hydromatic.morel.eval.Codes;
import net.hydromatic.morel.eval.Codes.Typed;
import net.hydromatic.morel.eval.MorelRuntimeException;
import net.hydromatic.morel.eval.Prop;
import net.hydromatic.morel.eval.Session;
import net.hydromatic.morel.eval.Stack;
import net.hydromatic.morel.eval.Unit;
import net.hydromatic.morel.eval.Variant;
import net.hydromatic.morel.eval.Variants;
import net.hydromatic.morel.parse.MorelParserImpl;
import net.hydromatic.morel.type.ListType;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.TupleType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.ColorScheme;
import net.hydromatic.morel.util.Lindig;
import net.hydromatic.morel.util.MorelHighlighter;
import net.hydromatic.morel.util.PairList;
import org.jspecify.annotations.Nullable;

/**
 * Implementations of built-in functions and values in the {@code Sys}, {@code
 * Interact}, {@code Datalog}, {@code Test}, {@code Variant} and {@code PP}
 * structures.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public final class SysCodes {
  private SysCodes() {}

  /** Registers the implementations in this class. */
  public static void register(PairList<BuiltIn, Object> b) {
    // lint: sort until '#}' where '##b\.add\(BuiltIn' erase 'b\.'
    b.add(BuiltIn.DATALOG_EXECUTE, DATALOG_EXECUTE);
    b.add(BuiltIn.DATALOG_TRANSLATE, DATALOG_TRANSLATE);
    b.add(BuiltIn.DATALOG_VALIDATE, DATALOG_VALIDATE);
    b.add(BuiltIn.INTERACT_USE, INTERACT_USE);
    b.add(BuiltIn.INTERACT_USE_SILENTLY, INTERACT_USE_SILENTLY);
    b.add(BuiltIn.PP_ALIGN, PP_ALIGN);
    b.add(BuiltIn.PP_BESIDE, PP_BESIDE);
    b.add(BuiltIn.PP_BRACES, PP_BRACES);
    b.add(BuiltIn.PP_BRACKETS, PP_BRACKETS);
    b.add(BuiltIn.PP_CAT, PP_CAT);
    b.add(BuiltIn.PP_EMPTY, PP_EMPTY);
    b.add(BuiltIn.PP_ENCLOSE_SEP, PP_ENCLOSE_SEP);
    b.add(BuiltIn.PP_FILL_CAT, PP_FILL_CAT);
    b.add(BuiltIn.PP_FILL_SEP, PP_FILL_SEP);
    b.add(BuiltIn.PP_GROUP, PP_GROUP);
    b.add(BuiltIn.PP_HANG, PP_HANG);
    b.add(BuiltIn.PP_HARD_LINE, PP_HARD_LINE);
    b.add(BuiltIn.PP_HCAT, PP_HCAT);
    b.add(BuiltIn.PP_HSEP, PP_HSEP);
    b.add(BuiltIn.PP_INDENT, PP_INDENT);
    b.add(BuiltIn.PP_LINE, PP_LINE);
    b.add(BuiltIn.PP_LINE_BREAK, PP_LINE_BREAK);
    b.add(BuiltIn.PP_NEST, PP_NEST);
    b.add(BuiltIn.PP_PACK, PP_PACK);
    b.add(BuiltIn.PP_PARENS, PP_PARENS);
    b.add(BuiltIn.PP_PUNCTUATE, PP_PUNCTUATE);
    b.add(BuiltIn.PP_RENDER, PP_RENDER);
    b.add(BuiltIn.PP_SEP, PP_SEP);
    b.add(BuiltIn.PP_SOFT_BREAK, PP_SOFT_BREAK);
    b.add(BuiltIn.PP_SOFT_LINE, PP_SOFT_LINE);
    b.add(BuiltIn.PP_TEXT, PP_TEXT);
    b.add(BuiltIn.PP_VCAT, PP_VCAT);
    b.add(BuiltIn.PP_VSEP, PP_VSEP);
    b.add(BuiltIn.SYS_CLEAR_ENV, SYS_CLEAR_ENV);
    b.add(BuiltIn.SYS_COLOR_SCHEMES, SYS_COLOR_SCHEMES);
    b.add(BuiltIn.SYS_DEDUCE_COLOR_SCHEME, SYS_DEDUCE_COLOR_SCHEME);
    b.add(BuiltIn.SYS_ENV, (Macro) SysCodes::sysEnv);
    b.add(BuiltIn.SYS_PARSE_TREE, SYS_PARSE_TREE);
    b.add(BuiltIn.SYS_PLAN, SYS_PLAN);
    b.add(BuiltIn.SYS_PLAN_EX, SYS_PLAN_EX);
    b.add(BuiltIn.SYS_PLAN_OF, SYS_PLAN_OF);
    b.add(BuiltIn.SYS_SET, SYS_SET);
    b.add(BuiltIn.SYS_SHOW, SYS_SHOW);
    b.add(BuiltIn.SYS_SHOW_ALL, SYS_SHOW_ALL);
    b.add(BuiltIn.SYS_UNSET, SYS_UNSET);
    b.add(BuiltIn.TEST_BAG_SUM, TEST_BAG_SUM);
    b.add(BuiltIn.TEST_FOO, TEST_FOO);
    b.add(BuiltIn.TEST_HIGHLIGHT, TEST_HIGHLIGHT);
    b.add(BuiltIn.TEST_LIST_SUM, TEST_LIST_SUM);
    b.add(BuiltIn.TEST_OVER_COUNT, TEST_OVER_COUNT);
    b.add(BuiltIn.TEST_OVER_SUM, TEST_OVER_SUM);
    b.add(BuiltIn.VARIANT_PARSE, VARIANT_PARSE);
    b.add(BuiltIn.VARIANT_PRINT, VARIANT_PRINT);
    b.add(BuiltIn.Z_TEST_OVER_COUNT_BAG, Z_TEST_OVER_COUNT_BAG);
    b.add(BuiltIn.Z_TEST_OVER_COUNT_LIST, Z_TEST_OVER_COUNT_LIST);
  }

  // lint: sort until '#}' \
  //   where '##private static final [^ ]+ [^ ]+ =' \
  //   erase 'private static final [^ ]+ '

  /** @see BuiltIn#DATALOG_EXECUTE */
  private static final Applicable DATALOG_EXECUTE =
      new BaseApplicable(BuiltIn.DATALOG_EXECUTE) {
        @Override
        public Object apply(Stack stack, Object arg) {
          String program = (String) arg;
          return DatalogEvaluator.execute(program, stack.session);
        }
      };

  /** @see BuiltIn#DATALOG_TRANSLATE */
  private static final Applicable DATALOG_TRANSLATE =
      new BaseApplicable(BuiltIn.DATALOG_TRANSLATE) {
        @Override
        public Object apply(Stack stack, Object arg) {
          String program = (String) arg;
          return DatalogEvaluator.translate(program, stack.session);
        }
      };

  /** @see BuiltIn#DATALOG_VALIDATE */
  private static final Applicable DATALOG_VALIDATE =
      new BaseApplicable(BuiltIn.DATALOG_VALIDATE) {
        @Override
        public Object apply(Stack stack, Object arg) {
          String program = (String) arg;
          return DatalogEvaluator.validate(program, stack.session);
        }
      };

  /** @see BuiltIn#INTERACT_USE */
  private static final Applicable INTERACT_USE =
      new InteractUse(Pos.ZERO, false);

  /** @see BuiltIn#INTERACT_USE_SILENTLY */
  private static final Applicable INTERACT_USE_SILENTLY =
      new InteractUse(Pos.ZERO, true);

  /** Implements {@link #INTERACT_USE}. */
  private static class InteractUse extends BasePositionedApplicable {
    private final boolean silent;

    InteractUse(Pos pos, boolean silent) {
      super(BuiltIn.INTERACT_USE, pos);
      this.silent = silent;
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new InteractUse(pos, silent);
    }

    @Override
    public Object apply(Stack stack, Object arg) {
      final String f = (String) arg;
      stack.session.use(f, silent, pos);
      return Unit.INSTANCE;
    }
  }

  /** @see BuiltIn#PP_ALIGN */
  private static final Applicable PP_ALIGN =
      new BaseApplicable1<Lindig.Doc, Lindig.Doc>(BuiltIn.PP_ALIGN) {
        @Override
        public Lindig.Doc apply(Lindig.Doc doc) {
          return Lindig.align(doc);
        }
      };

  /** @see BuiltIn#PP_BESIDE */
  private static final Applicable PP_BESIDE =
      new BaseApplicable2<Lindig.Doc, Lindig.Doc, Lindig.Doc>(
          BuiltIn.PP_BESIDE) {
        @Override
        public Lindig.Doc apply(Lindig.Doc a, Lindig.Doc b) {
          return Lindig.beside(a, b);
        }
      };

  /** @see BuiltIn#PP_BRACES */
  private static final Applicable PP_BRACES =
      new BaseApplicable1<Lindig.Doc, Lindig.Doc>(BuiltIn.PP_BRACES) {
        @Override
        public Lindig.Doc apply(Lindig.Doc doc) {
          return Lindig.braces(doc);
        }
      };

  /** @see BuiltIn#PP_BRACKETS */
  private static final Applicable PP_BRACKETS =
      new BaseApplicable1<Lindig.Doc, Lindig.Doc>(BuiltIn.PP_BRACKETS) {
        @Override
        public Lindig.Doc apply(Lindig.Doc doc) {
          return Lindig.brackets(doc);
        }
      };

  /** @see BuiltIn#PP_CAT */
  private static final Applicable PP_CAT =
      new BaseApplicable1<Lindig.Doc, List>(BuiltIn.PP_CAT) {
        @Override
        public Lindig.Doc apply(List docs) {
          return Lindig.cat(docs);
        }
      };

  /** @see BuiltIn#PP_EMPTY */
  private static final Lindig.Doc PP_EMPTY = Lindig.EMPTY;

  /** @see BuiltIn#PP_ENCLOSE_SEP */
  private static final Applicable PP_ENCLOSE_SEP =
      new BaseApplicable1<Lindig.Doc, List>(BuiltIn.PP_ENCLOSE_SEP) {
        @Override
        public Lindig.Doc apply(List args) {
          return Lindig.encloseSep(
              (Lindig.Doc) args.get(0),
              (Lindig.Doc) args.get(1),
              (Lindig.Doc) args.get(2),
              (List) args.get(3));
        }
      };

  /** @see BuiltIn#PP_FILL_CAT */
  private static final Applicable PP_FILL_CAT =
      new BaseApplicable1<Lindig.Doc, List>(BuiltIn.PP_FILL_CAT) {
        @Override
        public Lindig.Doc apply(List docs) {
          return Lindig.fillCat(docs);
        }
      };

  /** @see BuiltIn#PP_FILL_SEP */
  private static final Applicable PP_FILL_SEP =
      new BaseApplicable1<Lindig.Doc, List>(BuiltIn.PP_FILL_SEP) {
        @Override
        public Lindig.Doc apply(List docs) {
          return Lindig.fillSep(docs);
        }
      };

  /** @see BuiltIn#PP_GROUP */
  private static final Applicable PP_GROUP =
      new BaseApplicable1<Lindig.Doc, Lindig.Doc>(BuiltIn.PP_GROUP) {
        @Override
        public Lindig.Doc apply(Lindig.Doc doc) {
          return Lindig.group(doc);
        }
      };

  /** @see BuiltIn#PP_HANG */
  private static final Applicable PP_HANG =
      new BaseApplicable2<Lindig.Doc, Integer, Lindig.Doc>(BuiltIn.PP_HANG) {
        @Override
        public Lindig.Doc apply(Integer indent, Lindig.Doc doc) {
          return Lindig.hang(indent, doc);
        }
      };

  /** @see BuiltIn#PP_HARD_LINE */
  private static final Lindig.Doc PP_HARD_LINE = Lindig.HARD_LINE;

  /** @see BuiltIn#PP_HCAT */
  private static final Applicable PP_HCAT =
      new BaseApplicable1<Lindig.Doc, List>(BuiltIn.PP_HCAT) {
        @Override
        public Lindig.Doc apply(List docs) {
          return Lindig.hcat(docs);
        }
      };

  /** @see BuiltIn#PP_HSEP */
  private static final Applicable PP_HSEP =
      new BaseApplicable1<Lindig.Doc, List>(BuiltIn.PP_HSEP) {
        @Override
        public Lindig.Doc apply(List docs) {
          return Lindig.hsep(docs);
        }
      };

  /** @see BuiltIn#PP_INDENT */
  private static final Applicable PP_INDENT =
      new BaseApplicable2<Lindig.Doc, Integer, Lindig.Doc>(BuiltIn.PP_INDENT) {
        @Override
        public Lindig.Doc apply(Integer indent, Lindig.Doc doc) {
          return Lindig.indent(indent, doc);
        }
      };

  /** @see BuiltIn#PP_LINE */
  private static final Lindig.Doc PP_LINE = Lindig.LINE;

  /** @see BuiltIn#PP_LINE_BREAK */
  private static final Lindig.Doc PP_LINE_BREAK = Lindig.LINE_BREAK;

  /** @see BuiltIn#PP_NEST */
  private static final Applicable PP_NEST =
      new BaseApplicable2<Lindig.Doc, Integer, Lindig.Doc>(BuiltIn.PP_NEST) {
        @Override
        public Lindig.Doc apply(Integer indent, Lindig.Doc doc) {
          return Lindig.nest(indent, doc);
        }
      };

  /** @see BuiltIn#PP_PACK */
  private static final Applicable PP_PACK =
      new BaseApplicable2<Lindig.Doc, Lindig.Doc, List>(BuiltIn.PP_PACK) {
        @Override
        public Lindig.Doc apply(Lindig.Doc glue, List docs) {
          return Lindig.pack(glue, docs);
        }
      };

  /** @see BuiltIn#PP_PARENS */
  private static final Applicable PP_PARENS =
      new BaseApplicable1<Lindig.Doc, Lindig.Doc>(BuiltIn.PP_PARENS) {
        @Override
        public Lindig.Doc apply(Lindig.Doc doc) {
          return Lindig.parens(doc);
        }
      };

  /** @see BuiltIn#PP_PUNCTUATE */
  private static final Applicable PP_PUNCTUATE =
      new BaseApplicable2<List, Lindig.Doc, List>(BuiltIn.PP_PUNCTUATE) {
        @Override
        public List apply(Lindig.Doc separator, List docs) {
          return Lindig.punctuate(separator, docs);
        }
      };

  /** @see BuiltIn#PP_RENDER */
  private static final Applicable PP_RENDER =
      new BaseApplicable2<String, Integer, Lindig.Doc>(BuiltIn.PP_RENDER) {
        @Override
        public String apply(Integer width, Lindig.Doc doc) {
          return Lindig.render(width, doc);
        }
      };

  /** @see BuiltIn#PP_SEP */
  private static final Applicable PP_SEP =
      new BaseApplicable1<Lindig.Doc, List>(BuiltIn.PP_SEP) {
        @Override
        public Lindig.Doc apply(List docs) {
          return Lindig.sep(docs);
        }
      };

  /** @see BuiltIn#PP_SOFT_BREAK */
  private static final Lindig.Doc PP_SOFT_BREAK = Lindig.SOFT_BREAK;

  /** @see BuiltIn#PP_SOFT_LINE */
  private static final Lindig.Doc PP_SOFT_LINE = Lindig.SOFT_LINE;

  /** @see BuiltIn#PP_TEXT */
  private static final Applicable PP_TEXT =
      new BaseApplicable1<Lindig.Doc, String>(BuiltIn.PP_TEXT) {
        @Override
        public Lindig.Doc apply(String s) {
          return Lindig.text(s);
        }
      };

  /** @see BuiltIn#PP_VCAT */
  private static final Applicable PP_VCAT =
      new BaseApplicable1<Lindig.Doc, List>(BuiltIn.PP_VCAT) {
        @Override
        public Lindig.Doc apply(List docs) {
          return Lindig.vcat(docs);
        }
      };

  /** @see BuiltIn#PP_VSEP */
  private static final Applicable PP_VSEP =
      new BaseApplicable1<Lindig.Doc, List>(BuiltIn.PP_VSEP) {
        @Override
        public Lindig.Doc apply(List docs) {
          return Lindig.vsep(docs);
        }
      };

  /** @see BuiltIn#SYS_CLEAR_ENV */
  private static final Applicable SYS_CLEAR_ENV =
      new ApplicableImpl(BuiltIn.SYS_CLEAR_ENV) {
        @Override
        public Object apply(Stack stack, Object arg) {
          stack.session.clearEnv();
          return Unit.INSTANCE;
        }
      };

  /** @see BuiltIn#SYS_COLOR_SCHEMES */
  private static final Applicable SYS_COLOR_SCHEMES =
      new ApplicableImpl(BuiltIn.SYS_COLOR_SCHEMES) {
        @Override
        public Object apply(Stack stack, Object arg) {
          final ImmutableList.Builder<List> list = ImmutableList.builder();
          for (ColorScheme scheme : ColorScheme.builtIns()) {
            // Fields in record (alphabetical) order.
            list.add(
                ImmutableList.of(
                    scheme.spec(ColorScheme.Category.COMMENT),
                    scheme.spec(ColorScheme.Category.CONSTANT),
                    scheme.spec(ColorScheme.Category.ERROR),
                    scheme.spec(ColorScheme.Category.IDENTIFIER),
                    scheme.spec(ColorScheme.Category.KEYWORD),
                    scheme.name(),
                    scheme.spec(ColorScheme.Category.NUMERIC),
                    scheme.spec(ColorScheme.Category.STRING),
                    scheme.spec(ColorScheme.Category.SYMBOL),
                    scheme.spec(ColorScheme.Category.TYPE_VAR)));
          }
          return list.build();
        }
      };

  /** @see BuiltIn#SYS_DEDUCE_COLOR_SCHEME */
  private static final Applicable SYS_DEDUCE_COLOR_SCHEME =
      new ApplicableImpl(BuiltIn.SYS_DEDUCE_COLOR_SCHEME) {
        @Override
        public Object apply(Stack stack, Object arg) {
          return stack.session.colorScheme().name();
        }
      };

  /** @see BuiltIn#SYS_ENV */
  private static Core.Exp sysEnv(
      TypeSystem typeSystem, Environment env, Type argType, Pos pos) {
    final TupleType stringPairType =
        typeSystem.tupleType(PrimitiveType.STRING, PrimitiveType.STRING);
    final List<Core.Tuple> args =
        env.getValueMap(true).entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(
                entry ->
                    core.tuple(
                        stringPairType,
                        core.stringLiteral(entry.getKey()),
                        core.stringLiteral(entry.getValue().id.type.moniker())))
            .collect(Collectors.toList());
    return core.apply(
        pos,
        typeSystem.listType(argType),
        core.functionLiteral(typeSystem, BuiltIn.Z_LIST),
        core.tuple(typeSystem, null, args));
  }

  /** @see BuiltIn#SYS_PARSE_TREE */
  private static final Applicable SYS_PARSE_TREE =
      new ApplicableImpl(BuiltIn.SYS_PARSE_TREE) {
        @Override
        public Object apply(Stack stack, Object arg) {
          final String source = (String) arg;
          final MorelParserImpl parser =
              new MorelParserImpl(new StringReader(source));
          parser.zero("parseTree");
          final AstNode node;
          try {
            node = parser.statementSemicolonOrEofSafe();
          } catch (RuntimeException e) {
            throw new RuntimeException("Sys.parseTree: " + e.getMessage(), e);
          }
          if (node == null) {
            return "()";
          }
          return AstDumper.dump(node);
        }
      };

  /** @see BuiltIn#SYS_PLAN */
  private static final Applicable SYS_PLAN =
      new ApplicableImpl(BuiltIn.SYS_PLAN) {
        @Override
        public Object apply(Stack stack, Object arg) {
          return Codes.describe(requireNonNull(stack.session.code));
        }
      };

  /** @see BuiltIn#SYS_PLAN_EX */
  private static final Applicable SYS_PLAN_EX =
      new ApplicableImpl(BuiltIn.SYS_PLAN_EX) {
        @Override
        public Object apply(Stack stack, Object arg) {
          final Session session = stack.session;
          final String phase = (String) arg;
          if (session.coreDecl == null) {
            return "No previous command to re-plan";
          }
          if (session.typeSystem == null) {
            return "Type system not available";
          }
          if (session.environment == null) {
            return "Environment not available";
          }
          try {
            final Core.Decl coreAtPhase =
                Compiles.replanToPhase(
                    session.coreDecl,
                    session.typeSystem,
                    session.environment,
                    session,
                    phase);
            // With types: planEx prints the collection type of every line.
            return coreAtPhase.unparseRenumbered(
                session.typeSystem,
                Prop.LINE_WIDTH.optionalIntValue(
                    session.map, Integer.MAX_VALUE),
                true);
          } catch (Exception e) {
            return "Error re-planning: " + e.getMessage();
          }
        }
      };

  /**
   * The resolver replaces {@code Sys.planOf e} with the plan of {@code e}, so
   * this runs only where {@code Sys.planOf} is used as a value rather than
   * applied to an expression -- passed to {@code map}, say -- which it cannot
   * serve, because by then the expression is gone and only its value remains.
   *
   * @see BuiltIn#SYS_PLAN_OF
   */
  private static final Applicable SYS_PLAN_OF =
      new ApplicableImpl(BuiltIn.SYS_PLAN_OF) {
        @Override
        public Object apply(Stack stack, Object arg) {
          return "Sys.planOf must be applied to an expression";
        }
      };

  /**
   * Looks up a property by name, raising {@code Fail} if there is no such
   * property.
   *
   * @param fnName Name of the function that is looking up the property, for
   *     example "set"; it appears in the error message
   */
  private static Prop lookupProp(String fnName, String propName, Pos pos) {
    final @Nullable Prop prop = Prop.lookup(propName);
    if (prop == null) {
      throw new MorelRuntimeException(
          BuiltInExn.FAIL,
          format("%s: unknown property '%s'", fnName, propName),
          pos);
    }
    return prop;
  }

  /** @see BuiltIn#SYS_SET */
  private static final Applicable SYS_SET = new SysSet(Pos.ZERO);

  /** Implements {@link #SYS_SET}. */
  private static class SysSet extends BasePositionedApplicable {
    SysSet(Pos pos) {
      super(BuiltIn.SYS_SET, pos);
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new SysSet(pos);
    }

    @Override
    public Object apply(Stack stack, Object arg) {
      final List list = (List) arg;
      final String propName = (String) list.get(0);
      final Object value = list.get(1);
      final Prop prop = lookupProp("set", propName, pos);
      final @Nullable String message =
          prop.setLenient(stack.session.map, value);
      if (message != null) {
        throw new MorelRuntimeException(BuiltInExn.FAIL, message, pos);
      }
      return Unit.INSTANCE;
    }
  }

  /** @see BuiltIn#SYS_SHOW */
  private static final Applicable SYS_SHOW = new SysShow(Pos.ZERO);

  /** Implements {@link #SYS_SHOW}. */
  private static class SysShow extends BasePositionedApplicable {
    SysShow(Pos pos) {
      super(BuiltIn.SYS_SHOW, pos);
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new SysShow(pos);
    }

    @Override
    public Object apply(Stack stack, Object arg) {
      final String propName = (String) arg;
      final Prop prop = lookupProp("show", propName, pos);
      return prop.showValue(stack.session.map);
    }
  }

  /** @see BuiltIn#SYS_SHOW_ALL */
  private static final Applicable SYS_SHOW_ALL =
      new ApplicableImpl(BuiltIn.SYS_SHOW_ALL) {
        @Override
        public Object apply(Stack stack, Object arg) {
          final Session session = stack.session;
          final ImmutableList.Builder<List<String>> list =
              ImmutableList.builder();
          for (Prop prop : Prop.BY_CAMEL_NAME) {
            list.add(
                ImmutableList.of(prop.camelName, prop.showValue(session.map)));
          }
          return list.build();
        }
      };

  /** @see BuiltIn#SYS_UNSET */
  private static final Applicable SYS_UNSET = new SysUnset(Pos.ZERO);

  /** Implements {@link #SYS_UNSET}. */
  private static class SysUnset extends BasePositionedApplicable {
    SysUnset(Pos pos) {
      super(BuiltIn.SYS_UNSET, pos);
    }

    @Override
    public Applicable withPos(Pos pos) {
      return new SysUnset(pos);
    }

    @Override
    public Object apply(Stack stack, Object arg) {
      final String propName = (String) arg;
      final Prop prop = lookupProp("unset", propName, pos);
      final Session session = stack.session;
      @SuppressWarnings("unused")
      final Object value = prop.remove(session.map);
      return Unit.INSTANCE;
    }
  }

  /** @see BuiltIn#TEST_BAG_SUM */
  private static final Macro TEST_BAG_SUM = RELATIONAL_SUM;

  /** @see BuiltIn#TEST_FOO */
  private static final Applicable1 TEST_FOO =
      new BaseApplicable1<Integer, Integer>(BuiltIn.TEST_FOO) {
        @Override
        public Integer apply(Integer arg) {
          return arg + 1;
        }
      };

  /** @see BuiltIn#TEST_HIGHLIGHT */
  private static final Applicable TEST_HIGHLIGHT =
      new ApplicableImpl(BuiltIn.TEST_HIGHLIGHT) {
        @Override
        public Object apply(Stack stack, Object arg) {
          return MorelHighlighter.DEFAULT.highlightRouge2((String) arg);
        }
      };

  /** @see BuiltIn#TEST_LIST_SUM */
  private static final Macro TEST_LIST_SUM = RELATIONAL_SUM;

  /** @see BuiltIn#TEST_OVER_COUNT */
  private static final Macro TEST_OVER_COUNT =
      (typeSystem, env, argType, pos) -> {
        if (argType instanceof ListType) {
          return core.functionLiteral(
              typeSystem, BuiltIn.Z_TEST_OVER_COUNT_LIST);
        }
        return core.functionLiteral(typeSystem, BuiltIn.Z_TEST_OVER_COUNT_BAG);
      };

  /** @see BuiltIn#TEST_OVER_SUM */
  private static final Macro TEST_OVER_SUM = RELATIONAL_SUM;

  /** @see BuiltIn#VARIANT_PARSE */
  private static final Typed VARIANT_PARSE = new VariantParser(null);

  /**
   * Implementation of {@link #VARIANT_PARSE}.
   *
   * <p>It implements {@link Typed} only because the implementation needs a
   * {@link TypeSystem} value.
   */
  static class VariantParser extends BaseApplicable1<Variant, String>
      implements Typed {
    private final @Nullable TypeSystem typeSystem;

    VariantParser(@Nullable TypeSystem typeSystem) {
      super(BuiltIn.VARIANT_PARSE);
      this.typeSystem = typeSystem;
    }

    @Override
    public Applicable withType(TypeSystem typeSystem, Type ignored, Pos pos) {
      return new VariantParser(requireNonNull(typeSystem));
    }

    @Override
    public Variant apply(String s) {
      return Variants.parse(s, requireNonNull(typeSystem));
    }
  }

  /** @see BuiltIn#VARIANT_PRINT */
  private static final Applicable1 VARIANT_PRINT =
      new BaseApplicable1<String, Variant>(BuiltIn.VARIANT_PRINT) {
        @Override
        public String apply(Variant arg) {
          return arg.print();
        }
      };

  /** Implements the bag variant of {@link BuiltIn#TEST_OVER_COUNT}. */
  private static final Applicable Z_TEST_OVER_COUNT_BAG =
      new BaseApplicable1<Integer, List>(BuiltIn.Z_TEST_OVER_COUNT_BAG) {
        @Override
        public Integer apply(List list) {
          return list.size();
        }
      };

  /** Implements the list variant of {@link BuiltIn#TEST_OVER_COUNT}. */
  private static final Applicable Z_TEST_OVER_COUNT_LIST =
      new BaseApplicable1<Integer, List>(BuiltIn.Z_TEST_OVER_COUNT_LIST) {
        @Override
        public Integer apply(List list) {
          return list.size() + 1000;
        }
      };
}

// End SysCodes.java
