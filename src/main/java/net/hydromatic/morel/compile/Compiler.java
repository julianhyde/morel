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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static net.hydromatic.morel.ast.CoreBuilder.core;
import static net.hydromatic.morel.util.Pair.forEach;
import static net.hydromatic.morel.util.Static.last;
import static net.hydromatic.morel.util.Static.plus;
import static net.hydromatic.morel.util.Static.skip;
import static net.hydromatic.morel.util.Static.str;
import static net.hydromatic.morel.util.Static.transformEager;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.eval.Applicable;
import net.hydromatic.morel.eval.Applicable1;
import net.hydromatic.morel.eval.Applicable2;
import net.hydromatic.morel.eval.Applicable3;
import net.hydromatic.morel.eval.Applicable4;
import net.hydromatic.morel.eval.Closure;
import net.hydromatic.morel.eval.Code;
import net.hydromatic.morel.eval.Codes;
import net.hydromatic.morel.eval.Comparators;
import net.hydromatic.morel.eval.Describer;
import net.hydromatic.morel.eval.EvalEnv;
import net.hydromatic.morel.eval.Prop;
import net.hydromatic.morel.eval.RowSink;
import net.hydromatic.morel.eval.RowSinks;
import net.hydromatic.morel.eval.Session;
import net.hydromatic.morel.eval.Stack;
import net.hydromatic.morel.eval.Unit;
import net.hydromatic.morel.eval.Variant;
import net.hydromatic.morel.foreign.CalciteFunctions;
import net.hydromatic.morel.type.AliasType;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.DataType;
import net.hydromatic.morel.type.FnType;
import net.hydromatic.morel.type.ForallType;
import net.hydromatic.morel.type.Keys;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.RecordLikeType;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.TupleType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.type.TypedValue;
import net.hydromatic.morel.util.ImmutablePairList;
import net.hydromatic.morel.util.Pair;
import net.hydromatic.morel.util.PairList;
import net.hydromatic.morel.util.TailList;
import net.hydromatic.morel.util.ThreadLocals;
import org.apache.calcite.util.TryThreadLocal;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Compiles an expression to code that can be evaluated. */
public class Compiler {
  protected static final EvalEnv EMPTY_ENV = Codes.emptyEnv();

  private static final TryThreadLocal<int[]> ORDINAL_CODE =
      TryThreadLocal.withInitial(() -> new int[] {0});

  protected final TypeSystem typeSystem;

  public Compiler(TypeSystem typeSystem) {
    this.typeSystem = requireNonNull(typeSystem, "typeSystem");
  }

  CompiledStatement compileStatement(
      Environment env,
      Core.Decl decl,
      Core.@Nullable NamedPat skipPat,
      Set<Core.Exp> queriesToWrap) {
    final List<Code> matchCodes = new ArrayList<>();
    final List<Binding> bindings = new ArrayList<>();
    final List<Action> actions = new ArrayList<>();
    final Context cx = Context.of(env);
    compileDecl(
        cx, decl, skipPat, queriesToWrap, matchCodes, bindings, actions);
    final Type type =
        decl instanceof Core.NonRecValDecl
            ? ((Core.NonRecValDecl) decl).pat.type
            : PrimitiveType.UNIT;
    final CalciteFunctions.Context context = createContext(env);

    return new CompiledStatement() {
      public Type getType() {
        return type;
      }

      public void eval(
          Session session,
          Environment env,
          Consumer<String> outLines,
          Consumer<Binding> outBindings) {
        ThreadLocals.let(
            CalciteFunctions.THREAD_ENV,
            context,
            () -> {
              final EvalEnv evalEnv = Codes.emptyEnvWith(session, env);
              for (Action action : actions) {
                action.apply(outLines, outBindings, evalEnv);
              }
            });
      }

      public void getBindings(Consumer<Binding> outBindings) {
        bindings.forEach(outBindings);
      }
    };
  }

  /**
   * Creates a context.
   *
   * <p>The whole way we provide compilation environments (including
   * Environment) to generated code is a mess:
   *
   * <ul>
   *   <li>This method is protected so that CalciteCompiler can override and get
   *       a Calcite type factory.
   *   <li>User-defined functions should have a 'prepare' phase, where they use
   *       a type factory and environment, that is distinct from the 'eval'
   *       phase.
   *   <li>We should pass compile and runtime environments via parameters, not
   *       thread-locals.
   *   <li>The dummy session is there because session is mandatory, but we have
   *       not created a session yet. Lifecycle confusion.
   * </ul>
   */
  protected CalciteFunctions.Context createContext(Environment env) {
    final Session dummySession = new Session(ImmutableMap.of());
    return new CalciteFunctions.Context(dummySession, env, typeSystem, null);
  }

  /**
   * Something that needs to happen when a declaration is evaluated.
   *
   * <p>Usually involves placing a type or value into the bindings that will
   * make up the environment in which the next statement will be executed, and
   * printing some text on the screen.
   */
  interface Action {
    void apply(
        Consumer<String> outLines,
        Consumer<Binding> outBindings,
        EvalEnv evalEnv);
  }

  /** Compilation context. */
  public static class Context {
    final Environment env;

    /** The current stack layout, mapping named patterns to slot indices. */
    final StackLayout layout;

    /**
     * The number of local variable slots currently live on the stack above the
     * frame base.
     *
     * <p>This counter is incremented when pushing a binding (e.g., {@code let}
     * or {@code fn} argument) and decremented when the scope exits. It must
     * equal the number of entries pushed onto {@link
     * net.hydromatic.morel.eval.Stack#slots} at every code point.
     */
    final int localDepth;

    Context(Environment env) {
      this(env, StackLayout.EMPTY, 0);
    }

    Context(Environment env, StackLayout layout, int localDepth) {
      this.env = env;
      this.layout = layout;
      this.localDepth = localDepth;
    }

    static Context of(Environment env) {
      return new Context(env, StackLayout.EMPTY, 0);
    }

    Context bindAll(Iterable<Binding> bindings) {
      return new Context(env.bindAll(bindings), layout, localDepth);
    }
  }

  public final Code compile(Environment env, Core.Exp expression) {
    return compile(Context.of(env), expression);
  }

  /** Compiles the argument to "apply". */
  public Code compileArg(Context cx, Core.Exp expression) {
    return compile(cx, expression);
  }

  /** Compiles the tuple arguments to "apply". */
  public List<Code> compileArgs(
      Context cx, List<? extends Core.Exp> expressions) {
    return transformEager(expressions, e -> compile(cx, e));
  }

  /** Compiles the tuple arguments to "apply". */
  public PairList<Code, Type> compileArgTypes(
      Context cx, List<? extends Core.Exp> expressions) {
    PairList.BiTransformer<Core.Exp, Code, Type> transformer =
        (exp, consumer) -> {
          final Code code = compileArg(cx, exp);
          consumer.accept(code, exp.type);
        };
    return ImmutablePairList.fromTransformed(expressions, transformer);
  }

  /** Compiles an expression that is evaluated once per row. */
  public Code compileRow(Context cx, Core.Exp expression) {
    final int[] ordinalSlots = {0};
    try (TryThreadLocal.Memo ignored = ORDINAL_CODE.push(ordinalSlots)) {
      Code code = compile(cx, expression);
      if (ordinalSlots[0] == 0) {
        return code;
      }
      // The ordinal was used in at least one place.
      // Create a wrapper that will increment the ordinal each time.
      ordinalSlots[0] = -1;
      return Codes.ordinalInc(ordinalSlots, code);
    }
  }

  /**
   * Compiles a collection of expressions that are evaluated once per row.
   *
   * <p>If one or more of those expressions references {@code ordinal}, add a
   * wrapper around the first expression that increments the ordinal, similar to
   * how {@link #compileRow(Context, Core.Exp)} does it.
   */
  private ImmutableSortedMap<String, Code> compileRowMap(
      Context cx, List<? extends Map.Entry<String, Core.Exp>> nameExps) {
    final int[] ordinalSlots = {0};
    try (TryThreadLocal.Memo ignored = ORDINAL_CODE.push(ordinalSlots)) {
      final PairList<String, Code> mapCodes = PairList.of();
      forEach(nameExps, (name, exp) -> mapCodes.add(name, compile(cx, exp)));
      if (ordinalSlots[0] > 0) {
        // The ordinal was used in at least one place.
        // Create a wrapper that will increment the ordinal each time.
        ordinalSlots[0] = -1;
        final List<Code> codes = mapCodes.rightList();
        codes.set(0, Codes.ordinalInc(ordinalSlots, codes.get(0)));
      }
      return ImmutableSortedMap.copyOf(mapCodes, RecordType.ORDERING);
    }
  }

  public Code compile(Context cx, Core.Exp expression) {
    final Core.Literal literal;
    final Code argCode;
    final List<Code> codes;
    switch (expression.op) {
      case BOOL_LITERAL:
        literal = (Core.Literal) expression;
        final Boolean boolValue = literal.unwrap(Boolean.class);
        return Codes.constant(boolValue);

      case CHAR_LITERAL:
        literal = (Core.Literal) expression;
        final Character charValue = literal.unwrap(Character.class);
        return Codes.constant(charValue);

      case INT_LITERAL:
        literal = (Core.Literal) expression;
        return Codes.constant(literal.unwrap(Integer.class));

      case REAL_LITERAL:
        literal = (Core.Literal) expression;
        return Codes.constant(literal.unwrap(Float.class));

      case STRING_LITERAL:
        literal = (Core.Literal) expression;
        final String stringValue = literal.unwrap(String.class);
        return Codes.constant(stringValue);

      case UNIT_LITERAL:
        return Codes.constant(Unit.INSTANCE);

      case FN_LITERAL:
        literal = (Core.Literal) expression;
        return Codes.constant(literal.toBuiltIn(typeSystem, null));

      case INTERNAL_LITERAL:
      case VALUE_LITERAL:
        literal = (Core.Literal) expression;
        return Codes.constant(literal.unwrap(Object.class));

      case LET:
        return compileLet(cx, (Core.Let) expression);

      case LOCAL:
        return compileLocal(cx, (Core.Local) expression);

      case FN:
        final Core.Fn fn = (Core.Fn) expression;
        return compileMatchListTail(
            cx, ImmutableList.of(core.match(fn.pos, fn.idPat, fn.exp)));

      case CASE:
        final Core.Case case_ = (Core.Case) expression;
        final Code matchCode = compileMatchList(cx, case_.matchList);
        argCode = compile(cx, case_.exp);
        return Codes.apply(matchCode, argCode);

      case RECORD_SELECTOR:
        final Core.RecordSelector recordSelector =
            (Core.RecordSelector) expression;
        return Codes.nth(recordSelector.slot).asCode();

      case APPLY:
        return compileApply(cx, (Core.Apply) expression);

      case FROM:
        return compileFrom(cx, (Core.From) expression);

      case ID:
        final Core.Id id = (Core.Id) expression;
        return compileFieldName(cx, id.idPat);

      case TUPLE:
        final Core.Tuple tuple = (Core.Tuple) expression;
        codes = new ArrayList<>();
        for (Core.Exp arg : tuple.args) {
          codes.add(compile(cx, arg));
        }
        return Codes.tuple(codes);

      default:
        throw new AssertionError("op not handled: " + expression.op);
    }
  }

  private Code compileFieldName(Context cx, Core.NamedPat idPat) {
    final int slotIndex = cx.layout.get(idPat);
    if (slotIndex >= 0) {
      // Offset is 1-based from the current top of the stack.
      // localDepth is the number of slots currently allocated;
      // slot i (0-based) is at offset (localDepth - i) from the top.
      return Codes.stackGet(cx.localDepth - slotIndex, idPat.name);
    }
    final Binding binding = cx.env.getOpt(idPat);
    if (binding != null && binding.value instanceof Code) {
      // Don't inline LinkCode (recursive bindings): the code will be a
      // StackMatchCode whose capture offsets were computed at the outer
      // context's depth. Inlining it here (at a different stack depth)
      // produces wrong captures. Use a runtime lookup instead so that the
      // StackClosure placed in globalEnv by Let1Code/LetCode is found.
      if (binding.value instanceof LinkCode) {
        return Codes.get(idPat.name);
      }
      return (Code) binding.value;
    }
    return Codes.get(idPat.name);
  }

  protected Code compileApply(Context cx, Core.Apply apply) {
    return compileApply(cx, apply, false);
  }

  private Code compileApply(Context cx, Core.Apply apply, boolean tailPos) {
    // Is this is a call to a built-in operator?
    switch (apply.fn.op) {
      case FN_LITERAL:
        final BuiltIn builtIn = ((Core.Literal) apply.fn).unwrap(BuiltIn.class);
        final List<Code> argCodes;
        switch (builtIn) {
          case Z_ANDALSO:
            // Argument for a built-in infix operator such as "andalso" is
            // always a tuple; operators are never curried, nor do they evaluate
            // an expression to yield the tuple of arguments.
            argCodes = compileArgs(cx, ((Core.Tuple) apply.arg).args);
            return Codes.andAlso(argCodes.get(0), argCodes.get(1));
          case Z_ORELSE:
            argCodes = compileArgs(cx, ((Core.Tuple) apply.arg).args);
            return Codes.orElse(argCodes.get(0), argCodes.get(1));
          case Z_LIST:
            argCodes = compileArgs(cx, ((Core.Tuple) apply.arg).args);
            return Codes.list(argCodes);
          case Z_ORDINAL:
            int[] ordinalSlots = ORDINAL_CODE.get();
            ordinalSlots[0]++; // signal that we are using an ordinal
            return Codes.ordinalGet(ordinalSlots);
          default:
            if (true) {
              break;
            }
            final Object o =
                ((Core.Literal) apply.fn).toBuiltIn(typeSystem, apply.pos);
            if (o instanceof Applicable) {
              final Code argCode = compile(cx, apply.arg);
              if (argCode instanceof Codes.TupleCode) {
                final Codes.TupleCode tupleCode = (Codes.TupleCode) argCode;
                if (tupleCode.codes.size() == 2 && o instanceof Applicable2) {
                  //noinspection rawtypes
                  return Codes.apply2(
                      (Applicable2) o,
                      tupleCode.codes.get(0),
                      tupleCode.codes.get(1));
                }
                if (tupleCode.codes.size() == 3 && o instanceof Applicable3) {
                  //noinspection rawtypes
                  return Codes.apply3(
                      (Applicable3) o,
                      tupleCode.codes.get(0),
                      tupleCode.codes.get(1),
                      tupleCode.codes.get(2));
                }
              }
              return Codes.apply((Applicable) o, argCode);
            }
            throw new AssertionError("unknown " + builtIn);
        }
    }
    final Gather gather = Gather.of(apply);
    if (gather != null) {
      // If we have a gather, we can compile the argument and return the
      // gather code.
      final @Nullable Applicable1 applicable1;
      final @Nullable Applicable2 applicable2;
      final @Nullable Applicable3 applicable3;
      final @Nullable Applicable4 applicable4;
      switch (gather.args.size()) {
        case 1:
          if (gather.argIsTuple(0, 2)) {
            applicable2 = gather.fnLiteral.toApplicable2(typeSystem, apply.pos);
            if (applicable2 != null) {
              final List<Code> argCodes = compileArgs(cx, gather.args);
              return Codes.apply2Tuple(applicable2, argCodes.get(0));
            }
          }
          if (gather.argIsTuple(0, 3)) {
            applicable3 = gather.fnLiteral.toApplicable3(typeSystem, apply.pos);
            if (applicable3 != null) {
              final List<Code> argCodes = compileArgs(cx, gather.args);
              return Codes.apply3Tuple(applicable3, argCodes.get(0));
            }
          }
          // Compile for partial evaluation, e.g. applying "String.isPrefix:
          // string -> string -> bool", a curried function with two arguments,
          // to just one argument.
          applicable1 = gather.fnLiteral.toApplicable1(typeSystem, apply.pos);
          if (applicable1 != null) {
            final List<Code> argCodes = compileArgs(cx, gather.args);
            return Codes.apply1(applicable1, argCodes.get(0));
          }
          break;
        case 2:
          applicable2 = gather.fnLiteral.toApplicable2(typeSystem, apply.pos);
          if (applicable2 != null) {
            final PairList<Code, Type> argCodes =
                compileArgTypes(cx, gather.args);
            return finishCompileApply2(cx, applicable2, argCodes);
          }
          break;
        case 3:
          applicable3 = gather.fnLiteral.toApplicable3(typeSystem, apply.pos);
          if (applicable3 != null) {
            final List<Code> argCodes = compileArgs(cx, gather.args);
            return Codes.apply3(
                applicable3, argCodes.get(0), argCodes.get(1), argCodes.get(2));
          }
          break;
        case 4:
          applicable4 = gather.fnLiteral.toApplicable4(typeSystem, apply.pos);
          if (applicable4 != null) {
            final List<Code> argCodes = compileArgs(cx, gather.args);
            return Codes.apply4(
                applicable4,
                argCodes.get(0),
                argCodes.get(1),
                argCodes.get(2),
                argCodes.get(3));
          }
          break;
        default:
          throw new UnsupportedOperationException(
              "arity " + gather.args.size());
      }
    }

    final Code argCode = compileArg(cx, apply.arg);
    final Type argType = apply.arg.type;
    final Applicable fnValue =
        compileApplicable(cx, apply.fn, argType, apply.pos);
    if (fnValue != null) {
      return tailPos
          ? Codes.tailApply(fnValue, argCode)
          : finishCompileApply(cx, fnValue, argCode, argType);
    }
    final Code fnCode = compile(cx, apply.fn);
    return tailPos
        ? Codes.tailApply(fnCode, argCode)
        : finishCompileApply(cx, fnCode, argCode, argType);
  }

  /**
   * Returns the arity of a function.
   *
   * <ul>
   *   <li>{@code 5: int} has arity -1;
   *   <li>{@code Sys.env: unit &rarr; string list} has arity 0;
   *   <li>{@code String.explode: string &rarr; char list} has arity 1;
   *   <li>{@code Math.atan2: real * real &rarr; real} has arity 2;
   *   <li>{@code Real.fromManExp: {exp:int, man:real} &rarr; real} has arity 2.
   * </ul>
   */
  private static int arity(Core.Exp fn) {
    Type type = fn.type;
    while (type instanceof ForallType) {
      type = ((ForallType) type).type;
    }
    if (!(type instanceof FnType)) {
      return -1;
    }
    final FnType fnType = (FnType) type;
    if (fnType.paramType instanceof TupleType) {
      return ((TupleType) fnType.paramType).argTypes.size();
    } else if (fnType.paramType instanceof RecordType) {
      return ((RecordType) fnType.paramType).argNameTypes.size();
    } else {
      return 1;
    }
  }

  protected Code finishCompileApply(
      Context cx, Applicable fnValue, Code argCode, Type argType) {
    return Codes.apply(fnValue, argCode);
  }

  protected Code finishCompileApply(
      Context cx, Code fnCode, Code argCode, Type argType) {
    return Codes.apply(fnCode, argCode);
  }

  @SuppressWarnings("rawtypes")
  protected Code finishCompileApply2(
      Context cx, Applicable2 applicable2, PairList<Code, Type> argCodes) {
    return Codes.apply2(applicable2, argCodes.left(0), argCodes.left(1));
  }

  protected Code compileFrom(Context cx, Core.From from) {
    Supplier<RowSink> rowSinkFactory =
        createRowSinkFactory(
            cx, Core.StepEnv.EMPTY, from.steps, from.type().elementType());
    Supplier<RowSink> firstRowSinkFactory =
        () -> RowSinks.first(rowSinkFactory.get());
    return RowSinks.from(firstRowSinkFactory);
  }

  protected Supplier<RowSink> createRowSinkFactory(
      Context cx0,
      Core.StepEnv stepEnv,
      List<Core.FromStep> steps,
      Type elementType) {
    final Context cx = cx0.bindAll(stepEnv.bindings);
    if (steps.isEmpty()) {
      final List<String> fieldNames =
          stepEnv.bindings.stream()
              .map(b -> b.id.name)
              .sorted()
              .collect(toImmutableList());
      final Code code;
      if (fieldNames.size() == 1
          && stepEnv.bindings.get(0).id.type.equals(elementType)) {
        code = Codes.get(fieldNames.get(0));
      } else {
        code = Codes.getTuple(fieldNames);
      }
      return () -> RowSinks.collect(code);
    }
    final Core.FromStep firstStep = steps.get(0);
    final Supplier<RowSink> nextFactory =
        createRowSinkFactory(cx, firstStep.env, skip(steps), elementType);
    final ImmutableList<Code> inputCodes;
    final ImmutableList<String> outNames;
    final Code code;
    switch (firstStep.op) {
      case SCAN:
        final Core.Scan scan = (Core.Scan) firstStep;
        code = compileRow(cx, scan.exp);
        final Code conditionCode = compile(cx, scan.condition);
        return () ->
            RowSinks.scan(
                firstStep.op, scan.pat, code, conditionCode, nextFactory.get());

      case WHERE:
        final Core.Where where = (Core.Where) firstStep;
        final Code filterCode = compileRow(cx, where.exp);
        return () -> RowSinks.where(filterCode, nextFactory.get());

      case SKIP:
        final Core.Skip skip = (Core.Skip) firstStep;
        final Code skipCode = compile(cx, skip.exp);
        return () -> RowSinks.skip(skipCode, nextFactory.get());

      case TAKE:
        final Core.Take take = (Core.Take) firstStep;
        final Code takeCode = compile(cx, take.exp);
        return () -> RowSinks.take(takeCode, nextFactory.get());

      case EXCEPT:
        final Core.Except except = (Core.Except) firstStep;
        inputCodes = transformEager(except.args, arg -> compile(cx, arg));
        outNames = bindingNames(stepEnv.bindings);
        return () ->
            RowSinks.except(
                except.distinct, inputCodes, outNames, nextFactory.get());

      case INTERSECT:
        final Core.Intersect intersect = (Core.Intersect) firstStep;
        inputCodes = transformEager(intersect.args, arg -> compile(cx, arg));
        outNames = bindingNames(stepEnv.bindings);
        return () ->
            RowSinks.intersect(
                intersect.distinct, inputCodes, outNames, nextFactory.get());

      case UNION:
        final Core.Union union = (Core.Union) firstStep;
        inputCodes = transformEager(union.args, arg -> compile(cx, arg));
        outNames = bindingNames(stepEnv.bindings);
        return () ->
            RowSinks.union(
                union.distinct, inputCodes, outNames, nextFactory.get());

      case YIELD:
        final Core.Yield yield = (Core.Yield) firstStep;
        if (steps.size() == 1) {
          // Last step. Use a Collect row sink, and we're done.
          // Note that we don't use nextFactory.
          final Code yieldCode = compileRow(cx, yield.exp);
          return () -> RowSinks.collect(yieldCode);
        } else if (yield.exp instanceof Core.Tuple) {
          final Core.Tuple tuple = (Core.Tuple) yield.exp;
          final RecordLikeType recordType = tuple.type();
          final Map<String, Code> codeMap =
              compileRowMap(cx, Pair.zip(recordType.argNames(), tuple.args));
          return () -> RowSinks.yield(codeMap, nextFactory.get());
        } else {
          final Binding binding = yield.env.bindings.get(0);
          Map<String, Code> codeMap =
              compileRowMap(cx, PairList.of(binding.id.name, yield.exp));
          return () -> RowSinks.yield(codeMap, nextFactory.get());
        }

      case ORDER:
        final Core.Order order = (Core.Order) firstStep;
        code = compile(cx, order.exp);
        final Comparator comparator =
            Comparators.comparatorFor(typeSystem, order.exp.type);
        return () ->
            RowSinks.order(code, comparator, stepEnv, nextFactory.get());

      case UNORDER:
        // No explicit step is required. Unorder is a change in type, but
        // ordered and unordered streams have the same representation.
        return nextFactory;

      case GROUP:
        final Core.Group group = (Core.Group) firstStep;
        final ImmutableList.Builder<Code> groupCodesB = ImmutableList.builder();
        for (Core.Exp exp : group.groupExps.values()) {
          groupCodesB.add(compile(cx, exp));
        }
        final ImmutableList.Builder<Code> valueCodesB = ImmutableList.builder();
        final SortedMap<String, Binding> bindingMap =
            sortedBindingMap(stepEnv.bindings);
        for (Binding binding : bindingMap.values()) {
          valueCodesB.add(compile(cx, core.id(binding.id)));
        }
        final ImmutableList<String> names =
            ImmutableList.copyOf(bindingMap.keySet());
        final ImmutableList.Builder<Applicable> aggregateCodesB =
            ImmutableList.builder();
        for (Core.Aggregate aggregate : group.aggregates.values()) {
          final Code argumentCode;
          final Type argumentType;
          if (aggregate.argument == null) {
            final PairList<String, Type> argNameTypes = PairList.of();
            stepEnv.bindings.forEach(
                b -> argNameTypes.add(b.id.name, b.id.type));
            argumentType = typeSystem.recordOrScalarType(argNameTypes);
            argumentCode = null;
          } else {
            argumentType = aggregate.argument.type;
            argumentCode = compile(cx, aggregate.argument);
          }
          Type aggType = aggregate.aggregate.type;
          if (aggType instanceof ForallType) {
            aggType = ((ForallType) aggType).type;
          }
          final Type aggParamType = ((FnType) aggType).paramType;
          final Applicable aggregateApplicable =
              compileApplicable(
                  cx, aggregate.aggregate, aggParamType, aggregate.pos);
          final Code aggregateCode;
          if (aggregateApplicable == null) {
            aggregateCode = compile(cx, aggregate.aggregate);
          } else {
            aggregateCode = aggregateApplicable.asCode();
          }
          aggregateCodesB.add(
              Codes.aggregate(cx.env, aggregateCode, names, argumentCode));
        }
        final ImmutableList<Code> groupCodes = groupCodesB.build();
        final Code keyCode = Codes.tuple(groupCodes);
        final ImmutableList<Applicable> aggregateCodes =
            aggregateCodesB.build();
        outNames = bindingNames(firstStep.env.bindings);
        final ImmutableList<String> keyNames =
            outNames.subList(0, group.groupExps.size());
        return () ->
            RowSinks.group(
                keyCode,
                aggregateCodes,
                names,
                keyNames,
                outNames,
                nextFactory.get());

      default:
        throw new AssertionError("unknown step type " + firstStep.op);
    }
  }

  private ImmutableSortedMap<String, Binding> sortedBindingMap(
      Iterable<Binding> bindings) {
    final ImmutableSortedMap.Builder<String, Binding> b =
        ImmutableSortedMap.orderedBy(RecordType.ORDERING);
    bindings.forEach(binding -> b.put(binding.id.name, binding));
    return b.build();
  }

  private ImmutableList<String> bindingNames(List<Binding> bindings) {
    return transformEager(bindings, b -> b.id.name);
  }

  /**
   * Compiles a function value to an {@link Applicable}, if possible, or returns
   * null.
   */
  private @Nullable Applicable compileApplicable(
      Context cx, Core.Exp fn, Type argType, Pos pos) {
    final Core.Literal literal;
    switch (fn.op) {
      case FN_LITERAL:
        literal = (Core.Literal) fn;
        return toApplicable(
            cx, literal.toBuiltIn(typeSystem, null), argType, pos);

      case VALUE_LITERAL:
        literal = (Core.Literal) fn;
        return toApplicable(cx, literal.unwrap(Object.class), argType, pos);

      case ID:
        final Pair<Binding, Environment> bindingPair =
            cx.env.getOpt2(((Core.Id) fn).idPat);
        Binding binding = bindingPair == null ? null : bindingPair.left;
        if (binding == null
            || binding.value instanceof LinkCode
            || binding.value == Unit.INSTANCE) {
          return null;
        }
        if (binding.kind != Binding.Kind.VAL) {
          final List<Binding> bindings = new ArrayList<>();
          cx.env.collect(
              ((Core.Id) fn).idPat,
              instBinding -> {
                if (instBinding.id.type.canCallArgOf(argType)) {
                  bindings.add(instBinding);
                }
              });
          if (bindings.size() == 1) {
            binding = bindings.get(0);
          } else {
            throw new AssertionError(
                argType + " matches functions with arguments " + bindings);
          }
        }
        return toApplicable(cx, binding.value, argType, pos);

      case RECORD_SELECTOR:
        final Core.RecordSelector recordSelector = (Core.RecordSelector) fn;
        return Codes.nth(recordSelector.slot);

      default:
        return null;
    }
  }

  private @Nullable Applicable toApplicable(
      Context cx, Object o, Type argType, Pos pos) {
    if (o instanceof Applicable) {
      final Applicable applicable = (Applicable) o;
      if (applicable instanceof Codes.Positioned) {
        return ((Codes.Positioned) applicable).withPos(pos);
      }
      return applicable;
    }
    if (o instanceof Macro) {
      final Macro value = (Macro) o;
      final Core.Exp exp = value.expand(typeSystem, cx.env, argType);
      switch (exp.op) {
        case FN_LITERAL:
          final Core.Literal literal = (Core.Literal) exp;
          return (Applicable) literal.toBuiltIn(typeSystem, null);
      }
      final Code code = compile(cx, exp);
      return new Applicable() {
        @Override
        public Describer describe(Describer describer) {
          return code.describe(describer);
        }

        @Override
        public Object apply(EvalEnv evalEnv, Object arg) {
          return code.eval(evalEnv);
        }
      };
    }
    return null;
  }

  private Code compileLet(Context cx, Core.Let let) {
    if (let.decl.op == Op.VAL_DECL) {
      final Code stackLetCode =
          tryCompileLetStack(cx, (Core.NonRecValDecl) let.decl, let.exp);
      if (stackLetCode != null) {
        return stackLetCode;
      }
    }
    final List<Code> matchCodes = new ArrayList<>();
    final List<Binding> bindings = new ArrayList<>();
    compileValDecl(
        cx, let.decl, null, ImmutableSet.of(), matchCodes, bindings, null);
    Context cx2 = cx.bindAll(bindings);
    final Code resultCode = compile(cx2, let.exp);
    return finishCompileLet(cx2, matchCodes, resultCode, let.type);
  }

  /**
   * Tries to compile a {@code let val x = expr in body} as a stack-based
   * push/pop. Returns null if the pattern is too complex (fall back to old-mode
   * let).
   */
  protected @Nullable Code tryCompileLetStack(
      Context cx, Core.NonRecValDecl valDecl, Core.Exp bodyExp) {
    if (valDecl.pat.op == Op.WILDCARD_PAT) {
      // "val _ = expr in body": evaluate expr for side effects, ignore result.
      final Code expCode = compile(cx, valDecl.exp);
      final Code bodyCode = compile(cx, bodyExp);
      return Codes.stackLet1(expCode, bodyCode);
    }
    if (valDecl.pat.op != Op.ID_PAT) {
      return null;
    }
    final Core.NamedPat xPat = (Core.NamedPat) valDecl.pat;
    // Detect the form "let val $tmp = expr in case $tmp of pat => body end",
    // which is how the Resolver desugars "let val (x, y) = expr in body end".
    // Compile directly as a multi-variable stack push, avoiding an intermediate
    // closure.
    if (bodyExp.op == Op.CASE) {
      final Core.Case case_ = (Core.Case) bodyExp;
      if (case_.matchList.size() == 1
          && case_.exp.op == Op.ID
          && ((Core.Id) case_.exp).idPat == xPat) {
        return compileLetStackPat(
            cx, valDecl.exp, case_.matchList.get(0), false);
      }
    }
    final Code expCode = compile(cx, valDecl.exp);
    final StackLayout newLayout = cx.layout.with(xPat, cx.localDepth);
    final List<Binding> bindings = new ArrayList<>();
    Compiles.acceptBinding(typeSystem, valDecl.pat, bindings);
    final Context cx2 =
        new Context(cx.env.bindAll(bindings), newLayout, cx.localDepth + 1);
    final Code bodyCode = compile(cx2, bodyExp);
    return Codes.stackLet1(expCode, bodyCode);
  }

  /**
   * Tries to compile a {@code let val x = expr in body} as a stack-based
   * push/pop, with the body in tail position. Returns null if the pattern is
   * too complex (fall back to old-mode let).
   */
  protected @Nullable Code tryCompileLetStackTail(
      Context cx, Core.NonRecValDecl valDecl, Core.Exp bodyExp) {
    if (valDecl.pat.op == Op.WILDCARD_PAT) {
      // "val _ = expr in body": evaluate expr for side effects, ignore result.
      final Code expCode = compile(cx, valDecl.exp);
      final Code bodyCode = compileTail(cx, bodyExp);
      return Codes.stackLet1(expCode, bodyCode);
    }
    if (valDecl.pat.op != Op.ID_PAT) {
      return null;
    }
    final Core.NamedPat xPat = (Core.NamedPat) valDecl.pat;
    // Detect the desugared form (tail position); see tryCompileLetStack.
    if (bodyExp.op == Op.CASE) {
      final Core.Case case_ = (Core.Case) bodyExp;
      if (case_.matchList.size() == 1
          && case_.exp.op == Op.ID
          && ((Core.Id) case_.exp).idPat == xPat) {
        return compileLetStackPat(
            cx, valDecl.exp, case_.matchList.get(0), true);
      }
    }
    final Code expCode = compile(cx, valDecl.exp);
    final StackLayout newLayout = cx.layout.with(xPat, cx.localDepth);
    final List<Binding> bindings = new ArrayList<>();
    Compiles.acceptBinding(typeSystem, valDecl.pat, bindings);
    final Context cx2 =
        new Context(cx.env.bindAll(bindings), newLayout, cx.localDepth + 1);
    final Code bodyCode = compileTail(cx2, bodyExp);
    return Codes.stackLet1(expCode, bodyCode);
  }

  /**
   * Compiles a let binding with a general pattern (tuple, record, etc.) as
   * direct stack pushes, avoiding an intermediate closure.
   *
   * <p>Called when the Resolver's desugared form {@code let val $tmp = expr in
   * case $tmp of pat => body end} is detected. Emits a {@link
   * Codes#stackLetPat} node that evaluates {@code valueExp}, calls {@link
   * Closure.StackClosure#pushBindings} to push each pattern-bound variable,
   * evaluates the body, then restores the stack.
   */
  private Code compileLetStackPat(
      Context cx, Core.Exp valueExp, Core.Match match, boolean tailPos) {
    final Core.Pat pat = match.pat;
    final List<Core.NamedPat> namedPats = namedPatsOf(pat);
    final Code expCode = compile(cx, valueExp);
    // Assign sequential slots starting at cx.localDepth, in the same order
    // that pushBindings pushes them.
    StackLayout newLayout = cx.layout;
    for (int i = 0; i < namedPats.size(); i++) {
      newLayout = newLayout.with(namedPats.get(i), cx.localDepth + i);
    }
    final List<Binding> bindings = new ArrayList<>();
    Compiles.acceptBinding(typeSystem, pat, bindings);
    final Context cx2 =
        new Context(
            cx.env.bindAll(bindings),
            newLayout,
            cx.localDepth + namedPats.size());
    final Code bodyCode =
        tailPos ? compileTail(cx2, match.exp) : compile(cx2, match.exp);
    return Codes.stackLetPat(
        pat, namedPats.size(), expCode, bodyCode, match.pos);
  }

  protected Code finishCompileLet(
      Context cx, List<Code> matchCodes, Code resultCode, Type resultType) {
    return Codes.let(matchCodes, resultCode);
  }

  private Code compileLocal(Context cx, Core.Local local) {
    final List<Binding> bindings = new ArrayList<>();
    compileDatatypeDecl(ImmutableList.of(local.dataType), bindings, null);
    Context cx2 = cx.bindAll(bindings);
    return compile(cx2, local.exp);
  }

  void compileDecl(
      Context cx,
      Core.Decl decl,
      Core.@Nullable NamedPat skipPat,
      Set<Core.Exp> queriesToWrap,
      List<Code> matchCodes,
      List<Binding> bindings,
      List<Action> actions) {
    switch (decl.op) {
      case VAL_DECL:
      case REC_VAL_DECL:
        final Core.ValDecl valDecl = (Core.ValDecl) decl;
        compileValDecl(
            cx, valDecl, skipPat, queriesToWrap, matchCodes, bindings, actions);
        break;

      case OVER_DECL:
        final Core.OverDecl overDecl = (Core.OverDecl) decl;
        compileOverDecl(overDecl, bindings, actions);
        break;

      case TYPE_DECL:
        final Core.TypeDecl typeDecl = (Core.TypeDecl) decl;
        compileTypeDecl(typeDecl.types, bindings, actions);
        break;

      case DATATYPE_DECL:
        final Core.DatatypeDecl datatypeDecl = (Core.DatatypeDecl) decl;
        compileDatatypeDecl(datatypeDecl.dataTypes, bindings, actions);
        break;

      default:
        throw new AssertionError("unknown " + decl.op + " [" + decl + "]");
    }
  }

  private void compileOverDecl(
      Core.OverDecl overDecl,
      List<Binding> bindings,
      @Nullable List<Action> actions) {
    bindings.add(Binding.over(overDecl.pat));
    if (actions != null) {
      actions.add(
          (outLines, outBindings, evalEnv) ->
              outLines.accept("over " + overDecl.pat));
    }
  }

  private void compileTypeDecl(
      List<AliasType> types,
      List<Binding> bindings,
      @Nullable List<Action> actions) {
    if (actions != null) {
      for (AliasType type : types) {
        actions.add(
            (outLines, outBindings, evalEnv) ->
                outLines.accept("type " + type.name + " = " + type.type.key()));
      }
    }
  }

  private void compileDatatypeDecl(
      List<DataType> dataTypes,
      List<Binding> bindings,
      @Nullable List<Action> actions) {
    for (DataType dataType : dataTypes) {
      final List<Binding> newBindings = new TailList<>(bindings);
      dataType
          .typeConstructors
          .keySet()
          .forEach(name -> bindings.add(typeSystem.bindTyCon(dataType, name)));
      if (actions != null) {
        final List<Binding> immutableBindings =
            ImmutableList.copyOf(newBindings);
        actions.add(
            (outLines, outBindings, evalEnv) -> {
              String line = dataType.describe(new StringBuilder()).toString();
              outLines.accept(line);
              immutableBindings.forEach(outBindings);
            });
      }
    }
  }

  /**
   * Returns the named patterns that {@link Closure#bindRecurse} (and {@link
   * Closure.StackClosure#pushBindings}) would bind when matching {@code pat},
   * in the order they are bound.
   */
  private static List<Core.NamedPat> namedPatsOf(Core.Pat pat) {
    final List<Core.NamedPat> result = new ArrayList<>();
    collectNamedPats(pat, result);
    return result;
  }

  private static void collectNamedPats(
      Core.Pat pat, List<Core.NamedPat> result) {
    switch (pat.op) {
      case ID_PAT:
        result.add((Core.IdPat) pat);
        break;
      case WILDCARD_PAT:
      case BOOL_LITERAL_PAT:
      case CHAR_LITERAL_PAT:
      case INT_LITERAL_PAT:
      case REAL_LITERAL_PAT:
      case STRING_LITERAL_PAT:
      case CON0_PAT:
        break;
      case AS_PAT:
        final Core.AsPat asPat = (Core.AsPat) pat;
        result.add(asPat);
        collectNamedPats(asPat.pat, result);
        break;
      case TUPLE_PAT:
        ((Core.TuplePat) pat).args.forEach(p -> collectNamedPats(p, result));
        break;
      case RECORD_PAT:
        ((Core.RecordPat) pat).args.forEach(p -> collectNamedPats(p, result));
        break;
      case LIST_PAT:
        ((Core.ListPat) pat).args.forEach(p -> collectNamedPats(p, result));
        break;
      case CONS_PAT:
      case CON_PAT:
        collectNamedPats(((Core.ConPat) pat).pat, result);
        break;
      default:
        break;
    }
  }

  /**
   * Compiles a {@code match} expression.
   *
   * @param cx Compile context
   * @param matchList List of Match
   * @return Code for match
   */
  private Code compileMatchList(Context cx, List<Core.Match> matchList) {
    return compileMatchListImpl(cx, matchList, false);
  }

  /**
   * Compiles an expression in tail position, emitting {@link Codes#tailApply}
   * at tail-call sites so that the trampoline in {@link Closure#bindEval} can
   * execute them in O(1) stack space.
   */
  protected Code compileTail(Context cx, Core.Exp expression) {
    switch (expression.op) {
      case APPLY:
        return compileApply(cx, (Core.Apply) expression, true);

      case CASE:
        final Core.Case case_ = (Core.Case) expression;
        final Code matchCode = compileMatchListTail(cx, case_.matchList);
        final Code argCode = compile(cx, case_.exp);
        return Codes.tailApply(matchCode, argCode);

      case LET:
        final Core.Let let = (Core.Let) expression;
        if (let.decl.op == Op.VAL_DECL) {
          final Code stackLetCode =
              tryCompileLetStackTail(
                  cx, (Core.NonRecValDecl) let.decl, let.exp);
          if (stackLetCode != null) {
            return stackLetCode;
          }
        }
        final List<Code> matchCodes = new ArrayList<>();
        final List<Binding> bindings = new ArrayList<>();
        compileValDecl(
            cx, let.decl, null, ImmutableSet.of(), matchCodes, bindings, null);
        final Context cx2 = cx.bindAll(bindings);
        final Code resultCode = compileTail(cx2, let.exp);
        return finishCompileLet(cx2, matchCodes, resultCode, let.type);

      default:
        return compile(cx, expression);
    }
  }

  /** Compiles a match list where each arm is in tail position. */
  private Code compileMatchListTail(Context cx, List<Core.Match> matchList) {
    return compileMatchListImpl(cx, matchList, true);
  }

  /**
   * Core implementation for compiling a match list.
   *
   * <p>Emits a {@link StackMatchCode} that captures currently live stack
   * variables and assigns inner slots for the pattern-bound variables.
   *
   * @param cx Outer compile context
   * @param matchList The match arms to compile
   * @param tailPos Whether the arm bodies are in tail position
   */
  private Code compileMatchListImpl(
      Context cx, List<Core.Match> matchList, boolean tailPos) {
    // Stack-mode: collect variables currently live in the outer stack layout.
    // These become the captured variables in the new StackClosure.
    // We use a LinkedHashMap to maintain a stable insertion order.
    final LinkedHashMap<Core.NamedPat, Integer> captureMap =
        new LinkedHashMap<>();
    // For simplicity in this step, we collect all referenced stack variables.
    // Safe but conservative: captures all live stack vars even if unused.
    // A future step can optimize this with VariableCollector.
    for (Core.Match match : matchList) {
      collectReferencedStackVars(cx, match.exp, captureMap);
    }

    final int numCapture = captureMap.size();

    // Build capture offsets: for each captured var, compute 1-based offset from
    // the current stack top.
    final int[] captureOffsets = new int[numCapture];
    for (Map.Entry<Core.NamedPat, Integer> e : captureMap.entrySet()) {
      final int outerSlot = cx.layout.get(e.getKey());
      // offset = localDepth - outerSlot (1-based from top = localDepth)
      captureOffsets[e.getValue()] = cx.localDepth - outerSlot;
    }

    // Compile each arm with a fresh inner context.
    final PairList<Core.Pat, Code> patCodes = PairList.of();
    for (Core.Match match : matchList) {
      final List<Core.NamedPat> argPats = namedPatsOf(match.pat);
      final int numArgs = argPats.size();

      // Build inner layout: captured vars at 0..numCapture-1, arg vars next.
      StackLayout innerLayout = StackLayout.EMPTY;
      for (Map.Entry<Core.NamedPat, Integer> e : captureMap.entrySet()) {
        innerLayout = innerLayout.with(e.getKey(), e.getValue());
      }
      for (int i = 0; i < argPats.size(); i++) {
        innerLayout = innerLayout.with(argPats.get(i), numCapture + i);
      }
      final int innerDepth = numCapture + numArgs;

      final List<Binding> bindings = new ArrayList<>();
      Compiles.acceptBinding(typeSystem, match.pat, bindings);
      final Context innerCx =
          new Context(cx.env.bindAll(bindings), innerLayout, innerDepth);

      final Code bodyCode =
          tailPos
              ? compileTail(innerCx, match.exp)
              : compile(innerCx, match.exp);
      patCodes.add(match.pat, bodyCode);
    }

    // Compute minimum slots needed when this closure is invoked fresh
    // (no pre-existing Stack). Max over all arms of:
    //   captureOffsets.length + numArgVars + bodyCode.maxSlots()
    int capacity = 0;
    for (Map.Entry<Core.Pat, Code> e : patCodes) {
      final int numArgs = namedPatsOf(e.getKey()).size();
      capacity =
          Math.max(
              capacity,
              captureOffsets.length + numArgs + e.getValue().maxSlots());
    }

    return new StackMatchCode(
        captureOffsets, patCodes.immutable(), capacity, last(matchList).pos);
  }

  /**
   * Collects stack variables from {@code exp} that are in {@code cx.layout}
   * into {@code captureMap}, assigning each a unique capture index.
   *
   * <p>Walks the expression tree (including into nested {@code fn} bodies) and
   * records any ID reference whose pattern is allocated in the outer layout.
   */
  private static void collectReferencedStackVars(
      Context cx,
      Core.Exp exp,
      LinkedHashMap<Core.NamedPat, Integer> captureMap) {
    collectReferencedStackVarsRec(cx.layout, exp, captureMap);
  }

  private static void collectReferencedStackVarsRec(
      StackLayout layout,
      Core.Exp exp,
      LinkedHashMap<Core.NamedPat, Integer> captureMap) {
    switch (exp.op) {
      case ID:
        final Core.Id id = (Core.Id) exp;
        if (layout.get(id.idPat) >= 0) {
          captureMap.computeIfAbsent(id.idPat, k -> captureMap.size());
        }
        break;
      case LET:
        final Core.Let let = (Core.Let) exp;
        // Visit the decl's expressions
        let.decl.forEachBinding(
            (pat, e, overloadPat, pos) ->
                collectReferencedStackVarsRec(layout, e, captureMap));
        collectReferencedStackVarsRec(layout, let.exp, captureMap);
        break;
      case FN:
        final Core.Fn fn = (Core.Fn) exp;
        // The fn body uses a new scope; we still need to check references in it
        // that point to the outer layout.
        collectReferencedStackVarsRec(layout, fn.exp, captureMap);
        break;
      case APPLY:
        final Core.Apply apply = (Core.Apply) exp;
        collectReferencedStackVarsRec(layout, apply.fn, captureMap);
        collectReferencedStackVarsRec(layout, apply.arg, captureMap);
        break;
      case TUPLE:
        final Core.Tuple tuple = (Core.Tuple) exp;
        tuple.args.forEach(
            e -> collectReferencedStackVarsRec(layout, e, captureMap));
        break;
      case CASE:
        final Core.Case case_ = (Core.Case) exp;
        collectReferencedStackVarsRec(layout, case_.exp, captureMap);
        case_.matchList.forEach(
            m -> collectReferencedStackVarsRec(layout, m.exp, captureMap));
        break;
      case LOCAL:
        final Core.Local local = (Core.Local) exp;
        collectReferencedStackVarsRec(layout, local.exp, captureMap);
        break;
      case FROM:
        final Core.From from = (Core.From) exp;
        for (Core.FromStep step : from.steps) {
          if (step instanceof Core.Scan) {
            final Core.Scan scan = (Core.Scan) step;
            collectReferencedStackVarsRec(layout, scan.exp, captureMap);
            collectReferencedStackVarsRec(layout, scan.condition, captureMap);
          } else if (step instanceof Core.Where) {
            collectReferencedStackVarsRec(
                layout, ((Core.Where) step).exp, captureMap);
          } else if (step instanceof Core.Skip) {
            collectReferencedStackVarsRec(
                layout, ((Core.Skip) step).exp, captureMap);
          } else if (step instanceof Core.Take) {
            collectReferencedStackVarsRec(
                layout, ((Core.Take) step).exp, captureMap);
          } else if (step instanceof Core.Order) {
            collectReferencedStackVarsRec(
                layout, ((Core.Order) step).exp, captureMap);
          } else if (step instanceof Core.Group) {
            final Core.Group group = (Core.Group) step;
            group
                .groupExps
                .values()
                .forEach(
                    e -> collectReferencedStackVarsRec(layout, e, captureMap));
            group
                .aggregates
                .values()
                .forEach(
                    agg -> {
                      collectReferencedStackVarsRec(
                          layout, agg.aggregate, captureMap);
                      if (agg.argument != null) {
                        collectReferencedStackVarsRec(
                            layout, agg.argument, captureMap);
                      }
                    });
          } else if (step instanceof Core.Yield) {
            collectReferencedStackVarsRec(
                layout, ((Core.Yield) step).exp, captureMap);
          } else if (step instanceof Core.SetStep) {
            // Handles Core.Union, Core.Intersect, Core.Except
            ((Core.SetStep) step)
                .args.forEach(
                    arg ->
                        collectReferencedStackVarsRec(layout, arg, captureMap));
          }
          // Unorder has no sub-expressions.
        }
        break;

      default:
        // Literals and other leaf nodes: no ID references.
        break;
    }
  }

  private void compileValDecl(
      Context cx,
      Core.ValDecl valDecl,
      Core.@Nullable Pat skipPat,
      Set<Core.Exp> queriesToWrap,
      List<Code> matchCodes,
      List<Binding> bindings,
      @Nullable List<Action> actions) {
    Compiles.bindPattern(typeSystem, bindings, valDecl);
    final List<Binding> newBindings = new TailList<>(bindings);
    final Map<Core.NamedPat, LinkCode> linkCodes = new HashMap<>();
    if (valDecl.op == Op.REC_VAL_DECL) {
      valDecl.forEachBinding(
          (pat, exp, overloadPat, pos) ->
              addLinkCodes(pat, linkCodes, bindings));
    }

    final Context cx1 = cx.bindAll(newBindings);
    valDecl.forEachBinding(
        (pat, exp, overloadPat, pos) -> {
          // Using 'compileArg' rather than 'compile' encourages CalciteCompiler
          // to use a pure Calcite implementation if possible, and has no effect
          // in the basic Compiler.
          final Code code0 = compileArg(cx1, exp);
          final Code code =
              queriesToWrap.contains(exp) ? Codes.wrapRelList(code0) : code0;
          if (!linkCodes.isEmpty()) {
            link(linkCodes, pat, code);
          }
          matchCodes.add(new MatchCode(ImmutablePairList.of(pat, code), pos));

          if (actions != null) {
            actions.add(
                new ActionImpl(
                    typeSystem, code, pat, exp, overloadPat, pos, skipPat));
          }
        });

    newBindings.clear();
  }

  /**
   * Recursively creates {@link LinkCode}s for all named patterns in {@code
   * pat}, adding them to {@code linkCodes} and {@code bindings}.
   *
   * <p>Handles {@link Core.IdPat}, {@link Core.AsPat}, and {@link
   * Core.TuplePat} (so that mutual-recursion patterns like {@code it as (f, g)}
   * correctly expose {@code f} and {@code g} in the compile context).
   */
  private void addLinkCodes(
      Core.Pat pat,
      Map<Core.NamedPat, LinkCode> linkCodes,
      List<Binding> bindings) {
    if (pat instanceof Core.IdPat || pat instanceof Core.AsPat) {
      final Core.NamedPat namedPat = (Core.NamedPat) pat;
      final LinkCode linkCode = new LinkCode();
      linkCodes.put(namedPat, linkCode);
      bindings.add(Binding.of(namedPat, linkCode));
      if (pat instanceof Core.AsPat) {
        addLinkCodes(((Core.AsPat) pat).pat, linkCodes, bindings);
      }
    } else if (pat instanceof Core.TuplePat) {
      ((Core.TuplePat) pat)
          .args.forEach(p -> addLinkCodes(p, linkCodes, bindings));
    }
  }

  private void link(
      Map<Core.NamedPat, LinkCode> linkCodes, Core.Pat pat, Code code) {
    if (pat instanceof Core.IdPat) {
      final LinkCode linkCode = linkCodes.get(pat);
      if (linkCode != null) {
        linkCode.refCode = code; // link the reference to the definition
      }
    } else if (pat instanceof Core.AsPat) {
      final Core.AsPat asPat = (Core.AsPat) pat;
      final LinkCode linkCode = linkCodes.get(asPat);
      if (linkCode != null) {
        linkCode.refCode = code;
      }
      link(linkCodes, asPat.pat, code);
    } else if (pat instanceof Core.TuplePat) {
      if (code instanceof Codes.TupleCode) {
        // Recurse into the tuple, binding names to code in parallel
        final List<Code> codes = ((Codes.TupleCode) code).codes;
        final List<Core.Pat> pats = ((Core.TuplePat) pat).args;
        forEach(codes, pats, (code1, pat1) -> link(linkCodes, pat1, code1));
      }
    }
  }

  /**
   * A piece of code that is references another piece of code. It is useful when
   * defining recursive functions. The reference is mutable, and is fixed up
   * when the function has been compiled.
   */
  private static class LinkCode implements Code {
    private @Nullable Code refCode;

    @Override
    public Describer describe(Describer describer) {
      return describer.start(
          "link",
          d -> {
            if (false) {
              // Don't recurse into refCode... or we'll never get out alive.
              d.arg("refCode", refCode);
            }
          });
    }

    public Object eval(EvalEnv env) {
      assert refCode != null; // link should have completed by now
      return refCode.eval(env);
    }

    @Override
    public Object eval(Stack stack) {
      assert refCode != null;
      return refCode.eval(stack);
    }
  }

  /** Code that creates a {@link Closure} for a match expression. */
  private static class MatchCode implements Code {
    private final ImmutablePairList<Core.Pat, Code> patCodes;
    private final Pos pos;

    MatchCode(ImmutablePairList<Core.Pat, Code> patCodes, Pos pos) {
      this.patCodes = patCodes;
      this.pos = pos;
    }

    @Override
    public Describer describe(Describer describer) {
      return describer.start(
          "match",
          d ->
              patCodes.forEach(
                  (pat, code) ->
                      d.arg("", pat.describe(describer)).arg("", code)));
    }

    @Override
    public Object eval(EvalEnv evalEnv) {
      return new Closure(evalEnv, patCodes, pos);
    }
  }

  /**
   * Code that creates a {@link Closure.StackClosure} when evaluated.
   *
   * <p>At compile time, {@code captureOffsets[i]} is the offset (1-based from
   * stack top) at which the i-th captured variable lives at closure-creation
   * time.
   *
   * <p>At runtime, snapshots those stack slots into {@code capturedValues[]}
   * and returns a {@link Closure.StackClosure}.
   */
  private static class StackMatchCode implements Code {
    private final int[] captureOffsets;
    private final ImmutablePairList<Core.Pat, Code> patCodes;
    /** Minimum slots needed for a fresh {@link Closure.StackClosure} call. */
    private final int capacity;

    private final Pos pos;

    StackMatchCode(
        int[] captureOffsets,
        ImmutablePairList<Core.Pat, Code> patCodes,
        int capacity,
        Pos pos) {
      this.captureOffsets = captureOffsets;
      this.patCodes = patCodes;
      this.capacity = capacity;
      this.pos = pos;
    }

    @Override
    public Describer describe(Describer describer) {
      return describer.start(
          "match",
          d ->
              patCodes.forEach(
                  (pat, code) ->
                      d.arg("", pat.describe(describer)).arg("", code)));
    }

    @Override
    public Object eval(EvalEnv env) {
      // Called from old-mode context: no stack, create closure with no
      // captures.
      return new Closure.StackClosure(
          env, new Object[0], patCodes, capacity, pos);
    }

    @Override
    public Object eval(Stack stack) {
      final Object[] captured = new Object[captureOffsets.length];
      for (int i = 0; i < captureOffsets.length; i++) {
        captured[i] = stack.slots[stack.top - captureOffsets[i]];
      }
      return new Closure.StackClosure(
          stack.globalEnv, captured, patCodes, capacity, pos);
    }
  }

  /** Application of a function to a sequence or tuple of arguments. */
  private static class Gather {
    final Core.Literal fnLiteral;
    final List<Core.Exp> args;

    private Gather(Core.Literal fnLiteral, List<Core.Exp> args) {
      this.fnLiteral = requireNonNull(fnLiteral);
      this.args = ImmutableList.copyOf(args);
    }

    private Gather(Core.Literal fnLiteral, Core.Exp... args) {
      this(fnLiteral, ImmutableList.copyOf(args));
    }

    @Override
    public String toString() {
      return format("Gather{fnLiteral=%s, args=%s}", fnLiteral, args);
    }

    static @Nullable Gather of(Core.Apply apply) {
      switch (apply.fn.op) {
        case FN_LITERAL:
          if (apply.arg instanceof Core.Tuple) {
            Core.Tuple arg = (Core.Tuple) apply.arg;
            if (arity(apply.fn) == arg.args.size()) {
              return new Gather((Core.Literal) apply.fn, arg.args);
            }
          }
          return new Gather((Core.Literal) apply.fn, apply.arg);
        default:
          return of2(apply.fn, ImmutableList.of(apply.arg));
      }
    }

    static @Nullable Gather of2(Core.Exp fn, List<Core.Exp> args) {
      switch (fn.op) {
        case FN_LITERAL:
          return new Gather((Core.Literal) fn, args);
        case APPLY:
          final Core.Apply apply = (Core.Apply) fn;
          if (apply.arg.op == Op.TUPLE || apply.arg.op == Op.RECORD) {
            // A mixture of tuples and chained arguments,
            // such as "General.o (f, g) arg", is not a valid Gather.
            return null;
          }
          return of2(apply.fn, plus(apply.arg, args));
        default:
          return null;
      }
    }

    /**
     * Returns whether the argument at index {@code i} is a tuple of {@code
     * arity}.
     */
    public boolean argIsTuple(int i, int arity) {
      return arity(fnLiteral) == arity
          && i < args.size()
          && (args.get(i).type instanceof TupleType
              || args.get(i).type instanceof RecordType)
          && ((RecordLikeType) args.get(i).type).argTypes().size() == arity;
    }
  }

  private static class ActionImpl implements Action {
    private final TypeSystem typeSystem;
    private final Code code;
    private final Core.NamedPat pat;
    private final Core.Exp exp;
    private final Type type;
    private final Core.@Nullable IdPat overloadPat;
    private final Pos pos;
    private final Core.@Nullable Pat skipPat;

    ActionImpl(
        TypeSystem typeSystem,
        Code code,
        Core.NamedPat pat,
        Core.Exp exp,
        Core.@Nullable IdPat overloadPat,
        Pos pos,
        Core.@Nullable Pat skipPat) {
      this.typeSystem = typeSystem;
      this.code = code;
      this.pat = pat;
      this.exp = exp;
      final Type type0 = pat.type.containsProgressive() ? exp.type : pat.type;
      this.type = typeSystem.ensureClosed(type0);
      this.overloadPat = overloadPat;
      this.pos = pos;
      this.skipPat = skipPat;
    }

    @Override
    public void apply(
        Consumer<String> outLines,
        Consumer<Binding> outBindings,
        EvalEnv evalEnv) {
      final Session session = evalEnv.getSession();
      final StringBuilder buf = new StringBuilder();
      final List<String> outs = new ArrayList<>();
      try {
        final Object o =
            code.eval(new Stack(evalEnv, Math.max(code.maxSlots(), 256)));
        final List<Binding> outBindings0 = new ArrayList<>();
        // For simple IdPat bindings, store the expression so it can be inlined
        // in subsequent compile units. For compound patterns (tuples), we don't
        // track sub-expressions.
        final Core.@Nullable Exp expForBinding =
            pat instanceof Core.IdPat ? exp : null;
        if (!Closure.bindRecurse(
            pat.withType(type),
            o,
            (pat2, o2) ->
                outBindings0.add(
                    overloadPat == null
                        ? Binding.of(pat2, expForBinding, o2)
                        : Binding.inst(
                            pat2, overloadPat, expForBinding, o2)))) {
          throw new Codes.MorelRuntimeException(Codes.BuiltInExn.BIND, pos);
        }
        // Patch globalEnv in any StackClosure values so that they can look up
        // themselves (recursive calls) and sibling bindings in globalEnv.
        // Only patch closures whose globalEnv is still evalEnv (i.e., they
        // were created directly by this code and have not already been
        // patched by an inner LetCode). Closures returned from let
        // expressions have a properly patched globalEnv and must not be
        // overwritten here.
        EvalEnv env2 = evalEnv;
        for (Binding b : outBindings0) {
          env2 = env2.bind(b.id.name, b.value);
        }
        for (Binding b : outBindings0) {
          if (needsGlobalEnvPatch(b.value, evalEnv)) {
            Closure.patchStackClosureEnv(b.value, env2);
          }
        }
        for (Binding binding : outBindings0) {
          outBindings.accept(binding);
          if (binding.id == skipPat) {
            continue;
          }
          final Pretty pretty = getPretty(session.map, session.bagPrinter());
          final Core.NamedPat id =
              binding.overloadId != null ? binding.overloadId : binding.id;
          final Pretty.TypedVal typedVal = getTypedVal(binding, id);
          pretty.pretty(buf, binding.id.type, typedVal);
          final String line = str(buf);
          outs.add(line);
          outLines.accept(line);
        }
      } catch (Codes.MorelRuntimeException e) {
        session.handle(e, buf);
        final String line = str(buf);
        outs.add(line);
        outLines.accept(line);
      }
      session.code = code;
      session.out = ImmutableList.copyOf(outs);
    }

    /**
     * Returns true if {@code value} is a {@link Closure.StackClosure} whose
     * {@code globalEnv} is still {@code baseEnv} (i.e., it was just created and
     * has not yet been patched by an inner {@code LetCode}).
     *
     * <p>Used to avoid overwriting a closure's already-complete {@code
     * globalEnv} when binding the result of a {@code let} expression.
     */
    private static boolean needsGlobalEnvPatch(Object value, EvalEnv baseEnv) {
      if (value instanceof Closure.StackClosure) {
        return ((Closure.StackClosure) value).hasGlobalEnv(baseEnv);
      }
      if (value instanceof List && !(value instanceof Variant)) {
        for (Object element : (List<?>) value) {
          if (needsGlobalEnvPatch(element, baseEnv)) {
            return true;
          }
        }
      }
      return false;
    }

    private Pretty.TypedVal getTypedVal(Binding binding, Core.NamedPat id) {
      if (binding.value instanceof TypedValue) {
        TypedValue typedValue = (TypedValue) binding.value;
        return new Pretty.TypedVal(
            id.name,
            typedValue.valueAs(Object.class),
            Keys.toProgressive(binding.id.type().key()).toType(typeSystem));
      } else {
        return new Pretty.TypedVal(id.name, binding.value, binding.id.type);
      }
    }

    private Pretty getPretty(Map<Prop, Object> map, BagPrinter bagPrinter) {
      int stringDepth = Prop.STRING_DEPTH.intValue(map);
      int lineWidth = Prop.LINE_WIDTH.intValue(map);
      Prop.Output output = Prop.OUTPUT.enumValue(map, Prop.Output.class);
      int printDepth = Prop.PRINT_DEPTH.intValue(map);
      int printLength = Prop.PRINT_LENGTH.intValue(map);
      return new Pretty(
          typeSystem,
          lineWidth,
          output,
          printLength,
          printDepth,
          stringDepth,
          bagPrinter);
    }
  }
}

// End Compiler.java
