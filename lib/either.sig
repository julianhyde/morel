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
 * The EITHER signature, a proposed addition to the standard basis
 * library.
 *)
signature EITHER =
sig
  [@@@specified "basis+"]
  (* A generic union type with left and right variants. *)
  datatype ('left, 'right) either = INL of 'left | INR of 'right

  (* Tests whether the value is a left variant. *)
  val isLeft  : ('left, 'right) either -> bool [@@method] [@@prototype "isLeft sm"]

  (* Tests whether the value is a right variant. *)
  val isRight : ('left, 'right) either -> bool [@@method] [@@prototype "isRight sm"]

  (* Extracts left value as option; returns SOME if left, NONE otherwise. *)
  val asLeft  : ('left, 'right) either -> 'left option [@@method] [@@prototype "asLeft sm"]

  (* Extracts right value as option; returns SOME if right, NONE otherwise. *)
  val asRight : ('left, 'right) either -> 'right option [@@method] [@@prototype "asRight sm"]

  (* Applies left function to left values, right function to right values. *)
  val map : ('ldom -> 'lrng) * ('rdom -> 'rrng)
          -> ('ldom, 'rdom) either
            -> ('lrng, 'rrng) either [@@prototype "map (fl, fr) sm"]

  (* Maps function over left values; acts as identity on right values. *)
  val mapLeft  : ('ldom -> 'lrng)
               -> ('ldom, 'rdom) either -> ('lrng, 'rdom) either [@@prototype "mapLeft f sm"]

  (* Maps function over right values; acts as identity on left values. *)
  val mapRight : ('rdom -> 'rrng)
               -> ('ldom, 'rdom) either -> ('ldom, 'rrng) either [@@prototype "mapRight f sm"]

  (* Applies left function to left values, right function to right values. *)
  val app : ('left -> unit) * ('right -> unit)
          -> ('left, 'right) either
            -> unit [@@prototype "app (fl, fr) sm"]

  (* Applies function to left values; ignores right values. *)
  val appLeft  : ('left -> unit) -> ('left, 'right) either -> unit [@@prototype "appLeft f sm"]

  (* Applies function to right values; ignores left values. *)
  val appRight : ('right -> unit) -> ('left, 'right) either -> unit [@@prototype "appRight f sm"]

  (* Reduces either value using appropriate function with accumulator. *)
  val fold : ('left * 'b -> 'b) * ('right * 'b -> 'b)
           -> 'b -> ('left, 'right) either -> 'b [@@prototype "fold (fl, fr) init sm"]

  (* Extracts contents when both variants contain the same type. *)
  val proj : ('a, 'a) either -> 'a [@@method] [@@prototype "proj sm"]

  (* Separates list of either values into left and right components. *)
  val partition : (('left, 'right) either) list -> ('left list * 'right list) [@@prototype "partition sms"]
end

(*) End either.sig
