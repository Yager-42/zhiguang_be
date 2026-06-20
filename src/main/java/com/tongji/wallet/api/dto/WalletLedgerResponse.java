package com.tongji.wallet.api.dto;

import java.util.List;

/**
 * 钱包流水分页只读响应。
 */
public record WalletLedgerResponse(
        List<WalletLedgerItemResponse> items,
        int page,
        int pageSize
) {
}
