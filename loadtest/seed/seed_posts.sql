-- 压测种子帖子（幂等：INSERT IGNORE）
-- __POST_N__ 篇 published 帖子：ID 从 __POST_ID_BASE__ + 1 起；作者从种子用户轮转；
-- 第一篇（POST_ID_BASE+1）为热帖（k6 默认 HOT_POST_ID 指向它），publish_time 倒序保证 Feed 排序稳定。
-- content_url 指向应用自身 /actuator/health：仅当 Cassandra 正文缺失时才回源（种子已灌正文则不会走到）；
-- 指向本机可避免压测流量打到外网。
-- ES 索引：应用启动时若索引为空会自动从本表回填（SearchIndexInitializer），灌数后重启应用一次。

SET @post_n := __POST_N__;
SET @post_base := __POST_ID_BASE__;
SET @user_base := __USER_ID_BASE__;
SET @user_n := __USER_N__;

INSERT IGNORE INTO know_posts (
  id, tag_id, tags, title, description, content_url, content_object_key,
  content_etag, content_size, content_sha256, creator_id, is_top, type, visible,
  img_urls, video_url, status, publish_attempt_id, publish_failed_reason,
  create_time, update_time, publish_time
)
WITH RECURSIVE seq AS (
  SELECT 1 AS i
  UNION ALL
  SELECT i + 1 FROM seq WHERE i < @post_n
)
SELECT
  @post_base + i,
  NULL,
  JSON_ARRAY('压测', 'loadtest'),
  CONCAT('loadtest post ', i),
  CONCAT('压测帖子描述 ', i),
  'http://localhost:8080/actuator/health',
  NULL,
  NULL,
  0,
  REPEAT('0', 64),
  @user_base + ((i - 1) % @user_n) + 1,
  0,
  'image_text',
  'public',
  NULL,
  NULL,
  'published',
  NULL,
  NULL,
  NOW(3) - INTERVAL (@post_n - i) MINUTE,
  NOW(3) - INTERVAL (@post_n - i) MINUTE,
  NOW(3) - INTERVAL (@post_n - i) MINUTE
FROM seq;
