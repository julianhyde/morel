# Plan for #430

Adds `with all`, `extend`, `extend all`, `remove` and `rename` to the
`with` operator of #249. Inside braces a base expression is followed by
operator regions applied left to right, each seeing the result of the
last.

## Done

| Commit | |
|---|---|
| `924d3418` | `with` rejects an unknown field, and needs a known base |
| `b0e14425` | `regions` list on `Ast.Record` |
| `6580371b` | the four words highlighted as keywords |
| `94a87193` | `Region` abstract, a subclass per payload |
| `1acefcb4` | the four words are non-reserved keyword tokens |

`924d3418` fixed the two `with` defects that #430 specifies: an
assignment to a field the base does not have was ignored, and a base of
unknown type threw a `ClassCastException` rather than reporting an
unresolved flex record.

`Ast.Record` carries `ImmutableList<Region>`. `Region` is abstract with
`AssignRegion` (`with`, `extend`, `rename`), `AllRegion` (`with all`,
`extend all`) and `RemoveRegion` (`remove`); each unparses itself,
enumerates its expressions and accepts a shuttle. `Op` has the six
region constants.

The four words are tokens, converted back to identifiers by
`nonReservedKeyword()` wherever an identifier is accepted. Being tokens
is what stops an application from swallowing them: a region keyword sits
where a comma could, so it must end the expression before it, and
`<EXTEND>` is not in the first set of `expression9`, exactly as `<WITH>`
is not.

## 1. Parse regions

Add a `recordRegion` production and admit it in the record production
wherever the `( <COMMA> recordExp )*` loop admits a comma. Fold the
existing `with` into the region list, so `Record.with` becomes the base
and `Record.args` goes away.

Watch for: `LOOKAHEAD(2)` on the comma loops, since a region keyword and
a comma both continue a region; and the `{r extend all {...}}` case,
where the argument of `all` is itself a record and re-enters the
production.

Verify with `assertParseSame` in `MainTest`, which needs no type
resolution -- a round trip through `Region.unparse` is the whole test.

## 2. Desugar to `let`

The issue's desugaring is the implementation: destructure the base once,
one nested `let` per `with` or `extend` region with assignments joined by
`and` so they bind simultaneously, and `remove` and `rename` deciding
only which names appear in the final record expression. Nesting is why a
later region sees an earlier one; `and` is why `{r with i=j, j=i}` swaps.

Do it in `TypeResolver`, not `Resolver`: the names in an assignment have
to resolve against the fields, which is name resolution, and by
`Resolver` that has happened. Nothing is then needed in `Core` or at
runtime.

The obstacle: the desugaring needs the base's field names, which need
its type, which is not known until the base has been deduced.
`deduceTypeWithRetries` already re-runs the whole deduction when
`typeSystem.expandCount` changes for a progressive record; the cheapest
route is probably a second such signal meaning "a record region was
rewritten, go again". Failing that, a deferred action on the base's type
variable, as `deduceRecordType` already uses to unify the common fields
of a `with`.

## 3. Check each operator

Each region reports its own error, all of them needing the base's fields:

* `with`, `with all` -- every label must exist, and may not change type;
* `extend`, `extend all` -- every label must be absent;
* `remove` -- every label must exist;
* `rename` -- every right label must exist, and every left label must be
  absent once all right labels are removed, so pairs may chain or cycle;
  labels must be distinct within each side.

`with` already reports `field 'k' does not exist` from
`checkRecordWith`, which is the place to grow the rest.

## 4. Make the fields visible

Currently an assignment sees only the enclosing environment, so
`{r with i=j}` means the variable `j`. #430 makes the fields visible and
shadowing, so it means the field. This falls out of the desugaring --
the destructuring binds the field names -- so it may arrive with step 2
rather than separately, but it deserves its own commit either way,
because it silently changes what existing code means. `type.smli` pins
the three outcomes: no such field and nothing changes; an `int` field
shadowing a record variable and it becomes a type error; a
record-valued field and the answer changes with no error.

## 5. Tests and docs

Migrate `430-tests.smli` into `type.smli` and `type-inference.smli`,
terse, one-liners, a negative case per operator. Fill in the error text
and positions, which are placeholders in the draft. `430-tests.smli` and
this file are then deleted.

Add the operators to `docs/reference.md`, beside `with`.
