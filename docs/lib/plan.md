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
val <a id='core' href="#core-impl">core</a> : 'a -> Core.expr
</pre>

<a id="core-impl"></a>
<h3><code>core</code></h3>

`core e` returns the typed Core form of `e` as a `Core.expr` value.

The argument is type-checked but *not* evaluated; the compiler
intercepts every call to `Plan.core` and emits a literal
`Core.expr` value at the call site (analogous to `typeof`).

Free variables in the argument resolve in the enclosing environment
for type-checking, but appear in the output as `VAR` nodes — they
are not folded to their runtime values.

[//]: # (end:lib/plan)
