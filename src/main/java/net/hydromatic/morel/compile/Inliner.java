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

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static net.hydromatic.morel.ast.CoreBuilder.core;
import static net.hydromatic.morel.util.Pair.forEach;
import static net.hydromatic.morel.util.Static.allMatch;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.eval.Applicable;
import net.hydromatic.morel.eval.Applicable1;
import net.hydromatic.morel.eval.Code;
import net.hydromatic.morel.eval.Codes;
import net.hydromatic.morel.eval.Unit;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.DataType;
import net.hydromatic.morel.type.FnType;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Shuttle that inlines constant values. */
public class Inliner extends EnvShuttle {
  private final Analyzer.@Nullable Analysis analysis;

  /** Private constructor. */
  private Inliner(
      TypeSystem typeSystem, Environment env, Analyzer.Analysis analysis) {
    super(typeSystem, env);
    this.analysis = analysis;
  }

  /**
   * Creates an Inliner.
   *
   * <p>If {@code analysis} is null, no variables are inlined.
   */
  public static Inliner of(
      TypeSystem typeSystem,
      Environment env,
      Analyzer.@Nullable Analysis analysis) {
    return new Inliner(typeSystem, env, analysis);
  }

  @Override
  protected Inliner push(Environment env) {
    return new Inliner(typeSystem, env, analysis);
  }

  @Override
  protected Core.Exp visit(Core.Id id) {
    final Binding binding = env.getOpt(id.idPat);
    if (binding != null && !binding.parameter) {
      if (binding.exp != null) {
        final Analyzer.Use use =
            analysis == null
                ? Analyzer.Use.MULTI_UNSAFE
                : requireNonNull(analysis.map.get(id.idPat));
        switch (use) {
          case ATOMIC:
          case ONCE_SAFE:
            return binding.exp.accept(this);
        }
      }
      Object v = binding.value;
      if (v instanceof Macro) {
        final Macro macro = (Macro) binding.value;
        final Core.Exp x =
            macro.expand(typeSystem, env, ((FnType) id.type).paramType);
        if (x instanceof Core.Literal) {
          return x;
        }
      }
      if (v != Unit.INSTANCE) {
        // Trim "forall", so that "forall b. b list -> int" becomes
        // "a list -> int" and is clearly a function type.
        final Type type = typeSystem.unqualified(id.type);
        switch (type.op()) {
          case ID:
            assert id.type instanceof PrimitiveType;
            return core.literal((PrimitiveType) id.type, v);

          case FUNCTION_TYPE:
            assert v instanceof Applicable
                    || v instanceof Applicable1
                    || v instanceof Macro
                : v;
            final BuiltIn builtIn = Codes.BUILT_IN_MAP.get(v);
            if (builtIn != null) {
              return core.functionLiteral(id.type, builtIn);
            }
            // Applicable (including Closure) that does not map to a BuiltIn
            // is not considered 'constant', mainly because it creates messy
            // plans.
            break;

          default:
            if (v instanceof Code) {
              v = ((Code) v).eval(Compiler.EMPTY_ENV);
              if (v == null) {
                // Cannot inline SYS_FILE; it requires a session.
                break;
              }
            }
            return core.valueLiteral(id, v);
        }
      }
    }
    return super.visit(id);
  }

  @Override
  protected Core.Exp visit(Core.Apply apply) {
    final Core.Apply apply2 = (Core.Apply) super.visit(apply);
    if (apply2.fn.op == Op.RECORD_SELECTOR
        && apply2.arg.op == Op.VALUE_LITERAL) {
      final Core.RecordSelector selector = (Core.RecordSelector) apply2.fn;
      @SuppressWarnings("rawtypes")
      final List list = ((Core.Literal) apply2.arg).unwrap(List.class);
      final Object o = list.get(selector.slot);
      if (o instanceof Applicable || o instanceof Macro) {
        // E.g. apply is '#filter List', o is Codes.LIST_FILTER,
        // builtIn is BuiltIn.LIST_FILTER.
        final BuiltIn builtIn = Codes.BUILT_IN_MAP.get(o);
        if (builtIn != null) {
          return core.functionLiteral(apply2.type, builtIn);
        }
      }
      return core.valueLiteral(apply2, o);
    }
    if (apply2.fn.op == Op.FN) {
      // Beta-reduction:
      //   (fn x => E) A
      // becomes
      //   let x = A in E end
      final Core.Fn fn = (Core.Fn) apply2.fn;
      return core.let(
          core.nonRecValDecl(apply2.pos, fn.idPat, null, apply2.arg), fn.exp);
    }
    return apply2;
  }

  @Override
  protected Core.Exp visit(Core.Case caseOf) {
    final Core.Exp exp = caseOf.exp.accept(this);
    final List<Core.Match> matchList = visitList(caseOf.matchList);
    if (matchList.size() == 1) {
      // This is a singleton "case". Inline if possible. For example,
      //   fn x => case x of y => y + 1
      // becomes
      //   fn x => x + 1
      final Core.Match match = matchList.get(0);
      final Map<Core.Id, Core.Id> substitution = getSub(exp, match);
      if (substitution != null) {
        return Replacer.substitute(typeSystem, substitution, match.exp);
      }
    }
    // If exp is a literal (simple or nullary constructor), find the matching
    // branch and return it directly. For example,
    //   case 2 of 1 => "one" | 2 => "two" | _ => "large"
    // becomes
    //   "two"
    // Only do this when analysis is available (full inlining mode).
    if (analysis != null && exp instanceof Core.Literal) {
      final Core.Literal literal = (Core.Literal) exp;
      for (Core.Match match : matchList) {
        final Boolean matches = literalMatchesPattern(literal, match.pat);
        if (matches != null) {
          if (matches) {
            return match.exp;
          }
          // Pattern doesn't match; try next pattern
        } else if (match.pat instanceof Core.IdPat) {
          // Pattern like "x => x + 1" where x binds to the literal.
          // Convert to: let x = <literal> in <match.exp>
          final Core.IdPat idPat = (Core.IdPat) match.pat;
          return core.let(
              core.nonRecValDecl(caseOf.pos, idPat, null, exp), match.exp);
        }
        // Unknown pattern type; try next pattern
      }
    }
    // If exp is a constructor application like SOME "a", try to match against
    // ConPat patterns. Only apply this optimization if the result type is a
    // DataType and the fn is a constructor of that DataType.
    if (analysis != null && exp instanceof Core.Apply) {
      final Core.Apply apply = (Core.Apply) exp;
      if (apply.fn instanceof Core.Id && apply.type instanceof DataType) {
        final String conName = ((Core.Id) apply.fn).idPat.name;
        final DataType dataType = (DataType) apply.type;
        if (dataType.typeConstructors.containsKey(conName)) {
          for (Core.Match match : matchList) {
            final Boolean matches =
                constructorAppMatchesPattern(conName, apply.arg, match.pat);
            if (matches != null) {
              if (matches) {
                // If the pattern is a ConPat with an IdPat inside, create a
                // binding
                if (match.pat instanceof Core.ConPat) {
                  final Core.ConPat conPat = (Core.ConPat) match.pat;
                  final Core.NonRecValDecl binding =
                      extractBinding(apply.arg, conPat.pat, caseOf.pos);
                  if (binding != null) {
                    return core.let(binding, match.exp);
                  }
                }
                return match.exp;
              }
              // Pattern doesn't match; try next pattern
            } else if (match.pat instanceof Core.IdPat) {
              // Pattern like "x => ..." where x binds to the constructor app.
              final Core.IdPat idPat = (Core.IdPat) match.pat;
              return core.let(
                  core.nonRecValDecl(caseOf.pos, idPat, null, exp), match.exp);
            }
            // Unknown pattern type; try next pattern
          }
        }
      }
    }
    if (exp.type != caseOf.exp.type) {
      // Type has become less general. For example,
      //   case x of NONE => [] | SOME y => [y]
      // has type 'alpha list' but when we substitute 'SOME 1' for x, it becomes
      //   case SOME 1 of NONE => [] | SOME y => [y]
      // with type 'int list'
      @Nullable Map<Integer, Type> sub = caseOf.exp.type.unifyWith(exp.type);
      if (sub == null) {
        throw new AssertionError(
            format("cannot unify %s with %s", exp.type, caseOf.exp.type));
      }
      final Type type =
          caseOf.type.substitute(
              typeSystem, ImmutableList.copyOf(sub.values()));
      return core.caseOf(caseOf.pos, type, exp, matchList);
    }
    return caseOf.copy(exp, matchList);
  }

  private @Nullable Map<Core.Id, Core.Id> getSub(
      Core.Exp exp, Core.Match match) {
    if (exp.op == Op.ID && match.pat.op == Op.ID_PAT) {
      return ImmutableMap.of(core.id((Core.IdPat) match.pat), (Core.Id) exp);
    }
    if (exp.op == Op.TUPLE && match.pat.op == Op.TUPLE_PAT) {
      final Core.Tuple tuple = (Core.Tuple) exp;
      final Core.TuplePat tuplePat = (Core.TuplePat) match.pat;
      if (allMatch(tuple.args, arg -> arg.op == Op.ID)
          && allMatch(tuplePat.args, arg -> arg.op == Op.ID_PAT)) {
        final ImmutableMap.Builder<Core.Id, Core.Id> builder =
            ImmutableMap.builder();
        forEach(
            tuple.args,
            tuplePat.args,
            (arg, pat) ->
                builder.put(core.id((Core.IdPat) pat), (Core.Id) arg));
        return builder.build();
      }
    }
    return null;
  }

  /**
   * Returns whether {@code op} is a simple literal type (not VALUE_LITERAL).
   */
  private static boolean isSimpleLiteral(Op op) {
    switch (op) {
      case INT_LITERAL:
      case STRING_LITERAL:
      case BOOL_LITERAL:
      case CHAR_LITERAL:
      case REAL_LITERAL:
        return true;
      default:
        return false;
    }
  }

  /**
   * Returns whether the literal is a nullary constructor (enum value like LESS,
   * NONE, etc.). These are represented as VALUE_LITERAL with a single-element
   * list containing the constructor name.
   */
  private static boolean isNullaryConstructor(Core.Literal literal) {
    if (literal.op != Op.VALUE_LITERAL) {
      return false;
    }
    final List list = literal.unwrap(List.class);
    return list != null && list.size() == 1 && list.get(0) instanceof String;
  }

  /**
   * Returns whether the literal is a constructor application (like SOME "a").
   * These are represented as VALUE_LITERAL with a two-element list containing
   * the constructor name and the argument value.
   */
  private static boolean isConstructorApplication(Core.Literal literal) {
    if (literal.op != Op.VALUE_LITERAL) {
      return false;
    }
    final List list = literal.unwrap(List.class);
    return list != null && list.size() == 2 && list.get(0) instanceof String;
  }

  /** Returns the constructor name from a nullary constructor literal. */
  private static String getNullaryConstructorName(Core.Literal literal) {
    return (String) literal.unwrap(List.class).get(0);
  }

  /** Returns the constructor name from a constructor application literal. */
  private static String getConstructorName(Core.Literal literal) {
    return (String) literal.unwrap(List.class).get(0);
  }

  /** Returns the argument value from a constructor application literal. */
  private static Object getConstructorArg(Core.Literal literal) {
    return literal.unwrap(List.class).get(1);
  }

  /**
   * Returns whether a literal value matches a pattern.
   *
   * @return {@link Boolean#TRUE} if the pattern definitely matches, {@link
   *     Boolean#FALSE} if the pattern definitely does not match, or null if the
   *     match cannot be determined (e.g., pattern is IdPat or a complex pattern
   *     type)
   */
  private static @Nullable Boolean literalMatchesPattern(
      Core.Literal literal, Core.Pat pat) {
    if (pat instanceof Core.WildcardPat) {
      return true;
    }
    if (isSimpleLiteral(literal.op)) {
      if (pat instanceof Core.LiteralPat) {
        return literal.value.equals(((Core.LiteralPat) pat).value);
      }
    } else if (isNullaryConstructor(literal)) {
      if (pat instanceof Core.Con0Pat) {
        final String tyConName = getNullaryConstructorName(literal);
        return ((Core.Con0Pat) pat).tyCon.equals(tyConName);
      }
      if (pat instanceof Core.ConPat) {
        // Nullary constructor can't match a ConPat (which expects an argument)
        return false;
      }
    } else if (isConstructorApplication(literal)) {
      final String conName = getConstructorName(literal);
      final Object argValue = getConstructorArg(literal);
      if (pat instanceof Core.Con0Pat) {
        // Constructor application can't match nullary constructor pattern
        return false;
      }
      if (pat instanceof Core.ConPat) {
        final Core.ConPat conPat = (Core.ConPat) pat;
        if (!conPat.tyCon.equals(conName)) {
          return false; // Different constructors
        }
        // Same constructor; check if argument matches sub-pattern
        return valueMatchesPattern(argValue, conPat.pat);
      }
    }
    return null;
  }

  /**
   * Returns whether a runtime value matches a pattern.
   *
   * @return {@link Boolean#TRUE} if definitely matches, {@link Boolean#FALSE}
   *     if definitely does not match, or null if cannot be determined
   */
  private static @Nullable Boolean valueMatchesPattern(
      Object value, Core.Pat pat) {
    if (pat instanceof Core.WildcardPat) {
      return true;
    }
    if (pat instanceof Core.LiteralPat) {
      return value.equals(((Core.LiteralPat) pat).value);
    }
    // IdPat always matches (but we can't determine substitutions statically)
    // so return null to indicate this needs runtime handling
    return null;
  }

  /**
   * Returns whether a constructor application matches a pattern.
   *
   * @param conName the constructor name (e.g., "SOME")
   * @param arg the argument expression
   * @param pat the pattern to match against
   * @return {@link Boolean#TRUE} if definitely matches, {@link Boolean#FALSE}
   *     if definitely does not match, or null if cannot be determined
   */
  private static @Nullable Boolean constructorAppMatchesPattern(
      String conName, Core.Exp arg, Core.Pat pat) {
    if (pat instanceof Core.WildcardPat) {
      return true;
    }
    if (pat instanceof Core.Con0Pat) {
      // Constructor application can't match nullary constructor pattern
      return false;
    }
    if (pat instanceof Core.ConPat) {
      final Core.ConPat conPat = (Core.ConPat) pat;
      if (!conPat.tyCon.equals(conName)) {
        return false; // Different constructors
      }
      // Same constructor; check if argument matches sub-pattern
      return expMatchesPattern(arg, conPat.pat);
    }
    return null;
  }

  /**
   * Returns whether an expression matches a pattern.
   *
   * @return {@link Boolean#TRUE} if definitely matches, {@link Boolean#FALSE}
   *     if definitely does not match, or null if cannot be determined
   */
  private static @Nullable Boolean expMatchesPattern(
      Core.Exp exp, Core.Pat pat) {
    if (pat instanceof Core.WildcardPat) {
      return true;
    }
    if (pat instanceof Core.IdPat) {
      // IdPat always matches, but requires binding
      return true;
    }
    if (exp instanceof Core.Literal && pat instanceof Core.LiteralPat) {
      final Core.Literal literal = (Core.Literal) exp;
      if (isSimpleLiteral(literal.op)) {
        return literal.value.equals(((Core.LiteralPat) pat).value);
      }
    }
    // Other patterns - cannot determine statically
    return null;
  }

  /**
   * Extracts a binding from a pattern match if needed.
   *
   * @param arg the argument expression being matched
   * @param pat the sub-pattern inside the ConPat
   * @param pos position for the binding
   * @return a NonRecValDecl binding, or null if no binding needed
   */
  private static Core.@Nullable NonRecValDecl extractBinding(
      Core.Exp arg, Core.Pat pat, Pos pos) {
    if (pat instanceof Core.IdPat) {
      return core.nonRecValDecl(pos, (Core.IdPat) pat, null, arg);
    }
    return null;
  }

  @Override
  protected Core.Exp visit(Core.Let let) {
    final Analyzer.Use use =
        analysis == null
            ? Analyzer.Use.MULTI_UNSAFE
            : let.decl instanceof Core.NonRecValDecl
                ? requireNonNull(
                    analysis.map.get(((Core.NonRecValDecl) let.decl).pat))
                : Analyzer.Use.MULTI_UNSAFE;
    switch (use) {
      case DEAD:
        // This declaration has no uses; remove it
        return let.exp.accept(this);

      case ATOMIC:
      case ONCE_SAFE:
        // This declaration has one use; remove the declaration, and replace its
        // use inside the expression.
        final List<Binding> bindings = new ArrayList<>();
        Compiles.bindPattern(typeSystem, bindings, let.decl);
        return let.exp.accept(bind(bindings));
    }
    return super.visit(let);
  }
}

// End Inliner.java
