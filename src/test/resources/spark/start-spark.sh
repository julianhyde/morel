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
# Starts a Spark Connect server in a Docker container, in ANSI mode
# with the UTC time zone, seeds it with the tables that seed.py (in
# this directory) creates, and prints its URI (for example
# "sc://localhost:15002") on stdout once it accepts connections.
# Everything else goes to stderr, so
#
#   export SPARK_REMOTE=$(src/test/resources/spark/start-spark.sh)
#
# is enough to point the tests at it.
#
# Usage: start-spark.sh [OPTIONS]
#
#   --reuse         Use the container if it exists, starting it if it
#                   is stopped (default).
#   --new           Remove the container if it exists, and create a
#                   fresh one.
#   --stop          Stop and remove the container, and exit.
#   --name NAME     Container name (default: morel-spark).
#   --port PORT     Host port for Spark Connect (default: 15002).
#   --image IMAGE   Docker image (default: apache/spark:4.0.0).
#   --timeout SECS  How long to wait for the server (default: 120).
#   --help          Show this message and exit.

set -euo pipefail

name=morel-spark
port=15002
image=apache/spark:4.0.0
timeout=120
mode=reuse

usage() {
  sed -n '/^# Usage:/,/^$/p' "$0" | sed 's/^# \{0,1\}//' >&2
}

while [ $# -gt 0 ]; do
  case "$1" in
    (--reuse) mode=reuse; shift ;;
    (--new) mode=new; shift ;;
    (--stop) mode=stop; shift ;;
    (--name) name=$2; shift 2 ;;
    (--port) port=$2; shift 2 ;;
    (--image) image=$2; shift 2 ;;
    (--timeout) timeout=$2; shift 2 ;;
    (--help) usage; exit 0 ;;
    (*) echo "start-spark.sh: unknown option '$1'" >&2; usage; exit 2 ;;
  esac
done

log() {
  echo "start-spark.sh: $*" >&2
}

if ! docker info >/dev/null 2>&1; then
  log "the Docker daemon is not running"
  exit 1
fi

# Container state: "running", "exited", "created", etc., or empty if
# there is no container with this name.
state() {
  docker inspect --format '{{.State.Status}}' "$name" 2>/dev/null || true
}

case "$mode" in
  (stop)
    if [ -n "$(state)" ]; then
      docker rm -f "$name" >/dev/null
      log "removed container $name"
    else
      log "no container named $name"
    fi
    exit 0
    ;;
  (new)
    if [ -n "$(state)" ]; then
      docker rm -f "$name" >/dev/null
      log "removed container $name"
    fi
    ;;
esac

s=$(state)
if [ "$s" = running ]; then
  log "container $name is already running"
elif [ -n "$s" ]; then
  log "starting container $name (was $s)"
  docker start "$name" >/dev/null
else
  log "creating container $name from $image on port $port"
  # seed.py is the driver program; the Connect server runs inside it
  # (spark.plugins), so clients see the tables it creates.
  dir=$(cd "$(dirname "$0")" && pwd)
  docker run -d --name "$name" -p "$port:15002" \
    -v "$dir:/morel:ro" "$image" \
    /opt/spark/bin/spark-submit \
    --conf spark.plugins=org.apache.spark.sql.connect.SparkConnectPlugin \
    --conf spark.connect.grpc.binding.port=15002 \
    --conf spark.sql.ansi.enabled=true \
    --conf spark.sql.session.timeZone=UTC \
    /morel/seed.py >/dev/null
fi

# Wait until the driver logs that the seed tables exist, which is
# after the server started listening. A restarted container appends to
# its log, so count only lines since the last container start.
started=$(docker inspect --format '{{.State.StartedAt}}' "$name")
deadline=$((SECONDS + timeout))
ready() {
  # Not "grep -q": it would close the pipe early, and under pipefail
  # the pipeline would fail even when the line was found.
  [ "$(docker logs --since "$started" "$name" 2>&1 \
      | grep -c "Morel seed tables created")" -gt 0 ]
}
until ready; do
  if [ "$(state)" != running ]; then
    log "container $name exited; last log lines:"
    docker logs --tail 20 "$name" >&2 || true
    exit 1
  fi
  if [ $SECONDS -ge $deadline ]; then
    log "server did not start within $timeout seconds"
    exit 1
  fi
  sleep 1
done

log "Spark Connect server is ready"
echo "sc://localhost:$port"

# End start-spark.sh
