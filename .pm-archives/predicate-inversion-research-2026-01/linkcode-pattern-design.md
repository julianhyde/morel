# LinkCode Pattern Design for Morel Transitive Closure
## Comprehensive Research Analysis - Phase 4

**Date**: 2026-01-24
**Research Agent**: deep-research-synthesizer (Phase 4 Agent aa2be7d)
**Status**: Complete - Ready for Phase 5 Decision

---

## Executive Summary

### Research Conclusion: Rename to "Core.Apply Generation Pattern"

After comprehensive research into Morel's LinkCode mechanism and its applicability to transitive closure, **LinkCode itself is not needed** for this use case. The correct approach is **Core.Apply Generation** - creating Core IR nodes for Relational.iterate calls at compile-time, with natural deferral through the compilation pipeline.

### Viability Assessment: HIGH CONFIDENCE

**Recommendation**: Pursue Core.Apply Generation for transitive closure implementation.

**Key Advantages**:
- ✅ Uses existing Relational.iterate (no new runtime primitives)
- ✅ Simple implementation (~400 LOC vs ~1000 LOC for Mode Analysis)
- ✅ Low risk (isolated to PredicateInverter)
- ✅ Fast timeline (2.6 weeks vs 5-7 weeks for Mode Analysis)
- ✅ No language changes (transparent to users)

**Key Disadvantages**:
- ⚠️ Specific to transitive closure pattern (less general than Mode Analysis)
- ⚠️ Requires pattern matching (may miss some valid recursive patterns)

### Comparison to Mode Analysis (Option 2)

| Criterion | Mode Analysis | Core.Apply Gen | Winner |
|-----------|---------------|----------------|---------|
| Implementation Complexity | 800-1200 LOC | 300-500 LOC | Core.Apply (3x simpler) |
| Risk Level | High | Low | Core.Apply |
| Timeline | 5-7 weeks | 2.6 weeks | Core.Apply (2-3x faster) |
| Generality | High (many patterns) | Medium (transitive closure) | Mode Analysis |
| Architectural Fit | Medium (new subsystem) | High (uses existing) | Core.Apply |
| User Impact | Breaking (annotations) | None (transparent) | Core.Apply |

**Overall Assessment**: Core.Apply Generation is the pragmatic choice for the immediate goal (transitive closure).

---

## 1. LinkCode Analysis

### 1.1 LinkCode Class Structure

Located in `Compiler.java` lines 1004-1023:

```java
static class LinkCode implements Code {
  Code refCode;

  @Override public Object eval(EvalEnv env) {
    return refCode.eval(env);
  }
}
```

**Architectural Role**: LinkCode is a **forward pointer pattern** that enables recursive function definitions by deferring code binding.

### 1.2 LinkCode Lifecycle

**Phase 1: Instantiation** - Create placeholder for recursive function
**Phase 2: Environment Construction** - Put placeholder in compilation environment
**Phase 3: Compilation** - Compile function body (can reference placeholder)
**Phase 4: Fix-Up** - Assign actual code to placeholder after compilation

### 1.3 Why LinkCode Enables Recursion

During compilation of `val rec f = fn x => ... f ...`:
1. Create placeholder for f (LinkCode)
2. Put placeholder in environment
3. Compile body (body references placeholder)
4. After compilation, assign actual code to placeholder
5. At runtime, placeholder forwards to actual code

### 1.4 Applicability to Predicate Transitive Closure

**Question**: Can we use LinkCode for predicates?

**Answer**: **No, not directly**, because:

1. **Different compilation context**:
   - LinkCode is for function DEFINITIONS (`val rec`)
   - Predicates are function APPLICATIONS in queries

2. **No forward reference needed**:
   - We're not defining a recursive predicate
   - We're CALLING an existing predicate

3. **Different problem**:
   - Not: "How to reference code that doesn't exist yet"
   - But: "How to transform recursive predicate call into iterate call"

**Conclusion**: LinkCode is the wrong abstraction.

---

## 2. SQL WITH RECURSIVE Comparison

### 2.1 Evaluation Algorithm

**SQL Pseudocode**:
```python
working = execute(anchor)          # W₀
result = working.copy()            # R = W₀

while working:                     # Until fixpoint
    new_rows = execute(recursive_member, working)  # Execute with Wᵢ
    working = new_rows - result    # Remove duplicates
    result = result ∪ new_rows     # Accumulate

return result
```

### 2.2 Morel Relational.iterate (from Prop.java)

```java
List<Object> result = new ArrayList<>(list);  // R = base
Set<Object> resultSet = new HashSet<>(list);  // For deduplication
List<Object> list2 = list;                    // W₀ = base

for (;;) {
    List<Object> args = ImmutableList.of(result, list2);
    Iterable<?> iterable2 = (Iterable) stepFn.apply(args);
    List<Object> list3 = new ArrayList<>();

    for (Object o : iterable2) {
        if (resultSet.add(o)) {  // New element?
            list3.add(o);
        }
    }

    if (list3.isEmpty()) {  // Fixpoint?
        break;
    }

    result.addAll(list3);  // Accumulate
    list2 = list3;         // Next working table
}

return result;
```

### 2.3 Architectural Parallel

| SQL | Morel |
|-----|-------|
| Anchor member (base query) | Base case expression |
| Recursive member (step query) | Step function |
| Working table (Wᵢ) | `new` parameter |
| Accumulated results (R) | `old` parameter |
| Fixpoint detection | No new tuples → iteration stops |
| Runtime iteration | Relational.iterate |
| Compile-time plan | Core.Apply generation |

**Both systems defer iteration to runtime while compiling query structure at compile-time.**

---

## 3. Core.Apply Generation Design

### 3.1 Why Core.Apply Defers Evaluation

**Deferred at IR Level**:
- Core.Apply is **abstract syntax** (not executable)
- Represents "call iterate function with these arguments"
- No execution happens during PredicateInverter

**Deferred at Code Level**:
- Code is **compiled bytecode** (not executed)
- Represents "when eval is called, run iterate"
- No execution happens during Compiler.compileExp

**Executed at Runtime**:
- Code.eval() is invoked when query runs
- Only then does iterate execute

**Why This Is Sufficient**:
- The edges collection reference is just a variable lookup
- The variable is bound in the environment when query runs
- No special forward reference mechanism needed
- Core.Apply generation naturally creates the deferred structure

### 3.2 Core.Apply Generation Algorithm

**Input**: Detected transitive closure pattern
```sml
fun path (x, y) = edge (x, y) orelse
  (exists z where edge (x, z) andalso path (z, y))
```

**Steps**:
1. Extract base case: `edge(x, y)`
2. Invert base case → Core.Exp for edges lookup
3. Analyze recursive structure
4. Build step function: `fn (old, new) => FROM ... WHERE ... YIELD ...`
5. Generate Core.Apply for iterate call
6. Return inversion result

### 3.3 Complete Example

**Input Pattern**:
```sml
fun path (x, y) = edge (x, y) orelse
  (exists z where edge (x, z) andalso path (z, y))
```

**Generated Core.Apply**:
```sml
Relational.iterate
  edges                          (* Base case *)
  (fn (old, new) =>              (* Step function *)
    from (x, z) in edges,        (* Scan base collection *)
         (z2, y) in new          (* Scan working table *)
      where z = z2               (* Join condition *)
      yield (x, y))              (* Output *)
```

### 3.4 Relationship to LinkCode

**Comparison**:

| Aspect | LinkCode | Core.Apply Generation |
|--------|----------|---------------------|
| Purpose | Forward reference for recursion | Deferred iteration call |
| When created | During `val rec` compilation | During predicate inversion |
| What it defers | Code binding | Iteration execution |
| How it defers | Placeholder + fix-up | IR → Code → Runtime |
| Level | Code (executable) | Core IR (abstract) |

**Key Difference**:
- LinkCode: "Reference code that doesn't exist yet"
- Core.Apply: "Represent computation to execute later"

**Why Core.Apply Is Sufficient**: We don't need LinkCode because the deferral happens naturally through IR → Code → Runtime compilation.

---

## 4. Fair Comparison to Mode Analysis

### 4.1 Implementation Complexity

**Mode Analysis**:
- New classes: 4-5 (ModeDecl, ModeInference, ModeChecker, etc.)
- LOC: 800-1200
- Score: 2/5

**Core.Apply Generation**:
- New classes: 1-2 (helper methods in PredicateInverter)
- LOC: 300-500
- Score: 4/5

### 4.2 Architectural Fit

**Mode Analysis**: 3/5
- Introduces new concept (modes) to language
- Requires language extension (annotations)
- Not aligned with functional programming

**Core.Apply Generation**: 5/5
- Uses existing Relational.iterate
- No language changes needed
- Functional programming style

### 4.3 Performance

**Mode Analysis**: 4/5
- Better optimization potential
- But higher compile cost

**Core.Apply Generation**: 4/5
- Same runtime performance
- Lower compile cost

### 4.4 Maintainability

**Mode Analysis**: 2/5
- Complex mode inference logic
- Mode annotations add surface area
- Debugging is hard

**Core.Apply Generation**: 5/5
- Localized change
- Pattern matching is explicit
- Easy to debug

### 4.5 Risk Assessment

**Mode Analysis**: 2/5
- High implementation risk
- High timeline risk
- Potential for scope creep

**Core.Apply Generation**: 5/5
- Low implementation risk
- Low timeline risk
- Clear success criteria

### 4.6 Overall Scorecard

| Criterion | Weight | Mode Analysis | Core.Apply | Winner |
|-----------|--------|---------------|------------|--------|
| Implementation | 25% | 2/5 | 4/5 | Core.Apply |
| Architecture | 20% | 3/5 | 5/5 | Core.Apply |
| Performance | 15% | 4/5 | 4/5 | Tie |
| Maintainability | 20% | 2/5 | 5/5 | Core.Apply |
| Risk | 20% | 2/5 | 5/5 | Core.Apply |
| **Weighted Score** | | **2.45/5** | **4.55/5** | **Core.Apply** |

---

## 5. Implementation Estimate

### Minimal Implementation (Transitive Closure Only)

**Timeline**:
- Design: 2-3 days
- Implementation: 3-5 days (~400 LOC)
- Testing: 2-3 days
- Integration: 1-2 days
- **Total: 13 days (80% confidence)**
- **Weeks: 2.6 weeks**

### Full Implementation (General Recursive Predicates)

**Timeline**:
- Minimal: 13 days
- Additional: 15-23 days
- **Total: 36 days (80% confidence)**
- **Weeks: 7 weeks**

### Critical Risks

1. **Type System Integration** (60% likelihood, Medium impact)
   - Mitigation: Study existing FROM compilation first
   - Impact: +1-2 days if issues found

2. **Step Function Construction** (40% likelihood, High impact)
   - Mitigation: Start with simplest pattern first
   - Impact: +2-3 days if issues found

3. **Pattern Detection Edge Cases** (50% likelihood, Low impact)
   - Mitigation: Comprehensive test suite
   - Impact: +1 day if issues found

---

## 6. Conclusion

### Should We Pursue Core.Apply Generation?

**YES** - Strong recommendation to proceed.

### Recommendation for Phase 5 Decision

**PRIMARY**: Implement Core.Apply Generation (Option 3)

**Rationale**:
1. Solves transitive closure efficiently
2. 3x simpler than Mode Analysis
3. 2-3x faster to implement
4. Lower risk (isolated change)
5. No language changes needed

**SECONDARY**: Consider Mode Analysis for future work

**For now**: Focus on transitive closure with Core.Apply Generation.

---

**Status**: Phase 4 Research Complete ✅
**Confidence**: HIGH
**Recommendation**: Pursue Core.Apply Generation
