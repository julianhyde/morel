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
(* Sort employees by job, and then by descending salary. *)
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

This release has actually been under development for a long time.
[Ordered and unordered collections](#1-ordered-and-unordered-collections),
which are the centerpiece of this release, required major changes to
the type inference algorithm, not to mention a new
[data type](https://github.com/hydromatic/morel/issues/235) (`bag`),
[query step](https://github.com/hydromatic/morel/issues/277) (`unorder`),
and
[expression](https://github.com/hydromatic/morel/issues/276) (`ordinal`).
The type inference changes have been under development for six months
(during which time there were two other Morel releases), and were so
extensive that we got
[function overloading](#2-function-overloading) practically for free.

There are other changes to query syntax:
[sorting on expressions](#3-sorting-on-expressions),
[atomic `yield` steps](#4-atomic-yield-steps), and
[set operators in pipelines](#5-set-operators-in-pipelines).

Morel aims to be a solid implementation of Standard ML and good
general-purpose programming language, in addition to being a
revolutionary query language, which means gradually completing our
implementation of Standard ML's
[Basis Library](https://smlfamily.github.io/Basis/). This release we
have completed the
[`String` and `Char` structures](#6-string-and-char-structures).

Let's explore the key features. For complete details, see the
[official release notes](https://github.com/hydromatic/morel/blob/main/HISTORY.md#070--2025-06-07).

## 1. Ordered and unordered collections

The biggest change in 0.7.0 is the introduction of
[ordered and unordered collections](https://github.com/hydromatic/morel/issues/273).
Previously, every query was over a `list` type, whose elements were
ordered and duplicates were allowed.

But saying that every collection and query is over a `list` type
is a white lie. Consider this query:

```sml
from e in scott.emps
  where e.sal > 1000.0
  yield e.ename;
```

The collection `scott.emps` maps to the `EMP` table in the `scott`
database, and Morel's goal is to push as much of the processing as
possible to where the data resides. In this case, Morel can generate
the SQL query

```sql
SELECT ENAME
FROM SCOTT.EMP
WHERE SAL > 1000.0;
```

SQL makes no guarantees about the order of results. If you execute
the query twice, a DBMS is free to return the results in a different
order each time. So Morel is being dishonest if it says that result
is a `list`.

Could we redefine `list` so that its iteration order is undefined?
Yes, but then we would be short-changing queries such as

```sml
from i in ["a", "b"],
    j in [1, 2, 3]
  yield (i, j);
> val it = [("a",1),("a",2),("a",3),("b",1),("b",2),("b",3)]
>   : (string * int) list
```

which do have a defined order.

The fact is -- even though the relational model tells us it ain't so
-- some data sets are ordered, and some are unordered. Adding distinct
`bag` and `list` types, relational operators that can work on both,
and relational operators to convert between them, was the way to go.

The features that we implemented are described in the article
"[Ordered and unordered data](http://blog.hydromatic.net/2025/06/06/ordered-unordered.html)".

## 2. Function overloading

In Standard ML, and in Morel until recently, a name could only have
one binding.  Functions are values, and therefore inhabit the same
namespace as regular values.  If I declare `x` to be an `int` value

```sml
val x = 42;
```

and then later try to declare `x` to be a function

```sml
val x = fn y => y + 1;
```

then the previous declaration of `x` is no longer accessible.
```sml
int z = x - 2;
> 0.0-0.0 Error: Cannot deduce type: conflict: fn(int, int) vs int
>   raised at: 0.0-0.0
```

To create
[overloaded functions](https://github.com/hydromatic/morel/issues/237),
we need declare that an identifier is special; we do this using the
new `over` keyword:

```sml
over f;
> over f
```

Now we can define several instances of `f`:

```sml
val inst f = fn (x : int, y : int) => x + y;
> val f = fn : int * int -> int
val inst f = fn list => length list;
> val f = fn : 'a list -> int
val inst f = fn SOME x => x ^ "!" | NONE => ":(";
> val f = fn : string option -> string
```

All must be functions, because the overloads are resolved based on
the type of the first argument.

Calls to `f` will be resolved based on the types of the arguments:
```sml
(* Call the "int * int -> int" overload. *)
f (7, 8);
> val it = 15 : int

(* Call the "'a list -> int" overload. *)
f ["a", "b", "c"];
> val it = 3 : int
f [1, 2, 3, 4];
> val it = 4 : int
f [];
> val it = 0 : int

(* Call the "string option -> string" overload. *)
f (SOME "happy");
> val it = "happy!" : string
f NONE;
> val it = ":(" : string

(* No overloads match "int option" or "(int, int, int)" arguments. *)
f (SOME 42);
> 0.0-0.0 Error: Cannot deduce type: no valid overloads
>   raised at: 0.0-0.0
f (1, 2, 3);
> 0.0-0.0 Error: Cannot deduce type: no valid overloads
>   raised at: 0.0-0.0
```

## 3. Sorting on expressions

There are only a few places in Morel syntax where you do not use an
expression, and the `order` step used to be one of them.  Previously,
`order` was followed by a list of "order items", each an expression
optionally followed by `desc`. The items were separated by commas, and
the list could not be empty.

The commas were a problem. In the expression

```sml
foo (from i in [1, 2, 3] order i desc, j);
```

it is not clear whether `j` is a second argument for the call to the
function `foo` or the second item in the `order` clause.

Another problem was the fact that the `order` clause could not be
empty. The
[ordered and unordered collections](#1-ordered-and-unordered-collections)
feature introduced an `unorder` step to convert a `list` to a `bag`,
and we need the opposite of that, a trivial sort whose
key has the same value for every element.

The answer was to
[make the argument to `order` an expression](https://github.com/hydromatic/morel/issues/244).
A composite sort specification is now a tuple, still separated by
commas, but now enclosed in parentheses.  If a sort key is descending,
you now wrap it in the `Descending` data type by preceding it with the
`DESC`.  Thus:

```sml
(* Old syntax *)
from e in scott.emps
  order e.job, e.sal desc;

(* New syntax *)
from e in scott.emps
  order (e.job, DESC e.sal);
```

You can now sort by any data type, including tuples, records,
sum-types such as `Option` and `Descending`, lists, bags, and any
combination thereof.

To achieve the trivial sort, you can sort by any constant value, such
as the integer `0` or the `Option` constructor `NONE`, but
conventionally you would sort by the empty tuple `()`:

```sml
from e in scott.emps
  yield e.ename
  order ();
> val it =
>   ["SMITH","ALLEN","WARD","JONES","MARTIN","BLAKE","CLARK",
>    "SCOTT","KING","TURNER","ADAMS","JAMES","FORD","MILLER"]
>   : string list
```

The key thing is that the result is a `list`.  The elements are in
arbitrary order (because any order is consistent with the empty sort
key) but in converting the collection to a `list` the arbitrary order
has become frozen and repeatable.

## 4. Atomic yield steps

At any step in a Morel query, there are generally several named fields
you can use to reference parts of the current row.  For example, the
`where` step in the following query refers to both fields, `i` and
`j`.

```sml
from i in [1, 2, 3],
    j in [4, 5, 6]
  where i + j > 6;
> i j
> - -
> 2 6
> 3 5
> 3 6
>
> val it : {i:int, j:int} list
```

But there is one circumstance where a step does not produce any named
fields: a `yield` whose expression is not a record, what we call an
"atomic yield". Here is an example:

```sml
from i in [1, 2, 3]
    j in [4, 5, 6]
  yield i + j;
```

That query is valid, but suppose we wished to sort or filter the
results.  If we added an `order` or `where` step it would have no way
to refer to the current row. We allowed atomic yields because we
needed queries with non-record elements, but we made a rule that the
atomic yield had to be the last step.

That restriction was becoming more of a burden, and the final straw
was ordered/unordered queries, which often end in `order` or
`unorder`. So we decided to fix the problem.

We
[added a new expression, `current`](https://github.com/hydromatic/morel/issues/265),
that refers to the current element. (It is only available in query
steps, but you can use it inside a sub-expression or sub-query.)  If
the value is atomic, `current` is that value; if there are named
fields, `current` is a record consisting of those fields. (In the
previous example, `current` would be equivalent to `{i, j}`.)

If a `yield` is atomic but the expression has a clear name, as in
`yield i` or `yield e.deptno`, you can also use that name.  (The
expression is still considered atomic, and the result of the query
will be a collection of that type, not a collection of records.)

Here are some examples of `current` in action.

```sml
from i in [1, 2, 3],
    j in [4, 5, 6]
  yield i + j
  order DESC current;
> val it = [9,8,8,7,7,7,6,6,5] : int list

from maker in ["ford", "ferrari"],
    color in ["red", "green"]
  order current.color;
> color maker
> ----- -------
> green ford
> green ferrari
> red   ford
> red   ferrari
>
> val it : {color:string, maker:string} list

from i in [1, 2, 3, 4]
  yield 4 * (i mod 2) + (i div 2)
  order current;
> val it = [1,2,4,5] : int list

from e in scott.emps
  yield e.deptno
  distinct
  order current;
> val it = [10,20,30] : int list

from e in scott.emps
  yield e.deptno
  distinct
  order deptno;
> val it = [10,20,30] : int list
```

## 5. Set operators in pipelines

The set operators (`union`, `intersect` and `except`) were previously
available via functions but now have
[dedicated steps](https://github.com/hydromatic/morel/issues/253) in
the query pipeline.

The steps have slightly different semantics for ordered and unordered
collections, and have an optional `distinct` keyword to eliminate
duplicates.

For example, here is a query that finds all employees in departments
10 and 20, but excludes those who are managers or clerks:

```sml
from e in scott.emps
  where e.deptno = 10
  union (from e in scott.emps where e.deptno = 20)
  except (from e in scott.emps where e.job = "MANAGER"),
     (from e in scott.emps where e.job = "CLERK");
```

If you have ever wondered about the semantics of `intersect` and
`except` with duplicates, wonder no more!
[INTERSECT ALL, EXCEPT ALL, and the arithmetic of fractions]({% post_url 2025-06-03-intersect-fractions %})
explains everything using a fun example.

## 6. String and Char structures

Morel now includes complete
[`String`](https://github.com/hydromatic/morel/issues/279) and
[`Char`](https://github.com/hydromatic/morel/issues/264) structures
following the
[Standard ML Basis Library](https://smlfamily.github.io/Basis/)
specification.

This gives you comprehensive text manipulation capabilities:

```sml
String.size "hello world";
> val it = 11 : int

String.substring ("hello world", 6, 5);
> val it = "world" : string

String.tokens (fn c => c = #" ") "hello world morel";
> val it = ["hello","world","morel"] : string list

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

## 7. Breaking changes

This release includes some breaking changes to be aware of.

### Database schema updates

The `scott` sample database now uses
[pluralized table names](https://github.com/hydromatic/morel/issues/255),
mapping the `emps` value maps to the `EMP` table, and `depts` to the
`DEPT` table.

```sml
(* Old *)
from e in scott.emp
  join d in scott.dept on e.deptno = d.deptno;

(* New *)
from e in scott.emps
  join d in scott.depts on e.deptno = d.deptno;
```

This change aligns with the modern programming convention that
collections have plural names.

### Type-based orderings

The previous `order` syntax no longer works.

You should convert a following `desc` to preceding `DESC`:

```sml
(* Old syntax *)
from e in scott.emps
  order e.sal desc;

(* New syntax *)
from e in scott.emps
  order DESC e.sal;
```

and put parentheses around composite orderings:

```sml
(* Old syntax *)
from e in scott.emps
  order e.job, e.sal desc;

(* New syntax *)
from e in scott.emps
  order (e.job, DESC e.sal);
```

## Conclusion

Release 0.7.0 represents a major evolution in Morel's
capabilities. Extensions to the query language, type system, and
standard library make Morel a good solution for a wide range of
data processing tasks, from simple queries to complex data
transformations.

As always, you can get started with Morel by visiting
[GitHub](https://github.com/hydromatic/morel).
For more background, read about its
[goals]({% post_url 2020-02-25-morel-a-functional-language-for-data %})
and [basic language]({% post_url 2020-03-03-morel-basics %}),
and find a full definition of the language in the
[query reference](https://github.com/hydromatic/morel/blob/main/docs/query.md)
and the
[language reference](https://github.com/hydromatic/morel/blob/main/docs/reference.md).

If you have comments, please reply on
[Bluesky @julianhyde.bsky.social](https://bsky.app/profile/julianhyde.bsky.social)
or Twitter:

*I'm pleased to announce release 0.7.0 of @morel_lang, a huge release
with major changes to query syntax and semantics. The biggest addition
is support for ordered and unordered collections, enabled by operator
overloading. [link to release notes]*