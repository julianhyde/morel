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
# Spec: the relational tree (step 0 of the #449 plan)

The datatype, its typing rules, its scoping rules and its printed
form. This is the document morel-rust (hydromatic/morel-rust#33) and
morel-go implement against; where it disagrees with an
implementation, this document is right.

Status: **frozen**. The datatype, the typing and scoping rules and
the printed form are settled; changing any of them now costs a
coordinated change across three implementations and every golden
file. Every decision that was made here rather than transcribed from
current behavior has since been built, and is now described in the
indicative rather than marked *Review*; the single exception is the
rename convention at a scope merge (§5), which no rule exercises yet
and which the validator makes unreachable in the meantime. Rationale
for the design is in discussion.md; the sequence is in plan.md.

**What the contract covers is a command, not a file.** `Sys.planEx`
prints the tree, and its text is what §6 specifies and what another
implementation must reproduce. `Sys.plan` prints the *executable*
code — the row sinks the compiler makes from the tree — and is
Morel's own, outside this document; it names a slot by the pattern
that binds it, `$0`, because a one-binder query has no projection to
read a name off (§3.1), and that is a fact about the compiler rather
than about the tree. Drawing
the line by command rather than by file is deliberate: the two appear
in the same script files — `optimize.smli` and `built-in/sys.smli`
each have both — and splitting the files would move tests to record
something a rule states in a sentence.

**Revised 2026-09-15: a node binds patterns.** §2 now describes a
node as a binding form, like `fn` and `case`: its row is a pattern of
its own rather than a positional reference, a dependent join's binder
is its left pattern read from the right input rather than a separate
name, and a node may bind an optional ordinal pattern. The printed
form is unchanged except for a query that reads `ordinal`, whose plan
loses the projection the resolver used to insert (§2). The datatype
is therefore stable rather than frozen: an implementation that built
the earlier form ports this change with the rule framework
(plan.md), and its golden files still hold.

## 1. What a node is

A node denotes a collection. Its type is a *kind* — `list` or `bag` —
applied to an *element type*, which may be any Morel type at any
depth: a record, a tuple, an `int`, a function, another collection.
There is no row/scalar distinction and no flat-row assumption.

Two things that the step list carried do not exist here:

* **No binding list.** A node's element type is derived from the node
  and its inputs (§4), and is exactly the type of the value flowing
  out of it. The step list's environment — bindings, `atom`,
  `ordered` — has no counterpart.
* **No `atom` flag.** Atomization was the rule that a single binding
  yields its bare type rather than a one-field record. In the tree it
  is not a rule at all: an element's type is the type of the
  expression that constructs it, so `project [#sal $0]` has element
  type `real` because `#sal $0 : real`, and nothing had to decide.

The tree is a closed algebra: every constructor takes collections and
returns a collection. A node *is* a Core expression — `Core.Rel`
extends `Core.Exp` — whose type is a collection type. Hence a query
may appear anywhere an expression may, including inside the
expressions of another tree (§3.3), and an input needs no wrapper: it
is just an expression (§3.1).

A node is also a binding form, like `fn` and `case`: it has patterns
of its own, in scope in its expressions and not in its inputs (§2).
That is the whole of what makes it more than an application, and it
is not much: the one rule a node has that no function has is the
join's, whose element is the flat concatenation of its inputs'
components (§3.4).

## 2. Names

A node binds names the way `fn` and `case` do: with patterns of its
own, in scope in its expressions and not in its inputs. An input is
evaluated in the environment enclosing the tree; an expression of the
node is evaluated in that environment extended by the node's patterns.

* **The row pattern**, written `$0`, names the element of the node's
  input. A `join` has two: `$0` for the element of its left input and
  `$1` for the element of its right.
* **The ordinal pattern**, written `$ordinal`, names the position of
  that element in the node's input, counted from zero. It is
  optional, and a node has one exactly when an expression of the node
  reads it (§3.2).
* **The enclosing environment**: globals, `let` bindings, function
  parameters — anything the surrounding Core expression has in scope.

Every pattern is an ordinary `IdPat`, distinct per node. `$0`, `$1`
and `$ordinal` are the names the printer gives them (§6.3); a `$`
cannot occur in an identifier, so they cannot be confused with a name
the query wrote. Nothing about scope is therefore special: a pass
that reasons about variables treats a node's patterns as it treats a
`fn`'s, and a name used under a node that does not bind it is
unbound, which the validator rejects (§5).

Three rules complete the picture:

1. **Before the first row.** `skip`, `take` and `ifEmpty` evaluate
   their argument once, before any element exists, and have no
   patterns: there is nothing for the argument to name but the
   enclosing environment. That is a fact of the datatype, not a rule
   the validator has to check.
2. **A dependent join's right input reads the left pattern.** A
   `join`'s left pattern is in scope in its condition and in its
   *right input*, because the right input is evaluated once per left
   element; its right pattern is in scope in the condition only.
   Dependence is a free occurrence of the left pattern in the right
   input, and nothing else marks it. See discussion.md §8.
3. **Nested trees.** A tree that appears within a node's expression
   binds patterns of its own. In the datatype they are distinct from
   the enclosing node's, so an inner expression may read the outer
   row directly; in the text both print as `$0`, so the printer names
   the outer one (§6.3).

The ordinal is the position among the rows the expression runs over.
In a `filter`, `project` or `sort` that is the input, so a sort key
sees the position before sorting. In a `group` it is the position in
the group's input, in keys and aggregate arguments alike, and not a
rank within the group. In a `join` the condition runs once per
candidate pair and sees the pair's position, counted in the nested-
loop order §4 fixes, while the right input runs once per left element
and sees the left element's position; that is one pattern read from
two places, and each reads what it runs over. A node may bind an
ordinal pattern only if its input's kind is `list`; a bag has no
positions (§5).

`$0`, `$1` and `$ordinal` are never record labels and never appear in
an element type: fields are addressed by label, rows by pattern.

## 3. Constructors

`r`, `r₀`, `r₁` are nodes; `e` is an expression; `τ` is the element
type of `r`, `τ₀`/`τ₁` those of `r₀`/`r₁`. "Patterns" says what the
node binds for its expressions, beyond the enclosing environment; an
ordinal pattern is optional, and present exactly where read (§2).

### 3.1 Leaves

There is no leaf constructor. A node is itself an expression whose
type is a collection type, so a node's input is simply an expression:
another node, or a leaf — a global (`scott.emps`), a variable, a list
literal, a function application, anything of collection type. The
boundary between the tree and the rest of Core is therefore not
marked by an operator; it is wherever the expression stops being a
node.

A leaf binds nothing: its element flows out as a value, and the node
above it names that value `$0`.

A query with no scan — `from where p`, `from yield e`, or bare
`from` — iterates over a single element, which is unit, so its leaf
is `[()]`. Morel's own semantics are already this: `from where false`
and `from u in [()] where false` both evaluate to `[] : unit list`,
and `from yield 1 + 2` and `from u in [()] yield 1 + 2` both to
`[3] : int list`. The tree writes down the element the query left
implicit, so such a query prints a leaf its author did not write:

```
project [1 + 2]
  [()]
```

That is the only intended difference, and it is in the plan text
alone. A distinguished empty leaf, printing as nothing, was
considered and rejected: it buys a little less noise in a rare query
shape at the price of a constructor and of an exception to "a leaf is
just an expression".

### 3.2 One input

| Constructor | Arguments | Element type | Patterns |
| --- | --- | --- | --- |
| `filter` | `cond : bool` | `τ` | `$0`, `$ordinal` |
| `project` | `e` | type of `e` | `$0`, `$ordinal` |
| `group` | keys `l₁ = e₁, …`, aggregates `m₁ = a₁, …` | record of the key and aggregate types | `$0`, `$ordinal` |
| `ifEmpty` | `e` of type `τ` | `τ` | — (§2 rule 1) |
| `sort` | `e` | `τ` | `$0`, `$ordinal` |
| `unorder` | — | `τ` | — |
| `skip` | `n : int` | `τ` | — (§2 rule 1) |
| `take` | `n : int` | `τ` | — (§2 rule 1) |

`group` keys and aggregate arguments are expressions over `$0`;
labels `l`, `m` are the output record's labels, and must be distinct.
A `group` builds a record even when it has one field; the projection
that turns a one-field record into the query's bare value sits above
it (discussion.md §14). `distinct` is not a constructor: it is
`group` whose keys are the whole element and whose aggregate list is
empty.

`ifEmpty` yields its expression as the single element where its input
has none, and its input's elements where it has some. The expression
is evaluated only in the first case, when there is no element, so
like the count of a `skip` it cannot mention `$0`; it can mention
whatever encloses the tree, which inside the right input of a
dependent join includes that join's left pattern.

`compute` is `group` with no keys, plus the extraction of the single
element that the enclosing expression performs — see §6.

### 3.3 Correlation

A scan whose collection depends on an earlier binder — `from d in
depts, e in d.emps` — is a **dependent join**: a `join` whose right
input reads its left pattern.

```sml
from d in scott.depts, e in d.emps where e.sal > 1000 yield {d, e}
```

```
project [{d = #1 $0, e = #2 $0}]
  join [v$0]
    scott.depts
    filter [#sal $0 > 1000]
      #emps v$0
```

The right input is a tree of its own, whose `$0` is its own element,
so the left element is reached through the join's left pattern, which
the printer names `v$0` and shows as the join's first argument
(§6.3). The condition is over `$0` and `$1`, as in any join.

**Dependence is not a mode of the node.** Nothing records it: it is a
free occurrence of the left pattern in the right input, which the
validator sees and a rule can guard on. Decorrelation is a rewrite of
the right input until it no longer reads the pattern, after which the
join is an ordinary join — it can be commuted and reassociated, and
prints without the argument. Two trees that mean the same thing
therefore print the same, and there is nothing for a builder to drop.

**A scan translates the same way whether or not anything reads both
sides.** `yieldAll` is a dependent join followed by a projection that
keeps the right element:

```sml
from r in orders yieldAll r.items
```

```
project [#2 $0]
  join [v$0]
    orders
    #items v$0
```

which is what the step list did — a scan over the collection-valued
expression, then a `yield` of the freshly bound element — said with
nodes. Fusing the two, so that one constructor
both correlated and dropped, is what an earlier draft of this
document did under the name `projectMany`; the cost was a node that
did two things, that alone among the nodes did not bind `$0`, and
that a decorrelation rule had to rewrite rather than simplify.

**An outer apply is a dependent join whose kind is `left`.** A
correlated outer join — `from r in orders left join i in r.items on
p` — yields a row for an order none of whose items satisfy `p`. That
is exactly what §3.4 says a `left` join does, so it needs no special
device: the right input is evaluated per left element, and where it
yields nothing the join still emits a row with `$1` absent.

### 3.4 Two inputs

| Constructor | Arguments | Element type | Patterns |
| --- | --- | --- | --- |
| `join` | kind ∈ {inner, left, right, full}, `cond : bool` | the inputs' components concatenated, each component of a side the kind can leave absent wrapped in `option` | `$0` (left element), `$1` (right element), `$ordinal`; the left pattern is in scope in the right input too (§3.3) |
| `union`, `intersect`, `except` | `r₀ … rₙ`, `distinct : bool` | `τ₀` | — |

**A join has no yield.** It concatenates, and a projection follows
where the query wants something else (discussion.md §15). The
*components* of a node are

```
components(join(r₀, r₁)) = components(r₀) ++ components(r₁)
components(anything else) = [it]
```

so `(A ⋈ B) ⋈ C` and `A ⋈ (B ⋈ C)` both have components `A, B, C`:
flat, three of them, and the same in both associations. Reassociating
therefore changes no type and nothing above the node rewrites.
Commuting swaps the inputs and substitutes `$0` ↔ `$1` in the
condition; it renumbers the components, so a projection above does
re-path.

A component is not a field of an element: `from e in emps, d in
depts` has two components, each a whole row, not sixteen.

**The condition and the element see different types on an absent
side.** The condition is evaluated on candidate pairs, where both
elements are present, so it sees `$0 : τ₀` and `$1 : τ₁` whatever the
join's kind. The element is the output row, including rows that
matched nothing, so a component of a side the join can leave absent
is an `option`.

The asymmetry is the standard one — it is what every relational
executor does, and it is what makes `on a = b` mean what it says
rather than `valOf`-ing an option that is never `NONE` at that point.
It costs the reader one rule, and the alternative (an option in the
condition too) costs every outer join a partial function in its plan
text. So, for `from a in [1, 2] left join b in [1] on a = b`:

```
project [{a = #1 $0, b = #2 $0}]
  join [left] [$0 = $1]
    [1, 2]
    [1]
```

where `$1` is `int` in the condition, and the join's element is `int *
int option`.

Morel makes each *binder* of the absent side an option, not the side
as a whole — `left join (j, k) in pairs` binds `j : int option` and
`k : int option`, not `(int * int) option`. Where a query's binder is
a whole component, which is the usual case, the node's rule is
Morel's rule and nothing distributes: `left join d in depts` binds `d
: {…} option`, and two chained outer joins give `i : int option
option` because wrapping is additive. Where a *pattern* binds several
names inside one component, as `(j, k)` does, the projection above
maps each access through the option with `Option.map`. The node stays
simple either way.

Set operators require the element types of all their inputs to be
equal. They are n-ary; `distinct` distinguishes `union` from `union
all`.

## 4. Kinds

The kind of a node's output, given the kinds of its inputs. All rows
are transcribed from current step behavior, not redesigned; the
"checked" column names the query that pins it.

| Constructor | Output kind | Checked by |
| --- | --- | --- |
| leaf `e` | kind of `e` | — |
| `filter`, `project`, `skip`, `take`, `ifEmpty` | kind of input | `from i in [1,2] where i > 1` is a `list` |
| `join` | `list` if both inputs are `list`, else `bag` | `from i in [1,2,3], j in bag [i]` is a `bag` (a join is a nested loop) |
| `group` | kind of input | `from i in [1,2,3] group j = i` is a `list` |
| `sort` | `list` | — |
| `unorder` | `bag` | — |
| `union`, `intersect`, `except` | `list` if every input is a `list`, else `bag` | `from i in [1,2] union [3]` is a `list` |

`sort : coll -> list` and `unorder : coll -> bag` are the pair that
`unorder` pushdown manipulates, and the reason kinds are in the
signature rather than a property of the runtime value.

## 5. Well-formedness

The validator checks these after translation and after every rule
firing. They are the contract a rule must preserve, and the first
place to look when a rule is wrong.

1. **Types.** Every expression type-checks in the scope §2 gives it.
   Every node's element type is the type §3 derives. Set-operator
   inputs agree. `filter` and `join` conditions are `bool`; `skip`
   and `take` arguments are `int`.
2. **Kinds.** Every node's kind is the kind §4 derives.
3. **Scope.** Every name is bound: by a pattern of the node whose
   expression it is in, by an enclosing node's pattern where §2
   allows it — a join's left pattern in its right input, any node's
   patterns inside a nested tree — or by the enclosing environment.
   A join's right pattern occurs only in its condition. `skip`,
   `take` and `ifEmpty` bind nothing, so their argument names only
   the environment.
4. **Labels.** Within one node, output labels are distinct: the
   fields of a `project`'s record, and the keys and aggregates of a
   `group`. A join has no labels; its components are positional.
5. **Root type.** A rewrite preserves the type of the tree's root —
   both element type and kind. This is the cheap litmus that catches
   most rule bugs, including every rule that forgets a projection.
6. **Ordinal.** A node that binds an ordinal pattern has an input of
   kind `list`. An ordinal pattern that no expression reads is
   permitted, because a rewrite may transiently strip the last
   reference, but nothing a builder produces has one, so that two
   trees that mean the same thing print the same.

Rewrites that merge scopes — decorrelation, subquery unnesting — can
bring two identically-named binders together. The rename convention
is deterministic and specified here rather than left to
implementations, so that Java, Rust and Go print the same plan for
the same rewrite. **This is the one thing the freeze leaves open**,
and deliberately: no rule that merges scopes has landed, so a
convention written now would be a convention nothing exercises. Until
one lands (plan.md step 5) the validator rejects the collision, so
the contract is that it cannot arise — an implementation that also
rejects it agrees with this one, and the convention is a coordinated
change when the first such rule needs it.

## 6. Plan text

The grammar below is the contract; §7 shows it working. `Sys.planEx`
prints it, and `Sys.plan` does not: `Sys.plan` prints the row sinks
that execute, which is a different thing said in a different
notation.

### 6.1 One formatter, two modes

Core expressions have one formatter, and it lays out a relational
node in one of two ways.

*Inline* mode prints a relation as an ordinary expression, nested
like any other application, and fits the text to the line width as it
fits anything else. It is how Core reads when a plan is not what you
are looking at — an error message, a trace.

*Tree* mode is what `Sys.planEx` and `Sys.planOf` print, and what the
rest of this section specifies. **In tree mode a relation is always
broken out, whatever the width.** Layout is not a function of the
width here; the width governs only how a node's own line wraps.

### 6.2 The invariant

**A relational operator is the first non-whitespace on its line.**
Every other line is a leaf, a continuation, or part of the
definitions region below.

That single rule is what makes the text parseable, and the rest of
the layout follows from it rather than being stipulated beside it. A
relation that would otherwise print in the middle of a line — inside
a `let`, a `case`, the argument of `nonEmpty`, a field of a record —
cannot print there, and is broken out instead (§6.3).

### 6.3 Nodes, inputs, continuations

One node per line. A node's inputs are the lines below it, indented
by **two** spaces. A line is an operator name followed by its
arguments, each in brackets, in the order §3 lists them; arguments
that are absent (an inner join's kind, a `true` condition, a
`project` expression that is `$0`) are omitted.

**Patterns are not printed.** A node's expressions name its row
pattern as `$0` — `$1` for a join's right — and its ordinal pattern
as `$ordinal`, and that is all the text says of them: a node binds an
ordinal pattern exactly where its expressions read one. A pattern
that an expression *outside its own node* reads is named, with a
generated name numbered by §6.7. A join whose right input reads its
left pattern prints the name as its first argument, `join [v$0]`, and
the right input uses it (§3.3). A node whose nested tree reads its
row prints the expression as `let val v$N = $0 in … end`, and the
nested tree uses `v$N` (§6.4); the text is the same whether the
datatype holds that `let` or a direct reference.

Where a node's own line does not fit the width it wraps, and the
continuation is indented **four** from the node. Continuations follow
the node line immediately, so a reader — and a parser — separates
them from a grandchild by position: after a node at indent *d*, the
run of lines at *d + 4* before the first line at *d + 2* belongs to
that node; a line at *d + 4* after a line at *d + 2* is a grandchild.

```
plan     ::= node defn* legend?
node     ::= indent op arg* '\n' cont* node*
           | indent exp '\n'                    -- a leaf
cont     ::= indent4 text '\n'        -- wrapped: 4 from the node
op       ::= 'filter' | 'project' | 'ifEmpty'
           | 'join' | 'group' | 'sort' | 'unorder'
           | 'skip' | 'take' | 'union' | 'intersect' | 'except'
arg      ::= '[' exp ']' | '[' label '=' exp (',' label '=' exp)* ']'
           | '[' word ']'
defn     ::= '\n' 'r$' int ('[' name (',' name)* ']')? ' =' '\n' node
```

### 6.4 Relations reached from inside an expression

A relation that §6.2 forbids printing in place is replaced by a
reference, `r$0`, `r$1`, …, numbered from zero in the order the
references were first handed out. Each is then printed as a block of
its own, after the tree and before the type legend, introduced by
`r$N =` and indented two:

```
project [let val v$0 = $0 in {i = $0, ys = r$0[v$0]} end] : t$0
  [1, 2] : int list

r$0[v$0] =
  filter [$0 > v$0] : int list
    [3, 4] : int list

t$0 {i:int, ys:int list} list
```

A `$` marks a name the printer made, and cannot occur in an identifier
(§6.7), so `r$0` and `t$0` cannot be read as anything the query wrote.
Brackets would be worse than useless here: `r[0]` has the shape of an
operator applied to an argument, which is what §6.2 says a line begins
with.

The reference carries no type of its own; the block's root line
carries it, as any node does.

**A fragment declares what it reads from outside itself.** The names
in brackets after `r$N` are the variables the fragment uses that
something outside it binds, and the same text stands at the reference
and at the definition. A fragment with no such variables is written
`r$N`, with no brackets, and is independent: nothing in it depends on
the row above.

The list is *transitive*, and that is the point of repeating it at the
reference. A fragment can be free in a variable it never mentions,
reaching it only through a fragment nested inside it:

```
filter [let val v$0 = $0 in nonEmpty (r$0[v$0]) end] : int list
  [1, 2, 3] : int list

r$0[v$0] =
  project [{a = #1 $0, b = #2 $0}] : {a:int, b:int} list
    filter [#2 $0 > 0 andalso nonEmpty (r$1[v$0])] : (int * int) list
      pairs : (int * int) list

r$1[v$0] =
  project [{c = #1 $0, d = #2 $0}] : {c:int, d:int} list
    filter [#1 $0 = v$0] : (int * int) list
      pairs : (int * int) list
```

Nothing in `r$0`'s own lines names `v$0`. It is `r$1[v$0]`, written
where `r$0` refers to `r$1`, that shows `v$0` passing through — and
`r$0[v$0]` at the head declares it.

A fragment may read more than one, and they are written in order:
`r$1[v$0, v$1]`.

An implementation must therefore compute the list before it writes
anything, since the reference is printed before the block in which the
dependency appears.

A block is a tree like any other: its root sits at indent two, under
the `r$N` that introduces it, and §6.3 applies within it from
there. That is the point: before this rule a nested
tree was spliced into the line that contained it, its own indentation
started again from zero in the middle of the enclosing one, and two
nodes at different depths could print at the same indent. The text
could not be parsed, and no implementation could have reproduced it.

Scope is unaffected, and becomes visible: `r$0` above reads `v$0`,
which the node that refers to it binds. A reference is a rendering of
the tree that is there, not a rewrite of it.

### 6.5 Width

A node line wraps at the session's `lineWidth`, the property that
also governs how values print. There is no separate plan width: a
plan is Core, Core has one formatter (§6.1), and a second width would
be a second answer to the same question.

What that exposes is bounded, and §6.1 is what bounds it. Tree mode
breaks relations out whatever the width, so the nodes, their
indentation, the `r$N` blocks and every number in the text are the
same at any width; only a node's own line wraps differently. A plan
read at another width is the same plan, folded differently. It cannot
gain a node, lose one, or renumber one.

It follows that `lineWidth` is the knob for reading a plan: widen it
to see a long condition on one line, narrow it to keep a deep tree
inside a terminal.

What it asks of a script is that it not change `lineWidth` between
the plans it prints. The suite already holds to that — forty of its
fifty-two settings are the same value, 78, set at the head of a file,
and the two scripts that vary it (`built-in/sys.smli`, `blog.smli`)
vary it after their last plan — so the golden plans are all at 78.

### 6.6 Expressions and types

Expressions inside brackets are printed as Morel, by the same
formatter in its inline mode (§6.1), so a field access appears as
`#deptno $0` (Morel's `e.deptno` is sugar for `#deptno e`) and a
record construction as `{d = $1, e = $0}`.

`Sys.planEx` and `Sys.planOf` print the tree with `: type` appended
to every node line, the type being the node's full collection type.
A continuation line carries no type; the type belongs to the node,
and the node is the line it starts on.

A moniker of **24 characters or fewer** is printed in full. A longer
one is replaced by a reference, `t$0`, `t$1`, ..., numbered from zero
in the order the references were first handed out; each is then
printed once in a *legend*, one line per type, after a blank line at
the end of the plan:

```
project [{comm = #comm (#3 $0), ...}] : t$0
  filter [#deptno (#2 $0) = #deptno (#1 $0)] : t$1
    join : t$1
      join : t$2
        #depts scott : t$3
        #emps scott : t$4
      #bonuses scott : t$5

t$0 {comm:real, dname:string, ename:string} bag
t$1 ({deptno:int, dname:string, loc:string} * ...) bag
...
```

A character count rather than a rule about the type's shape, because
three implementations must agree on which types are abbreviated and
they already agree on the moniker's text; a rule phrased over records
and tuples would abbreviate `(int * int) list`, which is short and
reads better in full. The threshold sits above `int option list` (15)
and `{a:int, b:int} list` (19), and below a three-field record (41).

Two types that print the same moniker share a reference, because the
moniker is all the plan says about them. The numbering, like the
numbering of generated binders, is a property of the *text*: a tree
nested in another tree's expressions shares the enclosing text's
references and legend, so one legend covers the whole plan. The
implementation consequence is the same one, for the same reason --
thread one writer through, rather than concatenating strings that
children returned.

### 6.7 Names

Generated labels sort with user labels under one collation, pinned
here so that three implementations agree: labels compare as Morel
strings compare, which puts `$`-prefixed names before alphabetic
ones.

**Generated binders are numbered by the printer**, from zero, in
order of first occurrence in the text: `v$0`, `v$1`, and so on.
Nothing about their allocation reaches the text, or the same query
would print differently depending on what was compiled before it, and
no other implementation could reproduce it. A `$` cannot occur in an
identifier, so a generated name cannot capture one the query wrote.

More precisely: **allocated freely, and renumbered when printed.** A
binder takes whatever number its maker's counter gives it, because
uniqueness is all that allocation is asked for; the *printer* then
numbers the generated binders it finds, from zero, in order of first
occurrence. `v$123`, `v$110`, `v$200`, `v$110` print as `v$0`, `v$1`,
`v$2`, `v$1`.

That is what makes the text depend on the query and nothing else,
and it survives nesting, which a rule about allocation does not: two
nested trees may each allocate `v$0`, and a scheme that numbered per
tree would have them collide, whereas a printer sees the whole text
and numbers what it finds. Morel does the same for type variables —
`TypeSystem.unqualified` prints `('b * 'a * 'b)` as `('a * 'b *
'a)` — so an implementation has the pattern already.

Each prefix is numbered in its own sequence, so a tree's `v$` and a
lowering's `w$` do not interleave.

A tree nested inside another tree's expressions shares the enclosing
text's numbering; it does not restart at zero. This was the one thing
§6 left open, on the reasoning that a nested query was still a step
list and would need a rule of its own — numbering by position, or a
prefix per nesting level — once the resolver built trees natively.
It needed neither. The rule as written already says "the printer
numbers the generated binders *it finds*, from zero, in order of
first occurrence", and a nested tree is written by the same formatter
onto the same writer as the tree that contains it, so one sequence
spans the whole text — the `r$N` blocks of §6.4 included:

```
filter [let val v$0 = $0 in nonEmpty (r$0[v$0]) end] : int list
  [1, 2, 3] : int list

r$0[v$0] =
  project [{a = #1 $0, b = #2 $0}] : {a:int, b:int} list
    filter [let val v$1 = $0 in #1 $0 = v$0
        andalso nonEmpty (r$1[v$0, v$1]) end] : (int * int) list
      pairs : (int * int) list

r$1[v$0, v$1] =
  project [{c = #1 $0, d = #2 $0}] : {c:int, d:int} list
    filter [#1 $0 = #2 v$1 andalso #2 $0 = v$0] : (int * int) list
      pairs : (int * int) list
```

Three sequences run through that text and all three are properties of
the text rather than of any node: `v$0` and `v$1` by first
occurrence, `r$0` and `r$1` by first reference, and `t$N` where a
type is too long to print. The middle `filter` also shows §6.3's
continuation rule: its line does not fit, so it wraps at four from
the node, two deeper than the `pairs` that is its input.

An implementation that builds the text by concatenating strings its
children returned gets all three wrong, and gets them wrong silently
— each child restarts at `v$0`, `r$0`, `t$0`. Thread one writer
through.

`compute` has no line of its own: `from … compute` prints as its
`group`, and the extraction of the single element belongs to the Core
expression that wraps the tree. The alternative — a `compute` node
whose type is a scalar — buys a shorter plan at the cost of a
constructor that is not collection-valued, which every rule would
then have to case on. Frozen as written.

## 7. Worked examples

The join from the issue:

```sml
from e in scott.emps
  join {dname, deptno = id, ...} in scott.depts on e.deptno = id
```

```
project [{dname = #dname (#2 $0), e = #1 $0, id = #deptno (#2 $0)}]
  join [#deptno $0 = #deptno $1]
    scott.emps
    scott.depts
```

The join contributes two components, `scott.emps` and `scott.depts`.
The pattern's binders (`dname`, `id`) and the record punning have
become field accesses in the projection above it; the element type is
that projection's, and no binding list records what `dname` used to
mean.

A correlated scan:

```sml
from d in scott.depts, e in d.emps where e.sal > 1000 yield {d, e}
```

```
project [{d = #1 $0, e = #2 $0}]
  join [v$0]
    scott.depts
    filter [#sal $0 > 1000]
      #emps v$0
```

The left pattern prints as an argument, under a generated name,
before the condition, and is omitted when the right input does not
read it. Its two inputs are its two
children, as any join's are; there is no lambda header, because the
right input is an ordinary input rather than the body of a function.

Group:

```sml
from e in scott.emps
  group deptno = e.deptno compute total = sum over e.sal
```

```
group [deptno = #deptno $0] [total = sum over #sal $0]
  scott.emps
```

## 8. What this replaces

`Core.From`'s step list, `Core.FromStep` and its subclasses, and
`Core.StepEnv` are gone. The compiler compiles the tree to row sinks
directly: a node's patterns become stack slots, or expressions over
them, and `RowSink` execution is unchanged. The Calcite compiler
reads the tree too.
