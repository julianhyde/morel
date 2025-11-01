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
signature GENERAL =
sig
  eqtype unit
  type exn = exn
    
  exception Bind
  exception Match
  exception Chr
  exception Div
  exception Domain
  exception Fail of string
  exception Overflow
  exception Size
  exception Span
  exception Subscript

  val exnName : exn -> string
  val exnMessage : exn -> string

  datatype `order` = LESS | EQUAL | GREATER
(*
  val ! : 'a ref -> 'a
  val := : 'a ref * 'a -> unit
*)
  val o : ('b -> 'c) * ('a -> 'b) -> 'a -> 'c
  val before : 'a * unit -> 'a
  val ignore : 'a -> unit
end
