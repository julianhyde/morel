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

## A constrained type is distinct from its base type

An ordinary alias expands, as in Standard ML: `type nat = int` makes `nat` and
`int` the same type, and the name does not survive inference.

A *constrained* type does not. `type nat = int check i => i >= 0` is a
distinct type. This is the decision that makes the rest of the design work:
if `nat` expanded to `int`, then every `int` would be structurally a `nat`,
there would be no site to guard, and the constraint would be unenforceable.

The relation between the two is **subtyping**: every `nat` is an `int`, so

* `nat` to `int` is free (an upcast: nothing to check), and
* `int` to `nat` requires a check (a downcast).

Every check in this document is a downcast. That is also what the issue's
examples assume — `val j: int check k => k >= 10 = i + 10` downcasts an `int`
expression into a constrained position.

**We do not add general subtyping to the unifier.** A downcast is inserted
only where the expected type is one the user wrote — checking mode in a
bidirectional reading of the type rules. Hindley-Milner inference is
unchanged, and the set of coercion sites stays syntactic and finite. Where an
`int` meets a `nat` outside such a site, the result is a type error, not a
silent coercion.

Two properties of Morel make this subtyping simpler than it would be
elsewhere. There are **no mutable references**, so nothing is invariant: an
immutable `list` and `bag` are covariant, and a function is contravariant in
its argument. And the representation is **identical** — a constrained type is
distinct in the type system but not boxed at run time — so an upcast is free,
a `nat list` used as an `int list` costs nothing, and interoperation with
Calcite is unaffected.

### Not an `abstype`

`abstype` gives the same guarantee we want — one checked way in, so the
invariant holds of every value — but in the wrong shape. It is opaque in
*both* directions:

| | in (`int` to `nat`) | out (`nat` to `int`) |
|---|---|---|
| `abstype` | explicit constructor | explicit accessor |
| constrained type | implicit, **checked** | implicit, **free** |

Requiring `toInt n + 1` everywhere would make constrained types unusable for
their purpose. A constrained type is a *refinement* type, not an abstract
type. (`abstype` is in any case largely superseded in Standard ML by opaque
signature ascription, and Morel has no user-facing module system, so it is not
available as a building block.)

`abstype` remains a fair model of the *implementation*: a distinct type whose
only entry point is a generated checked constructor. The divergence is that
the exit is implicit and free, and that there is no box.

### Ascription is the cast

We do not need a new cast operator. Two forms already express one:

* `e : nat` casts to a named constrained type — ordinary Standard ML
  ascription;
* `e check m` casts to an anonymous one — already proposed in #239, and used
  in #242 as `(i * j) check p => p > 0`.

Ascription therefore does the work, and one rule covers everything: **an
ascription site is a coercion site is a blame site.**

## Where a downcast happens

The sections below enumerate the sites, and what each one does when the
constraint fails.

Errors use Morel's existing format, `uncaught exception Name [message]`, as in
`uncaught exception Subscript [subscript out of bounds]`.

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

### C. Downcasts into a polymorphic result

A polymorphic function needs no modification. The downcast happens at the
site where the constrained type is written, which is outside the function.

```sml
fun dec x = x - 1;
val ns: nat list = List.map dec [0, 1, 2];
> uncaught exception Constraint [~1 is not a valid nat: element 0]
```

`List.map` runs unchanged and returns an `int list`; the downcast to `nat
list` at the `val` binding walks the list and checks each element.

Better still, a check written in a function's own signature compiles into its
body and travels with the value, so combinators stay ignorant even at higher
order:

```sml
fun dec (x: nat): nat = x - 1;   (* both checks inside dec *)
twice dec 1;                     (* 'a = nat; no coercion; twice unchanged *)
List.map dec ns;                 (* 'a = nat, 'b = nat; map unchanged *)
```

Two costs, neither of them soundness:

* **Promptness.** The exception is raised at the boundary, after the
  traversal, not at the offending element. `List.map` over `[3, 101, 8]`
  completes all three calls before the check runs, and reports the first
  breach in the *result*, not the first one encountered.
* **Forcing.** A downcast walks its operand, so a lazy or foreign collection
  is materialized at the boundary. See E.

### D. Downcasts at a type variable

This is the case that a polymorphic function cannot absorb.

```sml
type nat = int check i => i >= 0;
fun dec (x: nat) = x - 1;        (* nat -> int: checks argument, not result *)
fun twice f x = f (f x);         (* ('a -> 'a) -> 'a -> 'a *)
twice dec 1;
> Error: cannot unify nat and int in argument of twice
```

Unifying `nat -> int` with `'a -> 'a` needs `'a = nat` from the argument and
`'a = int` from the result. Under distinctness that is a type error rather
than a silent breach, which is the improvement we want. But to *run* it, the
`int` result of the inner `f x` must be downcast to `nat` before the outer `f`
receives it, and that downcast sits at a position typed `'a`, inside a `twice`
compiled once and holding no checker.

The same shape at first order is fine, because the downcast lands at a written
type rather than a type variable:

```sml
val ns: nat list = List.map dec ns0;   (* one downcast at the binding *)
```

**The fix is an ascription, not a new mechanism.** Writing the type gives the
compiler a site to compile the coercion, and `twice` is still unchanged:

```sml
twice (dec : nat -> nat) 5;
> val it = 3 : nat
twice (dec : nat -> nat) 1;        (* dec (dec 1) = dec 0 = ~1 *)
> uncaught exception Constraint [~1 is not a valid nat: result of dec]
twice (dec : nat -> nat) ~1;
> uncaught exception Constraint [~1 is not a valid nat: argument of dec]
```

Note which half needs the wrapper. `dec`'s *argument* check is compiled inside
`dec` and travels with the function value, so it fires however `dec` is
called, even from a polymorphic function that knows nothing. Only the *result*
needs the ascription to wrap it.

So D is a type error whose remedy the user can write today, rather than a hole
that waits on #290. #290 would remove the need for the ascription, not enable
the feature.

### D2. Function values

A downcast of a *function* cannot inspect its operand; it must wrap it, and
then attribute blame.

```sml
fun dec x = x - 1;
val g: nat -> nat = dec;    (* wraps dec: checks argument in, result out *)
g 0;
> uncaught exception Constraint [~1 is not a valid nat: result of g]
g ~1;
> uncaught exception Constraint [~1 is not a valid nat: argument of g]
```

Note that a downcast on data is an inspection and returns its operand
unchanged, whereas a downcast on a function is a transformation. Wrapping is
tractable at a written type; it is not available at a type variable, which is
D again.

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

## Polymorphic functions do not need to change

The important question for reuse is not "is the function polymorphic?" but
**where does the downcast land?**

* If it lands at a type the user wrote — a `val` annotation, a parameter, a
  return, a field — the downcast is compiled there, and every polymorphic
  function it flows through is untouched. `List.map`, `List.filter`, `twice`
  and user combinators need no modification, no reified type and no
  handler. This covers C, and it is the overwhelmingly common case.
* If it lands at a **type variable** (D), there is nowhere to compile it. The
  function was compiled once at `'a` and holds no checker.

So only D needs a dispatch mechanism, and D is the same question as #290:
what does a polymorphic function do when it needs a type-directed operation
for a type variable? There a polymorphic function needs a *comparator*, and
"the comparator is generated at compile time, but the full type is not
available until the function is applied". Here it needs a *checker*.
`Range.contains`, `Range.normalize`, `Range.toList` and `Range.toBag` need
the same thing.

Morel's current mechanism, `Codes.Typed.withType(typeSystem, type)`,
specializes a builtin once the concrete type is known at compile time. That is
enough for a monomorphic site and not for a polymorphic one — which is
precisely why #290 is still open.

So: **do not invent a constraint-dispatch mechanism for #239.** Whatever
solves #290 should carry the checker too. Until then, D is a type error, which
is honest: the program is rejected rather than silently unchecked.

### Options for the remaining cases

| | Approach | Reuse of polymorphic functions | Closes | Cost |
|---|---|---|---|---|
| 1 | Downcast at written types (this plan) | Untouched — no reified type, no handler | A, B, C, D2 | Traversal per downcast; not prompt |
| 2 | Type-directed dispatch, shared with #290 | Untouched — one compiled copy serves every instantiation | D | Large compiler change; hidden parameters |
| 3 | General subtyping in the unifier | Untouched | D, and removes the type errors | Subtyping plus HM is a research-grade change |
| 4 | Static proof (#242 `prove`), runtime check only where unproven | Untouched | An optimization on 1, not a design | SMT-shaped work |
| 5 | "Already checked" mark on a value | Untouched | Repeated traversal | Representation change; interacts with equality |

Option 3 is the only one that would make `twice dec 1` run without a wrapper,
and it is much the most expensive; option 2 gets the same effect for the price
we are paying for #290 anyway.

### Recommendation

Land 1, and let #290 supply 2:

* Phases 1–3 implement the downcast at written types, which covers A, B, C and
  D2 — nearly all the value, with no change to any polymorphic function.
* A downcast landing at a type variable (D) is a **type error**, not a silent
  hole, and the remedy is an ascription the user can write today. The program
  is rejected, so the "impossible to breach" promise holds for everything that
  compiles.
* When #290 lands a dispatch mechanism, route the checker through it. That
  removes the need for the ascription; it is not what enables the feature.

That ordering means we never ship an unsound-but-quiet feature, and we do not
build a second dispatch mechanism that #290 will later replace.

## Phases

0. **A constrained type survives inference.** A plain alias still expands; a
   constrained type does not. This is the prerequisite: unless the type
   reaches `Core`, there is nothing to compile a downcast from. Touches
   `TypeSystem`, `Keys`, `TypeResolver` and `Unifier`, and `Pretty` for
   printing.
1. **Syntax.** `check` keyword; `typbind` and `exp` productions; `Ast` and
   `Core` nodes; append `| _ => false`; suppress the non-exhaustive error and
   keep the redundant-branch one. No enforcement yet.
2. **Runtime.** `Constraint` added to `BuiltInExn`; downcast at bindings,
   parameters and returns (A).
3. **Composites.** Downcast into records, tuples, lists, datatype
   constructors and record modifiers (B, C); component-before-whole ordering
   and the blame path in messages.
4. **Functions.** Wrap a function at a downcast to a constrained function
   type, with blame (D2).
5. **Restriction.** A downcast landing at a type variable is a type error (D).
   The message should name the ascription that fixes it, in the style of
   "cannot unify nat and int in argument of twice; ascribe the argument, as
   `(dec : nat -> nat)`".
6. **Dispatch.** Once #290 has a mechanism, carry checkers through it and
   lift the phase-5 restriction.
7. **Planner.** Teach `Extents` to read constraints so a constrained type can
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
6. **What `assert` returns.** #239 says the #242 operators "return their
   operand, of the same type, but with additional constraints known to the
   system", but #242 says "Both have type `bool`" and uses `assert p > 0;` as
   a statement. This plan follows #242, so `assert` is not a cast and
   ascription does that job. The two issues should be reconciled.
7. **Does ascription always check?** This plan says `e : nat` inserts a
   downcast. If some ascriptions should be static-only, we need a second form,
   and the "ascription site is a coercion site" rule is lost.
