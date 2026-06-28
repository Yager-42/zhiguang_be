package com.tongji.notification.model;

public final class NotificationType {
    public static final String LIKE = "like";
    public static final String COMMENT = "comment";
    public static final String FOLLOW = "follow";
    public static final String MODERATION_ACTION = "moderation_action";
    public static final String REPORT_PROCESSED = "report_processed";

    private NotificationType() {
    }
}
