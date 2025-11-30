# Datalog Support in Morel

Morel includes support for Datalog, a declarative logic programming language commonly used for program analysis, knowledge representation, and deductive databases.

## Overview

Datalog extends Morel with:
- **Declarative relations** defined by facts and rules
- **Recursive queries** using stratified negation
- **Semi-naive evaluation** for efficient fixpoint computation
- **Type-safe integration** with Morel's type system

## Getting Started

### Basic Example

```sml
val program = ".decl edge(x:number, y:number)
edge(1, 2).
edge(2, 3).
edge(3, 4).
.output edge";

Datalog.execute program;
(* Returns: "edge\n1 2\n2 3\n3 4" *)
```

### Transitive Closure

```sml
val tc = ".decl edge(x:number, y:number)
.decl path(x:number, y:number)
edge(1, 2).
edge(2, 3).
edge(3, 4).

(* Base case: direct edges are paths *)
path(X, Y) :- edge(X, Y).

(* Recursive case: paths are transitive *)
path(X, Z) :- path(X, Y), edge(Y, Z).

.output path";

Datalog.execute tc;
(* Returns all reachable pairs:
   path
   1 2
   1 3
   1 4
   2 3
   2 4
   3 4
*)
```

## Syntax

### Declarations

Relations must be declared before use:

```datalog
.decl relation_name(param1:type1, param2:type2, ...)
```

Supported types:
- `number` - integers (mapped to Morel `int`)
- `string` - quoted strings (mapped to Morel `string`)
- `symbol` - unquoted identifiers (mapped to Morel `string`)

Examples:
```datalog
.decl edge(x:number, y:number)
.decl person(name:string, age:number)
.decl color(c:symbol)
```

### Facts

Facts define base data:

```datalog
edge(1, 2).
person("Alice", 30).
color(red).
```

**Variables in facts**: Unquoted identifiers in facts are treated as symbols:
```datalog
.decl node(n:symbol)
node(start).
node(end).
```

### Rules

Rules derive new facts from existing ones:

```datalog
head(Args) :- body1(Args1), body2(Args2), ...
```

Components:
- **Head**: Single atom defining what is derived
- **Body**: Comma-separated atoms (conjunction)
- **Variables**: Must start with uppercase letter
- **Constants**: Numbers, quoted strings, or lowercase symbols

Example:
```datalog
.decl parent(p:string, c:string)
.decl ancestor(a:string, d:string)

parent("Alice", "Bob").
parent("Bob", "Carol").

(* Base case *)
ancestor(P, C) :- parent(P, C).

(* Recursive case *)
ancestor(A, D) :- ancestor(A, X), parent(X, D).
```

### Negation

Use `!` to negate atoms (requires stratification):

```datalog
.decl student(name:string)
.decl graduate(name:string)
.decl undergraduate(name:string)

student("Alice").
student("Bob").
graduate("Alice").

(* Bob is a student but not a graduate *)
undergraduate(X) :- student(X), !graduate(X).
```

**Stratification**: Negation cycles are prohibited:
```datalog
(* INVALID - negation cycle *)
p(X) :- edge(X, Y), !q(X).
q(X) :- edge(X, Y), !p(X).
```

### Output Directives

Specify which relations to return:

```datalog
.output relation_name
```

Multiple outputs are supported:
```datalog
.output edge
.output path
```

### Comments

```datalog
(* This is a block comment *)

// This is a line comment

.decl edge(x:number, y:number)  (* Inline comment *)
```

## API

### Datalog.execute

Executes a Datalog program and returns formatted output:

```sml
Datalog.execute : string -> string
```

**Example**:
```sml
val result = Datalog.execute ".decl num(n:number)
num(1).
num(2).
num(3).
.output num";

(* result = "num\n1\n2\n3" *)
```

**Output format**:
- Relation name on first line
- One tuple per line, space-separated
- Multiple relations separated by blank lines
- Tuples sorted for deterministic output

### Datalog.validate

Validates a Datalog program and returns its type:

```sml
Datalog.validate : string -> string
```

**Example**:
```sml
Datalog.validate ".decl edge(x:number, y:number)
edge(1, 2).
.output edge";

(* Returns: "{edge:(int * int) list}" *)
```

**Return values**:
- Success: Type representation like `{edge:(int * int) list, path:(int * int) list}`
- Parse error: `"Error: Parse error: ..."`
- Semantic error: `"Error: ..."` (safety, stratification, type mismatch)
- No output: `"unit"` (programs without `.output` directives)

**Type mapping**:
| Datalog Type | Morel Type |
|--------------|------------|
| `number`     | `int`      |
| `string`     | `string`   |
| `symbol`     | `string`   |

**Examples**:
```sml
(* Single-field relation *)
Datalog.validate ".decl num(n:number)
.output num";
(* Returns: "{num:int list}" *)

(* Multi-field relation *)
Datalog.validate ".decl employee(name:string, age:number, dept:symbol)
.output employee";
(* Returns: "{employee:(string * int * string) list}" *)

(* Multiple outputs *)
Datalog.validate ".decl edge(x:number, y:number)
.decl path(x:number, y:number)
.output edge
.output path";
(* Returns: "{edge:(int * int) list, path:(int * int) list}" *)
```

## Evaluation

Morel uses **semi-naive evaluation** for efficient fixpoint computation:

1. **Initialize**: Start with facts
2. **Iterate**: Apply rules to derive new tuples
3. **Fixpoint**: Stop when no new tuples are derived
4. **Output**: Format results for output relations

**Example trace** for transitive closure:

```datalog
.decl edge(x:number, y:number)
.decl path(x:number, y:number)

edge(1, 2).
edge(2, 3).

path(X, Y) :- edge(X, Y).
path(X, Z) :- path(X, Y), edge(Y, Z).
```

Iteration 0 (facts):
- edge = {(1,2), (2,3)}
- path = {}

Iteration 1:
- Apply rule 1: path = {(1,2), (2,3)}

Iteration 2:
- Apply rule 2: path = {(1,2), (2,3), (1,3)}

Iteration 3:
- No new tuples → fixpoint reached

## Safety Rules

All Datalog programs must be **safe** and **stratified**.

### Safety

**Rule**: Every variable in the head must appear in a positive body atom.

**Valid**:
```datalog
path(X, Y) :- edge(X, Y).
path(X, Z) :- path(X, Y), edge(Y, Z).
```

**Invalid**:
```datalog
(* Y appears in head but not in any positive atom *)
bad(X, Y) :- edge(X, Z).
```

**Rationale**: Unsafe rules can produce infinite results.

### Stratification

**Rule**: No relation can depend on its own negation (directly or indirectly).

**Valid**:
```datalog
.decl base(x:number)
.decl derived(x:number)

derived(X) :- base(X), !derived(X).  (* ERROR: cycle *)
```

**Invalid**:
```datalog
(* p depends on !q, q depends on !p - negation cycle *)
p(X) :- edge(X, Y), !q(X).
q(X) :- edge(X, Y), !p(X).
```

**Rationale**: Negation cycles have no well-defined semantics.

## Type Checking

Datalog performs type checking on facts and rules:

### Type Mismatches in Facts

```sml
Datalog.validate ".decl edge(x:number, y:number)
edge(\"hello\", 2).";

(* Returns: "Error: Type mismatch in fact edge(...):
             expected number, got symbol for parameter x" *)
```

### Arity Mismatches

```sml
Datalog.validate ".decl edge(x:number, y:number)
edge(1, 2, 3).";

(* Returns: "Error: Atom edge/3 does not match declaration edge/2" *)
```

### Undeclared Relations

```sml
Datalog.validate ".decl edge(x:number, y:number)
path(1, 2).";

(* Returns: "Error: Relation 'path' used in fact but not declared" *)
```

## Advanced Examples

### Ancestor Queries

```sml
val family = ".decl parent(p:string, c:string)
.decl ancestor(a:string, d:string)
.decl descendant(p:string, d:string)

parent(\"Alice\", \"Bob\").
parent(\"Bob\", \"Carol\").
parent(\"Carol\", \"Dan\").

(* Ancestors are parents or ancestors of parents *)
ancestor(P, C) :- parent(P, C).
ancestor(A, D) :- ancestor(A, X), parent(X, D).

(* Descendants are the inverse *)
descendant(P, D) :- ancestor(D, P).

.output ancestor
.output descendant";

Datalog.execute family;
```

### Siblings

```sml
val siblings = ".decl parent(p:string, c:string)
.decl sibling(x:string, y:string)

parent(\"Alice\", \"Bob\").
parent(\"Alice\", \"Carol\").
parent(\"Alice\", \"Dan\").

(* Two people with the same parent are siblings *)
sibling(X, Y) :- parent(P, X), parent(P, Y).

.output sibling";

Datalog.execute siblings;
(* Includes reflexive pairs like (Bob, Bob) and symmetric pairs *)
```

### Set Difference with Negation

```sml
val diff = ".decl all(x:number)
.decl excluded(x:number)
.decl result(x:number)

all(1).
all(2).
all(3).
all(4).

excluded(2).
excluded(4).

result(X) :- all(X), !excluded(X).

.output result";

Datalog.execute diff;
(* Returns: result\n1\n3 *)
```

### Graph Reachability

```sml
val graph = ".decl edge(x:number, y:number)
.decl reachable(x:number, y:number)

edge(1, 2).
edge(2, 3).
edge(3, 4).
edge(2, 5).
edge(5, 6).

reachable(X, Y) :- edge(X, Y).
reachable(X, Z) :- reachable(X, Y), edge(Y, Z).

.output reachable";

Datalog.execute graph;
(* All pairs (x,y) where y is reachable from x *)
```

## Implementation Notes

### Architecture

- **Parser**: JavaCC-based parser (`DatalogParserImpl`)
- **AST**: Immutable syntax tree (`DatalogAst`)
- **Analyzer**: Safety and stratification checker (`DatalogAnalyzer`)
- **Evaluator**: Semi-naive fixpoint engine (`DatalogEvaluator`)
- **Type System**: Integration with Morel types

### Evaluation Engine

The evaluator uses:
- **Tuple representation**: Immutable lists of values
- **Hash-based sets**: For efficient duplicate elimination
- **Fixpoint iteration**: Until no new tuples are derived
- **Join algorithm**: Nested loop joins with variable binding
- **Pattern matching**: Unification-based matching

### Current Limitations

- No comparison operators (`<`, `>`, `<=`, `>=`, `!=`)
- No arithmetic expressions
- No aggregation (`count`, `sum`, `min`, `max`)
- No built-in predicates beyond equality
- No user-defined functions
- No incremental evaluation (full re-computation on each query)

### Future Enhancements

Planned improvements:
- Comparison operators in rule bodies
- Arithmetic expressions in rule heads
- Aggregation support
- Better error messages with source positions
- Index-based joins for better performance
- Magic sets optimization
- Integration with Morel's `from` expressions

## Best Practices

### Naming Conventions

- **Relations**: lowercase with underscores (`edge`, `parent_of`)
- **Variables**: uppercase letters (`X`, `Y`, `Person`)
- **Constants**: lowercase for symbols, quoted for strings

### Writing Efficient Rules

1. **Put selective atoms first**: More restrictive conditions early in the body
2. **Avoid Cartesian products**: Ensure variables connect atoms
3. **Use appropriate base cases**: Initialize recursive rules properly

**Good**:
```datalog
result(X) :- small_relation(X), large_relation(X, Y).
```

**Less efficient**:
```datalog
result(X) :- large_relation(X, Y), small_relation(X).
```

### Debugging

Use `Datalog.validate` to check for errors before execution:

```sml
val prog = "...";
val typeResult = Datalog.validate prog;
if String.isPrefix "Error:" typeResult
then raise Fail typeResult
else Datalog.execute prog;
```

## References

- [Datalog (Wikipedia)](https://en.wikipedia.org/wiki/Datalog)
- [What You Always Wanted to Know About Datalog (And Never Dared to Ask)](https://personal.utdallas.edu/~gupta/courses/acl/papers/datalog-paper.pdf)
- [Foundations of Databases (Abiteboul, Hull, Vianu)](http://webdam.inria.fr/Alice/)

## See Also

- [Morel Language Reference](REFERENCE.md)
- [Relational Operators in Morel](RELATIONAL.md)
