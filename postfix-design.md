# Postfix Method Syntax — Design Spec

## Status

Working draft. Corresponds to GitHub issue #346.

---

## Goal

Add postfix method-call syntax so that a pipeline like:

```sml
employees
  .filter (fn e => e.deptno = 30)
  .map    (fn e => e.ename)
  .length ()
```

is valid Morel and desugars to ordinary prefix function calls. No new runtime
mechanism is introduced; this is pure elaborator sugar.

---

## The `self` Convention

A function participates in postfix dispatch if its first parameter is (or
contains) a binding named `self`. There are two forms:

- **Curried form**: the first curried parameter is named `self`.
- **Tuple form**: the first parameter is a tuple whose first element is
  named `self`.

```sml
(* curried: self is the first curried parameter *)
fun length (self : 'a list) = List.length self
fun filter (self : 'a list) pred = List.filter pred self
fun insert (self : 'a tree) key = Tree.insert self key

(* tuple: self is the first element of a tuple parameter *)
fun drop (self : 'a list, n) = List.drop (self, n)
fun substring (self : string, i, j) = String.substring (self, i, j)
```

Functions that do not satisfy either condition are **not** callable in
postfix form. This is an explicit opt-in by the function author.

### Desugaring rules

The desugaring depends on which form `f` uses. The two forms are
distinguished by the type of `f`: if its first parameter has a function
type `'a -> ...` the curried rule applies; if it has a tuple type
`'a * ... -> ...` the tuple-splicing rule applies.

| Postfix syntax | `f` is curried: `fun f self …` | `f` takes a tuple: `fun f (self, …)` |
|----------------|-------------------------------|--------------------------------------|
| `x.f`          | `#f x` — field projection, unchanged | — |
| `x.f ()`       | `f x` — zero extra args       | — (no meaningful tuple to splice into) |
| `x.f arg`      | `f x arg`                     | `f (x, arg)` |
| `x.f (a, b)`   | `f x (a, b)`                  | `f (x, a, b)` |

In the tuple-splicing form `x` is prepended to the argument list to form a
larger tuple. This allows existing standard-library functions whose first
tuple element is the receiver to be called postfix directly, without a
wrapper.

Chaining associates left:

```sml
x.f ().g (a, b)
(* is: g (f x) (a, b) *)
```

### Defining methods correctly

Because `x` becomes the **first** argument, the `self` parameter must be
first in the definition.

**Curried form** — useful when argument order needs to be rewritten (e.g.
higher-order functions where the function argument conventionally comes
first):

```sml
(* correct: self is first curried parameter *)
fun map (self : 'a list) f = List.map f self

(* postfix call *)
val doubled = [1, 2, 3].map (fn x => x * 2)
(* desugars to: map [1, 2, 3] (fn x => x * 2) *)
```

**Tuple form** — useful (and required) when wrapping an existing function
that already takes a tuple, so no new wrapper is needed:

```sml
(* existing function already takes a tuple with list first *)
(* List.drop : 'a list * int -> 'a list *)

(* postfix call — no wrapper needed *)
val rest = [1, 2, 3].drop 1
(* desugars to: List.drop ([1, 2, 3], 1) *)
```

Note that the curried wrapper form flips the conventional `List.map`
argument order. Tuple-form functions with the receiver first are directly
usable without a wrapper.

### ISSUE A — map example in the GitHub issue is wrong

The issue shows:

```sml
fun map f (self : 'a list) = List.map f self   (* WRONG: self is second *)
```

and claims the postfix call `[1,2,3].map (fn x => x * 2)` is equivalent to
`map (fn x => x * 2) [1,2,3]`.

Both claims are inconsistent with the "self must be first" rule. The correct
definition for postfix use is:

```sml
fun map (self : 'a list) f = List.map f self
```

and the desugaring of `[1,2,3].map (fn x => x * 2)` is
`map [1,2,3] (fn x => x * 2)`.

**Resolution needed**: fix the spec and all examples to have `self` first.

---

## Syntax

### Disambiguation from field projection

| Expression    | Interpretation  |
|---------------|-----------------|
| `x.f`         | field projection: `#f x` |
| `x.f ()`      | method call: `f x` |
| `x.f arg`     | method call: `f x arg` |
| `x.f (a, b)`  | method call: `f x (a, b)` |

The presence of a following argument list (including bare `()`) distinguishes
a method call from a field projection in the common case.

**Ambiguous case — function-valued fields.** If a record has a field `f` whose
type is a function type, then `x.f ()` is currently valid Morel meaning
`(#f x) ()` — project the field, then apply it to `()`. Under the new rules,
`x.f ()` is always parsed as a method call. Resolution: **method takes
priority**. `x.f (args)` is always a method call; to apply a function-valued
field the programmer must parenthesise the projection: `(x.f) (args)`. This is
a small breaking change for existing code that calls function-valued fields via
dot notation, but it is the simpler rule to implement.

Qualified module paths in postfix position (`x.M.f ()`) are **not** valid.
Use prefix form `M.f x` instead.

### Precedence

Dot-call binds tighter than ordinary function application:

```sml
xs.map f
(* parses as: (xs.map) f *)
(* desugars to: map xs f *)
```

### Chained projections and calls

Steps are processed strictly left to right. Each `.f` or `.f (args)` applies
to the result of everything to its left:

```sml
dept.employees.length ()
(* dept.employees  =>  #employees dept           (field projection) *)
(* .length ()      =>  length (#employees dept)  (method call)      *)
```

---

## Elaboration

### Step 1 — Parse

A primary expression `e` followed by `.f` and then an argument list
(including bare `()`) is parsed as a new AST node `PostfixApp(e, f, args)`.
A bare `e.f` with no following argument list remains a field projection (the
existing `apply(recordSelector("f"), e)`).

### Step 2 — Resolve `f`

Collect the set of candidate bindings for the method name `f`. There are three
strategies for how broadly to search; they are not mutually exclusive:

**Strategy A — Scope-based (current environment only).** Collect all
unqualified bindings named `f` in the current environment whose first parameter
is named `self`. A method is a candidate only if the user has brought it into
scope, either by defining a wrapper directly or by `open`-ing a structure.
Morel has no `use` keyword, so there is no way to bring a single name from a
structure into scope without `open`-ing the whole structure.

**Strategy B — Type-linked structures.** Each built-in type has an associated
structure (`'a list` → `List`, `'a bag` → `Bag`, `string` → `String`, etc.).
When the type of `e` is known, automatically search that structure for a
function named `f` whose first parameter type matches, without requiring any
`open`. For user-defined types there is no linked structure, so strategy A or C
applies.

**Strategy C — Implicit global import for postfix.** All functions in the
standard library whose first parameter is named `self` are always candidates
for postfix dispatch regardless of what is in scope, as if every structure were
silently opened for method-lookup purposes only. This does not affect the prefix
namespace.

In all strategies, disambiguation between multiple candidates is
**type-directed**: the type of `e` is unified against each candidate's `self`
parameter type, and the unique match is selected. This is the same mechanism
used by Morel's existing overloading (`over`/`inst`). For example, if both a
`List.length` wrapper and a `Bag.length` wrapper named `length` are candidates
and `x : int list`, unification immediately selects the list version with no
ambiguity.

### Step 3 — Disambiguate

- **Zero candidates** — type error: no function named `f` with a `self`
  first parameter is in scope.
- **One candidate** — proceed; unify the type of `e` with the type of `f`'s
  `self` parameter.
- **Multiple candidates** — attempt to unify the type of `e` with each
  candidate's `self` parameter type. If exactly one succeeds, use it. If
  zero or more than one succeed, it is a compile-time ambiguity error; the
  programmer must use prefix form.

### Step 4 — Desugar

Replace `PostfixApp(e, f, args)` depending on which form `f` uses:

- **Curried** (`fun f self …`): `apply(apply(f, e), args)`, or just
  `apply(f, e)` when `args` is `()`.
- **Tuple-splicing** (`fun f (self, …)`): `apply(f, tuple(e, args))`,
  where `e` is prepended to `args` (the remaining tuple elements) to
  form a single larger tuple passed to `f`.

---

## Tracking `self` through the Pipeline

The elaborator needs to know, for each function binding in scope, whether
the function's first parameter is named `self`. Two sub-issues:

### Single-clause functions — already preserved

`TypeResolver.toValBind` uses the original `patList` directly for
single-clause `fun` declarations, so the `self` name survives in either
`Core.IdPat.name` (curried form) or `Core.TuplePat`'s first element name
(tuple form) inside the resulting `Core.Fn`.

### ISSUE B — Multi-clause functions lose parameter names

For multi-clause `fun` declarations `toValBind` synthesises fresh names
`v0, v1, ...` for the parameters:

```java
// TypeResolver.toValBind — multi-clause path
for (Ast.Pat var : Lists.reverse(vars)) {
    exp = ast.fn(pos, ast.match(pos, var, exp));
}
```

As a result a function like:

```sml
fun length [] = 0 | length (_ :: xs) = 1 + length xs
```

cannot use postfix dispatch even if you name the first parameter `self` in
every clause, because the compiled form loses the name.

**Options:**
1. Document as a known limitation: only single-clause functions are eligible.
2. Before synthesising `v0`, check whether every clause names the first
   parameter `self`; if so, use `self` as the synthesised name.

Option 2 is cleaner for users. Implementation cost is low: one extra check
in `toValBind`.

### Recording `self` in `Binding`

The elaborator must be able to ask "does function `f` have `self` as its
first parameter, and in which form?" at the point of a postfix call. Options:

1. **Inspect `Core.Fn` at elaboration time** — if the binding's expression
   is a `Core.Fn` whose first parameter pattern is either a `Core.IdPat`
   named `"self"` (curried) or a `Core.TuplePat` whose first element is
   named `"self"` (tuple), it qualifies. Works without any schema change,
   but requires the binding to carry the full expression (it is nullable
   today).
2. **Add a `SelfKind` enum to `Binding`** — values `NONE`, `CURRIED`,
   `TUPLE`, set during type resolution. Makes the invariant explicit without
   requiring re-inspection of the expression.
3. **Side-map** — maintain a separate `Map<String, SelfKind>` in the
   elaboration environment.

Option 2 is the most explicit and least fragile. It requires a small schema
change to `Binding` but makes both the presence and form of `self` visible.

---

## Interaction with Overloading

Morel's existing overloading mechanism (`over` / `inst`) is orthogonal.
An overloaded function can participate in postfix dispatch if and only if the
specific instance selected by the type-checker has `self` as its first
parameter. Disambiguation of overloaded instances proceeds as usual (by
type), after which the `self` check is applied to the selected instance.

---

## Non-goals

- **Mutation / mutable receivers.** Morel is purely functional.
- **Inheritance or dynamic dispatch.** No vtable or runtime mechanism.
- **Automatic wrapping of existing functions.** `List.length` is not callable
  as `xs.length ()` unless a wrapper `fun length (self : 'a list) = ...`
  is defined.
- **Qualified paths in postfix position.** `xs.List.length ()` is not valid.
- **Re-sugaring in output.** The pretty-printer always prints in prefix form.
  Re-sugaring is deferred to a separate issue.

---

## Open Questions

### OQ-1 — Should `self` be reserved?

**Proposal:** No. Keep `self` as a conventional name, not a keyword.
The syntactic distinction between `x.f` (projection) and `x.f ()` (call)
is clean without any reserved words. Reserving `self` would be a breaking
change for existing code.

### OQ-2 — Re-sugaring in output

Should the pretty-printer re-sugar applications back to postfix form when the
callee has a `self` first parameter? Deferred; not needed for the feature to
be useful.

### OQ-3 — Which candidate-collection strategy?

Strategies A, B, and C (see Elaboration § Step 2) are not mutually exclusive.
A reasonable default is **B + A**: for built-in types use the linked structure
automatically; for user-defined functions fall back to whatever is in scope.
Strategy C (implicit global import) is the most convenient but risks surprising
shadowing if the user defines a function whose name collides with a standard
library method.

The strategies also interact with `open`: if the user `open List` and `List`
defines a `length` with `self` first, strategy A finds it. Strategy B would
find `List.length` directly from the type without needing `open`. They agree
on the result but differ in whether `open` is required.

### OQ-5 — `self` in a non-first position

The spec is clear: only the **first** parameter may be named `self`.
Functions where `self` appears in a later position are not eligible:

```sml
(* NOT eligible — self is third *)
fun foldLeft f init (self : 'a list) = ...
```

---

## Built-in candidates (first argument matches primary type)

Functions where the primary type of the structure appears as the first
argument — either as the sole curried argument or as the first element of a
tuple argument. These are directly callable postfix via the `self` convention
without any wrapper function:

- **Unary** (`f xs`): first curried parameter is the receiver → `xs.f ()`
- **Tuple, receiver first** (`f (xs, …)`): first tuple element is the
  receiver → `xs.f arg` or `xs.f (a, b)` with the remaining elements as
  the argument (scalar if n=2, tuple if n>2)

Functions marked ⚠ have the primary type first but the natural *receiver* is
actually the second argument; they are listed for completeness but should not
be promoted as idiomatic methods.

Infix operators (`@`, `^`, `>=`, etc.) are excluded.

### Bag (`'a bag`)

| Prefix | Postfix |
|--------|---------|
| `getItem xs` | `xs.getItem ()` |
| `hd xs` | `xs.hd ()` |
| `length xs` | `xs.length ()` |
| `null xs` | `xs.null ()` |
| `tl xs` | `xs.tl ()` |
| `toList xs` | `xs.toList ()` |
| `drop (xs, n)` | `xs.drop n` |
| `nth (xs, n)` | `xs.nth n` |
| `take (xs, n)` | `xs.take n` |

### Bool (`bool`)

| Prefix | Postfix |
|--------|---------|
| `not b` | `b.not ()` |
| `toString b` | `b.toString ()` |

### Char (`char`)

| Prefix | Postfix |
|--------|---------|
| `isAlpha c` | `c.isAlpha ()` |
| `isAlphaNum c` | `c.isAlphaNum ()` |
| `isAscii c` | `c.isAscii ()` |
| `isCntrl c` | `c.isCntrl ()` |
| `isDigit c` | `c.isDigit ()` |
| `isGraph c` | `c.isGraph ()` |
| `isHexDigit c` | `c.isHexDigit ()` |
| `isLower c` | `c.isLower ()` |
| `isPrint c` | `c.isPrint ()` |
| `isPunct c` | `c.isPunct ()` |
| `isSpace c` | `c.isSpace ()` |
| `isUpper c` | `c.isUpper ()` |
| `ord c` | `c.ord ()` |
| `pred c` | `c.pred ()` |
| `succ c` | `c.succ ()` |
| `toCString c` | `c.toCString ()` |
| `toLower c` | `c.toLower ()` |
| `toString c` | `c.toString ()` |
| `toUpper c` | `c.toUpper ()` |
| `compare (c, c2)` | `c.compare c2` |

### Either (`('a,'b) either`)

| Prefix | Postfix |
|--------|---------|
| `asLeft e` | `e.asLeft ()` |
| `asRight e` | `e.asRight ()` |
| `isLeft e` | `e.isLeft ()` |
| `isRight e` | `e.isRight ()` |
| `proj e` | `e.proj ()` |

### Int (`int`)

| Prefix | Postfix |
|--------|---------|
| `abs n` | `n.abs ()` |
| `sign n` | `n.sign ()` |
| `toString n` | `n.toString ()` |
| `compare (n, n2)` | `n.compare n2` |
| `div (n, n2)` | `n.div n2` |
| `max (n, n2)` | `n.max n2` |
| `min (n, n2)` | `n.min n2` |
| `mod (n, n2)` | `n.mod n2` |
| `quot (n, n2)` | `n.quot n2` |
| `rem (n, n2)` | `n.rem n2` |
| `sameSign (n, n2)` | `n.sameSign n2` |

### List (`'a list`)

| Prefix | Postfix |
|--------|---------|
| `getItem xs` | `xs.getItem ()` |
| `hd xs` | `xs.hd ()` |
| `last xs` | `xs.last ()` |
| `length xs` | `xs.length ()` |
| `null xs` | `xs.null ()` |
| `rev xs` | `xs.rev ()` |
| `tl xs` | `xs.tl ()` |
| `drop (xs, n)` | `xs.drop n` |
| `nth (xs, n)` | `xs.nth n` |
| `revAppend (xs, ys)` | `xs.revAppend ys` |
| `take (xs, n)` | `xs.take n` |

### Option (`'a option`)

| Prefix | Postfix |
|--------|---------|
| `isSome opt` | `opt.isSome ()` |
| `valOf opt` | `opt.valOf ()` |
| `getOpt (opt, d)` | `opt.getOpt d` |

### Real (`real`)

| Prefix | Postfix |
|--------|---------|
| `abs x` | `x.abs ()` |
| `ceil x` | `x.ceil ()` |
| `checkFloat x` | `x.checkFloat ()` |
| `floor x` | `x.floor ()` |
| `isFinite x` | `x.isFinite ()` |
| `isNan x` | `x.isNan ()` |
| `isNormal x` | `x.isNormal ()` |
| `realCeil x` | `x.realCeil ()` |
| `realFloor x` | `x.realFloor ()` |
| `realMod x` | `x.realMod ()` |
| `realRound x` | `x.realRound ()` |
| `realTrunc x` | `x.realTrunc ()` |
| `round x` | `x.round ()` |
| `sign x` | `x.sign ()` |
| `signBit x` | `x.signBit ()` |
| `split x` | `x.split ()` |
| `toManExp x` | `x.toManExp ()` |
| `toString x` | `x.toString ()` |
| `trunc x` | `x.trunc ()` |
| `compare (x, y)` | `x.compare y` |
| `copySign (x, y)` | `x.copySign y` |
| `max (x, y)` | `x.max y` |
| `min (x, y)` | `x.min y` |
| `rem (x, y)` | `x.rem y` |
| `sameSign (x, y)` | `x.sameSign y` |
| `unordered (x, y)` | `x.unordered y` |

### Relational (`'a bag`)

| Prefix | Postfix |
|--------|---------|
| `count xs` | `xs.count ()` |
| `empty xs` | `xs.empty ()` |
| `max xs` | `xs.max ()` |
| `min xs` | `xs.min ()` |
| `nonEmpty xs` | `xs.nonEmpty ()` |
| `only xs` | `xs.only ()` |
| `sum xs` | `xs.sum ()` |
| `iterate xs f` | `xs.iterate f` |

### String (`string`)

| Prefix | Postfix |
|--------|---------|
| `explode s` | `s.explode ()` |
| `size s` | `s.size ()` |
| `compare (s, s2)` | `s.compare s2` |
| `extract (s, i, jOpt)` | `s.extract (i, jOpt)` |
| `sub (s, i)` | `s.sub i` |
| `substring (s, i, j)` | `s.substring (i, j)` |
| `isPrefix s1 s2` ⚠ | `s1.isPrefix s2` |
| `isSubstring s1 s2` ⚠ | `s1.isSubstring s2` |
| `isSuffix s1 s2` ⚠ | `s1.isSuffix s2` |
| `concatWith sep l` ⚠ | `sep.concatWith l` |

### Vector (`'a vector`)

| Prefix | Postfix |
|--------|---------|
| `length v` | `v.length ()` |
| `sub (v, i)` | `v.sub i` |

### Variant (`variant`)

| Prefix | Postfix |
|--------|---------|
| `print v` | `v.print ()` |

### Notes

**⚠ String functions with reversed receiver**: `isPrefix s1 s2`, `isSubstring
s1 s2`, and `isSuffix s1 s2` have the *pattern* as the first argument and the
*container* string as the second. In postfix form `s1.isPrefix s2` reads as
"does s1 have s2 as a prefix?" — the opposite of the natural reading. Similarly
`concatWith sep l` has the separator first. These should not be exposed as
methods under their current names. Reversed-argument wrappers with clearer
names are provided in `String2`: `startsWith`, `endsWith`, `contains`, and
`concatWith` (with the list as receiver).

**Bool.not**: `b.not ()` is valid under the rules but offers no readability
benefit over the prefix `not b`. Low value; not recommended for the standard
method set.

---

## Proposed method-wrapper structures

Some standard library functions have the collection as a *non-first* argument
— typically the higher-order functions (`map`, `filter`, `foldl`, etc.) where
the function argument comes first by convention. These cannot be called postfix
directly via the tuple-splicing rule; a wrapper with `self` as the first
curried parameter is required.

New structures (`List2`, `Bag2`, `String2`, `Option2`, `Vector2`) provide
such wrappers. Each wrapper reorders the arguments and delegates to the
corresponding standard function. Functions whose receiver is already the first
tuple element (e.g. `List.drop`, `List.nth`) are directly callable postfix
and do **not** need entries here.

### List2 (`'a list`)

| Function | Delegates to | Postfix example |
|----------|-------------|-----------------|
| `all self pred` | `List.all pred self` | `xs.all pred` |
| `app self f` | `List.app f self` | `xs.app f` |
| `exists self pred` | `List.exists pred self` | `xs.exists pred` |
| `filter self pred` | `List.filter pred self` | `xs.filter pred` |
| `find self pred` | `List.find pred self` | `xs.find pred` |
| `foldl self f init` | `List.foldl f init self` | `xs.foldl f init` |
| `foldr self f init` | `List.foldr f init self` | `xs.foldr f init` |
| `map self f` | `List.map f self` | `xs.map f` |
| `mapPartial self f` | `List.mapPartial f self` | `xs.mapPartial f` |
| `mapi self f` | `List.mapi f self` | `xs.mapi f` |
| `partition self pred` | `List.partition pred self` | `xs.partition pred` |

### Bag2 (`'a bag`)

| Function | Delegates to | Postfix example |
|----------|-------------|-----------------|
| `all self pred` | `Bag.all pred self` | `xs.all pred` |
| `app self f` | `Bag.app f self` | `xs.app f` |
| `exists self pred` | `Bag.exists pred self` | `xs.exists pred` |
| `filter self pred` | `Bag.filter pred self` | `xs.filter pred` |
| `find self pred` | `Bag.find pred self` | `xs.find pred` |
| `fold self f init` | `Bag.fold f init self` | `xs.fold f init` |
| `map self f` | `Bag.map f self` | `xs.map f` |
| `mapPartial self f` | `Bag.mapPartial f self` | `xs.mapPartial f` |
| `partition self pred` | `Bag.partition pred self` | `xs.partition pred` |

### String2 (`string`, and `string list` for `concatWith`)

| Function | Delegates to | Postfix example |
|----------|-------------|-----------------|
| `fields self pred` | `String.fields pred self` | `s.fields pred` |
| `map self f` | `String.map f self` | `s.map f` |
| `tokens self pred` | `String.tokens pred self` | `s.tokens pred` |
| `translate self f` | `String.translate f self` | `s.translate f` |
| `startsWith self prefix` | `String.isPrefix prefix self` | `s.startsWith prefix` |
| `endsWith self suffix` | `String.isSuffix suffix self` | `s.endsWith suffix` |
| `contains self sub` | `String.isSubstring sub self` | `s.contains sub` |
| `concatWith (self : string list) sep` | `String.concatWith sep self` | `strs.concatWith sep` |

### Option2 (`'a option`)

| Function | Delegates to | Postfix example |
|----------|-------------|-----------------|
| `app self f` | `Option.app f self` | `opt.app f` |
| `map self f` | `Option.map f self` | `opt.map f` |
| `mapPartial self f` | `Option.mapPartial f self` | `opt.mapPartial f` |

### Vector2 (`'a vector`)

| Function | Delegates to | Postfix example |
|----------|-------------|-----------------|
| `all self pred` | `Vector.all pred self` | `v.all pred` |
| `app self f` | `Vector.app f self` | `v.app f` |
| `appi self f` | `Vector.appi f self` | `v.appi f` |
| `exists self pred` | `Vector.exists pred self` | `v.exists pred` |
| `find self pred` | `Vector.find pred self` | `v.find pred` |
| `findi self pred` | `Vector.findi pred self` | `v.findi pred` |
| `foldl self f init` | `Vector.foldl f init self` | `v.foldl f init` |
| `foldli self f init` | `Vector.foldli f init self` | `v.foldli f init` |
| `foldr self f init` | `Vector.foldr f init self` | `v.foldr f init` |
| `foldri self f init` | `Vector.foldri f init self` | `v.foldri f init` |
| `map self f` | `Vector.map f self` | `v.map f` |
| `mapi self f` | `Vector.mapi f self` | `v.mapi f` |

---

## Work Items

| # | Area | Task |
|---|------|------|
| 1 | Spec | Fix `map` example (ISSUE A): `self` must be first param |
| 2 | Parser | Add `PostfixApp` AST node |
| 3 | Parser | LL(2) lookahead in `expression9()` to distinguish `e.f` from `e.f (args)` |
| 4 | AST/Binding | Add `SelfKind` enum (`NONE`/`CURRIED`/`TUPLE`) to `Binding` |
| 5 | TypeResolver | Set `SelfKind` when processing `fun` declarations (curried vs tuple first param) |
| 6 | TypeResolver | Handle `PostfixApp`: look up candidates, disambiguate, desugar |
| 7 | TypeResolver | Fix multi-clause parameter name synthesis (ISSUE B) |
| 8 | Tests | Add tests in `simple.smli` or a new `postfix.smli` |
