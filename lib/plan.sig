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
 * Plan structure: what the compiler made of a function.
 *)

(* Plan structure signature *)
signature PLAN =
sig
  (**
   * returns the body of the function `f` as the compiler holds it, after
   * every pass and rule. A query prints as a relational tree, one node per
   * line, with the collection type of each node, as `Sys.planOf` prints
   * one; the function's parameter is a free name in it.
   *
   * Raises `Fail` if `f` is not a function the compiler compiled, such as
   * a built-in.
   *)
  val bodyOf : ('a -> 'b) -> string [@@prototype "bodyOf f"]
end
[@@description "What the compiler made of a function."]
[@@specified "morel"]

(*) End plan.sig
