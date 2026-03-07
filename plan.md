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
    shallow because `tryCol` and `addQueen` are mutually tail-recursive
    with depth bounded by n)
  - `queens 100` → needs TCO; include with TODO comment.

### Step 2: Implement trampolining [ ]

Changes required:

1. **`TailCall` record** (new file or inner class in `Codes`):
   ```java
   // Sentinel returned from tail-call positions
   static final class TailCall {
     final Applicable fn;
     final Object arg;
   }
   ```

2. **`TailApplyCode`** (in `Codes.java`): a `Code` that, instead of
   calling `fn.apply(env, arg)`, constructs and returns a `TailCall`.
   Used at syntactic tail-call positions.

3. **Trampoline in `Closure.bindEval`**: after evaluating the body,
   loop while the result is a `TailCall`:
   ```java
   Object result = code.eval(env);
   while (result instanceof TailCall tc) {
     result = tc.fn.apply(env, tc.arg);
   }
   return result;
   ```
   This keeps the Java stack frame for the outermost `bindEval` call
   and avoids growing the stack for each recursive Morel call.

4. **Compiler tail-position detection** (in `Compiler.java`): pass an
   `isTailPos` flag through `compile()`/`compileApply()`. Tail position
   propagates through `if`, `case`, `let`, `fn` bodies. Emit
   `TailApplyCode` when `isTailPos` is true.

5. **Works with `RowSink`**: `RowSink.accept(EvalEnv)` calls
   `code.eval(env)`, which returns a real value (the trampoline runs
   inside any `Closure` that is called). No changes needed in any
   `RowSink` implementation.

### Step 3: Enable TODO tests [ ]

Remove TODO comments from `resum (1000000, 0)` and `queens 100` once
Step 2 is complete and tests pass.

### Step 4: Enable disabled Java tests [ ]

In `MainTest.java`, `testCompositeRecursiveLet` and
`testMutualRecursionComplex` are currently `@Disabled`. Verify whether
TCO unblocks them; enable if so.
