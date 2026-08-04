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

## 3. Proposed design

While translating a query from AST to Core, whenever a step's
expressions reference `ordinal`, materialize the ordinal into a
generated field of the row and rewrite the references to read that
field.

```
from e in emps
  yield {ordinal, e.name}
```

becomes, in Core (schematically):

```
from e in emps
  yield {e = e, o$0 = $ordinal ()}
  yield {ordinal = o$0, name = e.name}
```

with the optimization of §5 collapsing the two yields into one where
the step is itself a `yield`.

The field is *invisible*: it is a generated `Core.IdPat` whose name
cannot collide with a user name, it never appears in the query's
result type, and it is dropped once its last reader has been passed.

After this change, `$ordinal ()` appears in Core in exactly one
position — as the value of a generated field in a `yield` record — and
`Z_ORDINAL` is compiled in exactly one place.

## 4. Where the field is generated

In `Resolver.FromResolver` / `FromBuilder`, at the point a step is
added. `FromBuilder` already has `containsOrdinal(Core.Exp)` and the
`Shuttle` that rewrites `$ordinal` to an id; both are generalized from
`groupOverOrdinal` into a shared helper, roughly:

```java
/** If any of {@code exps} reads 'ordinal', prefixes a 'yield' that
 * materializes the ordinal as a field, and returns a shuttle that
 * rewrites '$ordinal ()' to a reference to that field; otherwise
 * returns null. */
private @Nullable Shuttle materializeOrdinal(Iterable<Core.Exp> exps)
```

and is applied by `scan` (the condition), `where`, `yield_`, `order`,
`group`, `skip` and `take`.

Naming uses `typeSystem.nameGenerator`, as `groupOverOrdinal` already
does, so the field is `o$0`, `o$1`, … and cannot collide.

## 5. Avoiding the extra step

Materializing always costs an extra `yield`. Two cases avoid it:

* **The step is a `yield` whose expression is a record.** Add the
  generated field to that record directly and rewrite the other
  fields to read it. Because a record's fields are all evaluated once
  per input row, and the generated field's value does not depend on
  the others, this is safe regardless of field order.
* **The step is a `group` and only the *keys* read `ordinal`.** Group
  keys are evaluated once per input row, so the field may be added as
  an extra key — but it must then be removed from the group's output
  before downstream steps see it. Simpler, and the initial
  implementation: fall back to the prefix `yield` (which is what
  `groupOverOrdinal` does today).

Everything else — `where`, `order`, `take`, `skip`, `scan`
conditions, aggregate arguments — takes the prefix `yield`.

A later projection-pruning pass (§9, phase 5) can remove a generated
field after its last reader, so the field does not travel further down
the pipeline than it needs to.

## 6. Runtime: the sequence generator

`$ordinal ()` no longer compiles to a read of a shared box. It
compiles to a *sequence generator*: a counter owned by the `RowSink`
that evaluates the yield.

This is where the design pays off. `RowSinks.from` takes a
`Supplier<RowSink>` and `FromCode.eval` calls `rowSinkFactory.get()`
on *every* execution, so a counter field in a `RowSink` instance is
automatically per-execution, per-nesting-level, and per-thread. No
box, no thread-local, no reset.

Concretely:

* `Codes.ordinalGet` / `Codes.ordinalInc` and `OrdinalGetCode` /
  `OrdinalIncCode` are deleted.
* `Codes.yield_` (and the corresponding `RowSink`) gains a variant
  that computes one designated field from an `int` counter it
  increments per accepted row.
* `Compiler.compileRow`, `compileRowMap` and `compileGroupSink` lose
  their `ORDINAL_CODE` blocks; `ORDINAL_CODE` is deleted.
* `Describer.addStartAction`, `RowSinks.first` and `FirstRowSink` are
  deleted, along with the `CodeVisitor` walk they require — they exist
  only to reset the ordinal.

`BuiltIn.Z_ORDINAL` remains, as the Core-level marker that a yield
field is the sequence generator.

## 7. Calcite

Per the issue, the `Project` relational operator gets a
sequence-generator operator. Once the ordinal is a projected field,
`CalciteCompiler.yield_` can translate it: today it has no case for
`Z_ORDINAL` and a query using `ordinal` cannot be pushed down.

Options, in increasing order of work: leave the Calcite path alone
(the field is a `RexNode` the compiler declines to translate, so the
query falls back to the interpreter — the status quo); or emit
`RexWindow`-based `ROW_NUMBER() OVER ()`; or add a Morel-specific
`RexNode` and a rule. Deferred to phase 6 and treated as optional.

## 8. Optimizations enabled and affected

* **Enabled.** `FromBuilder.scan` can drop the `!containsOrdinal(exp)`
  guard. Once the subquery's ordinal is a materialized field, merging
  its scan into the enclosing loop is safe, because the field carries
  the inner count.
* **Affected.** The inserted `yield` perturbs anything that pattern
  matches on step shape: `isSimplePat`, `safeToInline`, and the step
  counting in `FromBuilder`. These need to see through a generated
  yield, or the yield needs to be marked so they can.
* **Affected.** `Inliner` and any pass that duplicates an expression
  must not duplicate a reference to a generated field into a different
  row scope. Since the field is an ordinary `IdPat` reference, the
  existing scoping rules cover this; the value of the change is that
  they now *apply*, where `$ordinal ()` was opaque.

## 9. Phases

1. **Generalize `groupOverOrdinal`.** Extract `materializeOrdinal`,
   apply it to every step type in `FromBuilder`, keeping the existing
   runtime. At the end of this phase `$ordinal ()` in Core appears
   only inside a generated `yield` field, and all existing tests pass
   unchanged. `Sys.plan` output changes; expected outputs in
   `relational.smli` and `bag.smli` are regenerated.
2. **Collapse the redundant yield** for the record-yield case (§5).
3. **New runtime.** Add the sequence-generator `RowSink`; compile the
   generated field to it; delete `ordinalGet`/`ordinalInc`,
   `ORDINAL_CODE`, `RowSinks.first`, `FirstRowSink` and
   `Describer.addStartAction`.
4. **Re-enable subquery inlining** — drop the `containsOrdinal` guard
   in `FromBuilder.scan`, and fix whatever step-shape matching the
   generated yield disturbs.
5. **Prune generated fields** after their last reader.
6. **Calcite** (optional, §7).

Phases 1–3 are the issue; 4–6 are the payoff and can land separately.

## 10. Testing

Existing coverage is in `relational.smli` (lines ~290, ~580–620),
`bag.smli`, `type-inference.smli` and `blog.smli`; it must pass
unchanged apart from `Sys.plan` output.

New cases to add:

* Ordinal in every step type: `where`, `order`, `take`, `skip`, a
  `scan` condition, a `group` key, an aggregate argument.
* Ordinal in a subquery that is now inlined (phase 4), checking that
  the inner count still restarts per outer row.
* Repeated execution of the same plan:

  ```sml
  fun f x = (from i in [10,20] yield i + ordinal);
  (f 1, f 2);
  from x in [1,2] yield (from i in [100,200] yield i + ordinal);
  ```

  These give `([10,21],[10,21])` and `[[100,201],[100,201]]` today,
  via the start-action reset; they must give the same answers with the
  reset deleted.
* Hygiene: a user field literally named `o$0` (if expressible) or a
  query with many generated fields, to confirm no collision.
* `Sys.plan` output showing the generated field, as documentation of
  the new representation.

## 11. Open questions

1. Should the generated field be visible in `Sys.plan` output under a
   distinguishing name, or suppressed? Visible is more debuggable and
   is assumed above.
2. Should phase 5 (pruning) be required before phase 3 lands, to avoid
   a measurable regression from carrying an extra `int` per row in
   deep pipelines? Assumed not — the cost is one field in one
   record — but worth a benchmark on a query with several steps
   downstream of an `ordinal`.
3. `current` has a similar "only valid in a query" flavor but is
   already a reference to the row. Is there anything to unify here, or
   are they independent?
