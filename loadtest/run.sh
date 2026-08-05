#!/usr/bin/env bash
# zhiguang_be 压测编排入口
#
# 用法:
#   ./run.sh seed                  灌种子数据（幂等；含参数合规前置校验）
#   ./run.sh seed --force          先清理（cleanup.sql）再灌
#   ./run.sh verify                校验种子数据合规性（自关注/孤儿/格式/长度/状态，见 seed/verify_seed.sql）
#   ./run.sh baseline              单链路分级加压：auth feed counter comment relation search wallet promotion × VUS_LEVELS
#   ./run.sh mixed                 混合常态基线（MIXED_VUS=300, MIXED_HOLD=30m）
#   ./run.sh mixed --high          混合高压（按 VUS_LEVELS 逐级加压至拐点）
#   ./run.sh soak                  长稳（SOAK_VUS=300, SOAK_HOLD=2h）
#   ./run.sh all                   baseline + mixed + soak
#
# 前置条件:
#   - k6 已安装（https://k6.io/docs/getting-started/installation/）
#   - docker compose 全栈已启动（mysql/redis/kafka/rocketmq/cassandra/es）
#   - 应用已启动（mvn spring-boot:run 或 IDE），且灌数后重启过一次（触发 ES 索引自回填）
#   - Windows 请使用 Git Bash 或 WSL
#
# 单机模式（只有一台机器）:
#   K6_DOCKER=1 ./run.sh baseline   # k6 跑在容器里并限核/限内存（默认 --cpus=2 --memory=1024m），
#                                   # 避免 k6 与 JVM 抢 CPU 导致 P99 失真；BASE_URL 自动切 host.docker.internal
#   K6_CPUS=1 K6_MEM=512m ./run.sh mixed   # 进一步收紧 k6 配额，把 CPU 让给应用
#
# 常用覆盖:
#   BASE_URL=http://localhost:8080
#   USER_N=1000 POST_N=500 FOLLOW_PER_USER=20 LARGE_FOLLOWER_N=0
#   VUS_LEVELS="100 300 600 1000" HOLD=4m
#   BASELINE_SCRIPTS="feed counter"     # 只跑部分单链路
#   PROMOTION 场景需 PROMOTION_BPRIME_ENABLED=true 且 RocketMQ 正常
set -euo pipefail
cd "$(dirname "$0")"

# ---------- 配置 ----------
# 单机模式（K6_DOCKER=1）：k6 容器化限核，BASE_URL 用 host.docker.internal 访问宿主应用
if [ -n "${K6_DOCKER:-}" ]; then
  DEFAULT_BASE_URL="http://host.docker.internal:8080"
else
  DEFAULT_BASE_URL="http://localhost:8080"
fi
BASE_URL="${BASE_URL:-$DEFAULT_BASE_URL}"
RESULTS_DIR="${RESULTS_DIR:-results}"
METRICS_DIR="$RESULTS_DIR/metrics"
mkdir -p "$RESULTS_DIR" "$METRICS_DIR"

# 种子规模
USER_N="${USER_N:-1000}"
POST_N="${POST_N:-500}"
FOLLOW_PER_USER="${FOLLOW_PER_USER:-20}"
LARGE_FOLLOWER_N="${LARGE_FOLLOWER_N:-0}"
LARGE_AUTHOR_ID="${LARGE_AUTHOR_ID:-1000001}"
USER_ID_BASE="${USER_ID_BASE:-1000000}"
POST_ID_BASE="${POST_ID_BASE:-2000000}"
# 默认密码 Loadtest@123 的 BCrypt 哈希（node seed/gen_password_hash.mjs 可重新生成）。
# 注意：不能写成 ${PWD_HASH:-'$2b$...'} —— ${:-} 的默认值会先做参数展开，$2 会被当作位置参数。
if [ -z "${PWD_HASH:-}" ]; then
  PWD_HASH='$2b$10$QrYOGPw3LlsIK8s.YlaEzucAA2m9oDYlJjCcQkah4YqbB/wMXyxFq'
fi

# 加压级别
VUS_LEVELS="${VUS_LEVELS:-100 300 600 1000}"
HOLD="${HOLD:-4m}"
BASELINE_SCRIPTS="${BASELINE_SCRIPTS:-auth feed counter comment relation search wallet promotion}"
MIXED_VUS="${MIXED_VUS:-300}"
MIXED_HOLD="${MIXED_HOLD:-30m}"
SOAK_VUS="${SOAK_VUS:-300}"
SOAK_HOLD="${SOAK_HOLD:-2h}"

# ---------- 工具 ----------
log() { echo "[run.sh] $*"; }

mysql_exec() { docker exec -i zhiguang-mysql mysql -uzhiguang -pzhiguang123456 zhiguang; }

render() { # 渲染 SQL 占位符
  sed -e "s|__USER_N__|$USER_N|g" \
      -e "s|__POST_N__|$POST_N|g" \
      -e "s|__FOLLOW_PER_USER__|$FOLLOW_PER_USER|g" \
      -e "s|__LARGE_FOLLOWER_N__|$LARGE_FOLLOWER_N|g" \
      -e "s|__LARGE_AUTHOR_ID__|$LARGE_AUTHOR_ID|g" \
      -e "s|__USER_ID_BASE__|$USER_ID_BASE|g" \
      -e "s|__POST_ID_BASE__|$POST_ID_BASE|g" \
      -e "s|__BCRYPT__|$PWD_HASH|g" "$1"
}

# k6 执行包装：K6_DOCKER=1 时容器化限核运行（单机模式），否则直接用本机 k6。
# 注意：容器模式挂载 $PWD 到 /loadtest，RESULTS_DIR 请保持相对路径（默认 results/）。
if [ -n "${K6_DOCKER:-}" ]; then
  K6_IMAGE="${K6_IMAGE:-grafana/k6}"
  K6_CPUS="${K6_CPUS:-2}"
  K6_MEM="${K6_MEM:-1024m}"
  log "k6 container mode: image=$K6_IMAGE cpus=$K6_CPUS mem=$K6_MEM base_url=$BASE_URL"
  k6_run() {
    docker run --rm \
      --cpus="$K6_CPUS" --memory="$K6_MEM" \
      -v "$PWD:/loadtest" -w /loadtest \
      "$K6_IMAGE" "$@"
  }
else
  k6_run() { k6 "$@"; }
fi

start_samplers() { # tag —— 同时采 SUT 侧与压测机侧，判定瓶颈归属
  ./collect_metrics.sh "$METRICS_DIR/sut-$1.tsv" &
  SUT_SAMPLER=$!
  ./loadgen_metrics.sh "$METRICS_DIR/loadgen-$1.tsv" &
  LOADGEN_SAMPLER=$!
}

stop_samplers() {
  kill "$SUT_SAMPLER" "$LOADGEN_SAMPLER" 2>/dev/null || true
  wait 2>/dev/null || true
}

run_phase() { # name script extra_k6_args...
  local name=$1 script=$2
  shift 2
  for vus in $VUS_LEVELS; do
    log "phase=$name script=$script vus=$vus"
    start_samplers "$name-$script-$vus"
    k6_run run \
      --out "json=$RESULTS_DIR/$name-$script-$vus.json" \
      -e "BASE_URL=$BASE_URL" -e "VUS=$vus" -e "HOLD=$HOLD" \
      "$@" "scripts/$script.js" 2>&1 | tee "$RESULTS_DIR/$name-$script-$vus.log"
    stop_samplers
  done
}

# ---------- 子命令 ----------
cmd="${1:-help}"
case "$cmd" in
  seed)
    shift
    # 数据合规前置校验（防自关注/越界，详见 seed/verify_seed.sql）
    if [ "$USER_N" -lt 2 ]; then
      log "ERROR: USER_N must be >= 2 (单用户会自关注)"
      exit 1
    fi
    if [ "$FOLLOW_PER_USER" -ge "$USER_N" ]; then
      log "ERROR: FOLLOW_PER_USER must be < USER_N (轮转公式会产生自关注)"
      exit 1
    fi
    if [ "$LARGE_FOLLOWER_N" -gt $((USER_N - 1)) ]; then
      log "ERROR: LARGE_FOLLOWER_N must be <= USER_N-1 (大V粉丝必须落在用户池内且排除大V自己)"
      exit 1
    fi
    if [ "${1:-}" = "--force" ]; then
      log "cleanup existing seed data"
      render seed/cleanup.sql | mysql_exec
    fi
    log "seed users + wallets (USER_N=$USER_N)"
    render seed/seed_users.sql | mysql_exec
    log "seed posts (POST_N=$POST_N)"
    render seed/seed_posts.sql | mysql_exec
    log "seed follow graph (FOLLOW_PER_USER=$FOLLOW_PER_USER, LARGE_FOLLOWER_N=$LARGE_FOLLOWER_N)"
    render seed/seed_follow_graph.sql | mysql_exec
    log "seed promotion campaign + window"
    render seed/seed_promotion.sql | mysql_exec
    log "seed cassandra (post text + feed_inbox)"
    node seed/gen_seed_cassandra.mjs "$USER_N" "$USER_ID_BASE" "$POST_N" "$POST_ID_BASE" \
        "$FOLLOW_PER_USER" "$LARGE_AUTHOR_ID" "$LARGE_FOLLOWER_N" | docker exec -i zhiguang-cassandra cqlsh
    log "warm redis SDS counters (skip 以压重建风暴: warm_sds.sh 单独控制)"
    REDIS_CLI="docker exec -i zhiguang-redis redis-cli" bash seed/warm_sds.sh || log "warm_sds skipped/failed"
    log "seed done. 记得重启应用一次触发 ES 索引自回填；随后冒烟: k6_run run -e VUS=1 -e HOLD=1m scripts/mixed.js （单机容器模式: K6_DOCKER=1 时同样可用）"
    log "建议执行 ./run.sh verify 校验种子数据合规性"
    ;;

  verify)
    log "verify seed data compliance (all violations should be 0)"
    render seed/verify_seed.sql | mysql_exec
    ;;

  baseline)
    log "baseline: scripts=[$BASELINE_SCRIPTS] vus=[$VUS_LEVELS] hold=$HOLD"
    for script in $BASELINE_SCRIPTS; do
      run_phase baseline "$script"
    done
    ;;

  mixed)
    shift
    if [ "${1:-}" = "--high" ]; then
      log "mixed high-load: vus=[$VUS_LEVELS] hold=$HOLD"
      for vus in $VUS_LEVELS; do
        start_samplers "mixed-high-$vus"
        k6_run run --out "json=$RESULTS_DIR/mixed-high-$vus.json" \
          -e "BASE_URL=$BASE_URL" -e "VUS=$vus" -e "HOLD=$HOLD" \
          scripts/mixed.js 2>&1 | tee "$RESULTS_DIR/mixed-high-$vus.log"
        stop_samplers
      done
    else
      log "mixed baseline: vus=$MIXED_VUS hold=$MIXED_HOLD"
      start_samplers "mixed-$MIXED_VUS"
      k6_run run --out "json=$RESULTS_DIR/mixed-$MIXED_VUS.json" \
        -e "BASE_URL=$BASE_URL" -e "VUS=$MIXED_VUS" -e "HOLD=$MIXED_HOLD" \
        scripts/mixed.js 2>&1 | tee "$RESULTS_DIR/mixed-$MIXED_VUS.log"
      stop_samplers
    fi
    ;;

  soak)
    log "soak: vus=$SOAK_VUS hold=$SOAK_HOLD"
    start_samplers "soak-$SOAK_VUS"
    k6_run run --out "json=$RESULTS_DIR/soak-$SOAK_VUS.json" \
      -e "BASE_URL=$BASE_URL" -e "SOAK=1" -e "VUS=$SOAK_VUS" -e "HOLD=$SOAK_HOLD" -e "P95=1000" \
      scripts/soak.js 2>&1 | tee "$RESULTS_DIR/soak-$SOAK_VUS.log"
    stop_samplers
    ;;

  all)
    ./run.sh baseline
    ./run.sh mixed
    ./run.sh soak
    ;;

  *)
    echo "用法: ./run.sh {seed [--force] | verify | baseline | mixed [--high] | soak | all}"
    echo
    sed -n '1,30p' "$0" | grep -E '^#   ' 
    ;;
esac
