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
 *
 * Tests for 'suchThat'.
 *)

(*) If the expression is 'elem set' we can deduce the extent.
from e suchThat (e elem scott.emp)
  where e.deptno = 20
  yield e.ename;

(*) A function that finds its data internally.
let
  fun isEmp e =
    e elem scott.emp
in
  from e suchThat isEmp e
    where e.deptno = 20
    yield e.ename
end;
let
  fun isEmp e =
    e elem scott.emp
in
  from e suchThat isEmp e andalso e.deptno = 20
    yield e.ename
end;

(*) TODO: as previous, but 'fun' followed by 'from' without using 'let'

(*) Similar to 'isEmp' but with a conjunctive condition.
let
  fun isClerk e =
    e elem scott.emp andalso e.job = "CLERK"
in
  from e suchThat isClerk e andalso e.deptno = 20
    yield e.ename
end;

(*) A disjunctive condition prevents the extent.
(*) TODO: throw an error, rather than returning an empty list
let
  fun isEmp50 e =
    e elem scott.emp orelse e.deptno = 50
in
  from e suchThat isEmp50 e
    yield e.ename
end;

(*) A function with external extent.
fun hasJob (e, job) =
  e.job = job;
(*) Valid, because the argument has an extent.
let
  fun hasJob (e, job) =
    e.job = job
in
  from e in scott.emp,
    j suchThat hasJob (e, j)
    yield j
end;
(*) Invalid, because the argument has no extent.
from e suchThat hasJob (e, "CLERK");

(*) A string function with external extent.
(*) Given s2, we could generate finite s1.
fun isPrefix (s1, s2) =
  String.isPrefix s1 s2;
(*) This is invalid, but it could be valid, and would return
(*) ["", "a", "ab", "abc", "abcd"];
from s suchThat isPrefix (s, "abcd");

(*) An integer function with external extent.
(*) Given j, k we could generate finite i.
fun isBetween (i, j, k) =
  i <= j andalso j <= k;

(* ------------------------------------------------------ *)
(*) Convenience function that converts a predicate to a relation
fun enumerate predicate =
  from r suchThat predicate r;
(*) TODO should return non-empty list
enumerate isEmp;

(* ------------------------------------------------------ *)
(* The following example from Souffle,
 * https://souffle-lang.github.io/simple.
 *
 * Say we have a Datalog file example.dl, whose contents are as shown:
 *
 *   .decl edge(x:number, y:number)
 *   .input edge
 *
 *   .decl path(x:number, y:number)
 *   .output path
 *
 *   path(x, y) :- edge(x, y).
 *   path(x, y) :- path(x, z), edge(z, y).
 *
 * We see that edge is a .input relation, and so will be read from disk. Also,
 * path is a .output relation, and so will be written to disk.
 *
 * The last two lines say that 1) "there is a path from x to y if there is an
 * edge from x to y", and 2) "there is a path from x to y if there is a path
 * from x to some z, and there is an edge from that z to y".
 *
 * So if the input edge relation is pairs of vertices in a graph, by these two
 * rules the output path relation will give us all pairs of vertices x and y for
 * which a path exists in that graph from x to y.
 *
 * For instance, if the contents of the tab-separated input file edge.facts is
 *
 *   1  2
 *   2  3
 *
 * The contents of the output file path.csv, after we evaluate this program,
 * will be
 *
 *   1  2
 *   2  3
 *   1  3
 *)
val edges = [
 {x = 1, y = 2},
 {x = 2, y = 3}];
fun edge (x, y) = {x, y} elem edges;
fun path (x, y) =
  edge (x, y)
  orelse exists (
    from z suchThat path (x, z) andalso edge (z, y));
(*) TODO should return [(1,2),(2,3),(1,3)]
from p suchThat path p;

(* ------------------------------------------------------ *)
(* Joe's bar.
 * See http://infolab.stanford.edu/~ullman/fcdb/aut07/slides/dlog.pdf.
 *)

val barPatrons = [
  {bar = "squirrel", patron = "shaggy"},
  {bar = "cask", patron = "fred"},
  {bar = "cask", patron = "scooby"},
  {bar = "cask", patron = "shaggy"},
  {bar = "cask", patron = "velma"}];

val barBeers = [
  {bar =  "squirrel", beer =  "ipa", price =  2},
  {bar =  "squirrel", beer =  "pale", price =  2},
  {bar =  "squirrel", beer =  "amber", price =  3},
  {bar =  "cask", beer =  "stout", price =  4},
  {bar =  "cask", beer =  "ipa", price =  5}];

val patronBeers = [
  {patron =  "shaggy", beer = "amber"},
  {patron =  "fred", beer = "amber"},
  {patron =  "velma", beer = "stout"}];

fun frequents (patron, bar) =
  {patron, bar} elem barPatrons;
fun likes (patron, beer) =
  {patron, beer} elem patronBeers;
fun sells (bar, beer, price) =
  {bar, beer, price} elem barBeers;

(* Patron p is happy if there exists a bar, a beer, and a price such that p
 * frequents the bar, likes the beer, and the bar sells the beer at price p.
 *
 * Datalog:
 *    Happy(p) <- Frequents(p, bar) AND Likes(p, beer) AND Sells(bar, beer)
 *)
fun happy patron =
  exists (
    from (bar, beer, price) suchThat frequents (patron, bar)
      andalso likes (patron, beer)
      andalso sells (bar, beer, price));

(* Find happy patrons. Shaggy is happy because the Squirrel and Cask sell
   Amber; Velma is happy because Cask sells Stout. Fred and Scooby are not
   happy. *)
(*) TODO should return ["shaggy", "velma"]
from p suchThat happy p;

(* A beer is considered cheap if there are at least two bars that sell it for
 * under $3.

 * Datalog:
 *   Cheap(beer) <- Sells(bar1, beer, p1) AND Sells(bar2, beer, p2)
 *     AND p1 < 3 AND p2 < 3 AND bar1 <> bar2
 *)
fun cheap beer =
  exists (
    from (bar1, price1, bar2, price2)
      suchThat sells (bar1, beer, price1)
        andalso sells (bar2, beer, price2)
        andalso price1 < 3
        andalso price2 < 3
        andalso bar1 <> bar2);

(*) Pale is cheap
(*) TODO should return ["pale"]
from b suchThat cheap b;

(* A rule is safe if:
 * 1. Each distinguished variable,
 * 2. Each variable in an arithmetic subgoal, and
 * 3. Each variable in a negated subgoal,
 *
 *  also appears in a non-negated, relational sub-goal.
 * Each of the following is unsafe and not allowed:
 *
 * 1. S(x) <- R(y)
 * 2. S(x) <- R(y) AND NOT R(x)
 * 3. S(x) <- R(y) AND x < y
 *
 * In each case, an infinite number of values of x can satisfy the rule, even
 * if R is a finite relation.
 *
 * If rules are safe, we can use the following evaluation approach:
 * For each subgoal, consider all tuples that make the subgoal true.
 * If a selection of tuples define a single value for each variable,
 * then add the head to the result.
 * Leads to finite search for P(x) <- Q(x), but P(x) <- Q(y) is problematic.
 *)
fun isR y = true;
fun isS1 x = exists (from y suchThat isR y);
(*) TODO: should say that isS1 is unsafe
from x suchThat isS1 x;
fun isS2 x = exists (from y suchThat isR y andalso not (isR x));
(*) TODO: should say that isS2 is unsafe
from x suchThat isS2 x;
fun isS3 x = exists (from y suchThat isR y andalso x < y);
(*) TODO: should say that isS3 is unsafe
from x suchThat isS3 x;

(* Example Datalog Program. Using EDB Sells (bar, beer, price) and
 * Likes (patron, beer), find the patrons who like beers Joe doesn't sell.
 *
 * Datalog:
 *   JoeSells(b) <- Sells('Joe''s Bar', b, p)
 *   Answer(p) <- Likes(p, b)
 *     AND NOT JoeSells(b)
 *)
fun caskSells b =
  exists (from (beer, price) suchThat sells ("cask", beer, price));
from p suchThat exists (
  from b suchThat likes (p, b) andalso not (caskSells b));

(* Cousin
 *
 * Datalog:
 *   Sib(x,y) <- Par(x,p) AND Par(y,p) AND x<>y
 *   Cousin(x,y) <- Sib(x,y)
 *   Cousin(x,y) <- Par(x,xp) AND Par(y,yp) AND Cousin(xp,yp)
 *)
fun par (x, p) =
  (p, x) elem [
    ("a", "b"),
    ("a", "c"),
    ("d", "c"),
    ("d", "e"),
    ("b", "f"),
    ("c", "g"),
    ("e", "i"),
    ("f", "j"),
    ("f", "k"),
    ("g", "k"),
    ("h", "i")];
fun sib (x, y) = exists (
  from p suchThat par (x, p) andalso par (y, p) andalso x <> y);
fun cousin (x, y) = sib (x, y)
  orelse exists (
    from (xp, yp) suchThat par (x, xp)
      andalso par (y, yp)
      andalso cousin (xp, yp));

(*
 Round 1: (b, c), (c, e), (g, h), (j, k)
 Round 2: same
 Round 3: add (f, g), (f, h), (g, i), (i, k)
 Round 4: add (i, j), (k, k)
 *)
(*) TODO: return non-empty list
enumerate sib;
enumerate cousin;

(* Nonmonotone relation.
 * 'cousin2' is as 'cousin', but 'orelse' has become 'and not'.
 * The graph is not stratified: there is a path with an infinite number
 * of 'not' as we traverse the cycle cousin - s2 - cousin,
 * where s2 is the expression 'notExists (...)'. *)
fun cousin2 (x, y) = sib (x, y)
  andalso notExists (
    from (xp, yp) suchThat par (x, xp)
      andalso par (y, yp)
      andalso cousin2 (xp, yp));
(*) TODO: maybe give error: non-stratified
enumerate cousin2;

(*) End suchThat.sml
