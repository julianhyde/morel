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

### The `relation` abstract type

A **relation** is an abstract type parameterized by the row type and
the context type:

```sml
abstype ('row, 'ctx) relation = Relation of 'row list * ('row list -> 'ctx)
with
  fun collection (Relation (rows, _)) = rows
  fun mkContext (Relation (_, mk)) rows = mk rows
end;
```

At runtime, a relation is a pair: the row data and a function that
creates a context from a set of active rows. The context type `'ctx`
varies per relation — it's a record whose fields are the evaluated
measures and parameters.

Relations must support both ordered (list) and unordered (bag)
collections. Morel's `from` already works with both `list` and `bag`;
the `relation` type should be similarly polymorphic in the collection
kind. The abstract type could be parameterized by the collection type
constructor, or there could be separate `relation` and `bag_relation`
types. This choice affects whether `order` steps are valid (only for
ordered relations). Neither list nor bag eliminates duplicates; if
you want uniqueness, use constraints.

A relation works in a `from` clause just like a raw list or bag.
Code that doesn't use measures sees no difference — `from` calls
`collection` to extract the rows.

### The `reltype` keyword

A new declaration keyword `reltype` (analogous to `datatype` and
`abstype`) defines a relation type including its context:

```sml
reltype orders of {year: int, quarter: string, prodName: string,
                   revenue: int, cost: int}
  where quarter -> year
  define
    sumRevenue = sum over revenue,
    sumCost = sum over cost,
    profit = fn ctx => eval sumRevenue ctx - eval sumCost ctx,
    interest_rate = 0.05;
```

This is syntactic sugar. It generates:

```sml
(*) Row type
type order_row = {year: int, prodName: string,
                  revenue: int, cost: int};

(*) Context type — one field per measure/parameter
type order_ctx = {sumRevenue: int,
                  sumCost: int,
                  profit: int,
                  interest_rate: real};

(*) Relation type — a specific instantiation
type orders = (order_row, order_ctx) relation;

(*) Constructor — takes a row list, returns a relation
val orders : order_row list -> orders =
  fn rows =>
    Relation (rows,
      fn activeRows =>
        let val sumRevenue =
              from r in activeRows compute sum over r.revenue
            val sumCost =
              from r in activeRows compute sum over r.cost
        in {sumRevenue = sumRevenue,
            sumCost = sumCost,
            profit = sumRevenue - sumCost,
            interest_rate = 0.05}
        end);
```

Like `datatype`, `reltype` defines both a type name and a
constructor. The context factory lambda is baked into the constructor.

### The `measure` type

A measure has type `'a measure`. It is opaque — internally a function
from context to value, but not a plain lambda.

Operations:

| Operation | Signature | Description |
|-----------|-----------|-------------|
| `eval` | `'a measure -> context -> 'a` | Evaluate in a given context |
| `at` | `context -> modifier -> context` | Modify the context |

A measure definition can be any Morel expression, including queries
and literals. Measures compose via explicit lambdas — no lifted
arithmetic:

```sml
    profit = fn ctx => eval sumRevenue ctx - eval sumCost ctx,
    yoyGrowth = fn ctx =>
      eval sumRevenue ctx
      - eval sumRevenue (ctx at {year shift fn y => y - 1});
```

Each measure explicitly takes a context. References to other measures
use `eval` to extract values.

A possible future sugar `measure { ... }` could implicitly wrap in a
lambda and insert `eval` on bare measure names:

```sml
    profit = measure { sumRevenue - sumCost },
    yoyGrowth = measure { sumRevenue - sumRevenue at {year = year - 1} };
```

But that is not needed initially.

### Creating a value

```sml
val myOrders = orders [
  {year = 2023, prodName = "Widgets", revenue = 100, cost = 80},
  {year = 2024, prodName = "Gadgets", revenue = 200, cost = 150}];
(*) val myOrders : orders
```

At runtime, `myOrders` is the pair (row list, context factory). The
type is `orders`, not `order_row list` — but `from` knows how to
extract the collection.

### Context is (mostly) a predicate

The context is primarily a predicate — a `row -> bool` lambda. It
does not have a field per dimension. This keeps the context simple and
avoids a record type that grows with the number of dimensions.

When `from r in myOrders` executes and a `group` narrows the active
rows, the `from` machinery builds the predicate (e.g.,
`fn r => r.prodName = "Widgets" andalso r.year = 2024`), filters the
rows, and passes the active rows to the context factory. The factory
returns a context record with evaluated measures.

### How measures enter scope in `from`

When `from r in myOrders` processes a relation (not a raw list), the
measures are available qualified by relation name:

```sml
from r in myOrders
  group r.prodName
  yield {prodName,
         revenue = myOrders.sumRevenue,
         profit = myOrders.profit};
```

The `from` machinery supplies the context (derived from the current
group). The user never writes `eval myOrders.sumRevenue ctx` in a
query — that is what being "in a `from` context" gives you.

In a join, each relation's measures are qualified:

```sml
reltype products of {prodName: string, category: string}
  define
    prodCount = count over ();

val prods = products [
  {prodName = "Widgets", category = "Hardware"},
  {prodName = "Gadgets", category = "Electronics"}];

from r in myOrders, p in prods
  where r.prodName = p.prodName
  group p.category
  yield {category,
         revenue = myOrders.sumRevenue,
         numProducts = prods.prodCount};
```

Different relation types have different context types and different
measures. Qualified names disambiguate in joins.

### The `at` operator

`at` is a measure combinator. It takes a measure and a context
transformer, and returns a new measure:

```
eval (m at modifier) ctx  =  eval m (transform modifier ctx)
```

The result of `at` is a first-class measure — you can bind it to a
name, pass it around, or use it in `define`:

```sml
reltype orders of ...
  define
    sumRevenue = sum over revenue,
    revPrior = sumRevenue at {year shift fn y => y - 1};
```

#### Context operations

The context is primarily a predicate — a `row -> bool` lambda
built from a conjunction of field-level predicates. The `at` operator
modifies this predicate. The primitive operations are:

**Whole-predicate operations:**

| Operation | Syntax | Description |
|-----------|--------|-------------|
| **And** | `at (fn r => expr)` | Narrow by conjoining an additional predicate |
| **Or** | `at or (fn r => expr)` | Widen by disjoining an additional predicate |
| **Set** | `at set (fn r => expr)` | Replace the entire predicate |
| **Clear** | `at clear` | Remove all predicates (set to `fn _ => true`) |

**Field-level operations** (inside `at { ... }`):

| Operation | Syntax | Description |
|-----------|--------|-------------|
| **Equality** | `year = 2024` | Remove conjuncts for field, add equality |
| **Predicate** | `year check fn y => y elem [2024, 2026]` | Remove conjuncts, add general predicate |
| **Remove** | `any year` | Remove all conjuncts for field |
| **Shift** | `year shift fn y => y - 1` | Replace with function of current unique value |
| **Set parameter** | `interest_rate = 0.10` | Override a parameter value |

The `check` keyword (cf. [MOREL-239]) creates a clear syntactic
boundary between value and predicate forms: in `year = 2024` the
right-hand side is a value (`int`); in `year check fn y => ...` it is
a predicate (`int -> bool`). The compiler knows from the keyword which
form to expect, simplifying type deduction.

The `shift` form takes a function `fieldType -> fieldType` and applies
it to the current unique value of the field. If the field has no
unique value (option is `NONE`), the shift is a no-op (the constraint
is removed). This handles the common year-over-year case concisely:

```sml
at {year shift fn y => y - 1}
```

Some operations are compositions of primitives: equality is
remove + and with equality predicate; clear is set with
`fn _ => true`.

#### Field values in `at` expressions

Within an `at { ... }` expression, the current value of each field is
in scope as a `fieldType option`:

- `SOME v` if the current context uniquely determines the field's value
- `NONE` if it does not

This makes non-uniqueness explicit in the type system. The different
field operation forms use the current value differently:

- **Equality** (`year = 2024`): The right-hand side is a plain value
  (`int`). The current value is available but not needed.
- **Predicate** (`year check fn y => ...`): The bound variable `y`
  is the per-row field value (`int`). The current option value is
  also available for reference.
- **Remove** (`any year`): No expression needed.
- **Shift** (`year shift fn y => y - 1`): The bound variable `y`
  is the current unique value (`int`, not `int option`). If the
  field has no unique value, the shift is a no-op (constraint
  removed).

Common cases:

```sml
at {year = 2024}                     (*) set year = 2024
at {any year}                        (*) remove year constraint
at {year shift fn y => y - 1}        (*) shift back 1 if determined
at {year check fn y => y >= 2020}    (*) non-equality predicate
```

For advanced cases, `check` can reference the current option value:

```sml
at {year check fn y =>
      case year of
        SOME v => y = v - 1
      | NONE => true}
```

#### Runtime representation of context

The context is not a compiled `row -> bool` lambda — you cannot
decompose or extract field values from opaque code. Instead, the
context is a **structured data value**: a map from field name to
constraint, plus a list of global (cross-field) predicates.

```sml
type context = {
  fields: (string, constraint) map,   (* per-field *)
  globals: (row -> bool) list          (* cross-field *)
}
```

Each field constraint is one of:

| Constraint | Current value | Description |
|------------|---------------|-------------|
| `Eq v` | `SOME v` | Equality: field = v |
| `Check pred` | `NONE` | General predicate: `fieldType -> bool` |

The `fields` map is sparse — only fields with active constraints
appear. A field absent from the map is implicitly unconstrained
(`Any`). This means:

- `any year` removes the `year` key from the map
- `year = 2024` inserts `("year", Eq 2024)`
- `year check fn y => ...` inserts `("year", Check (fn y => ...))`
- `year shift f` on `Eq v` inserts `("year", Eq (f v))`; on
  `Check` or absent, removes the key (no unique value to shift)

Global predicates (from `at (fn r => expr)` or cross-field
constraints like `r.year + r.revenue > 100`) live in the `globals`
list. They cannot be attributed to a single field and are not
touched by field-level operations.

The filtering predicate is derived by walking the map entries and
the globals list, conjoining everything:

```sml
fun toPredicate {fields, globals} row =
  Map.all (fn (field, Eq v) => getField row field = v
            | (field, Check p) => p (getField row field)) fields
  andalso List.all (fn p => p row) globals
```

The `from` machinery builds this structured context from `group`
clauses — `group r.year` produces an `Eq` constraint for each group
key. The `at` operator transforms the structure directly, with no
AST introspection at runtime.

This is analogous to how SQL optimizers represent predicates: not as
opaque expressions but as structured filter lists that support
pushdown, combination, and field-level replacement.

#### Functional dependencies and `at`

When `at` replaces or removes a field constraint, it must also remove
constraints on **functionally dependent** fields, to avoid creating
unsatisfiable predicates.

For example, given FDs `orderDate -> quarter -> year`:

- Context: `r.quarter >= "2024-Q2" andalso r.quarter <= "2024-Q3"`
- Operation: `at {year = 2023}`
- The quarter constraint implies year 2024, conflicting with year 2023
- So `at` must also remove the quarter constraint

The algorithm: when replacing field `F`, compute the FD closure of
`F` — all fields connected via functional dependencies in either
direction, chasing transitively — and remove conjuncts on all fields
in the closure before adding the new constraint.

Functional dependencies are declared in the `reltype` `where` clause:

```sml
reltype orders of {orderDate: date, year: int, quarter: string,
                   prodName: string, custName: string,
                   revenue: int, cost: int}
  where unique {orderDate, prodName, custName},
        orderDate -> quarter -> year
  define
    sumRevenue = sum over revenue;
```

The chain `orderDate -> quarter -> year` declares: `orderDate`
determines `quarter`, `quarter` determines `year`, and transitively
`orderDate` determines `year`. The `unique` clause declares a key.

For derived fields — e.g., `year = yearOf r.orderDate` and
`quarter = quarterOf r.orderDate` — Morel can infer FDs automatically
from known function hierarchies (temporal extraction functions have a
known granularity order: `date -> month -> quarter -> year`), without
requiring explicit declaration.

#### Determining unique field values

The current value of a field is determined directly from the
structured context — no AST introspection needed:

- **`Eq v`**: Current value is `SOME v`. This is the common case,
  produced by `group` clauses and equality `at` operations.
- **`Check pred`**: Current value is `NONE`. A general predicate
  does not imply a unique value.
- **Absent** (unconstrained): Current value is `NONE`.

FD chasing can propagate unique values between fields:

- **Via FD**: Field `quarter` has `Eq "2024-Q3"`. Via
  `quarter -> year` and the known mapping, `year`'s current value is
  `SOME 2024` even if `year` is absent from the map.
- **Via FD with range**: Field `quarter` has
  `Check (fn q => q >= "2024-Q2" andalso q <= "2024-Q3")`. All
  quarters in that range map to year 2024, so `year`'s current value
  is `SOME 2024`. (This requires evaluating the FD mapping over the
  range — more expensive, and only applies to known hierarchies.)
- **Non-unique via FD**: The range spans two years. Value is `NONE`.

Most measure usage relies on the implicit context from `group` — no
`at` needed. The `at` operator handles the less common but important
case of dimensional calculations like year-over-year.

### Parameters

Parameters are defined alongside measures in `reltype`. A parameter
is a context field whose value doesn't depend on which rows are
active — it's a constant, overridable via `at`:

```sml
reltype orders of {year: int, prodName: string,
                   revenue: int, cost: int}
  define
    sumRevenue = sum over revenue,
    interest_rate = 0.05,
    financing_cost = fn ctx =>
      eval sumRevenue ctx * interest_rate;
```

```sml
(*) Override interest_rate for a scenario
myOrders.financing_cost at {interest_rate = 0.10}
(*) 'interest_rate = 0.10' uses the equality form — same as for fields
```

Parameters and measures live in the same namespace — the context
record. The difference is internal: measures re-evaluate as the
context narrows, parameters stay fixed unless explicitly overridden.

### Design alternatives considered

**Structures.** SML structures can attach operations to a type, but
structures are not first-class values — you cannot pass them as
function arguments or put different relations in a list.

**First-class modules (OCaml-style).** A relation is a module packed
as a value, conforming to a `RELATION` signature. This works, but
every relation with a different set of measures needs a different
signature, and you can't easily say "any relation with at least
`sumRevenue`."

**Type classes (Haskell-style).** Measures are declared as instances
on the row type. This is clean for generic functions (`fun
topByRevenue rows = ...` works for any row type with `HasRevenue`),
but measures are on the row *type*, not the relation *value* — so all
`order list` values share the same measures. Different measure sets
for the same row type require newtype wrappers.

**The `reltype` approach** avoids all three problems: relations are
first-class values, each relation has its own measures (via its own
`'ctx` type), and the collection is the only runtime data.

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

A measure is bound to a specific relation via `reltype`. When
`from r in myOrders` sees a relation, it makes the measures available.
A raw list has no measures.

### 4. AST introspection for `at`

The `at` operator requires decomposing a predicate's AST to find and
replace conjuncts. This is the most complex part of the implementation
but builds on existing infrastructure in Morel's generator/constraint
system, which already pattern-matches on predicate conjuncts.

## Open Questions

1. **Semi-additive measures** (e.g., inventory that sums across
   products but uses `last_value` across time) — these need
   per-dimension aggregate specifications. Probably out of scope
   initially.

2. **The `visible` modifier** — should `where` clauses restrict
   measures? The paper's default is no (measures see the full base
   table); `visible` opts in to the query's filters. For Morel, the
   default matters for user expectations.

3. **`measure { ... }` sugar**: Should measure definitions support
   implicit `eval` on bare measure names, so you can write
   `profit = measure { sumRevenue - sumCost }` instead of
   `profit = fn ctx => eval sumRevenue ctx - eval sumCost ctx`?
   Deferred — start with explicit lambdas.

4. **Generic functions over relations**: Can you write a function that
   takes any relation with a `sumRevenue` measure? The `'ctx` type
   parameter is specific to each `reltype`. A generic function would
   need either structural subtyping on `'ctx` or a type class
   constraint. Probably not needed initially — most queries name their
   relations explicitly.

## References

- [Measures in SQL](https://arxiv.org/abs/2406.00251) (Hyde &
  Fremlin, arXiv:2406.00251, SIGMOD-Companion '24)
- [SIGMOD 2024 slides](https://www.slideshare.net/slideshow/measures-in-sql-sigmod-2024-santiago-chile/269646979)
- [Apache Calcite CALCITE-4496](https://issues.apache.org/jira/browse/CALCITE-4496)
