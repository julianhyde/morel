# Remediation Plan Critique: Predicate Inversion

**Critic**: substantive-critic (sonnet)
**Date**: 2026-01-25
**Plan Under Review**: `PREDICATE_INVERSION_REMEDIATION_PLAN.md`
**Gold Standard**: `morel_active/gold-standard-requirements.md` (scott-julian.txt)

---

## Executive Summary

The remediation plan correctly identifies the four critical problems from the audit, but the proposed solutions suffer from **critical structural flaws** that will prevent successful implementation:

1. **Phase ordering is inverted** - dependencies are conceptual groupings, not true technical dependencies
2. **Pattern matching completely unaddressed** - tuple patterns vs scalar patterns will cause failures
3. **Non-recursive cases are not solid** - violates Scott's Principle 2
4. **Recursive analysis of recursive predicates** - violates Scott's Principle 3
5. **Test criteria misaligned** - tests claim to pass at phases where required functionality is missing

**Assessment**: The plan would require significant rework during implementation. A simpler approach focusing on function reference lookup without sophisticated mode analysis is recommended.

**Recommendation**: Do not proceed with this plan as written. Restructure phases to address dependencies correctly, or consider simpler alternative approach.

---

## Critical Issues

### Issue 1: Phase A Premature Deletion

**Location**: Plan lines 33-80, Phase A tasks

**Problem**: The plan justifies removing ProcessTreeBuilder (462 lines) by citing Scott's principle "Decompose as without recursion first, then add recursion later." This is a **misapplication of the principle**.

**Scott's actual principle**: Solve the non-recursive INVERSION case first (e.g., `x elem list` → generate from list), THEN handle recursive predicates (e.g., transitive closure).

**The misapplication**: Phase A interprets this as "delete unused code first" rather than "get simple inversion working first."

**Impact**:
- ProcessTreeBuilder might contain valuable predicate structure analysis logic
- 462 lines being deleted without salvage review
- No verification that this code was truly abandoned vs incomplete

**Evidence**: Plan line 42 states: "ProcessTreeBuilder constructs Perfect Process Trees but `tryInvertWithProcessTree()` always returns `Optional.empty()`." This suggests incomplete implementation, not dead code. The method was disabled, not removed, which typically indicates future intent.

**Recommendation**:
- Before deletion, audit ProcessTreeBuilder for reusable components (e.g., pattern extraction, variable binding analysis)
- Document why this approach was abandoned (not documented in plan)
- Only delete after confirming no salvageable logic exists

---

### Issue 2: FunctionRegistry Interface Too Simplistic

**Location**: Plan lines 122-136, Phase B.1

**Problem**: The `InvertibilityStatus` enum has three states:
```java
enum InvertibilityStatus { INVERTIBLE, NOT_INVERTIBLE, RECURSIVE }
```

This is **too coarse-grained** for real-world predicate inversion.

**Missing case**: PARTIALLY_INVERTIBLE functions
- Example: `fun f(x,y) = x > 5 andalso (x,y) elem pairs`
- Can generate pairs (x,y) but only when filtered by constraint x > 5
- Not simply "invertible" (needs filter) or "not invertible" (can generate something)

**Impact**:
- Functions with conjunction of generators and filters will be misclassified
- Either marked NOT_INVERTIBLE (losing optimization opportunity) or INVERTIBLE (producing incorrect results)
- Mode analysis in Phase C won't have enough information to work with

**Evidence**: Gold standard requirement (scott-julian.txt) discusses grounding: "Every variable must ultimately be traced to a finite generator." This implies predicates can have mixed generation/filtering characteristics.

**Recommendation**:
- Add `PARTIALLY_INVERTIBLE` status with associated filter predicates
- Or, store `Set<Core.Exp> requiredFilters` in FunctionInfo
- Revise FunctionInfo record to include constraint information

---

### Issue 3: Argument Substitution Unaddressed

**Location**: Plan lines 154-170, Phase B.3 code pattern

**Problem**: The registry lookup code returns cached generator without handling argument substitution:
```java
case INVERTIBLE:
  return result(info.get().baseGenerator().get(), ImmutableList.of());
```

**Example failure**:
```sml
fun contains(x) = x elem myList
from p where contains(5)
```
- Registry stores generator `myList` for `contains`
- But the call is `contains(5)`, not `contains(x)`
- We need to verify argument `5` matches pattern `x`, and potentially substitute or filter

**Impact**:
- Functions with arguments will fail to invert correctly
- Test `testInvertEdgeFunction` (plan line 180, claimed to pass after Phase B) will actually FAIL
- Pattern matching logic required but not implemented

**Evidence**: Plan line 361 mentions `testInvertEdgeFunction` should pass after Phase B, but this test involves inverting `edge(x,y)` where (x,y) is a tuple pattern and the goal might be a scalar pattern `p`. No substitution logic is shown in Phase B.

**Recommendation**:
- Add argument binding validation to Phase B.3
- Create `ArgumentMatcher` utility to handle pattern matching
- Store formal parameter patterns in FunctionInfo
- Implement substitution logic before claiming INVERTIBLE functions work

---

### Issue 4: Compiler Integration Point Unspecified

**Location**: Plan lines 138-144, Phase B.2

**Problem**: Phase B.2 states "After type checking, analyze each function" but doesn't specify:
- Which compilation phase does this occur in?
- Is it in Compiler.java? A new pass?
- Before or after name resolution?
- How does FunctionRegistry get populated and passed to PredicateInverter?

**Unvalidated assumption**: "This integration point exists and is straightforward"

**Impact**:
- Might require significant compiler architecture changes
- Dependencies between compilation passes might prevent this placement
- Risk classified as MEDIUM in plan (line 396) should be HIGH

**Evidence**: Plan line 139: "**File:** `src/main/java/net/hydromatic/morel/compile/Compiler.java` (or equivalent)" - the "or equivalent" indicates uncertainty about integration point.

**Recommendation**:
- Before starting Phase B, locate exact integration point in codebase
- Verify FunctionRegistry can be constructed at that point (all functions visible)
- Verify FunctionRegistry can be passed to PredicateInverter call sites
- Document compilation pipeline modifications required

---

### Issue 5: ModeAnalyzer Interface Too Coarse-Grained

**Location**: Plan lines 228-267, Phase C.1-C.2

**Problem**: ModeAnalyzer method signature:
```java
Set<Core.NamedPat> canGenerate(Core.Exp predicate, Set<Core.NamedPat> bound)
```

Returns which variables can be generated, but doesn't distinguish:
1. Can generate this variable **alone** (e.g., `x elem list` generates x)
2. Can generate this variable **given these others** (e.g., `x = y + 5` generates x if y is bound)
3. Can generate **this tuple** (e.g., `edge(x,y)` generates pairs, not x and y independently)

**Example failure**:
```sml
String.isPrefix p s
```
- Plan line 259: "can generate {p} if s bound"
- But what about generating all valid (p,s) pairs where the relation holds?
- Interface returns `Set<Core.NamedPat>` but can't express "generates p given s"

**Impact**:
- Mode analysis will be too imprecise for complex predicates
- Join ordering will be suboptimal or incorrect
- Cannot distinguish between "generates x alone" vs "generates x when y is bound"

**Recommendation**:
- Change interface to return `Map<Set<Core.NamedPat>, Set<Core.NamedPat>>`
  - Key: bound variables (input mode)
  - Value: generated variables (output mode)
- Or, introduce `ModeSignature` class to capture input/output modes
- Revise all Phase C tasks to use richer mode information

---

### Issue 6: Phase Ordering Inversion (Critical Structural Flaw)

**Location**: Plan lines 374-386, Dependency Graph

**Problem**: The dependency graph shows:
```
Phase B: Function Resolution
    |
    +-----> Phase C: Mode Analysis
    |              |
    v              v
Phase D: Transitive Closure
```

But the actual **technical dependencies** are:
- Phase C.4 (invertAnds) requires join support to handle `edge(x,z) andalso path(z,y)`
- Join support is in Phase D.2 ("Extract join variable", "Support multiple base predicates")
- Therefore: **C depends on D, not D depends on C**

**Evidence**:
- Plan line 280: Phase C.4 shows FromBuilder with scan/where but no join handling
- Plan line 344: Phase D.2 lists "Extract join variable from exists binding"
- My analysis (thought 7): Conjunction `edge(x,z) andalso path(z,y)` needs join on z

**Impact**:
- Phase C tests will fail because invertAnds can't handle joins
- Plan line 293: "testInvertCompositeWithExists should pass" - this test requires joins, so it CANNOT pass after Phase C
- Developers will implement Phase C, see test failures, and need to restructure

**Recommendation**:
- Restructure phases:
  - Phase C: Basic mode analysis (canGenerate logic)
  - Phase C': Join detection and generation (from Phase D.2)
  - Phase D: Transitive closure using C and C' capabilities
- Or, merge C and D into single phase: "Conjunction and Recursion Handling"
- Update test criteria to match actual phase capabilities

---

### Issue 7: Non-Recursive Case Not Solid (Violates Scott's Principle 2)

**Location**: Plan lines 296-371, Phase D rationale

**Problem**: Scott's Principle 2 states: "Solve the non-recursive case first, then add recursion later."

The plan structures Phase D as "Transitive Closure Completion" implying recursive cases are the final piece. But let me trace a **simple non-recursive example**:

```sml
fun edge(x,y) = (x,y) elem edges
from p where edge p
```

**What should happen**:
- Phase B registers `edge` as INVERTIBLE with generator `edges`
- Phase B.3 returns generator `edges` for goal pattern `p`

**What actually happens**:
- Function `edge` takes tuple pattern `(x,y)`
- Goal pattern is scalar `p`
- Plan has no pattern matching logic to connect tuple pattern to scalar goal
- **Result**: Inversion fails or produces incorrect result

**Impact**:
- Non-recursive function inversion is NOT working after Phases B and C
- Violates Scott's principle to get non-recursive case solid first
- Plan proceeds to complex transitive closure without foundation

**Evidence**:
- Plan line 361: claims `testInvertEdgeFunction` passes after Phase B
- But no pattern matching logic is implemented in Phase B
- Test will actually fail

**Recommendation**:
- Add explicit "Pattern Matching" phase between B and C
- Implement tuple destructuring and reconstruction logic
- Validate all non-recursive test cases pass before proceeding to Phase D
- Do not touch transitive closure until `testInvertEdgeFunction` actually passes end-to-end

---

### Issue 8: Recursive Resolution Chain Incomplete

**Location**: Plan lines 320-333, Phase D.1

**Problem**: Phase D.1 shows using registry lookup for base case:
```java
if (info.isPresent() && info.get().status() == INVERTIBLE) {
  baseCaseResult = result(info.get().baseGenerator(), ImmutableList.of());
}
```

But the gold standard example has a **resolution chain**:
```sml
fun path (x, y) = edge (x, y) orelse ...
```

The base case is `edge(x,y)`, which is **another function call**, not a direct collection reference.

**Resolution chain required**:
1. `path` base case is `edge(x,y)`
2. Look up `edge` in registry
3. `edge` is INVERTIBLE with generator `edges`
4. Substitute to get base generator `edges`

**Plan gap**: No logic shown for resolving nested function calls in base case.

**Impact**:
- Test `from p where path p` will fail
- Success criterion `[(1,2),(2,3),(1,3)]` will not be achieved
- Base case inversion incomplete

**Evidence**: Gold standard shows expected output has base generator `[(1,2),(2,3)]` which is `edges`, but plan doesn't show how to resolve `edge(x,y)` → `edges`.

**Recommendation**:
- Add recursive registry resolution in Phase D.1
- Handle nested function calls in base case
- Implement max recursion depth check to prevent infinite loops
- Add test case for nested function resolution before claiming Phase D complete

---

### Issue 9: Step Function Generation Might Still Inline (Partial Violation of Principle 1)

**Location**: Plan lines 346-355, Phase D.3

**Problem**: Scott's Principle 1: "Edge should never be on the stack."

Phase B correctly avoids inlining during **inversion** by using registry lookup. But Phase D.3 discusses `buildStepFunction()` which generates the iterate step function.

**Question not answered by plan**: Does buildStepFunction still inline function bodies when generating the step?

**If yes**: We've only partially fixed the mixing domains problem. Edge won't be on the inversion stack, but might be on the step function generation stack.

**If no**: Plan should document how step function generation uses registry lookup without inlining.

**Impact**:
- Might still have mixing domains bug in step function generation
- Scott's criticism not fully addressed
- Potential for infinite recursion during step function construction

**Evidence**: Plan line 351 states "Hardcoded variable names" and "Doesn't use mode analysis" as current issues, but doesn't mention inlining behavior.

**Recommendation**:
- Explicitly document whether buildStepFunction inlines or uses registry
- If it inlines, refactor to use registry lookup
- Add assertion that no function appears in multiple stack contexts simultaneously
- Validate no inlining happens anywhere in the predicate inversion pipeline

---

### Issue 10: Recursive Analysis of Recursive Predicates (Violates Principle 3)

**Location**: Plan lines 336-345, Phase D.2

**Problem**: Scott's Principle 3: "Recursion happens in a different domain. The recursive solution for macro expansion is tail-recursion + continuation-passing style, but that should be applied to 'cheapest_first' which is the thing which is actually recursing."

Translation: Recursion should be in the **generated iterate step function**, not in the **inversion traversal**.

**Plan's approach** (Phase D.2):
- "Improve pattern extraction for base/recursive cases"
- "Extract join variable from exists binding"
- "Support multiple base predicates in recursive case"

This is **structurally analyzing the recursive predicate** during inversion. We're traversing the recursive structure, extracting patterns, unwrapping exists - all recursive operations on a recursive predicate.

**Scott's approach**:
1. Detect recursion (FunctionRegistry - done in Phase B ✓)
2. Generate a fixed iterate template (standard pattern)
3. Fill in template with base generator and join condition
4. Recursion happens in the iterate execution, not in our analysis

**Impact**:
- Plan is doing exactly what Scott criticized: mixing analysis recursion with predicate recursion
- Overcomplicated implementation analyzing recursive structure
- Violates separation of concerns

**Evidence**: Gold standard shows iterate template is simple and fixed:
```sml
Relational.iterate baseGenerator
  (fn (old, new) =>
    from (x, z) in new, (z2, y) in baseGenerator
    where z = z2
    yield (x, y))
```

We don't need complex pattern extraction - just plug in base generator and join variable.

**Recommendation**:
- Simplify Phase D.2 to template instantiation, not pattern analysis
- Pre-define iterate templates for common recursive patterns (transitive closure, union, etc.)
- Use registry to get base generator (Issue 8 resolution chain)
- Extract join variable from function signature, not from body traversal
- Stop doing recursive analysis of recursive predicates

---

## Significant Issues

### Issue 11: Risk Assessment Understated

**Location**: Plan lines 388-397, Risk Mitigation table

**Problems**:

1. **"Inliner baseName hack interaction" rated LOW risk**
   - Plan line 399: "Remove baseName hack from Inliner.java ... after Phase B validates"
   - But: This hack prevents infinite recursion during inlining
   - If removed before fully validating FunctionRegistry prevents recursion, bug reintroduced
   - Should be MEDIUM risk, removed after Phase D, not Phase B

2. **"Pattern matching mismatch" not listed as a risk**
   - Issue 7 identified tuple vs scalar pattern problem
   - This is a HIGH risk - will cause non-recursive cases to fail
   - Completely absent from risk table

3. **"Recursive resolution chain" not listed as a risk**
   - Issue 8 identified nested function call resolution gap
   - This is MEDIUM risk - affects success criterion achievement
   - Not mentioned in risk mitigation

4. **"Compiler integration point" understated as MEDIUM**
   - Issue 4 identified integration point is unspecified
   - Could require significant architecture changes
   - Should be HIGH risk until integration point validated

**Impact**:
- False confidence in plan feasibility
- Surprises during implementation
- Potential for significant rework not accounted for in complexity estimates

**Recommendation**:
- Add missing risks to table
- Re-rate existing risks based on impact analysis
- Add mitigation strategies for newly identified risks
- Update complexity estimates if high risks materialize

---

### Issue 12: Test Criteria Misaligned with Phase Capabilities

**Location**: Plan lines 177-181 (Phase B), 291-295 (Phase C)

**Problem**: Test criteria claim tests will pass at phases where required functionality is missing.

**Phase B Test Criterion** (line 180):
> "`testInvertEdgeFunction` (currently disabled) should pass after this phase"

**Reality**: This test requires:
- Pattern matching (tuple `(x,y)` vs scalar `p`) - not implemented in Phase B
- Argument substitution logic - not implemented in Phase B (Issue 3)
- Result: Test CANNOT pass after Phase B

**Phase C Test Criterion** (line 293):
> "`testInvertCompositeWithExists` (currently disabled) should pass"

**Reality**: This test requires:
- Join support for `edge(x,z) andalso path(z,y)` - deferred to Phase D.2 (Issue 6)
- Result: Test CANNOT pass after Phase C

**Impact**:
- Developers will implement phases, see test failures, and assume bugs
- Actually missing foundational functionality, not bugs
- Creates false confidence that phases are "nearly complete"
- Leads to debugging wild goose chases

**Recommendation**:
- Revise test criteria to match actual phase capabilities
- Phase B: Only test registry lookup returns cached generators (no pattern matching)
- Phase C: Only test mode analysis API, not full invertAnds with joins
- Phase D: Enable integration tests only after all prerequisites complete
- Add new intermediate tests that validate partial functionality

---

### Issue 13: Complexity Estimates Unrealistic

**Location**: Plan lines 424-434, Estimated Complexity table

**Problem**: The table estimates:
- Phase B: 200 new lines, 150 modified
- Phase C: 250 new lines, 100 modified
- Phase D: 100 new lines, 200 modified
- Total: ~550 new, ~500 modified

**Missing from estimates**:
- Pattern matching logic (Issue 7) - probably +150 lines
- Argument substitution (Issue 3) - probably +100 lines
- Recursive resolution chain (Issue 8) - probably +75 lines
- Join support moved from D to C (Issue 6) - already counted, but wrong phase
- Mode analysis interface revision (Issue 5) - probably +50 lines rework

**More realistic estimate**:
- Phase B: 350 new lines (registry + pattern matching + substitution)
- Phase C: 400 new lines (mode analysis + join support)
- Phase D: 150 new lines (template instantiation only, not pattern analysis)
- Total: ~900 new lines, ~600 modified

**Impact**:
- Actual effort is ~1.6x estimated
- Phases will take longer than planned
- Scope creep likely as gaps discovered during implementation

**Recommendation**:
- Revise complexity estimates after addressing critique issues
- Add explicit line items for pattern matching, substitution, etc.
- Consider splitting large phases (C especially) into sub-phases
- Budget time for integration issues not accounted for

---

## Observations

### Observation 1: Alternative Approach Not Considered

**Simpler approach**: The plan proposes complex FunctionRegistry + ModeAnalyzer + refactored invertAnds architecture (~550 lines estimated, ~900 lines realistic).

**Alternative**: Simple lookup tables without sophisticated mode analysis:
```java
// Map function names to their generators (if invertible)
Map<FunctionName, Core.Exp> generatorLookup;

// Map recursive function names to iterate templates
Map<FunctionName, IterateTemplate> recursiveLookup;
```

**When encountering function call during inversion**:
1. Look up generator in generatorLookup
2. If found, return generator (no inlining)
3. If recursive, return iterate template
4. Otherwise, return fallback

**Estimated complexity**: ~100-150 lines

**Trade-offs**:
- Pro: Much simpler, addresses Scott's "edge on stack" criticism directly
- Pro: Faster to implement and validate
- Con: Less general than full mode analysis
- Con: Might need expansion later for complex cases

**Why not considered**: Plan jumps directly to sophisticated mode analysis system without evaluating simpler alternatives.

**Recommendation**: Prototype simple lookup approach, validate against test cases, then decide if mode analysis complexity is justified.

---

### Observation 2: ProcessTreeBuilder Might Contain Salvageable Logic

**Plan Phase A**: Delete 462 lines of ProcessTreeBuilder without review.

**Potential salvage**:
- Pattern extraction utilities
- Variable binding analysis
- Predicate structure traversal
- Join variable detection

**These capabilities are needed in later phases** (D.2 pattern extraction, C mode analysis).

**Recommendation**: Before deletion, audit ProcessTreeBuilder for reusable components. Extract to utility classes if valuable logic found.

---

### Observation 3: FunctionRegistry Could Be Simpler Initially

**Plan Phase B**: Full FunctionRegistry with InvertibilityStatus enum, FunctionInfo records, cache management.

**Simpler initial approach**:
- Phase B.1: Just `Map<IdPat, Core.Exp>` for invertible functions
- Phase B.2: Register only `elem` patterns initially
- Phase B.3: Lookup and return, no fancy status tracking

**Then expand**: Add status tracking, partial invertibility, etc. when needed.

**Principle**: "Make it work, make it right, make it fast" - start with simplest thing that could possibly work.

---

### Observation 4: Pattern From gold-julian.txt Not Fully Leveraged

The gold standard document shows the exact expected output:
```sml
Relational.iterate [(1, 2), (2, 3)]
  (fn (oldPaths, newPaths) =>
    from (x, z) in newPaths,
         (z2, y) in [(1, 2), (2, 3)]
      where z = z2
      yield (x, y))
```

This is a **template** with placeholders:
- `[(1,2),(2,3)]` → base generator (from `edges`)
- `z/z2` → join variable
- `(x,y)` → result pattern

**Plan should**: Use this as literal template and just fill in placeholders, not do complex pattern extraction.

---

## Alignment with Scott's Principles

### Principle 1: "Edge should never be on the stack"

**Status**: Partially addressed

**What plan does right**:
- Phase B creates FunctionRegistry to avoid inlining during inversion ✓

**What plan misses**:
- Step function generation (buildStepFunction) might still inline (Issue 9) ✗
- No assertion that functions never appear in multiple stack contexts ✗

**Verdict**: 70% addressed. Needs explicit verification of step function generation.

---

### Principle 2: "Solve the non-recursive case first, then add recursion later"

**Status**: Violated

**What plan does**:
- Structures Phase D as final phase for recursion ✓
- But non-recursive cases don't work after Phases B and C ✗

**Evidence**:
- Issue 7: Pattern matching not implemented, so `from p where edge p` fails
- Issue 3: Argument substitution not implemented
- Non-recursive foundation is NOT solid before tackling transitive closure

**Verdict**: 30% addressed. Plan structure looks right, but functionality is incomplete.

---

### Principle 3: "Recursion happens in a different domain"

**Status**: Violated

**What plan does wrong**:
- Phase D.2 does structural analysis of recursive predicates ✗
- "Extract join variable from exists binding" is recursive traversal ✗
- "Support multiple base predicates" is recursive pattern matching ✗

**What plan should do**:
- Use fixed iterate template (gold standard shows exact pattern)
- Get base generator from registry (no traversal)
- Get join variable from function signature (not body analysis)
- Recursion only in generated iterate step function

**Verdict**: 20% addressed. Plan does generate iterate expressions, but still does recursive analysis during inversion.

---

## Success Criterion Verification

**Success Criterion**: `from p where path p` returns `[(1,2),(2,3),(1,3)]`

**Will this plan achieve it?** Unlikely without significant rework.

**Trace through plan**:

1. **Phase A complete**: Dead code removed ✓
2. **Phase B complete**: FunctionRegistry exists
   - `path` registered as RECURSIVE ✓
   - `edge` registered as INVERTIBLE ✓
   - But: No pattern matching, so `from p where edge p` fails ✗
3. **Phase C complete**: ModeAnalyzer exists
   - Can analyze modes for simple predicates ✓
   - But: No join support, so `edge(x,z) andalso path(z,y)` fails ✗
4. **Phase D complete**: Transitive closure handling
   - tryInvertTransitiveClosure called for `path` ✓
   - Base case `edge(x,y)` needs resolution to `edges` ✗ (Issue 8)
   - Step function generation might still inline ✗ (Issue 9)
   - Pattern matching still missing ✗ (Issue 7)

**Result**: Multiple missing pieces prevent success criterion achievement.

**Gaps preventing success**:
- Pattern matching (Issue 7)
- Argument substitution (Issue 3)
- Recursive resolution chain (Issue 8)
- Join support in wrong phase (Issue 6)
- Step function generation inlining (Issue 9)

**Recommendation**: Do not implement this plan without addressing Issues 3, 6, 7, 8, 9 first. Success criterion will not be achieved.

---

## Verification Performed

**Evidence gathered**:
1. Read remediation plan in full (434 lines)
2. Read gold standard requirements (morel_active/gold-standard-requirements.md)
3. Analyzed each phase for logical consistency
4. Traced simple examples through proposed implementation
5. Cross-referenced Scott's three principles
6. Evaluated test criteria against phase capabilities
7. Assessed risk mitigation completeness

**Cross-references**:
- scott-julian.txt email exchange (via gold standard doc)
- Plan dependency graph (lines 374-386)
- Test criteria for each phase
- Risk mitigation table (lines 388-397)
- Complexity estimates (lines 424-434)

**Analytical methods used**:
- Sequential thinking to trace logic flow
- Example-driven analysis (edge function, path function)
- Dependency analysis (which phases need what from others)
- Principle alignment checking (Scott's 3 principles)
- Test capability matching (what tests require vs what phases provide)

---

## Recommendations

### Immediate Actions (Before Starting Implementation)

1. **Address Issue 7 (Pattern Matching)** - This is foundational
   - Add explicit Pattern Matching phase or sub-phase
   - Implement tuple destructuring and reconstruction
   - Validate with simple non-recursive test cases

2. **Address Issue 6 (Phase Ordering)** - This affects entire structure
   - Move join support from Phase D to Phase C (or create Phase C')
   - Update dependency graph
   - Revise test criteria to match actual capabilities

3. **Address Issue 3 (Argument Substitution)** - Required for functions to work
   - Add ArgumentMatcher utility to Phase B
   - Implement substitution logic
   - Add validation tests

4. **Validate Issue 4 (Integration Point)** - De-risk before starting
   - Locate exact compiler integration point
   - Verify FunctionRegistry can be populated there
   - Document pipeline modifications required

### Structural Changes

5. **Restructure phases** to reflect true dependencies:
   ```
   Phase A: Foundation Cleanup (delete ProcessTreeBuilder)
   Phase B: Function Registry (lookup without inlining)
   Phase B': Pattern Matching (tuple vs scalar)
   Phase C: Basic Mode Analysis (canGenerate logic)
   Phase C': Join Support (conjunction handling)
   Phase D: Transitive Closure (iterate template instantiation)
   ```

6. **Revise test criteria** to match phase capabilities:
   - Phase B: Registry lookup returns generators (no integration tests yet)
   - Phase B': testInvertEdgeFunction passes (pattern matching works)
   - Phase C: Mode analysis unit tests only
   - Phase C': testInvertCompositeWithExists passes (joins work)
   - Phase D: Success criterion achieved

### Alternative Approach

7. **Consider simpler lookup-based approach** as prototype:
   - Implement simple Map<FunctionName, Generator> lookup
   - Validate against test cases
   - Evaluate if full mode analysis needed or if simpler approach sufficient
   - Make informed decision about complexity vs benefit

### Risk Mitigation

8. **Update risk assessment**:
   - Add "Pattern matching mismatch" - HIGH risk
   - Add "Recursive resolution chain" - MEDIUM risk
   - Upgrade "Inliner baseName hack" to MEDIUM risk
   - Upgrade "Integration complexity" to HIGH risk until validated

9. **Revise complexity estimates**:
   - Add pattern matching (+150 lines)
   - Add argument substitution (+100 lines)
   - Add recursive resolution (+75 lines)
   - Total: ~900 new lines, not ~550

### Validation

10. **Add intermediate validation checkpoints**:
    - After Phase B': `from p where edge p` works (non-recursive function)
    - After Phase C': `edge(x,z) andalso path(z,y)` joins correctly
    - After Phase D: Success criterion achieved

---

## Conclusion

The remediation plan identifies the right problems but proposes solutions with **critical structural flaws**:

- **Phase ordering is inverted** (joins needed in C are in D)
- **Non-recursive foundation is incomplete** (pattern matching missing)
- **Recursive analysis violates Scott's principles** (should use templates, not traversal)
- **Test criteria are aspirational** (claim tests pass when functionality missing)

**Verdict**: Do not implement this plan without significant restructuring. The plan would likely require substantial rework during implementation, with Phases C and D needing redesign.

**Recommended path forward**:
1. Address critical Issues 3, 6, 7 before starting implementation
2. Restructure phases to reflect true dependencies
3. Consider simpler lookup-based approach as alternative
4. Add intermediate validation checkpoints
5. Update risk assessment and complexity estimates

**Estimated rework**: ~30-40% of plan needs revision to be viable.

---

**Files Referenced**:
- `/Users/hal.hildebrand/git/morel/PREDICATE_INVERSION_REMEDIATION_PLAN.md` (plan under review)
- `morel_active/gold-standard-requirements.md` (gold standard via Memory Bank)
- scott-julian.txt (via gold standard doc)

**Bead Tracking**:
- Epic: morel-alk (Predicate Inversion Remediation)
- Plan critique findings should inform revised plan
