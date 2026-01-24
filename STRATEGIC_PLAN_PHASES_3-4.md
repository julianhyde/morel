# Morel Predicate Inversion: Strategic Plan for Phases 3-4

## Executive Summary

This document outlines the strategic plan to complete Morel's predicate inversion implementation (Issue #217) across two major phases:

- **Phase 3: Full Relational.iterate Generation** - Implement transitive closure inversion using fixed-point iteration
- **Phase 4: Mode Analysis and Smart Generator Selection** - Add sophisticated mode inference for multi-directional predicates

Status: Phase 1-2 complete (301 tests passing), Phase 3-4 ready for implementation.

---

## Current State (Phase 1-2 Complete)

### What Works
- **Pattern Detection**: Recognizes invertible patterns (elem, ranges, string operations)
- **Recursive Function Detection**: Identifies transitive closure patterns
- **Partial Inversion**: Handles cases with known bounds
- **Test Coverage**: 301 tests passing across all test suites

### Recent Commits
- `1ed6e6dc` - Implement Phase 2: Transitive closure pattern detection
- `a139bf6e` - Handle recursive functions gracefully in predicate inversion
- `624bcf3b` - Handle 'andalso' predicates with multiple terms

### Key Files
- `src/main/java/net/hydromatic/morel/compile/PredicateInverter.java` (1033 lines)
- `src/test/resources/script/such-that.smli` (37KB test suite)
- `PREDICATE_INVERSION_DESIGN.md` (Design specification)

---

## Phase 3: Full Relational.iterate Generation

### Objective
Generate correct, performant `Relational.iterate` expressions for transitive closure predicates.

### Example
Transform this predicate:
```sml
fun path (x, y) = edge (x, y) orelse
  (exists z where edge (x, z) andalso path (z, y))

from p where path p
```

Into this generator:
```sml
Relational.iterate edges
  (fn (old, new) =>
    from (x, z) in edges,
         (z2, y) in new
      where z = z2
      yield (x, y))
```

### Architecture

#### Phase 3a: Perfect Process Tree (PPT) Construction
Extract the recursive structure from predicates.

**Epics & Beads:**
1. **3a-EXTRACT**: Extract base case and recursive case from function definition
   - Complexity: S (Small)
   - Dependencies: Phase 2 complete
   - AC (Acceptance Criteria):
     - [ ] Can extract base case: `edge(x,y)` from `path` definition
     - [ ] Can extract step case: `edge(x,z) andalso path(z,y)`
     - [ ] Handles `orelse` at top level
     - [ ] Unit tests for 5+ recursive patterns pass

2. **3a-JOINVAR**: Identify join variables in iteration
   - Complexity: S
   - Dependencies: 3a-EXTRACT
   - AC:
     - [ ] Identifies shared variable(s) between base and step case
     - [ ] Handles single join variable (e.g., `z` in path example)
     - [ ] Detects multiple join variables
     - [ ] Detects impossible patterns (no common variable)

3. **3a-PATTERN**: Implement pattern matcher for recursive structures
   - Complexity: M (Medium)
   - Dependencies: 3a-EXTRACT, 3a-JOINVAR
   - AC:
     - [ ] Recognizes `P1 orelse (exists z where P2 andalso f(...))`
     - [ ] Recognizes `P1 orelse (exists z where f(...) andalso P2)`
     - [ ] Detects when pattern doesn't match and returns None
     - [ ] Pattern matching passes 100% on test cases

**Knowledge Base References:**
- `algorithm-synthesis-ura-to-morel.md`: Perfect Process Trees
- `PREDICATE_INVERSION_DESIGN.md`: Example 6 (transitive closure)

---

#### Phase 3b: Tabulation Infrastructure
Build the incremental computation model.

**Epics & Beads:**

1. **3b-SUBST**: Implement variable substitution in iteration context
   - Complexity: M
   - Dependencies: 3a-PATTERN
   - AC:
     - [ ] Substitutes bound variables in generated expressions
     - [ ] Handles nested variable references
     - [ ] Maintains type correctness after substitution
     - [ ] Unit tests for 10+ substitution scenarios pass

2. **3b-INCREMENT**: Build incremental computation model
   - Complexity: M
   - Dependencies: 3b-SUBST
   - AC:
     - [ ] Generates step function: `fn (old, new) => ...`
     - [ ] Step function takes two parameters (previous, current iteration)
     - [ ] Step function returns new tuples for next iteration
     - [ ] Iteration terminates when no new tuples added

3. **3b-JOIN**: Implement join expression generation
   - Complexity: M
   - Dependencies: 3b-INCREMENT
   - AC:
     - [ ] Generates `from ... in old, ... in base where join_condition yield ...`
     - [ ] Join condition correctly unifies iteration variables
     - [ ] Handles projection (selecting output columns)
     - [ ] Integration tests with real data pass

4. **3b-TUPLE**: Handle tuple composition and destructuring
   - Complexity: S
   - Dependencies: 3b-JOIN
   - AC:
     - [ ] Correctly builds output tuple from join results
     - [ ] Handles record types vs tuple types
     - [ ] Maintains field names and types
     - [ ] Unit tests for 5+ tuple scenarios pass

**Knowledge Base References:**
- `algorithm-synthesis-ura-to-morel.md`: Tabulation algorithms
- `PREDICATE_INVERSION_DESIGN.md`: Examples 5-6

---

#### Phase 3c: Code Generation & Validation
Generate and test the complete Relational.iterate expression.

**Epics & Beads:**

1. **3c-ITERATE**: Generate Relational.iterate structure
   - Complexity: M
   - Dependencies: 3b-TUPLE
   - AC:
     - [ ] Generates `Relational.iterate(base)(stepFn)` syntax
     - [ ] Base generator is correctly inverted from base case
     - [ ] Step function has correct signature
     - [ ] Type checking passes for generated expression

2. **3c-COMPILE**: Validate generated code compiles
   - Complexity: S
   - Dependencies: 3c-ITERATE
   - AC:
     - [ ] Generated Core.Exp compiles without errors
     - [ ] No type errors in generated code
     - [ ] All type variables properly inferred
     - [ ] Compilation tests pass

3. **3c-TRANSITIVE**: Test transitive closure on dummy.smli
   - Complexity: S
   - Dependencies: 3c-COMPILE
   - AC:
     - [ ] `dummy.smli` test case passes (from p where path p → [(1,2),(2,3),(1,3)])
     - [ ] Result contains all transitive edges
     - [ ] No duplicate results
     - [ ] Performance acceptable (< 1s for small graph)

4. **3c-REGRESSION**: Regression test against such-that.smli
   - Complexity: S
   - Dependencies: 3c-TRANSITIVE
   - AC:
     - [ ] All 301 existing tests still pass
     - [ ] No performance degradation
     - [ ] New transitive closure tests pass (such-that.smli line 402+)
     - [ ] Edge cases handled gracefully

**Knowledge Base References:**
- `PREDICATE_INVERSION_DESIGN.md`: Entire document

---

### Phase 3 Quality Gates

| Gate | Criteria | Owner |
|------|----------|-------|
| After 3a | Pattern extraction passes unit tests, no regressions | code-review-expert |
| After 3b | Tabulation infrastructure passes integration tests | code-review-expert |
| After 3c | dummy.smli test passes, all 159 tests still pass | test-validator |

---

## Phase 4: Mode Analysis and Smart Generator Selection

### Objective
Enable predicates to generate values in multiple "modes" (directions).

### Example
```sml
fun path (x, y) = ...

(* Mode 1: Generate paths from x to y *)
from p where path p
→ generates (x, y) pairs

(* Mode 2: Generate possible destinations from x *)
from y where path (1, y)
→ generates y values reachable from 1

(* Mode 3: Generate possible sources to x *)
from x where path (x, 5)
→ generates x values that reach 5
```

### Architecture

#### Phase 4a: Mode Analysis Infrastructure
Build system to track and infer variable bindingness.

**Epics & Beads:**

1. **4a-MODE-DATA**: Implement mode system data structures
   - Complexity: M
   - Dependencies: Phase 3 complete
   - AC:
     - [ ] Mode representation: enum {IN, OUT, BOTH, IGNORE}
     - [ ] Mode vector for predicates
     - [ ] Mode inference rule definitions
     - [ ] Unit tests for 10+ mode configurations

2. **4a-INFER**: Implement mode inference algorithm
   - Complexity: M
   - Dependencies: 4a-MODE-DATA
   - AC:
     - [ ] Applies Hanus inference rules to determine modes
     - [ ] Handles built-in predicates (>, <, elem, etc.)
     - [ ] Handles user-defined predicates
     - [ ] Detects inconsistent mode requirements
     - [ ] Unit tests pass (from Hanus paper examples)

3. **4a-INTEGRATE**: Integrate mode analysis with PredicateInverter
   - Complexity: M
   - Dependencies: 4a-INFER
   - AC:
     - [ ] PredicateInverter accepts mode constraints
     - [ ] Uses mode info to select inversion strategy
     - [ ] Falls back gracefully for unsupported modes
     - [ ] Integration tests pass

4. **4a-MULTI**: Support multiple modes for single predicate
   - Complexity: S
   - Dependencies: 4a-INTEGRATE
   - AC:
     - [ ] Can generate all valid modes for a predicate
     - [ ] Mode selection is deterministic and documented
     - [ ] Multiple mode tests pass
     - [ ] Edge cases (no valid modes) handled

**Knowledge Base References:**
- `mode-analysis-synthesis.md`: Mode system design
- `Hanus_2022_From_Logic_to_Functional_Logic_Programs.pdf`: Mode inference algorithm

---

#### Phase 4b: Smart Generator Selection
Use mode analysis to select optimal generators.

**Epics & Beads:**

1. **4b-SELECT**: Use mode analysis to choose best generator
   - Complexity: M
   - Dependencies: 4a-MULTI
   - AC:
     - [ ] For each valid mode, generates appropriate generator
     - [ ] Selects generator based on actual variable bindings
     - [ ] Avoids impossible modes (throws error)
     - [ ] Unit tests for 5+ mode selection scenarios pass

2. **4b-SCENARIO**: Handle multi-mode generation scenarios
   - Complexity: S
   - Dependencies: 4b-SELECT
   - AC:
     - [ ] Correctly generates when multiple variables bound
     - [ ] Generates when single variable bound
     - [ ] Generates when all variables unbound (if possible)
     - [ ] Integration tests pass

3. **4b-SUITE**: Test multi-mode cases against test suite
   - Complexity: S
   - Dependencies: 4b-SCENARIO
   - AC:
     - [ ] Multi-mode test cases in such-that.smli pass
     - [ ] All Phase 3 tests still pass (no regression)
     - [ ] Performance acceptable
     - [ ] Code is maintainable and documented

**Knowledge Base References:**
- `mode-analysis-synthesis.md`: Mode selection strategy

---

#### Phase 4c: Magic Sets Optimization (OPTIONAL)

Advanced optimization technique for complex predicates. Can be deferred if Phase 4a/4b achieve performance targets.

**Status**: DEFER unless performance testing shows need.

**Potential Beads:**
- 4c-RESEARCH: Study magic set technique
- 4c-IMPLEMENT: Implement magic set transformation
- 4c-BENCHMARK: Benchmark against non-optimized version

**Knowledge Base References:**
- `Madsen_2024_Flix_Dahl_Nygaard_Prize.pdf`: Magic sets in Flix

---

### Phase 4 Quality Gates

| Gate | Criteria | Owner |
|------|----------|-------|
| After 4a | Mode analysis infrastructure complete, all tests pass | code-review-expert |
| After 4b | Multi-mode generation works, 301+ tests pass | test-validator |
| Decision | Evaluate Phase 4c necessity based on performance | strategic-planner |

---

## Overall Dependencies and Critical Path

```
Phase 2 COMPLETE (301 tests passing)
    ↓
Phase 3a (Extraction)
    ↓
Phase 3b (Tabulation)
    ↓
Phase 3c (Code Gen)  ←──────→ Phase 4a (Mode Analysis) [parallel]
    ↓                              ↓
Quality Gate 1                 Phase 4b (Selection)
    ↓                              ↓
Integration Testing           Quality Gate 2
    ↓
Final Validation
```

**Critical Path**: Phase 3 → Phase 4 (sequential dependency)
**Parallelization**: Can start Phase 4a while Phase 3c testing in progress

---

## Implementation Roadmap

### Sprint 1: Phase 3a (Extraction & Pattern Matching)
- **Duration**: 3-4 days
- **Beads**: 3a-EXTRACT, 3a-JOINVAR, 3a-PATTERN
- **Deliverable**: Pattern matching works for standard transitive closure
- **Success Metric**: Unit tests pass for pattern recognition

### Sprint 2: Phase 3b (Tabulation)
- **Duration**: 4-5 days
- **Beads**: 3b-SUBST, 3b-INCREMENT, 3b-JOIN, 3b-TUPLE
- **Deliverable**: Iteration infrastructure complete
- **Success Metric**: Integration tests pass, join generation works

### Sprint 3: Phase 3c (Code Generation)
- **Duration**: 3-4 days
- **Beads**: 3c-ITERATE, 3c-COMPILE, 3c-TRANSITIVE, 3c-REGRESSION
- **Deliverable**: Transitive closure test case passes
- **Success Metric**: dummy.smli passes, all 301 tests still pass

### Phase 3 Review & Testing: 2 days
- Code review against quality checklist
- Performance testing
- Edge case validation

### Sprint 4: Phase 4a (Mode Analysis)
- **Duration**: 4-5 days
- **Beads**: 4a-MODE-DATA, 4a-INFER, 4a-INTEGRATE, 4a-MULTI
- **Deliverable**: Mode system working end-to-end
- **Success Metric**: Mode inference tests pass

### Sprint 5: Phase 4b (Smart Selection)
- **Duration**: 3-4 days
- **Beads**: 4b-SELECT, 4b-SCENARIO, 4b-SUITE
- **Deliverable**: Multi-mode generation working
- **Success Metric**: Multi-mode tests pass, 301+ tests still pass

### Final Validation: 2 days
- Comprehensive testing
- Documentation review
- Performance benchmarking

---

## Testing Strategy

### Test Categories

#### Unit Tests (PredicateInverter)
- Pattern recognition (3a)
- Substitution (3b)
- Mode inference (4a)

#### Integration Tests
- Tabulation workflow (3b)
- Join generation (3b)
- Complete iteration (3c)

#### Script Tests (such-that.smli)
- Existing tests (regression baseline)
- New transitive closure tests
- Multi-mode tests

#### Validation Tests
- dummy.smli transitive closure
- Performance benchmarks
- Edge cases and error handling

### Test Case Locations
- `src/test/java/net/hydromatic/morel/compile/PredicateInverterTest.java`
- `src/test/resources/script/such-that.smli`
- `src/test/resources/script/dummy.smli`
- New: `src/test/resources/script/mode-analysis-tests.smli`

---

## Risk Mitigation

### Risk 1: Pattern Not Matching Arbitrary Recursion
**Mitigation**:
- Design pattern matcher to handle common variations
- Fall back to default generator for unsupported patterns
- Document supported patterns clearly

### Risk 2: Performance Regression
**Mitigation**:
- Maintain baseline test suite (301 tests)
- Benchmark before and after
- Implement early termination for iterate

### Risk 3: Type Inference Failures
**Mitigation**:
- Test with multiple type systems
- Validate type checking in each phase
- Use existing type system infrastructure

### Risk 4: Scope Creep into Phase 4c
**Mitigation**:
- Mark Phase 4c as OPTIONAL
- Define clear decision point for Phase 4c
- Focus on Phase 4a/4b first

---

## Success Criteria

### Phase 3 Complete
- ✅ All 301 existing tests pass
- ✅ Transitive closure test case (dummy.smli) passes
- ✅ Generated code compiles and has correct semantics
- ✅ Code review passes for all epics
- ✅ Documentation updated with new capabilities

### Phase 4a Complete
- ✅ Mode analysis infrastructure implemented
- ✅ Mode inference tests pass (from Hanus examples)
- ✅ Integration with PredicateInverter complete
- ✅ Code review passes

### Phase 4b Complete
- ✅ Multi-mode generation working
- ✅ All 301+ tests pass
- ✅ Performance acceptable
- ✅ Code review passes

### Overall Success
- ✅ Issue #217 resolved
- ✅ Comprehensive test coverage
- ✅ Maintainable, documented code
- ✅ Performance meets requirements

---

## Knowledge Base Integration

### Required Documents
1. **algorithm-synthesis-ura-to-morel.md**
   - Used for: Phase 3a, 3b design
   - Reference: PPT construction, tabulation

2. **mode-analysis-synthesis.md**
   - Used for: Phase 4a design
   - Reference: Mode inference algorithm

3. **complete-knowledge-base-summary.md**
   - Used for: Overall architecture validation
   - Reference: Cross-paper comparisons

4. **predicate-inversion-knowledge-integration.md**
   - Used for: Technical depth verification
   - Reference: Paper-to-code mappings

### Research Papers
- **Abramov & Glück (2000)**: Universal Resolving Algorithm
  - Core theoretical foundation
  - PPT construction algorithm

- **Hanus (2022)**: From Logic to Functional Logic Programs
  - Mode analysis foundation
  - Dependency inference techniques

- **Datafun (2016)**: Functional Datalog
  - Fixed-point iteration theory
  - Integration patterns

---

## Next Steps

1. **Validate this plan** with plan-auditor
2. **Create beads** using project-management-setup
3. **Assign agents** to each epic
4. **Begin Phase 3a** with java-developer and codebase-deep-analyzer
5. **Establish quality gates** with test-validator and code-review-expert

---

## Document History

- **Created**: 2026-01-23
- **Version**: 1.0
- **Status**: Ready for plan-audit
- **Next Review**: After plan-auditor validation
