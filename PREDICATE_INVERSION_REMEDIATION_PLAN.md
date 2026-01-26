# Predicate Inversion Remediation Plan

## Executive Summary

This plan addresses the four critical issues identified in the predicate inversion audit:
1. **Mixing Domains (Scott's Criticism)**: Functions inlined during inversion
2. **Non-Recursive Case Not Solid**: Conjunction handling lacks mode analysis
3. **ProcessTreeBuilder Dead Code**: PPT construction unused
4. **Transitive Closure Limited**: Fails if base case fails

### Design Goal (Julian/Scott Email)

Map Datalog-style programs into relational algebra with iteration:

**Input:**
```sml
fun path (x, y) = edge (x, y) orelse (exists z where path (x, z) andalso edge (z, y));
from p where path p;
```

**Expected Output:**
```sml
Relational.iterate edges
  (fn (old, new) =>
    from (x, z) in new, (z2, y) in edges
    where z = z2
    yield (x, y))
```

**Success Criterion:** `from p where path p` returns `[(1,2),(2,3),(1,3)]`

---

## Phase A: Foundation Cleanup

**Priority:** Immediate
**Complexity:** XS (deletions only)
**Risk:** Low (safe rollback point)

### Rationale

ProcessTreeBuilder (462 lines) constructs Perfect Process Trees but `tryInvertWithProcessTree()` always returns `Optional.empty()`. This is dead code that adds cognitive load without benefit. Scott's principle: "Decompose as without recursion first, then add recursion later" - we should simplify before adding complexity.

### Tasks

#### A.1: Remove ProcessTreeBuilder.java
- **File:** `src/main/java/net/hydromatic/morel/compile/ProcessTreeBuilder.java`
- **Action:** Delete file
- **Tests:** All existing tests must pass

#### A.2: Remove ProcessTreeNode.java
- **File:** `src/main/java/net/hydromatic/morel/compile/ProcessTreeNode.java`
- **Action:** Delete file (if exists; may be inner classes in ProcessTreeBuilder)
- **Tests:** All existing tests must pass

#### A.3: Remove tryInvertWithProcessTree() method
- **File:** `src/main/java/net/hydromatic/morel/compile/PredicateInverter.java`
- **Lines:** 602-634
- **Action:** Remove method and all callers
- **Tests:** All existing tests must pass

#### A.4: Clean up VarEnvironment
- **File:** `src/main/java/net/hydromatic/morel/compile/VarEnvironment.java`
- **Action:** Review and remove PPT-specific methods if unused elsewhere
- **Tests:** All existing tests must pass

#### A.5: Update PredicateInverterTest
- **File:** `src/test/java/net/hydromatic/morel/compile/PredicateInverterTest.java`
- **Action:** Remove tests that reference ProcessTreeBuilder (lines 679-1166 approximately)
- **Tests:** Remaining tests must pass

### Test Criteria
- `./mvnw test -Dtest=PredicateInverterTest` passes
- `./mvnw test -Dtest=ScriptTest` passes
- No references to ProcessTreeBuilder in codebase

### Rollback Point
After Phase A, the codebase is simpler. All future phases can be rolled back to this point.

---

## Phase B: Function Resolution Architecture

**Priority:** High
**Complexity:** M
**Dependencies:** Phase A complete

### Rationale

Scott's criticism: "Edge should never be on the stack." Currently, when `PredicateInverter.invert()` encounters a user function call, it:
1. Looks up the function binding
2. Inlines the function body
3. Adds the function to `active` stack
4. Recursively inverts the substituted body

This conflates semantic analysis (what does this function compute?) with inversion (how do we generate values?). The fix is to resolve functions by reference using a registry.

### Current Problem (PredicateInverter.java lines 195-264)

```java
// Problem: inlining puts function on active stack
if (apply.fn.op == Op.ID) {
  Binding binding = env.getOpt(fnPat);
  if (binding != null && binding.exp != null) {
    Core.Fn fn = (Core.Fn) fnBody;
    Core.Exp substitutedBody = substituteIntoFn(fn, apply.arg);
    return invert(substitutedBody, goalPats, ConsList.of(fnId, active));
  }
}
```

### Tasks

#### B.1: Create FunctionRegistry class
- **New File:** `src/main/java/net/hydromatic/morel/compile/FunctionRegistry.java`
- **Responsibilities:**
  - Store analyzed function metadata
  - Track invertibility status per function
  - Cache base generators for invertible functions
- **Interface:**
  ```java
  public class FunctionRegistry {
    enum InvertibilityStatus { INVERTIBLE, NOT_INVERTIBLE, RECURSIVE }

    record FunctionInfo(
      Core.IdPat functionPat,
      InvertibilityStatus status,
      Optional<Core.Exp> baseGenerator,
      Set<Core.NamedPat> canGenerateAlone
    ) {}

    void register(Core.IdPat fnPat, Core.Fn fn);
    Optional<FunctionInfo> lookup(Core.IdPat fnPat);
    void markRecursive(Core.IdPat fnPat);
  }
  ```

#### B.2: Pre-analyze functions during compilation
- **File:** `src/main/java/net/hydromatic/morel/compile/Compiler.java` (or equivalent)
- **Action:** After type checking, analyze each function:
  - If body is `pat elem collection`, mark as INVERTIBLE with generator
  - If body contains self-reference, mark as RECURSIVE
  - Otherwise mark as NOT_INVERTIBLE
- **Integration:** Pass FunctionRegistry to PredicateInverter

#### B.3: Refactor invert() to use registry lookup
- **File:** `src/main/java/net/hydromatic/morel/compile/PredicateInverter.java`
- **Changes:**
  - Add `FunctionRegistry registry` field
  - Replace inline expansion with registry lookup
  - For INVERTIBLE functions, return cached generator
  - For RECURSIVE functions, delegate to transitive closure handler
  - For NOT_INVERTIBLE, return fallback
- **Code pattern:**
  ```java
  if (apply.fn.op == Op.ID) {
    Core.Id fnId = (Core.Id) apply.fn;
    Optional<FunctionInfo> info = registry.lookup(fnId.idPat);
    if (info.isPresent()) {
      switch (info.get().status()) {
        case INVERTIBLE:
          return result(info.get().baseGenerator().get(), ImmutableList.of());
        case RECURSIVE:
          return tryInvertTransitiveClosure(...);
        case NOT_INVERTIBLE:
          return result(generatorFor(goalPats), ImmutableList.of(apply));
      }
    }
  }
  ```

#### B.4: Integrate with Extents.java
- **File:** `src/main/java/net/hydromatic/morel/compile/Extents.java`
- **Action:** Pass FunctionRegistry to PredicateInverter.invert() calls
- **Lines affected:** ~575-604, ~729-760

### Test Criteria
- Existing passing tests still pass
- New unit tests for FunctionRegistry
- `testInvertEdgeFunction` (currently disabled) should pass after this phase

### Validation
```java
// Unit test for FunctionRegistry
@Test
void testFunctionRegistryBasic() {
  FunctionRegistry registry = new FunctionRegistry();
  // Register: fun edge(x,y) = (x,y) elem edges
  registry.register(edgePat, edgeFn);

  Optional<FunctionInfo> info = registry.lookup(edgePat);
  assertThat(info.isPresent(), is(true));
  assertThat(info.get().status(), is(INVERTIBLE));
  assertThat(info.get().baseGenerator().get(), hasToString("edges"));
}
```

---

## Phase C: Mode Analysis

**Priority:** High
**Complexity:** M
**Dependencies:** Phase B complete

### Rationale

Mode analysis determines which variables a predicate can generate given which are already bound. This is essential for conjunction handling where multiple predicates must be ordered to minimize infinite domains.

### Example

```sml
edge(x, y) andalso y > 5 andalso x < 10
```

Mode analysis:
- `edge(x, y)`: can generate {x, y} when both unbound
- `y > 5`: cannot generate y (infinite domain), can filter y
- `x < 10`: cannot generate x (infinite domain), can filter x

Optimal order:
1. Generate (x, y) from `edge`
2. Filter by `y > 5`
3. Filter by `x < 10`

### Tasks

#### C.1: Create ModeAnalyzer class
- **New File:** `src/main/java/net/hydromatic/morel/compile/ModeAnalyzer.java`
- **Interface:**
  ```java
  public class ModeAnalyzer {
    private final TypeSystem typeSystem;
    private final FunctionRegistry functionRegistry;

    /**
     * Returns variables that can be generated by this predicate
     * given the set of already-bound variables.
     */
    Set<Core.NamedPat> canGenerate(Core.Exp predicate, Set<Core.NamedPat> bound);

    /**
     * Orders predicates to maximize generation and minimize filtering.
     * Returns (orderedPredicates, generatedVars, filterPredicates).
     */
    PredicateOrdering orderPredicates(
        List<Core.Exp> predicates,
        Set<Core.NamedPat> goalPats,
        Set<Core.NamedPat> initiallyBound);
  }
  ```

#### C.2: Implement canGenerate() for built-in predicates
- **File:** ModeAnalyzer.java
- **Cases:**
  - `x elem list`: can generate {x}
  - `x > y`: can generate {} (filter only)
  - `x = y`: can generate {x} if y bound, {y} if x bound
  - `String.isPrefix p s`: can generate {p} if s bound

#### C.3: Implement canGenerate() for user functions
- **File:** ModeAnalyzer.java
- **Action:** Use FunctionRegistry to determine generatable variables
  - INVERTIBLE: return canGenerateAlone from FunctionInfo
  - RECURSIVE: depends on base case analysis
  - NOT_INVERTIBLE: return empty set

#### C.4: Refactor invertAnds() to use mode analysis
- **File:** `src/main/java/net/hydromatic/morel/compile/PredicateInverter.java`
- **Current:** Lines 1205-1351 - greedy pairing for range constraints only
- **New approach:**
  ```java
  private Result invertAnds(List<Core.Exp> predicates, List<Core.NamedPat> goalPats, ...) {
    ModeAnalyzer analyzer = new ModeAnalyzer(typeSystem, registry);
    PredicateOrdering ordering = analyzer.orderPredicates(predicates, goalPats, boundPats);

    // Build FROM expression with scans and filters in optimal order
    FromBuilder builder = core.fromBuilder(typeSystem, env);
    for (PredicatePlan plan : ordering.plans()) {
      if (plan.isGenerator()) {
        builder.scan(plan.pattern(), plan.generator());
      } else {
        builder.where(plan.filter());
      }
    }
    return result(builder.build(), ordering.remainingFilters());
  }
  ```

### Test Criteria
- New unit tests for ModeAnalyzer
- `testInvertRangeConstraintsForY` (currently disabled) should pass
- `testInvertCompositeWithExists` (currently disabled) should pass
- `such-that.smli` tests around lines 421-478 (scott.depts) should pass

---

## Phase D: Transitive Closure Completion

**Priority:** Medium
**Complexity:** L
**Dependencies:** Phase B and C complete

### Rationale

The current `tryInvertTransitiveClosure()` fails if base case inversion fails, and only handles the trivial `base orelse recursive` pattern. With FunctionRegistry (Phase B), we can resolve the base case properly. With ModeAnalyzer (Phase C), we can handle complex recursive structures.

### Current Problem (PredicateInverter.java lines 500-518)

```java
Result baseCaseResult = invert(baseCase, goalPats, ImmutableList.of());
// PROBLEM: If base case is user function, inversion may fail
if (baseCaseResult.generator.cardinality == INFINITE) {
  return null; // Gives up entirely
}
```

### Tasks

#### D.1: Integrate FunctionRegistry with tryInvertTransitiveClosure
- **File:** `src/main/java/net/hydromatic/morel/compile/PredicateInverter.java`
- **Action:** When base case is a user function, use registry lookup:
  ```java
  // Before trying to invert base case
  if (baseCase.op == Op.APPLY && baseCase.fn.op == Op.ID) {
    Core.Id fnId = (Core.Id) baseCase.fn;
    Optional<FunctionInfo> info = registry.lookup(fnId.idPat);
    if (info.isPresent() && info.get().status() == INVERTIBLE) {
      // Use cached generator instead of inverting
      baseCaseResult = result(info.get().baseGenerator(), ImmutableList.of());
    }
  }
  ```

#### D.2: Improve pattern extraction for base/recursive cases
- **File:** PredicateInverter.java
- **Current issues:**
  - Only handles direct `base orelse recursive` pattern
  - Doesn't unwrap `exists` properly
  - Join variable extraction is brittle
- **Improvements:**
  - Handle `base orelse (exists z where recursive andalso base2)`
  - Extract join variable from exists binding
  - Support multiple base predicates in recursive case

#### D.3: Fix step function generation
- **File:** PredicateInverter.java, `buildStepFunction()` (lines 1813-1977)
- **Current issues:**
  - Hardcoded variable names
  - Doesn't use mode analysis for join ordering
- **Improvements:**
  - Use extracted join variables
  - Generate proper join condition
  - Handle multi-way joins if needed

#### D.4: Enable and fix disabled tests
- **File:** `src/test/java/net/hydromatic/morel/compile/PredicateInverterTest.java`
- **Tests to enable:**
  - `testInvertEdgeFunction` (line 326)
  - `testInvertPathFunction` (line 389)
  - `testInvertCompositeWithExists` (line 260)

### Test Criteria
- All previously passing tests still pass
- `testInvertPathFunction` passes
- Integration test: `such-that.smli` line 743-744:
  ```sml
  from p where path p;
  > val it = [(1,2),(2,3),(1,3)] : (int * int) list
  ```

---

## Dependency Graph

```
Phase A: Foundation Cleanup
    |
    v
Phase B: Function Resolution Architecture
    |
    +-----> Phase C: Mode Analysis
    |              |
    v              v
Phase D: Transitive Closure Completion
```

## Risk Mitigation

| Risk | Severity | Mitigation |
|------|----------|------------|
| Regression in passing tests | HIGH | Run full test suite after each phase; rollback to Phase A if needed |
| Scope creep in FunctionRegistry | MEDIUM | Focus only on elem patterns initially |
| Scope creep in ModeAnalyzer | MEDIUM | Focus only on conjunction ordering initially |
| Inliner baseName hack interaction | LOW | Remove hack after Phase B validates |
| Integration complexity in Extents.java | MEDIUM | Incremental integration with tests at each step |

## Cleanup After Success

Once all phases complete and tests pass:
1. Remove baseName hack from Inliner.java (lines 63-100)
2. Review and simplify active stack handling in PredicateInverter
3. Consider merging VarEnvironment into a simpler structure

---

## Files Affected

| File | Phase | Action |
|------|-------|--------|
| ProcessTreeBuilder.java | A | Delete |
| ProcessTreeNode.java | A | Delete (if exists) |
| PredicateInverter.java | A, B, C, D | Modify |
| VarEnvironment.java | A | Review/Simplify |
| PredicateInverterTest.java | A, D | Modify |
| FunctionRegistry.java | B | Create |
| ModeAnalyzer.java | C | Create |
| Extents.java | B | Modify |
| Compiler.java (or equivalent) | B | Modify |
| Inliner.java | Cleanup | Remove hack |

---

## Estimated Complexity

| Phase | Size | New Code | Modified Code | Deleted Code |
|-------|------|----------|---------------|--------------|
| A | XS | 0 | ~50 lines | ~1500 lines |
| B | M | ~200 lines | ~150 lines | 0 |
| C | M | ~250 lines | ~100 lines | 0 |
| D | L | ~100 lines | ~200 lines | 0 |
| **Total** | | ~550 lines | ~500 lines | ~1500 lines |

Net: Reduced code size with improved functionality.
