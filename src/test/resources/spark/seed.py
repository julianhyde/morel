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
# Driver program for the Morel test container. start-spark.sh runs it
# with spark-submit and the Spark Connect plugin, so the Connect server
# runs in this program's SparkContext and clients see the tables it
# creates. It creates the seed tables, prints a marker line, and then
# waits forever, keeping the server up.
#
# Seed tables:
#   emp, dept  The scott rows, as in script/spark.smli.
#   zoo        One column per Spark type. Row 1 has typical values, row 2
#              edge values (extremes, NaN, empty, non-ASCII), and row 3
#              nulls in every column but id.
#
# Nullability: Spark's built-in catalog does not keep NOT NULL for
# tables stored as files, so every column of these tables reads back as
# nullable, whatever the schema said when it was created.

import shutil
import time

from pyspark.sql import SparkSession

spark = SparkSession.builder.appName("Morel Spark Connect server").getOrCreate()


def sql(text):
    return spark.sql(text)


# The catalog is in memory, but the warehouse is on disk and survives a
# container restart; a managed table cannot be created over its old
# files, so clear them.
warehouse = spark.conf.get("spark.sql.warehouse.dir")
if warehouse.startswith("file:"):
    shutil.rmtree(warehouse[len("file:"):], ignore_errors=True)

sql("DROP TABLE IF EXISTS emp")
sql("""CREATE TABLE emp (
  empno INT, ename STRING, job STRING, mgr INT, hiredate STRING,
  sal DOUBLE, comm DOUBLE, deptno INT) USING parquet""")
sql("""INSERT INTO emp VALUES
  (7369, 'SMITH', 'CLERK', 7902, '1980-12-17', 800.0, 0.0, 20),
  (7499, 'ALLEN', 'SALESMAN', 7698, '1981-02-20', 1600.0, 300.0, 30),
  (7521, 'WARD', 'SALESMAN', 7698, '1981-02-22', 1250.0, 500.0, 30),
  (7566, 'JONES', 'MANAGER', 7839, '1981-02-04', 2975.0, 0.0, 20),
  (7654, 'MARTIN', 'SALESMAN', 7698, '1981-09-28', 1250.0, 1400.0, 30),
  (7698, 'BLAKE', 'MANAGER', 7839, '1981-01-05', 2850.0, 0.0, 30),
  (7782, 'CLARK', 'MANAGER', 7839, '1981-06-09', 2450.0, 0.0, 10),
  (7788, 'SCOTT', 'ANALYST', 7566, '1987-04-19', 3000.0, 0.0, 20),
  (7839, 'KING', 'PRESIDENT', 0, '1981-11-17', 5000.0, 0.0, 10),
  (7844, 'TURNER', 'SALESMAN', 7698, '1981-09-08', 1500.0, 0.0, 30),
  (7876, 'ADAMS', 'CLERK', 7788, '1987-05-23', 1100.0, 0.0, 20),
  (7900, 'JAMES', 'CLERK', 7698, '1981-12-03', 950.0, 0.0, 30),
  (7902, 'FORD', 'ANALYST', 7566, '1981-12-03', 3000.0, 0.0, 20),
  (7934, 'MILLER', 'CLERK', 7782, '1982-01-23', 1300.0, 0.0, 10)""")

sql("DROP TABLE IF EXISTS dept")
sql("CREATE TABLE dept (deptno INT, dname STRING, loc STRING) USING parquet")
sql("""INSERT INTO dept VALUES
  (10, 'ACCOUNTING', 'NEW YORK'),
  (20, 'RESEARCH', 'DALLAS'),
  (30, 'SALES', 'CHICAGO'),
  (40, 'OPERATIONS', 'BOSTON')""")

sql("DROP TABLE IF EXISTS zoo")
sql("""CREATE TABLE zoo (
  id INT,
  b BOOLEAN,
  i8 TINYINT,
  i16 SMALLINT,
  i32 INT,
  i64 BIGINT,
  f32 FLOAT,
  f64 DOUBLE,
  dec DECIMAL(10, 2),
  s STRING,
  bin BINARY,
  d DATE,
  ts TIMESTAMP,
  ts_ntz TIMESTAMP_NTZ,
  arr ARRAY<INT>,
  st STRUCT<a: INT, b: STRING>,
  m MAP<STRING, INT>) USING parquet""")
sql("""INSERT INTO zoo VALUES
  (1, true, 1Y, 2S, 3, 4L, 1.5F, 2.5D, 12.34, 'hello', X'0102',
   DATE '2024-01-15', TIMESTAMP '2024-01-15 10:30:00',
   TIMESTAMP_NTZ '2024-01-15 10:30:00',
   array(1, 2, 3), named_struct('a', 1, 'b', 'x'), map('k', 1)),
  (2, false, -128Y, 32767S, 2147483647, -9223372036854775807L - 1L,
   float('nan'), double('-inf'), 99999999.99, '', X'',
   DATE '1970-01-01', TIMESTAMP '1969-12-31 23:59:59',
   TIMESTAMP_NTZ '9999-12-31 23:59:59',
   CAST(array() AS ARRAY<INT>), named_struct('a', CAST(NULL AS INT), 'b', 'ü'),
   CAST(map() AS MAP<STRING, INT>)),
  (3, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
   NULL, NULL, NULL, NULL, NULL, NULL)""")

print("Morel seed tables created: emp, dept, zoo", flush=True)

while True:
    time.sleep(3600)

# End seed.py
