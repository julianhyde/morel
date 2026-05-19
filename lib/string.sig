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
 * The STRING signature, per the Standard ML Basis Library.
 *)
signature STRING =
sig
  eqtype string
  eqtype char

  (* The longest allowed size of a string. *)
  val maxSize : int

  (* Returns the character count in a string. *)
  val size : string -> int [@@method]

  (* Returns the i(th) character of s, counting from zero.
   * Raises Subscript if the index is out of bounds. *)
  val sub : string * int -> char [@@method]

  (* Returns substrings from a starting index, optionally with a
   * specified length. Raises Subscript for invalid indices or lengths. *)
  val extract : string * int * int option -> string [@@method]

  (* Returns the substring of size j starting at index i.
   * Equivalent to extract(s, i, SOME j). *)
  val substring : string * int * int -> string [@@method]

  (* Concatenation of the strings s and t.
   * Raises Size if the result exceeds maxSize. *)
  val `^` : string * string -> string

  (* Concatenates all strings in a list.
   * Raises Size if the total exceeds maxSize. *)
  val concat : string list -> string

  (* Returns the concatenation of the strings in the list l using
   * the string s as a separator. *)
  val concatWith : string -> string list -> string

  (* The string of size one containing the character c. *)
  val str : char -> string

  (* Generates the string containing the characters in the list l.
   * Raises Size for excessively long results. *)
  val implode : char list -> string

  (* Returns the list of characters in the string s. *)
  val explode : string -> char list [@@method]

  (* Applies f to each element of s from left to right, returning
   * the resulting string. *)
  val map : (char -> char) -> string -> string

  (* Returns the string generated from s by mapping each character
   * in s by f. *)
  val translate : (char -> string) -> string -> string

  (* Returns non-empty maximal substrings separated by delimiter
   * characters. *)
  val tokens : (char -> bool) -> string -> string list

  (* Returns maximal substrings (possibly empty) separated by exactly
   * one delimiter character. *)
  val fields : (char -> bool) -> string -> string list

  (* Tests whether the first string begins the second. *)
  val isPrefix : string -> string -> bool

  (* Tests whether the first string occurs within the second. *)
  val isSubstring : string -> string -> bool

  (* Tests whether the first string ends the second. *)
  val isSuffix : string -> string -> bool

  (* Does a lexicographic comparison of the two strings returning
   * an order value. *)
  val compare : string * string -> `order` [@@method]

  (* Performs lexicographic comparison of the two strings using the
   * given ordering f on characters. *)
  val collate : (char * char -> `order`) -> string * string -> `order`

  (* Compare strings lexicographically using the underlying character
   * ordering. *)
  val `<`  : string * string -> bool
  val `<=` : string * string -> bool
  val `>`  : string * string -> bool
  val `>=` : string * string -> bool

  (* Returns a string corresponding to s, with non-printable characters
   * replaced by SML escape sequences. *)
(*
  val toString : string -> string
*)

  (* Scans a character stream converting SML escape sequences into
   * appropriate characters. *)
(*
  val scan : (char, 'a) StringCvt.reader -> (string, 'a) StringCvt.reader
*)

  (* Scans the character source as a sequence of printable characters,
   * converting SML escape sequences and returns an option type. *)
(*
  val fromString : string -> string option
*)

  (* Returns a string corresponding to s, with non-printable characters
   * replaced by C escape sequences. *)
(*
  val toCString : string -> string
*)

  (* Scans a string as C language code, converting C escape sequences
   * into characters. *)
(*
  val fromCString : string -> string option
*)
end

(*) End string.sig
