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
# Builds the five seed queries of script/spark.smli with the PySpark
# Connect DataFrame API and prints each unresolved plan in protobuf
# text format; seed-plans.txt is this output with a LocalRelation's
# Arrow payload replaced by its row count.
#
# Needs only the lightweight client:
#   python3 -m venv pyenv && pyenv/bin/pip install pyspark-client
#   pyenv/bin/python capture.py
# Without arguments no server is contacted: the client's config and
# column-validation round trips are stubbed, and plans are built
# offline. With --execute, the queries also run against a Spark
# Connect server at localhost:15002 and print their rows; --seed
# first creates the emp and dept tables there.
import re, sys
from pyspark.sql import SparkSession
from pyspark.sql import functions as F
from pyspark.sql.types import *
from google.protobuf import text_format

import pyspark.errors.utils as _eu
_eu.is_debugging_enabled = lambda: False  # avoid a config RPC; plans build offline
spark = SparkSession.builder.remote("sc://localhost:15002").create()
EXECUTE = "--execute" in sys.argv
if not EXECUTE:
    spark.is_mock_session = True  # skip server-side column validation
    _defaults = {
        "spark.sql.timestampType": "TIMESTAMP_LTZ",
        "spark.sql.session.timeZone": "UTC",
        "spark.sql.session.localRelationCacheThreshold": "67108864",
        "spark.sql.execution.pandas.convertToArrowArraySafely": "false",
        "spark.sql.execution.pandas.inferPandasDictAsMap": "false",
        "spark.sql.pyspark.inferNestedDictAsStruct.enabled": "false",
        "spark.sql.pyspark.legacy.inferArrayTypeFromFirstElement.enabled": "false",
        "spark.sql.pyspark.legacy.inferMapTypeFromFirstPair.enabled": "false",
        "spark.sql.execution.arrow.useLargeVarTypes": "false"}
    spark._client.get_config_dict = lambda *keys: {k: _defaults.get(k) for k in keys}

def seed():
    emp_schema = StructType([
        StructField("empno", IntegerType(), False),
        StructField("ename", StringType(), False),
        StructField("job", StringType(), False),
        StructField("mgr", IntegerType(), False),
        StructField("hiredate", StringType(), False),
        StructField("sal", DoubleType(), False),
        StructField("comm", DoubleType(), False),
        StructField("deptno", IntegerType(), False)])
    emp_rows = [
        (7369, "SMITH", "CLERK", 7902, "1980-12-17", 800.0, 0.0, 20),
        (7499, "ALLEN", "SALESMAN", 7698, "1981-02-20", 1600.0, 300.0, 30),
        (7521, "WARD", "SALESMAN", 7698, "1981-02-22", 1250.0, 500.0, 30),
        (7566, "JONES", "MANAGER", 7839, "1981-02-04", 2975.0, 0.0, 20),
        (7654, "MARTIN", "SALESMAN", 7698, "1981-09-28", 1250.0, 1400.0, 30),
        (7698, "BLAKE", "MANAGER", 7839, "1981-01-05", 2850.0, 0.0, 30),
        (7782, "CLARK", "MANAGER", 7839, "1981-06-09", 2450.0, 0.0, 10),
        (7788, "SCOTT", "ANALYST", 7566, "1987-04-19", 3000.0, 0.0, 20),
        (7839, "KING", "PRESIDENT", 0, "1981-11-17", 5000.0, 0.0, 10),
        (7844, "TURNER", "SALESMAN", 7698, "1981-09-08", 1500.0, 0.0, 30),
        (7876, "ADAMS", "CLERK", 7788, "1987-05-23", 1100.0, 0.0, 20),
        (7900, "JAMES", "CLERK", 7698, "1981-12-03", 950.0, 0.0, 30),
        (7902, "FORD", "ANALYST", 7566, "1981-12-03", 3000.0, 0.0, 20),
        (7934, "MILLER", "CLERK", 7782, "1982-01-23", 1300.0, 0.0, 10)]
    dept_schema = StructType([
        StructField("deptno", IntegerType(), False),
        StructField("dname", StringType(), False),
        StructField("loc", StringType(), False)])
    dept_rows = [(10, "ACCOUNTING", "NEW YORK"), (20, "RESEARCH", "DALLAS"),
                 (30, "SALES", "CHICAGO"), (40, "OPERATIONS", "BOSTON")]
    spark.createDataFrame(emp_rows, emp_schema).write.mode("overwrite").saveAsTable("emp")
    spark.createDataFrame(dept_rows, dept_schema).write.mode("overwrite").saveAsTable("dept")

def canon(text):
    # plan ids are per-session counters; renumber in order of appearance
    ids = {}
    def sub(m):
        ids.setdefault(m.group(1), len(ids))
        return "plan_id: %d" % ids[m.group(1)]
    return re.sub(r"plan_id: (\d+)", sub, text)

def show(name, df, execute=None):
    execute = EXECUTE if execute is None else execute
    print("=== %s ===" % name)
    print(canon(text_format.MessageToString(df._plan.to_proto(spark._client))))
    if execute:
        for row in df.collect():
            print("  ", tuple(row))
    print()

if "--seed" in sys.argv:
    seed()
# Every scan is aliased by its Morel binder, and every column reference
# is qualified by that alias. This is the only form in which Spark 4.0.0
# resolves the outer reference of a correlated subquery (query 5) when
# both sides have a column of the same name; qualifying by plan id
# fails with CANNOT_RESOLVE_DATAFRAME_COLUMN.
e = spark.table("emp").alias("e")
d = spark.table("dept").alias("d")

# Query 1: filter and project over an inline relation.
local_schema = StructType([
    StructField("b", BooleanType(), False),
    StructField("i", IntegerType(), False),
    StructField("s", StringType(), False),
    StructField("x", DoubleType(), False)])
r = spark.createDataFrame(
    [(True, 1, "one", 1.5), (False, 2, "two", 2.5), (True, 3, "three", 3.5)],
    local_schema).alias("r")
q1 = (r.filter(F.col("r.b") & (F.col("r.i") > 1))
      .select(F.col("r.s"), (F.col("r.x") * 2.0).alias("y")))
show("q1", q1)

# Query 2: equijoin.
q2 = (e.join(d, F.col("e.deptno") == F.col("d.deptno"))
      .select(F.col("d.dname"), F.col("e.ename")))
show("q2", q2)

# Query 3: group by with aggregates.
q3 = e.groupBy(F.col("e.deptno")).agg(
    F.count(F.lit(1)).alias("c"),
    F.max(F.col("e.sal")).alias("top"),
    F.sum(F.col("e.sal")).alias("total"))
show("q3", q3)

# Query 4: sort and limit. The projection rebinds, so the sort keys are
# the projected names, unqualified.
q4 = (e.select(F.col("e.ename"), F.col("e.sal"))
      .orderBy(F.col("sal").desc(), F.col("ename").asc()).limit(3))
show("q4", q4)

# Query 5: correlated exists subquery.
inner = e.where((F.col("e.deptno") == F.col("d.deptno")) & (F.col("e.job") == "CLERK"))
q5 = d.where(inner.exists()).select(F.col("d.dname"))
show("q5", q5)

# End capture.py
