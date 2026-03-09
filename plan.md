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
- Lines 171–181 (`sml`, illustrative translation): convert to `<!-- morel skip -->`.
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

### Step 3 — Implement `MarkdownProcessor`

Status: DONE

New class `net.hydromatic.morel.MarkdownProcessor`:
1. Reads a markdown file.
2. Detects `<!-- morel` blocks and parses attributes (`silent`, `skip`,
   `fail`, `env=NAME`).
3. Splits content into input/output segments.
4. For non-`skip` blocks: executes Morel code via the interpreter and
   compares actual vs. expected output.
5. Generates `<div class="morel">...</div>` HTML after each non-`silent`
   comment using `MorelHighlighter`.
6. In `--md` mode: replaces/inserts `<div class="morel">...</div>` blocks
   and updates `> ` lines if output differs. Writes file in-place.
7. In `--md-verify` mode: reports mismatches, exits non-zero if any.

Generated HTML format:
```html
<div class="morel">
<pre><code class="morel-input"><b>fun</b> len [] = 0
  | len (_ :: tl) = 1 + len tl;</code></pre>
<pre><code class="morel-output">val len = fn : <i>'a</i> list -> int</code></pre>
</div>
```

### Step 4 — Wire up CLI flags

Status: DONE

In `Main.java`, detect `--md FILE` and `--md-verify FILE` arguments and
dispatch to `MarkdownProcessor` instead of the REPL. Multiple files may be
specified.

### Step 5 — Refactor `Main.main()` into two methods

Status: TODO

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

Status: TODO

The name "markdown" is too narrow. The `<!-- morel -->` comment format works
in any file that can contain HTML comments — plain HTML, Jekyll templates,
AsciiDoc with passthrough blocks, etc. The concept is closer to a *notebook
kernel*: a document contains code cells, the kernel executes them and writes
the results back into the document.

Decide on better names for:
- The CLI flags (currently `--md` / `--md-verify`).
- The processor class (currently `MarkdownProcessor`).
- The feature name in user-facing documentation.

Candidate terms: `notebook`, `doc`, `literate`. Consider what similar tools
use (ocaml-mdx, Scala mdoc, Rust doctests) and what fits Morel's conventions.
Update class names, flag names, plan, and documentation accordingly.

### Step 7 — Generate initial HTML for the blog post (share repo)

Status: TODO

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
