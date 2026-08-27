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

* **`239-check` is 99 commits above its merge-base** (`d4eeff5a`), not the 89
  this plan was first written against; C090-C099 arrived since. **7 are
  `fixup!` commits** awaiting an autosquash, not 5.
* **Tag `239-check.2` is stale**: it points at C089, and the branch has moved on
  since. The reorganization starts from **`239-check.2a`**, tagged at C099.
  Tags are never moved.
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
  C41, C42, C44-C52, C61, C63-C65, C69, C70, C72, C73, C75, C77-C79, C096,
  C098, and the seven fixups C081, C082, C086, C087, C088, C094, C095.
* **Neither** — C89, the lint commit, is dropped as a duplicate of `a071bcd7`;
  C90, this plan, is deleted with `plan.md` at the end.

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

1. ~~Tag `239-check.2`.~~ **Done**, but stale -- it is at C089. Tag
   `239-check.2a` at C099 and start from there.
2. ~~Run **E1** (attribute branch).~~ **Done, and it confirms.** Delete
   `369-attribute` and its remote.
3. Note the 7 fixups' targets, since the next step consumes them.

Ends at `239-check.2`.

### Phase 1 — autosquash, then label

**The fixups must be applied before the labelling**, because a `fixup!`
commit names its target by subject text, and prefixing subjects with `Cnn: `
breaks the match.

1. `git rebase -i --autosquash d4eeff5a` — no reordering, only the 7
   fixups folding into their targets. 99 commits become 92.
2. `fullMake`; tag `239-check.3`.
3. Build the id map, oldest first:

   ```
   git rev-list --reverse d4eeff5a..HEAD | nl -ba -w3 -nrz -s' ' > /tmp/ids
   ```

   and prefix each subject with `git filter-branch --msg-filter`, looking
   `$GIT_COMMIT` up in the map. `filter-branch` is deprecated and is the right
   tool anyway: it is a local one-shot, needs nothing installed, and gives the
   original hash to the filter, which is what the map is keyed by.
4. `fullMake`; tag `239-check.4`.

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

Re-planned once the experiments report. The shape it is aiming for:

1. `git rebase -i d4eeff5a`, reordering so that the M1 group, then the M2
   group, then the M3 group, then the M4 group appear in that order, with
   `squash`/`fixup` collapsing each group to one commit. **M3 is squashed even
   though it is already on main**, because M4's code does not compile without
   it; Phase 3a is what removes it.
2. The plan-only commits: `fixup` each into the commit that acts on it, so
   that its reasoning arrives with the code. Those that record a decision with
   no code are dropped here and carried into `follow-up-issue.md`.
3. The alias revert cluster — C10, C11, its follow-up, the revert of both, the
   second attempt, and the meet rule — collapses into M3. The first attempt is
   not history anyone needs.
4. `fullMake` after each of the four commits, not only at the end:
   `git rebase --exec 'fullMake' d4eeff5a`.

Ends at `239-check.5`, four commits on `d4eeff5a`.

### Phase 3a — rebase onto `origin/main`

**Squash first, then rebase**, and not the other way round. The branch is 101
commits and `origin/main` is 25 ahead of the merge-base, with 31 files touched
by both sides. Replaying the branch commit by commit means resolving that
overlap up to a hundred times, against intermediate states that are about to be
squashed away and that nobody will ever read. Replaying four commits means
resolving it four times, against exactly the states that merge. The intermediate
history is less true for the length of one rebase, and it is history we are
deleting anyway.

1. `git rebase --onto origin/main d4eeff5a 239-check`.
2. **M3 collapses to empty** against `d1a013a4` and is dropped -- `git rebase
   --skip` if it does not drop itself. If it does *not* come out empty, the
   residue is the difference between what the branch had and what was squashed
   into #459, and it is worth reading before discarding.
3. Resolve M1, M2 and M4 against main's 25 commits. The likely trouble spots
   are the env-count expectations in `sys.smli` and `misc.smli`, and
   `type.smli`/`type-alias.smli`, where main's alias tests and the branch's
   check tests both landed.
4. `git rebase --exec 'fullMake' origin/main` to confirm each of the three
   stands on its own; tag `239-check.5a`.

Ends at `239-check.5a`, three commits on `origin/main`.

### Phase 4 — terminology and redundancy

Both are edits to the three commits, applied as `fixup!` commits and then
autosquashed, so that each lands in the commit that introduced the code.

1. **"constrained type" to "checked type"** — see below.
2. **The redundancy sweep** — see below.
3. **Move the reasoning into javadoc** — the parts of `plan.md` that explain
   why the code is the way it is go onto the classes they explain:
   `TypeResolver`, `Resolver`, `Conditions`, `Ast.ConstrainedType` (by then
   renamed), and the internal operators. Per class, next to the code, rather
   than as a page under `docs/`, which drifts the way `plan.md` already has.
   What is about the design as a whole rather than any one class is compressed
   into M4's commit message instead.
4. `fullMake`; tag `239-check.6`.

### Phase 5 — reword

1. Write the three final messages (see the rules below).
2. Strip the prefixes, with a `--msg-filter` of `sed 's,^C[0-9][0-9][0-9]: ,,'`.
3. `fullMake`; tag `239-check.7`.

### Phase 6 — the design record

1. Write `follow-up-issue.md` (outline below), as a file to paste into the
   issue tracker rather than something to merge.
2. Delete `plan.md` and `squash.md` in the final commit of the series.
3. `fullMake`; tag `239-check.8`. This is what merges.

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

**E6 — is the split legible?** For each of the three, read its diff cold and
ask whether the subject would be guessed from it. A commit that cannot be
described in one line is two commits.

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

Mechanically: after Phase 3, for each method and field added by the branch,
count references in the final tree.

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

* **Whether M3 comes out of Phase 3a completely empty.** If it does not, the
  residue is what the branch had that #459 did not take, and it needs reading
  before it is discarded.
