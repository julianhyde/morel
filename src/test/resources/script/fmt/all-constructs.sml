let
  (* Datatype: polymorphic, multiple constructors *)
  datatype 'a tree = LEAF of 'a | NODE of 'a tree * 'a * 'a tree;

  (* Type alias: record type *)
  type point = {x: int, y: int};

  (* Val declarations *)
  val origin : point = {x = 0, y = 0};
  val pi = 3.14;
  val greeting = "hello";
  val unitVal = ();
  val yes = true;
  val no = false;

  (* Val rec: recursive binding *)
  val rec fact = fn n => if n = 0 then 1 else n * fact (n - 1);

  (* Fun: single clause *)
  fun double x = x + x;

  (* Fun: multiple clauses *)
  fun fib 0 = 0
    | fib 1 = 1
    | fib n = fib (n - 1) + fib (n - 2);

  (* Fun: mutually recursive with 'and' *)
  fun isEven 0 = true
    | isEven n = isOdd (n - 1)
  and isOdd 0 = false
    | isOdd n = isEven (n - 1);

  (* Literals: int, real, string, char, bool, unit *)
  val literals = (42, ~1, 3.14, "hello", #"A", true, false, ());

  (* Prefix operator *)
  val neg = ~42;

  (* Arithmetic operators *)
  val arith = (1 + 2, 3 - 4, 5 * 6, 7.0 / 2.0, 10 div 3, 10 mod 3);

  (* String concatenation *)
  val joined = "hello" ^ " " ^ "world";

  (* List operators: cons and append *)
  val consed = 1 :: 2 :: 3 :: [];
  val appended = [1, 2] @ [3, 4];

  (* Comparison operators *)
  val cmp = (1 = 1, 2 <> 3, 1 < 2, 2 > 1, 1 <= 1, 2 >= 1);

  (* Logical operators *)
  val logic = (true andalso false, true orelse false);

  (* Function composition *)
  val doubleThenFib = fib o double;

  (* Collections: tuple, list, record *)
  val myTuple = (1, "two", 3.0);
  val myList = [10, 20, 30, 40, 50];
  val myRecord = {name = "Alice", age = 30, active = true};

  (* Patterns: wildcard, tuple, list, record, cons, as, annotated, constructor *)
  fun patternExamples x =
    case x of
      [] => "empty"
    | [_] => "singleton"
    | (first :: _) => "multi";

  val (a, b, _) = (1, 2, 3);
  val {name = theName, ...} = {name = "Bob", age = 25};

  (* Type annotations *)
  val typed : int -> int = fn x => x + 1;

  (* If/then/else: nested *)
  fun classify n =
    if n < 0 then "negative"
    else if n = 0 then "zero"
    else "positive";

  (* Let/in/end: nested *)
  val nested =
    let
      val x = 10;
      val y = let val z = x + 1 in z * 2 end
    in
      x + y
    end;

  (* Fn with multiple match arms *)
  val describe = fn 0 => "zero" | 1 => "one" | _ => "other";

  (* Case with multiple arms *)
  fun treeSize t =
    case t of
      LEAF _ => 1
    | NODE (left, _, right) => treeSize left + 1 + treeSize right;

  (* Function application: simple and nested *)
  val applied = double (double 3);

  (* Unnecessary parentheses: formatter strips them *)
  val stripped = (1 + 2);
  val stripped2 = ((42));

  (* Deeply nested: case inside fn *)
  val deepFn =
    fn xs =>
      case xs of
        [] => 0
      | (h :: t) => h + 1;

  (* From query: scan, where, yield *)
  val emps = [{name = "Alice", deptno = 10}, {name = "Bob", deptno = 20}, {name = "Carol", deptno = 10}];
  val q1 = from e in emps where e.deptno = 10 yield e.name;

  (* From query: order *)
  val q2 = from e in emps order e.name;

  (* From query: group and compute *)
  val q3 = from e in emps group e.deptno compute count = count;

  (* From query: multiple scans *)
  val q4 = from x in [1, 2, 3], y in [10, 20] yield x + y;

  (* From query: distinct, skip, take *)
  val q5 = from i in [1, 2, 2, 3, 3, 3] distinct;
  val q6 = from i in [1, 2, 3, 4, 5] skip 2 take 2
in
  (* Final expression: tuple of results *)
  (fib 10, isEven 4, isOdd 3, classify ~5, treeSize (NODE (LEAF 1, 2, LEAF 3)), q1, q5)
end
