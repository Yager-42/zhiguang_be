#!/usr/bin/env bash
# 服务端指标采样器：每 SAMPLE_INTERVAL 秒输出一行 TSV 到 stdout（运行方重定向到 results/metrics/*.tsv）。
# 采样项：Redis ops/clients/keys、MySQL 连接与查询增量、Kafka 消费组总 lag、ES 健康、应用健康。
# 采样失败输出 NA，不中断。
# 用法: ./collect_metrics.sh results/metrics/feed.tsv   （后台运行，SIGTERM 结束）
set -uo pipefail

INTERVAL="${SAMPLE_INTERVAL:-10}"
DURATION="${SAMPLE_DURATION:-21600}" # 6h 安全上限
COLLECT_KAFKA_LAG="${COLLECT_KAFKA_LAG:-0}"
KAFKA_GROUP="${KAFKA_GROUP:-comment-write-consumer}"

MYSQL=(docker exec -i zhiguang-mysql mysql -uzhiguang -pzhiguang123456 -N -B zhiguang)
REDIS=(docker exec -i zhiguang-redis redis-cli)
KAFKA=(docker exec -i zhiguang-kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092)
BASE_URL="${BASE_URL:-http://localhost:8080}"

echo -e "ts\tredis_ops\tredis_clients\tredis_keys\tmysql_threads\tmysql_qps\tpending_count\tpending_oldest_s\toutbox_ready\toutbox_claimed\toutbox_published\toutbox_oldest_ready_s\tkafka_lag\tcomment_executor_queued\thikari_pending\tjvm_heap_used_bytes\tapp_up"

metric_value() {
  curl -fsS -m 3 "$1" 2>/dev/null \
    | sed -n 's/.*"measurements":\[{"statistic":"[^"]*","value":\([^}]*\)}\].*/\1/p'
}

prev_queries=""
prev_sample_ms=""
started_ms=$(date +%s%3N)
deadline_ms=$((started_ms + DURATION * 1000))
while true; do
  sample_ms=$(date +%s%3N)
  [ "$sample_ms" -ge "$deadline_ms" ] && break
  ts=$(date +%H:%M:%S)

  redis_info=$("${REDIS[@]}" info 2>/dev/null | tr -d '\r')
  redis_ops=$(echo "$redis_info" | awk -F: '/^instantaneous_ops_per_sec:/{print $2}')
  redis_clients=$(echo "$redis_info" | awk -F: '/^connected_clients:/{print $2}')
  redis_keys=$(echo "$redis_info" | awk -F'[:,]' '/^db0:/{sub(/^keys=/, "", $2); print $2}')

  mysql_status=$("${MYSQL[@]}" -e \
    "SHOW GLOBAL STATUS WHERE Variable_name IN ('Queries','Threads_connected');" 2>/dev/null)
  mysql_threads=$(echo "$mysql_status" | awk '$1 == "Threads_connected" {print $2}')
  queries=$(echo "$mysql_status" | awk '$1 == "Queries" {print $2}')
  if [ -n "$queries" ] && [ -n "$prev_queries" ] && [ "$sample_ms" -gt "$prev_sample_ms" ]; then
    mysql_qps=$(awk -v delta="$((queries - prev_queries))" -v elapsed_ms="$((sample_ms - prev_sample_ms))" \
      'BEGIN {printf "%.2f", delta * 1000 / elapsed_ms}')
  else
    mysql_qps=NA
  fi
  prev_queries="$queries"
  prev_sample_ms="$sample_ms"

  pending_count=$(metric_value "${BASE_URL}/actuator/metrics/comment.pending.count")
  pending_oldest=$(metric_value "${BASE_URL}/actuator/metrics/comment.pending.oldest.seconds")
  outbox_ready=$(metric_value "${BASE_URL}/actuator/metrics/comment.outbox.ready.count")
  outbox_claimed=$(metric_value "${BASE_URL}/actuator/metrics/comment.outbox.claimed.count")
  outbox_published=NA
  outbox_oldest=$(metric_value "${BASE_URL}/actuator/metrics/comment.outbox.oldest.ready.seconds")

  kafka_lag=NA
  if [ "$COLLECT_KAFKA_LAG" -eq 1 ]; then
    kafka_lag=$("${KAFKA[@]}" --describe --group "$KAFKA_GROUP" 2>/dev/null \
      | grep -E '^\S+\s+\S+\s+[0-9]+\s+[0-9]+\s+[0-9]+\s+[0-9]+' \
      | awk '{s += $6} END {print s + 0}')
  fi

  comment_executor_queued=$(metric_value "${BASE_URL}/actuator/metrics/executor.queued?tag=name:commentOutboxExecutor")
  hikari_pending=$(metric_value "${BASE_URL}/actuator/metrics/hikaricp.connections.pending")
  jvm_heap_used=$(metric_value "${BASE_URL}/actuator/metrics/jvm.memory.used?tag=area:heap")

  app_up=$(curl -fsS -m 3 "${BASE_URL}/actuator/health" 2>/dev/null | sed -n 's/.*"status":"\([A-Z]*\)".*/\1/p')
  [ -z "$app_up" ] && app_up=DOWN

  echo -e "${ts}\t${redis_ops:-NA}\t${redis_clients:-NA}\t${redis_keys:-NA}\t${mysql_threads:-NA}\t${mysql_qps:-NA}\t${pending_count:-NA}\t${pending_oldest:-NA}\t${outbox_ready:-NA}\t${outbox_claimed:-NA}\t${outbox_published:-NA}\t${outbox_oldest:-NA}\t${kafka_lag:-NA}\t${comment_executor_queued:-NA}\t${hikari_pending:-NA}\t${jvm_heap_used:-NA}\t${app_up}"

  sleep "$INTERVAL"
done
