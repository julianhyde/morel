# Discussion: Obsoleting `functions.toml`

Living log of the conversation about replacing `src/main/resources/functions.toml`
with `.sig` files as the source of doc-generation metadata.

## Context

* `functions.toml` is ~5,775 lines, 450 entries, 5 block kinds
  (`structures`, `functions`, `types`, `exceptions`, `values`).
* Consumers are entirely in test code:
  * `src/test/java/.../util/Generation.java` — generates `docs/reference.md`,
    `docs/lib/index.md`, and `docs/lib/{name}.md` per structure.
  * `src/test/java/.../LintTest.java` — cross-checks against `BuiltIn` enum,
    enforces alphabetical sort.
* Runtime does not read `functions.toml`. Source of truth at runtime is
  `BuiltIn.java` + `Codes.java`.
* `/lib/*.sig` (13 files) carry ML signatures consumed only by
  `SignatureChecker.java`, which lints them against `BuiltIn`.

## Field gap (TOML → `.sig`)

| TOML field | Already in `.sig`? | Path |
|---|---|---|
| `structure`, `name`, `type` | yes | trivial |
| `description` | partial — comments are terser | upgrade comments |
| `prototype` (`drop (b, i)`) | no | `@prototype` doc-comment tag |
| `method = true` | no | derivable from "first arg is structure's own type" OR `@method` tag |
| `specified` (basis/morel) | no | `@specified morel` tag, default basis |
| `implemented` | no | existing convention of commenting out the `val` |
| `extra` | no (1 use) | inline in description |
| structure `description` / `overview` | no | `(*! description: ... *)` / `(*! overview: ... *)` at file head |
| `ordinal` | no | physical declaration order in `.sig` (decided) |
| `[[values]]` (2 entries) | no | fold into `datatype bool` (decided) |

## Missing `.sig` files (12)

`Bag`, `Datalog`, `Date`, `IEEEReal`, `IntInf`, `Interact`, `Range`,
`Relational`, `String`, `StringCvt`, `Sys`, `Time`. Plus `integer.sig` may
want renaming to `int.sig` to match the `Int` structure name.

## Open prerequisites

1. **Comment preservation.** The Morel lexer drops comments. Either teach
   the lexer to keep them optionally, or build a line-based comment-pairing
   pass that attaches each comment block to the following `Ast.Spec`. The
   latter is cheaper and matches a pattern `SignatureChecker` already uses.
2. **Doc-comment convention.** Settle the tag syntax — current proposal:
   * `@prototype <call form>`
   * `@method`
   * `@specified morel` (default `basis`)
   * `(*! description: ... *)` and `(*! overview: ... *)` at file head
   Then document it in `CLAUDE.md`.
3. **Migrate structure-level prose.** `description` and `overview` move into
   `(*! ... *)` blocks. Depends on #2.

## Resolved design points

* **`ordinal` is dropped.** Order on per-structure doc pages = physical
  declaration order in the `.sig` file. `LintTest` enforces that order
  matches SML Basis where applicable.
* **`[[values]]` is dropped.** The two `Bool.true`/`Bool.false` entries fold
  into `datatype bool = true | false` in `bool.sig`.

## Suggested incremental path

A safer first PR keeps `functions.toml` but starts deriving `name`, `type`,
`structure`, and `implemented` from `.sig` files, with `LintTest` flagging
divergence. This eliminates the highest-risk duplication (types drift
between `BuiltIn`, TOML, and docs) without rewriting the doc generator.
Remaining fields migrate one at a time until TOML can be deleted.

## Where the plan still gets ugly

* Long-form prose in TOML descriptions (`<p>`, `<pre>`, examples) would
  either move into `.sig` comments (verbose — distributes ~5000 lines
  across 25 files) or be downgraded.
* Doc-comment pairing relies on physical proximity; refactoring a `.sig`
  file in an editor that moves comments around can silently break docs.

## Doc-comment convention: survey and recommendation

### How other languages do it

**Standard ML world.** No standard convention. The SML/NJ Basis docs use
plain `(* ... *)` comments above each `val` and rely on a separate tool
chain (historically `ml-doc`, XML-based, ran out-of-band). MLton uses
plain comments. Adopting a Morel-specific convention is uncontroversial
because there is no incumbent to break.

**OCaml — odoc / ocamldoc.** `(** ... *)` (double-star) is a doc comment
attached to the following declaration. Body is markdown-ish with curly
braces for markup (`{b bold}`, `{i italic}`, `[code]`, `{!cross-ref}`).
Tags: `@param`, `@return`, `@raise`, `@see`, `@deprecated`, `@since`.
*Separately*, OCaml has first-class **attribute syntax** —
`[@attr payload]`, `[@@attr ...]`, `[@@@attr ...]` — that survives the
parser as AST nodes. Used heavily by ppx preprocessors. Example:
`val foo : int → int [@@inline] [@@deprecated "use bar"]`.

**Haskell — Haddock.** `-- | ...` before a decl (or `-- ^ ...` after).
Block form `{-| ... -}`. Markdown body. Pragmas like
`{-# DEPRECATED foo "msg" #-}` are *not* comments — they're a separate
lexical class the parser recognises.

**Rust — rustdoc.** `///` and `//!` doc comments are *sugar* for the
`#[doc = "..."]` attribute, which is the real underlying mechanism.
Everything machine-readable is an attribute (`#[deprecated]`,
`#[inline]`). Free-form structure inside docs is by markdown section
convention only (`# Examples`, `# Panics`, `# Errors`).

**F# — XML doc + .NET attributes.** Two systems running side by side:
`/// <summary>...</summary>` XML for prose (tags `<param>`, `<returns>`,
`<exception>`, `<example>`), and `[<Obsolete>]`, `[<CompiledName "...">]`
attributes for machine-readable metadata. Same dual setup as C#.

**Scala — Scaladoc.** `/** ... */` Javadoc-style with `@param`,
`@return`, `@throws`, `@tparam`, `@since`, `@see`, `@example`, `@group`.
Annotations (`@deprecated`, `@inline`) are AST-level, separate from docs.

**Java — Javadoc.** `/** ... */` with `@param`, `@return`, `@throws`,
`@see`, `@since`, `@deprecated`. Inline `{@code ...}`, `{@link ...}`.
Annotations (`@Override`, `@Deprecated`) are AST-level, runtime-readable.

**Swift — DocC.** `///` or `/** */`, markdown body, callouts as bullet
list items: `- Parameter x: ...`, `- Returns: ...`, `- Throws: ...`.
Annotations (`@available`, `@inlinable`) are AST attributes.

### Patterns

1. **Every modern doc system uses a marked variant of the normal comment
   syntax** (`(** *)`, `/** */`, `///`, `-- |`) — not a brand-new lexical
   class. The marker is what triggers the doc tool.
2. **Languages with attributes consistently split the work**: prose lives
   in doc comments; machine-readable flags live in attributes. Rust is
   the limit case where doc *is* an attribute.
3. **`@tag`-style inline metadata inside doc comments is universal**
   (Java/Scala/F#/ocamldoc/JSDoc/TSDoc) and tooling-friendly.

### Comment-only vs annotation-based, for Morel

**Pure comment convention (option A).** Cheapest. No parser changes.
A comment-pairing pass over the raw file attaches each `(** ... *)`
block to the following `val`/`type`/`exception` spec. Tags inside the
block carry machine fields:
```ml
(** Returns what is left after dropping `i` elements.
    Raises `Subscript` if `i` < 0 or `i` > `length b`.
    @prototype drop (b, i)
    @specified morel
    @method *)
val drop : 'a bag * int → 'a bag
```
File-level metadata at the top of the `sig` block:
```ml
signature BAG = sig
  (** @specified morel
      @description Unordered collection of elements with duplicates.
      @overview
        The `Bag` structure provides operations on bags ... *)
  ...
end
```

Pros: zero parser work; conventional; immediately readable.
Cons: comment-pairing is fragile under editor moves; tags live in
comments that the compiler doesn't see, so typos go undetected until the
doc generator runs.

**Annotation-based (option B).** Add attribute syntax to the Morel
parser, OCaml-style. Specs grow optional trailing attributes:
```ml
val drop : 'a bag * int → 'a bag
  [@@method] [@@prototype "drop (b, i)"] [@@specified "morel"]
```
File-level via `[@@@...]` inside the `sig` block. Prose still lives in
`(** ... *)` doc comments; attributes carry only the few fields that are
truly machine-readable.

Pros: attributes are AST nodes — typos become parse errors, attribute
values get position info, tools can manipulate them safely.
Cons: new grammar (JavaCC change), new `Ast` node, new visitors, new
formatter behaviour — real work for ~5 fields over ~25 structures.

**Hybrid (option C).** Same as option A initially, with the explicit
plan that if attribute payloads ever grow (e.g., `@deprecated`,
`@since`, `@example`), we promote them to real attributes. Defers the
parser-change cost to when it's actually justified.

### Recommendation

**Start with option A (pure comments) using the `(** ... *)` marker and
`@tag` payloads**, modelled on ocamldoc:

* **Doc comment marker.** `(** ... *)`. Single asterisk stays a normal
  comment, so existing files don't accidentally become doc input. Place
  the marker immediately before the declaration it documents.
* **Prose.** Markdown body. Backticked identifiers, fenced code blocks,
  `<p>` allowed for the HTML target.
* **Machine tags** (all optional, all on their own line):
  * `@prototype <call form>` — e.g. `@prototype drop (b, i)`
  * `@method` — bare flag, no payload
  * `@specified morel` (default `basis`)
  * `@raise <Exn>` — already standard in ocamldoc, useful for docs
  * `@see <ref>` — cross-reference
* **File-level metadata.** `(** ... *)` at the top of the `sig` block,
  using `@description` (one-liner) and `@overview` (paragraph). The
  parser-leading licence header stays a plain `(* ... *)` so it doesn't
  get picked up.
* **"Not implemented" stays the same.** A `val` commented out with
  `(* val … *)` generates a doc entry marked *Not yet implemented* —
  same convention `real.sig` already uses for `*+`, `*-`.

**Promote to attributes only if** payloads grow beyond ~5 fields or we
start needing programmatic manipulation. The line-pairing implementation
should isolate the "doc comment block → following Spec" mapping behind
one small utility so a future swap to AST attributes only touches that
utility plus the parser.

### Open questions on the convention

* `@method` could be derived from "first arg type is the structure's own
  type" — same rule CLAUDE.md already states. Worth dropping the
  explicit tag and computing it? Risk: types like `Int.fromInt : int →
  int` would qualify but probably shouldn't be method-callable.
* TOML descriptions sometimes contain raw HTML (`<p>`, `<pre>`, `<sup>`).
  Permit in doc comments, or require markdown-only?
* Should the comment-pairing tool be liberal (any `(** *)` immediately
  preceding a spec) or strict (must touch the spec, no blank line)?

### How other languages represent parameter names

Standard ML signatures genuinely lack parameter names — `val drop : 'a
list * int → 'a list` has no slot for `(b, i)`. Other languages cope with
this differently:

**SML extensions allow named arguments in the signature.** Successor ML
and SML/NJ both accept (and discard) names in `val` specs, e.g.
`val drop : 'a list * int -> 'a list` *or* `val drop : (l: 'a list, i:
int) -> 'a list`. The names are documentation only — the parser keeps
them but they don't affect typing. Whether Morel's parser tolerates them
today is unknown; cheap to add if not.

**OCaml — labelled arguments.** `val drop : 'a list -> int -> 'a list`
gives no names; `val drop : list:'a list -> n:int -> 'a list` uses
labelled-argument syntax, where the labels (`list:`, `n:`) are part of
the type and *do* affect call sites (`drop ~list:[1;2;3] ~n:1`). Names
are first-class in the type system, which is exactly what we want for
doc generation — at the cost of being part of the call ABI.

**Haskell — no names in types; conventions vary.** Type signatures are
nameless. Haddock workarounds: (a) parameter section in the doc comment
(`-- | drop n elements from the list`), (b) bullet list of arguments by
position (`* The number to drop`, `* The list`), or (c) auxiliary record
type. Most libraries simply rely on the function definition's parameter
names being visible in the rendered docs (Haddock displays them when
available from source).

**Rust — names mandatory in fn signature.** `fn drop(list: &[T], n:
usize) -> Vec<T>` — names are syntactically required and rustdoc just
echoes them.

**F# / C# — names mandatory.** Method signatures carry names that show
up in XML doc, IntelliSense, and the call ABI (`drop(list, n)` or
`drop(list: list, n: n)`).

**Java — names mandatory in source, *erased* in bytecode by default.**
Javadoc tooling reads names directly from the `.java` source; if only
`.class` files are available the names become `arg0`, `arg1` unless
compiled with `-parameters`. The `@param` Javadoc tag is the
documentation hook (`@param list the list to drop from`).

**Scala — names mandatory in source.** Same as Java; named arguments are
also call-site-usable (`drop(list = xs, n = 1)`).

**Swift — names in two roles.** Each parameter has an *argument label*
(used at the call site) and an *internal name* (used in the body),
either of which can be `_`. DocC reads the argument labels.

**TypeScript / JSDoc.** Names are part of `function f(x: number, y:
string)`. JSDoc's `@param {number} x ...` mirrors the source name.

**OCaml again — odoc's pragmatic compromise.** Unlabelled OCaml types
have the same problem as SML. odoc handles it by reading parameter names
out of `let` bindings (the implementation), and the doc comment can use
the names by convention. If only a `.mli` (signature file) is available,
parameter names come from the labels (if any) or are omitted from the
rendered prose. Tags like `@param x` reference whatever name appears in
the type signature or the doc text.

#### Implication for Morel

Three viable approaches, in increasing invasiveness:

1. **`@prototype drop (b, i)` doc tag** (current proposal). Names live
   only in the doc tag; the type signature stays nameless. Pros: no
   parser change, no language change. Cons: names appear in two places
   if they also need to appear in the prose (e.g. `applies f to each
   element x of b`) — drift risk.
2. **Allow optional names in `val` specs**, parsed and kept on the AST,
   ignored for typing — same as Successor ML:
   ```ml
   val drop : (b: 'a list, i: int) → 'a list
   ```
   The doc generator reads names off the AST; no separate tag. One
   small parser change (the type grammar already accepts records, so
   the syntax is mostly there); typing logic unchanged.
3. **Adopt OCaml-style labelled arguments** as a real language feature.
   Out of scope for this issue.

**Recommendation:** start with #1 (`@prototype` tag) because it's purely
additive and ships fastest. If `@prototype` becomes ubiquitous (~all
methods need one), promote to #2 — the parser cost is small and the
benefit (single source of truth for names) is real.

## Attribute syntax proposal

Decision: adopt an OCaml-style attribute mechanism instead of the pure
comment convention. Doc comments become a special case that desugars to
a `doc` attribute. This eliminates the fragile comment-pairing problem,
because attributes are real AST nodes — typos become parse errors,
attribute values carry position info, and tools can manipulate them
safely.

### Surface syntax

Three forms, mirroring OCaml's three bracket depths:

```
[@ attr payload ]       -- attached to the immediately preceding item
                        --   (val, type, exception, datatype spec)
[@@ attr payload ]      -- (reserved; for future use on declarations)
[@@@ attr payload ]     -- floating; attached to the enclosing sig block
```

Why three forms? OCaml's distinction is useful: item attributes attach
to the *preceding* spec; floating attributes attach to the *enclosing*
sig/structure. The middle (`[@@]`) is reserved so the design has room to
grow into top-level declarations later. (For now Morel only needs items
and floating — we could ship with just `[@]` and `[@@@]`, leaving `[@@]`
unrecognised.)

### Examples

Item attributes after a spec:
```ml
val drop : 'a bag * int → 'a bag
  [@ prototype "drop (b, i)" ]
  [@ method ]
  [@ specified "morel" ]
```

Floating attributes at the top of a sig:
```ml
signature BAG = sig
  [@@@ specified "morel" ]
  [@@@ description "Unordered collection of elements with duplicates." ]
  [@@@ overview {|
    The `Bag` structure provides operations on bags (multisets) ...
  |} ]
  ...
end
```

Doc comments — syntactic sugar — `(** ... *)` desugars to a `doc`
attribute:
```ml
(** Returns what is left after dropping `i` elements. *)
val drop : 'a bag * int → 'a bag
  [@ prototype "drop (b, i)" ] [@ method ]
```
…lexes as if you had written:
```ml
val drop : 'a bag * int → 'a bag
  [@ doc "Returns what is left after dropping `i` elements." ]
  [@ prototype "drop (b, i)" ] [@ method ]
```
A floating doc comment at the top of a sig (before the first spec) is
sugar for `[@@@ doc "..." ]`.

### Attribute name and payload grammar

```
attribute       ::= "[@"   attrName attrPayload? "]"        -- item
                  | "[@@@" attrName attrPayload? "]"        -- floating
attrName        ::= IDENT ("." IDENT)*                       -- e.g. doc, morel.prototype
attrPayload     ::= STRING_LIT
                  | INT_LIT
                  | IDENT                                    -- bare identifier flag
                  | "(" payloadItem ("," payloadItem)* ")"   -- tuple payload
payloadItem     ::= STRING_LIT | INT_LIT | IDENT
```

* **Bare flag.** No payload: `[@ method ]`. Treated as boolean true.
* **String.** `[@ prototype "drop (b, i)" ]`.
* **Identifier.** `[@ specified morel ]` — for closed enums like
  basis/morel; shorter than the string form.
* **Tuple.** `[@ since (1, 2) ]` — payload of structured data, if needed.
* **Dotted names** (`morel.doc`, `morel.prototype`) leave room for
  namespacing if attributes proliferate. Initially Morel uses bare names
  (`doc`, `method`, `prototype`, `specified`, `extra`, `since`).

### Where attributes can appear (phase 1)

Only inside signature bodies, at two sites:

1. **After a spec** — item attribute `[@ ... ]` — attaches to the
   immediately preceding `valSpec` / `typeSpec` / `datatypeSpec` /
   `exceptionSpec`. Multiple attributes may follow in sequence; they
   form an unordered set on the spec.
2. **Before any spec** — floating attribute `[@@@ ... ]` — attaches to
   the enclosing `signatureBind`. Multiple may appear; order is
   preserved (so multi-line `overview` text is deterministic).

Later phases can extend attributes to top-level declarations
(`val`, `fun`, `datatype`, …) using `[@@ ... ]`.

### Where the doc comment is allowed

Doc comments use `(** ... *)` (two asterisks at the opening). Position:

* Immediately before a spec — desugars to `[@ doc "..." ]` on that
  spec.
* Immediately after `sig` and before the first spec — desugars to
  `[@@@ doc "..." ]` (floating).
* Anywhere else — lex error or silently degrade to a normal comment.
  (Pick the strict option; better to surface mistakes early.)

Normal `(* ... *)` comments remain just comments, ignored by the parser.
This keeps the licence header (`(* … *)`) and ordinary inline notes
unchanged.

### Parsing mechanism

**1. Lexer (JavaCC).** Add three new tokens. None of them collide with
existing tokens because `[` is currently followed only by an expression
opener, never by `@`:

```
< LBRACK_AT:       "[@"   >
< LBRACK_AT_AT_AT: "[@@@" >
< DOC_COMMENT:     "(**" ~["*"] (~["*"] | "*" ~[")"])* "*" "*)" >
                        -- captures the body so the parser can read it
```

Note the DOC_COMMENT regexp requires *exactly two* opening stars to
avoid matching e.g. `(*** banner ***)`. Body text (between `(**` and
`*)`, with the leading/trailing whitespace trimmed) is exposed via
`image()` to the grammar rule.

The closing `]` reuses the existing `RBRACKET` token.

Lexer also drops the trailing `*)` from the captured image so the
attribute payload is clean.

**2. Grammar productions** (new):

```
List<Attribute> itemAttrs() :
{ final List<Attribute> as = new ArrayList<>(); Attribute a; }
{
  ( ( a = itemAttr() | a = docCommentAsItemAttr() ) { as.add(a); } )*
  { return as; }
}

Attribute itemAttr() :
{ final Span span; final List<String> name; final AttrPayload p; }
{
  <LBRACK_AT> { span = Span.of(pos()); }
  name = dottedName()
  ( p = attrPayload() | { p = null; } )
  <RBRACKET>
  { return ast.attribute(span.end(this), name, p); }
}

Attribute docCommentAsItemAttr() :
{ final Token t; }
{
  t = <DOC_COMMENT>
  { return ast.docAttribute(pos(), t.image); }
}

Attribute floatingAttr() :
{ /* same as itemAttr but with LBRACK_AT_AT_AT */ }
{ ... }

AttrPayload attrPayload() :
{ final AttrPayload p; }
{
  ( <STRING_LITERAL>    { p = ast.payloadString(token.image); }
  | <NATURAL_LITERAL>   { p = ast.payloadInt(token.image); }
  | <IDENTIFIER>        { p = ast.payloadIdent(token.image); }
  | <LPAREN> p = payloadTuple() <RPAREN>
  )
  { return p; }
}
```

**3. Spec rules** are modified to consume trailing attributes:

```
Ast.ValSpec valSpec() :
{ /* existing locals */
  List<Attribute> docs;     // leading doc comment(s)
  List<Attribute> attrs;    // trailing item attributes
}
{
  docs = leadingDocs()      // 0 or 1 DOC_COMMENT
  <VAL> ...
  type = type()
  attrs = itemAttrs()
  { return ast.valSpec(span.end(this), id, type, concat(docs, attrs)); }
}
```

The same `leadingDocs() ... itemAttrs()` bracketing wraps `typeSpec`,
`datatypeSpec`, `exceptionSpec`.

**4. `signatureBind` rule** consumes floating attributes at the start:

```
void signatureBind(List<SignatureBind> list) :
{
  ...
  List<Attribute> floating;
}
{
  name = identifier() <EQ> <SIG>
  floating = floatingAttrs()         // 0 or more [@@@ … ] and (** … *)
  ( spec = spec() { specs.add(spec); } )*
  <END>
  ...
}
```

A floating doc comment at this position desugars to `[@@@ doc "..." ]`.

**5. AST additions.**

```java
public class Attribute extends AstNode {
  public final List<String> name;       // ["doc"], ["morel", "prototype"]
  public final @Nullable AttrPayload payload;
}

public abstract class AttrPayload extends AstNode {
  public static final class StringPayload extends AttrPayload { ... }
  public static final class IntPayload    extends AttrPayload { ... }
  public static final class IdentPayload  extends AttrPayload { ... }
  public static final class TuplePayload  extends AttrPayload { ... }
}
```

Each `Spec` subclass and `SignatureBind` gains
`List<Attribute> attributes`. `Shuttle` and `Visitor` get `visit`/copy
hooks for `Attribute`/`AttrPayload` — no-op for typing, since attributes
don't affect types.

**6. Validation.**

A new pass in the spirit of `SignatureChecker` walks the AST and:

* Reports unknown attribute names (whitelist: `doc`, `prototype`,
  `method`, `specified`, `extra`, `since`, `raise`, `see`, …).
* Checks payload types per attribute (`method` is a flag,
  `prototype` takes a string, `specified` takes `basis|morel`).
* Reports duplicates (one `doc` per spec, one `prototype` per spec).
* For floating attributes, checks `specified`, `description`, `overview`
  appear at most once.

This validation is what makes the move worthwhile: in the
comment-pairing world a typo (`@prototpe`) is silently ignored; in the
attribute world it fails the parser/lint at the line where it appears.

### Implementation phases

1. **Lexer + grammar + AST + Shuttle/Visitor.** No semantic consumers
   yet — attributes parse and round-trip but do nothing.
2. **Attribute validation pass.** Whitelist + payload type check.
3. **Doc-generator rewrite.** `Generation.java` reads attributes off the
   parsed AST; deletes its comment-pairing TODO before it was ever
   written.
4. **`functions.toml` field migration.** One field at a time, with
   `LintTest` cross-checking. `extra` and per-`val` `description` first
   (smallest), then `specified`/`method`/`prototype`, then structure
   `description`/`overview`, finally drop `functions.toml`.

### Sanity check: no syntactic conflicts

* `[ @ … ]` as written would be an empty list head `[` followed by an
  `@` infix operator (list-append) — but `@` requires a left operand, so
  `[@` at the start of an expression is currently a parse error. Safe to
  reuse as a single token.
* `(** *)` does not currently appear anywhere in real `.sig` or `.smli`
  source (block comments use `(* *)`). Safe to reserve.
* Attribute syntax appears only inside `sig … end` in phase 1 — outside
  that context, `[@` would still parse as the old error, so no
  silently-changed existing programs.

## Conversation timeline

* **Initial ask:** study what it would take to obsolete `functions.toml`
  and drive its generators from `.sig` files.
* **Analysis delivered:** full field-by-field gap analysis, consumer
  inventory, suggested incremental path.
* **`issue.md` created** summarising the work and prerequisites.
* **Clarifications:**
  * Ordinal → physical `.sig` order. Removed from open list.
  * `[[values]]` → fold into `datatype bool`. Removed from open list.
* **`discussion.md` created** (this file) to track the running discussion.
* **Doc-comment convention researched** across OCaml/Haskell/Rust/F#/
  Scala/Java/Swift. Recommendation: option A (ocamldoc-style
  `(** ... *)` + `@tag`s), with a clean path to promote to real
  attributes later if needed.
* **Parameter-name representation researched.** SML signatures are
  nameless; most modern languages keep names in the type. Recommend
  starting with an `@prototype` doc tag, with the option to promote to
  optional names on `val` specs (Successor-ML-style) if usage grows.
* **Switched to OCaml-style attributes** as the underlying mechanism.
  Doc comments `(** ... *)` desugar to a `doc` attribute. Eliminates
  comment-pairing fragility; typos become parse/validate errors.
  Detailed lexer/grammar/AST proposal added.

(*) End discussion.md
