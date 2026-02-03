<!--
{% comment %}
Licensed to Julian Hyde under one or more contributor license
agreements.  See the NOTICE file distributed with this work
for additional information regarding copyright ownership.
Julian Hyde licenses this file to you under the Apache
License, Version 2.0 (the "License"); you may not use this
file except in compliance with the License.  You may obtain a
copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
either express or implied.  See the License for the specific
language governing permissions and limitations under the
License.
{% endcomment %}
-->

# Datalog

Morel includes support for Datalog, a declarative logic programming
language commonly used for program analysis, knowledge representation,
and deductive databases.

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
(* Returns: {edge=[{x=1,y=2},{x=2,y=3},{x=3,y=4}]}
     : {edge:{x:int, y:int} list} variant *)
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
(* Returns all reachable pairs *)
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

### Comparison Operators

Rules can include comparisons between variables and constants:

```datalog
.decl num(n:number)
.decl small(n:number)
num(1). num(5). num(10).
small(X) :- num(X), X < 7.       (* less than *)
```

Supported operators:
- `=` - equality
- `!=` - not equal
- `<` - less than
- `<=` - less than or equal
- `>` - greater than
- `>=` - greater than or equal

Examples:
```datalog
(* Find self-loops in a graph *)
self_loop(X) :- edge(X, Y), X = Y.

(* Distinct pairs only *)
sibling(X, Y) :- parent(P, X), parent(P, Y), X != Y.

(* Ordered pairs to avoid duplicates *)
sibling(X, Y) :- parent(P, X), parent(P, Y), X < Y.
```

### Arithmetic Expressions

Head atoms can contain arithmetic expressions:

```datalog
.decl fact(n:number, value:number)
fact(0, 1).
fact(N + 1, value * (N + 1)) :- fact(N, value), N < 10.
```

This computes factorial values: `{(0,1), (1,1), (2,2), (3,6), ...}`.

Supported operators:
- `+` - addition
- `-` - subtraction
- `*` - multiplication

Variables in arithmetic expressions must be grounded by positive
body atoms.

### Input Directive

Load facts from CSV files:

```datalog
.decl dept(deptno:number, dname:string, loc:string)
.input dept "data/scott/depts.csv"
.output dept
```

The CSV file should have a header row matching the relation's
parameter names.

### Output Directive

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

Executes a Datalog program and returns a variant:

```sml
Datalog.execute : string -> 'a variant
```

**Return type**:
- Single output: `{relation: element_type list} variant`
- Multiple outputs: `{rel1: type1 list, rel2: type2 list, ...} variant`
- No outputs: `unit variant`

**Example**:
```sml
val result = Datalog.execute ".decl num(n:number)
num(1). num(2). num(3).
.output num";
(* result = {num=[1,2,3]} : {num:int list} variant *)
```

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
(* Returns: "{edge:{x:int, y:int} list}" *)
```

**Return values**:
- Success: Type representation like `"{edge:{x:int, y:int} list}"`
- Parse error: `"Parse error: ..."`
- Semantic error: `"Compilation error: ..."` (safety, stratification)

### Datalog.translate

Translates a Datalog program to Morel source code:

```sml
Datalog.translate : string -> string option
```

**Example**:
```sml
Datalog.translate ".decl edge(x:number, y:number)
.decl path(x:number, y:number)
edge(1,2). edge(2,3).
path(X,Y) :- edge(X,Y).
path(X,Z) :- path(X,Y), edge(Y,Z).
.output path";
(* Returns: SOME "let
  val edge = [(1, 2), (2, 3)]
  val path =
    Relational.iterate edge
      (fn (allPath, newPath) =>
        from (x, y) in newPath, (v0, z) in edge
          where y = v0 yield (x, z))
in
  {path = from (x, y) in path}
end" *)
```

## Evaluation

Morel uses **semi-naive evaluation** for efficient fixpoint
computation:

1. **Initialize**: Start with facts
2. **Iterate**: Apply rules to derive new tuples using only newly
   derived tuples from the previous iteration
3. **Fixpoint**: Stop when no new tuples are derived
4. **Output**: Return results for output relations

## Safety Rules

All Datalog programs must be **safe** and **stratified**.

### Safety

**Rule**: Every variable in the head must appear in a positive
body atom (not inside an arithmetic expression or negated atom).

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

**Rule**: No relation can depend on its own negation (directly or
indirectly).

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
(* Returns: "Compilation error: Type mismatch in fact edge(...):
             expected number, got string for parameter x" *)
```

### Arity Mismatches

```sml
Datalog.validate ".decl edge(x:number, y:number)
edge(1, 2, 3).";
(* Returns: "Compilation error: Atom edge/3 does not match
             declaration edge/2" *)
```

### Undeclared Relations

```sml
Datalog.validate ".decl edge(x:number, y:number)
path(1, 2).";
(* Returns: "Compilation error: Relation 'path' used in fact
             but not declared" *)
```

## Examples

### Factorial

```sml
Datalog.execute ".decl fact(n:number, value:number)
fact(0, 1).
fact(N + 1, value * (N + 1)) :- fact(N, value), N < 10.
.output fact";
(* Returns factorials 0! through 10!:
   {fact=[{n=0,value=1},{n=1,value=1},{n=2,value=2},
          {n=3,value=6},{n=4,value=24},...]} *)
```

### Ancestors

```sml
val family = ".decl parent(p:string, c:string)
.decl ancestor(a:string, d:string)
.decl descendant(p:string, d:string)

parent(\"Alice\", \"Bob\").
parent(\"Bob\", \"Carol\").
parent(\"Carol\", \"Dan\").

ancestor(P, C) :- parent(P, C).
ancestor(A, D) :- ancestor(A, X), parent(X, D).
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

(* Distinct pairs only *)
sibling(X, Y) :- parent(P, X), parent(P, Y), X != Y.

.output sibling";

Datalog.execute siblings;
(* Returns: {sibling=[{x="Bob",y="Carol"},{x="Carol",y="Bob"}]} *)
```

### Set Difference with Negation

```sml
val diff = ".decl all(x:number)
.decl excluded(x:number)
.decl result(x:number)

all(1). all(2). all(3). all(4).
excluded(2). excluded(4).
result(X) :- all(X), !excluded(X).

.output result";

Datalog.execute diff;
(* Returns: {result=[1,3]} *)
```

### Loading External Data

```sml
Datalog.execute ".decl adj(state:string, adjacent:string)
.decl result(state:string)
.input adj \"data/map/adjacent-states.csv\"
result(state) :- adj(state, \"FL\"), adj(state, \"TN\").
.output result";
(* Returns states adjacent to both Florida and Tennessee *)
```

### Odd Cycle Detection

```sml
Datalog.execute ".decl edge(x:symbol, y:symbol)
.decl odd_path(x:symbol, y:symbol)
.decl exists_odd_cycle()

edge(\"a\", \"b\").
edge(\"b\", \"c\").
edge(\"c\", \"a\").

odd_path(X, Y) :- edge(X, Y).
odd_path(X, Y) :- odd_path(X, Z), edge(Z, U), edge(U, Y).
exists_odd_cycle() :- odd_path(X, X).

.output exists_odd_cycle";
(* Returns: {exists_odd_cycle=[()]} if odd cycle exists *)
```

## Best Practices

### Naming Conventions

- **Relations**: lowercase with underscores (`edge`, `parent_of`)
- **Variables**: uppercase letters (`X`, `Y`, `Person`)
- **Constants**: numbers, quoted strings, or lowercase symbols

### Writing Efficient Rules

1. **Put selective atoms first**: More restrictive conditions early
2. **Avoid Cartesian products**: Ensure variables connect atoms
3. **Use appropriate base cases**: Initialize recursive rules properly

### Debugging

Use `Datalog.validate` to check for errors before execution:

```sml
val prog = "...";
val typeResult = Datalog.validate prog;
(* Check if typeResult starts with "Error:" or "Parse error:" *)
```

Use `Datalog.translate` to see the generated Morel code.

## References

- [Datalog (Wikipedia)](https://en.wikipedia.org/wiki/Datalog)
- [What You Always Wanted to Know About Datalog (And Never Dared
  to Ask)](https://personal.utdallas.edu/~gupta/courses/acl/papers/datalog-paper.pdf)
- [Foundations of Databases (Abiteboul, Hull, Vianu)](http://webdam.inria.fr/Alice/)

## See Also

- [Morel Language Reference](REFERENCE.md)
- [Query expressions in Morel](query.md)
