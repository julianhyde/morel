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
note that the impl is in java but could be ported to rust;
proof read for spelling and grammar;
proof read for sentence flow.
{% endcomment %}
-->

# Datalog on Morel

I am pleased to announce that Morel
[now supports Datalog](https://github.com/hydromatic/morel/commit/62581437ac9c8dc415b159fdc9d6abc7eb588e9a).

You can write queries in the
[Souffl&eacute;](https://souffle-lang.github.io/) dialect of Datalog
and execute them using Morel's usual runtime.

More impressive is that the Datalog implementation is extremely
simple.  This demonstrates that Morel supports both query language
paradigms &mdash; relational calculus and relational algebra &mdash;
and you can freely switch between them, even in the same query.

## The two paradigms

To understand why this matters, we need
to understand the two paradigms for writing queries.
The two paradigms originate in set theory, and continued
through the relational model into modern query languages.

Set theory provides two ways to define a set: the **intensional**
method defines the set by its properties (for example, "red cars" is
the set of all cars whose color is red), and the **extensional**
method creates the set by performing operations on existing sets
(intersect the set of all cars with the set of all red objects).

The relational model for databases provides two ways to specify a
query which mirror intensional and extensional set definitions.  In
**relational calculus**, one specifies the logical properties of the
tuples to retrieve from the input relations; in **relational
algebra**, one specifies the input relations and a sequence of
operations (intersect, join, filter, project) to apply to them.
[Codd's Theorem](https://en.wikipedia.org/wiki/Codd%27s_theorem)
proves that these languages have equivalent expressive power.

Query languages are generally based on one of those paradigms.  SQL is
largely based on algebra (although its `EXISTS` keyword shows the
influence of calculus).  Datalog is based on calculus.  Functional
programming languages (including Morel) are in the algebra camp; they
provide relational operators via higher-order functions like `map`,
`filter` and `reduce`, and sometimes provide syntactic sugar like
list-comprehensions.

If the languages are equivalent, why does it matter? The languages
have different strengths.

Algebra's strengths:
 * Algebra naturally extends to **bags and lists** (collections with
   ordering and/or duplicate values), while calculus only works on
   sets;
 * **Aggregate functions** are a more natural extension to algebra
     than calculus;
 * Mainstream programming languages are functional or procedural, so
   there is lower **impedance mismatch** embedding a query in a
   program or writing a user-defined function to be called from a
   query;
 * Developers familiar with mainstream programming languages
   find the calculus paradigm **difficult to learn**.

<!-- Calculus is often said to be more 'declarative' and algebra more
     'prescriptive'. It is true that algebra is a better language for
     plans (given a particular physical algebra). But both are
     declarative (e.g. a planner has license to re-order the joins in
     an algebra program, and knows whether a particular order is
     required by the program semantics or is just a convenient
     physical representation). -->

Calculus (epitomized by Datalog) excels at graph and deductive
queries, such as queries that iterate until they reach a fixed
point. As we shall see, it is just easier to write recursive queries
if they return a boolean than if they return a complex data type like
a set of tuples.

For simple fixed-point queries such as computing the transitive
closure of a relation, the algebra query returns a set,
combined using union. In calculus, the value is boolean: whether
there is a path from one point to another.

For more complex fixed-point queries, the algebra programmer must
define a data type with a semilattice structure.  Consider, for
example, a query to all pairs of nodes connected by no more than five
steps.  In algebra, the data type is now a set of `(source,
destination, distance)` triples combined by taking the minimum
distance.  In calculus, the data type remains boolean: the result of
the function `has_path_within(source, destination, distance)`.  The
boolean function is easier to write, and easier for the query planner
to understand.

Until now, if a programmer had to solve a problem with mixed workload,
they would need to switch languages.
Because of a new feature called predicate inversion,
Morel now supports both paradigms.

## The Datalog interface

The following program, in the
[Souffl&eacute;](https://souffle-lang.github.io/) dialect of Datalog,
computes the transitive closure of an `edge` relation.

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

You can now run that program from Morel's shell
practically unchanged:

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

The main change is that the program has been wrapped as a string
literal and passed as an argument to a new built-in function,
`Datalog.execute`.  (This seemed preferable to writing a
Datalog-specifc shell and testing framework.)

There are also minor changes to the dialect. Facts and rules
have the same syntax as Souffl&eacute;, as does the `.output` directive. In the `.decl`
directive, we have changed the `symbol` and `number` types to `string`
and `int`, to be consistent with Morel's type system. (The `.input`
directive, not shown in this example, is also implemented, and has a
new optional *filePath* argument.)

## Translating Datalog to Morel

<!-- TODO: Add a sentence connecting this to the thesis - the
     translation shows the structural correspondence between
     Datalog (calculus) and Morel (algebra). -->

How did we implement support for Datalog? Underneath the library
functions, translation has a structure that will be familiar to anyone
who has implemented a compiler that translates a high-level language
to a lower-level language. Three steps are executed in succession:

1. The *parser* converts a Datalog string to a parse tree.
2. The *validator* makes sure that the program is valid (that rules
   are safe, grounded and stratified) and deduces its type.
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

## How Morel does it

<!-- TODO: This section should be a brief (2-3 paragraph) summary.
     Consider linking to a separate predicate inversion post for
     details. -->

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

## Mixing styles

The net result is that predicate inversion allows you to freely mix
Datalog-style queries (defined by boolean expressions and functions)
with the relational algebra-style queries (defined by `from`,
`exists`, `join` and set operations).

<!-- TODO: Add an example that shows both styles in one program.
     Could adapt from the "Mixing Paradigms" example in
     datalog.md. -->

## Library functions

This change adds a `Datalog` structure with the following functions.

| Name        | Type                | Description |
|-------------|---------------------|-------------|
| `execute`   | `string -> variant` | Executes a Datalog program. Returns the result as a `variant`. |
| `translate` | `string -> string`  | Translates a Datalog program to an equivalent Morel program. |
| `validate`  | `string -> string`  | Parses and validates a Datalog program. Returns either a type or an error. |

Notice that the return type of `execute` is `variant`. This is a type
that can hold any Morel value; see [variant-post.md](variant-post.md)
for details.

## Conclusion

<!-- TODO: Rewrite conclusion to tie back to the thesis: Morel now
     unifies calculus and algebra styles. The Datalog interface is
     proof of this capability, not the main point. -->

The Datalog implementation demonstrates that Morel is a very powerful
language.

We make no promises about the efficiency of the implementation.  Now
we can translate programs with unbounded variables into relational
algebra with iteration, we can use conventional techniques to optimize
that algebra.
