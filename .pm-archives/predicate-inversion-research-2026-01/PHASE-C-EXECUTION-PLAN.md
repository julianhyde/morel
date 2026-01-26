# Phase C: Full Plan Refinement - Execution Plan

**Date**: 2026-01-24
**Author**: java-architect-planner
**Status**: READY FOR EXECUTION
**Target**: 85%+ confidence through comprehensive plan refinement

---

## Executive Summary

This document provides a comprehensive execution plan for generating all 8 refinement documents (B1-B8) required to address the 14 audit issues identified in `PHASE-5-DECISION-AUDIT.md`.

### Critical Discovery

**The codebase is substantially more complete than the audit suggests.** Analysis of `PredicateInverter.java` reveals:

1. **tryInvertTransitiveClosure exists** (lines 488-579) with:
   - Pattern detection (lines 495-505)
   - Base case inversion (line 508)
   - Cardinality boundary check (lines 523-526)
   - TabulationResult construction (lines 539-543)
   - VarEnvironment setup (line 546)
   - buildStepFunction call (line 549)
   - Relational.iterate construction (lines 556-565)

2. **Integration with Extents.java exists** (lines 707-758), not line 538

3. **Phase 5a-prime debug code exists** (lines 126-148)

4. **The fundamental blocker is understood**: The cardinality boundary problem documented in ChromaDB (`analysis::morel::transitive-closure-cardinality-mismatch`)

**Implication**: Phase 5d is "debug and integrate existing code" not "build from scratch."

---

## Task Specifications

### B1: Scope Analysis (morel-px9)

**Bead**: morel-px9
**Duration**: 30 minutes
**Priority**: P0 (FIRST - informs all other tasks)

**Purpose**: Document exactly what code exists vs. what needs building to set realistic expectations.

**Research Checklist**:
- [ ] Read PredicateInverter.java:443-600 (tryInvertTransitiveClosure)
- [ ] Read PredicateInverter.java:1596-1760 (buildStepFunction suite)
- [ ] Verify pattern detection code (lines 495-505)
- [ ] Analyze cardinality boundary logic (lines 523-526)
- [ ] Read ChromaDB doc: `analysis::morel::transitive-closure-cardinality-mismatch`
- [ ] Check buildStepLambda and buildStepBody implementations

**Key Questions to Answer**:
1. Does tryInvertTransitiveClosure detect the orelse pattern correctly?
2. Why does the cardinality check fail (lines 523-526)?
3. What does buildStepFunction produce when called?
4. Is the Relational.iterate construction correct (lines 556-565)?
5. What specific gaps exist?

**Deliverable**: `.pm/PHASE-5D-SCOPE-ANALYSIS.md`

**Quality Criteria**:
- [ ] Lists all existing methods with line numbers
- [ ] Documents what each method does
- [ ] Identifies specific gaps (not vague "needs work")
- [ ] Explains the cardinality boundary problem
- [ ] Provides revised time estimate with rationale
- [ ] Conclusion: Likely "debug-and-integrate" not "greenfield"

---

### B2: Failure Mode Analysis (morel-m4a)

**Bead**: morel-m4a
**Duration**: 2-3 hours
**Dependencies**: B1 (scope analysis)

**Purpose**: Identify 10-15 failure modes with documented mitigations.

**Research Checklist**:
- [ ] Read ChromaDB: `analysis::morel::transitive-closure-cardinality-mismatch`
- [ ] Read ChromaDB: `strategy::morel::phase3b-architectural-fix`
- [ ] Review substantive-critic's 7 issues from PHASE-5-DECISION-AUDIT.md
- [ ] Analyze B1 scope findings for component-specific failures
- [ ] Review current test output from PredicateInverterTest

**Failure Mode Categories to Cover**:
1. Cardinality boundary (compile-time vs runtime)
2. Environment scoping
3. Type system mismatches
4. Pattern detection brittleness
5. Step function generation bugs
6. Integration conflicts with existing Extents logic
7. Performance issues (convergence, memory)
8. Edge cases (empty graphs, cycles, disconnected components)
9. Timeline overruns
10. Test coverage gaps

**Deliverable**: `.pm/FAILURE-MODE-ANALYSIS.md`

**Quality Criteria**:
- [ ] Contains 10+ distinct failure modes
- [ ] Each has: ID, description, likelihood (%), impact (H/M/L)
- [ ] Each has: detection mechanism, mitigation strategy
- [ ] Each has: contingency action if mitigation fails
- [ ] Summary: expected 1-2 failures during Phase 5
- [ ] Risk register table format

---

### B3: GO/NO-GO Criteria Refinement (morel-fds)

**Bead**: morel-fds
**Duration**: 1 hour
**Dependencies**: B1 (informs what to measure)

**Purpose**: Define precise, measurable success criteria for Phase 5a validation.

**Research Checklist**:
- [ ] Read PredicateInverter.java:126-148 (existing 5a-prime debug code)
- [ ] Analyze what the debug output actually shows
- [ ] Review Extents.java:727-758 integration point
- [ ] Define what "environment accessible" actually means
- [ ] Distinguish "environment contains binding" from "can evaluate at compile time"

**Key Insight to Address**:
The environment DOES contain bindings. The real test is whether Core.Apply can DEFER evaluation to runtime, avoiding the cardinality boundary problem. The GO/NO-GO criteria must test the right thing.

**Criteria Categories**:
1. **GO** - Core.Apply defers evaluation successfully
2. **PARTIAL-GO** - Pattern detected but needs minor fixes
3. **NO-GO** - Fundamental approach doesn't work

**Deliverable**: `.pm/PHASE-5A-SUCCESS-CRITERIA.md`

**Quality Criteria**:
- [ ] GO criteria are ALL measurable (not subjective)
- [ ] Tests Core.Apply deferred evaluation (not just environment access)
- [ ] PARTIAL-GO scenario documented with fix scope estimates
- [ ] NO-GO criteria trigger clear pivot to B6 fallback
- [ ] Evidence collection methods specified
- [ ] Contingency time budgets per decision outcome

---

### B4: Phase Ordering Review (morel-a02)

**Bead**: morel-a02
**Duration**: 1.5 hours
**Dependencies**: B3 (criteria affect ordering)

**Purpose**: Justify correct phase sequence per audit recommendation.

**Research Checklist**:
- [ ] Map current bead dependencies: `bd show morel-wvh`, etc.
- [ ] Analyze audit recommendation: 5b -> 5a -> 5d -> 5c
- [ ] Verify why 5b (Pattern Spec) should come before 5a (Validation)
- [ ] Determine if B1 scope analysis changes the ordering

**Key Analysis**:
Given that code already exists (B1 finding), does the ordering matter as much?
- If Phase 5d is "debug existing" not "build new", validation can happen sooner
- Pattern spec may be less critical if patterns are already implemented

**Deliverable**: `.pm/PHASE-ORDERING-JUSTIFICATION.md`

**Quality Criteria**:
- [ ] Each phase's dependencies explicitly documented
- [ ] Decision clearly stated: ACCEPT/REJECT/MODIFY audit recommendation
- [ ] Rationale based on dependency analysis AND B1 scope findings
- [ ] Bead dependency updates specified (bd dep add commands)
- [ ] Timeline implications documented

---

### B5: Contingency Budget (morel-a25)

**Bead**: morel-a25
**Duration**: 1 hour
**Dependencies**: B2 (failure modes inform budget)

**Purpose**: Define time budgets for different fix categories.

**Research Checklist**:
- [ ] Review B2 failure mode analysis output
- [ ] Estimate fix time for each failure mode
- [ ] Categorize into SMALL/MEDIUM/LARGE
- [ ] Define escalation thresholds
- [ ] Map to pivot triggers

**Categories**:
- **SMALL** (< 2 hours): Syntax fixes, configuration, minor bugs
- **MEDIUM** (2-8 hours): Algorithm fixes, type annotation, pattern expansion
- **LARGE** (> 8 hours): Architectural changes, fundamental approach issues

**Deliverable**: `.pm/CONTINGENCY-BUDGET.md`

**Quality Criteria**:
- [ ] Three categories with concrete hour ranges
- [ ] 3+ examples for each category from B2 failure modes
- [ ] Escalation criteria are quantitative (hours spent, failures encountered)
- [ ] Pivot triggers clearly defined (when to abandon Core.Apply)
- [ ] Maps explicitly to failure modes from B2

---

### B6: Explicit Syntax Research (morel-l2u)

**Bead**: morel-l2u
**Duration**: 1-2 days (PARALLEL TRACK)
**Dependencies**: None (runs independently)

**Purpose**: Design WITH RECURSIVE syntax as viable fallback option.

**Research Checklist**:
- [ ] Study SQL WITH RECURSIVE semantics and implementation
- [ ] Review Flix explicit fixpoint syntax for comparison
- [ ] Design Morel syntax extension proposal
- [ ] Identify parser changes needed (MorelParser.jj)
- [ ] Identify Core IR changes needed
- [ ] Identify type system changes needed
- [ ] Estimate implementation complexity per component

**Syntax Design Considerations**:
```sml
(* Option A: SQL-style WITH RECURSIVE *)
with recursive path(x, y) as (
  edge(x, y)
  union
  from (x, z) in edges, (z2, y) in path
    where z = z2 yield (x, y)
)
from p in path

(* Option B: Explicit iterate *)
let path = Relational.iterate edges (fn (old, new) =>
  from (x, z) in edges, (z2, y) in new
    where z = z2 yield (x, y)
)
in from p in path

(* Option C: Annotation-based *)
(*@ recursive_query *)
fun path (x, y) = ...
```

**Deliverable**: `.pm/EXPLICIT-SYNTAX-DESIGN.md`

**Quality Criteria**:
- [ ] Proposed syntax is valid SML extension (or documents deviations)
- [ ] Implementation complexity broken down by component
- [ ] Effort estimate in days per component
- [ ] Comparison table vs Core.Apply approach
- [ ] Risk assessment for fallback viability
- [ ] Recommendation: Proceed if Core.Apply fails?

---

### B7: Integration Point Analysis (morel-vhd)

**Bead**: morel-vhd
**Duration**: 1-2 hours
**Dependencies**: B1 (scope informs integration)

**Purpose**: Verify Extents.java integration point is ready.

**Research Checklist**:
- [ ] Read Extents.java:700-760 (actual integration location)
- [ ] Verify line 707-710 Op.ID case handling
- [ ] Trace `tryInvert` flag logic (lines 727-758)
- [ ] Verify `env` parameter is passed to PredicateInverter.invert()
- [ ] Verify `typeSystem` parameter is available
- [ ] Check if `goalPats` are correctly constructed
- [ ] Check `boundPats` conversion to generators

**Key Verification**:
Line 742-743 shows:
```java
PredicateInverter.invert(typeSystem, env, filter, goalPats, generators);
```

- Is `env` the right environment (contains function bindings)?
- Is `typeSystem` correctly scoped?
- Are `goalPats` and `generators` correct?

**Deliverable**: `.pm/INTEGRATION-POINT-ANALYSIS.md`

**Quality Criteria**:
- [ ] Correct line numbers verified (707-710, 727-758)
- [ ] Environment availability PROVEN with code trace
- [ ] TypeSystem availability PROVEN with code trace
- [ ] Code snippet for any needed changes provided
- [ ] Complexity estimate with specific rationale
- [ ] Conclusion: Integration ready / needs X hours work

---

### B8: Type System Analysis (morel-3f8)

**Bead**: morel-3f8
**Duration**: 1-2 hours
**Dependencies**: B1 (scope informs type analysis)

**Purpose**: Analyze how Core.Apply types work in Morel.

**Research Checklist**:
- [ ] Read PredicateInverter.java:551-565 (Core.Apply construction)
- [ ] Analyze Relational.iterate type signature from BuiltIn.java
- [ ] Trace type inference for generated expressions
- [ ] Check buildStepLambda return type (lines 1649-1698)
- [ ] Check buildStepBody return type (lines 1701-1760)
- [ ] Verify FROM expression type in step body

**Key Type Constraints**:
```
Relational.iterate: 'a bag -> ('a bag * 'a bag -> 'a bag) -> 'a bag

- Base generator must be: 'a bag
- Step function must be: ('a bag * 'a bag) -> 'a bag
- Result is: 'a bag
```

**Questions to Answer**:
1. Does baseCaseResult.generator.expression have correct type?
2. Does stepFunction have correct signature?
3. Is type inference automatic or needs explicit annotations?
4. What type errors are possible?

**Deliverable**: `.pm/TYPE-SYSTEM-ANALYSIS.md`

**Quality Criteria**:
- [ ] Relational.iterate signature documented from code
- [ ] Generated expression types specified at each step
- [ ] Type compatibility verified with code traces
- [ ] Potential type errors enumerated (3+)
- [ ] Recommendations for type handling

---

## Execution Sequence

### Dependency Graph

```
                        B6 (Parallel, 1-2 days)
                        |
                        v
Start ──> B1 ──────────────────────────────────────> Continue
          │                                              │
          ├──> B7 ──┐                                    │
          │         │                                    v
          ├──> B8 ──┼──> B2 ──────────────────────> B5 ──> Done
          │         │
          └──> B3 ──┴──> B4 ──────────────────────────────>
```

### Day-by-Day Schedule

**Day 1 (6-7 hours)**:
| Time | Task | Duration | Notes |
|------|------|----------|-------|
| 0:00 | B1: Scope Analysis | 30 min | First priority |
| 0:00 | B6: Start Explicit Syntax (parallel) | - | Background |
| 0:30 | B7, B8, B3 in parallel | 2h | Three analysts |
| 2:30 | B2: Failure Modes | 2-3h | Uses B1, B7, B8 outputs |
| 5:00 | B4: Phase Ordering | 1.5h | Uses B3 output |
| | **Day 1 Total** | **6-7h** | |

**Day 2 (3-5 hours)**:
| Time | Task | Duration | Notes |
|------|------|----------|-------|
| 0:00 | B5: Contingency Budget | 1h | Uses B2 output |
| 1:00 | Document Collection | 30 min | Prepare for audit |
| 1:30 | B6 continues (if needed) | - | Background |
| 2:00 | Second Audit Request | 2-4h | Spawn plan-auditor |
| | **Day 2 Total** | **3-5h** | |

**Total**: 9-12 core hours + B6 parallel track

---

## Quality Gate Checklist (Second Audit Readiness)

Before requesting second audit, verify ALL documents complete:

### Document Completion
- [ ] `.pm/PHASE-5D-SCOPE-ANALYSIS.md` created (B1)
- [ ] `.pm/FAILURE-MODE-ANALYSIS.md` created (B2)
- [ ] `.pm/PHASE-5A-SUCCESS-CRITERIA.md` created (B3)
- [ ] `.pm/PHASE-ORDERING-JUSTIFICATION.md` created (B4)
- [ ] `.pm/CONTINGENCY-BUDGET.md` created (B5)
- [ ] `.pm/EXPLICIT-SYNTAX-DESIGN.md` created (B6)
- [ ] `.pm/INTEGRATION-POINT-ANALYSIS.md` created (B7)
- [ ] `.pm/TYPE-SYSTEM-ANALYSIS.md` created (B8)

### Audit Issue Coverage

Original 14 issues must be addressed:

| Issue | Addressed By | Document |
|-------|--------------|----------|
| 1. Documentation-focused validation | B1, B3, B7 | Scope, Criteria, Integration |
| 2. GO/NO-GO criteria vague | B3 | Success Criteria |
| 3. Missing SuchThatShuttle | B7 | Integration Analysis |
| 4. No step function validation | B1, B8 | Scope, Type System |
| 5. Confidence not justified | B2, B3 | Failure Modes, Criteria |
| 6. Phase ordering conflicts | B4 | Ordering Justification |
| 7. Missing failure modes | B2 | Failure Mode Analysis |
| 8. Fallback inadequate | B6 | Explicit Syntax Design |
| 9. Quality gates insufficient | B3, B5 | Criteria, Contingency |
| 10. Timeline lacks contingency | B5 | Contingency Budget |
| 11. Phase 5d may be redundant | B1 | Scope Analysis |
| 12. Risk mitigation missing | B2, B5 | Failure Modes, Contingency |
| 13. Integration validation needed | B7 | Integration Analysis |
| 14. Quality gates insufficient | B3 | Success Criteria |

### Quality Standards
- [ ] Each document follows template structure
- [ ] No vague claims - all assertions backed by evidence
- [ ] Code references include line numbers
- [ ] Time estimates include 30% contingency
- [ ] Failure modes have mitigation strategies

---

## Contingency Recommendations

### If B1 reveals more gaps than expected:
- Add 2-4 hours to B2 (more failure modes)
- Revise B5 contingency budgets upward
- May need to extend B7/B8 scope

### If B6 takes longer than 2 days:
- Continue past second audit (it's a fallback)
- Ensure B6 completion before Phase 5 execution
- Can be submitted as addendum

### If second audit fails:
- Review which issues remain unaddressed
- Allocate 4-8 hours for remediation
- May need third audit (add 4 hours)

### Escape hatch:
- If core track exceeds 16 hours
- Split into two refinement rounds:
  - Round 1: B1, B2, B3, B7 (technical foundation)
  - Round 2: B4, B5, B8, B6 (planning and fallback)

---

## Success Criteria for Phase C

### Plan Quality
- [ ] All 14 audit issues have documented resolution
- [ ] Failure modes identified with mitigations (10+ modes)
- [ ] GO/NO-GO criteria are measurable and clear
- [ ] Phase ordering justified with dependencies
- [ ] Contingency budget defined with thresholds
- [ ] Fallback option researched (not just mentioned)
- [ ] Quality gates are comprehensive
- [ ] Timeline realistic with 30% contingency buffers

### Second Audit Verdict Target
- [ ] Overall confidence: >= 85%
- [ ] Strategic direction: SOUND
- [ ] Tactical execution: WELL-PLANNED
- [ ] Risk management: COMPREHENSIVE
- [ ] Go/no-go recommendation: CLEAR

---

## Bead Commands

### Update beads to in_progress as work starts:
```bash
# B1 start
bd update morel-px9 --status in_progress

# B6 start (parallel)
bd update morel-l2u --status in_progress

# After B1 completes, start B2, B3, B7, B8
bd close morel-px9 --reason "Scope analysis complete"
bd update morel-m4a --status in_progress
bd update morel-fds --status in_progress
bd update morel-vhd --status in_progress
bd update morel-3f8 --status in_progress
```

### Verify dependencies:
```bash
bd show morel-px9   # Should show: depends on morel-6a8
bd show morel-m4a   # Should show: depends on morel-px9
bd show morel-vhd   # Should show: depends on morel-px9
```

---

## ChromaDB References

### Prior Work to Consult:
- `analysis::morel::transitive-closure-cardinality-mismatch` - Root cause analysis
- `strategy::morel::phase3b-architectural-fix` - Architectural fix strategy
- `implementation::morel::phase1-transitive-closure` - Implementation plan
- `codebase::morel::predicate-inversion` - System overview
- `codebase::morel::analysis-summary` - Codebase analysis

### Store New Findings:
- Store B1 findings: `analysis::morel::phase5d-scope`
- Store B2 findings: `risk::morel::failure-modes-phase5`
- Store B6 design: `design::morel::explicit-syntax-with-recursive`

---

**Document Status**: READY FOR EXECUTION
**Next Action**: Begin B1 (Scope Analysis) immediately
**Parallel Action**: Start B6 (Explicit Syntax Research) in background
