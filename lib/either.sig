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
  datatype ('left, 'right) either = INL of 'left | INR of 'right

  val isLeft  : ('left, 'right) either -> bool
  val isRight : ('left, 'right) either -> bool

  val asLeft  : ('left, 'right) either -> 'left option
  val asRight : ('left, 'right) either -> 'right option

  val map : ('ldom -> 'lrng) * ('rdom -> 'rrng)
  	  -> ('ldom, 'rdom) either
  	    -> ('lrng, 'rrng) either

  val mapLeft  : ('ldom -> 'lrng) -> ('ldom, 'rdom) either -> ('lrng, 'rdom) either
  val mapRight : ('rdom -> 'rrng) -> ('ldom, 'rdom) either -> ('ldom, 'rrng) either

  val app : ('left -> unit) * ('right -> unit)
  	  -> ('left, 'right) either
  	    -> unit

  val appLeft  : ('left -> unit) -> ('left, 'right) either -> unit
  val appRight : ('right -> unit) -> ('left, 'right) either -> unit

  val fold : ('left * 'b -> 'b) * ('right * 'b -> 'b)
  	   -> 'b -> ('left, 'right) either -> 'b

  val proj : ('a, 'a) either -> 'a

  val partition : (('left, 'right) either) list -> ('left list * 'right list)
end

(*) End either.sig
