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

# Table structure

[Up to index](index.md)

[//]: # (start:lib/table)
The `Table` structure provides measures and the dimensional contexts in
which they are evaluated.

A `measure` is an expression evaluated in a `context`. Typically a measure
belongs to a `table` and is based on an aggregate function, and the context
is the set of constraints that determine which of the table's rows are
included. Because a measure is an abstraction, you can use it without access
to, or even knowledge of, the table it aggregates over.

A measure has type `('p, 'e, 'a, 'r) measure`, whose parameters follow the
data flow: `'p` is the table's parameter type, `'e` is the element (row)
type, `'a` is the argument supplied when the measure is evaluated (usually
`unit`), and `'r` is the result type.

## Synopsis

<pre>
type ('p, 'e, 'a, 'r) <a id='measure' href="#measure-impl">measure</a>
type ('p, 'e) <a id='cx' href="#cx-impl">cx</a>
type ('p, 'e) <a id='table' href="#table-impl">table</a>

val <a id='measure' href="#measure-impl">measure</a> : (('p, 'e) cx -> 'r) -> ('p, 'e, unit, 'r) measure
val <a id='measure_fn' href="#measure_fn-impl">measure_fn</a> : ('a * ('p, 'e) cx -> 'r) -> ('p, 'e, 'a, 'r) measure
val <a id='evaluate' href="#evaluate-impl">evaluate</a> : ('p, 'e, 'a, 'r) measure * 'a * ('p, 'e) cx -> 'r
val <a id='eval' href="#eval-impl">eval</a> : ('p, 'e, 'a, 'r) measure * 'a -> 'r
val <a id='restrict' href="#restrict-impl">restrict</a> : ('p, 'e, 'a, 'r) measure * string * ('e -> bool) -> ('p, 'e, 'a, 'r) measure
val <a id='restrict_anon' href="#restrict_anon-impl">restrict_anon</a> : ('p, 'e, 'a, 'r) measure * ('e -> bool) -> ('p, 'e, 'a, 'r) measure
val <a id='relax' href="#relax-impl">relax</a> : ('p, 'e, 'a, 'r) measure * string -> ('p, 'e, 'a, 'r) measure
val <a id='override' href="#override-impl">override</a> : ('p, 'e, 'a, 'r) measure * ('e -> 'b) * 'b -> ('p, 'e, 'a, 'r) measure
val <a id='test' href="#test-impl">test</a> : ('p, 'e) cx * 'e -> bool
val <a id='paramOf' href="#paramOf-impl">paramOf</a> : ('p, 'e) cx -> 'p
val <a id='toString' href="#toString-impl">toString</a> : ('p, 'e) cx -> string
val <a id='table' href="#table-impl">table</a> : 'e bag * 'p -> ('p, 'e) table
val <a id='elements' href="#elements-impl">elements</a> : ('p, 'e) table -> 'e bag
val <a id='param' href="#param-impl">param</a> : ('p, 'e) table -> 'p
</pre>

<a id="measure-impl"></a>
<h3><code><strong>type</strong> ('p, 'e, 'a, 'r) measure</code></h3>

is the type of a measure: an expression of type `'r` evaluated in a
     `('p, 'e) cx` given an argument of type `'a`.

<a id="cx-impl"></a>
<h3><code><strong>type</strong> ('p, 'e) cx</code></h3>

is the type of a context: the constraints, and the parameter, under
     which a measure is evaluated.

<a id="table-impl"></a>
<h3><code><strong>type</strong> ('p, 'e) table</code></h3>

is the type of a table: a bag of elements of type `'e` together with a
     parameter of type `'p`.

<a id="measure-impl"></a>
<h3><code>measure</code></h3>

`measure f` is a measure whose value is `f c` when evaluated in context `c`.

<a id="measure_fn-impl"></a>
<h3><code>measure_fn</code></h3>

`measure_fn f` is a measure whose value is `f (a, c)` when evaluated with argument `a`
     in context `c`.

<a id="evaluate-impl"></a>
<h3><code>evaluate</code></h3>

`evaluate (m, a, c)` (or `m.evaluate (a, c)`) evaluates measure `m` with argument `a` in context `c`.

<a id="eval-impl"></a>
<h3><code>eval</code></h3>

`eval (m, a)` (or `m.eval a`) evaluates measure `m` with argument `a` in the current context.

<a id="restrict-impl"></a>
<h3><code>restrict</code></h3>

`restrict (m, label, p)` (or `m.restrict (label, p)`) is measure `m` with an additional filter `p`, labeled `label`, on the
     context.

<a id="restrict_anon-impl"></a>
<h3><code>restrict_anon</code></h3>

`restrict_anon (m, p)` (or `m.restrict_anon p`) is measure `m` with an additional anonymous filter `p` on the context.

<a id="relax-impl"></a>
<h3><code>relax</code></h3>

`relax (m, label)` (or `m.relax label`) is measure `m` with the filters labeled `label` removed from the
     context.

<a id="override-impl"></a>
<h3><code>override</code></h3>

`override (m, proj, value)` (or `m.override (proj, value)`) is measure `m` evaluated in a context where the dimension `proj` is
     overridden to `value`.

<a id="test-impl"></a>
<h3><code>test</code></h3>

`test (c, e)` (or `c.test e`) is whether context `c` admits element `e`.

<a id="paramOf-impl"></a>
<h3><code>paramOf</code></h3>

`paramOf c` (or `c.paramOf ()`) is the current parameter of context `c`.

<a id="toString-impl"></a>
<h3><code>toString</code></h3>

`toString c` (or `c.toString ()`) is a canonical string describing the constraints of context `c`.

<a id="table-impl"></a>
<h3><code>table</code></h3>

`table (elements, param)` is a table with the given `elements` and `param`.

<a id="elements-impl"></a>
<h3><code>elements</code></h3>

`elements t` (or `t.elements ()`) is the bag of elements of table `t`.

<a id="param-impl"></a>
<h3><code>param</code></h3>

`param t` (or `t.param ()`) is the parameter of table `t`.

[//]: # (end:lib/table)
