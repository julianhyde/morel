#!/bin/bash
#
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
# Generates the TPC-DS data set (derived from TPC-DS; not a TPC-DS
# benchmark) for hydromatic/morel#502: one TABLE.csv.gz per table of
# schema.sql, in the format of Morel's file reader, and a VERSION file.
# Runs TpcdsData (in src/test/java) through Maven, so it needs nothing
# but the build. The output is deterministic, and is not regenerated if
# the directory's VERSION file says it holds this generator and scale.
#
# Usage: generate.sh [OPTIONS]
#
#   --dir DIR       Output directory
#                   (default: ~/.cache/morel/tpcds/sf<SCALE>).
#   --scale SCALE   Scale factor (default: 0.01, about 10MB of data).
#   --force         Generate even if the directory is up to date.
#   --help          Show this message and exit.
#
# Prints the directory on stdout; everything else goes to stderr, so
#
#   tpcds=$(src/test/resources/tpcds/generate.sh)
#
# is enough.

set -euo pipefail

scale=0.01
dir=
force=

usage() {
  sed -n '/^# Usage:/,/^$/p' "$0" | sed 's/^# \{0,1\}//' >&2
}

while [ $# -gt 0 ]; do
  case "$1" in
    (--dir) dir=$2; shift 2 ;;
    (--scale) scale=$2; shift 2 ;;
    (--force) force=1; shift ;;
    (--help) usage; exit 0 ;;
    (*) echo "generate.sh: unknown option '$1'" >&2; usage; exit 2 ;;
  esac
done

if [ -z "$dir" ]; then
  dir="$HOME/.cache/morel/tpcds/sf$scale"
fi

# The generator; must match TpcdsData.GENERATOR and pom.xml.
generator=io.trino.tpcds:tpcds:1.4

if [ -z "$force" ] && [ -f "$dir/VERSION" ] \
    && grep -qx "generator=$generator" "$dir/VERSION" \
    && grep -qx "scale=$scale" "$dir/VERSION"; then
  echo "generate.sh: $dir is up to date" >&2
  echo "$dir"
  exit 0
fi

root=$(cd "$(dirname "$0")/../../../.." && pwd)
mkdir -p "$dir"
rm -f "$dir/VERSION"
echo "generate.sh: generating scale $scale into $dir" >&2
(cd "$root" && ./mvnw -q -Dmaven.gitcommitid.skip=true test-compile \
    exec:java -Dexec.classpathScope=test \
    -Dexec.mainClass=net.hydromatic.morel.tpcds.TpcdsData \
    "-Dexec.args=$dir $scale") >&2
echo "$dir"

# End generate.sh
