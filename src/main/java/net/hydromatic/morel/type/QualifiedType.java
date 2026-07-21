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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;
import net.hydromatic.morel.ast.Op;

/**
 * A type qualified by one or more overload constraints (predicates).
 *
 * <p>For example, {@code {foo : 'a -> 'b} => 'a -> 'b} is the type of a
 * function that, for any {@code 'a} and {@code 'b} such that there is an
 * instance of the overloaded name {@code foo} of type {@code 'a -> 'b}, maps
 * {@code 'a} to {@code 'b}.
 *
 * <p>Following "A Second Look at Overloading" (Odersky, Wadler, Wehr 1995), an
 * overloaded application whose argument type is not yet known records a
 * predicate rather than resolving eagerly; the predicate is discharged when the
 * type variable becomes concrete.
 *
 * <p>A qualified type usually sits just below a {@link ForallType} binder, so a
 * generalized scheme looks like {@code forall 'a 'b. {foo : 'a -> 'b} => 'a ->
 * 'b}.
 */
public class QualifiedType extends BaseType {
  public final List<Predicate> predicates;
  public final Type type;

  QualifiedType(List<Predicate> predicates, Type type) {
    super(Op.QUALIFIED_TYPE);
    this.predicates = ImmutableList.copyOf(predicates);
    this.type = requireNonNull(type);
    checkArgument(!this.predicates.isEmpty());
  }

  @Override
  public Key key() {
    return Keys.qualified(predicates, type);
  }

  @Override
  public <R> R accept(TypeVisitor<R> typeVisitor) {
    return typeVisitor.visit(this);
  }

  @Override
  public QualifiedType copy(
      TypeSystem typeSystem, UnaryOperator<Type> transform) {
    final Type type2 = type.copy(typeSystem, transform);
    boolean changed = type2 != type;
    final ImmutableList.Builder<Predicate> b = ImmutableList.builder();
    for (Predicate p : predicates) {
      // Transform the predicate's own type (which shares variables with the
      // body), but leave the candidate instance types untouched: they belong
      // to a separate variable scope.
      final Type pType2 = p.type.copy(typeSystem, transform);
      if (pType2 != p.type) {
        changed = true;
      }
      b.add(pType2 == p.type ? p : new Predicate(p.name, pType2, p.candidates));
    }
    return changed ? typeSystem.qualifiedType(b.build(), type2) : this;
  }

  @Override
  public boolean canCallArgOf(Type type) {
    return this.type.canCallArgOf(type);
  }

  /**
   * An overload constraint: the name {@code name} must have an instance whose
   * type is {@code type} (a function type over the enclosing scheme's type
   * variables).
   *
   * <p>{@code candidates} records the instance types that were in scope when
   * the predicate was formed, so that the constraint can be re-created (with
   * fresh variables) each time the scheme is instantiated.
   */
  public static class Predicate {
    public final String name;
    public final Type type;
    public final List<Type> candidates;

    public Predicate(String name, Type type, List<Type> candidates) {
      this.name = requireNonNull(name);
      this.type = requireNonNull(type);
      this.candidates = ImmutableList.copyOf(candidates);
    }

    @Override
    public int hashCode() {
      return Objects.hash(name, type, candidates);
    }

    @Override
    public boolean equals(Object o) {
      return o == this
          || o instanceof Predicate
              && ((Predicate) o).name.equals(name)
              && ((Predicate) o).type.equals(type)
              && ((Predicate) o).candidates.equals(candidates);
    }

    @Override
    public String toString() {
      return name + " : " + type;
    }
  }
}

// End QualifiedType.java
