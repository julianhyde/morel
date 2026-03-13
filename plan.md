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

### ✅ Step 11: Convert multi-binding `let` to stack-based (4df45130)
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

### Step 12: Convert RowSink iteration variables to stack slots
`ScanRowSink` (and `YieldRowSink`) currently bind each iteration
variable by creating a `MutableEvalEnv` extension of `stack.globalEnv`,
then constructing a new `Stack(mutableEnv, stack.slots, stack.top)`.
This keeps iteration variables in the env rather than on the stack, so
inner `StackCode` nodes cannot reference them by slot offset.

Replace with genuine stack allocation:
- At `from`-expression compile time, extend `cx.layout` with a slot for
  each scan variable, exactly as `tryCompileLetStack` does for `let`.
- At runtime in `ScanRowSink.accept(Stack)`, push the row value onto
  the stack instead of extending the env; pop in `result(Stack)`.
- This makes inner filter/yield/order codes pure stack operations and
  eliminates all `MutableEvalEnv` usage in the hot evaluation path.

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

