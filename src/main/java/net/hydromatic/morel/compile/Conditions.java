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

import static net.hydromatic.morel.ast.AstBuilder.ast;

import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import net.hydromatic.morel.ast.Ast;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.ast.Pos;
import net.hydromatic.morel.ast.Shuttle;
import net.hydromatic.morel.ast.Visitor;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.PairList;
import org.jspecify.annotations.Nullable;

/**
 * Carrying a {@code check} condition from one record type to another.
 *
 * <p>A record modifier that adds, removes or renames a field gives a record of
 * a different shape, and a condition is typed against the exact record type it
 * was written for, because records are not width-subtyped. So a condition can
 * be carried over only if it is rewritten to hold of the new record.
 *
 * <p>The rewrite is an inversion. If {@code m} is the modifier and {@code c}
 * the condition, then the condition of the record {@code m} produced is {@code
 * c} of the record it was applied to -- and the record it was applied to is
 * {@code m}<sup>-1</sup> of the record in hand:
 *
 * <blockquote>
 *
 * <pre>{@code
 * c' = fn r => c {r <inverse of m>}
 * }</pre>
 *
 * </blockquote>
 *
 * <p>So {@code r => #i r < 10} carried over {@code rename y = i} is first
 * {@code r => #i {r rename i = y} < 10}, and then simplifies, because selecting
 * {@code i} from a record whose {@code i} came from {@code y} is selecting
 * {@code y}, to {@code r => #y r < 10}.
 *
 * <p>The inverse of {@code extend} is {@code remove}, and of {@code rename} the
 * rename the other way. A field the modifier removed or assigned to has no
 * inverse -- its old value is gone -- so the inverse supplies a {@link #HOLE},
 * and a condition that still mentions the hole once the simplification has run
 * is dropped. That is the one thing dropped, and it is sound: a condition that
 * is dropped claims less.
 */
class Conditions {
  /** The name a condition's record is rebound to when it is destructured. */
  private static final String RECORD = "$r";

  /**
   * Stands for the value of a field whose old value is gone. A condition that
   * needs one cannot be carried over, and is dropped.
   */
  private static final String HOLE = "$hole";

  private Conditions() {}

  /**
   * Returns {@code check} rewritten to hold of the record a modifier produced,
   * or null if it cannot be.
   *
   * @param oldFields the fields of the record the modifier was applied to
   * @param newFields the fields of the record it produced
   * @param kept maps each field the modifier carried over unchanged to the name
   *     it carried it over at
   */
  static Ast.@Nullable Fn inherit(
      TypeSystem typeSystem,
      Ast.Fn check,
      Collection<String> oldFields,
      Collection<String> newFields,
      Map<String, String> kept) {
    final List<Ast.Modifier> inverse =
        inverse(check.pos, oldFields, newFields, kept);
    if (inverse.isEmpty()) {
      // The modifier left the shape alone, so there is nothing to invert.
      return check;
    }
    if (check.matchList.stream().allMatch(m -> m.pat.op == Op.ID_PAT)) {
      return substituting(typeSystem, check, inverse);
    }
    if (check.matchList.size() == 1) {
      return destructuring(check.matchList.get(0), inverse);
    }
    return null;
  }

  /**
   * Returns the modifiers that turn the record a modifier produced back into
   * the record it was applied to.
   *
   * <p>A field the modifier added is removed, one it renamed is renamed back,
   * and one whose old value is gone is supplied as a {@link #HOLE}. Returns an
   * empty list if the modifier left the record's shape and names alone, so that
   * a condition needs no rewriting at all.
   */
  private static List<Ast.Modifier> inverse(
      Pos pos,
      Collection<String> oldFields,
      Collection<String> newFields,
      Map<String, String> kept) {
    final List<Ast.Modifier> modifiers = new ArrayList<>();

    final List<Ast.Id> added = new ArrayList<>();
    newFields.forEach(
        field -> {
          if (!kept.containsValue(field)) {
            added.add(ast.id(pos, field));
          }
        });
    if (!added.isEmpty()) {
      modifiers.add(ast.removeModifier(Ast.ModifierVerb.REMOVE, added));
    }

    final PairList<Ast.Id, Ast.Id> renames = PairList.of();
    kept.forEach(
        (oldField, newField) -> {
          if (!oldField.equals(newField)) {
            renames.add(ast.id(pos, oldField), ast.id(pos, newField));
          }
        });
    if (!renames.isEmpty()) {
      modifiers.add(ast.renameModifier(renames));
    }

    final PairList<Ast.Id, Ast.Exp> holes = PairList.of();
    oldFields.forEach(
        field -> {
          if (!kept.containsKey(field)) {
            holes.add(ast.id(pos, field), ast.id(pos, HOLE));
          }
        });
    if (!holes.isEmpty()) {
      modifiers.add(ast.assignModifier(Ast.ModifierVerb.EXTEND, false, holes));
    }
    return modifiers;
  }

  /**
   * Rewrites a condition that names the record -- {@code r => #i r < 10} -- by
   * substituting the inverse for the name.
   */
  private static Ast.@Nullable Fn substituting(
      TypeSystem typeSystem, Ast.Fn check, List<Ast.Modifier> inverse) {
    final List<Ast.Match> matches = new ArrayList<>();
    for (Ast.Match match : check.matchList) {
      final String name = ((Ast.IdPat) match.pat).name;
      // The name is bound again to the record in hand, so the inverse is
      // expressed in terms of it, and the condition reads as it was written.
      final Ast.Exp inverseExp =
          ast.record(
              match.pat.pos,
              ast.id(match.pat.pos, name),
              PairList.of(),
              inverse);
      final Ast.Exp exp =
          AstSubstituter.substitute(typeSystem, match.exp, name, inverseExp);
      if (exp == null) {
        return null;
      }
      final Ast.Exp exp2 = simplify(typeSystem, exp);
      if (usesHole(exp2) || survives(exp2)) {
        return null;
      }
      matches.add(match.copy(match.pat, exp2));
    }
    return ast.fn(check.pos, matches);
  }

  /**
   * Rewrites a condition that destructures the record -- {@code {a, b} => a <
   * 10} -- into one that selects from the record in hand.
   *
   * <blockquote>
   *
   * <pre>{@code
   * {a, b} => a < 10
   * ==>
   * $r => let val a = #a {$r <inverse>} and b = #b {$r <inverse>} in a < 10 end
   * ==>
   * $r => let val a = #a $r and b = #b $r in a < 10 end
   * }</pre>
   *
   * </blockquote>
   *
   * <p>Only an irrefutable pattern is rewritten. A refutable one -- {@code {a =
   * 0, b}} -- decides by not matching, and a {@code val} that does not match
   * raises {@code Bind} rather than answering false.
   */
  private static Ast.@Nullable Fn destructuring(
      Ast.Match match, List<Ast.Modifier> inverse) {
    if (match.pat.op != Op.RECORD_PAT) {
      return null;
    }
    final Ast.RecordPat recordPat = (Ast.RecordPat) match.pat;
    if (recordPat.ellipsis) {
      return null;
    }
    final Pos pos = match.pat.pos;
    final Ast.Exp inverseExp =
        ast.record(pos, ast.id(pos, RECORD), PairList.of(), inverse);
    final PairList<Ast.Pat, Ast.Exp> binds = PairList.of();
    for (Map.Entry<String, Ast.Pat> arg : recordPat.args.entrySet()) {
      if (!irrefutable(arg.getValue())) {
        return null;
      }
      final Ast.Exp exp = select(pos, arg.getKey(), inverseExp);
      if (exp == null || usesHole(exp)) {
        return null;
      }
      binds.add(arg.getValue(), exp);
    }
    if (binds.isEmpty()) {
      // The pattern binds nothing, so the condition does not depend on any
      // field and holds of any record.
      return ast.fn(
          match.pos, ast.match(pos, ast.idPat(pos, RECORD), match.exp));
    }
    final List<Ast.ValBind> valBinds =
        binds.transform((pat, exp) -> ast.valBind(pos, pat, exp));
    final Ast.Exp let =
        ast.let(
            pos,
            ImmutableList.of(ast.valDecl(pos, false, false, valBinds)),
            match.exp);
    return ast.fn(match.pos, ast.match(pos, ast.idPat(pos, RECORD), let));
  }

  /** Returns whether a pattern matches every value, and so cannot decide. */
  private static boolean irrefutable(Ast.Pat pat) {
    return pat.op == Op.ID_PAT || pat.op == Op.WILDCARD_PAT;
  }

  /**
   * Simplifies the selections on a modified record: {@code #f {e ...}} is a
   * selection on {@code e} of whichever of its fields {@code f} came from.
   *
   * <p>This is what turns an inverse into the rewrite it stands for, and it is
   * valid of any modified record, not only an inverse.
   */
  private static Ast.Exp simplify(TypeSystem typeSystem, Ast.Exp exp) {
    return exp.accept(
        new Shuttle(typeSystem) {
          @Override
          protected Ast.Exp visit(Ast.Apply apply) {
            final Ast.Exp exp2 = super.visit(apply);
            if (exp2.op != Op.APPLY) {
              return exp2;
            }
            final Ast.Apply apply2 = (Ast.Apply) exp2;
            if (apply2.fn.op == Op.RECORD_SELECTOR
                && apply2.arg.op == Op.RECORD) {
              final Ast.RecordSelector selector =
                  (Ast.RecordSelector) apply2.fn;
              final Ast.Exp exp3 =
                  select(apply2.pos, selector.name, apply2.arg);
              if (exp3 != null) {
                return exp3;
              }
            }
            return apply2;
          }
        });
  }

  /**
   * Returns the field {@code field} of a modified record, as an expression on
   * the record the modifiers were applied to, or null if the modifiers do not
   * say.
   *
   * <p>The modifiers are traced from the last to the first, each saying where
   * the field it is asked for came from. A modifier that assigns is traced
   * through only when it assigns a {@link #HOLE}, because the expression an
   * assignment gives sees the record's fields as names of its own, and moving
   * it would take it out of that scope.
   */
  private static Ast.@Nullable Exp select(
      Pos pos, String field, Ast.Exp record0) {
    if (record0.op != Op.RECORD) {
      return null;
    }
    final Ast.Record record = (Ast.Record) record0;
    if (record.base == null || !record.args.isEmpty()) {
      return null;
    }
    String field2 = field;
    for (int i = record.modifiers.size() - 1; i >= 0; i--) {
      final Ast.Modifier modifier = record.modifiers.get(i);
      if (modifier instanceof Ast.RemoveModifier) {
        // The field is in the result, so it is not one that was removed.
        continue;
      }
      if (modifier instanceof Ast.RenameModifier) {
        final Ast.RenameModifier rename = (Ast.RenameModifier) modifier;
        for (Map.Entry<Ast.Id, Ast.Id> arg : rename.args) {
          if (arg.getKey().name.equals(field2)) {
            field2 = arg.getValue().name;
            break;
          }
        }
        continue;
      }
      if (modifier instanceof Ast.AssignModifier) {
        final Ast.AssignModifier assign = (Ast.AssignModifier) modifier;
        Ast.Exp assigned = null;
        for (Map.Entry<Ast.Id, Ast.Exp> arg : assign.args) {
          if (arg.getKey().name.equals(field2)) {
            assigned = arg.getValue();
          }
        }
        if (assigned == null) {
          continue;
        }
        if (assigned.op == Op.ID && ((Ast.Id) assigned).name.equals(HOLE)) {
          return assigned;
        }
        return null;
      }
      return null;
    }
    return ast.apply(ast.recordSelector(pos, field2), record.base);
  }

  /**
   * Returns whether an inverse survived the simplification -- which is to say
   * that the condition uses the record as a whole, rather than by selecting
   * fields from it.
   *
   * <p>The condition it stands for is a true one, and the record it is written
   * of is exactly the record the condition was written for, so this ought to be
   * carried over. It is not, because deducing it does not terminate: the record
   * it modifies is the one whose type is being deduced, and its fields are not
   * known until that deduction is done. Until that is untangled the condition
   * is dropped, which claims less, and is sound.
   */
  private static boolean survives(Ast.Exp exp) {
    final boolean[] found = {false};
    exp.accept(
        new Visitor() {
          @Override
          protected void visit(Ast.Record record) {
            if (record.base != null) {
              found[0] = true;
            }
            super.visit(record);
          }
        });
    return found[0];
  }

  /** Returns whether an expression asks for a value that is gone. */
  private static boolean usesHole(Ast.Exp exp) {
    final boolean[] found = {false};
    exp.accept(
        new Visitor() {
          @Override
          protected void visit(Ast.Id id) {
            if (id.name.equals(HOLE)) {
              found[0] = true;
            }
          }
        });
    return found[0];
  }
}

// End Conditions.java
