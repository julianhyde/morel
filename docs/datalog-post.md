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

# Datalog on Morel

This week we
[added Datalog support](https://github.com/hydromatic/morel/commit/62581437ac9c8dc415b159fdc9d6abc7eb588e9a)
to Morel &mdash; not by building a Datalog engine,
but by adding a language feature called predicate inversion.

You can now write queries in the
[Souffl&eacute;](https://souffle-lang.github.io/) dialect of Datalog
and execute them using Morel's usual runtime.

This demonstrates that Morel now supports both query paradigms &mdash;
Datalog's relational calculus and Morel's native relational algebra
&mdash; and you can freely switch between them. But what are these
paradigms, and why does it matter?

## The two paradigms

The two paradigms originate in set theory, and continue
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
closure of a relation, the algebra query returns a set that is the
union of the points that are one step away, two steps away, and so
forth. In calculus, the value is boolean: whether there is a path from
one point to another.

For more complex fixed-point queries, the algebra programmer must
define a data type with a semilattice structure.  Consider, for
example, a query to find all pairs of nodes connected by no more than
five steps.  In algebra, the data type is now a set of `(source,
destination, distance)` triples combined by taking the minimum
distance.  In calculus, the data type remains boolean: the result of
the function `has_path_within(source, destination, distance)`.  The
boolean function is easier to write, and easier for the query planner
to understand.

Until now, if a programmer had to solve a problem with mixed workload,
they would need to switch languages.  Because of a new feature called
predicate inversion, Morel now supports both paradigms.

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

You can now run the following program from Morel's shell:

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

The program is passed (as a string literal) as an argument to the
`Datalog.execute` function, and the Souffl&eacute; `symbol` and
`number` types in the `.decl` directive have been mapped to Morel
`string` and `int` types, but is otherwise unchanged.

(Adding a `Datalog` structure, with functions `execute`, `translate`
and `validate`, seemed preferable to writing a whole Datalog shell and
testing framework. Facts and rules have the same syntax as
Souffl&eacute;, as does the `.output` directive.  The `.input`
directive, not shown in this example, has a new optional *filePath*
argument.)

## Translating Datalog to Morel

The translation makes concrete the equivalence that Codd's Theorem
promises: each Datalog construct has a direct counterpart in Morel.

One way to support Datalog would have been to implement a Datalog
engine, but this would have been a major task and would not have
benefited the rest of Morel. Instead, we have extended the Morel
language with Datalog-like constructs; this has made the Morel
language more powerful, and made Datalog translation straightforward.

The Datalog-to-Morel translator has a structure that will be familiar
to anyone who has implemented a compiler that translates a high-level
language to a lower-level language. Three steps are executed in
succession:

1. The *parser* converts a Datalog string to a parse tree.
2. The *validator* makes sure that the program is valid (that rules
   are safe, grounded and stratified) and deduces its type.
3. The *translator* generates a Morel program that is equivalent to
   the Datalog program.

Parsing and validation follow standard patterns, but let's look at
the translation algorithm in a little more detail.
Here is the translation to Morel of the earlier Datalog program:

<pre style="background-color: #f0f0f0; padding: 1em; border-radius: 4px;"><code><span style="color: blue;">let</span>
  <span style="color: blue;">val</span> edge_facts = [(1, 2), (2, 3)]
  <span style="color: blue;">fun</span> edge (x, y) = (x, y) <span style="color: blue;">elem</span> edge_facts
  <span style="color: blue;">fun</span> path (x, y) =
    edge (x, y) <span style="color: blue;">orelse</span>
    (<span style="color: blue;">exists</span> v0 <span style="color: blue;">where</span> path (x, v0) <span style="color: blue;">andalso</span> edge (v0, y))
<span style="color: blue;">in</span>
  {path = <span style="color: blue;">from</span> x, y <span style="color: blue;">where</span> path (x, y)}
<span style="color: blue;">end</span>
</code></pre>

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
record has a single field named `path` that is a list of
`{x:int, y:int}` records.

## How Morel does it

The magic lies not in the Datalog-to-Morel converter but in the Morel
language itself. Over the last few months, we have added to Morel a
capability called *predicate inversion*, the ability to deduce a set
from a boolean expression.

At the heart of the generated Morel program is a query: `from x, y
where path (x, y)`.  It differs from a regular query in that the
variables `x` and `y` are *unbounded*.  (In a conventional query,
every variable is *bounded*, meaning it iterates over a collection, as
do `d` and `e` in `from d in departments, e in employees`.)

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
patterns, including boolean functions that check collection membership
(like `edge`) or compute transitive closure (like `path`).

## Mixing styles

The net result is that predicate inversion allows you to freely mix
Datalog-style queries (defined by boolean expressions and functions)
with the relational algebra-style queries (defined by `from`,
`exists`, `join` and set operations).

The following query is in a hybrid style.

<pre style="background-color: #f0f0f0; padding: 1em; border-radius: 4px;"><code><span style="color: green;">(* Calculus style: recursive reachability *)</span>
<span style="color: blue;">fun</span> edge (x, y) = (x, y) <span style="color: blue;">elem</span> [(1,2), (2,3), (3,4), (2,4)];
<span style="color: blue;">fun</span> reachable (x, y) =
  edge (x, y) <span style="color: blue;">orelse</span>
  <span style="color: blue;">exists</span> z <span style="color: blue;">where</span> edge (x, z) <span style="color: blue;">andalso</span> reachable (z, y);

<span style="color: green;">(* Algebra style: count reachable nodes per source *)</span>
<span style="color: blue;">from</span> source <span style="color: blue;">in</span> [1, 2, 3, 4]
  <span style="color: blue;">yield</span> {source,
         reachable_count = count (<span style="color: blue;">from</span> target <span style="color: blue;">where</span> reachable (source, target))}
</code></pre>

The `edge` and `reachable` functions define graph reachability in a
Datalog style, using recursion and boolean return values. The `from`
query is in the algebra style, but uses predicate inversion to
generate all values of the unbounded `target` variable for which
`reachable (source, target)` is true. Predicate inversion provides the
junction between the two styles.

## Conclusion

Morel now unifies the calculus and algebra styles of writing queries.
The new Datalog interface showcases this capability, but you can also
use the calculus style in Morel programs, where you can freely mix it
with the algebra style and functional programming.

Notice that we have made no claims about the **efficiency** of the
implementation.  Our goal was to increase the expressive power of the
language, and we have achieved that goal.  Morel's internal
representation is algebraic &mdash; using relational operators, other
operators provided by functions, and iteration to a fixed point
&mdash; and from this point we can apply conventional
query-optimization techniques.

To keep things simple, we have not discussed **evaluation models**.
Datalog uses forward chaining (bottom-up evaluation) while boolean
functions give the impression that backwards chaining (top-down
evaluation) is being used. For most queries both approaches are valid,
and the planner would ideally consider both strategies along with
optimizations such as join re-ordering, magic sets, semi-na&iuml;ve
evaluation, and materialized views. But there are queries where the
evaluation model matters (say, they would terminate under one model
but not another), and for these cases it is important that we define
Morel's operational semantics.

The predicate inversion algorithm needs to evolve and mature. It has
been tested over a wide array of queries, but there are still cases
where it fails to invert a predicate, or fails to remove a condition
that has been fully satisfied by a generator. (We hope to write more
about predicate inversion, generators, and subsuming predicates, in a
future article.)

Please download Morel and give it a try! (Morel has both
[Java](https://github.com/hydromatic/morel) and
[Rust](https://github.com/hydromatic/morel-rust) versions, but Datalog
and predicate inversion require the Java version for now.)
