# Failure Mode Analysis: Phase 5 PredicateInverter Implementation

**Date**: 2026-01-24
**Bead**: morel-b2 (Failure Mode Analysis)
**Author**: strategic-planner agent
**References**: B1 (PHASE-5D-SCOPE-ANALYSIS), B3 (PHASE-5A-SUCCESS-CRITERIA), B7 (INTEGRATION-POINT-ANALYSIS), B8 (TYPE-SYSTEM-ANALYSIS)

---

## Executive Summary: Risk Register

| ID | Failure Mode | Likelihood | Impact | Risk Score | Priority |
|----|--------------|------------|--------|------------|----------|
| FM-01 | g3 called instead of g3b | HIGH | CRITICAL | 9 | P0 |
| FM-02 | Base case always returns INFINITE cardinality | HIGH | CRITICAL | 9 | P0 |
| FM-03 | MultiType ClassCastException in Core.Apply | MEDIUM | HIGH | 6 | P1 |
| FM-04 | Binding.value is null despite binding found | MEDIUM | HIGH | 6 | P1 |
| FM-05 | Step function lambda signature mismatch | MEDIUM | HIGH | 6 | P1 |
| FM-06 | z/z2 join variable type mismatch | MEDIUM | MEDIUM | 4 | P2 |
| FM-07 | FROM expression missing scan steps | LOW | HIGH | 3 | P2 |
| FM-08 | Transitive closure pattern not detected | LOW | HIGH | 3 | P2 |
| FM-09 | Empty edges collection causes NPE | LOW | MEDIUM | 2 | P3 |
| FM-10 | Self-loops cause infinite iteration | LOW | HIGH | 3 | P2 |
| FM-11 | Heterogeneous tuple types unsupported | LOW | LOW | 1 | P3 |
| FM-12 | Relational.iterate bag/list resolution fails | LOW | HIGH | 3 | P2 |
| FM-13 | Circular references cause stack overflow | LOW | MEDIUM | 2 | P3 |
| FM-14 | Triple+ tuple arities rejected silently | MEDIUM | LOW | 2 | P3 |

**Risk Score**: Likelihood (HIGH=3, MEDIUM=2, LOW=1) x Impact (CRITICAL=3, HIGH=2, MEDIUM=1.5, LOW=1)

---

## Detailed Failure Mode Analysis

### FM-01: Integration Point Not Activated (g3 vs g3b)

**Category**: Integration Point

**Symptom**: Test runs but PredicateInverter.invert() is never called. Debug output from PredicateInverter lines 126-148 never appears in stderr.

**Root Cause**: Extents.java line 155 calls `extent.g3()` instead of `extent.g3b()`. The g3b method contains full PredicateInverter integration (lines 553-760) but is never invoked.

**Location**: `/Users/hal.hildebrand/git/morel/src/main/java/net/hydromatic/morel/compile/Extents.java:155`

**Likelihood**: HIGH - This is the current state of the code per B7 analysis.

**Impact**: CRITICAL - Without this fix, entire transitive closure feature is non-functional. Code path to PredicateInverter is completely blocked.

**Mitigation Strategy**:
1. Change line 155 from `extent.g3(map.map, ...)` to `extent.g3b(map.map, ...)`
2. Run baseline tests to verify g3b is superset of g3
3. Confirm PredicateInverter debug output appears

**Estimated Fix Time**: 5 minutes

**Testing**: Run such-that.smli test, verify PredicateInverter trace output in stderr.

---

### FM-02: Base Case Inversion Returns INFINITE Cardinality

**Category**: Cardinality Boundary

**Symptom**: Runtime error "infinite: int * int" (observed in ScriptTest[11]). Debug trace shows "Base case cardinality: INFINITE" at line 523.

**Root Cause**: When inverting `edge(x, y) = {x, y} elem edges`, the `edges` variable is runtime-bound. At compile time, PredicateInverter cannot enumerate its contents, so invert() returns INFINITE cardinality. The check at lines 523-526 then rejects the pattern, returning null.

**Location**: `/Users/hal.hildebrand/git/morel/src/main/java/net/hydromatic/morel/compile/PredicateInverter.java:523-526`

**Likelihood**: HIGH - This is the observed behavior per B8 runtime analysis.

**Impact**: CRITICAL - Entire Relational.iterate code path (lines 551-565) is never reached. Falls back to infinite extent which throws at runtime.

**Mitigation Strategy**:
1. Verify the `edges` binding IS accessible in environment (validated in 5a-prime)
2. Ensure invert() for `elem` expressions recognizes bound collection variables
3. Add special handling for `elem` pattern when RHS is a known bound variable
4. Core.Apply approach should defer evaluation, allowing runtime resolution

**Estimated Fix Time**: 2-4 hours (the core Phase 5d work)

**Testing**: Add cardinality trace at line 523, verify FINITE for invertible base cases.

---

### FM-03: MultiType ClassCastException During Core.Apply Construction

**Category**: Type System

**Symptom**: ClassCastException at line 556-557 when constructing Relational.iterate call. Error: "Cannot cast MultiType to FnType".

**Root Cause**: `core.functionLiteral(typeSystem, BuiltIn.RELATIONAL_ITERATE)` returns a literal with MultiType (bag + list overloads). When `core.apply(Pos.ZERO, iterateFn, baseGenerator)` is called, CoreBuilder.java:582 casts `fn.type` to `FnType`, which fails for MultiType.

**Location**: `/Users/hal.hildebrand/git/morel/src/main/java/net/hydromatic/morel/compile/PredicateInverter.java:556-565`

**Likelihood**: MEDIUM - Code path not yet reached due to FM-02, but will manifest once base case is fixed.

**Impact**: HIGH - Cannot construct Relational.iterate expression, feature completely broken.

**Mitigation Strategy**:
1. Use `core.functionLiteral(typeSystem, BuiltIn.RELATIONAL_ITERATE, ImmutableList.of(baseGenerator, stepFunction))` to resolve overload
2. Or manually select bag variant before calling apply()
3. Ensure resolved type is FnType before casting

**Estimated Fix Time**: 30-60 minutes

**Testing**: Add type trace at line 565, verify no ClassCastException.

---

### FM-04: Function Binding Found But Value Is Null

**Category**: Environment Scoping

**Symptom**: Debug shows "Binding accessible: true" but subsequent code fails with NPE when accessing binding.value.

**Root Cause**: Environment contains the binding key but the value was not populated during compilation. The `Binding` object exists but `binding.value` is null.

**Location**: `/Users/hal.hildebrand/git/morel/src/main/java/net/hydromatic/morel/compile/PredicateInverter.java:257-269`

**Likelihood**: MEDIUM - Phase 5a-prime showed binding IS accessible, but did not verify value is non-null Core.Exp.

**Impact**: HIGH - Cannot inline function body for pattern analysis. Feature fails silently.

**Mitigation Strategy**:
1. Add explicit null check after binding retrieval: `if (binding.value == null) return null;`
2. Trace what type binding.value actually is
3. Verify Compiles.java stores Core.Exp values in bindings

**Estimated Fix Time**: 15-30 minutes (investigation), 1-2 hours if environment needs fixing

**Testing**: Add null check and trace at line 257, verify binding.value is Core.Exp.

---

### FM-05: Step Function Lambda Signature Mismatch

**Category**: Type System

**Symptom**: Type error during Core.Apply construction: "Expected (('a bag * 'a bag) -> 'a bag), got wrong signature".

**Root Cause**: buildStepLambda() constructs lambda with incorrect type. Expected: `('a bag * 'a bag) -> 'a bag`. The tuple pattern destructuring or FnType construction may produce wrong types.

**Location**: `/Users/hal.hildebrand/git/morel/src/main/java/net/hydromatic/morel/compile/PredicateInverter.java:1649-1683`

**Likelihood**: MEDIUM - Code exists and looks correct on inspection, but not runtime-tested.

**Impact**: HIGH - Relational.iterate cannot accept malformed step function. Type system rejects construction.

**Mitigation Strategy**:
1. Add explicit type annotations at each construction step
2. Verify paramType is (bag, bag) tuple, not nested
3. Verify fnType.resultType matches bagType
4. Add assertion: `assert fnType.paramType.equals(paramType);`

**Estimated Fix Time**: 1-2 hours

**Testing**: Add lambda type trace at line 1682, compare against expected signature.

---

### FM-06: Join Variable z/z2 Type Mismatch

**Category**: Type System

**Symptom**: WHERE clause `z = z2` fails type checking. Error: "Cannot compare int with string" (for heterogeneous tuples).

**Root Cause**: At line 1737, z2Pat uses `baseComponents[0]` but z (from line 1729) uses `baseComponents[1]`. For homogeneous tuples like `(int * int)` this works. For `(string * int)`, types differ.

**Location**: `/Users/hal.hildebrand/git/morel/src/main/java/net/hydromatic/morel/compile/PredicateInverter.java:1729, 1737`

**Likelihood**: MEDIUM - For standard transitive closure with homogeneous types, this works. Fails for edge cases.

**Impact**: MEDIUM - Affects only heterogeneous tuple use cases. Core (int * int) transitive closure still works.

**Mitigation Strategy**:
1. Change line 1737: `baseComponents[1]` for z2Pat (same type as z)
2. Or: For transitive closure pattern, require homogeneous tuples
3. Document limitation if not fixing

**Estimated Fix Time**: 15 minutes (simple fix) or document as limitation

**Testing**: Test with `(string * int)` edge types, verify type error or correct behavior.

---

### FM-07: FROM Expression Missing Scan Steps

**Category**: FROM Construction

**Symptom**: FROM expression has wrong number of steps. Debug shows only 1 scan instead of 2, or missing WHERE clause.

**Root Cause**: buildStepBody() scan construction at lines 1748-1758 fails silently. FromBuilder may not add steps correctly if patterns are malformed.

**Location**: `/Users/hal.hildebrand/git/morel/src/main/java/net/hydromatic/morel/compile/PredicateInverter.java:1748-1758`

**Likelihood**: LOW - Code exists and pattern construction looks correct.

**Impact**: HIGH - Transitive closure computation is completely wrong. May produce empty results or wrong tuples.

**Mitigation Strategy**:
1. Add step count verification after build()
2. Assert FROM has exactly 2 scans, 1 where, 1 yield
3. Add trace for each step added to builder

**Estimated Fix Time**: 30-60 minutes (debugging)

**Testing**: Add FROM step trace at line 1783, verify step sequence.

---

### FM-08: Transitive Closure Pattern Not Detected

**Category**: Pattern Detection

**Symptom**: Debug shows "Pattern not recognized as transitive closure". Legitimate recursive patterns rejected.

**Root Cause**: isTransitiveClosurePattern() at line 495 is too strict. Requires exact `baseCase orelse recursiveCase` structure. Variations like different operator ordering or nested expressions not recognized.

**Location**: `/Users/hal.hildebrand/git/morel/src/main/java/net/hydromatic/morel/compile/PredicateInverter.java:495-506`

**Likelihood**: LOW - Standard patterns should match. Only affects variations.

**Impact**: HIGH - Legitimate transitive closure patterns silently fall through to INFINITE extent.

**Mitigation Strategy**:
1. Add pattern detection logging
2. Document expected pattern structure
3. Expand pattern matching to handle common variations
4. Provide clear error messages for almost-matching patterns

**Estimated Fix Time**: 2-4 hours (for expanded pattern support)

**Testing**: Test multiple pattern variations, verify detection.

---

### FM-09: Empty Edges Collection Causes NPE

**Category**: Edge Case

**Symptom**: NullPointerException when `edges = []`. Trace shows NPE in step function execution.

**Root Cause**: Relational.iterate with empty base may have edge case behavior. Step function assumes non-empty newTuples.

**Location**: Runtime in Codes.java iterate implementation

**Likelihood**: LOW - Relational.iterate should handle empty case gracefully.

**Impact**: MEDIUM - Edge case failure. Core functionality unaffected.

**Mitigation Strategy**:
1. Add empty collection test case
2. Verify Relational.iterate handles empty base
3. Add guard clause if needed: `if (baseGen.isEmpty()) return baseGen;`

**Estimated Fix Time**: 15-30 minutes

**Testing**: Test with `val edges = []`, verify empty result not crash.

---

### FM-10: Self-Loops Cause Infinite Iteration

**Category**: Edge Case

**Symptom**: Test hangs or times out. Relational.iterate never reaches fixpoint.

**Root Cause**: Self-loop `edge(1, 1)` produces `path(1, 1)` which joins with edge to produce `path(1, 1)` infinitely. Relational.iterate may not detect this as fixpoint if implementation uses reference equality.

**Location**: Runtime in Codes.java iterate implementation (lines 2902-2925)

**Likelihood**: LOW - Relational.iterate should use value equality and detect fixpoint.

**Impact**: HIGH - Causes test to hang. Bad user experience.

**Mitigation Strategy**:
1. Verify Relational.iterate uses value equality for fixpoint
2. Add timeout/iteration limit test
3. Consider explicit cycle detection in step function

**Estimated Fix Time**: 1-2 hours (if Relational.iterate needs fixing)

**Testing**: Test with `val edges = [{x=1,y=1}]`, verify terminates.

---

### FM-11: Heterogeneous Tuple Types Unsupported

**Category**: Tuple Handling

**Symptom**: Type error or wrong results for edges with different component types like `(string * int)`.

**Root Cause**: buildStepBody() assumes homogeneous tuples where first and second components can be swapped. For `(string * int)`, the join `z = z2` compares int with int (correct) but tuple reconstruction may be wrong.

**Location**: `/Users/hal.hildebrand/git/morel/src/main/java/net/hydromatic/morel/compile/PredicateInverter.java:1714-1745`

**Likelihood**: LOW - Most transitive closures use homogeneous types.

**Impact**: LOW - Limited use case. Can be documented as limitation.

**Mitigation Strategy**:
1. Document homogeneous tuple requirement
2. Add type check and clear error message for heterogeneous tuples
3. Or implement proper type tracking for heterogeneous case

**Estimated Fix Time**: 30 minutes (document) or 4+ hours (implement)

**Testing**: Test with `(string * int)` edges.

---

### FM-12: Relational.iterate Bag/List Resolution Fails

**Category**: Type System

**Symptom**: Type error selecting between bag and list overloads. Error: "Ambiguous overload resolution".

**Root Cause**: `core.functionLiteral(typeSystem, BuiltIn.RELATIONAL_ITERATE)` returns MultiType. Without explicit type hint, resolution may fail if base generator type is ambiguous.

**Location**: `/Users/hal.hildebrand/git/morel/src/main/java/net/hydromatic/morel/compile/PredicateInverter.java:556`

**Likelihood**: LOW - Generator type should be clearly bag or list.

**Impact**: HIGH - Cannot construct iterate expression.

**Mitigation Strategy**:
1. Explicitly check baseGenerator.type to determine bag vs list
2. Use overload-resolving functionLiteral variant
3. Add type trace to identify resolution issue

**Estimated Fix Time**: 30-60 minutes

**Testing**: Verify generated code uses correct bag/list variant.

---

### FM-13: Circular References Cause Stack Overflow

**Category**: Recursion Handling

**Symptom**: StackOverflowError during pattern detection or inversion.

**Root Cause**: Mutually recursive functions or indirect recursion not detected, causing infinite recursion in PredicateInverter analysis.

**Location**: `/Users/hal.hildebrand/git/morel/src/main/java/net/hydromatic/morel/compile/PredicateInverter.java` (recursionStack handling)

**Likelihood**: LOW - recursionStack exists to prevent this.

**Impact**: MEDIUM - Crashes compiler. Recoverable with proper recursion detection.

**Mitigation Strategy**:
1. Verify recursionStack is properly maintained
2. Add depth limit as safety net
3. Add test for mutually recursive functions

**Estimated Fix Time**: 30-60 minutes

**Testing**: Test with mutually recursive path/reach functions.

---

### FM-14: Triple+ Tuple Arities Rejected Silently

**Category**: Tuple Handling

**Symptom**: Transitive closure on 3+ tuple elements silently returns wrong results or INFINITE.

**Root Cause**: buildStepBody() handles only 1-2 element tuples (lines 1714-1721). Triple tuples not explicitly handled, may decompose incorrectly.

**Location**: `/Users/hal.hildebrand/git/morel/src/main/java/net/hydromatic/morel/compile/PredicateInverter.java:1714-1721`

**Likelihood**: MEDIUM - Some graph patterns use 3-tuples (edge with weight).

**Impact**: LOW - Can be documented as limitation. Workaround: flatten to nested pairs.

**Mitigation Strategy**:
1. Document binary tuple requirement
2. Add explicit check and error for n>2 tuples
3. Or extend implementation for arbitrary arities

**Estimated Fix Time**: 15 minutes (document) or 4+ hours (implement)

**Testing**: Test with `(int * int * int)` edges.

---

## Testing Strategy by Failure Mode

### Pre-Integration Tests (Before g3->g3b Fix)

| FM | Test Description | Expected Outcome |
|----|------------------|------------------|
| FM-01 | Verify g3 is called with trace | Trace shows g3, not g3b |
| FM-02 | Run such-that.smli | "infinite: int * int" error |

### Post-Integration Tests (After g3->g3b Fix)

| FM | Test Description | Expected Outcome |
|----|------------------|------------------|
| FM-01 | Verify g3b trace appears | PredicateInverter debug in stderr |
| FM-02 | Check cardinality trace | FINITE for invertible base |
| FM-03 | Check type trace at line 565 | No ClassCastException |
| FM-04 | Check binding.value trace | Non-null Core.Exp |
| FM-05 | Check lambda signature trace | (bag * bag) -> bag |
| FM-06 | Test heterogeneous tuples | Type error or correct handling |
| FM-07 | Check FROM step trace | 2 scans, 1 where, 1 yield |
| FM-08 | Test pattern variations | Detection or clear rejection |

### Edge Case Tests

| FM | Test Description | Expected Outcome |
|----|------------------|------------------|
| FM-09 | `val edges = []` | Empty result, no crash |
| FM-10 | `val edges = [{x=1,y=1}]` | Terminates with [(1,1)] |
| FM-11 | `(string * int)` edges | Error message or correct result |
| FM-12 | Explicit bag vs list | Correct overload selected |
| FM-13 | Mutual recursion | Stack limit or handled |
| FM-14 | `(int * int * int)` edges | Error message or limitation doc |

---

## Recommended Monitoring Checkpoints

### Checkpoint 1: Integration Activation (Phase 5a Start)
Monitor FM-01. Verify g3b is called and PredicateInverter receives control.

**Verification**:
```bash
./mvnw test -Dtest=ScriptTest 2>&1 | grep "PredicateInverter"
```

### Checkpoint 2: Cardinality Boundary (Phase 5a Mid)
Monitor FM-02. Verify base case produces FINITE cardinality.

**Verification**: Trace at line 523 shows "Base case cardinality: FINITE"

### Checkpoint 3: Type Construction (Phase 5a Late)
Monitor FM-03, FM-05, FM-12. Verify types are correct through construction chain.

**Verification**: No ClassCastException, lambda signature matches expected.

### Checkpoint 4: FROM Expression (Phase 5d Early)
Monitor FM-07. Verify FROM has correct structure.

**Verification**: Trace shows 4 steps: scan, scan, where, yield.

### Checkpoint 5: End-to-End Output (Phase 5d Late)
Monitor FM-06, FM-08. Verify output correctness.

**Verification**: Output is `[(1,2),(2,3),(1,3)]` exactly.

---

## Contingency Budget Allocation

| Priority | Failure Modes | Budget (hours) | Notes |
|----------|---------------|----------------|-------|
| P0 | FM-01, FM-02 | 4 | Critical path, must fix |
| P1 | FM-03, FM-04, FM-05 | 4 | Type system fixes |
| P2 | FM-06, FM-07, FM-08, FM-10, FM-12 | 3 | Secondary issues |
| P3 | FM-09, FM-11, FM-13, FM-14 | 1 | Edge cases, document |
| **Total** | | **12 hours** | 50% contingency over 8h estimate |

### Budget Escalation Rules

1. **P0 issues exceed 4 hours**: Escalate to NO-GO evaluation
2. **P1 issues exceed 4 hours**: Reduce P2/P3 scope, document limitations
3. **P2 issues exceed 3 hours**: Document as known limitations for v1
4. **Total exceeds 12 hours**: Re-evaluate Phase 5 scope with stakeholders

---

## Summary

This analysis identifies 14 specific failure modes that could occur during Phase 5a validation or Phase 5d implementation. The two highest-risk items are:

1. **FM-01 (g3->g3b integration)**: Known blocker, 5-minute fix
2. **FM-02 (INFINITE cardinality)**: Core technical challenge of Phase 5d

Once FM-01 is fixed, FM-02 will manifest and become the primary focus. The type system issues (FM-03, FM-05, FM-12) will then become visible and addressable.

The testing strategy provides specific verification steps for each failure mode. The contingency budget allocates 12 hours total, with 50% over the base 8-hour estimate to handle unexpected issues.

**Recommended Immediate Actions**:
1. Fix FM-01 (g3->g3b) to activate integration
2. Add traces for FM-02, FM-03, FM-04, FM-05 simultaneously
3. Run validation to identify which failure modes are active
4. Address in priority order based on actual manifestation

---

**Document Version**: 1.0
**Status**: COMPLETE
**Next Action**: Present to Phase 5 plan refinement for integration
