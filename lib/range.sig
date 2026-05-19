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
 * The RANGE signature, a Morel extension.
 *)
signature RANGE =
sig

  (* represents a set of values as a normalized list of
   * non-overlapping, non-adjacent ranges. *)
  eqtype 'a continuous_set

  (* represents a set of discrete values as a normalized list of
   * non-overlapping, non-adjacent ranges. *)
  eqtype 'a discrete_set

  (* represents a contiguous interval of values of an ordered type. *)
  datatype 'a range = ALL | AT_LEAST of 'a | AT_MOST of 'a | CLOSED of 'a * 'a | CLOSED_OPEN of 'a * 'a | GREATER_THAN of 'a | LESS_THAN of 'a | OPEN of 'a * 'a | OPEN_CLOSED of 'a * 'a | POINT of 'a

  (* returns true if x is a member of discrete set ds. *)
  val contains : 'a discrete_set -> 'a -> bool

  (* enumerates all values in the discrete set ds and returns them as
   * a bag. The element type must be discrete (e.g., int, char, bool).
   * Raises an exception if any range is unbounded below and the type
   * has no minimum value (e.g., LESS_THAN 5 : int range). *)
  val toBag : 'a discrete_set -> 'a bag

  (* enumerates all values in the discrete set ds and returns them as
   * a list, in ascending order. The element type must be discrete
   * (e.g., int, char, bool). Raises an exception if any range is
   * unbounded below and the type has no minimum value (e.g., *)
  val toList : 'a discrete_set -> 'a list

  (* normalizes ranges into a continuous_set. Overlapping and adjacent
   * ranges are merged, and the result is sorted by lower bound. *)
  val continuousSetOf : 'a range list -> 'a continuous_set

  (* normalizes ranges into a discrete_set. Overlapping and adjacent
   * ranges are merged (treating adjacent discrete values as
   * mergeable), and the result is sorted by lower bound. *)
  val discreteSetOf : 'a range list -> 'a discrete_set

  (* returns the list of ranges in the discrete set ds. *)
  val ranges : 'a discrete_set -> 'a range list

  (* returns the complement of discrete set ds: a discrete set
   * containing all values of the element type not in ds. *)
  val complement : 'a discrete_set -> 'a discrete_set
end

(*) End range.sig
