-- 清理压测种子数据（run.sh seed --force 时执行）
-- 依赖顺序删除；范围按 __USER_ID_BASE__ .. __USER_ID_BASE__ + __USER_N__ - 1 与固定种子 ID。

SET @lo := __USER_ID_BASE__;
SET @hi := __USER_ID_BASE__ + __USER_N__;

DELETE FROM wallet_business_ref WHERE claimed_by_owner_user_id >= @lo AND claimed_by_owner_user_id < @hi;
DELETE FROM wallet_ledger WHERE owner_user_id >= @lo AND owner_user_id < @hi;
DELETE FROM wallet_escrow WHERE payer_user_id >= @lo AND payer_user_id < @hi;
DELETE FROM promotion_slot_allocation WHERE bidder_user_id >= @lo AND bidder_user_id < @hi;
DELETE FROM promotion_bid WHERE bidder_user_id >= @lo AND bidder_user_id < @hi;
DELETE FROM promotion_auction_window WHERE id = 3000002;
DELETE FROM promotion_campaign WHERE id = 3000001;
DELETE FROM publish_attempt WHERE creator_id >= @lo AND creator_id < @hi;
DELETE FROM comment_outbox
WHERE aggregate_id IN (
    SELECT pending_comment_id FROM pending_comments WHERE creator_id >= @lo AND creator_id < @hi
)
OR aggregate_id IN (
    SELECT comment_id FROM comments WHERE creator_id >= @lo AND creator_id < @hi
);
DELETE FROM comments WHERE creator_id >= @lo AND creator_id < @hi;
DELETE FROM pending_comments WHERE creator_id >= @lo AND creator_id < @hi;
DELETE FROM following WHERE from_user_id >= @lo AND from_user_id < @hi;
DELETE FROM follower WHERE from_user_id >= @lo AND from_user_id < @hi;
DELETE FROM notifications WHERE actor_user_id >= @lo AND actor_user_id < @hi;
DELETE FROM know_posts WHERE creator_id >= @lo AND creator_id < @hi;
DELETE FROM wallet_account WHERE owner_user_id >= @lo AND owner_user_id < @hi;
DELETE FROM users WHERE id >= @lo AND id < @hi;
