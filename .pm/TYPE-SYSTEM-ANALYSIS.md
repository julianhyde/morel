# Type System Analysis for PredicateInverter

**Bead**: morel-3f8 (B8 Type System Analysis)
**Date**: 2026-01-24
**Author**: java-architect-planner agent

## Executive Summary

This document analyzes how the type system handles `Core.Apply` construction in `PredicateInverter.java`, specifically for `Relational.iterate` calls. The analysis reveals that the current implementation is **type-correct** for the nominal case but has several edge cases that could cause runtime type errors.

**Critical Runtime Finding**: ScriptTest[11] (such-that.smli) fails with `"infinite: int * int"` because:
1. The base case inversion for `edge(x,y)` returns INFINITE cardinality
2. This causes `tryInvertTransitiveClosure` to return null (line 525)
3. The fallback path creates an infinite extent
4. Runtime throws when trying to materialize the infinite extent

This is a **logic issue**, not a type system issue - the `Relational.iterate` code path at lines 551-565 is **not being reached** in the current test.

---

## 1. Relational.iterate Type Signature

### Definition Location
`src/main/java/net/hydromatic/morel/compile/BuiltIn.java:2936-2956`

### Type Signature
```sml
(* Bag variant *)
'a bag -> ('a bag * 'a bag -> 'a bag) -> 'a bag

(* List variant *)
'a list -> ('a list * 'a list -> 'a list) -> 'a list
```

### BuiltIn Definition
```java
RELATIONAL_ITERATE(
    "Relational",
    "iterate",
    "iterate",
    ts ->
        ts.multi(
            ts.forallType(
                1,
                h ->
                    ts.fnType(
                        h.bag(0),
                        ts.fnType(ts.tupleType(h.bag(0), h.bag(0)), h.bag(0)),
                        h.bag(0))),
            ts.forallType(
                1,
                h ->
                    ts.fnType(
                        h.list(0),
                        ts.fnType(
                            ts.tupleType(h.list(0), h.list(0)), h.list(0)),
                        h.list(0)))))
```

### Type Structure
The type is a **MultiType** containing two overloads:
1. **Bag variant**: `forall 'a. 'a bag -> ('a bag * 'a bag -> 'a bag) -> 'a bag`
2. **List variant**: `forall 'a. 'a list -> ('a list * 'a list -> 'a list) -> 'a list`

Each overload is a **ForallType** with one type parameter. The curried function type breaks down as:
- **Arg1**: Collection type (`'a bag` or `'a list`)
- **Arg2**: Step function of type `('a coll * 'a coll) -> 'a coll`
- **Result**: Collection type (`'a bag` or `'a list`)

### Type Verification for PredicateInverter
In `PredicateInverter.java:551-565`:

```java
// Get the RELATIONAL_ITERATE built-in as a function literal
final Core.Exp iterateFn =
    core.functionLiteral(typeSystem, BuiltIn.RELATIONAL_ITERATE);

// Apply iterate to base generator
final Core.Exp iterateWithBase =
    core.apply(Pos.ZERO, iterateFn, baseGenerator);

// Apply the result to the step function
final Core.Exp relationalIterate =
    core.apply(Pos.ZERO, iterateWithBase, stepFunction);
```

**Analysis**:
- `core.functionLiteral(typeSystem, BuiltIn.RELATIONAL_ITERATE)` returns a literal with **MultiType**
- The `core.apply(Pos.ZERO, iterateFn, baseGenerator)` call relies on type inference to select the correct overload
- **ISSUE**: The `core.apply(Pos, Core.Exp, Core.Exp)` method extracts `FnType` from `fn.type`, but if `fn.type` is a `MultiType`, this will fail

**Recommendation**: Use `core.functionLiteral(typeSystem, builtIn, argList)` which resolves overloading.

---

## 2. Step Function Lambda Type

### Location
`PredicateInverter.java:1649-1683` (`buildStepLambda`)

### Type Construction

```java
// Determine the element type from the base generator
final Type elementType = baseGen.type.elementType();
final Type bagType = baseGen.type;

// Create parameter patterns for the lambda: (old, new)
final Core.IdPat oldPat = core.idPat(bagType, "oldTuples", 0);
final Core.IdPat newPat = core.idPat(bagType, "newTuples", 0);

// Create the tuple pattern for lambda parameters: (old, new)
final Core.TuplePat paramPat =
    core.tuplePat(typeSystem, ImmutableList.of(oldPat, newPat));

// Determine function type: (bag, bag) -> bag
final Type paramType = typeSystem.tupleType(bagType, bagType);
final net.hydromatic.morel.type.FnType fnType =
    typeSystem.fnType(paramType, bagType);

// Create lambda parameter (simple IdPat)
final Core.IdPat lambdaParam = core.idPat(paramType, "stepFnParam", 0);

// Create lambda: fn v => case v of (old, new) => fromExp
final Core.Match match = core.match(Pos.ZERO, paramPat, fromExp);
final Core.Case caseExp =
    core.caseOf(
        Pos.ZERO, bagType, core.id(lambdaParam), ImmutableList.of(match));

return core.fn(fnType, lambdaParam, caseExp);
```

### Type Flow Verification

| Step | Expression | Type |
|------|------------|------|
| 1 | `baseGen.type` | `'a bag` (e.g., `(int * int) bag`) |
| 2 | `baseGen.type.elementType()` | `'a` (e.g., `int * int`) |
| 3 | `oldPat`, `newPat` | `'a bag` |
| 4 | `paramPat` | `('a bag * 'a bag)` |
| 5 | `paramType` | `('a bag * 'a bag)` |
| 6 | `fnType` | `('a bag * 'a bag) -> 'a bag` |
| 7 | `lambdaParam` | `('a bag * 'a bag)` |
| 8 | `caseExp` | `'a bag` |
| 9 | Result | `fn: ('a bag * 'a bag) -> 'a bag` |

**Verification**: The type construction is **CORRECT**. The step function type `('a bag * 'a bag) -> 'a bag` matches the expected signature for `Relational.iterate`.

### Potential Issues

1. **Line 1653**: `baseGen.type.elementType()` will throw `IllegalArgumentException("not a collection type")` if `baseGen.type` is not a collection type
   - **Mitigation**: Add pre-condition check

2. **Pattern-Case Pattern**: The implementation uses `fn v => case v of (old, new) => ...` instead of direct pattern matching in lambda
   - This is **correct** - Morel requires this indirection for tuple patterns in lambda parameters

---

## 3. Core.Apply Type Construction

### Location
`PredicateInverter.java:551-565`

### Two-Stage Application

**Stage 1**: Apply `Relational.iterate` to `baseGenerator`
```java
final Core.Exp iterateWithBase =
    core.apply(Pos.ZERO, iterateFn, baseGenerator);
```

**Stage 2**: Apply partial application to `stepFunction`
```java
final Core.Exp relationalIterate =
    core.apply(Pos.ZERO, iterateWithBase, stepFunction);
```

### CoreBuilder.apply Analysis
From `CoreBuilder.java:582-585`:
```java
public Core.Apply apply(Pos pos, Core.Exp fn, Core.Exp arg) {
  final FnType fnType = (FnType) fn.type;
  return apply(pos, fnType.resultType, fn, arg);
}
```

**Type Flow**:
1. `iterateFn.type` is **MultiType** (not FnType) - **PROBLEM**
2. Cast `(FnType) fn.type` will throw `ClassCastException`

### Analysis of MultiType Handling

The `core.functionLiteral(TypeSystem, BuiltIn)` method returns:
```java
public Core.Literal functionLiteral(TypeSystem typeSystem, BuiltIn builtIn) {
  final Type type = builtIn.typeFunction.apply(typeSystem);
  return functionLiteral(type, builtIn);
}
```

For `RELATIONAL_ITERATE`, this returns a `Core.Literal` with type = `MultiType`.

**ISSUE**: When `apply(Pos, Core.Exp, Core.Exp)` is called on this literal, it casts to `FnType` which will fail.

### Resolution Approaches

**Option 1**: Use `core.functionLiteral(typeSystem, builtIn, argList)`
```java
final Core.Literal iterateFn = core.functionLiteral(
    typeSystem,
    BuiltIn.RELATIONAL_ITERATE,
    ImmutableList.of(baseGenerator));
```
This resolves the overload based on argument types.

**Option 2**: Manually select bag/list variant
```java
// Determine if base is bag or list
boolean isBag = baseGenerator.type.op() == Op.DATA_TYPE
    && ((DataType) baseGenerator.type).name.equals("bag");

// Build type manually
Type fnType = isBag
    ? typeSystem.forallType(1, h ->
        ts.fnType(h.bag(0), ts.fnType(ts.tupleType(h.bag(0), h.bag(0)), h.bag(0)), h.bag(0)))
    : typeSystem.forallType(1, h ->
        ts.fnType(h.list(0), ts.fnType(ts.tupleType(h.list(0), h.list(0)), h.list(0)), h.list(0)));
```

**Recommendation**: Option 1 is cleaner and leverages existing infrastructure.

---

## 4. FROM Expression Type Handling

### Location
`PredicateInverter.java:1701-1784` (`buildStepBody`)

### Type Decomposition

```java
// Determine the base element type (should be a tuple type like (int, int))
final Type baseElemType = baseGen.type.elementType();

// Decompose tuple type
final Type[] baseComponents;
if (baseElemType.op() == Op.TUPLE_TYPE) {
  final RecordLikeType recordLikeType = (RecordLikeType) baseElemType;
  final List<Type> argTypes = recordLikeType.argTypes();
  baseComponents = argTypes.toArray(new Type[0]);
} else {
  // Simple case: single element type
  baseComponents = new Type[] {baseElemType};
}
```

### Pattern Construction Types

| Pattern | Type | Purpose |
|---------|------|---------|
| `xPat` | `baseComponents[0]` (e.g., `int`) | First element of new tuple |
| `zPat` | `baseComponents[1]` (e.g., `int`) | Join variable from new |
| `z2Pat` | `baseComponents[0]` (e.g., `int`) | Join variable from base |
| `yPat` | `baseComponents[1]` (e.g., `int`) | Second element of base tuple |
| `newScanPat` | `(int * int)` | Tuple pattern for new scan |
| `baseScanPat` | `(int * int)` | Tuple pattern for base scan |

### WHERE Clause Type Safety

```java
// OP_EQ is polymorphic, so we need to provide the type parameter
final Type zType = zPat.type;
final Core.Exp whereClause =
    core.call(
        typeSystem,
        BuiltIn.OP_EQ,
        zType,               // <-- Type parameter for polymorphic =
        Pos.ZERO,
        core.id(zPat),
        core.id(z2Pat));
```

**Analysis**: The `core.call` with 6 arguments uses the overload-resolving version that instantiates the polymorphic `=` operator with the concrete type. This is **type-correct**.

### YIELD Clause Type Safety

```java
final Core.Exp yieldExp;
if (baseComponents.length > 1) {
  yieldExp = core.tuple(typeSystem, core.id(xPat), core.id(yPat));
} else {
  yieldExp = core.id(xPat);
}
```

**Analysis**:
- `core.tuple(typeSystem, core.id(xPat), core.id(yPat))` produces type `(xPat.type * yPat.type)`
- For transitive closure with `(int * int)` base, this produces `(int * int)`
- This matches the expected element type of the result bag
- **Type-correct**

### Potential Type Errors

1. **Type Index Mismatch** (Lines 1737-1741):
   ```java
   final Core.IdPat z2Pat = core.idPat(baseComponents[0], "z2", 0);  // Uses [0]
   final Core.IdPat yPat = core.idPat(baseComponents[1], "y", 0);    // Uses [1]
   ```
   The join pattern assumes `(z2, y)` but uses `baseComponents[0]` for z2.
   For tuple `(a, b)` where we scan base edges, we want `z2` to match `z` type.

   **Issue**: `z` has type `baseComponents[1]` but `z2` has type `baseComponents[0]`.
   This is only correct if `baseComponents[0] == baseComponents[1]` (e.g., `(int * int)`).

   **Impact**: Type error for heterogeneous tuple types like `(string * int)`.

2. **Assumption of Binary Tuples** (Lines 1714-1721):
   The code handles only 2-element tuples or single elements. Triples and higher are not supported.

---

## 5. Identified Type Errors

### Critical Issues

| ID | Location | Issue | Impact | Fix |
|----|----------|-------|--------|-----|
| T1 | Line 556-557 | `functionLiteral` returns MultiType | ClassCastException at runtime | Use overload-resolving `functionLiteral` |
| T2 | Line 1653 | No collection type guard | IllegalArgumentException if not collection | Add `if (!baseGen.type.isCollection())` check |
| T3 | Line 1737 | z2Pat uses wrong component index | Type mismatch for heterogeneous tuples | Use `baseComponents[1]` for z2Pat |

### Non-Critical Issues

| ID | Location | Issue | Impact | Fix |
|----|----------|-------|--------|-----|
| T4 | Lines 1714-1721 | Only handles 1-2 element tuples | Limited to binary relations | Document limitation or extend |
| T5 | Line 1751 | `Environment` cast to null | Relies on undocumented behavior | Document or refactor |

---

## 6. Type System Compatibility

### API Usage Verification

| Method | Usage | Correct |
|--------|-------|---------|
| `typeSystem.fnType(Type, Type)` | Line 1670-1671 | Yes |
| `typeSystem.tupleType(Type, Type)` | Line 1669 | Yes |
| `baseGen.type.elementType()` | Line 1653 | Yes (with guard) |
| `core.idPat(Type, String, int)` | Multiple | Yes |
| `core.tuplePat(TypeSystem, List)` | Lines 1665-1666 | Yes |
| `core.fn(FnType, IdPat, Exp)` | Line 1682 | Yes |
| `core.caseOf(Pos, Type, Exp, List)` | Lines 1678-1680 | Yes |
| `core.call(TypeSystem, BuiltIn, Type, Pos, Exp...)` | Line 1764-1770 | Yes |
| `core.apply(Pos, Exp, Exp)` | Lines 560-565 | **No** - MultiType issue |

### Type System Assumptions

1. **Assumption**: Base generator is always a collection type (bag or list)
   - **Location**: Line 1653
   - **Status**: Implicit, should be explicit

2. **Assumption**: Element type for transitive closure is always a binary tuple
   - **Location**: Lines 1714-1745
   - **Status**: Documented limitation

3. **Assumption**: ForallType substitution preserves type structure
   - **Location**: Via `core.call` for OP_EQ
   - **Status**: Correct, handled by TypeSystem

---

## 7. Recommendations

### Immediate Fixes (P0)

1. **Fix MultiType Handling in Relational.iterate Construction**
   ```java
   // BEFORE (incorrect)
   final Core.Exp iterateFn =
       core.functionLiteral(typeSystem, BuiltIn.RELATIONAL_ITERATE);
   final Core.Exp iterateWithBase =
       core.apply(Pos.ZERO, iterateFn, baseGenerator);

   // AFTER (correct)
   final Core.Literal iterateFn = core.functionLiteral(
       typeSystem,
       BuiltIn.RELATIONAL_ITERATE,
       ImmutableList.of(baseGenerator, stepFunction));
   final Type iterateFnType = iterateFn.type;
   final FnType fn1Type = (FnType) iterateFnType;
   final Core.Exp iterateWithBase =
       core.apply(Pos.ZERO, fn1Type.resultType, iterateFn, baseGenerator);
   final FnType fn2Type = (FnType) fn1Type.resultType;
   final Core.Exp relationalIterate =
       core.apply(Pos.ZERO, fn2Type.resultType, iterateWithBase, stepFunction);
   ```

2. **Add Collection Type Guard**
   ```java
   // At start of buildStepLambda
   if (!baseGen.type.isCollection()) {
     throw new IllegalArgumentException(
         "Base generator must be a collection type, got: " + baseGen.type);
   }
   ```

### Short-Term Improvements (P1)

3. **Fix z2Pat Type Index**
   ```java
   // Line 1737: z2 should have same type as z (baseComponents[1])
   final Core.IdPat z2Pat = core.idPat(baseComponents[1], "z2", 0);
   ```

4. **Add Support for Arbitrary Tuple Arities**
   - Extend `buildStepBody` to handle n-ary tuples
   - Use first n-1 components as "threaded" variables

### Documentation

5. **Document Type Assumptions**
   - Binary tuple requirement
   - Collection type requirement
   - Homogeneous join variable types

---

## 8. Test Coverage Gaps

Based on the analysis, the following test cases should be added:

1. **Test MultiType Resolution**: Verify Relational.iterate correctly resolves bag vs list
2. **Test Heterogeneous Tuples**: `(string * int)` base type
3. **Test Non-Collection Base**: Error handling for scalar base
4. **Test Triple+ Tuples**: `(int * int * int)` base type (expect failure or extension)

---

## 9. Runtime Verification

### Test Execution
```bash
./mvnw test -Dtest=ScriptTest
```

### Failure Details
```
Test: ScriptTest.test(String)[11]
File: such-that.smli
Error: java.lang.AssertionError: infinite: int * int
```

### Stack Trace Analysis
```
at net.hydromatic.morel.eval.Codes$173.apply(Codes.java:3725)  <- Z_EXTENT throws
at net.hydromatic.morel.eval.Codes$BaseApplicable1.apply(Codes.java:5187)
at net.hydromatic.morel.eval.Codes$ApplyCode.eval(Codes.java:4863)
at net.hydromatic.morel.eval.RowSinks$ScanRowSink.accept(RowSinks.java:253)
at net.hydromatic.morel.eval.RowSinks$FromCode.eval(RowSinks.java:184)
```

### Root Cause Path
1. Query: `from p where path p`
2. Predicate inversion attempts to invert `path(p)`
3. `tryInvertTransitiveClosure` is called
4. Base case `edge(x,y)` inversion produces INFINITE cardinality
5. Check at line 523-525 returns null
6. Extents.g3 falls back to infinite extent
7. Runtime evaluator throws on infinite extent

### Implication for Type System Analysis
The identified type issues (T1-T5) have **not been runtime-tested** because the code path to `Relational.iterate` construction is blocked by the INFINITE cardinality check. Once the base case inversion is fixed to return FINITE cardinality, the type system issues will become runtime-visible.

### Blocking Issue
**B1 Priority**: Fix base case inversion for `edge(x,y)` to return FINITE cardinality before type system fixes can be validated.

---

## Appendix: Type Hierarchy

```
Type
  |-- BaseType
  |     |-- FnType (paramType, resultType)
  |     |-- TupleType (argTypes)
  |     |-- RecordType (argNameTypes)
  |     |-- ListType (elementType)
  |     |-- DataType (name, args) [includes bag]
  |     |-- ForallType (parameterCount, type)
  |     |-- PrimitiveType
  |-- MultiType (types: List<Type>)
```

---

## References

- `BuiltIn.java:2936-2956` - Relational.iterate definition
- `CoreBuilder.java:574-585` - Core.Apply construction
- `CoreBuilder.java:163-193` - functionLiteral methods
- `TypeSystem.java:547-561` - Type.apply for ForallType instantiation
- `Codes.java:2902-2925` - Runtime implementation of iterate
