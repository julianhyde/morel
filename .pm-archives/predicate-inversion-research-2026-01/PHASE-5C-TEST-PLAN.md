# Phase 5c: Comprehensive Test Plan for Transitive Closure Predicate Inversion

**Date**: 2026-01-24
**Bead**: morel-9af
**Status**: COMPLETE
**Author**: Strategic Planner Agent
**Purpose**: Define comprehensive test suite validating all aspects of transitive closure implementation

---

## Executive Summary

This test plan defines 24 comprehensive tests across 6 categories to validate the transitive closure predicate inversion implementation. The tests cover correctness, pattern variations, performance, edge cases, integration scenarios, and regression protection.

**Key Finding**: The basic transitive closure test at `such-that.smli:737-744` is ALREADY PASSING. This plan expands coverage to ensure robustness.

---

## Test Categories Overview

| Category | Tests | Purpose | Priority |
|----------|-------|---------|----------|
| 1. Correctness | 8 | Validate core algorithm behavior | P0 - MANDATORY |
| 2. Pattern Variation | 4 | Validate pattern recognition flexibility | P1 - HIGH |
| 3. Performance | 3 | Validate scalability | P1 - HIGH |
| 4. Edge Cases | 4 | Validate graceful degradation | P1 - HIGH |
| 5. Integration | 4 | Validate interoperability | P2 - MEDIUM |
| 6. Regression | 1 suite | Ensure no breakage | P0 - MANDATORY |
| **Total** | **24** | | |

---

## Category 1: Correctness Tests (P0 - MANDATORY)

### Test 1.1: Basic Transitive Closure [EXISTING - PASSING]

**Status**: ALREADY IMPLEMENTED AND PASSING

**Input**:
```sml
val edges = [{x = 1, y = 2}, {x = 2, y = 3}]
fun edge (x, y) = {x, y} elem edges
fun path (x, y) = edge (x, y) orelse
  (exists z where path (x, z) andalso edge (z, y))
from p where path p
```

**Expected Output**: `[(1,2),(2,3),(1,3)]`

**Location**: `src/test/resources/script/such-that.smli:737-744`

**Why This Works**: Validates basic transitive closure generates correct results with:
- Direct edges preserved
- One-hop transitive edge computed
- No duplicates

**Pass Criteria**: Output contains exactly 3 tuples with set equality

---

### Test 1.2: Empty Base

**Input**:
```sml
val edges = [] : (int * int) list
fun edge (x, y) = (x, y) elem edges
fun path (x, y) = edge (x, y) orelse
  (exists z where path (x, z) andalso edge (z, y))
from p where path p
```

**Expected Output**: `[]`

**Why**: Boundary condition - no edges means no paths

**Pass Criteria**:
- Returns empty list/bag
- Does NOT throw exception
- Does NOT hang indefinitely

---

### Test 1.3: Single Edge

**Input**:
```sml
val edges = [(1, 2)]
fun edge (x, y) = (x, y) elem edges
fun path (x, y) = edge (x, y) orelse
  (exists z where path (x, z) andalso edge (z, y))
from p where path p
```

**Expected Output**: `[(1,2)]`

**Why**: Base case only - no recursion needed

**Pass Criteria**: Output is exactly `[(1,2)]`

---

### Test 1.4: Self-Loop (Reflexive Edge)

**Input**:
```sml
val edges = [(1, 1)]
fun edge (x, y) = (x, y) elem edges
fun path (x, y) = edge (x, y) orelse
  (exists z where path (x, z) andalso edge (z, y))
from p where path p
```

**Expected Output**: `[(1,1)]`

**Why**: Edge case - same start and end vertex

**Pass Criteria**:
- Output is exactly `[(1,1)]`
- No infinite loop (self-join doesn't generate new tuples)

---

### Test 1.5: Linear Chain (Long Path)

**Input**:
```sml
val edges = [(1,2), (2,3), (3,4), (4,5)]
fun edge (x, y) = (x, y) elem edges
fun path (x, y) = edge (x, y) orelse
  (exists z where path (x, z) andalso edge (z, y))
from p where path p
```

**Expected Output**: 10 tuples total
- Direct (4): (1,2), (2,3), (3,4), (4,5)
- 1-hop (3): (1,3), (2,4), (3,5)
- 2-hop (2): (1,4), (2,5)
- 3-hop (1): (1,5)

**Why**: Multiple iteration rounds needed to reach fixpoint

**Pass Criteria**:
- Exactly 10 tuples
- All expected pairs present
- No duplicates

---

### Test 1.6: Cyclic Graph (Loop Detection) - CRITICAL

**Input**:
```sml
val edges = [(1,2), (2,3), (3,1)]
fun edge (x, y) = (x, y) elem edges
fun path (x, y) = edge (x, y) orelse
  (exists z where path (x, z) andalso edge (z, y))
from p where path p
```

**Expected Output**: 6 tuples (full connectivity)
- (1,2), (2,3), (3,1), (1,3), (2,1), (3,2)

**Why**: Verifies cycle handling WITHOUT infinite loops

**Pass Criteria**:
- MUST terminate (fixpoint detection works)
- Exactly 6 tuples
- No duplicates
- Execution time < 1 second

---

### Test 1.7: Disconnected Components

**Input**:
```sml
val edges = [(1,2), (3,4), (5,6)]
fun edge (x, y) = (x, y) elem edges
fun path (x, y) = edge (x, y) orelse
  (exists z where path (x, z) andalso edge (z, y))
from p where path p
```

**Expected Output**: `[(1,2), (3,4), (5,6)]`

**Why**: No false connections between disconnected components

**Pass Criteria**:
- Exactly 3 tuples
- NO cross-component pairs (e.g., NOT (1,4))

---

### Test 1.8: Diamond Pattern (Multiple Paths)

**Input**:
```sml
val edges = [(1,2), (1,3), (2,4), (3,4)]
fun edge (x, y) = (x, y) elem edges
fun path (x, y) = edge (x, y) orelse
  (exists z where path (x, z) andalso edge (z, y))
from p where path p
```

**Expected Output**: 5 tuples
- Direct (4): (1,2), (1,3), (2,4), (3,4)
- Transitive (1): (1,4) - via either 2 or 3

**Why**: Multiple paths to same destination - deduplication required

**Pass Criteria**:
- Exactly 5 tuples
- (1,4) appears exactly ONCE (not twice)
- No other duplicates

---

## Category 2: Pattern Variation Tests (P1)

These tests validate flexibility in pattern recognition per Phase 5b specification.

### Test 2.1: Reversed Base/Recursive Order

**Input**:
```sml
val edges = [(1,2), (2,3)]
fun edge (x, y) = (x, y) elem edges
(* Note: orelse order reversed from standard pattern *)
fun path (x, y) =
  (exists z where path (x, z) andalso edge (z, y)) orelse edge (x, y)
from p where path p
```

**Expected Output**: `[(1,2),(2,3),(1,3)]` (same as Test 1.1)

**Why**: Pattern should work regardless of orelse branch order

**Pass Criteria**: Same results as Test 1.1

---

### Test 2.2: Different Variable Names

**Input**:
```sml
val edges = [(1,2), (2,3)]
fun edge (a, b) = (a, b) elem edges
fun path (a, b) = edge (a, b) orelse
  (exists mid where path (a, mid) andalso edge (mid, b))
from p where path p
```

**Expected Output**: `[(1,2),(2,3),(1,3)]`

**Why**: Variable names should not affect pattern recognition

**Pass Criteria**: Same results as Test 1.1

---

### Test 2.3: Record-Based Edges

**Input**:
```sml
val edges = [{src=1, dst=2}, {src=2, dst=3}]
fun edge (x, y) = {src=x, dst=y} elem edges
fun path (x, y) = edge (x, y) orelse
  (exists z where path (x, z) andalso edge (z, y))
from p where path p
```

**Expected Output**: `[(1,2),(2,3),(1,3)]`

**Why**: Different data representation (records vs tuples)

**Pass Criteria**: Same logical results as Test 1.1

---

### Test 2.4: Alternative Conjunction Order

**Input**:
```sml
val edges = [(1,2), (2,3)]
fun edge (x, y) = (x, y) elem edges
(* Note: edge(z,y) before path(x,z) in conjunction *)
fun path (x, y) = edge (x, y) orelse
  (exists z where edge (z, y) andalso path (x, z))
from p where path p
```

**Expected Output**: `[(1,2),(2,3),(1,3)]`

**Why**: Conjunction order should not affect semantics

**Pass Criteria**: Same results as Test 1.1

---

## Category 3: Performance Tests (P1)

### Test 3.1: Small Graph (10 edges)

**Input**:
```sml
val edges = [
  (1,2), (2,3), (3,4), (4,5), (5,6),
  (1,3), (2,4), (3,5), (4,6), (6,7)
]
fun edge (x, y) = (x, y) elem edges
fun path (x, y) = edge (x, y) orelse
  (exists z where path (x, z) andalso edge (z, y))
from p where path p
```

**Expected Behavior**:
- Completes execution
- Result count: ~30-40 tuples (including transitives)
- Execution time: < 100ms

**Pass Criteria**:
- No timeout
- Correct result count
- Time within limit

---

### Test 3.2: Medium Graph (50 edges)

**Input**: Programmatically generated graph with 50 edges

**Expected Behavior**:
- Completes execution
- Execution time: < 1 second

**Pass Criteria**:
- No timeout
- No memory errors
- Time within limit

---

### Test 3.3: Large Graph (100+ edges)

**Input**: Programmatically generated graph with 100+ edges

**Expected Behavior**:
- Completes execution
- Execution time: < 10 seconds

**Pass Criteria**:
- Terminates successfully
- No OutOfMemoryError
- Time within limit (allows for iteration overhead)

---

## Category 4: Edge Case Tests (P1)

### Test 4.1: Unsupported Pattern - Graceful Fallback

**Input**:
```sml
val edges = [(1,2), (2,3)]
fun edge (x, y) = (x, y) elem edges
(* Non-transitive pattern - not actually path function *)
fun notPath (x, y) = edge (x, y) andalso x <> y
from p where notPath p
```

**Expected Behavior**:
- Should NOT try to optimize as transitive closure
- Falls back to standard evaluation
- Returns correct result: `[(1,2), (2,3)]`

**Pass Criteria**:
- No exceptions
- Correct result via fallback
- No false optimization

---

### Test 4.2: Type Mismatch Handling

**Input**:
```sml
val edges = [(1,2), (2,3)]
fun edge (x, y) = (x, y) elem edges
fun path (x: string, y: int) = (* type mismatch *)
  edge (x, y) orelse
  (exists z where path (x, z) andalso edge (z, y))
```

**Expected Behavior**:
- Type checker should catch error
- Clear error message
- No runtime crash

**Pass Criteria**: Type error detected at compile time

---

### Test 4.3: Deeply Nested Exists

**Input**:
```sml
val edges = [(1,2), (2,3)]
fun edge (x, y) = (x, y) elem edges
fun path (x, y) = edge (x, y) orelse
  (exists z1 where
    (exists z2 where edge (x, z2) andalso path (z2, z1) andalso edge (z1, y)))
```

**Expected Behavior**:
- Pattern may not be recognized (too complex)
- Graceful fallback or error
- No crash

**Pass Criteria**: Handles without crash

---

### Test 4.4: Variable Name Shadowing

**Input**:
```sml
val x = 99
val edges = [(1,2), (2,3)]
fun edge (x, y) = (x, y) elem edges  (* x shadows outer x *)
fun path (x, y) = edge (x, y) orelse
  (exists z where path (x, z) andalso edge (z, y))
from p where path p
```

**Expected Behavior**:
- Lexical scoping should resolve correctly
- Same results as Test 1.1

**Pass Criteria**:
- Outer `x` (99) not confused with pattern variable
- Correct result: `[(1,2),(2,3),(1,3)]`

---

## Category 5: Integration Tests (P2)

### Test 5.1: Combined with WHERE Filter

**Input**:
```sml
val edges = [(1,2), (2,3), (3,4)]
fun edge (x, y) = (x, y) elem edges
fun path (x, y) = edge (x, y) orelse
  (exists z where path (x, z) andalso edge (z, y))
from (x, y) where path (x, y) andalso x = 1
```

**Expected Output**: All paths starting from 1: `[(1,2),(1,3),(1,4)]`

**Pass Criteria**: Filter correctly applied after transitive closure

---

### Test 5.2: With ORDER BY Clause

**Input**:
```sml
val edges = [(1,2), (2,3)]
fun edge (x, y) = (x, y) elem edges
fun path (x, y) = edge (x, y) orelse
  (exists z where path (x, z) andalso edge (z, y))
from p where path p order p
```

**Expected Output**: `[(1,2),(1,3),(2,3)]` (sorted by tuple)

**Pass Criteria**: Results correctly ordered

---

### Test 5.3: With YIELD Clause

**Input**:
```sml
val edges = [(1,2), (2,3)]
fun edge (x, y) = (x, y) elem edges
fun path (x, y) = edge (x, y) orelse
  (exists z where path (x, z) andalso edge (z, y))
from (x, y) where path (x, y) yield {src = x, dst = y}
```

**Expected Output**: Records `[{src=1,dst=2},{src=2,dst=3},{src=1,dst=3}]`

**Pass Criteria**: Yield transforms results correctly

---

### Test 5.4: Nested Query

**Input**:
```sml
val edges = [(1,2), (2,3)]
fun edge (x, y) = (x, y) elem edges
fun path (x, y) = edge (x, y) orelse
  (exists z where path (x, z) andalso edge (z, y))
from x in [1, 2]
  where exists (from (a, b) where path (a, b) andalso a = x)
```

**Expected Output**: `[1, 2]` (both have outgoing paths)

**Pass Criteria**: Nested exists works with transitive closure

---

## Category 6: Regression Tests (P0 - MANDATORY)

### Test 6.1: Full Test Suite Regression

**Scope**: All existing tests must continue to pass

**Test Files**:
1. `src/test/resources/script/such-that.smli` - ALL tests
2. `src/test/java/net/hydromatic/morel/compile/PredicateInverterTest.java` - ALL enabled tests
3. Full Maven test suite: `./mvnw test`

**Pass Criteria**:
- ZERO new test failures
- All previously passing tests still pass
- No performance degradation (tests complete in similar time)

---

## Test Implementation Plan

### Phase 5c Deliverables (THIS DOCUMENT)
1. Test specifications complete
2. Expected results documented
3. Pass/fail criteria defined
4. Test location/format specified

### Phase 5d Implementation Tasks
1. Add Tests 1.2-1.8 to `such-that.smli` (or new file)
2. Add pattern variation tests (2.1-2.4)
3. Create performance test class
4. Add edge case tests
5. Run full regression suite

### Test File Organization

```
src/test/resources/script/
  such-that.smli           # Existing - Test 1.1 already here
  transitive-closure.smli  # NEW - Tests 1.2-1.8, 2.1-2.4, 5.1-5.4

src/test/java/net/hydromatic/morel/
  compile/
    PredicateInverterTest.java    # Existing unit tests
    TransitiveClosureTest.java    # NEW - Performance tests 3.1-3.3
```

---

## Success Criteria Summary

### Phase 5c SUCCESS (This Task)
- [x] Test plan document complete
- [x] 24 tests specified with expected results
- [x] Pass/fail criteria defined
- [x] Test locations specified
- [x] Performance targets defined
- [x] Regression strategy documented

### Phase 5d SUCCESS (Next Task - Uses This Plan)
- [ ] All 8 correctness tests pass (100%)
- [ ] At least 3 of 4 pattern variation tests pass (75%+)
- [ ] All 3 performance tests complete within limits
- [ ] At least 3 of 4 edge case tests handle gracefully
- [ ] At least 3 of 4 integration tests pass
- [ ] Zero regressions in full test suite
- [ ] Code coverage >= 80% for predicate inversion code

### Overall Phase 5 SUCCESS
- [ ] Phase 5a: GO (COMPLETE)
- [ ] Phase 5b: Pattern specification (pending)
- [ ] Phase 5c: Test plan (THIS DOCUMENT - COMPLETE)
- [ ] Phase 5d: Prototype validation (uses this plan)

---

## Risk Mitigation

### Risk 1: Tests Reveal Bugs
- **Mitigation**: Bugs found are SUCCESS - this is the purpose of testing
- **Response**: Document bug, create bead, fix in Phase 5d

### Risk 2: Performance Tests Fail
- **Mitigation**: Performance issues indicate optimization needed
- **Response**: Profile, identify bottleneck, optimize

### Risk 3: Pattern Variation Tests Fail
- **Mitigation**: Informs Phase 5b pattern specification
- **Response**: Narrow supported patterns or fix pattern matcher

### Risk 4: Regression Tests Fail
- **Mitigation**: Most critical - indicates breaking change
- **Response**: STOP implementation, fix regression first

---

## Appendix A: Test Data Generators

### Large Graph Generator (for Performance Tests)

```sml
(* Generate chain graph with n vertices *)
fun genChain n =
  List.tabulate (n - 1, fn i => (i + 1, i + 2))

(* Generate complete graph with n vertices *)
fun genComplete n =
  from i in List.tabulate (n, fn x => x + 1),
       j in List.tabulate (n, fn x => x + 1)
    where i <> j
    yield (i, j)

(* Generate random-ish graph *)
fun genRandom n density =
  from i in List.tabulate (n, fn x => x + 1),
       j in List.tabulate (n, fn x => x + 1)
    where i <> j andalso (i * 7 + j * 13) mod 100 < density * 100
    yield (i, j)
```

---

## Appendix B: Expected Tuple Counts

| Test | Input Edges | Expected Output Tuples | Formula |
|------|-------------|------------------------|---------|
| 1.1 | 2 | 3 | Base + transitive |
| 1.2 | 0 | 0 | Empty |
| 1.3 | 1 | 1 | Base only |
| 1.4 | 1 | 1 | Self-loop |
| 1.5 | 4 | 10 | n*(n+1)/2 for chain |
| 1.6 | 3 | 6 | Full connectivity |
| 1.7 | 3 | 3 | No cross-component |
| 1.8 | 4 | 5 | Diamond dedup |

---

## Changelog

- 2026-01-24: Initial test plan created (Phase 5c)
- Pending: Test implementation (Phase 5d)

---

**END OF PHASE 5c TEST PLAN**
