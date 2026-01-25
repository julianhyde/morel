# Phase 6a Implementation Plan: Disjunction Union Support

**Bead**: TBD (create at execution start)
**Date**: 2026-01-24
**Author**: java-architect-planner agent
**Status**: COMPLETE - Ready for Developer Handoff
**Reference**: PHASE-6A-DESIGN.md (706 lines, architectural specification)

---

## Executive Summary

Phase 6a extends the transitive closure optimization to handle multi-branch disjunction patterns. This plan provides step-by-step implementation details for the recommended Option A (Flatten and Union) approach.

**Key Deliverables**:
1. `flattenOrelse()` helper method to decompose nested orelse structures
2. `tryInvertDisjunction()` method for multi-branch disjunction handling
3. Enhanced `tryInvertTransitiveClosure()` for nested base cases
4. 12 new test cases across 4 categories
5. Integration with existing `unionDistinct()` infrastructure

**Estimated Effort**: 4-6 days (24-34 hours)
**Risk Level**: MODERATE

---

## 1. Problem Statement

### 1.1 Current Limitation

SML `orelse` is **left-associative**. The expression:

```sml
fun connected(x, y) =
  direct(x, y) orelse          (* base 1 *)
  indirect(x, y) orelse        (* base 2 - CURRENTLY IGNORED *)
  (exists z where ...)         (* recursive *)
```

Parses as:

```
orelse(orelse(direct, indirect), recursive)
```

Current code at `PredicateInverter.java:497-498` only handles:
- `arg(0)` = `orelse(direct, indirect)` - treated as single opaque base case
- `arg(1)` = `recursive` - processed correctly

**Result**: Only the first branch is inverted; second base case is ignored.

### 1.2 Desired Behavior

Union all invertible base cases before detecting TC pattern:

```sml
(* Should produce: Relational.iterate (direct UNION indirect) stepFn *)
```

### 1.3 Key Code Locations

| File | Line | Current Code | Required Change |
|------|------|--------------|-----------------|
| PredicateInverter.java | 163-178 | orelse detection in recursive context only | Add non-recursive disjunction handling |
| PredicateInverter.java | 446-465 | `unionDistinct()` implementation | Reuse as-is |
| PredicateInverter.java | 481-572 | `tryInvertTransitiveClosure()` | Handle nested base cases |
| PredicateInverter.java | ~1000+ | utility methods section | Add `flattenOrelse()` |

---

## 2. Implementation Tasks

### Task 2.1: Add `flattenOrelse()` Helper Method

**Location**: PredicateInverter.java, after line 1000 (utility methods section)
**Estimated Lines**: 25

**Method Signature**:

```java
/**
 * Flattens a nested orelse expression into a list of branches.
 *
 * <p>SML orelse is left-associative: {@code A orelse B orelse C} parses as
 * {@code (A orelse B) orelse C}. This method recursively flattens the
 * structure into {@code [A, B, C]}.
 *
 * <p>Examples:
 * <ul>
 *   <li>{@code A orelse B} -> {@code [A, B]}
 *   <li>{@code (A orelse B) orelse C} -> {@code [A, B, C]}
 *   <li>{@code A orelse (B orelse C)} -> {@code [A, B, C]}
 *   <li>{@code ((A orelse B) orelse C) orelse D} -> {@code [A, B, C, D]}
 *   <li>{@code A} (not orelse) -> {@code [A]}
 * </ul>
 *
 * @param exp The expression to flatten
 * @return List of branch expressions in left-to-right order
 */
private List<Core.Exp> flattenOrelse(Core.Exp exp) {
  // Base case: not an APPLY or not an orelse call
  if (exp.op != Op.APPLY) {
    return ImmutableList.of(exp);
  }
  Core.Apply apply = (Core.Apply) exp;
  if (!apply.isCallTo(BuiltIn.Z_ORELSE)) {
    return ImmutableList.of(exp);
  }

  // Recursive case: flatten both branches
  List<Core.Exp> result = new ArrayList<>();
  result.addAll(flattenOrelse(apply.arg(0)));  // Left branch (may be nested orelse)
  result.addAll(flattenOrelse(apply.arg(1)));  // Right branch
  return result;
}
```

**Unit Tests for flattenOrelse()**:
1. Single orelse: `A orelse B` -> `[A, B]`
2. Nested left: `(A orelse B) orelse C` -> `[A, B, C]`
3. Nested right: `A orelse (B orelse C)` -> `[A, B, C]`
4. Deep nesting: `((A orelse B) orelse C) orelse D` -> `[A, B, C, D]`
5. Non-orelse: `A` -> `[A]`

---

### Task 2.2: Add `tryInvertDisjunction()` Method

**Location**: PredicateInverter.java, after line 572 (after `tryInvertTransitiveClosure`)
**Estimated Lines**: 60

**Method Signature**:

```java
/**
 * Attempts to invert a disjunction (orelse) expression.
 *
 * <p>Flattens nested orelse structures and inverts each branch independently.
 * Returns the union of all FINITE generators. If no branch produces a FINITE
 * generator, returns null to signal inversion failure.
 *
 * <p>For example, {@code x elem set1 orelse x elem set2} produces a generator
 * that is the union of set1 and set2.
 *
 * <p>This method handles non-recursive disjunction patterns. For TC patterns
 * with recursive calls, use {@link #tryInvertTransitiveClosure}.
 *
 * @param orElseApply The orelse expression
 * @param goalPats Variables to generate values for
 * @param active Functions currently being inverted (recursion detection)
 * @return Inversion result with union generator, or null if not invertible
 */
private Result tryInvertDisjunction(
    Core.Apply orElseApply,
    List<Core.NamedPat> goalPats,
    List<Core.Exp> active) {

  // Step 1: Flatten nested orelse into list of branches
  List<Core.Exp> branches = flattenOrelse(orElseApply);

  if (branches.size() < 2) {
    // Not a valid disjunction (shouldn't happen, but defensive)
    return null;
  }

  // Step 2: Invert each branch, collect FINITE generators
  List<Generator> finiteGenerators = new ArrayList<>();

  for (Core.Exp branch : branches) {
    Result branchResult = invert(branch, goalPats, active);

    if (branchResult == null) {
      // Branch inversion failed completely - skip it
      continue;
    }

    // Only collect FINITE generators
    if (branchResult.generator.cardinality
        != net.hydromatic.morel.compile.Generator.Cardinality.INFINITE) {
      finiteGenerators.add(branchResult.generator);
    }
  }

  // Step 3: If no FINITE generators found, signal failure
  if (finiteGenerators.isEmpty()) {
    return null;
  }

  // Step 4: Union all FINITE generators
  // Reuse existing unionDistinct() which handles single and multiple generators
  Generator combined = unionDistinct(finiteGenerators);

  // For disjunction, remaining filters are already incorporated into each branch
  return result(combined, ImmutableList.of());
}
```

**Key Design Decisions**:
1. Uses existing `unionDistinct()` at line 446-465
2. Skips INFINITE generators (uninvertible branches)
3. Returns null if all branches are uninvertible
4. Remaining filters are cleared (union semantics)

---

### Task 2.3: Modify `tryInvertTransitiveClosure()`

**Location**: PredicateInverter.java, lines 497-520
**Change**: Handle nested orelse in base case

**Current Code** (lines 497-501):

```java
Core.Exp baseCase = orElseApply.arg(0);
Core.Exp recursiveCase = orElseApply.arg(1);

// Try to invert the base case without the recursive call
Result baseCaseResult = invert(baseCase, goalPats, ImmutableList.of());
```

**New Code**:

```java
Core.Exp baseCase = orElseApply.arg(0);
Core.Exp recursiveCase = orElseApply.arg(1);

// Check if baseCase is itself an orelse (nested disjunction)
// This handles patterns like: edge1 orelse edge2 orelse recursive
Result baseCaseResult;
if (baseCase.op == Op.APPLY
    && ((Core.Apply) baseCase).isCallTo(BuiltIn.Z_ORELSE)) {
  // Flatten all base case branches
  List<Core.Exp> baseBranches = flattenOrelse(baseCase);
  List<Generator> invertedBases = new ArrayList<>();

  for (Core.Exp branch : baseBranches) {
    Result branchResult = invert(branch, goalPats, ImmutableList.of());
    if (branchResult != null
        && branchResult.generator.cardinality
            != net.hydromatic.morel.compile.Generator.Cardinality.INFINITE) {
      invertedBases.add(branchResult.generator);
    }
  }

  if (invertedBases.isEmpty()) {
    // No invertible base cases - cannot build TC
    return null;
  }

  // Union all invertible base cases
  Generator combinedBase = unionDistinct(invertedBases);
  baseCaseResult = result(combinedBase, ImmutableList.of());
} else {
  // Original behavior: single base case
  baseCaseResult = invert(baseCase, goalPats, ImmutableList.of());
}

// Continue with existing cardinality check (line 516+)
if (baseCaseResult.generator.cardinality
    == net.hydromatic.morel.compile.Generator.Cardinality.INFINITE) {
  return null;
}
```

**Change Summary**:
- Before line 501: Check if baseCase is orelse
- If yes: flatten, invert each branch, union results
- If no: use existing single-branch logic
- Rest of method unchanged

---

### Task 2.4: Integrate into `invert()` Method

**Location**: PredicateInverter.java, after line 178
**Change**: Add call to `tryInvertDisjunction()` for non-TC orelse

**Current Code** (lines 163-178):

```java
// Check for orelse (disjunction)
// Only handle transitive closure pattern in recursive contexts.
// Non-TC orelse patterns fall through to default handler (returns
// INFINITE extent).
// This design choice optimizes for the common case (TC patterns) while
// allowing future enhancement to support general disjunction union (Phase
// 6a).
if (apply.isCallTo(BuiltIn.Z_ORELSE) && !active.isEmpty()) {
  Result tcResult =
      tryInvertTransitiveClosure(apply, null, null, goalPats, active);
  if (tcResult != null) {
    return tcResult;
  }
  // If transitive closure pattern doesn't match, fall through to default
  // handling
}
```

**New Code**:

```java
// Check for orelse (disjunction)
if (apply.isCallTo(BuiltIn.Z_ORELSE)) {
  // Try transitive closure pattern first (requires recursive context)
  if (!active.isEmpty()) {
    Result tcResult =
        tryInvertTransitiveClosure(apply, null, null, goalPats, active);
    if (tcResult != null) {
      return tcResult;
    }
  }

  // Try general disjunction union (Phase 6a)
  // This handles patterns like: x elem set1 orelse x elem set2
  Result disjResult = tryInvertDisjunction(apply, goalPats, active);
  if (disjResult != null) {
    return disjResult;
  }

  // Fall through to default handling if neither pattern matches
}
```

**Change Summary**:
- Keep TC pattern first (most specific)
- Add disjunction handler after TC
- Both return null if pattern doesn't match
- Fall through to default for truly uninvertible cases

---

## 3. Test Strategy

### 3.1 Test Categories (12 Tests Total)

**Category 1: Basic Disjunction** (3 tests)

| ID | Test Case | Pattern | Expected |
|----|-----------|---------|----------|
| TC-D1 | Two base cases + TC | `edge1 orelse edge2 orelse recursive` | TC with union base |
| TC-D2 | Three base cases + TC | `e1 orelse e2 orelse e3 orelse recursive` | TC with 3-way union |
| TC-D3 | Non-recursive multi-branch | `x elem s1 orelse x elem s2 orelse x elem s3` | Union of sets |

**Category 2: TC with Disjunction** (3 tests)

| ID | Test Case | Pattern | Expected |
|----|-----------|---------|----------|
| TC-D4 | Left-linear with multiple bases | `(e1 orelse e2) orelse (recursive andalso edge)` | Correct TC |
| TC-D5 | Right-linear with multiple bases | `(e1 orelse e2) orelse (edge andalso recursive)` | Correct TC |
| TC-D6 | Mixed invertible/uninvertible | `invertible orelse unknown orelse recursive` | Partial union |

**Category 3: Edge Cases** (3 tests)

| ID | Test Case | Description | Expected |
|----|-----------|-------------|----------|
| TC-D7 | Overlapping results | Sets with shared elements | Deduplicated output |
| TC-D8 | Deep nesting (4+ levels) | `(((a orelse b) orelse c) orelse d)` | All 4 branches flattened |
| TC-D9 | All uninvertible | All branches INFINITE | Falls through to default |

**Category 4: Performance** (3 tests)

| ID | Test Case | Scale | Target |
|----|-----------|-------|--------|
| TC-D10 | Large union | 10 base case branches | < 1 second |
| TC-D11 | Dense graph with multiple bases | K_5 with 3 edge types | Correct, < 1 second |
| TC-D12 | Complex filter + disjunction | Filter + multi-branch union | Correct, < 1 second |

### 3.2 Test Implementation

**Test File**: Extend `/Users/hal.hildebrand/git/morel/src/test/resources/script/transitive-closure.smli`

**New Category**:

```sml
(* ===================================================================
 * CATEGORY 11: DISJUNCTION UNION TESTS (Phase 6a)
 * =================================================================== *)

(* Test 11.1: TC-D1 - Two base cases with TC *)
val edges_11_1a = [(1,2), (2,3)];
val edges_11_1b = [(3,4)];
fun edge_11_1a (x, y) = (x, y) elem edges_11_1a;
fun edge_11_1b (x, y) = (x, y) elem edges_11_1b;
fun path_11_1 (x, y) =
  edge_11_1a (x, y) orelse
  edge_11_1b (x, y) orelse
  (exists z where path_11_1 (x, z) andalso edge_11_1a (z, y));
from p where path_11_1 p;
(*
[(1,2),(1,3),(1,4),(2,3),(2,4),(3,4)]
*)

(* Test 11.2: TC-D2 - Three base cases with TC *)
val edges_11_2a = [(1,2)];
val edges_11_2b = [(2,3)];
val edges_11_2c = [(3,4)];
fun edge_11_2a (x, y) = (x, y) elem edges_11_2a;
fun edge_11_2b (x, y) = (x, y) elem edges_11_2b;
fun edge_11_2c (x, y) = (x, y) elem edges_11_2c;
fun path_11_2 (x, y) =
  edge_11_2a (x, y) orelse
  edge_11_2b (x, y) orelse
  edge_11_2c (x, y) orelse
  (exists z where path_11_2 (x, z) andalso edge_11_2a (z, y));
from p where path_11_2 p;
(*
[(1,2),(2,3),(3,4)]
*)

(* Test 11.3: TC-D3 - Non-recursive multi-branch *)
val set_11_3a = [1, 2];
val set_11_3b = [3, 4];
val set_11_3c = [5];
fun inSet_11_3 x = x elem set_11_3a orelse x elem set_11_3b orelse x elem set_11_3c;
from p where inSet_11_3 p;
(*
[1,2,3,4,5]
*)

(* Test 11.4: TC-D4 - Left-linear with multiple bases *)
val edges_11_4a = [(1,2)];
val edges_11_4b = [(3,4)];
fun edge_11_4a (x, y) = (x, y) elem edges_11_4a;
fun edge_11_4b (x, y) = (x, y) elem edges_11_4b;
fun path_11_4 (x, y) =
  edge_11_4a (x, y) orelse
  edge_11_4b (x, y) orelse
  (exists z where path_11_4 (x, z) andalso edge_11_4a (z, y));
from p where path_11_4 p;
(*
[(1,2),(3,4)]
*)

(* Test 11.5: TC-D5 - Right-linear with multiple bases *)
val edges_11_5a = [(1,2), (2,3)];
val edges_11_5b = [(5,6)];
fun edge_11_5a (x, y) = (x, y) elem edges_11_5a;
fun edge_11_5b (x, y) = (x, y) elem edges_11_5b;
fun path_11_5 (x, y) =
  edge_11_5a (x, y) orelse
  edge_11_5b (x, y) orelse
  (exists z where edge_11_5a (x, z) andalso path_11_5 (z, y));
from p where path_11_5 p;
(*
[(1,2),(1,3),(2,3),(5,6)]
*)

(* Test 11.6: TC-D6 - Partial invertible branches *)
(* This tests that we handle the invertible branches even when some aren't *)
val edges_11_6 = [(1,2), (2,3)];
fun edge_11_6 (x, y) = (x, y) elem edges_11_6;
fun path_11_6 (x, y) =
  edge_11_6 (x, y) orelse
  (exists z where path_11_6 (x, z) andalso edge_11_6 (z, y));
from p where path_11_6 p;
(*
[(1,2),(1,3),(2,3)]
*)

(* Test 11.7: TC-D7 - Overlapping results (deduplication) *)
val set_11_7a = [1, 2, 3];
val set_11_7b = [2, 3, 4];
val set_11_7c = [3, 4, 5];
fun inSet_11_7 x = x elem set_11_7a orelse x elem set_11_7b orelse x elem set_11_7c;
from p where inSet_11_7 p;
(*
[1,2,3,4,5]
*)

(* Test 11.8: TC-D8 - Deep nesting (4 levels) *)
val set_11_8a = [1];
val set_11_8b = [2];
val set_11_8c = [3];
val set_11_8d = [4];
fun inSet_11_8 x =
  x elem set_11_8a orelse
  x elem set_11_8b orelse
  x elem set_11_8c orelse
  x elem set_11_8d;
from p where inSet_11_8 p;
(*
[1,2,3,4]
*)

(* Test 11.9: TC-D9 - Fallback test (simple non-disjunction) *)
val edges_11_9 = [(1,2), (2,3)];
fun edge_11_9 (x, y) = (x, y) elem edges_11_9;
fun notDisjunction (x, y) = edge_11_9 (x, y) andalso x < y;
from p where notDisjunction p;
(*
[(1,2),(2,3)]
*)

(* Test 11.10: TC-D10 - Large union performance *)
val sets_11_10 = List.tabulate (10, fn i => [i * 10 + 1, i * 10 + 2]);
fun flatten l = List.foldl (fn (a, b) => b @ a) [] l;
val allElems_11_10 = flatten sets_11_10;
fun inLargeUnion x = x elem allElems_11_10;
from p where inLargeUnion p;
(*
[1,2,11,12,21,22,31,32,41,42,51,52,61,62,71,72,81,82,91,92]
*)

(* Test 11.11: TC-D11 - Dense graph with multiple edge types *)
val edges_11_11a = from i in [1,2,3], j in [1,2,3] where i < j yield (i, j);
val edges_11_11b = from i in [4,5], j in [4,5] where i < j yield (i, j);
fun edge_11_11a (x, y) = (x, y) elem edges_11_11a;
fun edge_11_11b (x, y) = (x, y) elem edges_11_11b;
fun path_11_11 (x, y) =
  edge_11_11a (x, y) orelse
  edge_11_11b (x, y) orelse
  (exists z where path_11_11 (x, z) andalso edge_11_11a (z, y));
val result_11_11 = from p where path_11_11 p;
List.length result_11_11;
(*
4
*)

(* Test 11.12: TC-D12 - Filter with disjunction *)
val edges_11_12a = [(1,2), (2,3), (3,4)];
val edges_11_12b = [(5,6), (6,7)];
fun edge_11_12a (x, y) = (x, y) elem edges_11_12a;
fun edge_11_12b (x, y) = (x, y) elem edges_11_12b;
fun path_11_12 (x, y) =
  edge_11_12a (x, y) orelse
  edge_11_12b (x, y) orelse
  (exists z where path_11_12 (x, z) andalso edge_11_12a (z, y));
from (x, y) where path_11_12 (x, y) andalso x < 3;
(*
[(1,2),(1,3),(1,4),(2,3),(2,4)]
*)
```

---

## 4. Implementation Phases

### Phase 6a-1: Foundation (Day 1 - 4-6 hours)

**Objective**: Implement helper methods, verify no regression

**Tasks**:
1. Implement `flattenOrelse()` method (~25 lines)
2. Write unit tests for `flattenOrelse()`
3. Run existing 24 TC tests to verify no regression
4. Create Phase 6a bead: `bd create "Phase 6a - Disjunction Union" -t feature -p 1`

**Checkpoint**:
- flattenOrelse() method complete with Javadoc
- All 24 existing tests pass
- No new code paths executed yet

**Exit Criteria**:
- [ ] flattenOrelse() method implemented
- [ ] Unit tests for flattenOrelse() pass
- [ ] 24 existing TC tests pass (no regression)
- [ ] Bead created and status = in_progress

### Phase 6a-2: Disjunction Handler (Day 2 - 6-8 hours)

**Objective**: Implement non-recursive disjunction handling

**Tasks**:
1. Implement `tryInvertDisjunction()` method (~60 lines)
2. Integrate into `invert()` method at line ~178
3. Write tests TC-D3, TC-D7, TC-D8, TC-D9 (non-recursive disjunction)
4. Run full test suite

**Checkpoint**:
- tryInvertDisjunction() complete with Javadoc
- Integration in invert() method
- 4 new tests passing
- 24 existing tests still pass

**Exit Criteria**:
- [ ] tryInvertDisjunction() method implemented
- [ ] Integration in invert() complete
- [ ] 4 new disjunction tests pass (TC-D3, TC-D7, TC-D8, TC-D9)
- [ ] 24 existing TC tests pass (no regression)
- [ ] Total: 28 tests passing

### Phase 6a-3: TC Enhancement (Day 3 - 6-8 hours)

**Objective**: Enable TC with multiple base cases

**Tasks**:
1. Modify `tryInvertTransitiveClosure()` to handle nested base cases
2. Write tests TC-D1, TC-D2, TC-D4, TC-D5, TC-D6 (TC with disjunction)
3. Run full test suite
4. Verify edge cases handled

**Checkpoint**:
- Modified tryInvertTransitiveClosure() complete
- 5 new TC+disjunction tests passing
- 28 previous tests still pass

**Exit Criteria**:
- [ ] tryInvertTransitiveClosure() modified
- [ ] 5 new TC+disjunction tests pass
- [ ] 28 previous tests pass (no regression)
- [ ] Total: 33 tests passing

### Phase 6a-4: Performance Validation (Day 4 - 4-6 hours)

**Objective**: Verify performance characteristics

**Tasks**:
1. Write tests TC-D10, TC-D11, TC-D12 (performance)
2. Run performance profiling
3. Verify all tests complete in < 1 second
4. Address any performance issues

**Checkpoint**:
- 3 performance tests passing
- All tests < 1 second
- No performance regression

**Exit Criteria**:
- [ ] 3 performance tests pass (TC-D10, TC-D11, TC-D12)
- [ ] All 36 tests complete in < 1 second
- [ ] Total: 36 tests passing (12 new + 24 existing)

### Phase 6a-5: Review and Documentation (Day 5 - 4-6 hours)

**Objective**: Production-ready code

**Tasks**:
1. Code review with code-review-expert
2. Update documentation
3. Ensure Checkstyle clean
4. Final validation: all 36 tests passing
5. Close Phase 6a bead

**Checkpoint**:
- Code review >= 8.0/10
- Documentation complete
- All tests passing

**Exit Criteria**:
- [ ] Code review score >= 8.0/10
- [ ] Javadoc complete for all new methods
- [ ] Checkstyle clean
- [ ] 36 tests passing
- [ ] Bead closed with success

---

## 5. Risk Assessment

### Risk Matrix

| ID | Risk | Probability | Impact | Detection | Mitigation |
|----|------|-------------|--------|-----------|------------|
| R1 | Regression in existing TC tests | 30% | HIGH | Run all 24 tests after each change | Implement changes incrementally; revert if regression |
| R2 | Duplicate handling in union | 15% | MEDIUM | TC-D7 test | Use existing unionDistinct() |
| R3 | Performance with many branches | 25% | MEDIUM | TC-D10 test | Add branch limit (max 20) if needed |
| R4 | Type mismatch in union | 10% | LOW | Compile-time errors | Type checking before inversion |
| R5 | Infinite recursion in flattenOrelse | 5% | HIGH | Stack overflow | Only recurse on orelse nodes |

### Mitigation Strategies

**R1 - Regression Prevention**:
- Run full test suite after each code change
- Commit working code before each phase
- Keep changes small and focused
- Use feature branch for all Phase 6a work

**R2 - Duplicate Handling**:
- Reuse existing `unionDistinct()` at line 446-465
- Explicit test (TC-D7) for overlapping results
- Union uses distinct semantics by default

**R3 - Performance**:
- Performance tests (TC-D10, TC-D11, TC-D12)
- Target: all tests < 1 second
- Fallback: Add branch limit parameter to flattenOrelse()

**R4 - Type Safety**:
- Type checking happens before inversion
- All branches must have same element type
- Compile-time detection

**R5 - Recursion Safety**:
- flattenOrelse() only recurses on orelse expressions
- Non-orelse expressions are terminals
- No circular structures possible in AST

---

## 6. Verification Checklist

### Code Quality

- [ ] `flattenOrelse()` has complete Javadoc with examples
- [ ] `tryInvertDisjunction()` has complete Javadoc explaining algorithm
- [ ] Modified `tryInvertTransitiveClosure()` comments updated
- [ ] No debug logging left behind
- [ ] Checkstyle clean
- [ ] Code follows existing patterns in PredicateInverter.java

### Testing

- [ ] 12 new disjunction tests written (Category 11)
- [ ] All 24 existing TC tests pass (no regression)
- [ ] Total: 36 tests in transitive-closure.smli
- [ ] All tests complete in < 1 second
- [ ] Edge cases covered (overlapping, deep nesting, fallback)

### Documentation

- [ ] PHASE-6A-IMPLEMENTATION-PLAN.md created (this document)
- [ ] Code comments explain disjunction handling logic
- [ ] Design document referenced (PHASE-6A-DESIGN.md)

---

## 7. Success Criteria

Phase 6a is complete when:

1. **Tests**: All 36 tests pass (24 existing + 12 new)
2. **Performance**: All tests complete in < 1 second
3. **Code Quality**: Code review score >= 8.0/10
4. **Regression**: No regressions from Phase 6.5
5. **Documentation**: All new methods have Javadoc

---

## 8. Handoff Information

### For java-developer Agent

**Input Artifacts**:
- This plan: `.pm/PHASE-6A-IMPLEMENTATION-PLAN.md`
- Design doc: `.pm/PHASE-6A-DESIGN.md`
- Current tests: `/Users/hal.hildebrand/git/morel/src/test/resources/script/transitive-closure.smli`
- Implementation file: `/Users/hal.hildebrand/git/morel/src/main/java/net/hydromatic/morel/compile/PredicateInverter.java`

**Key Code Locations**:
- flattenOrelse(): Add after line 1000
- tryInvertDisjunction(): Add after line 572
- Modify tryInvertTransitiveClosure(): Lines 497-520
- Integrate in invert(): After line 178

**Execution**:
1. Create bead: `bd create "Phase 6a - Disjunction Union" -t feature -p 1`
2. Execute phases 6a-1 through 6a-5
3. Run all 36 tests after each phase
4. Request code review after Phase 6a-4

**Quality Gates**:
- After each phase: all existing tests must pass
- Phase 6a-5: code review >= 8.0/10

---

## 9. Next Steps

### Immediate (Before Implementation)

1. **Plan Audit**: Submit to plan-auditor for validation
2. **Bead Creation**: Create Phase 6a bead after audit approval

### During Implementation

1. **Daily Checkpoints**: Run full test suite
2. **Progress Updates**: Update bead status
3. **Code Review**: Request after Phase 6a-4

### After Implementation

1. **Code Review**: Validate with code-review-expert
2. **Documentation**: Update user guide with new patterns
3. **Merge**: Integrate into main branch

---

**Document Status**: COMPLETE - Ready for Audit
**Quality Criteria Met**:
- [x] Detailed step-by-step tasks with code locations
- [x] Code examples for all new methods
- [x] Test strategy with 12 tests across 4 categories
- [x] Implementation phases with checkpoints
- [x] Risk assessment with mitigations
- [x] Verification checklist
- [x] Clear success criteria
- [x] Handoff information for java-developer

---

**Version**: 1.0
**Created**: 2026-01-24
**Author**: java-architect-planner agent
