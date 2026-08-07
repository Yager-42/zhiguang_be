-- 种子数据合规校验（由 run.sh verify 渲染并执行）
-- 约定：所有 check_name 的 violations 应 = 0；seeded-users / large-follower-count 输出实际数量用于人工核对。
-- 任一 violations > 0 即判定种子数据不合规，需修复或重灌（./run.sh seed --force）。

SET @lo := __USER_ID_BASE__ + 1;
SET @hi := __USER_ID_BASE__ + __USER_N__ + 1;
SET @post_lo := __POST_ID_BASE__ + 1;
SET @post_hi := __POST_ID_BASE__ + __POST_N__;

-- 1. 自关注（业务层 follow 不拦截，种子数据必须为 0）
SELECT 'self-follow' AS check_name, COUNT(*) AS violations
FROM following WHERE from_user_id = to_user_id;

-- 2. 关注关系孤儿（from/to 不存在于 users）
SELECT 'orphan-following' AS check_name, COUNT(*) AS violations
FROM following f
LEFT JOIN users u1 ON u1.id = f.from_user_id
LEFT JOIN users u2 ON u2.id = f.to_user_id
WHERE u1.id IS NULL OR u2.id IS NULL;

-- 3. 帖子作者孤儿（外键应保证，防御性校验）
SELECT 'orphan-post-creator' AS check_name, COUNT(*) AS violations
FROM know_posts p LEFT JOIN users u ON u.id = p.creator_id
WHERE u.id IS NULL;

-- 4. 帖子状态 / 可见性非法（Feed 只认 published + public/followers，脏值会静默丢失）
SELECT 'illegal-post-status' AS check_name, COUNT(*) AS violations
FROM know_posts
WHERE status NOT IN ('draft','publishing','published','publish_failed','deleted');

SELECT 'illegal-post-visible' AS check_name, COUNT(*) AS violations
FROM know_posts
WHERE visible NOT IN ('public','followers','school','private','unlisted');

-- 5. 钱包孤儿 / 负余额（schema CHECK 约束兜底）
SELECT 'orphan-wallet' AS check_name, COUNT(*) AS violations
FROM wallet_account w LEFT JOIN users u ON u.id = w.owner_user_id
WHERE u.id IS NULL;

SELECT 'negative-balance' AS check_name, COUNT(*) AS violations
FROM wallet_account
WHERE available_balance < 0 OR held_balance < 0 OR escrowed_balance < 0;

-- 6. 字段长度越界（description VARCHAR(50)，title VARCHAR(256)）
SELECT 'long-description' AS check_name, COUNT(*) AS violations
FROM know_posts WHERE CHAR_LENGTH(description) > 50;

SELECT 'long-title' AS check_name, COUNT(*) AS violations
FROM know_posts WHERE CHAR_LENGTH(title) > 256;

-- 7. 标识符格式（登录依赖：phone 正则 ^1\d{10}$，email 正则）
SELECT 'bad-phone' AS check_name, COUNT(*) AS violations
FROM users WHERE phone IS NOT NULL AND phone NOT REGEXP '^1[0-9]{10}$';

SELECT 'bad-email' AS check_name, COUNT(*) AS violations
FROM users
WHERE email IS NOT NULL AND email NOT REGEXP '^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}$';

-- 8. 种子用户缺密码哈希（无法登录）
SELECT 'missing-password-hash' AS check_name, COUNT(*) AS violations
FROM users
WHERE id >= @lo AND id < @hi
  AND (password_hash IS NULL OR CHAR_LENGTH(password_hash) < 20);

-- 9. 种子帖 content_sha256 长度（CHAR(64)）
SELECT 'bad-sha256' AS check_name, COUNT(*) AS violations
FROM know_posts
WHERE id >= @post_lo AND id <= @post_hi AND CHAR_LENGTH(content_sha256) <> 64;

-- 10. 大V 粉丝块越界/自关注（id >= 9000000 为种子块专用区间）
SELECT 'large-follower-invalid' AS check_name, COUNT(*) AS violations
FROM following
WHERE id >= 9000000 AND id < 9100000
  AND (from_user_id < @lo OR from_user_id >= @hi OR from_user_id = to_user_id);

-- 11. 种子用户 / 大V 粉丝实际数量（人工核对期望值：__USER_N__ / __LARGE_FOLLOWER_N__）
SELECT 'seeded-users' AS check_name, COUNT(*) AS actual
FROM users WHERE id >= @lo AND id < @hi;

SELECT 'large-follower-count' AS check_name, COUNT(*) AS actual
FROM following WHERE id >= 9000000 AND id < 9100000;

-- 12. 推广种子状态（人工核对：竞价场景期望 1 / 1，非竞价场景允许 0）
SELECT 'promotion-campaign' AS check_name, COUNT(*) AS actual
FROM promotion_campaign WHERE id = 3000001;

SELECT 'promotion-window-open' AS check_name, COUNT(*) AS actual
FROM promotion_auction_window WHERE id = 3000002 AND status = 'OPEN';

-- 13. 评论链路完整性与状态分布
SELECT 'orphan-comment-creator' AS check_name, COUNT(*) AS violations
FROM comments c LEFT JOIN users u ON u.id = c.creator_id
WHERE u.id IS NULL;

SELECT 'orphan-pending-creator' AS check_name, COUNT(*) AS violations
FROM pending_comments p LEFT JOIN users u ON u.id = p.creator_id
WHERE u.id IS NULL;

SELECT 'orphan-comment-outbox' AS check_name, COUNT(*) AS violations
FROM comment_outbox o
LEFT JOIN pending_comments p ON p.pending_comment_id = o.aggregate_id
LEFT JOIN comments c ON c.comment_id = o.aggregate_id
WHERE p.pending_comment_id IS NULL AND c.comment_id IS NULL;

SELECT 'pending-status-distribution' AS check_name, status, COUNT(*) AS actual
FROM pending_comments GROUP BY status ORDER BY status;

SELECT 'outbox-state-distribution' AS check_name, event_type, state, COUNT(*) AS actual
FROM comment_outbox GROUP BY event_type, state ORDER BY event_type, state;
