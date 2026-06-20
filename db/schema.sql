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

-- 钱包流水：只追加事实源，business_ref 全局唯一保证幂等。
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
    UNIQUE KEY uk_wallet_ledger_business_ref (business_ref),
    KEY idx_wallet_ledger_owner_created (owner_user_id, created_at)
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
    KEY idx_wallet_escrow_payee_status (payee_user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
