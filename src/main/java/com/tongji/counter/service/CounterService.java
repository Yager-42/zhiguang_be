package com.tongji.counter.service;

import java.util.List;
import java.util.Map;

public interface CounterService {
    /**
     * 点赞：仅在之前未点赞时置位并 +1。
     * @return 是否发生状态变化（true 表示这次操作生效）
     */
    boolean like(String entityType, String entityId, long userId);

    /**
     * 取消点赞：仅在之前已点赞时清位并 -1。
     * @return 是否发生状态变化（true 表示这次操作生效）
     */
    boolean unlike(String entityType, String entityId, long userId);

    /**
     * 收藏：仅在之前未收藏时置位并 +1。
     */
    boolean fav(String entityType, String entityId, long userId);

    /**
     * 取消收藏：仅在之前已收藏时清位并 -1。
     */
    boolean unfav(String entityType, String entityId, long userId);

    /**
     * 获取指定指标的计数。
     */
    Map<String, Long> getCounts(String entityType, String entityId, List<String> metrics);

    /**
     * 基于事实源强制重建指定指标，并回写 SDS。
     */
    Map<String, Long> rebuildCountsFromFacts(String entityType, String entityId, List<String> metrics);

    /**
     * 直接覆盖指定指标计数。
     */
    void overwriteCount(String entityType, String entityId, String metric, long value);

    /**
     * 为新实体创建全零 SDS；已有计数时保持原值。
     */
    void initializeCounts(String entityType, String entityId);

    Map<String, Map<String, Long>> getCountsBatch(String entityType, List<String> entityIds, List<String> metrics);

    /**
     * 使用一次 Redis pipeline 读取页面计数与请求用户点赞态。
     */
    Map<String, CommentPageCounterState> getPageStateBatch(String entityType,
                                                           List<String> entityIds,
                                                           Long userId,
                                                           List<String> metrics);

    /**
     * 判断是否点赞/收藏（位图）。
     */
    boolean isLiked(String entityType, String entityId, long userId);
    Map<String, Boolean> isLikedBatch(String entityType, List<String> entityIds, long userId);
    boolean isFaved(String entityType, String entityId, long userId);
}
