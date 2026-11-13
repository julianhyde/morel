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

# Relational structure

[Up to index](index.md)

[//]: # (start:lib/relational)
The `Relational` structure provides aggregation, comparison, and set
operations that are used in Morel `from` expressions. These functions
extend Standard ML with relational-algebra capabilities.

## Synopsis

<pre>
datatype 'a <a id='descending' href="#descending-impl">descending</a> = DESC of 'a

val <a id='avg' href="#avg-impl">avg</a> : int bag -> real
val <a id='avgOpt' href="#avgOpt-impl">avgOpt</a> : int bag -> real option
val <a id='avgSome' href="#avgSome-impl">avgSome</a> : int option bag -> real option
val <a id='compare' href="#compare-impl">compare</a> : 'a * 'a -> order
val <a id='count' href="#count-impl">count</a> : 'a bag -> int
val <a id='countSome' href="#countSome-impl">countSome</a> : 'a option bag -> int
val <a id='empty' href="#empty-impl">empty</a> : 'a bag -> bool
val <a id='iterate' href="#iterate-impl">iterate</a> : 'a bag -> ('a bag * 'a bag -> 'a bag) -> 'a bag
val <a id='max' href="#max-impl">max</a> : 'a bag -> 'a
val <a id='maxBy' href="#maxBy-impl">maxBy</a> : ('a -> 'b) -> 'a bag -> 'a
val <a id='maxOpt' href="#maxOpt-impl">maxOpt</a> : 'a bag -> 'a option
val <a id='maxSome' href="#maxSome-impl">maxSome</a> : 'a option bag -> 'a option
val <a id='min' href="#min-impl">min</a> : 'a bag -> 'a
val <a id='minBy' href="#minBy-impl">minBy</a> : ('a -> 'b) -> 'a bag -> 'a
val <a id='minOpt' href="#minOpt-impl">minOpt</a> : 'a bag -> 'a option
val <a id='minSome' href="#minSome-impl">minSome</a> : 'a option bag -> 'a option
val <a id='nonEmpty' href="#nonEmpty-impl">nonEmpty</a> : 'a bag -> bool
val <a id='only' href="#only-impl">only</a> : 'a bag -> 'a
val <a id='somes' href="#somes-impl">somes</a> : 'a option bag -> 'a bag
val <a id='stddevPop' href="#stddevPop-impl">stddevPop</a> : int bag -> real
val <a id='stddevPopOpt' href="#stddevPopOpt-impl">stddevPopOpt</a> : int bag -> real option
val <a id='stddevPopSome' href="#stddevPopSome-impl">stddevPopSome</a> : int option bag -> real option
val <a id='stddevSamp' href="#stddevSamp-impl">stddevSamp</a> : int bag -> real
val <a id='stddevSampOpt' href="#stddevSampOpt-impl">stddevSampOpt</a> : int bag -> real option
val <a id='stddevSampSome' href="#stddevSampSome-impl">stddevSampSome</a> : int option bag -> real option
val <a id='sum' href="#sum-impl">sum</a> : 'a bag -> 'a
val <a id='sumOpt' href="#sumOpt-impl">sumOpt</a> : 'a bag -> 'a option
val <a id='sumSome' href="#sumSome-impl">sumSome</a> : 'a option bag -> 'a option
val <a id='varPop' href="#varPop-impl">varPop</a> : int bag -> real
val <a id='varPopOpt' href="#varPopOpt-impl">varPopOpt</a> : int bag -> real option
val <a id='varPopSome' href="#varPopSome-impl">varPopSome</a> : int option bag -> real option
val <a id='varSamp' href="#varSamp-impl">varSamp</a> : int bag -> real
val <a id='varSampOpt' href="#varSampOpt-impl">varSampOpt</a> : int bag -> real option
val <a id='varSampSome' href="#varSampSome-impl">varSampSome</a> : int option bag -> real option
</pre>

<a id="descending-impl"></a>
<h3><code><strong>datatype</strong> 'a descending</code></h3>

wraps a value so that it sorts in descending order when used with
`Relational.compare`.

<a id="avg-impl"></a>
<h3><code>avg</code></h3>

`avg list` (or `list.avg ()`) returns the mean of the elements of `list`. Often used with `group`, for
example `from e in emps group e.deptno compute avgSal = avg over e.sal`.

The type is overloaded: the mean of `int` values is a `real`, of `real`
values a `real`, and of `decimal` values a `decimal`.

Raises `Empty` if `list` is empty.

<a id="avgOpt-impl"></a>
<h3><code>avgOpt</code></h3>

`avgOpt list` (or `list.avgOpt ()`) returns `SOME` of the mean of the elements of `list`, as `avg` does, or
`NONE` if `list` is empty.

To ignore `NONE`, as SQL's `AVG` ignores nulls, use `avgSome`.

<a id="avgSome-impl"></a>
<h3><code>avgSome</code></h3>

`avgSome list` (or `list.avgSome ()`) returns `SOME` of the mean of the values of the `SOME` elements of
`list`, ignoring `NONE`, or `NONE` if there are none. It is SQL's `AVG`,
which ignores nulls.

The type is overloaded, as `avg`'s is.

<a id="compare-impl"></a>
<h3><code>compare</code></h3>

`compare (x, y)` returns `LESS`, `EQUAL`, or `GREATER` according to
whether its first argument is less than, equal to, or greater than the
second.

Comparisons are based on the structure of the type `α`.
Primitive types are compared using their natural order;
Option types compare with NONE first;
Tuple types compare lexicographically;
Record types compare lexicographically, with the fields
compared in alphabetical order;
List values compare lexicographically;
Bag values compare lexicographically, the elements appearing
in an order that is arbitrary but is consistent for each
particular value.

<a id="count-impl"></a>
<h3><code>count</code></h3>

`count list` (or `list.count ()`) returns the number of elements in `list`. Often used with
`group`, for example `from e in emps group e.deptno compute countId =
count`.

Counts every element, including `NONE`; `countSome` counts the `SOME`s.

<a id="countSome-impl"></a>
<h3><code>countSome</code></h3>

`countSome list` (or `list.countSome ()`) returns the number of `SOME` elements of `list`. It is SQL's `COUNT(x)`,
which ignores nulls.

<a id="empty-impl"></a>
<h3><code>empty</code></h3>

`empty list` (or `list.empty ()`) returns whether the list is empty, for example `from d in
depts where empty (from e where e.deptno = d.deptno)`.

<a id="iterate-impl"></a>
<h3><code>iterate</code></h3>

`iterate initialList listUpdate` (or `initialList.iterate listUpdate`) computes a fixed point, starting with `initialList` and calling
`listUpdate (prevList, newList)` each iteration, terminating the
iteration when it returns `newList`.

<a id="max-impl"></a>
<h3><code>max</code></h3>

`max list` (or `list.max ()`) returns the greatest element of `list`. Often used with
`group`, for example `from e in emps group e.deptno compute maxId =
max of e.id`.

Elements are compared as `compare` compares them, so `NONE` is less than
any `SOME`. Raises `Empty` if `list` is empty.

<a id="maxBy-impl"></a>
<h3><code>maxBy</code></h3>

`maxBy keyFn list` returns the element of `list` for which `keyFn` gives the greatest key,
for example `maxBy String.size ["john", "paul", "george", "ringo"]`,
which returns `"george"`.

Often used with `group` to deduplicate whole rows, for example
`from p in product_versions group p.id compute latest = maxBy
#version over p`.

If several elements are tied for the greatest key, and the input is
unordered, it is not specified which of them is returned.

Raises `Empty` if `list` is empty.

<a id="maxOpt-impl"></a>
<h3><code>maxOpt</code></h3>

`maxOpt list` (or `list.maxOpt ()`) returns `SOME` of the greatest element of `list`, as `max` does, or `NONE`
if `list` is empty.

To ignore `NONE`, as SQL's `MAX` ignores nulls, use `maxSome`.

<a id="maxSome-impl"></a>
<h3><code>maxSome</code></h3>

`maxSome list` (or `list.maxSome ()`) returns `SOME` of the greatest value of the `SOME` elements of `list`,
ignoring `NONE`, or `NONE` if there are none. It is SQL's `MAX`, which
ignores nulls.

<a id="min-impl"></a>
<h3><code>min</code></h3>

`min list` (or `list.min ()`) returns the least element of `list`. Often used with
`group`, for example `from e in emps group e.deptno compute minId =
min of e.id`.

Elements are compared as `compare` compares them, so `NONE` is less than
any `SOME`. Raises `Empty` if `list` is empty.

<a id="minBy-impl"></a>
<h3><code>minBy</code></h3>

`minBy keyFn list` returns the element of `list` for which `keyFn` gives the least key,
for example `minBy String.size ["john", "paul", "george", "ringo"]`,
which returns `"john"`.

If several elements are tied for the least key, and the input is
unordered, it is not specified which of them is returned.

Raises `Empty` if `list` is empty.

<a id="minOpt-impl"></a>
<h3><code>minOpt</code></h3>

`minOpt list` (or `list.minOpt ()`) returns `SOME` of the least element of `list`, as `min` does, or `NONE`
if `list` is empty.

To ignore `NONE`, as SQL's `MIN` ignores nulls, use `minSome`.

<a id="minSome-impl"></a>
<h3><code>minSome</code></h3>

`minSome list` (or `list.minSome ()`) returns `SOME` of the least value of the `SOME` elements of `list`,
ignoring `NONE`, or `NONE` if there are none. It is SQL's `MIN`, which
ignores nulls.

<a id="nonEmpty-impl"></a>
<h3><code>nonEmpty</code></h3>

`nonEmpty list` (or `list.nonEmpty ()`) returns whether the list has at least one element, for
example `from d in depts where nonEmpty (from e where e.deptno =
d.deptno)`.

<a id="only-impl"></a>
<h3><code>only</code></h3>

`only list` (or `list.only ()`) returns the sole element of list, for example `from e in
emps yield only (from d where d.deptno = e.deptno)`.

<a id="somes-impl"></a>
<h3><code>somes</code></h3>

`somes list` (or `list.somes ()`) returns the values of the `SOME` elements of `list`, dropping each
`NONE`; for example, `somes [SOME 1, NONE, SOME 3]` returns `[1, 3]`.
The result is a bag; for a list, `List.mapPartial (fn x => x)` keeps
the order.

`sumOpt (somes list)` is `sumSome list`, and so on.

<a id="stddevPop-impl"></a>
<h3><code>stddevPop</code></h3>

`stddevPop list` (or `list.stddevPop ()`) returns the population standard deviation of the elements of `list`.

The type is overloaded: the elements may be `int`, `real` or `decimal`, and
the result is a `real`. Raises `Empty` if `list` is empty.

<a id="stddevPopOpt-impl"></a>
<h3><code>stddevPopOpt</code></h3>

`stddevPopOpt list` (or `list.stddevPopOpt ()`) returns `SOME` of the population standard deviation of the elements of
`list`, as `stddevPop` does, or `NONE` if `list` is empty.

To ignore `NONE`, as SQL's `STDDEV_POP` ignores nulls, use `stddevPopSome`.

<a id="stddevPopSome-impl"></a>
<h3><code>stddevPopSome</code></h3>

`stddevPopSome list` (or `list.stddevPopSome ()`) returns `SOME` of the population standard deviation of the values of the
`SOME` elements of `list`, ignoring `NONE`, or `NONE` if there are none. It
is SQL's `STDDEV_POP`, which ignores nulls.

The type is overloaded, as `stddevPop`'s is.

<a id="stddevSamp-impl"></a>
<h3><code>stddevSamp</code></h3>

`stddevSamp list` (or `list.stddevSamp ()`) returns the sample standard deviation of the elements of `list`.

The type is overloaded: the elements may be `int`, `real` or `decimal`, and
the result is a `real`. Raises `Empty` if `list` has fewer than two
elements.

The sample standard deviation divides by one less than the number of
elements.

<a id="stddevSampOpt-impl"></a>
<h3><code>stddevSampOpt</code></h3>

`stddevSampOpt list` (or `list.stddevSampOpt ()`) returns `SOME` of the sample standard deviation of the elements of `list`,
as `stddevSamp` does, or `NONE` if `list` has fewer than two elements.

To ignore `NONE`, as SQL's `STDDEV_SAMP` ignores nulls, use
`stddevSampSome`.

<a id="stddevSampSome-impl"></a>
<h3><code>stddevSampSome</code></h3>

`stddevSampSome list` (or `list.stddevSampSome ()`) returns `SOME` of the sample standard deviation of the values of the `SOME`
elements of `list`, ignoring `NONE`, or `NONE` if there are fewer than two.
It is SQL's `STDDEV_SAMP`, which ignores nulls.

The type is overloaded, as `stddevSamp`'s is.

<a id="sum-impl"></a>
<h3><code>sum</code></h3>

`sum list` (or `list.sum ()`) returns the sum of the elements of `list`. Often used with
`group`, for example `from e in emps group e.deptno compute sumId =
sum of e.id`.

The elements may be `int`, `real` or `decimal`. Returns 0 if `list` is
empty.

<a id="sumOpt-impl"></a>
<h3><code>sumOpt</code></h3>

`sumOpt list` (or `list.sumOpt ()`) returns `SOME` of the sum of the elements of `list`, or `NONE` if `list`
is empty.

To ignore `NONE`, as SQL's `SUM` ignores nulls, use `sumSome`.

<a id="sumSome-impl"></a>
<h3><code>sumSome</code></h3>

`sumSome list` (or `list.sumSome ()`) returns `SOME` of the sum of the values of the `SOME` elements of
`list`, ignoring `NONE`, or `NONE` if there are none. It is SQL's `SUM`,
which ignores nulls.

<a id="varPop-impl"></a>
<h3><code>varPop</code></h3>

`varPop list` (or `list.varPop ()`) returns the population variance of the elements of `list`.

The type is overloaded: the elements may be `int`, `real` or `decimal`, and
the result is a `real`. Raises `Empty` if `list` is empty.

<a id="varPopOpt-impl"></a>
<h3><code>varPopOpt</code></h3>

`varPopOpt list` (or `list.varPopOpt ()`) returns `SOME` of the population variance of the elements of `list`, as
`varPop` does, or `NONE` if `list` is empty.

To ignore `NONE`, as SQL's `VAR_POP` ignores nulls, use `varPopSome`.

<a id="varPopSome-impl"></a>
<h3><code>varPopSome</code></h3>

`varPopSome list` (or `list.varPopSome ()`) returns `SOME` of the population variance of the values of the `SOME`
elements of `list`, ignoring `NONE`, or `NONE` if there are none. It is
SQL's `VAR_POP`, which ignores nulls.

The type is overloaded, as `varPop`'s is.

<a id="varSamp-impl"></a>
<h3><code>varSamp</code></h3>

`varSamp list` (or `list.varSamp ()`) returns the sample variance of the elements of `list`.

The type is overloaded: the elements may be `int`, `real` or `decimal`, and
the result is a `real`. Raises `Empty` if `list` has fewer than two
elements.

The sample variance divides by one less than the number of elements.

<a id="varSampOpt-impl"></a>
<h3><code>varSampOpt</code></h3>

`varSampOpt list` (or `list.varSampOpt ()`) returns `SOME` of the sample variance of the elements of `list`, as
`varSamp` does, or `NONE` if `list` has fewer than two elements.

To ignore `NONE`, as SQL's `VAR_SAMP` ignores nulls, use `varSampSome`.

<a id="varSampSome-impl"></a>
<h3><code>varSampSome</code></h3>

`varSampSome list` (or `list.varSampSome ()`) returns `SOME` of the sample variance of the values of the `SOME` elements
of `list`, ignoring `NONE`, or `NONE` if there are fewer than two. It is
SQL's `VAR_SAMP`, which ignores nulls.

The type is overloaded, as `varSamp`'s is.

[//]: # (end:lib/relational)
