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
(`filter`, `project`, `group`, `order`) binds `$0` to its input
element; a two-input node (`join`) binds `$0` and `$1` to its left
and right input elements. Those names are in scope in
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
  a name first; see §8. That is what a dependent join's binder is
  for: its right input is a tree of its own, so it cannot say `$0`
  and reach the left element.

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
  over `$0` and `$1`. Correlation is a binder on the join (§8).
  planEx
  prints a real type at every node; MEMO groups key on (semantics,
  element type) with no side-channel metadata.

**Resolution: value-passing with parameterized-yield join is the
destination.** The bindings form is not a way-station (that would pay
plan-text churn and rewrite ports twice); it survives permanently as
the internal lowering IR for RowSink, unprinted.

## 8. Correlation: the dependent join

A scan whose collection depends on an earlier binder — `from d in
depts, e in d.emps` — needs the outer element to reach the inner
expression. `$0` cannot carry it, because the inner expression is a
tree of its own and rebinds `$0`. So something must bind a name that
crosses the boundary by ordinary lexical scoping.

**Resolution: a `join` may carry a binder, read by its right input.**
Correlation is a join, and the yield is the join's yield, over `$0`
and `$1` like any other. Where the query wants only the inner
elements — `yieldAll r.items` — an ordinary `project` drops the left,
which is exactly what the step list does today: a scan over the
collection-valued expression, then a `yield` of the freshly bound
element.

The alternative, taken by an earlier draft of this document and of
spec.md §3.3, was `projectMany`: monadic bind, `α coll * (α -> β
coll) -> β coll`, with a lambda whose parameter named the outer
element. It reads well in isolation, and it is wrong in three ways
that only show up in the rest of the system.

* **It fuses two operations.** `projectMany` correlates *and* drops
  the left element. A query that wants both — `from d in depts, e in
  d.emps yield {d, e}`, which is the common case — has to rebuild the
  pair inside the lambda, so the node's own output is not what the
  query asked for and a projection inside the body compensates.
  Separating them makes the pairing the join's yield, where every
  other two-input node puts it.
* **It is the one node that does not bind `$0`.** The old §2 had to
  carry a rule for that, and every pass that walks nodes uniformly
  had to special-case it. With a binder on `join`, `$0` and `$1` mean
  what they mean everywhere, and the binder is an extra name, not a
  replacement for them.
* **Decorrelation becomes a rewrite rather than a simplification.**
  Under `projectMany`, decorrelating meant replacing the constructor
  and substituting `v ↦ $0` and the inner `$0 ↦ $1`. Under a
  dependent join it is *dropping the binder*: when nothing in the
  right input mentions it, the name goes and the node is already an
  ordinary join. Nothing above or below it changes.

What survives from the old argument is its best part. Dependence is
still not a mode of the node: the binder is a scoping device, and
dependence is a free occurrence of it, which the validator sees and a
rule can guard on. No metadata records it, and no correlation counter
exists.

And because an independent join is the better node — commutable,
reassociable, executable by something other than a nested loop — the
builder drops a binder the right input does not read. Decorrelation
is thereby paid at construction rather than deferred to a pass, and a
caller may offer a binder speculatively, before it knows whether the
right input will use it. That is only possible because dropping the
binder is the whole of decorrelation; under `projectMany` there was
no equivalent, because the constructor itself had to change.

**The outer apply comes for free.** The old design needed `ifEmpty`
inside the lambda, because flat-map has no element to map when the
body is empty and so cannot emit a row for an order with no matching
items. A dependent join whose kind is `left` emits that row already —
that is what `left` means — so outer apply is a kind, not a
construction. `ifEmpty` remains a node for its own sake; it is no
longer load-bearing for correlation.

The device still generalizes to correlated subqueries elsewhere:
`where exists (from d in depts where d.deptno = e.deptno)` binds the
outer element with `let v = $0 in ...` around the subquery. That is
the same lexical scoping, written with `let` because the enclosing
construct is an expression rather than a node.

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

## 11. Diagnostics when the IR has erased the name

The step list can say `pattern 'b' is not grounded`, because the scan
holds `b`. The tree holds a leaf `extent "int"` whose element is
`$0`, and there is no `b` in it, so the same failure could only be
reported as "cannot bound an `int`" — a regression in a message whose
whole job is to say *which* variable was not bounded.

Two ways to get a name back. A **diagnostic hint** on the leaf: a
field holding what the user wrote, ignored by typing, printing and
rewriting, read only when building a message. Or **positions**: the
leaf already carries a `Pos`, Morel's errors already print source
spans, and the span of `b` identifies the variable without naming it.

**Resolution: positions.** A hint is the advisory name of §3 in
another costume — every rewrite must carry it in tandem or it lies —
and although a rotted hint can only spoil a message, a message that
confidently names the *wrong* variable is worse than one that names
none, in exactly the situation where the reader is already confused.
Positions have the same tandem problem, but they are already carried,
already imperfect in the same way, and already the thing every other
Morel error points with; a span that drifts points at nearby code
rather than asserting something false. So the message becomes
`pattern is not grounded`, at the position of the pattern.

If that proves too vague in practice, the hint is the fallback, under
a rule that keeps it from lying: set once, at translation, and
dropped — not guessed — by any rewrite that replaces the leaf.

## 12. Grounding through a tree is stronger

Grounding an unbounded variable means finding a collection that
bounds it, by inverting the conditions. A step list can only pass
conditions along the list, so a `yield` between the scan and the
`where` stops it: `from x yield {y = x} where y elem [2, 3]` fails
today with `pattern 'x' is not grounded`, though `y` plainly is `x`.

A tree can do better, and cheaply. A condition above a projection is
a condition about the projection's expression, so substituting that
expression into the condition says the same thing about the element
below — and then the leaf is grounded by `[2, 3]`, as it should be.
It is one substitution, and the machinery was already there for
pushing conditions through a join.

**Resolution: the tree grounds strictly more, deliberately.** The
alternative was to keep parity by declining to push, which meant
carrying a limitation across the port for no reason other than that
it existed. Two consequences to plan for:

* Queries that error today start working. That is a change to the
  language, and the script expectations record it at the flip.
* The differential shadow can no longer treat "the tree grounds a
  query the step list rejects" as a failure. It counts it; what it
  guards instead is the reverse — a query the tree grounds *less*
  well is a gap, and the invariant to reach is that there are none.

The same substitution is why the translation's own projections stop
mattering. `from (b, i) : bool * int where p` translates to a filter
over a projection over the leaf, because the query's element is the
record the bindings describe while the leaf's is a tuple; pushing
through that projection is what lets the conditions reach the leaf at
all.

## 13. What the tree builder owns, and what the resolver owns

`FromBuilder` is 855 lines, and the plan's instruction was to build
trees through a builder but not to ape it. So: an inventory of what it
does, and of which parts a tree still needs.

Its responsibilities sort into two piles. The larger one exists
because a step list cannot say what is in scope, and cannot say what a
node's type is without knowing where the node sits.

* **Scope.** The builder holds `bindings` and an `atom` flag beside
  the steps, synthesizes a `StepEnv` for every step, re-syncs the
  fields from `step.env` afterwards, and rewrites every binding in
  scope when an outer join wraps them in `option`. A tree node's type
  carries its element type and expressions name their input `$0`, so
  there is no scope to thread.
* **Position.** Three separate fields —
  `removeIfNotLastIndex`, `removeIfLastIndex`, `scalarIfLastIndex`
  with its `scalarIfLastExp` — defer a decision about a step until the
  builder knows whether anything follows it. They exist because a
  query's result type is the environment of its *last* step, so a
  step's meaning depends on its position. In a tree a node's type is
  its own, a root is not a special kind of node, and "useless if last"
  is not a question that can be asked.
* **Nesting.** Around 130 lines splice a subquery's steps into the
  enclosing list, drop its trailing `yield`, and rebuild a projection
  to rename its bindings — with `safeToInline`, `isSimplePat`,
  `yieldsRecord` and `endsWithBindings` deciding when that is legal. A
  step list cannot contain a step list. A tree nests by construction.
* **Atomization.** The `atom` flag is passed *in*, not derived, and
  asserted consistent in three places. It is the difference between an
  element type of `int` and of `{i:int}` — a property of the type.
  `CoreBuilder.group` already writes it as `recordOrScalarType`.
* **Kind.** `ordered` is carried in `StepEnv`, asserted against
  `step.isOrdered(...)`, and recomputed by each set operator.
  `CoreBuilder.isOrdered` is `exp.type instanceof ListType`.
* **Name stability.** The `env2` parameter exists so that a `yield`
  does not regenerate `IdPat('a', 1)` where an expression already
  refers to `IdPat('a', 0)`. A tree has no names at step boundaries.

None of that is bad code; it is the step list's bill, itemized. And
`CoreBuilder`'s `Rel` methods already pay none of it: they take no
`StepEnv`, derive element type and kind from their inputs, and
validate by type. What they deliberately do *not* do is simplify —
`filter` builds a `Filter` even for `true`.

The smaller pile is semantic and survives any representation:
`distinct`'s unit case (`group {}` returns one row where `take 1`
returns zero), and ordinal materialization, which still needs a node
that evaluates once per row.

So what is left for a tree builder to own? One thing, and the
evidence for it is that two passes have now written it twice.
`RelTranslator` keeps a map from binder to an access expression over
`$0`; `RelLowerer` carries one element expression over the step
list's bindings. They are inverses — one eliminates variables, the
other reintroduces them — but underneath they share a shape:
"the element is the sole binding's value if it atomizes, otherwise a
record with one field per binding". That sentence is written four
times: `RelTranslator.elementType`, `RelLowerer.naturalElement`,
`FromBuilder.dropOrdinal`, and `CoreBuilder.fromElementType`. Beside
it, `element` and `naturalElement` both fold a name→expression list
into a record; the two id-substituting `Shuttle`s differ only in
whether the key is a user binder or `$0`; `over` and `rename` are
exact inverses; and `containsOrdinal` is duplicated verbatim.

The resolver has written it a fifth time. `FromResolver` calls
`fromBuilder.stepEnv()` twelve times — more often than any append
method — always as `withStepEnv(fromBuilder.stepEnv())`, and that
method reassembles the element expression from the bindings, atom or
record, and stores it as `Resolver.current`. That is what the user's
`current` keyword resolves to, and what the `elements` aggregate
reads. So the resolver asks the builder for a binding list in order to
rebuild a row that the builder could have handed it.

**Resolution: the builder does three things.**

1. **A stack of relational expressions.** A node takes its inputs
   from the stack and leaves its result there. `FromBuilder` had a
   linear `steps` list because a step list is linear; a tree needs
   the stack, and it is what makes an n-ary set operator or a join
   ordinary rather than special.
2. **A name map.** Names resolve to a path into an input: `e` is
   input #1, `deptno` is its second field. This is the
   element-expression bookkeeping, in the form that actually spans
   two inputs — `RelTranslator.access` is the same map for one.
   `current` falls out of it, which is why the resolver stops
   reassembling a row from bindings.
3. **Simplification, gated by an `EnumSet`.** Each simplification is
   named and can be switched off, individually or all at once.

Node construction, type and kind derivation and validation stay in
`CoreBuilder`. Scoping and name resolution stay with the resolver,
which gets *smaller* rather than larger: `current` stops being
derived from bindings on every step and becomes what the name map
already knows.

That also fixes the interface to aim for. Today the resolver asks
"what is in scope?" and reconstructs a row; afterwards it asks "what
is the element?" and gets it. The `stepPriorEnv` field and the
`materializeOrdinal`/`dropOrdinal` pair around it are the same wart
seen from the other side — a field spliced into the bindings and then
hidden again, so that the row the user sees is not the row the step
list carries — and they go when the row is a value rather than a
reassembly.

On the third: the fear was `FromBuilder`'s history repeating, and the
`EnumSet` is what prevents it. `FromBuilder`'s trouble was never that
it simplified; it was that the simplifications were unconditional,
entangled with the scope bookkeeping, and undiscoverable — three
position-dependent index fields, set in one method and applied in
another. A named, individually switchable set is none of those. It
also buys an oracle: with the set empty the builder is a pure
constructor, so the same query can be built twice and the two
compared, which is a sharper test than a golden file.

Measured, on the 1,852 trees the script suite translates: 154 of them
change under the full set, and 162 nodes go — so about one in twelve
queries is simplified, and almost every one that is loses exactly one
node. Small, which is the right size. A set that rewrote most trees
would be doing the rule framework's work in the constructor, and one
that rewrote none would not be worth the switch.

Where a simplification belongs is still a real question, and the
`EnumSet` defers rather than settles it. A rewrite that step 4's
framework will express as a rule should end up there; what stays in
the builder is what is cheaper to not build than to build and then
remove.

## 14. Trees have no atoms

`from i in [1, 2, 3] group j = i` has type `int list`, not `{j: int}
list`. Somewhere a one-label element becomes a bare value.

First, what is *not* the problem. Four shapes, and Morel's own
answers:

| query | type |
| --- | --- |
| `from i in [1,2,3] where i > 1` | `int list` |
| `from i in [1,2,3] group j = i` | `int list` |
| `from i in [1,2,3] yield {x = i}` | `{x:int} list` |
| `from i in [1,2], j in [3,4]` | `{i:int, j:int} list` |

A one-field record the user *writes* does not collapse; only bindings
do. And in the first row nothing is constructed at all: the leaf is a
collection of `int`, the filter keeps it so, and `i` is a *name* for
the element rather than a field of it. That is not atomization, and a
tree that made it `{i: int}` and unwrapped again would be adding a
projection and a map to the simplest query there is, for nothing.

So the discontinuity is narrower than a first look suggests, and it is
in two places.

**`group` constructs, and collapses when it constructs one field.**
`CoreBuilder.group` derives its element with
`TypeSystem.recordOrScalarType`, so one label gives a bare type and
two give a record. That is a genuine atomization, and the one a rule
will trip over: a rule that drops one of two group labels changes the
element's shape, so nothing above it rewrites locally.

**`Core.StepEnv.atom` is the flag that exists only because the step
list has names without expressions.** It says "one binding, and the
element is its value", which a pass then has to case on to know
whether the binding is `$0` or `#n $0`. A name map answers that
directly — it stores the expression — so the flag has nothing left to
say. `atom` is not a second problem; it is the first one's shadow in a
representation that cannot record paths.

**Resolution: `group` builds a record like everything else, and the
conversion to a bare value happens outside the tree.**

It cannot be a node. The invariant that buys the uniformity is that a
constructed element is a record; a node that breaks it puts the case
analysis back where rules live.

It does not need to be new. The design already does this once: §3.2
has `compute` yield a one-row collection with `Relational.only`
outside the tree doing the extraction. This is the same move one
dimension over — the tree yields `{j: int} bag` and the enclosing
expression maps it to `int bag`.

And it is a `map`, not an aggregate. An aggregate reduces a collection
to a value; this is elementwise and shape-preserving, so it is
`List.map #j` — a record selector lifted over the collection. No new
operator, and `CalciteCompiler` already pushes `Bag.map` down.

Consequences to plan for:

* The tree's root type stops being the query's type wherever a group
  atomizes; it is that type wrapped in a one-field record.
  `RelShadow` asserts the two are equal, and that assertion becomes
  one about the *wrapped* expression.
* Plan text changes for those queries, which is a step-3 rebaseline —
  the reason to decide this before the text is frozen rather than
  after.
* `atom` goes when the name map replaces `StepEnv`, which is the
  flip, not this. Until then the two coexist: the flag stays
  step-side, and the tree simply stops having the case.

Done, and it cost two lines and a deletion. `CoreBuilder.group` builds
a record; `RelLowerer` tells `FromBuilder` the group is not an atom,
so the lowered step list carries the same record; and the projection
that turns the record into the query's bare value is one the
translation was already inserting, because `normalize` adds a
projection wherever a node's element differs from what the bindings
describe. No boundary operator was needed after all -- the `map`
outside the tree that this section argued for is, in the translator's
hands, an ordinary `project [#j $0]` inside it, which is better,
because a projection is a node a rule can see and move.

The `map` is still the right answer where a tree is *given* rather
than built from a query -- a rule that rewrites a root, or an
implementation reading frozen plan text -- and the point stands that
it is elementwise, not an aggregate.

## 15. Does a join need a yield?

A join carries a yield expression over `$0` and `$1`, so it pairs its
inputs *and* projects them in one node. §7 chose that over fixed
pairs — a join emitting `leftElem * rightElem` with a record built
above — because n-ary joins nest the pairs, reassociation re-nests
them, and every access above has to re-path.

There is a third option §7 did not consider: the join emits the
*concatenation* of its inputs' components, and a projection follows
where the query wants something else. Flattening rather than nesting,
so the objection to fixed pairs does not apply.

**What counts as a component decides whether this works at all.** Not
the fields of the input's element: `emps` is a collection of eight-
field rows, and `from e in emps, d in depts` must yield two things,
`d` and `e`, not sixteen. The rule is that a *join* contributes its
own components and anything else contributes one:

    components(join(l, r)) = components(l) ++ components(r)
    components(anything else) = [it]

So `(A ⋈ B) ⋈ C` and `A ⋈ (B ⋈ C)` both have components `A, B, C` --
flat, three of them, and the same in both associations, which is the
property the whole idea rests on. And `from e in emps, d in depts`
has two, each a whole row, which is what the query means. Flattening
joins, not records.

**The yield is a pairing 495 times out of 497.** Measured over the
script suite: of the joins the translation builds, all but two have a
yield that is a record whose every field is `$0`, `$1`, or a field of
one of them. It renames; it does not compute. So the general
mechanism is paying for two queries.

And concatenation would make two rewrites free rather than merely
local. Morel's record types are sorted by label, so the
concatenation of two field sets is the same record whichever side
contributed which: **commute needs no substitution at all**, where
today it swaps `$0` and `$1` through the yield and the condition. For
the same reason `(A ⋈ B) ⋈ C` and `A ⋈ (B ⋈ C)` have the *identical*
element type, so **reassociation needs no compensating projection**,
where today it composes the two yields involved. Those are the two
rewrites a join planner does most.

It is also the argument that removed `projectMany` (§8), applied
again: a node that both pairs and projects is a node doing two
things, and the one that does one thing composes better.

One cost, and one apparent cost that is really an argument the other
way.

* **Field collisions need a convention.** Two inputs with the same
  field name have no concatenation by label. Addressing by ordinal —
  input ordinal and field ordinal — avoids the question entirely, at
  the price that commute renumbers and accesses above re-path. §5
  already owes a deterministic rename convention for scope-merging
  rewrites, and by label this makes it due earlier.

An earlier draft of this section listed a second cost, that each
input needs a projection naming it, because `from e in emps, d in
depts` wants `{d, e}` where `emps` is a collection of emp records.
That is true only of addressing by label. The cost is not there.

**The outer join is the case one expects to be the problem, and it is
the strongest argument for concatenating.** §3.4 records what Morel
does: it "makes each *binder* of the absent side an option, not the
side as a whole — `left join (j, k) in pairs` binds `j : int option`
and `k : int option`, not `(int * int) option`".

Read against the three designs, that sentence decides between them.

* **A yield** must express the distribution itself: §3.4's next
  clause is "a yield that reads more than one binder maps each access
  through the option, with `Option.map`". That machinery is what the
  suite's two non-pairing yields are made of.
* **A pair** — a join emitting exactly two fields, the left element
  and the right — gives `(int * int) option` on the absent side,
  which is the shape §3.4 says Morel does *not* have. A projection
  above must distribute the option before the element is the query's,
  so the `Option.map` does not go away; it moves.
* **A concatenation** gives each *component* of the absent side its
  own option, and a component is a binder's value, which is Morel's
  rule exactly, derived by the node from its kind. Nothing
  distributes anything.

So concatenation is the only one of the three that needs no
`Option.map` in an expression, and "the node stays simple" cuts for
it rather than against.

The pair also loses on the ground §7 rejected fixed pairs: `(A ⋈ B) ⋈
C` nests, so reassociation re-paths every access above. Concatenation
is flat, which was the reason to look past §7's two options at all.

**Resolution: the join concatenates, and a projection follows where
the query wants something else.** Not yet done. It should land before
step 3 freezes the plan text, and it wants §14 settled first, because
"what a leaf's element is" is the same question §14 answered for
constructed elements.
