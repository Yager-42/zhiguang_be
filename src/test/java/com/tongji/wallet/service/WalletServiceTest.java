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
import org.springframework.dao.DuplicateKeyException;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WalletServiceTest {

    private static final long OWNER_ID = 1001L;
    private static final long OTHER_OWNER_ID = 9999L;
    private static final long PAYEE_ID = 2002L;
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
        walletService = new WalletService(walletAccountMapper, walletLedgerMapper, idService, properties);
        when(idService.nextId(IdNamespace.ADMIN_OPERATION)).thenReturn(LEDGER_ID);
        // 默认：同 business_ref 既有 ledger 组为空（新操作），claim 成功
        when(walletLedgerMapper.findByBusinessRef(any())).thenReturn(List.of());
        when(walletLedgerMapper.claimBusinessRef(any(), anyLong(), any())).thenReturn(1);
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
        assertThat(inserted.getStatus()).isEqualTo(WalletAccountStatus.ACTIVE);
    }

    @Test
    void grantLocksRowAppliesDeltaAndWritesCreditLedger() {
        WalletAccount account = activeAccount(OWNER_ID, 0L, 0L, 0L);
        when(walletAccountMapper.findByOwnerUserIdForUpdate(OWNER_ID)).thenReturn(account);
        when(walletAccountMapper.applyBalanceDeltas(eq(OWNER_ID), eq(100L), eq(0L), eq(0L))).thenReturn(1);

        WalletLedgerEntry result = walletService.grant(OWNER_ID, 100L, WalletLedgerReason.REGISTRATION_GRANT,
                WalletBusinessType.REGISTRATION, "ref:grant");

        verify(walletAccountMapper).findByOwnerUserIdForUpdate(OWNER_ID);
        verify(walletAccountMapper).applyBalanceDeltas(OWNER_ID, 100L, 0L, 0L);
        assertThat(result.getDirection()).isEqualTo(WalletLedgerDirection.CREDIT);
        assertThat(result.getBusinessType()).isEqualTo(WalletBusinessType.REGISTRATION);
        assertThat(result.getCounterpartyUserId()).isEqualTo(PLATFORM_ID);
        assertThat(result.getBalanceAvailableAfter()).isEqualTo(100L);
        verify(walletLedgerMapper).insert(any(WalletLedgerEntry.class));
    }

    @Test
    void duplicateBusinessRefGrantReturnsExistingWhenParamsMatch() {
        WalletLedgerEntry existing = fullEntry(OWNER_ID, 100L, WalletLedgerReason.REGISTRATION_GRANT,
                WalletBusinessType.REGISTRATION, null, 100L, 0L, 0L);
        when(walletLedgerMapper.findByBusinessRef("ref:grant")).thenReturn(List.of(existing));

        WalletLedgerEntry result = walletService.grant(OWNER_ID, 100L, WalletLedgerReason.REGISTRATION_GRANT,
                WalletBusinessType.REGISTRATION, "ref:grant");

        assertThat(result).isSameAs(existing);
        verify(walletAccountMapper, never()).findByOwnerUserIdForUpdate(anyLong());
        verify(walletAccountMapper, never()).applyBalanceDeltas(anyLong(), anyLong(), anyLong(), anyLong());
        verify(walletLedgerMapper, never()).insert(any());
    }

    @Test
    void duplicateBusinessRefWithDifferentAmountRejects() {
        WalletLedgerEntry existing = fullEntry(OWNER_ID, 999L, WalletLedgerReason.REGISTRATION_GRANT,
                WalletBusinessType.REGISTRATION, null, 999L, 0L, 0L);
        when(walletLedgerMapper.findByBusinessRef("ref:grant")).thenReturn(List.of(existing));

        assertThatThrownBy(() -> walletService.grant(OWNER_ID, 100L, WalletLedgerReason.REGISTRATION_GRANT,
                WalletBusinessType.REGISTRATION, "ref:grant"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo(ErrorCode.WALLET_DUPLICATE_BUSINESS_REF));

        verify(walletAccountMapper, never()).applyBalanceDeltas(anyLong(), anyLong(), anyLong(), anyLong());
        verify(walletLedgerMapper, never()).insert(any());
    }

    @Test
    void duplicateBusinessRefDifferentOwnerRejects() {
        // 关键：同一个 business_ref 已被别的 owner 用过 -> 即便本次 owner 不同，也按冲突 reject，绝不放成新操作
        WalletLedgerEntry existing = fullEntry(OTHER_OWNER_ID, 100L, WalletLedgerReason.REGISTRATION_GRANT,
                WalletBusinessType.REGISTRATION, null, 100L, 0L, 0L);
        when(walletLedgerMapper.findByBusinessRef("ref:shared")).thenReturn(List.of(existing));

        assertThatThrownBy(() -> walletService.grant(OWNER_ID, 100L, WalletLedgerReason.REGISTRATION_GRANT,
                WalletBusinessType.REGISTRATION, "ref:shared"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo(ErrorCode.WALLET_DUPLICATE_BUSINESS_REF));

        verify(walletLedgerMapper, never()).insert(any());
        verify(walletAccountMapper, never()).applyBalanceDeltas(anyLong(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void duplicateBusinessRefDifferentReasonRejects() {
        WalletLedgerEntry existing = fullEntry(OWNER_ID, 100L, WalletLedgerReason.PLATFORM_SUBSIDY,
                WalletBusinessType.REGISTRATION, null, 100L, 0L, 0L);
        when(walletLedgerMapper.findByBusinessRef("ref:grant")).thenReturn(List.of(existing));

        assertThatThrownBy(() -> walletService.grant(OWNER_ID, 100L, WalletLedgerReason.REGISTRATION_GRANT,
                WalletBusinessType.REGISTRATION, "ref:grant"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo(ErrorCode.WALLET_DUPLICATE_BUSINESS_REF));
        verify(walletLedgerMapper, never()).insert(any());
    }

    @Test
    void duplicateBusinessRefDifferentBusinessTypeRejects() {
        WalletLedgerEntry existing = fullEntry(OWNER_ID, 100L, WalletLedgerReason.REGISTRATION_GRANT,
                WalletBusinessType.PROMOTION, null, 100L, 0L, 0L);
        when(walletLedgerMapper.findByBusinessRef("ref:grant")).thenReturn(List.of(existing));

        assertThatThrownBy(() -> walletService.grant(OWNER_ID, 100L, WalletLedgerReason.REGISTRATION_GRANT,
                WalletBusinessType.REGISTRATION, "ref:grant"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo(ErrorCode.WALLET_DUPLICATE_BUSINESS_REF));
        verify(walletLedgerMapper, never()).insert(any());
    }

    @Test
    void duplicateBusinessRefDifferentEscrowIdRejects() {
        WalletLedgerEntry existing = fullEntry(OWNER_ID, 80L, WalletLedgerReason.HOLD_TO_ESCROW,
                WalletBusinessType.BOUNTY, 666L, 0L, -80L, 80L);
        when(walletLedgerMapper.findByBusinessRef("ref:move")).thenReturn(List.of(existing));

        assertThatThrownBy(() -> walletService.moveHoldToEscrow(OWNER_ID, 555L, 80L,
                WalletBusinessType.BOUNTY, "ref:move"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo(ErrorCode.WALLET_DUPLICATE_BUSINESS_REF));
        verify(walletLedgerMapper, never()).insert(any());
    }

    @Test
    void duplicateBusinessRefDifferentDeltaRejects() {
        WalletLedgerEntry existing = fullEntry(OWNER_ID, 40L, WalletLedgerReason.HOLD_RESERVE,
                WalletBusinessType.PROMOTION, null, -30L, 30L, 0L);
        when(walletLedgerMapper.findByBusinessRef("ref:hold")).thenReturn(List.of(existing));

        assertThatThrownBy(() -> walletService.hold(OWNER_ID, 40L, WalletLedgerReason.HOLD_RESERVE,
                WalletBusinessType.PROMOTION, "ref:hold"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo(ErrorCode.WALLET_DUPLICATE_BUSINESS_REF));
        verify(walletLedgerMapper, never()).insert(any());
    }

    @Test
    void duplicateBusinessRefGroupSizeMismatchRejects() {
        // 同 ref 被一个双侧 release 用过（组里有两条），再用作单侧 grant -> 组大小不符 -> reject
        WalletLedgerEntry payerExisting = releaseEntry(OWNER_ID, PAYEE_ID, 0L, 0L, -60L);
        WalletLedgerEntry payeeExisting = releaseEntry(PAYEE_ID, OWNER_ID, 60L, 0L, 0L);
        when(walletLedgerMapper.findByBusinessRef("ref:release")).thenReturn(List.of(payerExisting, payeeExisting));

        assertThatThrownBy(() -> walletService.grant(OWNER_ID, 60L, WalletLedgerReason.REGISTRATION_GRANT,
                WalletBusinessType.REGISTRATION, "ref:release"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo(ErrorCode.WALLET_DUPLICATE_BUSINESS_REF));
        verify(walletLedgerMapper, never()).insert(any());
    }

    @Test
    void holdMovesAvailableToHeldViaDelta() {
        WalletAccount account = activeAccount(OWNER_ID, 100L, 0L, 0L);
        when(walletAccountMapper.findByOwnerUserIdForUpdate(OWNER_ID)).thenReturn(account);
        when(walletAccountMapper.applyBalanceDeltas(eq(OWNER_ID), eq(-40L), eq(40L), eq(0L))).thenReturn(1);

        walletService.hold(OWNER_ID, 40L, WalletLedgerReason.HOLD_RESERVE, WalletBusinessType.PROMOTION, "ref:hold");

        verify(walletAccountMapper).applyBalanceDeltas(OWNER_ID, -40L, 40L, 0L);
    }

    @Test
    void releaseHoldMovesHeldToAvailableViaDelta() {
        WalletAccount account = activeAccount(OWNER_ID, 0L, 50L, 0L);
        when(walletAccountMapper.findByOwnerUserIdForUpdate(OWNER_ID)).thenReturn(account);
        when(walletAccountMapper.applyBalanceDeltas(eq(OWNER_ID), eq(50L), eq(-50L), eq(0L))).thenReturn(1);

        walletService.releaseHold(OWNER_ID, 50L, WalletLedgerReason.HOLD_RELEASE, WalletBusinessType.PROMOTION, "ref:release");

        verify(walletAccountMapper).applyBalanceDeltas(OWNER_ID, 50L, -50L, 0L);
    }

    @Test
    void moveHoldToEscrowMovesHeldToEscrowedWithEscrowId() {
        WalletAccount account = activeAccount(OWNER_ID, 0L, 80L, 0L);
        when(walletAccountMapper.findByOwnerUserIdForUpdate(OWNER_ID)).thenReturn(account);
        when(walletAccountMapper.applyBalanceDeltas(eq(OWNER_ID), eq(0L), eq(-80L), eq(80L))).thenReturn(1);

        walletService.moveHoldToEscrow(OWNER_ID, 555L, 80L, WalletBusinessType.BOUNTY, "ref:move");

        verify(walletAccountMapper).applyBalanceDeltas(OWNER_ID, 0L, -80L, 80L);
        ArgumentCaptor<WalletLedgerEntry> captor = ArgumentCaptor.forClass(WalletLedgerEntry.class);
        verify(walletLedgerMapper).insert(captor.capture());
        assertThat(captor.getValue().getEscrowId()).isEqualTo(555L);
    }

    @Test
    void releaseEscrowToAvailableMovesEscrowedToAvailable() {
        WalletAccount account = activeAccount(OWNER_ID, 0L, 0L, 70L);
        when(walletAccountMapper.findByOwnerUserIdForUpdate(OWNER_ID)).thenReturn(account);
        when(walletAccountMapper.applyBalanceDeltas(eq(OWNER_ID), eq(70L), eq(0L), eq(-70L))).thenReturn(1);

        walletService.releaseEscrowToAvailable(OWNER_ID, 555L, 70L, WalletLedgerReason.ESCROW_REFUND,
                WalletBusinessType.BOUNTY, "ref:rel");

        verify(walletAccountMapper).applyBalanceDeltas(OWNER_ID, 70L, 0L, -70L);
    }

    @Test
    void releaseEscrowToPayeeWritesTwoCrossCounterpartyLedgerEntries() {
        WalletAccount payer = activeAccount(OWNER_ID, 10L, 0L, 60L);
        WalletAccount payee = activeAccount(PAYEE_ID, 5L, 0L, 0L);
        when(walletAccountMapper.findByOwnerUserIdForUpdate(OWNER_ID)).thenReturn(payer);
        when(walletAccountMapper.findByOwnerUserIdForUpdate(PAYEE_ID)).thenReturn(payee);
        when(walletAccountMapper.applyBalanceDeltas(eq(OWNER_ID), eq(0L), eq(0L), eq(-60L))).thenReturn(1);
        when(walletAccountMapper.applyBalanceDeltas(eq(PAYEE_ID), eq(60L), eq(0L), eq(0L))).thenReturn(1);

        walletService.releaseEscrowToPayee(OWNER_ID, PAYEE_ID, 555L, 60L, WalletBusinessType.BOUNTY, "ref:release");

        verify(walletAccountMapper).applyBalanceDeltas(OWNER_ID, 0L, 0L, -60L);
        verify(walletAccountMapper).applyBalanceDeltas(PAYEE_ID, 60L, 0L, 0L);
        ArgumentCaptor<WalletLedgerEntry> captor = ArgumentCaptor.forClass(WalletLedgerEntry.class);
        verify(walletLedgerMapper, org.mockito.Mockito.times(2)).insert(captor.capture());
        List<WalletLedgerEntry> entries = captor.getAllValues();
        WalletLedgerEntry payerEntry = entries.stream().filter(e -> e.getOwnerUserId() == OWNER_ID).findFirst().orElseThrow();
        WalletLedgerEntry payeeEntry = entries.stream().filter(e -> e.getOwnerUserId() == PAYEE_ID).findFirst().orElseThrow();
        assertThat(payerEntry.getDirection()).isEqualTo(WalletLedgerDirection.DEBIT);
        assertThat(payerEntry.getEscrowedDelta()).isEqualTo(-60L);
        assertThat(payerEntry.getCounterpartyUserId()).isEqualTo(PAYEE_ID);
        assertThat(payerEntry.getReason()).isEqualTo(WalletLedgerReason.ESCROW_RELEASE);
        assertThat(payeeEntry.getDirection()).isEqualTo(WalletLedgerDirection.CREDIT);
        assertThat(payeeEntry.getAvailableDelta()).isEqualTo(60L);
        assertThat(payeeEntry.getCounterpartyUserId()).isEqualTo(OWNER_ID);
        assertThat(payeeEntry.getReason()).isEqualTo(WalletLedgerReason.ESCROW_RELEASE);
    }

    @Test
    void insufficientAvailableThrowsBeforeAnyUpdate() {
        WalletAccount account = activeAccount(OWNER_ID, 10L, 0L, 0L);
        when(walletAccountMapper.findByOwnerUserIdForUpdate(OWNER_ID)).thenReturn(account);

        assertThatThrownBy(() -> walletService.hold(OWNER_ID, 40L, WalletLedgerReason.HOLD_RESERVE,
                WalletBusinessType.PROMOTION, "ref:hold"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo(ErrorCode.WALLET_BALANCE_NOT_ENOUGH));

        verify(walletAccountMapper, never()).applyBalanceDeltas(anyLong(), anyLong(), anyLong(), anyLong());
        verify(walletLedgerMapper, never()).insert(any());
    }

    @Test
    void nonPositiveAmountRejectsBeforeLedgerInsert() {
        assertThatThrownBy(() -> walletService.grant(OWNER_ID, 0L, WalletLedgerReason.REGISTRATION_GRANT,
                WalletBusinessType.REGISTRATION, "ref:zero"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
        assertThatThrownBy(() -> walletService.hold(OWNER_ID, -5L, WalletLedgerReason.HOLD_RESERVE,
                WalletBusinessType.PROMOTION, "ref:neg"))
                .isInstanceOf(BusinessException.class);

        verify(walletLedgerMapper, never()).insert(any());
        verify(walletAccountMapper, never()).applyBalanceDeltas(anyLong(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void getAccountOrThrowThrowsWhenMissing() {
        when(walletAccountMapper.findByOwnerUserId(OWNER_ID)).thenReturn(null);

        assertThatThrownBy(() -> walletService.getAccountOrThrow(OWNER_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo(ErrorCode.WALLET_NOT_FOUND));
    }

    @Test
    void duplicateHoldSameBusinessRefReturnsExisting() {
        WalletLedgerEntry existing = fullEntry(OWNER_ID, 40L, WalletLedgerReason.HOLD_RESERVE,
                WalletBusinessType.PROMOTION, null, -40L, 40L, 0L);
        when(walletLedgerMapper.findByBusinessRef("ref:hold")).thenReturn(List.of(existing));

        WalletLedgerEntry result = walletService.hold(OWNER_ID, 40L, WalletLedgerReason.HOLD_RESERVE,
                WalletBusinessType.PROMOTION, "ref:hold");

        assertThat(result).isSameAs(existing);
        verify(walletAccountMapper, never()).findByOwnerUserIdForUpdate(anyLong());
        verify(walletAccountMapper, never()).applyBalanceDeltas(anyLong(), anyLong(), anyLong(), anyLong());
        verify(walletLedgerMapper, never()).insert(any());
    }

    @Test
    void duplicateDirectReleaseSameBusinessRefReturnsExisting() {
        WalletLedgerEntry payerExisting = releaseEntry(OWNER_ID, PAYEE_ID, 0L, 0L, -60L);
        WalletLedgerEntry payeeExisting = releaseEntry(PAYEE_ID, OWNER_ID, 60L, 0L, 0L);
        when(walletLedgerMapper.findByBusinessRef("ref:release")).thenReturn(List.of(payerExisting, payeeExisting));

        walletService.releaseEscrowToPayee(OWNER_ID, PAYEE_ID, 555L, 60L, WalletBusinessType.BOUNTY, "ref:release");

        verify(walletAccountMapper, never()).findByOwnerUserIdForUpdate(anyLong());
        verify(walletAccountMapper, never()).applyBalanceDeltas(anyLong(), anyLong(), anyLong(), anyLong());
        verify(walletLedgerMapper, never()).insert(any());
    }

    @Test
    void duplicateDirectReleaseOneSideMissingRejects() {
        // 同 ref 组里只有 payer 侧（payee 侧缺失）-> 整组不符 -> reject，不静默返回
        WalletLedgerEntry payerExisting = releaseEntry(OWNER_ID, PAYEE_ID, 0L, 0L, -60L);
        when(walletLedgerMapper.findByBusinessRef("ref:release")).thenReturn(List.of(payerExisting));

        assertThatThrownBy(() -> walletService.releaseEscrowToPayee(OWNER_ID, PAYEE_ID, 555L, 60L,
                WalletBusinessType.BOUNTY, "ref:release"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo(ErrorCode.WALLET_DUPLICATE_BUSINESS_REF));
        verify(walletAccountMapper, never()).applyBalanceDeltas(anyLong(), anyLong(), anyLong(), anyLong());
        verify(walletLedgerMapper, never()).insert(any());
    }

    @Test
    void duplicateDirectReleaseMismatchedSideFactsRejects() {
        // payer 侧一致，payee 侧 delta 与预期不符 -> 整组不符 -> reject
        WalletLedgerEntry payerExisting = releaseEntry(OWNER_ID, PAYEE_ID, 0L, 0L, -60L);
        WalletLedgerEntry payeeExisting = releaseEntry(PAYEE_ID, OWNER_ID, 999L, 0L, 0L);
        when(walletLedgerMapper.findByBusinessRef("ref:release")).thenReturn(List.of(payerExisting, payeeExisting));

        assertThatThrownBy(() -> walletService.releaseEscrowToPayee(OWNER_ID, PAYEE_ID, 555L, 60L,
                WalletBusinessType.BOUNTY, "ref:release"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo(ErrorCode.WALLET_DUPLICATE_BUSINESS_REF));
        verify(walletAccountMapper, never()).applyBalanceDeltas(anyLong(), anyLong(), anyLong(), anyLong());
        verify(walletLedgerMapper, never()).insert(any());
    }

    @Test
    void claimLostButGroupMatchesReturnsExisting() {
        // 并发同操作重试：快路径空组，claim 输给并发事务，回读整组匹配 -> 幂等返回既有结果
        WalletLedgerEntry existing = fullEntry(OWNER_ID, 100L, WalletLedgerReason.REGISTRATION_GRANT,
                WalletBusinessType.REGISTRATION, null, 100L, 0L, 0L);
        when(walletLedgerMapper.findByBusinessRef("ref:grant")).thenReturn(List.of(), List.of(existing));
        when(walletLedgerMapper.claimBusinessRef(eq("ref:grant"), anyLong(), any())).thenThrow(new DuplicateKeyException("dup"));
        when(walletAccountMapper.findByOwnerUserIdForUpdate(OWNER_ID)).thenReturn(activeAccount(OWNER_ID, 0L, 0L, 0L));

        WalletLedgerEntry result = walletService.grant(OWNER_ID, 100L, WalletLedgerReason.REGISTRATION_GRANT,
                WalletBusinessType.REGISTRATION, "ref:grant");

        assertThat(result).isSameAs(existing);
        verify(walletLedgerMapper, never()).insert(any());
        verify(walletAccountMapper, never()).applyBalanceDeltas(anyLong(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void claimLostAndGroupDifferentOwnerRejects() {
        // 跨 owner 并发复用同一 ref：claim 输给并发事务，回读整组 owner 不同 -> reject，绝不放成新操作
        WalletLedgerEntry otherOwner = fullEntry(OTHER_OWNER_ID, 100L, WalletLedgerReason.REGISTRATION_GRANT,
                WalletBusinessType.REGISTRATION, null, 100L, 0L, 0L);
        when(walletLedgerMapper.findByBusinessRef("ref:shared")).thenReturn(List.of(), List.of(otherOwner));
        when(walletLedgerMapper.claimBusinessRef(eq("ref:shared"), anyLong(), any())).thenThrow(new DuplicateKeyException("dup"));
        when(walletAccountMapper.findByOwnerUserIdForUpdate(OWNER_ID)).thenReturn(activeAccount(OWNER_ID, 0L, 0L, 0L));

        assertThatThrownBy(() -> walletService.grant(OWNER_ID, 100L, WalletLedgerReason.REGISTRATION_GRANT,
                WalletBusinessType.REGISTRATION, "ref:shared"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo(ErrorCode.WALLET_DUPLICATE_BUSINESS_REF));
        verify(walletLedgerMapper, never()).insert(any());
    }

    @Test
    void claimLostAndGroupNotVisibleRejects() {
        // 并发：claim 输给并发事务但对方 ledger 还未可见 -> 整组为空 -> reject（ref 已被占，不能放成新操作）
        when(walletLedgerMapper.findByBusinessRef("ref:grant")).thenReturn(List.of(), List.of());
        when(walletLedgerMapper.claimBusinessRef(eq("ref:grant"), anyLong(), any())).thenThrow(new DuplicateKeyException("dup"));
        when(walletAccountMapper.findByOwnerUserIdForUpdate(OWNER_ID)).thenReturn(activeAccount(OWNER_ID, 0L, 0L, 0L));

        assertThatThrownBy(() -> walletService.grant(OWNER_ID, 100L, WalletLedgerReason.REGISTRATION_GRANT,
                WalletBusinessType.REGISTRATION, "ref:grant"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo(ErrorCode.WALLET_DUPLICATE_BUSINESS_REF));
        verify(walletLedgerMapper, never()).insert(any());
    }

    private WalletLedgerEntry releaseEntry(long ownerUserId, long counterpartyUserId,
                                           long availableDelta, long heldDelta, long escrowedDelta) {
        return WalletLedgerEntry.builder()
                .id(LEDGER_ID)
                .ownerUserId(ownerUserId)
                .counterpartyUserId(counterpartyUserId)
                .escrowId(555L)
                .businessType(WalletBusinessType.BOUNTY)
                .businessRef("ref:release")
                .direction(escrowedDelta < 0 ? WalletLedgerDirection.DEBIT : WalletLedgerDirection.CREDIT)
                .reason(WalletLedgerReason.ESCROW_RELEASE)
                .amount(60L)
                .availableDelta(availableDelta)
                .heldDelta(heldDelta)
                .escrowedDelta(escrowedDelta)
                .balanceAvailableAfter(availableDelta)
                .balanceHeldAfter(0L)
                .balanceEscrowedAfter(0L)
                .createdAt(Instant.now())
                .build();
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

    private WalletLedgerEntry fullEntry(long ownerUserId, long amount, WalletLedgerReason reason,
                                        WalletBusinessType businessType, Long escrowId,
                                        long availableDelta, long heldDelta, long escrowedDelta) {
        return WalletLedgerEntry.builder()
                .id(LEDGER_ID)
                .ownerUserId(ownerUserId)
                .counterpartyUserId(PLATFORM_ID)
                .escrowId(escrowId)
                .businessType(businessType)
                .businessRef("ref")
                .direction(WalletLedgerDirection.CREDIT)
                .reason(reason)
                .amount(amount)
                .availableDelta(availableDelta)
                .heldDelta(heldDelta)
                .escrowedDelta(escrowedDelta)
                .balanceAvailableAfter(amount)
                .balanceHeldAfter(0L)
                .balanceEscrowedAfter(0L)
                .createdAt(Instant.now())
                .build();
    }
}
