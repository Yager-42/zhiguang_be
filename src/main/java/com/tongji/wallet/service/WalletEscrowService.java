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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;

/**
 * 通用托管生命周期服务。资金落点严格按 spec 三态：
 * <ul>
 *   <li>create = payer available → held + CREATED 单据</li>
 *   <li>lock = payer held → escrowed + LOCKED</li>
 *   <li>release(LOCKED) = payer escrowed → payee available（direct transfer，不经平台过桥）</li>
 *   <li>refund(CREATED) = releaseHold；refund(LOCKED) = releaseEscrowToAvailable（ledger reason = ESCROW_REFUND）</li>
 *   <li>cancel(CREATED) = releaseHold（ledger reason = ESCROW_CANCEL，与 refund 区分）</li>
 *   <li>forfeit(LOCKED) = forfeitEscrowToPlatform</li>
 * </ul>
 * 幂等事实源 = wallet ledger（{@code owner_user_id + transitionBusinessRef}），并以 ledger reason 编码目标终态，
 * 故 refund/cancel 不可互相冒充；同 transitionBusinessRef + 同 escrow + 同 reason + 同金额/业务参数才算幂等。
 * 并发迁移由 escrow 行锁（{@code findByIdForUpdate}）+ 条件状态迁移串行化，失败不留钱包 movement。
 */
@Service
@RequiredArgsConstructor
public class WalletEscrowService {

    private final WalletEscrowMapper escrowMapper;
    private final WalletService walletService;
    private final IdService idService;

    /**
     * 创建托管：hold 付款方资金 + CREATED 单据。businessRef 唯一，重复只有参数完全一致才返回既有单据，
     * 任一关键参数不同 reject；并发同 ref 在 hold 持有的账户锁内复查，返回既有单据而非撞唯一键。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public WalletEscrow createEscrow(long payerUserId, Long payeeUserId, long amount,
                                     WalletBusinessType businessType, String businessRef, Instant expiresAt) {
        if (amount <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "金额必须大于 0");
        }
        WalletEscrow existing = escrowMapper.findByBusinessRef(businessRef);
        if (existing != null) {
            ensureCreateMatches(existing, payerUserId, payeeUserId, amount, businessType);
            return existing;
        }
        walletService.hold(payerUserId, amount, WalletLedgerReason.HOLD_RESERVE, businessType, businessRef);
        // hold 在同事务内锁了付款方账户行，串行化同 payer 的并发 create；锁释放后复查既有 escrow，避免并发撞唯一键
        existing = escrowMapper.findByBusinessRef(businessRef);
        if (existing != null) {
            ensureCreateMatches(existing, payerUserId, payeeUserId, amount, businessType);
            return existing;
        }
        long escrowId = idService.nextId(IdNamespace.ADMIN_OPERATION);
        Instant now = Instant.now();
        WalletEscrow escrow = WalletEscrow.builder()
                .id(escrowId)
                .businessType(businessType)
                .businessRef(businessRef)
                .payerUserId(payerUserId)
                .payeeUserId(payeeUserId)
                .amount(amount)
                .status(WalletEscrowStatus.CREATED)
                .expiresAt(expiresAt)
                .createdAt(now)
                .updatedAt(now)
                .build();
        escrowMapper.insert(escrow);
        return escrow;
    }

    /** 锁定：CREATED → LOCKED，payer held → escrowed。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void lockEscrow(long escrowId, String transitionBusinessRef) {
        WalletEscrow escrow = requireLocked(escrowId);
        if (alreadyTransitioned(escrow, transitionBusinessRef, WalletLedgerReason.HOLD_TO_ESCROW)) {
            return;
        }
        guardStatus(escrow, WalletEscrowStatus.CREATED);
        walletService.moveHoldToEscrow(escrow.getPayerUserId(), escrowId, escrow.getAmount(),
                escrow.getBusinessType(), transitionBusinessRef);
        transitionStatus(escrow, WalletEscrowStatus.CREATED, WalletEscrowStatus.LOCKED);
    }

    /** 放款：LOCKED → RELEASED，payer escrowed → payee available（direct transfer）。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void releaseEscrow(long escrowId, String transitionBusinessRef) {
        WalletEscrow escrow = requireLocked(escrowId);
        if (alreadyTransitioned(escrow, transitionBusinessRef, WalletLedgerReason.ESCROW_RELEASE)) {
            return;
        }
        guardStatus(escrow, WalletEscrowStatus.LOCKED);
        if (escrow.getPayeeUserId() == null) {
            throw new BusinessException(ErrorCode.ESCROW_INVALID_STATUS, "托管单缺少收款方");
        }
        walletService.releaseEscrowToPayee(escrow.getPayerUserId(), escrow.getPayeeUserId(), escrowId,
                escrow.getAmount(), escrow.getBusinessType(), transitionBusinessRef);
        transitionStatus(escrow, WalletEscrowStatus.LOCKED, WalletEscrowStatus.RELEASED);
    }

    /** 退款：CREATED|LOCKED → REFUNDED，资金退回付款方可用余额（ledger reason = ESCROW_REFUND）。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void refundEscrow(long escrowId, String transitionBusinessRef) {
        WalletEscrow escrow = requireLocked(escrowId);
        if (alreadyTransitioned(escrow, transitionBusinessRef, WalletLedgerReason.ESCROW_REFUND)) {
            return;
        }
        WalletEscrowStatus status = escrow.getStatus();
        if (status == WalletEscrowStatus.CREATED) {
            walletService.releaseHold(escrow.getPayerUserId(), escrowId, escrow.getAmount(), WalletLedgerReason.ESCROW_REFUND,
                    escrow.getBusinessType(), transitionBusinessRef);
        } else if (status == WalletEscrowStatus.LOCKED) {
            walletService.releaseEscrowToAvailable(escrow.getPayerUserId(), escrowId, escrow.getAmount(),
                    WalletLedgerReason.ESCROW_REFUND, escrow.getBusinessType(), transitionBusinessRef);
        } else {
            throw new BusinessException(ErrorCode.ESCROW_INVALID_STATUS);
        }
        transitionStatus(escrow, status, WalletEscrowStatus.REFUNDED);
    }

    /** 没收：LOCKED → FORFEITED，资金转给平台账本主体 sentinel（ledger reason = ESCROW_FORFEIT）。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void forfeitEscrow(long escrowId, String transitionBusinessRef) {
        WalletEscrow escrow = requireLocked(escrowId);
        if (alreadyTransitioned(escrow, transitionBusinessRef, WalletLedgerReason.ESCROW_FORFEIT)) {
            return;
        }
        guardStatus(escrow, WalletEscrowStatus.LOCKED);
        walletService.forfeitEscrowToPlatform(escrow.getPayerUserId(), escrowId, escrow.getAmount(),
                escrow.getBusinessType(), transitionBusinessRef);
        transitionStatus(escrow, WalletEscrowStatus.LOCKED, WalletEscrowStatus.FORFEITED);
    }

    /** 取消：CREATED → CANCELLED，资金退回付款方可用余额（ledger reason = ESCROW_CANCEL，与 refund 区分）。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void cancelEscrow(long escrowId, String transitionBusinessRef) {
        WalletEscrow escrow = requireLocked(escrowId);
        if (alreadyTransitioned(escrow, transitionBusinessRef, WalletLedgerReason.ESCROW_CANCEL)) {
            return;
        }
        guardStatus(escrow, WalletEscrowStatus.CREATED);
        walletService.releaseHold(escrow.getPayerUserId(), escrowId, escrow.getAmount(), WalletLedgerReason.ESCROW_CANCEL,
                escrow.getBusinessType(), transitionBusinessRef);
        transitionStatus(escrow, WalletEscrowStatus.CREATED, WalletEscrowStatus.CANCELLED);
    }

    /**
     * 过期结算 hook（本期不要求 scheduler）：检查 {@code expires_at <= now} 后走允许的 release/refund 终态。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void resolveExpiredEscrow(long escrowId, EscrowTimeoutResolution resolution,
                                     String transitionBusinessRef, Instant now) {
        WalletEscrow escrow = requireLocked(escrowId);
        if (escrow.getExpiresAt() == null || escrow.getExpiresAt().isAfter(now)) {
            throw new BusinessException(ErrorCode.ESCROW_INVALID_STATUS, "托管单未过期");
        }
        switch (resolution) {
            case RELEASE -> releaseEscrow(escrowId, transitionBusinessRef);
            case REFUND -> refundEscrow(escrowId, transitionBusinessRef);
        }
    }

    /**
     * 迁移幂等事实查询：同 transitionBusinessRef 是否已对该 escrow 产生目标终态 ledger。
     * ledger reason 编码目标终态，故 refund(ESCROW_REFUND) 与 cancel(ESCROW_CANCEL) 不可互相冒充。
     * 命中且参数一致返回 true；不命中返回 false；命中但参数不一致 reject。
     */
    private boolean alreadyTransitioned(WalletEscrow escrow, String transitionBusinessRef, WalletLedgerReason reason) {
        WalletLedgerEntry existing = walletService.findLedgerByOwnerAndBusinessRef(
                escrow.getPayerUserId(), transitionBusinessRef);
        if (existing == null) {
            return false;
        }
        if (existing.getAmount() != escrow.getAmount()
                || existing.getReason() != reason
                || existing.getBusinessType() != escrow.getBusinessType()
                || !Objects.equals(existing.getEscrowId(), escrow.getId())) {
            throw new BusinessException(ErrorCode.WALLET_DUPLICATE_BUSINESS_REF);
        }
        return true;
    }

    /** 重复 create businessRef 判等：payer/payee/amount/businessType 任一不同即 reject。 */
    private void ensureCreateMatches(WalletEscrow existing, long payerUserId, Long payeeUserId, long amount,
                                     WalletBusinessType businessType) {
        if (existing.getPayerUserId() != payerUserId
                || !Objects.equals(existing.getPayeeUserId(), payeeUserId)
                || existing.getAmount() != amount
                || existing.getBusinessType() != businessType) {
            throw new BusinessException(ErrorCode.WALLET_DUPLICATE_BUSINESS_REF);
        }
    }

    private WalletEscrow requireLocked(long escrowId) {
        WalletEscrow escrow = escrowMapper.findByIdForUpdate(escrowId);
        if (escrow == null) {
            throw new BusinessException(ErrorCode.ESCROW_NOT_FOUND);
        }
        return escrow;
    }

    private void guardStatus(WalletEscrow escrow, WalletEscrowStatus expected) {
        if (escrow.getStatus() != expected) {
            throw new BusinessException(ErrorCode.ESCROW_INVALID_STATUS);
        }
    }

    private void transitionStatus(WalletEscrow escrow, WalletEscrowStatus fromStatus, WalletEscrowStatus toStatus) {
        if (escrowMapper.transitionStatus(escrow.getId(), fromStatus, toStatus) == 0) {
            throw new BusinessException(ErrorCode.ESCROW_INVALID_STATUS);
        }
    }
}
