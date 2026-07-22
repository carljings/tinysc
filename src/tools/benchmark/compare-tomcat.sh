#!/bin/bash

set -euo pipefail
export LC_ALL=C

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
IO_THREADS=${IO_THREADS:?Set IO_THREADS explicitly to the TinySC I/O thread count under test}
WORKER_QUEUE=${WORKER_QUEUE:?Set WORKER_QUEUE explicitly to the TinySC worker queue capacity under test}
TINYSC_PORT=${TINYSC_PORT:-18080}
TOMCAT_PORT=${TOMCAT_PORT:-18081}
MAX_CONNECTIONS=${MAX_CONNECTIONS:-1024}
LISTEN_BACKLOG=${LISTEN_BACKLOG:-256}
MIN_WORKERS=${MIN_WORKERS:-$((WORKERS < 2 ? WORKERS : 2))}
VERIFY_ACCESS_LOGS=${VERIFY_ACCESS_LOGS:-false}
JVM_OPTS=${JVM_OPTS:--Xms64m -Xmx256m}
AB_BIN=${AB_BIN:-$(command -v ab || true)}

# TinySC retains the active 64 MiB access log plus five backups. The fixed
# benchmark request line is below 256 bytes; use that conservative bound so
# exact line verification never silently crosses the retained range.
ACCESS_LOG_RETAINED_BYTES=$((6 * 64 * 1024 * 1024))
ACCESS_LOG_MAX_LINE_BYTES=256
ACCESS_LOG_MAX_VERIFIABLE_REQUESTS=$((ACCESS_LOG_RETAINED_BYTES / ACCESS_LOG_MAX_LINE_BYTES))

require_file() {
    if [[ ! -f "$1" ]]; then
        echo "Required file does not exist: $1" >&2
        exit 2
    fi
}

require_command() {
    if ! command -v "$1" >/dev/null 2>&1; then
        echo "Required command is not available: $1" >&2
        exit 2
    fi
}

require_positive_integer() {
    if [[ ! "$2" =~ ^[1-9][0-9]*$ ]]; then
        echo "$1 must be a positive integer: $2" >&2
        exit 2
    fi
}

require_boolean() {
    if [[ "$2" != "true" && "$2" != "false" ]]; then
        echo "$1 must be true or false: $2" >&2
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

require_file "$JAVA_HOME/bin/java"
require_file "$TINYSC_JAR"
require_file "$WAR"
require_file "$TOMCAT_HOME/bin/catalina.sh"
require_file "$TOMCAT_HOME/conf/server.xml"
require_file "$TOMCAT_HOME/lib/catalina.jar"
for command_name in awk curl find perl ps sed sort wc; do
    require_command "$command_name"
done
if [[ ! -d "/proc/$$/fd" ]]; then
    require_command lsof
fi
if [[ -z "$AB_BIN" || ! -x "$AB_BIN" ]]; then
    echo "ApacheBench is required; set AB_BIN to its executable" >&2
    exit 2
fi
for pair in "REPETITIONS:$REPETITIONS" "REQUESTS:$REQUESTS" \
        "WARMUP_REQUESTS:$WARMUP_REQUESTS" "CONCURRENCY:$CONCURRENCY" \
        "WORKERS:$WORKERS" "IO_THREADS:$IO_THREADS" "WORKER_QUEUE:$WORKER_QUEUE" \
        "MAX_CONNECTIONS:$MAX_CONNECTIONS" "LISTEN_BACKLOG:$LISTEN_BACKLOG" \
        "MIN_WORKERS:$MIN_WORKERS"; do
    require_positive_integer "${pair%%:*}" "${pair#*:}"
done
require_boolean VERIFY_ACCESS_LOGS "$VERIFY_ACCESS_LOGS"
require_port TINYSC_PORT "$TINYSC_PORT"
require_port TOMCAT_PORT "$TOMCAT_PORT"
if [[ "$TINYSC_PORT" == "$TOMCAT_PORT" ]]; then
    echo "TINYSC_PORT and TOMCAT_PORT must differ" >&2
    exit 2
fi
if (( MIN_WORKERS > WORKERS )); then
    echo "MIN_WORKERS must not exceed WORKERS" >&2
    exit 2
fi

EXPECTED_ACCESS_LOG_LINES=$((1 + WARMUP_REQUESTS + REQUESTS))
if [[ "$VERIFY_ACCESS_LOGS" == "true" ]] \
        && (( EXPECTED_ACCESS_LOG_LINES > ACCESS_LOG_MAX_VERIFIABLE_REQUESTS )); then
    echo "VERIFY_ACCESS_LOGS=true supports at most $ACCESS_LOG_MAX_VERIFIABLE_REQUESTS requests per process, including readiness and warm-up; requested $EXPECTED_ACCESS_LOG_LINES" >&2
    echo "Use fewer requests for the access-log completeness run, or set VERIFY_ACCESS_LOGS=false for a long throughput run" >&2
    exit 2
fi

if [[ -e "$OUTPUT_DIR" ]]; then
    if [[ ! -d "$OUTPUT_DIR" ]]; then
        echo "OUTPUT_DIR exists and is not a directory: $OUTPUT_DIR" >&2
        exit 2
    fi
    if [[ -n "$(find "$OUTPUT_DIR" -mindepth 1 -print -quit)" ]]; then
        echo "OUTPUT_DIR must be new or empty: $OUTPUT_DIR" >&2
        exit 2
    fi
else
    mkdir -p "$OUTPUT_DIR"
fi

CONFIGURED_TOMCAT_SERVER_XML="$OUTPUT_DIR/configured-tomcat-server.xml"
cp "$TOMCAT_HOME/conf/server.xml" "$CONFIGURED_TOMCAT_SERVER_XML"
perl -0pi -e "s/port=\"8080\" protocol=\"HTTP\\/1[.]1\"/port=\"$TOMCAT_PORT\" address=\"127.0.0.1\" protocol=\"HTTP\\/1.1\" maxThreads=\"$WORKERS\" minSpareThreads=\"$MIN_WORKERS\" maxConnections=\"$MAX_CONNECTIONS\" acceptCount=\"$LISTEN_BACKLOG\" maxKeepAliveRequests=\"-1\"/" \
    "$CONFIGURED_TOMCAT_SERVER_XML"
if ! grep -q "port=\"$TOMCAT_PORT\" address=\"127.0.0.1\" protocol=\"HTTP/1[.]1\" maxThreads=\"$WORKERS\" minSpareThreads=\"$MIN_WORKERS\" maxConnections=\"$MAX_CONNECTIONS\" acceptCount=\"$LISTEN_BACKLOG\" maxKeepAliveRequests=\"-1\"" \
        "$CONFIGURED_TOMCAT_SERVER_XML"; then
    echo "Could not configure the Tomcat HTTP connector" >&2
    exit 2
fi
if ! grep -q '<Valve className="org.apache.catalina.valves.AccessLogValve"' \
        "$CONFIGURED_TOMCAT_SERVER_XML"; then
    echo "Tomcat AccessLogValve must be enabled for a fair default comparison" >&2
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
        lsof -nP -p "$1" 2>/dev/null \
            | awk 'NR > 1 && $4 ~ /^[0-9]+/ { count++ } END { print count + 0 }'
    else
        echo "NA"
    fi
}

sha256_digest() {
    if command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | awk '{ print $1 }'
    elif command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{ print $1 }'
    else
        echo "Neither shasum nor sha256sum is available" >&2
        return 1
    fi
}

ab_first_value() {
    local label=$1
    local file=$2
    awk -F: -v label="$label" '
        $1 == label {
            value = $2
            sub(/^[[:space:]]*/, "", value)
            split(value, fields, /[[:space:]]+/)
            print fields[1]
            found = 1
            exit
        }
        END { if (!found) exit 1 }
    ' "$file"
}

ab_percentile() {
    local percentile=$1
    local file=$2
    awk -v percentile="$percentile" '$1 == percentile "%" { print $2; exit }' "$file"
}

require_nonnegative_integer() {
    if [[ ! "$2" =~ ^[0-9]+$ ]]; then
        echo "$1 is missing or is not a non-negative integer: $2" >&2
        return 1
    fi
}

validate_ab_result() {
    local phase=$1
    local file=$2
    local expected=$3
    AB_COMPLETE=$(ab_first_value "Complete requests" "$file")
    AB_FAILED=$(ab_first_value "Failed requests" "$file")
    AB_NON_2XX=$(ab_first_value "Non-2xx responses" "$file" 2>/dev/null || true)
    AB_KEEP_ALIVE=$(ab_first_value "Keep-Alive requests" "$file")
    AB_TOTAL_BYTES=$(ab_first_value "Total transferred" "$file")
    AB_NON_2XX=${AB_NON_2XX:-0}

    require_nonnegative_integer "$phase complete requests" "$AB_COMPLETE"
    require_nonnegative_integer "$phase failed requests" "$AB_FAILED"
    require_nonnegative_integer "$phase non-2xx responses" "$AB_NON_2XX"
    require_nonnegative_integer "$phase keep-alive requests" "$AB_KEEP_ALIVE"
    require_nonnegative_integer "$phase total transferred bytes" "$AB_TOTAL_BYTES"
    if [[ "$AB_COMPLETE" != "$expected" ]]; then
        echo "$phase completed $AB_COMPLETE requests; expected $expected" >&2
        return 1
    fi
    if [[ "$AB_FAILED" != "0" || "$AB_NON_2XX" != "0" ]]; then
        echo "$phase was not clean: failed=$AB_FAILED non2xx=$AB_NON_2XX" >&2
        return 1
    fi
    if [[ "$AB_KEEP_ALIVE" != "$AB_COMPLETE" ]]; then
        echo "$phase did not keep every request alive: keepalive=$AB_KEEP_ALIVE complete=$AB_COMPLETE" >&2
        return 1
    fi
}

prepare_tomcat_base() {
    local base=$1
    mkdir -p "$base/conf" "$base/logs" "$base/temp" "$base/webapps" "$base/work"
    cp -R "$TOMCAT_HOME/conf/." "$base/conf/"
    cp "$CONFIGURED_TOMCAT_SERVER_XML" "$base/conf/server.xml"
    cp "$WAR" "$base/webapps/probe.war"
}

count_access_log_lines() {
    local container=$1
    local base=$2
    local logs="$base/logs"
    local file lines total=0 files=0
    if [[ ! -d "$logs" ]]; then
        echo "Access-log directory does not exist: $logs" >&2
        return 1
    fi
    if [[ "$container" == "tinysc" ]]; then
        while IFS= read -r file; do
            lines=$(wc -l < "$file" | tr -d ' ')
            total=$((total + lines))
            files=$((files + 1))
        done < <(find "$logs" -maxdepth 1 -type f \
            \( -name 'access.log' -o -name 'access.log.[0-9]*' \) -print)
    else
        while IFS= read -r file; do
            lines=$(wc -l < "$file" | tr -d ' ')
            total=$((total + lines))
            files=$((files + 1))
        done < <(find "$logs" -maxdepth 1 -type f \
            -name 'localhost_access_log.*.txt' -print)
    fi
    if (( files == 0 )); then
        echo "No $container access-log files found under $logs" >&2
        return 1
    fi
    echo "$total"
}

record_environment() {
    local git_commit git_state
    git_commit=$(git -C "$PROJECT_ROOT" rev-parse HEAD 2>/dev/null || echo "unavailable")
    if [[ -z "$(git -C "$PROJECT_ROOT" status --porcelain 2>/dev/null || true)" ]]; then
        git_state=clean
    else
        git_state=dirty
    fi
    {
        echo "date=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
        echo "host=$(hostname)"
        echo "os=$(uname -a)"
        if [[ "$(uname -s)" == "Darwin" ]]; then
            echo "cpu=$(sysctl -n machdep.cpu.brand_string 2>/dev/null || true)"
            echo "logical_cpus=$(sysctl -n hw.ncpu 2>/dev/null || true)"
            echo "memory_bytes=$(sysctl -n hw.memsize 2>/dev/null || true)"
        elif [[ "$(uname -s)" == "Linux" ]]; then
            echo "cpu=$(awk -F: '/^model name/ { sub(/^[[:space:]]*/, "", $2); print $2; exit }' /proc/cpuinfo 2>/dev/null || true)"
            echo "logical_cpus=$(command -v nproc >/dev/null 2>&1 && nproc || true)"
            echo "memory_bytes=$(awk '/^MemTotal:/ { print $2 * 1024; exit }' /proc/meminfo 2>/dev/null || true)"
        fi
        echo "load=$(uptime 2>/dev/null || true)"
        echo "open_file_limit=$(ulimit -n)"
        echo "git_commit=$git_commit"
        echo "git_state=$git_state"
        echo "java_home=$JAVA_HOME"
        env -u JRE_HOME -u JAVA_OPTS -u JAVA_TOOL_OPTIONS -u _JAVA_OPTIONS \
            -u JDK_JAVA_OPTIONS "$JAVA_HOME/bin/java" -version 2>&1
        echo "jvm_opts=$JVM_OPTS"
        echo "sanitized_environment=JRE_HOME,JAVA_OPTS,JAVA_TOOL_OPTIONS,_JAVA_OPTIONS,JDK_JAVA_OPTIONS,CATALINA_OPTS,CATALINA_BASE,CATALINA_HOME,CLASSPATH,JAVA_ENDORSED_DIRS,JSSE_OPTS,LOGGING_CONFIG,LOGGING_MANAGER"
        echo "workers=$WORKERS"
        echo "min_workers=$MIN_WORKERS"
        echo "worker_queue=$WORKER_QUEUE"
        echo "io_threads=$IO_THREADS"
        echo "max_connections=$MAX_CONNECTIONS"
        echo "listen_backlog=$LISTEN_BACKLOG"
        echo "tinysc_port=$TINYSC_PORT"
        echo "tomcat_port=$TOMCAT_PORT"
        echo "access_logs_enabled=true"
        echo "concurrency=$CONCURRENCY"
        echo "requests=$REQUESTS"
        echo "warmup_requests=$WARMUP_REQUESTS"
        echo "repetitions=$REPETITIONS"
        echo "verify_access_logs=$VERIFY_ACCESS_LOGS"
        echo "expected_access_log_lines_per_process=$EXPECTED_ACCESS_LOG_LINES"
        echo "benchmark_base_mode=fresh_base_per_process"
        echo "startup_metric=cold_application_ready_ms"
        echo "startup_definition=launcher invocation to first expected HTTP response with a fresh container deployment base; OS page cache is not cleared"
        echo "resource_metric=warmed_idle snapshot after warm-up and one second idle; not an under-load peak"
        echo "ab_version=$("$AB_BIN" -V 2>&1 | sed -n '1p')"
        echo "curl_version=$(curl --version 2>&1 | sed -n '1p')"
        echo "tinysc_jar=$TINYSC_JAR"
        echo "tinysc_jar_sha256=$(sha256_digest "$TINYSC_JAR")"
        echo "tomcat_home=$TOMCAT_HOME"
        echo "tomcat_catalina_sha256=$(sha256_digest "$TOMCAT_HOME/lib/catalina.jar")"
        echo "tomcat_source_server_xml_sha256=$(sha256_digest "$TOMCAT_HOME/conf/server.xml")"
        echo "tomcat_configured_server_xml_sha256=$(sha256_digest "$CONFIGURED_TOMCAT_SERVER_XML")"
        echo "war=$WAR"
        echo "war_sha256=$(sha256_digest "$WAR")"
        echo "benchmark_script_sha256=$(sha256_digest "$PROJECT_ROOT/src/tools/benchmark/compare-tomcat.sh")"
    } > "$OUTPUT_DIR/environment.txt"
}

start_tinysc() {
    local log=$1
    local base=$2
    env -u JRE_HOME -u JAVA_OPTS -u JAVA_TOOL_OPTIONS -u _JAVA_OPTIONS \
        -u JDK_JAVA_OPTIONS -u CATALINA_OPTS -u CATALINA_BASE -u CATALINA_HOME \
        -u CLASSPATH -u JAVA_ENDORSED_DIRS -u JSSE_OPTS -u LOGGING_CONFIG \
        -u LOGGING_MANAGER \
        "$JAVA_HOME/bin/java" "${JVM_ARGS[@]}" -jar "$TINYSC_JAR" start \
        --war "$WAR" --context-path /probe --bind 127.0.0.1 --port "$TINYSC_PORT" \
        --base "$base" --io-threads "$IO_THREADS" --workers "$WORKERS" \
        --min-workers "$MIN_WORKERS" --worker-queue "$WORKER_QUEUE" \
        --max-connections "$MAX_CONNECTIONS" --access-log true \
        > "$log" 2>&1 &
    CURRENT_PID=$!
}

start_tomcat() {
    local log=$1
    local base=$2
    env -u JRE_HOME -u JAVA_OPTS -u JAVA_TOOL_OPTIONS -u _JAVA_OPTIONS \
        -u JDK_JAVA_OPTIONS -u CATALINA_OPTS -u CATALINA_BASE -u CATALINA_HOME \
        -u CLASSPATH -u JAVA_ENDORSED_DIRS -u JSSE_OPTS -u LOGGING_CONFIG \
        -u LOGGING_MANAGER \
        JAVA_HOME="$JAVA_HOME" CATALINA_HOME="$TOMCAT_HOME" CATALINA_BASE="$base" \
        CATALINA_OPTS="$JVM_OPTS" "$TOMCAT_HOME/bin/catalina.sh" run \
        > "$log" 2>&1 &
    CURRENT_PID=$!
}

run_once() {
    local container=$1
    local run=$2
    local port base log url response_file header_file response_sha start_ms ready_ms
    local warmup_ab_file warmup_progress_file ab_file ab_progress_file status
    local rss threads fds rps complete failed non_2xx keep_alive total_bytes
    local p50 p95 p99 access_log_lines
    log="$OUTPUT_DIR/${container}-run-${run}.log"
    response_file="$OUTPUT_DIR/${container}-run-${run}-response.bin"
    header_file="$OUTPUT_DIR/${container}-run-${run}-headers.txt"
    warmup_ab_file="$OUTPUT_DIR/${container}-run-${run}-warmup-ab.txt"
    warmup_progress_file="$OUTPUT_DIR/${container}-run-${run}-warmup-ab-progress.txt"
    ab_file="$OUTPUT_DIR/${container}-run-${run}-ab.txt"
    ab_progress_file="$OUTPUT_DIR/${container}-run-${run}-ab-progress.txt"
    base="$OUTPUT_DIR/${container}-run-${run}-base"
    if [[ -e "$base" ]]; then
        echo "Per-run base already exists: $base" >&2
        exit 2
    fi
    if [[ "$container" == "tinysc" ]]; then
        port=$TINYSC_PORT
        mkdir -p "$base"
    else
        port=$TOMCAT_PORT
        prepare_tomcat_base "$base"
    fi
    url="http://127.0.0.1:${port}/probe/benchmark"
    echo "Starting cold-base $container run $run/$REPETITIONS"
    if curl --noproxy '*' -fsS --max-time 1 "$url" >/dev/null 2>&1; then
        echo "Port $port already serves HTTP; refusing an ambiguous benchmark" >&2
        exit 2
    fi

    start_ms=$(now_millis)
    if [[ "$container" == "tinysc" ]]; then
        start_tinysc "$log" "$base"
    else
        start_tomcat "$log" "$base"
    fi
    local pid=$CURRENT_PID
    if ! wait_until_ready "$pid" "$url" "$response_file" "$header_file"; then
        echo "$container run $run did not become ready; see $log" >&2
        exit 1
    fi
    ready_ms=$(( $(now_millis) - start_ms ))
    status=$(awk '$1 ~ /^HTTP\// { status=$2 } END { print status }' "$header_file")
    if [[ "$status" != "200" ]]; then
        echo "$container run $run returned readiness status $status; expected 200" >&2
        exit 1
    fi
    if ! grep -qi '^X-TinySC-Filter: applied' "$header_file"; then
        echo "$container run $run did not execute the Probe Filter" >&2
        exit 1
    fi
    response_sha=$(sha256_digest "$response_file")
    if [[ -z "$EXPECTED_RESPONSE_SHA" ]]; then
        EXPECTED_RESPONSE_SHA=$response_sha
    elif [[ "$response_sha" != "$EXPECTED_RESPONSE_SHA" ]]; then
        echo "$container run $run returned a different response body" >&2
        exit 1
    fi

    if ! "$AB_BIN" -k -n "$WARMUP_REQUESTS" -c "$CONCURRENCY" "$url" \
            > "$warmup_ab_file" 2> "$warmup_progress_file"; then
        echo "$container run $run warm-up failed; see $warmup_ab_file" >&2
        exit 1
    fi
    validate_ab_result "$container run $run warm-up" "$warmup_ab_file" \
        "$WARMUP_REQUESTS"
    sleep 1
    rss=$(process_rss_kb "$pid")
    threads=$(process_threads "$pid")
    fds=$(process_fds "$pid")
    if ! "$AB_BIN" -k -n "$REQUESTS" -c "$CONCURRENCY" "$url" \
            > "$ab_file" 2> "$ab_progress_file"; then
        echo "$container run $run measured load failed; see $ab_file" >&2
        exit 1
    fi
    validate_ab_result "$container run $run measured load" "$ab_file" "$REQUESTS"
    complete=$AB_COMPLETE
    failed=$AB_FAILED
    non_2xx=$AB_NON_2XX
    keep_alive=$AB_KEEP_ALIVE
    total_bytes=$AB_TOTAL_BYTES
    rps=$(ab_first_value "Requests per second" "$ab_file")
    p50=$(ab_percentile 50 "$ab_file")
    p95=$(ab_percentile 95 "$ab_file")
    p99=$(ab_percentile 99 "$ab_file")

    stop_process "$pid"
    CURRENT_PID=""
    access_log_lines=NA
    if [[ "$VERIFY_ACCESS_LOGS" == "true" ]]; then
        access_log_lines=$(count_access_log_lines "$container" "$base")
        if [[ "$access_log_lines" != "$EXPECTED_ACCESS_LOG_LINES" ]]; then
            echo "$container run $run access-log mismatch: lines=$access_log_lines expected=$EXPECTED_ACCESS_LOG_LINES" >&2
            exit 1
        fi
    fi

    echo "$container,$run,$ready_ms,$rss,$threads,$fds,$rps,$complete,$failed,$non_2xx,$keep_alive,$total_bytes,$p50,$p95,$p99,$access_log_lines" \
        >> "$OUTPUT_DIR/results.csv"
    echo "Completed $container run $run/$REPETITIONS: coldReady=${ready_ms}ms warmedIdleRss=${rss}KB rps=$rps accessLogLines=$access_log_lines"
}

column_range() {
    local container=$1
    local column=$2
    local values_file="$OUTPUT_DIR/.range-${container}-${column}"
    awk -F, -v container="$container" -v column="$column" \
        '$1 == container { print $column }' "$OUTPUT_DIR/results.csv" \
        | sort -n > "$values_file"
    local count middle left right minimum median maximum
    count=$(wc -l < "$values_file" | tr -d ' ')
    if (( count == 0 )); then
        echo "No values for $container column $column" >&2
        rm -f "$values_file"
        return 1
    fi
    minimum=$(sed -n '1p' "$values_file")
    maximum=$(sed -n "${count}p" "$values_file")
    if (( count % 2 == 1 )); then
        middle=$(( count / 2 + 1 ))
        median=$(sed -n "${middle}p" "$values_file")
    else
        left=$(sed -n "$(( count / 2 ))p" "$values_file")
        right=$(sed -n "$(( count / 2 + 1 ))p" "$values_file")
        median=$(awk -v left="$left" -v right="$right" \
            'BEGIN { printf "%.2f\n", (left + right) / 2 }'
        )
    fi
    rm "$values_file"
    echo "$minimum,$median,$maximum"
}

record_environment
echo "container,run,cold_application_ready_ms,warmed_idle_rss_kb,warmed_idle_threads,warmed_idle_fds,requests_per_second,complete_requests,failed_requests,non_2xx_responses,keep_alive_requests,total_transferred_bytes,p50_ms,p95_ms,p99_ms,access_log_lines" \
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

{
    echo "container,cold_application_ready_ms_min,cold_application_ready_ms_median,cold_application_ready_ms_max,warmed_idle_rss_kb_min,warmed_idle_rss_kb_median,warmed_idle_rss_kb_max,warmed_idle_threads_min,warmed_idle_threads_median,warmed_idle_threads_max,warmed_idle_fds_min,warmed_idle_fds_median,warmed_idle_fds_max,requests_per_second_min,requests_per_second_median,requests_per_second_max,complete_requests_min,complete_requests_median,complete_requests_max,keep_alive_requests_min,keep_alive_requests_median,keep_alive_requests_max,total_transferred_bytes_min,total_transferred_bytes_median,total_transferred_bytes_max,p50_ms_min,p50_ms_median,p50_ms_max,p95_ms_min,p95_ms_median,p95_ms_max,p99_ms_min,p99_ms_median,p99_ms_max,total_failed_requests,total_non_2xx_responses"
    for container in tinysc tomcat; do
        printf '%s' "$container"
        for column in 3 4 5 6 7 8 11 12 13 14 15; do
            printf ',%s' "$(column_range "$container" "$column")"
        done
        printf ',%s,%s\n' \
            "$(awk -F, -v container="$container" '$1 == container { total += $9 } END { print total + 0 }' "$OUTPUT_DIR/results.csv")" \
            "$(awk -F, -v container="$container" '$1 == container { total += $10 } END { print total + 0 }' "$OUTPUT_DIR/results.csv")"
    done
} > "$OUTPUT_DIR/summary.csv"

trap - EXIT INT TERM
echo "Raw benchmark evidence: $OUTPUT_DIR"
echo "Summary rows: $OUTPUT_DIR/results.csv"
echo "Min/median/max summary: $OUTPUT_DIR/summary.csv"
