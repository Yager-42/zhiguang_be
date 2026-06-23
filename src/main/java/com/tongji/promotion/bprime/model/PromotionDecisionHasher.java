package com.tongji.promotion.bprime.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

public final class PromotionDecisionHasher {

    private static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private PromotionDecisionHasher() {
    }

    public static String hash(PromotionAuctionDecision decision) {
        Objects.requireNonNull(decision, "decision");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String canonical = CANONICAL_MAPPER.writeValueAsString(decision);
            byte[] bytes = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                output.append(String.format("%02x", value));
            }
            return output.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest unavailable", e);
        } catch (Exception e) {
            throw new IllegalStateException("promotion decision canonicalization failed", e);
        }
    }
}
