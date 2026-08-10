-- 压测种子推广数据（幂等：重复执行会刷新活动与竞价窗口有效期）
-- 基础活动、性能活动池 + feed/search 两个 OPEN 竞价窗口（有效期 50 分钟，过期后重新执行）。
-- 竞价 WebSocket 压测依赖该种子窗口；批量高活动场景由下方 campaign 模板扩展。

INSERT INTO promotion_campaign (id, creator_user_id, post_id, resource_type, status, start_at, end_at, created_at, updated_at)
VALUES (3000001, __USER_ID_BASE__ + 1, __POST_ID_BASE__ + 1, 'FEED_TOP_SLOT', 'ACTIVE',
        NOW(3), NOW(3) + INTERVAL 2 HOUR, NOW(3), NOW(3))
ON DUPLICATE KEY UPDATE resource_type = 'FEED_TOP_SLOT', status = 'ACTIVE', start_at = NOW(3), end_at = NOW(3) + INTERVAL 2 HOUR, updated_at = NOW(3);

INSERT INTO promotion_auction_window (id, resource_type, window_start_at, window_end_at, slot_count, reserve_price, decision_path, status, settled_at, created_at, updated_at)
VALUES (3000002, 'FEED_TOP_SLOT', NOW(3) - INTERVAL 10 MINUTE, NOW(3) + INTERVAL 50 MINUTE, 1, 1, 'REDIS_STREAM', 'OPEN', NULL, NOW(3), NOW(3))
ON DUPLICATE KEY UPDATE resource_type = 'FEED_TOP_SLOT', window_start_at = NOW(3) - INTERVAL 10 MINUTE, window_end_at = NOW(3) + INTERVAL 50 MINUTE,
                        slot_count = 1, reserve_price = 1, decision_path = 'REDIS_STREAM', status = 'OPEN', settled_at = NULL, updated_at = NOW(3);

INSERT INTO promotion_auction_window (id, resource_type, window_start_at, window_end_at, slot_count, reserve_price, decision_path, status, settled_at, created_at, updated_at)
VALUES (3000003, 'SEARCH_TOP_SLOT', NOW(3) - INTERVAL 10 MINUTE, NOW(3) + INTERVAL 50 MINUTE, 1, 1, 'REDIS_STREAM', 'OPEN', NULL, NOW(3), NOW(3))
ON DUPLICATE KEY UPDATE resource_type = 'SEARCH_TOP_SLOT', window_start_at = NOW(3) - INTERVAL 10 MINUTE, window_end_at = NOW(3) + INTERVAL 50 MINUTE,
                        slot_count = 1, reserve_price = 1, decision_path = 'REDIS_STREAM', status = 'OPEN', settled_at = NULL, updated_at = NOW(3);

SET @promotion_campaign_n := __PROMOTION_CAMPAIGN_N__;
SET @promotion_user_base := __USER_ID_BASE__;
SET @promotion_post_base := __POST_ID_BASE__;

INSERT INTO promotion_campaign (id, creator_user_id, post_id, resource_type, status, start_at, end_at, created_at, updated_at)
WITH RECURSIVE seq AS (
  SELECT 1 AS i
  UNION ALL
  SELECT i + 1 FROM seq WHERE i < @promotion_campaign_n
)
SELECT 3100000 + i, @promotion_user_base + i, @promotion_post_base + i, 'FEED_TOP_SLOT', 'ACTIVE',
       NOW(3), NOW(3) + INTERVAL 2 HOUR, NOW(3), NOW(3)
FROM seq
ON DUPLICATE KEY UPDATE resource_type = 'FEED_TOP_SLOT', status = 'ACTIVE', start_at = NOW(3),
                        end_at = NOW(3) + INTERVAL 2 HOUR, updated_at = NOW(3);

INSERT INTO promotion_campaign (id, creator_user_id, post_id, resource_type, status, start_at, end_at, created_at, updated_at)
WITH RECURSIVE seq AS (
  SELECT 1 AS i
  UNION ALL
  SELECT i + 1 FROM seq WHERE i < @promotion_campaign_n
)
SELECT 3200000 + i, @promotion_user_base + i, @promotion_post_base + i, 'SEARCH_TOP_SLOT', 'ACTIVE',
       NOW(3), NOW(3) + INTERVAL 2 HOUR, NOW(3), NOW(3)
FROM seq
ON DUPLICATE KEY UPDATE resource_type = 'SEARCH_TOP_SLOT', status = 'ACTIVE', start_at = NOW(3),
                        end_at = NOW(3) + INTERVAL 2 HOUR, updated_at = NOW(3);

-- 独立活动池避免场景之间共享竞价热状态：330=容量，350/360=多房间接受，
-- 370/380=多房间拒绝，390=WebSocket 单房间网关快拒。
INSERT INTO promotion_campaign (id, creator_user_id, post_id, resource_type, status, start_at, end_at, created_at, updated_at)
WITH RECURSIVE seq AS (SELECT 1 AS i UNION ALL SELECT i + 1 FROM seq WHERE i < @promotion_campaign_n),
pools AS (
  SELECT 3300000 AS id_base, 'FEED_TOP_SLOT' AS resource_type
  UNION ALL SELECT 3500000, 'FEED_TOP_SLOT'
  UNION ALL SELECT 3600000, 'SEARCH_TOP_SLOT'
  UNION ALL SELECT 3700000, 'FEED_TOP_SLOT'
  UNION ALL SELECT 3800000, 'SEARCH_TOP_SLOT'
  UNION ALL SELECT 3900000, 'FEED_TOP_SLOT'
)
SELECT pools.id_base + seq.i, @promotion_user_base + seq.i, @promotion_post_base + seq.i,
       pools.resource_type, 'ACTIVE', NOW(3), NOW(3) + INTERVAL 2 HOUR, NOW(3), NOW(3)
FROM seq CROSS JOIN pools
WHERE TRUE
ON DUPLICATE KEY UPDATE resource_type = VALUES(resource_type), status = 'ACTIVE', start_at = NOW(3),
                        end_at = NOW(3) + INTERVAL 2 HOUR, updated_at = NOW(3);
