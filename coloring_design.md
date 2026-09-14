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

Status: **issues only.** Nothing here is decided. The questions are
written down while what prompted them is fresh; the next session
settles them.

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

