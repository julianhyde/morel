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
# `ordinal` as a row field — implementation plan

Implementation plan for "Change implementation of `ordinal` from a
thread-local 'slot' to an invisible field in each row" (issue #434).

The user-visible semantics of `ordinal` do not change. This is a
change of representation: from mutable state hidden in the compiled
plan to an ordinary (but invisible) field of the row, generated while
translating AST to Core.

## 0. Status

Branch `434-ordinal-field`. `fullMake` green at the last commit that
touched code (511 tests, 0 failures).

Done:

* **Phase 1** -- `ordinal` is materialized as a row field during
  AST-to-Core. `Resolver.ordinalPat` carries it;
  `FromBuilder.materializeOrdinal` / `dropOrdinal` add and remove it;
  `groupOverOrdinal` deleted. Fixed a bug: an `order` key or scan
  condition used to read a counter nothing advanced (§2.6).
* **Phase 2** -- `OrdinalChecker`, since deleted by phase 3.
* **Phase 3** -- the counter travels in `Compiler.Context`;
  `ORDINAL_CODE` deleted; only a `yield` installs a counter, so a call
  anywhere else throws at compile time; the reset moved into
  `RowSink.start`, deleting `RowSinks.first`, `FirstRowSink` and
  `Describer.addStartAction`.
* **Phase 4** -- a reading `yield` holds the call, so
  `yield {ordinal, e.name}` costs no extra step. Required inheriting
  the counter into match-list arms (`compileMatchListImpl`).

Not done:

* **Phase 5** -- abandoned as specified; dropping the
  `containsOrdinal` guard miscompiles. See §8.
* **Phase 6** -- Calcite. Untouched, still optional.
* **§12** -- a regression on this branch: `ordinal` before a nested
  query's first row. Fix this before anything else.

Issues: #435 (ordinal in a join's `on` condition) is filed.
`issue-ordinal-before-first-row.md` is drafted and unfiled; it covers
the language question behind §12.

Suggested order for the next session: decide the §12 rule (reject, or
carry the field), implement it with the traversal of §3a, add tests for
all five positions, then reconsider phases 5 and 6.

## 1. How `ordinal` works today

`ordinal` is a reserved word (`MorelParser.jj`) parsed as an atom into
`Ast.Ordinal`. `TypeResolver.deduceOrdinalType` checks that it occurs
inside a query and types it `int`. `Resolver.toCore(Ast.Ordinal)`
lowers it to the nullary call `$ordinal ()`, an application of the
function literal `BuiltIn.Z_ORDINAL`. In Core it is therefore an
opaque call, neither a variable nor a field.

The counter lives in the compiler and in the compiled plan:

* `Compiler.ORDINAL_CODE` is a `TryThreadLocal<int[]>` holding a
  one-element mutable box.
* `Compiler.compileRow`, `compileRowMap` and `compileGroupSink` each
  push a fresh `int[] {0}` around the compilation of one row scope's
  expressions.
* Each `$ordinal` call site compiles to `Codes.ordinalGet(box)` and
  bumps the box, using it as a *use counter*.
* If the count is positive, the compiler resets the box to `-1` and
  wraps the code — for a row map, the *first* code; for a group, the
  key tuple — in `Codes.ordinalInc`, which increments the box and then
  delegates. So the first row reads 0.
* At run time the box is reset to `-1` by a start action:
  `OrdinalIncCode.describe` registers `resetOrdinal`, and
  `RowSinks.first` walks the sink tree with a `Describer` purely to
  collect those actions.

Two places already cannot use the counter, and work around it:

* `FromBuilder.scan` refuses to inline a subquery that mentions
  `ordinal`, because merging the loops would corrupt the count.
* `FromBuilder.group` delegates to `groupOverOrdinal` when an
  aggregate argument mentions `ordinal`. Aggregate arguments are
  evaluated after the rows have been collected into groups, when the
  input row's position is gone, so `groupOverOrdinal` prefixes the
  step with a `yield` that materializes the ordinal as an ordinary
  field and rewrites the keys and aggregate arguments to read it.

`groupOverOrdinal` is the design this plan generalizes.

## 2. Problems with the current implementation

1. **Shared mutable state in the plan.** The counter is a box baked
   into the `Code` tree, not per-execution state. Correctness rests on
   the `RowSinks.first` reset, which exists only for this feature and
   requires a full walk of the sink tree at query start.
2. **Compilation depends on a thread-local.** `ORDINAL_CODE` couples
   three unrelated compiler methods and makes the meaning of
   `$ordinal` depend on which method is on the stack.
3. **Optimizations are blocked.** Subquery inlining is disabled for
   any subquery mentioning `ordinal`.
4. **Fragile invariants.** Group keys work only because the key
   happens to be evaluated exactly once per input row; a row map works
   only because the increment is attached to the first of its codes.
5. **No Calcite translation.** `CalciteCompiler` has no case for
   `Z_ORDINAL`, so a query using `ordinal` cannot be pushed down.
6. **It gives wrong answers.** Only `compileRow`, `compileRowMap` and
   `compileGroupSink` push a counter. An `order` key
   (`Compiler.compileOrderSink`) and a scan or join condition
   (`Compiler.java:830`) are compiled with plain `compile`, so
   `ordinal` there reads a box that is never incremented and returns
   the same value for every row. Found while implementing phase 1,
   which fixes it: see the two `order (~ordinal)` and
   `on i = ordinal mod 2` cases in `relational.smli`, whose answers
   changed from "unsorted" and "empty" to the correct ones.

## 3. Proposed design

The governing principle: **the field exists only where it is read.**
A step never produces an ordinal for its own sake. The field is
generated by an upstream `Project`, and only because a downstream
operator has been seen to read it. The decision is made during
AST-to-Core translation, the last point at which `ordinal` is still a
syntactic thing rather than a value.

So:

* A query that does not mention `ordinal` translates exactly as today
  — no field, no sequence generator, no cost. This is the overriding
  constraint; nothing below may add a step to a query that does not
  use `ordinal`.
* Where it is mentioned, the reader's *input* carries the field, and
  the reference becomes an ordinary `Core.Id`.
* The field is *invisible*: a generated name, excluded from implicit
  projections, and absent from the query's result type.

For example, `from e in emps where ordinal < 2` becomes, in Core
(schematically):

```
from e in emps
  yield {e = e, v$0 = $ordinal ()}  (* generated: the 'where' reads it *)
  where v$0 < 2
  yield {e.deptno, e.id, e.name}    (* implicit; v$0 is not projected *)
```

### Representation: an ordinary expression in a `Project`

`ordinal` stays what it is today in Core — the nullary call
`$ordinal ()`, an application of `BuiltIn.Z_ORDINAL`. There is no new
slot on `Core.Yield` and no new node type. What changes is *where the
call is allowed to be*, expressed as one validation rule:

**`$ordinal ()` may appear only in the expression of a `Yield`** (the
`Project` operator). Not in a `where` condition, an `order` key, a
`group` key, an aggregate argument, or a `take` or `skip` count.
Those all read a field instead.

A `Yield` may contain any number of calls. The increment belongs to
the step, not to the call: the step is evaluated exactly once per
input row, and every call in it reads the same counter and sees the
same value. Only the *position* matters, which is what the rule
constrains.

That last point is deliberate, and was a correction. An earlier draft
also required at most one call per `Yield`, on the grounds that two
calls would run two generators. They do not — `OrdinalIncCode` wraps
the whole row expression — and the extra rule would have made a legal
optimization illegal: merging two yields, inlining a `let`, or
duplicating a subexpression can each put a second call in one project
without changing what the query means. A rule that rejects correct
Core is worse than no rule.

`group` shows why the reader must often be a different step from the
producer, and is already implemented that way. Its keys are evaluated
once per input row but its aggregate arguments are evaluated after
grouping, when row position is gone, so `groupOverOrdinal` prefixes
the step with a `yield` that materializes the ordinal and rewrites
both keys and arguments to read it. Under this rule that stops being a
special case and becomes what every step does.

### Where the increment lives

The two forms that look problematic behave, today, like this:

```sml
from i in [10,20,30] yield {a = ordinal, b = ordinal};
> val it = [{a=0,b=0},{a=1,b=1},{a=2,b=2}] : {a:int, b:int} list

from i in [10,11,20,21,30]
  yield (if i mod 10 = 0 then ordinal else ~1);
> val it = [0,~1,2,~1,4] : int list
```

Both come from the same fact: `OrdinalIncCode` wraps the whole row
expression rather than sitting at the use site, so the increment is
per row, not per evaluation of the call. Two calls are two reads of
one counter, and the count advances on the rows that do not read it
at all — note the 0, 2, 4 in the second.

**The new runtime must keep that property** (§6). It is what lets the
rule be about position only, and it is not something a validation rule
could recover: the conditional case has a single use, so no rule about
how many calls a yield contains would protect it.

A compiled yield can still ask, once, whether its expression contains
any call, and skip the counter entirely if not — which is what
`compileRowMap`'s use-counter already does.

## 3a. Evaluation frequency, and the "before the first row" set

A query's expressions fall into three classes by how often they are
evaluated. The classes are what `ordinal` is really about, and getting
them wrong is what produced the defect in §12.

1. **Once per execution of the query** -- "before the first row". The
   expression the *first* step scans; a `take` or `skip` count; the
   operands of `union`, `except` and `intersect`. `TypeResolver` calls
   this the root environment (`Triple.rootEnv`), and `Resolver` resolves
   these with `withEnv(env)`, deliberately excluding the query's own
   bindings -- there is no row yet.
2. **Once per row arriving at step *N***. Step *N*'s condition, key or
   expression, and the extent of a scan that is not the first step.
3. **After the rows have been collected** -- an aggregate argument.

An `ordinal` is meaningful only in class 2, and belongs to the step
whose rows it counts. The subtlety is that class 1 of a *nested* query
is class 2 of the enclosing step: a nested query is executed once per
row of the step containing it, so its "before the first row"
expressions are evaluated exactly as often as that step's own.

That is why the rule in §4 has the shape it does, and why stating it as
"the first step's extent" was too narrow.

### The abstract visitor

The set is worth capturing once, rather than being re-derived at each
place that needs it. The shape: an abstract visitor that traverses only
the expressions evaluated the same number of times as the root
expression, in the manner of `EnvShuttle` -- the traversal is the base
class, and each use supplies what to do at the leaves.

Three uses in sight:

* the `ordinal` lookahead in `Resolver`, which decides which step must
  materialize the field;
* whatever fixes §12, which needs the same set on the resolution side;
* any analysis asking "is this expression loop-invariant with respect
  to the query", which is the same question.

It is needed in two forms, on `Ast` (the lookahead runs before Core
exists) and on `Core` (for analyses over compiled queries). The class
boundaries are identical; only the node types differ.

## 4. Which step an `ordinal` belongs to

An occurrence of `ordinal` belongs to the innermost step whose input
row it is positioned within — equivalently, the innermost enclosing
expression evaluated once per row. Stated as a rule over the AST:

* An `ordinal` in an expression of step *N* of query *Q* belongs to
  step *N* of *Q*, and counts the rows arriving at that step.
* An `ordinal` inside a **nested query** that appears in such an
  expression belongs to the *outer* step, if it occurs in the nested
  query's first (scan) step — because that expression is evaluated
  once per outer row. If it occurs in a later step of the nested
  query, it belongs to that inner step.
* An `ordinal` with no enclosing step is an error, which is why
  `from i in [ordinal, ordinal + 1]` is rejected today.
* In a join step, both the extent expression and the `on` condition
  see the ordinal of the **left** row, that is, of the row arriving at
  the step. The condition is evaluated once per candidate pair, so it
  is the one place where the rate of evaluation and the ordinal
  disagree. Decided for now; #435 is the follow-up that changes the
  condition (only) to the ordinal of the candidate pair. The extent
  expression keeps the left ordinal either way, because no pair has
  been formed when it is evaluated.

This is the existing behavior, and `relational.smli` pins it: the
nested-query case gives `[{i=10,js=[10,11]},{i=20,js=[21,22]},…]`,
that is, the *outer* row's ordinal. The rule is written down here
because the current implementation gets it for free — the thread-local
box in scope is by construction the enclosing row scope's — whereas a
lookahead has to reproduce it deliberately.

Note that `TypeResolver.deduceOrdinalType` attributes the ordinal via
`stepStack`, which in the nested case names the inner query's first
step rather than the outer step. That attribution feeds only the
"cannot use `ordinal` in unordered query" check, so it is harmless
today; it must not be reused as-is for field generation without
checking this case.

## 5. Generating the field

In `Resolver.FromResolver`, which converts a query's steps in order.
Before converting step *N*, look ahead at step *N*'s AST for an
`Ast.Ordinal` belonging to it under the rule of §4. If there is one:

1. Generate an `IdPat` and have `FromBuilder` insert a `yield` before
   step *N* that carries all current bindings plus that pat, bound to
   `$ordinal ()` — one call, in a `Yield`, satisfying both rules of
   §3.
2. Record it in the converter's context — the `Core.IdPat`,
   equivalently the ordinal of that field within the row — as a field
   of `FromResolver`, e.g. `@Nullable Core.IdPat ordinalPat`.
3. Convert step *N*. `Resolver.toCore(Ast.Ordinal)` returns
   `core.id(ordinalPat)` when the context holds one, and builds
   `$ordinal ()` only when the step being converted is itself the
   generating `yield`.
4. Clear the context field. An `ordinal` in step *N+1* counts a
   different sequence — the rows arriving at *N+1* — so it needs its
   own generator.

Step *N* being a `yield` is the case worth special-handling, and it is
the common one: `yield {ordinal, e.name}` needs no preceding step at
all, because the yield may hold the call itself. That is phase 4; the
first cut always inserts.

The lookahead is the only new analysis. `FromBuilder.containsOrdinal`
already does the equivalent test on Core; the new one runs on the AST,
before the generator exists, and honors the nested-query rule of §4.
It need only answer yes or no: a step that reads `ordinal` several
times needs one field, not several.

Naming uses `typeSystem.nameGenerator`, as `groupOverOrdinal` already
does, so the field is `v$0`, `v$1`, … and cannot collide with a user
name.

`take` and `skip` are unaffected. They count rows to implement
themselves — `TakeRowSink` has its own `int take` — and those counters
stay private. They are not reimplemented on top of the ordinal field,
and nothing here changes them.

### Invisibility

The generated pat is a binding of the generating step — it must be, or
the reader could not refer to it — but the field must not reach the
query's result. The implicit final `yield` projects the bindings in
scope, so without suppression `where ordinal < 2` would return
`{deptno, id, name, v$0}` instead of `{deptno, id, name}`.

Options:

* A flag on `Binding` (or a set of generated pats held by
  `FromBuilder`) marking the binding as not projectable, honored
  wherever an implicit projection is built.
* Drop the field explicitly, in the same place the reader is
  converted, once the context is cleared in step 4 above.

The first is preferred: it is one predicate consulted by the existing
projection-building code, and it also makes the field invisible to
`Sys.plan`-visible types without special-casing.

### Reuse across steps (deferred)

If step *N* is cardinality- and order-preserving (a `yield`), the rows
arriving at *N+1* are in one-to-one correspondence with those arriving
at *N*, so one generator could serve both. Not worth doing in the
first pass; noted so the context field in step 4 can later hold a
validity condition rather than being cleared unconditionally.

## 6. Runtime: where the counter lives

The counter moves from a box baked into the `Code` tree to a field of
the yield `RowSink` that evaluates the generating step. The increment
stays where it is today — **once per accepted row, around the whole
row expression, not at the call site** — which is what keeps
`yield (if i > 15 then ordinal else ~1)` returning `[~1,1,2]` (§3).
`$ordinal ()` remains a pure read.

This is where the design pays off. `RowSinks.from` takes a
`Supplier<RowSink>` and `FromCode.eval` calls `rowSinkFactory.get()`
on *every* execution, so a counter field in a `RowSink` instance is
automatically per-execution, per-nesting-level, and per-thread. No
box, no thread-local, no reset. It is also the established pattern:
`TakeRowSink` already holds its row budget the same way.

Concretely:

* The yield `RowSink` gains an `int` counter, incremented in
  `accept()` before the row expression is evaluated, and `$ordinal ()`
  compiles to a read of it.
* `Codes.ordinalGet` / `Codes.ordinalInc` and `OrdinalGetCode` /
  `OrdinalIncCode` are deleted, along with `Compiler.ORDINAL_CODE` and
  the `ORDINAL_CODE` blocks in `compileRow`, `compileRowMap` and
  `compileGroupSink`.
* `Describer.addStartAction`, `RowSinks.first` and `FirstRowSink` are
  deleted, along with the `CodeVisitor` walk they require — they exist
  only to reset the ordinal.
* `BuiltIn.Z_ORDINAL` survives, and the `Compiler.compileApply` case
  for it survives in reduced form: it no longer allocates or bumps a
  shared box, it just addresses the enclosing sink's counter.

## 7. Calcite

`CalciteCompiler.yield_` translates a `Core.Yield` to
`relBuilder.project`, and `translate` has no case for `Z_ORDINAL`, so
a query using `ordinal` cannot be pushed down today. Rule 2 of §3 is
what makes a translation possible: at most one call per project means
at most one row-number column per project.

Options, in increasing order of work: leave the Calcite path alone
(decline to translate a project containing the call, so such a query
falls back to the interpreter — the status quo); emit
`ROW_NUMBER() OVER ()` as a `RexOver`; or add a Morel-specific
`RexNode` and a rule. Deferred to phase 6 and treated as optional.

## 8. Optimizations enabled and affected

* **Not enabled after all.** This section claimed `FromBuilder.scan`
  could drop its `!containsOrdinal(exp)` guard, on the grounds that
  once the subquery's ordinal is a materialized field, merging its
  scan into the enclosing loop is safe because the field carries the
  inner count. That is wrong on both counts, and dropping the guard
  miscompiles: `from j in (from k in ["a","b","c"] where ordinal < 2)`
  throws `ClassCastException`.

  The field does not carry anything, because the step that *computes*
  it is spliced along with the rest. Inlining drops the subquery's
  trailing `yield` (`skipLast`) and rebuilds the projection from the
  surviving bindings -- which, after phase 1, include the generated
  ordinal field, so the field leaks into the result and its type no
  longer matches. And where the enclosing builder already has bindings,
  merging multiplies the rows the counting `yield` sees, so the count
  would change even if the projection were right.

  The guard is therefore semantic, not an artifact of the old
  implementation. It covers the case `safeToInline` does not: the
  subquery as the *first* step, where there is no join multiplication
  and inlining otherwise looks safe.
* **Affected.** An inserted `yield` perturbs anything that pattern
  matches on step shape: `isSimplePat`, `safeToInline`, and the step
  counting in `FromBuilder`. A generating yield is identifiable by
  `containsOrdinal`, so these can test for it rather than infer it.
* **Affected — the cost of keeping `ordinal` an expression.** The
  rule of §3 is not enforced by construction, so a pass that hoists a
  yield's field expression into a `where` or an `order` key would move
  a call out of the step whose increment it depends on. That is what
  the validation walk checks. Duplicating or merging within yields is
  safe, which is most of what the passes actually do.
* **Affected.** `Inliner` and any pass that duplicates an expression
  must not duplicate a reference to a generated field into a different
  row scope. Since the reference is an ordinary `Core.Id`, the
  existing scoping rules cover this; the value of the change is that
  they now *apply*, where `$ordinal ()` was opaque.

## 9. Phases

Keeping `ordinal` an expression means the phases land separately: the
existing runtime can compile a generated field the moment it exists,
so generation and the runtime change are independent commits.

1. **Generation.** *(Done.)* The AST lookahead (§4), the `ordinalPat`
   context field on `Resolver`, and `materializeOrdinal` /
   `dropOrdinal` in `FromBuilder`; `groupOverOrdinal` folded into it
   and deleted. Invisibility (§5) landed here, as `dropOrdinal`.
   Multiple use (§3) needs no separate rewrite: because generation is
   unconditional when a step reads `ordinal` at all, every use is
   already a reference to the one generated field.

   Two notes from doing it. The context field had to go on `Resolver`,
   not `FromResolver`, so that it propagates into a nested query's
   scan expression and gives §4's rule for free. And `current` must be
   built from the environment *before* the field is added, or the
   materialized field would change the type of the row the user sees.

   Not behavior-preserving after all: it fixes problem 6 in §2. Four
   new cases in `relational.smli`; the rest of the suite passes
   unchanged, and no existing test prints a plan for a query using
   `ordinal`, so there was no `Sys.plan` output to regenerate.
2. **Validation.** *(Done, and superseded by phase 3.)* `OrdinalChecker`
   checks the rule of §3.
   It runs always, not only in test builds (open question 1 resolved):
   it is one visitor pass against several inlining passes, and it
   follows the `RefChecker` precedent. `Compiles` calls it both on the
   Core the resolver produced and on the Core that reaches the
   compiler, so it covers the passes that could break the rule.

   Nothing in the pipeline breaks it today, so the check earns its
   keep only in phases 3 and 4. Its own tests, in `FromBuilderTest`,
   matter more than usual for that reason: a checker that never fires
   is worthless, so there is a positive case, a case with several
   calls in one yield, and a negative case.

   It was worth landing even though phase 3 deletes it: it pinned the
   rule while the representation was still settling, and its
   nested-query test is what showed the attribution was wrong.
3. **New runtime, and the check moves into it.** *(Done.)* Move the counter into
   the yield `RowSink`; delete `ORDINAL_CODE`, `ordinalGet` /
   `ordinalInc`, `OrdinalGetCode` / `OrdinalIncCode`, `RowSinks.first`,
   `FirstRowSink` and `Describer.addStartAction`.

   The compiler tracks, per step, whether the step referenced the
   ordinal. After compiling a step: if it did and the step is not a
   `yield`, throw; if it is a `yield`, that is the flag saying the sink
   must maintain a counter, and a yield that did not reference it pays
   nothing.

   This subsumes phase 2, so `OrdinalChecker` is **deleted** here and
   its tests become tests of the compiler's error. Two reasons it is
   the better home. It gets §4's attribution right by construction --
   while compiling a nested query's scan expression inside a yield's
   row expression, the counter in scope simply *is* the yield's, which
   is the rule both hand-written checkers had to encode and one of them
   got backwards. And it cannot be bypassed: `Compiles` had to call the
   checker at two points, whereas every query reaches the compiler.

   What the compiler must *not* do is what it does today.
   `ORDINAL_CODE` is a `TryThreadLocal.withInitial(() -> new int[] {0})`,
   so a call that finds no counter is silently handed a fresh one. That
   default is why problem 6 in §2 went unnoticed. Absence of a counter
   has to be an error.

   In the event the counter did not have to move at all -- only the
   *reset* did. `RowSink.start` already runs once per execution, and
   `CollectRowSink` already used it to clear its list, so the yield and
   collect sinks take the counter and reset it there. The `int[]` stays
   a compile-time allocation, but is now only a channel between a sink
   and the codes it evaluates, written and read within one `accept`.
   A correlated subquery gets a fresh sink per outer row and so
   restarts; each yield step has its own counter, so an inner query
   cannot disturb an outer one.
4. **Let a reading `yield` hold the call** (§5), so the common
   `yield {ordinal, e.name}` costs no extra step.
5. **Re-enable subquery inlining** — *abandoned as specified.* Dropping
   the `containsOrdinal` guard in `FromBuilder.scan` miscompiles; see
   §8. Making inlining work would mean teaching it about the generated
   field: keep the subquery's drop-`yield` rather than skipping it, or
   re-materialize after the merge. That is a real piece of work on
   `FromBuilder`'s inlining, not a guard removal, and it is worth doing
   only if inlining a subquery that uses `ordinal` is worth having.
6. **Calcite** (optional, §7).

Phases 1–3 are the issue; 4–6 are the payoff and can land separately.

## 10. Testing

Existing coverage is in `relational.smli` (lines ~290, ~580–620),
`bag.smli`, `type-inference.smli` and `blog.smli`; it must pass
unchanged apart from `Sys.plan` output.

New cases to add:

* Ordinal in every step type that can read one: `where`, `order`, a
  `scan` condition, a `group` key, an aggregate argument.
* **Result types unchanged.** For each of those, a case whose result
  type would expose the generated field if invisibility (§5) were
  wrong — e.g. `from e in emps where ordinal < 2` must stay
  `{deptno:int, id:int, name:string} list`.
* **No field when not used.** A query with no `ordinal` must produce
  byte-identical `Sys.plan` output to today. Worth an explicit test,
  since this is the constraint the whole design is built around.
* **The two forms from §3**, whose current answers are the
  specification:

  ```sml
  from i in [10,20,30] yield {a = ordinal, b = ordinal};
  > val it = [{a=0,b=0},{a=1,b=1},{a=2,b=2}] : {a:int, b:int} list

  from i in [10,20,30] yield (if i > 15 then ordinal else ~1);
  > val it = [~1,1,2] : int list
  ```

  The first exercises the multiple-use rewrite; the second fails if
  the increment migrates to the call site.
* Ordinal in a subquery that is now inlined (phase 5), checking that
  the inner count still restarts per outer row.
* The nested-query scope rule of §4, in both directions: an `ordinal`
  in the nested query's scan expression counts outer rows; one in a
  later step of the nested query counts inner rows.
* Repeated execution of the same plan:

  ```sml
  fun f x = (from i in [10,20] yield i + ordinal);
  (f 1, f 2);
  from x in [1,2] yield (from i in [100,200] yield i + ordinal);
  ```

  These give `([10,21],[10,21])` and `[[100,201],[100,201]]` today,
  via the start-action reset; they must give the same answers with the
  reset deleted.
* Hygiene: a user field literally named `v$0` (if expressible) or a
  query with many generated fields, to confirm no collision.
* `Sys.plan` output showing a generating yield, as documentation of
  the new representation.
* Negative tests for the validation walk (§3): hand-built Core with
  two calls in one project, or a call outside a project, must be
  rejected. These guard the rules that the representation cannot
  enforce by construction. *(Done, in `FromBuilderTest`.)*

## 11. Open questions

Answered while implementing phases 1 and 2:

* *Where does the validation walk run?* Always — see phase 2.
* *Which mechanism for invisibility?* Neither of the two considered.
  A flag on `Binding` cannot work: a query's result type comes from
  the last step's environment, so marking a binding unprojectable
  would make the type disagree with the record the runtime builds.
  The field has to be projected away by a step, which is
  `FromBuilder.dropOrdinal`.
* *Does `ordinal` in a `take` or `skip` count expression mean
  anything?* It is already rejected — `'ordinal' is only valid in a
  query` — as is an `ordinal` in a query's first step. So the
  `toCore(Ast.Ordinal)` fallback that builds a bare call is
  unreachable, and phase 3 can delete it rather than preserve it.

Still open:

1. Should the lookahead reuse `TypeResolver`'s knowledge? It already
   knows, in `deduceOrdinalType`, which step an `ordinal` belongs to,
   and could record it for the resolver — but the AST is rewritten
   between the two passes, so the recorded node may not be the one the
   resolver visits, and the attribution differs in the nested case
   (§4). An independent lookahead in `FromResolver` is what phase 1
   did.
2. `current` has a similar "only valid in a query" flavor but is
   already a reference to the row. Is there anything to unify here, or
   are they independent?

## 12. Known defect: `ordinal` before the first row

On this branch, an `ordinal` in a nested query's `take` or `skip`
count, or in a `union`/`except`/`intersect` operand, crashes:

```sml
from x in [1,2] yield (from k in [10,20,30] take ordinal);
> java.lang.NullPointerException
from x in [1,2] yield (from k in [10,20] union [ordinal]);
> java.lang.NullPointerException
```

Before this work they returned answers -- `[[],[10]]` and
`[[10,20,0],[10,20,1]]` -- using the *enclosing* row's ordinal, which
is the right reading: those expressions are evaluated once per
execution of the nested query, hence once per row of the enclosing
step (§3a).

### The rule

An `ordinal` in a class-1 expression of a nested query is **valid, and
means the enclosing row's ordinal, provided the enclosing step is
ordered.** This is the reading of §3a taken literally, and it restores
the answers that existed before this work. The alternative -- reject
everywhere, making `ordinal` valid only where a row exists -- is
recorded in issue #436 and is not what we are doing; #436 stays open
for the abstract traversal it also proposes.

### One cause, three symptoms

All three come from attributing a class-1 expression of a nested query
to that query's own step, rather than to the enclosing one.

*Symptom 1 -- the crash.* The nested query's `acceptStep` asks
`usesOrdinal(take)`, gets yes, and calls `materializeOrdinal` on the
*nested* `FromBuilder`. `visit(Ast.Take)` then resolves the count with
`withEnv(env)`, which deliberately excludes that query's own bindings,
so the `Core.Id` for the freshly materialized field names something
that is not in scope, and evaluates to null:

```
at RowSinks$TakeRowSink.start(RowSinks.java:663)
at RowSinks$YieldRowSink.start(RowSinks.java:1384)
```

The `YieldRowSink` in that trace is the materialized-ordinal yield that
should never have been added.

*Symptom 2 -- the ordering check does not fire.*
`TypeResolver.deduceOrdinalType` reads `last(stepStack.rightList())`,
which during a class-1 expression is still the step being deduced. So

```sml
from x in bag [1,2] yield (from k in [10,20,30] take ordinal);
```

is not rejected as unordered; it crashes as symptom 1 instead.

*Symptom 3 -- the position that "works" is wrong about ordering.* A
nested query's step 0 gets a `Triple` built with `listTerm`
unconditionally, so the check in `deduceOrdinalType` always passes
there, whatever the enclosing step is:

```sml
from x in bag [1,2] yield {js = (from j in [x + ordinal])};
> val it = [{js=[1]},{js=[3]}] : {js:int list} bag
```

The enclosing collection is a bag, so those ordinals are meaningless.
Under the rule above this must be an error. It is a pre-existing bug,
not a regression.

### The fix

**`TypeResolver`: hide the current step while deducing a class-1
expression.** A query pushes one `stepStack` frame at a time, so hiding
"the frames of this query" is popping the top one for the duration of
the call (`withoutStep`). `deduceOrdinalType` then lands on the
enclosing step, which gives all three: the ordering check tests the
right collection, a top-level class-1 `ordinal` finds an empty stack
and is rejected by `checkInQuery` as it is today, and symptom 3
disappears because the `listTerm` frame is no longer the one consulted.

No traversal is needed here -- the call sites *are* the class-1 set.
There are eight of them, two more than §3a listed: besides a `take` or
`skip` count, the set-step operands and the first scan's extent, the
function of a `through` and of an `into` are applied to the whole
collection, hence evaluated once per execution of the query.

`through` was doubly wrong: it deduced its function in `p.env`, not
`p.rootEnv`, so `current` in it type-checked against a row that the
resolver had no way to supply, and crashed. It now matches `into`.

**`Resolver`: attribute the lookahead the same way.** `usesOrdinal` is
the mirror of those four sites and does need a traversal:

* A `Take`, `Skip`, `Union`, `Except` or `Intersect` step is entirely
  class 1, so `usesOrdinal` answers no for it whatever it contains: an
  `ordinal` there belongs to the enclosing query, which will find it
  through its own lookahead. (Step 0 is already skipped for the same
  reason.)
* `visitQuery`, which descends into a nested query, must visit *every*
  class-1 expression of it -- the first `Scan`'s extent, and every
  `take`/`skip` count and set-step operand at any position -- not just
  the extent.

**Scope: almost nothing more to do.** Once attribution is right, the
field is mostly reachable already. For a materializing step, the nested
`FromResolver` is created by that step's `withStepEnv`, whose env
contains the materialized binding, and `Resolver.this` inside it
carries the `ordinalPat`; `withEnv(env)` in `visit(Ast.Take)` resolves
to exactly that resolver. For a `yield` step, which holds the call
rather than materializing (phase 4), the nested `take` count compiles
with `cxFrom`, which inherits the yield's `ordinalSlots`.

Two exceptions:

* `Compiler.compileSetSink` compiles a non-distinct `except`/`intersect`
  operand in `handoff.cx`, which drops the counter. It now inherits
  `cxFrom`'s, as the distinct branch beside it already did.
* `Resolver.visit(Ast.Scan)` resolved the first step's extent with
  `withStepEnv`, whose `current` is this query's row -- and at step 0
  that row is the empty record. So `current` in a subquery's extent
  type-checked as the enclosing row and evaluated as `{}`
  (`ClassCastException`). It now resolves in the enclosing resolver,
  matching where the type resolver read it.

The edge that looked risky is not: a nested query that itself has a
`yield` reading `ordinal` installs its own slots, but `cxFrom` is fixed
before that happens, so a class-1 expression still reads the enclosing
counter. `from x in [1,2,3] yield (from k in [10,20,30] yield k +
ordinal take ordinal)` gives `[[],[10],[10,21]]` -- both counters live,
each read where it belongs.

### Tests

In `relational.smli`, interleaved into the existing "Current" and
"Ordinal" sections rather than kept apart, since they subsume several
ad-hoc tests that were already there. Uniform one-liners: the eight
root positions x {`current`, `ordinal`} x {top level, subquery of an
ordered step, subquery of an unordered step, unordered subquery of an
ordered step}, plus a subquery nested in a root position (invalid at
the top level, valid under a row). The unordered cases are the
regression guard for a check that had never fired.

### A cleaner mechanism, not taken

`current` needed no attribution work at all: it resolves through the
type environment, and the root sites already deduce in `p.rootEnv`,
which still holds the *enclosing* binding. That is the whole rule,
expressed by the environment. `ordinal` needs `withoutStep` only
because it resolves through `stepStack` instead, with the ordering
check deferred to a `validations` closure.

Binding `ordinal` in the environment alongside `Z_CURRENT` -- with a
sentinel type where it is invalid, one for "not in a query" and one for
"unordered step" -- would make both checks ordinary type errors and let
`withoutStep`, the `stepStack` lookup in `deduceOrdinalType`, and the
deferred validation all be deleted. The tests above pin the behavior
either way.
