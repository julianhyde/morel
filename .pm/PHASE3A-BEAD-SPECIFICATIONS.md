# Phase 3a Bead Specifications - Complete Audit

**Document Date**: 2026-01-24  
**Phase**: 3a (ProcessTreeNode Construction)  
**Status**: Fully Enriched - Ready for Implementation  
**Total Beads**: 5  
**Sequence**: Linear (each blocks the next)

---

## Bead Summary Table

| ID | Title | Type | Status | Size | Test Count | Blocker |
|-----|-------|------|--------|------|-----------|---------|
| morel-1u4 | ProcessTreeNode: Class Hierarchy | task | READY | 250L | 13 | None |
| morel-djr | VarEnvironment: Immutable Env | task | blocked | 200L | 17 | morel-1u4 |
| morel-07f | ProcessTreeBuilder: PPT Construction | task | blocked | 400L | 21 | morel-djr |
| morel-mmn | Tests: Comprehensive Unit Tests | task | blocked | 500L | 53 | morel-07f |
| morel-klw | Integration: Wire into Inverter | task | blocked | ~100L | 301 | morel-mmn |

**Total Implementation**: ~1,450 lines of Java code + ~650 lines of tests
**Total Tests**: 354 (13 + 17 + 21 + 301 baseline + 2 new edge cases)

---

## Bead Specifications (Complete)

### Bead 1: morel-1u4 - ProcessTreeNode Class Hierarchy

**Objective**: Create the foundational ProcessTreeNode class and three static nested node types.

**Deliverables**:
- ProcessTreeNode.java (~250 lines)
  - Abstract base class with abstract methods: term(), env()
  - Predicate methods: isTerminal(), isRecursiveCall()
  - TerminalNode: static nested class (80 lines)
  - BranchNode: static nested class (70 lines)
  - SequenceNode: static nested class (100 lines)

**Key Implementation Details**:
1. **ProcessTreeNode**:
   - Abstract, non-sealed (Java 8 compatible)
   - Methods: term(), env(), isTerminal(), isRecursiveCall()
   - Javadoc with PPT semantics explanation

2. **TerminalNode**:
   - Fields: term, env, inversionResult, isRecursive
   - Factories: of(), recursive()
   - Immutable (all fields final)
   - Method: isInverted() checks if inversionResult is empty

3. **BranchNode** (for orelse):
   - Fields: term, env, left, right  
   - Methods: hasInvertibleBaseCase(), hasRecursiveCase()
   - Helper: containsRecursive() traverses children

4. **SequenceNode** (for andalso):
   - Fields: term, env, children (ImmutableList)
   - Constructor: validates size >= 2
   - Methods: recursiveChildren(), nonRecursiveChildren()

**Acceptance Criteria**:
- [ ] Compiles with Java 8
- [ ] No sealed interfaces or records
- [ ] All three node types implemented
- [ ] Factory methods work correctly
- [ ] toString() methods useful for debugging
- [ ] Javadoc complete with invariants
- [ ] Passes 13 unit tests
- [ ] No compiler warnings

**Test Coverage** (ProcessTreeNodeTest.java - 13 tests):
- Terminal node creation (2 tests)
- Terminal node factories (2 tests)
- Terminal node.isInverted() (1 test)
- Branch node structure (2 tests)
- Branch node predicates (2 tests)
- Sequence node children (2 tests)
- General predicates: isTerminal, isRecursiveCall (2 tests)

**Code Template**: Available in PHASE-3A-PROCESSTREENODE-DESIGN.md Section 3.1

**Next Step**: Unblocks morel-djr

---

### Bead 2: morel-djr - VarEnvironment Immutable Environment

**Objective**: Implement variable tracking environment that tracks goal variables, bound generators, and join variables.

**Deliverables**:
- VarEnvironment.java (~200 lines)
  - Static immutable class
  - Factory methods: initial() (2 variants)
  - Update methods: withBound(), withJoinVar(), withJoinVars()
  - Query methods: isGoal(), isBound(), isJoin(), getGenerator(), unboundGoals()

**Key Implementation Details**:
1. **Fields**:
   - goalPats: ImmutableSet<Core.NamedPat>
   - boundVars: ImmutableMap<Core.NamedPat, PredicateInverter.Generator>
   - joinVars: ImmutableSet<Core.NamedPat>
   - typeEnv: Environment (for type lookups)

2. **Factories**:
   - initial(goals, typeEnv) - empty environment
   - initial(goals, initialGenerators, typeEnv) - with pre-bindings

3. **Update Methods** (all return new instances):
   - withBound(pat, gen)
   - withJoinVar(pat)
   - withJoinVars(Set<NamedPat>)

4. **Query Methods**:
   - isGoal(pat): is this an output variable?
   - isBound(pat): do we have a generator for this?
   - isJoin(pat): is this a join variable?
   - getGenerator(pat): Optional<Generator>
   - unboundGoals(): Set<NamedPat> of unbound output variables

**Acceptance Criteria**:
- [ ] Compiles with Java 8
- [ ] All fields final and immutable
- [ ] No setters (factory pattern for updates)
- [ ] All update methods return new instances
- [ ] Query methods correctly implemented
- [ ] toString() shows goals, bound, joins
- [ ] Javadoc explains variable classifications
- [ ] Passes 17 unit tests
- [ ] No compiler warnings

**Test Coverage** (VarEnvironmentTest.java - 17 tests):
- initial() factory (2 tests)
- initial() with pre-bindings (1 test)
- withBound() adds binding (2 tests)
- withJoinVar() adds join (2 tests)
- withJoinVars() multiple joins (1 test)
- Query methods: isGoal, isBound, isJoin (3 tests)
- getGenerator() returns correct binding (2 tests)
- unboundGoals() filtering (2 tests)
- toString() output (1 test)

**Code Template**: Available in PHASE-3A-PROCESSTREENODE-DESIGN.md Section 3.2

**Dependencies**: Requires morel-1u4 to compile

**Next Step**: Unblocks morel-07f

---

### Bead 3: morel-07f - ProcessTreeBuilder PPT Construction

**Objective**: Implement the buildPPT() algorithm that converts predicate expressions into Perfect Process Trees, WITH CRITICAL exists pattern handling.

**Deliverables**:
- ProcessTreeBuilder.java (~400 lines)
  - Constructor: (TypeSystem, PredicateInverter, Set<Exp> activeFunctions)
  - Main method: build(Core.Exp, VarEnvironment) → ProcessTreeNode
  - Helper methods for branch, sequence, terminal
  - **CRITICAL**: exists pattern detection and unwrapping
  - Extraction method: extractCaseComponents() for Phase 3b

**Key Implementation Details**:

1. **build() Algorithm**:
   ```
   If Op.APPLY (orelse):
     → buildBranch() returns BranchNode
   Else if Op.APPLY (andalso):
     → buildSequence() returns SequenceNode
   Else if Op.FROM (exists pattern):
     → Check isExistsPattern()
     → Extract whereClause()
     → Recurse on body
   Else if recursive call detected:
     → return TerminalNode.recursive()
   Else (terminal):
     → buildTerminal() attempts inversion
   ```

2. **CRITICAL - Exists Pattern Handling**:
   - **isExistsPattern(Core.From)**:
     - Check: has WHERE step
     - Check: does NOT have YIELD step
     - Return: hasWhere && !hasYield
   
   - **extractWhereClause(Core.From)**:
     - Find all WHERE steps
     - Single WHERE: return directly
     - Multiple WHERE: combine with andalso
     - Return null if no WHERE
   
   - **Test Case**:
     ```
     exists z where edge(x,z) andalso path(z,y)
     ```
     Should unwrap to SequenceNode with two children:
     - TerminalNode(edge(x,z))
     - TerminalNode.recursive(path(z,y))

3. **buildSequence()**:
   - Decomposes andalso into flat conjunct list
   - Calls identifyJoinVariables() before building children
   - Returns SequenceNode with marked join variables

4. **identifyJoinVariables()**:
   - Finds variables appearing in multiple conjuncts
   - Excludes goal variables (outputs, not joins)
   - Returns Set<NamedPat>

5. **extractCaseComponents()**:
   - Takes BranchNode (must have invertible left branch and recursive right)
   - Returns Optional<CaseComponents> with:
     - baseCaseExpr (e.g., edge(x,y))
     - baseCaseResult (inversion result)
     - recursiveCallExpr (e.g., path(z,y))
     - joinVars (e.g., {z})

**Acceptance Criteria**:
- [ ] Compiles with Java 8
- [ ] build() handles orelse → BranchNode
- [ ] build() handles andalso → SequenceNode
- [ ] build() **UNWRAPS exists patterns** ← CRITICAL
- [ ] isExistsPattern() correctly detects exists vs generators
- [ ] extractWhereClause() handles single WHERE
- [ ] extractWhereClause() handles multiple WHERE
- [ ] Recursion detection works (uses activeFunctions)
- [ ] identifyJoinVariables() finds shared variables
- [ ] extractCaseComponents() extracts base/recursive/joins
- [ ] No compiler warnings
- [ ] Passes 21 unit tests (was 20, added generator discrimination test)
- [ ] **CRITICAL TEST PASSES**: path(x,y) unwrapping
- [ ] **CRITICAL TEST PASSES**: isExistsPattern discriminates generators from exists

**Test Coverage** (ProcessTreeBuilderTest.java - 21 tests):
- build() with orelse (2 tests)
- build() with andalso (3 tests)
- build() with recursion (2 tests)
- **build() with exists pattern (7 tests)** ← CRITICAL
  - Single WHERE unwrap
  - Multiple WHERE combination
  - Full path(x,y) pattern
  - Exists vs generator distinction ← **ADDED BY plan-auditor**
  - Nested exists patterns
  - Edge cases (no WHERE found)
- isExistsPattern() discrimination (2 tests)
- extractWhereClause() handling (2 tests)
- identifyJoinVariables() (2 tests)
- extractCaseComponents() (1 test)

**Code Template**: Available in PHASE-3A-PROCESSTREENODE-DESIGN.md Section 3.3

**Dependencies**: Requires morel-djr

**Critical Success Factor**: The exists pattern unwrapping MUST work correctly. This is the gate for Phase 3b progress.

**Next Step**: Unblocks morel-mmn

---

### Bead 4: morel-mmn - Comprehensive Unit and Integration Tests

**Objective**: Write 53 comprehensive tests covering all three classes in isolation and integration, with emphasis on exists pattern validation.

**Deliverables**:
- ProcessTreeNodeTest.java (13 tests, ~150 lines)
- VarEnvironmentTest.java (17 tests, ~200 lines)
- ProcessTreeBuilderTest.java (23 tests, ~330 lines)

**Total Test Code**: ~680 lines, 53 tests, 100% coverage of public APIs

**Test Distribution**:

| File | Tests | Focus | Critical |
|------|-------|-------|----------|
| ProcessTreeNodeTest.java | 13 | Node creation, factories, predicates | No |
| VarEnvironmentTest.java | 17 | Environment management, immutability | No |
| ProcessTreeBuilderTest.java | 23 | PPT building, exists handling | YES |

**ProcessTreeNodeTest.java (13 tests)**:
- TerminalNode.of() creates mutable instance
- TerminalNode.recursive() marks recursive
- TerminalNode.isInverted() checks result
- BranchNode.hasInvertibleBaseCase() checks left
- BranchNode.hasRecursiveCase() checks right
- BranchNode.containsRecursive() traverses
- SequenceNode requires 2+ children
- SequenceNode.recursiveChildren() filtering
- SequenceNode.nonRecursiveChildren() filtering
- toString() methods work
- isTerminal() predicates
- isRecursiveCall() predicates
- Immutability (no setters)

**VarEnvironmentTest.java (17 tests)**:
- initial(goals, env) empty bindings
- initial(goals, gens, env) pre-bound
- withBound() adds single binding
- withBound() immutable (returns new instance)
- withJoinVar() adds single join
- withJoinVar() idempotent (duplicate ignored)
- withJoinVars() adds multiple
- isGoal() returns true for goal vars
- isBound() returns true for bound vars
- isJoin() returns true for join vars
- getGenerator() returns binding or empty
- unboundGoals() filters correctly
- toString() includes all info
- Edge case: empty goals
- Edge case: all bound
- Edge case: overlapping joins
- Immutability of collections

**ProcessTreeBuilderTest.java (23 tests)** ← INCLUDES CRITICAL TESTS:
- build(orelse) creates BranchNode
- branch.left is base, branch.right is recursive
- build(andalso) creates SequenceNode
- sequence.children ordered correctly
- Recursion detection via activeFunctions
- **build(exists single-WHERE) unwraps** ← TEST 6
- **build(exists multiple-WHERE) combines** ← TEST 7
- **build(exists on path(x,y)) full pattern** ← TEST 8
- **isExistsPattern vs generators (SCAN discrimination)** ← **ADDED by plan-auditor**
- **Recursion detection matches PredicateInverter** ← **ADDED by plan-auditor**
- **Multi-WHERE clause handling (3+ clauses)** ← **ADDED by plan-auditor**
- isExistsPattern(FROM has WHERE no YIELD)
- isExistsPattern(YIELD-FROM is generator)
- extractWhereClause(single) returns directly
- extractWhereClause(multiple) combines with andalso
- identifyJoinVariables finds shared vars
- identifyJoinVariables excludes goals
- collectFreeVariables extracts refs
- extractCaseComponents returns CaseComponents
- extractCaseComponents validates base inverted
- extractCaseComponents finds recursive call
- Recursion with activeFunctions
- Complex pattern integration

**Acceptance Criteria**:
- [ ] ProcessTreeNodeTest.java created (13 tests)
- [ ] VarEnvironmentTest.java created (17 tests)
- [ ] ProcessTreeBuilderTest.java created (23 tests)
- [ ] **ALL 53 TESTS PASSING** ✅ (was 50, added 3 plan-auditor tests)
- [ ] **Exists pattern test PASSES** ← CRITICAL ✅
- [ ] **Generator vs exists discrimination test PASSES** ← **NEWLY CRITICAL** ✅
- [ ] All 301 existing Morel tests PASS (no regression)
- [ ] Coverage ≥ 90% for all public methods
- [ ] No test warnings or deprecations
- [ ] Code follows existing test patterns

**Test Execution**:
```bash
./mvnw test -Dtest=ProcessTreeNodeTest      # 13 pass
./mvnw test -Dtest=VarEnvironmentTest       # 17 pass
./mvnw test -Dtest=ProcessTreeBuilderTest   # 23 pass
./mvnw test                                  # 301+53=354 pass
```

**Success Metric**: Exit code 0, all 354 tests passing, 0 failures

**Dependencies**: Requires morel-07f (ProcessTreeBuilder must exist)

**Gate**: If exists pattern test fails, CANNOT proceed to morel-klw. Must debug ProcessTreeBuilder.

**Next Step**: Unblocks morel-klw

---

### Bead 5: morel-klw - Integration with PredicateInverter

**Objective**: Wire ProcessTreeBuilder into PredicateInverter.tryInvertTransitiveClosure() to complete Phase 3a.

**Deliverables**:
- Modified PredicateInverter.java (~100 lines):
  - Import statements for ProcessTreeBuilder, VarEnvironment
  - Modified tryInvertTransitiveClosure() method
  - Three new helper methods (stubs for Phase 3b)
  - No other files modified

**Key Implementation Details**:

1. **Modified tryInvertTransitiveClosure()**:
   - Extract function ID from apply
   - Look up function definition
   - Create ProcessTreeBuilder
   - Call build() with function body
   - Extract case components
   - Create Relational.iterate expression
   - Return Result

2. **New Helper Methods** (stubs - full implementation in Phase 3b/3c):
   - buildStepFunction(CaseComponents, goals) → Core.Exp
   - buildIterateExpression(baseGen, stepFn) → Core.Exp
   - getDefinition(Core.Id) → Optional<Binding>

3. **Integration Points**:
   - Hook: PredicateInverter.tryInvertTransitiveClosure() (line 180)
   - Caller: Extents.g3 line 538 (already configured)
   - Output: Result with generator for recursive predicates

**Acceptance Criteria**:
- [ ] ProcessTreeBuilder imported
- [ ] VarEnvironment imported
- [ ] tryInvertTransitiveClosure() calls build()
- [ ] tryInvertTransitiveClosure() calls extractCaseComponents()
- [ ] buildStepFunction() stub exists
- [ ] buildIterateExpression() stub exists
- [ ] getDefinition() looks up functions
- [ ] No compiler errors
- [ ] All 301 existing tests PASS ✅
- [ ] No performance regression
- [ ] Code review ready (<1 change request)

**What's NOT Done Yet**:
- ❌ buildStepFunction() full implementation (Phase 3b)
- ❌ buildIterateExpression() full implementation (Phase 3c)
- ❌ Relational.iterate generation (Phase 3c)

**What IS Done**:
- ✅ ProcessTreeBuilder wired in
- ✅ PPT construction happens
- ✅ Case components extracted
- ✅ Structure for Phase 3b ready

**Testing**:
```bash
./mvnw test                                # 301 tests pass
./mvnw test -Dtest=PredicateInverterTest   # Specific tests pass
```

**Test Expectations**:
- No new test failures
- No regression vs. baseline (18.9 seconds)
- PredicateInverter tests still pass

**Files Modified**:
- PredicateInverter.java only (~50 lines added/modified)
- No other files touched in this bead

**Code Review Readiness**:
- Follows METHODOLOGY.md checklist
- <1 change request expected
- Implementation ready for code-review-expert

**Dependencies**: Requires morel-mmn (all 50 tests must pass)

**Completion Criteria**: 301 tests passing + code review approval

**Next Phase**: Phase 3b (buildStepFunction implementation)

---

## Complete Bead Dependency Graph

```
morel-1u4 (ProcessTreeNode)
    ↓
morel-djr (VarEnvironment)
    ↓
morel-07f (ProcessTreeBuilder)
    ↓
morel-mmn (Tests)
    ↓
morel-klw (Integration)
```

**Critical Path**: All beads sequential, each must complete before next starts.

**Key Gate**: morel-mmn → morel-klw
- If any test in morel-mmn fails, specifically exists pattern tests, STOP before morel-klw
- Fix ProcessTreeBuilder before proceeding to integration

---

## Implementation Checklist

### Before Starting morel-1u4:
- [ ] Design document reviewed (PHASE-3A-PROCESSTREENODE-DESIGN.md)
- [ ] Java 8 patterns understood (no sealed, records, pattern matching)
- [ ] Test environment verified (301 baseline tests passing)
- [ ] All beads created and dependencies set

### During Each Bead:
- [ ] Code matches design templates exactly
- [ ] Unit tests written first (TDD)
- [ ] All tests passing locally
- [ ] No compiler warnings
- [ ] Code review checklist followed

### After morel-klw:
- [ ] All 301 existing tests passing
- [ ] All 50 new tests passing
- [ ] Code review approved
- [ ] Ready for Phase 3b

---

## Quality Metrics

**Expected Results**:
- Total Java code: ~1,450 lines (3 classes)
- Total test code: ~680 lines (3 test classes)
- Code-to-test ratio: 1:0.47 (reasonable for core classes)
- Test coverage: ≥ 90% of public APIs
- Zero compiler warnings
- Zero test failures (354/354)
- Code review: <1 change request

**Baseline**:
- Current tests: 301 passing
- Execution time: 18.9 seconds
- Expected after Phase 3a: 354 passing (301 + 53 new), ~20 seconds

---

## References

**Design Document** ⚠️ **REQUIRES MIGRATION FROM MEMORY BANK TO REPOSITORY**:
- Location (current): Memory Bank `morel_active/phase-3a-processtreenode-design.md`
- Location (target): `.pm/PHASE-3A-PROCESSTREENODE-DESIGN.md` ← **MUST BE IN REPOSITORY**
- Sections 3.1, 3.2, 3.3 have full code templates
- Contains complete exists pattern handling with SCAN check fix

**Algorithm Documents**:
- algorithm-synthesis-ura-to-morel.md (PPT construction algorithm)
- PREDICATE_INVERSION_DESIGN.md (example walkthrough)

**Project Management**:
- .pm/METHODOLOGY.md (engineering discipline)
- .pm/AGENT_INSTRUCTIONS.md (agent handoff)

**Code Patterns**:
- Existing PredicateInverter.java (similar structure)
- Extents.java (integration pattern)

**Audit Documents**:
- PHASE3A-AUDIT-FINAL.md (initial audit - now outdated with plan-auditor findings)
- AUDIT-CORRECTIONS.md ← **NEW** (consolidates both audits and fixes)

---

**Audit Status**: ✅ COMPLETE (Both substantive-critic and plan-auditor)
**Beads Status**: ✅ FULLY ENRICHED WITH CORRECTIONS
**Implementation Ready**: ⚠️ WITH REQUIRED CHANGES
**Critical Blocker**: Design document migration from Memory Bank to repository
**Critical Fix**: Exists pattern detection bug (SCAN check added)
**Test Additions**: 3 new tests added (53 total, was 50)
**Next Action**: Migrate design doc → Fix bead specs → Spawn java-developer

