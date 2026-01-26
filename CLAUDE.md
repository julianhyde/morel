# Claude Development Notes

## Predicate Inversion Implementation (In Progress)

### Goal
Enable Morel to invert boolean predicates (including recursive functions) into generator expressions. This allows queries like:

```sml
fun path (x, y) = edge (x, y) orelse
  (exists z where edge (x, z) andalso path (z, y))

from p where path p
(* Should return: [(1,2),(2,3),(1,3)] *)
```

### Current Status

#### Completed
1. **PredicateInverter stub class** (`src/main/java/net/hydromatic/morel/compile/PredicateInverter.java`)
   - Basic structure with `invert()` method
   - Handles simple `elem` pattern inversion
   - Returns `Optional<InversionResult>` (empty on failure)

2. **InversionResult class** - Contains:
   - `generator` - the generated expression
   - `mayHaveDuplicates` - whether duplicates might occur
   - `isSupersetOfSolution` - whether additional filtering is needed
   - `satisfiedPats` - which variables are actually produced
   - `satisfiedFilters` - which filters were incorporated

3. **Comprehensive test suite** (`src/test/java/net/hydromatic/morel/compile/PredicateInverterTest.java`)
   - Test 1: `x elem myList` → `myList`
   - Test 2: `String.isPrefix s "abcd"` → `["","a","ab","abc","abcd"]`
   - Test 3: Composite function with exists (joins)
   - Test 4: `edge(x,y)` function call inversion
   - Test 5: `x > y andalso x < y + 10` to generate y when x is [3,5,7]
   - Test 6: `x > y andalso x < y + 10` to generate x when y is given
   - Test 7: Uninvertible predicates return empty (PASSING ✓)

4. **Hook in Extents.g3** (line 538)
   - Detects user-defined function calls (`Op.ID`)
   - Ready to call PredicateInverter

5. **Fixed dummy.smli**
   - Changed `from p in path` to `from p where path p`

### What Needs to Be Implemented

#### Priority 1: Basic Recursive Inversion

Complete `PredicateInverter.invert()` to handle the transitive closure pattern:

```java
// In PredicateInverter.java, around line 110
if (binding != null && binding.value instanceof Core.Exp) {
  Core.Exp fnBody = (Core.Exp) binding.value;
  if (fnBody.op == Op.FN) {
    Core.Fn fn = (Core.Fn) fnBody;

    // Check if this is a recursive function
    // Pattern: baseCase orelse recursiveCase
    if (isTransitiveClosurePattern(fn.exp)) {
      return invertTransitiveClosure(fn, apply.arg, goalPats);
    }
  }
}
```

**Algorithm for Transitive Closure:**

1. **Detect pattern:**
   - Function body is `baseCase orelse recursiveCase`
   - `baseCase` is directly invertible (e.g., `edge(x,y)`)
   - `recursiveCase` contains a recursive call with `exists`

2. **Extract base case:**
   - Invert the base case to get initial generator
   - Example: `edge(x,y)` inverts to `edges` collection

3. **Build iteration step:**
   - Extract the pattern from recursive case
   - Example: `edge(x,z) andalso path(z,y)`
   - Build join expression

4. **Generate `Relational.iterate` call:**

   **Exact expression to generate:**

   For `fun path (x, y) = edge (x, y) orelse (exists z where edge (x, z) andalso path (z, y))`:

   ```sml
   Relational.iterate [(1, 2), (2, 3)]
     (fn (oldPaths, newPaths) =>
       from (x, z) in [(1, 2), (2, 3)],
            (z2, y) in newPaths
         where z = z2
         yield (x, y))
   ```

   **General pattern:**
   ```sml
   Relational.iterate (baseGenerator)
     (fn (old, new) =>
       from (x, z) in baseGenerator,
            (z2, y) in new
         where z = z2
         yield (x, y))
   ```

   Where `baseGenerator` is the inversion of the base case.

   **Java code to generate this:**
   ```java
   // Pseudo-code for result:
   Core.Exp baseGenerator = invertBaseCase(...);
   Core.Exp stepFn = buildStepFunction(...);
   Core.Exp result = core.apply(
       typeSystem,
       core.apply(typeSystem,
           core.functionLiteral(typeSystem, BuiltIn.RELATIONAL_ITERATE),
           baseGenerator),
       stepFn);
   ```

#### Priority 2: Wire into Extents

In `Extents.java` line 538-541, replace the TODO with:

```java
case ID:
  // User-defined function call - attempt to invert
  PredicateInverter inverter = new PredicateInverter(typeSystem, env);
  Optional<PredicateInverter.InversionResult> inversionOpt =
      inverter.invert(apply, goalPats, boundPats);

  if (inversionOpt.isPresent()) {
    PredicateInverter.InversionResult inversion = inversionOpt.get();
    for (Core.NamedPat pat : inversion.satisfiedPats) {
      map.computeIfAbsent(pat, p -> PairList.of())
          .add(inversion.generator, apply);
    }
  }
  break;
```

#### Priority 3: Handle Complex Cases

1. **Conjunction inversion** (`andalso`)
   - Identify which variables each conjunct can generate
   - Greedy algorithm to order generators
   - Build joins when needed

2. **Range constraints**
   - Invert `x > y andalso x < y + 10`
   - Generate using `List.tabulate(count, fn k => base + k)`
   - Handle both forward and reverse (x given y, or y given x)

3. **Built-in function inversion**
   - `String.isPrefix s "abcd"` → generate all prefixes
   - Other invertible built-ins

### Testing Strategy

1. **Run dummy.smli test:**
   ```bash
   ./mvnw test -Dtest=ScriptTest
   ```
   Expected: `[(1,2),(2,3),(1,3)]`

2. **Run PredicateInverterTest:**
   ```bash
   ./mvnw test -Dtest=PredicateInverterTest
   ```
   Currently: 5 failures, 1 error, 1 pass (uninvertible test)

3. **Run such-that.smli:**
   - Line 402: `from p where path p`
   - Should produce transitive closure

### Key Design Decisions

1. **Use `Optional<InversionResult>`** instead of success flag
   - Cleaner API
   - Forces handling of failure cases

2. **Use `List.tabulate` for ranges**
   - Standard SML approach
   - More functional than recursive range
   - Example: `List.tabulate(9, fn k => x - 9 + k)`

3. **Memoization for recursion**
   - Track recursive calls in `recursionStack`
   - Cache results in `memo` map
   - Detect infinite recursion

4. **Extent metadata**
   - `mayHaveDuplicates`: true for recursive predicates
   - `isSupersetOfSolution`: true if we need to filter afterward
   - `satisfiedFilters`: which filters are incorporated into generator

### References

- **Design doc:** `PREDICATE_INVERSION_DESIGN.md`
- **Test cases:** `src/test/resources/script/such-that.smli`
- **Existing iterate usage:** `src/test/resources/script/regex-example.smli` line 308
- **Related issue:** #217 (predicate inversion)

### Useful SML Patterns

**Relational.iterate signature:**
```sml
(* 'a bag -> ('a bag * 'a bag -> 'a bag) -> 'a bag *)
(* Repeatedly applies function until fixpoint (no new elements) *)
```

**Example usage:**
```sml
fun trans edges =
  Relational.iterate edges
    fn (oldEdges, newEdges) =>
      from (i, j) in newEdges,
           (j2, k) in edges
        where j = j2
        yield (i, k)
```

**List.tabulate for ranges:**
```sml
(* Generate [3, 4, 5, 6] *)
List.tabulate (4, fn i => 3 + i)

(* Generate y values when x is known *)
from x in [3,5,7],
     y in List.tabulate(9, fn k => x - 9 + k)
  yield y
```

### Next Steps

1. Implement `isTransitiveClosurePattern()` helper
2. Implement `invertTransitiveClosure()` method
3. Wire into `Extents.g3` at line 538
4. Test with dummy.smli
5. Expand to handle more complex patterns
6. Add support for `andalso` and range constraints
