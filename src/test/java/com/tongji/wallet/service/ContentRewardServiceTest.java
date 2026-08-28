package com.tongji.wallet.service;

import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.wallet.config.ContentRewardProperties;
import com.tongji.wallet.model.WalletBusinessType;
import com.tongji.wallet.model.WalletLedgerReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContentRewardServiceTest {

    private static final long USER_ID = 42L;
    private static final long POST_ID = 1001L;
    private static final long COMMENT_ID = 2001L;

    private WalletService walletService;
    private ContentRewardProperties properties;
    private ContentRewardService rewardService;

    @BeforeEach
    void setUp() {
        walletService = mock(WalletService.class);
        properties = new ContentRewardProperties(); // 默认 enabled=true / postAmount=10 / commentAmount=2
        rewardService = new ContentRewardService(walletService, properties);
    }

    @Test
    void rewardPostCreationGrantsPostAmount() {
        long amount = rewardService.rewardPostCreationStrict(USER_ID, POST_ID);

        assertThat(amount).isEqualTo(10L);
        verify(walletService).grant(eq(USER_ID), eq(10L), eq(WalletLedgerReason.CONTENT_CREATION_REWARD),
                eq(WalletBusinessType.CONTENT), eq("content-reward:post:" + POST_ID));
    }

    @Test
    void rewardCommentCreationGrantsCommentAmount() {
        long amount = rewardService.rewardCommentCreation(USER_ID, COMMENT_ID);

        assertThat(amount).isEqualTo(2L);
        verify(walletService).grant(eq(USER_ID), eq(2L), eq(WalletLedgerReason.CONTENT_CREATION_REWARD),
                eq(WalletBusinessType.CONTENT), eq("content-reward:comment:" + COMMENT_ID));
    }

    @Test
    void rewardIsNoOpWhenDisabled() {
        properties.setEnabled(false);

        long postAmount = rewardService.rewardPostCreationStrict(USER_ID, POST_ID);
        long commentAmount = rewardService.rewardCommentCreation(USER_ID, COMMENT_ID);

        assertThat(postAmount).isZero();
        assertThat(commentAmount).isZero();
        verify(walletService, never()).grant(anyLong(), anyLong(), any(), any(), any());
    }

    @Test
    void postRewardPropagatesWalletFailureForKafkaRetry() {
        when(walletService.grant(anyLong(), anyLong(), any(), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.WALLET_NOT_FOUND));

        assertThatThrownBy(() -> rewardService.rewardPostCreationStrict(USER_ID, POST_ID))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rewardReturnsZeroOnRuntimeExceptionAndDoesNotThrow() {
        // AC8 关键路径：非受检异常（如 DataAccessException / DB 抖动）也被 catch 不冒泡
        when(walletService.grant(anyLong(), anyLong(), any(), any(), any()))
                .thenThrow(new RuntimeException("db connection lost"));

        long amount = rewardService.rewardCommentCreation(USER_ID, COMMENT_ID);

        assertThat(amount).isZero();
    }

    @Test
    void rewardDelegatesIdempotencyToGrantOnRetry() {
        // grant 内置 businessRef 幂等，重试时同 businessRef+同操作幂等返回
        // 调用方重试调 rewardPostCreation 两次，grant 也调两次（幂等由 grant 内部 matchSingleSidedGroup 处理）
        // 这里验证 service 层不额外加防重（复用 grant 幂等）
        rewardService.rewardPostCreationStrict(USER_ID, POST_ID);
        rewardService.rewardPostCreationStrict(USER_ID, POST_ID);

        // service 每次都调 grant（幂等由 grant 内部保证），不外层查重
        verify(walletService, times(2)).grant(eq(USER_ID), eq(10L), eq(WalletLedgerReason.CONTENT_CREATION_REWARD),
                eq(WalletBusinessType.CONTENT), eq("content-reward:post:" + POST_ID));
    }
}
