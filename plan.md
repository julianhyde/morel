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
# #502: TPC-DS on Morel and Spark

Branch `502-tpc-ds` in `morel.3`, cut from `main` at `5e1bbbfcd`
(2026-11-08). Issue:
[hydromatic/morel#502](https://github.com/hydromatic/morel/issues/502).
This file holds the milestones and decisions; `~/dev/plan.md` holds only
the state of play.

## The goal

Run all 99 TPC-DS queries as Morel programs, locally and on Spark, at
scale factor 0.01 (about 10MB), with the results checked. The issue's
five deliverables, in the order they can be built:

1. **Data.** A repeatable way to generate the scale-0.01 data set and
   load it into the Spark test container. (Issue step 2; it comes first
   because nothing else can be checked without it.)
2. **The script.** `tpcds-10m.smli`: a Morel type for the 24-table
   schema, then the 99 queries, each with its SQL in a comment and its
   expected output. Claude translates the SQL. (Issue step 1.)
3. **Passes locally.** (Issue step 3.)
4. **Passes on Spark**, the same script, pushed down. (Issue step 4.)
5. **An `Sql` structure** that translates SQL DDL and queries to Morel
   types and programs, doing in code what Claude did by hand in step 2.
   (Issue step 5. A design of its own; see the last milestone.)

The suffix names the scale: `-10m` is SF 0.01, `-1g` SF 1, `-1t` SF
1,000. Only `-10m` is in scope.

## What exists, and what is missing

*Surveyed 2026-11-08 on `5e1bbbfcd`.*

**Spark side, mostly there.** `start-spark.sh` runs `apache/spark:4.0.0`
with `init.py` as the driver, which creates tables with `CREATE TABLE
... USING parquet` and a `morel.nullable` property per table, and the
container is recreated when `init.py`'s hash changes. A script marked
`(*) requires: spark` runs when `morel.spark` is set and `SPARK_REMOTE`
names a server, with `spark` bound. Spark maps `date` to `Date.date`,
`decimal(p,s)` to `decimal`, `bigint` to `int` (raising `Overflow`).
`sparkPushDown` sends each query to Spark; `sparkFallback=false` makes a
refusal an error, which is what step 4 needs. The translator
(`SparkTranslator`, which works on the `Core` tree) does scan, project,
filter, join, group/aggregate, sort, take, skip, and two-input set
operations.

**In flight elsewhere:** `morel.2` has two #501 commits above `main`
that push down `unorder`, an n-ary set operator and `bag`. Step 4
should start from them once they land, not duplicate them.

**Local side, the gaps.** There is no TPC-DS anything in the repo. The
only local route for tabular data is the file adapter
(`eval/Files.java`, `file.data.<dir>.<table>` over `.csv`/`.csv.gz`
with a `name:type` header), and it cannot carry TPC-DS:

* `decimal` in a header becomes **`real`**, Morel's 32-bit float. Every
  TPC-DS price and sum is a `decimal(7,2)`; summed as floats they would
  be wrong in the cents.
* `NULL` parses as **`0`** (or `0.0`). TPC-DS non-key columns are
  nullable and dsdgen does write nulls into them, so `sum`, `avg`,
  `count(col)` and every `where` on them would be wrong, silently.
* There is no `date` type. `d_date`, the one date column that matters,
  is compared and shifted (`d_date between cast('2000-02-01' as date)
  and cast('2000-02-01' as date) + interval 60 days`) in about 15
  queries.

And for the local and Spark runs to be *the same script*, the local
table's Morel type must equal the Spark catalog's type exactly.

**Morel language gaps that TPC-DS exercises** (measured 2026-11-10: the
103 query files of Spark `v4.0.0` scanned for each SQL feature, and each
Morel question tried in the shell on `3a7f5f03`; the per-query table is
under "Survey"):

| SQL | Queries | Morel today |
| --- | ---: | --- |
| `sum`, `min`, `max` over a nullable column | nearly all | **not defined**: `sum over x` with `x : decimal option` is a type error ("operator 'sum' not defined for type 'decimal option bag'"). SQL ignores nulls, and is null over an all-null group. #503 |
| comparison of a nullable column (`ss_quantity between 1 and 20`) | nearly all | an `int option` cannot be compared with `int`; each needs a `case`, or a helper. SQL's three-valued `where` drops the row |
| `avg` | 30 | **none**: no `avg` aggregate for `int`, `real` or `decimal`. By hand as sum and count, but Spark's `avg` of `decimal(7,2)` is `decimal(11,6)` and of `int` is `double`. #503 |
| `rollup`, `grouping()` | 11 | **none**; a union of groupings by hand |
| window functions (`rank() over`, `sum() over`, `avg() over`) | 15 | **none**; `ordinal` only |
| `stddev_samp` | 3 (q17, q39a, q39b) | **none** in `Relational`; by hand from sums of squares, in 32-bit `real` |
| `count(distinct x)` | 4 | no syntax (#389), but `compute {c = List.length (from x in elements distinct)}` works |
| date arithmetic | 16 | all but one is a constant (`cast('2000-08-03' as date) + interval 30 days`), so precompute it. q72 adds 5 days to a column: `Date.fromTimeUniv (Time.`+` (Date.toTime d, Time.fromSeconds 432000))` works, and `<` compares dates. `Date.fromString` does not parse ISO dates (it reads SML's `Thu Aug 03 ...` form) |
| `with` (CTE) | 34 | `let val ... in ... end`; fine |
| `union`, `intersect`, `except` | 20, 4, 1 | supported; n-ary needs #501 for Spark |
| `exists`, `in (select ...)`, scalar subquery | 5, 12, 11 | supported |
| outer join | 11 | supported: `left join`, `right join`, `full join` make the other side's fields options (docs/query.md). A column that is already an option becomes `t option option`; `?.` reads through the layers |
| `like` | 1 (q91) | `String.isSubstring` and friends |
| `limit 100` | 86 | `take 100` |

**The nullable columns are the real problem, not the missing
features.** Under D3 every non-key column is an option, so every
measure (`ss_sales_price`, `ss_quantity`, ...) is, and Morel can neither
sum nor compare one directly. Spelled out by hand, each query triples in
size and stops looking like the SQL. That wants a design decision (D10),
ahead of M3.

## Decisions

D2, D3 and D6 are agreed (2026-11-08); D1 was revised the same day. The
rest are recommendations, to confirm before the milestone that depends
on each.

**D1. Generator: Trino's Java port, `io.trino.tpcds:tpcds:1.4`, in
the JVM.** Julian asked for Java or Spark (2026-11-08); Java it is,
with Spark loading the result.

* *Why 1.4.* It is the last release compiled for Java 8 (class version
  52); 1.5 to 1.7 are class version 69, Java 25, which Morel cannot
  depend on while it builds on JDK 8. Licence Apache 2.0. Dependencies
  Guava and jsr305, which Morel already has, and `airline` for its
  command line, which we exclude because we call the library.
* *The API* (read from the jar with `javap`; not yet run):
  `Results.constructResults(Table, Session)` iterates a table's rows as
  lists of strings, `Session.getDefaultSession().withScale(0.01)` sets
  the scale, which is a `double` (validated as "greater than 0 and less
  than 100000", so 0.01 is accepted), and `Table` enumerates the tables
  (24 real ones plus dsdgen's helper tables, to filter out).
* *How it runs.* `TpcdsData`, a test-scope class, with a `main` that a
  `generate.sh` wrapper calls through `./mvnw exec:java
  -Dexec.classpathScope=test`. It writes one `.csv.gz` per table to the
  cache directory in the file adapter's format (D4), with a typed header
  built from the DDL, and a `VERSION` file naming the generator and
  scale. Generated once and reused; deterministic, so two machines get
  the same bytes.
* *Spark loads, not generates.* `start-spark.sh` gains `--tpcds DIR`,
  which mounts the cache directory read-only at `/tpcds`; an
  `init-tpcds.py` reads the CSV with an explicit schema from the DDL
  and writes `tpcds.*` as Parquet tables with `morel.nullable`. So
  local and Spark read identical rows by construction, and the oracle
  (D6) is computed over the same data Morel reads.
* *Why not Spark.* Spark has no generator of its own; its own TPC-DS CI
  builds the C `dsdgen` from `databricks/tpcds-kit` (from knowledge,
  not checked). That is a C build in the container, and a second
  source of truth. DuckDB's `dsdgen` (the earlier recommendation) is an
  external tool too, and Morel has no other reason to need it.
* *To verify in M1.* That it generates at 0.01 without complaint, the
  row counts, the null rate, and that its string output (empty string
  for null, `|` separator, configurable) round-trips through our CSV:
  addresses and names may contain commas or quotes.

**The SQL texts** come from Spark's own repository, which carries the
99 queries with their parameters substituted, in Spark's dialect, under
Apache 2.0 (`sql/core/src/test/resources/tpcds/`, from knowledge; check
the path and the version). That makes the oracle runnable as is, and
avoids the TPC toolkit's licence for the query templates. Spark's golden
answers next to them are for SF 1, so they are not ours to use.

**D2. Data stays out of the repo** (agreed 2026-11-08). Generated into a
cache directory (`~/.cache/morel/tpcds/sf0.01/`, keyed by generator
version), and the script requires it.

**D3. Nullability from the TPC-DS DDL** (agreed 2026-11-08). A column is
`t option` unless the DDL declares it `not null` (in practice, the
surrogate keys). This is the rule Julian chose for #476's property --
"deducing nullability by analyzing a table is a dubious practice" --
and it makes the schema type independent of what one generation happened
to produce. The Spark tables carry it in `morel.nullable`, generated
from the same DDL, so the two cannot disagree. The DDL is one file in
the repo (`src/test/resources/tpcds/schema.sql`), the single source for
the CSV headers, the Spark schema, `morel.nullable`, and the check of
the Morel schema type.

**D4. Local data through an extended file adapter.** Teach
`Files.java`'s header `decimal` (as `decimal`, not `real`), `date` (as
`Date.date`), and a trailing `option` (`ss_quantity:int option`, with
the empty field or `NULL` as `NONE`). The generator writes `.csv.gz`
with those headers directly (D1). This is a correction to the adapter (a
`decimal` column read as `real` is a bug on its own) rather than
TPC-DS plumbing, so it is its own commit, with tests over a small
table. Alternative: load into HSQLDB and go through the Calcite
bridge, which already maps `DECIMAL` to `decimal` but maps `DATE` to
`string`, and whose nullability handling would need checking -- more
moving parts for the same result.

**D5. One script, two bindings.** The queries refer to `tpcds.<table>`.
A requires-condition `tpcds` (system property `morel.tpcds` naming the
data directory) binds `tpcds` to `file.data` over that directory; with
`spark` too, the harness binds `tpcds` to `spark.catalog.tpcds` and sets
`sparkPushDown` and `sparkFallback=false`. The same expected output must
then come out of both, which is the point.

**D6. The oracle is Spark SQL running the original SQL** (agreed
2026-11-08), not Morel's
own output. `init-tpcds.py`, or a capture step after it, runs each SQL
text in Spark (`spark.sql(...)`) and stores the result in the cache
directory beside the data. The `.smli` expected
output is what Morel prints, as usual; a checker (Python, alongside
`capture.py`) compares each Morel result with Spark's answer as a
multiset of rows, reals within a tolerance, and fails on any other
difference. The `.smli` alone would only test that Morel is consistent
with itself.

**D7. Missing features are written around, and logged.** Where a query
needs `rollup` or a window function, the Morel version spells it out
(a union of groupings; a self-join or a correlated count for `rank`),
with a comment naming the issue that would make it direct. Each such
feature gets an issue, if it has none; none is built on this branch.
The branch's job is to run TPC-DS, and to produce the list.

**D8. Order and ties.** TPC-DS queries end `order by ... limit 100`, and
several order by a non-unique key, so the hundred rows are not
determined. Where they are not, the Morel translation keeps SQL's order
but the checker compares as a multiset over the whole tie group, and the
script prints the result under an order that is total (adding the
remaining columns as tie-breakers), with a comment saying so.

**D9. Not in the default suite.** `requires: tpcds` keeps the script out
of `./mvnw test` and CI unless the data is present. The weekly Spark job
(`spark.yml`) is where it would run in both modes, once it passes; that
needs the generator in CI, which D1 makes a Maven step rather than an
external tool.

**D10. How nulls are handled in the queries** (open; found by the
survey). *Choice 3's aggregate half is now
[#503](https://github.com/hydromatic/morel/issues/503), filed
2026-11-10: `Relational.avg`, and `sum`, `avg`, `count`, `min`, `max`
over options with SQL's semantics -- `count` over an option skips
`NONE`; `avg` of an empty collection raises `Empty`, as `min` and `max`
do. Comparisons of an option column are not in #503 and still need an
answer here. Whether the queries use helpers until #503 lands, or wait
for it, is the open part.* Three choices:

1. *By hand.* Each aggregate and comparison over an option column
   written out: `sum over (getOpt (x, 0))` (wrong for an all-null group,
   where SQL says null), `case x of SOME v => v > 10 | NONE => false`.
   No language change; the queries get long and unlike the SQL.
2. *Helpers in the script.* A small set of functions defined at the top
   of `tpcds-10m.smli` (`sumOpt`, `avgOpt`, `gtOpt`, ...), so each query
   stays close to its SQL. Spark push-down must then understand them, or
   they must inline to something it does.
3. *In Morel.* Aggregates over options that ignore `NONE` (`sum` of a
   `decimal option bag` is a `decimal option`), and `avg`. A language
   change, so an issue and a design of its own, but it is what the `Sql`
   structure (M6) will need anyway, and the Spark translator could map
   it to Spark's own null semantics directly.

Recommend 2 for this branch, with 3 logged as an issue: the helpers are
the prototype of the built-ins, and the queries written with them will
not change shape when the built-ins arrive.

**D11. Aggregates over options** (decided 2026-11-12, as proposed with
the named `Some` forms; see the log. Revisits #503, which is not yet on
`main`). #503 made `count`, `sum`, `min`, `max`
and `avg` behave differently when their elements are options, so
their answers depend on a type rather than on their definition:

| Expression | Result |
| --- | --- |
| `count [SOME 1, NONE]` | `1` |
| `size (bag [SOME 1, NONE])`, `fun size xs = count xs` | `2` |
| `least (bag [SOME 1, NONE])`, `fun least xs = min xs` | `ClassCastException` |
| `min [SOME 1, NONE]` | `SOME 1` (NONE is least by `compare`) |
| `min [{a = SOME 1}, {a = NONE}]` | `{a=NONE}` |
| `min ([] : int option list)` | `NONE` (`min ([] : int list)` raises `Empty`) |

Today, over empty input and over options:

| Function | Empty, not options | Empty, options | All `NONE` | Some `NONE` |
| --- | --- | --- | --- | --- |
| `count` | 0 | 0 | 0 | counts `SOME`s |
| `sum` | 0 | `NONE` | `NONE` | ignores `NONE` |
| `min`, `max` | raises `Empty` | `NONE` | `NONE` | ignores `NONE` |
| `avg` | raises `Empty` | `NONE` | `NONE` | ignores `NONE` |

`min : 'a bag -> 'a` cannot be both. By its own definition, the least
element by `compare`, `min` of an `int option` list should (a) raise
`Empty` if the list is empty, (b) be `NONE` if all are `NONE`, and (c)
be `NONE` if one is. #503 gives SQL's answers for (a) and (c).

Proposal: two families, each uniform.

* The plain aggregates are parametric, and do not look at the type.
  `count : 'a bag -> int` counts elements; `sum` adds numbers, 0 if
  empty; `min` and `max` order by `compare` and raise `Empty` if empty;
  `avg` (int to real, real, decimal) raises `Empty` if empty.
* The `Opt` aggregates have SQL's semantics, all of one shape: they
  take options, ignore `NONE`, and return `NONE` if there is no `SOME`:
  `minOpt, maxOpt : 'a option bag -> 'a option`, `sumOpt`, `avgOpt`
  (`int option` to `real option`, and so on), and `countOpt : 'a option
  bag -> int` (the number of `SOME`s; SQL's `COUNT` is never null). An
  aggregate over values that are not options, but may be empty, wraps
  them: `avgOpt over SOME e.sal` is `NONE` for an empty input, where
  `avg` raises.

So the overloads that remain are over the number type only, as `+`'s
are. SQL translates one-for-one: `SUM(c)` is `sumOpt over c`,
`COUNT(c)` is `countOpt over c`, `COUNT(*)` is `count over ()`; Spark's
aggregates ignore nulls, so each `Opt` one maps to Spark's. The cost is
a rewrite of #503 before it lands, and of the TPC-DS queries' `sum`,
`min`, `max` and `avg` over option columns. Whatever is decided, the
`ClassCastException` is a bug, and `Relational.compare`'s javadoc (NONE
greatest) contradicts its doc in `relational.sig` and its behavior
(NONE least).

## Milestones

Each is one commit, or a few, squashed on landing as "(#502, part N)".

**M0. Survey.** *Done 2026-11-10, except the generator check (in M1);
see "Survey".* For each of the 99 queries, the features it needs, as a
table in this file: the columns above, filled in exactly, from the
SQL (Spark's copies). Also check: the Java generator at SF 0.01 (size,
null rate, row counts against the spec's scaling); what `Date` can do for
`+ 60 days` and `between`; `stddev_samp`, `like`. Output: this file
updated, issues filed for the gaps (rollup, window functions, and
whatever else turns up), and a batch order for M3 (easiest queries
first).

**M1. Data.** The DDL (`schema.sql`), the test-scope dependency on
`io.trino.tpcds:tpcds:1.4` (airline excluded), `TpcdsData` and
`generate.sh`; the 99 SQL texts copied from Spark's repository with
their provenance; `start-spark.sh --tpcds` and `init-tpcds.py`
(separate from `init.py`, so `scott` does not wait for TPC-DS and the
container's hash covers both); capture of Spark SQL's answer to each
query (D6). Done when `spark.catalog.tpcds;` prints 24 tables with the
types the DDL implies, and every query has a captured answer. M1's
`.csv.gz` headers need M2's adapter types to be readable locally, so M2
can go first or alongside.

*Written 2026-11-10, uncommitted, not yet run* (running the generator
needs Julian; see the log):

* `src/test/resources/tpcds/schema.sql`: 24 tables, 425 columns,
  generated from Spark's `TPCDSSchema.scala`, with 46 `not null`s: each
  dimension's `_sk` and, if it has one, its `_id`; each fact table's
  primary key (`ss_item_sk, ss_ticket_number`, ...; `inventory`'s three
  keys). From the spec's DDL as remembered, not read; the generator
  checks it, failing on a null in a `not null` column.
* Its column lists agree with the generator's (`io.trino.tpcds.column`,
  read from the sources jar) except three names, which `TpcdsData`
  maps: `c_last_review_date_sk` (the queries, q30, say
  `c_last_review_date`), `p_response_targe` and `s_tax_precentage`
  (`dsdgen`'s spellings).
* `tpcds/TpcdsData.java` (test scope): writes `TABLE.csv.gz` (header
  `name:type[ option]`, null as an empty field, a string in single
  quotes if empty or holding `,` `'` `"` or a line ending) and
  `VERSION`; prints each table's rows and per-column nulls. A child
  table (`store_returns`, ...) is written while its parent is generated,
  as the library's own `TableGenerator` does. `TpcdsDataTest` checks
  the schema parse (in the default suite; runs no generator code).
* `tpcds/generate.sh [--dir DIR] [--scale S] [--force]`: default
  `~/.cache/morel/tpcds/sf0.01`; skips if `VERSION` matches; prints
  the directory. Calls `./mvnw test-compile exec:java`.
* `tpcds/queries/`: the 103 files from Spark `v4.0.0` (`fa33ea00`),
  unchanged, with a `README.md` giving provenance.
* `spark/init-tpcds.py`, run by `init.py` (via `runpy`) when `/tpcds`
  is mounted, after `init.py`'s own marker line: creates `tpcds.*` as
  Parquet from the CSVs, with `morel.nullable`; prints rows and nulls
  per table; runs each query and writes
  `/tmp/morel-tpcds/answers/qN.csv` (typed header, `option` on a
  column with a null; names with non-word characters made `_`) or
  `qN.error`. **char/varchar become STRING** in Spark, because Spark
  pads `CHAR(n)` on read and the local reader does not.
* `start-spark.sh --tpcds DIR`: mounts DIR and `src/test/resources/tpcds`,
  includes the TPC-DS files and DIR's `VERSION` in the container hash,
  waits (900s default) for "Morel TPC-DS answers captured", then copies
  the answers to `DIR/answers`.
* *Run 2026-11-10 by Julian.* Generated 24 files, 23MB compressed;
  Spark loaded them (row and null counts in its log). Rows: store_sales
  120,527, catalog_sales 89,807, web_sales 11,876, inventory 261,261,
  date_dim 73,049, time_dim 86,400, and customer_demographics 1,920,800
  (fixed at every scale, so the biggest table locally). Null rates about
  4% in store_sales, 0.03% in web_sales. 99 answers, 20 of them empty
  (q8 q10 q23b q24a q24b q31 q37 q39b q41 q44 q54 q58 q64 q78 q82 q83
  q84 q85 q91 q93), which check little; 4 failed. q35 and q69 ran out of
  memory broadcasting customer_demographics: now `ANALYZE TABLE` after
  each load, and a retry without broadcast joins. q12 and q90 divide by
  zero under ANSI: the error is the answer (Morel's `/` raises too), and
  the non-ANSI answer is kept as `qN.ansi-off.csv`. Rerun the same
  day: q35 (100 rows) and q69 (31) pass, so 101 answers and 2 errors.
  **M1 done**, uncommitted.
  Locally, `./morel --directory=DIR` reads every table; counts agree
  with Spark's (store_sales 120,527 rows, 5,450 null `ss_quantity`).
* Locally, `file` over the data directory (`Sys.set ("directory",
  ...)`) gives `file.store_sales` and so on; D5's `requires: tpcds`
  binding is M3's.

**M2. Local data (D4).** *Done; committed 2026-11-10 as `3a7f5f03`.*
The file-adapter change, with its own tests
and docs, independent of TPC-DS; then `file.data` over the cache
directory gives the same types as `spark.catalog.tpcds`. Done when a
`val` of the schema type binds from both. Could land on `main` ahead of
the rest.

**M3. Schema and queries.** `tpcds-10m.smli` begins with the schema
type (Claude, from the DDL, checked against both bindings), then the
queries in M0's batches. Each query: the SQL in a comment, the Morel,
the expected output, and a pass of the checker. A query Morel cannot yet
express is written around (D7), or, failing that, left with its SQL and
a comment naming what is missing -- never dropped. Done when all 99 are
present and the checker agrees on every one that runs.

*In progress 2026-11-11.* `script/tpcds-10m.smli`, `requires: tpcds`:
the system property `morel.tpcds` names the data directory, and
`ScriptTest.testScriptTpcds` (its own method, 30-minute timeout) runs
the script with `directory` set to it, so `val tpcds = file`. Queries
in numeric order, each `fun qN () = ...;` then `val rN = qN ();`, with
the SQL in a comment above. `TpcdsChecker` (test scope) watches each
`rN` through a new `Tracer.onBinding` hook and compares it with
`answers/qN.csv` as a multiset: columns by name ignoring case (one
leftover column pairs with one leftover field, for Spark's `count(1)`),
`NONE` as an empty field, decimals exactly, reals within 1e-5. So the
oracle check is part of the test, not a separate Python step. (Tier A
is now complete; see the log.) The first 18 tier-A queries (q3 q15
q19 q38 q42 q45 q46 q52 q55 q68 q73 q79 q84 q87 q91 q93 q96, and q90,
which raises `Div` as Spark raises `DIVIDE_BY_ZERO`) ran in about 4s.
D10 is answered by hand, with no helpers: `col = SOME c`, `>`, `>=`
and `between` drop `NONE` as SQL does; a lone `<`, `<=`, `<>` or a
function of a column gets a guard. No schema check yet: ascribing a
type to `file`'s progressive record fails
("conflict ... vs {z$dummy:unit}").

*Task (2026-11-11): review the Morel of `tpcds-10m.smli` for idiom.*
It was written query by query, to agree with Spark, and reads like
translated SQL. For example: chains of `orelse` and `elem` over a
column (q41, q48, q91) may read better as `case` expressions; `@`
joins collections where `union` says what is meant (q2, q4, q11, q66,
q74); helpers such as `ratio`, `divide` and `times` are redefined in
several queries; and the guards for `NONE` (`x <> NONE andalso x <
SOME c`) are repeated. Settle the idioms, then apply them to every
query, checking that each still agrees with Spark.

*Review done 2026-11-12.*
* `@` became `union` (q2, q4, q5, q11, q14a, q66, q74, q77, q80).
* `day` and `lift2` are defined once, at the top of the file.
  `lift2 (op -) (a, b)` is SQL's `a - b`, `NULL` if either operand
  is. `lift2` replaces the many copies of `minus` and `divide`, and
  `times` is defined using it. This needed operator sections to work
  on `decimal`, which they now do.
* The type annotations that worked around overloaded arithmetic in a
  `let` are gone.
* A chain of `orelse` whose branches test disjoint keys became a
  `case`: q13, q48 and q85 (marital status), q41 (category), q47 and
  q57 (year and month), q88 (dependants). Chains over lists of states
  that overlap (q13), or that would need or-patterns (q48, q85, q53),
  stayed as they were; Morel has no or-patterns.
* q49 defines its ranking once, now that the crash with a correlated
  subquery in a function called more than once (#508) is fixed.
* q51 still needs two copies of `running`: Morel does not generalize a
  let-bound function whose body is a query.
* The guards for `NONE` stay; the header of the file explains them.

*M3 completed 2026-11-12; see the log.*

**M4. Local pass.** Fix what M3 found in Morel itself (wrong answers,
crashes, performance -- a nested-loop join over `store_sales` at SF 0.01
is about 29K rows, so a cross product of three fact tables is not
viable and the optimizer must find the joins). Each fix with a test of
its own outside TPC-DS. Done when the script passes locally.

**M5. Spark pass.** Run with `--spark`, `sparkFallback=false`. Each
refusal is a translator gap: fix it here if small, else an issue.
Expect: `Date` functions, `Option` handling (`IS NULL`, three-valued
`where`), decimal aggregates, set operations (with #501), subqueries.
Done when the script passes in both modes, or every remaining refusal
has an issue and a comment in the script.

**M6. `Sql` structure.** Separate design (`spec.md`) once M3 has shown
what a translation looks like: `Sql.schema : string -> type`, or rather
a function from DDL to a value describing the type, and `Sql.query`. The
99 hand translations become its oracle: the generated Morel must give
the same answers. Likely its own issue; not required to close #502.

## Survey

*2026-11-10. Source: `~/dev/spark` (a clone of `apache/spark`),
`sql/core/src/test/resources/tpcds/`, 103 files: the 99 queries, with
14, 23, 24 and 39 in two parts. Identical at `v4.0.0` and at `master`
(`f093bdfa9ad`), so pin `v4.0.0`, the container's version. The files
carry no licence header. Spark's schema is
`sql/core/src/test/scala/org/apache/spark/sql/TPCDSSchema.scala`: types
match the spec (`DECIMAL(7,2)`, `INT`, `CHAR(n)`, `DATE`) but it has no
`NOT NULL` at all, so D3's key columns come from the TPC-DS DDL itself.
Spark also has `tpcds-v2.7.0/` (newer variants such as `q5a`, `q10a`)
and `tpcds-query-results/` (answers at SF 1, not ours).*

Features were found by regular expressions over the lower-cased text,
so a count can be off by one where a word appears in a comment or a
string; spot-checked for dates, `like` and `stddev_samp`.

**Batches for M3**, by tier, smallest first within a tier (the number
is the query's length in words):

* **A** (53): q96 (40), q3 (42), q55 (42), q52 (46), q42 (48), q15 (60),
  q84 (61), q93 (62), q19 (72), q45 (80), q87 (82), q38 (85), q79 (85),
  q91 (85), q90 (95), q68 (100), q46 (102), q73 (104), q25 (108), q29
  (110), q76 (114), q43 (117), q34 (121), q97 (121), q69 (128), q71
  (134), q61 (140), q8 (148), q10 (150), q48 (155), q62 (156), q99
  (156), q54 (170), q33 (181), q50 (181), q60 (182), q74 (186), q56
  (187), q83 (194), q23a (198), q11 (206), q31 (208), q2 (217), q59
  (226), q23b (228), q78 (232), q75 (244), q58 (248), q41 (272), q64
  (292), q4 (331), q88 (515), q66 (961)
* **B** (25): q7 (64), q26 (64), q37 (66), q82 (66), q1 (67), q32 (67),
  q92 (72), q6 (74), q94 (88), q24a (93), q24b (93), q65 (96), q16 (97),
  q30 (97), q81 (104), q95 (104), q40 (105), q21 (112), q35 (119), q72
  (154), q13 (179), q9 (197), q85 (206), q28 (251), q14b (333)
* **C** (25): q22 (48), q98 (65), q12 (67), q20 (67), q27 (71), q86
  (74), q36 (92), q67 (94), q89 (101), q39a (119), q18 (120), q39b
  (123), q17 (125), q70 (125), q44 (142), q53 (145), q63 (150), q57
  (177), q47 (190), q51 (194), q80 (307), q77 (324), q14a (372), q49
  (375), q5 (406)

Tier A needs none of `avg`, `count(distinct)`, date arithmetic,
`rollup`, window functions or `stddev_samp`; tier B needs one of the
first three, which Morel can do by hand; tier C needs one of the last
three, which it cannot do directly. Every tier needs D10.

| Query | Words | Needs |
| --- | ---: | --- |
| q1 | 67 | cte, avg, scalar-subq |
| q2 | 217 | cte, union |
| q3 | 42 |  |
| q4 | 331 | cte, union |
| q5 | 406 | cte, rollup, date+, union, outer |
| q6 | 74 | avg, scalar-subq |
| q7 | 64 | avg |
| q8 | 148 | intersect |
| q9 | 197 | avg |
| q10 | 150 | exists |
| q11 | 206 | cte, union |
| q12 | 67 | window, date+ |
| q13 | 179 | avg |
| q14a | 372 | cte, rollup, avg, union, intersect, in-subq, scalar-subq |
| q14b | 333 | cte, avg, union, intersect, in-subq, scalar-subq |
| q15 | 60 |  |
| q16 | 97 | cntdist, date+, exists |
| q17 | 125 | avg, stddev |
| q18 | 120 | rollup, avg |
| q19 | 72 |  |
| q20 | 67 | window, date+ |
| q21 | 112 | date+ |
| q22 | 48 | rollup, avg |
| q23a | 198 | cte, union, in-subq |
| q23b | 228 | cte, union, in-subq |
| q24a | 93 | cte, avg, scalar-subq |
| q24b | 93 | cte, avg, scalar-subq |
| q25 | 108 |  |
| q26 | 64 | avg |
| q27 | 71 | rollup, avg |
| q28 | 251 | cntdist, avg |
| q29 | 110 |  |
| q30 | 97 | cte, avg, scalar-subq |
| q31 | 208 | cte |
| q32 | 67 | avg, date+, scalar-subq |
| q33 | 181 | cte, union, in-subq |
| q34 | 121 |  |
| q35 | 119 | avg, exists |
| q36 | 92 | rollup, window |
| q37 | 66 | date+ |
| q38 | 85 | intersect |
| q39a | 119 | cte, avg, stddev |
| q39b | 123 | cte, avg, stddev |
| q40 | 105 | date+, outer |
| q41 | 272 |  |
| q42 | 48 |  |
| q43 | 117 |  |
| q44 | 142 | window, avg |
| q45 | 80 | in-subq |
| q46 | 102 |  |
| q47 | 190 | cte, window, avg |
| q48 | 155 |  |
| q49 | 375 | window, union, outer |
| q50 | 181 |  |
| q51 | 194 | cte, window, outer |
| q52 | 46 |  |
| q53 | 145 | window, avg |
| q54 | 170 | cte, union |
| q55 | 42 |  |
| q56 | 187 | cte, union, in-subq |
| q57 | 177 | cte, window, avg |
| q58 | 248 | cte, in-subq, scalar-subq |
| q59 | 226 | cte |
| q60 | 182 | cte, union, in-subq |
| q61 | 140 |  |
| q62 | 156 |  |
| q63 | 150 | window, avg |
| q64 | 292 | cte |
| q65 | 96 | avg |
| q66 | 961 | union |
| q67 | 94 | rollup, window |
| q68 | 100 |  |
| q69 | 128 | exists |
| q70 | 125 | rollup, window, in-subq |
| q71 | 134 | union |
| q72 | 154 | date+, outer |
| q73 | 104 |  |
| q74 | 186 | cte, union |
| q75 | 244 | cte, union, outer |
| q76 | 114 | union |
| q77 | 324 | cte, rollup, date+, union, outer |
| q78 | 232 | cte, outer |
| q79 | 85 |  |
| q80 | 307 | cte, rollup, date+, union, outer |
| q81 | 104 | cte, avg, scalar-subq |
| q82 | 66 | date+ |
| q83 | 194 | cte, in-subq |
| q84 | 61 |  |
| q85 | 206 | avg |
| q86 | 74 | rollup, window |
| q87 | 82 | except |
| q88 | 515 |  |
| q89 | 101 | window, avg |
| q90 | 95 |  |
| q91 | 85 | like |
| q92 | 72 | avg, date+, scalar-subq |
| q93 | 62 | outer |
| q94 | 88 | cntdist, date+, exists |
| q95 | 104 | cte, cntdist, date+, in-subq |
| q96 | 40 |  |
| q97 | 121 | cte, outer |
| q98 | 65 | window, date+ |
| q99 | 156 |  |


## Risks

* **Performance locally.** Morel's local evaluator over the fact tables
  may be too slow for 99 queries; M4 may turn into optimizer work. Find
  out in M3's first batch, not at the end.
* **`real` is 32-bit.** Ratios and `avg`/`stddev` over `real` will not
  match Spark's `double` digits; D6's tolerance covers comparison, but
  the printed expected output will differ between local and Spark if
  Spark's narrowing differs from Morel's arithmetic. May force rounding
  in the queries.
* **`int` is 32-bit.** Keys fit at SF 0.01; sums of quantities likely
  do; `bigint` columns raise `Overflow` rather than wrap, which is
  safe.
* **Licences.** The generator (Apache 2.0) and Spark's query copies
  (Apache 2.0) are clear; the TPC's EULA still covers the benchmark's
  name and the publication of results. Nothing here publishes a
  benchmark result, but say "derived from TPC-DS" in the script, as
  Spark does.
* **The generator's fidelity.** Trino's port claims to match `dsdgen`;
  we do not depend on that, because the oracle runs over our data. It
  matters only if someone compares our numbers with another
  implementation's.

## Log

* 2026-11-08: plan written. Branch at `main`; no code yet. Maturity:
  design.
* 2026-11-08: Julian confirmed D2, D3, D6 and asked for a Java or Spark
  generator instead of DuckDB. D1 is now Trino's Java port 1.4,
  checked by reading the jar (class version, API, scale validation);
  running it was not allowed in this session, so it is unrun.
* 2026-11-08: **M2 done, uncommitted**, `fullMake` green (663 tests).
  `Files.java`: a header type is `bool`, `int`, `real`/`double`,
  `decimal`, `date` or `string`, optionally followed by `option`;
  `decimal` is now `decimal` (was `real`), `date` is `Date.date` at
  midnight UTC (was `string`), `bool` has a parser (it threw before);
  in an option column `NULL` or an empty field is `NONE`, elsewhere it
  raises `Fail` (was `0`); a quoted field may hold commas and a doubled
  quote, and a trailing empty field is kept. `scott/emps.csv.gz` now
  declares `mgrno` and `comm` as options (they hold `NULL`s), which
  changes `file.smli`'s printed types; new `data/types/` with `all.csv`
  and `nulls.csv`. This could land on `main` alone, as "The file reader
  reads decimal, date and option columns (#502, part 1)".
* 2026-11-10: tuned the reader. `LineSplitter` (an instance, reusing
  its field list and buffer; a field is a substring unless it holds a
  doubled quote), an indexed loop instead of a capturing lambda per
  row, and a fast path for `yyyy-mm-dd` dates. A 1M-row, 6-column file
  reads in about 0.43s, from 0.55s; the date parsing was most of it.
  New `eval/FilesTest`. `fullMake` green, 666 tests. Uncommitted.
  Then `Column` holds its name, and `LineSplitter` became `LineParser`,
  which owns the column parsers and order and writes each value
  straight into the row's array: no list of fields at all, and a short
  line raises `Fail` naming the counts. An int/string file reads in
  0.30s per million rows, from 0.37s originally.
* 2026-11-10: `Column` holds its name; `LineParser.parse(line,
  targets, values)`, with `valueAs` computing the targets by sorting
  `Ord.zip(columns)` by name. `fullMake` green (667 tests); committed
  as `3a7f5f03`, "The file reader reads decimal, date and option
  columns (#502, part 1)". Not pushed.
* 2026-11-10: M0 survey done from Julian's clone of Spark
  (`~/dev/spark`); see "Survey". Found that Morel has no `avg`, cannot
  `sum` or compare an option, and has no `stddev`; opened D10.
* 2026-11-10: worked `select deptno, avg(mgr) from emp group by
  deptno` five ways against `scott.emps` and the offline Spark
  connection; only the filter-then-sum form plans on Spark, and it
  drops all-null groups. Julian filed the result as #503 (`avg`, and
  aggregates over options).
* 2026-11-10: #503 cherry-picked onto this branch (`efb28eb9`). M1
  written (see M1), not run: the session may not run the downloaded
  generator, so Julian runs `generate.sh`, then `start-spark.sh --tpcds
  ~/.cache/morel/tpcds/sf0.01`. The temporary `ExploreTest` was dropped:
  `TpcdsData` prints the same counts. It also showed that the
  library's iterator yields parent and child rows together, which
  `ExploreTest` would have miscounted.
* 2026-11-10: M3's first query, q96 (a four-way join, `count(*)`),
  does not finish locally: over 10 minutes, killed. The local evaluator
  joins by nested loops (store_sales 120K x household_demographics 7,200
  x time_dim 86,400 x store 2), and has no hash join. `hybrid=true` does
  not help: the file adapter's tables are constants, not Calcite
  relations, so the plan stays Morel's. Also found: options are ordered
  (`NONE < SOME x`), so `x >= SOME 30` has SQL's meaning but `x < SOME
  30` keeps the nulls. Blocked on a decision about local joins (M4
  brought forward); asked Julian.
* 2026-11-11: #504 (hash join) and #505 (`where` into joins)
  cherry-picked; q96 runs in about 5s and agrees with Spark (0). M3
  started; see M3. Two Morel fixes, each with a test in `file.smli`:
  `?.` on the right of a `left join` now learns a file table's fields,
  as `.` does (it raised "no field" if nothing had read the table
  before); and a file table is read when the code that scans it runs,
  not when it is compiled, with the rows kept behind a soft reference
  and shared. Before that, each `fun qN` held its own copy of every
  table it scans, and 14 of them ran out of heap.
* 2026-11-11: part 3 committed (`8de37f32`). Tier A continued: 39
  queries agree (35s). Found and fixed, each with an `optimize.smli`
  test: (1) a `where` with a correlated `exists` was wrapped by the
  resolver in `let val v = r in ... end`, so `FILTER_INTO_JOIN` saw one
  unsafe conjunct and left q69 and q10 a cross product of customer,
  address and customer_demographics; it now splits the conjuncts under
  such a `let`; (2) the inliner substituted a `let` value used once
  into a query's per-row expression, so q83's `weeks` query ran once
  per `date_dim` row; `Analyzer` now tracks depth (lambdas, and
  filter/project/sort/join/group expressions) and makes such a use
  `ONCE_UNSAFE` unless the value is a lambda, simple or constant. That
  changed one plan in `hybrid.smli` and `AlgebraTest
  .testCalciteWithVariable` (both now compute the value once). Still
  open for M4: factoring a conjunct common to the arms of an `orelse`
  (q48 is written factored by hand). The checker now also fails a
  query that is defined but never checked (a stray `(*` in a comment
  had silently hidden 29 of them). Decimals that Morel computes to
  more places than Spark (after a division) must agree to 15 digits.
  Dates are compared with values built by `Date.date`, not by
  formatting the column.
* 2026-11-11: 45 queries agree (42s); 8 of tier A left (q23a q23b q58
  q64 q66 q75 q78 q88). More for M4: (1) in a `let`, `fun divide (a, b)
  = ... SOME (x / y)` takes `/` as `real`, yet type-checks when called
  with `decimal option`s and then fails at run time
  (`ClassCastException`, BigDecimal to Float); q31 and q59 annotate
  `a : decimal option`. (2) `(*` inside a block comment is lexed
  unlike SML: `(* ... count(*) ... *)` ends on that line, which
  neither SML '97 (comments nest) nor SML/NJ and MLton (a `(*)` line
  comment runs to the end of the line) would do, and a double quote
  in the comment changes the result. (3) `exists` (compiled as
  `Relational.nonEmpty` of a correlated query) runs the subquery
  once per outer row; when its correlation is equalities between
  inner and outer columns it could be a semi-join (`not exists`: an
  anti-join) that evaluates the subquery once and probes a hash set.
  q69 takes 13s, most of it in three such subqueries.
* 2026-11-11: semi- and anti-joins for `exists` (`edaa8b76`; q69 13s
  to 6s), and #506 (comments in the shell, `ed83c2a5`); pushed. **Tier
  A done**: all 53 queries agree with Spark (52 values and q90's
  error); the script runs in 39s. More for M4: (4) Morel joins
  comma-separated scans in the order written; q64 lists `d2` and `d3`
  before `customer`, the only table they join to, and as written was a
  cross product with `date_dim`, so the script moves `store` and
  `customer` up, with a comment. (5) The `let`-bound arithmetic bug
  (1) again in q75 (`minus`, used for counts and amounts). The checker
  matches a repeated answer column (q64's `s1`) to a field `s1_2`, and
  a decimal that Morel computes to more places than Spark to within a
  relative 1e-9 (q66's ratios). Next: tier B.
* 2026-11-12: part 5 committed (`af491aea`); D11 (aggregates over
  options) written up, in `/tmp/avgOpt.md` too. **Tier B done**: all
  78 queries of tiers A and B agree (77 values and q90's error); the
  script runs in about 105s, half of it q35. Fixed on the way, each
  with a test: (6) `TypeResolver` kept an earlier attempt's terms and
  constraints when it retried after expanding a progressive type, so an
  overload (`avg`) over a file table's column, in the first statement
  that read it, was left unresolved and the statement returned a
  function (`file.smli`); (7) the shell's line reader did not know that
  a line continues a block comment, so a quote on it opened a literal
  and the shell waited (`ShellTest`, following #506). For M4: (8) two
  `exists` joined by `orelse` (q35) are not semi-joins, and run per
  row (47s); (9) `x elem q`, for an uncorrelated query, searches a
  list per row; q14b writes it as `exists`, which is a semi-join; (10)
  q72's joins are reordered by hand, as q64's. Next: tier C, which
  needs `rollup` or window functions.
* 2026-11-12: D11 decided and done, on `503-avg` (`c2e75c71`) and
  cherry-picked here: plain aggregates do not look at whether their
  elements are options; `sumOpt`, `avgOpt`, `minOpt`, `maxOpt` return
  NONE on an empty collection (#256); `countSome`, `sumSome`,
  `avgSome`, `minSome`, `maxSome` are SQL's aggregates, and map to
  Spark's; `somes` drops NONE. The named `Some` forms, rather than
  compositions such as `sumOpt o somes`, because an overloaded function
  (`avgOpt`) cannot yet be composed with `o`. The TPC-DS queries now
  use the `Some` forms; all 78 still agree.
* 2026-11-12: **M3 done**: tier C written, so all 103 query files are
  in `tpcds-10m.smli`; 101 values agree with Spark, and q12 and q90
  raise `Div` as Spark raises `DIVIDE_BY_ZERO`; the script runs in
  about 100s. Written around (D7): `rollup` (11 queries) as a union of
  groupings; window functions (15) as a grouped query joined back, a
  rank as a count or a sort with `ordinal`, a running total as a fold;
  `stddev_samp` (3) as a function. Issues drafted, not filed:
  `/tmp/rollup.md`, `/tmp/window.md`, `/tmp/stddev.md`. More for M4:
  (11) a `let`-bound function whose query reads its argument twice,
  called twice, raises `ArrayIndexOutOfBoundsException` (on `main`
  too): `let fun r xs = from x in xs yield 1 + count (from y in xs
  where y < x) in (r [1, 2], r [3]) end`; q49 writes it out. (12)
  Such a function is not generalized over two record types (q51 has
  two copies). (13) q51's value has type `forall 'a. {d_date: 'a
  option, ...} list`. (14) A backquoted label prints without its
  backquotes (`sum(sales)=6492`). (15) q18's joins reordered, as
  q64's. Next: M4.
* (Superseded: M0's part is done, M1's is now Julian running it.)
  Blocked for M0 and M1: fetching Spark's TPC-DS query files
  (`gh api`) and running the downloaded generator jar were both denied
  by the session's permission checks. Next session needs permission to
  read `apache/spark` on GitHub, and to add `io.trino.tpcds:tpcds:1.4`
  as a test dependency and run it through Maven.
