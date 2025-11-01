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
 * The FN signature, a proposed addition to the standard basis
 * library.
 *)
signature FN =
sig
  val id       : 'a -> 'a
  val const    : 'a -> 'b -> 'a
  val apply    : ('a -> 'b) * 'a -> 'b
  val o        : ('b -> 'c) * ('a -> 'b) -> ('a -> 'c)
  val curry    : ('a * 'b -> 'c) -> ('a -> 'b -> 'c)
  val uncurry  : ('a -> 'b -> 'c) -> ('a * 'b -> 'c)
  val flip     : ('a * 'b -> 'c) -> ('b * 'a -> 'c)
  val repeat   : int -> ('a -> 'a) -> ('a -> 'a)
(* TODO support eqtype in signatures
  val equal    : ''a -> ''a -> bool
  val notEqual : ''a -> ''a -> bool
*)
  val equal    : 'a -> 'a -> bool
  val notEqual : 'a -> 'a -> bool
end

(*) End fn.sig
