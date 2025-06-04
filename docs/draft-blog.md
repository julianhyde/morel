# Sorting on expressions

In Morel’s query syntax, almost everything is an expression. The
‘yield’ step has an expression (whereas SQL’s SELECT has a list of
expressions with optional aliases); the scan in a ‘join’ step is over
an expression, not necessarily a query; in the ‘group’ step, an
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

The ‘order’ step is a stubborn holdout. Its argument is a list of
‘orderIitem’ s, each of which is an expression with an optional ‘desc’
keyword.

We can’t just get rid of the ‘desc’ keyword and covert the list to a
singleton. Real queries require complex sorting behaviors like
composite keys, descending keys, and nulls-first or nulls-last. So,
how can we put all that complexity in a single expression?

One approach is to do what many programming languages do, and use a
comparator function.

## Comparator functions

In Standard ML, a comparator function is any function that takes a
pair of arguments of the same type and returns a value of the `order`
enum (‘LESS’, ‘EQUAL’, ‘GREATER’). Its type is ‘alpha * alpha ->
order’.

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
> val compareIntStringPair = fn : int * string * (int * string) -> order
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
functions are clearly powerful, but we are not living up to the ‘Make
simple things simple, make hard things possible’ principle.

Let’s abandon comparator functions and go back to sorting values.

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

SQL has `NULLS FIRST` and `NULLS LAST` keywords to control how nulls are
sorted. Morel uses the `option` type rather than `NULL` represent optional
values, but the same requirement exists.

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

What about the ‘order using’ syntax and comparator functions? We still
might go there (especially for the ‘Make hard things possible’). We
are still using comparator functions — the `Relational.compare`
function is generating a comparator at compile time based on the type
of the query.

Are structured values strictly less powerful than comparator
functions? It’s an interesting theoretical question, and I honestly
don’t know. A comparator function can be an arbitrarily complex piece
of code — but we could provide a complex type that match that And
we have created `Relational.compare`. (It is a somewhat strange function
because its type is an implicit argument.)

<hr>

# `INTERSECT ALL` and the arithmetic of fractions

Have you ever used `INTERSECT ALL` in SQL? Can you even think of a
problem where it would be useful? I confess that I don't use it often,
but I've found an example that you'll recognize from your middle
school math class.

SQL's set operators (`UNION`, `INTERSECT`, and `EXCEPT`) have set and
multiset variants.  The multiset variants retain duplicates and use
the `ALL` keyword; the set variants discard duplicates, and you can
use the optional `DISTINCT` keyword if you want to be explicit.

Morel has had `concat`, `intersect` and `except` functions for some
time, but we have
[only just added](https://github.com/hydromatic/morel/issues/253)
`union`, `intersect` and `except` as query steps, to achieve parity
with standard SQL and
[GoogleSQL's pipe syntax](https://cloud.google.com/bigquery/docs/reference/standard-sql/pipe-syntax#union_pipe_operator).

(The
[`union`, `intersect` and `except` steps](https://github.com/hydromatic/morel/blob/main/docs/query.md#step-list)
retain duplicates by default; to eliminate duplicates, add the
`distinct` keyword.  Like the in GoogleSQL, they accept multiple
arguments.
[Ordered and unordered](https://github.com/hydromatic/morel/issues/273)
collections are a major theme of release 0.7, and of course these
steps work in both modes.)

What better way to celebrate than with a concise example?
The example uses the `intersect` in its duplicate-retention mode,
and also showcases how Morel queries can operate integer values,
without the need for column names.

## LCM and GCD

Remember how -- probably in middle school -- you learned how to add
two fractions, and to reduce a fraction to its lowest terms?

Suppose you need to add 5/24 and 1/40. First, you find the **least
common multiple (LCM)** of their denominators. The LCM of 24 and 40 is
120, so you convert the fractions to have the LCM as their denominator:

```
  5      1        25      3         28
---- + ----  =  ----- + -----  =  -----
 24     40       120     120       120
```

Next, you need to reduce the fraction 28/120 to its lowest terms.  You
find **the greatest common divisor (GCD)** of 28 and 120, which is 4,
and divide both the numerator and denominator by the GCD:

```
  28        (28/4)        7
-----  =  ---------  =  ----
 120       (120/4)       30
```

To compute the GCD of two numbers, you start by finding their prime
factors.  Prime factors can be repeated, so these are multisets, not
sets.  Let's find the GCD of 36 and 120.

* 36 is 2<sup>2</sup> * 3<sup>2</sup>, so has factors [2, 2, 3, 3]
* 120 is 2<sup>3</sup> * 3 * 5, so has factors [2, 2, 2, 3, 5]

Where there are factors in common, we take the lower repetition count:
 * 36 has two 2s and 120 has three 2s, so the GCD will have two 2s;
 * 36 has two 3s and 120 has one 3, so the GCD will have one 3;
 * 36 has no 5s and 120 has one 5, so the GCD will have no 5s.

The GCD has factors [2, 2, 3], and 2<sup>2</sup> * 3 is 12.

The crucial step of the algorithm is to combine two multisets and take
the minimum repetition count; that is exactly what `intersect` does.

The LCM of two numbers is their product divided by their GCD,
and therefore the LCM of 36 and 120 is (36 * 120) / 12 = 360.

## Using Morel to compute LCM and GCD

To convert this algorithm to code, we will need three things:
 * a `factorize` function splits the numbers into multisets of prime
   factors;
 * the `intersect` step combines the multisets;
 * a `product` function converts the multisets back to a number.

Here are the `factorize` and `product` functions.

```
fun factorize n =
  let
    fun factorize' n d =
      if n < d then [] else
      if n mod d = 0 then d :: (factorize' (n div d) d)
      else factorize' n (d + 1)
  in
    factorize' n 2
  end;
> val factorize = fn : int -> int list

fun product [] = 1
  | product (x::xs) = x * (product xs);
> val product = fn : int list -> int
```

Let's test them.
```
factorize 120;
> val it = [2, 2, 2, 3, 5] : int list
product (factorize 120);
> val it = 120 : int
```

So, we can compute GCD like this:

```
fun gcd (m, n) =
  from f in factorize m
    intersect factorize n
    compute product;
> val gcd = fn : int * int -> int
```

The last step uses `compute` because `product` fulfills Morel's only
criterion to be an aggregate function: its argument is a collection
of values. (At least one SQL dialect agrees with us, and has a
[PRODUCT](https://duckdb.org/docs/stable/sql/functions/aggregates#productarg)
aggregate function.)

LCM can be computed from GCD:
```
fun lcm (m, n) =
  (m * n) div gcd (m, n);
```

Let's test them:

```
gcd (36, 120);
> val it = 12 : int
lcm (36, 120);
> val it = 120 : int
```

## Conclusion

The `intersect` step neatly solves the problem of computing GCD
because it handles repeated factors in exactly the way we need.

`intersect` and its friends `union` and `except` will be available
shortly in Morel release 0.7.
