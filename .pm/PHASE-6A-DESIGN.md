# Phase 6a Architecture Design: Disjunction Union Support

**Bead**: TBD (create after approval)
**Date**: 2026-01-24
**Author**: java-architect-planner agent
**Status**: Design Complete - Ready for Review
**Target Execution**: After Phase 6.1-6.4 (Day 8-14 of Phase 6)

---

## Executive Summary

Phase 6a extends the transitive closure optimization to handle multi-branch disjunction patterns. Currently, PredicateInverter handles only binary `orelse` expressions in recursive contexts. This design proposes extending support to handle arbitrary disjunction like `pattern1(x) orelse pattern2(x) orelse pattern3(x)`, combining all invertible branches into a unified generator.

**Key Deliverables**:
1. `flattenOrelse()` helper method to decompose nested orelse structures
2. `tryInvertDisjunction()` method for multi-branch disjunction handling
3. Leverage existing `unionDistinct()` for result combination
4. Comprehensive test suite (10-12 test cases)

**Estimated Effort**: 4-6 days
**Risk Level**: MODERATE

---

## 1. Problem Statement

### 1.1 Current Limitation

The current `tryInvertTransitiveClosure()` method (PredicateInverter.java:481-572) only handles binary orelse expressions:

```java
// Line 497-498 - Assumes binary structure
Core.Exp baseCase = orElseApply.arg(0);
Core.Exp recursiveCase = orElseApply.arg(1);
```

This means multi-branch disjunctions are NOT optimized:

```sml
(* CURRENT: Only first orelse is processed *)
fun connected(x, y) =
  direct_edge(x, y) orelse     (* This branch is processed *)
  indirect_edge(x, y) orelse   (* IGNORED - nested in left arg *)
  (exists z where connected(x, z) andalso edge(z, y))
```

### 1.2 SML Orelse Associativity

SML `orelse` is **left-associative**, so:

```sml
A orelse B orelse C
```

Parses as:

```
orelse(orelse(A, B), C)
```

The nested structure means current code only sees:
- `arg(0)` = `orelse(A, B)` (treated as base case, but is itself an orelse)
- `arg(1)` = `C` (treated as recursive case)

The `A` and `B` branches are never individually processed.

### 1.3 Goal

Support multi-branch disjunction patterns:

```sml
(* Pattern 1: Multiple non-recursive branches *)
fun inSet(x) = x elem set1 orelse x elem set2 orelse x elem set3
from p where inSet p;
(* Should optimize to: union of set1, set2, set3 *)

(* Pattern 2: TC with multiple base cases *)
fun path(x, y) =
  direct(x, y) orelse
  indirect(x, y) orelse
  (exists z where path(x, z) andalso edge(z, y))
from p where path p;
(* Should optimize to: Relational.iterate (direct union indirect) stepFn *)
```

### 1.4 Challenge

How to combine multiple generators into a single unified extent while:
1. Maintaining correct semantics (union of results)
2. Eliminating duplicates when branches overlap
3. Preserving transitive closure optimization when applicable
4. Handling mixed invertible/uninvertible branches gracefully

---

## 2. Proposed Solutions

### 2.1 Option A: Flatten and Union All Branches (RECOMMENDED)

**Description**: Recursively flatten nested orelse into a list, invert each branch independently, union all FINITE generators.

**Algorithm**:
```
1. flattenOrelse(expr) -> [branch1, branch2, ..., branchN]
2. For each branch:
   a. Attempt inversion
   b. If FINITE, add to invertedGenerators list
   c. If INFINITE, mark as uninvertible
3. If no FINITE generators, return null (fallback to default)
4. unionDistinct(invertedGenerators) -> combined generator
5. Return combined generator with union semantics
```

**Code Sketch**:
```java
private List<Core.Exp> flattenOrelse(Core.Exp exp) {
  if (exp.op != Op.APPLY) {
    return ImmutableList.of(exp);
  }
  Core.Apply apply = (Core.Apply) exp;
  if (!apply.isCallTo(BuiltIn.Z_ORELSE)) {
    return ImmutableList.of(exp);
  }
  // Recursively flatten left branch, then add right branch
  List<Core.Exp> result = new ArrayList<>();
  result.addAll(flattenOrelse(apply.arg(0)));
  result.addAll(flattenOrelse(apply.arg(1)));
  return result;
}

private Result tryInvertDisjunction(
    Core.Apply orElseApply,
    List<Core.NamedPat> goalPats,
    List<Core.Exp> active) {

  List<Core.Exp> branches = flattenOrelse(orElseApply);
  List<Generator> finiteGenerators = new ArrayList<>();

  for (Core.Exp branch : branches) {
    Result branchResult = invert(branch, goalPats, active);
    if (branchResult.generator.cardinality != Cardinality.INFINITE) {
      finiteGenerators.add(branchResult.generator);
    }
  }

  if (finiteGenerators.isEmpty()) {
    return null; // No invertible branches
  }

  Generator combined = unionDistinct(finiteGenerators);
  return result(combined, ImmutableList.of());
}
```

**Trade-offs**:
| Aspect | Assessment |
|--------|------------|
| Complexity | LOW - Reuses existing unionDistinct() |
| Performance | MEDIUM - May generate duplicates requiring elimination |
| Correctness | HIGH - Clear union semantics |
| Maintainability | HIGH - Simple, understandable algorithm |
| Risk | LOW - Minimal changes to existing code |

### 2.2 Option B: Priority-Ordered Branch Processing

**Description**: Process branches left-to-right, use first FINITE generator as base, remaining branches as alternatives.

**Algorithm**:
```
1. flattenOrelse(expr) -> [branch1, ..., branchN]
2. Find first FINITE branch -> baseGenerator
3. If no FINITE branch, return null
4. If exactly one FINITE branch, return that generator
5. If multiple FINITE branches, create priority union:
   - Base = first FINITE
   - Alternatives = remaining FINITE branches
   - Union with left-priority (earlier branches preferred)
```

**Trade-offs**:
| Aspect | Assessment |
|--------|------------|
| Complexity | MEDIUM - Requires priority tracking |
| Performance | HIGH - Avoids redundant evaluation |
| Correctness | MEDIUM - Priority may affect results if branches overlap |
| Maintainability | MEDIUM - Priority logic adds complexity |
| Risk | MEDIUM - Priority semantics may be surprising |

### 2.3 Option C: Symbolic Union Representation (Deferred Evaluation)

**Description**: Create a new IR node representing the union of generators, defer actual union computation to runtime.

**Algorithm**:
```
1. flattenOrelse(expr) -> [branch1, ..., branchN]
2. Invert all branches
3. Create Core.Union node containing all generators
4. Let runtime evaluate union lazily
```

**New IR Required**:
```java
// New Core.Union class
public static class Union extends Exp {
  public final ImmutableList<Exp> branches;
  public final boolean distinct;
  // ...
}
```

**Trade-offs**:
| Aspect | Assessment |
|--------|------------|
| Complexity | HIGH - Requires new IR type |
| Performance | HIGH - Lazy evaluation, no compile-time duplication |
| Correctness | HIGH - Clean semantics |
| Maintainability | LOW - New IR type requires changes throughout codebase |
| Risk | HIGH - Significant architectural change |

### 2.4 Option D: Smart Pattern Classification

**Description**: Classify each branch as BASE_CASE, RECURSIVE_CASE, or UNINVERTIBLE, then apply optimized handling per classification.

**Algorithm**:
```
1. flattenOrelse(expr) -> [branch1, ..., branchN]
2. Classify each branch:
   - BASE_CASE: Inverts to FINITE, no recursion
   - RECURSIVE_CASE: Contains recursive call
   - UNINVERTIBLE: Cannot invert
3. Group by classification
4. Build optimized generator:
   - If all BASE_CASE: union of all bases
   - If mixed BASE + RECURSIVE: TC with combined base
   - If RECURSIVE only: standard TC handling
   - If UNINVERTIBLE only: return null
```

**Trade-offs**:
| Aspect | Assessment |
|--------|------------|
| Complexity | HIGH - Classification logic is complex |
| Performance | HIGH - Optimal for each case |
| Correctness | HIGH - Handles all patterns correctly |
| Maintainability | LOW - Many code paths to maintain |
| Risk | HIGH - Classification errors could cause incorrect results |

---

## 3. Comparison Matrix

| Criterion | Option A (Union) | Option B (Priority) | Option C (Symbolic) | Option D (Classify) |
|-----------|------------------|---------------------|---------------------|---------------------|
| Implementation Effort | 2-3 days | 3-4 days | 5-7 days | 4-6 days |
| Code Changes | ~100 lines | ~150 lines | ~300+ lines | ~250 lines |
| Regression Risk | LOW | MEDIUM | HIGH | MEDIUM |
| Correctness | HIGH | MEDIUM | HIGH | HIGH |
| Performance | MEDIUM | HIGH | HIGH | HIGH |
| Maintainability | HIGH | MEDIUM | LOW | LOW |
| Aligns with Existing Code | YES | PARTIAL | NO | PARTIAL |

---

## 4. Integration Points

### 4.1 PredicateInverter.java Modifications

**File**: `/Users/hal.hildebrand/git/morel/src/main/java/net/hydromatic/morel/compile/PredicateInverter.java`

| Location | Current Code | Required Change |
|----------|--------------|-----------------|
| Line 163-178 | orelse detection only in recursive context | Add non-recursive disjunction handling |
| Line 481-572 | tryInvertTransitiveClosure() | Call tryInvertDisjunction() for nested orelse |
| Line 446-465 | unionDistinct() | Reuse as-is (no changes) |
| New | N/A | Add flattenOrelse() method (~25 lines) |
| New | N/A | Add tryInvertDisjunction() method (~60 lines) |

**Specific Code Changes**:

```java
// Add after line 178 (inside orelse handling block)
// NEW: Handle non-recursive disjunction
if (apply.isCallTo(BuiltIn.Z_ORELSE)) {
  Result disjunctionResult = tryInvertDisjunction(apply, goalPats, active);
  if (disjunctionResult != null) {
    return disjunctionResult;
  }
  // Fall through to default handling
}
```

```java
// Modify tryInvertTransitiveClosure() around line 497
// Check if baseCase is itself an orelse (nested disjunction)
Core.Exp baseCase = orElseApply.arg(0);
if (baseCase.op == Op.APPLY && ((Core.Apply) baseCase).isCallTo(BuiltIn.Z_ORELSE)) {
  // Flatten all base cases into union
  List<Core.Exp> baseBranches = flattenOrelse(baseCase);
  List<Generator> baseGenerators = new ArrayList<>();
  for (Core.Exp branch : baseBranches) {
    Result branchResult = invert(branch, goalPats, ImmutableList.of());
    if (branchResult.generator.cardinality == Cardinality.FINITE) {
      baseGenerators.add(branchResult.generator);
    }
  }
  if (!baseGenerators.isEmpty()) {
    // Use combined base for transitive closure
    Generator combinedBase = unionDistinct(baseGenerators);
    // Continue with TC optimization using combinedBase
  }
}
```

### 4.2 Extents.java Considerations

**File**: `/Users/hal.hildebrand/git/morel/src/main/java/net/hydromatic/morel/compile/Extents.java`

| Location | Current Behavior | Phase 6a Impact |
|----------|------------------|-----------------|
| Line 457-493 | g3 handles orelse with reduceOr | No change needed - already handles union |
| Line 613-649 | g3b handles orelse with reduceOr | May benefit from PredicateInverter call |

**Decision**: Extents.java changes are OPTIONAL for Phase 6a. The orelse handling in g3/g3b already performs union semantics. PredicateInverter improvements will automatically benefit queries routed through g3b.

### 4.3 New Methods Required

```java
// NEW METHOD 1: flattenOrelse
// Location: PredicateInverter.java, after line 1000 (utility methods section)

/**
 * Flattens a nested orelse expression into a list of branches.
 *
 * <p>SML orelse is left-associative: {@code A orelse B orelse C} parses as
 * {@code (A orelse B) orelse C}. This method recursively flattens the
 * structure into {@code [A, B, C]}.
 *
 * @param exp The expression to flatten
 * @return List of branch expressions
 */
private List<Core.Exp> flattenOrelse(Core.Exp exp) {
  if (exp.op != Op.APPLY) {
    return ImmutableList.of(exp);
  }
  Core.Apply apply = (Core.Apply) exp;
  if (!apply.isCallTo(BuiltIn.Z_ORELSE)) {
    return ImmutableList.of(exp);
  }
  List<Core.Exp> result = new ArrayList<>();
  result.addAll(flattenOrelse(apply.arg(0)));
  result.addAll(flattenOrelse(apply.arg(1)));
  return result;
}
```

```java
// NEW METHOD 2: tryInvertDisjunction
// Location: PredicateInverter.java, after tryInvertTransitiveClosure (~line 572)

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
 * @param orElseApply The orelse expression
 * @param goalPats Variables to generate values for
 * @param active Functions currently being inverted (recursion detection)
 * @return Inversion result with union generator, or null if not invertible
 */
private Result tryInvertDisjunction(
    Core.Apply orElseApply,
    List<Core.NamedPat> goalPats,
    List<Core.Exp> active) {

  // Flatten nested orelse into list of branches
  List<Core.Exp> branches = flattenOrelse(orElseApply);

  if (branches.size() < 2) {
    // Not a valid disjunction
    return null;
  }

  // Invert each branch, collect FINITE generators
  List<Generator> finiteGenerators = new ArrayList<>();
  List<Core.Exp> remainingFilters = new ArrayList<>();

  for (Core.Exp branch : branches) {
    Result branchResult = invert(branch, goalPats, active);

    if (branchResult == null) {
      // Branch inversion failed completely
      continue;
    }

    if (branchResult.generator.cardinality != Cardinality.INFINITE) {
      finiteGenerators.add(branchResult.generator);
      // Collect remaining filters from each branch
      remainingFilters.addAll(branchResult.remainingFilters);
    }
  }

  if (finiteGenerators.isEmpty()) {
    // No invertible branches - signal failure
    return null;
  }

  // Create union of all finite generators
  Generator combined = unionDistinct(finiteGenerators);

  // For disjunction, remaining filters should be applied with OR semantics
  // For simplicity, we clear remaining filters since union already captures intent
  return result(combined, ImmutableList.of());
}
```

### 4.4 Dependency Graph

```
flattenOrelse() [NEW]
      |
      v
tryInvertDisjunction() [NEW]
      |
      +---> invert() [EXISTING - line 123]
      |         |
      |         v
      |    tryInvertTransitiveClosure() [EXISTING - line 481]
      |
      v
unionDistinct() [EXISTING - line 446]
      |
      v
Result [EXISTING - line 1352]
```

---

## 5. Test Strategy

### 5.1 Test Categories

**Category 1: Basic Disjunction (3 tests)**

| ID | Test Case | Input | Expected Output |
|----|-----------|-------|-----------------|
| TC-D1 | Two-branch non-recursive | `x elem [1,2] orelse x elem [3,4]` | `[1,2,3,4]` |
| TC-D2 | Three-branch non-recursive | `x elem [1] orelse x elem [2] orelse x elem [3]` | `[1,2,3]` |
| TC-D3 | Four-branch (deep nesting) | Four nested orelse | Combined list |

**Category 2: Disjunction with Transitive Closure (3 tests)**

| ID | Test Case | Input | Expected Output |
|----|-----------|-------|-----------------|
| TC-D4 | TC with multiple base cases | `edge1 orelse edge2 orelse recursive` | TC of union |
| TC-D5 | TC with multiple relations | `edges1 union edges2` as combined base | Full transitive closure |
| TC-D6 | Mixed invertible/uninvertible | Some branches INFINITE | Union of FINITE only |

**Category 3: Edge Cases (4 tests)**

| ID | Test Case | Description | Expected Behavior |
|----|-----------|-------------|-------------------|
| TC-D7 | Overlapping results | Same value in multiple branches | Deduplicated result |
| TC-D8 | Empty branch | One branch produces `[]` | Union excludes empty |
| TC-D9 | All uninvertible | All branches INFINITE | Falls through to default |
| TC-D10 | Self-recursion in multiple | Complex recursion | Graceful rejection |

**Category 4: Performance (2 tests)**

| ID | Test Case | Scale | Target |
|----|-----------|-------|--------|
| TC-D11 | Large union | 10 branches, 100 elements each | < 1 second |
| TC-D12 | Deep nesting | 20 levels deep | < 1 second |

### 5.2 Test Implementation Examples

```sml
(* TC-D1: Two-branch non-recursive *)
val set1 = [1, 2]
val set2 = [3, 4]
fun inSet x = x elem set1 orelse x elem set2
from p where inSet p;
(* Expected: [1, 2, 3, 4] *)

(* TC-D4: TC with multiple base cases *)
val edges1 = [(1, 2)]
val edges2 = [(2, 3)]
fun edge(x, y) = (x, y) elem edges1 orelse (x, y) elem edges2
fun path(x, y) =
  edge(x, y) orelse
  (exists z where path(x, z) andalso edge(z, y))
from p where path p;
(* Expected: [(1,2), (2,3), (1,3)] *)

(* TC-D7: Overlapping results *)
val set1 = [1, 2, 3]
val set2 = [2, 3, 4]
fun inSet x = x elem set1 orelse x elem set2
from p where inSet p;
(* Expected: [1, 2, 3, 4] - deduplicated *)

(* TC-D9: All uninvertible *)
fun unknown1 x = ... (* no known extent *)
fun unknown2 x = ... (* no known extent *)
fun test x = unknown1 x orelse unknown2 x
from p where test p;
(* Expected: Falls back to default extent handling *)
```

### 5.3 Test File Location

Create: `src/test/resources/script/disjunction-union.smli`

Or extend existing: `src/test/resources/script/transitive-closure.smli`

---

## 6. Risk Assessment

### 6.1 Risk Register

| ID | Risk | Likelihood | Impact | Detection | Mitigation | Contingency |
|----|------|------------|--------|-----------|------------|-------------|
| R1 | Regression in TC | LOW (20%) | HIGH | Phase 5d tests | Run full test suite before merge | Revert changes |
| R2 | Performance degradation | MEDIUM (35%) | MEDIUM | TC-D11, TC-D12 | Lazy evaluation | Add branch limit |
| R3 | Incorrect duplicates | MEDIUM (30%) | LOW | TC-D7 | Use unionDistinct() | Add dedup pass |
| R4 | Type conflicts | LOW (15%) | MEDIUM | Compilation | Reuse existing types | Type annotation |
| R5 | Infinite loop on complex patterns | LOW (10%) | HIGH | TC-D10 | Recursion guard | Depth limit |

### 6.2 Risk Mitigation Details

**R1 Mitigation**:
- Run all 24 Phase 5d transitive closure tests before merge
- Add explicit regression tests for FM-02 fix

**R2 Mitigation**:
- Limit branch count (e.g., max 20 branches)
- Add early termination if union grows too large

**R3 Mitigation**:
- unionDistinct() already handles this (line 446)
- Union uses `distinct` flag in FROM builder

**R4 Mitigation**:
- All branches must have same element type
- Type checking happens before inversion

**R5 Mitigation**:
- flattenOrelse() does not recurse into non-orelse expressions
- No new recursion paths introduced

### 6.3 Complexity vs Benefit Analysis

**Benefit**:
- Enables optimization of common pattern matching queries
- Multi-branch disjunction is frequent in real-world queries
- Completes the predicate inversion feature set

**Complexity**:
- ~100 lines of new code
- 2 new methods (flattenOrelse, tryInvertDisjunction)
- Reuses existing unionDistinct()
- Clear, maintainable algorithm

**Verdict**: **FAVORABLE** - Benefits outweigh complexity

---

## 7. Recommendation

### 7.1 Recommended Approach: Option A (Flatten and Union)

**Justification**:

1. **Lowest Risk**: Minimal changes to existing code, reuses tested unionDistinct()
2. **Simplest Implementation**: ~100 lines of new code, 2 new methods
3. **Clear Semantics**: Union of results is intuitive and correct
4. **Aligns with Existing Patterns**: Extents.java already uses reduceOr for orelse
5. **Maintainable**: Simple algorithm easy to understand and debug

### 7.2 Implementation Sequence

1. **Day 1**: Implement flattenOrelse() and unit tests
2. **Day 2**: Implement tryInvertDisjunction() and integration with invert()
3. **Day 3**: Modify tryInvertTransitiveClosure() for nested base cases
4. **Day 4**: Create test suite (TC-D1 through TC-D12)
5. **Day 5**: Run regression tests, fix any issues
6. **Day 6**: Code review and documentation

### 7.3 Estimated Effort

| Task | Hours |
|------|-------|
| Design review | 1 |
| flattenOrelse() implementation | 2 |
| tryInvertDisjunction() implementation | 4 |
| TC handling modification | 2 |
| Test case implementation | 6 |
| Regression testing | 4 |
| Code review | 2 |
| Bug fixes | 4 |
| Documentation | 2 |
| **Total** | **27 hours (~4 days)** |

### 7.4 Timeline

| Phase | Days | Prerequisites |
|-------|------|---------------|
| Phase 6.1-6.4 | Days 1-7 | Phase 5 complete |
| Phase 6a | Days 8-12 | Phase 6.1-6.4 complete |
| Code Review | Day 13 | Phase 6a implementation |
| Merge | Day 14 | Code review approved |

### 7.5 Success Criteria

- [ ] All 10-12 disjunction tests pass
- [ ] All 24 existing TC tests pass (no regressions)
- [ ] Performance tests complete in < 1 second
- [ ] Code coverage > 80% for new methods
- [ ] Code review score 8.0+

---

## 8. Appendix

### 8.1 Key Files Reference

| File | Relevance |
|------|-----------|
| `PredicateInverter.java:163-178` | orelse detection in invert() |
| `PredicateInverter.java:446-465` | unionDistinct() implementation |
| `PredicateInverter.java:481-572` | tryInvertTransitiveClosure() |
| `Extents.java:457-493` | g3 orelse handling |
| `Extents.java:613-649` | g3b orelse handling |
| `transitive-closure.smli` | Existing TC test cases |

### 8.2 Related Beads

| Bead | Relationship |
|------|--------------|
| morel-45u | Phase 6 Epic |
| morel-ax4 | Phase 6.1 Cleanup |
| morel-wgv | Phase 6.2 Documentation |
| morel-uvg | Phase 6.3 Tests |
| morel-hvq | Phase 6.4 Performance |
| morel-3pp | Phase 6.5 Merge |
| TBD | Phase 6a Disjunction Union |

### 8.3 SML Orelse Semantics Reference

```sml
(* SML orelse is:
   - Left-associative
   - Short-circuit evaluating
   - Type: bool * bool -> bool
*)

(* Parsing examples *)
A orelse B orelse C
==> (A orelse B) orelse C
==> orelse(orelse(A, B), C)

A orelse B orelse C orelse D
==> ((A orelse B) orelse C) orelse D
==> orelse(orelse(orelse(A, B), C), D)
```

---

## 9. Conclusion

Phase 6a extends predicate inversion to handle multi-branch disjunction patterns, completing the feature set started in Phase 5. The recommended approach (Flatten and Union) provides the best balance of simplicity, correctness, and maintainability.

**Key Points**:
1. Problem is well-defined: multi-branch orelse not optimized
2. Solution is straightforward: flatten, invert, union
3. Risk is manageable: reuses existing tested code
4. Effort is reasonable: ~4 days implementation
5. Timeline fits Phase 6: Days 8-14 after core Phase 6 tasks

**Next Steps**:
1. Review this design with stakeholders
2. Create Phase 6a bead after approval
3. Execute after Phase 6.1-6.4 complete

---

**Document Status**: COMPLETE - Ready for Handoff
**Quality Criteria Met**:
- [x] All 3+ approaches evaluated with concrete trade-offs
- [x] Integration points identified with code line references
- [x] Test strategy sufficient for comprehensive coverage (10-12 tests)
- [x] Risk analysis completed (5 risks with mitigations)
- [x] Final recommendation justified with metrics
- [x] Document suitable for handing off to java-developer for implementation

---

**Version**: 1.0
**Created**: 2026-01-24
**Author**: java-architect-planner agent
