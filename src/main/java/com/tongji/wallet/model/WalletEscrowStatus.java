package com.tongji.wallet.model;

/**
 * 通用托管单据状态机：
 * CREATED → LOCKED → RELEASED / FORFEITED；CREATED/LOCKED → REFUNDED；CREATED → CANCELLED。
 * 终态：RELEASED / REFUNDED / FORFEITED / CANCELLED。
 */
public enum WalletEscrowStatus {
    CREATED,
    LOCKED,
    RELEASED,
    REFUNDED,
    FORFEITED,
    CANCELLED
}
