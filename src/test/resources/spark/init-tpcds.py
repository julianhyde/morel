# Licensed to Julian Hyde under one or more contributor license
# agreements.  See the NOTICE file distributed with this work
# for additional information regarding copyright ownership.
# Julian Hyde licenses this file to you under the Apache
# License, Version 2.0 (the "License"); you may not use this
# file except in compliance with the License.  You may obtain a
# copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
# either express or implied.  See the License for the specific
# language governing permissions and limitations under the
# License.
#
# Loads the TPC-DS data set (derived from TPC-DS; not a TPC-DS
# benchmark) into the Morel test container, and captures Spark SQL's
# answer to each TPC-DS query, for hydromatic/morel#502.
#
# init.py runs this, after it has created its own tables, when
# start-spark.sh is given "--tpcds DIR". start-spark.sh mounts DIR, the
# output of tpcds/generate.sh, at /tpcds, and src/test/resources/tpcds,
# which holds schema.sql and the queries, at /morel-tpcds. This program
#
#  1. creates database "tpcds", with one Parquet table per table of
#     schema.sql, loaded from DIR's TABLE.csv.gz, and a "morel.nullable"
#     property naming the columns that schema.sql does not declare NOT
#     NULL, which are the columns that Morel reads as options;
#  2. prints "Morel TPC-DS tables created";
#  3. runs each query in /morel-tpcds/queries, and writes its answer to
#     /tmp/morel-tpcds/answers/QUERY.csv, in the same format as the data
#     (a typed header, nulls as empty fields, strings quoted where
#     needed), or the error to QUERY.error;
#  4. prints "Morel TPC-DS answers captured", after which start-spark.sh
#     copies the answers to DIR/answers.
#
# A char(n) or varchar(n) column is a STRING column here. Spark pads
# CHAR(n) values with spaces when it reads them, and Morel's file
# reader does not, so with CHAR the local data and Spark's would
# differ; the query texts compare these columns only with literals and
# with each other, and none depends on the padding.
#
# The answers are the oracle for tpcds-10m.smli: they are computed by
# Spark from the same rows that Morel reads locally.

import os
import re
import time

from pyspark.sql.types import (
    BooleanType, ByteType, DateType, DecimalType, DoubleType, FloatType,
    IntegerType, LongType, ShortType)

# "spark" and "sql" are init.py's, which runs this with runpy.
spark = globals()["spark"]
sql = globals()["sql"]

DATA = "/tpcds"
RESOURCES = "/morel-tpcds"
ANSWERS = "/tmp/morel-tpcds/answers"


def read_schema(path):
    """Reads schema.sql, returning a list of (table, [(column, type,
    nullable)]), in the file's order. The format is narrow; see the
    comment at the top of schema.sql."""
    tables = []
    columns = None
    with open(path) as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("--"):
                continue
            m = re.fullmatch(r"create table (\w+) \(", line)
            if m:
                columns = []
                tables.append((m.group(1), columns))
            elif line == ");":
                columns = None
            else:
                m = re.fullmatch(
                    r"(\w+) +([a-z]+(?:\([0-9, ]+\))?)( not null)?,?", line)
                if not m or columns is None:
                    raise ValueError("bad line in schema.sql: " + line)
                columns.append((m.group(1), m.group(2), not m.group(3)))
    return tables


def spark_type(t):
    """Converts a schema.sql type to the Spark type of the table column."""
    if t.startswith("char") or t.startswith("varchar"):
        return "STRING"
    return t.upper()


def load(tables):
    sql("CREATE DATABASE IF NOT EXISTS tpcds")
    for name, columns in tables:
        ddl = ", ".join("%s %s" % (c, spark_type(t)) for c, t, _ in columns)
        df = (spark.read
              .schema(ddl)
              .option("header", True)
              .option("quote", "'")
              .option("escape", "'")
              .option("mode", "FAILFAST")
              .csv("%s/%s.csv.gz" % (DATA, name)))
        df.createOrReplaceTempView("csv_" + name)
        sql("DROP TABLE IF EXISTS tpcds." + name)
        sql("CREATE TABLE tpcds.%s (%s) USING parquet" % (name, ddl))
        sql("INSERT INTO tpcds.%s SELECT * FROM csv_%s" % (name, name))
        sql("DROP VIEW csv_" + name)
        # Without statistics Spark guesses sizes, and may decide to
        # broadcast customer_demographics (1.9 million rows at any scale)
        # to every join, which exhausts the driver's memory (q35, q69).
        sql("ANALYZE TABLE tpcds.%s COMPUTE STATISTICS" % name)
        nullable = ",".join(c for c, _, n in columns if n)
        sql("ALTER TABLE tpcds.%s SET TBLPROPERTIES ('morel.nullable' = '%s')"
            % (name, nullable))
        # The row count and each column's nulls, to compare with what
        # generate.sh printed.
        counts = sql(
            "SELECT count(*) AS n, %s FROM tpcds.%s"
            % (", ".join("count(%s) AS %s" % (c, c) for c, _, _ in columns),
               name)).collect()[0]
        n = counts["n"]
        print("Morel TPC-DS %s: %d rows%s" % (
            name, n,
            "".join("; %s: %d nulls" % (c, n - counts[c])
                    for c, _, _ in columns if counts[c] != n)),
              flush=True)


def header_type(dt):
    """The type of an answer column, as the file reader's header names
    it."""
    if isinstance(dt, (ByteType, ShortType, IntegerType, LongType)):
        return "int"
    if isinstance(dt, DecimalType):
        return "decimal"
    if isinstance(dt, (FloatType, DoubleType)):
        return "real"
    if isinstance(dt, DateType):
        return "date"
    if isinstance(dt, BooleanType):
        return "bool"
    return "string"


def field(value, t):
    """Formats a value as a field: empty for null, a string quoted if it
    would otherwise be read as something else."""
    if value is None:
        return ""
    if t == "decimal":
        return format(value, "f")
    if t == "real":
        return repr(float(value))
    if t == "date":
        return value.isoformat()
    if t == "bool":
        return "true" if value else "false"
    if t == "int":
        return str(value)
    s = str(value)
    if (s == "" or s == "NULL" or s[0] == " " or s[-1] == " "
            or any(ch in s for ch in ",'\"\n\r")):
        return "'" + s.replace("'", "''") + "'"
    return s


def run(text):
    """Runs a query and returns (schema, rows). If Spark runs out of
    memory broadcasting one side of a join, which statistics do not
    always prevent, tries again without broadcast joins; the answer is
    the same."""
    try:
        df = sql(text)
        return df.schema, df.collect()
    except Exception as e:  # pylint: disable=broad-except
        if "build and broadcast" not in str(e):
            raise
    key = "spark.sql.autoBroadcastJoinThreshold"
    old = spark.conf.get(key)
    spark.conf.set(key, "-1")
    try:
        df = sql(text)
        return df.schema, df.collect()
    finally:
        spark.conf.set(key, old)


def write_answer(path, schema, rows):
    types = [header_type(f.dataType) for f in schema.fields]
    # A name such as "sum(x, 2)" would break the header, so each
    # character that is not a letter, digit or underscore becomes one.
    names = [re.sub(r"\W", "_", f.name) for f in schema.fields]
    has_null = [any(r[i] is None for r in rows) for i in range(len(names))]
    with open(path, "w") as f:
        f.write(",".join(
            "%s:%s%s" % (n, t, " option" if o else "")
            for n, t, o in zip(names, types, has_null)) + "\n")
        for r in rows:
            f.write(",".join(field(v, t) for v, t in zip(r, types)) + "\n")


def capture():
    os.makedirs(ANSWERS, exist_ok=True)
    sql("USE tpcds")
    queries = sorted(
        (f[:-len(".sql")] for f in os.listdir(RESOURCES + "/queries")
         if f.endswith(".sql")),
        key=lambda q: (int(re.sub(r"\D", "", q)), q))
    failed = []
    for q in queries:
        with open("%s/queries/%s.sql" % (RESOURCES, q)) as f:
            text = f.read()
        start = time.time()
        try:
            schema, rows = run(text)
        except Exception as e:  # pylint: disable=broad-except
            failed.append(q)
            with open("%s/%s.error" % (ANSWERS, q), "w") as f:
                f.write(str(e) + "\n")
            print("Morel TPC-DS %s: failed: %s" % (q, str(e).splitlines()[0]),
                  flush=True)
            # Under ANSI a division by zero raises, as it does in Morel,
            # so the error is the answer. (q12 and q90 at scale 0.01.)
            # Outside ANSI it is null; keep that answer too, for
            # reference.
            if "DIVIDE_BY_ZERO" in str(e):
                spark.conf.set("spark.sql.ansi.enabled", "false")
                try:
                    schema, rows = run(text)
                    write_answer("%s/%s.ansi-off.csv" % (ANSWERS, q),
                                 schema, rows)
                finally:
                    spark.conf.set("spark.sql.ansi.enabled", "true")
            continue
        write_answer("%s/%s.csv" % (ANSWERS, q), schema, rows)
        print("Morel TPC-DS %s: %d rows in %.1fs"
              % (q, len(rows), time.time() - start), flush=True)
    with open(ANSWERS + "/VERSION", "w") as f:
        f.write("spark=%s\n" % spark.version)
    return failed


load(read_schema(RESOURCES + "/schema.sql"))
print("Morel TPC-DS tables created", flush=True)
failed = capture()
print("Morel TPC-DS answers captured: %d failed%s"
      % (len(failed), (" (" + ", ".join(failed) + ")") if failed else ""),
      flush=True)

# End init-tpcds.py
