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
  | BOOL_LITERAL of bool
  | CASE of expr * (pat * expr) list * Type.t
  | CHAR_LITERAL of char
  | E_RECORD of (string * expr) list * Type.t
  | EXCEPT of expr * bool * expr list
  | FIELD of expr * string * Type.t
  | FILTER of expr * expr
  | FN of pat * expr * Type.t
  | GROUP of expr * (string * expr) list * (string * expr) list
  | INT_LITERAL of int
  | INTERSECT of expr * bool * expr list
  | JOIN of expr * expr * expr
  | LET of (pat * expr) list * expr
  | LIST_LITERAL of expr list * Type.t
  | ORDER of expr * expr
  | PLUS of expr * expr * Type.t
  | PROJECT of expr * expr
  | RAISE of expr * Type.t
  | REAL_LITERAL of real
  | SKIP of expr * expr
  | STRING_LITERAL of string
  | TAKE of expr * expr
  | TUPLE of expr list
  | UNION of expr * bool * expr list
  | UNIT_LITERAL
  | UNORDER of expr
  | VAR of string * Type.t
datatype <a id='pat' href="#pat-impl">pat</a>
  = P_AS of string * pat * Type.t
  | P_BOOL_LIT of bool
  | P_CHAR_LIT of char
  | P_CON of string * pat * Type.t
  | P_CON0 of string * Type.t
  | P_CONS of pat * pat * Type.t
  | P_INT_LIT of int
  | P_LIST of pat list * Type.t
  | P_REAL_LIT of real
  | P_RECORD of (string * pat) list
  | P_STRING_LIT of string
  | P_TUPLE of pat list
  | P_VAR of string * Type.t
  | P_WILD of Type.t
</pre>

<a id="expr-impl"></a>
<h3><code><strong>datatype</strong> expr</code></h3>

is a Morel expression in its typed, post-desugar internal form.
Scalar constructors include `INT_LITERAL`, `VAR`, `APPLY`, `FIELD`,
`E_RECORD` (E-prefixed to avoid clashing with `Variant`'s `RECORD`),
`TUPLE`, `LIST_LITERAL`, and `PLUS`. Relational constructors include
`FILTER` and `PROJECT`; a `from`-expression desugars to a tree of
these. Binders in `FN`, `CASE`, and `LET` use the `pat` datatype.

<a id="pat-impl"></a>
<h3><code><strong>datatype</strong> pat</code></h3>

is a Morel pattern in its typed, post-desugar internal form.
`P_VAR` binds an identifier; `P_WILD` matches anything;
`P_INT_LIT`, `P_BOOL_LIT`, `P_CHAR_LIT`, `P_REAL_LIT`, and
`P_STRING_LIT` match literal values. `P_CON`/`P_CON0` match
datatype constructors with and without payload; `P_CONS` matches
list cons; `P_TUPLE`, `P_RECORD`, and `P_LIST` destructure
composite values; `P_AS` binds a name to a value matched by a
sub-pattern.

[//]: # (end:lib/core)
