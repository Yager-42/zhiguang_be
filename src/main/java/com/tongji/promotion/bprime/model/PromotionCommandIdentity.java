package com.tongji.promotion.bprime.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 无 command 表时的确定性命令身份与请求指纹。 */
public final class PromotionCommandIdentity {

    private static final int COMMAND_HASH_LENGTH = 32;
    private static final ThreadLocal<MessageDigest> SHA_256 = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 not available", exception);
        }
    });

    private PromotionCommandIdentity() {
    }

    public static String bidCommandId(long auctionWindowId, long bidderUserId, String idempotencyKey) {
        String source = "bid:" + auctionWindowId + ":" + bidderUserId + ":" + idempotencyKey;
        return "promotion-bprime-" + sha256(source).substring(0, COMMAND_HASH_LENGTH);
    }

    public static String escrowCommandId(long auctionWindowId, long campaignId, long authorizedAmount) {
        String source = "escrow:" + auctionWindowId + ":" + campaignId + ":" + authorizedAmount;
        return "promotion-escrow-" + sha256(source).substring(0, COMMAND_HASH_LENGTH);
    }

    public static String requestHash(long campaignId, long bidderUserId, long auctionWindowId,
                                     long amount, String idempotencyKey) {
        return sha256(campaignId + ":" + bidderUserId + ":" + auctionWindowId + ":" + amount + ":"
                + idempotencyKey);
    }

    private static String sha256(String source) {
        MessageDigest digest = SHA_256.get();
        digest.reset();
        return HexFormat.of().formatHex(digest.digest(source.getBytes(StandardCharsets.UTF_8)));
    }
}
