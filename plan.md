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
# Constrained types (#239) — plan

## Goal

Extend `type` declarations, types and expressions with a `check` clause, and
enforce the constraints so that **it is impossible to create a value of a
constrained type that breaches its constraints**.

That bar is the hard part. This document works out where a value can enter a
constrained type, what happens at each of those places, and which of them we
can close.

## Syntax

As in the issue, with one change (below):

> *typbind* ::= ⟨*var*⟩(`,`) *id* [`check` *match*] `=` *typ* ⟨`and` *typbind*⟩
>
> *exp* ::= ... | *exp* `check` *match*

### `check` matches need not be exhaustive

The issue originally required the *match* to be exhaustive. Instead, a
non-exhaustive match is allowed, and the compiler appends `| _ => false`. A
value that matches no branch fails the check.

```sml
(*) Valid. Equivalent to the three-branch form: (true, 1) fails the check.
type badPair = (bool * int) check
    (true, 0) => true
  | (false, i) => i mod 2 = 0;
```

Consequences:

* `PatternCoverageChecker` must not report a `check` match as non-exhaustive.
  It should still report a *redundant* branch, which is a real mistake.
* Appending `| _ => false` is a Core-level rewrite, so the appended branch
  needs a source position for error messages; use the position of the whole
  match.
* A match of one branch whose pattern is irrefutable (`i => i >= 0`, the
  common case) is unchanged: no branch is appended.

## Where a value can breach a constraint

`type` in Morel is a **transparent alias** (`AliasType`: "an alias is
transparent ... not a distinct type"). So `type nat = int check i => i >= 0`
makes `nat` and `int` the same type to the unifier, and every `int` is
structurally a `nat`. There is no single construction site to guard; we have
to find every point where a value flows into a position whose static type is
constrained.

Errors below use Morel's existing format, `uncaught exception Name [message]`,
as in `uncaught exception Subscript [subscript out of bounds]`.

### A. Direct binding sites

Syntactic, and easy.

```sml
type nat = int check i => i >= 0;

val n: nat = ~1;
> uncaught exception Constraint [~1 is not a valid nat]

fun f (n: nat) = n;
f ~1;
> uncaught exception Constraint [~1 is not a valid nat: argument of f]

fun g (): nat = ~1;
g ();
> uncaught exception Constraint [~1 is not a valid nat: result of g]
```

### B. Construction of composite values

Also syntactic. The check applies as the composite is built.

```sml
type employee = {empno: nat, name: string};
val fred: employee = {empno = ~10, name = "Fred"};
> uncaught exception Constraint [~10 is not a valid nat: field empno]

val ns: nat list = [1, ~2, 3];
> uncaught exception Constraint [~2 is not a valid nat: element 1]

datatype box = Box of nat;
Box ~1;
> uncaught exception Constraint [~1 is not a valid nat: argument of Box]
```

Record modifiers (#432) are a second construction site, easily missed:

```sml
val e: employee = {empno = 10, name = "Fred"};
e replace empno = ~1;
> uncaught exception Constraint [~1 is not a valid nat: field empno]
```

Compound constraints need an order and a path. Check components before the
whole, so the message names the innermost failure:

```sml
type evenPair = (nat * nat) check (i, j) => i * j mod 2 = 0;
(~1, 2): evenPair;
> uncaught exception Constraint [~1 is not a valid nat: component 0]
(1, 3): evenPair;
> uncaught exception Constraint [(1,3) is not a valid evenPair]
```

### C. Flow through polymorphic functions

Here transparency bites. Neither `id` nor `List.map` mentions `nat`; the only
place to check is the binding.

```sml
fun id x = x;
val n: nat = id ~1;
> uncaught exception Constraint [~1 is not a valid nat]

val ns: nat list = List.map (fn i => i - 1) [0, 1, 2];
> uncaught exception Constraint [~1 is not a valid nat: element 0]
```

Checking at the binding means walking the whole list there, every time such a
value crosses such a boundary.

### D. Function values

```sml
val g: nat -> nat = fn i => i - 1;
g 0;
```

Nothing is wrong at the binding: `fn i => i - 1` *is* an `int -> int`. The
breach happens later, at the call, and the blame differs:

```sml
g 0;
> uncaught exception Constraint [~1 is not a valid nat: result of g]
g ~1;
> uncaught exception Constraint [~1 is not a valid nat: argument of g]
```

### E. Values from outside

Foreign rows and parsed values never pass through a Morel constructor.

```sml
val emps: employee bag = scott.emps;
Variant.parse "..." : nat;
```

Checking them on entry is expensive and forces a streamed bag.

### F. Generated values

From the issue's comment: a constrained type used as a scan source must
*generate* only conforming values, not generate-and-filter.

```sml
type parity_pair = {i: int, j: int} check {i, j} => i mod 2 = j mod 2;
from p: parity_pair where p.i elem [0..2] andalso p.j elem [5..8];
```

`Extents` already deduces "the set of values a variable can take", so this is
where it plugs in — but it means a constraint must be *readable* by the
planner, not merely callable.

### G. The predicate itself

```sml
(*) The predicate raises.
type odd = int check i => 100 div i mod 2 = 1;
val x: odd = 0;
> uncaught exception Div [divide by zero]
```

The issue also specifies that a predicate captures the values it uses at
declaration time, so redefining `limit` or `lessThanDozen` afterwards does not
change the type. The closure must therefore be snapshotted into the type,
which affects type equality, printing and serialization.

## C and D are one problem, and it is #290

C and D look different but reduce to the same question: **what happens when a
constrained type meets a type variable?**

* In C, `List.map` is compiled once, at type `('a -> 'b) -> 'a list -> 'b
  list`. When `'b` is instantiated to `nat`, the compiled code has no idea it
  should be checking anything.
* In D, `g`'s calls are only checkable where `g`'s type is statically known.
  Pass `g` to something polymorphic and the knowledge is gone.

This is exactly #290. There, a polymorphic function needs a *comparator* for a
type variable, and "the comparator is generated at compile time, but the full
type is not available until the function is applied". Here a polymorphic
function needs a *checker* for a type variable. `Range.contains`,
`Range.normalize`, `Range.toList` and `Range.toBag` need the same thing.

Morel's current mechanism, `Codes.Typed.withType(typeSystem, type)`,
specializes a builtin once the concrete type is known at compile time. That is
enough for a monomorphic call site and not enough for a polymorphic one —
which is precisely why #290 is still open.

So: **do not invent a constraint-dispatch mechanism for #239.** Whatever
solves #290 should carry the checker too.

### Options

| | Approach | Reuse of polymorphic functions | Soundness | Cost |
|---|---|---|---|---|
| 1 | Check at annotation boundaries only | Good — polymorphic code is untouched and uninstrumented | A, B closed; C partly (deep walk); D, E, F open | Deep traversal per boundary, repeated |
| 2 | Type-directed dispatch (dictionary passing), shared with #290 | Good — one compiled copy serves every instantiation | A–D closable | Large compiler change; hidden parameters |
| 3 | Nominal (opaque) refinement | Excellent — checked once at construction, never re-walked | Strongest | Contradicts the issue's implicit `int` → `nat` examples; explicit coercions everywhere |
| 4 | Static proof (#242 `prove`), runtime check only where unproven | Good | An optimization on 1 or 2, not a design | SMT-shaped work |
| 5 | Constraints never cross a type variable; document the hole | Perfect — nothing changes | A, B only | Trivial |

Option 3 deserves a note, because it is the only one that makes the bar
("impossible to create a value in breach") achievable cheaply: if `nat` is
distinct from `int`, a `nat list` is checked once when built and never again,
and `List.map f` over it needs only `f`'s own boundary checked. The cost is
that `val j: int check k => k >= 10 = i + 10` — an example in the issue —
requires an implicit checked coercion, so we would be re-introducing option 1
at the coercion points anyway. A middle road is *nominal with implicit checked
coercion*: nominal enough to hang a checker on and to avoid re-walking, with
the compiler inserting the coercion where the issue's syntax expects one.

### Recommendation

Land 1, scoped by 5, and design for 2:

* Phase 1–3 (below) implement option 1 for the syntactic cases A and B, which
  is where nearly all the value is.
* A constrained type appearing in a *polymorphic* position (C, D) is a
  compile-time error at first — option 5 — rather than a silent hole. That
  keeps the "impossible to breach" promise honest for the fragment we support.
* When #290 lands a dispatch mechanism, relax the restriction and route the
  checker through it.

That ordering means we never ship an unsound-but-quiet feature, and we do not
build a second dispatch mechanism that #290 will later replace.

## Phases

1. **Syntax.** `check` keyword; `typbind` and `exp` productions; `Ast` and
   `Core` nodes; append `| _ => false`; suppress the non-exhaustive error and
   keep the redundant-branch one. No enforcement yet.
2. **Runtime.** `Constraint` added to `BuiltInExn`; check at direct bindings,
   parameters and returns (A).
3. **Composites.** Construction of records, tuples, lists, datatype
   constructors and record modifiers (B); component-before-whole ordering and
   the blame path in messages.
4. **Restriction.** Reject a constrained type in a polymorphic position, with
   a message pointing at this limitation.
5. **Dispatch.** Once #290 has a mechanism, carry checkers through it and
   lift the phase-4 restriction (C, D).
6. **Planner.** Teach `Extents` to read constraints so a constrained type can
   be scanned (F). Overlaps #240 and #241.

## Open questions

1. **Message format.** The issue uses both a bare `uncaught exception
   Constraint` and a descriptive `Invalid value '~10' for type 'nat' when
   assigning to field 'empno'`. This plan assumes `uncaught exception
   Constraint [<value> is not a valid <type>: <path>]`. Confirm?
2. **Anonymous constrained types.** `val j: int check k => k >= 10 = ...` has
   no type name to put in the message. Use the source position?
3. **Predicate that raises.** Propagate the underlying exception (as above),
   or wrap it as `Constraint`?
4. **Cost of deep checks.** Is a per-boundary traversal of a large bag
   acceptable, or should a checked value carry a "already checked" mark?
5. **Foreign data (E).** Check on entry, or declare the boundary untrusted?
