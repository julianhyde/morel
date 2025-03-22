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
package net.hydromatic.morel.util;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Unification algorithm due to Martelli, Montanari (1976) and Paterson, Wegman
 * (1978).
 */
public class MartelliUnifier extends Unifier {
  public @NonNull Result unify(
      List<TermTerm> termPairs,
      Map<Variable, Action> termActions,
      Tracer tracer) {
    final long start = System.nanoTime();

    // delete: G u { t = t }
    //   => G

    // decompose: G u { f(s0, ..., sk) = f(t0, ..., tk) }
    //   => G u {s0 = t0, ..., sk = tk}

    // conflict: G u { f(s0, ..., sk) = g(t0, ..., tm) }
    //   => fail
    // if f <> g or k <> m

    // swap: G u { f(s0, ..., sk) = x }
    //  => G u { x = f(s0, ..., sk) }

    // eliminate: G u { x = t }
    //  => G { x |-> t } u { x = t }
    // if x not in vars(t) and x in vars(G)

    // check: G u { x = f(s0, ..., sk)}
    //  => fail
    // if x in vars(f(s0, ..., sk))

    final Work work = new Work(tracer, termPairs);
    final Map<Variable, Term> result = new LinkedHashMap<>();
    for (int iteration = 0; ; iteration++) {
      // delete
      @Nullable TermTerm pair = work.popDelete();
      if (pair != null) {
        tracer.onDelete(pair.left, pair.right);
        continue;
      }

      pair = work.popSeqSeq();
      if (pair != null) {
        final Sequence left = (Sequence) pair.left;
        final Sequence right = (Sequence) pair.right;

        if (!left.operator.equals(right.operator)
            || left.terms.size() != right.terms.size()) {
          tracer.onConflict(left, right);
          return failure("conflict: " + left + " vs " + right);
        }

        // decompose
        tracer.onSequence(left, right);
        for (int j = 0; j < left.terms.size(); j++) {
          work.add(new TermTerm(left.terms.get(j), right.terms.get(j)));
        }
        continue;
      }

      pair = work.popVarAny();
      if (pair != null) {
        final Variable variable = (Variable) pair.left;
        final Term term = pair.right;
        if (term.contains(variable)) {
          tracer.onCycle(variable, term);
          return failure("cycle: variable " + variable + " in " + term);
        }
        tracer.onVariable(variable, term);
        result.put(variable, term);
        act(variable, term, work, new Substitution(result), termActions, 0);
        work.substituteList(ImmutableMap.of(variable, term));
      }

      final long duration = System.nanoTime() - start;
      if (true) {
        System.out.printf(
            "Term count %,d iterations %,d duration %,d nanos"
                + " (%,d nanos per iteration)%n",
            termPairs.size(), iteration, duration, duration / (iteration + 1));
      }
      return SubstitutionResult.create(result);
    }
  }

  private void act(
      Variable variable,
      Term term,
      Work work,
      Substitution substitution,
      Map<Variable, Action> termActions,
      int depth) {
    final Action action = termActions.get(variable);
    if (action != null) {
      action.accept(
          variable,
          term,
          substitution,
          (leftTerm, rightTerm) -> work.add(new TermTerm(leftTerm, rightTerm)));
    }
    if (term instanceof Variable) {
      // Create a temporary list to prevent concurrent modification, in case the
      // action appends to the list. Limit on depth, to prevent infinite
      // recursion.
      final List<TermTerm> termPairsCopy = work.allTermPairs();
      termPairsCopy.forEach(
          termPair -> {
            if (termPair.left.equals(term) && depth < 2) {
              act(
                  variable,
                  termPair.right,
                  work,
                  substitution,
                  termActions,
                  depth + 1);
            }
          });
      // If the term is a variable, recurse to see whether there is an
      // action for that variable. Limit on depth to prevent swapping back.
      if (depth < 1) {
        act(
            (Variable) term,
            variable,
            work,
            substitution,
            termActions,
            depth + 1);
      }
    }
    substitution.resultMap.forEach(
        (variable2, v) -> {
          // Substitution contains "variable2 -> variable"; call the actions of
          // "variable2", because it too has just been unified.
          if (v.equals(variable)) {
            act(variable2, term, work, substitution, termActions, depth + 1);
          }
        });
  }

  private int findDelete(List<TermTerm> termPairs) {
    for (int i = 0; i < termPairs.size(); i++) {
      TermTerm termTerm = termPairs.get(i);
      if (termTerm.left.equals(termTerm.right)) {
        return i;
      }
    }
    return -1;
  }

  private int findSeqSeq(List<TermTerm> termPairs) {
    for (int i = 0; i < termPairs.size(); i++) {
      TermTerm termTerm = termPairs.get(i);
      if (termTerm.left instanceof Sequence
          && termTerm.right instanceof Sequence) {
        return i;
      }
    }
    return -1;
  }

  private int findNonVarVar(List<TermTerm> termPairs) {
    for (int i = 0; i < termPairs.size(); i++) {
      TermTerm termTerm = termPairs.get(i);
      if (!(termTerm.left instanceof Variable)
          && termTerm.right instanceof Variable) {
        return i;
      }
    }
    return -1;
  }

  private int findVarAny(List<TermTerm> termPairs) {
    for (int i = 0; i < termPairs.size(); i++) {
      TermTerm termTerm = termPairs.get(i);
      if (termTerm.left instanceof Variable) {
        return i;
      }
    }
    return -1;
  }

  /** Workspace for {@link MartelliUnifier}. */
  static class Work {
    final Tracer tracer;
    final ArrayQueue<TermTerm> deleteQueue = new ArrayQueue<>();
    final ArrayQueue<TermTerm> seqSeqQueue = new ArrayQueue<>();
    final ArrayQueue<TermTerm> varAnyQueue = new ArrayQueue<>();

    Work(Tracer tracer, List<TermTerm> termPairs) {
      this.tracer = tracer;
      termPairs.forEach(this::add);
    }

    private static boolean isSeqSeq(TermTerm pair) {
      return pair.left instanceof Sequence && pair.right instanceof Sequence;
    }

    private static boolean isDelete(TermTerm pair) {
      return pair.left.equals(pair.right);
    }

    private static boolean isVarAny(TermTerm pair) {
      return pair.left instanceof Variable;
    }

    void add(TermTerm pair) {
      if (isDelete(pair)) {
        deleteQueue.add(pair);
      } else if (isSeqSeq(pair)) {
        seqSeqQueue.add(pair);
      } else if (!(pair.left instanceof Variable) && pair.right instanceof Variable) {
        tracer.onSwap(pair.left, pair.right);
        varAnyQueue.add(new TermTerm(pair.right, pair.left));
      } else {
        varAnyQueue.add(new TermTerm(pair.left, pair.right));
      }
    }

    /** Finds, and removes, the first pair whose left and right are equal. */
    @Nullable
    TermTerm popDelete() {
      return deleteQueue.poll();
    }

    /**
     * Finds, and removes, the first pair whose left and right are both
     * sequences.
     */
    @Nullable
    TermTerm popSeqSeq() {
      return seqSeqQueue.poll();
    }

    /**
     * Finds, and removes, the first pair whose left is a variable. Right might
     * be anything.
     */
    @Nullable
    TermTerm popVarAny() {
      return varAnyQueue.poll();
    }

    /** Returns a list of all term pairs. */
    List<TermTerm> allTermPairs() {
      final ImmutableList.Builder<TermTerm> builder = ImmutableList.builder();
      deleteQueue.forAll(builder::add);
      seqSeqQueue.forAll(builder::add);
      varAnyQueue.forAll(builder::add);
      return builder.build();
    }

    /**
     * Applies a mapping to all term pairs in a list, modifying them in place.
     */
    private void substituteList(Map<Variable, Term> map) {
      sub(map, deleteQueue, Work::isDelete);
      sub(map, seqSeqQueue, Work::isSeqSeq);
      sub(map, varAnyQueue, Work::isVarAny);
    }

    private void sub(
        Map<Variable, Term> map,
        ArrayQueue<TermTerm> queue,
        Predicate<TermTerm> predicate) {
      for (int j = 0; j < queue.size(); j++) {
        final TermTerm pair2 = queue.get(j);
        final Term left2 = pair2.left.apply(map);
        final Term right2 = pair2.right.apply(map);
        if (left2 != pair2.left || right2 != pair2.right) {
          tracer.onSubstitute(pair2.left, pair2.right, left2, right2);
          TermTerm pair = new TermTerm(left2, right2);
          if (predicate.test(pair)) {
            // Still belongs in this queue
            queue.set(j, pair);
          } else {
            // Belongs in another queue
            queue.remove(j);
            add(pair);
          }
        }
      }
    }
  }
}

// End MartelliUnifier.java
