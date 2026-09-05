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
- [ ] Step C, started: ground a query by translating it to a tree,
      expanding that, and lowering it back -- at the call site where
      grounding already happens, so it sees inlined Core and knows
      `rowsUsed`. `Expander.expandFrom` now takes that path when
      `MOREL_GROUND_VIA_TREE` is set, and the step list otherwise.

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

- [ ] Step C's remainder: eight lines of plan text in
      such-that.smli, and nothing else. Three of the four files that
      differed when the switch was turned on now pass entirely under
      the tree grounding -- blog.smli from 790 differing lines,
      fixed-point.smli from 477, optimize.smli from 155 -- and
      such-that.smli is at 8 from 1102, with no crashes, no wrong
      answers and no orderings left.

      What the eight are: the tree scans a grounded collection under
      one name and reads the fields back out of it, where the step
      list inlines the scan and keeps the names.

          step  from ({deptno = dno, ...}) in ... group {dno, name}
                order ... where dno > 20
          tree  from dno in (from ({deptno = dno_1, ...}) in ...
                group ... order ...) where #dno dno > 20 yield {...}

      `FromBuilder` will not inline a collection that yields a record
      under a scan of one name, and the step list scans it under a
      record pattern of the names it wants, which inlines. Making
      `RelLowerer.scan` do the same was tried and reverted: the
      lowering is shared with the flip, where a scan over a user's
      subquery must bind the user's `x` and not the subquery's own
      names, and dual.smli said so at once -- `unbound variable
      deptno_6`. Closing it means telling the lowering which
      collections may be scanned under their own binders, which is a
      distinction its caller has and it does not.

- [ ] Unbounded scans, the last 324, and the reason is sharper than
      "grounding reads step lists". Tried, and backed out; the branch
      is green without it.

      **Grounding must run after inlining, and the resolver runs
      before it.** The engine matches on *function literals*, and
      until `Inliner` has run a built-in such as `elem` is still an
      `Id` -- which `RelExpanderTest`'s own fixture says in a comment,
      and which is why `Compiles` runs `SuchThatShuttle` in the inline
      loop rather than at conversion. Two attempts, each answered by
      the suite:
      * Let the tree carry the extent and leave the lowered step list
        to `SuchThatShuttle`, as today. The pattern is gone by then --
        a tree erases it -- so the engine cannot tell a pattern that
        named each component from one that named the whole, and the
        inliner recursed until it overflowed the stack on
        `from n where n elem [1,2,3]`.
      * Call `RelExpander.expand` on the tree inside the resolver. The
        tree is exactly right (`filter [$0 elem [1, 2, 3]]` over
        `extent "int"`) and the engine finds no generator, because
        `elem` is still an `Id`.

      So the move is about *when*, not about which class: the tree has
      to survive as a tree until after inlining, which is step 3's
      business (`Core.Rel` reaching the compiler) rather than a slice
      of the flip. Until then unbounded queries keep the step list,
      and that is the whole of what does.
- [ ] Then flip for real: every query flows through the tree, and the
      suite checks the translation by its results. `Sys.plan` output
      changes (it prints the *executable* plan, which is exactly what
      this step changes); query results must not.
- [ ] Delete the AST→From path; the resolver builds trees natively.
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
