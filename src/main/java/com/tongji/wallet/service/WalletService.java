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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 钱包余额服务：维护可用 / 冻结 / 托管三态快照 + 只追加流水。
 * <p>
 * 所有变更方法在同一事务里更新账户快照并追加一条流水；
 * {@code businessRef} 唯一，重复请求返回既有结果，不重复扣/加钱。
 */
@Service
@RequiredArgsConstructor
public class WalletService {

    private final WalletAccountMapper walletAccountMapper;
    private final WalletLedgerMapper walletLedgerMapper;
    private final IdService idService;
    private final WalletProperties walletProperties;

    /**
     * 为用户初始化零余额钱包（已存在则不操作）。
     */
    @Transactional
    public void initializeIfAbsent(long ownerUserId) {
        if (walletAccountMapper.findByOwnerUserId(ownerUserId) == null) {
            Instant now = Instant.now();
            walletAccountMapper.insert(WalletAccount.builder()
                    .ownerUserId(ownerUserId)
                    .availableBalance(0L)
                    .heldBalance(0L)
                    .escrowedBalance(0L)
                    .status(WalletAccountStatus.ACTIVE)
                    .createdAt(now)
                    .updatedAt(now)
                    .build());
        }
    }

    /**
     * 平台账本主体向用户发放虚拟货币（注册赠币 / 平台补贴）。
     */
    @Transactional
    public WalletLedgerEntry grant(long ownerUserId, long amount, WalletLedgerReason reason, String businessRef) {
        return apply(ownerUserId, businessRef, reason, WalletLedgerDirection.CREDIT,
                walletProperties.getPlatformUserId(), amount, +amount, 0L, 0L, null);
    }

    /**
     * 冻结：可用 → 冻结。
     */
    @Transactional
    public WalletLedgerEntry hold(long ownerUserId, long amount, WalletLedgerReason reason, String businessRef) {
        return apply(ownerUserId, businessRef, reason, WalletLedgerDirection.DEBIT,
                null, amount, -amount, +amount, 0L, null);
    }

    /**
     * 释放冻结：冻结 → 可用。
     */
    @Transactional
    public WalletLedgerEntry releaseHold(long ownerUserId, long amount, WalletLedgerReason reason, String businessRef) {
        return apply(ownerUserId, businessRef, reason, WalletLedgerDirection.CREDIT,
                null, amount, +amount, -amount, 0L, null);
    }

    /**
     * 冻结 → 托管（绑定 escrowId）。
     */
    @Transactional
    public WalletLedgerEntry moveHoldToEscrow(long ownerUserId, long escrowId, long amount, String businessRef) {
        return apply(ownerUserId, businessRef, WalletLedgerReason.HOLD_TO_ESCROW, WalletLedgerDirection.DEBIT,
                null, amount, 0L, -amount, +amount, escrowId);
    }

    /**
     * 释放托管 → 可用（放款给收款方时由收款方钱包调用）。
     */
    @Transactional
    public WalletLedgerEntry releaseEscrowToAvailable(long ownerUserId, long escrowId, long amount,
                                                     WalletLedgerReason reason, String businessRef) {
        return apply(ownerUserId, businessRef, reason, WalletLedgerDirection.CREDIT,
                null, amount, +amount, 0L, -amount, escrowId);
    }

    /**
     * 没收托管 → 平台账本主体（owner 托管余额减少，counterparty 记为平台）。
     */
    @Transactional
    public WalletLedgerEntry forfeitEscrowToPlatform(long ownerUserId, long escrowId, long amount, String businessRef) {
        return apply(ownerUserId, businessRef, WalletLedgerReason.ESCROW_FORFEIT, WalletLedgerDirection.DEBIT,
                walletProperties.getPlatformUserId(), amount, 0L, 0L, -amount, escrowId);
    }

    /**
     * 读取用户钱包快照，不存在抛 {@link ErrorCode#WALLET_NOT_FOUND}。
     */
    public WalletAccount getAccountOrThrow(long ownerUserId) {
        WalletAccount account = walletAccountMapper.findByOwnerUserId(ownerUserId);
        if (account == null) {
            throw new BusinessException(ErrorCode.WALLET_NOT_FOUND);
        }
        return account;
    }

    /**
     * 分页查询用户流水（倒序）。
     */
    public List<WalletLedgerEntry> listLedger(long ownerUserId, int limit, int offset) {
        return walletLedgerMapper.listByOwnerUserId(ownerUserId, limit, offset);
    }

    private WalletLedgerEntry apply(long ownerUserId, String businessRef, WalletLedgerReason reason,
                                    WalletLedgerDirection direction, Long counterpartyUserId, long amount,
                                    long availableDelta, long heldDelta, long escrowedDelta, Long escrowId) {
        WalletLedgerEntry existing = walletLedgerMapper.findByBusinessRef(businessRef);
        if (existing != null) {
            return existing;
        }
        WalletAccount account = walletAccountMapper.findByOwnerUserId(ownerUserId);
        if (account == null) {
            throw new BusinessException(ErrorCode.WALLET_NOT_FOUND);
        }
        long newAvailable = account.getAvailableBalance() + availableDelta;
        long newHeld = account.getHeldBalance() + heldDelta;
        long newEscrowed = account.getEscrowedBalance() + escrowedDelta;
        guardSufficientBalance(availableDelta, newAvailable, heldDelta, newHeld, escrowedDelta, newEscrowed);

        account.setAvailableBalance(newAvailable);
        account.setHeldBalance(newHeld);
        account.setEscrowedBalance(newEscrowed);
        account.setUpdatedAt(Instant.now());
        walletAccountMapper.updateBalancesAndStatus(account);

        WalletLedgerEntry entry = WalletLedgerEntry.builder()
                .id(idService.nextId(IdNamespace.ADMIN_OPERATION))
                .ownerUserId(ownerUserId)
                .counterpartyUserId(counterpartyUserId)
                .escrowId(escrowId)
                .businessType(businessTypeFor(reason))
                .businessRef(businessRef)
                .direction(direction)
                .reason(reason)
                .amount(amount)
                .availableDelta(availableDelta)
                .heldDelta(heldDelta)
                .escrowedDelta(escrowedDelta)
                .balanceAvailableAfter(newAvailable)
                .balanceHeldAfter(newHeld)
                .balanceEscrowedAfter(newEscrowed)
                .createdAt(Instant.now())
                .build();
        walletLedgerMapper.insert(entry);
        return entry;
    }

    private void guardSufficientBalance(long availableDelta, long newAvailable,
                                        long heldDelta, long newHeld,
                                        long escrowedDelta, long newEscrowed) {
        if (availableDelta < 0 && newAvailable < 0) {
            throw new BusinessException(ErrorCode.WALLET_BALANCE_NOT_ENOUGH);
        }
        if (heldDelta < 0 && newHeld < 0) {
            throw new BusinessException(ErrorCode.WALLET_HELD_BALANCE_NOT_ENOUGH);
        }
        if (escrowedDelta < 0 && newEscrowed < 0) {
            throw new BusinessException(ErrorCode.WALLET_ESCROW_BALANCE_NOT_ENOUGH);
        }
    }

    private WalletBusinessType businessTypeFor(WalletLedgerReason reason) {
        if (reason == WalletLedgerReason.REGISTRATION_GRANT) {
            return WalletBusinessType.REGISTRATION;
        }
        return WalletBusinessType.SYSTEM;
    }
}
