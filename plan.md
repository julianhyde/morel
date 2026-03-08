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

# Plan: Stack-based Evaluation

See [issue.md](issue.md) for the specification and design.

The migration replaces `Code.eval(EvalEnv)` with `Code.eval(Stack)` across
the entire evaluator. It is large enough to be split into steps that each
leave the build green. The key invariant maintained throughout: any variable
not yet compiled for stack access falls back to `GetCode` (name lookup in
`globalEnv`), so partial migration is always correct.

---

## Step 1: Port `VariableCollector` [x]

Port `VariableCollector` from the `julianhyde/151-tail-call` branch.
This is the static analysis pass that walks a function body and identifies
which variables are free (i.e., captured from an enclosing scope) versus
local. It is needed in Step 4 to compute `captureCodes`.

The branch version is mostly complete; it just needs to be adjusted for
changes to `Core` and `Environment` since 2023.

**Files**: new `compile/VariableCollector.java` (ported from branch,
with corrected `isAncestorOf` check), `compile/Environment.java` (added
`forEachAncestor`, `isAncestorOf`, changed `getOpt2` to return
`Pair<Binding, Environment>`), `compile/Environments.java` (implementations).

**Test**: `compile/VariableCollectorTest.java` — 8 tests covering free-variable
detection, nested lambdas, boundary-crossing semantics, and `isAncestorOf`.

**Note**: The branch's `VariableCollector` had its `isAncestorOf` condition
reversed (`lambdaBoundary.isAncestorOf(declaringEnv)` should be
`declaringEnv.isAncestorOf(lambdaBoundary)`). The branch worked in practice
only because the outer environment was always a `MapEnvironment` where
`lambdaBoundary == declaringEnv`. Fixed in this port.

---

## Step 2: Add `Stack` and change `Code`/`Applicable`/`RowSink` signatures [ ]

Change the evaluation interfaces to take `Stack` instead of `EvalEnv`:

```java
// Code.java
Object eval(Stack stack);

// Applicable.java
Object apply(Stack stack, Object argValue);

// RowSink.java
void start(Stack stack);
void accept(Stack stack);
List<Object> result(Stack stack);
```

Add `Stack`:

```java
public final class Stack {
  public EvalEnv globalEnv;
  public Object[] slots;
  public int top;
  // push, pop, peek, save, restore
}
```

For this step, `Stack` is just a thin wrapper around an `EvalEnv`: every
`eval(stack)` call delegates to the existing `eval(stack.globalEnv)` logic.
This step is purely mechanical — it changes signatures throughout but
preserves all behavior. The build must be green at the end of this step.

**Files changed**: `Code.java`, `Applicable.java`, `Applicable1.java`,
`RowSink.java`, `Stack.java` (new), `Closure.java`, `Codes.java`,
`RowSinks.java`, `Compiler.java`, `CalciteCompiler.java`, `Main.java`,
`Shell.java`, all tests.

**Tip**: do the interface changes first (with a default `eval(stack)` bridge
on `Code` that calls `eval(stack.globalEnv)`), then update implementations
one file at a time.

---

## Step 3: Add `StackCode` and `StackLayout`; keep `GetCode` as fallback [ ]

Add compile-time machinery without yet using it:

- `StackLayout`: maps `Core.NamedPat` → slot index within the current frame.
  Stored in `Context` alongside `scopeMap`.

- `StackCode`: `Code` implementation that reads `stack.slots[top - offset]`.

- `GetCode` (unchanged): reads from `stack.globalEnv` by name.

Extend `Context` with:
```java
final StackLayout layout; // null until Step 4 activates stack compilation
final int localDepth;     // virtual frame depth (grows/shrinks with let/case)
```

Add `Codes.stackGet(offset, name)` factory method. The `name` field is kept
for debuggability (printing `stack(offset=N, name=x)` in `Sys.plan` output).

**Files**: `eval/Stack.java`, new `compile/StackLayout.java` (or inner class
in `Environment`), `eval/Codes.java`, `compile/Compiler.java`,
`compile/Context.java`.

**Test**: verify `Sys.plan` output includes `stack(...)` nodes for locally
bound variables and `get(...)` for globals.

---

## Step 4: Emit `StackCode` for `fn` arguments and `let` bindings [ ]

This is the core step. Enable stack-based access for all locals by:

1. In `compileFn` / `compileMatchList`: after pattern-binding the argument,
   emit `StackCode` for each bound variable instead of `GetCode`.

2. In `compileLet`: emit stack push/pop around the let body, and compile the
   body with `StackCode` for the let-bound variable.

3. In `compileValDecl` (function definitions): use `VariableCollector` to
   identify captured variables. Emit code to push them onto the stack at
   closure-call entry. Update `Closure` to store only `capturedValues[]` and
   push them in `apply(stack, arg)`.

4. Update `compileFieldName` to emit `StackCode` when the variable is in the
   current `StackLayout`, and `GetCode` when it is in `globalEnv`.

The `localDepth` counter in `Context` is the key invariant: it must equal
the number of local slots on the stack above the frame base at every code
point. The compiler increments it when pushing bindings and decrements when
popping.

**Files**: `compile/Compiler.java`, `eval/Closure.java`, `eval/Codes.java`.

**Tests**: run the full test suite. The two currently-disabled tests
(`testCompositeRecursiveLet`, `testMutualRecursionComplex`) should now pass
and be re-enabled.

---

## Step 5: Port `RowSink` implementations to use the stack [ ]

Update `RowSinks.java` (16 `RowSink` subclasses) to push/pop iteration
variables via the stack instead of constructing new `EvalEnv` nodes. Each
scan step:

1. Pushes the iteration variable(s) for the current row.
2. Calls `nextSink.accept(stack)`.
3. Pops the iteration variable(s).

The `MutableEvalEnv` used today by `ScanRowSink` to avoid allocation per row
becomes a pair of `(int savedTop, stack)` — equally allocation-free.

**Files**: `eval/RowSinks.java`, `compile/Compiler.java` (RowSink factory
methods).

---

## Step 6: Port `CalciteCompiler` and Calcite integration [ ]

Update the Calcite evaluation path:
- `CalciteFunctions.THREAD_ENV` becomes `CalciteFunctions.THREAD_STACK`.
- When Calcite calls back into Morel (UDFs, expressions), the `Stack` is
  retrieved from the thread local.
- The `CalciteCompiler.createContext` and surrounding machinery use the new
  `Stack`-based signatures.

**Files**: `compile/CalciteCompiler.java`, `foreign/Calcite.java`,
`foreign/CalciteFunctions.java`.

---

## Step 7: Remove `EvalEnv` from inner evaluation [ ]

After Step 6, `EvalEnv` should only appear as `stack.globalEnv` (for
top-level bindings and built-ins), and in the REPL's `bindingMap` (which is
separate from evaluation). The inner `EvalEnv` chain (the linked list that
grows per `let`-binding) is gone.

- Remove `MutableEvalEnv` and `EvalEnvs.bind(...)` (used for per-let nodes).
- Simplify `EvalEnv` to a flat name-lookup structure (e.g., a
  `HashMap<String, Object>`) since it is now only used for globals.
- Remove `Applicable.apply(EvalEnv, Object)` default methods and any
  remaining bridges.

**Files**: `eval/EvalEnv.java`, `eval/EvalEnvs.java`,
`eval/MutableEvalEnv.java` (possibly deleted).

---

## Step 8: Performance benchmarks [ ]

Add micro-benchmarks (JMH or a simple timing loop) for:
- Deep recursion: `resum (10000000, 0)` — measures call overhead.
- Nested `let` chains — measures variable lookup depth.
- N-queens: `queens 20` — exercises the full evaluator.

Compare with the `main` branch baseline. Expected improvements: 2–5× for
recursive functions, 1.5–2× for relational queries.

---

## Key risks

- **Offset arithmetic bugs**: the `localDepth` counter must be exactly right
  at every code point. A systematic test strategy is to add assertions in
  debug builds that the stack depth on function exit equals the depth on
  function entry.

- **`case` arms with different binding counts**: each arm must restore the
  stack to the same depth; the compiler must pad arms that bind fewer
  variables.

- **Calcite boundary**: variables that cross the Morel/Calcite boundary must
  still be reachable by name. `stack.globalEnv` handles this, but any local
  variable captured in a Calcite expression (e.g., an inline lambda in a
  `from` step) must be promoted to a captured value before entering Calcite.
