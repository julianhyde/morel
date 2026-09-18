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
 * Core structure: the compiler's tree, as a Morel value.
 *)

(* Core structure signature *)
signature CORE =
sig
  (**
   * is a type as the compiler holds it. A rule cannot write one, only pass
   * one along: `typeOf` gives an expression's, and the constructors of
   * `exp` derive the type of what they build.
   *)
  eqtype ty

  (**
   * is a pattern. A node binds its element to a pattern -- `ID_PAT` named
   * `$0`, `$1` or `$ordinal` -- and a function, a `let` and a `case`
   * bind theirs. A pattern the view does not render is `OPAQUE_PAT`.
   *)
  datatype pat =
      ID_PAT of {i: int, name: string, ty: ty}
    | WILDCARD of ty
    | TUPLE_PAT of pat list
    | RECORD_PAT of (string * pat) list
    | CON0_PAT of string * ty
    | CON_PAT of string * pat * ty
    | OPAQUE_PAT

  (** is the kind of a join. *)
  datatype join_kind = INNER | LEFT | RIGHT | FULL

  (**
   * is an expression: a node of the relational tree, or what its expressions
   * are made of. A value of this type is a view of the compiler's own tree:
   * matching a constructor renders one level of it, and applying a
   * constructor builds a node whose type is derived from its parts, so a
   * rule cannot build an ill-typed node. What the view does not render is
   * `OPAQUE`, which a rule can pass along but not look into.
   *)
  datatype exp =
      FILTER of {condition: exp, input: exp, ordinalPat: pat option, row: pat}
    | PROJECT of {exp: exp, input: exp, ordinalPat: pat option, row: pat}
    | JOIN of {condition: exp, kind: join_kind, leftInput: exp,
               leftRow: pat, ordinalPat: pat option, rightInput: exp,
               rightRow: pat}
    | GROUP of {aggregates: (string * exp * exp option * ty) list,
                input: exp, keys: (string * exp) list,
                ordinalPat: pat option, row: pat}
    | SORT of {input: exp, key: exp, ordinalPat: pat option, row: pat}
    | UNORDER of exp
    | BOUNDARY of {engine: string, input: exp}
    | SKIP of {count: exp, input: exp}
    | TAKE of {count: exp, input: exp}
    | IF_EMPTY of {input: exp, otherwise: exp}
    | UNION of {inputs: exp list, unique: bool}
    | INTERSECT of {inputs: exp list, unique: bool}
    | EXCEPT of {inputs: exp list, unique: bool}
    | ID of pat
    | LITERAL of variant
    | BUILTIN of string * ty
    | SELECTOR of string * ty
    | APPLY of exp * exp
    | TUPLE of exp list
    | RECORD_EXP of (string * exp) list
    | FN of pat * exp
    | LET of {body: exp, pat: pat, value: exp}
    | CASE of exp * (pat * exp) list
    | OPAQUE

  (**
   * prints an expression as a plan: a tree one node per line, with the
   * collection type of each node, as `Sys.planOf` prints a query; anything
   * else as an expression.
   *)
  val print : exp -> string [@@method] [@@prototype "print e"]

  (** returns the type of an expression. *)
  val typeOf : exp -> ty [@@method] [@@prototype "typeOf e"]

  (** prints a type. *)
  val printType : ty -> string [@@prototype "printType t"]
end
[@@description "The compiler's tree, as a value."]
[@@specified "morel"]

(*) End core.sig
