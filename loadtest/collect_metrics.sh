#!/usr/bin/env bash
# 服务端指标采样器：每 SAMPLE_INTERVAL 秒输出一行 TSV 到 stdout（运行方重定向到 results/metrics/*.tsv）。
# 采样项：Redis ops/clients/keys、MySQL 连接与查询增量、Kafka 消费组总 lag、ES 健康、应用健康。
# 采样失败输出 NA，不中断。
# 用法: ./collect_metrics.sh results/metrics/feed.tsv   （后台运行，SIGTERM 结束）
set -uo pipefail

INTERVAL="${SAMPLE_INTERVAL:-15}"
DURATION="${SAMPLE_DURATION:-21600}" # 6h 安全上限

MYSQL=(docker exec -i zhiguang-mysql mysql -uzhiguang -pzhiguang123456 -N -B zhiguang)
REDIS=(docker exec -i zhiguang-redis redis-cli)
KAFKA=(docker exec -i zhiguang-kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092)
BASE_URL="${BASE_URL:-http://localhost:8080}"

echo -e "ts\tredis_ops\tredis_clients\tredis_keys\tmysql_threads\tmysql_qps\tkafka_lag\tapp_up"

prev_queries=""
for ((s = 0; s < DURATION; s += INTERVAL)); do
  ts=$(date +%H:%M:%S)

  redis_ops=$("${REDIS[@]}" info stats 2>/dev/null | awk -F: '/^instantaneous_ops_per_sec/{gsub("\r", ""); print $2}')
  redis_clients=$("${REDIS[@]}" info clients 2>/dev/null | awk -F: '/^connected_clients/{gsub("\r", ""); print $2}')
  redis_keys=$("${REDIS[@]}" info keyspace 2>/dev/null | awk -F: '/^db0:/{gsub("keys=", "", $2); print $2}')

  mysql_threads=$("${MYSQL[@]}" -e "SHOW GLOBAL STATUS LIKE 'Threads_connected';" 2>/dev/null | tail -1)
  queries=$("${MYSQL[@]}" -e "SHOW GLOBAL STATUS LIKE 'Queries';" 2>/dev/null | tail -1)
  if [ -n "$queries" ] && [ -n "$prev_queries" ]; then
    mysql_qps=$(( (queries - prev_queries) / INTERVAL ))
  else
    mysql_qps=NA
  fi
  prev_queries="$queries"

  kafka_lag=$("${KAFKA[@]}" --describe --all-groups 2>/dev/null | grep -E '^\S+\s+\S+\s+[0-9]+\s+[0-9]+\s+[0-9]+\s+[0-9]+' | awk '{s += $6} END {print s + 0}')

  app_up=$(curl -fsS -m 3 "${BASE_URL}/actuator/health" 2>/dev/null | sed -n 's/.*"status":"\([A-Z]*\)".*/\1/p')
  [ -z "$app_up" ] && app_up=DOWN

  echo -e "${ts}\t${redis_ops:-NA}\t${redis_clients:-NA}\t${redis_keys:-NA}\t${mysql_threads:-NA}\t${mysql_qps:-NA}\t${kafka_lag:-NA}\t${app_up}"

  sleep "$INTERVAL"
done
