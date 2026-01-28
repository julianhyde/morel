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
import static net.hydromatic.morel.ast.CoreBuilder.core;
import static net.hydromatic.morel.util.Pair.forEach;
import static net.hydromatic.morel.util.Static.allMatch;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.ast.Visitor;
import net.hydromatic.morel.eval.Applicable;
import net.hydromatic.morel.eval.Applicable1;
import net.hydromatic.morel.eval.Closure;
import net.hydromatic.morel.eval.Code;
import net.hydromatic.morel.eval.Codes;
import net.hydromatic.morel.eval.EvalEnvs;
import net.hydromatic.morel.eval.Unit;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.DataType;
import net.hydromatic.morel.type.FnType;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.TupleType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.type.TypeVar;
import net.hydromatic.morel.type.TypeVisitor;
import net.hydromatic.morel.util.PairList;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Shuttle that inlines constant values. */
public class Inliner extends EnvShuttle {
  private final Analyzer.@Nullable Analysis analysis;
  /**
   * Names of patterns currently being inlined. Used to detect recursive
   * references and avoid infinite recursion during inlining. We use full names
   * (not base names) to correctly handle shadowed variables - if "f_0" and
   * "f_1" are different functions, they should be tracked separately.
   */
  private final Set<String> inliningNames;
  /**
   * Current inlining depth. Used to prevent very deep inlining chains that
   * could indicate cyclic inlining between different variables.
   */
  private final int depth;
  /** Maximum inlining depth to prevent infinite cycles. */
  private static final int MAX_DEPTH = 250;

  /**
   * Set of impure built-in function names that should not be inlined. These
   * functions have side effects or read mutable state.
   */
  private static final Set<String> IMPURE_FUNCTION_NAMES =
      Set.of(
          "env",
          "clearEnv",
          "set",
          "unset",
          "show",
          "showAll",
          "file",
          "plan",
          "planEx");

  /** Private constructor. */
  private Inliner(
      TypeSystem typeSystem,
      Environment env,
      Analyzer.Analysis analysis,
      Set<String> inliningNames,
      int depth) {
    super(typeSystem, env);
    this.analysis = analysis;
    this.inliningNames = inliningNames;
    this.depth = depth;
  }

  /**
   * Strips numeric suffix from a name (e.g., "fact_4" -> "fact"). Names in
   * Morel can have suffixes like "_4" to distinguish different instances of the
   * same logical variable. For recursion detection, we need to compare base
   * names.
   */
  private static String baseName(String name) {
    int i = name.lastIndexOf('_');
    if (i > 0) {
      String suffix = name.substring(i + 1);
      if (!suffix.isEmpty() && suffix.chars().allMatch(Character::isDigit)) {
        return name.substring(0, i);
      }
    }
    return name;
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
    return new Inliner(typeSystem, env, analysis, new HashSet<>(), 0);
  }

  @Override
  protected Inliner push(Environment env) {
    return new Inliner(typeSystem, env, analysis, inliningNames, depth);
  }

  @Override
  protected Core.Exp visit(Core.Id id) {
    final Binding binding = env.getOpt(id.idPat);
    if (binding != null && !binding.parameter) {
      // For simple constant values (not functions), prefer using the computed
      // value over inlining the expression. This ensures that values like NaN
      // show as "constant(NaN)" rather than "apply2(Real./, Infinity,
      // Infinity)".
      // We check this before expression inlining so that computed constants
      // are preferred over their defining expressions.
      if (binding.value != null
          && binding.value != Unit.INSTANCE
          && !(binding.value instanceof Applicable)
          && !(binding.value instanceof Applicable1)
          && !(binding.value instanceof Closure)
          && !(binding.value instanceof Macro)
          && !(binding.value instanceof Code)) {
        final Type type = typeSystem.unqualified(id.type);
        if (type.op() == Op.ID && id.type instanceof PrimitiveType) {
          return core.literal((PrimitiveType) id.type, binding.value);
        }
        if (type.op() != Op.FUNCTION_TYPE) {
          return core.valueLiteral(id, binding.value);
        }
      }
      if (binding.exp != null) {
        // Skip inlining if:
        // 1. This pattern name is already being inlined (recursive reference)
        // 2. We've exceeded the maximum inlining depth (cycle detection)
        // Use full name including index (not just base name) to correctly
        // handle shadowed variables. For example, x_1 and x (inner) are
        // different variables and should be tracked separately.
        final String name = id.idPat.name + "_" + id.idPat.i;
        if (!inliningNames.contains(name) && depth < MAX_DEPTH) {
          // Get usage info from analysis. If the pattern isn't in the analysis
          // map (e.g., it was added after analysis), treat it as MULTI_UNSAFE
          // to be safe.
          final Analyzer.Use use;
          if (analysis == null) {
            use = Analyzer.Use.MULTI_UNSAFE;
          } else {
            final Analyzer.Use analysisUse = analysis.map.get(id.idPat);
            use = analysisUse != null ? analysisUse : Analyzer.Use.MULTI_UNSAFE;
          }
          // Determine if we should inline this expression.
          // We inline if:
          // 1. The analysis says it's ATOMIC or ONCE_SAFE, OR
          // 2. We're in an inlining pass (analysis != null) and the expression
          //    is atomic (literal/id) which is always safe to inline, even if
          //    the pattern isn't in the analysis map (e.g., variables from
          //    beta-reduced expressions). We only do this when analysis is
          //    present to respect INLINE_PASS_COUNT=0 settings.
          final boolean shouldInline =
              use == Analyzer.Use.ATOMIC
                  || use == Analyzer.Use.ONCE_SAFE
                  || analysis != null && isAtomic(binding.exp);
          if (shouldInline) {
            // Don't inline expressions containing impure function calls.
            // These expressions read mutable state (like Sys.env) and would
            // produce different values if re-evaluated at the use site.
            if (!containsImpureCall(binding.exp)) {
              inliningNames.add(name);
              // Create a new Inliner with incremented depth for the nested
              // inlining
              final Inliner nestedInliner =
                  new Inliner(
                      typeSystem, env, analysis, inliningNames, depth + 1);
              try {
                return binding.exp.accept(nestedInliner);
              } finally {
                inliningNames.remove(name);
              }
            }
          }
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
      // VALUE_LITERAL might contain non-List values (e.g., Integer) if type
      // information was lost during pattern matching. Only proceed if we
      // actually have a List (record/tuple representation).
      final Object unwrapped = ((Core.Literal) apply2.arg).unwrap(Object.class);
      if (unwrapped instanceof List) {
        @SuppressWarnings("rawtypes")
        final List list = (List) unwrapped;
        if (selector.slot < list.size()) {
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
      }
      // Fall through if VALUE_LITERAL doesn't contain a List or slot out of
      // bounds
    }
    if (apply2.fn.op == Op.FN) {
      // Beta-reduction:
      //   (fn x => E) A
      // becomes
      //   let x = A in E end
      final Core.Fn fn = (Core.Fn) apply2.fn;
      // Only perform beta-reduction if argument type is assignable to parameter
      // type.
      // This handles polymorphic type instantiation where the function's
      // parameter
      // pattern may have a different (more general) type than the actual
      // argument.
      if (TypeSystem.canAssign(apply2.arg.type, fn.idPat.type)) {
        try {
          return core.let(
              core.nonRecValDecl(apply2.pos, fn.idPat, null, apply2.arg),
              fn.exp);
        } catch (IllegalArgumentException e) {
          // Type mismatch after type variable resolution - skip beta-reduction.
          // This can happen when canAssign returns true due to type variables,
          // but the actual types don't match after instantiation.
        }
      }
      // Types incompatible - skip beta-reduction and return as-is
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
      final Map<Core.Id, Core.Exp> substitution = getSub(exp, match);
      if (substitution != null) {
        Core.Exp result =
            Replacer.substitute(typeSystem, substitution, match.exp);
        // Apply type substitution: TypeVars in the result should be replaced
        // with concrete types based on the scrutinee's type.
        // Only attempt unification if the result type contains TypeVars.
        // Note: TypeUnifier lacks cycle detection, so recursive types
        // (like DataTypes) can cause infinite recursion. We catch that
        // and skip type substitution in those cases.
        if (containsTypeVar(result.type)) {
          try {
            // Unify the polymorphic pattern type with the concrete scrutinee
            // type
            // to get the type substitution map.
            Map<Integer, Type> typeSub = match.pat.type.unifyWith(exp.type);
            if (typeSub != null && !typeSub.isEmpty()) {
              result =
                  TypeSubstitutingShuttle.substitute(
                      typeSystem, typeSub, result);
            }
          } catch (StackOverflowError e) {
            // Unification hit infinite recursion on cyclic types; skip
          } catch (IllegalArgumentException e) {
            // Type mismatch during substitution - skip
          }
        }
        return result;
      }
    }

    // If exp is a literal (simple or nullary constructor), find the matching
    // branch and return it directly. For example,
    //   case 2 of 1 => "one" | 2 => "two" | _ => "large"
    // becomes
    //   "two"
    // Only do this when analysis is available (full inlining mode),
    // and when there is more than one branch.
    if (analysis != null && matchList.size() > 1) {
      final @Nullable Object value = expToValue(exp);
      if (value != null) {
        for (Core.Match match : matchList) {
          final PairList<Core.NamedPat, Object> binds = PairList.of();
          if (Closure.bindRecurse(match.pat, value, binds::add)) {
            final AtomicReference<Core.Exp> r =
                new AtomicReference<>(match.exp);
            binds.forEach(
                (pat, v) -> {
                  // Pattern like "x => x + 1" where x binds to the literal.
                  // Convert to: let x = <literal> in <match.exp>
                  final Core.Exp e = valueToExp(typeSystem, pat.type, v);
                  r.set(
                      core.let(
                          core.nonRecValDecl(caseOf.pos, pat, null, e),
                          r.get()));
                });
            return r.get();
          }
          // Unknown pattern type; try next pattern
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

  private @Nullable Map<Core.Id, Core.Exp> getSub(
      Core.Exp exp, Core.Match match) {
    if (match.pat.op == Op.ID_PAT && isAtomic(exp)) {
      return ImmutableMap.of(core.id((Core.IdPat) match.pat), exp);
    }
    if (exp.op == Op.TUPLE && match.pat.op == Op.TUPLE_PAT) {
      final Core.Tuple tuple = (Core.Tuple) exp;
      final Core.TuplePat tuplePat = (Core.TuplePat) match.pat;
      if (allMatch(tuple.args, Inliner::isAtomic)
          && allMatch(tuplePat.args, arg -> arg.op == Op.ID_PAT)) {
        final ImmutableMap.Builder<Core.Id, Core.Exp> builder =
            ImmutableMap.builder();
        forEach(
            tuple.args,
            tuplePat.args,
            (arg, pat) -> builder.put(core.id((Core.IdPat) pat), arg));
        return builder.build();
      }
    }
    return null;
  }

  /** Returns whether an expression can be inlined without expansion. */
  static boolean isAtomic(Core.Exp exp) {
    return exp instanceof Core.Literal || exp instanceof Core.Id;
  }

  /**
   * Returns the runtime value of a constant expression, or null if it is not
   * constant. Examples of constant expressions include literals {@code 1},
   * {@code "xyz"}, {@code true} and datatype constructors {@code NONE}, {@code
   * SOME 1}.
   *
   * @see #valueToExp(TypeSystem, Type, Object)
   */
  private static @Nullable Object expToValue(Core.Exp exp) {
    switch (exp.op) {
      case BOOL_LITERAL:
      case CHAR_LITERAL:
      case STRING_LITERAL:
      case UNIT_LITERAL:
        return ((Core.Literal) exp).value;
      case REAL_LITERAL:
        return ((Core.Literal) exp).unwrap(Double.class);
      case INT_LITERAL:
        return ((Core.Literal) exp).unwrap(Integer.class);
      case VALUE_LITERAL:
        // VALUE_LITERAL can contain any value (List, Integer, String, etc.)
        // Use Object.class to extract the wrapped value regardless of type
        return ((Core.Literal) exp).unwrap(Object.class);
      case TUPLE:
        final Core.Tuple tuple = (Core.Tuple) exp;
        final ImmutableList.Builder<Object> args = ImmutableList.builder();
        for (Core.Exp arg : tuple.args) {
          final Object value = expToValue(arg);
          if (value == null) {
            return null;
          }
          args.add(value);
        }
        return args.build();

      case APPLY:
        final Core.Apply apply = (Core.Apply) exp;
        if (apply.fn instanceof Core.Id && apply.type instanceof DataType) {
          final String conName = ((Core.Id) apply.fn).idPat.name;
          final DataType dataType = (DataType) apply.type;
          if (dataType.typeConstructors.containsKey(conName)) {
            final Applicable tyCon = Codes.tyCon(apply.type, conName);
            final Object arg = expToValue(apply.arg);
            if (arg == null) {
              return null;
            }
            return tyCon.apply(EvalEnvs.empty(), arg);
          }
        }

        // fall through
      default:
        return null;
    }
  }

  /** Converts a runtime value to constant expression (usually a literal). */
  @SuppressWarnings("unchecked")
  private static Core.Exp valueToExp(
      TypeSystem typeSystem, Type type, Object value) {
    final List<Object> list;
    switch (type.op()) {
      case TY_VAR:
        // Type variable - try to infer actual type from value
        Type inferredType = inferTypeFromValue(typeSystem, value);
        if (inferredType != null) {
          return valueToExp(typeSystem, inferredType, value);
        }
        // Can't infer type, fall through to error
        throw new AssertionError(
            format(
                "cannot convert value [%s] of type [%s] to expression",
                value, type));

      case ID:
        return core.literal((PrimitiveType) type, value);

      case DATA_TYPE:
        // DataType values are represented as [constructorName, argValue]
        // But we need to check the value format first
        if (!(value instanceof List)) {
          // Value is not in constructor format - it may be a primitive
          // that happens to have a DataType wrapper (shouldn't normally happen)
          throw new AssertionError(
              format(
                  "cannot convert value [%s] of type [%s] to expression - "
                      + "expected List for DataType",
                  value, type));
        }
        list = (List<Object>) value;
        String name = (String) list.get(0);
        final Core.IdPat idPat = core.idPat(type, name, 0);
        Core.Id id = core.id(idPat);
        if (list.size() == 1) {
          // Nullary constructor: keep the full [constructorName] list
          // representation
          // so that pattern matching code in Closure.bindRecurse can correctly
          // cast it to List and extract the constructor name
          return core.valueLiteral(id, list);
        }
        Type argType = ((DataType) type).typeConstructors(typeSystem).get(name);
        Core.Exp arg = valueToExp(typeSystem, argType, list.get(1));
        return core.apply(Pos.ZERO, type, id, arg);

      case TUPLE_TYPE:
        {
          final TupleType tupleType = (TupleType) type;
          // Check if value matches expected tuple representation
          if (!(value instanceof List)) {
            // Type/value mismatch - try to infer actual type from value
            Type tupleInferredType = inferTypeFromValue(typeSystem, value);
            if (tupleInferredType != null) {
              return valueToExp(typeSystem, tupleInferredType, value);
            }
            throw new AssertionError(
                format(
                    "cannot convert value [%s] of type [%s] to expression - "
                        + "expected List for TupleType",
                    value, type));
          }
          list = (List<Object>) value;
          final ImmutableList.Builder<Core.Exp> args = ImmutableList.builder();
          forEach(
              tupleType.argTypes,
              list,
              (t, v) -> args.add(valueToExp(typeSystem, t, v)));
          return core.tuple(tupleType, args.build());
        }

      case RECORD_TYPE:
        {
          final RecordType recordType = (RecordType) type;
          // Check if value matches expected record representation
          if (!(value instanceof List)) {
            // Type/value mismatch - try to infer actual type from value
            Type recordInferredType = inferTypeFromValue(typeSystem, value);
            if (recordInferredType != null) {
              return valueToExp(typeSystem, recordInferredType, value);
            }
            throw new AssertionError(
                format(
                    "cannot convert value [%s] of type [%s] to expression - "
                        + "expected List for RecordType",
                    value, type));
          }
          list = (List<Object>) value;
          final ImmutableMap.Builder<String, Core.Exp> recordFields =
              ImmutableMap.builder();
          int i = 0;
          for (Map.Entry<String, Type> entry :
              recordType.argNameTypes.entrySet()) {
            recordFields.put(
                entry.getKey(),
                valueToExp(typeSystem, entry.getValue(), list.get(i++)));
          }
          return core.record(typeSystem, recordFields.build());
        }

      default:
        throw new AssertionError(
            format(
                "cannot convert value [%s] of type [%s] to expression",
                value, type));
    }
  }

  /** Infers type from a runtime value. Returns null if cannot infer. */
  private static @Nullable Type inferTypeFromValue(
      TypeSystem typeSystem, Object value) {
    if (value instanceof Integer) {
      return PrimitiveType.INT;
    } else if (value instanceof String) {
      return PrimitiveType.STRING;
    } else if (value instanceof Boolean) {
      return PrimitiveType.BOOL;
    } else if (value instanceof Float || value instanceof Double) {
      return PrimitiveType.REAL;
    } else if (value instanceof Character) {
      return PrimitiveType.CHAR;
    } else if (value instanceof Unit) {
      return PrimitiveType.UNIT;
    } else if (value instanceof List) {
      // For lists, we need to infer element type
      @SuppressWarnings("unchecked")
      List<Object> listValue = (List<Object>) value;
      if (listValue.isEmpty()) {
        return null; // Can't infer element type from empty list
      }
      Type elementType = inferTypeFromValue(typeSystem, listValue.get(0));
      if (elementType != null) {
        return typeSystem.listType(elementType);
      }
    }
    return null;
  }

  @Override
  protected Core.RecValDecl visit(Core.RecValDecl recValDecl) {
    // Add the recursive function names to inliningNames to prevent
    // recursive expansion. This is critical because:
    // 1. Multiple inlining passes create fresh Inliner instances
    // 2. Without this, each pass would expand recursive calls one more level
    // 3. This prevents massive expansion like path -> edge orelse path ->
    //    edge orelse (edge orelse path) -> etc.
    // Use full names (not base names) to correctly handle shadowed variables
    final List<String> addedNames = new ArrayList<>();
    for (Core.NonRecValDecl decl : recValDecl.list) {
      if (decl.pat instanceof Core.IdPat) {
        final String name = ((Core.IdPat) decl.pat).name;
        if (!inliningNames.contains(name)) {
          inliningNames.add(name);
          addedNames.add(name);
        }
      }
    }
    try {
      return super.visit(recValDecl);
    } finally {
      // Remove the names we added to restore original state
      addedNames.forEach(inliningNames::remove);
    }
  }

  @Override
  protected Core.Exp visit(Core.Let let) {
    final Analyzer.Use use =
        analysis == null
            ? Analyzer.Use.MULTI_UNSAFE
            : let.decl instanceof Core.NonRecValDecl
                // Use MULTI_UNSAFE as default when pattern isn't in analysis
                // (can happen when inlining function bodies from other
                // declarations)
                ? analysis.map.getOrDefault(
                    ((Core.NonRecValDecl) let.decl).pat,
                    Analyzer.Use.MULTI_UNSAFE)
                : Analyzer.Use.MULTI_UNSAFE;
    switch (use) {
      case DEAD:
        // This declaration has no uses; remove it
        return let.exp.accept(this);

      case ATOMIC:
      case ONCE_SAFE:
        // This declaration has one use; remove the declaration, and replace its
        // use inside the expression.
        // IMPORTANT: We must first inline the declaration's expression before
        // storing it in the binding. Otherwise, references in the expression
        // to outer-scope variables will fail when we try to expand them later
        // (because those outer bindings may have been removed by then).
        if (let.decl instanceof Core.NonRecValDecl) {
          final Core.NonRecValDecl nonRecDecl = (Core.NonRecValDecl) let.decl;
          // First, inline the declaration's expression while outer bindings are
          // still available
          final Core.Exp inlinedDeclExp = nonRecDecl.exp.accept(this);
          // Create binding with the inlined expression
          final List<Binding> bindingsInlined = new ArrayList<>();
          if (nonRecDecl.pat instanceof Core.IdPat) {
            bindingsInlined.add(
                Binding.of((Core.IdPat) nonRecDecl.pat, inlinedDeclExp));
          }
          return let.exp.accept(bind(bindingsInlined));
        }
        // Fallback for non-NonRecValDecl cases (shouldn't happen for ONCE_SAFE)
        final List<Binding> bindings = new ArrayList<>();
        Compiles.bindPattern(typeSystem, bindings, let.decl);
        return let.exp.accept(bind(bindings));
    }
    return super.visit(let);
  }

  /** Returns whether the type contains any type variables. */
  private static boolean containsTypeVar(Type type) {
    final boolean[] found = {false};
    type.accept(
        new TypeVisitor<Void>() {
          @Override
          public Void visit(TypeVar typeVar) {
            found[0] = true;
            return null;
          }
        });
    return found[0];
  }

  /**
   * Returns whether the expression contains any call to an impure function.
   * Impure functions read mutable state (like Sys.env) and should not be
   * inlined because the expression would be re-evaluated at the use site,
   * potentially returning a different value.
   */
  private static boolean containsImpureCall(Core.Exp exp) {
    final boolean[] found = {false};
    exp.accept(
        new Visitor() {
          @Override
          protected void visit(Core.Id id) {
            if (IMPURE_FUNCTION_NAMES.contains(id.idPat.name)) {
              found[0] = true;
            }
          }

          @Override
          protected void visit(Core.RecordSelector recordSelector) {
            // Check if this record selector accesses an impure field
            // (e.g., Sys.env, Sys.clearEnv)
            if (IMPURE_FUNCTION_NAMES.contains(recordSelector.fieldName())) {
              found[0] = true;
            }
          }
        });
    // Also check if the expression's string representation contains impure
    // function patterns. This handles cases where macros like Sys.env have been
    // expanded into VALUE_LITERALs that we can't directly inspect.
    if (!found[0]) {
      final String expStr = exp.toString();
      for (String name : IMPURE_FUNCTION_NAMES) {
        if (expStr.contains("#" + name + " ")) {
          found[0] = true;
          break;
        }
      }
    }
    return found[0];
  }
}

// End Inliner.java
