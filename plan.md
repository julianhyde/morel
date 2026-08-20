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

**Where a metavariable is involved this is sound for free, and the reason is
parametricity.** The name survives exactly where unification never had to look
inside it — that is, where the value passed only through parametric
operations, so it is one of the inputs and its constraint still holds. The
moment an operation needs the base type (`i - 1` needs `int` arithmetic) the
name reduces to `int`, and that is precisely the moment the value may have
changed and the constraint stops being guaranteed. Nothing has to enforce
this; the unifier does it.

Parametricity does *not* cover the case where two concrete types meet, and
there SML/NJ's rule is unsound for us; see below.

It is conservative in the safe direction. `List.filter (fn i => i > 0) ns`
gives `int list` although filtering cannot invalidate the constraint, because
the *predicate* mentioned `int`. Losing a constraint that still holds is a
false negative; the reverse never happens.

### Arithmetic must drop the constraint

Standard ML's `+` is `int * int -> int`, so `n + 1` where `n: nat` has type
`int` and the constraint is dropped, exactly as the parametricity argument
above requires. **Morel's arithmetic operators are overloaded, `'a * 'a ->
'a`**, so the alias is carried through instead:

```sml
type nat = int check i => i >= 0;
val n: nat = 5;
n + 1;      (*) Morel gives 'nat'; Standard ML gives 'int'
n - 100;    (*) Morel gives 'nat' -- and ~95 is not a nat
```

For a plain alias that is harmless, since the alias and its expansion are the
same type. For a constrained type it is **unsound**, and `n - 100` is the
plan's own first example of a value that must lose its constraint.

**The meet rule handles the binary operators.** Since `+` has type `'a * 'a ->
'a`, its two operand types meet, and an alias meeting `int` weakens to `int`.
So `n + 1` and `n - 100` now report `int`, as Standard ML does, without
arithmetic being special-cased.

What remains is the **unary** case, where nothing meets:

```sml
~n;       (*) Morel gives 'nat'; Standard ML gives 'int'
```

`~` has type `'a -> 'a`, so the argument's type flows straight to the result.
For an alias that is a cosmetic difference from Standard ML; for a constrained
type it is unsound, since `~n` is negative whenever `n` is positive. An
operator that computes a new value from a single argument must drop the
constraint explicitly.

### The invariant

**A constraint is claimed only where the type says so, and a check is inserted
wherever a value flows into a claim.** Everywhere else the name has reduced to
the base type, so nothing is claimed and nothing can be breached. That is the
soundness argument for the goal.

Note that this supersedes the stronger rule in the issue's comment — "no
elaboration decision may consult the substitution" — which would give `List.hd
ns : int` and lose the propagation above. Consulting the substitution is
exactly what makes `#1 p : nat` work. But the comment's rule was guarding
something real: consulting the substitution is only sound when the binding
came from a metavariable. Where two concrete types meet, an extra rule is
needed; see below.

### This is not in the Definition, and we cannot adopt it unchanged

Two caveats, both of which matter.

**It is not specified.** In the Definition of Standard ML a `type` binding
introduces a *type function*, and applying a type function β-reduces
immediately; after elaboration there is no `nat`, it simply *is* `int`. The
Definition also says nothing about what a top level prints. So preserving the
name is an implementation nicety, not a requirement, and Morel's eager
expansion is faithful to the Definition. The Definition gives inference rules
rather than an algorithm; implementations use Algorithm W with unification,
and those that preserve abbreviations represent a `type` as a *defined type
constructor* carrying its definition and unify **up to head-reduction** —
SML/NJ's `DEFtyc` and `headReduceType`, OCaml's `expand_head`. Standard
practice, not standardized.

**SML/NJ's rule is order-dependent, and adopting it unchanged would be
unsound.** Which name survives depends on the order the unifier meets the
operands:

```sml
val n : nat = 5;   val i : int = 6;
[n, i];    > [5,6] : nat list
[i, n];    > [6,5] : int list
```

For a plain abbreviation that is harmless, because the two are the same type
and nothing is claimed. For a *constrained* type it is not:

```sml
val bad : int = ~5;
[n, bad];                    > [5,~5] : nat list
List.nth ([n, bad], 1);      > ~5 : nat
```

`~5` has been given the type `nat` without ever being checked. A later `as
nat` on it would be elided, and the value would breach the constraint. So the
heuristic is presentation for Standard ML but load-bearing for us, and it is
wrong.

**The rule we need.** Binding a metavariable to the written form is sound, and
that is the case parametricity covers: `#1 p`, `List.hd ns`, `List.map (fn i
=> i) ns`. But when two *concrete* types with the same erasure meet, the
result is their **meet**, computed without entailment: equal constraints
(textually) meet to themselves, and anything else meets to the base type.
`nat` meets `nat` gives `nat`; `nat` meets `int` gives `int`; `nat` meets
`teen` gives `int`, even though `teen` implies `nat`. Sound, deterministic,
and cheap, which SML/NJ's rule is not.

Morel expands eagerly today, so all of this is new behaviour:

| | Morel today | SML/NJ | this plan |
|---|---|---|---|
| `fun f (x: nat) = x` | `int -> int` | `nat -> nat` | `nat -> nat` |
| `[n]` where `n: nat` | `int list` | `nat list` | `nat list` |
| `List.hd ns` | `int` | `nat` | `nat` |
| `[n, i]` where `i: int` | `int list` | `nat list` | `int list` |
| `[i, n]` | `int list` | `int list` | `int list` |

A `check` node must also not obstruct unification: head-reduction discards it,
as it expands an abbreviation.

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

### The condition must be closed

A `check` condition may not depend on the environment. Its only free variable
is the value the match binds. That is what lets a constrained type be interned
like any other: `TypeSystem` holds `Map<Key, Type>`, a `Key` must hash and
compare cheaply, and it cannot hold a closure. With a closed condition the key
is structural — the condition itself — and two constrained types are the same
type when their conditions are textually equal.

**The issue's `batchSize` example is not closed**, and neither is the section
that follows it:

```sml
val limit = 12;
fun lessThanDozen i = i >= 1 andalso i <= limit;
type batchSize = int check i => lessThanDozen i;
```

Two ways to reconcile:

* **Reject it.** The condition must be written inline. Simple, and the whole
  question of what a predicate captures disappears, along with the issue's
  three re-binding cases.
* **Inline at declaration time.** Substitute `lessThanDozen` and `limit`,
  giving the closed term `check i => i >= 1 andalso i <= 12`. This *is* the
  issue's stated semantics — "the predicate does not change if the values are
  re-bound" — with the snapshot taken as a closed term rather than a closure,
  so the example survives and keying still works. A condition that cannot be
  inlined, such as one calling a recursive function, is rejected.

Either way the condition in the type is a closed term, and re-binding cannot
affect it. Built-in operators are not rebindable — `val op >= = ...` is a
parse error — so they need no special treatment.

### Out of scope

* Parameterized constrained types, `type 'a t = 'a list check ...`.
* Overloading (`over`, `inst`) on constrained types.
* Recursive constrained types.

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
* Elision does **not** attempt entailment. Two constraints match only if they
  are textually equal; anything else emits the check. So `n as nat` is free,
  but `k as nat` where `k` has type `int check z => z > 0` is *not*, even
  though `z > 0` implies `z >= 0`. Conservative and cheap, and it keeps #242's
  `prove` out of the critical path. An entailment test can be added later
  without changing any accepted program, only removing checks.
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

There is no value to check, so no narrowing can help; the constraint has to
reach the planner.

**Most of this now exists.** Unbounded scans over a written type landed in
#440 and #443, and `Extents` already grounds a variable from an `elem`
constraint. Writing the constraint by hand gives the answer the issue asks
for, exactly:

```sml
type pair = {i: int, j: int};
from {i, j}: pair
  where i elem [0..2] andalso j elem [5..8]
  andalso i mod 2 = j mod 2;
> val it = [{i=0,j=6},{i=0,j=8},{i=1,j=5},{i=1,j=7},{i=2,j=6},{i=2,j=8}]
>   : {i:int, j:int} list
```

So two things remain, both smaller than "teach the planner about
constraints":

1. **Conjoin the type's `check` predicate into the scan's filter.** Mechanical
   once `check` exists, and the example above is the result.
2. **Ground a record variable from a constraint on a field selection.** A
   destructured pattern already works, but the issue writes `p.i elem
   [0..2]`, and that reports "pattern 'p' is not grounded". Either teach
   `Extents` to ground `p` from constraints on `p.i`, or accept the
   destructured form.

Note the issue's expected output says `bag`; a scan currently yields a
`list`.

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

1. ~~**Abbreviations propagate.**~~ **Done.** An alias now survives inference,
   by unifying up to head-reduction. The design that landed differs from the
   one sketched here: an alias reaches only the type *displayed for a
   binding*, and every type the compiler examines has its aliases expanded, so
   nothing that inspects a type structurally has to know an alias exists.
   `check` nodes ride on the same mechanism.

   The **meet rule** is done too: where an alias meets a different type the
   result is the weaker of the two, so `[n, i]` and `[i, n]` are both `int
   list`, whichever is seen first. The unifier records each alias it had to
   expand in order to unify, and weakens it in the substitution on the way
   out.
2. ~~**Syntax.**~~ **Done.** `check` in `typbind`; `as` and `asOpt`
   expressions; `| _ => false` appended to a condition that is not exhaustive.
   The condition is also type-checked, as `base -> bool`, and must be closed;
   a parameterized type may not be constrained.

   Two things the sketch did not anticipate. A `check` clause holds a *list of
   functions*, one per clause, not a flat list of matches: the branches of a
   clause are alternatives, whereas separate clauses are conjoined, and
   flattening confused the two. And the `| _ => false` is appended in
   `Resolver`, not `TypeResolver`, because exhaustiveness is decided on Core
   patterns.
3. ~~**Narrowing.**~~ **Done.** `Constraint` in `BuiltInExn`; checks at
   bindings, parameters, results, ascriptions, `as` and `asOpt`.

   Every one of these reads the type *the user wrote* rather than the type
   inference deduces. Inference gives the meet, which for a constrained type
   is the type it abbreviates, so a deduced type has no condition left to
   check: `fun decr (n: nat) = n - 1` has type `int -> int`. This is the
   single most important thing the sketch got wrong.
4. ~~**Composites.**~~ **Done.** Records, tuples, lists and datatype
   constructors are followed, to any depth; components are checked before the
   whole; the message names the component that failed and quotes it.

   `deepCondition` walks *two* types in step -- the claimed type, which keeps
   its aliases and so knows where the conditions are, and the erased type,
   which the expressions being built are typed with. A single walk builds a
   selector typed `nat`, which a predicate typed `int -> bool` rejects.

   Record modifiers need no site of their own, contrary to this plan: a
   modifier's result is a plain record, which claims nothing, so it is checked
   where the result is bound.
5. ~~**Rejections.**~~ **Done**, and finer-grained than this plan said. What
   is rejected is a condition on a function's *parameter or result*, which
   would have to check every argument the function is ever given. A condition
   on the function type itself is given the function value, and is checked
   like any other:

   ```sml
   type fnFalse = (int -> int) check c => false;
   val ff: fnFalse = fn i => i;
   > uncaught exception Constraint [fn is not a valid fnFalse]
   ```

   Where the condition lands is decided by parenthesization, so `int -> int
   check c => ...` is allowed and `(int check c => ...) -> int` is not. Until
   this was rejected it was a silent hole -- `constrains` looks for conditions
   in positions a value can be checked at, so it passed a function type over
   and the claim went unenforced.

   A conversion between different erasures needed no work: it is an ordinary
   type error, and the unifier's message names both types and says which is an
   alias.
6. **Planner.** *Half done.* A type's `check` condition is conjoined into a
   scan over it, and the issue's example gives the answer it asks for. A scan
   is the one site whose condition does not raise: which values the type has
   is the question being asked, not something claimed of a value in hand.

   Grounding a record variable from a constraint on a field selection is not
   done. It is **not a constrained-types problem**: `from p: {i: int, j: int}
   where p.i elem [0..2]` reports that `p` is not grounded with no `check`
   anywhere. `Generators.patForExp` already turns `#i p` into a field pattern
   and `deriveFieldGenerators` already builds a record generator from field
   generators, so the machinery half-exists; why it does not fire here wants
   its own investigation, and its own issue.

Deferred: constrained function types and any recovery of a constraint that has
reached a type variable. Both need a type-directed dispatch mechanism; #290
needs one too, for comparators, so they should share it. Under this design
neither is a soundness hole -- the constraint is erased, so nothing is claimed
-- which is why they can wait.

Also not done: values from outside (E). A foreign row never passes through a
Morel constructor, so a claim over a Calcite-backed bag would have to walk it,
turning a streamed query into a materialized one.

## Messages when a constraint fails

Requirements collected for a follow-up issue. The messages today are
serviceable, not good, and the `hr` example in `check.smli` shows where they
fall short. They are accepted as they are for now.

Today a message reads `<value> is not a valid <type>[: <blame>]`, where
`<type>` is the type's moniker and `<blame>` is a path such as `field
emps.element`. The value is rendered as the shell would render it.

### Name the type when it has one

`~1 is not a valid nat` is right when the condition belongs to a type the user
named. A condition on a type that is not named has no such name to give, and
should say `is not a valid value` rather than invent one or print the whole
condition.

### Name the constraint

A type may carry several `check` clauses, and a message says only that one of
them failed:

```sml
type emps = emp bag
  check es => ...          (*) numbers are unique
  check es => ...;         (*) nobody out-earns their manager
```

Both failures read `is not a valid emps`. A syntax that names a clause would
let the message say which, and would give the reader a name to look up. It
would also let a constraint be referred to elsewhere -- in a diagnostic, or a
tool that lists what a schema requires.

### Control how the value is printed

This is the largest gap. A constraint on a collection quotes the entire
collection, and a constraint on a schema quotes every table in it. In the
single-type version of the `hr` example, hiring one unpaid employee prints the
whole schema.

What a reader wants instead:

* **The relation's name.** "in `emps`" rather than the rows of `emps`.
* **The offending row, identified by key.** `empno 1` rather than the row, and
  certainly rather than the table. This needs a notion of a primary key on a
  record collection, which Morel does not have yet; declaring one would serve
  more than messages.
* **Only the part that failed.** Naming the levels already buys this -- see
  below -- but a condition that is genuinely about the whole collection, such
  as uniqueness, has no smaller part to point at, and needs the two above.

### Naming the levels already helps

The two versions of the `hr` example differ only in whether the row and table
types are named, and the messages differ sharply:

```
(*) named
~1 is not a valid emp: field emps.element

(*) inline
{depts=[...],emps=[...]} is not a valid hr1
```

So naming a level is not only documentation: it is what buys a message that
says which level failed and quotes only that much of the value. Any scheme
here should keep that property, and give the anonymous case a way to reach it.

### Types that are not named

A type may carry a condition wherever it is written, so a constraint can sit on
the thing it is about:

```sml
type emp = {deptno: int, empno: int, mgrno: int option,
    sal: real check s => s > 0.0};
```

This is what moves a constraint towards the leaves, and it improves the
message, which now quotes the value that failed rather than the row that
contains it: `0 is not a valid value: field emps.element.sal`.

Such a type is keyed by its body and its conditions, so two written the same
way are the same type, and it is called "value" in a message, having no name
to give.

The `hr` example in `check.smli` is written three ways, and the messages are
what tells them apart:

| form | message when a salary is not positive |
| ---- | ---- |
| a named type per level | `0 is not a valid value: field emps.element.sal` |
| one type, conditions at the top | `{depts=[...],emps=[...]} is not a valid hr1` |
| one type, conditions pushed down | `0 is not a valid value: field emps.element.sal` |

So precision comes from *where the condition is written*, not from naming the
levels. Naming buys only the name: a type that is not named is called "value",
and the blame path says which one. That is what the "name the constraint"
requirement above is for.

Two limits remain:

* A condition is compiled where its type is declared, so one written anywhere
  else -- in an annotation, say -- is rejected: "a `check` may only be written
  in a type declaration". Lifting this means compiling conditions wherever a
  claim reads a type.
* A type that is not named may not sit under a type constructor: `{contents:
  (int check ...) list}` declares, but a value cannot be bound to it. `unalias`
  erases only the outermost type, so the value's type and the type claimed do
  not match. A deep erasure would fix it, and is the same rule -- an alias must
  not reach Core -- applied one level further in.

## Open questions

1. ~~**Closed conditions: reject or inline?**~~ **Resolved: reject.** A
   condition may refer only to the value it is given and to the standard
   basis; a reference to anything the user declared is an error. Inlining --
   substituting what the condition refers to, so that it becomes closed and
   the issue's `batchSize` survives -- is recorded in the tests as a possible
   future feature.

   One hole remains: a basis name the user has shadowed still counts as
   built-in, so a condition could capture the shadowing value. It bites only
   if the shadowing binding has a compatible type; closing it properly needs a
   built-in marker on `Binding`.
2. ~~**Predicate that raises.**~~ **Resolved: wrap as `Constraint`, but say
   which.** Whether the value has the type is then not false but unknown, so
   `Constraint` is right either way -- the value has not been shown to have
   the type -- but "is not a valid nat" would be a lie. The message reads
   `cannot tell whether 0 is a valid odd; Div [divide by zero]`.

   A `Constraint` from a check on a component passes through untouched rather
   than being wrapped again. `asOpt` needed a third operator, `$attempt`: its
   condition was evaluated by the `if` that chooses between SOME and NONE,
   outside any check, so a raise escaped unwrapped.
3. **Naming an anonymous constrained type in a message.** Folded into
   "Messages when a constraint fails" above, which collects this and the rest
   of what those messages need.
4. **What `assert` returns.** Still open. #239 says the #242 operators "return
   their operand, of the same type, but with additional constraints known to
   the system", but #242 says "Both have type `bool`". This plan follows #242.
5. **Two edits the issue needs.** It still requires the `check` match to be
   exhaustive, and its capture-semantics section is superseded by the closed
   condition above.
6. **Foreign data (E).** Still open. Check on entry, or declare the boundary
   untrusted?
7. **Repeated narrowing.** Still open, and now real: a value bound at a
   constrained type and then passed to something else expecting it is walked
   twice, and an ascription inside a binding checks twice over.
8. **Hiding constraints when printing.** Still open, and now pressing: the
   four-condition `hr1` declaration echoes as one very long line, desugared
   (`#length Bag (#emps h)`). Two things would help, and are separable: a
   variant of `type_string` that elides constraints, and an unparser that
   renders a record-selector application as `x.f`.

## Departures from this plan

Recorded here so that the plan and the code agree.

* **A tuple's components are numbered from 1**, not 0: "component 1" is what
  `#1` selects. Matching Morel's own selector seemed less surprising than
  matching this plan.
* **A list element has no index.** `List.all`, which walks the list, does not
  offer one, so "element 1" is just "element".
* **A record modifier does not raise.** Its result claims nothing; the binding
  that receives it does.
* **"Cannot claim", not "cannot convert"**, a constrained function type: the
  same message serves a binding and a parameter as well as a conversion.
