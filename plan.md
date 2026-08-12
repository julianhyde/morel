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

Extend `type` declarations and types with a `check` clause, and enforce the
constraints so that **it is impossible to create a value of a constrained type
that breaches its constraints**.

## Surface types and erasure

A constrained type is a **surface** type. There are two type languages:

* the *surface* language, in which `check` is a type constructor, and
* the *inference* language, which is Morel's existing type language,

with an erasure `⌊·⌋` that deletes `check` nodes structurally, so `⌊nat⌋ =
int`.

**Type inference is unchanged.** Algorithm W runs on the inference language.
Principal types and the value restriction are unaffected, and there is no
subtyping rule to admit. Constraints are handled by a second, syntax-directed
pass over the elaborated tree, which reconstructs a surface type for each
subexpression by walking the skeleton W has already produced, and inserts a
check wherever a value flows into a position whose surface type is
constrained.

This is the structure of [Liquid Types][liquid] (Rondon, Kawaguchi and Jhala,
PLDI 2008), where Hindley-Milner is invoked first as an oracle and each
subexpression is then assigned a template with the same shape as its inferred
ML type. The difference is that we propagate constraints from declared
signatures rather than inferring them by predicate abstraction.

[liquid]: https://patrickrondon.com/research/papers/liquid-types-pldi08.pdf

### Widening is free, narrowing is checked

Erasure is what makes a `nat` usable as an `int`. No coercion is needed in
that direction and none is generated: the representation is identical and the
constraint is simply dropped. The other direction is a narrowing, and can
fail.

```sml
type nat = int check i => i >= 0;

val n: nat = 5;
val i = n - 100;
> val i = ~95 : int
(*) Widening is free; 'n - 100' is an ordinary int subtraction.

val m: nat = i;
> uncaught exception Constraint [~95 is not a valid nat]
(*) Narrowing is checked at the binding, where the surface type is known.
```

### The invariant

**No elaboration decision may consult the substitution — only surface types
that are syntactically present.**

So a constrained type that reaches a type variable is *lost*, and the second
pass recovers it only where a declared signature lets it flow covariantly out
of a result position.

```sml
val ns: nat list = [1, 2, 3];
val ms = List.map (fn i => i - 1) ns;
> val ms = [0,1,2] : int list
(* The constraint is erased at the boundary: 'a is instantiated to int, and
 * nothing propagates it to the result. *)
```

This invariant is also the soundness argument for the goal. Every place a
surface type *claims* a constraint, a check is inserted; everywhere else the
constraint is erased, so nothing claims it. A value cannot breach a constraint
that was never asserted of it.

A surface type is therefore **a record of what has been verified, not an
obligation to verify**.

```sml
val ps = List.map (fn i => i as nat) [3, ~2];
> uncaught exception Constraint [~2 is not a valid nat]
(* The cast is an ordinary expression, evaluated once per element. Had it
 * succeeded, the surface type of 'ps' would be 'nat list'. *)
```

Note that this also answers promptness: a cast written *inside* the mapper
runs per element and fails at the offending one, where a narrowing at the
binding would have run the whole traversal first.

### Not an `abstype`

`abstype` gives a similar guarantee — one checked way in, so the invariant
holds of every value — but in the wrong shape. It is opaque in *both*
directions:

| | in (`int` to `nat`) | out (`nat` to `int`) |
|---|---|---|
| `abstype` | explicit constructor | explicit accessor |
| constrained type | checked narrowing | free, by erasure |

Requiring `toInt n + 1` everywhere would make constrained types unusable for
their purpose. (`abstype` is in any case largely superseded in Standard ML by
opaque signature ascription, and Morel has no user-facing module system.)

The analogy fails on the implementation too. `abstype` has a constructor; a
narrowing has none. On success it is the **identity**: no wrapper is
allocated, no tag is attached, and equality, printing and serialization are
unaffected.

## Syntax

> *typbind* ::= ⟨*var*⟩(`,`) *id* [`check` *match*] `=` *typ* ⟨`and` *typbind*⟩
>
> *exp* ::= ... | *exp* `as` *typ* | *exp* `asOpt` *typ*

`as` subsumes the *exp* `check` *match* production originally proposed: `e
check m` is `e as (t check m)` with `t` inferred. Only one of the two forms
need be kept.

### `check` matches need not be exhaustive

The issue text requires the *match* to be exhaustive. Instead, a
non-exhaustive match is allowed and the compiler appends `| _ => false`, so a
value that matches no branch fails the check.

```sml
(*) Valid. Equivalent to the three-branch form: (true, 1) fails the check.
type badPair = (bool * int) check
    (true, 0) => true
  | (false, i) => i mod 2 = 0;
```

### When to append

Appending unconditionally would break the issue's own example. A redundant
branch is an **error** today, and it throws:

```
fun f 1 = "a" | f 1 = "b" | f _ = "c";
> stdIn:1.17-1.26 Error: match redundant
fun g 1 = "a";
> stdIn:1.5-1.14 Warning: match nonexhaustive
```

so appending `| _ => false` to a match that is *already* exhaustive — such as
the three-branch `badPair` in the issue — would make the appended branch
redundant and reject the declaration.

No new property is needed. Use the existing `matchCoverageEnabled`:

* **`matchCoverageEnabled` false** — append blindly. Redundancy is never
  reported, so a redundant `_ => false` is unreachable and harmless. More to
  the point, `PatternCoverageChecker` is SAT-based (`Sat`, via
  `isExhaustive`), and someone who disables coverage checking should not be
  made to pay for a solver call merely to decide whether to append.
* **`matchCoverageEnabled` true** — call `PatternCoverageChecker.isExhaustive`
  on the match and append only if it is not exhaustive. The appended branch
  then covers a gap, so it is not redundant, and the match is exhaustive
  afterwards, so no warning is emitted either.

The semantics are the same either way: a value matching no branch fails the
check.

Other consequences:

* **Ordering.** The decision and the rewrite must happen *before* the general
  `checkPatternCoverage` pass, so that pass sees an exhaustive match and emits
  neither the non-exhaustive warning nor a redundancy error.
* `isExhaustive` takes `Core.Pat`, so the rewrite is a Core-level one.
* The appended branch needs a source position; use the position of the whole
  match, and never blame it — user-written redundancy inside a `check` match
  is a real mistake and should stay an error.
* A single irrefutable branch (`i => i >= 0`, the common case) is exhaustive,
  so nothing is appended.

## Conversion operators

Widening needs no operator. Narrowing has two, differing only in how they
report failure.

Both take a value and a type, and their typing rule is stated on erasures: `e
as t` and `e asOpt t` are well-typed if `⌊t⌋` unifies with the inferred type
of `e`. Because erasure deletes `check` nodes, every type built over `int` —
`int`, `nat`, `batchSize`, an anonymous `int check ...` — has the same
erasure, so all of them may be converted to one another. A conversion between
different erasures is an ordinary type error, reported by the unifier without
any constraint reasoning.

Neither is a runtime type test: Morel values carry no type information, so `i
asOpt string` is a compile-time error rather than an expression returning
`NONE`. The question they ask is whether a value satisfies a constraint, never
what type a value has.

### `as`

Returns the value unchanged if the constraints of `t` hold, and otherwise
raises `Constraint`. Surface type `t`, inference type `⌊t⌋`.

```sml
type nat = int check i => i >= 0;
type teen = int check i => i >= 13 andalso i <= 19;

val i = ~95;
i as nat;
> uncaught exception Constraint [~95 is not a valid nat]

val n: nat = 5;
n as teen;
> uncaught exception Constraint [5 is not a valid teen]
(*) Legal: 'nat' and 'teen' have the same erasure, 'int'.

n as int;
> val it = 5 : int
(*) Legal and free: widening discards a constraint, checks nothing.

"abc" as nat;
> Cannot convert 'string' to 'nat': types have different erasures
```

### `asOpt`

Returns `SOME v` if the constraints hold and `NONE` otherwise, with surface
type `t option`. It exists because failure is often ordinary — a value parsed
from outside, or a row that should be filtered rather than abort a scan — and
because the refined value arrives bound, so the successful branch needs no
separate mechanism for tracking what has been established.

```sml
val i = 20;
i asOpt nat;
> val it = SOME 20 : nat option
i asOpt teen;
> val it = NONE : teen option

case i asOpt nat of
    SOME n => n * 2
  | NONE => 0;
> val it = 40 : int
(* 'n' has surface type 'nat', so passing it to something expecting a 'nat'
 * emits no further check. *)
```

### Properties

* Neither operator changes representation; on success `as` is the identity.
* On a composite type the check is deep, applied to components before the
  whole, using the same order and blame path as construction.
* Where the surface type already satisfies the target, the check is elided
  statically, so `n as nat` costs nothing.
* Converting to a constrained **function** type is rejected: it cannot be the
  identity, since the only way to enforce it is a proxy checking each argument
  and result.

```sml
[1, ~2, 3] as nat list;
> uncaught exception Constraint [~2 is not a valid nat: element 1]

val f = fn i => i - 1;
f as (nat -> nat);
> Cannot convert to a constrained function type
```

## Where a check is inserted

Errors use Morel's existing format, `uncaught exception Name [message]`, as in
`uncaught exception Subscript [subscript out of bounds]`.

### A. Bindings, parameters and results

Syntactic, and the common case.

```sml
val n: nat = ~1;
> uncaught exception Constraint [~1 is not a valid nat]

fun f (n: nat) = n;
f ~1;
> uncaught exception Constraint [~1 is not a valid nat: argument of f]

fun g (): nat = ~1;
g ();
> uncaught exception Constraint [~1 is not a valid nat: result of g]
```

A parameter's check is compiled inside the function, so it travels with the
function value and fires however the function is called — including from
polymorphic code that knows nothing of `nat`.

### B. Construction of composite values

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

Compound constraints check components before the whole, so the message names
the innermost failure:

```sml
type evenPair = (nat * nat) check (i, j) => i * j mod 2 = 0;
(~1, 2): evenPair;
> uncaught exception Constraint [~1 is not a valid nat: component 0]
(1, 3): evenPair;
> uncaught exception Constraint [(1,3) is not a valid evenPair]
```

### C. Through polymorphic functions

**No polymorphic function needs to change**, and none is instrumented. Either
the constraint is erased at the boundary, in which case nothing is claimed of
the result and nothing need be checked:

```sml
val ms = List.map (fn i => i - 1) ns;
> val ms = [0,1,2] : int list
```

or the result is narrowed at a written type, and the check goes there:

```sml
val ms: nat list = List.map (fn i => i - 1) ns;
> uncaught exception Constraint [~1 is not a valid nat: element 0]
```

or a cast is written inside, and runs per element:

```sml
List.map (fn i => (i - 1) as nat) ns;
> uncaught exception Constraint [~1 is not a valid nat]
```

The costs are promptness (a narrowing at a binding runs the whole traversal
first) and forcing (a narrowing walks its operand, so a lazy or foreign
collection is materialized; see E).

### D. Constrained function types — deferred

A narrowing to a function type cannot be the identity, so it is rejected
rather than implemented with a proxy.

```sml
val h: nat -> nat = fn i => i - 1;
> Cannot convert to a constrained function type
```

This also removes the workaround an earlier draft of this plan proposed —
ascribing at a call site, `twice (dec : nat -> nat) 1`, to place a coercion
the compiler could compile. That is now rejected too. The polymorphic cases
therefore have no user-level remedy, and wait on the deferred work below.

### E. Values from outside

Foreign rows and parsed values never pass through a Morel constructor.

```sml
val emps: employee bag = scott.emps;
```

A narrowing here walks the whole bag, turning a streamed Calcite query into a
materialized one. `asOpt` is the intended tool where failure should filter
rather than abort.

### F. Generated values

From the issue's comment: a constrained type used as a scan source must
*generate* only conforming values, not generate-and-filter.

```sml
type parity_pair = {i: int, j: int} check {i, j} => i mod 2 = j mod 2;
from p: parity_pair where p.i elem [0..2] andalso p.j elem [5..8];
> val it = [{i=0,j=6},{i=0,j=8},{i=1,j=5},{i=1,j=7},{i=2,j=6},{i=2,j=8}]
>   : {i:int, j:int} bag
```

There is no value to check, so no narrowing can help. `Extents` already
deduces "the set of values a variable can take", so this is where it plugs in
— but a constraint must be *readable* by the planner, not merely callable.

### G. The predicate itself

```sml
(*) The predicate raises.
type odd = int check i => 100 div i mod 2 = 1;
val x: odd = 0;
> uncaught exception Div [divide by zero]
```

The issue specifies that a predicate captures the values it uses at
declaration time, so redefining `limit` or `lessThanDozen` afterwards does not
change the type. The closure must be snapshotted into the surface type, which
affects surface-type equality and printing.

## Phases

1. **Surface types.** A second type language with `check` nodes and an erasure
   to the inference language; surface types recorded on `Core` nodes. No
   enforcement. Inference untouched.
2. **Syntax.** `check` in `typbind`; `as` and `asOpt` expressions; append
   `| _ => false` per the rule above — blindly if `matchCoverageEnabled` is
   false, otherwise only when `isExhaustive` says the match has a gap — before
   the general coverage pass runs.
3. **Narrowing.** `Constraint` in `BuiltInExn`; checks at bindings, parameters
   and results (A); `as` and `asOpt`; static elision where the surface type
   already satisfies the target.
4. **Composites.** Deep checks for records, tuples, lists, datatype
   constructors and record modifiers (B); component-before-whole ordering and
   the blame path.
5. **Rejections.** Constrained function types (D) and conversions between
   different erasures, with messages that say which.
6. **Planner.** Teach `Extents` to read constraints so a constrained type can
   be scanned (F). Overlaps #240 and #241.

Deferred: constrained function types and any recovery of a constraint that has
reached a type variable. Both need a type-directed dispatch mechanism; #290
needs one too, for comparators, so they should share it. Under this design
neither is a soundness hole — the constraint is erased, so nothing is claimed
— which is why they can wait.

## Open questions

1. **The issue text still requires an exhaustive match.** This plan allows a
   non-exhaustive one and appends `| _ => false`. The issue should be updated.
2. **Anonymous constrained types in messages.** `val j: int check k => k >= 10
   = ...` has no type name to put in `[... is not a valid ...]`. Use the
   source position?
3. **Predicate that raises.** Propagate the underlying exception, as above, or
   wrap it as `Constraint`?
4. **Repeated narrowing.** A value narrowed to `nat list` and then passed to
   something else expecting `nat list` is walked twice. Is that acceptable, or
   should a surface type at a binding be enough to elide the second?
5. **Foreign data (E).** Check on entry, or declare the boundary untrusted?
6. **What `assert` returns.** #239 says the #242 operators "return their
   operand, of the same type, but with additional constraints known to the
   system", but #242 says "Both have type `bool`" and uses `assert p > 0;` as
   a statement. This plan follows #242. The two issues should be reconciled.
7. **Does a plain annotation always narrow?** `val m: nat = i` checks. If some
   annotations should be static-only, `as` and the annotation differ, and the
   rule "a written surface type is a check site" is lost.
