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

# Core structure

[Up to index](index.md)

[//]: # (start:lib/core)
The `Core` structure surfaces Morel's typed, post-desugar internal
expression representation as a first-class datatype, so that
metaprograms (such as the planner — see issue #359) can construct,
inspect, and pretty-print expressions as ordinary values.

## Synopsis

<pre>
datatype <a id='expr' href="#expr-impl">expr</a>
  = APPLY of expr * expr * Type.t
  | E_RECORD of (string * expr) list * Type.t
  | FIELD of expr * string * Type.t
  | FILTER of expr * expr
  | INT_LITERAL of int
  | LIST_LITERAL of expr list * Type.t
  | ORDER of expr * expr
  | PLUS of expr * expr * Type.t
  | PROJECT of expr * expr
  | STRING_LITERAL of string
  | TUPLE of expr list
  | UNIT_LITERAL
  | VAR of string * Type.t
</pre>

<a id="expr-impl"></a>
<h3><code><strong>datatype</strong> expr</code></h3>

is a Morel expression in its typed, post-desugar internal form.
Scalar constructors include `INT_LITERAL`, `VAR`, `APPLY`, `FIELD`,
`E_RECORD` (E-prefixed to avoid clashing with `Variant`'s `RECORD`),
`TUPLE`, `LIST_LITERAL`, and `PLUS`. Relational constructors include
`FILTER` and `PROJECT`; a `from`-expression desugars to a tree of
these.

[//]: # (end:lib/core)
