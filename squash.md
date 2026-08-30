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

# Reorganizing `239-check` for main

A working plan for turning 99 commits into three, and for deciding what of
the design record survives. It is written to be re-planned: the experiments in
it are the parts we do not yet know the answer to, and each one says what would
change if it fails.

## Where things stand

* **`239-check` is 92 commits above its merge-base** (`d4eeff5a`), down from
  the 99 it started at: Phases 0 and 1 are done, the seven `fixup!` commits are
  folded into their targets, and every commit carries a `C001`..`C092` prefix.
  The branch is at tag **`239-check.4`**.
* **Tag `239-check.2` is stale** -- it predates the last nine commits of the
  branch's working life. **`239-check.2a`** is the real starting point, and the
  reorganization runs `.2a`, `.3`, `.4`, ... from there. Tags are never moved.
* **`origin/main` has moved 25 commits ahead of `d4eeff5a`**, and the branch
  has never been rebased. **31 files are touched by both sides**, among them
  `TypeResolver`, `Resolver`, `TypeMap`, `Compiler`, `Codes`,
  `MartelliUnifier`, `type.smli` and `type-alias.smli`. That rebase, which this
  plan did not originally have to account for, is now the largest single piece
  of work left; Phase 3a below says when it happens.
* **Local `main` is stale**, at `2e82b462`. Every command below names
  `origin/main`; do not rebase onto `main` by habit.
* **`369-attribute` is merged.** E1 is settled: `ed919a2b` "Add attributes and
  doc comments (#369)" is an ancestor of `origin/main`. Delete the local branch
  and its remote; this plan concerns `239-check` alone.

### What has already landed

* **M3 landed as `d1a013a4`, "A type alias should survive inference (#459)."**
  It is the whole alias group squashed: C10-C14, C16, C17 (the revert cluster
  and the meet rule), C28 and C40 (looking through an alias term), C56
  (`type_string`), and C76's alias half (`typeof`). **This settles E4** -- the
  alias work does separate from the check work -- so the open question of three
  commits or four is answered, and the answer is that M3 is no longer ours to
  land.
* **The dangling-javadoc lint landed as `a071bcd7`.** Branch C89 (`522e9615`)
  is now a duplicate, to be dropped.

**So the series is M1, M2, M4 -- three commits.**

## The target shape

Three commits, landing in this order. The first two are independent of checked
types and each fixes something that is a bug today.

| | commit | what it is |
| ---- | ---- | ---- |
| **M1** | Type a modified record as written | #432 fix: a modifier was desugared before it was typed, and the destructure at the front erased the type of the record being modified. |
| **M2** | Ground a record variable from constraints on its fields | A query bug with no `check` anywhere: `from p: {i:int, j:int} where p.i elem [0..2]` reported that `p` was not grounded. |
| **M4** | Checked types (#239) | The feature: syntax, checking at every site, composites, rejections, the planner. |

M2 **stays in the series** rather than going to main on its own. It is
numbered M2, and the gap where M3 was is left as a gap, because every reference
below is keyed to those names.

Already landed, and no longer part of the plan: **M3** (`d1a013a4`) and the
**dangling-javadoc lint** (`a071bcd7`). See "What has already landed" above.

**`plan.md` does not migrate.** It is deleted in the last commit of the
series; what survives goes to `follow-up-issue.md` (below) and to javadoc.

### Which commit goes where

By `Cnn` (see Phase 1 for the numbering; it is the current order, oldest
first). Plan-only commits are not listed: each folds into the commit that acts
on it, or is dropped into `follow-up-issue.md`.

* **M1** — C68 `Type a modified record as written, and build it in Resolver`.
  One commit, carrying `Resolver.letValue` with it. C70, which makes a
  modifier *claim* the type, needs the check machinery and stays in M4.
* **M2** — C74 `Ground a record variable from constraints on its fields`. One
  commit, one production file, `Generators.java`.
* **M3** — the alias group: C10, C11, C12 (the revert), C16, C17 (the second
  attempt and the meet rule), C13, C14 (what an alias does to a type error),
  C28 and C40 (looking through an alias term to find a record's fields, and a
  field's type), C56 (`type_string` agrees with the displayed type), and the
  alias half of C76 (`typeof`). **All of these are on `origin/main` already**,
  in `d1a013a4`. They are still squashed into an M3 commit in Phase 3, because
  M4 does not compile without them; Phase 3a is where M3 goes away.
* **M4** — everything else that mentions `check`: C18-C27, C29-C33, C35-C39,
  everything from C018 up that is not M1, M2, M3 or plan-only. The seven
  fixups are gone, folded into their targets.
* **Neither** — C084, the lint commit, is dropped as a duplicate of
  `a071bcd7`; C085 and C092, this plan, are deleted with `plan.md` at the end.

C76 still **splits**: `typeof` naming the displayed type is alias work and is
on main, but the same commit makes a `typeof` annotation enforce the conditions
it names, which is M4's. E4 confirmed the rest of the assignment, C28 and C40
included.

## Ground rules

* **Tags.** `239-check.N` at each phase boundary, before anything
  destructive. `.0` and `.1` are already taken, by checkpoints made earlier in
  the branch's life, so the reorganization runs from **`.2`**, which is the
  branch as it stands now. Every phase below says which tag it ends with.
  Nothing is ever recovered from the reflog when a tag would have done.
* **Ids.** Each commit gets `C001`..`C101` in current order, and carries
  `Cnn: ` as a subject prefix for the duration. The prefix is how we refer to
  commits in conversation once their hashes start changing under us, and it is
  how a reordered todo list stays readable. It is stripped in the last phase.
* **Green at every tag.** `fullMake` passes at each tagged point. Individual
  intermediate commits inside a phase need not pass; the three final ones must,
  each on its own (E2 and E3 check exactly this).
* **`d4eeff5a` is the base until Phase 3a.** The squash happens on the
  merge-base, not on `origin/main` -- see Phase 3a for why. Commands in Phases
  1 to 3 that used to name `origin/main` now name `d4eeff5a`.

## Phases

### Phase 0 — record and verify

~~1. Tag `239-check.2`.~~ **Done**, but it went stale. `239-check.2a` is the
   real starting point.
~~2. Run **E1** (attribute branch).~~ **Done, and it confirms.**
   `369-attribute` is still to be deleted, locally and on the remote.
~~3. Note the 7 fixups' targets.~~ **Done**: each `fixup!` subject matched
   exactly one target, so the autosquash needed no hand-editing of the todo.

**Phase 0 complete**, at `239-check.2a`.

### Phase 1 — autosquash, then label

**The fixups must be applied before the labelling**, because a `fixup!`
commit names its target by subject text, and prefixing subjects with `Cnn: `
breaks the match.

~~1. `git rebase -i --autosquash d4eeff5a`~~ **Done.** 99 commits became 92.
   Driven with `GIT_SEQUENCE_EDITOR=true`, so the todo was taken as generated.
~~2. `fullMake`; tag `239-check.3`.~~ **Done**: BUILD SUCCESS, 517 tests, 0
   failures.
3. Build the id map, oldest first:

   ```
   git rev-list --reverse d4eeff5a..HEAD | nl -ba -w3 -nrz -s' ' > /tmp/ids
   ```

   and prefix each subject with `git filter-branch --msg-filter`, looking
   `$GIT_COMMIT` up in the map. `filter-branch` is deprecated and is the right
   tool anyway: it is a local one-shot, needs nothing installed, and gives the
   original hash to the filter, which is what the map is keyed by.
   **Done**, with `C001`..`C092`; the rewrite is message-only, and the tree is
   byte-identical to `239-check.3`.
~~4. `fullMake`; tag `239-check.4`.~~ **Done**: BUILD SUCCESS, 517 tests.

**Phase 1 complete**, at `239-check.4`. **All seven fixups sat after C074**, so
C001-C080 keep the numbers this plan already used -- M1 is still C068 and M2 is
still C074. Only the tail shifted: the lint commit is **C084** (was C089), and
this plan is **C085** and **C092**.

#### What the autosquash cost

Six conflicts, all in `check.smli`, all the same shape: two commits appending
to the end of the file, with an empty merge base. A `fixup!` written late in
the branch's life sits at the end of the file it was written against, and
folding it back into its target moves it hundreds of lines earlier, so every
later commit that appends collides with it. Resolved by keeping ours then
theirs, which preserves branch order and left the shadow-`not` block last,
where its own comment says it has to be.

Content is preserved exactly, but **`check.smli` is reordered**: the
record-pattern tests from C095's fixup now sit immediately after the
tuple-component tests they parallel, rather than at the end of the file where
they were written. That is where they belong once the fixup is folded into its
target, so the reordering stays.

**Gotcha, and it cost a false alarm: `clean` is not optional in this project.**
An incremental `./mvnw test` after a history rewrite reused a stale generated
parser and reported `check.smli` failing to parse `check i => ...` -- 2
failures that did not exist. The same tree built with `clean` is green. Always
`./mvnw clean test`, or `fullMake`, which cleans.

### Phase 2 — experiments

Each experiment is a scratch branch off `origin/main`, cherry-picking or
rebasing a candidate subset, ending in `fullMake`. None of them touches
`239-check`. Results feed a re-plan of Phase 3.

Run them in a `git worktree`, which needs two things that are not obvious.
The worktree must be created with a branch, not detached, and the build needs
`-Dmaven.gitcommitid.skip=true`: `git-commit-id-plugin` cannot read a linked
worktree's `.git`, which is a file rather than a directory, and fails the
build before anything compiles. And `./morel` needs `test-compile`, not
`compile`, because `BuiltInDataSet` lives in the test tree.

Ends at `239-check.4` (unchanged).

### Phase 3 — reorder and squash

**Done.** Four commits on `d4eeff5a`, tagged `239-check.5`, each green on its
own: M1 (516 tests), M2 (516), M3 (516), M4 (517).

**Not by reordering a 92-commit todo.** A `rebase -i` that moves the M1, M2 and
M3 groups to the front replays every intermediate state through a history that
no longer has the commits those states assumed, and each collision has to be
resolved against a state nobody will read. Because the *endpoint* is known --
the branch's tree at `239-check.4` -- the series can be built forwards instead,
which is what was done:

1. **M1** — cherry-pick C068 onto `d4eeff5a`. Clean.
2. **M2** — cherry-pick C074 onto M1. Only `plan.md` and `check.smli`
   conflicted, and neither file exists at that point, so both were dropped.
3. **M3** — cherry-pick the surviving alias commits: C013, C014, C016, C017,
   C028, C040, C056. **The revert cluster is skipped rather than collapsed**:
   C010 and C011 followed by the revert C012 net to nothing, so picking none of
   the three gives the same tree as squashing all three, with no conflicts.
4. **M4** — `git read-tree --reset -u` to the branch's tree, and commit. One
   step, and it makes the final tree exactly right by construction. This is E5,
   and it passes.
5. `git rebase --exec 'fullMake' d4eeff5a` to check all four.

#### What Phase 3 found

* **M1 does not compile without `Resolver.letValue`**, exactly as this plan
  said. The helper was added to M1 with its javadoc reworded: the branch's
  version says the `let` stops the expression being evaluated twice "once for a
  condition and once for the result", and M1 has no conditions.
* **M2's tests belong in `such-that.smli`, not `relational.smli`.**
  `such-that.smli` is the file that owns grounding -- it holds every other
  `pattern 'x' is not grounded` test -- and the new cases went in beside the
  ungrounded-tuple-pattern block. It runs in tabular output mode, so the
  expected output is not the `val it = [...]` form the cases had in
  `check.smli`; it was regenerated with `./morel --echo`.
* **M2's test was rewritten to use an inline record type**, `from p: {i: int,
  j: int, k: int}` rather than a named `triple`. A named type prints as
  `{i:int, j:int, k:int} list` before the alias work and `triple list` after
  it, so a named type would have made M2's expected output conflict with itself
  in Phase 3a. The inline form is stable across that boundary.
* **`d1a013a4` cannot be cherry-picked onto `d4eeff5a`** -- which would have
  been the tidiest M3, since it would then rebase to exactly empty. It depends
  on main's unbound-type-constructor fix (#448): without it `val b: true =
  false` reaches `TypeMap` and throws `AssertionError: unknown type constructor
  true`. M3 is therefore the branch's own alias work, and Phase 3a has to
  reconcile it with main's rather than skip it.
* **C040 is not alias work, despite its subject.** "Look through an alias term
  to find a field's type" changes `TypeResolver` and `type-alias.smli` for the
  alias, but its `Resolver` hunk is pure check machinery -- `checkClosed`,
  `total`, `setCheckPredicates`. Only the alias half is in M3; the `Resolver`
  hunk stayed in M4. C028 needed no such split.
* **C076 is left whole in M4**, not split as this plan expected. Its alias half
  is on main already, and its implementation is entangled with
  `Ast.ExpressionType` and with the conditions a `typeof` annotation enforces.
* **`.envrc` is not project content and is excluded from M4.** C091 swept a
  personal `jenv` config into the branch with a `commit -a`. It is not on main.

Ends at `239-check.5`, four commits on `d4eeff5a`.

### Phase 3a — rebase onto `origin/main`

**Squash first, then rebase**, and not the other way round. The branch was 99
commits and `origin/main` is 25 ahead of the merge-base, with 31 files touched
by both sides. Replaying the branch commit by commit means resolving that
overlap up to a hundred times, against intermediate states that are about to be
squashed away and that nobody will ever read. Replaying four commits means
resolving it four times, against exactly the states that merge. The intermediate
history is less true for the length of one rebase, and it is history we are
deleting anyway.

**Done.** Three commits on `origin/main`, tagged `239-check.5a`, each green on
its own: M1 512 tests, M2 512, M4 513. `origin/main` alone is 512, so the
series adds exactly one test -- `check.smli`, one new `ScriptTest` parameter.
M1 and M2 extend existing `.smli` files, which does not change the count.

**It was far easier than this plan feared.** Four conflicts in total:

* **M1 and M2 applied clean.** No conflicts at all.
* **M3 came out completely empty**, contradicting the correction made after
  Phase 3. Every Java file applied with no residue -- the branch's alias
  production code is byte-identical to main's -- and the two test files that
  conflicted, `type-alias.smli` and `type.smli`, turned out to have **main as a
  strict superset of the branch** in both cases. `d1a013a4` was polished beyond
  what the branch had. There was nothing to fold into M4 and nothing to read
  before discarding. `Unifier.weaken`, the residue this plan expected, belongs
  to M4 rather than M3: `eraseConstraints` is check work.
* **M4 conflicted once**, in `LintTest.java`, and trivially:
  `[a-z][a-zA-Z_]*` against `[a-z][A-Za-z_]*` -- the same character class
  written two ways, because C084 duplicated the lint fix that main already had
  as `a071bcd7`. Took main's spelling.

The trouble spots this plan predicted -- the env counts in `sys.smli` and
`misc.smli` -- did not materialize. They auto-merged.

#### Two things the rebase exposed

* **`fullMake` reformats, and that can dirty the tree mid-rebase.** Google Java
  Format runs in `process-sources` and restored a blank line after the static
  imports in `TypeResolver.java`, which made `rebase --exec` stop with
  "cannot rebase: You have unstaged changes". Amend with the formatter's output
  and continue. Note the exec had already run for every commit by then, so the
  stop was at the end, not where it first looked.
* **`read-tree` from the branch made M4 revert M2's tests.** M4's tree was
  taken wholesale from the branch, and the branch never had the
  `such-that.smli` cases that Phase 3 wrote fresh for M2 -- so M4 silently
  removed the 26 lines M2 had just added. Caught by grepping for M2's own
  comment in the final tree and finding nothing. **Any content authored during
  the reorganization, rather than carried from a branch commit, is invisible to
  a wholesale `read-tree` and has to be re-applied to M4.** Only two things
  were authored that way: these tests, and M1's reworded `letValue` javadoc --
  and M4 reverting the latter is correct, because M4 is where conditions exist.

### Phase 4 — terminology and redundancy

**Done**, all of it landing in M4 -- which is the whole point of renaming after
the squash rather than before. `fullMake` green, 513 tests; tagged
`239-check.6`.

1. **"constrained type" to "checked type"** — done. The identifiers
   (`Ast.CheckedType`, `Op.CHECKED_TYPE`, `AstBuilder.checkedType`,
   `rejectCheckedFunction`), the three user-visible messages, and the prose.
   **The verb survives unrenamed**, as this plan predicted for
   `Resolver.constrains`, and it turned out to be most of the hits: `Generators`
   alone has 212 lines saying "constrain", and `Fbbt`, `Unifier` and
   `MartelliUnifier` another 110, all of it the query and unification sense --
   a range constraint, a constrained type variable -- which has nothing to do
   with `check`. Only the noun moved.

   Two things it dragged with it: `check.smli` has an AST-unparser expectation
   that prints the op name, so `(constrained_type int check ...)` became
   `(checked_type ...)`; and `lib/general.sig` documents the `Constraint`
   exception in terms of "the constrained type it is being converted to", so
   `docs/lib/general.md` had to be regenerated. The exception itself stays
   `Constraint`.

   Also renamed, case by case rather than mechanically: "unconstrained type"
   became "unchecked type" where it meant "carries no condition", and stayed
   where it meant "not yet constrained by the query" -- as in "a field left
   unconstrained leaves the record ungrounded", which is grounding, not
   checking.
2. **The redundancy sweep** — see below. One real finding, removed.
3. **The reasoning into javadoc** — mostly already there. The branch was
   written with its reasoning in javadoc as it went: `Conditions`,
   `checkClosed`,
   `total`, `letValue` and the three internal operators all carry it. What was
   missing was the whole-design overview, which went onto `AliasType`, the class
   that carries the conditions: erasure, widening free and narrowing checked,
   and the one invariant the soundness argument rests on.
4. `fullMake`; tag `239-check.6`.

### Phase 5 — reword

**Done**, tagged `239-check.7`, all three green: 512, 512, 513.

1. The three messages, written to the rules below. **M4's subject is settled by
   the case rule** -- #239 is titled "Checked types", so the commit is
   `Checked types (#239)`. M1 and M2 have no case and got none, so their
   subjects are house style and carry no issue reference.
2. The prefixes went with the rewording rather than by `--msg-filter`: with
   only three commits, `rebase -i` rewording each in turn is fewer steps than
   a filter pass, and it is where the messages were being rewritten anyway.
3. `fullMake` after each; tag `239-check.7`.

Two things the rewriting turned up, both fixed here rather than filed:

* **`check.smli`'s opening comment was stale.** It said a `check` clause is
  "parsed but not yet enforced", which was true at the branch's first commit
  and false for everything after it. This is the hazard of a test file grown
  over eighty commits: the earliest prose describes the earliest state.
* **One message escaped the rename.** "cannot constrain parameterized type"
  survived Phase 4, because the rename deliberately spared the verb. Its
  sibling rejection reads "cannot claim a *checked* function type", so it is
  now "cannot check parameterized type" to match.

### Phase 6 — the design record

**Done.** Tagged `239-check.8`; this is what merges.

1. `follow-up-issue.md` written, twelve sections plus the departures. It
   reorders the outline below and drops what shipped: `e check match` was on
   the list and is in M4, and the closedness hole -- a shadowed basis name
   still counting as built-in -- is closed, because closedness is now decided
   by the binding.

   **The three unrelated bugs were re-tested against the merge commit, not
   trusted from the plan.** All three still reproduce: `let type t = int in 1
   end` throws an `AssertionError` and kills the shell; `local ... in ... end`
   does not parse; and a redeclared checked type still checks the condition it
   used to have when a binding holds the old one.
2. `plan.md` and `squash.md` are removed from the index rather than deleted
   from disk, so neither reaches main and both survive locally as untracked
   files. `follow-up-issue.md` is untracked for the same reason. All three can
   be deleted once the issues are filed.
3. `fullMake`; tag `239-check.8`.

## The result

Three commits on `origin/main`:

| | subject | tests |
| ---- | ---- | ---- |
| M1 | Type a modified record as written, and build it in `Resolver` | 512 |
| M2 | Ground a record variable from constraints on its fields | 512 |
| M4 | Checked types (#239) | 513 |

From 99 commits, of which 27 touched only `plan.md`, plus a revert and the two
commits it undid.

## Experiments

**E1 — is `369-attribute` merged?** **Done, and it confirms.** `ed919a2b`
"Add attributes and doc comments (#369)" is an ancestor of `origin/main`, so
the squash merge did happen and `git cherry`'s report of 18 unmerged commits
is the artifact it always is. Delete the branch and its remote.

**E2 — does M1 stand alone?** Cherry-pick the record-modifier typing commit
onto `origin/main` with `Resolver.letValue`; `fullMake`.
*Known result, needs re-confirming after Phase 1*: it applied clean with no
conflicts and 516 tests passed. Re-run because the fixups have since changed
`Resolver`.
*If it fails*: M1 merges inside M4 and the series is three commits.

**E3 — does M2 stand alone?** Cherry-pick C74 onto E2's result; `fullMake`.
The production change is one file, `Generators.java`, and has no `check` in
it. **Two of the four cases it adds need no `check` either**, and both fail on
`origin/main` today with the bug C74 fixes -- checked, in a worktree:

```sml
type triple = {i: int, j: int, k: int};
from p: triple where p.i elem [0,1] andalso p.j elem [2] andalso p.k elem [3..4];
> stdIn:1.6-1.15 Error: pattern 'p' is not grounded
from p: int * int where #1 p elem [0,1] andalso #2 p elem [2,3];
> stdIn:1.6-1.18 Error: pattern 'p' is not grounded
```

with the destructured form beside them working on `origin/main` already, so
the contrast the test is making survives the move. The two `parityPair` cases
stay behind in `check.smli` as M4's, which is where they belong: what they add
is that the type's condition is conjoined into the scan.

So M2 is one production file and three test cases, and E3 is a cherry-pick
plus moving those cases to `relational.smli`.
*If it fails*: M2 folds into M4.

**E4 — can the alias work be separated from the check work?** **Done, and it
confirms.** The alias half compiles and passes with the check work absent, and
it has already merged as `d1a013a4` (#459). Nothing here is left to run; what
it decided is that M3 is not ours to land, so the series is M1, M2, M4.

**E5 — does M4 squash cleanly?** Collapse everything remaining into one
commit and `fullMake`. This is the null hypothesis; it should pass. Run it on
`d4eeff5a` with M3 still present, per Phase 3; whether it survives the move to
`origin/main` is Phase 3a's business, not E5's.
*If it fails*: something in the middle of the branch depends on being applied
in order, and we need to know what.

**E6 — is the split legible?** **Done, and it passes.** Each of the three
contains only what its subject claims, and each is describable in one line.
M1 is four files, M2 is two. M4 is 36, which is what a feature costs, and the
two entries that look foreign to it are not: `MorelHighlighter` gains the
`check` and `asOpt` keywords, and the one line added to `LintTest` is a comment
explaining why the keyword regex admits camel-case -- which `asOpt` is what
forced.

**E7 — the redundancy sweep.** Below.

## Terminology

**"constrained type" becomes "checked type"** in the specification, the
implementation and the comments. The type is written with `check`, the
operator that adds a condition to an expression is `check`, and the exception
is `Constraint`; "constrained" is a third word for the same idea.

Measured on the current branch: **92 lines** carry the phrase, attributed by
`git blame` to **33 commits** — but after Phase 3 there are three commits, and
the rename lands entirely in M4, so this is one fixup, not 33. **That is the
reason to do the rename after the squash rather than before**, and it reverses
what an earlier note in `plan.md` said.

The identifiers are a separate pass, and no line-by-line fixup can do them:
`Ast.ConstrainedType`, `Op.CONSTRAINED_TYPE`, `AstBuilder.constrainedType`,
the parser production, and the `Visitor`/`Shuttle` cases — 17 references over
7 files.

Also to settle while renaming: `Resolver.constrains` reads as "does this type
constrain anything", which stays right under either name; but
`rejectConstrainedFunction` and the message "constrained type '%s' was not
declared successfully" both change.

## Redundancy review (E7)

Code added during development that may no longer earn its place. Each is a
question, not a finding.

* **`Z_CHECK` is derivable from `Z_REQUIRE`** — it is `$require` followed by
  the value, and the caller has already bound the value to a name, so nothing
  is evaluated twice. It had to be a primitive only while a dead binding could
  be discarded, which is no longer true. Three operators, one implementation,
  and `constraintCode` already takes the operator as an argument.
* **`Z_ATTEMPT` answers `bool` and leaves the `if` outside it**, which is the
  shape that made it necessary in the first place. An option-typed answer
  would fold the `if` in.
* **Three accessors for one idea** in `TypeMap`: `getAliasedType`,
  `displayedKey` and `getRealType`. `displayedKey` was added last; check
  whether the other two still have callers that need them.
* **`Resolver.letValue`** — introduced by the check work, used by the record
  modifier fix, and it must travel with M1. Confirm nothing else duplicates
  it.
* **`Unifier.weaken`** moved out of `MartelliUnifier` for `eraseConstraints`
  to use. If E4 puts them in different commits, the move belongs to whichever
  comes first.
* **Anything the reverted alias attempt left behind.** The revert cluster
  collapses to the design that stayed; check that no helper from the first
  attempt survives unreferenced.

#### What the sweep found

**One piece of dead code, removed**: `Resolver.rejectUnnamedCheck`, 20 lines
with no callers. It is left over from the design in which a `check` could only
be written in a type declaration; the branch later allowed one wherever a type
is written, and the method's message -- "a 'check' may only be written in a
type declaration" -- contradicts what ships. Found by listing every method the
branch adds and counting references in the final tree, which is the mechanical
check this section asked for. (`Binding.withBuiltIn` looks dead the same way
and is not: it is called as a method reference, `Binding::withBuiltIn`.)

**The other items are answered "keep":**

* **`Z_CHECK`, `Z_REQUIRE` and `Z_ATTEMPT` already share one implementation.**
  `constraintCode` takes the built-in as a parameter and the three differ by
  three lines inside one `Applicable`. Collapsing `Z_CHECK` into `Z_REQUIRE`
  would trade one enum constant for extra Core nodes at every check site, which
  is worse. Folding the three into one with a mode, and giving the asking mode
  an option type, stays a follow-up -- it is section 6 of the issue below.
* **The three `TypeMap` accessors are not ours.** `getAliasedType`,
  `displayedKey` and `getRealType` are all on `origin/main` already, having
  landed with #459. The branch only adds callers, so there is nothing here to
  remove.
* **`Resolver.letValue` earns its place**: four call sites, one definition,
  nothing duplicating it.
* **`Unifier.weaken` is settled by M3 disappearing.** It has callers in
  `MartelliUnifier` and in `TypeResolver.eraseConstraints`, so the move out of
  `MartelliUnifier` is justified, and with M3 gone it is unambiguously M4's.
* **The reverted alias attempt left nothing behind**, because Phase 3 skipped
  the revert cluster rather than applying and collapsing it.

## Rewording

* **A commit that fixes a GitHub case takes that case's summary line as its
  subject, verbatim**, with `(#nnn)` appended. Not a paraphrase: the subject
  *equals* the issue title. `d1a013a4` is the worked example -- issue #459 is
  titled "A type alias should survive inference" and the commit is
  "A type alias should survive inference (#459)".

  For **M4** this settles the subject with no work: #239 is titled
  "Checked types", so M4 is **`Checked types (#239)`**.

  **M1 and M2 have no case, and no case is filed for them.** M1 is a fix to
  the record modifiers of #432, but #432 is titled "Add `replace`, `extend`,
  `remove`, `rename` operators for functional insert, update, delete, rename of
  record fields" -- the feature request, already implemented, and not what M1
  fixes; borrowing that summary would misdescribe the commit. M2 has no issue
  at all. The rule simply does not apply to either: their subjects are written
  by hand, to house style -- sentence case, no trailing period, backticks
  around code, within 72 characters -- and carry no `(#nnn)`. The subjects in
  the target-shape table above are the starting point.
* **Descriptions get much shorter, and shortest where most was squashed.**
  A commit that absorbs thirty is not thirty paragraphs; it is the design in
  five. State what changed and why it had to; leave out how it was arrived at,
  which is what `plan.md` was for.
* **No `Cnn:` prefixes**, and no `fixup!` subjects, in the final three.
* The lint commit `a071bcd7` is the model for length: subject, one sentence, an
  example, one closing line.

## `follow-up-issue.md`

Drafted in Phase 6, from `plan.md`. One file, sections in this order, each
short enough to become an issue on its own:

1. **Deferred by design, not soundness holes** — constrained function types,
   and recovering a constraint that has reached a type variable. Both need
   type-directed dispatch; #290 needs one too, so they should share it.
2. **Values from outside** — a foreign row never passes through a Morel
   constructor. Check on entry, or declare the boundary untrusted?
3. **Messages when a constraint fails** — the requirements already collected,
   including naming a type that has no name.
4. **Hiding conditions when printing** — a `type_string` that elides them, and
   an unparser that renders `#f x` as `x.f`. The four-condition `hr1` echoes
   as one very long desugared line.
5. **Weakening a check to its residual** — the design as it stands: split
   conjuncts on `andalso`, a premise set keyed to `NamedPat`, `Fbbt` for
   entailment. Subsumes repeated narrowing.
6. **The internal operators** — fold the three into one with a mode; give the
   asking mode an option type.
7. **Capture for a condition that is not closed**, `unchecked t`,
   `predicate t`, and making `typeof e check m` work so that `e check m` is
   derivable.
8. **Refining the environment** after `assert`, `assume`, `prove` and `where`.
9. **Unrelated bugs found on the way** — `let type t = int in 1 end` throws an
   AssertionError; `local ... in ... end` is unimplemented; a redeclared
   checked type checks the condition it used to have when a binding still
   holds the old one.
10. **Two edits #239 itself needs** — it still requires the `check` match
    to be exhaustive, and its capture-semantics section is superseded.

What does **not** go in: the phases, the axes of the test matrix, the
departures list, and everything recording how the design was arrived at.
Anything that explains why the code is the way it is belongs in javadoc, and
should be moved there in Phase 4 rather than filed.

## Questions, answered

1. ~~**Three commits or four?**~~ **Three** -- M1, M2, M4. E4 confirmed the
   alias work separates, and it has since merged on its own as #459, so M3 is
   no longer ours to land.
2. ~~**Does M2 belong to this series at all?**~~ **Yes, it stays in the
   series.** It could have gone to main alone, as the lint commit did, but it
   travels with the work that found it.
3. ~~**"Summary lines must match case summaries"**~~ **It means: when a commit
   fixes a GitHub case, its subject equals that case's summary line.** It
   settles M4 (`Checked types (#239)`) and does not apply to M1 or M2, which
   have no case and are not getting one; their subjects are written to house
   style. See Rewording above.
4. ~~**Where does the reasoning go?**~~ **Javadoc, per class**, in Phase 4,
   next to the code it explains rather than a page under `docs/` that would
   drift the way `plan.md` has. Whole-design reasoning goes into M4's message.

## Still open

Nothing in this plan. All six phases are done, every experiment has reported,
and the series is `239-check.8`.

~~**Whether M3 comes out of Phase 3a completely empty.**~~ It does. See Phase
3a.

What is left is outside the reorganization:

1. **Merge the three commits.**
2. **File the issues** in `follow-up-issue.md`, and the two edits #239 itself
   needs (section 12).
3. **Delete `plan.md`, `squash.md` and `follow-up-issue.md`** from the working
   tree once the issues are filed. None of them is tracked; this file survives
   in the tags `239-check.2a` through `.7` if it is wanted later.
4. **`julianhyde/369-attribute` stays.** The branch is fully merged -- its work
   is on main as `ed919a2b`, and the one file that looked unmerged,
   `functions.toml`, was deliberately deleted by `553d17ba` when documentation
   metadata moved to `.sig` files -- but the remote branch is deliberately
   kept. Only the local branch was deleted.
