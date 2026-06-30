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
# Measures — implementation plan

Implementation plan for "Measures and context-dependent calculations"
(issue #401). A **measure** is an expression evaluated in a dimensional
**context**; typically it belongs to a **table** and aggregates the table's
rows that the context admits.

This document is the implementation plan and is intentionally separate from
the design narrative in #401. Where the two disagree, #401 is the design of
record for *semantics*; this file is the design of record for *sequencing,
testing, and the public API surface*.

## 1. Public surface: one `Table` structure

All three types and their operations live in a single structure `Table`,
with a single signature file `lib/table.sig`. The three types are
**pervasive** (registered in the type system like `bag`/`list`/`option`, so
you write `('p,'e,'a,'r) measure`, never `Table.measure`); the structure
carries only `val`s, so there is no cross-type signature recursion and the
member names must be unique within the one structure.

Measure type parameters are ordered `('p, 'e, 'a, 'r)` — param, element,
argument, result — following the data flow: the table's param arrives before
the row, which arrives before the measure argument, which produces the
result. The argument `'a` is usually `unit`.

```sml
signature TABLE = sig
  type ('p, 'e, 'a, 'r) measure
  type ('p, 'e) context
  type ('p, 'e) table

  (* measure constructors and evaluation *)
  val measure      : (('p, 'e) context -> 'r)      -> ('p, 'e, unit, 'r) measure
  val measure_fn   : ('a * ('p, 'e) context -> 'r) -> ('p, 'e, 'a, 'r) measure
  val evaluate     : ('p, 'e, 'a, 'r) measure * 'a * ('p, 'e) context -> 'r
  val eval         : ('p, 'e, 'a, 'r) measure * 'a -> 'r

  (* context modifiers — methods on measure *)
  val restrict      : ('p,'e,'a,'r) measure * string * ('e -> bool) -> ('p,'e,'a,'r) measure
  val restrict_anon : ('p,'e,'a,'r) measure *          ('e -> bool) -> ('p,'e,'a,'r) measure
  val relax         : ('p,'e,'a,'r) measure * string                -> ('p,'e,'a,'r) measure
  val override      : ('p,'e,'a,'r) measure * ('e -> 'b) * 'b       -> ('p,'e,'a,'r) measure

  (* context accessors *)
  val test     : ('p, 'e) context * 'e -> bool
  val paramOf  : ('p, 'e) context -> 'p
  val toString : ('p, 'e) context -> string

  (* table constructor and accessors *)
  val table    : 'e bag * 'p -> ('p, 'e) table
  val elements : ('p, 'e) table -> 'e bag
  val param    : ('p, 'e) table -> 'p
end
```

Naming notes:
- The `param`/`elements` reuse between table and context is dissolved by
  giving the **table** the plain names (`param`, `elements`) — so
  `orders.param.sum_units` and the disjoint-field sugar read naturally — and
  the **context** the distinct `paramOf` (read in measure bodies as
  `#growth (paramOf ctx)`).
- `toString` stays `toString`: unique in the structure, and its argument type
  (`context`) disambiguates it. The hot-path method form `context.toString ()`
  reads correctly.
- `val table` and `type table` coexist (values and types are separate
  namespaces): `Table.table (rows, param)` constructs, `('p,'e) table` is the
  type.
- `eval` carries the argument and the implicit context: `eval (m, a)` and
  `m.eval a` desugar to `evaluate (m, a, context)`; the common unit case is
  `m.eval ()`.

Method-eligible (`[@@method]`, postfix-callable): the measure ops whose first
argument is a measure (`evaluate`, `eval`, `restrict`, `restrict_anon`,
`relax`, `override`), the context ops (`test`, `paramOf`, `toString`), and the
table accessors (`elements`, `param`). Constructors (`table`, `measure`,
`measure_fn`) are plain.

### Deliberately deferred (not in the first signature)

These were considered and pushed out to keep the initial surface small; bring
them back when their phase arrives.

- **Measure combinators** `count`, `sum`, `avg`, `min`, `max` — concise
  aggregate measures for tests. They require the context to expose its base
  rows, so they come back together with:
- **`rows : ('p,'e) context -> 'e bag`** (and the design refinement that the
  context *carries* its base elements). Until then, a measure closes over its
  base bag lexically, as the worked examples do
  (`measure (fn ctx => from r in rows where test (ctx, r) compute …)`).
- **`describe : ('p,'e,unit,string) measure`** — a canned measure that renders
  its own evaluation context (`measure (fn ctx => toString ctx)`); convenience
  over the `toString` primitive.
- **`of : 'e list -> (unit,'e) table`** — paramless one-liner table.
- **Table-definition sugar** and **`orders.sum_units`** disjoint-field sugar
  (#401 future work).

## 2. Key technical problems and the components each adds

A measure is a boxed function `(arg, context) → result`; a context is
`{test, param}` (plus, later, its base elements). So most of the difficulty is
not "what is a measure" but **building the right context at each query step**
and **optimizing it to SQL**. That seam drives the phasing.

- **P1 — Measure/context/table as first-class typed values.** Opaque boxed
  values. *Components:* three type constructors in `BuiltIn.Eqtype` with
  `varCount` 4/2/2 (model on `BAG("bag",1)`, `BuiltIn.java:5630`);
  `ForallHelper` support for the multi-parameter `measure` type
  (`TypeSystem.java:405-455, 738-759`); the `Table` structure
  (`BY_STRUCTURE`, `BuiltIn.java:4977`); `Applicable` impls + `BUILT_IN_VALUES`
  entries (`Codes.java:6100`); runtime value classes boxed like `Session`/`doc`.
  Risk: low.

- **P2 — The context runtime and its modifier algebra.**
  `restrict`/`restrict_anon`/`relax`/`override` produce new contexts, so a
  context is not a bare predicate but a structure of *labeled* constraints over
  columns plus the param, supporting add/remove/replace. *Components:* a
  `Context` runtime class; the four modifier `Applicable`s; label resolution.
  Risk: medium, self-contained.

- **P3 — Context propagation (the hard core).** At each pipeline step, build
  the base-row context handed to measures (the base-expression algebra:
  scan → match-all; `where p` → conjoin; `group {k=e}` → add
  `base-expr(e)=key`; `yield` → re-key; `join` → union). *Components:* a
  per-step base-accessor carried alongside the existing `current` threading
  (`Resolver.withStepEnv`/`withCurrent`; the `Triple`/`stepStack` in
  `TypeResolver`), compiled into context-building code that wraps
  `Codes.aggregate` (`Codes.java:5991`) / `GroupRowSink.result`
  (`RowSinks.java:1215`). Decomposes: the case *current-row = base-row* (no
  `yield`/`join` before the measure use) needs none of the algebra — just the
  group keys compiled over the scan variable. Risk: highest, but stageable.

- **P4 — The `context` keyword.** Position-dependent, exactly like
  `current`/`ordinal`. *Components (turnkey template — all 10 touch-points
  traced):* token in `MorelParser.jj`; `Ast.Context` + `Op.CONTEXT`;
  `Z_CONTEXT` internal binding (cf. `Z_CURRENT`, `BuiltIn.java:4866`); a
  `TypeResolver` case via `checkInQuery` reading the per-step element from
  `stepStack`; `Resolver.toCore` returning the threaded context `Core.Exp`.
  Risk: low.

- **P5 — Recursive nominal param type & table construction.** Measures live in
  `param`; `param`'s type is recursive through the measures
  (`('p,'e,_,_) measure`). Only nominal types express this (verified
  empirically). *Components:* confirm the type system admits it; the printed
  abbreviation in `Pretty.java`; eventually table-definition sugar. Risk:
  medium-high — gate with a spike.

- **P6 — Dual execution.** Interpreted nested-loop path first
  (`Compiler.compileFrom`, `Compiler.java:760`); Calcite pushdown by static
  context-unwinding later (`CalciteCompiler.compileFrom`,
  `CalciteCompiler.java:522`, with `toRel` fallback). The two must agree. Risk:
  medium, fully deferrable — the interpreted path is the oracle.

## 3. Orthogonal concerns → the test matrix

**Cheap axes — sweep once, assume they compose.** Result type
(scalar / collection / record / measure-valued), argument type (`unit` /
non-unit), reads-param-or-not. One representative test each, plus a single
"exotic result type under a modifier" smoke test to confirm the boxed-closure
path is type-agnostic.

**Hard axes — the real combinatorial grid** is the product of:

1. **Context location:** `where` · single-key `group` · multi-key `group` ·
   `yield`-rekey · `join` · ungrouped.
2. **Modifier *sequences*** (not just single modifiers): because the block
   applies left-to-right and override/relax/restrict form a label algebra,
   *order matters* — `override x` → `relax x` → `override x`,
   `restrict q` → `relax q` → `restrict q`, and interleavings across distinct
   labels.
3. **Backend:** interpreted · Calcite, every cell asserted equal across the
   two.

So the grid is **(location × modifier-sequence × backend)** with the cheap
axes as a thin orthogonal pass.

**Functional-dependency interactions are pulled out of the grid into their own
phase** (see §4): `override year` then `relax month` (and the reverse), and
`override (sub(x,0))` then `relax x`. Whether `relax` is **narrow** (remove
only the exactly-named label) or **wide** (also remove constraints on
expressions functionally dependent on the relaxed label) is the issue's
undecided question. Narrow is pure label-matching and stays in the grid; wide
needs a constraint/FD engine and is deferred.

## 4. Phases — each fully runnable and testable

Critical path: **Spike K → Phase 1 → Phase 2** gets a real demoable feature
(grouped measures) fastest; **Spike R** runs in parallel and gates Phase 4.

**Spike K (before Phase 2).** Confirm the `current`/`ordinal` threading
carries a context value with no new mechanism. The probe strongly implies yes;
cheap to verify.

**Spike R (before Phase 4).** Prove the recursive nominal param type
round-trips through `TypeSystem`/`Pretty` as #401's examples claim. We have
empirical evidence; confirm in the real type system, since the param-reading
story and the printed types depend on it.

**Phase 1 — Measure values + explicit evaluation.** The `Table` structure and
its three types, `measure`/`measure_fn`/`evaluate`/`eval`, the modifiers as
no-op-context methods, `table`/`elements`/`param`, `test`/`paramOf`, and
`toString` (empty + equality forms). A primitive base-context builder
(match-all context from a table). *Runnable:* define measures, evaluate them
against the whole table → grand totals; `.smli` checks abbreviated type
printing. Interpreted only; param simple (no recursion).

**Phase 2 — `context` keyword + propagation for scan/where/group
(current-row = base-row).** The headline:
`from e in orders group {e.prod_color} compute {e.sum_units.eval()}`. No
yield-before-measure, no joins, single table. Core of P3's tractable case plus
all of P4. `toString` reaches group-key and `where` (`?`) items; the oracle
goes live across the propagation grid.

**Phase 3 — Context modifiers (narrow only).** The four methods + `at {}`
sugar + label derivation + **modifier-sequence** testing. `relax` = exact
label match; no FD reasoning. *Runnable:* percent-of-total,
what-if-by-override, and override→relax→override sequences. `toString` reaches
labeled-filter (`label: ?`) items and the collapse/replace normalization.

**Phase 4 — Param recursion.** Measures reading siblings/globals
(`forecast`/`uplift`, the what-if example); the nominal recursive param type.
*Runnable:* the full sensitivity table.

**Phase 5 — Full propagation.** `yield` re-keying, joins, multi-range-variable
labels, `distinct` — the base-expression algebra in general, plus the totality
argument. *Runnable:* `override bonus=20` where `bonus = units*2`; joined-table
contexts.

**Phase 6 — FD-aware contexts.** The wide `relax`,
override-of-derived-expressions, and the `year`/`month` and `sub(x,0)`/`x`
interactions. Its core is a shared **constraint engine** that knows declared
function properties (monotonicity, functional dependence) — the *same* engine
the period-over-period "gleaned year" approach needs, so wide-relax and
predicate-gleaning PoP are one component. Interpreted first.

**Phase 7 — Calcite pushdown.** Static context-unwinding to relational
algebra; re-run the entire Phase 2–6 grid under Calcite, asserting equality
with interpreted. No new surface area.

**Phase 8 — Ergonomics.** The deferred items from §1: combinators + context
`rows`, `describe`, `of`, table-definition sugar, disjoint-field sugar,
`evalAt`. Each independently shippable.

Period-over-period (see `discussion.md`) rides on top as a *library pattern*
over Phases 3–4 (offset as argument or param), not a new phase; add it as
worked examples once Phase 4 lands.

## 5. Specification work, and when

Three kinds, staggered.

- **Phase 1 — the `lib/table.sig` file** is binding spec: per the repo's lint
  rules, `LintTest` forces full impl + docs the moment a `.sig` exists, so it
  lands with the code. Settle here: `table` is an **abstract type** with
  `table`/`elements`/`param`, not a thin record wrapper — it keeps the
  disjoint-field sugar and printing under our control.

- **Phase 2 (write at the start) — the context-propagation semantics**: the
  base-expression rules per step, hardened from #401's sketch into a reference
  doc with a worked derivation, plus a short **laws** doc
  (`evaluate(m, wholeContext) = grand total`; `override x` then `relax x` =
  identity; measure-over-base is grain-independent). The laws become
  property/differential tests.

- **Phase 3 (decide before coding)** — lock in **narrow** `relax` as the spec,
  and the `at {}` clause grammar.

- **Phase 5 (extend)** — propagation spec grows to cover `yield`/`join` and
  states the totality argument.

- **Phase 6 (write at the start)** — the **wide/FD** `relax` semantics plus the
  declared-property registry (which functions are monotone, which determine
  which), authored jointly with the PoP "approach 5" inference rules in
  `discussion.md`, since they consume the same facts.

## 6. `Table.toString` — the portable context-rendering spec

`Table.toString : ('p,'e) context -> string` is the **primary test oracle**:
for every propagation and modifier-sequence cell, assert the *rendered
context*, pinning what context was built rather than only that the right number
came out. The same `.smli` files run under morel-rust must produce identical
strings, so the output is specified with **no implementation detail** — it is a
function only of the labels in force, their values, and whether each is an
equality or an opaque filter. No predicate body, closure, object identity,
internal node name, or insertion order ever appears.

**Grammar.**

```
render(ctx) = "{" ^ join(", ", sort(items)) ^ "}"      ("{}" when empty)
```

**Items** — one per active constraint, after all modifiers are applied
(relaxed labels removed; a later `override` replaces any earlier equality on
the same label):

| constraint kind | source | item string |
|---|---|---|
| equality | group key, `override`, bare `proj = val` modifier | `label ^ " = " ^ printValue(value)` |
| labeled filter (opaque) | `restrict`, `at {label: pred}` | `label ^ ": ?"` |
| anonymous filter (opaque) | `restrict_anon`, lifted `where` | `"?"` |

**Canonicalization rules** (portability requirements, not hints):

1. **Value rendering** = Morel's standard value printer (REPL `val it = …`
   form). Override/key RHS are always *values* (overrides evaluate their
   argument before entering the context), so there is nothing to fold and no
   backend divergence.
2. **Label canonical form** = the dotted base-expression path, **range
   variable included**, never the `#field x` form, no spaces: `e.prod_color`,
   `s.region`, `p.id`. Param overrides use the bare field name: `growth`. The
   label-derivation algorithm is the language spec (the same one `relax` uses),
   so it is portable. **Labels are always range-variable-qualified, even in the
   single-table case** — the only form that stays correct under joins.
3. **One equality per label.** `override` replaces a prior key/equality on the
   same label; a relaxed label is absent.
4. **Anonymous filters collapse to a single `?`** regardless of count or
   internal structure — implementations that merge `where` predicates and those
   that don't still agree.
5. **Predicate bodies are never rendered.** Constant-folding, reordering, and
   simplification happen inside opaque predicates, so they are invisible to the
   string — this is what guarantees interpreted output == Calcite output.
6. **Items sorted by rendered text**, ascending by Unicode code point — no
   dependence on insertion order or hashing.

**Examples:**

```
base / match-all                              {}
group {e.prod_color}, Red group               {e.prod_color = "Red"}
group {e.prod_color, e.region}                {e.prod_color = "Red", e.region = "East"}
m at {s.region = "East", quality: isFancy p}  {quality: ?, s.region = "East"}
override growth = 0.15                         {growth = 0.15}
override x=1; relax x; override x=2            {x = 2}
where (p.units > 3)                            {?}
```

`toString` lands in Phase 1 (empty + equality forms), gains `?` items in
Phase 2, gains labeled filters and collapse/replace in Phase 3, needs no format
change for the FD phase (label appearance/disappearance under wide-relax is
already expressible), and is the differential oracle in Phase 7.
