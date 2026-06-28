package com.tongji.moderation.model;

import java.util.Set;

public final class ModerationReason {
    private static final Set<String> SUPPORTED = Set.of(
            "spam",
            "harassment",
            "violence",
            "pornography",
            "illegal",
            "other"
    );

    private ModerationReason() {
    }

    public static boolean isSupported(String reason) {
        return reason != null && SUPPORTED.contains(reason);
    }
}
