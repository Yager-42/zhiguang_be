package com.tongji.promotion.bprime.model;

import java.util.List;

/**
 * 同一拍卖窗口的一次有界裁决批次。
 *
 * <p>命令必须已按出价金额降序、入站序号升序排列；Redis Lua 以该顺序选择唯一最高有效候选。</p>
 *
 * @since 2026-08-12
 */
public record PromotionAuctionCommandBatch(
        long auctionWindowId,
        List<PromotionAuctionCommand> commands
) {
    public PromotionAuctionCommandBatch {
        commands = List.copyOf(commands);
        if (commands.isEmpty()) {
            throw new IllegalArgumentException("commands must not be empty");
        }
        for (PromotionAuctionCommand command : commands) {
            if (command.auctionWindowId() != auctionWindowId) {
                throw new IllegalArgumentException("all commands must belong to the batch window");
            }
        }
    }
}
