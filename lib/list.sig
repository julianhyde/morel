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
 * The LIST signature, per the Standard ML Basis Library.
 *)
signature LIST =
sig
  datatype 'a list = nil | `::` of 'a * 'a list
  exception Empty

  (* Returns true if the list is empty. *)
  val null : 'a list -> bool [@@method] [@@prototype "null l"]

  (* Returns the number of elements in the list. *)
  val length : 'a list -> int [@@method] [@@prototype "length l"]

  (* Returns the concatenation of two lists. *)
  val @ : 'a list * 'a list -> 'a list [@@prototype "l1 @ l2"]

  (* Returns the first element; raises Empty if the list is nil. *)
  val hd : 'a list -> 'a [@@method] [@@prototype "hd l"]

  (* Returns all but the first element; raises Empty if the list is nil. *)
  val tl : 'a list -> 'a list [@@method] [@@prototype "tl l"]

  (* Returns the last element; raises Empty if the list is nil. *)
  val last : 'a list -> 'a [@@method] [@@prototype "last l"]

  (* Returns NONE if the list is empty, SOME(hd, tl) otherwise. *)
  val getItem : 'a list -> ('a * 'a list) option [@@method] [@@prototype "getItem l"]

  (* Returns the i-th element (0-indexed); raises Subscript if out of bounds. *)
  val nth : 'a list * int -> 'a [@@method] [@@prototype "nth (l, i)"]

  (* Returns the first i elements; raises Subscript if i < 0 or
   * i > length. *)
  val `take` : 'a list * int -> 'a list [@@method] [@@prototype "take (l, i)"]

  (* Returns the list after dropping the first i elements; raises
   * Subscript if out of bounds. *)
  val drop : 'a list * int -> 'a list [@@method] [@@prototype "drop (l, i)"]

  (* Returns the list with elements in reverse order. *)
  val rev : 'a list -> 'a list [@@method] [@@prototype "rev l"]

  (* Returns the concatenation of all lists in order. *)
  val concat : 'a list list -> 'a list [@@prototype "concat l"]

  (* Returns (rev l1) @ l2. *)
  val revAppend : 'a list * 'a list -> 'a list [@@method] [@@prototype "revAppend (l1, l2)"]

  (* Applies a function to each element from left to right for side effects. *)
  val app : ('a -> unit) -> 'a list -> unit [@@prototype "app f l"]

  (* Applies a function to each element, returning the list of results. *)
  val map : ('a -> 'b) -> 'a list -> 'b list [@@prototype "map f l"]

  (* Applies a partial function to each element; filters out NONE results
   * and unwraps SOME. *)
  val mapPartial : ('a -> 'b option) -> 'a list -> 'b list [@@prototype "mapPartial f l"]

  (* Returns SOME(x) for the first element where f(x) is true, or NONE. *)
  val find : ('a -> bool) -> 'a list -> 'a option [@@prototype "find f l"]

  (* Returns the list of elements for which the predicate returns true. *)
  val filter : ('a -> bool) -> 'a list -> 'a list [@@prototype "filter f l"]

  (* Partitions the list into elements satisfying and not satisfying the
   * predicate. *)
  val partition : ('a -> bool)
                    -> 'a list -> 'a list * 'a list [@@prototype "partition f l"]

  (* Left-associative fold; reduces the list from left to right with an
   * accumulator. *)
  val foldl : ('a * 'b -> 'b) -> 'b -> 'a list -> 'b [@@prototype "foldl f init [x1, x2, ..., xn]"]

  (* Right-associative fold; reduces the list from right to left with an
   * accumulator. *)
  val foldr : ('a * 'b -> 'b) -> 'b -> 'a list -> 'b [@@prototype "foldr f init [x1, x2, ..., xn]"]

  (* Returns true if the predicate is true for at least one element. *)
  val `exists` : ('a -> bool) -> 'a list -> bool [@@prototype "exists f l"]

  (* Returns true if the predicate is true for all elements. *)
  val all : ('a -> bool) -> 'a list -> bool [@@prototype "all f l"]

  (* Returns a list of length n equal to [f(0), f(1), ..., f(n-1)]. *)
  val tabulate : int * (int -> 'a) -> 'a list [@@prototype "tabulate (n, f)"]

  (* Performs lexicographic comparison of two lists using the given ordering. *)
  val collate : ('a * 'a -> `order`)
                  -> 'a list * 'a list -> `order` [@@prototype "collate f (l1, l2)"]

  (* Morel extensions *)
  (* Returns the set difference of lists. *)
  val `except` : 'a list list -> 'a list [@@specified "morel"] [@@prototype "except l"]

  (* Returns the set intersection of lists. *)
  val `intersect` : 'a list list -> 'a list [@@specified "morel"] [@@prototype "intersect l"]

  (* Maps over a list with index; applies f to (index, element) pairs. *)
  val mapi : (int * 'a -> 'b) -> 'a list -> 'b list [@@prototype "mapi f l"]
end

(*) End list.sig
