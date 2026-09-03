package com.tongji.promotion.bprime.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.promotion.bprime.config.PromotionBPrimeProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class PromotionRedisWindowCloserTest {

    private static final long WINDOW_ID = 301L;
    private static final long REDIS_NOW_EPOCH_MS = 1_781_952_000_123L;
    private static final long DEADLINE_EPOCH_MS = 1_781_953_200_000L;

    private StringRedisTemplate redisTemplate;
    private PromotionRedisWindowCloser closer;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        closer = new PromotionRedisWindowCloser(
                redisTemplate, new ObjectMapper().findAndRegisterModules(), new PromotionBPrimeProperties());
    }

    @Test
    void returnsRedisTimeAndFixedDeadlineWhenWindowIsNotDue() {
        redisReturns("""
                {
                  "status": "NOT_DUE",
                  "redisNowEpochMs": 1781952000123,
                  "deadlineEpochMs": 1781953200000
                }
                """);

        PromotionRedisCloseOutcome outcome = closer.close(WINDOW_ID);

        assertThat(outcome).isEqualTo(
                new PromotionRedisCloseOutcome.NotDue(REDIS_NOW_EPOCH_MS, DEADLINE_EPOCH_MS));
        verifyOnlyCloseScriptExecuted();
    }

    @Test
    void returnsAlreadyTerminalWithoutAdditionalRedisWrites() {
        redisReturns("""
                {"status": "ALREADY_TERMINAL"}
                """);

        PromotionRedisCloseOutcome outcome = closer.close(WINDOW_ID);

        assertThat(outcome).isEqualTo(new PromotionRedisCloseOutcome.AlreadyTerminal());
        verifyOnlyCloseScriptExecuted();
    }

    @Test
    void returnsClosedSoldDecisionAndNormalizesEmptyCollections() {
        redisReturns("""
                {
                  "decisionId": "close:301:v3",
                  "commandId": "close:301",
                  "requestHash": "close:301",
                  "auctionWindowId": "301",
                  "decisionVersion": 3,
                  "previousVersion": 2,
                  "campaignId": "201",
                  "bidderUserId": "0",
                  "postId": "0",
                  "resourceType": "FEED_TOP_SLOT",
                  "type": "AUCTION_SOLD",
                  "accepted": true,
                  "bidAmount": 0,
                  "ranking": {},
                  "walletEffects": {},
                  "payload": {
                    "winnerCampaignId": "201",
                    "winningAmount": 500,
                    "actualEndAtEpochMs": 1781953200123
                  },
                  "decidedAtEpochMs": 1781953200123
                }
                """);

        PromotionRedisCloseOutcome outcome = closer.close(WINDOW_ID);

        assertThat(outcome).isInstanceOfSatisfying(PromotionRedisCloseOutcome.Closed.class, closed -> {
            assertThat(closed.decision().decisionType()).isEqualTo("AUCTION_SOLD");
            assertThat(closed.decision().campaignId()).isEqualTo(201L);
            assertThat(closed.decision().ranking()).isEmpty();
            assertThat(closed.decision().walletEffects()).isEmpty();
            assertThat(closed.decision().decidedAt()).isEqualTo(Instant.ofEpochMilli(1_781_953_200_123L));
        });
        verifyOnlyCloseScriptExecuted();
    }

    @Test
    void returnsClosedNoBidDecision() {
        redisReturns("""
                {
                  "decisionId": "close:301:v1",
                  "commandId": "close:301",
                  "requestHash": "close:301",
                  "auctionWindowId": "301",
                  "decisionVersion": 1,
                  "previousVersion": 0,
                  "campaignId": "",
                  "bidderUserId": "0",
                  "postId": "0",
                  "resourceType": "FEED_TOP_SLOT",
                  "type": "AUCTION_NO_BID",
                  "accepted": true,
                  "bidAmount": 0,
                  "ranking": [],
                  "walletEffects": [],
                  "payload": {"actualEndAtEpochMs": 1781953200123},
                  "decidedAtEpochMs": 1781953200123
                }
                """);

        PromotionRedisCloseOutcome outcome = closer.close(WINDOW_ID);

        assertThat(outcome).isInstanceOfSatisfying(PromotionRedisCloseOutcome.Closed.class, closed -> {
            assertThat(closed.decision().decisionType()).isEqualTo("AUCTION_NO_BID");
            assertThat(closed.decision().campaignId()).isZero();
            assertThat(closed.decision().ranking()).isEmpty();
            assertThat(closed.decision().walletEffects()).isEmpty();
        });
        verifyOnlyCloseScriptExecuted();
    }

    @Test
    void throwsUnavailableWhenLuaRejectsClose() {
        redisReturns("""
                {"status": "UNAVAILABLE", "rejectionReason": "REDIS_STREAM_VERSION_MISMATCH"}
                """);

        assertThatThrownBy(() -> closer.close(WINDOW_ID))
                .isInstanceOf(PromotionAuctionUnavailableException.class)
                .hasMessage("REDIS_STREAM_VERSION_MISMATCH");
        verifyOnlyCloseScriptExecuted();
    }

    @Test
    void wrapsRedisFailureAsUnavailable() {
        RuntimeException redisFailure = new RuntimeException("Redis connection lost");
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(redisFailure);

        assertThatThrownBy(() -> closer.close(WINDOW_ID))
                .isInstanceOf(PromotionAuctionUnavailableException.class)
                .hasMessage("promotion auction Redis close failed")
                .hasCause(redisFailure);
        verifyOnlyCloseScriptExecuted();
    }

    @Test
    void treatsMalformedNotDueResultAsUnavailable() {
        redisReturns("""
                {"status": "NOT_DUE"}
                """);

        assertThatThrownBy(() -> closer.close(WINDOW_ID))
                .isInstanceOf(PromotionAuctionUnavailableException.class)
                .hasMessage("failed to parse promotion close result")
                .hasCauseInstanceOf(IllegalArgumentException.class);
        verifyOnlyCloseScriptExecuted();
    }

    private void redisReturns(String payload) {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(payload);
    }

    private void verifyOnlyCloseScriptExecuted() {
        verify(redisTemplate).execute(any(RedisScript.class), eq(List.of(
                "promotion:auction:{301}:state",
                "promotion:auction:{301}:events",
                "promotion:auction:{301}:pub")), any(Object[].class));
        verifyNoMoreInteractions(redisTemplate);
    }
}
