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
 * The INTEGER signature, per the Standard ML Basis Library.
 *)
signature INTEGER =
sig
  eqtype int

  (* Converts an integer to LargeInt representation. *)
  val toLarge   : int -> (*LargeInt.*)int [@@prototype "toLarge i"]

  (* Converts from LargeInt representation; may raise Overflow. *)
  val fromLarge : (*LargeInt.*)int -> int [@@prototype "fromLarge i"]

  (* Converts an integer to default Int representation. *)
  val toInt   : int -> (*Int.*)int [@@prototype "toInt i"]

  (* Converts from default Int representation. *)
  val fromInt : (*Int.*)int -> int [@@prototype "fromInt i"]

  (* The number of significant bits in this integer type; NONE for
   * infinite precision. *)
  val precision : (*Int.*)int option [@@prototype "precision"]

  (* The smallest representable integer; NONE for infinite precision. *)
  val minInt : int option [@@prototype "minInt"]

  (* The largest representable integer; NONE for infinite precision. *)
  val maxInt : int option [@@prototype "maxInt"]

(*
  val + : int * int -> int [@@prototype "i + j"]
  val - : int * int -> int [@@prototype "i - j"]
  val * : int * int -> int
*) [@@prototype "i * j"]
  (* Integer division truncated toward negative infinity; raises Div on
   * division by zero. *)
  val div : int * int -> int [@@prototype "i div j"]

  (* Modulus operation; result has same sign as divisor; raises Div on
   * division by zero. *)
  val mod : int * int -> int [@@prototype "i mod j"]

  (* Integer division truncated toward zero; raises Div on division by
   * zero. *)
  val quot : int * int -> int [@@method] [@@prototype "quot (i, j)"]

  (* Remainder operation; result has same sign as dividend; raises Div
   * on division by zero. *)
  val rem : int * int -> int [@@method] [@@prototype "rem (i, j)"]

  (* Returns the ordering of two integers. *)
  val compare : int * int -> `order` [@@method] [@@prototype "compare (i, j)"]
(*
  val <  : int * int -> bool [@@prototype "i < j"]
  val <= : int * int -> bool [@@prototype "i <= j"]
  val >  : int * int -> bool [@@prototype "i > j"]
  val >= : int * int -> bool [@@prototype "i >= j"]

  val ~ : int -> int
*) [@@prototype "~ i"]
  (* Returns the absolute value; raises Overflow on minInt for bounded types. *)
  val abs : int -> int [@@method] [@@prototype "abs i"]

  (* Returns the smaller of two integers. *)
  val min : int * int -> int [@@method] [@@prototype "min (i, j)"]

  (* Returns the larger of two integers. *)
  val max : int * int -> int [@@method] [@@prototype "max (i, j)"]

  (* Returns -1, 0, or 1 when the argument is negative, zero, or positive. *)
  val sign : int -> (*Int.*)int [@@method] [@@prototype "sign i"]

  (* Returns true if both arguments have the same sign. *)
  val sameSign : int * int -> bool [@@method] [@@prototype "sameSign (i, j)"]

(*
  val fmt      : StringCvt.radix -> int -> string
*) [@@prototype "fmt radix i"]
  (* Converts an integer to its decimal string representation. *)
  val toString : int -> string [@@method] [@@prototype "toString i"]
(*
  val scan       : StringCvt.radix
                     -> (char, 'a) StringCvt.reader
                       -> (int, 'a) StringCvt.reader
*) [@@prototype "scan radix getc strm"]
  (* Parses an integer from a string; returns SOME i or NONE. *)
  val fromString : string -> int option [@@prototype "fromString s"]
end

(*) End integer.sig
