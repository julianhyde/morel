# Integration Point Analysis: PredicateInverter to Compilation Pipeline

**Bead**: morel-vhd (B7 Integration Point Analysis)
**Date**: 2026-01-24
**Status**: Analysis Complete

---

## Executive Summary

The integration between PredicateInverter and Extents.java is **~50% complete**. The integration code exists in the `g3b` method but is **not being called**. The actual call path uses `g3`, which lacks PredicateInverter support. The fix is a single-line change.

---

## 1. Integration Point Verification

### 1.1 Actual Integration Hook Location

**File**: `/Users/hal.hildebrand/git/morel/src/main/java/net/hydromatic/morel/compile/Extents.java`

The integration point is in the `Extents.create()` method:

```java
// Line 155 - CURRENT (does NOT call PredicateInverter)
extent.g3(map.map, ((Core.Where) step).exp);
```

**g3b method** (lines 553-760) contains full PredicateInverter integration but is **NEVER CALLED**.

### 1.2 Environment Parameter Verification

**VERIFIED**: Environment is available at the integration point.

```java
// Extents.java lines 399-433 (Extent class)
private static class Extent {
    private final TypeSystem typeSystem;  // Line 400
    private final Environment env;         // Line 401
    private final boolean invert;          // Line 402
    final List<Core.NamedPat> goalPats;   // Line 403
    final SortedMap<Core.NamedPat, Core.Exp> boundPats;  // Line 404

    Extent(TypeSystem typeSystem, Environment env, boolean invert, ...) {
        this.typeSystem = requireNonNull(typeSystem);  // Line 427
        this.env = requireNonNull(env);                 // Line 428
        this.invert = invert;                           // Line 429
        // ...
    }
}
```

### 1.3 TypeSystem Verification

**VERIFIED**: TypeSystem is available at the integration point.

- Stored in `Extent.typeSystem` (line 400)
- Initialized via constructor (line 427)
- Passed to `PredicateInverter.invert()` at line 588

### 1.4 Call Stack Documentation

**Current (broken) call stack**:
```
SuchThatShuttle.rewrite1()
  -> Extents.create(typeSystem, initialEnv, true, ...)  [Line 234]
     -> extent.g3(map.map, filter)                       [Line 155]
        -> (NO PredicateInverter call)
```

**Intended (working) call stack**:
```
SuchThatShuttle.rewrite1()
  -> Extents.create(typeSystem, initialEnv, true, ...)  [Line 234]
     -> extent.g3b(map.map, filter)                      [Should be Line 155]
        -> PredicateInverter.invert()                    [Line 588 or 742]
           -> tryInvertTransitiveClosure()               [Line 223]
              -> buildStepFunction()                     [Line 549]
```

---

## 2. Hook Verification

### 2.1 tryInvert Flag Usage

Located in `g3b` method (lines 553-760):

```java
void g3b(Map<Core.Pat, PairList<Core.Exp, Core.Exp>> map, Core.Exp filter) {
    boolean tryInvert = false;  // Line 554 - initialized to false

    // ... switch statement ...

    case ID:  // Line 707 - User-defined function call
        tryInvert = true;  // Line 709
        break;

    case APPLY:  // Line 712 - Curried function application
        tryInvert = true;  // Line 715
        break;

    // Line 727-758: Actual PredicateInverter invocation
    if (tryInvert) {
        final Map<Core.NamedPat, PredicateInverter.Generator> generators = ...;
        final PredicateInverter.Result result =
            PredicateInverter.invert(typeSystem, env, filter, goalPats, generators);
        // ... process result ...
    }
}
```

**tryInvert triggers**:
| Line | Trigger | Description |
|------|---------|-------------|
| 564 | `FN_LITERAL` with `Core.Fn` value | User-defined function literals |
| 702 | Default built-in | Other built-in functions |
| 709 | `ID` | User-defined function calls |
| 715 | `APPLY` | Curried function applications |

### 2.2 PredicateInverter Constructor Parameters

PredicateInverter receives correct parameters when called from g3b:

```java
// g3b lines 729-743
final Map<Core.NamedPat, PredicateInverter.Generator> generators = new LinkedHashMap<>();
boundPats.forEach((pat, exp) ->
    generators.put(pat, new PredicateInverter.Generator(
        pat,
        exp,
        Generator.Cardinality.FINITE,
        ImmutableList.of(),
        ImmutableSet.of())));

final PredicateInverter.Result result =
    PredicateInverter.invert(
        typeSystem,  // Available: Extent.typeSystem
        env,         // Available: Extent.env
        filter,      // The predicate expression
        goalPats,    // Variables to generate
        generators); // Bound variable generators
```

### 2.3 Function Bindings Accessibility

**VERIFIED**: Function bindings are accessible through Environment.

```java
// PredicateInverter.java lines 257-269
Binding binding = env.getOpt(fnPat);  // Look up in environment

if (binding != null && binding.value instanceof Core.Exp) {
    Core.Exp fnBody = (Core.Exp) binding.value;
    if (fnBody.op == Op.FN) {
        Core.Fn fn = (Core.Fn) fnBody;
        // Substitute and invert
    }
}
```

### 2.4 Current Integration Status

| Component | Status | Notes |
|-----------|--------|-------|
| g3b method code | COMPLETE | Lines 553-760 |
| PredicateInverter call | IMPLEMENTED | Lines 741-743 |
| Environment passing | CORRECT | Uses Extent.env |
| TypeSystem passing | CORRECT | Uses Extent.typeSystem |
| **Method invocation** | **MISSING** | g3 called instead of g3b |

---

## 3. Cardinality Boundary Analysis

### 3.1 Cardinality Check Location

**File**: `/Users/hal.hildebrand/git/morel/src/main/java/net/hydromatic/morel/compile/PredicateInverter.java`
**Lines**: 523-526

```java
// Check if base case could be inverted to a FINITE generator.
if (baseCaseResult.generator.cardinality
    == net.hydromatic.morel.compile.Generator.Cardinality.INFINITE) {
    return null;  // Cannot build Relational.iterate with infinite base
}
```

### 3.2 Why Infinite Cardinality Causes Rejection

The cardinality boundary exists because:

1. **Runtime materialization**: Infinite generators cannot be materialized at runtime
2. **Compile-time limitation**: At compile time, we don't know what runtime values are available
3. **Runtime failure**: Would fail with "infinite: int * int" when attempting to iterate

**From comments in code** (lines 510-522):
```java
// If the base case is non-invertible (e.g., a user-defined function call),
// the invert method returns a result with INFINITE cardinality as a fallback.
// We cannot safely build Relational.iterate with an infinite generator because:
// 1. An infinite generator cannot be materialized at runtime
// 2. At compile time we don't know what runtime values are available
// 3. The entire iteration would fail with "infinite: int * int" at runtime
```

### 3.3 Fallback Location

When cardinality is INFINITE, `tryInvertTransitiveClosure()` returns `null` (line 525), causing:

1. **In PredicateInverter.invert()**: Falls back to `generatorFor(goalPats)` which creates extent generators
2. **In Extents.g3b**: The `inversionSucceeded` check (lines 745-747) prevents using the result

```java
// Extents.java lines 745-747
final boolean inversionSucceeded =
    result.remainingFilters.size() != 1
        || !result.remainingFilters.get(0).equals(filter);
```

### 3.4 INFINITE Cardinality Locations

All places INFINITE cardinality is set:

| Line | Context | Description |
|------|---------|-------------|
| 348 | `invertExists` | Extent scan for exists expressions |
| 1176 | `createExtentGenerator` | Default extent for unbound patterns |
| 1559 | `computeCardinality` | If any terminal is INFINITE |

---

## 4. Integration Readiness

### 4.1 What's Working

| Component | Status | Evidence |
|-----------|--------|----------|
| Environment availability | WORKING | Line 401, 428 |
| TypeSystem availability | WORKING | Line 400, 427 |
| PredicateInverter.invert() | WORKING | Lines 741-743 |
| Generator conversion | WORKING | Lines 729-740 |
| Result processing | WORKING | Lines 745-758 |
| Cardinality checks | WORKING | Lines 590-604 |

### 4.2 What Needs Fixing

**Single-line fix required**:

```java
// Extents.java Line 155
// CURRENT (broken):
extent.g3(map.map, ((Core.Where) step).exp);

// REQUIRED (working):
extent.g3b(map.map, ((Core.Where) step).exp);
```

### 4.3 Integration Gaps

| Gap | Severity | Fix Effort |
|-----|----------|-----------|
| g3 called instead of g3b | HIGH | 5 minutes |
| g3b duplicates g3 logic | LOW | Refactor later |
| No unit test for integration | MEDIUM | 30 minutes |

### 4.4 Complexity Estimate

| Task | Hours |
|------|-------|
| Fix call from g3 to g3b | 0.1 |
| Test integration with existing tests | 0.5 |
| Add dedicated integration test | 1.0 |
| Debug any issues | 1.0 |
| **Total** | **2.6 hours** |

### 4.5 Assumptions Requiring Validation

1. **Assumption**: Changing g3 to g3b won't break existing extent analysis
   - **Validation**: g3b falls through to g3 logic when tryInvert is false
   - **Risk**: LOW - g3b is a superset of g3

2. **Assumption**: Environment passed from SuchThatShuttle has function bindings
   - **Validation**: `initialEnv` in SuchThatShuttle should have `let` bindings
   - **Risk**: MEDIUM - needs testing with actual recursive functions

3. **Assumption**: Cardinality boundary correctly rejects non-invertible bases
   - **Validation**: Debug output in lines 129-148 confirms binding access
   - **Risk**: LOW - well-documented in code

---

## 5. Code Snippets

### 5.1 The Missing Integration (Extents.create)

```java
// File: Extents.java
// Lines: 140-157

public static Analysis create(
    TypeSystem typeSystem,
    Environment env,
    boolean invert,      // <-- invert=true from SuchThatShuttle
    Core.Pat pat,
    SortedMap<Core.NamedPat, Core.Exp> boundPats,
    Iterable<? extends Core.FromStep> followingSteps,
    PairList<Core.IdPat, Core.Exp> idPats) {
  final Extent extent =
      new Extent(typeSystem, env, invert, pat, boundPats, idPats);
  // ...
  for (Core.FromStep step : followingSteps) {
    if (step instanceof Core.Where) {
      extent.g3(map.map, ((Core.Where) step).exp);  // <-- SHOULD BE g3b
    }
  }
  // ...
}
```

### 5.2 The Working Integration (in g3b)

```java
// File: Extents.java
// Lines: 727-758

if (tryInvert) {
  // Convert boundPats to generators
  final Map<Core.NamedPat, PredicateInverter.Generator> generators =
      new LinkedHashMap<>();
  boundPats.forEach((pat, exp) ->
      generators.put(pat, new PredicateInverter.Generator(
          pat, exp, Generator.Cardinality.FINITE,
          ImmutableList.of(), ImmutableSet.of())));

  final PredicateInverter.Result result =
      PredicateInverter.invert(
          typeSystem, env, filter, goalPats, generators);

  // Check if inversion succeeded
  final boolean inversionSucceeded =
      result.remainingFilters.size() != 1
          || !result.remainingFilters.get(0).equals(filter);

  if (inversionSucceeded) {
    final Core.Exp combinedFilter =
        result.remainingFilters.isEmpty()
            ? core.boolLiteral(true)
            : core.andAlso(typeSystem, result.remainingFilters);
    goalPats.forEach(pat ->
        map.computeIfAbsent(pat, p -> PairList.of())
            .add(result.generator.expression, combinedFilter));
  }
}
```

### 5.3 The Cardinality Boundary

```java
// File: PredicateInverter.java
// Lines: 508-526

// Try to invert the base case without the recursive call
Result baseCaseResult = invert(baseCase, goalPats, ImmutableList.of());

// Check if base case could be inverted to a FINITE generator.
if (baseCaseResult.generator.cardinality
    == net.hydromatic.morel.compile.Generator.Cardinality.INFINITE) {
  return null;  // Cannot build Relational.iterate with infinite base
}

// Build Relational.iterate with the finite base generator.
// ...
```

---

## 6. Conclusion

### Integration Status: 50% Complete

The PredicateInverter integration is architecturally complete but operationally broken due to a single missed method call. All necessary components are in place:

- Environment and TypeSystem are correctly passed
- Generator conversion logic is implemented
- Cardinality boundary checks are working
- Result processing handles success and failure

### Recommended Fix

Change line 155 of Extents.java from:
```java
extent.g3(map.map, ((Core.Where) step).exp);
```
to:
```java
extent.g3b(map.map, ((Core.Where) step).exp);
```

### Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Regression in existing tests | LOW | MEDIUM | Run full test suite |
| g3b logic differs from g3 | LOW | LOW | g3b is superset |
| Environment binding issues | MEDIUM | HIGH | Debug output exists |

---

**Document Version**: 1.0
**Author**: Claude (java-architect-planner)
**References**: morel-vhd, morel-px9
