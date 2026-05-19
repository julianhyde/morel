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
 * The BAG signature, a Morel extension.
 *)
signature BAG =
sig
  (* a multiset of values of type 'a, an equality type if 'a is. *)
  eqtype 'a bag

  (* is the empty bag. *)
  val nil : 'a bag

  (* returns true if the bag b is empty. *)
  val null : 'a bag -> bool [@@method]

  (* creates a new bag from l, whose length is length l and whose
   * elements are the same as those of l. Raises Size if maxLen < n. *)
  val fromList : 'a list -> 'a bag

  (* creates a new bag from b, whose length is length b and whose
   * elements are the same as those of b. Raises Size if maxLen < n. *)
  val toList : 'a bag -> 'a list [@@method]

  (* returns the number of elements in the bag b. *)
  val length : 'a bag -> int [@@method]

  (* returns the bag that is the concatenation of b1 and b2. *)
  val `@` : 'a bag * 'a bag -> 'a bag

  (* returns an arbitrary element of bag b. Raises Empty if b is nil. *)
  val hd : 'a bag -> 'a [@@method]

  (* returns all but one arbitrary element of bag b. Raises Empty if b
   * is nil. *)
  val tl : 'a bag -> 'a bag [@@method]

  (* returns NONE if the bag b is empty, and SOME (hd b, tl b)
   * otherwise (applying hd and tl simultaneously so that they
   * choose/remove the same arbitrary element). *)
  val getItem : 'a bag -> ('a * 'a bag) option [@@method]

  (* returns an arbitrary i elements of the bag b. Raises Subscript if
   * i length l. We have take(b, length b) = b. *)
  val `take` : 'a bag * int -> 'a bag [@@method]

  (* returns what is left after dropping an arbitrary i elements of
   * the bag b. Raises Subscript if i length l. *)
  val drop : 'a bag * int -> 'a bag [@@method]

  (* returns the bag that is the concatenation of all the bags in b. *)
  val concat : 'a bag list -> 'a bag

  (* applies f to the elements of b. *)
  val app : ('a -> unit) -> 'a bag -> unit

  (* applies f to each element of b, returning the bag of results.
   * This is equivalent to: *)
  val map : ('a -> 'b) -> 'a bag -> 'b bag

  (* applies f to each element of b, returning a bag of results, with
   * SOME stripped, where f was defined. f is not defined for an
   * element of b if f applied to the element returns NONE. The above
   * expression is equivalent to: *)
  val mapPartial : ('a -> 'b option) -> 'a bag -> 'b bag

  (* applies f to each element x of the bag b, in arbitrary order,
   * until f x evaluates to true. It returns SOME (x) if such an x
   * exists; otherwise it returns NONE. *)
  val find : ('a -> bool) -> 'a bag -> 'a option

  (* applies f to each element x of b and returns the bag of those x
   * for which f x evaluated to true. *)
  val filter : ('a -> bool) -> 'a bag -> 'a bag

  (* applies f to each element x of b, in arbitrary order, and returns
   * a pair (pos, neg) where pos is the bag of those x for which f x
   * evaluated to true, and neg is the bag of those for which f x
   * evaluated to false. *)
  val partition : ('a -> bool) -> 'a bag -> 'a bag * 'a bag

  (* returns f(xn, ... , f(x2, f(x1, init))...) (for some arbitrary
   * reordering of the elements xi) or init if the bag is empty. *)
  val fold : ('a * 'b -> 'b) -> 'b -> 'a bag -> 'b

  (* applies f to each element x of the bag b, in arbitrary order,
   * until f(x) evaluates to true; it returns true if such an x exists
   * and false otherwise. *)
  val `exists` : ('a -> bool) -> 'a bag -> bool

  (* applies f to each element x of the bag b, in arbitrary order,
   * until f(x) evaluates to false; it returns false if such an x
   * exists and true otherwise. It is equivalent to not(exists (not o
   * f) b)). *)
  val all : ('a -> bool) -> 'a bag -> bool

  (* returns a bag of length n equal to [f(0), f(1), ..., f(n-1)].
   * This is equivalent to the expression: *)
  val tabulate : int * (int -> 'a) -> 'a bag

  (* returns the ith element of the bag b, counting from 0. Raises
   * Subscript if i = length b. We have nth(b,0) = hd b, ignoring
   * exceptions. *)
  val nth : 'a bag * int -> 'a [@@method]
end

(*) End bag.sig
