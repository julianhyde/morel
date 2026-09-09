(*
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
 *
 * The Morel functions and operators that the Spark translator
 * pushes down: one record per built-in. See spec.md, section 3. The
 * file is a Morel expression, a list of records, so that every
 * implementation of Morel can read it.
 *
 *   morel       The built-in: "op +" for a top-level operator, or
 *               "String.size" for a member of a structure.
 *   kind        "direct": Spark has a function with the same
 *               semantics, named by "spark";
 *               "rewritten": "spark" is a template over the
 *               arguments $0, $1, ...;
 *               "aggregate", "subquery": the translator handles the
 *               construct structurally; "spark" names what it emits;
 *               "unsupported": prepare rejects the expression.
 *   spark       As above.
 *   divergence  How the translation differs from local evaluation,
 *               or empty. A triple test documents each divergence.
 *)
[
  (* Comparison and logic *)
  {morel = "op =", kind = "direct", spark = "==",
   divergence = "on real, Spark has NaN = NaN"},
  {morel = "op <>", kind = "direct", spark = "!=",
   divergence = "on real, Spark has NaN = NaN"},
  {morel = "op <", kind = "direct", spark = "<",
   divergence = "strings: Spark compares UTF-8 bytes, Morel UTF-16 units; reals: Spark orders NaN above all"},
  {morel = "op <=", kind = "direct", spark = "<=",
   divergence = "as for <"},
  {morel = "op >", kind = "direct", spark = ">",
   divergence = "as for <"},
  {morel = "op >=", kind = "direct", spark = ">=",
   divergence = "as for <"},
  {morel = "Bool.andalso", kind = "direct", spark = "and", divergence = ""},
  {morel = "Bool.orelse", kind = "direct", spark = "or", divergence = ""},
  {morel = "Bool.not", kind = "direct", spark = "not", divergence = ""},
  {morel = "Int.min", kind = "direct", spark = "least", divergence = ""},
  {morel = "Int.max", kind = "direct", spark = "greatest", divergence = ""},
  {morel = "Real.min", kind = "direct", spark = "least",
   divergence = "NaN: Spark ignores it, SML returns it"},
  {morel = "Real.max", kind = "direct", spark = "greatest",
   divergence = "NaN: Spark ignores it, SML returns it"},

  (* Arithmetic *)
  {morel = "op +", kind = "direct", spark = "+", divergence = ""},
  {morel = "op -", kind = "direct", spark = "-", divergence = ""},
  {morel = "op *", kind = "direct", spark = "*", divergence = ""},
  {morel = "op ~", kind = "direct", spark = "negative", divergence = ""},
  {morel = "Real./", kind = "direct", spark = "/", divergence = ""},
  {morel = "op div", kind = "rewritten",
   spark = "(($0 - (($0 % $1 + $1) % $1)) div $1)",
   divergence = "SML rounds toward negative infinity, and Spark's div truncates, hence the template"},
  {morel = "op mod", kind = "rewritten", spark = "(($0 % $1 + $1) % $1)",
   divergence = "the SML result has the divisor's sign, Spark's % the dividend's, hence the template"},
  {morel = "abs", kind = "direct", spark = "abs", divergence = ""},
  {morel = "Real.floor", kind = "rewritten", spark = "cast(floor($0) as int)",
   divergence = ""},
  {morel = "Real.ceil", kind = "rewritten", spark = "cast(ceil($0) as int)",
   divergence = ""},
  {morel = "Real.trunc", kind = "rewritten", spark = "cast($0 as int)",
   divergence = ""},
  {morel = "Real.round", kind = "rewritten", spark = "cast(round($0) as int)",
   divergence = "SML rounds half to even, Spark half away from zero"},
  {morel = "Real.fromInt", kind = "rewritten", spark = "cast($0 as double)",
   divergence = ""},
  {morel = "Int.toString", kind = "rewritten", spark = "cast($0 as string)",
   divergence = "Morel writes a negative number with ~"},
  {morel = "Real.toString", kind = "unsupported", spark = "",
   divergence = "formatting differs"},
  {morel = "Math.sqrt", kind = "direct", spark = "sqrt", divergence = ""},
  {morel = "Math.exp", kind = "direct", spark = "exp", divergence = ""},
  {morel = "Math.ln", kind = "direct", spark = "ln", divergence = ""},
  {morel = "Math.pow", kind = "direct", spark = "power", divergence = ""},
  {morel = "Math.sin", kind = "direct", spark = "sin", divergence = ""},
  {morel = "Math.cos", kind = "direct", spark = "cos", divergence = ""},
  {morel = "Math.tan", kind = "direct", spark = "tan", divergence = ""},
  {morel = "Math.asin", kind = "direct", spark = "asin", divergence = ""},
  {morel = "Math.acos", kind = "direct", spark = "acos", divergence = ""},
  {morel = "Math.atan", kind = "direct", spark = "atan", divergence = ""},

  (* Strings *)
  {morel = "String.^", kind = "direct", spark = "concat", divergence = ""},
  {morel = "String.concat", kind = "rewritten", spark = "array_join($0, '')",
   divergence = ""},
  {morel = "String.size", kind = "direct", spark = "length", divergence = ""},
  {morel = "String.substring", kind = "rewritten",
   spark = "substring($0, $1 + 1, $2)",
   divergence = "out of range raises Subscript in Morel, is clamped in Spark"},
  {morel = "String.sub", kind = "rewritten", spark = "substring($0, $1 + 1, 1)",
   divergence = "out of range raises Subscript in Morel, is empty in Spark"},
  {morel = "String.isPrefix", kind = "rewritten", spark = "startswith($1, $0)",
   divergence = ""},
  {morel = "String.isSuffix", kind = "rewritten", spark = "endswith($1, $0)",
   divergence = ""},
  {morel = "String.isSubstring", kind = "rewritten", spark = "contains($1, $0)",
   divergence = ""},
  {morel = "String.compare", kind = "unsupported", spark = "",
   divergence = "returns order, which has no Spark type in phase 1"},
  {morel = "String.map", kind = "unsupported", spark = "",
   divergence = "function argument"},
  {morel = "String.translate", kind = "unsupported", spark = "",
   divergence = "function argument"},
  {morel = "String.tokens", kind = "unsupported", spark = "",
   divergence = "function argument"},
  {morel = "String.fields", kind = "unsupported", spark = "",
   divergence = "function argument"},
  {morel = "String.explode", kind = "unsupported", spark = "",
   divergence = "char list"},
  {morel = "String.implode", kind = "unsupported", spark = "",
   divergence = "char list"},

  (* Collections and queries; the translator handles the structure *)
  {morel = "Relational.count", kind = "aggregate", spark = "count",
   divergence = ""},
  {morel = "Relational.sum", kind = "aggregate", spark = "sum", divergence = ""},
  {morel = "Relational.min", kind = "aggregate", spark = "min", divergence = ""},
  {morel = "Relational.max", kind = "aggregate", spark = "max", divergence = ""},
  {morel = "op elem", kind = "subquery", spark = "in", divergence = ""},
  {morel = "op notelem", kind = "subquery", spark = "not in", divergence = ""},
  {morel = "Relational.nonEmpty", kind = "subquery", spark = "exists",
   divergence = ""},
  {morel = "Relational.empty", kind = "subquery", spark = "not exists",
   divergence = ""},
  {morel = "List.null", kind = "subquery", spark = "not exists",
   divergence = ""},
  {morel = "Relational.only", kind = "subquery", spark = "scalar",
   divergence = ""},
  {morel = "List.length", kind = "direct", spark = "size", divergence = ""},
  {morel = "List.nth", kind = "rewritten", spark = "element_at($0, $1 + 1)",
   divergence = ""},
  {morel = "List.hd", kind = "rewritten", spark = "element_at($0, 1)",
   divergence = ""},
  {morel = "List.@", kind = "direct", spark = "concat", divergence = ""},
  {morel = "List.rev", kind = "direct", spark = "reverse", divergence = ""},
  {morel = "Option.isSome", kind = "direct", spark = "isnotnull",
   divergence = ""},
  {morel = "Option.valOf", kind = "rewritten", spark = "assert_not_null($0)",
   divergence = ""},
  {morel = "Option.getOpt", kind = "rewritten", spark = "coalesce($0, $1)",
   divergence = ""},
  {morel = "Relational.iterate", kind = "unsupported", spark = "",
   divergence = "recursive"}
]

(*) End functions.sml
