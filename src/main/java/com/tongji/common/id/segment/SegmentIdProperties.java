package com.tongji.common.id.segment;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "id.segment")
public class SegmentIdProperties {
    private long waitTimeoutMs = 500L;
    private int preloadThreads = 2;

    public long getWaitTimeoutMs() {
        return waitTimeoutMs;
    }

    public void setWaitTimeoutMs(long waitTimeoutMs) {
        this.waitTimeoutMs = waitTimeoutMs;
    }

    public int getPreloadThreads() {
        return preloadThreads;
    }

    public void setPreloadThreads(int preloadThreads) {
        this.preloadThreads = preloadThreads;
    }
}
