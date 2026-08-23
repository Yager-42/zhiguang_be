package com.tongji.counter.api.dto;

import java.util.Map;

/**
 * 同一实体类型下的批量计数响应。
 *
 * @param entityType 实体类型
 * @param countsByEntityId 实体 ID 到指标计数的映射；请求顺序保持不变
 * @since 2026-08-21
 */
public record BatchCountsResponse(
        String entityType,
        Map<String, Map<String, Long>> countsByEntityId
) {
}
