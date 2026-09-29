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
package net.hydromatic.morel.eval.codes;

import static com.google.common.base.Preconditions.checkArgument;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static net.hydromatic.morel.ast.CoreBuilder.core;
import static net.hydromatic.morel.eval.Codes.OPTION_NONE;
import static net.hydromatic.morel.eval.Codes.optionSome;
import static net.hydromatic.morel.eval.codes.StringCodes.scanString;

import com.google.common.collect.ImmutableList;
import java.util.Comparator;
import java.util.List;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.compile.CompileException;
import net.hydromatic.morel.compile.Macro;
import net.hydromatic.morel.eval.Applicable;
import net.hydromatic.morel.eval.Applicable1;
import net.hydromatic.morel.eval.Applicable2;
import net.hydromatic.morel.eval.Applicable3;
import net.hydromatic.morel.eval.BuiltInExn;
import net.hydromatic.morel.eval.Codes.Typed;
import net.hydromatic.morel.eval.Comparators;
import net.hydromatic.morel.eval.MorelRuntimeException;
import net.hydromatic.morel.eval.Session;
import net.hydromatic.morel.eval.Stack;
import net.hydromatic.morel.eval.Unit;
import net.hydromatic.morel.eval.Variant;
import net.hydromatic.morel.eval.Variants;
import net.hydromatic.morel.eval.codes.StringCodes.CharSource;
import net.hydromatic.morel.type.DataType;
import net.hydromatic.morel.type.FnType;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.TupleType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.type.TypeVar;
import net.hydromatic.morel.util.PairList;
import org.apache.calcite.runtime.FlatLists;

/**
 * Implementations of built-in functions and values in the General, Op, Order,
 * Bool and Fn structures.
 */
public final class GeneralCodes {
  private GeneralCodes() {}

  /** Registers the implementations in this class. */
  public static void register(PairList<BuiltIn, Object> b) {
    // lint: sort until '#}' where '##b\.add\(BuiltIn' erase 'b\.'
    b.add(BuiltIn.ABS, ABS);
    b.add(BuiltIn.BOOL_ANDALSO, BOOL_ANDALSO);
    b.add(BuiltIn.BOOL_FROM_STRING, BOOL_FROM_STRING);
    b.add(BuiltIn.BOOL_IMPLIES, BOOL_IMPLIES);
    b.add(BuiltIn.BOOL_NOT, BOOL_NOT);
    b.add(BuiltIn.BOOL_OP_EQ, BOOL_OP_EQ);
    b.add(BuiltIn.BOOL_OP_GT, BOOL_OP_GT);
    b.add(BuiltIn.BOOL_OP_LT, BOOL_OP_LT);
    b.add(BuiltIn.BOOL_OP_NE, BOOL_OP_NE);
    b.add(BuiltIn.BOOL_ORELSE, BOOL_ORELSE);
    b.add(BuiltIn.BOOL_SCAN, BOOL_SCAN);
    b.add(BuiltIn.BOOL_TO_STRING, BOOL_TO_STRING);
    b.add(BuiltIn.FN_APPLY, FN_APPLY);
    b.add(BuiltIn.FN_CONST, FN_CONST);
    b.add(BuiltIn.FN_CURRY, FN_CURRY);
    b.add(BuiltIn.FN_EQUAL, FN_EQUAL);
    b.add(BuiltIn.FN_FLIP, FN_FLIP);
    b.add(BuiltIn.FN_ID, FN_ID);
    b.add(BuiltIn.FN_NOT_EQUAL, FN_NOT_EQUAL);
    b.add(BuiltIn.FN_O, FN_OP_O);
    b.add(BuiltIn.FN_REPEAT, FN_REPEAT);
    b.add(BuiltIn.FN_UNCURRY, FN_UNCURRY);
    b.add(BuiltIn.GENERAL_BEFORE, GENERAL_BEFORE);
    b.add(BuiltIn.GENERAL_EXN_MESSAGE, GENERAL_EXN_MESSAGE);
    b.add(BuiltIn.GENERAL_EXN_NAME, GENERAL_EXN_NAME);
    b.add(BuiltIn.GENERAL_IGNORE, GENERAL_IGNORE);
    b.add(BuiltIn.GENERAL_O, GENERAL_OP_O);
    b.add(BuiltIn.OP_CONS, OP_CONS);
    b.add(BuiltIn.OP_DIV, OP_DIV);
    b.add(BuiltIn.OP_DIVIDE, OP_DIVIDE);
    b.add(BuiltIn.OP_ELEM, OP_ELEM);
    b.add(BuiltIn.OP_EQ, OP_EQ);
    b.add(BuiltIn.OP_GE, OP_GE);
    b.add(BuiltIn.OP_GT, OP_GT);
    b.add(BuiltIn.OP_LE, OP_LE);
    b.add(BuiltIn.OP_LT, OP_LT);
    b.add(BuiltIn.OP_MINUS, OP_MINUS);
    b.add(BuiltIn.OP_MOD, OP_MOD);
    b.add(BuiltIn.OP_NE, OP_NE);
    b.add(BuiltIn.OP_NEGATE, OP_NEGATE);
    b.add(BuiltIn.OP_NOT_ELEM, OP_NOT_ELEM);
    b.add(BuiltIn.OP_PLUS, OP_PLUS);
    b.add(BuiltIn.OP_TIMES, OP_TIMES);
  }

  // lint: sort until '#}' \
  //   where '##private static final [^ ]+ [^ ]+ =' \
  //   erase 'private static final [^ ]+ '

  /**
   * Returns an applicable that returns the {@code slot}th field of a tuple or
   * record.
   */
  public static Applicable nth(int slot) {
    checkArgument(slot >= 0);
    return new BaseApplicable1<Object, List>(BuiltIn.Z_NTH) {
      @Override
      protected String name() {
        return "nth:" + slot;
      }

      @Override
      public Object apply(List list) {
        return list.get(slot);
      }
    };
  }

  /**
   * Returns an exception to throw when an overloaded operator has no instance
   * for the type of the argument it is applied to.
   */
  static CompileException notDefined(BuiltIn builtIn, Type argType, Pos pos) {
    return new CompileException(
        format(
            "operator '%s' not defined for type '%s'", builtIn.mlName, argType),
        false,
        pos);
  }

  /** @see BuiltIn#ABS */
  private static final Macro ABS =
      (typeSystem, env, argType, pos) -> {
        if (isDecimal(argType)) {
          return core.functionLiteral(typeSystem, BuiltIn.DECIMAL_ABS);
        }
        switch ((PrimitiveType) argType) {
          case INT:
            return core.functionLiteral(typeSystem, BuiltIn.INT_ABS);
          case REAL:
            return core.functionLiteral(typeSystem, BuiltIn.REAL_ABS);
          default:
            throw notDefined(BuiltIn.ABS, argType, pos);
        }
      };

  /** @see BuiltIn#BOOL_ANDALSO */
  private static final Applicable2 BOOL_ANDALSO =
      new BaseApplicable2<Boolean, Boolean, Boolean>(BuiltIn.BOOL_ANDALSO) {
        @Override
        public Boolean apply(Boolean a0, Boolean a1) {
          return a0 && a1;
        }
      };

  /** @see BuiltIn#BOOL_FROM_STRING */
  private static final Applicable BOOL_FROM_STRING =
      new BaseApplicable1<List, String>(BuiltIn.BOOL_FROM_STRING) {
        @Override
        public List apply(String s) {
          return scanString(BOOL_SCAN, s);
        }
      };

  /** @see BuiltIn#BOOL_IMPLIES */
  private static final Applicable2 BOOL_IMPLIES =
      new BaseApplicable2<Boolean, Boolean, Boolean>(BuiltIn.BOOL_IMPLIES) {
        @Override
        public Boolean apply(Boolean a0, Boolean a1) {
          return !a0 || a1;
        }
      };

  /** @see BuiltIn#BOOL_NOT */
  private static final Applicable BOOL_NOT =
      new BaseApplicable1<Boolean, Boolean>(BuiltIn.BOOL_NOT) {
        @Override
        public Boolean apply(Boolean b) {
          return !b;
        }
      };

  /** @see BuiltIn#BOOL_OP_EQ */
  private static final Applicable2 BOOL_OP_EQ =
      new BaseApplicable2<Boolean, Boolean, Boolean>(BuiltIn.BOOL_OP_EQ) {
        @Override
        public Boolean apply(Boolean a0, Boolean a1) {
          return a0.equals(a1);
        }
      };

  /** @see BuiltIn#BOOL_OP_GT */
  private static final Applicable2 BOOL_OP_GT =
      new BaseApplicable2<Boolean, Boolean, Boolean>(BuiltIn.BOOL_OP_GT) {
        @Override
        public Boolean apply(Boolean a0, Boolean a1) {
          return a0 && !a1;
        }
      };

  /** @see BuiltIn#BOOL_OP_LT */
  private static final Applicable2 BOOL_OP_LT =
      new BaseApplicable2<Boolean, Boolean, Boolean>(BuiltIn.BOOL_OP_LT) {
        @Override
        public Boolean apply(Boolean a0, Boolean a1) {
          return !a0 && a1;
        }
      };

  /** @see BuiltIn#BOOL_OP_NE */
  private static final Applicable2 BOOL_OP_NE =
      new BaseApplicable2<Boolean, Boolean, Boolean>(BuiltIn.BOOL_OP_NE) {
        @Override
        public Boolean apply(Boolean a0, Boolean a1) {
          return !a0.equals(a1);
        }
      };

  /** @see BuiltIn#BOOL_ORELSE */
  private static final Applicable2 BOOL_ORELSE =
      new BaseApplicable2<Boolean, Boolean, Boolean>(BuiltIn.BOOL_ORELSE) {
        @Override
        public Boolean apply(Boolean a0, Boolean a1) {
          return a0 || a1;
        }
      };

  /**
   * Consumes {@code word} from {@code source} if it is next, and returns
   * whether it was. If it was not, {@code source} is left where it was.
   */
  static boolean consume(
      CharSource[] source, Applicable1<List, Object> reader, String word) {
    return consume(source, reader, word, false);
  }

  /** As {@link #consume}, optionally ignoring case. */
  static boolean consume(
      CharSource[] source,
      Applicable1<List, Object> reader,
      String word,
      boolean ignoreCase) {
    final Object mark = source[0].stream();
    for (int i = 0; i < word.length(); i++) {
      final int c = source[0].peek();
      final boolean match =
          ignoreCase
              ? c >= 0 && Character.toLowerCase(c) == word.charAt(i)
              : c == word.charAt(i);
      if (!match) {
        source[0] = new CharSource(reader, mark);
        return false;
      }
      source[0].advance();
    }
    return true;
  }

  /** @see BuiltIn#BOOL_SCAN */
  private static final BaseApplicable2<List, Applicable1<List, Object>, Object>
      BOOL_SCAN =
          new BaseApplicable2<List, Applicable1<List, Object>, Object>(
              BuiltIn.BOOL_SCAN) {
            @Override
            public List apply(Applicable1<List, Object> reader, Object stream) {
              final CharSource[] source = {new CharSource(reader, stream)};
              source[0].skipWhitespace();
              if (consume(source, reader, "true")) {
                return optionSome(ImmutableList.of(true, source[0].stream()));
              }
              if (consume(source, reader, "false")) {
                return optionSome(ImmutableList.of(false, source[0].stream()));
              }
              return OPTION_NONE;
            }
          };

  /** @see BuiltIn#BOOL_TO_STRING */
  private static final Applicable BOOL_TO_STRING =
      new BaseApplicable1<String, Boolean>(BuiltIn.BOOL_TO_STRING) {
        @Override
        public String apply(Boolean b) {
          return b.toString();
        }
      };

  /** Returns whether a type is {@code decimal}. */
  private static boolean isDecimal(Type type) {
    return type instanceof DataType && ((DataType) type).name.equals("decimal");
  }

  /** @see BuiltIn#FN_APPLY */
  private static final Applicable2 FN_APPLY =
      new BaseApplicable2<Object, Applicable1, Object>(BuiltIn.FN_APPLY) {
        @Override
        public Object apply(Applicable1 f, Object arg) {
          return f.apply(arg);
        }
      };

  /** @see BuiltIn#FN_CONST */
  private static final Applicable2 FN_CONST =
      new BaseApplicable2<Object, Object, Object>(BuiltIn.FN_CONST) {
        @Override
        public Object apply(Object a0, Object a1) {
          return a0;
        }
      };

  /** @see BuiltIn#FN_CURRY */
  private static final Applicable3 FN_CURRY =
      new BaseApplicable3<Object, Applicable, Object, Object>(
          BuiltIn.FN_CURRY) {
        @Override
        public Object apply(Applicable applicable, Object o, Object o2) {
          return ((Applicable1) applicable).apply(FlatLists.of(o, o2));
        }
      };

  /** @see BuiltIn#FN_EQUAL */
  private static final Applicable2 FN_EQUAL =
      new BaseApplicable2<Boolean, Object, Object>(BuiltIn.FN_EQUAL) {
        @Override
        public Boolean apply(Object a0, Object a1) {
          return a0.equals(a1);
        }
      };

  /** @see BuiltIn#FN_FLIP */
  private static final Applicable2 FN_FLIP =
      new BaseApplicable2<Object, Applicable1, List>(BuiltIn.FN_FLIP) {
        @Override
        public Object apply(Applicable1 f, List args) {
          return f.apply(FlatLists.of(args.get(1), args.get(0)));
        }
      };

  /** @see BuiltIn#FN_ID */
  private static final Applicable1 FN_ID =
      new BaseApplicable1<Object, Object>(BuiltIn.FN_ID) {
        @Override
        public Object apply(Object arg) {
          return arg;
        }
      };

  /** @see BuiltIn#FN_NOT_EQUAL */
  private static final Applicable2 FN_NOT_EQUAL =
      new BaseApplicable2<Boolean, Object, Object>(BuiltIn.FN_NOT_EQUAL) {
        @Override
        public Boolean apply(Object a0, Object a1) {
          return !a0.equals(a1);
        }
      };

  /** @see BuiltIn#FN_O */
  private static final Applicable2 FN_OP_O = new OpO(BuiltIn.FN_O);

  /** @see BuiltIn#FN_REPEAT */
  private static final Applicable2 FN_REPEAT = new FnRepeat(Pos.ZERO);

  /** Implements {@link #FN_REPEAT}. */
  private static class FnRepeat
      extends BasePositionedApplicable2<Applicable1, Integer, Applicable1> {
    FnRepeat(Pos pos) {
      super(BuiltIn.FN_REPEAT, pos);
    }

    @Override
    public FnRepeat withPos(Pos pos) {
      return new FnRepeat(pos);
    }

    @Override
    public Applicable1 apply(Integer n, Applicable1 f) {
      if (n < 0) {
        throw new MorelRuntimeException(BuiltInExn.DOMAIN, pos);
      }
      return new BaseApplicable1<Object, Object>(builtIn) {
        @Override
        public Object apply(Object o) {
          for (int i = 0; i < n; i++) {
            o = f.apply(o);
          }
          return o;
        }
      };
    }

    /**
     * Overrides the default curry to validate {@code n} as soon as it arrives,
     * rather than waiting for the second argument. Matches the SML/NJ behavior
     * of {@code Fn.repeat ~5} raising {@code Domain} immediately.
     */
    @Override
    public Applicable1<Applicable1<Applicable1, Applicable1>, Integer> curry() {
      return new CurriedApplicable1<
          Applicable1<Applicable1, Applicable1>, Integer>(builtIn, this) {
        @Override
        public Applicable1<Applicable1, Applicable1> apply(Integer n) {
          if (n < 0) {
            throw new MorelRuntimeException(BuiltInExn.DOMAIN, pos);
          }
          return f -> FnRepeat.this.apply(n, f);
        }
      };
    }
  }

  /** @see BuiltIn#FN_UNCURRY */
  private static final Applicable FN_UNCURRY =
      new BaseApplicable2<Object, Applicable1<Applicable1, Object>, List>(
          BuiltIn.FN_UNCURRY) {
        @Override
        public Object apply(Applicable1<Applicable1, Object> f, List list) {
          Applicable1 g = f.apply(list.get(0));
          return g.apply(list.get(1));
        }
      };

  /** @see BuiltIn#GENERAL_BEFORE */
  private static final Applicable GENERAL_BEFORE =
      new BaseApplicable1<Object, List>(BuiltIn.GENERAL_BEFORE) {
        @Override
        public Object apply(List arg) {
          // Returns first element of tuple (a, unit), ignoring the second
          return arg.get(0);
        }
      };

  /** @see BuiltIn#GENERAL_EXN_MESSAGE */
  private static final Applicable GENERAL_EXN_MESSAGE =
      new BaseApplicable1<String, List>(BuiltIn.GENERAL_EXN_MESSAGE) {
        @Override
        public String apply(List arg) {
          // An exception value is a tagged list whose first element is the
          // constructor name; built-in exceptions may have a description.
          final String name = (String) arg.get(0);
          final BuiltInExn exn = BuiltInExn.forMlName(name);
          if (exn != null && exn.description != null) {
            return exn.description;
          }
          if (arg.size() > 1) {
            return name + ": " + arg.get(1);
          }
          return name;
        }
      };

  /** @see BuiltIn#GENERAL_EXN_NAME */
  private static final Applicable GENERAL_EXN_NAME =
      new BaseApplicable1<String, List>(BuiltIn.GENERAL_EXN_NAME) {
        @Override
        public String apply(List arg) {
          return (String) arg.get(0);
        }
      };

  /** @see BuiltIn#GENERAL_IGNORE */
  private static final Applicable GENERAL_IGNORE =
      new BaseApplicable1<Unit, Object>(BuiltIn.GENERAL_IGNORE) {
        @Override
        public Unit apply(Object arg) {
          return Unit.INSTANCE;
        }
      };

  /** @see BuiltIn#GENERAL_O */
  private static final Applicable2 GENERAL_OP_O = new OpO(BuiltIn.GENERAL_O);

  /** Implements {@link #GENERAL_OP_O}, {@link #FN_OP_O}. */
  private static class OpO
      extends BaseApplicable2<Applicable1, Applicable1, Applicable1> {
    OpO(BuiltIn builtIn) {
      super(builtIn);
    }

    @Override
    public Applicable1 apply(Applicable1 f, Applicable1 g) {
      return arg -> f.apply(g.apply(arg));
    }
  }

  /** @see BuiltIn#OP_CONS */
  private static final Applicable2 OP_CONS =
      new BaseApplicable2<List, Object, Iterable>(BuiltIn.OP_CONS) {
        @Override
        public List apply(Object e, Iterable iterable) {
          return ImmutableList.builder().add(e).addAll(iterable).build();
        }
      };

  /** @see BuiltIn#OP_DIV */
  private static final Macro OP_DIV =
      (typeSystem, env, argType, pos) -> {
        final Type resultType = ((TupleType) argType).argTypes.get(0);
        switch ((PrimitiveType) resultType) {
          case INT:
            return core.functionLiteral(typeSystem, BuiltIn.INT_DIV);
          case WORD:
            return core.functionLiteral(typeSystem, BuiltIn.WORD_DIV);
          default:
            throw notDefined(BuiltIn.OP_DIV, argType, pos);
        }
      };

  /** @see BuiltIn#OP_DIVIDE */
  private static final Macro OP_DIVIDE =
      (typeSystem, env, argType, pos) -> {
        final Type resultType = ((TupleType) argType).argTypes.get(0);
        if (isDecimal(resultType)) {
          return core.functionLiteral(typeSystem, BuiltIn.DECIMAL_DIVIDE);
        }
        if (resultType == PrimitiveType.REAL) {
          return core.functionLiteral(typeSystem, BuiltIn.REAL_DIVIDE);
        }
        throw notDefined(BuiltIn.OP_DIVIDE, argType, pos);
      };

  /** @see BuiltIn#OP_ELEM */
  private static final Applicable2 OP_ELEM =
      new BaseApplicable2<Boolean, Object, List>(BuiltIn.OP_ELEM) {
        @Override
        public Boolean apply(Object a0, List a1) {
          return a1.contains(a0);
        }
      };

  /** @see BuiltIn#OP_EQ */
  private static final Applicable2 OP_EQ =
      new BaseApplicable2<Boolean, Object, Object>(BuiltIn.OP_EQ) {
        @Override
        public Boolean apply(Object a0, Object a1) {
          return a0.equals(a1);
        }
      };

  /** @see BuiltIn#OP_GE */
  private static final Applicable OP_GE =
      new OpCompare(BuiltIn.OP_GE, Comparators::comparePartial);

  /** @see BuiltIn#OP_GT */
  private static final Applicable OP_GT =
      new OpCompare(BuiltIn.OP_GT, Comparators::comparePartial);

  /** @see BuiltIn#OP_LE */
  private static final Applicable OP_LE =
      new OpCompare(BuiltIn.OP_LE, Comparators::comparePartial);

  /** @see BuiltIn#OP_LT */
  private static final Applicable OP_LT =
      new OpCompare(BuiltIn.OP_LT, Comparators::comparePartial);

  /**
   * Implements {@link #OP_GE}, {@link #OP_GT}, {@link #OP_LE} and {@link
   * #OP_LT}.
   *
   * <p>If the type of the operands is known at compile time, {@link #withType}
   * creates a copy whose comparator, created by {@link
   * Comparators#partialComparatorFor}, is specialized to that type. Values are
   * compared in the same order used by {@code order}, {@code min} and {@code
   * max}, except that {@code real} values, including those inside composite
   * values such as tuples and options, are compared according to IEEE 754.
   *
   * <p>If the type is not known at compile time (for example, in a polymorphic
   * function), the comparator is {@link Comparators#comparePartial}.
   */
  private static class OpCompare
      extends BaseApplicable2<Boolean, Object, Object> implements Typed {
    private final Comparator comparator;

    OpCompare(BuiltIn builtIn, Comparator comparator) {
      super(builtIn);
      this.comparator = requireNonNull(comparator);
    }

    @Override
    public Applicable withType(TypeSystem typeSystem, Type type, Pos pos) {
      // 'type' is 'argType * argType -> bool' (perhaps wrapped in a
      // ForallType if the operator is used as a value).
      final Type paramType = FnType.of(type).paramType;
      if (!(paramType instanceof TupleType)) {
        return this;
      }
      final Type argType = ((TupleType) paramType).argTypes.get(0);
      if (argType instanceof TypeVar) {
        return this;
      }
      return new OpCompare(
          builtIn, Comparators.partialComparatorFor(typeSystem, argType, pos));
    }

    @SuppressWarnings("unchecked")
    @Override
    public Boolean apply(Object a0, Object a1) {
      final int c = comparator.compare(a0, a1);
      if (c == Comparators.UNORDERED) {
        return false;
      }
      switch (builtIn) {
        case OP_GE:
          return c >= 0;
        case OP_GT:
          return c > 0;
        case OP_LE:
          return c <= 0;
        case OP_LT:
          return c < 0;
        default:
          throw new AssertionError(builtIn);
      }
    }
  }

  /** @see BuiltIn#OP_MINUS */
  private static final Macro OP_MINUS =
      (typeSystem, env, argType, pos) -> {
        final Type resultType = ((TupleType) argType).argTypes.get(0);
        if (isDecimal(resultType)) {
          return core.functionLiteral(typeSystem, BuiltIn.DECIMAL_OP_MINUS);
        }
        switch ((PrimitiveType) resultType) {
          case INT:
            return core.functionLiteral(typeSystem, BuiltIn.INT_OP_MINUS);
          case REAL:
            return core.functionLiteral(typeSystem, BuiltIn.REAL_OP_MINUS);
          case WORD:
            return core.functionLiteral(typeSystem, BuiltIn.WORD_OP_MINUS);
          default:
            throw notDefined(BuiltIn.OP_MINUS, argType, pos);
        }
      };

  /** @see BuiltIn#OP_MOD */
  private static final Macro OP_MOD =
      (typeSystem, env, argType, pos) -> {
        final Type resultType = ((TupleType) argType).argTypes.get(0);
        switch ((PrimitiveType) resultType) {
          case INT:
            return core.functionLiteral(typeSystem, BuiltIn.INT_MOD);
          case WORD:
            return core.functionLiteral(typeSystem, BuiltIn.WORD_MOD);
          default:
            throw notDefined(BuiltIn.OP_MOD, argType, pos);
        }
      };

  /** @see BuiltIn#OP_NE */
  private static final Applicable2 OP_NE =
      new BaseApplicable2<Boolean, Object, Object>(BuiltIn.OP_NE) {
        @Override
        public Boolean apply(Object a0, Object a1) {
          return !a0.equals(a1);
        }
      };

  /** @see BuiltIn#OP_NEGATE */
  private static final Macro OP_NEGATE =
      (typeSystem, env, argType, pos) -> {
        if (isDecimal(argType)) {
          return core.functionLiteral(typeSystem, BuiltIn.DECIMAL_OP_NEGATE);
        }
        switch ((PrimitiveType) argType) {
          case INT:
            return core.functionLiteral(typeSystem, BuiltIn.INT_OP_NEGATE);
          case REAL:
            return core.functionLiteral(typeSystem, BuiltIn.REAL_OP_NEGATE);
          case WORD:
            return core.functionLiteral(typeSystem, BuiltIn.WORD_OP_NEGATE);
          default:
            throw notDefined(BuiltIn.OP_NEGATE, argType, pos);
        }
      };

  /** @see BuiltIn#OP_NOT_ELEM */
  private static final Applicable2 OP_NOT_ELEM =
      new BaseApplicable2<Boolean, Object, List>(BuiltIn.OP_NOT_ELEM) {
        @Override
        public Boolean apply(Object a0, List a1) {
          return !a1.contains(a0);
        }
      };

  /** @see BuiltIn#OP_PLUS */
  private static final Macro OP_PLUS =
      (typeSystem, env, argType, pos) -> {
        final Type resultType = ((TupleType) argType).argTypes.get(0);
        if (isDecimal(resultType)) {
          return core.functionLiteral(typeSystem, BuiltIn.DECIMAL_OP_PLUS);
        }
        switch ((PrimitiveType) resultType) {
          case INT:
            return core.functionLiteral(typeSystem, BuiltIn.INT_OP_PLUS);
          case REAL:
            return core.functionLiteral(typeSystem, BuiltIn.REAL_OP_PLUS);
          case WORD:
            return core.functionLiteral(typeSystem, BuiltIn.WORD_OP_PLUS);
          default:
            throw notDefined(BuiltIn.OP_PLUS, argType, pos);
        }
      };

  /** @see BuiltIn#OP_TIMES */
  private static final Macro OP_TIMES =
      (typeSystem, env, argType, pos) -> {
        final Type resultType = ((TupleType) argType).argTypes.get(0);
        if (isDecimal(resultType)) {
          return core.functionLiteral(typeSystem, BuiltIn.DECIMAL_OP_TIMES);
        }
        switch ((PrimitiveType) resultType) {
          case INT:
            return core.functionLiteral(typeSystem, BuiltIn.INT_OP_TIMES);
          case REAL:
            return core.functionLiteral(typeSystem, BuiltIn.REAL_OP_TIMES);
          case WORD:
            return core.functionLiteral(typeSystem, BuiltIn.WORD_OP_TIMES);
          default:
            throw notDefined(BuiltIn.OP_TIMES, argType, pos);
        }
      };

  /** @see BuiltIn.Constructor#ORDER_EQUAL */
  static final List ORDER_EQUAL =
      ImmutableList.of(BuiltIn.Constructor.ORDER_EQUAL.constructor);

  /** @see BuiltIn.Constructor#ORDER_GREATER */
  static final List ORDER_GREATER =
      ImmutableList.of(BuiltIn.Constructor.ORDER_GREATER.constructor);

  /** @see BuiltIn.Constructor#ORDER_LESS */
  static final List ORDER_LESS =
      ImmutableList.of(BuiltIn.Constructor.ORDER_LESS.constructor);

  /**
   * Returns an applicable that constructs an instance of a datatype.
   *
   * <p>For the {@code variant} datatype, creates a {@link Variant}. For other
   * datatypes, creates a {@link List} with two elements [constructorName,
   * value].
   */
  public static Applicable tyCon(Type dataType, String name) {
    requireNonNull(dataType);
    requireNonNull(name);
    // Special handling for "variant" datatype: create Variant instances.
    if (dataType instanceof DataType
        && ((DataType) dataType).name.equals("variant")) {
      return new ValueTyCon(name);
    }
    // Standard datatype constructor - return List
    return new BaseApplicable1(BuiltIn.Z_TY_CON) {
      @Override
      protected String name() {
        return "tyCon";
      }

      @Override
      public Object apply(Object arg) {
        return ImmutableList.of(name, arg);
      }
    };
  }

  /**
   * Type constructor for {@code variant} datatype that creates {@link Variant}
   * instances.
   *
   * <p>Only implements {@link Applicable} (not {@link Applicable1}), so that
   * {@code apply(Stack, Object)} is called and provides access to {@link
   * TypeSystem} via {@link Session}.
   */
  static class ValueTyCon extends ApplicableImpl {
    private final String tyConName;

    ValueTyCon(String tyConName) {
      super(BuiltIn.Z_TY_CON);
      this.tyConName = tyConName;
    }

    @Override
    protected String name() {
      return "tyCon";
    }

    @Override
    public Object apply(Stack stack, Object arg) {
      final TypeSystem typeSystem = stack.session.typeSystem;
      if (typeSystem == null) {
        throw new IllegalStateException(
            format("Variant constructor %s requires TypeSystem", tyConName));
      }
      // Create Value instance based on constructor name
      return Variants.fromConstructor(tyConName, arg, typeSystem);
    }
  }
}

// End GeneralCodes.java
