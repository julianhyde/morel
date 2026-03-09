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
Update `RowSink` interface: add `accept(Stack)` as the primary method;
default falls back to EvalEnv.
Update all `RowSinks` implementations to override `accept(Stack)`,
using `stack.push` / `stack.restore` for iteration variables instead
of `EvalEnv.bind`.
Update `Compiler.createRowSinkFactory` to pass stack context.

### ✅ Step 7: Activate stack mode globally
Remove the `if (cx.layout == null)` old-mode guard from
`compileMatchListImpl` (and the corresponding guard in
`compileMatchList`).
Remove `compileMatch`, `compileMatchTail`, and the old `MatchCode`
closure creation path.
All function bodies now use `StackMatchCode` / `StackClosure`.

### Step 8: CalciteCompiler / ThreadLocal<Stack>
Replace `ThreadLocal<EvalEnv>` in `CalciteFunctions` with
`ThreadLocal<Stack>`.
Update `CalciteCompiler`'s inline `Code` overrides to use
`eval(Stack)` rather than `eval(EvalEnv)`.

### Step 9: Remove EvalEnv from inner evaluation
Make `Code.eval(Stack)` the primary method (non-default).
Make `Code.eval(EvalEnv)` the fallback / deprecated path.
Remove `eval(EvalEnv)` overrides from all stack-based code nodes
(`StackCode`, `StackLet1Code`, `StackLetPatCode`, `StackMatchCode`).

### Step 10: Shrink Stack slots array
Replace `new Object[4096]` with a properly sized array based on the
maximum stack depth computed at compile time from `StackLayout`.
