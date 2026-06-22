package com.tongji.promotion.bprime.model;

public record PromotionWalletEffect(
        long ownerUserId,
        long amount,
        String effectType,
        String businessRef
) {}
