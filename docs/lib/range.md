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

# Range structure

[Up to index](index.md)

[//]: # (start:lib/range)
The `Range` structure represents contiguous intervals of values of an
ordered type, including open, closed, and half-open intervals, as well as
unbounded intervals.

A range is a value of the `'a range` datatype, which has nine constructors:
`AT_LEAST`, `AT_MOST`, `CLOSED`, `CLOSED_OPEN`, `GREATER_THAN`, `LESS_THAN`,
`OPEN`, `OPEN_CLOSED`, and `POINT`.

Naming conventions follow Guava and standard mathematical notation:
CLOSED endpoints are inclusive `[a, b]`;
OPEN endpoints are exclusive `(a, b)`.

## Synopsis

<pre>
datatype 'a <a id='range' href="#range-impl">range</a> =
    AT_LEAST of 'a
  | AT_MOST of 'a
  | CLOSED of 'a * 'a
  | CLOSED_OPEN of 'a * 'a
  | GREATER_THAN of 'a
  | LESS_THAN of 'a
  | OPEN of 'a * 'a
  | OPEN_CLOSED of 'a * 'a
  | POINT of 'a

val <a id='contains' href="#contains-impl">contains</a> : 'a range -> 'a -> bool
val <a id='normalize' href="#normalize-impl">normalize</a> : 'a range list -> 'a range list
val <a id='toBag' href="#toBag-impl">toBag</a> : 'a range list -> 'a bag
val <a id='toList' href="#toList-impl">toList</a> : 'a range list -> 'a list
</pre>

<a id="range-impl"></a>
<h3><code><strong>datatype</strong> 'a range</code></h3>

represents a contiguous interval of values of an ordered type.

The constructors and their meanings are:
* `AT_LEAST v`: `x >= v`
* `AT_MOST v`: `x <= v`
* `CLOSED (lo, hi)`: `x >= lo andalso x <= hi`
* `CLOSED_OPEN (lo, hi)`: `x >= lo andalso x < hi`
* `GREATER_THAN v`: `x > v`
* `LESS_THAN v`: `x < v`
* `OPEN (lo, hi)`: `x > lo andalso x < hi`
* `OPEN_CLOSED (lo, hi)`: `x > lo andalso x <= hi`
* `POINT v`: `x = v`

<a id="contains-impl"></a>
<h3><code>contains</code></h3>

`contains r x` (or `r.contains x`) returns `true` if `x` is a member of range `r`.

The ordering is implicit, derived from the type `α`.

<a id="normalize-impl"></a>
<h3><code>normalize</code></h3>

`normalize ranges` returns a minimal list of ranges that cover the same set of values as
`ranges`. Overlapping and adjacent ranges are merged, and the result is
sorted by lower bound.

The ordering is implicit, derived from the element type.

<a id="toBag-impl"></a>
<h3><code>toBag</code></h3>

`toBag ranges` enumerates all values covered by `ranges` and returns them as a bag.
The element type must be discrete (e.g., `int`, `char`, `bool`).
Raises an exception if any range is unbounded below and the type has no
minimum value (e.g., `LESS_THAN 5 : int range`).

<a id="toList-impl"></a>
<h3><code>toList</code></h3>

`toList ranges` enumerates all values covered by `ranges` and returns them as a list,
in ascending order. The element type must be discrete (e.g., `int`,
`char`, `bool`).
Raises an exception if any range is unbounded below and the type has no
minimum value (e.g., `LESS_THAN 5 : int range`).

[//]: # (end:lib/range)

<!-- End range.md -->
