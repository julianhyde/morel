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

## Constrained types and erasure

`check` is a type constructor in Morel's **one** type language, so a
constrained type is an ordinary type that inference can carry, exactly as
Standard ML carries a type abbreviation.

The erasure `⌊·⌋` deletes `check` nodes structurally, so `⌊nat⌋ = int`. It is
not applied eagerly to the whole program. It is applied **on demand, at the
head**, by unification: when the unifier compares two types whose heads
differ, it expands an abbreviation or discards a `check` node and retries.
Everywhere else the written form survives, and a metavariable is bound to
whatever was written. Erasure also states the typing rule for `as` and
`asOpt`, below.

**Type inference is unchanged.** Algorithm W runs as it does now, principal
types and the value restriction are unaffected, and there is no subtyping rule
to admit. Head-reduction is how Standard ML has always treated abbreviations,
not a new relation on types.

A second, syntax-directed pass then walks the elaborated tree and **inserts a
check wherever a value flows into a position whose type is constrained**. Note
what this pass is *not* responsible for: propagation. The unifier has already
done that. The pass only decides where checks go.

That division follows [Liquid Types][liquid] (Rondon, Kawaguchi and Jhala,
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

### Propagation is Standard ML's, by head-reduction

A constrained type should propagate as a Standard ML type abbreviation does.
SML/NJ keeps the name through construction, selection and polymorphic
instantiation, and this is the behaviour to match:

```sml
type nat = int;
val ns : nat list = [1,2];
List.map (fn i => i) ns;          > [1,2] : nat list
List.map (fn i => i - 1) ns;      > [0,1] : int list
List.filter (fn i => i > 0) ns;   > [1,2] : int list
val p : nat * nat = (1,2);
#1 p;                             > 1 : nat
fun twice f x = f (f x);
twice (fn i => i) (hd ns);        > 1 : nat
twice (fn i => i - 1) (hd ns);    > ~1 : int
```

The mechanism is that the abbreviation is a **named type constructor in the
one type language**, and unification is **up to head-reduction**: the name is
expanded only when a rule needs to look inside it. A metavariable is bound to
whatever form was written, so `'a` in `List.hd : 'a list -> 'a` is bound to
`nat`, not to `int`, and the result prints `nat`. This is not subtyping and
does not disturb principal types; Standard ML has both.

**The result is sound for free, and the reason is parametricity.** The name
survives exactly where unification never had to look inside it — that is,
where the value passed only through parametric operations, so it is one of the
inputs and its constraint still holds. The moment an operation needs the base
type (`i - 1` needs `int` arithmetic) the name reduces to `int`, and that is
precisely the moment the value may have changed and the constraint stops being
guaranteed. Nothing has to enforce this; the unifier does it.

It is conservative in the safe direction. `List.filter (fn i => i > 0) ns`
gives `int list` although filtering cannot invalidate the constraint, because
the *predicate* mentioned `int`. Losing a constraint that still holds is a
false negative; the reverse never happens.

### The invariant

**A constraint is claimed only where the type says so, and a check is inserted
wherever a value flows into a claim.** Everywhere else the name has reduced to
the base type, so nothing is claimed and nothing can be breached. That is the
soundness argument for the goal.

Note that this supersedes the stronger rule in the issue's comment — "no
elaboration decision may consult the substitution" — which would give `List.hd
ns : int` and lose the propagation above. Consulting the substitution is
exactly what makes `#1 p : nat` work, and head-reduction is what keeps it
sound.

### Morel does not do this yet

Morel expands an alias eagerly, so it is already less faithful than Standard
ML, before any constraint is involved:

| | Morel today | SML/NJ |
|---|---|---|
| `fun f (x: nat) = x` | `int -> int` | `nat -> nat` |
| `[n]` where `n: nat` | `int list` | `nat list` |
| `{a = n}` | `{a:int}` | `{a:nat}` |
| `List.hd ns` | `int` | `nat` |

Making Morel's abbreviations behave as Standard ML's is therefore a
prerequisite, and is worth doing on its own account — it is a divergence from
Standard ML independent of this feature, and probably deserves its own issue.
Constrained types then ride on the same mechanism, with one addition: a
`check` node must not obstruct unification, so head-reduction discards it,
just as it expands an abbreviation.

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

### Precedence and associativity

`as` and `asOpt` take the **same precedence as `:`**, are left-associative,
and chain. In Morel's grammar `:` sits in its own production above every
infix level:

```
expression: expression0 ( <COLON> type )*
```

so adding `<AS>` and `<AS_OPT>` as alternatives in that loop is the whole
change. Consequences, all of them shared with `:` today:

```sml
i - 1 as nat        (*) = (i - 1) as nat; looser than every operator
i as nat as teen    (*) = (i as nat) as teen; left-associative
e : int as nat      (*) = (e : int) as nat; mixes with ':'
(i as nat) + 1      (*) parentheses required, as for ':'
```

Two reasons to follow `:` rather than Kotlin, Rust or TypeScript, where `as`
binds *tighter* than arithmetic so that `x as Int + 1` means `(x as Int) + 1`:

1. **Refactoring safety.** `e : t` and `e as t` are the same shape — an
   expression paired with a type — and differ only in whether a check is
   emitted. If they had different precedence, changing one to the other would
   silently regroup the expression.
2. **The type grammar shares `*` and `->` with the expression grammar.** Under
   tight binding, `x as int * int` is ambiguous: `int * int` is a legal type
   and `... * int` is a legal expression. ML resolves this by parsing the type
   greedily at the loosest level, which is why `: ty` is loosest in Standard
   ML and Haskell but `as` is tight in Kotlin, whose types contain no `*`.
   Morel's own parse error already lists `-> : *` as the continuations of a
   type.

The cost is that `i as nat + 1` needs parentheses. That is not new: `1 : int +
2` is a parse error in Morel today.

`asOpt` mirrors Kotlin's `as?` exactly — cast that yields absence rather than
throwing — which is a good sign for the pair, though the spelling must be a
word because Morel's lexer has no `?`. It has to be a reserved word: in `e
asOpt t` an identifier could otherwise appear in that position, and `e asOpt`
would parse as an application. That breaks any existing code using `asOpt` as
an identifier.

### Properties

* Neither operator changes representation; on success `as` is the identity.
* On a composite type the check is deep, applied to components before the
  whole, using the same order and blame path as construction.
* Where the surface type already satisfies the target, the check is elided
  statically, so `n as nat` costs nothing. The implementation computes a
  **residual**: the part of the target's constraint not discharged by what is
  already known. An empty residual means no runtime check.
* Elision uses **subsumption**, not equality of types. If `k` has type `int
  check z => z > 0`, then `k as nat` is free, because `z > 0` entails `z >=
  0`. Entailment is undecidable in general, so the test must be a *sound
  approximation*: when it cannot prove entailment it emits the check. Never
  the reverse. This is the same reasoning #242's `prove` needs.
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

## Testing

### There is no oracle

For the `scan` functions of #371, SML/NJ settled every expected value, and it
repeatedly contradicted what the specification implied. Here there is no
oracle: no Standard ML has constrained types. Expected values come from this
document alone, so **the tests are the specification**, and a wrong expectation
will be enshrined rather than caught. Every open question below must be closed
before the tests that depend on it are written.

### The printed type is the observable

What the second pass knows is directly visible in the printed type, which
makes these the cheapest tests of the design — and the ones most likely to
break if propagation is later "improved" into something unsound.

A constrained type **is** printed on re-reference, unlike an alias today:

```sml
val n: nat = 5;
> val n = 5 : nat
n;
> val it = 5 : nat       (*) the check is inside the alias, so the name stays

val k: int check z => z > 0 = 5;
k;
> val it = 5 : int check z => z > 0    (*) anonymous: printed in full
```

So a binding records its surface type in the environment, and a use of the
bound variable recovers it. That is not a violation of the invariant: the
invariant forbids consulting the *substitution*, not the environment.

Note this differs from an ordinary alias, which is erased on re-reference
(`type nat = int` without a `check` prints `int`). The two must be tested
side by side, because the difference is the whole point.

A surface type propagates through construction and selection, as in Standard
ML, so these follow the SML/NJ column of the table above:

```sml
[n];                     > [5] : nat list
{a = n};                 > {a=5} : {a:nat}
val ns: nat list = [1, 2];
List.hd ns;              > 1 : nat
List.map (fn i => i - 1) ns;   > [0,1] : int list
```

The last is the one to pin hardest: the name is lost exactly where an
operation needed the base type, and that is what makes propagation sound.
Since Morel does not behave this way today, each of these is a change to
existing behaviour and needs a test whether or not a `check` is involved.

### Axes

1. **Type shape** — primitive, tuple, record, list, bag, option, datatype,
   nested (`{a: nat list}`, `(nat * nat) list`), constrained-over-constrained
   (`type teen = nat check ...`), function (rejected), type variable (erased).
2. **Site** — the A–G sections above.
3. **Outcome** — passes and returns the value unchanged; fails with a message
   and blame path; rejected at compile time; elided statically.
4. **Match shape** — single irrefutable branch, multi-branch exhaustive,
   multi-branch with a gap, redundant branch; crossed with
   `matchCoverageEnabled`.

Crossing every axis is wasteful. Cross axis 3 with axis 2 exhaustively, and
sample axis 1 — one primitive, one record, one list, one nested — except where
the shape is the point (composites, function types).

### Site × outcome

| Site | passes | fails | rejected | elided |
|---|---|---|---|---|
| `val` with annotation | ✓ | ✓ | — | when surface type already conforms |
| function parameter | ✓ | ✓ | — | — |
| function result | ✓ | ✓ | — | — |
| record / tuple / list construction | ✓ | ✓ (path names field, component, element) | — | — |
| datatype constructor | ✓ | ✓ | — | — |
| record modifier (#432) | ✓ | ✓ | — | — |
| `as` | ✓ | ✓ | different erasure; function type | `n as nat` |
| `asOpt` | `SOME` | `NONE` (never raises) | as above | — |
| through a polymorphic function | no check, constraint erased | — | — | — |
| foreign bag (E) | ✓ | ✓ | — | — |
| `from` scan (F) | generates conforming values | — | — | — |

### Cases a naive matrix misses

1. **Elision must not over-elide.** `n as nat` where `n: nat` is free; `i as
   nat` where `i: int` must check. Same shape, opposite answers — the pair
   catches an elision keyed on the target type rather than the source.
   Subsumption needs its own row: `k as nat` where `k: int check z => z > 0`
   is free because `z > 0` entails `z >= 0`, whereas `n as teen` where `n:
   nat` is not, because `i >= 0` does not entail `i >= 13`. And a case the
   approximation cannot prove must emit the check rather than drop it.
2. **All four coverage combinations.** {match has a gap, is exhaustive} ×
   {`matchCoverageEnabled` true, false}. All four must accept the declaration
   and give identical runtime semantics; today two of them would error.
3. **Component before whole.** A value failing both a component constraint and
   the enclosing one must report the component.
4. **Nested blame.** `{xs = [1, ~2]}` at `{xs: nat list}` must name the field
   *and* the element.
5. **Round trip.** `nat` to `int` to `nat` re-checks: nothing records that a
   value was checked before.
6. **Vacuous cases.** `[]: nat list` and `NONE: nat option` pass. Does the
   predicate run zero times? Observable if it raises.
7. **Refinement survives `asOpt`.** In `case i asOpt nat of SOME n => ...`,
   `n` has surface type `nat`, so a use of `n` where a `nat` is wanted emits
   no second check. This is the only place a constraint is *gained* rather
   than asserted, and it is easy to get wrong.
8. **Repeated `check` clauses.** `int check i => i >= 1 check j => j <= 12`:
   both apply; which failure is reported first?
9. **Capture.** All three of the issue's cases — redefine a captured value,
   redefine a captured function, and a type declared in a `let` whose captured
   bindings have gone out of scope.
10. **Predicate misbehaviour.** Raises, diverges, returns a non-`bool`,
    is constantly true, is constantly false.
11. **Same erasure, different constraint.** `nat as teen`, `teen as nat`,
    `nat as int`, anonymous `int check ...` to and from named.
12. **Different erasure is the unifier's error**, not the constraint
    machinery's: `"abc" as nat` must report before any constraint reasoning,
    and must not mention `Constraint`.
13. **Function types rejected at both sites** — `f as (nat -> nat)` and `val
    h: nat -> nat = f`.
14. **Shadowing.** A `check` match whose bound variable shadows an outer one.
15. **Self-reference.** `type t = t check ...`, and mutually recursive
    constrained types.
16. **Idempotency.** `script/idempotent.smli` round-trips source through the
    printer and parser; `check`, `as` and `asOpt` must survive.
17. **`Sys.plan()`.** Is the check visible in the plan, and does the inliner
    or `Relationalize` remove it?
18. **Calcite pushdown.** A constrained bag pushed to Calcite: does the check
    survive, and is a foreign scan still streamed?

### Where the tests live

* `script/check.smli` (new) — the bulk: sites, outcomes, messages.
* `script/match.smli` — the append rule and the four coverage combinations,
  since they are about matches.
* `script/type.smli` — erasure, unification, and the printed-type observables
  above.
* `script/idempotent.smli` — parser/printer round trip.
* `TypeTest` — unit tests for the erasure function itself, which is easier to
  cover exhaustively in Java than through scripts.
* `LintTest` — the new keywords appear in `docs/reference.md`.

### By phase

Each phase should land with its own tests, rather than deferring them:

1. Abbreviations — the SML/NJ column of the table above, with and without
   a `check`; `TypeTest` for head-reduction and erasure.
2. Syntax — parse and print `check`, `as`, `asOpt`; the four coverage
   combinations; redundant branch still an error; `idempotent.smli`.
3. Narrowing — site × outcome for `val`, parameter, result; `as`/`asOpt`;
   elision pair (case 1 above); message format.
4. Composites — shapes and nested blame paths; ordering (case 3); vacuous
   cases.
5. Rejections — function types, different erasures.
6. Planner — the `parity_pair` scan from the issue comment.

## Phases

1. **Abbreviations propagate.** Make Morel's type abbreviations survive
   inference as Standard ML's do, by unifying up to head-reduction rather than
   expanding eagerly. This changes existing printed types and is worth its own
   issue. `check` nodes then ride on the same mechanism, head-reduction
   discarding them as it expands an abbreviation.
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
8. **Should the abbreviation fix be a separate issue?** Making Morel's
   abbreviations propagate as Standard ML's is a prerequisite, but it is a
   divergence from Standard ML in its own right and changes existing printed
   types. Landing it separately would keep this branch honest and give the
   change its own tests.
9. **Hiding constraints when printing.** An anonymous constrained type prints
   in full (`int check z => z > 0`), which is noisy in a wide record. A
   variant of `type_string` that elides constraints is proposed for later.
