# Morel Predicate Inversion: Engineering Methodology

**Context**: `.pm/CONTEXT_PROTOCOL.md`
**Current Phase**: Phase 2 complete, Phase 3 planning
**Status**: Ready for Phase 3 execution

---

## Core Principles

### 1. Test-First Development

**Discipline**: Write test cases BEFORE implementation

- **Unit tests**: Test individual methods (ProcessTreeNode, PredicateModeAnalysis, etc.)
- **Integration tests**: Test components together (Extents + PredicateInverter)
- **Property tests**: Verify soundness (all outputs satisfy predicate) and completeness (all solutions generated)

**Workflow per Epic**:

```
1. Design (review with architect)
   ↓
2. Write test cases (all acceptance criteria as tests)
   ↓
3. Run tests (all FAIL initially)
   ↓
4. Implement minimum to pass tests
   ↓
5. Code review (review against knowledge base)
   ↓
6. Run full test suite (ensure no regressions)
   ↓
7. Performance check (benchmark before/after if applicable)
   ↓
8. Mark complete (update execution_state.json)
```

**Test Organization**:
- PredicateInverterTest.java: Core predicate inversion tests (currently 7 tests, expand to 25+)
- ModeAnalysisTest.java: Mode analysis tests (Phase 4, 20+ tests)
- DependencyResolutionTest.java: Dependency graph tests (Phase 4, 15+ tests)
- such-that.smli: Integration tests (currently 32 tests, add 10+)

### 2. Commit Discipline for Local-Only Work

**Policy**: Keep commits atomic, well-documented, focused on single concerns

**Pre-Commit Checklist**:
- [ ] All tests pass (including 159 baseline tests)
- [ ] Code review comments addressed
- [ ] No AI attribution in commit message (company policy)
- [ ] Reference relevant bead in commit message
- [ ] Commit message describes WHY not HOW

**Commit Format**:
```
<Scope>: <Description>

<Detailed explanation of change and rationale>

References: <Bead ID> (e.g., ART-phase3-1)
```

**Examples**:

GOOD:
```
Implement PPT node representation

Add ProcessTreeNode hierarchy to represent computation paths in Abramov-Glück algorithm.
This enables Phase 3 perfect process tree construction for transitive closure inversion.

References: Phase3-PPT-Design
```

BAD:
```
Add nodes

Generated with Claude Code
Co-Authored-By: Claude Haiku
```

**Workflow**:
1. Implement feature in feature branch (e.g., `phase3-1-ppt-nodes`)
2. Run full test suite
3. Get code review approval
4. Create atomic commit(s)
5. Merge to 217-phase1-transitive-closure branch

### 3. Knowledge Base Integration

**Sources of Truth** (in priority order):
1. **Beads** - Task tracking, dependencies, status
2. **ChromaDB** - Persistent knowledge (decisions, research, patterns)
3. **Memory Bank** - Session state, active work
4. **.pm/** - Project infrastructure

**Knowledge Integration Workflow**:

**Before Starting Work**:
- [ ] Review relevant synthesis document from Memory Bank
- [ ] Read linked paper sections
- [ ] Review code examples in synthesis document
- [ ] Check for existing patterns in codebase (Glob + Grep)

**During Implementation**:
- [ ] Reference synthesis document in code comments
- [ ] Link to paper sections for non-obvious design decisions
- [ ] Document algorithm with pseudo-code in comments
- [ ] Add test cases matching those in synthesis document

**After Implementation**:
- [ ] Update relevant synthesis document with lessons learned
- [ ] Store validated findings in ChromaDB (if novel insight)
- [ ] Note in learnings/L*.md for session knowledge

**Knowledge Base References**:

For Phase 3:
- `algorithm-synthesis-ura-to-morel.md`: Abramov-Glück algorithm mapping
- `predicate-inversion-knowledge-integration.md`: Paper overview
- Papers: Abramov & Glück Sections 2.3-6.3

For Phase 4:
- `mode-analysis-synthesis.md`: Hanus algorithm mapping
- Papers: Hanus Sections 4-5

### 4. Agent Delegation Patterns

**When to Spawn Agents**:
- **java-architect-planner**: Before epic (1 day design session)
- **java-developer**: During epic implementation (3-4 day sprints)
- **code-review-expert**: After epic code complete (review session)
- **test-validator**: After feature complete (validation session)
- **plan-auditor**: Before proceeding to next phase (quality gate)

**Handoff Format** (standardized):

```
## Handoff: [Target Agent]

**Task**: [1-2 sentence summary]
**Bead**: [ID] (status: ready_for_work)

### Input Artifacts
- Memory Bank: morel_active/predicate-inversion-knowledge-integration.md
- Files: PredicateInverter.java, PredicateInverterTest.java
- Synthesis: algorithm-synthesis-ura-to-morel.md

### Deliverable
[What the receiving agent should produce]

### Quality Criteria
- [ ] [Criterion 1]
- [ ] [Criterion 2]
- [ ] [Criterion 3]

### Context Notes
[Special context, blockers, or warnings]
```

**Example Handoff to java-developer**:

```
## Handoff: java-developer

**Task**: Implement Perfect Process Tree nodes and builders for Abramov-Glück algorithm Phase 1.
**Bead**: Phase3-PPT-Design (status: in_progress)

### Input Artifacts
- Memory Bank: morel_active/algorithm-synthesis-ura-to-morel.md (section "Step 1: Build Perfect Process Tree")
- Files: src/main/java/net/hydromatic/morel/compile/PredicateInverter.java
- Reference: Abramov & Glück 2002, Section 2.3

### Deliverable
ProcessTreeNode class hierarchy + ProcessTreeBuilder implementation. See bead description for complete spec.

### Quality Criteria
- [ ] All tests in ProcessTreeTest.java pass
- [ ] Code review approved
- [ ] Inline comments reference Abramov-Glück Section 2.3
- [ ] Design validates for 4+ edge cases

### Context Notes
Base case extraction (Phase 2) already works. Phase 3.1 focuses on supporting BOTH base + recursive cases.
```

---

## Quality Gates

### Phase 3 Gate (Before Phase 4 Start)

**Criteria**: ALL must be met

- [ ] **159 tests still passing** (regression check)
- [ ] **Transitive closure test passing** (such-that.smli:737-742 output verified as [(1,2),(2,3),(1,3)])
- [ ] **Code review approved** (code-review-expert: all epics reviewed, < 2 change requests avg)
- [ ] **Test coverage >= 85%** (test-validator: new code coverage report)
- [ ] **PPT construction proven correct** (via unit tests, edge cases passing)
- [ ] **Tabulation correctness verified** (I-O pairs correctly collected, duplicates handled)
- [ ] **Relational.iterate integration verified** (generated code compiles, produces correct results)
- [ ] **No performance regression** (benchmark: Phase 3 queries < 10ms)

**Reviewer**: plan-auditor
**Approval Required**: Yes

### Phase 4 Gate (Before Release)

**Criteria**: ALL must be met

- [ ] **Phase 3 tests still passing** (regression check)
- [ ] **Mode analysis test suite passing** (20+ tests)
- [ ] **Code review approved** (code-review-expert: all epics reviewed)
- [ ] **Test coverage >= 85%** (test-validator: new code coverage)
- [ ] **Mode inference correctness verified** (15+ test cases)
- [ ] **Dependency resolution verified** (circular dependency detection working)
- [ ] **Smart ordering integration verified** (replaces greedy, produces better results)
- [ ] **No performance regression** (< 5% overhead for mode analysis)

**Reviewer**: plan-auditor
**Approval Required**: Yes

---

## Testing Strategy

### Unit Tests (Per Component)

**ProcessTreeNode**:
- Terminal nodes can be created
- Branch nodes support alternative paths
- Environments track variable bindings
- Tree can be traversed depth-first

**PredicateInverter**:
- Base case inversion (existing, 7 tests)
- Recursive pattern detection (new, 4 tests)
- Tabulation correctness (new, 5 tests)
- Step function generation (new, 4 tests)
- Full integration (new, 3 tests)

**ModeAnalysis** (Phase 4):
- Mode inference for elem pattern
- Mode inference for comparison
- Mode inference for arithmetic
- Mode inference for function calls
- Circular dependency detection

### Integration Tests

**such-that.smli**:
- Line 737-742: Transitive closure (PRIMARY)
- New lines: Mode-dependent queries (Phase 4)
- New lines: Complex multi-predicate (Phase 4)

**Regression Tests**:
- All 159 existing tests pass
- No silent failures
- Performance stable

### Property Tests

**Soundness**: For all generated outputs, predicate is satisfied
```java
// Test: every generated (x,y) satisfies path(x,y)
```

**Completeness**: For all valid inputs, all solutions generated
```java
// Test: generated set equals expected set (compare with exhaustive search)
```

**Termination**: Iteration count matches cardinality prediction
```java
// Test: iteration stops when no new tuples (fixpoint reached)
```

### Test Organization

```
.pm/tests/
├── PredicateInverterTest.java (Phase 2-3: 25+ tests)
├── ModeAnalysisTest.java (Phase 4: 20+ tests)
├── DependencyResolutionTest.java (Phase 4: 15+ tests)
└── such-that.smli (Integration: 32 existing + 10+ new)
```

---

## Performance Benchmarking

### Before Each Phase

**Baseline Measurements**:
```bash
./mvnw test -Dtest=ScriptTest > baseline.txt 2>&1
time ./mvnw test -Dtest=PredicateInverterTest > baseline-time.txt 2>&1
```

### After Each Phase

**Regression Check**:
```bash
./mvnw test -Dtest=ScriptTest > after.txt 2>&1
diff baseline.txt after.txt  # Should be empty (tests passing)

time ./mvnw test -Dtest=PredicateInverterTest > after-time.txt 2>&1
# Compare execution time < 5% change
```

### Phase-Specific Benchmarks

**Phase 3 Goal**: Transitive closure queries < 10ms
```java
// In PredicateInverterTest
long start = System.nanoTime();
// Execute transitive closure query
long elapsed = System.nanoTime() - start;
assertTrue("Query time " + elapsed/1_000_000 + "ms", elapsed < 10_000_000);
```

**Phase 4 Goal**: Mode analysis overhead < 5%
```java
long baselineTime = timePredicateInversion(query, WITHOUT_MODE_ANALYSIS);
long newTime = timePredicateInversion(query, WITH_MODE_ANALYSIS);
assertTrue("Mode analysis overhead " + percent(newTime-baselineTime) + "%",
           newTime < baselineTime * 1.05);
```

---

## Code Review Checklist

### For Code Reviewers

**Design Alignment**:
- [ ] Implementation matches accepted design
- [ ] Matches knowledge base synthesis document
- [ ] Follows architectural patterns in existing code

**Correctness**:
- [ ] All tests pass
- [ ] Test coverage >= 85% for new code
- [ ] Edge cases handled
- [ ] Error paths have clear messages

**Quality**:
- [ ] No compiler warnings
- [ ] Follows Java 24 conventions (var everywhere, modern patterns)
- [ ] Comments explain WHY not WHAT
- [ ] Inline references to papers for non-obvious logic

**Performance**:
- [ ] No unnecessary allocations
- [ ] No infinite loops or stack overflows
- [ ] Benchmark results within target

**Integration**:
- [ ] No regression in existing tests
- [ ] New tests added for new functionality
- [ ] API changes documented

### For Code Authors

**Pre-Review Checklist**:
- [ ] All tests pass locally (`mvnw test`)
- [ ] Code follows existing style
- [ ] Added comments for complex logic
- [ ] Benchmark numbers collected
- [ ] Commit message clear and concise
- [ ] No debug code left in
- [ ] Bead acceptance criteria all checked

---

## Knowledge Base Maintenance

### During Session

**Learnings File** (updated after each epic):
```
.pm/learnings/L0-phase3-ppt-construction.md

## Lesson: PPT Representation
[What was learned, why it matters, evidence, action items]
```

**Hypotheses File** (for design decisions):
```
.pm/hypotheses/H0-ppt-visitor-pattern.md

## Hypothesis: Use Visitor Pattern
[The hypothesis, rationale, validation criteria, results]
```

### Session End

**Update Knowledge**:
1. Save insights to `.pm/learnings/` and `.pm/hypotheses/`
2. Store validated findings in ChromaDB
3. Update Memory Bank session files

**Persist to ChromaDB**:
```
research::predicate-inversion::ppt-construction
│
└─ Validated findings from Phase 3.1 implementation
   - ProcessTreeNode structure insights
   - Edge cases discovered
   - Performance characteristics
```

---

## When Things Go Wrong

### Test Failures

**Strategy**: Hypothesis-driven debugging

1. **Form hypothesis** about root cause (from test name and error)
2. **Identify evidence** needed (trace execution, add debug output)
3. **Gather evidence** (run debugger or add logging)
4. **Evaluate** against hypothesis
5. **If supported**: Fix and re-test
6. **If refuted**: Form new hypothesis and repeat

**Example**:
```
Test: testTransitiveClosureCardinalityTracking FAILS
Error: Expected cardinality FINITE, got INFINITE

Hypothesis 1: Cardinality tracking bug
Evidence: Add logging to tabulation algorithm
Result: Found - duplicate detection not working

Hypothesis 2: Not duplicates, genuinely infinite
Evidence: Review predicate definition
Result: Rejected - predicate is well-defined finite set

Fix: Implement proper duplicate detection in tabulation
```

### Performance Regressions

**Investigation**:
1. Compare benchmark before/after
2. Profile with JProfiler or async-profiler
3. Identify hot spots (allocations, loops)
4. Optimize or adjust algorithm

**Acceptable Regressions**:
- Phase 3: Slight increase (Relational.iterate overhead)
- Phase 4: < 5% mode analysis overhead

### Integration Issues

**Symptoms**: New feature breaks existing tests

**Resolution**:
1. Identify which test(s) fail
2. Review changes that might affect it
3. Determine root cause (API change, semantic change, interaction)
4. Update code or test as appropriate

---

## Ongoing Maintenance

### Weekly Progress Update

**Update `.pm/execution_state.json`**:
- Set `current_state.phase` to current phase
- Update `metrics.tests_passing` if changed
- Add any new blockers
- Update `current_state.ready_beads` with available work

**Update `.pm/CONTINUATION.md`**:
- Summarize week's work
- Note key learnings
- Identify emerging blockers
- List next week's planned work

### Bead Status Tracking

**As work progresses**:
- Bead created: `status: pending`
- Bead assigned: `status: pending, owner: agent`
- Work starts: `status: in_progress`
- Work complete: `status: completed, date_completed: YYYY-MM-DD`

---

## Summary

This methodology ensures:

1. **Quality**: Test-first + code review + quality gates
2. **Clarity**: Knowledge base integration + clear handoffs
3. **Traceability**: Beads track all work, commits are atomic
4. **Continuity**: CONTINUATION.md enables resumption after weeks/months
5. **Performance**: Benchmarking prevents silent regressions
6. **Learning**: Capture insights for future phases + team
