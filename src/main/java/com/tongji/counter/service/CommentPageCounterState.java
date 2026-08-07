package com.tongji.counter.service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单个评论在页面读取时所需的共享计数与请求用户点赞态。
 */
public record CommentPageCounterState(Map<String, Long> counts, boolean liked) {
    public CommentPageCounterState {
        counts = Map.copyOf(new LinkedHashMap<>(counts));
    }
}
