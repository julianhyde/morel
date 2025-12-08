# Plan: Implement Value Structure for Embedded Language Interoperability (Issue #324)

## Overview

Implement a universal value representation mechanism to facilitate returning results from embedded languages (such as Soufflé Datalog from Issue #323) back to the Morel system. This involves creating a `value` datatype with corresponding `VALUE` signature and structure implementation.

## Goals

1. Create a `value` datatype that can represent all Standard ML value types
2. Implement a `VALUE` signature with `parse` and `print` functions
3. Implement a `Value` structure that provides bidirectional conversion between strings and values
4. Enable embedded languages to return results in a universal format that Morel can consume

## Architecture

### Value Datatype

The `value` datatype encompasses:
- **Primitives**: `UNIT`, `BOOL`, `INT`, `REAL`, `CHAR`, `STRING`
- **Collections**: `LIST`, `BAG`, `VECTOR`
- **Option types**: `OPTION` for optional values
- **Structured data**: `RECORD` for named fields and tuples (using numeric labels "1", "2", etc.)
- **User-defined types**: `DATATYPE` constructor pairing a name with a payload value

### Implementation Strategy

Since Morel is implemented in Java, we need to:
1. Define the `value` datatype in Standard ML (likely in a built-in library file)
2. Implement the corresponding Java representation
3. Add built-in functions for `parse` and `print` operations
4. Register the Value structure in the BuiltIn enum

## Implementation Tasks

### 1. Create Standard ML Definition

**Location**: `src/main/resources/value.sml` (or similar)

Create a file containing:
```sml
datatype value =
    UNIT
  | BOOL of bool
  | INT of int
  | REAL of real
  | CHAR of char
  | STRING of string
  | LIST of value list
  | BAG of value list
  | VECTOR of value list
  | OPTION of value option
  | RECORD of (string * value) list
  | DATATYPE of string * value

signature VALUE =
sig
  datatype value =
      UNIT
    | BOOL of bool
    | INT of int
    | REAL of real
    | CHAR of char
    | STRING of string
    | LIST of value list
    | BAG of value list
    | VECTOR of value list
    | OPTION of value option
    | RECORD of (string * value) list
    | DATATYPE of string * value

  val parse : string -> value
  val print : value -> string
end

structure Value :> VALUE =
struct
  datatype value = datatype value

  fun print v =
    case v of
        UNIT => "()"
      | BOOL b => Bool.toString b
      | INT i => Int.toString i
      | REAL r => Real.toString r
      | CHAR c => "#\"" ^ Char.toString c ^ "\""
      | STRING s => "\"" ^ String.toString s ^ "\""
      | LIST vs => "[" ^ String.concatWith ", " (List.map print vs) ^ "]"
      | BAG vs => "{" ^ String.concatWith ", " (List.map print vs) ^ "}"
      | VECTOR vs => "#[" ^ String.concatWith ", " (List.map print vs) ^ "]"
      | OPTION NONE => "NONE"
      | OPTION (SOME v) => "SOME " ^ print v
      | RECORD fields =>
          "{" ^ String.concatWith ", "
            (List.map (fn (k, v) => k ^ " = " ^ print v) fields) ^ "}"
      | DATATYPE (name, UNIT) => name
      | DATATYPE (name, v) => name ^ " " ^ print v

  fun parse s = ... (* TODO: implement parser *)
end
```

### 2. Add Java Type Representation

**Location**: `src/main/java/net/hydromatic/morel/type/ValueType.java` (new file)

Create a Java class to represent the `value` datatype in the type system, similar to how `DataType`, `ListType`, `RecordType`, etc. are implemented.

### 3. Implement Java Runtime Support

**Location**: `src/main/java/net/hydromatic/morel/eval/Value.java` (new file)

Create Java classes representing value instances at runtime:
- `ValueCode` - Code implementation for value operations
- `ValuePrinter` - Implementation of the `print` function (compact format)
- `ValueParser` - Implementation of the `parse` function

**Note**: Normal value display uses the existing `Pretty` class which already handles:
- Line width management and backtracking
- Indentation (using spaces)
- Depth control (`printDepth`, `printLength`, `stringDepth`)
- Pretty printing of lists, records, tuples, and datatypes
- Custom output formatting

### 4. Register Built-in Value Structure

**Location**: `src/main/java/net/hydromatic/morel/compile/BuiltIn.java`

Add entries to the `BuiltIn` enum for:
- The `value` datatype and its constructors
- The `VALUE` signature
- The `Value` structure
- The `parse` and `print` functions

### 5. Add Function Definitions to TOML

**Location**: `src/main/resources/functions.toml`

Add entries for:
```toml
[[functions]]
structure = "Value"
name = "parse"
type = "string → value"
prototype = "parse s"
description = """
parses a string representation into a value.
"""

[[functions]]
structure = "Value"
name = "print"
type = "value → string"
prototype = "print v"
description = """
converts a value to its compact string representation.
"""
```

### 6. Implement Parser

The `parse` function is the most complex part. Consider:
- Using existing Morel parser infrastructure
- Implementing a simple recursive descent parser
- Handling all value types correctly
- Error handling strategy (return `value option` or raise exception?)

### 7. Add Tests

**Location**: `src/test/resources/script/value.sml` and corresponding `.out` file

Create comprehensive tests covering:
- All primitive types (UNIT, BOOL, INT, REAL, CHAR, STRING)
- Collection types (LIST, BAG, VECTOR)
- OPTION types (NONE and SOME)
- RECORD types (both named fields and tuples)
- DATATYPE constructors
- Round-trip testing (parse ∘ print = id)
- Edge cases (empty collections, nested structures, etc.)

### 8. Update Documentation

**Location**: `docs/reference.md`

Add documentation for:
- The `value` datatype
- The `VALUE` signature
- The `Value` structure
- Usage examples

## Open Questions

1. **Error Handling**: Should `parse` return `value option` or raise an exception on parse errors?
   - Option type is more functional and allows graceful error handling
   - Exception provides more detailed error messages

2. **Nullary Constructors**: How should nullary datatype constructors be represented?
   - Current plan: `DATATYPE (name, UNIT)`
   - Alternative: Special case in the datatype definition

3. **Type Information**: Should values carry type information?
   - Current plan: No, types are inferred separately
   - Pro: More efficient for homogeneous collections
   - Con: Cannot determine type from value alone

4. **String Escaping**: How to handle special characters in STRING values?
   - Use Standard ML escape sequences
   - Ensure `parse ∘ print = id` for all valid strings

5. **Pretty Print Formatting**: `prettyPrint` will use the existing `Pretty` class logic
   - Will respect Morel's existing print control properties (from `Prop.Output`)
   - Line width, print depth, print length, and string depth already configurable
   - Existing indentation and backtracking logic will be reused
   - Question: Should `prettyPrint` use default settings or allow configuration?

## Integration with Embedded Languages

Once implemented, embedded languages (like Soufflé Datalog) can:
1. Convert their results to the `value` representation
2. Return them as strings using the canonical format
3. Morel can parse these strings back into values
4. Values can be used in further Morel computations

## Success Criteria

- [ ] All value types can be constructed
- [ ] `print` function correctly stringifies all value types in compact form
- [ ] `parse` function correctly parses all stringified values
- [ ] Round-trip property holds: `parse (print v) = v` for all values
- [ ] Tests pass for all value types
- [ ] Documentation is complete
- [ ] Integration with embedded languages is possible

## Timeline Estimate

1. Standard ML definition and Java type representation: ~2-3 hours
2. Java runtime support (print function): ~3-4 hours
4. Parser implementation: ~8-12 hours (most complex)
5. Built-in registration and TOML entries: ~1-2 hours
6. Comprehensive testing: ~4-6 hours
7. Documentation: ~2-3 hours

Total: ~18-28 hours of development time

## Phase 2: Type + Object Representation with Refinement

### Motivation

The current implementation uses a tagged union representation where every element of a collection carries its own type tag:
```
["LIST", [["INT", 1], ["INT", 2], ["INT", 3]]]  // ~5 wrapper objects per element
```

This works but is inefficient for homogeneous collections (which are the norm in Standard ML). We can do better by storing a Type alongside a native Object:
```java
Value(ListType(INT), [1, 2, 3])  // 1 wrapper for the whole list
```

### Design Decisions

1. **Parser produces maximally general types**
   - `Value.parse "[]"` → `Value(ListType(ValueType), [])`
   - `Value.parse "[1]"` → `Value(ListType(ValueType), [Value(INT, 1)])`
   - `Value.parse "[[]]"` → `Value(ListType(ListType(ValueType)), [[]])`
   - This avoids type inference in the parser

2. **Equality is strict**
   - `Value(ValueType, Value(INT, 1)) ≠ Value(INT, 1)`
   - A value and an int are not equal
   - Same logical value with different types are not equal

3. **Pretty-print delegates to wrapped value**
   - `Value.prettyPrint` calls existing `Pretty.pretty()` with the wrapped value
   - A value instance pretty-prints the same as the embedded value
   - No special handling needed

4. **Internal construction produces strict types**
   - `LIST [INT 1, INT 2]` → `Value(ListType(INT), [1, 2])`
   - BuiltIn constructors create refined values directly

### New API

#### `typeString : value → string`
Returns the type as a Standard ML type string:
```sml
typeString (INT 42)                    → "int"
typeString (LIST [INT 1, INT 2])       → "int list"
typeString (LIST [INT 1, BOOL true])   → "value list"  (* heterogeneous *)
typeString (RECORD [("x", INT 1)])     → "{x: int}"
typeString (LIST [])                   → "value list"   (* unknown element type *)
```

Type string format:
- Primitives: `"unit" | "bool" | "int" | "real" | "char" | "string"`
- Collections: `"int list" | "value list" | "int vector" | "int bag"`
- Options: `"int option" | "value option"`
- Records: `"{x: int, y: string}"` or `"record"` (if generic)
- Nested: `"int list list" | "int option list"`
- Datatypes: `"SOME of int" | "NONE"`

#### `refine : value → value`
Narrows the type to the most specific type consistent with contents:
```sml
(* Homogeneous lists *)
refine (LIST [INT 1, INT 2, INT 3])
  → converts from Value(ListType(ValueType), [...])
  → to Value(ListType(INT), [1,2,3])

(* Nested homogeneous *)
refine (LIST [LIST [INT 1], LIST [INT 2]])
  → Value(ListType(ListType(INT)), [[1], [2]])

(* Heterogeneous - no change *)
refine (LIST [INT 1, BOOL true])
  → Value(ListType(ValueType), [...])  (* stays as-is *)

(* Empty list - no change without type hint *)
refine (LIST [])
  → Value(ListType(ValueType), [])  (* can't infer element type *)
```

**Note**: `refine` is explicit, not automatic. Users call it when they want space/performance optimization.

### Implementation Plan

1. **Create Value class** (`src/main/java/net/hydromatic/morel/eval/Value.java`)
   - Fields: `Type type`, `Object value`
   - Replace current List representation

2. **Update BuiltIn constructors**
   - Construct refined types directly
   - `LIST` constructor creates `Value(ListType(...), ...)`

3. **Update Values.parse()**
   - Return general types: `ListType(ValueType)`
   - Construct Value instances instead of Lists

4. **Update Values.print()**
   - Handle both old List and new Value representations (during migration)
   - Extract type and value, print accordingly

5. **Implement typeString()**
   - Traverse Type and format as ML type string

6. **Implement refine()**
   - Inspect contents, narrow types when homogeneous
   - Recursively refine nested structures

7. **Update prettyPrint()**
   - Extract wrapped value, call existing Pretty.pretty()

8. **Update tests**
   - Tests should continue to pass
   - Add new tests for typeString and refine

### Benefits

- **Space efficiency**: Homogeneous lists use native collections
- **Performance**: No wrapping/unwrapping per element
- **Type correctness**: Aligns with ML's type system
- **Backward compatible**: Parse/print round-trip still works
- **Incremental**: Can implement without breaking existing code

### Current Status (Code Compiles!)

**Completed:**
1. ✅ Created `Value.java` class with Type and Object fields
2. ✅ Implemented logical equality in Value.equals()
3. ✅ Created `Values.fromConstructor()` converter (partial - needs completion for RECORD, CONSTANT, CONSTRUCT)
4. ✅ Updated `Values.parse()` signature to return Value
5. ✅ Parser creates TypeSystem and uses fromConstructor
6. ✅ Implemented `Values.print(Value)` with support for refined and unrefined values
7. ✅ Updated Codes applicables: VALUE_PARSE, VALUE_PRINT
8. ✅ Fixed option type detection (DataType instead of Type.Op)

**Remaining Work:**

#### High Priority (Required for Tests to Pass)

1. **Update BuiltIn VALUE constructors**
   - All VALUE constructors (UNIT, BOOL, INT, etc.) must return Value instances
   - Currently they likely return Lists
   - Need to update BuiltIn.java to create Value objects
   - Example:
     ```java
     // Old: return ImmutableList.of("INT", intValue)
     // New: return Value.of(PrimitiveType.INT, intValue)
     ```

#### Medium Priority (Required for Full Functionality)

2. **Complete Values.fromConstructor() for missing types**
   - RECORD: Construct RecordType and record value
   - CONSTANT: Lookup datatype constructor
   - CONSTRUCT: Lookup datatype constructor with value
   - BAG: Use proper bag type (not just list)
   - VECTOR: Use proper vector type

#### Low Priority (Optimizations)

3. **Implement Values.refine()**
   ```java
   public static Value refine(Value value) {
     // If heterogeneous, return as-is
     // If homogeneous, unwrap elements and create refined type
     // Recursively refine nested structures
   }
   ```

4. **Implement Values.typeString()**
   ```java
   public static String typeString(Value value) {
     // Traverse value.type and format as ML type string
     // "int", "int list", "{x: int, y: string}", etc.
   }
   ```

5. **Add BuiltIn functions for refine and typeString**

6. **Improve pretty-printing**
   - Use existing Pretty.pretty() for formatted output
   - Handle indentation and line breaks properly

### Implementation Notes

**Key Design Decisions:**
- ✅ Logical equality: Refined and unrefined values are equal
- ✅ Parser produces general types (value list, value option)
- ✅ Internal constructors produce refined types (int list, int option)
- ✅ Pretty-print delegates to wrapped value

**Type System Access:**
- Parser creates its own TypeSystem instance
- TypeSystem.lookup("value") gets the value datatype
- TypeSystem methods: listType(), option(), etc.

**Unit Type:**
- Use Unit.INSTANCE or Codes.Unit.INSTANCE
- Check existing code for Unit representation

**Option Type:**
- NONE: Value(OptionType(valueType), null) or special representation?
- SOME: Value(OptionType(valueType), innerValue)
- Check how options are currently represented

**Record Type:**
- Need to construct RecordType with field names and types
- Field order is alphabetical
- Check RecordType constructor signature

### Testing Strategy

1. **Compile first** - Fix all compilation errors
2. **Run existing tests** - They will fail, that's expected
3. **Fix one test at a time** - Start with simple primitives
4. **Add new tests** - For typeString and refine

## References

- Issue #324: https://github.com/hydromatic/morel/issues/324
- Issue #323 (Soufflé Datalog integration): https://github.com/hydromatic/morel/issues/323
- Existing BuiltIn structures: `src/main/java/net/hydromatic/morel/compile/BuiltIn.java`
- Pretty printing logic: `src/main/java/net/hydromatic/morel/compile/Pretty.java`
- Function definitions: `src/main/resources/functions.toml`
- Test examples: `src/test/resources/script/*.sml`
