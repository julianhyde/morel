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

# Morel language reference

This document describes the grammar of Morel
([constants](#constants),
[identifiers](#identifiers),
[expressions](#expressions),
[patterns](#patterns),
[types](#types),
[declarations](#declarations)),
and then lists its built-in
[operators](#built-in-operators),
[types](#built-in-types),
[functions](#built-in-functions).
[Properties](#properties) affect the execution strategy and the
behavior of the shell.

Query expressions (`from`, `exists`, `forall`) are described in more
detail in the [query reference](query.md).

## Grammar

This reference is based on
[Andreas Rossberg's grammar for Standard ML](https://people.mpi-sws.org/~rossberg/sml.html).
While the files have a different [notation](#notation),
they are similar enough to the two languages.

### Differences between Morel and SML

Morel aims to be compatible with Standard ML.
It extends Standard ML in areas related to relational operations.
Some of the features in Standard ML are missing,
just because they take effort to build.
Contributions are welcome!

In Morel but not Standard ML:
* Queries (expressions starting with `exists`, `forall` or
  `from`) with `compute`,
  `distinct`,
  `except`,
  `group`,
  `intersect`,
  `into`,
  `join`,
  `order`,
  `require`,
  `skip`,
  `take`,
  `through`,
  `union`,
  `unorder`,
  `where`,
  `yield` steps and `in` and `of` keywords
* `elem`,
  `implies`,
  `notelem` binary operators
* `current`,
  `elements`,
  `ordinal` nilary operators
* `typeof` type operator
* <code><i>lab</i> =</code> is optional in <code><i>exprow</i></code>
* <code><i>record</i>.<i>lab</i></code> as an alternative to
  <code>#<i>lab</i> <i>record</i></code>;
  for tuples, <code><i>tuple</i>.1</code>, <code><i>tuple</i>.2</code> etc.
  as an alternative to <code>#1 <i>tuple</i></code>,
  <code>#2 <i>tuple</i></code>
* postfix method-call syntax <code><i>exp</i>.<i>f</i> ()</code> and
  <code><i>exp</i>.<i>f</i> <i>arg</i></code>, where `f` is a function
  whose first parameter is named `self`
* identifiers and type names may be quoted
  (for example, <code>\`an identifier\`</code>)
* `with` functional update for record values
* overloaded functions may be declared using `over` and `inst`
* `(*)` line comments (syntax as SML/NJ and MLton)

In Standard ML but not in Morel:
* `word` constant
* `longid` identifier
* references (`ref` and operators `!` and `:=`)
* exceptions (`raise`, `handle`, `exception`)
* `while` loop
* data type replication (`type`)
* `withtype` in `datatype` declaration
* abstract type (`abstype`)
* structures (`structure`)
* signature refinement (`where type`)
* signature sharing constraints
* local declarations (`local`)
* operator declarations (`nonfix`, `infix`, `infixr`)
* `open`
* `before` and `o` operators

### Constants

<pre>
<i>con</i> <b>&rarr;</b> <i>int</i>                       integer
    | <i>float</i>                     floating point
    | <i>char</i>                      character
    | <i>string</i>                    string
<i>int</i> &rarr; [<b>~</b>]<i>num</i>                    decimal
    | [<b>~</b>]<b>0x</b><i>hex</i>                  hexadecimal
<i>float</i> &rarr; [<b>~</b>]<i>num</i><b>.</b><i>num</i>              floating point
    | [<b>~</b>]<i>num</i>[<b>.</b><i>num</i>]<b>e</b>[<b>~</b>]<i>num</i>
                                scientific
<i>char</i> &rarr; <b>#"</b><i>ascii</i><b>"</b>                 character
<i>string</i> &rarr; <b>"</b><i>ascii</i>*<b>"</b>               string
<i>num</i> &rarr; <i>digit</i> <i>digit</i>*              number
<i>hex</i> &rarr; (<i>digit</i> | <i>letter</i>) (<i>digit</i> | <i>letter</i>)*
                                hexadecimal number (letters
                                may only be in the range A-F)
<i>ascii</i> &rarr; ...                     single non-" ASCII character
                                or \-headed escape sequence
</pre>

### Identifiers

<pre>
<i>id</i> &rarr;  <i>letter</i> (<i>letter</i> | <i>digit</i> | ''' | <b>_</b>)*
                                alphanumeric
    | <i>symbol</i> <i>symbol</i>*            symbolic (not allowed for type
                                variables or module language
                                identifiers)
<i>symbol</i> &rarr; <b>!</b>
    | <b>%</b>
    | <b>&amp;</b>
    | <b>$</b>
    | <b>#</b>
    | <b>+</b>
    | <b>-</b>
    | <b>/</b>
    | <b>:</b>
    | <b>&lt;</b>
    | <b>=</b>
    | <b>&gt;</b>
    | <b>?</b>
    | <b>@</b>
    | <b>\</b>
    | <b>~</b>
    | <b>`</b>
    | <b>^</b>
    | '<b>|</b>'
    | '<b>*</b>'
<i>var</i> &rarr; '''(<i>letter</i> | <i>digit</i> | ''' | <b>_</b>)*
                                unconstrained
      ''''(<i>letter</i> | <i>digit</i> | ''' | <b>_</b>⟩*
                                equality
<i>lab</i> &rarr; <i>id</i>                        identifier
      <i>num</i>                       number (may not start with 0)
</pre>

### Expressions

<pre>
<i>exp</i> &rarr; <i>con</i>                       constant
    | [ <b>op</b> ] <i>id</i>                 value or constructor identifier
    | <i>exp<sub>1</sub></i> <i>exp<sub>2</sub></i>                 application
    | <i>exp<sub>1</sub></i> <i>id</i> <i>exp<sub>2</sub></i>              infix application
    | '<b>(</b>' <i>exp</i> '<b>)</b>'               parentheses
    | '<b>(</b>' <i>exp<sub>1</sub></i> <b>,</b> ... <b>,</b> <i>exp<sub>n</sub></i> '<b>)</b>' tuple (n &ne; 1)
    | <b>{</b> [ <i>exprow</i> ] <b>}</b>            record
    | <b>#</b><i>lab</i>                      record selector
    | '<b>[</b>' <i>exp<sub>1</sub></i> <b>,</b> ... <b>,</b> <i>exp<sub>n</sub></i> '<b>]</b>' list (n &ge; 0)
    | '<b>(</b>' <i>exp<sub>1</sub></i> <b>;</b> ... <b>;</b> <i>exp<sub>n</sub></i> '<b>)</b>' sequence (n &ge; 2)
    | <b>let</b> <i>dec</i> <b>in</b> <i>exp<sub>1</sub></i> ; ... ; <i>exp<sub>n</sub></i> <b>end</b>
                                local declaration (n ≥ 1)
    | <i>exp</i> <b>.</b> <i>lab</i> <b>()</b>              postfix call (no argument)
    | <i>exp<sub>1</sub></i> <b>.</b> <i>lab</i> <i>exp<sub>2</sub></i>            postfix call (with argument)
    | <i>exp</i> <b>:</b> <i>type</i>                type annotation
    | <i>exp<sub>1</sub></i> <b>andalso</b> <i>exp<sub>2</sub></i>         conjunction
    | <i>exp<sub>1</sub></i> <b>orelse</b> <i>exp<sub>2</sub></i>          disjunction
    | <b>if</b> <i>exp<sub>1</sub></i> <b>then</b> <i>exp<sub>2</sub></i> <b>else</b> <i>exp<sub>3</sub></i>
                                conditional
    | <b>case</b> <i>exp</i> <b>of</b> <i>match</i>         case analysis
    | <b>fn</b> <i>match</i>                  function
    | <b>current</b>                   current element (only valid in a query step)
    | <b>elements</b>                  elements of current group (only valid in compute)
    | <b>ordinal</b>                   element ordinal (only valid in a query step)
    | <i>exp<sub>1</sub></i> <b>over</b> <i>exp<sub>2</sub></i>            aggregate (only valid in compute)
    | <b>from</b> [ <i>scan<sub>1</sub></i> <b>,</b> ... <b>,</b> <i>scan<sub>s</sub></i> ] <i>step<sub>1</sub></i> ... <i>step<sub>t</sub></i> [ <i>terminalStep</i> ]
                                relational expression (<i>s</i> &ge; 0, <i>t</i> &ge; 0)
    | <b>exists</b> [ <i>scan<sub>1</sub></i> <b>,</b> ... <b>,</b> <i>scan<sub>s</sub></i> ] <i>step<sub>1</sub></i> ... <i>step<sub>t</sub></i>
                                existential quantification (<i>s</i> &ge; 0, <i>t</i> &ge; 0)
    | <b>forall</b> [ <i>scan<sub>1</sub></i> <b>,</b> ... <b>,</b> <i>scan<sub>s</sub></i> ] <i>step<sub>1</sub></i> ... <i>step<sub>t</sub></i> <b>require</b> <i>exp</i>
                                universal quantification (<i>s</i> &ge; 0, <i>t</i> &ge; 0)
<i>exprow</i> &rarr; [ <i>exp</i> <b>with</b> ] <i>exprowItem</i> [<b>,</b> <i>exprowItem</i> ]*
                                expression row
<i>exprowItem</i> &rarr; [ <i>lab</i> <b>=</b> ] <i>exp</i>
<i>match</i> &rarr; <i>matchItem</i> [ '<b>|</b>' <i>matchItem</i> ]*
                                match
<i>matchItem</i> &rarr; <i>pat</i> <b>=&gt;</b> <i>exp</i>
<i>scan</i> &rarr; <i>pat</i> <b>in</b> <i>exp</i> [ <b>on</b> <i>exp</i> ]    iteration
    | <i>pat</i> <b>=</b> <i>exp</i>                 single iteration
    | <i>val</i>                       unbounded variable
<i>step</i> &rarr; <b>distinct</b>                 distinct step
    | <b>except</b> [ <b>distinct</b> ] <i>exp<sub>1</sub></i> <b>,</b> ... <b>,</b> <i>exp<sub>e</sub></i>
                                except step (<i>e</i> &ge; 1)
    | <b>group</b> <i>exp<sub>1</sub></i> [ <b>compute</b> <i>exp<sub>2</sub></i> ]
                                group step
    | <b>intersect</b> [ <b>distinct</b> ] <i>exp<sub>1</sub></i> <b>,</b> ... <b>,</b> <i>exp<sub>i</sub></i>
                                intersect step (<i>i</i> &ge; 1)
    | <b>join</b> <i>scan<sub>1</sub></i> <b>,</b> ... <b>,</b> <i>scan<sub>s</sub></i>  join step (<i>s</i> &ge; 1)
    | <b>order</b> <i>exp</i>                 order step
    | <b>skip</b> <i>exp</i>                  skip step
    | <b>take</b> <i>exp</i>                  take step
    | <b>through</b> <i>pat</i> <b>in</b> <i>exp</i>        through step
    | <b>union</b> [ <b>distinct</b> ] <i>exp<sub>1</sub></i> <b>,</b> ... <b>,</b> <i>exp<sub>u</sub></i>
                                union step (<i>u</i> &ge; 1)
    | <b>where</b> <i>exp</i>                 filter step
    | <b>yield</b> <i>exp</i>                 yield step
<i>terminalStep</i> &rarr; <b>into</b> <i>exp</i>         into step
    | <b>compute</b> <i>exp</i>               compute step
<i>groupKey</i> &rarr; [ <i>id</i> <b>=</b> ] <i>exp</i>
<i>agg</i> &rarr; [ <i>id</i> <b>=</b> ] <i>exp</i> [ <b>of</b> <i>exp</i> ]
</pre>

### Patterns

<pre>
<i>pat</i> &rarr; <i>con</i>                       constant
    | <b>_</b>                         wildcard
    | [ <b>op</b> ] <i>id</i>                 variable
    | [ <b>op</b> ] <i>id</i> [ <i>pat</i> ]         construction
    | <i>pat<sub>1</sub></i> <i>id</i> <i>pat<sub>2</sub></i>              infix construction
    | '<b>(</b>' <i>pat</i> '<b>)</b>'               parentheses
    | '<b>(</b>' <i>pat<sub>1</sub></i> , ... , <i>pat<sub>n</sub></i> '<b>)</b>' tuple (n &ne; 1)
    | <b>{</b> [ <i>patrow</i> ] <b>}</b>            record
    | '<b>[</b>' <i>pat<sub>1</sub></i> <b>,</b> ... <b>,</b> <i>pat<sub>n</sub></i> '<b>]</b>' list (n &ge; 0)
    | <i>pat</i> <b>:</b> <i>type</i>                type annotation
    | <i>id</i> <b>as</b> <i>pat</i>                 layered
<i>patrow</i> &rarr; '<b>...</b>'                  wildcard
    | <i>lab</i> <b>=</b> <i>pat</i> [<b>,</b> <i>patrow</i>]      pattern
    | <i>id</i> [<b>,</b> <i>patrow</i>]             label as variable
</pre>

### Types

<pre>
<i>typ</i> &rarr; <i>var</i>                       variable
    | [ <i>typ</i> ] <i>id</i>                constructor
    | '<b>(</b>' <i>typ</i> [<b>,</b> <i>typ</i> ]* '<b>)</b>' <i>id</i>  constructor
    | '<b>(</b>' <i>typ</i> '<b>)</b>'               parentheses
    | <i>typ<sub>1</sub></i> <b>-&gt;</b> <i>typ<sub>2</sub></i>              function
    | <i>typ<sub>1</sub></i> '<b>*</b>' ... '<b>*</b>' <i>typ<sub>n</sub></i>     tuple (n &ge; 2)
    | <b>{</b> [ <i>typrow</i> ] <b>}</b>            record
    | <b>typeof</b> <i>exp</i>                expression type
<i>typrow</i> &rarr; <i>lab</i> : <i>typ</i> [, <i>typrow</i>]   type row
</pre>

### Declarations

<pre>
<i>dec</i> &rarr; <i>vals</i> <i>valbind</i>              value
    | <b>fun</b> <i>funbind</i>               function
    | <b>type</b> <i>typbind</i>              type
    | <b>datatype</b> <i>datbind</i>          data type
    | <b>signature</b> <i>sigbind</i>         signature
    | <b>over</b> <i>id</i>                   overloaded name
    | <i>empty</i>
    | <i>dec<sub>1</sub></i> [<b>;</b>] <i>dec<sub>2</sub></i>             sequence
<i>valbind</i> &rarr; <i>pat</i> <b>=</b> <i>exp</i> [ <b>and</b> <i>valbind</i> ]*
                                destructuring
    | <b>rec</b> <i>valbind</i>               recursive
    | <b>inst</b> <i>valbind</i>              overload instance
<i>funbind</i> &rarr; <i>funmatch</i> [ <b>and</b> <i>funmatch</i> ]*
                                clausal function
<i>funmatch</i> &rarr; <i>funmatchItem</i> [ '<b>|</b>' funmatchItem ]*
<i>funmatchItem</i> &rarr; [ <b>op</b> ] <i>id</i> <i>pat<sub>1</sub></i> ... <i>pat<sub>n</sub></i> [ <b>:</b> <i>type</i> ] <b>=</b> <i>exp</i>
                                nonfix (n &ge; 1)
    | <i>pat<sub>1</sub></i> <i>id</i> <i>pat<sub>2</sub></i> [ <b>:</b> <i>type</i> ] <b>=</b> <i>exp</i>
                                infix
    | '<b>(</b>' <i>pat<sub>1</sub></i> <i>id</i> <i>pat<sub>2</sub></i> '<b>)</b>' <i>pat'<sub>1</sub></i> ... <i>pat'<sub>n</sub></i> [ <b>:</b> <i>type</i> ] = <i>exp</i>
                                infix (n &ge; 0)
<i>typbind</i> &rarr; [ <i>vars</i> ] <i>id</i> <b>=</b> <i>typ</i> [ <b>and</b> <i>typbind</i> ]*
                                abbreviation
<i>datbind</i> &rarr; <i>datbindItem</i> [ <b>and</b> <i>datbindItem</i> ]*
                                data type
<i>datbindItem</i> &rarr; [ <i>vars</i> ] <i>id</i> <b>=</b> <i>conbind</i>
<i>conbind</i> &rarr; <i>conbindItem</i> [ '<b>|</b>' <i>conbindItem</i> ]*
                                data constructor
<i>conbindItem</i> &rarr; <i>id</i> [ <b>of</b> <i>typ</i> ]
<i>vals</i> &rarr; <i>val</i>
    | '<b>(</b>' <i>val</i> [<b>,</b> <i>val</i>]* '<b>)</b>'
<i>vars</i> &rarr; <i>var</i>
    | '<b>(</b>' <i>var</i> [<b>,</b> <i>var</i>]* '<b>)</b>'
</pre>

### Modules

<pre>
<i>sigbind</i> &rarr; <i>id</i> <b>=</b> <b>sig</b> <i>spec</i> <b>end</b> [ <b>and</b> <i>sigbind</i> ]*
                                signature
<i>spec</i> &rarr; <b>val</b> <i>valdesc</i>              value
    | <b>type</b> <i>typdesc</i>              abstract type
    | <b>type</b> <i>typbind</i>              type abbreviation
    | <b>datatype</b> <i>datdesc</i>          data type
    | <b>exception</b> <i>exndesc</i>         exception
    | <i>empty</i>
    | <i>spec<sub>1</sub></i> [<b>;</b>] <i>spec<sub>2</sub></i>           sequence
<i>valdesc</i> &rarr; <i>id</i> <b>:</b> <i>typ</i> [ <b>and</b> <i>valdesc</i> ]*
                                value specification
<i>typdesc</i> &rarr; [ <i>vars</i> ] <i>id</i> [ <b>and</b> <i>typdesc</i> ]*
                                type specification
<i>datdesc</i> &rarr; <i>datdescItem</i> [ <b>and</b> <i>datdescItem</i> ]*
                                datatype specification
<i>datdescItem</i> &rarr; [ <i>vars</i> ] <i>id</i> <b>=</b> <i>conbind</i>
<i>exndesc</i> &rarr; <i>id</i> [ <b>of</b> <i>typ</i> ] [ <b>and</b> <i>exndesc</i> ]*
                                exception specification
</pre>

A **signature** defines an interface that specifies types,
values, datatypes, and exceptions without providing
implementations. Signatures are used to document module
interfaces and, in future versions of Morel, will be used to
constrain structure implementations.

Signature declarations appear at the top level (see grammar in
[Declarations](#declarations)).

#### Specifications

A signature body contains **specifications** that describe the
interface:

**Value specifications** declare the type of a value without
defining it:
```sml
val empty : 'a stack
val push : 'a * 'a stack -> 'a stack
```

**Type specifications** can be abstract (no definition) or
concrete (type alias):
```sml
type 'a stack              (* abstract type *)
type point = real * real   (* concrete type alias *)
type ('k, 'v) map          (* abstract with multiple params *)
```

**Datatype specifications** describe algebraic datatypes:
```sml
datatype 'a tree = Leaf | Node of 'a * 'a tree * 'a tree
```

**Exception specifications** declare exceptions:
```sml
exception Empty                  (* exception without payload *)
exception QueueError of string   (* exception with payload *)
```

#### Examples

A simple signature with abstract type and value specifications:

```sml
signature STACK =
sig
  type 'a stack
  exception Empty
  val empty : 'a stack
  val isEmpty : 'a stack -> bool
  val push : 'a * 'a stack -> 'a stack
  val pop : 'a stack -> 'a stack
  val top : 'a stack -> 'a
end
```

Multiple signatures declared together using `and`:

```sml
signature EQ =
sig
  type t
  val eq : t * t -> bool
end
and ORD =
sig
  type t
  val lt : t * t -> bool
  val le : t * t -> bool
end
```

#### Current Limitations

The current implementation supports parsing and
pretty-printing signatures but does not yet support:
* Structure declarations that implement signatures
* Signature refinement (`where type`)
* Signature sharing constraints
* Signature inclusion (`include`)

These features may be added in future versions.

### Notation

This grammar uses the following notation:

| Syntax      | Meaning |
| ----------- | ------- |
| *symbol*    | Grammar symbol (e.g. *con*) |
| **keyword** | Morel keyword (e.g. **if**) and symbol (e.g. **~**, "**(**") |
| \[ term \]  | Option: term may occur 0 or 1 times |
| \[ term1 \| term2 \] | Alternative: term1 may occur, or term2 may occur, or neither |
| term*       | Repetition: term may occur 0 or more times |
| 's'         | Quotation: Symbols used in the grammar &mdash; ( ) \[ \] \| * ... &mdash; are quoted when they appear in Morel language |

## Built-in operators

| Operator | Precedence | Meaning |
| :------- | ---------: | :------ |
| *        |    infix 7 | Multiplication |
| /        |    infix 7 | Division |
| div      |    infix 7 | Integer division |
| mod      |    infix 7 | Modulo |
| +        |    infix 6 | Plus |
| -        |    infix 6 | Minus |
| ^        |    infix 6 | String concatenate |
| ~        |   prefix 6 | Negate |
| ::       |   infixr 5 | List cons |
| @        |   infixr 5 | List append |
| &lt;=    |    infix 4 | Less than or equal |
| &lt;     |    infix 4 | Less than |
| &gt;=    |    infix 4 | Greater than or equal |
| &gt;     |    infix 4 | Greater than |
| =        |    infix 4 | Equal |
| &lt;&gt; |    infix 4 | Not equal |
| elem     |    infix 4 | Member of list |
| notelem  |    infix 4 | Not member of list |
| :=       |    infix 3 | Assign |
| o        |    infix 3 | Compose |
| andalso  |    infix 2 | Logical and |
| orelse   |    infix 1 | Logical or |
| implies  |    infix 0 | Logical implication |

`abs` is a built-in function (not an operator, because it uses function
syntax rather than prefix or infix syntax). It is overloaded: its type is
`int -> int` when applied to an `int` argument, and `real -> real` when
applied to a `real` argument. It is equivalent to `Int.abs` and `Real.abs`
respectively.

## Built-in types

Primitive: `bool`, `char`, `int`, `real`, `string`, `unit`

Datatype:
* `datatype 'a descending = DESC of 'a` (in structure `Relational`)
* `datatype ('l, 'r) either = INL of 'l | INR of 'r` (in structure `Either`)
* `datatype 'a list = nil | :: of 'a * 'a list` (in structure `List`)
* `datatype 'a option = NONE | SOME of 'a` (in structure `Option`)
* `datatype 'a order = LESS | EQUAL | GREATER`
  (in structure `General`)

Eqtype:
* `eqtype 'a bag = 'a bag` (in structure `Bag`)
* `eqtype 'a vector = 'a vector` (in structure `Vector`)

Exception:
* `Bind` (in structure `General`)
* `Chr` (in structure `General`)
* `Div` (in structure `General`)
* `Domain` (in structure `General`)
* `Empty` (in structure `List`)
* `Error` (in structure `Interact`)
* `Option` (in structure `Option`)
* `Overflow` (in structure `Option`)
* `Size` (in structure `General`)
* `Subscript` (in structure `General`)
* `Unordered` (in structure `IEEEReal`)

## Structures

[//]: # (start:structures)

| Structure | Description | Members |
| --------- | ----------- | ------- |
| [Bag](lib/bag.md) | Unordered collection of elements with duplicates. | `@`, `all`, `app`, `collate`, `concat`, `drop`, `exists`, `filter`, `find`, `fold`, `fromList`, `getItem`, `hd`, `length`, `map`, `mapPartial`, `nil`, `nth`, `null`, `partition`, `tabulate`, `take`, `tl`, `toList` |
| [Bool](lib/bool.md) | Boolean values and operations. | `bool`, `fromString`, `implies`, `not`, `toString` |
| [Char](lib/char.md) | Character values and operations. | `char`, `<`, `<=`, `>`, `>=`, `chr`, `compare`, `contains`, `fromCString`, `fromInt`, `fromString`, `isAlpha`, `isAlphaNum`, `isAscii`, `isCntrl`, `isDigit`, `isGraph`, `isHexDigit`, `isLower`, `isOctDigit`, `isPrint`, `isPunct`, `isSpace`, `isUpper`, `maxChar`, `maxOrd`, `minChar`, `notContains`, `ord`, `pred`, `succ`, `toCString`, `toLower`, `toString`, `toUpper` |
| [Datalog](lib/datalog.md) | Datalog query interface. | `execute`, `translate`, `validate` |
| [Either](lib/either.md) | Values that are one of two types. | `either`, `app`, `appLeft`, `appRight`, `asLeft`, `asRight`, `fold`, `isLeft`, `isRight`, `map`, `mapLeft`, `mapRight`, `partition`, `proj` |
| [Fn](lib/fn.md) | Higher-order function combinators. | `apply`, `const`, `curry`, `equal`, `flip`, `id`, `notEqual`, `o`, `repeat`, `uncurry` |
| [General](lib/general.md) | Basic types, exceptions, and utility functions. | `exn`, `order`, `unit`, `Bind`, `Chr`, `Div`, `Domain`, `Fail`, `Match`, `Overflow`, `Size`, `Span`, `Subscript`, `before`, `exnMessage`, `exnName`, `ignore`, `o` |
| [IEEEReal](lib/ieee-real.md) | IEEE 754 floating-point definitions. | `decimal_approx`, `float_class`, `real_order`, `rounding_mode` |
| [Int](lib/int.md) | Fixed-precision integer operations. | `int`, `*`, `+`, `-`, `<`, `<=`, `>`, `>=`, `abs`, `compare`, `div`, `fmt`, `fromInt`, `fromLarge`, `fromString`, `max`, `maxInt`, `min`, `minInt`, `mod`, `precision`, `quot`, `rem`, `sameSign`, `scan`, `sign`, `toInt`, `toLarge`, `toString`, `~` |
| [IntInf](lib/int-inf.md) | Arbitrary-precision integer operations. | `int` |
| [Interact](lib/interact.md) | Interactive session utilities. | `use`, `useSilently` |
| [List](lib/list.md) | Polymorphic singly-linked lists. | `list`, `Empty`, `@`, `all`, `app`, `at`, `collate`, `concat`, `drop`, `except`, `exists`, `filter`, `find`, `foldl`, `foldr`, `getItem`, `hd`, `intersect`, `last`, `length`, `map`, `mapPartial`, `mapi`, `nil`, `nth`, `null`, `partition`, `rev`, `revAppend`, `tabulate`, `take`, `tl` |
| [ListPair](lib/list-pair.md) | Operations on pairs of lists. | `UnequalLengths`, `all`, `allEq`, `app`, `appEq`, `exists`, `foldl`, `foldlEq`, `foldr`, `foldrEq`, `map`, `mapEq`, `unzip`, `zip`, `zipEq` |
| [Math](lib/math.md) | Mathematical functions for real numbers. | `acos`, `asin`, `atan`, `atan2`, `cos`, `cosh`, `e`, `exp`, `ln`, `log10`, `pi`, `pow`, `sin`, `sinh`, `sqrt`, `tan`, `tanh` |
| [Option](lib/option.md) | Optional values. | `option`, `Option`, `app`, `compose`, `composePartial`, `filter`, `getOpt`, `isSome`, `join`, `map`, `mapPartial`, `valOf` |
| [Real](lib/real.md) | Floating-point number operations. | `real`, `!=`, `*`, `*+`, `*-`, `+`, `-`, `/`, `<`, `<=`, `==`, `>`, `>=`, `?=`, `abs`, `ceil`, `checkFloat`, `class`, `compare`, `compareReal`, `copySign`, `floor`, `fmt`, `fromDecimal`, `fromInt`, `fromLarge`, `fromLargeInt`, `fromManExp`, `fromString`, `isFinite`, `isNan`, `isNormal`, `max`, `maxFinite`, `min`, `minNormalPos`, `minPos`, `negInf`, `nextAfter`, `posInf`, `precision`, `radix`, `realCeil`, `realFloor`, `realMod`, `realRound`, `realTrunc`, `rem`, `round`, `sameSign`, `scan`, `sign`, `signBit`, `split`, `toDecimal`, `toInt`, `toLarge`, `toLargeInt`, `toManExp`, `toString`, `trunc`, `unordered`, `~` |
| [Relational](lib/relational.md) | Relational algebra operations for Morel queries. | `descending`, `compare`, `count`, `elem`, `empty`, `iterate`, `max`, `min`, `nonEmpty`, `notelem`, `only`, `sum` |
| [String](lib/string.md) | String operations. | `string`, `<`, `<=`, `>`, `>=`, `^`, `collate`, `compare`, `concat`, `concatWith`, `explode`, `extract`, `fields`, `fromCString`, `fromString`, `implode`, `isPrefix`, `isSubstring`, `isSuffix`, `map`, `maxSize`, `scan`, `size`, `str`, `sub`, `substring`, `toCString`, `toString`, `tokens`, `translate` |
| [StringCvt](lib/string-cvt.md) | String conversion utilities and types. | `radix`, `reader`, `realfmt` |
| [Sys](lib/sys.md) | System interface utilities. | `clearEnv`, `env`, `file`, `plan`, `planEx`, `set`, `show`, `showAll`, `unset` |
| [Variant](lib/variant.md) | Dynamically-typed variant values. | `variant`, `parse`, `print` |
| [Vector](lib/vector.md) | Immutable fixed-length arrays. | `vector`, `all`, `app`, `appi`, `collate`, `concat`, `exists`, `find`, `findi`, `foldl`, `foldli`, `foldr`, `foldri`, `fromList`, `length`, `map`, `mapi`, `maxLen`, `sub`, `tabulate`, `update` |

[//]: # (end:structures)
