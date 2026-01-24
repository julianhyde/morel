# Phase 3-4 Orchestration Summary: Predicate Inversion Implementation

## Quick Reference

**Project**: Morel Predicate Inversion (Issue #217)
**Current Status**: Phase 1-2 Complete (159 tests passing)
**Next Target**: Phase 3 - Full Relational.iterate Generation
**Timeline**: ~4 weeks (3 sprints for Phase 3, 2 sprints for Phase 4)
**Owner**: Strategic Planning & Implementation Pipeline

---

## What's Complete (Phase 1-2)

✅ Pattern Detection: Recognizes invertible predicates (elem, ranges, string ops)
✅ Recursive Function Detection: Identifies transitive closure patterns
✅ Partial Inversion: Handles cases with known bounds
✅ Test Coverage: 159 tests passing in such-that.smli

**Key Files**:
- `src/main/java/net/hydromatic/morel/compile/PredicateInverter.java` (1033 lines)
- `src/test/resources/script/such-that.smli` (37KB test suite)
- `PREDICATE_INVERSION_DESIGN.md` (Detailed specification)

---

## What's Needed (Phase 3-4)

### Phase 3: Full Relational.iterate Generation

**Goal**: Transform transitive closure predicates into optimized Relational.iterate generators

**Example**:
```sml
from p where path p
→ Relational.iterate edges (fn (old, new) => join new edges)
```

**Three Sub-Epics**:
1. **3a** (PPT Construction): Extract & pattern match recursive structures
2. **3b** (Tabulation): Build iteration infrastructure with proper variable scoping
3. **3c** (Code Gen): Generate and validate Relational.iterate expressions

**Success**: dummy.smli transitive closure test passes, 159+ tests still pass

---

### Phase 4: Mode Analysis (Optional but Valuable)

**Goal**: Enable predicates to generate in multiple "directions" (modes)

**Example Modes**:
```sml
path(1, y) → find reachable destinations from 1
path(x, 5) → find reachable sources to 5
path(x, y) → find all transitive pairs
```

**Two Sub-Epics**:
1. **4a** (Mode Analysis): Implement mode inference (based on Hanus paper)
2. **4b** (Smart Selection): Use modes to select optimal generators

**Success**: Multi-mode tests pass, 159+ tests still pass

---

## Documents Created

### Strategic Planning
1. **STRATEGIC_PLAN_PHASES_3-4.md** (This directory)
   - Executive summary
   - Architecture breakdown
   - Dependency graph
   - Risk mitigation
   - Success criteria

2. **BEADS_DEFINITION_PHASES_3-4.yaml** (This directory)
   - 18 actionable beads for Phases 3-4
   - Dependencies and priorities
   - Acceptance criteria for each bead
   - Knowledge base references
   - Sprint schedule

3. **TESTING_STRATEGY_PHASES_3-4.md** (This directory)
   - Test categories (unit, integration, script)
   - Performance benchmarks
   - Quality gates
   - Test data sets
   - CI/CD workflow

4. **This Document**: Quick orchestration reference

---

## Bead Breakdown

### Phase 3 (12 beads, ~2-3 weeks)

**Sub-Epic 3a: PPT Construction** (3 beads)
- 3a-EXTRACT: Extract base & recursive cases
- 3a-JOINVAR: Identify join variables
- 3a-PATTERN: Implement pattern matcher

**Sub-Epic 3b: Tabulation** (4 beads)
- 3b-SUBST: Variable substitution
- 3b-INCREMENT: Incremental computation model
- 3b-JOIN: Join expression generation
- 3b-TUPLE: Tuple composition

**Sub-Epic 3c: Code Gen** (4 beads)
- 3c-ITERATE: Generate Relational.iterate
- 3c-COMPILE: Validate compilation
- 3c-TRANSITIVE: Test dummy.smli
- 3c-REGRESSION: Regression test suite

### Phase 4 (7 beads, ~2-3 weeks, conditional on Phase 3 success)

**Sub-Epic 4a: Mode Analysis** (4 beads)
- 4a-MODE-DATA: Mode data structures
- 4a-INFER: Mode inference algorithm
- 4a-INTEGRATE: Integration with PredicateInverter
- 4a-MULTI: Multiple modes support

**Sub-Epic 4b: Smart Selection** (3 beads)
- 4b-SELECT: Mode-based generator selection
- 4b-SCENARIO: Multi-mode scenarios
- 4b-SUITE: Regression tests with multi-mode

---

## Implementation Pipeline

### Agent Assignments

**Phase 3a-3b: Core Algorithm**
- Primary: java-developer (with opus or sonnet for complex logic)
- Support: codebase-deep-analyzer (for understanding existing code)
- Review: code-review-expert (after each epic)

**Phase 3c: Validation**
- Primary: java-developer
- Testing: test-validator (comprehensive testing)
- Review: code-review-expert

**Phase 4a: Mode Inference**
- Primary: java-developer (with opus for algorithm design)
- Research: deep-research-synthesizer (if Hanus paper details needed)
- Review: code-review-expert

**Phase 4b: Integration**
- Primary: java-developer
- Testing: test-validator
- Review: code-review-expert

---

## Sprint Schedule

### Sprint 1: Phase 3a (3-4 days)
```
Mon-Wed: 3a-EXTRACT, 3a-JOINVAR
Thu:     3a-PATTERN, code review
Fri:     Fix issues, merge to main
```

**Deliverable**: Pattern matching working, unit tests pass

### Sprint 2: Phase 3b (4-5 days)
```
Mon-Tue:  3b-SUBST, 3b-INCREMENT
Wed-Thu:  3b-JOIN, 3b-TUPLE
Fri:      Integration testing, code review
```

**Deliverable**: Iteration infrastructure working, join generation passes

### Sprint 3: Phase 3c (3-4 days)
```
Mon:  3c-ITERATE, 3c-COMPILE
Tue:  3c-TRANSITIVE (dummy.smli)
Wed:  3c-REGRESSION (all 159 tests)
Thu:  Code review, validation
```

**Deliverable**: Transitive closure test passes, all 159 tests pass

### Quality Gate 1: Phase 3 Review (2 days)
- Code review all Phase 3 code
- Performance benchmarking
- Documentation update
- **Gate**: code-review-expert approval

### Sprint 4: Phase 4a (4-5 days)
```
Mon-Tue:  4a-MODE-DATA, 4a-INFER
Wed-Thu:  4a-INTEGRATE, 4a-MULTI
Fri:      Unit tests, code review
```

**Deliverable**: Mode system working, inference tests pass

### Sprint 5: Phase 4b (3-4 days)
```
Mon-Tue:  4b-SELECT, 4b-SCENARIO
Wed:      4b-SUITE (regression tests)
Thu:      Code review, validation
```

**Deliverable**: Multi-mode generation working, 159+ tests pass

### Quality Gate 2: Phase 4 Review & Release (2 days)
- Comprehensive testing
- Performance validation
- Decision: Phase 4c needed? (→ deferred or implement)
- **Gate**: test-validator & code-review-expert approval

---

## Quality Gates

### Gate 1: After Phase 3c
**Criteria**:
- ✅ All 159 existing tests pass
- ✅ dummy.smli transitive closure passes
- ✅ Generated code type-correct
- ✅ Code review approved
- ✅ Documentation updated
- ✅ Performance regression < 10%

**Owner**: code-review-expert

---

### Gate 2: After Phase 4b
**Criteria**:
- ✅ Multi-mode generation working
- ✅ All 159+ tests pass
- ✅ Mode analysis infrastructure sound
- ✅ Code review approved
- ✅ Performance acceptable
- ✅ Ready for release

**Owner**: test-validator

---

### Gate 3: Phase 4c Decision (Optional)
**Criteria**:
- Performance metrics from Phase 4b
- Benchmarks against baseline
- Decision: Implement magic sets or mark as "won't implement"

**Owner**: strategic-planner

---

## Knowledge Base Integration

### Required Documents

1. **algorithm-synthesis-ura-to-morel.md**
   - Used for: Phase 3a (PPT), 3b (tabulation)
   - Maps Abramov-Glück URA to Morel implementation

2. **mode-analysis-synthesis.md**
   - Used for: Phase 4a (mode inference)
   - Maps Hanus mode system to Morel

3. **complete-knowledge-base-summary.md**
   - Master reference for all papers
   - Cross-references and correlations

4. **predicate-inversion-knowledge-integration.md**
   - Technical depth and paper details
   - Implementation patterns and gotchas

### Research Papers

- **Abramov & Glück (2000)**: URA - PPT construction algorithm
- **Hanus (2022)**: Mode inference and functional-logic integration
- **Datafun (2016)**: Fixed-point iteration theory
- **Flix (Madsen 2024)**: Magic sets and optimization (Phase 4c)

---

## Key Implementation Patterns

### 1. Pattern Matching
```java
// In PredicateInverter.matchRecursivePattern()
// Match: P1 orelse (exists z where P2 andalso f(...))
// Return: RecursivePattern with baseCase, stepCase, joinVars
```

### 2. Tabulation Model
```java
// Step function signature for Relational.iterate
fn (previousResults, currentResults) =>
  from ... in previousResults, ... in baseGenerator
  where joinCondition
  yield outputTuple
```

### 3. Mode Inference
```java
// Apply Hanus rules iteratively
// Mode vector: Map<Variable, {IN|OUT|BOTH|IGNORE}>
// Fixed-point until convergence
```

---

## Risk Mitigation Summary

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|-----------|
| Pattern not matching arbitrary recursion | M | H | Design flexible pattern matcher, fallback to default |
| Performance regression | M | H | Maintain baseline tests, benchmark each phase |
| Type inference failures | L | H | Validate in each phase, use existing infrastructure |
| Scope creep into Phase 4c | M | M | Mark Phase 4c as OPTIONAL, clear decision point |

---

## How to Start

### Step 1: Validate Plan (This Week)
```bash
# Review plan-auditor validation
bd audit "Phase 3-4 Strategic Plan"

# Create beads in project management
project-management-setup load BEADS_DEFINITION_PHASES_3-4.yaml
```

### Step 2: Assign Agents (This Week)
```bash
# Assign java-developer to Phase 3a
bd assign 3a-EXTRACT java-developer

# Assign codebase-deep-analyzer for context
bd assign 3a-EXTRACT codebase-deep-analyzer

# Review assignments
bd list --status=ready
```

### Step 3: Start Phase 3a (Next Monday)
```bash
# Begin first bead
bd start 3a-EXTRACT

# java-developer begins implementation
/java-implement 3a-EXTRACT

# Daily standup
bd status 3a-EXTRACT
```

---

## Success Criteria

### Phase 3 Complete
✅ All 159 existing tests pass
✅ Transitive closure test (dummy.smli) passes
✅ Generated code compiles and has correct semantics
✅ Code review passes for all Phase 3 beads
✅ Documentation updated with Phase 3 capabilities

### Phase 4 Complete
✅ Mode analysis infrastructure implemented
✅ Multi-mode generation working
✅ All 159+ tests pass
✅ Code review passes for all Phase 4 beads
✅ Performance acceptable for all scenarios

### Overall Success
✅ Issue #217 resolved
✅ Comprehensive test coverage
✅ Maintainable, documented code
✅ Performance meets requirements

---

## Next Actions

### Immediate (Today)
- [ ] Review STRATEGIC_PLAN_PHASES_3-4.md
- [ ] Review BEADS_DEFINITION_PHASES_3-4.yaml
- [ ] Identify questions or concerns

### This Week
- [ ] Plan auditor validates strategic plan
- [ ] Create beads in project management system
- [ ] Assign agents to Phase 3a beads
- [ ] Set up testing infrastructure (new test classes)

### Next Week
- [ ] Start Sprint 1: Phase 3a (PPT Construction)
- [ ] java-developer begins 3a-EXTRACT implementation
- [ ] Establish daily standup cadence
- [ ] First code review checkpoint

---

## Document Reference

| Document | Purpose | Owner | Status |
|----------|---------|-------|--------|
| STRATEGIC_PLAN_PHASES_3-4.md | Full strategic breakdown | Strategic Planner | ✅ Complete |
| BEADS_DEFINITION_PHASES_3-4.yaml | Executable bead definitions | PM Infrastructure | ✅ Complete |
| TESTING_STRATEGY_PHASES_3-4.md | Comprehensive test plan | Test Validator | ✅ Complete |
| PREDICATE_INVERSION_DESIGN.md | Technical specification | (existing) | ✅ Complete |
| CLAUDE.md | Implementation guidelines | (existing) | ✅ Complete |

---

## Questions & Discussion

For questions about:
- **Strategic Plan**: See STRATEGIC_PLAN_PHASES_3-4.md
- **Beads & Assignments**: See BEADS_DEFINITION_PHASES_3-4.yaml
- **Testing Approach**: See TESTING_STRATEGY_PHASES_3-4.md
- **Technical Details**: See PREDICATE_INVERSION_DESIGN.md

Contact: Strategic Planning team

---

## Version History

- **Created**: 2026-01-23
- **Version**: 1.0
- **Status**: Ready for plan-auditor validation
- **Next Review**: After plan validation approval

---

## Appendix: Key File Locations

### Implementation Files
```
src/main/java/net/hydromatic/morel/compile/
  ├── PredicateInverter.java (1033 lines, core implementation)
  ├── ModeSystem.java (NEW - Phase 4a)
  ├── ModeAnalyzer.java (NEW - Phase 4a)
  └── Generator.java (existing, may extend)
```

### Test Files
```
src/test/java/net/hydromatic/morel/compile/
  ├── PredicateInverterTest.java (existing, extend)
  ├── PredicateInverterIntegrationTest.java (NEW - Phase 3b)
  ├── ModeAnalyzerTest.java (NEW - Phase 4a)
  └── PredicateInverterPerformanceTest.java (NEW - benchmarking)

src/test/resources/script/
  ├── such-that.smli (159 tests, extend for Phase 3c & 4b)
  ├── dummy.smli (transitive closure, Phase 3c validation)
  └── mode-analysis-tests.smli (NEW - Phase 4 tests)
```

### Documentation
```
Repository root
  ├── STRATEGIC_PLAN_PHASES_3-4.md (THIS PLAN - full breakdown)
  ├── BEADS_DEFINITION_PHASES_3-4.yaml (executable definitions)
  ├── TESTING_STRATEGY_PHASES_3-4.md (test approach)
  ├── PREDICATE_INVERSION_DESIGN.md (technical spec)
  ├── PHASE_3_4_ORCHESTRATION_SUMMARY.md (THIS FILE - quick ref)
  └── CLAUDE.md (implementation guidelines)
```

---

**Ready to implement Phase 3-4? Start with plan-auditor validation!**
