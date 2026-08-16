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
# Plan: Core query representation, step list → balanced tree

Destination: value-passing relational tree (parameterized-yield join,
lambdas for scalar fields, self-describing element types), able to
apply rewrite rules. Each step keeps all tests green. Plan text and
rewrite ports are each paid exactly once.

## Step 0 — Freeze the datatype and the plan-text grammar

- [ ] Constructor set: SCAN-free leaves (bare expressions), FILTER,
      JOIN (with yield lambda `leftElem * rightElem -> outElem`),
      DEPENDENT_JOIN semantics via `right : leftElem -> collection`
      (decide: distinct constructor vs detectable-by-use), PROJECT,
      GROUP (key/agg shapes), ORDER, UNORDER, LIMIT, SKIP, UNION,
      INTERSECT, EXCEPT, COMPUTE; DISTINCT desugars to GROUP; AND/OR
      n-ary.
- [ ] Per-constructor bag/list kind signatures, transcribed from
      current step semantics (ORDER : bag -> list; UNORDER; kind of
      join; set operators).
- [ ] Scoping invariants: free variables of every embedded lambda
      body beyond its parameters must be bound by enclosing dependent
      joins or the environment outside the tree; per-node label
      distinctness; deterministic rename convention at scope merges.
- [ ] Element-type derivation specified normatively, including
      singleton atomization and the zero-binding (unit) case.
- [ ] Plan-text grammar for Sys.plan / Sys.planEx, written once in
      final form: this is the contract morel-rust and morel-go
      implement. planEx prints the element type at every node.
      Pin the collation of generated labels against user labels.
- [ ] Record rejected alternatives (pair-based join; advisory names;
      row-representation Plans A/B/B′) in discussion.md.

## Step 1 — Shadow tree (no behavior change)

- [ ] Datatype, AST→tree translation (variable elimination: pattern
      bindings become lambda parameters and record constructions),
      type derivation, validator, printer.
- [ ] tree→From converter as scaffolding.
- [ ] CI asserts, for every query in the suite: round-trip fidelity
      (AST→tree→From equals AST→From) and agreement of derived
      element types with the typechecker.

## Step 2 — Flip observability

- [ ] Sys.plan and Sys.planEx print the tree.
- [ ] Script-convert test expectations (one flip, final format).
- [ ] Plan text is now frozen; golden files are the
      cross-implementation contract. Rust (morel-rust#33) and Go
      work can begin here, in parallel with steps 3–5.

## Step 3 — Flip execution

- [ ] Lowering: tree → left-deep environment-passing form → RowSink;
      lambda patterns dictate EvalEnv slots; name→slot gathers where
      canonical label order diverges from construction order.
- [ ] Delete the AST→From path. From's step list becomes an
      unprinted lowering artifact or dissolves into the lowerer.
- [ ] Script tests unchanged and passing (behavior identical).

## Step 4 — Rule framework

- [ ] Pattern + guard rules over the tree; deterministic Hep-style
      driver.
- [ ] Validator runs after every rule firing; root-type preservation
      asserted.
- [ ] FromBuilder builds trees natively; begin RelBuilder-style
      conveniences as rules need them.

## Step 5 — Port rewrites as rules

- [ ] Existing Core optimizations (inliner interactions, suchThat,
      step-list pattern matches) re-expressed as rules — the
      framework's first clients.
- [ ] Retire the old rewrite code in the same motion.

## Step 6 — The #359 layer

- [ ] Plan.core reification as a view of the same datatype;
      Plan.bodyOf; closures retaining Core.
- [ ] User-written Morel rules compiling into the step-4 framework.
- [ ] Reactor / MEMO / guard-dependency machinery as the second
      engine beside Hep.

## Follow-ups (separate issues, clients of the sequence)

- Unorder pushdown (the motivating rewrite; needs the step-0 kind
  signatures).
- Decorrelation (dependent join → join where the correlated set
  empties; exercises the scope-merge rename convention).
- Row-representation revisit (Plans A/B/B′ per discussion.md §5),
  motivated by making rules easier to write.
- Physical operators beyond left-deep nested loops (hash join), at
  which point RowSink needs a symmetric counterpart.
- SQL structure / Calcite bridge (flattening as a normalization pass,
  where Plan A's tuple form genuinely pays).

## Milestones for morel-rust and morel-go

Fork after step 2. Implement the datatype, type derivation,
validator, printer, and lowering against the frozen plan-text golden
files; script tests are shared. The rule framework ports after the
Java version stabilizes in step 4–5.
