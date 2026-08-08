package com.tongji.counter.service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单个 Feed 条目在页面读取时所需的共享计数与请求用户互动状态。
 */
public record FeedPageCounterState(Map<String, Long> counts, boolean liked, boolean faved) {
    public FeedPageCounterState {
        counts = Map.copyOf(new LinkedHashMap<>(counts));
    }
}
