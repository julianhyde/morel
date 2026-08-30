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

# Checked types: follow-up work

Written when #239 merged. Each section is short enough to become an issue of
its own; nothing here is a soundness hole in what shipped.

**This file is not meant to be committed.** It is the surviving content of
`plan.md`, kept out of the merge so that it can be pasted into the tracker and
deleted.

## 1. Deferred by design: checked function types, and conditions on a variable

A function type cannot be claimed. `val f: nat -> nat = ...` is rejected with
"cannot claim a checked function type", because honouring the claim would mean
inserting a check at every call site, and the sites are not known where the
claim is made.

The same shape of problem appears when a condition reaches a type variable: a
polymorphic function that receives a checked value has erased it, so nothing
downstream can recover the condition.

Both need type-directed dispatch. #290 needs one too, so all three should
share it rather than each growing its own.

## 2. Values from outside

A foreign row never passes through a Morel constructor:

```sml
val emps: employee bag = scott.emps;
```

Checking on entry walks the whole bag, turning a streamed Calcite query into a
materialized one. So: check on entry and pay for it, or declare the boundary
untrusted and document that a checked type says nothing about foreign data?
`asOpt` is the tool where failure should filter rather than abort, but it does
not decide the policy.

## 3. Messages when a constraint fails

What shipped names the component and quotes it rather than the whole value:

```
uncaught exception Constraint [~1 is not a valid nat: field x]
```

What is still wanted, from the requirements collected during the work:

* Naming a type that has no name. A condition written inline has nothing to
  call itself, and the message has to describe it instead.
* Naming the constraint when a type has several, so that the message says
  which of them failed rather than that the value is invalid.
* Control over how the offending value is printed, since a large value makes
  the message unreadable.

## 4. Hiding conditions when printing

A `type_string` that elides conditions, and an unparser that renders `#f x` as
`x.f`. A type with four conditions echoes as one very long desugared line,
which is what the `hr1` case in `check.smli` shows.

## 5. Weakening a check to its residual

Where part of a condition is already known to hold, only the rest should be
checked. The design as it stands:

* The residual is a list of conjuncts, not a boolean: split on `andalso`.
* A premise set keyed to `Core.NamedPat` bindings rather than to names.
* Entailment by interval, using `Fbbt`, rather than a decision procedure.

This subsumes repeated narrowing, where a value already known to be a `nat` is
narrowed to a `nat` again.

## 6. The internal operators

`Z_CHECK`, `Z_REQUIRE` and `Z_ATTEMPT` already share one implementation, and
`constraintCode` already takes the operator as a parameter. Fold the three into
one with a mode, and give the asking mode an option type so that the `if` that
`asOpt` needs folds in rather than sitting outside.

## 7. Deriving what is primitive

Three operators that would make the surface smaller:

* **`unchecked t`**, to strip conditions from a type. It must be as deep as
  the walk that checks them.
* **`predicate t`**, to extract a type's condition as a value. Expresses
  "filter rather than abort", and makes `as`, `asOpt` and `e check m`
  derivable.
* **`typeof e check m`**, which reports rather than works. `checkTypes` types
  a condition against its base as a `Type`, so the base must be materialized,
  and a `typeof` has nothing to materialize at that point. The term-based
  `deduceChecks` overload beside it exists precisely so "the base type need
  never be materialized"; switching to it is the likely fix. Note that this
  does **not** make `e check m` derivable as `e : (typeof e check m)`:
  ascription is a meet that weakens, so `fun neg () : nat` is `unit -> int`,
  while `check` adds, so `one check i => i < 100` keeps `positive`.

  (`typeof` in a `type` or `datatype` declaration used to be rejected for a
  related reason, and now works.)

Also: `e as t` pairs with `e check m`, but `e asOpt t` pairs with nothing.
Naming the type is always available, so this may be acceptable.

## 8. Capture for a condition that is not closed

A condition may refer only to the value it is given and to the standard basis;
a reference to anything the user declared is an error. Inlining what the
condition refers to at the declaration, so that it becomes closed, would make
the `batchSize` example in #239 work.

Note that this is narrower than it looks today: `local` is not implemented and
a `type` may not be declared in a `let`, so a free variable can only be a
top-level binding. Implementing either widens it.

## 9. Refining the environment

After `assert`, `assume`, `prove` and `where`, what is learned should refine
the environment. Note that what is learned is a predicate, not a type: `assert
x > y` changes neither variable's type.

## 10. Unrelated bugs found on the way

All three reproduce on the merge commit.

* **`let type t = int in 1 end` throws an `AssertionError`** —
  `Resolver.resolve` has no case for a type declaration. It kills the shell
  rather than reporting an error.
* **`local ... in ... end` is not implemented.** It is how Standard ML
  declares a type locally, and it is what would widen §8.
* **A redeclared checked type checks the condition it used to have**, if a
  binding still holds the old one:

  ```sml
  type nat = int check i => i >= 0;
  val n: nat = 5;
  type nat = int check i => i >= 1;
  val m: nat = 0;                    (*) accepted; 0 is not >= 1
  ```

  Without the binding in the middle it is rejected, as it should be. A term
  names an alias by name, and `displacedTypes` recovers the type a name used
  to have; here it is consulted for a name that has not been displaced so much
  as redeclared. A condition is only what makes it visible.

## 11. A `case` branch that destructures is not checked

The check is put on a name the pattern binds, and a pattern that destructures
binds none that covers the whole value, so both of these admit the value:

```sml
case (~1, 2) of (n: nat, m) => n;
case {x = ~1, y = 2} of {x: nat, y: int} => x;
```

A function parameter of the same shape *is* checked, because there the check
goes on the parameter. `Core.AsPat` would give the whole value a name — `v as
(n, m)` is a `Core.NamedPat`, which is what the rewrite wants — but replacing
the branch's pattern with a fresh id, as the rewrite does, makes an
irrefutable branch of a refutable one. So it needs an irrefutability test
first, and that is a change of its own.

## 12. Two edits #239 itself needs

The issue text is now out of date in two places:

* It requires the `check` match to be exhaustive. What shipped does not: a
  non-exhaustive match has `| _ => false` appended, so `type z = int check 0 =>
  true` says that zero is the only value of the type.
* Its capture-semantics section is superseded by the closed-conditions rule
  (§8).

## Departures from the plan, for the record

* **A tuple's components are numbered from 1**, not 0: "component 1" is what
  `#1` selects, which seemed less surprising than numbering from 0.
* **A list element has no index.** `List.all`, which walks the list, does not
  offer one, so "element 1" is just "element".
* **A record modifier raises.** The plan first had it claiming nothing, with
  the receiving binding doing the checking; a modifier that assigns now claims
  the type it modifies, and that claim is checked where it is made.
* **"Cannot claim", not "cannot convert"**, a checked function type: one
  message serves a binding and a parameter as well as a conversion.
