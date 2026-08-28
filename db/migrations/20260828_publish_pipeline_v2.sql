-- 发布链路 v2：增加执行版本、受理快照和 Outbox 业务幂等键。
-- 仅执行加列、回填和加索引；保留旧 fallback 列供旧镜像回滚。

DELIMITER $$

CREATE PROCEDURE migrate_publish_pipeline_v2()
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'publish_attempt'
          AND column_name = 'run_version'
    ) THEN
        ALTER TABLE publish_attempt
            ADD COLUMN run_version INT NOT NULL DEFAULT 1
                COMMENT '发布执行轮次，手工重试时递增' AFTER status;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'publish_attempt'
          AND column_name = 'content_object_key_snapshot'
    ) THEN
        ALTER TABLE publish_attempt
            ADD COLUMN content_object_key_snapshot VARCHAR(512) NULL
                COMMENT '受理时固定的正文对象 Key' AFTER error_message;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'publish_attempt'
          AND column_name = 'content_etag_snapshot'
    ) THEN
        ALTER TABLE publish_attempt
            ADD COLUMN content_etag_snapshot VARCHAR(128) NULL
                COMMENT '受理时固定的正文 ETag' AFTER content_object_key_snapshot;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'publish_attempt'
          AND column_name = 'content_sha256_snapshot'
    ) THEN
        ALTER TABLE publish_attempt
            ADD COLUMN content_sha256_snapshot CHAR(64) NULL
                COMMENT '受理时固定的正文 SHA-256' AFTER content_etag_snapshot;
    END IF;

    UPDATE publish_attempt AS attempt
    INNER JOIN know_posts AS post ON post.id = attempt.post_id
    SET attempt.content_object_key_snapshot = COALESCE(attempt.content_object_key_snapshot, post.content_object_key),
        attempt.content_etag_snapshot = COALESCE(attempt.content_etag_snapshot, post.content_etag),
        attempt.content_sha256_snapshot = COALESCE(attempt.content_sha256_snapshot, post.content_sha256)
    WHERE attempt.content_object_key_snapshot IS NULL
       OR attempt.content_sha256_snapshot IS NULL;

    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'outbox'
          AND column_name = 'event_key'
    ) THEN
        ALTER TABLE outbox
            ADD COLUMN event_key VARCHAR(191) NULL
                COMMENT '业务事件幂等键' AFTER id;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'outbox'
          AND index_name = 'uk_outbox_event_key'
    ) THEN
        ALTER TABLE outbox
            ADD UNIQUE KEY uk_outbox_event_key (event_key);
    END IF;
END$$

CALL migrate_publish_pipeline_v2()$$
DROP PROCEDURE migrate_publish_pipeline_v2$$

DELIMITER ;
