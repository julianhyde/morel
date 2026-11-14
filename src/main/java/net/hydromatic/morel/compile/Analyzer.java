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

import static net.hydromatic.morel.ast.CoreBuilder.core;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Multimap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.hydromatic.morel.ast.AstNode;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Exps;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.type.Binding;
import net.hydromatic.morel.type.TypeSystem;
import org.jspecify.annotations.Nullable;

/** Shuttle that counts how many times each expression is used. */
public class Analyzer extends EnvVisitor {
  private final Map<Core.NamedPat, MutableUse> map;

  /**
   * How many lambdas and per-row expressions of a query enclose the node being
   * visited. A binding read at a greater depth than it was declared may be read
   * many times.
   */
  private final int depth;

  /**
   * Uses in the enclosing expression, if this analyzer counts the uses in one
   * branch of a {@code case}; null otherwise. A binding declared outside the
   * branch has its declaration's depth there.
   */
  private final @Nullable Map<Core.NamedPat, MutableUse> outer;

  /** Creates an Analyzer. */
  private static Analyzer of(TypeSystem typeSystem, Environment env) {
    return new Analyzer(typeSystem, env, new HashMap<>(), 0, null);
  }

  /** Private constructor. */
  private Analyzer(
      TypeSystem typeSystem,
      Environment env,
      Map<Core.NamedPat, MutableUse> map,
      int depth,
      @Nullable Map<Core.NamedPat, MutableUse> outer) {
    super(typeSystem, env);
    this.map = map;
    this.depth = depth;
    this.outer = outer;
  }

  /** Analyzes an expression. */
  public static Analysis analyze(
      TypeSystem typeSystem, Environment env, AstNode node) {
    final Analyzer analyzer = of(typeSystem, env);

    // Mark all top-level bindings so that they will not be removed
    if (node instanceof Core.NonRecValDecl) {
      analyzer.use(((Core.NonRecValDecl) node).pat).top = true;
    }
    node.accept(analyzer);
    return analyzer.result();
  }

  /** Returns the result of an analysis. */
  private Analysis result() {
    final ImmutableMap.Builder<Core.NamedPat, Use> b = ImmutableMap.builder();
    map.forEach((k, v) -> b.put(k, v.fix()));
    return new Analysis(b.build());
  }

  @Override
  protected Analyzer push(Environment env) {
    return new Analyzer(typeSystem, env, map, depth, outer);
  }

  /** Returns an analyzer for a node that may be evaluated many times. */
  private Analyzer deeper() {
    return new Analyzer(typeSystem, env, map, depth + 1, outer);
  }

  @Override
  protected void visit(Core.IdPat idPat) {
    final MutableUse use = use(idPat);
    if (use.depth < 0) {
      use.depth = depth;
    }
  }

  @Override
  protected void visit(Core.Id id) {
    final MutableUse use = use(id.idPat);
    use.useCount++;
    if (use.depth >= 0 && depth > use.depth) {
      use.repeated = true;
    }
    super.visit(id);
  }

  // A lambda's body, and the expressions that a query evaluates for each
  // row, may be evaluated many times.

  @Override
  protected void visit(Core.Fn fn) {
    fn.idPat.accept(this);
    fn.exp.accept(deeper().bind(Binding.of(fn.idPat)));
  }

  @Override
  protected void visit(Core.Filter filter) {
    filter.input.accept(this);
    filter.condition.accept(deeper().bind(patternBindings(filter)));
  }

  @Override
  protected void visit(Core.Project project) {
    project.input.accept(this);
    project.exp.accept(deeper().bind(patternBindings(project)));
  }

  @Override
  protected void visit(Core.Sort sort) {
    sort.input.accept(this);
    sort.exp.accept(deeper().bind(patternBindings(sort)));
  }

  @Override
  protected void visit(Core.Join join) {
    join.left.accept(this);
    final List<Binding> rightBindings = new ArrayList<>();
    rightBindings.add(Binding.of(join.leftId));
    if (join.ordinalId != null) {
      rightBindings.add(Binding.of(join.ordinalId));
    }
    join.right.accept(deeper().bind(rightBindings));
    join.condition.accept(deeper().bind(patternBindings(join)));
  }

  @Override
  protected void visit(Core.Group group) {
    group.input.accept(this);
    final EnvVisitor rowV = deeper().bind(patternBindings(group));
    group.keys.values().forEach(rowV::accept);
    final List<Binding> bindings = new ArrayList<>();
    group.keys.forEach(
        (name, key) -> bindings.add(Binding.of(core.idPat(key.type, name, 0))));
    // The aggregate function is evaluated once for each group.
    final EnvVisitor v2 = deeper().bind(bindings);
    group
        .aggregates
        .values()
        .forEach(
            aggregate -> {
              aggregate.aggregate.accept(v2);
              if (aggregate.argument != null) {
                aggregate.argument.accept(rowV);
              }
            });
  }

  /** Gets or creates a {@link MutableUse} for a given name. */
  private MutableUse use(Core.NamedPat name) {
    return map.computeIfAbsent(
        name,
        k -> {
          final MutableUse use = new MutableUse();
          final MutableUse outerUse = outer == null ? null : outer.get(name);
          if (outerUse != null) {
            use.depth = outerUse.depth;
          }
          return use;
        });
  }

  @Override
  protected void visit(Core.NonRecValDecl valDecl) {
    super.visit(valDecl);
    if (valDecl.exp.isAtom()) {
      use(valDecl.pat).atomic = true;
    }
    if (valDecl.exp.op == Op.FN
        || valDecl.exp.isSimple()
        || valDecl.exp.isConstant()) {
      use(valDecl.pat).value = true;
    }
  }

  @Override
  protected void visit(Core.Case case_) {
    case_.exp.accept(this);
    if (case_.matchList.size() == 1) {
      // When there is a single branch, we don't need to check for a single use
      // on multiple branches, so we can expedite.
      case_.matchList.get(0).accept(this);
    } else {
      // Create a multimap of all uses of bindings along the separate
      // branches. Example:
      //  case e of
      //    1 => a + c
      //  | 2 => a + b + a
      //  | _ => c
      //
      // a has use counts [1, 2] and is therefore MULTI_UNSAFE
      // b has use counts [1] and is therefore ONCE_SAFE
      // c has use counts [1, 1] and is therefore MULTI_SAFE
      final Multimap<Core.NamedPat, MutableUse> multimap =
          HashMultimap.create();
      final Map<Core.NamedPat, MutableUse> subMap = new HashMap<>();
      final Map<Core.NamedPat, MutableUse> outerMap =
          outer == null ? map : merged(outer, map);
      final Analyzer analyzer =
          new Analyzer(typeSystem, env, subMap, depth, outerMap);
      case_.matchList.forEach(
          e -> {
            subMap.clear();
            e.accept(analyzer);
            subMap.forEach(multimap::put);
          });
      multimap
          .asMap()
          .forEach(
              (id, uses) -> {
                final MutableUse baseUse = use(id);
                int maxCount = MutableUse.max(uses);
                if (uses.size() > 1) {
                  baseUse.parallel = true;
                }
                baseUse.useCount += maxCount;
                // What a branch learned about the binding: whether a use is
                // inside a lambda or a query that its declaration is not, and,
                // for a binding that a branch declares, what it is bound to.
                for (MutableUse use : uses) {
                  baseUse.repeated |= use.repeated;
                  baseUse.value |= use.value;
                  baseUse.atomic |= use.atomic;
                  if (baseUse.depth < 0) {
                    baseUse.depth = use.depth;
                  }
                }
              });
    }
  }

  /**
   * Returns a map that contains the entries of {@code map} and, for keys that
   * it lacks, of {@code outer}; for the uses of a {@code case} inside a branch
   * of another {@code case}.
   */
  private static Map<Core.NamedPat, MutableUse> merged(
      Map<Core.NamedPat, MutableUse> outer,
      Map<Core.NamedPat, MutableUse> map) {
    final Map<Core.NamedPat, MutableUse> merged = new HashMap<>(outer);
    merged.putAll(map);
    return merged;
  }

  /** How a binding (assignment of a value to a variable) is used. */
  public enum Use {
    /**
     * Indicates that the binding cannot be inlined because recursively refers
     * to itself (or more precisely, is part of a recursive cycle and has been
     * chosen as the link to remove to break the cycle).
     */
    LOOP_BREAKER,

    /**
     * Binding is not used. For a let (whether recursive or not), the binding
     * can be discarded.
     */
    DEAD,

    /**
     * The binding occurs exactly once, and that occurrence is not inside a
     * lambda, nor is a constructor argument. Inlining is unconditionally safe;
     * it duplicates neither code nor work.
     */
    ONCE_SAFE,

    /**
     * The binding is an atom (variable or literal). Regardless of how many
     * times it is used, inlining is unconditionally safe; it duplicates neither
     * code nor work.
     */
    ATOMIC,

    /**
     * The binding occurs at most once in each of several distinct case
     * branches; none of these occurrences is inside a lambda. For example:
     *
     * <pre>{@code
     * case xs of
     *   [] => y + 1
     * | x :: xs => y + 2
     * }</pre>
     *
     * <p>In this expression, {@code y} occurs only once in each case branch.
     * Inlining {@code y} may duplicate code, but it will not duplicate work.
     */
    MULTI_SAFE,

    /**
     * The binding occurs exactly once, but inside a lambda. Inlining will not
     * duplicate code, but it might duplicate work.
     *
     * <p>We must not inline an arbitrary expression inside a lambda, as the
     * following example (from GHC inlining section 2.2) shows:
     *
     * <pre>{@code
     * val f = fn x => E
     * val g = fn ys => map f ys
     * }</pre>
     *
     * <p>If we were to inline f inside g, thus:
     *
     * <pre>{@code
     * val g = fn ys => map (fn x => E) ys
     * }</pre>
     *
     * <p>no code is duplicated, but a small bounded amount of work is
     * duplicated, because the closure {@code fn x => E} must be allocated each
     * time {@code g} is called.
     */
    ONCE_UNSAFE,

    /** The binding may occur many times, including inside lambdas. */
    MULTI_UNSAFE,
  }

  /**
   * Work space where the uses of a binding are counted. When all the uses have
   * been found, call {@link #fix} to convert this into a {@link Use}.
   */
  private static class MutableUse {
    boolean top;
    boolean atomic;
    boolean parallel;
    /**
     * Whether the expression is a lambda, is simple (see {@link Exps}), or is a
     * constant; inlining it into a loop repeats no work worth avoiding.
     */
    boolean value;
    /**
     * Whether a use is inside a lambda or query that its declaration is not.
     */
    boolean repeated;
    /** Depth of the declaration, or -1 if not seen. */
    int depth = -1;

    int useCount;

    static int max(Collection<MutableUse> uses) {
      int max = 0;
      for (MutableUse use : uses) {
        max = Math.max(max, use.useCount);
      }
      return max;
    }

    @Override
    public String toString() {
      return "[" + useCount + (parallel ? " parallel]" : "]");
    }

    Use fix() {
      return top
          ? Use.MULTI_UNSAFE
          : useCount == 0
              ? Use.DEAD
              : atomic
                  ? Use.ATOMIC
                  : useCount == 1
                      ? (repeated && !value
                          // Inlining would evaluate it each time the lambda
                          // or the query's row expression is evaluated.
                          ? Use.ONCE_UNSAFE
                          : parallel ? Use.MULTI_SAFE : Use.ONCE_SAFE)
                      : Use.MULTI_UNSAFE;
    }
  }

  /** Result of an analysis. */
  public static class Analysis {
    public final ImmutableMap<Core.NamedPat, Use> map;

    Analysis(ImmutableMap<Core.NamedPat, Use> map) {
      this.map = map;
    }
  }
}

// End Analyzer.java
