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
 * The Table signature, measures and context-dependent calculations.
 *)
(**
 * The `Table` structure provides measures and the dimensional contexts in
 * which they are evaluated.
 *
 * A `measure` is an expression evaluated in a `context`. Typically a measure
 * belongs to a `table` and is based on an aggregate function, and the context
 * is the set of constraints that determine which of the table's rows are
 * included. Because a measure is an abstraction, you can use it without access
 * to, or even knowledge of, the table it aggregates over.
 *
 * A measure has type `('p, 'e, 'a, 'r) measure`, whose parameters follow the
 * data flow: `'p` is the table's parameter type, `'e` is the element (row)
 * type, `'a` is the argument supplied when the measure is evaluated (usually
 * `unit`), and `'r` is the result type.
 *)
signature TABLE =
sig

  (** is the type of a measure: an expression of type `'r` evaluated in a
      `('p, 'e) cx` given an argument of type `'a`. *)
  type ('p, 'e, 'a, 'r) measure

  (** is the type of a context: the constraints, and the parameter, under
      which a measure is evaluated. *)
  type ('p, 'e) cx

  (** is the type of a table: a collection of elements of type `'e` together
      with a parameter of type `'p`. `'o` is the orderedness tag (`ordered`
      for a list-backed table, `unordered` for a bag-backed one). *)
  type ('p, 'e, 'o) table

  (** is a measure whose value is `f c` when evaluated in context `c`. *)
  val measure : (('p, 'e) cx -> 'r) -> ('p, 'e, unit, 'r) measure
      [@@prototype "measure f"]

  (** is a measure whose value is `f (a, c)` when evaluated with argument `a`
      in context `c`. *)
  val measure_fn : ('a * ('p, 'e) cx -> 'r) -> ('p, 'e, 'a, 'r) measure
      [@@prototype "measure_fn f"]

  (** evaluates measure `m` with argument `a` in context `c`. *)
  val evaluate : ('p, 'e, 'a, 'r) measure * 'a * ('p, 'e) cx -> 'r
      [@@prototype "evaluate (m, a, c)"] [@@method]

  (** evaluates measure `m` with argument `a` in the current context. *)
  val eval : ('p, 'e, 'a, 'r) measure * 'a -> 'r
      [@@prototype "eval (m, a)"] [@@method]

  (** is measure `m` with an additional filter `p`, labeled `label`, on the
      context. *)
  val restrict :
      ('p, 'e, 'a, 'r) measure * string * ('e -> bool) ->
        ('p, 'e, 'a, 'r) measure
      [@@prototype "restrict (m, label, p)"] [@@method]

  (** is measure `m` with an additional anonymous filter `p` on the context. *)
  val restrict_anon :
      ('p, 'e, 'a, 'r) measure * ('e -> bool) -> ('p, 'e, 'a, 'r) measure
      [@@prototype "restrict_anon (m, p)"] [@@method]

  (** is measure `m` with the filters labeled `label` removed from the
      context. *)
  val relax : ('p, 'e, 'a, 'r) measure * string -> ('p, 'e, 'a, 'r) measure
      [@@prototype "relax (m, label)"] [@@method]

  (** is measure `m` evaluated in a context where the dimension `proj` is
      overridden to `value`. *)
  val override :
      ('p, 'e, 'a, 'r) measure * ('e -> 'b) * 'b -> ('p, 'e, 'a, 'r) measure
      [@@prototype "override (m, proj, value)"] [@@method]

  (** is whether context `c` admits element `e`. *)
  val test : ('p, 'e) cx * 'e -> bool
      [@@prototype "test (c, e)"] [@@method]

  (** is the current parameter of context `c`. *)
  val paramOf : ('p, 'e) cx -> 'p
      [@@prototype "paramOf c"] [@@method]

  (** is a canonical string describing the constraints of context `c`. *)
  val toString : ('p, 'e) cx -> string
      [@@prototype "toString c"] [@@method]

  (** is an unordered (bag-backed) table with the given `elements` and
      `param`. *)
  val table : 'e bag * 'p -> ('p, 'e, unordered) table
      [@@prototype "table (elements, param)"]

  (** is an ordered (list-backed) table with the given `elements` and
      `param`. *)
  val orderedTable : 'e list * 'p -> ('p, 'e, ordered) table
      [@@prototype "orderedTable (elements, param)"]

  (** is the bag of elements of unordered table `t`. *)
  val `elements` : ('p, 'e, unordered) table -> 'e bag
      [@@prototype "elements t"] [@@method]

  (** is the parameter of table `t`. *)
  val param : ('p, 'e, 'o) table -> 'p
      [@@prototype "param t"] [@@method]

end
[@@description "Measures and context-dependent calculations."]
[@@specified "morel"]

(*) End table.sig
