package com.tongji.wallet.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 通用托管单据：付款方、收款方、金额、状态、过期时间。业务驱动状态迁移，
 * 资金划转由 {@code WalletService} 承接。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WalletEscrow {
    private long id;
    private WalletBusinessType businessType;
    private String businessRef;
    private long payerUserId;
    private Long payeeUserId;
    private long amount;
    private WalletEscrowStatus status;
    private Instant expiresAt;
    private Instant createdAt;
    private Instant updatedAt;
}
