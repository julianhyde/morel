# Phase 5a Success Criteria: GO/NO-GO Decision Framework

**Date**: 2026-01-24
**Author**: java-architect-planner
**Bead**: morel-fds
**Purpose**: Define measurable success criteria for Phase 5a validation

---

## Executive Summary

Phase 5a validates whether the Core.Apply approach for transitive closure works end-to-end. This document defines precise, measurable criteria for GO/PARTIAL-GO/NO-GO decisions with a minimum confidence threshold of 85%.

**Key Insight from B1 Discovery**: Code is 80-90% complete. The real validation is proving:
1. Environment scoping works (already validated in 5a-prime)
2. Cardinality boundary is correctly detected
3. Core.Apply construction produces valid IR
4. Step function generation creates correct lambda
5. Full integration produces correct output

---

## GO Criteria (ALL Must Pass for GO Decision)

### GO Criterion #1: Environment Scoping [ALREADY VALIDATED]

| Attribute | Value |
|-----------|-------|
| **Measurement** | Debug output shows function bindings in environment |
| **Evidence Required** | System.err output from PredicateInverter lines 126-148 |
| **Test Method** | Run test, capture stderr, verify "Binding accessible: true" |
| **Status** | **ALREADY VALIDATED in Phase 5a-prime** |
| **Confidence Impact** | +10% (0% -> 75% base established) |
| **Success Definition** | env.getOpt(fnPat) returns non-null Binding |

**Evidence Collected** (Phase 5a-prime):
```
=== PHASE-5A-PRIME: PredicateInverter Environment Debug ===
Environment type: [EnvironmentClassName]
Checking function binding: path
Binding accessible: true
  -> GO: environment scoping works!
=== End Debug ===
```

**Verdict**: GO (already confirmed)

---

### GO Criterion #2: Cardinality Boundary Detection

| Attribute | Value |
|-----------|-------|
| **Measurement** | Code correctly identifies INFINITE cardinality at compile-time |
| **Evidence Required** | Debug trace showing Generator.cardinality evaluation |
| **Test Method** | Add trace to PredicateInverter.java:523-526, run test |
| **Status** | PENDING VALIDATION |
| **Confidence Impact** | +5% (75% -> 80%) |
| **Success Definition** | Pattern correctly rejected when base case has INFINITE cardinality |

**Test Code** (add temporarily to validate):
```java
// At PredicateInverter.java:523
System.err.println("=== GO-CRITERION-2: Cardinality Boundary ===");
System.err.println("Base case cardinality: " + baseCaseResult.generator.cardinality);
if (baseCaseResult.generator.cardinality == INFINITE) {
  System.err.println("-> Correctly detected INFINITE, returning null");
  return null;  // Correctly rejects infinite base case
}
System.err.println("-> FINITE base case, proceeding with iterate");
```

**Pass Criteria**:
- [ ] When base case is non-invertible (runtime-bound): output shows INFINITE
- [ ] When base case is invertible (compile-time known): output shows FINITE
- [ ] Code path correctly branches based on cardinality

**Fail Criteria**:
- [ ] Cardinality always INFINITE (never reaches iterate construction)
- [ ] Cardinality always FINITE (dangerous - could produce infinite loops)
- [ ] Cardinality check throws exception

---

### GO Criterion #3: Core.Apply Construction Type Safety

| Attribute | Value |
|-----------|-------|
| **Measurement** | Valid Relational.iterate call generated with correct types |
| **Evidence Required** | Core IR inspection showing apply nesting and type annotations |
| **Test Method** | Inspect generated Core.Exp structure, verify types match |
| **Status** | PENDING VALIDATION |
| **Confidence Impact** | +5% (80% -> 85%) |
| **Success Definition** | No type errors, proper argument ordering |

**Expected Core.Apply Structure**:
```
Apply(
  Apply(
    FnLiteral(RELATIONAL_ITERATE),  // fn: 'a bag -> (('a bag * 'a bag) -> 'a bag) -> 'a bag
    baseGenerator                    // arg1: 'a bag (edges list)
  ),
  stepFunction                       // arg2: ('a bag * 'a bag) -> 'a bag
)
```

**Test Code** (add temporarily):
```java
// At PredicateInverter.java:565
System.err.println("=== GO-CRITERION-3: Core.Apply Construction ===");
System.err.println("Iterate fn type: " + iterateFn.type);
System.err.println("Base generator type: " + baseGenerator.type);
System.err.println("iterateWithBase type: " + iterateWithBase.type);
System.err.println("stepFunction type: " + stepFunction.type);
System.err.println("Final relationalIterate type: " + relationalIterate.type);
```

**Pass Criteria**:
- [ ] iterateFn.type shows correct polymorphic function signature
- [ ] baseGenerator.type is a bag/list type
- [ ] stepFunction.type is (bag * bag) -> bag
- [ ] relationalIterate.type matches expected result bag type
- [ ] No ClassCastException or type inference failures

**Fail Criteria**:
- [ ] Type mismatch between iterateFn and arguments
- [ ] stepFunction has wrong signature
- [ ] relationalIterate.type is incorrect or null
- [ ] Type inference exception thrown

---

### GO Criterion #4: Step Function Lambda Signature

| Attribute | Value |
|-----------|-------|
| **Measurement** | Lambda with correct signature (bag, bag) -> bag generated |
| **Evidence Required** | Step function Core.Fn inspection |
| **Test Method** | Inspect buildStepLambda output, verify param and return types |
| **Status** | PENDING VALIDATION |
| **Confidence Impact** | +3% (85% -> 88%) |
| **Success Definition** | Types match Relational.iterate expectations |

**Expected Lambda Structure**:
```
Fn(
  idPat: stepFnParam : (('a * 'a) bag * ('a * 'a) bag),
  body: Case(
    id(stepFnParam),
    [Match(
      TuplePat(oldPat, newPat),
      FROM(scan(x,z)..., scan(z2,y)..., where(z=z2), yield(x,y))
    )]
  )
)
```

**Test Code** (add temporarily):
```java
// At PredicateInverter.java:1682
System.err.println("=== GO-CRITERION-4: Step Function Lambda ===");
System.err.println("Lambda param type: " + lambdaParam.type);
System.err.println("Lambda body type: " + caseExp.type);
System.err.println("Function type: " + fnType);
```

**Pass Criteria**:
- [ ] Lambda parameter is tuple of two bags
- [ ] Lambda body produces bag result
- [ ] fnType shows (bag * bag) -> bag signature
- [ ] No lambda construction errors

**Fail Criteria**:
- [ ] Parameter type mismatch
- [ ] Body type doesn't match return type
- [ ] Lambda construction throws exception

---

### GO Criterion #5: FROM Expression Construction

| Attribute | Value |
|-----------|-------|
| **Measurement** | FROM body with correct scan, where, yield steps |
| **Evidence Required** | FROM expression structure inspection |
| **Test Method** | Inspect buildStepBody output, verify steps |
| **Status** | PENDING VALIDATION |
| **Confidence Impact** | +3% (88% -> 91%) |
| **Success Definition** | FROM has 2 scans, 1 where, 1 yield with correct patterns |

**Expected FROM Structure**:
```
FROM(
  Scan((x, z), newTuples),       // from (x,z) in new
  Scan((z2, y), baseGenerator),  // (z2,y) in base
  Where(z = z2),                 // where z = z2
  Yield((x, y))                  // yield (x, y)
)
```

**Test Code** (add temporarily):
```java
// At PredicateInverter.java:1783
System.err.println("=== GO-CRITERION-5: FROM Expression ===");
Core.From from = (Core.From) builder.build();
for (Core.FromStep step : from.steps) {
  System.err.println("  Step: " + step.op + " - " + step);
}
System.err.println("FROM result type: " + from.type);
```

**Pass Criteria**:
- [ ] First step is SCAN with tuple pattern matching newPat
- [ ] Second step is SCAN with tuple pattern matching baseGenerator
- [ ] Third step is WHERE with equality condition
- [ ] Final step is implicit YIELD with result tuple
- [ ] FROM result type is bag of tuples

**Fail Criteria**:
- [ ] Missing scan steps
- [ ] WHERE clause malformed
- [ ] YIELD produces wrong type
- [ ] FROM construction exception

---

### GO Criterion #6: Integration Test Output Correctness

| Attribute | Value |
|-----------|-------|
| **Measurement** | Test produces exact expected output |
| **Evidence Required** | Test output from such-that.smli lines 737-744 |
| **Test Method** | `./mvnw test -Dtest=ScriptTest` |
| **Status** | PENDING VALIDATION |
| **Confidence Impact** | +7% (91% -> 98%) |
| **Success Definition** | Output is [(1,2),(2,3),(1,3)] |

**Test Input** (such-that.smli:737-744):
```sml
val edges = [{x=1,y=2}, {x=2,y=3}]
fun edge (x, y) = {x, y} elem edges
fun path (x, y) = edge (x, y) orelse
  (exists z where edge (x, z) andalso path (z, y))
from p where path p
```

**Expected Output**:
```
[(1,2),(2,3),(1,3)]
```

**Pass Criteria**:
- [ ] Test completes without exception
- [ ] Output contains exactly 3 tuples
- [ ] Output contains (1,2) - direct edge
- [ ] Output contains (2,3) - direct edge
- [ ] Output contains (1,3) - transitive edge
- [ ] No duplicate tuples
- [ ] No extra tuples

**Fail Criteria**:
- [ ] Test throws exception
- [ ] Output is empty []
- [ ] Missing transitive edge (1,3)
- [ ] Contains wrong tuples
- [ ] "infinite: int * int" error message

---

### GO Criterion #7: No Regression in Existing Tests

| Attribute | Value |
|-----------|-------|
| **Measurement** | All existing tests still pass |
| **Evidence Required** | Full test suite results |
| **Test Method** | `./mvnw test` |
| **Status** | PENDING VALIDATION |
| **Confidence Impact** | +2% (98% -> 100%) |
| **Success Definition** | 159/159 tests pass |

**Pass Criteria**:
- [ ] All 159 baseline tests pass
- [ ] No new test failures
- [ ] No performance regressions visible

**Fail Criteria**:
- [ ] Any baseline test fails
- [ ] Test suite hangs or times out
- [ ] Memory issues during testing

---

## PARTIAL-GO Scenarios (Fixable Issues)

### PARTIAL-GO #1: Type System Mismatch

| Attribute | Value |
|-----------|-------|
| **Symptom** | Type error in Core.Apply construction |
| **Example Error** | "Expected (bag, bag) -> bag, got bag -> bag" |
| **Impact** | Phase 5a fails, but pattern detection works |
| **Remediation Time** | 1-4 hours (add type coercion) |
| **Root Cause** | Lambda signature mismatch or tuple unpacking issue |
| **Decision Rule** | Fix if < 2 hours, else escalate |

**Detection**:
- Type error logged during Core.Apply construction
- GO Criterion #3 or #4 fails

**Fix Approach**:
1. Add explicit type annotations to lambda
2. Wrap arguments in appropriate constructors
3. Add type coercion at boundary

**Decision**: Fix then GO if fix < 2 hours

---

### PARTIAL-GO #2: FROM Expression Pattern Issue

| Attribute | Value |
|-----------|-------|
| **Symptom** | WHERE clause or YIELD produces wrong result |
| **Example Error** | Output has duplicates or wrong tuples |
| **Impact** | Transitive closure computed incorrectly |
| **Remediation Time** | 2-6 hours (fix FROM construction) |
| **Root Cause** | Pattern variable naming conflict or join condition error |
| **Decision Rule** | Fix if < 4 hours, else escalate |

**Detection**:
- GO Criterion #5 or #6 fails
- Output is non-empty but incorrect

**Fix Approach**:
1. Debug variable naming in FROM patterns
2. Fix z/z2 join condition
3. Verify tuple decomposition logic

**Decision**: Fix then GO if fix < 4 hours

---

### PARTIAL-GO #3: Missing Transitive Edge Only

| Attribute | Value |
|-----------|-------|
| **Symptom** | Output [(1,2),(2,3)] - missing (1,3) |
| **Example Error** | Direct edges work, transitive edges don't |
| **Impact** | Base case works, recursion doesn't |
| **Remediation Time** | 2-4 hours (debug step function) |
| **Root Cause** | Step function not executing or join failing |
| **Decision Rule** | Fix if < 4 hours, else escalate |

**Detection**:
- GO Criterion #6 fails partially
- Direct edges present, transitive missing

**Fix Approach**:
1. Debug step function execution
2. Verify Relational.iterate is being called
3. Check join condition in step body

**Decision**: Fix then GO if fix < 4 hours

---

### PARTIAL-GO #4: Environment Binding Incomplete

| Attribute | Value |
|-----------|-------|
| **Symptom** | Function binding found but value inaccessible |
| **Example Error** | "Binding exists but binding.value is null" |
| **Impact** | Cannot inline function body |
| **Remediation Time** | 1-2 hours (fix binding retrieval) |
| **Root Cause** | Environment populated incompletely |
| **Decision Rule** | Fix if < 2 hours, else NO-GO |

**Detection**:
- GO Criterion #1 shows binding exists but empty
- Phase 5a-prime would have caught this

**Fix Approach**:
1. Trace environment construction in Compiles.java
2. Ensure function values are stored
3. Add missing binding population

**Decision**: Fix then GO if fix < 2 hours

---

## NO-GO Criteria (Require Fallback)

### NO-GO #1: Environment Binding Not Accessible

| Attribute | Value |
|-----------|-------|
| **Symptom** | Binding lookup returns null in PredicateInverter |
| **Example Error** | "=== NO-GO: binding not found in environment ===" |
| **Root Cause** | Environment passed incorrectly or not at all |
| **Impact** | Cannot distinguish runtime-bound vs compile-time values |
| **Fallback** | Explicit syntax (WITH RECURSIVE) approach |
| **Time to Pivot** | 1-2 days research + implementation |

**Detection**:
- GO Criterion #1 fails
- env.getOpt(fnPat) returns null

**Why NO-GO**:
- Core.Apply relies on accessing function definitions
- Without environment, cannot detect recursive patterns
- Architectural change required (>4 days)

**Fallback Activation**:
1. Update bead morel-l2u (Explicit Syntax Research) to P0
2. Archive Core.Apply approach
3. Begin WITH RECURSIVE syntax design

---

### NO-GO #2: Infinite Cardinality Unavoidable

| Attribute | Value |
|-----------|-------|
| **Symptom** | All inversions return INFINITE cardinality |
| **Example Error** | Cardinality check always fails, never reaches iterate |
| **Root Cause** | Inversion cannot distinguish compile-time from runtime values |
| **Impact** | Core.Apply never constructed, always falls back |
| **Fallback** | Mode Analysis (Option 2) or Explicit Syntax |
| **Time to Pivot** | 2-3 days for Mode Analysis research |

**Detection**:
- GO Criterion #2 consistently fails
- Cardinality always INFINITE

**Why NO-GO**:
- Core.Apply requires FINITE base case
- Cannot detect when value is actually known at compile-time
- Would need full mode analysis to solve

**Fallback Activation**:
1. Update bead morel-1af (Mode Analysis Research) to P0
2. Evaluate Option 2 implementation cost
3. Compare with Explicit Syntax cost

---

### NO-GO #3: Relational.iterate Type Incompatibility

| Attribute | Value |
|-----------|-------|
| **Symptom** | Generated Core.Apply fails type checking |
| **Example Error** | "Type mismatch: expected 'a bag, got 'a list" |
| **Root Cause** | Relational.iterate signature differs from expected |
| **Impact** | Cannot construct valid Core IR |
| **Fallback** | Wrap in type coercion layer or change API |
| **Time to Pivot** | 3-5 days API modification |

**Detection**:
- GO Criterion #3 fails consistently
- Type errors in all generated code

**Why NO-GO**:
- Type system fundamental to Morel
- Cannot bypass type safety
- API change required (significant effort)

**Fallback Activation**:
1. Document type system gap
2. Evaluate Relational.iterate modification
3. Consider alternative iteration primitives

---

### NO-GO #4: FROM Expression Generation Broken

| Attribute | Value |
|-----------|-------|
| **Symptom** | FROM builder produces malformed expressions |
| **Example Error** | NullPointerException in FROM construction |
| **Root Cause** | FromBuilder API misuse or bug |
| **Impact** | Cannot construct step function body |
| **Fallback** | Use different expression construction approach |
| **Time to Pivot** | 2-3 days API debugging/replacement |

**Detection**:
- GO Criterion #5 fails with exceptions
- FROM construction throws errors

**Why NO-GO**:
- Step function requires valid FROM expression
- Core.Apply depends on correctly formed step function
- Would need FromBuilder fix or replacement

**Fallback Activation**:
1. File FromBuilder bug report
2. Implement direct Core.From construction
3. Consider alternative step function design

---

## Decision Tree

```
                                Phase 5a Validation
                                        |
                    +-------------------+-------------------+
                    |                                       |
            [Run Criteria 1-7]                      [Any Exception?]
                    |                                       |
                    v                                       v
        +-------+---+---+-------+                    NO-GO (Immediate)
        |       |       |       |                           |
       C1      C2      C3-5   C6-7                   Fallback Research
        |       |       |       |
     PASS?   PASS?   PASS?   PASS?
        |       |       |       |
        +---+---+---+---+---+---+
            |       |       |
      [All Pass] [Some Fail] [Critical Fail]
            |       |       |
            v       v       v
           GO   PARTIAL   NO-GO
            |       |       |
            v       v       v
      Proceed   Fix & Test  Fallback
        to 5b       |       Research
            |       |       |
            v       v       v
      Confidence  < 2h?   Pivot to
         95%+       |     Explicit
            |    Yes/No   Syntax
            |     |   |
            |   Fix  Escalate
            |     |       |
            v     v       v
         5b-5d  Retest  NO-GO
```

---

## Confidence Mapping

### Criterion Impact Table

| Criterion | Test | Pass | Fail | Cumulative |
|-----------|------|------|------|------------|
| Base | Start | - | - | 0% |
| C1: Environment | Phase 5a-prime | +75% | NO-GO | 75% |
| C2: Cardinality | Runtime trace | +5% | -30% | 80% |
| C3: Core.Apply Type | Type inspection | +5% | -25% | 85% |
| C4: Lambda Signature | Type inspection | +3% | -15% | 88% |
| C5: FROM Expression | Structure check | +3% | -10% | 91% |
| C6: Output Correctness | Integration test | +7% | -20% | 98% |
| C7: No Regression | Full test suite | +2% | -5% | 100% |

### Decision Thresholds

| Confidence | Status | Action |
|------------|--------|--------|
| 0-50% | NO-GO | Pivot to fallback immediately |
| 51-70% | PARTIAL-GO | Assess fix time, likely escalate |
| 71-84% | PARTIAL-GO | Fix issues, retest within 4h budget |
| **85%+** | **GO** | **Proceed to Phase 5b** |
| 95%+ | HIGH-CONFIDENCE GO | Fast-track to Phase 5d |

### Failure Scenarios

| Failed Criteria | Confidence | Status | Action |
|-----------------|------------|--------|--------|
| C1 only | 0% | NO-GO | Fallback research |
| C2 only | 45% | NO-GO | Mode analysis needed |
| C3 only | 55% | PARTIAL-GO | Type fix likely < 2h |
| C4 only | 65% | PARTIAL-GO | Lambda fix likely < 2h |
| C5 only | 75% | PARTIAL-GO | FROM fix likely < 4h |
| C6 only | 78% | PARTIAL-GO | Debug step function |
| C7 only | 83% | PARTIAL-GO | Investigate regression |
| C3+C4 | 45% | NO-GO | Major type issues |
| C5+C6 | 65% | PARTIAL-GO | FROM-related, fixable |

---

## Validation Execution Checklist

### Pre-Validation Setup
- [ ] Branch is clean: `git status` shows no uncommitted changes
- [ ] Baseline tests pass: `./mvnw test` shows 159/159 passing
- [ ] Debug code ready: Trace statements prepared for criteria 2-5

### Criterion 1 Validation (COMPLETED)
- [x] Phase 5a-prime executed
- [x] Environment binding confirmed accessible
- [x] Decision: **GO** for environment scoping

### Criterion 2 Validation
- [ ] Add cardinality trace to PredicateInverter.java:523
- [ ] Run test: `./mvnw test -Dtest=ScriptTest -Dmorel.script=such-that.smli`
- [ ] Capture stderr output
- [ ] Verify cardinality values logged
- [ ] Document result: PASS / FAIL / PARTIAL

### Criterion 3 Validation
- [ ] Add type trace to PredicateInverter.java:565
- [ ] Run test
- [ ] Verify type signatures match expectations
- [ ] Document result: PASS / FAIL / PARTIAL

### Criterion 4 Validation
- [ ] Add lambda trace to PredicateInverter.java:1682
- [ ] Run test
- [ ] Verify lambda signature is (bag * bag) -> bag
- [ ] Document result: PASS / FAIL / PARTIAL

### Criterion 5 Validation
- [ ] Add FROM trace to PredicateInverter.java:1783
- [ ] Run test
- [ ] Verify FROM has correct steps
- [ ] Document result: PASS / FAIL / PARTIAL

### Criterion 6 Validation
- [ ] Run full such-that.smli test
- [ ] Capture output
- [ ] Verify output is [(1,2),(2,3),(1,3)]
- [ ] Document result: PASS / FAIL / PARTIAL

### Criterion 7 Validation
- [ ] Run full test suite: `./mvnw test`
- [ ] Verify 159/159 tests pass
- [ ] Document result: PASS / FAIL / PARTIAL

### Post-Validation
- [ ] Remove debug trace statements
- [ ] Calculate final confidence percentage
- [ ] Make GO/PARTIAL-GO/NO-GO decision
- [ ] Document decision with evidence
- [ ] Update bead morel-fds

---

## Success Summary

**Minimum for GO**: 85% confidence (C1 through C5 all pass)

**Ideal for GO**: 98% confidence (C1 through C7 all pass)

**Timeline**:
- Validation execution: 2-4 hours
- Fix budget (if PARTIAL-GO): Up to 4 hours
- Total Phase 5a: 4-8 hours maximum

**Deliverable**: Updated PHASE-5A-SUCCESS-CRITERIA.md with execution results

---

## References

- PredicateInverter.java: Lines 126-148 (debug), 523-526 (cardinality), 551-578 (iterate), 1620-1683 (step function), 1701-1784 (step body)
- PHASE-5A-PRIME-RESULT.md: Environment scoping validation
- PHASE-5D-SCOPE-ANALYSIS.md: Code completeness assessment (80-90% complete)
- PHASE-5-REFINEMENT-PLAN.md: Full refinement execution plan
- such-that.smli: Lines 737-744 (test case)

---

**Status**: READY FOR EXECUTION
**Next Action**: Execute validation checklist criteria 2-7
**Bead**: morel-fds (close with validation results)
