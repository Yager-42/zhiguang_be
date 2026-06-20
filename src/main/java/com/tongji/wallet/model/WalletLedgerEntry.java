package com.tongji.wallet.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 只追加钱包流水：事实源，business_ref 全局唯一保证幂等。
 * 记录方向、原因、金额、三态 delta 与变更后快照余额。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WalletLedgerEntry {
    private long id;
    private long ownerUserId;
    private Long counterpartyUserId;
    private Long escrowId;
    private WalletBusinessType businessType;
    private String businessRef;
    private WalletLedgerDirection direction;
    private WalletLedgerReason reason;
    private long amount;
    private long availableDelta;
    private long heldDelta;
    private long escrowedDelta;
    private long balanceAvailableAfter;
    private long balanceHeldAfter;
    private long balanceEscrowedAfter;
    private Instant createdAt;
}
