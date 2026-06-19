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
# Plan: switch to a Doc-based pretty-printer (#398, #339)

## Terminology

The issue calls for an "Oppen" printer. The `julianhyde/281-fmt` branch
already implements a **Wadler-Leijen** Doc printer (Lindig's strict
variant). The two produce the same layouts — "break the outermost group
that doesn't fit, nest consistently" — that match SML/NJ's style, and
Wadler-Leijen is already written and tested, so we reuse it rather than
writing Oppen from scratch. (SML/NJ internally uses an Oppen PP, but the
Doc algebra reproduces its layouts; SML's per-construct quirks become
`group`/`nest`/`align` choices — see "How §1/§2/§3 fall out".)

## What already exists

Most of the machinery is on `281-fmt` or already in `main`:

- `util.Pretty` (Java) — the full Doc algebra (`text`, `beside`, `nest`,
  `group`, `align`, `hang`, `sep`, `cat`, `fillSep`, `encloseSep`,
  `parens`, `braces`, `brackets`, `punctuate`) plus `render(width, doc)`.
- Morel `Pretty` structure (#339) — the same algebra exposed to Morel
  code: a `Pretty.doc` datatype and ~30 builtins, including
  `Pretty.render`.
- `AstToDoc` (Java) — converts a Morel AST to `Pretty.Doc`; the template
  for a value-to-Doc converter (but for source, not runtime values).
- `variant` (in `main`) — a dynamically-typed universe datatype
  (`INT` / `REAL` / `LIST` / `RECORD` / `CONSTRUCT` / ...), backed by
  `eval.Variant(Type, Object)`. This is the bridge for accessing Java
  values from Morel (see "Accessing Java values").

What is missing is only the **runtime-value-to-Doc converter** — the
analog of `AstToDoc`, replacing the greedy `compile.Pretty`.

## Components and where each lives

| # | Component | Morel / Java | Status | Notes |
|---|---|---|---|---|
| 1 | Doc engine (algebra + `render`) | Java (`util.Pretty`), mirrored as the Morel `Pretty` structure | reuse `281-fmt` | host primitive; a Rust port mirrors it |
| 2 | Value reflection, `'a -> variant` | Java (host-specific, type-directed; `eval.Variant.of`) | mostly exists | the only code that touches raw Java objects |
| 3 | `variant` universe datatype | Morel | exists (`main`) | the shared, typed bridge |
| 4 | Value-to-Doc formatter (`variant -> Pretty.doc`): the policy for how each type maps to Docs and where to `group`/`nest` | Morel (recommended) | new | pure and host-independent, so shared by morel-java and morel-rust |
| 5 | REPL wiring (replace greedy `compile.Pretty`) | Java (`Compiler`, `Session`, `Shell`) | modify | reflect value to variant, call the formatter, `render` |

Component 4 is the real work; the rest is reuse or glue.

## Accessing Java values with polymorphic types (the key question)

Morel code does not touch Java values directly. The bridge is the
existing `variant` type:

- A **type-directed builtin** `'a -> variant` (backed by
  `eval.Variant.of(Type, Object)`). The compiler resolves `'a` to a
  concrete type at the call site and feeds it to the builtin — exactly
  how `Relational.compare` obtains a `Comparator` from
  `Comparators.comparatorFor(type)`. The polymorphism is discharged at
  the builtin boundary: compile-time type + runtime Java object produce a
  fully-typed `variant`.
- Above that boundary the formatter is ordinary **monomorphic Morel**
  over `variant`:

  ```
  fun toDoc UNIT          = Pretty.text "()"
    | toDoc (INT n)       = Pretty.text (...)
    | toDoc (RECORD fs)   = group (braces (...))
    | toDoc (LIST xs)     = group (brackets (...))
    | ...
  ```

  No reflection, no `'a`, no Java objects in Morel.

So the host-specific surface shrinks to (1) the Doc primitives and (2)
the `'a -> variant` reflect — both small and already largely present in
Java; a Rust port implements the same two. All the formatting logic
(Component 4, where SML's quirks live) is written once, in Morel.

## How §1, §2, §3 fall out

In the Doc model the three differences stop being separate patches:

- **§3 (2-column right margin):** a parameter to `render` (use
  `width - 2`), set once.
- **§1 (type-continuation indent):** the `nest` / `align` amounts in the
  record/list Doc; a list element gets one extra `nest` level.
- **§2 (eager record-type wrapping):** wrap each record type in a `group`
  with a *consistent* line (`vsep` / `cat`) rather than `fillSep`, so it
  breaks all-or-nothing — which is exactly why SML breaks a record type
  that would otherwise fit.

One converter, verified against SML/NJ with the existing fuzzer,
replaces all three offset patches.

## Phasing (each step shippable)

0. **Land the `281-fmt` engine on `main`** — `util.Pretty`, the Morel
   `Pretty` structure, and `PrettyTest`. Independent, tested, no
   behavior change.
1. **Bootstrap the value-to-Doc converter in Java** (fastest), wired
   into the classic-output path behind a flag; encode §1/§2/§3; verify
   with the fuzzer. Fixes the #398 body for morel-java.
2. **Cut over and regenerate** — make it the default, delete the greedy
   `compile.Pretty`, and regenerate the `.smli` and Java expected
   outputs once (instead of the 22/32-file churn per section).
3. **Port the converter to Morel** (`variant -> Pretty.doc`) for the
   cross-engine goal; morel-rust then reuses the formatter via `variant`
   plus the `Pretty` structure, and Java keeps only the engine and the
   reflect.

Tabular mode (`TabularPrinter`) is a separate layout and stays as-is for
now; it could later be re-expressed as Docs but is not on this path.

## Recommendation

If the near-term goal is matching SML/NJ in morel-java, do 0 -> 1 -> 2
(Java converter) and stop; reach for step 3 when unifying with
morel-rust becomes the priority. The expensive, fragile part — SML's
exact wrapping — is written once either way, in the Component-4
converter.
