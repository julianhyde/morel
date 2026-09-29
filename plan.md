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
# Plan: breaking up `Codes.java`

## Motivation

`src/main/java/net/hydromatic/morel/eval/Codes.java` is 10,287 lines
(159 commits, 50 of them in 2026). It mixes several unrelated concerns,
which makes it slow to navigate, conflict-prone, and hard to review.

## Anatomy of `Codes.java` (before)

| Lines | Content | Size |
|---|---|---|
| 1–158 | imports, misc helpers (`nth`, `FLOAT_TO_STRING`) | ~160 |
| 159–7435 | ~513 built-in implementations (fields + `private static class`es), sorted by `BuiltIn` name | **~7,270** |
| 7436–7880 | `populateBuiltIns`, `describe`/`strip`, `Code` factories (`apply*`, `tuple`, `tyCon`, ...), env builders, `aggregate` | ~450 |
| 7880–8424 | `BUILT_IN_VALUES` static block (530 `b.add(BuiltIn.X, X)` lines) | ~540 |
| 8424–8770 | `BUILT_IN_MAP`, float formatting, `TupleCode`/`GetCode`/`StackCode`, `MorelRuntimeException`, `Description` | ~350 |
| 8768–8850 | `BuiltInExn` enum | ~80 |
| 8850–9600 | `Code` node classes (`Apply*`, `StackLet*`, `StackMatch`, `TailCall`, `WrapRelList`, ...) | ~750 |
| 9590–9800 | `Positioned`, `Typed`, `BaseApplicable*` / `BasePositionedApplicable*` hierarchy | ~200 |
| 9868–10213 | range/set implementations | ~350 |
| 10213–end | `Comparer`, `CurriedApplicable1`, `Formatters` | ~75 |

Built-in section by structure (approx. lines): Real 740, String ~850
(Char+String ~1,250), List 825, Decimal 530, Word 420, Int 400, Date
400, Time 310, Sys 260, Relational 255, Op 240, PP 220, Option 195,
Vector 185, Either 160, Math 140, Bool 140, Fn 130, Bag 80, several
under 50.

The most widely used nested classes (references from outside
`Codes.java`): `BuiltInExn` (40), `MorelRuntimeException` (20),
`StackMatchCode` (12), `TupleCode` (8), `TailCall` (8).

## Experiments

All experiments were done in scratch copies of `src/main/java` (plus
generated JavaCC sources), compiled with `javac`, iteratively making
members `public` (or less private) until the code compiled, and
recording each change. Compile-only: tests, checkstyle, `LintTest`,
javadoc were not run.

### Experiment 1: move all of `Codes.java` to a new package `eval.code`

Result: compiles after these changes.

* Helper classes used only by `Codes` move along and stay
  package-private: `ApplicableImpl`, `Bound`, `DescriberImpl`,
  `FmtSpec`, `Radix`.
* `eval` members made public (5): `Decimals.MATH_CONTEXT`,
  `Decimals.parsePrefix`, `Decimals.adjustedExponent`,
  `Discretes.dummy`, `Comparators.comparePartial`.
* `eval.code` members made public because `eval` uses them (8):
  `TailCall` and its `fn`/`arg` (used by `Closure` trampolining),
  `StackMatchCode.capacity`/`captureOffsets`/`patCodes`/`pos` (used by
  `Closure`), `Codes.parseInt` (used by `Variants`).

Conclusion: `Code` node classes are tightly coupled to `Closure`,
`Stack`, `EvalEnv`; they belong in `eval`. Built-in implementations are
not coupled to them.

### Experiment 2: two-way split — `eval.Codes` + `eval.builtin.BuiltIns`

`eval.Codes` (1,351 lines) kept `Code` factories and node classes,
`describe`/`strip`, `aggregate`, `MorelRuntimeException`,
`Description`, `BuiltInExn`. Everything else (8,823 lines) went to
`eval.builtin.BuiltIns`, with `ApplicableImpl`, `Bound`, `FmtSpec`,
`Radix`. (`tyCon`/`ValueTyCon` had to go to the built-in side, because
`ValueTyCon extends ApplicableImpl`.)

Result: compiles; 6 members made public — the same 5 `eval` utility
members as Experiment 1, plus `BuiltIns.parseInt` (used by `Variants`).

* `eval.Codes` has **no** dependency on the built-in side.
* `eval` → built-in dependency (a cycle) via three classes: `RowSinks`
  (`OPTION_NONE`, `optionSome`), `Variant` (`OPTION_NONE`,
  `optionSome`, `floatToString`, `appendFloat`, `intToString`),
  `Variants` (`parseInt`, `parseReal`, `VARIANT_UNIT`). These are value
  helpers, not built-ins; they should stay in (or move to) `eval`.
* 10 files outside `eval` reference moved members (most commonly
  `OPTION_NONE`/`optionSome`, `Positioned`, `BUILT_IN_MAP`,
  `BUILT_IN_VALUES`).

### Experiment 3: `Codes` stays in `eval`; per-structure classes in `eval.codes`

21 classes (`ListCodes`, `StringCodes`, ...), each with a
`public static void register(PairList<BuiltIn, Object> b)` holding its
own sorted `b.add(...)` lines; implementation fields stay `private`.
`Codes` keeps `BUILT_IN_VALUES`, calls each `register`. Infrastructure
(`BaseApplicable*`, `CurriedApplicable1`, `CharPredicate`, ...) was
deliberately left in `Codes`.

Result: compiles.

* ~24 `eval` members made public, of which 18 are the infrastructure
  left in `Codes` (used only by `eval.codes`) — moving it into
  `eval.codes` eliminates them. `ApplicableImpl` became public (used by
  `Codes.ValueTyCon` and Date/Time built-ins). Remaining: the same 5
  utility members.
* ~30 private members became package-private for sharing between
  sibling classes. This maps inter-structure coupling:
  * Bag → List (~18 helpers: `listMap`, `listFilter`, `listFold0`,
    `union`, `ListHd`/`ListTl`/`ListNth`, ...) ⇒ put Bag and List in
    one class.
  * Vector → List (~8: `collate`, `listMapi`, `find`, `exists`,
    `ListTabulate`, ...).
  * Char ↔ String (`scanChar`, `scanCChar`, `skipGaps`,
    `charToCString`, `stringReader`) ⇒ put Char and String in one
    class.
  * Scanning helpers used across structures: `CharSource`
    (Int/Bool/Char/Date/Real), `scanString` (Date/Time), `digits`
    (Date/Time/Word), `consume` (Real).
  * `empty`, `RelationalOnly` (Relational → Bag/List); `decimalChecked`
    (Decimal → Relational); `ORDER_*` (General → Char/Real).
* Wildcard static imports between siblings caused overload collisions
  (`consume`, `scanString`) — use explicit imports.

## Plan

### Phase 1: promote widely used nested classes to top-level in `eval`

* `Codes.BuiltInExn` → `eval/BuiltInExn.java`
* `Codes.MorelRuntimeException` → `eval/MorelRuntimeException.java`
  (with `Codes.Description`, `Codes.UNCAUGHT_PREFIX` if they belong to
  it)

`StackMatchCode`, `TupleCode`, `TailCall` are `Code` node classes and
stay nested in `Codes` (cohesive with the other node classes).

### Phase 2: per-structure classes in `eval.codes`

`Codes` stays in `eval` with `Code` factories/nodes, the registry
(`BUILT_IN_VALUES`, `BUILT_IN_MAP`, `populateBuiltIns`, env builders),
and value helpers used outside the built-ins (`OPTION_NONE`,
`optionSome`, float/int formatting and parsing, ...).

New package `code`:

| Class | Structures |
|---|---|
| `ListCodes` | Bag, List, ListPair |
| `StringCodes` | Char, String |
| `VectorCodes` | Vector |
| `IntCodes` | Int |
| `RealCodes` | Real, Math |
| `WordCodes` | Word |
| `DecimalCodes` | Decimal |
| `DateCodes` | Date, Time |
| `OptionCodes` | Option, Either |
| `RelationalCodes` | Relational, internal `Z_*` aggregates |
| `RangeCodes` | Range, range/set implementations |
| `SysCodes` | Sys, Interact, Datalog, Test, Variant, PP |
| `GeneralCodes` | General, Op, Order, Bool, Fn, top-level (`abs`) |

Plus package-private infrastructure in `eval.codes`: `BaseApplicable*`
hierarchy, `CurriedApplicable1`, and helper classes used only by
built-ins (`Bound`, `FmtSpec`, `Radix`).

Rules:

* Each class has a private constructor and a `public static void
  register(PairList<BuiltIn, Object> b)`, sorted by the existing lint
  directive.
* Implementation fields stay `private`; helpers shared between sibling
  classes become package-private.
* Explicit static imports, no wildcards.

### Phase 3

* Consider a shared scanning helper class (`CharSource`, `scanString`,
  `digits`, `consume`).
* Update `CLAUDE.md` ("Adding a Standard Basis Library Structure", step
  2) to point at `eval/codes/<Struct>Codes.java` and its `register`
  method.
* Do moves in move-only commits so `git blame -C -C` keeps history.

## Outcome

### Phase 1 (done)

* `eval/BuiltInExn.java` and `eval/MorelRuntimeException.java` are
  top-level. `Description` and `UNCAUGHT_PREFIX` became members of
  `MorelRuntimeException` (`MorelRuntimeException.Description`,
  `MorelRuntimeException.UNCAUGHT_PREFIX`).
* References updated in `Main`, `Shell`, `BuiltIn` (including javadoc
  links), `Compiler`, `Closure`, `Session`, `Bound`, `FmtSpec`,
  `LintTest`, `MainTest`, `SignatureChecker`.

### Phase 2 (done)

Generated by scripts (see "Tooling" below), then verified by `fullMake`.

| File | Lines | Contents |
|---|---|---|
| `eval/Codes.java` | 1,690 (was 10,287) | `Code` factories and node classes, registry, value helpers (`OPTION_NONE`, `optionSome`, `parseInt`, `parseReal`, float/int formatting) |
| `eval/codes/StringCodes.java` | 1,476 | Char, String |
| `eval/codes/ListCodes.java` | 1,074 | Bag, List, ListPair |
| `eval/codes/DateCodes.java` | 1,043 | Date, Time |
| `eval/codes/RealCodes.java` | 921 | Real, Math |
| `eval/codes/GeneralCodes.java` | 818 | General, Op, Order, Bool, Fn, `abs`; also `tyCon`, `nth` |
| `eval/codes/SysCodes.java` | 769 | Sys, Interact, Datalog, Test, Variant, PP |
| `eval/codes/WordCodes.java` | 524 | Word |
| `eval/codes/RangeCodes.java` | 483 | Range, range/set implementations, `setToRangeList` |
| `eval/codes/RelationalCodes.java` | 433 | Relational, internal `Z_*` aggregates |
| `eval/codes/DecimalCodes.java` | 425 | Decimal |
| `eval/codes/OptionCodes.java` | 415 | Option, Either |
| `eval/codes/IntCodes.java` | 409 | Int |
| `eval/codes/VectorCodes.java` | 267 | Vector |
| `eval/codes/BaseApplicable*.java`, `BasePositionedApplicable*.java`, `CurriedApplicable1.java` | 40–75 each | base classes, package-private |
| `eval/codes/{ApplicableImpl,Bound,FmtSpec,Radix}.java` | | moved from `eval`, still package-private |

Differences from the plan:

* Helpers that were not built-in fields were placed by usage (the class
  whose code references them most), not by position in the old file.
  This moved misfiled helpers (e.g. `dateFmt`, `sessionNow`, which
  sat among the `DECIMAL_*` fields) to where they are used.
* `tyCon`/`ValueTyCon` moved to `GeneralCodes` (they extend
  `ApplicableImpl`, which can then stay package-private);
  `nth` moved to `GeneralCodes` (it extends `BaseApplicable1`);
  `setToRangeList` moved to `RangeCodes` (it uses `Bound`). All three
  were already public, so callers (`Compiler`, `Inliner`, `TypeSystem`,
  `Pretty`) just changed class name.
* `INT_PATTERN` and `FLOAT_PATTERN` stayed private in `Codes` alongside
  `parseInt`/`parseReal`.

Visibility changes — made **public** (6):

* `Decimals.MATH_CONTEXT`, `Decimals.parsePrefix`,
  `Decimals.adjustedExponent` (used by `DecimalCodes`)
* `Discretes.dummy` (used by `RangeCodes`)
* `Comparators.comparePartial` (used by `GeneralCodes`)
* `Codes.FLOAT_TO_STRING` (used by `DecimalCodes`, `FmtSpec`); later
  later replaced by `Static.floatToString(float)` (see Phase 3)

Made **package-private** (were `private`), shared between sibling
classes in `eval.codes` (about 25), e.g. `ListCodes.{all, collate,
exists, find, length, listMapi, ListNth, ListTabulate}` (used by
`VectorCodes`), `ListCodes.{empty, RelationalOnly}` (by
`RelationalCodes`), `GeneralCodes.ORDER_*` (by `StringCodes`),
`GeneralCodes.consume` (by `RealCodes`), `StringCodes.scanString` (by
`WordCodes`), `DateCodes.digits` (by `WordCodes`),
`DecimalCodes.decimalChecked` (by `RelationalCodes`),
`WordCodes.identity` (by `VectorCodes` and others).

Other fixes needed:

* `eval/codes/package-info.java` (checkstyle `JavadocPackage`), with
  `@NullMarked`.
* `// End X.java` trailers on new files (`LintTest`).
* Javadoc links whose targets moved to a sibling class (e.g.
  `{@link #VECTOR_SUB}` in `ListCodes` → `{@link VectorCodes#VECTOR_SUB}`),
  and javadoc-only imports.
* `CLAUDE.md` updated (architecture section; step 2 of "Adding a
  Standard Basis Library Structure"; lint note; "Adding a Language
  Feature").

Verification: `fullMake` (format, checkstyle, 600 tests with 7
skipped, javadoc, test-javadoc) passes.

Note: an early test run failed (`testParseDot`, `parse.smli`, ...)
because `target/generated-sources` held a stale generated parser; it
passed after `./mvnw clean`. Unrelated to this change.

### Tooling

Scripts in the session scratchpad:

* `chunk.py` — splits `Codes.java` into top-level member chunks
  (brace-matching that ignores strings and comments).
* `phase1.py` — promotes `BuiltInExn`, `MorelRuntimeException`.
* `phase2.py` — assigns chunks to classes (by `BuiltIn` prefix, then by
  usage), splits the registry into `register` methods, writes the
  files with temporary wildcard imports, rewrites `Codes.X` references.
* `fix4.py` — compiles with `javac`; relaxes visibility (package-private
  within `eval.codes`, public only across packages) and adds missing
  imports, logging each change.
* `imports4.py` — replaces wildcard imports with explicit ones.

## Status

* [x] Phase 1 — commit `dc52300f` on branch `split-codes`
* [x] Phase 2 — commit `1354b6e3` on branch `split-codes`
* [ ] Phase 3

### Phase 3 candidates

* `identity` (in `WordCodes`), `order` (in `DateCodes`), `CharSource`
  (in `StringCodes`), `consume` (in `GeneralCodes`) are shared helpers
  whose placement is by usage count; consider a package-private helper
  class (e.g. `Scanning`, `Helpers`) for them.
* [x] Replace `Codes.FLOAT_TO_STRING` (a patch for JDKs before 19) with
  `util.Static.floatToString(float)`, with the version test in `private
  static final boolean FLOAT_TO_STRING_IS_SHORTEST`; its helpers
  `floatToString0` and `formatFloat` move to `Static` too. The methods
  that format Morel `real` values are renamed from `Codes.floatToString`
  to `Codes.realToString`. No members are public any more just for
  this.
* `StringCodes` (1,476 lines) and `DateCodes` (1,043) are the largest;
  Char and String could be split again once scanning helpers are shared.
* When committing: consider committing Phase 1 and Phase 2 separately;
  Phase 2 is a near-pure move, so `git blame -C -C` will follow lines
  into the new files.
