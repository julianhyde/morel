<!--
{% comment %}
Licensed to Julian Hyde under one or more contributor license
agreements.  See the NOTICE file distributed with this work
for additional information regarding copyright ownership.
Julian Hyde licenses this file to you under the Apache
License, Version 2.0 (the "License"); you may not use this
file except in compliance with the License.  You may obtain a
copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
either express or implied.  See the License for the specific
language governing permissions and limitations under the
License.
{% endcomment %}
-->

# Spark structure

[Up to index](index.md)

[//]: # (start:lib/spark)


## Synopsis

<pre>
type <a id='connection' href="#connection-impl">connection</a>

exception <a id='Spark' href="#Spark-impl">Spark</a> of {errorClass: string, message: string}

val <a id='connect' href="#connect-impl">connect</a> : string -> {catalog: {}, connection: connection}
val <a id='connectDefault' href="#connectDefault-impl">connectDefault</a> : unit -> {catalog: {}, connection: connection}
val <a id='catalog' href="#catalog-impl">catalog</a> : connection -> {}
val <a id='close' href="#close-impl">close</a> : connection -> unit
val <a id='using' href="#using-impl">using</a> : (connection * 'a -> 'b) -> 'a -> 'b
</pre>

<a id="connection-impl"></a>
<h3><code><strong>type</strong> connection</code></h3>

is a connection to a Spark Connect server, or the offline
connection whose catalog is the session's foreign data sets.

<a id="Spark-impl"></a>
<h3><code><strong>exception</strong> Spark</code></h3>

is raised by an operation that Spark, or the Spark adapter, rejects.
`errorClass` is Spark's error condition, such as `DIVIDE_BY_ZERO`
or `TABLE_OR_VIEW_NOT_FOUND`, or one of the adapter's own:
`CONNECTION` (the server could not be reached), `CLOSED` (the
connection was closed), `NULL_VALUE` (a null in a column whose Morel
type has no room for it), `UNSUPPORTED_TYPE`, or `MOCK` (the offline
connection cannot execute).

<a id="connect-impl"></a>
<h3><code>connect</code></h3>

`connect uri` opens a connection to the server at `uri`, such as
`"sc://localhost:15002"`, or the offline connection if `uri` is
`"mock:"`. Returns a record whose `connection` field is the
connection and whose `catalog` field is the root of its catalog: a
progressively typed record whose fields are databases, whose fields
in turn are tables, each a bag of records. So `spark.catalog.scott.emps`
reads a table.

<a id="connectDefault-impl"></a>
<h3><code>connectDefault</code></h3>

`connectDefault ()` opens a connection to the server named by the `SPARK_REMOTE`
environment variable, as `connect` does.

<a id="catalog-impl"></a>
<h3><code>catalog</code></h3>

`catalog c` (or `c.catalog ()`) returns the root of the catalog of connection `c`, as the `catalog`
field of `connect` returns it. Bind it to a name with `val` to browse
it, since a name's type grows as fields are discovered.

<a id="close-impl"></a>
<h3><code>close</code></h3>

`close c` (or `c.close ()`) closes connection `c`. Any later use of it raises `Spark`.

<a id="using-impl"></a>
<h3><code>using</code></h3>

`using f` returns a function that opens the default connection, applies `f` to
the connection and its argument, and closes the connection afterwards,
whether or not `f` raised. So `using (fn (c, x) => x + 1)` is a
function that, applied to 4, returns 5.

[//]: # (end:lib/spark)
