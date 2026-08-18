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
# Spec: the relational tree (step 0 of the #449 plan)

The datatype, its typing rules, its scoping rules and its printed
form. This is the document morel-rust (hydromatic/morel-rust#33) and
morel-go implement against; where it disagrees with an
implementation, this document is right.

Status: **draft for review**. It is frozen at the end of step 0, and
after step 3 the printed form is a golden-file contract that costs a
coordinated change across three implementations to alter. Sections
marked *Review* are the ones where a decision was made here rather
than transcribed from current behavior. Rationale for the design is
in discussion.md; the sequence is in plan.md.

## 1. What a node is

A node denotes a collection. Its type is a *kind* — `list` or `bag` —
applied to an *element type*, which may be any Morel type at any
depth: a record, a tuple, an `int`, a function, another collection.
There is no row/scalar distinction and no flat-row assumption.

Two things that `Core.From` carries today do not exist here:

* **No binding list.** A node's element type is derived from the node
  and its inputs (§4), and is exactly the type of the value flowing
  out of it. `Core.StepEnv` — bindings, `atom`, `ordered` — has no
  counterpart.
* **No `atom` flag.** Atomization was the rule that a single binding
  yields its bare type rather than a one-field record. In the tree it
  is not a rule at all: an element's type is the type of the
  expression that constructs it, so `project [#sal $0]` has element
  type `real` because `#sal $0 : real`, and nothing had to decide.

The tree is a closed algebra: every constructor takes collections and
returns a collection. A node *is* a Core expression — `Core.Rel`
extends `Core.Exp` — whose type is a collection type. Hence a query
may appear anywhere an expression may, including inside the
expressions of another tree (§3.3), and an input needs no wrapper: it
is just an expression (§3.1).

## 2. Names

An expression inside a node may refer to:

* the names of the environment enclosing the tree (globals, `let`
  bindings, function parameters — anything the surrounding Core
  expression has in scope);
* `$0`, the element of the node's input;
* `$1`, for `join` only, the element of the node's right input, `$0`
  then being the element of its left input.

It may not refer to anything else — in particular not to the elements
of nodes further down the tree. `$0` is rebound by every node to its
own input, and does not accumulate.

Three rules complete the picture:

1. **Before the first row.** The arguments of `skip` and `take` are
   evaluated once, before any element exists. They see the enclosing
   environment only; an occurrence of `$0` or `$1` in them is
   ill-formed.
2. **`projectMany` names its input.** Its argument is a lambda
   `fn v => c`, and `v`, not `$0`, denotes the input element. This is
   the one node whose argument routinely contains a nested tree,
   which would shadow `$0`; a lambda-bound name crosses that boundary
   by ordinary lexical scoping. See discussion.md §8.
3. **Nested trees shadow.** Inside a tree that appears within an
   expression, `$0` is that tree's own input element. To use the
   outer element inside a nested tree, bind it first —
   `let v = $0 in <tree mentioning v>` — which is the same device as
   rule 2, written with `let` because the node's argument is not a
   function.

`$0` and `$1` are input references. They are never record labels,
never appear in an element type, and are not an ordinal encoding of a
field: fields are addressed by label, inputs by position.

## 3. Constructors

`r`, `r₀`, `r₁` are nodes; `e` is an expression; `τ` is the element
type of `r`, `τ₀`/`τ₁` those of `r₀`/`r₁`. "Scope" says what the
expressions of the node may name beyond the enclosing environment.

### 3.1 Leaves

There is no leaf constructor. A node is itself an expression whose
type is a collection type, so a node's input is simply an expression:
another node, or a leaf — a global (`scott.emps`), a variable, a list
literal, a function application, anything of collection type. The
boundary between the tree and the rest of Core is therefore not
marked by an operator; it is wherever the expression stops being a
node.

A leaf binds nothing: its element flows out as a value, and the node
above it names that value `$0`.

A query with no scan — `from where p`, `from yield e`, or bare
`from` — iterates over a single element, which is unit, so its leaf
is `[()]`. Morel's own semantics are already this: `from where false`
and `from u in [()] where false` both evaluate to `[] : unit list`,
and `from yield 1 + 2` and `from u in [()] yield 1 + 2` both to
`[3] : int list`. The tree writes down the element the query left
implicit, so such a query prints a leaf its author did not write:

```
project [1 + 2]
  [()]
```

That is the only intended difference, and it is in the plan text
alone. A distinguished empty leaf, printing as nothing, was
considered and rejected: it buys a little less noise in a rare query
shape at the price of a constructor and of an exception to "a leaf is
just an expression".

### 3.2 One input

| Constructor | Arguments | Element type | Scope |
| --- | --- | --- | --- |
| `filter` | `cond : bool` | `τ` | `$0` |
| `project` | `e` | type of `e` | `$0` |
| `group` | keys `l₁ = e₁, …`, aggregates `m₁ = a₁, …` | record of the key and aggregate types, or the single field's type if there is exactly one | `$0` |
| `ifEmpty` | `e` of type `τ` | `τ` | — (§2 rule 1) |
| `sort` | `e` | `τ` | `$0` |
| `unorder` | — | `τ` | — |
| `skip` | `n : int` | `τ` | — (§2 rule 1) |
| `take` | `n : int` | `τ` | — (§2 rule 1) |

`group` keys and aggregate arguments are expressions over `$0`;
labels `l`, `m` are the output record's labels, and must be distinct.
`distinct` is not a constructor: it is `group` whose keys are the
whole element and whose aggregate list is empty.

`ifEmpty` yields its expression as the single element where its input
has none, and its input's elements where it has some. The expression
is evaluated only in the first case, when there is no element, so
like the count of a `skip` it cannot mention `$0`; it can mention
whatever encloses the tree, which inside the body of a `projectMany`
includes that node's parameter. It is what makes an apply outer
(§3.3).

`compute` is `group` with no keys, plus the extraction of the single
element that the enclosing expression performs — see §6, *Review*.

### 3.3 `projectMany`

| Constructor | Arguments | Element type | Scope |
| --- | --- | --- | --- |
| `projectMany` | `fn v => c`, `c` of collection type | element type of `c` | `v` names the input element |

`project` maps an element to one element; `projectMany` maps it to
many, and is exactly monadic bind: `α coll * (α -> β coll) -> β
coll`. It is what a dependent scan becomes:

```
from d in depts, e in d.emps yield {d, e}
```

is

```
projectMany depts (fn d => project d.emps [{d = d, e = $0}])
```

Dependence is not a mode of the node; it is the presence of a free
occurrence of `v` in a leaf of the body, which the validator sees and
a rule can guard on. There is no separate dependent-join
constructor.

**An outer apply is `ifEmpty` inside the lambda.** A correlated outer
join — `from r in orders left join i in r.items on p` — yields a row
for an order none of whose items satisfy `p`, which flat-map cannot
do, because it has no element to map. The `ifEmpty` node (§3.2)
supplies that row, and it sits in the body, where `v` is in scope:

```
projectMany
  orders
  fn r =>
    ifEmpty [{i = NONE, r = r}]
      project [{i = SOME $0, r = r}]
        filter [#units $0 > 2]
          #items r
```

`projectMany` itself stays a pure bind with one argument. An earlier
draft made `ifEmpty` a second, optional argument of `projectMany`,
and it was wrong for a reason worth recording: its expression reads
`r`, which only the lambda binds, so an argument of the node would
have been printed outside the binder it depends on. As a node in the
body it is inside that scope, and it composes — it is an ordinary
collection→collection operator that a rule can move.

The `SOME` and the `NONE` are written by the expressions rather than
implied by any node, because Morel has no null to fill a row with:
only the query knows what the absent row's value is.

### 3.4 Two inputs

| Constructor | Arguments | Element type | Scope |
| --- | --- | --- | --- |
| `join` | kind ∈ {inner, left, right, full}, `cond : bool`, yield `e` | type of `e` | `$0` (left element), `$1` (right element) |
| `union`, `intersect`, `except` | `r₀ … rₙ`, `distinct : bool` | `τ₀` | — |

For an outer join the absent side is an `option`, which transcribes
current behavior: `from a in [1, 2] left join b in [1] on a = b` has
type `{a: int, b: int option} list`.

**The condition and the yield see different types on that side.** The
condition is evaluated on candidate pairs, where both elements are
present, so it sees `$0 : τ₀` and `$1 : τ₁` whatever the join's kind.
The yield is evaluated once per output row, including rows that
matched nothing, so on a side the join can leave absent it sees an
`option`: `$1 : τ₁ option` in a `left` join, `$0 : τ₀ option` in a
`right` join, both in a `full` join.

The asymmetry is the standard one — it is what every relational
executor does, and it is what makes `on a = b` mean what it says
rather than `valOf`-ing an option that is never `NONE` at that point.
It costs the reader one rule, and the alternative (an option in the
condition too) costs every outer join a partial function in its plan
text. So, for `from a in [1, 2] left join b in [1] on a = b`:

```
join [left] [$0 = $1] [{a = $0, b = $1}]
  [1, 2]
  [1]
```

where `$1` is `int` in the condition and `int option` in the yield.

Morel makes each *binder* of the absent side an option, not the side
as a whole — `left join (j, k) in pairs` binds `j : int option` and
`k : int option`, not `(int * int) option` — so a yield that reads
more than one binder maps each access through the option, with
`Option.map`. The node stays simple; the arithmetic of which value
becomes `NONE` is in the expression, where a rule can see it.

Set operators require the element types of all their inputs to be
equal. They are n-ary; `distinct` distinguishes `union` from `union
all`.

Commuting a join swaps its inputs and substitutes `$0` ↔ `$1` in the
condition and the yield. The element type is unchanged, so nothing
above the node rewrites, and no compensating projection appears.

## 4. Kinds

The kind of a node's output, given the kinds of its inputs. All rows
are transcribed from current step behavior, not redesigned; the
"checked" column names the query that pins it.

| Constructor | Output kind | Checked by |
| --- | --- | --- |
| leaf `e` | kind of `e` | — |
| `filter`, `project`, `skip`, `take`, `ifEmpty` | kind of input | `from i in [1,2] where i > 1` is a `list` |
| `projectMany` | `list` if the input is a `list` and the lambda's body is a `list`, else `bag` | `from i in [1,2,3], j in bag [i]` is a `bag` |
| `join` | `list` if both inputs are `list`, else `bag` | as above (a join is a nested loop) |
| `group` | kind of input | `from i in [1,2,3] group j = i` is a `list` |
| `sort` | `list` | — |
| `unorder` | `bag` | — |
| `union`, `intersect`, `except` | `list` if every input is a `list`, else `bag` | `from i in [1,2] union [3]` is a `list` |

`sort : coll -> list` and `unorder : coll -> bag` are the pair that
`unorder` pushdown manipulates, and the reason kinds are in the
signature rather than a property of the runtime value.

## 5. Well-formedness

The validator checks these after translation and after every rule
firing. They are the contract a rule must preserve, and the first
place to look when a rule is wrong.

1. **Types.** Every expression type-checks in the scope §2 gives it.
   Every node's element type is the type §3 derives. Set-operator
   inputs agree. `filter` and `join` conditions are `bool`; `skip`
   and `take` arguments are `int`; a `projectMany` body has a
   collection type.
2. **Kinds.** Every node's kind is the kind §4 derives.
3. **Scope.** No `$0` outside a node that binds it, no `$1` outside a
   `join`, neither in a `skip` or `take` argument, and no free
   variable other than those and the enclosing environment's.
4. **Labels.** Within one node, output labels are distinct: the
   fields of a `project` or `join` yield record, and the keys and
   aggregates of a `group`.
5. **Root type.** A rewrite preserves the type of the tree's root —
   both element type and kind. This is the cheap litmus that catches
   most rule bugs, including every rule that forgets a yield.

Rewrites that merge scopes — decorrelation, subquery unnesting — can
bring two identically-named binders together. The rename convention
is deterministic and specified here rather than left to
implementations, so that Java, Rust and Go print the same plan for
the same rewrite: *Review* — the convention is written when the first
scope-merging rule lands in step 5, and until then the validator
rejects the collision.

## 6. Plan text

*Review.* The grammar below is a proposal; §7 shows it working. Once
step 3 flips `Sys.plan` to print it, changing it is a coordinated
change across three implementations and every golden file.

One node per line. A node's inputs are the lines below it, indented
by two spaces. A line is an operator name followed by its arguments,
each in brackets, in the order §3 lists them; arguments that are
absent (an inner join's kind, a `true` condition, a `project` yield
that is `$0`) are omitted.

```
plan     ::= node
node     ::= indent op arg* '\n' node*
           | indent exp '\n'                    -- a leaf
op       ::= 'filter' | 'project' | 'projectMany' | 'ifEmpty'
           | 'join' | 'group' | 'sort' | 'unorder'
           | 'skip' | 'take' | 'union' | 'intersect' | 'except'
arg      ::= '[' exp ']' | '[' label '=' exp (',' label '=' exp)* ']'
           | '[' word ']'
```

Expressions inside brackets are printed as Morel, by the same
unparser that prints Core expressions elsewhere, so a field access
appears as `#deptno $0` (Morel's `e.deptno` is sugar for `#deptno e`)
and a record construction as `{d = $1, e = $0}`.

`Sys.planEx` prints the same tree with `: type` appended to every
line, the type being the node's full collection type.

Generated labels sort with user labels under one collation, pinned
here so that three implementations agree: labels compare as Morel
strings compare, which puts `$`-prefixed names before alphabetic
ones.

*Review.* `compute` has no line of its own: `from … compute` prints
as its `group`, and the extraction of the single element belongs to
the Core expression that wraps the tree. The alternative — a
`compute` node whose type is a scalar — buys a shorter plan at the
cost of a constructor that is not collection-valued, which every rule
would then have to case on. Recommended as written.

## 7. Worked examples

The join from the issue:

```sml
from e in scott.emps
  join {dname, deptno = id, ...} in scott.depts on e.deptno = id
```

```
join [#deptno $0 = #deptno $1] [{dname = #dname $1, e = $0, id = #deptno $1}]
  scott.emps
  scott.depts
```

The pattern's binders (`dname`, `id`) and the record punning have
become field accesses in the yield; the element type is the yield's
type, and no binding list records what `dname` used to mean.

A correlated scan:

```sml
from d in scott.depts, e in d.emps where e.sal > 1000 yield {d, e}
```

```
projectMany
  scott.depts
  fn d =>
    project [{d = d, e = $0}]
      filter [#sal $0 > 1000]
        d.emps
```

*Review.* A lambda whose body is a tree cannot print inside brackets
on the operator's line and stay readable, so `projectMany` prints its
input as its first child, then a `fn v =>` header, then the body as a
further-indented child — a node if the body is a node, a leaf line if
it is a plain expression such as `d.emps`.

Group:

```sml
from e in scott.emps
  group deptno = e.deptno compute total = sum over e.sal
```

```
group [deptno = #deptno $0] [total = sum over #sal $0]
  scott.emps
```

## 8. What this replaces

`Core.From`'s step list, `Core.FromStep` and its subclasses, and
`Core.StepEnv` stop being the logical representation. They survive as
the lowering target: the tree linearizes left-deep, `$0` and `$1` and
the field accesses on them become `EvalEnv` slots, and `RowSink`
execution is unchanged. That form is no longer printed, and step 2
may dissolve it into the lowerer entirely.
