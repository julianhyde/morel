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

# Plan: Issue #345 — Markdown code block validation and rendering

## Goal

Replicate the effect of Rouge + `after.sh` on the blog post
`blog/_posts/2026-03-09-datalog-in-morel.md` in the share repo, but produce
the syntax-highlighted HTML directly in the committed markdown, so the blog
no longer depends on Jekyll, Rouge, or `after.sh`.

The new format embeds Morel code in HTML comments (`<!-- morel ... -->`) and
generates `<div class="morel">...</div>` blocks with inline HTML highlighting.

## Steps

### Step 1 — Convert blog post to `<!-- morel -->` format (share repo)

Status: DONE

Transform each ` ```sml ` code block in
`blog/_posts/2026-03-09-datalog-in-morel.md` into the new comment format:

```markdown
<!-- morel
fun edge (x, y) = (x, y) elem [(1,2), (2,3)];
> val edge = fn : int * int -> bool
-->
```

Rules:
- Input lines become plain lines inside the comment.
- Lines wrapped in `(*[> ... ]*)` become `> ` prefixed output lines.
- The ` ```prolog ` Datalog block stays as a regular fenced block (not Morel).

Blocks to convert:
- Lines 100–108 (`prolog`): leave as fenced code block.
- Lines 120–131 (`sml`, has `(*[...]*)` output): convert to `<!-- morel -->`.
- Lines 171–181 (`sml`, illustrative): convert to `<!-- morel skip -->`.
- Lines 241–253 (`sml`, hybrid style example): convert to `<!-- morel skip -->`.

### Step 2 — Implement `MorelHighlighter`

Status: DONE

New class `net.hydromatic.morel.util.MorelHighlighter`. Tokenizes Morel
source and generates HTML:
- `<b>keyword</b>` for SML keywords plus Morel extensions (`from`, `yield`,
  `where`, `group`, `compute`, `join`, `exists`, `elem`, `orelse`, `andalso`,
  `not`, `union`, `intersect`, `except`, `distinct`, `order`, `unorder`,
  `desc`, `forall`, `require`, `skip`, `take`, `ordinal`, `current`, `over`,
  `inst`).
- `<i>'a</i>` for type variables (tokens starting with `'`).
- All text HTML-escaped (`<`, `>`, `&`).

For output lines, apply lighter treatment: escape HTML and italicize type
variables only.

### Step 3 — Implement `Darn`

Status: DONE (as `MarkdownProcessor`; rename in Step 6)

New class `net.hydromatic.morel.Darn` — a Morel notebook kernel that
processes documents containing embedded Morel cells:
1. Reads a document file (markdown, HTML, or any file with HTML comments).
2. Detects `<!-- morel` cell blocks and parses attributes (`silent`, `skip`,
   `fail`, `env=NAME`).
3. Splits each cell's content into input/output segments.
4. For non-`skip` cells: executes Morel code via the interpreter and
   compares actual vs. expected output.
5. Generates `<div class="morel">...</div>` HTML after each non-`silent`
   cell using `MorelHighlighter`.
6. In `--darn` mode: replaces/inserts `<div class="morel">...</div>` blocks
   and updates `> ` lines if output differs. Writes file in-place.
7. In `--darn-verify` mode: reports mismatches, exits non-zero if any.

Generated HTML format (content abbreviated):
```html
<div class="morel">
<pre class="morel-input"><code>...</code></pre>
<pre class="morel-output"><code>...</code></pre>
</div>
```

### Step 4 — Wire up CLI flags

Status: DONE (as `--md`/`--md-verify`; rename in Step 6)

In `Main.java`, detect `--darn FILE` and `--darn-verify FILE` arguments and
dispatch to `Darn` instead of the REPL. Multiple files may be specified.

### Step 5 — Refactor `Main.main()` into two methods

Status: DONE

Split `Main.main(String[])` into:
- `Main.run(String[]) : int` — does all the work and returns an exit code
  (0 for success, 1 for errors).
- `Main.main(String[]) : void` — thin wrapper that calls `run()` and passes
  the exit code to `System.exit()`.

This makes the logic testable without spawning a subprocess, and is the
conventional pattern for Java CLI tools. The `--md-verify` failure path
currently calls `System.exit(1)` directly; after this refactor it returns 1
from `run()` and only `main()` calls `System.exit`.

### Step 6 — Revisit feature and class naming

Status: DONE

The name "markdown" is too narrow. The `<!-- morel -->` comment format works
in any file that can contain HTML comments — plain HTML, Jekyll templates,
AsciiDoc with passthrough blocks, etc. The concept is closer to a *notebook
kernel*: a document contains code cells, the kernel executes them and writes
the results back into the document.

**Decision**: use "darn" throughout.

- CLI flags: `--darn` / `--darn-verify` (replacing `--md` / `--md-verify`).
- Processor class: `Darn` (replacing `MarkdownProcessor`).
- `MorelHighlighter` is unchanged — it is a Morel-specific utility,
  not coupled to the document format.

**Inspiration**: Donald Knuth's literate programming system (1984) introduced
TANGLE (extracts runnable code from a document) and WEAVE (produces a
typeset document with syntax highlighting). Darn is in the spirit of WEAVE:
it takes a document containing embedded Morel *cells*, executes them via a
Morel *kernel*, and weaves the highlighted output back into the document.

Unlike WEAVE, Darn operates on documents that already contain both the code
and the prose (no separate source file), and it updates the document in-place
rather than producing a separate output file. This is closer to the model
used by Jupyter notebooks, where a kernel executes cells embedded in the
document and the results are stored alongside the code. Darn can be thought
of as a Morel notebook kernel that works on plain text files (markdown, HTML,
or any file that accepts HTML comments) rather than the `.ipynb` JSON format.

### Step 7 — Generate initial HTML for the blog post (share repo)

Status: DONE

Run `./morel --md blog/_posts/2026-03-09-datalog-in-morel.md` against the
converted blog post to:
1. Execute `<!-- morel -->` blocks and validate output.
2. Insert `<div class="morel">...</div>` blocks with syntax highlighting.
3. Write the updated markdown in-place.

### Step 8 — Tests

Status: TODO

New test class `MarkdownTest` (or renamed equivalent after Step 6) that
processes markdown fixtures in `src/test/resources/` and validates generated
HTML.

## Completed steps

### Step 1 — Convert blog post to `<!-- morel -->` format (share repo)

Converted `blog/_posts/2026-03-09-datalog-in-morel.md` in the share repo:
- `prolog` block (lines 100–108): left as-is.
- `sml` block with `(*[...]*)` output (lines 120–131): converted to
  `<!-- morel -->` with `> ` prefixed output lines.
- `sml` illustrative block (lines 171–181): converted to `<!-- morel skip -->`.
- `sml` hybrid-style block (lines 241–253): converted to `<!-- morel skip -->`.
