# Testing Strategy for Phases 3-4: Predicate Inversion

## Overview

This document outlines the comprehensive testing strategy to validate Phases 3 and 4 of the predicate inversion implementation. Tests are organized by category, with clear acceptance criteria and execution procedures.

---

## Test Categories

### 1. Unit Tests (PredicateInverter)

Location: `src/test/java/net/hydromatic/morel/compile/PredicateInverterTest.java`

#### 1a. Pattern Recognition Tests

**Purpose**: Validate that pattern matching correctly identifies recursive structures.

| Test Case | Input | Expected Output | Notes |
|-----------|-------|-----------------|-------|
| `testExtractSimpleTransitiveClosure` | `fun path (x,y) = edge(x,y) orelse (exists z where edge(x,z) andalso path(z,y))` | (baseCase, stepCase) extracted | Simple case |
| `testExtractReorderedAndalso` | `fun path (x,y) = edge(x,y) orelse (exists z where path(z,y) andalso edge(x,z))` | (baseCase, stepCase) extracted | Andalso reordered |
| `testExtractNestedExists` | `fun path (x,y) = edge(x,y) orelse (exists z where exists w where ...)` | Correctly handles nesting | Edge case |
| `testExtractMultipleRecursiveCalls` | `fun f (x) = ... andalso f(...) andalso f(...)` | Returns None (pattern mismatch) | Should fail gracefully |
| `testExtractNonOrElse` | `fun f (x) = ... andalso ...` (no orelse) | Returns None | Should fail gracefully |
| `testIdentifyJoinVariable` | Step case: `edge(x,z) andalso path(z,y)` | Join variable: {z} | Single join |
| `testIdentifyMultipleJoinVariables` | Step case with multiple shared vars | Join variables: {z, w} | Multiple joins |
| `testIdentifyNoJoinVariable` | Step case: `edge(x,z) andalso path(a,b)` | Returns None | No shared vars |

**Execution**: `mvn test -Dtest=PredicateInverterTest#testPattern*`

---

#### 1b. Substitution Tests

**Purpose**: Validate variable substitution in iteration context.

| Test Case | Input | Substitution | Expected Output | Notes |
|-----------|-------|--------------|-----------------|-------|
| `testSimpleVariableSubst` | `(x, y)` | {x → x', y → y'} | `(x', y')` | Basic rename |
| `testNestedVarSubst` | `from (x, z) in edges yield (x, z)` | {z → z2} | `from (x, z2) in edges yield (x, z2)` | Nested structure |
| `testMultipleVarSubst` | Expression with 3+ vars | {x→x', y→y', z→z'} | All vars renamed | Multiple |
| `testTupleDestructuring` | `let (a, b) = x in ...` | {a → a'} | Destructuring preserved | Special case |
| `testRecordFieldSubst` | `{x=a, y=b}` | {a→a'} | `{x=a', y=b}` | Record type |
| `testFunctionArgSubst` | `f(x, y)` | {x→1, y→2} | `f(1, 2)` | Function application |

**Execution**: `mvn test -Dtest=PredicateInverterTest#testSubst*`

---

#### 1c. Join Expression Tests

**Purpose**: Validate join expression generation.

| Test Case | Input | Expected Structure | Notes |
|-----------|-------|-------------------|-------|
| `testSimpleJoinExpression` | Two generators, one join var | `from ... , ... where z=z2 yield ...` | Single join |
| `testMultipleJoinVars` | Two generators, multiple join vars | Multiple where conditions | Complex join |
| `testJoinWithProjection` | Different output tuple structure | Yield clause matches output | Projection |
| `testJoinWithRecordType` | Record type iteration | Proper destructuring | Type handling |
| `testJoinOrdering` | New results first, base second | Correct from clause order | Semantics |

**Execution**: `mvn test -Dtest=PredicateInverterTest#testJoin*`

---

#### 1d. Mode Inference Tests (Phase 4a)

**Purpose**: Validate mode analysis and inference.

| Test Case | Predicate | Input Mode | Expected Output Modes | Notes |
|-----------|-----------|-----------|----------------------|-------|
| `testModeElemIN` | `x elem list` | {list: IN} | {x: OUT} | Elem with bound list |
| `testModeElemOUT` | `x elem list` | {x: IN} | {list: OUT} | Elem with bound x |
| `testModeComparisonBoth` | `x > y` | {x: IN, y: IN} | {} (no solution) | Both bound |
| `testModeComparisonOneIN` | `x > y` | {x: IN} | {y: OUT} (some solutions) | One bound |
| `testModeComparisonBothOUT` | `x > y` | {} | {} (infinite) | Both unbound |
| `testModeConjunction` | `x elem L andalso x > 0` | {L: IN} | {x: OUT} | Mode propagation |
| `testModeDisjunction` | `x elem L1 orelse x elem L2` | {L1: IN, L2: IN} | {x: OUT} | Disjunction |
| `testModeNegation` | `not (x > y)` | {x: IN} | {} (unsupported) | Negation handling |

**Execution**: `mvn test -Dtest=ModeAnalyzerTest#testMode*`

---

### 2. Integration Tests

Location: `src/test/java/net/hydromatic/morel/compile/PredicateInverterIntegrationTest.java`

#### 2a. Tabulation Workflow Tests

**Purpose**: Validate complete tabulation process with real data.

| Test Case | Predicate | Input Data | Expected Output | Notes |
|-----------|-----------|-----------|-----------------|-------|
| `testTabulate2HopPath` | `path(x,y)` | edges: {(1,2), (2,3)} | {(1,2), (2,3), (1,3)} | Simple chain |
| `testTabulate3HopPath` | `path(x,y)` | edges: {(1,2), (2,3), (3,4)} | All transitive pairs | Extended chain |
| `testTabulateDiamondGraph` | `path(x,y)` | edges: {(1,2), (1,3), (2,4), (3,4)} | All reachable paths | Diamond pattern |
| `testTabulateWithCycles` | `path(x,y)` | edges with cycles | Correct closure | Cycle handling |
| `testTabulateEmptyGraph` | `path(x,y)` | edges: {} | {} | Empty input |
| `testTabulateSingleNode` | `path(x,y)` | edges: {(1,1)} | {(1,1)} | Self-loop |

**Execution**: `mvn test -Dtest=PredicateInverterIntegrationTest#testTabulate*`

---

#### 2b. Complete Iteration Tests

**Purpose**: Validate end-to-end iteration with proper termination.

| Test Case | Predicate | Graph | Iterations | Expected | Notes |
|-----------|-----------|-------|-----------|----------|-------|
| `testIterateTermination` | `path(x,y)` | 2-hop chain | 2 | Correct closure | Proper termination |
| `testIteratePerformance` | `path(x,y)` | 5-node graph | 5 | Runs in <1s | Performance gate |
| `testIterateNoNewResults` | `path(x,y)` | Already transitive | 1 | No iteration | Immediate termination |
| `testIterateWithDuplicates` | `path(x,y)` | Multiple paths | 2+ | No duplicates | Duplicate removal |

**Execution**: `mvn test -Dtest=PredicateInverterIntegrationTest#testIterate*`

---

#### 2c. Type Checking Tests

**Purpose**: Validate type correctness of generated code.

| Test Case | Generated Expression | Expected Type | Notes |
|-----------|----------------------|---------------|-------|
| `testTypeCheckIterateExpression` | `Relational.iterate edges stepFn` | `(int * int) bag` | Type inference |
| `testTypeCheckStepFunction` | `fn (old, new) => from ...` | `(int*int) bag * (int*int) bag -> (int*int) bag` | Step sig |
| `testTypeCheckJoinExpression` | Join with type mismatch | Type error | Error detection |
| `testTypeCheckTupleProjection` | Yield with wrong tuple | Type error | Projection check |

**Execution**: `mvn test -Dtest=PredicateInverterIntegrationTest#testTypeCheck*`

---

### 3. Script Tests (Morel Language Tests)

Location: `src/test/resources/script/such-that.smli`, `dummy.smli`

#### 3a. Regression Tests (Phase 1-2)

**Purpose**: Ensure no performance regressions from Phase 1-2.

**Test File**: `such-that.smli` (existing 301 tests)

```bash
# Run all existing tests
mvn test -Dtest=ScriptTest

# Expected: All 301 tests pass (baseline: ~18.9 seconds)
```

**Baseline Metrics**:
- Total tests: 301
- Expected execution time: Baseline (to be measured at start of Phase 3)
- Success rate: 100%

---

#### 3b. Phase 3 Transitive Closure Tests

**Purpose**: Validate transitive closure implementation.

**Test File**: `dummy.smli`

```sml
(* Test: Basic transitive closure *)
fun path (x, y) = edge (x, y) orelse
  (exists z where edge (x, z) andalso path (z, y))

val edges = [(1,2), (2,3)];
from p where path p;
(*
> val it = [(1,2),(2,3),(1,3)] : (int * int) bag
*)

(* Test: Extended chain *)
val edges = [(1,2), (2,3), (3,4)];
from p where path p;
(*
> val it = [(1,2),(2,3),(3,4),(1,3),(2,4),(1,4)] : (int * int) bag
*)
```

**Added to such-that.smli** (line 402+):

```sml
(* Tests for transitive closure *)
val edges = [(1, 2), (2, 3)];
from p where path p;
> val it = [(1, 2), (2, 3), (1, 3)] : (int * int) bag

val edges = [(1, 2), (2, 3), (3, 4)];
from p where path p;
> val it = [(1, 2), (2, 3), (3, 4), (1, 3), (2, 4), (1, 4)] : (int * int) bag
```

**Acceptance Criteria**:
- ✅ Returns all transitive pairs
- ✅ No duplicates
- ✅ Execution time < 1s for small graphs
- ✅ Correct tuple ordering in output

---

#### 3c. Phase 4 Multi-Mode Tests

**Purpose**: Validate multi-mode generation.

**New Test File**: `src/test/resources/script/mode-analysis-tests.smli`

```sml
(* Test: Forward mode - find paths from 1 *)
from y where path (1, y);
> val it = [2, 3] : int bag
(* 1→2 (direct), 1→3 (via 2) *)

(* Test: Backward mode - find sources reaching 5 *)
from x where path (x, 5);
> val it = [3, 4, 5] : int bag
(* 3→5 (via 4), 4→5 (direct), 5→5 (self) *)

(* Test: Both unbound *)
from p where path p;
> val it = [(1,2), (2,3), (1,3), ...] : (int * int) bag

(* Test: Boolean query *)
from () where path (1, 3);
> val it = [()] : unit bag
(* Returns one result if path exists *)
```

**Acceptance Criteria**:
- ✅ Forward mode: generates reachable targets
- ✅ Backward mode: generates reachable sources
- ✅ All-unbound mode: generates all pairs
- ✅ Boolean query: detects existence
- ✅ All modes return correct results
- ✅ Performance acceptable for all modes

---

#### 3d. Edge Case Tests

**Purpose**: Validate handling of edge cases.

**Test Cases**:

```sml
(* Empty graph *)
val edges = [];
from p where path p;
> val it = [] : (int * int) bag

(* Self-loop *)
val edges = [(1, 1)];
from p where path p;
> val it = [(1, 1)] : (int * int) bag

(* Single node no self-loop *)
val edges = [];
from p where path (1, 1);
> val it = [] : (int * int) bag

(* Multiple cycles *)
val edges = [(1, 2), (2, 3), (3, 1), (2, 4)];
from p where path p;
> val it = [(1,2),(2,3),(3,1),(2,4),(1,3),(3,2),(2,1),(1,4),(3,4),(2,2),(1,1),(3,3)] : (int * int) bag
(* All reachable within the cycle *)
```

---

### 4. Performance Tests

#### 4a. Baseline Tests

**Purpose**: Establish performance baseline before optimization.

```bash
# Run baseline suite
mvn test -Dtest=ScriptTest

# Measure execution time
time mvn test -Dtest=ScriptTest -q

# Record: baseline_time = X seconds
# Record: baseline_tests = 301 passing
```

**Baseline Metrics to Record**:
- Total test execution time
- Per-test average time
- Memory usage
- Compilation time

---

#### 4b. Regression Tests

**Purpose**: Ensure no performance degradation.

**After each phase, run**:

```bash
mvn test -Dtest=ScriptTest -q
```

**Acceptance Criteria**:
- ✅ Total execution time within 10% of baseline
- ✅ All 159+ tests still passing
- ✅ No out-of-memory errors
- ✅ Garbage collection overhead acceptable

---

#### 4c. Large Graph Tests (Phase 4c Candidate)

**Purpose**: Validate performance on larger graphs.

```sml
(* Generate 100-node fully connected graph *)
val edges = [(i, j) : i in 1..100, j in (i+1)..100];
from p where path p;
(*
Expected: Complete transitive closure
Acceptable: < 5s for 100 nodes
*)
```

---

### 5. Error Handling Tests

#### 5a. Invalid Mode Tests

**Purpose**: Validate graceful error handling for impossible modes.

| Test Case | Mode | Expected Behavior | Notes |
|-----------|------|------------------|-------|
| `testMode_ImpossibleInequality` | `x < 0 andalso x > 100` | Error or empty | Impossible constraint |
| `testMode_InfiniteGenerator` | Both variables unbound, no constraint | Fallback to cartesian | Acceptable degradation |
| `testMode_TypeMismatch` | `x elem "string"` where x is int | Type error | Type checking |

---

#### 5b. Pattern Mismatch Tests

**Purpose**: Validate fallback behavior for unsupported patterns.

| Test Case | Pattern | Expected Behavior | Notes |
|-----------|---------|------------------|-------|
| `testPattern_NonRecursive` | Regular predicate | Falls back to default | No iterate |
| `testPattern_NestedRecursion` | Recursive inside exists | Pattern mismatch | Limited support |
| `testPattern_MultipleRecursiveCalls` | f(...) andalso f(...) | Pattern mismatch | Pattern limit |

---

### 6. Quality Gate Tests

#### Quality Gate 1: After Phase 3c

```bash
# Run all Phase 1-2 regression tests
mvn test -Dtest=ScriptTest

# Verify:
# - 159 tests pass
# - dummy.smli transitive closure passes
# - No performance regression
```

**Gate Owner**: code-review-expert

---

#### Quality Gate 2: After Phase 4b

```bash
# Run all tests including Phase 4 multi-mode
mvn test -Dtest=ScriptTest
mvn test -Dtest=ModeAnalyzerTest
mvn test -Dtest=PredicateInverterIntegrationTest

# Verify:
# - 301+ tests pass
# - Mode tests pass
# - Integration tests pass
# - Performance acceptable
```

**Gate Owner**: test-validator

---

## Test Execution Workflow

### Daily Development

```bash
# Run tests affected by today's changes
mvn test -Dtest=PredicateInverterTest#testCurrentEpic*

# Before commit: run full suite
mvn test
```

### End of Sprint

```bash
# Full regression suite
mvn test -Dtest=ScriptTest
mvn test -Dtest=PredicateInverterTest
mvn test -Dtest=PredicateInverterIntegrationTest

# Record performance metrics
# Verify all quality gates
```

### Before Releasing Phase

```bash
# Clean build with tests
mvn clean test

# Verify: 301+ tests pass
# Verify: Performance acceptable
# Verify: No regressions
```

---

## Test Data Sets

### Small Graph (Testing)

```
Nodes: 3
Edges: [(1,2), (2,3)]
Path closure: {(1,2), (2,3), (1,3)}
```

### Medium Graph (Development)

```
Nodes: 10
Edges: Random DAG with ~20 edges
Expected: Full transitive closure
```

### Large Graph (Performance Validation)

```
Nodes: 100+
Edges: Varies by test
Used for: Performance regression detection
```

---

## Continuous Integration

### GitHub Actions Workflow

```yaml
name: Tests
on: [push, pull_request]
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v2
      - uses: actions/setup-java@v2
      - run: mvn test
      - run: mvn test -Dtest=ScriptTest
      # Performance comparison
      - run: |
          TIME=$(time mvn test -q 2>&1 | grep real)
          echo "Test execution: $TIME"
```

---

## Success Metrics

### Phase 3

| Metric | Target | Current |
|--------|--------|---------|
| Existing tests pass | 100% (301) | TBD |
| Transitive closure test | PASS | TBD |
| Performance regression | < 10% | TBD |
| Code review | PASS | TBD |

### Phase 4

| Metric | Target | Current |
|--------|--------|---------|
| All tests pass | 100% (301+) | TBD |
| Mode tests pass | 100% | TBD |
| Multi-mode tests | 100% | TBD |
| Performance regression | < 10% | TBD |
| Code review | PASS | TBD |

---

## Test Infrastructure

### New Test Classes

1. `ModeAnalyzerTest` - Mode inference unit tests
2. `PredicateInverterIntegrationTest` - End-to-end tabulation tests
3. `PredicateInverterPerformanceTest` - Benchmarking tests

### New Test Suites

1. `such-that.smli` (extended) - Phase 3-4 tests
2. `mode-analysis-tests.smli` - Phase 4 mode tests
3. `performance-tests.smli` - Performance benchmarks

---

## Document History

- **Created**: 2026-01-23
- **Version**: 1.0
- **Status**: Ready for implementation
- **Next Review**: End of Phase 3
