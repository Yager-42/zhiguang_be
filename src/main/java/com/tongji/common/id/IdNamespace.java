package com.tongji.common.id;

public enum IdNamespace {
    POST(IdMode.SNOWFLAKE),
    COMMENT(IdMode.SNOWFLAKE),
    PENDING_COMMENT(IdMode.SNOWFLAKE),
    PUBLISH_ATTEMPT(IdMode.SNOWFLAKE),
    RELATION(IdMode.SNOWFLAKE),
    OUTBOX_EVENT(IdMode.SNOWFLAKE),
    NOTIFICATION(IdMode.SNOWFLAKE),
    MODERATION_REPORT(IdMode.SNOWFLAKE),
    PROMOTION_COMMAND(IdMode.SNOWFLAKE),
    PROMOTION_ESCROW(IdMode.SNOWFLAKE),
    RECONCILIATION_TASK(IdMode.SEGMENT),
    ADMIN_OPERATION(IdMode.SEGMENT),
    AUDIT_LOG(IdMode.SEGMENT);

    private final IdMode mode;

    IdNamespace(IdMode mode) {
        this.mode = mode;
    }

    public IdMode getMode() {
        return mode;
    }
}
