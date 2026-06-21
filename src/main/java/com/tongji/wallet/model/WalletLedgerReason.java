package com.tongji.wallet.model;

/**
 * 钱包流水原因，标识产生该条流水的账务动作语义。
 */
public enum WalletLedgerReason {
    REGISTRATION_GRANT,
    PLATFORM_SUBSIDY,
    HOLD_RESERVE,
    HOLD_RELEASE,
    HOLD_TO_ESCROW,
    PROMOTION_BID_CAPTURE,
    PROMOTION_BID_RELEASE,
    PAID_BOOST_CAPTURE,
    PAID_BOOST_RELEASE,
    ESCROW_RELEASE,
    ESCROW_REFUND,
    ESCROW_CANCEL,
    ESCROW_FORFEIT
}
