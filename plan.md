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
expressions over numbered inputs for scalar fields, self-describing
element types), able to apply rewrite rules. Each step keeps all
tests green. Plan text and rewrite ports are each paid exactly once.

## Step 0 — Freeze the datatype and the plan-text grammar

- [ ] Constructor set: SCAN-free leaves (bare expressions), FILTER,
      JOIN (with yield expression over `$0` and `$1`), PROJECT_MANY
      (lambda `v => collection`, subsuming the dependent scan; the
      one node that names its input element rather than binding
      `$0`), PROJECT, GROUP (key/agg shapes), SORT, UNORDER, TAKE,
      SKIP, UNION, INTERSECT, EXCEPT, COMPUTE; DISTINCT desugars to
      GROUP; AND/OR n-ary.
- [ ] Per-constructor bag/list kind signatures, transcribed from
      current step semantics (SORT : bag -> list; UNORDER; kind of
      join; set operators).
- [ ] Scoping invariants: a one-input node binds `$0` to its input
      element, a two-input node binds `$0` and `$1` to its left and
      right input elements, in addition to the environment enclosing
      the tree; expressions evaluated before the first row (SKIP and
      TAKE arguments) see the enclosing environment only, and `$0`
      in them is an error; every other free variable of an embedded
      expression is bound outside the tree; per-node label
      distinctness; deterministic rename convention at scope merges.
- [ ] Element-type derivation specified normatively, including
      singleton atomization and the zero-binding (unit) case.
- [ ] Plan-text grammar for Sys.plan / Sys.planEx, written once in
      final form: this is the contract morel-rust and morel-go
      implement. planEx prints the element type at every node.
      Pin the collation of generated labels against user labels.
- [ ] Record rejected alternatives (pair-based join; lambdas for
      scalar fields; advisory names; row-representation Plans A/B/B′)
      in discussion.md.

## Step 1 — Shadow tree (no behavior change)

- [x] Datatype (`Core.Rel`), type derivation (`CoreBuilder`),
      validator (`RelValidator`), printer.
- [x] Translation (variable elimination: pattern bindings become
      `$0`/`$1` references, field accesses and record constructions).
      From the step list rather than from the AST, which reuses type
      resolution and is what step 2 replaces.
- [x] CI asserts, for every query in the suite (`RelShadow`, under
      `assert`): the tree's type is the query's type, and the
      validator accepts it. Declined constructs are counted, not
      guessed at.
- [x] No translator gaps: every query in the script suite
      translates -- 1534 of them, none declined. The last three to
      land were a scan whose pattern can fail to match, an outer
      apply (`projectMany` with `ifEmpty`), and an outer join whose
      absent side has more than one binder.
- [x] No tree→From converter for its own sake. A round-trip
      comparison cannot be structural — the translation normalizes,
      inserting a projection after a destructuring scan and
      unwrapping an atomizing yield — so the assertion would have to
      be weakened until it proved little. The converter is worth
      writing as the *lowerer* instead, in step 2, where results
      check it.

## Step 2 — Flip execution

Execution moves before observability, reversing the original order.
The plan text that step 3 freezes is a contract that three
implementations then follow, and freezing it on trees that have never
run risks churning it when a semantic bug surfaces. Results are the
only real check on a translation, so earn them first. The cost is
that the ports start later; the saving is that they start against
something settled — §8's principle, applied to the sequence itself.

- [ ] Lowering: tree → left-deep environment-passing form → RowSink;
      `$0`/`$1` and the field accesses on them dictate EvalEnv slots;
      name→slot gathers where canonical label order diverges from
      construction order. The step list survives as that form: an
      unprinted lowering artifact, or dissolved into the lowerer.
- [ ] Every query flows through the tree, so the whole script suite
      checks the translation by its results. Expectations do not
      change at all in this step: a test that needs re-baselining is
      a bug.
- [ ] Delete the AST→From path; the resolver builds trees natively.

## Step 3 — Flip observability

- [ ] Sys.plan and Sys.planEx print the tree.
- [ ] Script-convert test expectations (one flip, final format).
      These changes are benign by construction: only plan text moves,
      because execution changed in step 2 without changing results. A
      query with no scan gains a visible `[()]` leaf (spec.md §3.1)
      and a set operator may gain a projection that aligns its
      branches; both return exactly what they returned before. A test
      whose *result* changes in this step is a bug, not a
      re-baseline.
- [ ] Plan text is now frozen; golden files are the
      cross-implementation contract. Rust (morel-rust#33) and Go
      work can begin here, in parallel with steps 4–5.

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
- Decorrelation (PROJECT_MANY → JOIN where no leaf inside the lambda
  body mentions its parameter; exercises the scope-merge rename
  convention).
- Row-representation revisit (Plans A/B/B′ per discussion.md §5),
  motivated by making rules easier to write.
- Physical operators beyond left-deep nested loops (hash join), at
  which point RowSink needs a symmetric counterpart.
- SQL structure / Calcite bridge (flattening as a normalization pass,
  where Plan A's tuple form genuinely pays).

## Milestones for morel-rust and morel-go

Fork after step 3, when the plan text is frozen. Implement the
datatype, type derivation, validator, printer, and lowering against
the frozen plan-text golden files; script tests are shared. The rule
framework ports after the
Java version stabilizes in step 4–5.
