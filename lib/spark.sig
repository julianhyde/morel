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
 *)
signature SPARK =
sig

  (**
   * is a connection to a Spark Connect server, or the offline
   * connection whose catalog is the session's foreign data sets.
   *)
  eqtype connection

  (**
   * is raised by an operation that Spark, or the Spark adapter, rejects.
   * `errorClass` is Spark's error condition, such as `DIVIDE_BY_ZERO`
   * or `TABLE_OR_VIEW_NOT_FOUND`, or one of the adapter's own:
   * `CONNECTION` (the server could not be reached), `CLOSED` (the
   * connection was closed), `NULL_VALUE` (a null in a column whose Morel
   * type has no room for it), `UNSUPPORTED_TYPE`, or `MOCK` (the offline
   * connection cannot execute).
   *)
  exception Spark of {errorClass: string, message: string}

  (**
   * opens a connection to the server at `uri`, such as
   * `"sc://localhost:15002"`, or the offline connection if `uri` is
   * `"mock:"`. Returns a record whose `connection` field is the
   * connection and whose `catalog` field is the root of its catalog: a
   * progressively typed record whose fields are databases, whose fields
   * in turn are tables, each a bag of records. So `spark.catalog.scott.emps`
   * reads a table.
   *)
  val connect : string -> {catalog: {}, connection: connection}
    [@@prototype "connect uri"]

  (**
   * opens a connection to the server named by the `SPARK_REMOTE`
   * environment variable, as `connect` does.
   *)
  val connectDefault : unit -> {catalog: {}, connection: connection}
    [@@prototype "connectDefault ()"]

  (**
   * returns the root of the catalog of connection `c`, as the `catalog`
   * field of `connect` returns it. Bind it to a name with `val` to browse
   * it, since a name's type grows as fields are discovered.
   *)
  val catalog : connection -> {} [@@method] [@@prototype "catalog c"]

  (**
   * closes connection `c`. Any later use of it raises `Spark`.
   *)
  val close : connection -> unit [@@method] [@@prototype "close c"]

  (**
   * returns a function that opens the default connection, applies `f` to
   * the connection and its argument, and closes the connection afterwards,
   * whether or not `f` raised. So `using (fn (c, x) => x + 1)` is a
   * function that, applied to 4, returns 5.
   *)
  val using : (connection * 'a -> 'b) -> 'a -> 'b [@@prototype "using f"]
end
[@@description "Connections to Apache Spark."]
[@@specified "morel"]

(** The `Spark` structure connects Morel to an Apache Spark cluster through
 * Spark Connect, and gives access to its catalog as a progressively typed
 * record. Connect with `Spark.connect uri` (or `Spark.connect "mock:"` for
 * an offline connection whose catalog is the session's foreign data sets),
 * then browse the catalog: `spark.catalog.scott.emps` is a bag of records.
 *)

(*) End spark.sig
