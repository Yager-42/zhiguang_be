package com.tongji.wallet.model;

/**
 * 钱包账户状态。MyBatis 按枚举名（EnumTypeHandler）落库为 VARCHAR。
 */
public enum WalletAccountStatus {
    ACTIVE,
    DISABLED
}
