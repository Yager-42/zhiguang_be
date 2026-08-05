#!/usr/bin/env bash
# 预热计数 SDS 键（可选）：
#   cnt:v1:knowpost:{postId} —— 实体计数固定结构（20 字节 = 5 段 x 4 字节）
#   ucnt:{userId}            —— 用户计数固定结构
# 值填 20 个 NUL 字节，保证结构合法（readInt32BE 读出 0），不触发"缺失→重建"路径。
# 想专门压"SDS 缺失 → 位图重建风暴"时跳过本脚本。
set -euo pipefail

# redis-cli 可直接执行，或传 "docker exec -i zhiguang-redis redis-cli" 以进容器执行
REDIS_CLI="${REDIS_CLI:-redis-cli}"
POST_BASE="${POST_ID_BASE:-2000000}"
POST_N="${POST_COUNT:-500}"
USER_BASE="${USER_ID_BASE:-1000000}"
USER_N="${USER_POOL:-1000}"

for i in $(seq 1 "$POST_N"); do
  printf '\0%.0s' {1..20} | $REDIS_CLI -x SET "cnt:v1:knowpost:$((POST_BASE + i))" > /dev/null
done

for i in $(seq 1 "$USER_N"); do
  printf '\0%.0s' {1..20} | $REDIS_CLI -x SET "ucnt:$((USER_BASE + i))" > /dev/null
done

echo "warmed $((POST_N + USER_N)) SDS keys (cnt:v1:knowpost:* + ucnt:*)"
