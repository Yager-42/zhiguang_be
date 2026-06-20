package com.tongji.wallet.api.dto;

import java.time.Instant;

/**
 * 单条钱包流水只读响应。
 */
public record WalletLedgerItemResponse(
        long id,
        String businessType,
        String direction,
        String reason,
        long amount,
        long availableDelta,
        long heldDelta,
        long escrowedDelta,
        long balanceAvailableAfter,
        long balanceHeldAfter,
        long balanceEscrowedAfter,
        String businessRef,
        Instant createdAt
) {
}
