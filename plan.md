# Plan: Implement `Time` structure (issue #351) — COMPLETE

## Status

**Phase 1 (Time structure): COMPLETE** — all 329 tests pass.
Commits: d2739d8e (implementation), de6719de (CLAUDE.md).

**Phase 2 (Date structure): COMPLETE** — all 329 tests pass.
Commits: acf41114 (implementation + tests).

## Overview

Implement the `Time` structure from the SML Standard Basis Library, following
the same pattern used for `Real`, `Math`, `Char`, `List`, etc.

Reference: https://smlfamily.github.io/Basis/time.html

## Interface to implement

```sml
eqtype time
exception Time
val zeroTime : time
val fromReal : real -> time
val toReal : time -> real
val toSeconds      : time -> int
val toMilliseconds : time -> int
val toMicroseconds : time -> int
val toNanoseconds  : time -> int
val fromSeconds      : int -> time
val fromMilliseconds : int -> time
val fromMicroseconds : int -> time
val fromNanoseconds  : int -> time
val + : time * time -> time
val - : time * time -> time
val compare : time * time -> order
val <  : time * time -> bool
val <= : time * time -> bool
val >  : time * time -> bool
val >= : time * time -> bool
val now : unit -> time
val fmt      : int -> time -> string
val toString : time -> string
val fromString : string -> time option
```

Note: `scan` is omitted since Morel does not implement `StringCvt.reader`.

Note: In Morel, `LargeReal.real` = `real` and `LargeInt.int` = `int`.

## Java representation of `time`

Represent `time` values as Java `Long` (nanoseconds since Unix epoch,
1970-01-01T00:00:00Z). This gives exact representation up to nanosecond
precision, supports negative values (times before the epoch / negative
intervals), and maps cleanly to all `to*`/`from*` conversion functions.

- `zeroTime` = `0L`
- `now()` = `Instant.now().getEpochSecond() * 1_000_000_000L + Instant.now().getNano()`
- `toNanoseconds(t)` = `t` (identity)
- `toMicroseconds(t)` = `t / 1_000L`  (truncated toward zero for negative)
- `toMilliseconds(t)` = `t / 1_000_000L`
- `toSeconds(t)` = `t / 1_000_000_000L`
- `fromNanoseconds(n)` = `n`
- `fromMicroseconds(n)` = `n * 1_000L`
- `fromMilliseconds(n)` = `n * 1_000_000L`
- `fromSeconds(n)` = `n * 1_000_000_000L`
- `toReal(t)` = `t / 1e9` (seconds as floating-point)
- `fromReal(r)` = `(long)(r * 1e9)` (raises `Time` if NaN or overflow)
- `+(t1, t2)` = `t1 + t2` (raises `Time` on overflow)
- `-(t1, t2)` = `t1 - t2` (raises `Time` on overflow)
- `fmt n t` = format as decimal seconds with `n` fractional digits, e.g.
  `fmt 3 (fromReal 1.5)` = `"1.500"`; negative times use `"~"` prefix
- `toString t` = `fmt 3 t`
- `fromString s` = parse decimal seconds string, return `SOME t` or `NONE`

## Files to change

### 1. `src/main/java/net/hydromatic/morel/compile/BuiltIn.java`

**Step 1a: Add `TIME` to the `Eqtype` enum** (sorted alphabetically, so before `VECTOR`):

```java
TIME("time", 0),
```

**Step 1b: Add `TIME_*` enum constants** (in the `// lint: sort` section).
Each function follows the pattern used by similar structures.

```java
/** Constant "Time.zeroTime", of type "time". */
TIME_ZERO_TIME("Time", "zeroTime", ts -> ts.lookup(Eqtype.TIME)),

/** Function "Time.fromReal", of type "real -> time". */
TIME_FROM_REAL("Time", "fromReal", ts -> ts.fnType(REAL, ts.lookup(Eqtype.TIME))),

/** Function "Time.toReal", of type "time -> real". */
TIME_TO_REAL("Time", "toReal", true, ts -> ts.fnType(ts.lookup(Eqtype.TIME), REAL)),

/** Function "Time.toSeconds", of type "time -> int". */
TIME_TO_SECONDS("Time", "toSeconds", true, ts -> ts.fnType(ts.lookup(Eqtype.TIME), INT)),

/** Function "Time.toMilliseconds", of type "time -> int". */
TIME_TO_MILLISECONDS("Time", "toMilliseconds", true, ts -> ts.fnType(ts.lookup(Eqtype.TIME), INT)),

/** Function "Time.toMicroseconds", of type "time -> int". */
TIME_TO_MICROSECONDS("Time", "toMicroseconds", true, ts -> ts.fnType(ts.lookup(Eqtype.TIME), INT)),

/** Function "Time.toNanoseconds", of type "time -> int". */
TIME_TO_NANOSECONDS("Time", "toNanoseconds", true, ts -> ts.fnType(ts.lookup(Eqtype.TIME), INT)),

/** Function "Time.fromSeconds", of type "int -> time". */
TIME_FROM_SECONDS("Time", "fromSeconds", ts -> ts.fnType(INT, ts.lookup(Eqtype.TIME))),

/** Function "Time.fromMilliseconds", of type "int -> time". */
TIME_FROM_MILLISECONDS("Time", "fromMilliseconds", ts -> ts.fnType(INT, ts.lookup(Eqtype.TIME))),

/** Function "Time.fromMicroseconds", of type "int -> time". */
TIME_FROM_MICROSECONDS("Time", "fromMicroseconds", ts -> ts.fnType(INT, ts.lookup(Eqtype.TIME))),

/** Function "Time.fromNanoseconds", of type "int -> time". */
TIME_FROM_NANOSECONDS("Time", "fromNanoseconds", ts -> ts.fnType(INT, ts.lookup(Eqtype.TIME))),

/** Function "Time.+", of type "time * time -> time". */
TIME_ADD("Time", "+", true, ts -> ts.fnType(ts.tupleType(ts.lookup(Eqtype.TIME), ts.lookup(Eqtype.TIME)), ts.lookup(Eqtype.TIME))),

/** Function "Time.-", of type "time * time -> time". */
TIME_SUBTRACT("Time", "-", true, ts -> ts.fnType(ts.tupleType(ts.lookup(Eqtype.TIME), ts.lookup(Eqtype.TIME)), ts.lookup(Eqtype.TIME))),

/** Function "Time.compare", of type "time * time -> order". */
TIME_COMPARE("Time", "compare", true, ts -> ts.fnType(ts.tupleType(ts.lookup(Eqtype.TIME), ts.lookup(Eqtype.TIME)), ts.order())),

/** Function "Time.<", of type "time * time -> bool". */
TIME_LT("Time", "<", true, ts -> ts.fnType(ts.tupleType(ts.lookup(Eqtype.TIME), ts.lookup(Eqtype.TIME)), BOOL)),

/** Function "Time.<=", of type "time * time -> bool". */
TIME_LE("Time", "<=", true, ts -> ts.fnType(ts.tupleType(ts.lookup(Eqtype.TIME), ts.lookup(Eqtype.TIME)), BOOL)),

/** Function "Time.>", of type "time * time -> bool". */
TIME_GT("Time", ">", true, ts -> ts.fnType(ts.tupleType(ts.lookup(Eqtype.TIME), ts.lookup(Eqtype.TIME)), BOOL)),

/** Function "Time.>=", of type "time * time -> bool". */
TIME_GE("Time", ">=", true, ts -> ts.fnType(ts.tupleType(ts.lookup(Eqtype.TIME), ts.lookup(Eqtype.TIME)), BOOL)),

/** Function "Time.now", of type "unit -> time". */
TIME_NOW("Time", "now", ts -> ts.fnType(UNIT, ts.lookup(Eqtype.TIME))),

/** Function "Time.fmt", of type "int -> time -> string". */
TIME_FMT("Time", "fmt", ts -> ts.fnType(INT, ts.fnType(ts.lookup(Eqtype.TIME), STRING))),

/** Function "Time.toString", of type "time -> string". */
TIME_TO_STRING("Time", "toString", true, ts -> ts.fnType(ts.lookup(Eqtype.TIME), STRING)),

/** Function "Time.fromString", of type "string -> time option". */
TIME_FROM_STRING("Time", "fromString", ts -> ts.fnType(STRING, ts.option(ts.lookup(Eqtype.TIME)))),
```

Note: The `true` flag on some entries (e.g., `TIME_TO_REAL`) marks them as
`method = true` — eligible for postfix call syntax (`t.toReal`).

### 2. `src/main/java/net/hydromatic/morel/eval/Codes.java`

**Step 2a: Add `TIME` exception** to `BuiltInExn` enum (alphabetically before
`UNEQUAL_LENGTHS`):

```java
TIME("Time", "Time", null),
```

**Step 2b: Add Applicable implementations** for each `TIME_*` function:

```java
/** @see BuiltIn#TIME_ZERO_TIME */
private static final Long TIME_ZERO_TIME = 0L;

/** @see BuiltIn#TIME_FROM_REAL */
private static final Applicable1 TIME_FROM_REAL = new TimeFromReal(Pos.ZERO);

private static class TimeFromReal extends BasePositionedApplicable1<Long, Float> {
  TimeFromReal(Pos pos) { super(BuiltIn.TIME_FROM_REAL, pos); }
  @Override public TimeFromReal withPos(Pos pos) { return new TimeFromReal(pos); }
  @Override public Long apply(Float r) {
    if (Float.isNaN(r) || Float.isInfinite(r)) {
      throw new MorelRuntimeException(BuiltInExn.TIME, pos);
    }
    return (long) ((double) r * 1_000_000_000L);
  }
}

/** @see BuiltIn#TIME_TO_REAL */
private static final Applicable1 TIME_TO_REAL =
    new BaseApplicable1<Float, Long>(BuiltIn.TIME_TO_REAL) {
      @Override public Float apply(Long t) { return (float) (t / 1e9); }
    };

/** @see BuiltIn#TIME_TO_SECONDS */
private static final Applicable1 TIME_TO_SECONDS =
    new BaseApplicable1<Integer, Long>(BuiltIn.TIME_TO_SECONDS) {
      @Override public Integer apply(Long t) {
        return Math.toIntExact(t / 1_000_000_000L);
      }
    };

// ... similarly for TIME_TO_MILLISECONDS, TIME_TO_MICROSECONDS, TIME_TO_NANOSECONDS

/** @see BuiltIn#TIME_FROM_SECONDS */
private static final Applicable1 TIME_FROM_SECONDS =
    new BaseApplicable1<Long, Integer>(BuiltIn.TIME_FROM_SECONDS) {
      @Override public Long apply(Integer n) { return (long) n * 1_000_000_000L; }
    };

// ... similarly for TIME_FROM_MILLISECONDS, TIME_FROM_MICROSECONDS, TIME_FROM_NANOSECONDS

/** @see BuiltIn#TIME_ADD */
private static final Applicable2 TIME_ADD =
    new BaseApplicable2<Long, Long, Long>(BuiltIn.TIME_ADD) {
      @Override public Long apply(Long t1, Long t2) { return t1 + t2; }
    };

/** @see BuiltIn#TIME_SUBTRACT */
private static final Applicable2 TIME_SUBTRACT =
    new BaseApplicable2<Long, Long, Long>(BuiltIn.TIME_SUBTRACT) {
      @Override public Long apply(Long t1, Long t2) { return t1 - t2; }
    };

/** @see BuiltIn#TIME_COMPARE */
private static final Applicable2 TIME_COMPARE =
    new BaseApplicable2<List, Long, Long>(BuiltIn.TIME_COMPARE) {
      @Override public List apply(Long t1, Long t2) {
        return order(Long.compare(t1, t2));
      }
    };

// TIME_LT, TIME_LE, TIME_GT, TIME_GE: boolean comparisons of Long values

/** @see BuiltIn#TIME_NOW */
private static final Applicable TIME_NOW =
    new BaseApplicable<Long>(BuiltIn.TIME_NOW) {
      @Override public Long apply(EvalEnv env, Object arg) {
        Instant now = Instant.now();
        return now.getEpochSecond() * 1_000_000_000L + now.getNano();
      }
    };

/** @see BuiltIn#TIME_FMT */
private static final Applicable1 TIME_FMT =
    new BaseApplicable1<Applicable, Integer>(BuiltIn.TIME_FMT) {
      @Override public Applicable apply(Integer n) {
        return new BaseApplicable1<String, Long>(BuiltIn.TIME_FMT) {
          @Override public String apply(Long t) {
            return timeFmt(n, t);
          }
        };
      }
    };

/** @see BuiltIn#TIME_TO_STRING */
private static final Applicable1 TIME_TO_STRING =
    new BaseApplicable1<String, Long>(BuiltIn.TIME_TO_STRING) {
      @Override public String apply(Long t) { return timeFmt(3, t); }
    };

/** @see BuiltIn#TIME_FROM_STRING */
private static final Applicable1 TIME_FROM_STRING =
    new BaseApplicable1<List, String>(BuiltIn.TIME_FROM_STRING) {
      @Override public List apply(String s) {
        try {
          double seconds = Double.parseDouble(s);
          return optionSome((long)(seconds * 1_000_000_000L));
        } catch (NumberFormatException e) {
          return OPTION_NONE;
        }
      }
    };

/** Helper: format time as decimal seconds with {@code n} fractional digits. */
private static String timeFmt(int n, long t) {
  // negative times use "~" prefix (SML convention)
  boolean negative = t < 0;
  long abs = negative ? -t : t;
  long seconds = abs / 1_000_000_000L;
  long nanos = abs % 1_000_000_000L;
  String prefix = negative ? "~" : "";
  if (n <= 0) {
    return prefix + seconds;
  }
  // format nanos as n-digit fraction
  String frac = String.format("%09d", nanos);
  frac = frac.substring(0, Math.min(n, 9));
  // pad with zeros if n > 9
  while (frac.length() < n) frac += "0";
  return prefix + seconds + "." + frac;
}
```

**Step 2c: Register all `TIME_*` in the `CODES` static map.**

### 3. `src/main/resources/functions.toml`

Add a new `[[structures]]` block for `Time` (alphabetically, after `Sys`).
Add one `[[exceptions]]` entry for `Time`.
Add one `[[types]]` entry for `eqtype time`.
Add `[[functions]]` entries for each of the 22 functions (ordinals 239–260,
continuing from the current maximum of 238).

Example excerpts:

```toml
[[structures]]
name = "Time"
description = "Time values and operations."
overview = """
The `Time` structure provides an abstract type for representing both absolute
times and time intervals, with functions for conversion, arithmetic, comparison,
formatting, and parsing. Time values are measured in nanoseconds.
"""

[[exceptions]]
structure = "Time"
name = "Time"
description = """
is raised when a conversion produces a value that cannot be represented as a
`time` value, or when an illegal operation is attempted.
"""

[[types]]
structure = "Time"
name = "time"
type = "eqtype time"
description = """
is an equality type representing both absolute times (relative to the Unix
epoch, 1970-01-01T00:00:00Z) and time durations. Both absolute times and
intervals are represented identically; the interpretation is contextual.
Negative values represent times before the epoch or negative intervals.
"""

[[functions]]
ordinal = 239
structure = "Time"
name = "zeroTime"
specified = "basis"
type = "time"
prototype = "zeroTime"
description = """
denotes an empty interval and serves as the reference point.
It is equivalent to `fromReal(0.0)`.
"""

[[functions]]
ordinal = 240
structure = "Time"
name = "fromReal"
specified = "basis"
type = "real → time"
prototype = "fromReal r"
description = """
converts `r` (measured in seconds) to a `time` value.
Raises `Time` if `r` is NaN, infinite, or otherwise not representable.
"""

... (one entry per function through ordinal 260)
```

### 4. `src/test/resources/script/built-in.smli`

Add a `Time` section (find where to insert alphabetically, after `Sys`
or at the end of built-in structure tests). Include:

```sml
Time;
> val it =
>   {-=fn,<=fn,<<=fn,==fn,>=fn,>>=fn,compare=fn,fmt=fn,fromMilliseconds=fn,
>    fromMicroseconds=fn,fromNanoseconds=fn,fromReal=fn,fromSeconds=fn,
>    fromString=fn,now=fn,toMicroseconds=fn,toMilliseconds=fn,toNanoseconds=fn,
>    toReal=fn,toSeconds=fn,toString=fn,zeroTime=0}
>   : {-:time * time -> time, <:time * time -> bool, ...}

Time.zeroTime;
> val it = 0 : time

Time.fromSeconds 1;
> val it = 1000000000 : time

Time.toSeconds (Time.fromSeconds 42);
> val it = 42 : int

Time.fromMilliseconds 1500;
> val it = 1500000000 : time

Time.toMilliseconds (Time.fromSeconds 2);
> val it = 2000 : int

Time.fromMicroseconds 1000000;
> val it = 1000000000 : time

Time.toMicroseconds (Time.fromSeconds 1);
> val it = 1000000 : int

Time.fromNanoseconds 1000000000;
> val it = 1000000000 : time

Time.toNanoseconds (Time.fromSeconds 1);
> val it = 1000000000 : int

Time.fromReal 1.5;
> val it = 1500000000 : time

Time.toReal (Time.fromReal 2.5);
> val it = 2.5 : real

(Time.fromReal 2.5).toReal ();
> val it = 2.5 : real

(Time.fromSeconds 42).toSeconds ();
> val it = 42 : int

(Time.fromSeconds 2).toMilliseconds ();
> val it = 2000 : int

(Time.fromSeconds 1).toMicroseconds ();
> val it = 1000000 : int

(Time.fromSeconds 1).toNanoseconds ();
> val it = 1000000000 : int

Time.toString Time.zeroTime;
> val it = "0.000" : string

Time.zeroTime.toString ();
> val it = "0.000" : string

Time.fmt 0 Time.zeroTime;
> val it = "0" : string

Time.fmt 6 (Time.fromReal 1.5);
> val it = "1.500000" : string

Time.fromString "1.5";
> val it = SOME 1500000000 : time option

Time.fromString "bad";
> val it = NONE : time option

Time.+ (Time.fromSeconds 1, Time.fromSeconds 2);
> val it = 3000000000 : time

(Time.fromSeconds 1).+ (Time.fromSeconds 2);
> val it = 3000000000 : time

Time.- (Time.fromSeconds 3, Time.fromSeconds 1);
> val it = 2000000000 : time

(Time.fromSeconds 3).- (Time.fromSeconds 1);
> val it = 2000000000 : time

Time.compare (Time.fromSeconds 1, Time.fromSeconds 2);
> val it = LESS : order

(Time.fromSeconds 1).compare (Time.fromSeconds 2);
> val it = LESS : order

Time.compare (Time.fromSeconds 2, Time.fromSeconds 1);
> val it = GREATER : order

Time.compare (Time.fromSeconds 1, Time.fromSeconds 1);
> val it = EQUAL : order

Time.< (Time.fromSeconds 1, Time.fromSeconds 2);
> val it = true : bool

(Time.fromSeconds 1).< (Time.fromSeconds 2);
> val it = true : bool

Time.<= (Time.fromSeconds 1, Time.fromSeconds 1);
> val it = true : bool

(Time.fromSeconds 1).<= (Time.fromSeconds 1);
> val it = true : bool

Time.> (Time.fromSeconds 2, Time.fromSeconds 1);
> val it = true : bool

(Time.fromSeconds 2).> (Time.fromSeconds 1);
> val it = true : bool

Time.>= (Time.fromSeconds 2, Time.fromSeconds 1);
> val it = true : bool

(Time.fromSeconds 2).>= (Time.fromSeconds 1);
> val it = true : bool

(* Now returns the current time; test just that it has a plausible value *)
Time.toReal (Time.now ()) > 1.0e9;
> val it = true : bool
```

### 5. `docs/lib/time.md` (new file)

Create a new documentation page following the template of `docs/lib/real.md`
or `docs/lib/math.md`. Use the `[//]: # (start:lib/time)` ... `[//]: # (end:lib/time)`
markers so that `LintTest.testGeneratedSections()` can regenerate the content.

Template:
```markdown
<!--
{% comment %}
... license header ...
{% endcomment %}
-->

# Time structure

[Up to index](index.md)

[//]: # (start:lib/time)
[//]: # (end:lib/time)
```

The content between markers is auto-generated from `functions.toml` by
`Generation.generateStructureDoc("Time", pw)`. After writing the
`functions.toml` entries, run:

```bash
./mvnw test -Dtest=LintTest#testGeneratedSections
```

This test will fail and show the expected content. Copy that into `time.md`
between the markers.

### 6. `docs/lib/index.md`

Add a link to `time.md` in the index (the table of structures). The index
has a `[//]: # (start:lib/index)` ... `[//]: # (end:lib/index)` generated
section that is regenerated by `LintTest`. After adding `Time` to
`functions.toml`, the generated index will automatically include `Time`.

### 7. `CLAUDE.md`

Add a new section under "Common Development Patterns" describing how to
implement a Standard Basis Library structure. Insert it after the existing
"Adding a Built-in Function" section:

```markdown
### Adding a Standard Basis Library Structure

When implementing a structure from the
[SML Standard Basis Library](https://smlfamily.github.io/Basis/):

1. **`BuiltIn.java`** — If the structure introduces a new abstract type
   (e.g., `eqtype time`), add it to the `Eqtype` enum (zero type parameters)
   or `Datatype` enum (with type parameters). Then add one enum constant per
   function/value in the structure, named `STRUCTNAME_FUNCTIONNAME` (e.g.,
   `TIME_FROM_REAL`). Mark a constant as a method (`true` flag) if its first
   argument — or the first element of its tuple argument — is the structure's
   own type, following the same pattern as `REAL_COMPARE` and
   `REAL_COPY_SIGN`.

2. **`Codes.java`** — Add an `Applicable` implementation for each function
   and register it in the `CODES` static map. If the structure has an
   exception (e.g., `exception Time`), add it to `BuiltInExn`.

3. **`functions.toml`** — Add `[[structures]]`, `[[exceptions]]` (if any),
   `[[types]]` (if any), and `[[functions]]` entries. Copy descriptions from
   https://smlfamily.github.io/Basis/ and adapt them. Assign new ordinals
   continuing from the current maximum. Mark functions with `method = true`
   where appropriate. Use `specified = "basis"` for standard functions.

4. **`src/test/resources/script/built-in.smli`** — Add tests for all
   functions. Insert the section alphabetically by structure name. Include a
   test that prints the whole structure (e.g., `Time;`) so the record type is
   checked.

5. **`docs/lib/{name}.md`** (new file) — Create a doc page using the
   license header from an existing page, with `[//]: # (start:lib/{name})`
   and `[//]: # (end:lib/{name})` markers. The content between the markers
   is auto-generated from `functions.toml`.

6. **Regenerate docs** — Run `./mvnw test -Dtest=LintTest` to validate and
   regenerate all generated sections. The test will fail with a diff showing
   what content to insert between the markers in the `.md` files.

Notes:
- `scan` functions (those taking a `StringCvt.reader`) are omitted since
  Morel does not implement `StringCvt`.
- In Morel, `LargeReal.real` = `real` and `LargeInt.int` = `int`.
- The `docs/lib/index.md` index is auto-generated; no manual edits needed
  once the `[[structures]]` entry is in `functions.toml`.
```

## Build and test sequence

```bash
# 1. Compile after code changes
./mvnw compile

# 2. Run the LintTest to see what generated doc content looks like
#    (will fail if time.md doesn't exist or is missing generated content)
./mvnw test -Dtest=LintTest

# 3. Regenerate docs by copying the diff output into the .md files
#    (or let LintTest guide you on what to add)

# 4. Run MainTest to validate the .smli tests pass
./mvnw test -Dtest=MainTest

# 5. Full build
./mvnw verify
```

## Scope decisions

- **`scan`**: Omitted. Requires `StringCvt.reader` which Morel does not
  implement.
- **Overflow detection in `+` and `-`**: Use Java's arithmetic; if overflow
  is a concern, use `Math.addExact`/`Math.subtractExact` and catch
  `ArithmeticException` to raise `BuiltInExn.TIME`.
- **`fmt` with negative `n`**: Treat as `fmt 0` (no fractional digits), or
  raise `Time` exception. Follow SML spec (raises `Size` if n < 0).
- **`method = true`**: Mark any function whose first argument (or first element
  of a tuple argument) is `time`. This covers `toReal`, `toSeconds`,
  `toMilliseconds`, `toMicroseconds`, `toNanoseconds`, `toString`, `compare`,
  `+`, `-`, `<`, `<=`, `>`, `>=`. The `from*` functions (`fromReal`,
  `fromSeconds`, etc.), `now`, `fmt`, `fromString`, and `zeroTime` are not
  methods because their first argument is not `time`.
- **Precision**: `toReal`/`fromReal` use Java `double` internally (even
  though Morel's `real` is `float`). Add a cast to `float` for the return.
  Actually Morel `real` maps to Java `Float` — be careful here.

## Key type-system note

After adding `TIME` to `Eqtype`, the type `time` will be registered in the
TypeSystem via `BuiltIn.dataTypes()`. All `TIME_*` function types reference
it as `ts.lookup(Eqtype.TIME)`. This is the same pattern used for `bag`,
`list`, and `vector`.

Unlike those collection types, `time` has no type parameters (`varCount = 0`),
so `ts.lookup(Eqtype.TIME)` returns a concrete `DataType` directly (not a
`ForallType`).

---

# Phase 2: `Date` structure (issue #278)

**Prerequisite: Phase 1 (`Time` structure) must be complete**, because `Date`
uses `Time.time` in several places (`offset`, `fromTimeLocal`, `fromTimeUniv`,
`toTime`, `localOffset`).

## Interface to implement

```sml
datatype weekday = Mon | Tue | Wed | Thu | Fri | Sat | Sun

datatype month = Jan | Feb | Mar | Apr | May | Jun
               | Jul | Aug | Sep | Oct | Nov | Dec

type date
exception Date

val date : {year:int, month:month, day:int, hour:int,
            minute:int, second:int, offset:Time.time option} -> date

val year    : date -> int
val month   : date -> month
val day     : date -> int
val hour    : date -> int
val minute  : date -> int
val second  : date -> int
val weekDay : date -> weekday
val yearDay : date -> int
val offset  : date -> Time.time option
val isDst   : date -> bool option

val localOffset : unit -> Time.time

val fromTimeLocal : Time.time -> date
val fromTimeUniv  : Time.time -> date
val toTime : date -> Time.time

val compare : date * date -> order

val fmt      : string -> date -> string
val toString : date -> string
val fromString : string -> date option
```

Note: `scan` is omitted (requires `StringCvt.reader`).

## Java representation

- **`weekday`** and **`month`**: Standard datatypes, represented at runtime as
  single-element `List` containing the constructor name string (same as
  `order`). Add to `BuiltIn.Datatype` enum with their constructors in
  `BuiltIn.Constructor`.

- **`date`**: Add `DATE("date", 0)` to `Eqtype` enum. Represent date values as
  `java.time.OffsetDateTime`. Key conversions:
  - `Time.time` (nanoseconds `Long`) ↔ `OffsetDateTime` via
    `Instant.ofEpochSecond(nanos / 1_000_000_000L, nanos % 1_000_000_000L)`
  - SML offset convention (west = positive) is the **opposite** of Java
    (`ZoneOffset` uses east = positive); negate when converting.
  - `isDst` requires `ZonedDateTime` (not `OffsetDateTime`); use the system
    default `ZoneId` for `fromTimeLocal` so DST info is available, and return
    `NONE` for dates constructed with an explicit offset.

## Files to change

### 8. `src/main/java/net/hydromatic/morel/compile/BuiltIn.java`

**Step 8a: Add `weekday` and `month` to `Datatype` enum** (sorted
alphabetically within the enum):

```java
DATE_MONTH(
    "month",
    0,
    h -> h.tyCon(Constructor.DATE_JAN)
          .tyCon(Constructor.DATE_FEB)
          .tyCon(Constructor.DATE_MAR)
          .tyCon(Constructor.DATE_APR)
          .tyCon(Constructor.DATE_MAY)
          .tyCon(Constructor.DATE_JUN)
          .tyCon(Constructor.DATE_JUL)
          .tyCon(Constructor.DATE_AUG)
          .tyCon(Constructor.DATE_SEP)
          .tyCon(Constructor.DATE_OCT)
          .tyCon(Constructor.DATE_NOV)
          .tyCon(Constructor.DATE_DEC)),

DATE_WEEKDAY(
    "weekday",
    0,
    h -> h.tyCon(Constructor.DATE_MON)
          .tyCon(Constructor.DATE_TUE)
          .tyCon(Constructor.DATE_WED)
          .tyCon(Constructor.DATE_THU)
          .tyCon(Constructor.DATE_FRI)
          .tyCon(Constructor.DATE_SAT)
          .tyCon(Constructor.DATE_SUN)),
```

**Step 8b: Add `DATE` to `Eqtype` enum** (before `LIST`, alphabetically):

```java
DATE("date", 0),
```

**Step 8c: Add constructors to `Constructor` enum** (sorted alphabetically):

```java
DATE_FRI(Datatype.DATE_WEEKDAY, "Fri"),
DATE_MON(Datatype.DATE_WEEKDAY, "Mon"),
DATE_SAT(Datatype.DATE_WEEKDAY, "Sat"),
DATE_SUN(Datatype.DATE_WEEKDAY, "Sun"),
DATE_THU(Datatype.DATE_WEEKDAY, "Thu"),
DATE_TUE(Datatype.DATE_WEEKDAY, "Tue"),
DATE_WED(Datatype.DATE_WEEKDAY, "Wed"),
DATE_JAN(Datatype.DATE_MONTH, "Jan"),
DATE_FEB(Datatype.DATE_MONTH, "Feb"),
DATE_MAR(Datatype.DATE_MONTH, "Mar"),
DATE_APR(Datatype.DATE_MONTH, "Apr"),
DATE_MAY(Datatype.DATE_MONTH, "May"),
DATE_JUN(Datatype.DATE_MONTH, "Jun"),
DATE_JUL(Datatype.DATE_MONTH, "Jul"),
DATE_AUG(Datatype.DATE_MONTH, "Aug"),
DATE_SEP(Datatype.DATE_MONTH, "Sep"),
DATE_OCT(Datatype.DATE_MONTH, "Oct"),
DATE_NOV(Datatype.DATE_MONTH, "Nov"),
DATE_DEC(Datatype.DATE_MONTH, "Dec"),
```

**Step 8d: Add `DATE_*` function enum constants.** Methods (first arg is
`date`, or first element of tuple arg is `date`): `year`, `month`, `day`,
`hour`, `minute`, `second`, `weekDay`, `yearDay`, `offset`, `isDst`, `toTime`,
`toString`, `compare`. Non-methods: `date` (constructor), `localOffset`, `fmt`,
`fromTimeLocal`, `fromTimeUniv`, `fromString`.

```java
DATE_DATE(
    "Date", "date",
    ts -> ts.fnType(
        ts.recordType(RecordType.map(
            "day", INT, "hour", INT, "minute", INT, "month",
            ts.lookup(Datatype.DATE_MONTH), "offset",
            ts.option(ts.lookup(Eqtype.TIME)), "second", INT, "year", INT)),
        ts.lookup(Eqtype.DATE))),

DATE_YEAR("Date", "year", true, ts -> ts.fnType(ts.lookup(Eqtype.DATE), INT)),
DATE_MONTH_FN("Date", "month", true,
    ts -> ts.fnType(ts.lookup(Eqtype.DATE), ts.lookup(Datatype.DATE_MONTH))),
DATE_DAY("Date", "day", true, ts -> ts.fnType(ts.lookup(Eqtype.DATE), INT)),
DATE_HOUR("Date", "hour", true, ts -> ts.fnType(ts.lookup(Eqtype.DATE), INT)),
DATE_MINUTE("Date", "minute", true, ts -> ts.fnType(ts.lookup(Eqtype.DATE), INT)),
DATE_SECOND("Date", "second", true, ts -> ts.fnType(ts.lookup(Eqtype.DATE), INT)),
DATE_WEEK_DAY("Date", "weekDay", true,
    ts -> ts.fnType(ts.lookup(Eqtype.DATE), ts.lookup(Datatype.DATE_WEEKDAY))),
DATE_YEAR_DAY("Date", "yearDay", true,
    ts -> ts.fnType(ts.lookup(Eqtype.DATE), INT)),
DATE_OFFSET("Date", "offset", true,
    ts -> ts.fnType(ts.lookup(Eqtype.DATE), ts.option(ts.lookup(Eqtype.TIME)))),
DATE_IS_DST("Date", "isDst", true,
    ts -> ts.fnType(ts.lookup(Eqtype.DATE), ts.option(BOOL))),

DATE_LOCAL_OFFSET("Date", "localOffset",
    ts -> ts.fnType(UNIT, ts.lookup(Eqtype.TIME))),

DATE_FROM_TIME_LOCAL("Date", "fromTimeLocal",
    ts -> ts.fnType(ts.lookup(Eqtype.TIME), ts.lookup(Eqtype.DATE))),
DATE_FROM_TIME_UNIV("Date", "fromTimeUniv",
    ts -> ts.fnType(ts.lookup(Eqtype.TIME), ts.lookup(Eqtype.DATE))),
DATE_TO_TIME("Date", "toTime", true,
    ts -> ts.fnType(ts.lookup(Eqtype.DATE), ts.lookup(Eqtype.TIME))),

DATE_COMPARE("Date", "compare", true,
    ts -> ts.fnType(ts.tupleType(ts.lookup(Eqtype.DATE), ts.lookup(Eqtype.DATE)),
        ts.order())),

DATE_FMT("Date", "fmt",
    ts -> ts.fnType(STRING, ts.fnType(ts.lookup(Eqtype.DATE), STRING))),
DATE_TO_STRING("Date", "toString", true,
    ts -> ts.fnType(ts.lookup(Eqtype.DATE), STRING)),
DATE_FROM_STRING("Date", "fromString",
    ts -> ts.fnType(STRING, ts.option(ts.lookup(Eqtype.DATE)))),
```

Note: `DATE_MONTH_FN` uses the name `"month"` — same as the `month` datatype.
This is valid in SML (a function and a type can share a name); the BuiltIn
enum key just needs to be distinct.

### 9. `src/main/java/net/hydromatic/morel/eval/Codes.java`

**Step 9a: Add `DATE` exception** to `BuiltInExn` (before `DIV`):

```java
DATE("Date", "Date", null),
```

**Step 9b: Add helper to convert between `Time.time` (Long nanos) and
`OffsetDateTime`:**

```java
private static OffsetDateTime timeToOffsetDateTime(long nanos, ZoneOffset zone) {
  long secs = nanos / 1_000_000_000L;
  int ns = (int) (nanos % 1_000_000_000L);
  return OffsetDateTime.ofInstant(Instant.ofEpochSecond(secs, ns), zone);
}

private static long offsetDateTimeToTime(OffsetDateTime odt) {
  return odt.toEpochSecond() * 1_000_000_000L + odt.getNano();
}
```

**Step 9c: Add `Applicable` implementations.** Key examples:

```java
/** @see BuiltIn#DATE_DATE */
// Takes a record (represented as List of field values sorted by field name:
// day, hour, minute, month, offset, second, year).
// Normalizes via OffsetDateTime arithmetic per SML spec.

/** @see BuiltIn#DATE_FROM_TIME_LOCAL */
private static final Applicable1 DATE_FROM_TIME_LOCAL =
    new BaseApplicable1<OffsetDateTime, Long>(BuiltIn.DATE_FROM_TIME_LOCAL) {
      @Override public OffsetDateTime apply(Long t) {
        ZoneOffset offset = ZoneId.systemDefault()
            .getRules().getOffset(Instant.ofEpochSecond(t / 1_000_000_000L));
        return timeToOffsetDateTime(t, offset);
      }
    };

/** @see BuiltIn#DATE_FROM_TIME_UNIV */
private static final Applicable1 DATE_FROM_TIME_UNIV =
    new BaseApplicable1<OffsetDateTime, Long>(BuiltIn.DATE_FROM_TIME_UNIV) {
      @Override public OffsetDateTime apply(Long t) {
        return timeToOffsetDateTime(t, ZoneOffset.UTC);
      }
    };

/** @see BuiltIn#DATE_TO_TIME */
private static final Applicable1 DATE_TO_TIME =
    new BaseApplicable1<Long, OffsetDateTime>(BuiltIn.DATE_TO_TIME) {
      @Override public Long apply(OffsetDateTime d) {
        return offsetDateTimeToTime(d);
      }
    };

/** @see BuiltIn#DATE_LOCAL_OFFSET */
// Returns the current local UTC offset as Time.time (nanos), negated
// (SML: west = positive; Java ZoneOffset: east = positive).

/** @see BuiltIn#DATE_FMT */
// Delegates to java.time.format.DateTimeFormatter using strftime-style
// codes translated to Java format patterns.

/** @see BuiltIn#DATE_TO_STRING */
// SML format: "Wed Mar 08 19:06:45 1995" (fixed 24-char format).
// Java equivalent: DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss yyyy", Locale.US)

/** @see BuiltIn#DATE_IS_DST */
// Returns NONE for dates constructed with an explicit offset (no DST info).
// For dates from fromTimeLocal, use ZonedDateTime to check DST.
// Since OffsetDateTime doesn't carry DST, store a DST flag separately,
// or always return NONE (conservative, spec-compliant).
```

**Step 9d: Register all `DATE_*` in the `CODES` static map.**

### 10. `src/main/resources/functions.toml`

Add `[[structures]]`, `[[exceptions]]`, `[[types]]` (for both `date`,
`weekday`, and `month`), and `[[functions]]` entries for `Date`. Ordinals
continue from where `Time` left off (261–279 for 19 functions). Example:

```toml
[[structures]]
name = "Date"
description = "Calendar dates and time zone operations."
overview = """
The `Date` structure provides operations for converting between `Time.time`
values and calendar dates, with support for time zones, formatting, and
parsing. It depends on the `Time` structure.
"""

[[exceptions]]
structure = "Date"
name = "Date"
description = """
is raised when a date cannot be constructed or a conversion fails.
"""

[[types]]
structure = "Date"
name = "weekday"
type = "datatype weekday = Mon | Tue | Wed | Thu | Fri | Sat | Sun"
description = """
is the type of days of the week.
"""

[[types]]
structure = "Date"
name = "month"
type = "datatype month = Jan | Feb | Mar | Apr | May | Jun | Jul | Aug | Sep | Oct | Nov | Dec"
description = """
is the type of months of the year.
"""

[[types]]
structure = "Date"
name = "date"
type = "type date"
description = """
is an abstract type representing a calendar date and time in a specific
time zone.
"""

[[functions]]
ordinal = 261
structure = "Date"
name = "date"
specified = "basis"
type = "{year:int, month:month, day:int, hour:int, minute:int, second:int, offset:time option} → date"
prototype = "date {year, month, day, hour, minute, second, offset}"
description = """
constructs a canonical date from the given fields. Out-of-range values are
normalised (e.g. 70 seconds becomes 1 minute 10 seconds). Raises `Date` if
the resulting date is not representable.
"""

... (one entry per function through ordinal 279)
```

### 11. `src/test/resources/script/built-in.smli`

Add a `Date` section (before `Either`, alphabetically). Include:

```sml
Date;
> val it =
>   {compare=fn,date=fn,day=fn,fmt=fn,fromString=fn,fromTimeLocal=fn,
>    fromTimeUniv=fn,hour=fn,isDst=fn,localOffset=fn,minute=fn,month=fn,
>    offset=fn,second=fn,toTime=fn,toString=fn,weekDay=fn,year=fn,yearDay=fn}
>   : {compare:date * date -> order, ...}

val epoch = Time.fromSeconds 0;
> val epoch = 0 : time

val d = Date.fromTimeUniv epoch;
> val d = ... : date

Date.year d;
> val it = 1970 : int

d.year ();
> val it = 1970 : int

Date.month d;
> val it = Jan : month

d.month ();
> val it = Jan : month

Date.day d;
> val it = 1 : int

d.day ();
> val it = 1 : int

Date.hour d;
> val it = 0 : int

d.hour ();
> val it = 0 : int

Date.minute d;
> val it = 0 : int

d.minute ();
> val it = 0 : int

Date.second d;
> val it = 0 : int

d.second ();
> val it = 0 : int

Date.weekDay d;
> val it = Thu : weekday

d.weekDay ();
> val it = Thu : weekday

Date.yearDay d;
> val it = 0 : int

d.yearDay ();
> val it = 0 : int

Date.offset d;
> val it = SOME 0 : time option

d.offset ();
> val it = SOME 0 : time option

Date.isDst d;
> val it = NONE : bool option

d.isDst ();
> val it = NONE : bool option

Date.toTime d;
> val it = 0 : time

d.toTime ();
> val it = 0 : time

Date.toString d;
> val it = "Thu Jan  1 00:00:00 1970" : string

d.toString ();
> val it = "Thu Jan  1 00:00:00 1970" : string

Date.fmt "%Y-%m-%d" d;
> val it = "1970-01-01" : string

Date.compare (d, d);
> val it = EQUAL : order

d.compare d;
> val it = EQUAL : order

Date.fromString "Thu Jan  1 00:00:00 1970";
> val it = SOME ... : date option

Date.fromString "bad";
> val it = NONE : date option

val d2 = Date.date {year=2024, month=Date.Mar, day=15, hour=12,
                    minute=30, second=0, offset=SOME (Time.fromSeconds 0)};
> val d2 = ... : date

Date.year d2;
> val it = 2024 : int

d2.year ();
> val it = 2024 : int

Date.month d2;
> val it = Mar : month

d2.month ();
> val it = Mar : month
```

### 12. `docs/lib/date.md` (new file)

Same structure as `docs/lib/time.md`, with markers
`[//]: # (start:lib/date)` / `[//]: # (end:lib/date)`.

## Build and test sequence for Date

```bash
# After Phase 1 (Time) is complete and all its tests pass:

./mvnw compile
./mvnw test -Dtest=LintTest   # regenerate date.md content
./mvnw test -Dtest=MainTest
./mvnw verify
```

## Scope decisions for Date

- **`scan`**: Omitted (requires `StringCvt.reader`).
- **`isDst`**: Return `NONE` for dates constructed from an explicit offset
  (no DST information available). For `fromTimeLocal` results, use
  `ZoneId.systemDefault().getRules().isDaylightSavings(instant)` to determine
  DST, and wrap the `OffsetDateTime` with that flag (e.g., store as a
  two-element array `[OffsetDateTime, Boolean]`).
- **`localOffset` sign convention**: SML defines offset as nanoseconds west of
  UTC (positive = west). Java's `ZoneOffset` uses seconds east of UTC
  (positive = east). Negate when converting.
- **`fmt` format codes**: Map SML `strftime`-style codes (e.g. `%Y`, `%m`,
  `%d`) to `java.time.format.DateTimeFormatter` patterns. The full mapping
  is in the POSIX `strftime` spec; implement the common subset.
- **`method = true`**: `year`, `month`, `day`, `hour`, `minute`, `second`,
  `weekDay`, `yearDay`, `offset`, `isDst`, `toTime`, `toString`, `compare`
  (first arg is `date`). Non-methods: `date`, `localOffset`, `fmt`,
  `fromTimeLocal`, `fromTimeUniv`, `fromString`.
- **Ordinals**: Continue from the end of the `Time` functions (261+).
