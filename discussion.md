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
# Discussion: query rewrite rules in Morel (issue #359)

A working transcript of an exploratory design discussion. Nothing here
is locked in.

---

## User

let's start talking about https://github.com/hydromatic/morel/issues/359.
i would like to construct a worked example - one or two rules, a simple
reactor, a query that can be improved by those rules - that looks like
code the user would write.

## Assistant

Here's a sketch to react to. I'll keep it small and propose two rules
whose composition matters — that's what makes the reactor non-trivial.

### The worked example

**A toy plan algebra** (small enough to read, big enough to optimize):

```sml
datatype cond
  = Eq  of string * int          (* col = literal *)
  | And of cond * cond

datatype expr
  = Scan   of string * string list   (* table, column names *)
  | Filter of cond * expr
  | Join   of expr * expr * cond     (* l, r, join condition *)
```

**Two helpers** the rules will call (these are the kind of thing users
write, not built-ins):

```sml
fun colsOf (Scan (_, cs))   = cs
  | colsOf (Filter (_, e))  = colsOf e
  | colsOf (Join (l, r, _)) = colsOf l @ colsOf r

fun condCols (Eq (c, _))    = [c]
  | condCols (And (c1, c2)) = condCols c1 @ condCols c2

fun refsOnly cond e =
  List.all (fn c => List.exists (fn c' => c = c') (colsOf e)) (condCols cond)
```

**Two rules** in the proposed `rule` form:

```sml
(*) Push a Filter into the left input of a Join when the
(*) predicate mentions only left-side columns.
rule pushFilterToLeft
    (Filter (c, Join (l, r, jc))) when refsOnly c l
  = [Join (Filter (c, l), r, jc)]

(*) Collapse two adjacent Filters into one with a conjoined predicate.
rule mergeFilters
    (Filter (c1, Filter (c2, e)))
  = [Filter (And (c1, c2), e)]
```

**A program** that bundles the rules with a strategy:

```sml
val pushdown : program =
  { rules    = [mergeFilters, pushFilterToLeft],
    strategy = Fixpoint (First [OneRule mergeFilters,
                                OneRule pushFilterToLeft]) }
```

**The query** (the input plan) — chosen so that *neither rule alone*
finishes the job:

```sml
val emp  = Scan ("emp",  ["id", "name", "deptno", "salary"])
val dept = Scan ("dept", ["deptno", "dname"])

val q =
  Filter (Eq ("salary", 50000),
    Filter (Eq ("deptno", 10),
      Join (emp, dept, Eq ("deptno", 10))))
```

Tracing what the reactor does:

1. `mergeFilters` fires at the root →
   `Filter (And (sal=50000, dept=10), Join (emp, dept, …))`
2. `pushFilterToLeft` fires (both conjuncts now reference emp-side
   columns) → `Join (Filter (And (…), emp), dept, …)`
3. Fixpoint: nothing more matches, done.

That's the classic predicate-pushdown story in two rules and one
query, and it shows why incremental firing matters: rule 2 can only
fire *after* rule 1 has rewritten the subtree.

### Three things I'd like your call on

1. **Plan algebra shape.** I picked `Filter` + `Join` + `Scan` and
   split `cond` from `expr`. Alternative: fold `cond` into `expr` (as
   in the issue's example, where Filter takes `expr * expr`).
   Splitting reads more like real plans; folding makes the pattern
   story uniform. Which framing do you want the worked example to live
   in?

2. **Second rule.** `mergeFilters` is nice because it composes with
   pushdown, but `commuteJoin` (from the issue) is the better
   motivator for the *MEMO*, since it produces multiple equivalents
   rather than progressing toward a normal form. Want the worked
   example to be merge+push (predicate-pushdown story) or push+commute
   (search-space story)?

3. **Reactor depth.** Do you want the worked example to include a
   runnable toy reactor (naïve top-down + fixpoint, ~15 lines, no
   MEMO), or stop at "here's the program; the library handles it"?
   The toy reactor makes the example end-to-end runnable today but
   risks anchoring readers on the wrong implementation.

---

## User

I would like this planner to be able to optimize morel programs
(initially the relational subset of morel programs). Therefore
expressions are a representation of morel's own core language. SEe
also https://github.com/hydromatic/morel-rust/issues/33. There's no
need to declare columns because the expressions can introspect types.

## Assistant

Got it — `expr` is Morel's `Core`, and rules match on `Core.From` /
`Core.FromStep` constructors. Here's a revised sketch.

### Worked example, take 2

**The expression type is Morel's own.** Rules pattern-match
constructors that already exist: `Core.From` (which holds a
`step list`), `Core.Scan`, `Core.Where`, `Core.Yield`, `Core.Group`,
`Core.Order`, `Core.Take`, `Core.Skip`, `Core.Union`, etc. No
user-declared `expr`; no user-declared columns.

**Two rules, both classical predicate-pushdown moves over a
`from`-pipeline.**

```sml
(*) R1: collapse two adjacent `where` steps.
rule mergeWheres
    (From [..., Where p1, Where p2, ...])
  = [From [..., Where (p1 andalso p2), ...]]

(*) R2: hoist a `where` past a preceding `scan` whose variables it
(*) doesn't reference. `freeVars` and `boundVars` come from the
(*) standard library — they walk Core and consult its types directly.
rule pushWhereBeforeScan
    (From [..., Scan (pat, e), Where p, ...])
    when disjoint (freeVars p, boundVars pat)
  = [From [..., Where p, Scan (pat, e), ...]]
```

**A query a user would actually write.** Surface syntax:

```sml
from e in emps
  where e.sal > 50000
  join d in depts on e.deptno = d.deptno
  where e.bonus > 1000
```

**Core after parse/typecheck**:

```
From [ Scan (e, emps),
       Where (e.sal > 50000),
       Scan (d, depts, on = e.deptno = d.deptno),
       Where (e.bonus > 1000) ]
```

**Trace under `Fixpoint (First [pushWhereBeforeScan, mergeWheres])`:**

1. `pushWhereBeforeScan` fires on
   `..., Scan d, Where (e.bonus > 1000), ...` — the predicate's free
   vars are `{e}`, scan-d binds `{d}`, disjoint, swap:
   `[Scan e, Where (sal>50000), Where (bonus>1000), Scan d, …]`
2. `mergeWheres` fires on the two newly-adjacent wheres:
   `[Scan e, Where (sal>50000 andalso bonus>1000), Scan d, …]`
3. Fixpoint: nothing matches, done.

Neither rule alone produces this shape, which is the point of having
a fixpoint reactor with bottom-up firing.

### The unresolved question this surfaces

The rule LHS uses `[..., Where p1, Where p2, ...]` — a *context
pattern* that matches two adjacent elements somewhere in a variadic
list. That's stronger than the issue's current pattern language:
`AnyOf p` covers "*some* child matches `p`," not "*two adjacent*
children match these patterns."

Three choices:

1. **Extend the pattern language** with a context/adjacency form
   (`Adjacent (p1, p2)` inside a variadic position). Cleanest for
   users; new indexing logic in the reactor.
2. **Keep the pattern language as-is**, write the rules at
   granularity `From _` and let `apply` walk the step list itself.
   Works today but every `From` becomes a candidate — loses most of
   the indexing benefit for these rules.
3. **Lift step-list rewrites out of the rule form** and offer a
   separate combinator (e.g.
   `stepRule : (step * step -> step list option) -> rule`).
   Specialized but precisely indexable: the reactor subscribes to the
   *pair* of step constructors.

My lean is (3) for `from`-pipeline rewrites, since "rewrite a window
of adjacent steps" is the dominant shape and (3) makes the reactor's
job easy. (1) is the right answer if we expect non-`from` variadic
rewrites later. Which way do you want to go?

---

## User

I think From and FromStep are poor choices for rules and planning.
The expression language will have operators - basically builtin
functions - for Filter {input, condition}, Project {input,
expression}, Join {left, right, conditition}, Union {all, inputs} -
in the usual tree structure. But expressions like condition and
expression are ordinary morel expressions.

## Assistant

Got it — so the optimizer sees a tree of relational operators
(Filter, Project, Join, Union), and each operator's "scalar" fields
(the condition, the projection function) are ordinary Morel
expressions of normal types. That's the morel-rust #33 picture.

### Worked example, take 3

**Operators look like built-in functions over records** — so the
constructor-shape a rule matches is `Filter {input, condition}`,
`Join {left, right, condition}`, `Project {input, expression}`,
`Union {all, inputs}`.

**Two rules.**

```sml
(*) R1: collapse two adjacent Filters.
rule mergeFilters
    (Filter {input = Filter {input = e, condition = c1},
             condition = c2})
  = [Filter {input = e, condition = c1 andalso c2}]

(*) R2: push a Filter past a Join when the predicate only refers to
(*) the left input. `refsOnly` consults the (already-known) row types
(*) of the operand and the (already-known) free vars of the condition;
(*) the user doesn't have to declare anything.
rule pushFilterPastJoinLeft
    (Filter {input = Join {left, right, condition = jc},
             condition = c})
    when refsOnly (c, left)
  = [Join {left = Filter {input = left, condition = c},
           right, condition = jc}]
```

**The query** (surface):

```sml
from e in emps, d in depts
  where e.sal > 50000 andalso e.deptno = d.deptno
  where e.bonus > 1000
```

**After translation to the relational tree** (the planner's input):

```
Filter {
  input     = Filter {
                input     = Join {left = emps, right = depts,
                                  condition = e.deptno = d.deptno},
                condition = e.sal > 50000
              },
  condition = e.bonus > 1000
}
```

**Trace.**

1. `mergeFilters` fires at the root →
   `Filter {input = Join {...}, condition = e.sal>50000 andalso e.bonus>1000}`.
2. `pushFilterPastJoinLeft` fires (condition refs only `e`) →
   `Join {left = Filter {input = emps, condition = …}, right = depts,
          condition = e.deptno = d.deptno}`.
3. Fixpoint — done.

Both rules contribute; neither is sufficient alone; the body of each
is a plain Morel expression that constructs a new tree node.

### Three design questions this exposes

1. **Pattern surface for record-shaped operators.** The rule LHS
   above uses Morel record patterns — `Filter {input, condition}` —
   and the compiler picks up `Filter` as the indexable constructor
   name. Confirm that's the spelling you want, vs. positional
   `Filter (input, condition)`.

2. **Are operators constructors or function applications under the
   hood?** "Built-in functions" suggests `Filter` is just a name
   resolved to an applicable, so internally the node is
   `Apply (BuiltIn FILTER, Record {input, condition})`. The rule form
   then needs to canonicalize "looks like an Apply of a known
   relational operator" into a virtual constructor for indexing.
   Worth confirming so the pattern-extraction story is concrete.

3. **Do rules fire into scalar fields?** In
   `Filter {input, condition}`, `input` is a relational subtree (rules
   should fire there — `Any`); `condition` is an ordinary scalar
   expression (rules normally shouldn't fire there — `Leaf`). My
   lean: relational operators' relational fields default to `Any`,
   scalar fields default to `Leaf`, with a future opt-in for
   scalar-rewrite rules. Agree?

---

## User

1, yes let's use records. 2. the relational operators should be
constructors (in fact all built-in functions could be constructors,
definitely the common ones like 'andalso' and '='; calls to
user-defined functions and maybe some less common built-in functions
would be a 'Call' constructor whose first argument is the target
function), 3. yes in principle but no in practice because (at first,
at least) all the rules match only the relational operators.

## Assistant

Locked in. Here's the consolidated example.

### Expression representation

```sml
datatype expr
  (*) Relational operators (record-shaped) *)
  = Filter  of {input : expr, condition : expr}
  | Project of {input : expr, expression : expr}
  | Join    of {left : expr, right : expr, condition : expr}
  | Union   of {all : bool, inputs : expr list}
  | Scan    of string                  (*) named relation *)

  (*) Common built-ins as constructors *)
  | Andalso of expr * expr
  | Orelse  of expr * expr
  | Eq      of expr * expr
  | Lt      of expr * expr
  | …

  (*) Everything else — user-defined funs, less-common built-ins *)
  | Call    of expr * expr list

  (*) Variables, literals, records, etc. *)
  | Var     of string
  | Lit     of literal
  | Record  of (string * expr) list
  | …
```

### The two rules

```sml
rule mergeFilters
    (Filter {input = Filter {input = e, condition = c1},
             condition = c2})
  = [Filter {input = e, condition = c1 andalso c2}]
       (*) `c1 andalso c2` is surface for Andalso (c1, c2) *)

rule pushFilterPastJoinLeft
    (Filter {input = Join {left, right, condition = jc},
             condition = c})
    when refsOnly (c, left)
  = [Join {left = Filter {input = left, condition = c},
           right, condition = jc}]
```

In v1, the reactor's pattern indexer looks at `Filter`, `Join`,
`Project`, `Union` (the relational constructors). All scalar
positions in those patterns — `condition`, `expression` — are treated
as `Leaf`: rules don't fire inside them. `refsOnly` is just an
ordinary Morel predicate that walks the condition tree.

### The query, the input plan, the trace

```sml
from e in emps, d in depts
  where e.sal > 50000 andalso e.deptno = d.deptno
  where e.bonus > 1000
```

```
Filter {
  condition = e.bonus > 1000,
  input = Filter {
    condition = e.sal > 50000 andalso e.deptno = d.deptno,
    input = Join {left = Scan "emps", right = Scan "depts",
                  condition = Lit true}
  }
}
```

1. `mergeFilters` at root → one Filter with conjoined condition.
2. `pushFilterPastJoinLeft` — the merged condition still references
   `d` (via `e.deptno = d.deptno`), so it doesn't fire as-is. We need
   either a **split** rule or a smarter push that splits conjuncts at
   the boundary.

### One thing this surfaces

The compelling version of pushdown wants three rules — split, push,
(sometimes) merge. With `Andalso` as a constructor,
`splitFilterConjunction` is one line:

```sml
rule splitFilterConjunction
    (Filter {input = e, condition = Andalso (c1, c2)})
  = [Filter {input = Filter {input = e, condition = c1},
             condition = c2}]
```

But `splitFilterConjunction` and `mergeFilters` are inverses — under
naïve `Fixpoint`, a program containing both never terminates. The
strategy story has to handle this: either (a) include only one
direction in any given program, (b) phase the program (split → push
→ merge), or (c) eventually a cost model picks the cheaper of the
two equivalents in the MEMO.

For the worked example I'd propose **three rules with a phased
strategy**, which lets the example actually finish predicate-pushdown
end-to-end and motivates phases. Want me to write it up that way, or
keep the two-rule version and accept that the trace stops short of
full pushdown?

---

## User

let's not have split-filter. filter-join-transpose would decompose
the filter, and leave any conjunctions that cannot be pushed down.

## Assistant

Right, that's the cleaner design — one rule that knows how to
decompose, push what it can, and leave the rest.

### Updated rule

```sml
rule filterJoinTranspose
    (Filter {input = Join {left, right, condition = jc},
             condition = c})
  = let
      val cs = conjuncts c
      val (pushL, rest)  = List.partition (fn p => refsOnly (p, left))  cs
      val (pushR, keep)  = List.partition (fn p => refsOnly (p, right)) rest
    in
      if null pushL andalso null pushR then []
      else
        let
          val left'  = if null pushL then left
                       else Filter {input = left,  condition = conjoin pushL}
          val right' = if null pushR then right
                       else Filter {input = right, condition = conjoin pushR}
          val join'  = Join {left = left', right = right', condition = jc}
        in
          [if null keep then join'
           else Filter {input = join', condition = conjoin keep}]
        end
    end
```

Notes:

- `conjuncts : expr -> expr list` flattens nested `Andalso` (which is
  now a constructor, so this is a one-liner over `Andalso` /
  non-`Andalso`).
- `conjoin : expr list -> expr` is `foldr1 Andalso`.
- Returning `[]` when nothing is pushable lets the reactor treat this
  as a no-op firing — exactly the "false positives are fine" contract
  from the issue.

This subsumes both the left-push and right-push rules, so the program
is now just:

```sml
val pushdown : program =
  { rules    = [mergeFilters, filterJoinTranspose],
    strategy = Fixpoint (First [OneRule mergeFilters,
                                OneRule filterJoinTranspose]) }
```

### Updated trace

Input plan (post-translation from the surface query):

```
Filter {
  condition = e.bonus > 1000,
  input = Filter {
    condition = e.sal > 50000 andalso e.deptno = d.deptno,
    input = Join {left = Scan "emps", right = Scan "depts",
                  condition = Lit true}
  }
}
```

1. **`mergeFilters`** — root and its child collapse:
   ```
   Filter {
     condition = (e.bonus > 1000) andalso (e.sal > 50000)
                                  andalso (e.deptno = d.deptno),
     input = Join {Scan "emps", Scan "depts", Lit true}
   }
   ```

2. **`filterJoinTranspose`** — splits to
   `[bonus>1000, sal>50000, deptno=d.deptno]`; first two go left,
   last stays:
   ```
   Filter {
     condition = e.deptno = d.deptno,
     input = Join {
       left  = Filter {input = Scan "emps",
                       condition = (e.bonus > 1000) andalso (e.sal > 50000)},
       right = Scan "depts",
       condition = Lit true
     }
   }
   ```

3. **Fixpoint** — `filterJoinTranspose` reruns on the new outer
   Filter, finds nothing pushable, returns `[]`. `mergeFilters` has
   no adjacent Filters. Done.

A natural next rule (out of scope for v1 of the example) would be a
`joinAbsorbFilter` that pulls a Filter directly above a Join *into*
the join's `condition` field — turning `e.deptno = d.deptno` into a
real join predicate. That's a nice future demo of Cascades-style
search since the pulled-in predicate lights up further alternatives.

---

## User

That all makes sense. But I would also like to sketch out (a) how a
user (who writes morel programs to query data) would invoke the
planner, and (b) what is the actual data structure for a rule
(leaving aside syntactic sugar, and defining all of the data that is
necessary for the reactor to work, and (c) how to specify inputs to
relational expressions that are actually equivalence sets.

## Assistant

Three sketches.

### (a) How a user invokes the planner

**Implicit (default)** — most users never touch the planner directly:

```sml
from e in emps, d in depts
  where e.sal > 50000 andalso e.deptno = d.deptno
```

The compiler translates `from` to a relational op tree, runs
`Plan.optimize defaultProgram` on it, then compiles the result. The
user sees only the answer.

**Explicit** — for power users who want to pick a program, inspect,
or write rules:

```sml
val raw = Plan.of (from e in emps, d in depts
                     where e.sal > 50000 andalso e.deptno = d.deptno)
val opt = Plan.optimize pushdown raw          (*) op tree -> op tree *)
val rows = Plan.run opt

(*) Or in one step:
val rows = Plan.runWith pushdown
             (from e in emps, d in depts where ...)

(*) Inspecting the search space:
val memo = Plan.explore pushdown raw
val all  = Plan.allPlans memo                 (*) every equivalent plan *)
```

`Plan.of` lifts a relational morel expression into the IR (it's the
same lift the implicit path uses). `Plan.run` compiles and runs an
op tree. `Plan.optimize` returns one tree; `Plan.explore` returns the
MEMO; `Plan.allPlans` enumerates representatives.

### (b) The rule data structure

Stripped of sugar, what the reactor actually needs:

```sml
datatype pattern
  = Any                            (*) any subtree; rules may fire inside *)
  | Leaf                           (*) any subtree; opaque to this rule  *)
  | Con   of con * pattern list    (*) operator name + per-position kids *)
  | AnyOf of pattern               (*) "some child matches" for variadic *)
  | Guard of pattern * (op -> bool)

withtype con = string              (*) "Filter", "Join", ...             *)

(*) Index entry: when an op of constructor `con` enters a MEMO group at
(*) this path relative to the rule's match-root, the reactor tries the rule.
type subscription = { path : int list, con : con }

type rule = {
  name          : string,                  (*) trace label                  *)
  pattern       : pattern,                 (*) shape, used for indexing     *)
  apply         : op -> op list,           (*) rewriter; [] = no-op firing  *)
  subscriptions : subscription list        (*) derived from pattern at      *)
}                                          (*) registration time            *)

type program = { rules : rule list, strategy : strategy }
```

That's it. Notes:

- `subscriptions` is derivable from `pattern`, but materialized at
  registration so the index is built once.
- `apply` returns *equivalents* of its argument. The reactor adds
  each returned op to the same MEMO group as the input — that's how
  the search space grows.
- No idempotence flag, no priority, no cost. Re-firing is prevented
  by MEMO group-membership: once a rule has fired against a given
  group, the reactor remembers and won't re-call it on the same
  group.
- Logical-vs-physical rule kind is a future field (when
  implementation rules join transformation rules); not needed in v1.

### (c) Inputs as equivalence sets

The shift from "tree" to "MEMO" is in the op type itself: child
fields are *groups*, not ops.

```sml
datatype op
  = Filter  of {input : group, condition : expr}
  | Project of {input : group, expression : expr}
  | Join    of {left : group, right : group, condition : expr}
  | Union   of {all : bool, inputs : group list}
  | Scan    of string

eqtype group                            (*) opaque MEMO equivalence class *)

type memo = {
  members : group -> op list,           (*) ops in each group              *)
  groupOf : op    -> group              (*) which group an op lives in     *)
}
```

A "plan tree" the user constructs (from `Plan.of` or by hand) is a
tree of ops. Lifting it into the MEMO walks bottom-up: each op gets
a fresh group, and each child becomes a group reference.

```sml
val intern : opTree -> memo * group     (*) the lift                       *)
```

**Pattern matching descends through groups.** For a pattern like

```
Filter {input = Join {left, right, condition = jc}, condition = c}
```

the reactor does roughly:

```
For each op `f` in the root group such that `f` is a Filter:
  Let g = f.input  (* a group, by the type of Filter.input *)
  For each op `j` in members(g) such that `j` is a Join:
    Bind left  := j.left   (* a group  *)
    Bind right := j.right  (* a group  *)
    Bind jc    := j.condition
    Bind c     := f.condition
    Try the rule with these bindings.
```

So the bindings `left` and `right` have type `group`, not `op`. The
user doesn't get to dig deeper into `left` than the pattern matched —
if they want to, they extend the pattern.

**Rule bodies stay tree-shaped.** The body of `filterJoinTranspose`
writes:

```sml
Join {left  = Filter {input = left,  condition = pushedL},
      right = right,
      condition = jc}
```

Type-wise: `Join.left` expects a `group`. The expression
`Filter {input = left, condition = pushedL}` is an `op`. So the
body's return type is "tree of ops with `group`-typed leaves" — i.e.,
`op`s nested directly, but where any child position can also be a
bound `group` value. Two ways to spell this in the type system:

1. **Coercion at insertion.** When the reactor takes a returned
   `op`, it walks it and interns each constructed sub-op into a
   fresh group, leaving bound `group` values untouched. Cleanest
   for the user; needs the field type to admit both `op` and
   `group` (e.g., `child = OpRef of op | GroupRef of group`).
2. **Explicit `Plan.group : op -> group`.** The user wraps their
   constructed ops:
   `Filter {input = Plan.group (Filter {...}), …}`. More verbose but
   pure SML.

I lean (1), with the union type called something boring like `arg`:

```sml
datatype arg = Inline of op | Group of group

datatype op
  = Filter  of {input : arg, condition : expr}
  | Join    of {left : arg, right : arg, condition : expr}
  | …
```

Pattern matching on a `Filter` always sees an `arg` in `input`; in
practice it's `Group g` after interning, but in user-constructed
trees (from `Plan.of`) it starts as `Inline _` and gets normalized
when the tree enters the MEMO. From the rule writer's perspective,
both forms behave the same: a child you can pass around but can't
re-open without explicit unwrapping.

Want me to push (a) and (b) into a doc / proposal section, and stay
on (c) until the `arg` / interning shape is decided?

---

## User

when a rule is invoked (having matched a particular group of nodes in
the graph) the arguments that were matched by parameters of the rule
will be inline, and others will remain as is (probably groups).

## Assistant

That's the right semantics. It collapses cleanly onto a single op
type with a "group reference" leaf.

### Unified op type

```sml
datatype op
  = Filter  of {input : op, condition : expr}
  | Project of {input : op, expression : expr}
  | Join    of {left : op, right : op, condition : expr}
  | Union   of {all : bool, inputs : op list}
  | Scan    of string
  | Ref     of group           (*) opaque equivalence-class reference *)
```

Every relational child is just `op`. Inline forms (`Filter`, `Join`,
…) and group references (`Ref _`) are unified at the type level —
both are values of type `op`. Inline = "the structure is here";
`Ref` = "look me up in the MEMO."

### What the reactor hands the rule

For `filterJoinTranspose` matching at some root group:

```sml
rule filterJoinTranspose
    (Filter {input = Join {left, right, condition = jc}, condition = c})
  = …
```

The reactor materializes only the path the pattern descended:

```
Filter {                         <- inline (matched root)
  input = Join {                 <- inline (pattern descended)
    left  = Ref g_L,             <- Ref (Any position; not opened)
    right = Ref g_R,             <- Ref (Any position; not opened)
    condition = <expr jc>        <- scalar; bound as-is
  },
  condition = <expr c>           <- scalar; bound as-is
}
```

So the bindings are:

| binding | type | how |
|--------|------|-----|
| `left`  | `op` | concretely a `Ref g_L` |
| `right` | `op` | concretely a `Ref g_R` |
| `jc`    | `expr` | scalar |
| `c`     | `expr` | scalar |

The rule body uses them as opaque op-shaped leaves:

```sml
[Join {left  = Filter {input = left,  condition = pushedL},
       right = right,
       condition = jc}]
```

The `Ref` values pass through unchanged. The freshly-constructed
`Filter` and outer `Join` are inline. When the reactor takes this
returned `op` and inserts it into the MEMO, it walks down, allocates
new groups for inline sub-ops, and stops descending at `Ref` (which
already names a group). Result: the new equivalents share the same
group references for the parts the rule didn't touch — exactly the
Cascades invariant.

### A few small consequences

- **Pattern matching descends across `Ref` transparently.** When the
  SML-level pattern says `Filter {input = Join {…}, …}`, that's a
  guarantee from the reactor; the reactor walks group members until
  it finds a Join (or fails to). The rule writer never sees `Ref`
  at matched positions.

- **Patterns that bind without descending see `Ref`.** Variable-only
  positions, `Any` positions, and any sub-tree the rule doesn't care
  about all surface as `Ref`. The rule body can hand them around but
  can't pattern-match them in normal SML without explicit group
  lookup.

- **There's no separate `arg`/`Inline` wrapper.** One type, one set
  of constructors, with `Ref` as the "this is a group" leaf. That's
  simpler than the union I floated earlier — and your clarification
  implies it: matched-positions bind as the constructor itself, and
  unmatched-positions bind as `Ref`.

- **`Plan.of` produces all-inline trees.** Lifting an inline tree
  into the MEMO interns it: every `Filter`/`Join`/etc. becomes a
  member of a fresh group, with each child rewritten from inline form
  to `Ref g`. After interning, every op stored in a group has
  all-`Ref` relational children.

- **Scalar fields aren't groups.** They're plain `expr` values,
  ridden along as opaque payloads. If we ever want scalar rewriting,
  the scalar IR would grow its own MEMO-style representation;
  orthogonal to v1.

Want to lock these into a design doc next, or keep poking at edges
(variadic `Union.inputs`, the `Plan.of` lift, scalar rewriting)?

---

## User

In (a) a scenario is that the user can invoke optimization similarly
to JIT compilation. The user defines a function value (e.g. via
'fun') that contains one or more relational expressions. It may be a
closure (e.g. bind values from the environment at the time the
function is defined), and it has a type. I would like to invoke a
planner on that function and return a new value with the same type
(i.e. still a function) and binding the same values (if the values
are still needed post-optimization) that has a better plan. (This is
not the ONLY interface to the planner - clearly there are lower-level
ones that receive and return Core expressions directly - but it is a
motivating case for users who are working with data.)

## Assistant

Got it. That's a really compelling user surface — the planner as a
runtime function-to-function transformation. Here's a sketch.

### JIT-style invocation

The user writes ordinary morel:

```sml
(*) Captures `emps` and `depts` from the enclosing environment. *)
fun salaryReport threshold =
  from e in emps, d in depts
    where e.sal > threshold
    andalso e.deptno = d.deptno
    yield {name = e.name, dept = d.dname}

(*) salaryReport : real -> {name:string, dept:string} list *)

(*) Optimize. Same type, same closure, better internal plan. *)
val salaryReport' = Plan.optimize pushdown salaryReport

(*) Indistinguishable from the original at the call site: *)
val rows = salaryReport' 50000.0
```

The signature on the planner-facing function is just:

```sml
val optimize : program -> ('a -> 'b) -> ('a -> 'b)
```

(or `('a -> 'b) -> ('a -> 'b)` with an implicit default program).

### What the planner does mechanically

A function value carries three things the planner cares about:

1. **Its Core body** — the typed expression tree, retained on the
   closure (today this is mostly a compile-time artifact; for JIT it
   has to live on the runtime closure too).
2. **Its captured environment** — a name → value map: `emps`,
   `depts`, anything else free in the body.
3. **Its parameter list** — `threshold` here, which stays *symbolic*
   through planning.

Optimization is:

```
1. Walk the Core body; find relational sub-expressions (anything
   whose type is "relation of …" and which is built from Filter/
   Project/Join/Union/Scan, possibly with embedded scalar Core).
2. For each relational sub-expression:
     a. Lift it into the planner's op tree (Plan.of, but applied to
        the Core sub-expression rather than to a surface `from`).
     b. Resolve free relational names against the closure: a
        `Var "emps"` whose closure value is a relation becomes a
        Scan or a direct reference to that bound value, *not* a
        folded literal. The captured *value* informs decisions (its
        size, indexes, …); the *binding* survives.
     c. Run Plan.optimize on the op tree using `program`.
     d. Lower the optimized op tree back into Core.
3. Splice the optimized sub-trees back into the function body in
   place of the originals.
4. Rebuild a closure with the new body and the original captures.
```

### What this means for the rest of the design

A few constraints fall out:

- **Closures retain Core.** The function value has to keep the typed
  Core body around, not just compiled `Code`. Either always (cheap
  retention cost), or opt-in via a `Plan.fn` form, or only on
  functions that contain relational sub-expressions. Always-retain
  is the easiest story for users; the cost is a few hundred bytes
  per function value.

- **Closure captures are first-class planner inputs.** The planner
  can read `emps` at planning time (size, schema, indexes) without
  inlining its rows. This is the prepared-statement distinction:
  *captured* values inform planning; *parameter* values stay
  symbolic. We need a clear story about which is which — most
  obvious rule is "anything in the closure is fair game for
  planning-time inspection; anything in the parameter list is
  symbolic." Statistics on captured relations are how cost-based
  rules eventually earn their keep.

- **Type preservation is non-trivial in the general case.** For the
  rules we've been sketching (filter pushdown, filter merging) the
  output row type is invariant under rewrite. For projection-pruning
  rules the *intermediate* row types change but the final yield's
  type doesn't. The planner has to enforce "same external function
  type" as a postcondition; rules that violate it (e.g. a rewrite
  that drops a yield field) must be rejected or guarded.

- **Re-optimization is fine.** Because the optimized closure also
  retains its (now-rewritten) Core, you can pipe it back through
  `Plan.optimize` with another program and it just works. This
  matters for phased optimization:
  `f |> optimize phase1 |> optimize phase2`.

- **The planner is one of several entry points.** Lower-level
  callers (the morel compiler itself, tooling, tests) can call
  `Plan.optimize : program -> op -> op` on a bare op tree; the JIT
  surface above is a wrapper around that plus closure surgery. Both
  should exist; the function-to-function form is the user-facing
  motivator.

### One open question this exposes

When the function body contains *multiple* independent relational
sub-expressions — e.g.

```sml
fun report () =
  let val a = from e in emps where … in
      let val b = from d in depts where … in (a, b) end
  end
```

— does the planner optimize each independently, or build one MEMO
that spans both (allowing rules that share work across them)? My
lean: independently in v1, with a cross-tree MEMO mode as a future
flag. The independent story has clean semantics and matches what
users intuitively expect; cross-tree adds a lot of machinery for
gains that are mostly visible in CTE-heavy queries.

---

## User

Regarding "Closures retain Core". That's too high a price for all
function values. Function values created via the shell should opt in
(using Plan.fn) but the shell will hide the wrapper. (Is this similar
to how the shell remembers the expression of bindings for inlining
purposes?)

## Assistant

Yes — and the existing mechanism is even simpler than a wrapper.

### What Morel already does for inlining

Top-level `val` and `fun` declarations of the form `val name = expr`
(`IdPat`-shaped) already retain their Core expression on the binding.
The data structure is just:

```
Binding {
  exp   : Core.Exp   (* the typed Core expression that defined the binding *)
  value : Object      (* the runtime value *)
  ...
}
```

`Compiler.java:2125` populates both `exp` and `value`; `Inliner.java`
reads `binding.exp` and substitutes when it's safe to do so. It's
not a wrapper — there's no extra closure, no thicker representation.
It's just a pointer alongside the value, free in cost terms.

The retention is **selective**:

- `IdPat` (`val name = …`, `fun name …`) → `exp` retained.
- Compound destructuring patterns (`val (a, b) = …`) → `exp = null`.
- Type aliases, structure-internal bindings → not in the inliner's
  scope.

So the shell isn't doing anything special; the *compilation
pipeline* threads Core through to the Binding for the patterns
where it's unambiguous. The shell simply benefits because top-level
declarations live in the environment.

### How `Plan.fn` / `Plan.optimize` should relate to this

The shell case wants to ride this for free:

- Top-level `fun salaryReport …` → already has `Binding.exp` retained.
- `Plan.optimize` resolves a function reference back to its binding,
  reads `binding.exp`, runs the planner on that Core, and rebinds.
- No wrapper visible to the user; no opt-in needed at the shell
  level.

The non-shell case (anonymous functions, library code, function
values handed across module boundaries) needs explicit opt-in
because there's no binding to reach back to:

```sml
val planned = Plan.fn (fn x => from e in emps where e.sal > x yield e)
```

`Plan.fn` here has to be a syntactic form recognized by the
compiler — it needs access to its argument's Core, which an ordinary
function call can't observe. Mechanically, it's the same
pointer-retention idea as `Binding.exp`, just attached to a closure
value instead of a binding:

```
PlannableClosure {
  exp     : Core.Exp     (* retained body, parameters, captures-shape *)
  closure : Closure       (* the runtime function *)
}
```

This thick form only exists when explicitly constructed by `Plan.fn`.
Ordinary closures stay slim. The shell desugars top-level relational
`fun` declarations to this form transparently — but, importantly,
it can do so by reusing the *binding's* `exp` field (which already
exists), so even that desugaring is mostly bookkeeping rather than a
new representation.

### So to answer your question directly

Yes, this is the same idea as inliner-retention, but the existing
mechanism is one hop simpler: there's no wrapper, just a Core
pointer next to the value on the Binding. We can lean on it for the
shell path so `Plan.fn` becomes invisible there, and reserve the
explicit `Plan.fn` form for cases where the binding doesn't exist
(anonymous functions, library closures, anything not top-level).

The cost story therefore is:

- **Shell top-level `fun`s**: zero new cost — Core was already going
  to be retained for the inliner.
- **`Plan.fn`-wrapped values**: opt-in, carry an extra `Core.Exp`
  pointer.
- **All other functions**: slim as today.

One thing worth pinning down: when an inline `fn` is *not*
`Plan.fn`-wrapped but appears inside a `Plan.fn`-wrapped outer
function, the inner `fn`'s Core comes along for free (it's part of
the outer's Core body). So the opt-in granularity is "the outermost
function value the planner is going to be invoked on" — nested
anonymous functions in its body don't each need to opt in.

---

## User

Regarding type preservation. In Calcite, all rewrite rules must
preserve types. There are other rewrites (e.g. field pruning) that
are not packaged as rules, because of that constraint. I guess we
could have a 'prune project' rule that prunes its input's fields but
preserves the output type of the Project it was invoked on.

## Assistant

Yes — and the framing is sharp because it elevates `Project` from
"transform a row" to "type checkpoint." Below a Project, the row
type can wiggle freely as long as the rule restores it; above the
Project, the type is stable by construction. Calcite has to live
without that anchor (which is why `RelFieldTrimmer` is a pass, not a
rule), but a Morel/Calcite hybrid where Project is a privileged
operator gets it for free.

### One rule, two shapes

**`pruneProjectInput`** — insert a narrowing inner Project below an
existing one, dropping fields the outer expression doesn't read:

```sml
rule pruneProjectInput
    (Project {input, expression})
    when not (allInputFieldsUsed (input, expression))
  = let
      val needed = fieldsReferencedBy expression
      val pruned = Project {
        input      = input,
        expression = identityRecordOver needed   (*) fn r => {f = r.f, …} *)
      }
    in
      [Project {input = pruned, expression = expression}]
    end
```

The outer Project's output type is unchanged — its `expression` only
ever read `needed`, and the inner Project preserves exactly those
fields. Pure type-preserving rewrite.

This rule alone does no useful work; it just hoists in a stub
Project. The follow-ups are what move the savings down:

```sml
(*) Fuse a narrowing Project directly into a Scan. Type preserved
(*) because Scan-with-fields produces exactly the projected record type.
rule projectScanFuse
    (Project {input = Scan name, expression})
    when isIdentityFieldProjection expression
  = [Scan' {name = name, fields = projectedFields expression}]

(*) Push a narrowing Project past a Filter (the filter's condition
(*) might reference more fields, so we union the needed sets).
rule pushProjectPastFilter
    (Project {input = Filter {input = inner, condition = cond}, expression})
    when isIdentityFieldProjection expression
       andalso not (filterReadsExtraFields (cond, expression))
  = [Filter {input = Project {input = inner, expression = expression},
             condition = cond}]
```

Each of these stays type-preserving because the rule's match-root is
a Project (the anchor), and the rewrite either keeps the Project's
output type fixed or fuses it into something that produces the same
row type.

### What this implies for the IR

- **Project becomes a structural type-stabilizer.** The planner
  relies on Projects existing at the boundaries where pruning
  anchors. Translating morel surface — which always has an explicit
  or implicit yield — naturally produces a top-level Project, so
  users get the anchor for free. We may want a normalization pass
  that ensures a Project exists at the outermost level if one isn't
  already there, just so JIT-style optimization always has somewhere
  to anchor.

- **Termination is the new gotcha.** `pruneProjectInput` is
  idempotent only if the guard `not (allInputFieldsUsed …)` is
  checked carefully — if the rule fires once and inserts an inner
  identity Project that uses *all* the same fields as the original
  input, the guard prevents re-firing. But if the guard's "all
  fields used" check disagrees with itself across versions of the
  plan (e.g., aliases the schema differently), you get a loop.
  Worth landing the guard in shared helpers and treating it as part
  of the IR contract.

- **Cost in the rule system is the same as elsewhere.** No rule has
  to know about field pruning specifically; the planner just sees a
  few more rules and a few more equivalents in the MEMO. Cost-based
  selection between "fewer columns scanned" and "no extra Project
  wrapper" falls out naturally once we have a cost model.

So I'd say: agree with your framing — package field pruning as
Project-anchored rules. The price is committing to "Project is
special in the IR" as a design principle, but that's a price worth
paying.

---

## User

i'm not sure that Project is that special. however we do need to
support cases like "from e in emps order e.sal yield {e.ename,
e.deptno}" where a query is sorted by a field that has been removed -
and that guarantee must be preserved through optimization.

## Assistant

Fair — drop the "type checkpoint" framing for Project. The real
concern is narrower: certain operators carry semantic obligations
beyond the row type, and the optimizer has to respect them. `Order`
is the canonical example.

### What's going on in your example

Surface:

```sml
from e in emps order e.sal yield {e.ename, e.deptno}
```

Natural plan (Order below Project):

```
Project {
  expression = fn e => {ename = e.ename, deptno = e.deptno},
  input = Order {
    by    = fn e => e.sal,
    input = Scan "emps"
  }
}
```

Output type is `list {ename, deptno}`. The order-by-salary guarantee
lives in the *Order* node and survives because the Project above it
is a row-by-row map (which preserves list order). The output is
ordered by a field the user can no longer see. That's the
user-visible contract that must be preserved through optimization.

### What can break it

A naive `pushProjectIntoScan` would rewrite to

```
Order {
  by    = fn e => e.sal,                  (*) … but `sal` was just pruned *)
  input = Scan' {emps, fields = [ename, deptno]}
}
```

which is malformed: the Order's `by` references a field that the new
Scan doesn't produce. The rule has to know that it's punching
through an Order whose key contains a field the Project would drop.

The rewrite *is* still possible — just with a wider inner projection
that keeps the sort key alive:

```
Project {
  expression = fn e => {ename = e.ename, deptno = e.deptno},   (*) outer, narrow *)
  input = Order {
    by    = fn e => e.sal,
    input = Scan' {emps, fields = [ename, deptno, sal]}        (*) keep sal *)
  }
}
```

Same external type. Smaller scan. Order key still present.

### What this implies for the rule system

- **Each rule that interacts with Order carries a guard** that says
  "the fields this Order's `by` reads must remain available after my
  rewrite." Mechanically, that's
  `requiredFields order ⊆ producedFields newInput`. Same shape as
  the disjointness guards we already use for filter pushdown.

- **Morel's bag/list type already encodes part of the obligation.**
  `Order : bag 'a -> list 'a` (or `list 'a -> list 'a`); `Filter`,
  `Project` overload to preserve list-ness. So "this output is
  ordered" is in the type system. What the type system *doesn't*
  encode is *by what* — the sort key. That's the metadata the guard
  has to consult.

- **It's a per-operator concern, not a global IR shift.** Order is
  the obvious example today. `Group` (key fields), `Distinct` (set
  semantics), and physical traits like distribution will eventually
  bring their own constraints. Each gets handled by guards on the
  rules that move things around it. No need for a Project-anchored
  discipline; just rule-level care.

So the rule of thumb I'd use going forward: "rule preserves type" is
a hard requirement, but "rule respects all latent guarantees of the
operators it crosses" is the broader (and stricter) discipline.
Order, Group, Distinct each have their own guarantee that the rule
writer has to respect via guards. The reactor doesn't have to know
about them; the rules do.

---

## User

note that morel-java has a RelRoot class to solve this problem

## Assistant

(Note: I confirmed via search that RelRoot is a Calcite class —
`org.apache.calcite.rel.RelRoot` — not present in morel-java itself.)

That's exactly the right reference. RelRoot is the cleaner answer to
what I was groping at with "Project as type anchor" — the anchor
lives *outside* the optimized tree, not inside it.

### What RelRoot carries

From Calcite's `org.apache.calcite.rel.RelRoot`:

| field | role |
|---|---|
| `rel` | the relational tree (what the optimizer rewrites) |
| `validatedRowType` | the user's expected output row type |
| `fields : List<(Integer, String)>` | which columns of `rel`'s output to expose, and under what names |
| `collation : RelCollation` | the required output ordering, expressed as references into `rel`'s row — *which may include fields not exposed by `fields`* |
| `kind`, `hints` | SQL-level metadata |

The key trick: `collation` references `rel`'s columns by position,
*not* by what the user sees. So a query "sorted by sal, yielding
{ename, deptno}" becomes:

```
RelRoot {
  rel        = Sort(by = $sal) (Scan emps)        (* or any equivalent *)
  fields     = [(0, "ename"), (1, "deptno")]      (* expose two columns *)
  collation  = [($sal, ASC)]                       (* ordered by sal *)
}
```

The optimizer is free to rewrite `rel` however it likes — push
projections, fuse sort-into-scan, swap join order — provided that
whatever `rel` it produces still has `sal` reachable for the
collation invariant and produces (at minimum) the columns that
`fields` asks for. The user's contract is enforced at the root by
`fields` + `collation`, not by any inner Project.

### What this means for the Morel design

- **The planner's input is a `RelRoot`-shaped value, not just an
  `op`.** Roughly:

  ```sml
  type relRoot = {
    rel       : op,
    fields    : (int * string) list,      (* column mask exposed to caller *)
    collation : (int * dir) list,          (* required ordering, by column index of rel *)
    rowType   : type                       (* user-visible row type *)
  }
  ```

- **`Plan.optimize : program -> relRoot -> relRoot`.** The inner
  `rel` changes; `fields`, `collation`, `rowType` are invariants the
  optimizer must preserve.

- **No special "Project as anchor" rule.** Project is just another
  operator. The constraints that I was trying to put on Project's
  behavior live up at RelRoot instead, where they belong.

- **Field-pruning is now naturally legal.** A pruning rule that
  drops a column from `rel`'s output is sound iff the dropped column
  isn't named by `fields` and isn't referenced by `collation`.
  That's a single guard on the rule, checked once at the boundary,
  with no need to thread Project around.

- **The user's example becomes straightforward.** The relational
  tree under the RelRoot can have Order at any position (including
  fused into a Scan that supports ordered reads), and Project can
  come and go freely, as long as `sal` survives somewhere accessible
  to whatever realizes the collation. The fact that `sal` isn't in
  the user's output is just a `fields` decision at the root —
  invisible to the rules.

- **Ties back to the JIT story.** `Plan.fn`'s thick closure carries
  the function's Core; for a function whose body is a relational
  expression, the planner conceptually wraps that body in a RelRoot
  (the function's declared return type provides `rowType`/`fields`;
  explicit `order` clauses in the surface syntax provide
  `collation`). Optimization runs on the RelRoot. The function value
  comes back with the same external type, the same captures, and a
  rewritten inner `rel`.

---

## User

I wouldn't use field ordinals. I am trying to drop the assumption
that every relational expression returns flat records. Our equivalent
of RelRoot would be just an expression - similar to what is in yield
- that massages input elements to output elements. Maybe it drops 3
out of 7 fields. Or maybe it does something less record-oriented.

## Assistant

Right — that's a much better fit for Morel. Drop the field ordinals;
the boundary is just an ordinary Morel expression.

### Revised PlanRoot

```sml
type planRoot = {
  rel   : op,        (*) the planner-managed relational tree, of type 'a bag/list *)
  yield : expr       (*) a Morel function 'a -> 'b that produces user-visible elements *)
}
```

That's it. `yield` is whatever the user wrote (or whatever the
surface translator put there): a record projection, a tuple shuffle,
a function call, anything. The planner treats it as opaque — `Leaf`
from the rule-pattern perspective.

For your example:

```sml
{
  rel   = Order {by = fn e => e.sal, input = Scan "emps"},
  yield = fn e => {ename = e.ename, deptno = e.deptno}
}
```

The result type is `(type of yield's range) list` — the list-ness
comes from `rel` having an Order, the row type from `yield`'s
codomain. No ordinals, no field mask.

### Where the user's order-preservation guarantee lives now

In `rel` itself. The `Order` operator is the order producer; `yield`
is a row-by-row map (count- and order-preserving by construction);
the user-visible output is sorted because `rel` is sorted, period.

Rules then have a simpler rule of thumb: most operators (Filter,
Project, scalar Map) preserve the order of their input. Order is
the only operator that creates an order. A rule that adds, removes,
or moves an Order has to justify it locally (e.g., "two adjacent
Orders collapse — outer wins"). No collation metadata at the root,
no `RelCollation`-style trait — just operator semantics.

The cost: the planner can't remove a "useless" Order whose
downstream consumer doesn't care about order, because there's no
top-level "consumer doesn't care" signal. Calcite gets that signal
from `RelRoot.collation = empty`. We could add an `ordered : bool`
(or `requiredOrder : expr option`) field to `planRoot` later if it
turns out we're leaving optimizations on the table — but it's not
needed for v1.

### Field-pruning under this design

Two distinct paths, neither of them special:

1. **Internal Project nodes** — anywhere an explicit
   `Project { input, expression }` exists in `rel`, the
   type-preserving pruning rule we sketched earlier still applies.
   Project's output type stays fixed; its input gets narrowed by an
   inserted Project below.

2. **The yield boundary** — analyzing `yield`'s expression to see
   which parts of its input it actually reads, then narrowing the
   top of `rel` and rewriting `yield` to consume the narrower type.
   This is a *coordinated* rewrite of two PlanRoot fields, not a
   single-node rule. It's the natural Morel analog of Calcite's
   `RelFieldTrimmer`. Probably best as a separate pass that runs
   before or after the rule-driven optimizer, rather than as a
   rule.

For record-typed `rel` outputs, "which fields does `yield` read" is
a straightforward walk over the expression. For non-record outputs,
there's nothing to prune — the input element is consumed as a whole
or it isn't, and you optimize differently.

### What stays opaque

`yield` is a `Leaf` to the rule system. Rules pattern-match on
`rel`, never on `yield`. If we ever want scalar-expression rewriting
(constant folding inside `yield`, common-subexpression-elimination
across multiple `yield`s in a query), it's a parallel optimizer over
a separate IR, not the relational rule system. Keeps the relational
rules small and well-typed.

---

## User

let's review patterns again. a pattern matches one expression in a
matched sub-graph, identifying its position in that subgraph. it has
a predicate specifying first type, and second other conditions. can
the predicate reference other patterns? remember that patterns can be
matched in any other - top-down, or bottom-up, or up one input to the
root, and back down to other inputs. and we want the reactor to apply
predicates as soon as they become applicable.

## Assistant

Yes, predicates should be able to reference any combination of
patterns. And since matching can arrive at the patterns in any
order, the reactor needs to evaluate each predicate the moment all
of its referenced patterns are bound — earlier than that, it has
nothing to check; later than that, you've wasted work.

That moves the rule structure away from "one tree-shaped pattern +
one final guard" toward "a set of patterns + a set of guards, each
guard with explicit dependencies on patterns."

### The shape of a rule, internally

```sml
type pat_id = string                (*) "f", "j", "c", … *)
type field   = string               (*) "input", "condition", "left", … *)

(*) A pattern: one node in the matched subgraph. *)
type pat = {
  id      : pat_id,                 (*) name used by guards *)
  con     : con option,             (*) constructor it must have, if any *)
                                    (*) (None for a generic position) *)
  joins   : (field * pat_id) list   (*) field-equalities to other patterns *)
}

(*) A guard: a predicate that consumes some patterns' bindings. *)
type guard = {
  uses : pat_id list,               (*) which patterns this guard reads *)
  test : env -> bool                (*) the predicate body *)
}

(*) The whole rule. *)
type rule = {
  name     : string,
  patterns : pat list,              (*) one entry per matched node *)
  guards   : guard list,            (*) zero or more, each with its uses *)
  apply    : env -> op list         (*) given all bindings, produce equivalents *)
}
```

`env` here is the partial-match environment — a `pat_id` → bound
value map, where bound values are ops (or refs into the MEMO) for
patterns and `expr` values for scalar bindings.

`filterJoinTranspose` desugars to:

```
patterns:
  f : Filter
  j : Join,    joined: f.input = j
  c : <any>,   joined: f.condition = c     (* scalar binding *)
  jc: <any>,   joined: j.condition = jc    (* scalar binding *)
  l : <any>,   joined: j.left = l          (* group ref binding *)
  r : <any>,   joined: j.right = r         (* group ref binding *)

guards:
  (* none — the rule's "false positive ok" body decides per-conjunct
     whether to push, so there's no upfront guard. *)

apply: env -> op list
```

For a rule that *does* have a structural guard like
`refsOnly(c, l)`, that guard would list `uses = [c, l]` and fire as
soon as both `c` and `l` are bound — regardless of whether the
reactor reached `f`, `j`, `l`, `c` top-down or bottom-up.

### What the reactor does with this

- **Index by pattern.** Each pattern subscribes to its constructor
  (and its position relative to others). When a new op enters the
  MEMO, the reactor finds patterns it could fill and tries to extend
  the partial match.

- **Maintain partial matches.** A partial match is `(rule, env)`
  where `env` covers some subset of the rule's patterns. As more
  patterns get bound, the env grows.

- **Fire each guard the moment its `uses` are bound.** Maintain a
  guard worklist keyed on missing patterns. When pattern `p` gets
  bound, look up guards waiting on `p`; for each, if `p` was the
  last missing one, run the guard. False kills the partial match
  immediately; true marks the guard as discharged.

- **Fire `apply` only after all patterns are bound and all guards
  have passed.** That's the same point Cascades reaches; the
  difference is we got there faster on bad matches.

This is essentially incremental conjunctive-query evaluation —
patterns are atoms, guards are predicates, the reactor is a
fail-fast worklist join. The Cascades operand tree is one
specialization where patterns are arranged in a tree and the only
join structure is parent/child.

### Surface vs. internal form

The tree-shaped surface (`Filter {input = Join {…}, condition = c}`
+ `when refsOnly (c, left)`) is convenient and most rules will use
it; it desugars uniquely to the flat representation above. Users who
want the freedom to write rules whose patterns aren't tree-shaped —
e.g., a rule that needs *two* sibling Filters at unspecified
positions — can drop down to the flat form directly. (Calcite's
`RelOptRuleOperand` is tree-only; this gives us a slight super-set
without making the common case any harder.)

### One thing this clarifies

Because guards are independently named and dependency-tracked, two
guards in the same rule that depend on disjoint pattern subsets fire
independently. That falls out for free, but it's worth stating: a
rule with cheap guards on partial bindings and an expensive guard on
the full match will behave better than a single combined guard,
because the cheap ones can prune before the full match completes.

---

## User

Yes. And generate a structure containing all the types and functions
to be added to Morel's standard library, and a concise (one
sentence) description of each type and function. (Not the internal
implementation of those functions.)

## Assistant

(Structure proposed in chat — see the conversation summary; the
candidate signature is also the response to this turn.)
