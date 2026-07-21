#!/bin/bash

set -euo pipefail

PROJECT_ROOT=$(cd "$(dirname "$0")/../../.." && pwd)
JAVA_HOME=${JAVA_HOME:?Set JAVA_HOME to the JDK 8 installation used by both containers}
TOMCAT_HOME=${TOMCAT_HOME:?Set TOMCAT_HOME to an unpacked Tomcat 8.5 distribution}
TINYSC_JAR=${TINYSC_JAR:-$PROJECT_ROOT/src/tinysc-launcher/target/tinysc-1.0.0-alpha-SNAPSHOT.jar}
WAR=${WAR:-$PROJECT_ROOT/src/tinysc-testapp-javax/target/probe-javax31.war}
OUTPUT_DIR=${OUTPUT_DIR:-$PROJECT_ROOT/work/benchmark/results/$(date +%Y%m%d-%H%M%S)}
REPETITIONS=${REPETITIONS:-5}
REQUESTS=${REQUESTS:-20000}
WARMUP_REQUESTS=${WARMUP_REQUESTS:-2000}
CONCURRENCY=${CONCURRENCY:-32}
WORKERS=${WORKERS:-32}
IO_THREADS=${IO_THREADS:-2}
TINYSC_PORT=${TINYSC_PORT:-18080}
TOMCAT_PORT=${TOMCAT_PORT:-18081}
JVM_OPTS=${JVM_OPTS:--Xms64m -Xmx256m}
AB_BIN=${AB_BIN:-$(command -v ab || true)}

require_file() {
    if [[ ! -f "$1" ]]; then
        echo "Required file does not exist: $1" >&2
        exit 2
    fi
}

require_positive_integer() {
    if [[ ! "$2" =~ ^[1-9][0-9]*$ ]]; then
        echo "$1 must be a positive integer: $2" >&2
        exit 2
    fi
}

require_port() {
    require_positive_integer "$1" "$2"
    if (( "$2" > 65535 )); then
        echo "$1 must not exceed 65535: $2" >&2
        exit 2
    fi
}

require_file "$TINYSC_JAR"
require_file "$WAR"
require_file "$TOMCAT_HOME/bin/catalina.sh"
require_file "$TOMCAT_HOME/conf/server.xml"
if [[ -z "$AB_BIN" || ! -x "$AB_BIN" ]]; then
    echo "ApacheBench is required; set AB_BIN to its executable" >&2
    exit 2
fi
for pair in "REPETITIONS:$REPETITIONS" "REQUESTS:$REQUESTS" \
        "WARMUP_REQUESTS:$WARMUP_REQUESTS" "CONCURRENCY:$CONCURRENCY" \
        "WORKERS:$WORKERS" "IO_THREADS:$IO_THREADS"; do
    require_positive_integer "${pair%%:*}" "${pair#*:}"
done
require_port TINYSC_PORT "$TINYSC_PORT"
require_port TOMCAT_PORT "$TOMCAT_PORT"
if [[ "$TINYSC_PORT" == "$TOMCAT_PORT" ]]; then
    echo "TINYSC_PORT and TOMCAT_PORT must differ" >&2
    exit 2
fi

mkdir -p "$OUTPUT_DIR"
TINYSC_BASE="$OUTPUT_DIR/tinysc-base"
TOMCAT_BASE="$OUTPUT_DIR/tomcat-base"
mkdir -p "$TINYSC_BASE" "$TOMCAT_BASE/conf" "$TOMCAT_BASE/logs" \
    "$TOMCAT_BASE/temp" "$TOMCAT_BASE/webapps" "$TOMCAT_BASE/work"
cp -R "$TOMCAT_HOME/conf/." "$TOMCAT_BASE/conf/"
cp "$WAR" "$TOMCAT_BASE/webapps/probe.war"
perl -0pi -e "s/port=\"8080\" protocol=\"HTTP\\/1[.]1\"/port=\"$TOMCAT_PORT\" protocol=\"HTTP\\/1.1\" maxThreads=\"$WORKERS\"/" \
    "$TOMCAT_BASE/conf/server.xml"
if ! grep -q "port=\"$TOMCAT_PORT\" protocol=\"HTTP/1[.]1\" maxThreads=\"$WORKERS\"" \
        "$TOMCAT_BASE/conf/server.xml"; then
    echo "Could not configure the Tomcat HTTP connector" >&2
    exit 2
fi

read -r -a JVM_ARGS <<< "$JVM_OPTS"
CURRENT_PID=""
EXPECTED_RESPONSE_SHA=""

stop_process() {
    local pid=${1:-}
    if [[ -z "$pid" ]] || ! kill -0 "$pid" 2>/dev/null; then
        return
    fi
    kill "$pid" 2>/dev/null || true
    local attempt
    for attempt in $(seq 1 300); do
        if ! kill -0 "$pid" 2>/dev/null; then
            wait "$pid" 2>/dev/null || true
            return
        fi
        sleep 0.1
    done
    echo "Process $pid did not stop after SIGTERM; sending SIGKILL" >&2
    kill -KILL "$pid" 2>/dev/null || true
    wait "$pid" 2>/dev/null || true
}

cleanup() {
    stop_process "$CURRENT_PID"
}
trap cleanup EXIT INT TERM

now_millis() {
    perl -MTime::HiRes=time -e 'printf "%.0f\n", time * 1000'
}

wait_until_ready() {
    local pid=$1
    local url=$2
    local response_file=$3
    local header_file=$4
    local attempt
    for attempt in $(seq 1 3000); do
        if ! kill -0 "$pid" 2>/dev/null; then
            return 1
        fi
        if curl --noproxy '*' -fsS --max-time 1 -D "$header_file" "$url" \
                -o "$response_file" 2>/dev/null; then
            return 0
        fi
        sleep 0.01
    done
    return 1
}

process_rss_kb() {
    ps -o rss= -p "$1" | tr -d ' '
}

process_threads() {
    if [[ -d "/proc/$1/task" ]]; then
        find "/proc/$1/task" -mindepth 1 -maxdepth 1 -type d | wc -l | tr -d ' '
    else
        ps -M -p "$1" | awk 'NR > 1 { count++ } END { print count + 0 }'
    fi
}

process_fds() {
    if [[ -d "/proc/$1/fd" ]]; then
        find "/proc/$1/fd" -mindepth 1 -maxdepth 1 | wc -l | tr -d ' '
    elif command -v lsof >/dev/null 2>&1; then
        lsof -p "$1" 2>/dev/null | awk 'NR > 1 { count++ } END { print count + 0 }'
    else
        echo "NA"
    fi
}

ab_value() {
    local label=$1
    local file=$2
    awk -v label="$label" '$0 ~ label { print $4; exit }' "$file"
}

ab_percentile() {
    local percentile=$1
    local file=$2
    awk -v percentile="$percentile" '$1 == percentile "%" { print $2; exit }' "$file"
}

record_environment() {
    {
        echo "date=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
        echo "host=$(hostname)"
        echo "os=$(uname -a)"
        if command -v sysctl >/dev/null 2>&1; then
            echo "cpu=$(sysctl -n machdep.cpu.brand_string 2>/dev/null || true)"
            echo "logical_cpus=$(sysctl -n hw.ncpu 2>/dev/null || true)"
            echo "memory_bytes=$(sysctl -n hw.memsize 2>/dev/null || true)"
        elif command -v nproc >/dev/null 2>&1; then
            echo "logical_cpus=$(nproc)"
        fi
        echo "java_home=$JAVA_HOME"
        "$JAVA_HOME/bin/java" -version 2>&1
        echo "jvm_opts=$JVM_OPTS"
        echo "workers=$WORKERS"
        echo "io_threads=$IO_THREADS"
        echo "concurrency=$CONCURRENCY"
        echo "requests=$REQUESTS"
        echo "warmup_requests=$WARMUP_REQUESTS"
        echo "repetitions=$REPETITIONS"
        echo "tinysc_jar=$TINYSC_JAR"
        shasum -a 256 "$TINYSC_JAR"
        echo "tomcat_home=$TOMCAT_HOME"
        shasum -a 256 "$TOMCAT_HOME/lib/catalina.jar"
        echo "war=$WAR"
        shasum -a 256 "$WAR"
    } > "$OUTPUT_DIR/environment.txt"
}

start_tinysc() {
    local log=$1
    "$JAVA_HOME/bin/java" "${JVM_ARGS[@]}" -jar "$TINYSC_JAR" start \
        --war "$WAR" --context-path /probe --bind 127.0.0.1 --port "$TINYSC_PORT" \
        --base "$TINYSC_BASE" --io-threads "$IO_THREADS" --workers "$WORKERS" \
        --worker-queue 1024 \
        > "$log" 2>&1 &
    CURRENT_PID=$!
}

start_tomcat() {
    local log=$1
    JAVA_HOME="$JAVA_HOME" CATALINA_HOME="$TOMCAT_HOME" CATALINA_BASE="$TOMCAT_BASE" \
        CATALINA_OPTS="$JVM_OPTS" "$TOMCAT_HOME/bin/catalina.sh" run \
        > "$log" 2>&1 &
    CURRENT_PID=$!
}

run_once() {
    local container=$1
    local run=$2
    local port log url response_file header_file response_sha start_ms ready_ms ab_file
    local rss threads fds rps failed p50 p95 p99
    log="$OUTPUT_DIR/${container}-run-${run}.log"
    response_file="$OUTPUT_DIR/${container}-run-${run}-response.bin"
    header_file="$OUTPUT_DIR/${container}-run-${run}-headers.txt"
    ab_file="$OUTPUT_DIR/${container}-run-${run}-ab.txt"
    if [[ "$container" == "tinysc" ]]; then
        port=$TINYSC_PORT
    else
        port=$TOMCAT_PORT
    fi
    url="http://127.0.0.1:${port}/probe/benchmark"
    echo "Starting $container run $run/$REPETITIONS"
    if curl --noproxy '*' -fsS --max-time 1 "$url" >/dev/null 2>&1; then
        echo "Port $port already serves HTTP; refusing an ambiguous benchmark" >&2
        exit 2
    fi

    start_ms=$(now_millis)
    if [[ "$container" == "tinysc" ]]; then
        start_tinysc "$log"
    else
        start_tomcat "$log"
    fi
    local pid=$CURRENT_PID
    if ! wait_until_ready "$pid" "$url" "$response_file" "$header_file"; then
        echo "$container run $run did not become ready; see $log" >&2
        exit 1
    fi
    ready_ms=$(( $(now_millis) - start_ms ))
    if ! grep -qi '^X-TinySC-Filter: applied' "$header_file"; then
        echo "$container run $run did not execute the Probe Filter" >&2
        exit 1
    fi
    response_sha=$(shasum -a 256 "$response_file" | awk '{ print $1 }')
    if [[ -z "$EXPECTED_RESPONSE_SHA" ]]; then
        EXPECTED_RESPONSE_SHA=$response_sha
    elif [[ "$response_sha" != "$EXPECTED_RESPONSE_SHA" ]]; then
        echo "$container run $run returned a different response body" >&2
        exit 1
    fi

    "$AB_BIN" -k -n "$WARMUP_REQUESTS" -c "$CONCURRENCY" "$url" \
        >/dev/null 2>&1
    sleep 1
    rss=$(process_rss_kb "$pid")
    threads=$(process_threads "$pid")
    fds=$(process_fds "$pid")
    "$AB_BIN" -k -n "$REQUESTS" -c "$CONCURRENCY" "$url" \
        > "$ab_file" 2> "$OUTPUT_DIR/${container}-run-${run}-ab-progress.txt"
    rps=$(ab_value "Requests per second:" "$ab_file")
    failed=$(awk '/Failed requests:/ { print $3; exit }' "$ab_file")
    p50=$(ab_percentile 50 "$ab_file")
    p95=$(ab_percentile 95 "$ab_file")
    p99=$(ab_percentile 99 "$ab_file")
    echo "$container,$run,$ready_ms,$rss,$threads,$fds,$rps,$failed,$p50,$p95,$p99" \
        >> "$OUTPUT_DIR/results.csv"
    echo "Completed $container run $run/$REPETITIONS: ready=${ready_ms}ms rss=${rss}KB rps=$rps"

    stop_process "$pid"
    CURRENT_PID=""
}

median_column() {
    local container=$1
    local column=$2
    local values_file="$OUTPUT_DIR/.median-${container}-${column}"
    awk -F, -v container="$container" -v column="$column" \
        '$1 == container { print $column }' "$OUTPUT_DIR/results.csv" \
        | sort -n > "$values_file"
    local count middle left right
    count=$(wc -l < "$values_file" | tr -d ' ')
    if (( count % 2 == 1 )); then
        middle=$(( count / 2 + 1 ))
        sed -n "${middle}p" "$values_file"
    else
        left=$(sed -n "$(( count / 2 ))p" "$values_file")
        right=$(sed -n "$(( count / 2 + 1 ))p" "$values_file")
        awk -v left="$left" -v right="$right" \
            'BEGIN { printf "%.2f\n", (left + right) / 2 }'
    fi
    rm "$values_file"
}

record_environment
echo "container,run,application_ready_ms,rss_kb,threads,fds,requests_per_second,failed_requests,p50_ms,p95_ms,p99_ms" \
    > "$OUTPUT_DIR/results.csv"

for run in $(seq 1 "$REPETITIONS"); do
    if (( run % 2 == 1 )); then
        run_once tinysc "$run"
        run_once tomcat "$run"
    else
        run_once tomcat "$run"
        run_once tinysc "$run"
    fi
done

echo "container,application_ready_ms,rss_kb,threads,fds,requests_per_second,p50_ms,p95_ms,p99_ms,total_failed_requests" \
    > "$OUTPUT_DIR/summary.csv"
for container in tinysc tomcat; do
    echo "$container,$(median_column "$container" 3),$(median_column "$container" 4),$(median_column "$container" 5),$(median_column "$container" 6),$(median_column "$container" 7),$(median_column "$container" 9),$(median_column "$container" 10),$(median_column "$container" 11),$(awk -F, -v container="$container" '$1 == container { total += $8 } END { print total + 0 }' "$OUTPUT_DIR/results.csv")" \
        >> "$OUTPUT_DIR/summary.csv"
done

trap - EXIT INT TERM
echo "Raw benchmark evidence: $OUTPUT_DIR"
echo "Summary rows: $OUTPUT_DIR/results.csv"
echo "Median summary: $OUTPUT_DIR/summary.csv"
