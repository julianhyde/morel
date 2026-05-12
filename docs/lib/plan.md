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
The `Plan` structure is the entry point for the rule-based optimizer
described in issue #359. In its phase-1a form it provides only a
single primitive — `core` — which reifies the typed Core of any Morel
expression as a runtime value of type `Core.expr`. Later phases will
add rule-based rewriting, a MEMO, and function-to-function
optimization.

## Synopsis

<pre>
val <a id='core' href="#core-impl">core</a> : 'a -> {value: 'a, expr: Core.expr}
val <a id='optimize' href="#optimize-impl">optimize</a> : {value: 'a, expr: Core.expr} * (Core.expr -> Core.expr option) list -> {value: 'a, expr: Core.expr}
val <a id='transform' href="#transform-impl">transform</a> : {value: 'a, expr: Core.expr} * (Core.expr -> Core.expr) -> {value: 'a, expr: Core.expr}
</pre>

<a id="core-impl"></a>
<h3><code>core</code></h3>

`core e` returns a record containing the runtime value of `e` and a
`Core.expr` value reflecting its typed Core form.

The compiler intercepts every call to `Plan.core`: it compiles `e`
once and pairs its value with the statically-derived `Core.expr`.
Free variables in `e` resolve in the enclosing environment for
type-checking, but appear in the reified `expr` as `VAR` nodes —
they are not folded to their runtime values.

<a id="optimize-impl"></a>
<h3><code>optimize</code></h3>

`optimize (planned, rules)` applies a list of rewrite rules to the reified `expr` of `planned`
and recompiles to produce a new planned value. The walker descends
into `expr` bottom-up; at every `Core.expr` node it tries each rule
in turn. A rule returns `SOME e'` to rewrite the node, or `NONE` to
leave it unchanged. After any rewrite the node is re-tried until no
rule fires (fixpoint per node). The resulting `Core.expr` is
re-typechecked against `α` in the current environment, compiled, and
evaluated.

For a one-shot deterministic rewrite, use `Plan.transform` instead.

<a id="transform-impl"></a>
<h3><code>transform</code></h3>

`transform (planned, f)` applies `f` to the reified `expr` of `planned` and recompiles to
produce a new planned value of the same Morel type. The transformer
`f` must be type-preserving; the resulting `Core.expr` is unreified,
re-typechecked against `α` in the current environment, then compiled
and evaluated to obtain the new `value`.

With `f` the identity, `Plan.transform (Plan.core e, fn x => x)`
round-trips: the resulting record has the same `expr` and a `value`
re-evaluated from it.

[//]: # (end:lib/plan)
