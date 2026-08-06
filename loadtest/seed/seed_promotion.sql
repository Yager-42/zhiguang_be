-- 压测种子推广数据（幂等：重复执行会刷新活动与竞价窗口有效期）
-- 1 个 feed_top_slot 活动 + 1 个 OPEN 竞价窗口（窗口有效期 50 分钟，过期后重新执行本脚本）。
-- 竞价收单压测（scripts/promotion.js）依赖这两个 ID（k6 默认 PROMO_CAMPAIGN_ID=3000001, PROMO_WINDOW_ID=3000002）。

INSERT INTO promotion_campaign (id, creator_user_id, post_id, resource_type, status, start_at, end_at, created_at, updated_at)
VALUES (3000001, __USER_ID_BASE__ + 1, __POST_ID_BASE__ + 1, 'FEED_TOP_SLOT', 'ACTIVE',
        NOW(3), NOW(3) + INTERVAL 2 HOUR, NOW(3), NOW(3))
ON DUPLICATE KEY UPDATE resource_type = 'FEED_TOP_SLOT', status = 'ACTIVE', start_at = NOW(3), end_at = NOW(3) + INTERVAL 2 HOUR, updated_at = NOW(3);

INSERT INTO promotion_auction_window (id, resource_type, window_start_at, window_end_at, slot_count, reserve_price, status, settled_at, created_at, updated_at)
VALUES (3000002, 'FEED_TOP_SLOT', NOW(3) - INTERVAL 10 MINUTE, NOW(3) + INTERVAL 50 MINUTE, 1, 1, 'OPEN', NULL, NOW(3), NOW(3))
ON DUPLICATE KEY UPDATE resource_type = 'FEED_TOP_SLOT', window_start_at = NOW(3) - INTERVAL 10 MINUTE, window_end_at = NOW(3) + INTERVAL 50 MINUTE,
                        slot_count = 1, reserve_price = 1, status = 'OPEN', settled_at = NULL, updated_at = NOW(3);
