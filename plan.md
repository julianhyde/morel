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

### Step 13: Marshal referenced globals onto the stack at statement start
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

### Step 14: Remove EvalEnv from eval interfaces
After Steps 11–13, no `Code` class will have a meaningful
`eval(EvalEnv)` body, no `Applicable` will be called via
`apply(EvalEnv, Object)`, and no `RowSink` will need
`start/accept/result(EvalEnv)`.
Remove:
- `Code.eval(EvalEnv)` default throw method (and any remaining overrides)
- `Applicable.apply(EvalEnv, Object)` abstract method (and overrides)
- `RowSink.start/accept/result(EvalEnv)` abstract methods (and overrides)
- `Stack.globalEnv` field (replaced by `Stack.globalSlots`)
- `EvalEnvs` factory / `MutableEvalEnv` implementations used only in
  the now-deleted paths
- `Closure` (old non-stack closure) — replaced entirely by `StackClosure`

`EvalEnv` as a type may survive as the compile-time `Environment`
interface (used by `TypeResolver`, `Compiler.Context`, etc.) but is no
longer part of the runtime evaluation contract.

