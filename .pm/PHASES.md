# Morel Predicate Inversion: Phase Breakdown

**Project**: Morel Predicate Inversion (Issue #217)
**Duration**: 4-6 weeks total
**Team**: Solo developer + agent support
**Current Status**: Phase 2 complete, Phase 3 starting

---

## Phase Summary

| Phase | Name | Status | Duration | Epics | Key Outcome |
|-------|------|--------|----------|-------|------------|
| 1 | Recursive Function Handling | COMPLETE | 1 week | 1 | Graceful recursion, no crashes |
| 2 | Pattern Detection & Base Case | COMPLETE | 2 weeks | 2 | Transitive closure detection |
| 3 | Full Relational.iterate Generation | PLANNING | 3 weeks | 6 | Transitive closure inversion |
| 4 | Mode Analysis & Smart Selection | PENDING | 2-2.5 weeks | 5 | Optimal predicate ordering |

---

## PHASE 1: Recursive Function Handling

**Status**: COMPLETE (Jan 16-23, 2026)
**Objective**: Handle recursive functions gracefully

### Deliverables
- PredicateInverter.java with basic structure
- Graceful fallback for uninvertible predicates
- InversionResult class for return value
- 159 tests passing (no regressions)

### Key Decisions
- Use Optional<InversionResult> instead of null returns
- Fallback to cartesian product when pattern not recognized
- Track metadata: mayHaveDuplicates, isSupersetOfSolution

### Acceptance Criteria
- [x] PredicateInverter.invert() handles basic patterns
- [x] Recursive functions don't crash (fallback works)
- [x] 159 baseline tests passing
- [x] Code review approved

### Tests
- PredicateInverterTest.java: 7 tests (PASSING)
- Integration: such-that.smli (PASSING)

### Next Phase Gate
- All 159 tests passing ✓
- Code review approved ✓
- Ready for Phase 2 ✓

---

## PHASE 2: Pattern Detection & Base Case Extraction

**Status**: COMPLETE (Jan 23, 2026)
**Objective**: Detect transitive closure patterns and extract base cases

### Deliverables
- `tryInvertTransitiveClosure()` method (lines 419-464)
- Pattern detection for `baseCase orelse recursiveCase`
- Base case inversion (e.g., `edge(x,y)` from complex pattern)
- Mode tracking (which variables generated, which required)
- 159 tests still passing

### Key Achievements
1. **Pattern Detection**: Identify transitive closure structure reliably
2. **Base Case Extraction**: Separate base from recursive case
3. **Mode Tracking**: Know which variables can be generated from which
4. **Duplicate Awareness**: Mark results as potentially having duplicates

### Acceptance Criteria
- [x] Detects transitive closure pattern in test cases
- [x] Extracts and inverts base case correctly
- [x] Mode tracking accurate (tested with multiple patterns)
- [x] 159 baseline tests still passing
- [x] Code review approved

### Tests
- PredicateInverterTest.java: 7 tests (PASSING)
  - Test 7: Uninvertible patterns return empty (PASSING)
- such-that.smli: Base case extraction verified

### Code Review
- Architecture approved: Pattern decomposition is correct
- Design: Clean separation of concerns
- Quality: Meets code standards

### Next Phase Gate
- All 159 tests passing ✓
- Pattern detection working ✓
- Base case extraction working ✓
- Code review approved ✓
- Ready for Phase 3 ✓

---

## PHASE 3: Full Relational.iterate Generation

**Status**: PLANNING (Starting now)
**Objective**: Generate complete Relational.iterate expressions for transitive closure inversion
**Duration**: 3 weeks
**Target Tests**: such-that.smli:737-742 (transitive closure test passing)

### Phase Overview

This phase implements the full Abramov-Glück Universal Resolving Algorithm, specifically the perfect process tree construction and iteration steps. We build on Phase 2's base case extraction to generate complete Relational.iterate expressions.

### Key Transformation

**Input**: Recursive predicate definition
```sml
fun path (x, y) = edge (x, y) orelse
  (exists z where edge (x, z) andalso path (z, y))
```

**Output**: Generated expression
```sml
Relational.iterate edges
  (fn (old, new) =>
    from (x, z) in edges,
         (z2, y) in new
      where z = z2
      yield (x, y))
```

**Process**:
1. Build perfect process tree (Phase 3.1)
2. Extend base+recursive inversion (Phase 3.2)
3. Tabulate I-O pairs (Phase 3.3)
4. Generate step function (Phase 3.4)
5. Integrate Relational.iterate call (Phase 3.5)
6. Quality assurance (Phase 3.6)

### Epics

#### Epic 3.1: Perfect Process Tree Construction (2-3 days)

**Objective**: Implement PPT data structure and construction algorithm

**Description**:
- Build ProcessTreeNode hierarchy (Terminal, Branch, etc.)
- Implement visitor pattern for traversal
- Integrate with existing Core.Exp infrastructure
- Track environments (variable bindings)

**Key Classes**:
- ProcessTreeNode: Abstract base for PPT nodes
- TerminalNode: Leaf representing base case or recursive call
- BranchNode: Branch with multiple alternative paths
- ProcessTreeBuilder: Constructs PPT from pattern

**Files**:
- src/main/java/net/hydromatic/morel/compile/ProcessTreeNode.java (new)
- src/main/java/net/hydromatic/morel/compile/ProcessTreeBuilder.java (new)
- src/test/java/net/hydromatic/morel/compile/ProcessTreeTest.java (new)

**Acceptance Criteria**:
1. ProcessTreeNode hierarchy complete (4+ node types)
2. TreeBuilder constructs correct PPT for test patterns
3. Visitor pattern implemented for traversal
4. 10+ unit tests passing
5. Handles duplicate elements correctly
6. Edge cases covered (empty paths, cycles)

**Test Coverage**: 85%+ for new code

**Dependencies**: None (Phase 2 complete)

**Knowledge References**:
- Abramov & Glück 2002, Section 2.3
- Synthesis doc: "Step 1: Build Perfect Process Tree"

**Time Estimate**: 2-3 days

**Complexity**: Medium

---

#### Epic 3.2: Extended Base+Recursive Inversion (2-3 days)

**Objective**: Extend PredicateInverter to handle both base AND recursive cases

**Description**:
- Modify `tryInvertTransitiveClosure()` to handle both cases
- Extract recursive case pattern (e.g., `edge(x,z) andalso path(z,y)`)
- Build join logic for step function
- Track I-O variables (what gets generated, what's required)

**Key Methods**:
- extractRecursiveCase(): Separate from base case
- identifyJoinPattern(): Find join structure in recursive case
- buildJoinLogic(): Create join expression for iteration

**Files**:
- src/main/java/net/hydromatic/morel/compile/PredicateInverter.java (modify)
- src/test/java/net/hydromatic/morel/compile/PredicateInverterTest.java (expand)

**Acceptance Criteria**:
1. Correctly extracts recursive case pattern
2. Join logic builds proper from/where/yield structure
3. Handles multiple join variables correctly
4. 6+ new tests passing
5. All 159 baseline tests still passing
6. Proper error handling for invalid patterns

**Test Coverage**: 85%+ for modified code

**Dependencies**: Depends on 3.1 (ProcessTreeNode)

**Knowledge References**:
- Synthesis doc: "Step 2: Extended Base + Recursive Inversion"
- Abramov & Glück 2002, Section 3.1-3.5

**Time Estimate**: 2-3 days

**Complexity**: Medium-High

---

#### Epic 3.3: Tabulation (I-O Pair Collection) (2-3 days)

**Objective**: Implement tabulation algorithm to collect input-output pairs

**Description**:
- Create TabulationEngine class for I-O pair collection
- Implement duplicate detection using memoization
- Track cardinality estimates
- Handle sets vs bags distinction
- Integrate with PPT from 3.1

**Key Classes**:
- TabulationEngine: Orchestrates tabulation process
- I-OPair: Represents input-output relationship
- CardinalityTracker: Estimates result set size

**Files**:
- src/main/java/net/hydromatic/morel/compile/TabulationEngine.java (new)
- src/main/java/net/hydromatic/morel/compile/I-OPair.java (new)
- src/test/java/net/hydromatic/morel/compile/TabulationTest.java (new)

**Acceptance Criteria**:
1. TabulationEngine correctly collects I-O pairs
2. Duplicate detection working correctly
3. Cardinality estimates accurate
4. Handles recursive predicates with 2+ levels
5. 8+ unit tests passing
6. All 159 baseline tests still passing

**Test Coverage**: 85%+ for new code

**Dependencies**: Depends on 3.2 (Extended inversion)

**Knowledge References**:
- Synthesis doc: "Step 3: Tabulation and I-O Pairs"
- Abramov & Glück 2002, Section 4.1-4.3

**Time Estimate**: 2-3 days

**Complexity**: High

---

#### Epic 3.4: Step Function Generation (2 days)

**Objective**: Generate step function for Relational.iterate

**Description**:
- Build from/where/yield structure for iteration step
- Create parameter bindings (old, new collections)
- Generate join expressions
- Handle filter incorporation from recursive case

**Key Methods**:
- buildStepFunction(): Creates fn(old, new) => body
- buildIterationJoin(): Constructs from/where/yield
- incorporateFilters(): Add where clauses

**Files**:
- src/main/java/net/hydromatic/morel/compile/PredicateInverter.java (modify)
- src/test/java/net/hydromatic/morel/compile/PredicateInverterTest.java (expand)

**Acceptance Criteria**:
1. Generated step function has correct signature
2. Join logic correct for 3+ test cases
3. Filters incorporated properly
4. 5+ new tests passing
5. All 159 baseline tests still passing
6. Generated Core.Exp valid and compilable

**Test Coverage**: 85%+ for modified code

**Dependencies**: Depends on 3.3 (Tabulation)

**Knowledge References**:
- Synthesis doc: "Step 4: Step Function Generation"
- Abramov & Glück 2002, Section 5.1-5.3

**Time Estimate**: 2 days

**Complexity**: Medium

---

#### Epic 3.5: Relational.iterate Integration (2 days)

**Objective**: Wire all components together into complete Relational.iterate call

**Description**:
- Hook PredicateInverter output into Relational.iterate call
- Generate complete expression: iterate(baseGen)(stepFn)
- Wire into Extents.java (line 538)
- Validate generated expressions compile
- Test with transitive closure example

**Key Integration**:
- Extents.java line 538: Call PredicateInverter.invert()
- Handle result: Call Relational.iterate() with base + step

**Files**:
- src/main/java/net/hydromatic/morel/compile/Extents.java (modify ~5 lines)
- src/main/java/net/hydromatic/morel/compile/PredicateInverter.java (modify)
- src/test/resources/script/such-that.smli (uncomment lines 737-742)

**Acceptance Criteria**:
1. Integration compiles without warnings
2. Transitive closure test passing (such-that.smli:737-742)
3. Generated Relational.iterate expression valid
4. 3+ integration tests passing
5. All 159 baseline tests still passing
6. No performance regression (< 10ms for test query)

**Test Coverage**: Integration coverage >= 80%

**Dependencies**: Depends on 3.4 (Step function)

**Knowledge References**:
- Synthesis doc: "Step 5: Integration"

**Time Estimate**: 2 days

**Complexity**: Medium

---

#### Epic 3.6: Phase 3 Quality Assurance (1-2 days)

**Objective**: Comprehensive testing and validation

**Description**:
- Expand test suite to 25+ tests covering edge cases
- Property-based tests (soundness, completeness, termination)
- Performance benchmarking
- Code review and quality gate
- Prepare for Phase 3 → Phase 4 transition

**Files**:
- src/test/java/net/hydromatic/morel/compile/PredicateInverterTest.java (expand to 25+ tests)
- .pm/metrics/phase-3-performance.md (create)

**Acceptance Criteria**:
1. 25+ tests passing (Phase 2 + Phase 3 combined)
2. Edge cases covered (empty result, cycles, deep recursion)
3. Property tests passing (soundness + completeness)
4. Code coverage >= 85% for all Phase 3 code
5. Performance: queries < 10ms
6. All 159 baseline tests still passing

**Test Coverage**: >= 85%

**Dependencies**: Depends on 3.5 (Full integration)

**Knowledge References**:
- METHODOLOGY.md: Testing strategy section
- Synthesis doc: Quality assurance guidelines

**Time Estimate**: 1-2 days

**Complexity**: Medium

---

### Phase 3 Success Criteria (Quality Gate)

**All of these must be met before Phase 4 start**:

1. **Transitive Closure Test Passing**
   - such-that.smli:737-742 output correct
   - [(1,2),(2,3),(1,3)] generated for test input

2. **All Tests Passing**
   - 159 baseline tests still passing
   - 25+ Phase 3 tests passing
   - No regressions detected

3. **Code Quality**
   - Code review approved (all 6 epics)
   - < 2 change requests per epic on average
   - No compiler warnings

4. **Test Coverage**
   - >= 85% coverage for all Phase 3 code
   - Coverage report generated and reviewed

5. **Correctness Verification**
   - Property tests passing (soundness + completeness)
   - Edge cases handled correctly
   - PPT construction proven sound

6. **Performance**
   - Phase 3 queries execute in < 10ms
   - No performance regression from baseline

7. **Documentation**
   - Synthesis documents updated with implementation insights
   - Learnings captured in .pm/learnings/
   - Next phase (Phase 4) dependencies clear

### Phase 3 Gate Review

**Reviewer**: plan-auditor
**Approval Required**: Yes
**Review Criteria**: METHODOLOGY.md "Quality Gates" section

---

## PHASE 4: Mode Analysis & Smart Generator Selection

**Status**: PENDING (Starts after Phase 3 gate approval)
**Objective**: Implement mode inference and smart predicate ordering
**Duration**: 2-2.5 weeks
**Target**: 20+ mode analysis tests passing

### Phase Overview

Mode analysis determines which predicates can generate which variables. This enables smart ordering of predicates to minimize computation (vs. greedy cartesian product approach).

**Example**:
```sml
from x in [1,3,5],
     y in [1,10] where x > y
yield (x, y)
```

Without mode analysis: Cartesian product (5*10=50), then filter

With mode analysis: Generate x, then for each x generate matching y values (5+9=14)

### Key Concepts

**Modes**:
- IN: Variable is provided as input
- OUT: Variable is generated as output
- CHOICE: Can be either (depends on usage)

**Predicate Signatures**:
- `elem: (var, collection) -> OUT: var, IN: collection`
- `comparison: (var, expr) -> depends on dependencies`
- `function call: depends on function definition`

### Epics

#### Epic 4.1: Mode Analysis Infrastructure (2-3 days)

**Objective**: Design and implement mode inference engine

**Description**:
- ModeAnalysisEngine: Main inference engine
- ModeInference: Per-predicate mode inference
- ModeSignature: Describes mode of predicates
- Dependency graph for circular dependencies

**Key Classes**:
- ModeAnalysisEngine: Orchestrates analysis
- ModeSignature: Mode of predicates
- ModeInference: Inference for each pattern type
- DependencyGraph: Track variable dependencies

**Acceptance Criteria**:
1. Engine can infer modes for basic predicates
2. Handles circular dependencies
3. Mode signatures for 5+ predicate types
4. 10+ unit tests passing
5. No regressions in Phase 3 tests

**Complexity**: High
**Time Estimate**: 2-3 days

---

#### Epic 4.2: Smart Predicate Ordering (2-3 days)

**Objective**: Use mode analysis to reorder predicates optimally

**Description**:
- SmartOrdering: Algorithm using mode analysis
- Compare with greedy approach
- Prove optimality for specific patterns
- Integration with Extents.java

**Acceptance Criteria**:
1. Smart ordering produces better results than greedy
2. Benchmark shows < 5% overhead
3. 8+ tests comparing greedy vs. smart
4. All Phase 3 tests still passing

**Complexity**: Medium-High
**Time Estimate**: 2-3 days

---

#### Epic 4.3: Circular Dependency Detection (1-2 days)

**Objective**: Detect and handle circular variable dependencies

**Description**:
- Identify circular dependencies in predicate set
- Fallback strategies for circular cases
- Test with problematic patterns

**Acceptance Criteria**:
1. Correctly detects 5+ circular patterns
2. Provides sensible fallback
3. 6+ tests passing

**Complexity**: Medium
**Time Estimate**: 1-2 days

---

#### Epic 4.4: Filter Push-Down Optimization (Optional, 1-2 days)

**Objective**: Move filters to earliest possible point in computation

**Description**:
- Identify filters that can be pushed down
- Reorder to eliminate early rows
- Benchmark performance improvement

**Acceptance Criteria**:
1. Correctly pushes down 5+ filter patterns
2. Maintains correctness
3. Performance improvement measured

**Complexity**: Medium
**Time Estimate**: 1-2 days

---

#### Epic 4.5: Phase 4 Quality Assurance (1-2 days)

**Objective**: Testing and validation

**Description**:
- Expand mode analysis tests to 20+
- Property tests for mode correctness
- Performance benchmarking
- Code review and quality gate

**Acceptance Criteria**:
1. 20+ mode analysis tests passing
2. All Phase 3 tests still passing
3. Code coverage >= 85%
4. Performance acceptable

**Complexity**: Medium
**Time Estimate**: 1-2 days

---

### Phase 4 Success Criteria (Quality Gate)

**All of these must be met before release**:

1. **Mode Analysis Tests Passing**
   - 20+ mode analysis tests passing
   - All Phase 3 tests still passing (159)

2. **Code Quality**
   - Code review approved (all 5 epics)
   - < 2 change requests per epic on average

3. **Test Coverage**
   - >= 85% coverage for Phase 4 code

4. **Correctness**
   - Property tests for mode inference
   - Circular dependency detection working
   - Smart ordering produces correct results

5. **Performance**
   - Mode analysis overhead < 5%
   - Smart ordering beats greedy on benchmarks

6. **Documentation**
   - Synthesis documents updated
   - Learnings captured
   - Release notes prepared

### Phase 4 Gate Review

**Reviewer**: plan-auditor
**Approval Required**: Yes

---

## Phase Transition Timeline

```
PHASE 1        PHASE 2         PHASE 3                    PHASE 4        RELEASE
(Complete)     (Complete)      (In Progress)              (Pending)      (After P4)

Jan 16-23      Jan 23          Jan 23 - Feb 13            Feb 13 - Feb 27 Feb 27+
Recursion      Base Case       PPT + Tabulation +         Mode Analysis   Done
Handling       Extraction      Relational.iterate         + Ordering

                                   ↓ GATE
                               (All criteria met)
                                   ↓
                                Phase 4 Start

                                   ↓ GATE
                               (All criteria met)
                                   ↓
                              Release Ready
```

---

## Parallel Development Opportunities

**Can work in parallel**:
- Phase 3.1 design and Phase 4.1 design (after 3.1 approved)
- Phase 3.1 and 3.2 implementation (after both designed)
- Phase 3.2 and 3.3 (after both designed)

**Must be sequential**:
- Phase 3.1 must complete before 3.2 implementation starts
- Phase 3.5 must complete before gate review
- Phase 3 gate must approve before Phase 4 starts

---

## Risk Management

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|-----------|
| PPT complexity higher than estimated | Medium | High | Early prototype + design review |
| Duplicate detection subtle bugs | Low | High | Dedicated test cases |
| Performance regression in Phase 3 | Low | Medium | Benchmark before/after |
| Mode analysis correctness issues | Medium | High | Property-based tests |
| Circular dependency complexity | Medium | Medium | Fallback strategy + tests |

---

## Success Metrics

### Phase 3
- 159 baseline tests + 25+ Phase 3 tests passing
- Transitive closure test specific output verified
- Code coverage >= 85%
- Performance < 10ms per query

### Phase 4
- 20+ mode analysis tests passing
- Code coverage >= 85%
- Mode analysis overhead < 5%
- All Phase 3 + Phase 4 tests passing

### Overall Project
- Issue #217 resolved
- Feature complete and production-ready
- Comprehensive test coverage
- Performance meets targets
- Documentation complete

---

**Last Updated**: 2026-01-23
**Total Duration**: 4-6 weeks (Phases 3-4)
**Next Milestone**: Phase 3 gate approval
