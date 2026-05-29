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

# Add `Core` structure representing Morel expressions and types

## Summary

First slice of #359 (the planner / rewrite-rule infrastructure).
This issue carves off the smallest end-to-end piece: surface
Morel's typed, post-desugar internal representation as a
first-class datatype in the standard library, give types a
parallel datatype, and add a single compile-time form
(`Plan.core`) to reify any Morel expression as a `Core.expr`
value.

No rules, no MEMO, no reactor, no closure-rewriting — those are
tracked as later phases of #359.

## Proposal

Add three structures to the standard library.

### `Type`

A datatype `t` covering Morel types, plus pure observers.

```sml
signature TYPE = sig
  datatype t
    = INT | REAL | BOOL | STRING | CHAR | UNIT
    | VAR    of int
    | TUPLE  of t list
    | RECORD of (string * t) list
    | FN     of t * t
    | LIST   of t
    | BAG    of t
    | DATA   of {name : string, args : t list}

  val eq       : t * t -> bool
  val toMorel  : t -> string
  val freeVars : t -> int list
end
```

`TUPLE` is kept distinct from `RECORD` even though morel-java
internally represents tuples as records with numeric field
names, because they are distinct at the Morel surface and
`toMorel` needs the distinction to print readable output.
`LIST` and `BAG` get dedicated constructors because they are
load-bearing for relational semantics. Other built-in
datatypes (`option`, `ref`, `exn`, …) and user-defined
datatypes go through `DATA`. `VAR of int` gives type variables
unique ids; `eq` is alpha-equivalence, `toMorel` normalizes
ids to `'a`, `'b`, … in occurrence order.

### `Core`

Datatypes `pat` and `expr` mirroring morel-java's internal
`Core.Exp` hierarchy, with dedicated tags for the common
operators and one for each relational-algebra operator.
Everything else (less common built-ins, user-defined
functions) goes through `APPLY`.

```sml
signature CORE = sig
  type typ = Type.t

  datatype pat
    = P_VAR     of {name : string, typ : typ}
    | P_WILD    of typ
    | P_LITERAL of expr
    | P_CON     of {con : string, args : pat list, typ : typ}
    | P_RECORD  of (string * pat) list
    | P_TUPLE   of pat list

  and expr
    (* literals *)
    = INT_LITERAL    of int
    | REAL_LITERAL   of real
    | BOOL_LITERAL   of bool
    | STRING_LITERAL of string
    | CHAR_LITERAL   of char
    | UNIT_LITERAL
    | LIST_LITERAL   of {elems : expr list, typ : typ}
    | FN_LITERAL     of {param : pat, body : expr, typ : typ}

    (* names, application, binding *)
    | VAR    of {name : string, typ : typ}
    | APPLY  of {fn_ : expr, arg : expr, typ : typ}
    | LET    of {bindings : (pat * expr) list, body : expr}
    | LETREC of {bindings : (string * expr) list, body : expr}

    (* records and tuples *)
    | RECORD of {fields : (string * expr) list, typ : typ}
    | TUPLE  of expr list
    | FIELD  of {record : expr, name : string, typ : typ}

    (* boolean (n-ary AND/OR) *)
    | AND of expr list
    | OR  of expr list
    | NOT of expr

    (* comparison *)
    | EQUALS     of expr * expr
    | NOT_EQUALS of expr * expr
    | LT         of expr * expr
    | LE         of expr * expr
    | GT         of expr * expr
    | GE         of expr * expr

    (* arithmetic *)
    | PLUS   of expr * expr * typ
    | MINUS  of expr * expr * typ
    | TIMES  of expr * expr * typ
    | DIVIDE of expr * expr * typ
    | MOD    of expr * expr * typ
    | NEG    of expr * typ

    (* relational algebra *)
    | FILTER    of {input : expr, condition : expr}
    | PROJECT   of {input : expr, expression : expr}
    | JOIN      of {left : expr, right : expr, condition : expr}
    | UNION     of {all : bool, inputs : expr list}
    | INTERSECT of {all : bool, inputs : expr list}
    | EXCEPT    of {all : bool, left : expr, right : expr}
    | GROUP     of {input : expr, key : expr, agg : expr}
    | ORDER     of {input : expr, by : expr}
    | LIMIT     of {input : expr, count : expr}
    | SKIP      of {input : expr, count : expr}

    (* control flow *)
    | IF     of {cond : expr, thn : expr, els : expr}
    | CASE   of {scrutinee : expr, branches : (pat * expr) list,
                 typ : typ}
    | RAISE  of {body : expr, typ : typ}
    | HANDLE of {body : expr, branches : (pat * expr) list}

  val typeOf   : expr -> typ
  val freeVars : expr -> (string * typ) list
  val toMorel  : expr -> string
  val eq       : expr * expr -> bool
end
```

Notable shape decisions:

- **`AND` / `OR` are n-ary.** Empty list = unit element
  (`AND []` ≡ `true`, `OR []` ≡ `false`). Singleton prints as
  the element; two-or-more as `e1 andalso e2 andalso …`.
- **No `SCAN` constructor.** The leaf of a relational chain
  is any expression that has bag/list type — typically a
  `VAR` referring to a bound table value, but it could be a
  `LIST_LITERAL`, an `APPLY`, or another relational op.
- **No `DISTINCT` constructor.** Desugared to `GROUP` with an
  identity key and an empty agg.
- **Comparison operators carry no `typ`.** Result is always
  `bool`; operand type is recoverable via `Core.typeOf`.
- **Arithmetic operators carry `typ`** to disambiguate
  int-vs-real at construction time without a `typeOf` walk.

### `Plan`

One value, compile-time intercepted. The argument is
type-checked but not evaluated; the resolver/compiler
recognizes the call and emits a literal `Core.expr` value at
the call site. This is analogous to `typeof`.

```sml
signature PLAN = sig
  (*) Reify the typed Core of an expression. *)
  val core : 'a -> Core.expr
end
```

Free variables in the argument resolve in the enclosing
environment for type-checking, but appear in the output as
`VAR { name = …, typ = … }` — they are not folded to their
runtime values.

## Worked examples

Two acceptance gates, each a self-contained milestone.

### M1 — scalar smoke test

```sml
Plan.core (1 + 2);
> val it = PLUS (INT_LITERAL 1, INT_LITERAL 2, INT) : Core.expr

Core.toMorel (Plan.core (1 + 2));
> val it = "1 + 2" : string
```

Forces the smallest end-to-end path: `Plan.core` interception
→ `Core.expr` reification → `Core.toMorel` rendering → `Type`
carried on the `PLUS` node. Once this passes, the wiring is in
place.

### M2 — relational query

```sml
val emps : {ename:string, deptno:int} bag = bag [];

Plan.core
  (from e in emps
     where e.deptno elem [10, 20]
     yield {e.ename, e.deptno});
> val it = PROJECT { … FILTER { … VAR {name="emps", …} } } : Core.expr
```

Forces:

- `from` / `where` / `yield` desugaring to
  `PROJECT` / `FILTER` / `VAR`,
- `elem` resolved through `APPLY` of a named built-in (no
  dedicated tag — confirms the catch-all path),
- `LIST_LITERAL` for `[10, 20]`,
- record construction in the `yield` (`RECORD` with the
  implicit `e.ename` / `e.deptno` field shorthand expanded),
- `VAR` at the relational leaf (no `SCAN`).

Both must round-trip through `Core.toMorel` to a parseable
Morel string.

## Test

Add `src/test/resources/script/plan.smli`. Even though phase
1a is mostly `Core`, the entry point under test is `Plan.core`.

The test must cover, at minimum:

- M1 and M2 above.
- One example for every constructor of `Core.expr` and
  `Type.t`.
- One call each to `Core.typeOf`, `Core.freeVars`, `Core.eq`,
  `Core.toMorel`, and `Type.toMorel`.

`ScriptTest` picks up the file automatically; no new Java test
class is required.

## Out of scope

Tracked as later phases of #359:

- Rules and the reactor: `pat`, `guard`, `rule`, `program`,
  strategies, `Plan.optimize`, `Plan.optimizeOp`,
  `Plan.optimizeRoot`.
- The MEMO: `group`, `memo`, `Ref` constructor, `Plan.explore`,
  `Plan.allPlans`.
- Closure surgery: `Plan.bodyOf`, `Plan.plannable`,
  `Plan.runWith`.
- `planRoot`, `Plan.ofCore` / `Plan.toCore`.
- `Core.parse` (runtime parsing of Morel source to
  `Core.expr`).
- Specific rewrite rules.

## Notes

- Hand-written `Core` and `Type` structures for now; a
  `LintTest` can be added later to check that the Morel-level
  datatypes stay in sync with morel-java's internal `Core.Exp`
  hierarchy.
- `Plan.core` and `typeof` share the same general pattern of
  compiler-intercepted call: the argument is type-checked, the
  result is a meta-level value (a type or an expression),
  evaluation of the argument is suppressed.
- The full design dialogue is in `discussion.md` on branch
  `359-planner`.
