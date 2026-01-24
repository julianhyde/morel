# Morel Phase 5: Path C Full Refinement - Execution Plan

**Last Updated**: 2026-01-24 16:00 UTC
**Current Phase**: Phase 5 - Path C Full Refinement Selected
**Status**: Ready for Phase 5a-prime quick test (CRITICAL GATE)

---

## Current Situation

**COMPLETED**:
- Phase 3-4: Comprehensive research on Mode Analysis and Core.Apply approaches
- Substantive-critic: Independent review identifying 7 critical issues
- Phase 5 Decision: Core.Apply Generation recommended (4.3/5 viability)
- Plan Audit: Comprehensive audit identifying 14 critical issues
- Path C Selection: Full plan refinement chosen to achieve 85%+ confidence
- Bead Creation: 11 new beads for refinement work

**IN PROGRESS**:
- Phase 5a-prime: 30-minute quick empirical test (CRITICAL GATE)
- Full Refinement: 8 analysis documents to create
- Second Audit: Validate refined plan

---

## Path C: Full Plan Refinement

### Why Path C?

The audit identified 14 critical issues that need addressing:
1. Documentation-focused validation (not empirical)
2. Vague GO/NO-GO criteria
3. Missing contingency plan
4. Phase 5d scope unclear
5. Missing failure mode analysis
6. Single test case insufficient
7. Missing specification connection
8. Phase ordering has dependencies
9. Timeline lacks contingency
10. Fallback plan inadequate
11. Missing type system validation
12. Missing step function validation
13. Missing integration point analysis
14. Quality gates insufficient

**Target**: Achieve 85%+ confidence (up from current 60%)

---

## Execution Timeline

### Day 1: Critical Gate + Initial Refinement

**Phase 5a-prime (30-60 min)** - CRITICAL GATE
- Bead: `morel-6a8`
- Add debug logging to PredicateInverter
- Run such-that.smli test
- Decision: GO / INVESTIGATE / NO-GO

**If GO or INVESTIGATE, continue with:**

**B1: Scope Analysis (30 min)** - `morel-px9`
- Read PredicateInverter.java:464-555
- Document existing code vs requirements
- Deliverable: PHASE-5D-SCOPE-ANALYSIS.md

**B3: GO/NO-GO Criteria (1h)** - `morel-fds`
- Define measurable thresholds
- Create decision tree
- Deliverable: PHASE-5A-SUCCESS-CRITERIA.md

### Day 1-2: Full Refinement

**B2: Failure Mode Analysis (2-3h)** - `morel-m4a`
- Identify 10-15 failure modes
- Create risk register
- Deliverable: FAILURE-MODE-ANALYSIS.md

**B4: Phase Ordering Review (1.5h)** - `morel-a02`
- Evaluate audit recommendation
- Update bead dependencies
- Deliverable: PHASE-ORDERING-JUSTIFICATION.md

**B5: Contingency Budget (1h)** - `morel-a25`
- Define SMALL/MEDIUM/LARGE fix budgets
- Deliverable: CONTINGENCY-BUDGET.md

**B7: Integration Analysis (1-2h)** - `morel-vhd`
- Find Extents.java integration point
- Verify environment availability
- Deliverable: INTEGRATION-POINT-ANALYSIS.md

**B8: Type System Analysis (1-2h)** - `morel-3f8`
- Analyze Core.Apply type handling
- Deliverable: TYPE-SYSTEM-ANALYSIS.md

### Parallel Track (Days 1-2)

**B6: Explicit Syntax Research (1-2d)** - `morel-l2u`
- Design WITH RECURSIVE syntax
- Estimate implementation complexity
- Deliverable: EXPLICIT-SYNTAX-DESIGN.md

### Day 2-3: Second Audit

**Second Audit (2-4h)** - `morel-zpi`
- Compile all refinement documents
- Spawn plan-auditor agent
- Validate 85%+ confidence achieved
- Deliverable: PHASE-5-DECISION-AUDIT-2.md

---

## Bead Summary

### Critical Path Beads (P0)

| Bead | Task | Status | Blocks |
|------|------|--------|--------|
| morel-6a8 | Phase 5a-prime Quick Test | READY | All refinement |
| morel-a4y | Full Refinement Epic | BLOCKED by 6a8 | Second audit |
| morel-zpi | Second Audit Request | BLOCKED by a4y | Phase 5 execution |

### Refinement Task Beads (P1)

| Bead | Task | Duration | Depends On |
|------|------|----------|------------|
| morel-px9 | Scope Analysis (B1) | 30 min | 6a8 |
| morel-fds | GO/NO-GO Criteria (B3) | 1h | - |
| morel-m4a | Failure Mode Analysis (B2) | 2-3h | px9 |
| morel-a02 | Phase Ordering (B4) | 1.5h | fds |
| morel-a25 | Contingency Budget (B5) | 1h | m4a |
| morel-l2u | Explicit Syntax (B6) | 1-2d | - (parallel) |
| morel-vhd | Integration Analysis (B7) | 1-2h | px9 |
| morel-3f8 | Type System Analysis (B8) | 1-2h | vhd |

### Existing Phase 5 Beads (Updated)

| Bead | Task | Status | Now Depends On |
|------|------|--------|----------------|
| morel-wvh | Phase 5a Validation | BLOCKED by 6a8 | 5a-prime result |
| morel-c2z | Phase 5b Pattern Spec | BLOCKED by wvh | Phase ordering TBD |
| morel-9af | Phase 5c Test Plan | BLOCKED by wvh | Phase ordering TBD |
| morel-aga | Phase 5d Prototype | BLOCKED by c2z, 9af | Scope analysis |

---

## Immediate Action: Phase 5a-prime

### Execute Now (30 minutes)

**Step 1**: Add debug logging to PredicateInverter.java

```java
// In constructor (~line 91):
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

**Step 2**: Run test

```bash
./mvnw test -Dtest=ScriptTest -Dmorel.script=such-that.smli 2>&1 | tee /tmp/5a-prime.txt
```

**Step 3**: Analyze output

Look for:
```
=== PredicateInverter Environment ===
  edges: (int * int) list    <-- THIS IS THE KEY
  edge: int * int -> bool
  path: int * int -> bool
=====================================
```

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
    B1-B8             investigation       |
          |                |                v
          v                v          Pivot to
    Second Audit    Fix then continue   Explicit Syntax
                                       (B6 critical path)
```

---

## Decision Points

### After Phase 5a-prime

**GO**: Proceed with full refinement (B1-B8), then second audit
**INVESTIGATE**: Debug environment issue, then continue
**NO-GO**: Pivot to explicit syntax research (B6 becomes critical path)

### After Second Audit

**PASS (85%+ confidence)**: Proceed with Phase 5 execution
**CONDITIONAL PASS (75-84%)**: Address remaining issues, then proceed
**FAIL (<75%)**: Re-evaluate approach, consider Mode Analysis or explicit syntax

---

## Deliverables Checklist

### Phase 5a-prime
- [ ] Debug logging added to PredicateInverter
- [ ] Test executed
- [ ] Result documented in PHASE-5A-PRIME-RESULT.md
- [ ] GO/INVESTIGATE/NO-GO decision made

### Refinement Documents (B1-B8)
- [ ] PHASE-5D-SCOPE-ANALYSIS.md
- [ ] FAILURE-MODE-ANALYSIS.md
- [ ] PHASE-5A-SUCCESS-CRITERIA.md
- [ ] PHASE-ORDERING-JUSTIFICATION.md
- [ ] CONTINGENCY-BUDGET.md
- [ ] EXPLICIT-SYNTAX-DESIGN.md
- [ ] INTEGRATION-POINT-ANALYSIS.md
- [ ] TYPE-SYSTEM-ANALYSIS.md

### Second Audit
- [ ] All 8 documents complete
- [ ] PHASE-5-DECISION-AUDIT-2.md
- [ ] Confidence >= 85%
- [ ] Go/no-go recommendation clear

---

## Success Criteria for Path C

**Plan Quality**:
- All 14 audit issues have documented resolution
- Failure modes identified with mitigations (10+ modes)
- GO/NO-GO criteria are measurable
- Phase ordering justified
- Contingency budget defined
- Fallback researched (not just mentioned)
- Quality gates comprehensive
- Timeline realistic (30% buffer)

**Second Audit Verdict**:
- Overall confidence: >= 85%
- Strategic direction: SOUND
- Tactical execution: WELL-PLANNED
- Risk management: COMPREHENSIVE
- Go/no-go recommendation: CLEAR

---

## Timeline Summary

| Phase | Duration | Target |
|-------|----------|--------|
| Phase 5a-prime | 30-60 min | Day 1 AM |
| B1-B5, B7-B8 | 8-10 hours | Day 1-2 |
| B6 (parallel) | 1-2 days | Day 1-2 |
| Second Audit | 2-4 hours | Day 2-3 |
| **Total Refinement** | **2-3 days** | |
| Phase 5 Execution | 7-11 days | Week 2-3 |
| **Total to Validation** | **9-15 days** | |

---

## For Session Continuity

**If resuming this session**:
1. Check: Did Phase 5a-prime run? (`bd show morel-6a8`)
2. If not: Execute Phase 5a-prime NOW
3. If yes: Check result and continue with B1-B8

**If starting fresh**:
1. Read this CONTINUATION.md
2. Read PHASE-5-REFINEMENT-PLAN.md for detailed execution plan
3. Execute `bd ready` to see available work
4. Start with morel-6a8 (Phase 5a-prime)

---

## Quick Commands

```bash
# Check current state
bd ready

# Start Phase 5a-prime
bd update morel-6a8 --status=in_progress

# After 5a-prime completes
bd close morel-6a8 --reason="GO: edges binding verified"

# Start refinement
bd update morel-px9 --status=in_progress
```

---

## Key Files

| File | Purpose |
|------|---------|
| .pm/PHASE-5-REFINEMENT-PLAN.md | Detailed execution plan |
| .pm/PHASE-5-DECISION-AUDIT.md | First audit results (14 issues) |
| .pm/PHASE-5-DECISION.md | Original decision document |
| .pm/PHASE-5A-BEAD-SPECIFICATIONS.md | Phase 5 bead details |

---

## EXECUTE NOW

**morel-6a8: Phase 5a-prime Quick Empirical Test**

This 30-minute test is the gate for everything else. It provides empirical evidence that makes the entire Phase 5 validation plan credible.

```bash
bd update morel-6a8 --status=in_progress
# Add debug logging, run test, record result
bd close morel-6a8 --reason="[GO/INVESTIGATE/NO-GO]: [evidence]"
```

---

**Status**: Path C Selected | Phase 5a-prime READY | Refinement beads created
**Next Milestone**: Phase 5a-prime result (30 min)
**Then**: Full refinement (B1-B8) + Second audit
