# Agent Instructions: Morel Predicate Inversion

**Context**: Morel Issue #217 - Predicate Inversion (Phase 3-4)
**Current Phase**: Phase 2 complete, Phase 3 planning
**Orchestration**: Beads track all work; agents receive detailed handoffs

---

## Overview

This guide explains how to spawn specific agents for Morel predicate inversion work. Each agent type has specific responsibilities and handoff requirements.

---

## Agent Types & When to Use

### 1. java-architect-planner

**Purpose**: Design Phase 3-4 epic components before implementation

**When to Spawn**:
- Before each epic begins
- When encountering design questions
- For Phase 3.1 (PPT design) before implementation
- For Phase 4.1 (mode analysis infrastructure) before implementation

**Duration**: 1 day per epic (design session)

**Handoff Template**:

```
## Handoff: java-architect-planner

**Task**: [One sentence design goal]
**Bead**: Phase3-<N>-<Epic> (status: design_review)

### Input Artifacts
- Memory Bank: morel_active/algorithm-synthesis-ura-to-morel.md (relevant section)
- Files: [Key Java files involved]
- Synthesis: [Paper reference section]
- PHASES.md: Phase 3 overview

### Deliverable
[Component design document with:
- Class hierarchy (if applicable)
- Method signatures with types
- Integration points with existing code
- Edge cases identified
- Test strategy outline]

### Quality Criteria
- [ ] Design references existing code patterns
- [ ] All edge cases identified
- [ ] Integration points clear
- [ ] Test strategy includes 5+ specific test cases
- [ ] Performance implications considered
- [ ] No compiler warnings

### Context Notes
Phase 2 complete. This epic is Phase 3 epic N of 6.
Existing pattern reference: [Point to similar code in codebase]
```

**Example (Phase 3.1)**:

```
## Handoff: java-architect-planner

**Task**: Design ProcessTreeNode hierarchy for Abramov-Glück perfect process tree construction.
**Bead**: Phase3-PPT-Design (status: design_review)

### Input Artifacts
- Memory Bank: morel_active/algorithm-synthesis-ura-to-morel.md (section: "Step 1: Build Perfect Process Tree")
- Files: src/main/java/net/hydromatic/morel/compile/PredicateInverter.java
- Paper: Abramov & Glück 2002, Section 2.3
- Related: Existing Core.Exp hierarchy in Core.java

### Deliverable
ProcessTreeNode design document with:
- Node types: TerminalNode, BranchNode, etc.
- Field definitions with types
- Visitor pattern for traversal
- Integration with existing InversionResult

### Quality Criteria
- [ ] Design matches Abramov-Glück terminology
- [ ] Handles all pattern types in Phase 2
- [ ] 10+ unit test cases outlined
- [ ] Visitor pattern explored
- [ ] Memory efficiency analyzed

### Context Notes
This enables Phase 3.2+ work. Reference existing pattern visitors in codebase.
```

---

### 2. java-developer

**Purpose**: Implement designed components with test-first approach

**When to Spawn**:
- After design review approved for epic
- For implementation sprints (typically 3-5 days per epic)
- Can work on multiple epics in parallel if Phase 3.1 complete

**Duration**: 3-5 days per epic (implementation + review cycle)

**Handoff Template**:

```
## Handoff: java-developer

**Task**: [One sentence implementation goal]
**Bead**: Phase3-<N>-<Epic> (status: ready_for_implementation)

### Input Artifacts
- Memory Bank: morel_active/ (relevant synthesis documents)
- Design Doc: [From java-architect-planner]
- Files: [Files to modify/create]
- Tests: [Test file with test stubs]
- References: [Code examples from papers/synthesis]

### Deliverable
[Completed implementation with:
- All tests passing
- 85%+ code coverage
- Comments explaining algorithm
- Paper references for non-obvious logic]

### Quality Criteria
- [ ] All bead acceptance criteria passing
- [ ] 159 baseline tests still passing
- [ ] Code coverage >= 85% for new code
- [ ] No compiler warnings
- [ ] Comments reference synthesis doc + paper
- [ ] Performance benchmark collected

### Context Notes
Design approved. Focus on test-first. Synthesis document is source of truth.
Peer review available - request early if uncertain.
```

**Example (Phase 3.1)**:

```
## Handoff: java-developer

**Task**: Implement ProcessTreeNode hierarchy and TreeBuilder for perfect process tree construction.
**Bead**: Phase3-PPT-Implementation (status: ready_for_implementation)

### Input Artifacts
- Memory Bank: morel_active/algorithm-synthesis-ura-to-morel.md (Step 1)
- Design: [From architecture phase]
- Tests: ProcessTreeTest.java (test stubs)
- Code: src/main/java/net/hydromatic/morel/compile/PredicateInverter.java

### Deliverable
- ProcessTreeNode.java: Complete class hierarchy
- ProcessTreeBuilder.java: PPT construction logic
- 12+ tests in ProcessTreeTest.java all passing
- All 159 baseline tests still passing

### Quality Criteria
- [ ] testTerminalNodeCreation passing
- [ ] testBranchNodeAlternatives passing
- [ ] testEnvironmentTracking passing
- [ ] testDepthFirstTraversal passing
- [ ] testDuplicateElements passing
- [ ] 85%+ code coverage
- [ ] No compiler warnings
- [ ] Comments match synthesis doc terminology

### Context Notes
Synthesis document "Step 1: Build Perfect Process Tree" is authoritative.
Abramov & Glück 2002 Section 2.3 for theory.
Existing Core.Exp traversal patterns in code for reference.
```

---

### 3. code-review-expert

**Purpose**: Review implementation quality before merge

**When to Spawn**:
- After java-developer completes implementation
- For each epic before marking complete
- For cross-cutting reviews (if needed between phases)

**Duration**: 2-4 hours per epic

**Handoff Template**:

```
## Handoff: code-review-expert

**Task**: [One sentence: review feature/epic]
**Bead**: Phase3-<N>-<Epic> (status: review)

### Input Artifacts
- Code: [Changed files]
- Tests: [Test results]
- Benchmarks: [Performance data]
- Synthesis: [Knowledge base reference]

### Deliverable
[Code review with:
- Line-by-line comments
- Design alignment check
- Test coverage assessment
- Performance impact analysis
- Approval or requested changes]

### Quality Criteria
- [ ] Code matches design
- [ ] Tests comprehensive (covers edge cases)
- [ ] Test coverage >= 85%
- [ ] Comments explain algorithm
- [ ] No compiler warnings
- [ ] Performance acceptable

### Context Notes
Design document available. Paper reference expected in code.
All 159 baseline tests must pass.
```

**Example**:

```
## Handoff: code-review-expert

**Task**: Review ProcessTreeNode implementation for correctness and quality.
**Bead**: Phase3-PPT-Implementation (status: review)

### Input Artifacts
- Code: PredicateInverter.java + ProcessTreeNode.java
- Tests: ProcessTreeTest.java (12 tests, all passing)
- Benchmarks: PPT construction time < 1ms
- Design: From architecture phase
- Synthesis: algorithm-synthesis-ura-to-morel.md

### Deliverable
Code review addressing:
- Algorithm correctness vs. Abramov-Glück
- Test coverage completeness
- Edge case handling
- Performance vs. baseline
- Code quality and clarity

### Quality Criteria
- [ ] All tests passing
- [ ] 85%+ coverage
- [ ] Comments match synthesis document
- [ ] Paper references included
- [ ] No performance regression
- [ ] Ready for merge approval

### Context Notes
Synthesis document is specification.
Existing patterns in Core.java should be followed.
Check for duplicate detection edge cases.
```

---

### 4. test-validator

**Purpose**: Verify test coverage and completeness

**When to Spawn**:
- After Phase 3 complete (for Phase 3 aggregate validation)
- After Phase 4 complete (for Phase 4 aggregate validation)
- If test coverage gaps identified

**Duration**: 4-6 hours (full phase review)

**Handoff Template**:

```
## Handoff: test-validator

**Task**: [One sentence: validate test coverage for phase/epic]
**Bead**: Phase3-Testing or Phase4-Testing (status: validation)

### Input Artifacts
- Code: [All changed files for phase]
- Tests: [All test files for phase]
- Coverage Report: [JaCoCo or similar report]
- Spec: [Acceptance criteria for phase]

### Deliverable
[Test validation report with:
- Coverage map (% per file)
- Gap analysis
- Recommended new tests
- Test quality assessment
- Approval or change requests]

### Quality Criteria
- [ ] Coverage >= 85% for all new code
- [ ] All acceptance criteria tested
- [ ] Edge cases covered
- [ ] Property tests included
- [ ] No test interdependencies

### Context Notes
Use bead acceptance criteria as test checklist.
Check for property-based tests (soundness, completeness).
```

---

### 5. plan-auditor

**Purpose**: Validate plans and gate phase transitions

**When to Spawn**:
- Before Phase 3 implementation (validate plan)
- Before Phase 3 → Phase 4 transition (phase gate)
- Before Phase 4 → Release (final gate)

**Duration**: 4-8 hours (thorough review)

**Handoff Template**:

```
## Handoff: plan-auditor

**Task**: [Audit plan / gate phase transition]
**Bead**: Phase<N>-Gate or Phase<N>-Plan (status: audit)

### Input Artifacts
- Plan: [phase-3-4-strategic-plan.md]
- Execution State: [.pm/execution_state.json]
- Completion Evidence: [Tests passing, metrics, reviews]
- Bead Status: [All epics status]

### Deliverable
[Audit report with:
- Plan feasibility assessment
- Risk analysis
- Quality criteria verification
- Gate approval decision]

### Quality Criteria
- [ ] All phase success criteria evaluated
- [ ] Risks assessed and mitigated
- [ ] Quality gates met
- [ ] Team ready for next phase
- [ ] Dependencies resolved

### Context Notes
Reference METHODOLOGY.md quality gates.
Verify execution_state.json metrics.
```

---

### 6. strategic-planner

**Purpose**: Create high-level Phase 3-4 plan

**When to Spawn**:
- Already completed (plan exists in STRATEGIC_PLAN_PHASES_3-4.md)
- Only if plan needs revision

**Note**: This agent already provided the Phase 3-4 plan. See `STRATEGIC_PLAN_PHASES_3-4.md` in repo root.

---

## Handoff Checklist

**Before spawning any agent**, verify:

- [ ] Bead created with clear acceptance criteria
- [ ] Memory bank files prepared (synthesis docs)
- [ ] Input artifacts linked and available
- [ ] Clear deliverable statement
- [ ] Quality criteria specific and measurable
- [ ] Context notes explain blockers/dependencies
- [ ] Recent test baseline established

---

## Communication Patterns

### For Design Review (java-architect-planner)

**Approval Signal**:
```
Design approved. Ready for implementation with these guidelines:
1. [Key decision #1]
2. [Key decision #2]
3. Watch for [edge case]
```

**If Rejected**:
```
Design needs revision:
1. [Concern #1 with rationale]
2. [Concern #2 with rationale]

Recommendation: Try [alternative approach]
```

### For Implementation (java-developer)

**Approval Signal**:
```
Implementation complete and approved:
- All tests passing
- Code review: [comments addressed / approved]
- Ready to merge to 217-phase1-transitive-closure
- Mark bead complete: bd close <id>
```

**If Issues**:
```
Implementation needs revision:
1. [Test failure with diagnosis]
2. [Code review comment with fix]

Recommendation: Focus on [area]
```

### For Code Review (code-review-expert)

**Approval Signal**:
```
Code review approved:
- Design: matches specification
- Quality: acceptable
- Tests: comprehensive
- Performance: acceptable
- Ready to merge

Minor comments:
1. [Comment with rationale and fix]
```

### For Phase Gate (plan-auditor)

**Approval Signal**:
```
Phase 3 gate APPROVED. Proceed to Phase 4.

Verification:
- All success criteria met
- Risks mitigated
- Team ready
- Next phase ready to start

Recommendations for Phase 4: [list]
```

**If Not Approved**:
```
Phase 3 gate NOT APPROVED. Address:

Critical blockers:
1. [Criterion not met with evidence]
2. [Risk not mitigated]

Path to approval:
1. [Action #1 with responsible party]
2. [Action #2 with responsible party]

Review deadline: [date]
```

---

## Quick Reference: Spawning Flow

```
PHASE START
  ↓
[1] Architect designs epic
    Command: /java-architect-planner
    Duration: 1 day
  ↓ (design approved)
[2] Developer implements
    Command: /java-developer
    Duration: 3-5 days
  ↓ (tests passing)
[3] Code reviewer reviews
    Command: /code-review-expert
    Duration: 2-4 hours
  ↓ (approved)
[4] Mark bead complete
    Command: bd close <id>
  ↓
PHASE COMPLETE (if last epic)
  ↓
[5] Test validator validates phase
    Command: /test-validate
    Duration: 4-6 hours
  ↓
[6] Plan auditor gates phase
    Command: /plan-audit
    Duration: 4-8 hours
  ↓ (approved)
PROCEED TO NEXT PHASE
```

---

## Integration with Bead System

**Bead Status Flow**:

```
pending
  ↓ (design starts)
design_review
  ↓ (design approved)
ready_for_implementation
  ↓ (implementation starts)
in_progress
  ↓ (implementation complete)
review
  ↓ (code review approved)
completed
```

**How to Track**:
```bash
bd show <bead-id>          # See current status
bd update <id> --status in_progress  # Update when starting
bd close <id>              # Mark complete after approval
```

---

## Knowledge Base Integration

Every agent handoff should include:

1. **Memory Bank Location**: e.g., `morel_active/algorithm-synthesis-ura-to-morel.md`
2. **Synthesis Document Section**: e.g., "Step 1: Build Perfect Process Tree"
3. **Paper References**: e.g., "Abramov & Glück 2002, Section 2.3"
4. **Code Examples**: "See ProcessTreeNode.java for related pattern"

This ensures agents have immediate access to necessary knowledge without hunting.

---

## Troubleshooting

### "Agent seems confused about requirements"

**Check**:
1. Is bead description clear and specific?
2. Are synthesis documents available in memory bank?
3. Are paper sections referenced?
4. Is handoff template completely filled out?

**Fix**: Revise handoff, ensure all sections completed

### "Agent output quality below expectations"

**Check**:
1. Did agent read synthesis documents?
2. Are quality criteria specific enough?
3. Is test coverage target clear?
4. Are code examples provided?

**Fix**: Add more specific guidance, reference examples

### "Dependencies not clear"

**Check**:
1. Are epic dependencies documented in `.pm/PHASES.md`?
2. Is bead dependency graph correct (`bd dep`)?
3. Are blocker epics marked as such?

**Fix**: Use `bd dep add <id> <blocker>` to declare dependencies

---

## For Orchestrator Agent

When orchestrator creates Phase 3-4 beads:

1. Use template from `phase-3-4-bead-definitions.md`
2. Include all required fields:
   - Title: `Phase<N>-<Epic>-<Focus>`
   - Type: epic/feature/task
   - Complexity: S/M/L with time estimate
   - Acceptance criteria: 3-5 specific, testable criteria
   - Dependencies: Link to other beads
   - Knowledge references: Memory bank + papers
3. Create in priority order (Phase 3.1 → 3.2 → ... → 3.6)
4. Set initial status to `pending`
5. Use `bd dep add` to establish dependencies

---

**Last Updated**: 2026-01-23
**For**: Morel Predicate Inversion Phase 3-4
**Approval Required**: All agent handoffs reviewed by developer before spawning
