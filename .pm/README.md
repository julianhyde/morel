# Morel Predicate Inversion: Project Management

**Status**: Phase 2 Complete, Phase 3 Planning
**Location**: `.pm/` directory
**For**: Managing Morel Issue #217 implementation

---

## Quick Start

### First Time?

1. Read this file (you are here)
2. Read `CONTINUATION.md` (current state)
3. Read `CONTEXT_PROTOCOL.md` (how to resume)
4. Read `PHASES.md` (project structure)

### Resuming Work After Break?

1. Read `CONTINUATION.md` (what was done)
2. Run tests to verify baseline
3. Check `bd ready` for available work
4. Read relevant synthesis document
5. Start work on assigned bead

### Starting a Phase?

1. Read `PHASES.md` for phase overview
2. Create beads using orchestrator
3. Read relevant synthesis documents
4. Spawn architect for design (java-architect-planner)
5. Execute epics (java-developer)

---

## Directory Structure

```
.pm/
├── README.md (THIS FILE)
├── CONTEXT_PROTOCOL.md (How to resume work)
├── CONTINUATION.md (Current state + next actions)
├── METHODOLOGY.md (Engineering discipline)
├── AGENT_INSTRUCTIONS.md (How to spawn agents)
├── PHASES.md (Phase breakdown + epics)
├── KNOWLEDGE_INDEX.md (Index of knowledge sources)
├── execution_state.json (Metrics + state tracking)
│
├── checkpoints/          (Session progress tracking)
│   ├── TEMPLATE-checkpoint.md
│   └── *.md (one per session/day)
│
├── learnings/            (Insights captured during work)
│   ├── TEMPLATE-learning.md
│   └── L*.md (L0, L1, L2, ...)
│
├── hypotheses/           (Design decisions + validation)
│   ├── TEMPLATE-hypothesis.md
│   └── H*.md (H0, H1, H2, ...)
│
├── metrics/              (Performance + progress metrics)
│   ├── TEMPLATE-metrics.md
│   ├── phase-3-performance.md (to create)
│   ├── phase-4-performance.md (to create)
│   └── *.md (per-phase/per-component)
│
├── audits/               (Quality gates + retrospectives)
│   ├── TEMPLATE-audit.md
│   ├── phase-3-gate.md (to create)
│   └── *.md
│
├── tests/                (Test strategy + coverage)
│   ├── TEMPLATE-test-plan.md
│   └── phase-3-tests.md (to create)
│
├── performance/          (Benchmarks + profiling)
│   ├── TEMPLATE-benchmark.md
│   └── phase-3-baseline.md (to create)
│
├── code-reuse/           (Patterns + libraries)
│   └── (usage patterns discovered)
│
└── thinking/             (Deep analysis sessions)
    ├── phase-3-design-decision.md (to create)
    └── phase-4-architecture.md (to create)
```

---

## Core Files: What Each Does

### CONTINUATION.md
**Purpose**: Current state snapshot
**Read**: Every session start
**Update**: Every session end
**Contains**:
- What was completed recently
- Key learnings from last session
- Active hypotheses
- Current blockers
- Next actions with priority

### CONTEXT_PROTOCOL.md
**Purpose**: How to resume work
**Read**: When resuming after break > 1 day
**Contains**:
- Step-by-step session resumption
- Knowledge base loading sequence
- Current implementation overview
- How to find active work
- Example: resuming after 1-week break

### METHODOLOGY.md
**Purpose**: Engineering discipline
**Read**: Before implementing features
**Contains**:
- Test-first development approach
- Commit discipline (no AI attribution)
- Knowledge base integration workflow
- Agent delegation patterns
- Quality gates and checklist
- Performance benchmarking
- Code review checklist
- When things go wrong (debugging approach)

### AGENT_INSTRUCTIONS.md
**Purpose**: How to spawn agents
**Read**: Before spawning any agent
**Contains**:
- When to spawn each agent type
- Handoff template for each agent
- Communication patterns
- Examples for each agent
- Bead status tracking

### PHASES.md
**Purpose**: Project structure and epics
**Read**: Beginning of phase
**Contains**:
- Phase overview (1-4)
- Epic breakdown (6 in Phase 3, 5 in Phase 4)
- Acceptance criteria per epic
- Dependencies between epics
- Success criteria for phase gates
- Timeline and complexity
- Risk assessment

### KNOWLEDGE_INDEX.md
**Purpose**: Index of all knowledge sources
**Read**: When looking for information
**Contains**:
- Memory bank synthesis documents guide
- Research papers summary
- Key files in codebase
- ChromaDB document references
- How to find information patterns
- Synthesis document checklist

### execution_state.json
**Purpose**: Project metrics and state
**Read**: At phase transitions, weekly updates
**Update**: Weekly, after major milestones
**Contains**:
- Phase status (complete/ready/pending)
- Metrics (tests passing, coverage)
- Success criteria by phase
- Knowledge base references
- Team assignments
- Integration points

---

## How to Work with This Infrastructure

### Daily Workflow

**Morning** (10 min):
```
1. Read CONTINUATION.md (what was going on)
2. Check bd ready (available work)
3. Read relevant bead description
4. Start work
```

**During Work** (ongoing):
```
- Implement bead acceptance criteria
- Run tests continuously
- Reference synthesis documents
- Add comments linking to papers
```

**End of Day** (15 min):
```
1. Run full test suite
2. Commit changes (with bead reference)
3. Update CONTINUATION.md (what was done)
4. Mark bead complete if done
```

### Epic Workflow

**Phase Start**:
```
1. Read PHASES.md for epic list
2. Create beads (using orchestrator)
3. Set dependencies with bd dep add
```

**Epic Implementation**:
```
1. Read epic in PHASES.md
2. Architect designs (java-architect-planner)
3. Developer implements (java-developer)
4. Reviewer reviews (code-review-expert)
5. Mark complete (bd close)
```

**Phase Completion**:
```
1. Run full test suite (verify no regressions)
2. Validate with test-validator
3. Gate review with plan-auditor
4. Update execution_state.json
5. Proceed to next phase
```

### Session Resumption (After Break)

**Pattern**: Context → Knowledge → Work

```
1. Read CONTINUATION.md (max 5 min)
   → Understand: What happened, why, what's next

2. Check Current State (2 min)
   → mvnw test (verify 159 tests passing)
   → git log --oneline -5 (see recent commits)
   → bd ready (see available work)

3. Load Knowledge (5-15 min depending on phase)
   → Read relevant synthesis document section
   → Skim paper sections if needed
   → Review test patterns

4. Resume Work (2-4 hours)
   → Implement acceptance criteria
   → Run tests
   → Update progress
```

### Quality Gate Workflow

**Before Proceeding to Next Phase**:

```
1. All success criteria met? (check PHASES.md)
   - Tests passing? (159 + new phase tests)
   - Coverage >= 85%? (check coverage report)
   - Code reviewed? (all epics approved)
   - Performance acceptable? (benchmarks collected)

2. Record audit (audits/phase-N-gate.md)
   - What was verified
   - Evidence collected
   - Sign-off

3. Plan-auditor reviews (spawn agent)
   - Verifies all criteria
   - Approves or requests changes

4. Proceed to next phase (update execution_state.json)
   - Set phase status
   - Document transition
   - Note any risks
```

---

## Templates: Available Resources

### Checkpoint (checkpoints/TEMPLATE-checkpoint.md)
Use daily/session to record progress
```
## Session Checkpoint: [Date]

### Context
- Active bead: [Bead ID]
- Previous progress: [Summary]

### Work Completed
- [Item 1]
- [Item 2]

### Decisions Made
- [Decision 1]: [Rationale]

### Blockers Encountered
- [Blocker]: [Mitigation]

### Next Actions
- [Priority 1]
- [Priority 2]

### Metrics Update
- Tests passing: X/159
- Coverage: Y%
```

### Learning (learnings/TEMPLATE-learning.md)
Use to capture insights
```
## L0: [Learning Title]

### Date
[Date discovered]

### The Learning
[What was learned]

### Why It Matters
[Impact on project]

### Evidence
[How you know this is true]

### Action Items
[What to do with this insight]

### Related
[Cross-references to other knowledge]
```

### Hypothesis (hypotheses/TEMPLATE-hypothesis.md)
Use for design decisions
```
## H0: [Hypothesis Title]

### Date Proposed
[Date]

### Status
[pending/validated/rejected]

### The Hypothesis
[What you think is true]

### Rationale
[Why you believe it]

### Validation Criteria
[How to prove/disprove]

### Testing Approach
[What to test]

### Results
[What you found]

### Conclusion
[Did it hold up?]

### Impact
[Effect on design]

### Related
[Cross-references]
```

### Metrics (metrics/TEMPLATE-metrics.md)
Use to track performance
```
## [Phase] Performance Metrics

### Baseline (Before Implementation)
- Test runtime: X ms
- Memory usage: Y MB
- Coverage: Z%

### Current (After Implementation)
- Test runtime: X' ms
- Memory usage: Y' MB
- Coverage: Z'%

### Change
- Time: +/-X ms (±%Y)
- Memory: +/-Y MB (±%Y)
- Coverage: +Z% points

### Assessment
[Pass/warning/fail with rationale]

### Notes
[Special observations]
```

---

## Beads: Task Tracking

**Beads are the primary task system** - use them for everything:

```bash
# See ready work (not blocked)
bd ready

# Create a new bead
bd create "Phase3-PPT-Design" -t epic -p 1

# Start work
bd update <id> --status in_progress

# Complete work
bd close <id>

# Add dependencies
bd dep add <id> <blocker-id>

# Check status
bd show <id>
```

**Bead Naming Convention**:
```
Phase<N>-<Epic>-<Focus>

Examples:
- Phase3-1-PPT-Design
- Phase3-2-Extended-Inversion
- Phase4-1-Mode-Analysis-Infra
```

---

## Knowledge Base: Memory Bank

**Location**: `~/.claude/memory_bank/morel_active/`

**Files to Create/Use**:
1. predicate-inversion-knowledge-integration.md
2. algorithm-synthesis-ura-to-morel.md (Phase 3)
3. mode-analysis-synthesis.md (Phase 4)
4. phase-3-4-bead-definitions.md
5. phase-3-4-execution-guide.md

**How to Access** (via mcp tool):
```python
# Read a file
mcp__allPepper-memory-bank__memory_bank_read(
  projectName="morel_active",
  fileName="algorithm-synthesis-ura-to-morel.md"
)

# Write a file
mcp__allPepper-memory-bank__memory_bank_write(
  projectName="morel_active",
  fileName="phase-3-1-ppt-learning.md",
  content="..."
)

# Update a file
mcp__allPepper-memory-bank__memory_bank_update(
  projectName="morel_active",
  fileName="algorithm-synthesis-ura-to-morel.md",
  content="..."
)
```

---

## Metrics: Tracking Progress

**Weekly Updates**:

```bash
# Update execution_state.json with:
- current_state.phase (what phase are we in?)
- metrics.tests_passing (how many tests passing?)
- current_state.ready_beads (what work is available?)
- current_state.blockers (what's blocking progress?)
```

**Phase Transitions**:

```bash
# At phase gate:
- Verify all success criteria met (see PHASES.md)
- Create audit file (audits/phase-N-gate.md)
- Get plan-auditor approval
- Update execution_state.json phase status
- Document in CONTINUATION.md
```

---

## Troubleshooting

### "I'm confused about what to do next"

1. Read CONTINUATION.md (2 min)
2. Check `bd ready` (1 min)
3. Read bead description (3 min)
4. Read relevant synthesis doc (5-10 min)
5. Still confused? Ask architect

### "I don't understand the algorithm"

1. Read synthesis document for your phase
2. Read paper section linked in synthesis
3. Review code examples
4. Try implementing simple test case
5. Request design review from architect

### "Tests are failing"

1. Check error message
2. Read test description
3. Review code being tested
4. Add logging
5. Reference synthesis document
6. Request debugging help if stuck

### "Performance degraded"

1. Collect baseline metrics (from .pm/metrics/)
2. Profile current code
3. Compare vs. baseline
4. Identify hot spot
5. Optimize or adjust algorithm
6. Re-benchmark

### "I don't remember what happened last session"

1. Read CONTINUATION.md (entire file)
2. Check git log (last 5-10 commits)
3. Read CONTEXT_PROTOCOL.md Phase 1-2
4. Check learnings/ for insights
5. Check checkpoints/ for recent sessions

---

## Handoff: Leaving Project

When stopping work for extended period:

1. **Complete current bead** or move to milestone
2. **Update CONTINUATION.md**:
   - What was accomplished this session
   - Key learnings
   - Active hypotheses
   - Next actions for resumption
   - Identified blockers
   - Recommended reading

3. **Save progress**:
   - Commit code with bead reference
   - Update execution_state.json
   - Save checkpoint in checkpoints/

4. **Persist knowledge**:
   - Save learnings in learnings/
   - Save hypotheses in hypotheses/
   - Update synthesis documents if changed
   - Save to ChromaDB if permanent knowledge

---

## Handoff: Receiving Project

When resuming after extended break:

1. **Read CONTINUATION.md** (entire file, 10 min)
2. **Verify baseline**: `mvnw test` (5 min)
3. **Check state**: Read execution_state.json (2 min)
4. **Find work**: `bd ready` (1 min)
5. **Load knowledge**: Read synthesis doc (10-15 min)
6. **Start work**: Implement acceptance criteria

---

## Integration Points

### Git Workflow

**Branch**: 217-phase1-transitive-closure (local development)

**Commit Format**:
```
<Scope>: <Description>

<Detailed explanation>

References: <Bead ID>
```

**Example**:
```
PredicateInverter: Implement PPT node representation

Add ProcessTreeNode hierarchy to represent computation paths.
This enables Phase 3 perfect process tree construction.

References: Phase3-PPT-Design
```

### Testing Framework

**Test Files**:
- PredicateInverterTest.java (primary)
- such-that.smli (integration)

**Run Tests**:
```bash
./mvnw test                              # All tests
./mvnw test -Dtest=PredicateInverterTest # Specific test
./mvnw test -Dtest=ScriptTest            # Integration
```

### Code Review

**Process**:
1. Implement feature
2. All tests pass
3. Request code review (code-review-expert agent)
4. Address comments
5. Get approval
6. Merge

### Quality Gates

**Automatic Checks**:
- All tests pass (159+)
- No compiler warnings
- Code follows style

**Manual Reviews**:
- Code review (code-review-expert)
- Phase gate (plan-auditor)
- Architecture alignment (java-architect-planner)

---

## Success: How to Know You're Done

### For Each Bead

- [ ] All acceptance criteria implemented
- [ ] All tests passing
- [ ] Code reviewed and approved
- [ ] Coverage >= 85%
- [ ] Committed with bead reference
- [ ] Marked complete (bd close)

### For Each Phase

- [ ] All epics complete
- [ ] All success criteria met (see PHASES.md)
- [ ] Tests passing: 159 + phase tests
- [ ] Code coverage >= 85%
- [ ] Performance meets targets
- [ ] Code review approved
- [ ] Gate review approved
- [ ] Next phase ready to start

### For Project

- [ ] Phase 3 complete + gate approved
- [ ] Phase 4 complete + gate approved
- [ ] 159+ tests passing
- [ ] Coverage >= 85%
- [ ] Performance acceptable
- [ ] Issue #217 resolved
- [ ] Ready for release

---

## Quick Reference Checklist

**Morning Standup** (5 min):
- [ ] Read CONTINUATION.md
- [ ] Check `bd ready`
- [ ] Read bead description
- [ ] Understand task priority

**End of Day** (10 min):
- [ ] Tests passing
- [ ] Work committed
- [ ] CONTINUATION.md updated
- [ ] Bead progress noted

**Phase Boundary** (30 min):
- [ ] All epics complete
- [ ] Success criteria met
- [ ] Code reviewed
- [ ] Tests passing
- [ ] Metrics updated
- [ ] Gate review scheduled

---

## Support Resources

**For Architecture Questions**: Spawn java-architect-planner

**For Implementation Help**: Spawn java-developer

**For Code Quality**: Spawn code-review-expert

**For Test Coverage**: Spawn test-validator

**For Debugging**: Spawn java-debugger

**For Quality Gate**: Spawn plan-auditor

See `AGENT_INSTRUCTIONS.md` for handoff templates.

---

## File Ownership

| File | Owner | Update Frequency | Purpose |
|------|-------|------------------|---------|
| CONTINUATION.md | Developer | Every session | Current state |
| execution_state.json | Developer | Weekly | Metrics + phases |
| PHASES.md | Architect | Phase start | Epic planning |
| METHODOLOGY.md | Team | As needed | Process discipline |
| checkpoints/ | Developer | Daily/session | Progress tracking |
| learnings/ | Developer | Per insight | Knowledge capture |
| hypotheses/ | Architect+Dev | Per decision | Design tracking |
| metrics/ | Developer | Per benchmark | Performance tracking |

---

**Last Updated**: 2026-01-23

**For**: Morel Predicate Inversion Phase 3-4 Project Management

**Next Step**: Read CONTINUATION.md
