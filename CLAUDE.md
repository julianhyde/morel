# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

Morel is a Standard ML interpreter with relational extensions, implemented in Java. It allows users to write Standard ML code with SQL-like query expressions to operate on in-memory data structures. The project uses Apache Calcite for query optimization and planning.

## Build and Test Commands

### Building
```bash
./mvnw install              # Full build with all checks
./mvnw verify               # Compile and run tests
./mvnw compile              # Compile only
```

### Running Tests
```bash
./mvnw test                 # Run all tests
./mvnw test -Dtest=MainTest # Run specific test class
./mvnw test -Dtest=MainTest#testRepl # Run specific test method

# Run individual .smli script test files
./morel src/test/resources/script/wordle.smli

# Run individual script with visible output (for debugging hangs/slow tests)
# The --echo flag shows test output to stdout in real-time
./morel --echo src/test/resources/script/wordle.smli
```

### Running the Shell
```bash
./morel                     # Start interactive REPL
```

### Code Quality
```bash
./mvnw checkstyle:check     # Run checkstyle
./mvnw javadoc:javadoc      # Generate javadoc
```

Note: The build uses Google Java Format automatically during the `process-sources` phase. Checkstyle runs in the same phase.

## Architecture

Morel follows a traditional interpreter pipeline: Parse → Type Check → Compile → Evaluate.

### Core Components

**Parser (`net.hydromatic.morel.parse`)**
- `MorelParser.jj`: JavaCC grammar file that defines Standard ML syntax plus relational extensions
- `MorelParserImpl`: Generated parser implementation
- Produces AST (`Ast` nodes)

**AST Layer (`net.hydromatic.morel.ast`)**
- `Ast`: User-facing abstract syntax tree from parser
- `Core`: Internal representation after type resolution (more normalized)
- `AstBuilder`, `CoreBuilder`: Fluent builders for constructing nodes
- `Visitor`, `Shuttle`: Tree traversal patterns

**Type System (`net.hydromatic.morel.type`)**
- `TypeSystem`: Central registry for types
- `Type` hierarchy: `PrimitiveType`, `RecordType`, `TupleType`, `ListType`, `FnType`, `DataType`, `TypeVar`, etc.
- `TypeVar`: Polymorphic type variables (for parametric polymorphism)
- `TypeUnifier`: Implements Hindley-Milner type inference using unification
- `Binding`: Associates names with types and values

**Compilation (`net.hydromatic.morel.compile`)**
- `TypeResolver`: Performs type inference and checking; converts `Ast` to `Core`
- `Compiler`: Compiles typed `Core` expressions into executable `Code`
- `Environment`: Symbol table holding bindings (variables, functions, types)
- `BuiltIn`: Defines all built-in functions, operators, and types
- `CalciteCompiler`: Translates relational expressions to Calcite logical plans for optimization
- `Resolver`: Resolves names and converts patterns to code

**Evaluation (`net.hydromatic.morel.eval`)**
- `Code`: Interface for executable code nodes
- `Codes`: Implementations of all code types (literals, function application, let bindings, etc.)
- `EvalEnv`: Runtime environment mapping variables to values
- `Closure`: Function values that capture their environment
- `Applicable`: Function objects with apply methods
- `Session`: Maintains REPL state and configuration

**Foreign Interface (`net.hydromatic.morel.foreign`)**
- `ForeignValue`: Interface for exposing Java values/functions to Morel
- `Calcite`: Integration with Apache Calcite for querying relational data
- `DataSet`: Abstraction for queryable datasets (backed by Calcite)

**Main Entry Points**
- `Main`: REPL implementation with shell and sub-shell support
- `Shell`: Handles command execution and error reporting

### Key Execution Flow

1. **Parsing**: User input → `MorelParser` → `Ast` nodes
2. **Type Resolution**: `Ast` + `Environment` → `TypeResolver` → typed `Core` nodes
3. **Compilation**: `Core` → `Compiler` → `Code` nodes
4. **Evaluation**: `Code` + `EvalEnv` → execution → result value

### Important Implementation Details

**Type Inference**
- Uses Hindley-Milner algorithm (Algorithm W) via `TypeResolver`
- Type variables represent unknown types during inference
- Unification (`TypeUnifier`) propagates type constraints
- Generalization introduces polymorphism at `let` bindings

**Relational Extensions**
- `from` expressions are first-class and composable
- TypeResolver converts `from` to `Core.From` nodes
- Compiler can either:
  - Inline as nested loops (simple cases)
  - Send to `CalciteCompiler` for optimization (complex queries)
- Integration with Calcite allows joining external data sources

**Overloading**
- Functions like `+`, `max`, `empty` support multiple type signatures
- Declared using `over` (declares overloaded name) and `inst` (adds instance)
- Bindings track `overloadId` to distinguish overload instances
- Type resolution selects appropriate instance based on argument types

**Pattern Matching**
- Patterns appear in `val`, `fun`, `case`, `fn`, and `from`
- `PatternCoverageChecker` ensures exhaustiveness and detects redundancy
- Compiled to decision trees with guards

## Test Organization

Tests are in `src/test/java` and use JUnit 5. The main test infrastructure:

- `MainTest`: Primary tests using the `Ml` helper class
- `Ml.ml()`: Helper to run Morel code and check results
- `src/test/resources/script/`: Reference test files (`.smli` suffix)
  - These are Morel source files with expected output
  - Run via `MainTest` methods that check actual vs. expected output
  - `.smli.out` files contain the expected output

Key test files in `src/test/resources/script/`:
- `builtIn.smli`: Tests for built-in functions and operators
- `relational.smli`: Tests for relational/query features
- `simple.smli`: Basic language features
- `datatype.smli`: Algebraic data types
- `type.smli`: Type system tests
- `foreign.smli`: Foreign value integration

## Common Development Patterns

### Adding a Built-in Function

1. Add the function definition in `BuiltIn.java`
2. Register it in the appropriate structure (LIST, STRING, etc.)
3. Add tests in the corresponding `src/test/resources/script/` file
4. Update type signatures if polymorphic

### Adding a Language Feature

1. Update `MorelParser.jj` grammar
2. Add AST node types to `Ast.java` if needed
3. Update `TypeResolver.java` for type checking
4. Add compilation logic in `Compiler.java`
5. Add evaluation logic in `Codes.java`
6. Add tests

### Debugging Type Errors

- The `TypeResolver` tracks type constraints during inference
- Look for `unify()` calls to see where types are constrained
- Type variables have unique IDs; follow them through unification
- The `Tracer` interface can log type resolution steps

## Datalog Implementation Plan (Issue #323)

### Overview

Add a Datalog interface to Morel that translates Datalog programs to Morel Core expressions for execution. The implementation creates a new `Datalog` structure with `execute` and `validate` functions.

### Datalog Structure API

The `Datalog` structure provides two main functions:

**`Datalog.execute : string -> variant`**
- Takes a Datalog program as input
- Parses, validates, and executes the program
- Returns structured data for relations marked with `.output` as a variant
  - For single output: returns a record with one field (the relation name) containing a list
  - For multiple outputs: returns a record with multiple fields
  - For no outputs: returns `unit`
- Throws exception if program is invalid

**`Datalog.validate : string -> string`**
- Takes a Datalog program as input
- Parses and validates without executing
- Returns type information if program is valid (e.g., `"{parent:(string * string) list}"`)
- Returns `"Error: <message>"` if program is invalid
- Useful for pre-flight checking and IDE integration

**Example Usage**:
```sml
(* Validate before executing *)
val prog = "
.decl parent(x:symbol, y:symbol)
.output parent
parent(\"alice\", \"bob\").
";

Datalog.validate prog;
(* Returns: "{parent:(string * string) list}" *)

Datalog.execute prog;
(* Returns: {parent = [{x = "alice", y = "bob"}]} : {parent:{x:string, y:string} list} variant *)

(* Validation catches errors *)
val bad = "
.decl parent(x:symbol, y:symbol)
parent(\"alice\", \"bob\", \"charlie\").
";

Datalog.validate bad;
(* Returns: "Error: Atom parent/3 does not match declaration parent/2" *)
```

### Architecture Strategy

**Translation Target**: Core (typed AST)
- Datalog programs are parsed, type-checked, then translated to Core expressions
- Core is the canonical typed intermediate representation in Morel's pipeline
- Avoids re-parsing (vs. source code generation) and re-type-checking (vs. Ast generation)
- Direct path to compilation: Core → Compiler → Code → Evaluation

**Type Mapping**:
- Datalog `number` → Morel `int`
- Datalog `string` → Morel `string`
- Datalog `symbol` → Morel `string` (symbols are identifier-like values; string representation is sufficient)
- Datalog relations → Morel functions returning `bool` (predicates)

### Implementation Tasks

#### 1. Create Datalog Package Structure
**Package**: `net.hydromatic.morel.datalog`

**Files to create**:
- `DatalogParser.jj` - JavaCC grammar for Datalog
- `DatalogParserImpl.java` - Generated parser
- `DatalogAst.java` - Datalog AST nodes
- `DatalogAnalyzer.java` - Safety and stratification checker
- `DatalogToCoreTranslator.java` - Translates Datalog AST to Morel Core
- `DatalogEvaluator.java` - Orchestrates parse → analyze → translate → execute/validate
  - `execute(String program, Session)` - Parse, validate, execute and format results
  - `validate(String program, Session)` - Parse and validate, return type or error
  - `validateAndGetType(String program, Session)` - Return Morel Type object
- `DatalogException.java` - Exception types for Datalog errors

#### 2. Datalog Parser (JavaCC Grammar)
**File**: `src/main/javacc/DatalogParser.jj`

**Grammar elements**:
```
Program ::= Statement*

Statement ::=
  | Declaration     (.decl relation(var:type, ...))
  | Input           (.input relation)
  | Output          (.output relation)
  | Fact            (relation(value, ...).  )
  | Rule            (head :- body.)

Declaration ::= .decl ID ( ParamList )
ParamList ::= Param (, Param)*
Param ::= ID : Type
Type ::= number | string | symbol

Input ::= .input ID
Output ::= .output ID

Fact ::= Atom .
Rule ::= Atom :- Body .

Body ::= Atom (, Atom)*

Atom ::= ID ( TermList )
TermList ::= Term (, Term)*
Term ::= Variable | Constant

Variable ::= ID (lowercase starting)
Constant ::= NUMBER | STRING_LITERAL
```

**AST Node Structure** (in `DatalogAst.java`):
```java
class Program { List<Statement> statements; }
abstract class Statement { }
class Declaration extends Statement { String name; List<Param> params; }
class Param { String name; String type; }
class Input extends Statement { String name; }
class Output extends Statement { String name; }
class Fact extends Statement { Atom atom; }
class Rule extends Statement { Atom head; List<Atom> body; }
class Atom { String name; List<Term> terms; }
abstract class Term { }
class Variable extends Term { String name; }
class Constant extends Term { Object value; String type; }
```

#### 3. Datalog Analyzer (Safety & Stratification)
**File**: `DatalogAnalyzer.java`

**Safety Checking**:
A rule is safe if:
1. Each variable in the head appears in a positive body atom
2. Each variable in an arithmetic expression appears in a positive body atom
3. Each variable in a negated body atom appears in a positive body atom

**Examples of unsafe rules**:
```datalog
# Unsafe: x appears in head but not in positive body
S(x) :- R(y).

# Unsafe: x appears in negation but not in positive body
S(x) :- R(y), !R(x).

# Unsafe: x appears in arithmetic but not in positive body
S(x) :- R(y), x < y.
```

**Stratification Checking**:
Build a dependency graph:
- Nodes are relations
- Edge R → S if R appears in a rule defining S
- Mark edge as "negated" if R appears negated in rule body

A program is stratified if there are no cycles containing negated edges.

**Error Messages**:
```
Error: Rule is unsafe. Variable 'x' in head does not appear in positive body atom.
Error: Program is not stratified. Negation cycle detected: R -> S -> T -> R
```

#### 4. Datalog to Core Translator
**File**: `DatalogToCoreTranslator.java`

**Translation Strategy**:

Datalog relations become Morel functions that return `bool`:
```datalog
.decl parent(x:symbol, y:symbol)
parent("alice", "bob").
parent("bob", "charlie").
```

Translates to Core representing:
```sml
fun parent (x, y) =
  (x = "alice" andalso y = "bob") orelse
  (x = "bob" andalso y = "charlie")
```

Datalog rules become recursive functions using `from` expressions:
```datalog
ancestor(x, y) :- parent(x, y).
ancestor(x, z) :- ancestor(x, y), parent(y, z).
```

Translates to Core representing:
```sml
fun ancestor (x, y) =
  parent (x, y) orelse
  exists (from z where ancestor (x, z) andalso parent (z, y))
```

**Core Construction using CoreBuilder**:
```java
// Build: fun parent (x, y) = ...
Core.IdPat xPat = core.idPat("x", stringType);
Core.IdPat yPat = core.idPat("y", stringType);
Core.Pat paramPat = core.tuplePat(xPat, yPat);

// Build disjunction of fact checks
Core.Exp body = buildFactDisjunction(facts);

Core.Fn parentFn = core.fn(paramPat, body,
  typeSystem.fnType(tupleType, boolType));
```

**Handling Queries**:
Output directives specify which relations to evaluate:
```datalog
.output ancestor
```

For each output relation, generate code that enumerates all valid tuples:
```sml
from x, y where ancestor(x, y)
```

This uses unbounded variables (like in `such-that.smli`) that are constrained by the relation.

#### 5. Integration with BuiltIn
**File**: `BuiltIn.java` (modifications)

Add to BuiltIn enum:
```java
DATALOG_EXECUTE(
  "Datalog",
  "execute",
  ts -> ts.fnType(STRING, ts.lookup(Datatype.VARIANT))
),

DATALOG_VALIDATE(
  "Datalog",
  "validate",
  ts -> ts.fnType(STRING, STRING)
)
```

Register these as structure members in the `Datalog` structure, following the same pattern as existing structures like `List`, `String`, `Bag`, etc.

The implementation details of how to expose these as a structure can be determined during implementation - following the existing patterns in `BuiltIn.java` should be sufficient.

#### 6. Datalog Evaluator
**File**: `DatalogEvaluator.java`

**Execution Pipeline**:
```java
public static String execute(String program, Session session) {
  // 1. Parse Datalog program
  DatalogAst.Program ast = DatalogParser.parse(program);

  // 2. Analyze for safety and stratification
  DatalogAnalyzer.analyze(ast);  // Throws if unsafe/non-stratified

  // 3. Translate to Core
  Map<String, Core.Exp> relations =
    DatalogToCoreTranslator.translate(ast, session.typeSystem);

  // 4. Compile and evaluate
  Map<String, List<List<Object>>> results =
    evaluateRelations(relations, ast.outputs, session);

  // 5. Format output
  return formatResults(results, ast);
}
```

**Validation Pipeline**:
```java
public static String validate(String program, Session session) {
  try {
    // 1. Parse Datalog program
    DatalogAst.Program ast = DatalogParser.parse(program);

    // 2. Analyze for safety and stratification
    DatalogAnalyzer.analyze(ast);  // Throws if unsafe/non-stratified

    // 3. Determine output type
    Type outputType = inferOutputType(ast, session.typeSystem);

    // 4. Format type as string
    return formatTypeDescription(outputType);
  } catch (DatalogException e) {
    return "Error: " + e.getMessage();
  }
}
```

**Type Inference** (internal method used by validate):
```java
// In DatalogEvaluator.java
public static Type validateAndGetType(String program, Session session) {
  // 1. Parse Datalog program
  DatalogAst.Program ast = DatalogParser.parse(program);

  // 2. Analyze for safety and stratification
  DatalogAnalyzer.analyze(ast);  // Throws if unsafe/non-stratified

  // 3. Determine output type based on .output directives
  return inferOutputType(ast, session.typeSystem);
}

private static Type inferOutputType(DatalogAst.Program ast, TypeSystem ts) {
  List<Output> outputs = ast.getOutputs();

  if (outputs.isEmpty()) {
    // No outputs = returns empty string
    return PrimitiveType.STRING;
  }

  if (outputs.size() == 1) {
    // Single output: string (formatted relation)
    return PrimitiveType.STRING;
  }

  // Multiple outputs: record type with one field per output relation
  // Each field is a list of records
  Map<String, Type> fields = new LinkedHashMap<>();
  for (Output output : outputs) {
    Declaration decl = ast.getDeclaration(output.relationName);
    RecordType tupleType = buildRecordType(decl, ts);
    fields.put(output.relationName, ts.listType(tupleType));
  }

  // For string output, we just return STRING
  // But internally we track the structured type
  return PrimitiveType.STRING;
}

private static String formatTypeDescription(Type type) {
  // Returns a human-readable description of what execute() will return
  return "string";  // Since execute always returns formatted string output
}
```

**Output Formatting**:
```
relation_name
value1_1\tvalue1_2\t...
value2_1\tvalue2_2\t...
```

Tab-separated format matching Souffle conventions.

**Validate Method**:

The `Datalog.validate` method provides compile-time validation without execution:

```java
public static String validate(String program, Session session)
```

**Purpose**:
- Parse and validate a Datalog program without executing it
- Return type information if valid, or error message if invalid
- Useful for IDE integration, syntax checking, and type verification

**Return Values**:
- **Valid program**: Returns type information (e.g., `"{parent:(string * string) list}"`)
- **Invalid program**: Returns `"Error: <detailed error message>"`

**Use Cases**:
1. **Pre-flight validation**: Check program before execution
2. **Interactive development**: Validate as user types
3. **Testing**: Verify error messages without execution overhead
4. **Type checking**: Confirm program structure and types

**Internal Type Method** (`validateAndGetType`):

Below the public `validate` method, there's an internal method that returns the actual Morel Type object:

```java
public static Type validateAndGetType(String program, Session session)
```

This method:
- Parses and validates the program (throws if invalid)
- Returns the Morel `Type` representing the program's output
- Returns structured types based on the `.output` directives (e.g., record types with list fields)
- `execute` returns a variant matching this type structure

**Type Representation**:

For programs with `.output` directives, the type structure is:
- **No outputs**: `unit`
- **Single output**: `{relation1: element_type list}` where element_type is the tuple/value type
- **Multiple outputs**: `{relation1: type1 list, relation2: type2 list, ...}`

The execute function returns a variant matching this type structure:
- Arity-1 relations: list of primitive values (e.g., `[1, 2]`)
- Arity > 1 relations: list of records with field names from the declaration (e.g., `[{x = 1, y = 2}, ...]`)
- Records use the actual parameter names from the `.decl` statement

#### 7. Test Suite
**File**: `src/test/resources/script/datalog.smli`

**Test Categories**:

1. **Basic Facts and Queries**:
```sml
val program1 = "
.decl parent(x:symbol, y:symbol)
.output parent

parent(\"alice\", \"bob\").
parent(\"bob\", \"charlie\").
";
Datalog.execute program1;
(* Expected output:
   {parent = [{x = "alice", y = "bob"}, {x = "bob", y = "charlie"}]}
   : {parent:{x:string, y:string} list} variant
*)
```

2. **Recursive Rules (Transitive Closure)**:
```sml
val program2 = "
.decl parent(x:symbol, y:symbol)
.decl ancestor(x:symbol, y:symbol)
.output ancestor

parent(\"alice\", \"bob\").
parent(\"bob\", \"charlie\").

ancestor(x, y) :- parent(x, y).
ancestor(x, z) :- ancestor(x, y), parent(y, z).
";
Datalog.execute program2;
(* Expected output:
   {ancestor = [{x = "alice", y = "bob"}, {x = "bob", y = "charlie"}, {x = "alice", y = "charlie"}]}
   : {ancestor:{x:string, y:string} list} variant
*)
```

3. **Multi-Relation Programs (from such-that.smli)**:
```sml
(* Joe's bar example *)
val program3 = "
.decl frequents(patron:symbol, bar:symbol)
.decl likes(patron:symbol, beer:symbol)
.decl sells(bar:symbol, beer:symbol, price:number)
.decl happy(patron:symbol)
.output happy

frequents(\"shaggy\", \"squirrel\").
frequents(\"fred\", \"cask\").
frequents(\"shaggy\", \"cask\").

likes(\"shaggy\", \"amber\").
likes(\"fred\", \"amber\").

sells(\"squirrel\", \"amber\", 3).
sells(\"cask\", \"stout\", 4).

happy(p) :- frequents(p, bar), likes(p, beer), sells(bar, beer, price).
";
Datalog.execute program3;
(* Expected output: {happy = ["shaggy"]} : {happy:string list} variant *)
```

4. **Safety Violation Tests**:
```sml
(* Unsafe: x in head but not in positive body *)
val unsafe1 = "
.decl R(x:number)
.decl S(x:number)

S(x) :- R(y).
";
Datalog.execute unsafe1;
(* Expected: Error - Rule is unsafe. Variable 'x' in head does not appear in positive body atom *)

(* Unsafe: x in arithmetic but not in positive body *)
val unsafe2 = "
.decl R(y:number)
.decl S(x:number)

S(x) :- R(y), x < y.
";
Datalog.execute unsafe2;
(* Expected: Error - Rule is unsafe. Variable 'x' in arithmetic expression does not appear in positive body atom *)
```

5. **Stratification Violation Tests**:
```sml
(* Non-stratified: negation cycle *)
val nonstratified = "
.decl P(x:number)
.decl Q(x:number)

P(x) :- Q(x), !P(x).
Q(x) :- P(x), !Q(x).
";
Datalog.execute nonstratified;
(* Expected: Error - Program is not stratified. Negation cycle detected *)
```

6. **Type Mismatch Tests (Schema Validation)**:
```sml
(* Wrong number of arguments *)
val typeerror1 = "
.decl parent(x:symbol, y:symbol)

parent(\"alice\", \"bob\", \"charlie\").
";
Datalog.execute typeerror1;
(* Expected: Error - Atom parent/3 does not match declaration parent/2 *)

(* Wrong argument types *)
val typeerror2 = "
.decl age(person:symbol, years:number)

age(\"alice\", \"twenty\").
";
Datalog.execute typeerror2;
(* Expected: Error - Type mismatch: expected number, got string *)

(* Query variable in rule doesn't match relation schema *)
val typeerror3 = "
.decl parent(x:symbol, y:symbol)
.decl sibling(x:symbol, y:symbol)

sibling(x, y, z) :- parent(p, x), parent(p, y), x <> y.
";
Datalog.execute typeerror3;
(* Expected: Error - Atom sibling/3 does not match declaration sibling/2 *)

(* Using undeclared relation *)
val typeerror4 = "
.decl parent(x:symbol, y:symbol)

ancestor(x, y) :- parent(x, y).
";
Datalog.execute typeerror4;
(* Expected: Error - Relation 'ancestor' used but not declared *)

(* Fact uses relation that was only used in rule body *)
val typeerror5 = "
.decl parent(x:symbol, y:symbol)
.decl ancestor(x:symbol, y:symbol)

parent(\"alice\", \"bob\").
ancestor(x, y) :- parent(x, y).
grandparent(x, z) :- parent(x, y), parent(y, z).
";
Datalog.execute typeerror5;
(* Expected: Error - Relation 'grandparent' used but not declared *)
```

7. **Edge Cases**:
```sml
(* Empty program *)
val empty = "";
Datalog.execute empty;
(* Expected: "" *)

(* Only declarations, no facts or rules *)
val declonly = "
.decl parent(x:symbol, y:symbol)
.output parent
";
Datalog.execute declonly;
(* Expected: "parent\n" *)

(* Multiple outputs *)
val multiout = "
.decl parent(x:symbol, y:symbol)
.decl child(x:symbol, y:symbol)
.output parent
.output child

parent(\"alice\", \"bob\").
child(y, x) :- parent(x, y).
";
Datalog.execute multiout;
(* Expected:
parent
alice\tbob

child
bob\talice
*)
```

8. **Validation Tests**:
```sml
(* Valid program - single output *)
val valid1 = "
.decl parent(x:symbol, y:symbol)
.output parent

parent(\"alice\", \"bob\").
";
Datalog.validate valid1;
(* Expected: "string" *)

(* Valid program - multiple outputs *)
val valid2 = "
.decl parent(x:symbol, y:symbol)
.decl ancestor(x:symbol, y:symbol)
.output parent
.output ancestor

parent(\"alice\", \"bob\").
ancestor(x, y) :- parent(x, y).
";
Datalog.validate valid2;
(* Expected: "string" *)

(* Valid program - no outputs *)
val valid3 = "
.decl parent(x:symbol, y:symbol)

parent(\"alice\", \"bob\").
";
Datalog.validate valid3;
(* Expected: "string" *)

(* Valid program - complex relations *)
val valid4 = "
.decl person(name:symbol, age:number)
.decl works_at(person:symbol, company:symbol, salary:number)
.output works_at

person(\"alice\", 30).
works_at(\"alice\", \"acme\", 50000).
";
Datalog.validate valid4;
(* Expected: "string" *)

(* Invalid - syntax error *)
val invalid1 = "
.decl parent(x:symbol, y:symbol)

parent(\"alice\" \"bob\")
";
Datalog.validate invalid1;
(* Expected: "Error: Syntax error at line 4: expected ',' or ')'" *)

(* Invalid - undeclared relation *)
val invalid2 = "
.decl parent(x:symbol, y:symbol)

ancestor(x, y) :- parent(x, y).
";
Datalog.validate invalid2;
(* Expected: "Error: Relation 'ancestor' used but not declared" *)

(* Invalid - arity mismatch *)
val invalid3 = "
.decl parent(x:symbol, y:symbol)

parent(\"alice\", \"bob\", \"charlie\").
";
Datalog.validate invalid3;
(* Expected: "Error: Atom parent/3 does not match declaration parent/2" *)

(* Invalid - type mismatch *)
val invalid4 = "
.decl age(person:symbol, years:number)

age(\"alice\", \"thirty\").
";
Datalog.validate invalid4;
(* Expected: "Error: Type mismatch in fact age(...): expected number, got string" *)

(* Invalid - unsafe rule *)
val invalid5 = "
.decl R(x:number)
.decl S(x:number)

S(x) :- R(y).
";
Datalog.validate invalid5;
(* Expected: "Error: Rule is unsafe. Variable 'x' in head does not appear in positive body atom" *)

(* Invalid - non-stratified *)
val invalid6 = "
.decl P(x:number)

P(x) :- !P(x).
";
Datalog.validate invalid6;
(* Expected: "Error: Program is not stratified. Negation cycle detected: P -> P" *)

(* Validate before execute - success case *)
val prog = "
.decl edge(x:number, y:number)
.decl path(x:number, y:number)
.output path

edge(1, 2).
edge(2, 3).

path(x, y) :- edge(x, y).
path(x, z) :- path(x, y), edge(y, z).
";
Datalog.validate prog;
(* Expected: "{path:(int * int) list}" *)

Datalog.execute prog;
(* Expected:
   {path = [{x = 1, y = 2}, {x = 1, y = 3}, {x = 2, y = 3}]}
   : {path:{x:int, y:int} list} variant
*)

(* Validate before execute - error case *)
val badprog = "
.decl edge(x:number, y:number)

path(x, y) :- edge(x, y).
";
Datalog.validate badprog;
(* Expected: "Error: Relation 'path' used but not declared" *)

(*
Datalog.execute badprog;
(* Would throw error: Error: Relation 'path' used but not declared *)
*)
```

**Unit Tests** (in `src/test/java/net/hydromatic/morel/DatalogTest.java`):
```java
@Test void testParser() { ... }
@Test void testSafetyChecker() { ... }
@Test void testStratification() { ... }
@Test void testTypeChecking() { ... }
@Test void testTranslation() { ... }
@Test void testSchemaMismatch() { ... }  // Schema validation
@Test void testValidateSuccess() { ... }  // Validate returns type info for valid programs
@Test void testValidateErrors() { ... }  // Validate returns error messages
@Test void testValidateMultiOutput() { ... }  // Validate with multiple .output directives
@Test void testValidateAndGetType() { ... }  // Internal type inference method
```

#### 8. Error Handling

**Error Categories**:

1. **Parse Errors**:
   - Syntax errors in Datalog program
   - Include position information

2. **Semantic Errors**:
   - Undeclared relations
   - Type mismatches
   - Arity mismatches (wrong number of arguments)
   - Schema violations in rules/queries

3. **Safety Errors**:
   - Unsafe rules (ungrounded variables)
   - Clear indication of which variable and why

4. **Stratification Errors**:
   - Non-stratified programs
   - Show the cycle path

**Example Error Messages**:
```
Line 5: Syntax error at ';' - expected '.'
Line 8: Relation 'ancestor' used but not declared
Line 10: Atom parent(x,y,z) has 3 arguments but relation parent is declared with 2
Line 12: Type error in fact: parent("alice", 42) - expected symbol, got number
Line 15: Unsafe rule: variable 'z' in head does not appear in positive body
Line 20: Non-stratified program: negation cycle P -> Q -> P
```

### Design Decisions

1. **Translation to Core (not source or Ast)**:
   - Core is the typed intermediate representation
   - Avoids re-parsing and re-type-checking
   - Direct compilation path to executable code

2. **Symbol as String**:
   - Datalog symbols are identifier-like values
   - String representation is semantically correct
   - Compatible with existing Morel code patterns

3. **Semi-Naive Evaluation Strategy**:
   - Use Morel's existing `from` expressions for joins
   - Recursive rules translate to recursive functions
   - Leverage Morel's evaluation engine (no separate Datalog evaluator needed)

4. **Compile-Time Safety**:
   - Check safety and stratification during analysis phase
   - Prevent runtime errors from ill-formed programs
   - Clear error messages for debugging

5. **Schema Validation**:
   - Validate arity and types at parse/analysis time
   - Detect mismatches between declarations and usage
   - Ensure all relations are declared before use

### Example End-to-End Flow

**Input**:
```datalog
.decl edge(x:number, y:number)
.decl path(x:number, y:number)
.output path

edge(1, 2).
edge(2, 3).

path(x, y) :- edge(x, y).
path(x, z) :- path(x, y), edge(y, z).
```

**Translation Strategy (Simplified)**:

Datalog rules translate directly to Morel predicate functions:

```
Datalog:  p(x) :- q(x, 1), r(x).
Morel:    fun p x = q(x, 1) andalso r(x)

Datalog:  .output p
Morel:    from x where p(x)
```

**Complete Example**:
```
Datalog Input:
.decl edge(x:number, y:number)
.decl path(x:number, y:number)
edge(1, 2).
edge(2, 3).
path(X, Y) :- edge(X, Y).
path(X, Z) :- path(X, Y), edge(Y, Z).
.output path

Generated Morel (conceptual):
fun edge(x, y) =
  (x = 1 andalso y = 2) orelse
  (x = 2 andalso y = 3);

fun path(x, y) =
  edge(x, y) orelse
  (exists (from z where path(x, z) andalso edge(z, y)));

(* .output path becomes: *)
from x, y where path(x, y)
```

**Translation Rules**:
1. **Facts** → Disjunction of equality tests
   - `edge(1,2). edge(2,3).` → `fun edge(x,y) = (x=1 andalso y=2) orelse (x=2 andalso y=3)`

2. **Rules** → Function returning conjunction of body predicates
   - `p(X) :- q(X,1), r(X).` → `fun p x = q(x,1) andalso r(x)`
   - Multiple rules with same head → Disjunction of rule bodies

3. **Negation** → andalso not
   - `p(X) :- q(X), !r(X).` → `fun p x = q(x) andalso not(r(x))`

4. **Output** → from expression
   - `.output p` → `from x where p(x)` (where x matches relation arity)

5. **Recursive Rules** → Recursive functions
   - `path(X,Z) :- path(X,Y), edge(Y,Z).` → Uses recursion in function definition

**Output**:
```
path
1   2
2   3
1   3
```

### Testing Strategy

1. **Parser Tests**: Verify grammar accepts valid Datalog, rejects invalid
2. **Safety Tests**: Confirm detection of all unsafe patterns
3. **Stratification Tests**: Detect negation cycles correctly
4. **Schema Tests**: Validate arity and type checking
5. **Translation Tests**: Verify correct Core generation
6. **Evaluation Tests**: Verify correct results for various programs
7. **Integration Tests**: Run examples from such-that.smli in Datalog form
8. **Error Message Tests**: Verify clear, helpful error messages

### Dependencies

- JavaCC (already in project)
- No new external dependencies
- Leverage existing Morel infrastructure

### Files Modified

- `BuiltIn.java` - Add DATALOG_EXECUTE and DATALOG_VALIDATE to enum
- `pom.xml` - Configure JavaCC to compile DatalogParser.jj

### Files Created

**Package: net.hydromatic.morel.datalog**
- `DatalogParser.jj`
- `DatalogParserImpl.java` (generated)
- `DatalogAst.java`
- `DatalogAnalyzer.java`
- `DatalogToCoreTranslator.java`
- `DatalogEvaluator.java` (with execute, validate, validateAndGetType methods)
- `DatalogException.java`

**Tests**
- `src/test/resources/script/datalog.smli`
- `src/test/java/net/hydromatic/morel/DatalogTest.java`

### Optional: ForeignValue Implementation

If the standard BuiltIn structure pattern is insufficient, create:

**Package: net.hydromatic.morel.foreign**
- `DatalogForeignValue.java` - Custom ForeignValue implementation

This provides an alternative way to expose the Datalog structure if needed, but should only be implemented if the existing BuiltIn patterns don't work for this use case.
