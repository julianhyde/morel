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
package net.hydromatic.morel.type;

import net.hydromatic.morel.ast.Op;

import com.google.common.collect.ContiguousSet;
import com.google.common.collect.DiscreteDomain;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Range;
import com.google.common.collect.RangeSet;
import org.apache.calcite.runtime.Unit;
import org.apache.calcite.util.RangeSets;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.SortedMap;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/** Primitive type. */
public enum PrimitiveType implements RecordLikeType {
  BOOL,
  CHAR,
  INT,
  REAL,
  STRING,
  UNIT {
    @Override public SortedMap<String, Type> argNameTypes() {
      // "unit" behaves like a record/tuple type with no fields
      return ImmutableSortedMap.of();
    }

    @Override public Type argType(int i) {
      throw new IndexOutOfBoundsException();
    }
  };

  /** The name in the language, e.g. {@code bool}. */
  public final String moniker = name().toLowerCase(Locale.ROOT);

  @Override public String toString() {
    return moniker;
  }

  @Override public Key key() {
    return Keys.name(moniker);
  }

  @Override public Op op() {
    return Op.ID;
  }


  public <R> R accept(TypeVisitor<R> typeVisitor) {
    return typeVisitor.visit(this);
  }

  @Override public boolean isFinite() {
    return this == BOOL || this == UNIT;
  }

  @SuppressWarnings("unchecked")
  @Override public <E extends Comparable<E>> boolean populate(
      RangeSet<E> rangeSet, Consumer<E> consumer) {
    switch (this) {
    case BOOL:
      for (Boolean b : new Boolean[] {false, true}) {
        if (rangeSet.contains((E) b)) {
          consumer.accept((E) b);
        }
      }
      return true;

    case UNIT:
      if (rangeSet.contains((E) Unit.INSTANCE)) {
        consumer.accept((E) Unit.INSTANCE);
      }
      return true;

    case INT:
      RangeSet<BigDecimal> bigDecimalRangeSet = (RangeSet<BigDecimal>) rangeSet;
      RangeSet<Integer> integerRangeSet =
          RangeSets.copy(bigDecimalRangeSet, BigDecimal::intValue);
      for (Range<Integer> range : integerRangeSet.asRanges()) {
        if (!range.hasLowerBound() || !range.hasUpperBound()) {
          return false; // infinite
        }
        ContiguousSet.create(range, DiscreteDomain.integers())
            .forEach(i -> consumer.accept((E) i));
      }
      return true;

    case CHAR:
      rangeSet.asRanges().forEach(r -> {
        ContiguousSet.create(
            r.intersection((Range<E>) Range.closedOpen(0, 256)),
                (DiscreteDomain<E>) DiscreteDomain.integers())
            .forEach(consumer);
      });
      return true;

    default:
      return false;
    }
  }


  @Override public PrimitiveType copy(TypeSystem typeSystem,
      UnaryOperator<Type> transform) {
    return this;
  }

  @Override public SortedMap<String, Type> argNameTypes() {
    throw new UnsupportedOperationException();
  }

  @Override public Type argType(int i) {
    throw new UnsupportedOperationException();
  }
}

// End PrimitiveType.java
