package com.tongji.wallet.service;

import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.common.id.IdNamespace;
import com.tongji.common.id.IdService;
import com.tongji.wallet.mapper.WalletEscrowMapper;
import com.tongji.wallet.model.WalletBusinessType;
import com.tongji.wallet.model.WalletEscrow;
import com.tongji.wallet.model.WalletEscrowStatus;
import com.tongji.wallet.model.WalletLedgerReason;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 通用托管生命周期服务。资金在创建时即落到付款方托管余额（hold → moveHoldToEscrow），
 * 状态迁移驱动终态转账；平台在放款时作为托管过桥对手方。
 * <p>
 * 终态：RELEASED（放款给收款方）/ REFUNDED（退给付款方）/ FORFEITED（没收给平台）/ CANCELLED（取消退回）。
 * 所有迁移对终态幂等：重复请求命中终态即直接返回，不重复转账。
 */
@Service
@RequiredArgsConstructor
public class WalletEscrowService {

    private final WalletEscrowMapper escrowMapper;
    private final WalletService walletService;
    private final IdService idService;

    /**
     * 创建托管：锁定付款方资金到托管余额，落 CREATED 单据。businessRef 唯一，重复返回既有单据。
     */
    @Transactional
    public WalletEscrow createEscrow(long payerUserId, Long payeeUserId, long amount,
                                     WalletBusinessType businessType, String businessRef, Instant expiresAt) {
        WalletEscrow existing = escrowMapper.findByBusinessRef(businessRef);
        if (existing != null) {
            return existing;
        }
        walletService.hold(payerUserId, amount, WalletLedgerReason.HOLD_RESERVE, businessRef + ":hold");
        long escrowId = idService.nextId(IdNamespace.ADMIN_OPERATION);
        walletService.moveHoldToEscrow(payerUserId, escrowId, amount, businessRef + ":commit");

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

    /**
     * 锁定：CREATED → LOCKED（仅状态迁移，资金不变）。
     */
    @Transactional
    public void lockEscrow(long escrowId) {
        WalletEscrow escrow = require(escrowId);
        if (escrow.getStatus() == WalletEscrowStatus.LOCKED) {
            return;
        }
        guard(escrow, WalletEscrowStatus.CREATED);
        transition(escrow, WalletEscrowStatus.LOCKED);
    }

    /**
     * 放款：LOCKED → RELEASED。付款方托管余额经平台桥接转入收款方可用余额。
     */
    @Transactional
    public void releaseEscrow(long escrowId) {
        WalletEscrow escrow = require(escrowId);
        if (escrow.getStatus() == WalletEscrowStatus.RELEASED) {
            return;
        }
        guard(escrow, WalletEscrowStatus.LOCKED);
        if (escrow.getPayeeUserId() == null) {
            throw new BusinessException(ErrorCode.ESCROW_INVALID_STATUS, "托管单缺少收款方");
        }
        walletService.forfeitEscrowToPlatform(escrow.getPayerUserId(), escrowId, escrow.getAmount(),
                escrow.getBusinessRef() + ":release-out");
        walletService.grant(escrow.getPayeeUserId(), escrow.getAmount(), WalletLedgerReason.ESCROW_RELEASE,
                escrow.getBusinessRef() + ":release-in");
        transition(escrow, WalletEscrowStatus.RELEASED);
    }

    /**
     * 退款：CREATED|LOCKED → REFUNDED。资金退回付款方可用余额。
     */
    @Transactional
    public void refundEscrow(long escrowId) {
        WalletEscrow escrow = require(escrowId);
        if (escrow.getStatus() == WalletEscrowStatus.REFUNDED) {
            return;
        }
        if (escrow.getStatus() != WalletEscrowStatus.CREATED && escrow.getStatus() != WalletEscrowStatus.LOCKED) {
            throw new BusinessException(ErrorCode.ESCROW_INVALID_STATUS);
        }
        walletService.releaseEscrowToAvailable(escrow.getPayerUserId(), escrowId, escrow.getAmount(),
                WalletLedgerReason.ESCROW_REFUND, escrow.getBusinessRef() + ":refund");
        transition(escrow, WalletEscrowStatus.REFUNDED);
    }

    /**
     * 没收：LOCKED → FORFEITED。资金转给平台账本主体。
     */
    @Transactional
    public void forfeitEscrow(long escrowId) {
        WalletEscrow escrow = require(escrowId);
        if (escrow.getStatus() == WalletEscrowStatus.FORFEITED) {
            return;
        }
        guard(escrow, WalletEscrowStatus.LOCKED);
        walletService.forfeitEscrowToPlatform(escrow.getPayerUserId(), escrowId, escrow.getAmount(),
                escrow.getBusinessRef() + ":forfeit");
        transition(escrow, WalletEscrowStatus.FORFEITED);
    }

    /**
     * 取消：CREATED → CANCELLED。资金退回付款方可用余额。
     */
    @Transactional
    public void cancelEscrow(long escrowId) {
        WalletEscrow escrow = require(escrowId);
        if (escrow.getStatus() == WalletEscrowStatus.CANCELLED) {
            return;
        }
        guard(escrow, WalletEscrowStatus.CREATED);
        walletService.releaseEscrowToAvailable(escrow.getPayerUserId(), escrowId, escrow.getAmount(),
                WalletLedgerReason.ESCROW_REFUND, escrow.getBusinessRef() + ":cancel");
        transition(escrow, WalletEscrowStatus.CANCELLED);
    }

    private WalletEscrow require(long escrowId) {
        WalletEscrow escrow = escrowMapper.findById(escrowId);
        if (escrow == null) {
            throw new BusinessException(ErrorCode.ESCROW_NOT_FOUND);
        }
        return escrow;
    }

    private void guard(WalletEscrow escrow, WalletEscrowStatus expected) {
        if (escrow.getStatus() != expected) {
            throw new BusinessException(ErrorCode.ESCROW_INVALID_STATUS);
        }
    }

    private void transition(WalletEscrow escrow, WalletEscrowStatus target) {
        escrow.setStatus(target);
        escrow.setUpdatedAt(Instant.now());
        escrowMapper.updateStatus(escrow);
    }
}
