<!--
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
-->

# Darn test fixture: basic

## Simple expression

<!-- morel
1 + 2;
> val it = 3 : int
-->

<div class="code-block">
<div class="code-input"><span class="num">1</span> <span class="op">+</span> <span class="num">2</span>;</div>
<div class="code-output">val it = 3 : int</div>
</div>

## Morel keywords and type variables

<!-- morel skip
fun len [] = 0
  | len (_ :: tl) = 1 + len tl;
-->

<div class="code-block">
<div class="code-input"><span class="kw">fun</span> len [] = <span class="num">0</span>
  | len (_ <span class="op">::</span> tl) = <span class="num">1</span> <span class="op">+</span> len tl;</div>
</div>

## Silent cell (no HTML block generated)

<!-- morel silent
val hidden = 99;
> val hidden = 99 : int
-->
