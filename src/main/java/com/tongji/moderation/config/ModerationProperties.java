package com.tongji.moderation.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Data
@Component
@ConfigurationProperties(prefix = "moderation")
public class ModerationProperties {
    private Llm llm = new Llm();
    private Notification notification = new Notification();

    @Data
    public static class Llm {
        private boolean enabled = false;
        private BigDecimal minConfidence = new BigDecimal("0.8000");
        private int maxContentChars = 4000;
        private int maxRetries = 3;
    }

    @Data
    public static class Notification {
        private long platformActorUserId = 0L;
    }
}
