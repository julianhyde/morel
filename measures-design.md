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

A measure is a function from a predicate (the evaluation context) to
a value. For a measure `sumRevenue` defined as `SUM(revenue)` on
table `Orders`:

```sml
val sumRevenue : (order -> bool) -> int =
  fn pred =>
    from r in orders where pred r compute sum over r.revenue
```

The entire SQL machinery — evaluation context, `AT` operator,
`VISIBLE` — is syntactic sugar for constructing and transforming that
predicate argument.

## What Works Today (No Language Changes)

Measures can be expressed longhand using existing Morel features. See
`src/test/resources/script/measure.smli` for a working script.

### Approach 1: Measures as predicate functions

```sml
val orders = [
  {year = 2023, custName = "Alice", prodName = "Widgets",
   revenue = 100, cost = 80}, ...];

fun sumRevenue pred =
  from r in orders where pred r compute sum over r.revenue;
fun sumCost pred =
  from r in orders where pred r compute sum over r.cost;
```

Queries compose measures with explicit predicates:

```sml
(*) Revenue by product
from p in (from r in orders group r.prodName)
  yield {prodName = p,
         revenue = sumRevenue (fn r => r.prodName = p)}
  order prodName;

(*) Year-over-year
from g in (from r in orders group {r.prodName, r.year})
  yield {prodName = g.prodName, year = g.year,
         revenue = sumRevenue (fn r => r.prodName = g.prodName
                                       andalso r.year = g.year),
         revPrior = sumRevenue (fn r => r.prodName = g.prodName
                                        andalso r.year = g.year - 1)}
  order (prodName, year);

(*) Proportion of total
from p in (from r in orders group r.prodName)
  yield {prodName = p,
         revenue = sumRevenue (fn r => r.prodName = p),
         total = sumRevenue (fn _ => true)}
  order prodName;
```

### Approach 2: Context as record of options

Destructure the record parameter to avoid flex-record errors:

```sml
fun sumRevenueCtx {prodName = pn, year = yr, custName = cn} =
  from r in orders
    where (case pn of NONE => true | SOME p => r.prodName = p)
    where (case yr of NONE => true | SOME y => r.year = y)
    where (case cn of NONE => true | SOME c => r.custName = c)
    compute sum over r.revenue;
```

### Pain points with the longhand approaches

- **Verbose**: Every context modification requires writing out the
  full predicate. Year-over-year needs two nearly-identical lambdas.
- **Error-prone**: Forgetting a dimension constraint silently gives
  wrong results.
- **Not attached**: Measures are free functions with no formal
  connection to `orders`. Nothing prevents calling them with a
  predicate over the wrong schema.
- **Boilerplate**: The context-as-record approach requires spelling
  out `NONE` for every unused dimension.
- **Redundant evaluation**: `sumRevenue (fn _ => true)` in
  proportion-of-total is re-evaluated per row.

## Proposed Design

### Relations

A **relation** is a collection (list or bag) plus a lambda that
creates a context. The `define` keyword is syntactic sugar that
generates that lambda.

```sml
val orders = [
  {year = 2023, custName = "Alice", prodName = "Widgets",
   revenue = 100, cost = 80}, ...]
  define
    sumRevenue = sum over revenue,
    sumCost = sum over cost;
```

A relation works in a `from` clause just like a raw list or bag.
Code that doesn't use measures sees no difference.

### The `measure` type

A measure has type `'a measure`. It is opaque — internally like a
lambda from context to value, but not a plain function.

Operations:

| Operation | Signature | Description |
|-----------|-----------|-------------|
| `eval` | `'a measure -> context -> 'a` | Evaluate in a given context |
| `at` | `context -> modifier -> context` | Modify the context |

A measure definition can be any Morel expression, including queries
and literals. Measures compose via explicit lambdas:

```sml
val orders = [...]
  define
    sumRevenue = sum over revenue,
    sumCost = sum over cost,
    profit = fn ctx => eval sumRevenue ctx - eval sumCost ctx,
    yoyGrowth = fn ctx =>
      eval sumRevenue ctx
      - eval sumRevenue (ctx at {year = year - 1});
```

Each measure explicitly takes a context. References to other measures
use `eval` to extract values. The only thing `define` adds beyond
`fun` is: these functions are attached to the relation and are
automatically called with the right context when referenced in a
query.

A possible future sugar `measure { ... }` could implicitly wrap in a
lambda and insert `eval` on bare measure names:

```sml
    profit = measure { sumRevenue - sumCost },
    yoyGrowth = measure { sumRevenue - sumRevenue at {year = year - 1} };
```

But that is not needed initially.

### Context is (mostly) a predicate

The context is primarily a predicate — a `row -> bool` lambda. It
does not have a field per dimension. This keeps the context simple and
avoids a record type that grows with the number of dimensions.

```sml
type context  (* opaque, internally row -> bool *)
```

When `from r in orders` executes and a `group` narrows the active
rows, the `from` machinery builds the predicate (e.g.,
`fn r => r.prodName = "Widgets" andalso r.year = 2024`) and passes it
as the context to each measure.

### How measures enter scope in `from`

When `from r in orders` processes a relation (not a raw list), the
measures are available qualified by relation name:

```sml
from r in orders
  group r.prodName
  yield {prodName, revenue = orders.sumRevenue};
```

The `from` machinery supplies the context (derived from the current
group). The user never writes `eval orders.sumRevenue ctx` in a
query — that is what being "in a `from` context" gives you.

In a join, each relation's measures are qualified:

```sml
from r in orders, c in customers
  where r.custName = c.name
  group r.prodName
  yield {prodName,
         revenue = orders.sumRevenue,
         numCustomers = customers.custCount};
```

### The `at` operator

`at` modifies the context predicate. It is the one operation that
requires AST introspection — finding and replacing a conjunct in the
predicate.

```sml
from r in orders
  group {r.prodName, r.year}
  yield {prodName, year,
         revenue = orders.sumRevenue,
         revPrior = orders.sumRevenue at {year = year - 1}};
```

`orders.sumRevenue at {year = year - 1}` means: take the current
context predicate (e.g.,
`fn r => r.prodName = "Widgets" andalso r.year = 2024`), find the
conjunct matching `r.year = <expr>`, and rewrite it to
`r.year = 2024 - 1`. The result predicate is
`fn r => r.prodName = "Widgets" andalso r.year = 2023`.

The compiler:

1. Decomposes the predicate into `andalso` conjuncts
2. Finds the conjunct where the LHS matches the field named in `at`
3. Replaces the RHS expression
4. Reconstructs the predicate

If no matching conjunct exists, `at` adds the constraint. This is
the same kind of predicate decomposition that Morel's
generator/constraint system already performs in `Generators.java`.

Year-over-year is an uncommon case. Most measure usage just relies on
the implicit context from `group` — no `at` needed.

### Parameters

The context can also include **parameters** — values that are the
same for every row but can be overridden. Parameters are defined
alongside measures:

```sml
val orders = [...]
  define
    sumRevenue = sum over revenue,
    interest_rate = 0.05,
    financing_cost = fn ctx =>
      eval sumRevenue ctx * interest_rate;
```

A parameter is a measure whose value doesn't depend on which rows
are active. It's a constant in the context, overridable via `at`:

```sml
(*) Override interest_rate for a scenario
orders.financing_cost at {interest_rate = 0.10}
```

Parameters and measures live in the same namespace — the context.
The difference is internal: measures re-evaluate as the context
narrows, parameters stay fixed unless explicitly overridden.

## Challenges

### 1. Implicit vs. explicit context

The power of measures in SQL comes from the evaluation context flowing
*implicitly* from `GROUP BY` and query structure. A functional
language makes things explicit — which is normally a virtue, but here
it would force the user to manually construct predicates, destroying
the conciseness. The proposed design threads the context implicitly
through `from` (like `ordinal` and `current`) while keeping `eval`
explicit in measure definitions.

### 2. Phase distinction

A measure is not a value — it's a deferred aggregation. The `measure`
type prevents accidentally using a measure as a scalar (`sumRevenue +
1` is a type error without `eval`). Measures must be evaluated before
their values can be used in arithmetic.

### 3. Attached to a relation

A measure is bound to a specific relation. The `define` keyword
attaches it. When `from r in orders` sees a relation, it makes the
measures available. A raw list has no measures.

### 4. AST introspection for `at`

The `at` operator requires decomposing a predicate's AST to find and
replace conjuncts. This is the most complex part of the implementation
but builds on existing infrastructure in Morel's generator/constraint
system, which already pattern-matches on predicate conjuncts.

## Open Questions

1. **Syntax for `define`**: Is it part of the `val` declaration, or a
   separate declaration? Part of `val` means the definition travels
   with the data. Separate means you could add measures to an existing
   relation.

2. **Semi-additive measures** (e.g., inventory that sums across
   products but uses `last_value` across time) — these need
   per-dimension aggregate specifications. Probably out of scope
   initially.

3. **The `visible` modifier** — should `where` clauses restrict
   measures? The paper's default is no (measures see the full base
   table); `visible` opts in to the query's filters. For Morel, the
   default matters for user expectations.

4. **Widening**: How to remove a dimension constraint (the old `ALL`
   case). Options: `at {prodName = ALL}` with a keyword, or just
   construct the predicate manually for this rare case.

5. **`measure { ... }` sugar**: Should measure definitions support
   implicit `eval` on bare measure names, so you can write
   `profit = measure { sumRevenue - sumCost }` instead of
   `profit = fn ctx => eval sumRevenue ctx - eval sumCost ctx`?
   Deferred — start with explicit lambdas.

## References

- [Measures in SQL](https://arxiv.org/abs/2406.00251) (Hyde &
  Fremlin, arXiv:2406.00251, SIGMOD-Companion '24)
- [SIGMOD 2024 slides](https://www.slideshare.net/slideshow/measures-in-sql-sigmod-2024-santiago-chile/269646979)
- [Apache Calcite CALCITE-4496](https://issues.apache.org/jira/browse/CALCITE-4496)
