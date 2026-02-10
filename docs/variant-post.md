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
Add introduction explaining the motivation for variant;
proof read for spelling and grammar;
proof read for sentence flow.
{% endcomment %}
-->

# The Variant Type

<!-- TODO: Add introduction. The content below was extracted from
     datalog-post.md and needs a lede that explains the problem
     variant solves (dynamic typing in a statically typed language). -->

Notice that the return type of `execute` is declared as `variant`,
and appears in the shell as `{path:{x:int, y:int} bag} variant`.

To implement the `execute` function, we had a problem to solve. The
return type of a Datalog program, like any expression or query, varies
from one program to the next.  The program is not parsed until we call
the `execute` function, so cannot be known in advance. This is at odds
with Morel's static type system, which means that a function's return
type can depend only on the type (not value) of its arguments.

The solution was `variant` (added in
[#324](https://github.com/hydromatic/morel/issues/324)), a single type
that can represent all possible Morel values.

`variant` is a union of all primitive and composite types:

```sml
datatype variant =
    UNIT
  | BOOL of bool
  | INT of int
  | REAL of real
  | CHAR of char
  | STRING of string
  | LIST of variant list
  | BAG of variant list
  | VECTOR of variant list
  | VARIANT_NONE
  | VARIANT_SOME of variant
  | RECORD of (string * variant) list
  | CONSTANT of string
  | CONSTRUCT of string * variant
```

You can use labels like `INT`, `LIST`, `STRING` to construct and
deconstruct a value:

```sml
fun f v =
  case v of
    INT i => "it's an integer!"
   | LIST list => "it's a list!"
   | STRING s => "it's a string!"
   | _ => "it's something else!";
> val f = fn : variant -> string

f (INT 5);
> val it = "it's an integer!" : string

f (STRING "x");
> val it = "it's a string!" : string

f (LIST [INT 1, INT 2]);
> val it = "it's a list!" : string
```

Expressions like `LIST [INT 1, INT 2]` make it look like a composite
`variant` value consists of `variant` wrappers all the way down.  If
that were the case, converting large, deeply nested values to
`variant` would take considerable time and space. But the
representation is more efficient.  The implementation of `variant` is
a thin wrapper that contains a type and a native value. The runtime
can quickly convert any value to `variant` by adding that wrapper.

We have modified the shell's output to make working with variants more
intuitive.  Typically, the shell uses a colon (`:`) to separate a
runtime value from its static type.  However, when printing a
`variant` value, this convention shifts slightly:

```sml
val it = {path=[{x=1,y=2},{x=2,y=3},{x=1,y=3}]}
  : {path:{x:int, y:int} list} variant
```

In this case, the standard "value : type" separation is nuanced.
While `variant` is the static type, the description `{path:{x:int,
y:int} list}` is the dynamic type identified at runtime. Notably,
`variant` is not the polymorphic type that it appears to be.

But we've achieved our goal; `variant` delivers dynamically typed
values at low runtime cost while preserving the integrity and benefits
of the static type system.
