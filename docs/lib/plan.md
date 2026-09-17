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

# Plan structure

[Up to index](index.md)

[//]: # (start:lib/plan)


## Synopsis

<pre>
val <a id='bodyOf' href="#bodyOf-impl">bodyOf</a> : ('a -> 'b) -> exp
val <a id='program' href="#program-impl">program</a> : (exp -> exp option) list -> ('a -> 'b) -> 'a -> 'b
</pre>

<a id="bodyOf-impl"></a>
<h3><code>bodyOf</code></h3>

`bodyOf f` returns the body of the function `f` as the compiler holds it, after
every pass and rule; the function's parameter is a free name in it.
`Core.print` prints it as `Sys.planOf` prints a query.

Raises `Fail` if `f` is not a function the compiler compiled, such as
a built-in.

<a id="program-impl"></a>
<h3><code>program</code></h3>

`program rules f` returns a function that computes what `f` computes, whose body is `f`'s
rewritten by `rules`.

Each rule is given a node of the body, after the nodes below it have
been rewritten, and returns what replaces it, or `NONE` if it does not
apply there. The rules are tried in the order given, the first that
fires is applied, and the node is tried again until none fires. The
compiler's own rules run before them at each node, so what a rule
leaves is simplified as the compiler would simplify it.

A rule must not change what a node means, and its replacement must have
the node's type; that much is checked, and a rule that breaks it raises.

Raises `Fail` if `f` is not a function the compiler compiled, such as a
built-in.

[//]: # (end:lib/plan)
