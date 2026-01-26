# Phase 5 Beads: Complete Specifications & Enrichment

**Date**: 2026-01-24
**Purpose**: Fully enriched bead specifications with acceptance criteria, dependencies, and execution context
**Status**: READY FOR EXECUTION

---

## Bead morel-wvh: Phase 5a Environment Scoping Validation ⚠️ CRITICAL

**Priority**: P0 (GO/NO-GO Decision Gate)
**Status**: OPEN (Ready to claim)
**Type**: Task
**Estimated Time**: 2-4 hours
**Owner**: (Available for assignment)

### Summary
Validate the core technical assumption: Can PredicateInverter access runtime-bound variables (like `edges`) when inverted from within a recursive function body?

**This is the GO/NO-GO gate.** If environment scoping works as expected, proceed with Phases 5b-5d. If it fails, the entire Core.Apply approach must be reconsidered.

### Problem Statement
From substantive-critic Issue #2: The Phase 4 research document claims Core.Apply "naturally defers evaluation" but never proves that PredicateInverter can actually access the `edges` binding when trying to invert `path(x,z)` in:

```sml
val edges = [{x=1,y=2}, {x=2,y=3}]
fun edge (x, y) = {x, y} elem edges
fun path (x, y) = edge (x, y) orelse
  (exists z where edge (x, z) andalso path (z, y))

from p where path p
```

**Question**: When SuchThatShuttle calls PredicateInverter to invert the exists pattern, is `edges` in scope?

### What to Investigate
1. **Compiles.java:133-194** - Environment construction in function compilation
   - How is the environment built before calling SuchThatShuttle?
   - What bindings are included?
   - Are function bodies available?

2. **SuchThatShuttle.java** - Environment passing
   - What environment is passed to Expander?
   - Is the newly-defined function in this environment?
   - Can function body access outer scope variables?

3. **PredicateInverter.java** - Variable lookup
   - How does PredicateInverter access the environment?
   - Can it look up `edges` binding?
   - What happens if binding is not found?

4. **Extents.java:538** - Integration point
   - When Extents calls PredicateInverter, what environment is passed?
   - Is this the same environment used during SuchThatShuttle?
   - Do we have a concrete trace through this flow?

### Success Criteria - MUST HAVE ALL OF THESE

**PASS (Environment Scoping Works)**:
- [ ] ✅ Created code trace showing `edges` variable IS accessible in PredicateInverter
- [ ] ✅ Documented exact environment flow: Compiles → SuchThatShuttle → PredicateInverter
- [ ] ✅ Identified where variable lookup succeeds (specific code lines)
- [ ] ✅ Created minimal proof-of-concept showing variable access works
- [ ] ✅ Document shows how this enables deferred evaluation in Core.Apply
- [ ] ✅ Written "PHASE-5A-ENVIRONMENT-SCOPING-VALIDATION.md" with findings
- [ ] ✅ **Decision**: GO - Proceed to Phase 5b/5c/5d

**FAIL (Environment Scoping Doesn't Work)**:
- [ ] ✅ Created code trace showing `edges` variable is NOT accessible
- [ ] ✅ Documented exactly where scope breaks
- [ ] ✅ Identified root cause (environment not propagated, variable not bound, etc.)
- [ ] ✅ Written "PHASE-5A-ENVIRONMENT-SCOPING-VALIDATION.md" with findings
- [ ] ✅ Clearly documented why Core.Apply approach doesn't work
- [ ] ✅ **Decision**: NO-GO - Trigger fallback plan
  - Option A: Pursue Mode Analysis (higher cost)
  - Option B: SQL-inspired explicit syntax (different design)

### Acceptance Criteria

**Technical**:
- [ ] Evidence is concrete (code traces, not speculation)
- [ ] Covers both positive case (works) and negative case (fails)
- [ ] Includes specific line numbers and code locations
- [ ] Shows data flow through all intermediate steps
- [ ] Explains how any found issues could be resolved

**Documentation**:
- [ ] Clear summary of findings (1 page max)
- [ ] Detailed investigation report (2-5 pages)
- [ ] Code walkthrough with annotations
- [ ] Explicit GO/NO-GO recommendation with reasoning
- [ ] Handoff clear enough for Phase 5b/5c/5d to proceed

**Decision Quality**:
- [ ] Decision is definitive (not "probably works")
- [ ] Both success and failure paths explored
- [ ] If FAIL, fallback path is explicit
- [ ] Senior reviewer (plan-auditor) agrees with methodology
- [ ] Next phase is unblocked (no ambiguity)

### Key Investigation Points

**Check These Code Sections**:
1. `Compiles.java:133-194` - Function compilation environment
2. `Compiles.java:189-200` - SuchThatShuttle instantiation
3. `SuchThatShuttle.java` - How environment flows to Expander
4. `Expander.java:85-98` - Grounding check where errors occur
5. `PredicateInverter.java` - Environment parameter and lookup
6. `Extents.java:530-550` - User-defined function inversion (where Core.Apply would be generated)

**Create a Trace Document** like:
```
Flow: Compiles.java:194 SuchThatShuttle created with env
  → env contains {edges = [...], edge = fn, path = fn}
  → Expander.java:88 called with same env
  → Generator.cache.bestGenerator(z) called
    → PredicateInverter.invert(path(x,z), ...) called
    → PredicateInverter.env contains {edges = [...]} ✓ YES or ✗ NO
```

### Failure Modes & Mitigation

**Risk 1**: Can't find clear environment propagation path
- **Mitigation**: Add debug output to trace environment through compilation
- **Outcome**: Either find the path or confirm it doesn't exist

**Risk 2**: Variable is bound but not accessible due to scope issues
- **Mitigation**: Check if environment is captured or recreated at each step
- **Outcome**: Identify where scope is lost

**Risk 3**: Finding is inconclusive (works sometimes, not others)
- **Mitigation**: Test with multiple examples to establish pattern
- **Outcome**: Understand conditions for success/failure

### Deliverables

**Primary**:
- File: `.pm/PHASE-5A-ENVIRONMENT-SCOPING-VALIDATION.md`
  - Summary (1 page)
  - Detailed findings (2-5 pages)
  - Code walkthrough with line numbers
  - GO/NO-GO decision with reasoning
  - Fallback plan if NO-GO

**Secondary** (if helpful):
- Test class or debug script showing environment access
- Annotated code trace document
- Flow diagram showing environment flow

### Dependencies
- **Blocks**: morel-c2z (Phase 5b), morel-9af (Phase 5c)
- **No prerequisites** (can start immediately)

### Context
- Substantive-critic Issue #2: https://claude.ai (Issue 2 in PHASE-5-DECISION.md)
- Test case: `src/test/resources/script/such-that.smli` lines 737-744
- Reference design: `PHASE-5-DECISION.md` "Validation Roadmap" section

### Notes
- This is the most critical validation
- If this fails, Core.Apply approach doesn't work
- Estimated 2-4 hours, but could reveal unexpected complexity
- Good communication is critical - next phases depend on clarity of this decision

---

## Bead morel-c2z: Phase 5b Pattern Coverage Specification

**Priority**: P1
**Status**: OPEN (Blocked by morel-wvh)
**Type**: Task
**Estimated Time**: 4-8 hours
**Owner**: (Available after 5a passes)

### Summary
Define exactly which recursive predicate patterns Core.Apply will recognize and optimize. Specify supported patterns, unsupported patterns, error handling, and design rationale.

### Problem Statement
From substantive-critic Issue #5: The research document describes only one specific pattern (transitive closure with `orelse` and `exists`) but doesn't specify what pattern variations will work, what error messages users will see, or how brittle the pattern matcher is.

**Question**: When a user writes a recursive predicate that *almost* matches our pattern, what happens? Silent fallback? Error? Wrong code?

### What to Deliver

**1. Pattern Specification Document** (`.pm/PHASE-5B-PATTERN-SPECIFICATION.md`)

**Section A: Supported Patterns (Formal Grammar)**
```
Recursive Predicate Pattern:
  fun name (params) =
    base_case orelse
    (exists vars where cond andalso name(...))

Base Case: Any directly-invertible expression
  - Function call: edge(x, y)
  - List membership: elem
  - Other invertible predicates

Recursive Case: exists pattern with conjunction
  - Exactly: (exists vars where clause1 andalso clause2 andalso ... andalso nameCall)
  - clause1...N must be invertible (e.g., edge(x, z))
  - nameCall must be recursive call: name(args, ...)
  - All variables in nameCall must appear in earlier clauses
```

**Section B: Supported Pattern Examples** (5-10 concrete examples)
- Transitive closure: path(x,y) = edge(x,y) orelse (exists z where edge(x,z) andalso path(z,y))
- Reverse order: path(x,y) = (exists z where edge(x,z) andalso path(z,y)) orelse edge(x,y)
- Variations: What about different variable orders? Multiple edges in conjunction?

**Section C: Unsupported Patterns** (What we explicitly DON'T handle)
- Mutual recursion (path and other simultaneously)
- Multiple recursive calls in single branch
- Recursive calls not in final conjunction
- Complex patterns that don't match grammar
- Example: `fun path(x,y) = edge(x,y) orelse path(y,x)` (not the transitive closure pattern)

**Section D: Error Handling**
- Pattern doesn't match → What happens?
  - Silent fallback to cartesian product? ✓ (RECOMMENDED)
  - Warning message? (optional)
  - Error? (too strict - breaks valid queries)
- Partially matching patterns → How strict is matching?
  - Conservative: Fail unless exact match (safest)
  - Aggressive: Attempt optimization if close enough (riskier)
- User-facing error messages (if any)

**Section E: Design Rationale**
- Why these patterns? (based on transitive closure need)
- Why not broader patterns? (complexity/correctness trade-off)
- Why this error handling? (silent fallback = user never confused)
- Could this be extended? (yes, future work)

### Success Criteria

- [ ] ✅ Formal grammar captures all supported patterns
- [ ] ✅ 5-10 concrete examples with explanations
- [ ] ✅ Clear list of unsupported patterns with examples
- [ ] ✅ Error handling decision made and documented
- [ ] ✅ Design rationale explains trade-offs
- [ ] ✅ Document reviewed by plan-auditor for clarity
- [ ] ✅ Can be used by Phase 5d implementer without questions
- [ ] ✅ Phase 5c test plan references this spec

### Acceptance Criteria

**Content**:
- [ ] Any developer can understand which patterns work
- [ ] Error behavior is unambiguous
- [ ] Design rationale is clear
- [ ] Future variations are discussed (extensibility)

**Clarity**:
- [ ] Uses concrete code examples (not abstract description)
- [ ] Grammar notation is consistent
- [ ] All key design decisions are explained
- [ ] No ambiguity about "what will the code do?"

**Completeness**:
- [ ] Covers all pattern dimensions:
  - Order of base/recursive case
  - Single vs multiple recursive calls
  - Conjunction patterns
  - Variable binding requirements
- [ ] Error cases specified
- [ ] Performance implications noted

### Deliverable
- File: `.pm/PHASE-5B-PATTERN-SPECIFICATION.md`
- Format: Markdown with code examples
- Length: 3-5 pages
- Audience: Developers implementing Phase 5d
- Usage: Referenced by Phase 5c tests and Phase 5d implementation

### Dependencies
- **Blocked by**: morel-wvh (Phase 5a)
- **Blocks**: morel-aga (Phase 5d)
- **Related**: morel-9af should reference this spec

### Notes
- This is the "contract" between research and implementation
- Strict specification prevents bugs and user confusion
- Silent fallback for unmatched patterns is safest approach
- Document precision is critical for Phase 5d success

---

## Bead morel-9af: Phase 5c Comprehensive Test Plan

**Priority**: P1
**Status**: OPEN (Blocked by morel-wvh)
**Type**: Task
**Estimated Time**: 4-8 hours
**Owner**: (Available after 5a passes)

### Summary
Create comprehensive test plan with acceptance criteria for Core.Apply implementation. Define test cases, success criteria, and acceptance standards.

### Problem Statement
From substantive-critic Observation #3: Neither research document specifies how to test that Core.Apply generation is correct, what happens if generated code produces wrong results, or how to verify performance claims.

**Question**: What tests prove Core.Apply works and doesn't break anything else?

### What to Deliver

**File**: `.pm/PHASE-5C-TEST-PLAN.md`

**Section A: Correctness Tests** (What results must be right?)
1. **Transitive Closure - Basic Case**
   - Input: edges = [(1,2), (2,3)]
   - Query: from p where path p
   - Expected: [(1,2), (2,3), (1,3)]
   - File: such-that.smli:737-744

2. **Transitive Closure - With Cycles**
   - Input: edges = [(1,2), (2,3), (3,1)]
   - Expected: All pairs reachable in cycle
   - Purpose: Verify convergence handles cycles

3. **Disconnected Components**
   - Input: edges = [(1,2), (3,4), (5,6)]
   - Expected: All direct and transitive edges
   - Purpose: No false connections between components

4. **Empty Edges**
   - Input: edges = []
   - Expected: []
   - Purpose: Boundary condition

5. **Single Edge**
   - Input: edges = [(1,2)]
   - Expected: [(1,2)]
   - Purpose: Base case only (no recursion)

6. **Self-Loops**
   - Input: edges = [(1,1), (1,2)]
   - Expected: [(1,1), (1,2), and transitives...]
   - Purpose: Reflexive edges

**Section B: Pattern Variation Tests** (Do pattern variations work?)
1. Base case second (orelse order reversed)
2. Different variable naming
3. Different join patterns
4. Nested exists (if supported)

**Section C: Performance Tests** (Is it actually faster?)
1. **Small Graph** (< 100 edges)
   - Measure: Time, result count, iterations
   - Baseline: Cartesian product time
   - Expected: Core.Apply ≥ baseline (acceptable)

2. **Medium Graph** (100-1000 edges)
   - Measure: Time, result count, iterations
   - Baseline: Cartesian product time
   - Expected: Core.Apply > baseline (desired)

3. **Large Graph** (> 1000 edges)
   - Measure: Time, result count, iterations
   - Baseline: Cartesian product time
   - Expected: Core.Apply >> baseline (significant win)

**Section D: Edge Case Tests** (What about weird inputs?)
1. Unsupported pattern graceful fallback
2. Type errors properly reported
3. Variable name conflicts
4. Large intermediate results

**Section E: Regression Tests** (Did we break anything?)
1. Existing FROM/WHERE queries still work
2. Non-recursive predicates unaffected
3. Existing test suite passes 100%
4. No performance degradation elsewhere

### Success Criteria

**Test Coverage**:
- [ ] ✅ Correctness: 6 base cases + variations
- [ ] ✅ Performance: 3 graph sizes measured
- [ ] ✅ Edge cases: 4+ scenarios
- [ ] ✅ Regressions: Full existing test suite
- [ ] ✅ All tests have clear pass/fail criteria

**Test Quality**:
- [ ] ✅ Tests are independent (can run in any order)
- [ ] ✅ Tests are reproducible (same input → same output)
- [ ] ✅ Tests validate both success and failure paths
- [ ] ✅ Expected values match test case specification

**Acceptance Criteria Documentation**:
- [ ] ✅ Each test has explicit "expected result"
- [ ] ✅ Pass/fail criteria are measurable
- [ ] ✅ Performance thresholds defined (if applicable)
- [ ] ✅ Regression criteria clear (no new failures)

### Deliverable
- File: `.pm/PHASE-5C-TEST-PLAN.md`
- Format: Markdown with test specifications
- Length: 3-5 pages
- Includes: Test cases, expected results, success criteria
- Usage: Reference for Phase 5d implementation and verification

### Key Test Case Format
```markdown
### Test: Transitive Closure - Basic Case

**Input**:
```sml
val edges = [{x=1,y=2}, {x=2,y=3}]
fun edge(x,y) = {x,y} elem edges
fun path(x,y) = edge(x,y) orelse (exists z where edge(x,z) andalso path(z,y))
from p where path p
```

**Expected Output**: `[(1,2), (2,3), (1,3)]` (order may vary)

**Why**: Validates basic transitive closure generates correct results

**Pass Criteria**: Output contains all 3 tuples, no duplicates, no extra tuples
```

### Dependencies
- **Blocked by**: morel-wvh (Phase 5a)
- **Blocks**: morel-aga (Phase 5d)
- **Related**: morel-c2z (pattern spec) must be defined first

### Notes
- Test plan drives implementation (TDD methodology)
- Acceptance criteria prevent "I think it works" situations
- Performance baseline helps validate optimization value
- Regression tests prevent "we fixed the bug but broke 10 others"

---

## Bead morel-aga: Phase 5d Prototype Validation & POC

**Priority**: P1
**Status**: OPEN (Blocked by morel-c2z AND morel-9af)
**Type**: Task
**Estimated Time**: 2-3 days
**Owner**: (Available after 5b & 5c pass)

### Summary
Build minimal Core.Apply implementation prototype. Demonstrate pattern detection for simplest transitive closure case and Core.Apply code generation. Validate approach before committing to full implementation.

### Problem Statement
We've validated environment scoping (5a), specified patterns (5b), and created tests (5c). Now prove the approach actually works by building a minimal implementation that passes the basic test case.

### What to Build

**Scope: MINIMAL** - Only what's needed to pass basic test case

**Phase 5d-1: Pattern Detection** (2-3 hours)
- Detect transitive closure pattern in function body
- Location: `PredicateInverter.java` around line 440-520
- Input: Function body AST
- Output: Boolean "is this the transitive closure pattern?"
- Test case:
  ```sml
  fun path(x,y) = edge(x,y) orelse (exists z where edge(x,z) andalso path(z,y))
  ```
- Success: Returns TRUE

**Phase 5d-2: Core.Apply Generation** (3-4 hours)
- Generate Core.Apply for Relational.iterate
- Location: `PredicateInverter.java` method `invertTransitiveClosure()`
- Input: Detected transitive closure pattern
- Output: Core IR representing:
  ```sml
  Relational.iterate(edges)(fn (oldPaths, newPaths) =>
    from (x,z) in edges, (z2,y) in newPaths where z=z2 yield (x,y))
  ```
- Success: Generated code compiles

**Phase 5d-3: Integration with Extents** (1-2 hours)
- Wire pattern detection into Extents.java:538
- When Op.ID case hits, try pattern detection
- If matches, use Core.Apply instead of cartesian product
- Success: No compilation errors

**Phase 5d-4: Test Against Basic Case** (1-2 hours)
- Test file: `src/test/resources/script/such-that.smli` lines 737-744
- Expected output: `[(1,2),(2,3),(1,3)]`
- Run: `./mvnw test -Dtest=ScriptTest`
- Success: Test passes, all assertions green

### Success Criteria - MUST HAVE ALL

**Code Quality**:
- [ ] ✅ Code compiles without errors
- [ ] ✅ No new warnings introduced
- [ ] ✅ Follows Morel code style
- [ ] ✅ Pattern detection is explicit (not magic)
- [ ] ✅ Core.Apply generation is clear

**Functional Requirements**:
- [ ] ✅ Pattern detection correctly identifies transitive closure
- [ ] ✅ Core.Apply generation produces correct IR
- [ ] ✅ Integration with Extents doesn't break existing functionality
- [ ] ✅ Basic test case produces exactly: `[(1,2),(2,3),(1,3)]`
- [ ] ✅ No duplicate tuples in results
- [ ] ✅ All existing tests still pass (zero regressions)

**Test Results**:
- [ ] ✅ ScriptTest passes (such-that.smli:737-744)
- [ ] ✅ No new test failures in full suite
- [ ] ✅ Performance is acceptable (no major slowdown)
- [ ] ✅ All 6 correctness tests from Phase 5c pass

**Documentation**:
- [ ] ✅ Code changes documented
- [ ] ✅ Pattern matching logic explained
- [ ] ✅ Core.Apply generation walkthrough provided
- [ ] ✅ Known limitations documented

### Acceptance Criteria

**Correctness**:
- [ ] Produces exactly the expected result
- [ ] No off-by-one errors or missing tuples
- [ ] Handles the basic test case perfectly

**Robustness**:
- [ ] Doesn't break non-recursive queries
- [ ] Doesn't break other elem() patterns
- [ ] Gracefully falls back for unmatched patterns

**Implementation Quality**:
- [ ] Code is understandable to future maintainers
- [ ] Pattern logic is isolated (not scattered)
- [ ] Generation logic is separate from detection
- [ ] Easy to extend for future patterns

### Key Implementation Points

**Pattern Detection Checklist**:
- [ ] Function body is `baseCase orelse recursiveCase`
- [ ] baseCase is directly invertible
- [ ] recursiveCase is `(exists vars where conj1 andalso ... andalso fnCall)`
- [ ] fnCall is recursive to same function
- [ ] All variables in fnCall appear in earlier conjuncts

**Core.Apply Generation Checklist**:
- [ ] Extract base case and invert it
- [ ] Build step function with proper signature
- [ ] Create Relational.iterate call with correct arguments
- [ ] Return Core IR (not executable code yet)

**Integration Checklist**:
- [ ] Extents.java Op.ID case calls new method
- [ ] New method tries pattern detection
- [ ] If matches: return Core.Apply (inverted)
- [ ] If doesn't match: return empty (fallback to cartesian)
- [ ] Error handling is defensive

### Deliverables

**Code**:
- Modified `PredicateInverter.java` with pattern detection and generation
- Modified `Extents.java` to call new detection method
- Git commit with clear message

**Documentation**:
- Code walkthrough showing pattern detection logic
- Core.Apply generation explanation with example
- Test results showing basic case passes
- Any limitations or future work identified

**Test Results**:
- Full test suite results (before & after)
- Specific output for such-that.smli:737-744
- Performance measurements (if applicable)

### Dependencies
- **Blocked by**: morel-c2z (Phase 5b), morel-9af (Phase 5c)
- **No blockers** once 5b & 5c complete
- **Enables**: Full implementation work (Phase 6+)

### Risk Mitigation

**Risk 1: Pattern detection misses edge cases**
- Mitigation: Start with simplest pattern only
- Acceptance: Pass basic test, document limitations

**Risk 2: Core.Apply generation produces wrong IR**
- Mitigation: Use existing Relational.iterate as template
- Acceptance: Generated code matches expected structure

**Risk 3: Integration breaks existing functionality**
- Mitigation: No changes to non-transitive-closure paths
- Acceptance: All existing tests pass

**Risk 4: Unexpected environment issues discovered**
- Mitigation: Phase 5a should have validated this
- Acceptance: If problem found, have fallback plan ready

### Success Metrics

**Hard Criteria** (Must pass):
- Test produces `[(1,2),(2,3),(1,3)]` ✓
- Zero new test failures ✓
- Code compiles without warnings ✓

**Soft Criteria** (Should pass):
- Code is clean and understandable
- Pattern logic is clear
- Integration is minimal and focused
- Documentation explains approach

### Timeline Breakdown
- D1 (2-3 hours): Pattern detection implementation & testing
- D1-D2 (3-4 hours): Core.Apply generation & validation
- D2 (1-2 hours): Extents integration
- D2-D3 (1-2 hours): Test execution and verification
- D3: Fix any issues, document results

### Post-Completion Checklist
- [ ] All Phase 5c tests pass
- [ ] Code reviewed by plan-auditor
- [ ] Documented in tech decision log
- [ ] Ready to proceed to full implementation (Phase 6+)
- [ ] Known limitations and future work documented

### Notes
- This prototype is not "throw-away code"
- Expect to evolve this into full implementation
- Focus on correctness over optimization in prototype
- Document assumptions for future phases

---

## Dependency Summary

```
morel-wvh (Phase 5a)
  ↓ BLOCKS
├→ morel-c2z (Phase 5b)
│   ↓ BLOCKS
│   └→ morel-aga (Phase 5d)
│
└→ morel-9af (Phase 5c)
    ↓ BLOCKS
    └→ morel-aga (Phase 5d)
```

**Sequential Dependency**: 5a → {5b, 5c} (parallel) → 5d

**Critical Path**: 5a (2-4h) → 5c (4-8h, parallel with 5b) → 5d (2-3d) = **3-4 days total**

---

## Execution Guidelines

### For Claiming a Bead
1. Use `bd update <id> --status=in_progress`
2. Review full specification above
3. Create corresponding document file (e.g., `.pm/PHASE-5A-*.md`)
4. Update bead as you progress
5. Use `bd close <id>` when complete with deliverable

### For Phase 5a (GO/NO-GO)
- If PASS: Document clearly, unblock 5b & 5c
- If FAIL: Document root cause, trigger fallback planning
- Either way: Clear decision, no ambiguity for next phases

### For Phase 5b, 5c, 5d
- Can run 5b and 5c in parallel after 5a passes
- Start 5d only after both 5b and 5c are complete
- Reference Phase 5b spec during 5d implementation
- Reference Phase 5c tests during 5d verification

---

## Success Definition for Phase 5

**All four beads complete AND:**
- ✅ Environment scoping validated (5a GO)
- ✅ Pattern coverage specified (5b)
- ✅ Test plan created (5c)
- ✅ Prototype passes all tests (5d)
- ✅ Plan-auditor approves all work

**Then**: Ready for full Core.Apply implementation (Phase 6+)

---

**Status**: READY FOR EXECUTION
**Next Action**: Claim morel-wvh and begin Phase 5a validation
