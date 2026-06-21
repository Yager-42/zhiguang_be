-- MySQL 8.0 schema for ZhiGuang authentication service

CREATE TABLE IF NOT EXISTS users (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    phone VARCHAR(32) NULL,
    email VARCHAR(128) NULL,
    password_hash VARCHAR(128) NULL,
    nickname VARCHAR(64) NOT NULL,
    avatar TEXT NULL,
    bio VARCHAR(512) NULL,
    zg_id VARCHAR(64) NULL,
    gender VARCHAR(16) NULL,
    birthday DATE NULL,
    school VARCHAR(128) NULL,
    tags_json JSON NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_phone (phone),
    UNIQUE KEY uk_users_email (email),
    UNIQUE KEY uk_users_zg_id (zg_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS login_logs (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NULL,
    identifier VARCHAR(128) NOT NULL,
    channel VARCHAR(32) NOT NULL,
    ip VARCHAR(45) NULL,
    user_agent VARCHAR(512) NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY ix_login_logs_user_created_at (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 知文（KnowPost）主表
-- 说明：
-- - id 使用雪花算法在业务层生成（非自增）；
-- - tags、img_urls 使用 JSON 存储，兼容多标签/多图片；
-- - content 存储在 OSS，仅记录 URL 与校验信息；
-- - 一期类型仅 image_text，可扩展；
-- - 状态包含草稿/发布中/已发布/发布失败/驳回/删除；
CREATE TABLE IF NOT EXISTS know_posts (
    id BIGINT UNSIGNED NOT NULL,
    tag_id BIGINT UNSIGNED NULL COMMENT '主分类/内容分类ID',
    tags JSON NULL COMMENT '标签名数组，例如 ["java","编程"]',
    title VARCHAR(256) NULL,
    description VARCHAR(50) NULL COMMENT '摘要/描述，最多50字',
    content_url TEXT NULL COMMENT '正文存储于OSS的访问URL或签名URL',
    content_object_key VARCHAR(512) NULL COMMENT 'OSS对象Key',
    content_etag VARCHAR(128) NULL COMMENT 'OSS ETag（用于校验）',
    content_size BIGINT UNSIGNED NULL COMMENT '正文字节大小',
    content_sha256 CHAR(64) NULL COMMENT '正文SHA-256哈希（hex）',
    creator_id BIGINT UNSIGNED NOT NULL,
    is_top TINYINT(1) NOT NULL DEFAULT 0,
    type VARCHAR(32) NOT NULL DEFAULT 'image_text',
    visible VARCHAR(32) NOT NULL DEFAULT 'public',
    img_urls JSON NULL COMMENT '图片URL数组或对象数组',
    video_url TEXT NULL COMMENT '视频URL（一期不使用）',
    status VARCHAR(16) NOT NULL DEFAULT 'draft',
    publish_attempt_id BIGINT UNSIGNED NULL COMMENT '当前或最近一次发布尝试ID',
    publish_failed_reason VARCHAR(512) NULL COMMENT '最近一次发布失败原因',
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    publish_time TIMESTAMP NULL DEFAULT NULL,
    PRIMARY KEY (id),
    KEY ix_know_posts_creator_ct (creator_id, create_time),
    KEY ix_know_posts_status_ct (status, create_time),
    KEY ix_know_posts_publish_attempt (publish_attempt_id),
    KEY ix_know_posts_tag_ct (tag_id, create_time),
    KEY ix_know_posts_top_ct (is_top, create_time),
    KEY ix_know_posts_creator_status_pub (creator_id, status, publish_time),
    CONSTRAINT fk_know_posts_creator FOREIGN KEY (creator_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS publish_attempt (
    attempt_id BIGINT UNSIGNED NOT NULL,
    post_id BIGINT UNSIGNED NOT NULL,
    creator_id BIGINT UNSIGNED NOT NULL,
    idempotent_key VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    failed_step VARCHAR(64) NULL,
    error_message VARCHAR(1024) NULL,
    fallback_task_type VARCHAR(64) NULL,
    fallback_target_type VARCHAR(64) NULL,
    fallback_target_id BIGINT UNSIGNED NULL,
    fallback_failure_reason VARCHAR(1024) NULL,
    fallback_next_retry_at TIMESTAMP NULL DEFAULT NULL,
    retry_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (attempt_id),
    UNIQUE KEY uk_publish_attempt_creator_post_key (creator_id, post_id, idempotent_key),
    KEY ix_publish_attempt_post (post_id),
    KEY ix_publish_attempt_creator_status (creator_id, status),
    CONSTRAINT fk_publish_attempt_post FOREIGN KEY (post_id) REFERENCES know_posts(id),
    CONSTRAINT fk_publish_attempt_creator FOREIGN KEY (creator_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS outbox (
    id BIGINT UNSIGNED NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id BIGINT UNSIGNED NULL,
    type VARCHAR(64) NOT NULL,
    payload JSON NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY ix_outbox_agg (aggregate_type, aggregate_id),
    KEY ix_outbox_ct (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS comments (
    comment_id BIGINT UNSIGNED NOT NULL,
    post_id BIGINT UNSIGNED NOT NULL,
    root_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
    parent_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
    creator_id BIGINT UNSIGNED NOT NULL,
    client_request_id VARCHAR(64) NOT NULL,
    status TINYINT NOT NULL DEFAULT 0,
    like_count INT NOT NULL DEFAULT 0,
    reply_count INT NOT NULL DEFAULT 0,
    create_time DATETIME(3) NOT NULL,
    update_time DATETIME(3) NOT NULL,
    PRIMARY KEY (comment_id),
    UNIQUE KEY uk_comment_creator_client_request (creator_id, client_request_id),
    KEY idx_post_comments (post_id, parent_id, create_time, comment_id),
    KEY idx_root_replies (root_id, create_time, comment_id),
    KEY idx_creator (creator_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS pending_comments (
    pending_comment_id BIGINT UNSIGNED NOT NULL,
    post_id BIGINT UNSIGNED NOT NULL,
    creator_id BIGINT UNSIGNED NOT NULL,
    client_request_id VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'pending',
    create_time DATETIME(3) NOT NULL,
    update_time DATETIME(3) NOT NULL,
    PRIMARY KEY (pending_comment_id),
    UNIQUE KEY uk_pending_comment_client_request (creator_id, client_request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS reconciliation_task (
    id BIGINT UNSIGNED NOT NULL,
    task_type VARCHAR(64) NOT NULL,
    target_type VARCHAR(32) NOT NULL,
    target_id BIGINT UNSIGNED NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'pending',
    retry_count INT NOT NULL DEFAULT 0,
    next_execute_at DATETIME(3) NOT NULL,
    execution_duration_ms BIGINT NULL,
    dedupe_scope VARCHAR(180) NULL,
    task_payload TEXT NULL,
    last_error VARCHAR(512) NULL,
    active_dedupe_scope VARCHAR(180)
        GENERATED ALWAYS AS (
            CASE
                WHEN status IN ('pending', 'running') THEN dedupe_scope
                ELSE NULL
            END
        ) STORED,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_reconciliation_task_scheduled (status, next_execute_at),
    KEY idx_reconciliation_task_target (target_type, target_id, task_type),
    UNIQUE KEY uk_reconciliation_task_active_dedupe_scope (active_dedupe_scope)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS reconciliation_checkpoint (
    scan_type VARCHAR(64) NOT NULL,
    last_scanned_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (scan_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS reconciliation_error_log (
    id BIGINT UNSIGNED NOT NULL,
    task_id BIGINT UNSIGNED NOT NULL,
    execution_duration_ms BIGINT NULL,
    error_message TEXT NULL,
    stack_trace TEXT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_reconciliation_error_log_task_id (task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS following (
    id BIGINT UNSIGNED NOT NULL,
    from_user_id BIGINT UNSIGNED NOT NULL,
    to_user_id BIGINT UNSIGNED NOT NULL,
    rel_status TINYINT NOT NULL DEFAULT 1,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_from_to (from_user_id, to_user_id),
    KEY idx_from_created (from_user_id, created_at, to_user_id, rel_status),
    KEY idx_to (to_user_id, from_user_id, rel_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS follower (
    id BIGINT UNSIGNED NOT NULL,
    to_user_id BIGINT UNSIGNED NOT NULL,
    from_user_id BIGINT UNSIGNED NOT NULL,
    rel_status TINYINT NOT NULL DEFAULT 1,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_to_from (to_user_id, from_user_id),
    KEY idx_to_created (to_user_id, created_at, from_user_id, rel_status),
    KEY idx_from (from_user_id, to_user_id, rel_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS leaf_alloc (
    biz_tag VARCHAR(128) NOT NULL,
    max_id BIGINT NOT NULL DEFAULT 1,
    step INT NOT NULL DEFAULT 1000,
    description VARCHAR(256) NULL,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (biz_tag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO leaf_alloc (biz_tag, max_id, step, description) VALUES
    ('reconciliation_task', 1, 1000, 'Reconciliation task ID'),
    ('admin_operation', 1, 1000, 'Admin operation ID'),
    ('audit_log', 1, 1000, 'Audit log ID')
ON DUPLICATE KEY UPDATE
    step = VALUES(step),
    description = VALUES(description);

-- 钱包账户：每用户一行，owner_user_id 即 user.id；平台账本主体使用哨兵值 0。
CREATE TABLE IF NOT EXISTS wallet_account (
    owner_user_id BIGINT UNSIGNED NOT NULL,
    available_balance BIGINT NOT NULL DEFAULT 0,
    held_balance BIGINT NOT NULL DEFAULT 0,
    escrowed_balance BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (owner_user_id),
    CHECK (available_balance >= 0),
    CHECK (held_balance >= 0),
    CHECK (escrowed_balance >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 钱包流水：只追加事实源；(owner_user_id, business_ref) 唯一保证幂等；direct transfer 的 payer/payee 两条可共用同一 business_ref（owner 不同）。
CREATE TABLE IF NOT EXISTS wallet_ledger (
    id BIGINT UNSIGNED NOT NULL,
    owner_user_id BIGINT UNSIGNED NOT NULL,
    counterparty_user_id BIGINT NULL,
    escrow_id BIGINT UNSIGNED NULL,
    business_type VARCHAR(32) NOT NULL,
    business_ref VARCHAR(128) NOT NULL,
    direction VARCHAR(16) NOT NULL,
    reason VARCHAR(32) NOT NULL,
    amount BIGINT NOT NULL,
    available_delta BIGINT NOT NULL,
    held_delta BIGINT NOT NULL,
    escrowed_delta BIGINT NOT NULL,
    balance_available_after BIGINT NOT NULL,
    balance_held_after BIGINT NOT NULL,
    balance_escrowed_after BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_wallet_ledger_owner_business_ref (owner_user_id, business_ref),
    KEY idx_wallet_ledger_business_ref (business_ref),
    KEY idx_wallet_ledger_owner_created (owner_user_id, created_at),
    CHECK (amount > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 通用托管单据：记录付款方/收款方/金额/状态/过期，业务驱动状态迁移。
CREATE TABLE IF NOT EXISTS wallet_escrow (
    id BIGINT UNSIGNED NOT NULL,
    business_type VARCHAR(32) NOT NULL,
    business_ref VARCHAR(128) NOT NULL,
    payer_user_id BIGINT UNSIGNED NOT NULL,
    payee_user_id BIGINT UNSIGNED NULL,
    amount BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    expires_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_wallet_escrow_business_ref (business_ref),
    KEY idx_wallet_escrow_payer_status (payer_user_id, status),
    KEY idx_wallet_escrow_payee_status (payee_user_id, status),
    CHECK (amount > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- business_ref 全局 claim：PRIMARY KEY 在 business_ref 上，使"按 business_ref 串行化"不依赖 owner。
-- 任一 wallet 操作在写 ledger 前先 INSERT 此表抢占 ref；不同 owner 并发复用同一 ref 时，唯一键兜底只让一方成功，
-- 输方回读 ledger 整组判等后 reject（WALLET_DUPLICATE_BUSINESS_REF）。claim 行随 ledger 事实源常驻，不清理。
CREATE TABLE IF NOT EXISTS wallet_business_ref (
    business_ref VARCHAR(128) NOT NULL,
    claimed_by_owner_user_id BIGINT UNSIGNED NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (business_ref)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ===== 推广位竞价（slot auction promotions）=====
-- 推广活动：创作者为某帖子在某资源位发起的投放语义，承载资源类型与投放时间窗。
CREATE TABLE IF NOT EXISTS promotion_campaign (
    id BIGINT UNSIGNED NOT NULL,
    creator_user_id BIGINT UNSIGNED NOT NULL,
    post_id BIGINT UNSIGNED NOT NULL,
    resource_type VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    start_at DATETIME(3) NOT NULL,
    end_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_promotion_campaign_creator_status (creator_user_id, status),
    KEY idx_promotion_campaign_post (post_id),
    KEY idx_promotion_campaign_resource_window (resource_type, start_at, end_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 竞价窗口：某资源类型在一个时间窗内收单、排序、定价与出位的批次单位；窗口关闭时统一结算。
CREATE TABLE IF NOT EXISTS promotion_auction_window (
    id BIGINT UNSIGNED NOT NULL,
    resource_type VARCHAR(32) NOT NULL,
    window_start_at DATETIME(3) NOT NULL,
    window_end_at DATETIME(3) NOT NULL,
    slot_count INT NOT NULL,
    reserve_price BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    settled_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_promotion_window_resource_time (resource_type, window_start_at, window_end_at),
    KEY idx_promotion_window_status_time (status, window_end_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 推广出价：某活动在某窗口的单条出价，接单即冻结申报价；(campaign_id, auction_window_id) 唯一防重复出价。
CREATE TABLE IF NOT EXISTS promotion_bid (
    id BIGINT UNSIGNED NOT NULL,
    campaign_id BIGINT UNSIGNED NOT NULL,
    auction_window_id BIGINT UNSIGNED NOT NULL,
    bidder_user_id BIGINT UNSIGNED NOT NULL,
    bid_amount BIGINT NOT NULL,
    wallet_business_ref VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL,
    clearing_price BIGINT NULL,
    slot_index INT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_promotion_bid_campaign_window (campaign_id, auction_window_id),
    UNIQUE KEY uk_promotion_bid_wallet_ref (wallet_business_ref),
    KEY idx_promotion_bid_window_status_amount (auction_window_id, status, bid_amount DESC, id ASC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 位分配：窗口结算后的占位结果，驱动 feed/search 商业位读路径；同一窗口同一位号唯一。
CREATE TABLE IF NOT EXISTS promotion_slot_allocation (
    id BIGINT UNSIGNED NOT NULL,
    auction_window_id BIGINT UNSIGNED NOT NULL,
    resource_type VARCHAR(32) NOT NULL,
    slot_index INT NOT NULL,
    campaign_id BIGINT UNSIGNED NOT NULL,
    post_id BIGINT UNSIGNED NOT NULL,
    bidder_user_id BIGINT UNSIGNED NOT NULL,
    clearing_price BIGINT NOT NULL,
    allocation_start_at DATETIME(3) NOT NULL,
    allocation_end_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_promotion_slot_window_index (auction_window_id, slot_index),
    KEY idx_promotion_slot_resource_time (resource_type, allocation_start_at, allocation_end_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ===== 付费加权（paid boost promotions，非拍卖）=====
-- 与 slot auction 平行但独立：boost 活动按预算+boost 值表达，不产 winner/GSP/槽位分配。
-- boost 活动：创作者针对推荐排序或关注触达开启的非拍卖推广投放，含出价、有效 boost 值、单价、总预算、消耗与投放窗口。
CREATE TABLE IF NOT EXISTS promotion_boost_campaign (
    id BIGINT UNSIGNED NOT NULL,
    creator_user_id BIGINT UNSIGNED NOT NULL,
    post_id BIGINT UNSIGNED NOT NULL,
    channel VARCHAR(32) NOT NULL,
    bid_amount BIGINT NOT NULL,
    boost_value BIGINT NOT NULL,
    unit_price BIGINT NOT NULL,
    budget_total BIGINT NOT NULL,
    budget_consumed BIGINT NOT NULL,
    reserve_business_ref VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL,
    start_at DATETIME(3) NOT NULL,
    end_at DATETIME(3) NOT NULL,
    closed_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_paid_boost_campaign_reserve_ref (reserve_business_ref),
    KEY idx_paid_boost_campaign_creator_status (creator_user_id, status),
    KEY idx_paid_boost_campaign_channel_window (channel, status, start_at, end_at),
    KEY idx_paid_boost_campaign_post_channel (post_id, channel)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- boost 投放事实：内容被本地排序接纳并返回给客户端一次的可结算记录；同 (campaign,bucket,viewer) 在同 bucket 内聚合，不因刷新重复新增计费事实。
CREATE TABLE IF NOT EXISTS promotion_boost_delivery (
    id BIGINT UNSIGNED NOT NULL,
    campaign_id BIGINT UNSIGNED NOT NULL,
    channel VARCHAR(32) NOT NULL,
    post_id BIGINT UNSIGNED NOT NULL,
    viewer_user_id BIGINT UNSIGNED NOT NULL,
    delivery_bucket_start_at DATETIME(3) NOT NULL,
    delivery_count INT NOT NULL,
    unit_price_snapshot BIGINT NOT NULL,
    captured_amount BIGINT NOT NULL,
    settle_business_ref VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL,
    settled_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_paid_boost_delivery_campaign_bucket_viewer (campaign_id, delivery_bucket_start_at, viewer_user_id),
    UNIQUE KEY uk_paid_boost_delivery_settle_ref (settle_business_ref),
    KEY idx_paid_boost_delivery_status_bucket (status, delivery_bucket_start_at),
    KEY idx_paid_boost_delivery_campaign_status (campaign_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
