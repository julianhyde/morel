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

import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.type.TypeVar;
import net.hydromatic.morel.util.PairList;
import net.hydromatic.morel.util.Unifier;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static net.hydromatic.morel.util.Pair.forEach;
import static net.hydromatic.morel.util.Static.transform;
import static net.hydromatic.morel.util.Static.transformEager;

/**
 * Visitor that converts type terms into actual types.
 */
class TermToTypeConverter implements Unifier.TermVisitor<Type> {
  private final TypeSystem typeSystem;
  private final Function<Unifier.Variable, Unifier.Term> varToTerm;
  private final Function<Unifier.Variable, TypeVar> varToType;

  TermToTypeConverter(TypeSystem typeSystem,
      Function<Unifier.Variable, Unifier.Term> varToTerm,
      Function<Unifier.Variable, TypeVar> varToType) {
    this.typeSystem = typeSystem;
    this.varToTerm = varToTerm;
    this.varToType = varToType;
  }

  @Override public Type visit(Unifier.Sequence sequence) {
    final Type type;
    switch (sequence.operator) {
    case TypeResolver.FN_TY_CON:
      assert sequence.terms.size() == 2;
      final Type paramType = sequence.terms.get(0).accept(this);
      final Type resultType = sequence.terms.get(1).accept(this);
      return typeSystem.fnType(paramType, resultType);

    case TypeResolver.TUPLE_TY_CON:
      assert sequence.terms.size() != 1;
      final List<Type> argTypes =
          transformEager(sequence.terms, term -> term.accept(this));
      return typeSystem.tupleType(argTypes);

    case TypeResolver.LIST_TY_CON:
      assert sequence.terms.size() == 1;
      final Type elementType = sequence.terms.get(0).accept(this);
      return typeSystem.listType(elementType);

    case "bool":
    case "char":
    case "int":
    case "real":
    case "string":
    case "unit":
    default:
      type = typeSystem.lookupOpt(sequence.operator);
      if (type != null) {
        if (sequence.terms.isEmpty()) {
          return type;
        }
        final List<Type> types =
            transform(sequence.terms, t -> t.accept(this));
        return typeSystem.apply(type, types);
      }
      if (sequence.operator.startsWith(TypeResolver.RECORD_TY_CON)) {
        // E.g. "record:a:b" becomes record type "{a:t0, b:t1}".
        final List<String> argNames = TypeResolver.fieldList(sequence);
        if (argNames != null) {
          final PairList<String, Type> argNameTypes = PairList.of();
          final AtomicBoolean progressive = new AtomicBoolean(false);
          forEach(argNames, sequence.terms, (name, term) -> {
            if (name.equals(TypeResolver.PROGRESSIVE_LABEL)) {
              progressive.set(true);
            } else {
              argNameTypes.add(name, term.accept(this));
            }
          });
          return progressive.get()
              ? typeSystem.progressiveRecordType(argNameTypes)
              : typeSystem.recordType(argNameTypes);
        }
      }
      throw new AssertionError("unknown type constructor "
          + sequence.operator);
    }
  }

  @Override public Type visit(Unifier.Variable variable) {
    final Unifier.Term term = varToTerm.apply(variable);
    if (term != null) {
      return term.accept(this);
    }
    return varToType.apply(variable);
  }
}

// End TermToTypeConverter.java
