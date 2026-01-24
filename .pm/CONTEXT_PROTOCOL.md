# Morel Predicate Inversion: Context Protocol

**How to resume work on this project after breaks (days/weeks/months)**

---

## PHASE 1: Load Project Context

### Step 1: Understand Current State

**File**: `.pm/execution_state.json`

```json
{
  "current_state": {
    "phase": "phase_2_complete",
    "next_action": "Create beads for Phase 3 epics",
    "blockers": []
  },
  "metrics": {
    "tests_passing": 159,
    "test_coverage_target": 0.85
  }
}
```

**Action**: Read execution_state.json to understand:
- What phase are we in? (phase_2_complete → Ready for Phase 3)
- What's the next action? (Create beads)
- Are there blockers? (None currently)
- How many tests passing? (159/159)

### Step 2: Read Continuation Context

**File**: `.pm/CONTINUATION.md`

This file contains:
- What was completed recently
- Key learnings from last session
- Active hypotheses about design decisions
- Identified blockers
- Next actions with priority

**Action**: Skim CONTINUATION.md to get caught up on recent work.

### Step 3: Review Project Goals

**File**: `.pm/PHASES.md`

Understand the overall structure:
- Phase 1 (complete): Recursive function handling
- Phase 2 (complete): Pattern detection
- Phase 3 (next): Full Relational.iterate generation
- Phase 4 (planned): Mode analysis

**Current Phase**: Phase 3 - Full Relational.iterate Generation
- Duration: 3 weeks
- Epics: 6 main epics
- Gate: Phase 3 review before Phase 4

---

## PHASE 2: Load Knowledge Context

### Knowledge Base Organization

**Primary Source**: Memory Bank project `morel_active`

Located at: `/Users/hal.hildebrand/git/.claude/memory_bank/morel_active/`

**Files in morel_active**:

1. **predicate-inversion-knowledge-integration.md** (START HERE)
   - Overview of all 6 research papers
   - Concept relationships
   - Implementation status by phase
   - Quick reference table

2. **algorithm-synthesis-ura-to-morel.md**
   - Abramov-Glück Universal Resolving Algorithm explained
   - Phase 3 implementation roadmap
   - Code examples for each step
   - Correctness properties and proofs

3. **mode-analysis-synthesis.md**
   - Hanus mode inference algorithm explained
   - Phase 4 implementation roadmap
   - Smart predicate ordering algorithm
   - Test cases for mode analysis

4. **complete-knowledge-base-summary.md**
   - Complete summary of all papers
   - Paper cross-references
   - Status by component
   - Validation checklist

5. **phase-3-4-strategic-plan.md**
   - High-level plan for Phases 3-4
   - Timeline and complexity estimates
   - Quality gates and success criteria
   - Risk management

6. **phase-3-4-bead-definitions.md**
   - Detailed bead specifications
   - Epic breakdown with acceptance criteria
   - Knowledge base references per epic
   - Dependency graph

7. **phase-3-4-execution-guide.md**
   - How to execute each epic
   - Guidelines for developers, reviewers, QA
   - Parallel development opportunities

### Research Papers Location

**File System**:
- Location: `/Users/hal.hildebrand/Documents/Predicate Inversion Research/`
- Papers: 5 PDFs (1 behind paywall)
- Coverage: Theoretical foundation + implementation patterns

**Papers**:

| Paper | Author(s) | Year | Use For |
|-------|-----------|------|---------|
| Universal Resolving Algorithm | Abramov & Glück | 2002 | Phase 3 foundation |
| Datafun | Arntzenius & Krishnaswami | 2016 | Type system safety |
| Flix | Madsen | 2024 | Implementation patterns |
| From Logic to Functional Logic | Hanus | 2022 | Phase 4 mode analysis |
| Functional Programming with Datalog | Pacak & Erdweg | 2022 | Alternative direction |
| Relational Expressions | Pratten & Mathieson | 2023 | Constraint handling |

### How to Use Knowledge Base

**For Phase 3 Development**:

1. Start: `predicate-inversion-knowledge-integration.md` (5 min overview)
2. Study: `algorithm-synthesis-ura-to-morel.md` (30 min detailed study)
3. Code: Reference examples in synthesis document
4. Papers: Read Abramov & Glück Sections 2.3-6.3 as needed

**Quick Links in Synthesis Documents**:

Each synthesis document has:
- Table of contents
- Section titles referencing paper sections
- Pseudo-code and algorithm examples
- Implementation examples from Morel

**Example Search Pattern**:

Q: "How do I build a perfect process tree?"

A: 1. Check `algorithm-synthesis-ura-to-morel.md` → "Step 1: Build Perfect Process Tree"
   2. Read the section, find code example
   3. Reference Abramov & Glück Section 2.3 for theory
   4. Look at ProcessTreeNode.java for existing implementation

---

## PHASE 3: Understand Current Implementation

### Key Files

**Main Implementation**:
- `src/main/java/net/hydromatic/morel/compile/PredicateInverter.java` (966 lines)
  - `invert()` method: Main entry point
  - `tryInvertTransitiveClosure()`: Phase 2 base case extraction (lines 419-464)
  - `invertAnds()`: Predicate conjunction handling (lines 599-745)
  - `invertOr()`: Predicate disjunction handling (lines 747-774)

**Tests**:
- `src/test/java/net/hydromatic/morel/compile/PredicateInverterTest.java` (7 tests)
  - Tests 1-7 from Phase 2: Basic patterns and edge cases

**Integration Tests**:
- `src/test/resources/script/such-that.smli`
  - Line 737-742: Transitive closure test (currently commented out)

**Integration Point**:
- `src/main/java/net/hydromatic/morel/compile/Extents.java` (line 538-541)
  - TODO comment shows where recursive predicate inversion hooks in

### Current Architecture

**Data Flow**:
```
SQL/SML Query
  ↓
Extents.g3 (grammar)
  ↓
Extents.java (line 538: TODO for user-defined functions)
  ↓
PredicateInverter.invert()
  ├─ Simple patterns (elem, comparison)
  ├─ tryInvertTransitiveClosure() [Phase 2]
  └─ Returns Optional<InversionResult>
  ↓
InversionResult
  ├─ generator: Core.Exp (the generated collection)
  ├─ mayHaveDuplicates: boolean
  ├─ isSupersetOfSolution: boolean
  └─ satisfiedPats/satisfiedFilters: metadata
```

### API Reference

**Key Classes** (in PredicateInverter.java):

```java
// Main entry point
public Optional<InversionResult> invert(
    Core.Apply apply,
    Set<NamedPat> goalPats,
    Map<String, Core.Binding> boundPats)

// Result of inversion
public class InversionResult {
    public final Core.Exp generator;
    public final boolean mayHaveDuplicates;
    public final boolean isSupersetOfSolution;
    public final List<Core.NamedPat> satisfiedPats;
    public final List<String> satisfiedFilters;
}
```

### What's Working

- Phase 1: Recursive functions don't crash (fallback to cartesian product)
- Phase 2: Detect transitive closure patterns and extract base case
- All 159 tests passing

### What Needs Implementation

- Phase 3.1: Perfect Process Tree construction
- Phase 3.2: Extended base + recursive case inversion
- Phase 3.3: Tabulation (I-O pair collection)
- Phase 3.4: Step function generation
- Phase 3.5: Relational.iterate integration
- Phase 3.6: Quality assurance and validation

---

## PHASE 4: Identify Active Beads

### How to Find Work

1. **Query bead system**: `bd ready` (shows unblocked work)
2. **Check `.pm/CONTINUATION.md`**: Next actions section
3. **Review `execution_state.json`**: `current_state.ready_beads`

### Bead Naming Convention

Format: `Phase<N>-<Epic>-<Focus>`

Examples:
- `Phase3-1-PPT-Design`: Design perfect process tree nodes
- `Phase3-2-Extended-Inversion`: Extend base + recursive inversion
- `Phase4-1-Mode-Analysis-Infra`: Design mode analysis infrastructure

### Bead Dependencies

From `phase-3-4-bead-definitions.md`:

```
3.1: PPT Design
├─ 3.2: Extended Inversion
│  ├─ 3.3: Tabulation
│  │  └─ 3.4: Step Function Gen
│  │     └─ 3.5: Full Integration
│  │        └─ 3.6: Phase 3 QA
```

**Parallel Opportunities**:
- Can design 3.1 and 3.2 in parallel
- Can design 4.1 (mode analysis) while 3.5-3.6 in progress

---

## PHASE 5: Resume Work

### Typical Session

1. **Load context** (10 min)
   ```
   Read: .pm/execution_state.json
   Read: .pm/CONTINUATION.md
   Check: bd ready (active beads)
   ```

2. **Review knowledge** (15-30 min depending on complexity)
   ```
   Read: relevant synthesis document section
   Skim: related paper section
   Check: existing code examples
   ```

3. **Do work** (2-4 hours)
   ```
   Implement bead acceptance criteria
   Run tests continuously
   Update progress
   ```

4. **Wrap up** (15 min)
   ```
   Run full test suite
   Commit changes
   Update .pm/CONTINUATION.md
   Mark bead complete
   ```

### Example: Resuming after 1-week break

**Time**: Monday 9 AM
**Goal**: Get back into Phase 3.1 work

**Actions**:

1. **Check state** (5 min):
   ```bash
   cat .pm/execution_state.json | jq .current_state
   cat .pm/CONTINUATION.md | head -50
   ```

2. **Understand what was done** (5 min):
   ```
   Read: CONTINUATION.md "What was completed"
   Result: "3.1 PPT design approved, implementation started"
   ```

3. **Check where we left off** (5 min):
   ```
   git log --oneline -10
   bd show <bead-id>
   ```

4. **Resume implementation** (2 hours):
   ```
   Read: algorithm-synthesis-ura-to-morel.md "Step 1"
   Check: ProcessTreeNode.java (what's implemented)
   Continue: Next test case
   ```

5. **Final checklist** (5 min):
   ```
   ./mvnw test (verify 159 tests passing)
   git diff (review changes)
   Update: CONTINUATION.md
   ```

---

## PHASE 6: Key Resources

### During Development

**For Understanding Algorithm**:
- `algorithm-synthesis-ura-to-morel.md`: Pseudo-code and examples
- Abramov & Glück 2002: Formal definitions (if needed)
- ProcessTreeNode.java: Existing structure (as reference)

**For Understanding Mode Analysis** (Phase 4):
- `mode-analysis-synthesis.md`: Algorithm explanation
- Hanus 2022: Formal definitions
- ModeAnalysisTest.java: Test patterns (as reference)

**For Testing**:
- Bead acceptance criteria (primary test guide)
- PredicateInverterTest.java: Test patterns
- such-that.smli: Integration test examples

**For Architecture**:
- `.pm/PHASES.md`: Overall structure
- `phase-3-4-strategic-plan.md`: Timeline and quality gates
- `phase-3-4-bead-definitions.md`: Epic breakdown

### Communication

**Asking for Help**:
1. Check synthesis document first
2. Search existing code for similar patterns
3. If still stuck, create hypothesis + ask architect

**Recording Insights**:
- Learnings: `.pm/learnings/L*.md`
- Hypotheses: `.pm/hypotheses/H*.md`
- Metrics: `.pm/metrics/*.md`

### Integration Points

**With Existing Code**:
- Extents.java line 538: Where predicate inversion hooks in
- FromBuilder.java: Where generators are built
- Core.java: Main AST representation

**With Tests**:
- PredicateInverterTest.java: Unit tests
- ScriptTest.java: Integration test runner
- such-that.smli: SML test script

---

## PHASE 7: Context Protocol Diagram

```
SESSION START
  ↓
Load execution_state.json
  ├─ Current phase?
  ├─ Tests passing?
  └─ Blockers?
  ↓
Read CONTINUATION.md
  ├─ What was done?
  ├─ Key learnings?
  └─ Next actions?
  ↓
Review knowledge base
  ├─ Start: predicate-inversion-knowledge-integration.md
  ├─ Detail: algorithm-synthesis-ura-to-morel.md (current phase)
  ├─ Code: PredicateInverter.java
  └─ Theory: Paper sections
  ↓
Find active work
  ├─ bd ready (show unblocked)
  └─ .pm/CONTINUATION.md (next actions)
  ↓
DO WORK
  ├─ Implement acceptance criteria
  ├─ Run tests
  └─ Request review
  ↓
Wrap up
  ├─ Full test suite pass?
  ├─ Update CONTINUATION.md
  ├─ Mark bead complete
  └─ Commit changes
  ↓
SESSION END
```

---

## Quick Reference: Most Important Files

**For understanding where we are**:
- `.pm/execution_state.json` - Current state
- `.pm/CONTINUATION.md` - What's happening

**For understanding what to do**:
- `.pm/PHASES.md` - Overall structure
- `phase-3-4-bead-definitions.md` - Current epic details

**For understanding algorithm**:
- `algorithm-synthesis-ura-to-morel.md` - Phase 3
- `mode-analysis-synthesis.md` - Phase 4

**For doing the work**:
- PredicateInverter.java - Main code
- PredicateInverterTest.java - Tests to run

**For understanding code quality**:
- `.pm/METHODOLOGY.md` - How we work
- Bead acceptance criteria - What "done" means

---

## Session Recovery Checklist

Use this if you're confused about where things stand:

- [ ] Read `.pm/execution_state.json` for current phase
- [ ] Read `.pm/CONTINUATION.md` for recent progress
- [ ] Run `mvnw test` to verify baseline (159 tests)
- [ ] Check `bd ready` to see available work
- [ ] Review knowledge base introduction files
- [ ] Look at bead acceptance criteria
- [ ] Read last 5 commits (`git log --oneline -5`)

If still confused after this checklist:
1. Search memory bank files for context
2. Check ChromaDB for prior decisions
3. Review research papers as needed
4. Ask architect for clarification

---

**Last Updated**: 2026-01-23
**For**: Morel Predicate Inversion Phase 3-4
**Next Milestone**: Transitive Closure Test Passing
