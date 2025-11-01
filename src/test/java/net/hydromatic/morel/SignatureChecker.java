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
package net.hydromatic.morel;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static net.hydromatic.morel.TestUtils.toPascalCase;
import static net.hydromatic.morel.TestUtils.toSnakeCase;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import net.hydromatic.morel.ast.Ast;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.type.DataType;
import net.hydromatic.morel.type.FnType;
import net.hydromatic.morel.type.ForallType;
import net.hydromatic.morel.type.ListType;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.TupleType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.type.TypeVar;

/** Validates signature declarations against enum values in {@link BuiltIn}. */
class SignatureChecker {
  private SignatureChecker() {}

  static void checkSignature(Ast.SignatureDecl signatureDecl) {
    for (Ast.SignatureBind bind : signatureDecl.binds) {
      // If bind.name.name is "INTEGER", verify struct "Int".
      String structure;
      switch (bind.name.name) {
        case "BOOLEAN":
          structure = "Bool";
          break;
        case "INTEGER":
          structure = "Int";
          break;
        default:
          structure = toPascalCase(bind.name.name);
          break;
      }
      if (structure != null) {
        System.out.println("Verifying structure: " + structure);
        verifyBuiltInType(bind, structure);
      }
    }
  }

  private static void verifyBuiltInType(
      Ast.SignatureBind bind, String structure) {
    final TypeSystem typeSystem = new TypeSystem();
    BuiltIn.dataTypes(typeSystem, new ArrayList<>());

    // Build a map of the values in enum BuiltIn.
    final SortedMap<String, String> map = new TreeMap<>();
    for (BuiltIn builtIn : BuiltIn.values()) {
      if (structure.equals(builtIn.structure)) {
        map.put(builtIn.mlName, str(typeSystem, builtIn));
      }
    }

    // Build a map of the values in the signature.
    // Only include specs that have corresponding BuiltIn entries.
    final SortedMap<String, String> map2 = new TreeMap<>();
    for (Ast.Spec spec : bind.specs) {
      if (spec.op == Op.SPEC_VAL) {
        final Ast.ValSpec valSpec = (Ast.ValSpec) spec;
        String signatureName = valSpec.name.name;
        // Normalize operator names by adding "op " prefix for lookup
        String lookupName =
            isOperatorSymbol(signatureName)
                ? "op " + signatureName
                : signatureName;
        // Only add if this value exists in BuiltIn
        if (map.containsKey(lookupName)) {
          map2.put(lookupName, str(structure, valSpec));
        }
      }
    }

    String left = String.join("", map.values());
    String right = String.join("", map2.values());
    assertThat(
        "If there is a line on the left but not the right, "
            + "you need to add a value to enum BuiltIn",
        left,
        is(right));
  }

  /** Returns true if the name is an operator symbol (not alphanumeric). */
  private static boolean isOperatorSymbol(String name) {
    if (name.isEmpty()) {
      return false;
    }
    // Operator symbols are non-alphanumeric characters
    // Standard ML operators include: + - * / < > <= >= = <> :: @ ^ etc.
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      if (Character.isLetterOrDigit(c) || c == '_' || c == '\'') {
        return false;
      }
    }
    return true;
  }

  private static String str(String structure, Ast.ValSpec s) {
    String signatureName = s.name.name;
    // Normalize operator names by adding "op " prefix
    String normalizedName =
        isOperatorSymbol(signatureName) ? "op " + signatureName : signatureName;
    // Collect all type variables in this ValSpec and create a mapping
    final java.util.Map<String, Integer> tyVarMap = new java.util.HashMap<>();
    collectTyVars(s.type, tyVarMap);
    return format(
        "%s_%s(\"%s\", \"%s\", ts -> %s)\n",
        requireNonNull(structure).toUpperCase(Locale.ROOT),
        toSnakeCase(normalizedName).toUpperCase(Locale.ROOT),
        structure,
        normalizedName,
        str(s.type, tyVarMap));
  }

  /** Collects all type variables in an Ast.Type and assigns them ordinals. */
  private static void collectTyVars(
      Ast.Type t, java.util.Map<String, Integer> tyVarMap) {
    switch (t.op) {
      case FUNCTION_TYPE:
        final Ast.FunctionType fnType = (Ast.FunctionType) t;
        collectTyVars(fnType.paramType, tyVarMap);
        collectTyVars(fnType.resultType, tyVarMap);
        break;
      case TUPLE_TYPE:
        final Ast.TupleType tupleType = (Ast.TupleType) t;
        for (Ast.Type type : tupleType.types) {
          collectTyVars(type, tyVarMap);
        }
        break;
      case NAMED_TYPE:
        final Ast.NamedType namedType = (Ast.NamedType) t;
        for (Ast.Type type : namedType.types) {
          collectTyVars(type, tyVarMap);
        }
        break;
      case TY_VAR:
        final Ast.TyVar tyVar = (Ast.TyVar) t;
        tyVarMap.putIfAbsent(tyVar.name, tyVarMap.size());
        break;
      default:
        // Other types don't contain type variables
        break;
    }
  }

  private static String str(TypeSystem typeSystem, BuiltIn b) {
    Type type = b.typeFunction.apply(typeSystem);
    // Collect all type variables and create a normalized mapping
    final Map<Integer, Integer> typeVarMap = new java.util.HashMap<>();
    collectTypeVars(type, typeVarMap);
    return format(
        "%s_%s(\"%s\", \"%s\", ts -> %s)\n",
        requireNonNull(b.structure).toUpperCase(Locale.ROOT),
        toSnakeCase(b.mlName).toUpperCase(Locale.ROOT),
        b.structure,
        b.mlName,
        str(type, typeVarMap));
  }

  /**
   * Collects all type variables in a Type and assigns them normalized ordinals.
   */
  private static void collectTypeVars(
      Type t, Map<Integer, Integer> typeVarMap) {
    switch (t.op()) {
      case FUNCTION_TYPE:
        final FnType fnType = (FnType) t;
        collectTypeVars(fnType.paramType, typeVarMap);
        collectTypeVars(fnType.resultType, typeVarMap);
        break;
      case TUPLE_TYPE:
        final TupleType tupleType = (TupleType) t;
        for (Type argType : tupleType.argTypes) {
          collectTypeVars(argType, typeVarMap);
        }
        break;
      case DATA_TYPE:
        final DataType dataType = (DataType) t;
        for (Type arg : dataType.arguments) {
          collectTypeVars(arg, typeVarMap);
        }
        break;
      case LIST:
        final ListType listType = (ListType) t;
        collectTypeVars(listType.elementType, typeVarMap);
        break;
      case FORALL_TYPE:
        final ForallType forallType = (ForallType) t;
        collectTypeVars(forallType.type, typeVarMap);
        break;
      case TY_VAR:
        final TypeVar typeVar = (TypeVar) t;
        typeVarMap.putIfAbsent(typeVar.ordinal, typeVarMap.size());
        break;
      default:
        // Other types don't contain type variables
        break;
    }
  }

  /** Returns a string representation of a type, e.g. "ts.fnType(INT, INT)". */
  private static String str(Type t, Map<Integer, Integer> typeVarMap) {
    switch (t.op()) {
      case FUNCTION_TYPE:
        final FnType fnType = (FnType) t;
        return format(
            "ts.fnType(%s, %s)",
            str(fnType.paramType, typeVarMap),
            str(fnType.resultType, typeVarMap));
      case TUPLE_TYPE:
        final TupleType tupleType = (TupleType) t;
        if (tupleType.argTypes.size() == 2) {
          return format(
              "ts.tupleType(%s, %s)",
              str(tupleType.argType(0), typeVarMap),
              str(tupleType.argType(1), typeVarMap));
        } else {
          // Handle tuples with more than 2 elements
          StringBuilder sb = new StringBuilder("ts.tupleType(");
          for (int i = 0; i < tupleType.argTypes.size(); i++) {
            if (i > 0) {
              sb.append(", ");
            }
            sb.append(str(tupleType.argType(i), typeVarMap));
          }
          sb.append(")");
          return sb.toString();
        }
      case ID:
        return ((PrimitiveType) t).name();
      case DATA_TYPE:
        DataType dataType = (DataType) t;
        switch (dataType.name) {
          case "option":
            return format(
                "ts.option(%s)", str(dataType.arguments.get(0), typeVarMap));
          case "order":
            return "ts.order()";
          case "list":
            return format(
                "ts.list(%s)", str(dataType.arguments.get(0), typeVarMap));
          default:
            // Generic data type with type arguments
            if (!dataType.arguments.isEmpty()) {
              StringBuilder sb = new StringBuilder();
              sb.append("ts.").append(dataType.name).append("(");
              for (int i = 0; i < dataType.arguments.size(); i++) {
                if (i > 0) {
                  sb.append(", ");
                }
                sb.append(str(dataType.arguments.get(i), typeVarMap));
              }
              sb.append(")");
              return sb.toString();
            }
            return format("ts.lookup(\"%s\")", dataType.name());
        }
      case LIST:
        final ListType listType = (ListType) t;
        return format("ts.list(%s)", str(listType.elementType, typeVarMap));
      case FORALL_TYPE:
        // Unwrap forall and just show the inner type with type variables
        final ForallType forallType = (ForallType) t;
        return str(forallType.type, typeVarMap);
      case TY_VAR:
        final TypeVar typeVar = (TypeVar) t;
        // Use the normalized ordinal from the map
        final int normalizedOrdinal = typeVarMap.get(typeVar.ordinal);
        return format("h.get(%d)", normalizedOrdinal);
      default:
        throw new UnsupportedOperationException(
            "type: "
                + t
                + " (op="
                + t.op()
                + ", class="
                + t.getClass().getName()
                + ")");
    }
  }

  /** Returns a string representation of a type, e.g. "ts.fnType(INT, INT)". */
  private static String str(
      Ast.Type t, java.util.Map<String, Integer> tyVarMap) {
    switch (t.op) {
      case FUNCTION_TYPE:
        final Ast.FunctionType fnType = (Ast.FunctionType) t;
        return format(
            "ts.fnType(%s, %s)",
            str(fnType.paramType, tyVarMap), str(fnType.resultType, tyVarMap));
      case TUPLE_TYPE:
        final Ast.TupleType tupleType = (Ast.TupleType) t;
        if (tupleType.types.size() == 2) {
          return format(
              "ts.tupleType(%s, %s)",
              str(tupleType.types.get(0), tyVarMap),
              str(tupleType.types.get(1), tyVarMap));
        } else {
          // Handle tuples with more than 2 elements
          StringBuilder sb = new StringBuilder("ts.tupleType(");
          for (int i = 0; i < tupleType.types.size(); i++) {
            if (i > 0) {
              sb.append(", ");
            }
            sb.append(str(tupleType.types.get(i), tyVarMap));
          }
          sb.append(")");
          return sb.toString();
        }
      case NAMED_TYPE:
        Ast.NamedType namedType = (Ast.NamedType) t;
        switch (namedType.name) {
          case "order":
            return "ts.order()";
          case "unit":
          case "int":
          case "bool":
          case "string":
          case "char":
            return namedType.name.toUpperCase(Locale.ROOT);
          default:
            // Generic named type with type arguments
            if (!namedType.types.isEmpty()) {
              StringBuilder sb = new StringBuilder();
              sb.append("ts.").append(namedType.name).append("(");
              for (int i = 0; i < namedType.types.size(); i++) {
                if (i > 0) {
                  sb.append(", ");
                }
                sb.append(str(namedType.types.get(i), tyVarMap));
              }
              sb.append(")");
              return sb.toString();
            }
            return format("ts.lookup(\"%s\")", namedType.name);
        }
      case TY_VAR:
        final Ast.TyVar tyVar = (Ast.TyVar) t;
        // Use the ordinal from the tyVarMap
        final int ordinal = tyVarMap.get(tyVar.name);
        return format("h.get(%d)", ordinal);
      default:
        throw new UnsupportedOperationException("type: " + t);
    }
  }
}

// End SignatureChecker.java
