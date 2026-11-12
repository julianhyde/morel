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
(**
 * The `Relational` structure provides aggregation, comparison, and set
 * operations that are used in Morel `from` expressions. These functions
 * extend Standard ML with relational-algebra capabilities.
 *)
signature RELATIONAL =
sig

  (**
   * wraps a value so that it sorts in descending order when used with
   * `Relational.compare`.
   *)
  datatype 'a descending = DESC of 'a

  (**
   * returns the mean of the elements of `list`. Often used with `group`, for
   * example `from e in emps group e.deptno compute avgSal = avg over e.sal`.
   *
   * The type is overloaded: the mean of `int` values is a `real`, of `real`
   * values a `real`, and of `decimal` values a `decimal`.
   *
   * Raises `Empty` if `list` is empty.
   *)
  val avg : int bag -> real [@@method] [@@prototype "avg list"]

  (**
   * returns `SOME` of the mean of the elements of `list`, as `avg` does, or
   * `NONE` if `list` is empty.
   *
   * To ignore `NONE`, as SQL's `AVG` ignores nulls, use `avgSome`.
   *)
  val avgOpt : int bag -> real option [@@method] [@@prototype "avgOpt list"]

  (**
   * returns `SOME` of the mean of the values of the `SOME` elements of
   * `list`, ignoring `NONE`, or `NONE` if there are none. It is SQL's `AVG`,
   * which ignores nulls.
   *
   * The type is overloaded, as `avg`'s is.
   *)
  val avgSome : int option bag -> real option
      [@@method] [@@prototype "avgSome list"]

  (**
   * returns `LESS`, `EQUAL`, or `GREATER` according to
   * whether its first argument is less than, equal to, or greater than the
   * second.
   *
   * Comparisons are based on the structure of the type `α`.
   * Primitive types are compared using their natural order;
   * Option types compare with NONE first;
   * Tuple types compare lexicographically;
   * Record types compare lexicographically, with the fields
   * compared in alphabetical order;
   * List values compare lexicographically;
   * Bag values compare lexicographically, the elements appearing
   * in an order that is arbitrary but is consistent for each
   * particular value.
   *)
  val compare : 'a * 'a -> `order` [@@prototype "compare (x, y)"]

  (**
   * returns the number of elements in `list`. Often used with
   * `group`, for example `from e in emps group e.deptno compute countId =
   * count`.
   *
   * Counts every element, including `NONE`; `countSome` counts the `SOME`s.
   *)
  val count : 'a bag -> int [@@method] [@@prototype "count list"]

  (**
   * returns the number of `SOME` elements of `list`. It is SQL's `COUNT(x)`,
   * which ignores nulls.
   *)
  val countSome : 'a option bag -> int
      [@@method] [@@prototype "countSome list"]

  (**
   * returns whether the list is empty, for example `from d in
   * depts where empty (from e where e.deptno = d.deptno)`.
   *)
  val empty : 'a bag -> bool [@@method] [@@prototype "empty list"]

  (**
   * computes a fixed point, starting with `initialList` and calling
   * `listUpdate (prevList, newList)` each iteration, terminating the
   * iteration when it returns `newList`.
   *)
  val iterate : 'a bag -> ('a bag * 'a bag -> 'a bag) -> 'a bag
      [@@method] [@@prototype "iterate initialList listUpdate"]

  (**
   * returns the greatest element of `list`. Often used with
   * `group`, for example `from e in emps group e.deptno compute maxId =
   * max of e.id`.
   *
   * Elements are compared as `compare` compares them, so `NONE` is less than
   * any `SOME`. Raises `Empty` if `list` is empty.
   *)
  val max : 'a bag -> 'a [@@method] [@@prototype "max list"]

  (**
   * returns the element of `list` for which `keyFn` gives the greatest key,
   * for example `maxBy String.size ["john", "paul", "george", "ringo"]`,
   * which returns `"george"`.
   *
   * Often used with `group` to deduplicate whole rows, for example
   * `from p in product_versions group p.id compute latest = maxBy
   * #version over p`.
   *
   * If several elements are tied for the greatest key, and the input is
   * unordered, it is not specified which of them is returned.
   *
   * Raises `Empty` if `list` is empty.
   *)
  val maxBy : ('a -> 'b) -> 'a bag -> 'a [@@prototype "maxBy keyFn list"]

  (**
   * returns `SOME` of the greatest element of `list`, as `max` does, or `NONE`
   * if `list` is empty.
   *
   * To ignore `NONE`, as SQL's `MAX` ignores nulls, use `maxSome`.
   *)
  val maxOpt : 'a bag -> 'a option [@@method] [@@prototype "maxOpt list"]

  (**
   * returns `SOME` of the greatest value of the `SOME` elements of `list`,
   * ignoring `NONE`, or `NONE` if there are none. It is SQL's `MAX`, which
   * ignores nulls.
   *)
  val maxSome : 'a option bag -> 'a option
      [@@method] [@@prototype "maxSome list"]

  (**
   * returns the least element of `list`. Often used with
   * `group`, for example `from e in emps group e.deptno compute minId =
   * min of e.id`.
   *
   * Elements are compared as `compare` compares them, so `NONE` is less than
   * any `SOME`. Raises `Empty` if `list` is empty.
   *)
  val min : 'a bag -> 'a [@@method] [@@prototype "min list"]

  (**
   * returns the element of `list` for which `keyFn` gives the least key,
   * for example `minBy String.size ["john", "paul", "george", "ringo"]`,
   * which returns `"john"`.
   *
   * If several elements are tied for the least key, and the input is
   * unordered, it is not specified which of them is returned.
   *
   * Raises `Empty` if `list` is empty.
   *)
  val minBy : ('a -> 'b) -> 'a bag -> 'a [@@prototype "minBy keyFn list"]

  (**
   * returns `SOME` of the least element of `list`, as `min` does, or `NONE`
   * if `list` is empty.
   *
   * To ignore `NONE`, as SQL's `MIN` ignores nulls, use `minSome`.
   *)
  val minOpt : 'a bag -> 'a option [@@method] [@@prototype "minOpt list"]

  (**
   * returns `SOME` of the least value of the `SOME` elements of `list`,
   * ignoring `NONE`, or `NONE` if there are none. It is SQL's `MIN`, which
   * ignores nulls.
   *)
  val minSome : 'a option bag -> 'a option
      [@@method] [@@prototype "minSome list"]

  (**
   * returns whether the list has at least one element, for
   * example `from d in depts where nonEmpty (from e where e.deptno =
   * d.deptno)`.
   *)
  val nonEmpty : 'a bag -> bool [@@method] [@@prototype "nonEmpty list"]

  (**
   * returns the sole element of list, for example `from e in
   * emps yield only (from d where d.deptno = e.deptno)`.
   *)
  val only : 'a bag -> 'a [@@method] [@@prototype "only list"]

  (**
   * returns the values of the `SOME` elements of `list`, dropping each
   * `NONE`; for example, `somes [SOME 1, NONE, SOME 3]` returns `[1, 3]`.
   * The result is a bag; for a list, `List.mapPartial (fn x => x)` keeps
   * the order.
   *
   * `sumOpt (somes list)` is `sumSome list`, and so on.
   *)
  val somes : 'a option bag -> 'a bag [@@method] [@@prototype "somes list"]

  (**
   * returns the sum of the elements of `list`. Often used with
   * `group`, for example `from e in emps group e.deptno compute sumId =
   * sum of e.id`.
   *
   * The elements may be `int`, `real` or `decimal`. Returns 0 if `list` is
   * empty.
   *)
  val sum : 'a bag -> 'a [@@method] [@@prototype "sum list"]

  (**
   * returns `SOME` of the sum of the elements of `list`, or `NONE` if `list`
   * is empty.
   *
   * To ignore `NONE`, as SQL's `SUM` ignores nulls, use `sumSome`.
   *)
  val sumOpt : 'a bag -> 'a option [@@method] [@@prototype "sumOpt list"]

  (**
   * returns `SOME` of the sum of the values of the `SOME` elements of
   * `list`, ignoring `NONE`, or `NONE` if there are none. It is SQL's `SUM`,
   * which ignores nulls.
   *)
  val sumSome : 'a option bag -> 'a option
      [@@method] [@@prototype "sumSome list"]
end
[@@description "Relational algebra operations for Morel queries."]
[@@specified "morel"]

(*) End relational.sig
