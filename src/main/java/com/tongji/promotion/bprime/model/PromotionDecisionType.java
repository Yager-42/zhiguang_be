package com.tongji.promotion.bprime.model;

/** 推广拍卖 Stream 决策类型（Go place_bid/close_auction 同构；WINDOW_CLOSED 已废除，客户端实时事件名保留）。 */
public enum PromotionDecisionType {
    BID_ACCEPTED,
    BID_REJECTED,
    ESCROW_APPLIED,
    AUCTION_EXTENDED,
    AUCTION_SOLD,
    AUCTION_NO_BID
}
