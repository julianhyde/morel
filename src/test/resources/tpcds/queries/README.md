<!--
{% comment %}
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
{% endcomment %}
-->
# TPC-DS queries

The 99 TPC-DS queries, in Spark SQL's dialect, with their parameters
substituted, for [#502](https://github.com/hydromatic/morel/issues/502).
Queries 14, 23, 24 and 39 have two parts each (`q14a.sql`,
`q14b.sql`, ...), so there are 103 files.

They are copied unchanged from Apache Spark,
`sql/core/src/test/resources/tpcds/` at tag `v4.0.0` (commit
`fa33ea000a0bda9e5a3fa1af98e8e85b8cc5e4d4`), which is licensed under
the Apache License, Version 2.0; the files carry no header of their
own, and none has been added, so that they can be compared with
Spark's. They are derived from TPC-DS, and running them is not a
TPC-DS benchmark.

The tables they query are in `../schema.sql`.
