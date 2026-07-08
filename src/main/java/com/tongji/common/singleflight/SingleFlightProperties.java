package com.tongji.common.singleflight;

import com.tongji.common.singleflight.model.SingleFlightMode;
import com.tongji.common.singleflight.model.SingleFlightPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "singleflight")
public class SingleFlightProperties {

    private boolean enabled = true;
    private String mode = "distributed";
    private SingleFlightPolicyDefaults defaults = new SingleFlightPolicyDefaults();
    private Map<String, SingleFlightPolicyDefaults> stages = new HashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public SingleFlightPolicyDefaults getDefaults() {
        return defaults;
    }

    public void setDefaults(SingleFlightPolicyDefaults defaults) {
        this.defaults = defaults == null ? new SingleFlightPolicyDefaults() : defaults;
    }

    public Map<String, SingleFlightPolicyDefaults> getStages() {
        return stages;
    }

    public void setStages(Map<String, SingleFlightPolicyDefaults> stages) {
        this.stages = stages == null ? new HashMap<>() : stages;
    }

    public SingleFlightMode mode() {
        return SingleFlightMode.from(mode);
    }

    public SingleFlightMode mode(String stage) {
        SingleFlightPolicyDefaults stagePolicy = stages.get(stage);
        if (stagePolicy != null && stagePolicy.mode != null && !stagePolicy.mode.isBlank()) {
            return SingleFlightMode.from(stagePolicy.mode);
        }
        return mode();
    }

    public boolean isEnabled(String stage) {
        if (!enabled) {
            return false;
        }
        SingleFlightPolicyDefaults stagePolicy = stages.get(stage);
        return stagePolicy == null || stagePolicy.enabled == null || stagePolicy.enabled;
    }

    public SingleFlightPolicy resolvePolicy(String stage) {
        SingleFlightPolicyDefaults stagePolicy = stages.get(stage);
        SingleFlightPolicyDefaults source = stagePolicy == null ? defaults : defaults.merge(stagePolicy);
        return source.toPolicy();
    }

    public static class SingleFlightPolicyDefaults {
        private Boolean enabled;
        private String mode;
        private Long runningTtlMillis;
        private Long resultTtlMillis;
        private Long failedResultTtlMillis;
        private Long followerMaxWaitMillis;
        private Long streamBlockTimeoutMillis;
        private Long pollFallbackIntervalMillis;
        private Long takeoverDetectMillis;
        private Long heartbeatIntervalMillis;
        private Boolean l1CacheEnabled;
        private Long l1CacheTtlMillis;
        private Integer l1CacheMaxSize;
        private Integer compressionThresholdBytes;
        private String compressionCodec;

        SingleFlightPolicy toPolicy() {
            return new SingleFlightPolicy(
                    positive(runningTtlMillis, 15000L),
                    positive(resultTtlMillis, 600000L),
                    positive(failedResultTtlMillis, 60000L),
                    positive(followerMaxWaitMillis, 20000L),
                    positive(streamBlockTimeoutMillis, 3000L),
                    positive(pollFallbackIntervalMillis, 2000L),
                    positive(takeoverDetectMillis, 10000L),
                    positive(heartbeatIntervalMillis, 1000L),
                    l1CacheEnabled == null || l1CacheEnabled,
                    positive(l1CacheTtlMillis, 30000L),
                    positive(l1CacheMaxSize, 1000),
                    positive(compressionThresholdBytes, Integer.MAX_VALUE),
                    compressionCodec == null || compressionCodec.isBlank() ? "gzip" : compressionCodec
            );
        }

        SingleFlightPolicyDefaults merge(SingleFlightPolicyDefaults override) {
            SingleFlightPolicyDefaults merged = new SingleFlightPolicyDefaults();
            merged.enabled = override.enabled == null ? enabled : override.enabled;
            merged.mode = override.mode == null ? mode : override.mode;
            merged.runningTtlMillis = override.runningTtlMillis == null ? runningTtlMillis : override.runningTtlMillis;
            merged.resultTtlMillis = override.resultTtlMillis == null ? resultTtlMillis : override.resultTtlMillis;
            merged.failedResultTtlMillis = override.failedResultTtlMillis == null ? failedResultTtlMillis : override.failedResultTtlMillis;
            merged.followerMaxWaitMillis = override.followerMaxWaitMillis == null ? followerMaxWaitMillis : override.followerMaxWaitMillis;
            merged.streamBlockTimeoutMillis = override.streamBlockTimeoutMillis == null ? streamBlockTimeoutMillis : override.streamBlockTimeoutMillis;
            merged.pollFallbackIntervalMillis = override.pollFallbackIntervalMillis == null ? pollFallbackIntervalMillis : override.pollFallbackIntervalMillis;
            merged.takeoverDetectMillis = override.takeoverDetectMillis == null ? takeoverDetectMillis : override.takeoverDetectMillis;
            merged.heartbeatIntervalMillis = override.heartbeatIntervalMillis == null ? heartbeatIntervalMillis : override.heartbeatIntervalMillis;
            merged.l1CacheEnabled = override.l1CacheEnabled == null ? l1CacheEnabled : override.l1CacheEnabled;
            merged.l1CacheTtlMillis = override.l1CacheTtlMillis == null ? l1CacheTtlMillis : override.l1CacheTtlMillis;
            merged.l1CacheMaxSize = override.l1CacheMaxSize == null ? l1CacheMaxSize : override.l1CacheMaxSize;
            merged.compressionThresholdBytes = override.compressionThresholdBytes == null ? compressionThresholdBytes : override.compressionThresholdBytes;
            merged.compressionCodec = override.compressionCodec == null ? compressionCodec : override.compressionCodec;
            return merged;
        }

        private long positive(Long value, long defaultValue) {
            return value != null && value > 0 ? value : defaultValue;
        }

        private int positive(Integer value, int defaultValue) {
            return value != null && value > 0 ? value : defaultValue;
        }

        public Boolean getEnabled() { return enabled; }
        public void setEnabled(Boolean enabled) { this.enabled = enabled; }
        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }
        public Long getRunningTtlMillis() { return runningTtlMillis; }
        public void setRunningTtlMillis(Long runningTtlMillis) { this.runningTtlMillis = runningTtlMillis; }
        public Long getResultTtlMillis() { return resultTtlMillis; }
        public void setResultTtlMillis(Long resultTtlMillis) { this.resultTtlMillis = resultTtlMillis; }
        public Long getFailedResultTtlMillis() { return failedResultTtlMillis; }
        public void setFailedResultTtlMillis(Long failedResultTtlMillis) { this.failedResultTtlMillis = failedResultTtlMillis; }
        public Long getFollowerMaxWaitMillis() { return followerMaxWaitMillis; }
        public void setFollowerMaxWaitMillis(Long followerMaxWaitMillis) { this.followerMaxWaitMillis = followerMaxWaitMillis; }
        public Long getStreamBlockTimeoutMillis() { return streamBlockTimeoutMillis; }
        public void setStreamBlockTimeoutMillis(Long streamBlockTimeoutMillis) { this.streamBlockTimeoutMillis = streamBlockTimeoutMillis; }
        public Long getPollFallbackIntervalMillis() { return pollFallbackIntervalMillis; }
        public void setPollFallbackIntervalMillis(Long pollFallbackIntervalMillis) { this.pollFallbackIntervalMillis = pollFallbackIntervalMillis; }
        public Long getTakeoverDetectMillis() { return takeoverDetectMillis; }
        public void setTakeoverDetectMillis(Long takeoverDetectMillis) { this.takeoverDetectMillis = takeoverDetectMillis; }
        public Long getHeartbeatIntervalMillis() { return heartbeatIntervalMillis; }
        public void setHeartbeatIntervalMillis(Long heartbeatIntervalMillis) { this.heartbeatIntervalMillis = heartbeatIntervalMillis; }
        public Boolean getL1CacheEnabled() { return l1CacheEnabled; }
        public void setL1CacheEnabled(Boolean l1CacheEnabled) { this.l1CacheEnabled = l1CacheEnabled; }
        public Long getL1CacheTtlMillis() { return l1CacheTtlMillis; }
        public void setL1CacheTtlMillis(Long l1CacheTtlMillis) { this.l1CacheTtlMillis = l1CacheTtlMillis; }
        public Integer getL1CacheMaxSize() { return l1CacheMaxSize; }
        public void setL1CacheMaxSize(Integer l1CacheMaxSize) { this.l1CacheMaxSize = l1CacheMaxSize; }
        public Integer getCompressionThresholdBytes() { return compressionThresholdBytes; }
        public void setCompressionThresholdBytes(Integer compressionThresholdBytes) { this.compressionThresholdBytes = compressionThresholdBytes; }
        public String getCompressionCodec() { return compressionCodec; }
        public void setCompressionCodec(String compressionCodec) { this.compressionCodec = compressionCodec; }
    }
}
