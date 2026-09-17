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


## Synopsis

<pre>
type <a id='ty' href="#ty-impl">ty</a>
datatype <a id='pat' href="#pat-impl">pat</a>
  = ID_PAT of {i: int, name: string, ty: ty}
  | WILDCARD of ty
  | TUPLE_PAT of pat list
  | RECORD_PAT of (string * pat) list
  | CON0_PAT of string * ty
  | CON_PAT of string * pat * ty
  | OPAQUE_PAT
datatype <a id='join_kind' href="#join_kind-impl">join_kind</a> = INNER | LEFT | RIGHT | FULL
datatype <a id='exp' href="#exp-impl">exp</a>
  = FILTER of {condition: exp, input: exp, ordinalPat: pat option, row: pat}
  | PROJECT of {exp: exp, input: exp, ordinalPat: pat option, row: pat}
  | JOIN of {condition: exp, kind: join_kind, leftInput: exp, leftRow: pat, ordinalPat: pat option, rightInput: exp, rightRow: pat}
  | GROUP of {aggregates: (string * exp * exp option * ty) list, input: exp, keys: (string * exp) list, ordinalPat: pat option, row: pat}
  | SORT of {input: exp, key: exp, ordinalPat: pat option, row: pat}
  | UNORDER of exp
  | SKIP of {count: exp, input: exp}
  | TAKE of {count: exp, input: exp}
  | IF_EMPTY of {input: exp, otherwise: exp}
  | UNION of {inputs: exp list, unique: bool}
  | INTERSECT of {inputs: exp list, unique: bool}
  | EXCEPT of {inputs: exp list, unique: bool}
  | ID of pat
  | LITERAL of variant
  | BUILTIN of string * ty
  | SELECTOR of string * ty
  | APPLY of exp * exp
  | TUPLE of exp list
  | RECORD_EXP of (string * exp) list
  | FN of pat * exp
  | LET of {body: exp, pat: pat, value: exp}
  | CASE of exp * (pat * exp) list
  | OPAQUE

val <a id='print' href="#print-impl">print</a> : exp -> string
val <a id='typeOf' href="#typeOf-impl">typeOf</a> : exp -> ty
val <a id='printType' href="#printType-impl">printType</a> : ty -> string
</pre>

<a id="ty-impl"></a>
<h3><code><strong>type</strong> ty</code></h3>

is a type as the compiler holds it. A rule cannot write one, only pass
one along: `typeOf` gives an expression's, and the constructors of
`exp` derive the type of what they build.

<a id="pat-impl"></a>
<h3><code><strong>datatype</strong> pat</code></h3>

is a pattern. A node binds its element to a pattern -- `ID_PAT` named
`$0`, `$1` or `$ordinal` -- and a function, a `let` and a `case`
bind theirs. A pattern the view does not render is `OPAQUE_PAT`.

<a id="join_kind-impl"></a>
<h3><code><strong>datatype</strong> join_kind</code></h3>

is the kind of a join.

<a id="exp-impl"></a>
<h3><code><strong>datatype</strong> exp</code></h3>

is an expression: a node of the relational tree, or what its expressions
are made of. A value of this type is a view of the compiler's own tree:
matching a constructor renders one level of it, and applying a
constructor builds a node whose type is derived from its parts, so a
rule cannot build an ill-typed node. What the view does not render is
`OPAQUE`, which a rule can pass along but not look into.

<a id="print-impl"></a>
<h3><code>print</code></h3>

`print e` (or `e.print ()`) prints an expression as a plan: a tree one node per line, with the
collection type of each node, as `Sys.planOf` prints a query; anything
else as an expression.

<a id="typeOf-impl"></a>
<h3><code>typeOf</code></h3>

`typeOf e` (or `e.typeOf ()`) returns the type of an expression.

<a id="printType-impl"></a>
<h3><code>printType</code></h3>

`printType t` prints a type.

[//]: # (end:lib/core)
