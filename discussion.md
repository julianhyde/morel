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

## 2. Scalar expressions: open expressions vs lambdas vs matches

All three are equally expressive (any wraps any of the others); the
question is where names are stored. Open expressions require an
implicit "current element" convention, which quietly reintroduces the
ambient accumulated environment the tree exists to eliminate, and
they cannot address atomized elements (`from i in ints where i > 5`
has element type `int` — no label to reference) or destructured scans
(`from (a, b) in pairs`). Lambdas store names in their parameter
patterns, make scoping ordinary lambda calculus, and are what the
executor's closures already are. Resolution: lambdas uniformly —
`condition : elem -> bool`, `by : elem -> key`, projections
`elem -> elem'`. Record-pattern punning (`fn {d, e} => ...`) makes
binder names coincide with record labels in the common case.

## 3. Where names come from, and whether they are semantic

Morel has two kinds of names with different status. Record labels
live in element types, are observable (the default yield of
`from e in emps, d in depts` has type `{d: dept, e: emp}`), and are
canonically alphabetical. Binder names live in patterns and are
α-renamable. Because record labels are canonically sorted, position
carries no information; labels are the only addressing mechanism.
`$0`/`$1` would not be "positions with default names" — they would be
genuine labels, visible in output types.

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
* *Parameterized yield* (join carries
  `leftElem * rightElem -> outElem`) — adopted: commute swaps the
  lambda's arguments locally; reassociation recomposes the two
  lambdas involved; nothing above the node rewrites, because the
  output element type is pinned. Names live in the yield's record
  construction, checker-enforced. Conditions are lambdas.
  Correlation is `right : leftElem -> collection`. Everything
  reifies as plain Morel; planEx prints a real type at every node;
  MEMO groups key on (semantics, element type) with no side-channel
  metadata.

**Resolution: value-passing with parameterized-yield join is the
destination.** The bindings form is not a way-station (that would pay
plan-text churn and rewrite ports twice); it survives permanently as
the internal lowering IR for RowSink, unprinted.

## 8. Sequencing principle

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

## 9. Miscellany settled along the way

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
