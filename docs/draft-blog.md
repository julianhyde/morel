# Sorting on expressions

In Morel's query syntax, almost everything is an expression. The
`yield` step has an expression (whereas SQL's SELECT has a list of
expressions with optional aliases); the scan in a `join` step is over
an expression, not necessarily a query; in the `group` step, an
aggregate function may be a (function-valued) expression.

Making everything an expression pays dividends. Queries can return a
collection of any value, not just records. You can easily join a
collection to a set of nested records (say an order to its nested
order-lines). If you need a custom aggregate function, you can roll
your own. And each of these expressions can be made into function
arguments, so that you can parameterize your query.


```
step → distinct
    | except [ distinct ] exp , ... , exp
    | group groupKey1 , ... , groupKeyg [ compute agg1 , ... , agga ]
    | intersect [ distinct ] exp , ... , exp
    | join scan , … scan
    | order orderItem , ... , orderItem
    | skip exp
    | take exp
    | union [ distinct ] exp1 , ... , expu
    | where exp
    | yield exp

scan → pat in exp [ on exp ]

orderItem → exp [ desc ]

groupKey → [ id = ] exp

agg → [ id = ] exp [ of exp ]
```

[ github com hydromatic/morel/blob/main/docs/query.md#syntax ]

The `order` step is a stubborn holdout. Its argument is a list of
`orderItem` s, each of which is an expression with an optional `desc`
keyword.

We can't just get rid of the `desc` keyword and covert the list to a
singleton. Real queries require complex sorting behaviors like
composite keys, descending keys, and nulls-first or nulls-last. So,
how can we put all that complexity in a single expression?

One approach is to do what many programming languages do, and use a
comparator function.

## Comparator functions

In Standard ML, a comparator function is any function that takes a
pair of arguments of the same type and returns a value of the `order`
enum (`LESS`, `EQUAL`, `GREATER`). Its type is
`alpha * alpha -> order`.

For int, I can write a simple function:

```
fun compareInt (x: int, y: int) : order =
    if x < y then LESS
    else if x > y then GREATER
    else EQUAL;

val compareInt: (int * int) -> order;
```

In fact, most data types have a built-in `compare` function:

```
Int.compare;
val it = fn : int * int -> order

String.compare;
val it = fn : string * string -> order
```

For more complex orderings, I can write a comparator that combines
other comparators. For example, this function compares a list of
(string, real) pairs, the string first, then the real descending:

```
fun compareIntStringPair ((i1, s1), (i2, s2)) =
    case String.compare (s1, s2) of
        EQUAL => Int.compare (i1, i2)
      | result => result;
> val compareIntStringPair =
>   fn : int * string * (int * string) -> order
```

If we were to add comparators to Morel, we could add `order using`
syntax like this:

```
(*) Sort employees by job, and then by descending salary
from e in scott.emps
  order using fn (emp1, emp2) =>
    case String.compare (emp1.job, emp2.job) of
       EQUAL => Real.compare (emp2.sal, emp1.sal)
     | result => result;
```

But this is much longer than the equivalent in SQL. Comparator
functions are clearly powerful, but we are not living up to the "Make
simple things simple, make hard things possible" principle.

Let's abandon comparator functions and go back to sorting values.

## Structured values for complex orderings

The idea is that two values of the same type can be compared - and
that comparison is determined by the type. For complex sorting
behaviors, you use complex types.

For a composite ordering, you use a tuple type. Morel compares the
values lexicographically.

For a descending ordering, you wrap the value in the `descending` data
type using its `DESC` constructor. Morel compares the values in the
usual way, then reverses the direction.

Two tricks. First, Morel is effectively generating a comparator
function at compile time based on the type of the query.

Second, for all the common orderings, sort expressions are virtual.
If the query
```
from e in scott.emps
  order (e.job, DESC e.sal);
```
created a tuple `(e.job, DESC(e.sal))` for every element, we would
worry about the impact on performance, but those tuples are never
constructed. Morel operates on the employee records `e` directly,
and the performance is the same as if we had specified the ordering
using a list of order-items or a comparator function.

## Future work

There are a few things to be solved in the future.

### NULLS FIRST and NULLS LAST

SQL has `NULLS FIRST` and `NULLS LAST` keywords to control how nulls
are sorted. Morel uses the `option` type rather than `NULL` represent
optional values, but the same requirement exists.

Currently, the behavior is the same as SQL's `NULLS FIRST`.
`option` is a datatype declared as follows:

```
datatype option 'a = NONE | SOME 'a;
```

Therefore `NONE` will sort lower than all `SOME` values:
```
from i in [SOME 1, SOME ~100, NONE]
  order i;
> val it = [NONE, SOME ~100, SOME 1] : int option list
```

We haven't yet figured out how to express the equivalent of `NULLS
LAST`.  One idea is to add a `noneLast` datatype

```
datatype 'a noneLast = NONE_LAST of 'a;
```

and use it in a query like this:

```
from i in [SOME 1, SOME ~100, NONE]
  order NONE_LAST i;
> val it = [SOME ~100, SOME 1, NONE] : int option list
```

When we use `NONE_LAST` and `DESC` together in a query

```
from i in [SOME 1, SOME ~100, NONE]
  order DESC (NONE_LAST i);
> val it = [NONE, SOME 1, SOME ~100] : int option list
```

the `NONE` value appears first. It's what we asked for,
but not what we expected if we were expecting `DESC`
and `NONE_LAST` to commute.

Until we figure out something intuitive, we won't have a
solution for `NULLS LAST` yet.

### Comparator functions

A syntax <code>order using *comparator*</code>.

## Conclusion

Now the `order` step takes an expression, what we can we do? We can
pass the expression as an argument to a function.

What about the `order using` syntax and comparator functions? We still
might go there (especially for the "Make hard things possible"). We
are still using comparator functions — the `Relational.compare`
function is generating a comparator at compile time based on the type
of the query.

Are structured values strictly less powerful than comparator
functions? It's an interesting theoretical question, and I honestly
don't know. A comparator function can be an arbitrarily complex piece
of code — but we could provide a complex type that match that And we
have created `Relational.compare`. (It is a somewhat strange function
because its type is an implicit argument.)

# Morel release 0.7.0

I am pleased to announce Morel
[release 0.7.0](https://github.com/hydromatic/morel/blob/main/HISTORY.md#070--2025-06-07),
just one month after
[release 0.6.0](https://github.com/hydromatic/morel/blob/main/HISTORY.md#060--2025-05-02).

This has been an intense month of development, building on the
foundational work I mentioned in the previous release. The type system
enhancements and collection type work that was "not yet fully baked"
in 0.6.0 have now come to fruition in what I'm calling a "huge release
with major changes to query syntax and semantics."

The centerpiece of this release is support for
[ordered and unordered collections](#1-ordered-and-unordered-collections),
which required implementing [operator overloading](#2-operator-overloading)
to handle the complexity of multiple collection types. We've also
added
[new string manipulation capabilities](#3-string-and-char-structures)
and made
[significant improvements to query syntax](#4-query-syntax-improvements).

Let's explore the key features. For complete details, see the
[official release notes](https://github.com/hydromatic/morel/blob/main/HISTORY.md#070--2025-06-07).

## 1. Ordered and unordered collections

The biggest change in 0.7.0 is the introduction of
[ordered and unordered collections](https://github.com/hydromatic/morel/issues/273).
Previously, Morel had `list` (ordered) and `set` (unordered, no
duplicates). Now we add `bag` for unordered collections that allow
duplicates.

This might seem like a small addition, but it has profound
implications for how queries work. Consider this query:

```sml
from e in scott.emps
  where e.sal > 1000.0
  yield e.ename
```

In previous versions, this would always return a `list`. But what if
you don't care about the order? What if you want to emphasize that the
result is a collection where duplicates matter but order doesn't?

With the new
[bag type](https://github.com/hydromatic/morel/issues/235), you can be
explicit:

```sml
from e in scott.emps
  where e.sal > 1000.0
  yield e.ename
: string bag
```

The `bag` type represents unordered collections with duplicates
allowed – exactly what you get from most SQL queries. This makes
Morel's type system more precise about the semantics of your data.

### Controlling order with `unorder`

The new
[`unorder` step](https://github.com/hydromatic/morel/issues/277)
lets you explicitly convert ordered collections to unordered ones:

```morel
from e in scott.emps
  order e.sal desc
  unorder
  yield e.ename
```

This query sorts employees by salary but then removes the ordering,
yielding a `bag` rather than a `list`. This is useful when you need
intermediate ordering for a computation but don't want to preserve
that ordering in the final result.

### Ordinal expressions

When working with ordered collections, you sometimes need to know the
position of elements. The new
[`ordinal` expression](https://github.com/hydromatic/morel/issues/276)
provides this:

```sml
from e in scott.emps
  order e.sal desc
  yield {e.ename, rank = ordinal}
```

This assigns a rank to each employee based on their salary, with
`ordinal` providing the 1-based position in the ordered sequence.

## 2. Operator overloading

Supporting multiple collection types required a major enhancement to
Morel's type system:
[operator overloading](https://github.com/hydromatic/morel/issues/237).
This allows operators to work with different types in a type-safe way.

The feature introduces two new keywords: `over` and `inst`. Here's how
they work:

```sml
(* Define an overloaded operator for different collection types *)
val op + = fn (x : int list, y : int list) => x @ y
         | (x : int bag, y : int bag) => Bag.union x y
         | (x : int set, y : int set) => Set.union x y
  over int collection

(* Use the operator with different collection types *)
val list_result = [1, 2, 3] + [4, 5, 6]
val bag_result = Bag.fromList [1, 2, 3] + Bag.fromList [4, 5, 6]
val set_result = Set.fromList [1, 2, 3] + Set.fromList [4, 5, 6]
```

The `over` keyword declares that an operator is overloaded for a
family of types, while `inst` can be used to select specific instances
when the type system needs disambiguation.

This makes Morel's collection operations much more natural. The same
`+` operator can mean concatenation for lists, union for bags, and
union for sets – the type system chooses the right implementation
based on the operand types.

## 3. String and Char structures

Morel now includes complete
[`String`](https://github.com/hydromatic/morel/issues/279) and
[`Char`](https://github.com/hydromatic/morel/issues/264) structures
following the
[Standard ML Basis Library](https://smlfamily.github.io/Basis/)
specification.

This gives you comprehensive text manipulation capabilities:

```sml
(* String manipulation *)
String.size "hello world";
> val it = 11 : int

String.substring ("hello world", 6, 5);
> val it = "world" : string

String.tokens (fn c => c = #" ") "hello world morel";
> val it = ["hello","world","morel"] : string list

(* Character operations *)
Char.isAlpha #"a";
> val it = true : bool

Char.toUpper #"a";
> val it = #"A" : char

String.map Char.toUpper "hello";
> val it = "HELLO" : string
```

These structures provide everything you need for serious text
processing, from basic operations like substring extraction to
advanced features like tokenization and character classification.

## 4. Query syntax improvements

Beyond the major collection type changes, 0.7.0 includes several
quality-of-life improvements to query syntax.

### Simplified order syntax

The syntax for the
[`order` step has been simplified](https://github.com/hydromatic/morel/issues/244). The
`desc` keyword has been removed in favor of a more natural approach:

```morel
(* Old syntax *)
from e in scott.emps
  order e.sal desc
  yield e.ename

(* New syntax *)
from e in scott.emps
  order e.sal descending
  yield e.ename
```

### Current row reference

The new
[`current` keyword](https://github.com/hydromatic/morel/issues/265)
lets you reference the current row within complex queries:

```morel
from e in scott.emps
  where e.sal > (
    from e2 in scott.emps
    where e2.deptno = current.deptno
    compute avg of e2.sal)
  yield e.ename
```

This makes correlated subqueries more readable by explicitly showing
which row you're referencing.

### Flexible pipeline steps

[Set operators can now be used as steps](https://github.com/hydromatic/morel/issues/253)
in query pipelines:

```morel
from e in scott.emps
  where e.deptno = 10
  union
  from e in scott.emps
    where e.deptno = 20
    yield e.ename
```

Additionally,
[atomic `yield` steps can appear anywhere](https://github.com/hydromatic/morel/issues/262) in the
pipeline, not just at the end:

```morel
from e in scott.emps
  yield e.ename
  where String.size e.ename > 5
```

## 5. Breaking changes

This release includes some breaking changes to be aware of:

### Database schema updates

The `scott` sample database now uses
[pluralized table names](https://github.com/hydromatic/morel/issues/255)
following modern conventions:

```morel
(* Old *)
from e in scott.emp
  join d in scott.dept on e.deptno = d.deptno

(* New *)
from e in scott.emps
  join d in scott.depts on e.deptno = d.deptno
```

### Type-based orderings

The new
[`Descending` datatype](https://github.com/hydromatic/morel/issues/282)
and `Relational.compare` method provide more sophisticated ordering
capabilities, but may require updates to code that relied on the
previous ordering behavior.

## Conclusion

Release 0.7.0 represents a major evolution in Morel's
capabilities. The ordered/unordered collection distinction makes the
type system more precise about data semantics, while operator
overloading provides the flexibility needed to work naturally with
different collection types.

The enhanced query syntax, comprehensive string handling, and improved
type inference make Morel more powerful for data processing
tasks. These changes lay the groundwork for even more ambitious
features in future releases.

As always, you can get started with Morel by visiting
[GitHub](https://github.com/hydromatic/morel) – you can have it built
and running in under a minute.

The next release is already in the works. Stay tuned for more exciting
developments in functional data processing!

If you have comments, please reply on
[Bluesky @julianhyde.bsky.social](https://bsky.app/profile/julianhyde.bsky.social)
or Twitter:

*I'm pleased to announce release 0.7.0 of @morel_lang, a huge release
with major changes to query syntax and semantics. The biggest addition
is support for ordered and unordered collections, enabled by operator
overloading. [link to release notes]*