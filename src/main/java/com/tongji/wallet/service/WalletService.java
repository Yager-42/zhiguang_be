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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 钱包余额服务：维护可用 / 冻结 / 托管三态快照 + 只追加流水。
 * <p>
 * 并发安全：mutating 操作在事务内 {@code SELECT ... FOR UPDATE} 锁账户行，delta 更新余额，禁止读旧余额后绝对值覆盖。
 * <p>
 * 幂等按 {@code business_ref} 的"整组事实"判定（唯一约束仍是 {@code (owner_user_id, business_ref)}）：
 * 同一个 {@code business_ref} 下所有既有 ledger 构成一组；只有当这组 ledger 与本次预期组完全一致时才返回既有结果，
 * 任一参数（owner / amount / reason / businessType / escrowId / 三个 delta）不同，或组大小不符，一律
 * {@link ErrorCode#WALLET_DUPLICATE_BUSINESS_REF}——包括"不同 owner 复用同一 business_ref"也按冲突拒绝，绝不当新操作放过。
 * direct release 的同 ref 双侧 ledger 同样按整组（两条）判等。由 ledger insert 原子占位 + 唯一键兜底保证并发安全。
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
    @Transactional(isolation = Isolation.READ_COMMITTED)
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

    /** 平台账本主体向用户发放虚拟货币（注册赠币 / 平台补贴）。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public WalletLedgerEntry grant(long ownerUserId, long amount, WalletLedgerReason reason,
                                   WalletBusinessType businessType, String businessRef) {
        return apply(ownerUserId, amount, reason, businessType, WalletLedgerDirection.CREDIT,
                walletProperties.getPlatformUserId(), null, +amount, 0L, 0L, businessRef);
    }

    /** 冻结：可用 → 冻结。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public WalletLedgerEntry hold(long ownerUserId, long amount, WalletLedgerReason reason,
                                  WalletBusinessType businessType, String businessRef) {
        return apply(ownerUserId, amount, reason, businessType, WalletLedgerDirection.DEBIT,
                null, null, -amount, +amount, 0L, businessRef);
    }

    /** 释放冻结：冻结 → 可用（无 escrow 关联，如推广冻结释放）。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public WalletLedgerEntry releaseHold(long ownerUserId, long amount, WalletLedgerReason reason,
                                         WalletBusinessType businessType, String businessRef) {
        return releaseHold(ownerUserId, null, amount, reason, businessType, businessRef);
    }

    /** 释放冻结并关联 escrow（refund CREATED / cancel 用）：ledger 记录 escrowId，供 transition 幂等判等。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public WalletLedgerEntry releaseHold(long ownerUserId, Long escrowId, long amount, WalletLedgerReason reason,
                                         WalletBusinessType businessType, String businessRef) {
        return apply(ownerUserId, amount, reason, businessType, WalletLedgerDirection.CREDIT,
                null, escrowId, +amount, -amount, 0L, businessRef);
    }

    /** 冻结 → 托管（绑定 escrowId）。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public WalletLedgerEntry moveHoldToEscrow(long ownerUserId, long escrowId, long amount,
                                              WalletBusinessType businessType, String businessRef) {
        return apply(ownerUserId, amount, WalletLedgerReason.HOLD_TO_ESCROW, businessType,
                WalletLedgerDirection.DEBIT, null, escrowId, 0L, -amount, +amount, businessRef);
    }

    /**
     * 冻结 → 平台账本主体（推广位中标成交价扣减）：冻结余额减成交价，counterparty 记平台哨兵。
     * 与 {@link #forfeitEscrowToPlatform} 同一平台 sentinel 模式：不模拟"release + grant"假账，显式扣减冻结。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public WalletLedgerEntry captureHoldToPlatform(long ownerUserId, long amount,
                                                   WalletBusinessType businessType, String businessRef) {
        return apply(ownerUserId, amount, WalletLedgerReason.PROMOTION_BID_CAPTURE, businessType,
                WalletLedgerDirection.DEBIT, walletProperties.getPlatformUserId(), null, 0L, -amount, 0L, businessRef);
    }

    /**
     * 直接放款：payer 托管 → payee 可用。同一 {@code businessRef} 下写 payer/payee 两条流水，
     * counterparty 互指；不得用平台罚没 + 赠款模拟。双侧 ledger 按整组（两条）判等保证幂等。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void releaseEscrowToPayee(long payerUserId, long payeeUserId, long escrowId, long amount,
                                     WalletBusinessType businessType, String businessRef) {
        validateAmount(amount);
        Set<LedgerIdentity> expected = Set.of(
                new LedgerIdentity(payerUserId, amount, WalletLedgerReason.ESCROW_RELEASE, businessType, escrowId, 0L, 0L, -amount),
                new LedgerIdentity(payeeUserId, amount, WalletLedgerReason.ESCROW_RELEASE, businessType, escrowId, +amount, 0L, 0L));
        // 快路径：同 ref 双侧 ledger 组完全一致才幂等返回
        if (matchTwoSidedGroup(businessRef, expected)) {
            return;
        }
        // 锁两个账户，升序避免死锁
        long lo = Math.min(payerUserId, payeeUserId);
        long hi = Math.max(payerUserId, payeeUserId);
        WalletAccount loAccount = requireLocked(lo);
        WalletAccount hiAccount = (lo == hi) ? loAccount : requireLocked(hi);
        WalletAccount payerAccount = (payerUserId == lo) ? loAccount : hiAccount;
        WalletAccount payeeAccount = (payeeUserId == lo) ? loAccount : hiAccount;
        long payerEscrowedAfter = payerAccount.getEscrowedBalance() - amount;
        if (payerEscrowedAfter < 0) {
            throw new BusinessException(ErrorCode.WALLET_ESCROW_BALANCE_NOT_ENOUGH);
        }
        long payeeAvailableAfter = payeeAccount.getAvailableBalance() + amount;
        // 全局 claim business_ref：跨 owner 并发复用同一 ref 串行化；输方回读双侧整组判等，匹配返回，不符/未可见 reject
        try {
            walletLedgerMapper.claimBusinessRef(businessRef, payerUserId, Instant.now());
        } catch (DuplicateKeyException ex) {
            if (matchTwoSidedGroup(businessRef, expected)) {
                return;
            }
            throw new BusinessException(ErrorCode.WALLET_DUPLICATE_BUSINESS_REF);
        }
        Instant now = Instant.now();
        WalletLedgerEntry payerEntry = buildEntry(payerUserId, payeeUserId, escrowId, businessType, businessRef,
                WalletLedgerDirection.DEBIT, WalletLedgerReason.ESCROW_RELEASE, amount, 0L, 0L, -amount,
                payerAccount.getAvailableBalance(), payerAccount.getHeldBalance(), payerEscrowedAfter, now);
        WalletLedgerEntry payeeEntry = buildEntry(payeeUserId, payerUserId, escrowId, businessType, businessRef,
                WalletLedgerDirection.CREDIT, WalletLedgerReason.ESCROW_RELEASE, amount, +amount, 0L, 0L,
                payeeAvailableAfter, payeeAccount.getHeldBalance(), payeeAccount.getEscrowedBalance(), now);
        walletLedgerMapper.insert(payerEntry);
        walletLedgerMapper.insert(payeeEntry);
        if (walletAccountMapper.applyBalanceDeltas(payerUserId, 0L, 0L, -amount) == 0) {
            throw new BusinessException(ErrorCode.WALLET_ESCROW_BALANCE_NOT_ENOUGH);
        }
        if (walletAccountMapper.applyBalanceDeltas(payeeUserId, amount, 0L, 0L) == 0) {
            throw new BusinessException(ErrorCode.WALLET_NOT_FOUND);
        }
    }

    /** 释放托管 → 可用（同账户，refund LOCKED 用）。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public WalletLedgerEntry releaseEscrowToAvailable(long ownerUserId, long escrowId, long amount,
                                                     WalletLedgerReason reason, WalletBusinessType businessType,
                                                     String businessRef) {
        return apply(ownerUserId, amount, reason, businessType, WalletLedgerDirection.CREDIT,
                null, escrowId, +amount, 0L, -amount, businessRef);
    }

    /** 没收托管 → 平台账本主体 counterparty sentinel。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public WalletLedgerEntry forfeitEscrowToPlatform(long ownerUserId, long escrowId, long amount,
                                                    WalletBusinessType businessType, String businessRef) {
        return apply(ownerUserId, amount, WalletLedgerReason.ESCROW_FORFEIT, businessType,
                WalletLedgerDirection.DEBIT, walletProperties.getPlatformUserId(), escrowId,
                0L, 0L, -amount, businessRef);
    }

    /** 读取用户钱包快照，不存在抛 {@link ErrorCode#WALLET_NOT_FOUND}。 */
    public WalletAccount getAccountOrThrow(long ownerUserId) {
        WalletAccount account = walletAccountMapper.findByOwnerUserId(ownerUserId);
        if (account == null) {
            throw new BusinessException(ErrorCode.WALLET_NOT_FOUND);
        }
        return account;
    }

    /** 分页查询用户流水（倒序）。 */
    public List<WalletLedgerEntry> listLedger(long ownerUserId, int limit, int offset) {
        return walletLedgerMapper.listByOwnerUserId(ownerUserId, limit, offset);
    }

    /** 单 owner + businessRef 的幂等事实查询，无则 null；供 escrow transition 幂等判等。 */
    public WalletLedgerEntry findLedgerByOwnerAndBusinessRef(long ownerUserId, String businessRef) {
        return walletLedgerMapper.findByOwnerUserIdAndBusinessRef(ownerUserId, businessRef);
    }

    private WalletLedgerEntry apply(long ownerUserId, long amount, WalletLedgerReason reason,
                                    WalletBusinessType businessType, WalletLedgerDirection direction,
                                    Long counterpartyUserId, Long escrowId,
                                    long availableDelta, long heldDelta, long escrowedDelta, String businessRef) {
        validateAmount(amount);
        LedgerIdentity expected = new LedgerIdentity(ownerUserId, amount, reason, businessType,
                escrowId, availableDelta, heldDelta, escrowedDelta);
        // 快路径：同 business_ref 既有 ledger 组完全一致才幂等返回
        WalletLedgerEntry existing = matchSingleSidedGroup(businessRef, expected);
        if (existing != null) {
            return existing;
        }
        // 锁账户行（FOR UPDATE）保证余额 delta 原子
        WalletAccount account = walletAccountMapper.findByOwnerUserIdForUpdate(ownerUserId);
        if (account == null) {
            throw new BusinessException(ErrorCode.WALLET_NOT_FOUND);
        }
        long newAvailable = account.getAvailableBalance() + availableDelta;
        long newHeld = account.getHeldBalance() + heldDelta;
        long newEscrowed = account.getEscrowedBalance() + escrowedDelta;
        guardSufficientBalance(availableDelta, newAvailable, heldDelta, newHeld, escrowedDelta, newEscrowed);

        // 全局 claim business_ref（wallet_business_ref 的 PK = business_ref）：跨 owner 并发复用同一 ref 时，
        // 唯一键兜底只让一方 claim 成功；输方回读整组判等——匹配（同操作重试）返回既有结果，不符/未可见则 reject。
        try {
            walletLedgerMapper.claimBusinessRef(businessRef, ownerUserId, Instant.now());
        } catch (DuplicateKeyException ex) {
            WalletLedgerEntry committed = matchSingleSidedGroup(businessRef, expected);
            if (committed != null) {
                return committed;
            }
            throw new BusinessException(ErrorCode.WALLET_DUPLICATE_BUSINESS_REF);
        }
        WalletLedgerEntry entry = buildEntry(ownerUserId, counterpartyUserId, escrowId, businessType, businessRef,
                direction, reason, amount, availableDelta, heldDelta, escrowedDelta,
                newAvailable, newHeld, newEscrowed, Instant.now());
        walletLedgerMapper.insert(entry);
        if (walletAccountMapper.applyBalanceDeltas(ownerUserId, availableDelta, heldDelta, escrowedDelta) == 0) {
            throw new BusinessException(ErrorCode.WALLET_BALANCE_NOT_ENOUGH);
        }
        return entry;
    }

    /**
     * 单侧幂等整组判等：同 {@code business_ref} 的既有 ledger 组——
     * 组为空返回 null（继续）；组恰为 1 条且与 {@code expected} 完全一致返回该条（幂等）；其余（条数不符 / 任一字段不同，含不同 owner）reject。
     */
    private WalletLedgerEntry matchSingleSidedGroup(String businessRef, LedgerIdentity expected) {
        List<WalletLedgerEntry> group = walletLedgerMapper.findByBusinessRef(businessRef);
        if (group.isEmpty()) {
            return null;
        }
        if (group.size() != 1 || !LedgerIdentity.of(group.get(0)).equals(expected)) {
            throw new BusinessException(ErrorCode.WALLET_DUPLICATE_BUSINESS_REF);
        }
        return group.get(0);
    }

    /**
     * 双侧幂等整组判等（direct release）：同 {@code business_ref} 的既有 ledger 组——
     * 组为空返回 false（继续）；组的身份集合与 {@code expected} 完全一致返回 true（幂等）；其余 reject。
     */
    private boolean matchTwoSidedGroup(String businessRef, Set<LedgerIdentity> expected) {
        List<WalletLedgerEntry> group = walletLedgerMapper.findByBusinessRef(businessRef);
        if (group.isEmpty()) {
            return false;
        }
        Set<LedgerIdentity> actual = group.stream().map(LedgerIdentity::of).collect(Collectors.toSet());
        if (!actual.equals(expected)) {
            throw new BusinessException(ErrorCode.WALLET_DUPLICATE_BUSINESS_REF);
        }
        return true;
    }

    /** ledger 幂等身份：spec/plan 列明的 8 个判等字段。record 自动生成 equals/hashCode。 */
    private record LedgerIdentity(long ownerUserId, long amount, WalletLedgerReason reason,
                                  WalletBusinessType businessType, Long escrowId,
                                  long availableDelta, long heldDelta, long escrowedDelta) {
        static LedgerIdentity of(WalletLedgerEntry e) {
            return new LedgerIdentity(e.getOwnerUserId(), e.getAmount(), e.getReason(), e.getBusinessType(),
                    e.getEscrowId(), e.getAvailableDelta(), e.getHeldDelta(), e.getEscrowedDelta());
        }
    }

    private void validateAmount(long amount) {
        if (amount <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "金额必须大于 0");
        }
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

    private WalletAccount requireLocked(long ownerUserId) {
        WalletAccount account = walletAccountMapper.findByOwnerUserIdForUpdate(ownerUserId);
        if (account == null) {
            throw new BusinessException(ErrorCode.WALLET_NOT_FOUND);
        }
        return account;
    }

    private WalletLedgerEntry buildEntry(long ownerUserId, Long counterpartyUserId, Long escrowId,
                                         WalletBusinessType businessType, String businessRef,
                                         WalletLedgerDirection direction, WalletLedgerReason reason, long amount,
                                         long availableDelta, long heldDelta, long escrowedDelta,
                                         long balanceAvailableAfter, long balanceHeldAfter, long balanceEscrowedAfter,
                                         Instant createdAt) {
        return WalletLedgerEntry.builder()
                .id(idService.nextId(IdNamespace.ADMIN_OPERATION))
                .ownerUserId(ownerUserId)
                .counterpartyUserId(counterpartyUserId)
                .escrowId(escrowId)
                .businessType(businessType)
                .businessRef(businessRef)
                .direction(direction)
                .reason(reason)
                .amount(amount)
                .availableDelta(availableDelta)
                .heldDelta(heldDelta)
                .escrowedDelta(escrowedDelta)
                .balanceAvailableAfter(balanceAvailableAfter)
                .balanceHeldAfter(balanceHeldAfter)
                .balanceEscrowedAfter(balanceEscrowedAfter)
                .createdAt(createdAt)
                .build();
    }
}
