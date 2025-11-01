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
  
  val toLarge   : int -> (*LargeInt.*)int
  val fromLarge : (*LargeInt.*)int -> int
  val toInt   : int -> (*Int.*)int
  val fromInt : (*Int.*)int -> int
  
  val precision : (*Int.*)int option
  val minInt : int option
  val maxInt : int option
  
  val + : int * int -> int
  val - : int * int -> int
  val * : int * int -> int
  val div : int * int -> int
  val mod : int * int -> int
  val quot : int * int -> int
  val rem : int * int -> int
  
  val compare : int * int -> `order`
  val <  : int * int -> bool
  val <= : int * int -> bool
  val >  : int * int -> bool
  val >= : int * int -> bool
  
  val ~ : int -> int
  val abs : int -> int
  val min : int * int -> int
  val max : int * int -> int
  val sign : int -> (*Int.*)int
  val sameSign : int * int -> bool
  
  val fmt      : (*StringCvt.*)radix -> int -> string
  val toString : int -> string
  val scan       : (*StringCvt.*)radix
                     -> (char, 'a) (*StringCvt.*)reader
                       -> (int, 'a) (*StringCvt.*)reader
  val fromString : string -> int option
end
