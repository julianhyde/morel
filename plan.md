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

# Morel on Spark: phase 1 plan

Plan for [issue #467](https://github.com/hydromatic/morel/issues/467),
developed on branch `467-spark`. The specification below is the issue text,
verbatim. Milestones state goals and open questions; the notes record
decisions made so far.

## Specification (issue #467)

Create a first version of Morel running with an Apache Spark backend.

**Benefits**. Spark is a scalable, massively parallel data platform. It can
access data in a variety of formats, and its catalog is a gateway to an
enterprise's key data sets. Spark programs are typically a hybrid of SQL,
Python, Scala and data engineers will benefit from being able to express the
whole program in one language.

**Basic functionality**. A Morel program executes as a program in the Spark
cluster, accessing data sets stored in Spark's catalog. Conversely, from
phase 2 one can execute Morel commands from the Spark CLI similar to Spark
SQL, for example `df = morel.execute_query("from r in [{i=1, b=true}, {i=2,
b=false}]")`; when Morel is executed from within Spark, the connection is in
a value named `spark`, and `spark.catalog` gives access to data sets.

**Data exploration**. A Spark instance contains data sets whose type was not
known when the session started but we nevertheless wish to access in a
strongly-typed manner. This is the same requirement as the file reader
([#209](https://github.com/hydromatic/morel/issues/209)). A Spark connection
yields a catalog object that, like the `Sys.file` object, is a progressively
typed record.

**Data types**. Morel manages all coercions to and from Spark types. We will
need to devise and document a mapping from Spark types to Morel types. Morel
has `string`, `int`, `bool`, `real`; `option` for nullable columns; `bag` for
tables; `list` for arrays; record types for struct; Morel types for `byte`,
`short`, `long`, `binary`, `map` are TBD. Morel's `Time.time` type (which
represents instants, measured from UTC epoch) approximates SQL's
TIMESTAMP_LTZ type; Morel has no decimal type yet. Until the type mapping is
completed in phase 3, `decimal` columns will map to Morel's `real` type;
`byte`, `short`, `long` to `int`; `binary` to `word list`.

**Connectivity and planning**. Morel talks to Spark via Spark Connect (data
exploration and plans in protobuf over gRPC) in ANSI mode. Spark's plans are
a DAG of relational operators encoded in Protobuf. Any local data sets used
in a query are sent via Spark Connect's LocalRelation (over gRPC in Apache
Arrow format). If a query contains non-relational operators, its plan will
contain leaves, UDFs and table functions that are executed by the Morel
interpreter (phase 2).

**Built-in library**. A new `Spark` structure, with a `connection` type
(typically assigned to a value called `spark`) with a `catalog` method that
returns the root of the catalog, a `prepare` function that converts a Morel
value to a pair consisting of a Spark plan and an equivalent value, and an
`execute` function that evaluates a plan. (The "equivalent value" has the
same type and the same semantics but executes using Spark.) `plan` is an
opaque type over a Protobuf value, and has a `toString` method that we can
use for tests.

**Configuration and testing**. A full test environment will require Spark
running in a Docker container. However, it is too slow, expensive and flaky
for everyday development/testing. Regular builds and CI will run without
Spark, using mock data sources and testing conversion to Spark plans without
executing them. Ideally, a large fraction of tests are `.smli` scripts,
calling the built-in library, in particular Morel-to-Spark planning. A
periodic (e.g. weekly) CI will test with a Docker container, enabled via a
JVM property.

**Phased delivery**. Phase 1 will execute purely relational expressions
(including join, aggregate, sort, and correlated subqueries) against
Spark-native data sets and data uploaded via LocalRelation. Phase 2 will
package Morel's Java interpreter in Spark, thus allowing hybrid plans
(embedding Morel UDFs) and `morel.execute_query` from within Spark. Phase 3
will add any missing data types, e.g. a `decimal` type.

## Phase 1 milestones

* **M0 Oracle.** Build a Docker image running a Spark Connect server with
  seed data, and wire it into CI.
* **M1 Seed queries.** Decide 5 representative queries and the text
  rendering of their Spark plans.
* **M2 Type mapping.** Define the mapping between Spark and Morel types, and
  write a test that demonstrates it works.
* **M3 API.** Design the `Spark` structure, including the behavior of the
  `plan` type. How do we execute programs that are not queries (do not
  return a collection of records)? Programs that take arguments, of all
  possible types? Are sum types possible?
* **M4 Function inventory.** Define the list of Morel functions/operators
  that must be pushed down to Spark, and design a representation for the
  mapping.
* **M5 Translator.** Translate Morel's logical plan (core) to Spark plans,
  making the M1 tests pass.
* **M6 Connectivity.** Open a session, execute a plan, decode Arrow results
  into Morel values.
* **M7 Catalog.** Browse Spark's catalog as a progressively typed record.
* **M8 Execution.** Compile and execute queries against catalog data.
* **M9 Local data.** Upload local values via LocalRelation; force remote
  execution of queries over local data.
* **M10 Errors.** Map expected and unexpected Spark errors to good Morel
  exceptions.
* **M11 Edge cases.** Extend coverage to the tricky operators; repurpose the
  `dual` tests into a triple (local/Calcite/Spark) format.

## Notes

**M0.** Pinned stock Spark image, 4.x minimum, plus an init script: start the
Connect server, enable ANSI mode, register seed tables (`emp`, `dept`, and a
"datatype zoo" table with one column per Spark type, nullable and
non-nullable, edge values). Keep the image dumb; phase 2's interpreter jar
ships per-session via Spark Connect's `AddArtifacts`, not baked into the
image. Gate live tests on a JVM property; connection URI comes from
`SPARK_REMOTE` so the same tests run against the container or a real cluster.
Scripts that need a live connection skip when it is unset; translation-only
scripts always run. Spark 4.0 is the floor because it is the first release
whose Connect protocol has `SubqueryExpression`, which references the outer
plan by id and so can carry a correlated subquery (M1 query 5); against a 3.x
server Morel would have to decorrelate first. Done:
`src/test/resources/spark/start-spark.sh` creates the container (or reuses or
restarts an existing one, or removes it with `--stop`) and prints the `sc://`
URI once the server is ready. The container's driver program is `seed.py`,
run by `spark-submit` with the Spark Connect plugin, so the server runs in
the driver's context and clients see the tables it creates: a `scott`
database holding the four tables of Morel's built-in `scott` data set with
the same rows (so `spark.catalog.scott.emps` reads the same on a real
connection and on the offline one, spec.md §2.5), and `zoo` in the default
database (one column per Spark type; a typical row, an edge row, and a null
row). The server runs in the UTC time zone. `.github/workflows/spark.yml`
runs weekly (and on demand): it starts the container, runs the seed queries
with the PySpark client, and runs the build with `-Dmorel.spark=true` and
`SPARK_REMOTE` set. A finding for M2: Spark's built-in catalog does not keep
NOT NULL for tables stored as files, so every column of a catalog table reads
back as nullable, even `emp.empno`; only views over typed data (global
temporary views) keep non-null columns. `./morel --spark[=URI]` enables the
backend for a shell or script run, starting the container when no URI is
given and `SPARK_REMOTE` is unset; without the flag, `SPARK_REMOTE` is hidden
from Morel, so Spark is opt-in.

**Packaging.** The Spark adapter lives in its own package, and the rest of
Morel reaches it only through an interface declared outside that package,
instantiated via `Class.forName`. Nothing outside the package imports
gRPC, protobuf, Arrow, or the adapter's classes. This lets the adapter
later take on requirements (Java floor, dependency weight) that the rest
of Morel does not.

**Client library and Java floor.** Morel compiles at source level 8 and
CI builds on Java 8 through 25. Spark 4's `spark-connect-client-jvm`
requires Java 17 and Scala 2.13 and is a large shaded jar, so we do not
use it. Instead: protobuf stubs generated from Spark's `.proto` files,
grpc-java for transport, and Arrow Java 17.x (the last line supporting
Java 8) to decode result batches and encode LocalRelation payloads. This
is how the Go and Rust Connect clients work, and it means the client's
Java floor is independent of the server's Spark version.

**M1.** Done. The five queries and their local results are in
`src/test/resources/script/spark.smli`, and their expected Spark plans in
`src/test/resources/spark/seed-plans.txt`, captured by
`src/test/resources/spark/capture.py` from the PySpark Connect client
(`pyspark-client`, a small package that builds plans without a server once
its config and column-validation round trips are stubbed). All five have been
executed against a Spark 4.0.0 Connect server in Docker and return the rows
Morel computes locally. The queries: (1) filter+project over an inline
relation of int, string, bool and real columns; (2) equijoin of `scott.emps`
and `scott.depts` on `deptno`; (3) group by `deptno` with count, sum and max;
(4) project, sort on two keys of mixed direction, take 3; (5) `exists`
subquery correlated on `deptno`. The queries read the `scott` data set, which
M0 seeds into the container as a database of the same name. The text
rendering is protobuf text format with plan ids renumbered in order of first
appearance and a LocalRelation's Arrow payload replaced by its row count.
Translation decisions the captured plans pin: every scan is wrapped in a
`subquery_alias` named by its Morel binder, and every column reference is the
dotted `binder.field`; after a projection the names are the projected labels,
unqualified. This is the only form in which Spark 4.0.0 resolves the outer
reference of a correlated subquery when both sides have a column of the same
name; qualifying by the producing relation's `plan_id`, which is what the
DataFrame API emits for a join, fails in a subquery with
`CANNOT_RESOLVE_DATAFRAME_COLUMN`. A correlated subquery is `with_relations`,
whose `references` hold the inner plan and whose root refers to it by
`subquery_expression { plan_id }`. A sort names its null ordering explicitly
(descending defaults to nulls last, ascending to nulls first). `count` is
over the literal 1 and returns a `long`, so the decoder narrows it to `int`.
A LocalRelation carries its schema as JSON with per-column nullability.
Binders that shadow one another across nested queries will need renaming
before emission, since the alias namespace is flat.

**M2.** Done. The mapping is `spark.SparkTypes`, from the DataType proto that
Connect sends (a schema string is not needed, since the AnalyzePlan RPC gives
a table's schema as a proto); its pure test is `SparkTypesTest`, and the live
half is `spark-live.smli` and `SparkConnectTest`, which read every column of
`zoo` and see the map column rejected. The plan's idea of a `.smli` test
through the offline connection lapsed: that connection's catalog is
Calcite-typed, so it exercises the catalog, not the Spark mapping. Drafted in
spec.md §1: the type table, nullability at every level, dates and times,
precision, the mapping function and its DDL and JSON inputs, and the reverse
mapping. The mapping is new code. The Calcite `Converters` mapping is not
reused: it represents `option` as a nullable column and coerces nulls back to
zero values, which is lossy. Two halves. Pure: a function mapping Spark
schema strings (DDL or JSON) to Morel types, tested in `.smli` with no
cluster; includes tested rejections (`map`, intervals) and nullability at
every nesting level. Live: browse the zoo table, print the inferred type,
select and print the decoded values (needs M6). Decided (spec.md §1.2): a
catalog column maps to the plain type and the decoder raises when a null
arrives; nested array elements and struct fields keep `option`.

**M3.** Drafted in spec.md §2: the signature, what `prepare` accepts,
lifecycle, errors, and an offline `mock:` URI for testing translation without
a cluster (no `mock` function in the signature). Decided design (spec.md
§2.1): `connect` returns `{catalog: {...}, connection: connection}`, a
record, because the resolver types a name from its value and discovers a
catalog's fields only by walking from a name through record selectors; so
`spark.catalog.scott.emps` works inline, and `Spark.catalog c` must be bound
to a name before browsing. A plan is a function, `type ('a, 'b) plan`,
prepared from a function expression (`prepare : connection * ('a -> 'b) ->
('a, 'b) plan`) and executed on an argument (`execute : ('a, 'b) plan * 'a ->
'b`); a query with no parameters is a function of `unit`. `prepare` is not an
ordinary function: Morel evaluates arguments eagerly, so a function would
receive the query's result, not the query. It is an intrinsic that operates
on its argument's parse tree, in the same way as `Plan.program`
([#359](https://github.com/hydromatic/morel/issues/359)); the type signature
is as above, but the compiler recognizes the call.
[#470](https://github.com/hydromatic/morel/issues/470) proposes `Plan.core :
'a -> Core.expr`, an intrinsic of exactly this shape that reifies an
expression as a value; `prepare` and `Plan.core` should share one mechanism,
so keep this design in sync with #470. Non-query results wrap as a
single-row, single-column relation. `prepare` of a function value returns a
function of the same type; applying it splices arguments as literals or
LocalRelations. Boundary-representable types are exactly those with an image
in the M2 mapping; sum types cross via a tagged struct encoding (`option` is
the degenerate case, via nullability); recursive datatypes and function types
are rejected. Connection lifecycle: explicit `connect`/`close` (test scripts
open once, run many statements, close); a `using` wrapper for scoped use; no
pooling in phase 1; a registry of open connections, a cleaner that warns on
leaks, a shutdown hook, and a harness check that scripts leave the registry
empty. Use after close raises a closed-connection error, including when
forcing a lazy remote value.

**M4.** The table exists:
`src/main/resources/net/hydromatic/morel/spark/functions.sml`, a Morel
expression that `SparkFunctionsTest` evaluates and checks (every name is a
built-in, every kind is known, templates use their arguments). The translator
(M5) will load it; the tests that iterate it in the triple format come with
M11. Drafted in spec.md §3: the table's format and first contents. Represent
the mapping as data, ideally in Morel, shared by all ports: each entry
classifies an operator as direct, renamed, rewritten (an expression
template), or unsupported, with a flag for known semantic divergence (integer
division, overflow, collation, NaN ordering). Tests iterate the table through
the triple format.

**M5.** Done, but for executing. The translator is `spark.SparkTranslator`,
and all sixteen seed queries translate: `SparkTranslatorTest` holds each
against the plan `spark/seed-plans.txt` captured for it, which `capture.py`
still reproduces exactly, so a decision the translator makes is one PySpark
can be made to make too. The `Spark` structure has the `('a, 'b) plan` type,
`prepare` and `toString`, so `spark-plan.smli` runs: its seventeen plans are
the captured ones, byte for byte, reached through Morel rather than through a
Java test. `execute` and `remote` are M8's.

`prepare` is an intrinsic, expanded where it is resolved, as `Sys.planOf` is:
it reads the parse tree of its function rather than evaluating it, inlines it
once so that a built-in is a function literal the translator can recognise,
and makes the plan a literal, so that a plan is translated once however often
it is used. A table reaches it as a value rather than an expression, because
inlining replaced the read with the rows; the name is in the expression the
value remembers.

The tests were delivered ahead of the translator, since the tree it
reads was on the 449-tree branch: `spark.smli` runs the corpus locally
(sixteen queries: the five seed queries plus skip and take, distinct, left
join, union, arithmetic with the `div` and `mod` templates, string functions,
`elem`, a correlated scalar subquery, a scalar result, except and intersect,
and a two-key group); `spark/seed-plans.txt` holds their Spark plans,
captured from the PySpark client and executed live to confirm that Spark
gives Morel's results; `spark-plan.smli` (requires `spark-prepare`) prepares
each query on the offline connection and expects the plan text; and
`spark-exec.smli` (requires `spark` and `spark-prepare`) executes each on the
container and expects the results, plus a parameterized plan and `remote`.
The `spark-prepare` condition holds once the `Spark` structure has `prepare`,
so the scripts turn on by themselves. Decisions the corpus pins beyond M1: a
real literal is a double literal, and a decimal literal carries the narrowest
decimal type that holds it, as Spark's own parser gives `2BD`; `div` is
`cast(floor({0} / {1}) as int)` and `mod` is `(({0} % {1} + {1}) % {1})`, and
a rewritten function crosses as an expression string, not as the tree its
template would parse to; `elem` is an EXISTS subquery with an equality, since
Connect 4.0 has no IN subquery, and the collection's projection comes off,
its expression joining the condition; a left join's absent side is a struct
that is null when the join found nothing, decoded as an option, and the
column it asks about is the first field, in field order, whose type is not an
option; distinct is `deduplicate`; union, except and intersect keep all rows;
a scalar result is an aggregate with one column, named for what Morel's
`compute` names the aggregate. Every one of these is plan text, so all three
implementations must agree on it. The translator itself is
built on the 449-tree branch when it lands: Sequenced after the core tree
restructuring ([#449](https://github.com/hydromatic/morel/issues/449), branch
`449-tree`): Connect's Relation proto is a conventional operator tree, and
translating from the balanced tree is near 1:1. The M1 expectations are
substrate-independent and serve as this milestone's acceptance tests.
Everything that depends on #449 (M5, and through it M8, M9 and M11) is done
last; M0 through M4, M6 and M7 do not depend on it and come first.

**A left join whose right rows are all optional cannot say "no match".**
A task, or a bug to log if it is not done in this pass.

Spark's left join gives the absent side a row of nulls, so Morel asks
whether the side matched by testing a column that cannot itself be null,
and builds the `option` from the answer: `when(isNull(e.deptno), null,
struct(...))`. A row every one of whose columns is optional has no such
column, and the trouble is not that the witness is hard to choose -- it is
that the two cases are the same bytes. A matched row of all nulls and no
match at all are indistinguishable in what Spark returns.

```sml
from d in scott.depts
  left join e in (from x in scott.emps yield {x.comm, x.mgr}) on true
  yield {d.dname, e};
```

gives `{dname:string, e:{comm:decimal option, mgr:int option} option} bag`,
and KING, who has neither commission nor manager, really does contribute
`SOME {comm=NONE, mgr=NONE}`. Had no employee matched, the answer would be
`NONE`, and Spark would send the same thing either way. In SQL the query
is unremarkable --

```sql
SELECT d.dname, e.*
FROM scott.dept AS d
LEFT JOIN (SELECT comm, mgr FROM scott.emp) AS e ON TRUE
```

-- because SQL never claims to tell the two apart: the columns are null,
and that is all it says. The distinction exists only because Morel types
the absent side as an option.

The fix is to carry the answer rather than deduce it: project a constant
column onto the right input before the join, and test that. It costs a
node in the plan and no rows, and it would let every left join use one
rule instead of a rule plus an exception. Until then the translator should
reject such a join rather than pick a column that cannot answer the
question.

**M6.** Done. The adapter is package `net.hydromatic.morel.spark`, compiled
only under the `spark` Maven profile (JDK 17 and later), with protobuf
messages and gRPC stubs generated from Spark 4.0.0's `.proto` files in
`src/main/protobuf`; on JDK 8 and 11 the package is excluded and Morel builds
without it. The rest of Morel sees only `foreign.SparkBackend`, an interface
whose `load()` instantiates the adapter by name. A connection opens a gRPC
channel, and a query is an ExecutePlan request whose result arrives as Arrow
batches with a DataType schema; `SparkTypes` maps the schema to a Morel type
(spec.md §1, including decision B) and `ArrowDecoder` converts the batches to
Morel values, driven by that type. Spark's error condition comes from the
"errorClass" key of the ErrorInfo metadata on the gRPC status. Two things
learned: Arrow's netty allocator is incompatible with the netty that Calcite
brings, so the adapter uses the unsafe allocator; and Arrow needs
`--add-opens=java.base/java.nio=ALL-UNNAMED` on JDK 9 and later, which the
surefire configuration and `./morel --spark` supply. Tests: `SparkTypesTest`
and `ArrowDecoderTest` run everywhere the adapter compiles;
`SparkConnectTest` runs against the container when `-Dmorel.spark=true` and
`SPARK_REMOTE` are set, and reads the seed tables, including every column of
`zoo` and the error cases.

**M7.** Done. `SparkBackend.Connection` gained catalog methods (databases,
tables, a table's Morel type, a table's rows); `foreign.SparkCatalog` is the
catalog as a progressively typed record, built like `Files`: databases are
fetched when the root's type is first asked for, a database's tables when it
is first discovered, a table's type when it is first discovered, and its rows
when first forced, each bumping the expand count so the resolver deduces
again. `foreign.MockSparkConnection` is the offline connection (`mock:`),
whose catalog is the session's foreign data sets (now kept on `Session`), and
`SparkBackend.open` chooses between it and the adapter by URI. The adapter
lists databases and tables with `SHOW` statements and gets a table's type
from the AnalyzePlan schema RPC. Tested offline over the scott data set and
live against the container, where `scott.emps` has the same type and rows on
both. The `Spark` structure (`lib/spark.sig`, `docs/lib/spark.md`) has
`connection`, the `Spark` exception, `connect`, `connectDefault`, `catalog`,
`close` and `using`; `built-in/spark.smli` browses and queries the offline
catalog, and `spark-live.smli` (`requires: spark`) does the same against the
container. A catalog error, even one raised while type resolution discovers a
field, is reported as the `Spark` exception (its position is unknown, so it
prints as 0.0-0.0). Printing a catalog level shows a table as `<relation>`
rather than fetching its rows; forcing the table fetches them once.

**M9.** Done, but for forcing remote execution, which needs `execute`.
`spark.ArrowEncoder` writes the rows of a local collection as an Arrow
record batch, so a prepared plan carries its rows rather than only a count
of them, and `ArrowEncoderTest` round-trips them through `ArrowDecoder`. A
collection crosses that way whether the query writes it out or reaches it
through a name; a table of the connection's catalog is a scan however it is
reached, because the table says what Spark calls it, and only a value that
cannot say becomes rows to send. `spark-plan.smli` holds both.

**M8.** Done. `Spark.execute` runs a prepared plan on the connection it was
prepared for and decodes the rows as values of the type the program was typed
against; `Spark.remote` is `prepare` and `execute` in one step, expanded like
`prepare` and holding the function that runs the plan. A plan with one
parameter is translated at each execution, with the argument in place of the
parameter; see above for why, and for what is still missing. The offline
connection refuses to execute ("MOCK"). `spark-exec.smli` runs the whole
corpus on the container, and every query answers as the local corpus
answers.

**M10.** Three categories: runtime errors (e.g. divide by zero) must raise
the same Morel exception as local evaluation, testable in the triple
format; analysis errors are translator bugs and dump the offending plan —
except schema drift (table dropped after typechecking), which is a
user-facing error and gets its own test; environmental errors (refused,
auth, session expired) are user-facing connection errors. Unrecognized
server errors map to a catch-all exception carrying Spark's error class and
message (from the gRPC ErrorInfo metadata); after any error the connection
must remain usable.

**Testing throughout.** The `.smli` checker matches bag-valued output as a
multiset, so query output need not be deterministic. Expected error output
is part of the contract. `ScriptTest` gains a simple way to skip a script
(scripts needing a live server skip unless the gating property is set).
The live tests run in a separate weekly GitHub workflow; the existing
workflow keeps its short timeout and never starts Docker.

## A plan with a parameter

Spark Connect has no placeholder in a plan: a plan is a tree of relations,
and its parameters are for SQL text (`SQL.named_arguments`), not for a tree.
So spec.md section 2.2, which says `prepare` leaves "the parameter as a
placeholder", describes something the protocol cannot hold, and one of three
things has to happen instead.

**Decided: the query is translated again at each execution**, with the
argument in place of the parameter. Measured against the container, that
costs nothing worth counting: building and serializing a plan is 0.1 ms,
against 33 ms for the smallest round trip, and Spark re-analyses on every
request anyway -- a plan that was byte-identical to the last one ran only
about 5 ms faster, so the server's analyzed-plan cache is not what decides
this. The alternatives are no better: SQL text with a named argument ran the
same (36 ms small, 105 ms over 200M rows, against 38 and 100 for a rebuilt
plan), and it would mean generating SQL for whole queries rather than trees;
passing the argument as a one-row local relation to join against was the only
approach with a real cost (65 ms and 124 ms), because it turns a filter into
a join.

What this costs: a plan with a parameter has no text until it is given one,
so `toString` on one is refused. The other way round -- a plan carrying a
dummy literal that `execute` overwrites in the protobuf -- would print, but
what it printed would be a lie about one column.

**Only one parameter, and not a tuple of them.** A function of several
arguments is one of a tuple, and Morel's core destructures it: `fn (d, j) =>
e` is `fn v => case v of (d, j) => e`, so substituting the argument for `v`
leaves `d` and `j` bound by a case the translator does not reduce, and the
query is refused for naming a variable it cannot do. Supporting them means
reducing that case while substituting. The calling syntax needs thought
first: `p.execute (30, "SALESMAN")` splices into `execute (p, 30,
"SALESMAN")`, a three-tuple, so even with substitution it would have to be
written `Spark.execute (p, (30, "SALESMAN"))`.

## Phase 2 questions

**A nullable catalog column and an optional local one are not the same
type.** Spark's catalog keeps no NOT NULL for a table stored as files, so a
column maps to the plain type and a null in one raises (spec.md section 1.2,
decision B). Morel's own `scott` comes from Calcite, which does know
nullability, so its `comm` is `decimal option` and its president has no
manager. The container's `scott` therefore holds a zero where the data set
holds NONE, or the table could not be read at all.

The corpus mostly does not notice, since only the eighth query reads a whole
row, but that query's live result and its local result differ: `comm` is
`decimal` and zero there, `decimal option` and NONE here. Either the mapping
learns nullability from somewhere (a declaration, as decision A wanted), or
the triple tests of M11 must know that this column is one the two sides
disagree about.

**Should an uploaded collection be sent once and reused?** A catalog
table costs nothing to refer to twice -- it is a name that Spark resolves
-- but a local collection crosses as a `LocalRelation`, and since
`prepare` fixes a free variable's value when the plan is made, two plans
over the same bag each carry their own copy of the rows.

Connect has the mechanism: `CachedLocalRelation` carries nothing but a
sha-256 hash of the serialized `LocalRelation`, so a client can transfer
the rows once and refer to them by hash afterwards. It is session-scoped
by design -- `userId` and `sessionId` are reserved fields, removed
because "it is never valid to access a local relation from a different
session/user" -- and the hash is of bytes the translator already builds,
so identical data collides on its own, with no bookkeeping about which
Morel variable it came from.

Three things it would cost, which is why it is a question and not a
milestone:

* **`prepare` would stop being local.** It touches no server today,
  which is what lets it work against the `"mock:"` connection and lets
  `spark-plan.smli` run with no container. Uploading before referring by
  hash gives it a round trip.
* **Plan text would depend on data bytes.** A cached relation prints as
  a hash where an inline one prints as `data: [N rows]` -- and that
  substitution exists precisely so that a plan's text does not depend on
  how Arrow encoded the rows. The hash would also have to be identical
  in morel-rust and morel-go, since the three share the text.
* **It only pays above some size.** Below it, carrying the rows inline
  is cheaper than a round trip plus a hash.
