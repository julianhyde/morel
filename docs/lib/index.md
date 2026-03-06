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

# Structure index

[Up to reference](../reference.md#structures)

| Structure | Description | Members |
| --------- | ----------- | ------- |
| [Bag](bag.md) | Unordered collection of elements with duplicates. | `@`, `all`, `app`, `collate`, `concat`, `drop`, `exists`, `filter`, `find`, `fold`, `fromList`, `getItem`, `hd`, `length`, `map`, `mapPartial`, `nil`, `nth`, `null`, `partition`, `tabulate`, `take`, `tl`, `toList` |
| [Bool](bool.md) | Boolean values and operations. | `bool`, `fromString`, `implies`, `not`, `toString` |
| [Char](char.md) | Character values and operations. | `char`, `<`, `<=`, `>`, `>=`, `chr`, `compare`, `contains`, `fromCString`, `fromInt`, `fromString`, `isAlpha`, `isAlphaNum`, `isAscii`, `isCntrl`, `isDigit`, `isGraph`, `isHexDigit`, `isLower`, `isOctDigit`, `isPrint`, `isPunct`, `isSpace`, `isUpper`, `maxChar`, `maxOrd`, `minChar`, `notContains`, `ord`, `pred`, `succ`, `toCString`, `toLower`, `toString`, `toUpper` |
| [Datalog](datalog.md) | Datalog query interface. | `execute`, `translate`, `validate` |
| [Either](either.md) | Values that are one of two types. | `either`, `app`, `appLeft`, `appRight`, `asLeft`, `asRight`, `fold`, `isLeft`, `isRight`, `map`, `mapLeft`, `mapRight`, `partition`, `proj` |
| [Fn](fn.md) | Higher-order function combinators. | `apply`, `const`, `curry`, `equal`, `flip`, `id`, `notEqual`, `o`, `repeat`, `uncurry` |
| [General](general.md) | Basic types, exceptions, and utility functions. | `exn`, `order`, `unit`, `Bind`, `Chr`, `Div`, `Domain`, `Fail`, `Match`, `Overflow`, `Size`, `Span`, `Subscript`, `before`, `exnMessage`, `exnName`, `ignore`, `o` |
| [IEEEReal](ieee-real.md) | IEEE 754 floating-point definitions. | `decimal_approx`, `float_class`, `real_order`, `rounding_mode` |
| [Int](int.md) | Fixed-precision integer operations. | `int`, `*`, `+`, `-`, `<`, `<=`, `>`, `>=`, `abs`, `compare`, `div`, `fmt`, `fromInt`, `fromLarge`, `fromString`, `max`, `maxInt`, `min`, `minInt`, `mod`, `precision`, `quot`, `rem`, `sameSign`, `scan`, `sign`, `toInt`, `toLarge`, `toString`, `~` |
| [IntInf](int-inf.md) | Arbitrary-precision integer operations. | `int` |
| [Interact](interact.md) | Interactive session utilities. | `use`, `useSilently` |
| [List](list.md) | Polymorphic singly-linked lists. | `list`, `Empty`, `@`, `all`, `app`, `at`, `collate`, `concat`, `drop`, `except`, `exists`, `filter`, `find`, `foldl`, `foldr`, `getItem`, `hd`, `intersect`, `last`, `length`, `map`, `mapPartial`, `mapi`, `nil`, `nth`, `null`, `partition`, `rev`, `revAppend`, `tabulate`, `take`, `tl` |
| [ListPair](list-pair.md) | Operations on pairs of lists. | `UnequalLengths`, `all`, `allEq`, `app`, `appEq`, `exists`, `foldl`, `foldlEq`, `foldr`, `foldrEq`, `map`, `mapEq`, `unzip`, `zip`, `zipEq` |
| [Math](math.md) | Mathematical functions for real numbers. | `acos`, `asin`, `atan`, `atan2`, `cos`, `cosh`, `e`, `exp`, `ln`, `log10`, `pi`, `pow`, `sin`, `sinh`, `sqrt`, `tan`, `tanh` |
| [Option](option.md) | Optional values. | `option`, `Option`, `app`, `compose`, `composePartial`, `filter`, `getOpt`, `isSome`, `join`, `map`, `mapPartial`, `valOf` |
| [Real](real.md) | Floating-point number operations. | `real`, `!=`, `*`, `*+`, `*-`, `+`, `-`, `/`, `<`, `<=`, `==`, `>`, `>=`, `?=`, `abs`, `ceil`, `checkFloat`, `class`, `compare`, `compareReal`, `copySign`, `floor`, `fmt`, `fromDecimal`, `fromInt`, `fromLarge`, `fromLargeInt`, `fromManExp`, `fromString`, `isFinite`, `isNan`, `isNormal`, `max`, `maxFinite`, `min`, `minNormalPos`, `minPos`, `negInf`, `nextAfter`, `posInf`, `precision`, `radix`, `realCeil`, `realFloor`, `realMod`, `realRound`, `realTrunc`, `rem`, `round`, `sameSign`, `scan`, `sign`, `signBit`, `split`, `toDecimal`, `toInt`, `toLarge`, `toLargeInt`, `toManExp`, `toString`, `trunc`, `unordered`, `~` |
| [Relational](relational.md) | Relational algebra operations for Morel queries. | `descending`, `compare`, `count`, `elem`, `empty`, `iterate`, `max`, `min`, `nonEmpty`, `notelem`, `only`, `sum` |
| [String](string.md) | String operations. | `string`, `<`, `<=`, `>`, `>=`, `^`, `collate`, `compare`, `concat`, `concatWith`, `explode`, `extract`, `fields`, `fromCString`, `fromString`, `implode`, `isPrefix`, `isSubstring`, `isSuffix`, `map`, `maxSize`, `scan`, `size`, `str`, `sub`, `substring`, `toCString`, `toString`, `tokens`, `translate` |
| [StringCvt](string-cvt.md) | String conversion utilities and types. | `radix`, `reader`, `realfmt` |
| [Sys](sys.md) | System interface utilities. | `clearEnv`, `env`, `file`, `plan`, `planEx`, `set`, `show`, `showAll`, `unset` |
| [Variant](variant.md) | Dynamically-typed variant values. | `variant`, `parse`, `print` |
| [Vector](vector.md) | Immutable fixed-length arrays. | `vector`, `all`, `app`, `appi`, `collate`, `concat`, `exists`, `find`, `findi`, `foldl`, `foldli`, `foldr`, `foldri`, `fromList`, `length`, `map`, `mapi`, `maxLen`, `sub`, `tabulate`, `update` |
