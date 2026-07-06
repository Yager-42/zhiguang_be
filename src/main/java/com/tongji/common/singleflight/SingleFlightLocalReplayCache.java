package com.tongji.common.singleflight;

import com.tongji.common.singleflight.model.SingleFlightPolicy;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class SingleFlightLocalReplayCache {

    private final Map<String, CacheEntry> cache = new LinkedHashMap<>(128, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
            return size() > maxSize;
        }
    };

    private volatile int maxSize = 1000;

    @SuppressWarnings("unchecked")
    public synchronized <T> T get(String stage, String requestKey) {
        CacheEntry entry = cache.get(cacheKey(stage, requestKey));
        if (entry == null) {
            return null;
        }
        if (entry.expireAtMillis <= System.currentTimeMillis()) {
            cache.remove(cacheKey(stage, requestKey));
            return null;
        }
        return (T) entry.value;
    }

    public synchronized <T> void put(String stage, String requestKey, T value, SingleFlightPolicy policy) {
        if (stage == null || stage.isBlank() || requestKey == null || requestKey.isBlank()
                || value == null || policy == null || !policy.l1CacheEnabled()) {
            return;
        }
        this.maxSize = policy.l1CacheMaxSize();
        cache.put(cacheKey(stage, requestKey),
                new CacheEntry(value, System.currentTimeMillis() + policy.l1CacheTtlMillis()));
    }

    private String cacheKey(String stage, String requestKey) {
        return stage + "|" + requestKey;
    }

    private record CacheEntry(Object value, long expireAtMillis) {
    }
}
