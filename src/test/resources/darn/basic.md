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

<div class="highlighter-rouge morel">
<pre class="morel-input highlight"><code><span class="mi">1</span> <span class="o">+</span> <span class="mi">2</span><span class="p">;</span></code></pre>
<pre class="morel-output highlight"><code><span class="c">val it = 3 : int</span></code></pre>
</div>

## Morel keywords and type variables

<!-- morel skip
fun len [] = 0
  | len (_ :: tl) = 1 + len tl;
-->

<div class="highlighter-rouge morel">
<pre class="morel-input highlight"><code><span class="kr">fun</span> <span class="nf">len</span> <span class="p">[]</span> <span class="p">=</span> <span class="mi">0</span>
  <span class="p">|</span> <span class="n">len</span> <span class="p">(</span><span class="n">_</span> <span class="o">::</span> <span class="n">tl</span><span class="p">)</span> <span class="p">=</span> <span class="mi">1</span> <span class="o">+</span> <span class="n">len</span> <span class="n">tl</span><span class="p">;</span></code></pre>
</div>

## Silent cell (no HTML block generated)

<!-- morel silent
val hidden = 99;
> val hidden = 99 : int
-->
