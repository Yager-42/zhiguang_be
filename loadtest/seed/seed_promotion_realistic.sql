-- Dedicated single-room data for the stateful WebSocket auction scenario.
-- Re-running refreshes the window and campaigns without deleting existing load-test data.
SET @realistic_window_id := __PROMOTION_WINDOW_ID__;
SET @realistic_campaign_base := __PROMOTION_CAMPAIGN_BASE__;
SET @realistic_campaign_n := __PROMOTION_CAMPAIGN_N__;
SET @realistic_user_base := __USER_ID_BASE__;
SET @realistic_user_pool_n := __USER_POOL_N__;
SET @realistic_post_base := __POST_ID_BASE__;
SET @realistic_post_n := __POST_N__;

INSERT INTO promotion_auction_window (
    id, resource_type, window_start_at, window_end_at, slot_count, reserve_price,
    decision_path, status, settled_at, created_at, updated_at
)
VALUES (
    @realistic_window_id, 'FEED_TOP_SLOT', NOW(3) - INTERVAL 1 MINUTE,
    NOW(3) + INTERVAL __PROMOTION_WINDOW_MINUTES__ MINUTE,
    1, 1, 'REDIS_STREAM', 'OPEN', NULL, NOW(3), NOW(3)
)
ON DUPLICATE KEY UPDATE
    resource_type = 'FEED_TOP_SLOT',
    window_start_at = NOW(3) - INTERVAL 1 MINUTE,
    window_end_at = NOW(3) + INTERVAL __PROMOTION_WINDOW_MINUTES__ MINUTE,
    slot_count = 1,
    reserve_price = 1,
    decision_path = 'REDIS_STREAM',
    status = 'OPEN',
    settled_at = NULL,
    updated_at = NOW(3);

INSERT INTO promotion_campaign (
    id, creator_user_id, post_id, resource_type, status,
    start_at, end_at, created_at, updated_at
)
WITH RECURSIVE seq AS (
    SELECT 1 AS i
    UNION ALL
    SELECT i + 1 FROM seq WHERE i < @realistic_campaign_n
)
SELECT
    @realistic_campaign_base + i,
    @realistic_user_base + ((i - 1) % @realistic_user_pool_n) + 1,
    @realistic_post_base + ((i - 1) % @realistic_post_n) + 1,
    'FEED_TOP_SLOT',
    'ACTIVE',
    NOW(3),
    NOW(3) + INTERVAL 2 HOUR,
    NOW(3),
    NOW(3)
FROM seq
ON DUPLICATE KEY UPDATE
    creator_user_id = VALUES(creator_user_id),
    post_id = VALUES(post_id),
    resource_type = 'FEED_TOP_SLOT',
    status = 'ACTIVE',
    start_at = NOW(3),
    end_at = NOW(3) + INTERVAL 2 HOUR,
    updated_at = NOW(3);
