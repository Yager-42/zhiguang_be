package com.tongji.wallet.api.dto;

/**
 * 钱包余额只读响应：三态余额 + 账户状态。
 */
public record WalletBalanceResponse(
        long availableBalance,
        long heldBalance,
        long escrowedBalance,
        String status
) {
}
