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

# Plan: Issue #338 — `range` datatype and structure

## Overview

Add a `'a range` datatype and `Range` structure to Morel. The goal is to
represent ranges like `x > 0 andalso x < 10` and `x = 5`, and sets of
ranges. Based on Guava's `Range` and `RangeSet` types.

A secondary goal is to replace uses of `List.tabulate` and `Bag.tabulate`
in generated code with `Range.toList` / `Range.toBag`.

---

## The `range` datatype

```sml
datatype 'a range =
    AT_LEAST of 'a       (* AT_LEAST 5:         x >= 5             *)
  | AT_MOST of 'a        (* AT_MOST 5:           x <= 5             *)
  | CLOSED of 'a * 'a    (* CLOSED (5, 10):      x >= 5 andalso x <= 10  *)
  | CLOSED_OPEN of 'a * 'a (* CLOSED_OPEN (5, 10): x >= 5 andalso x < 10  *)
  | GREATER_THAN of 'a   (* GREATER_THAN 5:      x > 5              *)
  | LESS_THAN of 'a      (* LESS_THAN 5:         x < 5              *)
  | OPEN of 'a * 'a      (* OPEN (5, 10):        x > 5 andalso x < 10    *)
  | OPEN_CLOSED of 'a * 'a (* OPEN_CLOSED (5, 10): x > 5 andalso x <= 10  *)
  | POINT of 'a          (* POINT 5:             x = 5              *)
```

Naming follows Guava / standard math conventions:
- CLOSED = inclusive endpoint `[a, b]`
- OPEN = exclusive endpoint `(a, b)`

---

## Structure functions

All functions are polymorphic over `'a`. Ordering and discreteness are
**implicit** — derived from the concrete type at compilation time via the
`Typed` interface (same trick as `Relational.compare` and `Variant.parse`).
No explicit comparator or discrete-domain argument is needed.

| Function   | Type                        | Notes                                      |
|------------|-----------------------------|--------------------------------------------|
| `isMember` | `'a -> 'a range -> bool`    | True if value is within range              |
| `normalize`| `'a range list -> 'a range list` | Sort and merge overlapping/adjacent ranges |
| `toList`   | `'a range list -> 'a list`  | Enumerate values; requires discrete type, bounded ranges |
| `toBag`    | `'a range list -> 'a bag`   | As `toList` but returns a bag              |

`toList` and `toBag` raise an exception if:
- The element type is not discrete (e.g., `real`), or
- Any range in the list is unbounded (`AT_LEAST`, `AT_MOST`, `GREATER_THAN`,
  `LESS_THAN`).

---

## Internal design: implicit ordering and discreteness via `Typed`

Each function's `Applicable` implements `Codes.Typed`. When `Core.toBuiltIn()`
resolves a built-in literal, it calls `withType(typeSystem, type)` if the
`Applicable` is `Typed`. This happens at **compilation time**, not runtime.

### For `isMember` and `normalize`

In `withType`, extract the element type `'a` from `'a range` (or
`'a range list`), then call:

```java
Comparators.comparatorFor(typeSystem, elementType)
```

Bake the resulting `Comparator` into the returned `Applicable`.

### For `toList` and `toBag`

In `withType`, additionally build a **discrete domain** via a new utility:

```java
Discretes.discreteFor(typeSystem, elementType)
// returns: Discrete  (throws if type is not discrete)
```

This is analogous to `Comparators.comparatorFor`. Bake both the comparator
and the discrete domain into the returned `Applicable`.

`Discrete` is an internal interface (not user-facing):

```java
interface Discrete {
  Comparator<Object> comparator();
  Optional<Object> next(Object v);      // successor; empty() at type maximum
  Optional<Object> minValue();          // empty() if type is unbounded below
}
```

`minValue()` is needed so that `toList`/`toBag` can enumerate ranges that
have no explicit lower bound but are nonetheless finite — for example,
`LESS_THAN #"e"` on `char` enumerates from `#"\000"` up to `#"d"`.

`Discretes.discreteFor` behaviour by type:

| Type             | `next(v)`                                    | `minValue()`        |
|------------------|----------------------------------------------|---------------------|
| `int`            | `Optional.of(v + 1)` (always present)        | `empty()` (unbounded below) |
| `char`           | successor by ordinal; `empty()` at `maxChar` | `SOME #"\000"`      |
| `bool`           | `false → SOME true`, `true → empty()`        | `SOME false`        |
| `unit`           | `empty()`                                    | `SOME ()`           |
| Finite sum types | enumerate constructors in declaration order  | `SOME` (first constructor) |
| `real`, `string`, infinite types | throw "not a discrete type" | n/a  |

`toList`/`toBag` raise an exception for ranges that are unbounded on the
lower side **and** `minValue()` is `empty()` (e.g., `LESS_THAN 5 : int range`).
For types with a finite minimum (e.g., `LESS_THAN #"e" : char range`),
enumeration starts from `minValue()`. `POINT v` returns the singleton `[v]`.

---

## Files to modify

### 1. `src/main/java/net/hydromatic/morel/eval/Discrete.java` *(done)*

Top-level public interface, analogous to how `Comparator` is separate from
`Collections`. Package `net.hydromatic.morel.eval`.

```java
public interface Discrete {
  Comparator<Object> comparator();
  Optional<Object> next(Object v);   // successor; empty() at type maximum
  Optional<Object> minValue();       // empty() if unbounded below (e.g. int)
}
```

### 2. `src/main/java/net/hydromatic/morel/eval/Discretes.java` *(done)*

Top-level factory/utility class, analogous to `Comparators.java`.
Package `net.hydromatic.morel.eval`.

```java
public class Discretes {
  /** Returns a Discrete domain for the given type, or throws if not discrete. */
  public static Discrete discreteFor(TypeSystem ts, Type type) { ... }
}
```

### 3. `src/main/java/net/hydromatic/morel/compile/BuiltIn.java` *(done)*

**Add `Datatype.RANGE`** (alphabetically after `PSEUDO_LIST`):

```java
RANGE(
    "Range",
    "range",
    false,
    1,  // one type parameter: 'a
    h -> h.tyCon(Constructor.RANGE_AT_LEAST)
         .tyCon(Constructor.RANGE_AT_MOST)
         .tyCon(Constructor.RANGE_CLOSED)
         .tyCon(Constructor.RANGE_CLOSED_OPEN)
         .tyCon(Constructor.RANGE_GREATER_THAN)
         .tyCon(Constructor.RANGE_LESS_THAN)
         .tyCon(Constructor.RANGE_OPEN)
         .tyCon(Constructor.RANGE_OPEN_CLOSED)
         .tyCon(Constructor.RANGE_POINT)),
```

**Add `Constructor.RANGE_*`** (alphabetically in the sorted region):

```java
// Unary constructors — argument type 'a
RANGE_AT_LEAST(Datatype.RANGE, "AT_LEAST", h -> h.get(0)),
RANGE_AT_MOST(Datatype.RANGE, "AT_MOST", h -> h.get(0)),
RANGE_GREATER_THAN(Datatype.RANGE, "GREATER_THAN", h -> h.get(0)),
RANGE_LESS_THAN(Datatype.RANGE, "LESS_THAN", h -> h.get(0)),
RANGE_POINT(Datatype.RANGE, "POINT", h -> h.get(0)),

// Binary constructors — argument type 'a * 'a
RANGE_CLOSED(Datatype.RANGE, "CLOSED",
    h -> Keys.tuple(ImmutableList.of(h.get(0), h.get(0)))),
RANGE_CLOSED_OPEN(Datatype.RANGE, "CLOSED_OPEN",
    h -> Keys.tuple(ImmutableList.of(h.get(0), h.get(0)))),
RANGE_OPEN(Datatype.RANGE, "OPEN",
    h -> Keys.tuple(ImmutableList.of(h.get(0), h.get(0)))),
RANGE_OPEN_CLOSED(Datatype.RANGE, "OPEN_CLOSED",
    h -> Keys.tuple(ImmutableList.of(h.get(0), h.get(0)))),
```

**Add function constants** (alphabetically in the `RANGE_*` region):

```java
RANGE_IS_MEMBER("Range", "isMember", ts ->
    ts.forallType(1, h -> ts.fnType(h.get(0), ts.range(0), BOOL))),

RANGE_NORMALIZE("Range", "normalize", ts ->
    ts.forallType(1, h -> ts.fnType(ts.listType(ts.range(0)),
                                    ts.listType(ts.range(0))))),

RANGE_TO_BAG("Range", "toBag", ts ->
    ts.forallType(1, h -> ts.fnType(ts.listType(ts.range(0)), h.bag(0)))),

RANGE_TO_LIST("Range", "toList", ts ->
    ts.forallType(1, h -> ts.fnType(ts.listType(ts.range(0)), h.list(0)))),
```

(Note: `ts.range(0)` assumes a new helper on the type-helper analogous to
`h.list(0)`, `h.bag(0)`, `h.option(0)`, `h.vector(0)`.)

### 4. `src/main/java/net/hydromatic/morel/eval/Codes.java` *(done)*

**Add `Applicable` implementations** for each function, all implementing
`Typed`:

- `RangeIsMember` — extracts element type, gets comparator via
  `Comparators.comparatorFor`, tests membership by dispatching on constructor
  tag (see membership table below).
- `RangeNormalize` — extracts element type, gets comparator, sorts by lower
  bound, then merges overlapping/adjacent ranges in a single pass.
- `RangeToList` — extracts element type, calls `Discretes.discreteFor`, uses
  `Discrete.comparator()`, `next()`, and `minValue()` to enumerate each range,
  concatenates results.
- `RangeToBag` — same as `RangeToList` but wraps result in a bag.

**Register in `BUILT_IN_VALUES` / `CODES` map.**

#### Membership logic (for `isMember`):

| Constructor            | Condition for `isMember x r`                          |
|------------------------|-------------------------------------------------------|
| `POINT v`              | `cmp(x, v) = EQUAL`                                   |
| `AT_LEAST v`           | `cmp(x, v) ≠ LESS`                                    |
| `GREATER_THAN v`       | `cmp(x, v) = GREATER`                                 |
| `AT_MOST v`            | `cmp(x, v) ≠ GREATER`                                 |
| `LESS_THAN v`          | `cmp(x, v) = LESS`                                    |
| `CLOSED (lo, hi)`      | `cmp(x, lo) ≠ LESS   && cmp(x, hi) ≠ GREATER`        |
| `OPEN (lo, hi)`        | `cmp(x, lo) = GREATER && cmp(x, hi) = LESS`           |
| `CLOSED_OPEN (lo, hi)` | `cmp(x, lo) ≠ LESS   && cmp(x, hi) = LESS`           |
| `OPEN_CLOSED (lo, hi)` | `cmp(x, lo) = GREATER && cmp(x, hi) ≠ GREATER`       |

### 5. `src/test/java/net/hydromatic/morel/DiscreteTest.java` *(done)*

Unit test for `Discrete` and `Discretes` directly, without going through the
Morel evaluator. Follows the style of `PairListTest` / `UtilTest`.

Tests should cover `next()` and `minValue()` for each supported type:

```java
// int discrete
Discrete intD = Discretes.discreteFor(typeSystem, INT);
assertThat(intD.minValue()).isEmpty();           // unbounded below
assertThat(intD.next(3)).hasValue(4);

// char discrete
Discrete charD = Discretes.discreteFor(typeSystem, CHAR);
assertThat(charD.minValue()).hasValue('\u0000');
assertThat(charD.next('a')).hasValue('b');
assertThat(charD.next(Character.MAX_VALUE)).isEmpty();

// bool discrete (false < true)
Discrete boolD = Discretes.discreteFor(typeSystem, BOOL);
assertThat(boolD.minValue()).hasValue(false);
assertThat(boolD.next(false)).hasValue(true);
assertThat(boolD.next(true)).isEmpty();

// unit discrete
Discrete unitD = Discretes.discreteFor(typeSystem, UNIT);
assertThat(unitD.minValue()).hasValue(Unit.INSTANCE);
assertThat(unitD.next(Unit.INSTANCE)).isEmpty();

// real — not discrete
assertThrows(..., () -> Discretes.discreteFor(typeSystem, REAL));
```

### 6. `src/main/resources/net/hydromatic/morel/functions.toml` *(done)*

Add (alphabetically between `Relational` and the next structure):

- `[[structures]]` entry for `Range`
- `[[types]]` entry for `'a range` datatype
- `[[functions]]` entries for `isMember`, `normalize`, `toBag`, `toList`
  (interleaved alphabetically with any types/exceptions)

Assign new ordinals continuing from the current maximum. Use
`specified = "morel"` (not a standard SML Basis function).

### 7. `src/test/resources/script/built-in.smli` *(done)*

Add a `Range` section (alphabetically). Tests should include:

```sml
(* Range structure *)
Range;

(* Constructors *)
POINT 5 : int range;
AT_LEAST 3 : int range;
GREATER_THAN 3 : int range;
AT_MOST 10 : int range;
LESS_THAN 10 : int range;
CLOSED (3, 7) : int range;
OPEN (3, 7) : int range;
CLOSED_OPEN (3, 7) : int range;
OPEN_CLOSED (3, 7) : int range;

(* isMember — int *)
Range.isMember 5 (CLOSED (3, 7));     (* true *)
Range.isMember 3 (CLOSED (3, 7));     (* true *)
Range.isMember 2 (CLOSED (3, 7));     (* false *)
Range.isMember 5 (OPEN (3, 7));       (* true *)
Range.isMember 3 (OPEN (3, 7));       (* false *)
Range.isMember 5 (POINT 5);           (* true *)
Range.isMember 4 (POINT 5);           (* false *)

(* isMember — char *)
Range.isMember #"e" (CLOSED (#"a", #"z"));  (* true *)

(* normalize *)
Range.normalize [CLOSED (5, 10), CLOSED (1, 3), CLOSED (3, 6)];
(* [CLOSED (1, 10)] — merged overlapping/adjacent *)

(* toList — int *)
Range.toList [CLOSED (5, 10)];
(* [5,6,7,8,9,10] *)
Range.toList [OPEN (5, 10)];
(* [6,7,8,9] *)
Range.toList [POINT 5];
(* [5] *)
Range.toList [CLOSED (1, 3), CLOSED (7, 9)];
(* [1,2,3,7,8,9] *)
Range.toList [AT_LEAST 5];
(* raises exception — unbounded *)

(* toList — char *)
Range.toList [CLOSED (#"a", #"e")];
(* [#"a",#"b",#"c",#"d",#"e"] *)

(* toBag *)
Range.toBag [CLOSED (1, 3)];
(* bag {1,2,3} *)
```

### 8. `docs/lib/range.md` *(new file)* *(done)*

Create with the standard license header and doc markers:

```markdown
[//]: # (start:lib/range)
[//]: # (end:lib/range)
```

Content between the markers is auto-generated from `functions.toml` by
`LintTest`.

### 9. `docs/lib/index.md` and `docs/reference.md` *(done)*

Add a link to `range.md` in both files (alphabetically under `R`).

---

## Implementation sequence

1. **`Discrete` + `Discretes`** (files 1–2): implement and write
   `DiscreteTest` (file 5) to validate before anything else depends on it.
2. **Datatype + constructors** (file 3, `BuiltIn.java`): enables
   `CLOSED (3, 7) : int range` in the REPL.
3. **Skeleton tests** (file 7, `built-in.smli`): write constructor tests to
   validate step 2.
4. **`isMember`** (file 4, `Codes.java`): implement `RangeIsMember` with
   `Typed`; add membership tests.
5. **`normalize`** (file 4): implement `RangeNormalize`; add normalize tests.
6. **`toList` / `toBag`** (file 4): implement `RangeToList` and `RangeToBag`
   using `Discretes`; add toList/toBag tests for int and char.
7. **`functions.toml` + docs** (files 6, 8–9): add after functions are
   working; run `./mvnw test -Dtest=LintTest` to validate and auto-generate
   doc content.
8. **Full test run**: `./mvnw test` to confirm no regressions.

---

## Follow-up: issue #290 *(done)*

After this issue is complete, add a comment to
https://github.com/hydromatic/morel/issues/290 noting that `Range.isMember`,
`Range.normalize`, `Range.toList`, and `Range.toBag` all require reified types
at runtime (via the `Typed` / `withType` mechanism) and are therefore affected
by whatever solution is adopted in #290.

Comment posted: https://github.com/hydromatic/morel/issues/290#issuecomment-4101821634

---

## Known limitations (out of scope for this issue)

- `normalize` for discrete types could additionally merge adjacent points into
  ranges (e.g., `[POINT 3, POINT 4]` → `[CLOSED (3, 4)]`). Deferred.
- General discrete types beyond `int` and `char` (e.g., user-defined finite
  sum types) may need more work in `Discretes.nextFor`. Deferred.
- The implicit-type approach has known limitations to be addressed in
  issue #290.
