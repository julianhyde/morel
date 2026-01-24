<!--
Licensed to Julian Hyde under one or more contributor license
agreements. See the NOTICE file distributed with this work
for additional information regarding copyright ownership.
Julian Hyde licenses this file to you under the Apache
License, Version 2.0 (the "License"); you may not use this
file except in compliance with the License. You may obtain a
copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
either express or implied. See the License for the specific
language governing permissions and limitations under the
License.
-->

# Predicate Inversion: Developer Guide

## Architecture Overview

Predicate inversion is a compiler optimization that transforms boolean predicates into generator expressions. Instead of filtering a potentially infinite domain, the compiler "inverts" the predicate logic to directly generate values that satisfy it.

### High-Level Flow

```
User Query
    |
    v
Parser → AST (Abstract Syntax Tree)
    |
    v
Type Resolver → Type-checked AST
    |
    v
Core Language Conversion
    |
    v
Extent Analysis ← PredicateInverter
    |              - Detects patterns
    |              - Generates code
    v
Code Generation → Executable Code
```

### Core Concept: Inversion

**Forward Evaluation** (naive):
```sml
(* Generate all possible (x,y) pairs, then filter *)
from (x,y) in (allInts × allInts)
  where path(x, y)
(* Problem: allInts is infinite! *)
```

**Inverted Evaluation** (optimized):
```sml
(* Generate only values that satisfy path(x,y) *)
Relational.iterate edges (fn (old, new) => ...)
(* Solution: Generates only reachable pairs *)
```

The compiler automatically detects when a predicate can be inverted and generates the efficient code.

### Component Responsibilities

| Component | Responsibility |
|-----------|----------------|
| **PredicateInverter** | Pattern detection, code generation for inverted predicates |
| **Extents** | Integration point, calls PredicateInverter during extent analysis |
| **Result** | Data structure holding generated code and metadata |
| **Generator** | Representation of value-generating expressions |
| **Environment** | Symbol table for looking up function definitions |

## Key Classes

### PredicateInverter

**Location**: `src/main/java/net/hydromatic/morel/compile/PredicateInverter.java`

**Purpose**: Inverts boolean predicates into generator expressions that produce all satisfying values.

**Key Responsibilities**:
- Detect transitive closure patterns in recursive functions
- Extract base cases from `orelse` expressions
- Build `Relational.iterate` calls for fixpoint computation
- Handle conjunction (`andalso`) by ordering generators greedily
- Track which filters are incorporated vs. need runtime checking

**Public API**:

| Method | Description | Parameters | Returns |
|--------|-------------|------------|---------|
| `invert()` | Attempts to invert a predicate | `typeSystem`: Type system<br>`env`: Environment with bindings<br>`predicate`: Boolean expression<br>`goalPats`: Variables to generate<br>`generators`: Existing generators | `Result` with generator and remaining filters |

**Algorithm Overview**:

```
1. Check predicate structure (Op type)
2. If APPLY:
   a. Check for built-in patterns (elem, andalso, orelse)
   b. Check for user-defined function calls
   c. If recursive: detect transitive closure pattern
3. For transitive closure:
   a. Extract base case (e.g., edge(x,y))
   b. Extract recursive case (e.g., exists z ...)
   c. Generate Relational.iterate expression
4. For conjunction (andalso):
   a. Decompose into list of predicates
   b. Greedily order by generator capability
   c. Build chain of generators
5. Return Result with generator + remaining filters
```

**Key Decision Points**:

- **Line 170**: Detect `orelse` - only attempt transitive closure if in recursive context (`!active.isEmpty()`)
- **Line 201**: Check recursion via `active.contains(fnId)` - prevents infinite loops during pattern detection
- **Line 587** (Extents.java): Integration point - Extents calls `PredicateInverter.invert()` during WHERE clause analysis

**Transitive Closure Detection**:

The pattern matcher looks for:
1. **Top-level `orelse`**: Base case OR recursive case
2. **Base case**: Direct relation call (e.g., `edge(x,y)`)
3. **Recursive case**: `exists z where ...` with recursive call composed via `andalso`

Example pattern structure:
```
APPLY(orelse)
  ├─ APPLY(edge)  ← Base case
  │   └─ (x, y)
  └─ APPLY(nonEmpty)  ← Recursive case (exists)
      └─ FROM
          └─ WHERE
              └─ APPLY(andalso)
                  ├─ APPLY(path)  ← Recursive call
                  └─ APPLY(edge)
```

**Extension Points**:
- **Line 168-178**: Add new `orelse` handling for general disjunction union (Phase 6a)
- **Line 158-161**: Add new `andalso` patterns for complex conjunctions
- **Line 131-155**: Add new built-in function inversions (e.g., `String.sub`, arithmetic operations)

**Related Classes**:
- `Result`: Return type containing generator and metadata
- `Generator`: Representation of code that generates values
- `Environment`: Symbol table for function lookups
- `Extents`: Integration point during query compilation

### Result

**Location**: `src/main/java/net/hydromatic/morel/compile/PredicateInverter.java` (nested class, line 1352)

**Purpose**: Encapsulates the result of predicate inversion.

**Structure**:

```java
public static class Result {
  /** Generator expression for values satisfying the predicate */
  public final Generator generator;

  /** Filters that couldn't be incorporated (need runtime check) */
  public final List<Core.Exp> remainingFilters;
}
```

**When Inversion Succeeds**:
- `generator`: Contains efficient code (e.g., `Relational.iterate` call)
- `remainingFilters`: Empty list (all predicates incorporated)

**When Inversion Partially Succeeds**:
- `generator`: Best-effort generator (may be cartesian product)
- `remainingFilters`: Non-empty (caller must apply filters at runtime)

**When Inversion Fails**:
- `generator.cardinality`: `INFINITE` (signals fallback to extent)
- `remainingFilters`: Contains original predicate

**Handling Duplicates and Supersets**:

The `Generator` class (not `Result` directly) tracks:
- `mayHaveDuplicates`: Whether `distinct` operation is needed
- `cardinality`: FINITE, SINGLE, or INFINITE

For transitive closure:
- `mayHaveDuplicates`: true (fixpoint may generate same path multiple times)
- `cardinality`: FINITE (fixpoint guarantees termination)

Morel automatically applies `distinct` when needed based on query semantics.

### Generator

**Location**: `src/main/java/net/hydromatic/morel/compile/Generator.java`

**Purpose**: Represents an expression that generates a collection of values.

**Key Fields**:

```java
public class Generator {
  public final Core.Pat pat;           // Pattern being generated
  public final Core.Exp exp;           // Expression that generates values
  public final Cardinality cardinality; // FINITE, SINGLE, INFINITE
  public final List<Core.Exp> filters;  // Additional runtime filters
  public final Set<Core.NamedPat> uses; // Variables referenced
}
```

**Cardinality Enum**:
- `FINITE`: Bounded collection (e.g., list, iterate result)
- `SINGLE`: Exactly one value (e.g., `[()]`)
- `INFINITE`: Unbounded domain (e.g., all integers)

**Usage in Transitive Closure**:
```java
Generator tcGenerator = generator(
  tuplePattern,                    // (x, y)
  iterateExpression,               // Relational.iterate edges (fn ...)
  Cardinality.FINITE,              // Fixpoint guarantees termination
  ImmutableList.of()               // No additional filters
);
```

### Extents Integration

**Location**: `src/main/java/net/hydromatic/morel/compile/Extents.java` (lines 587-598, 742-753)

**Where PredicateInverter is Called**:

```java
// In Extents.analyze() method
final PredicateInverter.Result result =
    PredicateInverter.invert(
        typeSystem, env, filter, goalPats, generators);

// Check if inversion succeeded
final boolean inversionSucceeded =
    result.remainingFilters.size() != 1
        || !result.remainingFilters.get(0).equals(filter);

if (inversionSucceeded) {
  // Use improved generator
  final Core.Exp combinedFilter =
      core.andAlso(typeSystem, result.remainingFilters);
  goalPats.forEach(pat ->
      map.computeIfAbsent(pat, p -> PairList.of())
         .add(result.generator.exp, combinedFilter));
}
```

**How Results are Incorporated**:

1. **Success (no remaining filters)**:
   - Generator added to `map` for each `goalPat`
   - No runtime filter needed
   - Query uses optimized code path

2. **Partial success (some remaining filters)**:
   - Generator added with `combinedFilter` (AND of remaining predicates)
   - Runtime applies filters after generation

3. **Failure (infinite cardinality)**:
   - Falls back to extent analysis
   - Original filter kept for runtime evaluation

**Error Handling**:

- **Infinite cardinality**: Falls back gracefully, no error thrown
- **Unsupported pattern**: Returns original predicate as remaining filter
- **Recursive without base case**: Returns `null`, causing error upstream

## Data Flow

### Compilation Pipeline

```
1. Parser
   ├─ Input: Source code (.sml, .smli)
   └─ Output: AST (Abstract Syntax Tree)

2. Type Resolver
   ├─ Input: AST
   ├─ Process: Infer types, resolve names
   └─ Output: Typed AST + Environment

3. Core Language Conversion
   ├─ Input: Typed AST
   ├─ Process: Desugar to core language
   └─ Output: Core.Exp tree

4. Extent Analysis *** PredicateInverter called here ***
   ├─ Input: Core.Exp (query with WHERE clauses)
   ├─ Process:
   │  a. Analyze variable scopes
   │  b. Call PredicateInverter.invert() for filters
   │  c. Build generator map
   └─ Output: Generators for each variable

5. Code Generation
   ├─ Input: Generators + remaining filters
   ├─ Process: Generate JVM bytecode
   └─ Output: Executable code
```

### Inversion Flow (Detailed)

```
Input: path(x, y) predicate, goalPats = [x, y]

Step 1: Pattern Recognition
  └─ Detect: User-defined function call
  └─ Lookup: path binding in Environment
  └─ Result: Core.Fn definition found

Step 2: Recursion Detection
  └─ Check: active.contains(path)
  └─ Result: Yes (recursive call)
  └─ Action: Try transitive closure inversion

Step 3: Transitive Closure Analysis
  └─ Detect: orelse at top level
  ├─ Left branch: edge(x,y)  ← Base case
  └─ Right branch: exists z where ... ← Recursive case

Step 4: Base Case Inversion
  └─ Invert: edge(x,y)
  └─ Result: edges collection (from environment)

Step 5: Recursive Case Analysis
  └─ Extract: exists z where path(x,z) andalso edge(z,y)
  └─ Identify: Joining pattern
  └─ Variables: x (from input), z (intermediate), y (from output)

Step 6: Generate Relational.iterate
  └─ Base: edges collection
  └─ Step function:
      fn (old, new) =>
        from (x, z) in new,
             (z2, y) in edges
          where z = z2
          yield (x, y)
  └─ Result: Complete transitive closure generator

Step 7: Build Result
  └─ generator: Relational.iterate expression
  └─ remainingFilters: [] (empty - all incorporated)
  └─ Return: Result object
```

### Example Trace

**Input Query**:
```sml
val edges = [(1,2), (2,3)];
fun edge (x, y) = (x, y) elem edges;
fun path (x, y) = edge (x, y) orelse
  (exists z where path (x, z) andalso edge (z, y));
from p where path p;
```

**Compilation Steps**:

1. **Parser**: Creates AST with `from p where path p`
2. **Type Resolver**:
   - `path` has type `int * int -> bool`
   - `p` has type `int * int`
3. **Core Conversion**:
   - `path p` becomes `APPLY(path, p)`
4. **Extent Analysis** (line 587 in Extents.java):
   - `goalPats = [p]` (we want to generate p)
   - `filter = APPLY(path, p)`
   - Calls `PredicateInverter.invert()`
5. **PredicateInverter** (line 127):
   - Detects `APPLY` with function `path`
   - Looks up `path` in environment (line 203)
   - Finds `Core.Fn` definition
   - Detects `active.contains(path)` → recursive
6. **Transitive Closure Detection** (line 215):
   - Calls `tryInvertTransitiveClosure()`
   - Detects `orelse` pattern
   - Extracts base case: `edge(x,y)`
   - Extracts recursive case: `exists z where ...`
7. **Code Generation**:
   - Generates `Relational.iterate [(1,2), (2,3)] (fn (old, new) => ...)`
8. **Execution**:
   - Iteration 1: Start with `[(1,2), (2,3)]`
   - Iteration 2: Add `(1,3)` (from `1→2→3`)
   - Fixpoint reached: No new paths
   - Result: `[(1,2), (1,3), (2,3)]`

## Extending the Feature

### Adding New Patterns

To support a new invertible pattern, follow these steps:

#### Step 1: Define Test Cases (TDD Approach)

Create tests in `PredicateInverterTest.java`:

```java
@Test
public void testNewPattern() {
  // Define the pattern
  String sml =
      "val data = [1, 2, 3];\n" +
      "fun newPred x = ... ;\n" +
      "from x where newPred x";

  // Expected result
  String expected = "[1, 2, 3]";

  // Run test
  assertScriptOutput(sml, expected);
}
```

#### Step 2: Implement Pattern Detection

Add detection logic in `PredicateInverter.invert()`:

```java
// In PredicateInverter.invert() method, around line 150
if (apply.isCallTo(BuiltIn.NEW_BUILTIN)) {
  return invertNewPattern(apply, goalPats);
}
```

#### Step 3: Implement Inversion Logic

Create a new method:

```java
private Result invertNewPattern(Core.Apply apply, List<Core.NamedPat> goalPats) {
  // Extract pattern arguments
  Core.Exp arg1 = ...;
  Core.Exp arg2 = ...;

  // Check if goal variables appear in invertible positions
  if (goalPats.contains(extractVar(arg1))) {
    // Generate code to produce all satisfying values
    Core.Exp generatorExp = buildGeneratorExpression(arg2);
    Generator generator = generator(
        toTuple(goalPats),
        generatorExp,
        Cardinality.FINITE,
        ImmutableList.of());
    return result(generator, ImmutableList.of());
  }

  // Fallback if not invertible
  return result(generatorFor(goalPats), ImmutableList.of(apply));
}
```

#### Step 4: Add Integration Test

Create test in `src/test/resources/script/new-pattern.smli`:

```sml
(* Test new pattern optimization *)
val data = [1, 2, 3, 4, 5];
fun newPred x = ... ;
from x where newPred x;
(* Expected output: [subset of data that satisfies newPred] *)
```

Add to `ScriptTest.java`:
```java
@Test public void testNewPattern() {
  script("new-pattern.smli");
}
```

### Phase 6a: General Disjunction Union

**Current Limitation**: Only transitive closure `orelse` patterns are optimized.

**Enhancement Goal**: Support general disjunction by taking union of branches.

**Example**:
```sml
fun pathOrDirect (x, y) =
  edge (x, y) orelse
  (exists z where intermediateEdge (x, z) andalso edge (z, y));

(* Should generate: union of edge(x,y) and composed paths *)
```

**Design Approach**:

1. **Detect non-TC orelse** (line 170 in PredicateInverter.java):
```java
if (apply.isCallTo(BuiltIn.Z_ORELSE)) {
  // Check if this is TC pattern
  Result tcResult = tryInvertTransitiveClosure(...);
  if (tcResult != null) {
    return tcResult;
  }

  // NEW: Try general disjunction union
  Result unionResult = tryInvertDisjunctionUnion(apply, goalPats);
  if (unionResult != null) {
    return unionResult;
  }
}
```

2. **Implement union inversion**:
```java
private Result tryInvertDisjunctionUnion(
    Core.Apply orelseApply, List<Core.NamedPat> goalPats) {
  List<Core.Exp> branches = core.decomposeOr(orelseApply);
  List<Generator> branchGenerators = new ArrayList<>();

  for (Core.Exp branch : branches) {
    Result branchResult = invert(branch, goalPats, ImmutableList.of());
    if (branchResult.remainingFilters.isEmpty()) {
      branchGenerators.add(branchResult.generator);
    } else {
      // Can't invert this branch cleanly
      return null;
    }
  }

  // Union all branch generators
  Generator unionGen = unionDistinct(branchGenerators);
  return result(unionGen, ImmutableList.of());
}
```

3. **Expected Complexity**: 2-3 days (pattern detection, code generation, testing)

### Phase 6b: Mutual Recursion

**Current Limitation**: Only single-function recursion supported.

**Enhancement Goal**: Handle mutually recursive functions.

**Example**:
```sml
fun even n = (n = 0) orelse odd (n - 1)
and odd n = (n <> 0) andalso even (n - 1);

from n in [0, 1, 2, 3, 4, 5] where even n;
(* Should generate: [0, 2, 4] *)
```

**Design Notes**:
- Requires tracking multiple functions in `active` list
- Need to detect strongly connected components in call graph
- Generate fixpoint for entire SCC, not individual functions

**Expected Complexity**: 1-2 weeks (requires significant refactoring)

## Testing Strategy

### Unit Tests

**Location**: `src/test/java/net/hydromatic/morel/compile/PredicateInverterTest.java`

**Coverage**:
- `elem` pattern inversion
- `String.isPrefix` inversion
- Conjunction (`andalso`) ordering
- Transitive closure detection
- Unsupported pattern fallback

**Example**:
```java
@Test
public void testTransitiveClosureBasic() {
  String sml =
      "val edges = [(1,2), (2,3)];\n" +
      "fun edge (x, y) = (x, y) elem edges;\n" +
      "fun path (x, y) = edge (x, y) orelse\n" +
      "  (exists z where path (x, z) andalso edge (z, y));\n" +
      "from p where path p";

  assertScriptOutput(sml, "[(1,2),(1,3),(2,3)]");
}
```

### Integration Tests

**Location**: `src/test/resources/script/transitive-closure.smli`

**Coverage** (24 tests across 5 categories):

1. **Correctness (P0)**: Basic functionality
   - Empty graph, single edge, chains, cycles, disconnected, diamonds

2. **Pattern Variation (P1)**: Syntax flexibility
   - Reversed `orelse`, different variable names, records, alternative conjunction order

3. **Performance (P1)**: Scaling behavior
   - 10 edges, 15-node chain, dense 8-node graph

4. **Edge Cases (P1)**: Boundary conditions
   - Unsupported patterns, variable shadowing, self-loops, large numbers

5. **Integration (P2)**: Composition with other features
   - WHERE filters, ORDER BY, YIELD, nested queries

**Running Tests**:
```bash
# Run all unit tests
./mvnw test -Dtest=PredicateInverterTest

# Run integration tests
./mvnw test -Dtest=ScriptTest#testTransitiveClosure

# Run full test suite
./mvnw test
```

### Regression Tests

**Location**: `src/test/resources/script/such-that.smli` (line 743)

**Purpose**: Ensure existing functionality isn't broken.

**Example**:
```sml
(* Original test that motivated the feature *)
val edges = [{x=1,y=2},{x=2,y=3}];
fun edge (x, y) = {x, y} elem edges;
fun path (x, y) = edge (x, y) orelse
  (exists z where path (x, z) andalso edge (z, y));
from p where path p;
(* Expected: [(1,2),(1,3),(2,3)] *)
```

## Debugging

### Enabling Debug Logging

To trace predicate inversion, add logging to `PredicateInverter.java`:

```java
// At start of invert() method
System.err.println("=== Inverting predicate: " + predicate);
System.err.println("Goal patterns: " + goalPats);

// After pattern detection
System.err.println("Detected pattern: " + patternType);
System.err.println("Base case: " + baseCase);
System.err.println("Recursive case: " + recursiveCase);

// Before returning result
System.err.println("Generated code: " + result.generator.exp);
System.err.println("Remaining filters: " + result.remainingFilters);
```

### Common Issues and Solutions

#### Issue: Pattern Not Recognized

**Symptom**: Query produces "infinite: type" error.

**Diagnosis**:
```java
// Add logging in tryInvertTransitiveClosure()
System.err.println("TC detection failed: " + reasonCode);
```

**Common Causes**:
- Missing `orelse` at top level
- Recursive call not in `exists` context
- Base case is non-invertible

**Solution**: Verify AST structure matches expected pattern.

#### Issue: Incorrect Results

**Symptom**: Query returns wrong values.

**Diagnosis**:
```java
// Add logging in generated Relational.iterate
System.err.println("Iteration " + i + ": " + newPaths);
```

**Common Causes**:
- Variable substitution error in step function
- Incorrect join condition (`z = z2`)
- Missing deduplication

**Solution**: Inspect generated code, verify step function logic.

#### Issue: Performance Degradation

**Symptom**: Query takes longer than expected.

**Diagnosis**:
- Check graph size and density
- Count iterations to fixpoint
- Profile memory allocation

**Solution**:
- Reduce graph size
- Apply filters earlier
- Consider alternative algorithms for very dense graphs

### Trace Output Interpretation

**Example Output**:
```
=== Inverting predicate: APPLY(path, (x, y))
Goal patterns: [x, y]
Detected pattern: TRANSITIVE_CLOSURE
Base case: APPLY(edge, (x, y))
Base generator: edges
Recursive case: EXISTS z WHERE APPLY(andalso, [path(x,z), edge(z,y)])
Join variables: x → x, z → z2, y → y
Generated code: APPLY(Relational.iterate, edges, stepFn)
Remaining filters: []
```

**Interpretation**:
- ✅ Pattern recognized correctly
- ✅ Base case inverted to `edges`
- ✅ Recursive case decomposed
- ✅ Step function generated
- ✅ No remaining filters (full inversion)

## Performance Considerations

### Compilation Overhead

**Pattern Detection**: O(1) per predicate (constant AST traversal)

**Code Generation**: O(1) for transitive closure (fixed template)

**Total Overhead**: < 10ms typical, negligible compared to runtime savings.

### Execution Characteristics

**Fixpoint Iteration**:
- **Best case** (already transitive): 1 iteration
- **Average case** (sparse graph): O(diameter) iterations
- **Worst case** (complete graph): O(V) iterations

**Per-Iteration Cost**:
- O(E × P_new) where E = edges, P_new = new paths this iteration

**Total Time Complexity**:
- **Sparse graphs**: O(E × P) where P = total paths
- **Dense graphs**: O(E × V²) worst case

### Memory Profile

**Allocation Pattern**:
1. **Base set**: O(E) - edges collection
2. **Working set**: O(P_new) - new paths per iteration
3. **Result set**: O(P) - total paths

**Peak Memory**: O(E + P) - input plus output

**No Leaks**: Immutable collections, automatic GC of intermediate results.

### Optimization Recommendations

**For Compilation**:
- Cache pattern detection results (future work)
- Reuse common subexpressions in generated code

**For Execution**:
- Early termination when specific paths are found
- Batch processing for multiple queries on same graph
- Parallel iteration for large graphs (future work)

## Related Documentation

- [Transitive Closure User Guide](../transitive-closure.md) - User-facing documentation
- [Performance Benchmarks](../benchmarks/transitive-closure-performance.md) - Detailed performance analysis
- [Issue #217](https://github.com/hydromatic/morel/issues/217) - Original feature request
- [Relational.iterate Reference](../reference.md#relational-iterate) - Manual fixpoint iteration

## Summary

Predicate inversion is a powerful optimization that enables declarative graph queries in Morel. The implementation:

1. **Detects** transitive closure patterns in recursive functions
2. **Extracts** base cases and recursive cases from `orelse` expressions
3. **Generates** efficient `Relational.iterate` calls for fixpoint computation
4. **Handles** cycles correctly via automatic fixpoint detection
5. **Integrates** seamlessly with the Morel compiler pipeline

For maintenance and extension:
- Follow the TDD approach: write tests first
- Add new patterns incrementally
- Maintain backward compatibility
- Document all public APIs
- Profile performance impacts

The architecture supports future enhancements (general disjunction, mutual recursion) through well-defined extension points in `PredicateInverter.java`.
