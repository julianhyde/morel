# Bead Template: Morel Predicate Inversion

**Purpose**: Template for creating beads for Phase 3-4 epics

**Usage**: Copy this structure when creating new bead. Fill in all sections.

---

## Bead: [TITLE]

**Bead ID**: [Phase-N]-[Epic-Name] (e.g., Phase3-1-PPT-Design)
**Type**: epic | feature | task | chore
**Status**: pending | design_review | ready_for_implementation | in_progress | review | completed
**Complexity**: S | M | L
**Time Estimate**: [X-Y days or weeks]
**Date Created**: [YYYY-MM-DD]
**Owner**: [Developer or Agent name, or "TBD"]

---

## Summary

[One-sentence description of what this bead accomplishes]

**Example**: "Implement ProcessTreeNode hierarchy for perfect process tree construction"

---

## Description

[1-2 paragraph detailed description]

Include:
- What is being built
- Why it's important
- How it fits into the phase
- What it enables

**Example**:
```
This epic implements the ProcessTreeNode hierarchy, a core data structure for representing
computation paths in the Abramov-Glück Universal Resolving Algorithm. The perfect process tree
(PPT) captures all possible execution paths through a recursive predicate, distinguishing between
base case and recursive case invocations.

This work enables Phase 3.2 (extended base+recursive inversion) and is critical for generating
correct Relational.iterate expressions. Without PPT nodes, we cannot properly tabulate the
I-O pairs needed for transitive closure inversion.
```

---

## Acceptance Criteria

[3-5 specific, testable criteria. Each must be verifiable.]

Format: `1. [Criterion]` (must be measurable/testable)

**Example**:
```
1. ProcessTreeNode.java created with complete class hierarchy (Terminal, Branch, Environment)
2. ProcessTreeBuilder.java builds correct PPT for all test cases in ProcessTreeTest
3. Visitor pattern implemented and tested with 10+ test cases
4. All tests passing (12 tests in ProcessTreeTest.java)
5. Code coverage >= 85% for ProcessTreeNode + ProcessTreeBuilder
```

---

## Dependencies

**Blocks**: [Which beads depend on this one - list them]
**Depends On**: [Which beads must complete first - list them]
**Related**: [Other beads that are related but not blocking]

**Example**:
```
Blocks: Phase3-2-Extended-Inversion, Phase3-3-Tabulation, Phase3-4-Step-Function
Depends On: None (Phase 2 complete)
Related: Phase4-1-Mode-Analysis-Infra (can design in parallel after this is approved)
```

---

## Knowledge Base References

**Synthesis Documents**:
- [Document Name]: [Section reference]
- Example: `algorithm-synthesis-ura-to-morel.md`: "Step 1: Build Perfect Process Tree"

**Research Papers**:
- [Paper Name] ([Author Year]): [Section reference]
- Example: `Abramov & Glück (2002)`: Section 2.3 (Perfect Process Tree definitions)

**Code Examples**:
- [File Name]: [Lines or sections to reference]
- Example: `Core.java`: Exp node traversal patterns (lines 150-200)

**Memory Bank**:
- `morel_active/predicate-inversion-knowledge-integration.md` (overview)
- `morel_active/algorithm-synthesis-ura-to-morel.md` (this phase details)

**Example Full Block**:
```
Synthesis Documents:
- algorithm-synthesis-ura-to-morel.md: "Step 1: Build Perfect Process Tree"
- predicate-inversion-knowledge-integration.md: "PPT Overview"

Research Papers:
- Abramov & Glück (2002): Section 2.3 (PPT definitions and construction)
- Abramov & Glück (2002): Section 2.4 (PPT examples)

Code Examples:
- Core.java: Exp node traversal patterns (lines 150-200)
- Core.java: Similar visitor pattern implementations (search for "Visitor")
- PredicateInverter.java: Existing node structure (InversionResult class)

Memory Bank:
- morel_active/algorithm-synthesis-ura-to-morel.md (detailed walkthrough)
- morel_active/phase-3-4-execution-guide.md (execution patterns)
```

---

## Files Involved

**Files to Create**:
- [File path and description]

**Files to Modify**:
- [File path, specific sections, and purpose]

**Test Files**:
- [Test file with new test names]

**Example**:
```
Files to Create:
- src/main/java/net/hydromatic/morel/compile/ProcessTreeNode.java (node hierarchy)
- src/main/java/net/hydromatic/morel/compile/ProcessTreeBuilder.java (PPT construction)

Files to Modify:
- src/main/java/net/hydromatic/morel/compile/PredicateInverter.java (import ProcessTreeNode)

Test Files:
- src/test/java/net/hydromatic/morel/compile/ProcessTreeTest.java (12 new tests)
  - testTerminalNodeCreation()
  - testBranchNodeAlternatives()
  - testEnvironmentTracking()
  - testDepthFirstTraversal()
  - [... 8 more test cases ...]
```

---

## Design Notes

[Any design decisions, trade-offs, or architectural considerations]

**Example**:
```
- Use abstract base class (ProcessTreeNode) with concrete subclasses
- Implement visitor pattern for clean traversal separation
- Immutable nodes (functional style) to avoid mutation bugs
- Track environment separately from node structure (easier composition)
- Consider memory efficiency for large PPTs (lazy evaluation?)
```

---

## Test Strategy

[How this work will be tested - beyond just the acceptance criteria]

**Example**:
```
Unit Tests:
- Terminal node creation and basic properties
- Branch node with multiple alternatives
- Environment variable tracking
- Visitor pattern traversal
- Duplicate element handling
- Edge case: empty PPT
- Edge case: single-node PPT
- Edge case: deep PPT (10+ levels)
- Edge case: cyclic references (should not happen, but defensive code)

Property Tests:
- All nodes in PPT reachable from root
- No orphaned nodes
- Visitor visits all nodes exactly once

Integration Tests:
- PPT construction from Phase 2 base cases
- PPT traversal for Phase 3.3 tabulation
```

---

## Performance Considerations

[Performance targets, expected complexity, benchmarking approach]

**Example**:
```
Performance Target:
- PPT construction: < 1ms for test cases
- Visitor traversal: O(nodes in tree)
- Memory: reasonable for predicates with 2-3 levels of recursion

Benchmarking:
- Time operation on test PPTs
- Compare with baseline (if applicable)
- Profile memory usage
- Document results in .pm/metrics/phase-3-performance.md
```

---

## Validation Approach

[How to validate this work is correct]

**Example**:
```
1. All 12 ProcessTreeTest.java tests must pass
2. Code review with architecture alignment
3. Coverage report >= 85%
4. Property tests confirming soundness properties
5. Integration with Phase 3.2 (extended inversion must work)
6. Comparison: generated code matches expected PPT structure
```

---

## Risk Assessment

[Potential risks and mitigation strategies]

**Example**:
```
Risk: PPT representation too complex
  Likelihood: Medium
  Impact: High (blocks all Phase 3 later epics)
  Mitigation: Early prototype + design review + discussion with architect

Risk: Visitor pattern overhead
  Likelihood: Low
  Impact: Low (< 1% performance)
  Mitigation: Benchmark before/after, optimize if needed

Risk: Cyclic reference handling
  Likelihood: Low
  Impact: Medium (infinite loops)
  Mitigation: Add cycle detection, defensive coding, tests
```

---

## Success Criteria Checklist

Use this for tracking during implementation:

```
Acceptance Criteria:
- [ ] Criterion 1: [specific test or verification]
- [ ] Criterion 2: [specific test or verification]
- [ ] Criterion 3: [specific test or verification]
- [ ] Criterion 4: [specific test or verification]
- [ ] Criterion 5: [specific test or verification]

Code Quality:
- [ ] All tests passing (ProcessTreeTest.java: 12/12)
- [ ] Code coverage >= 85%
- [ ] No compiler warnings
- [ ] Follows Java 24 conventions
- [ ] Comments explain algorithm
- [ ] References synthesis doc and paper

Testing:
- [ ] Unit tests comprehensive
- [ ] Edge cases covered
- [ ] Property tests passing
- [ ] Integration tests working

Review:
- [ ] Code review requested
- [ ] Code review approved
- [ ] Comments addressed
- [ ] Ready to merge

Documentation:
- [ ] CONTINUATION.md updated
- [ ] Learnings captured (if applicable)
- [ ] Next bead dependencies clear
- [ ] Metrics recorded
```

---

## Execution Timeline

[Estimate work breakdown if known]

**Example**:
```
Day 1: Design class hierarchy and interfaces
  - ProcessTreeNode abstract base
  - Terminal, Branch subclasses
  - Visitor interface
  - Write test stubs

Day 2-3: Implement core functionality
  - Node implementations
  - Builder pattern
  - Visitor implementation
  - Test fixes and refinement

Day 4: Performance + quality
  - Benchmark
  - Code review
  - Performance optimization if needed
  - Final tests
```

---

## Communication

**Questions/Blockers**: Where to ask for help
- Code questions: Ask architect (java-architect-planner)
- Implementation help: Ask developer (java-developer)
- Design critique: Ask code reviewer (code-review-expert)
- Stuck: Ask debugger (java-debugger)

**Status Updates**: Where to report
- Daily: Update CONTINUATION.md
- Blocker: Note in execution_state.json blockers field
- Complete: Mark `bd close <id>`

---

## Related Documentation

**See Also**:
- PHASES.md: Overall phase structure
- METHODOLOGY.md: Engineering discipline + quality gates
- KNOWLEDGE_INDEX.md: How to find information
- AGENT_INSTRUCTIONS.md: How to spawn help

---

## Example Completed Bead

[Real example from Phase 3 or 4]

**This section populated during project execution**

---

**Template Version**: 1.0
**Last Updated**: 2026-01-23
**For**: Morel Predicate Inversion Phase 3-4

---

## How to Use This Template

1. **Copy structure** into bead description
2. **Fill in all sections** with project-specific content
3. **Be specific** - vague acceptance criteria cause confusion
4. **Link to knowledge** - cite synthesis documents and papers
5. **Make it testable** - each criterion must be verifiable
6. **Document trade-offs** - explain design decisions

**Pro Tips**:
- Link to synthesis doc sections, not just names
- Include specific test names in acceptance criteria
- Add examples in description section
- Reference line numbers for code examples
- Be specific about edge cases

**Anti-Patterns** (avoid):
- Vague criteria like "it works" or "implement the thing"
- Missing knowledge base references
- Unclear dependencies
- No test strategy
- Unmeasurable success metrics
