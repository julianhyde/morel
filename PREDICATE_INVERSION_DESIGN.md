# Predicate Inversion Design

## Overview

Predicate inversion transforms predicates into generator expressions. Given a predicate and generators for input variables, it produces an improved generator that exploits the predicate's structure to reduce cardinality (ideally from infinite to finite).

## Core Concept: Improving the Default Generator

The **default generator** for variables is the cartesian product of their individual generators:
```sml
from x in gen(x), y in gen(y), z in gen(z) where predicate
```

**Goal of inversion:** Produce a better generator by pushing predicates into the generator, ideally:
- INFINITE → FINITE (eliminate extent scans)
- FINITE → SMALLER FINITE (reduce size)
- Add constraints as WHERE clauses rather than runtime filters

## Generator Structure

A generator is not just an expression—it has additional metadata:

```java
class Generator {
  Core.Exp expression;          // The generator expression
  Cardinality cardinality;      // SINGLE | FINITE | INFINITE
  Core.Exp constraint;          // Predicate that values must satisfy (true if none)
  Set<Core.NamedPat> freeVars;  // Non-output variables referenced in expression
}
```

**Key insight:** `cardinality` is "per binding of free variables". A generator with free variables is only finite if those variables are bounded first.

### Examples

**Single value:**
```sml
empno = 7369
```
→ `Generator(expr: 7369, SINGLE, constraint: true, freeVars: {})`

**Finite list:**
```sml
empno in [1,2,3]
```
→ `Generator(expr: [1,2,3], FINITE, constraint: true, freeVars: {})`

**Infinite extent:**
```sml
empno (unbound)
```
→ `Generator(expr: extent(int), INFINITE, constraint: true, freeVars: {})`

**Constrained extent:**
```sml
from empno where empno > 7000
```
→ `Generator(expr: extent(int), INFINITE, constraint: empno > 7000, freeVars: {})`

**Dependent generator:**
```sml
from y in [1,2,3], x where x > y andalso x < y + 10
```
After inversion:
→ `Generator(expr: List.tabulate(9, fn k => y + 1 + k), FINITE, constraint: true, freeVars: {y})`

## Function Signature

```java
Result invert(
  Core.Exp predicate,                      // Predicate to invert
  Set<Core.NamedPat> outputPats,          // Variables in the output tuple
  Map<Core.NamedPat, Generator> generators, // Generators for ALL variables in scope
  Environment env                          // Environment to look up function definitions
)
```

**Parameters:**
- `predicate`: The boolean expression to invert
- `outputPats`: Which variables should appear in the result tuple
- `generators`: Generators for **all variables in scope** (not just those used in predicate)
  - Variables in `outputPats` must be in `generators`
  - Local variables (introduced by `exists`) also in `generators`
  - Passing all in-scope variables avoids need for free variable analysis
- `env`: Environment for looking up function definitions (needed for recursive predicate inversion)

**Returns:**
```java
class Result {
  Generator generator;              // Improved generator for output variables
  List<Core.Exp> remainingFilters; // Predicates still needing runtime checks
}
```

The generator always produces tuples of values for **all** variables in `outputPats`.

**Fallback behavior:** If inversion cannot improve on the default generator, returns:
```java
Result(
  generator: /* cartesian product of generators for outputPats */,
  remainingFilters: [predicate]  // Full predicate requires runtime filtering
)
```

## Example Walkthroughs

### Example 1: `(empno, d) elem emps`

**Input:**
```java
predicate: (empno, d) elem emps
outputPats: {empno, d}
generators: {
  empno -> Generator(extent(int), INFINITE, true, {}),
  d -> Generator(extent(int), INFINITE, true, {})
}
```

**Result:**
```java
Result(
  generator: Generator(
    expr: emps,
    cardinality: FINITE,
    constraint: true,
    freeVars: {}
  ),
  remainingFilters: []
)
```

**Improvement:** INFINITE × INFINITE → FINITE

### Example 2: `x > y andalso x < y + 10`

**Case A: y already bound**
```java
predicate: x > y andalso x < y + 10
outputPats: {x}
generators: {
  x -> Generator(extent(int), INFINITE, true, {}),
  y -> Generator([0, 2, 9], FINITE, true, {})  // already bound
}
```

**Result:**
```java
Result(
  generator: Generator(
    expr: List.tabulate(9, fn k => y + 1 + k),
    cardinality: FINITE,
    constraint: true,
    freeVars: {y}  // References y!
  ),
  remainingFilters: []
)
```

**Key:** Generator references non-output variable `y`. Works because `y` is in scope.

**Case B: Both unbounded**
```java
generators: {
  x -> Generator(extent(int), INFINITE, true, {}),
  y -> Generator(extent(int), INFINITE, true, {})
}
```

**Result:** Fallback to default generator.
```java
Result(
  generator: Generator(
    expr: from x in extent(int), y in extent(int) yield (x, y),
    cardinality: INFINITE,
    constraint: true,
    freeVars: {}
  ),
  remainingFilters: [x > y andalso x < y + 10]
)
```

### Example 3: `x > 0 andalso x < 10 andalso x mod 2 = 1`

**Input:**
```java
predicate: x > 0 andalso x < 10 andalso x mod 2 = 1
outputPats: {x}
generators: {
  x -> Generator(extent(int), INFINITE, true, {})
}
```

**Result (partial inversion):**
```java
Result(
  generator: Generator(
    expr: [1..9],
    cardinality: FINITE,
    constraint: true,
    freeVars: {}
  ),
  remainingFilters: [x mod 2 = 1]
)
```

Or with full inversion:
```java
Result(
  generator: Generator(
    expr: from x in [1..9] where x mod 2 = 1,
    cardinality: FINITE,
    constraint: true,
    freeVars: {}
  ),
  remainingFilters: []
)
```

### Example 4: `String.isPrefix s "abcd"`

**Input:**
```java
predicate: String.isPrefix s "abcd"
outputPats: {s}
generators: {
  s -> Generator(extent(string), INFINITE, true, {})
}
```

**Result:**
```java
Result(
  generator: Generator(
    expr: List.tabulate(5, fn i => String.substring("abcd", 0, i)),
    cardinality: FINITE,
    constraint: true,
    freeVars: {}
  ),
  remainingFilters: []
)
```

**Improvement:** INFINITE → FINITE (5 elements)

### Example 5: `exists d where (empno, d) elem emps andalso (d, dname) elem depts`

**Input:**
```java
predicate: (empno, d) elem emps andalso (d, dname) elem depts
outputPats: {empno, dname}  // d is local, not in output
generators: {
  empno -> Generator(extent(int), INFINITE, true, {}),
  d -> Generator(extent(int), INFINITE, true, {}),      // introduced by exists
  dname -> Generator(extent(string), INFINITE, true, {})
}
```

**Result:**
```java
Result(
  generator: Generator(
    expr: from (e, d) in emps, (d2, dn) in depts
          where d = d2
          yield (e, dn),
    cardinality: FINITE,
    constraint: true,
    freeVars: {}
  ),
  remainingFilters: []
)
```

**Key insights:**
- `d` is used in the join but not in output
- Two `elem` predicates combined via join on common variable `d`
- All three extents eliminated!

### Example 6: `path(x, y)` where `fun path (x, y) = edge(x, y) orelse (exists z where edge(x, z) andalso path(z, y))`

This is a **recursive predicate** defining transitive closure.

**Input:**
```java
predicate: path(x, y)
outputPats: {x, y}
generators: {
  x -> Generator(extent(?), INFINITE, true, {}),
  y -> Generator(extent(?), INFINITE, true, {})
}
```

**Analysis:**
- `path` is defined recursively in terms of itself
- Look up function definition in `env` to get body: `edge(x, y) orelse (exists z where edge(x, z) andalso path(z, y))`
- Base case: `edge(x, y)` → inverts to `edges`
- Recursive case: `edge(x, z) andalso path(z, y)` → join step

**Result:**
```java
Result(
  generator: Generator(
    expr: Relational.iterate
            (fn () => edges)
            (fn prev =>
              from (x, z) in prev, (z2, y) in edges
              where z = z2
              yield (x, y)),
    cardinality: FINITE,
    constraint: true,
    freeVars: {}
  ),
  remainingFilters: []
)
```

**Improvement:** INFINITE × INFINITE → FINITE (transitive closure)

**Pattern matching:** This works when the recursive predicate has the structure:
- `base_case orelse (exists z where step_case andalso recursive_call)`
- OR: `base_case orelse (exists z where recursive_call andalso step_case)`

For other recursive structures that don't match this pattern, fall back to the default generator.

## Design Principles

1. **Pass all in-scope variables** - Don't require free variable analysis
2. **Generators can reference non-output variables** - As long as they're in scope
3. **Cardinality is per binding** - FINITE means "finite per value of free variables"
4. **Partial inversion is OK** - Use `remainingFilters` for what can't be inverted
5. **Always returns a result** - Falls back to default cartesian product if no improvement possible
6. **Local variables can be projected away** - Used in computation but not in output

## Handling `orelse` (Disjunction)

For `P1 orelse P2`:

**Input:**
```java
predicate: P1 orelse P2
outputPats: {x, y, ...}
generators: {...}
```

**Approach:**
1. Invert `P1` → get `result1`
2. Invert `P2` → get `result2`
3. If both improved on the default (no `remainingFilters`): return union of generators
4. If one improved: return that one with the full disjunction in `remainingFilters`
5. If neither improved: return default generator with full predicate in `remainingFilters`

**Example (both branches improve):**
```sml
x elem [1,2,3] orelse x elem [4,5,6]
```
→ `Result(Generator([1,2,3,4,5,6], FINITE, true, {}), remainingFilters: [])`

## Recursive Predicates

Recursive predicates like transitive closure require **fixed-point iteration** using `Relational.iterate`.

**Recognizable pattern:**
```sml
fun f (x, y) =
  base_case orelse (exists z where step_case andalso f(x, z))
```
or with the recursive call on the right:
```sml
fun f (x, y) =
  base_case orelse (exists z where f(z, y) andalso step_case)
```

**Strategy:**
1. When inverting a predicate like `path(x, y)`, check if `path` is a function call
2. Look up the function definition in `env`
3. Check if it matches the recursive pattern
4. If yes:
   - Invert `base_case` to get base generator
   - Invert `step_case` to get step generator
   - Combine using `Relational.iterate`
5. If the pattern doesn't match:
   - Fall back to default generator

**Implementation approach:**
```java
if (predicate is Apply(functionName, args)) {
  FunctionDef def = env.lookup(functionName);
  if (matchesRecursivePattern(def.body, functionName)) {
    Result baseResult = invert(baseCase, outputPats, generators, env);
    Result stepResult = invert(stepCase, outputPats, generators, env);
    return Result(
      generator: generateIterateExpr(baseResult.generator, stepResult.generator, joinVar),
      remainingFilters: []
    );
  }
}
```

**See Example 6** for a complete walkthrough of inverting `path(x, y)`.

## Current Implementation Status

The current `PredicateInverter.java` uses a simpler signature:
```java
Optional<Result> invert(
  Core.Exp predicate,
  Set<Core.NamedPat> goalPats,           // = outputPats
  SortedMap<Core.NamedPat, Core.Exp> boundPats,  // Finite generators only
  List<Core.Exp> active                  // Recursion detection
)
```

**Differences from proposed design:**
- Returns `Optional<Result>` instead of `Result` (should always return a result)
- `boundPats` only includes finite generators (not extents)
- No explicit `Generator` class (just `Core.Exp`)
- No cardinality or constraint metadata
- No `Environment` parameter for looking up function definitions
- Extents are implicit (inferred if not in `boundPats`)

**Migration path:**
1. Change return type from `Optional<Result>` to `Result`
2. Remove `satisfiedFilters` and `satisfiedPats` from `Result` class
3. Introduce `Generator` class with cardinality, constraint, and freeVars
4. Add `Environment env` parameter
5. Pass all generators (including extents) explicitly
6. Implement fallback to default generator instead of returning empty
7. Implement recursive predicate pattern matching

## References

- Issue #217: https://github.com/hydromatic/morel/issues/217
- Existing implementation: `src/main/java/net/hydromatic/morel/compile/PredicateInverter.java`
- Test file: `src/test/resources/script/such-that.smli`
- Related: Datalog evaluation, program inversion, predicate materialization
