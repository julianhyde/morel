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

# Ordered and unordered data

Despite what the relational model says, some data is *ordered*.

I'm not talking about *sorted* data. If you sort a collection,
applying some comparator function to its elements, then have no
more information than you had before.

No, the integer list

```sml
[3, 1, 4, 1, 5, 9, 2, 6, 5, 3]
```

and the string list

```sml
["Shall I compare thee to a summer's day?",
  "Thou art more lovely and more temperate",
  "Rough winds do shake the darling buds of May",
  "And summer's lease hath all too short a date"]
```

depend on the order of their elements for their meaning.

But of course, some data is *unordered*, for good reason. A relational
database would be foolish to guarantee that if you write rows into a
table in a particular order, they will be read back in the same
order. Such a guarantee would seriously limit the database's
scalability.

This post is about how we allow ordered and unordered data to coexist
in Morel.

Recent additions, to appear soon in release 0.7, includes
[adding a `bag` type](https://github.com/hydromatic/morel/issues/235),
the
[ordered relational operators](https://github.com/hydromatic/morel/issues/273),
the
[`ordinal` keyword](https://github.com/hydromatic/morel/issues/276),
and a new
[`unorder` step](https://github.com/hydromatic/morel/issues/277).

## List and bag types

As a functional query language, Morel spans the worlds of database and
functional programming.

Databases' fundamental type, the relation, is an unordered collection
of records.  (Though curiously, modern SQL allows columns to contain
"nested tables", which can be either of the ordered `ARRAY` type or
the unordered `MULTISET` type.)

Functional programming languages' fundamental type is the
list. Functional programs are often defined by structural induction on
lists.  For example, the function

```sml
fun allPositive [] = true
  | allPositive (x::xs) = x > 0 andalso allPositive xs;
val allPositive: int list -> bool;
```

inductively defines that a list of numbers is "all-positive" if it is
empty, or if its first element is positive and the rest of the list is
"all-positive". This kind of inductive definition requires a firm
distinction between the first element of a list and the rest of the
list, a distinction that is not present in an unordered collection.

In earlier versions of Morel, we papered over the difference between
ordered and unordered data. All collections had type `list`, even the
unordered collections backed by database tables. Morel's relational
operators produced results in deterministic order if you applied them
to in-memory collections using the in-process interpreter, but order
was not guaranteed when "hybrid mode" converted the query to SQL.

The first step was to add a `bag` type as the unordered counterpart to
the ordered `list` type, and with similar operations.

```sml
val b = bag [3, 1, 4, 1, 5];
> val b = [3,1,4,1,5] : int bag
Bag.length b;
> val it = 5 : int
Bag.toList b;
> val it = [3,1,4,1,5] : int list
Bag.fromList [3, 1, 4, 1, 5];
> val it = [3,1,4,1,5] : int bag
```

Order-dependent operations from `list`, such as `hd` and `drop`, are
defined for bags, but they are not guaranteed to return the same
result every time you call them.

```sml
Bag.hd b;
> val it = 3 : int
Bag.drop (b, 2);
> val it = [4,1,5] : int bag
```

Collections backed by database tables now have type `bag`:

```sml
from e in scott.depts;
> val it =
>   [{deptno=10,dname="ACCOUNTING",loc="NEW YORK"},
>    {deptno=20,dname="RESEARCH",loc="DALLAS"},
>    {deptno=30,dname="SALES",loc="CHICAGO"},
>    {deptno=40,dname="OPERATIONS",loc="BOSTON"}]
>   : {deptno:int, dname:string, loc:string} bag
```

(You may notice that `scott.depts` collection, backed by the `DEPT`
table of the `SCOTT` JDBC data source, has changed its name as well
as its type. It used to be called `scott.dept`. Morel collection names
should be plural and lower-case, and improvements to the
[name mapping](https://github.com/hydromatic/morel/issues/255)
make it easier to provide those names.)

Next, we provide relational operators to convert between `list` and
`bag`.

## `order` and `unorder` steps, and the `ordinal` expression

Now that queries can reference `list` and `bag` collections, we need
operators to convert from one to the other.

In previous versions of Morel, the `order` converted a `list` to a
`list` with a different ordering; now its input can be a `list` *or*
a `bag`:

```sml
from i in [3, 1, 4, 1, 5]
  order DESC i;
> val it = [5,4,3,1,1] : int list
from i in bag [3, 1, 4, 1, 5]
  order DESC i;
> val it = [5,4,3,1,1] : int list
```

The new `unorder` step converts a `list` to a `bag`:

```sml
from i in [3, 1, 4, 1, 5]
  unorder;
> val it = [3,1,4,1,5] : int bag
```

(You are also free to apply `unorder` to a `bag`; it will have no
effect.)

As we said above, a `bag` contains less information than its
corresponding `list`. If you plan to convert the `bag` to a `list`
at a later stage, you need store the ordering in an extra field.
The new `ordinal` expression lets us do this:

```sml
from i in [3, 1, 4, 1, 5]
  yield {i, j = ordinal}
  unorder
  order j
  yield i;
> val it = [3,1,4,1,5] : int list
```

The `ordinal` expression can be used in an expression in a
step whose input is ordered (except the steps whose expressions are
evaluated before the query starts: `except`, `intersect`, `skip`,
`take`, and `union`). `ordinal` evaluates to 0 for the first element,
1 for the next element, and so on. But as we shall see, the optimizer
avoids evaluating `ordinal` if it can.

Here is a query that computes the salary rank of each employee,
then returns only the poorly-paid clerks.

```sml
from e in scott.emps
  order e.sal
  yield {e, rank = 1 + ordinal}
  where e.job = "CLERK";
> ename  rank
> ------ ----
> MILLER 9
> ADAMS  12
> JAMES  13
> SMITH  14
>
> val it : {ename:string, rank:int} list
```

The main reason to apply `order` and `unorder` in a query is
to control the target collection type. But there is a more subtle
reason which relates to performance. The ordered and unordered
versions of the relational operators may produce the same results
(modulo ordering) but ordered execution may be less efficient (such
as running with reduced parallelism). If a query contains an `order`
or `unorder`, the order of the input to that step is irrelevant, and
the optimizer can use a more efficient execution plan.

This, by the way, is why the specification of the `order` step does
not guarantee stability. If `order` was stable, the optimizer would
have to use ordered execution of upstream steps if the sort key is
not exhaustive.

If you want `order` to be stable, you can add `ordinal` to the
trailing edge of the sort key:

```sml
from e in scott.emps
  order DESC e.sal
  where e.deptno <> 20
  yield {e.ename, e.job, e.sal}
  order (e.job, ordinal);
> val it =
>   [{ename="MILLER",job="CLERK",sal=1300.0},
>    {ename="JAMES",job="CLERK",sal=950.0},
>    {ename="BLAKE",job="MANAGER",sal=2850.0},
>    {ename="CLARK",job="MANAGER",sal=2450.0},
>    {ename="KING",job="PRESIDENT",sal=5000.0},
>    {ename="ALLEN",job="SALESMAN",sal=1600.0},
>    {ename="TURNER",job="SALESMAN",sal=1500.0},
>    {ename="WARD",job="SALESMAN",sal=1250.0},
>    {ename="MARTIN",job="SALESMAN",sal=1250.0}]
>   : {ename:string, job:string, sal:real} list
```

Materializing `ordinal` as a 1-based, contiguous sequence of integers
is expensive because it forces sequential execution, and the
optimizer will avoid this if it can. In this case, because `ordinal`
is used for sorting but is not returned, the optimizer downgrades
`ordinal` to a virtual expression. The plan might use an ordered
implementation of the `where` and `yield` steps followed by a stable
sort, or it might replace `ordinal` with the previous sort key
(`DESC e.sal`). 

## Ordered relational operators

The relational model specifies the relational operators over
(unordered) sets; the SQL standard specifies the relational operators
of unordered multisets (the `bag` type). We now need to specify the
relational operators over ordered multisets (the `list` type).

Semantics of steps when applied to an ordered collection:

* The first step in a query -- <code>from *pat* in *exp*</code>,
  <code>forall *pat* in *exp*</code>, or
  <code>exists *pat* in *exp*</code> -- returns elements in the same
  order that they are emitted from *exp*.
* <code>join *pat* in *exp* [ on *condition* ]</code> for each element
  from its input evaluates *exp*, then, in order of those elements,
  emits a record consisting of fields of the two elements, skipping
  records where *condition* is false.
* If a `from`, `forall`, `exists` or `join` step has more than one
  scan, each subsequent scan behaves as if it were a separate `join`
  step.
* <code>yield *exp*</code> preserves order. 
* <code>where *condition*</code> preserves order, dropping rows for
  which *condition* is false.
* <code>skip *count*</code> and <code>take *count*</code> preserve
  order (respectively dropping the first *count* rows, or taking the
  first *count* rows).
* `distinct` preserves order, emitting only the first occurrence
  of each element.
* <code>group *groupKey<sub>1</sub>*, ..., *groupKey<sub>g</sub>* [
  compute *agg<sub>1</sub>*, ..., *agg<sub>a</sub>* ]</code> preserves
  order, emitting groups in the order that the first element in the
  group was seen; each aggregate function
  <code>*agg<sub>i</sub>*</code> is invoked with a list of the input
  elements that belong to that group, in arrival order.
* <code>compute *agg<sub>1</sub>*, ..., *agg<sub>a</sub>*</code>
  behaves as a `group` step where all inputs elements are in the same
  group.
* <code>union [ distinct ] *exp<sub>1</sub>*, ...,
  *exp<sub>n</sub>*</code> outputs the elements of the input in order,
  followed by the elements of each *exp<sub>i</sub>* argument in order
  (just like the UNIX `cat` command). If `distinct` is specified,
  outputs only the first occurrence of each element.
* <code>intersect [ distinct ] *exp<sub>1</sub>*, ...,
  *exp<sub>n</sub>*</code> outputs the elements of the input in order,
  provided that every *exp<sub>i</sub>* argument contains at least the
  number of occurrences of this element so far.  If `distinct` is
  specified, outputs only the first occurrence of each element.
* <code>except [ distinct ] *exp<sub>1</sub>*, ...,
  *exp<sub>n</sub>*</code> outputs the elements of the input in order,
  provided that the number of occurrences of that element so far is
  less than the number of occurrences of that element in all the
  *exp<sub>i</sub>* arguments.  If `distinct` is specified, outputs
  only the first occurrence of each element.
* <code>require *condition*</code> (which can only occur in a `forall`
  query) has the same behavior as the unordered case. 
* `order` and `unorder`, as discussed earlier, have the same
  semantics as in the unordered case.

The rules for `from` and `join` produce the ordering of nested "for"
loops in many languages:

```sml
from hundreds in [100, 200, 300],
    tens in [10, 20, 30]
  join units in [1, 2, 3]
  yield hundreds + tens + units;
> val it =
>   [111,112,113,121,122,123,131,132,133,211,212,213,...]
>   : int list
```
## Hybrid relational operators

We have specified the behavior of queries where input collections are
`list`s, or all collections are `bag`s. But what if a query has a mix
of `list` and `bag` inputs?

The mixing can occur if the first step of the query (`from`, `exists`,
or `forall`) has more than one scan, or in steps that introduce
another collection (`join`, `union`, `intersect`, or `except`). In all
cases, unordered wins: if any input is a `bag`, the step becomes
unordered, and unordered semantics apply from then on.

For example, if we join a list of department numbers (ordered)
to a table of employees (unordered), selecting only the clerks and
managers, the result is a `bag` (unordered):

```sml
from deptno in [10, 20, 30]
  join e in scott.emps on e.deptno = deptno
  where e.job elem ["CLERK", "MANAGER"]
  yield {deptno, e.ename};
> deptno ename
> ------ ------
> 30     JAMES
> 10     CLARK
> 20     ADAMS
> 10     MILLER
> 20     SMITH
> 30     BLAKE
> 20     JONES
>
> val it : {deptno:int, ename:string} bag
```

## Type inference

This feature was challenging to implement because it required
major changes to Morel's type inference algorithm. (We mention this
only in the spirit of sharing war-stories, and for the interest of
those who understand the internal workings of Morel's compiler.
Hopefully, the changes to type-inference algorithm will be invisible
to the casual user.)

The problem occurs in a program such as

```sml
let
  fun f (xs, ys) =
    from i in xs
      intersect ys
in
  f ((from e in scott.emps yield e.empno), [7521, 7782, 8000])
end;
> val it = [7521,7782] : int bag
```

While resolving the type of function `f` and its embedded query, the
types of the arguments `xs` and `ys` have not yet been
determined. Morel's previous type inference algorithm allowed us to
say "`xs` and `ys` must be lists with the same element type" or "`xs`
and `ys` must be bags with the same element type". It was based on
[Hindley-Milner's Algorithm W](https://en.wikipedia.org/wiki/Hindley%E2%80%93Milner_type_system#Algorithm_W)
and unification, which basically means finding an assignment of
logical variables so that two trees are structurally identical.

But the type inference rules for queries with a mixture of ordered and
unordered collections require conditions that contain the word
'or'. For example, resolving the `intersect` expression above requires
that we say "we can allow `xs` and `ys` to both be bags, or both be
lists, or one to be a bag and the other a list, but they must have
same element type".  Furthermore, we need to derive the result type,
saying "the result of the query is a list if both arguments are lists,
otherwise a bag, with the same element type as the arguments".

We needed a system where we can place a number of constraints on type
variables, and then solve for those constraints. The new type
inference algorithm extends Hindley-Milner with constraints, using the
approach described in
["A Second Look at Overloading" (Odersky, Wadler, Wehr, 1995)](https://dl.acm.org/doi/pdf/10.1145/224164.224195).
As the title of that paper suggests, we have [added a kind of
overloading](https://github.com/hydromatic/morel/issues/237) to Morel;
it is as if the `intersect` operator now has four forms:

 * <code>intersect: &alpha; bag * &alpha; bag &rarr; &alpha; bag</code>
 * <code>intersect: &alpha; bag * &alpha; list &rarr; &alpha; bag</code>
 * <code>intersect: &alpha; list * &alpha; bag &rarr; &alpha; bag</code>
 * <code>intersect: &alpha; list * &alpha; list &rarr; &alpha; list</code>

(and similar overloads for the other relational operators) and the
type inference algorithm solves the constraints to land on one valid
assignment of types.

The algorithm took several months of hard work to implement, but the
results are pleasing.  Morel retains the key benefits of a
Hindley-Milner type system: strong static typing, runtime efficiency,
and type inference without the need for type annotations.

Like any other major change in architecture, moving to
constraint-based type inference will take a while to mature; 
[[MOREL-270](https://github.com/hydromatic/morel/issues/270)]
and
[[MOREL-271](https://github.com/hydromatic/morel/issues/271)]
describe some of the remaining issues.