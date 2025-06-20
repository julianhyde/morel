# Sorting on expressions

Morel's design philosophy of "everything is an expression" has
transformed how we think about queries, making them more composable
and flexible than traditional SQL.  One stubborn holdout was the
`order` step, which required a special syntax with comma-separated
order-items rather than a single expression. In this post, we describe
how we
[evolved `order` syntax](https://github.com/hydromatic/morel/issues/244)
in Morel release 0.7, and the benefits of this change.

## Why expressions?

As of Morel release 0.6,
[query syntax](https://github.com/hydromatic/morel/blob/main/docs/query.md#syntax)
(a little simplified) looked like this:

<pre><code><i>query</i> &rarr; <b>from</b> <i>scan</i> , ... , <i>scan</i> <i>step</i> , ... <i>step</i>

<i>step</i> &rarr; <b>distinct</b>
    | <b>except</b> [ <b>distinct</b> ] <i>exp</i> , ... , <i>exp</i>
    | <b>group</b> <i>groupKey</i> , ... , <i>groupKey</i> [ <b>compute</b> <i>agg</i> , ... , <i>agg</i> ]
    | <b>intersect</b> [ <b>distinct</b> ] <i>exp</i> , ... , <i>exp</i>
    | <b>join</b> <i>scan</i> , ... , <i>scan</i>
    | <b>order</b> <i>orderItem</i> , ... , <i>orderItem</i>
    | <b>skip</b> <i>exp</i>
    | <b>take</b> <i>exp</i>
    | <b>union</b> [ <b>distinct</b> ] <i>exp</i> , ... , <i>exp</i>
    | <b>where</b> <i>exp</i>
    | <b>yield</b> <i>exp</i>

<i>scan</i> &rarr; pat <b>in</b> <i>exp</i> [ <b>on</b> <i>exp</i> ]

<i>orderItem</i> &rarr; <i>exp</i> [ <b>desc</b> ]

<i>groupKey</i> &rarr; [ <i>id</i> = ] <i>exp</i>

<i>agg</i> &rarr; [ <i>id</i> = ] <i>exp</i> [ <b>of</b> <i>exp</i> ]</code></pre>

Almost everything is an expression. The argument to the `yield` step
is an expression (whereas SQL's `SELECT` has a list of expressions
with optional `AS` aliases); the scan in a `from` query or `join` step
is over an expression (which, unlike SQL, is not necessarily a query);
the arguments to the `where`, `skip`, `take`, `union`, `intersect`,
and `union` steps are also expressions.

(The *groupKey* and *agg* items in `group` and `compute` have some way
to go, and we will be looking at those for Morel 0.8, but at least the
aggregate function (before <b>`of`</b>) may be a (function-valued)
expression.)

Making everything an expression pays dividends. Queries can return a
collection of any value, not just records. You can easily join a
collection to a set of nested records (say an order to its nested
order-lines). If you need a custom aggregate function, you can roll
your own. And each of these expressions can be made into function
arguments, so that you can parameterize your query.

## The `order` step

The `order` step is a stubborn holdout. Its argument is a
comma-separated list of order-items, each of which is an expression
with an optional `desc` keyword.

One problem is the commas. In the expression

```sml
let
  val pairs = [(1, "a"), (2, "b"), (1, "c")];
in
  foo (from (i, j) in pairs order i desc, j)
end;
```

it is not immediately clear whether `j` is a second argument for the
call to the function `foo` or the second item in the `order` clause.

Another problem was the fact that the `order` clause could not be
empty. The
[ordered and unordered collections](https://github.com/hydromatic/morel/issues/273)
feature introduced an `unorder` step to convert a `list` to a `bag`,
and we need the opposite of that, a trivial sort whose
key has the same value for every element.

We can't just get rid of the `desc` keyword and covert the list to a
singleton. Real queries require complex sorting behaviors like
composite keys, descending keys, and nulls-first or nulls-last
specifications. So, how can we put all that complexity in a single
expression?

One approach is to do what many programming languages do, and use a
comparator function. Let's explore this approach.

## Comparator functions

In Standard ML, a comparator function is any function that takes a
pair of arguments of the same type and returns a value of the `order`
enum (`LESS`, `EQUAL`, `GREATER`). Its type is
`alpha * alpha -> order`.

For `int`, I can write a simple function:

```sml
fun compareInt (x: int, y: int) =
    if x < y then LESS
    else if x > y then GREATER
    else EQUAL;
> val compareInt = fn : int * int -> order
```

In fact, most data types have a built-in `compare` function:

```sml
Int.compare;
val it = fn : int * int -> order

Real.compare;
val it = fn : real * real -> order

String.compare;
val it = fn : string * string -> order
```

For more complex orderings, I can write a comparator that combines
other comparators. For example, this function compares a list of
`string * real` pairs, the `string` first, then the `real`
descending:

```sml
fun compareStringRealPair ((s1, r1), (s2, r2)) =
    case String.compare (s1, s2) of
        EQUAL => Real.compare (r2, r1)
      | result => result;
> val compareStringRealPair = fn
>   : string * real * (string * real) -> order
```

If we were to add comparators to Morel, we could add `order using`
syntax like this:

```sml
(* Sort employees by job, and then by descending salary. *)
from e in scott.emps
  order using fn (emp1, emp2) =>
    case String.compare (emp1.job, emp2.job) of
       EQUAL => Real.compare (emp2.sal, emp1.sal)
     | result => result;
```

(The comparator expression in this query is basically an inline
version of the `compareStringRealPair` function, but working on `emp`
records rather than `string * real` pairs.)

But this is much longer than the equivalent in SQL. Comparator
functions are clearly powerful, but they fail the "make simple things
simple" test -- forcing developers to write complex code for common
sorting patterns.

Let's look instead at value-based sorting, which is simpler, but
provides most of the flexibility of comparator functions.

## Structured values for complex orderings

The idea behind value-based sorting is that any values of the same
type can be compared, and that the Morel system generates comparison
logic for any type. If you require a complex sorting behavior, you can
construct an expression with a complex type.

Previously, if you wanted a composite ordering, with one of the keys
descending, you would write something like this:

```sml
(* Old syntax *)
from e in scott.emps
  order e.job, e.sal desc;
```

As of Morel 0.7, you can write the same query using a single
expression:

```sml
(* New syntax *)
from e in scott.emps
  order (e.job, DESC e.sal);
```

Note that:
 * For a composite ordering, we use a tuple type. Morel compares the
   values lexicographically.
 * For a descending ordering, we wrap the value in the `descending`
   data type using its `DESC` constructor. Morel compares the values
   in the usual way, then reverses the direction.

Sorting is defined for all other data types, including tuples,
records, sum-types such as `Option` and `Descending`, lists, bags, and
any combination thereof.

Morel's compiler has two tricks to make this powerful and efficient.

First, Morel is effectively generating a comparator function at
compile time based on the type of the `order` expression.  This makes
value-based sorting as powerful as comparator functions, but with less
code for the user to write.

(The change included a new library function, `Relational.compare`,
that allows you to compare any two values of the same type, even if
you are not performing a sort. This is a somewhat strange function,
because it takes the type as an implicit argument, then drives its
behavior by introspecting that type.)

Second, the `order` clause uses a form of lazy evaluation. If the
query

```sml
from e in scott.emps
  order (e.job, DESC e.sal);
```

created a tuple `(e.job, DESC(e.sal))` for every element, we would
worry about the impact on performance, but those tuples are never
constructed. Morel operates on the employee records `e` directly,
and the performance is the same as if we had specified the ordering
using a list of order-items or a comparator function.

## Benefits of sorting on expressions

Now the `order` step takes an expression, what is now possible that
wasn't before?

We can pass the expression as an argument to a function, like this:

```sml
fun rankedEmployees extractKey =
  from e in scott.emps
    order extractKey e;
    
rankedEmployee (fn e => e.ename);
rankedEmployee (fn e => (e.job,  DESC e.sal));
```

We can also achieve the trivial sort required to convert a `bag` to a
`list`. You can sort by any constant value, such as the integer `0` or
the `Option` constructor `NONE`, but the norm would be to sort by the
empty tuple `()`:

```sml
from e in scott.emps
  yield e.ename
  order ();
> val it =
>   ["SMITH","ALLEN","WARD","JONES","MARTIN","BLAKE","CLARK",
>    "SCOTT","KING","TURNER","ADAMS","JAMES","FORD","MILLER"]
>   : string list
```

Note that result is a `list`, even though `scott.emps` (a relational
database table) is a `bag`.  The elements are in
arbitrary order (because any order is consistent with the empty sort
key) but in converting the collection to a `list` the arbitrary order
has become frozen and repeatable.

## Future work

Several challenges remain to be addressed.

### NULLS FIRST and NULLS LAST

Real-world data sets often contain null values, and at various times
you wish to sort nulls low (as if they were zero or negative infinity)
or high (as if they were positive infinity). Morel uses the `option`
type rather than `NULL` to represent optional values, but the same
requirement exists.

SQL has `NULLS FIRST` and `NULLS LAST` keywords to control how nulls
are sorted, but Morel does not have an equivalent syntax.

Currently, the behavior is the same as SQL's `NULLS FIRST`.  This
happens because Morel sorts datatype values based on the declaration
order of their constructors. The `option` type is declared as:

```sml
datatype option 'a = NONE | SOME of 'a;
```

Since `NONE` appears before `SOME` in this declaration, the `NONE`
value sorts lower than all `SOME` values:

```sml
from i in [SOME 1, SOME ~100, NONE]
  order i;
> val it = [NONE, SOME ~100, SOME 1] : int option list
```

We haven't yet figured out how to express the equivalent of `NULLS
LAST`.  One idea is to add a `noneLast` datatype

```sml
datatype 'a noneLast = NONE_LAST of 'a;
```

and use it in a query like this:

```sml
from i in [SOME 1, SOME ~100, NONE]
  order NONE_LAST i;
> val it = [SOME ~100, SOME 1, NONE] : int option list
```

When we use `NONE_LAST` and `DESC` together in a query

```sml
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

Under the "make hard things possible" principle, we might still want
to support comparator functions at some point. The syntax could be as
follows:

<pre><code><i>step</i> &rarr; ...
  | <b>order</b> <i>exp</i>
  | <b>order using</b> <i>comparator</i></code></pre>

Is value-based sorting strictly less powerful than comparator
functions? It's an interesting theoretical question, and I honestly
don't know. A comparator function can be an arbitrarily complex piece
of code — but perhaps it is always possible to create a value that
matches the structure of the code.

### Aggregation syntax

The syntax for `group` and `compute` steps is still not an expression.
For Morel 0.8 and beyond, we will be looking at
[several improvements](https://github.com/hydromatic/morel/issues/288).

First, making the group-key and compute-items an expression, with
field aliasing provided via record syntax, as in the current `yield`
step.

Second, allowing complex compute expressions with expressions both
inside and outside the aggregate function, as in the SQL expression
"`1 + AVG(sal * 2)`". This will mean the `of` keyword, which is
currently part of the *agg* syntax, will be transitioned to a new
keyword that is part of the expression syntax, possibly `over`.

Third, further explore the relationship between the argument to an
aggregate function and a query. Noting that SQL aggregate function
syntax by now includes most relational operators (`FILTER`,
`DISTINCT`, `WITHIN DISTINCT`, `ORDER BY`) consider making the
argument (the `over` keyword just mentioned) a kind of query
expression.

## Conclusion

As always, you can get started with Morel by visiting
[GitHub](https://github.com/hydromatic/morel).
For more background, read about its
[goals]({% post_url 2020-02-25-morel-a-functional-language-for-data %})
and [basic language]({% post_url 2020-03-03-morel-basics %}),
and find a full definition of the language in the
[query reference](https://github.com/hydromatic/morel/blob/main/docs/query.md)
and the
[language reference](https://github.com/hydromatic/morel/blob/main/docs/reference.md).
