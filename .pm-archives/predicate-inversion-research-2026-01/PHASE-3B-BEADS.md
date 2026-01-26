# Phase 3b-Final: Cardinality Boundary Fix - Bead Specifications

**Created**: 2026-01-24
**Status**: Ready to create via `bd create`
**Total Beads**: 4 (plus 1 epic bead)
**Timeline**: 2-4 hours total
**Complexity**: Low-Medium

---

## Epic Bead: Phase 3b-Final Cardinality Fix

```yaml
Title: "Fix Phase 3b cardinality boundary: prevent infinite extents in generated code"
Type: epic
Priority: 1
Complexity: low
Estimated Time: 2-4 hours
Description: |
  Discover root cause of "infinite: int * int" runtime error in Phase 3b.

  Problem: Attempted Relational.iterate integration created infinite extents in generated code.
  Root Cause: Compile-time/runtime information boundary in recursive predicate inversion.
  Solution: Option 1 (Quick Fix) - Add cardinality check before building Relational.iterate call.

  This epic contains 4 subtasks:
  1. Verify Problem: Confirm "infinite: int * int" error and root cause
  2. Implement Fix: Add cardinality check to PredicateInverter.java
  3. Test & Validate: Ensure all tests pass, no infinite extents
  4. Document Decision: Record rationale and future optimization path

  Success: All tests pass, transitive closure queries work (even if slower via cartesian product).
  Quality Gate: Code review approval of change.

Dependencies: "Phase 3a-Complete" (or direct to epic if no bead exists)
Related: "Issue #217" (Predicate Inversion)
Context: .pm/PHASE-3B-CARDINALITY-FIX.md
```

---

## Task Bead 1: Verify Problem

```yaml
Title: "Phase 3b-Verify: Confirm 'infinite: int * int' error and stack trace"
Type: task
Priority: 1
Complexity: low
Estimated Time: 30 minutes
Dependencies:
  - Blocks: "Phase 3b-Implement"
Description: |
  Verify that the transitive closure test fails with "infinite: int * int" error.

  Acceptance Criteria:
  1. Run transitive closure test (such-that.smli:737-742)
  2. Confirm error: AssertionError("infinite: int * int")
  3. Document exact stack trace
  4. Verify error occurs during code execution (not compilation)
  5. Verify baseline tests still pass (159/159)

  Steps:
  1. cd /Users/hal.hildebrand/git/morel
  2. Run: ./mvnw test (confirm 159 baseline tests pass)
  3. Look for test case with transitive closure
  4. Identify error location in stack trace
  5. Document findings in task comment

  Output:
  - Stack trace document
  - Confirmation that issue is at code execution, not compilation
  - Location in code where infinite extent is created

  Reference: PHASE-3B-CARDINALITY-FIX.md "Problem Statement" section

Epic: "Phase 3b-Final Cardinality Fix"
Context: .pm/PHASE-3B-CARDINALITY-FIX.md (lines: Problem Statement)
```

---

## Task Bead 2: Implement Fix

```yaml
Title: "Phase 3b-Implement: Add cardinality check to PredicateInverter.java"
Type: task
Priority: 1
Complexity: low
Estimated Time: 2-3 hours
Dependencies:
  - Requires: "Phase 3b-Verify" (needs problem confirmation)
  - Blocks: "Phase 3b-Test"
Description: |
  Implement the cardinality check in PredicateInverter.java to prevent infinite extents
  from reaching code generation.

  Acceptance Criteria:
  1. Locate tryInvertTransitiveClosure() method (lines ~465-515)
  2. Add cardinality check after line 463
  3. Return null if baseCaseResult is null or INFINITE cardinality
  4. Add comments explaining the check
  5. Code compiles with no errors
  6. Style follows project conventions (var, no synchronized)

  Implementation Details:

  Location: PredicateInverter.java, around line 463

  Insert after "Result baseCaseResult = invert(...)":

  ```java
  // Check if base case is invertible to a finite collection
  if (baseCaseResult == null) {
    // Base case inversion failed completely
    return null;
  }

  if (baseCaseResult.generator.cardinality ==
      Generator.Cardinality.INFINITE) {
    // Base case is user-defined function (not invertible to finite collection)
    // Can't materialize infinite collection in generated code
    // Return null to signal failure (fall back to cartesian product)

    // Note: This is temporary - Option 2 mode analysis would solve this
    // by deferring base case evaluation to runtime
    return null;
  }

  // Only proceed if we have a FINITE base generator
  // ... rest of iterate building code ...
  ```

  Files Modified:
  - src/main/java/net/hydromatic/morel/compile/PredicateInverter.java

  Lines Changed:
  - ~20 lines added/modified around line 463

  Commits:
  - 1 commit: "Fix Phase 3b cardinality boundary: add check before building iterate"

  Reference: PHASE-3B-CARDINALITY-FIX.md "Implementation Details" section

Epic: "Phase 3b-Final Cardinality Fix"
Context: .pm/PHASE-3B-CARDINALITY-FIX.md (lines: Implementation Details)
Related: PredicateInverter.java
```

---

## Task Bead 3: Test & Validate

```yaml
Title: "Phase 3b-Test: Verify all tests pass and no infinite extents in code"
Type: task
Priority: 1
Complexity: low
Estimated Time: 1 hour
Dependencies:
  - Requires: "Phase 3b-Implement"
  - Blocks: "Phase 3b-Document"
Description: |
  Run comprehensive tests to verify the cardinality fix works correctly.

  Acceptance Criteria:
  1. Code compiles with no errors: ./mvnw clean compile
  2. All 159 baseline tests pass: ./mvnw test
  3. PredicateInverterTest all pass (15+ tests)
  4. ScriptTest all pass (32 tests)
  5. No "infinite: int * int" errors in any test output
  6. Transitive closure query executes (even if slower)
  7. No compiler warnings

  Test Steps:

  Step 1: Compilation
  ```bash
  cd /Users/hal.hildebrand/git/morel
  ./mvnw clean compile
  # Expected: BUILD SUCCESS, no warnings
  ```

  Step 2: Full Test Suite
  ```bash
  ./mvnw test
  # Expected: 159 tests pass
  # Critical: No "infinite: int * int" in output
  ```

  Step 3: Verify Specific Tests
  ```bash
  ./mvnw test -Dtest=PredicateInverterTest
  # Expected: All tests pass

  ./mvnw test -Dtest=ScriptTest
  # Expected: All 32 tests pass
  ```

  Step 4: Verify No Regressions
  ```bash
  ./mvnw test -Dtest=AlgebraTest
  ./mvnw test -Dtest=MainTest
  # Expected: All pass, no new failures
  ```

  Output:
  - Test results summary
  - Confirmation of no infinite extent errors
  - Performance baseline for transitive closure (for future comparison)

  Quality Checks:
  - All 159 baseline tests pass
  - No regressions in any test suite
  - No compiler warnings
  - Code executes without infinite extent errors

  Reference: PHASE-3B-CARDINALITY-FIX.md "Testing Strategy" section

Epic: "Phase 3b-Final Cardinality Fix"
Context: .pm/PHASE-3B-CARDINALITY-FIX.md (lines: Testing Strategy)
```

---

## Task Bead 4: Document Decision

```yaml
Title: "Phase 3b-Document: Record decision rationale and future optimization path"
Type: task
Priority: 2
Complexity: low
Estimated Time: 30 minutes
Dependencies:
  - Requires: "Phase 3b-Test"
  - After: (Final task)
Description: |
  Create decision log documenting why Option 1 was chosen and when to revisit
  for Option 2 optimization.

  Acceptance Criteria:
  1. Create .pm/DECISION-LOG.md
  2. Document the three options (1, 2, 3)
  3. Explain why Option 1 chosen
  4. Note trade-offs and performance impact
  5. Define criteria for when to use Option 2
  6. Plan next phase accordingly

  File: .pm/DECISION-LOG.md

  Contents:
  ```markdown
  # Phase 3b Decision Log: Cardinality Boundary Fix

  ## Decision: Use Option 1 (Quick Fix)
  **Date**: 2026-01-24
  **Timeline**: 2-4 hours
  **Status**: IMPLEMENTED

  ## The Three Options

  ### Option 1: Early Detection (CHOSEN)
  - Check cardinality before building iterate
  - Return null if base case infinite
  - Fall back to cartesian product
  - Timeline: 2-4 hours
  - Advantage: Fast, safe, unblocks testing

  ### Option 2: Mode Analysis (Deferred)
  - Build comprehensive mode inference
  - Deferred until performance evaluated
  - Timeline: 2-3 days
  - Advantage: Fully solves boundary problem

  ### Option 3: SQL-Style Deferred (Deferred)
  - Use LinkCode pattern like recursion
  - Deferred for future phases
  - Timeline: 3-5 days
  - Advantage: Most robust solution

  ## Why Option 1
  1. Unblocks transitive closure testing
  2. Quick implementation (same day)
  3. Safe fallback (proven baseline)
  4. Gateway to understand performance
  5. Informs optimization decisions

  ## Trade-Offs
  - Performance: Not optimized (cartesian product used)
  - Correctness: 100% correct, just slower
  - Future: Option 2 provides optimization path

  ## When to Revisit for Option 2
  - If cartesian product too slow
  - If mode analysis needed for other reasons
  - After Phase 3b-Final proves concept

  ## Metrics
  - Time Saved: 2-3 days (vs Option 2/3)
  - Tests Passing: 159/159
  - Infinite Extents in Code: 0
  - Runtime Errors: 0
  ```

  Commits:
  - 1 commit: "Document Phase 3b decision: chose Option 1 quick fix"

  Updates:
  - Update CONTINUATION-PHASE-3B.md with completion notes
  - Update PHASES.md with note about cardinality issue
  - Update EXECUTION_STATE.md with current state

  Reference: PHASE-3B-CARDINALITY-FIX.md "Recommended Approach" section

Epic: "Phase 3b-Final Cardinality Fix"
Context: .pm/PHASE-3B-CARDINALITY-FIX.md (lines: Recommended Approach)
```

---

## Bead Creation Commands

Use these commands to create the beads (in order):

```bash
# Create Epic Bead
bd create "Phase 3b-Final Cardinality Fix" -t epic -p 1 \
  --description "Fix Phase 3b cardinality boundary: prevent infinite extents in generated code" \
  --time 2h \
  --related "Issue #217"

# Note the epic ID returned, then use it for dependencies

# Create Task 1 (no dependencies yet)
bd create "Phase 3b-Verify: Confirm 'infinite: int * int' error" -t task -p 1 \
  --time 30m \
  --description "Verify transitive closure test fails with expected error"

# Create Task 2 (blocks Task 3)
bd create "Phase 3b-Implement: Add cardinality check to PredicateInverter.java" -t task -p 1 \
  --time 2h \
  --description "Implement cardinality check to prevent infinite extents in code generation"

# Create Task 3 (blocks Task 4)
bd create "Phase 3b-Test: Verify all tests pass and no infinite extents" -t task -p 1 \
  --time 1h \
  --description "Run comprehensive tests to verify cardinality fix works"

# Create Task 4 (final)
bd create "Phase 3b-Document: Record decision rationale and optimization path" -t task -p 2 \
  --time 30m \
  --description "Create decision log and plan next phase"
```

### Alternative: One-Liner for Epic Only

```bash
bd create "Phase 3b-Final Cardinality Fix" -t epic -p 1 -c "Fix Phase 3b cardinality boundary"
```

Then create tasks individually as needed.

---

## Bead Dependencies (After Creation)

Once beads are created, establish dependencies:

```bash
# Get bead IDs from creation output
# Example: morel-3b-verify (ID), morel-3b-implement (ID), etc.

# Set up dependency chain:
# Verify → Implement → Test → Document

bd dep add morel-3b-implement morel-3b-verify
bd dep add morel-3b-test morel-3b-implement
bd dep add morel-3b-document morel-3b-test
```

---

## Workflow: Using These Beads

### Starting Work
```bash
bd ready  # See available (unblocked) beads
# Should show: morel-3b-verify (Task 1)

bd show morel-3b-verify
# Read task description and acceptance criteria
```

### During Work
```bash
# Update status as you progress
bd update morel-3b-verify --status in_progress

# Track progress in task comment
bd comment morel-3b-verify "Stack trace captured, confirmed error at line X"

# When task done
bd update morel-3b-verify --status done
bd close morel-3b-verify
```

### Next Task
```bash
bd ready  # Should now show morel-3b-implement

bd show morel-3b-implement
# Read implementation details
# Follow the steps in description
```

### Final Summary
```bash
bd show morel-3b-final-cardinality-fix  # Epic bead
# Shows all 4 subtasks and their status
```

---

## Handoff Specification

When handing off to java-developer:

### Input
1. **Beads**: All 4 beads created and visible in `bd ready`
2. **Documentation**:
   - PHASE-3B-CARDINALITY-FIX.md (implementation guide)
   - CONTINUATION-PHASE-3B.md (context)
   - ChromaDB documents (analysis and strategy)
3. **Source Code**: PredicateInverter.java ready at lines 463-515

### Deliverable
1. **Modified Code**: PredicateInverter.java with cardinality check
2. **All Tests Pass**: 159 baseline tests + PredicateInverterTest
3. **Commits**: Proper commit messages with references
4. **Beads Closed**: All 4 tasks marked done

### Quality Criteria
- Code compiles: ✓
- Tests pass: ✓
- No infinite extents: ✓
- Comments added: ✓
- Reviews clear: ✓

---

## Success Metrics

### Task 1 Success
- Stack trace captured and documented
- Error location identified
- Root cause confirmed
- Ready for Task 2

### Task 2 Success
- Code compiles with no errors
- ~20 lines changed (focused)
- Comments explain purpose
- Ready for Task 3

### Task 3 Success
- All 159 tests pass
- No "infinite: int * int" errors
- Transitive closure test executes
- Ready for Task 4

### Task 4 Success
- Decision log created
- Rationale documented
- Future path clear
- Epic can be closed

### Overall Epic Success
- All 4 tasks done
- All tests passing
- No blockers remain
- Ready for Phase 3c or Phase 4

---

## Escalation

If issues arise during implementation:

**Issue**: Cardinality enum not found
- **Solution**: Check Generator.java for correct constant name
- **Escalate**: If name differs, file needs adjustment

**Issue**: Tests fail after change
- **Solution**: Verify null handling in calling code
- **Escalate**: May need additional null checks

**Issue**: Performance unacceptable
- **Solution**: Document baseline, plan Option 2
- **Escalate**: Decision point for Phase 4

---

## Timeline (With Beads)

```
2026-01-24 (Today):
  ✓ Analysis complete
  ✓ Beads created
  ✓ Documentation ready
  → Next: Assign Task 1

2026-01-25:
  → Task 1: Verify problem (30 min)
  → Task 2: Implement fix (2-3 hrs)
  → Task 3: Test & validate (1 hr)
  → Task 4: Document decision (30 min)
  → All tasks: ~4-5 hours total

2026-01-26:
  ✓ Phase 3b-Final complete
  ✓ Decision point: evaluate performance
  ✓ Plan Phase 3c or Phase 4
```

---

**Status**: Beads ready to create
**Assigned To**: TBD (pending orchestrator bead creation)
**Quality Gate**: None (straightforward task)
**Timeline**: 2-4 hours total for all 4 beads

**Created**: 2026-01-24
**Updated By**: Orchestrator Agent
**Last Review**: 2026-01-24
