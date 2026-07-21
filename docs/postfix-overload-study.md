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

# Feasibility study: unifying postfix calls with overloading

*Status: study only (no code changes). Companion to the
hydromatic/morel#426 qualified-types work.*

## Question

Can *all* logic for resolving postfix ("method") calls be expressed in terms
of the overloading machinery, so that:

* the environment contains only a set of identifiers, each carrying a bit
  saying whether it may be used in postfix position; and
* there is **no** hard-coded list of "built-in functions that support postfix
  calls" anywhere?

Short answer: **yes, this is feasible, and it is the natural end-state of the
`over`/`inst` design** — but it depends on the deferred/qualified overload
resolution added for #426, and it requires reworking one parse-time decision.
This document maps what exists today, the one genuine obstacle (the parser has
no environment), and a phased path.

## How postfix calls resolve today

A postfix call `x.f a` (sugar for `f (x, a)` or `f x a`, with `x` spliced in as
the first argument) is gated in three places, all keyed off a **single
hard-coded list**.

### 1. The master list: `BuiltIn.BY_METHOD_NAME`

`compile/BuiltIn.java` builds an `ImmutableMultimap<String, BuiltIn>` once, at
class-load time, from every enum constant whose `method` flag is `true`:

```java
public static final ImmutableMultimap<String, BuiltIn> BY_METHOD_NAME;
// ...
for (BuiltIn builtIn : values()) {
  if (builtIn.method) {
    methodBuilder.put(builtIn.mlName, builtIn);
  }
}
```

The `method` flag is set per constant (e.g. `LIST_LENGTH("List", "length",
true, ...)`) and corresponds to the `[@@method]` attribute in the `.sig`
files. There are ~70 such built-ins (List/Bag/Vector/String/Option/Real/Date/
Time/Relational/…).

### 2. Parse-time gate: `MorelParser.jj`

The grammar must decide, while parsing `e . name atom`, whether this is a
postfix call or a field/module access. For a bare-identifier receiver it
consults the list directly:

```javacc
LOOKAHEAD({
    isAtomStart()
    && !isModule(e)
    && (getToken(1).kind == LPAREN
        ? !isId(e) || BuiltIn.BY_METHOD_NAME.containsKey(fieldName)
        : !isId(e) && getToken(2).kind != DOT)
})
arg = atom()
{ e = ast.postfixApp(pos(), e, fieldName, arg); }
```

This is the load-bearing hard-coded reference: the parser distinguishes
`String.size "x"` (module access) from `list.length ()` (postfix method) partly
by asking whether `fieldName` is a known method.

### 3. Resolve-time dispatch: `TypeResolver.deducePostfixAppType`

`deducePostfixAppType` collects candidates from `BY_METHOD_NAME.get(name)` plus
user functions, decides projection-vs-method, and desugars to an ordinary
application (`x.f ()` → `f x`, `x.f y` → `f (x, y)` / `f x y`). Receiver-type
hints for chained calls go through `applyReceiverTypeHint` and `BY_STRUCTURE`
(see commit *"Fix overload resolution for postfix compare on Time and Date
values"*).

### 4. User-defined methods: `methodNames`

For user code, a function whose first parameter (or first tuple element) is
named `self` is postfix-eligible; `TypeResolver.recognizeFunBindMethod`
records these names in a `Set<String> methodNames` (with `methodTupleNames`
for the tuple-splicing form). This is already an *environment-ish* per-name
bit — just a private set in the resolver rather than a property of the
`Binding`.

## The essential observation

`x.f` is overload resolution keyed on the type of the receiver `x`. A postfix
built-in such as `length` is overloaded across List, Bag, Vector and String;
choosing the right one from the receiver's type is exactly what `over`/`inst`
resolution does when it chooses an instance from the argument's type. The
paper's `demo` example (`#426`) — one name, several argument shapes, resolved
per call site — is the same shape as `length` over several collection types.

So the target design is:

* Every method-eligible built-in becomes a genuine **overloaded identifier**
  (an `over` name with one `inst` per structure). `length` is one name with
  instances `'a list -> int`, `'a bag -> int`, `'a vector -> int`,
  `string -> int`, …
* Each `Binding` carries a **`postfix` bit** (replacing the private
  `methodNames`/`methodTupleNames` sets and the `method` enum flag). The
  environment is then "a set of identifiers, some flagged postfix-callable",
  exactly as desired.
* `x.f a` desugars to an application of the overloaded name `f` with `x`
  spliced in as the first argument, and the existing overload-resolution
  constraint machinery selects the instance from `x`'s type — **the same code
  path as `over`/`inst`**. `BY_METHOD_NAME`, `BY_STRUCTURE`,
  `applyReceiverTypeHint`, `methodNames` and the `method` flag all disappear
  from the dispatch logic.

## Why this depends on #426

Today's overload resolution can select an instance only when the argument type
is already concrete. A postfix receiver is often *not* concrete — e.g. inside

```sml
fun countTwice self = self.length () + self.length ()
```

the receiver's type is a type variable. Resolving `self.length` therefore needs
the **deferred/qualified** resolution introduced for #426: record a predicate
`{length : 'a -> int}` and discharge it at the call site (or generalize it into
the enclosing function's type). Without qualified types, unifying postfix onto
overloading would regress polymorphic method use; with them, it falls out for
free. This is why the unification rides on the #426 milestones rather than
standing alone.

## The one genuine obstacle: the parser has no environment

The parse-time gate (§2) needs to know whether `x.f (...)` is a postfix call or
a record/module field access, but the parser runs before names are resolved and
has no access to the environment or its postfix bits. Two ways out:

* **(A) Resolve the ambiguity after parsing (recommended).** Parse `e . name
  arg` uniformly into a single "dot-application" node and let `TypeResolver`
  decide by type: if the receiver names a module → qualified reference; if its
  type is a record with field `name` → projection; otherwise → postfix method
  call, resolved via the overload constraint machinery. `deducePostfixAppType`
  already performs most of this three-way decision; the change is to *always*
  route through it rather than pre-committing in the grammar. This removes the
  parser's dependence on `BY_METHOD_NAME` entirely.

* **(B) Feed the parser an environment-derived hint.** Keep a parse-time set of
  postfix-eligible names, but source it from the environment's postfix bits
  instead of the hard-coded `BY_METHOD_NAME`. Smaller change, but it keeps a
  parse/resolve split of the same decision and needs the set threaded into the
  parser — less clean than (A).

Approach (A) is the one that actually achieves the stated goal ("no hard-coded
list anywhere"): the grammar stops asking *what* `name` is and defers entirely
to the type resolver.

## Verdict and phased path

Feasible and desirable. Recommended sequencing:

1. **#426 milestone 1 (done):** qualified-type inference, printing and errors.
   Establishes the constraint/predicate representation the postfix path will
   reuse.
2. **#426 milestone 2:** evaluate qualified-typed values (dictionary passing).
   Method dispatch on an abstract receiver needs this to *run*, not just
   type-check.
3. **Postfix step 1 — data model:** add a `postfix` bit to `Binding`; populate
   it from the `.sig` `[@@method]` attribute (so the surface metadata is
   unchanged) and from user `self`-parameter functions. Keep `BY_METHOD_NAME`
   as a temporary shim.
4. **Postfix step 2 — make built-in methods overloaded:** register each
   method-eligible built-in as an `over` name with one `inst` per structure, so
   dispatch is by receiver type through the #426 machinery.
5. **Postfix step 3 — parser:** switch the grammar to approach (A) (uniform
   dot-application node); delete `BY_METHOD_NAME`, `BY_STRUCTURE`,
   `applyReceiverTypeHint`, `methodNames`/`methodTupleNames`, and the `method`
   enum flag from the dispatch path.

## Risks

* **Parser ambiguity.** `x.f (...)` vs module access vs projection currently
  leans on the list at parse time. Deferring to the resolver (A) must preserve
  existing syntax (`String.size "x"`, `Sys.set ("x", 1)`, record projection
  `r.field`). `deducePostfixAppType` already handles these cases, which de-risks
  the move, but the grammar/lookahead rework is the most delicate part.
* **Built-in surface area.** Turning ~70 `method` built-ins into `over`/`inst`
  families touches `BuiltIn`, `Codes` and the `.sig`/doc lint. The
  `LintTest#testSignatures` cross-check between `.sig` and `BuiltIn` must keep
  passing; the `[@@method]` attribute becomes the source of the `postfix` bit.
* **Performance.** Postfix dispatch becomes constraint solving rather than a
  direct multimap lookup. For the common concrete-receiver case the constraint
  resolves immediately (one candidate), so the extra cost is small, but it is
  worth measuring on method-heavy scripts.
* **Ordering/ambiguity of overloads.** Some current methods share a name across
  unrelated structures; as `inst`s they must have distinguishable argument
  types (the `over`/`inst` well-formedness rule), which a few built-ins may
  need adjusted signatures to satisfy.

## Summary

The hard-coded `BuiltIn.BY_METHOD_NAME` list, the `method` flag, and the
`methodNames` set are three encodings of one idea — "this name dispatches on
its first argument's type" — which is precisely overloading. Once #426 gives
overloading deferred resolution, postfix method calls can be lowered to
overloaded applications, the environment reduces to identifiers plus a postfix
bit, and the hard-coded list can be deleted. The only real work beyond the
`over`/`inst` migration is moving the parser's postfix/field decision from a
grammar lookahead into the type resolver.
