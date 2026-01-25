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

import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Op;

/**
 * Matches patterns between function formal parameters and call arguments.
 *
 * <p>When inverting a function call like {@code edge p}, we need to:
 *
 * <ul>
 *   <li>Match the call argument ({@code p}) to the function's formal parameter
 *       ({@code (x,y)})
 *   <li>Determine the effective goal pattern for the generator
 *   <li>Handle scalar-to-tuple and tuple-to-scalar transformations
 * </ul>
 *
 * <p>Examples:
 *
 * <ul>
 *   <li>{@code from p where edge p}: scalar {@code p} matches tuple {@code
 *       (x,y)}. Generator produces tuples bound to {@code p}.
 *   <li>{@code from x, y where edge (x, y)}: tuple matches tuple directly.
 *       Generator produces tuples, components bound to {@code x} and {@code y}.
 * </ul>
 */
public class PatternMatcher {

  private PatternMatcher() {} // Utility class

  /** Result of pattern matching between call argument and formal parameter. */
  public static final class MatchResult {
    /** The effective goal pattern for the generator. */
    public final Core.Pat goalPat;

    /**
     * The goal patterns (NamedPats) that will receive generated values. These
     * are the patterns from goalPats that are actually bound.
     */
    public final ImmutableList<Core.NamedPat> boundPats;

    /**
     * Whether the match requires tuple-to-scalar transformation. If true, the
     * generator produces tuples that are bound to a single scalar variable.
     */
    public final boolean isScalarBinding;

    MatchResult(
        Core.Pat goalPat,
        List<Core.NamedPat> boundPats,
        boolean isScalarBinding) {
      this.goalPat = requireNonNull(goalPat);
      this.boundPats = ImmutableList.copyOf(boundPats);
      this.isScalarBinding = isScalarBinding;
    }
  }

  /**
   * Matches a function call argument against goal patterns.
   *
   * <p>Given a call like {@code edge p} where {@code p} is in goalPats,
   * determines how the generator's output should be bound.
   *
   * @param callArg the argument expression passed to the function
   * @param formalParam the function's formal parameter pattern
   * @param goalPats the patterns we want to generate values for
   * @return match result describing how to bind generator output, or empty if
   *     the argument doesn't reference goal patterns
   */
  public static Optional<MatchResult> match(
      Core.Exp callArg, Core.Pat formalParam, List<Core.NamedPat> goalPats) {
    // Extract the pattern(s) from the call argument
    List<Core.NamedPat> argPats = extractPatterns(callArg, goalPats);
    if (argPats.isEmpty()) {
      return Optional.empty(); // Argument doesn't reference goal patterns
    }

    // Case 1: Scalar argument (single ID) with tuple/record formal
    if (argPats.size() == 1 && isTupleOrRecord(formalParam)) {
      // Scalar binding: p binds to the entire tuple
      return Optional.of(new MatchResult(argPats.get(0), argPats, true));
    }

    // Case 2: Tuple argument with tuple formal (direct match)
    if (argPats.size() > 1 && formalParam instanceof Core.TuplePat) {
      Core.TuplePat tupleFormal = (Core.TuplePat) formalParam;
      if (argPats.size() == tupleFormal.args.size()) {
        // Components match - use the argument patterns directly
        return Optional.of(
            new MatchResult(makeTuplePat(argPats), argPats, false));
      }
    }

    // Case 3: Single argument with single formal (direct match)
    if (argPats.size() == 1 && formalParam instanceof Core.NamedPat) {
      return Optional.of(new MatchResult(argPats.get(0), argPats, false));
    }

    // Fallback: use first pattern as scalar binding
    if (!argPats.isEmpty()) {
      return Optional.of(new MatchResult(argPats.get(0), argPats, true));
    }

    return Optional.empty();
  }

  /**
   * Extracts named patterns from an expression that reference goal patterns.
   *
   * @param exp the expression to analyze
   * @param goalPats the patterns we're looking for
   * @return list of goal patterns referenced by the expression
   */
  private static List<Core.NamedPat> extractPatterns(
      Core.Exp exp, List<Core.NamedPat> goalPats) {
    List<Core.NamedPat> result = new ArrayList<>();

    if (exp.op == Op.ID) {
      // Simple variable reference
      Core.Id id = (Core.Id) exp;
      for (Core.NamedPat goalPat : goalPats) {
        if (id.idPat.equals(goalPat)) {
          result.add(goalPat);
          return result;
        }
      }
    } else if (exp.op == Op.TUPLE) {
      // Tuple of variables: (x, y)
      Core.Tuple tuple = (Core.Tuple) exp;
      for (Core.Exp arg : tuple.args) {
        result.addAll(extractPatterns(arg, goalPats));
      }
    }

    return result;
  }

  /** Returns whether the pattern is a tuple or record pattern. */
  private static boolean isTupleOrRecord(Core.Pat pat) {
    return pat instanceof Core.TuplePat || pat instanceof Core.RecordPat;
  }

  /** Creates a tuple pattern from a list of named patterns. */
  private static Core.Pat makeTuplePat(List<Core.NamedPat> pats) {
    if (pats.size() == 1) {
      return pats.get(0);
    }
    // For now, return the first pattern - proper tuple construction
    // would require TypeSystem access
    return pats.get(0);
  }
}
// End PatternMatcher.java
