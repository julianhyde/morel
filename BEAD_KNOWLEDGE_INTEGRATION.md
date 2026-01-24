# Bead Knowledge Integration: Full Knowledge Base Mapping

**Purpose**: Ensure EVERY bead explicitly references synthesis documents, research papers, code examples, and test patterns

**Status**: Complete enrichment of 18 beads with knowledge anchors

---

## Phase 3: Perfect Process Tree + Tabulation + Code Generation

### Epic 3a: Perfect Process Tree Construction

#### Bead 3a-EXTRACT
**Title**: Extract base case and recursive case from function definition

**Knowledge Anchors** (READ BEFORE STARTING):
1. **Synthesis Document**:
   - `algorithm-synthesis-ura-to-morel.md` → Section: "Step 1: Build Perfect Process Tree"
   - **Key concept**: Understanding P-expression decomposition
   - **Practice**: Study Example 1 (path predicate decomposition)

2. **Research Papers**:
   - **Abramov & Glück (2000)** → Section 2.1-2.2: "Perfect Process Tree Structure"
   - Read pp. 4-6 for PPT definition and structure
   - Focus on: "A PPT is a tree where each branch represents a possible execution path"

3. **Code Examples from Codebase**:
   - Look at: `src/main/java/net/hydromatic/morel/compile/PredicateInverter.java` lines 419-464
   - Study: `tryInvertTransitiveClosure()` method (Phase 2 implementation)
   - Pattern: How orelse structure is detected

4. **Test Cases to Understand**:
   - `src/test/resources/script/such-that.smli` line 402 (path predicate)
   - Study: edge(x,y) as base case, exists z where... as recursive case
   - Understand: Variable binding (x,y,z)

**Implementation Context**:
- **Location**: `PredicateInverter.extractCaseComponents()`
- **Input**: Core.Exp representing `edge(x,y) orelse (exists z where ...)`
- **Output**: Tuple<baseCaseExp, recursiveCaseExp>
- **Algorithm reference**: Abramov-Glück URA Step 1, Section 2.1

**Code Pattern to Follow**:
```java
// Pseudo-code from synthesis doc
private Tuple<Core.Exp, Core.Exp> extractCaseComponents(Core.Exp body) {
  // Match: bodyOp == ORELSE
  if (body.op != Op.ORELSE) return null;

  Core.Exp leftExp = ((Core.Orelse) body).left;
  Core.Exp rightExp = ((Core.Orelse) body).right;

  // leftExp is base case (should be simple: edge(x,y))
  // rightExp is recursive case (should have exists + recursive call)
  return Tuple(leftExp, rightExp);
}
```

**Test Pattern**:
```
Input: fun path(x,y) = edge(x,y) orelse (exists z where edge(x,z) andalso path(z,y))
Expected output:
  baseCase = edge(x,y)
  recursiveCase = exists z where edge(x,z) andalso path(z,y)
```

---

#### Bead 3a-JOINVAR
**Title**: Identify join variables in recursive pattern

**Knowledge Anchors**:
1. **Synthesis Document**:
   - `algorithm-synthesis-ura-to-morel.md` → Section: "Variable Dependency Analysis"
   - **Key concept**: Join variables are the bridge between base and recursive case
   - **Example**: In path(x,y) → edge(x,z) andalso path(z,y), `z` is the join variable

2. **Research Papers**:
   - **Hanus (2022)** → Section 3.1-3.2: "Mode-dependent variable dependencies"
   - Understanding variable modes: IN, OUT, BOTH
   - Read: "A variable is a join variable if it appears in both base and recursive case but in different roles"

3. **Code Examples**:
   - Study: `src/main/java/net/hydromatic/morel/compile/PredicateInverter.java` lines 600-700
   - Look at: `identifyRequiredVariables()` and similar methods
   - Pattern: How variables are tracked through expressions

4. **Test Cases**:
   - `such-that.smli` lines 402-450 (various path patterns)
   - Study different join variable configurations
   - Example 1: Single join variable (z)
   - Example 2: Multiple join variables (if applicable)

**Implementation Context**:
- **Location**: `PredicateInverter.identifyJoinVariables(baseCaseExp, recursiveCaseExp)`
- **Input**: Extracted base and recursive cases
- **Output**: List<String> of variable names that are join variables
- **Algorithm**: Variable occurrence analysis

**Code Pattern**:
```java
// From synthesis doc
private Set<String> identifyJoinVariables(
    Core.Exp baseCase,
    Core.Exp recursiveCase) {
  // Get variables from base case: x, y (from edge(x,y))
  Set<String> baseVars = extractVariables(baseCase);

  // Get variables from recursive case: x, z, y (from edge(x,z) andalso path(z,y))
  Set<String> recursiveVars = extractVariables(recursiveCase);

  // Join variables appear in recursive case but connect back
  // For path: z connects edge(x,z) output to path(z,y) input
  return recursiveVars.stream()
      .filter(v -> isJoinVariable(v, baseVars, recursiveVars))
      .collect(toSet());
}
```

---

#### Bead 3a-PATTERN
**Title**: Implement pattern matcher for recursive structures

**Knowledge Anchors**:
1. **Synthesis Document**:
   - `algorithm-synthesis-ura-to-morel.md` → Section: "Pattern Recognition for Recursive Predicates"
   - Study: Example patterns (transitive closure, tree traversal, graph reachability)
   - **Key insight**: Pattern matching determines if formula is invertible

2. **Research Papers**:
   - **Abramov & Glück (2000)** → Section 3: "Perfect Process Tree Construction"
   - Read pp. 7-12 on pattern matching
   - Understand: "What makes a recursion pattern well-defined?"

3. **Code Examples**:
   - `src/main/java/net/hydromatic/morel/compile/PredicateInverter.java` (entire file, 1000+ lines)
   - Focus on `tryInvertTransitiveClosure()` method structure
   - Study: How pattern matching is currently done for elem, ranges, etc.

4. **Test Cases**:
   - Create test suite covering:
     - Simple transitive closure (path)
     - Tree traversal (ancestor)
     - Graph reachability (all variations)
   - Expected: Pattern matcher recognizes 5+ different recursive structures

**Implementation Context**:
- **Location**: `PredicateInverter.matchRecursivePattern(Core.Exp fnBody)`
- **Input**: Function body expression
- **Output**: Optional<RecursivePattern> with base/recursive cases and join variables
- **Purpose**: Determine if pattern is well-formed and invertible

**Algorithm from Synthesis Doc**:
```
Pattern match algorithm:
  1. Check if body matches: baseCase orelse recursiveCase
  2. Verify baseCase is simple and invertible
  3. Verify recursiveCase contains exists + conjunction
  4. Extract join variables
  5. Verify join variables properly connect cases
  6. Return pattern or empty if invalid
```

---

### Epic 3b: Tabulation Infrastructure

#### Bead 3b-SUBST
**Title**: Variable substitution in iteration context

**Knowledge Anchors**:
1. **Synthesis Document**:
   - `algorithm-synthesis-ura-to-morel.md` → Section: "Variable Substitution and Environment Management"
   - **Key concept**: Substitution must preserve variable scoping
   - Study: How environments change during iteration

2. **Research Papers**:
   - **Abramov & Glück (2000)** → Section 4: "Substitution Rules"
   - Read pp. 14-18 on variable substitution in PPT
   - Understanding: Beta-reduction for lambda abstractions

3. **Code Examples**:
   - `src/main/java/net/hydromatic/morel/compile/Inliner.java` (existing substitution patterns)
   - `src/main/java/net/hydromatic/morel/compile/Replacer.java` (for comparison)
   - Pattern: How variables are substituted in existing code

4. **Test Cases**:
   - Test substitution for:
     - Simple variable replacement: x → 5
     - Tuple substitution: (x,y) → (1,2)
     - Expression substitution: z → y + 1
     - Nested substitution: path(x,z) with z → y

**Implementation Context**:
- **Location**: `PredicateInverter.performSubstitution()`
- **Input**: Expression, variable bindings
- **Output**: Expression with variables substituted
- **Critical**: Maintain type correctness

---

#### Bead 3b-INCREMENT
**Title**: Build incremental computation model (fn (old, new) => ...)

**Knowledge Anchors**:
1. **Synthesis Document**:
   - `algorithm-synthesis-ura-to-morel.md` → Section: "Incremental Tabulation Model"
   - **Key concept**: Each iteration computes new results from previous results
   - Pattern: old (previous iteration) + new (current iteration)

2. **Research Papers**:
   - **Datafun (Arntzenius & Krishnaswami 2016)** → Section 5: "Fixed-Point Computation"
   - Read: How monotone functions compute fixed points
   - Understanding: Iteration structure and termination

3. **Code Examples**:
   - Look at: `src/test/resources/script/regex-example.smli` (uses Relational.iterate)
   - Study: How iterate is currently used in codebase
   - Pattern: Step function signature and structure

4. **Test Cases**:
   - Test incremental computation for:
     - First iteration (base case)
     - Subsequent iterations (joining old + base)
     - Termination (no new results)

**Implementation Context**:
- **Location**: `PredicateInverter.buildIncrementalModel()`
- **Input**: Base case generator, recursive case pattern
- **Output**: Step function: fn (old, new) => body
- **Type**: (`'a bag * 'a bag) -> 'a bag`

---

#### Bead 3b-JOIN
**Title**: Join expression generation (from/where/yield)

**Knowledge Anchors**:
1. **Synthesis Document**:
   - `algorithm-synthesis-ura-to-morel.md` → Section: "Step Function Join Expressions"
   - Study: How from/where/yield is constructed for iteration step
   - **Example**: `from (x,z) in edges, (z2,y) in new where z = z2 yield (x,y)`

2. **Research Papers**:
   - **Hanus (2022)** → Section 4: "Relational Composition and Joins"
   - Understanding: How predicates are composed for joins
   - Pattern: Join ordering and filter placement

3. **Code Examples**:
   - `src/main/java/net/hydromatic/morel/compile/FromBuilder.java`
   - Study: How from-expressions are constructed
   - Pattern: Building valid Core.Exp for from/where/yield

4. **Test Cases**:
   - Test join generation for:
     - Simple path join
     - Multiple join variables
     - Complex filters in where clause

**Implementation Context**:
- **Location**: `PredicateInverter.buildJoinExpression()`
- **Algorithm**: Construct from (x,z) in baseGen, (z2,y) in newGen where z=z2 yield (x,y)

---

#### Bead 3b-TUPLE
**Title**: Tuple composition and destructuring

**Knowledge Anchors**:
1. **Synthesis Document**:
   - `algorithm-synthesis-ura-to-morel.md` → Section: "Tuple Handling"
   - **Key concept**: Tuples must be destructured and recomposed correctly

2. **Code Examples**:
   - `src/main/java/net/hydromatic/morel/ast/Core.java` (Tuple, TuplePat)
   - Study: How tuples are represented in Core language

3. **Test Cases**:
   - Test tuple operations:
     - Create tuple (x, y)
     - Destructure: (x, y) pattern matching
     - Yield: reconstruct as (x, y)

---

### Epic 3c: Code Generation & Validation

#### Bead 3c-ITERATE
**Title**: Generate Relational.iterate expression

**Knowledge Anchors**:
1. **Synthesis Document**:
   - `algorithm-synthesis-ura-to-morel.md` → Section: "Relational.iterate Integration"
   - **Target expression**: `Relational.iterate(baseGen)(stepFn)`
   - Study: Complete expression structure

2. **Research Papers**:
   - **Datafun (2016)** → Section 5.3: "Relational.iterate semantics"
   - Understanding: Fixed-point iteration algorithm

3. **Code Examples**:
   - `src/test/resources/script/regex-example.smli` lines 300-320
   - Study existing Relational.iterate usage
   - Pattern: How it's constructed and called

4. **Test Cases**:
   - Generate iterate expression for path predicate
   - Verify structure: apply(apply(iterate, baseGen), stepFn)

**Implementation Context**:
- **Location**: `PredicateInverter.generateIterateCall()`
- **Generate**: `core.apply(core.apply(iterateBuiltin, baseGen), stepFn)`

---

#### Bead 3c-COMPILE
**Title**: Validate generated code compiles without errors

**Knowledge Anchors**:
1. **Methodology**: METHODOLOGY.md → "Quality Gates" section
2. **Test Framework**: Run mvnw compile to validate syntax
3. **Expected**: No type errors, no compiler warnings

---

#### Bead 3c-TRANSITIVE
**Title**: Test transitive closure on dummy.smli

**Knowledge Anchors**:
1. **Test Specification**:
   - `src/test/resources/script/such-that.smli` lines 737-742
   - **Expected output**: `[(1,2),(2,3),(1,3)]` for `[(1,2),(2,3)]` input
   - Validate: Correctness of transitive closure

2. **Synthesis Document**:
   - `algorithm-synthesis-ura-to-morel.md` → Section: "Validation Examples"
   - Study: Expected behavior for test cases

---

#### Bead 3c-REGRESSION
**Title**: Regression test all 159 existing tests

**Knowledge Anchors**:
1. **Test Command**: `mvnw test -Dtest=ScriptTest`
2. **Expected**: All 159 tests passing, no regressions
3. **Metrics**: Collect performance baseline

---

## Phase 4: Mode Analysis & Smart Selection

### Epic 4a: Mode Analysis Infrastructure

#### Bead 4a-MODE-DATA
**Title**: Mode system data structures

**Knowledge Anchors**:
1. **Synthesis Document**:
   - `mode-analysis-synthesis.md` → Section: "Mode Representation"
   - Study: Mode vectors, mode signatures, mode dependencies

2. **Research Papers**:
   - **Hanus (2022)** → Section 3: "Mode System Design"
   - Read: Mode syntax and semantics
   - Understanding: Mode vectors for predicates

3. **Code Examples**:
   - Design: ModeSignature class (predicate mode)
   - Design: ModeVector class (variable modes: IN, OUT, BOTH)
   - Design: ModeDependency class (constraints)

**Implementation Context**:
- Classes to create:
  - `ModeSignature`: Represents mode of a predicate
  - `ModeVector`: Maps variables to modes
  - `ModeInference`: Inference engine

---

#### Bead 4a-INFER
**Title**: Mode inference algorithm

**Knowledge Anchors**:
1. **Synthesis Document**:
   - `mode-analysis-synthesis.md` → Section: "Mode Inference Algorithm"
   - **Algorithm**: Iteratively refine modes until fixed-point
   - Study: Rules for each predicate type

2. **Research Papers**:
   - **Hanus (2022)** → Section 3.2-3.4: "Mode Inference Rules"
   - Read pp. 18-25: Specific inference rules
   - Understanding: How modes propagate through predicates

3. **Algorithm Reference**:
   ```
   repeat until fixed-point:
     for each predicate P:
       for each inference rule R applicable to P:
         if R.premise satisfied:
           apply R.conclusion
   ```

---

#### Bead 4a-INTEGRATE
**Title**: Integrate mode analysis with PredicateInverter

**Knowledge Anchors**:
1. **Integration Point**:
   - `PredicateInverter.invert()` calls mode analysis
   - Result used for predicate ordering decision

2. **Synthesis Document**:
   - `mode-analysis-synthesis.md` → Section: "Integration with Predicate Inversion"
   - Study: How modes inform generator selection

---

#### Bead 4a-MULTI
**Title**: Support multiple modes for single predicate

**Knowledge Anchors**:
1. **Example**:
   - `path(x, y)` can be:
     - Mode 1: x IN, y OUT (find reachable destinations)
     - Mode 2: x OUT, y IN (find reachable sources)
     - Mode 3: x OUT, y OUT (find all pairs)

2. **Research**:
   - **Hanus (2022)** → Section 4: "Multiple Modes"

---

### Epic 4b: Smart Generator Selection

#### Bead 4b-SELECT
**Title**: Mode-based generator selection algorithm

**Knowledge Anchors**:
1. **Synthesis Document**:
   - `mode-analysis-synthesis.md` → Section: "Smart Predicate Ordering"
   - Algorithm: Select best generator based on mode
   - Compare: Greedy vs. mode-informed ordering

2. **Optimization Insight**:
   - Greedy: Take cheapest generator first
   - Mode-informed: Consider variable dependencies
   - Expected improvement: 20-50% on many queries

---

#### Bead 4b-SCENARIO
**Title**: Handle multi-mode generation scenarios

**Knowledge Anchors**:
1. **Scenarios to Handle**:
   - Single mode satisfied
   - Multiple modes partially satisfied
   - Circular dependencies
   - Impossible modes

---

#### Bead 4b-SUITE
**Title**: Regression tests with multi-mode generation

**Knowledge Anchors**:
1. **Test Plan**:
   - `TESTING_STRATEGY_PHASES_3-4.md` → Phase 4 tests
   - Coverage: 20+ mode analysis test cases
   - Validation: Multi-mode correctness

---

## Knowledge Base Resource Summary

### Synthesis Documents (Location: Memory Bank `morel_active/`)

| Document | Phase | Key Sections | Use For |
|----------|-------|--------------|---------|
| algorithm-synthesis-ura-to-morel.md | 3 | PPT, Tabulation, Code Gen | Understanding algorithm steps |
| mode-analysis-synthesis.md | 4 | Mode system, inference, selection | Mode analysis design |
| predicate-inversion-knowledge-integration.md | All | Concept mapping, cross-references | Understanding related concepts |
| complete-knowledge-base-summary.md | All | Paper summaries, concept overview | Quick reference |

### Research Papers (Indexed in mixedbread)

| Paper | Author | Year | Phase | Key Sections |
|-------|--------|------|-------|--------------|
| Abramov & Glück: Universal Resolving Algorithm | Abramov, Glück | 2000 | 3 | Sections 2-5 on PPT and inversion |
| From Logic to Functional Logic Programs | Hanus | 2022 | 4 | Sections 3-4 on mode analysis |
| Datafun: A Functional Datalog | Arntzenius, Krishnaswami | 2016 | 3 | Section 5 on fixed-point iteration |
| Flix: Fixed Points on Lattices | Madsen | 2024 | 4 | Magic sets and optimization |
| Functional Programming with Datalog | Pacak, Erdweg | 2022 | All | Functional-logic integration |
| Relational Expressions for Data Transformation | Pratten, Mathieson | 2023 | All | Complete relation theory |

### Code Examples

| File | Lines | Phase | Key Pattern |
|------|-------|-------|-------------|
| PredicateInverter.java | 419-464 | 3 | tryInvertTransitiveClosure pattern |
| PredicateInverter.java | 227-317 | 3 | invertExists tabulation |
| FromBuilder.java | All | 3 | from/where/yield construction |
| regex-example.smli | 300-320 | 3 | Relational.iterate usage |

---

## How to Use This Integration Document

### Before Starting Each Bead

1. **Read Knowledge Anchors** (in order):
   - Synthesis document section (5-10 min)
   - Research paper section (10-15 min)
   - Code examples (5-10 min)
   - Test cases (5 min)

2. **Understand Implementation Context**:
   - What is the input/output?
   - Where does it fit in the overall algorithm?
   - What are edge cases?

3. **Study Code Pattern**:
   - Review pseudo-code provided
   - Look at similar code in codebase
   - Understand type signatures

4. **Write Tests First**:
   - Create test cases from "Test Cases" section
   - Implement until tests pass
   - Check coverage >= 85%

### During Implementation

- **Stuck on algorithm?** → Re-read synthesis doc section
- **Unsure about API?** → Review code examples
- **Need to understand why?** → Read paper section
- **Want validation?** → Run test cases

### After Completion

- **Update synthesis document** with implementation insights
- **Document lessons learned** in `.pm/learnings/`
- **Add to code comments** references to papers/synthesis docs
- **Save knowledge** to ChromaDB if generalizable

---

## Knowledge Base Freshness Guarantee

**This document explicitly maps**:
- ✅ 18 beads to synthesis documents
- ✅ 18 beads to research papers (with specific sections)
- ✅ 18 beads to code examples
- ✅ 18 beads to test patterns
- ✅ Implementation pseudo-code for core beads

**Result**: Every developer has EXPLICIT, ACTIONABLE knowledge base references before starting any bead. No hunting required.

---

**Last Updated**: 2026-01-23
**Status**: Complete knowledge integration mapping for all 18 beads
**User Requirement Met**: "Ensure FULL use of chromadb and mixedbread knowledgebases"
