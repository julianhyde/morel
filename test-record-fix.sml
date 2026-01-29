(* Test for Op.RECORD fix in FunctionAnalyzer *)
(* This tests that record-based predicates like "fun edge(x,y) = {x,y} elem edges" *)
(* can be properly analyzed by FunctionAnalyzer.matchesFormalParameter *)

val edges = [{x=1,y=2},{x=2,y=3}];
fun edge (x, y) = {x, y} elem edges;
fun path (x, y) = edge (x, y) orelse (exists z where path (x, z) andalso edge (z, y));
from p where path p;
