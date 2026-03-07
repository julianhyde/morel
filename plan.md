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

# Plan: Tail-Call Optimization (Issues #151, #148)

## Infrastructure status

The `julianhyde/151-tail-call` branch attempted a full migration from
`EvalEnv` (linked-map) to `Stack` (flat `Object[]`) for all evaluation.
That work is **not** present in `151-2-tail-call` (the current branch).
Source files on this branch still use `EvalEnv`-based evaluation:
- `Code.eval(EvalEnv)`
- `Applicable.apply(EvalEnv, Object)`
- `Closure` captures via `EvalEnv evalEnv`
- `RowSink.accept(EvalEnv)`

The full stack migration is not a prerequisite for TCO via trampolining.

## Approach

Use **trampolining** within the existing `EvalEnv`-based evaluation:
- No migration to `Stack` required
- Identify tail-call positions at compile time
- Emit a `TailCall` sentinel value from tail-call sites instead of
  recursing on the Java stack
- A trampoline loop in `Closure` bounces on the sentinel until a real
  value is produced, keeping Java stack depth O(1) for tail calls
- Works for direct self-recursion, mutual recursion, and tail calls
  through higher-order functions (e.g. `List.foldl` lambda)

## Steps

### Step 1: Add tests [x]

Add `src/test/resources/script/queens.smli` covering:

- `resum`: simple tail-recursive accumulator.
  - `resum (1000, 0)` works today; include it.
  - `resum (1000000, 0)` needs TCO; include with TODO comment.
- `coprime`: mutually tail-recursive GCD check (from #148 branch).
  - Small inputs work today; include them all.
- `queens`: CPS N-queens solver (from #148 branch). Uses `List.\`exists\``.
  - `queens 2` → `NONE` (works today)
  - `queens 6` → `SOME [...]` (works today)
  - `queens 8` → `SOME [...]` (works today — the CPS style stays
    shallow because `try` and `addQueen` are mutually tail-recursive
    with depth bounded by n)
  - `queens 100` → needs TCO; include with TODO comment.

### Step 2: Implement trampolining [x]

Changes made:

1. **`TailCall` sentinel** (`Codes.TailCall`): inner class in `Codes`
   with `fn: Applicable` and `arg: Object`.

2. **`TailApplyCode`** and **`TailApplyCodeCode`** in `Codes.java`:
   eval returns `TailCall(fn, arg)` instead of calling `fn.apply()`.

3. **Trampoline in `Closure.bindEval`**: `bindEvalBody` evaluates the
   body (may return `TailCall`); `bindEval` loops on `TailCall` calling
   `closure.bindEvalBody(arg)` directly (bypassing the inner trampoline):
   ```java
   Object result = bindEvalBody(argValue);
   while (result instanceof TailCall) {
     TailCall tc = (TailCall) result;
     if (tc.fn instanceof Closure)
       result = ((Closure) tc.fn).bindEvalBody(tc.arg);
     else
       result = tc.fn.apply(evalEnv, tc.arg);
   }
   return result;
   ```
   This keeps the Java stack at O(1) depth for tail-recursive calls.

4. **Compiler tail-position detection** (`Compiler.compileTail`):
   - FN bodies: always compiled in tail position via `compileMatchListTail`
   - CASE arms: compiled in tail position when CASE is in tail position
   - LET body: compiled in tail position
   - APPLY: emits `TailApplyCode`/`TailApplyCodeCode` instead of regular apply

5. **Works with `RowSink`**: no changes needed (trampoline runs inside
   `Closure.bindEval` transparently).

### Step 3: Enable TODO tests [x]

- `resum (1000000, 0)` is now enabled and passes.
- `queens 100` is correct but computationally expensive; left as TODO.

### Step 4: Enable disabled Java tests [ ]

In `MainTest.java`, `testCompositeRecursiveLet` and
`testMutualRecursionComplex` are currently `@Disabled`. Verify whether
TCO unblocks them; enable if so.
