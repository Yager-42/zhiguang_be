package com.tongji.wallet.service;

import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.common.id.IdNamespace;
import com.tongji.common.id.IdService;
import com.tongji.wallet.mapper.WalletEscrowMapper;
import com.tongji.wallet.model.EscrowTimeoutResolution;
import com.tongji.wallet.model.WalletBusinessType;
import com.tongji.wallet.model.WalletEscrow;
import com.tongji.wallet.model.WalletEscrowStatus;
import com.tongji.wallet.model.WalletLedgerEntry;
import com.tongji.wallet.model.WalletLedgerReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WalletEscrowServiceTest {

    private static final long PAYER_ID = 101L;
    private static final long PAYEE_ID = 202L;
    private static final long ESCROW_ID = 9001L;
    private static final String BUSINESS_REF = "bounty:order:1";
    private static final String TRANSITION_REF = "bounty:order:1:lock-attempt-1";
    private static final long AMOUNT = 500L;

    private WalletEscrowMapper escrowMapper;
    private WalletService walletService;
    private IdService idService;
    private WalletEscrowService escrowService;

    @BeforeEach
    void setUp() {
        escrowMapper = mock(WalletEscrowMapper.class);
        walletService = mock(WalletService.class);
        idService = mock(IdService.class);
        escrowService = new WalletEscrowService(escrowMapper, walletService, idService);
        when(idService.nextId(IdNamespace.ADMIN_OPERATION)).thenReturn(ESCROW_ID);
        when(escrowMapper.transitionStatus(anyLong(), any(), any())).thenReturn(1);
        when(walletService.findLedgerByOwnerAndBusinessRef(anyLong(), anyString())).thenReturn(null);
    }

    @Test
    void createEscrowHoldsFundsAndPersistsCreatedRow() {
        when(escrowMapper.findByBusinessRef(BUSINESS_REF)).thenReturn(null);

        WalletEscrow created = escrowService.createEscrow(PAYER_ID, PAYEE_ID, AMOUNT,
                WalletBusinessType.BOUNTY, BUSINESS_REF, null);

        // create 只 hold（available->held），不碰 escrowed
        verify(walletService).hold(eq(PAYER_ID), eq(AMOUNT), eq(WalletLedgerReason.HOLD_RESERVE),
                eq(WalletBusinessType.BOUNTY), eq(BUSINESS_REF));
        verify(walletService, never()).moveHoldToEscrow(anyLong(), anyLong(), anyLong(), any(), anyString());
        ArgumentCaptor<WalletEscrow> captor = ArgumentCaptor.forClass(WalletEscrow.class);
        verify(escrowMapper).insert(captor.capture());
        WalletEscrow row = captor.getValue();
        assertThat(row.getId()).isEqualTo(ESCROW_ID);
        assertThat(row.getStatus()).isEqualTo(WalletEscrowStatus.CREATED);
        assertThat(row.getPayerUserId()).isEqualTo(PAYER_ID);
        assertThat(created).isSameAs(row);
    }

    @Test
    void createEscrowDuplicateBusinessRefReturnsExisting() {
        WalletEscrow existing = escrow(ESCROW_ID, WalletEscrowStatus.CREATED);
        when(escrowMapper.findByBusinessRef(BUSINESS_REF)).thenReturn(existing);

        WalletEscrow result = escrowService.createEscrow(PAYER_ID, PAYEE_ID, AMOUNT,
                WalletBusinessType.BOUNTY, BUSINESS_REF, null);

        assertThat(result).isSameAs(existing);
        verify(walletService, never()).hold(anyLong(), anyLong(), any(), any(), anyString());
        verify(escrowMapper, never()).insert(any());
    }

    @Test
    void createEscrowRejectsNonPositiveAmount() {
        assertThatThrownBy(() -> escrowService.createEscrow(PAYER_ID, PAYEE_ID, 0L,
                WalletBusinessType.BOUNTY, BUSINESS_REF, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
        verify(walletService, never()).hold(anyLong(), anyLong(), any(), any(), anyString());
    }

    @Test
    void lockEscrowMovesHeldToEscrowed() {
        when(escrowMapper.findByIdForUpdate(ESCROW_ID)).thenReturn(escrow(ESCROW_ID, WalletEscrowStatus.CREATED));

        escrowService.lockEscrow(ESCROW_ID, TRANSITION_REF);

        verify(walletService).moveHoldToEscrow(eq(PAYER_ID), eq(ESCROW_ID), eq(AMOUNT),
                eq(WalletBusinessType.BOUNTY), eq(TRANSITION_REF));
        verify(escrowMapper).transitionStatus(ESCROW_ID, WalletEscrowStatus.CREATED, WalletEscrowStatus.LOCKED);
    }

    @Test
    void releaseEscrowDoesDirectTransferToPayee() {
        when(escrowMapper.findByIdForUpdate(ESCROW_ID)).thenReturn(escrow(ESCROW_ID, WalletEscrowStatus.LOCKED));

        escrowService.releaseEscrow(ESCROW_ID, TRANSITION_REF);

        verify(walletService).releaseEscrowToPayee(eq(PAYER_ID), eq(PAYEE_ID), eq(ESCROW_ID), eq(AMOUNT),
                eq(WalletBusinessType.BOUNTY), eq(TRANSITION_REF));
        verify(escrowMapper).transitionStatus(ESCROW_ID, WalletEscrowStatus.LOCKED, WalletEscrowStatus.RELEASED);
        // 绝不用平台罚没 + 赠款模拟放款
        verify(walletService, never()).forfeitEscrowToPlatform(anyLong(), anyLong(), anyLong(), any(), anyString());
        verify(walletService, never()).grant(anyLong(), anyLong(), any(), any(), anyString());
    }

    @Test
    void refundCreatedEscrowUsesReleaseHold() {
        when(escrowMapper.findByIdForUpdate(ESCROW_ID)).thenReturn(escrow(ESCROW_ID, WalletEscrowStatus.CREATED));

        escrowService.refundEscrow(ESCROW_ID, TRANSITION_REF);

        verify(walletService).releaseHold(eq(PAYER_ID), eq(ESCROW_ID), eq(AMOUNT), eq(WalletLedgerReason.ESCROW_REFUND),
                eq(WalletBusinessType.BOUNTY), eq(TRANSITION_REF));
        verify(walletService, never()).releaseEscrowToAvailable(anyLong(), anyLong(), anyLong(), any(), any(), anyString());
        verify(escrowMapper).transitionStatus(ESCROW_ID, WalletEscrowStatus.CREATED, WalletEscrowStatus.REFUNDED);
    }

    @Test
    void refundLockedEscrowUsesReleaseEscrowToAvailable() {
        when(escrowMapper.findByIdForUpdate(ESCROW_ID)).thenReturn(escrow(ESCROW_ID, WalletEscrowStatus.LOCKED));

        escrowService.refundEscrow(ESCROW_ID, TRANSITION_REF);

        verify(walletService).releaseEscrowToAvailable(eq(PAYER_ID), eq(ESCROW_ID), eq(AMOUNT),
                eq(WalletLedgerReason.ESCROW_REFUND), eq(WalletBusinessType.BOUNTY), eq(TRANSITION_REF));
        verify(escrowMapper).transitionStatus(ESCROW_ID, WalletEscrowStatus.LOCKED, WalletEscrowStatus.REFUNDED);
    }

    @Test
    void cancelCreatedEscrowUsesReleaseHoldWithCancelReason() {
        when(escrowMapper.findByIdForUpdate(ESCROW_ID)).thenReturn(escrow(ESCROW_ID, WalletEscrowStatus.CREATED));

        escrowService.cancelEscrow(ESCROW_ID, TRANSITION_REF);

        verify(walletService).releaseHold(eq(PAYER_ID), eq(ESCROW_ID), eq(AMOUNT), eq(WalletLedgerReason.ESCROW_CANCEL),
                eq(WalletBusinessType.BOUNTY), eq(TRANSITION_REF));
        verify(escrowMapper).transitionStatus(ESCROW_ID, WalletEscrowStatus.CREATED, WalletEscrowStatus.CANCELLED);
    }

    @Test
    void forfeitLockedEscrowSendsFundsToPlatform() {
        when(escrowMapper.findByIdForUpdate(ESCROW_ID)).thenReturn(escrow(ESCROW_ID, WalletEscrowStatus.LOCKED));

        escrowService.forfeitEscrow(ESCROW_ID, TRANSITION_REF);

        verify(walletService).forfeitEscrowToPlatform(eq(PAYER_ID), eq(ESCROW_ID), eq(AMOUNT),
                eq(WalletBusinessType.BOUNTY), eq(TRANSITION_REF));
        verify(escrowMapper).transitionStatus(ESCROW_ID, WalletEscrowStatus.LOCKED, WalletEscrowStatus.FORFEITED);
    }

    @Test
    void invalidTransitionThrows() {
        when(escrowMapper.findByIdForUpdate(ESCROW_ID)).thenReturn(escrow(ESCROW_ID, WalletEscrowStatus.CREATED));

        assertThatThrownBy(() -> escrowService.releaseEscrow(ESCROW_ID, TRANSITION_REF))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo(ErrorCode.ESCROW_INVALID_STATUS));
        verify(walletService, never()).releaseEscrowToPayee(anyLong(), anyLong(), anyLong(), anyLong(), any(), anyString());
    }

    @Test
    void duplicateTransitionBusinessRefIsIdempotent() {
        when(escrowMapper.findByIdForUpdate(ESCROW_ID)).thenReturn(escrow(ESCROW_ID, WalletEscrowStatus.LOCKED));
        when(walletService.findLedgerByOwnerAndBusinessRef(PAYER_ID, TRANSITION_REF))
                .thenReturn(transitionLedger(WalletLedgerReason.HOLD_TO_ESCROW));

        escrowService.lockEscrow(ESCROW_ID, TRANSITION_REF);

        verify(walletService, never()).moveHoldToEscrow(anyLong(), anyLong(), anyLong(), any(), anyString());
        verify(escrowMapper, never()).transitionStatus(anyLong(), any(), any());
    }

    @Test
    void differentTransitionBusinessRefHittingTerminalRejects() {
        when(escrowMapper.findByIdForUpdate(ESCROW_ID)).thenReturn(escrow(ESCROW_ID, WalletEscrowStatus.LOCKED));

        // 已 LOCKED，再用新 ref 尝试 lock -> 非幂等、且状态非 CREATED -> reject
        assertThatThrownBy(() -> escrowService.lockEscrow(ESCROW_ID, "other-ref"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo(ErrorCode.ESCROW_INVALID_STATUS));
        verify(walletService, never()).moveHoldToEscrow(anyLong(), anyLong(), anyLong(), any(), anyString());
    }

    @Test
    void resolveExpiredEscrowReleasesAfterExpiry() {
        Instant expiredAt = Instant.now().minusSeconds(60);
        when(escrowMapper.findByIdForUpdate(ESCROW_ID)).thenReturn(escrow(ESCROW_ID, WalletEscrowStatus.LOCKED, expiredAt));

        escrowService.resolveExpiredEscrow(ESCROW_ID, EscrowTimeoutResolution.RELEASE, TRANSITION_REF, Instant.now());

        verify(walletService).releaseEscrowToPayee(eq(PAYER_ID), eq(PAYEE_ID), eq(ESCROW_ID), eq(AMOUNT),
                eq(WalletBusinessType.BOUNTY), eq(TRANSITION_REF));
    }

    @Test
    void resolveExpiredEscrowRejectsBeforeExpiry() {
        Instant futureExpiry = Instant.now().plusSeconds(3600);
        when(escrowMapper.findByIdForUpdate(ESCROW_ID)).thenReturn(escrow(ESCROW_ID, WalletEscrowStatus.LOCKED, futureExpiry));

        assertThatThrownBy(() -> escrowService.resolveExpiredEscrow(ESCROW_ID, EscrowTimeoutResolution.RELEASE,
                TRANSITION_REF, Instant.now()))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo(ErrorCode.ESCROW_INVALID_STATUS));
        verify(walletService, never()).releaseEscrowToPayee(anyLong(), anyLong(), anyLong(), anyLong(), any(), anyString());
    }

    @Test
    void missingEscrowThrowsNotFound() {
        when(escrowMapper.findByIdForUpdate(ESCROW_ID)).thenReturn(null);

        assertThatThrownBy(() -> escrowService.releaseEscrow(ESCROW_ID, TRANSITION_REF))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo(ErrorCode.ESCROW_NOT_FOUND));
    }

    @Test
    void createEscrowDuplicateDifferentParamsRejects() {
        // 既有 escrow 与本次请求 amount 不同 -> reject，不静默返回
        WalletEscrow existing = WalletEscrow.builder()
                .id(ESCROW_ID).businessType(WalletBusinessType.BOUNTY).businessRef(BUSINESS_REF)
                .payerUserId(PAYER_ID).payeeUserId(PAYEE_ID).amount(9999L)
                .status(WalletEscrowStatus.CREATED).expiresAt(null)
                .createdAt(Instant.now()).updatedAt(Instant.now()).build();
        when(escrowMapper.findByBusinessRef(BUSINESS_REF)).thenReturn(existing);

        assertThatThrownBy(() -> escrowService.createEscrow(PAYER_ID, PAYEE_ID, AMOUNT,
                WalletBusinessType.BOUNTY, BUSINESS_REF, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo(ErrorCode.WALLET_DUPLICATE_BUSINESS_REF));
        verify(walletService, never()).hold(anyLong(), anyLong(), any(), any(), anyString());
    }

    @Test
    void duplicateReleaseSameTransitionBusinessRefReturnsExisting() {
        when(escrowMapper.findByIdForUpdate(ESCROW_ID)).thenReturn(escrow(ESCROW_ID, WalletEscrowStatus.RELEASED));
        when(walletService.findLedgerByOwnerAndBusinessRef(PAYER_ID, TRANSITION_REF))
                .thenReturn(transitionLedger(WalletLedgerReason.ESCROW_RELEASE));

        escrowService.releaseEscrow(ESCROW_ID, TRANSITION_REF);

        verify(walletService, never()).releaseEscrowToPayee(anyLong(), anyLong(), anyLong(), anyLong(), any(), anyString());
        verify(escrowMapper, never()).transitionStatus(anyLong(), any(), any());
    }

    @Test
    void refundDuplicateTransitionBusinessRefReturnsExisting() {
        when(escrowMapper.findByIdForUpdate(ESCROW_ID)).thenReturn(escrow(ESCROW_ID, WalletEscrowStatus.REFUNDED));
        when(walletService.findLedgerByOwnerAndBusinessRef(PAYER_ID, TRANSITION_REF))
                .thenReturn(transitionLedger(WalletLedgerReason.ESCROW_REFUND));

        escrowService.refundEscrow(ESCROW_ID, TRANSITION_REF);

        verify(walletService, never()).releaseHold(anyLong(), anyLong(), any(), any(), anyString());
        verify(walletService, never()).releaseEscrowToAvailable(anyLong(), anyLong(), anyLong(), any(), any(), anyString());
        verify(escrowMapper, never()).transitionStatus(anyLong(), any(), any());
    }

    @Test
    void cancelDuplicateTransitionBusinessRefReturnsExisting() {
        when(escrowMapper.findByIdForUpdate(ESCROW_ID)).thenReturn(escrow(ESCROW_ID, WalletEscrowStatus.CANCELLED));
        when(walletService.findLedgerByOwnerAndBusinessRef(PAYER_ID, TRANSITION_REF))
                .thenReturn(transitionLedger(WalletLedgerReason.ESCROW_CANCEL));

        escrowService.cancelEscrow(ESCROW_ID, TRANSITION_REF);

        verify(walletService, never()).releaseHold(anyLong(), anyLong(), any(), any(), anyString());
        verify(escrowMapper, never()).transitionStatus(anyLong(), any(), any());
    }

    @Test
    void forfeitDuplicateTransitionBusinessRefReturnsExisting() {
        when(escrowMapper.findByIdForUpdate(ESCROW_ID)).thenReturn(escrow(ESCROW_ID, WalletEscrowStatus.FORFEITED));
        when(walletService.findLedgerByOwnerAndBusinessRef(PAYER_ID, TRANSITION_REF))
                .thenReturn(transitionLedger(WalletLedgerReason.ESCROW_FORFEIT));

        escrowService.forfeitEscrow(ESCROW_ID, TRANSITION_REF);

        verify(walletService, never()).forfeitEscrowToPlatform(anyLong(), anyLong(), anyLong(), any(), anyString());
        verify(escrowMapper, never()).transitionStatus(anyLong(), any(), any());
    }

    @Test
    void refundThenCancelSameTransitionBusinessRefRejects() {
        // 已 REFUNDED（ledger reason = ESCROW_REFUND），同一 ref 调 cancel 不能被当成幂等成功
        when(escrowMapper.findByIdForUpdate(ESCROW_ID)).thenReturn(escrow(ESCROW_ID, WalletEscrowStatus.REFUNDED));
        when(walletService.findLedgerByOwnerAndBusinessRef(PAYER_ID, TRANSITION_REF))
                .thenReturn(transitionLedger(WalletLedgerReason.ESCROW_REFUND));

        assertThatThrownBy(() -> escrowService.cancelEscrow(ESCROW_ID, TRANSITION_REF))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo(ErrorCode.WALLET_DUPLICATE_BUSINESS_REF));
        verify(walletService, never()).releaseHold(anyLong(), anyLong(), any(), any(), anyString());
    }

    @Test
    void cancelThenRefundSameTransitionBusinessRefRejects() {
        // 已 CANCELLED（ledger reason = ESCROW_CANCEL），同一 ref 调 refund 不能被当成幂等成功
        when(escrowMapper.findByIdForUpdate(ESCROW_ID)).thenReturn(escrow(ESCROW_ID, WalletEscrowStatus.CANCELLED));
        when(walletService.findLedgerByOwnerAndBusinessRef(PAYER_ID, TRANSITION_REF))
                .thenReturn(transitionLedger(WalletLedgerReason.ESCROW_CANCEL));

        assertThatThrownBy(() -> escrowService.refundEscrow(ESCROW_ID, TRANSITION_REF))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo(ErrorCode.WALLET_DUPLICATE_BUSINESS_REF));
        verify(walletService, never()).releaseHold(anyLong(), anyLong(), any(), any(), anyString());
        verify(walletService, never()).releaseEscrowToAvailable(anyLong(), anyLong(), anyLong(), any(), any(), anyString());
    }

    private WalletEscrow escrow(long id, WalletEscrowStatus status) {
        return escrow(id, status, null);
    }

    private WalletEscrow escrow(long id, WalletEscrowStatus status, Instant expiresAt) {
        Instant now = Instant.now();
        return WalletEscrow.builder()
                .id(id)
                .businessType(WalletBusinessType.BOUNTY)
                .businessRef(BUSINESS_REF)
                .payerUserId(PAYER_ID)
                .payeeUserId(PAYEE_ID)
                .amount(AMOUNT)
                .status(status)
                .expiresAt(expiresAt)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private WalletLedgerEntry transitionLedger(WalletLedgerReason reason) {
        return WalletLedgerEntry.builder()
                .id(1L)
                .ownerUserId(PAYER_ID)
                .amount(AMOUNT)
                .reason(reason)
                .businessType(WalletBusinessType.BOUNTY)
                .escrowId(ESCROW_ID)
                .businessRef(TRANSITION_REF)
                .build();
    }
}
