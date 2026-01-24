# Phase 2: Performance Criteria for Transitive Closure Optimization

**Phase Status**: ACTIVE - Starting Phase 2 (1 day)
**Date Started**: 2026-01-24
**Expected Completion**: 2026-01-24

---

## Objective

Establish objective, quantitative thresholds for when cartesian product fallback is acceptable vs when optimization (Phases 3-5) is required.

---

## 2.1: Input Size Thresholds

### Category Definition

Based on algorithm complexity analysis:
- Transitive closure is O(V × E) for sparse graphs, O(V³) worst case
- Cartesian product grows as O(E²) without optimization
- Practical threshold: 500 ms for interactive queries

| Category | Edge Count | Vertex Count | Operations | Cartesian Product Time | Threshold |
|----------|------------|--------------|------------|----------------------|-----------|
| **Small** | < 100 | < 50 | < 10,000 | 10-50 ms | ✅ Acceptable (< 200ms) |
| **Medium** | 100-1,000 | 50-500 | 10,000-1M | 100-500 ms | ⚠️ Borderline (200-500ms) |
| **Large** | > 1,000 | > 500 | > 1,000,000 | > 1s | ❌ Optimization Required (> 500ms) |

### Rationale

**Small Graphs** (< 100 edges):
- Cartesian product is acceptable for interactive use
- Phase 1 fallback (cartesian product) sufficient
- No optimization needed

**Medium Graphs** (100-1,000 edges):
- Cartesian product borderline - depends on system load
- May benefit from optimization
- Decision point: if baseline > 500ms, optimize

**Large Graphs** (> 1,000 edges):
- Cartesian product inadequate
- Optimization strongly recommended
- Optimization is critical requirement

### Note on Phase 1.5 Baseline

Phase 1.5 could not complete actual baseline measurements due to architectural blocker (transitive closure test fails). Thresholds above are based on:
1. Algorithm complexity analysis (O(V×E) vs O(E²))
2. Typical graph sizes in relational queries
3. Interactive latency expectations (< 100-500ms)

These thresholds are conservative (favor optimization) and can be refined with actual measurements after Phases 2-5 are complete.

---

## 2.2: Latency Acceptance Criteria

### Use Case-Specific Thresholds

| Use Case | Context | Acceptable Latency | Optimization Triggered |
|----------|---------|-------------------|------------------------|
| **Interactive Query** | REPL, ad-hoc analysis | < 100 ms | > 200 ms |
| **Batch Processing** | Reporting, data export | < 1 s | > 2 s |
| **Analytics Workload** | Background jobs, overnight runs | < 10 s | > 30 s |
| **Development/Testing** | Test suite execution | < 500 ms | > 1 s |

### Decision Trigger

```
IF transitive_closure_latency > 500 ms THEN optimization_justified = true
IF transitive_closure_latency < 200 ms THEN optimization_optional = true
IF transitive_closure_latency < 100 ms THEN optimization_unnecessary = true
```

---

## 2.3: Benchmark Test Harness Design

### Test Infrastructure

**File**: `src/test/java/net/hydromatic/morel/compile/TransitiveClosureBenchmark.java`

### Test Cases

#### Test 1: Small Graph (20 edges, 10 vertices)
```sml
val small_edges = [(1,2), (2,3), (1,3), (3,4), (4,5), (2,5), (5,6), (1,6),
                   (6,7), (7,8), (3,8), (2,8), (4,6), (1,5), (3,6),
                   (7,9), (8,9), (6,8), (9,10), (5,10)];

fun small_path (x, y) = (x, y) elem small_edges orelse
  (exists z where small_path (x, z) andalso (z, y) elem small_edges);

(* Measure: from p where small_path p *)
Expected: ~20-100 results, < 100 ms
```

#### Test 2: Medium Graph (500 edges, 100 vertices)
```sml
(* Generated: 100-vertex graph with random edges *)
val medium_edges = [(1,2), (1,3), ..., (99,100)];  (* 500 edges *)

fun medium_path (x, y) = (x, y) elem medium_edges orelse
  (exists z where medium_path (x, z) andalso (z, y) elem medium_edges);

Expected: ~500-5000 results, 200-500 ms (borderline)
```

#### Test 3: Large Graph (5000 edges, 500 vertices)
```sml
(* Generated: 500-vertex graph with dense connectivity *)
val large_edges = [...];  (* 5000 edges *)

fun large_path (x, y) = (x, y) elem large_edges orelse
  (exists z where large_path (x, z) andalso (z, y) elem large_edges);

Expected: > 10,000 results, > 1s (optimization needed)
```

### Measurement Protocol

For each test case:
1. **Warm-up**: Execute 2 times to stabilize JIT
2. **Measurements**: Execute 5 times, record each execution time
3. **Calculate**: Average, Min, Max, StdDev
4. **Memory**: Record heap before/after

```
Results format:
Test Case: medium_graph
Executions: [245ms, 267ms, 251ms, 258ms, 263ms]
Average: 256.8 ms
Min: 245 ms
Max: 267 ms
StdDev: 9.2 ms
Memory Delta: +2.3 MB
Result Count: 1,847 tuples
```

---

## 2.4: Decision Trigger Points

### Optimization Necessity Matrix

| Measurement | Decision |
|------------|----------|
| **small_avg < 100 ms** | ✅ NO optimization needed |
| **small_avg 100-200 ms** | ⚠️ Monitor, consider optimizing |
| **small_avg > 200 ms** | 🔴 Investigate why small graph slow |
| **medium_avg < 200 ms** | ✅ NO optimization needed |
| **medium_avg 200-500 ms** | ⚠️ BORDERLINE - Optimization optional |
| **medium_avg > 500 ms** | 🔴 Optimization REQUIRED |
| **large_avg < 2 s** | ⚠️ Acceptable, but optimization helps |
| **large_avg > 5 s** | 🔴 Optimization CRITICAL |

### Primary Trigger

```
PRIMARY_DECISION_GATE:
IF medium_benchmark_avg > 500ms THEN
    status = "OPTIMIZATION_REQUIRED"
    proceed_with_phases_3_5 = true
ELSE IF medium_benchmark_avg < 200ms THEN
    status = "OPTIMIZATION_UNNECESSARY"
    skip_phases_3_5 = true
ELSE
    status = "BORDERLINE"
    research_options_2_5_anyway = true
```

---

## 2.5: Integration with Phases 3-4

### Deliverables for Options Prototyping

Phase 2 produces:
1. ✅ **Performance Criteria Document** (this file)
2. ✅ **Benchmark Test Harness** (Java class)
3. ✅ **Concrete Decision Thresholds** (numerical criteria)
4. ✅ **Test Cases** (3 graph sizes)

### Usage in Phases 3-4

**Option 2 (Mode Analysis) Prototype**:
- Run against benchmark test harness
- Measure: latency improvement vs cartesian product
- Compare to thresholds

**Option 3 (LinkCode Pattern) Prototype**:
- Run against same benchmark test harness
- Measure: latency improvement vs cartesian product
- Compare to thresholds

**Phase 5 Decision**:
- Both prototypes evaluated using same criteria
- Which meets thresholds better?
- Which is more maintainable?

---

## Success Criteria

- [x] Input size thresholds defined (small/medium/large)
- [x] Latency criteria defined by use case
- [x] Benchmark test harness design documented
- [x] Decision trigger points specified numerically
- [x] Integration plan with Phases 3-4 documented
- [x] Conservative thresholds favor optimization research

**Status**: Phase 2 Complete ✅

---

## Notes for Phases 3-4

### When Running Option 2 (Mode Analysis) Prototype

```
// In TransitiveClosureBenchmark.java
@Test
void testModeAnalysisOption2() {
    // Run small, medium, large benchmarks
    // Compare results to PERFORMANCE_CRITERIA thresholds
    // Document: Does Mode Analysis meet thresholds?
    // Document: How much improvement vs cartesian product?
}
```

### When Running Option 3 (LinkCode Pattern) Prototype

```
// In TransitiveClosureBenchmark.java
@Test
void testLinkCodePatternOption3() {
    // Run small, medium, large benchmarks
    // Compare results to PERFORMANCE_CRITERIA thresholds
    // Document: Does LinkCode Pattern meet thresholds?
    // Document: How much improvement vs cartesian product?
}
```

### Phase 5 will compare:
- Which option meets thresholds?
- Which is faster overall?
- Which is easier to maintain?
- Which integrates better with existing code?

---

**Document Created**: 2026-01-24
**Phase**: 2 (Define Performance Criteria)
**Status**: COMPLETE
**Next**: Phase 3-4 (Research Options 2-3 in parallel)
