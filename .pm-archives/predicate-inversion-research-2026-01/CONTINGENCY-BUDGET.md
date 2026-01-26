# Contingency Budget Allocation: Phase 5 PredicateInverter Implementation

**Bead**: morel-a25 (B5 Contingency Budget)
**Date**: 2026-01-24
**Status**: COMPLETE
**Depends On**: B2 (Failure Mode Analysis), B1 (Scope Analysis), B7 (Integration Point Analysis)

---

## Executive Summary

This document allocates contingency budget across 14 identified failure modes for Phase 5 implementation. The total contingency budget of **16 hours** provides a 100% buffer over the optimistic 8-hour base estimate, accounting for the empirical reality that debugging and integration work typically encounters unexpected issues.

**Budget Allocation by Priority**:
- P0 (Critical): 3.5 hours (22%)
- P1 (High): 5.5 hours (34%)
- P2 (Medium): 4.5 hours (28%)
- P3 (Low): 1.5 hours (9%)
- Unallocated Reserve: 1.0 hour (6%)

---

## 1. Budget Framework

### 1.1 Total Budget Determination

Based on evidence from B1 and B7:

| Source | Estimate | Confidence |
|--------|----------|------------|
| B1 Scope Analysis | 5-8 hours validation | HIGH |
| B7 Integration Analysis | 2.6 hours integration | HIGH |
| B2 Failure Mode Analysis | 12 hours (50% contingency) | MEDIUM |

**Total Budget Calculation**:
- Optimistic base: 8 hours (all goes smoothly)
- Realistic base: 12 hours (B2 estimate)
- Pessimistic cap: 16 hours (100% contingency)

**Adopted Budget**: **16 hours** with escalation at 12 hours

**Justification**: Phase 5 involves debugging code that hasn't been runtime-tested. Historical data suggests debugging takes 2-3x longer than expected. The 16-hour budget provides sufficient margin while maintaining pressure for efficiency.

### 1.2 Allocation Strategy

Budget is allocated using **weighted priority distribution**:

```
Weight Formula: Priority_Budget = (Risk_Score_Sum / Total_Risk_Score) * Available_Budget * Priority_Multiplier

Priority Multipliers:
- P0: 1.5x (critical issues get premium)
- P1: 1.2x (high priority premium)
- P2: 0.8x (reduced allocation)
- P3: 0.5x (minimal allocation)
```

**Risk Score Distribution**:
- P0: 18 points (FM-01: 9, FM-02: 9)
- P1: 18 points (FM-03: 6, FM-04: 6, FM-05: 6)
- P2: 16 points (FM-06: 4, FM-07: 3, FM-08: 3, FM-10: 3, FM-12: 3)
- P3: 7 points (FM-09: 2, FM-11: 1, FM-13: 2, FM-14: 2)
- **Total**: 59 points

### 1.3 Risk Level Categories

**SMALL** (0-0.5 hours):
- Definition: Single-line fixes, simple reordering, configuration changes
- Examples: FM-01 (g3->g3b), FM-06 (tuple index), FM-14 (document limitation)
- Activation: Automatic - always budget

**MEDIUM** (0.5-2 hours):
- Definition: Multi-line fixes, logic adjustments, new null checks
- Examples: FM-03 (MultiType cast), FM-04 (null check), FM-07 (FROM steps)
- Activation: When failure detected, check P0+P1 budget

**LARGE** (2+ hours):
- Definition: Approach changes, significant rewrites, algorithm modifications
- Examples: FM-02 (cardinality approach), FM-05 (lambda signature), FM-08 (pattern detection)
- Activation: Only when P0+P1 budget allows, else escalate

### 1.4 Contingency Reserves

| Reserve Type | Amount | Trigger |
|--------------|--------|---------|
| **P0 Buffer** | 0.5h | If FM-01 or FM-02 exceeds estimate |
| **P1 Buffer** | 0.5h | Type system complications |
| **Unknown Issues** | 1.0h | Failure modes not in B2 analysis |
| **Total Reserve** | **2.0h** | Included in 16h budget |

---

## 2. Priority-Based Allocation

### 2.1 P0 (Critical) - Must Fix for Any Progress

These failure modes block all downstream work. They must be fixed before any P1/P2/P3 work can begin.

| Failure Mode | Risk Score | Base Fix | Worst Case | Allocated | Detection Strategy |
|--------------|------------|----------|------------|-----------|-------------------|
| FM-01: g3 called instead of g3b | 9/10 | 0.1h | 0.5h | **0.2h** | No PredicateInverter trace in stderr |
| FM-02: Base case INFINITE cardinality | 9/10 | 2.0h | 4.0h | **3.0h** | "Base case cardinality: INFINITE" in trace |
| **Subtotal P0** | - | **2.1h** | **4.5h** | **3.2h** | |
| **P0 Buffer** | - | - | - | **0.3h** | Critical path protection |
| **Total P0** | - | - | - | **3.5h** | 22% of budget |

**Evidence from Prior Analysis**:
- B7 confirms FM-01 is a single-line fix (line 155 of Extents.java)
- B1 confirms FM-02 is the "real blocker" requiring Core.Apply approach refinement
- B8 runtime trace showed "infinite: int * int" manifestation of FM-02

**Remediation Strategies**:

**FM-01** (g3 -> g3b):
1. Change line 155: `extent.g3(...)` to `extent.g3b(...)`
2. Run baseline tests to verify no regression
3. Confirm PredicateInverter debug output appears in stderr

**FM-02** (Cardinality FINITE):
1. Verify `edges` binding is accessible in environment (Phase 5a-prime confirmed)
2. Check `invert()` method recognizes bound collection variables
3. Add special handling for `elem` pattern when RHS is known bound variable
4. If still INFINITE, investigate Core.Apply deferred evaluation approach
5. Validate base case produces FINITE before proceeding

### 2.2 P1 (High) - Required for Correct Output

These failures become visible after P0 is fixed. They prevent correct code generation but allow partial progress.

| Failure Mode | Risk Score | Base Fix | Worst Case | Allocated | Detection Strategy |
|--------------|------------|----------|------------|-----------|-------------------|
| FM-03: MultiType ClassCastException | 6/10 | 0.5h | 1.5h | **1.0h** | ClassCastException at line 556 |
| FM-04: Binding.value is null | 6/10 | 0.25h | 2.0h | **1.5h** | NPE after binding lookup |
| FM-05: Lambda signature mismatch | 6/10 | 1.0h | 2.0h | **1.5h** | Type error in Core.Apply |
| **Subtotal P1** | - | **1.75h** | **5.5h** | **4.0h** | |
| **P1 Buffer** | - | - | - | **1.5h** | Type system complications |
| **Total P1** | - | - | - | **5.5h** | 34% of budget |

**Evidence from Prior Analysis**:
- B8 identified MultiType resolution as key challenge for Relational.iterate
- B2 documented that FM-04 has been partially validated (binding accessible, value untested)
- B1 shows lambda construction exists (lines 1649-1683) but unvalidated at runtime

**Remediation Strategies**:

**FM-03** (MultiType ClassCastException):
1. Use `core.functionLiteral(typeSystem, BuiltIn.RELATIONAL_ITERATE, ImmutableList.of(baseGenerator, stepFunction))` to resolve overload
2. Or manually select bag variant before calling apply()
3. Add type trace at line 565 to verify no ClassCastException

**FM-04** (Binding.value null):
1. Add explicit null check: `if (binding.value == null) return null;`
2. Trace what type binding.value actually is
3. If null, verify Compiles.java stores Core.Exp values in bindings
4. If environment issue, may require 1-2 hours to fix compilation pipeline

**FM-05** (Lambda signature mismatch):
1. Add explicit type annotations at each construction step
2. Verify paramType is (bag, bag) tuple, not nested
3. Verify fnType.resultType matches bagType
4. Add assertion: `assert fnType.paramType.equals(paramType);`

### 2.3 P2 (Medium) - Needed for Robust Implementation

These failures affect correctness in specific cases but don't block basic functionality.

| Failure Mode | Risk Score | Base Fix | Worst Case | Allocated | Detection Strategy |
|--------------|------------|----------|------------|-----------|-------------------|
| FM-06: z/z2 type mismatch | 4/10 | 0.25h | 0.5h | **0.3h** | Type error on WHERE clause |
| FM-07: FROM missing scan steps | 3/10 | 0.5h | 1.0h | **0.7h** | Wrong FROM structure in trace |
| FM-08: Pattern not detected | 3/10 | 2.0h | 4.0h | **1.5h** | "Pattern not recognized" log |
| FM-10: Self-loops infinite | 3/10 | 1.0h | 2.0h | **1.0h** | Test hangs on self-loop |
| FM-12: Bag/list resolution | 3/10 | 0.5h | 1.0h | **0.5h** | Ambiguous overload error |
| **Subtotal P2** | - | **4.25h** | **8.5h** | **4.0h** | |
| **P2 Buffer** | - | - | - | **0.5h** | Secondary issues |
| **Total P2** | - | - | - | **4.5h** | 28% of budget |

**Evidence from Prior Analysis**:
- B1 shows tuple decomposition code exists (lines 1714-1721) but may have index error
- B2 documents FM-08 as "legitimate patterns rejected" - lower risk since standard pattern should match
- FM-10 depends on Relational.iterate fixpoint detection in Codes.java

**Remediation Strategies**:

**FM-06** (z/z2 type mismatch):
- Change line 1737: use `baseComponents[1]` for z2Pat (same type as z)
- Or document homogeneous tuple requirement

**FM-07** (FROM missing steps):
- Add step count verification after build()
- Assert FROM has exactly 2 scans, 1 where, 1 yield
- Add trace for each step added to builder

**FM-08** (Pattern not detected):
- Add pattern detection logging
- Document expected pattern structure
- Expand pattern matching if variations needed (defer to v2 if > 2h)

**FM-10** (Self-loops infinite):
- Verify Relational.iterate uses value equality for fixpoint
- Add timeout/iteration limit test
- If Codes.java needs fixing, escalate

**FM-12** (Bag/list resolution):
- Check baseGenerator.type to determine bag vs list
- Use overload-resolving functionLiteral variant
- Add type trace to identify resolution issue

### 2.4 P3 (Low) - Edge Cases and Documentation

These failures are edge cases or can be documented as limitations for v1.

| Failure Mode | Risk Score | Base Fix | Worst Case | Allocated | Detection Strategy |
|--------------|------------|----------|------------|-----------|-------------------|
| FM-09: Empty edges NPE | 2/10 | 0.25h | 0.5h | **0.3h** | NPE with `edges = []` |
| FM-11: Heterogeneous tuples | 1/10 | 0.5h | 4.0h | **0.3h** | Type error on (string*int) |
| FM-13: Circular ref overflow | 2/10 | 0.5h | 1.0h | **0.5h** | StackOverflowError |
| FM-14: Triple+ tuples rejected | 2/10 | 0.25h | 4.0h | **0.3h** | Wrong results for 3-tuples |
| **Subtotal P3** | - | **1.5h** | **9.5h** | **1.4h** | |
| **P3 Buffer** | - | - | - | **0.1h** | Edge cases |
| **Total P3** | - | - | - | **1.5h** | 9% of budget |

**Strategy**: For FM-11 and FM-14, the "fix" allocation is for documentation, not implementation. If full implementation is required, escalate to scope change.

**Remediation Strategies**:

**FM-09** (Empty edges NPE):
- Add empty collection test case
- Verify Relational.iterate handles empty base
- Add guard clause if needed

**FM-11** (Heterogeneous tuples):
- Document homogeneous tuple requirement (0.3h)
- Full implementation deferred to v2 (4+ hours)

**FM-13** (Circular ref overflow):
- Verify recursionStack is properly maintained
- Add depth limit as safety net

**FM-14** (Triple+ tuples):
- Document binary tuple requirement (0.3h)
- Full implementation deferred to v2 (4+ hours)

---

## 3. Dependency Impact Analysis

### 3.1 Critical Path Analysis

The dependency chain identified in B2 creates a strict execution order:

```
FM-01 (g3->g3b fix)
   |
   v (blocks)
FM-02 (cardinality FINITE)
   |
   v (blocks)
{FM-03, FM-05} (type errors - can run in parallel)
   |
   v (blocks)
{FM-04, FM-06, FM-07} (secondary issues - parallel)
   |
   v (blocks)
{FM-08-FM-14} (edge cases - parallel)
```

**Critical Path Time**:
```
FM-01 (0.2h) + FM-02 (3.0h) + max(FM-03, FM-05) (1.5h) = 4.7 hours minimum
```

**Implication**: Even if all other fixes take zero time, the critical path requires at least 4.7 hours. This is our theoretical minimum.

### 3.2 Parallel Opportunities

Once FM-02 is resolved, several failure modes can be debugged in parallel:

**Parallel Set 1** (after FM-01):
- FM-02 investigation can proceed

**Parallel Set 2** (after FM-02):
- FM-03 and FM-05 are independent type issues
- Can be investigated simultaneously by examining different code paths

**Parallel Set 3** (after FM-03/FM-05):
- FM-04, FM-06, FM-07, FM-12 are independent
- All can be investigated once basic code generation works

**Parallel Set 4** (edge cases):
- FM-09, FM-10, FM-11, FM-13, FM-14 are independent
- Can be tested with dedicated test cases

**Maximum Parallelism Benefit**: ~2-3 hours saved if resources allow parallel investigation

### 3.3 Time Cascading Analysis

**Scenario 1: FM-01 takes 0.5h instead of 0.2h**
- Impact: +0.3h to critical path
- Slack remaining: 16h - 4.7h - 0.3h = 11.0h
- Status: GREEN - no schedule risk

**Scenario 2: FM-02 takes 4.0h instead of 3.0h (worst case)**
- Impact: +1.0h to critical path
- Slack remaining: 16h - 4.7h - 1.0h = 10.3h
- Status: GREEN - no schedule risk

**Scenario 3: FM-02 takes 6.0h (severe issue)**
- Impact: +3.0h to critical path
- Slack remaining: 16h - 4.7h - 3.0h = 8.3h
- Status: YELLOW - reduced margin
- Action: Reduce P2/P3 scope

**Scenario 4: FM-02 requires approach change (8.0h+)**
- Impact: +5.0h+ to critical path
- Slack remaining: 16h - 4.7h - 5.0h = 6.3h
- Status: RED - escalate to NO-GO evaluation
- Action: Re-evaluate Phase 5 scope with stakeholders

---

## 4. Budget Allocation Summary

### 4.1 Total Allocation by Priority

| Priority | # Modes | Base Fix | Worst Case | Allocated | % of Budget | Utilization |
|----------|---------|----------|------------|-----------|-------------|-------------|
| P0 | 2 | 2.1h | 4.5h | 3.5h | 22% | 78% of worst |
| P1 | 3 | 1.75h | 5.5h | 5.5h | 34% | 100% of worst |
| P2 | 5 | 4.25h | 8.5h | 4.5h | 28% | 53% of worst |
| P3 | 4 | 1.5h | 9.5h | 1.5h | 9% | 16% of worst |
| Reserve | - | - | - | 1.0h | 6% | N/A |
| **TOTAL** | **14** | **9.6h** | **28.0h** | **16.0h** | **100%** | **57% of worst** |

### 4.2 Budget Efficiency Metrics

- **P0+P1 Allocation**: 9.0h (56% of budget) - critical path protection
- **P2+P3 Allocation**: 6.0h (38% of budget) - robustness margin
- **Reserve**: 1.0h (6% of budget) - unknown issues

### 4.3 Budget Utilization Targets

| Metric | Target | Escalation Threshold |
|--------|--------|---------------------|
| P0 Time | <= 3.5h | > 4.5h |
| P1 Time | <= 5.5h | > 7.0h |
| P0+P1 Time | <= 9.0h | > 12.0h |
| Total Time | <= 16.0h | > 16.0h |

---

## 5. Monitoring and Escalation

### 5.1 Five Monitoring Checkpoints

**Checkpoint 1: Integration Activation** (After FM-01 fix)
- **Timing**: Within first 30 minutes
- **Verification**: PredicateInverter trace appears in stderr
- **Command**: `./mvnw test -Dtest=ScriptTest 2>&1 | grep "PredicateInverter"`
- **Pass Criteria**: Debug output from lines 126-148 visible
- **Failure Action**: Verify g3b is called, check environment passed

**Checkpoint 2: Cardinality Boundary** (FM-02 status)
- **Timing**: 1-2 hours into Phase 5a
- **Verification**: Base case cardinality is FINITE
- **Trace**: Line 523 shows "Base case cardinality: FINITE"
- **Pass Criteria**: FINITE cardinality for `edge(x,y)` base case
- **Failure Action**: Investigate Core.Apply approach, check environment bindings

**Checkpoint 3: Type Construction** (FM-03/FM-05 status)
- **Timing**: 2-4 hours into Phase 5a
- **Verification**: No ClassCastException, lambda signature correct
- **Trace**: Line 565 type trace, line 1682 lambda signature
- **Pass Criteria**: Types resolve correctly through construction chain
- **Failure Action**: Check MultiType resolution, verify (bag,bag)->bag signature

**Checkpoint 4: FROM Expression** (FM-07 status)
- **Timing**: 4-6 hours into Phase 5d
- **Verification**: FROM has correct structure (2 scans, 1 where, 1 yield)
- **Trace**: Line 1783 step sequence
- **Pass Criteria**: 4 steps in correct order
- **Failure Action**: Debug FromBuilder construction, check pattern creation

**Checkpoint 5: End-to-End Output** (Final validation)
- **Timing**: 6-8 hours into Phase 5d
- **Verification**: Output is `[(1,2),(2,3),(1,3)]` exactly
- **Command**: `./mvnw test -Dtest=ScriptTest`
- **Pass Criteria**: such-that.smli test passes
- **Failure Action**: Debug output differences, check edge cases

### 5.2 Escalation Criteria

**Level 1: Budget Warning** (Yellow)
- Trigger: Any P0 failure takes > 1.5x allocated time
- Action: Notify stakeholder, continue with reduced P2/P3 scope
- Authority: Engineer

**Level 2: Budget Alert** (Orange)
- Trigger: P0+P1 combined exceeds 9.0 hours
- Action: Pause work, scope review meeting
- Authority: Tech lead

**Level 3: Budget Exceeded** (Red)
- Trigger: Total time exceeds 12 hours
- Action: NO-GO evaluation, stakeholder decision required
- Authority: Project manager

**Level 4: Critical Escalation** (Black)
- Trigger: Fundamental approach is broken (FM-02 unfixable)
- Action: Phase 5 halt, architectural review
- Authority: Engineering leadership

---

## 6. Activation Strategy

### 6.1 SMALL Fixes (0-30 minutes)

**Definition**: Self-contained fixes with no cascading impact

**Activation**: Automatic - budget always available

**Examples**:
- FM-01: g3->g3b single-line change
- FM-06: Tuple index fix (baseComponents[1])
- FM-14: Documentation of limitation

**Process**:
1. Identify failure mode from trace/error
2. Locate fix in code (reference B2 analysis)
3. Apply fix
4. Run relevant tests
5. Proceed to next failure mode

### 6.2 MEDIUM Fixes (30 min - 2 hours)

**Definition**: Multi-line fixes requiring investigation

**Activation**: When failure detected, verify P0+P1 budget has slack

**Examples**:
- FM-03: MultiType resolution
- FM-04: Null check + environment investigation
- FM-07: FROM step debugging

**Process**:
1. Identify failure mode from trace/error
2. Check remaining budget (P0+P1 should have > 2h slack)
3. Investigate root cause
4. Implement fix
5. Run comprehensive tests
6. Update time tracking

### 6.3 LARGE Fixes (2+ hours)

**Definition**: Significant changes requiring approach modification

**Activation**: Only when P0+P1 budget allows, else escalate

**Examples**:
- FM-02: Cardinality approach change
- FM-05: Lambda signature restructure
- FM-08: Pattern detection expansion

**Process**:
1. Identify that fix requires > 2 hours
2. Check P0+P1 budget status
3. If budget allows: proceed with fix, track closely
4. If budget tight: escalate for scope decision
5. Document approach change
6. Run full test suite
7. Update estimates for remaining work

### 6.4 Decision Matrix

| Remaining P0+P1 Budget | SMALL Fix | MEDIUM Fix | LARGE Fix |
|------------------------|-----------|------------|-----------|
| > 6 hours | Proceed | Proceed | Proceed |
| 4-6 hours | Proceed | Proceed | Escalate first |
| 2-4 hours | Proceed | Escalate first | Defer to v2 |
| < 2 hours | Proceed | Defer to v2 | Defer to v2 |

---

## 7. References and Evidence

### 7.1 Source Documents

| Document | Location | Key Content |
|----------|----------|-------------|
| B2: Failure Mode Analysis | `.pm/FAILURE-MODE-ANALYSIS.md` | Risk scores, priority levels, 14 failure modes |
| B1: Scope Analysis | `.pm/PHASE-5D-SCOPE-ANALYSIS.md` | Code completeness (80-90%), time estimates |
| B7: Integration Point Analysis | `.pm/INTEGRATION-POINT-ANALYSIS.md` | g3->g3b fix details, 2.6h estimate |
| B8: Type System Analysis | `.pm/TYPE-SYSTEM-ANALYSIS.md` | MultiType resolution, lambda signatures |

### 7.2 Evidence Trail

**Time Estimate Sources**:
- FM-01 (0.1h): B7 line 274 "Fix call from g3 to g3b: 0.1h"
- FM-02 (2-4h): B2 line 80 "Estimated Fix Time: 2-4 hours"
- FM-03 (0.5-1h): B2 line 105 "Estimated Fix Time: 30-60 minutes"
- FM-04 (0.25-2h): B2 line 130 "15-30 minutes (investigation), 1-2 hours if environment needs fixing"
- FM-05 (1-2h): B2 line 156 "Estimated Fix Time: 1-2 hours"

**Risk Score Methodology**:
- Likelihood: HIGH=3, MEDIUM=2, LOW=1
- Impact: CRITICAL=3, HIGH=2, MEDIUM=1.5, LOW=1
- Risk Score = Likelihood x Impact

### 7.3 Assumptions

1. **Environment is accessible**: Phase 5a-prime GO confirms this (B1 Section 4)
2. **g3b is superset of g3**: B7 documents this (line 279)
3. **Implementation is 80-90% complete**: B1 executive summary
4. **Type system is consistent**: No compilation errors in existing code

### 7.4 Change Log

| Version | Date | Changes |
|---------|------|---------|
| 1.0 | 2026-01-24 | Initial contingency budget allocation |

---

## Summary

This contingency budget allocates **16 hours** across 14 failure modes with a clear priority structure. The critical path (FM-01 -> FM-02 -> FM-03/FM-05) requires approximately **4.7 hours minimum**, leaving **11.3 hours** for debugging, P2/P3 issues, and unknown problems.

Key metrics:
- **P0+P1 budget**: 9.0 hours (56%) - protects critical path
- **P2+P3 budget**: 6.0 hours (38%) - ensures robustness
- **Reserve**: 1.0 hour (6%) - unknown issues
- **Escalation threshold**: 12 hours

The five monitoring checkpoints provide early warning for budget issues, and the escalation criteria ensure timely decision-making if the budget is exceeded.

---

**Document Version**: 1.0
**Status**: COMPLETE
**Next Action**: Use during Phase 5a-5d execution for budget tracking
