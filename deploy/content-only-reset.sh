#!/usr/bin/env bash
# 仅保留已发布知文及其完整作者账号的生产数据重建脚本。
# 默认仅做只读预检；执行重置必须同时传入 --execute 和确认环境变量。
set -Eeuo pipefail

readonly PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly PROJECT_NAME="${COMPOSE_PROJECT_NAME:-$(basename "$PROJECT_DIR")}" 
readonly MYSQL_CONTAINER="${MYSQL_CONTAINER:-zhiguang-mysql}"
readonly REDIS_CONTAINER="${REDIS_CONTAINER:-zhiguang-redis}"
readonly KAFKA_CONTAINER="${KAFKA_CONTAINER:-zhiguang-kafka}"
readonly CASSANDRA_CONTAINER="${CASSANDRA_CONTAINER:-zhiguang-cassandra}"
readonly ELASTICSEARCH_CONTAINER="${ELASTICSEARCH_CONTAINER:-zhiguang-elasticsearch}"
readonly MINIO_CONTAINER="${MINIO_CONTAINER:-zhiguang-minio}"
readonly CANAL_CONTAINER="${CANAL_CONTAINER:-zhiguang-canal}"
readonly GORSE_CONTAINER="${GORSE_CONTAINER:-zhiguang-gorse}"
readonly APP_CONTAINER="${APP_CONTAINER:-zhiguang-app}"
readonly MINIO_BUCKET="${MINIO_BUCKET:-zhiguang}"
readonly DATABASE_NAME="zhiguang"
readonly SEARCH_INDEX="zhiguang_content_index"
readonly RETAINED_STATUS="published"
readonly POST_TEXT_EXPORT_PATH="/tmp/zhiguang-retained-post-text.csv"

EXECUTE=false
ARCHIVE_DIR=""
COMPOSE_ARGS=(-f "$PROJECT_DIR/docker-compose.yml")
if [[ -f "$PROJECT_DIR/docker-compose.prod.yml" ]]; then
  COMPOSE_ARGS+=(-f "$PROJECT_DIR/docker-compose.prod.yml")
fi

log() {
  printf '[content-reset] %s\n' "$*" >&2
}

die() {
  log "失败：$*"
  exit 1
}

usage() {
  cat <<'USAGE'
用法：
  deploy/content-only-reset.sh [--dry-run]
  CONFIRM_CONTENT_ONLY_RESET=published-posts-with-full-authors \
    deploy/content-only-reset.sh --execute [--archive-dir <目录>]

策略固定为：
  - 保留 status=published 的 know_posts；
  - 保留这些帖子作者的完整 users 行；
  - 保留对应 Cassandra 正文与 MinIO posts/<postId>/ 对象；
  - 清空其他业务数据、派生数据、缓存和事件状态；
  - 仅在全部验收通过后删除临时归档。
USAGE
}

compose() {
  docker compose "${COMPOSE_ARGS[@]}" "$@"
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "缺少命令：$1"
}

mysql_query() {
  local sql="$1"
  docker exec "$MYSQL_CONTAINER" sh -c \
    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -D zhiguang -Nse "$1"' \
    sh "$sql"
}

mysql_system_query() {
  local sql="$1"
  docker exec "$MYSQL_CONTAINER" sh -c \
    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -Nse "$1"' \
    sh "$sql"
}

mysql_dump() {
  local table="$1"
  local where_clause="$2"
  local destination="$3"
  docker exec "$MYSQL_CONTAINER" sh -c \
    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction --skip-triggers --no-create-info --skip-add-drop-table --skip-comments --where="$1" zhiguang "$2"' \
    sh "$where_clause" "$table" > "$destination"
}

mysql_import() {
  local source_file="$1"
  local container_file="/tmp/$(basename "$source_file")"
  docker cp "$source_file" "$MYSQL_CONTAINER:$container_file"
  docker exec "$MYSQL_CONTAINER" sh -c \
    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -D zhiguang < "$1"' \
    sh "$container_file"
}

minio_exec() {
  docker exec "$MINIO_CONTAINER" sh -c \
    'mc alias set local http://127.0.0.1:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null && exec "$@"' \
    sh "$@"
}

container_health() {
  docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$1" 2>/dev/null || true
}

wait_for_health() {
  local container="$1"
  local timeout_seconds="$2"
  local deadline=$((SECONDS + timeout_seconds))
  local health

  while (( SECONDS < deadline )); do
    health="$(container_health "$container")"
    if [[ "$health" == "healthy" ]]; then
      return 0
    fi
    if [[ "$health" == "exited" || "$health" == "dead" ]]; then
      die "容器 $container 未能启动，状态为 $health"
    fi
    sleep 3
  done
  die "等待容器 $container 健康超时，最后状态：${health:-unknown}"
}

read_single_value() {
  local value
  value="$(mysql_query "$1")"
  [[ "$value" =~ ^[0-9]+$ ]] || die "预检查询未返回预期数值：$1"
  printf '%s\n' "$value"
}
manifest_value() {
  local expected_key="$1"
  local key value
  while IFS='=' read -r key value; do
    if [[ "$key" == "$expected_key" ]]; then
      printf '%s\n' "$value"
      return 0
    fi
  done < "$ARCHIVE_DIR/manifest.properties"
  die "归档清单缺少字段：$expected_key"
}


preflight() {
  require_command docker
  for container in "$MYSQL_CONTAINER" "$REDIS_CONTAINER" "$KAFKA_CONTAINER" "$CASSANDRA_CONTAINER" "$ELASTICSEARCH_CONTAINER" "$MINIO_CONTAINER" "$CANAL_CONTAINER" "$GORSE_CONTAINER" "$APP_CONTAINER"; do
    docker inspect "$container" >/dev/null 2>&1 || die "未找到容器：$container"
  done

  local published_count author_count content_object_count
  published_count="$(read_single_value "SELECT COUNT(*) FROM know_posts WHERE status = '$RETAINED_STATUS'")"
  author_count="$(read_single_value "SELECT COUNT(DISTINCT creator_id) FROM know_posts WHERE status = '$RETAINED_STATUS'")"
  content_object_count="$(read_single_value "SELECT COUNT(*) FROM know_posts WHERE status = '$RETAINED_STATUS' AND content_object_key IS NOT NULL")"

  (( published_count > 0 )) || die "没有可保留的已发布帖子"
  (( author_count > 0 )) || die "已发布帖子没有作者"
  (( content_object_count == published_count )) || die "存在没有正文对象键的已发布帖子"

  minio_exec mc stat "local/$MINIO_BUCKET" >/dev/null || die "MinIO bucket 不存在：$MINIO_BUCKET"

  log "预检通过：保留 $published_count 篇已发布帖子、$author_count 个完整作者账号、$content_object_count 个正文对象。"
}

create_archive() {
  ARCHIVE_DIR="${ARCHIVE_DIR:-$(mktemp -d "$PROJECT_DIR/.content-reset-archive.XXXXXX")}" 
  mkdir -p "$ARCHIVE_DIR"

  log "创建临时语义归档：$ARCHIVE_DIR"
  mysql_query "SELECT id FROM know_posts WHERE status = '$RETAINED_STATUS' ORDER BY id" > "$ARCHIVE_DIR/published-post-ids.txt"
  mysql_query "SELECT id FROM know_posts WHERE status <> '$RETAINED_STATUS' ORDER BY id" > "$ARCHIVE_DIR/discarded-post-ids.txt"
  mysql_query "SELECT content_object_key FROM know_posts WHERE status = '$RETAINED_STATUS' AND content_object_key IS NOT NULL ORDER BY id" > "$ARCHIVE_DIR/required-object-keys.txt"

  mysql_dump \
    users \
    "id IN (SELECT creator_id FROM (SELECT DISTINCT creator_id FROM know_posts WHERE status = '$RETAINED_STATUS') retained_creators)" \
    "$ARCHIVE_DIR/users.sql"
  mysql_dump know_posts "status = '$RETAINED_STATUS'" "$ARCHIVE_DIR/know_posts.sql"


  minio_exec mc find "local/$MINIO_BUCKET" > "$ARCHIVE_DIR/minio-object-refs.txt"
  validate_archive

  {
    printf 'retention_status=%s\n' "$RETAINED_STATUS"
    printf 'published_posts=%s\n' "$(wc -l < "$ARCHIVE_DIR/published-post-ids.txt")"
    printf 'retained_authors=%s\n' "$(read_single_value "SELECT COUNT(DISTINCT creator_id) FROM know_posts WHERE status = '$RETAINED_STATUS'")"
    printf 'discarded_posts=%s\n' "$(wc -l < "$ARCHIVE_DIR/discarded-post-ids.txt")"
    printf 'required_content_objects=%s\n' "$(wc -l < "$ARCHIVE_DIR/required-object-keys.txt")"
    printf 'created_at_utc=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  } > "$ARCHIVE_DIR/manifest.properties"
  sha256sum "$ARCHIVE_DIR"/*.sql "$ARCHIVE_DIR"/*.txt "$ARCHIVE_DIR"/*.properties > "$ARCHIVE_DIR/manifest.sha256"
}

validate_archive() {
  local published_count author_count archived_post_count archived_author_count
  published_count="$(read_single_value "SELECT COUNT(*) FROM know_posts WHERE status = '$RETAINED_STATUS'")"
  author_count="$(read_single_value "SELECT COUNT(DISTINCT creator_id) FROM know_posts WHERE status = '$RETAINED_STATUS'")"
  archived_post_count="$(wc -l < "$ARCHIVE_DIR/published-post-ids.txt")"
  archived_author_count="$(mysql_query "SELECT COUNT(*) FROM users WHERE id IN (SELECT DISTINCT creator_id FROM know_posts WHERE status = '$RETAINED_STATUS')")"

  [[ "$published_count" == "$archived_post_count" ]] || die "帖子归档数量不一致"
  [[ "$author_count" == "$archived_author_count" ]] || die "作者归档数量不一致"
  [[ -s "$ARCHIVE_DIR/users.sql" && -s "$ARCHIVE_DIR/know_posts.sql" ]] || die "归档文件为空"

  local -A available_objects=()
  local object_ref object_key required_key
  while IFS= read -r object_ref; do
    object_key="${object_ref#local/$MINIO_BUCKET/}"
    available_objects["$object_key"]=1
  done < "$ARCHIVE_DIR/minio-object-refs.txt"
  while IFS= read -r required_key; do
    [[ -n "$required_key" ]] || continue
    [[ -n "${available_objects[$required_key]:-}" ]] || die "缺少正文对象：$required_key"
  done < "$ARCHIVE_DIR/required-object-keys.txt"
}

remove_volume_if_present() {
  local volume="$1"
  if docker volume inspect "$volume" >/dev/null 2>&1; then
    docker volume rm "$volume" >/dev/null
  fi
}

reset_ephemeral_stores() {
  log "停止写入服务与派生服务。"
  compose stop app canal gorse redis elasticsearch kafka
  compose rm -sf canal gorse redis elasticsearch kafka

  remove_volume_if_present "${PROJECT_NAME}_canal-data"
  remove_volume_if_present "${PROJECT_NAME}_canal-logs"
  remove_volume_if_present "${PROJECT_NAME}_gorse-data"
  remove_volume_if_present "${PROJECT_NAME}_gorse-logs"
  remove_volume_if_present "${PROJECT_NAME}_redis-data"
  remove_volume_if_present "${PROJECT_NAME}_elasticsearch-data"

  log "重建 Redis、Kafka 与 Elasticsearch 的空数据面，并原地保留 Cassandra 帖子正文。"
  compose up -d --no-build redis kafka elasticsearch minio
  compose restart cassandra
  wait_for_health "$REDIS_CONTAINER" 120
  wait_for_health "$KAFKA_CONTAINER" 180
  wait_for_health "$CASSANDRA_CONTAINER" 300
  wait_for_health "$ELASTICSEARCH_CONTAINER" 300
  wait_for_health "$MINIO_CONTAINER" 120
}

rebuild_mysql_schema() {
  log "使用当前 schema 重建 MySQL 业务库。"
  mysql_system_query "DROP DATABASE IF EXISTS $DATABASE_NAME; CREATE DATABASE $DATABASE_NAME CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"

  docker cp "$PROJECT_DIR/db/schema.sql" "$MYSQL_CONTAINER:/tmp/current-schema.sql"
  docker exec "$MYSQL_CONTAINER" sh -c \
    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -D zhiguang < /tmp/current-schema.sql'

  mysql_import "$ARCHIVE_DIR/users.sql"
  mysql_import "$ARCHIVE_DIR/know_posts.sql"
  mysql_query "UPDATE know_posts SET publish_attempt_id = NULL, publish_failed_reason = NULL WHERE status = '$RETAINED_STATUS'" >/dev/null
}

rebuild_cassandra_schema() {
  log "保留 Cassandra 帖子正文，清空评论正文与时间线派生表。"
  docker cp "$PROJECT_DIR/db/cassandra/init.cql" "$CASSANDRA_CONTAINER:/tmp/current-cassandra-init.cql"
  docker exec "$CASSANDRA_CONTAINER" cqlsh -f /tmp/current-cassandra-init.cql
  docker exec "$CASSANDRA_CONTAINER" cqlsh -e \
    "TRUNCATE zhiguang.comment_text_by_comment_id; TRUNCATE zhiguang.feed_inbox; TRUNCATE zhiguang.feed_author_feed"

  local post_id
  while IFS= read -r post_id; do
    [[ -n "$post_id" ]] || continue
    docker exec "$CASSANDRA_CONTAINER" cqlsh -e \
      "DELETE FROM zhiguang.post_text_by_post_id WHERE post_id = $post_id"
  done < "$ARCHIVE_DIR/discarded-post-ids.txt"
}

clear_redis() {
  docker exec "$REDIS_CONTAINER" redis-cli -n 0 FLUSHDB >/dev/null
}

remove_unretained_minio_objects() {
  log "删除不属于已发布帖子对象前缀的 MinIO 数据。"
  local -A retained_post_ids=()
  local post_id object_ref object_key
  while IFS= read -r post_id; do
    [[ -n "$post_id" ]] && retained_post_ids["$post_id"]=1
  done < "$ARCHIVE_DIR/published-post-ids.txt"

  while IFS= read -r object_ref; do
    object_key="${object_ref#local/$MINIO_BUCKET/}"
    if [[ "$object_key" =~ ^posts/([0-9]+)/ ]]; then
      post_id="${BASH_REMATCH[1]}"
      [[ -n "${retained_post_ids[$post_id]:-}" ]] && continue
    fi
    minio_exec mc rm --force "$object_ref" >/dev/null
  done < "$ARCHIVE_DIR/minio-object-refs.txt"
}

search_count() {
  local response
  response="$(docker exec "$ELASTICSEARCH_CONTAINER" curl -sS "http://127.0.0.1:9200/$SEARCH_INDEX/_count")"
  if [[ "$response" =~ \"count\":([0-9]+) ]]; then
    printf '%s\n' "${BASH_REMATCH[1]}"
    return
  fi
  if [[ "$response" == *"index_not_found_exception"* ]]; then
    printf '0\n'
    return
  fi
  die "无法解析 Elasticsearch 文档数：$response"
}

wait_for_search_backfill() {
  local expected_count="$1"
  local deadline=$((SECONDS + 900))
  local actual_count

  while (( SECONDS < deadline )); do
    actual_count="$(search_count)"
    if [[ "$actual_count" == "$expected_count" ]]; then
      return 0
    fi
    if (( actual_count > expected_count )); then
      die "Elasticsearch 文档数异常：$actual_count > $expected_count"
    fi
    sleep 5
  done
  die "等待 Elasticsearch 回灌超时"
}

start_application_stack() {
  compose up -d --no-build canal gorse
  wait_for_health "$CANAL_CONTAINER" 180
  wait_for_health "$GORSE_CONTAINER" 240

  compose up -d --no-build app
  wait_for_health "$APP_CONTAINER" 300
}

validate_rebuild() {
  local expected_posts expected_authors actual_posts actual_authors
  expected_posts="$(manifest_value published_posts)"
  expected_authors="$(manifest_value retained_authors)"
  actual_posts="$(read_single_value "SELECT COUNT(*) FROM know_posts WHERE status = '$RETAINED_STATUS'")"
  actual_authors="$(read_single_value "SELECT COUNT(*) FROM users")"
  [[ "$actual_posts" == "$expected_posts" ]] || die "重建后帖子数量不一致"
  [[ "$actual_authors" == "$expected_authors" ]] || die "重建后作者数量不一致"

  validate_minio_retention
  wait_for_search_backfill "$expected_posts"
  docker exec "$APP_CONTAINER" curl -fsS http://127.0.0.1:8080/actuator/health >/dev/null
  log "重建验收通过：$actual_posts 篇帖子、$actual_authors 个完整作者账号、$(search_count) 个搜索文档。"
}

validate_minio_retention() {
  local -A retained_post_ids=()
  local -A available_objects=()
  local post_id object_ref object_key required_key

  while IFS= read -r post_id; do
    [[ -n "$post_id" ]] && retained_post_ids["$post_id"]=1
  done < "$ARCHIVE_DIR/published-post-ids.txt"
  while IFS= read -r object_ref; do
    object_key="${object_ref#local/$MINIO_BUCKET/}"
    available_objects["$object_key"]=1
    if [[ "$object_key" =~ ^posts/([0-9]+)/ ]]; then
      post_id="${BASH_REMATCH[1]}"
      [[ -n "${retained_post_ids[$post_id]:-}" ]] || die "MinIO 仍保留未发布帖子对象：$object_key"
    else
      die "MinIO 仍保留非帖子对象：$object_key"
    fi
  done < <(minio_exec mc find "local/$MINIO_BUCKET")

  while IFS= read -r required_key; do
    [[ -n "$required_key" ]] || continue
    [[ -n "${available_objects[$required_key]:-}" ]] || die "重建后缺少正文对象：$required_key"
  done < "$ARCHIVE_DIR/required-object-keys.txt"
}

cleanup_archive() {
  log "验收通过，删除临时归档：$ARCHIVE_DIR"
  rm -rf "$ARCHIVE_DIR"
}

on_error() {
  local exit_code="$?"
  log "重建中断；临时归档保留在 ${ARCHIVE_DIR:-未创建}，未自动删除。"
  exit "$exit_code"
}

main() {
  while (($#)); do
    case "$1" in
      --dry-run)
        EXECUTE=false
        ;;
      --execute)
        EXECUTE=true
        ;;
      --archive-dir)
        shift
        (($# > 0)) || die "--archive-dir 缺少目录参数"
        ARCHIVE_DIR="$1"
        ;;
      --help|-h)
        usage
        exit 0
        ;;
      *)
        die "未知参数：$1"
        ;;
    esac
    shift
  done

  preflight
  if [[ "$EXECUTE" != "true" ]]; then
    log "只读预检完成；未修改任何数据。"
    return 0
  fi

  [[ "${CONFIRM_CONTENT_ONLY_RESET:-}" == "published-posts-with-full-authors" ]] \
    || die "拒绝执行：请设置 CONFIRM_CONTENT_ONLY_RESET=published-posts-with-full-authors"

  trap on_error ERR
  create_archive
  reset_ephemeral_stores
  rebuild_mysql_schema
  rebuild_cassandra_schema
  clear_redis
  remove_unretained_minio_objects
  start_application_stack
  validate_rebuild
  cleanup_archive
}

main "$@"
