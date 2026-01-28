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

import net.hydromatic.morel.ast.Ast;
import net.hydromatic.morel.ast.Core;

/**
 * Value restriction for let-polymorphism.
 *
 * <p>Per the Standard ML Definition (Section 4.7 "Closure"), only syntactic
 * values can be generalized. This prevents unsound type assignments that would
 * otherwise occur with mutable references.
 *
 * <p>A syntactic value is:
 *
 * <ul>
 *   <li>A lambda expression (fn x => e)
 *   <li>A literal (42, true, "hello", etc.)
 *   <li>A tuple or record where all components are values
 *   <li>A list where all elements are values
 *   <li>A constructor application where the argument is a value
 *   <li>A let expression where the body is a value
 * </ul>
 *
 * <p>Function applications (including ref creation) are NOT values.
 */
public final class ValueRestriction {
  private ValueRestriction() {}

  /**
   * Determines whether an expression is a syntactic value.
   *
   * <p>Only syntactic values can be safely generalized to polymorphic types
   * without risking unsoundness from mutable state.
   *
   * @param exp the expression to check
   * @return true if the expression is a syntactic value
   */
  public static boolean isValue(Core.Exp exp) {
    switch (exp.op) {
        // Lambda expressions are always values
      case FN:
        return true;

        // Literals are values
      case BOOL_LITERAL:
      case CHAR_LITERAL:
      case INT_LITERAL:
      case REAL_LITERAL:
      case STRING_LITERAL:
      case UNIT_LITERAL:
      case FN_LITERAL:
      case VALUE_LITERAL:
        return true;

        // Tuples and records are values if all components are values
      case TUPLE:
      case RECORD:
        if (exp instanceof Core.Tuple) {
          Core.Tuple tuple = (Core.Tuple) exp;
          return tuple.args.stream().allMatch(ValueRestriction::isValue);
        }
        return false;

        // Let expressions: body must be a value
        // Note: We don't check the bound expressions because they're already
        // processed separately for their own polymorphism.
      case LET:
        if (exp instanceof Core.Let) {
          Core.Let let = (Core.Let) exp;
          return isValue(let.exp);
        }
        return false;

        // Function applications require special handling
      case APPLY:
        if (exp instanceof Core.Apply) {
          Core.Apply apply = (Core.Apply) exp;
          // Check if this is a data constructor application
          // Data constructors (SOME, ::, user-defined) applied to values are
          // values
          if (isDataConstructor(apply.fn)) {
            return isValue(apply.arg);
          }
          // Check if this is a list literal [e1, e2, ...]
          // List literals are represented as Apply(Z_LIST, tuple)
          if (isListLiteral(apply)) {
            if (apply.arg instanceof Core.Tuple) {
              Core.Tuple tuple = (Core.Tuple) apply.arg;
              return tuple.args.stream().allMatch(ValueRestriction::isValue);
            }
            // Empty list is a value
            return true;
          }
        }
        // Other function applications (including ref) are NOT values
        return false;

        // CONS (::) - list cons is a value if both parts are values
      case CONS:
        if (exp instanceof Core.Apply) {
          Core.Apply apply = (Core.Apply) exp;
          if (apply.arg instanceof Core.Tuple) {
            Core.Tuple tuple = (Core.Tuple) apply.arg;
            if (tuple.args.size() == 2) {
              return isValue(tuple.args.get(0)) && isValue(tuple.args.get(1));
            }
          }
        }
        return false;

        // Everything else (CASE, IF, FROM, etc.) is NOT a value
      default:
        return false;
    }
  }

  /**
   * Determines whether an expression is a data constructor.
   *
   * <p>Data constructors include:
   *
   * <ul>
   *   <li>Built-in constructors like SOME, NONE
   *   <li>User-defined datatype constructors
   *   <li>The :: (cons) operator
   * </ul>
   */
  private static boolean isDataConstructor(Core.Exp fn) {
    if (fn instanceof Core.Literal) {
      Core.Literal literal = (Core.Literal) fn;
      // Check if this is a built-in constructor
      Object value = literal.value;
      if (value instanceof BuiltIn) {
        // The :: (cons) operator is a value constructor when applied to values.
        // Other datatype constructors (SOME, user-defined) are represented
        // differently, so we handle them conservatively.
        return value == BuiltIn.OP_CONS;
      }
    }
    // User-defined constructors would be Core.Id with datatype reference.
    // For now, we're conservative - only built-in cons is recognized.
    // This is sound because being conservative means we might not generalize
    // some things that could be generalized, but we won't incorrectly
    // generalize non-values.
    return false;
  }

  /**
   * Determines whether an Apply expression is a list literal.
   *
   * <p>List literals [e1, e2, ...] are represented as Apply(Z_LIST, tuple).
   */
  private static boolean isListLiteral(Core.Apply apply) {
    if (apply.fn instanceof Core.Literal) {
      Core.Literal literal = (Core.Literal) apply.fn;
      Object value = literal.value;
      if (value instanceof BuiltIn) {
        return (BuiltIn) value == BuiltIn.Z_LIST;
      }
    }
    return false;
  }

  /**
   * Determines whether an expression should be generalized.
   *
   * <p>An expression should be generalized if:
   *
   * <ul>
   *   <li>It passes the value restriction (is a syntactic value)
   *   <li>Its type contains free type variables
   * </ul>
   *
   * <p>The type variable check is done separately in TypeSystem.generalize().
   *
   * @param exp the expression to check
   * @return true if the expression should be generalized
   */
  public static boolean shouldGeneralize(Core.Exp exp) {
    return isValue(exp);
  }

  // =========================================================================
  // AST-level value restriction (for use during type inference)
  // =========================================================================

  /**
   * Determines whether an AST expression is a syntactic value.
   *
   * <p>This AST-level check is used during type inference to determine whether
   * a binding should be generalized for let-polymorphism.
   *
   * @param exp the AST expression to check
   * @return true if the expression is a syntactic value
   */
  public static boolean isValueAst(Ast.Exp exp) {
    switch (exp.op) {
        // Lambda expressions are always values
      case FN:
        return true;

        // Literals are values
      case BOOL_LITERAL:
      case CHAR_LITERAL:
      case INT_LITERAL:
      case REAL_LITERAL:
      case STRING_LITERAL:
      case UNIT_LITERAL:
        return true;

        // Tuple: all components must be values
      case TUPLE:
        if (exp instanceof Ast.Tuple) {
          Ast.Tuple tuple = (Ast.Tuple) exp;
          return tuple.args.stream().allMatch(ValueRestriction::isValueAst);
        }
        return false;

        // Record: all field values must be values
      case RECORD:
        if (exp instanceof Ast.Record) {
          Ast.Record record = (Ast.Record) exp;
          // Check if all expressions in the record are values
          for (java.util.Map.Entry<Ast.Id, Ast.Exp> entry : record.args) {
            if (!isValueAst(entry.getValue())) {
              return false;
            }
          }
          return true;
        }
        return false;

        // List: all elements must be values
      case LIST:
        if (exp instanceof Ast.ListExp) {
          Ast.ListExp list = (Ast.ListExp) exp;
          return list.args.stream().allMatch(ValueRestriction::isValueAst);
        }
        return false;

        // Let expression: body must be a value
      case LET:
        if (exp instanceof Ast.Let) {
          Ast.Let let = (Ast.Let) exp;
          return isValueAst(let.exp);
        }
        return false;

        // Function application: NOT a value (unless it's a constructor)
        // For AST, we can't easily distinguish constructors from functions,
        // so we're conservative and say applications are not values.
        // This is sound but may miss some generalizations.
      case APPLY:
        return false;

        // Infix operations are applications, not values
      case PLUS:
      case MINUS:
      case TIMES:
      case DIVIDE:
      case NEGATE:
      case EQ:
      case NE:
      case LT:
      case LE:
      case GT:
      case GE:
      case ANDALSO:
      case ORELSE:
      case CONS:
      case CARET:
        return false;

        // Control flow is not a value
      case IF:
      case CASE:
        return false;

        // Identifiers (variable references) - we consider them values
        // because they refer to already-bound values
      case ID:
        return true;

        // Everything else is not a value
      default:
        return false;
    }
  }

  /**
   * Determines whether an AST expression should be generalized during type
   * inference.
   *
   * @param exp the AST expression to check
   * @return true if the expression should be generalized
   */
  public static boolean shouldGeneralizeAst(Ast.Exp exp) {
    return isValueAst(exp);
  }
}

// End ValueRestriction.java
