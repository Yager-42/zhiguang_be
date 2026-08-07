#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

COMMAND="${1:-smoke}"
BASE_URL="${BASE_URL:-http://host.docker.internal:8080}"
SUT_BASE_URL="${SUT_BASE_URL:-http://localhost:8080}"
K6_IMAGE="${K6_IMAGE:-grafana/k6:latest}"
K6_CPUS="${K6_CPUS:-2}"
K6_MEM="${K6_MEM:-1024m}"
RATE="${RATE:-20}"
HOLD="${HOLD:-1m}"
WARMUP_SECONDS="${WARMUP_SECONDS:-10}"
VUS="${VUS:-$RATE}"
PREALLOCATED_VUS="${PREALLOCATED_VUS:-$VUS}"
ROUNDS="${ROUNDS:-5}"
DRAIN_WRITES="${DRAIN_WRITES:-100}"
TOKEN_POOL="${TOKEN_POOL:-32}"
SAMPLE_INTERVAL_SECONDS="${SAMPLE_INTERVAL_SECONDS:-10}"
COLLECT_KAFKA_LAG="${COLLECT_KAFKA_LAG:-0}"
RAW_JSON="${RAW_JSON:-0}"
RESET_EVENT_GROUP_OFFSETS="${RESET_EVENT_GROUP_OFFSETS:-1}"
RESET_COMMENT_DATA="${RESET_COMMENT_DATA:-1}"
ALLOW_COLD_MEASUREMENT="${ALLOW_COLD_MEASUREMENT:-0}"
EVENT_TOPIC="${EVENT_TOPIC:-comment-events}"
EVENT_CONSUMER_GROUPS="${EVENT_CONSUMER_GROUPS:-comment-cache-consumer comment-counter-consumer comment-feedback-consumer comment-reward-consumer}"
IDLE_TIMEOUT_SECONDS="${IDLE_TIMEOUT_SECONDS:-300}"
IDLE_STABLE_SAMPLES="${IDLE_STABLE_SAMPLES:-3}"
IDLE_SAMPLE_INTERVAL_SECONDS="${IDLE_SAMPLE_INTERVAL_SECONDS:-2}"
RESULT_ROOT="${RESULT_ROOT:-results/comment-throughput}"
VARIANT="${VARIANT:-candidate}"
SUT_SAMPLER=""
CLOCK_SKEW_MS=""
WSL_BOOT_ID=""
DOCKER_STARTED_AT=""
KAFKA_STOPPED=0
APP_STOPPED=0
ENVIRONMENT_PREPARED=0

log() { echo "[comment-throughput] $*"; }

case "$RESET_EVENT_GROUP_OFFSETS" in
  0|1) ;;
  *) log "ERROR RESET_EVENT_GROUP_OFFSETS must be 0 or 1"; exit 2 ;;
esac

case "$RESET_COMMENT_DATA" in
  0|1) ;;
  *) log "ERROR RESET_COMMENT_DATA must be 0 or 1"; exit 2 ;;
esac

case "$ALLOW_COLD_MEASUREMENT" in
  0|1) ;;
  *) log "ERROR ALLOW_COLD_MEASUREMENT must be 0 or 1"; exit 2 ;;
esac

MEASUREMENT_COMMAND=0
case "$COMMAND" in
  smoke|compare|pure-read|mixed|mixed-compare|overload|drain) MEASUREMENT_COMMAND=1 ;;
esac

if [ "$MEASUREMENT_COMMAND" -eq 1 ] \
    && [ "$RESET_COMMENT_DATA" -eq 1 ] \
    && awk -v warmup_seconds="$WARMUP_SECONDS" 'BEGIN {exit !(warmup_seconds == 0)}' \
    && [ "$ALLOW_COLD_MEASUREMENT" -ne 1 ]; then
  log "ERROR RESET_COMMENT_DATA=1 requires WARMUP_SECONDS>0 for steady-state measurement"
  log "set ALLOW_COLD_MEASUREMENT=1 only when intentionally measuring cold-start latency"
  exit 2
fi

cleanup_sampler() {
  if [ -n "$SUT_SAMPLER" ]; then
    kill "$SUT_SAMPLER" 2>/dev/null || true
    wait "$SUT_SAMPLER" 2>/dev/null || true
    SUT_SAMPLER=""
  fi
  if [ "$KAFKA_STOPPED" -eq 1 ]; then
    docker start zhiguang-kafka >/dev/null 2>&1 || true
    KAFKA_STOPPED=0
  fi
  if [ "$APP_STOPPED" -eq 1 ]; then
    docker compose up -d app >/dev/null 2>&1 || true
    APP_STOPPED=0
  fi
}

trap cleanup_sampler EXIT INT TERM

audit_topology() {
  local service count
  for service in app mysql redis kafka cassandra; do
    count=$(docker compose ps --status running --services | awk -v expected="$service" '$0 == expected {n++} END {print n + 0}')
    if [ "$count" -ne 1 ]; then
      log "ERROR topology service=$service running=$count expected=1"
      return 1
    fi
  done
}

audit_clock() {
  local windows_ms linux_ms skew absolute_skew synchronized
  if ! command -v powershell.exe >/dev/null 2>&1; then
    log "ERROR powershell.exe is required to audit the Windows/WSL clock"
    return 1
  fi
  windows_ms=$(powershell.exe -NoProfile -NonInteractive -Command \
    '[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()' | tr -d '\r')
  linux_ms=$(date +%s%3N)
  skew=$((linux_ms - windows_ms))
  absolute_skew=${skew#-}
  synchronized=$(timedatectl show -p NTPSynchronized --value 2>/dev/null || echo no)
  if [ "$synchronized" != "yes" ]; then
    log "ERROR WSL clock is not NTP synchronized"
    return 1
  fi
  if [ "$absolute_skew" -gt 1000 ]; then
    log "ERROR Windows/WSL clock skew=${skew}ms exceeds 1000ms"
    return 1
  fi
  CLOCK_SKEW_MS=$skew
}

capture_runtime_identity() {
  WSL_BOOT_ID=$(cat /proc/sys/kernel/random/boot_id)
  DOCKER_STARTED_AT=$(systemctl show docker -p ExecMainStartTimestampMonotonic --value)
}

audit_runtime_continuity() {
  local current_boot current_docker_start
  current_boot=$(cat /proc/sys/kernel/random/boot_id)
  current_docker_start=$(systemctl show docker -p ExecMainStartTimestampMonotonic --value)
  if [ "$current_boot" != "$WSL_BOOT_ID" ] || [ "$current_docker_start" != "$DOCKER_STARTED_AT" ]; then
    log "ERROR WSL or Docker restarted during the measurement"
    return 1
  fi
}

mysql_value() {
  docker exec -i zhiguang-mysql mysql -uzhiguang -pzhiguang123456 -N -B zhiguang -e "$1" 2>/dev/null
}

metric_value() {
  curl -fsS -m 3 "$1" 2>/dev/null \
    | sed -n 's/.*"measurements":\[{"statistic":"[^"]*","value":\([^}]*\)}\].*/\1/p'
}

seed_hash() {
  {
    mysql_value "SELECT COUNT(*),COALESCE(MIN(id),0),COALESCE(MAX(id),0) FROM users WHERE id BETWEEN 1000001 AND 1999999;"
    mysql_value "SELECT COUNT(*),COALESCE(MIN(id),0),COALESCE(MAX(id),0) FROM know_posts WHERE id BETWEEN 2000001 AND 2999999;"
  } | sha256sum | awk '{print $1}'
}

run_k6() {
  local run_dir=$1 mode=$2 ratio=$3 exit_code
  local output_args=()
  if [ "$RAW_JSON" -eq 1 ]; then
    output_args=(--out "json=/loadtest/$run_dir/k6-raw.json")
  fi
  set +e
  docker run --rm --cpus="$K6_CPUS" --memory="$K6_MEM" \
    --add-host=host.docker.internal:host-gateway \
    -v "$PWD:/loadtest" -w /loadtest "$K6_IMAGE" run \
    "${output_args[@]}" \
    --summary-export "/loadtest/$run_dir/k6-summary.json" \
    -e "BASE_URL=$BASE_URL" -e "COMMENT_MODE=$mode" -e "READ_RATIO=$ratio" \
    -e "RATE=$RATE" -e "HOLD=$HOLD" -e "VUS=$VUS" -e "DRAIN_WRITES=$DRAIN_WRITES" \
    -e "TOKEN_POOL=$TOKEN_POOL" -e "WARMUP_SECONDS=$WARMUP_SECONDS" \
    -e "PREALLOCATED_VUS=$PREALLOCATED_VUS" \
    scripts/comment-throughput.js > "$run_dir/k6.log" 2>&1
  exit_code=$?
  set -e
  echo "$exit_code" > "$run_dir/k6-exit-code.txt"
}

snapshot() {
  local target=$1
  mysql_value "SELECT NOW(),SUM(status='pending'),SUM(status='succeeded'),SUM(status='failed') FROM pending_comments;" > "$target/pending.tsv"
  snapshot_outbox_counts "$target/outbox.tsv"
  docker exec -i zhiguang-kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --all-groups > "$target/kafka-groups.txt" 2>&1 || true
}

snapshot_outbox_counts() {
  local target=$1
  mysql_value "SELECT NOW(),event_type,state,COUNT(*) FROM comment_outbox GROUP BY event_type,state ORDER BY event_type,state;" > "$target"
}

wait_for_drain() {
  local run_dir=$1 deadline_ms started_ms now_ms remaining elapsed_ms
  started_ms=$(date +%s%3N)
  deadline_ms=$((started_ms + ${DRAIN_TIMEOUT_SECONDS:-300} * 1000))
  echo -e "elapsed_s\tready\tclaimed\tpending" > "$run_dir/drain.tsv"
  while true; do
    remaining=$(mysql_value "SELECT COALESCE(SUM(state=0),0),COALESCE(SUM(state=1),0),(SELECT COUNT(*) FROM pending_comments WHERE status='pending') FROM comment_outbox;")
    now_ms=$(date +%s%3N)
    elapsed_ms=$((now_ms - started_ms))
    printf '%d.%03d\t%s\n' "$((elapsed_ms / 1000))" "$((elapsed_ms % 1000))" "$remaining" >> "$run_dir/drain.tsv"
    if echo "$remaining" | awk '{exit !(($1 + $2 + $3) == 0)}'; then
      return 0
    fi
    if [ "$now_ms" -ge "$deadline_ms" ]; then
      return 1
    fi
    sleep 0.1
  done
}

wait_for_kafka_health() {
  local deadline=$((SECONDS + ${KAFKA_START_TIMEOUT_SECONDS:-120})) status
  while [ "$SECONDS" -lt "$deadline" ]; do
    status=$(docker inspect -f '{{.State.Health.Status}}' zhiguang-kafka 2>/dev/null || true)
    [ "$status" = "healthy" ] && return 0
    sleep 1
  done
  return 1
}

wait_for_app_health() {
  local deadline=$((SECONDS + ${APP_START_TIMEOUT_SECONDS:-180})) status
  while [ "$SECONDS" -lt "$deadline" ]; do
    status=$(docker inspect -f '{{.State.Health.Status}}' zhiguang-app 2>/dev/null || true)
    [ "$status" = "healthy" ] && return 0
    sleep 1
  done
  return 1
}

audit_event_consumer_lag() {
  local group description
  for group in $EVENT_CONSUMER_GROUPS; do
    description=$(docker exec -i zhiguang-kafka \
      /opt/kafka/bin/kafka-consumer-groups.sh \
      --bootstrap-server localhost:9092 \
      --describe --group "$group" 2>&1) || {
        log "ERROR cannot describe Kafka group=$group"
        echo "$description"
        return 1
      }
    if ! echo "$description" | awk -v topic="$EVENT_TOPIC" '
      $2 == topic {
        partitions++
        if ($4 == "-" || $6 != 0) unhealthy = 1
      }
      END { exit !(partitions > 0 && !unhealthy) }
    '; then
      log "ERROR Kafka group=$group has missing offsets or non-zero lag on topic=$EVENT_TOPIC"
      echo "$description"
      return 1
    fi
  done
}

wait_for_idle_runtime() {
  local deadline=$((SECONDS + IDLE_TIMEOUT_SECONDS)) stable_samples=0 hikari_pending
  log "waiting for Kafka event lag and Hikari pending connections to remain at zero"
  while [ "$SECONDS" -lt "$deadline" ]; do
    hikari_pending=$(metric_value "${SUT_BASE_URL}/actuator/metrics/hikaricp.connections.pending" || true)
    if [ -n "$hikari_pending" ] \
        && awk -v pending="$hikari_pending" 'BEGIN {exit !(pending == 0)}' \
        && audit_event_consumer_lag >/dev/null; then
      stable_samples=$((stable_samples + 1))
      if [ "$stable_samples" -ge "$IDLE_STABLE_SAMPLES" ]; then
        log "runtime idle: Kafka event lag=0, Hikari pending=0"
        return 0
      fi
    else
      stable_samples=0
    fi
    sleep "$IDLE_SAMPLE_INTERVAL_SECONDS"
  done
  log "ERROR runtime did not become idle within ${IDLE_TIMEOUT_SECONDS}s"
  audit_event_consumer_lag || true
  log "Hikari pending=${hikari_pending:-unavailable}"
  return 1
}

reset_comment_data() {
  log "clearing prior load-test comment data and comment caches"
  mysql_value "
    DELETE o FROM comment_outbox o
    LEFT JOIN pending_comments p ON p.pending_comment_id = o.aggregate_id
    LEFT JOIN comments c ON c.comment_id = o.aggregate_id
    WHERE p.creator_id BETWEEN 1000001 AND 1999999
       OR c.creator_id BETWEEN 1000001 AND 1999999;
    DELETE FROM comments WHERE creator_id BETWEEN 1000001 AND 1999999;
    DELETE FROM pending_comments WHERE creator_id BETWEEN 1000001 AND 1999999;
  " >/dev/null
  docker exec -i zhiguang-redis sh -c \
    'redis-cli --scan --pattern "comment:*" | xargs -r redis-cli UNLINK >/dev/null'
  docker exec -i zhiguang-redis sh -c \
    'redis-cli --scan --pattern "zg:singleflight:*comment-page-head*" | xargs -r redis-cli UNLINK >/dev/null'
}

prepare_environment() {
  local group reset_output
  if [ "$ENVIRONMENT_PREPARED" -eq 1 ]; then
    return 0
  fi
  if [ "$RESET_COMMENT_DATA" -eq 1 ] || [ "$RESET_EVENT_GROUP_OFFSETS" -eq 1 ]; then
    docker compose stop app >/dev/null
    APP_STOPPED=1
    if [ "$RESET_COMMENT_DATA" -eq 1 ]; then
      reset_comment_data
    fi
    if [ "$RESET_EVENT_GROUP_OFFSETS" -eq 1 ]; then
      log "resetting load-test Kafka event groups to latest offset"
      for group in $EVENT_CONSUMER_GROUPS; do
        reset_output=$(docker exec -i zhiguang-kafka \
          /opt/kafka/bin/kafka-consumer-groups.sh \
          --bootstrap-server localhost:9092 \
          --group "$group" --topic "$EVENT_TOPIC" \
          --reset-offsets --to-latest --execute 2>&1) || {
            log "ERROR cannot reset Kafka group=$group"
            echo "$reset_output"
            return 1
          }
      done
    fi
    docker compose up -d app >/dev/null
    APP_STOPPED=0
    wait_for_app_health || {
      log "ERROR app did not become healthy after load-test environment reset"
      return 1
    }
  fi
  if [ "$RESET_EVENT_GROUP_OFFSETS" -eq 1 ]; then
    audit_event_consumer_lag
  fi
  ENVIRONMENT_PREPARED=1
}

run_round() {
  local scenario=$1 round=$2 mode=$3 ratio=$4 stamp run_dir cache_state measurement_mode
  cache_state="${CACHE_STATE:-warm}"
  if [ -z "${CACHE_STATE:-}" ] && [ "$RESET_COMMENT_DATA" -eq 1 ]; then
    cache_state=cold
  fi
  measurement_mode=steady-after-warmup
  if awk -v warmup_seconds="$WARMUP_SECONDS" 'BEGIN {exit !(warmup_seconds == 0)}'; then
    measurement_mode=cold-start
  fi
  stamp=$(date -u +%Y%m%dT%H%M%SZ)
  run_dir="$RESULT_ROOT/$VARIANT/$scenario/round-$round-$stamp"
  mkdir -p "$run_dir"
  prepare_environment
  audit_topology
  audit_clock
  wait_for_idle_runtime
  capture_runtime_identity
  {
    echo "sha=${APP_SHA:-$(git rev-parse HEAD)}"
    echo "variant=$VARIANT"
    echo "scenario=$scenario"
    echo "round=$round"
    echo "mode=$mode"
    echo "arrival_rate=$RATE"
    echo "read_ratio=$ratio"
    echo "duration=$HOLD"
    echo "warmup_seconds=$WARMUP_SECONDS"
    echo "measurement_mode=$measurement_mode"
    echo "vus=$VUS"
    echo "preallocated_vus=$PREALLOCATED_VUS"
    echo "drain_writes=$DRAIN_WRITES"
    echo "token_pool=$TOKEN_POOL"
    echo "seed_hash=$(seed_hash)"
    echo "cache_state=$cache_state"
    echo "base_url=$BASE_URL"
    echo "sut_base_url=$SUT_BASE_URL"
    echo "k6_image=$K6_IMAGE"
    echo "sample_interval_seconds=$SAMPLE_INTERVAL_SECONDS"
    echo "collect_kafka_lag=$COLLECT_KAFKA_LAG"
    echo "raw_json=$RAW_JSON"
    echo "reset_event_group_offsets=$RESET_EVENT_GROUP_OFFSETS"
    echo "reset_comment_data=$RESET_COMMENT_DATA"
    echo "allow_cold_measurement=$ALLOW_COLD_MEASUREMENT"
    echo "event_topic=$EVENT_TOPIC"
    echo "windows_wsl_clock_skew_ms=$CLOCK_SKEW_MS"
    echo "wsl_boot_id=$WSL_BOOT_ID"
    echo "docker_started_at_monotonic=$DOCKER_STARTED_AT"
  } > "$run_dir/metadata.env"
  snapshot_outbox_counts "$run_dir/outbox-before.tsv"
  BASE_URL="$SUT_BASE_URL" SAMPLE_INTERVAL="$SAMPLE_INTERVAL_SECONDS" SAMPLE_DURATION=7200 \
    COLLECT_KAFKA_LAG="$COLLECT_KAFKA_LAG" ./collect_metrics.sh > "$run_dir/sut.tsv" &
  SUT_SAMPLER=$!
  if [ "$mode" = "drain" ]; then
    docker stop zhiguang-kafka >/dev/null
    KAFKA_STOPPED=1
    run_k6 "$run_dir" "$mode" "$ratio"
    docker start zhiguang-kafka >/dev/null
    KAFKA_STOPPED=0
    wait_for_kafka_health
    wait_for_drain "$run_dir"
  else
    run_k6 "$run_dir" "$mode" "$ratio"
    if [ "$mode" = "write-only" ]; then
      wait_for_drain "$run_dir"
    fi
  fi
  audit_runtime_continuity
  cleanup_sampler
  snapshot "$run_dir"
  log "completed $run_dir"
}

summarize() {
  local csv="$RESULT_ROOT/summary.csv"
  mkdir -p "$RESULT_ROOT"
  echo "variant,scenario,round,sha,seed_hash,read_per_s,accepted_per_s,read_p50_ms,read_p95_ms,read_p99_ms,read_max_ms,error_rate,dropped,drain_seconds,artifact,read_count,accepted_count,outbox_published_delta,outbox_published_per_s,materialized_per_s" > "$csv"
  docker run --rm -v "$PWD:/loadtest" -w /loadtest node:20-alpine \
    node summarize-comment-throughput.mjs "$RESULT_ROOT" "$RESULT_ROOT/medians.csv" >> "$csv"
  log "summary=$csv"
}

case "$COMMAND" in
  prepare)
    prepare_environment
    audit_topology
    log "load-test Kafka event groups are ready"
    ;;
  clock-check)
    audit_clock
    log "clock synchronized, Windows/WSL skew=${CLOCK_SKEW_MS}ms"
    ;;
  idle)
    audit_topology
    wait_for_app_health
    wait_for_idle_runtime
    ;;
  smoke)
    ROUNDS=1 RATE="${RATE:-2}" HOLD="${HOLD:-1m}" DRAIN_WRITES="${DRAIN_WRITES:-20}"
    run_round pure-read 1 pure-read 1
    run_round mixed-80-20 1 mixed 0.8
    run_round outbox-drain 1 drain 0
    summarize
    ;;
  compare)
    for round in $(seq 1 "$ROUNDS"); do
      run_round pure-read "$round" pure-read 1
      run_round mixed-80-20 "$round" mixed 0.8
      run_round outbox-drain "$round" drain 0
    done
    summarize
    ;;
  pure-read) run_round pure-read 1 pure-read 1 ;;
  mixed) run_round mixed-80-20 1 mixed 0.8 ;;
  mixed-compare)
    for round in $(seq 1 "$ROUNDS"); do
      run_round mixed-80-20 "$round" mixed 0.8
    done
    summarize
    ;;
  overload) run_round sustained-overload 1 write-only 0 ;;
  drain) run_round outbox-drain 1 drain 0 ;;
  summarize) summarize ;;
  *) echo "usage: $0 {prepare|clock-check|idle|smoke|compare|pure-read|mixed|mixed-compare|overload|drain|summarize}"; exit 2 ;;
esac
