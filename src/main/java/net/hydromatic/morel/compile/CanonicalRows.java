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

import java.util.SortedMap;
import net.hydromatic.morel.ast.Core;
import net.hydromatic.morel.ast.Shuttle;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Canonical patterns for a pass that writes a node's expressions before it has
 * the node.
 *
 * <p>Every node binds patterns of its own, distinct from every other node's. A
 * pass that reads expressions off several nodes and moves them between nodes is
 * simplest written over one row: an expression read off a node is rewritten
 * over the canonical patterns first, and an expression written into a node is
 * rewritten over patterns minted for that node.
 */
class CanonicalRows {
  /** Ordinal of the canonical patterns; no node's pattern has it. */
  static final int CANON = -1;

  static final Core.IdPat ROW = core.idPat(PrimitiveType.UNIT, "$0", CANON);
  static final Core.IdPat RIGHT_ROW =
      core.idPat(PrimitiveType.UNIT, "$1", CANON);
  static final Core.IdPat ORDINAL =
      core.idPat(PrimitiveType.INT, "$ordinal", CANON);

  private final TypeSystem typeSystem;

  CanonicalRows(TypeSystem typeSystem) {
    this.typeSystem = typeSystem;
  }

  /** Canonical reference to a node's input element, {@code $0}. */
  Core.Id row0(Type elementType) {
    return core.id(core.idPat(elementType, "$0", CANON));
  }

  /** Canonical reference to a join's right input element, {@code $1}. */
  Core.Id row1(Type elementType) {
    return core.id(core.idPat(elementType, "$1", CANON));
  }

  /** Canonical reference to input {@code i}'s element. */
  Core.Id row(Type elementType, int i) {
    return i == 0 ? row0(elementType) : row1(elementType);
  }

  /**
   * Returns whether an expression is the canonical reference to input {@code
   * i}.
   */
  static boolean isRow(Core.Exp exp, int i) {
    return exp instanceof Core.Id
        && ((Core.Id) exp).idPat.equals(i == 0 ? ROW : RIGHT_ROW);
  }

  /** Returns whether an expression is a canonical reference to either input. */
  static boolean isRow(Core.Exp exp) {
    return isRow(exp, 0) || isRow(exp, 1);
  }

  /** Rewrites an expression of a node over the canonical patterns. */
  Core.Exp canonical(Core.Rel rel, Core.Exp exp) {
    Core.Exp e = exp;
    if (rel instanceof Core.Join) {
      final Core.Join join = (Core.Join) rel;
      e = substitute(e, join.leftRow, row0(join.leftRow.type));
      e = substitute(e, join.rightRow, row1(join.rightRow.type));
      if (join.ordinal != null) {
        e = substitute(e, join.ordinal, core.id(ORDINAL));
      }
    } else if (rel instanceof Core.RowRel) {
      final Core.RowRel rowRel = (Core.RowRel) rel;
      e = substitute(e, rowRel.row, row0(rowRel.row.type));
      if (rowRel.ordinal != null) {
        e = substitute(e, rowRel.ordinal, core.id(ORDINAL));
      }
    }
    return e;
  }

  /**
   * Rewrites an expression over the canonical patterns to be over a node's own.
   */
  Core.Exp real(
      Core.Exp exp,
      Core.IdPat row,
      Core.@Nullable IdPat rightRow,
      Core.@Nullable IdPat ordinal) {
    Core.Exp e = substitute(exp, ROW, core.id(row));
    if (rightRow != null) {
      e = substitute(e, RIGHT_ROW, core.id(rightRow));
    }
    if (ordinal != null) {
      e = substitute(e, ORDINAL, core.id(ordinal));
    }
    return e;
  }

  /** Replaces references to a pattern with an expression. */
  Core.Exp substitute(Core.Exp exp, Core.NamedPat pat, Core.Exp to) {
    return exp.accept(
        new Shuttle(typeSystem) {
          @Override
          protected Core.Exp visit(Core.Id id) {
            return id.idPat.equals(pat) ? core.at(to, id.pos) : id;
          }
        });
  }

  /** Mints the ordinal pattern for a node whose expressions read one. */
  Core.@Nullable IdPat ordinalFor(Core.Exp... exps) {
    for (Core.Exp exp : exps) {
      if (Core.mentions(exp, ORDINAL)) {
        return core.ordinalPat(typeSystem.nameGenerator::inc);
      }
    }
    return null;
  }

  Core.IdPat rowPat(Core.Exp input) {
    return core.rowPat(input.type.elementType(), typeSystem.nameGenerator::inc);
  }

  /** Builds a filter from a condition over the canonical row. */
  Core.Filter filter(Core.Exp input, Core.Exp condition) {
    final Core.IdPat row = rowPat(input);
    final Core.@Nullable IdPat ordinal = ordinalFor(condition);
    return core.filter(
        row, ordinal, input, real(condition, row, null, ordinal));
  }

  /** Builds a projection from an expression over the canonical row. */
  Core.Project project(Core.Exp input, Core.Exp exp) {
    final Core.IdPat row = rowPat(input);
    final Core.@Nullable IdPat ordinal = ordinalFor(exp);
    return core.project(
        typeSystem, row, ordinal, input, real(exp, row, null, ordinal));
  }

  /** Builds a sort from a key over the canonical row. */
  Core.Sort sort(Core.Exp input, Core.Exp exp) {
    final Core.IdPat row = rowPat(input);
    final Core.@Nullable IdPat ordinal = ordinalFor(exp);
    return core.sort(
        typeSystem, row, ordinal, input, real(exp, row, null, ordinal));
  }

  /** Builds a group from keys and aggregates over the canonical row. */
  Core.Group group(
      Core.Exp input,
      SortedMap<String, Core.Exp> keys,
      SortedMap<String, Core.Aggregate> aggregates) {
    final Core.IdPat row = rowPat(input);
    final java.util.TreeMap<String, Core.Exp> keys2 =
        new java.util.TreeMap<>(keys.comparator());
    keys.forEach((name, key) -> keys2.put(name, real(key, row, null, null)));
    final java.util.TreeMap<String, Core.Aggregate> aggregates2 =
        new java.util.TreeMap<>(aggregates.comparator());
    aggregates.forEach(
        (name, agg) ->
            aggregates2.put(
                name,
                core.aggregate(
                    agg.pos,
                    agg.type,
                    real(agg.aggregate, row, null, null),
                    agg.argument == null
                        ? null
                        : real(agg.argument, row, null, null))));
    return core.group(typeSystem, row, null, input, keys2, aggregates2);
  }

  /** Builds a join from a condition over the canonical rows. */
  Core.Join join(
      Core.Rel.JoinType joinType,
      Core.Exp left,
      Core.Exp right,
      Core.Exp condition) {
    return dependentJoin(joinType, left, right, null, condition);
  }

  /**
   * Builds a join whose right input may read the left element through a name,
   * which becomes the join's left row.
   */
  Core.Join dependentJoin(
      Core.Rel.JoinType joinType,
      Core.Exp left,
      Core.Exp right,
      Core.@Nullable IdPat param,
      Core.Exp condition) {
    final Core.IdPat leftRow = rowPat(left);
    final Core.IdPat rightRow =
        core.rightRowPat(
            right.type.elementType(), typeSystem.nameGenerator::inc);
    final Core.@Nullable IdPat ordinal = ordinalFor(condition);
    final Core.Exp right2 =
        param == null ? right : substitute(right, param, core.id(leftRow));
    return core.join(
        typeSystem,
        joinType,
        leftRow,
        rightRow,
        ordinal,
        left,
        right2,
        real(condition, leftRow, rightRow, ordinal));
  }
}

// End CanonicalRows.java
