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

# Type structure

[Up to index](index.md)

[//]: # (start:lib/type)
The `Type` structure surfaces Morel's resolved, post-typecheck type
representation as a first-class datatype. It is used as a payload on
certain `Core.expr` constructors (for example, to disambiguate
overloaded arithmetic) and is exposed so metaprograms can inspect
types as ordinary values.

## Synopsis

<pre>
datatype <a id='t' href="#t-impl">t</a>
  = T_BAG of t
  | T_BOOL
  | T_CHAR
  | T_DATA of string * t list
  | T_FN of t * t
  | T_INT
  | T_LIST of t
  | T_REAL
  | T_RECORD of (string * t) list
  | T_STRING
  | T_TUPLE of t list
  | T_UNIT
  | T_VAR of int
</pre>

<a id="t-impl"></a>
<h3><code><strong>datatype</strong> t</code></h3>

is a Morel type in its resolved, post-typecheck form. The
constructor names use a `T_` prefix to avoid clashing with the
`Variant` datatype, which has constructors of the same logical
names. `T_DATA` covers user-defined datatypes and built-ins like
`option`/`ref`/`exn`; `T_VAR` carries a type-variable id.

[//]: # (end:lib/type)
