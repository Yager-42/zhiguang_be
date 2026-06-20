package com.tongji.wallet.service;

import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.common.id.IdNamespace;
import com.tongji.common.id.IdService;
import com.tongji.wallet.config.WalletProperties;
import com.tongji.wallet.mapper.WalletAccountMapper;
import com.tongji.wallet.mapper.WalletLedgerMapper;
import com.tongji.wallet.model.WalletAccount;
import com.tongji.wallet.model.WalletAccountStatus;
import com.tongji.wallet.model.WalletBusinessType;
import com.tongji.wallet.model.WalletLedgerDirection;
import com.tongji.wallet.model.WalletLedgerEntry;
import com.tongji.wallet.model.WalletLedgerReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WalletServiceTest {

    private static final long OWNER_ID = 1001L;
    private static final long PLATFORM_ID = 0L;
    private static final long LEDGER_ID = 7001L;

    private WalletAccountMapper walletAccountMapper;
    private WalletLedgerMapper walletLedgerMapper;
    private IdService idService;
    private WalletService walletService;

    @BeforeEach
    void setUp() {
        walletAccountMapper = mock(WalletAccountMapper.class);
        walletLedgerMapper = mock(WalletLedgerMapper.class);
        idService = mock(IdService.class);
        WalletProperties properties = new WalletProperties();
        properties.setPlatformUserId(PLATFORM_ID);
        properties.setRegistrationGrantAmount(100L);
        walletService = new WalletService(walletAccountMapper, walletLedgerMapper, idService, properties);
        when(idService.nextId(IdNamespace.ADMIN_OPERATION)).thenReturn(LEDGER_ID);
    }

    @Test
    void initializeIfAbsentCreatesZeroBalanceAccountWhenMissing() {
        when(walletAccountMapper.findByOwnerUserId(OWNER_ID)).thenReturn(null);

        walletService.initializeIfAbsent(OWNER_ID);

        ArgumentCaptor<WalletAccount> captor = ArgumentCaptor.forClass(WalletAccount.class);
        verify(walletAccountMapper).insert(captor.capture());
        WalletAccount inserted = captor.getValue();
        assertThat(inserted.getOwnerUserId()).isEqualTo(OWNER_ID);
        assertThat(inserted.getAvailableBalance()).isZero();
        assertThat(inserted.getHeldBalance()).isZero();
        assertThat(inserted.getEscrowedBalance()).isZero();
        assertThat(inserted.getStatus()).isEqualTo(WalletAccountStatus.ACTIVE);
    }

    @Test
    void initializeIfAbsentIsNoopWhenAccountExists() {
        when(walletAccountMapper.findByOwnerUserId(OWNER_ID)).thenReturn(activeAccount(OWNER_ID, 50L, 0L, 0L));

        walletService.initializeIfAbsent(OWNER_ID);

        verify(walletAccountMapper, never()).insert(any());
    }

    @Test
    void grantIncreasesAvailableAndWritesCreditLedger() {
        WalletAccount account = activeAccount(OWNER_ID, 0L, 0L, 0L);
        when(walletAccountMapper.findByOwnerUserId(OWNER_ID)).thenReturn(account);
        when(walletLedgerMapper.findByBusinessRef("registration-grant:user:1001")).thenReturn(null);

        WalletLedgerEntry result = walletService.grant(OWNER_ID, 100L, WalletLedgerReason.REGISTRATION_GRANT, "registration-grant:user:1001");

        assertThat(account.getAvailableBalance()).isEqualTo(100L);
        assertThat(result.getBalanceAvailableAfter()).isEqualTo(100L);
        assertThat(result.getDirection()).isEqualTo(WalletLedgerDirection.CREDIT);
        assertThat(result.getReason()).isEqualTo(WalletLedgerReason.REGISTRATION_GRANT);
        assertThat(result.getBusinessType()).isEqualTo(WalletBusinessType.REGISTRATION);
        assertThat(result.getCounterpartyUserId()).isEqualTo(PLATFORM_ID);
        assertThat(result.getAmount()).isEqualTo(100L);
        verify(walletLedgerMapper).insert(any(WalletLedgerEntry.class));
    }

    @Test
    void grantDuplicateBusinessRefReturnsExistingEntryWithoutDoubleWrite() {
        WalletAccount account = activeAccount(OWNER_ID, 0L, 0L, 0L);
        when(walletAccountMapper.findByOwnerUserId(OWNER_ID)).thenReturn(account);
        WalletLedgerEntry existing = WalletLedgerEntry.builder().businessRef("registration-grant:user:1001").build();
        when(walletLedgerMapper.findByBusinessRef("registration-grant:user:1001")).thenReturn(existing);

        WalletLedgerEntry result = walletService.grant(OWNER_ID, 100L, WalletLedgerReason.REGISTRATION_GRANT, "registration-grant:user:1001");

        assertThat(result).isSameAs(existing);
        verify(walletAccountMapper, never()).updateBalancesAndStatus(any());
        verify(walletLedgerMapper, never()).insert(any());
    }

    @Test
    void holdMovesAvailableToHeld() {
        WalletAccount account = activeAccount(OWNER_ID, 100L, 0L, 0L);
        when(walletAccountMapper.findByOwnerUserId(OWNER_ID)).thenReturn(account);
        when(walletLedgerMapper.findByBusinessRef("hold:1")).thenReturn(null);

        walletService.hold(OWNER_ID, 40L, WalletLedgerReason.HOLD_RESERVE, "hold:1");

        assertThat(account.getAvailableBalance()).isEqualTo(60L);
        assertThat(account.getHeldBalance()).isEqualTo(40L);
        verify(walletAccountMapper).updateBalancesAndStatus(account);
    }

    @Test
    void holdInsufficientAvailableThrowsBalanceNotEnough() {
        WalletAccount account = activeAccount(OWNER_ID, 10L, 0L, 0L);
        when(walletAccountMapper.findByOwnerUserId(OWNER_ID)).thenReturn(account);
        when(walletLedgerMapper.findByBusinessRef("hold:2")).thenReturn(null);

        assertThatThrownBy(() -> walletService.hold(OWNER_ID, 40L, WalletLedgerReason.HOLD_RESERVE, "hold:2"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo(ErrorCode.WALLET_BALANCE_NOT_ENOUGH));

        verify(walletAccountMapper, never()).updateBalancesAndStatus(any());
        verify(walletLedgerMapper, never()).insert(any());
    }

    @Test
    void releaseHoldMovesHeldToAvailable() {
        WalletAccount account = activeAccount(OWNER_ID, 0L, 50L, 0L);
        when(walletAccountMapper.findByOwnerUserId(OWNER_ID)).thenReturn(account);
        when(walletLedgerMapper.findByBusinessRef("release:1")).thenReturn(null);

        walletService.releaseHold(OWNER_ID, 50L, WalletLedgerReason.HOLD_RELEASE, "release:1");

        assertThat(account.getAvailableBalance()).isEqualTo(50L);
        assertThat(account.getHeldBalance()).isZero();
    }

    @Test
    void releaseHoldInsufficientHeldThrowsHeldBalanceNotEnough() {
        WalletAccount account = activeAccount(OWNER_ID, 0L, 10L, 0L);
        when(walletAccountMapper.findByOwnerUserId(OWNER_ID)).thenReturn(account);
        when(walletLedgerMapper.findByBusinessRef("release:2")).thenReturn(null);

        assertThatThrownBy(() -> walletService.releaseHold(OWNER_ID, 50L, WalletLedgerReason.HOLD_RELEASE, "release:2"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo(ErrorCode.WALLET_HELD_BALANCE_NOT_ENOUGH));
    }

    @Test
    void moveHoldToEscrowMovesHeldToEscrowed() {
        WalletAccount account = activeAccount(OWNER_ID, 0L, 80L, 0L);
        when(walletAccountMapper.findByOwnerUserId(OWNER_ID)).thenReturn(account);
        when(walletLedgerMapper.findByBusinessRef("escrow-move:1")).thenReturn(null);

        walletService.moveHoldToEscrow(OWNER_ID, 555L, 80L, "escrow-move:1");

        assertThat(account.getHeldBalance()).isZero();
        assertThat(account.getEscrowedBalance()).isEqualTo(80L);
        ArgumentCaptor<WalletLedgerEntry> captor = ArgumentCaptor.forClass(WalletLedgerEntry.class);
        verify(walletLedgerMapper).insert(captor.capture());
        assertThat(captor.getValue().getEscrowId()).isEqualTo(555L);
    }

    @Test
    void releaseEscrowToAvailableMovesEscrowedToAvailable() {
        WalletAccount account = activeAccount(OWNER_ID, 0L, 0L, 70L);
        when(walletAccountMapper.findByOwnerUserId(OWNER_ID)).thenReturn(account);
        when(walletLedgerMapper.findByBusinessRef("escrow-release:1")).thenReturn(null);

        walletService.releaseEscrowToAvailable(OWNER_ID, 555L, 70L, WalletLedgerReason.ESCROW_RELEASE, "escrow-release:1");

        assertThat(account.getEscrowedBalance()).isZero();
        assertThat(account.getAvailableBalance()).isEqualTo(70L);
    }

    @Test
    void releaseEscrowInsufficientEscrowedThrowsEscrowBalanceNotEnough() {
        WalletAccount account = activeAccount(OWNER_ID, 0L, 0L, 5L);
        when(walletAccountMapper.findByOwnerUserId(OWNER_ID)).thenReturn(account);
        when(walletLedgerMapper.findByBusinessRef("escrow-release:2")).thenReturn(null);

        assertThatThrownBy(() -> walletService.releaseEscrowToAvailable(OWNER_ID, 555L, 70L, WalletLedgerReason.ESCROW_RELEASE, "escrow-release:2"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo(ErrorCode.WALLET_ESCROW_BALANCE_NOT_ENOUGH));
    }

    @Test
    void forfeitEscrowToPlatformDecreasesEscrowedWithPlatformCounterparty() {
        WalletAccount account = activeAccount(OWNER_ID, 0L, 0L, 60L);
        when(walletAccountMapper.findByOwnerUserId(OWNER_ID)).thenReturn(account);
        when(walletLedgerMapper.findByBusinessRef("escrow-forfeit:1")).thenReturn(null);

        walletService.forfeitEscrowToPlatform(OWNER_ID, 555L, 60L, "escrow-forfeit:1");

        assertThat(account.getEscrowedBalance()).isZero();
        ArgumentCaptor<WalletLedgerEntry> captor = ArgumentCaptor.forClass(WalletLedgerEntry.class);
        verify(walletLedgerMapper).insert(captor.capture());
        assertThat(captor.getValue().getDirection()).isEqualTo(WalletLedgerDirection.DEBIT);
        assertThat(captor.getValue().getReason()).isEqualTo(WalletLedgerReason.ESCROW_FORFEIT);
        assertThat(captor.getValue().getCounterpartyUserId()).isEqualTo(PLATFORM_ID);
    }

    @Test
    void getAccountOrThrowThrowsWhenMissing() {
        when(walletAccountMapper.findByOwnerUserId(OWNER_ID)).thenReturn(null);

        assertThatThrownBy(() -> walletService.getAccountOrThrow(OWNER_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo(ErrorCode.WALLET_NOT_FOUND));
    }

    private WalletAccount activeAccount(long ownerId, long available, long held, long escrowed) {
        Instant now = Instant.now();
        return WalletAccount.builder()
                .ownerUserId(ownerId)
                .availableBalance(available)
                .heldBalance(held)
                .escrowedBalance(escrowed)
                .status(WalletAccountStatus.ACTIVE)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }
}
