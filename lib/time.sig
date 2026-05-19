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
 * The TIME signature, per the Standard ML Basis Library.
 *)
signature TIME =
sig

  (* is an equality type representing both absolute times (relative to
   * the Unix epoch, 1970-01-01T00:00:00Z) and time durations. Both
   * absolute times and intervals are represented identically; the
   * interpretation is contextual. Negative values represent times *)
  eqtype time

  (* is raised when a conversion produces a value that cannot be
   * represented as a time value (for example, when fromReal is called
   * with NaN or infinity). *)
  exception Time

  (* denotes an empty interval and serves as the reference point for
   * absolute times. It is equivalent to fromReal(0.0). *)
  val zeroTime : time

  (* converts r (measured in seconds) to a time value. Raises Time if
   * r is NaN, infinite, or otherwise not representable. *)
  val fromReal : real -> time

  (* converts the time value t to a real number representing seconds. *)
  val toReal : time -> real [@@method]

  (* returns the number of whole seconds in t, truncated toward zero. *)
  val toSeconds : time -> int [@@method]

  (* returns the number of whole milliseconds in t, truncated toward
   * zero. *)
  val toMilliseconds : time -> int [@@method]

  (* returns the number of whole microseconds in t, truncated toward
   * zero. *)
  val toMicroseconds : time -> int [@@method]

  (* returns the number of whole nanoseconds in t. *)
  val toNanoseconds : time -> int [@@method]

  (* returns the time value corresponding to n seconds. *)
  val fromSeconds : int -> time

  (* returns the time value corresponding to n milliseconds. *)
  val fromMilliseconds : int -> time

  (* returns the time value corresponding to n microseconds. *)
  val fromMicroseconds : int -> time

  (* returns the time value corresponding to n nanoseconds. *)
  val fromNanoseconds : int -> time

  (* returns the sum of the two time values t1 and t2. *)
  val `+` : time * time -> time [@@method]

  (* returns the difference of the two time values t1 and t2. *)
  val `-` : time * time -> time [@@method]

  (* returns LESS, EQUAL, or GREATER depending on whether t1 is less
   * than, equal to, or greater than t2. *)
  val compare : time * time -> `order` [@@method]

  (* returns true if t1 is less than t2. *)
  val `<` : time * time -> bool [@@method]

  (* returns true if t1 is less than or equal to t2. *)
  val `<=` : time * time -> bool [@@method]

  (* returns true if t1 is greater than t2. *)
  val `>` : time * time -> bool [@@method]

  (* returns true if t1 is greater than or equal to t2. *)
  val `>=` : time * time -> bool [@@method]

  (* returns the current time. *)
  val now : unit -> time

  (* formats t as a decimal number of seconds with n fractional
   * digits. For example, fmt 3 (fromReal 1.5) returns "1.500".
   * Negative time values are formatted with a leading ~. *)
  val fmt : int -> time -> string

  (* formats t as a decimal number of seconds with 3 fractional
   * digits. Equivalent to fmt 3 t. *)
  val toString : time -> string [@@method]

  (* parses a time value from the string s, which should be a decimal
   * number of seconds. Returns SOME t if successful, NONE otherwise. *)
  val fromString : string -> time option
end

(*) End time.sig
