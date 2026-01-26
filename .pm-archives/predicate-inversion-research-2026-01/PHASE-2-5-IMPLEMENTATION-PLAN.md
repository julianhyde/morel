# Phases 2-5 Implementation Plan: Evidence-Driven Option Selection

**Created**: 2026-01-24
**Status**: PLAN REVISED - Audit Clarifications Applied
**Context**: Phase 1 (Option 1 cardinality check) identified architectural blocker
**Approach**: 5-phase evidence-based decision process
**Revision**: Addresses plan-auditor (1 blocker, 4 warnings) + substantive-critic (3 flaws, 6 issues)
**Audit Status**: APPROVED WITH CLARIFICATIONS - Ready for execution after revisions

---

## Executive Summary

Phase 3b implementation revealed that the deferred grounding mechanism in Expander is tightly coupled to Relational.iterate. Simple fallbacks using infinite extents fail at runtime. This plan details Phases 2-5 which will:

1. **Phase 2**: Define objective performance criteria (1 day)
2. **Phase 3**: Research Mode Analysis approach (2-3 days)
3. **Phase 4**: Research LinkCode Pattern approach (2-3 days)
4. **Phase 5**: Make informed architectural decision (1 day)

**Total Timeline**: 7-8 days realistic (Phases 3-4 run in parallel)
- Single developer: 7-8 days
- Two developers (parallel research): 5-6 days
- Previous estimate (4-5 days) was optimistic without accounting for identified gaps

---

## Architectural Context

### The Core Problem

At compile time, PredicateInverter attempts to invert the transitive closure pattern:

```sml
fun path (x, y) = edge (x, y) orelse
  (exists z where path (x, z) andalso edge (z, y))
```

**The Issue**: `edge(x, y)` is a user-defined function where `edges` is bound at runtime. At compile time, we cannot know what values `edges` contains, so inversion produces INFINITE cardinality, which fails when materialized at runtime.

### Key Architectural Components

| Component | Location | Role |
|-----------|----------|------|
| PredicateInverter | `compile/PredicateInverter.java:465-515` | Attempts predicate-to-generator transformation |
| LinkCode | `compile/Compiler.java:1004-1023` | Handles recursive function self-reference |
| RELATIONAL_ITERATE | `eval/Codes.java:2902-2927` | Runtime fixed-point iteration |
| Expander | `compile/Expander.java:95-111` | Deferred grounding checks |

### Boundary Classification

This is a **compile-time/runtime boundary problem** - the same pattern appears in:
- SQL `WITH RECURSIVE` (deferred to query execution)
- Datalog fixed-point semantics (semi-naive evaluation)
- Prolog mode declarations (in/out argument annotations)
- Mercury determinism categories

---

## Phase 1.5: Baseline Performance Measurement ⚠️ CRITICAL PREREQUISITE

**Duration**: 1 day (4-6 hours)
**Dependencies**: Phase 1 (Option 1 cardinality check) must be implemented
**Deliverable**: `baseline-performance.md`, benchmark test cases
**Quality Gate**: Must complete BEFORE committing to Phases 2-5

### Objective

Measure actual cartesian product performance to validate that optimization is necessary. This prevents investing 4-5 days in Phases 2-5 if Phase 1 already meets performance targets.

### Critical Validation Question

**ISSUE**: Plan currently assumes optimization is needed without measuring baseline. If cartesian product is acceptable, Phases 2-5 are wasted effort.

**DECISION GATE**:
- If baseline_small < 100ms AND baseline_medium < 500ms: **SKIP Phases 2-5**, accept Phase 1 cartesian product
- If baseline exceeds thresholds: **PROCEED to Phases 2-5**

### Subtasks

#### 1.5.1 Implement Phase 1 Option 1 (1 hour)

**Task**: Implement cardinality check in PredicateInverter to enable cartesian product fallback

**Code**: Add check before building Relational.iterate:
```java
if (baseCaseResult.generator.cardinality == Cardinality.INFINITE) {
    return null;  // Falls back to cartesian product in Expander
}
```

**Files Modified**:
- PredicateInverter.java: ~475-480

**Success**: Tests pass, no "infinite: int * int" errors

#### 1.5.2 Measure Cartesian Product Performance (3 hours)

**Task**: Run transitive closure queries with Phase 1 implementation, measure actual latency.

**Test Cases**:
1. **Small graph**: 20 edges, 10 vertices
   - Baseline latency: ___ ms
   - Acceptable? (target < 100ms)
2. **Medium graph**: 500 edges, 100 vertices
   - Baseline latency: ___ ms
   - Acceptable? (target < 500ms)
3. **Large graph**: 5000 edges, 500 vertices
   - Baseline latency: ___ ms
   - Optimization required?

**Measurements to Record**:
- Execution time (avg of 5 runs)
- Memory usage before/after
- Result set size
- CPU time

**Deliverable**: `baseline-performance.md` with actual measurements

#### 1.5.3 Decide: Optimize or Accept Phase 1? (1-2 hours)

**Task**: Compare baseline measurements to thresholds.

**Decision Logic**:
```
IF baseline_small < 100ms AND
   baseline_medium < 500ms AND
   baseline_large < 5s:
    → Phase 1 cartesian product is acceptable
    → RECOMMENDATION: Accept Phase 1, skip Phases 2-5
    → Document: "Optimization not justified by current performance"

ELSE IF baseline exceeds thresholds:
    → Phase 1 cartesian product is inadequate
    → RECOMMENDATION: Proceed to Phases 2-5
    → Document: "Optimization required for production use"
```

**Deliverable**: Decision document with clear go/no-go for Phases 2-5

### Success Criteria

- [ ] Phase 1 cardinality check implemented
- [ ] Benchmark test cases execute successfully
- [ ] Baseline measurements recorded for small/medium/large graphs
- [ ] Decision documented: Optimize OR Accept Phase 1
- [ ] If optimizing: Clear justification for Phases 2-5 investment

### Risk: Skip Unnecessary Work

**This phase prevents the risk identified by plan-auditor**: "Plan assumes optimization is needed but doesn't validate this assumption. Could waste 4-5 days if cartesian product is acceptable."

---

## Phase 2: Define Performance Criteria

**Duration**: 1 day (4-6 hours)
**Dependencies**: Phase 1.5 complete with decision to optimize
**Deliverable**: `performance-criteria.md`

### Objective

Establish objective, quantitative thresholds for when cartesian product fallback is acceptable vs when optimization is required.

### Subtasks

#### 2.1 Establish Input Size Thresholds (1.5 hours)

**Task**: Define collection size categories with expected behavior.

| Category | Edge Count | Vertex Count | Expected Operations | Acceptable? |
|----------|------------|--------------|---------------------|-------------|
| Small | < 100 | < 50 | < 10,000 | Yes (< 100ms) |
| Medium | 100-1,000 | 50-500 | < 1,000,000 | Maybe (100ms-1s) |
| Large | > 1,000 | > 500 | > 1,000,000 | No (> 1s) |

**Algorithm Complexity**: Transitive closure is O(V * E) for sparse graphs, O(V^3) worst case.

**Grounding in Baseline**: Use Phase 1.5 baseline measurements to inform realistic thresholds.
- Example: If baseline_medium=450ms, Medium category threshold might be 500-1000ms for optimization
- Example: If baseline_small=25ms, Small category threshold might be 50-100ms

**Deliverable**: Size threshold table with rationale grounded in Phase 1.5 baseline.

#### 2.2 Define Latency Acceptance Criteria (1 hour)

**Task**: Establish latency thresholds by use case.

| Use Case | Acceptable Latency | Trigger for Optimization |
|----------|-------------------|--------------------------|
| Interactive query (REPL) | < 100ms | > 200ms |
| Batch processing | < 1s | > 2s |
| Analytics workload | < 10s | > 30s |

**Deliverable**: Latency criteria table.

#### 2.3 Create Benchmark Test Harness (2 hours)

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

#### 2.4 Document Decision Trigger (0.5 hours)

**Task**: Define the exact criteria that triggers optimization work.

**Trigger Point**:
```
IF benchmark_medium_graph > 500ms THEN optimize_required = true
IF benchmark_small_graph > 200ms THEN investigate_further = true
IF benchmark_large_graph > 5s THEN optimization_critical = true
```

### Success Criteria

- [ ] performance-criteria.md written with quantitative thresholds
- [ ] Benchmark test harness compiles and runs
- [ ] At least 3 representative test cases defined
- [ ] Decision trigger documented numerically
- [ ] Baseline measurements recorded

### Handoff to Phases 3-4

Output provides:
- Concrete performance targets for prototype validation
- Benchmark infrastructure for comparing approaches
- Objective decision criteria for Phase 5

---

## Phase 3: Research Mode Analysis (Option 2)

**Duration**: 3-4 days (revised from 2-3 days)
- Literature + paradigm investigation: 8-10 hours (was 6-8 hours)
- Design + deferred evaluation: 10-12 hours (was 4-6 hours)
- Prototype + testing: 6-8 hours
- Integration + estimate: 8-10 hours
- **Rationale**: Added Subtask 3.5 (deferred evaluation design) + deeper paradigm investigation
**Dependencies**: Phase 2 complete
**Deliverable**: `mode-analysis-design.md`
**Can Run Parallel With**: Phase 4

### Objective

Determine if Mode Analysis (compile-time mode inference for predicates) is a viable approach for Morel.

### Background

Mode analysis originates from logic programming (Mercury, HAL, Prolog) where predicates declare which arguments are:
- **IN (input)**: Bound at call time
- **OUT (output)**: Computed by the predicate

Example in Mercury:
```mercury
:- pred path(int::in, int::out) is nondet.
```

### Subtasks

#### 3.1 Literature Research (6-8 hours)

**Task**: Study mode analysis in related systems.

**Sources to Review**:

1. **Mercury Language**
   - Mode system documentation
   - Determinism categories: det, semidet, multi, nondet
   - Mode inference vs mode declaration
   - URL: https://www.mercurylang.org/information/doc-latest/reference_manual.html

2. **HAL Constraint Logic Programming**
   - Mercury-based constraint handling
   - Mode propagation through constraints

3. **Datalog Evaluation**
   - Magic sets transformation
   - Semi-naive evaluation
   - Stratified negation

4. **Functional Logic Languages**
   - Curry mode analysis
   - Flix datalog integration

**Questions to Answer**:
- Can modes be inferred automatically without user annotations?
- What's the complexity of mode inference?
- How do modes interact with higher-order functions?
- What patterns can/cannot be handled?

**Deliverable**: Literature summary (2-3 pages) with key findings.

#### 3.2 Design Mode Representation (4-6 hours)

**Task**: Design Java data structures for mode representation.

**Proposed Design**:

```java
// Mode for a single argument
enum ArgMode {
    IN,      // Bound before call
    OUT,     // Computed by predicate
    INOUT,   // Both read and written
    UNUSED   // Not used
}

// Mode signature for a predicate
record PredicateMode(
    String predicateName,
    List<ArgMode> argModes,
    Determinism determinism  // SINGLE, FINITE, INFINITE
) {}

// Mode inference engine
class ModeInferenceEngine {
    private final TypeSystem typeSystem;
    private final Environment env;

    // Given a function and known bound variables, infer argument modes
    PredicateMode inferMode(
        Core.Fn fn,
        Set<Core.NamedPat> boundVars
    );

    // Check if a mode is compatible with a calling context
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

**Deliverable**: Mode representation design document.

#### 3.3 Prototype Mode Inference (6-8 hours)

**Task**: Implement minimal mode inference for transitive closure pattern.

**Scope** (Minimal Viable):
- Handle `fun f (x, y) = base(x, y) orelse recursive(...)` pattern
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

**Deliverable**: Working prototype that passes test cases.

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

**Deliverable**: Integration design document with API sketches.

#### 3.5 Design Deferred Evaluation Mechanism (4-6 hours) ⚠️ CRITICAL

**Task**: Design how mode-aware code defers base case evaluation to runtime.

**CRITICAL BLOCKER ADDRESSED**: Plan-auditor identified that Mode Analysis identifies modes (IN/OUT) but doesn't provide the deferred evaluation mechanism needed to solve the compile-time/runtime boundary problem.

**Problem Being Solved**:
- Mode Analysis says "path(IN, OUT)" means "generate y from x"
- But generating y requires evaluating `edge(x, y)` at some point
- Which requires `edges` collection available at runtime
- How do we defer this evaluation?

**Design Options to Evaluate**:

1. **Option 3A: Mode Analysis → Core.Apply Generation** (Converges with LinkCode)
   - Mode inference determines [IN, OUT]
   - Generate Core.Apply for Relational.iterate
   - Effectively becomes LinkCode approach with mode guidance
   - **Issue**: May converge with Option 3, reducing differentiation

2. **Option 3B: Mode-Aware Extent with Runtime Deferral**
   - Mode inference creates a generator that defers edge evaluation
   - Extent wraps the deferred computation
   - Evaluated only when needed at runtime

3. **Option 3C: Hybrid: Mode Hints + Runtime Validation**
   - Mode inference provides hints in comments
   - Runtime code validates mode constraints at execution
   - More flexible but requires runtime overhead

**Questions to Answer**:
- Can mode-aware code solve Expander.deferred grounding issue?
- Does Mode Analysis converge with LinkCode (both generate Core.Apply)?
- What's the integration point with Expander?

**Deliverable**: Deferred evaluation design document showing how modes + deferred evaluation work together.

#### 3.6 Implementation Estimate (2 hours)

**Task**: Provide realistic estimate for full implementation.

**Estimate Template**:
```
Minimal Mode Inference (transitive closure only):
- Design: 2 days (including deferred evaluation mechanism)
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

**Deliverable**: Detailed estimate with assumptions and risks.

### Success Criteria

- [ ] Literature summary completed
- [ ] Mode representation design documented
- [ ] Prototype **passes** basic test case with correct output (not just "runs")
- [ ] Deferred evaluation mechanism designed (Subtask 3.5)
- [ ] Integration design documented with Expander interaction
- [ ] Implementation estimate provided with confidence range
- [ ] Risks identified and documented

**Quality Gate Enhancement**: Prototype must produce correct transitive closure output, not just compile/run.

### Risk Assessment

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|-----------|
| SML/Mercury paradigm mismatch | Medium-High | High | Add Subtask 3.1 validation: "Can mode inference work for first-class functions?" If NO, Option 2 invalid. Design hybrid if partial: mode inference + runtime validation |
| Higher-order function modes | High | Medium | Limit scope to first-order predicates, recognize limitation |
| Mode inference complexity | Medium | High | Start with pattern-matching approach, don't attempt full inference |
| Deferred evaluation design incomplete | Medium | High | Subtask 3.5 must answer: Does Mode Analysis converge with LinkCode? |
| Integration with Expander | Medium | Medium | Study deferred grounding first, clarify interaction points |

---

## Phase 4: Research LinkCode Approach (Option 3)

**Duration**: 2-3 days
**Dependencies**: Phase 2 complete
**Deliverable**: `linkcode-pattern-design.md`
**Can Run Parallel With**: Phase 3

### Objective

Determine if extending Morel's existing LinkCode pattern can solve the compile-time/runtime boundary problem for transitive closure.

### Background

LinkCode in `Compiler.java` handles recursive functions by creating a mutable reference that gets "fixed up" after compilation. This defers the actual function resolution to runtime.

SQL `WITH RECURSIVE` takes a similar approach - the recursive CTE is not evaluated at query compile time, but deferred to query execution.

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

**Questions to Answer**:
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

**Deliverable**: LinkCode pattern documentation (2-3 pages).

#### 4.2 SQL WITH RECURSIVE Comparison (4-6 hours)

**Task**: Study how SQL databases handle recursive CTEs.

**Key Concepts**:

SQL recursive CTE structure:
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

**PostgreSQL Implementation** (conceptual):
- WorkTableScan operator references working table
- Iteration happens in executor, not planner
- Termination when no new rows produced

**Mapping to Morel**:
| SQL Concept | Morel Equivalent |
|-------------|------------------|
| Anchor member | Base case: `edge(x, y)` |
| Recursive member | Step function in `Relational.iterate` |
| Working table | `newList` parameter |
| Result accumulation | `Relational.iterate` loop |

**Deliverable**: SQL comparison document with mapping table.

#### 4.3 Design Core.Apply Generation for Relational.iterate (6-8 hours) ⚠️ CLARIFIED

**Task**: Design how PredicateInverter generates Core.Apply for deferred transitive closure evaluation.

**CRITICAL CLARIFICATION (Plan-Auditor)**: Plan said "TransitiveClosureCode class" but PredicateInverter works at **Core IR level**, not Code level. Should generate `Core.Apply` node, not implement `Code` interface.

**Corrected Design**:

**Phase**: PredicateInverter (Core IR level)
```java
// At compile time: Detect transitive closure pattern
// Instead of: invert(path) -> INFINITE extent (FAILS)
// Do: Create Core.Apply node

Core.Exp baseCaseExp = ...;           // Core IR for base case: edge(x, y)
Core.Exp stepFunctionExp = ...;       // Core IR for step function
Core.Exp iterateCall = core.apply(
    typeSystem,
    core.apply(typeSystem,
        core.functionLiteral(typeSystem, BuiltIn.RELATIONAL_ITERATE),
        baseCaseExp),
    stepFunctionExp);

return new Generator(..., iterateCall, ...);  // Return generator with Core.Apply
```

**Later**: Compiler Phase (Code generation level)
```java
// Compiler.compileExp(iterateCall) converts Core.Apply to Code
// This evaluates at RUNTIME when all bindings available
→ ApplyCode evaluates RELATIONAL_ITERATE
  → baseCaseExp.eval(env) → actual edge values
  → stepFunctionExp.eval(env) → step function
  → iterate(edges, stepFn) → transitive closure result
```

**Key Insight**: **Deferral happens at compilation boundary**, not at Code level.
- **Compile time**: Detect pattern, create Core.Apply node
- **Code generation**: Core.Apply → ApplyCode (defers evaluation)
- **Runtime**: ApplyCode evaluates when `edges` binding available

**Integration Flow Diagram**:
```
PredicateInverter.invert(pathPredicate, goalPats)
  ↓
Detect: path(x,y) = edge(x,y) orelse ... (transitive closure pattern)
  ↓
Recognize: Can't invert edge(x,y) at compile time (edges is runtime-bound)
  ↓
Instead: Create Core.Apply(RELATIONAL_ITERATE, edgeExpr, stepFnExpr)
  ↓
Return: Generator(Core.Apply)  [NOT infinite extent]
  ↓
[Later] Compiler.compileExp(Core.Apply) → ApplyCode
  ↓
[Runtime] ApplyCode.eval(env) → RELATIONAL_ITERATE.apply(...)
```

**Deliverable**: Design document with Core IR flow and compilation integration.

#### 4.4 Prototype Implementation (6-8 hours)

**Task**: Implement Core.Apply generation for transitive closure in PredicateInverter.

**Scope**:
- Support `fun path (x, y) = edge (x, y) orelse ...` pattern only
- Assume simple tuple arguments
- Generate Core.Apply for Relational.iterate
- No new Code classes needed (Core.Apply compiles to ApplyCode)

**Files to Modify**:
- `PredicateInverter.java`: Add transitive closure pattern detection + Core.Apply generation
- `Extents.java`: Hook into new PredicateInverter capability (may already exist)

**Test Case**:
```sml
val edges = [(1, 2), (2, 3)];
fun edge (x, y) = (x, y) elem edges;
fun path (x, y) = edge (x, y) orelse
  (exists z where path (x, z) andalso edge (z, y));
from p where path p;
(* Expected: [(1, 2), (2, 3), (1, 3)] *)
```

**Deliverable**: Working prototype that passes the test case.

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

**Deliverable**: Comparison document with scored matrix.

### Success Criteria

- [ ] LinkCode pattern thoroughly documented
- [ ] SQL WITH RECURSIVE comparison completed
- [ ] Core.Apply generation design documented with compilation flow
- [ ] Prototype **passes** transitive closure test case: produces [(1,2),(2,3),(1,3)]
- [ ] Fair comparison to Mode Analysis documented with objective criteria
- [ ] Implementation estimate provided with confidence range

**Quality Gate**: Prototype must generate correct transitive closure output.

### Risk Assessment

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|-----------|
| Core.Apply generation incomplete | Medium | High | Subtask 4.3 must show complete Core IR → Code compilation flow |
| Step function expression generation | Medium | Medium | Study existing SuchThatShuttle for pattern |
| Integration with Extents | Medium | Medium | Hook point already anticipated (line 707-710) |
| Pattern detection complexity | Low | Medium | Start with explicit pattern detection (base orelse recursive) |

---

## Phase 5: Informed Decision

**Duration**: 1 day
**Dependencies**: Phases 3 and 4 complete
**Deliverable**: Updated `DECISION-LOG.md`, `implementation-plan-chosen-approach.md`

### Objective

Make a well-reasoned architectural decision between Mode Analysis (Option 2) and LinkCode Pattern (Option 3) based on research findings.

### Subtasks

#### 5.1 Synthesize Research Findings (2-3 hours)

**Task**: Compile findings from Phases 3 and 4 into summary.

**Summary Document Structure**:
1. Executive Summary (1 paragraph)
2. Mode Analysis Findings
   - Key learnings
   - Prototype results
   - Implementation estimate
   - Risks
3. LinkCode Pattern Findings
   - Key learnings
   - Prototype results
   - Implementation estimate
   - Risks
4. Head-to-Head Comparison
5. Recommendation

**Deliverable**: Research synthesis document.

#### 5.2 Decision Matrix Evaluation with Tie-Breaking (3 hours)

**Task**: Score both approaches on weighted criteria with tie-breaking rules.

**Decision Matrix Template**:

| Criterion | Weight | Option 2 (Mode) Score | Option 3 (LinkCode) Score | Rationale |
|-----------|--------|----------------------|---------------------------|-----------|
| Implementation Complexity | 25% | 1-5 | 1-5 | (Populate with evidence) |
| Architectural Fit | 30% | 1-5 | 1-5 | (Populate with evidence) |
| Performance | 20% | 1-5 | 1-5 | (Populate with evidence) |
| Maintainability | 15% | 1-5 | 1-5 | (Populate with evidence) |
| Risk Profile | 10% | 1-5 | 1-5 | (Populate with evidence) |
| **WEIGHTED TOTAL** | 100% | X.XX | X.XX | **Decision** |

**Scoring Guide** (Evidence-Based):
- 5: Excellent - prototype validates clearly superior performance
- 4: Good - prototype shows minor concerns
- 3: Acceptable - prototype works but has some concerns
- 2: Marginal - prototype reveals significant issues
- 1: Poor - prototype fails or has major issues

**WEIGHT JUSTIFICATION** (Populated at plan time):
- **Architectural Fit (30%)**: Morel is research prototype, architectural alignment critical for maintainability
- **Implementation Complexity (25%)**: Development budget constraints, realistic timeline important
- **Performance (20%)**: Optimization is goal but not primary success criterion
- **Maintainability (15%)**: Long-term sustainability for future extensions
- **Risk (10%)**: Both solutions are research explorations, can iterate if issues arise

*Note: If context changed (production system, performance critical), weights should be recalibrated.*

**TIE-BREAKING RULES** (Must be defined before evaluation):

1. **If scores differ by < 0.5 points** (e.g., 3.2 vs 3.7):
   - Re-evaluate scoring against prototype evidence
   - If still tied: "Prefer simpler solution" (lower Implementation Complexity score)
   - If still tied: Development lead has final authority

2. **If scores are exactly tied** (e.g., 3.5 vs 3.5):
   - Stakeholder discussion on strategic preference
   - Consider hybrid approach (Mode hints + LinkCode deferral)
   - If no consensus: Default to LinkCode (extends proven pattern vs new infrastructure)

3. **If one prototype fails completely**:
   - Choose working option if other fails
   - If both fail: Keep Option 1 (cartesian product) as permanent solution
   - Document lessons learned for future optimization attempts

**Deliverable**: Completed decision matrix with scores, rationale, and tie-breaking application if needed.

#### 5.3 Stakeholder Review (1-2 hours)

**Task**: Present findings and get input.

**Review Agenda**:
1. Problem recap (5 min)
2. Research summary (10 min)
3. Decision matrix review (10 min)
4. Discussion and questions (15 min)
5. Decision confirmation (5 min)

**Stakeholders**:
- Development lead (decision authority)
- Code review expert (quality perspective)

**Deliverable**: Meeting notes with decision confirmation.

#### 5.4 Document Decision (2 hours)

**Task**: Update DECISION-LOG.md with formal decision.

**Decision Record Template**:

```markdown
## Decision 6: Option 2 vs Option 3 Selection

**Date**: YYYY-MM-DD
**Decision Maker**: [Name/Role]
**Status**: DECIDED

### The Options

#### Option 2: Mode Analysis
[Summary from Phase 3]

#### Option 3: LinkCode Pattern
[Summary from Phase 4]

### Decision Matrix Results
[Insert completed matrix]

### Chosen Approach
**Selected**: Option [2/3]

### Rationale
1. [Key reason 1]
2. [Key reason 2]
3. [Key reason 3]

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

**Deliverable**: Updated DECISION-LOG.md.

#### 5.5 Create Implementation Plan (2 hours)

**Task**: Detailed plan for chosen approach.

**Plan Components**:
1. Task breakdown with dependencies
2. Bead definitions for tracking
3. Success criteria per task
4. Quality gates
5. Risk mitigations
6. Timeline with milestones

**Special Cases**:
- **If both prototypes fail**: Document decision to keep Option 1 (cartesian product) permanently. Create Phase 3c (QA) for cleanup.
- **If one prototype fails**: Document why and choose working option.

**Deliverable**: `implementation-plan-chosen-approach.md`

#### 5.6 Documentation Updates (1 hour)

**Task**: Update project documentation with architectural decision and context.

**Files to Update**:
- `.pm/DECISION-LOG.md`: Add Decision 6 (Option 2 vs 3 selection)
- `CLAUDE.md`: Update with architectural context and chosen approach
- ChromaDB: Add document linking decision to prior research

**Deliverable**: Updated documentation with decision context.

### Success Criteria

- [ ] Research synthesis document complete
- [ ] Decision matrix completed with all scores
- [ ] Tie-breaking applied if needed
- [ ] Stakeholder review conducted and documented
- [ ] Decision recorded in DECISION-LOG.md with clear rationale
- [ ] Implementation plan created for chosen approach
- [ ] Documentation updates completed
- [ ] Fallback scenario addressed (if both fail, keep Option 1)
- [ ] Beads created for next phase (implementation of chosen approach)

---

## Timeline Summary

```
Day 1: Phase 1.5 - Baseline Performance (PREREQUISITE)
       ├─ 1.5.1 Implement Phase 1 Option 1 (1h)
       ├─ 1.5.2 Measure baseline performance (3h)
       └─ 1.5.3 Decide: Optimize OR Accept (1-2h)

       DECISION GATE: If Phase 1 acceptable, SKIP Phases 2-5

       Phase 2 - Performance Criteria (IF proceeding)
       ├─ 2.1 Size thresholds (1.5h)  [grounded in baseline]
       ├─ 2.2 Latency criteria (1h)
       ├─ 2.3 Benchmark harness (2h)
       └─ 2.4 Decision trigger (0.5h)

Day 2-4: Phase 3 + Phase 4 (PARALLEL) - IF Phase 1.5 decides to optimize

Phase 3 (Mode Analysis - 3-4 days):     Phase 4 (LinkCode - 3-4 days):
├─ 3.1 Literature + paradigm (8-10h)    ├─ 4.1 LinkCode deep dive (6-8h)
├─ 3.2 Design (6-8h)                    ├─ 4.2 SQL comparison (4-6h)
├─ 3.3 Prototype (6-8h)                 ├─ 4.3 Core.Apply design (6-8h)
├─ 3.4 Integration (4-6h)               ├─ 4.4 Prototype (6-8h)
├─ 3.5 Deferred evaluation (4-6h) ⚠️    └─ 4.5 Comparison (3-4h)
└─ 3.6 Estimate (2h)

Day 5: Phase 5 - Informed Decision
       ├─ 5.1 Synthesis (2-3h)
       ├─ 5.2 Decision matrix + tie-breaking (3h) ⚠️
       ├─ 5.3 Stakeholder review (1-2h)
       ├─ 5.4 Document decision (2h)
       ├─ 5.5 Implementation plan (2h)
       └─ 5.6 Documentation updates (1h)
```

**Critical Path**: Phase 1.5 → [Decision] → Phase 2 → max(Phase 3, Phase 4) → Phase 5

**Total Duration**:
- **Single Developer**: 7-8 days (1.5 + 2 + max(3-4, 3-4) + 1)
- **Two Developers** (Phases 3-4 parallel): 6-7 days
- **If Phase 1.5 shows optimization unnecessary**: 1 day (SKIP Phases 2-5)

---

## Bead Definitions

### Epic Bead
```bash
bd create "Phase 2-5: Evidence-Driven Option Selection" -t epic -p 1
```

### Phase 2 Beads
```bash
bd create "Phase 2: Define Performance Criteria" -t task
bd dep add <phase2-id> <epic-id>
```

### Phase 3 Beads (can run parallel with Phase 4)
```bash
bd create "Phase 3: Research Mode Analysis" -t task
bd dep add <phase3-id> <phase2-id>
```

### Phase 4 Beads (can run parallel with Phase 3)
```bash
bd create "Phase 4: Research LinkCode Pattern" -t task
bd dep add <phase4-id> <phase2-id>
```

### Phase 5 Beads
```bash
bd create "Phase 5: Informed Decision" -t task
bd dep add <phase5-id> <phase3-id>
bd dep add <phase5-id> <phase4-id>
```

---

## Quality Gates (Enhanced)

### Phase 1.5 Gate
- [ ] Phase 1 cardinality check implemented
- [ ] Benchmark tests execute successfully with measurements recorded
- [ ] Decision documented: "Optimize" OR "Accept Phase 1"
- [ ] If "Accept Phase 1": Clear justification recorded (e.g., "Small < 50ms, medium < 200ms")
- [ ] If decision is to stop: Project exits cleanly with Phase 1 as solution

### Phase 2 Gate
- [ ] performance-criteria.md exists with quantitative thresholds **grounded in Phase 1.5 baseline**
- [ ] Benchmark test harness compiles
- [ ] At least 3 representative test cases execute successfully
- [ ] Thresholds justified based on Phase 1 measurements

### Phase 3 Gate (Mode Analysis)
- [ ] mode-analysis-design.md exists with ALL sections
- [ ] Prototype **passes** basic test case with correct output `[(1,2),(2,3),(1,3)]`
- [ ] Deferred evaluation mechanism designed (Subtask 3.5)
- [ ] Design addresses compile-time/runtime boundary issue
- [ ] Integration with Expander clarified
- [ ] Risk: SML/Mercury paradigm mismatch validated or addressed

### Phase 4 Gate (LinkCode)
- [ ] linkcode-pattern-design.md exists with ALL sections
- [ ] Core.Apply generation flow documented with compilation steps
- [ ] Prototype **passes** transitive closure test case: `[(1,2),(2,3),(1,3)]`
- [ ] Fair comparison to Mode Analysis documented
- [ ] Integration with Extents hook point verified

### Phase 5 Gate (Decision)
- [ ] Research synthesis document complete with evidence
- [ ] Decision matrix completed with all scores and rationale
- [ ] Tie-breaking rules applied if needed
- [ ] Stakeholder review conducted and documented
- [ ] Decision recorded in DECISION-LOG.md with clear rationale
- [ ] Implementation plan created for chosen approach
- [ ] Fallback scenario documented (if both fail, keep Option 1)
- [ ] Documentation updated (DECISION-LOG.md, CLAUDE.md, ChromaDB)

---

## Handoff Specifications

### Phase 2 -> Phase 3 Handoff
**From**: Strategic planner
**To**: Research agent (Mode Analysis)
**Artifacts**:
- performance-criteria.md
- Benchmark test harness
**Context**: Use benchmarks to validate prototype performance

### Phase 2 -> Phase 4 Handoff
**From**: Strategic planner
**To**: Research agent (LinkCode)
**Artifacts**:
- performance-criteria.md
- Benchmark test harness
**Context**: Use benchmarks to validate prototype performance

### Phase 3 -> Phase 5 Handoff
**From**: Research agent (Mode Analysis)
**To**: Decision maker
**Artifacts**:
- mode-analysis-design.md
- Prototype code
- Implementation estimate
**Quality Criteria**:
- [ ] Design addresses compile-time/runtime boundary
- [ ] Prototype compiles and runs basic test
- [ ] Estimate includes uncertainty range

### Phase 4 -> Phase 5 Handoff
**From**: Research agent (LinkCode)
**To**: Decision maker
**Artifacts**:
- linkcode-pattern-design.md
- Prototype code
- Fair comparison document
**Quality Criteria**:
- [ ] Design integrates with existing LinkCode
- [ ] Prototype handles simple transitive closure
- [ ] Comparison is objective and quantified

---

## Risk Summary (Comprehensive)

| Phase | Key Risk | Probability | Impact | Mitigation |
|-------|----------|-------------|--------|-----------|
| 1.5 | Baseline unmeasured | Low | High | Add Phase 1.5 baseline measurement BEFORE committing to Phases 2-5 |
| 2 | Criteria not grounded in baseline | Low | Medium | Phase 2 thresholds must reference Phase 1.5 measurements |
| 3 | SML/Mercury paradigm mismatch | Medium-High | High | Subtask 3.1: Validate that mode inference works for first-class functions; hybrid approach if partial |
| 3 | Deferred evaluation incomplete | Medium | High | Subtask 3.5: Must show how modes + deferral solve boundary problem |
| 3 | Scope creep | Medium | Medium | Define minimal viable scope: transitive closure pattern only |
| 4 | Core.Apply generation incomplete | Medium | High | Subtask 4.3: Must show complete Core IR → Code compilation flow |
| 4 | Integration complexity | Medium | Medium | Study existing pattern detection in Extents (line 707-710) |
| 5 | Both prototypes fail | Low-Medium | High | Fallback: Keep Option 1 (cartesian product) permanently, document lessons |
| 5 | Analysis paralysis on close scores | Medium | Medium | Tie-breaking rules: prefer simpler solution, development lead authority |
| Overall | 4-5 days wasted on unnecessary optimization | **MEDIUM** | **HIGH** | Phase 1.5 baseline measurement gates Phases 2-5 execution |

---

## Related Documentation

### ChromaDB
- `analysis::morel::transitive-closure-cardinality-mismatch`
- `strategy::morel::phase3b-architectural-fix`
- `codebase::morel::predicate-inversion`

### .pm/ Files
- CONTINUATION-PHASE-3B.md (context)
- DECISION-LOG.md (decisions)
- PHASE-3B-CARDINALITY-FIX.md (problem statement)

### Codebase
- `/src/main/java/net/hydromatic/morel/compile/PredicateInverter.java`
- `/src/main/java/net/hydromatic/morel/compile/Compiler.java`
- `/src/main/java/net/hydromatic/morel/eval/Codes.java`

---

---

## Revision Summary

This plan has been revised to address audit findings from:
- **Plan-Auditor (ad654fe)**: 1 critical blocker (Phase 3 incomplete) + 4 clarifications needed
- **Substantive-Critic (a513b53)**: 3 logical flaws (unvalidated assumptions) + 6 issues

**Key Revisions Applied**:
1. ✅ **Added Phase 1.5**: Baseline performance measurement BEFORE committing to Phases 2-5
2. ✅ **Phase 3 Complete**: Added Subtask 3.5 (Deferred Evaluation Mechanism design)
3. ✅ **Phase 4 Clarified**: Changed from "TransitiveClosureCode" to "Core.Apply generation"
4. ✅ **Phase 5 Enhanced**: Added tie-breaking rules and fallback scenarios
5. ✅ **Timeline Extended**: Revised from 4-5 days to 7-8 days realistic with contingency
6. ✅ **Quality Gates Enhanced**: Check deliverable quality, not just existence
7. ✅ **Risk Assessment Expanded**: Added "Both prototypes fail" and optimization waste risks

**Plan Status**: ✅ APPROVED WITH CLARIFICATIONS (Audit Complete)

---

**Status**: REVISED PLAN - Ready for Execution
**Next Action**: Execute Phase 1.5 (Baseline Performance Measurement)
**Decision Gate**: After Phase 1.5, decide whether to proceed to Phases 2-5
**Quality Check**: All audit recommendations incorporated, validated against codebase

---

**Last Updated**: 2026-01-24 (Revised with audit clarifications)
**Author**: Strategic Planner Agent (Revised per plan-auditor + substantive-critic recommendations)
**Revision**: Comprehensive - addresses 1 blocker, 4 clarifications, 3 logical flaws, 6 issues
