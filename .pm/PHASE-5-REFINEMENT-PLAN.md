# Path C: Full Plan Refinement - Comprehensive Execution Plan

**Date**: 2026-01-24
**Purpose**: Execute full plan refinement addressing all 14 audit issues
**Status**: READY FOR EXECUTION
**Target Outcome**: 85%+ confidence plan with second audit validation

---

## Executive Summary

This plan executes Path C (Full Plan Refinement) to address all 14 critical issues identified in the Phase 5 Decision Plan Audit. The work is structured in three phases:

1. **Phase 5a-prime** (30-60 min): Quick empirical test - CRITICAL GATE
2. **Full Refinement** (8-12 hours): Address all audit issues with analysis documents
3. **Second Audit** (2-4 hours): Validate refined plan achieves 85%+ confidence

### Timeline Summary

| Phase | Duration | Days |
|-------|----------|------|
| Phase 5a-prime | 30-60 min | Day 1 AM |
| Full Refinement | 8-12 hours | Day 1-2 |
| Second Audit | 2-4 hours | Day 2-3 |
| **Total Refinement** | **12-18 hours** | **2-3 days** |

After refinement completes successfully:
| Phase | Duration | Days |
|-------|----------|------|
| Phase 5b | 6-10 hours | Day 3-4 |
| Phase 5a | 11-15 hours | Day 4-6 |
| Phase 5d | 4-6 days | Day 6-12 |
| Phase 5c | 1.5-2.5 days | Day 12-15 |
| **Total Phase 5** | **7-11 days** | **~2 weeks** |

---

## Phase 1: Phase 5a-prime Quick Empirical Test

### Purpose
Validate the core technical assumption empirically before investing in full refinement. This is the CRITICAL GATE that determines whether Core.Apply approach is viable.

### Duration
30-60 minutes

### Bead
**morel-5ap**: Phase 5a-prime Quick Empirical Test

### Actions

#### Step 1: Add Debug Logging (5 min)

Add temporary debug output to `PredicateInverter.java` constructor:

```java
// In PredicateInverter constructor (line ~91):
PredicateInverter(TypeSystem typeSystem, Environment env) {
  this.typeSystem = requireNonNull(typeSystem);
  this.env = requireNonNull(env);

  // DEBUG: Log environment bindings
  System.err.println("=== PredicateInverter Environment ===");
  env.forEachBinding((name, binding) -> {
    System.err.println("  " + name + ": " + binding.value.type);
  });
  System.err.println("=====================================");
}
```

#### Step 2: Run Test (10 min)

```bash
./mvnw test -Dtest=ScriptTest -Dmorel.script=such-that.smli 2>&1 | tee /tmp/5a-prime-output.txt
```

Or more specifically:
```bash
./mvnw test -Dtest=ScriptTest#testSuchThat
```

#### Step 3: Analyze Output (15 min)

Check stderr for the environment dump.

**Expected output if GO:**
```
=== PredicateInverter Environment ===
  edges: (int * int) list
  edge: int * int -> bool
  path: int * int -> bool
  ...
=====================================
```

**Expected output if INVESTIGATE:**
```
=== PredicateInverter Environment ===
  edge: int * int -> bool
  path: int * int -> bool
  (edges NOT present)
=====================================
```

**Expected output if NO-GO:**
- No "PredicateInverter Environment" output at all
- Error during compilation
- PredicateInverter never called

### Decision Tree

```
                    Phase 5a-prime Result
                           |
          +----------------+----------------+
          |                |                |
         GO            INVESTIGATE        NO-GO
          |                |                |
    edges present    edges missing     Not called
          |          but fixable           |
          v                |                v
    Proceed to          Add 1-2d       STOP Core.Apply
    refinement         investigation       |
          |                |                v
          v                v          Pivot to
    [PHASE 2]     Fix then continue   Explicit Syntax
                   [PHASE 2]          [morel-xsr critical]
```

### Success Criteria

**GO (proceed immediately):**
- [ ] PredicateInverter debug output shows `edges` binding
- [ ] Type is correct: `(int * int) list`
- [ ] No compilation errors

**INVESTIGATE (proceed with investigation):**
- [ ] PredicateInverter is called (debug output appears)
- [ ] Environment exists but `edges` missing
- [ ] Issue appears fixable (environment propagation)
- Add 1-2 days budget for investigation

**NO-GO (pivot to fallback):**
- [ ] PredicateInverter not called
- [ ] Fundamental architecture issue
- [ ] Fix estimated > 4 days
- Pivot to Explicit Syntax research

### Deliverable

File: `.pm/PHASE-5A-PRIME-RESULT.md`

Contents:
- Debug output captured
- Decision: GO / INVESTIGATE / NO-GO
- Evidence supporting decision
- Next steps

---

## Phase 2: Full Refinement

**Prerequisite**: Phase 5a-prime result is GO or INVESTIGATE

### Overview

| Task ID | Task | Duration | Deliverable |
|---------|------|----------|-------------|
| B1 | Scope Analysis | 30 min | PHASE-5D-SCOPE-ANALYSIS.md |
| B2 | Failure Mode Analysis | 2-3h | FAILURE-MODE-ANALYSIS.md |
| B3 | GO/NO-GO Criteria | 1h | PHASE-5A-SUCCESS-CRITERIA.md |
| B4 | Phase Ordering Review | 1.5h | PHASE-ORDERING-JUSTIFICATION.md |
| B5 | Contingency Budget | 1h | CONTINGENCY-BUDGET.md |
| B6 | Explicit Syntax Research | 1-2d | EXPLICIT-SYNTAX-DESIGN.md |
| B7 | Integration Analysis | 1-2h | INTEGRATION-POINT-ANALYSIS.md |
| B8 | Type System Analysis | 1-2h | TYPE-SYSTEM-ANALYSIS.md |

### Execution Order

```
Day 1:
  5a-prime (30 min) ──GO/INVESTIGATE──> B1 (30 min)
                                          │
                                          v
  B6 starts ─────────────────────────> B2 (2-3h)
  (parallel, 1-2 days)                    │
                                          v
                                       B3 (1h)
                                          │
                                          v
                                       B4 (1.5h)
                                          │
                                          v
                                       B5 (1h)

Day 2:
  B6 continues ────────────────────────>
                                          │
                                          v
                                       B7 (1-2h)
                                          │
                                          v
                                       B8 (1-2h)
                                          │
                                          v
                                    Update Beads
                                          │
                                          v
                                    [PHASE 3: Second Audit]
```

---

### Task B1: Phase 5d Scope Analysis

**Bead**: morel-sco

**Purpose**: Clarify what already exists in PredicateInverter vs what needs building

**Duration**: 30 minutes

**Actions**:
1. Read PredicateInverter.java lines 464-555
2. Document `tryInvertTransitiveClosure()` method capabilities
3. Check Core.Apply generation code (lines 527-541)
4. Check `buildStepFunction()` implementation (lines 1596-1760)
5. Identify gaps between existing code and requirements

**Key Questions**:
- Does pattern detection work?
- Does Core.Apply generation produce correct IR?
- Is step function construction complete?
- What's missing for integration with Extents.java?

**Deliverable**: `.pm/PHASE-5D-SCOPE-ANALYSIS.md`

**Template**:
```markdown
# Phase 5d Scope Analysis

## Existing Implementation

### tryInvertTransitiveClosure() (lines 464-555)
- Status: [COMPLETE/PARTIAL/MISSING]
- What it does: [description]
- What's missing: [gaps]

### Pattern Detection (lines 470-482)
- Status: [COMPLETE/PARTIAL/MISSING]
- Detects: [patterns]
- Limitations: [gaps]

### Core.Apply Generation (lines 527-541)
- Status: [COMPLETE/PARTIAL/MISSING]
- Generates: [what]
- Verified: [yes/no]

### buildStepFunction() (lines 1596-1760)
- Status: [COMPLETE/PARTIAL/MISSING]
- Creates: [what]
- Known issues: [if any]

## Integration Work Required
1. [task 1]
2. [task 2]

## Revised Phase 5d Estimate
- Original: 2-3 days
- Revised: [X] days
- Rationale: [explanation]

## Conclusion
Phase 5d is [greenfield/debug-and-integrate/mostly-complete]
```

---

### Task B2: Failure Mode Analysis

**Bead**: morel-fma

**Purpose**: Identify potential failure modes and create risk register with mitigations

**Duration**: 2-3 hours

**Actions**:
1. Review substantive-critic findings
2. Identify 10-15 potential failure modes
3. Assess likelihood and impact for each
4. Define mitigation strategies
5. Create risk register

**Failure Mode Categories**:
1. Environment scoping failures
2. Type system integration issues
3. Pattern matching brittleness
4. Step function generation bugs
5. Integration conflicts with Extents.java
6. Performance issues
7. Timeline overruns
8. Test case coverage gaps

**Deliverable**: `.pm/FAILURE-MODE-ANALYSIS.md`

**Template**:
```markdown
# Failure Mode Analysis

## Risk Register

| ID | Failure Mode | Likelihood | Impact | Detection | Mitigation | Contingency |
|----|--------------|------------|--------|-----------|------------|-------------|
| FM-1 | Environment scoping fails | 30% | Critical | Phase 5a-prime | Quick test | Pivot to explicit syntax |
| FM-2 | Type system mismatch | 20% | High | Phase 5a.3 | Type validation | Fix types (+1-2d) |
| FM-3 | Pattern too brittle | 40% | Medium | Phase 5b | Spec patterns | Expand patterns (+1-2d) |
| ... | ... | ... | ... | ... | ... | ... |

## Expected Failures
Based on likelihood analysis, expect 1-2 issues to occur during Phase 5.

## Contingency Time
Add 3-5 days buffer to baseline estimate.

## Mitigation Strategies

### For Critical Risks
[strategies]

### For High Risks
[strategies]

### For Medium Risks
[strategies]
```

---

### Task B3: GO/NO-GO Criteria Refinement

**Bead**: morel-gnc

**Purpose**: Define precise, measurable success criteria for Phase 5a validation

**Duration**: 1 hour

**Actions**:
1. Review current vague criteria
2. Define specific, measurable thresholds
3. Create decision tree for each scenario
4. Document evidence requirements

**Deliverable**: `.pm/PHASE-5A-SUCCESS-CRITERIA.md`

**Template**:
```markdown
# Phase 5a Success Criteria

## GO Criteria (ALL must be true)
- [ ] PredicateInverter environment contains `edges` binding
- [ ] Type is correct: `(int * int) list`
- [ ] env.getOpt("edges") returns non-null
- [ ] No compilation errors when referencing `edges`
- [ ] Can build Core.Apply with `edges` reference

## PARTIAL-GO Criteria (fixable issues)
- [ ] Environment available but `edges` filtered out
- [ ] Fix is identifiable and scoped
- [ ] Fix estimated at < 2 days
- [ ] Risk is acceptable

## NO-GO Criteria (fundamental failure)
- [ ] Environment not available at PredicateInverter level
- [ ] Architecture doesn't support variable access
- [ ] Fix estimated at > 4 days OR requires major rework

## Decision Tree
[flowchart showing decision logic]

## Evidence Requirements
For each criterion, specify:
- What evidence proves it?
- How is it collected?
- Who validates it?

## Contingency Time by Decision
- GO: 0 additional days
- PARTIAL-GO: +1-2 days
- NO-GO: Pivot to fallback (+3-5 days research)
```

---

### Task B4: Phase Ordering Review

**Bead**: morel-por

**Purpose**: Evaluate and justify phase ordering based on dependencies

**Duration**: 1.5 hours

**Actions**:
1. Review current order: 5a -> {5b, 5c} -> 5d
2. Evaluate audit recommendation: 5b -> 5a -> 5d -> 5c
3. Analyze dependencies
4. Make decision with rationale
5. Update bead dependencies

**Audit Recommendation**:
```
5b (Pattern Spec)
  | defines what to validate
  v
5a (Environment Validation)
  | proves approach works
  v
5d (Prototype)
  | provides implementation to test
  v
5c (Test Execution)
```

**Deliverable**: `.pm/PHASE-ORDERING-JUSTIFICATION.md`

**Template**:
```markdown
# Phase Ordering Justification

## Current Order
5a -> {5b, 5c parallel} -> 5d

## Audit Recommendation
5b -> 5a -> 5d -> 5c

## Dependency Analysis

### 5a depends on:
- [analysis]

### 5b depends on:
- [analysis]

### 5c depends on:
- [analysis]

### 5d depends on:
- [analysis]

## Decision
[ACCEPT/REJECT/MODIFY] audit recommendation

## Rationale
[explanation]

## Final Order
[sequence]

## Bead Dependency Updates
- morel-wvh: [changes]
- morel-c2z: [changes]
- morel-9af: [changes]
- morel-aga: [changes]
```

---

### Task B5: Contingency Budget Definition

**Bead**: morel-ctg

**Purpose**: Define fix budgets and escalation thresholds

**Duration**: 1 hour

**Actions**:
1. Define SMALL/MEDIUM/LARGE fix categories
2. Specify time budgets for each
3. Define escalation criteria
4. Document pivot triggers

**Deliverable**: `.pm/CONTINGENCY-BUDGET.md`

**Template**:
```markdown
# Contingency Budget Definition

## Fix Categories

### SMALL FIX (< 2 hours)
- Scope: Minor adjustments
- Examples:
  - Environment binding adjustment
  - Small code path fix
  - Configuration change
- Action: Fix and continue
- Budget: Built into timeline

### MEDIUM FIX (2-4 hours)
- Scope: Moderate code changes
- Examples:
  - SuchThatShuttle modification
  - Type annotation additions
  - Integration point adjustment
- Action: Assess risk, then fix
- Budget: Add 0.5-1 day to timeline

### LARGE FIX (> 4 hours)
- Scope: Significant rework
- Examples:
  - Architecture change
  - Multiple component modifications
  - Fundamental approach change
- Action: Escalate and reassess
- Budget: Trigger fallback evaluation

## Escalation Criteria
- Time spent > 4 hours on single issue
- 2+ MEDIUM fixes in same phase
- Unexpected failure mode discovered

## Pivot Triggers
- LARGE FIX required for environment scoping
- Total contingency budget exceeded
- Second audit confidence < 70%
```

---

### Task B6: Explicit Syntax Research

**Bead**: morel-xsr

**Purpose**: Research WITH RECURSIVE syntax as viable fallback option

**Duration**: 1-2 days (runs in parallel)

**Actions**:
1. Design syntax extension
2. Estimate implementation complexity
3. Compare to Core.Apply approach
4. Assess SML compatibility impact

**Deliverable**: `.pm/EXPLICIT-SYNTAX-DESIGN.md`

**Template**:
```markdown
# Explicit Syntax Design: WITH RECURSIVE

## Proposed Syntax

```sml
with recursive path(x, y) as (
  (* Base case *)
  edge(x, y)
  union
  (* Recursive case *)
  from (x, z) in edges,
       (z2, y) in path
  where z = z2
  yield (x, y)
)
from p in path
```

## Design Rationale
[explanation]

## Advantages
- No environment scoping needed
- No mode analysis needed
- Direct translation to Relational.iterate
- SQL-like syntax (familiar)
- Clear semantics

## Disadvantages
- Language syntax change required
- Not transparent
- Less powerful than automatic inversion

## Implementation Complexity

### Parser Changes
- [scope]

### Core IR Changes
- [scope]

### Type System Changes
- [scope]

### Estimated Effort
- [X] days

## Comparison to Core.Apply

| Aspect | Core.Apply | Explicit Syntax |
|--------|------------|-----------------|
| Implementation effort | X days | Y days |
| User experience | Transparent | Explicit |
| Scope | Transitive closure | Recursive queries |
| Risk | [level] | [level] |

## Recommendation
[recommendation]
```

---

### Task B7: Integration Point Analysis

**Bead**: morel-ipa

**Purpose**: Validate where and how PredicateInverter integrates with Extents.java

**Duration**: 1-2 hours

**Actions**:
1. Find exact integration point in Extents.java
2. Verify environment and TypeSystem availability
3. Document integration approach
4. Estimate integration complexity

**Deliverable**: `.pm/INTEGRATION-POINT-ANALYSIS.md`

**Template**:
```markdown
# Integration Point Analysis

## Current State

### Extents.java Structure
- [overview]

### Where PredicateInverter Should Be Called
- Line: [number]
- Context: [description]
- Current behavior: [what happens now]

## Integration Approach

### Code to Add
```java
case ID:  // User-defined function call
  PredicateInverter inverter = new PredicateInverter(typeSystem, env);
  Optional<PredicateInverter.InversionResult> result =
      inverter.invert(apply, goalPats, boundPats);
  if (result.isPresent()) {
    // Use inverted generator
  }
  break;
```

### Environment Availability
- Source: [where it comes from]
- Verified: [yes/no]
- Issues: [if any]

### TypeSystem Availability
- Source: [where it comes from]
- Verified: [yes/no]
- Issues: [if any]

## Complexity Assessment
- Estimated: [X] hours
- Risk: [level]
- Dependencies: [list]
```

---

### Task B8: Type System Analysis

**Bead**: morel-tsa

**Purpose**: Understand how generated Core.Apply nodes interact with type system

**Duration**: 1-2 hours

**Actions**:
1. Analyze Core.Apply type requirements
2. Document how type inference handles generated code
3. Identify potential type errors
4. Document type system interaction

**Deliverable**: `.pm/TYPE-SYSTEM-ANALYSIS.md`

**Template**:
```markdown
# Type System Analysis

## Core.Apply Type Requirements

### Relational.iterate Signature
```
'a bag -> ('a bag * 'a bag -> 'a bag) -> 'a bag
```

### Type Constraints
- Base generator: `'a bag`
- Step function: `('a bag * 'a bag) -> 'a bag`
- Result: `'a bag`

## Generated Code Types

### Base Generator
- Expression: [what's generated]
- Expected type: [type]
- Actual type: [type]
- Issues: [if any]

### Step Function
- Expression: [what's generated]
- Expected type: [type]
- Actual type: [type]
- Issues: [if any]

## Type Inference Interaction

### How Inference Works
[explanation]

### Potential Issues
1. [issue 1]
2. [issue 2]

## Type Error Scenarios

### Scenario 1: [description]
- Cause: [cause]
- Error message: [message]
- Fix: [fix]

## Recommendations
[recommendations]
```

---

## Phase 3: Second Audit

**Bead**: morel-au2

**Purpose**: Validate refined plan achieves 85%+ confidence

**Duration**: 2-4 hours

### Prerequisite
All refinement tasks (B1-B8) complete

### Actions

1. **Compile Refinement Documents**
   - Gather all 8 deliverables
   - Verify completeness
   - Create summary document

2. **Spawn plan-auditor Agent**
   ```
   Skill: plan-audit
   Target: Refined Phase 5 Plan
   Focus: All 14 original issues addressed?
   ```

3. **Audit Scope**
   - Verify all 14 issues addressed
   - Assess risk management quality
   - Evaluate contingency planning
   - Review quality gates
   - Confirm fallback viability

4. **Target Metrics**
   - Overall confidence: >= 85%
   - Strategic direction: SOUND
   - Tactical execution: WELL-PLANNED
   - Risk management: COMPREHENSIVE
   - Go/no-go recommendation: CLEAR

### Deliverable

File: `.pm/PHASE-5-DECISION-AUDIT-2.md`

**Template**:
```markdown
# Phase 5 Decision Plan Audit Report (Second Audit)

**Date**: [date]
**Auditor**: plan-auditor agent
**Plan Audited**: Refined Phase 5 Plan
**Audit Status**: COMPLETE

## Executive Summary

### Audit Verdict
[PASS/CONDITIONAL PASS/FAIL]

### Confidence Assessment
| Metric | First Audit | Second Audit | Target | Status |
|--------|-------------|--------------|--------|--------|
| Overall confidence | 60% | [X]% | 85% | [PASS/FAIL] |
| Strategic soundness | Medium | [level] | High | [PASS/FAIL] |
| Tactical execution | Low | [level] | High | [PASS/FAIL] |
| Risk management | Minimal | [level] | Comprehensive | [PASS/FAIL] |

## Issue Resolution Status

| Issue # | Description | Status | Evidence |
|---------|-------------|--------|----------|
| 1 | Documentation-focused validation | [RESOLVED/PARTIAL/OPEN] | [doc] |
| 2 | GO/NO-GO criteria vague | [RESOLVED/PARTIAL/OPEN] | [doc] |
| ... | ... | ... | ... |
| 14 | Quality gates insufficient | [RESOLVED/PARTIAL/OPEN] | [doc] |

## Recommendation
[GO/CONDITIONAL GO/NO-GO]

## Remaining Concerns
[if any]
```

---

## Updated Bead Dependencies

After refinement, update Phase 5 beads:

### Current Dependencies
```
morel-wvh (5a)
  -> morel-c2z (5b)
  -> morel-9af (5c)
     -> morel-aga (5d)
```

### Updated Dependencies (after B4 analysis)

**If audit recommendation accepted (5b -> 5a -> 5d -> 5c):**
```
morel-5ap (5a-prime)
  -> morel-rfn (refinement epic)
     -> morel-au2 (second audit)
        -> morel-c2z (5b)
           -> morel-wvh (5a expanded)
              -> morel-aga (5d)
                 -> morel-9af (5c)
```

---

## Success Criteria for Path C

### Plan Quality
- [ ] All 14 audit issues have documented resolution
- [ ] Failure modes identified with mitigations (10+ modes)
- [ ] GO/NO-GO criteria are measurable and clear
- [ ] Phase ordering justified with dependencies
- [ ] Contingency budget defined with thresholds
- [ ] Fallback option researched (not just mentioned)
- [ ] Quality gates are comprehensive
- [ ] Timeline realistic with buffers (30% contingency)

### Second Audit Verdict
- [ ] Overall confidence: >= 85%
- [ ] Strategic direction: SOUND
- [ ] Tactical execution: WELL-PLANNED
- [ ] Risk management: COMPREHENSIVE
- [ ] Go/no-go recommendation: CLEAR

### Ready for Execution
- [ ] All beads created with dependencies
- [ ] All deliverables complete
- [ ] Second audit passed
- [ ] User approval received

---

## Quick Reference: Bead IDs

| Bead ID | Task | Type | Priority |
|---------|------|------|----------|
| morel-5ap | Phase 5a-prime Quick Test | task | P0 |
| morel-rfn | Phase 5 Full Refinement | epic | P0 |
| morel-sco | Scope Analysis (B1) | task | P1 |
| morel-fma | Failure Mode Analysis (B2) | task | P1 |
| morel-gnc | GO/NO-GO Criteria (B3) | task | P1 |
| morel-por | Phase Ordering Review (B4) | task | P1 |
| morel-ctg | Contingency Budget (B5) | task | P1 |
| morel-xsr | Explicit Syntax Research (B6) | task | P1 |
| morel-ipa | Integration Analysis (B7) | task | P1 |
| morel-tsa | Type System Analysis (B8) | task | P1 |
| morel-au2 | Second Audit Request | task | P0 |

---

## Execution Commands

### Start Phase 5a-prime
```bash
bd update morel-5ap --status=in_progress
# Execute quick test
# Document result
bd close morel-5ap --reason="GO: edges binding verified"
```

### Start Refinement
```bash
bd update morel-rfn --status=in_progress
# Execute B1-B8 in order
# Create all deliverables
bd close morel-rfn --reason="All refinements complete"
```

### Request Second Audit
```bash
bd update morel-au2 --status=in_progress
# Spawn plan-auditor
# Review results
bd close morel-au2 --reason="Audit passed: 87% confidence"
```

---

**Document Status**: READY FOR EXECUTION
**Next Action**: Create beads and execute Phase 5a-prime
