# Reorganizing `239-check` for main

A working plan for turning 89 commits into three or four, and for deciding
what of the design record survives. It is written to be re-planned: the
experiments in it are the parts we do not yet know the answer to, and each one
says what would change if it fails.

## Where things stand

* **`239-check` is 89 commits above `origin/main`** (`d4eeff5a`). Of those, 27
  touch only `plan.md`, 5 are `fixup!` commits awaiting an autosquash, and 8
  do not mention #239 in their subject.
* **Local `main` is stale**, at `2e82b462`. Every command below names
  `origin/main`; do not rebase onto `main` by habit.
* **`369-attribute` appears to be already merged, not pending.** Its work is on
  `origin/main` as the squashed `ed919a2b` "Add attributes and doc comments
  (#369)", including the last commit on the branch: `attribute.smli` on main
  carries the nested-comment tests that `77290b8f` added, and main's copy is a
  superset in every hunk sampled. `git cherry` still lists all 18 as unmerged,
  but that is what it always reports against a squash merge. **E1 settles
  this**; if it confirms, the branch is deleted rather than reorganized, and
  this plan concerns `239-check` alone.

## The target shape

Four commits, landing in this order. The first three are independent of
checked types and each fixes something that is a bug today.

| | commit | what it is |
| ---- | ---- | ---- |
| **M1** | Type a modified record as written | #432 fix: a modifier was desugared before it was typed, and the destructure at the front erased the type of the record being modified. |
| **M2** | Ground a record variable from constraints on its fields | A query bug with no `check` anywhere: `from p: {i:int, j:int} where p.i elem [0..2]` reported that `p` was not grounded. |
| **M3** | A type alias survives inference | Alias terms, head-reduction, the meet rule, the displayed type, and the operators that reify a type (`typeof`, `type_string`) agreeing with it. |
| **M4** | Checked types (#239) | The feature: syntax, checking at every site, composites, rejections, the planner. |

Landing separately, not part of the series:

* **The dangling-javadoc lint** (`522e9615`) — already independent, already
  cherry-picked to `morel.1`. Drop it from the series and land it on its own.

**`plan.md` does not migrate.** It is deleted in the last commit of the
series; what survives goes to `follow-up-issue.md` (below) and to javadoc.

Whether M3 and M4 can be separated at all is **E4**, and it is the experiment
that decides between three commits and four.

## Ground rules

* **Tags.** `239-check.N` at each phase boundary, before anything
  destructive. `.0` and `.1` are already taken, by checkpoints made earlier in
  the branch's life, so the reorganization runs from **`.2`**, which is the
  branch as it stands now. Every phase below says which tag it ends with.
  Nothing is ever recovered from the reflog when a tag would have done.
* **Ids.** Each commit gets `C01`..`C89` in current order, and carries `Cnn: `
  as a subject prefix for the duration. The prefix is how we refer to commits
  in conversation once their hashes start changing under us, and it is how a
  reordered todo list stays readable. It is stripped in the last phase.
* **Green at every tag.** `fullMake` passes at each tagged point. Individual
  intermediate commits inside a phase need not pass; the four final ones must,
  each on its own (E2, E3, E4 check exactly this).

## Phases

### Phase 0 — record and verify

1. Tag `239-check.2`.
2. Run **E1** (attribute branch). If merged, delete `369-attribute` and its
   remote; if not, re-plan with a second series.
3. Note the 5 fixups' targets, since the next step consumes them.

Ends at `239-check.2`.

### Phase 1 — autosquash, then label

**The fixups must be applied before the labelling**, because a `fixup!`
commit names its target by subject text, and prefixing subjects with `Cnn: `
breaks the match.

1. `git rebase -i --autosquash origin/main` — no reordering, only the 5
   fixups folding into their targets. 89 commits become 84.
2. `fullMake`; tag `239-check.3`.
3. Build the id map, oldest first:

   ```
   git rev-list --reverse origin/main..HEAD | nl -ba -w2 -nrz -s' ' > /tmp/ids
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

Ends at `239-check.4` (unchanged).

### Phase 3 — reorder and squash

Re-planned once the experiments report. The shape it is aiming for:

1. `git rebase -i origin/main`, reordering so that the M1 group, then the M2
   group, then the M3 group, then the M4 group appear in that order, with
   `squash`/`fixup` collapsing each group to one commit.
2. The 27 plan-only commits: `fixup` each into the commit that acts on it, so
   that its reasoning arrives with the code. Those that record a decision with
   no code are dropped here and carried into `follow-up-issue.md`.
3. The alias revert cluster — `C..` for `A type alias should survive
   inference`, its follow-up, the revert of both, the second attempt, and the
   meet rule — collapses into M3. The first attempt is not history anyone
   needs.
4. `fullMake` after each of the four commits, not only at the end:
   `git rebase --exec 'fullMake' origin/main`.

Ends at `239-check.5`.

### Phase 4 — terminology and redundancy

Both are edits to the four commits, applied as `fixup!` commits and then
autosquashed, so that each lands in the commit that introduced the code.

1. **"constrained type" to "checked type"** — see below.
2. **The redundancy sweep** — see below.
3. `fullMake`; tag `239-check.6`.

### Phase 5 — reword

1. Write the four final messages (see the rules below).
2. Strip the prefixes: `git filter-branch --msg-filter 'sed "s/^C[0-9][0-9]: //"'`.
3. `fullMake`; tag `239-check.7`.

### Phase 6 — the design record

1. Write `follow-up-issue.md` (outline below), as a file to paste into the
   issue tracker rather than something to merge.
2. Delete `plan.md` and `squash.md` in the final commit of the series.
3. `fullMake`; tag `239-check.8`. This is what merges.

## Experiments

**E1 — is `369-attribute` merged?** Diff each attribute-related file between
`369-attribute` and `origin/main`, and confirm main is a superset in every
hunk that is not comment-style drift from the `(*)` lint rule. Check
`MorelParser.jj` specifically for the nested-comment handling of `77290b8f`,
which is the one commit that came after the squash point.
*If it fails*: the attribute branch needs its own series, and this plan grows
a parallel track.

**E2 — does M1 stand alone?** Cherry-pick the record-modifier typing commit
onto `origin/main` with `Resolver.letValue`; `fullMake`.
*Known result, needs re-confirming after Phase 1*: it applied clean with no
conflicts and 516 tests passed. Re-run because the fixups have since changed
`Resolver`.
*If it fails*: M1 merges inside M4 and the series is three commits.

**E3 — does M2 stand alone?** Cherry-pick the grounding commit onto E2's
result; `fullMake`. The plan records the diagnosis as independent of checked
types, but the commit may still touch code M4 introduces.
*If it fails*: M2 folds into M4.

**E4 — can the alias work be separated from the check work?** The one that
decides three commits or four. Rebase the alias group — the revert cluster,
the meet rule, `typeof`, `type_string`, and the two type-error commits — onto
E3's result, and `fullMake` with the check work absent.
*Expected difficulty*: `check` nodes ride on the alias mechanism, and
`TypeMap.displayedKey` was added for `typeof` but is now read by the checked
-type code too. The question is whether the alias half compiles and passes
without the other half, not whether it is useful alone.
*If it fails*: M3 and M4 are one commit, the series is three, and the message
has to explain both.

**E5 — does M4 squash cleanly?** Collapse everything remaining into one
commit and `fullMake`. This is the null hypothesis; it should pass.
*If it fails*: something in the middle of the branch depends on being applied
in order, and we need to know what.

**E6 — is the split legible?** For each of the four, read its diff cold and
ask whether the subject would be guessed from it. A commit that cannot be
described in one line is two commits.

**E7 — the redundancy sweep.** Below.

## Terminology

**"constrained type" becomes "checked type"** in the specification, the
implementation and the comments. The type is written with `check`, the
operator that adds a condition to an expression is `check`, and the exception
is `Constraint`; "constrained" is a third word for the same idea.

Measured on the current branch: **92 lines** carry the phrase, attributed by
`git blame` to **33 commits** — but after Phase 3 there are four commits, so
this becomes four fixups, not 33. **That is the reason to do the rename after
the squash rather than before**, and it reverses what an earlier note in
`plan.md` said.

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

* **Subject lines must match the case convention of the summaries they
  replace** — sentence case, no trailing period, backticks around code,
  within 72 characters. *Open: whether "case summaries" meant this, or meant
  that a subject should echo the prose comment of the test case that
  demonstrates it. The second reading would tie M4's subject to the heading in
  `check.smli`. Confirm before Phase 5.*
* **Descriptions get much shorter, and shortest where most was squashed.**
  A commit that absorbs thirty is not thirty paragraphs; it is the design in
  five. State what changed and why it had to; leave out how it was arrived at,
  which is what `plan.md` was for.
* **No `Cnn:` prefixes**, and no `fixup!` subjects, in the final four.
* The recent lint commit is the model for length: subject, one sentence, an
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
10. **Two edits #239 itself needs** — it still requires the `check` match to be
    exhaustive, and its capture-semantics section is superseded.

What does **not** go in: the phases, the axes of the test matrix, the
departures list, and everything recording how the design was arrived at.
Anything that explains why the code is the way it is belongs in javadoc, and
should be moved there in Phase 4 rather than filed.

## Open questions

1. **Three commits or four?** E4 decides.
2. **Does M2 belong to this series at all**, or is it a separate bug fix that
   should go to main on its own, like the lint commit? It has no `check` in
   it.
3. **"Summary lines must match case summaries"** — see Rewording above.
4. **Where does the reasoning go?** Javadoc is the obvious home for the parts
   of `plan.md` that explain the code, but some of it is about the design as a
   whole rather than about any one class. `docs/` is the alternative.
