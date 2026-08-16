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
# Design record: balanced relational tree for Core

A record of the design discussion, organized by topic. Positions are
recorded with their objections and resolutions; rejected alternatives
are kept because the Rust and Go implementers will re-ask these
questions.

## 1. Why a tree, and what survives from today

The step list is a left-deep tree in disguise, so the conversion is
mechanical in one direction: any tree can be linearized left-deep,
which is exactly what nested-loop execution over RowSink does. Hence
the execution story never changes — the tree is the logical IR, a
linearizer lowers it to the environment-passing left-deep form, and
RowSink code generation proceeds as today. The asymmetry of RowSink
becomes a property of the physical strategy rather than of the IR,
which is where it belongs; a future hash join is the first thing that
would break left-deep-ness, and it is out of scope.

## 2. Scalar fields: lambdas vs expressions over numbered inputs

Conditions, projections, sort keys and group keys have to address
the element(s) flowing into their node. Three forms were considered —
open expressions over an ambient environment, lambdas whose parameter
pattern binds the element, and expressions over inputs the node names
— and all three are equally expressive, so the question is only where
names are stored.

Open expressions over the *accumulated* environment are the step
list's own convention and are rejected for the reason the tree exists:
what an expression may reference depends on how far along the pipeline
it sits, so a node is not readable in isolation and rewrites must
recompute scopes.

Lambdas (`condition : elem -> bool`, `by : elem -> key`, projections
`elem -> elem'`) make scoping ordinary lambda calculus, are
α-renamable, are what the executor's closures already are, and reify
as plain Morel. Their cost is a layer of indirection that every rule
pays: matching `Filter(input, cond)` means matching through
`Fn(pat, body)` and inverting the parameter *pattern* — which may be a
record pattern, a tuple pattern, a wildcard, or a variable — before a
rule can tell what the condition reads. Worse for the
cross-implementation contract, α-equivalent spellings are the same
plan, so the printer needs a canonical naming convention anyway, and
the convention then exists in two places (the printer's and the
translator's).

**Resolution: expressions over numbered inputs.** A one-input node
(`filter`, `project`, `group`, `order`, `projectMany`) binds `$0` to
its input element; a two-input node (`join`) binds `$0` and `$1` to
its left and right input elements. Those names are in scope in
addition to the environment enclosing the tree, and in place of
nothing else: an expression sees its node's inputs and the outside
world, never the bindings of nodes further down.

That last clause is what separates this from the open-expression form
it superficially resembles. `$0` does not accumulate; every node
rebinds it to its own input. `Filter` under a five-node chain reads
exactly what `Filter` over a leaf reads, so a node remains readable —
and matchable — in isolation, which was the whole point.

Atomization needs no special case, because `$0` denotes the element
whatever its type: `from i in ints where i > 5` is `filter ($0 > 5)`
over a leaf of element type `int`, with no label to invent, and a
destructured scan `from (a, b) in pairs` addresses components as
`#1 $0` and `#2 $0`. Names for the *output* still come from record
construction in the projection or the join's yield, so §3's
semantic-label argument is untouched: `$0` and `$1` are how a node
reads, never how a type is spelled.

Well-formedness, checked by the validator:

* `$0` (and `$1`) occur only in expressions the node evaluates per
  row.
* Expressions evaluated before the first row — the arguments of
  `skip` and `take` (SKIP and LIMIT), which the tree evaluates before
  it has an element — are evaluated once in the enclosing environment,
  and an occurrence of `$0` in them is an error. This is a real rule,
  not a formality: it is what makes `take` and `skip` arguments
  constant-foldable and hoistable, and it is the reason they cannot
  silently become correlated.
* An expression that contains a nested tree rebinds `$0` inside it,
  so an outer element that must reach into a nested tree is bound to
  a name first; see §8. `projectMany` is the one node whose input
  element is named by a lambda parameter rather than by `$0`, for
  that reason.

Reification (#359) loses nothing: a node's expression wraps
mechanically as `fn $0 => e`, which is the lambda form, recovered on
demand rather than carried everywhere.

## 3. Where names come from, and whether they are semantic

Morel has two kinds of names with different status. Record labels
live in element types, are observable (the default yield of
`from e in emps, d in depts` has type `{d: dept, e: emp}`), and are
canonically alphabetical. Binder names live in patterns and are
α-renamable. Because record labels are canonically sorted, position
carries no information; labels are the only addressing mechanism
*within* an element.

`$0` and `$1` (§2) are not a counter-example. They are input
references, bound by a node and consumed by its own expressions; they
never appear in an element type, never become record labels, and are
not an ordinal encoding of a field. Positions address *inputs*, where
position is exactly the right thing — a join's left and right are
genuinely ordered — and labels address *fields*, where sorting has
made position meaningless.

Advisory (non-semantic) names were considered and rejected on the rot
argument: names that carry no semantics but must be permuted in
tandem by every rewrite are maintained by discipline alone; the first
rule that forgets produces plans whose names lie, which is worse for
debugging than honest positions. Calcite's `$f2` residue and the
current unreifier's `$scan`/`$join` fallbacks are both this failure
mode. Keeping advisory names accurate costs the same tandem
bookkeeping as keeping semantic labels accurate, but semantic labels
get the type checker as enforcer for free. If names are worth
printing, they are worth making load-bearing.

## 4. Flattening and depth

Not required, and not even a convention. The invariant is: every
node's element type is exactly the logical Morel type of the value
flowing there — depth 0 (`int`, atomized single-scan chains), depth 2
(`{e: {sal, ename}, d: {dname, deptno}}`), arbitrary nesting,
non-record types including functions. Depth-1 rows are Calcite's
flat-row assumption, which costs it `RelStructuredTypeFlattener`;
Morel drops the assumption. Depth-1 nonetheless feels natural because
it is the *physical* representation: lowering un-nests one level of
the element into `EvalEnv` slots. That flattening lives in the
executor and is invisible to the logical plan.

## 5. Field order: the trilemma

Alphabetical canonical order is inherited from Morel record
semantics, and it removes the classic reordering pains: commuting a
join leaves the output type invariant, so conditions need no rewrite
and no compensating Project appears (contrast Calcite's
`JoinCommuteRule` plus `Mappings`). "A rule that reorders fields" is
a logical no-op. But alphabetical order interleaves contributions
(left `{a, c}` + right `{b}` = `{a, b, c}`), so joins cannot build
output rows by pure concatenation.

Three properties are jointly unsatisfiable — any convention gets two:

* **join as pure concatenation** (physical order = construction
  order of the current tree);
* **path-independent plan text** (printed form a function of the
  logical plan, not of rewrite history — required because plan text
  is the cross-implementation contract);
* **type-invariance under commute** (no compensating projections, no
  reference rewrites).

Alternatives considered:

**Plan A — positional tuples with advisory names, flatten on entry
to the query subtree, re-nest on exit.** Buys concatenation,
structural collision-freedom, a near-1:1 bridge to a future SQL
structure, and legal-Morel reification via `#n` access. Costs: the
ordinal tax returns in full (commute changes the output type;
`Mappings` machinery in three implementations); deep flattening hits
`RelStructuredTypeFlattener` territory the moment an expression
consumes an element whole (`where isGood e`) or a field is a list or
function; advisory names rot (§3). A shallow variant (one position
per contribution, fields keeping their whole Morel types) dodges the
flattener but keeps the ordinal tax.

**Plan B — named fields with alphabetical sorting disabled.** Fixes
Plan A's rot problem (names become checker-enforced) but is
underdetermined: if order is type-significant, it is Plan A with
prettier names (commute changes the type again); if order is not
type-significant, plan text becomes history-dependent, breaking the
cross-implementation contract.

**Plan B′ — order-insignificant labels with canonical order defined
as source-introduction order** (the left-to-right order of the
query's binding introductions, stable under all rewrites). Gets free
commute, checker-enforced names, path-independent text, and plans
that read in query order; sacrifices concatenation only for subtrees
the planner has actually reordered, where a compile-time name→slot
gather costs the same k writes at runtime.

**Resolution for this refactor: keep the field structure unchanged**
(alphabetical canonical order; gathers at lowering). The
representations above are recorded for the later revisit, motivated
by making rules easier to write. The trilemma is the decision
criterion when that revisit happens.

## 6. Label collisions

Per-node label distinctness is an invariant. Within one query scope,
surface translation guarantees it and within-scope rewrites (commute,
reassociation, pushdown) only move existing names around a scope in
which they are already distinct. Scope-merging rewrites —
decorrelation, subquery unnesting — can collide and require a
deterministic renaming convention, applied at the merge point,
specified in the plan-format spec (not left to implementations, or
Java/Rust/Go produce textually different plans from the same
rewrite). Renames confined to non-root-visible fields preserve the
root type.

## 7. Bindings vs values: what a node outputs

The AST-level rule is clean: bindings(join) = bindings(left) ⊎
bindings(right), disjoint union, with the element type *derived*
(singleton binding atomizes to its bare type; two or more yield a
record; zero yields unit). Output type is a function of input
bindings, not of input element types — see the `{v: {x, y}}` vs
`{x, y}` example in issue.md. This forces a choice for Core:

**Environment-passing (bindings) core.** Nodes emit environments;
binding lists are node metadata; expressions reference names; element
types materialize only at observation boundaries. Minimal delta from
today, identity-shaped lowering. But nodes are not self-describing
(the unreifier heuristics are the symptom), nodes do not compose as
functions, and reification must invent a Morel representation for "a
set of bindings", which is not a Morel value.

**Value-passing core.** Every node emits elements of a definite type;
the tree is a closed algebra of collection→collection operators.
Join must then say what value it emits:

* *Fixed pairs* (join emits `leftElem * rightElem`, record
  construction in a Yield above) — rejected: n-ary joins nest pairs,
  reassociation re-nests, downstream accesses re-path. The ordinal
  tax in structural clothing.
* *Parameterized yield* (join carries a yield expression over `$0`
  and `$1`, e.g. `{d = $1, e = $0}`) — adopted: commute swaps the
  two inputs and substitutes `$0`↔`$1` in the yield and the
  condition, a purely local textual rewrite; reassociation composes
  the two yields involved; nothing above the node rewrites, because
  the output element type is pinned. Names live in the yield's
  record construction, checker-enforced. Conditions are expressions
  over `$0` and `$1`. Correlation is `projectMany` (§8). planEx
  prints a real type at every node; MEMO groups key on (semantics,
  element type) with no side-channel metadata.

**Resolution: value-passing with parameterized-yield join is the
destination.** The bindings form is not a way-station (that would pay
plan-text churn and rewrite ports twice); it survives permanently as
the internal lowering IR for RowSink, unprinted.

## 8. Correlation: `projectMany`

`project` maps an element to one element; `projectMany` maps it to
many. Its expression is evaluated with `$0` bound to the input
element and must have a collection type; the node's element type is
that collection's element type, and its kind follows the usual
signatures. `from e in emps, d in e.depts` is a `projectMany` whose
expression mentions `$0`; `from e in emps, d in depts` is one whose
expression does not.

This is why there is no separate `dependentJoin` constructor.
Dependence is not a mode of a node, it is a property visible in the
node's expression — an occurrence of `$0` under a collection-typed
expression — and the validator can see it, a rule can guard on it,
and no metadata records it. Decorrelation becomes a rule with a
syntactic guard: when the collection expression stops mentioning
`$0` (because a preceding rule pulled the correlated part out), the
node is a cross join and `join` replaces it.

**How the outer element reaches the pairing.** `projectMany` emits
the inner elements, but `from d in depts, e in d.emps` must emit
pairs, so something has to combine the outer element with each inner
one, and the natural place is a nested query:

```
projectMany depts (fn d => from e in d.emps yield {d, e})
```

which in Core is a `projectMany` whose argument is a lambda whose
body is a `project` over the leaf `d.emps`, with the projection
expression `{d = d, e = $0}` — `$0` being the inner element and `d`
the lambda's parameter.

So the outer element is named by an ordinary lambda binder. This is
not a retreat from §2: it is the one place where a scope crosses a
tree boundary, and `$0` cannot cross it, because the nested tree
rebinds `$0` to its own input. A named binder crosses it by ordinary
lexical scoping — no sigil, no correlation counter, no yield on the
node, and no descent into scalar expressions where rules cannot look.
`projectMany` is exactly monadic bind, `α bag * (α -> β bag) -> β
bag`, and the lambda is a plain Core lambda, so reification is
nothing.

Two consequences worth stating:

* **`projectMany` is the one node that does not bind `$0`.** Its
  input element is named by its lambda's parameter, precisely because
  its argument is the one expression that routinely contains a tree.
  Every other node binds `$0` (and `join` also `$1`) as §2 says.
* **The device generalizes.** Any expression that contains a nested
  tree shadows `$0` inside it, so a correlated subquery elsewhere —
  `where exists (from d in depts where d.deptno = e.deptno)` — binds
  the outer element the same way, with `let v = $0 in ...` around the
  subquery. It is the same mechanism, written with `let` instead of
  `fn` because the node's own argument is not a function.

Decorrelation stays a rule with a syntactic guard, now stated over
the lambda: when no leaf inside the body has a free occurrence of the
parameter, the collection does not depend on the outer element, and
`projectMany input (fn v => body)` becomes a `join` whose right input
is that leaf and whose yield is the body's projection with `v ↦ $0`
and the inner `$0 ↦ $1`.

## 9. Sequencing principle

The two expensive costs are plan-text churn (test files plus the
cross-implementation contract) and rewrite ports. A coherent sequence
pays each exactly once: shadow the tree behind round-trip converters
while `Core.From` still does the work; flip plan text once, in final
form; flip execution; land the rule framework *before* porting
rewrites and express the ports as its first rules (the Calcite
lesson: the framework's first clients are real optimizations, keeping
it honest); reification and MEMO last, as views and engines over a
datatype that already exists. Unorder pushdown and decorrelation are
clients of the sequence, not steps in it.

## 10. Miscellany settled along the way

`UNORDER` must be in the constructor set (it was the motivating
rewrite) and every constructor needs a stated bag/list kind signature
(`ORDER : bag -> list` is load-bearing; kind of `join` transcribed
from current step semantics, not redesigned). Union branch alignment
carries over current behavior. Generated labels need a pinned
collation against user labels, since Rust and Go must sort
identically. Root-type preservation, asserted by the validator after
every rule firing, is the cheap litmus that catches most rule bugs;
type-invariance of join reordering over a fixed binding set is a
theorem the validator can assert after every rewrite.
