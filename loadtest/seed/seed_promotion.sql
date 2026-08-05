-- 压测种子推广数据（幂等：INSERT IGNORE）
-- 1 个 feed_top_slot 活动 + 1 个 OPEN 竞价窗口（窗口有效期 50 分钟，过期后需重新灌数）。
-- 竞价收单压测（scripts/promotion.js）依赖这两个 ID（k6 默认 PROMO_CAMPAIGN_ID=3000001, PROMO_WINDOW_ID=3000002）。

INSERT IGNORE INTO promotion_campaign (id, creator_user_id, post_id, resource_type, status, start_at, end_at, created_at, updated_at)
VALUES (3000001, __USER_ID_BASE__ + 1, __POST_ID_BASE__ + 1, 'feed_top_slot', 'ACTIVE',
        NOW(3), NOW(3) + INTERVAL 2 HOUR, NOW(3), NOW(3));

INSERT IGNORE INTO promotion_auction_window (id, resource_type, window_start_at, window_end_at, slot_count, reserve_price, status, settled_at, created_at, updated_at)
VALUES (3000002, 'feed_top_slot', NOW(3) - INTERVAL 10 MINUTE, NOW(3) + INTERVAL 50 MINUTE, 1, 1, 'OPEN', NULL, NOW(3), NOW(3));
