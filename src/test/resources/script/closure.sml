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
 * Expressions that use recursion, function values, and closure.
 *)

(*) Simple factorial function
let
  fun fact n =
    if n = 0 then 1 else n * fact (n - 1)
in
  fact 5
end;

(*) Similar, but using parallel functions
let
  fun fact 0 = 1
    | fact n = n * fact (n - 1)
in
  fact 5
end;

(*) Similar, but using 'case'
let
  fun fact n =
    case n
      of 0 => 1
       | n => n * fact (n - 1)
in
  fact 5
end;

(*) Similar, but 'val rec'
let
  val rec fact =
    fn n =>
      case n
        of 0 => 1
         | n => n * fact (n - 1)
in
  fact 5
end;

(*) Closure; int variable 'n' is captured in the function definition
val one = 1;
fun fact 0 = one
  | fact n = n * fact (n - 1);
fact 5;
val one = 2;
fact 5;
fun fact 0 = one
  | fact n = n * fact (n - 1);
fact 5;

(*) Closure; function 'f' is captured in the function definition
val f = fn x => x - 1;
fun fact3 0 = 1
  | fact3 n = n * fact3 (f n);
fact3 5;

(*) Closure; function 'f' is an argument to an enclosing function.
fun baz4 f =
  let
    fun fact4 0 = 1
      | fact4 n = n * fact4 (f n)
  in
    fact4 5
end;
baz4 (fn i => i - 1);

(*) Closure; function 'f' is not the only argument.
fun baz5 (f, m) =
  let
    fun fact5 0 = 1
      | fact5 n = n * fact5 (f n)
  in
    fact5 m
end;
baz5 (fn i => i - 1, 5);

(*) As previous, with shadowed 'n'.
fun baz6 (f, n) =
  let
    fun fact6 0 = 1
      | fact6 n = n * fact6 (f n)
  in
    fact6 n
end;
baz6 (fn i => i - 1, 5);

(*) End closure.sml
