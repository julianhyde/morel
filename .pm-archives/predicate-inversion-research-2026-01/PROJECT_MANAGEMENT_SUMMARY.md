# Morel Predicate Inversion: Project Management Summary

**Quick Overview**: How this project is organized and managed

**Current Status**: Phase 2 Complete ✓ | Phase 3 Planning | Phase 4 Pending

**Team**: Solo developer (Hal Hildebrand) + agent support

**Duration**: 4-6 weeks total (Phases 3-4)

---

## What This Project Does

**Goal**: Enable Morel to invert boolean predicates (including recursive functions) into generator expressions.

**Example**:
```sml
fun path (x, y) = edge (x, y) orelse
  (exists z where edge (x, z) andalso path (z, y))

from p where path p
(* Returns all transitive paths: [(1,2),(2,3),(1,3)] *)
```

**Issue**: [#217 - Predicate Inversion](https://github.com/Hellblazer/morel/issues/217)

**Impact**: Enables efficient relational queries on recursive predicates, making Morel capable of expressing sophisticated datalog-style queries.

---

## Project Status at a Glance

### Progress
- **Tests Passing**: 159/159 ✓
- **Phase 1**: Complete ✓
- **Phase 2**: Complete ✓
- **Phase 3**: Ready to start
- **Phase 4**: Pending (after Phase 3)

### Key Metrics
- **Code Coverage**: ~75% (target: 85%+)
- **Blockers**: None
- **Code Reviews Approved**: Phases 1-2 ✓

### Timeline
```
Week 1-2:   Phase 1 (COMPLETE)
Week 2-3:   Phase 2 (COMPLETE)
Week 3-5:   Phase 3 (NEXT)
Week 5-6:   Phase 4 (AFTER P3 GATE)
```

---

## Key Files (Start Here)

### 1. CONTINUATION.md (5-10 min read)
**What**: Current state + next actions
**When**: Every session start
**Read First**: Yes, always

### 2. PHASES.md (15-20 min read)
**What**: Phase breakdown + 6 Phase 3 epics
**When**: Phase start
**For**: Understanding structure

### 3. METHODOLOGY.md (15 min read)
**What**: Engineering discipline (test-first, commits, quality gates)
**When**: Before implementing
**For**: Understanding how to work

### 4. CONTEXT_PROTOCOL.md (15 min read)
**What**: How to resume work after breaks
**When**: Returning from 1+ day break
**For**: Getting up to speed

### 5. KNOWLEDGE_INDEX.md (quick reference)
**What**: Index of all knowledge sources
**When**: Looking for information
**For**: Finding what you need

### 6. README.md (reference)
**What**: .pm/ directory guide + quick start
**When**: Confused about structure
**For**: Understanding infrastructure

---

## Quick Start: New to Project?

**Time**: 30 min to be productive

### Step 1: Read This File (5 min)
You're reading it now! ✓

### Step 2: Read CONTINUATION.md (5 min)
```
cd /Users/hal.hildebrand/git/morel
cat .pm/CONTINUATION.md
```
Learn what's been done and what's next.

### Step 3: Read PHASES.md (10 min)
```
cat .pm/PHASES.md
```
Understand Phase 3 structure (6 epics, who does what).

### Step 4: Run Tests (5 min)
```
./mvnw test
```
Verify baseline (159 tests passing = good).

### Step 5: Ready to Work? (5 min)
```
bd ready
```
See available work. Pick a bead and read its description.

---

## Resuming After Break

**Time**: 20 min to be productive

### Pattern: Context → Knowledge → Work

1. **Read CONTINUATION.md** (5 min)
   - What was happening?
   - What's next?

2. **Verify Baseline** (3 min)
   ```bash
   ./mvnw test
   ```
   Should show 159 tests passing.

3. **Find Work** (2 min)
   ```bash
   bd ready
   ```
   Shows unblocked work.

4. **Load Knowledge** (5-10 min)
   - Read relevant synthesis doc section
   - Skim paper if needed
   - Review test patterns

5. **Start Implementing** (ready to work!)
   - Follow bead acceptance criteria
   - Reference synthesis doc
   - Run tests frequently

**Full Resumption Guide**: See CONTEXT_PROTOCOL.md "PHASE 5: Resume Work"

---

## Understanding the Structure

### The .pm/ Directory

```
.pm/                          ← You are here
├── README.md                 ← .pm/ guide
├── CONTINUATION.md           ← Current state
├── CONTEXT_PROTOCOL.md       ← How to resume
├── METHODOLOGY.md            ← Engineering discipline
├── AGENT_INSTRUCTIONS.md     ← How to spawn agents
├── PHASES.md                 ← Phase breakdown
├── KNOWLEDGE_INDEX.md        ← Knowledge index
├── PROJECT_MANAGEMENT_SUMMARY.md (THIS FILE)
├── BEAD_TEMPLATE.md          ← Template for new beads
├── execution_state.json      ← Metrics + state
│
├── checkpoints/              ← Session progress
├── learnings/                ← Insights captured
├── hypotheses/               ← Design decisions
├── metrics/                  ← Performance data
├── audits/                   ← Quality gates
├── tests/                    ← Test strategy
├── performance/              ← Benchmarks
├── code-reuse/               ← Patterns found
└── thinking/                 ← Deep analysis
```

### The Work System: Beads

**Beads** track all work. Think of them as structured TODOs.

```bash
# See available work
bd ready

# Create new bead
bd create "Phase3-PPT-Design" -t epic -p 1

# Start work
bd update <id> --status in_progress

# Mark complete
bd close <id>

# Check specific bead
bd show <id>
```

**Bead Format**: `Phase<N>-<Epic>-<Focus>`
- Example: `Phase3-1-PPT-Design`
- Example: `Phase4-3-Circular-Dependencies`

---

## The 4-Phase Plan

### Phase 1: Recursive Function Handling
**Status**: ✓ COMPLETE
- Goal: Handle recursive functions gracefully
- Result: 159 tests passing, no crashes

### Phase 2: Pattern Detection & Base Case Extraction
**Status**: ✓ COMPLETE
- Goal: Detect transitive closure patterns
- Result: Base case extraction working, 159 tests passing

### Phase 3: Full Relational.iterate Generation (NEXT)
**Status**: PLANNING (this week)
- Goal: Generate complete Relational.iterate expressions
- Duration: 3 weeks
- Epics: 6 (PPT, Extended Inversion, Tabulation, Step Function, Integration, QA)
- Target Test: such-that.smli:737-742 (transitive closure output correct)

### Phase 4: Mode Analysis & Smart Generator Selection
**Status**: PENDING (after Phase 3 gate approval)
- Goal: Smart predicate ordering based on mode analysis
- Duration: 2-2.5 weeks
- Epics: 5 (Infrastructure, Smart Ordering, Circular Deps, Optimization, QA)

---

## How Work Gets Done

### For Each Feature (Epic)

```
1. DESIGN (1 day)
   ├─ Architect designs component
   ├─ Synthesizes research papers
   └─ Gets approval

2. IMPLEMENTATION (3-5 days)
   ├─ Tests written first (test stubs)
   ├─ Code implemented to pass tests
   ├─ Comments added (with paper references)
   └─ All 159 baseline tests still passing

3. CODE REVIEW (4 hours)
   ├─ Reviewer checks quality
   ├─ Comments addressed
   └─ Gets approval

4. QUALITY GATE (if last epic)
   ├─ All tests passing?
   ├─ Coverage >= 85%?
   ├─ Performance acceptable?
   └─ Plan-auditor approval
```

### Who Does What

**Developer** (Hal):
- Implement beads
- Run tests
- Commit code
- Update progress

**Architects** (Agent: java-architect-planner):
- Design epics
- Review designs
- Validate approach

**Code Reviewers** (Agent: code-review-expert):
- Review code quality
- Check correctness
- Verify patterns

**Validators** (Agent: test-validator):
- Check test coverage
- Verify edge cases

**Gatekeepers** (Agent: plan-auditor):
- Gate phase transitions
- Verify criteria met

---

## Key Principles

### 1. Test-First Development
- Write tests before code
- All tests must pass
- 85%+ coverage target

### 2. Commit Discipline
- Atomic commits
- Reference bead in commit
- Clear commit messages
- **No AI attribution** (company policy)

### 3. Knowledge Integration
- Link to synthesis documents
- Reference papers in code
- Capture insights in learnings/
- Persist knowledge in ChromaDB

### 4. Agent Delegation
- Design: java-architect-planner
- Implementation: java-developer
- Code review: code-review-expert
- Quality gates: plan-auditor

### 5. Progress Tracking
- Beads for all work
- Daily continuation updates
- Weekly metric updates
- Clear success criteria

---

## The Knowledge Base

### Synthesis Documents (Memory Bank)
Located in: `~/.claude/memory_bank/morel_active/`

**Create these before Phase 3 starts**:
1. predicate-inversion-knowledge-integration.md
2. algorithm-synthesis-ura-to-morel.md
3. phase-3-4-bead-definitions.md
4. phase-3-4-execution-guide.md

**Create before Phase 4 starts**:
5. mode-analysis-synthesis.md

### Research Papers (Local)
Located in: `/Users/hal.hildebrand/Documents/Predicate Inversion Research/`

**Key Papers**:
- Abramov & Glück (2002): Universal Resolving Algorithm
- Hanus (2022): Mode analysis for logic programming
- Arntzenius & Krishnaswami (2016): Datafun (type safety)
- Others: Flix, Pacak & Erdweg, Pratten & Mathieson

---

## Success Criteria

### Phase 3 Gate (Before Phase 4 Starts)
- [ ] Transitive closure test passing (such-that.smli:737-742)
- [ ] 159 baseline tests still passing
- [ ] 25+ Phase 3 tests passing
- [ ] Coverage >= 85%
- [ ] Code reviewed and approved
- [ ] Performance < 10ms per query

### Phase 4 Gate (Before Release)
- [ ] 20+ mode analysis tests passing
- [ ] All Phase 3+4 tests passing
- [ ] Coverage >= 85%
- [ ] Code reviewed and approved
- [ ] Mode analysis overhead < 5%

### Project Complete
- [ ] All 4 phases complete
- [ ] All tests passing (150+)
- [ ] Coverage >= 85%
- [ ] Performance meets targets
- [ ] Issue #217 resolved
- [ ] Ready for release

---

## Common Tasks

### "I want to start Phase 3 now"

1. **Create beads** (using orchestrator)
   - 6 epic beads per PHASES.md
   - Set dependencies correctly

2. **Create memory bank** (morel_active project)
   - Copy synthesis doc templates
   - Fill with knowledge

3. **Design Phase 3.1** (java-architect-planner)
   - ProcessTreeNode hierarchy
   - Integration points

4. **Start implementation** (java-developer)
   - ProcessTreeNode.java
   - ProcessTreeTest.java

### "I need to understand the algorithm"

1. Read: algorithm-synthesis-ura-to-morel.md (synthesis doc)
2. Read: Abramov & Glück 2002, Section 2.3 (theory)
3. Read: Code examples in synthesis doc
4. Review: Similar patterns in existing code

### "Tests are failing"

1. Check: Test error message
2. Review: Code being tested
3. Read: Relevant synthesis doc section
4. Debug: Add logging, trace execution
5. Fix: Update code
6. Verify: All tests pass

### "I'm blocked on something"

1. Document: The blocker (what, why, impact)
2. Update: execution_state.json blockers field
3. Escalate: Ask java-architect-planner if design issue
4. Continue: Other work if possible

---

## Quick Reference

### Most Important Files
- **CONTINUATION.md**: Current state (read every session)
- **PHASES.md**: Phase structure (read at phase start)
- **METHODOLOGY.md**: How to work (read before impl)
- **KNOWLEDGE_INDEX.md**: Find information (quick reference)

### Most Important Commands
```bash
# See available work
bd ready

# Run all tests
./mvnw test

# See current state
cat .pm/execution_state.json | jq .current_state

# See what happened last session
cat .pm/CONTINUATION.md
```

### Most Important URLs
- **Repository**: https://github.com/Hellblazer/morel
- **Branch**: 217-phase1-transitive-closure
- **Issue**: #217 (Predicate Inversion)

---

## Getting Help

### For Different Questions

| Question | Ask | Tool |
|----------|-----|------|
| "How do I design X?" | java-architect-planner | `/java-architect-planner` |
| "How do I implement X?" | java-developer | `/java-developer` |
| "Is my code good?" | code-review-expert | `/code-review` |
| "Are tests sufficient?" | test-validator | `/test-validate` |
| "Can we proceed to next phase?" | plan-auditor | `/plan-audit` |
| "Algorithm understanding?" | Synthesis docs + papers | Read docs, then ask architect |
| "What should I do next?" | CONTINUATION.md | Read file |
| "Confused overall?" | README.md + PHASES.md | Read docs |

### Escalation Path
1. Check CONTINUATION.md (5 min)
2. Check synthesis documents (10 min)
3. Check existing code patterns (10 min)
4. Ask architect for design review (30 min)
5. Ask debugger for test failures (1 hour)

---

## Metrics Dashboard

**Current Status** (from execution_state.json):

| Metric | Value | Target |
|--------|-------|--------|
| Tests Passing | 159/159 | 159+ |
| Phase 1 | COMPLETE | COMPLETE |
| Phase 2 | COMPLETE | COMPLETE |
| Phase 3 | PLANNING | IN_PROGRESS |
| Phase 4 | PENDING | IN_PROGRESS |
| Code Coverage | ~75% | 85%+ |
| Blockers | 0 | 0 |

**Update Schedule**:
- Daily: CONTINUATION.md
- Weekly: execution_state.json
- Per Phase: Phase gate review

---

## Next Steps

### This Week
1. Create Phase 3 beads (orchestrator)
2. Design Phase 3.1: PPT (java-architect-planner)
3. Review design
4. Start implementation

### This Phase (3 weeks)
1. Implement 6 epics (developer + code review)
2. Each epic: design → implement → review
3. Integration: wire all together
4. Testing: comprehensive test suite
5. Gate review: plan-auditor approval

### Next Phase (2-2.5 weeks)
1. Design mode analysis infrastructure
2. Implement smart predicate ordering
3. Handle circular dependencies
4. Performance optimization
5. Final testing and release

---

## FAQ

**Q: Do I need to read all the .pm/ files?**
A: No. Start with CONTINUATION.md, then read specific files as needed.

**Q: How often should I update CONTINUATION.md?**
A: Every session (end of day). Takes 5-10 min to summarize progress.

**Q: Can I work on multiple epics in parallel?**
A: No, not until Phase 3.1 is complete. Then you can design multiple in parallel.

**Q: What if I find a bug in Phase 2 code?**
A: Document in learnings/, fix it, add test, commit with bead reference.

**Q: How do I know if my code is good?**
A: (1) All tests pass, (2) Coverage >= 85%, (3) Code review approved, (4) No performance regression.

**Q: What if I need to stop work?**
A: Update CONTINUATION.md with next actions. Code will be ready for you to resume.

**Q: Can I commit without all tests passing?**
A: No. All 159 baseline tests must pass before commit.

---

## Support

**For This Infrastructure**: See README.md

**For Algorithm Questions**: Read synthesis docs, then ask java-architect-planner

**For Implementation Help**: Ask java-developer agent

**For Code Quality**: Ask code-review-expert agent

**For Test Coverage**: Ask test-validator agent

**For Debugging**: Ask java-debugger agent

**For Quality Gates**: Ask plan-auditor agent

---

**Status**: Ready for Phase 3 Implementation

**Next Milestone**: Phase 3 Gate Approval

**Expected Completion**: 4-6 weeks (Phases 3-4)

---

**Last Updated**: 2026-01-23
**For**: Morel Predicate Inversion Issue #217
**Next Read**: CONTINUATION.md
