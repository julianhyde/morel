# Morel Predicate Inversion: Knowledge Index

**Central reference for all project knowledge sources**

---

## Quick Navigation

**New to the project?** Start here:
1. Read this file (you are here)
2. Read `.pm/CONTINUATION.md` (current state)
3. Read `.pm/PHASES.md` (overview)
4. Read relevant synthesis document for your phase

**Need specific information?** Search by section below.

---

## Knowledge Sources Summary

| Source | Type | Coverage | Location | When to Use |
|--------|------|----------|----------|------------|
| CONTEXT_PROTOCOL.md | Guide | Session resumption | `.pm/` | Before every session |
| CONTINUATION.md | State | Current progress | `.pm/` | Start of day |
| PHASES.md | Plan | Phase breakdown | `.pm/` | Understanding phases |
| METHODOLOGY.md | Process | Engineering discipline | `.pm/` | Implementing features |
| AGENT_INSTRUCTIONS.md | Guide | Agent spawning | `.pm/` | Before using agents |
| Synthesis Documents | Knowledge | Algorithm + design | Memory Bank | During implementation |
| Research Papers | Theory | Formal definitions | File system | When diving deep |
| CLAUDE.md | Context | Design rationale | Repo root | Project background |
| Existing Code | Reference | Implementation patterns | Source code | Finding examples |

---

## Memory Bank Project: morel_active

**Location**: `/Users/hal.hildebrand/git/.claude/memory_bank/morel_active/`

### Synthesis Documents (Create/Read via memory bank)

#### 1. predicate-inversion-knowledge-integration.md
**Status**: Template ready (create when project starts)
**Purpose**: Overview of all research papers and their relationships
**Content**:
- Quick reference table for all 6 papers
- Concept relationships and cross-references
- Implementation status by phase
- Paper locations and availability

**When to Use**: First read of project, understanding research landscape

**Example Sections**:
```
## Overview of 6 Key Papers

| Paper | Author | Year | Focus | Phase |
|-------|--------|------|-------|-------|
| URA | Abramov & Glück | 2002 | Core algorithm | P3 |
| Datafun | Arntzenius & K | 2016 | Type safety | P3-P4 |
...

## Concept Relationships
- Perfect Process Tree: Abramov-Glück concept for capturing recursion
- Transitive Closure: Specific recursion pattern
- Mode Inference: Hanus concept for variable dependency
...

## Implementation Status
Phase 1: ✓ Complete
Phase 2: ✓ Complete
Phase 3: Planning
  - 3.1 PPT: Design phase
  - 3.2 Extended Inversion: Design phase
  ...
```

---

#### 2. algorithm-synthesis-ura-to-morel.md
**Status**: Template ready (create when Phase 3 starts)
**Purpose**: Detailed mapping of Abramov-Glück URA to Morel implementation
**Content**:
- URA algorithm steps mapped to Java implementation
- Perfect process tree construction explained
- Base case extraction algorithm
- Recursive case integration
- Code examples and pseudo-code

**When to Use**: Implementing Phase 3 (PPT + Full Relational.iterate)

**Structure**:
```
## Step-by-Step Mapping to Morel

### Step 1: Build Perfect Process Tree (Phase 3.1)
- **Paper Reference**: Abramov-Glück Section 2.3
- **Algorithm**: ProcessTree construction
- **Implementation**: ProcessTreeBuilder.java
- **Code Example**: [pseudo-code from paper mapped to Java]
- **Edge Cases**: [handled patterns]
- **Tests**: [expected test cases]

### Step 2: Extended Base + Recursive Case (Phase 3.2)
...

### Step 3: Tabulation and I-O Pairs (Phase 3.3)
...

### Step 4: Step Function Generation (Phase 3.4)
...

### Step 5: Integration with Relational.iterate (Phase 3.5)
...

## Correctness Properties
- [Property 1]: All generated outputs satisfy predicate
- [Property 2]: All valid solutions generated
- [Property 3]: Termination guaranteed for well-defined predicates
```

---

#### 3. mode-analysis-synthesis.md
**Status**: Template ready (create when Phase 4 starts)
**Purpose**: Detailed explanation of mode analysis for Phase 4
**Content**:
- Mode analysis algorithm explained
- Smart predicate ordering algorithm
- Integration points with Extents.java
- Circular dependency detection
- Code examples and test patterns

**When to Use**: Implementing Phase 4 (Mode Analysis)

**Structure**:
```
## Mode Analysis Deep Dive

### Concepts
- Mode: IN/OUT/CHOICE classification for variables
- Signature: Mode of predicates
- Dependency: Variables that depend on other variables

### Algorithm: Smart Predicate Ordering
1. Analyze modes of all predicates
2. Build dependency graph
3. Detect circular dependencies
4. Order predicates to minimize computation
5. Compare with greedy (cartesian product)

### Examples
- elem(x, collection): OUT x, IN collection
- x > y: depends on what's given
- function call: depends on function

### Integration
- Hook in Extents.java
- Call ModeAnalysisEngine before extent generation
- Use result to reorder predicates
```

---

#### 4. complete-knowledge-base-summary.md
**Status**: Optional (for reference)
**Purpose**: Consolidated reference of all papers
**Content**:
- Complete summary of all 6 papers
- Cross-references between papers
- Status by component
- Validation checklist

**When to Use**: When you need complete knowledge (research phase)

---

#### 5. phase-3-4-strategic-plan.md
**Status**: Already exists in repo root as STRATEGIC_PLAN_PHASES_3-4.md
**Purpose**: High-level plan for Phases 3-4
**Content**:
- Timeline with complexity estimates
- Resource allocation
- Quality gates and success criteria
- Risk management

**When to Use**: Planning and progress tracking

---

#### 6. phase-3-4-bead-definitions.md
**Status**: Template ready (use for orchestrator)
**Purpose**: Detailed bead specifications for Phase 3-4
**Content**:
- Epic breakdown with specific acceptance criteria
- Knowledge base references per epic
- Dependency graph (which epics depend on which)
- Time estimates and complexity

**When to Use**: Bead creation (before implementation)

**Example Entry**:
```
## Epic 3.1: Perfect Process Tree Construction

**Type**: Epic
**Duration**: 2-3 days
**Complexity**: Medium
**Dependencies**: None

### Acceptance Criteria
1. ProcessTreeNode hierarchy complete
2. ProcessTreeBuilder constructs correct PPT
3. 10+ unit tests passing
4. Visitor pattern implemented
5. Handles duplicates correctly

### Knowledge Base References
- algorithm-synthesis-ura-to-morel.md: "Step 1"
- Paper: Abramov & Glück Section 2.3
- Code Reference: Core.Exp traversal patterns

### Related Beads
- Depends on: Nothing (Phase 2 complete)
- Enables: 3.2, 3.3, 3.4
- Related: 4.1 (can design in parallel after 3.1 approved)
```

---

#### 7. phase-3-4-execution-guide.md
**Status**: Template ready (use during implementation)
**Purpose**: How to execute each epic
**Content**:
- Guidelines for developers
- Patterns to follow
- Code examples to reference
- Testing strategies
- Troubleshooting tips

**When to Use**: During implementation (Phase 3-4)

---

## Research Papers

**Location**: `/Users/hal.hildebrand/Documents/Predicate Inversion Research/`

### Paper 1: Universal Resolving Algorithm (URA)
- **Authors**: Abramov & Glück
- **Year**: 2002
- **Title**: "The Universal Resolving Algorithm"
- **Status**: Available (local copy)
- **Relevance**: Core algorithm for Phase 3
- **Key Sections**:
  - 2.1-2.3: Perfect Process Tree
  - 3.1-3.5: Base+Recursive case handling
  - 4.1-4.3: Tabulation
  - 5.1-5.3: Iteration step generation
  - 6.1-6.3: Correctness proof

**When to Read**:
- Phase 3 detailed study (Section 2.3 before 3.1)
- Correctness validation (Section 6)

**Key Concepts**:
- Perfect Process Tree: Capturing all computation paths
- Answer table: I-O pair collection
- Step function: One iteration of fixed-point computation

---

### Paper 2: Datafun
- **Authors**: Arntzenius & Krishnaswami
- **Year**: 2016
- **Title**: "Datafun: A Language Based on Datalog and Functional Programming"
- **Status**: Available
- **Relevance**: Type system safety, functional patterns
- **Key Sections**:
  - 2: Type system for declarative programming
  - 3: Fixed-point computation (relates to Relational.iterate)
  - 4: Monotonicity analysis

**When to Read**:
- Understanding type safety implications
- Monotonicity guarantees

---

### Paper 3: Flix
- **Authors**: Madsen et al.
- **Year**: 2024
- **Title**: "[Recent Flix paper on logic + functional]"
- **Status**: Available
- **Relevance**: Modern implementation patterns
- **Key Sections**:
  - Implementation patterns
  - Performance optimization
  - Practical considerations

**When to Read**:
- Understanding modern implementations
- Performance optimization strategies

---

### Paper 4: From Logic to Functional Logic Programs
- **Authors**: Hanus
- **Year**: 2022
- **Title**: "[Mode analysis and logic programming]"
- **Status**: Available (key for Phase 4)
- **Relevance**: Mode analysis (Phase 4)
- **Key Sections**:
  - 4: Mode analysis algorithm
  - 5: Smart predicate ordering
  - 6: Correctness properties

**When to Read**:
- Phase 4 preparation (2+ weeks before Phase 4 start)
- Detailed study during Phase 4.1

---

### Paper 5: Functional Programming with Datalog
- **Authors**: Pacak & Erdweg
- **Year**: 2022
- **Title**: "[Functional datalog patterns]"
- **Status**: Available
- **Relevance**: Alternative perspectives, functional patterns

**When to Read**:
- Reference for alternative approaches
- If stuck on functional composition

---

### Paper 6: Relational Expressions
- **Authors**: Pratten & Mathieson
- **Year**: 2023
- **Title**: "[Constraint handling in relational expressions]"
- **Status**: Available
- **Relevance**: Advanced optimization (Phase 4b, optional)

**When to Read**:
- Phase 4b (optional, if doing advanced optimization)
- Constraint handling patterns

---

## Codebase References

### Key Implementation Files

#### PredicateInverter.java
**Location**: `src/main/java/net/hydromatic/morel/compile/PredicateInverter.java`
**Size**: 966 lines
**Purpose**: Main predicate inversion implementation

**Key Methods**:
- `invert()`: Main entry point
- `tryInvertTransitiveClosure()`: Phase 2 implementation
- `invertAnds()`: Predicate conjunction (lines 599-745)
- `invertOr()`: Disjunction (lines 747-774)

**When to Read**:
- Understanding current implementation
- Finding patterns to extend
- Phase 3 implementation location

---

#### PredicateInverterTest.java
**Location**: `src/test/java/net/hydromatic/morel/compile/PredicateInverterTest.java`
**Size**: 300+ lines
**Purpose**: Unit tests for predicate inversion

**Tests**:
- Test 1-7: Phase 1-2 patterns
- Tests to add: Phase 3-4 patterns

**When to Reference**:
- Understanding test patterns
- Adding new test cases
- Validating new functionality

---

#### Extents.java
**Location**: `src/main/java/net/hydromatic/morel/compile/Extents.java`
**Line**: 538-541 (TODO for user-defined functions)
**Purpose**: Integration point for predicate inversion

**Relevant Sections**:
- Line 538: Where to hook PredicateInverter
- Surrounding lines: Pattern matching structure

**When to Modify**:
- Phase 3.5 integration

---

#### such-that.smli
**Location**: `src/test/resources/script/such-that.smli`
**Line**: 737-742 (transitive closure test, currently commented)
**Purpose**: Integration test for recursive predicates

**Test Pattern**:
```sml
(* Transitive closure test *)
fun path (x, y) = edge (x, y) orelse
  (exists z where edge (x, z) andalso path (z, y))

from p where path p
(* Should return: [(1,2),(2,3),(1,3)] *)
```

**When to Use**:
- Phase 3.5 validation
- Final integration verification
- Phase 3 gate criteria

---

### Supporting Files

#### Core.java
**Purpose**: AST representation
**Use**: Understanding expression structure

#### FromBuilder.java
**Purpose**: Generator building
**Use**: Understanding how generators are constructed

#### Relational.java
**Purpose**: Relational operations
**Use**: Understanding Relational.iterate signature

---

## ChromaDB Knowledge Base

**Enabled**: Yes
**Documents**: 6 papers indexed

**Document IDs**:
```
research::predicate-inversion::abramov-gluck
research::predicate-inversion::datafun
research::predicate-inversion::flix
research::predicate-inversion::hanus
research::predicate-inversion::pacak-erdweg
research::predicate-inversion::pratten-mathieson
```

**When to Use**:
- Searching for specific concepts across papers
- Finding where a concept is discussed
- Validating research findings

**Search Examples**:
```bash
# Search for mode analysis concepts
mcp__chromadb__search_similar(
  query="mode analysis and variable dependency",
  num_results=5
)

# Search for perfect process tree explanations
mcp__chromadb__search_similar(
  query="perfect process tree construction",
  num_results=5
)
```

---

## Documentation Files in Repo

### CLAUDE.md (Project Root)
**Purpose**: Design context and implementation notes
**Content**:
- Predicate inversion goal and current status
- What needs implementation (Phase 1-3)
- Testing strategy
- Key design decisions
- References to papers

**When to Read**: Project background, understanding rationale

---

### STRATEGIC_PLAN_PHASES_3-4.md (Project Root)
**Purpose**: High-level strategic plan
**Status**: Already exists
**When to Use**: Planning, understanding timeline

---

### PREDICATE_INVERSION_DESIGN.md (Project Root)
**Purpose**: Design document for predicate inversion
**When to Use**: Understanding overall design

---

## Synthesis Documents Checklist

**Before implementing each phase**, ensure synthesis documents exist:

### Phase 3 Preparation
- [ ] predicate-inversion-knowledge-integration.md created
- [ ] algorithm-synthesis-ura-to-morel.md created
- [ ] Abramov & Glück 2002 accessible
- [ ] phase-3-4-bead-definitions.md available

### Phase 4 Preparation (Before Phase 3 gate)
- [ ] mode-analysis-synthesis.md created
- [ ] Hanus 2022 accessible
- [ ] Phase 4 bead definitions in phase-3-4-bead-definitions.md
- [ ] phase-3-4-execution-guide.md updated

---

## How to Find Information

### "How do I implement X?"

**Pattern**: Bead Title + Synthesis Document → Code

1. Find bead for task (e.g., "Phase3-PPT-Design")
2. Read relevant synthesis document section
3. Follow code examples in synthesis
4. Reference papers for theory
5. Look at existing code for patterns

**Example**: "How do I implement ProcessTreeNode?"
1. Read Phase3-PPT-Design bead
2. Read algorithm-synthesis-ura-to-morel.md: "Step 1"
3. Review pseudo-code examples
4. Check Core.Exp for similar node structure
5. Implement following pattern

### "What's the algorithm?"

**Pattern**: Synthesis Document → Paper → Code

1. Read synthesis document for your phase
2. Read paper sections linked in synthesis
3. Review code examples in synthesis
4. Check existing code for integration pattern

### "What's the current state?"

**Pattern**: `.pm/execution_state.json` → CONTINUATION.md → Beads

1. Read execution_state.json for metrics and phase
2. Read CONTINUATION.md for recent progress
3. Check `bd ready` or `bd list` for available work
4. Read bead description for specific task

### "Am I doing this right?"

**Pattern**: Bead Acceptance Criteria → Test → Code Review

1. Check bead acceptance criteria
2. Ensure tests cover all criteria
3. Run tests to validate
4. Request code review
5. Address review comments

### "What am I missing?"

**Pattern**: Synthesis Document → Edge Cases → Tests

1. Review synthesis document "edge cases" section
2. Check test patterns for missed cases
3. Add new test for edge case
4. Implement fix
5. Document lesson learned

---

## Knowledge Freshness

**Last Updated**: 2026-01-23
**Paper References**: Up to date
**Synthesis Templates**: Ready for use
**Codebase References**: Current as of commit e032e620

**Staleness Indicators**:
- More than 2 weeks since CONTINUATION.md updated → refresh knowledge
- More than 4 weeks since synthesis documents reviewed → re-read
- Phase boundaries → re-read PHASES.md

---

## Using This Index

**Bookmark this file** - You'll reference it frequently

**Quick Links Menu** (add to browser/tool bookmarks):
1. This file (KNOWLEDGE_INDEX.md) - Master index
2. CONTINUATION.md - Current state
3. PHASES.md - Phase overview
4. algorithm-synthesis-ura-to-morel.md - Phase 3 how-to
5. mode-analysis-synthesis.md - Phase 4 how-to

**Daily Workflow**:
- Start: Read CONTINUATION.md (2 min)
- During: Reference synthesis documents (as needed)
- Wrap-up: Update CONTINUATION.md (5 min)

---

## Troubleshooting Knowledge Gaps

**"I don't understand the algorithm"**
1. Read synthesis document for your phase
2. Read relevant paper section
3. Review code examples
4. Try implementing a simple test case
5. Ask architect (java-architect-planner) if still stuck

**"I'm not sure how to design this"**
1. Review bead acceptance criteria
2. Read synthesis document design section
3. Look at similar patterns in codebase
4. Prototype on paper/whiteboard
5. Request design review from architect

**"Tests failing but I don't know why"**
1. Read test description
2. Review code section being tested
3. Add logging/debugging
4. Check synthesis document for algorithm details
5. Request debugging help (java-debugger) if needed

**"Performance regression"**
1. Check baseline metrics in .pm/metrics/
2. Profile with JProfiler
3. Review synthesis document for complexity
4. Identify hot spot
5. Optimize or adjust algorithm

---

## Knowledge Contributing

**If you discover new insights**:
1. Document in `.pm/learnings/L*.md`
2. Add to synthesis document if generalizable
3. Update CONTINUATION.md
4. Store in ChromaDB if permanent knowledge

**If synthesis documents become outdated**:
1. Update file
2. Note update in CONTINUATION.md
3. Commit with reference to relevant bead

---

**For questions about the knowledge index itself**: Refer to CONTEXT_PROTOCOL.md "PHASE 2: Load Knowledge Context"
