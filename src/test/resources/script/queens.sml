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
 * The N-Queens problem
 * Based on https://rosettacode.org/wiki/N-queens_problem#Standard_ML
 *)

(*
 * val threat : (int * int) -> (int * int) -> bool
 * Returns true iff the queens at the given positions threaten each other
 *)
fun threat (x, y) (x', y') =
  x = x' orelse y = y' orelse abs(x - x') = abs(y - y');

(*
 * val conflict : (int * int) -> (int * int) list -> bool
 * Returns true if there exists a conflict with the position and the list of queens.
 *)
fun conflict pos = List.exists (threat pos);

(*
 * val addQueen : (int * int * (int * int) list * (unit -> (int * int) list option)) -> (int * int) list option
 * Returns either NONE in the case that no solution exists or SOME(l) where l is a list of positions making up the solution.
 *)
fun addQueen (i, n, qs, fc) =
  let
    fun try j =
      if j > n then fc ()
      else if (conflict (i, j) qs) then try (j + 1)
      else if i = n then SOME ((i, j)::qs)
      else addQueen (i + 1, n, (i,j)::qs, fn() => try (j + 1))
  in
    try 1
  end;

(*
 * val queens : int -> (int * int) list option
 * Given the board dimension n, returns a solution for the n-queens problem.
 *)
fun queens n = addQueen (1, n, [], fn () => NONE);

(* SOME [(8,4),(7,2),(6,7),(5,3),(4,6),(3,8),(2,5),(1,1)] *)
(*) Morel cannot solve currently - gives StackOverflowError
(*) queens 8;

(* NONE *)
queens 2;

(* SOME [(7,6),(6,4),(5,2),(4,7),(3,5),(2,3),(1,1)] *)
queens 6;

(*) End queens.sml
