package com.tongji.wallet.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 钱包账户余额快照：每用户一行，owner_user_id 即 zhiguang user.id。
 * 平台账本主体使用哨兵 owner_user_id = 0。三态：可用 / 冻结 / 托管。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WalletAccount {
    private long ownerUserId;
    private long availableBalance;
    private long heldBalance;
    private long escrowedBalance;
    private WalletAccountStatus status;
    private Instant createdAt;
    private Instant updatedAt;
}
