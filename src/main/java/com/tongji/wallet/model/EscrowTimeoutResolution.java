package com.tongji.wallet.model;

/**
 * 过期托管单据的可选结算终态，由业务方（或后续 timeout 驱动器）传入。
 * 本期不要求 scheduler，仅提供可调用 resolver。
 */
public enum EscrowTimeoutResolution {
    RELEASE,
    REFUND
}
