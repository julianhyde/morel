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

TODO:
insert hyperlinks;
proof read for spelling and grammar;
proof read for sentence flow;
factor out blog post on variant.
{% endcomment %}
-->

# Datalog on Morel

Following a recent
[commit to Morel](https://github.com/hydromatic/morel/commit/62581437ac9c8dc415b159fdc9d6abc7eb588e9a),
you can now parse, validate and execute programs in Datalog. Consider
the following program, in the
[Souffl&eacute;](https://souffle-lang.github.io/) dialect of Datalog,
to compute transitive closure of an `edge` relation.

<pre><code>.decl edge(x:number, y:number)
.decl path(x:number, y:number)
edge(1,2).
edge(2,3).
path(X,Y) :- edge(X,Y).
path(X,Z) :- path(X,Y), edge(Y,Z).
.output path
</code></pre>

In a graph with nodes 1, 2 and 3, the `edge` relation defines edges
from 1 &rarr; 2 and 2 &rarr; 3. The derived `path` relation says that
there is a path between two nodes if (a) there is an edge, or (b)
there is an edge to an intermediate node and a path from that
intermediate node to the destination node.  From the edges {1 &rarr;
2, 2 &rarr; 3} it deduces the paths {1 &rarr; 2, 2 &rarr; 3, 1 &rarr;
3}.

The same program can be executed from Morel's shell:

<pre><code>Datalog.execute <span style="color: brown;">"
.decl edge(x:int, y:int)
.decl path(x:int, y:int)
edge(1,2).
edge(2,3).
path(X,Y) :- edge(X,Y).
path(X,Z) :- path(X,Y), edge(Y,Z).
.output path"</span>;
<i>
&gt; val it = {path=[{x=1,y=2},{x=2,y=3},{x=1,y=3}]}
&gt;   : {path:{x:int, y:int} list} variant</i>
</code></pre>

Morel's dialect is very similar to Souffl&eacute;. Facts and rules
have the same syntax, as does the `.output` directive. In the `.decl`
directive, we have changed the `symbol` and `number` types to `string`
and `int`, to be consistent with Morel's type system. (The `.input`
directive, not shown in this example, is also implemented, and has a
new optional *filePath* argument.)

You'll notice that the Datalog program is passed as a string literal
to a built-in function, `Datalog.execute`, and that the return is of
type `variant`.  Before we describe the new functions and the new
`variant` type, let's discuss why Datalog is so important.

## Why Datalog?

Datalog represents the *other* great paradigm for writing queries and
logic programs.

In the beginning, set theory provided two ways to define a set: you
could define a set by its properties (for example, the "red cars" set
is the set of all cars that are red) or by performing operations on
existing sets (intersect the set of all cars with the set of all red
objects).

The relational model for databases provides two ways to specify a
query.  In *relational calculus*, one specifies the logical properties
of the tuples to retrieve; in *relational algebra*, one specifies the
input relations and a sequence of operations (intersect, join, filter,
project) to apply to them.
[Codd's Theorem](https://en.wikipedia.org/wiki/Codd%27s_theorem)
proves that these languages have equivalent expressive power.

Datalog is based on relational calculus; Morel and SQL belong to the
relational algebra camp.

If the languages are equivalent, why does it matter? The languages
have different strengths.

We often wish to integrate queries with other programs, and relational
algebra fits more easily, especially into functional programs.
Relational algebra is essentially a subset of functional programming,
where the system provides a built-in function for each relational
operator.  Many modern languages do this, either in the form of
functions such as `List.map` and `List.filter` or in syntax such as
list comprehensions.

Some kinds of query are easier to express in one paradigm than the
other. Queries that iterate until they reach a fixed point are easier
to express in the calculus (Datalog). In the algebra, a query reaches
a fixed point when a value stops 'growing'.  For simple fixed-point
queries such as computing transitive closure, that value is a set,
combined using union. But for more complex fixed-point queries the
programmer needs to define a data type with the properties of a
partially-ordered set. In the calculus, the data type is boolean.

Consider, for example, a query to find all nodes in a graph reachable
from a given node in under five steps, and the length of the shortest
path. In algebra, the data type is a set of nodes and the length of
the shortest known path.

## Library functions

This change adds a `Datalog` structure with the following functions.

| Name        | Type                | Description |
|-------------|---------------------|-------------|
| `execute`   | `string -> variant` | Executes a Datalog program. Returns the result as a `variant`. |
| `translate` | `string -> string`  | Translates a Datalog program to an equivalent Morel program. |
| `validate`  | `string -> string`  | Parses and validates a Datalog program. Returns either a type or an error. |

## Variant

Notice that the return type of `execute` is declared as `variant`,
and appears in the shell as `{path:{x:int, y:int} bag} variant`.

To implement the `execute` function, we had a problem to solve. The
return type of a Datalog program, like any expression or query, varies
from one program to the next.  The program is not parsed until we call
the `execute` function, so cannot be known in advance. This is at odds
with Morel's static type system, which means that a function's return
type can depend only on the type (not value) of its arguments.

The solution was `variant` (added in
[#324](https://github.com/hydromatic/morel/issues/324)), a single type
that can represent all possible Morel values.

`variant` is a union of all primitive and composite types:

```sml
datatype variant =
    UNIT
  | BOOL of bool
  | INT of int
  | REAL of real
  | CHAR of char
  | STRING of string
  | LIST of variant list
  | BAG of variant list
  | VECTOR of variant list
  | VARIANT_NONE
  | VARIANT_SOME of variant
  | RECORD of (string * variant) list
  | CONSTANT of string
  | CONSTRUCT of string * variant
```

You can use labels like `INT`, `LIST`, `STRING` to construct and
deconstruct a value:

```sml
fun f v =
  case v of
    INT i => "it's an integer!"
   | LIST list => "it's a list!"
   | STRING s => "it's a string!"
   | _ => "it's something else!";
> val f = fn : variant -> string

f (INT 5);
> val it = "it's an integer!" : string

f (STRING "x");
> val it = "it's a string!" : string

f (LIST [INT 1, INT 2]);
> val it = "it's a list!" : string
```

Expressions like `LIST [INT 1, INT 2]` make it look like a composite
`variant` value consists of `variant` wrappers all the way down.  If
that were the case, converting large, deeply nested values to
`variant` would take considerable time and space. But the
representation is more efficient.  The implementation of `variant` is
a thin wrapper that contains a type and a native value. The runtime
can quickly convert any value to `variant` by adding that wrapper.

We have modified the shell's output to make working with variants more
intuitive.  Typically, the shell uses a colon (`:`) to separate a
runtime value from its static type.  However, when printing a
`variant` value, this convention shifts slightly:

```sml
val it = {path=[{x=1,y=2},{x=2,y=3},{x=1,y=3}]}
  : {path:{x:int, y:int} list} variant
```

In this case, the standard "value : type" separation is nuanced.
While `variant` is the static type, the description `{path:{x:int,
y:int} list}` is the dynamic type identified at runtime. Notably,
`variant` is not the polymorphic type that it appears to be.

But we've achieved our goal; `variant` delivers dynamically typed
values at low runtime cost while preserving the integrity and benefits
of the static type system.

## Translation

How did we implement support for Datalog? Underneath the library
functions, translation has a structure that will be familiar to anyone
who has implemented a compiler that translates a high-level language
to a lower-level language. Three steps are executed in succession:

1. The *parser* (generated by Claude) converts a Datalog string to a
   parse tree.
2. The *validator* (also generated by Claude) makes sure that the
   program is valid (that rules are safe, grounded and stratified)
   and deduces its type.
3. The *translator* generates a Morel program that is equivalent to
   the Datalog program.

Parsing and validation are straightforward, but let's look at
the translation algorithm in a little more detail.
Here is the translation to Morel of the earlier Datalog program:

```sml
let
  val edge_facts = [(1, 2), (2, 3)]
  fun edge (x, y) = (x, y) elem edge_facts
  fun path (x, y) =
    edge (x, y) orelse
    (exists v0 where path (x, v0) andalso edge (v0, y))
in
  {path = from x, y where path (x, y)}
end
```

You'll notice that the Datalog and Morel programs have the same
structure.  Datalog rules without a body (such as `edge(1,2)` and
`edge(2,3)`) are gathered into a list of tuples (`edge_facts`).

Each rule becomes a boolean function.  If there are several
comma-separated predicates in a rule's body, they are combined using
`andalso`.  If there are several rules of the same name, their
conditions are combined using `orelse`.  Invocations of a rule become
function calls, which, like rules, may be recursive.

The body of the rule `path(X,Z) :- path(X,Y), edge(Y,Z)` has a
variable, `Y`, that does not occur in the head. It is translated to
`exists v0`.

A Datalog program may have several `.output` directives.  The Morel
program returns a single value, a record with one field for each
directive. This program has one directive, `.output path`, so the
record has a single field named `path` that is a `bag` of
`{x:int, y:int}` records.

## Predicate inversion

As we just saw, translating Datalog to Morel is straightforward.
You might find that surprising, given that Datalog expresses problems
differently than relational algebra, and that when we include
recursion (transitive closure) it can compute things that relational
algebra cannot.

That magic lies not in the Datalog-to-Morel converter but in the Morel
language itself. Over the last few months, we have added to Morel a
capability called *predicate inversion*, the ability to deduce a set
from a boolean expression.

At heart of the generated Morel program is a query: `from x, y where
path (x, y)`.  It differs from a regular query in that the variables
`x` and `y` are *unbounded*.  (In a conventional query, every variable
is *bounded*, meaning it iterates over a collection, as do `d` and `e`
in `from d in depts, e in employees`.)

In principle, an unbounded variable iterates over every possible value
of its data type. This is fine for "small" data types like `boolean`,
`char`, and `enum Color { RED | GREEN | BLUE }`, but problematic for
"large" data types like `int` and `{b: boolean, i: int}` and infinite
data types like `string` and `int list`.

Morel allows unbounded variables in a program as long as there is a
predicate like `where x > 0 andalso x < 10` or `where e elem
employees` that connects it with a finite set. Invertible predicates
provide a way to generate the values of the variable. In Datalog
parlance, they ensure that the variable is *grounded*.

Morel's predicate inversion algorithm recognizes various predicate
patterns, including boolean functions
that check membership in a collection (like `edge`)
and that compute transitive closure (like `path`).

The net result is that predicate inversion allows you to freely mix
Datalog-style queries (defined by boolean expressions and functions)
with the relational algebra-style queries (defined by `from`,
`exists`, `join` and set operations)

## Conclusion

The Datalog implementation demonstrates that Morel is a very powerful
language.

We make no promises about the efficiency of the implementation.  Now
we can translate programs with unbounded variables into relational
algebra with iteration, we can use conventional techniques to optimize
that algebra.
