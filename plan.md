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

- [x] Lowering (`RelLowerer`): tree → the environment-passing form
      that RowSink runs. Every query in the suite lowers, and the
      lowered form has the query's type, which `RelShadow` asserts.
- [x] Linearize: one step list carries the left spine, because a
      node's element is carried as an expression over the bindings
      rather than materialized. A projection then costs no step, and
      a `yield` appears only where something needs the row — before a
      set operator, before an outer join, at the end. Plan text is
      now at worst equal to what it was, and sometimes simpler: the
      round trip removes `from i in [3,1,2] yield i` from
      optimize.smli's `nonEmpty`.
- [x] Route the suite through the translation and the lowering
      (`RelShadow.viaTree`, a diagnostic rather than the flip) and
      fix what its *results* find. Four bugs so far: a pattern that
      permutes fields, an atomizing yield, a failable pattern whose
      scan condition kept a dangling reference, and a projection
      containing `ordinal` deferred past the step that counts rows.
- [ ] The flip proper: the resolver builds trees natively, and the
      lowering runs once. A round trip cannot be the flip, because it
      perturbs Core shapes that other machinery reads, and no care in
      the lowering avoids that. Two such readers, and both must move
      to the tree with it:
      * the grounding of unbounded variables (such-that.smli, "pattern
        'b' is not grounded"). Bigger than it looked: `Generators`,
        `Expander`, `Fbbt` and `Extents` are ~6,400 lines that reason
        about *patterns* — `Expander` walks scans whose expression is
        an infinite extent and inverts predicates to find a generator
        for each `NamedPat` the pattern binds. The tree erases
        patterns, which is the point of it, so this is a port, not an
        adaptation: a leaf that is an infinite extent, and filters
        above it constraining `$0` or paths into it. Arguably cleaner
        there — one element to constrain rather than a set of names —
        but it is the largest single piece of work in the sequence,
        and step 5 already owns it.

        Ported in place, as one engine with two front ends, not as a
        second engine. The split is not even: `Fbbt` and `Extents`
        never mention a step, and `Generators` mentions `Core.Exp`
        306 times against 15 step references, so the inversion is
        expression-shaped and shared; what is step-shaped is
        `Expander`, the smallest of them, and the `pat`/`exp` pair a
        `Generator` returns. So: abstract what is being grounded (a
        `NamedPat` today, a leaf's element read as `$0` or a path
        into it tomorrow), keep one inversion core, and write a small
        tree front end beside `Expander`, which goes away when the
        resolver flips. Two engines would diverge into "compiles one
        way, errors the other" over the months of the transition,
        which is the worst kind of bug to chase; and the usual risk
        of an in-place port — no caller until the flip — is answered
        by `RelShadow.viaTree`, which runs all 1534 queries through
        the tree and can check the new front end against the old
        engine's answers;
      * the Calcite hybrid path (hybrid.smli), which embeds Morel
        source in a plan and re-resolves it out of context.
- [ ] Then flip for real: every query flows through the tree, and the
      suite checks the translation by its results. `Sys.plan` output
      changes (it prints the *executable* plan, which is exactly what
      this step changes); query results must not.
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
