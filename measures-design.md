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

# Measures and Dimensional Calculations in Morel

Design notes for adding measure support to Morel, informed by
[Measures in SQL](https://arxiv.org/abs/2406.00251) (Hyde & Fremlin,
SIGMOD-Companion '24).

## Background

In SQL, a **measure** is a new kind of column that encapsulates an
aggregate calculation and attaches it to a table. Unlike a regular
column (which has a value per row), a measure's value depends on an
**evaluation context** — a predicate over the table's dimensions. The
`AT` operator modifies this context, enabling concise dimensional
calculations like year-over-year growth or proportion-of-total without
correlated subqueries or self-joins.

BI languages (MDX, DAX, Tableau LOD) have had this concept for
decades. The paper shows how to bring it into SQL while preserving
composability and closure. The question here is: how should it work in
a functional language like Morel?

## The Core Insight: Measures Are Lambdas

The paper reveals this directly in its formal semantics. For a measure
`sumRevenue` defined as `SUM(revenue)` on table `Orders`, the system
generates an auxiliary function:

```
computeSumRevenue(pred : order -> bool) -> real
```

that evaluates `SUM(revenue)` over the rows satisfying `pred`. In
Morel terms, a measure **is** a closure over a base relation:

```sml
val sumRevenue : (order -> bool) -> real =
  fn pred =>
    from o in orders where pred o compute sum over o.revenue
```

The entire SQL machinery — evaluation context, `AT` operator,
`VISIBLE` — is syntactic sugar for constructing and transforming that
predicate argument.

## Challenges for a Functional Language

### 1. Implicit vs. explicit context

The power of measures in SQL comes from the evaluation context flowing
*implicitly* from `GROUP BY` and query structure. In
`SELECT prodName, AGGREGATE(sumRevenue) FROM ... GROUP BY prodName`,
the context `prodName = 'Widgets'` is derived automatically. A
functional language makes things explicit — which is normally a
virtue, but here it would force the user to manually construct
predicates, destroying the conciseness.

### 2. Measures are neither values nor functions — they're deferred aggregations

A regular column `e.sal` has type `int` at every row. A measure
`avgSal` has type `int` only after specifying *which rows* to
aggregate over. This is a phase distinction. The type system must
prevent accidentally using a measure as a scalar (`avgSal + 1` is
meaningless without context) while allowing it inside `compute` or
`at` expressions.

### 3. The "attached to a relation" problem

A measure is not free-standing — it's bound to a specific base table.
When you `from e in enhancedOrders`, the measure `e.sumRevenue` is
available but carries a hidden reference back to `orders`. This
doesn't exist in ML's type system. A record field is just a value;
there's no notion of a field that's actually a suspended computation
over the record's source relation.

### 4. Composability through views

The paper emphasizes that tables-with-measures are closed under query
operations — a query over a table with measures returns a table with
measures. This "measures propagate through the query pipeline"
property requires careful design so that `from ... yield ... where
...` transformations preserve measure availability.

### 5. The `at` operator breaks referential transparency (locally)

`profitMargin at {year = year - 1}` evaluates the *same* expression
in a *different* context. This is essentially dynamic scoping — the
result depends on which "year" the caller provides. In functional
terms it maps to `local` in a reader monad, but introducing monadic
plumbing everywhere would be heavy.

## Proposed Design: Lambdas + Syntactic Sugar

Measures are functions under the hood, with syntactic sugar that makes
the common patterns concise.

### Defining Measures

Introduce a `define` step in `from` expressions that attaches
measures to a relation:

```sml
(*) A relation with measures
val orders =
  from o in rawOrders
  define
    sumRevenue = sum over o.revenue,
    sumCost = sum over o.cost,
    profitMargin = (sum over o.revenue - sum over o.cost)
                   / sum over o.revenue;
```

`define` doesn't change the row type or filter rows — it attaches
named aggregate computations. The result type would be something like:

```sml
{orderDate: date, prodName: string, revenue: real, cost: real,
 sumRevenue: real measure, sumCost: real measure,
 profitMargin: real measure} list
```

where `real measure` is a new type constructor meaning "an aggregation
that produces a `real`."

### Evaluating Measures

Inside `group ... compute`, measures evaluate against the group's
context automatically:

```sml
from o in orders
  group o.prodName
  compute o.profitMargin;
(*) val it : {prodName: string, profitMargin: real} list
```

The desugaring: `profitMargin` expands to the aggregate expression
`(sum over o.revenue - sum over o.cost) / sum over o.revenue`, scoped
to rows where `prodName` matches the group key. No new evaluation
machinery needed — it reuses `compute`.

Without `group`, a bare `compute o.sumRevenue` gives the grand total.

### The `at` Operator for Dimensional Calculations

Introduce `at` as a postfix operator on measures:

```sml
from o in orders
  group {o.prodName, year = yearOf o.orderDate}
  compute {
    profit = o.sumRevenue - o.sumCost,
    profitLastYear = (o.sumRevenue - o.sumCost) at {year = year - 1},
    totalProfit = (o.sumRevenue - o.sumCost) at {all year},
    pctOfTotal = o.sumRevenue / (o.sumRevenue at {all prodName})
  };
```

The `at` modifier transforms the evaluation context:

| Modifier | Meaning |
|----------|---------|
| `at {year = year - 1}` | Override one dimension |
| `at {all year}` | Remove a dimension (roll up) |
| `at {all prodName, all year}` | Grand total |

### Desugaring `at`

`measure at {year = year - 1}` desugars to a correlated
subquery-like expansion. In the lambda formulation, `at` modifies the
predicate:

```sml
(* Normal context: *)
computeSumRevenue (fn r => r.prodName = prodName
                           andalso yearOf r.orderDate = year)

(* at {year = year - 1}: *)
computeSumRevenue (fn r => r.prodName = prodName
                           andalso yearOf r.orderDate = year - 1)

(* at {all year}: *)
computeSumRevenue (fn r => r.prodName = prodName)
```

### Year-over-Year Growth: Full Example

```sml
val ordersWithMeasures =
  from o in rawOrders
  define
    sumRevenue = sum over o.revenue,
    sumProfit = sum over o.revenue - sum over o.cost;

from o in ordersWithMeasures
  group {o.prodName, year = yearOf o.orderDate}
  compute {
    revenue = o.sumRevenue,
    revLastYear = o.sumRevenue at {year = year - 1},
    yoyGrowth =
      let
        val curr = o.sumRevenue
        val prev = o.sumRevenue at {year = year - 1}
      in
        (curr - prev) / prev
      end
  };
```

## Why Not Plain Lambdas?

You *could* do all of this with explicit lambdas today, without any
new syntax:

```sml
fun sumRevenue pred =
  from o in rawOrders where pred o compute sum over o.revenue;

(* Year-over-year *)
from o in rawOrders
  group {prodName = o.prodName, year = yearOf o.orderDate}
  compute {
    revenue =
      sumRevenue (fn r => r.prodName = prodName
                          andalso yearOf r.orderDate = year),
    revLastYear =
      sumRevenue (fn r => r.prodName = prodName
                          andalso yearOf r.orderDate = year - 1)
  };
```

This works but has serious problems:

- **Verbose**: Every `at`-style context modification requires writing
  out the full predicate.
- **Error-prone**: Forgetting a dimension constraint silently gives
  wrong results.
- **Not composable**: The measure isn't attached to the relation, so
  views can't propagate it.
- **No type safety**: Nothing stops you from passing a predicate over
  the wrong table.

The sugar adds three things: (a) the measure definition is co-located
with the data, (b) the evaluation context is derived from query
structure automatically, (c) `at` lets you modify one dimension
without restating the others.

## Type System Sketch

```
t measure     -- a measure that evaluates to t
```

Rules:

| Rule | Description |
|------|-------------|
| In a `define` clause, `agg over e` has type `t measure` | where `agg : t list -> t` |
| In `compute`, a measure evaluates to its base type `t` | Implicit `AGGREGATE` |
| `m at {...}` has the same type as `m` | Still a measure until evaluated |
| Arithmetic on measures produces measures | `m1 + m2 : int measure` if both are `int measure` |
| Assigning a measure to a non-measure binding is a type error | Phase separation |

## Open Questions

1. **Should `define` be a `from` step or a separate declaration?** A
   `from` step composes naturally in pipelines. But a standalone `val`
   binding would allow reuse across queries.

2. **Semi-additive measures** (e.g., inventory that sums across
   products but uses `last_value` across time) — these need
   per-dimension aggregate specifications. Probably out of scope
   initially.

3. **Interaction with Morel's `from` steps** — `where`, `yield`,
   `order` after `define` should propagate the measures. `group`
   without `compute` drops them (the dimensions changed). `yield` that
   projects away a dimension changes the measure's dimensionality.

4. **The `visible` modifier** — should `where` clauses restrict
   measures? The paper's default is no (measures see the full base
   table); `visible` opts in to the query's filters. For Morel, the
   default matters for user expectations.

5. **Interaction with `union`/`except`/`intersect`** — set operations
   on relations with measures need clear semantics. Presumably
   measures are only preserved if both sides define the same measures
   over the same base table.

## References

- [Measures in SQL](https://arxiv.org/abs/2406.00251) (Hyde &
  Fremlin, arXiv:2406.00251, SIGMOD-Companion '24)
- [SIGMOD 2024 slides](https://www.slideshare.net/slideshow/measures-in-sql-sigmod-2024-santiago-chile/269646979)
- [Apache Calcite CALCITE-4496](https://issues.apache.org/jira/browse/CALCITE-4496)
