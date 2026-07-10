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
# Row binders for `yield`, `yieldAll`, `group` — implementation plan

Implementation plan for "Syntax that allows `yield`, `yieldAll` and
`group` to produce a single variable" (issue #387). See also discussion
#384 (naming output rows) and the record type-inference issues #375 and
#139.

This document is the design of record for *semantics, sequencing,
testing, and syntax*. Where it disagrees with #387/#384, the concrete
test outputs in section 4 are authoritative — they were agreed
explicitly.

## 1. Feature

Add an optional **row binder** to the `yield`, `yieldAll`, and `group`
steps of a `from` expression, so a step can name its output row as a
single variable instead of scattering the row's fields into scope:

```sml
from e in emps
  yield r = {deptno = e.deptno, bonus = e.sal div 10}
  where r.bonus > 50
  yield r.deptno
```

Connectors (per #387): `=` for `yield` and `group`, `in` for `yieldAll`.

## 2. Semantics

The binder names the step's output row. The name behaves **exactly like
a scan variable** (`from r in …`): it is a scope-only label that does
**not** appear in the output type, it suppresses field-scattering, and
it combines with other bindings (e.g. a following `join`) into a record.

Precisely, after `yield r = e`:

* The row is the value of `e` (an *atom* — see section 3), named `r`.
* `r` and `current` both refer to the row; `r.f` and `current.f` work.
* Bare field names of `e` are **not** in scope.
* The output element type is the row's natural type, **unwrapped**
  (no `{r: …}` wrapper).

`group g = {keys} compute {aggs}` names the whole output row — the union
of the key fields and the computed fields — as `g`. `yieldAll r in e`
flattens `e` and names each resulting element `r`.

The binder therefore overrides the *implicit* label an atom yield
already assigns (`yield i` keeps `i`/`current` in scope without
scattering). Its one novel effect is forcing the **atom path for a
record literal**: `yield {a, b}` scatters `a` and `b`, whereas
`yield r = {a, b}` does not — it names the whole `{a, b}` row `r`.

## 3. The "atom" mechanism, and the `fieldVars` list

A `from` step produces a list of named fields (`fieldVars` in
`TypeResolver`). `fieldVar(fieldVars, atom)` turns that list into the
row variable:

* 0 fields → `unit`;
* 1 field, `atom` true → the field's value, **unwrapped** (an atom: a
  row that has a field name but is not a record);
* otherwise → a record of the fields.

Scans already use this: `from i in xs` contributes one field `i`, so the
row is `xs`'s element type unwrapped; `from i in xs join u in ys`
contributes `i` and `u`, so the row is `{i, u}`.

The binder plugs into the same mechanism: it contributes **exactly one
named field** (`r`/`g`) to `fieldVars`, in place of the scattered fields
or the `current`-only binding used today. Consequences:

* a binder step that is the sole binding → the row unwraps (atom);
* a binder step followed by `join u in …` → `fieldVars` holds two
  entries, so the row becomes `{r, u}` — the join test in section 4.

This is what makes a binder "act like `from r in …`".

## 4. Agreed test cases (authoritative)

```sml
from i in [{a=1,b=2}] yield r = i;
> val it = [{a=1,b=2}] : {a:int, b:int} list

from i in [{a=1,b=2}] yield r = i yield r.a;
> val it = [1] : int list

from i in [{a=1,b=2}] yield r = i where a > 0;
> ... Error: unbound variable or constructor: a

from i in [{a=1,b=2}] yield r = i where current.a > 0;
> val it = [{a=1,b=2}] : {a:int, b:int} list

from i in [{a=1,b=2}] yield r = i yield current.a;
> val it = [1] : int list

from i in [{a=1,b=2}] yield r = {c = i.a + i.b};
> val it = [{c=3}] : {c:int} list

from i in [1,2,3] group g = {x=i} compute {c = count over ()} order g.x;
> val it = [{c=1,x=1},{c=1,x=2},{c=1,x=3}] : {c:int, x:int} list

from i in [1,2,3] group g = {x=i} order g.x;
> val it = [{x=1},{x=2},{x=3}] : {x:int} list

from i in [1,2] yieldAll r in [i, i*10];
> val it = [1,10,2,20] : int list

from i in [1,2] yieldAll r in [i, i*10] join u in [3,4];
> val it =
>   [{r=1,u=3},{r=1,u=4},{r=10,u=3},{r=10,u=4},{r=2,u=3},{r=2,u=4},
>    {r=20,u=3},{r=20,u=4}] : {r:int, u:int} list
```

Note the last two: alone, `yieldAll r in …` unwraps to `int`; followed
by a join it forms `{r:int, u:int}`.

## 5. Interaction with `current`

`current` always refers to the step's output row (`Triple.of` binds
`Z_CURRENT` to the row variable). Because the binder makes `r`/`g` the
row, `current` equals the binder: `current.f` and `r.f` are
interchangeable, and bare `f` / `current`-of-a-scattered-field do not
exist. Inside the binder's right-hand side, `current` still refers to
the *input* row, so `yield r = current` names the whole input row.
`ordinal` is unaffected and is regression-tested alongside.

## 6. Grammar and the `=` disambiguation

`yield x = y` currently parses as `yield (x = y)` (an equality test);
under this feature it becomes a binder. This is a breaking change. Rules
to keep it predictable:

1. **Purely syntactic trigger.** A binder is recognized only when the
   clause begins with `IDENT =` (or `IDENT in` for `yieldAll`) — no scope
   or type lookup. `LOOKAHEAD(<IDENTIFIER> <EQ>)` /
   `LOOKAHEAD(<IDENTIFIER> <IN>)`.
2. **Parentheses always mean "expression", never a binder.** So equality
   is `yield (x = y)` and membership is `yieldAll (x in ys)`; migration
   of any broken code is mechanical.
3. **Uniform** across `yield`/`group` (`=`) and `yieldAll` (`in`).
4. **Transitional shadow warning** (optional): warn when a binder name
   shadows an in-scope binding — the tell-tale of an accidental binder.
5. **Sweep and migrate** existing `yield <id> =`, `group <id> =`,
   `yieldAll <id> in` in tests/docs/scripts; real breakage is expected to
   be tiny.
6. The `in` connector overlaps the proposed membership operator (#226);
   rule 2 keeps them compatible.

## 7. Touchpoints

* **Grammar** `MorelParser.jj` — `yield` (`:780`), `yieldAll` (`:784`),
  `group` (`:720`): add the optional lookahead binder.
* **AST** `Ast.java` — add nullable `Id binder` to `Yield` (`:3332`),
  `YieldAll` (`:3361`), `Group` (`:3484`); update constructors, `copy`,
  `unparse` (emit `yield r = …`), `Shuttle`/`Visitor`. Keep the binder in
  the AST for faithful unparse.
* **AstBuilder** `AstBuilder.java` — binder-aware `yield` (`:772`),
  `yieldAll`, `group` (`:729`) factories.
* **TypeResolver** — in each `deduce*StepType`, when a binder is present,
  contribute a single named field to `fieldVars` and skip scattering:
    * `deduceYieldStepType` (`:1696`): use the binder as the label; take
      the atom path even for a record literal (skip the scatter branch at
      `:1713`).
    * `deduceYieldAllStepType` (`:1753`): register `(binder, elem)` in
      `fieldVars` instead of the `current`-only binding (`:1776`).
    * `deduceGroupStepType` (`:1782`): bind the single field
      `(binder, v2)` where `v2` is the keys∪aggs row (`:1823`), instead
      of scattering.
* **Core / Resolver / FromBuilder** — likely no new step field; the
  binder is a resolution/scoping concern. Verify the `yieldAll`
  lowering to scan+yield preserves the name.
* **Docs** `docs/reference.md`, `docs/query.md`.

## 8. Relationship to #375 / #139 (tests only, no fix here)

The binder sidesteps **#375** (scattering fields from a non-literal
record): you never scatter — you write `r.f`, which only needs the row's
type, not its syntactic form. Accessing `r.f` still needs the row type
resolved to a concrete record; when it is an unresolved type variable
(e.g. an unannotated lambda parameter) that is **#139**
(`ClassCastException: TypeVar cannot be cast to RecordLikeType`). This
plan does not fix #139. It adds passing tests for cases where the record
type is known, and, optionally, converts the #139 crash into a clean
"cannot deduce record type" error (small, separable).

## 9. Deferred: pattern binders

For now the binder is a single identifier. Allowing a **pattern**
(`yield {a, b} = e`) probably makes sense but is deferred; noted here so
it is not forgotten.

## 10. Related bug

Unnamed `yieldAll` followed by a join currently throws
(`Conversion to core did not preserve type … {`op current`:int, u:int}
… {u:int, v$0:int}`), because the unnamed row is bound as `current`, not
a real field name. The binder fixes this for the named case. Whether the
*unnamed* `yieldAll` + join should become a clean error or also be made
to work is a separate follow-up.

## 11. Settled design decisions

* Output type is **unwrapped** (scope-only binder), not `{r: …}`.
* `current` equals the binder.
* Standalone `compute` does **not** accept a binder; `group` without
  `compute` does.
* `group` uses a single binder `g =` naming keys ∪ aggregates.
* Binder is an identifier; pattern binders deferred (section 9).
* The `=`-becomes-binder breaking change is accepted, mitigated by the
  rules in section 6.

## 12. Phasing

1. `yield` binder end-to-end (grammar → AST → resolver → tests) — proves
   the `current` interaction.
2. `yieldAll` binder (also fixes the join path; see section 10).
3. `group` binder (union wrap into `g`).
4. Optional: turn the #139 crash into a clean diagnostic.
5. Docs and the `yield <id> =` compatibility sweep.
