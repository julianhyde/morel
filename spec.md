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
# Spec: Morel on Spark, phase 1

The design behind milestones M2 (type mapping), M3 (the `Spark`
structure) and M4 (function inventory) of the plan in `plan.md`. The
plan says what and when; this says how. Sections marked *open* need a
decision before the milestone that depends on them.

## 1. Types

### 1.1 Spark to Morel

A Spark type maps to a Morel type as follows. The mapping is total on
the types Spark can store in a table, except where marked *rejected*;
a rejected column makes its table unreadable, and the catalog reports
why.

| Spark                  | Morel          | Notes                                    |
| ---------------------- | -------------- | ---------------------------------------- |
| `boolean`              | `bool`         |                                          |
| `tinyint`, `smallint`  | `int`          | Widened on read; narrowed on write       |
| `int`                  | `int`          |                                          |
| `bigint`               | `int`          | Raises `Overflow` if the value does not fit; phase 3 adds a 64-bit type |
| `float`, `double`      | `real`         | `float` widened; Morel `real` is a Java `float`, so `double` is narrowed (see 1.4) |
| `decimal(p, s)`        | `real`         | Until phase 3 adds `decimal`             |
| `string`, `char(n)`, `varchar(n)` | `string` | Trailing spaces of `char(n)` kept   |
| `binary`               | `word list`    | One `word` per byte, 0 to 255            |
| `date`                 | `Date.date`    | Midnight, no zone; see 1.3               |
| `timestamp`            | `Time.time`    | An instant; microsecond precision        |
| `timestamp_ntz`        | `Date.date`    | Wall-clock time, no zone; see 1.3        |
| `array<t>`             | `t list`       | Element nullability per 1.2              |
| `struct<f1: t1, ...>`  | `{f1: t1, ...}` | Fields matched by name; Morel sorts labels |
| `map<k, v>`            | *rejected*     | Phase 3; candidate `(k * v) list`        |
| `interval ...`         | *rejected*     |                                          |
| `void`                 | *rejected*     |                                          |
| a table                | `{...} bag`    | Rows are unordered                       |

### 1.2 Nullability

Spark tracks nullability at every level: a column, an array element
(`containsNull`), a struct field, a map value. Morel has no null; the
type that admits "no value" is `option`. The mapping rule is:

> a nullable Spark type `t` maps to `m option`, where `m` is the Morel
> type of `t` as if non-null,

at every level, so `array<int>` with nullable elements is
`int option list`, and a nullable struct with a nullable field `a` is
`{a: int option, ...} option`.

**Catalog columns.** Spark's built-in catalog keeps no NOT NULL
constraint for tables stored as files (the M0 finding), so every column
of every ordinary table is nullable, and under the rule above every
column of `emp` is an `option`. The seed queries then do not typecheck:
`e.sal > 1000.0` compares a `real option` with a `real`. Two ways out:

* **A. Strict.** Keep the rule. Give the catalog a way to declare a
  column non-null, and give queries a cheap way to unwrap (`Option.valOf`
  today; perhaps a `?` postfix later). Honest, and the decoder never
  fails; but every query over a real table pays.
* **B. Trusting.** A catalog column maps to the plain type, and the
  decoder raises an exception (`Spark` with class `NULL_VALUE`, say)
  when a null arrives. Columns nested inside `array` and `struct` still
  follow the rule, because there the flag is meaningful. Queries read
  as in the seed script; a table with nulls fails at run time, on the
  column, with a message naming it.

**Decided: B**, as a mapping decision rather than a language one, to
be revisited when Morel has better `option` ergonomics. The pure
mapping function (1.5) still takes the flag as an argument, so the
strict mapping stays testable.

### 1.3 Dates and times

`Time.time` is an instant, measured from the epoch, and matches Spark's
`timestamp` (`TIMESTAMP_LTZ`): an instant that Spark renders in the
session time zone. Decoding uses the Arrow value, which is an instant
in microseconds, independent of the session zone.

`Date.date` is a broken-down civil time, without a zone. It matches
`timestamp_ntz`, and it holds a `date` as the midnight of that day.
`Date.date` to `date` on the way back drops the time of day. There is
no Morel type for a day alone; if that proves to matter, phase 3 can
add one.

The container runs in UTC (`spark.sql.session.timeZone`), so a script
that prints a timestamp gets the same text everywhere.

### 1.4 Precision

Morel's `real` is a 32-bit float, and Spark's `double` is 64-bit, so
`double` narrows on read. This is the same lossy step the Calcite path
takes today. A query computed in Spark (say, `sum over e.sal`) is
computed in `double` and narrowed once, at the end; the same query
computed locally is computed in `float` throughout. The triple tests
of M11 therefore compare reals with a tolerance, or use values that are
exact in both.

### 1.5 The mapping function, and how it is tested

The mapping is one pure function in the adapter, from a Spark schema
to a Morel type. Spark states a schema two ways, and both are accepted:

* DDL: `id INT NOT NULL, s STRING, st STRUCT<a: INT, b: STRING>`
* JSON: the `{"type": "struct", "fields": [...]}` form that
  LocalRelation carries and that `DataFrame.schema.json()` prints.

The pure half of M2 is a `.smli` test that exercises the function
without a cluster, through an offline connection (2.5): each seed
table schema, the `zoo` schema, nullability at each level, and every
rejected type with its message. The live half browses `zoo` on the container,
prints its type and its rows.

### 1.6 Morel to Spark

The reverse mapping is needed for LocalRelation (M9) and for literals
in plans. It is the inverse of 1.1 on the image of 1.1: `int` to
`int`, `real` to `float` (not `double`: the value is a float, and
widening it would invent digits), `string`, `bool`, `Time.time` to
`timestamp`, `Date.date` to `timestamp_ntz`, `word list` to `binary`,
`t list` to `array<t>`, records to structs, `t option` to nullable
`t`. A `char` is a one-character `string`. `unit` is a struct with no
fields. Tuples are structs with fields `1`, `2`, ... A datatype other
than `option` crosses as a tagged struct: `{tag: string, a: ..., b:
...}` with one nullable field per constructor that has an argument.
Function types, and types containing them, are rejected.

## 2. The `Spark` structure

### 2.1 Signature

```sml
signature SPARK = sig
  (* A connection to a Spark Connect server. *)
  type connection

  (* A Spark plan that computes a value of type 'b from an argument of
     type 'a. The type parameters are phantom: they record what the
     plan takes and computes, and nothing else. A plan with no
     parameters has 'a = unit. *)
  type ('a, 'b) plan

  (* Raised by any Spark operation that fails. errorClass is Spark's
     error class, such as DIVIDE_BY_ZERO or TABLE_OR_VIEW_NOT_FOUND;
     message is Spark's message. *)
  exception Spark of {errorClass: string, message: string}

  (* Opens a connection to the server at the given URI, such as
     "sc://localhost:15002". *)
  val connect : string -> connection

  (* Opens a connection to the server named by the SPARK_REMOTE
     environment variable. *)
  val connectDefault : unit -> connection

  (* Closes a connection. Any later use of it, including forcing a
     value that was read from it lazily, raises Spark. *)
  val close : connection -> unit

  (* Wraps a function that needs a connection in one that opens the
     default connection (as connectDefault does), passes it, and
     closes it afterwards, whether or not the function raised. So
     "using (fn (c, x) => x + 1)" is a function that, applied to 4,
     returns 5. *)
  val using : (connection * 'a -> 'b) -> 'a -> 'b

  (* The root of the connection's catalog: a progressively typed
     record whose fields are catalogs, then databases, then tables. A
     table is a bag of records. *)
  val catalog : connection -> {...}

  (* Converts a function into a plan that computes the same function
     on the connection. This is an intrinsic: it operates on the parse
     tree of its argument, as Plan.core does, and does not evaluate
     it. *)
  val prepare : connection * ('a -> 'b) -> ('a, 'b) plan

  (* Executes a plan on an argument and returns the result. *)
  val execute : ('a, 'b) plan * 'a -> 'b

  (* The plan as text, in the rendering that seed-plans.txt fixes:
     protobuf text format with plan ids renumbered in order of first
     appearance, and a LocalRelation's payload replaced by its row
     count. The argument's value is not part of the text; where the
     plan uses it, the text shows a parameter marker. *)
  val toString : ('a, 'b) plan -> string

  (* Prepares a function, and returns a function of the same type
     that executes the plan on the connection. *)
  val remote : connection * ('a -> 'b) -> 'a -> 'b
end
```

`connection` and `('a, 'b) plan` are opaque. `catalog`, `close`,
`prepare` and `remote` are methods on `connection`, and `execute` and
`toString` on `plan`, so a script reads

```sml
val spark = Spark.connectDefault ();
val q = spark.prepare (fn () =>
  from e in spark.catalog.emp where e.sal > 1000.0);
q.toString;
q.execute ();
val byDept = spark.prepare (fn d =>
  from e in spark.catalog.emp where e.deptno = d);
byDept.execute 10;
spark.close ();
```

A plan is a function, and a query with no parameters is a function of
`unit`. `using` gives a function its connection for the duration of
one call:

```sml
val clerks = Spark.using (fn (spark, job) =>
  (spark.remote (fn j => from e in spark.catalog.emp where e.job = j)) job);
clerks "CLERK";
```

The argument is the only thing that varies between executions
of a plan; everything else the function refers to is fixed when the
plan is prepared (2.2).

### 2.2 What `prepare` accepts

`prepare` captures its second argument's parse tree, which in phase 1
must be a function expression, `fn pat => body`. It resolves and types
the function in the current environment, and translates the body's
core to a Spark plan in which the parameter is a placeholder. Free
variables of the body other than the parameter are bound in the
environment at that point, and their values cross the boundary
according to 1.6 when the plan is prepared: a table read from the
catalog becomes a table scan, and any other collection becomes a
LocalRelation. At `execute`, the argument crosses the same way, as a
literal or a LocalRelation, and the plan runs.

A function bound to a name (`prepare (spark, f)`) is not accepted in
phase 1, because the compiler has the name's value, a closure, and not
its parse tree; this is the same limit as `Plan.core`
([#470](https://github.com/hydromatic/morel/issues/470)), and lifts
with it.

Phase 1 accepts an expression whose core is relational: a `from` whose
steps and scalar expressions the translator handles (M4 says which),
over catalog tables and local collections. Everything else is
rejected at `prepare` time with `Spark {errorClass = "UNSUPPORTED",
message = ...}` naming the construct or function. There is no partial
push-down in phase 1: a plan is all Spark or it is an error.

A non-collection result (an `int`, a record) is computed as a
single-row, single-column relation, and `execute` unwraps it.

`remote` is `prepare` followed by a function that calls `execute`;
it is the "equivalent value" of the issue: a function of the same
type that runs on Spark.

### 2.3 Lifecycle

`connect` opens a gRPC channel and a Spark Connect session; `close`
releases both. There is no pooling. The adapter keeps a registry of
open connections so that a shutdown hook can close them and the test
harness can check that a script closed what it opened; a connection
that is garbage-collected while open logs a warning.

### 2.4 Errors

Every failure raises `Spark`. The `errorClass` is Spark's, taken from
the `ErrorInfo` in the gRPC status (or `"CONNECTION"` for transport
failures, `"UNSUPPORTED"` for translation). M10 maps some classes to
Morel's own exceptions so that a query raises the same exception
remotely as locally: `DIVIDE_BY_ZERO` to `Div`, `ARITHMETIC_OVERFLOW`
and `CAST_OVERFLOW` to `Overflow`. After any error the connection is
usable.

### 2.5 The offline connection

Translation must be testable without a cluster, but the signature
does not need a function for it: `connect` accepts a URI, and the
adapter recognizes the scheme `mock:` as a connection to no server.

The offline connection's catalog is the session's foreign data sets:
the values that the environment binds from `--foreign`, such as
`scott`. Each data set is a database, and each of its tables a table,
so `spark.catalog.scott.emps` on the offline connection is the
environment's `scott.emps`. The test container seeds a `scott`
database with the same tables and rows (M0), so on a real connection
`spark.catalog.scott.emps` has the same type and the same values, and
a script written against it runs unchanged on either.

`prepare` works normally on the offline connection, and `toString`
gives the plan text, so a translation is checked against the golden
plans without a cluster. `execute` evaluates the expression that was
prepared, locally, in the Morel interpreter. So one script with one
expected output is checked three ways: local evaluation, offline (the
plan text, then the same values by local evaluation), and live. What
the offline connection cannot catch is a translation that yields a
wrong plan with a plausible shape; the golden plan text guards that,
and a live run is the final word.

Scripts that need a live server connect to `SPARK_REMOTE` instead,
and skip when it is unset.

### 2.6 The `spark` value

Nothing is opened at startup. A script or a shell session calls
`connect` or `connectDefault`. When Morel runs inside Spark (phase 2),
a `spark` value is bound to the enclosing session's connection.

## 3. Functions and operators

### 3.1 The table

The translator consults one table, kept as data so that the Rust and
Go ports can share it. Each entry classifies a Morel built-in:

* **direct**: the same function exists in Spark with the same
  semantics; emit `unresolved_function` with the given name.
* **rewritten**: emit an expression template over the arguments.
* **aggregate**, **subquery**: handled structurally by the translator
  (a `compute` clause, a subquery expression); listed so that the
  table is complete.
* **unsupported**: `prepare` rejects the expression.

An entry may carry a **divergence** note: the translation is the
closest Spark offers, but differs in a stated way, and a triple test
documents the difference.

The table is a Morel value, a list of records, in a file the
translator loads at startup. The columns are `morel` (the built-in's
name), `spark` (the function name or template), `kind`, and
`divergence`. Its first contents are below.

### 3.2 Contents

Comparison and logic:

| Morel                | Spark        | Kind    | Divergence                       |
| -------------------- | ------------ | ------- | -------------------------------- |
| `=`, `<>`            | `==`, `!=`   | direct  | On `real`: Spark has NaN = NaN   |
| `<`, `<=`, `>`, `>=` | same         | direct  | Strings: Spark compares UTF-8 bytes, Morel UTF-16 units; they differ only beyond the BMP. Reals: Spark orders NaN above all |
| `andalso`, `orelse`  | `and`, `or`  | direct  |                                  |
| `not`                | `not`        | direct  |                                  |
| `Int.min/max`, `Real.min/max` | `least`, `greatest` | direct | NaN handling as above |

Arithmetic:

| Morel                    | Spark            | Kind      | Divergence                       |
| ------------------------ | ---------------- | --------- | -------------------------------- |
| `+`, `-`, `*`, `~`       | `+`, `-`, `*`, `negative` | direct | ANSI mode raises on `int` overflow, as Morel does |
| `/` (real)               | `/`              | direct    |                                  |
| `div`                    | `floor(a / b)` on ints; template `(a - pmod(a, b)) div b` | rewritten | SML rounds toward negative infinity; Spark's `div` truncates. Zero divisor raises in both |
| `mod`                    | `pmod(a, b)` when b > 0; template `((a % b) + b) % b` | rewritten | SML's result has the divisor's sign; Spark's the dividend's |
| `abs`                    | `abs`            | direct    |                                  |
| `Real.floor/ceil/trunc`  | `floor`, `ceil`, `cast(... as int)` | direct | Spark returns `bigint`; narrowed |
| `Real.round`             | `round`          | direct    | SML rounds half to even; Spark half away from zero |
| `Real.fromInt`           | `cast(x as double)` | rewritten |                               |
| `Real.toString`, `Int.toString` | `cast(x as string)` | rewritten | Formatting differs (`~` sign, exponent form); flagged |
| `Math.sqrt/exp/ln/pow/sin/cos/...` | same names (`ln` is `ln`, `pow` is `power`) | direct | |

Strings:

| Morel                   | Spark                        | Kind      | Divergence |
| ----------------------- | ---------------------------- | --------- | ---------- |
| `^`, `String.concat`    | `concat`                     | direct    |            |
| `String.size`           | `length`                     | direct    |            |
| `String.substring (s, i, n)` | `substring(s, i + 1, n)` | rewritten | Out-of-range raises `Subscript` in Morel, is clamped in Spark; flagged |
| `String.sub (s, i)`     | `substring(s, i + 1, 1)`     | rewritten | As above; result is a one-char string, decoded as `char` |
| `String.isPrefix/isSuffix/isSubstring` | `startswith`, `endswith`, `contains` | direct | |
| `String.compare`        | rewritten via `<`, `=`       | rewritten |            |
| `String.map/translate/tokens/fields/explode/implode` | | unsupported | Function arguments or char lists |
| `Char.*`                |                              | unsupported | Phase 2 |

Collections and queries (the translator handles the structure; the
table records the mapping for completeness):

| Morel                              | Spark                         | Kind      |
| ---------------------------------- | ----------------------------- | --------- |
| `count`, `sum`, `min`, `max`       | `count(1)`, `sum`, `min`, `max` | aggregate |
| `elem`, `notElem`                  | `in`, `not in` (subquery)     | subquery  |
| `nonEmpty`, `empty`, `List.null`   | `exists`, `not exists`        | subquery  |
| `only`                             | scalar subquery               | subquery  |
| `List.length`, `Bag.count` on a list-typed column | `size`         | direct    |
| `List.nth (l, i)`, `List.hd`       | `element_at(l, i + 1)`, `element_at(l, 1)` | rewritten |
| `@`, `List.rev`                    | `concat`, `reverse`           | direct    |
| `Option.isSome`, `valOf`, `getOpt` | `isnotnull`, `assert_not_null`, `coalesce` | rewritten (under 1.2) |
| `Relational.ordinal`, user functions, `fn` values | | unsupported |

Errors that a rewritten or direct entry can raise map per 2.4.

### 3.3 Tests

M4 delivers the table and a test that iterates it: for each entry
that is direct or rewritten, a query over `zoo` or a local relation
whose plan text is checked (mock connection) and whose result, when
live, matches local evaluation, with the divergence cases asserting the
documented difference instead of equality.
