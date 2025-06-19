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

## Appendix: Sorting on expressions

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
