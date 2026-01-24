# Morel Predicate Inversion: Continuation Context

**Last Updated**: 2026-01-24
**Current Phase**: Phase 2-5 Planning Complete → Phase 2 Ready for Execution
**Status**: Evidence-based implementation plan created for Option 2/3 decision

---

## Current State: Phase 2-5 Implementation Plan

**Key Document**: `.pm/PHASE-2-5-IMPLEMENTATION-PLAN.md`

### Why This Plan Exists

Phase 1 (Option 1 cardinality check) revealed an **architectural blocker**:
- Deferred grounding in Expander is tightly coupled to Relational.iterate
- Simple fallbacks using infinite extents fail at runtime
- This is a compile-time/runtime boundary problem (same as SQL WITH RECURSIVE)

### The 5-Phase Evidence-Based Approach

| Phase | Duration | Status | Description |
|-------|----------|--------|-------------|
| 1 | 2-4 hours | BLOCKED | Option 1 implementation (revealed blocker) |
| 2 | 1 day | READY | Define performance criteria |
| 3 | 2-3 days | PENDING | Research Mode Analysis |
| 4 | 2-3 days | PENDING | Research LinkCode Pattern (PARALLEL with 3) |
| 5 | 1 day | PENDING | Informed decision with matrix |

### Next Immediate Action

**Execute Phase 2**: Define quantitative performance criteria
- Establish input size thresholds (small/medium/large)
- Define latency acceptance criteria
- Create benchmark test harness
- Document decision trigger

### Two Options Being Evaluated

**Option 2: Mode Analysis**
- Track which arguments are inputs vs outputs
- Generate mode-aware code
- Similar to Mercury/Prolog mode declarations

**Option 3: LinkCode Pattern**
- Extend Morel's existing LinkCode mechanism
- Defer base case evaluation to runtime
- Similar to SQL WITH RECURSIVE

---

## What Was Completed

### Phase 1-2: Recursive Function Handling & Pattern Detection (COMPLETE)
- PredicateInverter class created with basic structure
- Graceful handling of recursive functions (no crashes)
- Pattern detection for transitive closure (`baseCase orelse recursiveCase`)
- Base case extraction (e.g., `edge(x,y)` from complex recursive predicate)
- Mode detection (which variables can be generated, which required)
- **Baseline Tests**: 301 passing (verified 2026-01-23)
  - Test breakdown in `.pm/metrics/baseline-phase-2.json`
  - ScriptTest: 32, MainTest: 127, AlgebraTest: 29, PredicateInverterTest: 11, Others: 102
  - Execution time: 18.906 seconds
- Code review approved for Phases 1-2

**Recent Commits**:
- `e032e620` - Eliminate duplicates
- `6921301f` - Add method `Static.transformToMap`
- `0daf6285` - Change signature of method `CoreBuilder.recordPat`

---

## Key Learnings

### L1: Pattern Decomposition
- Transitive closure patterns split cleanly: base case + recursive case
- Base case is directly invertible using existing infrastructure
- Recursive case contains the actual iteration logic

### L2: Mode Tracking
- Essential to track which variables are generated vs required
- Affects how we build joins and dependency resolution
- Will be critical for Phase 4 mode analysis

### L3: Duplicate Management
- Recursive patterns inherently produce duplicates
- Need tabulation + duplicate detection in Phase 3
- Field `mayHaveDuplicates` in InversionResult properly tracks this

---

## Active Hypotheses

### H1: PPT Construction Via Visitor Pattern
- **Status**: Candidate for Phase 3.1
- **Hypothesis**: Visitor pattern enables clean separation of PPT traversal from code generation
- **Validation**: Design review required before implementation
- **Evidence Needed**: Performance analysis, edge case testing

### H2: Greedy Predicate Ordering
- **Status**: Candidate for Phase 4
- **Hypothesis**: Greedy algorithm (order by cardinality) sufficient for good mode selection
- **Validation**: Compare against optimal for 20+ test cases
- **Evidence Needed**: Benchmark results, comparison with mode-based ordering

### H3: Relational.iterate Expressiveness
- **Status**: Planning
- **Hypothesis**: Relational.iterate can express all well-defined recursive predicates
- **Validation**: Test against 5+ different recursive patterns
- **Evidence Needed**: All Phase 3.5 integration tests passing

---

## Current Blockers

**BLOCKING**: Synthesis documents (ETA: 2-3 days)
- Agent a80563e (deep-research-synthesizer) creating 4 synthesis documents
- Once complete: paper accessibility verification (1 day)
- Then: BEADS refinement + load (4 hours)
- Then: Phase 3a design can start (2-3 days)

---

## Next Actions (Priority Order)

### Immediate (Today)

1. **Create Phase 3 beads** (using orchestrator)
   - 6 epic beads as per `phase-3-4-bead-definitions.md`
   - Set dependencies correctly
   - Assign complexity/time estimates

2. **Create Memory Bank project** `morel_active`
   - Copy synthesis documents from synthesis work
   - Organize into phase-specific files
   - Create index

### This Week

3. **Phase 3.1: PPT Design** (2-3 days)
   - Architect designs ProcessTreeNode hierarchy
   - Review with java-architect-planner
   - Validate with plan-auditor

4. **Phase 3.1: PPT Implementation** (3-4 days)
   - java-developer implements classes
   - Tests written first
   - Code review + 85% coverage target

### Next Week

5. **Phase 3.2: Extended Inversion** (3-4 days)
6. **Phase 3.3: Tabulation** (2-3 days)
7. **Phase 3.4: Step Function Generation** (2-3 days)

### Gate: Phase 3.5 Integration (Week 3)

8. **Phase 3.5: Full Relational.iterate Integration** (2 days)
   - Wire everything together
   - Transitive closure test validation
   - Quality gate review

---

## Metrics Summary

| Metric | Value | Target |
|--------|-------|--------|
| Tests Passing | 301/301 | 301+ |
| Test Coverage | ~75% | 85%+ for new code |
| Phase Completion | 2/4 | 4/4 |
| Blockers | 1 (synthesis docs) | 0 |
| Code Review Approved | P1-P2 | P1-P4 |
| Strategic Plan | COMPLETE | ✅ |
| PM Infrastructure | COMPLETE | ✅ |
| Synthesis Docs | IN PROGRESS | ⏳ ETA 2-3 days |

---

## Files Modified in Last Session

- `PredicateInverter.java`: Complete Phase 2 implementation
- `PredicateInverterTest.java`: 7 passing tests
- `.pm/execution_state.json`: Updated phase tracking
- `CLAUDE.md`: Design context updated

---

## Knowledge Base Ready

**Memory Bank**: morel_active (CREATE NEEDED)
- predicate-inversion-knowledge-integration.md (template ready)
- algorithm-synthesis-ura-to-morel.md (template ready)
- mode-analysis-synthesis.md (template ready)
- phase-3-4-bead-definitions.md (template ready)
- phase-3-4-execution-guide.md (template ready)

**ChromaDB**: 6 papers already indexed (from prior work)

**Research**: 5 PDFs available in `/Users/hal.hildebrand/Documents/Predicate Inversion Research/`

---

## For Next Session Resume

**Quickstart Checklist**:
- [ ] Read this file (you are here!)
- [ ] Review `.pm/execution_state.json` for current state
- [ ] Check `bd ready` for available work
- [ ] Read relevant synthesis document section
- [ ] Start work on assigned bead

**If Resuming After Break**:
1. Read CONTEXT_PROTOCOL.md Phase 1 (load context)
2. Run `mvnw test` (verify 159 tests passing)
3. Check memory bank for recent learnings
4. Review last 3 commits in git log
5. Ask architect if unclear

---

## Phase Transition Checklist

**Before Starting Phase 3**:

- [ ] Review PHASES.md for Phase 3 overview
- [ ] Read algorithm-synthesis-ura-to-morel.md
- [ ] Check phase-3-4-bead-definitions.md for epic details
- [ ] All 159 tests passing
- [ ] No compiler warnings
- [ ] Code review approved
- [ ] Performance baseline established

**Phase 3 Success Criteria** (from execution_state.json):
1. Transitive closure test (such-that.smli:737-742) passing
2. All 159 existing tests still passing
3. Code review approved with < 2 requested changes
4. Test coverage >= 85% for new code
5. Performance: Phase 3 queries run in < 10ms

---

## Risk Assessment

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|-----------|
| PPT construction complexity | Medium | High | Early design review + prototyping |
| Mode analysis interaction effects | Medium | Medium | Comprehensive test suite |
| Performance regression | Low | Medium | Benchmark before/after each phase |
| Duplicate detection bugs | Low | High | Dedicated test cases + code review |

---

**Status**: PHASE 2 COMPLETE, READY FOR PHASE 3 PLANNING

**Next Milestone**: Transitive Closure Test Passing (Phase 3.5)

**Expected Duration**: 4-6 weeks total for Phases 3-4
