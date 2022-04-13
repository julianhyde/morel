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
 * Tests for 'suchThat'.
 *)

(*) If the expression is 'elem set' we can deduce the extent.
from e suchThat (e elem scott.emp)
  where e.deptno = 20
  yield e.ename;

(*) A function that finds its data internally.
let
  fun isEmp e =
    e elem scott.emp
in
  from e suchThat isEmp e
    where e.deptno = 20
    yield e.ename
end;
let
  fun isEmp e =
    e elem scott.emp
in
  from e suchThat isEmp e andalso e.deptno = 20
    yield e.ename
end;

(*) TODO: as previous, but 'fun' followed by 'from' without using 'let'

(*) Similar to 'isEmp' but with a conjunctive condition.
let
  fun isClerk e =
    e elem scott.emp andalso e.job = "CLERK"
in
  from e suchThat isClerk e andalso e.deptno = 20
    yield e.ename
end;

(*) A disjunctive condition prevents the extent.
(*) TODO: throw an error, rather than returning an empty list
let
  fun isEmp50 e =
    e elem scott.emp orelse e.deptno = 50
in
  from e suchThat isEmp50 e
    yield e.ename
end;

(*) A function with external extent.
fun hasJob (e, job) =
  e.job = job;
(*) Valid, because the argument has an extent.
let
  fun hasJob (e, job) =
    e.job = job
in
  from e in scott.emp,
    j suchThat hasJob (e, j)
    yield j
end;
(*) Invalid, because the argument has no extent.
from e suchThat hasJob (e, "CLERK");

(*) A string function with external extent.
(*) Given s2, we could generate finite s1.
fun isPrefix (s1, s2) =
  String.isPrefix s1 s2;
(*) This is invalid, but it could be valid, and would return
(*) ["", "a", "ab", "abc", "abcd"];
from s suchThat isPrefix (s, "abcd");

(*) An integer function with external extent.
(*) Given j, k we could generate finite i.
fun isBetween (i, j, k) =
  i <= j andalso j <= k;

(*) End suchThat.sml
