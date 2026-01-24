# Morel Project Decision Log

**Project**: Morel Predicate Inversion (Issue #217)
**Maintained**: 2026-01-24 onwards
**Purpose**: Record significant architectural and technical decisions

---

## Decision 1: Phase 3b Cardinality Boundary - Option Selection

**Date**: 2026-01-24
**Issue**: Phase 3b attempted Relational.iterate integration failed with "infinite: int * int" runtime error
**Decision Maker**: Orchestrator (Analysis from ChromaDB + strategic assessment)
**Status**: DECIDED, Awaiting Implementation

### The Problem
- Attempted Phase 3b implementation discovered fundamental cardinality boundary issue
- Compile-time/runtime information mismatch in recursive predicate inversion
- Pattern is recognized at compile time, but actual bindings only available at runtime
- Current approach creates infinite extents that fail at runtime

### The Options Considered

#### Option 1: Early Detection (CHOSEN)
**Timeline**: 2-4 hours
**Approach**: Check cardinality before building Relational.iterate call
**Implementation**: Return null if base case inverts to INFINITE cardinality
**Consequence**: Falls back to cartesian product (baseline behavior)
**Advantage**: Fast, safe, unblocks testing
**Trade-off**: Transitive closure not optimized (slower)

#### Option 2: Mode Analysis
**Timeline**: 2-3 days
**Approach**: Build mode inference to handle deferred bindings
**Implementation**: Track which variables are inputs vs outputs
**Consequence**: Enables proper optimization with deferred base case evaluation
**Advantage**: Fully solves the boundary problem
**Trade-off**: Significant code addition (mode analysis infrastructure)

#### Option 3: SQL-Style Deferred
**Timeline**: 3-5 days
**Approach**: Don't invert at compile time, use LinkCode pattern
**Implementation**: Generate bytecode that uses Relational.iterate wrapper
**Consequence**: Entire recursion deferred to runtime (like SQL WITH RECURSIVE)
**Advantage**: Most robust, completely solves boundary crossing
**Trade-off**: Most complex, requires LinkCode pattern understanding

### Decision Rationale

**Chosen**: Option 1 (Early Detection)

**Reasoning**:
1. **Time**: 2-4 hours vs 2-3+ days
2. **Safety**: Reverts to proven baseline behavior (cartesian product)
3. **Unblocking**: Allows testing and verification TODAY
4. **Gateway**: Informs whether optimization (Option 2/3) is needed
5. **Cost-Benefit**: Solves immediate blocker with minimal change

**Deferred Optimization**: After Option 1 completion and performance evaluation:
- If cartesian product acceptable: DONE
- If optimization needed: Choose Option 2 (mode analysis) or Option 3 (LinkCode)

### Implementation Plan

**Phase 3b-Final (Quick Fix)**:
1. Add cardinality check before building Relational.iterate call
2. Return null if base case is INFINITE (user-defined function)
3. Test and validate (all tests must pass)
4. Document decision and performance baseline

**Timeline**: Tomorrow 2026-01-25 (2-4 hours)

### Success Criteria
- [ ] Code compiles with no errors
- [ ] All 159 baseline tests pass
- [ ] No "infinite: int * int" errors in generated code
- [ ] Transitive closure queries execute (even if slower)
- [ ] Performance baseline documented
- [ ] Decision rationale recorded

### Post-Implementation Review
**When**: After Phase 3b-Final completion
**Evaluate**: Is cartesian product performance acceptable?
**Decision Point**:
- If yes → Move to Phase 3c (QA) or Phase 4 (Mode Analysis)
- If no → Plan Option 2 (mode analysis) for next phase

### Related Documentation
- **ChromaDB**: analysis::morel::transitive-closure-cardinality-mismatch
- **ChromaDB**: strategy::morel::phase3b-architectural-fix
- **PM Files**: PHASE-3B-CARDINALITY-FIX.md, PHASE-3B-BEADS.md
- **Session**: CONTINUATION-PHASE-3B.md

### Decision Approval
- **Decision Level**: Architectural choice
- **Decision Maker**: Orchestrator agent (based on analysis)
- **Quality Gate**: plan-auditor review of approach (before implementation)
- **Authority**: Follows established PM methodology

---

## Decision 2: Keep Deferred Grounding as Defensive Measure

**Date**: 2026-01-24
**Context**: Modification in Expander.java (lines 95-111) to skip pattern grounding check
**Status**: DECIDED, Remains in Codebase

### The Change
Modified Expander.java to skip pattern grounding check when extent has deferred evaluation:

```java
if (scan.exp.isExtent()) {
  // Deferred grounding for extents
  // Will be validated at code generation time
  // If infinite, will fail at runtime
}
```

### Rationale
1. **Defensive**: Doesn't prevent errors, just defers them
2. **Safety Net**: Allows compilation to proceed, fails at runtime if problematic
3. **Information Flow**: Separates compile-time from runtime concerns
4. **Reversible**: Can be reverted if analysis shows it's unnecessary

### Decision
**Keep as is** - Defensive measure doesn't hurt
**Rationale**: If Option 1 cardinality check fails to catch infinite extents, this is fallback error point

### Future Review
- Revisit if Option 2 (mode analysis) implemented
- May be reverted if no longer needed after cardinality check

---

## Decision 3: Knowledge Integration Strategy

**Date**: 2026-01-24
**Decision**: Use ChromaDB for persistent knowledge, Memory Bank for session state
**Status**: DECIDED, Implemented

### ChromaDB Documents Created
1. **analysis::morel::transitive-closure-cardinality-mismatch**
   - Purpose: Root cause analysis
   - Reference: Phase 3b investigation
   - Reusability: Reference for similar boundary problems

2. **strategy::morel::phase3b-architectural-fix**
   - Purpose: Three options with trade-offs
   - Reference: Decision documentation
   - Reusability: Pattern for future architectural decisions

3. **implementation::morel::phase1-transitive-closure**
   - Purpose: Original implementation plan
   - Reference: Pattern understanding
   - Reusability: Foundation for Phases 3-4

### Rationale
- **ChromaDB**: Permanent knowledge base for concepts, decisions, patterns
- **Memory Bank**: Temporary session state for active work
- **Separation**: Clear distinction between persistent and ephemeral knowledge

### Handoff Benefits
- Future sessions can reference ChromaDB for context
- Pattern recognized: compile-time/runtime boundaries appear across languages
- Enables faster decision-making on similar issues

---

## Decision 4: Phase Transition After Phase 3b-Final

**Date**: 2026-01-24
**Decision**: Next phase determined by Phase 3b-Final performance evaluation
**Status**: DECIDED (Conditional)

### Possible Paths

#### Path A: Phase 3c (Quality Assurance) - If Performance Acceptable
**Timeline**: 1-2 days
**Scope**: Comprehensive testing, edge cases, property-based tests
**Outcome**: Feature complete with full test coverage

#### Path B: Phase 4 (Mode Analysis) - If Optimization Needed
**Timeline**: 2-3 weeks
**Scope**: Full mode inference and smart predicate ordering
**Outcome**: Optimized transitive closure with minimal overhead

#### Path C: Phase 3b Revisit - If Option 1 Insufficient
**Timeline**: TBD (escalation)
**Scope**: Switch to Option 2 or Option 3
**Outcome**: Depends on chosen option

### Decision Criteria
After Phase 3b-Final:
1. **Measure**: Performance of cartesian product approach
2. **Benchmark**: How much slower than direct inversion would be?
3. **Acceptable**: If < 10x slower, maybe acceptable
4. **Threshold**: If > 10x slower, plan optimization

### Who Decides
**Decision Authority**: Development lead + code review expert
**Review Process**: Performance review + stakeholder assessment
**Timeline**: Same day as Phase 3b-Final completion

---

## Decision 5: Bead Structure for Phase 3b-Final

**Date**: 2026-01-24
**Decision**: Use 4 task beads + 1 epic bead
**Status**: DECIDED, Ready to Create

### Rationale
- **Task 1 (Verify)**: Discovery task, establishes baseline
- **Task 2 (Implement)**: Core implementation
- **Task 3 (Test)**: Validation and quality assurance
- **Task 4 (Document)**: Final documentation and decision record

### Benefits
- Clear task boundaries
- Natural workflow (serial dependencies)
- Easy to track progress
- Quality gates between tasks

### Alternative Considered
- Single large bead: Less tracking granularity
- 5+ beads: Over-granulated for simple fix
- Chosen: 4 beads provides good balance

---

## Decision Summary Table

| Decision | Date | Status | Impact | Reference |
|----------|------|--------|--------|-----------|
| Option 1 for Phase 3b-Final | 2026-01-24 | DECIDED | Unblocks testing in 2-4hrs | PHASE-3B-CARDINALITY-FIX.md |
| Keep deferred grounding | 2026-01-24 | DECIDED | Defensive measure (low risk) | Expander.java:95-111 |
| ChromaDB knowledge integration | 2026-01-24 | DECIDED | Future reference, pattern reuse | ChromaDB docs |
| Phase transition conditional | 2026-01-24 | DECIDED | Performance-driven next phase | EXECUTION_STATE.md |
| 4-task bead structure | 2026-01-24 | DECIDED | Clear workflow, good tracking | PHASE-3B-BEADS.md |

---

## Lessons Learned (From Phase 3b Analysis)

### Lesson 1: Compile-Time/Runtime Boundary
**Insight**: Some patterns can't be inverted at compile time
**Examples**: SQL (WITH RECURSIVE), Datalog (fixed-point), Functional logic (Datafun)
**Application**: Recognize these patterns early, have fallback strategies

### Lesson 2: Information Boundary
**Insight**: Compile-time inversions work only if all information available at compile time
**Example**: Can't invert `edge(x, y)` because `edges` binding only at runtime
**Application**: Track what's known at compile vs runtime

### Lesson 3: Defensive Programming
**Insight**: It's OK to have fallback to less-optimal but correct behavior
**Example**: Cartesian product slower but always correct
**Application**: Safe fallback > aggressive optimization that fails

### Lesson 4: Architectural Issues Need Strategic Response
**Insight**: Don't patch symptoms, understand root cause
**Example**: Creating infinite extent wasn't just a bug, fundamental mismatch
**Application**: Deep analysis before implementation, three options evaluated

---

## Future Decision Points

### Decision TBD: Performance Acceptable?
**Trigger**: After Phase 3b-Final completion
**Options**:
- A: Performance acceptable, move to Phase 3c
- B: Need optimization, plan Option 2/3
- C: Unexpected issue, escalate

### Decision TBD: Mode Analysis Scope
**Trigger**: If Option 2 chosen
**Options**:
- A: Basic mode analysis (minimal)
- B: Full mode analysis with filter push-down
- C: Magic sets and semi-naive evaluation (Phase 4b)

### Decision TBD: Unified Framework
**Trigger**: After Phase 4 completion
**Options**:
- A: Morel-specific implementation
- B: Extract to general predicate inversion library
- C: Contribute patterns back to Datalog/Flix communities

---

## Decision Rollback Procedure

### If Option 1 Fails

**Scenario**: Cardinality check insufficient, tests fail

**Rollback Steps**:
1. Revert PredicateInverter.java changes
2. Evaluate Option 2 (mode analysis) more carefully
3. Design comprehensive solution
4. Submit to plan-auditor for review

**Timeline**: If needed, same day escalation

### If Phase 3b-Final Blocked

**Escalation Path**:
1. Notify development lead (decision maker)
2. Review root cause
3. Determine: Technical issue vs architectural issue
4. If technical: Assign to java-debugger
5. If architectural: Form design review board

---

## Updates & Amendments

### Amendment 1: Critical Review Revision - Evidence-Driven Path Forward
**Date**: 2026-01-24 (13:30 UTC)
**Reason**: Substantive-critic identified architectural conflicts and missing decision criteria
**Change**: Pivot from single Option 1 path to 5-phase evidence-based approach

#### What Was Identified
1. **Conflicting Decisions**: Option 1 (quick fix) and Mode Analysis (comprehensive) are mutually exclusive, but treated as compatible
2. **Missing Criteria**: No performance threshold defined for "when is Option 1 acceptable?"
3. **Unfair Comparison**: LinkCode approach (Option 3) dismissed without fair evaluation
4. **Unvalidated Claims**: "Mode analysis proven" in Mercury, but SML is different paradigm
5. **Scope Creep Risk**: 3-4 week mode analysis investment without cost-benefit analysis

#### New Path: 5-Phase Evidence-Based Approach

**Phase 1: Option 1 Implementation** (2-4 hours)
- Add cardinality check before building Relational.iterate
- Proven, safe fallback to cartesian product
- Unblocks testing immediately
- No risk

**Phase 2: Define Performance Criteria** (1 hour)
- What's "acceptable" for cartesian product?
- Define thresholds: simple (< 100 edges), medium (100-1000), large (1000+)
- When do we trigger optimization (Option 2 or 3)?
- Establish objective decision threshold

**Phase 3: Research Mode Analysis (Option 2)** (2-3 days)
- How do Mercury/HAL implement modes?
- Can SML's type system support mode annotations?
- User experience: What would developers see?
- Realistic implementation estimate
- Deliverable: Mode-Analysis-Design.md

**Phase 4: Research LinkCode Approach (Option 3)** (2-3 days)
- How does Morel's LinkCode work currently?
- How do SQL databases handle `WITH RECURSIVE`?
- Could LinkCode be extended for predicates?
- Fair comparison to Mode Analysis
- Deliverable: LinkCode-Pattern-Design.md

**Phase 5: Informed Architectural Decision** (1 day)
- Choose Option 2 (Mode Analysis) OR Option 3 (LinkCode)
- Based on design complexity, fit with Morel, user experience
- Document decision rationale
- Create implementation plan for chosen approach

#### Decision Criteria Applied
- **Evidence > Ideology**: Both Option 2 and 3 deserve equal research
- **Objective Thresholds**: Don't guess about performance acceptance
- **Fair Comparison**: Don't dismiss alternatives without design review
- **Incremental Unblocking**: Phase 1 unblocks testing, Phase 5 chooses optimization

#### Risk Assessment
- **Phase 1 Risk**: LOW (proven approach)
- **Phases 3-5 Risk**: Mitigated by structured research
- **Overall Risk**: REDUCED by not committing prematurely

#### Status
- Phase 1-2: Ready to execute immediately
- Phase 3-5: Dependent on Phase 1 completion
- Tasks created: 5 beads tracking all phases
- Timeline: Phase 1-2 complete 2026-01-25; decision point after Phase 1 baseline established

---

## Decision 6: Phase 2-5 Implementation Plan Created

**Date**: 2026-01-24 (Late)
**Status**: PLAN COMPLETE - Awaiting Execution
**Decision**: Detailed 4-5 day implementation plan for evidence-based option selection

### Summary

Following Amendment 1's 5-phase approach, a comprehensive implementation plan has been created:

- **Phase 2** (1 day): Define quantitative performance criteria
- **Phase 3** (2-3 days): Research Mode Analysis approach
- **Phase 4** (2-3 days): Research LinkCode Pattern approach (PARALLEL with Phase 3)
- **Phase 5** (1 day): Make informed decision with decision matrix

### Key Plan Features

1. **Parallel Execution**: Phases 3 and 4 run simultaneously
2. **Objective Criteria**: Decision matrix with weighted scoring
3. **Prototype Validation**: Both approaches prototyped before decision
4. **Clear Handoffs**: Specifications for each phase transition

### Document Location

Full plan: `.pm/PHASE-2-5-IMPLEMENTATION-PLAN.md`

### Timeline

- Total Duration: 4-5 days
- Critical Path: Phase 2 -> max(Phase 3, Phase 4) -> Phase 5

### Next Action

Execute Phase 2: Define Performance Criteria

---

**Last Updated**: 2026-01-24
**Maintained By**: Orchestrator Agent
**Next Review**: After Phase 2 completion
**Stakeholders**: Development lead, code review expert, plan auditor

---

## How to Use This Log

**For New Sessions**:
1. Check this file for recent decisions
2. Understand rationale for current approach
3. Know what's been considered and why

**For Code Reviews**:
1. Reference decisions when discussing design
2. Link to analysis documents
3. Maintain consistency with previous decisions

**For Future Work**:
1. Check Decision Summary Table for quick reference
2. Read full decision details for context
3. Understand trade-offs and alternatives
