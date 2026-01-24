# Phase 6: Production Integration Plan

**Date Created**: 2026-01-24
**Author**: strategic-planner
**Status**: DRAFT - Pending Audit
**Version**: 1.0

---

## Executive Summary

Phase 6 moves the transitive closure predicate inversion feature from validated prototype (Phase 5) to production-ready system. This phase covers code cleanup, documentation, extended testing, performance validation, and main branch integration.

**Duration**: 6-10 days
**Entry Criteria**: Phase 5d complete with GO decision
**Exit Criteria**: Feature merged to main branch, production-ready

---

## 1. Phase 6 Overview

### 1.1 Objective

Transform the validated Phase 5d prototype into a production-quality feature ready for inclusion in the Morel main branch and release.

### 1.2 Scope

**In Scope**:
- Code cleanup and polish (debug removal, comments)
- Comprehensive documentation (user, developer, API)
- Extended regression test suite (30-40 tests)
- Performance profiling and baseline
- Main branch merge and release preparation

**Out of Scope** (deferred to Phase 6a-6d):
- General disjunction union (non-TC orelse patterns)
- Mutual recursion support
- Advanced join patterns
- Performance optimizations beyond baseline

### 1.3 Success Criteria

| Criterion | Target | Measurement |
|-----------|--------|-------------|
| Test Pass Rate | 100% | 30-40 tests all green |
| Code Coverage | >= 80% | JaCoCo report |
| Code Review | >= 8.5/10 | code-review-expert rating |
| Performance | < 10% regression | Benchmark vs Phase 5d |
| Documentation | 100% complete | All guides published |
| Regressions | Zero | Full test suite pass |

---

## 2. Entry Gate: Phase 5d Validation

### 2.1 Prerequisites

Before Phase 6 begins, Phase 5d must achieve:

| Criterion | Status | Evidence |
|-----------|--------|----------|
| C6: Output [(1,2),(2,3),(1,3)] | REQUIRED | such-that.smli test |
| C7: No regression (159/159 pass) | REQUIRED | Full test suite |
| 24/24 Phase 5c tests | REQUIRED | Test report |
| Code coverage >= 80% | REQUIRED | JaCoCo |
| Team confidence >= 90% | REQUIRED | Assessment |

### 2.2 Gate Decision

**GO Criteria**:
- All 5 prerequisites met
- Code review >= 8.0/10
- No P0/P1 bugs outstanding

**NO-GO Criteria**:
- Any prerequisite not met
- Critical bugs discovered in Phase 5d
- Performance regression > 20%

---

## 3. Phase 6 Tasks

### 3.1 Phase 6.1: Pre-Production Cleanup (0.5-1 day)

**Objective**: Polish code for production quality

**Deliverables**:
1. Remove all debug logging from PredicateInverter.java
2. Add comprehensive inline comments explaining:
   - Transitive closure detection algorithm
   - Cardinality boundary handling
   - Core.Apply generation logic
3. Ensure consistent code style (checkstyle pass)
4. Final code review with code-review-expert

**Acceptance Criteria**:
- [ ] No System.err.println debug statements remain
- [ ] All public methods have Javadoc
- [ ] Key algorithm sections have explanatory comments
- [ ] Checkstyle clean
- [ ] Code review >= 8.5/10

**Dependencies**: Phase 5d complete (morel-aga closed)

**Agent Assignment**: java-developer

### 3.2 Phase 6.2: Documentation (1-2 days)

**Objective**: Create comprehensive documentation for users and maintainers

**Deliverables**:

1. **Implementation Guide** (for maintainers)
   - Architecture overview
   - Key classes and their responsibilities
   - Data flow diagram
   - Extension points

2. **User Guide** (for SML query writers)
   - Which patterns are automatically optimized
   - Example queries with transitive closure
   - Troubleshooting: patterns that don't match
   - Performance expectations

3. **API Documentation** (Javadoc)
   - PredicateInverter class documentation
   - InversionResult class documentation
   - Public method documentation

**Acceptance Criteria**:
- [ ] Implementation guide: 5-10 pages
- [ ] User guide: 3-5 pages with examples
- [ ] Javadoc coverage: 100% public API
- [ ] Documentation review passed

**Dependencies**: Phase 5d complete (morel-aga closed)

**Agent Assignment**: strategic-planner (coordination), java-developer (technical content)

### 3.3 Phase 6.3: Regression Suite Expansion (1-2 days)

**Objective**: Expand test coverage from 24 to 30-40 tests

**Current State**: 24 tests from Phase 5c

**New Test Categories**:

1. **Backward Compatibility Tests** (6 tests)
   - Existing queries without transitive closure still work
   - Non-TC recursive functions unchanged
   - FROM expressions without predicates

2. **Unsupported Pattern Tests** (4 tests)
   - Graceful degradation for non-TC orelse
   - Clear error message for complex recursion
   - Fallback to standard evaluation

3. **Edge Cases** (6 tests)
   - Empty edge collection
   - Self-loops in graph
   - Single-node graph
   - Disconnected components
   - Large graph (100+ edges)
   - Deep transitive closure (10+ hops)

**Acceptance Criteria**:
- [ ] 30-40 tests total
- [ ] All tests passing
- [ ] Coverage report shows >= 80%
- [ ] Test names follow conventions

**Dependencies**: Phase 5d complete (morel-aga closed)

**Agent Assignment**: test-validator

### 3.4 Phase 6.4: Performance Profiling (2-3 days)

**Objective**: Establish performance baseline and validate no regression

**Deliverables**:

1. **Performance Baseline Report**
   - Compilation time for TC patterns
   - Execution time for TC queries
   - Memory allocation patterns

2. **Benchmark Suite**
   - 10-node graph baseline
   - 100-node graph scalability
   - 1000-node graph stress test

3. **JIT Analysis**
   - Warm-up behavior
   - Steady-state performance
   - GC impact analysis

**Performance Targets**:
| Metric | Target | Notes |
|--------|--------|-------|
| Compile time | < 100ms | TC pattern detection |
| 10-node TC | < 10ms | Baseline graph |
| 100-node TC | < 100ms | Medium graph |
| 1000-node TC | < 1s | Stress test |
| Memory overhead | < 10% | vs non-TC query |

**Acceptance Criteria**:
- [ ] All performance targets met
- [ ] Baseline report documented
- [ ] Benchmark suite reproducible
- [ ] No performance regression vs Phase 5d

**Dependencies**: Phase 6.3 complete

**Agent Assignment**: java-developer (benchmarks), code-review-expert (analysis)

### 3.5 Phase 6.5: Main Branch Integration (1-2 days)

**Objective**: Merge feature to main branch and prepare for release

**Deliverables**:

1. **Merge Preparation**
   - Rebase on latest main
   - Resolve any conflicts
   - Final test suite run

2. **Pull Request**
   - Comprehensive description
   - Link to documentation
   - Test evidence

3. **Release Preparation**
   - Update CHANGELOG.md
   - Version bump consideration
   - Release notes draft

**Acceptance Criteria**:
- [ ] PR approved by maintainer
- [ ] CI pipeline green
- [ ] No merge conflicts
- [ ] Release notes complete

**Dependencies**: Phase 6.1, 6.2, 6.3, 6.4 all complete

**Agent Assignment**: java-developer (PR), code-review-expert (final review)

---

## 4. Dependency Graph

```
Phase 5d (morel-aga)
    |
    | [GATE CHECK]
    |
+---+---+---+
|   |   |   |
v   v   v   |
6.1 6.2 6.3 |  (parallel: cleanup, docs, tests)
|   |   |   |
+---+---+---+
    |
    v
   6.4        (performance - needs tests)
    |
    v
   6.5        (merge - needs all)
    |
    v
PRODUCTION READY
```

**Critical Path**: 5d -> 6.1 -> 6.4 -> 6.5 (7-9 days)

**Parallel Opportunities**:
- 6.1, 6.2, 6.3 can all run in parallel after gate
- 6.4 needs 6.3 (tests for benchmarking)
- 6.5 needs everything

---

## 5. Bead Specifications

### 5.1 Epic Bead

```
ID: morel-phase6
Type: epic
Priority: P0
Title: Phase 6 - Production Integration

Description:
Move transitive closure predicate inversion from validated prototype
to production system. Includes code cleanup, documentation, extended
testing, performance validation, and main branch integration.

Context: .pm/PHASE-6-PRODUCTION-PLAN.md, .pm/CONTEXT_PROTOCOL.md

Success Criteria:
- Feature merged to main branch
- 30-40 tests passing
- Documentation complete
- Performance baseline established
```

### 5.2 Task Beads

#### 5.2.1 Phase 6 Gate Check

```
ID: morel-6gate
Type: task
Priority: P0
Title: Phase 6 Gate Check - Validate Phase 5d Complete

Description:
Verify Phase 5d completion and GO criteria before proceeding with
Phase 6. Check: tests passing, coverage met, review approved,
team confidence >= 90%.

Dependencies: morel-aga (Phase 5d)

Acceptance Criteria:
- [ ] C6: Output = [(1,2),(2,3),(1,3)]
- [ ] C7: 159/159 baseline tests pass
- [ ] 24/24 Phase 5c tests pass
- [ ] Coverage >= 80%
- [ ] Code review >= 8.0/10

Duration: 0.5 day
Agent: java-developer
```

#### 5.2.2 Pre-Production Cleanup

```
ID: morel-6.1
Type: task
Priority: P1
Title: Phase 6.1 - Pre-Production Cleanup

Description:
Polish PredicateInverter code for production: remove debug logging,
add comprehensive comments, ensure checkstyle compliance, final
code review.

Dependencies: morel-6gate

Acceptance Criteria:
- [ ] No System.err.println debug statements
- [ ] All public methods have Javadoc
- [ ] Algorithm sections have explanatory comments
- [ ] Checkstyle clean
- [ ] Code review >= 8.5/10

Duration: 0.5-1 day
Agent: java-developer
```

#### 5.2.3 Documentation

```
ID: morel-6.2
Type: task
Priority: P1
Title: Phase 6.2 - Documentation

Description:
Create comprehensive documentation: implementation guide (maintainers),
user guide (SML writers), API documentation (Javadoc).

Dependencies: morel-6gate

Acceptance Criteria:
- [ ] Implementation guide: 5-10 pages
- [ ] User guide: 3-5 pages with examples
- [ ] Javadoc: 100% public API coverage
- [ ] Documentation review passed

Duration: 1-2 days
Agent: strategic-planner, java-developer

Knowledge Base Search Terms:
- "transitive closure documentation"
- "predicate inversion user guide"
- "Morel query optimization guide"
```

#### 5.2.4 Regression Suite Expansion

```
ID: morel-6.3
Type: task
Priority: P1
Title: Phase 6.3 - Regression Suite Expansion

Description:
Expand test coverage from 24 to 30-40 tests. Add: backward
compatibility tests, unsupported pattern tests, edge case tests.

Dependencies: morel-6gate

Acceptance Criteria:
- [ ] 30-40 tests total
- [ ] All tests passing
- [ ] Coverage >= 80%
- [ ] Test names follow conventions

Duration: 1-2 days
Agent: test-validator

Test Categories:
- Backward compatibility (6)
- Unsupported patterns (4)
- Edge cases (6)
```

#### 5.2.5 Performance Profiling

```
ID: morel-6.4
Type: task
Priority: P1
Title: Phase 6.4 - Performance Profiling

Description:
Establish performance baseline. Create benchmark suite, measure
compile/runtime, analyze JIT behavior, document baseline.

Dependencies: morel-6.3

Acceptance Criteria:
- [ ] Performance targets met
- [ ] Baseline report documented
- [ ] Benchmark suite reproducible
- [ ] No regression vs Phase 5d

Performance Targets:
- Compile: < 100ms
- 10-node TC: < 10ms
- 100-node TC: < 100ms
- 1000-node TC: < 1s
- Memory overhead: < 10%

Duration: 2-3 days
Agent: java-developer, code-review-expert
```

#### 5.2.6 Main Branch Integration

```
ID: morel-6.5
Type: task
Priority: P1
Title: Phase 6.5 - Main Branch Integration

Description:
Merge feature to main branch. Rebase, resolve conflicts, create PR,
update CHANGELOG, prepare release notes.

Dependencies: morel-6.1, morel-6.2, morel-6.3, morel-6.4

Acceptance Criteria:
- [ ] PR approved
- [ ] CI pipeline green
- [ ] No merge conflicts
- [ ] Release notes complete

Duration: 1-2 days
Agent: java-developer, code-review-expert
```

---

## 6. Timeline Summary

| Phase | Duration | Start | End | Dependencies |
|-------|----------|-------|-----|--------------|
| 5d (current) | 3-5d | In progress | Day 0 | - |
| 6-gate | 0.5d | Day 0 | Day 0.5 | 5d |
| 6.1 | 0.5-1d | Day 0.5 | Day 1.5 | 6-gate |
| 6.2 | 1-2d | Day 0.5 | Day 2.5 | 6-gate |
| 6.3 | 1-2d | Day 0.5 | Day 2.5 | 6-gate |
| 6.4 | 2-3d | Day 2.5 | Day 5.5 | 6.3 |
| 6.5 | 1-2d | Day 5.5 | Day 7.5 | 6.1,6.2,6.3,6.4 |
| **Total Phase 6** | **6-10 days** | | | |

**Critical Path**: 5d -> 6-gate -> 6.3 -> 6.4 -> 6.5 = 8-12 days

---

## 7. Risk Assessment

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|-----------|
| Phase 5d fails to complete | LOW | HIGH | Fallback documented (B6) |
| Performance regression | MEDIUM | MEDIUM | Early benchmarking in 6.4 |
| Documentation incomplete | LOW | LOW | Template-based approach |
| Merge conflicts | LOW | MEDIUM | Frequent rebases |
| Test failures in expanded suite | MEDIUM | MEDIUM | Incremental test addition |

---

## 8. Future Phases (Roadmap)

### 8.1 Phase 6a: General Disjunction Union (3-5 days)

**Objective**: Support non-recursive orelse patterns via union

**Trigger**: User demand for logic.smli-style patterns

**Scope**:
- Detect when orelse has two invertible branches
- Invert each branch separately
- Union the results

### 8.2 Phase 6b: Mutual Recursion Support (5-7 days)

**Objective**: Support mutually recursive functions

**Trigger**: Graph algorithm use cases

**Scope**:
- Detect mutual recursion pattern
- Generate combined iterate step

### 8.3 Phase 6c: Advanced Join Patterns (4-6 days)

**Objective**: Support complex join conditions

**Trigger**: Performance optimization needs

**Scope**:
- Arithmetic joins (where x + 1 = z)
- Comparison joins
- Multi-column joins

### 8.4 Phase 6d: Performance Optimizations (Ongoing)

**Objective**: Continuous performance improvement

**Trigger**: User feedback, profiling data

**Scope**:
- Parallel iterate steps
- Index-based joins
- Incremental memoization

---

## 9. Team Allocation

| Role | Phase 6 Tasks | Estimated Hours |
|------|---------------|-----------------|
| java-developer | 6.1, 6.2 (partial), 6.4, 6.5 | 20-30h |
| code-review-expert | 6.1 review, 6.4 analysis, 6.5 review | 8-12h |
| test-validator | 6.3 | 8-16h |
| strategic-planner | 6.2 coordination, overall oversight | 4-8h |

**Total Phase 6 Effort**: 40-66 hours (6-10 days)

---

## 10. Quality Gates

### 10.1 Gate 1: Phase 6 Entry (after 5d)

| Check | Required |
|-------|----------|
| Phase 5d tests pass | YES |
| Coverage >= 80% | YES |
| Code review >= 8.0/10 | YES |
| No P0/P1 bugs | YES |

### 10.2 Gate 2: Pre-Merge (before 6.5)

| Check | Required |
|-------|----------|
| 6.1-6.4 all complete | YES |
| 30-40 tests pass | YES |
| Performance targets met | YES |
| Documentation complete | YES |
| Code review >= 8.5/10 | YES |

### 10.3 Gate 3: Production Release (after 6.5)

| Check | Required |
|-------|----------|
| PR approved | YES |
| CI pipeline green | YES |
| Release notes published | YES |
| No critical bugs | YES |

---

## 11. Monitoring and Reporting

### 11.1 Daily Status

Track:
- Tasks in progress
- Blockers identified
- Hours consumed vs budget
- Test pass rate

### 11.2 Escalation Triggers

| Level | Trigger | Action |
|-------|---------|--------|
| Yellow | Any task > 1.5x estimated | Notify stakeholder |
| Orange | Total > 8 days | Scope review |
| Red | Total > 10 days | GO/NO-GO evaluation |

---

## 12. Appendix: Key Files

| File | Purpose |
|------|---------|
| PredicateInverter.java | Main implementation |
| Extents.java | Integration point |
| such-that.smli | Primary test file |
| Phase5aValidationTest.java | Comprehensive tests |
| .pm/CONTINUATION.md | Session state |
| .pm/PHASE-6-PRODUCTION-PLAN.md | This plan |

---

## 13. Approval

**Plan Status**: DRAFT - Pending Audit

**Required Approvals**:
- [ ] plan-auditor review
- [ ] User/stakeholder review

**Next Action**: Submit to plan-auditor for comprehensive review

---

**Document Version**: 1.0
**Last Updated**: 2026-01-24
