# Phase 6.2: Documentation Structure Plan

**Date Created**: 2026-01-24
**Author**: strategic-planner
**Bead**: morel-wgv
**Status**: READY FOR EXECUTION
**Phase**: Phase 6.2 - Documentation

---

## Executive Summary

This document provides comprehensive templates, guidelines, and checklists for all production documentation required for the transitive closure predicate inversion feature (FM-02). The documentation targets three audiences: SML developers using Morel, compiler maintainers extending the feature, and project maintainers managing releases.

**Estimated Total Effort**: 12-18 hours
**Parallel Execution**: User Guide and Developer Guide can be written concurrently
**Quality Gate**: All documentation must pass review checklist before Phase 6.5 merge

---

## 1. User Guide

**Target Audience**: Technical users, SML developers writing Morel queries
**Location**: `docs/transitive-closure.md` (new file)
**Estimated Effort**: 3-4 hours
**Responsible**: java-developer with strategic-planner review

### 1.1 Section Outline

```markdown
# Transitive Closure in Morel

## Overview
- What is transitive closure?
- When Morel automatically optimizes recursive queries
- Benefits: declarative syntax, automatic optimization

## Quick Start
- Simple example with edge/path
- Expected output
- Performance comparison (naive vs optimized)

## Pattern Recognition
- Supported patterns
  - Base case: direct relation (e.g., edge(x,y))
  - Recursive case: orelse with exists
  - Variable binding requirements
- Unsupported patterns (graceful degradation)
  - General disjunction (non-TC orelse)
  - Mutual recursion
  - Complex join conditions

## Examples
### Example 1: Graph Reachability
### Example 2: Hierarchical Data
### Example 3: Shortest Path (future)

## Performance Expectations
- Graph size vs execution time
- Memory considerations
- When to use vs alternatives

## Troubleshooting
- Pattern not recognized
- Unexpected results
- Performance issues

## Related Topics
- Link to query.md
- Link to reference.md
- Link to Relational.iterate
```

### 1.2 Example Structure Template

Each example should follow this structure:

```markdown
### Example: [Title]

**Use Case**: [1-2 sentence description of real-world scenario]

**Data Setup**:
```sml
val edges = [(1,2), (2,3), (3,4)];
```

**Query**:
```sml
fun path (x, y) = edge (x, y) orelse
  (exists z where edge (x, z) andalso path (z, y));

from p where path p;
```

**Expected Output**:
```
(1, 2)
(1, 3)
(1, 4)
(2, 3)
(2, 4)
(3, 4)

val it : (int * int) list
```

**Explanation**: [Brief explanation of how the optimization works]

**Performance Note**: [Any relevant performance characteristics]
```

### 1.3 Writing Guidelines (User Guide)

1. **Accessibility**: Assume reader knows SML basics but not Morel internals
2. **Practical Focus**: Lead with examples, then explain theory
3. **Progressive Complexity**: Start simple, build to complex
4. **Consistency**: Match style of existing `docs/query.md`
5. **Completeness**: Cover all supported patterns with examples
6. **Honesty**: Clearly state what is NOT supported

### 1.4 User Guide Content Checklist

- [ ] Overview explains transitive closure concept (no jargon)
- [ ] Quick start example works copy-paste in Morel shell
- [ ] All supported patterns documented with examples
- [ ] Unsupported patterns clearly listed with rationale
- [ ] At least 3 complete examples with data, query, output
- [ ] Performance expectations stated (graph size, memory)
- [ ] Troubleshooting section covers common issues
- [ ] Cross-references to related documentation
- [ ] No broken internal links
- [ ] Code examples validated against current implementation

---

## 2. Developer Integration Guide

**Target Audience**: Compiler maintainers, contributors extending the feature
**Location**: `docs/developer/predicate-inversion.md` (new file)
**Estimated Effort**: 4-5 hours
**Responsible**: java-developer with code-review-expert review

### 2.1 Section Outline

```markdown
# Predicate Inversion: Developer Guide

## Architecture Overview
- High-level flow diagram
- Component responsibilities
- Integration points with Morel compiler

## Key Classes

### PredicateInverter
- Purpose and responsibilities
- Public API
- Pattern detection logic
- Cardinality boundary handling

### InversionResult
- Structure and fields
- When inversion succeeds/fails
- Handling duplicates and supersets

### Extents Integration
- Where PredicateInverter is called
- How results are incorporated
- Error handling

## Data Flow

### Compilation Pipeline
1. Parser -> AST
2. Type resolution
3. Core language conversion
4. **Extent analysis (PredicateInverter)**
5. Code generation

### Inversion Flow
1. Pattern recognition
2. Base case extraction
3. Recursive case analysis
4. Relational.iterate generation

## Extending the Feature

### Adding New Patterns
- Step-by-step guide
- Test-first approach
- Pattern detection implementation
- Code generation changes

### Phase 6a: General Disjunction Union
- Design notes
- Implementation approach
- Expected complexity

### Phase 6b: Mutual Recursion
- Design notes
- Implementation approach
- Expected complexity

## Testing Strategy
- Unit tests: PredicateInverterTest.java
- Integration tests: transitive-closure.smli
- Regression tests: such-that.smli

## Debugging
- Debug logging (how to enable)
- Common issues and solutions
- Trace output interpretation

## Performance Considerations
- Compilation overhead
- Runtime characteristics
- Memory allocation patterns
```

### 2.2 Class Documentation Template

For each key class:

```markdown
### [ClassName]

**Location**: `src/main/java/net/hydromatic/morel/compile/[ClassName].java`

**Purpose**: [1-2 sentence summary]

**Key Responsibilities**:
- [Responsibility 1]
- [Responsibility 2]
- [Responsibility 3]

**Public API**:

| Method | Description | Parameters | Returns |
|--------|-------------|------------|---------|
| `invert()` | Attempts to invert a predicate | `apply`: Core.Apply, `goalPats`: Set<NamedPat> | `Optional<InversionResult>` |

**Algorithm Overview**:
```
1. Check if apply matches transitive closure pattern
2. Extract base case (direct relation call)
3. Extract recursive case (orelse with exists)
4. Generate Relational.iterate expression
5. Return InversionResult with metadata
```

**Key Decision Points**:
- Line X: [Decision and rationale]
- Line Y: [Decision and rationale]

**Extension Points**:
- [Where to add new pattern recognition]
- [Where to add new code generation]

**Related Classes**:
- [RelatedClass1]: [Relationship]
- [RelatedClass2]: [Relationship]
```

### 2.3 Writing Guidelines (Developer Guide)

1. **Precision**: Use exact class names, method signatures, line numbers
2. **Architecture First**: Start with big picture, then drill down
3. **Code References**: Link to specific files and line numbers
4. **Extensibility Focus**: Emphasize how to add new patterns
5. **TDD Emphasis**: Test-first approach throughout
6. **Diagrams**: Include ASCII diagrams for complex flows

### 2.4 Developer Guide Content Checklist

- [ ] Architecture diagram (ASCII or Mermaid) included
- [ ] All key classes documented with public API
- [ ] Data flow clearly explained with numbered steps
- [ ] Extension guide includes concrete example
- [ ] Phase 6a-6d design notes included for future work
- [ ] Testing strategy documented with file locations
- [ ] Debug logging explained with examples
- [ ] Performance considerations documented
- [ ] No references to removed/changed code
- [ ] Code examples compile and run

---

## 3. Performance Benchmarking Documentation

**Target Audience**: Developers validating performance, users understanding limits
**Location**: `docs/benchmarks/transitive-closure-performance.md` (new file)
**Estimated Effort**: 2-3 hours
**Responsible**: java-developer

### 3.1 Section Outline

```markdown
# Transitive Closure Performance

## How to Run Benchmarks

### Prerequisites
- JDK 17+
- Maven 3.8+
- Sufficient heap memory (recommend 4GB+)

### Running Benchmark Suite
```bash
./mvnw test -Dtest=TransitiveClosureBenchmark
```

### JMH Benchmarks (Optional)
```bash
./mvnw package -Pbenchmarks
java -jar target/benchmarks.jar TransitiveClosureBenchmark
```

## Baseline Metrics

### Test Environment
- JDK: [version]
- OS: [macOS/Linux/Windows]
- CPU: [specs]
- Memory: [heap size]

### Results Table

| Graph Size | Edges | TC Size | Compilation (ms) | Execution (ms) | Memory (MB) |
|------------|-------|---------|------------------|----------------|-------------|
| Small      | 10    | 45      | < 50             | < 10           | < 10        |
| Medium     | 100   | 4,950   | < 100            | < 100          | < 50        |
| Large      | 1,000 | 499,500 | < 200            | < 1,000        | < 500       |

## Performance Characteristics

### Compilation Overhead
- Pattern detection: O(1) per predicate
- Code generation: O(1) per pattern
- Total overhead: negligible for typical queries

### Execution Characteristics
- Fixpoint iteration: O(|TC|) iterations
- Per-iteration cost: O(|E|) edge comparisons
- Total: O(|E| * |TC|) worst case

### Memory Profile
- Base set: O(|E|)
- Working set: O(|TC| / iterations)
- Peak: O(|TC|)

## Optimization Recommendations

### For Small Graphs (< 100 edges)
- Optimization benefit minimal
- Overhead acceptable

### For Medium Graphs (100-1000 edges)
- Significant benefit
- Consider indexing for repeated queries

### For Large Graphs (> 1000 edges)
- Essential for reasonable performance
- Monitor memory usage
- Consider batch processing

## Known Limitations

### Memory Constraints
- Full transitive closure stored in memory
- Not suitable for graphs with |TC| > 10^6

### Performance Degradation
- Dense graphs: higher memory, more iterations
- Disconnected components: efficient
- Cyclic graphs: handled correctly, no infinite loops
```

### 3.2 Benchmark Template

```markdown
### Benchmark: [Name]

**Graph Configuration**:
- Nodes: [N]
- Edges: [E]
- Structure: [linear chain / random / dense / sparse]
- Expected TC size: [calculated]

**Test Code**:
```java
@Test
public void benchmark[Name]() {
    var edges = generateGraph([params]);
    var startTime = System.nanoTime();
    var result = runTransitiveClosure(edges);
    var elapsed = (System.nanoTime() - startTime) / 1_000_000;

    assertThat(result.size(), is([expected]));
    assertThat(elapsed, lessThan([threshold]));
}
```

**Results**:
| Run | Compilation (ms) | Execution (ms) | TC Size | Memory (MB) |
|-----|------------------|----------------|---------|-------------|
| 1   |                  |                |         |             |
| 2   |                  |                |         |             |
| 3   |                  |                |         |             |
| Avg |                  |                |         |             |

**Analysis**: [Observations about results]
```

### 3.3 Performance Documentation Checklist

- [ ] Benchmark running instructions complete
- [ ] Prerequisites clearly stated
- [ ] Baseline metrics table populated
- [ ] Graph size categories defined
- [ ] Big-O complexity analysis included
- [ ] Memory profile documented
- [ ] Optimization recommendations provided
- [ ] Known limitations documented
- [ ] At least 3 benchmark configurations
- [ ] Results reproducible on clean environment

---

## 4. Release Notes Template

**Target Audience**: Users, release managers
**Location**: Update to `HISTORY.md` (existing file)
**Estimated Effort**: 1-2 hours
**Responsible**: java-developer with strategic-planner review

### 4.1 Release Notes Structure

Follow existing HISTORY.md format:

```markdown
## <a href="https://github.com/hydromatic/morel/releases/tag/morel-0.X.0">0.X.0</a> / YYYY-MM-DD

Release 0.X.0 introduces automatic optimization of transitive closure
queries, significantly improving performance for graph traversal and
hierarchical data operations.

The transitive closure optimization automatically detects recursive
predicates that follow the transitive closure pattern and converts them
to efficient iterative fixpoint computations using `Relational.iterate`.
This can provide 10-100x speedup for large graphs compared to naive
recursive evaluation.

Contributors:
[List contributors]

### Features

* Automatic transitive closure optimization
  ([#217](https://github.com/hydromatic/morel/issues/217))
  - Detects recursive predicates with base case `orelse` recursive case pattern
  - Generates efficient `Relational.iterate` calls
  - Handles cycles correctly without infinite loops
  - Graceful degradation for unsupported patterns

### Bug-fixes and internal improvements

* [List any bug fixes related to this feature]
* PredicateInverter: improved pattern detection for nested exists
* Extents: better integration with cardinality analysis

### Build and tests

* Add transitive-closure.smli test suite (24 tests)
* Add Phase5aValidationTest.java for comprehensive validation
* Performance benchmarks for graph sizes 10, 100, 1000

### Component upgrades

* [List any dependencies changed]

### Site and documentation

* Add transitive closure user guide (docs/transitive-closure.md)
* Add developer guide for predicate inversion
* Add performance benchmarking documentation
* Release 0.X.0
  ([#XXX](https://github.com/hydromatic/morel/issues/XXX))
```

### 4.2 Release Notes Content Requirements

1. **Summary Paragraph**: 2-3 sentences explaining the feature benefit
2. **Feature List**: Bullet points with issue references
3. **Bug Fixes**: Any related fixes with issue references
4. **Tests**: Mention test coverage additions
5. **Documentation**: Link to new docs
6. **Contributors**: Credit all contributors

### 4.3 Release Notes Checklist

- [ ] Summary explains user-facing benefit
- [ ] All features have GitHub issue references
- [ ] Bug fixes listed with issue references
- [ ] Test additions documented
- [ ] Documentation additions listed
- [ ] Contributors credited
- [ ] Format matches existing HISTORY.md entries
- [ ] No sensitive information (API keys, internal paths)
- [ ] Version number placeholder marked clearly

---

## 5. Changelog Structure

**Location**: Commit messages + HISTORY.md integration
**Estimated Effort**: 30 minutes (template creation)

### 5.1 Commit-to-Feature Mapping

| Commit Hash | Date | Category | Description | Feature |
|-------------|------|----------|-------------|---------|
| b68b5e86 | 2026-01-XX | feature | Implement orelse handler for TC | #217 |
| [previous] | 2026-01-XX | feature | PredicateInverter base implementation | #217 |
| [previous] | 2026-01-XX | test | Add Phase5aValidationTest | #217 |
| [previous] | 2026-01-XX | refactor | Extents integration cleanup | #217 |

### 5.2 Issue Tracking References

| Issue | Title | Status | Related Commits |
|-------|-------|--------|-----------------|
| #217 | Predicate inversion for transitive closure | In Progress | b68b5e86, ... |

### 5.3 Change Categories

Following existing HISTORY.md conventions:

| Category | Section | Description |
|----------|---------|-------------|
| feature | Features | New user-facing functionality |
| enhancement | Features | Improvement to existing feature |
| bugfix | Bug-fixes and internal improvements | Fix for incorrect behavior |
| refactor | Bug-fixes and internal improvements | Code restructuring |
| test | Build and tests | Test additions/changes |
| docs | Site and documentation | Documentation changes |
| build | Build and tests | Build system changes |
| deps | Component upgrades | Dependency updates |

---

## 6. Integration with Existing Docs

### 6.1 Documentation Placement

| New Document | Location | Rationale |
|--------------|----------|-----------|
| User Guide | `docs/transitive-closure.md` | Alongside query.md, reference.md |
| Developer Guide | `docs/developer/predicate-inversion.md` | New developer/ subdirectory |
| Benchmarks | `docs/benchmarks/transitive-closure-performance.md` | New benchmarks/ subdirectory |

### 6.2 Cross-References Needed

#### From Existing Docs TO New Docs:

1. **docs/query.md** - Add section:
   ```markdown
   ## Recursive Queries and Transitive Closure

   Morel automatically optimizes certain recursive predicates. See the
   [Transitive Closure Guide](transitive-closure.md) for details on:
   - Supported patterns
   - Performance characteristics
   - Examples
   ```

2. **docs/reference.md** - Add to `Relational.iterate` section:
   ```markdown
   **Note**: For transitive closure queries, Morel automatically generates
   efficient `Relational.iterate` calls. See
   [Transitive Closure Guide](transitive-closure.md).
   ```

3. **README.md** - Add to Extensions section:
   ```markdown
   ### Automatic Query Optimization

   Morel automatically optimizes recursive predicates that follow the
   transitive closure pattern. See the
   [Transitive Closure Guide](docs/transitive-closure.md) for details.
   ```

#### From New Docs TO Existing Docs:

1. **transitive-closure.md** should link to:
   - `query.md` (general query syntax)
   - `reference.md` (Relational.iterate)
   - README.md (getting started)

2. **predicate-inversion.md** should link to:
   - Source files on GitHub
   - Test files
   - CLAUDE.md (development notes)

### 6.3 Navigation Updates

Update `docs/` index or README to include:

```markdown
## Documentation

### User Documentation
- [Language Reference](reference.md)
- [Query Reference](query.md)
- [Transitive Closure Guide](transitive-closure.md) **NEW**
- [How-To Guide](howto.md)

### Developer Documentation
- [Predicate Inversion](developer/predicate-inversion.md) **NEW**
- [Performance Benchmarks](benchmarks/transitive-closure-performance.md) **NEW**
```

---

## 7. Quality Assurance Checklist

### 7.1 Documentation Review Criteria

#### Accuracy Verification

- [ ] All code examples execute without error
- [ ] All code examples produce documented output
- [ ] Class names and method signatures match current code
- [ ] Line number references are accurate
- [ ] Performance claims match benchmark results
- [ ] Supported patterns list matches implementation

#### Completeness Verification

- [ ] All supported patterns documented
- [ ] All unsupported patterns documented
- [ ] All public API documented
- [ ] All error conditions documented
- [ ] Extension points for future work documented

#### Style Verification

- [ ] Matches existing Morel documentation style
- [ ] Consistent formatting throughout
- [ ] No jargon without explanation
- [ ] No broken links
- [ ] Proper Markdown formatting

#### Technical Accuracy Verification

- [ ] Algorithm descriptions match implementation
- [ ] Big-O complexity claims verified
- [ ] Memory usage claims verified
- [ ] Performance claims reproducible

### 7.2 Example Code Validation Process

For each code example in documentation:

1. **Extract**: Copy example code to temporary file
2. **Execute**: Run in Morel shell or test harness
3. **Compare**: Verify output matches documented output
4. **Document**: Record any discrepancies

Validation script template:
```bash
#!/bin/bash
# validate-docs.sh

EXAMPLES_DIR="docs/examples"
MOREL_CMD="./morel"

for example in $EXAMPLES_DIR/*.sml; do
    echo "Validating: $example"
    $MOREL_CMD < "$example" > "${example}.actual"
    diff "${example}.expected" "${example}.actual"
    if [ $? -eq 0 ]; then
        echo "  PASS"
    else
        echo "  FAIL"
    fi
done
```

### 7.3 Performance Claim Verification

For each performance claim:

| Claim | Location | Verification Method | Status |
|-------|----------|---------------------|--------|
| "< 100ms for 100 edges" | User Guide | Run benchmark | [ ] |
| "10-100x speedup" | Release Notes | Compare naive vs optimized | [ ] |
| "O(E * TC) complexity" | Developer Guide | Algorithmic analysis | [ ] |

### 7.4 Final Review Checklist

Before Phase 6.5 merge:

- [ ] User Guide reviewed by non-author
- [ ] Developer Guide reviewed by code-review-expert
- [ ] All code examples validated
- [ ] All performance claims verified
- [ ] Cross-references verified
- [ ] No TODOs remain in documentation
- [ ] Documentation spell-checked
- [ ] Documentation renders correctly on GitHub

---

## 8. Responsibility Matrix

| Document | Primary Author | Reviewer | Estimated Hours |
|----------|----------------|----------|-----------------|
| User Guide | java-developer | strategic-planner | 3-4h |
| Developer Guide | java-developer | code-review-expert | 4-5h |
| Performance Docs | java-developer | - | 2-3h |
| Release Notes | java-developer | strategic-planner | 1-2h |
| Changelog | java-developer | - | 0.5h |
| Cross-References | strategic-planner | - | 1h |
| QA Validation | java-developer | code-review-expert | 1-2h |
| **Total** | | | **12-18h** |

---

## 9. Timeline for Phase 6.2 Execution

| Day | Activity | Deliverable | Dependencies |
|-----|----------|-------------|--------------|
| 0.5 | User Guide draft | transitive-closure.md v1 | Phase 6.1 complete |
| 1 | Developer Guide draft | predicate-inversion.md v1 | Phase 6.1 complete |
| 1.5 | Performance docs | transitive-closure-performance.md | Benchmarks run |
| 1.5 | Release Notes draft | HISTORY.md update | Feature description |
| 2 | Code example validation | All examples verified | Drafts complete |
| 2.5 | Review cycle | Comments addressed | Drafts complete |
| 2.5 | Cross-references | Links verified | All docs complete |
| 3 | Final QA | All checklists passed | Reviews complete |

**Critical Path**: User Guide + Developer Guide (parallel) -> Review -> Final QA

---

## 10. Handoff to Phase 6.3

After Phase 6.2 documentation is complete:

### Deliverables to Archive
- `/docs/transitive-closure.md`
- `/docs/developer/predicate-inversion.md`
- `/docs/benchmarks/transitive-closure-performance.md`
- Updated `HISTORY.md`
- Updated cross-references

### Quality Gate Evidence
- All checklists in this plan marked complete
- Review comments resolved
- Code examples validated

### Handoff Note
```
## Handoff: Phase 6.3 (Test Expansion)

**Status**: Phase 6.2 Documentation COMPLETE
**Evidence**: PHASE-6.2-DOCUMENTATION-PLAN.md checklists passed

### Completed Artifacts
- docs/transitive-closure.md (user guide)
- docs/developer/predicate-inversion.md (developer guide)
- docs/benchmarks/transitive-closure-performance.md (performance)
- HISTORY.md (release notes draft)

### Quality Confirmation
- All code examples validated
- Performance claims verified
- Cross-references verified
- Reviews passed

### Next: Phase 6.3 Test Expansion
- Expand test suite from 24 to 30-40 tests
- Add backward compatibility tests
- Add unsupported pattern tests
- Add edge case tests
```

---

## Appendix A: Documentation Style Guide

### Code Block Formatting

Use Morel/SML for code:
````markdown
```sml
fun path (x, y) = edge (x, y) orelse
  (exists z where edge (x, z) andalso path (z, y));
```
````

Use output formatting for results:
````markdown
```
(1, 2)
(2, 3)
(1, 3)

val it : (int * int) list
```
````

### Inline Code

- Class names: `PredicateInverter`
- Method names: `invert()`
- File names: `such-that.smli`
- Keywords: `orelse`, `andalso`

### Headings

- Level 1 (#): Document title only
- Level 2 (##): Major sections
- Level 3 (###): Subsections
- Level 4 (####): Detail sections

### Links

- Internal: `[Query Reference](query.md)`
- External: `[GitHub Issue](https://github.com/...)`
- Anchors: `[See Section](#section-name)`

---

## Appendix B: File Templates

### User Guide Template File

Create `docs/transitive-closure.md`:

```markdown
<!--
Licensed to Julian Hyde under one or more contributor license
agreements. See the NOTICE file distributed with this work
for additional information regarding copyright ownership.
-->

# Transitive Closure in Morel

[Content following Section 1 outline]
```

### Developer Guide Template File

Create `docs/developer/predicate-inversion.md`:

```markdown
<!--
Licensed to Julian Hyde under one or more contributor license
agreements. See the NOTICE file distributed with this work
for additional information regarding copyright ownership.
-->

# Predicate Inversion: Developer Guide

[Content following Section 2 outline]
```

---

**Document Version**: 1.0
**Last Updated**: 2026-01-24
**Status**: READY FOR EXECUTION
