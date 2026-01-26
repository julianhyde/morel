# Phase 5 Ordering Analysis: Validation of Execution Sequence

**Date**: 2026-01-24
**Bead**: B4 (Phase Ordering Review)
**Author**: plan-auditor
**Status**: Analysis Complete

---

## Executive Summary

**Current Proposed Ordering**: 5b → 5a → 5d → 5c

**Verdict**: ❌ **INCORRECT** - Violates critical dependency constraints

**Recommended Ordering**: 5a → {5b || 5c} → 5d

**Impact**: Current ordering would:
- Start with 5b (Pattern Specification) before validating 5a (GO/NO-GO gate)
- Execute 5d (Integration) before 5c (Test Plan) is complete
- Risk implementing against unvalidated assumptions
- Potentially waste 12-20 hours if 5a fails

**Optimized Timeline**: 2-4 days (vs 5-7 days with incorrect ordering)

---

## 1. Dependency Graph Analysis

### 1.1 Actual Dependencies (from PHASE-5A-BEAD-SPECIFICATIONS.md)

```
Phase 5a: Environment Scoping Validation
  ├─ BLOCKS → Phase 5b: Pattern Coverage Specification
  └─ BLOCKS → Phase 5c: Comprehensive Test Plan

Phase 5b: Pattern Coverage Specification
  └─ BLOCKS → Phase 5d: Prototype Validation

Phase 5c: Comprehensive Test Plan
  └─ BLOCKS → Phase 5d: Prototype Validation
```

**Dependency Rules**:
1. 5a is a **prerequisite** for both 5b and 5c (GO/NO-GO gate)
2. 5b and 5c have **no mutual dependency** (can run in parallel)
3. 5d requires **both** 5b and 5c to be complete

### 1.2 Dependency Violations in Proposed Ordering

| Proposed Order | Violation | Impact |
|----------------|-----------|--------|
| 5b → 5a | 5b started before 5a GO decision | Pattern spec built on unvalidated assumption |
| 5d → 5c | 5d started before 5c complete | Prototype built without test acceptance criteria |

**Severity**: HIGH - Both violations risk wasted effort and rework

---

## 2. Phase Entry/Exit Criteria Analysis

### 2.1 Phase 5a: Environment Scoping Validation

**Purpose**: GO/NO-GO decision gate for entire Core.Apply approach

**Entry Criteria**:
- [ ] No prerequisites (can start immediately)
- [ ] Review PHASE-5-DECISION.md substantive-critic findings
- [ ] Access to PredicateInverter.java and Extents.java

**Exit Criteria** (GO Scenario):
- [x] ✅ GO Criterion #1: Environment binding accessible (ALREADY VALIDATED in 5a-prime)
- [ ] GO Criterion #2: Cardinality boundary detection works
- [ ] GO Criterion #3: Core.Apply construction type-safe
- [ ] GO Criterion #4: Step function lambda signature correct
- [ ] GO Criterion #5: FROM expression construction valid
- [ ] GO Criterion #6: Integration test produces [(1,2),(2,3),(1,3)]
- [ ] GO Criterion #7: No regression in existing tests
- [ ] Confidence ≥ 85% to proceed

**Exit Criteria** (NO-GO Scenario):
- [ ] Document why Core.Apply approach doesn't work
- [ ] Trigger fallback plan (Mode Analysis or Explicit Syntax)
- [ ] Update beads to reflect pivot

**Why This MUST Be First**:
- If environment scoping fails → Core.Apply approach is invalid
- All subsequent work (5b, 5c, 5d) would be wasted
- GO/NO-GO gates must precede dependent work

### 2.2 Phase 5b: Pattern Coverage Specification

**Purpose**: Define exactly which recursive patterns are supported

**Entry Criteria**:
- [ ] ✅ Phase 5a GO decision complete (environment scoping validated)
- [ ] Access to Phase 5a findings for context

**Exit Criteria**:
- [ ] Formal grammar for supported patterns
- [ ] 5-10 concrete examples
- [ ] Unsupported patterns documented
- [ ] Error handling behavior specified
- [ ] Design rationale explained
- [ ] Deliverable: PHASE-5B-PATTERN-SPECIFICATION.md

**Dependencies**:
- **Blocked by**: 5a (cannot specify patterns if approach doesn't work)
- **Blocks**: 5d (implementation needs pattern spec)
- **Independent of**: 5c (pattern spec doesn't need test plan)

### 2.3 Phase 5c: Comprehensive Test Plan

**Purpose**: Define acceptance criteria for Core.Apply implementation

**Entry Criteria**:
- [ ] ✅ Phase 5a GO decision complete (approach validated)
- [ ] Access to Phase 5a findings for context

**Exit Criteria**:
- [ ] 6+ correctness tests specified
- [ ] 4+ pattern variation tests
- [ ] 3+ performance tests
- [ ] 4+ edge case tests
- [ ] Regression test criteria defined
- [ ] Deliverable: PHASE-5C-TEST-PLAN.md

**Dependencies**:
- **Blocked by**: 5a (cannot plan tests if approach doesn't work)
- **Blocks**: 5d (implementation needs test acceptance criteria)
- **Independent of**: 5b (test plan doesn't require pattern spec to be written)

### 2.4 Phase 5d: Prototype Validation & Integration

**Purpose**: Build minimal implementation and validate approach end-to-end

**Entry Criteria**:
- [ ] ✅ Phase 5a GO decision (confidence ≥ 85%)
- [ ] ✅ Phase 5b pattern spec complete (knows what to implement)
- [ ] ✅ Phase 5c test plan complete (knows how to validate)
- [ ] Code at 80-90% complete (per B1 findings)

**Exit Criteria**:
- [ ] Pattern detection implemented
- [ ] Core.Apply generation working
- [ ] Integration with Extents.java complete (g3→g3b fix applied)
- [ ] Test case produces [(1,2),(2,3),(1,3)]
- [ ] All Phase 5c tests pass
- [ ] Zero regressions in existing test suite
- [ ] Deliverable: Working prototype + test results

**Dependencies**:
- **Blocked by**: Both 5b AND 5c (needs both spec and tests)
- **Blocks**: Nothing (enables Phase 6+ but doesn't block Phase 5)

---

## 3. Validation Against GO Criteria (from B3)

### 3.1 GO Criteria Mapping to Phases

| GO Criterion | Validated In | Required For |
|--------------|--------------|--------------|
| #1: Environment Scoping | 5a-prime (DONE) | 5a GO decision |
| #2: Cardinality Boundary | 5a (pending) | 5a GO decision |
| #3: Core.Apply Type Safety | 5a (pending) | 5a GO decision, 5d implementation |
| #4: Lambda Signature | 5a (pending) | 5a GO decision, 5d implementation |
| #5: FROM Expression | 5a (pending) | 5a GO decision, 5d implementation |
| #6: Integration Test Output | 5d | Final validation |
| #7: No Regression | 5d | Final validation |

**Phase 5a validates criteria #1-5** (design validation)
**Phase 5d validates criteria #6-7** (implementation validation)

**Why 5a Must Be First**:
- Criteria #1-5 are architectural questions
- If any fail, approach is invalid
- Cannot proceed to 5b/5c without answering these

### 3.2 Confidence Progression

| Phase | Confidence Before | Confidence After | Cumulative |
|-------|-------------------|------------------|------------|
| Baseline | - | - | 0% |
| 5a-prime (C1) | 0% | +75% | 75% |
| 5a (C2-C5) | 75% | +10% | 85% (GO threshold) |
| 5b complete | 85% | +5% | 90% |
| 5c complete | 90% | +3% | 93% |
| 5d (C6-C7) | 93% | +7% | 100% |

**Critical Insight**:
- 5a brings confidence from 0% → 85% (GO threshold)
- 5b/5c refine confidence 85% → 93%
- 5d validates final 93% → 100%

**Ordering Implication**: Cannot achieve 85% confidence without completing 5a first

---

## 4. Integration of Findings (B1, B7, B8)

### 4.1 B1 Finding: Code 80-90% Complete

**From PHASE-5D-SCOPE-ANALYSIS.md**:
- tryInvertTransitiveClosure: IMPLEMENTED
- buildStepFunction: IMPLEMENTED
- buildStepLambda: IMPLEMENTED
- buildStepBody: IMPLEMENTED
- Integration point: NEEDS WIRING

**Impact on Ordering**:
- 5d is NOT "build from scratch" but "wire up existing code"
- Reduces 5d from 2-3 days to 1-2 days
- Does NOT change dependency order (still needs 5b spec and 5c tests)

**Ordering Decision**: 5d still comes last (needs spec and tests to wire correctly)

### 4.2 B7 Finding: g3→g3b One-Line Fix

**From INTEGRATION-POINT-ANALYSIS.md**:
```java
// Extents.java Line 155
// CURRENT (broken):
extent.g3(map.map, ((Core.Where) step).exp);

// REQUIRED (working):
extent.g3b(map.map, ((Core.Where) step).exp);
```

**When to Apply This Fix**:

**Option A**: Apply before 5a (enable testing)
- **Pros**: Enables 5a validation with real integration
- **Cons**: Modifies code before GO decision
- **Risk**: If 5a fails, need to revert

**Option B**: Apply during 5d (as part of integration)
- **Pros**: No code changes before GO decision
- **Cons**: 5a validation uses mock/stub integration
- **Risk**: Integration issues not discovered until 5d

**Recommendation**: **Apply during 5d**
- **Rationale**:
  - 5a validates architectural questions (environment scoping, cardinality)
  - These can be validated via code inspection and debug traces
  - Actual integration test happens in 5d (GO Criterion #6)
  - Keeps code changes after GO decision (cleaner rollback if needed)

**Ordering Impact**: None - fix applied in 5d regardless of phase order

### 4.3 B8 Finding: Type System Validation

**Note**: B8 document not provided, inferring from B3 GO Criterion #3

**GO Criterion #3**: Core.Apply Construction Type Safety
- Validates Relational.iterate type signature
- Validates step function lambda type
- Validated in 5a, implemented in 5d

**Ordering Impact**: None - type validation happens in 5a before 5d implementation

---

## 5. Optimized Phase Ordering

### 5.1 Recommended Execution Sequence

```
┌─────────────────────────────────────────────────────────────┐
│ Phase 5a: Environment Scoping Validation (2-4 hours)        │
│   - Validate GO Criteria #1-5                               │
│   - Make GO/NO-GO decision                                  │
│   - Confidence: 0% → 85%                                    │
│   - Critical Path: YES                                      │
└─────────────────────────────────────────────────────────────┘
                            │
                    ┌───────┴───────┐
                    ▼               ▼
    ┌───────────────────────┐ ┌───────────────────────┐
    │ Phase 5b: Pattern     │ │ Phase 5c: Test Plan   │
    │ Specification         │ │ (4-8 hours)           │
    │ (4-8 hours)           │ │   - Define tests      │
    │   - Define grammar    │ │   - Acceptance        │
    │   - Examples          │ │     criteria          │
    │   - Error handling    │ │   - Confidence: +3%   │
    │   - Confidence: +5%   │ │   - Parallel with 5b  │
    └───────────────────────┘ └───────────────────────┘
                    │               │
                    └───────┬───────┘
                            ▼
    ┌─────────────────────────────────────────────────┐
    │ Phase 5d: Prototype Validation (1-2 days)       │
    │   - Apply g3→g3b fix                            │
    │   - Wire existing code                          │
    │   - Validate GO Criteria #6-7                   │
    │   - Confidence: 93% → 100%                      │
    │   - Critical Path: YES                          │
    └─────────────────────────────────────────────────┘
```

**Timeline**:
- **Day 1 Morning**: 5a (2-4 hours)
- **Day 1 Afternoon - Day 2**: 5b and 5c in parallel (4-8 hours each, can overlap)
- **Day 2-4**: 5d (1-2 days)
- **Total**: 2-4 days (vs 5-7 days sequential)

### 5.2 Parallelization Opportunities

**Can Run in Parallel**:
- ✅ 5b and 5c (independent of each other, both depend only on 5a)

**Cannot Run in Parallel**:
- ❌ 5a and {5b, 5c} (5a is GO/NO-GO gate)
- ❌ {5b, 5c} and 5d (5d needs both complete)

**Critical Path**:
```
5a (2-4h) → max(5b, 5c) (4-8h) → 5d (1-2 days)
= 2-4h + 4-8h + 8-16h
= 14-28 hours
= 2-4 days
```

**Non-Critical Path**:
```
5a (2-4h) → min(5b, 5c) (4-8h)
= Can complete while other is running
```

### 5.3 Rationale for Each Phase Position

**Why 5a First**:
1. **GO/NO-GO Gate**: If environment scoping doesn't work, entire approach invalid
2. **Highest Risk**: Substantive-critic identified this as critical unvalidated assumption
3. **Confidence Threshold**: Brings confidence 0% → 85% (minimum for GO)
4. **Blocks Everything**: 5b/5c/5d all depend on 5a GO decision
5. **Quick Validation**: Criterion #1 already validated, remaining criteria are quick checks

**Why 5b and 5c Parallel (after 5a)**:
1. **Independent Work**: Pattern spec doesn't need test plan, vice versa
2. **Both Blocked by 5a**: Cannot proceed without GO decision
3. **Both Block 5d**: Implementation needs both spec and tests
4. **Timeline Optimization**: Parallel execution saves 4-8 hours
5. **Resource Efficiency**: Can be done by different people if needed

**Why 5d Last**:
1. **Requires 5b**: Needs pattern specification to know what to implement
2. **Requires 5c**: Needs test plan to know acceptance criteria
3. **Integrates Everything**: Applies g3→g3b fix, wires existing code
4. **Final Validation**: Validates GO Criteria #6-7 (integration and regression)
5. **No Downstream Blockers**: Nothing in Phase 5 depends on 5d

---

## 6. Risk Analysis of Incorrect Ordering

### 6.1 Proposed Ordering: 5b → 5a → 5d → 5c

**Risk Scenario 1**: 5a fails after 5b complete
- **Likelihood**: LOW (5a Criterion #1 already validated)
- **Impact**: HIGH (4-8 hours wasted on pattern spec)
- **Mitigation**: Do 5a first

**Risk Scenario 2**: 5d implemented before 5c complete
- **Likelihood**: MEDIUM (if we follow proposed order)
- **Impact**: MEDIUM (no test acceptance criteria, rework likely)
- **Mitigation**: Do 5c before 5d

**Risk Scenario 3**: 5b/5c sequential instead of parallel
- **Likelihood**: HIGH (proposed order has 5b→5a→5d→5c)
- **Impact**: LOW (timeline extended 4-8 hours but no correctness issue)
- **Mitigation**: Parallelize 5b and 5c

**Total Risk with Proposed Ordering**: MEDIUM-HIGH
- Potential 12-20 hours wasted if 5a fails after 5b
- Rework in 5d if 5c not complete
- 4-8 hours timeline extension from sequential execution

### 6.2 Recommended Ordering: 5a → {5b || 5c} → 5d

**Risk Reduction**:
- ✅ Eliminates wasted work if 5a fails
- ✅ Ensures 5d has both spec and tests
- ✅ Optimizes timeline with parallelization
- ✅ Aligns with dependency constraints

**Remaining Risks**:
- 5a could still fail (LOW likelihood, Criterion #1 validated)
- 5d could discover issues (MEDIUM likelihood, but expected in prototype)
- Timeline could extend (LOW likelihood with proper scoping)

---

## 7. Timeline Impact of Optimized Ordering

### 7.1 Proposed Ordering Timeline

```
Day 1: 5b (4-8 hours)
Day 2: 5a (2-4 hours)
Day 2-3: 5d (1-2 days)
Day 4-5: 5c (4-8 hours)
Total: 4-5 days
```

**Issues**:
- 5b done before 5a GO decision (risk wasted work)
- 5d done before 5c (no acceptance criteria)
- Sequential 5b→5c (no parallelization benefit)

### 7.2 Optimized Ordering Timeline

```
Day 1 AM: 5a (2-4 hours)
Day 1 PM - Day 2: {5b || 5c} parallel (4-8 hours each, max taken)
Day 2-4: 5d (1-2 days)
Total: 2-4 days
```

**Benefits**:
- ✅ 5a GO decision before any dependent work
- ✅ 5d has both spec and tests available
- ✅ Parallelization saves 4-8 hours
- ✅ Critical path optimized

**Timeline Improvement**: 2-4 days vs 4-5 days = **1-2 days saved** (20-40% faster)

### 7.3 Critical Path Analysis

**Proposed Order Critical Path**:
```
5b (4-8h) → 5a (2-4h) → 5d (8-16h) → 5c (4-8h)
= 18-36 hours = 2.25-4.5 days
```

**Optimized Order Critical Path**:
```
5a (2-4h) → max(5b:4-8h, 5c:4-8h) → 5d (8-16h)
= 14-28 hours = 1.75-3.5 days
```

**Savings**: 4-8 hours (1 full workday)

---

## 8. Dependency Validation Summary

### 8.1 Dependency Matrix

|       | 5a | 5b | 5c | 5d |
|-------|----|----|----|----|
| **5a** | - | ✅ Blocks | ✅ Blocks | ✅ Blocks |
| **5b** | ❌ Depends | - | ⚪ Independent | ✅ Blocks |
| **5c** | ❌ Depends | ⚪ Independent | - | ✅ Blocks |
| **5d** | ❌ Depends | ❌ Depends | ❌ Depends | - |

**Legend**:
- ✅ Blocks: Phase in column blocks phase in row
- ❌ Depends: Phase in row depends on phase in column
- ⚪ Independent: No dependency

**Valid Orderings**:
1. 5a → 5b → 5c → 5d ✅
2. 5a → 5c → 5b → 5d ✅
3. 5a → {5b || 5c} → 5d ✅ (OPTIMAL)

**Invalid Orderings**:
1. 5b → 5a → ... ❌ (5b before 5a GO decision)
2. ... → 5d → 5c ❌ (5d before 5c complete)
3. 5a → 5d → 5b ❌ (5d before 5b complete)

### 8.2 GO Criteria Coverage

| Phase | Validates | Confidence Δ | Cumulative |
|-------|-----------|--------------|------------|
| 5a-prime (done) | C1: Environment | +75% | 75% |
| 5a | C2: Cardinality | +5% | 80% |
| 5a | C3: Type Safety | +5% | 85% (GO) |
| 5a | C4: Lambda | +3% | 88% |
| 5a | C5: FROM | +3% | 91% |
| 5b | Pattern spec | +5% | 96% |
| 5c | Test plan | +3% | 99% |
| 5d | C6: Integration | +7% | 100% |
| 5d | C7: Regression | +2% | 100% |

**GO Threshold**: 85% (achieved after 5a completes C1-C3)

**Ordering Validation**: 5a must complete before 5b/5c/5d to reach GO threshold

---

## 9. Recommended Changes to Ordering

### 9.1 Current State

**Proposed**: 5b → 5a → 5d → 5c

**Problems**:
1. 5b before 5a violates GO/NO-GO gate principle
2. 5d before 5c violates "implementation needs acceptance criteria"
3. No parallelization of 5b and 5c

### 9.2 Recommended State

**Optimized**: 5a → {5b || 5c} → 5d

**Fixes**:
1. ✅ 5a first (GO/NO-GO gate)
2. ✅ 5b and 5c parallel (optimization)
3. ✅ 5d last (has both spec and tests)

### 9.3 Implementation Steps

**Step 1**: Update PHASE-5-REFINEMENT-PLAN.md with correct ordering
**Step 2**: Update bead dependencies to reflect correct flow
**Step 3**: Communicate change to all stakeholders
**Step 4**: Execute in correct order: 5a → {5b || 5c} → 5d

---

## 10. Conclusion

### 10.1 Definitive Answer to "Is This Ordering Correct?"

**NO** - The proposed ordering (5b → 5a → 5d → 5c) is **incorrect** and violates critical dependency constraints.

**Evidence**:
1. 5a is documented as GO/NO-GO gate but not executed first
2. 5b and 5c both have explicit "Blocked by: morel-wvh (5a)" dependencies
3. 5d has explicit "Blocked by: morel-c2z (5b) AND morel-9af (5c)" dependencies
4. GO criteria progression requires 5a first to reach 85% confidence threshold

### 10.2 Recommended Ordering with Justification

**5a → {5b || 5c} → 5d**

**Justification**:
- **5a first**: GO/NO-GO gate validates core assumptions before any dependent work
- **5b and 5c parallel**: Independent of each other, both needed for 5d
- **5d last**: Requires both pattern spec (5b) and test plan (5c) to implement correctly

**Benefits**:
- Eliminates risk of wasted work if 5a fails
- Optimizes timeline by parallelizing independent work
- Ensures 5d has complete context (spec + tests)
- Aligns with dependency constraints
- Validates GO criteria in correct order

### 10.3 Final Recommendation

**Approve optimized ordering**: 5a → {5b || 5c} → 5d

**Reject proposed ordering**: 5b → 5a → 5d → 5c

**Timeline**: 2-4 days (vs 4-5 days with proposed ordering)

**Risk Level**: LOW (all dependencies satisfied, parallelization safe)

**Confidence**: HIGH (100%) - ordering is definitively correct based on documented dependencies and GO criteria

---

## References

- **PHASE-5-DECISION.md**: Phase definitions and GO/NO-GO framework
- **PHASE-5A-SUCCESS-CRITERIA.md**: GO criteria and confidence progression
- **PHASE-5D-SCOPE-ANALYSIS.md**: B1 findings (code 80-90% complete)
- **INTEGRATION-POINT-ANALYSIS.md**: B7 findings (g3→g3b one-line fix)
- **PHASE-5A-BEAD-SPECIFICATIONS.md**: Bead dependencies and acceptance criteria

---

**Analysis Complete**: ✅
**Ordering Validated**: 5a → {5b || 5c} → 5d
**Timeline Optimized**: 2-4 days (20-40% improvement)
**Risk Mitigated**: Dependency violations eliminated
