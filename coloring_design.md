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
# Design: coloring a relational tree for SQL and Spark

Status: **issues, with proposed resolutions.** §§1--11 write the
questions down while what prompted them is fresh. §12 answers them in
the order §11 asks, and §13 works out the one that turned out to
change the shape of the rest: Spark can call back into Morel, and a
JDBC database cannot. Nothing in §§12--13 is built; each is a
position to argue with, not a decision.

## 1. What happens today

`CalciteCompiler` walks a `Core.From` and builds a Calcite `RelNode`.
Where it reaches an expression it cannot translate, `morelScalar`
replaces it with a call to a special Calcite operator whose arguments
are the Morel source of the expression, as text, and its type, as
JSON. `morelApply` does the same for an application. At run time
Calcite calls back into Morel, parses the text and evaluates it.

So the split exists, but three things about it are worth naming,
because the tree changes all of them.

* **It is implicit.** Nothing decides where the boundary is. The
  translator tries, and the boundary is wherever it failed. There is
  no colored plan, only a Calcite plan with Morel strings in it.
* **It is fused with generation.** The same walk decides feasibility
  and emits `RelNode`s. A second backend cannot reuse the decision,
  only the idea.
* **It is one-way and in-process.** A Morel fragment inside a Calcite
  plan works because Calcite runs in the same JVM as the Morel
  interpreter. Nothing is shipped anywhere.

`Prop.HYBRID` turns the attempt on and off, and an `aggressive` flag
threaded through `toRel` controls how hard it tries -- the only
present trace of the idea that pushing more down is not always
better.

## 2. What coloring would be

Given a tree, assign every node -- and every part of a node's
expressions -- to an engine: this backend, or Morel. The result is a
tree in which the boundaries are explicit, so that a generator for any
backend can be handed a subtree and told "emit this", and Morel
evaluates what is left.

The word is doing more work than two colors: SQL, Spark and Morel are
three, and a query that reads a JDBC table and a Spark table is a
fourth problem (federation) that this design should say whether it is
in scope.

## 3. Issue: how is a colored tree represented?

Three shapes, and the choice decides how much else is possible.

* **A field on each node.** Smallest change, and wrong for the same
  reason a color is not a property of a node: the interesting thing is
  a *boundary*, and a boundary lies between nodes, not on one.
* **A boundary constructor.** A node, say `foreign [engine] r`, whose
  input is the subtree that engine runs. The tree stays a tree, the
  boundary has a place in the plan text, and the validator can check
  the rules that ought to hold at one.
* **A map from node to engine, beside the tree.** Invisible in plan
  text, not serializable, and therefore not a contract. Rejected
  unless something forces it.

The boundary constructor looks right, and it has a consequence worth
stating early: **boundaries nest both ways**. A Morel expression
inside a SQL subtree is today's `morelScalar`; under this design it is
the same construct pointing the other way. So the tree alternates, and
the representation must allow a Morel fragment inside a foreign
subtree inside a Morel plan.

If the boundary is a constructor then it is in the plan text, which is
frozen for three implementations. That is a change to the contract,
and the moment to make it is before morel-rust and morel-go implement
the printer, not after.

## 4. Issue: cleaving

A boundary does not always fall between nodes.

* `filter [a andalso b]`, where `a` can be pushed and `b` cannot,
  wants to become `filter [b] (filter [a] r)`.
* `project [{x = e1, y = e2}]`, where `e1` can and `e2` cannot, wants
  to become a projection above a projection, the lower one computing
  what the backend can.

Both are rewrites, and rewrites are what step 4's rule framework is
for. So one question is whether coloring is a pass that happens to
rewrite, or a set of rules that the driver runs like any other. The
second is tidier and means coloring inherits the validator, the
determinism and the testing that the framework has.

It also raises the reverse question: a rewrite that cleaves a filter
is only worth doing if something can then be pushed, so the rules are
*conditional on capability*. A rule framework that knows nothing about
backends has to be told, which is issue 6.

## 5. Issue: can coloring be written in Morel?

This is the question with the largest consequence, because the answer
decides whether coloring is written once or three times.

For it: Java, Rust and Go each need coloring, and coloring is
intricate. A Morel function is written once and runs everywhere,
and the rules are then data the same way the plan is.

Against it, or at least before it:

* **The tree must be a Morel value.** Today it is an internal Java
  datatype. Reifying it is issue #359, already step 6 of plan.md
  ("Plan.core reification as a view of the same datatype"). So
  coloring-in-Morel is not blocked on a decision but on a step, and
  the honest answer today may be "yes, after step 6".
* **Capabilities must be data too**, or the Morel function is
  parameterized by something that is not expressible. See issue 6.
* **Bootstrapping.** A Morel function that colors plans is itself
  compiled by Morel. There is no cycle -- it is not a query over a
  foreign source, so it needs no coloring -- but that should be
  checked rather than assumed.
* **Cost.** Coloring runs at compile time in the interpreter. Probably
  irrelevant; worth measuring once rather than arguing about.

An intermediate position: express the *rules* in Morel and keep the
driver in each implementation. That divides the work where the
duplication actually hurts.

## 6. Issue: division of labor, and what a capability is

Coloring needs to know what a backend can do; generation needs to know
how to say it. The clean split is that capability is data and syntax
is code. The difficulty is that capability is not a flat set.

* "Does this dialect have `mod`?" is a set membership question.
* "Can this dialect express this correlated subquery in this
  position?" is not. Capability is contextual, and a model that
  pretends otherwise will be wrong in one direction or the other --
  refusing what would have worked, or proposing what the generator
  cannot emit.

Two ways out, and they are not exclusive:

* **Coloring proposes, generation disposes.** The generator may reject
  a colored subtree and coloring retries with that node forbidden.
  Robust, and it makes the capability model an optimization rather
  than a correctness requirement. But it is a negotiation, and the
  result must still be deterministic, because plan text is a contract.
* **Capability tables per backend**, rich enough to be contextual, and
  validated against the generator by a test that asserts the generator
  accepts everything the table permits. That test is the thing that
  keeps the two honest.

Spark and SQL differ enough that this is not one model. Spark has a
DataFrame API and a SQL dialect; which is the target changes what is
expressible.

## 7. Issue: the escape hatch, and why Spark is not Calcite

Today a Morel fragment inside a Calcite plan is evaluated by calling
back into the Morel interpreter **in the same process**. That is why
the hatch is cheap, and it is the assumption that does not survive.

Spark executors are elsewhere. A Morel fragment inside a Spark plan
must either

* be shipped to the executor and run by a Morel runtime there, which
  means a Morel runtime on the executor and a way to serialize a
  closure and its environment; or
* not exist -- Spark subtrees must be wholly translatable, and a
  fragment that is not forces the boundary further up, possibly to the
  root.

The second is much less work and much less useful. Which it is decides
how aggressive Spark coloring can be, and it should be decided early
because it changes what the capability model is for.

There is a related, smaller question: today the hatch carries Morel
*source text*, re-parsed at run time. Now that plan text is frozen and
specified, carrying a plan rather than source is defensible; carrying
neither, and sending a structured value, is better still.

## 8. Issue: how is coloring tested?

If the colored tree is printable -- which the boundary constructor of
issue 3 makes true -- then coloring is a function from a tree and a
capability description to a tree, and golden tests over plan text
work. They are fast, need no backend, and are shareable with
morel-rust and morel-go exactly as `rel-tree.smli` is.

That suggests a three-layer answer, and the question is where the
weight goes:

* **Golden coloring tests**, the bulk. One per constructor per
  interesting capability profile. The constructor set is thirteen, so
  this is enumerable rather than sampled.
* **A generator-agreement test** per backend: everything the
  capability table permits, the generator emits. This is what catches
  a table that lies.
* **End-to-end**, few, slow, and the only thing that catches a
  generator that emits something the backend rejects at run time.

The open question is whether the first layer can be *exhaustive* in
any useful sense. Thirteen constructors is small; the expressions
inside them are not, and cleaving is driven by expressions.

## 9. Issue: feasibility or cost?

Pushing everything possible into the backend is not always fastest --
a filter that removes nothing costs a round trip, and a join pushed
down may materialize something large. The `aggressive` flag is the
present acknowledgement of this.

A cost-based coloring needs statistics, which is a much larger scope
and a dependency on things Morel does not have. The recommendation to
argue about: feasibility first, with the boundary chosen by a fixed
rule ("push as much as possible"), and cost later, as a separate
input to the same representation.

## 10. Sequencing

Coloring depends on things that are not done.

* The **rule framework** (step 4), if cleaving is rules rather than a
  bespoke pass.
* **Plan reification** (#359, step 6), if coloring is written in
  Morel.
* The **tree executing** rather than being lowered, or coloring
  operates on something that is then thrown away.

It does not depend on the freeze, but it changes what is frozen: a
boundary constructor is a new node in spec.md's constructor set and a
new line in its plan-text grammar.

## 11. What to settle first

In the order the answers unblock each other:

1. Is the boundary a constructor in the tree? Everything else --
   plan text, golden tests, the validator's rules -- follows from it.
2. Is a Morel fragment inside a Spark plan possible at all? It decides
   whether Spark coloring is "push what you can" or "push all or
   nothing".
3. Is coloring rules-in-the-framework or a pass of its own?
4. Is coloring written in Morel, and if so is it gated on #359?
5. What is a capability, concretely, for one dialect, written out?


## 12. Proposed resolutions

Two facts, read off the code, underlie most of what follows.

*Coloring runs on the tree.* `Compiles` hands the tree to the
compiler, and `CalciteCompiler` reads the tree; there is no lowering.
Coloring the tree therefore runs on the thing the rewrite passes
carry, and each compiler learns one new node.

*There are two colorings today, not one.* `CalciteCompiler` decides
what reaches Calcite; Calcite's own planner then decides what reaches
JDBC and executes the rest in-process. The second is invisible in
`Sys.plan`, which prints the logical plan, and it is the only reason
a `morelScalar` inside a filter works at all: Calcite runs the filter
in the JVM, above a `JdbcTableScan`. morel-rust and morel-go will not
have it.

### 12.1 The boundary is a constructor, at any type

A wrapper, call it `run [engine] e` until a better name is argued
(not `at`: #401, measures, takes that word for something else, and
`on` and `in` are reserved),
where `e` is any expression and the wrapper's type is `e`'s type. It
is not a relational constructor: a switch of engine can happen at any
type, and coloring produces it at three.

* At a node, in either direction: `run [spark] r` is a subtree Spark
  runs, and inside that subtree `run [morel] r'` would be a subtree
  Morel runs, which §13.4 says is rarely worth it.
* At a scalar, inside a foreign node's expression: `run [morel] (f $0)`
  is today's `morelScalar`, the UDF of §13.1. Its type is whatever
  `f $0` returns.
* At a scalar, from Morel into an engine: a foreign function called
  on a Morel value. The datatype permits it and no rule proposes it.

Because a node is an expression (spec.md §1) one constructor covers
all three, and the alternation of §3 needs no second construct. But
the Java datatype cannot express it as one class: `Core.Rel`'s
constructor rejects a non-collection type, and nineteen places decide
node-ness by `instanceof Core.Rel`. So it is one `Op` and two classes
-- a `SingleRel` for the node form and an `Exp` for the rest -- with
one builder method that returns the node form exactly when its
argument is a node. A leaf wrapped in a boundary, `run [spark] xs`, is
the expression form: a leaf is one component whether or not it is
wrapped, and nothing below it needs seeing through. The node form
exists for the one thing only a node needs -- its components are its
input's (discussion.md §16), so that a boundary between two joins
leaves the element flat and a projection above it does not re-path.
§14 says what a rel is, and why that is the one thing.
For morel-rust and morel-go this is a variant on the expression enum
and a case in whatever the node walkers match on; the spec says
"constructor" and means the algebra, not the class.

The validator gains three rules at a boundary:

1. What crosses must be of a type the profile can carry: the
   expression's own type for the scalar form, the element type for
   the node form, and the types of the free variables of what is
   inside, which become parameters. A function, or a value containing
   one, cannot cross in any position.
2. A dependent join's binder may cross into a foreign right input only
   where the engine's profile says correlation is expressible in that
   position (§12.5).
3. A wrapper whose engine is its enclosing engine is dropped by the
   builder, as an unread binder is (spec.md §3.3), so that two trees
   that mean the same thing print the same.

This is an addition to the frozen spec: one constructor, one row in
§3.2 for the node form, one line in the grammar of §6.3, and a
sentence in §6.6 saying the expression form prints inline as any
application does. It changes no existing golden file, because no
current plan prints a boundary, and it fits the surface as it is:
`Sys.planEx` already takes a phase, so the colored tree is one more
phase; and §6.4's rule that a relation inside an expression is broken
out as `r$N` already covers a node-form boundary inside a filter's
condition. The engine name is contract text; if profile names carry a
dialect, as `sql:hsqldb` does, the grammar's `word` must admit the
colon. A field on each node is rejected for the reason §3 gives, and
for two more: a field cannot sit on a scalar, and cleaving is a
rewrite, which is testable through plan text where a field is not.

**Resolved, after a detour, as this section proposed, and the name is
`boundary`.** The detour is kept because it is what was thought and
because the measurement in it stands. First the boundary was to be a
function, `run "spark" e` of type `string -> 'a -> 'a`, on the
grounds that nothing requires a node's input to be a node -- which is
true, and is recorded below. Then: `filter` and `project` are not
functions either, they are kinds of Core expression, and a boundary
belongs in that family. So it is `Op.BOUNDARY` and `Core.Boundary
extends SingleRel`, and the name is `boundary`. Built: the node, the
builder, the validator's derived-type check, the compiler (which runs
the input, since no engine honors a boundary yet), the rule driver,
and the `BOUNDARY` constructor of the Morel view, so a rule written
in Morel can insert one. spec.md gains the §3.2 row and the §6.3
grammar line this section asks for, and no golden file changes
because nothing produces one.

**What was true in the detour.** The tree has no requirement that a
node's input be a node. `RelValidator.input` recurses where the input
happens to be a `Core.Rel` and otherwise asks only that its type be a
collection; `Core.Rel`'s constructor constrains only the node's own
type; §3.1 already says a leaf is just an expression, and the leaves
in the suite are applications like `#filter Bag (fn e => ...) (#emps
scott)`. So a call at a collection type would have been a legal input
wherever a node takes one. What decided against it is not legality
but family: a boundary is a way of forming a relational expression,
as `unorder` is, and components are the thing only a node gets. The
three validator rules above are still wanted, and are not built yet.

What the node form was to have bought is components, and that is real
code rather than spec prose: `CoreBuilder.componentCount` returns 1
for anything that is not a flat join or an element-preserving node,
and the join compiler, the grounder and the flat-query reader all ask
it. Measured, the difference is exactly what the argument said and
nothing worse. `from a in as, b in bs, c in cs` joins a join to a
leaf and its element is `(int * string * bool)`, three flat
components, with the projection above reading `#1 $0`, `#2 $0`, `#3
$0`. Wrap the inner join in an ordinary function and the element is
`({a:int, b:string} * bool)`: two components, the first a record, and
the projection above reads `#1 $0` for the pair. Both run, and both
give the right answers -- the builder knows how many components each
input has and builds the projection to match.

That is what the node form buys and a function would not. Because
`Core.Boundary` preserves its input's element, `componentCount` sees
through it, so a boundary beneath a join leaves the element flat and
nothing above re-paths. A rule may put one anywhere.

### 12.2 Coloring is a pure function of the tree and a profile

The three implementations have different generators: Java's is
Calcite's `RelToSql`, and the other two will be written by hand. If
generation could reject a coloring and force a retry, plan text would
depend on which generator an implementation has, and the contract
would break. So of §6's two ways out, only the second is available:
the capability description is an *under-approximation* that coloring
trusts absolutely, and a generator that refuses a colored subtree is
a bug, caught by the agreement test of §12.6, not a path the compiler
takes.

The description is called a *profile* below, because it describes a
deployment, not only an engine: whether the Morel runtime is installed
on Spark's executors (§13) changes what may be colored, and that is a
fact about a cluster. A profile has a name, the name is a property the
session sets, and plan text is a function of the tree and the name.

Cost stays out, as §9 recommends. The fixed rule is "push the largest
subtrees the profile permits", and the `aggressive` flag retires.

### 12.3 Coloring is a fold, and cleaving is the fold on a partly pushable node

The fold runs over expressions, then over nodes. An expression is
pushable if its operator is in the profile, its arguments are
pushable, and its free variables can cross. A node is pushable if its
expressions are pushable in their positions and its inputs are. A
node whose expression is partly pushable is cleaved:

* `filter [a andalso b]` becomes `filter [b] (filter [a] r)`, which
  is engine-agnostic normalization and can run before coloring.
* `project [{x = e1, y = e2}]` becomes an upper projection over a
  lower one, and the lower one emits a *tuple*, `(e1, $0)`, so that
  no label has to be invented: the upper reads `{x = #1 $0, y = e2'}`
  with `e2'` being `e2` over `#2 $0`. Tuples are why this is cheap
  (discussion.md §14).
* `group` cleaves the same way when some aggregates are native and
  some are not, and only if the profile can group by the same keys
  natively, which it always can if the keys are pushable.

Both rewrites preserve the root type, so the validator checks them,
and both are what §4 hoped: rules in the step-4 framework, with
guards that read the profile. The reverse merges -- two adjacent
filters into one, two projections into one -- are the same rules run
backwards, and belong to the same set.

### 12.4 Engines: Morel, a SQL dialect, Spark. Not in-process Calcite

In-process Calcite is not an engine the plan may name. It executes in
the same JVM, over Java collections, exactly as Morel does; its only
distinction was the optimizer, which is a planner over the tree, not
a place to run it. What Calcite keeps is a role in the Java
implementation only: `RelToSql` as the SQL generator, and the JDBC
adapter as the way a table is reached. The hatch it provided -- a
Morel fragment evaluated inside a Calcite plan -- goes with it.

That inverts §7. A JDBC database cannot call Morel, so `sql:<dialect>`
is the engine *without* a hatch: a subtree colored for it must be
wholly translatable, and the only things that cross are parameters.
Spark can call Morel (§13), so Spark is the engine *with* one. The
capability model is what says so, per profile, and coloring for the
two engines is the same fold with different profiles.

Without a hatch, "wholly translatable" is still far from all-or-
nothing. The pushed region is rooted at the leaves, which is where
scans, filters, joins and native aggregates live; what stays in Morel
is the top of the tree, and the Morel driver runs it over the rows
that come back.

### 12.5 The driver in Java, as rules; the profiles as data all three read

Writing the driver in Morel now has two unbuilt dependencies: the
rule framework (step 4) and reification (#359, step 6). So coloring
is the first rule set in the step-4 framework, written in Java, with
every guard a pure function of a node and a profile. That is the
"intermediate position" of §5, sharpened: the framework ports to Rust
and Go anyway (plan.md, "Milestones"), so the driver ports with it,
and the *profiles* are written once.

A profile is a Morel value. That is not a flourish: a Morel literal
is the one data format all three implementations already parse.
A sketch for Spark SQL, to make "capability, concretely" mean
something:

```sml
val spark = {
  nodes = ["filter", "project", "join", "group", "sort",
           "skip", "take", "union", "intersect", "except"],
  joinKinds = ["inner", "left", "right", "full"],
  subquery = {existsInFilter = true, inInFilter = true,
              scalarInProject = true, lateral = true},
  ops = ["+", "-", "*", "/", "div", "mod", "=", "<>", "<", "<=",
         ">", ">=", "andalso", "orelse", "not", "^", "String.size",
         "String.sub", "Int.abs", "Real.floor", ...],
  aggregates = ["sum", "count", "min", "max", "avg"],
  types = ["int", "real", "string", "bool", "record", "tuple",
           "list", "option"],
  hatch = {scalar = true, aggregate = true,
           perRowTable = true, relation = false}
}
```

Two things in that sketch answer §6's worry that capability is
contextual. `subquery` is keyed by *position*, because the fold knows
which node's expression it is in, so the predicate is
`can (profile, position, exp)` and the table lists positions rather
than pretending they do not matter. And `hatch` is keyed by the four
shapes of §13.2, because "can Morel run here" has four answers, not
one. `unorder` is absent because a relation is unordered in every
backend, so it is free; and `sort` is permitted only as the *root* of
a pushed subtree, since no backend preserves an order below a join.
The rule that falls out: the kind at a boundary must be `bag` unless
the boundary's root is a `sort`.

The bootstrapping check of §5 is a test, not an argument: a profile is
a value with no query in it, and the rule set is compiled by a
compiler with coloring disabled.

### 12.6 Tests, three layers, and where the weight goes

* **Golden coloring**, the bulk, through `Sys.planEx` at the colored
  phase, with the profile named by a property. Synthetic profiles are
  cheap and are the point: a dialect without `mod`, a Spark without
  the hatch. Per node constructor, three cases are enumerable --
  wholly pushable, not pushable, cleavable -- so the node layer is
  exhaustive in the useful sense. The expression layer is not, so it
  tests the *rules of the fold* on a handful of shapes: a pushable
  operator over an unpushable argument and the reverse, a free
  variable that can cross and one that cannot, a nested tree in each
  position `subquery` names. Shareable with morel-rust and morel-go
  as `rel-tree.smli` is.
* **Generator agreement**, one per backend: for every entry in a
  profile, build the smallest node or expression that exercises it
  and assert the generator emits it. This is the test that keeps a
  profile conservative, and the only test that involves Calcite.
* **End-to-end**, few. `hybrid.smli` already covers JDBC with the
  in-memory scott database. Spark has no target in CI, so its
  end-to-end layer is the emitted plan as golden text, and a manual
  run against a cluster when a profile changes.

### 12.7 The spike, from `449-tree.3`

The tag is HEAD minus this file, its compiled classes are the tag's
build, and `ScriptTest` is green on it (73 of 73), so a worktree on
the tag is the place to start.

The spike is a pass inserted before `lowerTrees`, producing
`run [sql] r` subtrees for the JDBC profile, with lowering turning each
into a leaf that `CalciteCompiler` translates whole. Its acceptance
test is crisp: after the spike, no `morelScalar` or `morelApply`
appears inside any Calcite plan in `hybrid.smli`; `morelTable` appears
only at leaves; every plan is identical with the pass on and off
except for the boundary lines; and the logical plan handed to Calcite
contains only operators the profile lists. If that holds without
touching the resolver, the surface is clean for planning work, which
is what the checkpoint was cut to find out.

The spike is a pass, which §4 argues against, and it is thrown away.
What survives is the shape: a list of local rewrites driven by a
trivial bottom-up loop, so that the step-4 driver adopts the rewrites
as its first rules rather than replacing them.

## 13. Spark calling back into Morel

§7 asked whether a Morel fragment inside a Spark plan is possible at
all. It is, through hooks Spark already has, and the answer is
different for each of the four places a fragment can sit.

### 13.1 What Spark offers

| Hook | Granularity | Reached from SQL text? | Needs |
| --- | --- | --- | --- |
| Scalar UDF (`UDF1`..`UDF22`, or a Hive `GenericUDF`) | one expression, per row | yes: `CREATE FUNCTION f AS 'class' USING JAR 'path'` | the jar on the executors |
| UDAF (Hive `GenericUDAF`, or `Aggregator`) | one aggregate, per group | yes, same syntax | the jar |
| Per-row table function (Hive `GenericUDTF`, via `LATERAL VIEW`; Python UDTF with `TABLE` arguments in 3.5+) | a relation per row | yes | the jar; the Python form needs a Python worker, so not for Morel |
| `mapPartitions` / `mapInArrow` (Dataset API; Connect `MapPartitions`) | a relation, per partition | no: DataFrame or Connect plan only | the jar, or a Python worker |
| Inline data (`LocalRelation` in the Connect plan, `createDataFrame`) | a leaf | no: plan only | nothing |
| Data Source V2 | a leaf, partitioned | as a table, once registered | the jar, server side |
| Catalyst extension: a custom operator via `spark.sql.extensions`, addressed over Connect through a relation plugin | a relation, with Spark choosing the partitioning | no: plan only | the jar *and* server configuration |

The first three are reachable from SQL text alone, need only the
Morel jar to be on the executors' classpath, and are enough for
every fragment that is row-local (§13.4). The last is the general
answer -- a `MorelExec` node with a `requiredChildDistribution`, so
that a fragment that needs all the rows of a key in one place says
so and Spark shuffles -- and it costs cluster configuration that a
client cannot do for itself.

Spark Connect has a Go client and a Rust client as well as the JVM
one, and its plan is a protobuf whose relations -- `Filter`,
`Project`, `Join`, `Aggregate`, `Sort`, `Limit`, `Offset`,
`SetOperation`, `Deduplicate`, `LocalRelation`, `MapPartitions` --
are nearly one-to-one with the tree's constructors, and whose
expressions include lambdas for the array higher-order functions. So
the generator target for Spark should be the Connect plan, not SQL
text; SQL text is one relation kind in it, and the plan is what can
carry a UDF reference, inline leaf data, and an extension node. A
golden test prints the plan's text form.

### 13.2 Leaves, interior subtrees, the root

*A leaf.* A leaf is a Morel expression of collection type, evaluated
by the driver. Small leaves ship inline as a `LocalRelation`, the
analog of a SQL `VALUES` list. A large leaf can be evaluated on the
executors only if the runtime there can evaluate it -- which for a
leaf that reads JDBC means the executors need the connection -- and
is otherwise the case where the boundary moves up and the join runs
in Morel. A leaf that is already a Spark table is not a Morel leaf at
all.

*An interior subtree.* Three shapes, after cleaving:

* a scalar in a native node's expression, which is a UDF;
* an aggregate in a native `group`, which is a UDAF;
* a dependent join whose right input is Morel, which is a per-row
  table function under `LATERAL VIEW`, and is the shape a correlated
  Morel subquery takes.

What is left is a whole-relation Morel node above a native subtree:
a `sort` by a Morel comparator, an `ifEmpty`, a `group` whose *keys*
are not pushable. Those need `mapPartitions` and a single partition,
or the extension operator; without one of those the boundary sits
below them. §13.4 says which is which.

*The root.* When Morel is the driver, the root is Morel for free: the
Spark subtree returns rows over Connect and Morel runs the rest.
"Spark calling Morel at the root" means the other direction -- Spark
is the driver and a Morel query is a table it reads, for a notebook
or a BI tool -- and that is a Data Source, or a per-row table
function with no table argument, running the Morel runtime on the
executors against Morel's own sources. It is a product in its own
right, out of scope here, and the same runtime serves it.

### 13.3 What crosses a boundary

Parameters cross as values, in both directions, and nothing else
does. A closure does not: an expression whose type mentions a function
cannot cross, and neither can a reference to a user-defined function
unless its definition travels with the fragment. The fragment format
should therefore be **plan text, plus what the text declares it
reads**. §6.4 already makes a fragment declare its free variables at
its head, and the transitive closure of the declarations a fragment
names is a finite list of Core declarations that ship beside it. The
executor parses the text, compiles once per fragment (a cache keyed
by the text), and evaluates per row or per partition. Source text,
which is what `morelScalar` carries today, is dropped for the reason
§7 gives: the plan text is frozen, parseable by design, and is the
form every implementation can produce.

Types cross under a mapping that is a profile entry (`types` in
§12.5): records and tuples to structs, lists to arrays, options to
nullable, `bag` and `list` both to a relation whose order is
unspecified. Functions, and anything containing them, do not cross;
that is the test in rule 1 of §12.1.

Order is the constraint that is easy to miss. A pushed subtree of kind
`list` has its order dropped by Spark unless its root is a `sort`, so
coloring may push a `list`-kinded subtree only when the boundary's
root is a `sort` or the enclosing node does not depend on order
(`unorder` above it, or a `group`).

### 13.4 Row-local fragments are parallel; the rest are not

A fragment applied per row or per partition is correct only if its
meaning does not depend on rows in other partitions. That is true of
a scalar, of an aggregate with a combine step, of a per-row table
function, and of `filter` and `project` whose expressions are those.
It is not true of `sort`, `ifEmpty`, `skip`, `take`, the set
operators, or a `group` whose keys the partitioning does not respect.

So the `hatch` entry of a Spark profile has four flags rather than
one, and a profile with the jar installed says `relation = false`
until the extension operator exists, since `mapPartitions` with a
single partition is serial and defeats the engine. After cleaving,
nearly every Morel-only thing in a real query is an expression, and
expressions are row-local, so the three cheap hooks cover the cases
that matter; the extension operator is the second tier, for the
day a whole-relation fragment is worth a shuffle.

### 13.5 The executor runtime is morel-java, whoever the driver is

A UDF body on a Spark executor is JVM code, so the runtime that
evaluates a fragment there is morel-java, even when the driver is
morel-rust or morel-go. That is why §13.3 insists the fragment format
be plan text rather than a closure: a Go driver ships text that a
Java executor reads, and the frozen contract is what makes that
sound. It also means the jar is a deliverable of this work in its own
right, with an entry point that takes a fragment and its parameters,
and no dependency on the driver's session.

### 13.6 What this settles in §11

1. The boundary is a constructor (§12.1).
2. A Morel fragment inside a Spark plan is possible, through UDF,
   UDAF and per-row table function for row-local fragments today,
   and a custom operator for the rest later. Inside a JDBC plan it
   is not, and the profile says which is which (§12.4, §13).
3. Coloring is rules in the framework (§12.3), spiked first as a
   pass with the shape of rules (§12.7).
4. The driver is Java until #359; the profiles are Morel values now
   (§12.5).
5. One profile is written out, for Spark, in §12.5.

## 14. What makes a rel a rel

The question came up deciding whether the boundary is a node, and it
is worth writing down because the answer says which of the tree's
properties are essential and which follow. A rel is an ordinary Core
expression plus four things.

**It binds positions, not names.** A node binds `$0` for its own
expressions, and a join binds `$1` too. The binder is implicit,
rebound at every node, and not a variable: `Core.Input.equals` ignores
type, and an expression moved to the wrong node is caught by the
validator, not by substitution (discussion.md §17). Every other Core
binder is a pattern reached by lexical scoping. This is the sharpest
difference, and it is why a nested tree inside an expression must
bind the outer element to a name before it can reach it (spec.md §2).

**Its element is made of components.** A join's element is its
inputs' components concatenated, flat. Filter, sort, skip, take and
unorder pass their input's components through; project, group and the
set operators start a new one (discussion.md §16). An ordinary
expression's value has no such structure. Components are what let a
rule commute or reassociate a join without retyping anything above
it.

**Its expressions run per element, or before the first one.** A node
is a dataflow: the inputs produce elements, the node's expressions
run once per element, and the arguments of `skip`, `take` and
`ifEmpty` run before any element exists (spec.md §2, rule 1).
The compiler turns that into row sinks. An ordinary
expression is evaluated once for its value, and its subexpressions
are evaluated because it needs them.

**Its type is a collection with a kind that is derived, not
observed.** `list` or `bag` is computed from the constructor and the
inputs' kinds by a fixed table (spec.md §4), and `sort` and `unorder`
exist to move between them. A leaf is also collection-typed, so this
quality is necessary and not sufficient.

The rest of what the code gives a rel follows from those four rather
than standing beside them: the closed algebra, which is what makes a
local rewrite type-preserving; the validator's checks beyond typing,
every one of which is about binding, components or kind; the rule
framework matching on nodes; the tree-mode printer, where a node owns
a line and a relation inside an expression is broken out; and the
frozen, finite constructor set, with leaves left open because a leaf
is just an expression.

Two things a rel is not. It is not "anything of collection type": a
leaf has the type and none of the four qualities, and the tree's edge
is wherever they stop (spec.md §3.1). And it is not defined by having
expressions: `unorder` has none and binds nothing. It is a rel for
the fourth quality alone, and for passing components through.

That is what settles §12.1. The node form of `run` is `unorder`'s kind
of rel -- it binds nothing and runs nothing -- and it is a node for
one reason, so that its components are its input's and a boundary
between two joins leaves the element flat. The scalar form has none
of the four qualities and is rightly a plain expression.

## 15. Chipping away: a rel as an application in normal form

§14 listed four qualities. Pushed on, three of them dissolve into
ordinary Core and one survives; the residue is the honest definition
of a rel, and the reading that exposes it is worth adopting.

### 15.1 The reading

A node is an application of a relational built-in whose function
arguments are manifest lambdas. `filter (r, fn $0 => c)` is the Core;
the printer, seeing a lambda whose parameter is `$0`, prints
`filter [c]`. Discussion.md §2 rejected lambdas for two reasons, and
both fall away here. Matching through a pattern is not a cost, since
`Core.Fn` takes an `IdPat` and never a pattern. And α-equivalent
spellings are not a second convention, since the printer already
renumbers `v$N` at print time (spec.md §6.7), and `$0` is the same
mechanism on one more prefix. The plan text does not change by a
character.

### 15.2 What each quality becomes

* **Binding positions** becomes lambda binding. `$0` is an `IdPat`
  with a fresh ordinal per node, bound by a lambda, suppressed by the
  printer. Discussion.md §17 then resolves the other way: `$0` is a
  variable, properly bound, so use counts and substitution are right
  by the ordinary rules. A rule that moves a condition through a
  projection does it by β-reduction, which is inliner machinery,
  instead of the substitution `RelExpander` carries for itself. A
  dependent join's binder is the right input's lambda parameter, and
  decorrelation is an unused parameter, which use counting finds.
* **Per-element evaluation** becomes the semantics of the built-in.
  `Bag.filter` already means that.
* **Kind derivation** becomes the built-in's type, overloaded on list
  and bag as `Bag.filter` and `List.filter` already are; `unorder` is
  `'a coll -> 'a bag`.
* **The scope rules** mostly stop being rules. `skip` and `take`
  cannot mention the element because they take no lambda; "no `$0`
  outside a node" is lexical scoping.

### 15.3 What survives: the join's flat components

`join (join (a, b), c)` has element `(τa, τb, τc)`, not
`((τa * τb) * τc)`, and no ML function type says that. Types alone
cannot recover it either: a leaf of pairs and a join of two leaves
have the same element type and different components. So components
stay a function over syntax -- a join concatenates, filter and sort
and the boundary pass through, everything else is one component --
and the checker carries that one rule for that one built-in. It is
the essential quality. Everything else about a rel is a normal form:
a dozen built-in names, lambdas whose parameter is `$0` or `($0, $1)`,
and a printer that elides them.

Two constructors are less tidy. `group`'s aggregate list is a small
language of its own; its lambda form is a reducer over the key and
the group's collection, and the elision there is a rule rather than
an omission. And the boundary becomes one class (§15.4).

### 15.4 `run` under this reading

`run` is a function, `run : engine * 'a -> 'a`, and denotationally the
identity: `run [x] e = e`. That single equation is what makes coloring
a semantics-preserving rewrite and lets the validator's root-type
rule apply to it unchanged. Its equational theory is small.
`run [x] (run [x] e) = run [x] e`, which is the builder's drop rule;
`run [morel] e = e` at the root, which is why the root is implicit;
and nothing else, so `run [x] (f (run [y] e))` is simply nested.

Operationally it is the one node that lowers to a *call* rather than
a step: generate `e` for engine `x`, treating every `run [y]` inside it
as opaque -- a leaf if collection-typed, a function call if scalar --
then run the result and marshal it back, with the free variables of
`e` as parameters. That is the shape `calcite(plan ...)` already has
in `Sys.plan`, made explicit and given a type.

Because it is a function at any type, one class serves, and §12.1's
two classes were the cost of the class hierarchy rather than of the
idea. Components pass through it by one line in the components
function, which is the only place a node-form boundary is treated
differently from a scalar one.

Two consequences worth naming. **A foreign table is already a
boundary.** `scott.emps` is a value whose home is an engine, and
coloring for that engine starts from such leaves and grows upward;
whether the resolver writes `run [sql:hsqldb] scott.emps` from the
binding or a first rule does is a detail, but the seed of coloring is
in the tree, not in a side table. **A user can write it.** If `run` is
a built-in, `run ("spark", from e in emps where p)` is a query with a
coloring hint, and coloring's job is then to insert the boundaries
the user did not write and to check the ones they did, by the rules
of §12.1. That is a possible feature, not a commitment.

### 15.5 What it costs, and when

The plan text and the golden files are untouched. The datatype
description in spec.md §§1 and 3 changes; the Java classes become
applications, or thin views over them; and the nineteen
`instanceof Rel` sites become a test for the normal form. That is a
coordinated change with morel-rust, which is the reason to decide it
before their datatype is built rather than after. The gain is the
one this document is for: rules in Morel match on applications,
#359's reification is the identity, and a user's
`Bag.filter (fn e => e.deptno = 20) emps` is the same Core as the
query, so tree mode prints it too.

## 16. What coloring has to do, measured

Written after steps 4 to 6 landed, because §12.7's spike was
specified before the rule driver existed and its acceptance test can
now be run against the code as it stands. The measurement is over
the whole script suite, which is a sample of what Morel compiles and
not a proof about every program.

**Where Morel appears inside a Calcite plan.** Three occurrences in
the suite, and one absence:

* `morelTable` twice, at `hybrid.smli` line 52 and `relational.smli`
  line 3431. Both are a `LogicalTableFunctionScan`, which is to say
  both are leaves: a `Bag.filter` over `scott.emps`, and a
  `List.tabulate`.
* `morelScalar` twice, both in the one plan at `hybrid.smli` line
  135: `ten`, and `#nth List (ten :: 20 :: 30 :: [40], 1)`. Neither
  reads the row. Both name only variables the enclosing `let` binds,
  so each is a constant for the query, delivered by a callback where
  a bind parameter would do.
* `morelApply` never appears.

**What reaches Calcite at all.** Of the eighteen hybrid-mode queries
in `hybrid.smli` that print a plan, fifteen reach Calcite. The three
that do not are the three the script's own comment at line 372
describes: an aggregate over a type Calcite has no SQL type for --
`word`, a tuple, an `option`.

**What this says about the spike.** §12.7's acceptance test is that
no `morelScalar` or `morelApply` appears inside a Calcite plan, that
`morelTable` appears only at leaves, and that the logical plan holds
only operators the profile lists. The first two hold today, before
any coloring: there is no row-dependent Morel inside a Calcite plan
anywhere in the suite. So the spike as specified would prove
nothing, and §12.7 is obsolete twice over -- once because the step-4
driver exists and coloring can be rules from the start rather than a
pass to be thrown away, and once because its test already passes.

**What is actually left to do**, then, is narrower than the document
implies, and is two things rather than one:

1. *The decision is implicit.* What may be pushed is whatever
   `CalciteCompiler` does not decline -- ordinals, outer and
   dependent joins, `ifEmpty`, and an aggregate whose type has no
   SQL spelling. That is a capability description written as control
   flow. Coloring's contribution is to make it a profile: data, read
   the same way by three implementations, which is what §12.2 and
   §12.5 are for. The boundary of §12.1 -- an ordinary built-in, as
   that section now records -- is what lets a rule *say* where the
   decision fell.
2. *Query-constants cross as callbacks.* `morelScalar('ten', ...)`
   is a value the enclosing environment already holds. Coloring, or
   something smaller and sooner, could hoist it to a parameter.

Neither is the cleaving of §4 and §12.3, which is what the document
spends its length on. Cleaving matters when a profile declines a
node in the middle of a tree; today the decision is taken at the
root, and a tree that declines anywhere runs locally in full. That
is the case to measure next, and it is not measurable from the suite
as it stands, because no query in it is partly pushable.

## 17. Where coloring stops today: the leaf

§16 measured what coloring has to do. Trying to build the fold of
§12.3 on top of `Profile` found what stops it, and it is one thing.

**A node's colorability is a function of the node; a leaf's is not.**
`Profile.permits` answers for a node from the node alone, which is
what §12.5 asks. A leaf is an arbitrary expression, and the question
"can this engine read it?" is answered today by *compiling* it:
`CalciteCompiler.toRel3` compiles the expression and asks whether the
`Code` that comes back is a `RelCode` that can become a `Rel`. That is
not a function of the Core node, so a coloring fold cannot ask it and
stay the pure function of tree and profile that §12.2 requires.

And the question cannot be ducked, because a fold that says every
leaf is colorable colors every tree at the root -- which is the
decision the translation already takes, so it would add nothing --
and one that says no leaf is colorable colors nothing, since every
tree bottoms out in leaves.

**What the leaves actually are.** In the suite they are of three
shapes. `#emps scott`, a field of a foreign structure, which Calcite
reads as a table scan. A collection the query wrote, `[1, 2, 3]` or a
list of records. And a relational built-in over one of those, `#filter
Bag (fn e => ...) (#emps scott)` or `#tabulate List (...)`, which
becomes a `morelTable` -- Calcite's plan containing a call back into
Morel, which is not Calcite running it.

**A first proposal, and why it was wrong.** It was: a leaf is
colorable when it is a field of a value the engine owns, and not
otherwise. That is right for a SQL dialect, where the only data are
the tables the database has, and wrong in general.

**Settled: whether a leaf may be colored is a fact about the
deployment, which is what a profile is for.** Spark Connect ships
values from the environment to the cluster, so when coloring for
Spark every referenced data set is colored `spark` -- not merely
*may* be, but is, since a fragment that reads it must have it. For a
SQL dialect, nothing ships, and only a table the database owns can be
colored. So the profile gains a fact beside the node kinds: whether
the engine can be given data, and by what means.

**The environment divides, and not by type.** A *data set* may cross;
a *built-in function* may not, whatever the profile. That is §12.1's
first validator rule seen from the other side -- "a function, or a
value containing one, cannot cross in any position" -- and the
mechanism is already named there: the free variables of what is
inside become parameters. So the question a fold asks of a leaf is
not "does the engine own this?" but "is this data, and can this
profile be given data?".

**A leaf's colour therefore follows its consumer.** `#emps scott` is
not colorable in itself; it is colored `spark` because what reads it
is. So the fold is not the plain bottom-up pass §12.3 describes: node
colorability propagates up, and a leaf takes the colour of the node
that consumes it, where the profile allows. That is one more reason
cleaving is the hard part and the part nothing has exercised.

It still amends §12.2 by one phrase -- coloring is a pure function of
the tree, a profile **and the environment** -- because the environment
is what says whether a name is a data set or a function.
`RelRule.Context` carries it already, because grounding needed it, and
the ports need the same.
