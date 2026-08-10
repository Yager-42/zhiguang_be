package com.tongji.promotion.bprime.model;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * 钱包变动效果。字段顺序按字母序序列化（@JsonPropertyOrder(alphabetic=true)），
 * 与 PromotionDecisionHasher.CANONICAL_MAPPER 的 ORDER_MAP_ENTRIES_BY_KEYS 对齐——
 * 该 record 会作为 Map&lt;String,Object&gt; payload 的 value 出现（终态 AUCTION_SOLD/AUCTION_NO_BID decision 的
 * walletEffects），消费者反序列化后退化成 LinkedHashMap（按 key 字母序序列化），生产者侧 record
 * 必须同样按字母序，否则 hash mismatch。
 */
@JsonPropertyOrder(alphabetic = true)
public record PromotionWalletEffect(
        long ownerUserId,
        long amount,
        String effectType,
        String businessRef
) {}
