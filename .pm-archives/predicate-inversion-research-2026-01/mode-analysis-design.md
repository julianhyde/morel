# Mode Analysis Research and Design Document

**Project**: Morel Predicate Inversion (Phase 3)
**Date**: 2026-01-24
**Author**: deep-research-synthesizer (Phase 3 Agent ab504b8)
**Version**: 1.0

---

## Executive Summary

### Overview of Mode Analysis Approach

Mode Analysis is a technique from logic programming (primarily Mercury and Datalog) where predicates declare which arguments are inputs (bound before call) versus outputs (computed by call). The compiler uses these mode declarations to determine execution order and optimize performance.

### Viability Assessment: LOW CONFIDENCE

**Verdict**: Mode Analysis is **NOT RECOMMENDED** for Morel.

**Reasoning**:
1. **Fundamental incompatibility** with SML's first-class functions
2. **No proven solution** for the deferred evaluation problem
3. **Breaking changes** required to SML's type system
4. **Higher implementation complexity** than alternatives (2-3x more work)
5. **Evidence from Flix**: Modern functional-logic languages use explicit fixpoint computation, not mode analysis

### Key Advantages and Disadvantages

**Advantages**:
- ✅ Mature theory from logic programming research
- ✅ Efficient execution when applicable (compile-time optimization)
- ✅ Clear declarative semantics
- ✅ Proven in Mercury for Prolog-style predicates

**Disadvantages**:
- ❌ Incompatible with first-class functions (CRITICAL BLOCKER)
- ❌ Requires compile-time predicate identity (impossible in SML)
- ❌ No solution for runtime data dependency (deferred evaluation)
- ❌ Needs mode annotations (breaks SML compatibility)
- ❌ Cannot handle partial application or higher-order predicates
- ❌ Complex implementation with high risk

---

## Critical Validation Questions - ANSWERED

### Q1: Can mode inference work for first-class functions?

**Answer: NO**

**Evidence**:
1. Mercury requires compile-time mode knowledge
2. SML allows runtime predicate selection and partial application
3. Mode Analysis assumes predicate identity known at compile time
4. First-class functions break this assumption

### Q2: Are Mercury's modes compatible with SML's type system?

**Answer: NO**

**Incompatibilities**:
- Mode syntax not present in SML type system
- Would require breaking changes to language
- Would need mode inference pass (new subsystem)
- Integration with existing polymorphic types is complex

### Q3: What patterns CAN mode analysis handle?

**CAN Handle**:
- Direct predicate calls with known bindings
- Simple base cases
- Deterministic predicates with single mode

**CANNOT Handle**:
- First-class predicates
- Partial application
- Predicates in data structures
- Higher-order predicates without annotations
- Runtime data dependencies

### Q4: Is automatic mode inference feasible or require manual annotations?

**Answer: Manual annotations required**

- Simple cases can be inferred
- Complex cases need manual @mode annotations
- Breaking SML compatibility (annotations everywhere)

### Q5: Does Mode Analysis converge with LinkCode/Core.Apply (Option 3)?

**Answer: NO - They diverge fundamentally**

**Evidence**:
- Flix (modern functional-logic language) chose explicit fixpoint, NOT mode analysis
- This represents fundamental architectural difference
- Mode Analysis ≠ LinkCode Extension

---

## Literature Research Synthesis

### Mercury Mode System

**Key Concepts**:
- Modes: `in` (input), `out` (output), `di` (destructive), `uo` (unique)
- Determinism: `det`, `semidet`, `multi`, `nondet`, `cc_multi`, `cc_nondet`
- Mode declarations in type signatures
- Higher-order mode support with strict requirements

**Critical Limitation**: Cannot handle first-class predicates without compile-time knowledge.

### Datalog Magic Sets Transformation

**Key Concepts**:
- Adornments: Binding pattern notation (b=bound, f=free)
- Program specialization: Generate adorned versions of rules
- Semi-naive evaluation: Bottom-up fixpoint computation
- Still has first-class function problem

### Curry Narrowing & Residuation

**Key Concepts**:
- Narrowing: Instantiate free variables to make progress
- Residuation: Suspend evaluation until variables bound
- Can handle first-class predicates but high runtime overhead

### Flix Language (CRITICAL)

**Key Finding**: Flix does **NOT** use mode analysis. Instead:
```flix
let rules = #{
    Path(x, y) :- Edge(x, y).
    Path(x, z) :- Path(x, y), Edge(y, z).
};
query facts, rules select (x, y) from Path(x, y)
```

This is explicit fixpoint computation (like Morel's Relational.iterate).

---

## Deferred Evaluation Mechanism Design

### Problem Statement

Mode Analysis identifies that `path(IN, OUT)` can generate y from x. But:
1. **Compile time**: Know the mode, don't have edges collection
2. **Runtime**: Have edges, mode info lost in Core IR
3. **First-class**: Predicate might be runtime-selected value

### Three Design Options Evaluated

#### Option 3A: Mode Analysis → Core.Apply Generation
- **Problem**: Cannot generate Core.Exp that references runtime data

#### Option 3B: Mode-Aware Extent with Runtime Deferral
- **Problem**: Function body lost by execution time

#### Option 3C: Hybrid Mode Hints + Runtime Validation
- **Problem**: No fallback when runtime validation fails

### Conclusion: No Proven Deferred Evaluation Mechanism

All three options fail due to fundamental mismatch between compile-time mode analysis and runtime data access.

---

## Implementation Estimate

### Minimal Implementation (Transitive Closure Only)

**Estimated Effort**:
- Design: 2-3 days
- Mode inference: 3-5 days
- Integration: 2-3 days
- Testing: 2-3 days
- Total: 14-22 days, **50% confidence**

**Key Blockers**:
1. Deferred evaluation mechanism unvalidated (may not be solvable)
2. First-class function incompatibility (fundamental blocker)
3. Type system integration complexity

### Full Implementation

**Estimated Effort**:
- Additional components: 41-59 days
- **Total: 55-81 days, 30% confidence**

### Risk Assessment

**High-Risk Items**:
- CRITICAL: First-class function incompatibility
- HIGH: Deferred evaluation mechanism unproven
- HIGH: Type system integration
- MEDIUM: Performance overhead

---

## Conclusion

### Should We Pursue Mode Analysis?

**NO** - Mode Analysis is not recommended for Morel due to:

1. **Fundamental incompatibility** with first-class functions
2. **No proven solution** for deferred evaluation
3. **Breaking changes** to SML semantics
4. **Higher complexity** than LinkCode/Core.Apply approach
5. **Evidence from Flix**: Explicit fixpoint is the right pattern

### Recommendation for Phase 5

**Pursue Option 3 (LinkCode/Core.Apply)** with high confidence:
- Lower implementation complexity
- Compatible with SML semantics
- Proven pattern (Flix, existing Relational.iterate)
- Works with first-class functions
- No new syntax or type system changes

Mode Analysis remains interesting for future optimization but should NOT be primary mechanism.

---

**Status**: Phase 3 Research Complete ✅
**Confidence**: HIGH
**Recommendation**: Do NOT pursue Mode Analysis
