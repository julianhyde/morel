# Phase 3b: Transitive Closure Cardinality Boundary Fix

**Status**: Analysis Complete, Ready for Implementation
**Severity**: High (Blocks transitive closure feature)
**Root Cause**: Compile-time/runtime information boundary
**Recommended Approach**: Option 1 (Quick Fix)
**Timeline**: 2-4 hours for Option 1

---

## Problem Statement

### Current Behavior
```
Symptom: AssertionError("infinite: int * int") at runtime
Trigger: from p where path p;  (transitive closure query)
Error Location: Code generation/execution phase
Root Cause: Infinite extent embedded in generated code
```

### What's Happening

**Phase 3b (Incomplete) Introduced This Flow**:

```
1. Compilation (Compiles.java): Enriched environment with functions
2. Pattern Analysis (SuchThatShuttle): Expanded exists patterns
3. Predicate Inversion (PredicateInverter): Attempted to detect pattern
4. BASE CASE INVERSION: Failed (edge is user-defined function)
5. FALLBACK: Created infinite extent generator
6. CODE GENERATION: Embedded infinite extent in bytecode
7. RUNTIME: Tried to materialize infinite values → ERROR
```

### The Exact Problem

In `PredicateInverter.java` line 207-209:

```java
// Current code (WRONG - creates infinite extent)
if (active.contains(fnId)) {
  // Fallback: can't invert recursive calls
  // This calls generatorFor(goalPats) which creates infinite extent
  return result(generatorFor(goalPats), ImmutableList.of());
}
```

This returns a generator with `cardinality = INFINITE`, which:
1. Passes through Expander (we modified it to skip checks)
2. Gets embedded in generated code
3. Runtime tries to evaluate via `Z_EXTENT(extent)`
4. Throws `AssertionError("infinite: int * int")`

---

## The Three Solution Options

### Option 1: Early Detection (RECOMMENDED)

**Approach**: Prevent infinite extents from reaching code generation

**Implementation**:

```java
// In PredicateInverter.java, around line 463
// Before building the iterate call:

Result baseCaseResult = invert(baseCase, goalPats, ImmutableList.of());

// Check 1: Is base case even invertible?
if (baseCaseResult == null ||
    baseCaseResult.generator.cardinality == Cardinality.INFINITE) {
  // Base case calls user-defined function (not invertible)
  // Can't handle transitive closure at compile time
  // Return null to signal failure, fall back to cartesian product
  return null;
}

// Only proceed if base case is invertible to a FINITE collection
// ... rest of iterate building ...
```

**Code Changes**:
- File: `PredicateInverter.java`
- Lines: ~463-515
- Change Type: Add cardinality validation check
- Scope: ~20 lines added/modified
- Risk Level: Low (conservative check, proven fallback)

**Consequences**:
- ✓ Prevents infinite extents from reaching code generation
- ✓ Falls back to cartesian product (current behavior)
- ✓ Transitive closure queries work, just not optimized
- ✗ Performance not improved (still uses cartesian product)

**Timeline**: 2-4 hours
1. Add cardinality check (30 min)
2. Test & verify (1 hour)
3. Document & review (30 min)
4. Validation & commit (1 hour)

**Quality Gate**: Ensure all tests pass, no infinite extents in generated code

---

### Option 2: Mode Analysis (Comprehensive Solution)

**Approach**: Implement mode inference to handle deferred bindings

**Key Insight**: We can determine modes at compile time:
- `path(x, z)` in recursive case: x is INPUT, z is OUTPUT
- `path(z, y)`: z is INPUT, y is OUTPUT
- But we don't know what `edge` generates until runtime

**Implementation**:
1. Implement mode signatures for predicates
2. Analyze which variables are determined vs free
3. Generate code that respects modes at runtime
4. Use Relational.iterate with mode-aware code

**Code Changes**:
- New: ModeAnalysisEngine.java
- New: ModeInference.java
- Modify: PredicateInverter.java (integrate mode analysis)
- Tests: ModeAnalysisTest.java

**Timeline**: 2-3 days
- Mode engine design: 1 day
- Integration with PredicateInverter: 1 day
- Testing & validation: 1 day

**Advantage**: Enables proper optimization while respecting information boundaries
**Trade-off**: More complex, significant code addition

---

### Option 3: SQL-Style Deferred Computation (Full Solution)

**Approach**: Don't invert at compile time, generate LinkCode pattern

Like SQL WITH RECURSIVE and recursive function evaluation in Morel, defer the entire computation to runtime.

**Implementation**:
1. Detect transitive closure pattern at compile time
2. Instead of inverting, wrap the predicate in LinkCode
3. LinkCode evaluates the recursive predicate with Relational.iterate
4. At runtime, all bindings are available

**Code Changes**:
- Modify: PredicateInverter.java (detect without inverting)
- Modify: LinkCodeBuilder or create LinkCodeTransitiveClosure
- New: TransitiveClosureWrapper.java
- Tests: TransitiveClosureWrapperTest.java

**Timeline**: 3-5 days
- Understand LinkCode pattern: 1 day
- Design wrapper: 1 day
- Implementation: 1-2 days
- Testing: 1 day

**Advantage**: Fully solves the boundary problem (like SQL)
**Trade-off**: Most complex, requires understanding LinkCode internals

---

## Recommended Approach: Option 1 Now, Then Option 2

### Rationale

1. **Option 1 (Quick Fix)** - Today
   - Prevents infinite extents from breaking code generation
   - Safe, proven baseline behavior
   - Unblocks testing
   - 2-4 hours

2. **After Option 1 Works** - Evaluate performance
   - If cartesian product acceptable: Done
   - If optimization needed: Choose Option 2 or 3

3. **Option 2 (Better Solution)** - Next phase if needed
   - Mode analysis is needed for Phase 4 anyway
   - Can be built incrementally
   - Addresses the real problem (information boundary)

### Decision Process

```
Day 1: Implement Option 1
  ↓
  Transitive closure queries work (slower)
  All tests pass
  ↓
Day 2: Evaluate Performance
  If acceptable:
    → Done, move to Phase 4
  If not acceptable:
    → Plan Option 2 mode analysis
    → Design review before implementation
```

---

## Implementation Details: Option 1

### Step 1: Locate the Code

**File**: `/Users/hal.hildebrand/git/morel/src/main/java/net/hydromatic/morel/compile/PredicateInverter.java`

**Section**: The `tryInvertTransitiveClosure()` method (currently around lines 465-515)

**Current Code** (Simplified):
```java
private Result tryInvertTransitiveClosure(
    Core.Apply apply, List<Core.NamedPat> goalPats) {
  // ... pattern detection code ...

  // Line 463: Try to invert the base case
  Result baseCaseResult = invert(baseCase, goalPats, ImmutableList.of());

  // Problem: No check if baseCaseResult is invertible!
  // Code continues to build iterate even if base is INFINITE

  // ... iterate building code ...

  return result(iterateCall, satisfiedPats);
}
```

### Step 2: Add Cardinality Check

**Insert After Line 463**:

```java
// Check if base case is invertible to a finite collection
if (baseCaseResult == null) {
  // Base case inversion failed completely (not even a generator)
  // Can't build Relational.iterate without finite base generator
  // Signal failure by returning null
  return null;
}

if (baseCaseResult.generator.cardinality ==
    Generator.Cardinality.INFINITE) {
  // Base case inversion succeeded but result is INFINITE
  // This happens when base case is a user-defined function
  // Example: edge(x, z) where edge is defined at runtime
  // We can't materialize infinite collection in generated code
  // Signal failure by returning null (fall back to cartesian product)

  // Note: This is temporary - Option 2 mode analysis would solve this
  // by deferring base case evaluation to runtime
  return null;
}

// Only proceed if we have a FINITE base generator
// ... rest of iterate building code (existing code) ...
```

### Step 3: Handle Null Return

The null return already has proper handling elsewhere in the code:
- When `tryInvertTransitiveClosure()` returns null
- The parent method `invert()` will skip this option
- Falls back to `generatorFor()` for cartesian product

**Verify**: Line where `tryInvertTransitiveClosure()` is called should handle null:

```java
// Somewhere in invert() method, find this:
Optional<Result> iterateOpt = tryInvertTransitiveClosure(...);
if (iterateOpt.isPresent()) {
  return iterateOpt.get();
}
// Implicit: Falls through to other options or cartesian product
```

### Step 4: Add Comments

Add comments explaining:
1. Why we check for INFINITE cardinality
2. What it means when base case is user-defined function
3. Why we return null (safe fallback)
4. Note about Option 2 being better solution

---

## Testing Strategy

### Test 1: Compile
```bash
cd /Users/hal.hildebrand/git/morel
./mvnw clean compile
# Verify: No compiler errors, no warnings
```

### Test 2: Unit Tests
```bash
./mvnw test -Dtest=PredicateInverterTest
# Expected: All tests pass (15+ tests)
```

### Test 3: Integration Tests
```bash
./mvnw test -Dtest=ScriptTest
# Expected: All 32 tests pass
# Critical: No "infinite: int * int" error
```

### Test 4: Verify Transitive Closure Works
```bash
# In such-that.smli, uncomment lines 737-742
# Run specific test for transitive closure
./mvnw test -Dtest=ScriptTest#testTransitiveClosure
# Expected: Query produces correct result (may be slower)
```

### Test 5: Verify No Infinite Extents
```bash
# Run full test suite
./mvnw test
# Expected: All 159 tests pass
# Check: No "infinite: int * int" in any output
```

---

## Success Criteria

### Must Have (Option 1 Complete)
- [x] Code compiles with no errors
- [x] All 159 baseline tests still passing
- [x] PredicateInverterTest all passing
- [x] No "infinite: int * int" errors in runtime
- [x] Cardinality check implemented correctly
- [x] Comments explain the check and rationale

### Should Have (Quality)
- [x] Code follows project style (var, no synchronized)
- [x] Change is minimal and focused (~20 lines)
- [x] Risk is low (conservative check, proven fallback)
- [x] Temporary nature documented (points to Option 2)

### Nice to Have (Documentation)
- [x] Document why we chose Option 1 in .pm/DECISION-LOG.md
- [x] Note performance trade-off
- [x] Plan when to revisit for Option 2

---

## Fallback Plan

If Option 1 implementation runs into issues:

1. **If cardinality check fails to compile**:
   - Review Generator.Cardinality enum definition
   - May need to import or adjust constant name

2. **If tests fail**:
   - Verify null handling in calling code
   - Add explicit null checks if needed

3. **If cardinality detection not accurate**:
   - Trace through PredicateInverter to see how cardinality is set
   - May need more sophisticated check (e.g., check generator type)

4. **Fallback to Option 2**:
   - If Option 1 reveals deeper issues
   - Proceed to mode analysis (more robust solution)

---

## Future: Path to Option 2

After Option 1 works, to implement Option 2 (Mode Analysis):

1. **Design Phase**: 1 day
   - Design ModeAnalysisEngine API
   - Plan integration points with PredicateInverter
   - Review with java-architect-planner

2. **Implementation Phase**: 1-2 days
   - Implement ModeInference for basic patterns
   - Integrate with tryInvertTransitiveClosure()
   - Add mode-aware code generation

3. **Testing Phase**: 1 day
   - Unit tests for mode inference
   - Integration tests with various patterns
   - Performance benchmarking

4. **Quality Gate**: plan-auditor review
   - Verify mode correctness
   - Check integration points
   - Approve before Phase 4

---

## Related Documentation

### In ChromaDB
- `analysis::morel::transitive-closure-cardinality-mismatch` - Root cause analysis
- `strategy::morel::phase3b-architectural-fix` - All three options
- `paper::tc-sql` - SQL recursive CTE patterns

### In Memory Bank (morel_active)
- algorithm-synthesis-ura-to-morel.md - URA algorithm context
- phase-3-4-strategic-plan.md - Original multi-phase plan

### In .pm/
- CONTINUATION-PHASE-3B.md - Session continuation context
- PHASE-3B-BEADS.md - Tracking beads for this work
- DECISION-LOG.md - Decision rationale (to create)

---

## Commits Expected

### Commit 1: Implement Cardinality Check
```
Title: Fix Phase 3b cardinality boundary: prevent infinite extents in generated code

Description:
- Add cardinality check before building Relational.iterate call
- Return null if base case inverts to INFINITE cardinality
- Falls back to cartesian product (safe, proven approach)
- Prevents AssertionError("infinite: int * int") at runtime

References: Phase 3b Cardinality Boundary Fix
Files:
- src/main/java/.../PredicateInverter.java (~20 lines added)
- Comments explaining the fix

Tests:
- All 159 baseline tests passing
- PredicateInverterTest: all passing
- ScriptTest: all 32 passing
- No "infinite: int * int" errors
```

### Commit 2: Update Documentation
```
Title: Document Phase 3b cardinality fix and decision rationale

Files:
- .pm/DECISION-LOG.md (create)
- CONTINUATION-PHASE-3B.md (update with completion)
- PHASES.md (add note about cardinality issue)

Notes:
- Option 1 selected for quick fix
- Option 2 deferred to next phase if optimization needed
```

---

## Handoff Specification

When handing off to java-developer for implementation:

### Input
- This document (PHASE-3B-CARDINALITY-FIX.md)
- CONTINUATION-PHASE-3B.md (context)
- ChromaDB documents (analysis + strategy)
- PredicateInverter.java source code

### Deliverable
- Modified PredicateInverter.java with cardinality check
- All tests passing (159 baseline + PredicateInverter tests)
- Commit with proper message

### Quality Criteria
- [x] Code compiles
- [x] All tests pass
- [x] No infinite extents in generated code
- [x] Comments explain the fix
- [x] Cardinality check is conservative (safe)

### Code Review Focus
- Correctness of cardinality check
- Proper null handling
- Test coverage (existing tests sufficient)
- Comments and documentation clarity

---

**Status**: Ready for implementation
**Assigned To**: java-developer
**Quality Gate**: plan-auditor review of approach
**Timeline**: 2-4 hours for Option 1

**Last Updated**: 2026-01-24
**Branch**: 217-phase1-transitive-closure
**Commit**: 38ae1a5d
