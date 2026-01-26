# Phase 5b: Pattern Coverage Specification

**Bead**: morel-c2z
**Status**: Complete
**Date**: 2026-01-24
**Author**: strategic-planner agent

---

## Executive Summary

This document specifies exactly which recursive patterns PredicateInverter will optimize via `Relational.iterate`. It provides clear boundaries for what patterns are supported vs. unsupported, a decision tree for pattern detection, and test cases for Phase 5c validation.

---

## 1. Supported Transitive Closure Patterns

### 1.1 Primary Pattern: Simple Transitive Closure (CONFIRMED TARGET)

```sml
val edges = [(1, 2), (2, 3)]
fun edge(x, y) = (x, y) elem edges
fun path(x, y) =
  edge(x, y) orelse                           (* base case *)
  (exists z where path(x, z) andalso edge(z, y))  (* recursive case *)

from p where path p;
(* Expected: [(1,2), (2,3), (1,3)] *)
```

**Pattern Structure**:
- **Base case**: Direct relation lookup (invertible to FINITE generator)
- **Recursive case**: `exists` expression containing:
  - Recursive call to same function
  - Step relation (same as base case)
  - Join via `andalso`
- **Join variable**: `z` appears in both recursive call result and step input

**Detection Criteria**:
1. Function body is `baseCase orelse recursiveCase`
2. Base case inverts to FINITE cardinality
3. Recursive case contains exists wrapper
4. Recursive call is to the same function
5. Join variable identifiable

**Generated Code Pattern**:
```sml
Relational.iterate edges
  (fn (oldPaths, newPaths) =>
    from (x, z) in newPaths,
         (z2, y) in edges
      where z = z2
      yield (x, y))
```

---

### 1.2 Variant: Record-Based Elements

```sml
val edges = [{x = 1, y = 2}, {x = 2, y = 3}]
fun edge p = p elem edges
fun path(x, y) =
  edge {x, y} orelse
  (exists z where path(x, z) andalso edge {x = z, y = y})
```

**Status**: SUPPORTED (same detection logic, different element type)

**Detection**: Same as 1.1, record type handled by existing type system

---

### 1.3 Variant: Left-Linear Recursion

```sml
fun path(x, y) =
  edge(x, y) orelse
  (exists z where edge(x, z) andalso path(z, y))  (* edge first, then recurse *)
```

**Status**: SUPPORTED

**Note**: Step function scans base relation then joins with recursive result.

---

### 1.4 Variant: Right-Linear Recursion

```sml
fun path(x, y) =
  edge(x, y) orelse
  (exists z where path(x, z) andalso edge(z, y))  (* recurse first, then edge *)
```

**Status**: SUPPORTED (primary pattern)

---

### 1.5 Variant: Multiple Base Cases

```sml
fun connected(x, y) =
  direct(x, y) orelse           (* base case 1 *)
  indirect(x, y) orelse         (* base case 2 *)
  (exists z where connected(x, z) andalso direct(z, y))
```

**Status**: PARTIALLY SUPPORTED

**Behavior**: First orelse branch that successfully inverts becomes base case. Subsequent base cases may be treated as part of union.

---

### 1.6 Variant: Nested Orelse in Base

```sml
fun path(x, y) =
  (edge1(x, y) orelse edge2(x, y)) orelse  (* combined base *)
  (exists z where path(x, z) andalso (edge1(z, y) orelse edge2(z, y)))
```

**Status**: SUPPORTED if combined base inverts to FINITE

---

## 2. Unsupported Patterns (Explicit Boundaries)

### 2.1 Non-Linear Recursion

```sml
fun tree_path(x) =
  leaf(x) orelse
  (tree_path(left(x)) orelse tree_path(right(x)))  (* BOTH branches recursive *)
```

**Status**: UNSUPPORTED

**Reason**: Cannot build single step function. Would require tracking multiple recursive calls. Relational.iterate assumes single fixpoint iteration.

**Detection**: Check if right branch of orelse contains multiple recursive calls.

---

### 2.2 Mutual Recursion

```sml
fun even(n) = n = 0 orelse odd(n - 1)
and odd(n) = n = 1 orelse even(n - 1)
```

**Status**: UNSUPPORTED

**Reason**: Cross-function recursion not supported by single Relational.iterate. Would require coordinated fixpoint between multiple relations.

**Detection**: Active function set would contain different function than current.

---

### 2.3 Non-Orelse Structure

```sml
fun path(x, y) = if edge(x, y) then true else path_step(x, y)
```

**Status**: UNSUPPORTED

**Reason**: Pattern doesn't match orelse detection (line 507 checks `isCallTo(BuiltIn.Z_ORELSE)`).

**Detection**: Falls through at line 507 check.

---

### 2.4 Infinite Base Case

```sml
fun path(x, y) =
  unknownRelation(x, y) orelse  (* cannot invert - no known extent *)
  (exists z where path(x, z) andalso unknownRelation(z, y))
```

**Status**: UNSUPPORTED

**Reason**: Base case inversion returns INFINITE cardinality. Cannot materialize infinite set for Relational.iterate.

**Detection**: Line 535-538 rejects when `baseCaseResult.generator.cardinality == INFINITE`.

**Behavior**: Returns null from tryInvertTransitiveClosure, falls through to default handling.

---

### 2.5 Missing Exists Wrapper

```sml
fun path(x, y) =
  edge(x, y) orelse
  path(x, z) andalso edge(z, y)  (* z is FREE/UNBOUND! *)
```

**Status**: UNSUPPORTED (and invalid SML)

**Reason**: Variable `z` has no scope - would fail type checking before reaching predicate inversion.

**Detection**: Type system rejects before compilation.

---

### 2.6 Complex Arithmetic in Join

```sml
fun path(x, y) =
  edge(x, y) orelse
  (exists z where path(x, z) andalso edge(z + 1, y))  (* z + 1 in join *)
```

**Status**: PARTIALLY SUPPORTED

**Behavior**: Extra constraint `z + 1` pushed to runtime filter. Step function may generate incorrect joins.

**Detection**: identifyJoinVariable may fail to find clean join.

---

### 2.7 First-Class Function Recursion

```sml
fun fix f x = f (fix f) x
val path = fix (fn self => fn (x, y) =>
  edge(x, y) orelse (exists z where self(x, z) andalso edge(z, y)))
```

**Status**: UNSUPPORTED

**Reason**: Higher-order recursion not detected by active function tracking.

**Detection**: `apply.fn.op == Op.ID` check fails - fn is not a simple identifier.

---

## 3. Pattern Detection Algorithm

### 3.1 Decision Tree

```
                    Predicate Expression
                           |
                    Is it APPLY op?
                    /             \
                  NO               YES
                   |                |
              NOT SUPPORTED    Is function Op.ID?
                               /           \
                             NO             YES
                              |              |
                    (Check other patterns)  Is function in active set?
                                           /                    \
                                         NO                      YES
                                          |                       |
                               Look up function binding    RECURSIVE CALL
                               Does binding exist?         detected
                               /              \
                             NO                YES
                              |                 |
                       NOT SUPPORTED      Is binding Core.Fn?
                                          /           \
                                        NO             YES
                                         |              |
                                  NOT SUPPORTED    Substitute arg into body
                                                   Add function to active
                                                   Recurse with body
                                                         |
                                                   Is body Z_ORELSE?
                                                   /            \
                                                 NO              YES
                                                  |               |
                                            NOT SUPPORTED    Extract left/right
                                                              branches
                                                                  |
                                                   Can left (base) be inverted?
                                                   /                      \
                                                 NO                        YES
                                                  |                         |
                                            NOT SUPPORTED         Is cardinality FINITE?
                                                                 /               \
                                                               NO                 YES
                                                                |                  |
                                                          NOT SUPPORTED    Does right contain
                                                                           recursive call?
                                                                           /           \
                                                                         NO             YES
                                                                          |              |
                                                                   NOT SUPPORTED   Can identify
                                                                                   join variable?
                                                                                   /         \
                                                                                 NO           YES
                                                                                  |            |
                                                                           NOT SUPPORTED   SUPPORTED
                                                                                              |
                                                                                     Build Relational.iterate
```

### 3.2 Code Location Mapping

| Decision Point | Code Location | Method |
|----------------|---------------|--------|
| Is APPLY? | PredicateInverter.java:151 | `invert()` |
| Is Op.ID? | PredicateInverter.java:214 | `invert()` |
| In active set? | PredicateInverter.java:220 | `invert()` |
| Lookup binding | PredicateInverter.java:269 | `invert()` |
| Is Core.Fn? | PredicateInverter.java:273 | `invert()` |
| Is Z_ORELSE? | PredicateInverter.java:189 | `invert()` |
| Base invertible? | PredicateInverter.java:520 | `tryInvertTransitiveClosure()` |
| FINITE cardinality? | PredicateInverter.java:535 | `tryInvertTransitiveClosure()` |
| Build iterate | PredicateInverter.java:569-577 | `tryInvertTransitiveClosure()` |

---

## 4. Success Criteria by Pattern

| Pattern | Detection | Generation | Execution | Notes |
|---------|-----------|------------|-----------|-------|
| Simple TC (pair) | orelse detected | Rel.iterate built | Fixpoint computed | Primary target |
| Record elements | Type handled | Same as pair | Same | Type system handles |
| Left-linear | Same structure | Step fn order | Same | Edge-then-recurse |
| Right-linear | Same structure | Step fn order | Same | Recurse-then-edge |
| Multiple base | First orelse wins | Union base | Same | May miss some |
| Non-linear | REJECT | N/A | N/A | Multiple recursion |
| Mutual recursion | REJECT | N/A | N/A | Cross-function |
| Infinite base | REJECT at line 535 | N/A | N/A | Cannot materialize |
| No exists | Type error | N/A | N/A | Invalid SML |

---

## 5. Edge Cases

### 5.1 Empty Base Case

```sml
val edges = []
fun edge(x, y) = (x, y) elem edges
fun path(x, y) = edge(x, y) orelse (exists z where path(x, z) andalso edge(z, y))
from p where path p;
(* Expected: [] *)
```

**Behavior**: `Relational.iterate []` returns `[]` immediately.

---

### 5.2 Single Element (No Transitive Paths)

```sml
val edges = [(1, 2)]
from p where path p;
(* Expected: [(1, 2)] *)
```

**Behavior**: Base case only, no new paths generated by step function.

---

### 5.3 Cyclic Graph

```sml
val edges = [(1, 2), (2, 1)]
from p where path p;
(* Expected: [(1, 2), (2, 1), (1, 1), (2, 2)] *)
```

**Behavior**: `Relational.iterate` handles cycles via fixpoint. Duplicate elimination prevents infinite loops.

---

### 5.4 Deep Transitive Closure

```sml
val edges = [(1, 2), (2, 3), (3, 4), (4, 5), (5, 6)]
from p where path p;
(* Expected: 15 pairs (n*(n-1)/2 for chain of n) *)
```

**Behavior**: O(n) iterations, O(n^2) output size. Performance scales quadratically.

---

### 5.5 Self-Loops

```sml
val edges = [(1, 1), (1, 2)]
from p where path p;
(* Expected: [(1, 1), (1, 2)] - self-loop produces itself *)
```

**Behavior**: Self-loop handled naturally, no special case needed.

---

## 6. Test Cases for Phase 5c

### 6.1 Correctness Tests (Minimum 6)

| ID | Test Case | Input | Expected Output | Pattern |
|----|-----------|-------|-----------------|---------|
| TC1 | Simple TC | edges=[(1,2),(2,3)] | [(1,2),(2,3),(1,3)] | Primary |
| TC2 | Empty base | edges=[] | [] | Edge case |
| TC3 | Single edge | edges=[(1,2)] | [(1,2)] | Edge case |
| TC4 | Cyclic graph | edges=[(1,2),(2,1)] | [(1,2),(2,1),(1,1),(2,2)] | Edge case |
| TC5 | Long chain | edges=[(1,2)..(4,5)] | 10 pairs | Performance |
| TC6 | Record-based | edges=[{x=1,y=2}...] | Same as TC1 | Variant |

### 6.2 Rejection Tests

| ID | Test Case | Pattern | Expected Behavior |
|----|-----------|---------|-------------------|
| TR1 | Non-linear recursion | 2.1 | Falls through, uses default handling |
| TR2 | Infinite base | 2.4 | Returns null, pattern not matched |
| TR3 | No orelse | 2.3 | Not detected as TC pattern |

### 6.3 Performance Tests

| ID | Test Case | Input Size | Expected |
|----|-----------|------------|----------|
| TP1 | Chain depth 10 | 10 edges | Completes <1s |
| TP2 | Dense graph | 20 edges | Completes <5s |
| TP3 | Very deep | 50 edges | Completes <30s |

---

## 7. Implementation Verification Checklist

For Phase 5d to validate implementation against this specification:

- [ ] Simple transitive closure produces correct output
- [ ] Empty base case returns empty result
- [ ] Single edge returns only that edge
- [ ] Cyclic graph reaches fixpoint without infinite loop
- [ ] Record-based edges work identically to tuple-based
- [ ] Non-linear recursion does NOT trigger TC optimization
- [ ] Infinite base case does NOT trigger TC optimization
- [ ] Step function correctly identifies join variable
- [ ] Generated Relational.iterate has correct type
- [ ] Performance scales reasonably with input size

---

## 8. Relationship to Other Documents

| Document | Relationship |
|----------|--------------|
| PHASE-5A-SUCCESS-CRITERIA.md | Defines GO/NO-GO for environment scoping |
| PHASE-5C-TEST-PLAN.md | Uses test cases from Section 6 |
| PHASE-5D-SCOPE-ANALYSIS.md | Validates implementation against this spec |
| INTEGRATION-POINT-ANALYSIS.md | Shows where detection hooks into Extents |
| TYPE-SYSTEM-ANALYSIS.md | Covers type handling for variants |

---

## 9. Key Files Reference

| File | Relevance |
|------|-----------|
| `PredicateInverter.java:500-591` | `tryInvertTransitiveClosure()` implementation |
| `PredicateInverter.java:1632-1796` | Step function generation |
| `ProcessTreeBuilder.java` | PPT construction for pattern analysis |
| `VarEnvironment.java` | Variable binding tracking |
| `Extents.java:457-480` | orelse handler integration |
| `such-that.smli:739-744` | Primary test case |

---

## 10. Conclusion

This specification provides clear boundaries for transitive closure pattern optimization:

**In Scope**:
- Simple 2-tuple transitive closure
- Record-based elements
- Left and right linear recursion
- Single-level orelse with invertible base case

**Out of Scope**:
- Non-linear recursion
- Mutual recursion
- Complex join conditions
- Infinite base cases

The decision tree in Section 3 provides deterministic pattern detection, and the test cases in Section 6 enable comprehensive validation in Phase 5c.

---

**Next Steps**:
1. Phase 5c: Create comprehensive test plan using Section 6 test cases
2. Phase 5d: Validate implementation produces expected outputs
3. Iterate if any test cases fail
