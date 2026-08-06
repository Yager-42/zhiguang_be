-- 压测种子关注关系（幂等：INSERT IGNORE）
-- 1) 每个种子用户关注 __FOLLOW_PER_USER__ 个其他用户（轮转公式与 gen_seed_cassandra.mjs 保持一致）
-- 2) 可选大V：当 __LARGE_FOLLOWER_N__ > 0 时，前 __LARGE_FOLLOWER_N__ 个用户关注 __LARGE_AUTHOR_ID__
--    （≥10000 粉即触发 fanout pull 模式与 author_head 读路径；需要先扩用户池到 ≥ 11000）
-- 注意：follow 写接口有令牌桶限流（容量 100 / 1 token/s/用户），但本脚本直插 SQL，不受限。

SET @user_n := __USER_N__;
SET @id_base := __USER_ID_BASE__;
SET @follow_per := __FOLLOW_PER_USER__;
SET @large_author := __LARGE_AUTHOR_ID__;
SET @large_followers := __LARGE_FOLLOWER_N__;

-- 普通关注（following 表）
INSERT IGNORE INTO following (id, from_user_id, to_user_id, rel_status, created_at, updated_at)
WITH RECURSIVE seq AS (
  SELECT 1 AS i UNION ALL SELECT i + 1 FROM seq WHERE i < @user_n
), seq2 AS (
  SELECT 1 AS j UNION ALL SELECT j + 1 FROM seq2 WHERE j < @follow_per
)
SELECT
  @id_base + (seq.i - 1) * @follow_per + seq2.j,
  @id_base + seq.i,
  @id_base + ((seq.i + seq2.j - 1) % @user_n) + 1,
  1,
  NOW(3),
  NOW(3)
FROM seq JOIN seq2 ON 1 = 1;

-- 普通关注镜像（follower 表）
INSERT IGNORE INTO follower (id, to_user_id, from_user_id, rel_status, created_at, updated_at)
WITH RECURSIVE seq AS (
  SELECT 1 AS i UNION ALL SELECT i + 1 FROM seq WHERE i < @user_n
), seq2 AS (
  SELECT 1 AS j UNION ALL SELECT j + 1 FROM seq2 WHERE j < @follow_per
)
SELECT
  @id_base + (seq.i - 1) * @follow_per + seq2.j,
  @id_base + ((seq.i + seq2.j - 1) % @user_n) + 1,
  @id_base + seq.i,
  1,
  NOW(3),
  NOW(3)
FROM seq JOIN seq2 ON 1 = 1;

-- 大V 粉丝（可选；@large_followers = 0 时 WHERE 常量为假，不插入）
-- 合规约束：
--   a) 排除 @large_author 自己（业务层 follow 不拦截自关注，种子数据必须自守）
--   b) from 必须落在种子用户池内（需 LARGE_FOLLOWER_N <= USER_N - 1，run.sh seed 已前置校验）
INSERT IGNORE INTO following (id, from_user_id, to_user_id, rel_status, created_at, updated_at)
WITH RECURSIVE seq AS (
  SELECT 1 AS i UNION ALL SELECT i + 1 FROM seq WHERE i < @large_followers
)
SELECT
  9000000 + i,
  @id_base + i,
  @large_author,
  1,
  NOW(3),
  NOW(3)
FROM seq
WHERE @large_followers > 0
  AND @id_base + i <> @large_author;

INSERT IGNORE INTO follower (id, to_user_id, from_user_id, rel_status, created_at, updated_at)
WITH RECURSIVE seq AS (
  SELECT 1 AS i UNION ALL SELECT i + 1 FROM seq WHERE i < @large_followers
)
SELECT
  9000000 + i,
  @large_author,
  @id_base + i,
  1,
  NOW(3),
  NOW(3)
FROM seq
WHERE @large_followers > 0
  AND @id_base + i <> @large_author;
