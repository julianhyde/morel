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
  val maxSize : int [@@prototype "maxSize"]

  (* Returns the character count in a string. *)
  val size : string -> int [@@method] [@@prototype "size s"]

  (* Returns the i(th) character of s, counting from zero.
   * Raises Subscript if the index is out of bounds. *)
  val sub : string * int -> char [@@method] [@@prototype "sub (s, i)"]

  (* Returns substrings from a starting index, optionally with a
   * specified length. Raises Subscript for invalid indices or lengths. *)
  val extract : string * int * int option -> string [@@method] [@@prototype "extract (s, i, NONE)"]

  (* Returns the substring of size j starting at index i.
   * Equivalent to extract(s, i, SOME j). *)
  val substring : string * int * int -> string [@@method] [@@prototype "substring (s, i, j)"]

  (* Concatenation of the strings s and t.
   * Raises Size if the result exceeds maxSize. *)
  val `^` : string * string -> string [@@prototype "s ^ t"]

  (* Concatenates all strings in a list.
   * Raises Size if the total exceeds maxSize. *)
  val concat : string list -> string [@@prototype "concat l"]

  (* Returns the concatenation of the strings in the list l using
   * the string s as a separator. *)
  val concatWith : string -> string list -> string [@@prototype "concatWith s l"]

  (* The string of size one containing the character c. *)
  val str : char -> string [@@prototype "str c"]

  (* Generates the string containing the characters in the list l.
   * Raises Size for excessively long results. *)
  val implode : char list -> string [@@prototype "implode l"]

  (* Returns the list of characters in the string s. *)
  val explode : string -> char list [@@method] [@@prototype "explode s"]

  (* Applies f to each element of s from left to right, returning
   * the resulting string. *)
  val map : (char -> char) -> string -> string [@@prototype "map f s"]

  (* Returns the string generated from s by mapping each character
   * in s by f. *)
  val translate : (char -> string) -> string -> string [@@prototype "translate f s"]

  (* Returns non-empty maximal substrings separated by delimiter
   * characters. *)
  val tokens : (char -> bool) -> string -> string list [@@prototype "tokens f s"]

  (* Returns maximal substrings (possibly empty) separated by exactly
   * one delimiter character. *)
  val fields : (char -> bool) -> string -> string list [@@prototype "fields f s"]

  (* Tests whether the first string begins the second. *)
  val isPrefix : string -> string -> bool [@@prototype "isPrefix s1 s2"]

  (* Tests whether the first string occurs within the second. *)
  val isSubstring : string -> string -> bool [@@prototype "isSubstring s1 s2"]

  (* Tests whether the first string ends the second. *)
  val isSuffix : string -> string -> bool [@@prototype "isSuffix s1 s2"]

  (* Does a lexicographic comparison of the two strings returning
   * an order value. *)
  val compare : string * string -> `order` [@@method] [@@prototype "compare (s, t)"]

  (* Performs lexicographic comparison of the two strings using the
   * given ordering f on characters. *)
  val collate : (char * char -> `order`) -> string * string -> `order` [@@prototype "collate (f, (s, t))"]

  (* Compare strings lexicographically using the underlying character
   * ordering. *)
  val `<`  : string * string -> bool [@@prototype "s < t"]
  val `<=` : string * string -> bool [@@prototype "s <= t"]
  val `>`  : string * string -> bool [@@prototype "s > t"]
  val `>=` : string * string -> bool [@@prototype "s >= t"]

  (* Returns a string corresponding to s, with non-printable characters
   * replaced by SML escape sequences. *)
(*
  val toString : string -> string
*) [@@prototype "toString s"]

  (* Scans a character stream converting SML escape sequences into
   * appropriate characters. *)
(*
  val scan : (char, 'a) StringCvt.reader -> (string, 'a) StringCvt.reader
*) [@@prototype "scan getc strm"]

  (* Scans the character source as a sequence of printable characters,
   * converting SML escape sequences and returns an option type. *)
(*
  val fromString : string -> string option
*) [@@prototype "fromString s"]

  (* Returns a string corresponding to s, with non-printable characters
   * replaced by C escape sequences. *)
(*
  val toCString : string -> string
*) [@@prototype "toCString s"]

  (* Scans a string as C language code, converting C escape sequences
   * into characters. *)
(*
  val fromCString : string -> string option
*) [@@prototype "fromCString s"]
end

(*) End string.sig
