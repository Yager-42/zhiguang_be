package com.tongji.promotion.bprime.model;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 竞价排名项。campaignId/bidderUserId/postId 用 String 序列化（snowflake 64 位 >2^53，防 JS 精度丢失）。
 * bidAmount 是积分类小额整数（保留价 1L），保留 long；rank 是名次，保留 int。
 *
 * 字段顺序按字母序序列化（@JsonPropertyOrder(alphabetic=true)），与
 * PromotionDecisionHasher.CANONICAL_MAPPER 的 ORDER_MAP_ENTRIES_BY_KEYS 对齐——
 * 该 record 会作为 Map&lt;String,Object&gt; payload 的 value 出现（WINDOW_CLOSED decision 的
 * finalRanking），消费者反序列化后退化成 LinkedHashMap（按 key 字母序序列化），生产者侧 record
 * 必须同样按字母序，否则 hash mismatch。
 */
@JsonPropertyOrder(alphabetic = true)
public record PromotionRankingItem(
        String campaignId,
        String bidderUserId,
        String postId,
        long bidAmount,
        int rank
) {}
