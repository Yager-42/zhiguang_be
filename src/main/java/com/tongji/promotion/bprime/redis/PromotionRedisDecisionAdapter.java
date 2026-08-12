package com.tongji.promotion.bprime.redis;

import com.tongji.promotion.bprime.config.PromotionBPrimeProperties;
import com.tongji.promotion.bprime.model.PromotionAuctionBatchItemResult;
import com.tongji.promotion.bprime.model.PromotionAuctionBatchOutcome;
import com.tongji.promotion.bprime.model.PromotionAuctionBatchResult;
import com.tongji.promotion.bprime.model.PromotionAuctionCommand;
import com.tongji.promotion.bprime.model.PromotionAuctionCommandBatch;
import com.tongji.promotion.bprime.model.PromotionAuctionDecision;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将同窗口有界命令批次编码为紧凑 Redis Lua 协议并解码结果。
 *
 * <p>该 adapter 是新接受的唯一入口；不提供逐请求兼容重载。</p>
 *
 * @since 2026-08-12
 */
@Component
public class PromotionRedisDecisionAdapter {

    private static final int PUBLIC_KEY_COUNT = 6;
    private static final int ITEM_RESULT_WIDTH = 12;

    private final StringRedisTemplate redisTemplate;
    private final PromotionBPrimeProperties properties;
    private final DefaultRedisScript<List> decisionScript;

    public PromotionRedisDecisionAdapter(StringRedisTemplate redisTemplate,
                                         com.fasterxml.jackson.databind.ObjectMapper ignored,
                                         PromotionBPrimeProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.decisionScript = new DefaultRedisScript<>();
        this.decisionScript.setLocation(new ClassPathResource("redis/lua/promotion-auction-decision-batch.lua"));
        this.decisionScript.setResultType(List.class);
    }

    /**
     * 原子裁决一个同窗口批次。
     *
     * @param batch 已按金额降序、入站序号升序排列的非空批次
     * @return 与输入下标对应的最终结果及提交后的窗口 admission 状态
     * @throws PromotionAuctionUnavailableException Redis 拒绝执行或返回无效协议时
     */
    public PromotionAuctionBatchResult decide(PromotionAuctionCommandBatch batch) {
        LinkedHashMap<Long, Integer> campaignKeyIndexes = campaignKeyIndexes(batch.commands());
        List<String> keys = PromotionAuctionRedisKeys.decisionKeys(
                batch.auctionWindowId(), new ArrayList<>(campaignKeyIndexes.keySet()));
        List<String> arguments = arguments(batch, campaignKeyIndexes);
        final List<?> raw;
        try {
            raw = redisTemplate.execute(decisionScript, keys, arguments.toArray());
        } catch (RuntimeException exception) {
            throw new PromotionAuctionUnavailableException("promotion auction Redis batch decision failed", exception);
        }
        return decode(batch, raw);
    }

    private LinkedHashMap<Long, Integer> campaignKeyIndexes(List<PromotionAuctionCommand> commands) {
        LinkedHashMap<Long, Integer> indexes = new LinkedHashMap<>();
        for (PromotionAuctionCommand command : commands) {
            indexes.computeIfAbsent(command.campaignId(), ignored -> PUBLIC_KEY_COUNT + indexes.size() + 1);
        }
        return indexes;
    }

    private List<String> arguments(PromotionAuctionCommandBatch batch, Map<Long, Integer> campaignKeyIndexes) {
        long wakeupTtlSeconds = Math.max(1L, (properties.getStreamSweepIntervalMs() * 2L + 999L) / 1_000L);
        List<String> arguments = new ArrayList<>(3 + batch.commands().size() * 10);
        arguments.add(String.valueOf(batch.commands().size()));
        arguments.add(String.valueOf(properties.getHotStateTtlSeconds()));
        arguments.add(String.valueOf(wakeupTtlSeconds));
        for (int inputIndex = 0; inputIndex < batch.commands().size(); inputIndex++) {
            PromotionAuctionCommand command = batch.commands().get(inputIndex);
            arguments.add(String.valueOf(inputIndex));
            arguments.add(command.commandId());
            arguments.add(command.requestHash());
            arguments.add(String.valueOf(command.bidderUserId()));
            arguments.add(String.valueOf(command.bidAmount()));
            arguments.add(String.valueOf(command.campaignId()));
            arguments.add(String.valueOf(command.postId()));
            arguments.add(command.resourceType());
            arguments.add(command.submittedAt().toString());
            arguments.add(String.valueOf(campaignKeyIndexes.get(command.campaignId())));
        }
        return arguments;
    }

    private PromotionAuctionBatchResult decode(PromotionAuctionCommandBatch batch, List<?> raw) {
        if (raw == null || raw.size() < 2) {
            throw unavailable("empty Redis batch result");
        }
        String status = text(raw.get(0));
        if ("UNAVAILABLE".equals(status)) {
            throw unavailable(text(raw.get(1)));
        }
        if (!"OK".equals(status) || raw.size() != 8 || !(raw.get(1) instanceof List<?> itemRows)) {
            throw unavailable("invalid Redis batch result");
        }
        List<PromotionAuctionBatchItemResult> items = new ArrayList<>(itemRows.size());
        for (Object itemRow : itemRows) {
            if (!(itemRow instanceof List<?> fields) || fields.size() != ITEM_RESULT_WIDTH) {
                throw unavailable("invalid Redis batch item result");
            }
            int inputIndex = Math.toIntExact(number(fields.get(0)));
            if (inputIndex < 0 || inputIndex >= batch.commands().size()) {
                throw unavailable("Redis batch input index out of range");
            }
            PromotionAuctionCommand command = batch.commands().get(inputIndex);
            PromotionAuctionBatchOutcome outcome;
            try {
                outcome = PromotionAuctionBatchOutcome.valueOf(text(fields.get(1)));
            } catch (IllegalArgumentException exception) {
                throw new PromotionAuctionUnavailableException("unknown Redis batch outcome", exception);
            }
            boolean accepted = outcome == PromotionAuctionBatchOutcome.ACCEPTED
                    || outcome == PromotionAuctionBatchOutcome.REPLAYED_ACCEPTED;
            String rejectionReason = accepted ? null : emptyToNull(text(fields.get(9)));
            long decisionVersion = number(fields.get(3));
            long previousVersion = number(fields.get(4));
            long decidedAtEpochMs = number(fields.get(5));
            Long authorizedAmount = nullableNumber(fields.get(7));
            long bidAmount = number(fields.get(8));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("submittedAt", text(fields.get(6)));
            if (authorizedAmount != null) {
                payload.put("authorizedAmount", authorizedAmount);
            }
            payload.put("requiredAmount", number(fields.get(10)));
            payload.put("currentPriceCents", number(fields.get(11)));
            PromotionAuctionDecision decision = new PromotionAuctionDecision(
                    text(fields.get(2)), command.commandId(), command.requestHash(), command.auctionWindowId(),
                    decisionVersion, previousVersion, command.campaignId(), command.bidderUserId(), command.postId(),
                    command.resourceType(), accepted ? "BID_ACCEPTED" : "BID_REJECTED", accepted,
                    rejectionReason, bidAmount, List.of(), List.of(), Map.copyOf(payload),
                    Instant.ofEpochMilli(decidedAtEpochMs));
            items.add(new PromotionAuctionBatchItemResult(inputIndex, outcome, decision));
        }
        return new PromotionAuctionBatchResult(items, number(raw.get(2)), emptyToNull(text(raw.get(3))),
                nullableNumber(raw.get(4)) == null ? 0L : number(raw.get(4)), text(raw.get(5)),
                number(raw.get(6)), number(raw.get(7)));
    }

    private PromotionAuctionUnavailableException unavailable(String reason) {
        return new PromotionAuctionUnavailableException(reason == null ? "invalid Redis batch result" : reason);
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private long number(Object value) {
        try {
            return Long.parseLong(text(value));
        } catch (NumberFormatException exception) {
            throw new PromotionAuctionUnavailableException("invalid numeric Redis batch result", exception);
        }
    }

    private Long nullableNumber(Object value) {
        String text = text(value);
        return text.isEmpty() ? null : number(value);
    }
}
