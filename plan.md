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

# Plan: `Word` structure (issue #396)

Implement the Standard ML Basis `Word` structure and the `word` type.

## Decisions (locked)

- **`wordSize` = 64.** A `word` is backed by a Java `long`. Java 8's
  `Long.*Unsigned` helpers give full 64-bit unsigned semantics with no
  masking. (SML/NJ reports 63 only because of its tagged runtime.)
- **`word` is a `PrimitiveType`, not an `Eqtype`.** The literal pipeline
  (Ast -> Core -> Code) only carries `PrimitiveType` values. `int.sig`
  already declares `eqtype int` for `PrimitiveType.INT`, so `word.sig`'s
  `eqtype word` stays consistent.
- **Word literals in the grammar:** `0w255` (decimal), `0wx1f` (hex).
- **REPL prints words SML-style:** `0w255` (a `Pretty.java` branch).
- **`LargeWord.word` = `word`**: `toLarge`/`toLargeX`/`fromLarge` and the
  deprecated `toLargeWord*` are identity.
- **`LargeInt.int` = `int`** (32-bit). So `toLargeInt`/`toLargeIntX` raise
  `Overflow` for words that don't fit in `int`; documented honestly.
- **Sorted-switch convention:** in ordered/semi-ordered switches, `word`
  sorts after `unit` (alphabetically `unit` < `word`).

## Cross-check findings (word.sig vs SML spec)

Docs drawn from <https://smlfamily.github.io/Basis/word.html>, cross-checked
against SML, adjusting only for Morel's 64-bit `word`:

1. Member docs are written in terms of `2^wordSize`, so they port
   unchanged; only `wordSize`'s own doc states the concrete value (64).
2. `LargeWord = word` => the `toLarge*`/`fromLarge*` family is identity.
3. `LargeInt = int` (32-bit) vs 64-bit `word`: `toLargeInt`/`toLargeIntX`
   deviate from the spec's "never raises" - flagged. Revisit if `LargeInt`
   becomes `IntInf`.
4. `scan` is commented out (needs `StringCvt.reader`, only partially
   implemented) - same convention as `int.sig`.

## Steps

Each step ends with `fullMake` (green) and a commit on branch `396-word`.

### Step 1 - `word` type + literals + pretty-printing

- `type/PrimitiveType.java`: add `WORD` (after `STRING`, before `UNIT`).
- `MorelParser.jj`: `WORD_LITERAL` (`0w` + digits) and `WORD_HEX_LITERAL`
  (`0wx` + hex) tokens; `wordLiteral()` production wired into `literal()`,
  which feeds both expressions and patterns.
- `Op.java`: `WORD_LITERAL` and `WORD_LITERAL_PAT` (and `toPat()`).
- `Ast.java` / `Core.java`: allow `WORD_LITERAL_PAT` in the `LiteralPat`
  op whitelists.
- `AstBuilder.java` / `CoreBuilder.java`: `wordLiteral` builders; `WORD`
  case in `CoreBuilder.literal`.
- `TypeResolver.java`: type word literal/pattern as `PrimitiveType.WORD`.
- `Resolver.java`: lower `WORD_LITERAL`/`WORD_LITERAL_PAT` to Core.
- `Compiler.java`: compile `WORD_LITERAL` to a constant `Long`.
- `Closure.java`: match `WORD_LITERAL_PAT` (both matchers, via `.equals`).
- `PatternCoverageChecker.java`, `Generators.java`: handle the new pat op.
- `Pretty.java`: print `word` as `0w` + `Long.toUnsignedString`.
- Tests: word-literal parse/type/print/equality/match in a language script.
- NOTE: `lib/word.sig` is held out of the tree until Step 2, because
  `LintTest` is signature-driven - a `word.sig` forces the whole structure,
  doc page, and reference.md row to exist.

### Step 2 - `Word` structure

- `BuiltIn.java`: `WORD_*` constants (alphabetical). `LintTest.testSignatures`
  emits the exact expected entries (lookup by name `ts.lookup("word")`).
- `Codes.java`: an `Applicable` per function over `Long`; register in
  `CODES`. Use `Long.*Unsigned`. Shifts guard amount >= 64.
- `lib/word.sig`: restore the drafted file; reconcile with BuiltIn.
- `src/test/resources/script/built-in/word.smli`: idempotent tests modeled
  on `int.smli`/`time.smli`. Generate `>` output by running and pasting back.
- Update env-count tests in `built-in/sys.smli` / `misc.smli` if affected.

### Step 3 - Documentation (requested)

- `docs/lib/word.md`: new page with license header and
  `start:lib/word`/`end:lib/word` markers; content from `lib/word.sig`.
- `docs/lib/index.md` and `docs/reference.md`: add `Word` entries.
- Run `LintTest`; paste generated content between markers.

### Step 4 - Review structure-creation process doc (requested)

- Review the "Adding a Standard Basis Library Structure" process (currently
  in `CLAUDE.md`; the request named it `agents.md`, which does not exist).
  Decide: update `CLAUDE.md` or introduce `AGENTS.md`.
- Add what `Word` taught: the new-PrimitiveType + literal-syntax path
  (grammar/AST/TypeResolver/Closure/Pretty), the literal-op whitelists in
  `Ast`/`Core`, that `word.sig` forces the full structure under lint, and
  the `LargeInt`-is-fixed-precision gotcha.

## Verification

`fullMake` green after every step. Commits end with the `Co-Authored-By`
trailer; the final structure commit references `Fixes #396`.
