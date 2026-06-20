package com.tongji.wallet.service;

import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.common.id.IdNamespace;
import com.tongji.common.id.IdService;
import com.tongji.wallet.mapper.WalletEscrowMapper;
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
        when(walletService.hold(anyLong(), anyLong(), any(), anyString())).thenReturn(dummyEntry());
        when(walletService.moveHoldToEscrow(anyLong(), anyLong(), anyLong(), anyString())).thenReturn(dummyEntry());
        when(walletService.releaseEscrowToAvailable(anyLong(), anyLong(), anyLong(), any(), anyString())).thenReturn(dummyEntry());
        when(walletService.forfeitEscrowToPlatform(anyLong(), anyLong(), anyLong(), anyString())).thenReturn(dummyEntry());
        when(walletService.grant(anyLong(), anyLong(), any(), anyString())).thenReturn(dummyEntry());
    }

    @Test
    void createEscrowHoldsFundsIntoEscrowedAndPersistsCreatedRow() {
        when(escrowMapper.findByBusinessRef(BUSINESS_REF)).thenReturn(null);

        WalletEscrow created = escrowService.createEscrow(PAYER_ID, PAYEE_ID, AMOUNT,
                WalletBusinessType.BOUNTY, BUSINESS_REF, null);

        verify(walletService).hold(eq(PAYER_ID), eq(AMOUNT), eq(WalletLedgerReason.HOLD_RESERVE), eq(BUSINESS_REF + ":hold"));
        verify(walletService).moveHoldToEscrow(eq(PAYER_ID), eq(ESCROW_ID), eq(AMOUNT), eq(BUSINESS_REF + ":commit"));
        ArgumentCaptor<WalletEscrow> captor = ArgumentCaptor.forClass(WalletEscrow.class);
        verify(escrowMapper).insert(captor.capture());
        WalletEscrow row = captor.getValue();
        assertThat(row.getId()).isEqualTo(ESCROW_ID);
        assertThat(row.getPayerUserId()).isEqualTo(PAYER_ID);
        assertThat(row.getPayeeUserId()).isEqualTo(PAYEE_ID);
        assertThat(row.getAmount()).isEqualTo(AMOUNT);
        assertThat(row.getStatus()).isEqualTo(WalletEscrowStatus.CREATED);
        assertThat(row.getBusinessType()).isEqualTo(WalletBusinessType.BOUNTY);
        assertThat(created).isSameAs(row);
    }

    @Test
    void createEscrowDuplicateBusinessRefReturnsExistingWithoutSideEffects() {
        WalletEscrow existing = escrow(ESCROW_ID, WalletEscrowStatus.CREATED);
        when(escrowMapper.findByBusinessRef(BUSINESS_REF)).thenReturn(existing);

        WalletEscrow result = escrowService.createEscrow(PAYER_ID, PAYEE_ID, AMOUNT,
                WalletBusinessType.BOUNTY, BUSINESS_REF, null);

        assertThat(result).isSameAs(existing);
        verify(walletService, never()).hold(anyLong(), anyLong(), any(), anyString());
        verify(escrowMapper, never()).insert(any());
    }

    @Test
    void lockEscrowTransitionsCreatedToLockedRowOnly() {
        when(escrowMapper.findById(ESCROW_ID)).thenReturn(escrow(ESCROW_ID, WalletEscrowStatus.CREATED));

        escrowService.lockEscrow(ESCROW_ID);

        ArgumentCaptor<WalletEscrow> captor = ArgumentCaptor.forClass(WalletEscrow.class);
        verify(escrowMapper).updateStatus(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(WalletEscrowStatus.LOCKED);
        verify(walletService, never()).hold(anyLong(), anyLong(), any(), anyString());
    }

    @Test
    void lockEscrowInvalidStatusThrows() {
        when(escrowMapper.findById(ESCROW_ID)).thenReturn(escrow(ESCROW_ID, WalletEscrowStatus.RELEASED));

        assertThatThrownBy(() -> escrowService.lockEscrow(ESCROW_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo(ErrorCode.ESCROW_INVALID_STATUS));
    }

    @Test
    void lockEscrowAlreadyLockedIsIdempotent() {
        when(escrowMapper.findById(ESCROW_ID)).thenReturn(escrow(ESCROW_ID, WalletEscrowStatus.LOCKED));

        escrowService.lockEscrow(ESCROW_ID);

        verify(escrowMapper, never()).updateStatus(any());
    }

    @Test
    void releaseEscrowMovesFundsFromPayerToPayee() {
        when(escrowMapper.findById(ESCROW_ID)).thenReturn(escrow(ESCROW_ID, WalletEscrowStatus.LOCKED));

        escrowService.releaseEscrow(ESCROW_ID);

        verify(walletService).forfeitEscrowToPlatform(eq(PAYER_ID), eq(ESCROW_ID), eq(AMOUNT), eq(BUSINESS_REF + ":release-out"));
        verify(walletService).grant(eq(PAYEE_ID), eq(AMOUNT), eq(WalletLedgerReason.ESCROW_RELEASE), eq(BUSINESS_REF + ":release-in"));
        ArgumentCaptor<WalletEscrow> captor = ArgumentCaptor.forClass(WalletEscrow.class);
        verify(escrowMapper).updateStatus(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(WalletEscrowStatus.RELEASED);
    }

    @Test
    void releaseEscrowAlreadyReleasedIsIdempotent() {
        when(escrowMapper.findById(ESCROW_ID)).thenReturn(escrow(ESCROW_ID, WalletEscrowStatus.RELEASED));

        escrowService.releaseEscrow(ESCROW_ID);

        verify(walletService, never()).forfeitEscrowToPlatform(anyLong(), anyLong(), anyLong(), anyString());
        verify(walletService, never()).grant(anyLong(), anyLong(), any(), anyString());
        verify(escrowMapper, never()).updateStatus(any());
    }

    @Test
    void releaseEscrowInvalidStatusThrows() {
        when(escrowMapper.findById(ESCROW_ID)).thenReturn(escrow(ESCROW_ID, WalletEscrowStatus.CREATED));

        assertThatThrownBy(() -> escrowService.releaseEscrow(ESCROW_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo(ErrorCode.ESCROW_INVALID_STATUS));
    }

    @Test
    void refundEscrowReturnsFundsToPayerFromLocked() {
        when(escrowMapper.findById(ESCROW_ID)).thenReturn(escrow(ESCROW_ID, WalletEscrowStatus.LOCKED));

        escrowService.refundEscrow(ESCROW_ID);

        verify(walletService).releaseEscrowToAvailable(eq(PAYER_ID), eq(ESCROW_ID), eq(AMOUNT), eq(WalletLedgerReason.ESCROW_REFUND), eq(BUSINESS_REF + ":refund"));
        ArgumentCaptor<WalletEscrow> captor = ArgumentCaptor.forClass(WalletEscrow.class);
        verify(escrowMapper).updateStatus(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(WalletEscrowStatus.REFUNDED);
    }

    @Test
    void refundEscrowAlsoAllowedFromCreated() {
        when(escrowMapper.findById(ESCROW_ID)).thenReturn(escrow(ESCROW_ID, WalletEscrowStatus.CREATED));

        escrowService.refundEscrow(ESCROW_ID);

        verify(walletService).releaseEscrowToAvailable(eq(PAYER_ID), eq(ESCROW_ID), eq(AMOUNT), eq(WalletLedgerReason.ESCROW_REFUND), anyString());
    }

    @Test
    void forfeitEscrowSendsFundsToPlatform() {
        when(escrowMapper.findById(ESCROW_ID)).thenReturn(escrow(ESCROW_ID, WalletEscrowStatus.LOCKED));

        escrowService.forfeitEscrow(ESCROW_ID);

        verify(walletService).forfeitEscrowToPlatform(eq(PAYER_ID), eq(ESCROW_ID), eq(AMOUNT), eq(BUSINESS_REF + ":forfeit"));
        ArgumentCaptor<WalletEscrow> captor = ArgumentCaptor.forClass(WalletEscrow.class);
        verify(escrowMapper).updateStatus(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(WalletEscrowStatus.FORFEITED);
    }

    @Test
    void cancelEscrowFromCreatedRefundsPayer() {
        when(escrowMapper.findById(ESCROW_ID)).thenReturn(escrow(ESCROW_ID, WalletEscrowStatus.CREATED));

        escrowService.cancelEscrow(ESCROW_ID);

        verify(walletService).releaseEscrowToAvailable(eq(PAYER_ID), eq(ESCROW_ID), eq(AMOUNT), eq(WalletLedgerReason.ESCROW_REFUND), eq(BUSINESS_REF + ":cancel"));
        ArgumentCaptor<WalletEscrow> captor = ArgumentCaptor.forClass(WalletEscrow.class);
        verify(escrowMapper).updateStatus(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(WalletEscrowStatus.CANCELLED);
    }

    @Test
    void operationsOnMissingEscrowThrowNotFound() {
        when(escrowMapper.findById(ESCROW_ID)).thenReturn(null);

        assertThatThrownBy(() -> escrowService.releaseEscrow(ESCROW_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo(ErrorCode.ESCROW_NOT_FOUND));
    }

    private WalletEscrow escrow(long id, WalletEscrowStatus status) {
        Instant now = Instant.now();
        return WalletEscrow.builder()
                .id(id)
                .businessType(WalletBusinessType.BOUNTY)
                .businessRef(BUSINESS_REF)
                .payerUserId(PAYER_ID)
                .payeeUserId(PAYEE_ID)
                .amount(AMOUNT)
                .status(status)
                .expiresAt(null)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private WalletLedgerEntry dummyEntry() {
        return WalletLedgerEntry.builder().id(1L).build();
    }
}
