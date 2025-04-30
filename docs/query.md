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

# Query

Queries are a class of Morel expressions that operate on
collections. A typical query takes one or more collections as input
and returns a collection, but there are also variants that return a
scalar value such as a `bool` or a single record.

For example, the following query returns the name and job title of all
employees in department 10:

<pre>
<b>from</b> e <b>in</b> scott.emps
  <b>where</b> e.deptno = 10
  <b>yield</b> {e.ename, e.sal};
<i>
ename  job
------ ---------
CLARK  MANAGER
KING   PRESIDENT
MILLER CLERK

val it : {ename:string, job:string} list</i>
</pre>

(Notice how this result is printed as a table. Morel automatically
uses tabular format if the value is a list of records or atomic
values, provided that you have `set("output", "tabular");` in the
current session; see [properties](reference.md#properties).)

If you know SQL, you might have noticed that this looks similar to a
SQL query:

<pre>
<b>SELECT</b> e.ename, e.sal
<b>FROM</b> scott.emps <b>AS</b> e
<b>WHERE</b> e.deptno = 10;
</pre>

There are deep similarities between Morel query expressions and SQL,
which is expected, because both are based on relational algebra. Any
SQL query has an equivalent in Morel, often with
[similar syntax](#correspondence-between-sql-and-morel-query).

## Syntax

The formal syntax of queries is as follows.

<pre>
<i>exp</i> &rarr; (other expressions)
    | <b>from</b> [ <i>scan<sub>1</sub></i> <b>,</b> ... <b>,</b> <i>scan<sub>s</sub></i> ] <i>step</i>* [ <i>terminalStep</i> ]
                                relational expression (<i>s</i> &ge; 0)
    | <b>exists</b> [ <i>scan<sub>1</sub></i> <b>,</b> ... <b>,</b> <i>scan<sub>s</sub></i> ] <i>step</i>*
                                existential quantification (<i>s</i> &ge; 0)
    | <b>forall</b> [ <i>scan<sub>1</sub></i> <b>,</b> ... <b>,</b> <i>scan<sub>s</sub></i> ] <i>step</i>* <b>require</b> <i>exp</i>
                                universal quantification (<i>s</i> &ge; 0)

<i>scan</i> &rarr; <i>pat</i> <b>in</b> <i>exp</i> [ <b>on</b> <i>exp</i> ]    iteration
    | <i>pat</i> <b>=</b> <i>exp</i> [ <b>on</b> <i>exp</i> ]      single iteration
    | <i>var</i>                       unbounded variable

<i>step</i> &rarr; <b>join</b> <i>scan<sub>1</sub></i> [ <b>,</b> ... <b>,</b> <i>scan<sub>s</sub></i> ]
                                join clause
    | <b>where</b> <i>exp</i>                 filter clause
    | <b>distinct</b>                  distinct clause
    | <b>group</b> <i>groupKey<sub>1</sub></i> <b>,</b> ... <b>,</b> <i>groupKey<sub>g</sub></i>
      [ <b>compute</b> <i>agg<sub>1</sub></i> <b>,</b> ... <b>,</b> <i>agg<sub>a</sub></i> ]
                                group clause (<i>g</i> &ge; 0, <i>a</i> &ge; 1)
    | <b>order</b> <i>orderItem<sub>1</sub></i> <b>,</b> ... <b>,</b> <i>orderItem<sub>o</sub></i>
                                order clause (<i>o</i> &ge; 1)
    | <b>skip</b> <i>exp</i>                  skip clause
    | <b>take</b> <i>exp</i>                  take clause
    | <b>through</b> <i>pat</i> <b>in</b> <i>exp</i>        through clause
    | <b>yield</b> <i>exp</i>                 yield clause

<i>terminalStep</i> &rarr; <b>into</b> <i>exp</i>         into clause
    | <b>compute</b> <i>agg<sub>1</sub></i> <b>,</b> ... <b>,</b> <i>agg<sub>a</sub></i>  compute clause (<i>a</i> &gt; 1)

<i>groupKey</i> &rarr; [ <i>id</i> <b>=</b> ] <i>exp</i>

<i>agg</i> &rarr; [ <i>id</i> <b>=</b> ] <i>exp</i> [ <b>of</b> <i>exp</i> ]

<i>orderItem</i> &rarr; <i>exp</i> [ <b>desc</b> ]
</pre>

First, notice that a query is an expression. Provided that its type is
valid, you can use a query anywhere in a Morel program that an
expression is valid, such as in a `case` expression, the body of a
`fn` lambda, or the argument to a function call. Or you can evaluate a
query by typing it into the shell, just like any other expression.

A query is a `from`, `exists` or `forall` keyword followed by one or
more *scans*, then followed by zero or more *steps*. (A `forall` query
must end with a `require` step, and a `from` query may end with an
`into` or `compute` terminal step.)

In the previous query, <code><b>from</b> e <b>in</b> scott.emps</code>
is a scan, and <code><b>where</b> e.deptno = 10</code> and
<code><b>yield</b> {e.ename, e.sal}</code> are steps.

Now let's look at [scans](#scan) and [steps](#step) in more detail. We
will focus on `from` for now, and will cover `forall` and `exists` in
[quantified queries](#quantified-queries).

## Scan

A **scan** is a source of rows. The most common form, "*id* `in`
*collection*", assigns each element of *collection* to *id* in turn
and then invokes the later steps in the pipeline.

A scan is like a "for" loop in a language such as Java or Python.

The collection can have elements of any type. In SQL, the elements
must be records, but in Morel they may be atomic values, lists, lists
of lists, records that contain lists of records, or anything else.

<pre>
<i>(* Query over a list of integers. *)</i>
<b>from</b> i <b>in</b> [1, 2, 3, 4, 5]
  <b>where</b> i <b>mod</b> 2 = 0;
<i>
2
4

val it : int list</i>
</pre>

If the collection has a structured type, you can use a pattern to
deconstruct it.

<pre>
<i>(* Query over a list of (string, int) pairs. *)</i>
<b>from</b> (name, age) <b>in</b> [("shaggy", 17), ("scooby", 7)]
  <b>yield</b> {s = name ^ " is " ^ Int.toString(age) ^ "."};
<i>
shaggy is 17.
scooby is 7.

val it : {s:string} list</i>
</pre>

### Multiple scans

If there are multiple scans, the query generates a cartesian product:

<pre>
<b>from</b> i <b>in</b> [2, 3],
  s <b>in</b> ["ab", "cde"];
<i>
i s
- ---
2 ab
2 cde
3 ab
3 cde

val it : {i:int, s:string} list</i>
</pre>

If you want to add a join condition, you can append an `on` clause:

<pre>
<b>from</b> e <b>in</b> scott.emps,
    d <b>in</b> scott.depts <b>on</b> e.deptno = d.deptno
  <b>where</b> e.job = "MANAGER"
  <b>yield</b> {e.ename, d.dname};
<i>
dname      ename
---------- ------
RESEARCH   JONES
SALES      BLAKE
ACCOUNTING CLARK

val it : {dname:string, ename:string} list</i>
</pre>

(The `on` clause is not allowed on the first scan.)

If you want scans later in a query, use the `join` step.

<pre>
<b>from</b> c <b>in</b> clients
  <b>where</b> c.city = "BOSTON"
  <b>join</b> e <b>in</b> scott.emps <b>on</b> c.contact = e.empno,
      d <b>in</b> scott.depts <b>on</b> e.deptno = d.deptno
  <b>yield</b> {c.cname, e.ename, d.dname};
<i>
cname  dname ename
------ ----- ------
Apple  SALES MARTIN
Disney SALES ALLEN
Ford   SALES WARD
IBM    SALES MARTIN</i>
</pre>

### Lateral scans and nested data

Multiple scans are a convenient way of dealing with nested data.

<pre>
<i>(* Define the shipments data set; each shipment has one or
 * more nested items. *)</i>
<b>val</b> shipments =
  [{id=1, shipping=10.0, items=[{product="soda", quantity=12},
                                {product="beer", quantity=3}],
   {id=2, shipping=7.5, items=[{product="cider",quantity=4}]}]}];

<i>(* Flatten the data set by joining each shipment to its own
 * items. *)</i>
<b>from</b> s <b>in</b> shipments,
    i <b>in</b> s.items
  <b>yield</b> {s.id, i.product, i.quantity};
<i>
id product quantity
-- ------- --------
 1 soda          12
 1 beer           3
 2 cider          4

val it : {id:int, product:string, quantity:int} list</i>
</pre>

Note that the second scan uses current row from the first scan (`s`
appears in the expression `s.items`). SQL calls this a lateral join
(because lateral means "sideways" and one scan is looking "sideways"
at the other scan). Lateral joins are only activated in SQL when you
use the keywords `LATERAL` or `UNNEST`, but Morel's scans and joins
are always lateral. As a result, queries over nested data are easy and
concise in Morel.

### Single-row scan

A scan with `=` syntax iterates over a single value. While `pat = exp`
is just syntactic sugar for `pat <b>in</b> [exp]`, it is nevertheless
a useful way to add a column to the current row.

<pre>
<i>(* Iterate over a list of integers and compute whether
 * they are odd. *)</i>
<b>from</b> i <b>in</b> [1, 2, 3, 4, 5],
    odd = (i <b>mod</b> 2 = 1);
<i>
i odd
- -----
1 true
2 false
3 true
4 false
5 true</i>

<i>(* Equivalent using "in" and a singleton list. *)</i>
<b>val</b> it : {i:int, odd:bool} list
<b>from</b> i <b>in</b> [1, 2, 3, 4, 5],
    odd <b>in</b> [(i <b>mod</b> 2 = 1)];
<i>
i odd
- -----
1 true
2 false
3 true
4 false
5 true

val it : {i:int, odd:bool} list</i>
</pre>

### Empty scan

In case you are wondering, yes, a query with no scans is legal. It
produces one row with zero fields.

<pre>
<b>from</b>;

<it>val it = [()] : unit list</it>
</pre>

You can even feed that one row into a pipeline.

<pre>
<b>from</b>
  <b>where</b> true
  <b>yield</b> 1 + 2;
<i>
3

val it : {i:int} list</i>
</pre>

## Step

A query is a pipeline of data flowing through relational
operators. The scans introduce rows into the pipeline, and the steps
are the relational operators that these rows flow through.

Each step has a contract with its preceding and following step: what
fields does it consume, and what fields are produced. A query begins
with a set of scans, and each scan defines a number of variables
(usually one, unless the scan has a complex pattern).

The following query defines two fields: `deptno` of type `int` and
`emp` with a record type.

<pre>
<b>from</b> deptno <b>in</b> [10, 20],
    emp <b>in</b> scott.emps <b>on</b> emp.deptno = deptno;
</pre>

(Unlike SQL, the fields of a record are not automatically unnested. If
you wish to access the `job` field of an employee record, then you
must write `emp.job`; the unqualified expression `job` is invalid.)

The `deptno` and `emp` fields can be consumed in a following `yield`
step, which produces fields `deptno`, `job`, `initial`:

<pre>
<b>from</b> deptno <b>in</b> [10, 20],
    emp <b>in</b> scott.emps <b>on</b> emp.deptno = deptno
  <b>yield</b> {deptno, emp.job, initial = String.sub(emp.ename, 1);
<i>
deptno initial job
------ ------- ---------
10     L       MANAGER
10     I       PRESIDENT
10     I       CLERK
20     M       CLERK
20     O       MANAGER
20     C       ANALYST
20     D       CLERK
20     O       ANALYST

val it : {deptno:int, initial:char, job:string} list</i>
</pre>

And so on. In the following sections, we define each of Morel's step
types and how they map input fields to output fields.

### Distinct step

`distinct`

### Group step

`group`

### Join step

`join`

### Skip step

`skip`

### Take step

`take`

### Through step

`through`

### Order step

`order`

### Where step

`where`

### Yield step

`yield`

## Quantified queries

`forall` and `exists`

## Correspondence between SQL and Morel query

Many of the keywords in a SQL query have an equivalent in Morel.

| SQL         | Morel       | Remarks
|-------------|-------------|---------
| `SELECT`    | `yield`     | `SELECT` is always the first keyword of a query, but you may use `yield` at any point in the pipeline. It often occurs last, and you can omit it if the output record has the right shape.
| `FROM`      | `from`      | Unlike SQL `FROM`, `from` is the first keyword in a Morel query.
| `JOIN`      | `join`      | SQL `JOIN` is part of the `FROM` clause, but Morel `join` is a step.
| `WHERE`     | `where`     | Morel `where` is equivalent to SQL `WHERE`.
| `HAVING`    |             | Use a `where` after a `group`.
| `DISTINCT` | `distinct`   | SQL `DISTINCT` is part of the `SELECT` clause, but Morel `distinct` is a step, shorthand for `group`
| `ORDER BY`  | `order`     | Morel `order` is equivalent to SQL `ORDER BY`.
| `LIMIT`     | `take`      | Morel `take` is equivalent to SQL `LIMIT`.
| `OFFSET`    | `skip`      | Morel `skip` is equivalent to SQL `OFFSET`.
| `UNION`     | `union`     | Morel `union` is equivalent to SQL `UNION ALL`.
| `INTERSECT` | `intersect` | Morel `intersect` is equivalent to SQL `INTERSECT ALL`.
| `EXCEPT`    | `except`    | Morel `except` is equivalent to SQL `EXCEPT ALL` (or `MINUS ALL` in some SQL dialects).
| `EXISTS`    | `exists`    | SQL `EXISTS` is unary operator whose operand is a query, but Morel `exists` is a query that returns `true` if the query has at least one row.
| -           | `forall`    | Morel `forall` is a query that returns `true` if a predicate is true for all rows.
| `IN`        | `elem`      | SQL `IN` is a binary operator whose right operand is either a query or a list (but not an array or multiset); Morel `elem` is the equivalent operator, and its right operand can be any collection, including a query.
| `NOT IN`    | `notelem`   | Morel `notelem` is equivalent to SQL `NOT IN`, but without SQL's confusing [NULL-value semantics](https://community.snowflake.com/s/article/Behaviour-of-NOT-IN-with-NULL-values)).
| -           | `yieldall`  | Morel `yieldall` evaluates a collection expression and outputs one row for each element of that collection.

