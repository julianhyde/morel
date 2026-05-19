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
 * The RELATIONAL signature, a Morel extension.
 *)
signature RELATIONAL =
sig

  (* wraps a value so that it sorts in descending order when used with
   * Relational.compare. *)
  datatype 'a descending = DESC of 'a

  (* returns LESS, EQUAL, or GREATER according to whether its first
   * argument is less than, equal to, or greater than the second. *)
  val compare : 'a * 'a -> `order`

  (* returns the number of elements in a bag (or list). Often used
   * with group, for example
   *   from e in emps
   *   group e.deptno compute countId = count *)
  val count : 'a bag -> int [@@method]

  (* returns whether the bag (or list) is empty, for example
   *   from d in depts
   *   where empty (from e where e.deptno = d.deptno) *)
  val empty : 'a bag -> bool [@@method]

  (* computes a fixed point, starting with initialList and calling
   * listUpdate (prevList, newList) each iteration, terminating when
   * the result equals the previous result. *)
  val iterate : 'a bag -> ('a bag * 'a bag -> 'a bag) -> 'a bag [@@method]

  (* returns the greatest element. Often used with group, for example
   *   from e in emps
   *   group e.deptno compute maxId = max of e.id *)
  val max : 'a bag -> 'a [@@method]

  (* returns the least element. Often used with group, for example
   *   from e in emps
   *   group e.deptno compute minId = min of e.id *)
  val min : 'a bag -> 'a [@@method]

  (* returns whether the bag (or list) has at least one element, for
   * example
   *   from d in depts
   *   where nonEmpty (from e where e.deptno = d.deptno) *)
  val nonEmpty : 'a bag -> bool [@@method]

  (* returns the sole element of a bag (or list); raises Empty if the
   * collection is empty, Size if it has more than one element. *)
  val only : 'a bag -> 'a [@@method]

  (* returns the sum of the elements. Often used with group, for
   * example
   *   from e in emps
   *   group e.deptno compute sumId = sum of e.id *)
  val sum : 'a bag -> 'a [@@method]
end

(*) End relational.sig
