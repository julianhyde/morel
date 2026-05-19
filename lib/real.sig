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
 * The REAL signature, per the Standard ML Basis Library.
 *)
signature REAL =
sig
  type real
(*
  structure Math : MATH where type real = real
*)

  (* The base of the representation, e.g., 2 or 10 for IEEE floating point. *)
  val radix : int [@@prototype "radix"]

  (* The number of digits, each between 0 and radix-1, in the mantissa.
   * Note that the precision includes the implicit (or hidden) bit used
   * in the IEEE representation. *)
  val precision : int [@@prototype "precision"]

  (* The maximum finite number, the minimum non-zero positive number,
   * and the minimum non-zero normalized number, respectively. *)
  val maxFinite : real [@@prototype "maxFinite"]
  val minPos : real [@@prototype "minPos"]
  val minNormalPos : real [@@prototype "minNormalPos"]

  (* Positive and negative infinity values. *)
  val posInf : real [@@prototype "posInf"]
  val negInf : real [@@prototype "negInf"]

  (* These compute sum and difference. Special cases:
   * - finite ± infinity = infinity with correct sign
   * - infinity + infinity = infinity
   * - other infinity combinations yield NaN *)
(*
  val `+` : real * real -> real [@@prototype "r1 + r2"]
  val `-` : real * real -> real
*) [@@prototype "r1 - r2"]

  (* Product operation. Zero × infinity = NaN; otherwise infinity
   * results have correct sign. *)
(*
  val `*` : real * real -> real
*) [@@prototype "r1 * r2"]

  (* Division where:
   * - 0/0 = NaN
   * - ±infinity/±infinity = NaN
   * - finite non-zero ÷ zero = infinity with correct sign
   * - infinity ÷ finite = infinity with correct sign *)
  val `/` : real * real -> real [@@prototype "r1 / r2"]

  (* Returns remainder x - n×y where n = trunc(x/y). Same sign as x,
   * absolute value less than |y|. Returns NaN if x is infinity or y is
   * zero; returns x if y is infinity. *)
  val rem : real * real -> real [@@method] [@@prototype "rem (x, y)"]

  (* Return a×b+c and a×b−c respectively. May use single instruction with
   * different rounding than sequential operations. *)
(*
  val `*+` : real * real * real -> real [@@prototype "*+ (a, b, c)"]
  val `*-` : real * real * real -> real
*) [@@prototype "*- (a, b, c)"]

  (* Produces negation; ~(±infinity) = ∓infinity. *)
(*
  val `~` : real -> real
*) [@@prototype "~ r"]

  (* Returns absolute value:
   * - abs (±0.0) = +0.0
   * - abs (±infinity) = +infinity
   * - abs (±NaN) = +NaN *)
  val abs : real -> real [@@method] [@@prototype "abs r"]

  (* If exactly one argument is NaN, they return the other argument.
   * If both arguments are NaN, they return NaN. *)
  val min : real * real -> real [@@method] [@@prototype "min (x, y)"]
  val max : real * real -> real [@@method] [@@prototype "max (x, y)"]

  (* Returns −1 for negative, 0 for zero, 1 for positive.
   * Raises Domain exception on NaN. *)
  val sign : real -> int [@@method] [@@prototype "sign r"]

  (* Returns true if and only if the sign of r (infinities, zeros, and NaN
   * included) is negative. *)
  val signBit : real -> bool [@@method] [@@prototype "signBit r"]

  (* Returns true if and only if signBit r1 equals signBit r2. *)
  val sameSign : real * real -> bool [@@method] [@@prototype "sameSign (r1, r2)"]

  (* Returns x with the sign of y, even if y is NaN. *)
  val copySign : real * real -> real [@@method] [@@prototype "copySign (x, y)"]

  (* Returns LESS, EQUAL, or GREATER; raises IEEEReal.Unordered on
   * unordered arguments. *)
  val compare : real * real -> `order` [@@method] [@@prototype "compare (x, y)"]

  (* Behaves similarly to compare except that the values it returns have
   * the extended type IEEEReal.real_order and it returns
   * IEEEReal.UNORDERED on unordered arguments. *)
(*
  val compareReal : real * real -> IEEEReal.real_order
*) [@@prototype "compareReal (x, y)"]

  (* Return true for corresponding relations; return false on unordered
   * arguments (when either is NaN). *)
(*
  val `<`  : real * real -> bool [@@prototype "x < y"]
  val `<=` : real * real -> bool [@@prototype "x <= y"]
  val `>`  : real * real -> bool [@@prototype "x > y"]
  val `>=` : real * real -> bool
*) [@@prototype "x >= y"]

  (* Returns true if and only if neither y nor x is NaN, and y and x are
   * equal, ignoring signs on zeros. *)
(*
  val `==` : real * real -> bool
*) [@@prototype "x == y"]

  (* Equivalent to not o op ==. *)
(*
  val `!=` : real * real -> bool
*) [@@prototype "x != y"]

  (* Returns true if either argument is NaN or if the arguments are
   * bitwise equal, ignoring signs on zeros. *)
(*
  val `?=` : real * real -> bool
*) [@@prototype "?= (x, y)"]

  (* Returns true if x and y are unordered, i.e., at least one of x and y
   * is NaN. *)
  val unordered : real * real -> bool [@@method] [@@prototype "unordered (x, y)"]

  (* Returns true if x is neither NaN nor an infinity. *)
  val isFinite : real -> bool [@@method] [@@prototype "isFinite x"]

  (* Returns true if x is NaN. *)
  val isNan : real -> bool [@@method] [@@prototype "isNan x"]

  (* Returns true if x is normal, i.e., neither zero, subnormal,
   * infinite nor NaN. *)
  val isNormal : real -> bool [@@method] [@@prototype "isNormal x"]

  (* Returns the IEEEReal.float_class to which x belongs. *)
(*
  val class : real -> IEEEReal.float_class
*) [@@prototype "class x"]

  (* Returns {man, exp} where r = man × radix^exp and
   * 1.0 ≤ man × radix < radix. For ±0, man is ±0 and exp is 0;
   * for ±infinity, man is ±infinity with unspecified exp;
   * for NaN, man is NaN with unspecified exp. *)
  val toManExp : real -> {man : real, exp : int} [@@method] [@@prototype "toManExp r"]

  (* Returns man × radix^exp. Note that, even if man is a non-zero,
   * finite real value, the result can be zero or infinity because
   * of underflows and overflows. *)
  val fromManExp : {man : real, exp : int} -> real [@@prototype "fromManExp r"]

  (* Returns {whole, frac} where whole is integral, |frac| < 1.0,
   * both have r's sign, and r = whole + frac. For ±infinity:
   * whole is ±infinity, frac is ±0; for NaN: both are NaN. *)
  val split : real -> {whole : real, frac : real} [@@method] [@@prototype "split r"]

  (* Equivalent to #frac o split. *)
  val realMod : real -> real [@@method] [@@prototype "realMod r"]

  (* Returns the next representable real after r in the direction of t.
   * If t is less than r, returns the largest representable floating-point
   * number less than r. If r = t then it returns r. *)
(*
  val nextAfter : real * real -> real
*) [@@prototype "nextAfter (r, t)"]

  (* Raises Overflow if x is an infinity, and raises Div if x is NaN.
   * Otherwise, it returns its argument. *)
  val checkFloat : real -> real [@@method] [@@prototype "checkFloat x"]

  (* Convert to integer-valued reals using floor, ceiling, truncation
   * toward zero, or nearest integer (ties to even) respectively.
   * Return NaN or infinity unchanged. *)
  val realFloor : real -> real [@@method] [@@prototype "realFloor r"]
  val realCeil : real -> real [@@method] [@@prototype "realCeil r"]
  val realTrunc : real -> real [@@method] [@@prototype "realTrunc r"]
  val realRound : real -> real [@@method] [@@prototype "realRound r"]

  (* Convert to int type using corresponding rounding modes.
   * Raise Overflow on overflow; raise Domain on NaN. *)
  val floor : real -> int [@@method] [@@prototype "floor r"]
  val ceil : real -> int [@@method] [@@prototype "ceil r"]
  val trunc : real -> int [@@method] [@@prototype "trunc r"]
  val round : real -> int [@@method] [@@prototype "round r"]

  (* Convert the argument x to an integral type using the specified
   * rounding mode. They raise Overflow if the result is not representable,
   * in particular, if x is an infinity. They raise Domain if the input
   * real is NaN. *)
(*
  val toInt : IEEEReal.rounding_mode -> real -> int [@@prototype "toInt mode x"]
  val toLargeInt : IEEEReal.rounding_mode -> real -> LargeInt.int
*) [@@prototype "toLargeInt mode r"]

  (* Convert integers to real. Large magnitudes become appropriate infinity;
   * inexact values use current rounding mode. *)
  val fromInt : int -> real [@@prototype "fromInt i"]
(*
  val fromLargeInt : LargeInt.int -> real
*) [@@prototype "fromLargeInt i"]

  (* Convert between real and LargeReal.real types. Out-of-range values
   * become zero or infinity. *)
(*
  val toLarge : real -> LargeReal.real [@@prototype "toLarge r"]
  val fromLarge : IEEEReal.rounding_mode -> LargeReal.real -> real
*) [@@prototype "toLarge r"]

  (* Converts reals to strings using SCI (scientific), FIX (fixed-point),
   * GEN (adaptive), or EXACT format. Positive and negative infinities are
   * converted to "inf" and "~inf", respectively, and NaN values are
   * converted to the string "nan". *)
(*
  val fmt : StringCvt.realfmt -> real -> string
*) [@@prototype "fmt spec r"]

  (* Equivalent to fmt (StringCvt.GEN NONE) r. *)
  val toString : real -> string [@@method] [@@prototype "toString r"]

  (* Parse real numbers from character sources, accepting formats like
   * [+~-]?(digits.digits | .digits)(e|E)[+~-]?digits and non-finite
   * representations like inf, infinity, nan (case-insensitive). *)
(*
  val scan : (char, 'a) StringCvt.reader -> (real, 'a) StringCvt.reader
*) [@@prototype "scan getc strm"]
  val fromString : string -> real option [@@prototype "fromString s"]

  (* Convert between real and IEEEReal.decimal_approx representations.
   * For normal/subnormal values: fromDecimal (toDecimal r) = r bitwise.
   * Returns NONE if decimal format is invalid. *)
(*
  val toDecimal : real -> IEEEReal.decimal_approx [@@prototype "toDecimal r"]
  val fromDecimal : IEEEReal.decimal_approx -> real option
*) [@@prototype "fromDecimal d"]
end

(*) End real.sig
