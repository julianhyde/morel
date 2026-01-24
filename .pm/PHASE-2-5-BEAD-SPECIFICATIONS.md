# Phase 2-5 Bead Specifications

**Created**: 2026-01-24
**Status**: BEADS CREATED - Ready for Execution
**Epic Bead**: morel-pml

---

## Epic Bead: Phase 2-5: Evidence-Driven Option Selection

**Bead ID**: `morel-pml`
**Type**: Epic
**Priority**: P1
**Status**: Open
**Duration**: 4-5 days
**Created**: 2026-01-24

### Objective

Execute comprehensive 4-5 day evidence-based decision process to choose between Mode Analysis (Option 2) and LinkCode Pattern (Option 3) approaches for solving the compile-time/runtime boundary problem in transitive closure inversion.

### Context

Phase 3b implementation revealed fundamental cardinality mismatch: PredicateInverter attempts to invert patterns at compile time, but actual bindings only available at runtime. Current approach creates infinite extents that fail at runtime. This epic orchestrates phases 2-5 to gather evidence, prototype approaches, and make informed architectural decision.

### Timeline

- **Day 1**: Phase 2 - Define Performance Criteria (1 day, 4-6 hours)
- **Days 2-3**: Phase 3 + Phase 4 in PARALLEL (2-3 days each)
  - Phase 3: Research Mode Analysis
  - Phase 4: Research LinkCode Pattern
- **Day 4**: Phase 5 - Informed Decision (1 day, 9-10 hours)

### Critical Path

```
Phase 2 (1d) → max(Phase 3, Phase 4 in parallel: 2-3d) → Phase 5 (1d)
Total: 4-5 days
```

### Phase Overview

| Phase | Duration | Deliverable | Status |
|-------|----------|-------------|--------|
| 2 | 1 day | performance-criteria.md | Pending |
| 3 | 2-3 days | mode-analysis-design.md | Pending |
| 4 | 2-3 days | linkcode-pattern-design.md | Pending |
| 5 | 1 day | Updated DECISION-LOG.md + implementation plan | Pending |

### Success Criteria (Epic Level)

- [ ] All 4 phase beads created with clear dependencies
- [ ] Performance criteria established before research phases
- [ ] Both Option 2 and Option 3 researched equally
- [ ] Prototypes created and tested for both approaches
- [ ] Decision made with clear rationale documented
- [ ] Implementation plan ready for next phase
- [ ] No critical blockers encountered during research

### Quality Gates

**Before Phase 3+4 Start**:
- [ ] Phase 2 deliverables complete
- [ ] Performance thresholds quantified
- [ ] Benchmark test harness working

**Before Phase 5 Start**:
- [ ] Phase 3 design document complete
- [ ] Phase 4 design document complete
- [ ] Both prototypes compiling and running
- [ ] Fair comparison document created

**Phase 5 Completion**:
- [ ] Decision matrix completed with scores
- [ ] Decision rationale documented
- [ ] Implementation plan ready
- [ ] Next phase beads created

### Risk Assessment

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|-----------|
| Phases 3+4 take longer than estimated | Medium | Medium | Set hard deadline for decision review |
| Analysis paralysis in Phase 5 | Low | Medium | Use weighted decision matrix |
| Performance criteria too vague | Low | Low | Use quantitative thresholds (benchmarks) |
| Prototypes don't compile | Low | High | Prototype in isolation first |

### Related Documentation

- **Plan**: `.pm/PHASE-2-5-IMPLEMENTATION-PLAN.md`
- **Previous Decision**: `.pm/DECISION-LOG.md` (Decision 6)
- **ChromaDB**: analysis::morel::transitive-closure-cardinality-mismatch
- **ChromaDB**: strategy::morel::phase3b-architectural-fix

### Blocking Status

- **Blocks**: All phase 2-5 work
- **Blocked By**: Phase 3b-Final completion (in progress)

### Next Steps

1. Complete Phase 3b-Final (in progress)
2. Execute Phase 2 immediately
3. Spawn Phase 3 + Phase 4 research agents in parallel
4. Conduct Phase 5 decision review
5. Create beads for implementation of chosen approach

---

## Phase Bead 2: Define Performance Criteria

**Bead ID**: `morel-aaj`
**Type**: Task
**Priority**: P1
**Status**: Open
**Duration**: 1 day (4-6 hours)
**Blocks**: morel-m53, morel-cfd (parallel)
**Blocked By**: morel-pml (epic)
**Created**: 2026-01-24

### Objective

Establish objective, quantitative thresholds for when cartesian product fallback is acceptable vs when optimization is required. Define decision trigger that determines whether Option 2 or Option 3 implementation is needed.

### Scope

**Included**:
- Input size categories (small/medium/large graphs)
- Algorithm complexity analysis (O(V*E) to O(V^3))
- Latency acceptance criteria by use case (REPL, batch, analytics)
- Benchmark test infrastructure
- Performance baseline measurements
- Decision trigger documentation

**Excluded**:
- Implementing optimization approaches
- Mode analysis research
- LinkCode pattern investigation

### Subtasks

#### 2.1 Input Size Thresholds (1.5 hours)

**Task**: Define collection size categories with expected behavior.

**Deliverable**: Size threshold table with rationale

**Table**:
| Category | Edge Count | Vertex Count | Expected Operations | Acceptable? |
|----------|------------|--------------|---------------------|-------------|
| Small | < 100 | < 50 | < 10,000 | Yes (< 100ms) |
| Medium | 100-1,000 | 50-500 | < 1,000,000 | Maybe (100ms-1s) |
| Large | > 1,000 | > 500 | > 1,000,000 | No (> 1s) |

**Questions to Answer**:
- What's the cardinality of typical transitive closure predicates?
- How many operations does cartesian product require?
- Where's the practical breaking point?

**Success Criteria**:
- [ ] Size categories defined based on typical Morel usage
- [ ] Operations count calculated for each category
- [ ] Rationale documented

#### 2.2 Latency Acceptance Criteria (1 hour)

**Task**: Establish latency thresholds by use case.

**Deliverable**: Latency criteria table

**Table**:
| Use Case | Acceptable Latency | Trigger for Optimization |
|----------|-------------------|--------------------------|
| Interactive query (REPL) | < 100ms | > 200ms |
| Batch processing | < 1s | > 2s |
| Analytics workload | < 10s | > 30s |

**Questions to Answer**:
- What's Morel's primary use case (interactive vs batch)?
- What latency makes queries feel slow?
- When does performance become unacceptable?

**Success Criteria**:
- [ ] Use cases identified for Morel
- [ ] Acceptable latency ranges set
- [ ] Optimization trigger points defined

#### 2.3 Benchmark Test Harness (2 hours)

**Task**: Implement test infrastructure for measuring cartesian product performance.

**Files to Create**:
- `src/test/java/net/hydromatic/morel/compile/TransitiveClosureBenchmark.java`

**Test Cases**:
1. Small graph: 20 edges, 10 vertices
2. Medium graph: 500 edges, 100 vertices
3. Large graph: 5000 edges, 500 vertices

**Measurements**:
- Execution time (avg of 5 runs)
- Memory allocation (before/after)
- Result set size

**Success Criteria**:
- [ ] Test harness compiles without errors
- [ ] All 3 test cases execute successfully
- [ ] Timing measurements accurate
- [ ] Memory tracking implemented

#### 2.4 Document Decision Trigger (0.5 hours)

**Task**: Define the exact criteria that triggers optimization work.

**Decision Trigger**:
```
IF benchmark_medium_graph > 500ms THEN optimize_required = true
IF benchmark_small_graph > 200ms THEN investigate_further = true
IF benchmark_large_graph > 5s THEN optimization_critical = true
```

**Success Criteria**:
- [ ] Trigger points defined numerically
- [ ] Baseline measurements recorded
- [ ] Decision logic documented clearly

### Success Criteria (Task Level)

- [ ] performance-criteria.md written with quantitative thresholds
- [ ] Benchmark test harness compiles and runs
- [ ] At least 3 representative test cases defined with baseline numbers
- [ ] Decision trigger documented numerically
- [ ] Baseline measurements recorded in deliverable

### Deliverables

**Primary**: `performance-criteria.md`

**Contents**:
1. Input size categories with rationale
2. Latency criteria by use case
3. Benchmark test harness code
4. Baseline measurement results
5. Decision trigger conditions
6. Recommendation on optimization necessity

### Quality Gates

- [ ] performance-criteria.md exists with all sections
- [ ] Benchmark compiles and runs without errors
- [ ] At least 3 data points per test case
- [ ] Decision trigger is numeric and measurable
- [ ] Handoff artifact ready for Phases 3 and 4

### Risk Assessment

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|-----------|
| Criteria too vague | Low | Medium | Use quantitative benchmarks, not opinion |
| Benchmark setup complex | Low | Medium | Start with simple graph structure |
| Performance unpredictable | Medium | Low | Average 5 runs, measure variance |
| Hardware variance | Medium | Low | Document hardware used |

### Handoff Specifications

**To**: Phase 3 researcher (Mode Analysis) and Phase 4 researcher (LinkCode)

**Artifacts**:
- performance-criteria.md
- Benchmark test harness (.java file)
- Baseline measurements

**Usage in Phases 3-4**:
- Use benchmarks to validate prototype performance
- Compare against baseline for relative improvement
- Determine if optimization worth the implementation cost

### Dependencies

- **Blocked By**: morel-pml (epic)
- **Blocks**: morel-m53 (Phase 3), morel-cfd (Phase 4)

---

## Phase Bead 3: Research Mode Analysis (Option 2)

**Bead ID**: `morel-m53`
**Type**: Task
**Priority**: P1
**Status**: Open
**Duration**: 2-3 days
**Blocks**: morel-0md (Phase 5)
**Blocked By**: morel-aaj (Phase 2)
**Can Run Parallel With**: morel-cfd (Phase 4)
**Created**: 2026-01-24

### Objective

Determine if Mode Analysis (compile-time mode inference for predicates) is a viable approach for Morel. Prototype basic mode inference and provide implementation estimate.

### Context

Mode analysis originates from logic programming (Mercury, HAL, Prolog) where predicates declare which arguments are IN (input) or OUT (output). Research whether SML's type system and functional semantics can support similar capabilities.

### Scope

**Included**:
- Literature research on mode systems
- Design of mode representation in Java
- Prototype mode inference for transitive closure
- Integration design with PredicateInverter
- Full implementation estimate with confidence range

**Excluded**:
- Full implementation of mode analysis
- Integration into Expander
- Performance optimization beyond prototyping
- Magic sets or semi-naive evaluation (future work)

### Subtasks

#### 3.1 Literature Research (6-8 hours)

**Task**: Study mode analysis in related systems.

**Sources**:
1. Mercury Language (https://www.mercurylang.org/information/doc-latest/reference_manual.html)
   - Mode system documentation
   - Determinism categories: det, semidet, multi, nondet
   - Mode inference vs declaration

2. HAL Constraint Logic Programming
   - Mercury-based constraint handling
   - Mode propagation through constraints

3. Datalog Evaluation
   - Magic sets transformation
   - Semi-naive evaluation
   - Stratified negation

4. Functional Logic Languages
   - Curry mode analysis
   - Flix datalog integration

**Questions to Answer**:
- Can modes be inferred automatically without user annotations?
- What's the complexity of mode inference?
- How do modes interact with higher-order functions?
- What patterns can/cannot be handled?

**Success Criteria**:
- [ ] Mercury documentation reviewed thoroughly
- [ ] At least 3 research sources consulted
- [ ] Key findings documented (2-3 pages)
- [ ] Answers to core questions in summary

**Deliverable**: Literature summary (2-3 pages)

#### 3.2 Design Mode Representation (4-6 hours)

**Task**: Design Java data structures for mode representation.

**Design Elements**:

```java
enum ArgMode {
    IN,      // Bound before call
    OUT,     // Computed by predicate
    INOUT,   // Both read and written
    UNUSED   // Not used
}

record PredicateMode(
    String predicateName,
    List<ArgMode> argModes,
    Determinism determinism  // SINGLE, FINITE, INFINITE
) {}

class ModeInferenceEngine {
    private final TypeSystem typeSystem;
    private final Environment env;

    PredicateMode inferMode(
        Core.Fn fn,
        Set<Core.NamedPat> boundVars
    );

    boolean isModeCompatible(
        PredicateMode mode,
        List<Boolean> argumentsBound
    );
}
```

**Integration Points**:
- Called from PredicateInverter when handling function calls
- Uses TypeSystem for type consistency
- Returns mode info to guide code generation

**Success Criteria**:
- [ ] Mode representation classes designed
- [ ] Integration points identified
- [ ] API signatures sketched
- [ ] Design document with diagrams

**Deliverable**: Mode representation design document

#### 3.3 Prototype Mode Inference (6-8 hours)

**Task**: Implement minimal mode inference for transitive closure pattern.

**Scope** (Minimal Viable):
- Handle `fun f (x, y) = base(x, y) orelse recursive(...)` pattern only
- Infer IN/OUT for simple tuple arguments
- Return INFINITE for uninvertible cases

**Test Cases**:
```sml
(* Case 1: path(x, y) where x is bound *)
from x in [1,2,3], y where path (x, y)
(* Expected mode: path(IN, OUT) *)

(* Case 2: path(x, y) where y is bound *)
from y in [1,2,3], x where path (x, y)
(* Expected mode: path(OUT, IN) *)

(* Case 3: Both bound - should filter, not generate *)
from (x, y) in edges where path (x, y)
(* Expected mode: path(IN, IN) - filter mode *)
```

**Success Criteria**:
- [ ] Prototype compiles without errors
- [ ] All 3 test cases produce correct output
- [ ] Mode inference working for transitive closure pattern

**Deliverable**: Working prototype code

#### 3.4 Integration Design (4-6 hours)

**Task**: Design how mode analysis integrates with PredicateInverter.

**Integration Flow**:
```
PredicateInverter.invert(predicate, goalPats, boundPats)
    |
    v
ModeInferenceEngine.inferMode(fn, boundPats)
    |
    +---> mode.argModes = [IN, OUT]
    |
    v
If mode allows generation for goalPats:
    Generate mode-aware code
Else:
    Return null (can't invert)
```

**Changes Required**:
| File | Change | Impact |
|------|--------|--------|
| PredicateInverter.java | Add mode inference call | Medium |
| Extents.java | Pass bound variables | Low |
| New: ModeInferenceEngine.java | New class | Medium |
| New: PredicateMode.java | New record | Low |

**Success Criteria**:
- [ ] Integration flow documented
- [ ] API sketches created
- [ ] Impact analysis completed

**Deliverable**: Integration design document

#### 3.5 Implementation Estimate (2 hours)

**Task**: Provide realistic estimate for full implementation.

**Estimate Template**:
```
Minimal Mode Inference (transitive closure only):
- Design: 2 days
- Implementation: 3-5 days
- Testing: 2 days
- Integration: 2 days
- TOTAL: 9-11 days

Full Mode Analysis (general predicates):
- Design: 5 days
- Implementation: 10-15 days
- Testing: 5 days
- Integration: 5 days
- TOTAL: 25-30 days
```

**Success Criteria**:
- [ ] Detailed estimate with assumptions
- [ ] Risks identified and documented
- [ ] Confidence range provided (low/medium/high)
- [ ] Dependencies on other work identified

**Deliverable**: Implementation estimate document

### Success Criteria (Task Level)

- [ ] Literature summary completed (2-3 pages)
- [ ] Mode representation design documented with diagrams
- [ ] Prototype compiles and runs all 3 test cases
- [ ] Integration design documented with API sketches
- [ ] Implementation estimate provided with confidence range
- [ ] All risks identified and documented

### Deliverables

**Primary**: `mode-analysis-design.md`

**Contents**:
1. Literature research summary
2. Mode representation design with Java code
3. Prototype implementation code
4. Integration design with flow diagrams
5. Implementation estimate with assumptions
6. Risk assessment and mitigation strategies
7. Comparison to LinkCode approach (preliminary)

### Quality Gates

- [ ] mode-analysis-design.md exists with all sections
- [ ] Prototype produces output for all 3 test cases
- [ ] Literature summary covers at least 3 sources
- [ ] Design is consistent with Morel's type system
- [ ] Integration plan is realistic and documented

### Risk Assessment

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|-----------|
| SML type system incompatibility | Medium | High | Focus on inference, not annotations |
| Higher-order function modes | High | Medium | Limit scope to first-order predicates |
| Mode inference complexity | Medium | High | Start with pattern-matching approach |
| Integration with Expander | Medium | Medium | Study deferred grounding first |

### Handoff Specifications

**To**: Phase 5 decision maker

**Artifacts**:
- mode-analysis-design.md
- Prototype code
- Implementation estimate

**Quality Criteria**:
- [ ] Design addresses compile-time/runtime boundary
- [ ] Prototype compiles and runs basic test
- [ ] Estimate includes uncertainty range

### Dependencies

- **Blocked By**: morel-aaj (Phase 2)
- **Blocks**: morel-0md (Phase 5)
- **Parallel With**: morel-cfd (Phase 4)

---

## Phase Bead 4: Research LinkCode Pattern (Option 3)

**Bead ID**: `morel-cfd`
**Type**: Task
**Priority**: P1
**Status**: Open
**Duration**: 2-3 days
**Blocks**: morel-0md (Phase 5)
**Blocked By**: morel-aaj (Phase 2)
**Can Run Parallel With**: morel-m53 (Phase 3)
**Created**: 2026-01-24

### Objective

Determine if extending Morel's existing LinkCode pattern can solve the compile-time/runtime boundary problem for transitive closure. Prototype deferred evaluation approach similar to SQL WITH RECURSIVE.

### Context

LinkCode in `Compiler.java` handles recursive functions by creating a mutable reference that gets "fixed up" after compilation. SQL `WITH RECURSIVE` takes similar approach - defers recursive CTE evaluation to query execution. Research whether this pattern can be extended for predicates.

### Scope

**Included**:
- Deep analysis of Morel's LinkCode implementation
- SQL WITH RECURSIVE comparison and mapping
- Design of TransitiveClosureCode subclass
- Prototype implementation for simple patterns
- Fair comparison to Mode Analysis
- Implementation estimate

**Excluded**:
- Full production implementation
- Mode analysis research
- Performance optimization beyond prototyping
- Complex recursive patterns

### Subtasks

#### 4.1 Deep Dive into Morel's LinkCode (6-8 hours)

**Task**: Thoroughly understand the LinkCode pattern.

**Code to Analyze**:
```java
// Compiler.java:1004-1023
private static class LinkCode implements Code {
    private @Nullable Code refCode;

    public Object eval(EvalEnv env) {
        assert refCode != null; // link should have completed by now
        return refCode.eval(env);
    }
}
```

**Investigation Points**:
1. Where is LinkCode instantiated?
2. When is `refCode` assigned (the "fix-up" point)?
3. How does `val rec` use LinkCode?
4. What's the lifecycle: compile-time creation, runtime evaluation?
5. Can we create LinkCode for predicates (not just functions)?

**Investigation Steps**:
1. Grep for `new LinkCode` to find instantiation points
2. Grep for `refCode =` to find fix-up points
3. Trace through `compileExp` for `val rec` expressions
4. Document the compile-time vs runtime boundary
5. Identify any assumptions LinkCode makes

**Success Criteria**:
- [ ] All LinkCode instantiation points found
- [ ] Fix-up points identified and documented
- [ ] Lifecycle documented with timeline
- [ ] Assumptions and constraints listed

**Deliverable**: LinkCode pattern documentation (2-3 pages)

#### 4.2 SQL WITH RECURSIVE Comparison (4-6 hours)

**Task**: Study how SQL databases handle recursive CTEs.

**Key Concepts**:

SQL recursive CTE:
```sql
WITH RECURSIVE path(x, y) AS (
    SELECT x, y FROM edge              -- Anchor member (base case)
    UNION
    SELECT e.x, p.y                     -- Recursive member
    FROM edge e
    JOIN path p ON e.y = p.x
)
SELECT * FROM path;
```

**Evaluation Strategy**:
1. Evaluate anchor member to get initial working table
2. Repeat until working table is empty:
   a. Evaluate recursive member with current working table
   b. Add new rows to result
   c. New rows become next working table
3. Return accumulated result

**PostgreSQL Mapping**:
- WorkTableScan operator references working table
- Iteration happens in executor, not planner
- Termination when no new rows produced

**Morel Mapping**:
| SQL Concept | Morel Equivalent |
|-------------|------------------|
| Anchor member | Base case: `edge(x, y)` |
| Recursive member | Step function in `Relational.iterate` |
| Working table | `newList` parameter |
| Result accumulation | `Relational.iterate` loop |

**Success Criteria**:
- [ ] SQL execution strategy understood
- [ ] Mapping to Morel concepts clear
- [ ] Key differences documented
- [ ] Advantages/disadvantages identified

**Deliverable**: SQL comparison document with mapping table

#### 4.3 Design TransitiveClosureCode (6-8 hours)

**Task**: Design a Code subclass for deferred transitive closure evaluation.

**Proposed Design**:

```java
/**
 * Code that evaluates a transitive closure predicate at runtime.
 *
 * Instead of inverting the predicate at compile time (which fails
 * when the base case depends on runtime bindings), this Code:
 * 1. Evaluates the base case generator at runtime
 * 2. Builds the step function for iteration
 * 3. Calls Relational.iterate with both
 */
class TransitiveClosureCode implements Code {
    // Pattern components identified at compile time
    private final Core.IdPat baseCasePat;
    private final Code baseCaseCode;      // edge(x, y) - runtime evaluated
    private final Code stepFunctionCode;  // Join logic as Code

    TransitiveClosureCode(
        Core.IdPat baseCasePat,
        Code baseCaseCode,
        Code stepFunctionCode
    ) {
        this.baseCasePat = baseCasePat;
        this.baseCaseCode = baseCaseCode;
        this.stepFunctionCode = stepFunctionCode;
    }

    @Override
    public Object eval(EvalEnv env) {
        // At runtime, we can now evaluate the base case
        List<?> baseValues = (List<?>) baseCaseCode.eval(env);

        // Get the step function (also runtime)
        Applicable1<List<?>, List<?>> stepFn =
            (Applicable1<List<?>, List<?>>) stepFunctionCode.eval(env);

        // Use existing Relational.iterate implementation
        return Codes.RELATIONAL_ITERATE.apply(baseValues, stepFn);
    }
}
```

**Compilation Flow**:
```
compile(from p where path p)
    |
    v
Detect transitive closure pattern at compile time
    |
    v
Instead of: invert(path) -> INFINITE extent (FAILS)
Do: Create TransitiveClosureCode(pattern, baseCaseCode, stepCode)
    |
    v
At runtime:
    baseCaseCode.eval() -> actual edge values
    stepCode.eval() -> step function
    iterate(edges, stepFn) -> transitive closure result
```

**Key Design Insight**: Defer base case evaluation to runtime, but detect pattern at compile time.

**Success Criteria**:
- [ ] TransitiveClosureCode class designed with clear API
- [ ] Compilation flow documented
- [ ] Integration points identified
- [ ] Class diagram created

**Deliverable**: Design document with class diagram and flow

#### 4.4 Prototype Implementation (6-8 hours)

**Task**: Implement minimal TransitiveClosureCode.

**Scope**:
- Support `fun path (x, y) = edge (x, y) orelse ...` pattern only
- Assume simple tuple arguments
- No mode analysis (all arguments treated as output)

**Files to Create**:
- `src/main/java/net/hydromatic/morel/compile/TransitiveClosureCode.java`

**Files to Modify**:
- `Compiler.java`: Add case for transitive closure pattern detection

**Test Case**:
```sml
val edges = [(1, 2), (2, 3)];
fun edge (x, y) = (x, y) elem edges;
fun path (x, y) = edge (x, y) orelse
  (exists z where path (x, z) andalso edge (z, y));
from p where path p;
(* Expected: [(1, 2), (2, 3), (1, 3)] *)
```

**Success Criteria**:
- [ ] TransitiveClosureCode compiles
- [ ] Pattern detection working
- [ ] Test case produces expected output
- [ ] No runtime errors

**Deliverable**: Working prototype that passes test case

#### 4.5 Fair Comparison to Mode Analysis (3-4 hours)

**Task**: Create objective comparison between Option 2 and Option 3.

**Comparison Criteria**:

| Criterion | Mode Analysis (Option 2) | LinkCode Pattern (Option 3) |
|-----------|-------------------------|----------------------------|
| **Implementation Complexity** | | |
| New classes | 2-3 (Mode types, inference) | 1-2 (TransitiveClosureCode) |
| Lines of code (estimate) | 500-800 | 200-400 |
| Integration points | 3-4 | 2 |
| **Architectural Fit** | | |
| Aligns with Morel patterns | Medium (new concept) | High (extends LinkCode) |
| Compile-time vs runtime | More compile-time | More runtime |
| Future extensibility | High (general mechanism) | Medium (specific pattern) |
| **Performance** | | |
| Compile-time overhead | Medium (inference) | Low (pattern match only) |
| Runtime overhead | Low | Low |
| **Maintainability** | | |
| Conceptual complexity | High | Medium |
| Test coverage feasibility | Medium | High |
| **Risk** | | |
| Implementation risk | Medium-High | Low-Medium |
| Timeline risk | Medium | Low |

**Success Criteria**:
- [ ] Comparison is objective (no bias)
- [ ] Both approaches scored fairly
- [ ] Rationale provided for each score
- [ ] Document ready for Phase 5 decision makers

**Deliverable**: Comparison document with scored matrix

### Success Criteria (Task Level)

- [ ] LinkCode pattern thoroughly documented
- [ ] SQL WITH RECURSIVE comparison completed
- [ ] TransitiveClosureCode design documented
- [ ] Prototype compiles and passes basic test
- [ ] Fair comparison to Mode Analysis documented
- [ ] Implementation estimate provided

### Deliverables

**Primary**: `linkcode-pattern-design.md`

**Contents**:
1. LinkCode pattern analysis (2-3 pages)
2. SQL WITH RECURSIVE comparison with mapping
3. TransitiveClosureCode design with diagrams
4. Prototype implementation code
5. Fair comparison matrix to Mode Analysis
6. Implementation estimate with assumptions
7. Risk assessment

### Quality Gates

- [ ] linkcode-pattern-design.md exists with all sections
- [ ] Prototype handles simple transitive closure test case
- [ ] Design integrates with existing LinkCode pattern
- [ ] Comparison is objective and quantified

### Risk Assessment

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|-----------|
| LinkCode insufficient for predicates | Medium | High | Design new Code subclass |
| Step function Code generation | Medium | Medium | Use existing FromBuilder |
| Integration with FROM handling | Medium | Medium | Study Compiler.compileFrom |
| Pattern detection complexity | Low | Medium | Start with explicit pattern |

### Handoff Specifications

**To**: Phase 5 decision maker

**Artifacts**:
- linkcode-pattern-design.md
- Prototype code
- Fair comparison document

**Quality Criteria**:
- [ ] Design integrates with existing LinkCode
- [ ] Prototype handles simple transitive closure
- [ ] Comparison is objective and quantified

### Dependencies

- **Blocked By**: morel-aaj (Phase 2)
- **Blocks**: morel-0md (Phase 5)
- **Parallel With**: morel-m53 (Phase 3)

---

## Phase Bead 5: Informed Architectural Decision

**Bead ID**: `morel-0md`
**Type**: Task
**Priority**: P1
**Status**: Open
**Duration**: 1 day (9-10 hours)
**Blocks**: None (final decision point)
**Blocked By**: morel-m53 (Phase 3), morel-cfd (Phase 4)
**Created**: 2026-01-24

### Objective

Make well-reasoned architectural decision between Mode Analysis (Option 2) and LinkCode Pattern (Option 3) based on research findings, create decision record, and plan implementation for chosen approach.

### Context

After Phases 3 and 4, both approaches will be researched with prototypes implemented and designs documented. This phase synthesizes findings, scores approaches using decision matrix, and determines which path forward.

### Scope

**Included**:
- Synthesis of Phase 3 and Phase 4 findings
- Completion of weighted decision matrix
- Stakeholder review and input
- Formal decision documentation
- Implementation plan for chosen approach
- Beads creation for implementation phase

**Excluded**:
- Implementation of chosen approach
- Performance optimization
- Further prototyping

### Subtasks

#### 5.1 Synthesize Research Findings (2-3 hours)

**Task**: Compile findings from Phases 3 and 4 into comprehensive summary.

**Summary Document Structure**:
1. Executive Summary (1 paragraph)
2. Mode Analysis Findings (from Phase 3)
   - Key learnings from literature
   - Prototype results and insights
   - Implementation estimate with risks
   - Architectural fit assessment
3. LinkCode Pattern Findings (from Phase 4)
   - Key learnings from SQL research
   - Prototype results and insights
   - Implementation estimate with risks
   - Architectural fit assessment
4. Head-to-Head Comparison (preliminary)
5. Initial Recommendation (based on research)

**Success Criteria**:
- [ ] All Phase 3 deliverables summarized
- [ ] All Phase 4 deliverables summarized
- [ ] Findings presented objectively
- [ ] Key insights highlighted

**Deliverable**: Research synthesis document (4-5 pages)

#### 5.2 Decision Matrix Evaluation (2 hours)

**Task**: Score both approaches on weighted criteria.

**Decision Matrix**:

| Criterion | Weight | Option 2 Score | Option 3 Score | Notes |
|-----------|--------|----------------|----------------|-------|
| Implementation Complexity | 25% | 1-5 | 1-5 | Fewer LOC better |
| Architectural Fit | 30% | 1-5 | 1-5 | Alignment with Morel |
| Performance | 20% | 1-5 | 1-5 | Compile + runtime overhead |
| Maintainability | 15% | 1-5 | 1-5 | Conceptual clarity, testing |
| Risk Profile | 10% | 1-5 | 1-5 | Implementation + integration |
| **WEIGHTED TOTAL** | 100% | **X.X** | **X.X** | Winner: [Option] |

**Scoring Guide**:
- 5: Excellent - clearly superior, no concerns
- 4: Good - minor concerns, acceptable
- 3: Acceptable - some concerns, manageable
- 2: Marginal - significant concerns, risky
- 1: Poor - major issues, not recommended

**Success Criteria**:
- [ ] All criteria scored for both options
- [ ] Scores justified with phase data
- [ ] Weighted totals calculated
- [ ] Winner identified clearly

**Deliverable**: Completed decision matrix with detailed scoring notes

#### 5.3 Stakeholder Review (1-2 hours)

**Task**: Present findings and get input from stakeholders.

**Review Agenda**:
1. Problem recap (5 min) - Why we're here
2. Research summary (10 min) - What we learned
3. Decision matrix review (10 min) - Scoring rationale
4. Discussion and questions (15 min) - Stakeholder input
5. Decision confirmation (5 min) - Final approval

**Stakeholders**:
- Development lead (decision authority)
- Code review expert (quality perspective)

**Success Criteria**:
- [ ] Both stakeholders present
- [ ] All agenda items covered
- [ ] Decision confirmed and approved
- [ ] Concerns addressed

**Deliverable**: Meeting notes with decision confirmation

#### 5.4 Document Decision (2 hours)

**Task**: Update DECISION-LOG.md with formal decision record.

**Decision Record Template**:

```markdown
## Decision 7: Option 2 vs Option 3 Selection

**Date**: YYYY-MM-DD
**Decision Maker**: [Name/Role]
**Status**: DECIDED

### The Options

#### Option 2: Mode Analysis
[Summary from Phase 3]

#### Option 3: LinkCode Pattern
[Summary from Phase 4]

### Decision Matrix Results
[Insert completed matrix with scores and notes]

### Chosen Approach
**Selected**: Option [2/3] - [Approach Name]

### Rationale
1. [Key reason 1 from matrix]
2. [Key reason 2 from matrix]
3. [Key reason 3 from risks/fit]

### Deferred Alternative
Option [3/2] is not selected because:
- [Reason 1]
- [Reason 2]

May be reconsidered if:
- [Condition 1]
- [Condition 2]

### Implementation Timeline
- Phase A: [Task] - [Duration]
- Phase B: [Task] - [Duration]
- Quality Gate: [Checkpoint]
```

**Success Criteria**:
- [ ] Decision record formatted correctly
- [ ] All matrix scores included
- [ ] Rationale clear and compelling
- [ ] DECISION-LOG.md updated

**Deliverable**: Updated DECISION-LOG.md

#### 5.5 Create Implementation Plan (2 hours)

**Task**: Detailed plan for chosen approach.

**Plan Components**:
1. Task breakdown with estimates
2. Dependencies between tasks
3. Success criteria per task
4. Quality gates and checkpoints
5. Risk mitigations
6. Timeline with milestones
7. Bead definitions for tracking

**Deliverable**: `implementation-plan-[chosen-option].md`

**Contents**:
- Overview and context
- Task list (5-10 tasks depending on option)
- Estimated timeline
- Quality gates
- Risk assessments
- Bead specifications

**Success Criteria**:
- [ ] Plan is detailed and actionable
- [ ] Tasks have clear success criteria
- [ ] Quality gates defined
- [ ] Bead definitions ready for creation

**Deliverable**: Implementation plan for chosen approach

### Success Criteria (Task Level)

- [ ] Research synthesis document complete
- [ ] Decision matrix completed with all scores
- [ ] Stakeholder review conducted and recorded
- [ ] Decision documented in DECISION-LOG.md with clear rationale
- [ ] Implementation plan created for chosen approach
- [ ] Implementation beads ready to create
- [ ] Next phase clearly defined

### Deliverables

**Primary Deliverables**:
1. Research synthesis document (4-5 pages)
2. Decision matrix (completed with scoring notes)
3. Stakeholder review meeting notes
4. Updated DECISION-LOG.md (Decision 7 record)
5. Implementation plan for chosen approach

### Quality Gates

**Before Decision**:
- [ ] Phase 3 design document exists
- [ ] Phase 4 design document exists
- [ ] Both prototypes compiling and running
- [ ] Fair comparison completed

**Decision Point**:
- [ ] Decision matrix completed with objective scoring
- [ ] Stakeholder review conducted
- [ ] Decision documented with clear rationale
- [ ] Implementation plan ready

**After Decision**:
- [ ] Implementation beads created
- [ ] Next phase clearly defined
- [ ] Timeline established
- [ ] Team assigned

### Risk Assessment

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|-----------|
| Analysis paralysis (can't decide) | Low | Medium | Use weighted matrix, hard deadline |
| Stakeholder disagreement | Low | Medium | Discuss trade-offs, get consensus |
| Implementation plan incomplete | Low | Low | Use previous phases' estimates |
| Scope creep in implementation | Medium | Medium | Define clear boundaries |

### Handoff Specifications

**To**: Implementation team (for chosen approach)

**Artifacts**:
- Implementation plan document
- Implementation beads (created during this phase)
- Phase 3 or Phase 4 design (depending on choice)
- Research synthesis for context

**Quality Criteria**:
- [ ] Plan has clear task breakdown
- [ ] Success criteria are measurable
- [ ] Timeline is realistic
- [ ] Risks identified and mitigated

### Dependencies

- **Blocked By**: morel-m53 (Phase 3), morel-cfd (Phase 4)
- **Blocks**: Implementation phase beads (to be created)

---

## Bead Dependency Graph

```
morel-pml (Epic)
  │
  └─ morel-aaj (Phase 2)
       │
       ├─ morel-m53 (Phase 3) ─┐
       │                       │
       ├─ morel-cfd (Phase 4) ─┤
       │                       │
       └─ morel-0md (Phase 5) ─┴─ Implementation Phase
```

**Critical Path**: morel-aaj → max(morel-m53, morel-cfd) → morel-0md
**Duration**: 4-5 days
**Parallelization**: Phase 3 and Phase 4 run simultaneously (2-3 days each)

---

## Summary

**Total Beads Created**: 5 (1 epic + 4 phases)
**Total Duration**: 4-5 days
**Status**: All beads created and linked, ready for execution

### Bead IDs
- Epic: `morel-pml`
- Phase 2: `morel-aaj` (1 day)
- Phase 3: `morel-m53` (2-3 days)
- Phase 4: `morel-cfd` (2-3 days, parallel)
- Phase 5: `morel-0md` (1 day)

### Next Steps
1. Review specifications with team
2. Execute Phase 2 immediately
3. Spawn Phase 3 and Phase 4 research agents in parallel
4. Track progress via bead status updates
5. Conduct Phase 5 decision review at end of research

**Last Updated**: 2026-01-24
**Status**: BEADS CREATED - READY FOR EXECUTION
