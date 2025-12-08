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
 * Variant structure for embedded language interoperability.
 *
 * The Variant structure provides a universal value representation
 * mechanism to facilitate returning results from embedded languages
 * (such as Soufflé Datalog) back to the Morel system.
 *)

(* Universal value representation datatype *)
datatype variant =
    UNIT
  | BOOL of bool
  | INT of int
  | REAL of real
  | CHAR of char
  | STRING of string
  | LIST of variant list
  | BAG of variant list
  | VECTOR of variant list
  | VARIANT_NONE
  | VARIANT_SOME of variant
  | RECORD of (string * variant) list
  | CONSTANT of string
  | CONSTRUCT of string * variant

(* Variant structure signature *)
signature VARIANT =
sig
  (* Re-export the variant datatype *)
  datatype variant =
      UNIT
    | BOOL of bool
    | INT of int
    | REAL of real
    | CHAR of char
    | STRING of string
    | LIST of variant list
    | BAG of variant list
    | VECTOR of variant list
    | VARIANT_NONE
    | VARIANT_SOME of variant
    | RECORD of (string * variant) list
    | CONSTANT of string
    | CONSTRUCT of string * variant

  (*
   * parse : string -> variant
   *
   * Parses a string representation into a variant.
   *
   * The string should be in the format produced by the print function.
   * This enables round-tripping: parse (print v) = v for all variants v.
   *
   * Examples:
   *   parse "()" = UNIT
   *   parse "true" = BOOL true
   *   parse "42" = INT 42
   *   parse "\"hello\"" = STRING "hello"
   *   parse "[1, 2, 3]" = LIST [INT 1, INT 2, INT 3]
   *   parse "{x = 1, y = 2}" = RECORD [("x", INT 1), ("y", INT 2)]
   *)
  val parse : string -> variant

  (*
   * print : variant -> string
   *
   * Converts a variant to its compact string representation.
   *
   * The output is a valid Standard ML expression that can be parsed
   * back into the same variant using the parse function.
   *
   * Examples:
   *   print UNIT = "()"
   *   print (BOOL true) = "true"
   *   print (INT 42) = "42"
   *   print (STRING "hello") = "\"hello\""
   *   print (LIST [INT 1, INT 2, INT 3]) = "[1, 2, 3]"
   *   print (RECORD [("x", INT 1), ("y", INT 2)]) = "{x = 1, y = 2}"
   *)
  val print : variant -> string
end

(*
 * Usage notes:
 *
 * 1. Round-trip property:
 *    For all variants v, parse (print v) should equal v.
 *    This property is essential for reliable data exchange.
 *
 * 2. Nullary constructors:
 *    Nullary datatype constructors are represented as CONSTANT name.
 *    For example, the constructor NIL would be CONSTANT "NIL".
 *
 * 3. Type information:
 *    Variants do not carry explicit type information. The type system
 *    infers types separately. This design is more efficient for
 *    homogeneous collections (e.g., lists where all elements have
 *    the same type).
 *
 * 4. String escaping:
 *    String variants use Standard ML escape sequences. The parse and
 *    print functions handle escaping automatically to ensure the
 *    round-trip property holds for all valid strings.
 *
 * 5. Embedded language integration:
 *    Embedded languages (like Soufflé Datalog) should:
 *    a. Convert their results to the variant representation
 *    b. Use print to serialize variants as strings
 *    c. Return these strings to Morel
 *    d. Morel uses parse to reconstruct the variants
 *    e. The variants can then be used in further Morel computations
 *
 * 6. BAG vs LIST:
 *    Both BAG and LIST are represented internally as lists, but they
 *    have different semantics:
 *    - LIST: ordered collection (list type in Standard ML)
 *    - BAG: unordered collection with duplicates (multiset/bag type)
 *
 * 7. VECTOR:
 *    Vectors are represented as lists but correspond to Standard ML
 *    vector types (immutable arrays with O(1) random access).
 *)

(*) End variant.sig
