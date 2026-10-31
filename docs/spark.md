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
# Morel on Spark

Morel can run a query on an [Apache Spark](https://spark.apache.org)
cluster. You write the same `from` expression you would write over a list,
Morel translates it to a Spark plan, Spark runs it, and the rows come back
as Morel values.

## Why

* **One language for a laptop and a cluster.** A query over a list, over a
  JDBC database, and over a Spark table is the same query. You develop
  against three rows and run against three billion.
* **It is typed before it runs.** `spark.catalog.scott.emps` has a record
  type, so a misspelled column or a comparison between a string and an int
  is a compile error, not a stage failure twenty minutes in.
* **The catalog is a value.** Databases and tables are fields of a
  progressively typed record, so you explore with the language you query
  with -- `spark.catalog;`, then `spark.catalog.scott;` -- rather than with
  a separate dialect of `SHOW`.
* **No silent half-execution.** A plan is all Spark or it is an error: a
  query Spark cannot do is refused where the plan is made, naming the
  construct, rather than quietly running part of it in one place and part
  in another.
* **Your data can meet the cluster's.** A list the session built crosses as
  rows and joins a server table.
* **You can read the plan without running it**, and against an offline
  connection with no cluster at all.

## A first session

Start a server. This one is a Docker container holding the `scott` tables,
which is what Morel's own tests use; any Spark Connect server will do.

```bash
$ src/test/resources/spark/start-spark.sh
sc://localhost:15002
```

Then start Morel with `--spark`, which connects, binds the server as
`spark`, and sends each query there:

```bash
$ ./morel --spark
```

```sml
(*) What Spark has
spark.catalog.scott;
> val it =
>   {bonuses=<relation>,depts=<relation>,emps=<relation>,salgrades=<relation>}
>   : {bonuses:{...} bag, depts:{...} bag, emps:{...} bag, salgrades:{...} bag,
>       ...}

(*) A query, run on the cluster
from e in spark.catalog.scott.emps
  group e.deptno compute {c = count over ()}
  order deptno;
> val it = [{c=3,deptno=10},{c=5,deptno=20},{c=6,deptno=30}]
>   : {c:int, deptno:int} list

(*) A list of your own, joined to a table of theirs
val rates = [{job = "CLERK", bonus = 100}, {job = "MANAGER", bonus = 500}];
from e in spark.catalog.scott.emps
  join r in rates on e.job = r.job
  yield {e.ename, r.bonus};
> val it =
>   [{bonus=500,ename="BLAKE"},{bonus=500,ename="CLARK"},
>    {bonus=500,ename="JONES"},{bonus=100,ename="ADAMS"},
>    {bonus=100,ename="JAMES"},{bonus=100,ename="SMITH"},
>    {bonus=100,ename="MILLER"}] : {bonus:int, ename:string} bag
```

The `rates` rows are sent with the plan; the employees never leave the
cluster.

## Configuration

### Command line and environment

| What | Means |
| --- | --- |
| `--spark` | Connect to `$SPARK_REMOTE`, or `sc://localhost:15002`; bind it as `spark`; push queries down |
| `--spark=URI` | The same, with that URI, whatever `$SPARK_REMOTE` says |
| `--no-spark` | What omitting `--spark` does: no connection, no push-down |
| `$SPARK_REMOTE` | The default server, which `Spark.connectDefault` also reads |

`--spark` starts no server and stops none. Start one yourself --
`src/test/resources/spark/start-spark.sh` runs a container and prints its
URI; `--stop` removes it -- or point `--spark` at a cluster you have.

### Session properties

| Property | Type | Default | Means |
| --- | --- | --- | --- |
| `sparkPushDown` | bool | false | Translate each query for Spark and run it there. `--spark` sets it |
| `sparkFallback` | bool | true | A query Spark cannot do is evaluated here, with a warning, rather than failing |

```sml
Sys.set ("sparkPushDown", false);   (*) back to local evaluation
Sys.set ("sparkFallback", false);   (*) a refusal is an error, not a warning
```

`sparkFallback` governs refusals only -- a construct or function the
translator cannot express. A query that reached the cluster and failed
there is raised, never recomputed locally: its answer is not ours to
invent.

### Connection URI parameters

A URI may carry parameters after `/;`, separated by `;`:

```
sc://localhost:15002/;user_id=fred;nullable=scott.emps.comm
```

| Parameter | Means |
| --- | --- |
| `user_id` | The user the server sees |
| `nullable` | Columns that may be null, as `database.table.column`; `*` means every column |

A declared column has type `t option`; an undeclared one has the plain
type `t`, and a null in it raises `Spark` with error class `NULL_VALUE`.

### Table properties

Better than a URI, a table can say which of its own columns hold nulls:

```sql
ALTER TABLE scott.emps SET TBLPROPERTIES ('morel.nullable' = 'comm,mgr')
```

Morel reads `morel.nullable` when it asks for a table's type. The property
is the whole answer for its table -- a column it names may be null, one it
does not name may not -- so an empty property says the table holds no
nulls, which is different from saying nothing. A URI wins where both
speak.

This is needed because Spark keeps nothing itself: a `NOT NULL` in a
`CREATE TABLE` is accepted and discarded, nothing enforces it, `ALTER
COLUMN ... SET NOT NULL` is refused for a table stored as files, and
Spark's own constraints (CHECK, UNIQUE, PRIMARY KEY, FOREIGN KEY) do not
include nullability, which belongs to a column's type. Delta Lake and
Iceberg do record it -- at the price of changing file format.

### Building and testing

The adapter needs JDK 17 or later, where the `spark` Maven profile
activates; on an older JDK, Morel builds without it. Tests that need a
server are skipped unless you ask for them:

```bash
export SPARK_REMOTE=$(src/test/resources/spark/start-spark.sh)
./mvnw test -Dmorel.spark=true
```

## The `Spark` structure

`connect`, `connectDefault`, `catalog`, `close`, `prepare`, `toString`,
`execute`, `remote`, `using`, the type `('a, 'b) plan`, and the exception
`Spark {errorClass, message}`. See the
[library reference](lib/spark.md) for each.

```sml
(*) A plan, made but not run
val p = spark.connection.prepare (fn () =>
  from d in spark.catalog.scott.depts where d.deptno = 10 yield d.dname);
p.toString ();     (*) the plan, as Spark's own text format
p.execute ();      (*) run it
```

`prepare` reads the parse tree of the function, so the function has to be
written at the call; a plan with parameters is translated afresh at each
execution, with the argument in place.

## Architecture

Morel talks to Spark over **Spark Connect**, the gRPC protocol that Spark
4 exposes. A client builds a `Plan` -- a tree of protobuf `Relation`
messages -- and the server analyzes or executes it; results come back as
Arrow record batches. Morel speaks the protocol directly, from protobuf
definitions vendored into the repository, so a Morel session needs no
Spark jars and no JVM on the cluster's version.

Six pieces do the work:

| Piece | Job |
| --- | --- |
| `SparkTranslator` | Morel's core query to a protobuf relation tree |
| `SparkTypes`, `SparkSchema` | Spark's types to Morel's, and back |
| `ArrowEncoder`, `ArrowDecoder` | Morel values to Arrow batches, and back |
| `SparkCatalog` | Databases and tables as a progressively typed record |
| `PreparedPlan` | The value of `('a, 'b) plan`: a plan and its connection |
| `SparkConnection` | The channel, and requests on it |

What a query reads crosses in one of two ways, and **what the collection
is decides which, not what it is called**:

* a table of the connection's catalog becomes a scan -- `read {
  named_table { "scott.emps" } }`;
* every other collection -- a list, a data set, a value a query computed --
  becomes `local_relation`, carrying its rows.

So a data set that happens to be named `scott` is not the server's
`scott`: to read the server's table, reach it through the catalog.

## Reading a plan

`Sys.plan ()` prints the plan of the statement just run. Morel's own plan
is what you see, with a node naming any engine that did part of the work:
`calcite(...)` where Apache Calcite did, and `spark(...)` where Spark did.

With `Sys.set ("hybrid", true)`, a query over a JDBC data set goes to
Calcite:

```
"globalMarshal(globals [scott], body calcite(plan
  LogicalProject(dname=[$1])
    LogicalFilter(condition=[=($0, 20)])
      LogicalProject(deptno=[$0], dname=[$1], loc=[$2])
        JdbcTableScan(table=[[scott, DEPT]])
))"
```

With `sparkPushDown`, a query over a catalog table goes to Spark, and the
plan node holds the plan that was sent:

```
"apply(fnValue spark(plan root {
  common { plan_id: 0 }
  project {
    input { ... filter { ... read { named_table {
      unparsed_identifier: "scott.depts" } } ... } }
    expressions { unresolved_attribute {
      unparsed_identifier: "d.dname" } }
  }
}), argCode constant([]))"
```

(Both are one long line in reality; they are laid out here to be read, and
the Spark plan is elided in the middle.)

Three things to look for:

* **`spark(plan root {...})`** -- Spark ran this, and that is what it was
  asked. `read { named_table }` means it read its own table;
  `local_relation { data: [N rows] }` means Morel sent the rows.
* **`spark(parameters [d])`** -- a prepared plan with a parameter, which has
  no text until it is executed, because each execution translates it afresh.
* **no `spark(` at all** -- it ran here. If you expected otherwise, look
  for the warning: with `sparkFallback` true, a query Spark could not do
  says so and is evaluated locally.

## Limits

* A plan is all Spark or an error; there is no partial push-down yet.
* A row must be a record: Spark has no column for a bare `int`, so
  `from i in [1, 2]` cannot be sent, and nothing has a column for `unit`.
* A few operators answer differently on Spark -- an overflow raises where
  Morel wraps, because Spark runs in ANSI mode; `Int.toString` writes
  `-7782` where Morel writes `~7782`; a substring that runs off the end
  clamps where Morel raises. Each is recorded, with both answers, in
  `src/test/resources/script/spark-divergence.smli`.
* `real` is a 32-bit float in Morel and a column crosses as `double`, so a
  computation done on the cluster is done in double precision.

## See also

* [`Spark` structure reference](lib/spark.md)
* `spec.md` and `plan.md` in the repository root: the design, the
  decisions, and what is not done
