<!--
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
-->

# Stack-based Evaluation: Implementation Plan

See `issue.md` for the full design.

## Steps

### ✅ Step 1: VariableCollector (c93e39a0)
Port `VariableCollector` from the `151-tail-call` branch.
Identifies free/captured variables by traversing `fn` boundaries.

### ✅ Step 2: Stack and default methods (b722a61e)
Add `Stack` class (`globalEnv`, `slots[]`, `top`).
Add default `eval(Stack)` / `apply(Stack, Object)` / RowSink `Stack`
methods that fall back to EvalEnv.

### ✅ Step 3: StackLayout, StackCode, Context (a077739f)
Add `StackLayout` (compile-time slot map).
Add `Codes.stackGet` / `StackCode` (reads `stack.slots[top - offset]`).
Extend `Context` with `layout` and `localDepth` fields.

### ✅ Step 4: Emit StackCode for fn arguments and let bindings (f7986493)
`compileMatchListImpl`: when `cx.layout != null`, emit `StackMatchCode`
→ `StackClosure` (captures free vars from outer stack).
`tryCompileLetStack` / `tryCompileLetStackTail`: emit `StackLet1Code`
for simple `val x = expr` bindings.

### ✅ Step 4b: let val (x, y) = expr directly as stack pushes (21ba8d6f)
Detect the Resolver's desugared form
`let val $tmp = expr in case $tmp of (x, y) => body`
and compile as `StackLetPatCode` (calls `pushBindings` at runtime),
avoiding an intermediate closure.
Remove dead `withFreshStack()` method.

### ✅ Step 5: StackClosure implements Applicable1
Store `globalEnv` in `StackClosure` at creation time.
Implement `Applicable1.apply(Object)` via `apply(new Stack(globalEnv), arg)`.
This unblocks builtins like `List.map`, `List.filter` etc. that
receive user functions typed as `Applicable1`.

### ✅ Step 6: RowSinks use Stack
Add `FromCode.eval(Stack)` to route `from` evaluation through the stack
path. Override `start/accept/result(Stack)` in every `RowSinks`
implementation:
- `ScanRowSink`: `code.eval(stack)` for the list; creates an `innerStack`
  with a `MutableEvalEnv` for the iteration variable so `Codes.get` nodes
  resolve via the extended env and `StackCode` nodes still read from the
  shared slots array.
- `WhereRowSink`, `SkipRowSink`, `TakeRowSink`: call `code.eval(stack)`.
- `CollectRowSink`, `YieldRowSink`: call `code.eval(stack)`; YieldRowSink
  builds an `innerStack` to expose yield outputs downstream.
- `OrderRowSink`, `GroupRowSink`: save row values from `stack.globalEnv`;
  re-emit via innerStack in `result(Stack)`.
- `SetRowSink` variants (except/intersect/union): call `code.eval(stack)`
  for RHS codes, access row values from `stack.globalEnv`.
- `BaseRowSink` and `FirstRowSink`: delegate to `rowSink` stack methods.
Added `Stack(EvalEnv, Object[], int)` constructor for sharing slots arrays.

### ✅ Step 7: Activate stack mode globally (ab921c71)
Remove the `if (cx.layout == null)` old-mode guard from
`compileMatchListImpl` (and the corresponding guard in
`compileMatchList`).
Remove `compileMatch`, `compileMatchTail`, and the old `MatchCode`
closure creation path.
All function bodies now use `StackMatchCode` / `StackClosure`.

### ✅ Step 8: CalciteCompiler / ThreadLocal<Stack> (7e866584)
Replace `ThreadLocal<EvalEnv>` in `CalciteFunctions` with
`ThreadLocal<Stack>`.
Update `CalciteCompiler`'s inline `Code` overrides to use
`eval(Stack)` rather than `eval(EvalEnv)`.

### ✅ Step 9: Remove EvalEnv from inner evaluation (d06cb01f)
Make `Code.eval(Stack)` the primary method (non-default).
Make `Code.eval(EvalEnv)` the fallback / deprecated path.
Remove `eval(EvalEnv)` overrides from all stack-based code nodes
(`StackCode`, `StackLet1Code`, `StackLetPatCode`, `StackMatchCode`).

### ✅ Step 10: Shrink Stack slots array
Replace `new Object[4096]` with a properly sized array based on the
maximum stack depth computed at compile time from `StackLayout`.

Compute `capacity` in `StackMatchCode` as `max over arms of
(captureOffsets.length + numArgVars + body.maxSlots())`.
Propagate `maxSlots()` through `StackLet1Code` and `StackLetPatCode`.
Also propagate through `ApplyCode*`, `TupleCode`, `AndAlsoCode`,
`OrElseCode`, `WrapRelList` (all code nodes with sub-expression children).

Fix two runtime array-growth issues:
1. Trampoline in `StackClosure.apply(Stack, Object)`: when a tail call
   targets a closure with larger `capacity`, grow the slots array.
2. Non-tail recursive calls: when `StackClosure.apply(Stack, Object)` is
   called with `stack.slots.length < stack.top + capacity`, allocate a
   larger array and copy live slots.

### ✅ Fix: Variant handling in StackClosure (1a152437)
Three bugs surfaced after Step 10 fixed AIOOB errors:
1. `needsGlobalEnvPatch` / `patchStackClosureEnv`: `Variant extends
   AbstractImmutableList` and `Variant.get(1)` returns `this`, causing
   infinite recursion. Fix: add `!(value instanceof Variant)` guard.
2. `pushVariantConPat` passed the whole `Variant` to `pushBindings`
   instead of the extracted inner value. Fix: extract via new
   `Variant.innerValue()` helper shared with `Variant.bindConPat`.
3. `built-in.smli` and other test expected outputs updated throughout.

### ✅ Fix: Restore detailed describe output; rename stackLet1 → let1
1. `StackMatchCode.describe()` restored to full `match(pat, body)` format
   (was simplified to `"stackMatch"`). Updated all test strings accordingly.
2. `StackLet1Code.describe()` renamed from `"stackLet1"` to `"let1"` to
   align with origin/main naming.
3. Test code reorganized: restored `final String plan = ...` variable style
   in `testLet6`, `testLet7` (MainTest), and `testInline` (InlineTest).

### Step 11: Convert multi-binding `let` to stack-based
`finishCompileLet` still emits `Codes.let()` → `LetCode`/`Let1Code`,
which evaluates via `MatchCode` → old `Closure` → EvalEnv extension.
This path is reached for:
- multi-binding `let` (`let val f = ... and g = ...`, mutual recursion)
- any complex pattern not caught by `tryCompileLetStack*`

Convert `finishCompileLet` to emit stack-based code:
- Emit `StackLetPatCode` (or a new `StackLetMultiCode`) for each binding,
  pushing values onto the stack sequentially.
- Handle mutual recursion by pushing placeholder slots, then
  back-patching `StackClosure.globalEnv` (as `needsGlobalEnvPatch` already
  does) after all bindings are pushed.
- Remove `MatchCode`, old `Closure`, `Let1Code`, `LetCode` once no
  longer emitted.

### Step 12: Convert RowSink scan variables to stack slots

Goal: eliminate `MutableEvalEnv` from the hot `ScanRowSink.accept()`
inner loop by pushing scan variables directly onto `stack.slots`.

#### Background: the deferred-sink problem

Simple sinks (`WhereRowSink`, `YieldRowSink`, `CollectRowSink`) run
their code immediately while the scan variable is still live on the
stack — no special treatment needed.

The hard cases are **deferred sinks** that collect rows during
`accept()` and replay them during `result()`:

| Sink | What it defers |
|------|----------------|
| `OrderRowSink` | Emits rows in sorted order after all rows are collected |
| `GroupRowSink` | Emits aggregated groups after all rows are scanned |
| `ExceptRowSink`, `IntersectRowSink`, `UnionRowSink` | Emits set-operation result after both sides are collected |

During `accept()` the scan variable is on the stack.  During
`result()` the scan loop is finished and those stack slots are gone.
Code compiled to run at `result()` time must therefore **not** use
`StackCode` for scan variables — it must use `GetCode` so the
deferred sink can rebind values by name in a `MutableEvalEnv` chain.

**Previous attempt** (tagged `step-12-partial-2026-03-12`, branch
`349-stack.0`): compiled all downstream code with the scan context
(`cx`), then tried to reconstruct `stack.top` at `result()` time.
This was fragile and produced 8 test failures (wrong offsets, CCE,
AIOBE).

#### Correct approach: two compile-time contexts per deferred sink

The insight: code that runs at **`accept()` time** (condition, key,
`inCodes`) must be compiled with the scan context `cxScan` (scan vars
in layout → `StackCode`).  Code that runs at **`result()` time** (the
downstream `RowSink` factory) must be compiled with `cxFrom` (scan
vars **not** in layout → `GetCode`).

`cxFrom` is threaded through `createRowSinkFactory` as the "result-
time context" — the context that has no scan-variable layout entries.
It is initialized to `cx` at `compileFrom` entry and propagated
unchanged through each `SCAN` step (the SCAN case passes `cxFrom` —
not the newly extended `cxScan` — as the `cxFrom` for the nested
calls).

Additionally, deferred sinks must capture **all variables currently in
scope**, not just those from the immediately preceding `SCAN` step.
After a `YIELD` step, yield-produced variables live in
`stack.globalEnv` (bound by `YieldRowSink.accept`) but not in the
stack layout.  An `ORDER` step after `YIELD` must capture these
env-based variables via `GetCode` during `accept()` so it can rebind
them in `result()`.  This requires an `allScopeBindings` accumulator
— a shadow-merging map threaded through `createRowSinkFactory` that
tracks every binding introduced since the start of the `from`
expression.

#### Step 12a: ScanRowSink push-based; deferred sinks rebind via env

**All tests pass after this step.**

Starting from commit **4df45130** (Step 11).

##### Changes to `Compiler.java`

1. **Private `createRowSinkFactory` overload** with new parameters:
   - `cxFrom: Context` — result-time context (no scan vars in layout)
   - `allScopeBindings: ImmutableMap<String, Binding>` — all
     query-level bindings accumulated since `from` entry, shadow-
     merged (later binding wins by name)

   The public overload `createRowSinkFactory(cx0, stepEnv, steps,
   elementType)` delegates to the private one with `cxFrom = cx0` and
   `allScopeBindings = ImmutableMap.of()`.

2. **SCAN case**: at compile time, extend `cx.layout` with each
   `NamedPat` from `scan.pat` at slot index `cx.localDepth + i`,
   producing `cxScan`.  Compile `conditionCode` and the scan's
   immediate downstream factory with `cxScan`.  Recurse with:
   - `cx0 = cxScan` (scan vars in layout for immediate children)
   - `cxFrom = cxFrom` (unchanged — **not** `cxScan` — so that nested
     deferred sinks never see the outer scan var in the layout)
   - `allScopeBindings` updated by shadow-merging `scan.env.bindings`

3. **YIELD case**: update `allScopeBindings` by shadow-merging
   `yield.env.bindings` before recursing.  YIELD itself stays
   env-based (`YieldRowSink` unchanged).

4. **ORDER case** — extract to `compileOrderSink(cx, cxFrom,
   allScopeBindings, order, remainingSteps, elementType)`:
   - `code` (sort key) compiled with `cx` (`cxScan`) → `StackCode`
   - `inCodes` = `buildInCodes(cx, allScopeBindings.values())` —
     for each binding: `StackCode` if in layout, `GetCode` otherwise
   - `inNames` = parallel name list (sorted by slot index, non-layout
     vars last)
   - downstream `RowSink` factory = `createRowSinkFactory(cxFrom,
     cxFrom, order.env, remainingSteps, elementType)` → `GetCode`

5. **GROUP case** — in `compileGroupSink`, change `inCodes` to use
   `allScopeBindings.values()` instead of `stepEnv.bindings`.
   Downstream factory already uses `cxFrom`.

6. **SET cases** (EXCEPT/INTERSECT/UNION) — extract to
   `compileSetSink(cx, cxFrom, allScopeBindings, stepEnv, args,
   distinct, op, remainingSteps, elementType)`:
   - `codes` (RHS expressions) compiled with `cx` → evaluated during
     first `accept()` when scan vars are live on the stack
   - `inCodes` = `buildInCodes(cx, allScopeBindings.values())`
   - downstream factory = `createRowSinkFactory(cxFrom, cxFrom,
     stepEnv, remainingSteps, elementType)` → `GetCode`

7. **Terminal collect** (empty `steps`): use `compileFieldName(cx, ...)`
   so scan vars that reach the collect directly still use `StackCode`.

##### Changes to `RowSinks.java`

8. **`ScanRowSink.accept(Stack)`**: replace `bindMutablePat` +
   `innerStack` with `pushBindings(pat, element, stack)` and direct
   `rowSink.accept(stack)`.  Grow `stack.slots` if needed before the
   loop (`stack.top + varCount > slots.length`).

9. **`OrderRowSink`**:
   - `accept(Stack)`: capture all scope var values via `inCodes`
     (StackCode reads slots; GetCode reads `stack.globalEnv`); store
     per-row.
   - `result(Stack)`: for each sorted row, **rebind** all captured
     values into a `MutableEvalEnv` chain keyed by `inNames`; call
     `rowSink.accept(new Stack(env2, stack.slots, stack.top))` — same
     `stack.top`, no pushes.

10. **SET sinks `result(Stack)`**: same pattern as ORDER — rebind
    captured values in `MutableEvalEnv`, call downstream with same
    `stack.top`.  (GROUP already does this correctly.)

11. **`ScanRowSink.varCount`**: new field, set from `scanPats.size()`
    at compile time, used only for the slots-growth check.

##### Test expectation updates

12. **`built-in.smli`**: update description strings where
    `get(name x)` becomes `stack(offset N, name x)` for variables
    that are now in the scan layout.

#### Step 12b: Pure-stack deferred sink result()

After Step 12a the deferred sinks still create a `MutableEvalEnv`
chain in `result()`.  This is the cold path (called once per `from`,
not per row) so it is not a performance bottleneck, but it blocks the
eventual removal of `EvalEnv` from the runtime (Step 14).

Approach: at compile time record `scanDepth` (the number of
`allScopeBindings` entries that are in the stack layout).  In
`result()`, push the stored per-row values back onto the stack at
`stack.top`..`stack.top + scanDepth - 1`, so that `StackCode` offsets
computed in the scan context resolve correctly.  Compile downstream
with the scan context `cx` (not `cxFrom`) as the `cx0` argument to
`createRowSinkFactory`.  For env-based slots (from YIELD steps, not
in the layout), bind their values into `stack.globalEnv` per row via
a `MutableEvalEnv` chain — a smaller partial chain than before.

Done one sink type at a time:

##### ✅ OrderRowSink

- `compileOrderSink`: compile sort-key `code` with `cx`; compute
  `scanDepth`; pass `cx` (not `cxFrom`) as `cx0` to downstream
  factory; pass `scanDepth` to `RowSinks.order()`.
- `OrderRowSink`: add `scanDepth` field; replace `result(Stack)` with
  push-back loop using a `withRow()` helper; no `MutableEvalEnv` for
  stack-based slots.

##### ✅ GroupRowSink

- `compileGroupSink`: compute `scanDepth`; compile `argumentCode` with
  `cx` (not `cxFrom`) so scan variables use `StackCode`; pass `scanDepth`
  to `Codes.aggregate()`.
- `Codes.aggregate()`: add `scanDepth` parameter.  In `apply(Stack, arg)`:
  - Single stack-based var (`names.size()==1 && scanDepth==1`): push row,
    eval, restore.
  - Single env-based var (`names.size()==1 && scanDepth==0`): bind into
    `MutableEvalEnv` per row (legacy path).
  - All stack-based (`envCount==0`): push all `scanDepth` values, eval,
    restore; no `MutableEvalEnv`.
  - Mixed (scan + env-based, e.g. after YIELD): push stack-based values,
    bind env-based values into a `MutableEvalEnv` chain, eval, restore.
- `GroupRowSink` itself unchanged (no `scanDepth` field needed since
  `Codes.aggregate` handles the push-back).

##### ✅ SetRowSink (accept pass-through and result paths)

- `compileSetSink`: compute `scanDepth` from `stepEnv.bindings ∩ cx.layout`;
  compile RHS `codes` with `codeCx` (either `cx` with a `localDepth` bump for
  `EXCEPT-ALL`/`INTERSECT-ALL`, or `cxFrom` for union/distinct cases); pass
  `scanDepth` and `inSlots` to `RowSinks.except/intersect/union()`.
- **Downstream boundary**: pass `ImmutableMap.of()` as `allScopeBindings` to
  the downstream factory.  The SET step is a scope boundary (like GROUP): outer
  scan vars must not appear in the downstream's `inSlots`, because those vars
  are not on the stack at result()-time and would cause an AIOBE
  (`slots[-1]`).
- `SetRowSink` (abstract base): add `scanDepth` field and `withRowFromKey()`
  helper.  `withRowFromKey` pushes the first `scanDepth` names back onto the
  stack (for `StackCode` resolution) and binds any remaining env-based names
  into a `MutableEvalEnv` chain.
- `ExceptAllRowSink.accept(Stack)`: pass through to `rowSink.accept(stack)`
  (no rebind needed — scan vars are live).
- `ExceptDistinctRowSink.result(Stack)`: call `withRowFromKey()` per key,
  then `rowSink.accept()`.
- `IntersectAllRowSink.accept(Stack)`: pass through as above.
- `IntersectDistinctRowSink.result(Stack)`: call `withRowFromKey()` per key.
- `UnionRowSink.accept(Stack)`: pass through for both distinct and all cases.
- `UnionRowSink.result(Stack)`: iterate RHS code elements; for each call
  `withRowFromKey()` then `rowSink.accept()`; finally call
  `rowSink.result(stack)`.

### ✅ Step 13: Yield vars as stack slots (#349)

Convert yield output variables from `GetCode`/`MutableEvalEnv` bindings to
`StackCode`/slot-push, eliminating the per-row `MutableEvalEnv` allocation
in `YieldRowSink.accept()`.

**Compiler.java** — YIELD case in `createRowSinkFactory`:
- Add `yieldContext(cx, yieldBindings, nameOrder)` helper: extends `cx.layout`
  with each yield output pat at slot `cx.localDepth + i`, in `codeMap.keySet()`
  order (alphabetical, matching YieldRowSink's push order).  Returns a new
  `Context` (`cxYield`) with updated layout and `localDepth`.
- Pass `cxYield` (not `cx`) as `cx0` to the downstream factory.
- `cxFrom` is unchanged: yield vars are per-row, not baseline globals.

**RowSinks.java** — `YieldRowSink.accept(Stack)`:
- Pre-evaluate all yield codes against the input `stack` (before any push),
  then push all values in order onto `s` (grown if needed).
- After `rowSink.accept(s)`, restore to `savedTop`.
- Downstream `StackCode` offsets for yield vars resolve correctly because
  `withRow()` / `withRowFromKey()` in deferred sinks now include yield vars
  in `scanDepth` (they are in `cx.layout`), and push them back at result() time.

**Test updates**: `relational.smli` plan-string tests updated:
`get(name eN)` → `stack(offset 1, name eN)` for yield output vars in
downstream `collect()` code.

### ✅ Step 14: Marshal referenced globals onto the stack at statement
start (bf5cfc1c)
The only remaining EvalEnv lookups at runtime are `GetCode` /
`GetTupleCode`, which call `env.getOpt(name)` to find top-level and
built-in bindings.

Fix: at compile time, determine exactly which global names the
expression references (those not covered by the local `StackLayout`).
Emit a **`GlobalMarshalCode` prologue** that fetches just those names
from `globalEnv` and pushes their values onto the stack before the body
runs. The body then uses `StackCode` offsets for all variable access,
including globals.

This is the same mechanism `StackClosure` already uses to capture local
variables from an outer stack frame — extended to the global level.

**Compile time:**
- `VariableCollector` (or a new pass) identifies every free global name
  referenced in the expression.
- Assign slot offsets 0, 1, … to those names, producing a
  `globalSlotMap: ImmutableMap<Name, Integer>` stored in `Context`.
- `compileFieldName` emits `StackCode(offset, name)` for names found
  in `globalSlotMap`, instead of `GetCode(name)`.
- Wrap the compiled body in `GlobalMarshalCode(names, body)`.

**Runtime** (`GlobalMarshalCode.eval(Stack)`):
- For each name in order, call `stack.globalEnv.getOpt(name)` and push
  the value onto the stack.
- Evaluate the body; pop the global slots on return.

**Properties:**
- Only referenced globals are marshaled — minimal stack footprint and
  no spurious references keeping old values alive.
- Shadowed bindings are never fetched (the compiler resolved the visible
  name at compile time); their values are GC-eligible immediately.
- Slot indices are local to one compilation + execution pair (recompile
  = fresh slot map), so REPL redefinition is handled naturally.
- Previously-compiled `StackClosure` values captured their `globalEnv`
  at creation time and continue to see the binding visible then —
  correct ML semantics across redefinitions.
- `GlobalMarshalCode` is the only node that touches `EvalEnv` at
  runtime; it runs once per statement, outside the hot inner loop.
- Type constructors: in standard SML, redefining a datatype obscures
  the old constructors; verify Morel matches this (separate issue).

Remove `GetCode` / `GetTupleCode` once all global references are
handled by `GlobalMarshalCode` + `StackCode`.

### ✅ Step 15: Remove EvalEnv from Code and RowSink eval interfaces
Remove all `eval(EvalEnv)` overrides from `Code` nodes; make
`eval(Stack)` the sole required method (default now throws).
Remove `start/accept/result(EvalEnv)` from `RowSink` interface;
make the `Stack` variants abstract.
Delete dead `GetTupleCode` class.
Fix `Inliner`, `Applicable.asCode()`, and `CalciteFunctions` to use
`eval(Stack)` / `apply(Stack, Object)` throughout.

`Applicable.apply(EvalEnv, Object)` and `Stack.globalEnv` are still
present (used internally by `StackMultiLetCode`, `ValueTyCon`,
`withRowFromKey`, etc.) and are candidates for a future cleanup step.

### ✅ Step 16: Add `Stack.session` field

Add `public final @Nullable Session session` to `Stack`, derived from
`globalEnv.getOpt(EvalEnv.SESSION)` in both constructors.
Migrate all 13 built-ins that called `env.getSession()` to override
`apply(Stack, Object)` using `stack.session` instead:
`DATALOG_EXECUTE/TRANSLATE/VALIDATE`, `InteractUse`,
`SYS_CLEAR_ENV/PLAN/PLAN_EX/SET/SHOW/SHOW_ALL/UNSET`, `ValueTyCon`.
Also add `apply(Stack, Object)` to `BaseApplicable1/2/3` delegating
to their typed `apply(...)` methods.
Each migrated built-in retains a stub `apply(EvalEnv, Object)` that
throws; `Applicable.apply(EvalEnv, Object)` is still abstract
(cleaned up in the next step).

### ✅ Step 16b: Remove `Applicable.apply(EvalEnv, Object)` from interface

Make `apply(Stack, Object)` the sole abstract method on `Applicable`;
change `apply(EvalEnv, Object)` to a `@Deprecated` default that throws.
Delete all throw stubs from Codes.java (15 locations) and the dead
EvalEnv overrides from `Closure` and `StackClosure`.
Fix two call sites (`EITHER_FOLD`, `FN_CURRY`) that passed
`(EvalEnv) null` — changed to `((Applicable1) a).apply(arg)`.

### ✅ Task: Consider Option 2 — session/globals in a root stack frame

**Conclusion**: Root-frame approach rejected. Benefits (no
`GlobalMarshalCode`, no `patchStackClosureEnv`, no
`StackClosure.globalEnv`) are outweighed by costs: two kinds of slot
access (root-frame slots vs local slots), monotonically growing root
frame, and added complexity in the lookup protocol when a `StackCode`
offset spans a frame boundary.

Preferred path: the three-step sequence below (Steps 17–19).

### ✅ Step 17: Convert `StackMultiLetCode` to stack-slot let-bindings

`StackMultiLetCode` (used for `REC_VAL_DECL` — `fun` and mutually-
recursive `val ... and ...` bindings) still uses `MutableEvalEnv`
chains for recursive self-references. Convert the result-code context
to use stack slots.

**What was implemented**: Added `useSlots` flag to `StackMultiLetCode`.
For `REC_VAL_DECL` (`useSlots=true`): `buildLetContext` assigns each
binding a consecutive stack slot in the outer `cx2`; `resultCode` is
compiled with `cx2` so it uses `StackCode` for the bound names.
All RHSs are evaluated first (before any push), then values are pushed
onto the stack, then `resultCode` runs. `patchStackClosureEnv` is still
called to wire up recursive refs inside closure bodies via `globalEnv`.

**Bug fixed**: GROUP result tuple for `fun my_sum ... in from e in emps
group ... compute {my_sum over e.id}` raised `ClassCastException:
StackClosure cannot be cast to Integer`. Root cause: `buildLetContext`
put `my_sum` into `cxFrom.layout` at slot 1; the GROUP result tuple
compiled `my_sum` as `StackCode(offset=1)`, which at result()-time reads
the slot still holding the `StackClosure`, not the aggregate result.
Fix: `StackLayout.without(names)` strips GROUP output names from the
layout; `compileGroupSink` creates `cxResult = cxFrom.without(outNames)`
before passing it to `groupNextFactory`, forcing those names to compile
as `GetCode` (reading from `groupEnvs` in `globalEnv`).

**Limitation of this step**: `patchStackClosureEnv` is a temporary
measure — it mutates each closure's `globalEnv` after creation.
Step 17b replaces it with the `RecFrame` design.

### ✅ Step 17b: `RecFrame` for mutual recursion

**Problem**: `patchStackClosureEnv` (used by `StackMultiLetCode`) keeps
`globalEnv` alive in `StackClosure` for recursive self-references.
Every recursive call does a name-keyed `EvalEnv` lookup, even though
the slot indices are known at compile time.

**Key insight — two complementary concepts**:

1. *`StackLayout` as compile-time map*: `StackLayout` already records
   `{binding → slot index}` for the outer context. `buildLetContext`
   uses it to assign the N rec-group bindings to slots `D..D+N-1`.
   This is exactly the compile-time information needed to emit
   `StackCode` for recursive refs inside closure bodies — no new
   compile-time structure is required.

2. *`RecFrame` as runtime indirection*: When closure `f` is created,
   `g` does not yet exist — so `f`'s captured-slots snapshot cannot
   contain `g`. `RecFrame` is an `Object[N]` allocated *before* any
   closure is created, shared by all closures in the group, and filled
   in *after* all closures exist. All closures hold a reference to the
   same frame; there is no snapshot-then-patch problem.

**Data structures**:

```
RecFrame { Object[] bindings; }   // N slots; one per rec-group binding
                                  // allocated before closures; filled after

StackClosure {
    Object[] slots;               // captured outer stack region (snapshot)
    int captureLen;
    int recGroupSize;             // 0 if not in a rec group
    @Nullable RecFrame recFrame;  // shared with all peers; null if non-rec
    StackMatchCode code;
    // EvalEnv globalEnv — removed in this step
}
```

`RecFrame` provides the indirection that avoids snapshot-then-patch:
because all closures share the same `RecFrame` object, filling it in
once makes the values visible to every closure immediately.

**Why offsets are independent of `captureLen`**: When `apply()` seeds a
new stack, it copies `captureLen` outer slots (indices `0..captureLen-1`),
then pushes the N RecFrame bindings (indices `captureLen..captureLen+N-1`),
then pushes the single argument (index `captureLen+N`). The body context
starts at depth `captureLen+N+1`. The StackCode offset for rec peer `j`
is `(captureLen+N+1) − (captureLen+j) = N+1−j`. The `captureLen` terms
cancel, so the offset is a compile-time constant depending only on `N`
and `j`, not on how many variables a particular closure happens to
capture.

**Compile-time changes**:

- Pass `RecGroupInfo(N, j, peers)` through to `compileMatchListImpl`
  for each binding in a `REC_VAL_DECL`.
- After `VariableCollector` determines `captureLen`, extend the
  body context with the N rec peers at slots
  `captureLen+0 .. captureLen+N-1`.
  Since the offset formula `N+1−j` is independent of `captureLen`, the
  offsets can be stored as compile-time constants in the `StackCode`
  nodes.
- Remove `GetCode` emission for recursive self-references inside
  rec-group closure bodies; they all become `StackCode`.

**Runtime changes**:

- `StackMultiLetCode.eval()` (`useSlots=true`):
  1. Allocate `RecFrame rf = new RecFrame(N)`.
  2. Evaluate each RHS (`StackMatchCode.eval(stack)`) — each creates a
     `StackClosure`; attach `rf` to it immediately.
  3. Fill `rf.bindings[i] = closure_i`.
  4. Push closures onto the N stack slots.
  5. Evaluate `resultCode`.
  - Remove `patchStackClosureEnv`, `bindPatGetValue` calls.
- `StackClosure.apply(Stack, Object)`:
  - After seeding the new stack from `captureLen` captured slots, push
    `recFrame.bindings[0..N-1]` before pushing the argument.

**Eliminate after this step**:
`patchStackClosureEnv`, `needsGlobalEnvPatch`, `bindPatGetValue`,
`EvalEnv globalEnv` field in `StackClosure`, `GetCode` emissions for
rec-group self-references.

### ✅ Step 18: Move `globalEnv` from `Stack` into `Session`

After Step 17b, `StackClosure` no longer carries a `globalEnv` field.
`Stack.globalEnv` remains — it is read by `GlobalMarshalCode` to fetch
top-level bindings, and by `StackClosure.apply()` to seed the child
`Stack`.

Move the authoritative `globalEnv` into `Session` as a mutable field.

Changes:
- Add `EvalEnv globalEnv` field to `Session`.
- `Stack` constructor: derive `session` from `globalEnv`, or accept
  `Session` directly; keep `globalEnv` as an alias until Step 19.
- `StackClosure.apply()`: reads `stack.session.globalEnv` to seed the
  child `Stack` instead of carrying its own copy.
- Top-level redefinition in the REPL: update `session.globalEnv`
  directly; no closure-by-closure patching needed.

### Step 19: Remove `Stack.globalEnv` field

After Step 18, replace every `stack.globalEnv` read with
`stack.session.globalEnv` and delete the field.

`Stack` then contains exactly three fields: `session`, `slots`, `top`.

**Prerequisite**: Several callers in `RowSinks.java` and `Codes.java` create
inner stacks via `new Stack(extendedEnv, slots, top)` where `extendedEnv` is
a `MutableEvalEnv` with rebound row values for deferred-sink rebinding
(`withRow`, `withRowFromKey`). `GetCode` nodes compiled for those names look
up in `stack.globalEnv = extendedEnv`. Until those are converted to slot-push
(as Step 12b described for deferred sinks), `Stack.globalEnv` cannot be
removed. The simple reads (`GetCode.eval`, `GlobalMarshalCode.eval`,
`StackMultiLetCode.useSlots=false`, `StackMatchCode.eval`, `Calcite.eval`)
can be converted to `stack.session.globalEnv` immediately; the
`new Stack(extendedEnv, ...)` constructors are the blocking dependency.

This step therefore decomposes into three sub-steps, each independently
committable:

#### ✅ Step 19a: Partial `stack.globalEnv` → `stack.session.globalEnv`

Replace reads that access only top-level globals (not locally-extended
envs) with `stack.session.globalEnv`:

- `GlobalMarshalCode.eval`: pushes top-level globals onto the stack at
  statement start — use `stack.session.globalEnv.getOpt(name)`.
- `Calcite.CalciteCode.eval(Stack)`: sets
  `THREAD_EVAL_ENV = stack.globalEnv` for the legacy scalar-fallback
  path — change to `stack.session.globalEnv`.
- `StackMatchCode.eval` in `Compiler.java`: passes `stack.globalEnv`
  as the `globalEnv` argument to `StackClosure` constructor (snapshot
  for dummy-session fallback) — change to `stack.session.globalEnv`.

The `Calcite.CalciteCode.eval(Stack)` `THREAD_EVAL_ENV` assignment is kept as
`stack.globalEnv` (not changed): `session.globalEnv` lacks the SESSION key
so Stacks built from it would have null sessions.

A companion fix: `CalciteFunctions.MorelTableFunction.Compiled.create` now
initialises `session.globalEnv = evalEnv` so that the dummy session used
by `CalciteCompiler.createContext` has a non-null `globalEnv`, preventing
NPEs when `StackMatchCode` creates `StackClosure` objects during table-
function scan evaluation.

After this step `Stack.globalEnv` is still read by `GetCode.eval` (for
locally-extended envs in `StackMultiLetCode` and `withRow` / `withRowFromKey`)
and by the `new Stack(extendedEnv, …)` constructors.

#### ✅ Step 19b: Convert RowSink deferred-result env-extension to slot-push

Eliminated `new Stack(extendedEnv, slots, top)` from `OrderRowSink.withRow()`
and `SetRowSink.withRowFromKey()`.

Approach taken:
- Added `Compiler.buildEnvSlotsContext()`: extends a context's `StackLayout`
  by assigning new slot indices to bindings not yet in the layout (env-based
  vars such as GROUP output vars). Slot assignment order matches
  `sortedInBindings` (stack vars first by slot, then env vars in original
  order), which matches the push order in `withRow`/`withRowFromKey`.
- `compileOrderSink`: builds
  `cxResult = buildEnvSlotsContext(cx, allScopeBindings)`; compiles sort
  key and downstream `nextFactory` with `cxResult` so that all scope vars
  (including formerly env-based GROUP output vars) use `StackCode`.
- `compileSetSink`: builds
  `cxResult = buildEnvSlotsContext(cx, stepEnv.bindings)`; compiles
  `nextFactory` with `cxResult`.
- `OrderRowSink.withRow()`: simplified to push all `inSlots.size()` values
  onto the stack; `maxSlots()` returns
  `inSlots.size() + rowSink.maxSlots()`.
- `SetRowSink.withRowFromKey()`: simplified to push all `names.size()`
  values; `maxSlots()` returns `names.size() + rowSink.maxSlots()`.

Remaining `new Stack(extendedEnv, …)` calls:
- `GroupRowSink.result()` (lines 1025, 1046): GROUP output vars are still bound
  via `MutableEvalEnv` chains. Converting GROUP requires restructuring
  `Codes.aggregate` and the `GroupRowSink`; deferred to a later step.
- `Codes.aggregate` (lines 4292, 4325): env-based paths for aggregate argument
  evaluation; only triggered when scan vars are env-based (rare, e.g.
  GROUP-after-GROUP). Deferred to a later step.
- `StackMultiLetCode.useSlots=false` (line 5278): Step 19c.

#### ✅ Step 19c-i: Add `postProcessLetBody` hook; re-enable let-stack path

Added a `postProcessLetBody(cx, bodyCode, bodyType)` hook to `Compiler.java`
(default: identity). Called it from `tryCompileLetStack`,
`tryCompileLetStackTail`, and `compileLetStackPat` so subclasses can
post-process the compiled let body.

In `CalciteCompiler.java`:
- Deleted the null-returning `tryCompileLetStack` and `tryCompileLetStackTail`
  overrides (which had previously disabled the stack-based let path so that
  `finishCompileLet` could call `toRel4`).
- Added `postProcessLetBody` override that calls `toRel4` on the body code.
  If `toRel4` converts it to a `CalciteCode` **and** there are slot-bound
  variables in the current layout, a slot-to-env bridge is emitted: an
  anonymous `Code` that copies each slot value into `stack.globalEnv` before
  evaluating the `CalciteCode`, so that `morelScalar`'s `GetCode` references
  can find them via `stack.globalEnv`.

Added `StackLayout.nameToOffsetMap(int localDepth)`: returns a
`Map<String, Integer>` giving the 1-based runtime stack offset for each
variable in the layout (offset = `localDepth − slotIndex`),
used by the bridge.

`StackMultiLetCode.useSlots=false` is no longer reached for `VAL_DECL` lets
whose body can be converted by `toRel4`; the bridge approach subsumes the
previously-planned Step 19c-ii.

#### ✅ Step 19c: Remove `StackMultiLetCode.useSlots=false` path

Changed `compileLet` (both the normal and tail-position paths) to always use
`buildLetContext` and always pass `useSlots=true` to `finishCompileLet`.
Removed `boolean useSlots` parameter from `Compiler.finishCompileLet` and
`CalciteCompiler.finishCompileLet`; `CalciteCompiler.finishCompileLet` now
calls `postProcessLetBody(cx, resultCode_, resultType)` instead of
`toRel4(cx.env, resultCode_, resultType)` so that the slot-to-env bridge is
applied when the result code is a Calcite plan and there are slot-bound
variables in scope.

Removed `useSlots` field and `useSlots=false` branch from
`StackMultiLetCode`; `Codes.stackMultiLet` no longer takes a `useSlots`
parameter. `Closure.bindPatGetValue` is now dead code (will be deleted with
`Stack.globalEnv` in Step 19d).

#### ✅ Step 19e: Convert remaining `globalEnv` reads to `stack.currentEnv()`

Changed all remaining runtime reads of `stack.globalEnv` to go through
`session.globalEnv` (when session is non-null) or `stack.globalEnv` as a
fallback. The mechanism:

- Added `Stack.currentEnv()` method: returns `session.globalEnv` when
  the session is non-null, else falls back to `stack.globalEnv`. Used by
  all read-only lookups (`GetCode.eval`, `GlobalMarshalCode.eval`,
  `StackMatchCode.eval`, SET-sink `bindMutableArray` calls in
  `RowSinks.java`).

- `GroupRowSink.result()` and `Codes.aggregate` (env-based paths):
  these require a session (they call `requireNonNull(stack.session)`).
  They mutate `session.globalEnv` for the duration of the evaluation
  then restore it. Updated to use a local `session` variable.

- `CalciteCompiler.postProcessLetBody` bridge: likewise uses
  `requireNonNull(stack.session)`.

- `AlgebraTest`: updated four plan-string assertions to match the new
  `let1(expCode ...)` format (the `globalMarshal` wrapper is stripped
  by `Matchers.stripGlobalMarshal`).

- `plan.md`: shortened two overly-long section headings.

#### Step 19d: Remove `Stack.globalEnv`

After Steps 19a–19e, no code reads `stack.globalEnv` at runtime.
Delete the field, the `Stack(EvalEnv, int)` constructor, and the
`Stack(EvalEnv, Object[], int)` constructor.  `Stack` then has exactly
three fields: `session`, `slots`, `top`.

Also delete the now-dead `Closure.bindPatGetValue` and simplify
`StackClosure.apply(Object)` (no more `globalEnv` fallback needed).

### ✅ Step 20: Eliminate `THREAD_EVAL_ENV`

`THREAD_EVAL_ENV` was a `ThreadLocal<EvalEnv>` that carried the
evaluation environment into Calcite so that callbacks from Calcite SQL
evaluation back into Morel (`morelScalar`, `morelApply`) could
reconstruct a `Stack`.

Eliminated by converting `CalciteCode.eval(EvalEnv)` to delegate to
`eval(Stack)` (creates `new Stack(evalEnv, 0)` and calls `eval(stack)`).
This ensures `THREAD_STACK` is always set during Calcite plan execution,
making `THREAD_EVAL_ENV` unnecessary.

Deleted:
- `CalciteFunctions.THREAD_EVAL_ENV` ThreadLocal field
- `THREAD_EVAL_ENV` set-sites in `CalciteCode.eval(EvalEnv)` and
  `eval(Stack)` in `Calcite.java`
- `THREAD_EVAL_ENV` read-sites in `MorelScalarFunction.eval` and
  `MorelApplyFunction.eval` in `CalciteFunctions.java`

After this step `THREAD_STACK` and `THREAD_ENV` are the only Calcite
thread-locals.

### ✅ Step 21: Remove `CalciteCode.eval(EvalEnv)` and `Code.eval(EvalEnv)`

After Step 20 every `Code` node uses only `eval(Stack)`.

Deleted:
- `CalciteCode.eval(EvalEnv)` thin-delegate override in `Calcite.java`
- `Code.eval(EvalEnv)` default method in `Code.java`; its removal turned
  any remaining caller into a compile error, exposing:
  - Four `@Override eval(EvalEnv)` methods in `RelCode` anonymous classes
    in `CalciteCompiler.java` (all deleted)
  - `Closure.bindEvalBody()` calling `code.eval(envRef.env)` (changed to
    `code.eval(new Stack(envRef.env, code.maxSlots()))`)
- `MorelTableFunction.Compiled.evalEnv` field; replaced with `session`
  field; `scan()` now uses `compiled.session.globalEnv` to create the
  `Stack`; `EvalEnv` import removed from `CalciteFunctions.java`

`Closure` is never directly instantiated (only `StackClosure` is),
so `bindEvalBody` is dead code, but its fix keeps the class compilable.

### Step 22: Simplify `EvalEnv` to a flat map

**Blocked by Steps 19c/19d.**

After all the steps above, `EvalEnv` is used only as the authoritative
store of top-level REPL bindings — accessed exclusively via
`session.globalEnv` (a single read per `GlobalMarshalCode` invocation).
The lookup chain and `MutableEvalEnv` mechanism are no longer needed at
runtime.

However, `stack.globalEnv` (an `EvalEnv`) is still read in:
- `GetCode.eval(Stack)` for env-based variable lookups
- GROUP row sinks (`bindMutableArray` calls in `RowSinks.java`)
- Aggregate argument evaluation in `Codes.java`

These will be eliminated by Steps 19c/19d (convert remaining env-based
lookups to slots, then delete `Stack.globalEnv`). Once those are done:

- Replace `Session.globalEnv: EvalEnv` with
  `Session.globalEnv: ImmutableMap<String, Object>` (or a similar flat
  structure).
- Update `GlobalMarshalCode.eval` to call `session.globalEnv.get(name)`.
- Update `ActionImpl.apply` (REPL redefinition) to rebuild the map with
  the new binding.
- Delete `MutableEvalEnv`, `EvalEnvs`, and the `EvalEnv` interface once
  no code constructs or traverses the old chain form.

This is the end of the stack-based evaluation migration.  After Step 22
the runtime contains no linked-list environment chains — all variable
access is either a direct array slot read (`StackCode`) or a flat-map
lookup (`GlobalMarshalCode`).

