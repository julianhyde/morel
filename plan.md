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

Destination: value-passing relational tree (concatenating join,
expressions over numbered inputs for scalar fields, self-describing
element types), able to apply rewrite rules. Each step keeps all
tests green. Plan text and rewrite ports are each paid exactly once.

## Where this stands, and what the next session does

Steps 0, 1 and 2 are done, and so are goals 1, 2 and 5. The resolver
returns a relational tree; it is what the rewrite passes carry and what
grounding works on; lowering is a pass of its own, after the rewrites
and before the compiler. The AST-to-From path is deleted, and spec.md is
frozen against what was built rather than what was first designed.
`fullMake` is green and is the gate; there is one configuration to test.

The feature is finished when a tree is what executes and what prints.
Six goals, in order, each with the thing that says it is done.

### Start here

**The flip has landed. Goals 1, 2, 4 and 5 are done, and goal 3 is half
done.** The resolver returns the tree, the rewrite passes carry it,
grounding works on it, lowering is a pass of its own in `Compiles`, and
`Sys.planEx "0"` prints a tree. No query answers differently: the
expectations moved for plan text and for eight §11 messages, and for
nothing else. `fullMake` is the gate and it is green.

What is left, in the order it is worth doing:

1. **Goal 3's other half.** `SuchThatShuttle.visitRel` grounds the tree
   and falls back to lowering plus `Expander.expandFrom` where the tree
   engine declines. Each query that stops needing the fallback is a
   round trip removed and, eventually, `RelShadow`'s translation shadow
   with nothing left to check -- the two are the same work, because the
   shadow exists to guard the translator, and the translator is only
   still needed for the fallback.

   **The residue is 16 of 240**, measured over the whole script suite by
   counting the fallback: `optimize.smli` 10 of 33, `such-that.smli` 6
   of 191, every other file 0. It was 37 at the start of the session and
   28 after `Analyzer.isAtom`; grounding through a nested query took the
   remaining 12. What is left:

   * *An infinite range leaf with the bound above it* (10, all in
     optimize). `filter [$0 < 5] (#flatten Range ([AT_LEAST 1]))`, and
     the same over a join of two with `#* Int (#1 $0, #2 $0 + 3) < 30`.
     The step list has `Fbbt.strengthen` and `RangePushdown.apply` for
     exactly this; the tree engine has neither, and `RelExpander` does
     not treat a range as a leaf it could bound at all. **The next
     piece**, and the only one with a class of its own.
   * *Neither engine can ground it* (~4 of the 6 left in such-that).
     `from i where i elem [1..]`, `from x where (x + 2) * (x - 3) = 0`,
     `from i, j` over type variables. The expected output is an error,
     both decline, and the fallback runs only to produce the message.
     Not residue to remove, though it is work done twice.
   * *`elem` over a collection that is itself a tree* (2). `op elem ((n,
     d), project [(#name $0, #deptno $0)] [...])` -- the engine inverts
     `elem` against a collection, and this one is a `project`.

   Three things tried and measured, so they are not tried again:
   `Core.Input` in `Analyzer.isAtom` took the residue from 37 to 28 and
   changed no output; lowering the argument of `Relational.nonEmpty` in
   `Generators.maybeExists`, with `RelExpander.subst` dropping the row
   binding it had just made redundant, took it from 28 to 16; and
   treating `#1 $0` as atomic in `Inliner.isAtomic`, so that a `case`
   over a tree's components would reduce the way one over a step list's
   variables does, removed no declines and broke a script.

   The pattern in the two that worked: a pass that reads a query's shape
   was written against a step list, and the resolver now leaves a tree
   where it looks. `fnBody`, `maybeExists` and the `let` that binds the
   row are three instances; expect more, and expect them to be small.
2. **Goal 6, freeze** -- but not before the plan text is stable. A tree
   prints its bracketed expressions with `StringBuilder.append(exp)`,
   which is `toString()` and a plain writer, so an identifier keeps the
   ordinal its generator gave it: `let val d_14 = $0 in ...`. Change
   anything upstream that draws a name and it becomes `d_16`. Golden
   files cannot be a cross-implementation contract while they say that.

   `Core.Rel.describe` renumbers `x$N` afterwards with a regex, and
   `AstNode.unparseRenumbered` renumbers ordinals properly, through
   `AstWriter`, but the two do not meet: `describe` never uses a writer.
   The fix is for `describe` to build its text with one
   `RenumberingAstWriter` over the whole tree -- which would also
   subsume the regex pass -- and it means changing `describeArgs` and
   its helpers from `StringBuilder` to that writer, in about a dozen
   places.

**One thing the flip cost, which goal 4 mostly retires.** A leaf is a
bare expression (spec.md §3.1), so a tree holds no names. A query with
several binders ends in a projection that names its element's components
-- `project [{deptno = #2 $0, loc = #1 $0, name = #3 $0}]` -- and
`RelExpander.leafPats` reads them back, which is enough for grounding to
name what it builds and for the "not grounded" message to name the
pattern. A query with **one** binder has no projection and no name
anywhere, so `from e in emps` lowers to `from w$0 in emps` and eight
messages say "pattern is not grounded" with no name.

That is only visible where a *lowered* plan is printed, and goal 4 has
taken most of those away: `Sys.planEx` prints the tree, where the
element is `$0` and a name is neither present nor wanted. What is left
is `Sys.plan`, which prints the executable code and still says `w$0`.
If that grates, the answer is to give a leaf an optional binder, which
is a change to spec.md §3.1 and worth its own argument.

**Three things worth not re-deriving.**

* *Build before you run.* `./morel` defaults to `--no-build`, so a
  source edit does not reach it.
* *Read the surefire output, not the last one you looked at.*
  `fullMake` reruns the suite and overwrites
  `target/test-classes/script/surefire/`, so a measurement taken before
  it and read after it describes a different build.
* *Classify diff hunks that add lines, not only those that delete
  them.* A script that skipped pure additions hid seven wrong rows and
  cost a session's worth of wrong conclusions.


1. ~~Decide how `$0` is told apart, and implement it.~~ **Done.** It
   is a node of its own, `Core.Input`, carrying the ordinal of the
   input it names -- argued in discussion.md §17 against the two
   alternatives (an `IdPat` ordinal per node, a scoping rule every
   walk honours). A pass that reasons about variables walks
   `Core.Id`, and `$0` has stopped being one, so `Analyzer`,
   `Inliner` and `freePats` are right about it without being told.
   `RelTest.testInputIsNotAVariable` is the regression test, and the
   13 places that compared its *name* are now type tests.

2. ~~**The resolver stops lowering, and the tree survives the rewrite
   passes.**~~ **Done.** `Resolver` returns the `Core.Rel`, the rewrite
   passes carry it, and lowering is a pass of its own in `Compiles` --
   not at the compiler's boundary, for the reason below. The suite is
   green and `Sys.planEx "0"` prints a tree.

   Lowering
   is a pass of its own, in `Compiles`, after the rewrites and before
   the compiler -- not inside `Compiler.compile` at `expression
   instanceof Core.Rel`, which looks like the tidy place and is wrong:
   the compiler lays out the stack from the Core it is given, so a
   binder minted while it compiles has no slot, and `fun sym c s = from
   (i, c2) in mk s where c2 = c yield i` fails with
   `NullPointerException: c`, on `c`, nowhere near the binder that
   caused it.

   The prerequisite was done first: `Shuttle`'s twelve `Core.Rel` visits
   and the matching `accept` methods returned narrow types (`Core.Filter`
   in, `Core.Filter` out), which forbids the rewrites step 4 exists to
   write -- dropping a `filter true` replaces a filter with its input,
   and no `Shuttle` override could say so. They return `Core.Exp` now.
   Nothing overrode them, which is itself the evidence: `RelExpander
   .rebuild` walks the tree with its own recursion rather than a
   `Shuttle`, and that was why.

   The findings, in the order they were made, each now a commit:

   * *Picking the root out.* A `visitRel(Core.Rel)` hook on `Shuttle`,
     called on entering each of the twelve visits and returning null to
     descend as usual, is the one place a pass sees a node before its
     children -- which is how it tells a root from what is under it,
     since a shuttle does not know its parent.
   * *The latch.* `Compiles` stops running `SuchThatShuttle` once
     `containsUnbounded` says no, so that has to be exact. It looked for
     a `Core.Scan` with an infinite collection, and a tree has no scans:
     its leaves are the inputs that are not themselves nodes. The same
     hook on `Visitor` answers it, through
     `RelExpander.containsUnbounded`. The latch must also know an
     infinite *range* leaf, `[1..]`, or the pushdown never runs and the
     query raises `Size` at run time.
   * *The environment machinery.* `EnvVisitor` built an aggregate's
     environment from the `Core.From` group **step** on its
     `fromStack`, and a tree's `Core.Group` puts nothing there. A
     tree's group needs a context of its own: the aggregate's
     *argument* reads `$0` and needs nothing, but the aggregate
     *function* may name a key -- `fn list => List.size list + k`.
   * *A group's projection must read its own labels.* `Scope
     .substitute` files its paths under a pattern it invents per name,
     with an ordinal of its own -- rightly, because a name is not
     unique. A `group`'s keys and aggregates are the exception: the
     resolver made *those* patterns, at ordinal 0, and the expressions
     it then substitutes refer to them, so every lookup missed and a
     bare `sum` survived into the tree, where the inliner matched it
     against the built-in and found a `Macro`. The scope now takes the
     patterns the caller already has.
   * *The nested query.* `RelLowerer.subst` was substituting the
     element for `$0` right through a tree nested in an expression,
     whose `$0` is its own (spec.md §2 rule 3), and `from d in depts
     where d.deptno elem (from e in emps ...)` read the outer row at a
     field only the inner one has. The walk stops at a nested node now,
     and the resolver binds the row first, as the spec says in the same
     breath.
   * *The row binding, and what protects it.* Two things plant the
     enclosing row -- a path, and the `current` keyword -- and routing
     `current` through `byPat` makes them one case. `Inliner` must then
     leave the binding alone, at the declaration *and* at the
     reference: guarding only the id leaves the reference unbound,
     guarding only the let lets the id put `$0` back. Hiding the
     value from the environment does both.
   * *`ordinal` in a `let`.* Both stack-`let` paths in `Compiler` built
     their body's context with the three-argument `Context`
     constructor, which passes null for `ordinalSlots`, so a `let`
     inside a `yield` lost the counter and `$ordinal ()` raised "occurs
     outside a yield". A bug of its own, with nothing to do with trees;
     fixed, and `relational.smli` has the query.
   * *Names must be unique.* Two ways they were not. `RelExpander`
     numbered its invented binders from zero with ordinal zero, so two
     expanders named two things `g$0` and a record over both lost a
     field. And `Session` and `TypeSystem` had a `NameGenerator` each,
     both handing out `w$0`, so a binder from one captured a binder from
     the other as soon as a pass with a type system and no session --
     `Generators`, reading a function's body -- lowered something. One
     generator per session now, and the printer renumbers what it
     prints, which the step list's printer did not do until this session
     and now does.
   * *An input reference is atomic.* `$0` is a node and not a variable,
     so `Inliner.getSub` left `case (x, $0) of (x, y) => (x, y) elem
     edges` unreduced, and the grounding engine matches on `elem` over
     the query's variables. And the guard that keeps `$0` out of a
     nested tree must stop at a nested node, or a function whose body is
     a query is never inlined and the same engine never sees the
     constraint at all.
   * *Fold a field only where the slot is in range.* `RelExpander
     .simplify` folds `#y {x = a, y = b}`, which is what lets the engine
     see a constraint a yield was substituted into, and it read past the
     end of a record a substitution had made smaller. The shape that
     survives the fold fails at run time instead, so `visitRel` asks
     `misaddressed` of the lowered answer, as `expandViaTree` does. The
     guard is a range check and not the type equality `RelLowerer
     .readField` uses: the stricter one declines folds the engine needs,
     and `check.smli` and `such-that.smli` lose queries that grounded.
   * *A record label is printed as text.* `RenumberingAstWriter` renamed
     what goes through `id` and `idQuoted`, and a record writes its
     labels with `append`, so a plan read `yield {w$3 = g$0} ... where
     par (x, w$0)` -- one binder under two names. Half an hour went on
     believing that.
   * *The step list's grounding engine is still needed.* See "Start
     here".


3. **Grounding takes the tree directly.** `SuchThatShuttle` calls
   `RelExpander.expand` instead of `Expander.expandFrom`, so a query
   is no longer lowered, translated back and lowered again. This is
   what makes step 2's "the lowering runs once" true. *Done when* no
   query round-trips twice, and `RelShadow`'s translation shadow has
   nothing left to check.

   **Half done.** `SuchThatShuttle.visitRel` grounds the tree at its
   root; where the tree engine declines it lowers and hands the query to
   `Expander.expandFrom`, so the round trip is paid on the residue
   rather than on everything. What the tree engine will not yet take is
   what shrinks that residue: measured, grounding *only* on the tree
   costs 1841 differing lines against 106 with the fallback, so the gap
   is wide and worth measuring query by query.

   Two known members of the residue: a predicate written as a function
   that reads a global, which the inliner leaves alone, so the
   constraint is behind a call (`from n where isNum n`); and a
   constraint inside a nested query, which the step list's engine reads
   through and the tree engine does not (`exists x where (exists y where
   (x, y) elem pairs)`).

   `Generators` reads a constraint out of a *function's body*, and is
   written against a step list -- `Relational.nonEmpty (from ...)`, and
   `nonEmpty.arg.op != Op.FROM` declines anything else. `fnBody` lowers
   what it reads. That is a plaster: the reading itself should learn the
   tree, and until it does a body is lowered once per analysis.

4. ~~**`Sys.plan` and `Sys.planEx` print the tree.**~~ **Done for
   `planEx`**, which is what was decided: `AstWriter.withTypes` carries
   the flag, `Core.Rel.unparse` reads it, and the final phase is the
   tree rather than the step list, because the lowering is a pass that
   runs after the plan is taken.

   `Sys.plan` is untouched, deliberately. It prints the executable code
   -- the step list that `Compiler` compiles -- and a tree there would
   say something that is not what runs. It flips when the tree executes,
   which is what spec.md §6 means by "once step 3 flips `Sys.plan` to
   print it"; the 196 `Sys.plan` sites re-baseline then, and whether
   the code view survives under another name is a question for then.

5. ~~**Script-convert the expectations, in one flip.**~~ **Done**, with
   goals 2 and 3, because the plan text moves the moment the resolver
   stops lowering. Only plan text moved: 106 lines across six script
   files, 50 hunks, of which 8 were §11's message and 42 were plan text,
   and no result changed. A query with no scan did gain a visible
   `[()]` leaf (spec.md §3.1), as this said it would, and it reaches
   Calcite as the values it is with a project over it. Four Java test
   fixtures took the resolver's output as a `Core.From` and now take the
   tree.

6. **Freeze the plan text.** Golden files become the
   cross-implementation contract, and morel-rust (#33) and the Go
   work can begin against them, in parallel with steps 4 and 5.

Three things not to re-derive, each of which cost a detour once:

* Grounding must run *after* inlining, because the engine matches on
  function literals and `elem` is an `Id` until `Inliner` has run.
  That is why it lives in the inline loop and not in the resolver.
* `Resolver.toCore` is not pure -- it takes names from a generator and
  registers in the type map -- so nothing can shadow the resolver by
  re-running it. Replace, and let the suite's results be the oracle.
* An outer join concatenates like any other, each component of the
  absent side wrapped in `option` on its own (discussion.md §15).

One loose end that is not ours: issue #468, the `yield {h = h}`
type bug in `FromBuilder.tupleType`, is being fixed on main. The fix
on this branch is only what the yield-binder slice needed to stay
green; take main's when the two meet.

## Step 0 — Freeze the datatype and the plan-text grammar

All six are written in spec.md, and the constructor set is not the
one this step first listed. `JOIN` carries no yield -- it
concatenates its inputs' components, and a projection follows
(discussion.md §15) -- and `PROJECT_MANY` is gone, because a
dependent join is a join carrying a binder and needs no constructor
of its own (§8). `IF_EMPTY` arrived instead. spec.md §3.4 described
the join with a yield until step 2 was finished, under a note saying
it would be rewritten "when it lands, which is before this text is
frozen"; it has landed, and it is rewritten.

- [x] Constructor set: SCAN-free leaves (bare expressions), FILTER,
      JOIN (kind, optional binder, condition; no yield), PROJECT,
      GROUP (key/agg shapes), IF_EMPTY, SORT, UNORDER, TAKE, SKIP,
      UNION, INTERSECT, EXCEPT; DISTINCT desugars to GROUP, COMPUTE
      to GROUP plus the extraction its wrapper performs; AND/OR
      n-ary. spec.md §3.
- [x] Per-constructor bag/list kind signatures, transcribed from
      current step semantics (SORT : bag -> list; UNORDER; kind of
      join; set operators). spec.md §4.
- [x] Scoping invariants: a one-input node binds `$0` to its input
      element, a two-input node binds `$0` and `$1` to its left and
      right input elements, in addition to the environment enclosing
      the tree; expressions evaluated before the first row (SKIP and
      TAKE arguments) see the enclosing environment only, and `$0`
      in them is an error; every other free variable of an embedded
      expression is bound outside the tree; per-node label
      distinctness; deterministic rename convention at scope merges.
      spec.md §2 and §5.
- [x] Element-type derivation specified normatively, including
      singleton atomization and the zero-binding (unit) case.
      spec.md §1 and §3 -- atomization by being abolished, since an
      element's type is the type of the expression that builds it,
      and the unit case by the `[()]` leaf of §3.1.
- [x] Plan-text grammar for Sys.plan / Sys.planEx, written once in
      final form: this is the contract morel-rust and morel-go
      implement. planEx prints the element type at every node.
      Pin the collation of generated labels against user labels.
      spec.md §6.
- [x] Record rejected alternatives (pair-based join; lambdas for
      scalar fields; advisory names; row-representation Plans A/B/B′)
      in discussion.md -- §15, §2, §3 and §7 respectively.

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
- [x] A builder (`RelBuilder`), per discussion.md §13: a stack of
      relational expressions, a name map, and simplifications under a
      switchable `EnumSet`. `RelShadow` asserts on every query that
      the builder can express the tree exactly -- 1852 of them, none
      rebuilt differently -- which is the precondition for the
      resolver depending on it. The name map is the untested half,
      because only a caller that starts from names exercises it.
- [x] Replace `projectMany` with a dependent join: a `join` that
      carries a binder its right input may read, plus an ordinary
      `project` where only the inner elements are wanted. Done, and
      it paid more than it cost. The translator's forty-line
      correlated branch collapsed into the independent one plus two
      lines; the outer apply needed no code at all, because a
      dependent `join [left]` emits the unmatched row by definition;
      and the expander's two correlated constructions became a join
      and a commuted join. The binder is dropped where the right
      input does not read it, so decorrelation is paid at
      construction and a caller may offer one speculatively. spec.md
      §3.3 and discussion.md §8 are rewritten; the narrative above
      this line predates the change and says `projectMany` where it
      now means a dependent join.
- [x] A scan whose pattern can fail is a filter and a projection, not
      a node holding a `case` that yields a collection. The condition
      is a filter that a rule can reorder and the expander can push
      through; the bindings are paths, and the projection that turns
      them into the row is the one the translation already added.
      Every pattern takes this path except a user datatype's
      constructor, whose argument has no total accessor: `::` and
      `[]` escape through `null`, `hd`, `tl`, `nth` and `length`,
      which are exactly the accessors a datatype lacks.
- [x] Trees have no atoms (discussion.md §14). `group` builds a record
      whether it has one label or many, and the conversion to a bare
      value is a `map` of a record selector outside the tree, as
      `compute`'s extraction is an `only` outside it. Narrower than
      it first looked: a single *binding* naming the element is not
      atomization, so nothing changes for `from i in [1,2,3] where i
      > 1`, and `Core.StepEnv.atom` goes with the step list at the
      flip rather than here. Done, for two lines and a deletion: the
      projection that turns the record into the query's bare value is
      one `normalize` was already inserting, so no boundary operator
      was needed.
- [x] A join concatenates its inputs' components, and carries no
      yield (discussion.md §15). Three things the design did not
      anticipate, each found by building it: the translator had to
      stop normalizing after a join, or the projection it inserts
      keeps joins from nesting; `optionize` wanted the binder's type
      where it was given the component's, which silently gave every
      binder of a multi-binder absent side the whole option; and an
      outer join contributes one component rather than its inputs',
      because flattening it would need each component of an absent
      side wrapped again, which the step list cannot express.

- [x] Deterministic names for the lowered form, which turned out to
      be a *prerequisite* of the flip rather than something the flip
      would reach. Measuring what a flip would change to the script
      expectations showed two kinds of churn: one genuine improvement
      (optimize.smli's `nonEmpty (from i in [3,1,2] yield i)` becomes
      `nonEmpty [3,1,2]`) and one intolerable -- binder names like
      `w$1509`, taken from the session-wide generator, which would
      make every expectation depend on everything compiled before it.
      Solved by renumbering when printing rather than by controlling
      allocation, which is what Morel already does for type variables
      (`TypeSystem.unqualified` prints `('b * 'a * 'b)` as `('a * 'b
      * 'a)`). A binder is allocated freely -- uniqueness is all
      allocation owes -- and `Core.Rel.describe` numbers the
      generated binders it finds, from zero, in order of first
      occurrence. It survives nesting, which a rule about allocation
      does not, so it closes spec.md §6's hole as well.
- [ ] The flip proper: the resolver builds trees natively, and the
      lowering runs once.

      **Not a differential shadow, and the reason narrowed.**
      `Resolver.toCore` is not pure, so a pass that shadows the
      resolver by re-running it corrupts the pass it shadows. But
      that rules out shadowing, not the flip: a native path that is
      the *only* conversion runs `toCore` once, which is what the
      resolver does today. So the shape is to replace, not to
      shadow -- build the tree natively for the queries a slice
      handles, lower it, execute it, fall back to the step list for
      the rest -- and the oracle is the script suite's results.

      **The shadow cannot be a differential one.** Tried, and backed
      out. Building the tree natively beside the step list and
      comparing the two requires converting each step's expressions a
      second time, and `Resolver.toCore` is not a pure function: run
      under `assert` beside the real conversion it shifted the global
      name generator, and then broke real queries outright
      (a `ClassCastException` in blog.smli, casting a `Core.Apply` to
      a `Core.Tuple`). A pass that shadows the resolver by re-running
      it corrupts the pass it shadows.

      That rules out the method the rest of this branch has leaned on,
      and it is worth knowing before the flip rather than during it.
      What is left: convert step kinds one at a time, with the *step
      list* as the fallback for kinds not yet converted, and check by
      results rather than by comparing trees. Which means the flip is
      a sequence of small behaviour changes, each verified by the
      script suite, not a shadow that runs to zero and then a switch.

      Three differences the attempt did surface before it was backed
      out, all real:
      * The native path emits an identity projection where the
        translation drops it -- `PROJECT_IDENTITY`, which the
        translation gets from `normalize` and the builder would get
        from its `EnumSet`.
      * A translated tree carries `FromBuilder`'s normalizations. An
        inlined subquery is the clearest: the step list flattens
        `from p in (from q in ...)` and the tree has no reason to, so
        the two differ and the *tree* is right.
      * Substituting a name into an expression that contains a nested
        query puts `$0` where the nested tree rebinds it. spec.md §2
        rule 3 says a `let` is needed; nothing enforces it yet. A round trip cannot be the flip, because it
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
        there — one element to constrain rather than a set of names.

        Smaller than the line count suggests. `Expander.expandSteps`
        reaches the engine through two calls: `maybeExtent(cache,
        pat, exp)` for a scan, and a constraint per `where` conjunct.
        Everything else is inversion, and `Generators.Cache` already
        keys on a variable *and on field accesses into it*
        (`patForExp`, `fieldPats`), which is the shape a tree hands
        it. So the front end is: name the element of each
        infinite-extent leaf, substitute that name for `$0` in the
        filters above it, and make those two calls. Order 200 lines
        against an engine that does not change. Done, in
        `RelExpander`: it grounds a leaf and replaces it with what
        bounds it, projecting where the generator binds a tuple of
        which the element is one component. What remains before it
        can stand in for `Expander`: none that is known. A generator
        that reads another variable turns the join into a
        `projectMany`, whose lambda binds the element it reads —
        `from x in [1, 2], y where y elem [x, x + 1]` becomes a
        `projectMany` over `[1, 2]` whose body scans `[g$0, g$0 + 1]`.
        Run against the suite beside `Expander` (`RelShadow`
        .groundingAgrees, under `assert`, on every unbounded query
        the suite compiles). It fails the build if the tree grounds a
        query the step list rejects — a change in what compiles —
        and counts the reverse. Three gaps found, all one shape:
        the front end grounds one leaf at a time, and the engine
        grounds a set of variables together.
        * Ranges. `from i : int where i > 0 andalso i < 10` grounds
          in the step list because `expandFrom` runs Fbbt first to
          turn the comparisons into bounds; the tree path does not
          run it.
        * Several leaves under a join. `from x : string join
          y : string where (x, y) elem pairs` needs both leaves
          grounded from one constraint; the walk grounds the right
          one and loses the constraints on the way to the left.
        * A generator that binds a tuple. The same shape from the
          other side: the engine answers with a generator for
          several variables at once, which a one-leaf caller cannot
          use.

        So the front end took the shape the step engine has: the
        extent leaves under a join tree are collected with the
        conditions above them, grounded together in one cache, and
        replaced together; the correlated case is the same question
        asked of one leaf. With Fbbt run first, as `expandFrom` runs
        it, and with the names a generator merely *mentions*
        (constructors such as `OPEN`, and globals) no longer mistaken
        for names another leaf must bind.

        Agreement over such-that.smli and relational.smli went from
        187 queries to 235, with 65 still grounding less. Those are
        one shape: a scan whose pattern destructures an extent —
        `from (b, i) : bool * int where i elem [3, 5] andalso b`. The
        step list registers the pattern, so the engine grounds `b`
        and `i` separately, one from the `bool` extent and one from
        the `elem`; the tree names the leaf's element once, so the
        engine is asked to bound a tuple.

        Naming a leaf by pattern, and trying that when naming it by
        variable fails, is written and is needed — but it does not
        move the number, because of what sits between the filter and
        the leaf. `from (b, i) : bool * int where p` translates to a
        filter over a *projection* over the leaf: the query's element
        is the record `{b, i}` that the bindings describe, and the
        leaf's is the tuple, so the translation normalizes between
        them. Conditions stop at a projection, for parity, so they
        never reach the leaf and the leaf is grounded against nothing.

        Conditions are now pushed through a projection, by
        substituting the projection into them (discussion.md §12).
        The tree therefore grounds strictly more than the step list —
        `from x yield {y = x} where y elem [2, 3]` errors today and
        grounds in a tree — which is deliberate, and which the script
        expectations record at the flip.

        Leaves that one generator binds together are collapsed into
        a single scan, with the join between them removed and each
        name read through the path its pattern gives it. Replacing
        them separately would enumerate the collection once per leaf
        and pair every value with every other, which is why the front
        end used to decline.

        Agreement is 300 of 300: the tree grounds every unbounded
        query in such-that.smli and relational.smli that the step
        list grounds, and no query that it does not. Note that a
        query both engines decline is agreement, not a gap; `from x
        where (x + 2) * (x - 3) = 0` errors in both, as
        such-that.smli says it should, for want of symbolic maths.
        What the trace found, in the order it was closed:
        * Grounding through a nested query was never failing: the
          shadow was. It asked whether *any* infinite extent survived
          anywhere in the expanded expression, and a nested query in
          a condition -- `where nonEmpty (from y : int where ...)` --
          has an unbounded pattern of its own that the step list
          grounds when it reaches that query. Now only the tree's own
          leaves count.
        * Correlation chains, six queries. `from dno : int join name
          : string join v : {deptno, dname, loc} where v elem depts
          andalso #deptno v = dno` grounds `v` and then `dno` from
          `v`. Two changes closed them. A generator that reads the
          left side no longer needs the left side to *be* a leaf: the
          `projectMany` binds whatever element the left subtree has
          and reads the name out of it by path, which is the inverse
          of the element expression `collect` already builds. And
          where the correlation runs the other way -- the right side
          grounds on its own and the left reads it -- the join is
          reordered, the right side coming first and the names it
          binds carried down as a substitution that `rebuild` applies
          to each generator it uses. The step list defers the same
          way, which is what the script's "forward references are
          required" comment is about, so reordering the rows is
          parity rather than licence.
        * Patterns that nothing reads, when the rows are only
          counted, are now dropped rather than grounded, as
          `Expander` drops them: `rowsUsed` is threaded from
          `expandFrom` through the shadow, and a leaf that no
          constraint mentions is removed from its join. Seven
          queries.
        * A filter *between* two joins is now seen through, in the
          one piece the earlier attempt lacked: `collect` gathers its
          conjuncts as constraints and carries on to the leaves
          below, `rebuild` puts it back with whatever a sealed
          generator has not taken over, `contains` counts the leaves
          under it, and the collapse stops at it rather than
          swallowing it with the join it replaces.

        Naming a leaf by pattern, and retrying that way when naming
        by variable fails, is worth five queries: 284 of 300 with
        the retry, 279 without. Naming from the constraints rather
        than by trial was tried and reverted: it is a better rule,
        but it moved nothing -- none of the tail is a naming problem
        -- and unpaid complexity is worse than the trial it replaced.

        The invariant to reach before the flip was that the tree
        never grounds less than the step list, since any query the
        tree declined would stop compiling. It holds over the suite.
        It is checked, not assumed: `RelShadow.groundingAgrees` runs
        on every unbounded query the suite compiles and fails the
        build on a disagreement in either direction. The counter for
        the tree grounding less is gone with the gap it measured.

        The conditions a
        sealed
        generator subsumes are now dropped, so `from x where x elem
        [1, 2, 3]` expands to the list itself, and a filter survives
        only for what the generator does not enforce. Conditions reach the leaf
        through `sort`, `unorder`, `skip` and `take`, and stop at a
        projection — parity with the step list on both counts, which
        ignores every step but a scan and a `where`, and so does not
        push a condition through a `yield` either. Pushing one
        through would ground `from x yield {y = x} where y elem
        [2, 3]`, which errors today; that is a change to the
        language, not to this port. The diagnostic now points at the
        pattern rather than naming it (discussion.md §11).

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
      * the Calcite hybrid path (hybrid.smli). Sized: the channel is
        text. `CalciteCompiler` emits a fragment as `exp.toString()`
        and `CalciteFunctions` re-parses and re-type-resolves it from
        scratch, in an environment built from `RelContext.map`, which
        binds Morel variables *by name* to Calcite fields. So the
        channel depends on two things the tree changes: what the
        binders are called, and that a value is a materialized row
        rather than an expression. Hence `#deptno v$108` arriving as
        text with no way to know what record `v$108` is — "unresolved
        flex record".

        But that error was not the channel's fault. The fragment that
        crossed was `#x`: a selector with no argument, because the
        lowering had built `#x {x = x_3}`, reading a field out of a
        record it was constructing on the spot. `translate` pushes
        down `#f v`, a field of a row; a selector applied to a record
        it did not build is neither that nor anything else it knows,
        so it sends the *function* across as a scalar fragment, and a
        bare `#x` has no record to resolve against.

        So the lowering reads the field instead of building the
        record: `#b {a = x, b = y}` is `y`. It is the dual of
        `simplifyTuple`, which the translation already uses in the
        other direction, and it is what the step list does anyway —
        it reads the field off the row its `yield` left behind. That
        closes hybrid.smli's error and one of dual.smli's two
        pushdowns, and most of the plan-text difference goes with it:
        relational.smli's `#2 (#0 (v$1998))` is `#2 v$1998` again.

        The other pushdown was the same question from the other
        side. `from x in (from e in scott.emps yield e.deptno) union
        ... group x` scans a nested query in the step list, so `join`
        binds `x` and `group x` finds it; the lowering inlines the
        nested query and leaves an atomizing `yield` where it was,
        and `yield_` extended the environment and the map only for a
        record yield. So it binds an atomizing one too: the yield
        step carries the binding, and the projection is the one-field
        row that Calcite already uses for a scalar variable. The
        column keeps the name Calcite gave it, since only Morel looks
        the variable up and by then it is the sole field. That is a
        gap in the step-list path as well, not only the tree's --
        write the branch inline and the query stops being pushed
        down today -- so dual.smli has it written both ways now.

        hybrid.smli and dual.smli are therefore clean through the
        tree.

        The lost source position was the last of it. Both the
        translation and the lowering substitute one expression for
        another — a binder for `$0`, `$0` for the element — and both
        dropped the position of the occurrence they replaced, so
        `from i in [{a = fn x => x}] order i` blamed nothing at all
        (`0.0-0.0`) where it used to blame the `i` the user wrote.
        `core.at` carries it, for the two shapes an access expression
        takes.

        Three ways out remain for the channel itself. Materialize
        more in the lowering, which cannot work, because whether a
        fragment crosses the Calcite boundary is decided inside
        `CalciteCompiler`, long after lowering. Emit types with the
        text, which is local — a printing mode that annotates
        binders — and keeps plans readable, but leaves a lossy
        channel in place. Or translate the tree to `RelNode`s
        directly, which is where this was
        always going: 1,223 lines of which the step-shaped part is
        small (`Core.FromStep` twice, `Core.Scan` three times), the
        bulk being expression conversion and `RelContext` (52
        mentions) that a tree needs as much as a step list. The issue
        itself gives the reason: a tree is closer to `RelNode` than a
        step list is.
      One consequence of concatenating the join, found by
      re-measuring and then diagnosed: dual.smli's two three-way
      joins fail under the round trip with "unresolved flex record
      (can't tell what fields there are besides #1)".

      It is not a Calcite problem, though it looks like one. The
      fragment that crosses is a bare `#1`, and the expression it
      came from is `#1 (w$0, w$1)` -- a selector applied to a *tuple
      construction*, not to a variable. The lowering builds a join's
      element as a literal tuple of its components and a later access
      reads a field of it, so what Calcite is handed is a projection
      of something it has no reason to understand.

      `RelLowerer.readField` reduces exactly that shape -- `#b {a =
      x, b = y}` is `y` -- but only inside `subst` and `rename`, so
      an access built anywhere else kept the tuple. The site was
      `core.components`, which reads a node's components out of an
      expression for its element and projected each one out even when
      the element was already a tuple of exactly those components,
      which for a lowered join it always is. Fixed there: read them
      off the tuple. dual.smli is clean again.

      Where the round trip stands, re-measured after the dependent
      join, the failable-pattern translation and the group-record
      change, and again after concatenating the join: three scripts
      differ, all in plan text only (optimize, hybrid, relational),
      and such-that.smli has the two artifacts below. blog.smli,
      dual.smli and built-in/relational.smli used to differ and now
      do not — the dependent join and
      the filter-and-projection translation closed them. Re-measure
      after a run of changes rather than only when something is
      expected to move: the run that produced these numbers also
      turned up a name-collision bug that no single change looked
      capable of causing. Those two are the round trip's
      own doing rather than the tree's, and they are the reason it
      cannot be the flip. `fun cheap beer = exists bar1, ... where
      sells (...)` has its body lowered when it is declared, and `from
      b where cheap b` then asks `Expander` to ground `b` by reading
      that body — which is now a step list the lowering shaped, not
      the one the resolver built. One loses the grounding; the other
      reaches a generator that scans a collection twice under the same
      pattern, which the step list reads by shadowing and a tree
      cannot represent at all. Both go when grounding moves to
      `RelExpander`, which returns a tree and has no shadowing to
      represent.
- [x] Slice 1 of the flip, live: a query that scans one plain
      collection (`from e in emps`), filters it, and optionally
      projects it last, is now built as a tree by
      `Resolver.RelFromResolver` and lowered once. Everything else
      keeps the step list, and `FromResolver.nativelyBuildable`
      chooses between them from the `Ast` alone -- necessarily, since
      `toCore` is impure and a half-converted attempt cannot be
      abandoned.

      What the slice cost, and each item was found by running the
      suite rather than by reading the code:
      * A nested query is still a step list, and its `FromBuilder`
        validates each step against the resolver's environment. So
        `$0` -- the tree's own reference, which the lowering
        substitutes away afterwards -- has to be a visible binding
        while that nested list is built. Otherwise `from x in [10,20]
        yield (from i in [1,2] take current)` fails with `not found
        [$0]`.
      * The builder names the element's *fields* as well as the
        binder, and binding those shadows an enclosing name the query
        may read: `fun employeesIn deptno = from e in emps where
        e.deptno = deptno` silently compared the field to itself. The
        resolver binds what the query's steps bind, and no more.
      * `RelLowerer.isNatural` compared *printed* forms, and printing
        a unary built-in throws (`Core.Apply.unparse` asks a `~` for
        two arguments). Structural comparison instead; the crash was
        latent, and only a tree-shaped element reached it.
      * A tree has no names, so the lowering invents them, and a plan
        full of `w$1509` is unreadable. `RelLowerer.lower` now takes
        the scan names the caller knows, with the same ordinal
        allocation the step list uses -- which `InlineTest.testFromView`
        insisted on, two `e` binders in one plan.
- [x] Slice 2: a `yield` anywhere in the query, not only last. The
      names a yield introduces are the untested half of the builder's
      map, and deriving them is the whole of the slice: a record yield
      binds its fields, at paths `#f $0`; any other yield binds the
      row at `$0` under one name, if it has one to offer -- a
      reference keeps its name, `e.deptno` gives `deptno`, and
      anything else is anonymous, which is exactly when the user
      cannot name it either. The rule has to be the step list's
      because the *type resolver* has already decided by it which
      names the later steps may use. Whether the yield is a record is
      what the user wrote rather than what the expression turned out
      to be: a record with modifiers is a `let` by the time it
      arrives, so only the `Ast` can say. No expectation moved.
- [x] Slice 3: a second scan, as a join. The right input is a tree of
      its own and cannot say `$0`, so the left's names are read off a
      dependent-join binder that the builder drops again where the
      collection turns out not to use it -- decorrelation paid at
      construction, and `from e in emps, d in depts` comes out as an
      independent join without a pass to notice.

      What a join forced into the open, having been invisible while
      every query had one binder:
      * A query with no trailing yield returns what its *binders*
        name, and a join's element is its inputs' components
        concatenated, which is not that record. So the resolver
        projects it -- and that projection is `current` as well, which
        is the same thing said in an expression.
      * `Core.StepEnv.atom` had to come back, under its own name. The
        distinction is not how many binders there are: `yield {j = i +
        1}` binds one name and the row is still a record, so `current`
        is a record too, and reading the binder instead gives an
        `int`. One binder is the row only when the step that bound it
        made it the row.
      * A yield's name has to be read off the `Ast`. The step list
        reads it off the Core -- `getIdPat` sees `Core.Id d` and says
        `d` -- but by the time the tree's converter is done, `d` is a
        path into the element and no longer looks like a reference.
- [x] Slice 4: `on` conditions, and the steps that leave the row
      alone -- `order`, `unorder`, `skip`, `take`. A condition is
      converted where the builder addresses both inputs, the left's
      names over `$0` and the right's binder over `$1`; `current` in
      one is the row so far, which is the left's, because the
      condition is asked of a row the join has not made yet. A count
      is read in the enclosing scope, as the step list reads it, since
      it is evaluated before this query has a row. Inner joins only.
      No expectation moved by any of it.

      741 of the suite's 1852 queries now build natively. What the
      other 1111 still want, by step kind: `compute` 291, `group` 222,
      `order` 130 (in queries excluded for something else), `union`
      75, `except` 49, `intersect` 47, `require` 36, `through` 31,
      `into` 6, `distinct` 3.
- [x] Slice 4: `on` conditions, and the steps that leave the row
      alone -- `order`, `unorder`, `skip`, `take`. A condition is
      converted where the builder addresses both inputs, the left's
      names over `$0` and the right's binder over `$1`; `current` in
      one is the row so far, which is the left's, because the
      condition is asked of a row the join has not made yet. A count
      is read in the enclosing scope, as the step list reads it, since
      it is evaluated before this query has a row. Inner joins only.

      Two things `order` found, both about *where* the row gets built
      rather than what it is, and both invisible until a step read the
      element after a projection:
      * The lowering deferred a projection past a sort, which is what
        deferring one is for -- except that a sort reads the element.
        `AlgebraTest.testScottOrder` caught it: Calcite sorted the
        eight-column row and projected two columns after, where it had
        been sorting two. Deferring also evaluates the projection
        twice for anything the sort key shares with it. So a sort
        materializes, as a set operator and an outer join do.
      * `finish` projected the record of binders even where the row
        was already that record, which is a node in the plan that
        nothing needs. The resolver now tracks whether the element is
        the row -- true everywhere except after a join -- and
        `current` is then the element itself rather than a record
        rebuilt out of paths into it.

      741 of the suite's 1852 queries build natively. What the other
      1111 still want, by step kind: `compute` 291, `group` 222,
      `order` 130 (in queries excluded for something else), `union`
      75, `except` 49, `intersect` 47, `require` 36, `through` 31,
      `into` 6, `distinct` 3.
- [x] Slice 5: `group` and `compute`, which were 513 of the 1111
      queries the flip had not reached. A tree's group builds a record
      whether it has one label or many (discussion.md §14), so an
      atomizing group -- `group e.deptno`, whose rows are bare ints --
      is that record and a projection that reads its one field. The
      aggregate machinery is reused as it stands: `withAggregateResolver`
      wants a `StepEnv`, and one made of the tree's bindings serves,
      since it reads only the bindings and the ordering.

      Three findings, and the third is a defect that predates the
      branch:
      * The post projection -- the step list's trailing yield, which
        names what the group produced -- is usually the identity, and
        emitting it anyway put a projection under the query's own
        yield. Merging two projections binds the row to a variable,
        which is right, and a `let` is something Calcite will not push
        down: dual.smli caught it. Skip it where the group's labels
        are already what the query calls them -- and *being* a label
        matters, not merely sharing a name, because `compute sum` with
        nothing to sum reads the built-in `sum` under that name.
      * `Core.StepEnv` asserts that an atom env holds exactly one
        binding, and the tree's bindings include its inputs, so the
        flag has to say false. It is not read here.
      * `RelLowerer.rebind`'s fallback rebuilt a record where the row
        was a single binding. `FromBuilder` inlines a subquery and
        skips its trailing `yield e`, so the binder's name is gone --
        but that yield is exactly what made the subquery's rows
        scalar, and putting a record back undoes it. Only a group
        reached it, because only a group leaves one binding in a row
        that is not that binding.

      936 of the suite's 1852 queries now build natively, up from 741.
      What the other 916 want: `compute` 91 and `group` 82 (in queries
      excluded for something else), `union` 75, `order` 67, `except`
      49, `intersect` 47, `require` 36, `through` 31, `into` 6,
      `distinct` 3.
- [x] Slice 6: the set operators, `require` (which is `where not e`,
      as the step list has it) and `distinct` (a group on every
      binder, or `take 1` where the row is `unit`, which is the
      exception the step list makes too: `group {}` always returns one
      row, so an empty input would gain one).

      And a correctness hole that had been open since the first slice,
      which only a query that shadows a binder could reach.
      Substitution was by *name*, and a name is not unique: `forall p
      in s.pictures require ... exists p in s.products where p.sku =
      sku` rebinds `p`, and the inner query -- itself built as a tree
      -- is lowered to a step list that says `p` again. The outer
      substitution then handed the inner query the outer row. It is by
      pattern now, each binder given an ordinal of its own, and the
      resolver hands back the very pattern it was given.

      Those ordinals come from a private negative range rather than
      the name generator. Taking them from the generator works and
      costs one line, and it suffixed every binder in every plan --
      `from p_1 in ...` where the user wrote `p`. These patterns are
      substituted away before anything sees them; uniqueness is all
      their ordinals owe.

      1142 of the suite's 1852 queries now build natively, up from
      936. Of the 710 that do not: about 330 are unbounded scans,
      whose grounding reads step lists and waits on `RelExpander`;
      192 are scans with a pattern; the rest are `through`, `into`,
      `yieldAll`, `ordinal`, and outer joins.
- [x] Slice 7: a scan whose pattern destructures -- `(i, j) in pairs`,
      `{a, c, ...} in recs`, `_ in xs`. The builder already had
      `push(pat, rel)`, which erases the pattern and keeps one path
      per name it binds; the resolver's work was to decide from the
      `Ast` whether a pattern binds without also filtering. The one
      thing the `Ast` does not say is whether a bare name is a nullary
      constructor -- `from NIL in xs` tests rather than binds -- so
      the type system is asked.

      Two things the slice restored, both about the row rather than
      the pattern:
      * `atom` counts *bindings*, not what the step added. `from a in
        [1], _ in [true]` binds one name, so its rows are ints and not
        records of one field, and `from {a} in recs` is an `int list`
        for the same reason. It is the step list's own rule
        (`FromBuilder.scan` says `atom = bindings.size() == 1`), and
        having dropped it in favour of "the element is the row" I had
        to put it back beside that, not instead of it.
      * A pattern that binds nothing makes rows of `unit`, which is
        not the element and not a record either.

      `RelTranslatorTest.testDestructuringScan` moved, and the new
      plan is the better one: the filter now precedes the projection,
      because a pattern binds paths into the element and the
      projection that makes the record is owed only to whatever wants
      the row.

      1360 of the suite's 1852 queries now build natively, up from
      1142. Of the 492 that do not: 270 are unbounded scans, 74 read
      `ordinal`, 24 are `through` or `into`, 23 are `yieldAll`, and
      the rest are outer joins.
- [x] Slice 8: outer joins, where the absent side binds one name. The
      mapping is three lines; the constraint is the finding. A tree's
      outer join contributes one component and wraps *it* in `option`
      (discussion.md §15), and the step list wraps each binding of the
      absent side separately. For one binding those are the same type;
      for several they are not -- `(a * b) option` against `a option *
      b option` -- and it is the step list's answer the user has seen.
      Chained outer joins are what find it, because the second one's
      left side is the first join, which binds two:
      `from i in [1,2] right join j in [3] on true right join k in [4]
      on true` is `{i:int option option, ...}`, and the tree said
      `int option`.

      Also: a scan's *condition* may read `ordinal` (`left join j in
      [...] on ordinal mod 2 = 0` counts candidate pairs), and
      `usesOrdinal` answers only for the extent, so the condition is
      asked separately.

      1377 of the suite's 1852 queries now build natively. Of the 475
      that do not, about 350 are unbounded scans, 74 read `ordinal`,
      24 are `through` or `into`, 23 are `yieldAll`, and a handful are
      chained outer joins.
- [x] Slice 9: `yieldAll`, which is what the design said it was
      (discussion.md §8) and nothing more: a dependent join whose
      binder is how the right input names the current row of the left,
      and a projection that drops the left again, since `yieldAll`
      yields only the elements. Written from the design rather than
      from the step list, and right the first time.
- [x] Slice 10: `through`. `from ... through p in f` is `from p in f
      (from ...)`, so the tree built so far is lowered *there* rather
      than at the end, and the builder starts again from the
      collection the function returns. `into` needed nothing: the
      query-level conversion strips it before the steps are seen.

      1425 of the suite's 1852 queries build natively. Of the 427 that
      do not, 74 read `ordinal` and all but a handful of the rest are
      unbounded scans -- which is now the whole of what is left, and
      is the grounding move rather than another slice.
- [x] Slice 11: `ordinal`, and the tree keeps no notion of a row's
      position. The builder has one instead: it projects the ordinal
      into a field beside the row, the step reads the field like any
      other name, and `finish` projects the field away again -- what
      the step list does with two extra yields, for the same reason,
      but held where the names are held rather than in the datatype.

      Three things it found:
      * A path to the field has to be rooted where its *reader* reads
        it, exactly as a binder's is: a join's right input reads it
        through the join's binder, and rooting it at `$0` put a leaf
        that references `$0` into the tree. So the builder keeps the
        field's *name*, not its path.
      * `finish` projected an atom row without naming it, and a
        projection takes its names from the element's fields, which an
        atom has none of. Invisible until now, because `finish` had
        only ever run at the end of a query or before a set operator;
        dropping the ordinal field made it run in the middle.
      * A row with no name (`yield i + 1` binds none) has to be given
        one before the field can sit beside it, or the record would be
        the field and nothing else.

      1492 of the suite's 1852 queries build natively. Of the 360 that
      do not, 341 are unbounded scans, 14 are queries whose first step
      is not a scan, and 5 read `ordinal` in a join's condition --
      which counts candidate pairs rather than rows, and is a counter
      the tree has no way to ask for.
- [x] `ordinal` in a join's condition, which needed no new variable
      and no new node -- only the exclusion removed. Two counters
      share the name: a row counter, which the builder materializes
      into a field, and a *candidate-pair* counter, which belongs to
      the join. The second is already scoped the way a system variable
      would be, because the difference between them is exactly whether
      a field was materialized. Where none was, `Ast.Ordinal` converts
      to the plain call, the tree carries it in the join's condition
      as an ordinary expression, and the compiler installs the counter
      when the join is lowered to a scan -- which is where it installs
      it for the step list too. The two coexist in one query without
      interfering: `from i in [1,2,3] where ordinal > 0 join j in
      ["x"] on ordinal = 0` gives the same answer either way.

      A separate question, and a language one rather than a tree one:
      whether two counters should share a name. A distinct name for
      the join's would say which is meant without the reader having to
      know that a condition is not a step.

      1495 of the suite's 1852 queries build natively. Of the 357 that
      do not, 341 are unbounded scans and 14 are queries whose first
      step is not a scan.
- [x] A query with no scan -- `from`, `from where p`, `from yield e`
      -- which iterates over one row, and that row is `unit`. The tree
      says so with a leaf holding that one row, exactly as
      `RelTranslator.unitCollection` does.

      The lowering owed the inverse and did not have it, which two
      tests found at once: `InlineTest.testFromEmptyFrom` read
      `from u in [()]` where it had read `from u in (from)`, and
      `AlgebraTest.testCalciteFrom` got a project over a values of one
      `true` where Calcite had been given a values of one empty row. A
      step list says "one unit row" by having no scan, and downstream
      reads it that way; `[()]` is the same rows and a worse plan. So
      the lowering now turns that leaf back into a scan-free step
      list, which is the translator's rule read backwards.

      1528 of the suite's 1852 queries build natively. The 324 that do
      not are unbounded scans, save a handful of chained outer joins.
- [x] Step C, done: ground a query by translating it to a tree,
      expanding that, and lowering it back -- at the call site where
      grounding already happens, so it sees inlined Core and knows
      `rowsUsed`. `Expander.expandFrom` now takes that path by
      default; `MOREL_GROUND_VIA_STEPS` puts the step list back, which
      is how to tell whether a plan changed because of this. The step
      list is still the fallback for the queries the tree path
      declines, so it is not yet dead code.

      Every script agrees, both ways, and `fullMake` is green with the
      variable set and unset.

      Two defects found and fixed on the way, both committed: the
      lowering numbered binders from the wrong generator, and a filter
      did not pass its components through (discussion.md §16).

      The tree path declines wherever the step list has an answer of
      its own -- the translator declines, expansion throws, the result
      still holds an infinite extent anywhere, or it does not lower to
      a step list -- because the step list's error names the pattern
      and its "unchanged" is read by a later pass.

      **How far apart they are, now measured.** The assertion used to
      compare whether the two ground a query; it now compares *what*
      they decide -- the collection each scan reads and the conditions
      the filters still test, canonically named and sorted, which
      ignores the step order and the projections that are the
      lowering's business rather than grounding's. 217 distinct
      queries in the suite ground differently. The old boolean
      assertion reported none of them, and four failing script files
      had suggested a much smaller gap.

      Three pieces of `Expander` that `RelExpander` does not have, all
      visible in one line of the measurement --
      `from (b, i) where i elem [3, 5] andalso b`:

          tree  [scan [3,5], scan extent "bool",
                 where op elem (v0,[3,5]) andalso v1]
          step  [scan [3,5], scan extent "bool",
                 scan from i in [3,5] group i order i, where b]

      * **Dedup** -- *ported*. The step list deduplicates a generator
        when duplicates would be observable (`expandFrom2`'s
        `dedupObservable`, which depends on `rowsUsed` and on whether
        a take or skip follows); `RelExpander` now does the same, by
        building the same `distinct`/`order`/`yield` the step list
        builds. Only for a generator whose pattern is one name, which
        is where the two agree that it is sound.
      * **Sealing** -- *ported*, and it was not sealing. The step list
        drops a conjunct two ways: a sealed generator's provenance
        subsumes it, or every generator's `simplify` is run over it
        and it comes back `true`. Only the first was in the tree. The
        second is how a query grounded by a transitive closure loses
        its `where`: the generator is *not* sealed and does not need
        to be, because `simplify` answers `true`. Without it `path p`
        stayed in the filter, `path` was re-evaluated per row, and its
        own unbounded `exists` reached the evaluator as `infinite:
        int`. With it, optimize.smli falls from 155 diffs to 9 and
        blog.smli from 790 to 23.
      * **Dedup, once vs per generator** -- *not ported, and the
        reason matters*. `scheduled` decides once, at the end of the
        chain, whether the row it yields needs deduplicating;
        `addGeneratorScan` decides per scan, in a subquery of its own.
        Porting the shape without the driver was tried and reverted.
        `addGeneratorScan` is driven *per name*, recursing through
        each generator's dependencies with an explicit `patternState`,
        so a generator is scanned exactly once, for the names it
        provides, and is never reached for a name that is already
        DONE. A loop over *generators* meets cases that driver never
        does -- a generator whose names another has already bound,
        which is then a filter and not a source -- and each one wants
        a special case. Three were written before it was clear that
        the driver, not the shape, is what has to come across.

        This is what blocks extending the schedule to leaves that are
        already bounded: without per-scan dedup the chain yields a
        tuple several times where the step list yields it once.

        Ported the driver itself next -- by *calling*
        `Expander.addGeneratorScan` rather than transcribing it, which
        is what makes `Expander.ground` one implementation rather than
        two -- and it is callable: the enum and the generator type are
        package-private, so making the method so is the whole of the
        plumbing. Two inputs had to be got right, and a third was not:
        * `allPats` is the names of *extent* scans, `allScanPats` is
          every scan's; for an all-extent join tree they are the same
          set, which is what a tree has.
        * The order names are asked for decides the chain, and
          `frame.leaves` is an `IdentityHashMap`. Walking the join
          left to right fixes that.
        * What did not come right is *which* generator the cache calls
          best for each name. The step list grounds the Lollipop query
          as `from (x, z) in wp ... join y in (from (y, z') in wp on
          z' = z ...) join w in (from (x', w) in wp on x' = x ...)` --
          each generator correlated on a name the one before it bound.
          The tree gets generators that *partition* the names, `(g$0,
          g$3)` and `(g$1, g$2)`, so nothing is bound when either is
          scanned, nothing correlates, and the chain over-produces:
          ten rows where there are seven.

        So the driver is not what is missing, and neither is the
        cache. What differed was the *order* the two front ends handed
        the engine its work.

        `Expander.expandFrom` does not call `ground` at all: it calls
        `expandSteps`, which walks the query's steps and interleaves
        them -- `maybeExtent` for a scan, `plusConstraint` and
        `improveGenerators` for each conjunct of a where. `from i
        where A join b where B` is extent, A, extent, B. The engine
        improves its generators after every constraint, so a
        constraint sees the extents registered before it and not the
        ones after, and the order is part of the answer.

        `RelExpander` called `ground(cache, extents, constraints)`,
        which registers every extent and then applies every
        constraint -- and took its extents from `frame.leaves`, an
        `IdentityHashMap`, so which order that was varied with the
        hash. Two wrongs that had been cancelling: making the extents
        deterministic on its own broke two queries, because the hash
        order had been supplying by accident what the interleaving
        supplies by construction.

        Both are fixed. `collect` records extents and constraints in
        the order the walk reaches them -- a filter's conjuncts after
        its input, a join's condition after both sides -- and
        `Expander` grew an overload of `ground` that replays such a
        sequence, which the old two-list form now delegates to. What
        strengthening adds has no place in the tree, so it goes last.

        No test moved: the suite is what it was. What is gone is a
        dependency on hash order in the middle of grounding.

      * **Shared scans** -- *ported*. A generator
        may bind several names: `(x, y) elem pairs` grounds both. The
        step list scans it once and lets a later generator join on
        whichever name is already bound (`sharedPats`, and
        `addGeneratorScan`'s `patternState` scheduling). The tree has
        the same idea in `commonGenerator`, but only where *one*
        generator binds every leaf under a join. The triangles query
        needs less than that and more than the tree can say: of `x`,
        `y`, `z`, the last two share a generator and the first does
        not, and the tree is `join(join(x, y), z)`, so the sharing
        crosses subtrees. The step list does not care, because it
        works from a flat list of scans and reorders them.

        So the port is not a rule to add but a scheduling loop to
        bring across: replace `rebuild`'s leaf-by-leaf substitution
        with one scan per *distinct* generator, ordered so that each
        generator's free names are bound before it, and a projection
        that reads the frame's element out of them. Until then the
        tree answers `{x=1,y=1,z=3}`, which is not a triangle, and
        fixed-point.smli's 477 diffs are all of this one shape.

        Ported as a schedule, not a rule: where the leaves of a join
        tree share generators, `RelExpander` now scans each generator
        once, in an order that binds what the next one reads, renaming
        an already-bound name in the scan pattern and testing it
        against what bound it -- `addGeneratorScan`'s own shape, built
        with the same `FromBuilder`, so the chain *is* the step list's
        construction and the tree scans what it yields. It cedes the
        one-generator case to `commonGenerator`, which says the same
        thing in one projection, and declines wherever it does not
        apply: nothing shared, a leaf that is not an extent, a node
        between the leaves that is not a join, a join with a
        condition, or no order that satisfies the dependencies.
        fixed-point.smli falls from 477 diffs to 17.

        A leaf-local rule was tried first and reverted, and the reason
        it cannot work is what pointed at the schedule. It made the join dependent
        where the *right* leaf's generator bound a name the left side
        binds, and filtered the collection to the matching rows --
        `Expander`'s "some patterns are already bound" branch,
        written the same way. That is right as far as it goes: it
        gives `from x, y, z where (x, y) elem pairs andalso (y, z)
        elem pairs andalso (z, x) elem pairs` the same three triangles
        the step list gives, four times faster than the cross product
        it replaced. It fails on the next instance in fixed-point.smli
        because the generator chosen for the *left* leaf binds two
        names as well: the tree picks a generator per leaf, so two
        leaves can hold generators with overlapping patterns and
        neither is the "right" one. The step list picks a generator
        per *name* and then schedules, which is why the question does
        not arise for it. Any rule that decides at a join, looking
        only at that join's two sides, is deciding too late.

      A note on what the residual "same shape, different text" is,
      since it is most of the count and none of it matters: the tree
      lowers "scan the generator's collection" by inventing a binder,
      and `FromBuilder` then inlines the collection with a step to
      rename what it bound -- `... yield {w$4 = g$0} where w$4 >= 0`
      where the step list says `where n_4 >= 0`. Naming the scan after
      the collection's own binder removes it, and captures a
      correlated subquery's variable when the collection is not
      inlined; tried, reverted, and left as noise the count carries.

      A fourth thing, which the three uncovered rather than caused: a
      generator's pattern may be one that can *fail*. `(x, 20) elem
      [(1, 10), (2, 20)]` grounds `x` by a pattern holding a literal,
      and the tree *projected* `#1` out of every row where the step
      list *scans* the pattern, which filters. `from x where (x, 20)
      elem [(1, 10), (2, 20)]` answered `[1, 2]`. It scans now.

      And a fifth, tried and reverted: extending the schedule to
      leaves that are already bounded, so that `from x in [1, 2, 3], y
      where (x, y) elem [(1, 10), (2, 20)]` joins rather than crossing.
      The schedule builds one chain in one scope, and two scans that
      rename the same name collided -- `Expander` builds a subquery
      per generator, so its `p'` never meets another; numbering them
      fixes that. What it does not fix is duplicate rows: the chain
      yields the same tuple several times where the step list yields
      it once, and which scans make duplicates observable is the part
      `addGeneratorScan` decides per generator and this decides once
      at the end. fixed-point.smli went from 17 diffs to 274, so it is
      out until that reasoning is ported too.

      A sixth, and it was a copy rather than a gap. `RelExpander`
      records what a generator subsumes in two places -- once for a
      single leaf, once inline for a join's frame -- and only the
      first had learned about `simplify`. So `from p where path p`
      dropped its filter and `from x, y where path (x, y)` did not,
      and the second reached the evaluator as `infinite: int`, which
      is where such-that.smli stopped. The join path calls the same
      method now.

      With all of that, the switch-on state is: such-that.smli 692
      diffs, blog.smli 16, optimize.smli 9, fixed-point.smli 17 --
      from 1102, 790, 155 and 477. Run outside the harness, where it
      is not cut short, such-that.smli has 306 differing lines, and
      most are `scott` being unbound there rather than anything the
      tree did: what is left is six `ClassCastException`, three
      `NullPointerException: g$1`, some row orderings, and the
      renaming yield. And the suite runs in nine seconds
      with the switch on, against sixty before the schedule: the cross
      products it replaced were most of the cost.

      With dedup ported, and the comparison no longer confusing a
      name for a difference -- it renames what the *query* binds, once
      for the whole query, rather than per part, since a part names
      binders bound outside it -- 153 distinct queries differ, from
      217. What they are:

      | count | difference |
      |------:|------------|
      |    87 | same shape, different text (residual naming) |
      |    52 | the step list has more scans (shared scans) |
      |    30 | the tree keeps a filter (sealing) |
      |    13 | the tree has more scans |
      |     3 | the tree's expansion will not lower |

      What remains, precisely. Four script files still differ, and the
      one diagnosed further is `from p where path p` over a recursive
      `path`:
      both paths run once, on the same pass, with the same
      environment, and the step list's generator comes back sealed
      while the tree's comes back `sealed=false, provenance=[]`. The
      step list therefore drops `where path p`, which the generator
      enforces; the tree keeps it, `path` is re-evaluated per row, and
      its own unbounded `exists` reaches the evaluator as `infinite:
      int`. The difference is inside `Generators`, not in either front
      end's bookkeeping: `RelExpander` looks up provenance by
      identity, and `strengthen` is written to preserve it.

- [x] Step C's remainder, closed. All four files that differed when
      the switch was turned on now pass under the tree grounding:
      blog.smli from 790 differing lines, fixed-point.smli from 477,
      optimize.smli from 155, such-that.smli from 1102. Four things
      were wrong, and each was a difference of kind rather than of
      degree:

      * **A nest of joins lowered to a nest of queries.** A join whose
        right input was another inner join lowered the right one as a
        subquery and scanned it, so the plan held a collection it then
        read back apart. It now lowers into the same builder: one scan
        per leaf, the condition a `where`, which is what a step list
        has.
      * **A collection with several binders could not be scanned.**
        `FromBuilder` will not inline one that yields a record, so the
        plan gained a scan and read the fields out. The step list
        scans it under a record pattern of the names it wants, which
        inlines. Making `RelLowerer.scan` do the same was tried and
        reverted once -- the lowering is shared with the flip, where a
        scan over a user's subquery must bind the user's `x`, and
        dual.smli said so at once with `unbound variable deptno_6`.
        The distinction the lowering lacked is the caller's, so the
        caller now passes it: grounding's collections are its own and
        may be scanned under their own binders; the resolver's are the
        user's and may not.
      * **A scan could be given another variable's name.** The
        lowering took its names from a queue, in the order the query
        was written, which is not the order grounding leaves them in
        -- a leaf is scheduled after whatever bounds it. Grounding now
        says, by identity, what each collection it builds is for. The
        queue stays for what grounding did not build, and a name goes
        to whichever asks first: two scans whose names share a base
        are one binding too many for the environment.
      * **Constraints reached the engine backwards.** Where two
        constraints could each generate a name the engine keeps the
        first, so `from dno, v where v.deptno = dno where dno = 30`
        generates `dno` from `[#deptno v]` and keeps `dno = 30` as a
        filter. The tree walk carried the conditions of the filters it
        passed down in the order it met them -- outermost, hence
        latest, first -- and generated `dno` from `[30]`. It now
        carries them the other way up.

      A measurement worth keeping: the last of these was found by
      printing the constraint list the engine sees, from both front
      ends, on the one query that still differed. Four rounds of
      reasoning about schedules had not found it, and the two lists
      side by side took a minute.

- [x] Unbounded scans, mostly done: 1760 of the suite's 1856 queries
      now build natively, up from 1528. The scan's collection is
      `extent` of the pattern's type, exactly as the step list builds
      it, and grounding -- which now goes through the tree -- takes it
      from there.

      Two earlier attempts had been backed out, and the note here said
      the move was blocked until a tree could survive as a tree until
      after inlining (step 3), because the engine matches on function
      literals and `elem` is an `Id` until `Inliner` has run. That is
      still true of the two attempts, both of which tried to ground
      *inside the resolver*. It is not true of this one, which leaves
      grounding exactly where it was, in the inline loop; what the
      resolver hands it is a step list either way. The stack overflow
      the first attempt hit was the two-name-generator bug, found and
      fixed later for an unrelated reason.

      Three things were wrong, and two were bugs of the existing
      grounding rather than of the tree:

      * **The pattern's name was spent twice.** Converting the pattern
        to get the extent's type took its ordinal from the generator,
        so the lowering's own scan found `x` taken and called itself
        `x_1`. The type map has the type without converting anything.
      * **A group's key name was assumed to be its value's name.**
        `Expander` projects its shared patterns away at the end, and
        decided which by asking which bindings are the query's own.
        After `distinct` groups on `x_1` and binds a new `x`, none
        are, so it projected them all away and the query returned
        `unit list`. It now asks the question the other way up --
        which bindings are shared -- and does nothing when none is.
        The old test passed only because the two names usually agree.
      * **Flattening a pattern changes its type.** `from {b, i}` scans
        `bool * int` in the step list, not the record, because
        `extentPat` flattens. The tree's element is the pattern's own
        type, so the two agree only for a name or a tuple of names,
        and the rest keep the step list.

      Then the annotated pattern, which is most of what was left:
      `from i : int` and `from p : parityPair` are the way an
      unbounded scan usually says what it ranges over. The annotation
      is see-through for the pattern, but not for the type: a checked
      type's condition belongs in the query, where grounding can use
      it to generate the values rather than generate and reject them,
      and without it `from n : nat where n elem [~2..2]` answered
      `[~2,~1,0,1,2]`. The tree adds it as a step of its own, as the
      step list does, reading the row out of the builder rather than
      out of the pattern, which the tree does not have.

      1780 of 1856 after that, and 1812 with `yield r = e`, which was
      the largest thing left at 33 queries. A binder names the whole
      row, whatever it yields, so a record yielded this way binds one
      name and not its fields -- which is `FromBuilder`'s rule for a
      binder, said to the builder instead.

      That one exposed a bug of its own, older than the tree and
      reachable without it, now logged as issue #468 and to be fixed
      on main. The fix here is only what this branch needs to stay
      green; take main's when the two meet.

      `from i in [1, 2, 3] yield {h = h}`, where
      `h` comes from the enclosing scope, failed with "conversion to
      core did not preserve type". `FromBuilder` decides whether a
      record is the row renamed by comparing each field's name with
      the name of the id in it, and never asked whether that id is one
      of the row's bindings, so it called this one the identity and
      gave the step the row's own bindings. The suite did not have the
      query; it had `yield h = {h = h}`, which the tree path had been
      declining.

      Then `group g = ...`, which is the same shape as the yield
      binder -- one name for the whole result, not the labels the
      group made -- and takes it to 1826. `from i in [1, 2, 3] group
      j = i` now loses a projection: the identity `yield i` that the
      resolver used to leave in the step list is not there when the
      tree builds it, which `RelTranslatorTest` had documented as
      belonging to the resolver rather than to the translation.

      Then the unbounded scan that binds more than one name, which
      was left out earlier because the lowering could name a scan and
      not the parts of one, so `from (b, i)` reached the plan as
      `w$26` and grounding quoted that in the error it raises when it
      cannot bound a leaf. What `scanNames` carries is now a list per
      scan rather than a name, and where it holds several the lowering
      scans under a tuple pattern of them. 1834 of 1856.

      Two things had to be right for that. The names come from the
      Ast, not from converting the pattern: converting it takes the
      ordinals, and the lowering -- which mints the patterns the plan
      actually has -- would then call them `b_1` and `i_1`. And only
      an unbounded scan gets this; supplying the names for an ordinary
      `from (a, b) in pairs` as well moved 561 lines of expected
      output across ten files, because those plans are named the way
      they are for reasons of their own.

      Then the scan whose pattern can fail to match, which is the
      last slice worth a name and takes it to 1844. A pattern that can
      fail filters as well as binds, and the two halves are separate
      nodes: a filter for the condition, the paths for the binding.
      `RelTranslator` had worked that out already -- it is how it
      translates such a scan -- so the move was to put `test` and
      `testable` where `RelBuilder` can reach them, beside
      `destructurable`, which is the question they answer a harder
      version of. `RelTranslator` now asks `RelBuilder` rather than
      keeping its own copy.

      Then the "as" pattern, which had looked like it belonged with
      the constructor and does not. `p as (a, b)` binds the whole
      value and its parts, and in a tree both are paths to the same
      element, so nothing makes the two dependent -- what
      `extentPat`'s comment says about them is true of a step list,
      where the pattern reaches Core as a value to be taken apart.
      1847 of 1856.

      Three places had to learn about it, and the third was the one
      the suite found: `destructure`, so the name is bound; `test`, so
      that `p as (h :: t)` filters by what it wraps; and the resolver's
      own collection of a pattern's names, which walked for
      `Core.IdPat` and an `AsPat` is a `NamedPat` that is not one. And
      the names of such a pattern do not line up with the element's
      components -- it binds one more -- so it is not one the lowering
      can scan under.

      And then the constructor, which turned out not to need the
      `case` after all -- not here. All seven were *unbounded* scans,
      and such a scan generates the values that match rather than
      filtering values it is given, so `extentPat` keeps only the
      pattern's variables and the constructor says which values are
      wanted and then plays no further part: `from SOME (i : int)`
      scans the extent of `int`. The tree's flattening path already
      did this; only the gate had to learn that an unbounded scan asks
      a weaker question than a bounded one.

      1854 of 1856, and then all 1856. The two that were left were
      chained outer joins, and the note here had them down as a
      decision about the IR. They were not: §15 had already decided
      it -- "a concatenation gives each component of the absent side
      its own option, and a component is a binder's value, which is
      Morel's rule exactly" -- and the implementation had drifted.
      `CoreBuilder.joinElementType` wraps per component, as §15 says;
      `isFlat` said an outer join is one component, which is the
      *pair* §15 rejected, and the two contradicted each other.

      The reason `isFlat` gave was that "the step list the tree lowers
      to re-types whole bindings, not fields of them". It re-types
      each binding, and additively -- its own comment says so, and
      that is where `int option option` comes from when two outer
      joins chain.

      So `isFlat` is true for every join, and three things follow.
      The lowering leaves a side of several bindings apart rather than
      materializing it into one, so the scan wraps each. `rebind`
      handles an expression over several bindings, rebuilding the
      tuple rather than copying it, because the components' types have
      changed. And a name that reads *into* an option-wrapped
      component maps through the option, which is the `Option.map`
      that `RelTranslator` already had for exactly this: `left join
      (j, k) in pairs` binds two names inside one component, and they
      are `int option` apiece.

      **Measured, not assumed.** Letting them through and following
      the failure narrows it to one place. The lowering *can* produce
      the step list's shape: leave a side of several bindings apart
      rather than materializing it into one, and `FromBuilder`'s scan
      wraps each of them, which is where `i option option` comes from.
      Two things had to be fixed to get that far -- `rebind` handled
      only a bare id, and rebuilding the re-typed components as a
      tuple has to construct rather than copy, because a copy keeps
      the old type -- and after both, the element handed up is
      correctly `int option option * int option`.

      The result is still `{i:int option, j:int, k:int}`, because the
      *resolver's* paths address the tree's shape: `i` is inside the
      one `option`-wrapped component, and no amount of work in the
      lowering changes where the name map says to look. So the two
      shapes have to be reconciled where they are decided -- either a
      tree's outer join contributes a component per binding, each
      wrapped (a change to discussion.md §15), or the query's type
      changes and the step list's answer is no longer the one users
      see. That is a decision about the IR, not a defect in the
      translation, and it is the last thing between this and every
      query flowing through the tree.

      Two plans in `RelTranslatorTest` lost a projection by it: the
      tree names what a cons pattern binds with paths rather than
      binding it, so nothing materializes the `xs` that `from (x ::
      xs) ... yield x` does not read.

      That last one is a naming limit rather than a translation one.
      `from (b, i)` grounds and answers correctly, but the tree erases
      the pattern and the lowering can name a scan and not the parts
      of one, so the components reach the plan as `w$26` -- and
      grounding names them in the error it raises when it cannot bound
      one, where `such-that.smli` expects `pattern 'i' is not
      grounded`. Closing it means a name per component reaching the
      lowering, which is a change to what `scanNames` carries.

      One consequence worth stating: a query the resolver now builds
      natively can be one that only the tree can ground -- `from (b,
      i) where i elem [3, 5]` is a single scan of a pair, where the
      step list wants a pattern per component. So
      `MOREL_GROUND_VIA_STEPS` puts the native path for unbounded
      scans back too; it is one switch for one old world, not two.
- [x] Then flip for real: every one of the suite's 1856 queries flows
      through the tree, and the suite checks the translation by its
      results.
- [x] Delete the AST→From path; the resolver builds trees natively.
      Done: 595 lines out of `Resolver`, and `nativelyBuildable` with
      them -- there is no longer a choice to make. What went is the
      step-by-step conversion (`acceptStep`, `withStepEnv`, a `visit`
      per step kind, `scanTypeCondition`, `rowValue`) and
      `FromResolver`'s `FromBuilder`; what stays is the query-level
      wrapping that `into`, `exists`, `forall` and `compute` need, and
      the questions the step conversion asks about `ordinal`.

      **`MOREL_GROUND_VIA_STEPS` is retired with it, and that is a
      real loss.** It was the way to ask whether a plan that changed
      changed because of grounding, and it could not survive: a query
      the resolver builds as a tree can be one only the tree can
      ground -- `from (b, i) where i elem [3, 5]` is a single scan of
      a pair -- so reverting the grounding without also reverting the
      resolver breaks queries, and reverting the resolver is what this
      step deletes. `Expander`'s step-list grounding remains as the
      fallback for what `expandViaTree` declines; it is simply no
      longer switchable.

      Build them through a *builder*, not by constructing nodes
      directly and not by aping `FromBuilder`. The research is done
      (discussion.md §13): of `FromBuilder`'s 855 lines, the parts
      that carry scope, defer a step's fate until it knows whether
      anything follows, splice a subquery into the enclosing list,
      pass `atom` in rather than deriving it, and keep binder names
      stable across a `yield` are all the step list's bill, and a
      tree owes none of it. `CoreBuilder`'s `Rel` methods already
      derive element type and kind and validate by type.

      So the builder owns one thing beyond that: the element
      expression. The evidence is that the sentence "the element is
      the sole binding's value if it atomizes, otherwise a record
      with one field per binding" is written four times already
      (`RelTranslator.elementType`, `RelLowerer.naturalElement`,
      `FromBuilder.dropOrdinal`, `CoreBuilder.fromElementType`), and
      a fifth time in the resolver, where `withStepEnv(fromBuilder
      .stepEnv())` reassembles it on every step to set `current`.

      So the builder does three things: keeps a **stack** of
      relational expressions, which a node takes its inputs from and
      leaves its result on; keeps a **name map**, so that `e` names
      input #1 and `deptno` its second field; and **simplifies**,
      through an `EnumSet` in which each simplification is named and
      can be switched off. Node construction, type and kind
      derivation and validation stay in `CoreBuilder`; scoping and
      name resolution stay with the resolver, which gets smaller,
      because `current` stops being derived from bindings and falls
      out of the name map.

      The `EnumSet` is what keeps this from being `FromBuilder`
      again: its simplifications were unconditional, entangled with
      the scope bookkeeping, and set in one method to be applied in
      another. Named and switchable, they are none of those, and an
      empty set makes the builder a pure constructor — so the same
      query can be built twice and the two compared, which is a
      sharper test than a golden file.

## Step 3 — Flip observability

**Surveyed before starting, because it is a different size of thing
from step 2's slices.** What is already in place: the printer
(`Core.Rel.describe(withTypes)`, which is what `RelTranslatorTest`
prints and what spec.md §6 specifies, `withTypes` being planEx's
`: type`), and the validator (`RelValidator.violations`, run by the
shadow on every query the suite compiles). What is missing is only
that no tree survives to where `Sys.plan` looks: the resolver lowers
it immediately, so what executes and what prints is a step list.

Which passes would have to learn `Core.Rel`, counted by how many
times each names `Core.From` today:

* `Inliner` (0) and `Analyzer` (0) name it not at all, and `Shuttle`
  and `Visitor` already traverse every `Core.Rel` node, so they would
  descend correctly by inheritance.
* `SuchThatShuttle` (2) hands a `Core.From` to `Expander.expandFrom`.
  A tree wants `RelExpander.expand`, which it already has -- and that
  is also what collapses the round trip this step is named for, since
  grounding would stop translating a lowered step list back into the
  tree it came from.
* `Relationalizer` (11), `Compiler` (11) and `CalciteCompiler` (4)
  case on `Core.From` throughout. The compilers could lower at their
  own boundary, which would let the tree survive every *rewrite* pass
  and be lowered only for code generation -- that is enough for the
  plan text this step is about.

**The hazard, and it is not in any of those.** `$0` is an ordinary
`Core.Id` over an `IdPat` named `"$0"` with ordinal 0, and
`IdPat.equals` compares name and ordinal and *not type*. So every
`$0` in a tree is the same variable to anything that reasons about
free variables: `Analyzer`'s use counts, `Inliner`'s substitution,
`freePats`. Today that is harmless, because a tree is built, lowered
and discarded inside one pass and no such pass ever sees one. The
moment a tree survives the inline loop it is not harmless, and it
will not announce itself -- a use count that is too high only makes
the inliner decline, while one that is too low makes it substitute
across a node boundary. Decide how `$0` is told apart (a distinct
`Core` node rather than an `Id`, a per-node ordinal, or a scoping
rule the free-variable walk honours) before the first pass sees a
tree, not after.

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
