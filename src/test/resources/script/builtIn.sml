(*
 * Licensed to Julian Hyde under one or more contributor license
 * agreements.  See the NOTICE file distributed with this work
 * for additional information regarding copyright ownership.
 * Julian Hyde licenses this file to you under the Apache
 * License, Version 2.0 (the "License"); you may not use this
 * file except in compliance with the License.  You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.  See the License for the specific
 * language governing permissions and limitations under the
 * License.
 *)
Sys.set ("lineWidth", 78);
Sys.set ("printLength", 64);
Sys.set ("stringDepth", ~1);

(* Structures -------------------------------------------------- *)
General;
Interact;
List;
List.rev;
List.rev [1,2,3];
Math;
Option;
Option.compose;
String;
Real;
Relational;

(* Operators --------------------------------------------------- *)
2 + 3;
2 + 3 * 4;
Sys.plan ();

fn x => x + 1;
Sys.plan ();

val nan = Real.posInf / Real.negInf;

(* Datatypes --------------------------------------------------- *)

(*) datatype option
SOME 1;
NONE;
SOME (SOME true);

(* General ----------------------------------------------------- *)

(*) op o - function composition
val plusOne = fn x => x + 1;
val timesTwo = fn x => x * 2;
val plusThree = fn x => x + 3;
plusOne o timesTwo;
(plusOne o timesTwo) 3;
plusOne o timesTwo o plusThree;
((plusOne o timesTwo) o plusThree) 3;
(plusOne o (timesTwo o plusThree)) 3;
Sys.plan ();

ignore;
ignore (1 + 2);
Sys.plan ();

(* Interact ---------------------------------------------------- *)

(*) use - load source from a file
Interact.use;
use;

(* String ------------------------------------------------------ *)

(*) val maxSize : int
String.maxSize;
Sys.plan ();

(*) val size : string -> int
String.size;
String.size "abc";
String.size "";
Sys.plan ();

(*) val sub : string * int -> char
String.sub;
String.sub("abc", 0);
String.sub("abc", 2);
String.sub("abc", 20);
String.sub("abc", 3);
String.sub("abc", ~1);
Sys.plan ();

(*) val extract: string * int * int option -> string
String.extract;
String.extract("abc", 1, NONE);
String.extract("abc", 1, SOME 2);
String.extract("abc", 3, NONE);
String.extract("abc", 3, SOME 0);
String.extract("abc", 4, NONE);
String.extract("abc", ~1, NONE);
String.extract("abc", 4, SOME 2);
String.extract("abc", ~1, SOME 2);
String.extract("abc", 1, SOME ~1);
String.extract("abc", 1, SOME 99);
Sys.plan ();

(*) val substring : string * int * int -> string
String.substring;
String.substring("hello, world", 2, 7);
String.substring("hello, world", 0, 1);
String.substring("hello", 5, 0);
String.substring("hello", 1, 4);
String.substring("", 0, 0);
String.substring("hello", ~1, 0);
String.substring("hello", 1, ~1);
String.substring("hello", 1, 5);
Sys.plan ();

(*) val ^ : string * string -> string
"a" ^ "bc";
"a" ^ "";
"a" ^ "bc" ^ "" ^ "def";
Sys.plan ();

(*) val concat : string list -> string
String.concat;
String.concat ["a", "bc", "def"];
String.concat ["a"];
String.concat [];
Sys.plan ();

(*) val concatWith : string -> string list -> string
String.concatWith;
String.concatWith "," ["a", "bc", "def"];
String.concatWith "," ["a"];
String.concatWith "," ["", ""];
String.concatWith "," [];
Sys.plan ();

(*) val str : char -> string
String.str;
String.str #"a";
Sys.plan ();

(*) val implode : char list -> string
String.implode;
String.implode [#"a", #"b", #"c"];
String.implode [];
Sys.plan ();

(*) val explode : string -> char list
String.explode;
String.explode "abc";
String.explode "";
Sys.plan ();

(*) val map : (char -> char) -> string -> string
String.map;
String.map (fn c => if c = #"a" then #"A" else if c = #"c" then #"C" else c) "abc";
String.map (fn c => if c = #"a" then #"A" else if c = #"c" then #"C" else c) "";
Sys.plan ();

(*) val translate : (char -> string) -> string -> string
String.translate;
String.translate (fn c => if c = #"a" then "AA" else if c = #"c" then "CCC" else "-") "abc";
String.translate (fn c => if c = #"a" then "AA" else if c = #"c" then "CCC" else "-") "";
Sys.plan ();

(*) val tokens : (char -> bool) -> string -> string list
(*) val fields : (char -> bool) -> string -> string list
(*) val isPrefix    : string -> string -> bool
String.isPrefix;
String.isPrefix "he" "hello";
String.isPrefix "el" "hello";
String.isPrefix "lo" "hello";
String.isPrefix "bonjour" "hello";
String.isPrefix "el" "";
String.isPrefix "" "hello";
String.isPrefix "" "";
Sys.plan ();

(*) val isSubstring : string -> string -> bool
String.isSubstring;
String.isSubstring "he" "hello";
String.isSubstring "el" "hello";
String.isSubstring "lo" "hello";
String.isSubstring "bonjour" "hello";
String.isSubstring "el" "";
String.isSubstring "" "hello";
String.isSubstring "" "";
Sys.plan ();

(*) val isSuffix    : string -> string -> bool
String.isSuffix;
String.isSuffix "he" "hello";
String.isSuffix "el" "hello";
String.isSuffix "lo" "hello";
String.isSuffix "bonjour" "hello";
String.isSuffix "el" "";
String.isSuffix "" "hello";
String.isSuffix "" "";
Sys.plan ();

(*) val compare : string * string -> order
(*) val collate : (char * char -> order) -> string * string -> order
(*) val <  : string * string -> bool
(*) val <= : string * string -> bool
(*) val >  : string * string -> bool
(*) val >= : string * string -> bool

(*) val toString : string -> String.string
(*) val scan       : (char, 'a) StringCvt.reader
(*)                    -> (string, 'a) StringCvt.reader
(*) val fromString : String.string -> string option
(*) val toCString : string -> String.string
(*) val fromCString : String.string -> string option

(* List -------------------------------------------------------- *)

(*) val nil : 'a list
List.nil;
Sys.plan ();

(*) val null : 'a list -> bool
List.null;
List.null [];
List.null [1];
Sys.plan ();

(*) val length : 'a list -> int
List.length;
List.length [];
List.length [1,2];
Sys.plan ();

(*) val @ : 'a list * 'a list -> 'a list
List.at;
List.at ([1], [2, 3]);
List.at ([1], []);
List.at ([], [2]);
List.at ([], []);
Sys.plan ();

[1] @ [2, 3];
[] @ [];
Sys.plan ();

(*) val hd : 'a list -> 'a
List.hd;
List.hd [1,2,3];
List.hd [];
Sys.plan ();

(*) val tl : 'a list -> 'a list
List.tl;
List.tl [1,2,3];
List.tl [];
Sys.plan ();

(*) val last : 'a list -> 'a
List.last;
List.last [1,2,3];
List.last [];
Sys.plan ();

(*) val getItem : 'a list -> ('a * 'a list) option
List.getItem;
List.getItem [1,2,3];
List.getItem [1];
Sys.plan ();

(*) val nth : 'a list * int -> 'a
List.nth;
List.nth ([1,2,3], 2);
List.nth ([1], 0);
List.nth ([1,2,3], 3);
List.nth ([1,2,3], ~1);
Sys.plan ();

(*) val take : 'a list * int -> 'a list
List.take;
List.take ([1,2,3], 0);
List.take ([1,2,3], 1);
List.take ([1,2,3], 3);
List.take ([1,2,3], 4);
List.take ([1,2,3], ~1);
Sys.plan ();

(*) val drop : 'a list * int -> 'a list
List.drop;
List.drop ([1,2,3], 0);
List.drop ([1,2,3], 1);
List.drop ([1,2,3], 3);
Sys.plan ();

(*) val rev : 'a list -> 'a list
List.rev;
List.rev [1,2,3];
List.rev [2,1];
List.rev [1];
List.rev [];
Sys.plan ();

(*) val concat : 'a list list -> 'a list
List.concat;
List.concat [[1],[2,3],[4,5,6]];
List.concat [[1],[],[4,5,6]];
List.concat [[],[],[]];
List.concat [];
Sys.plan ();

(*) val revAppend : 'a list * 'a list -> 'a list
List.revAppend;
List.revAppend ([1,2],[3,4,5]);
List.revAppend ([1],[3,4,5]);
List.revAppend ([],[3,4,5]);
List.revAppend ([1,2],[]);
List.revAppend ([],[]);
Sys.plan ();

(*) val app : ('a -> unit) -> 'a list -> unit
List.app;
List.app (fn x => ignore (x + 2)) [2,3,4];
List.app (fn x => ignore (x + 2)) [];
Sys.plan ();

(*) val map : ('a -> 'b) -> 'a list -> 'b list
List.map;
List.map (fn x => x + 1) [1,2,3];
List.map (fn x => x + 1) [];
Sys.plan ();

(*) map is alias for List.map
map;
map (fn x => x) [];
Sys.plan ();

(*) val mapPartial : ('a -> 'b option) -> 'a list -> 'b list
List.mapPartial;
List.mapPartial (fn x => if x mod 2 = 0 then NONE else SOME (x + 1)) [1,2,3,5,8];
List.mapPartial (fn x => if x mod 2 = 0 then NONE else SOME (x + 1)) [];
Sys.plan ();

(*) val find : ('a -> bool) -> 'a list -> 'a option
List.find;
List.find (fn x => x mod 7 = 0) [2,3,5,8,13,21,34];
List.find (fn x => x mod 11 = 0) [2,3,5,8,13,21,34];
Sys.plan ();

(*) val filter : ('a -> bool) -> 'a list -> 'a list
List.filter;
List.filter (fn x => x mod 2 = 0) [0,1,2,3,4,5];
List.filter (fn x => x mod 2 = 0) [1,3];
List.filter (fn x => x mod 2 = 0) [];
Sys.plan ();

(*) val partition : ('a -> bool) -> 'a list -> 'a list * 'a list
List.partition;
List.partition (fn x => x mod 2 = 0) [0,1,2,3,4,5];
List.partition (fn x => x mod 2 = 0) [1];
List.partition (fn x => x mod 2 = 0) [];
Sys.plan ();

(*) val foldl : ('a * 'b -> 'b) -> 'b -> 'a list -> 'b
List.foldl;
List.foldl (fn (a, b) => a + b) 0 [1,2,3];
List.foldl (fn (a, b) => a + b) 0 [];
List.foldl (fn (a, b) => b) 0 [1,2,3];
List.foldl (fn (a, b) => a - b) 0 [1,2,3,4];
Sys.plan ();

(*) val foldr : ('a * 'b -> 'b) -> 'b -> 'a list -> 'b
List.foldr;
List.foldr (fn (a, b) => a + b) 0 [1,2,3];
List.foldr (fn (a, b) => a + b) 0 [];
List.foldr (fn (a, b) => b) 0 [1,2,3];
List.foldr (fn (a, b) => a - b) 0 [1,2,3,4];
Sys.plan ();

(*) val exists : ('a -> bool) -> 'a list -> bool
List.exists;
List.exists (fn x => x mod 2 = 0) [1,3,5];
List.exists (fn x => x mod 2 = 0) [2,4,6];
List.exists (fn x => x mod 2 = 0) [1,2,3];
List.exists (fn x => x mod 2 = 0) [];
Sys.plan ();

(*) val all : ('a -> bool) -> 'a list -> bool
List.all;
List.all (fn x => x mod 2 = 0) [1,3,5];
List.all (fn x => x mod 2 = 0) [2,4,6];
List.all (fn x => x mod 2 = 0) [1,2,3];
List.all (fn x => x mod 2 = 0) [];
Sys.plan ();

(*) val tabulate : int * (int -> 'a) -> 'a list
List.tabulate;
List.tabulate (5, let fun fact n = if n = 0 then 1 else n * fact (n - 1) in fact end);
List.tabulate (1, let fun fact n = if n = 0 then 1 else n * fact (n - 1) in fact end);
List.tabulate (0, let fun fact n = if n = 0 then 1 else n * fact (n - 1) in fact end);
List.tabulate (~1, let fun fact n = if n = 0 then 1 else n * fact (n - 1) in fact end);
Sys.plan ();

(*) val collate : ('a * 'a -> order) -> 'a list * 'a list -> order
List.collate;
List.collate (fn (x, y) => if x < y then LESS else if x = y then EQUAL else GREATER) ([1, 2,3], [1,3,4]);
List.collate (fn (x, y) => if x < y then LESS else if x = y then EQUAL else GREATER) ([1,2,3], [1,2,2]);
List.collate (fn (x, y) => if x < y then LESS else if x = y then EQUAL else GREATER) ([1,2,3], [1,2]);
List.collate (fn (x, y) => if x < y then LESS else if x = y then EQUAL else GREATER) ([1,2,3], [1,2,3,4]);
List.collate (fn (x, y) => if x < y then LESS else if x = y then EQUAL else GREATER) ([1,2,3], []);
List.collate (fn (x, y) => if x < y then LESS else if x = y then EQUAL else GREATER) ([], []);
Sys.plan ();

(* Math -------------------------------------------------------- *)
(* The signature MATH specifies basic mathematical constants, the square root
   function, and trigonometric, hyperbolic, exponential, and logarithmic
   functions based on a real type. The functions defined here have roughly the
   same semantics as their counterparts in ISO C's math.h.

   In the functions below, unless specified otherwise, if any argument is a NaN,
   the return value is a NaN. In a list of rules specifying the behavior of a
   function in special cases, the first matching rule defines the semantics. *)

(* "acos x" returns the arc cosine of x. acos is the inverse of cos.
   Its result is guaranteed to be in the closed interval [0, pi]. If
   the magnitude of x exceeds 1.0, returns NaN. *)
Math.acos;
Math.acos 1.0;
Sys.plan ();
List.map (fn x => (x, Math.acos x))
  [1.0, 0.0, ~0.0, ~1.0, 0.5, Math.sqrt 0.5, 2.0, Real.posInf, Real.negInf, nan];

(* "asin x" returns the arc sine of x. asin is the inverse of sin. Its
   result is guaranteed to be in the closed interval [-pi / 2, pi / 2].
   If the magnitude of x exceeds 1.0, returns NaN. *)
Math.asin;
Math.asin 1.0;
Sys.plan ();
List.map (fn x => (x, Math.asin x))
  [1.0, 0.0, ~0.0, ~1.0, 0.5, Math.sqrt 0.5, 2.0, Real.posInf, Real.negInf, nan];

(* "atan x" returns the arc tangent of x. atan is the inverse of
   tan. For finite arguments, the result is guaranteed to be in the
   open interval (-pi / 2, pi / 2). If x is +infinity, it returns pi / 2;
   if x is -infinity, it returns -pi / 2. *)
Math.atan;
Math.atan 0.0;
Sys.plan ();
List.map (fn x => (x, Math.atan x))
  [1.0, 0.0, ~0.0, ~1.0, 0.5, Math.sqrt 0.5, 2.0, Real.posInf, Real.negInf, nan];

(* "atan2 (y, x)" returns the arc tangent of (y / x) in the closed
   interval [-pi, pi], corresponding to angles within +-180
   degrees. The quadrant of the resulting angle is determined using
   the signs of both x and y, and is the same as the quadrant of the
   point (x, y). When x = 0, this corresponds to an angle of 90
   degrees, and the result is (real (sign y)) * pi / 2.0. It holds
   that
     sign (cos (atan2 (y, x))) = sign (x)
   and
     sign (sin (atan2 (y, x))) = sign (y)
   except for inaccuracies incurred by the finite precision of real
   and the approximation algorithms used to compute the mathematical
   functions.  Rules for exceptional cases are specified in the
   following table.

   y                 x         atan2(y, x)
   ================= ========= ==========
   +-0               0 < x     +-0
   +-0               +0        +-0
   +-0               x < 0     +-pi
   +-0               -0        +-pi
   y, 0 < y          +-0       pi/2
   y, y < 0          +-0       -pi/2
   +-y, finite y > 0 +infinity +-0
   +-y, finite y > 0 -infinity +-pi
   +-infinity        finite x  +-pi/2
   +-infinity        +infinity +-pi/4
   +-infinity        -infinity +-3pi/4
*)
Math.atan2;
Math.atan2 (0.0, 1.0);
Sys.plan ();
List.map (fn x => (x, Math.atan2 (x, 1.0)))
  [1.0, 0.0, ~1.0, 0.5, Math.sqrt 0.5, 2.0, Real.posInf, Real.negInf, nan];
List.map (fn (x, y) => (x, y, Math.atan2 (x, y)))
  [(0.0, 1.0), (~0.0, 1.0),
   (0.0, 0.0), (~0.0, 0.0),
   (0.0, ~1.0), (~0.0, ~1.0),
   (2.5, 0.0), (2.5, ~0.0),
   (~2.5, 0.0), (~2.5, ~0.0),
   (3.0, Real.posInf), (~3.0, Real.posInf),
   (4.0, Real.negInf), (~4.0, Real.negInf),
   (Real.posInf, 5.0), (Real.negInf, 5.0),
   (Real.posInf, Real.posInf), (Real.negInf, Real.posInf),
   (Real.posInf, Real.negInf), (Real.negInf, Real.negInf),
   (0.0, nan), (1.0, nan), (~1.0, nan), (Real.posInf, nan), (Real.negInf, nan),
   (nan, 0.0), (nan, 1.0), (nan, ~1.0), (nan, Real.posInf), (nan, Real.negInf),
   (nan, nan)];

(* "cos x" returns the cosine of x, measured in radians. If x is an infinity,
   returns NaN. *)
Math.cos;
Math.cos 0.0;
Sys.plan ();
List.map (fn x => (x, Math.cos x))
  [0.0, ~0.0, Math.pi, Math.pi * 0.5, ~Math.pi, Math.pi * 5.0, Real.posInf, Real.negInf, nan];

(* "cosh x" returns the hyperbolic cosine of x, that is, (e(x) + e(-x)) / 2.
   It has the properties cosh +-0 = 1, cosh +-infinity = +-infinity. *)
Math.cosh;
Math.cosh 0.0;
Sys.plan ();
List.map (fn x => (x, Math.cosh x))
  [0.0, ~0.0, 1.0, Real.posInf, Real.negInf, nan];

(* "val e : real" The base e (2.718281828...) of the natural logarithm. *)
Math.e;
Sys.plan ();

(* "exp x" returns e(x), i.e., e raised to the x(th) power. If x is
   +infinity, it returns +infinity; if x is -infinity, it returns 0. *)
Math.exp;
Math.exp 0.0;
Sys.plan ();
List.map (fn x => (x, Math.exp x))
  [0.0, ~0.0, 1.0, ~2.0, Real.posInf, Real.negInf, nan];

(* "ln x" returns the natural logarithm (base e) of x. If x < 0,
   returns NaN; if x = 0, returns -infinity; if x is infinity, returns
   infinity. *)
Math.ln;
Math.ln 1.0;
Sys.plan ();
List.map (fn x => (x, Math.ln x))
  [1.0, 2.718, Math.e, 0.0, ~0.0, ~3.0, Real.posInf, Real.negInf, nan];

(* "log10 x" returns the decimal logarithm (base 10) of x. If x < 0,
   returns NaN; if x = 0, returns -infinity; if x is infinity, returns
   infinity. *)
Math.log10;
Math.log10 1.0;
Sys.plan ();
List.map (fn x => (x, Math.log10 x))
  [1.0, 10.0, 1000.0, 0.0, ~0.0, ~3.0, Real.posInf, Real.negInf, nan];

(* "val pi : real" The constant pi (3.141592653...). *)
Math.pi;
Sys.plan ();

(* "pow (x, y)" returns x(y), i.e., x raised to the y(th) power. For
   finite x and y, this is well-defined when x > 0, or when x < 0 and
   y is integral. Rules for exceptional cases are specified below.

   x                 y                             pow(x,y)
   ================= ============================= ==========
   x, including NaN  0                             1
   |x| > 1           +infinity                     +infinity
   |x| < 1           +infinity                     +0
   |x| > 1           -infinity                     +0
   |x| < 1           -infinity                     +infinity
   +infinity         y > 0                         +infinity
   +infinity         y < 0                         +0
   -infinity         y > 0, odd integer            -infinity
   -infinity         y > 0, not odd integer        +infinity
   -infinity         y < 0, odd integer            -0
   -infinity         y < 0, not odd integer        +0
   x                 NaN                           NaN
   NaN               y <> 0                        NaN
   +-1               +-infinity                    NaN
   finite x < 0      finite non-integer y          NaN
   +-0               y < 0, odd integer            +-infinity
   +-0               finite y < 0, not odd integer +infinity
   +-0               y > 0, odd integer            +-0
   +-0               y > 0, not odd integer        +0
*)
Math.pow;
Math.pow (2.0, 3.0);
Math.pow (2.0, ~4.0);
Math.pow (100.0, 0.5);
Sys.plan ();
List.map (fn (x, y) => (x, y, Math.pow (x, y)))
  [(0.0, 0.0), (nan, 0.0),
   (2.0, Real.posInf), (~2.0, Real.posInf),
   (0.5, Real.posInf), (~0.5, Real.posInf),
   (3.0, Real.negInf), (~3.0, Real.negInf),
   (0.25, Real.negInf), (~0.25, Real.negInf),
   (Real.posInf, 0.5),
   (Real.posInf, ~0.5),
   (Real.negInf, 7.0),
   (Real.negInf, 8.0),
   (Real.negInf, ~7.0),
   (Real.negInf, ~8.0),
   (9.5, nan),
   (nan, 9.6),
   (1.0, Real.posInf), (~1.0, Real.posInf), (1.0, Real.negInf), (~1.0, Real.negInf),
   (~9.8, 2.5),
   (0.0, ~9.0), (~0.0, ~9.0),
   (0.0, ~10.0), (~0.0, ~10.0),
   (0.0, 11.0), (~0.0, 11.0),
   (0.0, 12.0), (~0.0, 12.0)];

(* "sin x" returns the sine of x, measured in radians.
   If x is an infinity, returns NaN. *)
Math.sin;
Math.sin 0.0;
Sys.plan ();
List.map (fn x => (x, Math.sin x))
  [0.0, ~0.0, Math.pi, Math.pi * 0.5, ~Math.pi, Math.pi * 5.0, Real.posInf, Real.negInf, nan];

(* "sinh x" returns the hyperbolic sine of x, that is, (e(x) - e(-x)) / 2.
   It has the property sinh +-0 = +-0, sinh +-infinity = +-infinity. *)
Math.sinh;
Math.sinh 0.0;
Sys.plan ();
List.map (fn x => (x, Math.sinh x))
  [0.0, ~0.0, 1.0, Real.posInf, Real.negInf, nan];

(* "sqrt x" returns the square root of x. sqrt (~0.0) = ~0.0.
   If x < 0, returns NaN. *)
Math.sqrt;
Math.sqrt 4.0;
Sys.plan ();
List.map (fn x => (x, Math.sqrt x))
  [4.0, 0.0, ~0.0, ~9.0, Real.posInf, Real.negInf, nan];

(* "tan x" returns the tangent of x, measured in radians. If x is an
   infinity, returns NaN. Produces infinities at various finite values,
   roughly corresponding to the singularities of the tangent function. *)
Math.tan;
Math.tan 0.0;
Sys.plan ();
List.map (fn x => (x, Math.tan x))
  [0.0, ~0.0, Math.pi, Math.pi * 0.5, ~Math.pi, Math.pi * 5.0, Real.posInf, Real.negInf, nan];

(* "tanh x" returns the hyperbolic tangent of x, that is, (sinh x) / (cosh x).
   It has the properties tanh +-0 = +-0, tanh +-infinity = +-1. *)
Math.tanh;
Math.tanh 0.0;
Sys.plan ();
List.map (fn x => (x, Math.tanh x))
  [0.0, ~0.0, 1.0, Real.posInf, Real.negInf, nan];

(* Option ------------------------------------------------------ *)
(*) val getOpt : 'a option * 'a -> 'a
Option.getOpt (SOME 1, 2);
Option.getOpt (NONE, 2);
Sys.plan ();

(*) val isSome : 'a option -> bool
Option.isSome (SOME 1);
Option.isSome NONE;
Sys.plan ();

(*) val valOf : 'a option -> 'a
Option.valOf (SOME 1);
(* sml-nj gives:
    stdIn:6.1-6.18 Warning: type vars not generalized because of
       value restriction are instantiated to dummy types (X1,X2,...)
 *)
Option.valOf NONE;
val noneInt = if true then NONE else SOME 0;
Sys.plan ();
Option.valOf noneInt;
Sys.plan ();

(*) val filter : ('a -> bool) -> 'a -> 'a option
Option.filter (fn x => x mod 2 = 0) 1;
Option.filter (fn x => x mod 2 = 0) 2;
Sys.plan ();

(*) val flatten : 'a option option -> 'a option
(*) (This function is called "Option.join" in the Standard ML basis library.)
Option.flatten (SOME (SOME 1));
Option.flatten (SOME noneInt);
(* sml-nj gives
  stdIn:1.2-1.18 Warning: type vars not generalized because of
     value restriction are instantiated to dummy types (X1,X2,...)
*)
Option.flatten NONE;
Sys.plan ();

(*) val app : ('a -> unit) -> 'a option -> unit
Option.app General.ignore (SOME 1);
Option.app General.ignore NONE;
Sys.plan ();

(*) val map : ('a -> 'b) -> 'a option -> 'b option
Option.map String.size (SOME "xyz");
Option.map String.size NONE;
Sys.plan ();

(*) val mapPartial : ('a -> 'b option) -> 'a option -> 'b option
Option.mapPartial (fn s => if s = "" then NONE else (SOME (String.size s))) (SOME "xyz");
Option.mapPartial (fn s => if s = "" then NONE else (SOME (String.size s))) NONE;
Option.mapPartial (fn s => if s = "" then NONE else (SOME (String.size s))) (SOME "");
Sys.plan ();

(*) val compose : ('a -> 'b) * ('c -> 'a option) -> 'c -> 'b option
Option.compose (String.size,
                (fn s => if s = "" then NONE
                 else SOME (String.substring (s, 1, String.size s))))
               "";
Option.compose (String.size,
                (fn s => if s = "" then NONE
                 else SOME (String.substring (s, 0, String.size s))))
               "a";
Option.compose (String.size,
                (fn s => if s = "" then NONE
                 else SOME (String.substring (s, 0, String.size s))))
               "";
Sys.plan ();

(*) val composePartial : ('a -> 'b option) * ('c -> 'a option) -> 'c -> 'b option
Option.composePartial (fn i => if i = 0 then NONE else (SOME i),
                       fn s => if s = "" then NONE else SOME (String.size s))
                      "abc";
Option.composePartial (fn i => if i = 0 then NONE else (SOME i),
                       fn s => if s = "" then NONE else SOME (String.size s))
                      "";
Sys.plan ();

(* Real -------------------------------------------------------- *)

(*) val posInf : real
Real.posInf;

(*) val negInf : real
Real.negInf;

(*) val radix : int
Real.radix;
(*) TODO 2

(*) val precision : int
Real.precision;
(*) TODO 53

(*) val maxFinite : real
Real.maxFinite;
(*) TODO val it = 1.79769313486E308 : real

(*) val minPos : real
Real.minPos;
(*) TODO val it = 4.94065645841E~324 : real

(*) val minNormalPos : real
Real.minNormalPos;
(*) TODO val it = 2.22507385851E~308 : real

(*) val posInf : real
Real.posInf;
(*) TODO val it = inf : real

(*) val negInf : real
Real.negInf;
(*) TODO val it = ~inf : real

(* "r1 + r2" and "r1 - r2" are the sum and difference of r1 and r2. If one
   argument is finite and the other infinite, the result is infinite with the
   correct sign, e.g., 5 - (-infinity) = infinity. We also have infinity +
   infinity = infinity and (-infinity) + (-infinity) = (-infinity). Any other
   combination of two infinities produces NaN. *)
1.0 + ~3.5;

1.0 + Real.posInf;
(*) TODO val it = inf : real
Real.posInf + 2.5;
(*) TODO val it = inf : real
Real.posInf - Real.posInf;
(*) TODO val it = nan : real
Real.posInf + Real.negInf;
(*) TODO val it = nan : real

(* "r1 * r2" is the product of r1 and r2. The product of zero and an infinity
   produces NaN. Otherwise, if one argument is infinite, the result is infinite
   with the correct sign, e.g., -5 * (-infinity) = infinity, infinity *
   (-infinity) = -infinity. *)
0.0 * Real.posInf;
(*) TODO val it = nan : real
0.0 * Real.negInf;
(*) TODO val it = nan : real
~0.0 * Real.negInf;
(*) TODO val it = nan : real
0.5 * 34.6;
(*) TODO val it = 17.3 : real
Real.posInf * 2.0;
(*) TODO val it = inf : real
Real.posInf * Real.negInf;
(*) TODO val it = ~inf : real

(* "r1 / r2" denotes the quotient of r1 and r2. We have 0 / 0 = NaN and
   +-infinity / +-infinity = NaN. Dividing a finite, non-zero number by a zero,
   or an infinity by a finite number produces an infinity with the correct sign.
   (Note that zeros are signed.) A finite number divided by an infinity is 0
   with the correct sign. *)
0.0 / 0.0;
(*) TODO val it = nan : real

Real.posInf / Real.negInf;
(*) TODO val it = nan : real

1.5 / Real.posInf;
(*) TODO val it = 0.0 : real

1.5 / Real.negInf;
(*) TODO val it = ~0.0 : real

~1.5 / Real.negInf;
(*) TODO val it = 0.0 : real

~0.0 + ~0.0;
(*) TODO val it = ~0.0 : real

~0.0 + 0.0;
(*) TODO val it = 0.0 : real

0.0 + ~0.0;
(*) TODO val it = 0.0 : real

(* "rem (x, y)" returns the remainder x - n * y, where n = trunc (x / y). The
    result has the same sign as x and has absolute value less than the absolute
    value of y. If x is an infinity or y is 0, rem returns NaN. If y is an
    infinity, rem returns x. *)
Real.rem (13.0, 5.0);
(*) TODO val it = 3.0 : real
Real.rem (~13.0, 5.0);
(*) TODO val it = ~3.0 : real
Real.rem (13.0, ~5.0);
(*) TODO val it = 3.0 : real
Real.rem (~13.0, ~5.0);
(*) TODO val it = ~3.0 : real
Real.rem (13.0, 0.0);
(*) TODO val it = nan : real
Real.rem (13.0, ~0.0);
(*) TODO val it = nan : real
Real.rem (13.0, Real.negInf);
(*) TODO val it = nan : real
Real.rem (13.0, Real.negInf);
(*) TODO val it = nan : real (per SMLNJ, but spec says 13.0)
Real.rem (13.0, Real.posInf);
(*) TODO val it = nan : real (per SMLNJ, but spec says 13.0)

(* "*+ (a, b, c)" and "*- (a, b, c)" return a * b + c and a * b - c,
   respectively. Their behaviors on infinities follow from the behaviors derived
   from addition, subtraction, and multiplication. *)
(*) TODO Real.*+ (2.0, 3.0, 7.0);
(*) TODO val it = 13.0 : real
(*) TODO Real.*- (2.0, 3.0, 7.0);
(*) TODO val it = ~1.0 : real

(* "~ r" produces the negation of r.
   ~ (+-infinity) = -+infinity. *)
~ 2.0;
~ ~3.5;
~ Real.posInf;
~ Real.negInf;
~ nan;
(*) TODO val it = nan : real

(* "abs r" returns the absolute value |r| of r.
    abs (+-0.0) = +0.0;
    abs (+-infinity) = +infinity;
    abs (+-NaN) = +NaN *)
Real.abs ~5.5;
Real.abs Real.posInf;
Real.abs Real.negInf;
Real.abs nan;
(*) TODO val it = nan : real

(* val min : real * real -> real
   val max : real * real -> real
   These return the smaller (respectively, larger) of the arguments. If exactly
   one argument is NaN, they return the other argument. If both arguments are
   NaN, they return NaN. *)
Real.min (3.5, 4.5);
(*) TODO val it = 3.5 : real
Real.min (3.5, ~4.5);
(*) TODO val it = ~4.5 : real
Real.min (nan, 4.5);
(*) TODO val it = 4.5 : real
Real.min (Real.posInf, 4.5);
(*) TODO val it = 4.5 : real
Real.min (Real.negInf, 4.5);
(*) TODO val it = ~inf : real

Real.max (3.5, 4.5);
(*) TODO val it = 4.5 : real
Real.max (3.5, ~4.5);
(*) TODO val it = 3.5 : real
Real.max (nan, 4.5);
(*) TODO val it = 4.5 : real
Real.max (Real.posInf, 4.5);
(*) TODO val it = inf : real
Real.max (Real.negInf, 4.5);
(*) TODO val it = 4.5 : real

(* "sign r" returns ~1 if r is negative, 0 if r is zero, or 1 if r is positive.
    An infinity returns its sign; a zero returns 0 regardless of its sign.
    It raises Domain on NaN.
Real.sign 2.0;
(*) TODO val it = 1 : int
Real.sign ~3.0;
(*) TODO val it = ~1 : int
Real.sign 0.0;
(*) TODO val it = 0 : int
Real.sign ~0.0;
(*) TODO val it = 0 : int
Real.sign Real.posInf;
(*) TODO val it = 1 : int
Real.sign Real.negInf;
(*) TODO val it = ~1 : int
Real.sign nan;
(*) TODO uncaught exception Domain [domain error]

(* "signBit r" returns true if and only if the sign of r (infinities, zeros,
   and NaN, included) is negative. *)
Real.signBit 2.0;
(*) TODO val it = false : bool
Real.signBit ~3.5;
(*) TODO val it = true : bool
Real.signBit 0.0;
(*) TODO val it = false : bool
Real.signBit ~0.0;
(*) TODO val it = true : bool
Real.signBit Real.posInf;
(*) TODO val it = false : bool
Real.signBit Real.negInf;
(*) TODO val it = true : bool
Real.signBit nan;
(*) TODO val it = true : bool; SMLNJ returns true but spec would suggest false
Real.signBit (~nan);
(*) TODO val it = false : bool; SMLNJ returns false but spec would suggest true

(* "sameSign (r1, r2)" returns true if and only if signBit r1 equals
   signBit r2. *)
Real.sameSign (2.0, 3.5);
(*) TODO val it = true : bool
Real.sameSign (~2.0, Real.negInf);
(*) TODO val it = true : bool
Real.sameSign (2.0, nan);
(*) TODO val it = false : bool
Real.sameSign (~2.0, nan);
(*) TODO val it = true : bool
Real.sameSign (nan, nan);
(*) TODO val it = true : bool

(* "copySign (x, y)" returns x with the sign of y, even if y is NaN. *)
Real.copySign (2.0, Real.posInf);
(*) TODO val it = 2.0 : real
Real.copySign (2.0, Real.negInf);
(*) TODO val it = ~2.0 : real
Real.copySign (2.0, nan);
(*) TODO val it = ~2.0 : real
Real.copySign (~3.5, ~nan);
(*) TODO val it = 3.5 : real
Real.copySign (~3.5, nan);
(*) TODO val it = ~3.5 : real
Real.copySign (2.0, ~0.0);
(*) TODO val it = ~2.0 : real

(* "val compare : real * real -> order" returns LESS, EQUAL, or GREATER
   according to whether its first argument is less than, equal to, or
   greater than the second. It raises IEEEReal.Unordered on unordered
   arguments. *)
Real.compare (2.0, 2.0);
(*) TODO val it = EQUAL : order
Real.compare (~0.0, 0.0);
(*) TODO val it = EQUAL : order
Real.compare (~5.0, Real.posInf);
(*) TODO val it = LESS : order
Real.compare (~5.0, Real.negInf);
(*) TODO val it = GREATER : order
Real.compare (Real.negInf, Real.negInf);
(*) TODO val it = EQUAL : order
Real.compare (Real.negInf, nan);
(*) TODO uncaught exception Unordered
Real.compare (nan, nan);
(*) TODO uncaught exception Unordered

(* "val compareReal : real * real -> IEEEReal.real_order" behaves similarly to
   "Real.compare" except that the values it returns have the extended type
   IEEEReal.real_order and it returns IEEEReal.UNORDERED on unordered
   arguments. *)
Real.compareReal (2.0, 2.0);
(*) TODO val it = EQUAL : IEEEReal.real_order
Real.compareReal (~0.0, 0.0);
(*) TODO val it = EQUAL : IEEEReal.real_order
Real.compareReal (~5.0, Real.posInf);
(*) TODO val it = LESS : IEEEReal.real_order
Real.compareReal (~5.0, Real.negInf);
(*) TODO val it = GREATER : IEEEReal.real_order
Real.compareReal (Real.negInf, Real.negInf);
(*) TODO val it = EQUAL : IEEEReal.real_order
Real.compareReal (Real.negInf, nan);
(*) TODO val it = UNORDERED : IEEEReal.real_order
Real.compareReal (nan, nan);
(*) TODO val it = UNORDERED : IEEEReal.real_order
Real.compareReal (~nan, nan);
(*) TODO val it = UNORDERED : IEEEReal.real_order
Real.compareReal (0.0, ~nan);
(*) TODO val it = UNORDERED : IEEEReal.real_order

(* val < : real * real -> bool
   val <= : real * real -> bool
   val > : real * real -> bool
   val >= : real * real -> bool
   These return true if the corresponding relation holds between the two reals.
  Note that these operators return false on unordered arguments, i.e., if
  either argument is NaN, so that the usual reversal of comparison under
  negation does not hold, e.g., a < b is not the same as not (a >= b). *)
3.0 < 3.0;
3.0 < 5.0;
(*) TODO val it = true : bool
3.0 < nan;
(*) TODO val it = false : bool
nan < 5.0;
(*) TODO val it = false : bool
3.0 < Real.posInf;
(*) TODO val it = true : bool
3.0 < Real.negInf;
(*) TODO val it = false : bool
Real.posInf < Real.posInf;
(*) TODO val it = false : bool

3.0 <= 3.0;
3.0 <= 5.0;
3.0 <= nan;
nan <= 5.0;
3.0 <= Real.posInf;
3.0 <= Real.negInf;
Real.posInf <= Real.posInf;
(*) TODO val it = true : bool

3.0 > 3.0;
3.0 > 5.0;
3.0 > nan;
nan > 5.0;
3.0 > Real.posInf;
3.0 > Real.negInf;
Real.posInf > Real.posInf;

3.0 >= 3.0;
3.0 >= 5.0;
3.0 >= nan;
nan >= 5.0;
3.0 >= Real.posInf;
3.0 >= Real.negInf;
Real.posInf >= Real.posInf;

(* "== (x, y)" eturns true if and only if neither y nor x is NaN, and y and x
   are equal, ignoring signs on zeros. This is equivalent to the IEEE =
   operator. *)
(*) TODO

(* "!= (x, y)" is equivalent to not o op == and the IEEE ?<> operator. *)
(*) TODO

(* "val ?= : real * real -> bool" returns true if either argument is NaN or if
   the arguments are bitwise equal, ignoring signs on zeros. It is equivalent
   to the IEEE ?= operator. *)
(*) TODO

(* "unordered (x, y)" returns true if x and y are unordered, i.e., at least one
   of x and y is NaN. *)
(*) TODO

(* "isFinite x" returns true if x is neither NaN nor an infinity. *)
(*) TODO

(* "isNan x" returns true if x is NaN. *)
(*) TODO

(* "isNormal x" returns true if x is normal, i.e., neither zero, subnormal,
   infinite nor NaN. *)
(*) TODO

(* "class x" returns the IEEEReal.float_class to which x belongs. *)
(*) TODO

(* "toManExp r" returns {man, exp}, where man and exp are the mantissa and
   exponent of r, respectively. Specifically, we have the relation
     r = man * radix(exp)
   where 1.0 <= man * radix < radix. This function is comparable to frexp in
   the C library. If r is +-0, man is +-0 and exp is +0. If r is +-infinity,
   man is +-infinity and exp is unspecified. If r is NaN, man is NaN and exp
   is unspecified. *)
(*) TODO

(* "fromManExp {man, exp}" returns man * radix(exp). This function is comparable
   to ldexp in the C library. Note that, even if man is a non-zero, finite real
   value, the result of fromManExp can be zero or infinity because of underflows
   and overflows. If man is +-0, the result is +-0. If man is +-infinity, the
   result is +-infinity. If man is NaN, the result is NaN. *)
(*) TODO

(* "split r" returns {whole, frac}, where frac and whole are the fractional and
   integral parts of r, respectively. Specifically, whole is integral,
   |frac| < 1.0, whole and frac have the same sign as r, and r = whole + frac.
   This function is comparable to modf in the C library. If r is +-infinity,
   whole is +-infinity and frac is +-0. If r is NaN, both whole and frac are
   NaN. *)
(*) TODO
(*) Real.split ~12.25;
(*) TODO val it = {frac=~0.25,whole=~12.0} : {frac:real, whole:real}

(* "realMod r" returns the fractional part of r. "realMod" is equivalent to
   "#frac o split". *)
Real.realMod ~12.25;
(*) TODO val it = ~0.25 : real

(* "nextAfter (r, t)" returns the next representable real after r in the
   direction of t. Thus, if t is less than r, nextAfter returns the largest
   representable floating-point number less than r. If r = t then it returns
   r. If either argument is NaN, this returns NaN. If r is +-infinity, it
   returns +-infinity. *)
(*) TODO

(* "checkFloat x" raises Overflow if x is an infinity, and raises Div if x is
   NaN. Otherwise, it returns its argument. This can be used to synthesize
   trapping arithmetic from the non-trapping operations given here. Note,
   however, that infinities can be converted to NaNs by some operations, so
   that if accurate exceptions are required, checks must be done after each
   operation. *)
(*) TODO

(* "realFloor r", "realCeil r", "realTrunc r", "realRound r" convert real values
   to integer-valued reals. realFloor produces floor(r), the largest integer not
   larger than r. realCeil produces ceil(r), the smallest integer not less than r.
   realTrunc rounds r towards zero, and realRound rounds to the integer-values
   real value that is nearest to r. If r is NaN or an infinity, these functions
   return r. *)
(*) TODO

(* "floor r", "ceil r", "trunc r", "round r" convert reals to integers.
   floor produces floor(r), the largest int not larger than r.
   ceil produces ceil(r), the smallest int not less than r.
   trunc rounds r towards zero.
   round yields the integer nearest to r. In the case of a tie, it rounds to the
   nearest even integer.

   They raise Overflow if the resulting value cannot be represented as an int,
   for example, on infinity. They raise Domain on NaN arguments.

   These are respectively equivalent to:
     toInt IEEEReal.TO_NEGINF r
     toInt IEEEReal.TO_POSINF r
     toInt IEEEReal.TO_ZERO r
     toInt IEEEReal.TO_NEAREST r *)
(*) TODO

(* "toInt mode x", "toLargeInt mode x" convert the argument x to an integral
   type using the specified rounding mode. They raise Overflow if the result
   is not representable, in particular, if x is an infinity. They raise
   Domain if the input real is NaN. *)
(*) TODO

(* "fromInt i", "fromLargeInt i" convert the integer i to a real value. If the
   absolute value of i is larger than maxFinite, then the appropriate infinity
   is returned. If i cannot be exactly represented as a real value, then the
   current rounding mode is used to determine the resulting value. The top-level
   function real is an alias for Real.fromInt. *)
Real.fromInt;
Real.fromInt 1;
Real.fromInt ~2;
Sys.plan ();

(* "toLarge r", "fromLarge r" convert between values of type real and type
   LargeReal.real. If r is too small or too large to be represented as a real,
   fromLarge will convert it to a zero or an infinity.

   Note that SMLNJ diverges from the the spec. The spec:
      Real.toLarge : real -> LargeReal.real
      Real.fromLarge : LargeReal.real -> real
   SMLNJ:
      Real.toLarge : real -> real
      Real.fromLarge : IEEEReal.rounding_mode -> real -> real *)
(*) TODO

(* "fmt spec r", "toString r" convert reals into strings. The conversion
   provided by the function fmt is parameterized by spec, which has the
   following forms and interpretations.

   SCI arg
     Scientific notation:
       [~]?[0-9].[0-9]+?E[0-9]+
     where there is always one digit before the decimal point, nonzero if the
     number is nonzero. arg specifies the number of digits to appear after the
     decimal point, with 6 the default if arg is NONE. If arg is SOME(0), no
     fractional digits and no decimal point are printed.
   FIX arg
     Fixed-point notation:
       [~]?[0-9]+.[0-9]+?
     arg specifies the number of digits to appear after the decimal point, with
     6 the default if arg is NONE. If arg is SOME(0), no fractional digits and
     no decimal point are printed.
   GEN arg
     Adaptive notation: the notation used is either scientific or fixed-point
     depending on the value converted. arg specifies the maximum number of
     significant digits used, with 12 the default if arg is NONE.
   EXACT
     Exact decimal notation: refer to IEEEReal.toString for a complete
     description of this format.
   In all cases, positive and negative infinities are converted to "inf" and
   "~inf", respectively, and NaN values are converted to the string "nan".

   Refer to StringCvt.realfmt for more details concerning these formats,
   especially the adaptive format GEN.

   fmt raises Size if spec is an invalid precision, i.e., if spec is
     SCI (SOME i) with i < 0
     FIX (SOME i) with i < 0
     GEN (SOME i) with i < 1
   The exception should be raised when fmt spec is evaluated.

  The fmt function allows the user precise control as to the form of the
  resulting string. Note, therefore, that it is possible for fmt to produce
  a result that is not a valid SML string representation of a real value.

  The value returned by toString is equivalent to:
    (fmt (StringCvt.GEN NONE) r)
 *)
(*) TODO

(* "scan getc strm", "fromString s" scan a real value from character source. The
   first version reads from ARG/strm/ using reader getc, ignoring initial
   whitespace. It returns SOME(r,rest) if successful, where r is the scanned
   real value and rest is the unused portion of the character stream strm.
   Values of too large a magnitude are represented as infinities; values of too
   small a magnitude are represented as zeros. The second version returns
   SOME(r) if a real value can be scanned from a prefix of s, ignoring any
   initial whitespace; otherwise, it returns NONE. This function is equivalent
   to StringCvt.scanString scan.

   The functions accept real numbers with the following format:
     [+~-]?([0-9]+.[0-9]+? | .[0-9]+)(e | E)[+~-]?[0-9]+?

   It also accepts the following string representations of non-finite values:
     [+~-]?(inf | infinity | nan)
   where the alphabetic characters are case-insensitive. *)
(*) TODO

(* "toDecimal r", "fromDecimal d" convert between real values and decimal
   approximations. Decimal approximations are to be converted using the
   IEEEReal.TO_NEAREST rounding mode. toDecimal should produce only as many
   digits as are necessary for fromDecimal to convert back to the same number.
   In particular, for any normal or subnormal real value r, we have the bit-wise
   equality:
     fromDecimal (toDecimal r) = r.

   For toDecimal, when the r is not normal or subnormal, then the exp field is
   set to 0 and the digits field is the empty list. In all cases, the sign and
   class field capture the sign and class of r.

  For fromDecimal, if class is ZERO or INF, the resulting real is the
  appropriate signed zero or infinity. If class is NAN, a signed NaN is
  generated. If class is NORMAL or SUBNORMAL, the sign, digits and exp fields
  are used to produce a real number whose value is

    s * 0.d(1)d(2)...d(n) 10(exp)

  where digits = [d(1), d(2), ..., d(n)] and where s is -1 if sign is true and 1
  otherwise. Note that the conversion itself should ignore the class field, so
  that the resulting value might have class NORMAL, SUBNORMAL, ZERO, or INF. For
  example, if digits is empty or a list of all 0's, the result should be a
  signed zero. More generally, very large or small magnitudes are converted to
  infinities or zeros.

  If the argument to fromDecimal does not have a valid format, i.e., if the
  digits field contains integers outside the range [0,9], it returns NONE.

  Implementation note: Algorithms for accurately and efficiently converting
  between binary and decimal real representations are readily available, e.g.,
  see the technical report by Gay[CITE]. *)
(*) TODO

(* Relational -------------------------------------------------- *)

Relational.count [1, 2, 3];
Relational.count [];
Relational.count [false];
Sys.plan ();

Relational.exists [1, 2, 3];
Relational.exists [];
Relational.exists [false];
Sys.plan ();

Relational.notExists [1, 2, 3];
Relational.notExists [];
Relational.notExists [false];
Sys.plan ();

val emp = [
  {empno=7839, ename="KING", mgr=0},
  {empno=7566, ename="JONES", mgr=7839},
  {empno=7698, ename="BLAKE", mgr=7839},
  {empno=7782, ename="CLARK", mgr=7839},
  {empno=7788, ename="SCOTT", mgr=7566},
  {empno=7902, ename="FORD", mgr=7566},
  {empno=7499, ename="ALLEN", mgr=7698},
  {empno=7521, ename="WARD", mgr=7698},
  {empno=7654, ename="MARTIN", mgr=7698},
  {empno=7844, ename="TURNER", mgr=7698},
  {empno=7900, ename="JAMES", mgr=7698},
  {empno=7934, ename="MILLER", mgr=7782},
  {empno=7876, ename="ADAMS", mgr=7788},
  {empno=7369, ename="SMITH", mgr=7902}];
Relational.iterate
  (from e in emp where e.mgr = 0)
  fn (oldList, newList) =>
      (from d in newList,
          e in emp
      where e.mgr = d.empno
      yield e);
Sys.plan ();

Relational.sum [1, 2, 3];
Relational.sum [1.0, 2.5, 3.5];
Sys.plan ();

Relational.max [1, 2, 3];
Relational.max [1.0, 2.5, 3.5];
Relational.max ["a", "bc", "ab"];
Relational.max [false, true];
Sys.plan ();

Relational.min [1, 2, 3];
Relational.min [1.0, 2.5, 3.5];
Relational.min ["a", "bc", "ab"];
Relational.min [false, true];
Sys.plan ();

Relational.only [2];
Relational.only [1, 2, 3];
Relational.only [];
Sys.plan ();

[1, 2] union [3] union [] union [4, 2, 5];
[] union [];
Sys.plan ();

[1, 2] except [2] except [3] except [];
[] except [];
["a"] except ["a"];
["a", "b", "c", "a"] except ["a"];
["a", "b", "c", "a"] except ["c", "b", "c"];
["a", "b"] except ["a", "c"] except ["a"];
Sys.plan ();

[1, 2] intersect [2] intersect [0, 2, 4];
[1, 2] intersect [];
[] intersect [1, 2];
["a", "b", "a"] intersect ["b", "a"];
[(1, 2), (2, 3)] intersect [(2, 4), (1, 2)];
[1, 2, 3] intersect [2, 3, 4] except [1, 3, 5];
[1, 2, 3] except [1, 3, 5] intersect [2, 3, 4];
Sys.plan ();

1 elem [1, 2, 3];
1 elem [2, 3, 4];
1 elem [];
[] elem [[0], [1, 2]];
[] elem [[0], [], [1, 2]];
(1, 2) elem [(0, 1), (1, 2)];
(1, 2) elem [(0, 1), (2, 3)];
Sys.plan ();

1 notElem [1, 2, 3];
1 notElem [2, 3, 4];
1 notElem [];
[] notElem [[0], [1, 2]];
[] notElem [[0], [], [1, 2]];
(1, 2) notElem [(0, 1), (1, 2)];
(1, 2) notElem [(0, 1), (2, 3)];
Sys.plan ();

(* Sys --------------------------------------------------------- *)

(*) val env : unit -> string list
Sys.env;
Sys.env ();

env;
env ();

(*) val plan : unit -> string
Sys.plan;
1 + 2;
Sys.plan ();

(*) val set : string * 'a -> unit
Sys.set;
Sys.set ("hybrid", false);
Sys.plan ();

(*) val show : string -> string option
Sys.show;
Sys.show "hybrid";
Sys.set ("hybrid", true);
Sys.show "hybrid";
Sys.show "optionalInt";
Sys.plan ();

Sys.set ("optionalInt", ~5);
Sys.show "optionalInt";

(*) val unset : string -> unit
Sys.unset;
Sys.unset "hybrid";
Sys.unset "optionalInt";
Sys.plan ();

(* Vector ------------------------------------------------------ *)

(*) Vector.fromList : 'a list -> 'a vector
Vector.fromList;
Vector.fromList [1,2];
Sys.plan ();

(* supported in sml-nj but not morel:
 #[1,2];
 *)

(* sml-nj says:
  stdIn:3.1-3.19 Warning: type vars not generalized because of
     value restriction are instantiated to dummy types (X1,X2,...)
  val it = #[] : ?.X1 vector
*)
Vector.fromList [];
Sys.plan ();

(*) Vector.maxLen: int
Vector.maxLen;
Sys.plan ();

(*) Vector.tabulate : int * (int -> 'a) -> 'a vector
Vector.tabulate;
Vector.tabulate (5, let fun fact n = if n = 0 then 1 else n * fact (n - 1) in fact end);
Sys.plan ();

(*) Vector.length : 'a vector -> int
Vector.length;
Vector.length (Vector.fromList [1,2,3]);
Sys.plan ();

(*) Vector.sub : 'a vector * int -> 'a
Vector.sub;
Vector.sub (Vector.fromList [3,6,9], 2);
Vector.sub (Vector.fromList [3,6,9], ~1);
Vector.sub (Vector.fromList [3,6,9], 3);
Sys.plan ();

(*) Vector.update : 'a vector * int * 'a -> 'a vector
Vector.update;
Vector.update (Vector.fromList ["a","b","c"], 1, "baz");
Vector.update (Vector.fromList ["a","b","c"], ~1, "baz");
Vector.update (Vector.fromList ["a","b","c"], 3, "baz");
Sys.plan ();

(*) Vector.concat : 'a vector list -> 'a vector
Vector.concat;
Vector.concat [Vector.fromList ["a","b"],
  Vector.fromList [], Vector.fromList ["c"]];
Sys.plan ();

(*) Vector.appi : (int * 'a -> unit) -> 'a vector -> unit
Vector.appi;
Vector.appi (fn (i,s) => ignore s) (Vector.fromList ["a", "b", "c"]);
Sys.plan ();

(*) Vector.app  : ('a -> unit) -> 'a vector -> unit
Vector.app;
Vector.app (fn s => ignore s) (Vector.fromList ["a", "b", "c"]);
Sys.plan ();

(*) Vector.mapi : (int * 'a -> 'b) -> 'a vector -> 'b vector
Vector.mapi;
Vector.mapi (fn (i, s) => String.sub (s, i)) (Vector.fromList ["abc", "xyz"]);
Sys.plan ();

(*) Vector.map  : ('a -> 'b) -> 'a vector -> 'b vector
Vector.map;
Vector.map (fn s => String.sub (s, 0)) (Vector.fromList ["abc", "xyz"]);
Sys.plan ();

(*) Vector.foldli : (int * 'a * 'b -> 'b) -> 'b -> 'a vector -> 'b
Vector.foldli;
Vector.foldli (fn (i,j,a) => a + i * j) 0 (Vector.fromList [2,3,4]);
Sys.plan ();

(*) Vector.foldri : (int * 'a * 'b -> 'b) -> 'b -> 'a vector -> 'b
Vector.foldri;
Vector.foldri (fn (i,j,a) => a + i * j) 0 (Vector.fromList [2,3,4]);
Sys.plan ();

(*) Vector.foldl  : ('a * 'b -> 'b) -> 'b -> 'a vector -> 'b
Vector.foldl;
Vector.foldl (fn (j,a) => a + j) 0 (Vector.fromList [2,3,4]);
Sys.plan ();

(*) Vector.foldr  : ('a * 'b -> 'b) -> 'b -> 'a vector -> 'b
Vector.foldr;
Vector.foldr (fn (j,a) => a + j) 0 (Vector.fromList [2,3,4]);
Sys.plan ();

(*) Vector.findi : (int * 'a -> bool) -> 'a vector -> (int * 'a) option
Vector.findi;
Vector.findi (fn (i,j) => j < i) (Vector.fromList [10,8,6,4,2]);
Sys.plan ();

(*) Vector.find  : ('a -> bool) -> 'a vector -> 'a option
Vector.find;
Vector.find (fn j => j mod 2 = 0) (Vector.fromList [3,5,7,8,9]);
Sys.plan ();

(*) Vector.exists : ('a -> bool) -> 'a vector -> bool
Vector.exists;
Vector.exists (fn j => j mod 2 = 0) (Vector.fromList [3,5,7,8,9]);
Sys.plan ();

(*) Vector.all : ('a -> bool) -> 'a vector -> bool
Vector.all;
Vector.all (fn j => j mod 2 = 0) (Vector.fromList [3,5,7,8,9]);
Sys.plan ();

(*) Vector.collate : ('a * 'a -> order) -> 'a vector * 'a vector -> order
Vector.collate;
Vector.collate
  (fn (i,j) => if i < j then LESS else if i = j then EQUAL else GREATER)
  (Vector.fromList [1,3,5], Vector.fromList [1,3,6]);
Sys.plan ();

(*) End builtIn.sml
